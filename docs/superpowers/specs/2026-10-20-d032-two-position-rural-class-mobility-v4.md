# D-032 二元农村生产关系、临时土地持有与阶级跃迁 v4（2026-10-20）

> **状态：当前机制草案；已纳入用户 R1–R6 裁定；不是实现验收。**
> **本文取代：** `docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility.md`（v1）、
> `...-v2.md`、`...-v3.md`；v1–v3 只作历史留痕。
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md`
> 的 S3/S4/S7/S8。
> 派生派单计划：`docs/superpowers/plans/2026-10-20-d032-two-position-rural-class-mobility-dispatch-plan.md`。
>
> **局部取代（2026-10-21）：** 本文的 LANDED/LANDLESS 阶级、§6.2 含 productionMode 的合并 key、
> §7 向下流动搬钱货债/经验的口径，已由
> `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` 取代；
> LAND 市场/土地跃迁的宏观方向仍作历史参考。新口径为 OWNER/PROLETARIAN，生产方式不绑定阶级，
> 向下 `HouseholdFlowBook` 只迁人口、净身出户。

## 1. 用户原话（逐字）

### 1.1 本轮补充

```text
理论上来说，在进行计算的时候，市场轮买卖结束、阶级跃迁没有开始的时候，阶级性为agri/landless的家户账面库存上是由土地的，正因如此下一阶段阶级跃迁要根据土地交易的出价方，以把人口按照土地交易的出价来源家户，按比例转移人口以达到把账面土地全部转移走的作用——如果这个家户的其他属性和出价家户一样，那么对应比例人口就转移到购买的土地所属hex的对应有地农民家户，只不过后面单个家户可能还有文化属性，如果文化属性不同，恐怕涉及文化属性判断，乃至要创建新家户，因此这个合并也是一个需要进行额外判断计算的过程，目前自然是直接合并添加
```

### 1.2 R1–R6 裁定

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣

R2：有地的家户挂单根据库存情况等写好的判断挂单，如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走，如果是有地的家户买到了土地，那就是买到了，走正常的生产支配流程

R3：完全可以

R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护

R5：是的，随着人走

R6：测试世界依旧32hex，每个hex一个有地农民家户一个无地农民家户就行，政府和市场区配置照旧
```

## 2. 最终裁定摘要

| # | 事项 | 最终口径 |
|---|---|---|
| R1 | 同 hex 同文化/同生产方式/同阶级家户 | **合并**；有地农民户是小农与地主语义的集合体，不再区分地主/富农/中农/贫农生产位置 |
| R2 | LAND 买卖 | 有地户买入 = 正常产权/生产支配；无地户买入 = 临时持有 LAND，随后 LAND + 按比例人口全部转到购买土地所属 hex 的对应有地户 |
| R3 | 多 hex 买地 | 允许；按每个 hex 的成交 LAND 比例拆分人口 |
| R4 | 人口上限 | **取消 `MAX_HOUSEHOLD_POPULATION` 及所有调用点对该上限的使用**；合并不再按 200 人夹紧 |
| R4b | 家户文化属性 | 现无自由文本文化标签；由 culture 模块新增并维护一个自然语言描述标签 |
| R5 | 钱/货/债 | 随人口按比例走；债务能拆则拆，不能拆具名保留，不得静默丢债 |
| R6 | 测试世界 | 仍为 32 hex；每 hex 1 个有地农民户 + 1 个无地农民户；政府和市场区配置照旧 |

## 3. 目标运行序列

```text
S0 Social
S1 经济载入
S2 进入/迁移/组织
S3 生产位置选择
   - LANDED：有地户，可自耕/雇工/卖地；可代表小农或地主（只是 land/population 比例不同）
   - LANDLESS：无地户，不支配 LAND；被雇才进入农业生产；未雇 = idle
S4 生产日结
S5 收获/毛产分配
S6 工资
S7 市场
   A. 商品 + LAND 撮合
      - 有地户根据库存/生产规划决定挂单
      - 有地户买入 LAND = 正常买入，下一生产 tick 可用
      - 无地户买入 LAND = 临时持有 LAND；classness 仍 LANDLESS
   B. PostLandTradeClassCheck（同一 revision）
      - 对无地买方：LAND + 按比例人口转到购买土地所属 hex 的对应有地户
      - 无地买方账面本轮 LAND 清零
      - 目标有地户的 LAND/人口/资产增加
      - 同文化/同生产方式/同阶级性的多个有地户按 R1 合并
   C. 向下流动
      - 每个有地户维护 flow table；目标 = 本 hex 的无地户
      - 债务扩大 ⇒ 人口 + 按比例钱/货/债流向目标
S8 Social 事实回传
```

**原子性**：S7-A、S7-B、S7-C 在同一 revision；不允许把“LANDLESS 长期持有 LAND”落盘。  
**时间边界**：S7 买入的 LAND 只从下一生产 tick 起进入买方生产规划，不回溯本 tick S4/S5。

## 4. 阶级性（classness）

### 4.1 定义归属

- production contract 中增加统一的 `ClassNature`（名称可再定）：`LANDED` / `LANDLESS`；
- `ProductionModeDefinitionView` 暴露该 mode 允许的 `Set<ClassNature>`，具体生产位置引用单一 `ClassNature`；
- `grain_farm` 的 `operator` 位置 = `LANDED`，`hired_laborer` 位置 = `LANDLESS`（位置级，不用 mode 级单值）；
- economy 从家户当前生产位置派生 classness；production 用同一代码定义判断位置；
- app/seeder 不得再拼第二套 `"landed"/"landless"` 字符串；
- 家户的 classness 由其生产方式/生产位置决定，LAND 是硬约束和容量来源，但 S7-A 允许临时的
  `LANDLESS + LAND>0` 待转移状态。

### 4.2 有地农民的含义

- `LANDED` 不是“地主”专用类，而是**有地农民**：小农、富农、地主都落在同一个位置/阶级；
- 差别体现在：
  - LAND 数量；
  - 家庭人口/劳动；
  - 自耕/雇佣比例；
  - 库存/债务/财富。
- 极端分化可以直接表示为一个 hex 的有地户人口极少、LAND 极多，其余大部分人口是无地户并只能接受雇佣；
- 不再恢复 `landlord / rich_peasant / middle_peasant / poor_peasant` 四个生产位置或四个 class slot。

### 4.3 不变量

```text
正常状态：
  LANDED 户：应有 LAND，或处于 landed 生产位置
  LANDLESS 户：LAND=0，且只能占 landless 位置

S7-A 临时状态（仅同一 revision）：
  LANDLESS 户可以临时持有本轮买入的 LAND
  S7-B 必须把本轮 LAND 清零/转出
```

## 5. LAND 市场

### 5.1 卖方

- 有地户根据库存、种子、劳动、生产规划等既有判断写卖单；
- 只挂本轮/S3 规划中确定不用于生产的 LAND；
- 卖不掉的土地保持原 owner 并闲置，不自动销毁；
- LAND 价格/0 价/现金腿/债务腿沿用既有市场与 RF1 需求价格接受者语义。

### 5.2 买方

- 有地户买入：正常产权/生产支配，LAND 直接加到买方，下一生产 tick 可用；
- 无地户买入：
  1. 市场轮内先把 LAND 记到无地户账上（临时）；
  2. 市场轮末 `PostLandTradeClassCheck` 把该 LAND **全部**转给购买土地所属 hex 的对应有地户；
  3. 按成交 LAND 规模 + 最低土地覆盖 + 财富/速度计算人口迁移量；
  4. 人口 + 按比例钱/货/债迁入目标有地户；
  5. 无地户本轮 LAND 归零；可缩编/成为空壳；
  6. 目标有地户获得 LAND + 人口，classness 保持 LANDED。

### 5.3 多 hex 买入

R3 允许。处理规则：

- 按每个 hex 的成交 LAND 数量比例拆分迁移人口；
- 每个 hex 的 LAND 分别转给该 hex 的对应有地户；
- 钱/货/债按同一人口比例拆分；
- 余数用稳定序 + 最大余数法，不丢人/不丢钱/不丢债。

### 5.4 人口迁移比例

```text
netGrainPerMu =
    outputPerMu × expectedEfficiency - seedPerMu

requiredGrainPerPersonPerCycle =
    dailyNeedPerPerson × cycleDays

minLandPerPersonMilliMu =
    ceil(requiredGrainPerPersonPerCycle × 1000 / max(1, netGrainPerMu))

maxByLand =
    floor(acquiredLandMilliMu / minLandPerPersonMilliMu)

maxByWealth =
    floor(landlessWealth / entryCostPerPerson)

maxBySpeed =
    max(1, floor(sourcePopulation × migrationRatePerMille / 1000))

migrants = min(
    maxByLand,
    maxByWealth,
    maxBySpeed,
    sourcePopulation - MIN_SOURCE_POPULATION)
```

- 不设 `MAX_HOUSEHOLD_POPULATION` 上限（R4）；
- `dailyNeedPerPerson` 优先取 Social 逐户温饱线/年龄性别需求；0.8 kg/人天只作成人重劳力测试默认；
- `outputPerMu`/`seedPerMu`/`cycleDays` 来自生产方式定义，不写死 120；
- 当前 48/12、0.8 kg/人天、120 天、100% 效率示例约 `2.67 mu/人`；
- 当前 32hex 每 hex 3,100 mu、200 人约 15.5 mu/人，土地承载通常不是瓶颈，财富/雇佣/速度更可能限制；
- `MIN_SOURCE_POPULATION` 仍保留，防止一次把源户搬空；它不是人口上限，是迁移下限保护。

## 6. 目标有地户与合并

### 6.1 目标选择

- 目标 = **购买土地所属 hex 的对应有地农民家户**；
- 如果该 hex 只有 1 个有地户，target 就是它；
- 如果该 hex 有多个有地户：
  - 优先找同文化属性、同生产方式、同 classness 的有地户；
  - 多个符合同一 key 的有地户按 R1 合并；
  - 没有同文化/同 key 的有地户 ⇒ 按文化模块判断是否创建新的有地户（当前测试世界默认文化相同，可直接合并）；
- 目标不存在/文化不匹配时，当前先按“直接合并添加”处理；将来 culture 模块启用后再新建家户。

### 6.2 合并 key

```text
mergeKey = (hex, cultureKey, productionMode, classNature)
```

- 同 key 的家户应当合并（R1）；
- `cultureKey` 至少由 culture 模块的家户文化属性决定；不能由 economy 用库存/人口猜；
- 合并 survivor 建议确定性规则：人口最多 → LAND 最多 → HouseholdId 最小；
- 合并后：LAND 合并、人口合并、钱/货/债合并、unit/roster/operator 重绑；
- 不允许因合并丢 LAND/人口/钱/货/债；自债/同户两端相等按既有规则净额处理。

## 7. 向下流动表

每个有地户维护一张流动表：

```text
HouseholdFlowTable {
  sourceHousehold: landed household
  sourceHex: current hex
  targets: [
    {
      targetHousehold: same-hex landless household
      reason: DEBT_DOWNWARD
      cultureKey: ???   // 目标匹配条件
    }
  ]
}
```

- 当前只服务 `DEBT_DOWNWARD`；
- 债务扩大时，只流向**同 hex 的无地户**；
- 表的维护职责包括找到/建立同 hex 的无地目标；
- 若同 hex 有多个无地户：优先同文化/同生产方式/同 classness 者，多个同 key 合并；
- 迁移人口 + 按比例钱/货/债（R5）；LAND 不随人口走；
- 表不跨 hex、不指向有地户、不指向不存在的 id；
- 每市场轮最多一次，带 hysteresis/cooldown；`MIN_LANDED_POPULATION` 只保护源户不搬空，不是人口上限。

## 8. 同阶级生产方式交易 = B

同 classness 的有地户可以交易整个 production unit/组织：

```text
ProductionUnitTransfer {
  sellerHousehold, buyerHousehold
  unit / enterprise
  LAND parcel(s)
  roster participants
  production contracts / anchors
  attached goods/debts
}
```

- 同 classness 才可直接交易；
- 跨 classness 不直接交易 unit，必须先走 LAND 市场/债务流动；
- 第一版只允许 **LAND + unit + roster** 一起交易，禁止半包；
- 交易后 owner/operator/roster 同 revision 重绑，LAND 总量守恒。

## 9. 家户文化属性标签（R4b）

现状：`HouseholdCulture` 已有 typed `CultureTag`（CultureId / ReligionId / customs），但**没有自由文本自然语言标签**。
按 R4 的要求，新增一个自然语言标签，归 culture 模块维护：

```text
HouseholdCulture {
  household: HouseholdId
  tag: CultureTag
  label: String   // 新增：自然语言描述，例如“本地稻农”“山地牧民”
}
```

- label 由 culture 模块维护；economy/production 只读；
- `culture.SetHouseholdCulture` 增加可选 `label` 字段；
- Codec/ChangeSet/往返测试同步；Alpha 不兼容旧档，不写迁移；
- economy 不直接依赖 culture：由 economy-api 提供只读 `HouseholdCultureKeyLookup`（不透明 key），
  culture 模块实现，app 装配；label 仍只由 culture 维护；
- `cultureKey` 建议 = `(CultureTag, label)`；label 缺失时用显式空串/默认值，不猜；
- 同 key 才允许 R1 合并；不同文化属性默认不合并，是否新建 landed 户由 culture 模块判断；
- 当前测试世界可以让所有家户用统一默认文化/标签，先跑通直接合并路径。

## 10. 测试世界（R6）

- 保持 32 hex；
- 每个农村池：
  - 1 个有地农民家户；
  - 1 个无地农民家户；
  - 无 displaced 户；
- 政府和市场区配置照旧；首都 700 城镇人口与既有城市池本轮不重写（机制只作用于农村位置）；
- 初始 3~4 档不作为四个生产 slot；如果保留，只作为有地户的初始 LAND/库存/财富差异；
- 无地户 LAND=0，作为劳动池；有地户按库存/劳动/种子挂卖闲置 LAND；
- 初始文化标签统一，避免一开始就触发文化差异分支。

## 11. 失败/守恒/日志

- 人口：Social 唯一权威；经济只产生 population outbox；同一 revision 回写；
- LAND：每 hex `Σ owner.quantityMilliMu == arableMilliMu(hex)`；
- classness：正常状态与 LAND/生产位置一致；S7 临时 LANDLESS 持有 LAND 不落盘；
- 不设人口上限；但必须有 `MIN_SOURCE_POPULATION`/`MIN_LANDED_POPULATION` 防搬空；
- 钱/货/债按人口比例迁移，不能拆的债具名保留或另记；
- 债务清算的 LAND 腿只允许转给 LANDED 债权人；LANDLESS 债权人跳过 LAND，余债保留，
  LAND 留在债务人/进入市场——否则会绕开“向上只能通过市场买地”；
- 失败：LAND 超卖、landless 临时地未清理、合并丢资产/人口、债务无法拆分 ⇒ 整次 advance 回滚；
- 日志：LAND_TEMP_HOLD、POST_LAND_CLASS_CHECK、LAND_TO_TARGET、CLASS_MERGE、HOUSEHOLD_FLOW_DEBT、
  CULTURE_LABEL_MISSING 等 INFO/DEBUG/TRACE。

## 12. 还需不需要用户裁定？

R1–R6 已经覆盖核心机制。本文认为**R1–R6 没有新的玩法级裁定必须再问**；以下由工程默认决定，除非用户反对：

1. merge survivor：人口最多 → LAND 最多 → HouseholdId 最小；
2. class `ClassNature` 放 production contract；是**位置级**而不是 mode 级单值；
3. 文化 label 的字段名/默认值；`cultureKey` 包含 label；
4. economy 通过 economy-api 的不透明 culture-key lookup 读 culture，不直接依赖 culture 模块；
5. 多 hex 人口分配的最大余数法；
6. `MIN_SOURCE_POPULATION`/`MIN_LANDED_POPULATION` 的具体数值；
7. 目标同 key 合并时 LAND/unit 的结算顺序；
8. 目标无同文化 landed 户时：当前选确定性的既有 landed 户直接合并并记文化不匹配；未来 culture 模块决定是否新建；
9. 债务清算中 LANDLESS 债权人跳过 LAND 腿（这是“向上只能通过市场买地”的直接推论，不是新玩法）；
10. `§8` 同阶级整组织交易不在本批派单；需独立约束设计（对价/支付/债腿/非 LAND 资产估值）。

## 13. 非目标

- 不恢复四阶层生产 slot/slot 表；
- 不恢复财富自动向上；
- 不恢复租佃/rent/pledge/抵押；
- 不把 LAND 做成 block；
- 不直接改 DB；
- 不在本批实现文化传播/同化/文化差异自动新建家户；
- 不把 120 天写死为通用收获周期；
- 不保留 `MAX_HOUSEHOLD_POPULATION` 作为本机制/合并/迁移路径的人口夹紧；实现时必须同步清理所有调用点。

## 14. 用户原话附录

见 §1.1 与 §1.2；日期：2026-10-20。
