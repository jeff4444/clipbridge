package dev.clipbridge;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Brings the background helper back when it is found down. Runs in two forms:
 *
 *  - a one-off job right after a reboot or app update ({@link Reconnector#BOOT_JOB}), which keeps
 *    trying for a little while because Wi-Fi and Wireless debugging take a moment to come up, and
 *    asks JobScheduler to retry with backoff if they are not up yet;
 *  - a low-rate periodic safety net ({@link Reconnector#WATCHDOG_JOB}).
 *
 * A job rather than a foreground service: Android 15+ does not allow a boot receiver to start a
 * dataSync foreground service.
 */
public final class WatchdogJob extends JobService {
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        final boolean boot = params.getJobId() == Reconnector.BOOT_JOB;
        final String tag = boot ? "boot-reconnect: " : "watchdog: ";
        new Thread(() -> {
            boolean ok = false;
            try {
                if (!new Prefs(this).isPaired()) {
                    ok = true; // nothing to do until setup has been completed
                    return;
                }
                int attempts = boot ? 5 : 1;
                for (int i = 0; i < attempts && !ok && !stopped; i++) {
                    if (i > 0) Thread.sleep(8_000);
                    ok = Reconnector.ensureRunning(this, m -> android.util.Log.i("ClipBridge", tag + m));
                }
                android.util.Log.i("ClipBridge", tag + (ok ? "helper is running" : "not yet (is Wireless debugging on?)"));
            } catch (InterruptedException ignored) {
            } finally {
                // For the boot job, ask for a retry (with backoff) if we could not bring it up.
                jobFinished(params, boot && !ok);
            }
        }, boot ? "boot-reconnect" : "watchdog").start();
        return true; // work continues on the thread
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true; // reschedule if the system cut us off
    }
}
