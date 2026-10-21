# D-032 RF1 任务书：家户需求价格接受者 + 同键自动订单替换

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3–§4
> **父目标设计：** `docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md` S7 / §5
> **状态：** 可派实现；本批只写生产代码到编译过，不写/不跑测试。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **禁止：** 主树、GUI/服务、commit/push、workflow、测试文件、`EconomyData`/ChangeSet/Codec、production 定义。

## 1. 用户原话（逐字）

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```

## 2. 问题现场（已核实）

1. `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketIntentBook.java:80-148`
   - 先算需求缺口 `naturalNeeds×5 - onHand`；
   - 再用 `remainingBudget`、`market.askPriceOf`、`maxSpend=quantity×ask/1000` 截断；
   - `ask=0` ⇒ `maxSpend=0` ⇒ 需求直接不生成。
2. `MarketRoundRunner.java:761-799`
   - 对出现任一 M3 商品意向的 actor，删除该 actor **全部** `ctx.buys`/`ctx.sells` 自动订单；
   - 只补回 M3 显式腿，未补的方向不会恢复。
3. `MarketIntent.java:36-66`
   - BUY/LAND 使用统一的 `limitPrice`、`budgetMilli` 字段，当前语义上允许给需求挂价/预算门。
4. `MarketLandIntentBook.java:242-312`
   - 土地 BUY 也带 `limitPricePerMu`、`budgetMilli`；现金/预算不应成为挂单门。
5. `MarketOrderPlanner.java:298-319`
   - 已有 0 价免费交易路径（`referenceInPay == 0 ⇒ cashAffordable = Long.MAX_VALUE`），但被 M3 的现金门/actor 级替换绕开。

## 3. 目标能力

家户提出的商品/土地**需求**必须：

- 按真实数量全额生成，不由 ask、现金、预算、剩余预算截断；
- 接受市场给出的任意非负价格（价格接受者）；
- 成交时先用现金，现金不足的货款按既有路径成为普通货币债；
- 无供给只产生 `unmet`，不产生债务；
- 与现有商品+土地统一订单簿、同一撮合入口共用，不新增第二订单簿/matcher。

M3 显式意向对自动订单的替换必须按 `(actor, commodity, side)` 精确进行：

- 有 M3 cloth BUY 时，只替换该 actor 的 cloth BUY；
- 同一 actor 的 grain BUY/SELL、cloth SELL 等自动腿必须保留；
- 不得因 actor 级集合删除整个 actor 的订单。

## 4. 约束设计（必须遵守）

### 4.1 形状

- `MarketIntent` 是**瞬态**类型，不得落盘/进 ChangeSet/Codec。
- BUY 不应该把 `limitPrice`/`budgetMilli` 当语义门。允许两种实现路线：
  1. **优先**：拆分/移除 BUY 的价格/预算字段，使“需求挂价”在内部形状上不可表达；
  2. 若 API 的 `BuyOrder`/`Budget` 必须保留边界字段，则只在转换边界写入“无价格上限/无预算上限”的显式 sentinel，并在实现账本说明这是过渡桥，不允许任何上游用它削减数量。
- SELL 侧的最小价/接受币种/实际盈余语义保持不变。
- 土地 BUY 不能因现金/预算不足而少挂；土地 SELL 侧的最低保护价不变。

### 4.2 现金与债务

- 商品成交的现金不足，必须走既有 `recordDeferredPaymentDebt` / 普通货币债路径；
- 土地成交的现金不足，必须走既有 `MarketTradeExecutor.executeLandTrade` 的 deferred 分支；
- 不得为了“让买方买得起”而把需求数量截成现金可买量；
- 不得凭空造币或绕过 `applyTransfer`。

### 4.3 同键替换

替换键至少包含：

```text
(actor, commodity, side)   // 商品
(actor, LAND, hex, side)   // 土地
```

- 对同一键，M3 显式意向替换自动腿；
- 对其它键，自动腿保留；
- 缺方向的订单就是没有该方向订单，但不得扩大成“删除 actor 所有方向”。

### 4.4 日志

新增/修改的路径至少加：

- BUY 生成：`MARKET_INTENT_BUY`（actor、kind、commodity/hex、quantity、priceTaker=true、sourceUnit）；
- 显式替换：`D032_P3_MARKET_INTENT_REPLACED` 现有事件增加 `removedKeys`/`retainedKeys` 或等价具名计数；
- 0 价/现金不足成交：沿用现有 `MARKET_DEFERRED_PAYMENT` / 0 价路径日志；
- 日志失败不得影响结算。

### 4.5 非目标

- 不修生产、种子、土地组织、运力、卖盘盈余、价格发现；
- 不改 `EconomyData`/组件数/Codec/往返不变式；
- 不改工资/债务合同形状；
- 不改测试文件、不跑测试/门禁。

## 5. 允许修改的文件（实现者拥有）

**主要：**

- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketIntent.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketIntentBook.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundRunner.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketLandIntentBook.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketLandOrder.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketMatcher.java`

**仅在编译必需时允许：**

- `simos-economy-api` 的 `BuyOrder` / `Budget` / `SellOrder`（必须先在实现账本报告理由；不得做旧档兼容）；
- 其它 `simos-economy/time` 内最小相邻文件。

**禁止：**

- 测试文件；
- `EconomyData` / `EconomyChangeSet` / Codec；
- `simos-production` / `simos-app` 主代码（除编译必需的极窄接线，需先报告）；
- 主树、GUI、服务、`.m2-repo`。

## 6. 编译与交账

- 只跑：
  ```bash
  tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
  ```
  若修改了 `simos-economy-api`，改为：
  ```bash
  tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy-api,simos-economy -am
  ```
- 不跑 `test` / `verify`；不写/改测试文件；不 commit。
- 报告必须包含：
  1. 改动的文件与关键方法；
  2. 编译命令与真实 rc；
  3. 会改变数值行为的清单；
  4. 受影响的硬编码字面量 / 既有测试断言清单；
  5. 与父计划/本任务书不一致处及原因；
  6. 未完成/未验证项；
  7. 实现架构账本落点：`.superpowers/sdd/2026-10-20-d032-rf1-demand-price-taker/impl-ledger.md`。
- 若发现用户原话/五条铁律/模块边界/权限冲突：**立即停手上报**，不得自裁。

## 7. 验收锚点（测试 owner 后补）

- ask=0 且存在 grain gap ⇒ 仍生成完整数量 BUY；
- 现金=0/预算=0 ⇒ BUY quantity 不变；
- 任意高价格成交 ⇒ 买盘仍进入撮合，现金不足部分成普通债；
- 同一 actor 的 `(cloth, BUY)` M3 intent 只替换 `(cloth, BUY)`，`(grain, BUY)` / `(grain, SELL)` 自动腿保留；
- 无供给 ⇒ `unmet`，不新增债务；
- 0 价成交合法；未定价与 0 价仍区分；
- 商品和土地仍走同一 `MarketRound` / `MarketMatcher.matchAll`。
