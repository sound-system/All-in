package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;

import static com.gaeul.allin.VendorPolicyGatewayHttpTest.*;
import static com.gaeul.allin.WorkBuddyCompletionGatewayHttpTest.call;
import static com.gaeul.allin.WorkBuddyCompletionGatewayHttpTest.chunk;

/** Deterministic wire regression for Kimi tool-only history and split calls. */
public final class WorkBuddyToolRoundTripGatewayHttpTest {
    private static final String MODEL = "kimi-k3.1-pro";
    private static final String TOOL = "fixture_sum";
    private static final String CALL_ID = "call_fixture";
    private static final String PROMPT = "Add 2 and 3 using the tool";
    private static final String TOOL_RESULT = "5";
    private static final String LONG_PAYLOAD = String.join("", java.util.Collections.nCopies(256, "\u884c-A|quote=\"|slash=\\|\n"));
    private static final String LONG_RESULT = "result-start|" + LONG_PAYLOAD + "|result-end";
    private static final int LONG_TOOL_COUNT = 35;
    private static int checks;

    private enum Protocol {
        CHAT("/v1/chat/completions"), MESSAGES("/v1/messages"), RESPONSES("/v1/responses");

        final String path;

        Protocol(String path) {
            this.path = path;
        }
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static JSONObject user() {
        return new JSONObject().put("role", "user").put("content", PROMPT);
    }

    private static JSONObject input(String provider, Protocol protocol, boolean stream, JSONObject received, boolean longCase) {
        JSONObject schema = new JSONObject().put("type", "object")
                .put("properties", new JSONObject().put("a", new JSONObject().put("type", "integer"))
                        .put("b", new JSONObject().put("type", "integer")))
                .put("required", new JSONArray().put("a").put("b"));
        JSONObject body = new JSONObject().put("model", provider + "/" + MODEL).put("stream", stream);
        if (longCase) schema.getJSONObject("properties").put("payload", new JSONObject().put("type", "string"));
        String toolResult = longCase ? LONG_RESULT : TOOL_RESULT;
        JSONArray tools = new JSONArray();
        for (int i = 0; longCase && i < LONG_TOOL_COUNT - 1; i++) {
            tools.put(toolDefinition(protocol, "unused_" + i, schema));
        }
        tools.put(toolDefinition(protocol, TOOL, schema));
        JSONArray history = new JSONArray().put(user());
        switch (protocol) {
            case RESPONSES:
                if (received != null) {
                    history.put(received).put(new JSONObject().put("type", "function_call_output")
                            .put("call_id", received.getString("call_id")).put("output", toolResult));
                }
                return body.put("input", history).put("max_output_tokens", 128)
                        .put("tools", tools);
            case MESSAGES:
                if (received != null) {
                    history.put(new JSONObject().put("role", "assistant").put("content", new JSONArray().put(received)))
                            .put(new JSONObject().put("role", "user").put("content", new JSONArray()
                                    .put(new JSONObject().put("type", "tool_result").put("content", toolResult)
                                            .put("tool_use_id", received.getString("id")))));
                }
                break;
            case CHAT:
                if (received != null) {
                    history.put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL)
                                    .put("tool_calls", new JSONArray().put(received)))
                            .put(new JSONObject().put("role", "tool").put("tool_call_id", received.getString("id"))
                                    .put("content", toolResult));
                }
                break;
        }
        return body.put("messages", history).put("max_tokens", 128).put("tools", tools);
    }

    private static JSONObject toolDefinition(Protocol protocol, String name, JSONObject schema) {
        JSONObject tool = new JSONObject().put("name", name);
        if (protocol == Protocol.MESSAGES) return tool.put("input_schema", schema);
        tool.put("parameters", schema);
        if (protocol == Protocol.RESPONSES) return tool.put("type", "function");
        return new JSONObject().put("type", "function").put("function", tool);
    }

    private static JSONArray frames(String response) {
        JSONArray result = new JSONArray();
        for (String line : response.split("\\r?\\n")) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            JSONObject event = new JSONObject(data);
            check(!event.has("error") && !"error".equals(event.optString("type"))
                    && !"response.failed".equals(event.optString("type")), "no stream error");
            result.put(event);
        }
        return result;
    }

    private static JSONObject chatChoice(JSONObject body) {
        JSONArray choices = body.optJSONArray("choices");
        return choices == null || choices.length() == 0 ? new JSONObject() : choices.getJSONObject(0);
    }

    private static JSONObject receivedTool(Protocol protocol, boolean stream, String body) {
        if (protocol == Protocol.RESPONSES) {
            JSONObject response = responsesFinal(stream, body);
            JSONObject tool = onlyItem(response.getJSONArray("output"), "function_call");
            check("completed".equals(tool.optString("status")), "Responses function call completed");
            return tool;
        }
        if (!stream) {
            JSONObject response = new JSONObject(body);
            if (protocol == Protocol.MESSAGES) {
                check("tool_use".equals(response.optString("stop_reason")), "Messages tool-use terminal");
                return onlyItem(response.getJSONArray("content"), "tool_use");
            }
            JSONObject choice = chatChoice(response);
            check("tool_calls".equals(choice.optString("finish_reason")), "Chat tool-call terminal");
            return onlyItem(choice.getJSONObject("message").getJSONArray("tool_calls"), "function");
        }
        JSONArray events = frames(body);
        StringBuilder id = new StringBuilder(), name = new StringBuilder(), args = new StringBuilder();
        JSONObject messageTool = null;
        int toolIndex = -1;
        String terminal = "";
        boolean messageStop = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (protocol == Protocol.MESSAGES) {
                String type = event.optString("type");
                if ("content_block_start".equals(type) && "tool_use".equals(event.getJSONObject("content_block").optString("type"))) {
                    check(messageTool == null, "one emitted Messages tool");
                    messageTool = event.getJSONObject("content_block");
                    toolIndex = event.getInt("index");
                } else if ("content_block_delta".equals(type) && "input_json_delta".equals(event.getJSONObject("delta").optString("type"))) {
                    check(toolIndex == event.getInt("index"), "tool arguments attach to the emitted Messages block");
                    args.append(event.getJSONObject("delta").getString("partial_json"));
                } else if ("message_delta".equals(type)) {
                    terminal = event.getJSONObject("delta").optString("stop_reason");
                } else if ("message_stop".equals(type)) {
                    messageStop = true;
                }
                continue;
            }
            JSONObject choice = chatChoice(event);
            if (!choice.isNull("finish_reason")) terminal = choice.getString("finish_reason");
            JSONObject delta = choice.optJSONObject("delta");
            JSONArray tools = delta == null ? null : delta.optJSONArray("tool_calls");
            for (int j = 0; tools != null && j < tools.length(); j++) {
                JSONObject part = tools.getJSONObject(j);
                check(part.getInt("index") == 0, "each Chat fragment belongs to the one emitted tool");
                id.append(part.optString("id", ""));
                JSONObject fn = part.getJSONObject("function");
                name.append(fn.optString("name", ""));
                args.append(fn.optString("arguments", ""));
            }
        }
        if (protocol == Protocol.MESSAGES) {
            check(messageStop && "tool_use".equals(terminal) && messageTool != null, "Messages tool stream completes");
            return messageTool.put("input", new JSONObject(args.toString()));
        }
        check(body.contains("data: [DONE]") && "tool_calls".equals(terminal), "Chat tool stream completes");
        return new JSONObject().put("type", "function").put("id", id.toString()).put("function",
                new JSONObject().put("name", name.toString()).put("arguments", args.toString()));
    }

    private static JSONObject responsesFinal(boolean stream, String body) {
        if (!stream) {
            JSONObject response = new JSONObject(body);
            check("completed".equals(response.optString("status")), "Responses ordinary completion");
            return response;
        }
        JSONArray events = frames(body);
        check(events.length() > 0, "Responses SSE frames present");
        JSONObject last = events.getJSONObject(events.length() - 1);
        check("response.completed".equals(last.optString("type")), "Responses SSE terminal completion");
        return last.getJSONObject("response");
    }

    private static JSONObject onlyItem(JSONArray items, String type) {
        JSONObject result = null;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            if (!type.equals(item.optString("type"))) continue;
            check(result == null, "one item of type " + type);
            result = item;
        }
        check(result != null, "expected item of type " + type);
        return result;
    }

    private static void finalAnswer(Protocol protocol, boolean stream, String body) {
        if (protocol == Protocol.RESPONSES) {
            JSONObject message = onlyItem(responsesFinal(stream, body).getJSONArray("output"), "message");
            check(TOOL_RESULT.equals(onlyItem(message.getJSONArray("content"), "output_text").optString("text")), "Responses follow-up answer");
        } else if (!stream) {
            JSONObject response = new JSONObject(body);
            if (protocol == Protocol.MESSAGES) {
                check("end_turn".equals(response.optString("stop_reason")), "Messages follow-up terminal");
                check(TOOL_RESULT.equals(onlyItem(response.getJSONArray("content"), "text").optString("text")), "Messages follow-up answer");
            } else {
                JSONObject choice = chatChoice(response);
                check("stop".equals(choice.optString("finish_reason")), "Chat follow-up terminal");
                check(TOOL_RESULT.equals(choice.getJSONObject("message").optString("content")), "Chat follow-up answer");
            }
        } else {
            JSONArray events = frames(body);
            StringBuilder text = new StringBuilder();
            String finish = "";
            boolean complete = false;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (protocol == Protocol.CHAT) {
                    JSONObject choice = chatChoice(event);
                    JSONObject delta = choice.optJSONObject("delta");
                    if (delta != null) text.append(delta.optString("content", ""));
                    if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason");
                } else if ("content_block_delta".equals(event.optString("type"))) {
                    text.append(event.getJSONObject("delta").optString("text", ""));
                } else if ("message_delta".equals(event.optString("type"))) {
                    finish = event.getJSONObject("delta").optString("stop_reason");
                } else if ("message_stop".equals(event.optString("type"))) {
                    complete = true;
                }
            }
            check(TOOL_RESULT.contentEquals(text), "stream follow-up answer");
            check(protocol == Protocol.CHAT ? "stop".equals(finish) && body.contains("data: [DONE]")
                    : "end_turn".equals(finish) && complete, "stream follow-up terminal");
        }
    }

    private static String toolStream(String arguments) {
        StringBuilder sse = new StringBuilder(chunk(call("call_", "fixture_", null), null));
        sse.append(chunk(call("fixture", "sum", null), null));
        for (int offset = 0; offset < arguments.length(); offset += 137) {
            sse.append(chunk(call(null, null, arguments.substring(offset, Math.min(offset + 137, arguments.length()))), null));
        }
        return sse.append(chunk(new JSONObject(), "tool_calls")).append("data: [DONE]\n\n").toString();
    }

    private static void roundTrip(String id, Protocol protocol, boolean stream, boolean longCase) throws Exception {
        JSONObject expectedArgs = new JSONObject().put("a", 2).put("b", 3);
        if (longCase) expectedArgs.put("payload", LONG_PAYLOAD);
        String host = id.equals("workbuddy") ? "https://copilot.tencent.com/v2" : "https://www.workbuddy.ai/v2";
        JSONObject cfg = config(provider(id, "openai", host, MODEL))
                .put("guard", new JSONObject().put("enabled", false))
                .put("failover", new JSONObject().put("enabled", false));
        String toolSse = toolStream(expectedArgs.toString());
        String answerSse = chunk(new JSONObject().put("content", TOOL_RESULT), null)
                + chunk(new JSONObject(), "stop") + "data: [DONE]\n\n";
        try (Upstream upstream = new Upstream((req, n) -> new Reply(200, "text/event-stream", n == 1 ? toolSse : answerSse));
             Gateway gateway = new Gateway(cfg, upstream, false)) {
            try {
                Result first = post(gateway, protocol.path, input(id, protocol, stream, null, longCase));
                check(first.status == 200 && upstream.requests.size() == 1, "first turn HTTP and one upstream send");
                JSONObject received = receivedTool(protocol, stream, first.body);
                Result second = post(gateway, protocol.path, input(id, protocol, stream, received, longCase));
                check(second.status == 200 && upstream.requests.size() == 2, "follow-up HTTP and one upstream send");
                for (Request request : upstream.requests) {
                    check("/v2/chat/completions".equals(request.path) && MODEL.equals(request.body.optString("model")), "WorkBuddy Chat upstream");
                    JSONArray declarations = request.body.getJSONArray("tools");
                    check(declarations.length() == (longCase ? LONG_TOOL_COUNT : 1), "all tool declarations arrive");
                    check(TOOL.equals(declarations.getJSONObject(declarations.length() - 1).getJSONObject("function").optString("name")), "last tool declaration arrives");
                }
                JSONArray history = upstream.requests.get(1).body.getJSONArray("messages");
                int firstUser = history.length() - 3;
                check(firstUser >= 0, "outbound history has the full three-message conversation");
                for (int i = 0; i < firstUser; i++) {
                    check("system".equals(history.getJSONObject(i).optString("role")), "only system messages may precede the conversation");
                }
                JSONObject user = history.getJSONObject(firstUser);
                check("user".equals(user.optString("role")) && PROMPT.equals(user.optString("content")), "original user message survives");
                JSONObject assistant = history.getJSONObject(firstUser + 1);
                check("assistant".equals(assistant.optString("role")) && " ".equals(assistant.opt("content")), "Kimi receives one space on the follow-up");
                JSONObject tool = assistant.getJSONArray("tool_calls").getJSONObject(0);
                JSONObject fn = tool.getJSONObject("function");
                JSONObject output = history.getJSONObject(firstUser + 2);
                check(CALL_ID.equals(tool.optString("id")) && TOOL.equals(fn.optString("name")), "split tool identity survives the round trip");
                check(expectedArgs.similar(new JSONObject(fn.getString("arguments"))), "split arguments survive the round trip");
                check("tool".equals(output.optString("role")) && CALL_ID.equals(output.optString("tool_call_id"))
                        && (longCase ? LONG_RESULT : TOOL_RESULT).equals(output.optString("content")), "tool result links to original call");
                finalAnswer(protocol, stream, second.body);
            } finally {
                gateway.gateway.retireForRestore();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        for (String id : new String[]{"workbuddy", "workbuddy-ai"}) {
            for (Protocol protocol : Protocol.values()) {
                for (boolean stream : new boolean[]{false, true}) {
                    for (boolean longCase : new boolean[]{false, true}) {
                        try {
                            roundTrip(id, protocol, stream, longCase);
                        } catch (Exception | AssertionError ex) {
                            throw new AssertionError(id + " " + protocol + " stream=" + stream + " long=" + longCase, ex);
                        }
                    }
                }
            }
        }
        System.out.println("WorkBuddyToolRoundTripGatewayHttpTest PASS: 24 round trips, 48 upstream requests, " + checks + " checks");
    }
}
