package com.gaeul.allin;

import fi.iki.elonen.NanoHTTPD;
import com.gaeul.allin.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Loopback Gateway regression: real gift transport and plan HTTP, synthetic credentials only. */
public final class ZCodeBoundaryGatewayHttpTest {
    static void check(boolean ok, String msg) { if (!ok) throw new AssertionError(msg); }
    static final class Reply { int status; String body; }
    static Reply post(int port, String path, boolean stream) throws Exception {
        JSONObject b = new JSONObject().put("model", ZCodeGiftUsageHttpTest.MODEL).put("stream", stream).put("max_tokens", 64);
        if (path.endsWith("/responses")) b.put("input", "hello");
        else b.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
        HttpURLConnection c = (HttpURLConnection)new URL("http://127.0.0.1:" + port + path).openConnection();
        c.setConnectTimeout(5000); c.setReadTimeout(15000); c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer fixture-gateway"); c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("anthropic-version", "2023-06-01"); c.setRequestProperty("Accept-Encoding", "identity");
        try (OutputStream out = c.getOutputStream()) { out.write(b.toString().getBytes(StandardCharsets.UTF_8)); }
        try { Reply r = new Reply(); r.status = c.getResponseCode(); InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : ZCodeGiftUsageHttpTest.read(in); if (in != null) in.close(); return r;
        } finally { c.disconnect(); }
    }
    static void run(boolean gift, String path, boolean stream, boolean truncated) throws Exception {
        String payload = gift ? (stream ? ZCodeGiftUsageHttpTest.stream("full", !truncated) : ZCodeGiftUsageHttpTest.json("full"))
                : (stream ? VendorPolicyGatewayHttpTest.SSE : VendorPolicyGatewayHttpTest.CHAT);
        if (!gift && stream && truncated) payload = payload.substring(0, payload.indexOf("data: {", 6));
        if (!stream && truncated) payload = payload.substring(0, payload.length()-8);
        final String response = payload;
        try (VendorPolicyGatewayHttpTest.Upstream u = new VendorPolicyGatewayHttpTest.Upstream((request, n) -> {
            VendorPolicyGatewayHttpTest.Reply r = new VendorPolicyGatewayHttpTest.Reply(200, stream ? "text/event-stream" : "application/json", response);
            return truncated ? r.truncated(50) : r;
        })) {
            ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests();
            ZCodeGift.setFixtureBase("http://127.0.0.1:" + u.server.getLocalPort());
            JSONObject cfg = ZCodeGiftUsageHttpTest.config(u.server.getLocalPort());
            if (!gift) cfg.getJSONArray("providers").getJSONObject(0).remove("accounts");
            ZCodeGiftBusyGatewayHttpTest.Transport transport = new ZCodeGiftBusyGatewayHttpTest.Transport();
            ZCodeGiftUsageHttpTest.Gateway g = new ZCodeGiftUsageHttpTest.Gateway(cfg, transport);
            g.gateway.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp("http://127.0.0.1:" + u.server.getLocalPort() + new URL(url).getFile()));
            g.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
            try {
                Reply r = post(g.getListeningPort(), path, stream); ZCodeGiftUsageHttpTest.idle(g);
                String label = (gift ? "gift" : "plan") + " " + path + " stream=" + stream + " truncated=" + truncated;
                check(u.requests.size() == 1, label + " replay count=" + u.requests.size());
                if (!truncated) check(r.status == 200 && r.body.contains(gift || stream ? "hello" : "ok"), label + " status=" + r.status + " body=" + r.body);
                else if (!stream) check(r.status == 502, label + " expected 502 got " + r.status + " " + r.body);
                else {
                    check(r.status == 200, label + " expected committed SSE 200 got " + r.status);
                    check(r.body.contains("error"), label + " missing SSE error: " + r.body);
                    check(!r.body.contains("[DONE]") && !r.body.contains("event: message_stop") && !r.body.contains("response.completed"), label + " false completion: " + r.body);
                }
            } finally { g.stop(); ZCodeGift.setFixtureBase(null); ZCodeGift.resetBusyForTests(); ZCodeGift.resetCooldownForTests(); }
        }
    }
    static void checkSubscriptionGate() throws Exception {
        for (int scenario : new int[]{200, 503, 204}) {
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger paid = new java.util.concurrent.atomic.AtomicInteger();
            try (VendorPolicyGatewayHttpTest.Upstream u = new VendorPolicyGatewayHttpTest.Upstream((request, n) -> {
                if (request.path.endsWith("/api/biz/subscription/list")) {
                    reads.incrementAndGet();
                    int status = scenario == 503 ? 503 : 200;
                    String reply = scenario == 200 ? "{\"data\":[{\"status\":\"VALID\"}]}" : "{\"data\":[]}";
                    return new VendorPolicyGatewayHttpTest.Reply(status, "application/json", reply);
                }
                paid.incrementAndGet();
                return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", VendorPolicyGatewayHttpTest.CHAT);
            })) {
                JSONObject config = ZCodeGiftUsageHttpTest.config(u.server.getLocalPort());
                config.getJSONArray("providers").getJSONObject(0).getJSONArray("accounts").getJSONObject(0)
                        .put("id", "zcode-test-session").put("token", "fixture-key");
                ZCodeGiftBusyGatewayHttpTest.Transport gift = new ZCodeGiftBusyGatewayHttpTest.Transport();
                gift.allowed = false;
                ZCodeGiftUsageHttpTest.Gateway gateway = new ZCodeGiftUsageHttpTest.Gateway(config, gift);
                gateway.gateway.setVendorConnectionsForTests(url -> NetworkPolicy.openHttp("http://127.0.0.1:" + u.server.getLocalPort() + new URL(url).getFile()));
                gateway.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
                try {
                    for (int i = 0; i < 2; i++) {
                        Reply reply = post(gateway.getListeningPort(), "/v1/responses", false);
                        int expected = scenario == 204 ? 402 : 200;
                        check(reply.status == expected, "subscription state " + scenario + " actual status " + reply.status + " reads=" + reads + " paid=" + paid + " gift=" + gift.opens + " body=" + reply.body);
                    }
                    check(reads.get() == 1 && paid.get() == (scenario == 204 ? 0 : 2), "subscription state " + scenario + " exact plan reads and paid requests " + reads + "/" + paid);
                } finally { gateway.stop(); }
            }
        }
        System.out.println("ZCode plan gate PASS: confirmed, absent and unavailable subscriptions");
    }

    public static void main(String[] args) throws Exception {
        checkSubscriptionGate();
        List<String> failures = new ArrayList<>();
        for (boolean gift : new boolean[]{true, false}) for (String path : new String[]{"/v1/chat/completions", "/v1/messages", "/v1/responses"})
            for (boolean stream : new boolean[]{false, true}) for (boolean truncated : new boolean[]{false, true}) {
                if (gift && path.endsWith("/responses")) { System.out.println("SKIP gift Responses: fixture returns Anthropic where adapter expects Chat"); continue; }
                String label = (gift ? "gift" : "plan") + " " + path + " stream=" + stream + " truncated=" + truncated;
                try { run(gift, path, stream, truncated); System.out.println("PASS " + label); }
                catch (Throwable e) { failures.add(label + ": " + e); System.out.println("FAIL " + label + ": " + e); }
            }
        if (!failures.isEmpty()) throw new AssertionError(failures.size() + " failures: " + failures);
    }
}
