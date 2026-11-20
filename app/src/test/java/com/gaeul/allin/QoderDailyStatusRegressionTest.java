package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** No network, credentials or invented legacy daily claim protocol. */
public final class QoderDailyStatusRegressionTest {
    private static int checks;
    private static final long NOW = 1795068000000L;
    private static final String CAMPAIGNS = "https://openapi.qoder.com.cn/sash/api/v1/me/campaigns";
    private static final String DAILY = "https://gateway.qoder.com.cn/sash/api/v1/me/daily-check-in/status";
    private static final JSONObject ACCOUNT = new JSONObject().put("id","fixture")
            .put("deviceToken","fixture-device-credential").put("token","fixture-job-credential");
    private static void check(boolean yes, String name) { checks++; if (!yes) throw new AssertionError(name); }
    private static JSONObject activity(String id, long start, long end, String status) {
        return new JSONObject().put("campaignId",id).put("actionType","CLAIM_BENEFIT")
                .put("startAt",start).put("endAt",end).put("claimStatus",status)
                .put("benefit",new JSONObject().put("amount",100));
    }
    private static final class Transport implements GatewayServer.VendorConnectionFactory {
        JSONObject campaigns = new JSONObject().put("campaigns",new JSONArray());
        JSONObject daily = new JSONObject().put("campaignKey","cn_daily_check_in_legacy")
                .put("status","DISABLED").put("rewardCredits",100).put("nextClaimAt",0)
                .put("currentStreakDays",0).put("totalClaimDays",0).put("totalRewardCredits",0).put("rewardExpiresAt",0);
        int campaignHttp = 200, dailyHttp = 200, queries, dailyQueries, claims;
        boolean malformedDaily, failDaily;
        public HttpURLConnection open(String url) throws IOException {
            boolean dailyRequest = DAILY.equals(url);
            check(dailyRequest || CAMPAIGNS.equals(url) || url.equals(CAMPAIGNS+"/live/claim"),"fixed official URLs only");
            return new HttpURLConnection(new URL(url)) {
                public void disconnect() { }
                public boolean usingProxy() { return false; }
                public void connect() { }
                public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                public int getResponseCode() throws IOException {
                    check("Bearer fixture-device-credential".equals(getRequestProperty("Authorization")),"device token only");
                    check(!getInstanceFollowRedirects(),"no redirect can leak credentials");
                    check("application/json".equals(getRequestProperty("Accept")),"JSON response requested");
                    if ("POST".equals(getRequestMethod())) {
                        check(!dailyRequest,"never try the unverified legacy daily claim endpoint"); claims++; return 200;
                    }
                    check("GET".equals(getRequestMethod())&&!getDoOutput(),"status is read only");
                    queries++;
                    if (dailyRequest) { dailyQueries++; if(failDaily)throw new SocketTimeoutException(); }
                    return dailyRequest ? dailyHttp : campaignHttp;
                }
                public InputStream getInputStream() {
                    String response = "POST".equals(getRequestMethod())
                            ? new JSONObject().put("status","CLAIMED").put("benefit",new JSONObject().put("amount",100)).toString()
                            : dailyRequest ? (malformedDaily ? "not-json" : daily.toString()) : campaigns.toString();
                    return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
                }
            };
        }
    }
    private static JSONObject query(Transport t) throws Exception { return QoderCheckin.status(ACCOUNT,NOW,t); }
    public static void main(String[] args) throws Exception {
        Transport t = new Transport();
        // The live 2026-11-19 response contained only VIEW_DETAILS (a subscription offer).
        t.campaigns.put("campaigns",new JSONArray().put(new JSONObject().put("actionType","VIEW_DETAILS").put("claimStatus","CLAIMED")));
        JSONObject r = query(t);
        check("daily_disabled".equals(r.optString("outcome")),"explicit official disabled, not guessed activity closure");
        check("DISABLED".equals(r.optString("dailyStatus"))&&"official_daily_disabled".equals(r.optString("reasonCode")),"preserve bounded official evidence");
        check(t.queries==2&&t.dailyQueries==1&&t.claims==0,"subscription-only list probes independent daily status without claiming");
        check(!r.has("credit")&&!r.has("streak")&&!r.optBoolean("verified"),"configured 100 credits is not received reward");
        check(!r.has("campaignId")&&!r.optBoolean("claimPossible"),"disabled endpoint cannot enable claim");
        check(!r.toString().contains("credential"),"no credential in result");
        t.campaigns.put("campaigns",new JSONArray().put(activity("live",NOW-60000,NOW+60000,"CLAIMABLE")));
        int before=t.dailyQueries;
        r=query(t);check("claimable".equals(r.optString("outcome"))&&r.optBoolean("claimPossible"),"live reward campaign remains claimable despite disabled legacy endpoint");
        check(t.dailyQueries==before,"legacy fallback cannot delay known campaign");
        check(!r.has("credit"),"unclaimed reward is not received credit");
        r=QoderCheckin.claim(ACCOUNT,"live",t);check("claimed".equals(r.optString("outcome"))&&r.optBoolean("verified")&&r.getInt("credit")==100&&t.claims==1,"existing campaign claim protocol retained");
        t.campaigns.put("campaigns",new JSONArray().put(activity("live",NOW-60000,NOW+60000,"CLAIMED")));
        r=query(t);check("done".equals(r.optString("outcome"))&&r.optBoolean("verified"),"confirmed campaign remains confirmed");
        t.campaigns=new JSONObject().put("data",new JSONObject().put("campaigns",new JSONArray().put(activity("live",NOW-60000,NOW+60000,"CLAIMABLE"))));
        check("claimable".equals(query(t).optString("outcome")),"wrapped campaign response supported");
        t.campaigns=new JSONObject().put("campaigns",new JSONArray().put(activity("old",NOW-86400000,NOW,"CLAIMED")).put(activity("future",NOW+60000,NOW+86400000,"CLAIMABLE")));
        r=query(t);check(!r.has("campaignId")&&r.getLong("nextAt")==NOW+60000,"official end is exclusive; future campaign never claimable early");
        t.daily=new JSONObject().put("status","UNRECOGNIZED_STATE").put("rewardCredits",100).put("nextClaimAt",(NOW+3600000)/1000);
        r=query(t);check("unknown".equals(r.optString("outcome"))&&!r.optBoolean("claimPossible")&&!r.has("credit"),"unverified daily state never enables guessed claim or success");
        check(r.getLong("dailyNextAt")==NOW+3600000,"read supplier seconds as milliseconds without guessing reset");
        t.daily=new JSONObject().put("status","CLAIMED").put("rewardCredits",100);
        r=query(t);check("unknown".equals(r.optString("outcome"))&&!r.optBoolean("verified")&&!r.has("credit"),"legacy status alone without verified day/window does not claim today's reward");
        t.daily=new JSONObject().put("data",new JSONObject().put("status","DISABLED"));
        check("daily_disabled".equals(query(t).optString("outcome")),"wrapped daily status supported");
        t.malformedDaily=true;check("unknown".equals(query(t).optString("outcome")),"invalid daily JSON is query uncertainty, not disabled");
        t.malformedDaily=false;t.failDaily=true;check("unknown".equals(query(t).optString("outcome")),"daily timeout not mistaken for activity closure");
        t.failDaily=false;t.dailyHttp=401;r=query(t);check("auth_required".equals(r.optString("dailyQueryOutcome"))&&!r.optBoolean("claimPossible"),"independent daily authentication failure retained");
        t.dailyHttp=404;check("unknown".equals(query(t).optString("outcome")),"missing endpoint not inactivity or success");
        t.campaignHttp=401;t.dailyHttp=200;r=query(t);check("auth_required".equals(r.optString("outcome")),"legacy status cannot hide campaign credential rejection");
        t.campaignHttp=200;t.campaigns=new JSONObject().put("campaigns",new JSONArray().put(activity("one",NOW-60000,NOW+60000,"CLAIMABLE")).put(activity("two",NOW-60000,NOW+60000,"CLAIMABLE")));
        check("failed".equals(query(t).optString("outcome")),"overlapping campaign ambiguity never authorizes a claim");
        check(t.claims==1,"all status probes issue zero additional claims");
        System.out.println("QoderDailyStatusRegressionTest PASS: "+checks+" checks");
    }
}
