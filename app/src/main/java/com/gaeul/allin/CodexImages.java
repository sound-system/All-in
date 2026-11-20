package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

/** ChatGPT subscriptions expose a single drawer independently of the Codex chat catalog. */
final class CodexImages {
    static final String PROVIDER = "codex";
    static final String MODEL = "gpt-image-2";
    static final String REF = PROVIDER + "/" + MODEL;
    static final String ENDPOINT = "https://chatgpt.com/backend-api/codex/images/generations";
    static final int MAX_RESPONSE_BYTES = 24 * 1024 * 1024;

    static boolean matches(String value) {
        String model = value.trim();
        if (model.startsWith(PROVIDER + "/")) model = model.substring(PROVIDER.length() + 1);
        return model.matches("gpt-image-[a-z0-9][a-z0-9.-]{0,79}");
    }

    static String access(JSONObject account) {
        String access = account.optString("access").trim();
        return access.isEmpty() ? account.optString("token").trim() : access;
    }

    static boolean usable(JSONObject account, long now) {
        if (account == null || !account.optBoolean("enabled", true) || account.optBoolean("off", false)
                || !"chatgpt".equalsIgnoreCase(account.optString("authMode", "chatgpt"))) return false;
        String token = access(account);
        boolean renew = !account.optString("refresh").trim().isEmpty();
        long expires = account.optLong("expires", 0);
        return !token.startsWith("sk-") && (renew || (!token.isEmpty() && (expires <= 0 || expires > now)));
    }

    static JSONObject pick(JSONObject provider, long now) {
        if (provider == null || !provider.optBoolean("enabled", true)) return null;
        JSONArray accounts = provider.optJSONArray("accounts");
        for (int i = 0; accounts != null && i < accounts.length(); i++) {
            JSONObject account = accounts.optJSONObject(i);
            if (usable(account, now)) return account;
        }
        return null;
    }

    static JSONObject catalogEntry(JSONObject provider) {
        return new JSONObject().put("id", REF).put("name", "GPT Image").put("display_name", "GPT Image")
                .put("provider", PROVIDER).put("providerName", provider.optString("name", "Codex"))
                .put("kind", "model").put("media_type", "image")
                .put("modalities", new JSONObject().put("input", new JSONArray().put("text"))
                        .put("output", new JSONArray().put("image")));
    }

    static JSONObject request(JSONObject input, String prompt) {
        if (prompt.trim().isEmpty()) throw new IllegalArgumentException("Image prompt is required");
        Object count = input.opt("n");
        int n = 1;
        if (count != null && count != JSONObject.NULL) {
            if (!(count instanceof Integer || count instanceof Long) || ((Number) count).longValue() > 4)
                throw new IllegalArgumentException("n must be an integer, at most 4");
            n = (int) Math.max(1, ((Number) count).longValue());
        }
        String model = input.optString("model").trim();
        if (model.startsWith(PROVIDER + "/")) model = model.substring(PROVIDER.length() + 1);
        JSONObject output = new JSONObject().put("model", model).put("prompt", prompt).put("n", n);
        for (String field : new String[]{"size", "quality", "background", "output_format"}) {
            Object value = input.opt(field);
            if (value == null || value == JSONObject.NULL) continue;
            if (!(value instanceof String)) throw new IllegalArgumentException(field + " must be a string");
            if (!((String) value).isEmpty()) output.put(field, value);
        }
        return output;
    }

    static boolean hasImage(JSONObject response) {
        JSONArray images = response.optJSONArray("data");
        for (int i = 0; images != null && i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image == null) continue;
            for (String field : new String[]{"b64_json", "url"}) {
                Object value = image.opt(field);
                if (value instanceof String && !((String) value).trim().isEmpty()) return true;
            }
        }
        return false;
    }
}
