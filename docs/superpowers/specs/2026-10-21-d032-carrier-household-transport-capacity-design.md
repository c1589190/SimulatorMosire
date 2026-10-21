# D-032 商队家户、运输生产方式与市场轮运力约束设计（2026-10-21）

> **状态：用户已确认的目标设计 v2.2；本文只落设计与控制方冻结的工程口径，不落生产代码，不是实现验收。**
> **局部取代：**
> - `docs/superpowers/specs/2026-10-21-d032-hex-land-ownership-and-transport-commerce-integration.md` §6–§9（运输/商业接入部分）；
> - `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §8.10 C30（“不新增运输生产方式”）；
> - `docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` 的 `haul` 普通库存/旧运力池实现方向；
> - `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` v3.26 的“运力全市场区共享、可跨区被雇”供给范围；
> - `docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` 中 LANDED/LANDLESS 阶级、mode 入合并 key、S7-C 搬钱货债、§11“PROLETARIAN 债权人跳过 LAND 腿”的口径。
> 旧文保留历史留痕；冲突处以本文和用户最新原话为准。
>
> 父目标运行序列：`docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §1 S0–S8。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 不碰主树，不 commit/push。

---

## 0. 一句话结论

运输不是一条独立市场，而是标准生产链的一环：

```text
每 tick：
  有产者 operator 户用“运输马匹 + 自雇/雇工劳动”生产运输能力
  产出全部归 operator 户；雇工只领工资
  能力以“本市场轮批次”暂存在 operator 户上

市场日：
  商品订单先派生通用运输需求，shippingHex = order.fromHex
  本轮批次按市场区单一锚价挂出，但只能由同一 hex 的本地批次服务
  商品撮合只能在已分配的本地 lane 运力内成交

市场轮末：
  已售运力核销
  未售运力整批作废
  不跨市场轮保存

阶级：
  有产者/无产者只由生产资料持有状态决定
  生产方式切换不是阶级流动
  向下阶级流动 = 债务扩大触发“只迁人口、净身出户”的 HouseholdFlowBook
```

---

## 1. 用户原话附录（逐字，不改写）

### 1.1 本轮运输系统总设计原话（2026-10-21）

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

### 1.2 运输批次与每 tick 生产原话（本轮确认）

```text
我不懂你S3S7啥意思，我只知道运输生产应当是每一tick都进行组织——这意味着，实际还是要囤4tick（市场轮间隔）的临时运力在运输家户，囤到有产者商人家户上，然后在市场轮的时候，再统一把这些运力挂到市场上，不同的是，上一市场轮的运力在这一市场轮无法保留，以实现运力分批的效果
```

### 1.3 阶级向下流动原话（本轮确认）

```text
阶级向下流动不需要有产者家户失去所有生产资料，只需要随着负债增加，给一个家户迁移数值就行，只迁移人口的那种——我记得家户人员流动表有搞吧？
```

```text
是的，HouseholdFlowBook不记库存也不记债，只记人净身出户，也就是方向B
```

### 1.4 本轮约束原话

```text
不需要生成新的代码结构！尤其是不要动项目，不过如果是为了架构设计而写一些小示例倒是可以
```

```text
如果没有其他问题的话，就按照开发计划文档编写标准落盘，顺便再检查一遍有没有裁定
```

### 1.5 相关历史原话（继续有效，除非被本文明确取代）

来源：`docs/superpowers/specs/2026-10-10-haul-service-commodity-design.md` §1。

```text
商号的本质就是提供运力的家户，运力就是每一笔长途商品需要额外支付的……
算了，把运力也改成可计算的吧，就是单个家户每需求一个商品就算运力，算完之后算一下总共需要多少运力，按照市场上最低价的运力提供商买运力……
运力作为特殊商品不可储存转卖，只能计算后在生产环节统一兑现、自动算收益
跑商不是生产方式吗？难到家户不会给预估生产方式预留生产资料吗？
我觉得更该排查的是为什么跑商作为生产方式会和其他类型的生产隔离开来
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

> 说明：2026-10-19 的“简单运力函数、不新增运输生产方式”是当时的工程默认，已被 2026-10-21 用户明确要求推翻；
> 2026-10-09 的“运力过来同样需要运力”递归也被 2026-10-21 的“单 hex 运力只服务本 hex”取代：
> 运力本身不跨 hex；只有运输马匹作为普通商品跨 hex 时才产生普通货运需求。见本文 §12 口径溯源。

### 1.6 本轮已确认的工程裁定选项

- 运力价格：**市场区单一锚价**；
- 混合土地+马匹买入：**按吸收能力拆分人口/钱货债**；
- 阶级：**所有生产资料**都计入有产者判定；**有产者也可以受雇**。

> **选择器回执（2026-10-21）**：`transport_pricing` =「A 市场区单一运力锚价（推荐）」；
> `mixed_purchase` =「A 按吸收能力拆分（推荐）」；`class_scope` =「A 所有生产资料；有产者也可受雇（推荐）」。
> `transport_timing` 的自由文本答复逐字见 §1.2。

### 1.7 用户补充裁定原话（2026-10-21）

```text
1、其实我原本想的是，单hex的运力产出只能服务于单hex本身，你没有办法大老远叫别人来运你这边东西
2、是的，目前不算运输马匹的耐久消耗，但是以后要，我准备在之后算一个比较简单的生产损耗以模拟——主要是目前没有马匹生产方式，如果加损耗会妨碍到模拟，之后改生产方式配置吧
3、是的，不过我记得雇工在农业生产方式里的所有产出也是归有产者啊？
7、不不不，要模拟的就是市场上看着欣欣向荣，但是不同hex面对统一价格各有不同的反应
其他要不直接根据我之前的思路，你代替我做裁定？根据我的补充内容，完善设计和开发计划，有裁定再告诉我
```

控制方按上述授权作出的补充裁定：

1. **单 hex 自足**：运输能力不得跨 hex 调度。每笔跨 hex 商品订单的需求 `shippingHex = order.fromHex`；
   只有 `carrier.homeHex == shippingHex` 的本地批次可以参与该 lane。禁止把别 hex 的运力拉来服务本 hex。
2. **马匹暂不折旧**：当前配方不消耗运输马匹；以后由运输生产方式配置增加简洁的生产损耗/折旧项，
   本批只保留扩展位，不实现损耗。
3. **产出归经营者**：运输能力产出归 `caravan_operator` 所属 OWNER 家户，和农业生产中雇工劳动产出归有产者同口径；
   `hired_driver` 只领取工资，不取得运输能力份额。
4. **统一价格、局部异质反应**：市场区只有一个运力锚价；锚价的 D/S 按市场区总需求/总有效批次统计。
   但服务分配严格按 shippingHex 本地进行 ⇒ **全区总量可能“繁荣/满足”，同时某些 hex 仍有未服务需求、另一些 hex 有未售运力**。
   这是目标世界要模拟的结果，不是需要抹平的 bug。

### 1.8 债务清偿介质澄清（2026-10-21）

用户原话（逐字）：

```text
额，所以说，如果有产者欠无产者工资，是不能用土地/货运马匹抵债的？
```

```text
市场轮还债就是该还土地还土地，该还其他东西还其他东西，有一个通用还债顺序的，也就是A；如果无产者家户在期间拥有土地等生产资料了怎么办？没有关系，后续统一转化/合并为有产者家户，中间态流程处理应该是可行的吧？
```

用户随后明确选择 **A：市场轮通用还债顺序，货币 → 其他资产/库存 → LAND**。
结合“工资债就是一般债务，允许使用库存”“货币→库存→土地”的既有裁定，收口为：

- **第一次发工资（S6）不能**直接拿土地/运输马匹抵工资；只付货币，未付部分立即形成普通 0‰ 工资债；
- **后续市场轮 S7-M2 债务清偿可以**用货币、可清偿库存、LAND 抵债；
- `transport_horse` 是家户商品库存，因此属于可清偿库存；LAND 仍按当前市场区地价估值；
- 本批沿用现有通用介质集合，不新增 `OwnershipStake` 非 LAND 耐久资产的债务转移腿；
  若以后要把作坊/工具等份额也纳入，另立设计；
- **不再保留**“PROLETARIAN 债权人跳过 LAND 腿”的旧例外；
- 债权人直接取得抵债资产并留在自己家户：PROLETARIAN 债权人收到 LAND/运输马匹后，
  由资产派生自动变为 OWNER，无需市场买入目标户吸收、无需迁移人口；
- 这是独立于“市场买入生产资料”的第二条向上通道；
- 价格缺失/0 价的资产不自动冲本金，余债保留；任何转移必须先减本金、同一 revision 原子。

---

## 2. 当前代码事实（回代码核过）

以下只列影响本设计的现行事实，不是实现验收：

1. 当前日序是 S0 Social → S1 载入 → S2 进入/迁移/组织 → S3 位置选择 → S4 生产日结 → S5 收获 → S6 工资 → S7 债务/市场 → S8 事实回传；
   economy 文件覆盖 S3–S8 编排到收尾：`EconomyDayPipeline.java:59-130`；S0 在 app/Social 侧。
2. 当前市场每 5 天开一次（`day % 5 == 0`；无市场表 ⇒ 不开）：
   `MarketConstants.java:16`、`MarketTriggerBook.java:21-26`。
3. 当前运力池在市场轮装配阶段、订单/撮合之前装配；运输需求在单笔跨格成交时现算：
   `SettlementDayMarketPipeline.java:120-138`、`MarketRoundRunner.java:475-520`、
   `MarketTradeExecutor.java:163-211`。
4. 当前运力来自“总劳动 + 工具”或可交付 `haul` 库存：
   `MerchantCapacity.java:124-179`、`MerchantCapacityCore.java:112-143`。
5. 当前 `haul` 是普通商品库存；未卖服务在产出 `haul` 的 trade unit 关账时作废，不是本市场轮批次：
   `HaulService.java:59-96`、`ShipmentSettlement.java:78-193`、
   `SettlementDayProductionPipeline.java:827-849`。
6. 当前阶级 `ClassNature` 只有 `LANDED/LANDLESS`，由生产位置派生，并会拒绝同一户混合 class nature：
   `ClassNature.java:15-19`、`ClassNatureBook.java:36-70`。
7. 当前合并 key 为 `(hex, cultureKey, productionMode, classNature)`：
   `HouseholdMergeBook.java:52-64`。
8. 当前阶级向下流动 `HouseholdFlowBook` 会搬人口、钱、货、债、经验，并缩减源户劳动承诺（LAND 不随人走；
   人口权威仍是 Social，经济只追加 outbox）：`HouseholdFlowBook.java:244-336`、
   `HouseholdTransferSettlement.java:188-231`。
9. 当前 `ClassSlideSettlement` 按债务/资产比重写 `currentPositionId`；这是本文要退役的旧“阶层滑落”路径。
   当前代码已对声明 classNature 的户部分跳过，但旧路径仍在：
   `ClassSlideSettlement.java:97-190,260-272`。
10. 当前没有运输马匹概念；`AssetKind` 只有 `CATTLE` 等，商品词表也没有 horse：
    `simos-actor-api/.../asset/AssetKind.java:11-18`、`simos-util/.../EconomyVocabulary.java:149-157`。
11. 当前 32hex 是单派生市场区；只有首都等有城镇人口的 hex 播种 trade 产业：
    `Rural32HexWorldFixtureTest.java:173-180`、`ProductionRuntimeSeeder.java:159,203-206`。

---

## 3. 目标运行序列（S0–S8 的运输增量）

### 3.1 每 tick

```text
S0 Social
  不变：人口/劳动/温饱权威仍在 Social。

S1 经济载入
  载入：
    - 家户资产：LAND（Map owner）、运输马匹（家户商品库存）、非 LAND 耐久资产
    - 当前运输能力批次（上一 tick 累积、本市场轮有效）
  派生：
    - ownershipClass = OWNER / PROLETARIAN
    - 家户当前可参加的生产位置组合

S2 进入/迁移/组织
  - 同 (hex, culture, ownershipClass) 的 owner 家户先合并；不再要求同 productionMode
  - 无产者只进入工资位置；有产者可进入经营者位置，也可进入工资位置
  - 有产者可同时拥有农业、运输等多个生产组织

S3 生产位置选择
  - 每个 tick 都重新组织运输生产位置（与农业、手工业一起排序选择）
  - 商队家户可用自雇劳动，也可生成 hired_driver 工资位置
  - 同一市场区的商队看到同一个运力锚价，但各自再按本 hex 的马匹、劳动、
    上一轮本 hex 的 TransportHexObservation 与其他生产机会计算是否生产及规模
    ⇒ 不同 hex 的供给反应可以不同；无本 hex 观察 = expectedSale 0，不借别 hex 需求
  - 商队家户若认为运输收益不足，可选择其他生产活动（包括受雇农业）

S4 生产日结
  - 运输生产方式 cycleDays = 1
  - 输入：运输马匹（耐久商品资本，不按趟消耗）+ 实际劳动
  - 输出：本 tick 的运输能力工作流（不是普通商品库存）
  - 农业/手工业仍走各自既有生产路径

S5 收获/产出入账
  - 农业/手工业走既有结算
  - 运输产出的“本 tick 运输能力”全部归 caravan_operator 所属 OWNER 家户；
    雇工劳动只产生工资义务，不取得运输能力份额
  - 运输能力进入 operator 家户的 TransportCapacityBatch[marketRoundId]，
    不进入 HouseholdInventory
  - S5 必须按“非库存产出”分流；不能把运输能力当普通商品产出发给参与角色

S6 工资
  - 运输雇工 hired_driver 按本 tick 实际劳动结算工资
  - 付不出 ⇒ 普通工资债（沿用既有工资机制）
  - 运输收益不足由商队经营者承担，不因亏损自动改变阶级
  - 经营者/有产者本人参与劳动时，其劳动不另发“自己给自己”的工资腿；
    产出仍归经营者

S8 Social 事实回传
  - 仍回传本 tick S4 已发生的温饱/缺口；运输批次不进入本条
```

### 3.2 市场日 S7

```text
S7-M2 债务清算
  沿用既有债务清算；不因运输批次改变优先级。

S7-A 订单构建
  生成商品买卖订单、LAND 订单。
  同 hex 交易不产生运输需求；LAND 本身不付运费。

S7-T 运输需求派生
  从跨 hex 商品订单派生：
    TransportDemand(marketZone, shippingHex, arrivalHex, commodity, workMilli)
  shippingHex = 商品订单的发货 hex（order.fromHex），不是承运商 homeHex 的倒推。
  数量口径沿用现有 CapacityDemand（商品数量 × 距离/重量系数）。
  同 hex 交易不产生需求。

S7-CAP 运输能力分配
  读取当前 marketRoundId 的全部 TransportCapacityBatch。
  每市场区只有一个运力锚价；所有批次看到同一个价格。
  对每条需求，只允许 shippingHex == carrier.homeHex 的本地批次参与。
  以稳定序分配 lane 运力，形成 LaneCapacityCommitment。
  不允许跨 hex 调拨、借调、汇总后异地使用运力。
  运力不足的货量不成交、不成债、不计价，具名 LOGISTICS_CAPACITY。

S7-MATCH 商品/土地撮合
  商品撮合只能使用已承诺的 lane 运力。
  运费由买方支付给 carrier owner household。
  买方现金不足的运费部分按既有“未付成交款”原则成普通债；无运力则不成债。

S7-B 向上阶级跃迁
  处理本轮成交的 LAND / 运输马匹购买：
    无产者买入生产资料 ⇒ 资产 + 按吸收能力拆分的人口转入对应 OWNER 家户。

S7-C 向下人口流动
  HouseholdFlowBook：债务扩大 ⇒ OWNER 家户只迁人口到同 hex PROLETARIAN 家户，
  不搬库存、不搬债、不搬资产。

S7-END 批次清零
  本 marketRoundId 的所有运输能力批次清零。
  未售出容量作废；不进入下一市场轮。
```

### 3.3 市场轮批次的时间边界

当前市场轮间隔是 5 tick（`day % 5 == 0`）。本文采用以下解释：

```text
上一市场轮结束后：
  tick 1..4（非市场日）每次 S5 的运输产出累加到下一市场轮批次
市场日：
  S4/S5 的运输产出也进入同一市场轮批次
  S7 使用该批次
S7-END：
  整个批次（含市场日新增、含未售部分）清零
```

因此正常会看到 **4 个非市场 tick 的累计 + 市场日 S4 的产出**。这是“每 tick 都组织运输生产”与“上一市场轮运力不能保留”的直接展开，不是额外的玩法裁定。

---

## 4. 阶级模型

### 4.1 二阶级

```text
OwnershipClass = OWNER | PROLETARIAN
```

判定唯一依据是生产资料持有：

```text
OWNER = 任一正数量生产资料
  1. LAND（Map component owner；家户的 Σ quantityMilliMu > 0）
  2. 运输马匹（家户商品库存 > 0）
  3. 非 LAND 耐久资产（assetShares 中 quantity > 0）
     CATTLE / SMALL_TOOL / LARGE_TOOL / WORKSHOP / MACHINE / SHIP

PROLETARIAN = 以上全部为 0
```

说明：

- 阶级由资产在当刻派生，不另存“阶级字段”；
- 本批的“生产资料”集合 = LAND + `transport_horse` + 现有 `OwnershipStake` 的非 LAND 耐久资产；
- 普通商品 `grain/cloth/fiber/tool/iron/wood` 作为消费/投入库存时不单独改变阶级；
  其中 `SMALL_TOOL/LARGE_TOOL` 的 `OwnershipStake` 属于耐久资产，计入；
- `transport_horse` 虽在商品库存中，但被明确登记为耐久商品资本，计入；
- 现金多不等于有产者；
- 生产资料全部卖光后自然回 `PROLETARIAN`；
- 有产者也可以受雇，所以家户可同时“有产”和“拿工资”；
- 无产者只能进入工资位置，不能组织需要生产资料的经营者位置。

### 4.2 生产方式与身份解耦

```text
生产方式 = 家户当期组织的生产活动
阶级     = 家户对生产资料的占有状态
```

因此：

- 主生产方式只是当期活动的排序第一项，不是身份；
- 农业不赚钱、运输不赚钱，只改变活动组合；
- 不会因为“某生产方式不赚钱”而把有产者改写成无产者；
- `ClassSlideSettlement` 这类“按债务/资产改 currentPosition 完成阶级滑落”的机制退役；
- `ModeMigrationPolicy` / `ModeMigrationSettlement` 不再承担阶级跃迁；
  若保留，只能作为普通生产活动/劳动配置，不得改 ownershipClass，也不得被读成阶级流。

### 4.3 位置访问

```text
OWNER：
  - 可进入经营者/自营位置
  - 可进入工资位置（用户本轮明确允许）

PROLETARIAN：
  - 只能进入工资位置
  - 不能进入需要正生产资料的经营者/自营位置
```

运输生产方式：

```text
caravan_transport（概念名；实现可复用现有 MERCHANT 稳定 id）
  caravan_operator   OWNER，PRODUCT_SHARE / 组织者，sharePerMille = 1000
                     —— 取得全部运输能力产出
  hired_driver       PROLETARIAN 或 OWNER，MONEY_WAGE / 劳动者，sharePerMille = 0
                     —— 只取得工资/工资债，不取得运输能力产出
```

这与农业生产方式同口径：雇工提供实际劳动，最终产出归有产者/经营者；雇工的收入是工资，
未付部分为普通工资债。运输经营亏损由 operator 承担，亏损本身不改变 ownershipClass。

> 现有代码的 `ProductionRole.classNature` 和 `ProductionPlanBridge` 的家户级 LANDED/LANDLESS 门必须被本模型替换；不能再用“一个位置阶级 = 整个家户阶级”的推导。

---

## 5. 运输生产方式与资本

### 5.1 运输马匹

- 运输马匹是**可交易、耐久的商品资本**；
- 当前不计算马匹生产、繁殖、死亡、折旧，也**不按趟消耗**；
- 马匹在 operator 户的 `HouseholdInventory.balances[transport_horse]` 中是唯一数量权威；
- 不另建一份 `OwnershipStake` 马匹账，避免双权威；
- 生产配方需要新增“耐久商品资本”通道，直接读取同一份家户商品库存；
- 未来的简单损耗是**生产方式配置项**（例如每次生产或每市场轮扣减/磨损），本批不实现，
  但契约必须允许以后在不新建第二本马匹账的前提下增加损耗通道。

### 5.2 运输配方（语义形状）

```text
caravan_transport
  cycleDays = 1
  durableCommodityCapacityPerUnit = { transport_horse: H } // 只当容量锚，不消耗
  laborPerUnit = L
  nonInventoryOutputPerUnit = { transport_capacity_service: C } // 本 tick 的服务能力
```

- `transport_capacity_service` 是 production 契约里的内部稳定服务输出键，不是普通 `CommodityId` 商品；
- `ProductionReceipt` 必须区分普通库存产出与非库存服务产出；economy 将后者折成 workMilli 入批次。

实际实现必须遵守：

- 规模 = min(operator 户可用马匹库存 / H, 实际劳动 / L)；
- 产出 = 规模 × C；
- 马匹不被扣减；
- **马匹只从 caravan_operator 所属 OWNER 户读取**；hired_driver 自带马匹不进入经营者规模；
- 没有马匹 ⇒ 本 tick 运输运力为 0；
- 没有实际劳动 ⇒ 本 tick 运输运力为 0；
- 产出全部归 operator 户；hired_driver 只领工资，不分运输能力；
- 运输服务走 production 的**非库存产出**通道，由 economy 在 S5 转入 batches；
  `ProductionRunSettlement.harvest` 不得将其 credit 到 `HouseholdInventory`，也不得按角色份额分给雇工；
- 非库存运输能力绕过普通商品 `RoleDistribution`，直接记给 operator 户；
  运输配方只有一个 `caravan_operator` 产品份额角色（share=1000），不建第二个产品份额角色。
- 一个家户在同一 `homeHex`、同一 tick 至多有一个活跃的 `caravan_transport` 生产组织；
  但该家户仍可同时经营农业/手工业等其他生产活动。运输马匹是家户级资本，不允许被多个运输组织重复计算；
  若实现允许同一户多运输收据，S5 必须把它们汇总到同一个 `(household, homeHex, marketRoundId)` 批次，
  且所有收据共享同一份马匹库存上限。

### 5.3 工资

- `hired_driver` 走既有工资锚：市场区 × 行业 × 角色；
- `MONEY_WAGE` 角色不持有产品份额（既有契约已经是 `sharePerMille = 0`）；
  因此所有运输能力只进 operator/产品份额角色，不进雇工；
- 每 tick 按实际劳动结算；
- 0 工资合法；
- 付不出成普通 0‰ 工资债；
- 运输服务没卖掉不等于工资不用付，经营者承担亏损；
- 有产者本人进入工资位置时，按普通工资位置结算，不改变阶级。

---

## 6. 临时运力批次

### 6.1 形状

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

- `marketRoundId` 的工程定义 = **本批次将要结算的市场日**（绝对 tick；按 `day % 5 == 0` 的下一个市场日）；
  市场日当天的 S5 产出也进入当天的 marketRoundId；
- `marketZone` = 生产时 `homeHex` 所属市场区；找不到市场区/市场表 ⇒ 不生成批次，S3 也不得选择运输生产；
- S7 只读取 `marketRoundId` 与当前市场区都匹配的批次；不匹配 ⇒ fail-closed，不跨区借用；
- 一个家户在一个 hex、一个市场轮只有一个批次；
- 家户迁移 hex 时，旧 hex 批次仍属于旧 homeHex 的批次；
- 批次可以跨 tick 累积，但不得跨 marketRoundId；
- 批次不是普通商品库存，不可自由转卖、不可用于自然需求、不可作为债务抵物。

### 6.2 写入和清零

```text
每 tick S5：
  运输生产收据 -> 找到/新建当前 marketRoundId 批次 -> capacityWork 累加

市场日 S7：
  读取当前 marketRoundId 批次作为本区运力供给

市场轮末：
  删除当前 marketRoundId 的全部批次
  未售出容量记 TRANSPORT_CAPACITY_EXPIRED 读数，不进入商品损耗/库存
```

### 6.3 旧 `haul` 库存路径

以下旧路径全部退出运输生产链：

- `haul` 作为普通商品库存；
- `MerchantCapacityPool` 从 labor + tool 计算运力；
- `CARRIER_FEE` 作为运输私有收费腿；
- 服务成市格判据；
- 未卖 haul 到产业周期关账才作废。

新设计只有一条运输收费路径：

```text
买方 --运力锚价 × 实际承运量--> carrier owner household
```

不得同时铸 `CARRIER_FEE` 和服务成交腿。

---

## 7. 运输需求与市场分配

### 7.1 需求

```text
TransportDemandKey = (marketZone, shippingHex, arrivalHex, commodity)
TransportDemand.workMilli
  = CapacityDemand.workMilliOf(orderQuantityMilli, workPerGoodPerMille)
```

规则：

- 只对跨 hex 商品订单生成；
- 同 hex 交易需求为 0；
- `shippingHex = order.fromHex`（商品发货格）；需求在选承运商之前就已确定；
- 承运资格 = `carrier.homeHex == shippingHex`；
- 商队只服务本 hex 发出的运输需求；**禁止跨 hex 调拨、跨 hex 汇总后异地使用运力**；
- LAND 无运费；
- 运输能力本身不跨 hex 调拨；旧的“运力运输自己”递归在本设计下不适用、不展开。
  运输马匹作为普通商品跨 hex 买入时，按普通商品发货需求处理：由马匹发货 hex 的本地运力承运。

### 7.2 单一市场区锚价

```text
TransportPriceAnchor {
  marketZone
  currency
  pricePerWorkMilli
  shortageStreak
  satisfiedStreak
  lastAdjustedMarketRound
}
```

- 初始 `pricePerWorkMilli = 0`；0 是合法价格，不是缺价；
- 每个市场区只有一个锚价；同区内所有 hex、所有承运户看到同一个价格；
- 市场轮内价格不变；
- 市场轮结束后根据本轮结果更新，下一市场轮生效；
- `D > S`：缺运力，提高价格；
- `D <= S` 且持续满足：延迟后降低价格，最低回 0；
- 其中 **D/S 按市场区汇总**：`D_zone = Σ TransportDemand.workMilli`，
  `S_zone = Σ 本市场轮所有本地 TransportCapacityBatch.capacityWorkMilli`；
  不把本地未成交量与本地未售容量先互相抵消；
- **目标异质性**：由于承运只能本地分配，可能出现“区内总量 D<=S、锚价不升，
  但某 hex 未服务、另一 hex 有未售运力”的状态。该状态保留，不能为消除它而做跨 hex 调拨；
- 同价分配在同一 `shippingHex` 内按“本批次可售 capacityWorkMilli / 本 hex 本轮可服务总容量”的份额
  降序 → HouseholdId 升序稳定破平；同一批次容量可在本 hex 的多个目的地 lane 之间共享，容量扣完即止；
  lane 按 workMilli 降序 → arrivalHex → commodity 稳定序处理；
- 有实际承运且买方现金不足的运费部分成普通货币债，债权人为实际承运户；无本地运力则整笔不成交、不成债。

### 7.3 商品撮合约束

运输能力是商品撮合的前置硬约束：

```text
LaneCapacityCommitment 先于 goods matching 产生
商品成交不得超出该 lane 已承诺的 capacityWork
运力不足的余量：
  - 不成交
  - 不记货物债
  - 不记运费债
  - 具名 LOGISTICS_CAPACITY
```

### 7.4 各 hex 的本地观察

为了让“统一价格下不同 hex 有不同反应”可计算，S7 结束写入每市场区、每 shippingHex 的运输轮观察：

```text
TransportHexObservation {
  marketZone
  shippingHex
  marketRoundId
  demandWorkMilli
  offeredWorkMilli
  committedWorkMilli
  unservedWorkMilli
  unsoldWorkMilli
  anchorPricePerWorkMilli
}
```

- 它冻结为既有 `MarketRoundStats` 的 `lastTransportByHex` 扩展读数面；不新增第三个 EconomyData 组件；
- 下一次 S3 的商队家户只读自己 `homeHex` 的上一轮观察：
  `expectedSaleWorkMilli = min(plannedCapacity, demandWorkMilli)`，用于本地收益预期；
- 观察不是需求预留：同 hex 多个承运户会看到同一份需求，实际按同价份额分配，可能低于各自预期；
- 没有上一轮观察 ⇒ expectedSale = 0；不伪造跨 hex 需求；
- 区级锚价仍由 `D_zone/S_zone` 更新；本地观察只影响本 hex 家户的生产决策与读口，
  不改变锚价、不做跨 hex 补偿。

---

## 8. 阶级跃迁

### 8.1 向上：取得生产资料

```text
PROLETARIAN 买入 LAND 或运输马匹
  ⇒ 资产转入对应 OWNER 家户
  ⇒ 按吸收能力迁移人口到 OWNER 家户
  ⇒ 被迁移的人在 Social 中成为该 OWNER 家户成员
```

目标选择：

```text
LAND：购买土地所属 hex 的 OWNER 家户
马匹：买方“当前 hex”= S7-M2 债务清偿完成后、市场交易开始前的 homeHex 的 OWNER 家户
匹配：同 hex + 同 culture 优先
不存在目标：建立确定性的 OWNER 壳/目标户后并入
不再要求同 productionMode
```

- “当前 hex”不取买方成交时所在的市场格、不取卖方 hex、也不取土地目标 hex；
- 若 LAND 目标户与马匹目标户是同一个 OWNER 户，**允许重合**：先执行 LAND 转移，再执行马匹转移；
  每一步只携带该步触发的生产资料，最终同一 OWNER 户可以同时拥有土地和马匹，这正是“一个 owner 多生产组织”；
- 一次资产获得若按吸收能力得到 0 个迁移人口，生产资料仍完整转入目标 OWNER 户；
  此时若目标壳原本 0 人，允许形成 0 人口 OWNER 壳；不得凭空转移人口，也不得因此把买方整体改阶级。

### 8.2 混合购买：按吸收能力拆分

```text
土地侧吸收人数：
  landCapacity = min(
    floor(acquiredLandMilliMu / minLandPerPersonMilliMu),
    migrationSpeedCap,
    sourcePopulation - MIN_SOURCE_POPULATION)

马匹侧吸收人数：
  horseCapacity = min(
    floor(acquiredHorseUnits / horsePerDriver),
    migrationSpeedCap,
    sourcePopulation - MIN_SOURCE_POPULATION)

totalMigrants = min(
  sourcePopulation - MIN_SOURCE_POPULATION,
  migrationSpeedCap,
  landCapacity + horseCapacity)
```

然后：

1. 以 `landCapacity : horseCapacity` 为权重，用最大余数法拆分 `totalMigrants`；
2. 先执行 LAND 跃迁，再执行马匹跃迁；
3. LAND 只进入土地目标户；马匹只进入当前 hex 商队目标户；两个目标户可以是同一个 OWNER 户；
4. 钱/货/债按两次迁出人口比例拆分；`totalMigrants == 0` 时不搬任何钱/货/债；
5. 任一边迁移人口为 0，对应生产资料仍完整转入目标户；该步不转移“另一边同时买入的生产资料”；
6. 不允许丢人口、丢资产、丢债、半提交。

### 8.3 向下：HouseholdFlowBook 只迁人口

本方向为用户本轮确认的**方向 B**：

```text
触发：
  OWNER 家户当前未偿债务本金相对本 flow table 的上次观察增加

目标：
  同 hex 的 PROLETARIAN 家户
  同 culture 优先
  不再要求同 productionMode
  不存在则建立 0 人口 PROLETARIAN 壳
  该壳不要求 currentPosition/生产 mode；只建稳定身份、空账户和 Social 待创建标记
  迁入人口在 S3 重新获得当期生产位置，不沿用 source 的 mode/position

迁移量：
  沿用现有 HouseholdFlowBook 的债务增量触发、1% 量级、hysteresis/cooldown；
  每个市场轮每户最多一次，source 保留至少 MIN_SOURCE_POPULATION。

实际搬什么：
  只搬人口（以及为保持劳动一致性而缩减 source 的未完成劳动承诺）；
  ❌ 不搬货币
  ❌ 不搬商品/库存
  ❌ 不搬非 LAND 资产
  ❌ 不搬 LAND
  ❌ 不搬债务
  ❌ 不搬经验
  target 不承接 source 的库存/债务
```

语义：

- source 仍保留原有生产资料，所以 source 家户仍是 `OWNER`；
- target 仍是 `PROLETARIAN`；
- 迁移的是**人**：这些人在 target 家户中成为无产者；
- 不需要 source 失去全部生产资料才发生向下人口流动；
- 不能把本机制读成“源家户整体改阶级”；
- `HouseholdFlowTable` 的持久状态保留（source、targets、lastObservedDebtPrincipalMilli、lastFlowDay）；
  `lastObservedDebtPrincipalMilli` 只作**触发基线/观察值**，不等于搬债；
- 现有 `HouseholdTransferSettlement` 的“人口 + 钱货债/经验”不能再作为 flow 的执行器；需要 population-only 变体或专用 primitive。

### 8.4 其他流动

- 生产方式不赚钱不触发阶级流动；只改下一 tick 的生产活动/劳动配置；
- D-031 原列出的“库存价值变大”“生产方式相对收益较差”两项人口流，在当前两阶级/本运输批次中**不启用**；
  只保留债务增量驱动的向下人口流，作为后续细化阶层时的独立议题；
- `ClassSlideSettlement` 不再作为向下流动写口；
- `ModeMigrationPolicy`/`ModeMigrationSettlement` 不再承担阶级流动；若保留只作普通活动配置；
- 由债务导致的资产实际清零（例如最后一块地/最后一匹马被清算）会使家户派生的 `ownershipClass` 变为 `PROLETARIAN`，这是资产结果，不是 mode 迁移。

### 8.5 债务清偿取得生产资料：就地向上

预算口径：

```text
S6 工资第一次支付：只货币；未付 = 普通 0‰ 货币工资债
下一市场轮 S7-M2：工资债与普通债同池
清偿顺序：货币 → 库存（含 transport_horse）→ LAND
```

- 债务清偿的资产转移直接给债权人，不经过土地市场，也不要求购买者吸收人口；
- PROLETARIAN 债权人取得 LAND 或运输马匹后，按资产派生规则**就地变为 OWNER**；
- 该资产/人口不迁往“其他 OWNER 家户”；债权人的原家户人口与身份保留；
- 下一 S2 起按标准 `(homeHex, cultureKey, ownershipClass)` 与同 hex 同文化的 OWNER 户合并；
  无可合并目标时继续作为独立 OWNER 户存在；接收的异 hex LAND 仍归该 creditor 户，不因此强制迁人；
- 这是“债偿取得”独立向上通道，和市场买入的“目标户吸收人口”不是同一机制；
- 债偿实物转移沿用既有 ledger 直转语义，不是市场成交，不生成新的运输需求/运费；跨 hex 实物债偿规则本批不扩；
- 不再使用“PROLETARIAN 债权人跳过 LAND 腿”的旧例外；
- 0/缺价资产不冲本金；有转移必须有对应债务本金减少，同一 revision 原子。

### 8.6 债务清偿的中间态

允许且必须处理“清偿前 PROLETARIAN、清偿后持有生产资料”的中间态，但**不得持久化矛盾状态**：

```text
S7-M2 开始时：
  只读 ownershipClassAtMarketOpen（供市场买入的 S7-B 判断使用）
S7-M2 为每个 creditor 逐笔执行债务转移：
  货币 → 库存（含 transport_horse）→ LAND
  所有写入先落在同一 working copy
每笔/整阶段需要 class 读数时：
  从当刻工作资产重新派生 ownershipClass，不使用持久 class 字段
S7-M2 结束、同一 revision build 前：
  对受影响户重新派生 ownershipClass
   debtor 若生产资料归零 ⇒ PROLETARIAN
   creditor 若由零变正 ⇒ OWNER
  class 变化本身不搬人口、不触发目标户吸收
下一 S2：
  用最终 ownershipClass 走标准合并
```

- “PROLETARIAN 名下有生产资料”只能存在于 M2 的瞬态工作副本，不能进入 Codec/ChangeSet 的最终持久状态；
- S7-M2 的 ownership lookup 必须读**同一份活工作副本**（Map transient land ledger、household goods、
  assetShares 工作副本），不能读 S7-M2 前旧快照；
- S7-M2 全部转移完成后、S7-A 市场交易开始前，拍一次 `ownershipClassAtMarketTradeStart` 快照；
  它只服务“本轮市场买入是否算 PROLETARIAN 买方”的 S7-B 判定；
- 同一 revision 内要么按最终资产派生 class，要么中止整次 advance，不允许半提交；
- 后续合并/生产按最终 ownershipClass 走，不需要专门的“先改阶级再合并”命令。

示例（不制造持久矛盾态）：

```text
S7-M2 前：W 户 = PROLETARIAN
S7-M2 中：W 作为债权人收到 LAND / transport_horse（working copy）
S7-M2 末：W 户资产 > 0 ⇒ 派生 OWNER
S7-A 前：在 S7-M2 完成后拍 ownershipClassAtMarketTradeStart
S7-B：市场买入路径使用该快照；W 已是 OWNER，不再按 PROLETARIAN 买方做目标户吸收
下一 S2：W 按 (homeHex, cultureKey, OWNER) 与同 hex 同文化 OWNER 户标准合并
          无可合并目标 ⇒ W 作为独立 OWNER 户存在
```

---

## 9. 合并规则

```text
mergeKey = (hex, cultureKey, ownershipClass)
```

- `productionMode` 从 key 中删除；
- 同一 hex、同文化、同 `OWNER` 或同 `PROLETARIAN` 的家户可合并；
- 一个 `OWNER` 可以同时拥有多个生产组织、多个生产位置；
- 合并 survivor 继续沿用既有确定性规则：人口降序 → 本 hex LAND 降序 → HouseholdId 升序；
- 合并后：
  - LAND/马匹/非 LAND 资产合并；
  - 人口/钱/货/债/经验合并；
  - production unit/roster/enterprise/operator/occupancy/reservation/工资引用重绑；
  - **当前 marketRoundId 的 TransportCapacityBatch 按 survivor/homeHex 合并重键，capacityWorkMilli 相加**；
  - 同一市场轮/同一 homeHex 若因重键产生多条批次 ⇒ 合并为一条；不同 homeHex 的批次保留各自键；
  - 不允许丢资产/债务/人口/已生产运力。

---

## 10. 数据所有权

| 事实 | 唯一权威 |
|---|---|
| LAND 数量/owner | Map `HexLand` component attributes |
| 运输马匹数量 | Actor `HouseholdInventory.balances[transport_horse]` |
| 非 LAND 耐久资产 | Economy `assetShares` / `OwnershipStake` |
| 家户商品/货币 | Actor `HouseholdInventory` |
| 人口 | Social |
| 运输生产方式/配方/物理产出 | production；运输能力产出的经济归属 = operator OWNER 家户 |
| 运输能力批次 | economy（短寿命 `marketRoundId` 批次，不进入商品库存） |
| 家户 ownershipClass | 由 app 装配的跨切片只读事实派生：Map LAND + actor 马匹库存 + economy 非 LAND 耐久资产；不持久另存 |
| 运输价格锚 | economy（逐市场区） |
| 本地运输轮观察 | economy（扩展 `MarketRoundStats.lastTransportByHex`；按 shippingHex 读，只影响本地生产决策/读口） |
| 向下人口流动 | economy `HouseholdFlowBook` 计划 + Social 人口 outbox |

禁止：

- 马匹同时作为商品库存和 `OwnershipStake` 两份权威；
- 运输能力同时作为普通库存和批次两份权威；
- production 持有运输能力批次或价格锚；
- Map 反向 import economy/social；
- 第二套市场/第二套 matcher。

---

## 11. 不变量与失败语义

| # | 不变量 |
|---|---|
| I1 | 运输生产每 tick 可运行；`cycleDays=1`；无马匹/无劳动 ⇒ 运力为 0 |
| I2 | 运输能力只进入当前 `marketRoundId` 批次，不进入普通商品库存 |
| I3 | 市场轮末清零当前批次；上一市场轮批次不可用 |
| I4 | 运输需求只从跨 hex 商品订单派生；同 hex 为 0；LAND 无运费 |
| I5 | 本地运输能力不足 ⇒ 对应货量不成交、不成债、不记账；不得调用别 hex 运力补足 |
| I6 | 运输价格每市场区一个锚，初始 0；市场轮内不变 |
| I7 | 0 价合法，不得用 `max(1,...)` 把 0 价强制改成 1 |
| I8 | 买方现金不足的运费部分成普通债；无运力则不成债 |
| I9 | `ownershipClass` 只由生产资料持有派生；生产方式切换不改阶级 |
| I10 | 有产者可受雇；无产者只能进入工资位置 |
| I11 | 合并 key 不含 productionMode |
| I12 | 向上跃迁资产按触发类型分别入门；混合买入按吸收能力拆人口 |
| I13 | 向下 `HouseholdFlowBook` 只迁人口，不搬库存/债/资产/经验 |
| I14 | `ClassSlideSettlement` 不再作为阶级向下写口 |
| I15 | 所有排序确定性；不使用 Map 迭代序/墙钟/随机 UUID |
| I16 | 全部写入仍走 `Command → ChangeSet → Revision`；同一 revision 原子提交 |
| I17 | 每笔运输需求 `shippingHex = order.fromHex`；只有 `carrier.homeHex == shippingHex` 的本地批次可参与 |
| I18 | 运输能力不得跨 hex 调拨或汇总后异地使用；本地未售容量与本地未服务需求可以同时存在 |
| I19 | 市场区运力锚只有一个；D/S 用市场区汇总量；允许区内总量满足而局部仍短缺/闲置 |
| I20 | 运输能力全部归 operator OWNER 户；hired_driver 只领工资，不取得能力份额 |
| I21 | 马匹本期不消耗；未来损耗只能走生产方式配置，且不得建立第二本马匹账 |
| I22 | 运输服务走 non-inventory 产出通道；不得 credit 到 HouseholdInventory，也不得进入普通商品订单簿 |
| I23 | 本地需求观察只按 shippingHex 读取；缺观察 = 0，不得用区内其他 hex 的需求冒充 |
| I24 | S6 工资第一次只付货币；LAND/运输马匹不得在该步直接抵工资 |
| I25 | 后续普通工资债清偿允许货币→库存（含运输马匹）→LAND；债权人取得生产资料后就地派生为 OWNER |
| I26 | “PROLETARIAN 名下有生产资料”只可存在于 S7-M2 working copy；最终状态按当刻资产重新派生 ownershipClass，不持久矛盾 class |
| I27 | 债偿取得的异 hex LAND 归债权人户所有，不因债偿自动迁人；下一 S2 按 creditor 当前 homeHex/culture/ownershipClass 标准合并 |
| I28 | 债偿实物转移沿用 ledger 直转，不是市场成交，不生成运输需求或运费 |

失败：

- 马匹数量为负 / 未知商品 key ⇒ 具名拒绝；
- 当前批次 marketRoundId 不匹配 ⇒ fail-closed，不跨轮借用；
- 运输产出为负 / 溢出 ⇒ fail-closed；
- 运力超卖或 lane 承诺超过批次 ⇒ fail-closed；
- 使用了非 shippingHex 的承运批次 ⇒ 契约故障；
- 运输能力被写入普通库存/订单簿 ⇒ 契约故障；
- 向下 flow 搬了库存/债/资产 ⇒ 契约故障；
- S6 工资直接转移 LAND/运输马匹 ⇒ 契约故障；
- 债务清偿转了资产但未同步减少本金 ⇒ 契约故障；
- 将“PROLETARIAN + 正生产资料”的中间态落进最终 ChangeSet/Codec，而不是按最终资产派生 class ⇒ 契约故障；
- 混合跃迁人口拆分不守恒 ⇒ 整次 advance 回滚；
- 0 价被强改成正价 ⇒ 契约故障。

---

## 12. 口径溯源与取代关系

| 结论 | 出处 | 日期 | 状态 |
|---|---|---|---|
| 运输需求由其他商品购买派生 | `2026-10-10-haul-service-commodity-design.md` §1/§3.3 | 2026-10-10 | 继续有效 |
| 运力不可储存转卖 | 同上 §1 | 2026-10-10 | 继续有效，但由本文细化为“市场轮批次，非普通库存” |
| 购买运力 = 满足全部需求 + 没钱全算债务 | `2026-10-09-commodity-freight-and-merchant-ladder-design.md` §15.1 | 2026-10-10 | 继续有效；本文按“无运力不成债、有运力未付成债”落地 |
| 向市场上最低价的运力提供商买 | 同上 §15.2 K-C | 2026-10-10 | **被用户本轮选择的市场区单一运力锚价取代** |
| 跑商是生产方式 | 同上 §1 | 2026-10-10 | 继续有效 |
| 纯商号自运免运费、替别人运取消 | `2026-10-10-haul-service-commodity-design.md` §1（H-A/H-B） | 2026-10-10 | **部分取代**：自运不产生自我付款；但通用运输服务重新成为市场交易对象 |
| 运力 = 劳动 + 工具派生、haul 普通库存 | 同上 §3.2 / 当前 `MerchantCapacity` | 2026-10-10 | **被本文运输生产方式（马匹 + 实际劳动、市场轮批次）取代** |
| 先做简单运力函数、不新增运输生产方式 | `2026-10-19-d032-standard-agriculture-architecture-design-v2.md` §8.10 C30 | 2026-10-19 | **被用户 2026-10-21 原话推翻** |
| 运输走 CARRIER_FEE、不做独立服务订单簿 | `2026-10-21-d032-hex-land-ownership-and-transport-commerce-integration.md` §6–§9 | 2026-10-21 | **被本文取代** |
| mergeKey 含 productionMode / classNature | `2026-10-20-d032-two-position-rural-class-mobility-v4.md` §6.2 | 2026-10-20 | **被本文取代** |
| 向下流动搬人口 + 钱货债/经验 | 同上 §3/§7，`HouseholdFlowBook` | 2026-10-20 | **被用户本轮“只迁人口、净身出户”取代** |
| 生产位置 LANDED/LANDLESS 决定阶级 | 同上 §4 | 2026-10-20 | **被用户本轮“有产/无产、家户不绑定生产方式”取代** |
| D-031 三项人口流动因素（负债↑/库存增值/生产不赚钱） | `2026-10-02-architecture-design-source.md` §D-031 | 2026-10-15 | **当前只启用负债↑向下**；生产不赚钱仅改活动组合；库存增值与生产收益驱动人口流在当前两阶级批次不启用，细化阶层时另议 |
| 运力提供全市场区共享、可跨区被雇 | `2026-10-10-unified-price-and-ordered-settlement-design.md` v3.26 | 2026-10-11 | **被用户 2026-10-21 原话“单hex的运力产出只能服务于单hex本身”取代** |
| “运力过来同样需要运力”的递归终止规则 | `2026-10-09-commodity-freight-and-merchant-ladder-design.md` §15.1/K-D | 2026-10-10 | **本设计不适用**：运力不跨 hex；只有作为普通商品的运输马匹跨 hex 时才产生普通货运需求 |
| PROLETARIAN 债权人跳过 LAND 腿 | `2026-10-20-d032-two-position-rural-class-mobility-v4.md` §11 | 2026-10-20 | **被本轮用户选择 A 推翻**：工资债后续清偿允许货币→库存→LAND，债权人取得生产资料后就地变为 OWNER |
| 工资第一次只付货币；后续普通债允许库存 | `2026-10-18-d032-economy-tick-redesign.md` §37/§38/§43 | 2026-10-18 | 继续有效；运输马匹作为商品库存纳入清偿库存 |
| 用户选择 A：通用还债顺序；允许中间态后续合并 | 本轮用户原话 §1.8 | 2026-10-21 | 已确认；S7-M2 working copy 可暂态持有，最终按资产派生并下一 S2 合并 |
| 市场区单一运力锚价 | 本轮用户选择器回答 A | 2026-10-21 | 已确认 |
| 混合买入按吸收能力拆分 | 本轮用户选择器回答 A | 2026-10-21 | 已确认 |
| 所有生产资料计阶级、有产者可受雇 | 本轮用户选择器回答 A | 2026-10-21 | 已确认 |
| 向下流动只迁人口、HouseholdFlowBook 净身出户 | 本轮用户原话 | 2026-10-21 | 已确认 |
| 单 hex 运力只服务本 hex；统一价格下允许各 hex 反应不同 | 本轮用户补充原话 §1.7 | 2026-10-21 | 已确认 |
| 运输马匹本批不折旧，未来作为生产方式损耗配置 | 本轮用户补充原话 §1.7 | 2026-10-21 | 已确认 |
| 运输雇工只领工资，产出归有产者/经营者 | 本轮用户补充原话 §1.7 | 2026-10-21 | 已确认 |
| “有货但买不起才成债；无供给不凭空记债” | `2026-10-18-d032-economy-tick-redesign.md` §9/§10.4 | 2026-10-18 | 继续有效；运费腿沿用同一市场赊购原则，债权人为实际承运户 |

---

## 13. 仍需用户裁定？

**无。** 用户已明确授权控制方按此前思路收口；以下为已冻结的工程默认或控制方裁定，可随实现失败证据修订，
但不再作为待用户确认的玩法缺口：

1. 当前市场间隔按代码是 5 tick；非市场 tick 为 4，市场日 S4/S5 运力也进入本市场轮批次；
2. 马匹本批不折旧、不死亡、不繁殖、不按趟消耗；未来只增加生产方式级损耗配置；
3. 向下 flow 沿用现有债务增量触发、1% 量级、hysteresis/cooldown、source 至少保留 1 人；
4. 运输锚价的升/降步长、延迟、上限按工资锚同族参数做工程默认；
5. 32hex 测试世界每个 hex 的既有有产户持有大量马匹并保留可售余量；
6. 运输生产方式实现上复用现有 `MERCHANT` 稳定 id，避免新增第二套商人身份；
7. 运输能力服务使用独立的 non-inventory 服务输出键，不进入普通商品词表/库存/订单簿；
8. `shippingHex = order.fromHex`；承运批次只按 `homeHex` 本地匹配；
9. 锚价 D/S 按市场区汇总；本地成交量与本地未售容量不回灌成区级二次净额；
10. 同一 shippingHex 内，同价批次按“本批次可售 capacityWorkMilli / 本 hex 本轮可服务总容量”的份额降序
    → HouseholdId 升序排定；批次容量在本 hex 多个 lane 间共享；
    lane 按 `workMilli` 降序 → arrivalHex → commodity 稳定序处理；
11. “当前 hex” = S7-M2 完成后、市场交易开始前的买方 homeHex；LAND 目标户与马匹目标户允许重合；
12. 一次取得生产资料若吸收人口为 0，资产仍转目标 OWNER，不强制迁人；
13. 运费欠款只在有本地承运能力且实际成交后产生，债权人为实际承运户；无运力不成债；
14. 运输能力产出归 operator OWNER 户；雇工只领工资；
15. 每 hex 的上一轮运输需求观察冻结为 `MarketRoundStats.lastTransportByHex`；S3 按 homeHex 读取，
    缺观察 = expectedSale 0；
16. 一个家户同一 homeHex/同一 tick 至多一个活跃 transport 组织；多收据必须共享马匹上限并汇总到同一批次；
17. 工资第一次只能货币；后续普通债清偿允许货币→库存（含运输马匹）→LAND；
    PROLETARIAN 债权人取得 LAND/运输马匹后就地变为 OWNER，保留旧“跳过 LAND”例外已删除。

---

## 14. 非目标

- 本批不实现马匹生产、繁殖、死亡、折旧/损耗；未来只允许通过运输生产方式配置加入简单损耗；
- 不实现城市/城区机制；
- 不实现商品跨市场区价差/买低卖高商业（另立设计）；
- 不实现跨 hex 运力调度、借调或“把别 hex 商队叫来”的玩法；
- 不恢复四阶级 slot/地主/富农/中农/贫农生产位置；
- 不恢复租佃/rent/Pledge/抵押；
- 不恢复 `MerchantFirm` 持久商号行；
- 不恢复普通 `haul` 库存或 `CARRIER_FEE` 双收费；
- 不写旧档兼容；
- 本文不写生产代码、不跑测试、不 commit/push。

---

## 15. 修订记录

- 2026-10-21 v1：按用户本轮运输系统原话、每 tick 运输批次原话、向下流动净身出户原话、四项裁定选项落盘。
  取代旧 `haul` 库存/`CARRIER_FEE`/LANDED-LANDLESS 阶级路径的运输与阶级口径；
  保留 Map LAND 权威、LAND 市场、基本工资/债务/统一市场框架。
- 2026-10-21 v2：吸收用户 §1.7 补充——
  ① 单 hex 运力只服务本 hex，禁止跨 hex 调拨；
  ② 马匹本批无耐久消耗，未来作为生产方式损耗配置；
  ③ 运输产出归 operator OWNER，雇工只领工资；
  ④ 保留市场区单一锚价，并用区级 D/S + 本地分配制造“区内繁荣与 hex 局部短缺/闲置并存”；
     新增 `MarketRoundStats.lastTransportByHex` 供下一 S3 做本地预期，缺观察按 0；
  ⑤ 明确 shippingHex=order.fromHex、目标户重合、0 人口迁移、批次合并和 non-inventory 产出等工程冻结；
  ⑥ 一 household/homeHex/tick 至多一个活跃 transport 组织；多收据共享马匹上限并汇总同一批次；
  ⑦ 用户选定 A：市场轮通用还债顺序为货币→库存/其他资产→LAND；
     工资第一次只付货币，后续普通工资债按此清偿；删除 PROLETARIAN 债权人跳过 LAND 腿的旧例外；
     债权人取得生产资料后就地变为 OWNER；
  ⑧ 允许 S7-M2 内“PROLETARIAN 暂持生产资料”的 working-copy 中间态，但最终同一 revision
     必须按资产重新派生 ownershipClass；下一 S2 按标准 key 合并，异 hex LAND 不强制迁人。
