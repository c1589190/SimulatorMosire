# 可行性调查：比例状态城市 / hex 命名 / Region 通用化 + 语义归 GOV（2026-10-12）

> **这是什么**：用户 2026-10-12 新设计意图的**可行性结论 + 待裁定清单**（三个只读子 Agent 并行调查 + 控制方综合）。
> **不是设计书、不含实现方案**；只回答"能不能、要动什么、代价多大、先要什么裁定"。
> **基线**：`main` @ `cd31f16d`；只读（未跑 Maven）。**权威**：与代码冲突以代码为准（AGENTS §四）。

## 0. 用户设计意图（逐字照录，§一.8.1）

> 「首先，地图肯定是交给Map模块维护逻辑的，存储交给Core就行；然后是城市，城市是单个hex的比例状态，也就是说单个hex可以1%是城市、99%是非城市等，这种状态叫比例状态；然后是去掉作为标签的城市后，所有hex都可以被额外命名，GUI方面后面在做，改名需要用GM工具；行政区划本质是区域，实际上，行政区划、国家区划本质上都应该交给GOV模块来维护，GUI只负责显示——因此应该先修改GOV模块和Region模块，Region模块只提供通用的区域创建/修改/替换等工具操作和通用接口，GOV是实际负责提供国家、省份等语义的」
>
> 同轮追问：「明白我的意思吗？调查一下，告诉我是否可以实现」

---

## 1. 逐条可行性结论

| # | 用户意图 | 可行性 | 一句话依据 |
|---|---|---|---|
| 1 | **地图逻辑归 map、存储归 core** | ✅ **已是现状，零工作** | `simos-map` 只依赖 util（+ga-spi）、零存储；落盘走 core 的 `MapCodec`+`Timeline`/`CheckpointStore` |
| 2 | **城市 = 逐 hex 比例状态** | ✅ **可实现**（爆炸半径比预期小，但创世重写贵） | 见 §2 |
| 3 | **城市不再是标签 ⇒ 所有 hex 可额外命名** | ✅ **可实现**（现状 = 0） | 见 §3 |
| 4 | **Region 只做通用区域** | ✅ **map 侧基本已在岸上** | `grep -i "nation\|province" simos-map/src/main` 命中**全是 Javadoc/工具文案/接口签名**，**零类型判定** |
| 5 | **国家/省份语义归 GOV** | ⚠️ **分区可行**：省籍/首都区 = 中成本；**国家语义卡两条硬阻塞** | 见 §4 |

---

## 2. 城市 → 逐 hex 比例状态：四条意外助力 + 两处真成本

### 2.1 助力（实测）
1. **市场区已不再派生自城市**：`MarketTopologyBook` 的 W1 重写**删掉了** `byCityRadius`；现在只读 `economy.marketZones`（持久区）或 `marketTables`（价表条目），**全程不读 `SocialCity`**（`app/time/MarketTopologyBook.java:109-133,144-208`）。只剩 **3 处陈旧类注**（`MarketRegion.java:15-18`、`MarketNode.java:12-16`、`EconomyData.java:347-348`）。
2. **城乡判据（`urban:` 前缀）完全不绑 `CityId`**：`ResidenceKind.ofLot` 只按前缀判（`ResidenceKind.java:79-95`）；真正用 `<cityId>` 段的只有 **5 个方法**（`PopulationLots.urban/urbanPrefix`、`SocialData.urbanPopulationAt`、`CityOperations.attributedLots`、`PopulationSeeder.cityIdByHex`）。前缀总耦合 **30 处 / 8 文件**。
3. **逐格城乡读数已经存在**：`SocialData.urbanRuralAt(HexCoord)`（`SocialData.java:1277-1291`）；`ApiViews` 逐格城乡预聚合、`EconomySeeder.urbanByHex` 也都是逐格的。
4. **城市化迁移整链是死码**：`PopulationMigrationPlanner` 全仓**零引用** ⇒ `CityMigrationReading`、`MigrationPolicy.urbanPull`、`CityLand`/`CityLandBook`（9 个 `cityLand.*` 键零写点）、`MoveCapitalPlan` 随之全死 ⇒ **删掉零行为变化**。

### 2.2 真成本
- **`SocialCity.at` 有 86 处读者**（这是"去掉城市点实体"的真正成本面）；`SocialCity` 的 4 条命令 handler、GUI 三处、渲染两处、创世多处都硬依赖坐标。
- **创世 `SettlementGenerator` 整套以"点"为中心**：候选格评分 → 三级抽样 `tierUpgradeRate` → Zipf 档位 cap → 半径内最近归属（`SettlementGenerator.java:31-49,178-212,298-330,367-410,545-620`）。城市不再是点 ⇒ 这套要重写成**逐格城市化强度场**；但其**逐格权重/剩余/交通三段算式可直接复用**（输入本就是逐格量）。**本项最贵。**
- 归省（`ProvinceAssignCitiesPlan` 按 `city.at()` 落省）、判据 B（`residenceAt`：落点==某城 `at`）、渲染/GUI、`DeleteCity` 守卫（按前缀查 `urban:` 批次）都要换判据。

### 2.3 落点候选（比例状态 + hex 名字**抢同一个落点**）
| | 落点 | 评级 | 关键代价 |
|---|---|---|---|
| (a) | `GameMap` 新组件（`Map<HexCoord, …>`） | **中** | `new GameMap(` **120 处**（main 14/test 106）；组件数钉子 3 处（`GameMapTest:219-222`、`RegressionGuardsTest:319-329`、`RoundTripComponentsTest:147-152`）；`MapChangeSet` **位置构造** 3 处；`MapCodec` 3 处；`migrate` 一行兜底 |
| (b) | 塞 `HexCell`（加字段） | **中** | `new HexCell(` **240 处**；`HexCellTest:50` 组件数钉子红；**复活 P1 用户裁定「HexCell 只剩 height」** |
| (c) | overlay Region（每格一个 Region） | 代码**小** / 副作用**大** | **零新代码**（4 条命令与 GM 窄工具全在），但 `regionIndex()` 每次调用重算且被**逐格/逐单位热调**（`ToolSupport:285`、`NationScope:94`、`MapResolver:187`）；GUI 区域列表多 L 条；`map.overlaps` 配对爆炸；标签 Region 会被 `unit.SetJurisdiction` 当**合法管辖目标** |
| (d) | 塞 `GameMap.spec` | **不可行** | `spec` 是生成输入、契约"同 spec 必然同图"、**不进变更集**（破铁律 2/5） |
> ⇒ 建议 **(a) 稀疏 Map 组件**（比例 + 名字同批放，59,223 格里绝大多数没名字，稀疏更省）。

---

## 3. hex 命名：现状 0，成本可控
- **现状实测 = 0**：`hexLabels|hexLabel|nameHex|SetHexLabel|hexNames` 全树 **0 命中**；命名只在 Region 级；GUI 的 `label` 是**区域质心锚点**（`ApiViews.java:424 → :5004-5019`），不是 hex 名。
- **写入路径 = 中**：1 handler + 1 窄工具（`{GM}`）+ 2 注册点 + **catalog 构造期抛**（`CatalogTool.java:965-970`，必须补 1 条）+ **5 个测试文件 / 4 个精确计数**（`SimosToolsTest` 的 142/166/70/176、`McpCoverageTest`、`McpServerTest`、`McpPortTopologyTest`）。
- ★ **不能复用 `map.UpdateRegion`**（它改 hexes/meta，不含 name；`RenameRegionHandler.java:33-35` 有"为什么单开一条"的明文先例）。

---

## 4. Region 通用化 + 语义归 GOV

### 4.1 map 侧已经在岸上（反直觉，实测）
- `simos-map` 的 `nation|province` 命中**全是注释/文案/接口签名**；`RegionMeta.tag` 在 map 里就是**不透明串**（`MapOverlapsTool` 的过滤/分组即证据）。
- `RegionOperations` **六操作全部天然通用**：`createRegion:79` / `updateRegion:119` / `deleteRegion:163` / `mergeRegions:186` / `splitRegion:233` / `reassignHexes:357`（+ `RegionPart:454`）。
- 通用面**两个缺口**：无 `renameRegion`（内联在 `RenameRegionHandler.java:86-88`）；用户说的"**替换**"没有独立操作（`updateRegion` 是全量替换 hexes/meta）。

### 4.2 语义现在住哪（全在 sd/gov）
| 语义 | 位置 | 读者规模 |
|---|---|---|
| `nation:<id>` | `NationTag.java:12-22`（**在 simos-sd**） | **14 个生产读点**；引用 `NationTag` 文件 27 个、调用点 31 处、字面量 `"nation:` 22 处 |
| `tag="province"` | `ProvinceApplyPlan.java:137,616` | **1 写 0 读**（第二个 `annexedBy`） |
| `__P00` / `__CAP` id 约定 | `ProvinceApplyPlan.java:160,860-876`、`ProvinceAssignCitiesPlan.java:44-47,145`、`RegionClearStructuresPlan.java:298-301` | gov 内 4 处字符串匹配 |
| `annexedBy` | `RegionMeta.java:8` | 唯一读点 `ApiViews.java:5026`（回显） |

★ **顺手消掉的真缺口（实测）**：`CreateNation` **只判前缀**（`CreateNationHandler.java:65`）而 `DeleteNation` 要**逐字相等**（`DeleteNationHandler.java:233`）⇒ **全仓无人校验 `tag == tagFor(nationId)`**（`nation:FRA` 的 Region 可承载 nation `GER`）。

### 4.3 ★★ 两条硬阻塞（冻结表**完全没识别**）
| # | 阻塞 | 实测 | 出路（需裁定） |
|---|---|---|---|
| ① | **`NationId` 住在 `simos-sd`，而 `simos-gov` 禁 sd** | `NationId` 全仓唯一在 `simos-sd/.../sd/id/NationId.java`；`simos-gov/pom.xml:95` = `<exclude>io.mosire:simos-sd</exclude>` | (a) 新建 `simos-gov-api` 契约层（干净，但**要回填 17 模块 enforcer + AGENTS 表 + 父 pom**）(b) `NationId` 下沉 `simos-util`（最省，但 util 自述"零依赖地基/不持领域身份"）(c) 表键用 `String`（**造第二拼写点**） |
| ② | **`HandlerOutcome.Applied(ChangeSet)` 只回"自己模块"的变更集** | `util/spi/HandlerOutcome.java:20`；而外交判据全在 sd（`CreateDecisionMakerHandler.java:113-124` 等） | Nation 搬 gov ⇒ 要么 gov 回 `SdChangeSet`（**gov→sd 被 enforcer 拒**），要么把 `SdState.nations` 一起搬（**sd 20+ 处读者**，而 sd 也禁 gov） |
> ★ 冻结表 `freeze.md:78` 写的迁移前置是「`simos-map` 现用了 `Nation`」—— **实测为假**（map 零 sd 依赖，`grep` 0 命中；`simos-map/pom.xml` 只声明 util + ga-spi）。**真正的前置是上面两条。**

### 4.4 gov 加命令面的成本（有先例可抄）
`git show 1630104e` **删过 gov 的 3 条 handler**（`SetPortPolicyHandler`/`SetBudgetPolicyHandler`/`SetAdministrationPlanHandler`）⇒ "gov 加第一条命令"有先例。加一条 `gov.*` 必须同步 **9 处**，漏任一处**当场红或抛**：
`Shell.java:530-871` handlers 清单 · `CatalogTool.PAYLOAD_HINTS`（**缺项 ⇒ Shell 构造期抛**）· `SimosToolsTest`（`EXPECTED_COMMAND_TYPES:547` + `hasSize(142):985`）· `McpCoverageTest`（类型表 + `MINIMAL_PAYLOADS` + `PRECONDITION_REJECT_TYPES:1050-1058`）· `handlerTypesFromSources()`（扫 `../simos-gov/src/main/java`，**自动纳入 ⇒ 只改计数**）· `AdjudicateTickToolTest`（1630104e 点名的"第 5 张手抄清单"）· `directiveCommandTypes`（`Shell.java:876-884` **自动派生 ⇒ 新命令默认可嵌决策令**，要排除须显式 `implements GmOnlyCommand`）· `CatalogVisibility`（自动派生，不用手改）· `ToolRoleAudit`（**只认工具面、不认命令**；若同时加窄工具则要同步 `roles()` 与 5 个计数 + `DecisionFaceGolden.ALL`）。

### 4.5 建议的主权落点（不破铁律 3 + enforcer）
1. **gov 新增「Region → 行政语义」分类表**，**key/value 都用 `RegionId`** ⇒ **绕开阻塞①**，且 gov **不持 hex**；
2. "Region → 哪些 hex" 仍归 **map**（`Region.hexes` 是几何唯一权威）；
3. "哪个 GOV 管哪些 Region" 仍归 **unit**（`Unit.jurisdiction`，类注"key 集就是管辖区域集"）；
4. **合成仍在 app**（`GovJurisdictionGuard.java:29-33` 自陈的理由成立）；
5. `unit.Jurisdiction` **不动**（它答"**谁管这片**"，gov 表答"**这片是什么**"，正交；强行合并会把 `unit.SetJurisdiction` 变成跨切片写口，成本从中跳到**大**）。

---

## 5. ★★ 待用户裁定（**不定不能开工**）

### 5.1 口径冲突（必须用户裁，控制方不得代裁 —— §一.8.1 / §一.12）
| # | 冲突 | 双方原话与出处 |
|---|---|---|
| **C-1** | **"比例状态" vs D10"不是比例、是独立池子"** | **本次**：「城市是单个hex的**比例状态**…1%是城市、99%是非城市」。**2026-10-10 D10**（`specs/2026-10-10-urbanization-and-industry-pool-design.md:34`）：「非城市化地区同样可以增加，之前说错了，**城市化地区也不应该算作比例而是算作独立的池子**」⇒ **逐字相反** |
| **C-2** | 与 **D-025** 的关系 | D-025（`specs/2026-10-02-architecture-design-source.md:409-416`）：「**城市化率是派生量** `urbanization = urbanPopulation / totalPopulation`…允许城市清零」。若"比例"是**人口比例** ⇒ 推翻 D-025；若"比例"是**用地/强度比例** ⇒ D-025 原样成立 |
| **C-3** | 该文档**自己已记着**这条冲突未消解 | `urbanization spec §4.2` **C-1**：「D10 与 D-025 冲突…**两者不能同时成立** ⇒ Q1」；同文档 §6 **Q1–Q11 全部未裁定**、§8 明说"§6 未答复前不足以支撑派单" |

### 5.2 其余待裁（实现级）
1. **城市是"完全消失"还是"不再占一个点"**（保留无坐标身份）⇒ 决定城籍前缀走 `urban:<q>_<r>:`（与 `rural:` 同形）还是保留 `CityId`；
2. **随仓世界 `worlds/v17levant.json`（4.2MB，构建输入，每次加载都走 `migrate`）**：`migrate` 补空段 vs 重刷资源 —— **§一.11「Alpha 不考虑兼容」是否覆盖构建输入**（不裁则实现方要么白写迁移、要么踩红 `RichWorld` 那 3~4 个测试）；
3. 比例 + 名字的**落点形态**：(a) 新组件 / (b) 塞 `HexCell`（复活 P1 裁定）/ (c) overlay Region；
4. **名字与 Region 的关系**（hex 自身属性 vs "标签区域"是否参与 `RegionIndex`/GUI 列表/重叠检测/管辖目标）；
5. 命名一条 **upsert**（null=清）还是 **Set+Clear 两条**（决定 MCP 覆盖表 +1/+2）；读侧是否过 `hexVisible` 并集口径；
6. `RegionMeta.tag` **保留为通用自由标签**（零代价）还是**退役**（大：48 处 `new RegionMeta(`、34 文件、随仓 252 个 region 全带 `"tag":"Nation"`、而 `migrate` 完全不碰 regions）；
7. `annexedBy` 是否随批退役（唯一读者是 GUI 回显）；
8. `province` / `__P` / `__CAP` 是否收口为 gov 的单一常量；
9. **`NationId` 去向**（阻塞①的 (a)/(b)/(c)）；
10. **外交类型迁移的前置重写**（阻塞②；冻结表原文的前置实测为假）。

---

## 6. 建议的执行次序（**裁定之后**）

**第一步（能独立编译 + 验收，绕开全部硬阻塞）**：只做**省籍**收口，不碰 Nation、不碰 hex 归属
1. `GovState` 加 **1 组件** `provinceRegions: Map<RegionId, RegionId>`（省 Region → 父国家 Region；**key/value 都是 `RegionId`**）；
2. `GovChangeSet` + `GovCodec`（**键读侧必须在本模块注册**，漏了 ⇒ **表非空时解码期炸**）+ 往返测试（**表非空**）；
3. `ProvinceApplyPlan` 在**同一批**多提交一条 `gov.RegisterProvince`；同步 §4.4 的 9 处；
4. `ProvinceAssignCitiesPlan.isProvinceOrCapital` 改读 gov 表；`isNationRegion` **暂保留**（国家 tag 未搬）。

**验收判据（含负向）**：正向 —— `province.apply` → `province.assignCities` 走通且候选来自 gov 表；
**负向（判别力核心）** —— 造一个 **id 不含 `__P`** 但 gov 表里有它的 Region ⇒ 改造后**应被选为候选**（**这条今天必红**）；
回归 —— `McpCoverageTest` 绿、`GovCodec` 往返绿、`SimosToolsTest` 计数同步。

**同时可做的零风险清理**（零行为变化）：删城市化迁移死链（`PopulationMigrationPlanner`/`CityMigrationReading`/`CityLand`/`CityLandBook`/`MoveCapitalPlan`）+ 清 3 处陈旧类注（`MarketRegion`/`MarketNode`/`EconomyData`）。

**次序铁律（语义搬迁）**：**先把所有 tag 读者切完并证明零遗漏，再停写 tag**（反过来 = 漏切读者**静默失明**：`HexOwner` 空集 / `NationScope` deny-all / `CreateNation` 具名拒）。

---

## 7. 调查边界（如实）
- **未跑 Maven、未起服务** ⇒ 所有"会红在哪一行/会抛"均为**静态读断言源码**得出，非实测红；无测试计数。
- **未读 live 世界库**（`test-world`/`~/Simos-18Lvt`）⇒ `RegionMeta.tag` 的真实值分布按随仓资源 + 代码写点推断（随仓 252 个 region 的 tag 全是 `"Nation"`，**与代码约定 `nation:` 大小写不符**，且**无人读**）。
- **未核 `.claude/worktrees/**` 下 30+ 平行工作树**（按纪律只看主工作树）。
- **未追 `DeleteCity(deletePopulation=true)` → economy 侧 `PeopleLotId` 悬空引用链**（改前缀口径时它会变成实际影响面）。
- `SettlementGenerator` 只读行号级摘要 ⇒ **创世重写成本估计可能偏乐观**。
- **未系统检索 D-xxx 用户原稿** ⇒ 用户这条设计意图**是否已有更早裁定，未排除**（§一.12 口径溯源未做全）。

---

## 8. ★★ 用户 2026-10-12 后续裁定（**追加；与上文冲突处以本节为准**）

### 8.1 原话逐字照录（§一.8.1）

> 「首先，一切以我最新说明为准
> 其次，城市方面目前暂时只交给Map维护一个固定数值，变动搞一个标准的程序内工单，就是提交谁干的、为了干什么就能改一个hex的城市化率
> 还有，根据前者，这种程序内工单在项目里似乎不少吧？也许可以做个通用化？原本要求提交谁干的为了干什么是为了日志方便，现在看来很有必要标准化啊
> simos-sd不是取缔掉了吗？GOV模块随便引用新的ga模块；
> 国家语义直接般GOV模块得了呗，GOV就是维护国家/政府等的！
> 继续思考和考察」

### 8.2 控制方处置（逐条）

| # | 裁定 | 处置 |
|---|---|---|
| R1 | **一切以最新说明为准** | ⇒ §5.1 的 **C-1 冲突按用户最新原话判**：**城市 = 单个 hex 的比例状态**；**D10「不是比例、是独立池子」作废**（其出处 `specs/2026-10-10-urbanization-and-industry-pool-design.md:34` 需标注被取代）。C-2 仍待明确（"比例"是人口还是用地/强度）。 |
| R2 | **城市 = Map 维护一个固定数值** | ⇒ 城市化率是 `map` 持有的**逐 hex 数值**（不是社会域、不是点实体）⇒ 落点 = `GameMap` 的逐 hex 属性（候选 (a) 新组件 / (b) `HexCell` 加字段）；**"固定数值"** = 它是个被写入的事实，不是每 tick 重算的派生量。 |
| R3 | **变动走标准"程序内工单"：谁干的 + 为了什么** | ⇒ 改单格城市化率要走一条**带 `initiator` + `reason` 的命令**（`initiator` 信封层已有；**`reason` 尚无标准位置** —— 见 §8.3）。 |
| R4 | **"这种程序内工单在项目里似乎不少"，要做通用化** | ⇒ 已派只读调查（工单/审计元数据现状盘点 + 通用化代价）。**待其结论后再裁形态**。 |
| R5 | **GOV 模块随便引用新的 ga 模块** | ⇒ 技术上**成立**：`ga` 的闭包 = `util`/`ga-spi`/`core`/`sd`，**其中无 `gov`** ⇒ `gov → ga` 无环。（控制方复核进行中；`ga` 是否需要另论 —— gov 已依赖 `ga-spi`。） |
| R6 | **国家语义直接搬 GOV 模块；GOV 就是维护国家/政府的** | ⇒ 目标明确：`Nation` 及国家/政府语义归 `simos-gov`。**但用户前提有误**（见 §8.4），搬迁路径已派只读调查。 |

### 8.3 控制方已实测的两条起点事实（供 R3/R4 用）

| 元数据 | 现状 | 位置 |
|---|---|---|
| **"谁干的"（`initiator`）** | ✅ **已标准化** | `CommandEnvelope(commandId, correlationId, **initiator**, branch, expectedRevision, type, payloadJson)`（`simos-core/.../command/CommandEnvelope.java:22-29`）→ 落盘 `RevisionRow(..., **initiator**, commandType, changesetJson)`（`simos-core/.../timeline/RevisionRow.java:25-34`） |
| **"为了什么"（`reason`）** | ❌ **未标准化** | 全仓 `"reason"` 951 处命中，但只有 **3 个 handler** 用 `requireText(payload,"reason")` 当**必填载荷**；其余散在可选载荷/工具参数/日志/注释里 |
| **仓里已有的"工单"实体** | 存在 | `social.SubmitHouseholdWorkOrder`（army/gov 都在用，如 `simos-gov/.../GovRecruitPlan.java:82-83`） |

### 8.4 ★ 更正用户前提：**`simos-sd` 没有被取缔**（实测）

`simos-sd/src/main/java/io/mosire/simos/sd/` **存在且是重模块**：
- **`SdState` 有 14 个组件**：`nations` / `armies` / `combats` / `combatStates` / `decisionMakers` / `directives` / `effects` / `verdicts` / `lossRecords` / `info` / `diplomaticRelations` / `diplomaticEvents` / `decisionPackets` / `mergedEffectPlans`。
- **28 个 `CommandHandler`**。
- ★ **军队/交战状态也在 sd**（`armies`/`combats`/`combatStates`/`lossRecords`），而 `simos-army` 只是**派生视图**模块（`ArmyVision` 不落盘）。
- B2a/B3 只把**决策人运行流**与**决策/GM 工具**搬进了 `simos-ga`；`sd` 的**状态与 handler 一件没走**。
⇒ 因此「国家语义搬 GOV」的实际含义是：**从 `SdState` 把一个或多个组件搬出去**，而不是"从已废弃模块里搬"。

### 8.5 顺带更正两处已被实测推翻的文档前提（留痕，不改旧行）

| 文档 | 原文 | 实测 |
|---|---|---|
| `specs/2026-10-12-module-boundaries-freeze.md:78` | 「外交迁移前置：**`simos-map` 现用了 `Nation`**」 | **假**：`grep -rn "io.mosire.simos.sd" simos-map/src` = **0 命中**；`simos-map/pom.xml` 只声明 util + ga-spi。map 里的 `Nation` 全是 Javadoc/测试 tag 字符串。**真前置是 `NationId` 落点 + `HandlerOutcome` 只回本模块变更集**（前一轮报告 §4.2/§4.3）。 |
| `specs/2026-10-12-module-boundaries-freeze.md:68`（sd 行） | 「`simos-sd` = **推演框架层**：决策流程、权限范围、工具协议与两个工具面」 | **已过期**：决策流程与两个工具面在 B2a/B3 已搬 `simos-ga`；`sd` 现在是**决策/军事/外交的域状态 + handler**。 |

---

## 9. 两路深查结论（2026-10-12 追加）

### 9.1 工单/审计元数据：现状 + 通用化的四道门

**★ 先说口径溯源（§一.12）：「工单」这件事用户 2026-10-09 已经裁过**
> 用户原话（`specs/2026-10-09-social-direct-boundary-and-substantive-naming.md:172-178`）：
> 「Unit/Eco 要改一个家户的人口属性，**直接向 Social 提交"更改理由 + 更改方案 + 更改对象"的 Social 工单**」
> + 「**不要为此普遍搞多模块聚合**」

⇒ **本次 R4「做个通用化」与那条裁定存在读法张力**：若"通用化"= **统一工单的元数据形态**（谁+为什么），与 2026-10-09 相容；
若"通用化"= **把所有域的动作收进一个跨模块工单聚合器**，则**与那条裁定相反**。**必须先确认是哪一种。**

**现状量化（实测）**
| 维度 | 现状 |
|---|---|
| 「谁干的」 | **已贯穿到底**：`CommandEnvelope.initiator` → `revisions.initiator` → `events.agent`；形态 = C21（`String`，`<kind>:<id>`，不设枚举，`specs/2026-09-18-core-simos-design.md:41`） |
| 「为了什么」 | **按模块各写各的**：**必填 18 个命令类型**（social 12 + unit 2 + economy 4，**不是 3** —— 控制方起点按 `requireText(payload,"reason")` 字面数漏了 `SocialPayloads.requireReason` 一族）；可选 17 个；**工具面必填 32 个工具**；日志 kv 716 处 |
| 唯一"真工单"实体 | `social.HouseholdWorkOrder(orderId?, target, **reason**, **source**, plan)` —— 四件齐（谁自报+为什么+改什么+幂等键），**只活在 social 一个域**；类注自认「来源是**调用方自报**…不宣称它是可信身份」 |
| 其余候选实体 | `Directive`/`DecisionPacket`/`MergedEffectPlan`/`Effect`/`SdInfoEntry` **不是同一抽象**（分四层：请求/决策/调度/记录） |
| `reason` 是**重载词** | ≥6 义：人类审计理由 / **拒绝原因**（`CommandResult.Rejected.reason()`）/ 审批摘要字段 / **封闭词表**（`DeductionReason` 6 档）/ 状态内持久字段 / 注释 |

**三条硬约束（决定"标准放哪层"）**
1. **handler 结构性看不见调用者**：`CommandHandler.handle(SimulationState, String)` 无调用者入参（`DecisionSignature.java:20-23` 逐字承认）⇒ 域层自记身份只能是**自报**；可信身份只在 `revisions.initiator`/`events.agent`。
2. **Core 不能解析载荷**（C26）⇒ "顶层必须有 reason"的中央校验**只能落在 app 装配层**，且**拦不住直接构造信封的路径**（`sd.IssueDirective` 内嵌命令、`AdjudicateTickTool` 批）。
3. **批路径**：`CommandBus.java:468-477` `commitBatch` **只取首条命令的 initiator**，且**批不写事件** ⇒ 同批多命令只能共用一个 reason。（与清单待裁定第 5 条「批工具审计缺口」是同一个洞。）

**五种形态对照（详见调查原文）**：(a) 信封必填 = 可验证性最强（72 构造点 + 旧库读不了，§一.11 允许）；(b) 信封可选 = 最弱且与「空串当场炸」口径自相矛盾；(c) 装配层声明表 = 不破旧档但拦不住直接构造；(d) 域内工单 = **与 2026-10-09 裁定相反**；(e) 只做日志 = 弱（`events.payload` 只留 digest）。
★ **若只加可选字段（overload/工厂），测试红点可压到 ≈0；加必填则 `McpCoverageTest` 的 88 条最小载荷要逐条补。**

### 9.2 国家语义搬 GOV：三条新发现 + 谁该搬谁不该搬

**★ 新发现①：命令改名会「自动放权」**（前两轮未列，直接改变做法）
`Shell.java:863,876-884`：令白名单 = 注册面 − **`sd.*`** − `GmOnlyCommand`；`DirectiveWhitelist.java:38-40` 同款。
⇒ `sd.CreateNation` 今天**靠 `sd.` 前缀**才被挡在"可嵌决策令"外；**一旦改名 `gov.CreateNation`，它自动进入令白名单与 Effect 可入队集合** ⇒ **必须显式 `implements GmOnlyCommand`**（先例：`DeleteNationHandler:62`、`SetArmyMasterGovHandler:46`），否则是**静默权限扩张**。

**★ 新发现②：`simos-gov` 是全仓唯一没有铁律 5 往返护栏的切片**
`find simos-gov/src/test -name '*RoundTrip*'` = **0**；gov 只有 4 个测试文件；`getRecordComponents()` 组件数钉子有 actor/army/economy/sd/map，**没有 gov**。
⇒ **给 `GovState` 加第 3 个组件而忘了改 `GovChangeSet`，今天没有任何测试会红** —— 正是铁律 5 要防的 G漂移。**必须先补护栏再加组件。**

**★ 新发现③：`state.module("gov")` 是 untyped，救不了跨模块读**
`SimulationState.module(String)` 返回 `Optional<Snapshot>`（只有 ref/timestamp/namespace）；全仓 `*-api` 里**零 Snapshot**，既有形制全是 `instanceof` 强转 ⇒ sd 要读 gov 状态**必须 import gov 的类型** ⇒ **必须解 gov/sd 互禁**（`gov/pom.xml:95` + `sd/pom.xml:78`，两侧都要动）。

**阻塞②的机械形状（比前一轮更硬）**：`CommandBus.java:414` `namespaceOf(type)` = `type.substring(0, indexOf('.'))`；`:506-512` 用**命令类型前缀**决定把 handler 的 ChangeSet 应用到哪个命名空间 ⇒ **一个 handler 只能回自己模块的变更集**；跨模块写只能靠 `submitBatch`（多条命令同批）或多模块 tick 提案。

**组件归属（14 个逐条判定）**：**3 个属国家/政府**（`nations`/`diplomaticRelations`/`diplomaticEvents`）；**11 个属决策/推演**。
★ **战斗四件（`armies`/`combats`/`combatStates`/`lossRecords`）不归 gov**：**D-012 用户口述已裁定**「交战的具体记录**移到 Army 模块**记录与处理」「旧 sd 战斗命令族**不再作为交战记录的载体**，命令本身本阶段不动，清理另批」（`architecture-design-source.md:141-148`），且 `simos-army` **已有平行实现与数据**（`ArmyData(Map<CombatRecordId,CombatRecord>)`，army 对 sd 的 import = 0）⇒ 去向是"**退役或归 army**"，把 gov 当 sd 剩余组件的回收站会与 D-012 撞车。

**`nations` 读者 20 处**：sd 内 **13**（`SdResolver:106`/`SdCodec:181,211,242,264`/`SdChangeSet:123,144`/`RegionDeleteGuard:62`/`CreateDecisionMakerHandler:124`/`SetDiplomaticRelationHandler:87,91`/`CreateNationHandler:56,73`/`DeleteNationHandler:90,139`）、app **4**、ga **3**（经 `SdState` 间接）。测试面：sd 13 文件/49 处、app 34 文件/180 处。

**★ `Affiliation.Nation` 判为"留 sd"**：它是**权限边界**（`NationScope.java:60` 的 `instanceof Affiliation.Nation` 决定国家决策人可见范围；`ChannelAdmission:82-85` 渠道准入）⇒ 属 dm-gm 总实现，**不是国家语义**。⚠️ 但这与「国家语义直接搬 GOV」的**字面**冲突 ⇒ 按 §一.8.1 **必须用户裁**。

**「GOV 随便引用 ga」控制方判定**：`gov → ga` **无环但没必要** —— gov 已引 `ga` **141 处、全部是 `ga.spi.*`**（非 spi = 0）；`simos-ga/pom.xml:96` 禁 gov；gov 自己的 pom 注释写「域模块依赖 **ga-spi 而不是 ga** —— 反过来会成环」⇒ **应解读为"GOV 可引用 ga 的工具契约层（ga-spi）"，现状已满足**。

**会破的既有裁定/文档（4 条）**：① R6（2026-10-02「外交关系边表落 **sd 新组件**」）与「外交是 gov 的事」冲突，须按 §四.1 标注取代；② D-012（战斗归 army）；③ D-002（`NationScope` 是"不给中央开天眼"的判据载体）；④ 冻结表 §3 的 gov/sd 两行 + `AGENTS.md` gov 行。
★ 顺带更正：冻结表 `:78`/`AGENTS.md:72` 写的迁移前置「`simos-map` 现用了 `Nation`」**实测为假**（`NationTag` 在 map/gov 里只是 `{@link}` javadoc，无 import）⇒ **真前置是 `NationId` 落点 + gov/sd 互禁 + gov 缺往返护栏**。

**不可逆点三处**：`sd:nation.<id>` 地址族（`AccessPathsTest:54` 钉住；地址不是"档"，是对外接口）· 命令类型串 `sd.CreateNation`（决策人 prompt/白名单、GM catalog、12 处测试夹具）· gov/sd 互禁解除。

**最小可行切片（绕开全部阻塞）**：第 0 步给 gov 补 `GovRoundTripTest`（反射枚举 + `hasSize(2)` + 排除集为空）；第 1 步建 `simos-gov-api` **只装 `NationId`**（零主依赖，照 `actor-api` 先例）+ 回填 7 类同步点。
**负向判据（今天必绿 ⇒ 判别力缺失）**：往 `GovState` 加组件而不改 `GovChangeSet` ⇒ **应红**。
