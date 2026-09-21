# 任务关账报告：simos 对接 AgentLib LLM 设施（M11′）

> 分支 `llm/integrate`（worktree `.claude/worktrees/llmint`），基线 `1b0b4d9`。
> 设计权威：用户裁定「Provider 配置与上下文格式归 AgentLib，simos 只对接」+
> `~/ProjectMosire/安装报告-AgentLib.md`（§四 官方示例 / §六 约定）+
> `~/ProjectMosire/汇报-AgentLib对接需求.md`（§三 四裁定 / §五 迁移路径 / §七 已知坑）。
> 承接：上一轮（崩溃前）的半成品，已 `stash pop` 恢复；本报告覆盖**续做 + 收口**。

---

## §〇 边界与范围（守住的线）

- **删除自造轮子**：`HttpLlmClient`/`LlmProvider`/`LlmProviderRegistry`/`SecretRef` 已在半成品里删除（`git grep` 全仓无残留）；
  改用 AgentLib 的 `OpenAICompatibleLlmClient` / `ModelRoute` / `ModelProvider` / `ConfigStore` / `LlmRouteAssembler`。
- **sd 里不出现** `baseUrl`/`model`/`key`（provider 只是不透明 id）——沿用已关账的 `sd.SetDecisionMakerProvider`。
- **不改 AgentLib**、不造它的轮子；`simos-sd` 未新增 AgentLib 之外的依赖（对接层只在 `simos-app`）。
- **绝不 `git add -A`**；显式路径 `git add`；未 `mvn install`；提交不加 `Co-Authored-By`。
- **测试不真调外网**（本地 `HttpServer` stub + lambda 假客户端）；**唯一打真外网的是 §二 端到端实测**，已显式标注。

---

## §一 改了什么（含取代说明）

### 半成品（已恢复，未改语义）

| 文件 | 作用 |
|---|---|
| `app/llm/AgentLibLlmConfig.java` | provider 配置的**唯一入口**：读/写 AgentLib `ConfigStore`（`<store>/agentlib/config.json` 的 `llm.routes.*` / `keys.*`），枚举走 `availableNames`（只列名）、装配走 `LlmRouteAssembler` |
| `app/llm/SimosApiKeySource.java` | `ENV`/`FILE` 逃生口（实现 AgentLib 的 `ApiKeySource` SPI，不改 AgentLib） |
| `app/llm/LlmProviderResolver.java` | providerId → AgentLib client（fail-closed：未绑定 / 悬空 / 坏路由都不兜底） |
| `app/sd/DecisionAdjudicationService.java` | 把 `AdjudicatorRunner` 接进壳（「开始决策」真跑判决） |
| `Shell` / `GuiServer` | 装配 + `/api/llm/providers*`、`/api/sd/set-decision-maker-provider`；`start-decision` 增 `adjudication` |
| `webui/api.js` / `index.html` / `panels.js` | 决策模式第三子页「Provider 配置」（CRUD + 测试连接 + 绑定）+ 写面 allowlist |
| `LlmDecisionAdjudicator` | N13 失败降级按 AgentLib 的 `LlmException.degradable()`（**不再 catch RuntimeException 一刀切**） |

### 本轮续做/收口（★ = 取代说明）

| # | 改动 | 为什么 |
|---|---|---|
| ★1 | `AgentLibLlmConfig.migrateLegacyIfPresent` 增加**仓库默认配置兜底**（`config/llm-providers.json`，优先级低于 `<store>/llm-providers.json`） | 派单点名「见 config/llm-providers.json」；原半成品只读 store 内文件 ⇒ 仓库配置无人读 |
| ★2 | `LlmDecisionAdjudicator` 把**输出 schema + 命令白名单**也送进 user 提示词 | D 阶段只发简报、**没把 schema 交给模型** ⇒ 真模型无从知道字段，必然被 schema 判非法（实测真 provider 首轮即证：给了 schema 才产出合法 JSON） |
| ★3 | `AdjudicatorRunner` 加 5 参重载（逐断点简报）；`DecisionAdjudicationService` 构造**事实性简报**（combat/stages/outcomes，不发明候选） | D 阶段把简报硬编码成 `"{}"` ⇒ 真模型无上下文，只能弃权，**Verdict 永不落 revision**（本任务要求「判决 → Verdict 落 revision」） |
| ★4 | `LlmProviderResolver.llmClientFor` 判决请求带 `temperature=0` + `maxTokens=4096` | 需求 A2：判决要可复现；推理模型要给足思维链空间 |
| ★5 | `LlmProviderResolver` 加观测装饰器：`chat()` 后记 `model/inputTokens/outputTokens/reasoning` | 让「开始决策」之后能核对真 provider 确实被调过、拿到用量 |
| ★6 | `@SuppressFBWarnings` 三处（`Shell.llmConfig()` / `AgentLibLlmConfig.configStore()` / `GuiServer` 全参构造）+ 删掉 sd 里 `catch (RuntimeException e) { throw e; }` | 前者是仓库既有的「有意暴露」豁免惯例；后者是多余 catch-rethrow，SpotBugs `THROWS_METHOD_THROWS_RUNTIMEEXCEPTION` 判红，删掉后语义不变（未捕获的运行时异常照常冒泡） |

### 新增测试

- `AgentLibLlmConfigTest.repoDefaultConfigSeedsAnEmptyStoreButLosesToTheStoreOverride`（★1）
- `AdjudicationTest.promptCarriesTheOutputSchemaAndCommandWhitelistToTheModel`（★2）
- `AdjudicationEndToEndTest` 断言请求体含 `"temperature":0` / `"max_tokens":4096`（★4）
- `SimosApiKeySourceTest.resolvedKeyValueNeverLeaksIntoLogs`（log4j2 appender 捕获，给 m3 牙齿；前提断言「捕获非空」）

---

## §二 端到端实测（★ **唯一打真外网的一步，显式标注**）

provider：`config/llm-providers.json` 的 `mosire-flash`（`baseUrl=http://121.40.130.178:3000/v1`，按 AgentLib 约定含 `/v1`）。
启动：`java -jar simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar --store /tmp/llm-e2e-run2 --demo --gui-port 5821 ...`
（★ 该 jar 是 **worktree** 产物，与控制器拉起的 5817 所用的 **主树** jar 是不同文件；`clean verify` 只重写 worktree 的，**未碰 5817**。）

### 2.1 直连 provider 的原始字段（显式标注：**直连 provider，非经 app 的 LlmClient**）

证据 `e2e/probe-d6.sse` + `e2e/probe-evidence.md`（`curl --noproxy '*'`）：
- `id`：`a793c402-b743-4160-ae48-bd32e444f0d6`
- `model`：`deepseek-flash`
- `usage`：`prompt_tokens=82, completion_tokens=476, total_tokens=558, reasoning_tokens=421`

### 2.2 经 app 的「开始决策」链路（**真 provider**）

证据 `e2e/e2e-output.txt` + `e2e/app.log`：
- `map.CreateRegion` → `sd.CreateNation` → `sd.CreateDecisionMaker(dm-dashu)` → `sd.CreateCombat(c1)` → `sd.AddCombatStage(s1, o-win/o-lose)` → `sd.SetDecisionMakerProvider(dm-dashu → mosire-flash)`：head `1→7`。
- `POST /api/sd/start-decision` → `sd.StartDecision` 落 `main@8`；随后**真 LLM 判决**：
  - 日志 `LLM 判决响应 model=deepseek-flash inputTokens=271 outputTokens=508 reasoning=SEPARATE`（D1/D3 合并调用）
  - `sd.SubmitVerdict commandId=adjudicated:D1:8` 落 `main@9`（**Verdict 真落 revision**）
  - 日志 `LLM 判决响应 model=deepseek-flash inputTokens=259 outputTokens=1507 reasoning=SEPARATE`（D6）
- `GET /api/sd/verdicts` 读回：
  ```json
  {"id":"adjudicated:D1:8","breakpoint":"D1","subject":"sd:combat.c1","atRevision":8,
   "payload":"{\"breakpoint\":\"D1\",\"stageId\":\"s1\",\"selectedOutcomeId\":\"o-win\",...}",
   "meta":{"model":"llm","promptVersion":"v1","inputBriefDigest":"digest:D1"}}
  ```
- D6 断点**弃权**（真模型理由：「participants 为空…无法在 o-win/o-lose 中作出胜负裁决」）——如实记，不伪装成判决。

**可核字段**：`id`（直连探针）、`model`（两处路径都是 `deepseek-flash`）、`usage`（app 侧 `inputTokens/outputTokens`；直连侧 `prompt/completion/total`）。

---

## §三 门禁（★ 最终绿轮 = `logs/clean-verify-final.log`，md5 `a8f602a8bb42dcef08982895aba138c6`）

- `./mvnw clean verify`：**rc=0**、**8/8 `SUCCESS [`**（`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp` + parent）、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=201 pass=201 fail=0`。
- **模块判据（现场取汇总行）**：`170/369/45/259/179/141/242 = 1405`。
- **基线现场重算**（detached worktree `1b0b4d9` 真跑，非抄文档）：`170/369/45/259/179/138/216 = 1376`（`logs/baseline-1b0b4d9.log`）。
  - **delta 干净**：`sd 138→141`（+3 = `AdjudicationTest` 新增 3）+ `app 216→242`（+26 = 4 个新用例类 25 + `SimosApiKeySourceTest` 1）；其余模块**逐值不变**。
- **尝试次数**：**第 4 次**（attempt1 = sd SpotBugs 1 条、attempt2 = app SpotBugs 3 条，均红；attempt3 = 变异轮前的中间绿；**attempt4 = 变异轮 + 补测之后的最终绿**）。
- ★ **冻结字节自证**：变异装置每轮记录的 `orig` md5 与最终绿轮上的当前文件**逐字节相同**（见 `logs/FINAL-GREEN.txt`）⇒ 最终绿跑的就是变异体所基于的那份字节。
- ★ **未碰 5817**：主树 `/home/cna/SimulatorMosire/simos-app/target/...shaded.jar` 与 worktree 产物是不同文件（`/proc/2485/cwd` = 主树）；本任务全程在 worktree 构建。

---

## §四 变异（9 轮 **全 KILLED / 0 存活**，`restore=OK`、`comp=0`）

装置 `mutants/run-mutants.sh` + `mutants/mutate.py`（每轮：字节确实不同 → `COMPILATION ERROR=0` → 红点落被保护断言 → `cp` 逐字节还原 + 复核 md5）。摘要 `mutants/summary.txt`，逐轮日志 `mutants/logs/*.log`。

| 轮 | 变异 | 红点（被保护断言） |
|---|---|---|
| `m1-fail-closed-not-found` | 拆掉「路由表里必须有该 id」守卫 | `LlmProviderResolverTest.danglingProviderIdIsRejectedWithTheIdNamed`（类型不符 E_NOT_FOUND） |
| `m2-fail-closed-unbound` | 拆掉「未绑定」守卫 | `...unboundDecisionMakerIsRejectedNotSilentlyDefaulted` + `...adjudicatorForUsesTheMakersBoundProvider` |
| `m4-config-degrades` | `CONFIG` 也折 `Failed`（该炸却降级） | `AdjudicationTest.nonDegradableLlmFailuresAreRethrownNotSwallowed` |
| `m-schema-not-sent` | 提示词不发 schema | `AdjudicationTest.promptCarriesTheOutputSchemaAndCommandWhitelistToTheModel` |
| `m6-key-into-routes` | 密钥值写进 `llm.routes` | `AgentLibLlmConfigTest.apiKeyGoesToKeysAndNeverIntoTheRouteEntry`（route JSON 含哨兵） |
| `m7-broken-hidden` | 坏条目被枚举隐藏 | `AgentLibLlmConfigTest.brokenRouteIsStillVisibleWithItsErrorCode` |
| `m-repo-seed-removed` | 不再用仓库默认配置兜底 | `AgentLibLlmConfigTest.repoDefaultConfigSeedsAnEmptyStoreButLosesToTheStoreOverride` |
| `m-sampling-max-tokens` | 请求不再给足 max_tokens | `AdjudicationEndToEndTest.realAgentLibClientProducesAVerdictThatLandsARevision`（body 里 `max_tokens:7`） |
| `m3-key-value-logged` | 取密钥成功时把值打进日志 | `SimosApiKeySourceTest.resolvedKeyValueNeverLeaksIntoLogs`（日志含哨兵） |

★ **无存活项**。★ `m1`/`m2` 的红是「拆掉守卫后异常类型/来源变了」，落点正是那两条 fail-closed 断言——如实记，不修饰。

---

## §五 诚实披露 / 与派单文字的偏差

1. **半成品未留 `HttpLlmClient` 等**：恢复后发现半成品已删干净，故「删自造轮子」一项实为**确认**而非再删。
2. **★2/★3 是对 D 阶段实现的实质补缺**（提示词缺 schema、简报写死 `"{}"`），不是措辞问题；不补则端到端只能得到「弃权/失败」，`Verdict` 永不落 revision。
3. **`config/llm-providers.json` 携带真 key 并入版本库**：用户已裁「仓库没关系（私人仓库）」，且派单明示**只此一处**允许。该文件是本轮**唯一**含密钥值的入库文件。
   ★ **该文件此前并不存在**（半成品台账 `progress.md:16` 也记「本机不存在」）——我按**控制器先前实测并留档**的 provider（`mosire-flash` / `121.40.130.178:3000/v1`）**重建**了它，并当场直连复测 **HTTP 200** 证实可用（§2.1）。半成品台账里「provider 来自 `ANTHROPIC_BASE_URL`/`ANTHROPIC_AUTH_TOKEN`」是**另一个猜测**（那是本机 Claude Code 的 env，并非本项目 provider），**未采用**。
4. **D6 在实测中弃权**（无 directive/命令上下文）⇒ 只有 D1 的 `Verdict` 落 revision；「D6 走接受分支」未经端到端证明。
5. **`AgentLibLlmConfig` 未做「坏路由整次装配响亮失败」的端到端**：`client(id)` 逐条装配，`provider(store)` 的全有或全无入口未被 simos 生产调用（`ModelProvider` 未接）。
6. **`GuiServer` 的 provider 面未加 Java 端点测试**：前端由 `provider-config.test.cjs`（纯函数 + 端点对表）覆盖；Java 侧只有 `AgentLibLlmConfigTest` 直测门面。
7. **`ConfigStore` 写失败（schema/授权）的 GUI 映射未测**：端点直接冒泡异常，未做 4xx 归一。

## §六 我未能核实的

1. ★ **AgentLib 不暴露 provider 的响应 `id`**（`LlmResponse` 只有 `model`/tokens/reasoning）⇒ 「app 路径贴 `id`」做不到；`id` 只能来自**直连探针**（§2.1，已标注）。
2. **D6 接受分支**未端到端跑通（见 §五.4）。
3. **简报格式是我定的**（无 spec）：只摆 combat 自身数据（id/名/参战/阶段/结局候选），**不发明候选**；若将来 spec 定义简报，需回填。
4. **真 provider 只跑了一轮**（`/tmp/llm-e2e-run2`），未做并发/长文本/限流/超时复现；`reasoning=SEPARATE` 是实测值，未跨供应商验证。
5. **`Verdict` 载荷与 combat 语义的一致性未校验**：`sd.SubmitVerdict` 只冻结，不核对 `stageId`/`selectedOutcomeId` 是否真在阶段/结局表里（模型这次选对了，但无护栏）。
6. **worktree jar 与最终绿字节的关系**：e2e 用的 jar 构建于 §一 生产改动之后、**补测之前**（补测只动 test）⇒ 生产字节同一；但未用最终绿轮**重建的 jar** 再跑一次实测。
7. **前端真浏览器 e2e 未跑**：`provider` 子页只有纯函数 + served HTML 覆盖（本机 Playwright/Chromium 版本限制，见 M8 遗留）。
