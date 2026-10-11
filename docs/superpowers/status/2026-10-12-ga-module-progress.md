# 工具面下放 + `simos-ga`/`ga-spi` 建设：进度台账（2026-10-12 夜）

> **这是什么**：`docs/superpowers/specs/2026-10-12-ga-module-and-tool-descent.md`（约束设计书）的**执行台账**。
> 该设计书定 B1~B4 四批；本文件记**已落地的批次、证据、偏离、下一步**。追加式，不改旧行（§五.4）。
> **权威顺序**：与代码冲突以代码为准（§四）；与设计书冲突以本文件为准（§四.1 最新落盘）。

---

## 0. 一句话现状

**B1（契约层）与 B2a（运行流）已落地并提交；B2b/B3/B4 未做。** 提交序列：
`64259f9d`（B1）→ `257e26fe`（B2a + C1 测试迁移）。

---

## 1. 已落地批次

### B1：`simos-ga-spi` 工具契约模块（提交 `64259f9d`）

| 交付 | 证据 |
|---|---|
| `ToolRole{GM,DM}`（"能被谁用"的唯一拼写点；**审批链不写进枚举**，按装配决定） | `simos-ga-spi/src/main/java/io/mosire/simos/ga/spi/ToolRole.java` |
| `SimosTool extends AgentTool` + `roles()` | `…/ga/spi/SimosTool.java` |
| `GmOnlyRead` 自 app 原样迁入（app 旧文件保留，B4 收口） | `…/ga/spi/GmOnlyRead.java` |
| `ToolWritePort`（替代 162 个工具直连 `CoreSimos`；**结局类型自造以不引 core**） | `…/ga/spi/ToolWritePort.java` |
| `ToolResources`（21 项）/ `ToolSchema`（9 项）—— 自 `ToolSupport` 拆出的**纯助手** | 同上目录 |
| **零依赖违规** | `dependency:tree` 的 `io.mosire` 前缀只有 `simos-util` + `agentlib-mosire`；源码 import 同为 0 领域/0 core |
| 禁列补 `ga-spi` 的 5 个最底层模块 | `util`/`map`/`calendar`/`social-api`/`actor-api`；5 条全部加得进去、零 `<includes>` |

★ **B1 的偏离（已追认）**：D1 端口带 `BranchId`（branch 来自每次调用载荷，实测 `AdvanceTool.java:113-116`）；
D2 `replay` 返回 `SimulationState` 而非 `Optional`（core 用**异常**表达"读不出"，折 empty 会把故障静默成空世界）；
D3 自造 `BatchOutcome` 镜像 core 的 `BatchResult`；D4 一并迁入 5 个 `ResourceManifest` 常量；
D5 **未**实现设计书 §4.4 的 `exported()`（与 `roles()` 语义重叠，会造第二处声明）；D6 父 pom 加 `dependencyManagement`；
D7 三处计数文案同步。
★ **B1 的关键发现（供 B3 写死）**：`submitRestore` / `revisions` **不能**进端口 —— 其载荷与返回
（`core/state/WorldChangeSet`、`core/timeline/RevisionRow`）全是 core 类型，进 `ga-spi` 就破 I-G2。

### B2a：`simos-ga` 推演框架（运行流）（提交 `257e26fe`）

| 交付 | 证据 |
|---|---|
| `git mv` 4 个运行流文件 → `ga.decision`：`DecisionAgentRunner`（回合循环）/ `DecisionRunRegistry` / `DecisionToolDefs` / `DecisionProposable` | `simos-ga/src/main/java/io/mosire/simos/ga/decision/` |
| `LlmToolNames` → `ga.llm`（属"工具运作"的供应商方言面） | `…/ga/llm/LlmToolNames.java` |
| 日志门面 `GaLog`/`GaLogSource`（logger 根 `io.mosire.simos.ga`；`-Dsimos.ga.logLevel`/`traceLevel`；log4j2 已配） | `…/ga/GaLog.java` |
| **永不依赖 app**：三处注入接缝（接口在 ga、实现在 app） | `DecisionToolCaller` ← `app.access.DecisionCallerFactory implements`；`DecisionToolNames` ← `app.decision.AppDecisionToolNames`；`ToolImages` ← `app.decision.DecisionToolImages` |
| 依赖面恰 4 条：util / ga-spi / sd / agentlib | `dependency:tree` 的 `io.mosire` 前缀**零 app** |
| 15 个模块禁列 + `simos-ga`；`simos-app/pom.xml` 显式声明 | 15 条全部加得进去（`searchTransitive=false` 下无例外） |

★ **B2a 的边界（如实）**：持有**域工具实例**或**读世界**的五个类按设计书 §4.5 判据**留 app = 装配**：
`ProposalCatalog`（持有域工具实例）/ `NationOpeningSnapshot`（读世界）/ `DecisionAgentService` / `DecisionTurnFinalizer` /
`DecisionEffectExecutor`；工具运作的另一半（`SimosToolSource` 装配两桶、`GmAutoApproveGate` 审批链）与 §4.3 的
ga 归属工具**留 app**，属 **B2b/B3**。`simos-ga` 现**无 `src/test`**（其运行流的测试必须留 app：依赖 app 的
`DecisionCallerFactory`）。
★ **B2a 的负向验证（4 条，全部还原并复验）**：`ga→app` 与 `sd→ga` 在 **Maven 建图期**报 `cyclic reference`；
`ga→map` 与 `economy→ga` 被 enforcer 拒（`banned via the exclude/include list`）。

### C1：B2a 搬迁后的测试迁移（随 `257e26fe`）

9 个测试文件：7 个是 import / 构造实参 / 已删重载的机械迁移（**零断言改动**）；
额外 2 个是"手抄模块清单必须 == 根 pom `<module>` 集合"的护栏（`EconomyVocabularyGuardTest` / `ArchitectureGuardsTest`），
因 B1/B2a 加模块而红 ⇒ 补 `simos-ga-spi` / `simos-ga`（**断言未削弱**，扫描面变大 = 加法）。

---

## 2. 未做（B2b / B3 / B4）

| 批 | 内容 | 前置（已就绪的接缝） |
|---|---|---|
| **B2b** | 工具运作另一半搬 ga：`SimosToolSource`（装配两桶）、`GmAutoApproveGate`（审批链）、`RecordingToolSource`、`GmToolUsage`、§4.3 表里 ga 归属的工具（审批/决策包族） | `ga-spi.ToolWritePort` 已有接口；`ga` 需新增 `core` 声明（B2a 零 core import，故未声明） |
| **B3** | **域工具下放**：按设计书 §4.3 表把 176 个工具类搬入各域（`unit` 32 / `gov` 30 / `sd` 20 / `map` 15 / `economy` 13 / `social` 8+3 / `army` 6 …），含 `ToolSupport` 三分、窄写口接线替代 `CoreSimos` | `ga-spi.ToolResources`/`ToolSchema` 已就绪；需先给 `ToolWritePort` 写 core 适配器（**在 ga**，非 ga-spi） |
| **B4** | **一处声明收口**：`roles()` 派生两桶 + `DecisionCallerFactory.WHITELIST`；删手抄清单；终审 | B2b/B3 完成 |

★ **B3 的已知拦路（B1/B2a 已核实）**：`submitRestore` / `revisions` 的载荷/返回是 core 类型 ⇒ 用它们的那两个工具
要么留 app，要么经 ga 的适配器暴露。

---

## 3. 遗留观察（未处理，不是缺陷）

1. **两处"部分模块清单"扫描器覆盖有洞**：`simos-map/src/test/.../RepresentationLeakGuardTest.java:41`（扫 5 个模块）、
   `simos-app/src/test/.../tools/SimosToolsTest.java:1830-1844`（扫 8 个模块）**都不含 `ga`/`ga-spi`** ⇒
   B2a 之后 ga 成了这两条护栏的盲区。**今天无实际违规**（实测两片 `src/main` 里 `.terrain()` 直读 = 0、
   词表 token = 0），但覆盖有洞。
2. **`simos-ga` 声明了 `ga-spi` 但本批零 import**（死声明；设计书 §4.1 的允许面，第一处使用落 B2b）。
3. **`simos-ga` 未声明 `core`**：B2a 零 core import，按"按实际 import 增删"处理；core 仍在其允许面（不在禁列）。

---

## 4. 验收记录

| 轮次 | 命令 | 结果 |
|---|---|---|
| B1 | `-q -DskipTests compile` / `-DskipTests validate` | rc=0；`BannedDependencies passed` ×15 |
| B2a + C1 | `-q -DskipTests test-compile` | rc=0（全反应堆 18 模块） |
| B2a + C1 | `-pl simos-app -am -DskipTests=false test` | rc=0；18/18 SUCCESS；**tests=3409 / failures=0 / errors=0 / skipped=5**；前端门禁 412/412 |

★ **终审（控制方亲跑，B2a+C1 提交后）**：`tools/mvn-lock.sh clean verify` → **BUILD SUCCESS**，
**18/18 模块全绿**（含新模块 `GaSpiSimos` / `GaSimos`），**5:37**（spotless / spotbugs / enforcer×16 / 前端门禁 / shaded-jar guard 全跑）。
2026-10-12 03:01:49 完成。

---

## 5. ★★ B3 的前置（本批新核，2026-10-12）：比设计书估的便宜

设计书 §4.2/§9 把"读口"列为 B3 的最大不确定项（98 个工具用 app 的 `QueryService`）。**实测收窄了**：

| 项 | 实测 | 结论 |
|---|---|---|
| 98 个工具实际调的 `QueryService` 方法 | **只有 3 个**：`stateAt(QueryTarget)` **76 次**、`resolve(String, QueryTarget)` 2 次、`facets(String, QueryTarget)` 2 次 | 读口面 = **3 个方法**，不是 11 个 |
| `QueryService.stateAt` 的返回 | **`SimulationState`**（`util.state` 类型；`QueryService.java:96`） | ✅ 读口可抽成 `ga-spi` 的窄接口（只依赖 util），**不引 core、不引 app** |
| `ToolSupport` 的调用热度 | `prop` 632 / `json` 257 / `requiredText` 142 / `optionalText` 140 / `ok` 131 / `optionalLong` 109 / `schema` 107 / `optionalBoolean` 61 / `requireAll` 50 / `requiredLong` 48 —— **绝大多数是纯助手** | ✅ 这些**已在 B1 搬进 `ga-spi`**（`ToolSchema`/`ToolResources`），域工具改用它们即可 |
| 领域取数助手能否搬 `ga-spi` | `hexCoord` / `stateRef` / `unitState` / `socialData` / `sdState` / `gameMap` **都只从 `SimulationState` 按 namespace 取快照**（无 app 依赖） | ✅ 可搬 `ga-spi`（`ToolResources`/新 `ToolReads`） |
| **搬不动的** | ① 可见性谓词（`hexVisible`/`unitVisible`/…，依赖 `ApiViews` + `QueryService`）；② `target(...)`（返回 app 的 `QueryService.QueryTarget`）；③ `economyData(...)`（内部走 `ApiViews.economyData`） | ⇒ 这三族**留 app**；域工具若需要"按坐标查世界"，走**注入的读口**（`QueryTarget` 的构造也须由 app 提供或改为 `(branch, revision)` 值参） |

**⇒ B3 的执行顺序（建议，未派）；★ 适配器落点已定（控制方 2026-10-12 裁定）**：
1. **写口**：**适配器写在本 crate 的 `simos-app`（组合根），不写进 `ga`** —— 依据：搬进域后的工具由 **app 装配**
   （app 是唯一同时认识 `core` 与各域的模块），端口实现必须能被 app 注入工具的构造器；写进 `ga` 既无必要
   （`ga` 看不见域工具）又会把 `ga` 的依赖面撑大。域工具把 `core.submit(...)` 换成**构造期注入的
   `ga-spi.ToolWritePort`**，app 在装配处传 `CoreSimos` 适配器；
2. **读口**：`ga-spi` 新增窄读接口（`stateAt` 三方法面）+ `ToolReads` 助手；域工具改用它（`QueryTarget` 的构造由 app 以值参或注入解决）；
3. **`ToolSupport` 收口**：把已搬 `ga-spi` 的助手从 app 的 `ToolSupport` 删掉、留可见性谓词与 `target`/`economyData`；
4. **按域搬工具**（建议 **先 `simos-map` 15 个做试点**，它是纯读+纯 map 域、依赖面最小），再 unit/gov/social/economy/army/sd/ga。
