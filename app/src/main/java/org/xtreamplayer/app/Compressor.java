package org.xtreamplayer.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes a recording smaller using the phone's own video chip (MediaCodec):
 * decodes the video straight onto the encoder's input surface and re-encodes it at a lower
 * bitrate (HEVC where the phone supports it, otherwise H.264). AAC audio is copied as-is;
 * other audio formats are re-encoded to AAC. Output is an MP4 file.
 */
final class Compressor {

    interface Progress { void update(float fraction); }

    static final class Cancelled extends IOException { Cancelled() { super("cancelled"); } }

    private static final String AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final String AAC = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final long TIMEOUT = 10_000;

    private final File in, out;
    private final String level;
    private final double expectSec;
    private final AtomicBoolean cancel;
    private final Progress progress;

    private MediaExtractor vex, aex;
    private MediaCodec dec, enc, adec, aenc;
    private MediaMuxer mux;
    private int vTrack = -1, aTrack = -1;
    private boolean muxStarted, hasAudio, copyAudio;
    private long baseUs = -1, totalUs;
    private final List<Object[]> pending = new ArrayList<>();
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final ByteBuffer copyBuf = ByteBuffer.allocate(512 * 1024);
    private String usedCodec = "";

    private Compressor(File in, File out, String level, double expectSec, AtomicBoolean cancel, Progress progress) {
        this.in = in; this.out = out; this.level = level; this.expectSec = expectSec; this.cancel = cancel; this.progress = progress;
    }

    /** Returns a short description of what was used, e.g. "HEVC 1800 kbps". */
    static String transcode(File in, File out, String level, double expectSec, AtomicBoolean cancel, Progress progress) throws IOException {
        Compressor c = new Compressor(in, out, level, expectSec, cancel, progress);
        try {
            c.run();
            return c.usedCodec;
        } finally {
            c.releaseAll();
        }
    }

    static long durationUs(File f) {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(f.getPath());
            long d = 0;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat fm = ex.getTrackFormat(i);
                if (fm.containsKey(MediaFormat.KEY_DURATION)) d = Math.max(d, fm.getLong(MediaFormat.KEY_DURATION));
            }
            return d;
        } catch (Exception e) {
            return 0;
        } finally {
            ex.release();
        }
    }

    private static String findEncoder(String mime, int w, int h) {
        MediaFormat probe = MediaFormat.createVideoFormat(mime, w, h);
        probe.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        try { return new MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(probe); }
        catch (Exception e) { return null; }
    }

    private void run() throws IOException {
        vex = new MediaExtractor();
        vex.setDataSource(in.getPath());
        int vi = -1, ai = -1;
        for (int i = 0; i < vex.getTrackCount(); i++) {
            String m = vex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (m == null) continue;
            if (m.startsWith("video/") && vi < 0) vi = i;
            if (m.startsWith("audio/") && ai < 0) ai = i;
        }
        if (vi < 0) throw new IOException("No video found in this recording");
        MediaFormat vfmt = vex.getTrackFormat(vi);
        vex.selectTrack(vi);
        int w = vfmt.getInteger(MediaFormat.KEY_WIDTH), h = vfmt.getInteger(MediaFormat.KEY_HEIGHT);
        double secs = expectSec > 0 ? expectSec : (vfmt.containsKey(MediaFormat.KEY_DURATION) ? vfmt.getLong(MediaFormat.KEY_DURATION) / 1e6 : 0);
        totalUs = (long) (secs * 1e6);

        // ----- choose codec and bitrate -----
        String mime = AVC;
        String name = null;
        if (!"balanced".equals(level)) { name = findEncoder(HEVC, w, h); if (name != null) mime = HEVC; }
        if (name == null) { mime = AVC; name = findEncoder(AVC, w, h); }
        if (name == null) throw new IOException("This phone has no suitable video encoder");
        double srcKbps = secs > 0 ? in.length() * 8.0 / secs / 1000 - 160 : 4000;
        double factor = "balanced".equals(level) ? 0.55 : "small".equals(level) ? 0.40 : 0.30;
        if (AVC.equals(mime) && !"balanced".equals(level)) factor *= 1.35; // H.264 needs more bits than HEVC
        double cap = h >= 1000 ? 5000 : h >= 700 ? 3000 : 1500;
        cap *= "balanced".equals(level) ? 1.0 : "small".equals(level) ? 0.6 : 0.45;
        int kbps = (int) Math.max(450, Math.min(srcKbps * factor, cap));
        usedCodec = (HEVC.equals(mime) ? "HEVC " : "H.264 ") + kbps + " kbps";

        MediaFormat ef = MediaFormat.createVideoFormat(mime, w, h);
        ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        ef.setInteger(MediaFormat.KEY_BIT_RATE, kbps * 1000);
        int fps = 25;
        try { if (vfmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = Math.max(10, Math.min(60, vfmt.getInteger(MediaFormat.KEY_FRAME_RATE))); } catch (Exception ignored) { }
        ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        enc = MediaCodec.createByCodecName(name);
        enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface surface = enc.createInputSurface();
        enc.start();
        dec = MediaCodec.createDecoderByType(vfmt.getString(MediaFormat.KEY_MIME));
        dec.configure(vfmt, surface, null, 0);
        dec.start();

        mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // ----- audio: copy AAC, otherwise re-encode -----
        if (ai >= 0) {
            hasAudio = true;
            aex = new MediaExtractor();
            aex.setDataSource(in.getPath());
            aex.selectTrack(ai);
            MediaFormat afmt = aex.getTrackFormat(ai);
            String amime = afmt.getString(MediaFormat.KEY_MIME);
            if (AAC.equals(amime)) {
                try { aTrack = mux.addTrack(afmt); copyAudio = true; } catch (Exception e) { copyAudio = false; }
            }
            if (!copyAudio) {
                adec = MediaCodec.createDecoderByType(amime);
                adec.configure(afmt, null, null, 0);
                adec.start();
            }
        }
        baseUs = Math.max(0, vex.getSampleTime());

        boolean vInDone = false, decDone = false, vDone = false;
        boolean aInDone = false, adecDone = false, aDone = !hasAudio;
        long lastReport = 0;

        while (!vDone || !aDone) {
            if (cancel.get()) throw new Cancelled();

            // ---- video: extractor -> decoder ----
            if (!vInDone) {
                int i = dec.dequeueInputBuffer(TIMEOUT);
                if (i >= 0) {
                    ByteBuffer b = dec.getInputBuffer(i);
                    int n = vex.readSampleData(b, 0);
                    if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); vInDone = true; }
                    else { dec.queueInputBuffer(i, 0, n, vex.getSampleTime(), 0); vex.advance(); }
                }
            }
            // ---- video: decoder -> encoder surface ----
            if (!decDone) {
                int o = dec.dequeueOutputBuffer(info, TIMEOUT);
                if (o >= 0) {
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    dec.releaseOutputBuffer(o, info.size > 0 && info.presentationTimeUs >= baseUs);
                    if (eos) { enc.signalEndOfInputStream(); decDone = true; }
                }
            }
            // ---- video: encoder -> muxer ----
            while (!vDone) {
                int e = enc.dequeueOutputBuffer(info, decDone ? TIMEOUT : 0);
                if (e == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { vTrack = mux.addTrack(enc.getOutputFormat()); maybeStart(); continue; }
                if (e < 0) continue;
                ByteBuffer b = enc.getOutputBuffer(e);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && b != null) {
                    write(vTrack, b, info);
                    if (totalUs > 0 && info.presentationTimeUs - lastReport > 2_000_000) {
                        lastReport = info.presentationTimeUs;
                        progress.update((float) Math.min(0.99, (info.presentationTimeUs - baseUs) / (double) totalUs));
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) vDone = true;
                enc.releaseOutputBuffer(e, false);
            }

            // ---- audio ----
            if (!aDone && copyAudio) {
                if (!muxStarted) continue;      // wait for the video track before copying
                for (int k = 0; k < 4 && !aDone; k++) {
                    copyBuf.clear();
                    int n = aex.readSampleData(copyBuf, 0);
                    if (n < 0) { aDone = true; break; }
                    long t = aex.getSampleTime();
                    if (t >= baseUs) {
                        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                        bi.set(0, n, t - baseUs, (aex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        mux.writeSampleData(aTrack, copyBuf, bi);
                    }
                    aex.advance();
                }
            } else if (!aDone) {
                if (!aInDone) {
                    int i = adec.dequeueInputBuffer(0);
                    if (i >= 0) {
                        ByteBuffer b = adec.getInputBuffer(i);
                        int n = aex.readSampleData(b, 0);
                        if (n < 0) { adec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); aInDone = true; }
                        else { adec.queueInputBuffer(i, 0, n, aex.getSampleTime(), 0); aex.advance(); }
                    }
                }
                if (!adecDone) {
                    MediaCodec.BufferInfo ai2 = new MediaCodec.BufferInfo();
                    int o = adec.dequeueOutputBuffer(ai2, 0);
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat pf = adec.getOutputFormat();
                        MediaFormat af = MediaFormat.createAudioFormat(AAC, pf.getInteger(MediaFormat.KEY_SAMPLE_RATE), pf.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                        af.setInteger(MediaFormat.KEY_BIT_RATE, "smallest".equals(level) ? 96_000 : 128_000);
                        aenc = MediaCodec.createEncoderByType(AAC);
                        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        aenc.start();
                    } else if (o >= 0) {
                        ByteBuffer pcm = adec.getOutputBuffer(o);
                        boolean eos = (ai2.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        if (aenc != null && pcm != null) feedPcm(pcm, ai2, eos);
                        adec.releaseOutputBuffer(o, false);
                        if (eos) adecDone = true;
                    }
                }
                if (aenc != null) aDone = drainAudioEncoder(false);
                else if (adecDone) aDone = true;
            }
        }
        if (!muxStarted) throw new IOException("Nothing was encoded");
        progress.update(1f);
    }

    private void feedPcm(ByteBuffer pcm, MediaCodec.BufferInfo src, boolean eos) throws IOException {
        MediaFormat pf = adec.getOutputFormat();
        int rate = pf.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = pf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        double usPerByte = 1e6 / (rate * ch * 2.0);
        pcm.position(src.offset);
        pcm.limit(src.offset + src.size);
        long t = src.presentationTimeUs;
        do {
            if (cancel.get()) throw new Cancelled();
            int i = aenc.dequeueInputBuffer(TIMEOUT);
            if (i < 0) { drainAudioEncoder(false); continue; }
            ByteBuffer dst = aenc.getInputBuffer(i);
            dst.clear();
            int n = Math.min(dst.remaining(), pcm.remaining());
            ByteBuffer slice = pcm.duplicate();
            slice.limit(pcm.position() + n);
            dst.put(slice);
            pcm.position(pcm.position() + n);
            boolean last = eos && !pcm.hasRemaining();
            aenc.queueInputBuffer(i, 0, n, t, last ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
            t += (long) (n * usPerByte);
        } while (pcm.hasRemaining());
    }

    /** Returns true once the audio encoder has finished. */
    private boolean drainAudioEncoder(boolean block) throws IOException {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        while (true) {
            int e = aenc.dequeueOutputBuffer(bi, block ? TIMEOUT : 0);
            if (e == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { aTrack = mux.addTrack(aenc.getOutputFormat()); maybeStart(); continue; }
            if (e < 0) continue;
            ByteBuffer b = aenc.getOutputBuffer(e);
            if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) bi.size = 0;
            if (bi.size > 0 && b != null && bi.presentationTimeUs >= baseUs) write(aTrack, b, bi);
            boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            aenc.releaseOutputBuffer(e, false);
            if (eos) return true;
        }
    }

    private void maybeStart() {
        if (muxStarted || vTrack < 0 || (hasAudio && aTrack < 0 && (copyAudio || adec != null))) return;
        mux.start();
        muxStarted = true;
        for (Object[] p : pending) mux.writeSampleData((Integer) p[0], (ByteBuffer) p[1], (MediaCodec.BufferInfo) p[2]);
        pending.clear();
    }

    private void write(int track, ByteBuffer b, MediaCodec.BufferInfo bi) {
        MediaCodec.BufferInfo copy = new MediaCodec.BufferInfo();
        copy.set(0, bi.size, Math.max(0, bi.presentationTimeUs - baseUs), bi.flags & ~MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        b.position(bi.offset);
        b.limit(bi.offset + bi.size);
        if (muxStarted) { mux.writeSampleData(track, b, copy); return; }
        ByteBuffer keep = ByteBuffer.allocate(bi.size);   // hold until every track is known
        keep.put(b);
        keep.flip();
        pending.add(new Object[]{track, keep, copy});
    }

    private void releaseAll() {
        for (MediaCodec c : new MediaCodec[]{dec, enc, adec, aenc}) {
            if (c == null) continue;
            try { c.stop(); } catch (Exception ignored) { }
            try { c.release(); } catch (Exception ignored) { }
        }
        if (mux != null) {
            try { if (muxStarted) mux.stop(); } catch (Exception ignored) { }
            try { mux.release(); } catch (Exception ignored) { }
        }
        if (vex != null) vex.release();
        if (aex != null) aex.release();
    }
}
