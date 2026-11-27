package org.xtreamplayer.app;

import android.app.Activity;
import android.net.Uri;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A tiny web server on the Wi-Fi (port 8898) so a phone can be used as a remote and keyboard,
 * send its login / debrid settings, or send a video to play. Every command needs the 6-digit code
 * shown on the TV.
 */
final class LanServer {
    static final int PORT = 8898;
    private static ServerSocket server;
    private static volatile String pin = "";
    private static MainActivity main;

    static synchronized void start(MainActivity a, String p) {
        main = a;
        if (p != null && p.matches("\\d{6}")) pin = p;
        if (server != null) return;
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(PORT));
        } catch (Exception e) { server = null; return; }
        Thread t = new Thread(() -> {
            while (server != null && !server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> handle(s)).start();
                } catch (Exception ignored) { }
            }
        }, "xp-lan");
        t.setDaemon(true);
        t.start();
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10000);
            InputStream in = sock.getInputStream();
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') line.append((char) c);
            // skip the headers: they end with an empty line
            StringBuilder h = new StringBuilder();
            while ((c = in.read()) != -1) {
                if (c == '\r') continue;
                if (c == '\n') { if (h.length() == 0) break; h.setLength(0); }
                else h.append((char) c);
            }
            String[] parts = line.toString().trim().split(" ");
            if (parts.length < 2) return;
            Uri u = Uri.parse("http://x" + parts[1]);
            String path = u.getPath() == null ? "/" : u.getPath();
            OutputStream out = sock.getOutputStream();
            if (path.equals("/remote")) {
                byte[] body = readAsset("remote.html");
                send(out, 200, "text/html; charset=utf-8", body);
            } else if (path.equals("/remote/cmd")) {
                if (pin.isEmpty() || !pin.equals(u.getQueryParameter("t"))) { send(out, 403, "text/plain", "Wrong code".getBytes(StandardCharsets.UTF_8)); return; }
                String d = u.getQueryParameter("d");
                JSONObject cmd = new JSONObject(d == null ? "{}" : d);
                command(cmd);
                send(out, 200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
            } else {
                send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    private static void command(JSONObject cmd) {
        String c = cmd.optString("c");
        if (c.equals("ping")) return;
        if (c.equals("key")) {
            String k = cmd.optString("k");
            Activity a = NativeExtras.top != null ? NativeExtras.top : main;
            boolean inPlayer = a instanceof PlayerActivity;
            int code;
            switch (k) {
                case "ArrowUp": code = KeyEvent.KEYCODE_DPAD_UP; break;
                case "ArrowDown": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
                case "ArrowLeft": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
                case "ArrowRight": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
                case "Enter": code = KeyEvent.KEYCODE_DPAD_CENTER; break;
                case "Back": code = KeyEvent.KEYCODE_BACK; break;
                case "PlayPause": code = inPlayer ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : -1; break;
                case "Rew": code = inPlayer ? KeyEvent.KEYCODE_DPAD_LEFT : -1; break;
                case "Fwd": code = inPlayer ? KeyEvent.KEYCODE_DPAD_RIGHT : -1; break;
                case "Home":
                    if (inPlayer) a.runOnUiThread(a::onBackPressed);
                    code = -1; break;
                default: code = -1;
            }
            if (code >= 0 && a != null) {
                final int kc = code;
                a.runOnUiThread(() -> {
                    a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, kc));
                    a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, kc));
                });
                return;
            }
        }
        // everything else (typing, setup, play, Home, play/pause in the web player) is handled by the page
        MainActivity.js("window.xpRemote && xpRemote(" + JSONObject.quote(cmd.toString()) + ")");
    }

    private static byte[] readAsset(String name) throws Exception {
        try (InputStream in = main.getAssets().open(name)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : code == 403 ? " Forbidden" : " Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
