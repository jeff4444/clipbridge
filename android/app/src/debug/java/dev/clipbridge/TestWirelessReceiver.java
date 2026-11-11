package dev.clipbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import dev.clipbridge.adb.WirelessHelper;

/**
 * Debug-only. Runs the pairing in the background so the Settings "Pair with code" dialog stays in
 * the foreground (and its pairing server stays alive). Drive it with:
 *
 *   adb shell am broadcast -a dev.clipbridge.PAIR -p dev.clipbridge \
 *       --es host <wifi-ip> --ei pairPort <port> --es code <6 digits>
 *
 * Read results with:  adb logcat -d -s ClipBridgeWL
 */
public final class TestWirelessReceiver extends BroadcastReceiver {
    private static final String TAG = "ClipBridgeWL";

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context app = context.getApplicationContext();
        // Test hook: forward a code into the running PairingService (the real path uses a
        // non-exported receiver reachable only by the system's notification reply).
        String toService = intent.getStringExtra("toService");
        if (toService != null) {
            PairingService.deliverCode(app, toService);
            return;
        }
        // Test hook: inject a scanned Mac config (the real path uses the camera QR scanner).
        String macConfig = intent.getStringExtra("macConfig");
        if (macConfig != null) {
            new Prefs(app).setMacConfig(macConfig);
            android.util.Log.i("ClipBridgeWL", "macConfig stored (" + macConfig.length() + " chars)");
            return;
        }
        String host = intent.getStringExtra("host");
        final String fHost = host != null ? host : "127.0.0.1";
        final int pairPort = intent.getIntExtra("pairPort", 0);
        final int connectPort = intent.getIntExtra("connectPort", 0);
        final String code = intent.getStringExtra("code");
        final String cfg = new Prefs(app).macConfig(); // exercise the config-write path when present
        final var pending = goAsync();
        WirelessHelper.Log log = message -> Log.i(TAG, message);
        new Thread(() -> {
            try {
                if (pairPort > 0 && code != null) {
                    WirelessHelper.pairAndLaunch(app, fHost, pairPort, code, connectPort, cfg, log);
                } else {
                    WirelessHelper.connectAndLaunch(app, fHost, connectPort, cfg, log);
                }
                Log.i(TAG, "RESULT ok");
            } catch (Throwable t) {
                Log.e(TAG, "RESULT fail: " + t, t);
            } finally {
                pending.finish();
            }
        }, "wl-test").start();
    }
}
