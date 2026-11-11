package dev.clipbridge.adb;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import java.security.PrivateKey;
import java.security.cert.Certificate;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * ClipBridge's view of the on-device ADB daemon. The key pair is persistent, so once the user has
 * paired (a one-time six-digit code), reconnecting after a reboot needs no further interaction.
 */
public final class ClipAdbManager extends AbsAdbConnectionManager {
    private static ClipAdbManager instance;

    private final AdbKeyStore keyStore;

    public static synchronized ClipAdbManager getInstance(@NonNull Context context) throws Exception {
        if (instance == null) {
            instance = new ClipAdbManager(context.getApplicationContext());
        }
        return instance;
    }

    private ClipAdbManager(@NonNull Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        keyStore = new AdbKeyStore(context);
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return keyStore.getPrivateKey();
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return keyStore.getCertificate();
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "ClipBridge";
    }
}
