package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import com.gaeul.allin.json.JSONTokener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/** Qoder CN's user-initiated campaign flow. No client device identity is synthesized. */
final class QoderCheckin {
    static final String PROVIDER = "qoder_cn";
    private static final String URL = "https://openapi.qoder.com.cn/sash/api/v1/me/campaigns";
    private static final String DAILY_URL = "https://gateway.qoder.com.cn/sash/api/v1/me/daily-check-in/status";

    private QoderCheckin() { }

    static boolean eligible(JSONObject account) {
        return !token(account).isEmpty();
    }

    private static String token(JSONObject account) {
        String device = account.optString("deviceToken", "").trim();
        if (!device.isEmpty()) return device;
        if (account.optBoolean("deviceChat", false) || "pat".equals(account.optString("authSource"))) {
            return account.optString("token", "").trim();
        }
        return "";
    }

    static JSONObject state(String outcome, String message) {
        return new JSONObject().put("outcome", outcome).put("message", message).put("claimPossible", false);
    }

    static JSONObject status(JSONObject account, long now, GatewayServer.VendorConnectionFactory transport) throws IOException {
        JSONObject campaign;
        try { campaign = campaignStatus(account, now, transport); }
        catch (IOException failure) { campaign = state("failed", "Qoder CN 奖励活动查询失败，请稍后再查"); }
        // A currently claimable/confirmed campaign is authoritative. A dormant legacy
        // daily endpoint must never hide it or introduce a second claim route.
        if (campaign.has("campaignId")) return campaign;
        JSONObject daily;
        try { daily = dailyStatus(account, now, transport); }
        catch (IOException failure) { daily = state("failed", "每日签到状态暂时无法查询"); }
        String detail = daily.optString("message");
        if ("not_offered".equals(campaign.optString("outcome"))) {
            if ("daily_disabled".equals(daily.optString("outcome"))) {
                campaign.put("outcome", "daily_disabled").put("reasonCode", "official_daily_disabled")
                        .put("message", "官方旧每日签到接口当前停用（DISABLED），奖励活动列表也未提供可领取的每日奖励。此结果不代表 Qoder CN 没有签到功能，可在官方客户端核对当前账号。");
            } else {
                campaign.put("outcome", "unknown").put("reasonCode", "daily_confirmation_required")
                        .put("message", "奖励活动列表未提供可领取的每日奖励；" + detail + "。请在官方客户端核对，不会自动尝试领取。");
            }
        } else {
            campaign.put("message", campaign.optString("message") + "；" + detail);
        }
        campaign.put("dailyQueryOutcome", daily.optString("outcome"));
        for (String field : new String[]{"dailyStatus", "dailyNextAt"}) {
            if (daily.has(field)) campaign.put(field, daily.opt(field));
        }
        return campaign;
    }

    private static JSONObject dailyStatus(JSONObject account, long now, GatewayServer.VendorConnectionFactory transport) throws IOException {
        JSONObject body = callUrl(account, DAILY_URL, false, transport);
        int http = body.optInt("httpStatus");
        if (http != 200) return state(http == 401 || http == 403 ? "auth_required" : "failed", "每日签到查询失败（HTTP " + http + "）");
        JSONObject data = body.optJSONObject("data");
        if (data != null) body = data;
        String status = body.optString("status", "");
        // Only DISABLED has been verified against the live legacy endpoint. Other
        // states do not authorize an undocumented claim or imply today's success.
        if (!status.matches("[A-Z_]{1,48}")) throw new IOException("Invalid daily status");
        JSONObject result = "DISABLED".equals(status)
                ? state("daily_disabled", "官方旧每日签到接口当前停用（DISABLED）")
                : state("unknown", "官方每日签到返回 " + status + "，需进一步核对领取规则");
        result.put("dailyStatus", status);
        long next = AccountRoutingPolicy.time(body.opt("nextClaimAt"));
        if (next > now) result.put("dailyNextAt", next);
        // rewardCredits is configured reward, not evidence that any credit was claimed.
        return result;
    }

    private static JSONObject campaignStatus(JSONObject account, long now, GatewayServer.VendorConnectionFactory transport) throws IOException {
        JSONObject body = call(account, null, transport);
        if (body.optInt("httpStatus") != 200) {
            int code = body.optInt("httpStatus");
            return state(code == 401 || code == 403 ? "auth_required" : "failed", "Qoder CN 活动查询失败（HTTP " + code + "）");
        }
        JSONArray campaigns = body.optJSONArray("campaigns");
        JSONObject data = body.optJSONObject("data");
        if (campaigns == null && data != null) campaigns = data.optJSONArray("campaigns");
        if (campaigns == null) throw new IOException("Missing campaign list");
        long next = Long.MAX_VALUE;
        JSONObject current = null;
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject item = campaigns.optJSONObject(i);
            if (item == null) throw new IOException("Malformed campaign");
            if (!"CLAIM_BENEFIT".equals(item.optString("actionType"))) continue;
            String id = item.optString("campaignId", "");
            long start = AccountRoutingPolicy.time(item.opt("startAt"));
            long end = AccountRoutingPolicy.time(item.opt("endAt"));
            if (!validId(id) || start <= 0 || end <= start) throw new IOException("Malformed campaign window");
            if (now < start) { next = Math.min(next, start); continue; }
            if (now >= end) continue;
            String claimStatus = item.optString("claimStatus", "");
            if (!"CLAIMED".equals(claimStatus) && !"CLAIMABLE".equals(claimStatus)) continue;
            if (current != null) throw new IOException("Ambiguous campaign window");
            boolean claimed = "CLAIMED".equals(claimStatus);
            current = state(claimed ? "done" : "claimable", claimed ? "本轮活动已领取" : "本轮活动可领取")
                    .put("campaignId", id).put("campaignStart", start).put("campaignEnd", end)
                    .put("verified", claimed).put("claimPossible", !claimed);
            if (claimed) setCredit(current, item);
        }
        if (current != null) return current;
        JSONObject answer = state("not_offered", "官方奖励活动列表当前未提供可领取的每日奖励，不能据此判断签到功能不存在")
                .put("reasonCode", "no_reward_campaign");
        if (next != Long.MAX_VALUE) answer.put("nextAt", next);
        return answer;
    }

    static JSONObject claim(JSONObject account, String id, GatewayServer.VendorConnectionFactory transport) throws IOException {
        if (!validId(id)) throw new IOException("Invalid campaign ID");
        JSONObject body = call(account, id, transport);
        JSONObject data = body.optJSONObject("data");
        String result = body.optString("status", data == null ? "" : data.optString("status", ""));
        if (body.optInt("httpStatus") != 200 || !"CLAIMED".equals(result)) throw new IOException("Claim outcome uncertain");
        JSONObject answer = state("claimed", "官方已确认本轮领取").put("verified", true);
        setCredit(answer, body.optJSONObject("benefit") != null || data == null ? body : data);
        return answer;
    }

    private static boolean validId(String id) {
        return id.matches("[A-Za-z0-9_-]{1,128}");
    }

    private static void setCredit(JSONObject answer, JSONObject source) {
        JSONObject benefit = source.optJSONObject("benefit");
        Object value = benefit == null ? null : benefit.opt("amount");
        if (value instanceof Number && Double.isFinite(((Number) value).doubleValue()) && ((Number) value).doubleValue() > 0) {
            answer.put("credit", value).put("unit", "积分");
        }
    }

    private static JSONObject call(JSONObject account, String id, GatewayServer.VendorConnectionFactory transport) throws IOException {
        return callUrl(account, id == null ? URL : URL + "/" + id + "/claim", id != null, transport);
    }

    private static JSONObject callUrl(JSONObject account, String url, boolean claim, GatewayServer.VendorConnectionFactory transport) throws IOException {
        String access = token(account);
        if (access.isEmpty()) throw new IOException("Missing Qoder campaign credential");
        HttpURLConnection conn = transport.open(url);
        try {
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(7000);
            conn.setReadTimeout(7000);
            conn.setRequestMethod(claim ? "POST" : "GET");
            conn.setRequestProperty("Authorization", "Bearer " + access);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Cosy-ClientType", "10");
            conn.setRequestProperty("User-Agent", "Qoder");
            conn.setRequestProperty("Content-Type", "application/json");
            if (claim) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(body); }
            }
            int code = conn.getResponseCode();
            if (code != 200) return new JSONObject().put("httpStatus", code);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                for (int n; (n = in.read(buffer)) != -1;) {
                    if (out.size() + n > 262144) throw new IOException("Campaign response too large");
                    out.write(buffer, 0, n);
                }
                JSONTokener parser = new JSONTokener(out.toString("UTF-8"));
                Object parsed = parser.nextValue();
                if (!(parsed instanceof JSONObject) || parser.nextClean() != 0) throw new IOException("Invalid campaign response");
                JSONObject result = (JSONObject) parsed;
                if (result.has("code") && !"0".equals(result.optString("code"))) throw new IOException("Campaign returned a business error");
                return result.put("httpStatus", code);
            }
        } finally {
            conn.disconnect();
        }
    }
}
