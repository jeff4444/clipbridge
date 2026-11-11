package dev.clipbridge;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import dev.clipbridge.adb.WirelessHelper;

/** Shared logic for bringing the background helper back after it stops (reboot, or it was killed). */
public final class Reconnector {
    private static final int STATUS_PORT = 47824;
    static final int WATCHDOG_JOB = 101;
    static final int BOOT_JOB = 102;

    private Reconnector() {
    }

    /** The helper's status report, or null if it is not running. */
    static JSONObject helperStatus() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", STATUS_PORT), 400);
            s.setSoTimeout(800);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the helper is up (answering on its localhost status port). */
    public static boolean helperRunning() {
        return helperStatus() != null;
    }

    /**
     * True if the helper is up and was started from the app as it is installed now. After an app
     * update the old helper carries on with the old code until it is restarted.
     */
    public static boolean helperCurrent(Context context) {
        return isCurrent(context, helperStatus());
    }

    static boolean isCurrent(Context context, JSONObject status) {
        return status != null && context.getPackageCodePath().equals(status.optString("apk"));
    }

    /**
     * If paired and the helper is down or left over from an older version of the app, connect over
     * the on-device ADB and (re)launch it. Blocking; call off the main thread. Returns true if the
     * current helper is running afterwards.
     */
    public static boolean ensureRunning(Context context, WirelessHelper.Log log) {
        if (!new Prefs(context).isPaired()) return false;
        if (helperCurrent(context)) return true;
        // One launch at a time, app-wide. The boot job, the periodic watchdog and the status screen
        // can all notice the helper is down at the same moment; whoever comes second re-checks
        // inside the lock and finds it already running.
        synchronized (WirelessHelper.class) {
            if (helperCurrent(context)) return true;
            try {
                WirelessHelper.connectAndLaunch(context, "127.0.0.1", 0, null, log);
            } catch (Throwable t) {
                log.line("reconnect failed: " + t);
            }
            // Trust the helper's own status port, not the launcher's output.
            for (int i = 0; i < 16; i++) {
                if (helperCurrent(context)) return true;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    break;
                }
            }
            return false;
        }
    }

    /**
     * A periodic, low-rate safety net: re-launch the helper whenever it is found down. No network
     * constraint — the helper is started over the on-device adb (localhost), which needs none.
     * Never lets a scheduling failure reach the caller.
     */
    public static void scheduleWatchdog(Context context) {
        try {
            JobScheduler js = context.getSystemService(JobScheduler.class);
            if (js == null) return;
            JobInfo job = new JobInfo.Builder(WATCHDOG_JOB, new ComponentName(context, WatchdogJob.class))
                    .setPersisted(true)              // survives reboot
                    .setPeriodic(20 * 60 * 1000L)    // ~every 20 minutes (OS may widen this)
                    .build();
            js.schedule(job);
        } catch (Throwable t) {
            android.util.Log.w("ClipBridge", "watchdog schedule failed: " + t);
        }
    }

    /**
     * One-off job that brings the helper back right after a reboot or an app update. Expedited so it
     * runs promptly; if it fails (Wireless debugging not up yet) JobScheduler retries with backoff.
     */
    public static void scheduleBootReconnect(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js == null) return;
        ComponentName job = new ComponentName(context, WatchdogJob.class);
        try {
            JobInfo expedited = new JobInfo.Builder(BOOT_JOB, job)
                    .setExpedited(true)
                    .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                    .build();
            if (js.schedule(expedited) == JobScheduler.RESULT_SUCCESS) return;
        } catch (Throwable t) {
            android.util.Log.w("ClipBridge", "expedited boot job refused: " + t);
        }
        try {
            JobInfo regular = new JobInfo.Builder(BOOT_JOB, job)
                    .setMinimumLatency(0)
                    .setOverrideDeadline(5_000L)
                    .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_LINEAR)
                    .build();
            js.schedule(regular);
        } catch (Throwable t) {
            android.util.Log.w("ClipBridge", "boot job schedule failed: " + t);
        }
    }

    public static void cancelWatchdog(Context context) {
        JobScheduler js = context.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(WATCHDOG_JOB);
    }
}
