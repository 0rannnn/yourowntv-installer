package com.gathin.kit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Runs while an install is going on, so Android keeps this app alive and unfrozen even when another screen (the new
 * home, loading its themes) is in front. Kit starts it with the run and stops it at the end; it does no work itself.
 */
public class InstallService extends Service {
    private static final String CHANNEL = "install";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "התקנה", NotificationManager.IMPORTANCE_LOW));
        }
        Notification note = new Notification.Builder(this, CHANNEL)
                .setContentTitle("מתקין את מסך הבית")
                .setContentText("לא לכבות את הטלוויזיה עד הסוף")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(1, note);
        } catch (RuntimeException e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
