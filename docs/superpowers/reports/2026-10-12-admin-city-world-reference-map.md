# 行政区划 / 城市 / 世界：引用与所有权全图（只读调查，2026-10-12）

> **这是什么**：三个只读子 Agent 并行调查 + 控制方综合的**事实图**（不是设计、不是裁定、不是待办清单）。
> 供后续"区划下游重算 / 城籍 / 城市化 / 地图重建 / 世界内容初始化"等批次**派单前定位现场**用。
> **方法**：只读回代码（显式目录 grep + 逐文件读）+ 仓外运行目录只读查看；**未跑 Maven、未起服务**。
> **基线**：`main` @ `d3ad1a18`。**标注约定**：`file:line` 为实测；文档说但未跑起来的写"【注释推断】"。
> **权威**：与代码冲突以代码为准（AGENTS §四）。

---

## 0. 三条先纠正的普遍误解

| 误解 | 实测 |
|---|---|
| 地图状态类型叫 `MapData` | **全仓不存在 `MapData` 类型**（只在指 GSimulator 老仓的注释里）。simos 的地图状态 = **`GameMap`**（9 组件：`hexes/terrainBlocks/regions/cities/terrainTypes/pathways/pathwayGroups/edges/spec`），`simos-map/.../map/GameMap.java` |
| "省"是状态里的一种实体 | **「省」在状态里不存在**。`Province` 作为**类型**全仓只有一处声明 = `simos-gov/.../gov/tools/ProvinceDivider.java:129` 的**嵌套 record**，只是"建议器"的输出条目。省 = **`Region.meta.tag="province"` + id 约定**（`__P<两位序号>` / 首都区 `__CAP`），下游全靠**字面量**认这个约定（`ProvinceAssignCitiesPlan.java:44-47,146-149`） |
| `simos-gov` 有一批 `gov.*` 命令 | **`simos-gov` 一条 `CommandHandler` 都没有，全仓不存在任何 `gov.*` 命令类型**。gov 全是**工具**（tool），靠**组批别的域**的命令落盘 ⇒ "gov 的写面" = 它提交的那些 map/unit/social/sd/economy/actor 命令 |

---

## 1. 行政区划（Region / Province / 辖区）

### 1.1 类型与状态
- `Region`（record，5 组件）：`id/name/hexes/boundary/meta` @ `map/region/Region.java:35`；住 **`GameMap.regions`** @ `GameMap.java:65`。
- **`boundary` 是组件且构造期与 `hexes` 重算结果钉死**（不等即抛，`Region.java:55-60`）；`RegionMeta{color,tag,description,annexedBy}` 中 `annexedBy` **无业务判定读它**（只在创世回填与 GUI 透出）。
- `RegionIndex`（hex→`List<RegionId>`）**不进状态**、每次重算（`GameMap.java:177-179`，类注明写"缓存就是第二份可漂移的副本"）。
- `RegionId`：**稳定 ID 由调用方给**（`RegionOperations.java:37` 类注 Q3）；`toString()` = 裸值、`parse` 收裸值并剥 `map:<mapId>:region.` 前缀（`RegionId.java:23,31,37-42`）。
- Codec `MapCodec`（`namespace()="map"`）+ `MapChangeSet.regions`；往返测试三处齐备（`RoundTripComponentsTest.java:97,184,204`、`MapCodecTest.java:84`）。

### 1.2 写面：8 条真改 `regions` 的 `map.*` 命令
`Create/Update/Delete/Rename/Merge/Split/ReassignHexes/RandomizeRegion`（handler 见 `simos-map/.../map/spi/*Handler.java`）。
- **`GmOnlyCommand` 标记**：`RenameRegion`/`MergeRegions`/`SplitRegion`/`ReassignHexes` **标了**；`CreateRegion`/`UpdateRegion`/`DeleteRegion`/`RandomizeRegion` **没标** ⇒ **可嵌进决策令**（`Shell.java:876-878` 的 `directiveCommandTypes` 推导）。
- ⚠️ **命名陷阱**：`map.RandomizeRegion` **不碰 `regions` 组件** —— 它按载荷给的 `hexes[]` 随机化**地形**（`RandomizeRegionHandler.java:22-30`）。
- **三条 `*Region` 命令的窄工具不存在**：`MergeRegions`/`SplitRegion`/`ReassignHexes` 无窄工具，只能走 `simos.command.submit`。**其中 `SplitRegion` 零生产调用点**。
- 交叉域"区域清空"三条 GM-only 命令：`social.ClearRegion` / `actor.ClearRegion` / `economy.ClearRegion`，由 app 的 `RegionClearPlan.java:35-42` 一批下单。
- 辖区/税率的写口：`unit.SetJurisdiction`（区域存在性由领域层**具名拒**，`UnitOperations.java:711-713`）、`unit.SetTaxRate`（两条具名拒 `:747-776`）。

### 1.3 读面（按域，要点）
- **economy 的 23 个「Region」文件里 22 个是 `MarketRegion`（市场区）**，与行政区**无引用关系**（`grep import io.mosire.simos.map.region simos-economy/src/main` **只命中 1 个文件**：`spi/EconomyClearRegionHandler.java`）。行政区→市场区的桥在 **app**（`app/time/MarketTopologyBook.java:147-224`、`app/world/ThreePowersMarketZones.java:257-273`、`app/access/HexOwner.java:15-17,54-70`）。⇒ **`Region` 与 `MarketRegion` 是名字撞车的两个概念。**
- gov：`GovDemand.java:22-29,57-62`（逐格行政需求，Region 查无 ⇒ **跳过**不抛）、`GovCreateOfficePlan`/`GovRecruitPlan`/`GovSelectExamineesPlan`/`GovExpandHouseholdTool`（按管辖 Region 取 hex 集）、`GovToolPerspective.regionVisible`（围栏）。
- unit：`Jurisdiction.java:40-41`（**唯一权威**）、`UnitOperations.setJurisdiction/setTaxRate`、`LevyRegionPlan.java:144-152,250-266`、`RaiseUnitPlan.java:184-194`。
- social：`SocialCity.region`（`Optional<RegionId>`）是城市归属权威；`ClearRegionHandler.java:100,129`；创世配置以 `RegionId` 为键（`WorldgenConfig.java:52,58-62`）。

### 1.4 辖区表示对照 —— **只有一处权威**
| 表示 | 位置 | 权威? | 与 Region 的关系 |
|---|---|---|---|
| `unit.Jurisdiction` | `unit/Jurisdiction.java:13-14,40-45` | ✅ **唯一权威**（`taxRatePerMilleByRegion` 的**键集就是管辖区域集**） | 只持 `RegionId`，不持 Region 对象 |
| gov 侧 | `GovOfficeState.java:56-62` | ❌ gov **没有** jurisdiction 字段 | 读 `Unit.jurisdiction` 算需求 |
| `army.ArmyVision`（"军队辖区=视野"） | `army/ArmyVision.java:16-19,21-22` | ❌ 纯派生不落盘 | **完全不引用 Region**（只 `effectivePosition`+`visionRadius`）⇒ 只是共享"辖区"这个词 |
| `app.gov.GovTerritory` | `app/gov/GovTerritory.java:30-35` | ❌ 派生；类注 **"绝不进任何授权判定"** | 沿 `superiorGov` 子树收集 RegionId 并集 |
| `app.access.GovScope` | `app/access/GovScope.java:28-38,93-117` | ❌ 派生授权前缀 | 本级 jurisdiction 的 Region → hex/Region 前缀 |

### 1.5 `Province` 三个工具的实际产出
| 工具 | 产出 | 落点 | roles() |
|---|---|---|---|
| `simos.province.divide` | **只读建议**（`ProvinceDivider.java:198-226` 的 `Suggestion`，含 `suggestionHash`/`warnings`） | **零写入、零 revision**（类注 `:28-30`"本类只出建议"） | `{GM}`+`GmOnlyRead` |
| `simos.province.apply` | 一批落盘：`map.CreateRegion ×(省+首都区)` → `unit.CreateUnit ×(中央+省)` → `social.CreateHousehold` → `actor.EnsureHouseholdAccount` → `unit.SetGovFormation` → `economy.RegisterGovernment` → `unit.SetJurisdiction` → `sd.CreateDecisionMaker`（`ProvinceApplyPlan.java:69-86`） | map 的 Region + unit 的 Unit/Formation + sd 的 DM + social 家户 + economy 政府记录；**一批一条 revision** | `{GM}` |
| `simos.province.assignCities` | 按 `SocialCity.at` 落在哪个省/首都区，批量重设 `SocialCity.region`（每条一条 `social.UpdateCity`，一批一条 revision） | **只改 social** | `{GM}` |

### 1.6 重叠 / 重复征税现场
- **Region 重叠是明文设计**（`RegionOperations.java:30-31` 引 M8-U1 原话"重叠一律允许…不存在'重叠时谁赢'"）；`RegionIndex` 一个 hex 可属多区（`RegionIndex.java:14-16`）。
- 但 **GOV 辖区另有互斥红线**：`GovJurisdictionGuard.java:35-36` 引用户 2026-10-08 第 8 轮原话「重叠辖区是不被允许的」。该守卫**只拦三条入口**（`unit.SetJurisdiction`/`map.UpdateRegion`/`map.ReassignHexes`，`:44-53`），明写不拦 `Create/Merge/Split/Delete/RandomizeRegion`（`:55-64`）；**零测试**。
- **同一条不变式，两条日循环处置相反**：
  - `app/time/JurisdictionDailyTax.java:73` —— 「**重叠管辖**：同一天多单位命中同一区域 ⇒ **按单位 id 升序依次征，后者见前者税后余额**」，**无去重**；
  - `app/time/PortExposureEdges.java:244-249` —— 同格两主 ⇒ `LOG.error` + **`throw new IllegalStateException`**（fail-closed）。
- ⇒ 需要人定：这条红线现在的**实际保证边界**是什么（`CreateRegion`/`Merge`/`Split` 能否造出双征），以及**期望行为**统一成什么。

### 1.7 下游重算面（P2-F1 前置）
- 三命令类注**四处都明说**"只改 map 自己的 `regions`；下游由 app 组合根 `submitBatch` 协调"（`RegionOperations.java:179-180`、`MergeRegionsHandler.java:29-31`、`SplitRegionHandler.java:38-40`、`ReassignHexesHandler.java:33-34`）。
- **但那是类注、不是机制**：全仓**没有任何反应式钩子**；唯一组批者是 `app/gov/MoveCapitalPlan.java`（同批含 Merge/ReassignHexes/UpdateRegion + `social.MoveCity` + `unit.SetJurisdiction`，`:210-283`），**它不重算税率/编制**，且**自身零生产调用者**（见 §2.6）。
- **测试落差断崖**：`Create/Update/Delete` 有 35 条用例；`Merge/Split/ReassignHexes` **零直接调用测试**，只在 `McpCoverageTest` 的"必须被拒"清单里。

---

## 2. 城市（City）

### 2.1 ★ 四处 `City`，map 那份是**死支**
| # | 类型 | 位置 | 生产是否被写 |
|---|---|---|---|
| 1 | `map.City` | `simos-map/.../map/City.java:29` | **否 —— 零生产构造点** |
| 2 | `social.SocialCity` | `simos-social/.../social/city/SocialCity.java:40` | **是（唯一权威）** |
| 3 | `ThreePowersWorld.City`（private 嵌套） | `app/world/ThreePowersWorld.java:900-901` | 仅 demo 夹具常量表 |
| 4 | `social.gen.PlannedCity` | `social/gen/PlannedCity.java:24` | 仅 worldgen plan（落盘前，同一身份） |

**map 侧是死支的四条证据**：① `GameMap.withCities` 的 7 处命中**全是测试**；② 生产 `new City(` 只有 `ThreePowersWorld:301-303`（那是私有嵌套 City，非 map 的 record）；③ **map 模块 13 个 spi handler 无一个 City**，所有创世只发 `social.CreateCity`；④ **代码自认** —— `app/gui/ApiViews.java:449-451` 原文「数据源是 `SocialData#cities()`…**不是** `GameMap.cities()` —— **后者在 worldgen 之后仍为空**」。

**由此 4 处恒空/恒失败的读点**：`MapResolver.java:166-173`（`map.city` 主体解析恒失败）、`ApiViews.java:430-439`（`/api/map/overview` 的 cities 块恒空）、`ToolSupport.java:688-698`（工具总览 cities 恒空）、`ToolSupport.java:366-369`（`cityHex` 资源可见性恒 false）。

### 2.2 写面（5 条）
| type | GmOnlyCommand | 窄工具 | 关键语义 |
|---|---|---|---|
| `social.CreateCity` | ❌ | **无** | `population` **明令拒收**（`SocialPayloads.java:546-553`，`population: null` **不拒**）；id 已存在 ⇒ 拒；未知键静默忽略 |
| `social.UpdateCity` | ❌ | ✅ `SocialUpdateCityTool` `{GM}` | `props` 合并、`region` 三键态；**`at` 等未知键静默忽略**（`UpdateCityHandler.java:68-86`，类注自认只管形状与类型） |
| `social.MoveCity` | ✅ | **无** | 落点与归属都同现值 ⇒ **拒**（"没有造成任何变化"）；**只改 `at`，不改批次 id** |
| `social.DeleteCity` | ✅ | **无** | 有 `urban:<id>:` 批次且未给 `deletePopulation=true` ⇒ **严格拒**（原文见 `CityOperations.java:84-93`，含"当前模型不支持改批次 id"） |
| `social.ClearRegion` | ✅ | — | ★ **`DeleteCity` 守卫的旁路**：`ClearRegionHandler.java:123-129` 按 `at` 或 `region` 命中**整条删城、不查 urban 批次** |

### 2.3 读面
- **economy 零城市身份读**：`simos-economy/src/main` **零 `import map.CityId`、零 `cities()` 调用** ⇒ economy 只通过 props/参数/批次前缀认识城市（铁律 3 成立）。
- gov：`ProvinceAssignCitiesPlan`（读 `social.cities()`）、**`GovSelectExamineesPlan.java:311-318` 的 `residenceAt`**（落点==某城 `at` ⇒ URBAN）。
- unit：经 `UnitToolPerspective.residenceAt` **转调** gov 那个 `residenceAt`（`AppUnitToolPerspective.java:57-58`，类注自称"既有唯一实现"）。
- app：`ApiViews.java:461-490`（`/api/social/cities` 权威读点）、`MapTextRenderer:155`/`RenderModelBuilder:107`（渲染实际画 social 城市）、`GovWorldBootstrap:796`/`ThreePowersGovBootstrap:972`（校验 GOV 座位落在某城）。

### 2.4 ★★ 城籍：**没有字段，就是批次 id 前缀**
- 唯一拼写点链：`economy-api` 的 `ResidenceKind.URBAN.lotPrefix()` → `PopulationLots.URBAN_PREFIX`（转发）→ `PopulationLots.urbanPrefix(CityId)`（**fail-closed 拒含 `:` 的城 id**）。
- 形状：`rural:<q>_<r>:<SEX>:<细分>` / `urban:<cityId>:<SEX>:<细分>`；**城籍绑 `CityId`（身份）不是坐标**；新生批次**继承母亲前缀**（`PopulationLots.born()`）。
- **城市人口 = 现算**：`SocialData.urbanPopulationAt(CityId)`（`SocialData.java:1208-1221`，城不在表 ⇒ **抛**、拒绝静默返回 0）；`SocialCity` 上**没有 population 字段**（R1/T5 降级为派生量，类注称原字段是"第三份人口账…只会漂移"）。
- `CreateCityHandler` 拒收理由**原文**：「若留着那个字段又不读它，就会得到本仓最忌的那种字段："看起来在记、其实不起作用"。」

### 2.5 ★★ 现场有**两套互不相同**的城乡判据
| # | 判据 | 位置 | 依据 | 认不出时 |
|---|---|---|---|---|
| A | **批次 id 前缀** | `PopulationLots.isUrban` / `ResidenceKind.ofLot` | 城**身份** | **抛**（fail-closed） |
| B | **落点 == 某城 `at`** | `GovSelectExamineesPlan.residenceAt:311-318`（unit raise/spawn 在用） | 城**位置** | 恒 `RURAL`（不抛） |

`MoveCity` **只改 `at`、不改批次 id** ⇒ **A 与 B 在迁城后必然分歧**；**无文档、无测试说明谁对**。（清单 §5-#7 的"按前缀还是 residence"问的就是这个，但现状比二选一更复杂：两套都在跑。）

### 2.6 其它关键状态
- **`CityLand` / `CityLandBook` 全链死**：9 个 `cityLand.*` 键**零生产写点**、`fromProps` **零调用者**；唯一消费者 `CityMigrationReading` 的唯一引用者 `PopulationMigrationPlanner` **也零调用者**。它是 `specs/2026-10-10-urbanization-and-industry-pool-design.md` 声明的"城区池雏形"，而该文档 **Q1–Q11 全部未裁定**、§4.2 记着 **D10 与 D-025 不能同时成立**、§8 明说"§6 未答复前不足以支撑派单"。
- **`MoveCapitalPlan` 零生产调用者**（后端已写完、**无工具、无测试**；类注自称"先做后端，再做 Agent/MCP 工具"）。
- **`GovAssignPosts` 零 City 引用**（它写家户/档位/承诺）。
- MCP 侧 **没有 `simos.social.cities`**（116 条工具名常量逐条核过）；GUI 有 `/api/social/cities`（前端 `webui/api.js:150-163` 等 4 处已消费）。

---

## 3. 世界（创世 / 播种 / 世界状态）

### 3.1 两条互不相干的"创世"
**A. 空库首启**（`--world=<id>` → 内置生成器 → 恰 1 条 `(main,1)` revision）：
`ShellMain.parse` → `Shell.start`（8 codec 表 / handler / participants / 两桶 + `ToolRoleAudit` / MCP 桥 / GUI 监听）→ `WorldRegistry.require` → `seedGenesisIfEmpty`（**仅空库**）→ `CoreSimos.bootstrapGenesis`（**非空库具名拒**；全 8 片一次落盘；变更集 `WorldChangeSet.empty()`）→ 写 `(main,1)` checkpoint（`CoreSimos.java:302-341`）。
**B. GM 组合工具链**：`simos.worldgen.initialize` / `simos.region.seed` / `simos.region.clearData`（见 §3.3）。

### 3.2 ★ 默认世界**三处矛盾**
| 落点 | 值 |
|---|---|
| `config/shell.json:2` | `small-world` |
| `ShellConfig.java:62` **内置缺省** | **`v17levant`** |
| `run-small-world.sh:28` | 显式 `--world=small-world` |

已登记 4 个世界：`WorldRegistry.java:135-152`（`v17levant`/`small-world`/`corridor`/`three-powers`）；`dashu-v2` **不是注册世界**（只在 `~/simos-runs/`）；`test-world` **不是 worldId**（是仓内运行目录，启动**不传** `--world`）。

### 3.3 三个创世工具
`WorldgenInitializeTool`(1392 行) / `RegionSeedTool`(744) / `RegionClearDataTool`(404)，**`roles()` 全是 `{GM}`**，生产装配点 `SimosToolSource.java:657/661/664`，**只在 GM 桶 = 唯一 MCP 口**；**GUI 没有这三条的路由**（`/api/command` 是裸信封直落 `core.submit`）⇒ GUI 调不到组合工具。
- `RegionSeedTool` **有 clean gate**（命中 ⇒ `NEEDS_CLEAR` + **零 revision**）；`WorldgenInitializeTool` **没有**自己的 clean gate（靠下游 handler 拒）；`shortfall>0` **原样返回、不阻止落盘**（"播了但缺口未补"的合法态）；`preview/dryRun` = **零字节**。
- 另有两个 **in-code** 创世落点（不走工具）：`GovWorldBootstrap`（只被 `SmallWorld.java:318-324` 调）、`ThreePowersGovBootstrap`（只被 `ThreePowersWorld.java:374-375` 调）。

### 3.4 世界状态的权威载体
`SimulationState`（util，无跨模块访问器）只持 `Map<String,Snapshot>`；**权威 = SQLite `revisions` 表 + `checkpoints/<branch>/<rev>.json`**（"历史是唯一真相"；checkpoint 是纯派生物、不进事务、缺了不失败）。8 个命名空间：`map/social/unit/sd/economy/actor/gov/army`。
地图载入三条真路径：内置资源（`RichWorld.readResource:150-159`）· 落盘回放（`Replay.java:168,235,256`）· 程序化构造（`SmallWorld.map():419-470` 等）。

### 3.5 ★ v17l 重建的影响面
- **内置 `simos-app/src/main/resources/worlds/v17levant.json` 是旧形状**（4.22MB；59223 hex / 252 regions / **240 边** / **terrainBlocks 0**；每格带 `terrain`+`height`）⇒ **靠 `MapCodec.migrate` 的旧档回退才读得回来**（`MapCodec.java:48-51,278-360`；`MapCodecLegacyTest` 钉的就是它）。
- **重建产物只在仓外**：`~/Simos-18Lvt/world/checkpoints/main/1.json` 与 `test-world/store/`（59223 hex / 252 regions / **447 边** / **1270 terrainBlocks** / 含 `plateau`）。导入器 `tools/gsimap_import.py --regen-samples`（D-013 重建模式；`REBUILD_TERRAIN_MAP` 里 `plains→plateau`）。
- **硬编码 59223 的 11 处**；**"240" 的语义在 `social/gen/GameMapTerrainView.java:17`**（重建后是 447 ⇒ 注释已不准）；`WorldRegistry.java:137` 的描述串仍写"240 条河流边"。
- **顺序冲突（必须先裁）**：AGENTS §一.11 说"Alpha 不写旧档兼容"，而**旧形状回退正是默认世界（v17levant）的必经路径** —— 顺序反了默认世界立刻读不回来。

### 3.6 test-world 实测（python sqlite3 只读）
`test-world/store/simos.db`：`revisions` main 1..8、**tick 全 0**；`checkpoints/main/1.json` 八片俱全（59223 hex / **cities 0** / `units:{}` / actors 0 / gov offices 0）；rev3/rev4 = 手工写入的 `u-demo-red`/`u-demo-blue` 两条演示单位。
★ **更正清单文档口径**：`2026-10-12-known-issues-code-verified.md:109` 说"rev1 是 `modules:{}` 空创世"—— 空的只是 rev1 的 **changeset**（创世变更集按设计为空），**checkpoint 不空**。

### 3.7 旧档兼容代码（与 §一.11 并存，实测点名）
① `MapCodec.migrate` 旧形状回退（**默认世界必经**）② `ActorCodec` 旧三段 household id 迁移 ③ "缺键=空"一族（`ActorData`/`HouseholdInventory`/`ArmyData`/`EconomyData`/`ProductionLedger`）④ `ActorChangeSet` ⑤ `MarketZone` 的 `@JsonIgnoreProperties("issuingGov")` ⑥ 决策人空 `allowedTools` ⇒ 沿用全局白名单 ⑦ `administrationPerMille`"已退役、仅为旧档兼容" ⑧ `RichWorld:94-124` 给旧创世档补空片。
**不是**兼容位的：`MarketTable.isEmpty()` 的 `@JsonIgnore`（那是铁律 5 往返缺陷修复）。

---

## 4. 综合：这三块"要动先裁定/先解决"的清单（**不是待办，是前置**）

1. **默认世界的权威落点**（`shell.json` vs `ShellConfig` 内置 vs 脚本）—— §5-#11 未裁定；且"非空库绝不覆盖"意味着换世界只能换 store 目录。
2. **内置 v17levant 的旧形状与 §一.11 的顺序冲突**：重建产物以什么形态进仓、旧形状回退何时能撤。
3. **`GameMap.cities` 是留是删**（死支 ⇒ 每加一处城市功能都要先猜读哪一份）。
4. **城籍口径**（前缀 vs residence）：两套判据已在跑且迁城后必然分歧 —— 这比"二选一"更需要明确"哪套是权威、另一套要不要废"。
5. **`DeleteCity` 守卫是否升级为全仓不变量**（`ClearRegion` 现在是旁路），以及"无主 `urban:` 前缀"是否必须禁止。
6. **重叠辖区红线的实际边界与期望行为**（`GovJurisdictionGuard` 只拦三条入口、零测试；日税不去重 vs 口岸暴露边硬抛）。
7. **`Merge/Split/ReassignHexes` 的语义证据链**（零直接测试、无窄工具、`SplitRegion` 零调用点）——任何"下游重算"都建在它上面。
8. **`CityLand` 是接线还是废弃**（全链死 + 城市化 Q1–Q11 未裁定 + D10/D-025 冲突）。
9. **世界内容的初始化路径二选一**（D-013 第 4 条原文）：in-code 创世 vs `simos.worldgen.initialize`。

---

## 5. 调查边界（如实）

- **未跑 Maven、未起服务**：所有"某测试覆盖/未覆盖"都是**读文件**得出，测试计数与红绿未复核。
- **未读 live 世界库的全部状态**：`test-world` 只读 `revisions`/`events`/`store_meta` + `checkpoints/main/1.json`（本机无 `sqlite3` CLI，用 python 只读）；`main@8` 未重放验证。
- **`.claude/worktrees/**` 下 30+ 平行工作树未核**（各有一份 `City.java` 等副本）。
- **未追 `DeleteCity(deletePopulation=true)` → economy 侧 `PeopleLotId` 悬空引用链**（12 个 economy main 文件用 `PeopleLotId`）—— 这是**最大的未覆盖面**，也是唯一"能造出破坏性状态但无人验证后果"的路径。
- **`EconomySeeder`(5474 行)/`GovWorldBootstrap`(965)/`ThreePowersGovBootstrap`(1160)** 只读类注 + 入口 + 调用点，未逐方法核。
- **`MarketTopology` 本体**（economy-api）未读 ⇒ "市场区与行政区完全解耦"只有单侧证据。
