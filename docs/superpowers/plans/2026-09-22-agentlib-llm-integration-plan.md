# AgentLib LLM 对接实现计划（simos 只对接不造轮）

> 设计权威：**用户裁定**「Provider 配置与上下文格式归 AgentLib，simos 只对接」+
> `~/ProjectMosire/安装报告-AgentLib.md` + `~/ProjectMosire/汇报-AgentLib对接需求.md`（D-A~D-D 已裁）。
> 分支：`llm/integrate`（worktree `.claude/worktrees/llmint`）。基线 HEAD：`1b0b4d9`。
> 纪律：worktree 隔离；一次只跑一个 Maven；显式 `git add`；不加 `Co-Authored-By`；不 `mvn install`。

## §〇 对接口径（已定，照此执行）

| # | 裁定 | 落点 |
|---|---|---|
| D-A | 配置存储改用 AgentLib `ConfigStore`（`config.json` 的 `llm.routes.<name>` / `keys.<name>`）；simos 的 `llm-providers.json` **退化为迁移来源 / 缓存** | `AgentLibLlmConfig`（app） |
| D-B | 密钥只 `keys.*`；simos 若确需 ENV/FILE ⇒ **实现 `ApiKeySource` SPI**，不改 AgentLib、不绕 `ConfigStore` 读 env | `AgentLibLlmConfig.simosApiKeySource` |
| D-C | `reasoning_content`：**条件并入 + 独立字段 + 标记**（不是无条件并入） | AgentLib 已实现；simos 侧读 `LlmResponse` |
| D-D | 装哪一侧由 simos 决定 | 对接层只落 `simos-app`，`simos-sd` 不新增依赖 |

## §一 删除与替换清单

| simos 现状 | 处置 |
|---|---|
| `app/llm/HttpLlmClient.java` | ★ **删**（自造 HTTP 客户端） |
| `app/llm/LlmProvider.java` | ★ **删**（重复 `ModelRoute` + 采样/密钥） |
| `app/llm/SecretRef.java` | ★ **删**（`ENV/FILE/LITERAL` 三态 ⇒ 迁成 AgentLib `keys.*` → `ApiKeySource`） |
| `app/llm/LlmProviderRegistry.java` | ★ **删**（自造 JSON 存储） |
| `app/llm/LlmProviderResolver.java` | 保留语义（fail-closed 解析），实现换 AgentLib |
| `sd.LlmClient` / `sd.LlmRequest` | 保留（D 阶段 SPI，**不属造轮**：LlmRequest 的 `breakpoint` 是 N4 可观测要求） |
| `app/sd/AdjudicatorRunner` | 接进 `Shell`（本任务核心之一） |

## §二 任务分解

### T1 — `AgentLibLlmConfig`：AgentLib 配置根 + 读装配 + 写 CRUD + 迁移

1. `FileConfigStore` 建在 `<store>/agentlib/`（`config.json` = `llm.routes.<name>` + `keys.<name>`）。
2. 读：`LlmRouteLoader.availableNames` 枚举（**只列名不校验**）、逐条 `load` + `capabilities`（坏条目
   `ConfigException` 单独捕获、**原样上报**，不静默丢弃）、`LlmRouteAssembler.client` / `provider`。
3. 写：`store.put("llm.routes", name, routeJson, SYSTEM, null, schema)` / `store.remove(...)`；
   `keys.<name>` 同法。SYSTEM 身份（`AgentPermissionSet.system()`），ownerId `null`。
4. 迁移：启动时若 `<store>/llm-providers.json` 在场且 `llm.routes` 为空 ⇒ 迁进 `config.json`。
   `ENV/FILE` ⇒ `keys.<id>` + `credentialsRef="keys.<id>"`；`LITERAL` 同理。
5. 测试 `AgentLibLlmConfigTest`：往返 / 坏条目上报 / 密钥不落盘 / 迁移 / 删除幂等。

### T2 — `SimosApiKeySource`：ENV / FILE 逃生口（SPI，不改 AgentLib）

- 实现 `OpenAICompatibleLlmClient.ApiKeySource`：`keys.<name>` 先查 `ConfigStore`；查不到再按
  `SIMO_LLM_KEY_<NAME>`（env）/ `$store/keys/<name>`（file）解析。
- ★ 密钥纪律：只打 `kind + 名字/路径 + 长度`；异常只点名引用；值不进日志/异常/argv。
- 测试 `SimosApiKeySourceTest`：三态 + 哨兵自证（日志/异常/视图不含值）。

### T3 — `LlmProviderResolver` 换 AgentLib + `AdjudicatorRunner` 接进 `Shell`

1. `LlmProviderResolver(AgentLibLlmConfig, DecisionMaker, LlmResponseSink?)`：
   - `llmClientFor(providerId)`：空 ⇒ 抛「未绑定」；路由表查无 ⇒ 抛**点名 id**「不存在」；命中 ⇒
     `LlmRouteAssembler.client(store, id, SYSTEM)` 包一层 `AdapterLlmClient`（`chat()` → `text()`）。
   - ★ **绝不静默兜底**（C8/C9）。
2. `Shell`：建 `AgentLibLlmConfig` + `LlmProviderResolver`，**注入** `GuiServer`（provider 配置面）；
   `startDecision` 提交成功后 ⇒ `AdjudicatorRunner.run(...)` ⇒ 判决落 revision。
3. `GuiServer`：`/api/llm/providers*`（走 `ConfigStore`）+ `/api/sd/set-decision-maker-provider`；
   `/api/sd/start-decision` 增 `runAdjudication`（默认 true）。
4. 测试：`LlmProviderResolverTest`（fail-closed 靶子）、`StartDecisionAdjudicationApiTest`
   （真 Shell + 假 LLM 客户端 + **本地 stub HTTP**，判决落 revision）、既有 `AdjudicatorRunnerTest` 不破。

### T4 — N13 异常分类：`LlmDecisionAdjudicator` 按 `LlmException.degradable()`

- `catch (LlmException e)`：`degradable()` ⇒ `Judgement.Failed`；否则（`CONFIG`/`CANCELLED`/`INTERNAL`）**重抛**。
- 其它 `RuntimeException`（配置装配 / 密钥缺失 / `ConfigException`）⇒ **重抛**（该炸，不藏故障）。
- 非法输出仍折 `Judgement.Failed`（isAbstention / validate 的 `IllegalArgumentException`）。
- 测试 `AdjudicationTest` 扩：真值表逐 Kind（含 `CONFIG` 必炸）。

### T5 — 前端：provider 配置子页（写 `ConfigStore`）

- `panels.js`：`DECISION_SUBPAGES` 第三项 `provider`；`decisionSubpageVisibility` 三键。
- `api.js`：`llmProviders/saveLlmProvider/deleteLlmProvider/testLlmProvider/setDecisionMakerProvider`。
- `index.html`：第三 radio + `data-decision-subpage="provider"` 容器。
- 测试 `provider-config.test.cjs`；`gate-contract` / `run-gate` 下界两处同改；`write-allowlist` 声明集合更新；
  `decision-mode.test.cjs` 子页 2→3 的语义更新。

### T6 — 门禁 + 变异 + 端到端实测 + 报告

1. `./mvnw clean verify` 前台跑；记**第几次尝试** + 最终绿轮文件名 + 逐模块 `SUCCESS [` + 增量（现场重算）。
2. 变异九道门禁（§三），红点落被保护断言；存活项如实报理由。
3. ★ **端到端实测**：用用户 provider（`deepseek`，`baseUrl=https://api.deepseek.com/v1`）点一次
   「开始决策」⇒ 真 LLM 判决落 revision，贴可核字段（`id`/`model`/`usage`）。
4. 报告 `.superpowers/sdd/2026-09-22-llm-integration/task-final-report.md`（含 **§我未能核实的**）。

## §三 变异靶子（至少）

| # | 变异 | 应红在 |
|---|---|---|
| m1 | `LlmProviderResolver.llmClientFor` 查无 provider ⇒ 静默回落第一条路由 | fail-closed 用例 |
| m2 | 未绑定（providerId 空）⇒ 当成默认路由 | 未绑定用例 |
| m3 | `SimosApiKeySource` 把密钥值写进异常/日志 | 哨兵自证用例 |
| m4 | `LlmDecisionAdjudicator` 对 `CONFIG` 也折 `Failed`（该炸却降级） | N13 真值表用例 |
| m5 | `Executor` 侧静默吞掉非 degradable 异常 | 同 m4 |
| m6 | `AgentLibLlmConfig` 写路由时把密钥写进 `llm.routes`（而非 `keys.*`） | 密钥不落盘用例 |
| m7 | 坏路由被跳过（枚举只列合法的） | "坏条目也看得见"用例 |
| m8 | `decisionSubpageVisibility` 未知值兜成 `view` | 前端 fail-closed 用例 |
| m9 | provider 表单把缺 `baseUrl` 读成默认 | 前端纯函数用例 |

## §四 取代说明（执行期就地校正记这里）

（执行中填。）

1. ★ **T1 的迁移来源扩为两级**：除 `<store>/llm-providers.json` 外，增加**仓库默认配置** `config/llm-providers.json`
   （`AgentLibLlmConfig.DEFAULT_REPO_CONFIG`，优先级更低）。由来：派单点名「见 `config/llm-providers.json`」，而半成品只读 store 内文件。
2. ★ **T4 之外，另修 D 阶段的提示词缺口**：`LlmDecisionAdjudicator` 现在把 `outputSchemaJson` / `commandWhitelistJson`
   一并送进 user 提示词（D 阶段只发简报）——否则真模型无从知道输出字段。
3. ★ **T3 的"判决落 revision"补上简报**：`AdjudicatorRunner` 新增 5 参重载（逐断点简报），`DecisionAdjudicationService`
   构造事实性简报（combat/stages/outcomes，不发明候选）；D 阶段把简报写死 `"{}"` 会让真模型只能弃权。
4. ★ **T3 的请求采样**：`LlmProviderResolver` 判决请求带 `temperature=0` + `maxTokens=4096`（需求 A2）。
5. ★ 门禁最终：`rc=0`、8/8 SUCCESS、`170/369/45/259/179/141/242 = 1405`（基线 `1376`，delta +29）、前端 201/201；
   变异 9 轮全 KILLED。详见 `.superpowers/sdd/2026-09-22-llm-integration/task-final-report.md`。

