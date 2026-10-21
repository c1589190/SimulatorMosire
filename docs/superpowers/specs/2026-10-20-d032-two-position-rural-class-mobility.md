# D-032 二元农村生产关系与家户流动机制（2026-10-20 草案 v1，已被取代）

> **取代者：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v2.md`。**
> 本文只作历史留痕；v1 的 “class-entry / 人口并入” 向上流动方案已被用户最新裁定取代。
> **状态：控制方机制草案，待用户裁定；不是实现验收。**
> **本文是新机制文档，不向旧 v2 / 旧 class-first / 旧 tick contract 追加；与 v2 冲突时以本文为准。**
> 父设计锚点：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的
> S3 生产位置选择、S4 生产日结、S7 市场、S8 事实回传。
> 用户原话：见 §8 逐字附录。

## 1. 目标运行序列（先于裁定）

本文不改变 v2 的 S0–S8 主链，只把农村生产关系收敛为两个**位置**，并在市场轮末加入一次
“人口/土地关系变化”的结算：

```text
S0 Social
S1 经济载入
S2 进入/迁移/组织
S3 生产位置选择
   - landed position：有地户（可多个/可一个 manager），自耕或雇工
   - landless position：无地户，只能受雇；未被雇 = idle
S4 生产日结
S5 收获/毛产分配
S6 工资
S7 市场
   - LAND 订单只在 landed 户之间转移土地所有权
   - landless 不拥有 LAND；landless 的“买地”改走 class-entry（见 §4）
   - 市场轮末：class-entry / distress-split 结算
S8 Social 事实回传
   - 人口变更走 Social work order / EconomyPopulationTransfer
   - 土地变更走 economy.LandLedger / landHoldings，同 revision
```

**关键点**：生产位置只有两个：`landed` 和 `landless`。富农/中农/贫农/地主不再是生产位置；
如果保留“3~4 档”，它们只是**初始财富/土地规模分档**或读数投影，不是四套生产方式。

## 2. 用户原话（逐字）

```text
我思考了一下，发现如果不禁止家户间土地转移的话，无地农民依旧会变成有地农民，也许我们甚至不应该区分出一个标准的landless户，而是初始设置3~4档家户，4档家户的最后一档可以有地可以没有地，重点是，如果财富位于最后一档的家户负债增加，且单hex没有无地家户，那么按比例把这个家户的人口转移到新的无地家户之中；——这依旧让现有生产方式设置变得太臃肿了，另一条方案是对于单个hex，生产方式设两档生产关系位置，一档为有地农民，管理单hex全部土地、自由设置雇佣比例，一档为无地农民，不能支配土地，只有在接受前者雇佣的时候被算作纳入这个hex的农业生产方式，土地依旧被算作在前者手上且可交易，如果对其他hex的有地农户，那么正常交易，如果对本hex或者其他hex的无地农户，则在交易成功时应当直接移动一部分获得土地的家户人口到出售土地的有地家户中，不给原本的无地家户添加任何已有土地，不管是哪一hex，这个机制可以算作是阶级跃迁，关于迁移的具体比例，很显然要以那次市场交易的土地规模来计算，应当设置一个土地最低覆盖标准，也就是1单位土地最低能让多少人自给自足，或者你有更好的算法也行
```

## 3. 当前事实（回代码核过的约束）

1. `LandHolding` 是 `(owner, hex, quantityMilliMu)` 的标量；每 hex 总耕地守恒；
   没有 block id。LAND 是 economy 的持久状态。
2. 当前 `grain_farm` 已有 `operator` 与 `hired_laborer` 两个**生产角色**；
   四阶层槽位是 `classStructures/classPositions` 的投影，不是四套生产配方。
3. `ClassSlideSettlement` 只在市场轮末写 `classStanding.currentPositionId`，不移动人口/土地/债务。
4. `ModeMigrationSettlement` 能移动人口/钱/债/部分可移动资产，但它：
   - 只在周期关账触发，不是每个市场轮；
   - 目标选择按预期利润，不按财富；
   - 不处理标量 `landHoldings`；
   - 当前 organizer-source 迁移被 RF4b 临时整源跳过。
5. `ClassTransitionFeed` 只是关账日 `view.stratum` 的审计，不执行人口迁移。
6. 当前 32hex 测试世界的 `PopulationSeeder` 每池按 450/350/150/50 切四户，再切 5% displaced；
   `LandOwnershipSeeder` 按 100/250/300/350 分配初始 LAND；这些是代码常量，不是配置。

## 4. 推荐方案：三层分离（Land / Production / Class-entry）

把现在混在一起的三件事拆开：

### 4.1 LAND 所有权

- 只有 **landed 户**可以拥有 LAND；
- LAND 市场只在 landed 户之间正常交易；每 hex 的 LAND 总量守恒；
- landless 户的 LAND 持有恒为 0；
- 如果 landless 户试图下 LAND BUY，拒绝并给出具名 `landless-cannot-own-land`；
  或者把它的需求转成 §4.3 的 class-entry 需求（推荐后者，不让市场伪造 land 交易）。

### 4.2 生产位置

- 每个 hex 一个 `grain_farm` 生产总集；
- 两个位置：
  - `LANDED`：有地户，拥有/经营 LAND，可选择自耕或雇工；
  - `LANDLESS`：无地户，不支配 LAND，只有被 landed 户雇佣时才进入农业生产；
- 未受雇的 landless 劳动就是 idle；不另建 `displaced` 户/独立 displaced mode；
- 3~4 档家户可以作为**初始财富/土地规模档**存在，但都落在 `LANDED` 或 `LANDLESS` 两个位置里，
  不新增生产位置。

> ★★ **推荐：测试世界不要把“有地”压成一个单 manager。** 保留 3~4 个 landed 户，
> 它们同属 `LANDED` 位置，但作为多个独立雇主竞争劳动；否则一个地主同时控制全部土地和雇佣比例，
> 会在“自耕更便宜/风险更低”时把雇佣压到 0，landless 只能 idle，反而重现当前 360 tick 的
> `hiredLaborMilli` 极低、失业劳动巨大的问题。单 manager 只应作为显式对照夹具，不作为默认测试世界。

### 4.3 阶级跃迁：class-entry（不是 land sale）

**landless → landed** 不是把 LAND 加到 landless 户头上，而是一个 `ClassEntry` 合同：

```text
source: landless household
target: landed household (manager)
claimedCapacityLandMilliMu: 目标 landed 户可用于承载的 LAND 规模
migrants: 从 source 迁入 target 的人口
money/goods/debt: 按人口比例迁移（债务走既有 split/self-debt 规则）
```

- `source` 不获得 LAND；它只是把一部分人口“并入”目标 landed 户；
- `target` 的 LAND 不变；
- 这不是 LAND 市场成交，因此不能记 `LAND_TRADE_SETTLED`；
- 这是**人口层面的阶级跃迁**：进来的人在 landed 户中生活/劳动，原 landless 户人口减少，
  最终可能成为 0 人空壳（保留身份/账户壳，便于下一次反向流动）。

**landed → landless** 是 distress-split：

```text
if landed house at lowest wealth tier
   and debtStress > threshold
   and no landless household exists in hex:
     create stable hh-<hex>-landless-split-<n>
else:
     target = existing landless household
splitPopulation = clamp(floor(population * splitRate), 1, population - minLandedPopulation)
move population + proportional money/goods/debt
land remains with source landed household
```

- LAND 不转给新 landless 户；
- 如果源 landed 户最终人口归零，LAND 必须通过明确的继承/归属规则交给另一个 landed 户或
  landed estate，不能凭空消失。

> 结论：**“landless 买地”在机制上其实是“landless 买进入 landed 户的名额”**。
> 这样既不伪造 land 所有权转移，也不产生 landless 拥有 LAND 的矛盾。

## 5. 迁移比例：比“按交易土地规模”更好的算法

用户提出“1 单位土地最低能让多少人自给自足”。这个方向对，但只用成交土地规模不够，
还应同时受**目标承载能力**、**买方财富**、**速度上限**和**最小户人口**约束。

### 5.1 每户最低土地覆盖

```text
netGrainPerMu = outputPerMu × expectedEfficiency - seedPerMu
requiredGrainPerPersonPerCycle = dailyNeedPerPerson × cycleDays
minLandPerPersonMilliMu =
    ceil(requiredGrainPerPersonPerCycle × 1000 / max(1, netGrainPerMu))
```

说明：

- `outputPerMu`、`seedPerMu`、`cycleDays` 来自生产方式定义，**不把 120 天写成通用常数**；
- `expectedEfficiency` 用 production 的实际/预期周期效率；没有效率读数时用 1000‰ 作显式缺省并记 reason；
- `dailyNeedPerPerson` 优先取 Social 的逐户温饱线/实际年龄性别需求，不用全世界一个 0.8；
  0.8 kg/人天只能作为成人重劳力的测试默认；
- 当前 48/12 预设、0.8 kg/人天、120 天、100% 效率的**示例**：
  `net = 48 - 12 = 36 grain/mu/cycle`，`need = 0.8 × 120 = 96`，
  `minLandPerPerson ≈ 96/36 = 2.67 mu/人`。
  第三周期实际亩产 42.1 时，`net = 42.1 - 12 = 30.1` ⇒ 约 `3.19 mu/人`。
- ★ **当前 32hex 世界并不缺地**：每 hex 约 3,100 mu、农村人口 200 ⇒ 约 15.5 mu/人，
  高于最低覆盖 2.67~3.19 mu/人。也就是说，landless 主要是**所有权/制度造成的**，不是土地总量不足；
  因此“目标承载能力”在当前世界通常不是瓶颈，真正会限制 class-entry 的是财富、雇佣机会和速度上限。
  这也意味着单靠“最低土地覆盖”不能解决 landless 的生存问题，必须同时有劳动需求/雇佣机制。

### 5.2 一次 class-entry 的最大人口

```text
freeCapacityPeople = max(0,
    floor(targetLandMilliMu / minLandPerPersonMilliMu) - targetPopulation)

maxByLand = min(sourcePopulation - MIN_SOURCE_POPULATION, freeCapacityPeople)
maxByWealth = floor(sourceWealth / entryCostPerPerson)
maxBySpeed = max(1, floor(sourcePopulation × migrationRatePerMille / 1000))
migrants = min(maxByLand, maxByWealth, maxBySpeed)

if migrants <= 0: no entry, record reason
```

- `targetLandMilliMu` 是目标 landed 户可用于承载的 LAND，不是“卖出的那块地”；
- `entryCostPerPerson` = 该目标户一周期口粮 + 种子/生产成本的显式政策值；
  不是 LAND 价格（因为 LAND 没有转给 landless）；
- `migrationRatePerMille` 和 `MIN_SOURCE_POPULATION` 是政策参数，防止一次搬空；
- 余数按稳定序/最大余数法处理，保证人数、钱、债守恒。

### 5.3 反向 loss / split 的最大人口

```text
debtStress = debtPrincipal / max(1, liquidAssets + goodsValue + landValue)
if debtStress > splitThreshold and isLowestWealthTier:
    splitRate = min(splitMaxRate, debtStress × splitSlope)
    split = min(population - MIN_LANDED_POPULATION,
                max(1, floor(population × splitRate)))
```

- 同一市场轮最多一次；设置 cooldown/hysteresis，避免来回震荡；
- 目标优先已有 landless 户，没有才新建；
- 新建 landless 户只拿人口/按比例的流动资产/债，不拿 LAND。

## 6. 守恒/失败语义/所有权

- 人口：Social 是唯一权威；经济只产生 `EconomyPopulationTransfer` outbox，
  App 在同一 revision 翻译成 Social 工单并回写经济行人口投影。
- LAND：每 hex 的 `Σ owner.quantityMilliMu == arableMilliMu(hex)` 不变；
  class-entry 和 distress-split 都不修改 LAND。
- 钱/货/债：按人口比例移动；债务拆分必须保持债权人/债务人稳定，
  自债/同户两端相等按既有规则具名处理，不得静默丢债。
- 失败：任一步失败整次市场轮/advance 回滚；LAND 不足、目标容量不足、人口不足、
  财富不足都具名拒绝/记录原因，不静默补 0。
- 确定性：家户/土地/债务/市场全部按稳定 id 排序；无随机、无墙钟、无 UUID。
- 日志：class-entry、distress-split、landless-rejected、capacity-reason、
  debt-split 全部 INFO/DEBUG，逐笔 TRACE。

## 7. 与用户原方案的对比

| 用户原方案 | 本文推荐 | 原因 |
|---|---|---|
| landless 买地，成交后把人口移到 seller landed 户，不给 landless 加地 | landless 不发 LAND 单，改发 `ClassEntry` 人口并入合同 | 避免“土地卖了但没有新 owner”导致 LAND 守恒/市场语义破裂 |
| 4 档家户/4 个阶层槽位 | 两个生产位置 + 3~4 档仅作初始财富/土地档 | 生产机制不再被四阶层槽位拖大 |
| 1 单位土地最低支持人口 | `netGrainPerMu`、实际 Social 需求、`minLandPerPersonMilliMu` 公式 | 避免把 48/12/0.8/120 写死；每次按实际效率和人口结构算 |
| 交易土地规模决定迁移人数 | 目标承载能力 + 买方财富 + 速度上限取最小 | 防止买方买了地却养不起人，或一次搬空 |
| 最后一档负债增加才新建 landless 户 | 有 landless 就迁入，没有才新建；加 hysteresis | 不制造重复 landless 户，也不来回震荡 |
| 单 hex 一个 landed manager，自由设置雇佣比例 | 优先多个 landed 户作为独立雇主，单 manager 只做对照 | 避免单地主 monopsony 把雇佣压到 0，landless 永远无法积累财富/进入 class-entry |

## 8. 待用户裁定

1. **LAND 是否只允许 landed 户持有？** 推荐“是”。若允许 landless 买地，就必须新增
   landless 成为 landed manager 的路径，不能继续维持“单 hex 一个 landed manager”。
2. **一个 hex 是否只允许一个 landed manager？** 本文**不推荐**默认单 manager：
   推荐 3~4 个 landed 户同属 `LANDED` 位置、各自作为雇主；单 manager 只作为显式对照。
   如果必须单 manager，则要额外定义最低雇佣/劳动需求规则，否则 landless 没有收入路径。
3. **“阶级跃迁”是人口并入 landed 户，还是 landless 户整体改变身份？**
   本文推荐前者（人口层面）；后者需要新的 household 身份与 LAND 所有权转移规则。
4. **财富按纯库存财富还是净资产？** 本文默认纯库存财富（钱+货+LAND 市值，不扣债），
   债务作为反向 split 的独立压力维；若用户要净资产，需改公式。
5. **`class-entry` 的价格/费用如何定？** 本文建议按目标承载地 + 一周期口粮/投入，
   不按土地成交价；如果用户希望按 LAND 价格，则必须改变“LAND 不转移”的前提。

## 9. 非目标

- 不在本批实现完整 D-031 共享流动表；
- 不实现跨 hex 迁移网络/文化同化/科技传播；
- 不恢复租佃/rent/pledge/抵押；
- 不让 landless 持有 LAND；不把 LAND 做成 block；
- 不把 120 天当通用收获周期；
- 不直接改 DB；所有状态变更仍走 ChangeSet/Revision；
- 不在本批实现 3~4 档的完整财富分档 UI。

## 10. 验收锚点（实现阶段）

- 单 hex 两位置世界：landed 拥有全部 LAND，landless LAND=0；
- landless 的未雇佣劳动表现为 idle，不产生 displaced 户；
- landed 户选择雇佣比例后，hired labor 进入生产 roster，landless 才进入生产；
- landless 财富达到阈值 + 目标有承载空间 ⇒ class-entry 只移动人口/钱/债，LAND 总量不变；
- landed 最后一档债务压力高且无 landless ⇒ 新建/迁入 landless；LAND 总量不变；
- 每轮最多一次、速度有上限、人口不为负、债务/钱/货守恒；
- 全部事件 INFO/DEBUG 可读，负向用例（LAND 被 landless 持有、人口穿越、土地消失）当场红。

## 11. 附：为什么当前测试世界是代码预设

- `Rural32HexWorld` 是 `src/test/java` 的确定性 test fixture，不是运行期世界配置；
  它硬编码 32 hex、每 hex 200 农村人口、首都 700 城镇人口，并直接构造 `SettlementPlan`。
- 真实 worldgen 配置 `config/worldgen/v17levant-nations.json` 只有国家人口/城市化/军队等字段；
  没有四阶层人口比例、初始 LAND 权重、口粮天数、劳动参与率、mobility 参数。
- `EconomySeeder` 自己注明：这些“场景参数”配置里没有，所以以代码常量存在。
- `store/simos.db` 是 revision/checkpoint 输出，不是种子配置；直接改 DB 会绕过
  `Command → ChangeSet → Revision`、Codec、跨切片一致性检查，而且 360 runner 每轮会清空并重新创世。
- 正确方向是新增 **JSON seed spec + 通用 seed builder**，不是继续写四阶层代码常量，也不是改 DB。

## 12. 用户原话附录

见 §2 的逐字块。日期：2026-10-20。
