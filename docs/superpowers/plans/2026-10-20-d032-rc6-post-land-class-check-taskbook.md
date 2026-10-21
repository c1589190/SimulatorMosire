# RC6 任务书：S7-B 无地买地后的阶级跃迁检查

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v4 R2/R3、§3 S7-B、§5；v2 S7。
> 依赖：RC4-A/B/C/D、RC3、RC5 已 compile 绿。
> 本单只实现 **landless 买方本轮市场 LAND 成交 → 全部 LAND + 按比例人口/钱/货/债转到 target landed 户**；
> 不做 RC7 向下流动表，不做 §8。

## 1. 目标运行位置

在统一市场撮合完成、LAND 产权写回工作副本之后、`ClassSlideSettlement` 之前：

```text
S7-A 市场（商品 + LAND）
→ 写回 MarketRound.landHoldings 到 session.sheet().landHoldings()
→ 标记 marketRound.landAcquiredQuantityThisRound
→ S7-B PostLandTradeClassCheck
   ├─ 只取本轮市场 LAND 买方（不是债务抵地）
   ├─ classNature=LANDLESS 的买方逐 hex 处理
   ├─ 全部取得 LAND 转给该 hex 对应 LANDED 户
   ├─ 按每个 hex 的成交 LAND 比例拆人口
   └─ 钱/货/债/经验随人口走；临时 LAND 清零
→ S7-C 向下流动（RC7，不在本单）
→ 收尾
```

## 2. 当前事实（file:line）

- `MarketRoundCore.markLandAcquired(...)` / `landAcquiredQuantityThisRound()` 只由 `MarketTradeExecutor.executeLandTrade` 写市场买入，键 `(hex, buyer)`；不包含 M2 债务抵地（M2 写 `SettlementDayContext.landAcquiredQuantityThisRound`）。
- `SettlementDayMarketPipeline.executeMarketRound` 在市场末写回 `workingLand.putAll(ctx.marketRound.landHoldings())`，并调 `ctx.markLandAcquired(...)`。
- `MarketLandBook.LandFill` 有 buyer/seller/hex/quantity 的只读读数，但 `MarketRound` 里没有 fills 列表；本单使用 `marketRound.landAcquiredQuantityThisRound()` 即可（只含市场）。
- `ClassNatureBook.classNatureOf(dayView, household)` 已存在（RC2a）。
- `HouseholdMergeBook` 已有同 key 计划/survivor 规则（RC4-A）。
- `HouseholdTransferSettlement` 已处理钱/货/债/经验/人口 outbox/source labor shrink（RC3）。
- `LandLedgerState`/`LandBook` 是 LAND 转移唯一写口。
- `EconomyDayStepper.mergeSameKeyHouseholds` 在 step 前做 R1；本单 S7-B 中若同 hex 同时出现多个 landed 目标，必须按 R1 的 survivor 规则选/合并，或在未合时具名失败；首版优先依赖 step 前 R1，不另造合户公式。

## 3. 冻结算法

新增 `simos-economy/time/PostLandTradeClassCheck.java`。

入口：

```text
static PostLandResult settle(SettlementDayContext ctx, long day)
```

### 3.1 输入/过滤

- 取 `ctx.marketRound.landAcquiredQuantityThisRound()`，按 `(hex, buyer)` 稳定序分组；
- 通过 `ctx.householdOfActor`/`marketRound.householdOfActor` 得到 buyer HouseholdId；
- 只对 `ClassNatureBook.classNatureOf(ctx.dayView, buyer) == LANDLESS` 的买方执行；
- `LANDED` / 无 class nature / 无市场 LAND 的买方跳过（DEBUG/INFO）；
- 不得处理 `ctx.landAcquiredQuantityThisRound`（M2 债务抵地）；本单只处理 marketRound 的 map；
- 若买方在 marketMap 中有多个 hex，合并为一个 buyer 计划。

### 3.2 目标 landed 户

每个 hex：

- 候选 = `ctx.householdEconomies` 中：
  - `view().hex() == hex`
  - `ClassNatureBook.classNatureOf(...) == LANDED`
  - current position mode == buyer current position mode（`grain_farm`）
  - population > 0（0 人口壳不作为目标；若无正人口 target ⇒ fail-closed）
- 文化键：`ctx.session.cultureKeyOf(h)`（RC4-A）：
  - 优先同 cultureKey 的候选；
  - 多个同 key ⇒ 取 R1 survivor 规则（人口最多 → 该 hex LAND 最多 → HouseholdId 最小）；
  - 没有同 key 但存在其它 LANDED 候选 ⇒ 取稳定最小 `HouseholdId`，INFO `POST_LAND_CULTURE_MISMATCH_DIRECT_MERGE`；
  - 无候选 ⇒ fail-closed `post-land-no-landed-target`，整次 advance 失败；
- 目标 mode/hex 进 `HouseholdTransferRequest`。

### 3.3 人口容量与多 hex 拆分

对每个 buyer：

```text
P = buyer 当前 population
acquired_i = 该 hex 市场成交 LAND（>0）
minLandPerPersonMilliMu = POST_LAND_MIN_LAND_PER_PERSON_MILLI_MU (工程默认 2667; 后续接 DailyDemandBill/Social 温饱线)
maxByLand = floor(Σ_i acquired_i / minLandPerPersonMilliMu)
maxBySpeed = max(1, floor(P × 100 / 1000))     // 既有 100‰/市场轮
maxByWealth = Long.MAX_VALUE                   // RF1 允许现金不足成普通债；首版不设财富门
migrants = min(maxByLand, maxBySpeed, P - 1)
```

- `migrants` 可为 0；即使 0，LAND 也必须全部转给 target（不得让 LANDLESS 保留临时 LAND）；
- 按各 hex `acquired_i` 权重用最大余数法拆 `migrants`；
- 每个 hex 的 target 按 3.2 选择；
- `targetPopulationBefore` 使用 target 当前人口 + 已迁入该 target 的 projected 人口（多 hex 可能同一 target）；
- `sourcePopulationBefore` 在多笔 transfer 中**逐笔递减**（第一笔后 P' = P - moved），保证钱/货/债按剩余人口比例正确切；
- `HouseholdTransferRequest(reason="POST_LAND_CLASS_TRANSITION", targetIsNew=false)`。

### 3.4 LAND 转移

对每个 `(buyer, hex, acquired_i)`：

- 从 `LandLedgerState.from(sheet.landArableSupply(), sheet.landHoldings())`；
- 验证 buyer 在该 hex 持有量 ≥ acquired_i；
- `LandTransfer(hex, buyer, target, acquired_i)` 一次全量转出；
- 写回 `sheet.landHoldings()`；
- 清除 buyer 的 `marketRound.landAcquiredQuantityThisRound` 项（或通过新 `clearMarketLandAcquired` 写口）；
- 不写 `landAcquiredThisRound`/`SettlementDayContext.landAcquiredQuantityThisRound`（那是 M2 的 marker）；
- 最终 buyer 在该 hex LAND 必须为 0；否则 `post-land-landless-residual-land` fail-closed。

### 3.5 人口/钱/货/债/经验

- 每个 hex 按 `migrants_i` 调 `HouseholdTransferSettlement.apply(...)`；
- 顺序：LAND 先转，再人口/资产转移；同一 buyer 多 hex 的顺序按 hex 自然序；
- `migrants=0` ⇒ 不调 RC3，只转 LAND；
- 转移失败 ⇒ 整次 advance 失败，不落 revision；
- 记录 `PostLandResult`（buyers、hexes、transfers、migrants、landTransferred、targets）。

### 3.6 位置/classness

- buyer classness 在 S7-B 内**不直接改**；其人口减少/清空，另由 App outbox 写 Social；
- target 保持 LANDED；
- 不得在 S7-B 中把 buyer 的 `HouseholdClassMembership.currentPositionId` 改成 landed；
- 若 buyer 变为 0 人口壳，保留 row/classStanding/FlowRow，不删除。

## 4. 日志/失败

- INFO `POST_LAND_CLASS_CHECK`：day/buyer/hex/target/migrants/landTransferred/notional；
- INFO `LAND_TO_TARGET`：逐 hex land qty + target；
- INFO `POST_LAND_CULTURE_MISMATCH_DIRECT_MERGE`：buyer/target/hex；
- DEBUG 逐币/逐商品/逐债转移读数；
- fail-closed：无 landed target、target mode 不匹配、buyer 残留 LAND、LAND 转移后总量不守恒、RC3 失败 ⇒ 整次 advance 失败。

## 5. 允许修改的文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/PostLandTradeClassCheck.java（新增）
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayMarketPipeline.java（S7-A 写回后调用一次）
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRoundCore.java（只加/读市场取得 map 的 clear 口）
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdMergeSettlement.java（只读复用 target/survivor；不改既有语义）
```

必要时只改 `HouseholdTransferSettlement` 注释；**禁止**改 LAND 市场/债务公式、EconomyData/ChangeSet/Codec、seeder、tests、主树。

## 6. 验收锚点（测试 owner 后补）

正例：

1. LANDLESS 买方在本轮买 2 个 hex LAND ⇒ 每 hex LAND 全转对应 LANDED target；买方 LAND=0；
2. 人口按各 hex LAND 比例拆；钱/货/债按逐笔剩余人口比例走；source/target 人口守恒；
3. `maxBySpeed=100‰` 生效；`P-1` 保护；多 hex 余数不丢；
4. target 同文化优先；无同文化选最小 HouseholdId 并记 mismatch；
5. LANDED 买方/无 class nature 买方不触发 S7-B；
6. 0 migrant 时仍 LAND 全转，无临时 LAND 落盘。

负例：

- LANDLESS 买方残留 LAND ⇒ 红；
- 无 landed target 时静默跳过/凭空建 target ⇒ 红；
- 把 M2 债务抵地当市场 LAND 触发 S7-B ⇒ 红；
- 人口超过 P-1/超过 maxByLand/maxBySpeed ⇒ 红；
- 钱货债分母用初值导致多笔比例错误 ⇒ 红；
- LAND 总量不守恒/未走 LandBook ⇒ 红。

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc6-post-land-class-check/impl-ledger.md`。
- 报告列：改动文件、编译 rc、多 hex 拆分/分母规则、LAND/RC3 顺序、失败点、可能失效测试断言、未验证项。

## 8. 本责任区不做

- 不做 RC7 向下流动表；
- 不改 LAND 市场/债务公式；
- 不把 §8 整组织交易做进来；
- 不写/不跑测试。

## 9. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R2：有地的家户挂单根据库存情况等写好的判断挂单，如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走，如果是有地的家户买到了土地，那就是买到了，走正常的生产支配流程
R3：完全可以
R5：是的，随着人走
```
