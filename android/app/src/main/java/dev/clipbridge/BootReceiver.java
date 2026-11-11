package dev.clipbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a reboot (or an app update), bring the background helper back and make sure the periodic
 * watchdog is scheduled. Does nothing until the user has completed setup.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        if (!new Prefs(context).isPaired()) return;
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                Reconnector.scheduleWatchdog(context);
                // A job, not a foreground service: Android 15+ does not let a boot receiver start
                // a dataSync foreground service.
                Reconnector.scheduleBootReconnect(context);
                break;
            default:
                break;
        }
    }
}
