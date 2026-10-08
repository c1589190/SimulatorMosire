# 调查报告：辖区互斥新裁定与「三不管地带」在现有代码的落点

> **调查性质**：只读。未改任何生产代码/测试，未跑 Maven，未起服务，未 `git commit`。
> **核对基线**：工作树 `HEAD = 5ae583e1`（"docs: HANDOFF-2026-10-23 保存工作状态…"）。工作树有 2 个**别人**的未跟踪报告
> （`docs/superpowers/reports/2026-10-08-admin-region-and-extraction-investigation.md`、
> `2026-10-08-market-zone-and-currency-investigation.md`）——本文件与它们**不重叠**：本文只答「辖区归属/互斥/三不管」这一片，
> 抽税-军俸统一原语、市场区-货币口径分别归那两份。
> **纪律**（AGENTS §四）：机制性描述一律回代码核；台账/设计书措辞只作线索。凡未核到者单列 §10，不写成事实。
> **检索写法**（回应派单书对「glob pathspec 会静默 0 命中」的提醒）：本文全部用**显式目录 + `--include`**
> （如 `grep -rn … --include=*.java simos-unit/src/main simos-app/src/main`），或 `grep -rn … .` 后显式
> `grep -v /target/ | grep -v "\.claude/worktrees"`（本仓 `.claude/worktrees/` 下有 ~30 棵历史工作树，
> 不过滤会把旧代码读成现状）。每条"零命中"结论都写明检索命令与关键词。

---

## 0. 一句话结论

**「一个 hex 只被一个行政政府管辖」这条新裁定，在今天的代码里既没有被表达、也没有被校验、更没有被拒绝**：
`map` 的 Region 是**显式多对多**（重叠是用户自己 M8-U1 裁定的正常状态），`unit.Jurisdiction` 只记「谁管哪些 Region」而
**不查别的 GOV 是否已管同一个 Region**，全仓唯一的跨切片守卫（`MutationGuard`）只管"国家 tag 的 Region 不可删"；
于是两个 GOV 同辖一格会**各自全额征税**（`JurisdictionDailyTax` 逐区域累加，无跨 GOV 判重，`inventory:70/192` 已登记）；
而用户原话里的第三条出路「**三不管地带**」在全仓**零表示**——没有判定、没有读口、没有日志、`GapKind` 里也没有这个 kind，
一个不属于任何 GOV 辖区的 hex 今天只是**静默地**不征税、不算行政需求，市场则照常运转（市场与 GOV 的唯一实质耦合是
"政府国库户不得生成买卖单"）。

---

## 1. 本次调查的最高权威：用户 2026-10-08 原话（逐字）

本报告引用的两条裁定为派单书原样转录（未改写、未缩写）**且经检索确认全仓首次落盘**：

> ①「**重叠辖区是不被允许的，一个地方只可能有一个行政政府，要么就是重划、变成三不管地带**」
>
> ②「**三不管地带的市场行为完全自由，全由市场机制调控**」

**留痕状态（如实记，附实际检索写法）**：于 HEAD `5ae583e1` 在仓库根跑
`grep -rn "重叠辖区是不被允许的\|一个地方只可能有一个行政政府\|三不管地带的市场行为完全自由\|全由市场机制调控" --include=*.md . | grep -v "\.claude/worktrees"`
→ **0 命中**；`grep -rln "三不管" --include=*.md . | grep -v "\.claude/worktrees"` → **0 命中**。
⇒ 两句原话此前未见于仓库任何文档，**本报告是它们的首次落盘引用**。

**与同批另一条 2026-10-08 原话的关系（必须并列，不在本文裁定）**：同日另一份报告
（`2026-10-08-admin-region-and-extraction-investigation.md:26-30`）引用的用户原话是
「**行政区、市场区不能搞混，两者理论上不但可以重叠、包含，甚至可以半包含、部分重合等**」。两条原话只有在下述读法下才不冲突：
前者说**跨层**（行政区 ↔ 市场区）可以重叠，本条说**同层**（GOV 辖区 ↔ GOV 辖区）不允许重叠。
代码今天**不区分这两层**（`RegionIndex` 不承载层次，`RegionIndex.java:21-22` 自述；`map.region.RegionId` 与
`economy.api.market.MarketRegion` 是两个不同类型，见该报告 §3）。
⇒ 该读法是本报告的**假设**，需用户/控制方确认后才可当裁定用（见 §9 矛盾点 D）。

---

## 2. Q1 — 「辖区/行政区归属」在代码里的**全部**表达方式

逐条给 `file:line` + 「今天允不允许一个 hex 被两个 GOV 管」。

| # | 表达方式 | file:line | 语义 | 今天允许一 hex 两 GOV 吗 |
|---|---|---|---|---|
| 1 | `map.Region`（几何实体） | `simos-map/.../map/region/Region.java:18-19`（5 字段）、`:28`（`hexes = Set.copyOf`）、`:36-41`（**唯一不变式**：`boundary` 必须等于 `hexes` 重算结果） | 纯几何 + 元数据；**无层级、无 GOV 字段** | **允许**——Region 根本不知道 GOV 存在 |
| 2 | `GameMap.regions`（容器） | `simos-map/.../map/GameMap.java:65`、`:79`（只做 null 校验 + 不可变拷贝，**无相交校验**）；`regionIndex()` 派生 `:174-179` | 全图区域表 | **允许** |
| 3 | `RegionIndex` 反向索引 | `simos-map/.../map/region/RegionIndex.java:14-16`（"从属是多对多…**不存在"重叠时谁赢"**"）、`:42-56`（**全部保留**，按 id 字典序）、`:58-62`（`regionOf` 返回**全部**归属）、`:64-67`（`hasRegion`） | 派生件，不进变更集/存档 | **允许**（显式设计） |
| 4 | `RegionOperations` 五个几何命令 | `simos-map/.../map/ops/RegionOperations.java:30-31`（"**重叠一律允许**…**没有任何"与已有区域相交就拒绝"的校验**"）、`:477`（"**只校验"在不在图上"**，绝不校验相交"） | map 域写口 | **允许** |
| 5 | `unit.Jurisdiction.taxRatePerMilleByRegion` | `simos-unit/.../unit/Jurisdiction.java:13-14`（"**key 集就是管辖区域集**，空 map = 无管辖"）、`:34`、`:41` | 单位 → 它管的 Region（+每 Region 长期税率） | **允许**：两个 unit 的 key 集可同时含同一 `RegionId`，**无跨 unit 校验**（见 #9） |
| 6 | `unit.GovernmentFormation` | `simos-unit/.../unit/GovernmentFormation.java:62-64`（`policy`/`superiorGov`/`level`） | 编制：上级 GOV + 层级 + 岗位表 | **允许**：`superiorGov` 只表层级，父子 GOV 辖区可任意重合 |
| 7 | `GovernmentLevel` | `simos-unit/.../unit/GovernmentLevel.java:9-16`（`CENTRAL`/`PROVINCE`） | **编制事实**，类注明说"**它不参与权限判定**" | 不表达辖区 ⇒ 不构成任何限制 |
| 8 | `GovScope`（授权派生） | `simos-app/.../app/access/GovScope.java:93-97`（管辖集 ← `jurisdiction.keySet()`）、`:100-122`（逐 Region 展开 hex 前缀）、`:134-145`（unit 面） | 决策人可见面 = 本级**直辖** | **允许**：两个 GOV 的授权 hex 面可完全重合，各算各的、无交叉判重 |
| 9 | **写口** `unit.SetJurisdiction` | 域层 `simos-unit/.../unit/ops/UnitOperations.java:686-732`——校验**只有** `:705-715`（`regions` 每项必须在 `map.regions()` 里）；handler `simos-unit/.../unit/spi/SetJurisdictionHandler.java:53`（type）、`:58`（handle）、`:96-105`（从 `SimulationState` 取 map）；窄工具 `simos-app/.../tools/write/UnitSetJurisdictionTool.java:15` | 整体替换管辖集合（空数组 = 撤销全部） | **允许**——**这是"一 hex 两 GOV"最直接的路径**：把已被别的 GOV 辖的 Region 原样再授一次，零拒绝 |
| 10 | `GovDemand`（逐格行政需求） | `simos-gov/.../gov/GovDemand.java:74-95`；去重只在**同 unit 内**：`:92-93`（`putIfAbsent`，注释"重叠 Region 的同一格需求相同…只保证首次出现的键序"） | 按本级 jurisdiction 的 Region 逐 hex 算治安/文书需求 | **允许** ⇒ 同一 hex 被两个 GOV **各算一份编制需求**（行政成本重复，跨 GOV 不去重） |
| 11 | `sd.Nation.homeRegion` + `RegionMeta.tag = "nation:<id>"` | `simos-sd/.../sd/model/Nation.java:19`（`homeRegion` 字段）、`:26-36`（只守自己形状）；命令期校验 `simos-sd/.../sd/spi/CreateNationHandler.java:65-72`（只判 tag 前缀）；常量 `simos-sd/.../sd/spi/NationTag.java:13` | **政治实体** → 一个带 `nation:` tag 的 Region（另一套归属，与 GOV 不是同一条轴） | **允许**：只校验 tag，不校验与任何 GOV 辖区的互斥 |
| 12 | `map.City.region` | `simos-map/.../map/City.java:16-17`（"**允许为 null** = 这座城不在任何区域内…**合法状态**"）、`:30`（record 组件） | 城 → Region（**单值**，不能表达双归属） | 单值：不表达"两 GOV"，但可表达"无归属"；与 GOV 无任何关联 |
| 13 | `SocialCity.region` | `simos-social/.../social/city/SocialCity.java:23-24`（`Optional.empty()` = 合法）、`:41`、`:53-54`（构造期只判非 null） | 同上（social 侧形制） | 同上。读它的只有省籍/播种/GUI：`RegionSeedPlan.java:422`、`ProvinceAssignCitiesPlan.java:133,185`、`ApiViews.java:570-574` |
| 14 | `RegionMeta.annexedBy` | `simos-map/.../map/region/RegionMeta.java:8` | **自由字符串**（不是 `RegionId`） | 无语义读取（同批报告 Q1 已核）⇒ 不构成限制 |
| 15 | `economy.Government.nationRef` | `simos-economy/.../economy/model/Government.java:40-46`（record），字段语义 `:30` | "国家/辖区引用"，当前世界级最小政府取 `"world"` | 与 `Region`/GOV 之间**零映射**（`EconomyRegisterGovernmentHandler` 的 `nationRef` 注释自述"本命令不解释国家语义"）⇒ 不构成限制 |
| 16 | 读侧「最顶层区域」 | `simos-map/.../map/resolve/MapResolver.java:176-203`（**定义序**排列，**末位 = 最顶层**） | **只改排列、不改集合** | 允许；且只有 GUI 单格详情在用（`simos-app/.../app/gui/GuiServer.java:1162`） |
| 17 | webui GOV 辖区图层 | `simos-app/src/main/resources/webui/map.js:312-365` | 逐 GOV 画各自 jurisdiction 的 hex 并集 | 允许：**同一 GOV 内**按 `q_r` 去重（`:343-360`），**跨 GOV 不去重、不告警** ⇒ 重叠格被重复绘制 |

**Q1 的直接回答**：上表 17 条里，**没有任何一条**在写入期拒绝「同一 hex 落入两个 GOV 的辖区」。
要出现这个状态，只需满足三条中的任意一条组合：
① 一个 hex 同时属于 Region A 与 Region B（几何层允许，`RegionIndex.java:42-56`）；
② A ∈ GOV1 的 `jurisdiction.keySet()`、B ∈ GOV2 的（写口允许，`UnitOperations.java:705-715`）；
③ 或 A = B 被两个 GOV 同时纳入管辖（**连几何重叠都不需要**，一条 `unit.SetJurisdiction` 就够）。

---

## 3. Q2 — 「禁止重叠」要落到哪一层才**关得住**

### 3.1 今天存在的互斥/归属断言（全部，逐条 file:line）

| 装置 | file:line | 作用域 | 关得住吗 |
|---|---|---|---|
| Z7a 世界装配断言 | `simos-app/.../tools/write/GovWorldBootstrap.java:232-237`（`Collections.disjoint(provinceRegion.hexes(), capitalRegion.hexes())` 不成立即 `IllegalStateException`） | **只保 small-world 那 2 个省级 Region** | ❌ 只覆盖一个世界的两个区域；命令面任何一条都能绕过 |
| Z7a 座位在本辖区 | `GovWorldBootstrap.java:238-245` | 同上 | ❌ 同上 |
| 互斥的来源（手写 hex 集） | `simos-app/.../app/world/SmallWorld.java:437-455`（`REGION_ID` 18 格 + `CAPITAL_PROVINCE_ID` 1 格）；类注 `:66`、`:82-83` | 同上 | ❌ 是"世界造得刚好不重叠"，不是校验 |
| 迁都的 best-effort 独占 | `simos-app/.../app/gov/MoveCapitalPlan.java:191-193`（"**重叠虽是合法状态，但首都区应独占其格**"→ 把目标 hex 从其它所有 Region 移除） | 只此一条工具、只这一个格 | ⚠️ 唯一在通用命令里向"独占"靠的写口，但**只是尽力**且只针对首都格 |
| 省界重叠**软**门 | `simos-app/.../tools/write/ProvinceApplyPlan.java:52-63`（类注）、`:776-779`（`Gate.OVERLAP_OVERRIDE_REQUIRED`）；工具侧 `ProvinceApplyTool.java:274-275`、`:538-552`、`:204` | `simos.province.apply` | ❌ 命中重叠**默认拒**，但 `overrideOverlaps=true` 即可继续（注释："区域重叠 ≠ 省籍"） |
| 跨切片守卫机制（**唯一**实现） | `simos-app/.../sd/guard/RegionDeleteGuard.java:43-70`；注册/调用：`simos-core/.../core/CoreSimos.java:179`、`simos-core/.../core/command/CommandBus.java:396,680`；接口 `simos-util/.../util/spi/MutationGuard.java` | 只拦 `map.DeleteRegion` 且目标是 **nation tag / `Nation.homeRegion`** 的区域 | ❌ **完全不看 unit/GOV**；但这是**唯一现成的跨切片守卫形状** |
| 区域删除的悬空引用 | `JurisdictionDailyTax.java:562-563`（`GapKind.REGION_MISSING`）、`GovScope.java:110-111`（跳过）、`GovDemand.java:80-81`（跳过） | 只**事后记缺口/跳过** | ❌ 不阻止；且守卫只管 nation（见上） |

**全仓 `MutationGuard` 实现数 = 1**（`grep -rln "implements MutationGuard" --include=*.java simos-*/src/main | grep -v /target/` → 仅 `RegionDeleteGuard.java`）。

### 3.2 三个候选落点（并列，不裁定）

**L1 领域层（unit 域，最小）**：`UnitOperations.setJurisdiction`（`UnitOperations.java:686-732`，在 `:705-715` 之后加校验）
—— 它有 `UnitState`（全部单位的 jurisdiction）与 `GameMap`（Region 几何），**两个输入都在签名里**；handler 侧
`SetJurisdictionHandler.java:58-105` 手里更是整个 `SimulationState`。
- 关得住：`unit.SetJurisdiction` 这一条（"把已被别人辖的 Region 再授一次"）。
- 关不住：Region 几何**事后**变化造成的重叠（那是 map 命令）。
- 注意：判据必须写成"hex 集相交"（而不是"RegionId 相同"），因为 `Region A ⊃ Region B` 这种**包含**同样产生重叠
  （`simos.map.overlaps` 已能判 `aContainsB`/`bContainsA`，`MapOverlapsTool.java:24-35,43`）。

**L2 领域层（map 域）**：给 `RegionOperations` 加"与已有区域相交就拒"。
- **不该这么做**：① 与用户自己的 M8-U1 裁定直接冲突（`RegionOperations.java:30-31`、`RegionIndex.java:14-16` 逐字引用了
  用户原话「hex 只是地形块，应当**兼容多种从属**」）；② map **编译期不认识 GOV**（铁律 3；`RegionOperations.java:179-180`
  自述"jurisdiction / 城市 region / 税率 / 编制等跟随重算由 app 组合根用 `CommandBus.submitBatch` 协调"）。
- 也就是说：几何层的多对多是**设计**，不是缺口；新裁定要禁的是**同层 GOV 辖区互斥**，不是"hex 不能属于两个 Region"。

**L3 组合根守卫（跨切片，最贴合"关得住"）**：新增一个 `MutationGuard`（照 `RegionDeleteGuard.java:23-70` 的形制，
`CoreSimos.java:179` 注册），拦 `map.CreateRegion` / `map.UpdateRegion` / `map.MergeRegions` / `map.ReassignHexes` /
`map.SplitRegion` + `unit.SetJurisdiction`：读 unit 的 jurisdiction → 展开成 hex 集 → 与目标 Region 的 hex 集求交 →
非空即拒。这是**唯一能同时看见 map 与 unit** 的位置（铁律 3/4 的结构性结论）。
- 代价：守卫是**命令级**（`CommandBus` 每次提交都过），必须在"命令序列中途才产生重叠"的批量场景（`submitBatch`）里也想清楚；
- `GovWorldBootstrap.java:232-237` 那类世界装配断言可以保留，但它**不足以**关住命令面。

**L4 只保世界装配（现状形态）**：`GovWorldBootstrap.java:232-237`。任何命令都能绕；且只保 small-world。

### 3.3 漏网路径全列（哪个命令能让两个 GOV 同辖一格）

**A. 直接路径（把 Region 授给第二个 GOV）**

1. `unit.SetJurisdiction` — `UnitOperations.java:705-715`（只判区域存在）+ `SetJurisdictionHandler.java:96-105`；工具 `UnitSetJurisdictionTool.java:15`。
2. `sd.CreateNation` — `CreateNationHandler.java:65-72`（只判 `nation:` tag，不判 GOV）⇒ 能让"政治归属"与"行政归属"落在两个不同的 GOV 辖区上。

**B. 间接路径（让两个已被不同 GOV 管辖的 Region 产生 hex 相交）**

3. `map.CreateRegion` — `RegionOperations.java:79-104`；唯一校验 `:89` → `:479-492`（非空 + 在图上），`:477` 明说不校验相交。
4. `map.UpdateRegion` — `RegionOperations.java:119-152`；类注 `:110`"改成与别的区域重叠**不报错**"。
5. `map.MergeRegions` — `RegionOperations.java:186-222`：并集写回目标（`:195-206`），**不检查第三区域**是否也含这些格。
6. `map.ReassignHexes` — `RegionOperations.java:357-446`：只从**显式列出的 sources** 删（`:400-415`），目标加格（`:416-418`）；
   未列入 sources 的第三区域**仍持有这些格** ⇒ 重叠原样保留甚至扩大（类注 `:353` 自述"不猜重叠里的'真正归属'"）。
7. `map.SplitRegion` — `RegionOperations.java:233-347`：只查 **parts 内部**相交（`:272-276`），不查 parts 与**其它既有 Region** 相交。
8. `simos.province.apply`（组合工具）— `ProvinceApplyPlan.java:776-779` + `ProvinceApplyTool.java:204`：重叠可 `overrideOverlaps=true` 强行落盘；
   且它落的每省 GOV + 中央 GOV 的 jurisdiction 也走 `unit.SetJurisdiction`（类注 §"固定批序"第 5 条）。

**C. 绕开前置校验的通用口**

9. `simos.command.submit`（裸信封，`CommandSubmitTool.java:36`）——工具层前置校验对它**无效**（`UnitSetJurisdictionTool.java:15`
   逐字："工具层不做前置校验（那份校验能被 `simos.command.submit` 绕过 ⇒ 是装饰）"）⇒ **只有域层/守卫校验才算数**。

**D. 删除造成的悬空（相关缺口，非重叠）**

10. `map.DeleteRegion` — 守卫只拦 nation tag / `homeRegion`（`RegionDeleteGuard.java:43-70`），**不拦"某 GOV 的 jurisdiction 里有它"**
    ⇒ 删掉后 jurisdiction 留着悬空 RegionId：税侧记 `GapKind.REGION_MISSING`（`JurisdictionDailyTax.java:562-563`）、
    `GovScope` / `GovDemand` 跳过（`GovScope.java:110-111`、`GovDemand.java:80-81`）。

**非漏网（已核，别记错）**：
- `map.RandomizeRegion` 只改**地形**，不动 Region 的 hex 集（`RandomizeRegionHandler.java:21-38`、`RandomizeOperations.java:49-64`）；
- `simos.region.seed` 往**已存在**的 Region 里播人口/城市/经济，不建 Region（`RegionSeedPlan.java:104,380`）；
- `WorldgenInitializeTool` 只发 `map.UpdateRegion` 且**只换 tag**（原样回填 color/description/annexedBy，`WorldgenInitializeTool.java:1011-1022`），不发 hexes；
- Region 的生产构造点只有 3 处：`SmallWorld.java:442,450`、`RegionOperations` 的 create/update/split（`:91,139,294,331`）、`Region.java:48` 工厂。

---

## 4. Q3 — 「三不管地带」今天有没有表示

### 4.1 **零表示**（这是本报告最硬的一条）

检索写法与结果（HEAD `5ae583e1`）：

```
grep -rni "三不管|unruled|terra nullius|ungoverned|unclaimed" --include=*.java --include=*.md simos-*/src docs/superpowers/status
→ 仅 2 条无关命中：simos-gov/src/test/.../GovCommandHandlersTest.java:469（局部变量名 noGov）、
  simos-sd/src/test/.../CreateDecisionMakerHandlerTest.java:134（测试方法名）
grep -rln "三不管" --include=*.md . | grep -v "\.claude/worktrees"  → 0 命中
```

具体缺的是四样：

1. **判定**：没有任何"这个 hex 不被任何 GOV 管"的派生函数。`GovScope` 只从 GOV **正向**推 hex（`GovScope.java:100-122`），
   `GovTerritory.nominalRegions` 只从 GOV 子树**正向**收 Region（`GovTerritory.java:48-82`）——**没有反向索引**。
2. **读口**：GUI/MCP 都读不到。`GovTerritory` 与 `NationSummary` **生产零调用**（详见 §7.2）。
3. **日志/缺口**：`JurisdictionDailyTax.GapKind` 五个值无"无人管辖"（`:561-572`：`REGION_MISSING` / `NO_POSITION` /
   `NO_GOVERNMENT_HOUSEHOLD` / `TREASURY_ACCOUNT_MISSING` / `HOUSEHOLD_ACCOUNT_MISSING`）。
4. **状态**：没有"三不管"标记位（也不该有——见下），因为它是**派生量**：需要"全图 hex 集 − ⋃所有 GOV jurisdiction 的 hex"。

**能读到的只有"几何上无 Region"**（这是唯一现成的近似）：

- `RegionIndex.regionOf(c)` 返回**空列表**、`hasRegion(c)` 为 false（`RegionIndex.java:58-67`）；
- `MapResolver.regionOfHex` 无归属返回空列表（`MapResolver.java:185-190`）；
- `City.region == null` / `SocialCity.region.isEmpty()`（`City.java:16-17`、`SocialCity.java:23-24`）。

⇒ 但"无 Region" ≠ "无 GOV"：一个 hex 可以**属于某个 Region 而该 Region 不在任何 GOV 的 jurisdiction 里**（那就是三不管），
也可以**被两个 GOV 管**（重叠）——两者都读不出来。

### 4.2 一个没有 Region 的 hex 今天怎么走（逐条 file:line）

| 面 | 走法 | 证据 |
|---|---|---|
| 辖区日税 | **完全不经过**：`collect()` 只遍历 units → 有 GOV 读数者 → jurisdiction → region → hex，**不枚举全图** | `JurisdictionDailyTax.java:162-166`（无 GOV 读数 ⇒ 整单位跳过）、`:178-198`（无 jurisdiction / rate=0 ⇒ 跳过）、`:314-327`（只走 `existingRegions` 的 hex） |
| 缺口记录 | **不记**：无 Region 的 hex 不产生任何 `Gap` | `GapKind` 五值见 `JurisdictionDailyTax.java:561-572` |
| 行政需求 | **无需求**：`GovDemand.of` 只遍历 jurisdiction 的 region | `GovDemand.java:74-95`（Region 查无 ⇒ 跳过，`:80-81`） |
| 市场 | **照常**：市场是**逐 hex** 的 `EconomyData.markets` 行；`economy.SetMarketPrice` 建行时明确"**不校验该格在图上**…市场可以在尚未播种的格上先建" | `EconomySetMarketPriceHandler.java:31-38` |
| 市场区归属 | **照常有**：市场区由城市节点 + 半径现算，一个 hex 归**最近节点**（"一个 hex 恰属一个区"），与行政区无关 | `MarketTopology.java:24-37`；装配 `MarketTopologyBook.java:141-170` |
| 城市 | **合法**：可以落在无归属格 | `City.java:16-17`、`SocialCity.java:23-24` |
| GOV 决策人视野 | **看不见**：GOV 的 map/social 授权面只按自己 jurisdiction 的 Region 展开 | `GovScope.java:105-122`、`:134-145` |
| 迁都 | 需要目标城 `region == capitalRegionId`，否则拒 | `MoveCapitalPlan.java:248` |

⇒ **"三不管"今天不是"一种状态"，而是"一串静默的缺席"**：不征税、不算需求、不给任何 GOV 看见，但市场照常运转。
用户第 ② 句"三不管地带的市场行为完全自由"在**今天已经成立**——但成立的理由不是"系统认出了三不管"，
而是市场规则**本来就没挂在 GOV 上**（见 §5）。这两者的区别在将来接线时才致命。

---

## 5. Q4 — 「三不管 ⇒ 市场行为完全自由」：今天哪些市场约束依赖 GOV

| 约束 | 粒度 | 需要 GOV 存在吗 | 无 GOV 的 hex 今天的行为 | file:line |
|---|---|---|---|---|
| **长期税**（粮 + 银两维） | **per Region**（`jurisdiction.taxRatePerMilleByRegion`）；效率 per unit | **需要**：`efficiencyPerMilleByUnit` 缺键 = 没 GOV 读数 ⇒ **整单位跳过、不征**（"不读退役字段、不补 0"） | **自动失效**（不抛、不默认值、连缺口都不记） | `JurisdictionDailyTax.java:162-166`、`:178-198`、`:314-427`；效率表构建 `PopulationEconomyTimeParticipant.java:1277-1300` |
| **一次性抽取** `simos.unit.levyRegion` | per Region（须在 jurisdiction key 集里） | **需要**：无 jurisdiction / regionId 不在 key 集 ⇒ 具名拒 | 抽不到（**拒**，不是静默） | `LevyRegionPlan.java:147-156`；上限来源 `:166-170` |
| **市场税费 / 配额 / 限价 / 开闭市**（`MarketRegulation`：`tariffPerUnit` / `quotaPerWindow` / `referencePrices` / `bidPerMille` / `askPerMille` / `open`） | **per 市场区**（`anchor` 所在区，"按区施加一次"） | **完全不需要**——**生产路径零生产者** | 与 GOV **无关**：既不 per-GOV 也不 per-区域（走默认实例） | 类与语义 `MarketRegulation.java:16-17`（"本批只作为逐轮瞬态…**GM 命令面/落盘是后续批次**"）、`:41-43`、`:82-84`、`:91-95`、`:102-104`、`:107-115`、`:121-169`；**生产结算** `EconomySettlement.java:1660-1664`（`MarketRegulation.defaultsFor(markets)`，注释"跨市场区/自定义制度由后续批次经 GM 命令面注入"）；**读口** `MarketReadout.java:185`（`MarketRegulation.none()`）；全仓 `new MarketRegulation(` 只有 1 处 = `defaults()` 自身（`:83`） |
| **市场准入**（谁能生成买卖单） | 排除集（家户级） | **部分需要**：两条来源 ① `base.governments()` 的国库户（**GOV 专属**）；② 调用方传入的 `Unit.households()` 并集（**单位级**，GOV 也含在内） | 有 GOV 登记 ⇒ 其国库户**退出商品市场**（买卖单都不生成）；无 GOV 登记 ⇒ 该来源为空 | 并集 `EconomySettlement.java:8431-8443` + `:8412-8425`（`governmentTreasuryHouseholds`）、调用点 `:769-771`；消费 `MarketSettlement.java:1308`、`:4658`、`:4759`；app 注入 `PopulationEconomyTimeParticipant.java:595` + `:1064-1075`（`unitHouseholdExclusions`）；投影入口 `EconomyDayStepper.java:203-218` |
| **周期铸币 / 发债** | per Government | **需要**政府登记（`Government.seignioragePerCycle` > 0） | 无 GOV ⇒ 不铸币、不发债 | `GovernmentSeigniorage.java:21-33`（触发日/金额/账户）；登记命令 `EconomyRegisterGovernmentHandler.java`（`seignioragePerCycle` 缺省 0） |
| **运输费率 / 商人折扣** | per lane | **不需要** | 与 GOV 无关 | `MarketTopologyBook.java:136`、`:200`（`TransportTariff.probeDefaults()`）；`MerchantPolicy` 由 `MerchantSettlement` 从商号现算（`MerchantSettlement.java:584-585`） |

**Q4 的直接回答**：

1. 今天**唯一**"GOV 存在 ⇒ 市场行为被改"的实质点是**市场准入排除集**（政府国库户不得生成买卖单）；
2. **税/抽取**是"财政抽取"，不是"市场规则"——它们**要求 GOV 存在**（无 GOV ⇒ 不征/拒）；
3. **税费/配额/限价/开闭市**这套"市场总调控"**代码在、生产路径不在**（零生产者）——所以"三不管 ⇒ 市场完全自由"
   今天**无条件成立**（连"有 GOV 的地方"也一样自由）；
4. ⇒ 一旦 `MarketRegulation` 接线，必须同时规定"**无 GOV 区 = `MarketRegulation.none()`/默认实例**"，否则会出现
   "三不管区反而被某个区的调控误伤"或"默认值取代自由"这类与用户第 ② 句相悖的形态。今天**没有任何代码**做这个判断。

---

## 6. Q5 — 重叠下的税收语义：**两种裁定**分别要改哪几行（只列不改）

现状（共同前提，已核）：`JurisdictionDailyTax.collect` 对每个 unit → 每个 rate>0 的 Region → 该 Region 每个 hex →
该 hex 每个家户 **各算各征**（`:314-427`），**只排除本 GOV 自己的国库户**（`:321-323`），**无跨 GOV 判重、无顶层优先**；
税侧的先验顺序是"units 按 `UnitId` 升序"（`:129-130`），所以重叠时是**按 id 先后依次征**，后者见前者税后余额（类注 `:73` 逐字）。
`GovDemand` 的 `putIfAbsent`（`:92-93`）只在**同一个 GOV 自己的**需求表里去重，跨 GOV 不去重。

### 裁定 A — 保留重叠，只做去重 / 顶层优先（= `inventory:70/192` 的原口径）

| 要动的地方 | file:line | 说明 |
|---|---|---|
| 税累加循环 | `JurisdictionDailyTax.java:314-427`（键集 `:193-198`、累加器 `:352-361`、落账 `:378-394`） | 需在 region 循环之上引入"同一 `(hex, household)` 只征一次"的判重集，或"只由最顶层 Region 的 GOV 征" |
| "最顶层"的定义 | 现成读口约定在 `MapResolver.java:176-203`（定义序末位），**税里一次都没用** | 若选顶层优先，需把同一约定搬进税路径（两者都在 `simos-app`，可行；但"定义序"是 map 的插入序，语义上是否等于"行政上级"需另裁） |
| 行政需求去重 | `GovDemand.java:74-95`（尤其 `:92-93`）；调用方 `GovApplyStaffingTool.java:244-265`、`PopulationEconomyTimeParticipant.java:1286` | 跨 GOV 若也要"一 hex 一份需求"，判重必须上移到调用方（`GovDemand` 是 per-unit 纯函数，看不见别的 GOV） |
| 一次性抽取 | `LevyRegionPlan.java:147-170` | 两个 GOV 今天可各抽同一 hex 一次（各受自己 cap 约束）；是否也算重复需一并裁定 |
| 读口 | `NationSummary.java:120-155`（Region 并集 + hex 并集，人口按 hex 去重）、`GovTerritory.java:48-82` | 已天然"折叠"重复（Set 语义）⇒ 读不出"谁还声称它"，但不会重复计数；**若要求读口暴露冲突，需另加** |
| 文档 | `inventory:192`（"是否加互斥不变式或跨 GOV 去重"）从**待裁定**改为**已裁定=去重** | 追加，不改旧行 |

### 裁定 B — 禁止重叠，违反即拒

| 要动的地方 | file:line | 说明 |
|---|---|---|
| **最小落点（unit 域）** | `UnitOperations.java:686-732`（在 `:705-715` 之后加跨 unit 校验）+ `SetJurisdictionHandler.java:58-105`（手里有整个 `SimulationState` ⇒ `UnitState` + `GameMap`） | 判据必须是 **hex 集相交**（含"包含"），不能只比 `RegionId` |
| **几何变化造成的重叠（组合根守卫）** | 新增 `MutationGuard`，照 `RegionDeleteGuard.java:23-70` 形制，注册于 `CoreSimos.java:179`、由 `CommandBus.java:396,680` 调用；拦 `map.CreateRegion` / `UpdateRegion` / `MergeRegions` / `ReassignHexes` / `SplitRegion` + `unit.SetJurisdiction` | 因为 map 域不许认识 GOV（铁律 3、`RegionOperations.java:179-180`），跨切片校验只能在组合根 |
| 复用现成判定 | `MapOverlapsTool.java:24-35,43`（`equal` / `aContainsB` / `bContainsA` / `partial`）——算法是 hex 倒排、非 n² 区域对枚举 | 可直接抽成共享助手兼作拒绝依据 |
| **不要动的地方** | `RegionOperations.java:30-31`、`:477`；`RegionIndex.java:14-16` | 改这里 = 推翻用户 M8-U1「hex 只是地形块，兼容多种从属」⇒ 几何层多对多是设计而非缺口 |
| 世界装配断言 | `GovWorldBootstrap.java:232-237`、`238-245` | 可保留（对 small-world 仍是 fail-closed 钉子），但**不足以**关住命令面；裁定 B 落地后它对 small-world 变成冗余 |
| **若"重叠 ⇒ 三不管"（原话第三条出路）** | 需要新增：派生判定（全图 hex − ⋃jurisdiction hex）+ 读口 + 至少一条日志；可能的 `GapKind` 扩展位在 `JurisdictionDailyTax.java:561-572` | 今天**全无**（§4）⇒ 这是一整块新功能，不是改几行 |

**两裁定共同的代价（如实记）**：无论 A 还是 B，都会动到 `JurisdictionDailyTax` 的**数值行为**——
A 会改变重叠区的实收税与逐户税额；B 会让既有世界（若已存在重叠，见 §10 未核项）**拒绝落盘**。
两者都需要 `run7`/真档重跑读数才能判定影响面（本报告未跑）。

---

## 7. Q6 — 行政区层级与全疆域

### 7.1 层级今天怎么表达

- **构件**：`GovernmentFormation.superiorGov`（`GovernmentFormation.java:63`，`Optional<UnitId>`，空 = 层级根）+ `GovernmentLevel`
  （`:64`，只有 `CENTRAL`/`PROVINCE` 两个值，`GovernmentLevel.java:9-16`）。**只有两级词表**，没有第三级（县/乡）。
- **本级直辖** = `Unit.jurisdiction`（`Jurisdiction.java:13-14`）；**授权只认它**：`GovScope.java:24-33`（类注逐字：
  "**这里只算"直辖"**：中央决策人读不到省的数据，除非中央自己的 `jurisdiction` 里就有那个 Region"）、`:93-97`、`:100-122`。
- **"中央 = 下属行政区 + 直辖区"的现成实现** = `GovTerritory.nominalRegions(units, rootGovId)`
  （`simos-app/.../app/gov/GovTerritory.java:48-82`）：沿 `superiorGov` 向下收整棵 GOV 子树的 `jurisdiction` Region **并集**
  （含起点自己），环由访问集兜底，悬空上级跳过；返回 `Set<RegionId>`。
  类注 `:22-34` 逐字："**只读派生，绝不进任何授权判定**…只给 GUI / 后续 `NationSummary` 显示用，**不得**被 `GovScope`、
  `AdjudicateTick` 或任何资源判定调用"。
- **另一份同形派生**：`NationSummary.of`（`NationSummary.java:120-155`），按 `GovernmentLevel.CENTRAL` 根逐个汇总
  （`nominalRegions` + 覆盖 hex 并集上的人口，`:36-37`）。

### 7.2 关键事实：这两份派生**生产零调用**

```
grep -rn "GovTerritory"  --include=*.java . | grep -v /target/ | grep -v "\.claude/worktrees" | grep -v "GovTerritory.java"
→ simos-app/.../access/GovScope.java:33（**只是类注引用**）、simos-app/src/test/.../GovTerritoryTest.java（多处）
grep -rn "NationSummary" --include=*.java . | grep -v /target/ | grep -v "\.claude/worktrees" | grep -v "NationSummary.java"
→ GovScope.java:33（注释）、GovTerritory.java:24（注释）、simos-sd/.../model/Army.java:14（注释）、
  simos-app/src/test/.../NationSummaryTest.java（多处）
```

⇒ **"名义全境"今天只存在于类与测试里**：GUI、MCP、`/api/*` 都没有它。
今天真正暴露给读口的只有**每个 unit 的直辖 jurisdiction**：

- `/api/units` → `ApiViews.java:5003-5004`（`jurisdiction` 节点）+ `:5146-5156`（`unitJurisdictionView`：regions→税率 + 三个 levy cap + 退役字段）；
- `simos.gov.info` 只在**告警视图**里用它展开 hex（`GovInfoTool.java:731-747`）；
- webui 的 GOV 辖区图层逐 GOV 画各自辖区（`map.js:312-365`）。

### 7.3 能不能算全疆域？—— **不能**

- 全仓没有"所有 GOV 辖区并集 / 全图 hex 覆盖率 / 未被任何 GOV 覆盖的 hex"：
  `grep -rni "全疆域|疆域|领土|coverage" --include=*.java simos-*/src/main` 的命中全部与辖区无关
  （`grep -v /target/`；命中项是 `securityCoveragePerMille` / `grainCoverageDays` / `injectionCoverage` 等同形词）。
- `MapOverlapsTool` 能回答"**两两**区域重叠多少/是否包含"（`MapOverlapsTool.java:24-35,43`，GM-only），
  但**不回答覆盖率**，也不与 GOV 关联。
- `GovTerritory` / `NationSummary` 能回答"**某个** GOV 的名义全境 Region 集合"，但 ① 生产零调用；② 返回 Region 集，
  要 hex 级覆盖率还得自己展开；③ **被两个 GOV 同时声称的 Region 在并集里被折叠成一个**，读不出"还有谁声称它"。

### 7.4 重叠在层级里的额外后果

- **父子 GOV 同辖一格**（中央的直辖区与省的辖区重叠）⇒ 税重复（§6）且**层级本身不构成任何判重依据**
  （`superiorGov` 在税/授权路径里**一次都没被读**：`grep -n "superiorGov" --include=*.java simos-app/src/main` 只在
  `GovScope.java:126-131`（actor 国库路径授权）与 `GovTerritory` 的树遍历里出现）；
- 读口层级的唯一痕迹是 `MapResolver.regionOfHex` 的"**末位 = 最顶层**"（`MapResolver.java:176-203`）——**那与 GOV 层级无关**，
  是 map 的 Region 定义序。

---

## 8. Q7 — 缺口三档

### A. 有代码、可跑、可读、有验收痕迹

| 能力 | file:line |
|---|---|
| Region 多对多从属 + O(1) 反向索引（"全部保留、无谁赢"） | `RegionIndex.java:14-16, 42-56, 58-67` |
| 重叠检测读口 `simos.map.overlaps`（GM-only；`equal`/`aContainsB`/`bContainsA`/`partial`，确定性排序，单对上限 1024 格） | `MapOverlapsTool.java:24-35, 43` |
| map 命令面**显式允许**重叠并写入类注（防后来者误加校验） | `RegionOperations.java:30-31, 110, 353, 477` |
| 跨切片守卫**机制**（`MutationGuard`：注册 + 提交前逐条询问） | `CoreSimos.java:179`；`CommandBus.java:396, 680`；接口 `simos-util/.../util/spi/MutationGuard.java`；唯一实现 `RegionDeleteGuard.java:23-70`（拦 nation tag / `homeRegion` 的删除） |
| 辖区日税完整链路 + 五种具名缺口 | `JurisdictionDailyTax.java:113-448`；`GapKind` `:561-572`；`Gap` `:575-583` |
| "无 GOV ⇒ 不征"的 fail-closed 语义（无读数即跳过，不补 0、不读退役字段） | `JurisdictionDailyTax.java:162-166` |
| 名义全境派生 ⚠️（形状完整但**没接线**，见 §7.2） | `GovTerritory.java:22-34, 48-82`；`NationSummary.java:36-37, 120-155` |
| 迁都时的"首都区独占其格"best-effort | `MoveCapitalPlan.java:191-193` |
| 单格多重归属的读侧可见性（GUI 单格详情，定义序，末位=最顶层） | `MapResolver.java:176-203`；`GuiServer.java:1150-1180` |

### B. 半成品（有形状、语义不完整或只覆盖一半）

| 缺什么 | file:line |
|---|---|
| **市场总调控 `MarketRegulation` 有类型无生产者**：tariff/quota/参考价/开闭市全都只在"逐轮瞬态默认实例"上，生产结算走 `defaultsFor`、读口走 `none()`；类注自述 "GM 命令面/落盘是后续批次" | `MarketRegulation.java:16-17, 41-43, 82-115`；`EconomySettlement.java:1660-1664`；`MarketReadout.java:185` |
| **互斥只有 per-world 断言 + 一条 best-effort + 一个软门**，无通用不变式 | `GovWorldBootstrap.java:232-245`；`MoveCapitalPlan.java:191-193`；`ProvinceApplyPlan.java:776-779` |
| **行政需求去重只在同一个 GOV 内**（跨 GOV 不去重） | `GovDemand.java:92-93`（对比税侧 `JurisdictionDailyTax.java:314-427` 连同 GOV 内也不去重——按"一个 Region 一次"天然不重复） |
| **"最顶层"约定存在但税里没用** | `MapResolver.java:176-203` vs `JurisdictionDailyTax.java:314-427` |
| **`GovScope` 只算直辖**（这是**有意设计**，用户裁定 4）；缺口在**显示侧也没接线**：`GovTerritory`/`NationSummary` 零生产调用 | `GovScope.java:24-33, 46-50`；§7.2 检索结果 |
| 删 Region 的保护只覆盖 nation 归属，不覆盖 GOV 管辖 | `RegionDeleteGuard.java:43-70`；事后处理见 `JurisdictionDailyTax.java:562-563`、`GovScope.java:110-111`、`GovDemand.java:80-81` |

### C. 完全没有

1. **"三不管地带"（无任何 GOV 管辖）的任何表示**：判定 / 读口 / 日志 / `GapKind` / GUI 图层，全无（§4.1 检索写法与结果）；
2. **GOV 辖区互斥的通用不变式或守卫**：唯一 `MutationGuard` 只管 nation tag / `homeRegion`；
3. **全疆域覆盖率读数**（"哪些 hex 没有任何政府" / "哪些 hex 被两个政府管" = 全图视角）；
4. **跨 GOV 的税收判重 / 顶层优先**（现状 = 逐区域累加，`:314-427`；`inventory:70` 已登记）；
5. **"重叠 ⇒ 重划"的任何自动化**：`map.ReassignHexes`（`:357-446`）只做"从列出的源删、往目标加"的显式语义，
   没有"检测到重叠就自动重划/降级为三不管"的路径。

---

## 9. 矛盾点（新裁定 vs 现状；**并列事实，不替用户裁定**）

### A. 「禁止重叠」vs 现状「允许重叠 + 重复征税」

- 现状三句话都有代码：① map 允许重叠是**用户自己的 M8-U1 裁定**（`RegionIndex.java:14-16`、`RegionOperations.java:30-31`，
  逐字引用"hex 只是地形块，应当兼容多种从属…不存在'重叠时谁赢'"）；② 税在重叠下逐区域累加、无判重（`JurisdictionDailyTax.java:314-427`）；
  ③ 该缺口已登记待裁（`inventory:70, 192`）。
- ⇒ 新裁定「重叠辖区是不被允许的」若按字面执行，必须**先分清要禁的是哪一层**：
  - 禁 **map 几何**（Region 之间不得相交）⇒ 直接推翻 M8-U1，且要违反铁律 3（map 不认识 GOV）；
  - 禁 **同层 GOV 辖区**（一个 hex 不得被两个 GOV 管）⇒ 不必动 map；落点是 §3.2 的 L1 或 L3；
  - 二者**代码里今天没有任何区分**（`RegionIndex.java:21-22` 自述"本类不承载层次"）。
- 另有一个**同层判据的坑**：即使禁止了"两个 GOV 授同一个 RegionId"，`Region A ⊃ Region B` 这种**包含**（A 属于 GOV1、
  B 属于 GOV2）仍然让同一 hex 被两个 GOV 管；`MapOverlapsTool` 已能判包含（`MapOverlapsTool.java:24-35`），
  但 jurisdiction 层**没有任何包含检测**（`UnitOperations.java:705-715` 只比存在性）。

### B. 「三不管地带」**零表示**

用户原话把"三不管"当作重叠的**合法替代出路**（"要么就是重划、变成三不管地带"），但代码里：
既没有"无 GOV 管辖"的判定/读口/日志/缺口 kind（§4.1），也没有把"一个 hex 从所有 GOV 辖区移出"表达为一种**状态**的机制。
今天它只是"税静默不征 + 需求不算 + 市场照常"的一串缺席 ⇒ **无法把"三不管"与"忘了配 GOV"区分开**（后者在同一套机制下
同样静默：`JurisdictionDailyTax.java:162-166` 连缺口都不记）。

### C. 2026-10-23「D1 辖区互斥」vs 2026-10-08 新裁定：**方向一致，作用域不同**

| | D1（2026-10-23） | 本次新裁定（2026-10-08 原话） |
|---|---|---|
| 表述 | 「**D1 = 辖区互斥：中央座位不入省辖**（用户当选）。实施方式（独立 central 区域 vs 辖区逐 hex 排除）由 Z7 排查/设计定；**不采用**"政府家户一律免税"" | 「重叠辖区是不被允许的，一个地方只可能有一个行政政府」 |
| 落盘出处 | `docs/superpowers/specs/2026-10-23-gov-service-mode-design.md:379`；`docs/superpowers/reports/2026-10-23-fiscal-loop-investigation.md:19-70, 345`；`docs/superpowers/reports/2026-10-23-fiscal-loop-run7-vs-run6.md:104-108` | **本报告首次落盘**（§1 检索为 0 命中） |
| 落到代码 | Z7a：`SmallWorld` 拆出与省平级的 `capital-province`（`SmallWorld.java:437-455`）+ `GovWorldBootstrap.java:232-237` 互斥断言 + 中央 `SetJurisdiction` 只授 `__CAP`（`GovWorldBootstrap.java:362-380`） | —— 尚无 |
| 作用域 | **一个世界的一对辖区**（small-world 的中央座位 vs 省辖） | **通用规则**：任意两个 GOV、任意几何命令、任意时刻 |
| 缺口 | 不是缺口，是**已实施的规避** | 现状**完全未实现**（§3、§8.C.2） |
| 修过的实证 | run6：省税抽中央国库 **173 天**；run7 = **0**（`2026-10-23-fiscal-loop-run7-vs-run6.md:104-108`） | —— |

⇒ D1 是**用"这一处不重叠"让现状税收语义自洽**的规避；新裁定要求把"不许重叠"变成**系统规则**。
两者不冲突，但**新裁定的覆盖范围严格大于 D1**（D1 的断言只认 small-world 的 `REGION_ID`/`CAPITAL_PROVINCE_ID`，
换一个世界、换两个省、或任何一条 `map.*` 几何命令都能造出重叠）。

### D. 待用户确认的口径分叉（**最需要澄清的一条**）

同日（2026-10-08）另一条用户原话（同批报告 §1 逐字）是「行政区、市场区…**理论上不但可以重叠、包含，甚至可以半包含**」。
与本次原话「重叠辖区是不被允许的」并列时，只有两种读法能同时成立：

- **读法 1（跨层 vs 同层）**：前句说"行政区 ↔ 市场区"（两套不同轴）可以重叠；本句说"GOV ↔ GOV"（同一轴）不许重叠。
  ⇒ 两句互补，不矛盾。**本报告采信这一读法作工作假设**（§1）。
- **读法 2**：前句中的"行政区"泛指行政区之间 ⇒ 与本次原话**直接对立**，必须由用户澄清哪句作准。

代码今天**不区分**这两种读法（`map.Region` 不知道自己是"行政"还是"市场"轴；`MarketRegion` 是另一个类型，
见同批报告 §3 的对照表）。在澄清之前：把"禁止重叠"实现到 **map 几何层**（拒绝任意两个 Region 相交）会
**同时**违反读法 1 与用户 M8-U1 裁定；实现到 **GOV 辖区层**则与两种读法都不冲突。

---

## 10. 我没核到的（未验证清单，不得当成结论）

1. **没跑 Maven、没起服务**：全部结论是静态代码阅读。测试计数、`clean verify`、GUI 实际渲染、`run7`/真档读数**均未复核**。
2. **没读 live 世界库**（`test-world` / `~/Simos-18Lvt` / SQLite）：现实世界里**是否已经存在**重叠辖区、是否存在
   "无任何 GOV 管辖的 hex"、中央/省实际税率，**全未实测**。⇒ 裁定 B 的迁移影响面（会不会拒绝既有世界）**未知**。
3. **未逐条枚举全部已注册命令**：§3.3 的漏网清单来自 `simos-map/src/main/.../spi/`（10 个 handler）、`simos-unit/.../spi/`
   （16 个 handler）、app 组合工具（`ProvinceApply*`、`RegionSeed*`、`MoveCapital*`、`WorldgenInitialize*`、`Gov*` 目录下 40+ 文件）
   与「会写 jurisdiction / Region」的关键词检索**并集**；**没有**逐条核对 46 类工具面全表 ⇒ 可能有未列出的第三条路径。
4. **未逐行读**：`GovAbsorbUnitPlan`（关键词检索 `jurisdiction` = 0 命中，但未通读）、`GovExpandHouseholdTool:204-208, 385-397`
   （已核它**只读** jurisdiction 作招募来源）、`GovRecruitPlan:173`、`GovSelectExamineesPlan:228`（同款只读，未逐行确认无写）。
5. **`RegionMeta.annexedBy` 的读取方**：本报告直接采信同批报告 Q1 的结论（只有两处视图/回填读取），**未逐字重核**。
6. **`simos.province.divide` / `ProvinceDivider`**：已核它"只出建议、零写入"（`ProvinceDivider.java:22-30`）与重叠只作信息
   （`:56-58`），但**未核**其建议本身在多 Region 相交图上是否总能给出不重叠的省界（那是 task-4 的问题域）。
7. **`map.RandomizeRegion`**：已核它只改地形（`RandomizeOperations.java:49-64`），但**未核** `RegionRandomizer` 内部是否
   有改 Region 的副作用（只读了它的调用签名）。
8. **规模/性能**：若采用 §3.2 L3 的组合根守卫（每次提交全量展开 jurisdiction → hex 求交），在真档
   （59,223 格 + 多 GOV）的**成本未测**；`MapOverlapsTool` 的倒排算法可复用，但那是**读工具**路径。
9. **本轮未与 `task-4`/`task-8` 报告作者直接对齐**：§1/§9.D 对同日两条原话的分层读法是我基于两份派单书的推断，
   **未得到用户或控制方确认**。

---

## 11. 交账速览（15 条最关键的 file:line）

| # | 事实 | file:line |
|---|---|---|
| 1 | hex → Region 是**多对多**，"不存在重叠时谁赢"（M8-U1 用户裁定） | `simos-map/.../region/RegionIndex.java:14-16` |
| 2 | map 命令面**显式允许重叠**、无"相交就拒"校验 | `simos-map/.../ops/RegionOperations.java:30-31, 110, 477` |
| 3 | 全仓唯一的跨切片守卫只管 nation tag / `homeRegion` | `simos-sd/.../sd/guard/RegionDeleteGuard.java:43-70`；机制 `simos-core/.../CoreSimos.java:179` |
| 4 | jurisdiction 写口**只判区域存在**，不判别的 GOV | `simos-unit/.../ops/UnitOperations.java:705-715`；`simos-unit/.../spi/SetJurisdictionHandler.java:58-105` |
| 5 | 税在重叠下**逐区域累加、无判重** | `simos-app/.../app/time/JurisdictionDailyTax.java:314-427` |
| 6 | 无 GOV 读数 ⇒ **整单位跳过、不征**（无 GOV 的 hex 自动不征） | `JurisdictionDailyTax.java:162-166` |
| 7 | `GapKind` 五值**无"无人管辖"** | `JurisdictionDailyTax.java:561-572` |
| 8 | 行政需求去重只在**同 unit 内** | `simos-gov/.../gov/GovDemand.java:92-93` |
| 9 | GOV 授权 = **只算直辖**（本级 jurisdiction） | `simos-app/.../app/access/GovScope.java:93-97, 100-122` |
| 10 | **名义全境**（中央=下属+直属）派生存在但**生产零调用** | `simos-app/.../app/gov/GovTerritory.java:48-82`；`NationSummary.java:120-155` |
| 11 | "最顶层区域"只是**读侧排列**约定（定义序末位），税里没用 | `simos-map/.../resolve/MapResolver.java:176-203` |
| 12 | 市场总调控 `MarketRegulation` **类型在、生产零生产者** | `simos-economy/.../time/MarketRegulation.java:16-17, 82-115`；`EconomySettlement.java:1660-1664`；`MarketReadout.java:185` |
| 13 | 市场准入的**唯一 GOV 耦合** = 政府国库户退出市场 | `EconomySettlement.java:769-771, 8412-8443`；`MarketSettlement.java:1308, 4658, 4759`；`PopulationEconomyTimeParticipant.java:595, 1064-1075` |
| 14 | 重叠检测读口存在且能判包含（GM-only） | `simos-app/.../tools/read/MapOverlapsTool.java:24-35, 43` |
| 15 | 已登记待裁定：Region 互斥 / 重复征税去重 | `docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md:70, 192` |
