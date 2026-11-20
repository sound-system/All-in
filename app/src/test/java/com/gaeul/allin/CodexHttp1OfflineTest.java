package com.gaeul.allin;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Raw HTTP/1 fixture. No external network, credential, generated certificate or files. */
public final class CodexHttp1OfflineTest {
    private static int checks;
    private static void check(boolean ok,String s){checks++;if(!ok)throw new AssertionError(s);}
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static final class Fixture implements AutoCloseable {
        final ServerSocket listener;final Thread thread;final byte[] response;final AtomicInteger sends=new AtomicInteger();
        volatile String requestLine="",headers="",body="";volatile Throwable failure;
        Fixture(byte[] response)throws Exception{this.response=response;listener=new ServerSocket(0,4,InetAddress.getByName("127.0.0.1"));thread=new Thread(this::serve);thread.setDaemon(true);thread.start();}
        void serve(){try(Socket s=listener.accept()){
            s.setSoTimeout(5000);InputStream in=s.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream();int b,state=0;
            while(state<4&&(b=in.read())>=0){out.write(b);state=state==0&&b==13?1:state==1&&b==10?2:state==2&&b==13?3:state==3&&b==10?4:0;if(out.size()>65536)throw new IOException();}
            headers=out.toString("UTF-8");requestLine=headers.split("\r\n")[0];int length=0;
            for(String h:headers.split("\r\n"))if(h.toLowerCase().startsWith("content-length:"))length=Integer.parseInt(h.substring(15).trim());
            byte[] data=new byte[length];for(int n=0;n<length;){int x=in.read(data,n,length-n);if(x<0)throw new EOFException();n+=x;}body=new String(data,StandardCharsets.UTF_8);sends.incrementAndGet();
            s.getOutputStream().write(response);s.getOutputStream().flush();
        }catch(Throwable e){if(!listener.isClosed())failure=e;}}
        public void close()throws Exception{listener.close();thread.join(6000);check(!thread.isAlive(),"fixture stopped");if(failure!=null)throw new AssertionError("fixture",failure);}
        CodexHttp1Connection connection()throws Exception{CodexHttp1Connection c=new CodexHttp1Connection(new URL("http://127.0.0.1:"+listener.getLocalPort()+"/responses?x=1"),NetworkPolicy.parse(null));c.setConnectTimeout(3000);c.setReadTimeout(3000);return c;}
    }
    private static String read(InputStream in)throws Exception{try(InputStream s=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[31];for(int n;(n=s.read(b))>=0;)out.write(b,0,n);check(s.read()==-1,"repeated EOF is stable");return out.toString("UTF-8");}}
    public static void main(String[] args)throws Exception{
        check(CodexHttp1Connection.handles("https://chatgpt.com/backend-api/codex/responses"),"exact backend selected");
        for(String s:new String[]{"https://chatgpt.com.evil.invalid/","https://api.openai.com/v1/responses","https://auth.openai.com/oauth/token","https://daily-cloudcode-pa.googleapis.com/","http://chatgpt.com/","https://user@chatgpt.com/","https://chatgpt.com:444/"})check(!CodexHttp1Connection.handles(s),"other endpoint not changed");
        check(!(CodexHttp1Connection.open("https://api.openai.com/v1/responses") instanceof CodexHttp1Connection),"OpenAI API remains on normal transport");
        String reply="HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\nhello";
        try(Fixture f=new Fixture(bytes(reply))){CodexHttp1Connection c=f.connection();try{
            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Authorization","Bearer fixture-only");byte[] data=bytes("{\"text\":\"中文\"}");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}
            check(c.getResponseCode()==200&&c.getResponseCode()==200,"status cached without retry");check("OK".equals(c.getResponseMessage()),"status message");
            check("text/plain".equals(c.getContentType()),"case-insensitive header access");check(c.getHeaderFields().get("set-cookie").size()==2,"ordinary duplicate headers retained");
            check("hello".equals(read(c.getInputStream())),"exact length body");
            check(f.requestLine.equals("POST /responses?x=1 HTTP/1.1"),"actual HTTP1 wire request");check(f.body.equals(new String(data,StandardCharsets.UTF_8)),"request UTF8 bytes unchanged");
            check(f.headers.contains("Connection: close")&&f.headers.contains("Accept-Encoding: identity"),"no shared HTTP2 connection");check(f.sends.get()==1,"one physical request");
        }finally{c.disconnect();}}
        try(Fixture f=new Fixture(bytes("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nhe\r\n3;x=y\r\nllo\r\n0\r\nX-Finish: yes\r\n\r\n"))){CodexHttp1Connection c=f.connection();try{check("hello".equals(read(c.getInputStream())),"chunk extensions and trailers supported");}finally{c.disconnect();}}
        ByteArrayOutputStream gz=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream out=new java.util.zip.GZIPOutputStream(gz)){out.write(bytes("gzip body"));}
        ByteArrayOutputStream response=new ByteArrayOutputStream();response.write(bytes("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "+gz.size()+"\r\n\r\n"));response.write(gz.toByteArray());
        try(Fixture f=new Fixture(response.toByteArray())){CodexHttp1Connection c=f.connection();try{check("gzip body".equals(read(c.getInputStream())),"gzip decoded for existing JSON/SSE readers");}finally{c.disconnect();}}
        try(Fixture f=new Fixture(bytes("HTTP/1.1 429 Limited\r\nContent-Length: 5\r\nRetry-After: 30\r\n\r\nerror"))){CodexHttp1Connection c=f.connection();try{check(c.getResponseCode()==429&&"30".equals(c.getHeaderField("Retry-After")),"rejection facts retained");check("error".equals(read(c.getErrorStream())),"error body retained");check(f.sends.get()==1,"no rejection replay");}finally{c.disconnect();}}
        try(Fixture f=new Fixture(bytes("HTTP/1.1 302 Found\r\nLocation: https://elsewhere.invalid/\r\nContent-Length: 0\r\n\r\n"))){CodexHttp1Connection c=f.connection();try{check(!c.getInstanceFollowRedirects()&&c.getResponseCode()==302,"no credential redirect");check(f.sends.get()==1,"redirect never followed");}finally{c.disconnect();}}
        for(String broken:new String[]{"HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nabc","HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\nabc", "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\nabc"}){
            try(Fixture f=new Fixture(bytes(broken))){CodexHttp1Connection c=f.connection();try{
                try{read(c.getInputStream());throw new AssertionError("truncation/ambiguous framing accepted");}catch(IOException expected){check(true,"invalid response remains failure");}
                check(f.sends.get()==1,"failed body never retried");
            }finally{c.disconnect();}}
        }
        System.out.println("CodexHttp1OfflineTest PASS checks="+checks);
    }
}
