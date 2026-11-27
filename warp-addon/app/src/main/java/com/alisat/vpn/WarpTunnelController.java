package com.alisat.vpn;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/** App-side controller for the MASQUE-backed Android VPN service. */
public final class WarpTunnelController {
    public interface Callback { void onResult(boolean ok, String message); }

    private static volatile WarpTunnelController instance;
    private static volatile boolean connected;
    private static volatile Callback pendingCallback;

    private final Context appContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WarpTunnelController(Context context) {
        appContext = context.getApplicationContext();
    }

    public static synchronized WarpTunnelController get(Context context) {
        if (instance == null) instance = new WarpTunnelController(context);
        return instance;
    }

    public static boolean isConnected() { return connected; }

    public Intent prepare() { return VpnService.prepare(appContext); }

    public void connect(Callback callback) {
        pendingCallback = callback;
        connected = false;
        Intent intent = new Intent(appContext, WarpVpnService.class)
                .setAction(WarpVpnService.ACTION_CONNECT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) appContext.startForegroundService(intent);
        else appContext.startService(intent);
    }

    public void disconnect(Callback callback) {
        pendingCallback = callback;
        Intent intent = new Intent(appContext, WarpVpnService.class)
                .setAction(WarpVpnService.ACTION_DISCONNECT);
        appContext.startService(intent);
    }

    static void reportConnected() {
        connected = true;
        report(true, "WARP connected using MASQUE");
    }

    static void reportFailure(String message) {
        connected = false;
        report(false, message == null || message.trim().isEmpty() ? "MASQUE connection failed" : message);
    }

    static void reportDisconnected() {
        connected = false;
        report(true, "WARP disconnected");
    }

    private static void report(boolean ok, String message) {
        WarpTunnelController current = instance;
        Callback callback = pendingCallback;
        pendingCallback = null;
        if (current != null && callback != null)
            current.main.post(() -> callback.onResult(ok, message));
    }
}
