package com.alisat.vpn;

import android.content.Context;
import android.net.VpnService;
import android.os.Handler;
import android.os.Looper;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native in-app WireGuard tunnel controller for Cloudflare WARP. */
public final class WarpTunnelController {
    public interface Callback { void onResult(boolean ok, String message); }
    private static final String REGISTRATION_URL = "https://api.cloudflareclient.com/v0a2158/reg";
    private static final int TIMEOUT_MS = 15000;
    private static volatile boolean connected;
    private static volatile WarpTunnelController instance;
    public static boolean isConnected() { return connected; }
    public static synchronized WarpTunnelController get(Context context) {
        if (instance == null) instance = new WarpTunnelController(context);
        return instance;
    }
    private final Context appContext;
    private final GoBackend backend;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "Cloudflare-WARP"; }
        @Override public void onStateChange(State state) { connected = state == State.UP; }
    };

    private WarpTunnelController(Context context) {
        appContext = context.getApplicationContext();
        backend = new GoBackend(appContext);
    }

    public android.content.Intent prepare() { return VpnService.prepare(appContext); }

    public void connect(Callback callback) {
        worker.execute(() -> {
            try {
                KeyPair keys = new KeyPair();
                JSONObject result = registerWithCloudflare(keys.getPublicKey().toBase64());
                String text = toWireGuardConfig(keys.getPrivateKey().toBase64(), result);
                Config config;
                try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
                    config = Config.parse(reader);
                }
                backend.setState(tunnel, Tunnel.State.UP, config);
                connected = true;
                connected = true;
                connected = true;
                connected = true;
                connected = true;
                post(callback, true, "Cloudflare WARP connected");
            } catch (Exception e) {
                post(callback, false, "Connection failed: " + safeMessage(e));
            }
        });
    }

    public void disconnect(Callback callback) {
        worker.execute(() -> {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
                connected = false;
                connected = false;
                connected = false;
                connected = false;
                connected = false;
                post(callback, true, "Cloudflare WARP disconnected");
            } catch (Exception e) {
                post(callback, false, "Disconnect failed: " + safeMessage(e));
            }
        });
    }

    public void close() { }

    private JSONObject registerWithCloudflare(String publicKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(REGISTRATION_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");

        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject request = new JSONObject()
                .put("key", publicKey)
                .put("install_id", "")
                .put("fcm_token", "")
                .put("tos", iso.format(new Date()))
                .put("model", "Android")
                .put("type", "Android")
                .put("locale", Locale.getDefault().toString());
        try (OutputStream out = connection.getOutputStream()) {
            out.write(request.toString().getBytes(StandardCharsets.UTF_8));
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String body;
        try (InputStream in = stream;
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) text.append(line);
            body = text.toString();
        } finally { connection.disconnect(); }

        JSONObject response = new JSONObject(body);
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Cloudflare registration returned HTTP " + status);
        }
        JSONObject config = response.optJSONObject("config");
        if (config == null && response.optJSONObject("result") != null)
            config = response.getJSONObject("result").optJSONObject("config");
        if (config == null) throw new IllegalStateException("Cloudflare returned no WireGuard config");
        return config;
    }

    private static String toWireGuardConfig(String privateKey, JSONObject config) throws Exception {
        JSONObject iface = config.getJSONObject("interface");
        JSONObject addresses = iface.getJSONObject("addresses");
        String address = withPrefix(addresses.getString("v4"), "/32");
        String v6 = addresses.optString("v6", "");
        if (!v6.isEmpty()) address += ", " + withPrefix(v6, "/128");

        JSONObject peer = config.getJSONArray("peers").getJSONObject(0);
        JSONObject endpointData = peer.getJSONObject("endpoint");
        String host = endpointData.getString("host");
        Object ports = endpointData.get("ports");
        Object selectedPort = ports instanceof JSONArray
                ? ((JSONArray) ports).get(0) : ports;
        String port = String.valueOf(selectedPort);
        if (host.startsWith("[")) {
            int bracket = host.indexOf(']');
            if (bracket > 0) host = host.substring(1, bracket);
        } else if (host.indexOf(':') >= 0 && host.indexOf(':') == host.lastIndexOf(':')
                && host.substring(host.lastIndexOf(':') + 1).matches("\\d+")) {
            host = host.substring(0, host.lastIndexOf(':'));
        }
        if (host.indexOf(':') >= 0) host = "[" + host + "]";
        JSONArray dns = iface.optJSONArray("dns");
        String dnsText = dns == null || dns.length() == 0 ? "1.1.1.1, 1.0.0.1" : join(dns);

        return "[Interface]\n" +
                "PrivateKey = " + privateKey + "\n" +
                "Address = " + address + "\n" +
                "DNS = " + dnsText + "\n" +
                "MTU = 1280\n\n" +
                "[Peer]\n" +
                "PublicKey = " + peer.getString("public_key") + "\n" +
                "AllowedIPs = 0.0.0.0/0, ::/0\n" +
                "Endpoint = " + host + ":" + port + "\n" +
                "PersistentKeepalive = 25\n";
    }

    private static String withPrefix(String address, String prefix) {
        return address.contains("/") ? address : address + prefix;
    }
    private static String join(JSONArray values) throws Exception {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length(); i++) {
            if (i != 0) out.append(", ");
            out.append(values.getString(i));
        }
        return out.toString();
    }
    private void post(Callback callback, boolean ok, String message) {
        if (callback != null) main.post(() -> callback.onResult(ok, message));
    }
    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
