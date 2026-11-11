package dev.clipbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import java.net.ConnectException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.clipbridge.adb.AdbKeyStore;
import dev.clipbridge.adb.WirelessHelper;
import dev.clipbridge.adb.WirelessHelper.SetupException;

/**
 * Runs the one-time wireless pairing.
 *
 * It puts up a notification with a reply field straight away. The user opens Android's "Pair device
 * with pairing code" dialog, swipes down the shade (which, unlike switching apps, leaves the dialog
 * and its pairing server alive) and types the code into the notification. Only then does the app
 * look up the dialog's port, pair, connect and launch the background helper. Nothing here waits for
 * or reacts to the dialog appearing: the port is random per dialog, so it is looked up fresh for
 * every attempt and never remembered.
 */
public final class PairingService extends Service {
    public static final String ACTION_START = "dev.clipbridge.START_PAIRING";
    public static final String ACTION_CODE = "dev.clipbridge.PAIRING_CODE";
    public static final String KEY_CODE = "code";

    private static final String TAG = "ClipBridge";
    private static final String CHANNEL = "pairing";
    private static final int NOTIF = 1;
    private static final long SESSION_MS = 10 * 60 * 1000;
    /** "123456", or "123456 34967" (code, then the port shown in the dialog). */
    private static final Pattern REPLY = Pattern.compile("^\\D*(\\d{6})(?:\\D+(\\d{4,5}))?\\D*$");

    private static final String HOW = "In Settings › Developer options › Wireless debugging, tap "
            + "“Pair device with pairing code”. Then swipe down and type the 6-digit code here.";

    /** Progress for any open SetupActivity to observe. */
    public enum Phase {IDLE, READY_FOR_CODE, PAIRING, SUCCESS, FAILED}

    public interface Observer {
        void onPhase(Phase phase, @Nullable String detail);
    }

    private static volatile Observer observer;
    private static volatile Phase phase = Phase.IDLE;
    private static volatile String detail;

    public static void setObserver(@Nullable Observer o) {
        observer = o;
        if (o != null) o.onPhase(phase, detail);
    }

    public static Phase currentPhase() {
        return phase;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean pairing = new AtomicBoolean(false);
    private final Runnable timeout = this::onTimeout;
    private volatile boolean finished;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_CODE.equals(action)) {
            onReply(extractReply(intent));
            return START_NOT_STICKY;
        }
        // Start, or start again after a failed attempt: same thing either way.
        startForeground(NOTIF, buildNotification("Type the pairing code here", HOW, true));
        finished = false;
        if (!pairing.get()) setPhase(Phase.READY_FOR_CODE, null);
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, SESSION_MS);
        tryExistingKey();
        return START_NOT_STICKY;
    }

    /**
     * If this app's key is already authorised on the phone (an earlier attempt paired but then
     * failed to start the helper, or the app is being set up again), no code is needed at all.
     */
    private void tryExistingKey() {
        if (!AdbKeyStore.exists(this)) return;
        final String macConfig = new Prefs(this).macConfig();
        new Thread(() -> {
            try {
                WirelessHelper.connectAndLaunch(this, "127.0.0.1", 0, macConfig, m -> log("existing key: " + m));
                log("existing key: already authorised, no code needed");
                main.post(this::succeed);
            } catch (Throwable t) {
                log("existing key: not usable yet (" + t + ")");
            }
        }, "pairing-existing-key").start();
    }

    private void onReply(@Nullable String reply) {
        if (finished) return;
        Matcher m = reply != null ? REPLY.matcher(reply) : null;
        if (m == null || !m.matches()) {
            notifyNow("That didn’t look like the code", "Type the 6 digits shown in the pairing dialog.", true);
            return;
        }
        final String code = m.group(1);
        final int port = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        if (!pairing.compareAndSet(false, true)) return;
        setPhase(Phase.PAIRING, null);
        notifyNow("Pairing…", "Setting up ClipBridge.", false);
        final String macConfig = new Prefs(this).macConfig(); // from the Mac's QR; becomes config.json
        new Thread(() -> {
            try {
                WirelessHelper.pairAndLaunch(this, code, port, macConfig, msg -> log("pair: " + msg));
                main.post(this::succeed);
            } catch (Throwable t) {
                android.util.Log.w(TAG, "pairing failed", t);
                final String why = explain(t);
                main.post(() -> {
                    pairing.set(false);
                    if (finished) return;
                    setPhase(Phase.FAILED, why);
                    notifyNow("Pairing didn’t work", why, true);
                });
            }
        }, "pairing").start();
    }

    private void succeed() {
        if (finished) return;
        finished = true;
        pairing.set(false);
        main.removeCallbacks(timeout);
        new Prefs(this).setPaired(true);
        Reconnector.scheduleWatchdog(this); // keep it alive across reboots from now on
        setPhase(Phase.SUCCESS, null);
        notifyNow("ClipBridge is set up", "Copy on either device — it just syncs now.", false);
        main.postDelayed(() -> {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
            stopSelf();
        }, 2500);
    }

    private void onTimeout() {
        if (finished || pairing.get()) return;
        finished = true;
        setPhase(Phase.FAILED, "Timed out. Tap Start pairing to try again.");
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** What went wrong, and what to do about it, in the user's terms. */
    private static String explain(Throwable t) {
        if (!(t instanceof SetupException)) return "Something went wrong (" + shortName(t) + "). Try again.";
        SetupException e = (SetupException) t;
        switch (e.stage) {
            case NO_DIALOG:
                return "Couldn’t find the pairing dialog. Check that “Pair device with pairing code” is open, "
                        + "then type the code again. If this keeps happening, type the code and then the "
                        + "port, which is the number after the colon in the dialog: 123456 34567";
            case PAIR:
                if (refused(e)) {
                    return "The pairing dialog had closed, or that port was wrong. Open “Pair device with "
                            + "pairing code” again and type the new code.";
                }
                return "That code wasn’t accepted. Open “Pair device with pairing code” again and type the "
                        + "new code.";
            case LAUNCH:
            default:
                return "Paired, but ClipBridge couldn’t start its helper (" + shortName(e.getCause()) + "). "
                        + "Check that Wireless debugging is still on, then tap Start pairing in ClipBridge "
                        + "again. No new code is needed.";
        }
    }

    private static boolean refused(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof ConnectException) return true;
            String m = c.getMessage();
            if (m != null && (m.contains("ECONNREFUSED") || m.contains("refused"))) return true;
        }
        return false;
    }

    private static String shortName(@Nullable Throwable t) {
        if (t == null) return "unknown error";
        String m = t.getMessage();
        String name = t.getClass().getSimpleName();
        return m == null || m.isEmpty() ? name : name + ": " + (m.length() > 80 ? m.substring(0, 80) + "…" : m);
    }

    private static void log(String message) {
        android.util.Log.i(TAG, "setup: " + message);
    }

    // notifications ---------------------------------------------------------

    private Notification buildNotification(String title, String text, boolean withReply) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Setting up ClipBridge");
            nm.createNotificationChannel(ch);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        if (withReply) {
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_CODE).setLabel("6-digit code").build();
            Intent replyIntent = new Intent(this, PairReplyReceiver.class).setAction(ACTION_CODE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, replyIntent, flags);
            NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground, "Enter code", pi)
                    .addRemoteInput(remoteInput)
                    .setAllowGeneratedReplies(false)
                    .build();
            b.addAction(action);
        }
        return b.build();
    }

    private void notifyNow(String title, String text, boolean withReply) {
        getSystemService(NotificationManager.class).notify(NOTIF, buildNotification(title, text, withReply));
    }

    @Nullable
    private static String extractReply(Intent intent) {
        android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results != null) {
            CharSequence c = results.getCharSequence(KEY_CODE);
            if (c != null) return c.toString().trim();
        }
        String direct = intent.getStringExtra(KEY_CODE); // also accept a plain extra (testing)
        return direct != null ? direct.trim() : null;
    }

    private void setPhase(Phase p, @Nullable String d) {
        phase = p;
        detail = d;
        Observer o = observer;
        if (o != null) main.post(() -> o.onPhase(p, d));
    }

    /**
     * Deliver a reply to the running service (from PairReplyReceiver). The service is already in the
     * foreground when a real reply arrives, so this start is allowed; we guard anyway so a stray
     * reply after the service has stopped can never crash the app.
     */
    public static void deliverCode(Context context, @Nullable String code) {
        Intent i = new Intent(context, PairingService.class).setAction(ACTION_CODE).putExtra(KEY_CODE, code);
        try {
            context.startService(i);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "pairing reply ignored (service not running): " + t);
        }
    }

    public static void start(Context context) {
        Intent i = new Intent(context, PairingService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(i);
        } else {
            context.startService(i);
        }
    }
}
