package com.gaeul.allin;

import static com.gaeul.allin.VendorPolicyGatewayHttpTest.*;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.util.*;

/** Real loopback gateway/upstream sockets, synthetic accounts only. No live vendor requests. */
public final class ProviderAlignmentGatewayHttpTest {
    private static int checks, logical, physical;
    private static final String[] PATHS={"/v1/chat/completions","/v1/messages","/v1/responses"};
    private static final String ARGS=new JSONObject().put("value",1).put("label","DeepSeek Harness").toString();
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static JSONObject schema(){return new JSONObject().put("type","object").put("properties",new JSONObject()
            .put("value",new JSONObject().put("type","integer")).put("label",new JSONObject().put("type","string")));}
    private static JSONObject function(){return new JSONObject().put("name","sum").put("parameters",schema());}
    private static JSONObject user(String text){return new JSONObject().put("role","user").put("content",text);}
    private static JSONObject wbInput(String id,String path,boolean stream){
        JSONObject input=new JSONObject().put("model",id+"/kimi-k3.1-pro").put("stream",stream);
        JSONObject f=function();
        if(path.endsWith("/responses")){
            input.put("max_output_tokens",64).put("tools",new JSONArray().put(new JSONObject(f.toString()).put("type","function")))
                    .put("input",new JSONArray().put(new JSONObject().put("type","message").put("role","user").put("content","calculate"))
                            .put(new JSONObject().put("type","function_call").put("call_id","old-call").put("name","sum").put("arguments",ARGS))
                            .put(new JSONObject().put("type","function_call_output").put("call_id","old-call").put("output","DeepSeek Harness result")));
        }else if(path.endsWith("/messages")){
            input.put("max_tokens",64).put("tools",new JSONArray().put(new JSONObject().put("name","sum").put("input_schema",schema())))
                    .put("messages",new JSONArray().put(user("calculate"))
                            .put(new JSONObject().put("role","assistant").put("content",new JSONArray().put(new JSONObject().put("type","tool_use").put("id","old-call").put("name","sum").put("input",new JSONObject(ARGS)))))
                            .put(new JSONObject().put("role","user").put("content",new JSONArray().put(new JSONObject().put("type","tool_result").put("tool_use_id","old-call").put("content","DeepSeek Harness result")))));
        }else{
            input.put("max_tokens",64).put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",f)))
                    .put("messages",new JSONArray().put(user("calculate"))
                            .put(new JSONObject().put("role","assistant").put("content",JSONObject.NULL).put("tool_calls",new JSONArray().put(new JSONObject().put("id","old-call").put("type","function").put("function",new JSONObject().put("name","sum").put("arguments",ARGS)))))
                            .put(new JSONObject().put("role","tool").put("tool_call_id","old-call").put("content","DeepSeek Harness result")));
        }
        return input;
    }
    private static Result call(Gateway g,String path,JSONObject input)throws Exception{logical++;return post(g,path,input);}
    private static String chatSse(){return WorkBuddyCompletionGatewayHttpTest.chunk(new JSONObject().put("role","assistant").put("content","ok"),null)
            +WorkBuddyCompletionGatewayHttpTest.chunk(new JSONObject(),"stop")+"data: [DONE]\n\n";}
    private static void workbuddy()throws Exception{
        for(String id:new String[]{"workbuddy","workbuddy-ai"})for(String path:PATHS)for(boolean stream:new boolean[]{false,true}){
            String host=id.equals("workbuddy")?"https://copilot.tencent.com/v2":"https://www.workbuddy.ai/v2";
            JSONObject p=provider(id,"openai",host,"kimi-k3.1-pro");JSONObject cfg=config(p).put("guard",new JSONObject().put("enabled",false));
            try(Upstream u=new Upstream((r,n)->new Reply(200,"text/event-stream",chatSse()));Gateway g=new Gateway(cfg,u,false)){
                try{
                    JSONObject input=wbInput(id,path,stream);String original=input.toString();Result result=call(g,path,input);
                    check(result.status==200,"WorkBuddy response "+id+" "+path+" "+stream+" "+result.status+" "+result.body);
                    check(u.requests.size()==1,"one WorkBuddy upstream request");physical+=u.requests.size();
                    Request request=u.requests.get(0);check("/v2/chat/completions".equals(request.path),"fixed WorkBuddy chat endpoint");
                    check("kimi-k3.1-pro".equals(request.body.optString("model")),"match selected wire model, not prefixed client ID");
                    JSONObject assistant=null,tool=null;JSONArray messages=request.body.getJSONArray("messages");
                    for(int i=0;i<messages.length();i++){JSONObject m=messages.getJSONObject(i);if("assistant".equals(m.optString("role")))assistant=m;if("tool".equals(m.optString("role")))tool=m;}
                    check(assistant!=null&&" ".equals(assistant.opt("content")),"call-only history actually sent with one space");
                    JSONObject old=assistant.getJSONArray("tool_calls").getJSONObject(0);
                    check("old-call".equals(old.getString("id"))&&"sum".equals(old.getJSONObject("function").getString("name")),"original call identity retained on wire");
                    check(new JSONObject(ARGS).similar(new JSONObject(old.getJSONObject("function").getString("arguments"))),"original arguments retained on wire");
                    check(tool!=null&&"DeepSeek Harness result".equals(tool.getString("content")),"tool result text not rewritten");
                    check(original.equals(input.toString()),"caller WorkBuddy document unchanged");
                }finally{g.gateway.retireForRestore();}
            }
        }
    }
    private static JSONObject traeConfig(int cap)throws Exception{
        JSONObject meta=new JSONObject().put("function","solo_agent").put("modelName","fixture").put("maxOutput",cap)
                .put("multimodal",false).put("base","fixture").put("contextWindow",1024).put("maxMode",false);
        JSONObject account=TraeDevice.create().put("id","trae-fixture").put("generation","fixture-generation")
                .put("token","synthetic-trae-token").put("refresh","synthetic-trae-refresh").put("expires",System.currentTimeMillis()+86400000L)
                .put("uid","synthetic-uid").put("name","synthetic").put("enabled",true).put("models",new JSONArray().put("fixture"))
                .put("modelMeta",new JSONObject().put("fixture",meta));
        JSONObject p=provider("trae-cn","trae","https://unused.invalid","fixture").put("accounts",new JSONArray().put(account))
                .put("modelMeta",new JSONObject().put("fixture",meta)).put("keys",new JSONArray().put("synthetic-trae-token"));
        return config(p).put("setup",new JSONObject().put("done",true)).put("guard",new JSONObject().put("enabled",false));
    }
    private static JSONObject simple(String path,String model,boolean stream,Integer asked){
        JSONObject input=new JSONObject().put("model",model).put("stream",stream);
        if(path.endsWith("/responses")){input.put("input","fixture");if(asked!=null)input.put("max_output_tokens",asked);}
        else{input.put("messages",new JSONArray().put(user("fixture")));if(asked!=null)input.put("max_tokens",asked);}
        return input;
    }
    private static List<JSONObject> frames(String body){List<JSONObject> result=new ArrayList<>();for(String line:body.split("\\r?\\n")){
        if(!line.startsWith("data:"))continue;String data=line.substring(5).trim();if(!data.isEmpty()&&!"[DONE]".equals(data))result.add(new JSONObject(data));
    }return result;}
    private static void finish(Result result,String path,boolean stream,String want){
        check(result.status==200,"Trae successful transport "+path+" "+stream+" "+result.status+" "+result.body);
        if(!stream){JSONObject body=new JSONObject(result.body);
            if(path.endsWith("/responses")){check((want.equals("length")?"incomplete":"completed").equals(body.optString("status")),"Responses terminal status");if(want.equals("length"))check("max_output_tokens".equals(body.getJSONObject("incomplete_details").optString("reason")),"Responses incomplete reason");}
            else if(path.endsWith("/messages"))check((want.equals("length")?"max_tokens":"end_turn").equals(body.optString("stop_reason")),"Messages terminal stop reason");
            else check(want.equals(body.getJSONArray("choices").getJSONObject(0).optString("finish_reason")),"Chat terminal finish reason");
            return;
        }
        String actual="";JSONObject response=null;
        for(JSONObject frame:frames(result.body)){
            if(path.endsWith("/responses")&&frame.optString("type").matches("response\\.(completed|incomplete)"))response=frame.optJSONObject("response");
            else if(path.endsWith("/messages")){JSONObject d=frame.optJSONObject("delta");if(d!=null&&!d.isNull("stop_reason"))actual=d.optString("stop_reason",actual);}
            else if(path.endsWith("/completions")){JSONArray a=frame.optJSONArray("choices");if(a!=null&&a.length()>0&&!a.getJSONObject(0).isNull("finish_reason"))actual=a.getJSONObject(0).optString("finish_reason",actual);}
        }
        if(path.endsWith("/responses")){check(response!=null&&(want.equals("length")?"incomplete":"completed").equals(response.optString("status")),"Responses streaming terminal status");if(want.equals("length"))check("max_output_tokens".equals(response.getJSONObject("incomplete_details").optString("reason")),"Responses streaming incomplete reason");}
        else check((path.endsWith("/messages")?(want.equals("length")?"max_tokens":"end_turn"):want).equals(actual),"stream terminal reason "+path+" got "+actual);
    }
    private static void trae()throws Exception{
        // requested 16 is clipped to 8: inference must use wire 8, not caller 16.
        int[][] cases={{8,0,8},{8,5,5},{8,16,8},{8,0,7},{0,0,8},{8,16,4,4},{0,0,4,4}};
        for(String path:PATHS)for(boolean stream:new boolean[]{false,true})for(int[] sample:cases){
            int cap=sample[0],asked=sample[1],output=sample[2],wire=asked>0?(cap>0?Math.min(asked,cap):asked):cap;
            int guard=sample.length>3?sample[3]:0;
            if(guard>0)wire=wire>0?Math.min(wire,guard):guard;
            String want=wire>0&&output>=wire?"length":"stop";
            String sse="event: output\ndata: {\"response\":\"budget result\"}\n\n"
                    +"event: token_usage\ndata: {\"prompt_tokens\":1,\"completion_tokens\":"+output+"}\n\n"
                    +"event: done\ndata: {\"finish_reason\":\"stop\"}\n\n";
            JSONObject cfg=traeConfig(cap);
            if(guard>0)cfg.put("guard",new JSONObject().put("enabled",true).put("maxBodyBytes",0).put("maxOutputTokens",guard));
            try(Upstream u=new Upstream((r,n)->new Reply(200,"text/event-stream",sse));Gateway g=new Gateway(cfg,u,false)){
                try{
                    g.gateway.setTraeOriginForTests("http://127.0.0.1:"+u.server.getLocalPort());
                    JSONObject input=simple(path,"trae-cn/fixture",stream,asked>0?asked:null);String original=input.toString();
                    Result result=call(g,path,input);finish(result,path,stream,want);
                    check(u.requests.size()==1,"one Trae physical request");physical+=u.requests.size();Request r=u.requests.get(0);
                    check("/api/agent/v3/llm_utils_chat".equals(r.path),"one exact Trae endpoint");
                    check(wire>0?r.body.optInt("max_tokens")==wire:!r.body.has("max_tokens"),"actual transmitted normal-mode budget: "+wire);
                    check(!r.body.has("user_message_context"),"no Max-only context field in normal mode");
                    check(original.equals(input.toString()),"caller budget/document untouched");
                    check(result.body.contains("budget result"),"truncation annotation retains response text");
                }finally{g.gateway.retireForRestore();}
            }
        }
    }
    private static JSONObject strictSchema(){return new JSONObject().put("type","object").put("properties",new JSONObject().put("value",
            new JSONObject().put("oneOf",new JSONArray().put(new JSONObject().put("type","string")).put(new JSONObject().put("type","number")))))
            .put("required",new JSONArray().put("value")).put("additionalProperties",false);}
    private static JSONObject codexConfig(){
        JSONObject account=new JSONObject().put("id","codex-fixture").put("enabled",true).put("access","synthetic-codex-token")
                .put("refresh","").put("expires",0).put("accountId","synthetic-codex-account").put("models",new JSONArray().put("fixture"));
        JSONObject p=provider("codex","codex",CodexAuth.CODEX_BASE,"fixture").put("accounts",new JSONArray().put(account)).put("keys",new JSONArray().put("synthetic-codex-token"));
        return config(p).put("guard",new JSONObject().put("enabled",false)).put("failover",new JSONObject().put("enabled",false));
    }
    private static JSONObject codexInput(String path,boolean stream,Object flag,boolean named){
        JSONObject input=simple(path,"codex/fixture",stream,null);JSONObject f=new JSONObject().put("name","sum").put("parameters",strictSchema());if(named)f.put("strict",flag);
        if(path.endsWith("/responses"))input.put("tools",new JSONArray().put(f.put("type","function")));
        else if(path.endsWith("/messages"))input.put("tools",new JSONArray().put(new JSONObject().put("name","sum").put("input_schema",strictSchema()))).put("max_tokens",64);
        else input.put("tools",new JSONArray().put(new JSONObject().put("type","function").put("function",f)));
        return input;
    }
    private static void codexCase(String path,boolean stream,Object flag,boolean named,boolean invalid)throws Exception{
        try(Upstream u=new Upstream((r,n)->{
            JSONObject tool=r.body.getJSONArray("tools").getJSONObject(0);
            if(Boolean.TRUE.equals(tool.opt("strict")))return new Reply(400,"application/json","{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Invalid schema: oneOf is unsupported in this synthetic strict backend\"}}");
            return new Reply(200,"text/event-stream",CodexRequestPolicyOfflineTest.EVENTS);
        });Gateway g=new Gateway(codexConfig(),u,false)){
            try{
                JSONObject input=codexInput(path,stream,flag,named);String original=input.toString();Result result=call(g,path,input);
                check(result.status==(invalid||Boolean.TRUE.equals(flag)?400:200),"Codex status for "+path+" flag "+flag+" got "+result.status+" "+result.body);
                check(u.requests.size()==(invalid?0:1),"Codex schema error/flag has no hidden replay");physical+=u.requests.size();
                if(!invalid){Request request=u.requests.get(0);JSONObject tool=request.body.getJSONArray("tools").getJSONObject(0);
                    check(Boolean.valueOf(Boolean.TRUE.equals(flag)).equals(tool.opt("strict")),"Codex final wire flag matches default or explicit flag");
                    check(strictSchema().similar(tool.getJSONObject("parameters")),"strict oneOf and property constraints unchanged on wire");
                    check(request.path.endsWith("/responses"),"Codex native backend response endpoint");
                }
                check(original.equals(input.toString()),"original Codex request immutable");
            }finally{g.gateway.retireForRestore();}
        }
    }
    private static void codex()throws Exception{
        for(String path:new String[]{PATHS[0],PATHS[2]})for(boolean stream:new boolean[]{false,true}){
            codexCase(path,stream,null,false,false);codexCase(path,stream,false,true,false);codexCase(path,stream,true,true,false);
        }
        for(boolean stream:new boolean[]{false,true})codexCase(PATHS[1],stream,null,false,false);
        for(String path:new String[]{PATHS[0],PATHS[2]})for(Object invalid:new Object[]{JSONObject.NULL,"true",0})codexCase(path,false,invalid,true,true);
    }
    public static void main(String[] args)throws Exception{
        workbuddy();trae();codex();
        System.out.println("ProviderAlignmentGatewayHttpTest PASS: "+checks+" checks; clientRequests="+logical+"; upstreamRequests="+physical+"; synthetic loopback only");
    }
}
