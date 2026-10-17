# D-032 D1–D4 最终复核（F-05/F-06 修正后，2026-10-17）

> **性质**：只读最终复核。未修改设计/代码/测试，未跑 Maven/测试/仿真，未提交/合并/推送。
>
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
>
> **基线**：`HEAD 018f43b1`；复核开始时 `git status --porcelain=v1` 为空。
>
> **输入**：上一份 `2026-10-17-d032-d1-d4-design-final-review-2.md`、当前 D1–D4/replan/status、当前源码事实。

## 结论

**不通过。**

P0=0，仍有 **P1=1、P2=1**。F-05 的组件落形顺序和 F-06 的 P18d 已修正；但 D4-C 只把 `WagePayableInventoryLookup` 定为必选接口，C2 实际需要的 `AssetOccupancyLookup` 仍写成“必要时可选”，且没有对应实现 owner / fail-closed 适配器。C2 在 D4-C→C2 的顺序下仍无法按单一接口编译或运行。

## 一、F-05/F-06 核对

| 编号 | 判定 | 当前证据与说明 |
|---|---|---|
| F-05（组件落形顺序） | **关闭** | 当前统一顺序为 `C0/C0-A → D4-C → C2(32) → W2a(33/34) → W1 → W2b(35) → D4-I(36/37) → D4-T → W3`（D4 `:912`；D3 `:1557`；D2 `:786-788`；replan `:86,90`；status `:204,230`）。D4-C 只新增接口、不落状态；D4-I 在 W2b 后落 36/37；D4-T 在 D4-I 后看最终 37 组件；W3 最后。组件号未发现回退。 |
| F-06（跨地点 overcommit） | **关闭** | D4 正文要求 payer×commodity 全地点 ACTIVE + frozen ≤ 户级余额（`asset-occupancy-design.md:265-276`）；新增 P18d（`:753`）明确 A=600、B=600、余额=1000 ⇒ `inventory-reservation-overcommitted`、零新增预留/零写入；负向行（`:790`）与例 J（`:878-887`）一致。 |
| F-05 附带 `AssetOccupancyLookup` | **未关闭** | D4-C 只把 `WagePayableInventoryLookup.java` 写成必选，`AssetOccupancyLookup.java` 仍写“如 C2/W1/W2b 确需，另可新增”（D4 `:906`）；但 D2 明确 C2 的 `assetOccupancy` 由 `AssetOccupancyLookup` 提供（D2 `:558-559`），C2 owner 也在 D2 `:780`。这成为 F-07。 |

## 二、发现清单

| ID | 级别 | 问题 | `file:line` 证据 | 影响 | 建议最小修正 | 归属 |
|---|---|---|---|---|---|---|
| F-07 | P1 | D4-C 没有把 C2 必需的 `AssetOccupancyLookup` 定为必选接口，也没有实现 owner / fail-closed 适配器；C2 在 D4-C 后、D4-I 前无法获得单一资产占用读口。 | D2 `cooperative-production-design.md:558-559` 明确 C2 使用 app/D4 的 `AssetOccupancyLookup`；C2 owner `:780`；D4-C owner 写“如 C2/W1/W2b 确需，另可新增 `AssetOccupancyLookup.java`”（D4 `:906`）；D4-I owner 表只有 `AssetOccupancyBook`，没有 `AssetOccupancyLookup` 实现（`:907`）；D4 `§8.4` 仍只列内部读口 `AssetOccupancyLookup`（`:691`） | C2 只拿到 D4-C 时没有 `AssetOccupancyLookup` 类型或实现：要么 C2 自行复制一个临时接口/旧 `usableAssets` 适配器（违反唯一读口与文件 owner），要么不能编译/运行。D4-C 前的顺序修好了 `WagePayableInventoryLookup`，但 C2 的资产占用依赖仍未闭合。 | 把 `AssetOccupancyLookup` 明确列为 **D4-C 必选只读接口**（不能“必要时可选”），冻结其输入/输出与缺失语义；在 D4-C 提供不落状态的 fail-closed 适配器（D4 未接时返回 `asset-occupancy-unavailable`），或让 C2 显式接收 `Optional.empty` 的同一接口。D4-I 唯一实现真实读口；D4-T 验证 C2 只依赖 D4-C 接口、D4-I 接手后无第二套实现。 | D4/D2 |
| F-08 | P2 | 即使把 `AssetOccupancyLookup` 加入 D4-C，当前也只冻结了 C2 的“输入形状 = `Map<ProductionUnitId, Map<AssetKind, Long>>`”，没有接口方法、`Optional.empty`/空 `Map`、revision 口径和 `asset-occupancy-unavailable` 的精确边界；D4-C 可被实现成不同签名。 | D2 `cooperative-production-design.md:558-559`（只列 Map 输入形状）；D4 `asset-occupancy-design.md:691`（内部读口只列名称）；D4-C owner `:906` 没有方法契约；D4 `§2` 事实 `:88` 表明当前 `AccountSession` 无资产占用表 | C2、W1/W2b 可能各自猜 `AssetOccupancyLookup` 的返回类型/缺失语义，导致同一 `assetOccupancy` 在预计、排队、执行之间不可同源；D4-T 也无法按唯一契约写负向判据。 | 在 D4-C 冻结类似 `Optional<Map<ProductionUnitId, Map<AssetKind, Long>>> lookup(...)` 的精确只读契约，明确 `Optional.empty` = 不可用、空 Map = 可用但零占用、revision/地点口径和 `asset-occupancy-unavailable`；D4-I 实现、D4-T 验收。 | D4/D2 |

## 三、跨文档重点

### 3.1 组件号与批次顺序

F-05 已实质关闭：D4-C 只接口、C2 落 32、W2a 落 33/34、W2b 落 35、D4-I 落 36/37、D4-T 在 D4-I 后、W3 最后；D2/D3/D4/replan/status 的有效文字一致。当前源码仍是 31 组件（`EconomyData.java:310-397`；`EconomyRoundTripTest.java:549`），未实现新增组件。

### 3.2 时间尺度

F-04 已关闭：`NextTickIncomeProjection` 由 W2b 唯一实现，输入 cycle 起止/quote/target/劳动计划，输出仅下一 tick 可交付量；D2 周期 `×cycleDays` 产品份额不得直接填入 D3 `expected*`（D2 `:529`；D3 `:498-506,1449`）。未发现新的时间口径矛盾。

### 3.3 idle / allowIdle

D1–D4 有效正文没有 `allowIdle`/`householdLaborChoices` 状态/命令/seed；`idleEligible`/`idleChosen` 与默认/替代策略仍一致。未发现新的有效 `allowIdle` 判据。

### 3.4 D4 / R2 / W3

N-01/N-02/N-03/N-04/N-05 与 F-01/F-03/F-04 均保持关闭：lookup 全地点预留扣减、payer snapshot 覆盖已有+候选、`ShareTuple`/location、`incomeAvailableAtTick`、`DebtSource` 写入点均有当前正文与判据。D4 仍未关闭 R2；W3 仍最后运行。剩余问题是 C2 需要的 `AssetOccupancyLookup` 未成为 D4-C 必选接口（F-07/F-08）。

### 3.5 抵物折价与模块边界

未发现 `LOCAL_LAST_TRADE`/`referencePriceOf`/`SettlementLocationRef` 残留；D1/D3 仍只允许 `LOCAL_BID`/`LOCAL_ASK`，缺价/0 价/折后 0/缺币种 skip 留债、系数可 `<1`，production 定义/economy 执行边界一致。`OwnershipStake`/`InventoryReservation`/`AssetOccupancyGrant` 仍不是第二本资产/库存数量账；模块边界方向未发现新冲突。

## 四、已核无问题项

以下项已核，未发现额外问题（仅静态复核，不是实现/测试通过）：

1. **F-05**：组件落形严格为 C2=32 → W2a=33/34 → W2b=35 → D4-I=36/37；D4-C 不落状态，D4-T 在 D4-I 后，W3 最后。
2. **F-06**：P18d、负向行与例 J 均覆盖 A=600/B=600/余额=1000 的 `inventory-reservation-overcommitted`，且要求零新增预留、零写入。
3. **F-01**：`WagePayerLocationSnapshot` 明确由已有 unit/roster/posting 地点 ∪ 候选/本次地点组成；W1/W2b/W3 共用，P39b 覆盖 A+B 候选阶段失败。
4. **F-03**：D3-I3/I25 已改为 W1/W2b/W3 共享 snapshot。
5. **F-04**：`NextTickIncomeProjection` 类型/owner/输入/输出/失败语义与 P51 完整；周期末 product share 不得当下一 tick 可交付量。
6. **N-01/N-03/N-04/N-05**：全地点预留扣减、`ShareTuple` location、`incomeAvailableAtTick`、`DebtSource` 普通贷款/迁移/seed/codec 写入点与 P29b 无回归。
7. **组件与用户口径**：当前 31 组件基线；D2/D3/D4 的 32–37 编号文本一致；U22/U23 原话、idle 撤回、`TOOL` 退役、折价词表未见有效残留。
8. **源码事实**：`OwnershipStakeBook.ShareTuple` 旧源码仍无 location；`AccountSession` 户级无 location 级库存；`MarketTable` 有 `hasPrice`/bid/ask；`AssetOccupancyLookup`/`WagePayableInventoryLookup`/D4 新类型仍未实现。本报告不据此宣称编译或行为通过。

## 五、复核限制

- **只读**：未修改 D1/D2/D3/D4、R2/status/replan/decision 等任何设计或代码/测试；本报告是唯一新增文件。
- **未跑**：未运行 Maven、测试、仿真、门禁；未验证 D4-I 编译、D4-T、W3 支付、`WagePayableInventoryLookup`/`AssetOccupancyLookup`、`DebtSource`、`SurvivalOutlook` 或任何数值例。
- **未实现**：当前源码仍是旧形状；D4 占用/资产 location、工资链、生存判定、`DebtSource` 均只有设计；未派实现单、未提交/合并/推送/同步主树。

## 六、交账：`git status --porcelain=v1`

```text
?? docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review-3.md
```
