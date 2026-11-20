package com.gaeul.allin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Resolves the public compatibility version according to the Antigravity 0.2.4 plugin. */
final class AntigravityVersion {
    private AntigravityVersion() {}

    private static final String MANIFEST_URL = "https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/manifest/latest-arm64-mac.yml";
    private static final long CACHE_MS = TimeUnit.HOURS.toMillis(6);
    private static final long RETRY_MS = TimeUnit.MINUTES.toMillis(1);
    private static final int TIMEOUT_MS = 15_000;
    private static final int MANIFEST_BYTES = 64 * 1024;
    private static final Object LOCK = new Object();

    // All state is process-local and protected by LOCK.
    private static String version = AntigravityProtocol.VERSION;
    private static long checkedAt;
    private static long cacheMs;
    private static long startedAt;
    private static FutureTask<String> pending;

    static String resolve() {
        FutureTask<String> refresh;
        long remaining;
        synchronized (LOCK) {
            long now = System.nanoTime();
            if (cacheMs > 0 && elapsedMs(checkedAt, now) < cacheMs) return version;
            if (pending == null) {
                final NetworkPolicy.Snapshot network = NetworkPolicy.capture();
                final long start = now;
                pending = new FutureTask<>(() -> refresh(network, start));
                startedAt = start;
                Thread worker = new Thread(pending, "antigravity-version");
                worker.setDaemon(true);
                worker.start();
            }
            refresh = pending;
            remaining = TIMEOUT_MS - elapsedMs(startedAt, now);
            if (remaining <= 0) return version;
        }
        try {
            return refresh.get(remaining, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // The metadata lookup is best-effort. Requests can use the last known version.
        }
        synchronized (LOCK) {
            return version;
        }
    }

    private static String refresh(NetworkPolicy.Snapshot network, long started) {
        String resolved = null;
        try {
            resolved = fetch(network, started);
        } catch (IOException | RuntimeException ignored) {
            // Keep the last known version and retry on demand after the short cooldown.
        }
        synchronized (LOCK) {
            if (remainingMs(started) <= 0) resolved = null;
            if (resolved != null) version = resolved;
            checkedAt = System.nanoTime();
            cacheMs = resolved == null ? RETRY_MS : CACHE_MS;
            pending = null;
            return version;
        }
    }

    private static String fetch(NetworkPolicy.Snapshot network, long started) throws IOException {
        HttpURLConnection connection = network.openHttp(MANIFEST_URL);
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            // This unauthenticated GET never carries an account credential.
            connection.setRequestProperty("User-Agent", "electron-builder");
            int timeout = remainingMs(started);
            if (timeout <= 0) return null;
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) return null;
            ByteArrayOutputStream data = new ByteArrayOutputStream(4096);
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[4096];
                while (data.size() < MANIFEST_BYTES) {
                    if (remainingMs(started) <= 0) return null;
                    int count = in.read(chunk, 0, Math.min(chunk.length, MANIFEST_BYTES - data.size()));
                    if (count < 0) break;
                    data.write(chunk, 0, count);
                }
            }
            String text = new String(data.toByteArray(), StandardCharsets.UTF_8);
            // Don't parse a potentially truncated final line at the size limit.
            if (data.size() == MANIFEST_BYTES) text = text.substring(0, text.lastIndexOf('\n') + 1);
            return parse(text);
        } finally {
            connection.disconnect();
        }
    }

    private static String parse(String text) {
        for (String line : text.split("\\r?\\n")) {
            if (!line.startsWith("version:")) continue;
            String value = line.substring("version:".length()).trim();
            if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'"))
                    || (value.startsWith("\"") && value.endsWith("\"")))) {
                value = value.substring(1, value.length() - 1);
            }
            return value.length() <= 32 && value.matches("[0-9]+(?:\\.[0-9]+)+") ? value : null;
        }
        return null;
    }

    private static long elapsedMs(long then, long now) {
        return TimeUnit.NANOSECONDS.toMillis(now - then);
    }

    private static int remainingMs(long started) {
        return (int) Math.max(0, TIMEOUT_MS - elapsedMs(started, System.nanoTime()));
    }
}
