# 清理账本 —— 删掉已成死分支的 `tool-budget-exhausted` 一族（G3-fix-3 后续）

> 角色：**写码 Agent**（允许写 `simos-economy/src/main/**`；本账本是唯一其它写盘）。
> **未碰**：任何 `src/test/**`、`pom.xml`、`docs/**`、`AGENTS.md`、`.superpowers/**`（除本文件）。**未 commit**。
> 日期：2026-10-10。基线 HEAD `2561c721`（G3e 验收通过：A 2,567 行 / B 506 行 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`，Σ 预算档 **全 0**）。
> ★ 一句话：**判据一字未动**，只删"结构上不可达"的第三档归因及其计数/日志字段；旧路径（4/5 参，无活视图）逐值不变（见 §3 等价性证据）。

## 1. 可达性判定（删之前的证据链）

| 步骤 | 事实（HEAD 行号） |
|---|---|
| 生产装配点 | `EconomySettlement.java:1861` 走 **6 参** `MerchantCapacityPool.of(...)` ⇒ `liveGoods/liveFrozenGoods` **非空**（`MerchantCapacityPool.java:253-262`） |
| 判据量 | `select` 内 `toolCheckMilli = availableMilli < 0 ? remainingToolMilli : availableMilli`（`:709-714`）；"被拦" ⇔ `!affordsRun(toolCheckMilli)` |
| 生产路径 | 被拦 ⇒ `availableMilli < 1000`（判据量就是它） |
| 旧路径（4/5 参） | `stockMilli = -1`、`availableMilli = -1`（`:820-833`）⇒ 判据 = `remainingToolMilli`，而**归因函数第一支** `stockMilli < 0` 直接落 `tool-short` |
| 旧归因函数 | `toolBlockReason(stock, avail)`（`:852-859`）三支：`stock<0 \|\| stock<1000` → short；`avail<1000` → frozen；**else → budget**。两条可达路径都落前两支 ⇒ **第三支在两条路径上都不可达** |
| 只服务它的面 | `Entry.toolBudgetBlockedRuns`（`:498`）+ `select` 局部（`:687/727-729`）+ `recordToolBlock` else（`:545-547`）+ `laneBlockedReason` 第三支（`:886-888`）+ 汇总下标 2（`:946/1183-1184`）+ 两处逐行/逐格日志字段（`:986-987`、`:796-797`） |

⇒ 结论：**该分支不是"可能可达"，而是"结构上不可达"**，与题面一致；未发现任何生产调用点能让它成立（故未触"停手上报"条）。

## 2. 改动与删除清单

改动 2 个文件（`git status --porcelain` = 仅此 2 行）：`MerchantHaul.java`、`MerchantCapacityPool.java`。

**grep 命中数（删除前 = HEAD / 删除后 = 工作树；范围 = 全仓 `**/*.java`，2060 个 tracked java 文件含 `.superpowers/**/mutants` 夹具）**

| token | 删除前 | 删除后 |
|---|---|---|
| `tool-budget-exhausted` | 7 | **0** |
| `TOOL_BUDGET_REASON` | 3 | **0** |
| `budgetBlockedRuns`（逐户日志字段） | 3 | **0** |
| `toolBudgetRemainingMilli`（逐行日志字段） | 1 | **0** |
| `toolBudgetBlockedRuns`（Entry 字段 + 2 处事件字段 + 局部 + 汇总） | 12 | **0** |
| `toolBlockReason`（私有归因函数） | 3 | **0** |
| **4-token 合并（tool-budget 一族）** | **11** | **0** |
| **6-token 合并** | **26** | **0** |
| 命中文件（删除前） | `MerchantCapacityPool.java`、`MerchantHaul.java`（仅此 2 个；`src/test/**` 命中 **0**） | —— |

**删除项**：① 常量 `MerchantHaul.TOOL_BUDGET_REASON`（连带其整段注释，原位留 4 行"该档已删/为什么"的当前态说明）；② 私有函数 `toolBlockReason(stock, avail)`（含 28 行注释）；
③ `Entry.toolBudgetBlockedRuns` 字段；④ `select` 局部 `toolBudgetBlockedRuns`；⑤ `laneBlockedReason` 的第 7 个入参及其第三支 `reasons.add(...)`；
⑥ `toolBlockedByReason()` 的 `budget` 累加与下标 2（返回 `long[]{frozen, short}`）；⑦ 4 个日志字段：`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT.budgetBlockedRuns`、同名事件的 `toolBudgetRemainingMilli`、
`MERCHANT_CAPACITY_LANE_TRUNCATED.toolBudgetBlockedRuns`、`MERCHANT_CAPACITY_POOL.toolBudgetBlockedRuns`。

**替换手法（判据不受影响的关键）**：`select` 里的归因调用改为 `MerchantHaul.blockedReason(toolStockMilli, TOOL_MILLI_PER_HAUL)`（现工作树 `MerchantHaul.java:119`，**二选一的唯一拼写点**，`MarketSettlement.java:7067` 也在用），
两处计数链的 `else if(SHORT) … else{BUDGET}` 收成 `else`（= short）。★ 可达域上逐值等价见 §3。

**类注/事件说明同步**：类注 §G3-leftovers 与 §G3-fix-3 的"归因副作用"、`select` 类注、`recordToolBlock`/`laneBlockedReason`/`toolBlockedByReason` 注、4 处事件字段注、`Entry` 字段注 —— 全部改为"归因只剩两支 + 第三档已删"；
留 9 处带日期的"该档已删"标注，**无任何"仍参与判据/字段保留"的过期描述**（`grep -i toolbudget` = 0）。

## 3. 真实命令与结果（一次一个 Maven，均走本仓锁）

| # | 命令 | 结果 |
|---|---|---|
| 1 | `tools/mvn-lock.sh -q spotless:apply -pl simos-economy` | **rc=0**（无输出 = 无违规；run 后 `git status` 仍只 2 文件） |
| 2 | `tools/mvn-lock.sh -DskipTests compile` | **rc=0 / `BUILD SUCCESS`**，16 模块，Total time 9.748 s，Finished 2026-10-10T10:07:45；`EconomySimos ... SUCCESS [2.192 s]` |
| 3 | 编译产物核对（`javap -p`，证明编的是新字节） | `MerchantCapacityPool$Entry` 无 `toolBudgetBlockedRuns`、保留 `remainingToolMilli` + `releasedToolMilli()`；`MerchantHaul` 常量只剩 `TOOL_SHORT_REASON`/`TOOL_FROZEN_REASON`（无 `TOOL_BUDGET_REASON`）；外层类 `toolBlockReason` **0 命中**、`laneBlockedReason` 入参 6→5 |
| 4 | 判据代码 vs HEAD 逐字节比对 | `sed -n '/long toolStockMilli = liveToolStockMilli/,/: toolAvailableMilli;/p'` 两版 **diff 为空**（含注释逐字节相同）⇒ 第 4 条"旧路径逐值不变"的结构前提成立 |
| 5 | 归因/计数差分（`/tmp/g3dead/ReasonDiff.java`，非仓库测试，跑真实编译产物 `MerchantHaul.blockedReason`） | `checked(reachable blocked cases)=86 mismatch=0` → **EQUIVALENT**；变异体（`newCount` 的 short 支改坏）= `mismatch=38` + `AssertionError` exit=1 ⇒ 该装置**有判别力**，非恒真 |

## 4. 保留项（题面第 2 条逐条）

- **`releasedMilli` 保留**：它**仍有读者** —— `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT.releasedMilli` 逐户 DEBUG 行（首次被拦那刻的"本轮已放行 × 每趟门槛"），且它 + `stockMilli/frozenMilli` 仍解释"为什么可用量少了"。字段 `toolBlockedReleasedMilli` 与 `Entry.releasedToolMilli()` 一字未动。
- **`tool-short` / `tool-frozen` 两档可达归因保留**：`TOOL_SHORT_REASON` 3 处、`TOOL_FROZEN_REASON` 3 处（`MerchantCapacityPool`）+ `blockedReason` 供 `MarketSettlement:7067` 使用。
- **`Entry.remainingToolMilli` 保留**：它就是**旧路径（无活视图）的判据量**，删它才会真的改行为。
- **`toolBlockedRuns()` 公开读数保留**：`MerchantCapacityAcceptanceTest.java:189` 在用（禁碰 test ⇒ 不能删）。
- **未删的镜像打印字段**（题面只点名 `toolBudgetRemainingMilli`，故不动）：`MERCHANT_CAPACITY_HOUSEHOLD.toolRemainingMilli`/`runsAffordable`、`MERCHANT_CAPACITY_POOL.toolMilliRemaining`。
- **标定值未动**：`TOOL_MILLI_PER_HAUL = 1000` 及其标定注释一字未改（tool 门槛标定待用户裁定）。

## 5. 未完成 / 未验证 / 交控制方

1. **未跑** `test` / `verify` / world（题面纪律）⇒ 无行为回归实测；等价性证据 = §3 的 4/5 两项（静态 + 编译产物差分），**不等于**真实世界读数。
2. **观察到但未删**（题面未列，避免扩大范围）：`MerchantCapacityPool.remainingToolMilliOf(HouseholdId)`（现工作树 `:888-891`）全仓 **0 调用**，是装配镜像的公开死读口 —— 请控制方裁定是否随测试批次一并处理。
3. 删掉的 4 个日志字段**会改日志行内容**（字段数变少）；全仓**无脚本/工具读它们**（`grep` 命中的只有代码与历史账本/docs：`.superpowers/sdd/2026-10-10-g3*/**`、`docs/superpowers/status/2026-10-10-resume-points-and-backlog.md`）—— 后者按留痕纪律**未改**，其历史数字（29 行 / Σ5,824 / B 25 趟）仍成立，只是不再由新日志产出。
4. 工作树状态：`M` 两个 main 文件 + 本账本（新增）；**未提交**。
