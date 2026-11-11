package dev.clipbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.RemoteInput;

/** Receives the pairing code typed into the notification's inline reply. */
public final class PairReplyReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String code = null;
        android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
        if (results != null) {
            CharSequence c = results.getCharSequence(PairingService.KEY_CODE);
            if (c != null) code = c.toString().trim();
        }
        if (code == null) {
            CharSequence c = intent.getCharSequenceExtra(PairingService.KEY_CODE);
            if (c != null) code = c.toString().trim();
        }
        PairingService.deliverCode(context, code);
    }
}
