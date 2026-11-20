package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Three detached request/stream contracts. No account, credential, network or fixture files. */
public final class ProviderAlignmentRegressionTest {
    private static int checks;
    private static void check(boolean ok,String name) { checks++; if(!ok)throw new AssertionError(name); }
    private static JSONObject copy(JSONObject x) { return new JSONObject(x.toString()); }
    private static JSONObject function() { return new JSONObject().put("type","function").put("name","sum")
            .put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject())); }
    private static JSONObject chat(String model,Object content,boolean omit) {
        JSONObject assistant=new JSONObject().put("role","assistant").put("tool_calls",new JSONArray().put(
                new JSONObject().put("id","call-1").put("type","function").put("function",
                        new JSONObject().put("name","sum").put("arguments","{\"value\":1}"))));
        if(!omit)assistant.put("content",content);
        return new JSONObject().put("model",model).put("messages",new JSONArray()
                .put(new JSONObject().put("role","user").put("content","calculate"))
                .put(assistant).put(new JSONObject().put("role","tool").put("tool_call_id","call-1").put("content","1")));
    }
    private static JSONObject assistant(JSONObject x) {
        JSONArray a=x.getJSONArray("messages");for(int i=0;i<a.length();i++)if("assistant".equals(a.getJSONObject(i).optString("role")))return a.getJSONObject(i);
        throw new AssertionError("assistant missing");
    }
    private static void workbuddy() throws Exception {
        Object[] empty={JSONObject.NULL,"",new JSONArray(),new JSONArray().put(new JSONObject().put("type","text").put("text","")),
                new JSONArray().put(new JSONObject().put("type","text"))};
        for(String model:new String[]{"kimi-k3.1-pro","kimi-k3.1-pro-highspeed","KIMI-K3.1-PRO"}) {
            for(Object content:empty) {
                JSONObject input=chat(model,content,false);String original=input.toString(),calls=assistant(input).getJSONArray("tool_calls").toString();
                JSONObject out=WorkBuddyRequestPolicy.transform(input);
                check(" ".equals(assistant(out).opt("content")),"Kimi call-only turn carries one space: "+model);
                check(calls.equals(assistant(out).getJSONArray("tool_calls").toString()),"tool identity and arguments preserved");
                check(original.equals(input.toString()),"caller request never mutated");
                check(out.toString().equals(WorkBuddyRequestPolicy.transform(out).toString()),"one-space shaping idempotent");
            }
            check(" ".equals(assistant(WorkBuddyRequestPolicy.transform(chat(model,null,true))).opt("content")),"missing content treated as call-only");
        }
        for(String model:new String[]{"kimi-k3","kimi-k3.10-pro","deepseek-v4.1-flash","qwen","my-kimi-k3.1-pro"}) {
            JSONObject input=chat(model,JSONObject.NULL,false);
            check(assistant(WorkBuddyRequestPolicy.transform(input)).isNull("content"),"other models retain null: "+model);
        }
        for(Object content:new Object[]{"text"," ","\n",0,false,new JSONArray().put(new JSONObject().put("type","text").put("text","text")),
                new JSONArray().put(new JSONObject().put("type","image_url").put("image_url",new JSONObject().put("url","https://fixture.invalid/image.png")))}) {
            JSONObject input=chat("kimi-k3.1-pro",content,false);
            check(assistant(input).get("content").toString().equals(assistant(WorkBuddyRequestPolicy.transform(input)).get("content").toString()),"never replace text/image/invalid content with a blank");
        }
        JSONObject annotated=chat("kimi-k3.1-pro",new JSONArray().put(new JSONObject().put("type","text").put("text","").put("cache_control",new JSONObject().put("type","ephemeral"))),false);
        check(assistant(annotated).get("content").toString().equals(assistant(WorkBuddyRequestPolicy.transform(annotated)).get("content").toString()),"empty text with metadata is not erased by call-only compatibility");
        JSONObject noCall=chat("kimi-k3.1-pro",JSONObject.NULL,false);assistant(noCall).remove("tool_calls");
        check(assistant(WorkBuddyRequestPolicy.transform(noCall)).isNull("content"),"assistant without calls unchanged");
        assistant(noCall).put("tool_calls",new JSONArray());
        check(assistant(WorkBuddyRequestPolicy.transform(noCall)).isNull("content"),"empty tool array does not authorize content change");
    }
    private static JSONObject simpleChat() { return new JSONObject().put("model","fixture-model")
            .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","fixture"))); }
    private static JSONObject traeRequest(JSONObject x,int cap,boolean max) throws Exception {
        return TraeProtocol.request(x,"Chat","fixture",cap,"fixture-session",false,new JSONArray(),"fixture-base",1024,max);
    }
    private static TraeStream stream(String events,int cap,JSONArray tools) throws Exception {
        InputStream in=new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8));
        // Baseline-compatible oracle: the unpatched constructor has no wire-budget input.
        try {
            Constructor<TraeStream> c=TraeStream.class.getConstructor(InputStream.class,JSONArray.class,String.class,RequestContext.class,TraeStream.UsageSink.class,int.class);
            return c.newInstance(in,tools,"fixture-model",null,null,cap);
        } catch(NoSuchMethodException absent) { return new TraeStream(in,tools,"fixture-model",null,null); }
    }
    private static String events(int output,String finish) {
        return "event: output\ndata: {\"response\":\"same text\"}\n\n"
                +"event: token_usage\ndata: {\"prompt_tokens\":1,\"completion_tokens\":"+output+"}\n\n"
                +"event: done\ndata: {\"finish_reason\":\""+finish+"\"}\n\n";
    }
    private static void trae() throws Exception {
        JSONObject input=simpleChat();String original=input.toString();
        check(traeRequest(input,64,false).optInt("max_tokens")==64,"normal mode sends supplier output budget even when client omits it");
        check(original.equals(input.toString()),"Trae caller budget/request not mutated");
        check(!traeRequest(input,64,false).has("user_message_context"),"normal mode does not acquire Max-only context field");
        check(!traeRequest(input,0,false).has("max_tokens"),"unknown supplier limit never guessed");
        check(traeRequest(simpleChat().put("max_tokens",128),64,false).getInt("max_tokens")==64,"actual wire cap is clipped to model cap");
        check(traeRequest(simpleChat().put("max_tokens",12),64,false).getInt("max_tokens")==12,"explicit smaller cap kept");
        check(traeRequest(simpleChat().put("max_tokens",12).put("max_completion_tokens",40),64,false).getInt("max_tokens")==40,"existing completion alias precedence kept");
        check(traeRequest(simpleChat().put("max_tokens",19),0,false).getInt("max_tokens")==19,"explicit cap without model metadata kept");
        JSONObject max=traeRequest(simpleChat(),64,true);
        check(max.getInt("max_tokens")==64&&max.getJSONObject("user_message_context").getJSONObject("model_info").getInt("prompt_max_tokens")==960,"Max budget/context unchanged");
        for(Object bad:new Object[]{0,-1,1.5,"4",JSONObject.NULL}) {
            try { traeRequest(simpleChat().put("max_tokens",bad),64,false);throw new AssertionError("invalid budget accepted"); }
            catch(IOException expected){check(true,"existing invalid-budget rejection kept");}
        }
        for(int[] pair:new int[][]{{8,8},{8,9},{8,7},{8,0},{0,8},{4096,2000}}) {
            int cap=pair[0],output=pair[1];String want=cap>0&&output>=cap?"length":"stop";
            try(TraeStream s=stream(events(output,"stop"),cap,null)) {
                JSONObject whole=s.aggregate();check(want.equals(whole.getJSONArray("choices").getJSONObject(0).getString("finish_reason")),"nonstream finish uses actual sent limit: "+cap+"/"+output);
                check("same text".equals(whole.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")),"budget inference never changes answer text");
                check(whole.getJSONObject("usage").getInt("completion_tokens")==output,"supplier output usage unchanged");
            }
            try(TraeStream s=stream(events(output,"stop"),cap,null)) {
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[256];for(int n;(n=s.read(b))>=0;)out.write(b,0,n);
                String body=out.toString("UTF-8");check(body.contains("\"finish_reason\":\""+want+"\""),"stream finish uses same actual limit");
                check(body.contains("data: [DONE]"),"successful stream terminates normally");
            }
        }
        for(String reason:new String[]{"length","content_filter"})try(TraeStream s=stream(events(2,reason),8,null)) {
            check(reason.equals(s.aggregate().getJSONArray("choices").getJSONObject(0).getString("finish_reason")),"explicit upstream finish preserved: "+reason);
        }
        for(String reason:new String[]{"cancelled","future_reason"})try(TraeStream s=stream(events(8,reason),8,null)) {
            check(!"length".equals(s.aggregate().getJSONArray("choices").getJSONObject(0).getString("finish_reason")),"unmapped explicit finish is not relabeled as budget exhaustion");
        }
        String missing="event: output\ndata: {\"response\":\"same text\"}\n\nevent: done\ndata: {}\n\n";
        try(TraeStream s=stream(missing,1,null)){check("stop".equals(s.aggregate().getJSONArray("choices").getJSONObject(0).getString("finish_reason")),"no output count means no invented truncation");}
        JSONArray tools=new JSONArray().put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","sum").put("parameters",new JSONObject().put("type","object"))));
        String call="event: output\ndata: {\"tool_calls\":[{\"id\":\"c1\",\"index\":0,\"function\":{\"name\":\"sum\",\"arguments\":\"{}\"}}]}\n\n"
                +"event: token_usage\ndata: {\"prompt_tokens\":1,\"completion_tokens\":8}\n\nevent: done\ndata: {\"finish_reason\":\"stop\"}\n\n";
        try(TraeStream s=stream(call,8,tools)){check("tool_calls".equals(s.aggregate().getJSONArray("choices").getJSONObject(0).getString("finish_reason")),"tool finish takes precedence over inferred length");}
        try(TraeStream s=stream("event: output\ndata: {\"response\":\"partial\"}\n\nevent: error\ndata: {\"error\":{\"code\":\"bad\",\"message\":\"fixture\"}}\n\n",1,null)) {
            s.aggregate();throw new AssertionError("error incorrectly completed");
        } catch(IOException expected){check(true,"upstream error remains failure, never synthetic length success");}
    }
    private static JSONObject codexBody(JSONObject tool) { return new JSONObject().put("model","fixture-model").put("input","fixture").put("tools",new JSONArray().put(tool)); }
    private static JSONObject prepare(JSONObject input) { return CodexRequestPolicy.prepareFinalOutbound(input,CodexRequestPolicy.OutputConstraints.none()).body; }
    private static void codex() throws Exception {
        JSONObject missing=codexBody(function());String original=missing.toString();
        check(Boolean.FALSE.equals(prepare(missing).getJSONArray("tools").getJSONObject(0).opt("strict")),"omitted Codex strict explicitly false");
        check(original.equals(missing.toString()),"Codex caller never mutated");
        JSONObject schema=new JSONObject().put("type","object").put("properties",new JSONObject().put("bounds",new JSONObject().put("oneOf",new JSONArray()
                .put(new JSONObject().put("type","number")).put(new JSONObject().put("type","string").put("pattern","(?=a)")))))
                .put("required",new JSONArray().put("bounds")).put("additionalProperties",false);
        for(boolean strict:new boolean[]{false,true}) {
            JSONObject tool=function().put("strict",strict).put("parameters",copy(schema));JSONObject out=prepare(codexBody(tool)).getJSONArray("tools").getJSONObject(0);
            check(Boolean.valueOf(strict).equals(out.opt("strict")),"explicit Codex strict never downgraded");
            check(schema.toString().equals(out.getJSONObject("parameters").toString()),"explicit schema oneOf/lookaround not weakened or rewritten");
        }
        JSONObject nest=new JSONObject().put("type","namespace").put("name","ns").put("tools",new JSONArray().put(function())
                .put(new JSONObject().put("type","namespace").put("name","inner").put("tools",new JSONArray().put(function().put("strict",true)))));
        JSONObject n=prepare(codexBody(nest)).getJSONArray("tools").getJSONObject(0);
        check(Boolean.FALSE.equals(n.getJSONArray("tools").getJSONObject(0).opt("strict")),"namespace function default normalized");
        check(n.getJSONArray("tools").getJSONObject(1).getJSONArray("tools").getJSONObject(0).getBoolean("strict"),"nested explicit strict retained");
        for(String type:new String[]{"additional_tools","tool_search_output"}) {
            JSONObject input=new JSONObject().put("model","fixture").put("input",new JSONArray().put(new JSONObject().put("type",type).put("tools",new JSONArray().put(function()))));
            check(Boolean.FALSE.equals(prepare(input).getJSONArray("input").getJSONObject(0).getJSONArray("tools").getJSONObject(0).opt("strict")),"dynamic tool output default normalized: "+type);
        }
        for(Object bad:new Object[]{JSONObject.NULL,"true",0,new JSONObject()}) {
            JSONObject request=codexBody(function().put("strict",bad));String before=request.toString();
            try { prepare(request);throw new AssertionError("malformed explicit strict silently weakened"); }
            catch(IllegalArgumentException expected){check(before.equals(request.toString()),"malformed flag rejected without mutation");}
        }
        JSONObject custom=new JSONObject().put("type","custom").put("name","custom").put("format",new JSONObject().put("type","text"));
        check(custom.toString().equals(prepare(codexBody(custom)).getJSONArray("tools").getJSONObject(0).toString()),"custom/nonfunction tools not touched");
        JSONObject propertyNames=new JSONObject().put("type","object").put("properties",new JSONObject().put("strict",new JSONObject().put("type","boolean")).put("oneOf",new JSONObject().put("type","string")));
        JSONObject named=function().put("parameters",propertyNames);
        check(propertyNames.similar(prepare(codexBody(named)).getJSONArray("tools").getJSONObject(0).getJSONObject("parameters")),"schema properties named strict/oneOf are data, not transformation targets");
        JSONObject deep=function();for(int i=0;i<66;i++)deep=new JSONObject().put("type","namespace").put("tools",new JSONArray().put(deep));
        JSONObject deepInput=codexBody(deep);String deepBefore=deepInput.toString();
        try{prepare(deepInput);throw new AssertionError("unbounded namespace recursion");}catch(IllegalArgumentException expected){check(deepBefore.equals(deepInput.toString()),"deep namespace fails closed without caller mutation");}
        JSONObject once=prepare(missing);check(once.toString().equals(prepare(once).toString()),"Codex strict shaping idempotent");
        try { CodexRequestPolicy.prepareFinalOutbound(missing,CodexRequestPolicy.OutputConstraints.of(0,8));throw new AssertionError("Guard guarantee removed"); }
        catch(CodexRequestPolicy.UnpreservableOutputLimitException expected){check(true,"existing explicit Guard protection preserved");}
    }
    public static void main(String[] args) throws Exception {
        List<String> failures=new ArrayList<>();
        try{workbuddy();System.out.println("WorkBuddy alignment PASS");}catch(Throwable e){failures.add("WorkBuddy: "+e.getMessage());}
        try{trae();System.out.println("Trae alignment PASS");}catch(Throwable e){failures.add("Trae: "+e.getMessage());}
        try{codex();System.out.println("Codex alignment PASS");}catch(Throwable e){failures.add("Codex: "+e.getMessage());}
        if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));
        System.out.println("ProviderAlignmentRegressionTest PASS: "+checks+" checks");
    }
}
