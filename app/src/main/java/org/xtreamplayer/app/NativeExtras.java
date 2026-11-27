package org.xtreamplayer.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.speech.RecognizerIntent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** App features the page asks for through /native/<cmd>: Google TV home row, voice, downloads, phone remote. */
final class NativeExtras {
    static final int VOICE_REQ = 77;

    static boolean handle(MainActivity a, String cmd, JSONObject in, JSONObject out) throws Exception {
        switch (cmd) {
            case "watchnext": watchNext(a, in.optJSONArray("list")); return true;
            case "voice": voice(a); return true;
            case "lan": {
                LanServer.start(a, in.optString("pin"));
                String ip = lanIp();
                out.put("addr", ip == null ? "" : ip + ":" + LanServer.PORT);
                return true;
            }
            case "myip": { String ip = lanIp(); out.put("ip", ip == null ? "" : ip); return true; }
            case "vpn": out.put("vpn", vpnOn(a)); out.put("how", "android"); return true;
            case "download": out.put("id", download(a, in)); return true;
            case "downloads": out.put("list", downloads(a)); return true;
            case "dldelete": deleteDownload(a, in.optLong("id")); return true;
            default: return false;
        }
    }

    /* ---------- Google TV home screen: "Continue watching" (Watch Next) ---------- */
    private static void watchNext(Context c, JSONArray list) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || list == null) return;
        new Thread(() -> {
            try {
                // remove our earlier entries, then add the current ones
                try (Cursor cur = c.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                        new String[]{TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID}, null, null, null)) {
                    while (cur != null && cur.moveToNext()) {
                        String pid = cur.getString(1);
                        if (pid != null && pid.startsWith("xp:"))
                            c.getContentResolver().delete(TvContract.buildWatchNextProgramUri(cur.getLong(0)), null, null);
                    }
                }
                for (int i = 0; i < list.length(); i++) {
                    JSONObject e = list.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    boolean ep = e.optBoolean("ep");
                    v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, ep ? TvContract.WatchNextPrograms.TYPE_TV_EPISODE : TvContract.WatchNextPrograms.TYPE_MOVIE);
                    v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
                    v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, e.optString("title"));
                    if (ep) v.put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, e.optString("sub"));
                    else v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, e.optString("sub"));
                    if (!e.optString("img").isEmpty()) {
                        v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, e.optString("img"));
                        v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_2_3);
                    }
                    v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) Math.min(Integer.MAX_VALUE, e.optLong("t") * 1000));
                    v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) Math.min(Integer.MAX_VALUE, e.optLong("d") * 1000));
                    v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, e.optLong("u", System.currentTimeMillis()));
                    v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, "xp:" + e.optString("k"));
                    v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, "xtreamplayer://resume?k=" + Uri.encode(e.optString("k")));
                    c.getContentResolver().insert(TvContract.WatchNextPrograms.CONTENT_URI, v);
                }
            } catch (Exception ignored) { }   // not a TV, or the home screen doesn't allow it
        }).start();
    }

    /* ---------- voice search ---------- */
    static void voice(MainActivity a) {
        a.runOnUiThread(() -> {
            try {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a channel, programme, film or series");
                a.startActivityForResult(i, VOICE_REQ);
            } catch (Exception e) {
                MainActivity.js("toast('Voice search isn\\'t available on this device')");
            }
        });
    }

    /* ---------- downloads for watching offline ---------- */
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("downloads", Context.MODE_PRIVATE); }

    private static JSONArray saved(Context c) {
        try { return new JSONArray(prefs(c).getString("list", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static long download(Context c, JSONObject in) throws Exception {
        String title = in.optString("title", "Video");
        String ext = in.optString("ext", "mp4").replaceAll("[^A-Za-z0-9]", "");
        String name = title.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        if (name.isEmpty()) name = "video";
        name = name.substring(0, Math.min(80, name.length())) + "-" + (System.currentTimeMillis() % 100000) + "." + (ext.isEmpty() ? "mp4" : ext);
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(in.getString("url")))
                .setTitle(title)
                .setDescription("PanGaze")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MOVIES, name);
        r.addRequestHeader("User-Agent", MainActivity.PROVIDER_UA);
        long id = dm.enqueue(r);
        JSONArray l = saved(c);
        l.put(new JSONObject().put("id", id).put("title", title).put("img", in.optString("img")).put("key", in.optString("key")).put("name", name).put("added", System.currentTimeMillis()));
        prefs(c).edit().putString("list", l.toString()).apply();
        return id;
    }

    static File fileFor(Context c, long id) {
        JSONArray l = saved(c);
        for (int i = 0; i < l.length(); i++) {
            JSONObject o = l.optJSONObject(i);
            if (o != null && o.optLong("id") == id) {
                File f = new File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES), o.optString("name"));
                return f.exists() ? f : null;
            }
        }
        return null;
    }

    private static JSONArray downloads(Context c) throws Exception {
        JSONArray l = saved(c), out = new JSONArray();
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        for (int i = l.length() - 1; i >= 0; i--) {
            JSONObject o = l.getJSONObject(i);
            JSONObject x = new JSONObject(o.toString());
            String status = "downloading";
            long done = 0, total = 0;
            try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(o.getLong("id")))) {
                if (cur != null && cur.moveToFirst()) {
                    int s = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                    total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    status = s == DownloadManager.STATUS_SUCCESSFUL ? "done" : s == DownloadManager.STATUS_FAILED ? "failed" : "downloading";
                } else if (fileFor(c, o.getLong("id")) != null) {
                    status = "done";
                    total = done = fileFor(c, o.getLong("id")).length();
                } else status = "failed";
            }
            x.put("status", status).put("size", total).put("pct", total > 0 ? Math.round(done * 100.0 / total) : 0);
            out.put(x);
        }
        return out;
    }

    private static void deleteDownload(Context c, long id) throws Exception {
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        try { dm.remove(id); } catch (Exception ignored) { }
        File f = fileFor(c, id);
        if (f != null) //noinspection ResultOfMethodCallIgnored
            f.delete();
        JSONArray l = saved(c), keep = new JSONArray();
        for (int i = 0; i < l.length(); i++) if (l.getJSONObject(i).optLong("id") != id) keep.put(l.getJSONObject(i));
        prefs(c).edit().putString("list", keep.toString()).apply();
    }

    /* ---------- this device's Wi-Fi address (for the phone remote) ---------- */
    /** True when this device's internet traffic goes through a VPN (any VPN app, or Android's Always-on VPN). */
    static boolean vpnOn(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network n = cm.getActiveNetwork();
        NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
        for (Network x : cm.getAllNetworks()) {          // some VPNs aren't reported as the active network
            NetworkCapabilities xc = cm.getNetworkCapabilities(x);
            if (xc != null && xc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && xc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return true;
        }
        return false;
    }

    static String lanIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof Inet4Address && !ad.isLoopbackAddress() && ad.isSiteLocalAddress()) return ad.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static Activity top;
}
