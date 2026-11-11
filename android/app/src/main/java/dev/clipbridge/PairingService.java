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

import java.util.concurrent.atomic.AtomicBoolean;

import dev.clipbridge.adb.WirelessHelper;

/**
 * Runs the one-time wireless pairing. The user opens Android's "Pair device with pairing code"
 * dialog; this service finds it over mDNS and shows a reply notification. The user swipes down the
 * shade — which keeps the dialog (and its pairing server) in the foreground — and types the code
 * into the notification. We then pair, connect, and launch the background helper.
 */
public final class PairingService extends Service {
    public static final String ACTION_START = "dev.clipbridge.START_PAIRING";
    public static final String ACTION_CODE = "dev.clipbridge.PAIRING_CODE";
    public static final String KEY_CODE = "code";

    private static final String CHANNEL = "pairing";
    private static final int NOTIF = 1;
    private static final long DISCOVERY_WINDOW_MS = 5 * 60 * 1000;

    /** Progress for any open SetupActivity to observe. */
    public enum Phase {IDLE, WAITING_FOR_DIALOG, READY_FOR_CODE, PAIRING, SUCCESS, FAILED}

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
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    private volatile WirelessHelper.Endpoint pairingEndpoint;
    private volatile boolean pairingInFlight;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;
        if (ACTION_CODE.equals(action)) {
            String code = extractCode(intent);
            onCode(code);
            return START_NOT_STICKY;
        }
        startForeground(NOTIF, buildNotification("Looking for the pairing dialog…",
                "Open Developer options › Wireless debugging › Pair device with pairing code.", false));
        setPhase(Phase.WAITING_FOR_DIALOG, null);
        startDiscovery();
        main.postDelayed(this::timeout, DISCOVERY_WINDOW_MS);
        return START_NOT_STICKY;
    }

    private void startDiscovery() {
        if (!discovering.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + DISCOVERY_WINDOW_MS;
                while (discovering.get() && System.currentTimeMillis() < deadline && pairingEndpoint == null) {
                    WirelessHelper.Endpoint e = WirelessHelper.discoverEndpoint(
                            this, WirelessHelper.PAIRING_SERVICE, 4000);
                    if (e != null) {
                        pairingEndpoint = e;
                        main.post(() -> {
                            setPhase(Phase.READY_FOR_CODE, null);
                            notifyNow("Enter the 6-digit code",
                                    "Swipe down and type the pairing code shown on your phone.", true);
                        });
                        return;
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException ignored) {
            }
        }, "pairing-discovery").start();
    }

    private void onCode(@Nullable String code) {
        if (code == null || !code.matches("\\d{6}")) {
            notifyNow("That code didn't look right", "Enter the 6 digits exactly as shown.", true);
            return;
        }
        WirelessHelper.Endpoint endpoint = pairingEndpoint;
        if (endpoint == null) {
            notifyNow("Open the pairing dialog first",
                    "Tap 'Pair device with pairing code', then type the code here.", true);
            return;
        }
        if (pairingInFlight) return;
        pairingInFlight = true;
        setPhase(Phase.PAIRING, null);
        notifyNow("Pairing…", "Setting up ClipBridge.", false);
        final String macConfig = new Prefs(this).macConfig(); // from the Mac's QR; writes config.json
        new Thread(() -> {
            try {
                WirelessHelper.pairAndLaunch(this, endpoint.host, endpoint.port, code, 0, macConfig,
                        m -> android.util.Log.i("ClipBridge", "pair: " + m));
                new Prefs(this).setPaired(true);
                Reconnector.scheduleWatchdog(this); // keep it alive across reboots from now on
                main.post(() -> {
                    setPhase(Phase.SUCCESS, null);
                    notifyNow("ClipBridge is set up", "Copy on either device — it just syncs now.", false);
                    stop();
                });
            } catch (Throwable t) {
                pairingInFlight = false;
                main.post(() -> {
                    setPhase(Phase.FAILED, friendly(t));
                    notifyNow("Pairing failed", friendly(t) + " Make sure the dialog is still open, then try again.", true);
                });
            }
        }, "pairing").start();
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        if (m.contains("Exchanging message") || m.contains("peer info")) return "Wrong code.";
        if (m.contains("ECONNREFUSED") || m.contains("refused")) return "The pairing dialog closed.";
        return m;
    }

    private void timeout() {
        if (phase == Phase.WAITING_FOR_DIALOG || phase == Phase.READY_FOR_CODE) {
            setPhase(Phase.FAILED, "Timed out");
            stop();
        }
    }

    private void stop() {
        discovering.set(false);
        main.postDelayed(() -> {
            stopForeground(phase == Phase.SUCCESS ? Service.STOP_FOREGROUND_DETACH : Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, phase == Phase.SUCCESS ? 2500 : 0);
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
    private static String extractCode(Intent intent) {
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
     * Deliver a reply code to the running service (from PairReplyReceiver). The service is already
     * in the foreground when a real reply arrives, so this start is allowed; we guard anyway so a
     * stray reply after the service has stopped can never crash the app.
     */
    public static void deliverCode(Context context, @Nullable String code) {
        Intent i = new Intent(context, PairingService.class).setAction(ACTION_CODE).putExtra(KEY_CODE, code);
        try {
            context.startService(i);
        } catch (Throwable t) {
            android.util.Log.w("ClipBridge", "pairing reply ignored (service not running): " + t);
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
