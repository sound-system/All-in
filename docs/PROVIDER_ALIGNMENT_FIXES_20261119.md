# 三项供应商兼容修补与验证（2026-11-19）

## 授权、基线与范围

用户原始 sent-at：2026-11-19T08:19:28.146Z（UTC，本地16:19:28 UTC+8），仅要求：WorkBuddy Kimi工具历史、Trae普通模式输出预算与截断终态、Codex缺省strict兼容且审慎保留显式严格约束；Codex需要真实测试时叫用户配合。

基线为6e223632cf1733c9fe2dd3f359584204be057008加本线程已有的1.2.72-test（78）Qoder查询修补工作树。既有改动保留。本轮未更新npm插件或Magpie本地源码、未扩大到国际站/企业登录/Tabbit/其他供应商，未使用子智能体。没有真实供应商账号请求或领取，没有模拟器操作、覆盖安装、签发新包、版本号更新、远端提交/推送/发布或CI触发。

## 先验证再修补

新增ProviderAlignmentRegressionTest先在未修补的生产代码上运行，三个分组分别失败：

- WorkBuddy：Kimi call-only turn carries one space。
- Trae：normal mode sends supplier output budget even when client omits it。
- Codex：omitted Codex strict explicitly false。

这些是生产纯函数对合成输入的失败，不是供应商现场故障测量。

### 对此前Trae判断的纠正

进一步核对完整调用链发现：通用网关的catalogOutputDefault/effectiveOutputLimit原本就可能从模型目录预填预算。因此不能由Trae适配器普通模式未自行补预算，推导成所有真实普通模式请求都缺少max_tokens。本轮强化适配器合同的一致性；实际确认的终态缺口是未把最终出站预算传给解码器，因而不能将已耗尽预算的stop标成length。

## 实现

### 1. WorkBuddy

只匹配选定出站模型kimi-k3.1-pro及连字符变体（含highspeed，大小写不敏感）。assistant有非空tool_calls、且content缺省/null/空字符串/仅空text parts时补一个空格。

其他模型、已有非空文字、空白文字、图片或其他内容不替换；工具ID/名称/参数、用户文字和工具结果不改。作用在现有WorkBuddyRequestPolicy.transform复制后的请求中，国内站/AI站经同一Chat适配器接入，不改原生DeepSeek路径或71版工具聚合。

### 2. Trae

普通模式在模型目录提供maxOutput时也补max_tokens；保留客户端更小预算、现有别名优先级及Max-only上下文字段。没有目录上限且客户端没指定时，不编造默认预算。

Gateway把真正发送的body.max_tokens传给TraeStream，再交给TraeProtocol.Decoder。只有实际预算大于0、官方输出计数大于0且达到该预算时，才把普通stop/缺省结束标为length。未知用量/预算不推断，不对CN个人模型硬套企业2000-token猜测；明确length/content_filter、工具结束、错误仍保留各自行为。

Chat finish_reason=length；Messages stop_reason=max_tokens；Responses status=incomplete且incomplete_details.reason=max_output_tokens，流式与非流式一致。

### 3. Codex

只在函数工具strict字段完全缺省时补false。显式true/false及完整parameters原样保留，不把oneOf改为anyOf、不为通过后端而把true降级为false。显式null/字符串/数字等非法strict在本地400拒绝，不出站。

覆盖顶层tools、namespace内函数及input中additional_tools/tool_search_output的工具声明；不递归修改parameters中同名属性、文字或非函数工具。namespace遍历有深度保护，所有处理都作用于复制后的请求，保留旧版输出Guard保障与不重发规则。

显式严格schema若后端不接受，仍应将其拒绝交给用户；本轮不宣称这类schema会被自动修成可运行。

## 新增验收

| 检查 | 最终结果 | 验证内容 |
|---|---:|---|
| ProviderAlignmentRegressionTest | 147项通过 | 空工具历史、模型限定、不可变、预算优先级、终态、严格schema及深度保护 |
| ProviderAlignmentGatewayHttpTest | 556项通过 | 实际网关与上游均为回环HTTP，检查收到的请求字节/字段及客户端最终输出 |

新增合计703项检查。HTTP夹具共74个客户端请求、68个上游出站；6个非法strict输入在本地拒绝，新增上游出站为0。WorkBuddy两站分别覆盖Chat/Messages/Responses的流式与非流式；Trae覆盖普通、请求预算高于模型预算、未耗尽、未知预算，以及Guard缩小预算的场景。实际接收的出站预算和返回终态分别检查，不只验证某分支执行过。

Codex使用合成账号和合成后端：验证缺省false、显式false、显式true原样保留、模拟严格schema拒绝不重放；**不是实际ChatGPT后端验收**。

## 既有回归与Android构建

最终同轮20个JVM/HTTP任务通过：新增两个任务及WorkBuddy请求/Chat策略、Chat接线、规则顺序、完成事件、额度、Native聚合/签名恢复/规则、Trae协议/流/网关/Max、Codex请求/Key池/持久化、ReleaseRepair和Qoder签到回归。任务中若本身运行多个子套件，不另冒称全仓测试覆盖。

网关动效、签到页面、管理鉴权边界三项面板任务通过，git diff --check通过。隔离Debug APK（com.gaeul.allin.repairtest）构建和lintDebug通过；该包未安装到模拟器。不把JVM或编译结果当作Android设备实测，模拟器仍运行此前已安装的78签名包。

## 真实Codex测试边界

真实Codex验证尚未进行。需用户配合和确认后，才打包/部署本次修补并使用其可用测试账号验证。建议先对照一个含oneOf的函数工具：strict缺省时按false发送，显式true时保持约束并观察实际后端是否接受；不得擅自使用已有账号或后台消耗额度。

## 参考

- [WorkBuddy 0.2.37](https://github.com/magpie-community/plugins/blob/0a34ff850887d4d4848e7655341e8cb0dd4d744d/packages/workbuddy/index.mjs)
- [Trae 0.2.45](https://github.com/magpie-community/plugins/blob/0a34ff850887d4d4848e7655341e8cb0dd4d744d/packages/trae/index.mjs)
- [Codex工具strict处理](https://github.com/yetone/magpie/blob/6a46bad3441151f91ab188cc378fd444f5564138/internal/provider/codex_strict.go)

Codex显式strict与schema约束的处理有意比参考源保守，不盲目照搬源里的宽松降级。
