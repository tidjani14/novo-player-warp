package com.alisat.vpn;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
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
        // The original player is landscape. Keep this transparent dispatcher in
        // landscape too, so launching it never rotates the visible player UI.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
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

        if (!isVpnAuthorized()) {
            Toast.makeText(this, "VPN access is not enabled for this device", Toast.LENGTH_LONG).show();
            finish();
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
        // Keep connect/disconnect silent; the toggle reflects the resulting state.
        finish();
    }

    /** Read the VPN entitlement delivered in the app's existing auth response. */
    private boolean isVpnAuthorized() {
        try {
            Class<?> preferenceClass = Class.forName("com.shadeed.ibopro.helper.PreferenceHelper");
            Object preferences = preferenceClass
                    .getConstructor(android.content.Context.class)
                    .newInstance(getApplicationContext());
            Object appInfo = preferenceClass.getMethod("getSharedPreferenceAppInfo").invoke(preferences);
            if (appInfo == null) return false;
            Object allowed = appInfo.getClass().getMethod("isVpn_allowed").invoke(appInfo);
            return Boolean.TRUE.equals(allowed);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }
}
