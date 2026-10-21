# D-032 二元农村生产关系、阶级性与流动表 v3（2026-10-20，已被取代）

> **取代者：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md`。**
> v4 澄清了 “landless 买方先临时持有 LAND，再按成交检查把 LAND + 人口并入购买土地所属 hex 的
> landed 户” 的具体时序；本文只作历史留痕。
> **状态：控制方第二轮裁定草案，待用户确认一个剩余语义点；不是实现验收。**
> **本文取代：** `docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v2.md`
> 以及 v1；v1/v2 仅作历史留痕。
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 S3/S4/S7/S8。

## 1. 用户最新原话（逐字）

```text
肯定是B啊，土地买卖完成之后，可以再塞一轮无地家户是否获得土地的检查，然后按比例算人口的阶级跃迁，很奇怪吗？
这样一来，家户的阶级性也就必须跟着生产方式的定义走了，经济模块和生产方式模块必须根据阶级性的代码定义来维护，农业生产方式的具体维护路径如上
家户向下流动的规则我不是已经说了吗？单个家户维护一个流动表，除去后面可能会做的文化流动因素，目前的作用就是指定在债务扩大的时候，本hex的有地家户只会流向本hex的无地家户，这张表的维护包括找到本hex的对应无地家户这一职责
根据我上面的陈述再算一遍裁定
```

## 2. 工作解释（先写清楚，避免误读）

我把“B”按以下方式落地：

```text
同阶级性家户自由交易生产方式 = B：交易整个生产单元/生产组织
土地less 买方不直接把 LAND 记到自己名下；
土地成交完成后，追加一轮“无地家户是否获得土地”的检查；
若检查命中，则按土地规模/最低覆盖标准计算人口迁移量，
把相应人口从 landless 户迁入出售土地的 landed 户；
LAND 仍留在 landed 一侧。
```

这不是普通的“土地所有权买卖”，而是**土地交易触发的阶级跃迁**。  
如果你说的 B 不是这个意思，或者要求 LAND 真的转给 landless 买方，则本文的 §4/§5 需要改成 A/C 版本；这是唯一必须再确认的语义点（见 §8 R0）。

## 3. 重算后的裁定表

| # | 事项 | 根据最新陈述的裁定 |
|---|---|---|
| 1 | 生产位置数量 | 农村农业只有两档：`LANDED` 与 `LANDLESS`；富农/中农/贫农/地主不再作为生产位置 |
| 2 | 3~4 档家户 | 只作为初始财富/土地规模档或读数投影；不新增 4 个生产 slot |
| 3 | 无地户劳动供给 | landless 认为 `净劳动收入 <= 成本` 时可以不提供任何劳动；不得强制派工 |
| 4 | 单 hex 一个 landed 户 | 初始可行；landless 可拒绝低收益劳动，landed 可以少种/卖闲置土地 |
| 5 | 同阶级生产方式交易 | **B：交易整个生产 unit/组织**（LAND、roster、生产合同等资产包）；同 classness 才允许 |
| 6 | 跨 classness 的生产方式交易 | 不直接允许；必须先走阶级跃迁（土地交易/债务向下流动）改变 classness |
| 7 | classness 定义位置 | 由**生产方式定义**维护：production contract 定义 `ClassNature`，economy 与 production 共同读同一代码定义 |
| 8 | classness 状态 | 家户的 classness 由其生产位置/生产方式定义决定；LAND 所有权是硬约束/容量来源；不得在两模块各写一套字符串 |
| 9 | 向上流动 | **取缔财富自动向上**；landless 只能通过市场土地交易 + 成交后的阶级检查上升，不能靠“财富变多自动并入 landed 户” |
| 10 | 向下流动 | **保留**；每个 landed 户维护一张流动表，债务扩大时只流向**同 hex 的 landless 户**；流动表维护包括找到/建立该 hex 的 landless 目标 |
| 11 | 向下流动内容 | 默认迁移人口（和按比例的钱/货/债，具体见 §6.3），不是整户自动改 class；land 留在 landed 一侧 |
| 12 | 向上流动触发 | 土地成交完成后追加一次 `PostLandTradeClassCheck`；若 landless 获得土地（按 B 的解释是“获得进入 landed 户的资格”），按比例算人口迁入 landed 户 |
| 13 | 土地成交量与人口比例 | 用最低土地覆盖/净产出容量计算，不直接用成交金额；详见 §5 |
| 14 | 债务清算中的 LAND | 不允许把 LAND 直接转给 landless 债权人（否则绕开“向上只能买市场土地”）；landless 债权人收钱/货，LAND 留给 landed/市场 |
| 15 | 初始预设 | 每 hex 可以只有一个 initial landed 户 + 一个 landless 池；不建 displaced 户；买地/阶级跃迁后是否出现第二个 landed 户取决于 R0 |

## 4. classness 的代码定义

### 4.1 单一词表

建议在 production contract 增加稳定词表（名字可再定）：

```text
ProductionClassNature {
    LANDED,
    LANDLESS
}
```

- `ProductionModeDefinitionView` / `ClassSlot` / 生产位置定义引用它；
- economy 的 household classness 与 production 的 mode/position 使用同一个类型，不得在 app/economy 重新拼 `"landed"`/`"landless"` 字符串；
- 需要给 `ClassNature` 一个稳定 id 解析/序列化口，所有 codec/ChangeSet 往返都走它。

### 4.2 谁维护什么

- production：定义哪些生产 mode/position 属于 `LANDED` 或 `LANDLESS`；
- economy：维护每个家户当前 classness（或派生缓存）及其与 LAND/position 的一致性；
- app：只装配/播种，不定义 classness；
- Social：只执行人口变更，不定义 classness。

### 4.3 不变量

```text
household.classNature == LANDED
    ⇒ 该户至少在一个 hex 有 LAND，或处于 landed 生产位置
household.classNature == LANDLESS
    ⇒ 该户没有 LAND，且只能占 landless 位置
```

若 classness 是缓存，必须与 LAND/position 在同一个 ChangeSet/Revision 更新；否则必须从 LAND/position 派生，不能独立写。

## 5. 向上流动：土地成交后的阶级检查

### 5.1 两阶段

```text
阶段 1：LAND 市场成交（B：landless 买方不直接获得 land 所有权）
阶段 2：PostLandTradeClassCheck
        - 找出本次成交的 landless 买方
        - 按成交 land 规模计算可承载人口
        - 在买方户内取有界人口切片
        - 迁入出售土地的 landed 户
        - 买方 landless 户可缩编/变空壳；不获得 LAND
```

### 5.2 人口比例

```text
netGrainPerMu =
    outputPerMu × expectedEfficiency - seedPerMu

requiredGrainPerPersonPerCycle =
    dailyNeedPerPerson × cycleDays

minLandPerPersonMilliMu =
    ceil(requiredGrainPerPersonPerCycle × 1000 / max(1, netGrainPerMu))

maxByLand =
    floor(transactionLandMilliMu / minLandPerPersonMilliMu)

maxByWealth =
    floor(landlessWealth / entryCostPerPerson)

maxBySpeed =
    max(1, floor(sourcePopulation × migrationRatePerMille / 1000))

migrants = min(maxByLand, maxByWealth, maxBySpeed,
               sourcePopulation - MIN_SOURCE_POPULATION)
```

- `transactionLandMilliMu` 是本次成交的 LAND 数量；
- `dailyNeedPerPerson` 优先用 Social 逐户温饱线，不用全世界统一 0.8；
- 当前 48/12、0.8 kg/人天、120 天、100% 效率的示例约 `2.67 mu/人`；
- 当前 32hex 每 hex 3,100 mu、200 人约 15.5 mu/人，土地承载通常不是瓶颈，财富/雇佣/速度才是。

## 6. 向下流动：单个家户流动表

### 6.1 表形状

```text
HouseholdFlowTable {
  sourceHousehold: HouseholdId
  sourceHex: HexCoord
  targets: [
    {
      targetHousehold: HouseholdId   // 必须是同 hex 的 landless 户
      reason: DEBT_DOWNWARD
      // 未来可扩 culture/production 等贡献
    }
  ]
}
```

当前阶段只有 `DEBT_DOWNWARD` 一行，目标是**同 hex 的 landless 户**。  
维护责任包含：

- 找到该 hex 的 landless 户；
- 若不存在，按规则建立/指定一个 landless 户（stable id，如 `hh-<hex>-landless-split-<n>`）；
- table 不跨 hex、不指向 landed 户、不指向不存在的 id。

### 6.2 触发

```text
if landed household debt grows / debtStress exceeds threshold:
    target = same-hex landless household from table
    flowPopulation = bounded proportion of source population
    move population to target
```

- 只影响人口（以及 §6.3 的按比例钱/货/债）；
- 不把 LAND 转给 landless；
- 不因财富多而自动向上；
- 每市场轮最多一次；cooldown/hysteresis 防抖；
- `flowPopulation` 可参考 `debtStress × slope`，但必须保留 `MIN_LANDED_POPULATION`，避免一轮搬空。

### 6.3 钱/货/债是否随人走

工作默认（可被用户推翻）：

- 人口迁移时，按人口比例迁移**钱和货**；
- 债务按合同/债务人规则处理：不由流动表直接“送给” landless 户；
  如果债务是个人债且合同允许，则按比例拆分；如果无法拆分则保留在原 landed 户并记具名 reason。
- 任何拆分必须保持债权人/债务人稳定，不静默丢债。

## 7. 同阶级生产方式交易 = B

同 classness 的家户可以交易**整个生产 unit/组织**，建议资产包：

```text
ProductionUnitTransfer {
    sellerHousehold, buyerHousehold
    unit / enterprise
    LAND parcel(s)
    roster participants
    production contracts / anchors
    goods/inventory attached to the unit?   // 待定
    debts attached to the unit?             // 待定
}
```

- 只有 same classness 可以直接成交；
- 跨 classness 的 unit 交易不得直接落，必须先走 §5/§6 的阶级跃迁；
- 交易后 unit/roster/LAND 的 owner/operator 同步重绑；
- LAND 总量、人口、钱/货/债守恒；
- 如果只交易 unit 而不交易 LAND，必须显式说明 unit 是“经营合同”还是“资产包”；建议第一版**只允许 land+unit+roster 一起交易**，避免半包状态。

## 8. 仍需确认/裁定的点

### R0（唯一硬冲突）：B 下 landless 买方到底有没有 LAND？

- **B（本文工作解释）**：landless 买方不获得 LAND；土地仍留 landed 侧，只迁移人口。
  这样单 hex 可以长期只有一个 landed 户，但“买地”实质是阶级进入，不是产权买卖。
- **A**：landless 买方获得 LAND，成为新的 landed 户；同 hex 会出现多个 landed，单 landed 只能是初态。
- **C**：LAND 转给一个由买方人口形成的新 landed 户，而不是原 landless 户；需要新 household 身份规则。

请只确认这一条：**你要 B（不转地、迁人）还是 A/C（转地、landless 成为 landed 户）？**

### 其余已可按工程默认推进

- R1 单 landed 是否永久：若 R0=B，可长期单 landed；若 R0=A/C，单 landed 仅初态。
- R2 3~4 档：作为初始 LAND/财富档，不新增生产位置；具体每 hex 几户可先 1 landed + 1 landless。
- R3 债务清算给 landless 债权人：按本文 §3 第 14 条，不转 LAND；只收钱/货。
- R4 向下流动：按 §6 的 per-household table，同 hex landless 目标；不再问“整户还是拆户”，默认按比例迁移人口。
- R5 同阶级交易：B，整 unit/组织；资产包细节可工程默认。
- R6 跨 hex 买地：若 R0=B，landless 买其他 hex 土地 = 人口迁入那个 hex 的 landed 户；需 Social 跨 hex 迁移；建议测试世界先做同 hex。
- R7 债务融资：沿用 RF1 的“先现金、未付成普通债”；若 R0=B，它相当于 class-entry 费用融资，需要额外杠杆读数。
- R8 初始 3~4 档：先按 landed 户的初始 land/wealth 档；不恢复四个生产 slot。

## 9. 非目标/边界

- 不恢复四阶层生产 mode/slot；
- 不恢复财富驱动的自动向上流动；
- 不恢复租佃/rent/pledge/抵押；
- 不把 LAND 做成 block；
- 不直接改 DB；所有 LAND/人口/债/classness 变化仍走 ChangeSet/Revision；
- 不在本批实现跨 hex 迁移网络/文化同化/企业并购；
- 不把当前 360 的 land price 0 当作新机制已生效证据。

## 10. 用户原话附录

见 §1 逐字块。日期：2026-10-20。
