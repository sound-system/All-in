package com.gaeul.allin;

import com.gaeul.allin.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Adapts the DeepSeek web protocol to incremental OpenAI chunks. */
final class DeepSeekWebChat {
    interface Completion { void done(boolean success, int status, String error); }
    static final class Result { String text, reasoning; JSONArray toolCalls = new JSONArray(); }
    private final String token;
    private final DeepSeekWeb.Transport transport;
    private final DeepSeekWeb.CancelToken cancel = new DeepSeekWeb.CancelToken();
    private volatile boolean cancelled;
    DeepSeekWebChat(String token, DeepSeekWeb.Transport transport) { this.token=token; this.transport=transport; }
    void validate(JSONObject request) throws IOException { DeepSeekWeb.prepare(request,null,null); }
    void cancel() { cancelled=true; cancel.cancel(); }
    Result run(JSONObject request, DeepSeekWeb.Listener listener) throws IOException {
        validate(request);
        DeepSeekWeb client=new DeepSeekWeb(transport,token);
        String id=null;
        try {
            id=client.createSession();
            final StringBuilder text=new StringBuilder(); final JSONArray calls=new JSONArray();
            DeepSeekToolBridge.Prepared prepared=DeepSeekToolBridge.analyze(request);
            DeepSeekToolBridge.StreamParser parser=prepared.newParser(new DeepSeekToolBridge.Listener() {
                public void onText(String value) { text.append(value); if(listener!=null) try { listener.onText(value); } catch(IOException e) { throw new UncheckedIOException(e); } }
                public void onToolCall(DeepSeekToolBridge.ToolCall call) { try {calls.put(new JSONObject().put("id",call.id).put("type","function").put("function",new JSONObject().put("name",call.name).put("arguments",call.arguments)));} catch(JSONException e) {throw new IllegalStateException(e);} }
            });
            DeepSeekWeb.Listener wrapped=new DeepSeekWeb.Listener() {
                public void onText(String value) throws IOException {try {parser.feed(value);} catch(UncheckedIOException e) {throw e.getCause();}}
                public void onReasoning(String value) throws IOException {if(listener!=null)listener.onReasoning(value);}
            };
            DeepSeekWeb.TurnResult turn=client.run(request,id,null,wrapped,30000,cancel);
            try { parser.finish(); } catch(UncheckedIOException e) {throw e.getCause();}
            Result r=new Result(); r.text=text.toString(); r.reasoning=turn.stream.reasoning.toString(); r.toolCalls=calls; return r;
        } finally { if (id!=null && !cancelled) try { client.deleteSession(id); } catch (IOException ignored) {} }
    }
    static String safeErrorMessage(int status) {
        if (status == 401) return "DeepSeek web authentication failed";
        if (status == 403) return "DeepSeek web denied access. Check the account and model in the official web app.";
        if (status == 429) return "DeepSeek web rate limited";
        return "DeepSeek web upstream failed";
    }

    private String safeBusinessCode(DeepSeekWeb.UpstreamException failure) {
        if (failure.bodyTruncated || failure.responseBody.length() > 16 * 1024) return null;
        try {
            JSONTokener reader = new JSONTokener(failure.responseBody);
            Object raw = reader.nextValue();
            if (!(raw instanceof JSONObject) || reader.nextClean() != 0) return null;
            JSONObject root = (JSONObject) raw;
            JSONObject value = root.optJSONObject("v");
            for (JSONObject node : new JSONObject[]{root.optJSONObject("data"),
                    value == null ? null : value.optJSONObject("data"), value,
                    root.optJSONObject("error"), root}) {
                if (node == null) continue;
                for (String field : new String[]{"biz_code", "code"}) {
                    String code = node.optString(field, "");
                    if (code.matches("[1-9][0-9]{0,5}") && !code.equals(token)) return code;
                }
            }
        } catch (JSONException ignored) {
            // Ignore malformed and non-JSON bodies.
        }
        return null;
    }

    JSONObject errorDetail(int status, DeepSeekWeb.UpstreamException failure) {
        String type;
        switch (status) {
            case 401: type = "authentication_error"; break;
            case 403: type = "permission_error"; break;
            case 429: type = "rate_limit_error"; break;
            default: type = "upstream_error";
        }
        JSONObject detail = new JSONObject().put("type", type).put("code", status)
                .put("message", safeErrorMessage(status));
        if (failure != null) {
            if (failure.upstreamStatus > 0) detail.put("upstream_status", failure.upstreamStatus);
            String businessCode = safeBusinessCode(failure);
            if (businessCode != null) detail.put("upstream_business_code", businessCode);
        }
        return detail;
    }

    static String chunk(String model,String field,String value,boolean finish) throws IOException {
        try {
            JSONObject delta=new JSONObject(); if(field!=null) delta.put(field,value);
            JSONObject choice=new JSONObject().put("index",0).put("delta",delta).put("finish_reason",finish?"stop":JSONObject.NULL);
            return "data: "+new JSONObject().put("object","chat.completion.chunk").put("model",model).put("choices",new JSONArray().put(choice)).toString()+"\n\n";
        } catch(Exception e) { throw new IOException(e); }
    }
    static String finishToolChunk(String model) throws JSONException {
        JSONObject choice=new JSONObject().put("index",0).put("delta",new JSONObject()).put("finish_reason","tool_calls");
        return "data: "+new JSONObject().put("object","chat.completion.chunk").put("model",model).put("choices",new JSONArray().put(choice))+"\n\n";
    }
    InputStream stream(final JSONObject request,final String model,final Completion completion) throws IOException {
        validate(request);
        final PipedInputStream input=new PipedInputStream(32768);
        final PipedOutputStream output=new PipedOutputStream(input);
        final NetworkPolicy.Snapshot snapshot=NetworkPolicy.capture();
        Thread worker=new Thread(()->{
          try (NetworkPolicy.Scope scope=NetworkPolicy.use(snapshot)) {
            boolean success=false; int status=502; String error="";
            try {
                DeepSeekWeb.Listener listener=new DeepSeekWeb.Listener() {
                    private void send(String field,String text) throws IOException {
                        if(cancelled) throw new IOException("Cancelled");
                        output.write(chunk(model,field,text,false).getBytes(StandardCharsets.UTF_8)); output.flush();
                    }
                    public void onText(String text) throws IOException {send("content",text);}
                    public void onReasoning(String text) throws IOException {send("reasoning_content",text);}
                };
                Result result=run(request,listener);
                for(int i=0;i<result.toolCalls.length();i++) {
                    JSONObject call=result.toolCalls.getJSONObject(i); call.put("index",i);
                    JSONObject delta=new JSONObject().put("tool_calls",new JSONArray().put(call));
                    JSONObject choice=new JSONObject().put("index",0).put("delta",delta).put("finish_reason",JSONObject.NULL);
                    output.write(("data: "+new JSONObject().put("object","chat.completion.chunk").put("model",model).put("choices",new JSONArray().put(choice))+"\n\n").getBytes(StandardCharsets.UTF_8));
                }
                if(cancelled) throw new IOException("Cancelled");
                output.write((result.toolCalls.length()>0 ? finishToolChunk(model)+"data: [DONE]\n\n" : chunk(model,null,null,true)+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)); output.flush(); success=true;
            } catch(Exception e) {
                DeepSeekWeb.UpstreamException failure = e instanceof DeepSeekWeb.UpstreamException
                        ? (DeepSeekWeb.UpstreamException) e : null;
                if (failure != null) status = failure.gatewayStatus;
                error=cancelled?"Client cancelled":safeErrorMessage(status);
                if(!cancelled) try {output.write(("data: " + new JSONObject().put("error", errorDetail(status, failure)) + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();} catch(IOException ignored) {}
            } finally { try {output.close();} catch(IOException ignored) {} completion.done(success,success?200:status,error); }
          }
        },"deepseek-web-stream");
        worker.setDaemon(true); worker.start();
        return new FilterInputStream(input) { public void close() throws IOException { cancel(); worker.interrupt(); super.close(); } };
    }
}
