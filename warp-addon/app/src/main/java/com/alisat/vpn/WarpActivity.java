package com.alisat.vpn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Performs the overlay's one-tap connect/disconnect action without opening a screen. */
public final class WarpActivity extends Activity {
    public static final String EXTRA_TOGGLE = "com.alisat.vpn.extra.TOGGLE";
    private static final int VPN_CONSENT_REQUEST = 731;
    private WarpTunnelController controller;
    private boolean operationStarted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        controller = WarpTunnelController.get(this);
        if (getIntent().getBooleanExtra(EXTRA_TOGGLE, false)) toggleTunnel();
        else finish();
    }

    private void toggleTunnel() {
        if (operationStarted) return;
        operationStarted = true;
        if (WarpTunnelController.isConnected()) {
            controller.disconnect(this::finishOperation);
            return;
        }

        Intent consent = controller.prepare();
        if (consent == null) startConnection();
        else startActivityForResult(consent, VPN_CONSENT_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_CONSENT_REQUEST) return;
        if (resultCode == RESULT_OK) startConnection();
        else finishOperation(false, "VPN permission was not granted");
    }

    private void startConnection() {
        controller.connect(this::finishOperation);
    }

    private void finishOperation(boolean ok, String message) {
        if (!ok) Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
}
