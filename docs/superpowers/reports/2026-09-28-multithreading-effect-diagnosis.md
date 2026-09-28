# 多线程优化“效果不明显”的排查报告（2026-09-28）

> **触发**：tick0 实战模拟（R0+R1+R2 + M1–M8 修复后）0→30 天实测 **46.06 s**，与旧基线早期阶段约 49 s/30 天相比几乎无改善。
> **结论（一句话）**：这次运行**根本没有启用多线程**；即使强行启用，R2 也只并行了少数非热点阶段，市场撮合（约占 74%）仍是串行，Amdahl 上限约 1.1–1.35×；同时 32 分区结构在单线程路径上引入了重复索引构建，可能抵消已有收益。

---

## 1. 实测证据

### 1.1 本次运行

| 项 | 值 | 来源 |
|---|---|---|
| 实例 | GUI 5837 / MCP 5735 / approval 5733，participant=3 | `server-tick0-fixed-20260928-210928.log:7` |
| store | `/home/cna/SimosData/simos-dev/tick0-fixed-20260928-210928` | 同上 |
| jar | `simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 `222c2f5af559857c99445f42e14b312b` | `md5sum`；构建日志 21:04/21:09 |
| worldgen | 三国 seed 总耗时 2.2 s；59223 hex / 252 区域 / 799 经济格 / 6000 人口批次 / 6392 家户行 | `worldgen-init.log`、`tick0-readings.json` |
| 0→30 推进 | `before_head=4 / before_tick=0 → after_head=5 / after_tick=30`，**elapsed 46.06 s** | `advance-log.jsonl`、`advance-30.log` |
| 30→120 | 未完成（`advance-120.log` 空，随后收到停止信号） | `server...log:2424` |
| tick0 读数 | population 11,830,000 = social sum；membership 逐 lot 匹配；allocated ≤ available；money total 142,924,800；debts 0；use_rights 无超容量 | `tick0-readings.json` |

### 1.2 旧基线参照（旧代码，tick360 附近）

- 0→180 推进：**296.1 s**（调查 §1.1）⇒ 平均 **49.35 s / 30 天**；本次 tick0→30 的 46.06 s 与它同量级，**不能证明多线程收益**。
- 360→390（30 天）嵌套：step 140.229 s（其中市场 126.03 s）+ apply 29.147 s = 169.722 s（边界报告 §5.2）。
- 市场轮占 step+apply 的 **74.3%**；`OwnershipBooks.apply` 占 **17.2%**；市场轮内地形索引重建 22.66%、跨区 31.95%、refreshSellFrozen 7.65%（调查 §1.2）。

---

## 2. 根因

### C1（决定性）：app 从未把多线程配置接进运行期

静态证据：

- `PopulationEconomyTimeParticipant.java:172` 调的是 3 参构造 `new EconomyDayStepper(economy, session, topology)`；
- 该 3 参构造 `EconomyDayStepper.java:51-58` 转 5 参构造；
- 5 参构造 `EconomyDayStepper.java:76-88` 转 6 参重载并**写死** `EconomyParallelism.singleThreaded()`；
- 全 `src/main` 对 `EconomyParallelism.of(...)` 的调用 **0 处**（`grep -RIn "EconomyParallelism.of" simos-app/src/main/java simos-economy/src/main/java` 空命中）；
- `ShellConfig`/`ShellMain` 没有线程数开关；
- `Shell` 装配日志只报 `participant=3`，没有任何 worker/thread 配置。

⇒ 这次 46.06 s 是**单线程退化路径**的时间。多线程代码存在，但没有任何生产入口能打开它。

### C2：即使接上线程，市场撮合仍是串行，Amdahl 上限很低

- R2 只在 `MarketSettlement.clearOncePerCycle` 的第 1 步“逐格建参与者 + 生成订单”上按市场区并行（`MarketSettlement.java:469-560`）；
- 第 2~4 步仍是协调器单线程：`commitFreezes(ctx)`、`matchWithinRegions(ctx)`、`matchAcrossRegions(ctx)`、`releaseAllFreezes(ctx)`（`MarketSettlement.java:565-573`）；
- 区内/跨区撮合入口仍是 `matchGroup`/`matchRoute`/`pairUp`/`executeTrade`（`MarketSettlement.java:939/980/1009/1080/1204/1260`）；
- 市场占 360→390 的 74.3% ⇒ 只并行其余阶段的理论加速上限约 **169.7 / (126.0 + 少量) ≈ 1.30–1.35×**；若把 `OwnershipBooks.apply` 也串行算进去，上限更低。
- 实际 R2 并行覆盖的消费/投入/收获/借粮/劳动再分配等阶段，按旧 JFR 只占 step 的百分之几到十几 ⇒ 8 线程下可期望的加速只有 **1.1× 量级**，会被开销吃掉。

### C3：P1.2 / P1.3 / P1.6 的关键算法去重尚未实现

R2 报告明确：`buysByHexCommodity`、`sellsByRegionCommodity`、`adjacentRegions`、`refreshSellFrozen` 增量视图、关账日 21,721 条按账户分桶都**留给 R3**。因此市场热点仍按原扫描/重建路径在跑，多线程覆盖不到的部分不可能变快。

### C4：32 分区结构在单线程路径造成额外重复工作

- `EconomyParallelism.partitionCount()` 固定 32（为保证 1/4/8 同序）；
- 即使 `pool == null`，`SettlementExecutor.execute` 仍按 32 个分区逐个跑 worker（`SettlementExecutor.java:86-92`）；
- `MarketSettlement` 的 worker 在每个分区里都调 `EconomySettlement.rowsByHex(round.rows)`（`MarketSettlement.java:500`），即**同一市场轮重建 32 次行索引**；旧串行路径每轮只建一次（`MarketSettlement.java:371`）。
- 其它已接入阶段同样每个分区各建计划/缓冲；对 799 格/201 区规模，这部分固定开销会侵蚀 `MarketTopologyBook` 地形缓存带来的收益。

### C5：`OwnershipBooks.apply` 未见并行

`OwnershipBooks` 在 R2 只做了按账户分组/批量写回，没有接 `SettlementExecutor`；关账日 21,721 条 apply（旧约 17.5 s/日）仍在协调器单线程。

---

## 3. 这次运行能证明什么 / 不能证明什么

**能证明**：
- tick0 世界能从空 store 正常初始化，R0+R1+R2 + M1–M8 修复后的真实推进**没有崩溃**，tick0 守恒读数基本成立。
- 0→30 单线程耗时 46.06 s，与旧基线早期同量级。

**不能证明**：
- 不能证明多线程“无效”——因为**多线程从未启用**；
- 不能证明 1/4/8 一致性；
- 不能证明优化后仍达不到 4 min——需要先接线 + 并行市场后再测。

---

## 4. 建议的下一步集中修复（按优先级）

1. **接线**：`ShellConfig` 增加 `economyWorkerCount`（CLI `--economy-threads N` 或系统属性），经 `Shell` 传入
   `PopulationEconomyTimeParticipant`，最终构造 `EconomyParallelism.of(N)`；默认先保守为 1，显式传 4/8；
   生命周期由 `EconomyDayStepper.finish()` 关闭自建池。
2. **市场撮合并行**：把区内 `matchGroup` 按市场区线程本地化（BuySlot/SellSlot → FillIntent），
   跨区由协调器按全局 `(landedPrice,costRank,canonical seller,canonical buyer,orderId)` 稳定序处理；
   冻结 `FreezeIntent` 只允许协调阶段写（M8 已定）。
3. **P1.2/P1.3/P1.6**：市场索引、冻结增量视图、关账日按账户分桶；与并行同批做，否则市场热点下不去。
4. **消除 C4 重复索引**：`rowsByHex` 在 `clearOncePerCycle` 入口建一次，作为只读参数传入 worker；
   1 线程路径不再重复 32 次建索引。
5. **测量**：在同一 tick0 baseline 复制 store，跑 0→30 与 30→60 的 `workers=1/4/8`，记录 wall 与阶段计时；
   旧基线仅作热点参考，不能跨代码态直接比。

## 5. 诚实边界

- 本报告只使用已有日志/静态代码与旧调查数字，没有在本次排查中新跑 1/4/8 对照实验；
- 46.06 s 与旧 296.1 s/180 天不是同一代码态、同一 tick 区间，不能做严格加速比；
- 要得出“多线程能提升多少”的结论，必须完成 §4.1–§4.4 后再测。
