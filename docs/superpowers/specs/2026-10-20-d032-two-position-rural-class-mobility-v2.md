# D-032 二元农村生产关系与土地市场流动机制 v2（2026-10-20，已被取代）

> **取代者：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v3.md`。**
> 本文的 “landless 买地获得 LAND / 整户转 landless / 多雇主建议” 已被 v3 的
> “B：土地成交后检查 + 人口阶级跃迁 + 同 hex 向下流动表” 取代，只作历史留痕。
> **状态：控制方机制草案 v2，待用户裁定；不是实现验收。**
> **本文取代：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility.md`（v1）。**
> v1 的 “class-entry：landless 用财富并入 landed 户、土地不转移” 已被用户最新口径取代：
> **landless 只能通过购买市场上抛售的土地成为 landed 户；不再保留财富驱动的自动向上流动。**
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 S3/S4/S7/S8。

## 1. 用户最新原话（逐字）

```text
第二层，我的建议之前就提过了，只要没地家户认为干活收益入不敷出，就不提供任何劳动力进雇佣农业生产，单hex只有一个land户是完全可行的，其次这个单hex的有地农民不是能和其他hex交易土地吗？相当于，生产方式端要额外给家户维护一个阶级性，同阶级性家户自由交易生产方式，不同阶级性的家户如果产生交易生产方式的需求，走阶级跃迁流程，注意，家户间因负债而产生的向下阶级流动依旧即存在，只不过向上流动我觉得可以取缔掉了，无地农民只能通过买市场上抛售的土地才能成为本hex或者其他hex的有地农民；这样一来，土地买卖机制反而更好做了，生产规划里因劳动力、种子粮等原因空出来挂到市场上的土地也就有了去处
```

## 2. 目标运行序列

不改变 v2 S0–S8 主链，只改农村 classness 与 LAND 市场语义：

```text
S0 Social
S1 经济载入
S2 进入/迁移/组织
S3 生产位置选择
   - classness = LANDED：可占 landed 位置，支配自己的 LAND，选择自耕/雇工
   - classness = LANDLESS：不能占 landed 位置，只能作为 hired_laborer 被 landed 户雇佣
   - landless 如果认为“净劳动收入 <= 成本”，可不提供任何劳动
S4 生产日结
S5 收获/毛产分配
S6 工资
S7 市场
   ① 生产规划先确定本轮实际使用/闲置的土地
   ② landed 户把本轮未使用/无法使用的 LAND 挂卖
   ③ 商品与 LAND 同一订单簿；landless/landed 都可作为 LAND 买方
   ④ 成交后 LAND 标量转移；买方 landless ⇒ 成交即触发 landless→landed 阶级跃迁
   ⑤ landed 卖方如果卖出后 LAND=0 ⇒ 可在本轮/下一轮触发 landed→landless
   ⑥ 债务清偿仍可造成向下阶级流动；但不得凭空产生财富驱动的向上流动
S8 Social 事实回传
```

**时间规则**：S7 卖出的土地只能从**下一生产 tick** 起进入买方生产规划；本轮已经完成的 S4/S5
不回溯。买方在本轮已经耗尽的生产投入/劳动不因买地而补算。

## 3. 两层状态

### 3.1 LAND 所有权（唯一土地权威）

- `LandHolding(owner, hex, quantityMilliMu)`，每 hex 土地总量守恒；
- LAND 可以跨家户转移，也可以跨 hex 交易（具体限制见 §6 待裁定）；
- LAND 市场不再只面向 landed 户：landless 也可以下 LAND BUY，成交后获得 land 所有权；
- 成交后 landless 买方成为 landed，landed 卖方如果归零则成为 landless；
- 0 价 LAND 市场成交合法；债务清算中的 0 价 LAND 仍按 v2 的 E8：不自动冲本金。

### 3.2 家户阶级性（classness）

本文建议：

```text
classNature =
    LANDED   如果该家户在任一 hex 的 landHoldings 合计 > 0
    LANDLESS 如果该家户在任一 hex 的 landHoldings 合计 == 0
```

- classness 是**从 LAND 所有权派生的读/调度属性**，不是第二份土地所有权；
- 如果为了性能缓存，必须和 LAND 变更在同一个 ChangeSet/Revision 里更新，并加不变量：
  `classNature == LANDED ⇔ Σ landHoldings > 0`；
- 不允许一个家户 classness=LANDED 但 land=0，或 classness=LANDLESS 但 land>0。

> 用户说“生产方式端额外给家户维护一个阶级性”，我建议“维护”为派生缓存，而不是可独立写的
> class 状态；否则 land 交易和 classness 会漂开，违反一个事实一个写口。

## 4. 生产位置：只有两档

`grain_farm` 的生产关系位置只保留：

```text
LANDED_MANAGER / LANDED_OWNER
  - 必须有 LAND
  - 可自耕、可雇工、可决定 land 的 self/hired 比例
  - 可与其他 landed 户交易 land/生产组织
LANDLESS_LABORER
  - 不能支配 LAND
  - 只有被 landed 户雇佣时才进入该 hex 的农业生产
  - 未被雇佣 = idle；不建 displaced 户/独立 displaced mode
```

- “3~4 档家户”可以保留，但只是初始财富/土地规模档，或读数投影；不是四个生产位置；
- 单一 hex 只有一个 landed 户在初始状态是可行的；但如果 landless 买走 part of LAND，
  该 hex 可以出现第二个 landed 户。**“只有一个 landed 户”是初态，不是永久不变量**，除非
  另定 LAND 购买只能发生在没有其他 landed 户的 hex。此点待裁定。

### 4.1 无地户的劳动供给

```text
netWorkIncome = wageIncome - workCost
workCost = 口粮/生产额外消耗 + 显式劳动负效用（可选）
if netWorkIncome <= 0: suppliedLabor = 0
```

- landless 可以拒绝所有雇佣劳动；
- landless 拒绝劳动后进行 starvation/生存结算，不得被强制派工；
- 单 hex 只有一个 landed 户时，这个规则允许 landed 户自己种、雇不到人就减少播种/挂卖闲置土地；
  因此“单 landed 户”本身可行，但可能出现大量 idle land/landless。

## 5. LAND 市场与阶级跃迁

### 5.1 卖方：生产规划空出来的土地

- S3/S4 确定本轮实际自耕/雇工/播种面积后，landed 户把**本轮未使用**的 LAND 挂卖；
- 只允许挂本轮未投入生产的土地，不能卖已经计入本轮 `u/a_i` 的地；
- 卖方可以定价；LAND 市场仍是区级价格参考 + 单据价格；
- 卖不掉的土地保持原 owner、原样闲置；不得自动销毁/重分配。

### 5.2 买方：landless / landed 均可

- LAND 买方可以是 landed 户（同阶级交易），也可以是 landless 户（跨阶级）；
- 买方需求仍是**数量型**：由该户实际需要的 land 规模决定，不以现金/价格上限截断；
- 成交时先付现金，未付部分按既有普通货币债路径；
- landless 买方成交后：
  - LAND 加到该家户的 `landHoldings`；
  - 该家户 classness 在成交后的同一 settlement 中变为 LANDED；
  - 下一生产 tick 起可以占 landed 位置/组织生产；
- landed 卖方如果卖出后 LAND=0，classness 变为 LANDLESS；
- 同阶级 LAND 交易正常；跨阶级 LAND 交易即“阶级跃迁”：
  - **向上**：landless 买地 → landed；
  - **向下**：landed 卖光/失去全部 LAND → landless。

### 5.3 买方需要多少 LAND

用户提出“1 单位土地最低能让多少人自给自足”。建议用与 v1 相同的**净产出承载公式**，但
这里只用于需求数量，不再是 class-entry 名额：

```text
netGrainPerMu = outputPerMu × expectedEfficiency - seedPerMu
requiredGrainPerPersonPerCycle = dailyNeedPerPerson × cycleDays
minLandPerPersonMilliMu =
    ceil(requiredGrainPerPersonPerCycle × 1000 / max(1, netGrainPerMu))
desiredLandMilliMu =
    min(
      minLandPerPersonMilliMu × householdPopulation,
      landDemandFromLaborCapacity,
      availableMarketLand)
```

- `outputPerMu`、`seedPerMu`、`cycleDays` 来自生产方式定义，不写死 120；
- `dailyNeedPerPerson` 优先从 Social 的逐户温饱线/年龄性别需求计算，不全世界统一 0.8；
- `landDemandFromLaborCapacity` = 该户可投入劳动 ÷ 生产方式每 mu 所需劳动；
- 买方是价格接受者，**不是用现金/预算截断数量**；现金只影响支付腿/债务腿。

### 5.4 同阶级“生产方式交易”

用户说“同阶级性家户自由交易生产方式，不同阶级性走阶级跃迁”。本文建议把这句话落成：

- 生产方式/生产位置有 `classRequirement`：
  - `LANDED_MANAGER` 要求 classness=LANDED；
  - `LANDLESS_LABORER` 只对 classness=LANDLESS 开放；
- 同 classness 的家户可以自由交易/转移与生产组织相关的 LAND、unit、roster、生产合同
  （具体是 LAND 还是整个 unit，待裁定）；
- 跨 classness 的“生产方式交易”不直接允许，必须先通过 LAND 市场成交/债务清算改变 classness；
- 禁止“landless 有现金就直接占 landed production position”，即使它很有钱。

## 6. 向下流动：负债仍然存在

- 债务清偿沿用 v2 S7-M2：货币 → 商品 → 标量 LAND；
- landed 户因债务失去部分 LAND：
  - 仍有 LAND ⇒ 仍为 LANDED，但可能降为低财富档；
  - LAND 归零 ⇒ classness 变为 LANDLESS；
- 若一个 landed 户人口很多、全部 LAND 归零，建议**整户保持 HouseholdId 不变并转为 landless**；
  不再自动用“财富驱动”向上把人口并入别的 landed 户；
- 是否允许把该户部分人口拆成新的 landless 户，作为“半无产阶级化”的表现，待裁定；
  本文默认“整户转 landless”，不新建户，避免同一事实两套身份。

### 6.1 债务清偿中 landless 债权人收到 LAND 怎么办

这是一个必须裁定的边界：

- 若允许 landless 债权人直接以 LAND 抵债，它会**不经过市场购买**变成 landed，
  与“向上流动只能通过买市场上土地”冲突；
- 本文建议：**债务清算不把 LAND 直接转给 landless 债权人**。
  - landed 债务人仍按 S7-M2 可把 LAND 卖给/转给 landed 债权人；
  - 若唯一可接收的债权人是 landless，LAND 进入市场出售，价款按债务清偿；
  - 若市场没有买家，债务保留/继续挂账，LAND 不凭空转给 landless。
- 这条需要用户确认，因为它会改变现有 `DebtFirstSettlement` 的 LAND 腿。

## 7. 初始测试世界（建议的最小形态）

```text
32 hex（可先缩成单 hex 做机制冒烟）
每 hex：
  1 个 initial landed 户（持有该 hex 全部 LAND，landed position）
  1 个 initial landless 户/池（LAND=0，landless position）
  无 displaced 户
```

- 初始 landed 户可以只有一个；landless 买地后，hex 可自然出现第二个 landed 户；
- “3~4 档家户”可以先作为 landed 户的初始土地规模/财富档（不同 hex 可有不同规模），
  或作为后续扩展；不在生产 mode 里增加四个 slot；
- landless 未被雇时 idle；landed 可自耕/雇工/把闲置 LAND 挂卖；
- 初始 landless 户不获得任何 LAND；
- 如果后续需要多雇主竞争，可在 landed 户中增加数量；单 landed 户不是永久限制。

## 8. 守恒/失败/所有权

- 人口：Social 唯一权威；经济只产生 population outbox；
- LAND：每 hex `Σ owner.quantityMilliMu == arableMilliMu(hex)`；LAND 市场/债务转移都不破坏；
- 钱/货/债：land 成交的现金腿/债务腿走既有普通债路径；不得凭空造钱/免债；
- classness：从 LAND 派生；若缓存，和 LAND 同 ChangeSet 更新，并有不变量；
- 失败：LAND 超卖、买方 land 超出可买量、卖方失去全部 land、债务无法拆分、人口为负/
  超上限 ⇒ 具名拒绝或整次回滚，不静默；
- 确定性：LAND/household/debt 全部稳定序；无随机；
- 日志：LAND_CLASS_TRANSITION、LAND_SALE_IDLE、LAND_BUY_CLASSLESS、CLASS_DOWN_DEBT 等
  INFO/DEBUG；逐笔 TRACE。

## 9. 与 v1/旧机制的关键差异

| 项 | v1（已取代） | v2 |
|---|---|---|
| landless 向上 | class-entry 并入 landed，不拿 LAND | 买市场 LAND，拿 LAND，成为 landed |
| 自动财富向上 | 有 | **取缔**；只有市场买地 |
| LAND 市场买方 | 只有 landed | landed + landless 都可买 |
| 单一 landed 户 | 推荐多雇主 | 初始单 landed 可行；卖地后可出现多个 landed |
| 生产位置 | 两档 | 两档不变 |
| 向下流动 | distress-split 新 landless 户 | 默认整户转 landless；split 待裁定 |
| 劳动供给 | 可 idle | 明确按净劳动收入决定是否供给 |

## 10. 待用户裁定（按优先级）

### R1. LAND 是否真的转给 landless 买方？

按最新原话应“是”：landless 买地后成为 landed。是否确认这**取代**上一轮“不给原本 landless
户添加任何已有土地”的口径？这是最关键的一条。

### R2. 单 hex “只有一个 landed 户”是初态还是永久约束？

若 landless 在本 hex 买地，本 hex 会出现第二个 landed 户。建议：**单 landed 是初态，不是永久；
买地后允许多个 landed 户。** 若您要求永久单 landed，则 landless 只能买其他 hex 的 LAND，
本 hex 的土地必须先集中卖给唯一 landed 户——这需要额外规则。

### R3. 债务清算中 landless 债权人能否直接拿 LAND？

- 允许：landless 可通过债务成为 landed，违反“向上只能买市场土地”；
- 禁止：LAND 只能卖给/转给 landed 债权人；landless 债权人只能收现金/商品，LAND 走市场。
  本文推荐“禁止”，但会改变现有债务 LAND 腿。

### R4. 向下流动：整户转 landless，还是拆出新 landless 户？

- 本文推荐整户保持 HouseholdId 转 landless（简单、身份稳定）；
- 若要在同一 hex 保留“部分有地/部分无地”，才需要拆户；这会新增人口/债务/资产拆分规则。

### R5. “同阶级家户自由交易生产方式”到底交易什么？

请明确是以下哪一种：
- A. 只交易 LAND 所有权/使用权；
- B. 交易整个生产 unit/组织（包括 roster、land、库存）；
- C. 只允许同 classness 选择/切换生产 mode/position。
本文推荐 A + C：LAND 正常交易，生产位置按 classness 门控；B 作为后续企业并购。

### R6. 跨 hex 买地后如何生产？

- 买方必须把家户迁到目标 hex 才能成为该 hex 的 local landed operator；或
- 允许异地持有 LAND，但需要当地 manager/operator；或
- 先限制测试世界只能买本 hex 的 LAND，跨 hex 后做。
本文建议先做“本 hex 买地 → 本 hex 生产”，跨 hex 放到下一阶段，避免未定义的异地经营。

### R7. landless 能否用债务买地？

RF1 已定“需求价格接受者，现金不足成普通货币债”。如果 LAND 也沿用，landless 可以借钱买地，
变成“有地但高负债”的 landed。建议**允许**，但加杠杆/债务压力读数；若要禁止，必须给 LAND
买方单独的现金门，这会与 RF1 冲突。

### R8. 向上流动是否只认市场买地？

继承、赠与、政府授地、债务抵地是否也算？建议测试世界先只允许市场买地，其他路径明确拒绝/留空。

### R9. 初始 3~4 档怎么落？

- 每 hex 一个 landed（tier0）+ 一个 landless 池；或
- 每 hex 3~4 个 landed 户（不同 land 规模）+ 一个 landless 池。
本文建议先做最小“1 landed + 1 landless”冒烟，再扩 3~4 档；若您要一开始就 3~4 档，需要确认
每档是否都是独立 landed 户、是否互为雇主竞争。

### R10. 劳动负收益的“成本”口径

建议：

```text
workCost = 本户一 tick 口粮/生产额外消耗 + 可选的劳动负效用
netWorkIncome = wageIncome - workCost
```

是否允许 landless 拒绝全部工作并因此饿死？本文按“允许，无强制劳动”处理。

## 11. 工程默认（无需用户裁定，可先这样实现）

- classness 从 LAND 派生；缓存需同 revision 更新；
- S7 卖出的 LAND 从下一生产 tick 生效；
- LAND 卖方只能挂本轮未使用的土地；
- LAND 买方数量按 `minLandPerPerson` + 劳动容量 + 市场可售量，不以现金/价格上限截断；
- LAND 成交先现金、后普通债；0 价合法；
- 每市场轮最多一次 class 变更；用 hysteresis/cooldown 防抖；
- 单 hex 只有一个 landed 户时，允许其雇不到人/减少播种/挂卖闲置土地；
- 初始 landless 户无 LAND，不建 displaced 户。

## 12. 非目标

- 不恢复四阶层生产 mode/slot；
- 不恢复财富驱动的自动 class-entry；
- 不恢复租佃/rent/pledge/抵押；
- 不把 LAND 做成 block；
- 不直接改 DB；所有 LAND/人口/债变化仍走 ChangeSet/Revision；
- 不在本批实现跨 hex 迁移网络/文化同化/企业并购。

## 13. 实现前门槛

- R1–R4 必须先由用户裁定；R5–R10 可以在拿到 R1–R4 后由控制方给工程默认；
- 需要先写 taskbook（目标运行序列 + 文件所有权 + 负向用例），再派实现；
- 测试世界先做单 hex 冒烟，再扩 32 hex；
- 不能直接使用 `ModeMigrationSettlement` 作为 LAND 腿：它当前不处理标量 `landHoldings`，
  且 organizer-source 迁移仍被临时跳过。
