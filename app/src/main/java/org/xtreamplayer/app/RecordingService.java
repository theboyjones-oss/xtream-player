package org.xtreamplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Keeps recordings going while the app is in the background or closed.
 * Runs only while something is scheduled or recording, then stops itself.
 */
public class RecordingService extends Service {

    static final String CH_ACTIVE = "rec_active";
    static final String CH_DONE = "rec_done";
    static final int NID = 4201;

    private volatile boolean running;
    private Thread loop;
    private PowerManager.WakeLock wake;

    static void ensure(Context c) {
        Intent i = new Intent(c, RecordingService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) { /* not allowed from background; started again next time the app opens */ }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        channels(this);
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_ACTIVE, "Recording in progress", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, "Finished recordings", NotificationManager.IMPORTANCE_DEFAULT));
    }

    @SuppressWarnings("deprecation")
    private static Notification.Builder builder(Context c, String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, ch) : new Notification.Builder(c);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification ongoing(String text) {
        return builder(this, CH_ACTIVE)
                .setSmallIcon(android.R.drawable.presence_video_busy)
                .setContentTitle("PanGaze")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp(this))
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = ongoing(Recorder.get(this).summary());
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(NID, n);
        if (wake == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XtreamPlayer:recorder");
            wake.setReferenceCounted(false);
            wake.acquire();
        }
        if (loop == null) {
            running = true;
            loop = new Thread(this::runLoop, "recorder-scheduler");
            loop.start();
        }
        return START_NOT_STICKY;
    }

    private void runLoop() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Recorder r = Recorder.get(this);
        String last = "";
        int idle = 0;
        while (running) {
            r.tick();
            String s = r.summary();
            if (!s.equals(last)) { nm.notify(NID, ongoing(s)); last = s; }
            if (!r.hasPending()) { if (++idle > 15) break; } else idle = 0;   // ~30 s grace so a guide check can add episodes
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
        }
        running = false;
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /* ---------------- finished / failed notifications ---------------- */

    static void notifyFinished(Context c, Recorder.Item it) {
        channels(c);
        Notification.Builder b = builder(c, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Recorded: " + it.title)
                .setContentText(it.channel + ("stopped".equals(it.status) ? " · stopped early" : ""))
                .setAutoCancel(true)
                .setContentIntent(openApp(c));
        Intent drive = Recorder.get(c).driveIntent(it.id);
        if (drive != null) {
            PendingIntent pi = PendingIntent.getActivity(c, it.id.hashCode(), drive, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String label = Recorder.get(c).driveInstalled() ? "Save to Google Drive" : "Save / share";
            b.addAction(new Notification.Action.Builder(null, label, pi).build());
        }
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(it.id.hashCode(), b.build());
    }

    static void notifyFailed(Context c, Recorder.Item it) {
        channels(c);
        Notification n = builder(c, CH_DONE)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Recording failed: " + it.title)
                .setContentText(it.error == null ? "" : it.error)
                .setAutoCancel(true)
                .setContentIntent(openApp(c))
                .build();
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(it.id.hashCode(), n);
    }
}
