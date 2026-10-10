# T-fix 实现架构账本：烧工具绕过唯一写口 + 没让路冻结（真缺陷）

> 责任区：**T-fix**（测试批抓到，6 个错误同根因）。
> 权威来源 = 控制方裁定（转述在派单书里，本文件 **§4 偏离记录** 记与它不一致之处）。
> 前置批 = `.superpowers/sdd/2026-10-10-m-c-merchant-profit/impl-ledger.md`（M-C 跑商门槛）。
> ★ 本文件是实现方自己写的**实现架构账本**（AGENTS §一.8），只记决策相关事实，不抄工具输出。

## 1. 关键调查结论（`file:line` 证据 → 结论 → 影响）

| # | 证据 | 结论 | 影响 |
|---|---|---|---|
| S-1 | 改前 `MarketSettlement.java:6971-6982`：`stock = householdStockOf(...)`；`consumed = min(stock, cost)`；`setHouseholdStock(stock - consumed)`；`round.ledger.addLoss(...)` | 烧工具腿**直改会话账**、且实扣上限**只看 `stock`、不减冻结** | 冻结（同轮该户 `tool` 卖单承诺）被烧掉 ⇒ 同轮该户卖单成交时 `EconomySettlement.java:8438` 判 `stock - 扣减 < frozen` ⇒ **硬抛**（`:8440` 文案"转移会花掉家户账上已冻结的商品"）；6 个测试错误同此根因 |
| S-2 | `MarketSettlement.java:2745-2758`（`refreshSellFrozen/releaseSellFrozenSum`）+ `setFrozenGoods` 写在 `MarketRound.householdFrozenGoods` | 冻结表是**每轮活表**、随成交释放；`settleHaulRuns` 逐条重读它即得到当前承诺量 | 不必新增任何状态：`max(0, stock − frozen)` 当场可算（与 `MarketSettlement.java:8593 spendableGoodsOf`、`:2082`、`:9006` 同一条既存算式） |
| S-3 | `SellOrder` 生成处 `MarketSettlement.java:2082-2096`：`available = max(0, stock − frozen)`、`sellable = max(0, stock − frozen − necessary − retention)` | 订单生成时恒有 `frozen_total ≤ stock`；每次成交把 `stock` 与 `frozen` **同减** ⇒ 该不变式由成交保持 | ⇒ 只要烧工具**不越过 `available`**，不变式 `stock ≥ frozen` 就不被打破 ⇒ S-1 的硬抛不可达（本 fix 的充分性论证） |
| S-4 | `Transfer`（economy-api）不变式 `from != to` + `EconomySettlement.java:8352 requireHouseholdOf`（键集 = `SettlementIndex.java:599-605 householdByActor`，只含 `HouseholdActors.of(家户)`）+ `TransferReason` 词表（**实测 12 档**，全是两端之间的流动，**没有"损耗/烧毁"档**） | **纯损耗无法表达为一次 `applyTransfer`**：它没有对端；硬塞接收方 = 伪造一笔转移（接收方凭空多出货） | 控制方裁定 1「烧工具腿必须走 `applyTransfer`」**在结构上不可实现**；按 spec §3.2「守恒实现口径（唯一写口 + 非换手落点）」落在**非换手**那一半 ⇒ 见 §4 D-1 |
| S-5 | 既存非换手落点：`EconomySettlement.java:8596 consumeFromHousehold`（消费/投入扣减）、`:9352 setStock`；`MarketSettlement.java:7158 deductBuyerLossNoTransfer` + `ledger.addLoss`（D-027 货损，spec `2026-10-06-single-market-region-cycle.md:79-82` 明文裁定这一族合法） | "货物离开账户但不换手"本仓有既存纪律，且**写口类（`EconomySettlement`）才是它该在的地方**（`consumeFromHousehold`/`drawCycleInputs` 都在那儿） | 新写口落在 `EconomySettlement`（不是市场轮），顺带把市场轮那条旁路整条删掉 |
| S-6 | `MerchantCapacityPool.java:528`（装配时点 `item.remainingToolMilli` = 该户 `tool` 存量，够一趟才产生条目）+ `:612 reasons.add("tool-short")` | 门槛判在**装配/分配**那一刻；成交点只是"实扣"落点，且 `tool-short` 字面量此前有**两处**拼写 | 本 fix 只动**成交点**的实扣（门槛语义不碰，见 §4 D-3）；`tool-short` 收成 `MerchantHaul` 唯一拼写点 |
| S-7 | `EconomySettlement.java:9352-9364 setStock`：`amount ≤ 0 ⇒ 去键`（"不落一个值为 0 的假键"）；`MarketSettlement.java:7184 setHouseholdStock`：`max(0, value)` 后**总是 put** | 改前烧到 0 会留一个 `tool: 0` 的**假键**，而 `applyTransfer` 走 `setStock`（去键）⇒ 同一本账两种形态 | 本 fix 后烧工具与 `applyTransfer` **同形**（去键）；见 §3 的数值行为清单第 3 条 |

## 2. 实现架构（落点 / 写口 / 调用次序）

```
MarketSettlement.settleHaulRuns（:6977-7016）
  └─ EconomySettlement.consumeForLoss(householdGoods, householdFrozenGoods, ledger,
                                       lossAccount, household, commodity, requestedMilli)   ← :8627
       ├─ stock  = stockOf(...)                    （改前市场轮自己算的那两个数，改由写口算）
       ├─ frozen = frozenGoodsOf(...)
       ├─ available = max(0, stock − frozen)  <  requested  ⇒ 返回 LossConsumption(…, 0)   ← :8646-8648
       │                                                      两本账**一字未动**（fail-closed，绝不部分扣）
       └─ 否则 setStock(stock − requested)  +  ledger.addLoss(lossAccount, commodity, requested)
                                                          ← :8649-8651（账户减 + 损耗账加**同址**，只减不记不可达）
```

- **具名归因**：`MerchantHaul.TOOL_FROZEN_REASON = "tool-frozen"`（:86）、`TOOL_SHORT_REASON = "tool-short"`（:77）、
  派生 `MerchantHaul.blockedReason(stockMilli, neededMilli)`（:114）。判据是纯函数 ⇒ I7 安全。
- **日志**：`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT`（事件名不变，级别不变 = DEBUG，与改前同级；块 = `MarketSettlement:6998-7016`）
  字段加 `frozenMilli` / `availableMilli`，`reason` 改为两个具名值之一（改前是笼统的 `tool-sold-out-same-round`）。
- **零新增状态**：无 `EconomyData` / ChangeSet / Codec / 命令面 / GM 工具改动（与 M-C 的 Q-23 同款）。

## 3. 会改变数值行为 / 账形态的清单

| # | 变化 | 缺省中性 |
|---|---|---|
| 1 | ★ **实扣上限加上冻结让路**：`min(stock, 一次跑商)` ⇒ `available ≥ 一次跑商 ? 一次跑商 : 0`。同轮该户 `tool` 卖单承诺（`householdFrozenGoods`）不再被烧 | 无跑商家户 ⇒ `settleHaulRuns` 根本不调用（`allocation == null`）⇒ 逐值不变；有跑商家户且 `frozen == 0` 且 `stock ≥ 1000` ⇒ **逐值同改前** |
| 2 | **不再部分扣**：改前 `consumed = min(stock, 1000)`（可能 0 < consumed < 1000），改后 `consumed ∈ {0, 1000}` | 同上：够则全额、不够则 0（改前是"扣到 0 为止并具名"） |
| 3 | **烧到恰好 0 时的账形态**：不再留 `tool: 0` 假键（`consumeForLoss` 走 `setStock`，与 `applyTransfer` 同形；改前走市场轮自己的 `setHouseholdStock`，会留 0 键） | 只在 `frozen == 0 ∧ stock == 1000` 的精确情形可见；读口两处都是 `getOrDefault(..., 0)` ⇒ 读数不变，只有内层表的**键集**形态变 |
| 4 | 日志：`MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 多 `frozenMilli`/`availableMilli`，`reason` 由 `tool-sold-out-same-round` 改为 `tool-frozen`/`tool-short` | 不触发时不打（与改前同）；日志不改任何状态/公式 |
| 5 | `MerchantCapacityPool.laneBlockedReason` 的 `"tool-short"` 字面量改引用常量 | **逐值相同**（同字符串），只是消掉第二处拼写点 |
| 6 | 利润读数 `recordRun(..., consumed, ...)`：`consumed` 现在可能是 `0`（该次跑商不成立）⇒ `runs` 仍计数、`toolBurnMilli` 少加那一笔 | 读数算式与调用形状**未动**（裁定 5）；`runs` 记的是"承运确实发生了"这个事实（货已运走），`toolBurnMilli` 记的是"门槛没付成" |

## 4. 偏离记录（与派单书冻结口径不一致处，主动记录）

| # | 偏离 | 原因 |
|---|---|---|
| **D-1** | ★★ 裁定 1 原文「烧工具腿必须走唯一写口 **`EconomySettlement.applyTransfer`**」**未照字面实现**：改走 `EconomySettlement.consumeForLoss`（同类的**新增**非换手损耗写口） | **结构上不可实现**，证据见 S-4：① `Transfer` 构造不变式 `from != to`；② 两端都必须能被 `requireHouseholdOf` 解析到**现存家户行**；③ `TransferReason` 11 档全是两端流动、无"损耗"档；④ 世界里的 actor 全是家户 actor（`SettlementIndex.householdByActor` 键集 = 家户行）。而损耗**没有对端** —— 硬塞接收方 = 伪造转移（接收方凭空多出货、账面看不出破绽），与裁定 4「Σ余额+losses 守恒 / 只进 `losses[market-merchant-haul]`」直接冲突。⇒ 按 spec §3.2 的「唯一写口 + **非换手落点**」取非换手那一半，并把这类落点**收成一个口**放进写口类（改前是市场轮自己直改）。★ 裁定 1 的**目的**（不绕写口、冻结让路）逐条达成：市场轮里 `setHouseholdStock`/`ledger.addLoss` 的裸调用已删（`grep` 见 §5）。**待控制方裁定**：若必须出现 `applyTransfer` 这个名字，可选做法 = 给它加一个"损耗汇"重载（`to` 是损耗账而不是 actor）——那是**扩 `applyTransfer` 语义**，本实现认为会在"转移 = A→B 事实"上开一个口子，故未擅自做。 |
| D-2 | 归因判据取 `stock ≥ needed ⇒ tool-frozen`，而不是"看 `frozen > 0`" | 两者等价且前者更强：`available < needed ≤ stock` ⇒ `frozen > 0` 必然成立；反之 `stock < needed` 时即使 `frozen == 0` 也不成立 ⇒ 约束是实物存量（真缺货）。⇒ 二值互斥、可判 |
| D-3 | **装配/分配期的门槛（`MerchantCapacityPool.select` 的工具预算）未做冻结让路**，只有成交点做 | ① 裁定 5 明写"门槛…不动"；② `MerchantCapacityPool` 装配在 `EconomySettlement` 侧、手里**没有**冻结表（冻结表是市场轮 `MarketRound.householdFrozenGoods`）⇒ 让它读冻结要改装配契约（跨批次改动面）。**具名边界后果**：冻结导致成交点不成立时，货已运走且运费腿已铸 ⇒ 该次承运"没付门槛却收了运费"，只在 DEBUG 归因里可见。 |
| D-4 | 归因日志**保持 DEBUG**（未升 INFO） | 与改前同级（同一事件名 `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` 本来就是 DEBUG）；AGENTS §一.9 对"逐笔"一档的旧口径是 TRACE/DEBUG。★ **待控制方裁定**：2026-10-23 的"业务拒绝 = INFO"若适用到逐笔跑商门槛，需要另开一条聚合 INFO（本批未做，避免动 M-C 的轮末汇总 = 裁定 5 的"利润读数不动"）。 |

## 5. 实施记录

### 5.1 改动文件（4 个，全在允许范围 `simos-economy/src/main/**`）

| 文件 | 处置 |
|---|---|
| `time/EconomySettlement.java` | **新增** `consumeForLoss`（非换手损耗唯一写口，`:8627`）+ 嵌套 `record LossConsumption`（`:8663`） |
| `time/MarketSettlement.java` | `settleHaulRuns` 的烧工具腿改走写口（`:6977-7016`）；删掉 `setHouseholdStock` 直改与 `ledger.addLoss` 裸调用；方法注/类注同步 |
| `time/MerchantHaul.java` | 新增 `TOOL_SHORT_REASON`/`TOOL_FROZEN_REASON`/`blockedReason`；类注加 ⑤（T-fix 语义） |
| `time/MerchantCapacityPool.java` | `:612` 字面量 → `MerchantHaul.TOOL_SHORT_REASON`（消第二处拼写点，逐值相同） |

### 5.2 命令与结果（两条，本仓锁；只到编译过 —— 派单纪律：不跑 test/verify）

```
$ tools/mvn-lock.sh -q spotless:apply                                  → [exit=0]
$ tools/mvn-lock.sh -DskipTests compile                                → [exit=0]
  Reactor 16 模块全 SUCCESS（EconomySimos 3.682s / SimosApp 2.967s，总 11.250s）
  ★ 编译真发生了（增量编译核对）：4 个 .class 的 mtime = 08:09:34~35，均**晚于**对应源文件 08:09:00~21
```

★ 定稿前又做了一处小改（`burn.blocked()` 进 else-if，消掉新 record 的死方法）⇒ **两条命令各重跑一次**：

```
$ tools/mvn-lock.sh -q spotless:apply                                  → [exit=0]
$ tools/mvn-lock.sh -DskipTests compile                                → [exit=0]
  Compiling 199 source files（EconomySimos，全量重编）+ 328（SimosApp）；BUILD SUCCESS；EconomySimos 4.463s / SimosApp 3.004s
```

### 5.3 静态自审（未跑测试，用 `grep` 核的）

```
$ grep -rn "TOOL_BURN_ACCOUNT" simos-economy/src/main   ⇒ 唯一实参点 = MarketSettlement:6982（传进写口）
$ sed -n '/private static void settleHaulRuns/,/^  }/p' MarketSettlement.java | grep -E "setHouseholdStock|addLoss|householdStockOf"
                                                       ⇒ 0 命中（裸写口彻底删除）
$ grep -rn '"tool-short"\|"tool-frozen"' simos-economy/src/main ⇒ 只有 MerchantHaul 的两处常量定义
$ git status --porcelain                                ⇒ 4 个已改文件，无 src/test/**、无 pom.xml、无 docs/**
```

### 5.4 守恒与 fail-closed 的落点（一行）

- **守恒**：`EconomySettlement.java:8649-8651` —— `setStock(stock − requested)` 与
  `ledger.addLoss(lossAccount, commodity, requested)` 在**同一个方法体内相邻两行**完成（拿不到"只减不记"的中间态）
  ⇒ Σ余额 + losses 逐值守恒；`losses[market-merchant-haul]` 仍是唯一凭据（`MerchantHaul.TOOL_BURN_ACCOUNT`）。
- **fail-closed**：`EconomySettlement.java:8646-8648` —— `max(0, stock − frozen) < requested ⇒ consumed = 0`、两本账未动；
  具名归因 `MarketSettlement.java:7000-7016`（DEBUG）+ `MerchantHaul.java:114`（`tool-frozen` / `tool-short` 二选一）。

## 6. 未完成 / 未验证（如实记）

- **未跑任何测试**（派单纪律：测试轮归控制方）⇒ 6 个测试错误是否"当场转绿"**未实测**；§1 S-3 的充分性论证是**推理**（依据：订单生成的 `sellable ≤ stock − frozen` 与成交期 `stock`/`frozen` 同减）。
- **未跑真档长跑** ⇒ 冻结让路在真世界里的发生频率（`tool-frozen` 打多少次）、以及"门槛没付成却收了运费"（D-3）的实际金额**均未测量**。
- **待控制方裁定 2 条**：D-1（`applyTransfer` 字面 vs 非换手写口）、D-4（逐笔门槛归因的日志级别）。
- **未做**（按冻结范围）：装配期门槛的冻结让路（D-3）、工具补货路径（M-C §6 的既有缺口）、`runs` 是否该在门槛未付成时计数（裁定 5 说不改读数）。
- ★ **会让既有测试失效的静态判断**（未跑，供测试轮参考）：夹具里的承运户是 `dormant`（0 人口、空商品账 + 只给 tool）
  ⇒ **不生成卖单 ⇒ `frozen == 0`** ⇒ `M-A1/M-A2/M-C` 那批夹具用例（`MerchantCapacityAcceptanceTest`、`PortGateTaxAcceptanceTest`、
  `MarketSettlementSingleHexLossTest`）的烧工具量**逐值不变**；唯一形态差异是 §3 第 3 条（烧到恰好 0 时不留 `tool: 0` 假键），
  现有断言不查内层键集。
