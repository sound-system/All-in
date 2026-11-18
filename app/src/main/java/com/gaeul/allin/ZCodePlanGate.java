package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONTokener;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verify entitlement before using a key created as part of a ZCode session login. */
final class ZCodePlanGate {
    enum State { ACTIVE, NO_PLAN, UNKNOWN }

    private static final long ACTIVE_MS = 60_000;
    private static final long FAILURE_MS = 15_000;
    private final Map<String, Reading> readings = new LinkedHashMap<>();

    private static final class Reading {
        State result = State.UNKNOWN;
        long until;
        boolean reading;
    }

    State state(String identity, String site, String key, GatewayServer.VendorConnectionFactory factory) {
        Reading reading;
        synchronized (this) {
            reading = readings.get(identity);
            if (reading != null && reading.reading) {
                long end = System.nanoTime() + 1_000_000_000L;
                try {
                    while (reading.reading && end - System.nanoTime() > 0) {
                        java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, end - System.nanoTime());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (reading.reading || Thread.currentThread().isInterrupted()) return State.UNKNOWN;
            }
            if (reading != null && System.nanoTime() < reading.until) return reading.result;
            if (reading == null) {
                if (readings.size() >= 128) readings.remove(readings.keySet().iterator().next());
                reading = new Reading();
                readings.put(identity, reading);
            }
            reading.reading = true;
        }
        State fresh = read(site, key, factory);
        synchronized (this) {
            reading.result = fresh;
            reading.until = System.nanoTime() + (fresh == State.ACTIVE ? ACTIVE_MS : FAILURE_MS) * 1_000_000L;
            reading.reading = false;
            notifyAll();
        }
        return fresh;
    }

    static State parse(String response) {
        JSONTokener parser = new JSONTokener(response);
        Object payload = parser.nextValue();
        if (parser.nextClean() != 0) return State.UNKNOWN;
        if (payload instanceof JSONObject) {
            JSONObject envelope = (JSONObject) payload;
            String code = envelope.optString("code");
            if (envelope.has("code") && !"0".equals(code) && !"200".equals(code)) return State.UNKNOWN;
            payload = envelope.opt("data");
        }
        if (!(payload instanceof JSONArray)) return State.UNKNOWN;
        JSONArray plans = (JSONArray) payload;
        for (int i = 0; i < plans.length(); i++) {
            JSONObject plan = plans.optJSONObject(i);
            if (plan == null || !(plan.opt("status") instanceof String) || plan.optString("status").trim().isEmpty()) return State.UNKNOWN;
            if ("VALID".equalsIgnoreCase(plan.optString("status"))) return State.ACTIVE;
        }
        return State.NO_PLAN;
    }

    private static State read(String site, String key, GatewayServer.VendorConnectionFactory factory) {
        HttpURLConnection connection = null;
        try {
            String root = "bigmodel".equals(site) ? ZCodeAuth.BIGMODEL_API : ZCodeAuth.ZAI_API;
            connection = factory.open(root + "/api/biz/subscription/list");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", ZCodeAuth.UA);
            connection.setRequestProperty("Authorization", key);
            if (connection.getResponseCode() != 200) return State.UNKNOWN;
            return parse(BoundedResponse.readUtf8(connection.getInputStream(), 128 * 1024, 2500));
        } catch (Exception failure) {
            return State.UNKNOWN;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
