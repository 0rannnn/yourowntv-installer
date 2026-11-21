package com.gathin.kit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The launcher's Troubleshooting card asks this app (which holds the TV's ADB permission) for two things:
 * com.gathin.kit.action.REMOTE_ON, the TV's ADB on port 5555 for the computer (reached through Tailscale), and
 * com.gathin.kit.action.REPORT, the troubleshooting report (Kit.writeReport). Only on a TV that has our launcher.
 */
public class RemoteReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            context.getPackageManager().getPackageInfo("com.gathin.tvhome", 0);
        } catch (Exception notInstalled) {
            return;
        }
        String action = intent.getAction();
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                Kit kit = new Kit(app);
                if ("com.gathin.kit.action.REMOTE_ON".equals(action)) kit.openRemoteAdb();
                else if ("com.gathin.kit.action.REPORT".equals(action)) kit.writeReport();
            } finally {
                pending.finish();
            }
        }, "kit-remote").start();
    }
}
