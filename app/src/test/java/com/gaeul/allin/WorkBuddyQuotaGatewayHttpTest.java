package com.gaeul.allin;

import com.gaeul.allin.json.JSONArray;
import com.gaeul.allin.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.gaeul.allin.WorkBuddyNativeGatewayHttpTest.*;

/** Socket-level checks of the model-window policy. All credentials and upstream answers are synthetic. */
public final class WorkBuddyQuotaGatewayHttpTest {
    private static final String MODEL = "deepseek-v4.1-flash", ALIAS = MODEL + "-sg", OTHER = "hy4free";
    private static final String CHAT = "/v1/chat/completions", RESPONSES = "/v1/responses", MESSAGES = "/v1/messages";
    private static final String CODE = "workbuddy_model_quota_exhausted";
    private static int checks;

    private static void expect(boolean ok, String reason) {
        checks++;
        check(ok, reason);
    }

    private static JSONObject account(String id) {
        return new JSONObject().put("id", id).put("type", "api").put("token", "ck_fixture_quota_" + id);
    }

    private static JSONObject setup(String provider, boolean second) {
        JSONObject cfg = config(provider, "openai", WorkBuddyAuth.origin(provider) + "/v2", MODEL);
        JSONArray accounts = new JSONArray().put(account("A"));
        if (second) accounts.put(account("B"));
        cfg.getJSONArray("providers").getJSONObject(0).put("keys", new JSONArray()).put("accounts", accounts)
                .put("models", new JSONArray().put(MODEL).put(ALIAS).put(OTHER));
        cfg.getJSONObject("failover").put("maxTargets", 1);
        return cfg;
    }

    private static JSONObject input(String model, String path, boolean stream) {
        JSONObject body = new JSONObject().put("model", model).put("stream", stream);
        if (RESPONSES.equals(path)) return body.put("input", "hello").put("max_output_tokens", 1024);
        return body.put("max_tokens", 1024)
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", "hello")));
    }

    private static String date(long time, String zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone(zone));
        return f.format(new Date(time));
    }

    private static String windowMessage(String provider, long reset) {
        String time = date(reset, "GMT+08:00") + " UTC+8";
        if ("workbuddy".equals(provider)) return "您的使用量已超出频率限制，将在 " + time + " 重置，您也可以切换其他模型继续使用。";
        return "usage exceeds frequency limit, but don't worry, your usage will reset at " + time
                + ", alternatively, you can switch to the other models to continue using it.";
    }

    private static String error(String message, String code) {
        return new JSONObject().put("error", new JSONObject().put("message", message).put("code", code)).toString();
    }

    private static Reply rejected(String body) {
        return new Reply(429, "application/json", body);
    }

    private static Reply answer() {
        return new Reply(200, "text/event-stream", DeepSeekHarnessGatewayHttpTest.sse().replace("hello", "quota fixture answer"));
    }

    private static String bearer(Request request) {
        return request.headers.get("Authorization");
    }

    private static void assertQuota(Result result, long expected) {
        expect(result.status == 429, "verified quota HTTP status: " + result.status + " " + result.body);
        JSONObject detail = new JSONObject(result.body).getJSONObject("error");
        expect(CODE.equals(detail.optString("code")) && "quota_exceeded".equals(detail.optString("type")), "quota classification");
        expect("upstream_reset".equals(detail.optString("cooldown_source")) && detail.getLong("cooldown_until") == expected,
                "absolute reset timestamp from WorkBuddy");
        expect(Long.toString(expected).equals(header(result, "X-AllIn-Cooldown-Until")), "matching response deadline header");
        long wait = Long.parseLong(header(result, "Retry-After"));
        long secondsLeft = (expected - System.currentTimeMillis() + 999) / 1000;
        expect(wait == detail.getLong("retry_after") && Math.abs(wait - secondsLeft) <= 2, "Retry-After uses earliest active reset");
        expect(result.body.contains("模型额度已用尽") && !result.body.contains("200000000"), "clear refusal without invented token count");
    }

    private static void routing(String provider, String path, boolean stream) throws Exception {
        long resetA = System.currentTimeMillis() / 1000 * 1000 + 10 * 3600000L;
        long resetB = resetA - 3600000L;
        AtomicBoolean rejectB = new AtomicBoolean();
        JSONObject cfg = setup(provider, true);
        try (MockUpstream upstream = new MockUpstream((req, n) -> {
            String model = req.body.optString("model");
            if (OTHER.equals(model)) return answer();
            if ("Bearer ck_fixture_quota_A".equals(bearer(req))) return rejected(error(windowMessage(provider, resetA), "quota_exceeded"));
            return rejectB.get() ? rejected(error(windowMessage(provider, resetB), "quota_exceeded")) : answer();
        }); GatewayHarness gateway = new GatewayHarness(cfg, upstream)) {
            Result first = post(gateway.port(), path, input(provider + "/" + MODEL, path, stream), null);
            expect(first.status == 200 && first.body.contains("quota fixture answer"), "successful alternate account via " + path + " " + first.body);
            expect(upstream.requests.size() == 2, "first request sends once per account");
            expect("Bearer ck_fixture_quota_A".equals(bearer(upstream.requests.get(0)))
                    && "Bearer ck_fixture_quota_B".equals(bearer(upstream.requests.get(1))), "A rejected, B answered");
            expect(upstream.requests.get(0).path.endsWith("/chat/completions"), "known quota window uses WorkBuddy Chat upstream");

            Result alias = post(gateway.port(), path, input(provider + "/" + ALIAS, path, stream), null);
            expect(alias.status == 200 && upstream.requests.size() == 3, "alias sends only once");
            expect("Bearer ck_fixture_quota_B".equals(bearer(upstream.requests.get(2))), "alias also skips A");

            rejectB.set(true);
            assertQuota(post(gateway.port(), path, input(provider + "/" + ALIAS, path, stream), null), resetB);
            expect(upstream.requests.size() == 4 && "Bearer ck_fixture_quota_B".equals(bearer(upstream.requests.get(3))),
                    "another account's first refusal is the only outgoing request");
            assertQuota(post(gateway.port(), path, input(provider + "/" + MODEL, path, stream), null), resetB);
            expect(upstream.requests.size() == 4, "all accounts held: zero outgoing requests");
            Result other = post(gateway.port(), CHAT, input(provider + "/" + OTHER, CHAT, false), null);
            expect(other.status == 200 && upstream.requests.size() == 5, "another WorkBuddy model still works");
        }
    }

    private static void ordinaryFailures() throws Exception {
        for (String code : new String[] {"rate_limit_error", "14018"}) {
            String message = "14018".equals(code) ? "Credits exhausted. Please purchase add-on packs." : "Too many requests per minute.";
            try (MockUpstream upstream = new MockUpstream((req, n) -> rejected(error(message, code)));
                 GatewayHarness gateway = new GatewayHarness(setup("workbuddy", false), upstream)) {
                Result result = post(gateway.port(), CHAT, input("workbuddy/" + MODEL, CHAT, false), null);
                expect(result.status == 429 && !result.body.contains(CODE), "unrelated 429 does not become a model-window hold");
                expect("14018".equals(code) ? result.body.contains(message) : result.body.contains("rate_limit_error"),
                        "minute rate limit and exhausted credit remain distinct");
                post(gateway.port(), CHAT, input("workbuddy/" + ALIAS, CHAT, false), null);
                expect(upstream.requests.size() == 2, "unrelated 429 does not cool the alias");
            }
        }
        long reset = System.currentTimeMillis() / 1000 * 1000 + 7200000;
        JSONObject cfg = setup("workbuddy", true);
        cfg.getJSONObject("failover").put("enabled", false);
        try (MockUpstream upstream = new MockUpstream((req, n) -> rejected(error(windowMessage("workbuddy", reset), "quota_exceeded")));
             GatewayHarness gateway = new GatewayHarness(cfg, upstream)) {
            assertQuota(post(gateway.port(), CHAT, input("workbuddy/" + MODEL, CHAT, false), null), reset);
            expect(upstream.requests.size() == 1, "disabling fallback limits the original request to one send");
        }
    }

    private static void depletedAttemptBudget() throws Exception {
        long reset = System.currentTimeMillis() / 1000 * 1000 + 3600000L;
        JSONObject cfg = setup("workbuddy", true);
        cfg.getJSONObject("failover").put("maxAttempts", 1);
        try (MockUpstream upstream = new MockUpstream((req, n) -> rejected(error(windowMessage("workbuddy", reset), "quota_exceeded")));
             GatewayHarness gateway = new GatewayHarness(cfg, upstream)) {
            assertQuota(post(gateway.port(), CHAT, input("workbuddy/" + MODEL, CHAT, false), null), reset);
            expect(upstream.requests.size() == 1, "verified quota survives one-send attempt budget");
        }
    }

    private static void gatewayRestartAndDashboard() throws Exception {
        long reset = System.currentTimeMillis() / 1000 * 1000 + 3600000L;
        JSONObject cfg = setup("workbuddy", false);
        try (MockUpstream upstream = new MockUpstream((req, n) -> n == 1
                ? rejected(error(windowMessage("workbuddy", reset), "quota_exceeded")) : answer())) {
            try (GatewayHarness gateway = new GatewayHarness(cfg, upstream)) {
                assertQuota(post(gateway.port(), CHAT, input("workbuddy/" + MODEL, CHAT, false), null), reset);
                assertQuota(post(gateway.port(), CHAT, input("workbuddy/" + ALIAS, CHAT, false), null), reset);
                expect(upstream.requests.size() == 1, "hold is retained on the live gateway");
                JSONArray recent = UsageLedger.summary(1, "model").getJSONArray("recent");
                boolean displayed = false;
                for (int i = 0; i < recent.length(); i++) {
                    JSONObject row = recent.getJSONObject(i);
                    if (row.optBoolean("terminal") && ALIAS.equals(row.optString("model"))
                            && CODE.equals(row.optString("upstreamErrorCode")) && row.optInt("status") == 429
                            && row.optString("err").contains("模型额度已用尽")) displayed = true;
                }
                expect(displayed, "the usage API supplies the confirmed local rejection and its model to the dashboard");
            }
            try (GatewayHarness recreated = new GatewayHarness(cfg, upstream)) {
                Result response = post(recreated.port(), CHAT, input("workbuddy/" + ALIAS, CHAT, false), null);
                expect(response.status == 200 && upstream.requests.size() == 2,
                        "a new gateway has no persisted hold, so the next user request makes one upstream call");
            }
        }
    }

    private static void parserAndExpiry() {
        long now = 1795012800000L;
        long reset = now + 7200000;
        String cn = windowMessage("workbuddy", reset);
        String en = windowMessage("workbuddy-ai", reset);
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(cn, "quota_exceeded"), now) == reset, "Chinese UTC+8 exact");
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(en.replace("UTC+8", "UTC+08:00"), "quota_exceeded"), now) == reset,
                "English UTC+08:00 exact");
        String offset = en.replace(date(reset, "GMT+08:00") + " UTC+8", date(reset, "GMT-05:30") + " GMT-05:30");
        expect(WorkBuddyQuotaCooldown.resetAt(429, new JSONObject().put("msg", offset).toString(), now) == reset, "signed non-hour offset");
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(cn, "14018"), now) == 0, "explicit credits rejection wins over conflicting text");
        expect(WorkBuddyQuotaCooldown.resetAt(403, error(cn, "quota_exceeded"), now) == 0, "429 evidence required");
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(cn.replace(" UTC+8", ""), "quota_exceeded"), now) == 0, "offset required");
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(cn.replace("UTC+8", "UTC+08:99"), "quota_exceeded"), now) == 0, "invalid offset");
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(cn.replace(date(reset, "GMT+08:00").substring(0, 10), "2026-02-30"), "quota_exceeded"), now) == 0, "invalid civil date");
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(windowMessage("workbuddy", now), "quota_exceeded"), now) == 0, "already reset");
        expect(WorkBuddyQuotaCooldown.resetAt(429, error(windowMessage("workbuddy", now + 172800000), "quota_exceeded"), now) == 0, "out of known window");
        expect(WorkBuddyQuotaCooldown.resetAt(429, new JSONObject().put("request", cn).toString(), now) == 0, "echoed request is not evidence");
        WorkBuddyQuotaCooldown state = new WorkBuddyQuotaCooldown();
        state.observe("scope", reset, now);
        state.observe("scope", reset - 1000, now);
        expect(state.active("scope", reset - 1) == reset, "a concurrent observation cannot shorten a live hold");
        expect(state.active("scope", reset) == 0, "expiry requires no poll or timer");
        expect(new WorkBuddyQuotaCooldown().active("scope", now) == 0, "fresh state has no hold");
        expect(WorkBuddyQuotaCooldown.window("other", MODEL) == null && WorkBuddyQuotaCooldown.window("workbuddy", MODEL + "-other") == null,
                "known providers and exact upstream aliases only");
    }

    public static void main(String[] args) throws Exception {
        parserAndExpiry();
        for (String provider : new String[] {"workbuddy", "workbuddy-ai"}) {
            for (String path : new String[] {CHAT, RESPONSES, MESSAGES}) {
                for (boolean stream : new boolean[] {false, true}) routing(provider, path, stream);
            }
        }
        ordinaryFailures();
        depletedAttemptBudget();
        gatewayRestartAndDashboard();
        expect(IngressMemoryBudget.usedBytes() == 0, "all request budgets released");
        System.out.println("WorkBuddyQuotaGatewayHttpTest passed " + checks + " checks");
    }
}
