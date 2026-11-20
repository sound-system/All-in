# All In

**让 Android 手机运行 AI 网关，让电脑和手机上的客户端共用一个 API 地址。**

All In 在 Android 设备上运行 HTTP 服务。你在手机上配置供应商账号和 API Key，客户端用 All In 的网关口令访问模型。换账号、调整路由或限制可用模型，都可以在 All In 的面板里完成。推理请求由手机转发给所选供应商，模型在供应商侧运行。

[下载 APK](https://github.com/xunianooo/All-in/releases/latest) · [查看更新记录](https://github.com/xunianooo/All-in/releases) · [反馈问题](https://github.com/xunianooo/All-in/issues)

当前正式版为 **All In 1.2.90（构建 96）**，APK 和标签保留 `1.2.90-test` 命名。[1.2.90 更新说明](docs/RELEASE_1.2.90-test.md)

## 快速开始

1. 从 Releases 下载 APK，安装到 **Android 7.0（API 24）及以上**的设备。从旧版升级时，可以用同签名 APK 直接覆盖安装，保留账号和配置。
2. 打开 All In，按向导完成设置并启动网关。在「供应商」里登录支持的账号或添加 API Key，再查看该账号可用的模型。
3. 在面板复制 API 地址和网关口令，填入客户端。选一个面板或模型目录中实际存在的模型发起请求。

| 客户端位置 | OpenAI 兼容 Base URL | Anthropic 服务根地址 |
| --- | --- | --- |
| 与 All In 位于同一部手机 | `http://127.0.0.1:3425/v1` | `http://127.0.0.1:3425` |
| 同一局域网中的电脑或其他设备 | `http://<手机局域网 IP>:3425/v1` | `http://<手机局域网 IP>:3425` |

`3425` 是默认端口。Anthropic 客户端应最终请求 `/v1/messages`，Base URL 的填写方式以客户端设置为准。客户端使用 **All In 网关口令**，供应商凭据留在 All In 中。局域网访问时，以面板显示的地址和实际监听范围为准。

### 用 curl 验证

在环境中设置 `ALLIN_API_KEY` 为面板中的网关口令。从电脑连接时，把 `127.0.0.1` 换成手机的局域网 IP。

```bash
BASE_URL="http://127.0.0.1:3425/v1"
curl -sS "$BASE_URL/models" \
  -H "Authorization: Bearer $ALLIN_API_KEY"
```

复制返回的模型 `id`，替换下例中的 `provider/model`。启用路由组后，可在目录中找到 `group/<组 ID>`。

```bash
curl -sS "$BASE_URL/chat/completions" \
  -H "Authorization: Bearer $ALLIN_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"provider/model","messages":[{"role":"user","content":"你好"}]}'
```

网关也接受 `x-api-key` 请求头。需要让电脑持续使用网关时，请保持手机的网关前台服务运行。锁屏后如果中断，可以检查通知权限、电池优化和厂商的后台管理设置。

## 供应商与账号

| 接入方式 | 内置适配 |
| --- | --- |
| 账号、订阅或会话 | ZCode、WorkBuddy、Trae CN、CatPaw、小浣熊（Raccoon）、Qoder CN、Cline、Codex、Antigravity、GitHub Copilot、MiniMax Code、DeepSeek 网页版、Loomy |
| API Key 等配置 | WorkBuddy AI、DeepSeek API、SiliconFlow（硅基流动）、OpenAI、Anthropic、OpenRouter、腾讯混元 |

登录方式、模型和额度取决于账号与供应商。Loomy 支持手机验证码登录，也可使用已有会话凭据。部分适配依赖供应商网页或客户端协议，上游变更可能影响登录、模型同步或推理。面板也会显示尚未实现的占位入口，可用模型以实际同步结果为准。

- **多账号管理** · 同一供应商可以管理多个账号或 API Key，按账号启停，分别查看模型或额度。账号可在 Android 应用里用 JSON 文件迁移，整机配置另有密码加密备份。[账号迁移说明](docs/ACCOUNT_JSON_TRANSFER.md)
- **每日签到** · 签到中心显示受支持账号的状态，支持手动触发及网关运行时自动处理。奖励资格由供应商判定，ZCode 赠送通过手动入口领取。

## 模型路由与用量

客户端可以用 `provider/model` 指定供应商模型，也可以用 `group/<组 ID>` 请求路由组。路由组默认关闭。启用后，可为同一入口配置多个候选模型，并选择按顺序、轮流、手动指定优先项或近期用量优先。模型白名单用于控制对外可用的模型。裸模型名兜底默认关闭。

同一供应商内可选 **轮流、用量均衡、到期优先**。用量均衡优先比较可信的额度使用比例，缺少可比数据时参考本机近期 Token 与在途负载。到期优先使用供应商明确提供的额度到期或重置信息。模型权限、账号启停和冷却状态仍参与选路。[账号路由说明](docs/ACCOUNT_ROUTING.md)

「用量与治理」可查看调用记录、Token 统计和热力图。账本区分已知、部分已知、估算与未知用量。对于部分执行结果未知的请求，网关会停止自动重试，以避免重复执行或重复扣费。

## API 与模型能力

| 方法和路径 | 用途 |
| --- | --- |
| `GET /v1/models` | 查看网关对当前客户端提供的模型及路由组 |
| `POST /v1/chat/completions` | OpenAI Chat Completions |
| `POST /v1/responses` | OpenAI Responses |
| `POST /v1/messages` | Anthropic Messages |
| `POST /v1/images/generations` | 图片生成，适配 MiniMax Code 国区、Codex 和 Loomy |
| `POST /v1/images/edits` | MiniMax Code 国区参考图生成 |
| `POST /v1/videos` | 提交 MiniMax Code 国区视频任务 |
| `GET /v1/videos/{id}` | 查询视频任务 |
| `GET /v1/videos/{id}/content` | 获取已完成的视频 |

文本接口按所选路径处理普通 JSON 或 SSE 流式响应。工具调用、图片输入、推理字段和其他扩展能力取决于客户端协议、上游适配与具体模型。遇到无法安全转换的字段，相关路径会返回错误。[Responses 转换说明](docs/responses-bridge.md) · [Agent 字段兼容性](docs/responses-agent-compatibility.md)

Codex 生图使用 `codex/gpt-image-2`，Loomy 生图使用目录中的 `loomy/<模型 ID>`。MiniMax Code 国区生图使用 `minimax-code/MiniMax-Image`。视频可选择 `minimax-code/MiniMax-H3`、`minimax-code/MiniMax-H3-Max` 或 `minimax-code/MiniMax-Hailuo-2.3`，以面板实际目录为准。使用参考图时，可按接口提交 HTTPS URL、图片 data URL 或 multipart 文件。提交视频任务后，保留返回的完整 ID，并使用原网关口令查询或下载。媒体功能受上游账号权限、额度和频率限制。

## 访问与凭据

- 新安装会生成随机网关口令。旧版升级后若仍使用公开默认口令，请先在面板更换，再从其他设备连接。面板的管理接口使用独立管理凭据，推理客户端只需网关口令。
- 网关提供 HTTP。局域网请求的内容和口令没有 TLS 加密，请在可信网络使用，避免直接将服务暴露到公网。
- 账号 JSON 包含明文凭据。请保护这些文件及备份，提交 Issue 前先移除口令、Token、验证码、账号信息和敏感对话。
- 上游请求受供应商的服务条款、权限、额度和计费约束。启用脱敏规则时，也请确认请求中可发送给上游的内容。

## 从源码构建

使用 JDK 17、Android SDK Platform 37.0 和仓库自带的 Gradle Wrapper。仓库配置为 `minSdk 24`、`targetSdk 34`、`compileSdk 37`。[SDK 配置说明](docs/sdk.md)

```bash
./gradlew :app:assembleDebug
./gradlew :app:offlineTests
./gradlew :app:setupKeyHttpTests :app:streamingHttpTests :app:outboundRoutingHttpTests
```

Windows 使用 `gradlew.bat`。Debug APK 在 `app/build/outputs/apk/debug/`。GitHub Actions 的 Windows 与 Ubuntu CI 由维护者在 Actions 页面手动触发，另包含管理面板回归测试。Release APK 需要自行配置签名，同一应用的覆盖升级要求签名匹配。[CI 工作流](.github/workflows/ci.yml) · [签名说明](docs/RELEASE_SIGNING.md)

## 反馈与贡献

欢迎提交 Issue 或 Pull Request。报告兼容性问题时，请附上 All In 版本、Android 版本、客户端及协议、供应商及模型、复现步骤和脱敏错误信息。[GitHub Issues](https://github.com/xunianooo/All-in/issues)

## 致谢

All In 建立在开源项目和社区分享的工作之上，感谢每一位作者和维护者：

- **[Magpie](https://github.com/yetone/magpie)**：感谢 [@yetone](https://github.com/yetone) 和 Magpie 贡献者提供的开源网关、协议处理及供应商适配思路。All In 的部分实现参考或移植自 Magpie。
- **[magpie-community/plugins](https://github.com/magpie-community/plugins)**：感谢维护 OpenCode / Magpie 供应商插件的社区与各插件作者。项目在适配不同供应商的登录、请求和响应时，参考了社区发布的 npm 包及其源码；各插件有各自的作者和许可，请以对应项目的声明为准。
- **DeepSeek 网页适配贡献者**：感谢相关社区作者与贡献者分享实现；随应用附带的许可文件见 [`magpie-deepseek-web-auth-MIT.txt`](app/src/main/assets/licenses/magpie-deepseek-web-auth-MIT.txt)。

感谢所有提供反馈、测试问题和改进建议的朋友。

## 许可证

All In 由 [Gaeul](https://github.com/xunianooo) 开发，采用 [MIT License](LICENSE)。第三方代码与资源遵循各自许可，详见[第三方声明](docs/THIRD_PARTY_NOTICES.md)及[随应用分发的许可](app/src/main/assets/licenses/)。
