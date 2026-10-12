# 全面任务规划：权限反默认 / 逐 hex 比例城市 / 区划语义归 GOV / 战斗归 Army（2026-10-12）

> **这是什么**：用户 2026-10-12 三轮裁定的**可派单任务规划**（两轮共 5 个只读调查的综合结论 + 批次切分）。
> **不是设计书**：批次内的实现架构由实现方出账本（§一.8）。
> **基线**：`main` @ `6592bf54`。**权威**：与代码冲突以代码为准（§四）。
> **用户原话**：见 `docs/superpowers/reports/2026-10-12-hex-ratio-city-gov-semantics-feasibility.md` §0/§8.1/§10.1（逐字照录，§一.8.1）。

---

## 0. 已关闭 / 已定 / 待裁

### 0.1 已关闭
| 项 | 裁定 |
|---|---|
| **工单通用化** | **保持现有**（用户 2026-10-12：「额外的自然语言 reason 没啥必要，保持现有」）⇒ 不新增信封字段、不统一 payload、不建通用 WorkOrder；18 必填 + 17 可选 + 32 工具面原样 |
| **D10「不是比例、是独立池子」** | **作废**（用户「一切以我最新说明为准」）⇒ 城市 = 逐 hex 比例状态 |

### 0.2 已定方向（无需再裁）
| 项 | 内容 |
|---|---|
| 地图逻辑归 map、存储归 core | **已是现状，零工作** |
| 城市 = Map 维护的逐 hex 固定数值 + 改它走标准命令（谁干的 + 为什么） | 落点/命名仍待裁（见 0.3） |
| 权限**反默认**：GM 优先 + 显式 DM 标签 | 用户批评经实测证实（142 注册面 / 51 `GmOnlyCommand`） |
| 战斗（含阶段）归 `simos-army` | = 兑现 **D-012**（「旧 sd 战斗命令族不再作为交战记录的载体，清理另批」） |
| `army` 可以依赖 `gov` | pom 级无环（gov 闭包 = util/map/unit/social/economy-api/ga-spi，不含 army） |
| 对 GOV 的修改走既有命令路径 | = 多条命令同批 `submitBatch`（受 `namespaceOf` 约束，一个 handler 只能回自己模块的变更集） |

### 0.3 ★ 待裁（11 条，分组；**不裁不能开工**）

**甲组·权限反默认（4 条）**
1. 标签**命名**：`DmUsableCommand`（建议）还是照原话 `DMOnly`？★ 默认已仅 GM ⇒ `DMOnly` 是**反义误名**。
2. `GmOnlyCommand`（51 处）**是否同批退役**？反默认后它语义为空；退役最干净，保留则须加"两标记互斥"装配期断言。
3. `sd.` **前缀规则**留不留？（建议**留**：一条 `if` 换一层防自指；删除须同时删 `Shell.java:903-914` 的无条件 add。）
4. **切片 A（等价迁移）与切片 B（35 条收紧）是否分批**？（**强烈建议分批**：`unit.CreateUnit`/`SetComposition` 已在册待裁定，混做会让测试红点无法归因。）

**乙组·城市（3 条）**
5. **"比例"是什么的比例**：用地/强度（⇒ D-025「城市化率是派生量」原样成立）还是人口（⇒ 推翻 D-025）？
6. 逐 hex 属性的**落点形态**：(a) `GameMap` 新组件（120 处 `new GameMap(`）/ (b) 塞 `HexCell`（240 处 `new HexCell(`，**会复活 P1 裁定「HexCell 只剩 height」**）/ (c) overlay Region（零代码但热路径重算 + 污染 RegionIndex/overlaps/管辖目标）。
7. **随仓 `worlds/v17levant.json`（4.2MB 构建输入，每次加载都走 `migrate`）**：migrate 补空段，还是重刷资源？★ §一.11「Alpha 不考虑兼容」是否覆盖**构建输入**（不裁则实现方要么白写迁移、要么踩红 3~4 个 RichWorld 测试）。

**丙组·区划语义归 GOV（3 条）**
8. `NationId` 落点：新建 `simos-gov-api` / 允许 gov→sd 直连 / 下沉 util。
9. **`Affiliation.Nation` 留不留 sd**？它是**权限边界**（`NationScope` 靠它决定国家决策人可见范围）⇒ 控制方判"留 sd"，但与你「国家语义直接搬 GOV」的字面冲突 —— **两条都是你的原话**。
10. `sd:nation.<id>` 地址族改不改 `gov:nation.<id>`？（对外接口 + 已落盘 `infos` 键，**改了回不去**）

**丁组·战斗归 Army（4 条）**
11. **`armies` 组件（军队身份）算不算"战斗"**？它有独立价值（`ArmyScope` **权限边界**、`CreateDecisionMakerHandler`、`SdResolver` 地址）⇒ 留 sd（最小改动）还是搬 army？
12. **D1/D3/D6 判决面的 subject 族**是否从 `sd:combat.*` 改挂 `army:combat.*`？★ `Verdict.subject` 是**持久字段**，`Breakpoints.java:21,84-99` 硬编码"namespace 必须 sd、kind 必须 combat" ⇒ **不能选"不动"**，只能"重挂"或"保留 sd 影子表"。
13. **旧族能力等价物**：`entry/exit` 触发器状态机、`SetStageOutcomeTable` 的"改表"、`LossClass`、`LossRecord.atRevision`、交战级 `finalOutcome`、`sd:combat.<id>.stage.<sid>` 三段地址 —— 逐条"必有等价物 / 允许作废"。
14. **是否现在就给 gov 建命令面**？（gov 今天 **0 个 `CommandHandler`**；`GovState` 只被 tick 多切片提案写。这是"对 GOV 的修改走工单"的前置。）

---

## 1. 批次表

### 批次 0 · 零裁定、零风险（**现在就能做**）
| # | 责任区 | 交付 | 验收判据（含负向） |
|---|---|---|---|
| **0a** | `simos-gov` 的**铁律 5 往返护栏** | 新建 `GovRoundTripTest`（照 `SdRoundTripTest` 形制：反射枚举 `GovState` 组件 + `hasSize(2)` + 排除集为空 + `between→apply` 逐字段重建） | 正向：新测试绿。**负向：往 `GovState` 加组件而不改 `GovChangeSet` ⇒ 当场红（今天必绿）** ← 这是全仓**唯一**缺失的往返护栏，必须先补 |
| **0b** | `army → gov` **pom 放行** | `simos-army/pom.xml:92` 删 `<exclude>simos-gov</exclude>` + 加 `<dependency>` + 改 `:19-24` 注释与 `:115` message；**`simos-gov/pom.xml:107` 的 `simos-army` 禁列保留不动** | `-pl simos-army -am -DskipTests validate` rc=0 + `BannedDependencies passed` 计数不变；**负向**：给 army 加回 `simos-sd` 依赖 ⇒ 当场红（证明禁列仍有牙）。★ 代价 = 1 exclude + 1 dependency + 2 段文案 |
| **0c** | `army.RecordCombat` 的 **revision 级判据** | 只加测试：① 提交 ⇒ head +1 且 `ArmySnapshot.data().combats()` 恰 1 条；② 同 id 再提交 ⇒ `Rejected` 且 **head 不变** | **负向**：改坏写口（`withCombat` 写别表 / 去掉 `Applied` 分支）⇒ ①必红；删"阶段 id 重复具名拒"⇒ ②必红。今天只有域级测试，无 revision 级证据 |
| **0d** | 文档更正（4 处，实测已推翻） | `freeze.md:69`（army 行**漏 gov/sd/ga**，允许面要 +gov）· `freeze.md:68` + `AGENTS.md:62`（sd 的"推演框架层"已被 B2a/B3 推翻 ⇒ 改为"决策/军事/外交域状态 + handler"）· `2026-10-13-unit-integration-p2.md:40` 的 `unit.ResolveCombatStage` **笔误**（实测只有 `army.ResolveCombatStage`）· `freeze.md:78`/`AGENTS.md:72` 的迁移前置「map 现用了 Nation」**实测为假** | 逐处 `grep` 复核；**不改历史留痕行**（§五.4），用追加更正 |

### 批次 1 · 权限反默认（**等价迁移，零行为变化**）
- **依赖**：甲组 4 条裁定。
- **交付**：`simos-util/spi/DmUsableCommand.java`（纯标记，照 `GmOnlyCommand` 形制；**必须落 util** —— 9 个域要实现，放 sd 要改 9 个 pom 的禁列）；`Shell.java` 三处判据改判标记（**含 `:903-914` 那 5 条无条件 add —— 否则是后门**）；机械派生的 **91 条**加 `implements`（**零 pom / 零 enforcer / 零测试断言改动**）；装配期**互斥断言**（同时标两个 ⇒ 当场抛，照 `ToolRoleAudit` 形制）。
- **验收判据**：
  - 正向：`66 集对称差 = ∅`（`DirectiveWhitelist.allowedTypes()` vs `drainableCommandTypes` —— **今天实测已是 ∅**，迁移后重跑同比较）；
  - 计数三重锁：`AdjudicateTickToolTest:549`（**66**）+ `:931`（97）+ `McpCoverageTest:1050`/`SimosToolsTest:900`（142）同时绿；
  - 语义锁：`CatalogVisibilityTest:64-107`（`map.CreateRegion` 等对 DM **必须可见**、`sd.PutInfo` 等**必须消失**）全绿；
  - **负向（判别力核心，今天必红）**：新加一条命令**不打任何标签** ⇒ 它**不得**进 `directiveCommandTypes` 与 `drainableCommandTypes`，**必须**进 `catalogCommandTypes`（142→143）与 GM 的 `simos.command.submit` 目标表。
- **风险**：失效方向从"漏标 ⇒ 静默放权"翻成"**漏标 ⇒ 静默收权**"。缓解事实：收窄**只影响"令里可嵌 / 效果可入队"**，**不影响 GM 直接提交面（仍 142）** ⇒ 没有任何命令变成提交不了。

### 批次 2 · 权限收紧（**另开批，逐条裁定**）
- **依赖**：甲组第 4 条（分批）。
- **内容**：**35 条今天在生效**的候选（map 7 · social 6 · `actor.Seed` · `economy.Seed`（★ 两者可**凭空造钱**，`reports/2026-10-08-currency-issuance-path-investigation.md:231-249` 记为"本次调查最重的一条"）· unit 20）。
- ★ **不得与批次 1 混提交**（否则红点无法归因）。其中 `unit.SetComposition`/`CreateUnit` **已在册待裁定**（`status/2026-10-23-…:190` 第 12 条）。

### 批次 3 · 城市 = 逐 hex 比例状态 + hex 命名
- **依赖**：乙组 3 条裁定。
- **交付**：`map` 持有逐 hex 城市化率（固定数值）；改它的命令（`initiator` 走既有信封；`reason` 保持现有）；hex 命名（额外命名 / GM 改名工具）；GUI 后做。
- **已知爆炸半径（实测）**：`SocialCity.at` **86 处读者**；创世 `SettlementGenerator` 整套以"点"为中心（**本项最贵**）；`urban:` 前缀 30 处/8 文件（**判据 A 不绑 CityId** ⇒ 可改成 `urban:<q>_<r>:`）；**市场区已不派生自城市**（只剩 3 处陈旧类注）；**城市化迁移整链是死码**（可零成本删）。
- **顺带消解**：两套城乡判据（前缀 vs 落点==城 at）的分歧**自动消失**；`GameMap.cities` 死支可删；`CityLand` 死链可删。

### 批次 4 · 区划语义归 GOV（**省籍优先，绕开全部阻塞**）
- **依赖**：丙组 3 条裁定（省籍步只需第 8 条的"先立契约"）。
- **次序**：
  1. `GovState` 加 **1 组件** `provinceRegions: Map<RegionId, RegionId>`（**key/value 都 `RegionId`** ⇒ 绕开 `NationId` 阻塞）；`GovChangeSet` + `GovCodec`（**键读侧必须在本模块注册**，漏了 ⇒ 表非空时解码期炸）+ **批次 0a 的往返护栏**；
  2. `ProvinceApplyPlan` 同批多提交一条 `gov.RegisterProvince` + 同步"加第一条 gov 命令"的 **9 处**（`Shell` 注册 / `CatalogTool.PAYLOAD_HINTS`（缺项 ⇒ Shell 构造期抛）/ `SimosToolsTest` 两处 / `McpCoverageTest` 三表 / `AdjudicateTickToolTest` / 自动派生面）；
  3. `ProvinceAssignCitiesPlan.isProvinceOrCapital` 改读 gov 表；`isNationRegion` **暂保留**。
- **负向判据（今天必红）**：造一个 **id 不含 `__P`** 但 gov 表里有它的 Region ⇒ 改造后**应被选为候选**。
- **★ 命令改名必须显式 `GmOnlyCommand`**：`Shell.java:863,876-884` 的令白名单 = 注册面 − `sd.*` − `GmOnlyCommand` ⇒ 改名 `gov.*` 会**自动进入令白名单与 Effect 入队集合**（批次 1 落地后此风险自动消失）。
- **后续**：`Nation`/`diplomaticRelations`/`diplomaticEvents` 三组件搬 gov（受第 8/9/10 条裁定与 gov/sd 互禁约束）。

### 批次 5 · 战斗退役 + 归 Army
- **依赖**：丁组 4 条裁定（0b 的 pom 放行已可在批次 0 做）。
- **次序**：
  1. **判决面重挂**（需第 12 条）：`Breakpoints.java:84-99` + `VerdictFreezer` 文案 + 3 个测试 → `army:combat.*`；**负向**：`sd:combat.<id>` + D1 ⇒ **具名拒**（今天接受 ⇒ 判别力明确）；
  2. **效果面脱钩**（需第 13 条）：`Trigger.OutcomeSelected`/`ThresholdKills`、`Action.SetStage`/`RecordCasualties`（★ `Action.RecordCasualties` **无生产者**且 `SdTimeParticipant:361` 的 `default -> {}` **静默忽略**它 ⇒ 已是死路，可顺手清）；
  3. **退注册面**（纯删除，最易验证）：`Shell.java:840-844`、`SimosToolSource.java:876-880,1196`、**ga 侧 6 个工具类**、`CatalogTool.java:780-794`、GUI `/api/sd/combats`、MCP `simos.sd.combats`、`DecisionAdjudicationService` 数据源；
  4. **样板：只退 `sd.CreateCombat` 一条**（验证整套拆除流程）；**负向**：`grep 'sd\.CreateCombat' simos-*/src/main` **必须 = 0**（今天 9 处）、`Shell.java:840` 不得再 `new CreateCombatHandler()`、四个 `containsExactly` 名单必须同步（只删注册不改名单 ⇒ 当场红）；
  5. **删模型与组件**：`SdState` 组件（14 → 11 或 10）、`SdChangeSet`/`SdCodec`/`SdResolver:126,146-169`、`model/{Combat,CombatStage,CombatStages,CombatState,OutcomeTable,OutcomeOption,LossRecord,LossClass,CasualtyDelta,CasualtySpec}`、`SdTimeParticipant:196-249`、`SdRoundTripTest:60` 的 `hasSize(14)`；
  6. **`armies` 归属**（需第 11 条）。
- **不可逆点**：`Verdict.subject`（**持久字段**，历史 world 会指向不存在实体）· 命令类型串（出现在提示词/白名单/catalog，失败**静默**）· 地址族 `sd:combat.*`（`AccessPathsTest:59`、`ToolSurfaceGapTest:176`、`SdVerdictFreezeEndToEndTest:86,103,113` 钉着）。

### 批次 6 · gov 命令面（若第 14 条裁"现在就建"）
- gov 加第一条命令的 **9 处同步** + `GovCodec` 已是 `ModuleCodec, ModuleDiffer` ⇒ 批路径机械可行 + **批次 0a 的往返护栏是前置**。

---

## 2. 执行次序与并行度

```
批次 0（0a/0b/0c 可并行；0d 文档）           ← 现在就能做，零裁定
   ↓
批次 1（权限反默认等价迁移）— 需甲组 4 条裁定
   ↓
批次 2（收紧 35 条）— 逐条裁定，独立批
   ↓
批次 3（城市比例 + hex 命名）— 需乙组 3 条裁定；本主题**最贵**
批次 4（省籍 → Nation/外交）— 需丙组 3 条裁定；**省籍步可先走**
批次 5（战斗退役 → 战斗归 army）— 需丁组 4 条裁定；**0b/0c 已先走**
批次 6（gov 命令面）— 若裁"现在就建"
```
- **并行度**：**一次只允许一个写代码 Agent**（§一.10 红线）；`SimosToolSource`/`Shell`/测试计数表是共用装配面 ⇒ 批次之间**串行**。
- **每批一编译门禁**（`compile` → `validate` → `-pl <域> -am verify`），测试迁移按 §一.5 留到批次末尾统一做。

---

## 3. 不做什么（非目标）

- **不做工单通用化**（用户已裁：保持现有）。
- **不把 `GameMap.spec` 当状态用**（它不进变更集，破铁律 2/5）。
- **不把 overlay Region 当 hex 命名方案**（`regionIndex()` 每次调用重算且被**逐格/逐单位热调**；标签 Region 会被 `SetJurisdiction` 当合法管辖目标）——除非第 6 条裁定选 (c)。
- **不把 gov 当 sd 剩余组件的回收站**（战斗四件按 **D-012** 归 army）。
- **不为"外交迁移"写旧档兼容**（§一.11：旧档作废；但**地址族是对外接口**，见第 10 条）。
- **不在批次 1 里顺手收紧**（35 条另批）。

---

## 4. 风险与不确定（如实）

1. **未跑 Maven**：所有"无环 / 会红"均为 **pom 声明 + 断言源码**级证据，非实测；`army→gov` 放行后是否被传递链逼出例外**未实测**（`searchTransitive=false` 已在，按冻结批经验应无例外）。
2. **`affiliation`/权限面**：`Affiliation.Nation` 若随国家语义搬走，会动 **D-002**（"不给中央开天眼"）的判据载体 ⇒ 第 9 条的裁定影响面大于表面。
3. **前端未统计**：`resources/webui/**` 对 `/api/sd/combats`、城市图层、region tag 的消费点**未逐文件核**（批次 3/5 的实现方须先补这一核）。
4. **创世重写成本可能偏乐观**：`SettlementGenerator` 只读了行号级摘要（候选评分/升级抽样/Zipf/腹地归属）。
5. **`DeleteCity(deletePopulation=true)` → economy 侧 `PeopleLotId` 悬空链未追**（批次 3 会撞上）。
6. **`.claude/worktrees/**` 下 30+ 平行工作树未核**。
