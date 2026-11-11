package dev.clipbridge;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import dev.clipbridge.adb.WirelessHelper;

/**
 * Debug-only harness for the on-device ADB pairing path. Drive it from a terminal:
 *
 *   adb shell am start -n dev.clipbridge/.TestWirelessActivity \
 *       --ei pairPort <port> --es code <6 digits> [--ei connectPort <port>]
 *
 * Then read results with:  adb logcat -d -s ClipBridgeWL
 */
public final class TestWirelessActivity extends Activity {
    private static final String TAG = "ClipBridgeWL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String host = getIntent().getStringExtra("host");
        if (host == null) host = "127.0.0.1";
        int pairPort = getIntent().getIntExtra("pairPort", 0);
        int connectPort = getIntent().getIntExtra("connectPort", 0);
        String code = getIntent().getStringExtra("code");
        final String fHost = host;

        WirelessHelper.Log log = message -> Log.i(TAG, message);
        new Thread(() -> {
            try {
                if (pairPort > 0 && code != null) {
                    WirelessHelper.pairAndLaunch(this, fHost, pairPort, code, connectPort, null, log);
                } else {
                    WirelessHelper.connectAndLaunch(this, fHost, connectPort, null, log);
                }
                Log.i(TAG, "RESULT ok");
            } catch (Throwable t) {
                Log.e(TAG, "RESULT fail: " + t, t);
            }
            runOnUiThread(this::finish);
        }, "wl-test").start();
    }
}
