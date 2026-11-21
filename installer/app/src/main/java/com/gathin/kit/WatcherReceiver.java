package com.gathin.kit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Starts the shell watcher that makes a held volume key repeat quickly (Kit.VOLUME_WATCHER, the launcher's
 * VolumeHold): after every reboot (shell processes end there), and whenever Home finds it missing and asks
 * (com.gathin.kit.action.VOLUME_WATCHER). Only on a TV that has our launcher.
 */
public class WatcherReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.getPackageManager().getPackageInfo("com.gathin.tvhome", 0);
        } catch (Exception notInstalled) {
            return;
        }
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                // After a boot the network and ADB come up a little after BOOT_COMPLETED: a few tries.
                for (int i = 0; i < 4; i++) {
                    if (new Kit(app).startVolumeWatcher()) break;
                    Thread.sleep(8_000);
                }
            } catch (InterruptedException ignored) {
            } finally {
                pending.finish();
            }
        }, "volume-watcher").start();
    }
}
