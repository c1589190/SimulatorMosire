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

---

## 6. ★★ B3 试点已完成：`simos-map` 15 个工具下放（模式已验证，其余域照抄）

**规模**：`git mv` 15 个 `Map*` 工具 → `io.mosire.simos.map.tools.{read,write}`（app 侧 `Map*Tool` **清零**，
map 侧 18 个文件含基类/助手）；`simos-map/src/main` 对 `core`/`app` 的 import **= 0**；
`dependency:tree` 的 `io.mosire` 只剩 `simos-util` + `simos-ga-spi`（**无 core / 无 app / 无领域模块**）。

### 6.1 新增的接缝（其余域可直接复用）

| 接缝 | 落点 | app 侧实现 |
|---|---|---|
| `ToolReadPort`（`stateAt`/`facets`，**值参 branch/revision**） | **ga-spi** | `app.tools.QueryServiceToolReadPort`（通用） |
| `ToolResults`（`json`/`ok`/`stateRef`/`committedView`/`fold(Outcome)`） | **ga-spi** | 纯函数（无实现） |
| `ToolWritePort.submitWithReceipt` + `Receipt`（★ 唯一契约改动） | **ga-spi** | `app.tools.CoreSimosToolWritePort` |
| `MapToolViews` / `MapToolPerspective` / `MapPathPort` / `MapRenderPort`（签名含 `GameMap`/`HexCoord`/`Region` ⇒ 放不进 ga-spi） | **simos-map 自己声明** | `app.tools.map.App*`，逐条**转调既有唯一实现** ⇒ 输出逐字不变 |

### 6.2 偏离与裁定

- **D1（已追认）** `ToolWritePort` 加 `Receipt`/`submitWithReceipt`：原端口只回 `Outcome` ⇒ 工具拿不到自己命令的真实
  `commandId`，而结果载荷里有它 ⇒ 不改就会"报出去的 id ≠ 落盘信封 id"。改动时全仓无实现类，面为零。
- **D3（已裁定）** `agentlib-mosire` 仍在 `simos-map` 的**禁列**里，但工具所需 agentlib 类型走 `ga-spi` **传递**引入 ——
  因 `searchTransitive=false`，禁列只拦**直接声明** ⇒ **保留现状**（"域模块不许直接声明 agentlib"这条纪律没破）。
- **③（已裁定）** `AppWritePathGuardTest`（R1 禁直连存储）**不扩扫描面**到 `simos-map/.../map/tools`：
  被搬走的工具**结构上不可能**再直连存储（`simos-map` 的 enforcer 禁 `core`）⇒ 扩扫 = 重复围栏。
- **★ 留下的观察**：`simos.map.path` 的实体是 **unit 域**（寻路/位置），本批按设计书 §4.3 留 `map` + 用 `MapPathPort` 解边界；
  若要改归 `simos-unit`，是**另一次归属裁定**（本批不动）。

### 6.3 测试迁移（C2）

3 个测试文件改到全绿：`SimosToolsTest`（import 搬家 + 9 处构造改吃注入端口 + `mapOverlaps` 的标记判据改成
**app 标记 ∪ 契约层标记**，与生产 `SimosToolSource.java:1070` 的并集判据对齐 ⇒ 不是放宽）、
`RedactingQueryServiceTest`、`GmToolUsageApiTest`（后者实际在 `app/gui/`，报告里曾写成 `app/gm/`）。
★ **两条"扫描面"计数断言（70 / 176）字面量一字未改**：实测"下放只移动落点、不增减工具"⇒ 只把**扫描根**
从 app 单根扩成 app + `simos-map` 两根（抽成命名助手，注明"下一个域下放时在此加一行"），**绊线判别力保持**；
**变异自证**：摘掉 map 根 ⇒ `Expected size: 70 but was: 62` / `176 but was: 161` 当场红，还原后 md5 逐字节一致。

### 6.4 验收

| 门禁 | 结果 |
|---|---|
| `-q -DskipTests compile` | rc=0 |
| `-DskipTests validate` | rc=0；`BannedDependencies passed` ×16；18/18 模块 SUCCESS |
| `-pl simos-app -am -DskipTests=false test` | rc=0；**tests=3409 / failures=0 / errors=0 / skipped=5**（413 类） |
| `spotless:check -pl simos-app,simos-map` | rc=0 |
| 负向（map→actor-api） | rc=1，`banned via the exclude/include list`；还原 md5 一致 |
| 负向（map→core） | rc=1，Maven **`cyclic reference`**（map→core→map(test)） |
| `clean verify`（B3 试点后） | **BUILD SUCCESS**，18/18 模块全绿，5:36（2026-10-12 03:38） |

★ **踩坑 1（供下一批）**：聚焦跑 `-pl simos-app` **必须带 `-am`**，否则 `simos-ga` 不在本地库 ⇒ 直接 `DependencyResolutionException`（0.2s 假红）。
★ **踩坑 2（本批真踩到）**：B3 试点只跑了 `compile`/`validate`/`test`，**没跑 `verify`** ⇒ 直到终审才发现 `spotbugs:check @ simos-map`
**3 条 finding**（`EI_EXPOSE_REP`/`EI_EXPOSE_REP2` 在 `MapPathPort.Outcome.Reachable`、`CT_CONSTRUCTOR_THROW` 在 map 的窄写基类）。
处置（**下次下放域必须同样处理**）：① 记录类型的有序集合一律**保序冻结在赋值处**（`Collections.unmodifiableList(new ArrayList<>(…))`，
**禁用** `List.copyOf`/`Set.copyOf`——迭代序不是内容的纯函数，本仓明文禁用）；② `abstract` 基类构造器只做 `requireNonNull` 时，
用仓内既有的 `@SuppressFBWarnings(value="CT_CONSTRUCTOR_THROW", …)`（同款见 `simos-app/.../AbstractHouseholdGmTool:87`），
并**给该域补 `spotbugs-annotations`（provided、4.10.4）**——`simos-map` 原先没声明它（11 个模块早有先例）。
修复后 `spotbugs @ simos-map` 3 → **0**，全仓终审 BUILD SUCCESS。

### 6.5 下一个域（照抄试点的模板）

1. `simos-map/pom.xml` 只加 `ga-spi` 一条依赖 + 把它从禁列除名（**不改其余禁列、不加 `<includes>`**）；
2. 工具搬进 `<域>/src/main/java/.../<域>/tools/{read,write}`，写侧继承域内 `AbstractNarrowWriteTool`（吃 `ToolWritePort`）；
3. 域内**声明**自己需要的端口（签名含域类型时放不进 ga-spi），app 侧实现**逐条转调既有唯一实现**；
4. 权限围栏（可见性谓词）**不抄第二份** —— 域声明端口、app 转调 `ToolSupport`；
5. app 侧：`SimosToolSource` 的 new 处换成注入 + 该域 `App*` 适配器；测试扫描根 +1 行、计数不动；
6. 门禁：`compile` → `validate`（看 `BannedDependencies passed` 次数）→ 聚焦测试 → `clean verify`。

---

## 7. ★★★ B3 全部完成：七批工具下放（2026-10-12/13）

用户 2026-10-12：「**我认为应该把各个工具的实现都下放到各个具体的功能模块**」「**app只负责组装**」
→ 后续追令：「**一口气做完**」。

| 批 | 提交 | 域 | 工具数 | 关键证据 |
|---|---|---|---|---|
| 试点 | `f5eeb2c3` | `simos-map` | 15 | `-am verify` 全绿；spotbugs 3→0（保序冻结 + 仓内同款 suppress） |
| 2 | `d4bbde12` | `simos-army` | 12 | 跨切片组合工具照样下放；`ArmyCodec` 规模敏感误报经干净副本对照实验后精确豁免 |
| 3/4 | `f9438461` | `simos-economy` + `simos-social` | 15 + 12 | `SocialHouseholdsTool` 如实留 app（其实现是 1459 行跨 actor/unit 的聚合服务） |
| 5 | `5b788b4b` | `simos-unit` | 36 | 最大域；spotbugs 零 finding 零豁免；`RegionAllocations` 留 app（3 个 gov 调用点） |
| 6 | `ab7a7829` | `simos-gov` | 36 + `ProvinceDivider` | economy 政策值类型用**全字段镜像记录**（少镜像一字段会静默变 noop） |
| 7 | `d8714818` | `simos-ga` | 49 + 工具运作三件 | `SimosToolSource` 留 app（组合根，搬即环）；抽出 `GaToolSource`+`ToolBuckets` |
| 测试 | 随 §7.1 提交 | 全仓测试迁移 | 30 文件 | 3409/0/0/5 与基线逐数一致；两条扫描面断言经变异自证 |

**总量**：领域模块自带工具类 = map 19 / army 12 / economy 20 / social 17 / unit 39 / gov 32 / ga 63；
**app 侧只剩 20 个真工具**（系统/核心面 + 创世三件套 + `SocialHouseholdsTool` + `AdjudicateTickTool` + `VoidAdjudicationTool`），
全部按设计书 §4.3/G-5 有据留 app。

### 7.1 收尾时抓出并修掉的两处（都在生产代码，如实记）

1. **`SimosToolSource:556` 死局部量 `gaReads`**（`DLS_DEAD_LOCAL_STORE`, High）：ga 批在 ga 模块里删了 3 个 `URF_UNREAD_FIELD`，
   **漏删装配器里同一处清理的另一半**；ga 批的 `-pl simos-ga -am verify` 不含 `simos-app` ⇒ 直到全仓 verify 才暴露。
   处置：删该行（与 ga 批"不留死参数、不豁免"的口径一致）。
2. **修 1 时吞掉一个换行**（把代码行与紧随的注释并成一行、行尾 8 空格、长度 147 > 100）⇒ 会被 `spotless:check` 抓。
   处置：拆回两行（纯格式、零行为）。★ 教训：**"删一行"的 edit 也要当心吃掉邻近换行**；改完立刻 `sed -n` 目视。

### 7.2 沉淀为规则的（后续域/后续批照抄）

1. 域内**声明端口**、app **逐条转调既有唯一实现**（权限围栏/裁剪/署名**绝不抄第二份**）；
2. 有序集合**保序冻结在赋值处**（`Collections.unmodifiableList(new ArrayList<>(…))`；**禁** `List.copyOf`/`Set.copyOf`）；
3. **SpotBugs 误报**：先在 `git archive`/`worktree` 干净副本做**对照实验**定位 ⇒ 精确位置豁免 + 记账；
   落新代码的 finding **改代码**（保序冻结/删无用字段），**豁免不是第一选择**；
4. 适配器落 `app.tools.write` 或 `app.tools.<域>`（委托人**包内可见** ⇒ 适配器同包，不为一次下放放宽既有类可见性）；
5. **跨切片组合工具照样下放**，走注入写口提交多条命令；
6. **搬不动的如实上报**（`SocialHouseholdsTool`/`RegionAllocations`/`AdjudicateTickTool`/`VoidAdjudicationTool`），
   **不硬搬、不造"域里只剩 schema"的假接缝**；
7. **每批跑 `-pl <域> -am verify`（含 spotbugs）** —— 只跑 compile/validate 会漏掉 verify 阶段的门禁（试点教训）。

### 7.2.1 终审（控制方亲跑，冻结树上）

`tools/mvn-lock.sh clean verify` ⇒ **BUILD SUCCESS，18/18 模块，5:57**（2026-10-12 18:17，含 spotless / spotbugs /
前端门禁 / shade 守卫）；**surefire 合计 3409 / 0 失败 / 0 错误 / 5 跳过**（跳过 = 既有真 LLM 门控）；
前端门禁 **412/412**；spotbugs **18 模块 `BugInstance size is 0`**（含修掉死变量后的 `simos-app`）。
**已提交并推送**：`0d23489b`（`main` = `origin/main`，0 未推送）。

### 7.3 仍未做（下一步）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **enforcer 闭合补列** | `ga-spi` 只进了 5 个最底层模块的禁列、`ga` 进了 15 个；social/unit/sd/core/actor/economy/gov/army 等**仍缺这两条**（口径 = 全仓 − 自己 − 允许面） |
| 2 | **B4 `roles()` 收口** | `SimosTool`/`roles()` 尚未被任何工具实现；两个桶与 `DecisionCallerFactory.WHITELIST` 仍是手抄（设计书 §4.4） |
| 3 | **`simos-ga`/`simos-ga-spi` 无 `src/test`** | 其工具与新接缝零测试覆盖（既有测试全在 app） |
| 4 | 外交类型迁 gov、`AdjudicateTickTool`/`VoidAdjudicationTool` 的归属 | 均属后续批（前者要先迁 `Nation`/关系/事件类型） |

---

## 8. ★★ B3 收尾之二：enforcer 补列（`simos-ga-spi` 的真缺口，已关闭）

**怎么发现的**（逐模块算"应禁 vs 实禁"，不是靠肉眼）：写了个脚本对 16 个设 enforcer 的模块
逐条算 `应禁 = 全仓模块 − 自己 − 允许面 − ledger留痕`，与实禁做差集 ⇒ **只有 4 个模块缺 `ga-spi`**。

| 模块 | 缺什么 | 为什么之前漏了 |
|---|---|---|
| `simos-actor` / `simos-core` / `simos-economy-api` / `simos-sd` | `io.mosire:simos-ga-spi` 一条 | 冻结批按 I-F4 只给 **util/map/calendar/social-api/actor-api** 五个最底层模块补了它；**其余模块当时没补**，B1/B2a/B3 又各自只动自己的允许面 ⇒ 长期没合上 |

**口径（写死，后续补列照此判）**：`ga-spi` 的禁列 = **"谁直接声明谁除外"** ——
`army / economy / gov / map / social / unit` 六个域模块 + `ga` 自己**声明**了它 ⇒ 必须**除名**（写注释说明）；
其余模块（不声明、只可能传递可见）**一律列入**。`searchTransitive=false` 保证"列入"不会与传递链冲突。

**证据**：
- `tools/mvn-lock.sh -DskipTests validate` ⇒ **rc=0**、`BannedDependencies passed` **×16**、BUILD SUCCESS；
- **负向验证**：给 `simos-sd` 注入 `<dependency>simos-ga-spi</dependency>` ⇒ **rc=1**，原文
  `Failed to execute goal … maven-enforcer-plugin … (enforce-module-boundaries) on project simos-sd:` +
  `io.mosire:simos-ga-spi:jar:0.1.0-SNAPSHOT <--- banned via the exclude/include list`；
  还原后 `md5sum simos-sd/pom.xml` = `97fa84dd45708b841510c570db972835` **前后逐字节一致**。
- ★ 脚本坑（如实记）：第一版只插了注释、**漏插 `<exclude>` 那一行**（`raise SystemExit` 分支外的路径没走到），
  复核时发现"条数没变"才抓到 ⇒ 已补齐并逐个复验条数 +1。**"改完立刻复核条数"这一步不能省**。
