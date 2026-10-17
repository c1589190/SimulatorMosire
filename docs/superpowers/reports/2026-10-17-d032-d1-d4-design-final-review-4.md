# D-032 D1–D4 最终复核（F-07/F-08 修正后，2026-10-17）

> **性质**：只读最终复核。未修改设计/代码/测试，未跑 Maven/测试/仿真，未提交/合并/推送。
>
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
>
> **基线**：`HEAD e0b3ca45`；复核开始时 `git status --porcelain=v1` 为空。
>
> **输入**：`2026-10-17-d032-d1-d4-design-final-review-3.md`、当前 D1–D4/replan/status、当前源码事实。

## 结论

**不通过。**

P0=0，仍有 **P1=1、P2=1**。F-05/F-06 已关闭，F-07 的“D4-C 必选 AssetOccupancyLookup + fail-closed 适配器”大体落地；但 `AssetOccupancySnapshot`/`occupancyOf` 没有 revision 身份，无法实现声称的“同 revision”校验；另外 `WagePayableInventoryLookup` 没有 D4-C 的 fail-closed 适配器，W1/W2b 在 D4-I 前缺少可运行实现。

## 一、F-07/F-08 核对

| 编号 | 判定 | 当前证据与说明 |
|---|---|---|
| F-07 必选接口 | **部分关闭** | `AssetOccupancyLookup` 已列为 D4-C 必选接口（D4 `:541-568,948`；D2 `:558` 已改为只依赖 D4-C；D3 `:1561`）；`UnavailableAssetOccupancyLookup` 返回 `Optional.empty()`，D4-I 有唯一真实实现 `AssetOccupancyLookupImpl`，D4-T 验证 C2 只依赖 D4-C（D4 `:575,950`）。但 revision 身份未进入接口/快照，见 F-09。 |
| F-07 签名/语义 | **部分关闭** | `AssetOccupancySnapshot` 与 `occupancyOf(unit, fromTick, toTickExclusive)` 已给出；`Optional.empty`/空 Map、location/interval mismatch、`asset-occupancy-unavailable`、`asset-occupancy-snapshot-mismatch` 已写（D4 `:546-576,801`；D2 `:558`；D3 `:1202`）。仍缺 revision 字段/参数，见 F-09。 |
| F-08 C2 只依赖 D4-C | **关闭（除 F-09）** | D2 明确 C2 只调用 D4-C `AssetOccupancyLookup.occupancyOf`，不再回退 `usableAssets`（D2 `:558-559`）；D4-C 提供 fail-closed 适配器，D4-I 真实实现，D4-T 验证无第二套实现（D4 `:575,948-950`）。 |
| F-05 组件顺序 | **关闭** | 顺序仍为 `C0/C0-A → D4-C → C2(32) → W2a(33/34) → W1 → W2b(35) → D4-I(36/37) → D4-T → W3`（D4 `:912`；D3 `:1557`；D2 `:786-788`；replan `:86,90`；status `:204,230`）。D4-C 只接口/无状态适配器，D4-I 在 W2b 后落 36/37，D4-T 看最终 37 组件，W3 最后。 |
| F-06 跨地点 overcommit | **关闭** | D4 P18d、负向行、例 J 覆盖 A=600/B=600/余额=1000 ⇒ `inventory-reservation-overcommitted`、零新增预留/零写入（D4 `:265-276,753,790,878-887`）。 |

## 二、发现清单

| ID | 级别 | 问题 | `file:line` 证据 | 影响 | 建议最小修正 | 归属 |
|---|---|---|---|---|---|---|
| F-09 | P1 | `AssetOccupancySnapshot` 和 `occupancyOf` 没有 revision 身份：正文要求 snapshot 来自同一 revision，但记录只有 unit/location/from/to，接口也没有 revision 参数；`asset-occupancy-snapshot-mismatch` 只能检查 unit/location/interval，跨 revision 的同 unit/location/interval 快照仍可通过。 | D4 `asset-occupancy-design.md:546-559`（Snapshot 只有 unit/location/from/to，接口无 revision）；`:573` 写“snapshot 必须来自同一 revision”；`:801` P27 要求“跨 revision/地点/区间 mismatch”；D2 `cooperative-production-design.md:558` 只要求同 revision；当前源码已有 `RevisionId` 类型（`simos-util/src/main/java/io/mosire/simos/util/state/RevisionId.java`）可用 | C2 若缓存/复用上一 revision 的 snapshot，可能用陈旧 allocation 填 probe/execute；R2 的“同 revision”只成文字承诺，D4-T 无法用当前精确签名断言 revision mismatch。 | 给 `AssetOccupancySnapshot` 加 `RevisionId revision`（或让 `occupancyOf` 接收 `RevisionId expectedRevision`），并明确 `revision`、unit、location、interval 任一不匹配 ⇒ ERROR `asset-occupancy-snapshot-mismatch`；`UnavailableAssetOccupancyLookup` 返回 empty；补跨 revision 负向判据。 | D4/D2 |
| F-10 | P2 | `WagePayableInventoryLookup` 只有 D4-C 接口，没有像 `UnavailableAssetOccupancyLookup` 那样的 fail-closed 适配器；但 W1/W2b 在 D4-I 之前消费接口（D4-C→C2→W2a→W1→W2b→D4-I），没有可运行实现。 | D4-C owner 只列 `UnavailableAssetOccupancyLookup`（D4 `:948`）；`WagePayableInventoryLookup` 接口见 `:581-585`；D3 W1/W2b 只消费接口（D3 `:1563-1565`）；顺序 `:912,1557` 把 W1/W2b 放在 D4-I 前 | W1/W2b 可编译但无法在 D4-I 前运行：可能注入 null、临时匿名实现或直接把 lookup 当 0，违反“不得第二套空实现/不得回退原始余额”。 | D4-C 增加 `UnavailableWagePayableInventoryLookup`（返回 `Optional.empty()`），W1/W2b 用它 fail-closed 为 `wage-inventory-scope-unavailable`；D4-I 后替换为 `WagePayableInventoryLookupImpl`；D4-T 验证 W1/W2b 在 D4-I 前只得到 fail-closed 读数。 | D4/D3 |

## 三、跨文档重点

### 3.1 组件号与批次顺序

F-05 已关闭：`C2=32 → W2a=33/34 → W2b=35 → D4-I=36/37`，D4-C 不落状态，D4-T 在 D4-I 后，W3 最后；D2/D3/D4/replan/status 当前有效文字一致。当前源码仍是 31 组件（`EconomyData.java:310-397`；`EconomyRoundTripTest.java:549`），新增组件尚未实现。

### 3.2 时间尺度

F-04 仍关闭：`NextTickIncomeProjection` 由 W2b 唯一实现，只输出下一 tick 可交付量；D2 周期 `×cycleDays` 预计不得直接填入 D3 `expected*`（D2 `:529`；D3 `:498-506,1449`）。未发现新的时间口径矛盾。

### 3.3 idle / allowIdle

D1–D4 有效正文没有 `allowIdle`/`householdLaborChoices` 状态/命令/seed；`idleEligible`/`idleChosen` 及默认/替代策略仍一致。未发现新的有效 `allowIdle` 判据。

### 3.4 D4 / R2 / W3

N-01/N-02/N-03/N-04/N-05 与 F-01/F-03/F-04 仍关闭：全地点预留扣减、payer snapshot 覆盖已有+候选、`ShareTuple` location、`incomeAvailableAtTick`、`DebtSource` 写入点均有正文与判据。D4 仍未关闭 R2；W3 仍最后。剩余问题是 F-09（AssetOccupancySnapshot revision）与 F-10（WagePayable fail-closed 适配器）。

### 3.5 抵物折价与模块边界

未发现 `LOCAL_LAST_TRADE`/`referencePriceOf`/`SettlementLocationRef` 残留；D1/D3 仍只允许 `LOCAL_BID`/`LOCAL_ASK`，缺价/0 价/折后 0/缺币种 skip 留债、系数可 `<1`，production 定义/economy 执行边界一致。`OwnershipStake`/`InventoryReservation`/`AssetOccupancyGrant` 仍不是第二本资产/库存数量账；模块边界方向未发现新冲突。

## 四、已核无问题项

以下项已核，未发现额外问题（仅静态复核，不是实现/测试通过）：

1. **F-05**：组件顺序严格为 C2=32 → W2a=33/34 → W2b=35 → D4-I=36/37；D4-C 只接口，D4-T 在 D4-I 后，W3 最后。
2. **F-06**：P18d、负向行与例 J 覆盖 A=600/B=600/余额=1000 的 `inventory-reservation-overcommitted`，且要求零新增预留/零写入。
3. **F-07 的接口主体**：`AssetOccupancyLookup` 已是 D4-C 必选接口；`AssetOccupancySnapshot`/`occupancyOf`、`UnavailableAssetOccupancyLookup`、D4-I `AssetOccupancyLookupImpl`、D4-T 验证链条已写清；C2 不再回退旧 `usableAssets`。
4. **F-08 的 empty 语义**：`Optional.empty` = 不可用，空 Map = 合法零占用；location/interval mismatch 与 `asset-occupancy-snapshot-mismatch` 已写。
5. **N-01/N-03/N-04/N-05**：全地点预留扣减、`ShareTuple` location、`incomeAvailableAtTick`、`DebtSource` 写入点/P29b 无回归。
6. **组件与用户口径**：当前 31 组件基线；D2/D3/D4 的 32–37 编号文本一致；U22/U23 原话、idle 撤回、`TOOL` 退役、折价词表未见有效残留。
7. **源码事实**：`OwnershipStakeBook.ShareTuple` 旧源码仍无 location；`AccountSession` 户级无 location 级库存；`MarketTable` 有 `hasPrice`/bid/ask；`AssetOccupancyLookup`/`WagePayableInventoryLookup`/D4 新类型仍未实现。本报告不据此宣称编译或行为通过。

## 五、复核限制

- **只读**：未修改 D1/D2/D3/D4、R2/status/replan/decision 等任何设计或代码/测试；本报告是唯一新增文件。
- **未跑**：未运行 Maven、测试、仿真、门禁；未验证 D4-I 编译、D4-T、W3 支付、`WagePayableInventoryLookup`/`AssetOccupancyLookup`、`DebtSource`、`SurvivalOutlook` 或任何数值例。
- **未实现**：当前源码仍是旧形状；D4 占用/资产 location、工资链、生存判定、`DebtSource` 均只有设计；未派实现单、未提交/合并/推送/同步主树。

## 六、交账：`git status --porcelain=v1`

```text
?? docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review-4.md
```
