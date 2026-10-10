# 实现架构账本：G3-fix-1 —— 修两处真实 world 复测抓到的行为缺陷

- **责任区**：G3-fix-1（缺陷 1 = 多市场区世界 day38 硬崩；缺陷 2 = tool 门槛被冻结绕过、货照运运费照铸）。
- **输入证据**：`.superpowers/sdd/2026-10-10-g3-real-world-verify/impl-ledger.md` §3.3 / §3.6（先读）。
- **日期**：2026-10-10。**实现者**：办事子 Agent（只写生产代码、只到编译过；不写/跑测试、不 commit）。
- **允许写**：`simos-economy/src/main/**`、`simos-economy-api/src/main/**`、`simos-app/src/main/**`。
  **禁止碰**：任何 `src/test/**`、`pom.xml`、`docs/**`、`.superpowers/**`（本账本除外）、`AGENTS.md`。

---

## 1. 关键调查结论（file:line 证据 → 结论 → 影响）

### 1.1 缺陷 1：跨区路径缺"集合主体跳过"这一半

| # | 证据（改动前 file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `MarketSettlement.java:8187` 抛 `unresolvedMarketSubject` | 抛点**不在** `matchAcrossRegions`，而在**参与者构建** `participantsFor`（`:8101`）——区内/跨区**共用同一个参与集**；`matchAcrossRegions`（`:4590`）只消费已建好的槽位 | "跨区缺一半"不是两段代码，而是**同一个判据的第三种状态无人处理** |
| 2 | `MarketSettlement.java:8158-8210` 三分支 | ① `economicHouseholdOf` 非空 ⇒ 挂到家户；② 空但 `householdsOf` 非空 ⇒ `MARKET_SUBJECT_COLLECTIVE_SKIPPED`(TRACE) + skip；③ 两个都空 + 无正资产 ⇒ `MARKET_SUBJECT_EMPTY_UNIT`(WARN) + skip；**③ 两个都空 + 有正资产 ⇒ 具名抛** | 崩溃现场落在第 4 格：`householdsOf` **也**空了 |
| 3 | `SettlementIndex.java:523-544`（`householdsByUnit`） | `householdsOf(unit)` = 该 unit 名下劳动配额的家户 ∩ **现存家户行**（`householdEconomies.containsKey`），按 id 升序 | 一个"集体 unit"的成员户**全部消亡/被迁移删行**后，它的账户主体**真的不存在**——这是合法终态，不是坏数据 |
| 4 | `ModeMigrationSettlement.java:586-705`（`retireSource`） | 迁移把源户人口清空且无合同/资产残留 ⇒ **删掉家户行**（`shell=false` 路径）；有资产残留才留"壳户" | 删行是设计内的事实 ⇒ `householdsOf` 变空是**可达状态**，与拓扑无关 |
| 5 | `EconomySeeder.java:4081-4095` + `:4233-4256` + `RegimeOperators.java:89-102` | `householdWeaving` 不显式给 operator ⇒ `industry(...)` 缺省用 `RegimeOperators.defaultOperator(regime, industryId)` = **合成 `HOUSEHOLD:weave@<hex>`**；它**不是**家户行（`:3279-3280` 明文：weave 是聚合主体、账户主体 = 名下劳动家户集合） | 现场 `operator=HOUSEHOLD:weave@-1_2`、`operatorHouseholdRow=false`、`unit=unit-weave@-1_2-…` 三者互相印证：**合成聚合经营者 + 成员户已不在行表** |
| 6 | `git show 23e11653:…/MarketSettlement.java`（基线 jar 的源码，`:6181-6223`） | 基线里 `MARKET_SUBJECT_EMPTY_UNIT` / `MARKET_SUBJECT_COLLECTIVE_SKIPPED` / 具名抛**三分支逐字相同**，且 `23e11653` **已包含**引入它们的 `d3109166`（2026-10-23 裁定 B）与 `1bf41015` | ⇒ **不是本链引入**：本链只是让真实世界跑到了这个既有缺口上（多区拓扑 + 长跑把"成员户被删行"的窗口跑了出来） |
| 7 | `git diff --stat 23e11653..HEAD -- SettlementIndex.java ModeMigrationSettlement.java HouseholdRouting.java EconomicHouseholdResolver.java` | `SettlementIndex` / `HouseholdRouting` / `EconomicHouseholdResolver` **零改动**（`ModeMigrationSettlement` 只有 17/17 行的自债净额改动，不涉行删除） | 判据的**输入**（行表、家户行集合）未被本链改口径 |

**结论**：缺陷 1 = **既有缺口**（`23e11653` 即存在），不是本链引入。

★★ **与派单书假设不符之处（必须上报）**：派单书写"判断是跨区路径（`matchAcrossRegions` 一族）缺'集合主体跳过'这一半"。
诊断结果**不是这样**：`matchAcrossRegions`（`:4590`）只消费 `participantsFor` 建好的槽位，而抛点在 `participantsFor` 内
——**区内与跨区共用同一个参与者构建**。单区世界 A 的对照之所以"优雅跳过"，是因为那一刻
`householdsOf(unit)` **非空**（成员户还在行表里）⇒ 走 ② 分支；多区世界的 day38 那一刻它**空了** ⇒ 落到抛点。
⇒ 两边的差别是**行表事实**，不是拓扑分支。**同一个世界如果成员户被删光，单区世界一样会崩**。
判据只有一处、不分区内/跨区 ⇒ 修法落在这一处即对两条路径**同口径**生效（符合派单书"让跨区与区内同口径"的**目的**）。

★ **诚实边界**：我没有 A 世界的日志/状态（`/tmp/simos-g3-runA3.log` 已不存在），无法逐值证明 day38 那一刻
`householdsOf` 空掉的**具体成因**（成员户自然消亡 vs 迁移删行）；上面第 3/4 条只证明"两个成因都能让它空、且都是合法终态"。

### 1.2 缺陷 2：门槛判据两处不同口径 ⇒ 冻结可绕过

| # | 证据（file:line） | 结论 | 影响 |
|---|---|---|---|
| 1 | `MerchantCapacityPool.java:229`（`of` 装配）：`long toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L)` | 门槛预算 = **原始存量**（**不减冻结**） | 冻结全部 tool 的户在池里仍显示 `toolRemainingMilli=12000` |
| 2 | `EconomySettlement.java:8627-8653`（`consumeForLoss`）：`if (max(0, stock − frozen) < requested) return 0` | 实扣判据 = **可用量**（减冻结），T-fix 刚把这里改成 fail-closed | **两处口径不同** ⇒ 池说"够"，提交时说"不成立" |
| 3 | `MarketSettlement.java:6344`（`select`）→ `MerchantCapacityPool.java:528-532`：`if (!MerchantHaul.affordsRun(item.remainingToolMilli)) continue;` | 选择期门槛=池装配快照；`allocate>0` 才 `executeTrade` 继续 | 选择放行 ⇒ 成交成立 |
| 4 | `MarketSettlement.java:6505-6524`（铸 `CARRIER_FEE`）**先于** `:6565-6568`（`settleHaulRuns` 烧工具） | 运费腿**先铸**、工具**后烧**；烧失败**不回头** | **实测的 fail-open**：货走了、运费收了、工具没扣 |
| 5 | ★ 现场证据（`/tmp/simos-g3-runB.log`，本次实地 grep 到） | `:1229` 池 `toolMilli=12000`（day3 装配）；`:3521` `TOOL_SHORT_AT_COMMIT frozenMilli=12000 availableMilli=0 reason=tool-frozen`；`:1139/1141/1143` 该户在**同一市场轮**把 tool 卖给 displaced/rural-landlord/middle_peasant（`tr-3-76/78/80`，共 1,330 单位） | 成因判明：**该户同轮的 tool 卖单把 12,000 毫全部冻结**，池的预算快照看不到冻结 ⇒ 门槛在"运力分配"处被绕过 |
| 6 | `MarketSettlement.java:5433-5446`：`effectiveRoute = intraRegionFreightRoute(...)` 只在 `ctx.carrierPool.hasCapacityAt(sell.hex)` 时建 | 同区跨格（`route == null` 分支）也走 `select` | 区内跨格与跨区**同一处**受缺陷 2 影响（不是只影响跨区） |

**结论**：`MerchantHaul` 的 H-5「缺工具 ⇒ 该次跑商不成立」确实只在"烧工具"这一步 fail-closed，
**承运分配这一步没有同口径**。根因 = 装配时读**原始存量**、提交时读**可用量**（`max(0, stock − frozen)`）。

---

## 2. 实现架构（改动落点与数据流）

### 2.1 缺陷 1：参与者构建的第 4 种状态 ⇒ 具名跳过（不是静默、不是抛整轮）

- **落点**：`MarketSettlement.participantsFor`（`MarketSettlement.java:8160-8190`），在 `throw unresolvedMarketSubject(...)` **之前**插入第四分支。
- **判据（唯一拼写点：新私有方法 `isUnresolvedAggregateOperator`）**：operator **就是**该 unit 的产业在该制度下的
  **合成默认经营者**（`RegimeOperators.defaultOperator(industry.regime(), industry.id())`）**且它不是现存家户行**。
  - 为什么精确到这个判据：合成默认经营者**按构造**不是家户行（`RegimeOperators.defaultOperator` 只产 `ActorRef`，
    从不在 `householdEconomies` 里）；它只出现在"聚合经营"的产业上。**真实经营者**（组织者家户被显式 `withOperator` 换上）
    能解析到家户行，**走不到这里** ⇒ 真正的坏数据（经营者指着不存在的主体）**仍然具名抛**，fail-closed 不放宽。
  - regime 未登记（`defaultOperator` 抛 `IllegalArgumentException`）⇒ 判据**不成立**（捕获后返回 false）⇒ 仍抛。
- **行为**：发 `MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` **WARN**（`day/unit/operator/operatorHousehold/hex/industry/modeKey/usableAssets/laborAllocations/reason`）
  + `continue`。该 unit **不进参与者表**（与 ② 的跳过后参与者列表**逐值一致**：反正它进不去）。
- **为什么 WARN 不是 TRACE**：② 是"by design 不入市"（正常），第 4 种是"这个生产组织已经没有任何家户能收它的账"
  ——它是**真实异常**（组织崩了），必须可见（§一.9：业务拒绝 = INFO，契约故障 = ERROR；这里既不是拒绝也不是契约故障，
  取 WARN 并在文本里如实说明是"组织无主"）。**不降级 ERROR**：它不再中止整轮推进，也不代表跨切片一致性坏了。
- **为什么不选"补行 / 造家户"**：那正是 G3 复测里的 world 数据补丁（GM 补 19 行 + 19 空账）——
  派单书明令**不许**写进代码来掩盖问题；且造一个不存在的家户等于伪造账户主体（铁律 1/3）。

### 2.2 缺陷 2：池的工具门槛与提交门槛**同口径**（可用量 = `max(0, stock − frozen)`）

- **落点 1**：`MerchantCapacityPool.of(...)` 新增 **6 参重载**（多收 `frozenGoods`）；旧 5 参重载**逐字保留**为
  `of(..., quotes, Map.of())`（缺省 = 无冻结 ⇒ 既有调用点/夹具逐值不变，I-C2 缺省中性）。
- **落点 2**：装配处 `toolMilli = max(0, goods.tool − frozen.tool)` ⇒ 该值同时进入
  ① `MerchantCapacity.of(...)`（运力算式）② `Entry.remainingToolMilli`（**门槛预算**）。⇒ 池里
  `toolBlockedRuns` / `runsAffordable` 读数与"能不能真的烧得起"同源。
- **落点 3**：`EconomySettlement.java:1850-1856` 的调用点补传 `householdFrozenGoods`（`:940` 已有的活表）。
- **数据流**：`EconomySettlement`（装配，`:1850`）→ `MerchantCapacityPool`（池 + 门槛预算）→
  `MarketSettlement.select`（`:6344`，`allocation.allocatedMilli() <= 0` ⇒ `return 0L` 不成交）→
  `executeTrade`（`allocate == 0` ⇒ 连 `CARRIER_FEE` 都不铸）⇒ `settleHaulRuns` 不再遇到"冻结绕过"。
- **时序论证**：池装配（`:1850`）在 `commitFreezes`（`MarketSettlement.java:1583`，市场轮**内部**）**之前**，
  两者之间**没有任何**写冻结的代码 ⇒ 装配时读到的冻结 = `commitFreezes` 之后生效的冻结（同一份表、同一轮）。
  观测到的正是这种情形：`MERCHANT_CAPACITY_HOUSEHOLD … toolMilli=12000`（装配，冻结未生效）
  → 同轮卖单冻结 12,000 → 提交时 `availableMilli=0`。修后装配即读到 `available=0` ⇒ 该户**不进池**
  （`capacityMilli = labor + 0`；无劳动 ⇒ 0 运力 ⇒ 按既有 `:220-222` 规则不入池），`select` 不会给已冻结的 tool 放行。
- **为什么不是"在 select 里现读活表"**：`select` 与装配之间**没有**冻结写者（见上），现读与装配读**逐值等价**；
  而现读要把 `round` 的表塞进池对象（池是"一轮一份的派生量"，`MarketSettlement` 类注明确它不持可写状态），
  复杂度换不到一个字节的差别。⇒ 取"装配时同口径"，并在装配处把口径写明。

---

## 3. 关键判断（为什么这样改 / 为什么不另一条路）

1. **缺陷 1 不改成"静默 continue"**：派单书要求"具名、不静默、不抛整轮"；静默会把"生产组织无主"这条真实事实从日志里抹掉，
   而它正是破产/消亡/迁移后的第一个可观测信号。
2. **缺陷 1 不放宽到"任何解析不到就跳"**：那会把"经营者指着不存在的主体"这类**真坏数据**也吞掉
   （`docs/superpowers/specs/2026-10-23-weave-not-a-market-subject.md` §3.4 明文要求保留具名抛）。判据收紧到
   "**合成默认经营者**且无该家户行"⇒ 只覆盖"聚合经营 + 成员全无"这一种合法终态。
3. **缺陷 2 选"选择期即不放行"而不是"成交后回退运费腿"**：成交与承运在 `executeTrade` 内**同一步**完成
   （货腿 `:6437`、运费腿 `:6505`、烧工具 `:6568`），事后回退要拆掉已 `applyTransfer` 的货腿与钱腿
   ⇒ 违背"成交流程只有一条正向链"的既有结构。**在成交流程之前判死**是这里能做到的最强 fail-closed：
   分不到运力 ⇒ `executeTrade` 返回 0 ⇒ 既不成交、也不铸运费（与既有"运力不足 ⇒ 未运走部分不成交、不成债、不计价"完全同形）。
4. **缺陷 2 不动 `MerchantHaul` / `consumeForLoss` 的判据**：T-fix 刚把提交侧做成 fail-closed（`available < 一趟 ⇒ 一点也不烧`），
   该判据**正确且必须保留**（它是"绝不部分扣"的唯一守卫）；本批只把**上游**的选择期判据对齐，不碰下游。
5. **缺陷 2 不动 `MerchantCapacity` 的常量与算式**：工具维仍 1:1（`CAPACITY_MILLI_PER_TOOL_MILLI_PER_MILLE = 1000`），
   改的只是"读哪个量"（存量 → 可用量）——常量与标定不在本责任区。

---

## 4. 偏离记录

1. **缺陷 1 的判据比"跨区路径"这个说法更宽/更窄**：派单书写的是"跨区路径缺一半"，诊断发现
   **区内/跨区共用同一个参与者构建**，缺的是**第三种状态的判据**而不是一条独立的跨区代码。⇒ 修法落在
   `participantsFor`（对区内/跨区**同口径**生效），符合派单书"让跨区路径与区内路径**同口径**处理"的要求，
   但**不新建**跨区专属分支（新建会造出第二套判据，正是本仓反复禁止的"两处各写一遍"）。
2. **缺陷 2 的调用点签名变更**：`MerchantCapacityPool.of` 增 6 参重载（旧 5 参保留）。生产调用点 1 处
   （`EconomySettlement:1850`）；`src/test` 里的 8 处调用（`MarketSettlementFixtures` / `MerchantCapacityAcceptanceTest`）
   继续走旧重载 ⇒ **不碰测试、不改测试语义**。
3. **`MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` 是新事件名**（既有 `MARKET_SUBJECT_EMPTY_UNIT` 未复用）：
   两者语义不同（"从没产能"vs"有产能但组织无主"），复用会让日志再也答不出是哪一种。

---

## 5. 真实命令与结果

```text
$ tools/mvn-lock.sh -q spotless:apply
[spotless exit=0]          # 跑完 git status --porcelain 只有本批 3 个 M 文件 ⇒ 未误改他文件

$ tools/mvn-lock.sh -DskipTests compile
[INFO] Reactor Summary for SimulatorMosire 0.1.0-SNAPSHOT:
[INFO] EconomySimos ....................................... SUCCESS [  4.413 s]
[INFO] SimosApp ........................................... SUCCESS [  3.043 s]
[INFO] BUILD SUCCESS
[compile exit=0]           # 16/16 模块 SUCCESS

$ tools/mvn-lock.sh -o spotbugs:check -pl simos-economy     # 额外自检（不属派单门禁）
[INFO] BugInstance size is 0
[INFO] BUILD SUCCESS
[spotbugs exit=0]
```

**未跑**：`test` / `verify` / `package` / 真实 world（按派单书由控制方下一轮统一验收）。

### 改动文件清单（3 个，全在 `simos-economy/src/main/**`）

| 文件 | 改动（行号 = 改后） |
|---|---|
| `simos-economy/.../time/MarketSettlement.java` | `:50` +1 import；`:4233` 新判据 `isUnresolvedAggregateOperator`；`:8216-8221` 三分支注释改五分支配；`:8252` 新分支 `MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` WARN + `continue`（旧具名抛顺移到 `:8281`） |
| `simos-economy/.../time/MerchantCapacityPool.java` | `:150` 5 参 `of` 委托空表；`:173` 6 参 `of`（多收 `frozenGoods`）；`:203` `toolMilli = max(0, 存量 − 冻结)`（该值同时喂 `MerchantCapacity` 与 `Entry.remainingToolMilli`，见 `:422`）；javadoc |
| `simos-economy/.../time/EconomySettlement.java` | `:1860` 生产装配点补传 `householdFrozenGoods` + 口径注释 |

---

## 6. 会改变数值行为的清单（给测试代理当输入）

1. **缺陷 2（唯一改数值的改动）**：承运池装配的 `tool` 维 = **可用量**（`max(0, stock − frozen)`）而非原始存量。
   - 影响面：① `MerchantCapacity.capacityMilli`（= 劳动 + 可用工具）② 逐 hex 运力总量（`totalCapacityByHex`）
     ③ `sharePerMille` 与池内分配序（占比降序）④ `runsAffordable` / `toolBlockedRuns` 读数 ⑤ 跨格/跨区成交是否成立。
   - **方向**：只减不增（可用量 ≤ 存量）⇒ 只会**减少**跨格承运与运费，不会造出新的运力。
   - **实证判据**：本批现场那种"池 12,000 / 提交 0"的户，修后**不进池**（无劳动时）或**预算为 0**（有劳动时）
     ⇒ `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 归零、`toolBurnedMilli=0` 的 `MERCHANT_HAUL_RUN` 归零。
   - **可能收紧的读数**：`MERCHANT_CAPACITY_POOL_HEX` 的 `capacityMilli` / `allocatedMilli` 会变小；
     `MARKET_UNFILLED reason=LOGISTICS_CAPACITY` 会**变多**（原本靠"冻结的 tool"虚构出来的运力没了）。
2. **缺陷 1**：`participantsFor` 新增一条跳过 ⇒ 参与者列表**可能少一个 unit**（仅限"合成默认经营者 + 无家户行"的聚合 unit）。
   - **逐值不变量**：这些 unit 在**改动前**会抛，从来没有进过参与者表 ⇒ 对**跑得通的世界**（含全部既有测试）
     **参与者列表逐值不变**。新增的是 WARN 行（默认 INFO 级可见）。
3. **日志面**：新增 1 条 WARN（`MARKET_SUBJECT_COLLECTIVE_UNRESOLVED`）；`MERCHANT_CAPACITY_HOUSEHOLD` 的
   `toolMilli` / `remainingToolMilli` / `runsAffordable` 读数在"有冻结"的户上变小（同源口径）。
4. **不改**：五条铁律、模块边界、唯一写口 `applyTransfer` / 唯一损耗落点 `consumeForLoss`、
   `MerchantHaul` 常量、`RegimeOperators` 表、任何 Codec/ChangeSet/载荷/命令/持久组件。
5. **受影响的硬编码字面量**：无（新增的 `8`/`12` 类留样上界不新增；新事件只有字段名与 reason 字符串，不参与判据）。

---

## 7. 未完成 / 未验证（如实）

1. **多市场区世界跑满 360 tick —— 未实测**（派单书明令不跑真实 world，由控制方下一轮统一验收）。
   本账本给出的只是**判据链**：抛点被 `:8187` 之前的 WARN 分支接住 ⇒ `AdvanceTime` 不再中断 ⇒ revision 可落。
   ★ 我没有跑过一次世界，**不能**说"360 tick 一定能跑通"。
2. **day38 那一刻 `householdsOf` 为空的成因**未逐值定位（缺 A 世界日志/状态；见 §1.1 的诚实边界）。
3. **缺陷 2 的修后读数未实测**：`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT=3,589` 是否归零、`MERCHANT_HAUL_RUN` 减少多少，
   都只是推理（判据链 + 现场日志），**未跑**。
4. **未跑** `test` / `verify` / `package` / 真实 world（按派单书）。
5. **未写/未改任何测试**（`src/test/**` 一字未碰）。
6. **未 `git commit`**（按派单书由控制方提交）。
