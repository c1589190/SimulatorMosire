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
