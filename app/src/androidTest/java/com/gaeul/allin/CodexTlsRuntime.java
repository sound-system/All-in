package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.net.Socket;
import javax.net.ssl.*;

/** Fixed-host TLS-only diagnostics: no HTTP, accounts, tokens or persistent configuration. */
final class CodexTlsRuntime {
    static String run()throws Exception {
        JSONArray results=new JSONArray();
        for(String version:new String[]{"default","TLSv1.2","TLSv1.3","TLSv1.2","default","TLSv1.2"}) {
            long start=System.nanoTime();Socket socket=null;JSONObject row=new JSONObject().put("requested",version).put("httpRequests",0);
            try {
                Socket plain=NetworkPolicy.parse(null).connectTunnel("chatgpt.com",443,7000,7000);socket=plain;
                SSLContext context=SSLContext.getInstance("TLS");context.init(null,null,null);
                SSLSocket tls=(SSLSocket)context.getSocketFactory().createSocket(plain,"chatgpt.com",443,true);socket=tls;
                if(!version.equals("default"))tls.setEnabledProtocols(new String[]{version});
                SSLParameters p=tls.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");
                try{SSLParameters.class.getMethod("setApplicationProtocols",String[].class).invoke(p,(Object)new String[0]);}catch(NoSuchMethodException unsupported){}
                tls.setSSLParameters(p);tls.setSoTimeout(7000);tls.startHandshake();
                row.put("ok",true).put("negotiated",tls.getSession().getProtocol()).put("cipher",tls.getSession().getCipherSuite());
            }catch(Exception error){row.put("ok",false).put("exceptionType",error.getClass().getSimpleName());Throwable cause=error.getCause();if(cause!=null)row.put("causeType",cause.getClass().getSimpleName());}
            finally{if(socket!=null)try{socket.close();}catch(Exception ignored){}}
            row.put("elapsedMs",(System.nanoTime()-start)/1000000);results.put(row);
        }
        return "CODEX_TLS_ONLY "+new JSONObject().put("host","chatgpt.com").put("results",results).toString();
    }
}
