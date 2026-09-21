# LLM Provider 实现计划（bite-sized）

> 设计权威：`docs/superpowers/specs/2026-09-22-llm-provider-design.md`（判据 C1~C19）。
> 分支：`feat/adr1-core-scope`（主树）。基线 HEAD：`998332a`。
> 纪律：不跳步；新命令进 catalog ⇒ 喂饱 `McpCoverageTest` + `CatalogTool.PAYLOAD_HINTS`；
> 铁律 5 往返不破；显式路径 `git add`；不加 `Co-Authored-By`；不 `mvn install`。

## T1 — sd 模型：`DecisionMaker.providerId`

1. `DecisionMaker` 加第 6 组件 `Optional<String> providerId`（存在则非空白）；保留 5 参重载
   （`Optional.empty()`）。
2. ★ `SetViewScopeHandler` 重建时显式带 `existing.providerId()`（否则配权丢绑定）。
3. `ShowDecisionMaker` 视图透出：`SdQueryService.DecisionMakerInfo` + `ApiViews.decisionMaker` 加
   `providerId`（`null` = 未绑定，**不拿空串顶替**）。
4. 测试：`SetViewScopeHandlerTest` 加「配权不丢 providerId」；`SdModelTest` 加「providerId 空白拒」。

## T2 — sd 命令：`sd.SetDecisionMakerProvider`

1. `SetDecisionMakerProviderHandler`：`{decisionMakerId, providerId}`；dm 不存在 / 字段坏 ⇒ `Rejected`；
   写回 `DecisionMaker`（保其余字段）。
2. `SdPayloads.requireText` 复用，不加多余助手。
3. 测试：`SetDecisionMakerProviderHandlerTest`（成功改 providerId、dm 不存在拒、缺字段拒、重复绑定幂等）。
4. ★ 不动 `SdChangeSet`（`DecisionMaker` 在组件内，字段变化走 `FieldDelta` 天然覆盖）。

## T3 — app 基础设施：`LlmProvider` / `SecretRef` / `LlmProviderRegistry`

1. `LlmProvider`、`SecretRef`（含 `Kind`）record + 校验。
2. `LlmProviderRegistry`：`load(storeDir)` / `load(storeDir, env)`、`list/find/upsert/delete`、
   `resolveSecret`、`view/providerView`。落盘 `<store>/llm-providers.json`，列表按 id 字典序。
3. 密钥纪律：`resolveSecret` 只打印 `kind+ref+length`；缺失异常只点名引用；`view` 无值。
4. 测试 `LlmProviderRegistryTest`：C1~C4，含**哨兵自证**（配置 env 引用后，文件字节 / 日志 / view 都不含哨兵）。

## T4 — 真客户端：`HttpLlmClient`

1. `HttpLlmClient implements LlmClient`：OpenAI 兼容 `POST /v1/chat/completions`，`Supplier<String>` 取 key，
   可注入 `HttpClient`。
2. 测试 `HttpLlmClientTest`：本地 `com.sun.net.httpserver.HttpServer` stub——断言请求路径 / 方法 / `Bearer` 头 /
   体 JSON（model + 两条 message）；解析 `choices[0].message.content`；非 2xx / 坏 JSON ⇒ 抛；
   ★ 异常消息不含 key。

## T5 — 解析与降级：`LlmProviderResolver`

1. `llmClientFor(providerId)`：空 ⇒ 抛「未绑定」；查无 ⇒ 抛「绑定的 LLM provider 不存在: <id>」；
   命中 ⇒ 造 `HttpLlmClient`。
2. `adjudicatorFor(DecisionMaker)`。
3. 测试 `LlmProviderResolverTest`：C6/C8/C9 —— **变异靶子**：静默回落第一个 ⇒ 红。
4. 端到端 `SdProviderBindingEndToEndTest`：真 `CoreSimos` + store，提交 `sd.SetDecisionMakerProvider` ⇒ head 前进、
   `replay` 后 providerId 逐值；配权（`sd.SetViewScope`）后 providerId 仍在。C7。

## T6 — 装配与 GUI 端点

1. `Shell`：注册 `SetDecisionMakerProviderHandler`；构造 `LlmProviderRegistry`（`config.storeDir()`）+
   `LlmProviderResolver`；注入 `GuiServer`。
2. `GuiServer`：加 5 条路由（§5.1）；`as=` 拒绝；注册表缺席 ⇒ 503；`test` 端点打真 client。
3. `GuiServer` 构造器加注册表参数（旧构造器委托为 null ⇒ 503，`ShellApprovalTest` 不动）。
4. 测试 `LlmProviderApiTest`：C13/C14 —— CRUD + test（打 stub）+ 绑定落 revision。

## T7 — 目录与注册面

1. `CatalogTool.PAYLOAD_HINTS` 加 `sd.SetDecisionMakerProvider`。
2. `McpCoverageTest`：`EXPECTED_COMMAND_TYPES` + `MINIMAL_PAYLOADS` 加条目（放**最后**，减小 revision 号位移面）；
   head/字数断言随动。
3. `SimosToolsTest`：`EXPECTED_COMMAND_TYPES` 加条目；`catalogCoversEveryCommandHandlerImplementation`
   的 42→43。
4. `SdFixtures` 等测试构造点：5 参重载保住，**仅在需要断言 providerId 处**用 6 参。

## T8 — 前端配置页

1. `panels.js`：`DECISION_SUBPAGES` +`provider`；`decisionSubpageVisibility` 三键；纯函数
   `providerFormToPayload` / `providerFields` / `providerBindingPayload`。
2. `index.html`：第三 radio + `data-decision-subpage="provider"` 容器（provider 表单 + 列表 + 绑定表单）。
3. `api.js`：5 个函数（§5.2）。
4. `map.js` / `app.js`：子页三键（`map.js` 的 `decisionViewSubpageActive` 只看 `view`，不变）。
5. 测试 `provider-config.test.cjs`（约 12 条）：C15/C16 + 端点声明静态检查。
6. 门禁：`gate-contract.test.cjs` 的 `REQUIRED_FILES` + `MIN_ASSERTIONS`、`run-gate.cjs` 的 `MIN_TESTS`
   **两处同改**；`write-allowlist.test.cjs` 声明集合 + 动态对表（5→9）。
7. `decision-mode.test.cjs`：子页 2→3 的语义更新。

## T9 — 门禁 + 变异 + 关账

1. `./mvnw clean verify` 前台跑，记录**第几次尝试** + 最终绿轮文件名 + 逐模块 `SUCCESS [` + 增量。
2. 变异九道门禁（spec §六），红点落在被保护断言；存活项如实报理由。
3. 证据目录 `.superpowers/sdd/2026-09-22-llm-provider/`：报告（含 §我未能核实的）+ mutants/。
4. 显式 `git add`，中文提交信息，推送。

## §取代说明

（执行期就地校正记在这里。）
