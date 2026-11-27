package com.alisat.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import usqueandroid.Usqueandroid;
import usqueandroid.SocketProtector;
import usqueandroid.VpnStateCallback;

/** Android TUN frontend for the upstream Cloudflare WARP MASQUE engine. */
public final class WarpVpnService extends VpnService implements SocketProtector {
    public static final String ACTION_CONNECT = "com.alisat.vpn.action.MASQUE_CONNECT";
    public static final String ACTION_DISCONNECT = "com.alisat.vpn.action.MASQUE_DISCONNECT";
    private static final String TAG = "NovoWarpMasque";
    private static final String CHANNEL = "novo_warp_masque";
    private static final int NOTIFICATION_ID = 7311;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile int tunFd = -1;
    private volatile ParcelFileDescriptor tun;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_CONNECT : intent.getAction();
        if (ACTION_DISCONNECT.equals(action)) {
            stopping.set(true);
            worker.execute(() -> stopTunnel(true));
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (running.compareAndSet(false, true)) {
            stopping.set(false);
            worker.execute(this::startTunnel);
        }
        return START_STICKY;
    }

    private void startTunnel() {
        try {
            File config = new File(getFilesDir(), "warp-masque/config.json");
            File parent = config.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
                throw new IllegalStateException("Cannot create private WARP storage");

            boolean registered;
            try { registered = config.isFile() && Usqueandroid.isRegistered(config.getAbsolutePath()); }
            catch (Throwable ignored) { registered = false; }
            if (!registered) {
                String registrationError = Usqueandroid.register(config.getAbsolutePath(), "Android");
                if (registrationError != null && !registrationError.trim().isEmpty())
                    throw new IllegalStateException("Cloudflare MASQUE registration failed: " + registrationError);
                if (!Usqueandroid.isRegistered(config.getAbsolutePath()))
                    throw new IllegalStateException("Cloudflare registration returned no usable MASQUE identity");
            }

            // Use the MASQUE engine's registered default endpoint and SNI. Do not
            // probe/benchmark endpoints or silently fall back to WireGuard.
            Usqueandroid.resetConnectionOptions();
            String ipv4 = safeAddress(Usqueandroid.getAssignedIPv4(config.getAbsolutePath()), "172.16.0.2");
            Builder builder = new Builder()
                    .setSession("Cloudflare WARP · MASQUE")
                    .setMtu(1280)
                    .addAddress(ipv4, 32)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("1.0.0.1")
                    .addRoute("0.0.0.0", 0);

            String ipv6 = safeAddress(Usqueandroid.getAssignedIPv6(config.getAbsolutePath()), "");
            if (!ipv6.isEmpty() && ipv6.indexOf(':') >= 0) {
                builder.addAddress(ipv6, 128)
                        .addRoute("::", 0)
                        .addDnsServer("2606:4700:4700::1111")
                        .addDnsServer("2606:4700:4700::1001");
            }

            tun = builder.establish();
            if (tun == null) throw new IllegalStateException("Android refused to establish the VPN interface");
            tunFd = tun.detachFd();
            tun = null;

            String error = Usqueandroid.startTunnelWithFdProtected(
                    config.getAbsolutePath(), (long) tunFd, new VpnStateCallback() {
                        @Override public void onConnected() {
                            Log.i(TAG, "MASQUE tunnel connected");
                            WarpTunnelController.reportConnected();
                        }
                        @Override public void onDisconnected(String reason) {
                            Log.w(TAG, "MASQUE tunnel disconnected: " + reason);
                            if (stopping.get()) WarpTunnelController.reportDisconnected();
                            else WarpTunnelController.reportFailure("MASQUE tunnel disconnected: " + reason);
                            stopTunnel(false);
                        }
                        @Override public void onError(String message) {
                            Log.e(TAG, "MASQUE tunnel error: " + message);
                            WarpTunnelController.reportFailure(message);
                            stopTunnel(false);
                        }
                    }, this);
            if (error != null && !error.trim().isEmpty())
                throw new IllegalStateException("MASQUE engine failed to start: " + error);
        } catch (Throwable error) {
            Log.e(TAG, "MASQUE start failed", error);
            WarpTunnelController.reportFailure(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
            stopTunnel(false);
        }
    }

    /** Called by the Go MASQUE engine before its QUIC socket connects. */
    @Override public boolean protect(int socketFd) {
        return super.protect(socketFd);
    }

    private static String safeAddress(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private void stopTunnel(boolean userRequested) {
        if (userRequested) stopping.set(true);
        running.set(false);
        try { Usqueandroid.stopTunnel(); }
        catch (Throwable e) { Log.w(TAG, "Native MASQUE stop failed", e); }
        try { if (tun != null) tun.close(); }
        catch (Exception e) { Log.w(TAG, "TUN close failed", e); }
        tun = null;
        if (tunFd >= 0) {
            try { Os.close(fileDescriptorFromInt(tunFd)); }
            catch (Exception e) { Log.w(TAG, "Detached TUN close failed", e); }
            tunFd = -1;
        }
        if (userRequested) WarpTunnelController.reportDisconnected();
        stopForeground(true);
        stopSelf();
    }

    private FileDescriptor fileDescriptorFromInt(int value) throws Exception {
        FileDescriptor descriptor = new FileDescriptor();
        java.lang.reflect.Field field = FileDescriptor.class.getDeclaredField("descriptor");
        field.setAccessible(true);
        field.setInt(descriptor, value);
        return descriptor;
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "WARP MASQUE", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pending = PendingIntent.getActivity(this, 0,
                new Intent(this, WarpActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        Notification notification = builder.setContentTitle("Cloudflare WARP")
                .setContentText("MASQUE tunnel is running")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override public void onRevoke() {
        stopping.set(true);
        worker.execute(() -> stopTunnel(true));
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopping.set(true);
        try { Usqueandroid.stopTunnel(); } catch (Throwable ignored) { }
        worker.shutdownNow();
        super.onDestroy();
    }
}
