package org.xtreamplayer.app;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.util.Rational;
import android.webkit.ValueCallback;
import android.app.UiModeManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.Manifest;
import org.json.JSONObject;
import java.io.File;
import java.io.RandomAccessFile;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PanGaze for Android.
 *
 * Loads the same single-file player (assets/index.html) in a WebView and answers
 * the page's "/proxy?url=..." requests natively, the same way server.py does on the Mac.
 * That removes browser CORS limits, so no separate server is needed.
 */
public class MainActivity extends Activity {

    static final String HOST = "appassets.androidplatform.net";
    static final String HOME = "https://" + HOST + "/index.html";
    static final String PROVIDER_UA = "VLC/3.0.20 LibVLC/3.0.20";
    static final Pattern URI_ATTR = Pattern.compile("URI=\"([^\"]+)\"");

    private FrameLayout root;
    private WebView web;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;
    private Chrome chrome;
    private static MainActivity instance;

    /** Runs JavaScript in the page (from any thread). */
    static void js(String code) {
        MainActivity a = instance;
        if (a == null) return;
        a.runOnUiThread(() -> { if (a.web != null) a.web.evaluateJavascript(code, null); });
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.parseColor("#151a21"));
        getWindow().setNavigationBarColor(Color.parseColor("#0e1116"));

        instance = this;
        root = new FrameLayout(this);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0e1116"));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); // channel logos / posters are often http://
        ws.setAllowFileAccess(false);
        ws.setUserAgentString(ws.getUserAgentString() + " XtreamPlayerAndroid/1.0" + (isTv() ? " XtreamPlayerTV" : ""));

        chrome = new Chrome();
        web.setWebViewClient(new Client());
        web.setWebChromeClient(chrome);

        if (saved != null) web.restoreState(saved);
        else web.loadUrl(HOME);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        if (Recorder.get(this).hasPending() || Recorder.get(this).hasRules()) RecordingService.ensure(this);
        if (saved == null) handleIntent(getIntent());
    }

    /* ---------------- recordings API (same as server.py's /rec/...) ---------------- */

    private WebResourceResponse json(int code, JSONObject o) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/json", "UTF-8", code, reason(code), h,
                new ByteArrayInputStream(o.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private WebResourceResponse recApi(String action, Uri u, Map<String, String> headers) {
        Recorder r = Recorder.get(this);
        try {
            String id = u.getQueryParameter("id");
            switch (action) {
                case "status": return json(200, r.status());
                case "add": {
                    Recorder.Item it = r.add(new JSONObject(u.getQueryParameter("data")));
                    runOnUiThread(() -> { askNotificationPermission(); RecordingService.ensure(this); });
                    return json(200, new JSONObject().put("ok", true).put("item", it.toJson(false)));
                }
                case "stop": return json(200, new JSONObject().put("ok", r.stop(id)));
                case "delete": return json(200, new JSONObject().put("ok", r.delete(id)));
                case "config":
                    if (u.getQueryParameter("compress") != null) r.setCompress(u.getQueryParameter("compress"));
                    return json(200, r.status());
                case "compress": {
                    Recorder.Item it = r.compressNow(id, u.getQueryParameter("level"));
                    runOnUiThread(() -> RecordingService.ensure(this));
                    return json(200, new JSONObject().put("ok", true).put("item", it.toJson(false)));
                }
                case "compress_cancel": return json(200, new JSONObject().put("ok", r.cancelCompress(id)));
                case "rule_add": {
                    JSONObject rule = r.addRule(new JSONObject(u.getQueryParameter("data")));
                    runOnUiThread(() -> RecordingService.ensure(this));
                    return json(200, new JSONObject().put("ok", true).put("rule", rule));
                }
                case "rule_del": return json(200, new JSONObject().put("ok", r.delRule(id)));
                case "drive": r.launch(r.driveIntent(id)); return json(200, new JSONObject().put("ok", true));
                case "open": r.launch(r.viewIntent(id)); return json(200, new JSONObject().put("ok", true));
                case "file": return serveFile(r.fileFor(id), headers);
                default: return json(404, new JSONObject().put("ok", false).put("error", "Not available on Android"));
            }
        } catch (Exception e) {
            try { return json(400, new JSONObject().put("ok", false).put("error", e.getMessage() == null ? e.toString() : e.getMessage())); }
            catch (Exception ignored) { return text(500, "error"); }
        }
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
    }

    /** Serves a recording with byte-range support so the player can seek. */
    private WebResourceResponse serveFile(File f, Map<String, String> headers) {
        if (f == null) return text(404, "Recording not found");
        try {
            long size = f.length(), start = 0, end = size - 1;
            boolean partial = false;
            String range = header(headers, "Range");
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-", -1);
                try {
                    if (!p[0].isEmpty()) {
                        start = Long.parseLong(p[0].trim());
                        if (p.length > 1 && !p[1].trim().isEmpty()) end = Math.min(Long.parseLong(p[1].trim()), size - 1);
                        partial = true;
                    } else if (p.length > 1 && !p[1].trim().isEmpty()) {
                        start = Math.max(0, size - Long.parseLong(p[1].trim()));
                        partial = true;
                    }
                } catch (NumberFormatException ignored) { }
            }
            if (start >= size) return text(416, "Range not satisfiable");
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.seek(start);
            Map<String, String> h = new HashMap<>();
            h.put("Accept-Ranges", "bytes");
            h.put("Content-Length", String.valueOf(end - start + 1));
            if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
            return new WebResourceResponse(Recorder.mimeFor(f.getName()), null, partial ? 206 : 200, reason(partial ? 206 : 200), h,
                    new RangeStream(raf, end - start + 1));
        } catch (IOException e) {
            return text(500, "Could not read recording");
        }
    }

    private static class RangeStream extends InputStream {
        private final RandomAccessFile raf;
        private long left;
        RangeStream(RandomAccessFile raf, long len) { this.raf = raf; this.left = len; }
        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = raf.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = raf.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
        @Override public void close() throws IOException { raf.close(); }
    }

    /** Android TV, Google TV and Fire TV: switches the player into TV mode (remote control, big text). */
    private boolean isTv() {
        UiModeManager um = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) return true;
        PackageManager pm = getPackageManager();
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature("amazon.hardware.fire_tv");
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onResume() { super.onResume(); web.onResume(); NativeExtras.top = this; }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handleIntent(i);
    }

    /** Opened from the Google TV home screen ("Continue watching") or by a voice / assistant search. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri d = i.getData();
        if (d != null && "xtreamplayer".equals(d.getScheme()) && "resume".equals(d.getHost()) && d.getQueryParameter("k") != null)
            web.postDelayed(() -> js("window.xpOpenResume && xpOpenResume(" + JSONObject.quote(d.getQueryParameter("k")) + ")"), 600);
        String q = i.getStringExtra(android.app.SearchManager.QUERY);
        if (q == null) q = i.getStringExtra("query");
        if (q != null && !q.isEmpty()) {
            final String text = q;
            web.postDelayed(() -> js("window.xpVoice && xpVoice(" + JSONObject.quote(text) + ")"), 1500);
        }
    }

    @Override
    public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (code == android.view.KeyEvent.KEYCODE_SEARCH) { NativeExtras.voice(this); return true; }
        return super.onKeyDown(code, e);
    }
    @Override protected void onPause() { web.onPause(); super.onPause(); }
    @Override protected void onDestroy() { web.destroy(); super.onDestroy(); }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) { chrome.onHideCustomView(); return; }
        web.evaluateJavascript("(window.xpBack && window.xpBack()) ? 'y' : 'n'", v -> {
            if (v == null || !v.contains("y")) finish();
        });
    }

    /* ---------------- page loading + proxy ---------------- */

    private class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath() == null ? "/" : u.getPath();
            switch (path) {
                case "/":
                case "/index.html":
                    return asset("index.html", "text/html");
                case "/help.html":
                    return asset("help.html", "text/html");
                case "/__xp_ping":
                    return text(200, "xp-proxy");
                case "/proxy":
                    return proxy(u.getQueryParameter("url"), req.getRequestHeaders());
                case "/xhr":
                    return xhr(u);
                case "/dl/file":
                    try { return serveFile(NativeExtras.fileFor(MainActivity.this, Long.parseLong(u.getQueryParameter("id"))), req.getRequestHeaders()); }
                    catch (Exception e) { return text(404, "Not found"); }
                default:
                    if (path.startsWith("/native/")) return nativeCmd(path.substring(8), u);
                    if (path.startsWith("/rec/")) return recApi(path.substring(5), u, req.getRequestHeaders());
                    return text(404, "Not found");
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            String scheme = u.getScheme() == null ? "" : u.getScheme();
            if (scheme.equals("xpext")) { openExternal(u.getQueryParameter("url")); return true; }
            if (scheme.equals("xpapp")) {
                if ("pip".equals(u.getHost())) enterPip();
                if ("refresh".equals(u.getHost())) {
                    try { setRefreshRate(Float.parseFloat(u.getQueryParameter("hz"))); } catch (Exception ignored) { }
                }
                return true;
            }
            if (HOST.equals(u.getHost())) return false;
            try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) { }
            return true;
        }
    }

    private WebResourceResponse asset(String name, String mime) {
        try {
            return new WebResourceResponse(mime, "UTF-8", getAssets().open(name));
        } catch (IOException e) {
            return text(500, "Missing asset " + name);
        }
    }

    private static WebResourceResponse text(int code, String body) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-store");
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse("text/plain", "UTF-8", code, reason(code), h,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    /** Commands from the page: /native/<cmd>?d=<json>. Answers with JSON. */
    private WebResourceResponse nativeCmd(String cmd, Uri u) {
        try {
            String d = u.getQueryParameter("d");
            JSONObject in = d == null || d.isEmpty() ? new JSONObject() : new JSONObject(d);
            JSONObject out = new JSONObject().put("ok", true);
            switch (cmd) {
                case "info":
                    out.put("tv", isTv()).put("sdk", Build.VERSION.SDK_INT).put("player", true);
                    break;
                case "play": {
                    Intent i = new Intent(this, PlayerActivity.class);
                    i.putExtra("item", in.toString());
                    i.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    runOnUiThread(() -> startActivity(i));
                    break;
                }
                default:
                    return NativeExtras.handle(this, cmd, in, out) ? json(200, out) : text(404, "Unknown command");
            }
            return json(200, out);
        } catch (Exception e) {
            return text(500, String.valueOf(e.getMessage()));
        }
    }

    /** Relays an API call (method, headers, body) for the Debrid tab, like server.py's /xhr. */
    private WebResourceResponse xhr(Uri u) {
        String url = u.getQueryParameter("url");
        String method = u.getQueryParameter("m") == null ? "GET" : u.getQueryParameter("m").toUpperCase();
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))
                || !(method.equals("GET") || method.equals("POST") || method.equals("PUT") || method.equals("DELETE")))
            return text(400, "Bad request");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", "XtreamPlayer/1.0");
            c.setRequestProperty("Accept", "application/json");
            String h = u.getQueryParameter("h");
            if (h != null && !h.isEmpty()) {
                JSONObject o = new JSONObject(h);
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String lk = k.toLowerCase();
                    if (lk.equals("host") || lk.equals("content-length") || lk.equals("connection") || lk.equals("cookie")) continue;
                    c.setRequestProperty(k, o.optString(k));
                }
            }
            String b = u.getQueryParameter("b");
            if (b != null) {
                byte[] data = b.getBytes(StandardCharsets.UTF_8);
                if (c.getRequestProperty("Content-Type") == null)
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(data.length);
                c.getOutputStream().write(data);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 16 * 1024 * 1024) bo.write(buf, 0, n);
                in.close();
            }
            if (code >= 300 && code < 400) code = 502;
            Map<String, String> rh = new HashMap<>();
            rh.put("Cache-Control", "no-store");
            rh.put("Access-Control-Allow-Origin", "*");
            String ct = c.getContentType() == null ? "application/json" : c.getContentType();
            String mime = ct.split(";")[0].trim();
            return new WebResourceResponse(mime, "UTF-8", code, reason(code), rh, new ByteArrayInputStream(bo.toByteArray()));
        } catch (Exception e) {
            return text(502, "Could not reach " + Uri.parse(url).getHost() + ": " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 416: return "Range Not Satisfiable";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default: return "Status " + code;
        }
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    /** Fetches the provider URL (following redirects across http/https) and returns it to the page. */
    private WebResourceResponse proxy(String url, Map<String, String> reqHeaders) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://")))
            return text(400, "Bad or missing url parameter");
        HttpURLConnection c = null;
        try {
            String[] cached = REDIRECTS.get(url);
            boolean useCache = cached != null && Long.parseLong(cached[1]) > System.currentTimeMillis();
            URL cur = new URL(useCache ? cached[0] : url);
            int code = 0;
            for (int hop = 0; hop < 8; hop++) {
                c = (HttpURLConnection) cur.openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", PROVIDER_UA);
                c.setRequestProperty("Accept", "*/*");
                String range = header(reqHeaders, "Range");
                if (range != null) c.setRequestProperty("Range", range);
                code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    cur = new URL(cur, loc);
                    drain(c);          // lets Android reuse the connection (keep-alive)
                    continue;
                }
                if (useCache && code >= 400 && hop == 0) {   // remembered address expired: ask the provider again
                    drain(c);
                    REDIRECTS.remove(url);
                    useCache = false;
                    cur = new URL(url);
                    hop = -1;
                    continue;
                }
                break;
            }
            if (code >= 300 && code < 400) return text(502, "Too many redirects");
            if (!useCache && code < 400 && !cur.toString().equals(url)) {
                if (REDIRECTS.size() > 500) REDIRECTS.clear();
                REDIRECTS.put(url, new String[]{cur.toString(), String.valueOf(System.currentTimeMillis() + 45_000)});
            }

            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) in = new ByteArrayInputStream(new byte[0]);
            String ctype = c.getContentType();
            boolean playlist = (ctype != null && ctype.toLowerCase().contains("mpegurl"))
                    || cur.getPath().toLowerCase().endsWith(".m3u8");

            Map<String, String> h = new HashMap<>();
            h.put("Access-Control-Allow-Origin", "*");
            h.put("Cache-Control", "no-store");

            if (playlist && code < 400) {
                byte[] body = readAll(in);
                String t = new String(body, StandardCharsets.UTF_8);
                if (t.trim().startsWith("#EXTM3U")) body = rewritePlaylist(t, cur).getBytes(StandardCharsets.UTF_8);
                return new WebResourceResponse("application/vnd.apple.mpegurl", "UTF-8", code, reason(code), h,
                        new ByteArrayInputStream(body));
            }

            for (String k : new String[]{"Content-Length", "Content-Range", "Accept-Ranges"}) {
                String v = c.getHeaderField(k);
                if (v != null) h.put(k, v);
            }
            String mime = ctype == null ? "application/octet-stream" : ctype.split(";")[0].trim();
            return new WebResourceResponse(mime, null, code, reason(code), h, in); // streamed, not buffered
        } catch (Exception e) {
            if (c != null) c.disconnect();
            return text(502, "Could not reach provider: " + e.getMessage());
        }
    }

    /** Xtream servers redirect every live request; remembering where it led saves a round trip each time. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String[]> REDIRECTS = new java.util.concurrent.ConcurrentHashMap<>();

    private static void drain(HttpURLConnection c) {
        try {
            InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) { byte[] b = new byte[4096]; while (in.read(b) > 0) { } in.close(); }
        } catch (IOException ignored) {
            c.disconnect();
        }
    }

    private static String proxied(String absolute) {
        try { return "/proxy?url=" + URLEncoder.encode(absolute, "UTF-8"); }
        catch (Exception e) { return absolute; }
    }

    private static String resolve(URL base, String ref) {
        try { return new URL(base, ref).toString(); } catch (Exception e) { return ref; }
    }

    /** Points every segment / key / sub-playlist in an HLS playlist back through the proxy. */
    static String rewritePlaylist(String text, URL base) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("#")) {
                if (s.contains("URI=\"")) {
                    Matcher m = URI_ATTR.matcher(s);
                    StringBuffer sb = new StringBuffer();
                    while (m.find()) {
                        String v = m.group(1);
                        String rep = v.startsWith("data:") ? m.group(0) : "URI=\"" + proxied(resolve(base, v)) + "\"";
                        m.appendReplacement(sb, Matcher.quoteReplacement(rep));
                    }
                    m.appendTail(sb);
                    s = sb.toString();
                }
                out.append(s).append('\n');
            } else {
                out.append(proxied(resolve(base, s))).append('\n');
            }
        }
        return out.toString();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /* ---------------- external player (VLC, MX Player…) ---------------- */

    private void openExternal(String url) {
        if (url == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        try {
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No video player app found. Try installing VLC.", Toast.LENGTH_LONG).show();
        }
    }

    /* ---------------- match the TV's refresh rate to the programme ---------------- */

    /** hz: 50 (UK/European TV), 60, 24 (films), or 0 to go back to the TV's normal mode. */
    private void setRefreshRate(float hz) {
        if (Build.VERSION.SDK_INT < 23) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        int want = 0;
        if (hz > 0) {
            android.view.Display d = getWindowManager().getDefaultDisplay();
            android.view.Display.Mode cur = d.getMode();
            android.view.Display.Mode best = null;
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                float r = m.getRefreshRate();
                if (Math.abs(r - hz) < 0.6f || (hz == 24f && Math.abs(r - 23.976f) < 0.05f)) {
                    if (best == null || Math.abs(r - hz) < Math.abs(best.getRefreshRate() - hz)) best = m;
                }
            }
            if (best == null) return;          // the TV can't do that rate: leave it alone
            want = best.getModeId();
        }
        if (lp.preferredDisplayModeId != want) {
            lp.preferredDisplayModeId = want;
            getWindow().setAttributes(lp);
        }
    }

    /* ---------------- picture-in-picture ---------------- */

    private void enterPip() {
        if (Build.VERSION.SDK_INT < 26) { Toast.makeText(this, "Picture-in-picture needs Android 8 or newer", Toast.LENGTH_SHORT).show(); return; }
        try { enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build()); }
        catch (Exception e) { Toast.makeText(this, "Picture-in-picture is turned off for this app", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(inPip, cfg);
        if (!inPip) web.evaluateJavascript("document.body.classList.remove('theater')", null);
    }

    /* ---------------- file picker (restore backup) ---------------- */

    private ValueCallback<Uri[]> fileCallback;

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == NativeExtras.VOICE_REQ && res == RESULT_OK && data != null) {
            java.util.ArrayList<String> r = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
            if (r != null && !r.isEmpty()) js("window.xpVoice && xpVoice(" + JSONObject.quote(r.get(0)) + ")");
            return;
        }
        if (req == 9 && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    /* ---------------- native fullscreen video ---------------- */

    private class Chrome extends WebChromeClient {
        @Override
        @SuppressWarnings("deprecation")
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try { startActivityForResult(params.createIntent(), 9); }
            catch (Exception e) { fileCallback = null; return false; }
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback cb) {
            if (customView != null) { cb.onCustomViewHidden(); return; }
            customView = view;
            customCallback = cb;
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            web.setVisibility(View.GONE);
            setFullscreenUi(true);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            web.setVisibility(View.VISIBLE);
            setFullscreenUi(false);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void setFullscreenUi(boolean on) {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(on
                ? View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                : View.SYSTEM_UI_FLAG_VISIBLE);
    }
}
