# D-032 D1–D4 最后一次独立设计复核（F-01..F-04 修正后，2026-10-17）

> **性质**：只读终局复核。未修改设计/代码/测试，未跑 Maven/测试/仿真，未提交/合并/推送。
>
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
>
> **基线**：`HEAD 5705c357`；复核开始时 `git status --porcelain=v1` 为空。
>
> **输入**：`docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review.md`（上一轮 F-01..F-04 清单）、当前 D1–D4、replan/status 与源码事实。

## 结论

**不通过。**

P0=0，仍有 **P1=1、P2=1**。F-01/F-03/F-04 已实质关闭；F-02 的“W1/W2b 必须在 D4-I 之后”也已统一，但新顺序把 **D4-I 放在 W2a/W1/W2b 之前**，与 `EconomyData` 的组件落形顺序 `32 → 33/34/35 → 36/37` 冲突。当前不能按“可派 D4-I/W3”冻结。

## 一、上一轮 F-01..F-04 与 N/P2 核对

| 编号 | 判定 | 当前证据与说明 |
|---|---|---|
| F-01 | **关闭** | D3 已引入 `WagePayerLocationSnapshot`，要求 `existingSettlementLocations` 来自当前 revision 该 payer 的全部已有 unit/roster/posting 地点，再并 `candidateSettlementLocations`（`wage-chain-design.md:1220-1225`；W1/W2b `:961,989`；W3 `:1052`；P39b `:1437`）；已有 A + candidate B 的候选阶段 fail/skip 已写入判据。 |
| F-02 | **设计顺序关闭，但引入组件顺序 P1** | 统一顺序已改为 `C0/C0-A → C2 → D4-I → D4-T → W2a → W1 → W2b → W3`（D2 `:785`、D3 `:1555`、D4 `:899`、replan `:85-88`、status `:228-229`）。W1/W2b 确实在 D4-I 之后；但 D4-I 先于 W2a/W2b 会破坏 32–37 组件落形顺序，见 F-05。 |
| F-03 | **关闭** | D3-I3/I25 已改为 W1/W2b/W3 共用 `WagePayerLocationSnapshot`（`wage-chain-design.md:1366,1388`），与 §6.2/§6.9.1/P39b 一致。 |
| F-04 | **关闭** | D3 已增加 `NextTickIncomeProjectionRequest/Result/接口`，W2b 唯一 owner，文件 `time/NextTickIncomeProjection.java`；输入 cycle 起止/quote/target/劳动计划，输出仅下一 tick 可交付量，缺输入具名失败（`wage-chain-design.md:427-444,498-506`）；P51 明确 cycle=100、nextTick=1 时期末 product share=1000 只能投影本 tick 为 0（`:1449`）。D2 周期预计不得直接当下一 tick 输入（`cooperative-production-design.md:529`）。 |
| N-01（上一轮） | **关闭** | `InventoryReservation` overcommit 与 lookup 已按 payer×commodity 全部地点 ACTIVE 预留 + frozen 扣减（D4 `:265-276,572-585`；P13/P18c `:745,752`）。 |
| N-02（上一轮） | **关闭** | F-01/F-03 补齐后，W1/W2b/W3 共用 snapshot；F-02 的旧接口依赖已由新顺序解决。 |
| N-03（上一轮） | **关闭** | D4 已要求 `ShareTuple`/`buildTupleIndex`/Move/split/rebuild 纳入 location（D4 `:197-200`；P24b `:759`）。当前源码仍是旧形状，但设计义务明确。 |
| N-04（上一轮） | **关闭** | `incomeAvailableAtTick` 与 `NextTickIncomeProjection` 已落地设计（D3 `:427-444,498-506,1449`；D2 `:529`）。 |
| N-05（上一轮） | **关闭** | D3 已列全普通贷款/迁移/seed/codec 的 `DebtSource` 写入点与 owner（`wage-chain-design.md:647-657,1523`；P29b `:1438`）。 |
| N-06..N-09（P2） | **关闭** | D3 旧“D4 未完成/不改 replan”句已修正；D1 U14/U15 引文括注已移出；`maxCombinations`/`survival-combination-limit` 已有；D4-I/C2/W2a/W2b 顺序已统一。 |

R-01..R-18 上一轮状态未发现回归：`SMALL_TOOL`/`LARGE_TOOL`、D2-I2、32–35 与 36/37、D1 毫小时/包名/`UNALLOCATED`、U23 原话、`allowIdle` 撤回、折价词表等仍有效。

## 二、发现清单

| ID | 级别 | 问题 | `file:line` 证据 | 影响 | 建议最小修正 | 归属 |
|---|---|---|---|---|---|---|
| F-05 | P1 | 新 owner 顺序把 D4-I（要新增第 36/37 组件）放在 W2a/W1/W2b（要新增第 33/34/35 组件）之前，与 D2/D3/D4 冻结的组件落形顺序 `32 → 33/34/35 → 36/37` 冲突。 | D4 明确 32–35 后接 36/37：`asset-occupancy-design.md:655-658`；D4-I 在顺序中位于 W2a/W1/W2b 之前：`:899`；D2 owner 顺序 `cooperative-production-design.md:785`；D3 组件 33/34/35 由 W2a/W2b 落形：`wage-chain-design.md:539-547,1559-1561`；D4-I owner 含 `EconomyData`：`:894`；D4-T 在 D4-I 后、W2a 前 | D4-I 先落 `EconomyData` 时只能把 D4 两个字段追加成实际第 33/34 个，而不是第 36/37 个；随后 W2a/W2b 再插入/追加 33–35 会造成记录顺序与组件号漂移。D4-T 在 W2a/W2b 前看到的也不是 37 组件最终形状；实现者若为保持序号而预留字段，又会引入无主占位组件。 | 分离“lookup 接口”和“EconomyData 落形”：① 在 W2a 提前定义 `WagePayableInventoryLookup` 接口契约（或独立 shared-api owner），D4-I 之后实现；组件状态仍按 `C0/C0-A → C2 → W2a → W1 → W2b → D4-I → D4-T → W3` 落形。或② 把 D4-I 拆成 D4-contract（lookup 接口）与 D4-state（36/37）两批，D4-state 必须在 W2b 后。必须同时改 owner/批次/编译与 D4-T 判据。 | 控制方/D3/D4 |
| F-06 | P2 | D4 的测试只验证了 lookup 扣减与单地点预留（P13/P18c），没有独立验证“同 payer 两个地点预留各自单独不超、合计超过户级余额”时创建路径必须拒绝；all-location overcommit 主要在正文，缺少可执行负向判据。 | D4 正文要求 `payer×commodity` 全部地点 ACTIVE 预留 + frozen ≤ 户级余额（`asset-occupancy-design.md:266,276`）；P13 只测 lookup 余额/冻结/预留（`:745`）；P18c 只测其他地点预留被 lookup 扣除（`:752`）；没有“A=600、B=600、余额=1000 ⇒ `inventory-reservation-overcommitted`”判据 | 实现者可能只改 lookup 的求和，却让 `InventoryReservation` 创建口继续按单地点校验；该情况下 lookup 会抛错但预留已写入，违反 D4-I8 的一致性与“同一户级余额”约束。 | 增加 P18d：`grain=1000`、A/B 各 ACTIVE 预留 600 ⇒ 第二个创建/同批写入必须 `inventory-reservation-overcommitted`，零半状态；同时核对冻结 + 全地点预留的 aggregate 校验。 | D4 |

## 三、跨文档重点

### 3.1 组件号

这是本轮唯一 P1 矛盾。逻辑编号仍为当前 31 → D2 32 → D3 33/34/35 → D4 36/37，但新 owner 顺序让 D4-I 先落 36/37、W2a/W2b 后落 33–35，实际 record 顺序/测试计数会冲突。当前源码仍是 31 组件（`EconomyData.java:310-397`；`EconomyRoundTripTest.java:549`），尚未实现，固不存在运行期纠正。

### 3.2 时间尺度

A1 候选窗口与实际 W3 due 不乘 `cycleDays` 仍一致。F-04 的 `NextTickIncomeProjection` 已把“下一 tick 可交付”与 D2 周期累计分开；`cycleEnd > nextTick` 的 product share 被明确投影为 0（D3 `:498-506,1449`）。未发现新的时间口径矛盾。

### 3.3 idle / allowIdle

D1–D4 有效正文没有 `allowIdle`/`householdLaborChoices` 状态/命令/seed；`idleEligible`/`idleChosen` 与 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET`/`BEST_EFFORT_WORK` 语义仍一致。裁决文件旧 U-D3-2/U-D3-3 已标历史。未发现新的有效 `allowIdle` 判据。

### 3.4 D4 / R2 / W3

N-01/N-02 已在设计文字上关闭：lookup 全地点预留扣减、payer snapshot 覆盖已有 + 候选、W1/W2b/W3 共用预检。F-02 的新顺序解决了 lookup 接口先后依赖，但产生 F-05 组件落形顺序冲突；F-06 是 D4 预留创建负向判据的缺口。D4 仍未关闭 R2，W3 仍只在 D4-I/D4-T 后接入，方向未变。

### 3.5 抵物折价与模块边界

D1/D3 仍只允许 `LOCAL_BID`/`LOCAL_ASK`；未发现 `LOCAL_LAST_TRADE`/`referencePriceOf`/`SettlementLocationRef` 残留。缺价/0 价/折后 0/缺币种 skip 留债、系数可 `<1`、production 定义/economy 执行边界保持一致。`OwnershipStake`/`InventoryReservation`/`AssetOccupancyGrant` 仍不是第二本资产/库存数量账；未发现新的模块边界冲突。

## 四、已核无问题项

以下项已核，未发现额外问题（仅静态复核，不是实现/测试通过）：

1. **F-01**：`WagePayerLocationSnapshot` 已包含 payer 当前 revision 全部已有 unit/roster/posting 地点 ∪ 候选/本次地点；W1/W2b/W3 共用；P39b 已有 A+B 候选阶段失败判据。
2. **F-02 的接口方向**：D4-I 唯一实现 `WagePayableInventoryLookup`，W1/W2b/W3 只消费；旧“W1/W2b 先于 D4-I”的顺序已从 D2/D3/D4/replan/status 统一替换。遗留问题仅为 F-05 的组件顺序。
3. **F-03**：D3-I3/I25 已与 W1/W2b/W3 共享 snapshot 对齐。
4. **F-04**：`NextTickIncomeProjection` 已有类型、W2b owner、输入/输出/失败语义与 P51；D2 周期值不得直接成为下一 tick `expected*`。
5. **上一轮 N-01/N-03/N-04/N-05**：全地点预留扣减、`ShareTuple` location、`incomeAvailableAtTick`、`DebtSource` 写入点/P29b 均保持有效。
6. **P2 回归**：D3 旧状态句、D1 引文括注、`maxCombinations` 已修；未发现有效 `TOOL` 词表、`allowIdle`/`householdLaborChoices`、`LOCAL_LAST_TRADE`/`referencePriceOf` 残留。
7. **源码事实**：`EconomyData` 31 组件；`OwnershipStakeBook.ShareTuple` 旧源码无 location；`AccountSession` 户级无 location 级库存；`MarketTable` 有 `hasPrice`/bid/ask；本轮设计与新增类型均未实现，不能据此宣称编译或行为通过。

## 五、复核限制

- **只读**：未修改 D1/D2/D3/D4、R2/status/replan/decision 等任何设计或代码/测试；本报告是唯一新增文件。
- **未跑**：未运行 Maven、测试、仿真、门禁；未验证 D4-I 编译、D4-T、W3 支付、`WagePayableInventoryLookup`、`DebtSource`、`SurvivalOutlook` 或任何数值例。
- **未实现**：当前源码仍是旧形状；D4 占用/资产 location、工资链、生存判定、`DebtSource` 均只有设计；未派实现单、未提交/合并/推送/同步主树。

## 六、交账：`git status --porcelain=v1`

```text
?? docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review-2.md
```
