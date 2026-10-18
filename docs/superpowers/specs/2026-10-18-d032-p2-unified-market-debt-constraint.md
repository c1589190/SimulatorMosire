# D-032 P2 约束设计书：统一市场（商品+土地）+ 债务最先 + 删 Pledge（2026-10-18）

> 状态：**控制方工程约束已冻结；P2 实现尚未开始。**
> 上位文档：`docs/superpowers/plans/2026-10-18-d032-rural-master-dispatch.md` §8 与 §17；`docs/superpowers/specs/2026-10-18-d032-rural-tick-contract.md` §3.2 M2/M4、§4、§5。
> 本文件只冻结 P2 的实现形状；不重新裁定玩法。用户原话见 §12 逐字附录。
> 权威顺序：用户原话 > D-032 U1–U23 > master dispatch > 本文件 > 当前代码。

## §1 目标与非目标

### 1.1 P2 目标

1. 市场轮只在 `day % 5 == 0` 开；删除低库存追加轮与关账日额外开市；删除 35 天生活储备。
2. 每个市场轮**最先**做债务清算（M2），然后才是生产编排/市场/生产/工资。
3. 债务本金只允许货币；库存与土地只是还债介质；删除 `Pledge`/抵押/担保/特定资产优先受偿。
4. 债权人按**纯库存财富**降序受偿；受偿大类顺序固定为：货币 → 库存 → 土地。
5. 土地是 `AssetKind.LAND` 的生产能力资产；土地块有稳定 id、hex、面积、owner/operator；价格按市场区一个价；`0` 合法，未定价与 0 分离。
6. 商品订单与土地订单进入同一个市场轮/同一个撮合入口；土地订单不进入普通商品库存。
7. 本轮新买/抵债取得的土地写入同 tick 瞬态 `landAcquiredThisRound`；P2 只落标记与产权行为，P3 的 M3/M6/M7 消费它。

### 1.2 P2 非目标（留给 P3/P4/P5/P6）

- P3：两遍单家户生产编排、雇佣锁、M3/M6/M7 的完整生产选择、卖地中断后的位置失败。
- P4：工资只付货币、普通工资债、U20 工资锚。
- P5：单一 `grain_farm` 模板、阶级投影、农村 Seeder 默认值、初始土地分布。
- P6：新测试、变异自证、全量 `clean verify`。
- 不做：新的融资上限、强制卖地/强制找买家、抵押/担保/特定资产优先权、旧档兼容。

## §2 P2 批次与可编译边界

P2 不允许一次改完再编译。按下列顺序由同一个 W2 owner 顺序执行；每批编译绿后才进下一批。

| 批次 | 目标 | 必过编译 |
|---|---|---|
| P2a | 市场轮只 `day%5==0`；删 35 天/追加轮 | `tools/mvn-lock.sh -pl simos-economy -am -DskipTests compile` |
| P2b | `MarketTable.landPrice`（OptionalLong）+ 土地块稳定 id | 先 economy，再 app |
| P2c | `DebtUnit` Money-only；还款介质内部类型 | app |
| P2d | Pledge 全链删除（含 app 机械清理例外） | app |
| P2e | 债务最先（M2）+ 纯库存财富 + 土地直接抵债 | app |
| P2f | 商品+土地同订单簿/同撮合入口 + `landAcquiredThisRound` 标记 | app |

> P2a 可独立先行；P2b–P2f 顺序不可交换：P2e 依赖 P2b/P2c/P2d；P2f 依赖 P2b/P2e。

## §3 市场节奏与 35 天储备（P2a）

- `MarketTrigger` 只保留 `NONE`、`PERIODIC`；`triggerFor(day, marketTables)`：
  - 市场表为空 ⇒ `NONE`；
  - `day % 5 == 0` ⇒ `PERIODIC`；
  - 否则 `NONE`。
- 删除 `CYCLE_CLOSE`、`LOW_GRAIN_STOCK`、`lowGrainStock`、`MARKET_LOW_STOCK_TRIGGER_DAYS`、`MARKET_LOW_STOCK_EXTRA_PHASE_DAYS`。
- 删除 `MARKET_SAFETY_STOCK_DAYS=30` 与 `MARKET_LIFE_RESERVE_DAYS=35`：
  - `MARKET_LIFE_RESERVE_DAYS = MARKET_RESTOCK_INTERVAL_DAYS = 5`；
  - `MARKET_BUY_DEADLINE_DAYS = 5`；
  - `HouseholdValuationBook.HOLD_DAYS = 5`；
  - 保留买/卖侧“到下一市场轮的 5 tick 基础消费 + 生产投入”口径。
- 不保留“35 天”文案、常量或测试期望。

## §4 土地块、地价与身份（P2b）

### 4.1 稳定土地块 id

- `OwnershipStake.id` 只由稳定键派生：`(industry, asset, location, sequence)`；**不把 owner/operator 编进 id**。
- 土地所有权转移只改 `owner`/`operator`；`id`、`location`、`quantity`、`asset` 不变。
- `sequence` 必须由稳定排序/确定性分配得到，不得依赖 `HashMap` 迭代序、时钟或随机数。
- 非土地资产同样走稳定 id 规则；Alpha 旧档直接失败/重建，不写兼容位。

### 4.2 `MarketTable.landPrice`

- 新增 `OptionalLong landPrice`：
  - `OptionalLong.empty()` = 未定价；
  - `OptionalLong.of(0)` = 明确 0 价（合法）；
  - 负值拒绝。
- 量纲：**毫计价货币 / 1 个整亩**；土地数量用千分亩（`quantityMilliMu`），价值：
  `floor(quantityMilliMu × landPrice / 1000)`。
- 保留 5 参构造器/`singleHex` 作为“未定价”中性构造；新增带 `landPrice` 的构造器/`withLandPrice`。
- 所有 `withPrices/withPrice/withNumeraire/withHexes/withLoanRate*` 必须原值带过 `landPrice`。
- `hasLandPrice()` 必须显式区分 empty/0；任何调用方不得用 `orElse(0)` 把未定价冒充免费。
- 价格按市场区一个价；禁止 hex 级独立地价表。
- P2 不自动生成初始地价：empty 保持 empty。P5 的 Seeder 或显式 GM/测试写入才给初始值；P2 测试用显式 `OptionalLong`。
- P2f 的地价调整只对已定价市场做；empty 市场不生成土地订单、不改价。

### 4.3 土地质量和数量

- 土地数量/质量按 hex；`landPrice` 只按市场区。
- 质量不进入价格表，只进入生产能力的可经营规模/估值量；P2 只保留字段与读取口，具体产出约束由 P3/P5 实现。
- 土地块整块交易；P2 不支持部分成交、不找零、不拆块。

## §5 债务清算（P2e）

### 5.1 位置与日序

- 市场日（`PERIODIC`）日序：`entry → 债务清算 M2 → 重建 SettlementIndex → 消费 → 生产 → 工资 → 市场 → 政府授权生命周期 → close`。
- M2 必须在任何市场订单生成前完成；不得“市场先卖地再抵债”。
- `anyCycleClosed` 只继续服务计息/关账/阶层；不得用它决定是否还债。
- 债务清算后必须重建 `SettlementIndex`，让后续生产/市场读取新产权。
- 删除原“市场之后”的还债调用；同一还债事实只允许一个写口。

### 5.2 债务人/债权人顺序

- 债务人：只取 `principal > 0` 的合同，按 `HouseholdId.value()` 升序。
- 同一债务人的债权人：
  1. 债权人纯库存财富**降序**；
  2. 债权人 `HouseholdId.value()` 升序；
  3. `DebtContractId.value()` 升序。
- 纯库存财富快照在 M2 开始时冻结一次；本轮收款不实时重排。
- `isSelfHeld` 继续排除（发行记账事实）。
- 债权人全灭：`DebtStatus.FORGIVEN`，本金归零，土地留债务人；不得转给不存在对象。

### 5.3 纯库存财富

- `PureInventoryWealth = 钱 + 商品库存 + LAND`。
- 不扣债务；不含 `SMALL_TOOL`/`LARGE_TOOL`/`WORKSHOP`/`MACHINE`/`SHIP`。
- 计价币 = 家户所在市场区的 `numeraire`。
- 钱：用既有 `CurrencyValuation` 的 `valuationMicro(numeraire, currency, region)`；
  - 无价/不认识的正余额**不计入**并记 DEBUG，不得 1:1 冒充；
  - 同一家户的估值结果必须确定。
- 商品：优先用本户价目表 `HouseholdValuationBook.priceOf`，否则用其市场区 `MarketTable.priceOf`；
  - 未定价商品不计入并记 DEBUG。
- 土地：用土地所属市场区 `MarketTable.landPrice`；
  - `empty` 不计入并记 DEBUG；
  - `0` 计入且价值为 0。
- 排序比较使用“可估值部分”的合计；无价资产的漏计是**保守下界**，必须在 DEBUG 中具名。该口径是工程默认，不改变用户“钱+货+土地”的语义。

### 5.4 受偿介质与土地抵债

固定大类顺序：

1. **货币**：先债务币种；其它币种按当前市场区 `CurrencyValuation` 折算；缺汇率具名跳过，不得 1:1。
2. **商品库存**：只作还款介质；低估值优先 + `CommodityId` 稳定破平。
3. **土地**：按 `valueMilli = floor(quantityMilliMu × landPrice / 1000)`；
   - 土地块按价值升序 → `AssetShareId` 升序；
   - `landPrice.empty()` ⇒ 不转地、余债保留，DEBUG 具名；
   - `landPrice=0` ⇒ 整块转移、价值 0、债务不减少（按字面规则）；
   - 价值 > 余债 ⇒ 整块转走、不找零，债务清零；
   - 价值 < 余债 ⇒ 整块转走，余债继续。
- 土地转移走唯一产权写口，只改 owner/operator，id 不变；不进入 `HouseholdGoods`。
- 本轮抵债取得的土地加入 `landAcquiredThisRound`。
- 债务人无资产/资产不足 ⇒ 余债继续；不强制卖地、不伪造买家、不新增 Pledge。

## §6 Pledge 与货币债（P2c/P2d）

- 删除 `Pledge`、`PledgeId`、`pledges` 组件、Codec/Payload/ChangeSet/Seed/UI/迁移/自动清算中的 Pledge 分支。
- 删除自动质押/抵押处置/特定资产优先受偿；保留与 Pledge 无关的危机信号/压力计数时，必须有具名新 owner，且不得偷偷恢复抵押。
- 同删只服务 Pledge 的配置：`AssetRule.pledgeable`、`liquidationPriority`、`LiquidationPolicy` 及其写面。
- `DebtUnit` 只保留 `Money`；删除 `Commodity` 变体。
- 商品欠款在形成时按当地价折货币；无价时具名 fail-closed/未解析，不得 0 折或 1:1。
- 内部可新增 `RepaymentMedium` 区分 money/commodity 作为**还债介质**；合同本金/币种仍只 Money。
- `DebtContract`、`DebtContractId`、Codec/Payload 的 commodity 分支按 Alpha 直接删除。
- 组件数 37→36；`EconomyRoundTripTest` 等旧断言由 P6 迁移，P2 只保证生产编译和既有可运行测试的具名红灯清单。

## §7 统一市场（P2f）

### 7.1 订单/订单簿最小形状

- 新增内部 `MarketInstrument` sealed 变体：
  - `Commodity(CommodityId)`；
  - `Land(AssetShareId)`。
- 商品和土地订单进入**同一个 `MarketRound` 的买/卖槽集合**与**同一个 `clearOncePerCycle` 入口**；不得另开 `landMarketPhase` 或第二个撮合器。
- 土地卖单：引用 `AssetShareId`、整块数量、限价、收款币、卖方 actor/location。
- 土地买单：P2 最小实现引用**具体 `AssetShareId`**（逐块定向）。买单由 planner 在收集本轮土地卖单后生成；仍进入同一订单簿与同一撮合入口。
- 土地数量按块自身 `quantityMilliMu`；不支持部分成交；P2 不生成“按 hex 汇总但不知具体块”的匿名买单。
- 土地订单不得使用 `CommodityId` 伪键，不得进入 `HouseholdGoods`。

### 7.2 共享预算/冻结

- 同一买方 actor × 支付币只有一份可花预算；商品订单先冻结，土地订单只能用剩余可花额。
- 基础消费/生产投入的商品订单优先于土地投资订单；同类内按现有稳定排序。
- 土地卖单冻结是 `MatchContext` 瞬态 `Set<AssetShareId>`；不写 actor 的 `frozenGoods`，不写第二本库存。
- 同一买方商品+土地订单不得双重支付；成交后按同一 `AccountSession` 落账。

### 7.3 土地成交/结算

- 土地成交：买方现金腿走既有 `applyTransfer` 唯一写口；现金不足且有成交时，买卖双方均为家户则普通货币债，卖方非家户则不成交。
- 土地产权腿：只改 owner/operator；不搬商品库存；无运费。
- P2 不征土地税；商品既有口岸税/税层不变。
- 本轮取得土地写入 `SettlementDayContext.landAcquiredThisRound`（`LinkedHashSet<AssetShareId>`），同一 step 内 M2/M4 写、P3 的 M3/M6/M7 读；不落盘/不进 ChangeSet/Codec。
- P2 的验收只要求“成交后标记存在、土地不进普通库存、产权 id 不变”；M3/M6/M7 的不可用和卖地中断由 P3 验收。

### 7.4 买家排序与市场区

- 保留市场区/拓扑作为外层撮合范围（与现有区内优先/邻区规则一致）。
- 同一市场区内的买槽按纯库存财富降序 → `HouseholdId` 升序 → 稳定订单 id；不得用净值/债务调整。
- 跨区/路线匹配沿用现有市场区/运输规则；土地无运费，但不得把商品运输规则复制到土地产权。

## §8 文件所有权与机械清理例外

- W2 可以按批次修改 economy/economy-api 的相关文件，以及 `simos-app` 的只读装配/视图文件。
- **例外（控制方已批准）**：P2b/P2c/P2d 允许机械修改：
  - `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`
  - `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeederConditions.java`
  - `simos-app/src/main/java/io/mosire/simos/app/world/ProductionPayloadSeeder.java`
  - `simos-app/src/main/java/io/mosire/simos/app/world/TestConditions.java`
  - 其他直接引用被删类型的 app world 文件（仅签名/分支机械清理）
- 例外边界：**不得改初始土地分布、生产方式、种子数值、租佃默认或任何 P5 玩法**；只允许删 Pledge/Commodity/旧 idOf 的编译残留。
- 不改 POM/enforcer/权限；不写/跑测试；不 commit/push。

## §9 验收判据（P2）

### 9.1 P2a

- 第 3 天即使低库存也不开市；只有 `day%5==0` 开市。
- 35 天/30 天安全库存常量与文案不存在。
- 到下一轮的保留窗口 = 5 tick。

### 9.2 P2b

- `MarketTable.landPrice` 的 empty 与 0 在 Codec/roundtrip 中可区分。
- 土地块转移后 id/location/quantity/owner/operator 语义正确；id 不把 owner/operator 编进去。
- 所有 `with*` 不改丢 `landPrice`。

### 9.3 P2c/P2d

- 无 Pledge 组件/Codec/命令/Seed/UI/自动清算分支。
- `DebtUnit` 无 Commodity；商品欠款形成时折货币，缺价具名。
- 组件数 37→36；旧 Pledge/Commodity 测试的失效项有具名清单。

### 9.4 P2e

- 市场日债务在订单生成前完成。
- 债务人 id 升序；债权人纯库存财富降序 → id → debtId。
- 货币 → 库存 → 土地；整块不找零；余债继续。
- 债权人全灭：FORGIVEN、土地留债务人。
- 纯库存财富不扣债、不含工具/作坊。

### 9.5 P2f

- 商品+土地在同一 `MarketRound`/同一撮合入口成交；无双市场阶段。
- 土地订单不进 `HouseholdGoods`；产权 id 不变；共享预算无双付。
- 本轮取得土地标记存在且不落盘。
- 未定价土地不成交、不伪造；0 价可成交。
- 卖地中断/新土地本轮不用留 P3 验收。

## §10 不可破坏机制

- 五条铁律；`Command → ChangeSet → Revision`；领域单 owner；Core 只组合调度。
- `AccountSession` 唯一账户工作副本；M2/M4/M7/M8 同 revision 原子提交；失败零半提交。
- 稳定排序/禁止 HashMap 迭代序/时钟/随机 UUID。
- 经济日志：INFO 业务拒绝具名、DEBUG 判据、TRACE 逐笔；契约故障 ERROR 不降级。
- 不新增 第二可写权威；不把 LAND 放进普通货物库存；不恢复 Pledge/抵押。

## §11 工程默认（可被控制方调整，不需用户逐项裁定）

1. `landPrice` 单位 = 毫计价货币/整亩；数量 = 千分亩。
2. P2 不自动给初始地价；empty 保持；P5/GM/测试给定。
3. 土地订单整块、定向 `AssetShareId`；P2 不支持部分成交。
4. 纯库存财富中无价资产按 0 计并 DEBUG；这是保守下界。
5. 市场区外层排序保留；区内纯财富序。
6. P2 不征土地税。
7. 0 价土地抵债：转地、价值 0、债务不减；empty：不转、余债保留。
8. Pledge-adjacent 配置（pledgeable/liquidationPriority/LiquidationPolicy）随 Pledge 同删；保留的危机/压力逻辑不得读取抵押。
9. P2a 可先独立完成；P2f 的完整生产侧消费归 P3。

## §12 用户原话附录（逐字，P2 相关）

> 完整逐字表见 master dispatch §17；以下是 P2 直接相关的原话，不改写、不缩写。

```text
为什么土地市场和商品市场是分开的？我的原话多次提到两者是统一的……
根据hex来，数值你来定，因为这玩意能直接决定测试世界的最大农业生产规模……
粮食库存过低为什么要额外开一轮？去掉！
土地产权就是一般商品，走正常市场流程，允许在还债的时候抵押
首先，为什么这块地会被挂到市场上，它应该在最开始的还债环节就被抵走了
当前市场区的土地价格啊
？？？？谁让你加的担保机制？借债就是借债，还债就是还债，我没说的机制不要加
2 抵押是什么？如果你说的是还债的话，定一个还债顺序就可以解决了，那就是先还给有钱的——没钱的没能力组织催收
工资债就是一般债务，家户到家户的，和其他债务一同计算，允许使用库存，我说不允许是不让你在第一次付工资的环节直接上库存
1 不行，不抵押库存
2 用完为止
3 因此也没有抵押问题
我想了一下，要不还是把账面欠薪债加回来吧
```
