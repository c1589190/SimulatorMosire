# 多线程优化结果报告（90 tick，2026-09-28）

> **任务**：按用户要求先完成多线程优化，再跑一次 90 tick 模拟；单次测试 ≤3 分钟。
> **代码态**：`ts/m1` + 未提交的多线程优化改动；控制方用 `tools/mvn-lock.sh -Dmaven.test.skip=true package` 构建 jar，
> md5 `a69dbbd298eb57c5260c503286963b88`。
> **诚实边界**：本报告只有一次 1 线程 / 一次 8 线程的 90 tick 运行；未跑 JUnit/verify；不代表 360 tick 或最终 4 min 目标。

---

## 1. 优化内容（生产代码）

修改 8 个文件：

- `ShellConfig` / `ShellMain` / `Shell`：新增 `economyWorkerCount`（缺省 1）与 CLI `--economy-threads N`；
- `PopulationEconomyTimeParticipant`：把 workerCount 传入 `EconomyDayStepper`，用 `try/finally` 收线程池；
- `EconomyDayStepper`：`AutoCloseable`，异常路径不再漏关池；日志可见 workerCount；
- `EconomyParallelism`：`toString` 增加 `parallel=`；
- `MarketSettlement`：
  - `rowsByHex` 每轮只建一次，消除 32 分区重复建索引；
  - 新增 `MarketIndexes`（按区/hex/商品的买卖索引、邻接区、商品序缓存、参与者索引）；
  - 区内撮合按市场区并行：协调器建只读 clone，worker 产出 `FillIntent`/槽位终态，协调器按固定回放序走
    `executeTrade` → 唯一写口 `applyTransfer`；
  - 冻结增量轴累计（`sellFrozenSums` / `buyFrozenSums`），`refreshSellFrozen` 等从全表扫描降为 O(1)；
- `SettlementStage`：并行阶段声明补全。
- **未做**：跨区撮合仍单线程（但已用索引去掉全表扫描）；`OwnershipBooks.apply` P1.6 无新增改动。

运行日志证据：`人口—经济推进并行入口: workerCount=8 parallelism=EconomyParallelism[workers=8, parallel=true, partitions=32]`。

---

## 2. 90 tick 实测结果（同一 tick30 起点，复制 store）

| 代码态 | 线程 | 30→120 墙钟 | 相对旧单线程 | 相对新单线程 |
|---|---|---:|---:|---:|
| 旧代码（`7e1417d4`，多线程未接线） | 1 | **156.41 s** | 1.00× | — |
| 新代码（本次优化） | 1 | **102.40 s** | **1.53×** | 1.00× |
| 新代码（本次优化） | 8 | **82.39 s** | **1.90×** | **1.24×** |

- 8 线程 90 tick **82.39 s < 180 s**，满足单次 ≤3 分钟要求；
- 主要收益来自算法/索引/单线程去重（1.53×），线程并行只再贡献 1.24×；
- 跨区撮合仍串行，是后续 4 min 目标的主要剩余瓶颈。

## 3. 1/8 线程状态一致性（运行时证据）

对同一 tick30 起点分别跑 1 线程与 8 线程，在 tick120 收集：

- 顶层统计：population / goods / money / debts / counts 全部逐值相等；
- API 逐 hex `api_hex_rows`：799/799 行**逐值相等**；
- Membership 逐 lot 对齐、使用权不超容量、冻结净零；
- 两个 store 的 `reconcile` 结果相同；
- 因此，至少在这条 90 tick 路径上，**8 线程没有改变领域状态**；更广的 1/4/8 重放仍留 V 阶段。

## 4. 经济效果

tick120 与优化前 90 tick 评估报告一致：

- population 11,621,928（tick0 −1.76%）；births 99,482 / deaths 307,554；
- grain 总库存 150,301,673,963 毫（全球覆盖约 153 天）；
- 799/799 格粮食 satisfaction <500‰（中位 383‰），Σ unmetPersonDays 861M；
- 说明优化没有改变“总量足、分配坏”的经济结论；这属于 R3/R4 的语义问题，不是本次多线程任务的目标。

## 5. 结论与下一步

- 多线程接线与区内市场并行**已经产生可测收益**：90 tick 从 156.4 s 降到 82.4 s（1.90×），且 1/8 线程状态逐值一致；
- 但 8 线程相对新单线程只有 1.24×，因为跨区市场、部分日结算阶段和 apply 仍未并行；
- 按 90 tick 粗略线性外推 360 tick ≈ 5.5 min，仍高于 4 min；后段通常更慢 ⇒ 4 min 仍需 R3/P3 的跨区并行、P1.6 与更多算法去重；
- 下一步：R3（市场成本排序/逐买方原因/经营者-家户状态机/迁移/阶层 + 跨区并行），随后 R4（GOV），最后 V 阶段做完整 1/4/8、守恒、场景与 360 tick 性能验收。
