package org.xtreamplayer.app;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records live channels, scheduled programmes and catch-up programmes to the device.
 * Same behaviour and JSON shape as recorder.py on the Mac, so the player page works with both.
 */
public class Recorder {

    static final String DRIVE_PKG = "com.google.android.apps.docs";
    private static Recorder instance;

    public static synchronized Recorder get(Context c) {
        if (instance == null) instance = new Recorder(c.getApplicationContext());
        return instance;
    }

    /* ---------------- model ---------------- */

    static class Item {
        String id, title, channel, streamId, kind, url, alt, status, path, file, error, rule;
        JSONObject compress;
        double progStart, start, stop, created, started, ended;
        long bytes;
        boolean userStopped;

        JSONObject toJson(boolean full) throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id); o.put("title", title); o.put("channel", channel);
            o.put("stream_id", streamId); o.put("kind", kind); o.put("status", status);
            if (progStart > 0) o.put("prog_start", progStart);
            o.put("start", start);
            if (stop > 0) o.put("stop", stop);
            o.put("created", created); o.put("bytes", bytes);
            if (path != null) o.put("path", path);
            if (file != null) o.put("file", file);
            if (error != null) o.put("error", error);
            if (started > 0) o.put("started", started);
            if (ended > 0) o.put("ended", ended);
            if (rule != null) o.put("rule", rule);
            if (compress != null) o.put("compress", new JSONObject(compress.toString()));
            if (full) { o.put("url", url); if (alt != null) o.put("alt", alt); o.put("user_stopped", userStopped); }
            return o;
        }

        static Item fromJson(JSONObject o) {
            Item i = new Item();
            i.id = o.optString("id"); i.title = o.optString("title", "Recording"); i.channel = o.optString("channel", "");
            i.streamId = o.optString("stream_id", ""); i.kind = o.optString("kind", "live");
            i.url = o.optString("url"); i.alt = o.has("alt") && !o.isNull("alt") ? o.optString("alt") : null;
            i.status = o.optString("status", "scheduled");
            i.path = o.has("path") ? o.optString("path") : null; i.file = o.has("file") ? o.optString("file") : null;
            i.error = o.has("error") ? o.optString("error") : null;
            i.progStart = o.optDouble("prog_start", 0); i.start = o.optDouble("start", 0); i.stop = o.optDouble("stop", 0);
            i.created = o.optDouble("created", 0); i.started = o.optDouble("started", 0); i.ended = o.optDouble("ended", 0);
            i.bytes = o.optLong("bytes", 0); i.userStopped = o.optBoolean("user_stopped", false);
            i.rule = o.has("rule") ? o.optString("rule") : null;
            i.compress = o.optJSONObject("compress");
            return i;
        }

        boolean pending() {
            String cs = compress == null ? "" : compress.optString("state");
            return "scheduled".equals(status) || "recording".equals(status) || "processing".equals(status) || "queued".equals(cs) || "running".equals(cs);
        }
    }

    private final Context ctx;
    private final File stateFile;
    private final List<Item> items = new ArrayList<>();
    private final List<JSONObject> rules = new ArrayList<>();
    private volatile long rulesChecked = 0;
    private String compressLevel = "off";
    private final ExecutorService compressor = Executors.newSingleThreadExecutor();
    private final Map<String, AtomicBoolean> compressCancel = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> stops = new ConcurrentHashMap<>();

    private Recorder(Context c) {
        ctx = c;
        stateFile = new File(c.getFilesDir(), "recordings.json");
        load();
        List<Item> resume = new ArrayList<>();
        for (Item i : items) {
            String cs = i.compress == null ? "" : i.compress.optString("state");
            if ("queued".equals(cs) || "running".equals(cs)) resume.add(i);
            if ("recording".equals(i.status) || "processing".equals(i.status)) {
                i.status = i.bytes > 0 ? "stopped" : "failed";
                if (i.error == null) i.error = "The app was closed during the recording";
            }
        }
        save();
        for (Item i : resume) queueCompress(i, i.compress.optString("level", "small"));
    }

    static double now() { return System.currentTimeMillis() / 1000.0; }

    /* ---------------- persistence ---------------- */

    private synchronized void load() {
        try (FileInputStream in = new FileInputStream(stateFile)) {
            JSONObject root = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            JSONArray a = root.getJSONArray("items");
            for (int k = 0; k < a.length(); k++) items.add(Item.fromJson(a.getJSONObject(k)));
            compressLevel = root.optString("compress", "off");
            JSONArray r = root.optJSONArray("rules");
            if (r != null) for (int k = 0; k < r.length(); k++) rules.add(r.getJSONObject(k));
        } catch (Exception ignored) { }
    }

    synchronized void save() {
        try {
            JSONArray a = new JSONArray();
            for (Item i : items) a.put(i.toJson(true));
            File tmp = new File(stateFile.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                JSONArray r = new JSONArray();
                for (JSONObject o : rules) r.put(o);
                out.write(new JSONObject().put("items", a).put("rules", r).put("compress", compressLevel).toString().getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(stateFile);
        } catch (Exception ignored) { }
    }

    /* ---------------- api ---------------- */

    File recDir() {
        File base = ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = new File(ctx.getFilesDir(), "movies");
        File d = new File(base, "Xtream Recordings");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    boolean driveInstalled() {
        try { ctx.getPackageManager().getPackageInfo(DRIVE_PKG, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    synchronized JSONObject status() throws Exception {
        JSONArray a = new JSONArray();
        for (Item i : items) {
            if ("recording".equals(i.status) && i.path != null) i.bytes = new File(i.path).length();
            a.put(i.toJson(false));
        }
        return new JSONObject()
                .put("ok", true).put("platform", "android").put("now", now())
                .put("dir", recDir().getAbsolutePath()).put("mode", "local").put("convert", false).put("ffmpeg", false)
                .put("compress", compressLevel)
                .put("compressor", new JSONObject().put("available", true).put("engine", "android").put("hw", true))
                .put("drive", new JSONObject().put("available", driveInstalled()))
                .put("items", a).put("rules", rulesPublic());
    }

    /* ---------------- compression (phone's video chip) ---------------- */

    synchronized void setCompress(String level) {
        if ("off".equals(level) || "balanced".equals(level) || "small".equals(level) || "smallest".equals(level)) { compressLevel = level; save(); }
    }

    String compressLevel() { return compressLevel; }

    synchronized Item compressNow(String id, String level) throws Exception {
        Item i = find(id);
        if (i == null || fileFor(id) == null) throw new IllegalArgumentException("Recording not found");
        String cs = i.compress == null ? "" : i.compress.optString("state");
        if ("queued".equals(cs) || "running".equals(cs)) return i;
        if (!"balanced".equals(level) && !"small".equals(level) && !"smallest".equals(level)) level = "off".equals(compressLevel) ? "small" : compressLevel;
        queueCompress(i, level);
        putC(i, "manual", true);
        return i;
    }

    synchronized boolean cancelCompress(String id) {
        Item i = find(id);
        if (i == null || i.compress == null) return false;
        AtomicBoolean c = compressCancel.get(id);
        if (c != null) c.set(true);
        try { i.compress.put("state", "cancelled"); } catch (Exception ignored) { }
        save();
        return true;
    }

    private synchronized void queueCompress(Item i, String level) {
        try { i.compress = new JSONObject().put("state", "queued").put("level", level).put("progress", 0); } catch (Exception ignored) { }
        AtomicBoolean cancel = new AtomicBoolean(false);
        compressCancel.put(i.id, cancel);
        save();
        compressor.submit(() -> runCompress(i, cancel));
    }

    private void runCompress(Item it, AtomicBoolean cancel) {
        File src;
        String level;
        synchronized (this) {
            if (it.compress == null || !"queued".equals(it.compress.optString("state"))) return;
            src = fileFor(it.id);
            level = it.compress.optString("level", "small");
            if (src == null) { putC(it, "state", "failed"); putC(it, "error", "File missing"); save(); return; }
            putC(it, "state", "running"); putC(it, "before", src.length()); save();
        }
        String base = src.getName().replaceAll("\\.(ts|mp4)$", "");
        File tmp = new File(src.getParentFile(), base + ".compressing.mp4");
        double expect = it.ended > 0 && it.started > 0 ? it.ended - it.started : 0;
        long srcDur = Compressor.durationUs(src);
        if (srcDur > 0) expect = srcDur / 1e6;
        try {
            String used = Compressor.transcode(src, tmp, level, expect, cancel, f -> { synchronized (Recorder.this) { putC(it, "progress", Math.round(f * 1000) / 1000.0); } });
            long newDur = Compressor.durationUs(tmp);
            long before = src.length(), after = tmp.length();
            if (expect > 0 && newDur > 0 && newDur / 1e6 < expect * 0.9) throw new IOException("the compressed file came out shorter than the original");
            if (after >= before * 0.97) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                synchronized (this) { putC(it, "state", "skipped"); putC(it, "error", "already compact, so the original was kept"); save(); }
            } else {
                File fin = new File(src.getParentFile(), base + ".mp4");
                //noinspection ResultOfMethodCallIgnored
                src.delete();
                if (!tmp.renameTo(fin)) throw new IOException("could not replace the original");
                synchronized (this) {
                    it.path = fin.getAbsolutePath(); it.file = fin.getName(); it.bytes = after;
                    putC(it, "state", "done"); putC(it, "progress", 1); putC(it, "after", after); putC(it, "codec", used);
                    save();
                }
            }
        } catch (Compressor.Cancelled e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        } catch (Throwable e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            synchronized (this) {
                putC(it, "state", "failed");
                putC(it, "error", e.getMessage() == null ? e.toString() : e.getMessage());
                putC(it, "progress", 0);
                save();
            }
        }
        compressCancel.remove(it.id);
        if (it.compress != null && !it.compress.optBoolean("manual")) RecordingService.notifyFinished(ctx, it);
    }

    private static void putC(Item it, String k, Object v) {
        try { if (it.compress != null) it.compress.put(k, v); } catch (Exception ignored) { }
    }

    /* ---------------- series recording rules ---------------- */

    private synchronized JSONArray rulesPublic() throws Exception {
        JSONArray out = new JSONArray();
        for (JSONObject r : rules) {
            JSONObject o = new JSONObject();
            for (String k : new String[]{"id", "title", "channel", "stream_id", "created", "last_check", "error"}) if (r.has(k)) o.put(k, r.get(k));
            out.put(o);
        }
        return out;
    }

    synchronized JSONObject addRule(JSONObject d) throws Exception {
        for (String k : new String[]{"title", "epg_url", "ts_url"}) if (d.optString(k).isEmpty()) throw new IllegalArgumentException("missing " + k);
        JSONObject r = new JSONObject();
        r.put("id", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        r.put("title", trim(d.getString("title"), 200)).put("channel", d.optString("channel", "")).put("stream_id", d.optString("stream_id", ""));
        r.put("epg_url", d.getString("epg_url")).put("ts_url", d.getString("ts_url")).put("m3u8_url", d.optString("m3u8_url", ""));
        r.put("pre", d.optDouble("pre", 60)).put("post", d.optDouble("post", 180)).put("created", now()).put("skip", new JSONArray());
        rules.add(r);
        save();
        new Thread(() -> checkRule(r), "rule-check").start();
        return r;
    }

    synchronized boolean delRule(String id) {
        JSONObject found = null;
        for (JSONObject r : rules) if (id.equals(r.optString("id"))) found = r;
        if (found == null) return false;
        rules.remove(found);
        List<Item> drop = new ArrayList<>();
        for (Item i : items) if ("scheduled".equals(i.status) && id.equals(i.rule)) drop.add(i);
        items.removeAll(drop);
        save();
        return true;
    }

    synchronized boolean hasRules() { return !rules.isEmpty(); }

    private static String b64(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            String out = new String(Base64.decode(s, Base64.DEFAULT), StandardCharsets.UTF_8);
            return out.contains("\uFFFD") ? s : out;
        } catch (IllegalArgumentException e) { return s; }
    }

    private void checkRule(JSONObject r) {
        try {
            HttpURLConnection c = open(r.getString("epg_url"));
            String body;
            try (InputStream in = c.getInputStream()) { body = new String(readAll(in), StandardCharsets.UTF_8); } finally { c.disconnect(); }
            JSONArray list = new JSONObject(body).optJSONArray("epg_listings");
            double n = now();
            String want = r.getString("title").trim().toLowerCase(Locale.ROOT);
            if (list != null) for (int k = 0; k < list.length(); k++) {
                JSONObject e = list.getJSONObject(k);
                String title = b64(e.optString("title"));
                double start = e.optDouble("start_timestamp", 0), stop = e.optDouble("stop_timestamp", e.optDouble("end_timestamp", 0));
                if (start <= 0 || stop <= n || start > n + 8 * 86400 || !title.trim().toLowerCase(Locale.ROOT).equals(want)) continue;
                synchronized (this) {
                    boolean exists = false;
                    JSONArray skip = r.optJSONArray("skip");
                    if (skip != null) for (int j = 0; j < skip.length(); j++) if (skip.optDouble(j) == start) exists = true;
                    for (Item i : items) if (i.streamId.equals(r.optString("stream_id")) && i.progStart == start) exists = true;
                    if (exists) continue;
                    JSONObject d = new JSONObject().put("title", title).put("channel", r.optString("channel")).put("stream_id", r.optString("stream_id"))
                            .put("prog_start", start).put("kind", "live").put("url", r.getString("ts_url"))
                            .put("start", Math.max(n, start - r.optDouble("pre", 60))).put("stop", stop + r.optDouble("post", 180));
                    if (!r.optString("m3u8_url").isEmpty()) d.put("alt", r.optString("m3u8_url"));
                    Item it = add(d);
                    it.rule = r.optString("id");
                }
            }
            synchronized (this) { r.put("last_check", n); r.remove("error"); save(); }
        } catch (Exception e) {
            synchronized (this) { try { r.put("error", "Could not read the guide: " + e.getMessage()); r.put("last_check", now()); } catch (Exception ignored) { } }
        }
    }

    synchronized Item add(JSONObject d) throws Exception {
        Item i = new Item();
        i.id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        i.title = trim(d.optString("title", "Recording"), 200);
        i.channel = trim(d.optString("channel", ""), 120);
        i.streamId = d.optString("stream_id", "");
        i.kind = "catchup".equals(d.optString("kind")) ? "catchup" : "live";
        i.url = d.getString("url");
        i.alt = d.has("alt") && !d.isNull("alt") ? d.optString("alt") : null;
        i.progStart = d.optDouble("prog_start", 0);
        i.start = d.optDouble("start", now());
        i.stop = d.optDouble("stop", 0);
        i.created = now();
        i.status = "scheduled";
        if (!(i.url.startsWith("http://") || i.url.startsWith("https://"))) throw new IllegalArgumentException("bad url");
        if ("live".equals(i.kind) && (i.stop <= 0 || i.stop <= now())) throw new IllegalArgumentException("This programme has already finished");
        items.add(i);
        save();
        return i;
    }

    synchronized Item find(String id) {
        for (Item i : items) if (i.id.equals(id)) return i;
        return null;
    }

    synchronized boolean hasPending() {
        for (Item i : items) if (i.pending()) return true;
        return false;
    }

    synchronized String summary() {
        int rec = 0; Item next = null;
        for (Item i : items) {
            if ("recording".equals(i.status)) rec++;
            if ("scheduled".equals(i.status) && (next == null || i.start < next.start)) next = i;
        }
        if (rec > 0) {
            for (Item i : items) if ("recording".equals(i.status)) return (rec > 1 ? rec + " recordings · " : "Recording: ") + i.title;
        }
        for (Item i : items) if (i.compress != null && "running".equals(i.compress.optString("state")))
            return "Making smaller: " + i.title + " " + Math.round(i.compress.optDouble("progress", 0) * 100) + "%";
        if (next != null) return "Next recording: " + next.title + " at " + new SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(new Date((long) (next.start * 1000)));
        return "Finishing…";
    }

    boolean stop(String id) {
        Item i = find(id);
        if (i == null) return false;
        synchronized (this) {
            if ("scheduled".equals(i.status)) {
                items.remove(i);
                if (i.rule != null) for (JSONObject r : rules) if (i.rule.equals(r.optString("id"))) {
                    JSONArray skip = r.optJSONArray("skip");
                    if (skip == null) { skip = new JSONArray(); try { r.put("skip", skip); } catch (Exception ignored) { } }
                    try { skip.put(i.progStart); } catch (Exception ignored) { }
                }
                save();
                return true;
            }
        }
        AtomicBoolean s = stops.get(id);
        if (s != null) s.set(true);
        return true;
    }

    boolean delete(String id) {
        Item i = find(id);
        if (i == null) return false;
        if ("recording".equals(i.status)) { stop(id); try { Thread.sleep(500); } catch (InterruptedException ignored) { } }
        File f = fileFor(id);
        //noinspection ResultOfMethodCallIgnored
        if (f != null) f.delete();
        synchronized (this) { items.remove(i); save(); }
        return true;
    }

    File fileFor(String id) {
        Item i = find(id);
        if (i == null || i.path == null) return null;
        File f = new File(i.path);
        try {
            if (!f.getCanonicalPath().startsWith(recDir().getCanonicalPath() + File.separator)) return null;
        } catch (IOException e) { return null; }
        return f.isFile() ? f : null;
    }

    static String mimeFor(String name) { return name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4") ? "video/mp4" : "video/mp2t"; }

    /** Intent that sends a finished recording to the Google Drive app (or any app if Drive isn't installed). */
    Intent driveIntent(String id) {
        File f = fileFor(id);
        if (f == null) return null;
        Uri u = RecFileProvider.uriFor(f);
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("video/*");
        s.putExtra(Intent.EXTRA_STREAM, u);
        s.putExtra(Intent.EXTRA_TITLE, f.getName());
        s.putExtra(Intent.EXTRA_SUBJECT, f.getName());
        s.setClipData(ClipData.newRawUri(f.getName(), u));
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (driveInstalled()) { s.setPackage(DRIVE_PKG); s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); return s; }
        Intent c = Intent.createChooser(s, "Save recording to…");
        c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return c;
    }

    Intent viewIntent(String id) {
        File f = fileFor(id);
        if (f == null) return null;
        Uri u = RecFileProvider.uriFor(f);
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setDataAndType(u, "video/*");
        v.setClipData(ClipData.newRawUri(f.getName(), u));
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent c = Intent.createChooser(v, "Play with");
        c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return c;
    }

    void launch(Intent i) throws ActivityNotFoundException {
        if (i == null) throw new IllegalArgumentException("Recording not found");
        ctx.startActivity(i);
    }

    /* ---------------- scheduler (called every 2 s by RecordingService) ---------------- */

    void tick() {
        if (System.currentTimeMillis() - rulesChecked > 15 * 60 * 1000L) {
            rulesChecked = System.currentTimeMillis();
            List<JSONObject> copy;
            synchronized (this) { copy = new ArrayList<>(rules); }
            if (!copy.isEmpty()) new Thread(() -> { for (JSONObject r : copy) checkRule(r); }, "rules").start();
        }
        List<Item> due = new ArrayList<>();
        synchronized (this) {
            double n = now();
            for (Item i : items) {
                if (!"scheduled".equals(i.status) || i.start > n) continue;
                if ("live".equals(i.kind) && i.stop <= n) { i.status = "missed"; continue; }
                i.status = "recording";
                i.started = n;
                due.add(i);
            }
            if (!due.isEmpty()) save();
        }
        for (Item i : due) {
            AtomicBoolean stop = new AtomicBoolean(false);
            stops.put(i.id, stop);
            new Thread(() -> run(i, stop), "rec-" + i.id).start();
        }
    }

    /* ---------------- recording ---------------- */

    private static String safe(String s) {
        s = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]+", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "Recording" : trim(s, 100);
    }

    private static String trim(String s, int n) { return s.length() > n ? s.substring(0, n) : s; }

    private void run(Item it, AtomicBoolean stop) {
        String when = new SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(new Date((long) ((it.progStart > 0 ? it.progStart : it.start) * 1000)));
        String base = when + " " + safe(it.channel) + " - " + safe(it.title);
        File dir = recDir();
        File f = new File(dir, base + ".ts");
        for (int n = 2; f.exists(); n++) f = new File(dir, base + " (" + n + ").ts");
        synchronized (this) { it.path = f.getAbsolutePath(); it.file = f.getName(); save(); }
        String err = null;
        try (FileOutputStream out = new FileOutputStream(f, true)) {
            record(it, out, stop);
        } catch (Exception e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        long size = f.length();
        synchronized (this) {
            it.bytes = size;
            it.ended = now();
            if (size < 50 * 1024) {
                it.status = "failed";
                it.error = err != null ? err : "No video was received (channel offline or connection limit reached?)";
                //noinspection ResultOfMethodCallIgnored
                if (size == 0) f.delete();
            } else {
                it.status = (stop.get() || it.userStopped) ? "stopped" : "done";
                if (err != null) it.error = err;
            }
            save();
        }
        stops.remove(it.id);
        if ("failed".equals(it.status)) RecordingService.notifyFailed(ctx, it);
        else if (!"off".equals(compressLevel)) queueCompress(it, compressLevel);   // notifies when done
        else RecordingService.notifyFinished(ctx, it);
    }

    private static class NotTs extends IOException { NotTs() { super("not a TS stream"); } }

    private static class HttpError extends IOException {
        final int code;
        HttpError(int c) { super("Provider returned HTTP " + c); code = c; }
    }

    private void record(Item it, FileOutputStream out, AtomicBoolean stop) throws IOException {
        boolean live = "live".equals(it.kind);
        double until = live ? it.stop : 0;
        boolean useHls = false;
        int errors = 0;
        while (!stop.get()) {
            if (live && now() >= until) break;
            String url = (useHls && it.alt != null) ? it.alt : it.url;
            try {
                boolean done = fetch(url, it, out, stop, until);
                if (done || !live) break;
            } catch (NotTs e) {
                if (it.alt != null && !useHls) { useHls = true; continue; }
                throw new IOException("The provider did not send a video stream");
            } catch (HttpError e) {
                errors++;
                if (errors == 1 && it.alt != null && !useHls) { useHls = true; continue; }
                if (errors > 30 || (!live && errors > 5)) throw e;
            } catch (IOException e) {
                errors++;
                if (errors > 30 || (!live && errors > 5)) throw e;
            }
            if (!stop.get()) try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
        }
        if (stop.get()) it.userStopped = true;
    }

    static HttpURLConnection open(String url) throws IOException {
        URL cur = new URL(url);
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c = (HttpURLConnection) cur.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", MainActivity.PROVIDER_UA);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && loc != null) { cur = new URL(cur, loc); c.disconnect(); continue; }
            if (code >= 400) { c.disconnect(); throw new HttpError(code); }
            return c;
        }
        throw new IOException("Too many redirects");
    }

    /** Returns true when the recording is complete. */
    private boolean fetch(String url, Item it, FileOutputStream out, AtomicBoolean stop, double until) throws IOException {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[65536];
            int n = 0, r;
            while (n < 188 && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            int k = 0;
            while (k < n && Character.isWhitespace(buf[k])) k++;
            if (n - k >= 7 && new String(buf, k, 7, StandardCharsets.US_ASCII).equals("#EXTM3U")) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bo.write(buf, 0, n);
                bo.write(readAll(in));
                return hls(c.getURL().toString(), bo.toString("UTF-8"), it, out, stop, until);
            }
            if (n <= 0 || buf[0] != 0x47) throw new NotTs();
            out.write(buf, 0, n);
            long lastSave = System.currentTimeMillis();
            long written = n;
            while (!stop.get()) {
                if (until > 0 && now() >= until) return true;
                r = in.read(buf);
                if (r < 0) return until <= 0; // live dropped -> caller reconnects
                out.write(buf, 0, r);
                written += r;
                if (System.currentTimeMillis() - lastSave > 5000) {
                    synchronized (this) { it.bytes = new File(it.path).length(); }
                    lastSave = System.currentTimeMillis();
                }
            }
            return true;
        } finally {
            c.disconnect();
        }
    }

    private boolean hls(String url, String body, Item it, FileOutputStream out, AtomicBoolean stop, double until) throws IOException {
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> order = new ArrayDeque<>();
        String text = body;
        while (!stop.get()) {
            if (text == null) {
                HttpURLConnection c = open(url);
                try (InputStream in = c.getInputStream()) {
                    url = c.getURL().toString();
                    text = new String(readAll(in), StandardCharsets.UTF_8);
                } finally { c.disconnect(); }
            }
            String[] raw = text.split("\r?\n");
            text = null;
            List<String> lines = new ArrayList<>();
            for (String l : raw) if (!l.trim().isEmpty()) lines.add(l.trim());
            String variant = null;
            for (int k = 0; k < lines.size(); k++) {
                if (lines.get(k).startsWith("#EXT-X-STREAM-INF") && k + 1 < lines.size()) { variant = lines.get(k + 1); break; }
            }
            if (variant != null) { url = new URL(new URL(url), variant).toString(); continue; }
            double target = 6;
            boolean ended = false;
            List<String> segs = new ArrayList<>();
            for (String l : lines) {
                if (l.startsWith("#EXT-X-KEY") && !l.contains("METHOD=NONE")) throw new IOException("This stream is encrypted and can't be recorded");
                if (l.startsWith("#EXT-X-TARGETDURATION:")) { try { target = Double.parseDouble(l.substring(22).trim()); } catch (NumberFormatException ignored) { } }
                if (l.startsWith("#EXT-X-ENDLIST")) ended = true;
                if (!l.startsWith("#")) segs.add(new URL(new URL(url), l).toString());
            }
            for (String s : segs) {
                if (stop.get() || (until > 0 && now() >= until)) return true;
                if (seen.contains(s)) continue;
                HttpURLConnection c = open(s);
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                } finally { c.disconnect(); }
                seen.add(s); order.add(s);
                if (order.size() > 3000) seen.remove(order.poll());
                synchronized (this) { it.bytes = new File(it.path).length(); }
            }
            if (ended || (until > 0 && now() >= until)) return true;
            try { Thread.sleep((long) (Math.max(1, target / 2) * 1000)); } catch (InterruptedException ignored) { }
        }
        return true;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }
}
