# G3-fix-3 实现账本 —— 删掉 tool 判据的"− 本轮已放行 × 门槛"（重复扣减 / B 世界 25 趟假拦）

> 角色：写码 Agent（责任区 = G3-fix-3）。**未改任何测试 / pom / docs / 其他模块**；未跑 `test` / `verify` / world；未 `git commit`。
> 日期：2026-10-10。基线：`ffa363e9`（工作树开工时干净）。上一轮现场：`.superpowers/sdd/2026-10-10-g3d-verify-budget-timing/impl-ledger.md` §1/§2。
> 一句话：判据量从 `max(0, 当刻可用量 − 本轮已放行 × 门槛)` 改成 **`当刻可用量`**；`releasedMilli` 退为纯日志字段。

## 1. 关键调查结论（file:line 证据 → 结论 → 影响）

| # | 证据（落点） | 结论 | 影响 |
|---|---|---|---|
| 1 | `EconomySettlement.java:1860-1867` 装配 `MerchantCapacityPool.of(..., householdGoods, householdFrozenGoods, ...)`；同两个对象在 `:1817-1819` 传给 `MarketSettlement.MarketRound` | 池的活视图 = 市场轮的账表**同一个对象**；`MarketRound.java:599` 是直接引用赋值（无 copy） | 池持有的引用当刻可见市场轮内的写入 |
| 2 | `EconomySettlement.java:8661-8669` `consumeForLoss` → `setStock`（`:9374-9380` 就地 `householdGoods.put`） | 实扣**就地改外层 Map**，不是替换或快照 | 燃烧落账后，池的活视图读数立刻少 1000 |
| 3 | `MarketSettlement.java:6396` `select(...)` → `:6618` `settleHaulRuns(...)` → `:7030` `consumeForLoss(...)`，全部在**同一次 `executeTrade`** 内；`consumeForLoss` 在 economy main 里**只有这一个调用点** | 燃烧在"本笔 select 之后、下一笔 lane 的 select 之前"落账；两次 select 之间唯一的 tool 写入者就是它 | ⇒ 下一次 select 读到的可用量**已含**前几趟的燃烧；再减 `releasedMilli` = 同一笔扣两次 |
| 4 | `select` 的 pool 条目逐户唯一（`assemble` 每户一个 `Entry`），一次 select 内每户最多被选一次 | 不存在"同一次 select 内重复放行同一户"需要靠减项防住的情形 | 删减项不会放过"同一 select 内的第二次" |
| 5 | G3d 实测：B 世界 `day=213 stock=1465 frozen=0 available=1465 needed=1000 released=2000`；A 同户同日 `1879 → 879`（差恰 1000） | 假拦机理坐实；1879→879 = 真烧一趟后活视图少了恰好一趟门槛（实测直证，不是推演） | 删减项后 `1465 ≥ 1000` ⇒ 放行（两种成文口径都放行） |

## 2. 实现（唯一行为改动 1 处算式）

- `MerchantCapacityPool.java:710-714`：`toolCheckMilli = toolAvailableMilli < 0 ? item.remainingToolMilli : toolAvailableMilli;`
  - 有活视图（生产路径）：判据量 = `max(0, 现货 − 冻结)` 的**当刻**读数，减项删除。
  - 无活视图（4/5 参旧路径）：仍 = `item.remainingToolMilli` ⇒ **逐值退回改前**（缺省中性不变）。
  - `toolAvailableMilli < 0` 仍只作"没有活视图"的哨兵（`liveToolStockMilli` 返回 -1），语义未动。
- 纯文档改动（不改行为）：类注新增 G3-fix-3 节（算式/证据/单调性/归因副作用）、`toolBlockReason` 三选一说明、
  `of(...)` 生产装配点 javadoc、`select` javadoc、`Entry.remainingToolMilli` / `releasedToolMilli()` /
  `toolBlockedReleasedMilli` / `recordToolBlock @param` / `logToolBlocks` 的 `releasedMilli` 注释、
  `toolBlockedByReason` 与 `MERCHANT_CAPACITY_POOL` 的归因注释；`MerchantHaul.java:88-100` `TOOL_BUDGET_REASON` javadoc。
- `releasedMilli` **保留**：唯一调用点 = `MerchantCapacityPool.java:722`（`recordToolBlock` 的日志实参），
  `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 字段不动；注释写明"只回答放行过几趟，不参与判据"。

## 3. 关键判断

1. **为什么删而不是改成别的减项**：`releasedToolMilli()` 是"本池自己放行过几趟"的计数，而活视图是同轮**账上**的当刻量；
   两者在"每放行一趟立即实扣"的次序下描述同一笔事实 ⇒ 任何非零减项都是重复扣减。
2. **fail-closed 方向不变，且判据与提交侧从"更严"变成"完全一致"**：同一张活表、同一算式 `max(0, 现货 − 冻结)`、
   同一次 `executeTrade` 内"先判后扣"且中间没有 tool 写入者（结论 3）⇒ `TOOL_SHORT_AT_COMMIT` /
   "SHORT ∧ 同户同日 `CARRIER_FEE_PAID`" 结构上仍为 0；可用量真不够 ⇒ 拦（`tool-short`；与冻结相关 ⇒ `tool-frozen`）。
3. **单调性**：新判据 ≥ 旧判据（逐点，减项非负）⇒ 只会减少拦、不会新增拦 ⇒ A 世界真烧趟次不会因本改动下降（结构保证）。
4. **归因副作用如实记**：拦 ⇔ 可用量 < 一趟 ⇒ `tool-budget-exhausted` 在生产路径**结构上不可达**。
   常量与三个计数**不删**（日志字段/既有读数不漂），但注释写明"不再由判据产生"（否则后人会以为它仍参与）。
5. **未采纳**：不删 `toolBudgetBlockedRuns` 字段、不改 `laneBlockedReason` 的拼接、不动 `MerchantHaul.affordsRun`
   —— 都是裁定外的行为面改动（会改日志形状/读数），风险大于收益。

## 4. 偏离记录

无。判据算式、退化路径、`releasedMilli` 保留三条与裁定逐字一致；未碰标定值、测试、pom、docs。

## 5. 真实命令与结果

| 命令 | 结果 |
|---|---|
| `MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -q spotless:apply -pl simos-economy` | rc=0；`git diff --name-only` 只有本批 2 个文件（无越界格式化） |
| `MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests compile` | **BUILD SUCCESS**，16 模块全 SUCCESS，9.313 s |

## 6. 未完成 / 未验证（如实）

- **①②③ 验收判据一条都没跑**（禁跑 world/测试）：B 25 趟假拦是否归零、三条 0 判据是否仍 0、A 真烧趟次是否 ≥48 —— 全部留给控制方下一轮复测。
- 未跑 `test` / `verify` / 前端门禁；未做变异自证（开发期纪律：测试留最后统一做）。
- 既有测试的断言影响面：全仓 `src/test` 对 `tool-budget-exhausted` / `releasedMilli` / `budgetBlockedRuns` **零命中**
  （`grep -rl` 实测），故预期无用例因本改动变红；**未实跑确认**（不归本责任区）。
