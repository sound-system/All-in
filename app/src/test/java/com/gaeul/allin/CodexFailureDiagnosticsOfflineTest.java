package com.gaeul.allin;

import static com.gaeul.allin.VendorPolicyGatewayHttpTest.*;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLHandshakeException;

/** Types/stages only: deliberately secret-like exception messages must not enter the response/log. */
public final class CodexFailureDiagnosticsOfflineTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        String message="Bearer synthetic-exception-secret; private conversation must not be emitted";
        IOException fault=new IOException(message,new SSLHandshakeException(message));
        Method method=GatewayServer.class.getDeclaredMethod("codexFailureTypes",Throwable.class);method.setAccessible(true);
        String types=(String)method.invoke(null,fault);
        check(types.equals("IOException -> SSLHandshakeException"),"exception types extracted without messages");
        check(!types.contains("synthetic")&&!types.contains("Bearer"),"diagnostics no secret-like message");
        JSONObject account=new JSONObject().put("id","codex-fixture").put("enabled",true).put("access","synthetic-access")
                .put("refresh","").put("expires",0).put("accountId","synthetic-account").put("models",new JSONArray().put("fixture"));
        JSONObject p=provider("codex","codex",CodexAuth.CODEX_BASE,"fixture").put("accounts",new JSONArray().put(account)).put("keys",new JSONArray().put("synthetic-access"));
        JSONObject cfg=config(p).put("guard",new JSONObject().put("enabled",false)).put("failover",new JSONObject().put("enabled",false));
        AtomicInteger opens=new AtomicInteger();
        try(Upstream u=new Upstream((r,n)->new Reply(200,"text/event-stream",CodexRequestPolicyOfflineTest.EVENTS));Gateway g=new Gateway(cfg,u,false)){
            try{
                g.gateway.setVendorConnectionsForTests(url->{opens.incrementAndGet();throw fault;});
                Result r=post(g,"/v1/chat/completions",new JSONObject().put("model","codex/fixture").put("stream",false)
                        .put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content","fixture"))));
                check(r.status==502,"network failure status unchanged");
                check(r.body.contains("[open_connection]")&&r.body.contains("SSLHandshakeException"),"safe phase and type reach client");
                check(!r.body.contains("synthetic-exception-secret")&&!r.body.contains("private conversation"),"original exception message not exposed");
                check(u.requests.size()==0&&opens.get()==1,"diagnostic adds no retry or extra vendor send");
            }finally{g.gateway.retireForRestore();}
        }
        System.out.println("CodexFailureDiagnosticsOfflineTest PASS checks="+checks);
    }
}
