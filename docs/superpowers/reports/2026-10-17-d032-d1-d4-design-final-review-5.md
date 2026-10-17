# D-032 D1–D4 最终复核（F-09/F-10 修正后，2026-10-17）

> **性质**：只读最终复核。未修改设计/代码/测试，未跑 Maven/测试/仿真，未提交/合并/推送。
>
> **工作树**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
>
> **基线**：`HEAD 59a17b6c`；复核开始时 `git status --porcelain=v1` 为空。
>
> **输入**：`2026-10-17-d032-d1-d4-design-final-review-4.md`、当前 D1–D4/replan/status、当前源码事实。

## 结论

**通过（设计层）。**

P0=0、P1=0、P2=0。F-09/F-10 已按前次报告的精确要求闭环：

- `AssetOccupancySnapshot` 带 `RevisionId`，`occupancyOf(unit, expectedRevision, from, to)` 精确；revision/unit/location/interval 任一不匹配均 `asset-occupancy-snapshot-mismatch`；
- D4-C 同时提供 `UnavailableAssetOccupancyLookup` 与 `UnavailableWagePayableInventoryLookup`；W1/W2b 在 D4-I 前可编译并按各自词表 fail-closed；D4-I 唯一真实实现、D4-T 验证 C2/W1/W2b 只依赖 D4-C；
- 组件顺序仍为 `C2=32 → W2a=33/34 → W2b=35 → D4-I=36/37`，D4-C 只接口/无状态适配器。

本轮仍只表示**设计可冻结**，不表示代码已实现、已编译或已通过测试。

## 一、F-09/F-10 核对

| 编号 | 判定 | 当前证据与说明 |
|---|---|---|
| F-09 revision 身份 | **关闭** | `AssetOccupancySnapshot` 已含 `io.mosire.simos.util.state.RevisionId revision`（`asset-occupancy-design.md:546-554`）；`AssetOccupancyLookup.occupancyOf` 已改为 `(unit, expectedRevision, fromTick, toTickExclusive)`（`:556-561`）；`snapshot.revision != expectedRevision`、unit/location/interval 任一不匹配均 ERROR `asset-occupancy-snapshot-mismatch`（`:580,681`）；P27 覆盖跨 revision/地点/区间（`:818`）。 |
| F-09 跨 revision 拒绝 | **关闭** | 正文明确“任何跨 revision 的同地点同区间 snapshot 不得通过”（`asset-occupancy-design.md:580`）；D2 同步 `occupancyOf(unit, expectedRevision, fromTick, toTickExclusive)` 与 mismatch ERROR（`cooperative-production-design.md:558`）；D4-C owner 明确 RevisionId 精确签名（`:965`）。 |
| F-10 wage fail-closed | **关闭** | D4-C 新增 `UnavailableWagePayableInventoryLookup` 返回 `Optional.empty()`（`asset-occupancy-design.md:595-602`）；D4-C owner 与 D2/D3 D4-C owner 均列入必选接口/适配器（D4 `:965`；D2 `:783`；D3 `:1565`）；W1/W2b 在 D4-I 前只注入该适配器，wage empty ⇒ `wage-inventory-scope-unavailable`（D4 `:583,818`；D3 `:1200,1439`），不回退账户余额。 |
| F-10 唯一真实实现/测试 | **关闭** | D4-I 拥有 `WagePayableInventoryLookupImpl`/`AssetOccupancyLookupImpl` 的唯一真实实现（D4 `:966`；D2 `:784`；D3 `:1562`）；D4-T 验 C2/W1/W2b 只依赖 D4-C 接口、D4 未接时只得到 `Unavailable*`，D4-I 接手后无第二套实现（D4 `:967`）。 |
| F-10 词表不混用 | **关闭** | W1/W2b wage empty ⇒ `wage-inventory-scope-unavailable`；C2 asset empty ⇒ `asset-occupancy-unavailable`，正文明确两者不混用（D2 `:558`；D3 `:1200`；D4 `:818`、P38c `:1439`）。 |

## 二、F-05/F-06 与既有修正回归

| 项 | 判定 | 证据 |
|---|---|---|
| F-05 组件顺序 | **关闭且无回归** | `C2=32 → W2a=33/34 → W2b=35 → D4-I=36/37`；D4-C 只接口；D4-T 在 D4-I 后看最终 37；W3 最后（D4 `:912`；D3 `:1557`；D2 `:786-788`；replan `:86,90`；status `:204,230`）。 |
| F-06 跨地点 overcommit | **关闭且无回归** | P18d、负向行、例 J 覆盖 A=600/B=600/余额=1000 ⇒ `inventory-reservation-overcommitted`、零新增预留/零写入（D4 `:265-276,753,790,878-887`）。 |
| N-01/N-02/N-03/N-04/N-05 | **无回归** | lookup 全地点 ACTIVE 预留 + frozen 扣减；`WagePayerLocationSnapshot` 含已有+候选地点；`ShareTuple` 含 location；`incomeAvailableAtTick`/`NextTickIncomeProjection`；`DebtSource` 全写入点仍有效。 |
| 组件与用户口径 | **无回归** | 当前 `EconomyData` 31 组件；D2/D3/D4 的 32–37 编号文本一致；U22/U23 原话、`allowIdle` 撤回、`TOOL` 退役、`LOCAL_BID`/`LOCAL_ASK` 词表未见有效残留。 |

## 三、发现清单

**本轮未发现 P0/P1/P2。**

前次 F-09/F-10 的两条实质问题均已关闭；没有发现新的跨文档矛盾、组件顺序回退、接口 owner 冲突或 W3/D4-I 依赖缺口。

## 四、已核无问题项

1. **F-09**：`AssetOccupancySnapshot` 含 `RevisionId`；`occupancyOf` 含 `expectedRevision`；revision/unit/location/interval mismatch 统一 `asset-occupancy-snapshot-mismatch`；P27 有正向/负向判据。
2. **F-10**：`UnavailableWagePayableInventoryLookup` 返回 `Optional.empty()`；W1/W2b 在 D4-I 前可编译并 fail-closed；D4-I 唯一真实实现；D4-T 验证 C2/W1/W2b 只依赖 D4-C；wage/asset empty 词表分离。
3. **F-05**：D4-C 只接口/无状态适配器；C2 落 32、W2a 落 33/34、W2b 落 35、D4-I 落 36/37、D4-T 后 W3。
4. **F-06**：P18d/例 J 的跨地点 overcommit 判据完整。
5. **边界**：未发现有效 `allowIdle`/`householdLaborChoices`、有效 `TOOL` 词表、`LOCAL_LAST_TRADE`/`referencePriceOf`/`SettlementLocationRef` 残留；`OwnershipStake`/`InventoryReservation`/`AssetOccupancyGrant` 仍不是第二本资产/库存数量账。
6. **源码事实**：`EconomyData` 仍为 31 组件；`OwnershipStakeBook.ShareTuple` 旧源码仍无 location；`AccountSession` 户级无 location 级库存；`MarketTable` 有 `hasPrice`/bid/ask；`AssetOccupancyLookup`/`WagePayableInventoryLookup`/D4 新类型仍未实现。本报告不据此宣称编译或行为通过。

## 五、复核限制

- **只读**：未修改 D1/D2/D3/D4、R2/status/replan/decision 等任何设计或代码/测试；本报告是唯一新增文件。
- **未跑**：未运行 Maven、测试、仿真、门禁；未验证 D4-I 编译、D4-T、W3 支付、`WagePayableInventoryLookup`/`AssetOccupancyLookup`、`DebtSource`、`SurvivalOutlook` 或任何数值例。
- **未实现**：当前源码仍是旧形状；D4 占用/资产 location、工资链、生存判定、`DebtSource` 均只有设计；未派实现单、未提交/合并/推送/同步主树。
- **状态**：本报告结论仅用于 D1–D4 **设计冻结**，不替代 C0/C0-A/D4-C/D4-I 的编译证据或 D4-T/W5 的独立测试证据。

## 六、交账：`git status --porcelain=v1`

```text
?? docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review-5.md
```
