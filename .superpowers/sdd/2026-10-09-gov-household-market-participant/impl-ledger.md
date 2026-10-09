# R1 实现架构账本：政府行政家户（国库户）回到商品市场 + 只按明确授权下单

> 责任区：R1（约束设计书 `docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` §7 第 1 行）。
> 基线：`e928d535`。写法子 Agent 交付物；**只写生产代码、只过编译**（§一.5）；不写/不改任何测试、不跑 test/verify/package、不 commit。
> 编译门禁：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` → **exit 0**（多轮，末轮在全部改动落地后重跑）。
> ★ 跑前每次都先 `pgrep -af classworlds.launcher`（本仓无 Maven 在跑）——见 AGENTS §一.1。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（基线 `e928d535`） | 结论 | 影响 |
|---|---|---|---|
| C1 | `EconomySettlement.java:775` `mergeMarketExclusions(marketExcludedHouseholds, base.governments())` | 有效排除集 = 组合根单位户 ∪ **政府国库户**（Z7b 的那一半） | 这是本批要撤销的那一处；改后国库户不再是"排除户" |
| C2 | `GovernmentHouseholds.java`（`simos-social-api`）：`hh-gov-<govUnitId>`；`EconomyData:1729` 起 `Government.treasury` 必须钉在该家户上 | 国库户身份是 GOV 单位 id 的纯函数；**不能**按 `hh-gov-` 前缀猜（前缀只是拼法） | 名单权威 = `EconomyData.governments()`（`Government.treasury()`） |
| C3 | `MarketSettlement.planOrders`（基线 `:1559`）循环里 `marketExcludedHouseholds().contains(...)` 直接 `continue` | 排除户**根本不生成订单**；卖家判据 = `max(0, 持有 − 冻结 − 必要投入 − 生活保留 − 需求目标)` | ★★ 0 人口国库户后四项全 0 ⇒ 一旦只"撤销排除"、不加守卫，它会**被当卖家清仓**（Z7b 的根因：run6 day50 中央粮 271,393 → 0）。本批**实测**：三个国库户被压住的自动卖单合计 **87,784,976 毫**（≈87,785 单位，见 §5） |
| C4 | `MarketSettlement.participantsFor`（`:5571`）与 `:5667`（unit 经营者解析到排除户） | 单位户有**两层**排除（家户层 + 经营者层） | 单位户那一半一个字不改；两层都保留 |
| C5 | `FxSettlement.java:273` `round.marketExcludedHouseholds().contains(household)` → `continue` | **同一个排除集兼着"商品市场"与"家户外汇单"两件事** | ★ 撤销商品市场那一半时**必须**显式保住外汇那一半（国库户是 GOV 外汇窗口的属主，不该再挂家户外汇单）：新增 `govMandates().isAuthorizationOnly(...)` 守卫 |
| C6 | `MarketSettlement.CreditPools.build`（`:2506`）遍历 `ctx.participants.values()` 生成**货币放贷人** | 改前国库户不是参与者 ⇒ **它不是放贷人** | ★ 国库户回市场后会顺理成章变成"自动放贷人"，普通家户的借贷结果随之改变 ⇒ 显式跳过（授权 ≠ 放贷） |
| C7 | `MarketSettlement.creditRound`（`:1965`）遍历买槽 | 有买盘就可能被**信用撮合**补钱 | ★ 授权 ≠ 加杠杆 ⇒ 国库户的买盘不得进信用撮合（显式跳过） |
| C8 | `MarketReadout.java:183` 传 `EconomySettlement.governmentTreasuryHouseholds(data.governments())` | 读口与结算**同源**排除国库户 | 读口改为同源注入授权计划（"看到的订单 == 会下的订单"） |
| C9 | ★★ **`Unit.households()` 里含 GOV 单位的国库户**（探针实测：`hh-gov-gov-central` / `hh-gov-gov-province` 都在里面；只有 `hh-gov-world-silver` 不在） | 组合根注入的"单位户排除集"**本来就盖住了两个主政府的国库户** ⇒ 只改 `EconomySettlement:775` 的话，撤销会被单位户那一半**原路抵消** | ★★ 按**身份**重新分类：`单位户 − 已登记政府国库户` 仍排除；摘出来的国库户走"只按授权下单"。**这是本批最关键的一处发现**（不测出来整个 R1 对两个主政府等于没做） |
| C10 | `MarketSettlement.classifyNoSupply`（`:5999`）遍历本格参与者判 `ALL_RESERVED` | 参与者表影响**未成交归因**（只进报告，不进状态） | 国库户成为参与者后，普通家户的"没货"归因可能从 `NO_ADJACENT_SUPPLY` 变 `ALL_RESERVED`（读数差异，见 §6） |
| C11 | `MarketRound` 的瞬态字段曾被克隆丢掉（类注记：arbitrage 丢过一次、fx 丢过一次） | 新增瞬态输入有"静默丢字段"的既往前科 | ★ 新增 `govMandates` 在 `withCredit/withArbitrage/withFx/copyForWorker` **四处**逐字段带过 + `EconomySettlement` 里加**防复发守卫**（丢了就 ERROR + 抛） |
| C12 | `CatalogTool` 构造期强制"已注册命令类型必须登记 `PAYLOAD_HINTS`" | 新命令缺提示 ⇒ **Shell.start 直接抛**（真装配 fail-closed） | ★ 探针第一轮就撞上（`IllegalArgumentException: 已注册命令类型未登记载荷提示`）⇒ 已补两条 |
| C13 | 一次 `advance` 的**多日共用同一个会话工作副本**（只落一条 revision；探针实测 `advance(0→30)` ⇒ revision +1） | 当日"耗尽/到期清除"必须对**后续各日**立即生效 | ★ 授权计划必须读 `session.sheet().govMarketMandatesOrBase()`（**工作副本**），读 `session.base()` 会把已清掉的行再挂一遍 ⇒ 成交回落时找不到状态行（探针实测踩到、已修） |

---

## 2. 授权通道形态与理由（本批的"落点"选择）

**选型：`EconomyData` 第 39 个组件 `govMarketMandates`（政府市场授权表）**（`Map<MarketMandateId, GovernmentMarketMandate>`）。

- 值 `GovernmentMarketMandate`（`simos-economy-api`，`api/market/`）：`id / government / commodity / side(BUY|SELL) / quantityMilli / limitPriceMilli / authorizedDay / expiresOnDay / filledMilli / source`；
- 两条 GM 命令：`economy.AuthorizeGovernmentMarketOrder`、`economy.CancelGovernmentMarketOrder`；窄工具 `simos.gm.govMarketMandate`（`list/authorize/cancel`）。

**为什么不是候选二（`GovBudgetLine` 新类别 ⇒ 组合根转成订单）**：`GovBudgetLine` 住在 `simos-gov`，而 R1 的文件所有权**不含** `simos-gov/**`（设计书 §8），且预算面是"政府花钱"的口径，表达不了 `SELL`（抛售）与"限价"两维 —— 硬塞进去就是"为了少写一个组件而发明的第二种拼法"。

**为什么必须落成状态、而不是逐轮瞬态（像 `regulation`/`arbitrage`）**：授权有**生命周期**（累计成交 `filledMilli`、耗尽/到期清除），而瞬态输入不进变更集 ⇒ 重启/重放后"已成交多少"会丢，可能重复下单。落状态 ⇒ 走 `EconomyChangeSet`（第 39 个组件一一对应，铁律 5）+ `EconomyCodec` 键反序列化器，往返可重放。

**它为什么不是"额外再加一个调整机制"（用户 §1.3 的原话）**：
- 只写一张"授权表"，**不改价格、不改成本、不豁免任何撮合规则**；订单仍由既有的 `家户→订单→撮合→结算` 在日结算里生成；
- 成交价仍按市场参考价裁定（区内 = 本格参考价 / 跨区 = 卖方格参考价）；
- 授权限价**只能比市场自身的限价更严**：`BUY` 取 `min(授权限价, 市场买方限价)`、`SELL` 取 `max(授权限价, 市场卖方底价)`；
- 买盘**不走信用**（授权 ≠ 加杠杆）；不放贷；不挂家户外汇单。

**幂等与"不许留永久挂单"**（用户「肯定不是主动啊」= 只按明确授权下单）：
| 情形 | 语义 |
|---|---|
| 同 id + 授权形状逐值相同 | **具名拒** `idempotent-duplicate-authorization`（不双倍下单、零 revision、head 不动）★ 为什么是拒而不是静默 no-op：`HandlerOutcome` 只有 Applied/Rejected 两种，而 Applied（哪怕全 Unchanged）也会落一条 revision ⇒ 静默 no-op 会给"看起来成功了"的假象 |
| 同 id + 形状不同 | **具名拒** `authorization-shape-change`（要改形状先 Cancel 再授权 —— 否则"改一次量"就能把已成交量悄悄重置 = 另一种双倍下单） |
| 同政府 + 同商品 + 同方向已有一条生效授权 | **具名拒** `duplicate-open-authorization`（两条同类授权会生成两条同类订单，成交量无法归属） |
| 量耗尽 | 状态层**删除该行**（INFO `GOV_MARKET_MANDATE_CLEARED reason=exhausted`） |
| 到期（`day > expiresOnDay`） | 状态层**删除该行**（`reason=expired`）；`expiresOnDay` 是**必填** ⇒ 本批不存在"永久授权" |
| 撤销 | `economy.CancelGovernmentMarketOrder`；不存在/已清除 ⇒ 具名拒 `unknown-authorization`（不静默成功） |

---

## 3. 撤销排除的逐处清单（"依赖国库户不在市场"的假设，逐条核）

| # | 位置 | 改法 |
|---|---|---|
| 1 | `EconomySettlement.settleOneDayInto`（原 `:773-775`） | 删 `mergeMarketExclusions(..., base.governments())`；有效排除集 = 组合根单位户 **减** 已登记政府国库户（见 #2） |
| 2 | 新增 `EconomySettlement.unitExclusionsMinusGovernmentTreasuries` | ★ C9：`Unit.households()` 含 GOV 单位的国库户 ⇒ 必须按身份摘出来，否则撤销被单位户那一半抵消 |
| 3 | 删除 `EconomySettlement.mergeMarketExclusions` | 旧的"单位户 ∪ 国库户"有效排除集**整体删除**：留一份"谁退出商品市场"的第二种拼法就是第二个权威 |
| 4 | `EconomySettlement.governmentTreasuryHouseholds` | 保留（名单来源），类注改写为"R1 起 = 只按授权下单的名单来源，不再是排除集" |
| 5 | `MarketSettlement.ordersFor`（原 `:1560-1564` 守卫） | 守卫改为"只挡单位户"；**新增**"只按授权下单"分支（国库户：不生成任何自动订单，只把当日生效授权折成订单） |
| 6 | `MarketSettlement.participantsFor`（`:5571`、`:5667`） | 不变（单位户两层排除照旧）；日志 `reason` 从 `treasury-or-unit-household` 改为 `unit-household`（口径已变，别让日志说谎） |
| 7 | `MarketSettlement.planGovMandateOrders`（新增） | 授权 → 订单：`BUY` 量 = `min(剩余授权量, 按参考价买得起的量)`、限价 = `min(授权限价, 市场 ask)`；`SELL` 量 = `min(剩余授权量, 可卖余量)`、底价 = `max(授权限价, 市场 bid)`；同时记 DEBUG `GOV_MARKET_AUTO_ORDERS_SUPPRESSED`（含被压住的 `wouldBeBuy/wouldBeSell`） |
| 8 | `MarketSettlement.CreditPools.build`（货币放贷人） | 跳过授权专属家户（C6） |
| 9 | `MarketSettlement.creditRound`（买盘信用） | 跳过授权专属家户的买槽（C7） |
| 10 | `FxSettlement.planHouseholdOrders`（`:273`） | 显式跳过授权专属家户（C5：国库户原来在排除集里就不挂家户外汇单，撤销后必须显式保住） |
| 11 | `MarketReadout`（`:183`） | 排除集改为 `Set.of()`（单位户只有组合根看得见，读口的口径边界照旧记在 Z7e 清单）；同源注入 `GovernmentMarketMandatePlan` |
| 12 | `MarketSettlement.MarketRound` 新增瞬态字段 `govMandates` + `withGovMandates` | ★ 在 `withCredit/withArbitrage/withFx/copyForWorker` 四处逐字段带过（C11 的前科） |
| 13 | `EconomySettlement` 市场轮之后新增防复发守卫 | `marketRound.govMandates()` 与刚构造的计划不等 ⇒ ERROR + 抛（丢了它国库户当场退回自动清仓且毫无报错） |
| 14 | `EconomySettlement.applyGovMarketMandateLifecycle`（新增，**每日无条件**跑） | 成交累加进 `filledMilli`；耗尽/到期删除行并记 INFO |
| 15 | `EconomyDayStepper` / `PopulationEconomyTimeParticipant` 的类注 | Z7b 措辞更新为 R1 口径（"单位户集合"**只**含单位户） |

**状态面**：`EconomyData`（第 39 组件 + 构造期三条守卫：键==值内 id、授权政府必须已登记、其国库必须是家户）、`EconomyStateBuilder`（工作副本 + `govMarketMandatesOrBase()`）、`EconomyChangeSet`（`FieldDelta.rebuild(..., MarketMandateId::parse)`）、`EconomyCodec`（键反序列化器）、`EconomySeedHandler`/`EconomyClearRegionHandler`（原样带过，漏了 = 播种/清区把授权静默抹掉）。

**app 面**：`Shell` 注册两条 handler、`SimosToolSource` 注册窄工具、`CatalogTool.PAYLOAD_HINTS` 两条（C12 的 fail-closed 门禁）。

---

## 4. 授权通道的负向语义（具名拒 + 零 revision + head 不动）

`未知政府` / `国库不是家户` / `国库家户没有经济行` / `国库户所在格没有市场` / `未知或未定价商品`（市场对未定价商品整行不交易 ⇒ 授权是死行）/ `到期日已过`（`expiresOnDay < 当天`）/ `量 ≤ 0` / `限价 ≤ 0` / `side` 词表外 / `幂等重复` / `形状变更` / `同类重复` / `撤销不存在`。
全部：`HandlerOutcome.Rejected` + INFO 具名日志 + **不落 revision**（`CommandBus.reject` 不留 revision 行）。

---

## 5. "无自动订单"的实测证据（探针，真装配）

装置：`/tmp` 一次性探针（`io.mosire.simos.app.R1Probe`，**不进仓库、不进 `src/test`**）；真 `Shell.start` + 真创世（`ShellMain.seedGenesisIfEmpty`，`small-world`）+ 真 `advanceAndDrain`；**不** `core.register` 任何东西。

场景 A：空库 small-world，**零授权**，真跑 30 天（`simos.economy.logLevel=TRACE`）：

```
A treasuries=[hh-gov-gov-central, hh-gov-gov-province, hh-gov-world-silver]
A unitHouseholds=[hh-gov-gov-central, hh-gov-gov-province, hh-unit:gov-central, hh-unit:gov-province]
events=105 autoBuy=0 autoSell=0 mandateBuy=0 mandateSell=0 wouldBeSellSum=87784976
国库户的授权订单条数: 0       涉及国库户的成交条数: 0
单位户排除事件: hh-unit:gov-central ×7、hh-unit:gov-province ×7（reason=unit-household）
涉及单位户的成交条数: 0
A DIGEST=d01602d058cdbf46fad3601bd5183cb438a207c78991f921d64aa0dc62ce3da3 accounts=178
A MARKET_FILL digest=c59f5293cb960181ebb9e600cf637ae82fd3f804a7661811e86d7967e7452c07 (527 条)
```

**R1-b（真成交；授权通道实测）**：
```
B government=gov-unit-gov-central treasury=hh-gov-gov-central hex=0_0 referenceMilli=1 limitMilli=5 numeraire=silver
B tinyAuthorized=... quantityMilli=1000 limitPriceMilli=5 authorizedDay=0 expiresOnDay=20 filledMilli=0
B tinyAfterDay5=<cleared-exhausted>            ← 量耗尽 ⇒ 状态行被清除（到期日 20，不可能是到期）
GOV_MARKET_MANDATE_BUY_ORDER day=3 quantityMilli=1000 limitPriceMilli=2 mandateLimitPriceMilli=5 marketAskMilli=2   ← 限价取更严者
MARKET_FILL day=5 commodity=grain seller=hh-0_0-urban-poor_peasant buyer=hh-gov-gov-central quantity=552 unitPriceMilli=1
MARKET_FILL day=5 commodity=grain seller=hh-0_0-urban-rich_peasant buyer=hh-gov-gov-central quantity=448 unitPriceMilli=1
GOV_MARKET_MANDATE_FILLED day=5 filledDelta=1000 filledMilli=1000 quantityMilli=1000 remainingMilli=0
GOV_MARKET_MANDATE_CLEARED day=5 reason=exhausted
B bigAfterDay10=... quantityMilli=5000000 filledMilli=4583846（部分成交）
MARKET_FILL day=10 seller=hh-0_0-urban-landlord quantity=935421 unitPriceMilli=1
MARKET_FILL day=10 seller=hh-0_0-urban-middle_peasant quantity=1351279 unitPriceMilli=1
MARKET_FILL day=10 seller=hh-0_0-urban-poor_peasant quantity=942696 unitPriceMilli=1
MARKET_FILL day=10 seller=hh-0_0-urban-rich_peasant quantity=1354450 unitPriceMilli=1   （四笔合计 = 4,583,846 = filledDelta）
```
- 买卖双方身份、商品、量、单价、币种（`numeraire=silver`）逐笔可核；成交价恒 = 市场参考价（1 毫/单位），**授权没有改价**；
- day=3 的大额买盘被 `MARKET_UNFILLED reason=LOGISTICS_CAPACITY` 挡住（跨区运力），说明订单走的是**既有撮合与物流规则**。

**R1-c（逐值不变，`git worktree` 基线对照）**：基线 = `git worktree add --detach /tmp/r1/baseline HEAD`（`e928d535`）+ 同一条编译门禁 → exit 0；同一探针（`--baseline` 变体）跑同一世界 30 天：
```
baseline A DIGEST = d01602d058cdbf46fad3601bd5183cb438a207c78991f921d64aa0dc62ce3da3 accounts=178
new      A DIGEST = d01602d058cdbf46fad3601bd5183cb438a207c78991f921d64aa0dc62ce3da3 accounts=178   ← 逐值相同
baseline MARKET_FILL digest = c59f5293cb960181ebb9e600cf637ae82fd3f804a7661811e86d7967e7452c07 (527)
new      MARKET_FILL digest = c59f5293cb960181ebb9e600cf637ae82fd3f804a7661811e86d7967e7452c07 (527)   ← 逐值相同
```
（digest = 全部 178 个家户账户的 `goods/money/frozenGoods/frozenMoney` 规范文本 SHA-256；含三个国库户 ⇒ 连"国库户在无授权时逐值不变"一并证到。）

**负向（具名拒 + 零 revision + head 不动）**：5 类非法授权 + 幂等重复 + 形状变更 + 同类重复 + 撤销不存在，全部 `CommandResult.Rejected` 且 head 与授权表都不动（探针内逐条断言）；`R1 PROBE OK (46/46 checks)`、`exit=0`。样例：
```
C REJECT 未知政府 reason=未知政府（授权没有可执行的主体）: gov-unit-does-not-exist
C REJECT 未知商品 reason=商品在国库户所在格从未定价（市场对未定价商品整行不交易）: unobtainium treasury=hh-gov-gov-central
C REJECT 负量 reason=GovernmentMarketMandate.quantityMilli 必须 > 0（0/负量不是一次授权，是一条假记录）: probe:reject-quantityMilli
C REJECT 到期日已过 reason=GovernmentMarketMandate.expiresOnDay 必须 ≥ authorizedDay（不许出生即过期的授权）: probe:reject-expiresOnDay authorizedDay=0 expiresOnDay=-1
C REJECT 词表外方向 reason=economy.AuthorizeGovernmentMarketOrder 的 side 词表外（只认 BUY/SELL）: HOLD
```

- **R1-a（核心判据）**：105 条 `GOV_MARKET_AUTO_ORDERS_SUPPRESSED`，`autoBuyOrders=0 autoSellOrders=0`（**国库户的自动买/卖单数 = 0**），且 `mandateBuyOrders/mandateSellOrders=0`（没有授权就没有订单）；涉及 `hh-gov-*` 的成交 = 0。
- ★ 同一条事件里的 `wouldBeSellMilli_sum = 87,784,976` 毫（三个国库户被压住的自动卖单合计，单户最大 `hh-gov-gov-province` grain 20,344,259 毫）⇒ **守卫是承重的**：没有"只按授权下单"这一支，它们会当场清仓（Z7b 的根因形态）。
- **R1-d（单位户仍排除）**：`hh-unit:gov-central`/`hh-unit:gov-province` 各有 7 条 `MARKET_HOUSEHOLD_EXCLUDED reason=unit-household`，零成交。

---

## 6. 会改变数值行为的清单

**状态数值（`EconomyData`/账户）**
1. **授权表非空时**：新增 `govMarketMandates` 组件（旧档缺键 ⇒ 空表，旧世界逐值不变）；国库户按授权真买卖（货/钱/运费/税费全走既有链路）。
2. **授权表为空时**：`EconomyData` 的一切状态**逐值不变**（实测：30 天全量家户账户 digest 与基线相同，含国库户，见 §7-R1-c）。
3. ★ `unitExclusionsMinusGovernmentTreasuries`：`Unit.households()` 里的**政府国库户**不再被排除（这是"回到市场"的前提）；其余单位户逐值照旧。

**报告/读数（不进状态）**
4. `MarketReport.unfilled` 的**归因**：国库户成为参与者后，普通家户的"没货"归因可能由 `NO_ADJACENT_SUPPLY` 变为 `ALL_RESERVED`（`classifyNoSupply` 现在能看到持有公家库存的国库户）。★ 只影响报告文本与 `reasonCounts` 日志，不改任何状态。
5. 新增 INFO/DEBUG/TRACE 事件（授权落 INFO、拒落 INFO、计划与压住的自动订单落 DEBUG、订单/成交累加落 TRACE、清除落 INFO）。
6. `EconomyData` 组件数 36 → 37（`toString`/`equals` 形状、变更集 JSON 多一个键 `govMarketMandates`）。★ 旧档读入 ⇒ 空表（`EconomyCodec` 键反序列化器已注册；旧 revision 行重放不受影响）。

**没有改的**：价格/成本/公式/运费分档/税率/预算桥/俸禄/债务/迁移/FX 汇率与窗口/单位户语义/五条铁律的写入口。

---

## 7. 会让既有测试失效的清单（**未改任何测试**；测试由后续测试 Agent 按验收判据重建）

| 测试 | 预期影响 | 理由 |
|---|---|---|
| `simos-economy` `Z7MarketExclusionTest` | **预计仍然通过**（它显式传 `Set.of(TREASURY, UNIT_HOUSE)` 作为第二轮排除集；`planOrders` 的守卫对该集合照旧生效） | ★ 但它测的是 **Z7b 语义**（"国库户退出商品市场"），与新架构（"回市场、只按授权下单"）**口径相反** ⇒ 建议由测试 Agent 重写为 R1 口径（含"未注入计划时国库户仍会生成自动订单"这一**已知边界**的显式断言） |
| `simos-economy` `MarketSettlementFixtures` | 编译不受影响（22 参构造器保留） | 夹具不注入 `GovMandates` ⇒ 国库户在夹具里表现为普通参与者（**已知边界**：生产只有两个构造点，都已注入） |
| `MarketRegulationTest` / `MarketSettlementSingleHexLossTest` | 读 `MarketOutcome` 的两个组件 ⇒ 编译/行为不受影响（新增第 3 组件带 2 参便捷构造器） | — |
| `simos-economy` `EconomyRoundTripTest`（反射枚举组件） | **预计通过**：新组件已进 `EconomyChangeSet` 并有 `withGovMarketMandates` | 若它是"逐组件枚举 + 逐字段重建"，新增组件会自动被覆盖 |
| `simos-app` `GovAdminSalaryBudgetZ6Test` / `Z7D4BudgetLedgerTest` | 只涉及预算/俸禄；与市场排除无关 ⇒ 预计通过 | — |
| `simos-app` 前端门禁 / 目录类测试 | `CatalogTool.PAYLOAD_HINTS` 多两条（已补）⇒ 目录测试若逐条钉死类型数，需要更新 | 新增两条命令是**预期**的注册面变化 |
| 任何"国库户市场流水 byte 级快照"式测试 | 若存在则失效 | 国库户从"零订单"变"可授权下单"（空授权时逐值相同） |

★ **本批未跑任何测试**（任务书：写代码代理只过编译）；上表是静态核出的影响面，**不是**跑出来的结论。

---

## 8. 偏离设计书处（主动记录）

1. **§8 文件所有权**：设计书 R1 的允许写列表**不含** `simos-app/src/main/java/**` 的 `CatalogTool`/`SimosToolSource`/`Shell`（它写的是 `simos-economy/**` + `simos-app/**` + 账本，本批任务书补充 `simos-economy-api/**`）⇒ 实际按任务书执行（app 组合根 + economy-api 均在其中），未越界。
2. ★ **"单位户集合照旧排除，不许顺手放开"的执行形态**：单位户集合里**是政府国库户**的那些（C9：`hh-gov-gov-central`/`hh-gov-gov-province`）被按身份摘出来改走"只按授权下单"。若不摘，R1 对两个主政府**完全落空**（探针实测）。★ 实质未变：官吏户/军户照旧两层排除；摘出来的国库户零自动订单、不放贷、不挂外汇单。**这条请控制方复核**（若控制方裁定"国库户也必须留在排除集里"，则 R1 的 R1-b 只能用 `hh-gov-world-silver` 演示，且 R2 的"压制政府家户的市场行为"失去作用面）。
3. **§7 R1 行提到"预算/定价/下单/被撮合/委托指定家户"**：本批落"下单/被撮合"（含限价）；"预算"由既有预算桥承担（任务书：预算桥先保留、采购迁移是后续单独一步），"委托指定家户"（设计书 §9-O3、§4.5 脚注）**未做**（家户间合同本批不新增）。
4. **授权通道形态**：任务书候选一（`EconomyData` 新组件"待执行的市场订单"）✓ 采纳；候选二（`GovBudgetLine` 新类别）**未采纳**（越界 + 表达不了 SELL/限价，见 §2）。
5. **幂等语义**：任务书只要求"重复注入不得双倍下单"；本批实现为**具名拒**（而非静默 no-op），理由见 §2（Applied 也会落 revision）。
6. **限价必填**：任务书要求"某限价"是必填维度 ⇒ `limitPriceMilli ≥ 1`（不接受无限价授权）；`expiresOnDay` 亦必填（不许永久授权）。

---

## 9. 未完成 / 未验证 / BLOCKED

**未完成（本批非目标，已随任务书冻结）**
- 采购迁移（政府日常采购改走授权表）、"委托指定家户"、口岸/禁运/效率聚合（R2）、政府决策人面（谁有权授权、审批链 —— 设计书 §9-O4 留 GOV 优化）：本批**只有 GM 命令 + GM 窄工具**，属**临时通道**（任务书："这属临时通道、采购迁移是后续一步"）。

**未验证（如实记）**
- 未跑任何 `test`/`verify`/`package`/`spotless:apply`（按任务书只过 `compile`）⇒ **google-java-format 折行、Checkstyle、SpotBugs 均未跑**；新文件的行宽/折行按仓内风格手写。
- `MarketReadout`（GUI 读口）与 GM 窄工具 `simos.gm.govMarketMandate` 的**运行时行为未跑**（探针走的是命令面 + 日结算；两者编译通过、`Shell.start` 成功构造了工具面）。
- 「无授权时读口显示的订单 == 结算会下的订单」只做了**同源装配**（同一 `GovernmentMarketMandatePlan.of`），未做逐值对照。
- 未跑多日/多年的真档（`v17levant`）—— 探针只跑 `small-world` 30 天 + 授权场景 10 天。
- **已知边界（不是缺陷，是边界）**：任何**新的** `MarketRound` 构造点若不调用 `withGovMandates(...)`，其世界里的国库户会退回"普通参与者"（= 自动清仓）。生产只有两个构造点（`EconomySettlement` / `MarketReadout`），**都已注入**；`EconomySettlement` 另有防复发守卫挡住"字段被 withX 丢掉"这一种。

**BLOCKED**：无。（C9 的处置已实现并实测，但按 §8.2 **请控制方复核**；若裁定不同，改动点是 `unitExclusionsMinusGovernmentTreasuries` 一处。）

---

## 10. 改动文件清单（21 个：15 改 + 6 新增；**无测试、无 pom、无 docs、无 social/gov/map**）

**simos-economy-api（2 新增）**
- `…/economy/api/market/MarketMandateId.java`、`…/economy/api/market/GovernmentMarketMandate.java`

**simos-economy（5 新增 + 10 改）**
- 新增：`…/economy/time/GovernmentMarketMandatePlan.java`、`…/economy/spi/EconomyAuthorizeGovMarketOrderHandler.java`、`…/economy/spi/EconomyCancelGovMarketOrderHandler.java`
- 改：`EconomyData.java`（第 39 组件 + 构造期三守卫 + `withGovMarketMandates`）、`change/EconomyChangeSet.java`、`codec/EconomyCodec.java`、`time/EconomyStateBuilder.java`、`time/EconomySettlement.java`（撤销合并/分类/注入/生命周期/两道防复发守卫）、`time/MarketSettlement.java`（只按授权分支/信用与克隆守卫/成交归属）、`time/MarketReadout.java`、`time/FxSettlement.java`、`time/EconomyDayStepper.java`（类注）、`spi/EconomySeedHandler.java`、`spi/EconomyClearRegionHandler.java`

**simos-app（1 新增 + 4 改）**
- 新增：`…/app/tools/write/GmGovMarketMandateTool.java`
- 改：`Shell.java`（注册两条 handler）、`tools/SimosToolSource.java`（注册窄工具）、`tools/read/CatalogTool.java`（两条 `PAYLOAD_HINTS`）、`time/PopulationEconomyTimeParticipant.java`（类注）

**账本**：`.superpowers/sdd/2026-10-09-gov-household-market-participant/impl-ledger.md`（本文件）

**探针（不在仓库、不进 `src/test`）**：`/tmp/r1/probe/…/R1Probe.java`（全场景）与 `/tmp/r1/probe-base/…/R1BaselineProbe.java`（基线对照）；
运行记录 `/tmp/r1/{final.log,base.log,run*.log,cp2.txt}`、探针源 `/tmp/r1/probe*/`、store `/tmp/r1/store-*`。

## 11. 任务书外多跑的一条 Maven 目标（如实记）

为给探针装配**运行时** classpath，跑过一次 `tools/mvn-lock.sh -o -q dependency:build-classpath -pl simos-app -Dmdep.outputFile=/tmp/r1/cp-app.txt -Dmdep.includeScope=runtime`
（只写 `/tmp` 一份 classpath 文本，**不编译、不测试、不动仓库产物**）。★ 直接原因是"把 `~/.m2` 全部 jar 拼进 classpath"会让 SLF4J 绑到 `slf4j-simple`、日志全哑（探针第一轮实测）——而日志是本批 R1-a/R1-d 证据的载体。此外按任务书建议用了一次 `git worktree add --detach /tmp/r1/baseline HEAD`（基线对照）并在用完后 `git worktree remove`。
