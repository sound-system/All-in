# Qoder CN 签到状态核对（2026-11-19）

## 范围与当前完成度

基于 All In 1.2.71 / versionCode 77 / `6e22363`。用户于 2026-11-19T06:18:11.419Z（UTC）要求做好 Qoder CN 每日签到；本轮经确认获准只读查询 AllIn_UserTest_API34 / emulator-5580 已有 Qoder CN 账号，未发送真实领取、未续期或修改账号凭据。

**本轮完成查询补齐和误报修补，不是已完成真实每日领取。** 旧独立每日接口的可领取状态、有效周期及领取响应目前没有成功现场证据，因此没有猜测其领取协议或重新启用该入口。需要官方客户端同账号当前可领取页面进一步核对。

## 只读现场事实

- 账号与用户截图按显示名称、官方 UID 一致；界面显示的标识并非 All In 内部账号 ID，不能只比较内部 ID 判断是不同账号。
- 2026-11-19T06:26:16Z（UTC），`GET https://openapi.qoder.com.cn/sash/api/v1/me/campaigns` 返回 HTTP 200，只含一条 `VIEW_DETAILS` 的首次订阅优惠，未含 `CLAIM_BENEFIT`。
- 2026-11-19T06:26:52Z（UTC），只读使用该账号 UID 经本机官方 Qoder CN 身份工具获取真实设备身份再查，仍只返回同一订阅优惠。身份未保存、未回显，不伪造 Android/桌面身份。这个对照不能证明其他设备没有资格。
- 2026-11-19T06:28:07Z（UTC），`GET https://gateway.qoder.com.cn/sash/api/v1/me/daily-check-in/status` 分别用该账号已有 device token 和 job token 返回 HTTP 200、`status=DISABLED`。后续仅 device token 的读回同样为 DISABLED。
- 该响应的 `campaignKey=cn_daily_check_in_legacy`、`rewardCredits=100`、`nextClaimAt=0`、累计领取和累计奖励为 0。100 是奖励配置，**不是已领取证据**；legacy 接口停用不等于整个 Qoder CN 没有签到。
- 凭据只在内存中用于对固定官方 HTTPS 地址发 GET，不落盘或写日志。本轮真实领取次数为零。

## 本轮代码

- 现有奖励活动有明确当前 CLAIMABLE/CLAIMED 时继续走原协议及持久领取围栏，旧每日接口不能阻碍该路径。
- 未找到当前奖励活动时，补查固定官方 daily-check-in/status。区分明确 DISABLED、未知状态、401/403、超时、404 和无效 JSON。
- 不按未经验证的旧每日状态猜测 claim 请求、当前周期或领取成功；不会显示其配置积分为奖励。
- 奖励活动支持顶层和 data 包装；未来活动不可提前领，保留官方起始时间，截止时间仍为排他边界。
- 页面将“查询活动”改为“查询签到”；历史 inactive 记录显示“未查到可领取奖励”，提示更新独立签到查询。明确 legacy DISABLED 显示“旧签到接口停用”，不泛化为全部签到活动停用。
- 不改变网关推理、鉴权、凭据刷新、Qoder 首包期限；不加入自动或一键签到。

## 本地验证

- 新增 QoderDailyStatusRegressionTest：154 项检查通过。
- CheckinCenter / Status / Proof / Gateway：44 + 17 + 48 + 21 项通过。
- ReleaseRepairOfflineTest：131 项通过。六组共 415 项检查。
- 签到 DOM：浅色、深色各 74 项通过；另通过网关动效、管理边界、赠送安全、返回导航、弹性设置五组面板任务。
- 隔离 Debug APK、Android 测试组件编译及 lintDebug 通过；Android 测试组件尚未在设备执行，不得把编译成功写成设备测试成功。
- 未对正式应用覆盖安装、未签发新正式包、未变更版本号、未提交/推送/发布或触发 CI。

## 参考（不能替代现场证据）

- 原有本地 opencode-qoder-auth 快照：奖励活动 CLAIM_BENEFIT、设备身份条件以及每日 10:00（UTC+8）活动窗口。
- [旧独立每日接口调查](https://github.com/hope0719/qoder-check-in)：MIT 项目，定位 gateway daily-check-in/status；未照搬其以 HTTP 200 当领取成功或直接重发的逻辑。
- [后续奖励活动方案](https://github.com/re-skylar/qoder-check-in)：改为 openapi campaigns。两个源码说明不能证明当前账号/官方客户端的实时可领取入口。

## 后续授权覆盖安装（2026-11-19）

用户于2026-11-19T06:38:16.676Z（UTC）要求覆盖安装后自己试。当前修补以1.2.72-test（78）、原证书签名覆盖到AllIn_UserTest_API34 / emulator-5580。v2/v3、包名/版本及面板字节已验证。安装前后 files目录11个私有文件及UID10192一致，安装APK与交付包SHA一致，MainActivity已启动。没有替用户发起真实Qoder领取；这次安装不是领取成功证明。未推送或公开发布。
