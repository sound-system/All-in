package com.gaeul.allin;

import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.util.Locale;

/** Loomy 0.1.12 chat refusal policy. Only confirmed business codes prove expiry. */
final class LoomyChatRefusal {
    static final int BODY_LIMIT = 64 * 1024;

    private LoomyChatRefusal() {}

    static boolean isJson(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(Locale.ROOT);
        return type.contains("json") && !type.contains("event-stream");
    }

    static String businessCode(byte[] body, long expectedLength) {
        if (body.length > BODY_LIMIT || (expectedLength >= 0 && expectedLength != body.length)) return "";
        try {
            String text = BoundedResponse.utf8(body);
            new BoundedStrictJson(text).run();
            JSONObject value = new JSONObject(text);
            if (value.has("choices")) return "";
            Object code = value.opt("code");
            return code instanceof String || code instanceof Number ? code.toString().trim() : "";
        } catch (Exception invalid) {
            return "";
        }
    }

    static boolean expired(String code) {
        return "020002".equals(code) || "100002".equals(code);
    }

    static JSONObject errorDetail(int upstreamStatus, String code) {
        boolean expired = expired(code);
        String message = expired ? "Loomy 登录态已失效，请重新登录"
                : upstreamStatus == 401 || upstreamStatus == 403
                    ? "Loomy 上游拒绝访问，当前登录状态未确认失效"
                    : "Loomy 上游返回业务拒绝";
        JSONObject detail = new JSONObject().put("type", expired ? "authentication_error" : "upstream_error")
                .put("message", message).put("upstream_status", upstreamStatus);
        if (code.matches("[0-9]{1,9}")) detail.put("upstream_business_code", code);
        return detail;
    }

    static byte[] readPrefix(java.io.InputStream input) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (bytes.size() <= BODY_LIMIT) {
            int read = input.read(buffer, 0, Math.min(buffer.length, BODY_LIMIT + 1 - bytes.size()));
            if (read < 0) break;
            if (read > 0) bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }
}
