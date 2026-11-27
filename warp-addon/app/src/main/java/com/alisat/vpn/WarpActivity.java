package com.alisat.vpn;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

/** Performs the overlay's one-tap connect/disconnect action without opening a screen. */
public final class WarpActivity extends Activity {
    public static final String EXTRA_TOGGLE = "com.alisat.vpn.extra.TOGGLE";
    public static final String EXTRA_SYSTEM_UI_FLAGS = "com.alisat.vpn.extra.SYSTEM_UI_FLAGS";
    public static final String EXTRA_WINDOW_FLAGS = "com.alisat.vpn.extra.WINDOW_FLAGS";
    private static final int VPN_CONSENT_REQUEST = 731;
    private WarpTunnelController controller;
    private boolean operationStarted;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Do not force orientation: the manifest uses "behind" so this
        // transparent dispatcher follows the current player screen.
        try {
            Intent launch = getIntent();
            if (getWindow() != null) {
                int uiFlags = launch.getIntExtra(EXTRA_SYSTEM_UI_FLAGS, 0);
                getWindow().getDecorView().setSystemUiVisibility(uiFlags);
                int safeFlags = WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                        | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION;
                getWindow().addFlags(launch.getIntExtra(EXTRA_WINDOW_FLAGS, 0) & safeFlags);
            }
            controller = WarpTunnelController.get(this);
            if (launch.getBooleanExtra(EXTRA_TOGGLE, false)) toggleTunnel();
            else finish();
        } catch (RuntimeException error) {
            finishOperation(false, error.getMessage());
        }
    }

    private void toggleTunnel() {
        if (operationStarted) return;
        operationStarted = true;
        try {
            if (WarpTunnelController.isConnected()) {
                controller.disconnect(this::finishOperation);
                return;
            }
            // Ask Android for VPN consent before applying the server entitlement
            // gate. The consent Activity must remain alive to receive its result.
            Intent consent = controller.prepare();
            if (consent == null) startConnection();
            else startActivityForResult(consent, VPN_CONSENT_REQUEST);
        } catch (RuntimeException error) {
            finishOperation(false, error.getMessage());
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != VPN_CONSENT_REQUEST) return;
        if (resultCode == RESULT_OK) startConnection();
        else finishOperation(false, "VPN permission was not granted");
    }

    private void startConnection() {
        try {
            if (!isVpnAuthorized()) {
                Toast.makeText(this, "WARP VPN is not enabled for this device", Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            controller.connect(this::finishOperation);
        } catch (RuntimeException error) {
            finishOperation(false, error.getMessage());
        }
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
