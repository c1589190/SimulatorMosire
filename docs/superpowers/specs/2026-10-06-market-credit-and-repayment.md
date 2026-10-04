# 市场信用与还款顺序（借啥欠啥、挂单即可借、钱优先）

> 状态：**设计已记录、待实现**（2026-10-06）
> 规则依据：`AGENTS.md` §一.8（派实现子 Agent 前先保存完整架构设计文档）。
> 用户裁定来源（本会话原话）：
> - 「没钱不能借钱吗，我有在经济循环里规定没钱的时候不能借钱吗」
> - 「借是什么都可以借，那还自然是什么都可以还啊，就是还款如果要换算，需要根据还的人那边的价格来」
> - 「借债事实上就是市场上没卖完的商品，只是这里的'商品'要加上能挂到市场上借的钱，依旧是钱优先借、后续的实物按量大到小被借」
> - 「利息和本金不分开算了，反正都是增量，能还多少还多少」
> - 「库存多不多应当单纯按数量排序，不过不管怎么样钱应该放在第一位」
> 关联裁定：D-023（任意介质偿还；单合同单未偿额；不做利息/本金分账）、D-024（预期利润/需求）、D-025（饥荒/城市化派生）、D-027（单市场区、单 hex 贸易成本与市场总调控分层）。

## 0. 一句话目标

把市场从“只能现金买”扩成“可买、可借”：**卖家挂出的未售商品同时是可借头寸；货币持有人可把余额挂成可借货币**；
缺钱/缺货的买方**先借钱、再买货；钱借不到才借实物，实物按可借量从大到小借**；
借到啥就欠啥，家户债表是一串“欠谁什么”的合同；还款时**货币第一、商品按数量降序、债按未偿总额升序、按债务人价目表折算**，能还多少还多少。

## 1. 需求与非目标

### 1.1 目标

1. **市场供给双用途**：卖单剩余 = 商品可借池；不另建仓库、不复制库存。
2. **货币可出借**：货币余额超过保留额的部分可作为可借货币；出借单是每轮瞬态派生，不落盘。
3. **借款顺序**：买方先用自己的钱；不够先借货币（钱优先），再用借到的钱买货；货币借不到才借实物；实物按可借数量降序借。
4. **借啥欠啥**：借粮欠粮、借布欠布、借银欠银；每条债务合同是 `(债务人, 债权人, DebtUnit, principal, terms)`。
5. **还款自由**：任意库存可还任意债；**货币排最前**，然后商品按原始数量降序；债按未偿总额升序；
   跨单位换算用**债务人自己的价目表**（缺项 → 市场区默认价目表；再缺 → 具名跳过）。
6. **不拆利息/本金**：利息继续按周期增量并入 `principal`；还款只面对一个未偿总额。
7. **守恒**：不铸钱、不造货；货币/商品只在账户间转移，债务只是新增合同记录。
8. **信用约束**：复用并扩展 `DebtCapacityBook` headroom；货币债与商品债都按市场价折成共同价值计入额度。
9. **可读债表**：家户债表按“欠谁：什么单位多少”多条展示。

### 1.2 非目标

- 不做跨市场区 lane/承运/聚集节点（D-027 仍单区优先；跨区后续批次）。
- 不做商人买低卖高/投机；债权人只收本金+利息，不主动转卖债权。
- 不做债务二级市场/债权转让/抵押品拍卖（本批只记录、只偿还；清算走既有 OperatorSettlement/债务压力路径）。
- 不做动态价格/自适应价格；换算使用市场参考价/债务人价目表。
- 不新增 `EconomyData` 持久组件；出借单是每轮瞬态。
- 不实现“无限信用”：信用额度、价目表缺失、冻结/口粮保留都会限制可借可还。

## 2. 现状与问题

- `MarketSettlement` 只有“现金买/卖”：买方 `budget=spendableMoney` 不足就不能下单（`ordersFor` 里 `quantity<=0` 直接不买）。
- 唯一自动借贷是 `EconomySettlement.lendDeficitsInHex`：同 hex、只借粮、在市场**之后**、借到当日直接吃掉；不产生市场买盘。
- 结果：货币总量守恒但分布集中，缺钱户没有有效需求；卖盘巨大、成交 0（见 `/tmp/testagent/marketfreeze/run6.log`）。
- 债务模型已支持 `DebtUnit.Money` / `DebtUnit.Commodity` 与多合同并存；`DebtContract` 只有一个 `principal`，利息并入本金；
  信用的物理通道存在，只缺“市场内可借/可还”的规则与算法。

## 3. 新架构

### 3.1 市场供给：卖单剩余 + 可借货币

每个市场轮构造（瞬态，不落盘）：

```text
goodsLendable:
  对每个 SellSlot：可用可借量 = 该卖单在现金成交后的剩余 remaining
  卖家“挂单即可借”由市场规则 MARKET_GOODS_LENDING_ENABLED=true 表达（GM 可关；关闭时只现金卖）
  排序：可借数量降序 -> 商品 id 升序 -> 卖家 actor id 升序

moneyLendable:
  对每个参与者（家户/经营者）：
      reserve = value(自身未覆盖自然需求，按市场价) + LENDER_MONEY_BUFFER_PER_CAPITA_MILLI × 人口
      lendable = max(0, 可花货币余额 - 冻结 - reserve)
  可选：只列同市场区计价货币（silver）——本批单区单币种
  排序：费率升序 -> 可借额降序 -> 出借人 actor id 升序
```

- “可借货币”由 `MONEY_LENDING_AUTO_LIST=true` 默认从余额派生；不要求显式挂单（GM 可改为显式）。
- 出借人保留额 `LENDER_MONEY_BUFFER_PER_CAPITA_MILLI` 是具名 GM 默认（建议 12 毫银/人，与创世禀赋量级一致），防止出借人把自己的口粮钱借空。
- 商品出借没有额外保留：能出现在卖单剩余里的量，就是卖家已经愿意脱手的量。

### 3.2 借款与购买顺序

对每个买方/商品缺口，在同一个市场轮内按以下顺序处理：

```text
① 用自己的现金（现有订单路径）
② 若缺口仍在，且买方信用 headroom > 0：
   a. 先借货币（钱优先）：
      在 moneyLendable 里按排序取；借入额 = min(缺口金额, 买方 headroom 可支撑金额, 出借人可借额)
      生成 DebtUnit.Money(计价货币) 合同；银主 -> 买方 转银（LOAN_PRINCIPAL）
   b. 用借到的银（并入现金预算）继续买货；
      卖方按现有现金成交收到银 —— 对卖方仍是一笔现金销售。
   c. 若货币借不到/不够，缺口仍在，且 headroom 仍 > 0：
      借实物：在 goodsLendable 里按“可借量降序”取；
      货腿直接从卖家 -> 买方；生成 DebtUnit.Commodity(该商品) 合同；没有货币腿。
③ 三者都用尽仍不足 -> 不成交：记具名未成交原因（见 §5.3），不伪造成交、不造资产。
```

- **关键原子性**：借货币不是先给买方一笔闲置现金；货币借贷只与“买这批货”一起落账：
  `银主→买方（贷款）`、`买方→卖方（货款）` 同时提交；没有可买货物时不放贷。
- **卖方优先现金**：现金成交（含借来的银）优先吃掉卖单；卖单剩余才转为实物借贷。
- **买方顺序**：沿用现有撮合序（规范槽位序）；信用分配在同一序内确定性分配。
- **不强制出借**：出借人只按其可借余额参与；商品只从挂单剩余出借；没有头寸就没有借贷。

### 3.3 债务模型

- 沿用 `DebtContract`：`debtor, creditor, unit, terms, principal, openedDay, lastInterestDay, dueCycle, status`；
  **不新增 accruedInterest 字段**（用户确认利息/本金不分开，利息继续并入 principal）。
- `unit`：
  - 借货币：`DebtUnit.Money(currency)`，本金单位 = 毫货币；
  - 借实物：`DebtUnit.Commodity(commodity)`，本金单位 = 毫商品。
- 同一 `(债务人, 债权人, unit, terms)` 命中同一条合同，本金累加（`DebtContractBook.upsert`）；
  不同债权人/单位天然是多条合同 → 家户债表就是“欠谁什么”的列表。
- 利率/期限：沿用 `BORROW_RATE_PER_MILLE_PER_CYCLE` 与“下周期到期”；利息仍由 `chargeInterest` 并入 `principal`。
- `ClassRow.debts` 只加合同引用；资产/库存不因“生成债务”而变化，只有贷款本金转移和还款转移会动余额。

### 3.4 信用额度（headroom）

- `DebtCapacityBook` 现有口径只把粮债计入 `existingDebt`；货币/其它商品债全部 `unpriced`。
- 本批必须扩展：
  ```text
  debtValueMilliGrain(debt) =
      unit=Commodity(c): principal × price(c) / price(grain)
      unit=Money(cur):   principal × 1000 / price(grain)      // 1 毫银 = 1 毫粮 ÷ 粮价（价目表口径）
      缺 grain 价或缺该单位价 -> 不可定价
  headroom = pledgeableAssets + income − existingDebt(All priced units) − nextRoundNecessaryInput
  ```
- 可定价债务全部计入 `existingDebt`；仍有任一不可定价债务 ⇒ 该户新信用 headroom 视为 0（fail-closed，不超借）。
- 货币借款金额也按同一折算占 headroom；实物借款按该商品价格折算。
- 信用额度只用于“能不能借”，不改变利率/成交价。

### 3.5 还款顺序与折算（用户给定，唯一口径）

对每个还款家户（关账日、所得到账后、计息之前）：

```text
价目表：债务人自己的家户价目表优先；
        缺项 -> 该家户所在市场区默认价目表；
        再缺 -> 这个 medium 对这条债具名跳过（不静默当 0，不拦下一条）

债表：按“未偿总额折算成共同价值”升序（价目表折算；不可定价的排最后，按 unit id 升序）
       tie: 债权人 id 升序 -> 债的单位 id 升序

还款介质顺序：
  A. 先所有货币：按余额原始数量降序；tie: 币种 id 升序
  B. 货币用完才用商品：按余额原始数量降序；tie: 商品 id 升序
  （即：钱永远第一位；实物内部按数量，不按价值）

逐条债（debtOrder）：
  for medium in 介质顺序（先整段货币，再整段商品）：
      若 medium.unit == debt.unit：pay = min(medium 余额, debt.principal)
      否则按债务人价目表折算：
          debtValue = debt.principal × price(debt.unit)
          mediumUnitValue = price(medium.unit)
          pay = min(medium 余额, floor(debtValue / mediumUnitValue))
          抵扣本金 = floor(pay × mediumUnitValue / price(debt.unit))，夹在 debt.principal 内
      转移 medium：债务人 -> 债权人（唯一 applier）
      扣 medium 余额、减 debt.principal；还清则结清；不足顺延下周期
```

- **货币优先是全局口径**：先用完所有可用货币，再动商品；不是每条债内部先钱后货。
- **实物按原始数量排序**：不同商品数量不具经济可比性，这是用户明确要的口径；同类内按 id 稳定 tie-break。
- **债按未偿总额**（principal，含已资本化利息）升序，不再区分利息/本金。
- **缺价处理**：跳过该 medium（对该条债），换下一 medium；所有 medium 都缺价/不足 ⇒ 剩余债顺延；不得静默付 0。
- 保留现有约束：冻结余额不越、一日口粮保留、全币种余额可用、余额不得为负；D-023 的“任意介质”继续保持。

## 4. 数据流与调用次序

```text
每日：
  ① 生产/劳动/投入/收获/消费（现有）
  ② 到市场触发日：
       a. 构造 goodsLendable（卖单剩余）与 moneyLendable（余额派生）
       b. 现有现金买卖
       c. 信用撮合：借钱买货（钱优先）→ 借实物（量大到小）
       d. 写 MarketReport：cash fills + credit fills + unfilled
  ③ 关系实付/消费/出生死亡（现有）

周期关账日：
  ④ 还款（本节 §3.5 顺序；转移走唯一 applier）—— 仍在计息之前
  ⑤ chargeInterest：利息并入 principal（不变）
  ⑥ 其余关账/迁移/读账（D-022/D-024/D-025 不变）
```

## 5. 接口/契约

### 5.1 市场轮瞬态对象

```text
MoneyLendOrder(lender: ActorRef, currency: CurrencyId, amountMilli: long, ratePerMille: long)
GoodsLendOrder(seller: ActorRef, commodity: CommodityId, quantityMilli: long, hex: HexCoord)

MarketRound 增加：
  List<MoneyLendOrder> moneyLendable
  List<GoodsLendOrder> goodsLendable     // 由 SellSlot.remaining 派生，不新增持久状态
```

### 5.2 信用成交读数

`MarketReport` 增加一个只读列表（record 形状变更；实现代理同步更新构造点）：

```text
record CreditFill(
    HexCoord hex,
    CommodityId commodity,
    ActorRef borrower,
    ActorRef lenderOrSeller,
    long quantityMilli,
    DebtUnit unit,            // Money / Commodity
    DebtContractId debtId,
    long ratePerMille,
    long dueCycle)
```

`MarketReport` 增加 `List<CreditFill> creditFills` 组件；`immediateFills`/`crossRegionFills` 口径不变；`creditFills` 单独计数。
家户债表读口（后续/本批最小实现）：按 `(creditor, unit)` 分组，输出 `principal`；由 `ApiViews`/读口选择。

### 5.3 未成交原因

`MarketUnfilledReason` 增加：

- `NO_CREDIT_LIMIT("no_credit_limit")`：有缺口但 headroom 为 0；
- `NO_LENDABLE_MONEY("no_lendable_money")`：货币借不到；
- `NO_LENDABLE_GOODS("no_lendable_goods")`：商品卖单剩余为 0。

保留现有 `NO_BUDGET`（确实没钱且没信用）、`NO_SELLER`、`ALL_RESERVED` 等档。

### 5.4 失败语义

- 没有可借头寸 / 没 headroom / 缺价 ⇒ 不借贷、不成交，具名原因；不抛、不造假。
- 数据坏（负余额、余额不足转移、冻结越界、合同重复冲突）⇒ 沿用现有 fail-closed 抛。
- 借贷后物/钱守恒：`Σ余额` 逐商品/逐币种不变；新增的只是 `DebtContract` 合同行。
- 还款转移后：`Σ余额` 不变；`principal` 减少/结清；不得出现负库存/负货币。

## 6. 版本、激活与旧数据

- 行为变化（市场信用、还款顺序、信用额度折算）需要新运行时版本：建议 `seven-hex-v3`（在现有 `EconomyMeta` 版本常量上追加），seeder 写 v3；旧档按既定“直接作废、GM 重置”。
- 不新增 `EconomyData` 组件；`DebtContract` 形状不变；`MarketReport` 是瞬态读数，不进持久状态。
- 激活/加载拒绝旧版本仍按现有缺口处理（本批至少 seeder 自检与版本位更新）。

## 7. 验收判据

### 7.1 市场信用

1. 无现金缺粮户 + 银主 + 卖单：一笔信用成交产生 `DebtContract{Money(silver)}`，银主 → 卖方收到钱，买方拿到粮；货币与商品总量守恒。
2. 无货币出借 + 卖单剩余：实物借贷成交产生 `DebtContract{Commodity(grain)}`，卖方 → 买方货腿，无货币腿；商品守恒。
3. 借款顺序：有货币可借时优先货币；货币用尽才借实物；实物按可借数量降序选择。
4. 信用额度：headroom 不足时借不到；货币债/商品债都占额度；不可定价债务 ⇒ headroom 0（fail-closed）。
5. 没有可借头寸 ⇒ 未成交原因 `NO_LENDABLE_MONEY`/`NO_LENDABLE_GOODS`/`NO_CREDIT_LIMIT`。
6. 卖方未售剩余不会被现金与信用重复占用；同一卖单剩余两种用途之和 ≤ 原 remaining。

### 7.2 还款顺序

1. 货币优先：有钱先还钱，货币用完才动商品。
2. 商品按原始数量降序（同数量按 id）；债按未偿总额升序。
3. 跨单位折算使用债务人价目表；缺项回退市场区默认；再缺则具名跳过。
4. 部分还款可留余债；还清合同结清；`Σ余额` 守恒；不得负余额/越冻结/吃口粮保留。
5. 债表读口输出“欠谁：什么单位多少”的多条记录。

### 7.3 真实 12hex 3650

1. tick1200 之后不再“全年市场 0 成交”：至少多个关账窗口出现 `creditFills > 0`；`marketFills`/`immediateCrossHexFills` 后段非零。
2. `unmetNeed` 后段显著低于改造前（改造前 1200 后 3.1M~4.4M/周期；目标下降，具体值由测试报告给出）。
3. 货币/商品/资产守恒；D-022 violations=0；D-023 流民无配额/无组织；D-025 城市可衰亡。
4. 债表出现 Money 与 Commodity 两类合同，且同一家户可有多债权人/多单位条目。

## 8. 已知缺口与风险

1. **债务螺旋**：信用扩张靠 headroom/利率/违约约束，本批不做债权转让与抵押品拍卖；坏账留给既有债务压力/清算/迁移。
2. **价目表缺失**：债务人价目表尚无家户级数据；本批先走“市场区默认价目表”路径，家户级价目表是 D-023 的既有留位。
3. **实物按数量排序不具经济可比性**：这是用户明确口径；实现照做，并在读口中标注。
4. **货币出借保留额**：`LENDER_MONEY_BUFFER_PER_CAPITA_MILLI` 是具名默认，需在测试中观察是否足够。
5. **卖方对实物借贷的意愿**：本批默认“挂单剩余即可借”；GM 可关 `MARKET_GOODS_LENDING_ENABLED`。
6. **跨区信用**：D-027 仍单区；跨区借贷后续批次。
7. **`MarketReport` 形状变更**：需同步更新 surefire/夹具与读口；测试代理负责。
8. **利率**：本批统一 `BORROW_RATE_PER_MILLE_PER_CYCLE`；出借单可带费率字段但默认同一值。

## 9. 实施阶段与文件所有权（按 AGENTS §一.5/§一.8）

### Phase 1（economy main）：信用额度估值 + 还款顺序

允许：
- `simos-economy/src/main/java/io/mosire/simos/economy/time/DebtCapacityBook.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/DebtValuation.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java`（仅 `repayDebts`/capacity 调用点/计息顺序注释）
- `simos-economy/src/main/java/io/mosire/simos/economy/model/DebtContract.java`（仅当必须；预计不改）
禁止：测试、app、docs、commit。

### Phase 2（economy main + app 最小改动）：市场信用

允许：
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketReport.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java`（市场回合构造/借贷明细接线）
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketReadout.java`（只读透传）
- `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/MarketUnfilledReason.java`（新增三个原因）
- `simos-app/src/main/java/io/mosire/simos/app/...` 仅当读口/装配必须；优先不改
- `simos-economy/src/main/java/io/mosire/simos/economy/model/EconomyMeta.java`（追加 v3 版本位）
禁止：测试、docs、commit。

### Phase 3（测试代理）

允许：`simos-economy/src/test/**`、`simos-app/src/test/**`；更新 `RealTwelveHexProductionRuntime3650Test` 与服务商
`RealTwelveMarketFreezeDiagnosisTest`（诊断测试可保留/转为正式信用成交验收）；重跑 economy 全量与 12hex 3650。
禁止：main、docs、commit。

## 10. 逻辑完全性自检

- 钱/货/债每一步：市场信用成交时只发生“银主→买方贷款 + 买方→卖方货款 + 卖方→买方货腿”或“卖方→买方货腿 + 商品债”，
  没有第三种库存变动；债务合同生成不碰余额。
- 失败时：没有头寸/额度/价格 ⇒ 不成交、不借、不造资产，写具名原因。
- 旧数据：直接作废，GM 重置；无兼容迁移。
- 验收：§7 每条都有读数/断言；实现代理与测试代理按 §9 文件所有权分工。

## 11. D-031（2026-10-04 用户裁定）：**取消借债上限，粮食给到放贷人拿不出为止**

> 用户原话：「不是，谁让你设借债上限了？没有抵押物就没有呗，把这个限制取消掉！
> 粮食应该给到一点粮都给不出来为止！」

**本裁定取代以下旧口径**（旧文保留，只作历史）：
- §3.4/§3.5 的"信用额度 = 开市前 `DebtCapacity`，headroom 不足不借"；
- §7.1 第 4 条"headroom 不足时借不到；货币债/商品债都占额度；不可定价债务 ⇒ headroom 0"；
- §8 第 1 条中"信用扩张靠 headroom 约束"的表述。

### 11.1 新口径（唯一行为）

1. **借款人侧没有上限**：借债不再检查 `DebtCapacity`/headroom；没有抵押物也允许借。
2. **唯一的借出上限 = 放贷人实际可借出的钱/货**：
   - 货币：家户余额 − 既有冻结 − 放贷方保留额（`LENDER_MONEY_BUFFER_PER_CAPITA_MILLI` 与自身未覆盖需要）；
   - 实物：卖单剩余（= 卖方库存 − 已冻结 − 下一轮必要投入 − 生活保留），按剩余数量降序；
   - 直借粮：同格放贷人的余粮（库存 − 本周期自需 × `LENDER_SUBSISTENCE_RESERVE_PER_MILLE`）。
3. **顺序不变**：借款人先借货币买货；货币池用尽才借实物；直借粮仍是市场之后、所有救济通道的最后一步。
4. **债务与风险**：每笔借出照旧落 `DebtContract`（`Money`/`Commodity`），到期计息/偿还按 D-023/D-030 不变；坏账风险由债权人承担。
5. **用途**：借到的粮当日顶饭（直借粮路径），市场借货币买的粮按 D-030 原子三腿交付。
6. **失败原因**：只剩"没有可借头寸/没有可卖货物/算法未覆盖"三类；`NO_CREDIT_LIMIT` 不再表示"借款人额度为 0"，
   只允许表示"可借头寸还有余额但凑不成一笔正交易"（本批保留该枚举值做读口兼容，语义已在 javadoc 更正）。
7. **`DebtCapacity` 的去向**：不再是借贷门；仍可服务清算/阶层下滑/只读诊断，但不得再作为任何借出路径的封顶输入。
8. **日志**：`11a DEFICIT_POOL` 去掉 `debtorsWithHeadroom/headroomTotal/sampleCapacity`，改记
   `lendableLenders/lendableTotalGrainMilli/debtors/debtorsWithGrainStock`；市场轮 `09 CREDIT_CAPACITY` 整条删除。
9. **无放贷头寸 ⇒ 不造债**：市场信用只有在真实可借货币/卖单剩余存在时才成交；同格借粮只有在同格放贷人
   `lendableOf > 0` 时才放。没有任何可借头寸 ⇒ `creditFills=0`、`lentGrainMilli=0`、**不生成任何新
   `DebtContract`**，只写 `NO_LENDABLE_MONEY`/`NO_LENDABLE_GOODS`/`NO_BUDGET` 等具名读数。
10. **"无力还钱"不是拒绝理由**：借款人没有收入、没有资产、没有抵押、已经欠债，都不构成不借的理由；这类债照借，
    到期还不上就走既有的违约/清算/豁免规则。**唯一能阻止借出的，是放贷人那一侧真的拿不出钱/货。**

### 11.2 非目标

- 不改还款顺序、利率、到期周期、清算/违约规则；
- 不改卖方的"下一轮必要投入 + 生活保留"（放贷人自己那份口粮仍受保护，除非用户另行裁定）；
- 不新增持久组件、不改 `DebtContract` 形状、旧档照旧作废 GM 重置。

### 11.3 验收判据（测试阶段执行，本次不跑）

1. 同一场景下，取消上限前后：无收入/无抵押的缺粮户能借到粮或货币；借入只受放贷人可借量封顶。
2. 直借粮：放贷人余粮降到 `lendableOf` 规则下的 0 时停止；不得出现负库存。
3. 市场信用：货币池/卖单剩余被借空后停止；债务合同按 `(debtor, creditor, unit, terms)` 累加；守恒不变。
4. `creditGoods > 0` 可出现（货币池不足时借实物粮）。
5. D-022/D-023/D-025 验收不变。
