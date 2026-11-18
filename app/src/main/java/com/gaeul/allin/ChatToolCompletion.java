package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/** Bounded tool-name validation for one Chat choice. Parameter-only deltas are valid. */
final class ChatToolCompletion {
    static final String ERROR = "Incomplete upstream tool call: missing function name or tool list";
    private final Map<Integer, Boolean> named = new HashMap<>();

    boolean observe(JSONArray calls) {
        for (int i = 0; calls != null && i < calls.length(); i++) {
            JSONObject call = calls.optJSONObject(i);
            if (call == null) return false;
            int index = call.optInt("index", i);
            if (named.size() >= 1024 && !named.containsKey(index)) return false;
            boolean hasName = !string(call.optJSONObject("function"), "name").trim().isEmpty();
            named.put(index, hasName || Boolean.TRUE.equals(named.get(index)));
        }
        return true;
    }

    boolean valid(String finish) {
        return (!"tool_calls".equals(finish) || !named.isEmpty()) && !named.containsValue(false);
    }

    static String string(JSONObject object, String field) {
        Object value = object == null ? null : object.opt(field);
        return value instanceof String ? (String) value : "";
    }

    static String mergeIdentity(String current, String fragment) {
        return current.equals(fragment) ? current : current + fragment;
    }
}
