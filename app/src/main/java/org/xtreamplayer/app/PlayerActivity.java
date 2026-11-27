package org.xtreamplayer.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Native full-screen player (Google's ExoPlayer / Media3).
 * Plays MKV, HEVC, HDR and Dolby / DTS audio (passed through to the TV or soundbar when they support it),
 * which the WebView's built-in <video> can't. Reports progress back to the page so resume, Trakt and
 * "next episode" keep working.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {

    private ExoPlayer player;
    private PlayerView view;
    private TextView titleView, subView;
    private LinearLayout topBar;
    private Button skipBtn, nextBtn;
    private JSONObject item;
    private String key = "";
    private boolean live, reported, nextShown;
    private long introS = -1, introE = -1;
    private boolean introAuto, introDone;
    private int retries;
    private final Handler h = new Handler(Looper.getMainLooper());
    private float matchedFps = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new PlayerView(this);
        view.setShowSubtitleButton(true);
        view.setShowNextButton(false);
        view.setShowPreviousButton(false);
        view.setControllerShowTimeoutMs(4000);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // title bar, shown together with the controls
        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setPadding(dp(32), dp(22), dp(32), dp(30));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xCC000000, 0x00000000});
        topBar.setBackground(g);
        titleView = new TextView(this);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(22);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        subView = new TextView(this);
        subView.setTextColor(0xFFCBD5E1);
        subView.setTextSize(15);
        topBar.addView(titleView);
        topBar.addView(subView);
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        skipBtn = pill("Skip intro ⏭");
        skipBtn.setOnClickListener(v -> skipIntro());
        nextBtn = pill("Next episode ▶");
        nextBtn.setOnClickListener(v -> finishWith("next"));
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(skipBtn);
        btns.addView(nextBtn);
        FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        bl.setMargins(0, 0, dp(40), dp(110));
        root.addView(btns, bl);
        setContentView(root);

        view.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) vis -> topBar.setVisibility(vis));
        load(getIntent());
        h.postDelayed(tick, 1000);
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        report("switch");
        load(i);
    }

    private Button pill(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE63B82F6);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(22), dp(10), dp(22), dp(10));
        b.setVisibility(View.GONE);
        b.setFocusable(true);
        b.setOnFocusChangeListener((v, f) -> { v.setScaleX(f ? 1.08f : 1f); v.setScaleY(f ? 1.08f : 1f); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), 0, 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void load(Intent i) {
        try { item = new JSONObject(i.getStringExtra("item")); } catch (Exception e) { finish(); return; }
        key = item.optString("key", "");
        live = item.optBoolean("live", false);
        reported = false; nextShown = false; introDone = false; retries = 0;
        titleView.setText(item.optString("title", ""));
        subView.setText(item.optString("sub", ""));
        JSONObject intro = item.optJSONObject("intro");
        introS = intro == null ? -1 : intro.optLong("s", -1) * 1000;
        introE = intro == null ? -1 : intro.optLong("e", -1) * 1000;
        introAuto = intro != null && intro.optBoolean("auto", false);
        skipBtn.setVisibility(View.GONE);
        nextBtn.setVisibility(View.GONE);
        nextBtn.setText(item.optString("next", "").isEmpty() ? "Next episode ▶" : "Next: " + item.optString("next") + " ▶");

        if (player != null) { player.release(); player = null; }

        Map<String, String> headers = new HashMap<>();
        JSONObject hj = item.optJSONObject("headers");
        if (hj != null) for (Iterator<String> it = hj.keys(); it.hasNext(); ) { String k = it.next(); headers.put(k, hj.optString(k)); }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(item.optString("ua", MainActivity.PROVIDER_UA))
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(30000)
                .setDefaultRequestProperties(headers);
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http));
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        player = new ExoPlayer.Builder(this, rf)
                .setMediaSourceFactory(msf)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(30_000)
                .build();
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        String lang = item.optString("lang", "en");
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setPreferredAudioLanguage(lang)
                .setPreferredTextLanguage(item.optBoolean("subsOn", false) ? lang : null)
                .build());
        view.setPlayer(player);

        String url = item.optString("url");
        if (!item.optString("recId").isEmpty()) {          // a recording on this device
            java.io.File f = Recorder.get(this).fileFor(item.optString("recId"));
            if (f != null) url = Uri.fromFile(f).toString();
        }
        if (!item.optString("dlId").isEmpty()) {           // a download on this device
            try { java.io.File f = NativeExtras.fileFor(this, Long.parseLong(item.optString("dlId"))); if (f != null) url = Uri.fromFile(f).toString(); } catch (Exception ignored) { }
        }
        MediaItem.Builder mb = new MediaItem.Builder().setUri(url)
                .setMediaMetadata(new MediaMetadata.Builder().setTitle(item.optString("title")).setSubtitle(item.optString("sub")).build());
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8") || "m3u8".equals(item.optString("type"))) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if (lower.matches(".*\\.ts(\\?.*)?$") || "ts".equals(item.optString("type"))) mb.setMimeType(MimeTypes.VIDEO_MP2T);
        JSONArray subs = item.optJSONArray("subs");
        if (subs != null && subs.length() > 0) {
            List<MediaItem.SubtitleConfiguration> list = new ArrayList<>();
            for (int k = 0; k < subs.length() && k < 12; k++) {
                JSONObject s = subs.optJSONObject(k);
                if (s == null || s.optString("url").isEmpty()) continue;
                String su = s.optString("url");
                list.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(su))
                        .setMimeType(su.toLowerCase().contains(".vtt") ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(s.optString("lang", "und"))
                        .setLabel(s.optString("label", s.optString("lang")))
                        .setSelectionFlags(s.optBoolean("on", false) ? C.SELECTION_FLAG_DEFAULT : 0)
                        .build());
            }
            mb.setSubtitleConfigurations(list);
        }
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int s) {
                if (s == Player.STATE_ENDED) finishWith(live ? "ended" : (item.optString("next", "").isEmpty() ? "ended" : "next"));
                if (s == Player.STATE_READY) { retries = 0; matchFrameRate(); }
            }
            @Override public void onPlayerError(PlaybackException e) {
                if (live && retries < 30) {   // live streams drop: reconnect quietly
                    retries++;
                    h.postDelayed(() -> { if (player != null) { player.prepare(); player.setPlayWhenReady(true); } }, Math.min(5000, 800L * retries));
                    return;
                }
                if (!live && retries < 2) {
                    retries++;
                    long pos = player.getCurrentPosition();
                    h.postDelayed(() -> { if (player != null) { player.seekTo(pos); player.prepare(); player.setPlayWhenReady(true); } }, 1200);
                    return;
                }
                Toast.makeText(PlayerActivity.this, "Can't play this here (" + e.getErrorCodeName() + ")", Toast.LENGTH_LONG).show();
                finishWith("error:" + e.getErrorCodeName());
            }
            @Override public void onVideoSizeChanged(VideoSize v) { matchFrameRate(); }
        });
        long start = item.optLong("start", 0) * 1000;
        player.setMediaItem(mb.build(), live ? C.TIME_UNSET : start);
        player.prepare();
        player.setPlayWhenReady(true);
        view.showController();
        view.requestFocus();
    }

    /** Switch the TV to the video's frame rate (24p films look smoother), if the display supports it. */
    private void matchFrameRate() {
        if (!item.optBoolean("matchFps", true) || player == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        Format f = player.getVideoFormat();
        if (f == null || f.frameRate <= 0 || Math.abs(f.frameRate - matchedFps) < 0.01) return;
        matchedFps = f.frameRate;
        Display d = getWindowManager().getDefaultDisplay();
        Display.Mode cur = d.getMode();
        Display.Mode best = null;
        for (Display.Mode m : d.getSupportedModes()) {
            if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
            float r = m.getRefreshRate();
            for (int mult = 1; mult <= 3; mult++) {
                if (Math.abs(r - f.frameRate * mult) < 0.05f && (best == null || r < best.getRefreshRate())) best = m;
            }
        }
        if (best != null && best.getModeId() != cur.getModeId()) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.preferredDisplayModeId = best.getModeId();
            getWindow().setAttributes(lp);
        }
    }

    private final Runnable tick = new Runnable() {
        int n = 0;
        @Override public void run() {
            if (player != null) {
                long pos = player.getCurrentPosition(), dur = player.getDuration();
                // skip intro
                boolean inIntro = introS >= 0 && introE > introS && pos >= introS && pos < introE - 1000;
                if (inIntro && introAuto && !introDone) { introDone = true; skipIntro(); }
                else if (inIntro && skipBtn.getVisibility() != View.VISIBLE) { skipBtn.setVisibility(View.VISIBLE); skipBtn.requestFocus(); }
                else if (!inIntro && skipBtn.getVisibility() == View.VISIBLE) skipBtn.setVisibility(View.GONE);
                // next episode, in the last 40 seconds
                if (!live && !item.optString("next", "").isEmpty() && dur > 0 && dur != C.TIME_UNSET && dur - pos < 40_000 && !nextShown) {
                    nextShown = true; nextBtn.setVisibility(View.VISIBLE); nextBtn.requestFocus();
                }
                if (++n % 15 == 0) progress();
            }
            h.postDelayed(this, 1000);
        }
    };

    private void skipIntro() {
        if (player != null && introE > 0) player.seekTo(introE);
        skipBtn.setVisibility(View.GONE);
        view.requestFocus();
    }

    private void progress() {
        if (player == null || live) return;
        MainActivity.js("window.xpNative && xpNative('progress'," + JSONObject.quote(key) + "," + player.getCurrentPosition() / 1000 + "," + Math.max(0, player.getDuration()) / 1000 + ")");
    }

    private void report(String how) {
        if (reported || player == null) return;
        reported = true;
        long dur = player.getDuration();
        MainActivity.js("window.xpNative && xpNative(" + JSONObject.quote(how) + "," + JSONObject.quote(key) + "," + player.getCurrentPosition() / 1000 + "," + (dur == C.TIME_UNSET ? 0 : dur / 1000) + ")");
    }

    private void finishWith(String how) {
        report(how);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible()
                && skipBtn.getVisibility() != View.VISIBLE && nextBtn.getVisibility() != View.VISIBLE) {
            if (live && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP)) { report("zap:1"); reported = false; return true; }
            if (live && (k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN)) { report("zap:-1"); reported = false; return true; }
            if (!live && k == KeyEvent.KEYCODE_DPAD_LEFT && player != null) { player.seekTo(Math.max(0, player.getCurrentPosition() - 10_000)); view.showController(); return true; }
            if (!live && k == KeyEvent.KEYCODE_DPAD_RIGHT && player != null) { player.seekTo(player.getCurrentPosition() + 30_000); view.showController(); return true; }
        }
        if (k == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP && view.isControllerFullyVisible() && player != null && player.isPlaying()) {
            view.hideController();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public void onBackPressed() { finishWith("stop"); }

    @Override
    protected void onResume() { super.onResume(); NativeExtras.top = this; }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
        if (isFinishing()) report("stop");
        else progress();
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        report("stop");
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
