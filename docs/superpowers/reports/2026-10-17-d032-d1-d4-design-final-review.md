# D-032 D1–D4 第三次（终局）独立设计复核（2026-10-17）

> **性质**：只读终局复核。未修改设计/代码/测试，未跑 Maven/测试/仿真，未提交/合并/推送。
>
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
>
> **基线**：`HEAD a33a75af`；复核开始时 `git status --porcelain=v1` 为空。
>
> **输入**：`docs/superpowers/reports/2026-10-17-d032-d1-d4-design-rereview.md`（第二次报告）、当前 D1–D4、replan/status，以及当前源码事实。

## 结论

**不通过。**

P0=0，仍有 **P1=2、P2=2**。第二次报告的 N-01/N-03/N-04/N-05 已在设计文字上实质关闭；N-02 只完成了“加上 `WagePayerLocationPrecheck`”，但其输入范围、D3 不变量和 W1/W2b→D4-I 的接口/批次依赖仍未闭合。当前仍不能按“可派 D4-I/W3”冻结。

## 一、上一轮 N-01..N-05 与 P2 核对

| 编号 | 判定 | 当前证据与说明 |
|---|---|---|
| N-01 | **关闭** | D4 的 `InventoryReservation` overcommit 已改为 payer×commodity **全地点** ACTIVE 预留 + frozen 对户级余额校验（`asset-occupancy-design.md:265-276`）；`WagePayableInventoryLookup` 已按 payer×commodity 汇总所有地点预留（`:572-585`）；P13/P18c/P38b 已覆盖跨地点预留。 |
| N-02 | **部分关闭** | 已新增 `WagePayerLocationPrecheck` 并声明 W1/W2b/W3 共享（`wage-chain-design.md:1023,1150,1174-1191`）；但输入只取“当前操作涉及”的地点，未覆盖 payer 现有其他 unit/义务；D3 不变量 D3-I3/I25 仍只写 W3；W1/W2b 与 D4-I 的接口/批次依赖也未闭合。见 F-01/F-02/F-03。 |
| N-03 | **关闭** | D4 已明确 `ShareTuple` 加 `location`，并逐个要求 `ShareTuple`/`buildTupleIndex`/Move/split/rebuild 使用 source location（`asset-occupancy-design.md:197-199`；D4-I owner `:894`；附件 P24b `:756`）。当前源码 `OwnershipStakeBook.java:247-252,371-374,486-489` 仍是旧形状，但设计义务已写清；实现尚未发生。 |
| N-04 | **关闭** | D3 已给 `LivelihoodAlternative` 增加 `incomeAvailableAtTick`，只接受 `<= nextTickNeed.forTick` 的收入（`wage-chain-design.md:417-424,481-489`）；D2 明确周期预计不是下一 tick 输入（`cooperative-production-design.md:529`）；P49 覆盖整季 product share 不得当下一 tick 口粮。 |
| N-05 | **关闭** | D3 已列出 `UnitBorrowHandler`、`MoneyMarketSettlement`、`MarketSettlement`、`MintSettlement`、`EconomySettlement`、`ModeMigrationSettlement`、`LotMigrationBook`、`EconomyPayloads`/`EconomySeedHandler`、codec/ChangeSet/upsert 的 source 义务（`wage-chain-design.md:647-657,1523`；P29b `:1388`）；`LEGACY_UNKNOWN` 不自动 0‰、迁移保 source、普通贷款保原 terms 已写明。 |
| N-06（P2） | **关闭** | D3 已把“D4 正文仍未完成”改为“D4 设计已落盘、lookup 未实现”（`:1148`），并声明 replan 已同步（`:1512`）。 |
| N-07（P2） | **关闭** | D1 U14/U15 引文已去掉控制方括注，控制方说明已移到引文外（`production-definition-v2-freeze.md:194-206`）。 |
| N-08（P2） | **部分关闭** | 已增加 `C0/C0-A → C2 → W2a → W1 → W2b → D4-I → D4-T → W3` 的共享 economy 文件交接（`asset-occupancy-design.md:899`；`wage-chain-design.md:1516`；replan `:85-88`），但该顺序与 W1/W2b 消费 D4-I 的 `WagePayableInventoryLookup` 形成新的依赖冲突，见 F-02。 |
| N-09（P2） | **关闭** | D3 已增加 `maxCombinations` 与 `survival-combination-limit`（`wage-chain-design.md:391-399,481,1388,1404`）；超过上限不返回截断 best/idleEligible。 |

R-01..R-18 的上一轮“关闭/基本关闭”状态未发现回归：D4 `SMALL_TOOL`/`LARGE_TOOL`、D2-I2、32–35 与 36/37、D1 毫小时/包名/`UNALLOCATED`、裁决策略历史标记等仍保持有效。

## 二、发现清单

| ID | 级别 | 问题 | `file:line` 证据 | 影响 | 建议最小修正 | 归属 |
|---|---|---|---|---|---|---|
| F-01 | P1 | `WagePayerLocationPrecheck` 的输入只包含“当前操作涉及”的 settlement location；W1/W2b 的候选通常只给当前 candidate/unit 地点，不会自动纳入同一 payer 已有的其他 unit/工资义务。因此候选可能通过预检，实际 W3 才整组拒绝，仍会出现“候选可估、实际不可付”的预计/实际脱节。 | `wage-chain-design.md:1188-1191`（输入定义：“当前操作涉及”；W1 用 candidate/instance 当前 posting/default 地点，W2b 用匹配候选 unit 地点）；`:1023`（W1/W2b 候选不可行/skip）；`:1402` P39b；`asset-occupancy-design.md:555`（“当前操作涉及”）；D2 允许同一户出现在多个 unit 名册（`cooperative-production-design.md:610`） | 同一 payer 已有 unit A（location A），W1/W2b 评估 candidate B（location B）时，locations={B} → 预检 OK；实际 W3 汇总 A+B 后才 `wage-payer-location-unsupported`。候选成本/排序/预计利润与实际结算不一致，违反 N-02 的“候选不可行”目标。 | 把 `WagePayerLocationCheck` 的输入改为 **payer 当前完整已知 unit/工资义务地点集合 + 当前 candidate**（由调用方从同一状态快照提供），W1/W2b/W3 共用同一集合；补 P26b：已有 unit A + candidate B ⇒ W1/W2b 在候选阶段即 `wage-payer-location-unsupported`/skip，且零 lookup。 | D3/D4 |
| F-02 | P1 | 共享 economy 文件交接顺序把 `D4-I` 放在 `W2b` 之后，但 D4 又要求 W1/W2b 消费 D4-I 唯一新增/实现的 `WagePayableInventoryLookup`；W2a 的 owner 表只新增 `WagePayerLocationPrecheck`，没有提前定义 lookup 接口。W1/W2b 在 D4-I 之前无法按“只消费 D4-I 已编译读口”编译，除非另造临时/重复接口。 | `asset-occupancy-design.md:553-557`（W1/W2b/W3 只消费 D4-I 的 lookup；D4-I 是唯一新增/实现 owner）；`:894`（D4-I owner）；`:899`（D4-I 在 W2b 之后）；D3 `wage-chain-design.md:1157`（预计/排队/执行使用同一 lookup）；D3 owner W1/W2b `:1521-1522` 没有 `WagePayableInventoryLookup` 写面/接口定义 | 批次顺序与依赖矛盾：W1/W2b 要么在 D4-I 前编译失败，要么自行复制接口/空实现，违反 R-16/N-08 的唯一实现与一文件一 owner。 | 三选一并写死：① 将 `WagePayableInventoryLookup` 的**接口契约**前移到 W2a（或独立 shared-api owner），D4-I 只实现；② 把 D4-I 调到 W1/W2b 之前；③ 明确 W1/W2b 只需 `WagePayerLocationPrecheck`、删除其 lookup 消费声明。同步修 owner 表、编译命令与 P39b。 | D3/D4/控制方 |
| F-03 | P2 | D3 的不变量 D3-I3 与 D3-I25 仍只写 W3 的 payer 预检，和 §6.2/§6.9.1/P39b 的 W1/W2b/W3 共享预检不一致；测试/实现若按不变量表取义会漏掉 W1/W2b。 | `wage-chain-design.md:1331`（D3-I3：W3 …）；`:1353`（D3-I25：W3 …）；对照 `:1023`（W1/W2b/W3 共享）、`:1188-1191`（共享 precheck）、`:1402` P39b | 不变量是测试 owner 的主要判据来源；只写 W3 会让 W1/W2b 的候选 skip 没有强制约束，N-02 在测试面未闭合。 | 把 D3-I3/I25 改为“W1/W2b 候选不可行/skip、W3 整组拒绝；均零 lookup”，并引用 `WagePayerLocationPrecheck`。 | D3 |
| F-04 | P2 | D2 周期预计到 D3 下一 tick 的 adapter 只有一句“W2b/D3 adapter 必须换算”，没有明确 adapter 类型、落点、owner 和失败语义；实现者可自行决定 `incomeAvailableAtTick`，甚至把 cycle 总值填进去。 | `cooperative-production-design.md:529`（只说 W2b/D3 adapter）；D3 `wage-chain-design.md:421-424`（字段必须显式）、`:481-489`（只按 tick 过滤）；D3 W2b owner `:1522` 只列 SurvivalOutlook 文件，没有 adapter/转换 owner | N-04 的设计方向正确，但 D2→D3 的转换契约未落到可派单的文件/接口；`incomeAvailableAtTick` 的调用方来源不确定，仍可能伪造/误填周期值。 | 在 W2b owner 中明确新增 `CycleIncomeToTickAdapter`（或等价 helper）及其输入/输出/缺输入语义：D2 cycle 预计 → 每个 `LivelihoodAlternative` 的 tick 可交付量；周期末 product share 固定 `cycleEnd`，不得直接复制；补 P21 的 adapter 级判据。 | D2/D3 |

## 三、跨文档矛盾重点

### 3.1 组件号

未发现回退。当前 `EconomyData` 源码仍为 31 组件（`EconomyData.java:310-397`，`EconomyRoundTripTest.java:549`），D2 的 32、D3 的 33/34/35、D4 的 36/37 在有效正文一致；未再出现 40/41/42 作为有效组件号，也未把 36 让给 `allowIdle`。

### 3.2 时间尺度

A1 候选窗口 `cycleDays` 与实际 due 不乘 `cycleDays` 仍一致。D3 新增 `incomeAvailableAtTick` 后，下一 tick 生存预测的方向与 D2 的周期预计已能区分（N-04 关闭）；剩余问题是 D2→D3 adapter 未落 owner/接口（F-04），以及 D3 不变量未同步 N-02（F-03）。

### 3.3 idle / allowIdle

D1–D4 有效正文没有 `allowIdle`/`householdLaborChoices` 状态/命令/seed；D3 的 `idleEligible`/`idleChosen`、默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET` 与 `BEST_EFFORT_WORK` 仍一致。裁决文件的 U-D3-2/U-D3-3 已标历史；未发现新的有效 `allowIdle` 判据。

### 3.4 D4 / R2 / W3

D4 对 R2 的“未关闭”表述、五项退役门、`LAND=100` 原复现与 D4-I/D4-T 边界仍保留；W3 的 payer 地点预检、lookup owner、`Optional.empty()`/空 `Map`、W3 不二次扣减、N-01 全地点预留扣除已写入。剩余实质问题是 **F-01**（预检输入不覆盖 payer 已有其他 unit）和 **F-02**（W1/W2b 与 D4-I 接口/批次顺序冲突）；F-03 是测试不变量未同步。

### 3.5 抵物折价与模块边界

D1/D3 仍只允许 `LOCAL_BID`/`LOCAL_ASK`，未发现 `LOCAL_LAST_TRADE`/`referencePriceOf`/`SettlementLocationRef` 残留；缺价/0 价/折后 0/缺币种 skip 留债、系数可 `<1`、production 定义/economy 执行边界保持一致。`OwnershipStake`/`InventoryReservation`/`AssetOccupancyGrant` 仍明确不是第二本资产/库存数量账；模块边界方向未发现新冲突。

## 四、已核无问题项

以下项已核，未发现额外问题（仅静态复核，不是实现/测试通过）：

1. **N-01**：`InventoryReservation` overcommit 与 `WagePayableInventoryLookup` 已按 payer×commodity 全地点汇总；P13/P18c/P38b 覆盖跨地点预留。
2. **N-03**：D4 已要求 `ShareTuple`/`buildTupleIndex`/Move/split/rebuild 加 location；P24b 有 A/B 同 tuple 不得合并判据。
3. **N-04**：`LivelihoodAlternative.incomeAvailableAtTick` 与 `<= nextTick` 过滤已落地设计；D2 明确周期预计不得直接当下一 tick 输入。
4. **N-05**：`DebtSource` 的工资债 0‰、普通贷款原 terms、迁移保 source、seed/codec 必填 source 与 P29b 已写清；`LEGACY_UNKNOWN` 不自动冒充工资债。
5. **上一轮 P2**：D3 旧“D4 未完成/不改 replan”句已修正；D1 U14/U15 引文括注已移出；D4-I 与 C2/W2a/W2b 的顺序已写；`SurvivalOutlook` 已有 `maxCombinations`/`survival-combination-limit`。
6. **组件号/U23 原话**：D1/D2/D3/D4 的组件号、U22/U23 逐字原话、D1 U14/U15、D4 U8/U16/U21/U22/U23 均与当前文档/来源一致。
7. **工作树事实**：当前 `EconomyData` 31 组件；`OwnershipStake` 无 location（旧源码）；`OwnershipStakeBook.ShareTuple` 无 location（旧源码）；`AccountSession` 户级无 location 级库存；`MarketTable` 有 `hasPrice`/bid/ask；D2/D3/D4 新增类型仍未实现；本报告未据此宣称编译/行为通过。

## 五、复核限制

- **只读**：未修改 D1/D2/D3/D4、R2/status/replan/decision 等任何设计或代码/测试；本报告是唯一新增文件。
- **未跑**：未运行 Maven、测试、仿真、门禁；未验证 D4-I 编译、W3 支付、`WagePayableInventoryLookup`、`DebtSource`、`SurvivalOutlook` 或任何数值例。
- **未实现**：当前源码仍是旧形状；D4 occupancy/资产 location、工资链、生存判定、`DebtSource` 均只有设计；未派实现单、未提交/合并/推送/同步主树。

## 六、交账：`git status --porcelain=v1`

```text
?? docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review.md
```
