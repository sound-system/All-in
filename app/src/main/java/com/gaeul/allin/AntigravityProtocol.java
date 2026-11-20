package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Antigravity request contract. */
public final class AntigravityProtocol {
    private AntigravityProtocol() { }
    public static final String VERSION = "2.32.1";
    // Newer manifests don't include the changelist. Use the latest known value, as upstream does.
    public static final String CHANGE_LIST = "1021296516";

    public static String userAgent() {
        return userAgent(VERSION);
    }

    static String resolvedUserAgent() {
        return userAgent(AntigravityVersion.resolve());
    }

    private static String userAgent(String version) {
        String os = System.getProperty("os.name", "linux").toLowerCase(Locale.ROOT);
        os = os.contains("windows") ? "windows" : os.contains("mac") || os.contains("darwin") ? "darwin" : "linux";
        String arch = System.getProperty("os.arch", "aarch64").toLowerCase(Locale.ROOT);
        if (arch.equals("aarch64") || arch.equals("arm64")) arch = "arm64";
        else if (arch.equals("x86_64") || arch.equals("amd64") || arch.equals("x64")) arch = "amd64";
        else if (arch.startsWith("arm")) arch = "arm";
        else if (arch.equals("x86") || arch.matches("i[3-6]86")) arch = "386";
        else if (!arch.matches("[a-z0-9_]{1,24}")) arch = "unknown";
        return "antigravity/hub/" + version + " (aidev_client; os_type=" + os
                + "; arch=" + arch + "; cl=" + CHANGE_LIST + ")";
    }

    static JSONObject onboardBody(JSONObject load) {
        JSONArray tiers = load == null ? null : load.optJSONArray("allowedTiers");
        String tier = "free-tier";
        if (tiers != null) {
            JSONObject selected = tiers.optJSONObject(0);
            for (int i = 0; i < tiers.length(); i++) {
                JSONObject t = tiers.optJSONObject(i);
                if (t != null && Boolean.TRUE.equals(t.opt("isDefault"))) { selected = t; break; }
            }
            if (selected != null) tier = selected.optString("id", tier);
        }
        if (tier.isEmpty() || "legacy-tier".equals(tier)) tier = "free-tier";
        return new JSONObject().put("tierId", tier)
                .put("metadata", new JSONObject().put("ideType", "ANTIGRAVITY"));
    }

    static String uuid(String seed) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (byte b : bytes) h.append(String.format(Locale.ROOT, "%02x", b & 255));
            return h.substring(0, 8) + "-" + h.substring(8, 12) + "-4" + h.substring(13, 16)
                    + "-" + Integer.toHexString((Character.digit(h.charAt(16), 16) & 3) | 8)
                    + h.substring(17, 20) + "-" + h.substring(20, 32);
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    /** Local serializer order only; the shared JSON library intentionally uses HashMap. */
    private static final class Ordered extends JSONObject {
        private final java.util.List<String> order = new java.util.ArrayList<>();
        @Override public Ordered put(String key, Object value) {
            if (!has(key) && value != null) order.add(key);
            super.put(key, value);
            return this;
        }
        @Override protected java.util.Set<java.util.Map.Entry<String, Object>> entrySet() {
            java.util.LinkedHashMap<String, Object> sorted = new java.util.LinkedHashMap<>();
            for (String key : order) if (has(key)) sorted.put(key, opt(key));
            for (String key : super.keySet()) if (!sorted.containsKey(key)) sorted.put(key, opt(key));
            return sorted.entrySet();
        }
    }

    static JSONObject envelope(JSONObject request, String model, String project, JSONObject modelInfo) {
        if (model == null) model = "";
        String session = request.optString("sessionId", "");
        String cascade = uuid("cascade\u0000" + session);
        String trajectory = uuid("trajectory\u0000" + session);
        JSONArray contents = request.optJSONArray("contents");
        int step = Math.max(0, (contents == null ? 0 : contents.length()) - 1), turns = 0;
        if (contents != null) for (int i = 0; i < contents.length(); i++) {
            JSONObject c = contents.optJSONObject(i);
            if (c != null && "model".equals(c.optString("role"))) turns++;
        }
        String maker = modelInfo == null ? "" : modelInfo.optString("modelProvider", "");
        boolean claude = "MODEL_PROVIDER_ANTHROPIC".equals(maker) || model.startsWith("claude");
        boolean gemini = maker.isEmpty() ? model.startsWith("gemini") : "MODEL_PROVIDER_GOOGLE".equals(maker);
        JSONObject labels = new JSONObject().put("cascade_id", cascade).put("last_step_index", String.valueOf(step))
                .put("request_id", trajectory + "-" + turns).put("root_cascade_id", cascade)
                .put("trajectory_id", trajectory).put("used_claude", String.valueOf(claude))
                .put("used_claude_conservative", String.valueOf(claude)).put("used_non_gemini_model", String.valueOf(!gemini));
        String modelEnum = modelInfo == null ? "" : modelInfo.optString("modelEnum", "");
        if (modelEnum.matches("[A-Z][A-Z0-9_]{0,159}")) labels.put("model_enum", modelEnum);
        // Keep the source client's key order without changing request content or output budgets.
        JSONObject ordered = new Ordered();
        for (String k : new String[]{"contents", "systemInstruction", "tools", "toolConfig"})
            if (request.has(k)) ordered.put(k, request.opt(k));
        java.util.Iterator<String> keys = request.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            if (!ordered.has(k) && !"labels".equals(k) && !"generationConfig".equals(k) && !"sessionId".equals(k))
                ordered.put(k, request.opt(k));
        }
        ordered.put("labels", labels);
        if (request.has("generationConfig")) ordered.put("generationConfig", request.opt("generationConfig"));
        ordered.put("sessionId", session);
        return new Ordered().put("project", project)
                .put("requestId", "agent/" + cascade + "/" + System.currentTimeMillis() + "/" + trajectory + "/" + (step + 1))
                .put("request", ordered).put("model", model).put("userAgent", "antigravity").put("requestType", "agent");
    }

    /** Never expose credential echoes, even when an upstream puts them in its error message. */
    static String safeError(String text, JSONObject account) {
        if (text == null) return "";
        if (account != null) for (String k : new String[]{"access", "token", "refresh", "access_token", "refresh_token", "id_token"}) {
            String secret = account.optString(k, "");
            if (!secret.isEmpty()) text = text.replace(secret, "[REDACTED]");
        }
        text = text.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]")
                .replaceAll("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[REDACTED]")
                .replaceAll("(?i)(?:access_token|refresh_token|id_token|client_secret)\\s*[=:]\\s*[^\\s,;\"}]+", "credential=[REDACTED]");
        return text;
    }

    /** Display-only Google error facts; no routing, cooldown, refresh or retry decisions. */
    static void describeRefusal(JSONObject into, int status, String safeBody) {
        JSONObject root;
        try { root = new JSONObject(safeBody); } catch (Exception ignored) { return; }
        JSONObject error = root.optJSONObject("error");
        if (error == null) return;
        JSONObject facts = new JSONObject();
        String message = error.optString("message", "");
        String googleStatus = error.optString("status", "");
        if (!message.isEmpty()) facts.put("message", clip(message, 2000));
        if (googleStatus.matches("[A-Z_]{1,64}")) facts.put("status", googleStatus);
        JSONArray details = error.optJSONArray("details"), reasons = new JSONArray();
        boolean insufficientScope = false;
        if (details != null) for (int i = 0; i < Math.min(details.length(), 16); i++) {
            JSONObject detail = details.optJSONObject(i);
            if (detail == null || !"type.googleapis.com/google.rpc.ErrorInfo".equals(detail.optString("@type"))) continue;
            String reason = detail.optString("reason", "");
            if (reason.matches("[A-Z][A-Z0-9_]{0,127}") && reasons.length() < 8) reasons.put(reason);
            if ("ACCESS_TOKEN_SCOPE_INSUFFICIENT".equals(reason)) insufficientScope = true;
        }
        if (reasons.length() > 0) facts.put("reasons", reasons);
        into.put("upstreamError", facts);
        if (status != 403) return;
        String lower = message.toLowerCase(Locale.ROOT);
        if (insufficientScope) {
            into.put("failureLayer", "login").put("failureLayerLabel", "授权范围")
                    .put("failureLabel", "Google 授权范围不足 (403)")
                    .put("advice", "Google 明确报告授权范围不足。新版已补齐授权请求；需要重新完成 Google 同意授权，普通刷新不会自动增加权限。");
        } else if (lower.contains("not currently available in your location") || lower.contains("unsupported country")
                || lower.contains("unsupported region")) {
            into.put("failureLayer", "inference").put("failureLayerLabel", "服务资格与地区")
                    .put("failureLabel", "Google 拒绝服务资格或地区 (403)")
                    .put("advice", "Google 按服务资格或地区拒绝本次调用；这不证明凭据过期。若同账号在 Magpie 可用，请对照授权范围、项目与应用实际网络出口，避免连续重试。");
        } else {
            into.put("failureLayer", "inference").put("failureLayerLabel", "访问权限与资格")
                    .put("advice", "Google 拒绝本次调用。仅凭 403 无法区分服务资格、授权范围或模型权限；请结合上游原因核对，不要直接删除账号。");
        }
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }
}
