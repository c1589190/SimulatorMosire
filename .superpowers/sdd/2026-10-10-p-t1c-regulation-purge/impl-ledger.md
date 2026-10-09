# P-T1c 实现架构账本：`MarketRegulation` 大删 + 走私死代码删除

- 责任区：P-T1c（纯删减 + 读出/读口跟进；**不搬钱、不加新机制**）
- 权威设计书：`docs/superpowers/specs/2026-10-09-port-policy-and-zone-efficiency-design.md` §15 / §16 / §11.2 第 4 条；
  `docs/superpowers/plans/2026-10-10-market-port-fx-merchant-plan.md` §2.2 / §4；`AGENTS.md` §一.10 / §一.11
- 起点：`8b728a82`（工作树干净）；责任人：P-T1c 写码 Agent
- 纪律：只写生产代码、只到编译过；不写/不改测试；不跑 `test`/`verify`；不 `git commit`

---

## 1. 关键调查结论（`file:line` 证据 → 结论 → 影响）

| # | 证据（实测，2026-10-10 于 `8b728a82`） | 结论 | 影响 |
|---|---|---|---|
| 1 | `MarketRegulation` 7 项条目里，`referencePrices` / `quotaPerWindow` / `bidPerMille` / `askPerMille` / `open` / `rules` 在 `*/src/main` 的**构造点只有两处**：`EconomySettlement:1757` `MarketRegulation.defaultsFor(markets)`、`MarketReadout:189` `MarketRegulation.none()`；`simos-app`/`simos-gov` 的 `src/main` 对 `MarketRegulation` **零命中** | 生产世界里 `regulation` **恒为缺省实例**（空表 / 0 / `open=true` / 空标签） | 删掉这六项在**生产路径上逐值不变**（不只是"缺省中性"，是"从来如此"） |
| 2 | `MarketRegulation.bidPriceOf`/`askPriceOf` 在 `referencePrices` 空、`bid/askPerMille=0` 时与 `Market.bidPriceOf`/`askPriceOf` **逐行等价**（`MarketRegulation:142-169` vs `Market:104-131`：同样 `hasPrice` 早退、`price<=0 ⇒ 0`、`max(1, p*rate/1000)`；ask 同样 `max(bid+1, ⌈p*rate/1000⌉)`） | 删掉区级限价覆盖后回落到 `Market` 的两个常量是**同一算式** | I-C2 成立：逐格价表的 `BID/ASK_PER_MILLE` 与 `bidPriceOf/askPriceOf` 原样保留（用户点名要求） |
| 3 | `quotaRemaining(...)` 在无配额表时恒返回 `Long.MAX_VALUE`；`consumeQuota`/`markQuotaExhausted` 在无表时**立即 return**；`quotaExhausted` 恒 false（`MarketSettlement:7444-7493` 删除前原文） | 配额的全部**消费点**（`min(..., quota)`、`if (quotaLeft<=0) break`、`IllegalStateException` 封顶守卫、`REGULATION_QUOTA` 归因）在"无配额"下**全是不触发的分支** | 删掉整条配额机制（表 + 扣减 + 归因档）在缺省世界里逐值不变 |
| 4 | `ctx.regulation.open()` 的**唯一读者**是 `MarketSettlement:1274`（`if (!ctx.regulation.open()) return empty(...)`） | 删 `open` 必须同步删这个读者；否则留下"没人读的字段" | 该分支在 `open=true` 下从不进入 ⇒ 删掉后逐值不变 |
| 5 | `rules`（`List<String>`）在 `*/src/main` 的**唯一出现处 = `MarketRegulation` 自身**（其余 `.rules()` 命中全是 `CompensationRule`/`MilitaryPayReport` 等别的类型） | `rules` 是零读者字段 | 整体删，不留"只读标签" |
| 6 | `PortRegimeAggregation.split` / `SmugglingSplit` / `SEIZURE_BASELINE_MILLI` 在 `*/src/main` **零读者**（`PortRegimeAggregation:45-47` 自己的注释已记载"删除留给 P-T1c"） | 设计书 §11.2 第 4 条的"实测零读者"复核通过 | 三样整体删；连带删掉只为它们存在的 `CommodityFreightBase` import |
| 7 | ★ **用户任务书给的行号已漂移**：任务书说配额消费点在 `MarketSettlement` 约 `:7092-7096`、`:6991` 注释，闭市在 `:1274`，`planOrders` 在 `:1114-1140`；实测配额消费点在 **`:2671-2678 / :2723-2730 / :2755-2760 / :2865-2870 / :4040-4045 / :4664-4672 / :5935-5938`**，闭市在 `:1274`（未漂） | 设计书 §16.5 末尾自己预告过"`MarketRegulation` 大删落地后 `planOrders` 段行号会整体位移" | **按符号清单逐点清，不按行号**；行号差异如实记（见 §4 偏离记录） |
| 8 | `MarketUnfilledReason.REGULATION_QUOTA` 在 `*/src/main` 只由删除前的 `collectUnfilled` 产生，且 `MarketUnfilledReason` **不进 Codec/ChangeSet**（`grep` 零命中）、`src/main` 无 `values()`/`all()` 消费 | 配额删掉后该档**永远不可能被产生** ⇒ 是死枚举值 | 一并删（§一.11：旧设计类型直接删，不留只留痕的档） |
| 9 | `MarketSettlement.MatchContext.regulationByRegionId()`（无参访问器）在 `*/src/main` 零读者 | 死访问器 | 删 |

## 2. 实现架构（改后的形状与数据流）

**`MarketRegulation`（2 字段）**：`(HexCoord anchor, Map<CommodityId, Long> tariffPerUnit)`；工厂收敛为
`defaults(anchor)` / `defaultsFor(markets)` / `none()`；`defined()` 语义收窄为 `!tariffPerUnit.isEmpty()`。
`anchor` **保留**：它现在只服务一件事——把"区内税"归属到某个区（`MatchContext.regulationByRegionId`）。
保序/冻结纪律不变：`freeze(...)` 仍走 `LinkedHashMap` + `Collections.unmodifiableMap`（**未用** `Map.copyOf`）。

**定价数据流（改后，唯一一条）**：`Market.prices()` → `Market.hasPrice/priceOf/bidPriceOf/askPriceOf` →
`ordersFor(...)` → `PlanOrders`。区级参考价这一层**从数据流里消失**，`ordersFor` 不再接 `regulation`/`regulated`
两个参数，`planOrders` 从"4 参 + 5 参 + 7 参三重载"收敛为"4 参 + 5 参"。

**配额数据流**：整条撤除 —— `MatchContext.quotas` / `regulationQuotaExhausted` / `configureQuotas()` /
`absorbQuotas()` 及 7 个消费点全删；`RegionClone` 回放不再有"配额并回"这一步（区内撮合本来就走账号因果链回放）。

**税费数据流（本批不动）**：`regulationFor(regionId).tariffPerUnit()` → `tariffPerUnitOf` → `tariffByFill` →
`MarketReport.withRegulatedTariff`。仍是"只记读数、不搬钱"（真收款是 P-T1b）。

## 3. 关键判断

1. **为什么不保留 `defined()` 的旧宽度**（旧：任一条目非缺省即 true）。旧宽度唯一的读者是"要不要查区级覆盖"与
   "要不要建配额表"——两者都已删。剩下的读者只有 `tariffPerUnitOf`，对它而言"无税"与"未定义"同义 ⇒ 收窄成
   `!tariffPerUnit.isEmpty()` 不改变任何读数。P-T1d 加采购优先级参数时，`defined()` 需要重新审宽度（**已记入 §7 未完成项**）。
2. **为什么 `anchor` 不删**：任务书写"`anchor` 若删掉上面各项后无人读，一并删"。实测删完后它**仍有读者**
   ——`MatchContext` 构造器拿 `regulation.anchor()` 定位锚区、`regionIdAt(topology, anchor)` 求区 id、
   再由 `regulationByRegionId` 支撑 `tariffPerUnitOf`。税是"按区施加一次"的，没有区归属就无处落地 ⇒ **保留**。
3. **为什么删 `REGULATION_QUOTA` 枚举档而不是留成"永不产生"**：`MarketUnfilledReason` 不进 Codec/ChangeSet、
   无 `values()` 消费、无 `switch` 穷举 ⇒ 它只是报告里的一个档；留着就是"只留痕的结构"，正是 §一.11 要删的那类。
4. **为什么不写任何兼容位/归一/INFO**：§一.11 用户原话"旧设计和数据类型直接重建不用留，删就删了"。
   `MarketRegulation` 是纯值类型、不落盘（`MarketSettlement` 类注与 `MarketRound.regulation` 字段注均写明
   "逐轮瞬态、不进 EconomyData/Codec/ChangeSet"）⇒ 连"旧档读不进来"这个问题都不存在。
5. **`MatchContext.regulationFor(regionId)` 为什么保留**：它是"区 → 该区税表"的唯一查表点，被
   `tariffPerUnitOf` 与 worker 副本（`MarketSettlement:3499` 把本区规则传给 `RegionClone` 的 `MatchContext`）读。
   删了会让 worker 副本丢掉本区税 ⇒ 读数漂开。
6. **`matchGroup` 里 `sells` 参数为什么不动**：删掉 `commodityOf(buys, sells)` 后 `sells` 仍是
   `ordered = new ArrayList<>(sells)` 的来源（成本排序用），签名原样保留。

## 4. 偏离记录（与约束设计书/任务书不一致处，主动记）

| # | 差异 | 原因 |
|---|---|---|
| 1 | 任务书的行号（`:7092-7096`、`:6991`、`:1114-1140`）与实测不符 | 行号漂移（设计书 §16.5 已预告）；**按符号清单逐点清**，清完后 `grep` 证明归零（见报告 ④） |
| 2 | 额外删了 `MarketUnfilledReason.REGULATION_QUOTA` 与 `MatchContext.regulationByRegionId()` | 两者在删完配额后是**不可达/零读者**；留着违反"不留没人读的字段"。见 §3 判断 3 |
| 3 | 额外改了 4 处**注释/javadoc**（`HexTradeCost:16-17`、`MarketSettlement` 的 `regionIdAt`/`readOnlyPlanningRound`/口岸预算类比段、`EconomySettlement:1755`） | 原文写着"参考价/限价、配额、开闭市"与 `MatchContext.quotas`，删完后是**指向已删机制的错描述**；按 §四"机制性描述一律回代码核"更正 |
| 4 | `MarketRegulation` javadoc 里**保留了被删字段的名字**（逐条写明"删 + 用户原话依据"） | 这是**设计留痕**（解释为什么这个 record 只剩两个字段），不是旧类型驻留；与 `PortRegimeAggregation:45` 原有写法同制 |
| 5 | 未新增任何"采购优先级参数位" | 任务书 §A.7 明写"+ 后续批要加的采购优先级参数位"，但**不做**清单又明写"采购优先级（P-T1d）"不在本批 ⇒ 只留 javadoc 指向 §16.3 |

## 5. 会改变数值行为的清单（给测试 Agent）

**生产路径（`*/src/main`）：零。** 论证见 §1 第 1~4 条——生产世界里 `regulation` 恒为缺省实例，
被删的每条分支在缺省值下都不触发；`bid/ask` 回落是同一算式。

**非缺省路径（改前能配、改后配不了）**：
- 区级参考价覆盖（`referencePrices`）：改前可让某区按覆盖价成交，改后**只能按逐格价表**；
- 区级限价覆盖（`bidPerMille`/`askPerMille`）：同上，改后只能用 `Market.BID_PER_MILLE`/`ASK_PER_MILLE`；
- 关市（`open=false`）：改前该区本轮返回空报告，改后**无此能力**（关市退役，属 `MarketTrigger` 层）；
- 配额（`quotaPerWindow`）：改前该（区, 商品）卖方成交量有上限、超出部分归因 `REGULATION_QUOTA`，改后无上限、该归因档不存在；
- 制度标签（`rules`）：纯只读，无数值行为。

**不受影响**：`tariffPerUnit`（税费读数）、`Market.prices`、逐格限价常量、口岸节流（P-T1a）、三层税（未做）。

## 6. 会让既有测试失效的清单（**不自己改**，交测试 Agent）

- ★ **编译期失败（1 个文件，唯一）**：`simos-economy/src/test/java/io/mosire/simos/economy/time/MarketRegulationTest.java`
  —— 8 参 `new MarketRegulation(anchor, refPrices, bid, ask, quota, tariff, open, rules)`（`:97/:125/:132/:141/:148/:162/:226/:261/:300`）、
  `regulation.bidPerMille()/askPerMille()/open()/rules()/referencePrices()/quotaPerWindow()`（`:52-58`）、
  `referencePriceOf/bidPriceOf/askPriceOf`（`:60-66/:99-115`）、`MarketUnfilledReason.REGULATION_QUOTA`（`:275/:280`）。
  ⇒ 该文件整体测的是**已被删掉的机制**，按 §一.11 第 3 条**直接删**（新测试按新验收判据另写）。
- **仍可编译、且按 §1 的缺省中性论证应仍绿**（未跑，如实记）：
  `MarketSettlementFixtures`（只透传 `MarketRegulation` 类型）、`MarketSettlementSingleHexLossTest`、
  `Z7MarketExclusionTest`（走 4 参 `planOrders`）。
- **零命中**：`SmugglingSplit` / `SEIZURE_BASELINE_MILLI` / `PortRegimeAggregation.split` 在 `*/src/test` 全为 0。
- ★ **本 Agent 未跑 `test`/`verify`**（纪律），故"仍绿"是**推断不是实测**；真数以测试 Agent 轮为准。

## 7. 未完成 / 未验证项

1. **未跑测试**（纪律要求）：`compile` 绿 ≠ 行为对；§6 的"仍绿"是静态推断。
2. **`defined()` 宽度待 P-T1d 复审**：加"采购优先级"参数后 `defined()` 必须重新定义（否则优先级参数会被
   当成"未定义"而静默失效）。留给 P-T1d。
3. **不做**：三层税真收款（P-T1b）、采购优先级（P-T1d）、订单可选币（3c）、FX（P-T5）—— 按任务书"不做"清单。
4. **未做真实 world 360 tick 读数**（任务书只要求编译过；真实行为验证是后续批次/测试 Agent 的事）。
5. **SpotBugs/Checkstyle 未跑**（属 `verify`，纪律禁止）：本批新增了"删除后可能变 unused 的私有成员"，
   已逐一人工核对（`grep` 零读者才删），但未经静态分析器复核。

## 8. 门禁证据（真实命令与结果，2026-10-10 05:05，工作树 `8b728a82` + 本批改动）

```
$ tools/mvn-lock.sh -q spotless:apply            → [spotless exit=0]
$ tools/mvn-lock.sh -DskipTests compile          → BUILD SUCCESS，16/16 模块 SUCCESS [compile exit=0]
    SimulatorMosire/Util/Map/Calendar/SocialApi/ActorApi/EconomyApi/Social/Unit/Core/SD/Actor/Economy/Gov/Army/App
```

**引用清零（作用域 `*/src/main`，排除 `MarketRegulation.java` 的删除留痕 javadoc）—— 逐项 0 命中**：
`referencePrices` / `quotaPerWindow` / `referencePriceOf` / `REGULATION_QUOTA` / `SmugglingSplit` /
`SEIZURE_BASELINE_MILLI` / `PortRegimeAggregation.split` / `regulation.open()` / `regulation.rules()` /
`regulatedBid|regulatedAsk|regulatedReference` /
`quotaRemaining|consumeQuota|markQuotaExhausted|quotaExhausted|quotaConfigured|absorbQuotas|configureQuotas|regulationQuotaExhausted`。

**保留项自证（未删过头）**：`Market.java:138/145` 的 `BID_PER_MILLE=990`/`ASK_PER_MILLE=1010` 与
`Market.java:104-131` 的 `bidPriceOf/askPriceOf` 原样在位（`*/src/main` 命中 13/12 处）；
`tariffPerUnit` 16 处、`MarketRegulation` 35 处、`PortRegimeAggregation` 14 处、`MarketUnfilledReason` 114 处。

**改动规模**：7 文件，`+79 / -489`（`git diff --stat`）。

**生产路径逐值不变的静态证明（I-C2）**：`*/src/main` 里 `MarketRegulation` 的构造点只有
`EconomySettlement:1757 defaultsFor(markets)` 与 `MarketReadout:189 none()`（`simos-app`/`simos-gov` 的
`src/main` 对 `MarketRegulation` 零命中）⇒ `regulation` 恒为缺省实例（空参考价 / 空配额 / bid=ask=0 /
`open=true` / 空标签 / 空税表）⇒ 被删的每条分支在该取值下都不触发；`bid/ask` 的回落是同一算式（§1 第 2 条）。
