# M-D 实现架构账本：主业/副业 = 生产方式排序表的第 1 项 / 其余项（+ trade 工具补货路径）

> 责任区：**M-D**。权威设计书 = `docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md`
> **§13（I-A..I-E、§13.3、§13.5 I-1..I-4）**；`docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md`
> **§2.4（M5 行）、§6.2 V-23、§7 Q-18..Q-20**；前置批 = `.superpowers/sdd/2026-10-10-m-c-merchant-profit/impl-ledger.md`
> （M-C 的判据与 §6 已知缺口）。
> 本文件是实现方自己写的**实现架构账本**（AGENTS §一.8），只记决策相关事实。
> 用户原话（逐字）： 「单个家户不是不能选择主生产方式，如果副生产方式足够赚钱会加速流向对应另一生产方式吗，
> 主业副业这不就判断出来了……其他的我没意见，单个家户是先根据市场议价权和库存选择生产方式的是吧，
> 那如果第一项是跑商，就算是主业为商户了」

## 1. 关键调查结论（`file:line` 证据 → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| S-1 | `HouseholdClassMembership.java:136-143`（`effectivePositionIds`）、`:45-53`（`currentPositionId`/`participatingPositionIds`）、`EconomyData.java:1563-1591`（构造期引用完整性） | 主业/副业**已在既有状态里表达**（§13.3），零新增状态；位置合法性由 `EconomyData` 判死 | 排序表是**纯读数/裁决**，不碰 Codec/ChangeSet/往返不变式 |
| S-2 | `ModeMigrationPolicy.java`（`plan` 的 `accounts` 入参，M-D 前只被 `liquidityMilli` 读货币+商品；`ExpectedProfitBook.prospect` 的 `accounts` 入参**改前未被读**，类注写明"保留入参以承接后续流动性/工资口径"） | **排序输入"库存"的读口已经存在**（`liquidityMilli`：本格计价币余额 + 商品库存按本格牌价折算）；`ExpectedProfitBook` 已读"可用生产资料份额"（`assetScaleOf`） | M-D 只需**把两处接到同一张表**，不新增权威、不写第二本需求（I-C6） |
| S-3 | `MerchantCapacity.java:182-187`（`sharePerMilleOf`）、`MerchantCapacityPool.java:197-206`（池内分配序 = 限价 → 议价权降序 → 家户 id 升序） | "市场议价权"的既有唯一拼写点 = **本格运力占比‰**（§11.4 G-1） | Q-19 的"同一拼写点不得两套"落成：排序表**调用**它，不重写算式 |
| S-4 | `ModeMigrationPolicy.java:482/526`（`weight = max(0, target.netPerLaborScaled − current.netPerLaborScaled)`）、`:316`（`modeByHousehold` 只读 `currentPositionId`） | 改前的"候选序"已经是**收益率降序**（同一把百万分之一尺），破平 = 距离 → canonical 串 → 家户 id；**没有**位置 id 升序这一级，也**没有**排序表这个显式对象 | M-D = 把这张隐式序**显式化**（I-3 的破平 + 两个排序输入 + 日志），并让它决定 `current` 的落点 |
| S-5 | `ModeMigrationSettlement.java:797-836`（`createNewHousehold`：`pickTargetPosition(...)` → `currentPositionId`、`participating = Set.of()`、`reason = "AUTO_MIGRATION:" + move.reason()`） | `current` 的**唯一自动落点**在这里（D-022：源户 standing 一字不改） | M-D 的落点接在这里：`MigrationMove` 带 `primaryPositionId` ⇒ 新建户 `current` = 排序表第 1 项；`reason` 具名 |
| S-6 | `MarketSettlement.java:1959-1963`（`plan.necessaryInputs` → 买单目标）、`:7939-7967`（`necessaryInputsOf` = `industry.inputPerUnit() × plannedCapacityScale`） | 家户买生产投入的**唯一路径**是 `Industry.inputPerUnit()`；`trade` 改前 `cycleInputPerUnit = {}` ⇒ 跑商家户**没有工具买单** | §附修法（给 `trade` 加工具投入）确实走既有路径产生需求；不动 Social 的自然需求权威 |
| S-7 | `MerchantHaul.java:53/66`（`TOOL_MILLI_PER_HAUL = 1_000`、`affordsRun`） | 跑商门槛常量与判据已有唯一拼写点 | 排序表的"库存"门槛直接调它（不另拍一个阈值） |
| S-8 | `grep 'new MigrationMove('`：`src/main` 1 处（policy 内）、`src/test` 1 处（`ModeMigrationGovServiceFailClosedTest.java:183`，9 参） | 给 record 加第 11 个分量会**破坏**该测试的编译 | 保留 9 参 / 10 参别名构造（缺省 = `null` ⇒ 执行器退回既有 `pickTargetPosition`，逐值不变） |
| S-9 | `ExpectedProfitBook.java:612-740`（`merchantProspect`：`carryable = min(capacity, requested)`，`requested = max(routeDemand, unfilledBuyer)`，工具维显式传 0 = 下界） | 改前每个跑商候选都按"**本格全部跨格需求**"计收益（逐户上界）；工具维不在决策面 | 见 §4 D-1（议价权**不**按占比缩放，只作门槛）——这是本批最大的一次口径取舍 |

## 2. 实现架构（组件拆分 / 数据流 / 调用次序）

### 2.1 新增类型

**`time/PrimaryModeRanking.java`（public）—— 生产方式排序表的唯一拼写点**

```
Row(positionId, modeId, hex, yieldPerLaborScaled, bargainingPowerPerMille, inventoryMilli, reason)
Excluded(modeId, hex, reason)                      // 被排序输入挡下的行（具名，不静默）
Table(household, rows, excluded)  → primary() / secondary() / secondaryPositionIds() / rankedModes()
rank(household, rows, excluded)   // 排序：收益率降序 → 位置 id 升序（I-3）→ 格(q,r) 升序 → mode id 升序
inventoryMilli(accounts, markets, topology, 户, hex)   // 库存唯一拼写点（搬自 policy.liquidityMilli）
toolStockMilli(goods, 户) / merchantGateReason(base, 户, hex, goods)
logTable(day, table)              // DEBUG：逐行 rank/mode/position/hex/yield/议价权/库存/reason + excluded
logPrimaryChange(day, 户, fromPosition, fromMode, toRow, fromYield)   // INFO：谁 从哪 到哪 为什么
```

### 2.2 改动的既有类型

**`MerchantCapacityPool`（+3 个纯函数）**
- `hexCapacityMilli(base, hex, goods)`：逐户 `MerchantCapacity.of(...)`（劳动 + 工具）求和；`hasCapacityAt(base,hex)` 改为**委托**它（`goods = Map.of()` ⇒ 工具 0 ⇒ 与改前逐值相同）。
- `memberCapacityMilli(...)`（私有）：成员判据 `MerchantIdentity.selectsMerchant` + 算式 `MerchantCapacity.of` **与装配同源**。
- `sharePerMilleAsProviderAt(base, 户, hex, goods)`：**G-1 同一拼写点**（`MerchantCapacity.sharePerMilleOf`）。分母：住本格且已是池成员 ⇒ 本格总量；其余（本格新进入者 / 邻格准备迁入的候选）⇒ 本格总量 + 自己。

**`ModeMigrationPolicy`**
- `MigrationMove` 加第 11 分量 `primaryPositionId`（可 null）+ 保留 9/10 参别名。
- `Target` 加 `positionId` / `yieldPerLaborScaled` / `prospectReason`（同一批 prospect 的读数，**不重复现算**）。
- `buildTargets(...)` 加一个 `List<Excluded>` 收集器 + **跑商候选门槛**（工具 + 议价权）：不成立 ⇒ 不进候选、具名入 excluded。
- `planForSource`：① 当前项 prospect → ② 候选（带门槛）→ ③ `rankingTable(...)`（当前项 + 全部候选，纯读数）→ ④ `logTable`（DEBUG）→ ⑤ `primaryFirst(targets, table)`（排序表第 1 项对应的目标提到最前 = I-C 的"加速流向"）→ ⑥ 既有 A 规则 / 权重分支**一字不改地读 `ranked`** → ⑦ `maybeLogPrimaryChange`（INFO，只在真有 move 且第 1 项换了 mode 时）。
- `liquidityMilli` / `marketOf` / 饱和算术 ⇒ 委托 / 搬到 `PrimaryModeRanking`（**全仓只此一处**，A 规则与排序表不可能漂开）。

**`ModeMigrationSettlement.createNewHousehold`**
- `plannedPrimary = move.primaryPositionId()`；其 `modeId == move.targetMode()` ⇒ 用它当 `currentPositionId`（顺序表第 1 项的落点），否则退回 `pickTargetPosition`（旧调用方/夹具逐值不变）；`reason` 在用了排序表落点时追加 `:PRIMARY_MODE_RANKING`（I-4 的具名 reason，既有字段）。

**`app: world/EconomySeeder`**
- 具名常量 `TOOL_MILLI_PER_TRADE_UNIT_CYCLE = 100`（毫工具 / 规模单位·周期）；`trade(...)` 的 `cycleInputPerUnit` 从 `{}` 改成 `{CATTLE: {tool: 100}}` ⇒ 需求 = 100 规模 × 100 = **10,000 毫工具/格·周期 = 10 商品单位 = 10 次跑商的工具量**。

### 2.3 一 tick 内的落点（本批）

```
⑧ 迁移计划 mode（ModeMigrationPolicy.plan）
   逐源户：当前项 prospect（不动）→ 候选（+ 跑商门槛：tool ≥ 1,000 毫 ∧ 议价权‰ > 0）
     → 排序表（收益率降序 → 位置 id 升序；两个输入：议价权‰ + 库存折价）→ DEBUG 逐行
     → 第 1 项对应的目标排最前 → 既有 A 规则 / 权重分支（门槛/权重/承载一字不改）
     → INFO PRIMARY_MODE_CHANGED（仅当真有 move 且第 1 项换了 mode）
⑨ 迁移执行（ModeMigrationSettlement）
   新建目标户：current = 排序表那一项的位置（reason 带 :PRIMARY_MODE_RANKING）
   已有目标户合并：current 一字不改（D-022）
```

## 3. 关键判断（为什么这样拆 / 为什么不用另一条路线）

- **J-1 ★ 排序表不新造候选、不重复现算**：表的行全部来自 `planForSource` **已经算过**的 prospect（当前项 + `buildTargets` 的候选），
  只补两个**已存在的读口**（议价权‰ / 库存折价）。理由：① 保证"排序表"与"A 规则读数"是**同一批数字**（冻结项 5 的"自洽"）；
  ② 不把 `plan()` 的开销翻倍（真档每周期逐户 × 逐格 × 逐 mode 现算一次已经很贵）。
- **J-2 ★ 候选集 = 当前项 + 收益率严格更高的候选**（= `weight > 0` 的同一门槛）⇒ 无更优候选时表只有当前项 ⇒
  第 1 项 = 当前 ⇒ **缺省语义中性（冻结项 6）**逐值成立；有更优候选时，第 1 项 = 应该接任主业的那个 mode，
  "其余项" = 其他候选 + **原主业**（换过主业后它自然降为一项副业）——与 I-C 自洽。
- **J-3 ★ 落点接在 `createNewHousehold`（唯一自动写 `current` 的地方）**：D-022 明令源户 standing 一字不改 ⇒
  "主业改变"在结构上只能是"人迁到一个 `current` = 新主业的户"。计划带 `primaryPositionId`、执行器只用它当**选位置**的依据
  （不越权改 mode），所以"排序表只决定 current 落在哪一项，落点仍走既有模式变迁路径"逐字成立。
- **J-4 ★ 议价权不按占比缩放收益**（见 §4 D-1）：门槛 + 读数，方向 fail-closed。
- **J-5 ★ 工具门槛在**决策层**与市场层各判一次、互不冒充**：决策层判"要不要把跑商当主业"（`merchantGateReason`，
  用 `MerchantHaul.affordsRun`）；市场层照 M-C 的既有 `tool-short` 判"该次跑商成不成立"。
- **J-6 ★ 邻格跑商候选的议价权按"新进入者"算**（`sharePerMilleAsProviderAt` 的 else 支）：运力池挂**发货格**且成员判据是
  "住在那格"⇒ 若不这样，"住在 A 格、想迁到 B 格当跑商"这条路会被判成占比 0 ⇒ **跨格进入跑商整条堵死**（初版就是这样，自查后改掉）。
- **J-7 ★ INFO 只在"真有 move 且第 1 项换了 mode"时打**：否则每户每天都刷一行（迁移计划本就逐户跑），
  日志会盖过真正的变更；判据本身在 DEBUG（`PRIMARY_MODE_RANKING_ROW/_EXCLUDED`）。
- **J-8 ★ `liquidityMilli` 的算式搬走而不是复制**：I-2 的"不得两套"精神同样适用于"库存"——
  搬进 `PrimaryModeRanking.inventoryMilli` 后由 policy 委托，含 2026-10-09 的溢出饱和修复（`saturatedMulDiv/saturatedAdd` 一并搬走）。

## 4. 偏离记录（与冻结口径不一致处，主动记录）

| # | 偏离 | 原因 / 待裁定 |
|---|---|---|
| **D-1** | **"市场议价权"在本批是跑商行的可行性门槛 + 读数，不是收益的比例因子**（I-B 的另一种读法 = "按占比缩放该家户的预期收益"） | ① Q-25 已冻结"**价格管买方的选择、议价权管稀缺时的分配**"⇒ 议价权不是收入的比例因子；§11.2③ 的"运力总量 = 硬上限"只在**逐 hex 汇总**层面成立，逐户只能给上界（改前 `carryable = min(capacity, requested)` 就是那个上界）。② 按占比缩放会**同时压低 `ExpectedProfitBook` 的 `expectedNet`**（同一个算式既喂排序表也喂 A 规则）⇒ 已成立商户的 A 规则读数更易变负 ⇒ 可能整批外流；本批不跑测试，无法验证这条连锁反应。③ 门槛式改动方向 fail-closed。★ 若控制方裁定按占比缩放：改动面 = `ExpectedProfitBook.merchantProspect` 的 `carryable` 一处 + `PrimaryModeRanking.merchantGateReason` 一处（其余不动）。 |
| **D-2** | 排序表的**候选集**= 当前项 + 收益率严格更高的候选（不是"本格所有可行 mode"） | 与 A 规则/权重的门槛同源（J-2），避免重复现算与行为漂移；"其余项 = 副业"因此含**原主业**（与 I-C 自洽）。 |
| **D-3** | **本批不写 `participatingPositionIds`** | 冻结项 1 明写"排序表**只决定** `current` 落在哪一项"⇒ 副业的写口仍归既有路径（GM 参与命令 / 迁移落点 `Set.of()`），本批不新造第二条写口。★ 若把排序表全体"其余项"写进 `participating`，会把每户都变成"顺便跑商家户"（`MerchantIdentity.selectsMerchant` 读的就是并集）⇒ 本格运力池总量暴涨、既有商号的占比‰ 被摊薄 ⇒ 与 D-1 叠加会压垮跑商经济。**这条必须由控制方裁定后再动。** |
| **D-4** | `buildTargets` 里的跑商门槛**只对候选 + 当前主业行**判；A 规则读的 `expectedNet` 一字不改 | 冻结项 5 明写既有 A 规则机制不改；缺工具的"该次跑商不成立"仍在市场轮 M-C 门槛里具名（`tool-short`）。 |
| **D-5** | I-3 的破平（位置 id 升序）在**排序表内逐字实现**；迁移目标序只在**第 1 项**跟随排序表，其余保持既有 `weight → distance → canonical` 序 | 全量替换目标序会改既有分配语义（`allocate` 两遍顺延依赖次序）⇒ 风险大于收益。★ 副作用：**权重并列**时第 1 项与改前的首元素可能不同 ⇒ 目标序变 ⇒ 分配可能变（具名，见 §5.3/§5.5）。 |
| **D-6** | `MigrationMove.reason` 在用了排序表落点时变成 `AUTO_MIGRATION:<既有 reason>:PRIMARY_MODE_RANKING` | I-4 要求主业改变写具名 reason（既有字段）；不改前缀、只追加具名段；未用落点时逐字不变。 |
| **D-7** | `trade` 的工具补货只覆盖**经营 trade 的那一个家户**（该格 merchant principal） | `trade` 每格只有一个经营者（`EconomySeeder:3274` 的 `merchantPrincipalActor`）⇒ 同格其它跑商家户（`merchant.self_employed`）仍无 trade unit ⇒ 它们的工具补给要靠 M-D 排序表把它们选去当经营者、或另开"跑商家户按剩余运力生成工具购买意图"那一档（M-C 账本 §6 的三条补救 ②）。 |

## 5. 实施记录

### 5.1 改动文件

| 类别 | 文件 | 处置 |
|---|---|---|
| 新增 | `simos-economy/…/time/PrimaryModeRanking.java` | 排序表 + 两个排序输入 + INFO/DEBUG 日志（唯一拼写点） |
| 改 | `simos-economy/…/time/MerchantCapacityPool.java` | `hexCapacityMilli` / `sharePerMilleAsProviderAt`（G-1 同一拼写点）；`hasCapacityAt` 委托 |
| 改 | `simos-economy/…/time/ModeMigrationPolicy.java` | 排序表构建与排序输入、跑商候选门槛、`primaryFirst`、INFO 主业变更、`MigrationMove.primaryPositionId`、`Target` 带位置/收益率/reason、库存算式迁出 |
| 改 | `simos-economy/…/time/ModeMigrationSettlement.java` | 新建户 `current` = 排序表那一项的位置 + `reason` 具名 |
| 改 | `simos-app/…/world/EconomySeeder.java` | `TOOL_MILLI_PER_TRADE_UNIT_CYCLE` + `trade` 的 `cycleInputPerUnit = {CATTLE:{tool:100}}` |

### 5.2 命令与结果（两条，本仓锁；只到编译过 —— 派单纪律）

```
$ tools/mvn-lock.sh -q spotless:apply                       → exit=0
$ rm -rf simos-economy/target/classes simos-app/target/classes
$ tools/mvn-lock.sh -DskipTests compile                     → exit=0（BUILD SUCCESS，16 模块）
```

### 5.3 会改变数值行为的清单（含缺省中性论证）

| # | 变化 | 缺省中性（无跑商候选 / 单候选 / 无更优候选） |
|---|---|---|
| 1 | ★ **跑商候选（含当前主业就是跑商的行）在决策层要过"库存 + 议价权"门槛**：`tool ≥ 1,000 毫` 且 占比‰ > 0；不成立 ⇒ 不成候选（无工具的家户**不再被迁进商贩行业**） | 无 merchant mode 的世界/夹具 ⇒ 门槛不可达；单候选 ⇒ 表只有当前项 ⇒ 第 1 项 = 当前 ⇒ 不动 |
| 2 | ★ **迁移目标序**：排序表第 1 项对应的目标提到最前（D-5）。**权重并列**时这一级与改前的 `distance → canonical` 首元素可能不同 ⇒ 分配可能变 | 候选 ≤ 1 或第 1 项就是第一个目标 ⇒ 原序返回，一字不改 |
| 3 | 新建目标户的 `currentPositionId` 改为**排序表那一项的位置**（限"该位置属于目标 mode"；否则退回既有 `pickTargetPosition`） | `primaryPositionId = null`（夹具/旧调用方）⇒ 逐值退回 `pickTargetPosition` |
| 4 | `reason` 追加 `:PRIMARY_MODE_RANKING`（仅在使用排序表落点时） | 未用落点/夹具 ⇒ 逐字不变 |
| 5 | ★ **创世 `trade` 每格每周期多 10,000 毫工具投入**（`inputPerUnit[tool]`）：① 生产投入需求 ⇒ 经营者家户挂工具买单（补货路径）；② 周期首日现扣 `cycleInputUsedMilli`；③ `MarketDemandBook.addressable` 的工具需求项变大 | 非 `production-runtime` / 无城镇人口的格 ⇒ 无 trade 模板 ⇒ 0 |
| 6 | 新增日志：DEBUG `PRIMARY_MODE_RANKING_ROW/_EXCLUDED`、INFO `PRIMARY_MODE_CHANGED` | 无更优候选且主业非跑商 ⇒ 表只有当前项：DEBUG 仍有 1 行/户（仅 DEBUG 档）、INFO 一行不打 |
| 7 | 零持久状态改动 | `EconomyData` / 变更集 / Codec / 命令面 / GM 工具 **全零改动**；`MigrationMove` 是瞬态计划对象 |

### 5.4 具名常量（一行）

```
TOOL_MILLI_PER_TRADE_UNIT_CYCLE = 100 毫工具 / 规模单位·周期
  ⇒ 一格 trade（规模 = MERCHANT_CATTLE_PER_CITY 100）= 10,000 毫工具/周期 = 10 商品单位 = 10 次跑商的工具量
     （1 商品单位 = 1,000 毫 = 1 趟 = MerchantHaul.TOOL_MILLI_PER_HAUL）
  理由：与 M-C 创世存量 12,000 毫（≈12 趟，M-C 账本 D-3）同量级；低于手工业工具产出量级（1 作坊 5 件/周期 = 5,000 毫 × 作坊数）
        ⇒ 不抽干工具市场，也不与作坊自己的工具投入打架。
排序表本身**不新增数值常量**：门槛用既有 MerchantHaul.TOOL_MILLI_PER_HAUL，议价权用既有 MerchantCapacity.sharePerMilleOf，
收益率用既有 ExpectedProfitBook 的百万分之一尺。
```

### 5.5 会让既有测试失效的清单（**静态核对，未跑** —— 派单纪律只到编译）

| 类别 | 文件 | 原因 |
|---|---|---|
| **编译：无** | —— | `MigrationMove` 保留 9/10 参别名（`ModeMigrationGovServiceFailClosedTest.java:183` 走 9 参）⇒ 全仓 `src/test` 编译不受影响 |
| 行为：**可能变**（排序表只在有 merchant mode 的世界里改序/改候选；有 trade 模板的创世世界改需求） | `simos-economy`：`ModeMigrationPolicyD024Test`、`ModeMigrationPolicyClaimedAssetsTest`、`ModeMigrationGovServiceFailClosedTest`（**权重并列**时目标序可能不同 ⇒ 分配可能不同；夹具无 merchant mode ⇒ 门槛不触发） | D-5 的目标序 +（若有 merchant 夹具）门槛 |
| 行为：**可能变** | `simos-app`：`ProductionRuntimeSeedSmokeTest`、`CompactThreeNationsWorld` 系、`EconomyCycleHealthTest`、`RealTwelveHexWorld` 系、`GuiApiTest` | ① trade 新增工具投入（需求/现扣/价格读数）；② 跑商家户的工具补货买单出现 ⇒ 市场成交与 `unfilled` 读数变；③ 若夹具家户工具不足 1,000 毫 ⇒ 跑商候选被挡 |
| 行为：**不变** | `ExpectedProfitBookTest`（直调 `prospect`，不经过排序表/门槛）、`EconomyRoundTripTest`（零持久状态改动）、单 hex 市场用例 | 排序表不写状态、不改 `prospect` 算式 |

★ 测试侧口径（留给测试 Agent）：T1 排序表键（同收益率 ⇒ 位置 id 升序；`Table` 行序是内容的纯函数，两跑逐值相同）；
T2 缺省中性（单候选 / 无更优候选 ⇒ `primary()` = 当前项、`secondary()` 空、无 `PRIMARY_MODE_CHANGED`）；
T3 跑商门槛（tool < 1,000 毫 ⇒ merchant 候选不进表、`Excluded.reason = tool-stock-zero`；补足工具后同一用例进表）；
T4 落点（`MigrationMove.primaryPositionId` 属于目标 mode ⇒ 新建户 `currentPositionId` = 它、reason 带 `:PRIMARY_MODE_RANKING`；
传 `null` ⇒ 与改前逐值相同）；T5 工具补货（trade 模板有 `inputPerUnit[tool] = 100` ⇒ 经营者家户的工具买单目标 = 20,000×scale 的
口径核对与"周期末工具存量不再单调降"）；T6 日志（INFO 一行/变更、DEBUG 逐行）。

## 6. 未完成 / 未验证（如实记）

- **未跑任何测试/长跑**（派单纪律：只到编译过）⇒ §5.3 的数值变化、日志形态、门槛在真档里"咬不咬得住"**均未实测**；
  §5.5 是**静态推断**。
- **D-1（议价权不缩放收益）与 D-3（不写 `participating`）是两条待控制方裁定的口径取舍**，本批按"风险最小 + fail-closed"落，
  两处都留了具名改动面。
- **D-7：工具补货只覆盖 trade 的经营者家户**（同格其它跑商家户仍会烧完即停）。
- **已知具名边界（继承 M-C）**：① 纯状态侧（`EconomyData`）的议价权与运力都**看不到工具**（`hexCapacityMilli` 传空表 ⇒ 下界）；
  ② 排序表不判"生产候选的现金门槛"（若给生产行加流动性硬门槛，会拆掉 A 规则"没钱 ⇒ 迁移"的安全阀，故只把库存当**读数**喂 A 规则）；
  ③ 跨轮库存持有的本钱占用不在读数里（M-C J-3）。
- **未做**（按冻结范围）：币种挂单过滤（P-T1e）；政府采购优先级（P-T1d）；卖家赊购（Q-26）；挂单簿持久状态。
