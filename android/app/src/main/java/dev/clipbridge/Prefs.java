package dev.clipbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** Small persistent flags for the companion app. */
public final class Prefs {
    private static final String FILE = "clipbridge";
    private static final String KEY_PAIRED = "paired";
    private static final String KEY_CONFIG = "mac_config";

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** True once the app's key has been authorised by this phone's adbd (survives reboots). */
    public boolean isPaired() {
        return sp.getBoolean(KEY_PAIRED, false);
    }

    public void setPaired(boolean paired) {
        sp.edit().putBoolean(KEY_PAIRED, paired).apply();
    }

    /** The Mac's config (shared key + how to reach it), scanned from the Mac's pairing QR. */
    public String macConfig() {
        return sp.getString(KEY_CONFIG, null);
    }

    public void setMacConfig(String json) {
        sp.edit().putString(KEY_CONFIG, json).apply();
    }
}
