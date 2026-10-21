# D-032 商队家户、运输生产方式与市场轮运力约束开发计划（2026-10-21）

> **状态：设计已收口 v2.2，开发计划；尚未写生产代码，不是实现验收。**
> **本版：** 已吸收用户 2026-10-21 补充：单 hex 本地承运、马匹本批无损耗、产出归有产者、
> 市场区统一锚价下各 hex 异质反应；控制方已按用户授权补齐工程裁定。
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md`。
> **父目标运行序列：** `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1 S0–S8。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> **纪律：** 一个责任区一个实现 writer；writer 只写生产代码、只到 `-DskipTests compile`；
> 不写/不跑测试；测试统一由后续测试 owner 负责；不 commit/push；不碰主树。
>
> 本文只规划，不派单。每个责任区正式开工前仍须有对应任务书（用户原话、冻结契约、文件所有权、验收锚点）。

---

## 1. 权威顺序

1. 用户本轮原话、选择器回答与 §2.6 补充裁定；
2. 本计划 v2 与父设计 v2；
3. `2026-10-19-d032-standard-agriculture-architecture-design-v2.md` 的 S0–S8 主链（运输部分除外）；
4. 既有 `haul`、商户阶梯、运输服务商品化文档——仅历史细节，冲突处一律按本计划；
5. 代码事实只在回核现状时使用，不得反过来推翻用户设计。

---

## 2. 用户原话附录（逐字）

### 2.1 运输系统总设计

```text
很好，现在来看一下运力系统，我需要的是一个会根据市场订单产生通用运力需求，以及配套一个运力生产方式吗，当运力需求产生时，可以从已有的家户中自发产生商队家户，商队生产方式也放两档，第一档是组织运输生产的家户，和有地农民一样是独立家户，需要额外的生产资料才能扩大生产规模；第二档是雇佣制家户，允许之前提到的无地农民家户受雇参与；
商队的生产资料暂时和耕地在市场中的表述持平，可以设置为运输马匹，目前不计算其生产，作为可交易商品，运输马匹的测试世界存量必须要大；无地农民购买到土地，成为有地农民；同理。无地农民购买到运输马匹，自动成商人家户；
每个hex都可以有商人家户，单hex的商人家户只承运单hex的运力需求，运力作为商品只在单市场轮有用、不可储存，单个商人家户的运力生产同样可以走自给或者雇佣他人，雇佣的话就产生运输雇佣位置给无地农民家户；这里还要计算商人家户的运力需求规模，因此对于单市场区，不但有劳动力雇佣价格，还有运力价格，市场区运力价格的机制和雇佣价格一样，由最低开始报价，最低为0，如果商人家户发现选择进行商业活动没有综合收益，那么单个商人家户可以根据条件自主选择其他有钱赚的生产位置，例如雇佣农业；
这里遇到一个问题，那就是理论上来说虽然没有需求，但是地主家户可以购买运输马屁，商人家户也可以购买土地，商人家户若选择购买土地且市场上真的有、买到了，怎么办？地主阶层同理；
注意到，这里事实上直接以资本主义逻辑区分了有产者和无产者，单个有产者允许购买多种生产资料，包括土地、运输马匹，并同时根据利润高低组织多种社会生产，例如有地农业、运力提供，但是无产者只能是无产者，其只能作为被雇佣的一方，在买得生产资料时，自动成为对应阶层的有产者，这套机制毫无疑问是合理的；
那么余下要规定的就是这套通用的阶级跃迁系统如何运行了，已有的阶级跃迁已经实现了，无地农民买入当前/另一hex的地，自动成为对应hex属性相同的有产者，那么当无产者买入任何一方提供的运输马匹时，自动成为当前hex的承运家户，如果是混合情况，那么拍个先后顺序，先成为地主，为对应hex的有地农民家户增加对应土地和人数，不增加同时买入的其他生产资料，然后再为当前hex增加商人家户，同样不增加其他生产资料；
现在，对于商业运输，我们已经规定了二元制的生产方式、对应家户的阶级跃迁通道和方式、运力商品的特殊性，值得一提的是，应当先把有地农民和无地农民规范成有产者和无产者——家户不绑定生产方式，生产方式对家户的区分从此只有有产者和无产者，细化阶层划分以后再做
现在根据我的当前原话和之前相关模块的最后设计，开始调查、规范化我的方案并生成对应的新代码结构，发现其中需要我确认的裁定项并让我确认
```

### 2.2 运输批次与每 tick 生产

```text
我不懂你S3S7啥意思，我只知道运输生产应当是每一tick都进行组织——这意味着，实际还是要囤4tick（市场轮间隔）的临时运力在运输家户，囤到有产者商人家户上，然后在市场轮的时候，再统一把这些运力挂到市场上，不同的是，上一市场轮的运力在这一市场轮无法保留，以实现运力分批的效果
```

### 2.3 阶级向下流动

```text
阶级向下流动不需要有产者家户失去所有生产资料，只需要随着负债增加，给一个家户迁移数值就行，只迁移人口的那种——我记得家户人员流动表有搞吧？
```

```text
是的，HouseholdFlowBook不记库存也不记债，只记人净身出户，也就是方向B
```

### 2.4 本轮约束

```text
不需要生成新的代码结构！尤其是不要动项目，不过如果是为了架构设计而写一些小示例倒是可以
```

```text
如果没有其他问题的话，就按照开发计划文档编写标准落盘，顺便再检查一遍有没有裁定
```

### 2.5 继续有效的历史原话

来源：`docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` §1。

```text
商号的本质就是提供运力的家户，运力就是每一笔长途商品需要额外支付的……
算了，把运力也改成可计算的吧，就是单个家户每需求一个商品就算运力，算完之后算一下总共需要多少运力，按照市场上最低价的运力提供商买运力……
运力作为特殊商品不可储存转卖，只能计算后在生产环节统一兑现、自动算收益
跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？
运输服务我不是说算商品吗，只是这个商品的需求需要额外通过其他已有商品的购买来计算，要我选的话我肯定选A
```

来源：`docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md` §15.1。

```text
算了，把运力也改成可计算的吧，就是单个家户每需求一个商品就算运力，算完之后算一下总共需要多少运力，按照市场上最低价的运力提供商买运力，注意"运力"过来同样需要运力，反正购买走的是满足全部需求+没钱全算债务，能跑通吗？
```

来源：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §11。

```text
跨hex运输目前貌似没做生产方式吧？目前可以做一个简单的运力计算函数，然后加一些象征性的价格方便进行测试，后面再完善运力体系
```

> 后两条中的“简单运力函数、不新增运输生产方式”已被 2026-10-21 用户原话推翻。
> 2026-10-09 原话里的“运力过来同样需要运力”递归也被本轮“单 hex 运力只服务本 hex”取代：
> 运力本身不跨 hex；只有运输马匹作为普通商品跨 hex 时才产生普通货运需求。其余历史原话继续有效。

### 2.6 用户补充原话（2026-10-21，逐字）

```text
1、其实我原本想的是，单hex的运力产出只能服务于单hex本身，你没有办法大老远叫别人来运你这边东西
2、是的，目前不算运输马匹的耐久消耗，但是以后要，我准备在之后算一个比较简单的生产损耗以模拟——主要是目前没有马匹生产方式，如果加损耗会妨碍到模拟，之后改生产方式配置吧
3、是的，不过我记得雇工在农业生产方式里的所有产出也是归有产者啊？
7、不不不，要模拟的就是市场上看着欣欣向荣，但是不同hex面对统一价格各有不同的反应
其他要不直接根据我之前的思路，你代替我做裁定？根据我的补充内容，完善设计和开发计划，有裁定再告诉我
```

> 选择器回执补充：2026-10-21 `transport_pricing` =「A 市场区单一运力锚价（推荐）」；
> `mixed_purchase` =「A 按吸收能力拆分（推荐）」；`class_scope` =「A 所有生产资料；有产者也可受雇（推荐）」。
> 用户已授权控制方按此前思路补齐工程裁定；本计划不再把纯实现细节上呈。

### 2.7 债务清偿介质追问（2026-10-21，逐字）

```text
额，所以说，如果有产者欠无产者工资，是不能用土地/货运马匹抵债的？
```

```text
市场轮还债就是该还土地还土地，该还其他东西还其他东西，有一个通用还债顺序的，也就是A；如果无产者家户在期间拥有土地等生产资料了怎么办？没有关系，后续统一转化/合并为有产者家户，中间态流程处理应该是可行的吧？
```

用户已明确选择 **A：市场轮通用还债顺序，货币 → 库存/其他资产 → LAND**。控制方收口：

- S6 **第一次发工资仍只付货币**，不能直接转移 LAND/运输马匹；
- 未付成普通 0‰ 工资债；下一市场轮 S7-M2 与普通债同池；
- 清偿顺序为货币 → 可清偿库存（含运输马匹）→ LAND；
- 本批沿用现有介质集合，不新增非 LAND `OwnershipStake` 的债务转移腿；以后要扩再另立设计；
- 删除 `v4 §11` 的“PROLETARIAN 债权人跳过 LAND 腿”旧例外；
- 债权人直接取得抵债资产并保留原家户；取得 LAND/运输马匹后按资产派生**就地变为 OWNER**；
- 债偿实物转移沿用既有 ledger 直转语义，不走市场成交，不生成新的运输需求/运费；
- 允许 S7-M2 working copy 内“PROLETARIAN 暂持生产资料”；最终同一 revision 按最终资产重新派生 class；
- 下一 S2 按标准 key 合并为 OWNER 户；异 hex LAND 归 creditor，不因债偿强制迁人；
- 这是“债偿取得”向上通道，与市场买入的目标户吸收是两条不同机制。

---

## 3. 已确认裁定与冻结口径

| # | 事项 | 最终口径 |
|---|---|---|
| T1 | 运输生产时点 | 每 tick 都组织；每 tick 产出运输能力 |
| T2 | 运力生命周期 | 累积到本市场轮批次；市场轮统一挂出；上一市场轮不可保留；市场轮末未售作废 |
| T3 | 运输生产方式 | 二元：owner operator + hired driver；可自雇/雇工；**运输能力全归 operator OWNER，雇工只领工资** |
| T4 | 运输马匹 | 耐久、可交易商品；本批不生产/折旧/损耗；测试世界存量大；未来损耗走生产方式配置 |
| T5 | 运力价格 | 每市场区单一锚价；初始 0；按区级 D/S 反馈；**同价下各 hex 可因本地供给/需求不同而反应不同** |
| T6 | 阶级 | OWNER / PROLETARIAN；所有生产资料；生产方式不绑定阶级；阶级跨切片派生，不持久另存 |
| T7 | 有产者劳动 | 有产者也可以受雇；无产者只能进入工资位置 |
| T8 | 合并 key | `(hex, cultureKey, ownershipClass)`，不含 productionMode |
| T9 | 向上跃迁 | **PROLETARIAN** 买 LAND / 买马；LAND + 马混合时按吸收能力拆分人口/钱货债 |
| T10 | 向下跃迁 | 债务扩大触发 HouseholdFlowBook；只迁人口、净身出户；不搬库存/债/资产/经验 |
| T11 | 旧阶级滑落 | 按生产方式不赚钱或债务/资产比改 currentPosition 的 ClassSlide 路径退役 |
| T12 | 旧运输链 | `haul` 普通库存、`MerchantCapacityPool` 劳动+工具、`CARRIER_FEE` 收费腿退出新运输生产路径 |
| T13 | 单 hex 供给范围 | `shippingHex = order.fromHex`；只有 `homeHex == shippingHex` 的本地批次可服务；禁止跨 hex 调拨 |
| T14 | 统一价格与局部差异 | 市场区一个锚价；D/S 按市场区汇总；服务按 hex 本地分配；允许区内“总量满足但局部短缺/闲置”并存 |
| T15 | 当前 hex | 马匹跃迁“当前 hex” = S7-M2 完成、市场交易开始前的买方 `homeHex`；LAND 目标与马匹目标可重合 |
| T16 | 0 迁移边界 | 吸收人口为 0 时不强迁人口，但生产资料仍完整转目标 OWNER 户；目标可为 0 人口 OWNER 壳 |
| T17 | 产出通道 | 运输能力走 non-inventory 服务产出；不得进入 HouseholdInventory，也不得分给 hired_driver |
| T18 | 运费债务 | 有实际承运且买方现金不足才成普通货币债，债权人 = 实际承运户；无运力不成债 |
| T19 | 未来马匹损耗 | 本批不做；未来只加生产方式级损耗配置，不建第二本马匹账 |
| T20 | 工资债清偿 | S6 第一次只付货币；未付成普通 0‰ 工资债；下一市场轮 M2 按货币 → 库存（含运输马匹）→ LAND 清偿 |
| T21 | 债偿取得生产资料 | 删除 PROLETARIAN 债权人跳过 LAND 腿的旧例外；债权人取得 LAND/运输马匹后就地变为 OWNER，不触发市场买入目标户吸收 |
| T22 | 债偿中间态 | 允许 S7-M2 working copy 内 PROLETARIAN 暂持生产资料；最终同一 revision 按最终资产派生 class；下一 S2 标准合并，异 hex LAND 不强制迁人 |

> **性质区分**：T1–T14、T18–T22 有用户原话/选择器/既有后续裁定作依据；
> T15–T17 是用户授权控制方补齐的工程/边界冻结（“当前 hex”定义、0 迁移边界、产出通道）；
> 所有控制方冻结都可由用户一句话推翻，不再作为“待用户裁定”阻塞。

---

## 4. 工程默认 / 控制方冻结（按用户授权收口，可随时被用户推翻）

1. **市场轮批次口径**：当前 `day % 5 == 0` 开市场；非市场日 4 个 tick 累积，市场日 S4/S5 的运输产出也进入本市场轮批次。
2. **运输生产方式 cycleDays = 1**，每 tick 产出；不把 `haul` 库存跨轮保存。
3. **马匹本批无耐久消耗**：不折旧/死亡/繁殖/按趟消耗；按耐久商品资本读取；不建立第二份 `OwnershipStake` 马匹账。未来只加生产方式级的简单损耗配置。
4. **向下 flow 触发/速度**：沿用现有 `HouseholdFlowBook` 的债务增量触发、1% 量级、hysteresis/cooldown、source 至少保留 1 人；target 不再要求同 mode。
5. **向上 mixed 拆分**：土地吸收人数按 `acquiredLand / minLandPerPerson`；马匹吸收人数按 `acquiredHorses / horsePerDriver`；两边权重最大余数法，钱/货/债按各自迁出人口比例拆；总迁移 0 时钱货债不搬。
6. **当前 hex**：马匹跃迁目标 = 买方市场前 `homeHex` 的 OWNER 户；不是土地目标 hex，也不是卖方 hex。
7. **运输价格锚**：市场区级；初始 0；市场轮内不变；轮末按 **区级 D/S** 更新、下一市场轮生效；升/降步长/延迟按工资锚同族默认。
8. **同价分配**：同一 shippingHex 内，按本批次可售 capacityWorkMilli 份额降序 → HouseholdId 升序；
   同一批次容量在该 hex 多个 lane 间共享；lane 按 workMilli 降序 → arrivalHex → commodity 稳定序处理。
9. **运费未付**：有实际承运且买方现金不足时，运费欠款对实际承运户成普通货币债；无本地运力时整笔不成交、不成债。
10. **32hex 测试世界**：每个 hex 的既有 OWNER 家户持有大量运输马匹，并提供可出售余量；不另造“马商”特殊阶级。
11. **实现 id**：运输生产方式概念名为 `caravan_transport`；实现可复用现有 `MERCHANT` 稳定 id，但必须只有一套运输生产方式，不新增第二套商人身份。
12. **需求侧**：`shippingHex = order.fromHex`；承运方在选商之前不参与需求 hex 的确定。
13. **产出/库存分流**：运输能力使用独立 non-inventory 服务输出键；`ProductionRunSettlement.harvest` 必须分流，不能按普通 `outputPerUnit` credit 到库存。
14. **合并重键**：同一市场轮、同一 homeHex 的 `TransportCapacityBatch` 在合户时相加；不同 homeHex 保留分键。
15. **债务清偿介质**：S6 工资第一次只付货币；未付成普通 0‰ 工资债。下一 S7-M2 允许
    货币 → 可清偿库存（含 `transport_horse`）→ LAND 抵债；删除 PROLETARIAN 债权人跳过 LAND 腿的旧例外。
    本批不新增非 LAND `OwnershipStake` 的债务转移腿。
    抵债资产直接归债权人户；取得 LAND/运输马匹后按资产派生就地变为 OWNER，不做市场买入的目标户吸收/人口迁移。
16. **对外标签**：内部统一 `ownershipClass = OWNER/PROLETARIAN`；旧 `SocialClassId` 字符串只作历史兼容/展示迁移，不作为阶级权威。若要全量改名，另立命名清理批。

---

## 5. 目标数据形状（实现前供任务书引用）

### 5.1 阶级

```text
OwnershipClass = OWNER | PROLETARIAN
```

由以下当刻资产派生，不持久另存：

- LAND：Map component owner；
- 运输马匹：actor `HouseholdInventory.balances[transport_horse]`；
- 非 LAND 耐久资产：`EconomyData.assetShares` 中 quantity > 0 的 `OwnershipStake`。

普通商品 `grain/cloth/fiber/tool/iron/wood` 作为消费/投入库存时不单独改变阶级；
其中 `SMALL_TOOL/LARGE_TOOL` 的 `OwnershipStake` 属于耐久资产，计入。
`transport_horse` 虽在商品库存中，但明确登记为耐久商品资本，计入。

跨切片派生输入（只读）：

```text
HouseholdOwnershipFacts {
  household
  landMilliMuByHex                 // Map / transient LandComponentLedger
  commodityBalances                // actor HouseholdInventory，其中 transport_horse 为资本
  nonLandDurableAssets             // economy assetShares，quantity > 0
}
```

- 任何 revision 内都不持久保存 `ownershipClass`；每 tick 由 app/装配层给 economy 提供只读 lookup；
- 不要求 `EconomyData` 构造器独立看见 actor/Map 三域；构造期只校验 economy 自己的资产表；
- 买前/买后边界由“S7-M2 完成后、市场交易前”的 ownershipClass 快照与 acquisition ledger 处理；
- 债偿取得生产资料可在 `DebtFirstSettlement` working copy 内产生“清偿前 PROLETARIAN、清偿后 OWNER”的中间态；
  最终同一 revision 按最终资产重新派生 class，下一 S2 走标准合并，不持久化矛盾 class。

### 5.2 运输生产

```text
caravan_transport（概念名）
  positions:
    caravan_operator  // OWNER，PRODUCT_SHARE / organizer，sharePerMille=1000；取得全部运输能力
    hired_driver      // OWNER 或 PROLETARIAN，MONEY_WAGE / worker，sharePerMille=0；只领工资

recipe:
  cycleDays = 1
  durableCommodityCapacityPerUnit = { transport_horse: H } // Map<CommodityId, Long>；不消耗
  laborPerUnit = L
  nonInventoryOutputPerUnit = { transport_capacity_service: C }
    // Map<ServiceCapacityOutputId, Long>；内部稳定服务键，不属于普通商品词表
```

`ProductionReceipt` 增加非库存服务产出的只读回执；economy 只认 `transport_capacity_service`
一个稳定键并折成 workMilli。该键不进 `EconomyVocabulary.allCommodityIds()`、
不进 `MarketTable.prices`、不进 `HouseholdInventory`。

- 马匹由 **operator 户** 的 actor 商品库存作为唯一数量权威；雇工自带马匹不计入本生产组织；
- `durableCommodityCapacityPerUnit` 与消耗性 `inputPerUnit` 必须是两条不同通道；
- 运输服务输出走 `nonInventoryOutputPerUnit`，不进入普通 `HouseholdInventory`；
- `production` 的产出回执要区分普通库存产出与非库存服务产出；
- economy 在 S5 将 transport_capacity_service 转为 operator 户的 TransportCapacityBatch；
- `ProductionRunSettlement.harvest` 不得将 non-inventory 产出 credit 到库存，也不得按角色份额分给 hired_driver。
- `MONEY_WAGE` 角色 `sharePerMille = 0`（现有契约已强制），所以 hired_driver 不落入服务/商品产出份额池；
- 非库存服务产出绕过普通商品 `RoleDistribution`，直接记给 operator 户；
  transport recipe 只有一个 `caravan_operator` 产品份额角色（share=1000），不建立第二个产品份额角色。
- 一个家户同 homeHex 同 tick 至多一个活跃 `caravan_transport` 组织；其他农业/手工业活动可并存；
  如实现允许同户多运输收据，必须共享同一份马匹库存上限并汇总到同一批次，禁止重复计算资本。
- 为 future wear 预留：以后可在生产方式配置上增加 durable commodity 损耗率；
  本批不实现，不得先以输入扣减冒充损耗。

### 5.3 运输能力批次

```text
TransportCapacityBatchKey = (household, homeHex, marketRoundId)

TransportCapacityBatch {
  household
  homeHex
  marketZone
  marketRoundId
  capacityWorkMilli
}
```

- `marketRoundId` = 本批次将要结算的市场日（绝对 tick；按 `day % 5 == 0` 的下一个市场日）；
- 每 tick S5 累加，市场日当天的 S5 产出也进入当天批次；
- 市场日 S7 使用；
- S7-END 清除当前 marketRoundId 全部批次；
- 不进入商品库存、不进入 LAND、不进入 `assetShares`；
- 合户时，同一 marketRoundId + 同一 homeHex 的批次 capacityWorkMilli 相加；
  不同 homeHex 的批次保留为不同键，不得跨 hex 合并后异地使用；
- 批次存在 `marketZone`，但分配资格仍只由 `homeHex == shippingHex` 决定；
- `marketZone` = 生产时 homeHex 所属区；无市场区/市场表 ⇒ 不产批次且 S3 不选运输；
- S7 只读取 marketRoundId 与当前区均匹配的批次，否则 fail-closed。

### 5.4 运输价格锚

```text
TransportPriceAnchorKey = marketZone

TransportPriceAnchor {
  currency
  pricePerWorkMilli
  shortageStreak
  satisfiedStreak
  lastAdjustedMarketRound
}
```

- 初始 0；
- 0 合法；
- 市场轮内不变；
- 轮末更新，下一轮生效；
- `D_zone = Σ 本区 TransportDemand.workMilli`；
- `S_zone = Σ 本区当前轮 TransportCapacityBatch.capacityWorkMilli`；
- 锚价只按 `D_zone/S_zone` 更新，不用本地匹配结果做二次净额；
- 因服务严格按 hex 本地分配，允许出现 `D_zone <= S_zone` 但某些 hex 无服务、
  另一些 hex 有未售运力——这是目标现象，不是 bug。

### 5.4.1 各 hex 的运输轮观察

```text
TransportHexObservationKey = (marketZone, shippingHex, marketRoundId)

TransportHexObservation {
  demandWorkMilli
  offeredWorkMilli
  committedWorkMilli
  unservedWorkMilli
  unsoldWorkMilli
  anchorPricePerWorkMilli
}
```

- 冻结落在既有 `MarketRoundStats` 上：新增 `lastTransportByHex: Map<HexCoord, TransportHexObservation>`；
  不新增第三个 EconomyData 组件，组件计数仍为 35→37；
- 下一 tick S3，运输户只读自己 homeHex 的上一轮观察；
  `expectedSaleWorkMilli = min(plannedCapacity, demandWorkMilli)`；
- 观察不是需求预留：同 hex 多承运户共享同一观察，实际成交按同价份额分配，可能低于各家预期；
- 无上一轮观察 ⇒ expectedSale = 0，不伪造跨 hex 需求；
- 该观察只影响本 hex 的生产决策/读口，不参与区级锚价 D/S 的二次净额，也不允许跨 hex 补偿。

### 5.5 需求与分配

```text
TransportDemandKey = (marketZone, shippingHex, arrivalHex, commodity)
shippingHex = order.fromHex
TransportDemand.workMilli = CapacityDemand.workMilliOf(...)

LaneCapacityCommitment {
  demandKey
  carrierHousehold
  homeHex                  // 必等于 shippingHex
  capacityWorkMilli
  pricePerWorkMilli
}
```

分配冻结：

```text
每条 demand 只允许 homeHex == shippingHex 的批次
同一 shippingHex 先把多笔 demand 按 workMilli 降序 → arrivalHex → commodity 稳定排序
同一 demand 的本地承运批次按：
  份额 = 本批次可售 capacityWorkMilli / 本 shippingHex 本轮可服务总容量
  份额降序 → HouseholdId 升序
逐份扣减；扣完即止
一个批次容量可在本 hex 多个 lane 间共享，绝不跨 hex
```

未服务量不成交、不成债、不计价；有成交但买方现金不足的运费部分才成普通货币债，
债权人 = 实际承运户；同一 lane 多承运户时按各自实际承运 work 份额拆分债务。

### 5.6 向下流动

复用：

- `EconomyData.householdFlowTables`；
- `HouseholdFlowTable(sourceHousehold, sourceHex, targets, lastObservedDebtPrincipalMilli, lastFlowDay)`；
- 债务增量触发、每市场轮最多一次、同 hex、同 culture、OWNER → PROLETARIAN。

改变执行载荷：

```text
population-only flow:
  人口从 source 转 target
  source 的劳动承诺按迁出人口比例缩减
  不搬钱、货、非 LAND 资产、LAND、债、经验
  不创建 target 的库存/债/承诺
  target 壳不要求 productionMode/currentPosition；只建稳定身份 + 空账户 + Social 待创建标记
```

人口 outbox 契约：

- `EconomyPopulationTransfer.targetMode` 从必填改为 optional/none，或增加无 mode 的 population-outbox 变体；
- 目标壳不复制 source 的 currentPosition；
- 迁入人口在下一 S3 重新获得生产位置；
- Social 仍是人口唯一权威，经济只追加 outbox。

---

## 6. 责任区与实施顺序

> 以下只是计划，不是已派单。每个责任区正式开工前必须先有任务书。

### C1：阶级契约与位置访问

**目标**

- 新增统一的 `OwnershipClass = OWNER | PROLETARIAN`；阶级由跨切片只读事实派生，不持久另存；
- 装配层提供 `HouseholdOwnershipFacts/Lookup`：Map LAND + actor 马匹库存 + economy 非 LAND 耐久资产；
- 用 `OWNER/PROLETARIAN` 取代 `LANDED/LANDLESS` 的**家户阶级语义**；
- `ProductionRole.classNature` 不再作为家户阶级来源；位置只表达“可接受哪种 ownershipClass / 劳动支付形态”；
- 删除 `ProductionPlanBridge` 的 `LANDED cannot wage` 与 `LANDLESS cannot own` 旧门，改为资产门 + 角色支付形态；
- `ClassNatureBook` 不再作为家户 class 唯一读口；改为 ownership lookup 的派生读口；
- `ModeMigrationPolicy`/`ModeMigrationSettlement` 不再承担阶级流动；由 S3 当期活动选择替代，旧类型退出生产链或仅保留纯活动逻辑；
- `ClassSlideSettlement` 退出向下流动链并删除调用；
- 删除 `DebtFirstSettlement` 的“PROLETARIAN 债权人跳过 LAND 腿”旧例外；抵债资产可直接给任意债权人，
  债权人取得 LAND/运输马匹后按资产派生就地变为 OWNER；
- 支持 S7-M2 working copy 的 class 中间态：每笔资产转移后/阶段末按当刻资产重新派生；
  最终 ChangeSet 不得写入“PROLETARIAN + 正生产资料”的矛盾组合；
- S7-M2 的 ownership lookup 必须读同一份活工作副本（Map land ledger / goods / assetShares），
  不能读 S7-M2 前快照；S7-M2 完成后、S7-A 交易前再拍 `ownershipClassAtMarketTradeStart`，
  该快照只用于 S7-B 识别市场买入的 PROLETARIAN 买方；
- 债偿取得不触发市场买入的目标户吸收/人口迁移；下一 S2 按 `(homeHex,cultureKey,ownershipClass)` 标准合并；
  异 hex LAND 归 creditor，不强制迁人。
- 清理 `ClassNature` 在生产契约、payload、seed、codec、invariant 中的家户阶级用途；
- 保留 `PRODUCT_SHARE` / `MONEY_WAGE` 的产出归属语义：工资角色 share=0，不拿产出；
  农业与运输同规则，所有产出归有产者/产品份额角色；
- 明确旧 `SocialClassId` 字符串只是历史展示/迁移标签，不是阶级权威；本批不强制全量改名。

**联动文件/入口必须回核**

- `ProductionRole`、`ProductionModeDefinitionView`、`DefaultProductionModes`；
- `ProductionDefinitionPayloads`、`EconomyPolicyPayloads`、`ProductionReferenceValidation`；
- `ProductionPlanBridge`、`EnterpriseOrganizationBook`、`DebtFirstSettlement`；
- `EconomyDataInvariants` 的 `householdFlowTables` 校验改为 ownershipClass 口径；
- 测试 fixture 中的 class position/mode 构造点。

**依赖：** 无。

**产出：** 编译绿；不跑测试。

### C2：合并 key 与家户结构

**目标**

- `HouseholdMergeBook` key 改为 `(hex, cultureKey, ownershipClass)`；
- `plan()` 签名补入 actor 马匹库存/ownership lookup；不再要求 class standing/currentPosition/classNature；
- 同 hex 同文化同 OWNER / 同 PROLETARIAN 可合并；
- 一个 OWNER 可以拥有多个 production organization/roster；currentPosition 只能作“当前主活动”读数；
- 合并 survivor 规则沿用确定性排序；
- 合并时资产、债务、人口、经验、unit/roster/enterprise/occupancy 等重绑不丢；
- 合并时 `TransportCapacityBatch` 按 `(marketRoundId, homeHex)` 重键并把 capacityWorkMilli 相加；
- `HouseholdMergeSettlement` 不再强制 survivor 必须有 mode/currentPosition；
- `HouseholdTransferRequest.targetMode` 需支持无 mode 的合并目标。

**依赖：** C1。

**产出：** 编译绿；不跑测试。

### C3：向上跃迁（LAND，通用化）

**目标**

- 将现有 `PostLandTradeClassCheck` 的 LANDED/LANDLESS/mode 语义改为 OWNER/PROLETARIAN 且不含 productionMode；
- **使用 S7-M2 完成、S7-A 市场交易开始前的 ownershipClass 快照**判断买方是否 PROLETARIAN，
  不能等到 LAND 已写回后再现算；
- 无产者买 LAND 后，资产 + 按吸收能力迁移人口进入目标 hex 的 OWNER；
- 目标选择同 hex + culture 优先，不要求 productionMode；目标可重合/可新建 OWNER 壳；
- 混合买入的 LAND 部分先执行，马匹部分在 C8 接入；
- 吸收人口为 0 时资产仍完整转入目标 OWNER；不凭空迁人；
- 所有排序确定性、同 revision、失败零半提交；
- 本责任区只处理市场买入的 LAND 向上；债务清偿的 LAND/马匹资产取得归 C1/DebtFirstSettlement 改动，
  但两者都要按“取得后 asset-derived ownershipClass”处理。

**依赖：** C1/C2。

### C4：向下人口流动

**目标**

- `HouseholdFlowBook` 改为 OWNER → PROLETARIAN；
- 目标为同 hex、同 culture 优先，不再要求同 productionMode；
- 保留 `householdFlowTables` 持久状态；
- `lastObservedDebtPrincipalMilli` 只作触发基线/观察值，不等于搬债；
- 触发仍为债务本金相对上轮 observation 的增量；
- 实际执行改为 **population-only**：
  - 只迁人口；
  - 不搬钱/货/债/经验/资产/LAND；
  - source 劳动承诺按迁出人口比例缩减；
  - target 不承接库存/债/承诺。
- 删除 `ClassSlideSettlement` 调用/路径；
- 目标不存在时创建 0 人口 PROLETARIAN 壳：无 productionMode、无 currentPosition，只有稳定身份、空账户和 Social 待创建标记；
- `EconomyPopulationTransfer`/`MigrationSocialBridge` 的 targetMode 改为 optional 或增加无 mode 变体；
- 人口仍只经 outbox 由 Social 写回，经济不直接写 population。

**依赖：** C1/C2；可与 C3 并行（不同文件）但不能同一 writer 跨文件重叠。

### C5：运输马匹商品与耐久资本通道

**目标**

- 定义 `transport_horse` 商品；
- 价格/种子/读口按商品词表规则接入；
- 生产契约新增 `durableCommodityCapacityPerUnit: Map<CommodityId, Long>`（名称可微调，语义冻结）：
  - 只当规模锚，不扣减；
  - 与消耗性 `inputPerUnit` 分离；
  - 必须贯通 `IndustryDefinitionView`、`ProductionRecipe`、probe/roster/execute request、`ProductionRuntimeEngine`、`ProductionProbeInputs`、`ProductionPlanBridge`。
- “容量锚至少一条”的旧 invariant 改为“AssetKind capacity 与 durable commodity capacity 至少一边非空”；
- 马匹只从 operator/owner 户读取；hired_driver 马匹不计入；
- 运输生产方式/两个位置/配方/roster 接入 production；
- 马匹为 0 时运输产出为 0；
- 马匹本批不按趟消耗、不折旧；
- 预留未来生产方式级损耗配置，但本批不实现。

**依赖：** C1（位置访问语义）；可先于 C2/C4。

### C6：每 tick 运输生产与批次

**目标**

- 运输生产方式 `cycleDays=1`；
- 每 tick 生产运输能力；
- production 契约新增 non-inventory 服务产出通道（与普通 `outputPerUnit` 分离）；
- `ProductionRunSettlement.harvest` 分流：普通产出走库存/RoleDistribution；非库存运输能力绕过 RoleDistribution 直接转 economy 批次；
- hired_driver 不取得运输能力份额；
- 同一 household + homeHex 同一 tick 只允许一个活跃 transport 组织；多收据必须共享马匹库存并汇总到同一批次；
- S5 收集生产收据中的运输能力，累加到 operator 户当前 marketRoundId 批次；
- 新增 `TransportCapacityBatch` 持久组件 + ChangeSet/Codec/StateBuilder/往返不变式；
- 批次不进入普通库存；
- 市场轮末清除当前批次；
- 批次复合 key 的 Codec 要显式注册；空表往返与多批次往返都要有测试锚点；
- 批次 cross-table invariant：key 的 household/homeHex/marketRoundId 与值一致；homeHex 必须属于该 marketZone；
  capacityWorkMilli 非负；禁止同一 key 多值或跨轮复用。

**依赖：** C5；C1 的 OWNER 派生。

### C7：运输需求、分配与价格锚

**目标**

- S7 从跨 hex 商品订单派生 `TransportDemand`，`shippingHex = order.fromHex`；
- 在 goods matching 之前生成 lane 容量承诺；只能使用 `carrier.homeHex == shippingHex` 的本地批次；
- 禁止跨 hex 调拨/共享运力；
- `TransportPriceAnchor` 新持久组件 + ChangeSet/Codec/StateBuilder/往返；
- anchor invariant：每个 marketZone 至多一个；currency = 本区法定计价币；price/streak 非负；
- 市场区单一锚价，初始 0；D/S 按市场区汇总；
- S3 预期只共享同一锚价；各户再按本 hex 马匹/劳动/上一轮本 hex 需求观察与其他机会决策，允许不同 hex 不同反应；
- S7 结束写 `TransportHexObservation`，且没有观察时 expectedSale=0，不借别 hex 需求；
- 有意保留“区内总量满足但局部无服务/局部未售运力”；
- 同价按 shippingHex 本地分配，排序和 lane 顺序按 §5.5；
- 商品撮合使用 `LaneCapacityCommitment`；无运力 ⇒ 不成交、不成债、不计价；
- 有实际承运且运费未付 ⇒ 普通货币债务给实际承运户；0 价合法，不得 `max(1,...)` 强改；
- 退役旧 `haul` 服务成市/普通库存路径、`MerchantCapacityPool` 劳动+工具路径，
  以及旧 `CARRIER_FEE` 私有收费腿；
- 清理旧 transport 价格/身份/路由/利润残留：
  `MarketPricer` 的 haul 价、`MerchantIdentity` 的 merchant 身份、路由闸、
  `MarketShipmentBook/ShipmentSettlement` 的旧 haul 分支、`EnterpriseProfitBook` 的 haul 利润腿、
  `MerchantTradeTemplate`/`IndustrySeeder` 的 haul 配方。
- 新路径只保留一条运费腿；`TransferReason` 可用新 `TRANSPORT_FEE` 或明确注释复用 `CARRIER_FEE`，
  但不得同时产生旧、新两条腿。

**依赖：** C6；C2 的合并/组织不阻塞但需兼容。

### C8：向上马匹跃迁与混合拆分

**目标**

- 使用 S7-M2 完成、S7-A 市场交易开始前的 ownershipClass 快照识别 PROLETARIAN 买方；
- 建立本轮商品 acquisition ledger（或从 `MarketReport.Fill` 确定性派生），记录 `transport_horse` 的买方/数量；
- 无产者买运输马匹 ⇒ 目标 = 买方市场前 homeHex 的 OWNER 户；
- 混合 LAND + 马匹按吸收能力拆分人口/钱货债；
- LAND 目标与马匹目标允许重合；每步只携带本步触发的生产资料；
- 第二笔迁移读第一笔后的活余额，不按初值重复切分；总人口/钱/货/债守恒；
- 任一边吸收 0 人时资产仍转入对应目标，不凭空迁人；
- 不回溯本市场轮已完成的运输批次；马匹只能从下一 tick 起进入运输生产；
- 复用 C3 的向上 primitive，扩展 horse 分支；
- 目标不存在时建确定性的 OWNER 壳，并不要求同一 productionMode。

**依赖：** C3 + C5/C6。

### C9：Seeder / 读口 / 32hex

**目标**

- 每个 hex 的既有 OWNER 家户获得运输马匹大库存；
- 每个 hex 有运输生产组织/可形成 carrier 家户；
- 32hex 仍单市场区；
- 读口显示：
  - 家户 ownershipClass；
  - 马匹库存；
  - 本市场轮运力批次（按 homeHex）；
  - 市场区运力锚价；
  - 区内总 D/S、各 hex 的 `TransportHexObservation` 与本地未服务/未售原因
    （用于呈现“统一价格、不同 hex 反应不同”）。
- 不改世界特有 class 字符串；新增词表只落在中心 vocab/seeder；
- `simos-app/src/test/**` 的 32hex fixture 必须由测试 owner 改，实现 writer 不得越界；
  主 seeder 与测试 fixture 的初始马匹数量要一致或由同一合同口径生成。

**依赖：** C1–C8 的形状稳定。

### C10：测试 owner（最后）

- 按父设计 §11 验收锚点写测试，不按代码反推；
- 正例 + 负例；
- 关键变异自证：
  - 批次跨轮清空；
  - 0 价合法；
  - 向下 flow 不搬库存/债；
  - 生产方式切换不改阶级；
  - 运输马匹为 0 ⇒ 0 运力；
  - 无运力不成债；
  - 非 shippingHex 批次被拿来服务 ⇒ 红；
  - 区内 D/S 满足但本地仍完整保留未服务/未售 ⇒ 这是目标现象，不得被“修平”；
  - 雇工不取得运输能力；
  - 工资债第一次只货币、后续可用运输马匹/LAND 清偿；
  - 债偿取得生产资料后 creditor 就地变 OWNER，且最终状态不持久化矛盾 class；
  - 混合跃迁人口/资产守恒。
- 更新 35→37 组件的 `EconomyRoundTripTest`、Codec/StateBuilder/invariant/fixture，
  以及 `MarketRoundStats.lastTransportByHex` 的非空往返；
- 覆盖所有 `IndustryDefinitionView` 构造点，更新 `ProductionRecipe` durable commodity/non-inventory output 往返；
- 32hex fixture 由测试 owner 改；32hex 360 tick / 全仓门禁按项目统一测试纪律；
- 必须写明未验证项。

---

## 7. 验收锚点

| # | 判据 |
|---|---|
| A1 | 运输生产每 tick 组织；`cycleDays=1`；无马匹/无劳动产出 0 |
| A2 | 每 tick 运输产出进入当前 marketRoundId 批次；不进入普通商品库存 |
| A3 | 市场轮使用当前批次；市场轮末清零；上一轮批次不可见 |
| A4 | 跨 hex 商品订单派生运输需求；同 hex 无需求；LAND 无运费 |
| A5 | 商品撮合受 lane capacity commitment 硬约束；本地运力不足不成交、不成债、不计价 |
| A6 | 市场区单一锚价；初始 0；0 价不强制改为 1；下一市场轮生效；D/S 按区级汇总 |
| A7 | 有实际承运且买方现金不足的运费部分成普通货币债，债权人为实际承运户；无本地运力不成债 |
| A8 | `ownershipClass` 由资产派生；生产方式切换不改阶级 |
| A9 | 有产者可受雇；无产者只能进工资位置 |
| A10 | 合并 key 不含 productionMode；合户时运输批次不丢且容量相加 |
| A11 | **PROLETARIAN** 买地/买马向上跃迁；LAND + 马混合按吸收能力拆分人口；目标户允许重合 |
| A12 | 向下 HouseholdFlowBook 只迁人口；库存/债/资产/经验不变；target 可为无 mode 壳 |
| A13 | 旧 ClassSlide/生产不赚钱阶级流动退出；旧 haul/CARRIER_FEE、MerchantCapacityPool、MarketPricer haul 价不残留 |
| A14 | 32hex 每个 hex 有商队生产条件；测试世界马匹存量大 |
| A15 | `shippingHex = order.fromHex`；只有 homeHex 相同的本地批次可参与，跨 hex 调拨必红 |
| A16 | 市场区一个锚价；允许“区内总量满足但局部未服务 + 另一 hex 未售运力”并存，且不得跨 hex 修正 |
| A17 | 运输能力归 operator OWNER；hired_driver 只拿工资，不拿能力份额 |
| A18 | 运输马匹当前不消耗；生产规模下降只因马匹库存/劳动/其他实际约束，不因按趟扣马 |
| A19 | 运输能力不接受普通库存/普通商品订单簿的读写；harvest 非库存分流成立 |
| A20 | 未来马匹损耗只作为生产方式配置扩展位，本批无实现 |
| A21 | S7 写每 hex 本地运输观察；下一 S3 只读自己 homeHex，缺观察按 0，跨 hex 需求不冒充 |
| A22 | 区级锚价只按 `D_zone/S_zone` 更新；本地观察不反向改写锚价或做跨 hex 补偿 |
| A23 | S6 工资第一次只付货币；下一市场轮 M2 普通工资债可按货币→库存（含运输马匹）→LAND 清偿，且无 PROLETARIAN 跳过 LAND 旧例外 |
| A24 | 抵债资产直接给债权人户；债权人取得 LAND/运输马匹后就地变为 OWNER，不触发市场买入的目标户吸收或人口迁移 |
| A25 | S7-M2 允许 working copy 中间态，但最终 ChangeSet 按最终资产派生 class；不得持久化“PROLETARIAN + 正生产资料” |
| A26 | 债偿取得异 hex LAND 不强制迁人；下一 S2 按 creditor homeHex/culture/ownershipClass 标准合并 |
| A27 | 债偿实物转移沿用 ledger 直转，不生成新的运输需求/运费；本批不扩跨 hex 实物债偿规则 |

### 7.1 负向用例

- 无马匹却产出运力 ⇒ 红；
- 运输批次跨市场轮留存 ⇒ 红；
- 无本地运力仍然成交/记运费债 ⇒ 红；
- 用别 hex 的批次服务 shippingHex ⇒ 红；
- 为追求“补齐短缺”而跨 hex 调拨运力 ⇒ 红；
- S3 读取别 hex 的运输需求观察来假装本 hex 有需求 ⇒ 红；
- 用本地未服务/未售结果二次改写区级锚价 ⇒ 红；
- 0 价被改成 1 ⇒ 红；
- 生产方式切换触发 `ownershipClass` 改变 ⇒ 红；
- 向下 flow 搬了货币/商品/债务/资产/经验 ⇒ 红；
- 向下 target 强制复制 source mode/currentPosition ⇒ 红；
- 工资债第一次发放直接转 LAND/运输马匹 ⇒ 红；
- 债偿资产给了错误目标（未给债权人）或未减本金 ⇒ 红；
- 债偿实物转移被当成市场成交并生成运输需求/运费 ⇒ 红；
- 最终状态仍持久化“PROLETARIAN + 正生产资料” ⇒ 红；
- 债偿后 creditor 未派生 OWNER，或下一 S2 未按标准 key 合并 ⇒ 红；
- 混合买地+买马重复 / 丢失人口或资产 ⇒ 红；
- 合并 key 仍含 productionMode ⇒ 红；
- 合户后运输批次丢失或未相加 ⇒ 红；
- 运输能力被 credit 到普通库存或按角色分给雇工 ⇒ 红；
- 旧 `haul`/`CARRIER_FEE` / `MarketPricer` haul 价 / `MerchantCapacityPool` 路径仍被运输生产调用 ⇒ 红。

---

## 8. 文件所有权（草稿，正式派单时按任务书冻结）

### 可能允许写入

```text
simos-economy-api/src/main/java/**
simos-economy/src/main/java/**
simos-production/src/main/java/**
simos-actor-api/src/main/java/**        # 若运输马匹需要类型化商品/资本键
simos-actor/src/main/java/**            # 仅商品库存/读口如需
simos-app/src/main/java/**              # 编排/Seeder/读口/桥接
simos-util/src/main/java/**             # 仅中心商品词典
```

### 仍禁止

```text
src/test/**
pom.xml
tools/**
test-world/**
主树
```

### 明确边界

- production 只拥有运输方式/配方/物理产出（含 non-inventory 服务产出），不拥有批次/价格锚；
- economy 拥有批次、锚价、运输需求/本地分配/撮合、向下 flow；ownershipClass 由装配层只读事实派生；
- actor 拥有马匹/商品库存；
- Map 只拥有 LAND；
- app 只编排/桥接/Seeder；负责装配跨切片 ownership facts；
- 不新增第二个 market/matcher；
- 不保留旧 haul/CARRIER_FEE 双路径；
- 不允许跨 hex 运力池或异地使用批次；
- 所有状态组件改动必须同批改 ChangeSet/Codec/StateBuilder/往返不变式。

---

## 9. 影响表

| 维度 | 影响 |
|---|---|
| 顺序 | S3 每 tick 组织运输生产；S5 累积批次；S7 需求先于商品撮合；S7-END 清零 |
| 预算/冻结 | 市场区运力锚价进入匹配；无本地运力直接收缩成交；有实际承运且运费未付可成普通债 |
| 身份/持久化 | 新增运输能力批次、运输价格锚；马匹在 actor 商品库存；ownershipClass 只派生不存 |
| 空间 | shippingHex 本地；禁止跨 hex 调拨；区内 D/S 与本地分配故意允许局部短缺/闲置并存 |
| 产出归属 | 运输能力全归 operator OWNER；hired_driver 只工资；non-inventory 通道 |
| 失败/回滚 | 跨 hex 使用批次、批次跨轮/超卖、搬库存债、混合拆分不守恒 ⇒ fail-closed；同一 revision 原子 |
| 日志 | 每 tick 运输产出、批次累积、区级 D/S、各 hex 未服务/未售、批次清零、flow 人口、跃迁拆分 |
| 测试 | 测试 owner 按 A1–A27 与负向用例补；更新 35→37、Codec、StateBuilder、invariant、fixture；writer 不写/不跑测试 |
| 旧路径 | `haul` 库存与服务价、`MerchantCapacityPool` 劳动+工具、`CARRIER_FEE`、`ClassSlideSettlement`、mode-profit class migration 退出 |

---

## 10. 风险与 blocker

1. **耐久商品资本契约**：`ProductionRecipe/IndustryDefinitionView` 当前 capacity 只有
   `Map<AssetKind,Long>`；马匹是 `CommodityId` 且不能消耗。必须新增独立 durable commodity capacity 通道，
   贯通 probe/roster/execute/efficiency/plan bridge；不能塞 `inputPerUnit`，也不能新增 `AssetKind.HORSE`。
2. **非库存服务产出**：`ProductionRunSettlement.harvest` 会把 `outputPerUnit` 全量 credit 到库存；
   必须新增 non-inventory 通道并保证 hired_driver 不分得运输能力。
3. **ownershipClass 跨切片输入**：需要 Map LAND + actor 马匹库存 + economy assetShares 的 lookup；
   当前 `HouseholdMergeBook.plan`、`EconomyData` 构造/Invariant 拿不到完整输入，必须改签名或上移校验。
4. **S7 买前/买后快照**：LAND 写回后 PROLETARIAN 立即会派生为 OWNER；C3/C8 必须使用
   “S7-M2 完成后、S7-A 交易开始前”的 ownershipClass 快照；马匹购买还要 acquisition ledger
   （或由 `MarketReport.Fill` 确定性派生）。
5. **lane commitment 与当前撮合顺序不兼容**：当前 `select` 在 `MarketTradeExecutor.executeTrade` 内；
   新设计必须在 goods matching 前分配 lane，同时改造路由/配对/口岸/定价，避免第二套 matcher 或超发。
6. **两个新 EconomyData 组件 + composite key + MarketRoundStats 扩面**：运输能力批次/锚价会改 35 组件状态；
   本地观察还改 `MarketRoundStats` 形状；要同步 ChangeSet/StateBuilder/Codec/key serializer/invariant/往返测试。
   空表往返测不出复合 key 错误，必须有非空批次与非空 TransportHexObservation 往返。
7. **population-only flow 契约**：现有 `HouseholdTransferRequest.targetMode` 与 `EconomyPopulationTransfer.targetMode`
   都强制非 null；无 mode 的 PROLETARIAN 壳必须改契约，否则 C4 无法落地。
8. **向下 target 结构**：target shell 无 currentPosition/mode 时，`EconomyDataInvariants`、merge preflight、
   Social bridge 都要允许；不能复制 source 的 mode 冒充目标身份。
9. **合户批次重绑**：任何同 hex 合户都必须将当前轮 `TransportCapacityBatch` 按 survivor/homeHex 相加；
   否则会丢已生产运力。
10. **ClassNature 跨模块退役**：`ProductionRole`、`DefaultProductionModes`、payload/seed、codec、
    `ProductionReferenceValidation`、`EconomyDataInvariants`、`EnterpriseOrganizationBook`、`DebtFirstSettlement`
    都有联动；只改 `ClassNatureBook`/`ProductionPlanBridge` 会留死语义。
11. **旧运输路径退役面**：不只 `MerchantCapacityPool`，还包括 `MarketPricer` haul 价、`MerchantIdentity`、
    路由闸、`MarketShipmentBook/ShipmentSettlement`、`EnterpriseProfitBook`、`MerchantTradeTemplate`、
    `IndustrySeeder`、迁移/预估引用；必须全量替换为唯一批次+锚价路径。
12. **统一锚价与本地供给的“非收敛”是设计目标**：测试和日志要允许区内 D/S 满足时局部仍有未服务/未售；
    不得加跨 hex 调拨、暗池、二次匹配把它修平。
13. **同价分配口径**：需要冻结份额分母、lane 排序、批次容量跨 lane 共享、同一 lane 多承运户按实际 work 拆收入/欠款；
    本计划 §5.5 已给默认，任务书必须原样落实。
14. **C9 测试 fixture 归属**：32hex 世界在 `simos-app/src/test/**`；实现 writer 禁写测试，
    必须由测试 owner 或明确豁免承接。
15. **35→37 测试迁移**：`EconomyRoundTripTest`、Codec、StateBuilder、fixture、
    `IndustryDefinitionView` 所有构造点、ClassNature 相关测试都要逐项列出，不能只靠“全仓门禁”。
16. **生产规模校验**：运输马匹只读 operator/owner 户，雇工自带马匹不得计入；需要在 probe/roster/execute
    三处用同一份资本视图，不能一处聚合参与户、一处只看 organizer。
17. **未来马匹损耗扩展位**：本批不得顺手做扣马/折旧；预留字段/任务项即可，避免没有马匹生产方式时破坏模拟。
18. **每 hex 本地观察的持久/读口落点**：冻结为扩展现有 `MarketRoundStats.lastTransportByHex`，
    不新增第三个组件；必须由 S7 写、S3 按 homeHex 读，不能额外增加一套锚价或从别 hex 借需求。
    该项仍会改 `MarketRoundStats` 的 canonical/便捷构造器、Codec、RoundTrip 与所有 fixture。
19. **一个家户多运输收据**：同 household/homeHex/tick 至多一个活跃 transport 组织；若实现允许多收据，
    必须共享同一份马匹库存并汇总到同一批次，禁止资本重复计算。
20. **债偿 class 中间态**：`DebtFirstSettlement` 当前先读 classNature 再决定 LAND 腿；必须移除该守卫，
    并在债务转移后的最终状态重新派生 ownershipClass。S7-M2 working copy 内可暂存“PROLETARIAN 有资产”，
    但最终 ChangeSet 不得持久化矛盾组合；异 hex LAND 不强制迁人，下一 S2 才按标准 key 合并。
    债务转移与 ownership lookup 必须共用活工作副本；若 lookup 仍读旧快照，会漏掉本轮取得资产的 creditor。

---

## 11. 未做 / 未验证

- 本计划未写任何生产代码；
- 未改 `EconomyData`/ChangeSet/Codec/StateBuilder；
- 未新增运输马匹商品/生产模式/配方；
- 未改 `HouseholdFlowBook`/`ClassNatureBook`/`MerchantCapacityPool`/`HaulService`；
- 未运行 Maven、测试、verify、gate、32hex 360；
- 未 commit/push，主树未碰；
- 本轮只更新目标设计与开发计划，不把文档完成当实现完成。

---

## 12. 待用户裁定

**无。** 用户已明确授权控制方按此前思路和本轮补充收口；§3 是已确认/frozen 口径，§4 是控制方工程冻结。

若实现前发现的是：
- 用户原话/红线冲突 ⇒ 停手上报用户；
- 跨模块契约或纯工程冲突 ⇒ 控制方在任务书中冻结，不得由 writer 自裁；
- 纯实现困难 ⇒ 采用 fail-closed 最强方案并记 BLOCKED，不冒充 DONE。

---

## 13. 修订记录

- 2026-10-21 v1：按用户运输原话、每 tick 批次、向下净身出户和四项选择落成 C1–C10 计划。
- 2026-10-21 v2：吸收用户补充：
  单 hex 本地承运、马匹本批无损耗但留未来生产方式配置、运输产出归有产者、
  市场区统一锚价下允许各 hex 异质反应；同时把工程默认/跨切片输入/非库存产出/pre-match lane/
  批次合户/35→37 组件/旧路径退役等实施 blocker 写进对应责任区与验收锚点。
- 2026-10-21 v2.1：冻结 `MarketRoundStats.lastTransportByHex` 本地观察、
  单户单 homeHex 单运输组织、0 迁移边界、目标户重合。
- 2026-10-21 v2.2：用户明确选择 A（通用还债顺序：货币→其他资产→LAND）——
  S6 工资第一次仍只货币；后续普通工资债可用库存（含运输马匹）/LAND 清偿；
  删除 PROLETARIAN 债权人跳过 LAND 的旧例外；允许 S7-M2 working-copy 中间态，
  最终按最终资产派生 class，下一 S2 标准合并。
