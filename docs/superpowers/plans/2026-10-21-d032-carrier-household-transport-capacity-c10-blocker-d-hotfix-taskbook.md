# D-032 C10 blocker D hotfix：派生市场区锚价 + Timeline HexCoord key（2026-10-21）

> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> 上游：C10 全量 test 剩 2 个 main blocker（C7）。
> 只修这两个 main 缺陷；不写/跑测试，不 commit/push，不碰主树。

## D1：派生市场区锚价失败

实测：`TransportPriceAnchorBook.recordMarketRound` 在 `marketZones` 为空、市场区由
`marketTables` 派生（`single-region`）时抛 `transport-price-anchor-zone-missing`。

冻结修法：

- `recordMarketRound`：先查持久 `marketZones`；缺时若 `marketTables` 含该 zone，则按派生区支持：
  - currency = table.numeraire；
  - alpha = `MarketZone.DEFAULT_PRICE_ALPHA_PER_MILLE`（派生区无 MarketZone 记录）；
  - 跳过 legalTender 比对（无 MarketZone）；
- 若 `marketTables` 也无该 zone ⇒ 仍 fail-closed；
- `anchorOf` 同理只需 table；持久区原路径逐值不变；
- 不改 D/S/定价公式；不改 C7b1 plan。

## D2：Timeline core mapper 缺 HexCoord Map key deserializer

实测：`Timeline.readChangeSet` 经 `EconomyChangeSet["marketRoundStats"]` 读
`MarketRoundStats.lastTransportByHex: Map<HexCoord, TransportHexObservation>` 时
`Cannot find a (Map) Key deserializer for type [HexCoord]`。

冻结修法（优先最小、领域自洽）：

- 在 `MarketRoundStats.lastTransportByHex` 的 record component 上使用 Jackson
  `@JsonDeserialize(keyUsing = ...)` 指向 economy 模块内的 `HexCoord` key deserializer；
  - key 写侧保持默认 `HexCoord.toString()`（现有规范串）；
  - 读侧 `HexCoord.parse(String)`；
  - 这样 Timeline 第四台 mapper 无需认识领域类型即可回放；
- 或等价方案：在 `SimosObjectMapper` 提供可注入的 domain-key 模块注册点并让 economy 注册；
  但不得让 core 编译期依赖 map；
- 不改 Timeline 的 `@class` 类型信息机制；
- 不改 `lastTransportByHex` 的语义/形状（仍 Map<HexCoord,...>）。

## 验收

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

rc=0；C10 复跑 `EconomyPolicyEndToEndTest` 与 `EconomyCycleHealthTest` 不再红。
账本 `.superpowers/sdd/2026-10-21-d032-carrier-c10-blocker-d-hotfix/impl-ledger.md`。
