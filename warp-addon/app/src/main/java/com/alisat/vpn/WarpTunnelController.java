package com.alisat.vpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import com.wireguard.crypto.KeyPair;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Cloudflare WARP WireGuard controller with adaptive port selection.
 * It verifies the tunnel using Cloudflare's documented warp=on trace field,
 * benchmarks a small download, and remembers the best port per network type.
 */
public final class WarpTunnelController {
    public interface Callback { void onResult(boolean ok, String message); }

    private static final String REGISTRATION_URL = "https://api.cloudflareclient.com/v0a2158/reg";
    private static final String TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace";
    private static final String SPEED_URL = "https://speed.cloudflare.com/__down?bytes=131072&probe=";
    private static final int REGISTRATION_TIMEOUT_MS = 12000;
    private static final int TRACE_TIMEOUT_MS = 2200;
    private static final int SPEED_TIMEOUT_MS = 2600;
    private static final int SPEED_SAMPLE_BYTES = 131072;
    private static final String PREFS_NAME = "novo_warp_smart_v2";
    private static final String PREF_CIPHER = "account_cipher";
    private static final String PREF_IV = "account_iv";
    private static final String KEY_ALIAS = "novo_player_warp_account_v2";
    private static final int[] DOCUMENTED_WG_PORTS = {2408, 500, 1701, 4500};

    private static volatile boolean connected;
    private static volatile WarpTunnelController instance;
    private static volatile Account memoryAccount;

    public static boolean isConnected() { return connected; }

    public static synchronized WarpTunnelController get(Context context) {
        if (instance == null) instance = new WarpTunnelController(context);
        return instance;
    }

    private final Context appContext;
    private final GoBackend backend;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean backendUp;
    private volatile int selectedPort = -1;
    private volatile long selectedLatencyMs = -1;
    private volatile double selectedMbps = -1;

    private final Tunnel tunnel = new Tunnel() {
        @Override public String getName() { return "Cloudflare-WARP"; }
        @Override public void onStateChange(State state) {
            backendUp = state == State.UP;
            if (state == State.DOWN) connected = false;
        }
    };

    private WarpTunnelController(Context context) {
        appContext = context.getApplicationContext();
        backend = new GoBackend(appContext);
    }

    public android.content.Intent prepare() { return VpnService.prepare(appContext); }

    public void connect(Callback callback) {
        worker.execute(() -> {
            try {
                Account account = loadCachedAccount();
                boolean usedCache = account != null;
                if (account == null) account = registerNewAccount();

                try {
                    ProbeResult best = connectUsingBestRoute(account);
                    post(callback, true, describe(best));
                } catch (Exception firstFailure) {
                    if (!usedCache) throw firstFailure;
                    // A cached registration can be revoked or expire. Refresh once, then retry.
                    disconnectBackendQuietly();
                    clearCachedAccount();
                    Account refreshed = registerNewAccount();
                    ProbeResult best = connectUsingBestRoute(refreshed);
                    post(callback, true, describe(best));
                }
            } catch (Exception e) {
                connected = false;
                disconnectBackendQuietly();
                post(callback, false, "WARP connection failed: " + safeMessage(e));
            }
        });
    }

    public void disconnect(Callback callback) {
        worker.execute(() -> {
            try {
                backend.setState(tunnel, Tunnel.State.DOWN, null);
                connected = false;
                backendUp = false;
                post(callback, true, "Cloudflare WARP disconnected");
            } catch (Exception e) {
                connected = false;
                post(callback, false, "Disconnect failed: " + safeMessage(e));
            }
        });
    }

    public int getSelectedPort() { return selectedPort; }
    public long getSelectedLatencyMs() { return selectedLatencyMs; }
    public double getSelectedMbps() { return selectedMbps; }
    public void close() { }

    private ProbeResult connectUsingBestRoute(Account account) throws Exception {
        List<Integer> ports = getCandidatePorts(account.config);
        if (ports.isEmpty()) throw new IllegalStateException("Cloudflare returned no usable WireGuard ports");

        int preferred = getPreferredPort();
        if (ports.remove(Integer.valueOf(preferred))) ports.add(0, preferred);

        List<ProbeResult> successful = new ArrayList<>();
        Exception lastFailure = null;
        int testedPorts = 0;

        for (int port : ports) {
            testedPorts++;
            boolean attempted = false;
            try {
                Config config = parseTunnelConfig(account, port);
                attempted = true;
                backend.setState(tunnel, Tunnel.State.UP, config);
                backendUp = true;

                ProbeResult result = probeActiveTunnel(port);
                if (result != null && result.warpVerified) successful.add(result);
            } catch (Exception e) {
                lastFailure = e;
            } finally {
                if (attempted || backendUp) disconnectBackendQuietly();
            }

            // Compare at least two paths. If one is already fast and healthy, avoid
            // spending time probing every remaining fallback port.
            if (testedPorts >= 2 && !successful.isEmpty()) {
                ProbeResult currentBest = Collections.max(successful,
                        (a, b) -> Double.compare(a.score(), b.score()));
                if (currentBest.latencyMs <= 180 && currentBest.mbps >= 8.0d) break;
            }
        }

        if (successful.isEmpty()) {
            if (lastFailure != null) throw new IllegalStateException(
                    "No WireGuard port passed the WARP connectivity test: " + safeMessage(lastFailure), lastFailure);
            throw new IllegalStateException("No WireGuard port passed Cloudflare's WARP verification");
        }

        Collections.sort(successful, (a, b) -> Double.compare(b.score(), a.score()));
        Exception finalFailure = null;
        for (ProbeResult candidate : successful) {
            try {
                Config bestConfig = parseTunnelConfig(account, candidate.port);
                backend.setState(tunnel, Tunnel.State.UP, bestConfig);
                backendUp = true;

                ProbeResult verification = probeTraceOnly(candidate.port);
                if (verification == null || !verification.warpVerified) {
                    throw new IllegalStateException("The selected route did not pass final WARP verification");
                }

                connected = true;
                selectedPort = candidate.port;
                selectedLatencyMs = candidate.latencyMs;
                selectedMbps = candidate.mbps;
                savePreferredPort(candidate.port);
                return candidate;
            } catch (Exception e) {
                finalFailure = e;
                disconnectBackendQuietly();
            }
        }
        if (finalFailure != null) throw finalFailure;
        throw new IllegalStateException("Unable to activate the fastest verified WARP route");
    }

    private ProbeResult probeActiveTunnel(int port) {
        try {
            long started = SystemClock.elapsedRealtime();
            String trace = fetchText(TRACE_URL + "?smart=" + System.nanoTime(), TRACE_TIMEOUT_MS);
            long latency = Math.max(1, SystemClock.elapsedRealtime() - started);
            if (!isWarpOn(trace)) return null;

            String colo = readTraceValue(trace, "colo");
            double mbps = measureDownloadMbps();
            return new ProbeResult(port, latency, mbps, colo, true);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ProbeResult probeTraceOnly(int port) {
        final long[] retryDelayMs = {350L, 700L, 1200L};
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(retryDelayMs[attempt - 1]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            try {
                long started = SystemClock.elapsedRealtime();
                String trace = fetchText(TRACE_URL + "?verify=" + System.nanoTime(), TRACE_TIMEOUT_MS);
                long latency = Math.max(1, SystemClock.elapsedRealtime() - started);
                if (isWarpOn(trace)) {
                    return new ProbeResult(port, latency, -1, readTraceValue(trace, "colo"), true);
                }
            } catch (Exception ignored) {
                // Retry in case the WireGuard handshake is still starting.
            }
        }
        return null;
    }

    private double measureDownloadMbps() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(SPEED_URL + System.nanoTime());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(SPEED_TIMEOUT_MS);
            connection.setReadTimeout(SPEED_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestProperty("Accept-Encoding", "identity");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return -1;

            long started = SystemClock.elapsedRealtime();
            int total = 0;
            byte[] buffer = new byte[8192];
            try (InputStream in = connection.getInputStream()) {
                while (total < SPEED_SAMPLE_BYTES) {
                    int read = in.read(buffer, 0, Math.min(buffer.length, SPEED_SAMPLE_BYTES - total));
                    if (read < 0) break;
                    total += read;
                }
            }
            long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
            if (total < SPEED_SAMPLE_BYTES / 2) return -1;
            // Mbps = bits / milliseconds / 1000.
            return (total * 8.0d) / elapsed / 1000.0d;
        } catch (Exception ignored) {
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String fetchText(String address, int timeoutMs) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Connection", "close");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("Cloudflare trace HTTP " + status);
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[2048];
                int total = 0;
                int read;
                while ((read = in.read(buffer)) != -1 && total < 8192) {
                    int accepted = Math.min(read, 8192 - total);
                    out.write(buffer, 0, accepted);
                    total += accepted;
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isWarpOn(String trace) {
        if (trace == null) return false;
        for (String line : trace.split("\\r?\\n")) {
            if ("warp=on".equalsIgnoreCase(line.trim()) || "warp=plus".equalsIgnoreCase(line.trim())) return true;
        }
        return false;
    }

    private static String readTraceValue(String trace, String key) {
        if (trace == null) return "";
        String prefix = key + "=";
        for (String line : trace.split("\\r?\\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private Account registerNewAccount() throws Exception {
        KeyPair keys = new KeyPair();
        JSONObject config = registerWithCloudflare(keys.getPublicKey().toBase64());
        Account account = new Account(keys.getPrivateKey().toBase64(), config);
        saveCachedAccount(account);
        return account;
    }

    private JSONObject registerWithCloudflare(String publicKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(REGISTRATION_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(REGISTRATION_TIMEOUT_MS);
        connection.setReadTimeout(REGISTRATION_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "okhttp/3.12.1");

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
        } finally {
            connection.disconnect();
        }

        JSONObject response = new JSONObject(body);
        if (status < 200 || status >= 300)
            throw new IllegalStateException("Cloudflare registration returned HTTP " + status);
        JSONObject config = response.optJSONObject("config");
        if (config == null && response.optJSONObject("result") != null)
            config = response.getJSONObject("result").optJSONObject("config");
        if (config == null) throw new IllegalStateException("Cloudflare returned no WireGuard config");
        return config;
    }

    private Config parseTunnelConfig(Account account, int port) throws Exception {
        String text = toWireGuardConfig(account.privateKey, account.config, port);
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            return Config.parse(reader);
        }
    }

    private static String toWireGuardConfig(String privateKey, JSONObject config, int selectedPort) throws Exception {
        JSONObject iface = config.getJSONObject("interface");
        JSONObject addresses = iface.getJSONObject("addresses");
        String address = withPrefix(addresses.getString("v4"), "/32");
        String v6 = addresses.optString("v6", "");
        if (!v6.isEmpty()) address += ", " + withPrefix(v6, "/128");

        JSONObject peer = config.getJSONArray("peers").getJSONObject(0);
        JSONObject endpointData = peer.getJSONObject("endpoint");
        String host = endpointData.optString("host", "");
        if (host.isEmpty()) host = endpointData.optString("v4", "");
        if (host.isEmpty()) throw new IllegalStateException("Cloudflare did not return an endpoint host");
        host = normalizeHost(host);
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";

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
                "Endpoint = " + host + ":" + selectedPort + "\n" +
                "PersistentKeepalive = 25\n";
    }

    private static String normalizeHost(String host) {
        String value = host.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 0 ? value.substring(1, end) : value;
        }
        int firstColon = value.indexOf(':');
        if (firstColon >= 0 && firstColon == value.lastIndexOf(':')) {
            String suffix = value.substring(firstColon + 1);
            if (suffix.matches("\\d+")) return value.substring(0, firstColon);
        }
        return value;
    }

    private static List<Integer> getCandidatePorts(JSONObject config) throws Exception {
        JSONObject peer = config.getJSONArray("peers").getJSONObject(0);
        JSONObject endpoint = peer.getJSONObject("endpoint");
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        JSONArray ports = endpoint.optJSONArray("ports");
        if (ports != null) {
            for (int i = 0; i < ports.length(); i++) {
                int port = ports.optInt(i, -1);
                if (port > 0 && port <= 65535) values.add(port);
            }
        } else {
            Object raw = endpoint.opt("ports");
            if (raw instanceof Number) {
                int port = ((Number) raw).intValue();
                if (port > 0 && port <= 65535) values.add(port);
            } else if (raw instanceof String) {
                try {
                    int port = Integer.parseInt((String) raw);
                    if (port > 0 && port <= 65535) values.add(port);
                } catch (NumberFormatException ignored) { }
            }
        }
        // Cloudflare documents 2408 as default and 500/1701/4500 as WireGuard fallbacks.
        for (int port : DOCUMENTED_WG_PORTS) values.add(port);
        List<Integer> result = new ArrayList<>(values);
        if (result.size() > 4) return new ArrayList<>(result.subList(0, 4));
        return result;
    }

    private int getPreferredPort() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("best_port_" + getNetworkTypeKey(), -1);
    }

    private void savePreferredPort(int port) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("best_port_" + getNetworkTypeKey(), port).apply();
    }

    private String getNetworkTypeKey() {
        try {
            ConnectivityManager manager = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
            if (info != null && info.isConnected()) {
                if (info.getType() == ConnectivityManager.TYPE_WIFI) return "wifi";
                if (info.getType() == ConnectivityManager.TYPE_MOBILE) return "mobile";
                return "other_" + info.getType();
            }
        } catch (Exception ignored) { }
        return "unknown";
    }

    private Account loadCachedAccount() {
        if (memoryAccount != null) return memoryAccount;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String ivText = prefs.getString(PREF_IV, null);
        String cipherText = prefs.getString(PREF_CIPHER, null);
        if (ivText == null || cipherText == null) return null;
        try {
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(cipherText, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateCacheKey(), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(encrypted);
            JSONObject saved = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            memoryAccount = new Account(saved.getString("private_key"), new JSONObject(saved.getString("config")));
            return memoryAccount;
        } catch (Exception e) {
            clearCachedAccount();
            return null;
        }
    }

    private void saveCachedAccount(Account account) {
        memoryAccount = account;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            JSONObject saved = new JSONObject()
                    .put("private_key", account.privateKey)
                    .put("config", account.config.toString());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCacheKey());
            byte[] encrypted = cipher.doFinal(saved.toString().getBytes(StandardCharsets.UTF_8));
            SharedPreferences.Editor edit = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
            edit.putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
            edit.putString(PREF_CIPHER, Base64.encodeToString(encrypted, Base64.NO_WRAP));
            edit.apply();
        } catch (Exception ignored) {
            // Keep the in-memory registration; next process launch can register again if encryption is unavailable.
        }
    }

    private SecretKey getOrCreateCacheKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private void clearCachedAccount() {
        memoryAccount = null;
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .remove(PREF_IV).remove(PREF_CIPHER).apply();
    }

    private void disconnectBackendQuietly() {
        try {
            backend.setState(tunnel, Tunnel.State.DOWN, null);
        } catch (Exception ignored) { }
        backendUp = false;
        connected = false;
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

    private static String describe(ProbeResult result) {
        StringBuilder text = new StringBuilder("WARP connected on UDP ").append(result.port)
                .append("; latency ").append(result.latencyMs).append(" ms");
        if (result.mbps > 0) text.append("; test download ").append(String.format(Locale.US, "%.1f", result.mbps)).append(" Mbps");
        if (!result.colo.isEmpty()) text.append("; Cloudflare colo ").append(result.colo);
        return text.toString();
    }

    private void post(Callback callback, boolean ok, String message) {
        if (callback != null) main.post(() -> callback.onResult(ok, message));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class Account {
        final String privateKey;
        final JSONObject config;
        Account(String privateKey, JSONObject config) {
            this.privateKey = privateKey;
            this.config = config;
        }
    }

    private static final class ProbeResult {
        final int port;
        final long latencyMs;
        final double mbps;
        final String colo;
        final boolean warpVerified;
        ProbeResult(int port, long latencyMs, double mbps, String colo, boolean warpVerified) {
            this.port = port;
            this.latencyMs = latencyMs;
            this.mbps = mbps;
            this.colo = colo == null ? "" : colo;
            this.warpVerified = warpVerified;
        }
        double score() {
            return (mbps > 0 ? mbps * 1000.0d : 0.0d) - latencyMs;
        }
    }
}

