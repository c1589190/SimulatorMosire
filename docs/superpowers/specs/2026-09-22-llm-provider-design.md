# LLM Provider 设计（通用 provider 注册表 + 决策人绑定 + 配置页）

> 里程碑：**M11（LLM Provider）**。缘起（用户原话，逐字）：
> 「不是，是**搞一个通用的llm provider**，**决策人只能帮定provider用**；然后额外做一个**简单的provider配置页面**就行」
>
> 状态：**已裁（控制器 4 个默认 + 本 spec）**。实现前先读 §〇.1 判据。

## §〇 本任务是什么 / 不是什么

**是**：把 SDSimos D 阶段留下的**已知盲区**（「只用 lambda 假客户端、从未接真实 LLM」，见
`docs/superpowers/specs/2026-09-20-sd-simos-design.md` §八.5 / 台账 D 段）补上，并给出**通用 provider 注册表**：
LLM 端点（baseUrl / model / 密钥引用）是**基础设施**，收在 app 层一个配置文件里；**决策人**只持有一个
**providerId 引用**（进 sd、落 revision、可回放）。

**不是**：
- ❌ 不是把 LLM 端点塞进 sd 领域模型（铁律 3：provider 不是世界事实）。
- ❌ 不是自动裁决编排（`AdvanceTime` 时自动跑 LLM）。本单只交付**解析与调用路径**，裁决编排仍归 app 的
  `AdjudicatorRunner`（既有，未接壳——见 §八）。
- ❌ 不是多用户认证 / 密钥托管。密钥**只存引用**（环境变量名或文件路径）。

### §〇.0 四条地基（控制器已定，逐条在本 spec 落成结构）

1. **Provider 注册表存 app 层配置文件**（`<store>/llm-providers.json`），**不进 sd ChangeSet**。
2. **密钥只存"引用"**（env 变量名 / 文件路径）；**值绝不入库 / 不进日志 / 不进异常 / 不进 argv / 不进 stdio**；
   读配置只打印**路径 + 长度**（本仓密钥纪律）。
3. **决策人的绑定进 sd**：新命令 `sd.SetDecisionMakerProvider {decisionMakerId, providerId}` ⇒ 落 revision、
   可回放、可分岔；**回放时若 provider 不存在 ⇒ 明确报错或明确降级，不许静默**。
4. **真客户端 `HttpLlmClient implements LlmClient`**（OpenAI 兼容 `POST /v1/chat/completions`），**构造器可注入**；
   配置页 = 决策模式下的**第三个子页**（provider CRUD + 「测试连接」+ 决策人绑定）。

## §〇.1 判据（关账逐条实测，无占位符）

| # | 判据 | 落点 |
|---|---|---|
| C1 | 注册表读写 `<store>/llm-providers.json`；列表按 id 字典序（响应字节可复现） | `LlmProviderRegistryTest` |
| C2 | ★ **密钥值不入库**：配置了 env 引用的 provider 落盘后，文件字节**不含**该值的哨兵串 | `LlmProviderRegistryTest`（自证） |
| C3 | ★ **密钥值不进日志 / 异常 / 视图**：`resolveSecret` 缺失时异常只点名**引用名**、不点名值；`view()` 无值 | `LlmProviderRegistryTest` |
| C4 | `resolveSecret` 对 ENV / FILE 两种引用都返回正确值；FILE 的读取只打印**路径 + 长度** | `LlmProviderRegistryTest` |
| C5 | ★ **真客户端** `HttpLlmClient` 打本地 stub 的 `POST /v1/chat/completions`，带 `Authorization: Bearer`，解析 `choices[0].message.content` | `HttpLlmClientTest`（本地 stub，无外网） |
| C6 | `HttpLlmClient` 的 HTTP 非 2xx / 坏 JSON ⇒ 抛异常（由 `LlmDecisionAdjudicator` 折成 `Judgement.Failed`，N13） | `HttpLlmClientTest` + `LlmProviderResolverTest` |
| C7 | ★ **绑定落 revision、可回放**：`sd.SetDecisionMakerProvider` 经 `CoreSimos.submit` 提交后 head 前进，`replay` 后 dm 的 `providerId` 逐值等于所绑 | `SetDecisionMakerProviderHandlerTest` + `SdProviderBindingEndToEndTest` |
| C8 | ★ **回放/解析时 provider 不存在 ⇒ 明确报错，不静默兜底**：绑定悬空 id ⇒ 解析抛出的消息**点名该 id** | `LlmProviderResolverTest`（变异靶子） |
| C9 | ★ **未绑定 provider 的决策人不得被静默兜到某个默认 provider**：解析抛出「未绑定」 | `LlmProviderResolverTest`（变异靶子） |
| C10 | 铁律 5 往返仍成立：`DecisionMaker` 新增 `providerId` 后，`SdRoundTripTest` 的反射枚举 + `apply(between(base,target),base).equals(target)` 不破 | `SdRoundTripTest`（既有，自动覆盖） |
| C11 | 目录：新命令进 `Shell` 注册面 ⇒ catalog 42→43；`McpCoverageTest` 逐类真载荷 + `SimosToolsTest` 强判据（注册面 == 实现面）不破 | `McpCoverageTest` + `SimosToolsTest` |
| C12 | `CatalogTool.PAYLOAD_HINTS` 含新 type（缺项**构造期抛 ⇒ app 起不来**，unit-ext T10 实测教训） | `SimosToolsTest` + `CatalogTool` |
| C13 | GUI：`GET /api/llm/providers` 返回掩码视图（无密钥值）；`POST /api/llm/providers` upsert；`POST /api/llm/providers/delete`；`POST /api/llm/providers/test` 打本地 stub | `LlmProviderApiTest` |
| C14 | GUI：`POST /api/sd/set-decision-maker-provider` 固定类型 `sd.SetDecisionMakerProvider`，落 revision（与 `/api/sd/start-decision` 同制） | `LlmProviderApiTest` |
| C15 | 前端：决策模式**第三个子页**「Provider 配置」；`decisionSubpageVisibility` 三键恰一真、未知值全假（fail-closed） | `provider-config.test.cjs` |
| C16 | 前端：provider 表单 / 绑定表单的纯函数（解析、校验、掩码显示），**不造假**、不把缺值读成默认 | `provider-config.test.cjs` |
| C17 | 前端写路径仍受 allowlist 管：新端点进 `write-allowlist.test.cjs` 的声明集合，且**逐条**有动态调用对表 | `write-allowlist.test.cjs` |
| C18 | 门禁全量 `./mvnw clean verify` rc=0；8/8 `SUCCESS [`；`[ERROR]` 0；`BugInstance size is 0`；前端 `fail=0`（下界自测后同改两处） | 关账报告 |
| C19 | 每道新护栏各配**故意违规**变异轮（密钥入日志 / 未绑定静默兜底 / 绑定不落 revision / provider 不存在静默兜底 各一） | 证据目录 mutants/ |

**边界（诚实）**：
- `agentlib-mosire` 是**外部依赖、不在本仓** ⇒ 审批/传输路径的深层行为**未核**（沿用 D 阶段盲区标注）。
- LLM 端点**真网络**不测：测试一律打**本地 stub HTTP**。

## §一 数据模型

### §1.1 sd 侧：`DecisionMaker` + `providerId`（铁律 3 的边界）

```java
public record DecisionMaker(
    DecisionMakerId id,
    Affiliation affiliation,
    Set<String> allowedTools,
    ViewScope viewScope,
    long decisionCadenceTicks,
    Optional<String> providerId) { … }   // ★ 新增第 6 组件
```

- `providerId` **只是基础设施引用**（一个不透明字符串），sd **不解释它**、**不校验它是否存在**——注册表在
  app，sd 看不见（铁律 3 的结构化）。存在性在**使用期**（§五）强制。
- 保留 5 参构造器（`providerId = Optional.empty()`）以免动 11 处既有构造点；★ **`SetViewScopeHandler`
  例外**：它**重建** `DecisionMaker`，必须显式带上 `existing.providerId()`（否则配权会静默丢绑定——已识别，写成变异靶子）。
- `providerId` 若存在则必须**非空白**（构造期校验）。

### §1.2 命令 `sd.SetDecisionMakerProvider`

```json
{"decisionMakerId":"dm1","providerId":"p-openai"}
```

- 处理器：`SetDecisionMakerProviderHandler`（`type()` = `sd.SetDecisionMakerProvider`）。
- 拒绝：`decisionMakerId` 不存在；载荷缺字段 / `providerId` 非字符串或空白。
- **不拒绝**「providerId 在注册表里不存在」——模块看不见注册表（§五 使用期强制）。★ 这是**有意**的边界，写进类注。

### §1.3 app 侧：`LlmProvider` / `SecretRef`

```java
public record LlmProvider(String id, String baseUrl, String model, SecretRef apiKeyRef, Duration timeout)
public record SecretRef(Kind kind, String ref)   // Kind = ENV | FILE
```

- `id` / `baseUrl` / `model` 非空白；`timeout` 正、有上界（如 ≤ 5 分钟）。
- `SecretRef.ENV` 的 `ref` = 环境变量名；`SecretRef.FILE` 的 `ref` = 密钥文件路径。

## §二 Provider 注册表（app 基础设施）

`io.mosire.simos.app.llm.LlmProviderRegistry`：

- 落盘：`<store>/llm-providers.json`，形状 `{"version":1,"providers":[…]}`（列表按 id 字典序）。
- API：`list()` / `find(id)` / `upsert(provider)`（写盘）/ `delete(id)`（写盘）/ `resolveSecret(ref)` /
  `view(provider)`（掩码）/ `providerView()`。
- **写盘是 app 基础设施操作**（不是世界写）：不落 revision、不进 `modes.js` 命令白名单；但它**确是一条 app 写路径**，
  在报告里显式记账（与 `bootstrapGenesis` 同类）。
- `resolveSecret`：
  - ENV ⇒ `env.get(name)`（构造器可注入 `Map<String,String>` 供测试）；
  - FILE ⇒ `Files.readString(path)`，trim；
  - ★ **日志只写 `kind + ref + 长度`**（`"读取密钥引用 kind=FILE path=/… length=42"`），**绝不写值**；
  - 缺失 ⇒ 抛 `IllegalStateException("密钥引用不可解析: kind=… ref=…")`（消息**只有引用名**，无值）。
- `view`：`{id, baseUrl, model, timeoutMs, apiKeyRef:{kind,ref}, secretResolvable:boolean}`——**无值**。

## §三 使用期解析与降级（★ 判据 C8 / C9 / 决策 3）

`io.mosire.simos.app.llm.LlmProviderResolver`：

```
llmClientFor(String providerId):
  providerId == null || blank ⇒ throw IllegalStateException("决策人未绑定 LLM provider（providerId 为空）")
  provider = registry.find(providerId)
     orElseThrow ⇒ IllegalStateException("绑定的 LLM provider 不存在: " + providerId)   // ★ 不静默兜 default
  return new HttpLlmClient(provider.baseUrl(), provider.model(),
        () -> registry.resolveSecret(provider.apiKeyRef()).orElseThrow(…), provider.timeout(), httpClient)

adjudicatorFor(DecisionMaker maker):  new LlmDecisionAdjudicator(llmClientFor(maker.providerId().orElse(null)))
```

- 「回放时 provider 不存在」= 回放后拿到的 `DecisionMaker.providerId` 在当前注册表里查无 ⇒ 抛出**点名 id** 的
  异常（由调用方决定是报错还是降级；`LlmDecisionAdjudicator` 会把 `LlmClient` 的异常折成 `Judgement.Failed`，
  满足 N13，**但绝不是静默用别的 provider 顶上**）。
- ★ **降级 = 明确失败，不是换一个能用的**。C8/C9 的变异靶子：把 `orElseThrow` 改成「回落到第一个 provider」⇒ 应红。

## §四 真客户端 `HttpLlmClient`

`io.mosire.simos.app.llm.HttpLlmClient implements LlmClient`：

- 构造器 `(baseUrl, model, Supplier<String> apiKey, Duration timeout, HttpClient http)`——★ **全可注入**
  （`HttpClient` 与 `apiKey` 供应器都可替身；测试仍可用 fake `LlmClient`）。
- `complete(LlmRequest)`：`POST {baseUrl}/v1/chat/completions`，头 `Authorization: Bearer <key>`
  （key 由供应器**调用时**取，不存字段），体 `{model, messages:[{role:"system",…},{role:"user",…}], temperature:0}`；
  解析 `choices[0].message.content`。
- 非 2xx / 坏 JSON / 空 content ⇒ 抛 `IllegalStateException`（消息含状态码，**不含 key**）。
- ★ **日志绝不打印 key / `Authorization` 头**。

## §五 GUI 端点与配置页

### §5.1 端点（`GuiServer`；provider 面 = app 基础设施，命令面 = 世界写）

| 方法 | 路径 | 语义 |
|---|---|---|
| GET | `/api/llm/providers` | 掩码列表 `{providers:[…]}`（无密钥值） |
| POST | `/api/llm/providers` | upsert（体 = provider 字段） |
| POST | `/api/llm/providers/delete` | 删（体 `{id}`） |
| POST | `/api/llm/providers/test` | 测试连接（体 `{id}`）；**服务端**用该 provider 打一次 `complete`，回 `{ok, detail}`；失败回 `{ok:false, detail:…}`（**不 500**，便于前端显示） |
| POST | `/api/sd/set-decision-maker-provider` | ★ **世界写**：固定 `sd.SetDecisionMakerProvider`，落 revision（与 `/api/sd/start-decision` 同制） |

- `/api/llm/*` 带 `as=` ⇒ 显式拒绝（fail-closed，与 GM 工具使用面同口径）。
- 注册表缺席（未配置）⇒ 503（不静默 200 空表）。Shell 装配时**恒注入**，故线上不缺席。

### §5.2 配置页（决策模式第三子页）

- `panels.js`：`DECISION_SUBPAGES` 加 `{id:"provider", label:"Provider 配置"}`；`decisionSubpageVisibility`
  改三键（`view`/`approval`/`provider`），未知值仍**全假**。
- `index.html`：第三个 radio + 左栏 `data-decision-subpage="provider"` 容器（provider 表单 + 列表 + 绑定表单）。
- `api.js`：`llmProviders()` / `saveLlmProvider(p)` / `deleteLlmProvider(id)` / `testLlmProvider(id)` /
  `setDecisionMakerProvider(branch, expectedRevision, decisionMakerId, providerId)`。
- 纯函数（`panels.js`，node 可测）：`providerFormToPayload(form)`（校验 + 归一）、`providerFields(p)`
  （掩码显示，`secretResolvable` 三态不造假）、`providerBindingPayload(...)`。

## §六 门禁与变异

- **门禁**：`./mvnw clean verify` rc=0；**8/8 `SUCCESS [`**；`[ERROR]` 0；`BugInstance size is 0` ×7；前端
  `tests=N pass=N fail=0`（下界**自测后同改** `run-gate.cjs` + `gate-contract.test.cjs` 两处 + 新文件进
  `REQUIRED_FILES`）。
- **写路径 allowlist**：`write-allowlist.test.cjs` 的声明集合与运行期对表**逐条更新**（新增 4 条 POST；
  断言数从 5 → 9，**不是恒真**：每条新端点都进动态调用对表）。
- **变异九道门禁**，靶子至少：
  - m1 `resolveSecret` 把值写进日志/异常 ⇒ C2/C3 红；
  - m2 `llmClientFor` 对未绑定/查无 provider 静默回落第一个 ⇒ C8/C9 红；
  - m3 `SetDecisionMakerProviderHandler` 只返回 `Applied` 但 `SdChangeSet` 不含 providerId（等价于"绑定不落 revision"）
    ⇒ C7 红；
  - m4 `SetViewScopeHandler` 重建时不带 `providerId` ⇒ C7 的一个子断言（配权不丢绑定）红；
  - m5 `LlmProvider.view()` 回显密钥值 ⇒ C2/C13 红；
  - m6 `HttpLlmClient` 出错时把响应体（可能含回显密钥）塞进异常 ⇒ C6 的"消息不含值"红；
  - m7 `decisionSubpageVisibility` 未知值兜成 view ⇒ C15 红；
  - m8 `providerFormToPayload` 把缺 baseUrl 读成默认 ⇒ C16 红；
  - m9 `CatalogTool.PAYLOAD_HINTS` 缺项被 `getOrDefault` 兜住 ⇒ C12 红。

## §七 与既有实现的牵动（显式记账）

- `McpCoverageTest` / `SimosToolsTest`：type 清单 42→43、字数/head 断言随动、扫描上限 42→43。**这不是"改恒真"，
  是注册面真实增长**。
- `decision-mode.test.cjs`：`subpage-ids-and-labels` 与 `subpage-visibility-is-mutually-exclusive` 从 2 子页
  更新为 3 子页（语义更新，非削弱）。
- `write-allowlist.test.cjs`：声明集合 + 动态对表更新（见 §六）。
- `RedactionApiTest` / `RedactingQueryServiceTest` / 其它 `new DecisionMaker(...)` 构造点：5 参重载保住，不动。

## §八 遗留 / 未做（转下，不读作已实现）

1. **`AdjudicatorRunner` 未接进 `Shell`**（既有事实）：本单只交付解析 + 真客户端 + 绑定；`AdvanceTime` 时**不自动跑 LLM**。
2. **无「解绑」命令**：v1 只支持绑定非空 providerId；解绑需另裁（`providerId=null` 的语义）。
3. **provider 注册表不进 revision**：它是基础设施，回放时以**当前**表为准 ⇒ 「同绑定的两个 revision 在不同时刻回放可能解析到不同 provider」是**有意**的（世界事实 vs 基础设施的边界）。
4. `agentlib-mosire` 外部依赖盲区：审批/传输路径未核。
5. provider **真网络**未测（本地 stub 之外）。
6. 配置文件**无并发写锁**（单进程 GUI 写；多进程不保证）。
