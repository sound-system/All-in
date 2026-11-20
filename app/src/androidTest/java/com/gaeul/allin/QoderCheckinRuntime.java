package com.gaeul.allin;

import android.content.Context;
import android.content.ContextWrapper;
import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

final class QoderCheckinRuntime {
    private static final String LIST = "/sash/api/v1/me/campaigns";
    private static final String DAILY = "/sash/api/v1/me/daily-check-in/status";
    private static final String ACCOUNT = "fixture-qoder-account";
    private static final String CREDENTIAL = "fixture-qoder-device";
    private static final String PROBE = "/api/checkin/reconcile";
    private static final String CLAIM = "/api/checkin/qoder-claim";
    private static final String VIEW = "/api/checkin/status";
    private int checks;
    private final AtomicInteger queries = new AtomicInteger(), claims = new AtomicInteger();
    private final long anchor = System.currentTimeMillis() - 3600000L;
    private volatile String campaign = "previous";
    private volatile boolean committed, loseReply, failQuery, empty;
    private volatile long start = anchor - 86400000L, end = anchor;

    private void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private JSONObject activity(String id, long from, long to, boolean claimed) {
        return new JSONObject().put("campaignId", id).put("actionType", "CLAIM_BENEFIT")
                .put("startAt", from).put("endAt", to).put("claimStatus", claimed ? "CLAIMED" : "CLAIMABLE")
                .put("benefit", new JSONObject().put("amount", 100));
    }

    private HttpURLConnection vendor(String address) throws IOException {
        URL url = new URL(address);
        check(("openapi.qoder.com.cn".equals(url.getHost()) && url.getPath().startsWith(LIST))
                || ("gateway.qoder.com.cn".equals(url.getHost()) && DAILY.equals(url.getPath())), "fixed Qoder CN host and path");
        return new HttpURLConnection(url) {
            final ByteArrayOutputStream sent = new ByteArrayOutputStream();
            boolean counted;
            @Override public void connect() { throw new AssertionError("fixture attempted real network"); }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public OutputStream getOutputStream() { return sent; }
            @Override public int getResponseCode() throws IOException {
                if (!counted) {
                    counted = true;
                    check(("Bearer " + CREDENTIAL).equals(getRequestProperty("Authorization")), "uses device credential");
                    check("10".equals(getRequestProperty("Cosy-ClientType")) && !getInstanceFollowRedirects(), "official client type without redirects");
                    if ("POST".equals(getRequestMethod())) {
                        check(url.getPath().equals(LIST + "/" + campaign + "/claim") && "{}".equals(sent.toString("UTF-8")), "one exact campaign claim");
                        claims.incrementAndGet();
                    } else {
                        check("GET".equals(getRequestMethod()) && (LIST.equals(url.getPath()) || DAILY.equals(url.getPath())), "read-only activity or daily listing");
                        queries.incrementAndGet();
                    }
                }
                if ("POST".equals(getRequestMethod()) && loseReply) throw new SocketTimeoutException("fixture response lost");
                return failQuery ? 503 : 200;
            }
            @Override public InputStream getInputStream() throws IOException {
                JSONObject response = new JSONObject();
                if ("POST".equals(getRequestMethod())) response.put("status", "CLAIMED");
                else if (DAILY.equals(url.getPath())) response.put("status","DISABLED").put("rewardCredits",100);
                else {
                    JSONArray rows = new JSONArray();
                    if (!empty) {
                        rows.put(activity(campaign, start, end, committed));
                        rows.put(activity("expired-old", anchor-86400000L, anchor, false));
                    }
                    response.put("campaigns", rows);
                }
                return new ByteArrayInputStream(response.toString().getBytes(StandardCharsets.UTF_8));
            }
        };
    }

    private static final class Reply {
        int status;
        JSONObject body;
    }

    private Reply http(GatewayServer gateway, String path, JSONObject payload, boolean auth) throws Exception {
        URL url = new URL("http://127.0.0.1:" + gateway.getListeningPort() + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (auth) c.setRequestProperty("X-All-In-Management", GatewayManagementCredential.get());
            if (payload != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            Reply r = new Reply();
            r.status = c.getResponseCode();
            try (InputStream in = r.status < 400 ? c.getInputStream() : c.getErrorStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[4096];
                for (int n; in != null && (n = in.read(b)) != -1;) out.write(b,0,n);
                r.body = new JSONObject(out.toString("UTF-8"));
            }
            return r;
        } finally { c.disconnect(); }
    }

    private JSONObject row(Reply reply) {
        check(reply.status == 200, "management HTTP " + reply.status);
        JSONArray rows = reply.body.getJSONArray("accounts");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (ACCOUNT.equals(row.optString("accountId"))) return row;
        }
        throw new AssertionError("Qoder account not in signup center");
    }

    private GatewayServer open(Context context) throws IOException {
        GatewayServer gateway = new GatewayServer(context, GatewayServer.planListen("127.0.0.1", true, 0, 0));
        gateway.setVendorConnectionsForTests(this::vendor);
        gateway.start(5000, true);
        return gateway;
    }

    static String run(Context target) throws Exception { return new QoderCheckinRuntime().exercise(target); }

    private String exercise(Context target) throws Exception {
        File dir = new File(target.getFilesDir(), "campaign-test-" + UUID.randomUUID());
        if (!dir.mkdir()) throw new IOException("Fixture directory unavailable");
        File config = new File(dir,"config.json");
        JSONObject account = new JSONObject().put("id",ACCOUNT).put("deviceToken",CREDENTIAL)
                .put("token","fixture-ordinary-job-token").put("uid","fixture-user");
        JSONObject provider = new JSONObject().put("id","qoder_cn").put("enabled",true).put("accounts",new JSONArray().put(account));
        JSONObject settings = new JSONObject().put("setup",new JSONObject().put("done",true))
                .put("server",new JSONObject().put("authKey","fixture-inference"))
                .put("providers",new JSONArray().put(provider)).put("checkin",new JSONObject().put("autoEnabled",true));
        AtomicConfigFile.write(config,settings.toString());
        Context context = new ContextWrapper(target) {
            @Override public File getFilesDir() { return dir; }
            @Override public Context getApplicationContext() { return this; }
        };
        JSONObject query = new JSONObject().put("provider","qoder_cn").put("accountId",ACCOUNT);
        JSONObject click = new JSONObject().put("accountId",ACCOUNT).put("campaignId","today");
        GatewayServer gateway = null;
        try {
            gateway = open(context);
            check(http(gateway,VIEW,null,false).status==401,"management authentication required");
            JSONObject initial = row(http(gateway,VIEW,null,true));
            check(initial.getBoolean("supported")&&!initial.getBoolean("autoEnabled")&&queries.get()==0,"only explicit user action queries Qoder");
            check(http(gateway,CLAIM,click,true).status==400&&claims.get()==0,"cannot claim before reading eligibility");
            check(http(gateway,"/api/checkin/run",query,true).status==400&&claims.get()==0,"legacy batch entry cannot claim Qoder");
            JSONObject found = row(http(gateway,PROBE,query,true));
            check("daily_disabled".equals(found.optString("outcome"))&&"DISABLED".equals(found.optString("dailyStatus"))&&!found.has("credit")&&claims.get()==0,"expired activity checks independent daily status without inventing reward");
            campaign="today"; start=anchor; end=anchor+86400000L;
            found = row(http(gateway,PROBE,query,true));
            check("claimable".equals(found.optString("outcome"))&&"today".equals(found.optString("campaignId"))&&found.getBoolean("claimPossible"),"only current activity selectable");
            check(!found.toString().contains(CREDENTIAL),"status hides device credential");
            loseReply=true;
            JSONObject unknown = row(http(gateway,CLAIM,click,true));
            check("unknown".equals(unknown.optString("outcome"))&&claims.get()==1&&unknown.optBoolean("retryBlocked"),"timeout keeps claim fence");
            check(http(gateway,CLAIM,click,true).status==400&&claims.get()==1,"duplicate click sends no second claim");
            gateway.stop(); gateway=null;
            gateway=open(context);
            found=row(http(gateway,PROBE,query,true));
            check("unknown".equals(found.optString("outcome"))&&claims.get()==1,"restart preserves claim fence despite claimable response");
            failQuery=true;
            found=row(http(gateway,PROBE,query,true));
            check(found.optBoolean("retryBlocked")&&claims.get()==1,"query outage cannot open fenced claim");
            failQuery=false; committed=true;
            found=row(http(gateway,PROBE,query,true));
            check("done".equals(found.optString("outcome"))&&found.getBoolean("verified")&&found.getInt("credit")==100&&claims.get()==1,"official confirmation reconciles timeout");
            // Change only the persisted fixture's previous end to model time passing across the boundary.
            gateway.stop(); gateway=null;
            JSONObject stored = new JSONObject(new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8));
            JSONObject prior = stored.getJSONObject("checkin").getJSONObject("records").getJSONObject("qoder_cn:"+ACCOUNT);
            prior.put("campaignEnd",anchor).put("fenceEnd",anchor);
            AtomicConfigFile.write(config,stored.toString());
            campaign="next"; end=anchor+86400000L; committed=false; loseReply=false;
            gateway=open(context);
            found=row(http(gateway,PROBE,query,true));
            check("next".equals(found.optString("campaignId"))&&found.getBoolean("claimPossible"),"new campaign replaces expired activity");
            found=row(http(gateway,CLAIM,click.put("campaignId","next"),true));
            check("claimed".equals(found.optString("outcome"))&&claims.get()==2&&!found.has("credit"),"claim without amount does not assert advertised credit");
            empty=true;
            found=row(http(gateway,PROBE,query,true));
            check("claimed".equals(found.optString("outcome"))&&found.getBoolean("verified")&&claims.get()==2,"empty listing preserves the confirmed receipt");
            check(!http(gateway,VIEW,null,true).body.toString().contains(CREDENTIAL),"status has no credential");
            return "ANDROID_QODER_CHECKIN_PASS checks="+checks+" queries="+queries.get()+" claims="+claims.get()+" api="+android.os.Build.VERSION.SDK_INT;
        } finally {
            if (gateway != null) gateway.stop();
            try (java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(dir.toPath())) {
                for(java.nio.file.Path file : (Iterable<java.nio.file.Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(file);
            }
        }
    }
}
