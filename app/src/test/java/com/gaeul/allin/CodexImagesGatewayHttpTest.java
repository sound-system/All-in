package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;

/** Exercises the gateway's public image route and catalog against a Codex fixture. */
public final class CodexImagesGatewayHttpTest {
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static String get(VendorPolicyGatewayHttpTest.Gateway gateway, String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + gateway.getListeningPort() + path).openConnection();
        conn.setRequestProperty("Authorization", "Bearer fixture-auth");
        conn.setReadTimeout(5000);
        try (java.io.InputStream in = conn.getInputStream()) { return ZCodeGiftUsageHttpTest.read(in); }
        finally { conn.disconnect(); }
    }

    public static void main(String[] args) throws Exception {
        TestPrivacyScope.initialize();
        try (VendorPolicyGatewayHttpTest.Upstream upstream = new VendorPolicyGatewayHttpTest.Upstream((r, ordinal) -> {
            check("/backend-api/codex/images/generations".equals(r.path), "upstream path");
            check("Bearer fixture-subscription".equals(r.headers.get("Authorization")), "subscription credential");
            check("fixture-account".equals(r.headers.get("chatgpt-account-id")), "account id");
            check("application/json".equals(r.headers.get("Accept")), "JSON response");
            check(r.headers.containsKey("x-codex-imagegen-request-id") && r.headers.containsKey("x-codex-image-turn-id"), "image identifiers");
            check(!r.body.has("response_format") && !r.body.has("complexity") && !r.body.has("stream"), "upstream field allowlist");
            if (ordinal == 1) {
                check("gpt-image-2".equals(r.body.optString("model")) && r.body.optInt("n") == 1, "canonical generation fields");
                check("1024x1024".equals(r.body.optString("size")) && "low".equals(r.body.optString("quality")), "official image settings");
                return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", "{\"created\":1,\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]}");
            }
            if (ordinal == 2) {
                check("gpt-image-2.5".equals(r.body.optString("model")), "saved alias reaches the same drawer");
                return new VendorPolicyGatewayHttpTest.Reply(429, "application/json", "{\"error\":\"not forwarded\"}");
            }
            return new VendorPolicyGatewayHttpTest.Reply(200, "application/json", "{\"data\":[]}");
        })) {
            JSONObject account = new JSONObject().put("id", "subscription").put("authMode", "chatgpt")
                    .put("access", "fixture-subscription").put("accountId", "fixture-account");
            JSONObject provider = VendorPolicyGatewayHttpTest.provider("codex", "codex", "https://chatgpt.com/backend-api/codex", "gpt-5")
                    .put("accounts", new JSONArray().put(new JSONObject().put("id", "key-only").put("authMode", "apikey")
                            .put("access", "sk-fixture-key")).put(account));
            JSONObject config = VendorPolicyGatewayHttpTest.config(provider).put("setup", new JSONObject().put("done", true));
            try (VendorPolicyGatewayHttpTest.Gateway gateway = new VendorPolicyGatewayHttpTest.Gateway(config, upstream, false)) {
                String images = "/v1/images/generations";
                JSONObject prompt = new JSONObject().put("model", CodexImages.REF).put("prompt", "A red circle")
                        .put("size", "1024x1024").put("quality", "low").put("response_format", "b64_json").put("complexity", "low");
                JSONArray catalog = new JSONObject(get(gateway, "/v1/models")).getJSONArray("data");
                int count = 0;
                for (int i = 0; i < catalog.length(); i++) {
                    JSONObject entry = catalog.getJSONObject(i);
                    if (!CodexImages.REF.equals(entry.optString("id"))) continue;
                    check("image".equals(entry.optString("media_type")), "image metadata");
                    count++;
                }
                check(count == 1 && upstream.requests.isEmpty(), "default drawer appears once without fetching a catalog");
                check(GatewayAccessCatalog.build(config, System.currentTimeMillis(), null).toString().contains(CodexImages.REF), "access catalog contains media entry");
                VendorPolicyGatewayHttpTest.Result result = VendorPolicyGatewayHttpTest.post(gateway, images, prompt);
                check(result.status == 200 && result.body.contains("aW1hZ2U="), "image receipt: " + result.status + " " + result.body);
                result = VendorPolicyGatewayHttpTest.post(gateway, images, new JSONObject(prompt.toString()).put("model", "codex/gpt-image-2.5"));
                check(result.status == 429 && upstream.requests.size() == 2, "rate limit, no replay");
                result = VendorPolicyGatewayHttpTest.post(gateway, images, prompt);
                check(result.status == 502 && upstream.requests.size() == 3, "missing image is not reported as success");
                check(VendorPolicyGatewayHttpTest.post(gateway, images, new JSONObject(prompt.toString()).put("n", 5)).status == 400,
                        "upstream count bound");
                config.put("exposed", new JSONObject().put("enabled", true).put("items", new JSONArray()));
                check(!get(gateway, "/v1/models").contains(CodexImages.REF)
                        && VendorPolicyGatewayHttpTest.post(gateway, images, prompt).status == 403, "gateway exposure applies to listing and inference");
                config.optJSONObject("exposed").put("items", new JSONArray().put(CodexImages.REF));
                check(get(gateway, "/v1/models").contains(CodexImages.REF), "whitelisted drawing model is visible");
                account.put("enabled", false);
                check(!get(gateway, "/v1/models").contains(CodexImages.REF)
                        && VendorPolicyGatewayHttpTest.post(gateway, images, prompt).status == 401, "API key alone cannot advertise or draw");
                check(upstream.requests.size() == 3, "rejected requests did not reach upstream");
            }
        }
        System.out.println("Codex images HTTP PASS: catalog, signed JSON generation, legacy alias, exposure and refusal");
    }
}
