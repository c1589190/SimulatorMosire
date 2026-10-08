# 市场区 = GOV 疆域的派生链、持久化缺口与落点（只读调查）

- **日期**：2026-10-08
- **任务**：共享任务 `task-4`（只读调查；不改生产代码/测试、不跑 Maven、不 commit）
- **纪律**：AGENTS §四「台账/计划措辞一律回代码核」；每条结论带 `file:line`；零命中写清检索写法；不确定写「未核到」。
- **本报告的性质**：**纯静态代码调查**。全篇没有跑过 Maven、没有跑过测试、没有起过世界 ⇒ 凡"会怎样"都是**代码路径推断**，
  不是运行证据（见末节「我没核到的」）。

---

## 0. 一句话结论

今天「市场区」是**一个完全由逐格 `Market.numeraire` 现算出来的、无持久状态、无命令面、无 GM 面的派生对象**
（`MarketTopology`，`sameNumeraire` 一票决定是否把全世界合成一个区），而用户 2026-10-08 的设计要求它成为
**「政府维护的法律规定」（持久、可被政府退让/覆盖/合并）**——两者之间缺的不是一个字段，而是
**三层东西全缺**：①「市场区」的持久状态与 GM 命令面（只有形状 `MarketRegulation`，且 `defined()` 恒 false、app 侧零引用）；
②「发行政府 → 疆域」的连线（`Government.nationRef` 是自由串、零逻辑读者；真档的 silver 发行主体 `world-silver` **不是任何 GOV 单位**）；
③「GOV 总疆域 → hex」的公开函数（`GovTerritory` 只给 RegionId 且**明令不得进授权**；hex 层 `NationSummary` 内部算过但**算完即弃**）。

---

## 1. Q1：`MarketRegion` / `MarketNode` / `MarketTopology` 的完整派生链

### 1.1 三个类型的形状

| 类型 | 位置 | 形状 |
|---|---|---|
| `MarketNode` | `simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/MarketNode.java:26-27` | `(String nodeId, HexCoord anchor, int radiusHex, CurrencyId numeraire, InstrumentId receiveWith)` |
| `MarketRegion` | `simos-economy-api/.../market/MarketRegion.java:26` | `(MarketNode node, Set<HexCoord> members)` |
| `MarketTopology` | `simos-economy/src/main/java/io/mosire/simos/economy/time/MarketTopology.java:54` | 不可变；持有 `regions`、`regionByHex`、地形/道路/费率入口 |

三者的"派生件、不是状态"定案写在三处类注：`MarketTopology.java:27-29`、`MarketNode.java:10-12`、`MarketRegion.java:14-15`。

### 1.2 谁调用（生产路径）

装配点唯一：`MarketTopologyBook`（`simos-app/src/main/java/io/mosire/simos/app/time/MarketTopologyBook.java:72`，**包内可见** `final class`）。

| 入口 | 调用者 | 作用 |
|---|---|---|
| `MarketTopologyBook.from(SimulationState)` `:88-93` | — | 无商人调整量的默认入口 |
| `MarketTopologyBook.from(state, 两个 ToLongBiFunction)` `:107-140` | — | P6 带商人调整量的入口（真正的实现） |

生产调用点（`grep -rn "MarketTopologyBook.from" --include=*.java simos-app/src/main`，3 命中）：

- `simos-app/src/main/java/io/mosire/simos/app/time/MarketReadoutAssembly.java:72`（读口：逐格市场读数）
- `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java:564`（日循环：结算 + 逃亡去向）
- `simos-app/src/main/java/io/mosire/simos/app/time/EconomyOwnershipTimeParticipant.java:193`（产权切片推进）

测试专用桥：`simos-app/src/test/java/io/mosire/simos/app/time/MarketTopologyBookTestAccess.java:17`（**逐字转调生产实现**，不是复制）。

消费侧：`MarketTopology` 由组合根注入 `EconomyDayStepper`（`simos-economy/.../time/EconomyDayStepper.java:90`）
→ `EconomySettlement` → `MarketSettlement`（`regionOf` 读取点 `MarketSettlement.java:956`、`:3037`、`:3049`；
规范 id `:1246`；按区配额 `:5485-5538`）。

### 1.3 输入是什么

`from(state, …)` `:111-140` 取四个输入：

| 输入 | 行 | 说明 |
|---|---|---|
| `economy.markets()` | `:114` | 逐格 `Map<HexCoord, Market>`（**唯一的市场权威**） |
| `state.module("map")` → `MapSnapshot` | `:115-121` | 缺席 ⇒ 直接 `singleHex` 退化（`:117`） |
| `social.cities()` | `:123-124` | `cityAuthority = social != null && !social.cities().isEmpty()` |
| `sameNumeraire(economy.markets())` | `:125` | 见 1.5 |

★ `MarketTopology` 本体**不吃 `GameMap`**：地形/道路/费率一律以 lambda 注入
（`MarketTopology.java:42-44` 类注；入参 `:337-347` 收 `Set<HexCoord> hexes` + `ToIntFunction`）。

### 1.4 两条分支

**分支 A：D-027「同币即同区」（单区）** `:128-137`

```java
if (cityAuthority && sameNumeraire) {
  … return MarketTopology.singleRegion(economy.markets(), economy.markets().keySet(), …);   // :131-136
}
```

**分支 B：旧路「城市节点 + tier 半径」** `:138-139` → `byCityRadius(...)` `:146-203`

- 节点来源优先级：`social.cities` `:153-158` → `map.cities` `:160-165` → `craft@` 手工业格 `:166-179`（M0.6 口径）。
- 半径：`TIER_RADIUS_HEX` `:75-80`（MarketTown 2 / Town 4 / City 8 / MajorCity 16），无 tier ⇒ `DEFAULT_CITY_RADIUS_HEX = 8` `:83`。
- 最终 `MarketTopology.of(nodes, markets, markets.keySet(), 地形, 道路, 辐射, tariff, 两个调整量)` `:193-202`。

两者都为空 ⇒ `singleHex` `:180-182`。

### 1.5 `sameNumeraire` 如何合区

`MarketTopologyBook.java:209-219`：

```java
private static boolean sameNumeraire(Map<HexCoord, Market> markets) {
  CurrencyId single = null;
  for (Market market : markets.values()) {
    if (single == null) { single = market.numeraire(); }              // :212-213
    else if (!single.equals(market.numeraire())) { return false; }    // :214-215
  }
  return single != null;                                              // :218（空表 ⇒ false）
}
```

★★ **判据 = 逐格 `Market.numeraire()` 是否全同，只看币种，不看发行人/政府/疆域/城市。**
一旦为真，`:128` 走进 `singleRegion`，而 `singleRegion` 把**传入的全部 hex 归入恰好一个区**
（`MarketTopology.java:201` 注释「★"有市场的 hex 全部归入一个区"：markets 的每个键都在同一个区里，不造第二个区」）。
⇒ **今天"同币"的语义是"全世界一个区"**（不是"每个发行政府一个区"）。这一点是 Q5/Q7 的核心错位，见 §5、§7。

### 1.6 `MarketTopology` 内部的成员表构造与 `regionByHex` 单值性

**单区路径** `singleRegion` `:165-231`：

- 校验：`markets` 空 `:176-178`、`marketHexes` 含 null `:181-183`、成员格无市场表条目 `:184-187`、
  成员表空 `:190-192`、`markets` 键含 null `:193-196`、两入参不同源 `:197-200` —— 全部 fail-closed 具名抛。
- 锚格 = 规范序（q,r 升序）第一个格：`anchor = canonicalFirstHex(members)` `:203`，定义 `:234-242`。
- 节点：`new MarketNode("single-region", anchor, 0, anchorMarket.numeraire(), SILVER_SPECIE.id())` `:209-215`。
- 区：`new MarketRegion(node, members)` `:216`。
- **填表**：`for (HexCoord member : members) byHex.put(member, region);` **`:217-220`**。

**城市节点路径** `of(...)` `:337-440`：

- 去重 + 跳过"锚格没有市场"的节点：`:359-368`（`:364-366 continue`）。
- **逐格归属最近节点**：`:370-396`；半径外跳过 `:383-385`；同距按 `node.nodeId()` 字典序 `:386-391`；
  命中后 `members.get(best.nodeId()).add(hex)` `:393-395`。
- **集散节点本身必须进自己的成员表**：`:397-400`（`for (MarketNode node : byId.values()) members.get(node.nodeId()).add(node.anchor());`）。
- **填表（单值性的落点）**：`:401-409`
  ```java
  for (HexCoord member : regionMembers) { byHex.putIfAbsent(member, region); }   // :406-408
  ```
- 没有落在任何半径内的市场格 ⇒ 退化成单格区：`:410-427`（前置 `:413-415 if (byHex.containsKey(hex)) continue;`，
  故 `:426 byHex.put(hex, region)` 不会覆盖）。

**`regionByHex` 的单值性总账**：

| 事项 | 行 |
|---|---|
| 字段声明 | `MarketTopology.java:89` |
| 构造期冻结（`Collections.unmodifiableMap`） | `:120` |
| 写入点 1（单区，`put`） | `:219` |
| 写入点 2（城市节点，**`putIfAbsent` = 单值性的唯一保证**） | `:407` |
| 写入点 3（退化单格区，`put`，前置已 `continue`） | `:426` |
| 读取口 `regionOf`（无归属 ⇒ 具名抛） | `:448-454` |
| 不抛版本 `contains` | `:461-463` |

★★ **单值性的边界（重要，且未被任何守卫把守）**：
`byHex` 单值，但 **`MarketRegion.members()` 不是划分**。原因在 `:397-400` 与 `:393-395` 的组合：
若节点 A 的锚格落在节点 B 的半径内且 B 更近，则该格在 `:394` 被判给 **B**，又在 `:399` 被无条件加进 **A** 自己的成员表
⇒ `members[A] ∩ members[B] ≠ ∅`；而 `byHex` 由 `:407 putIfAbsent` 按 `byId` 的键序（= 调用方 `nodes` 的**声明序**，
城市按 id 字典序 `MarketTopologyBook.java:155`/`:161`）取**第一个声明者**，**不是最近者**。
⇒ 对同一个 hex，`regionOf(hex)` 与"哪个区的 `members()` 含它"**可以给出两个不同的区**。

全仓 main 对 `MarketRegion.members` 的**不相交守卫 = 0**（检索写法：
`grep -rn "disjoint" --include=*.java simos-economy/src/main simos-economy-api/src/main simos-app/src/main` ⇒ 仅
`GovWorldBootstrap.java:234`、`HouseholdQueryService.java:587` 两处**与本主题无关**命中；`MarketTopology.java` 内
`grep -c disjoint` = 0）。★ 这条是**代码层推断**，我没有实跑验证它是否在真档世界里实际发生（见末节）。

### 1.7 退化入口 `singleHex`（每格一区、不构造跨区候选）

定义 `MarketTopology.java:137-140`。生产/退化使用点：
`MarketTopologyBook.java:117`、`:120`、`:181`；`EconomySettlement.java:603`、`:653`、`:673`、`:687`；`EconomyDayStepper.java:104`。

---

## 2. Q2：市场区有没有持久状态？（独立复核）

### 2.1 结论

**没有。** 三条独立证据，全部回代码核过。

### 2.2 证据 ①：`EconomyData` 组件面

`EconomyData` 的 canonical 组件表：`simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:233-264`。
`markets` 在 **`:241`**（`Map<HexCoord, Market> markets`）。

★★ **实测组件序号**（逐行数 `:234-264`）：
`meta`(1) / `industries`(2) / `classes`(3) / `debtContracts`(4) / `flows`(5) / `allocations`(6) / `relations`(7) /
**`markets`(8)** / `shipments`(9) / `assetShares`(10) / `operatorConditions`(11) / `units`(12) / `demands`(13) /
`candidates`(14) / `modes`(15) / `classStructures`(16) / `classPositions`(17) / `classStandings`(18) /
`productionOrganizations`(19) / `assetRules`(20) / `governments`(21) / `moneyIssuances`(22) / `pledges`(23) /
`liquidationPolicies`(24) / `crisisSignals`(25) / `modeTransitions`(26) / `classShares`(27) / `merchantFirms`(28) /
`periodicAdjustments`(29) / `outputQuantityOverrides`(30) / `productionEfficiency`(31)。

⇒ **`markets` = 第 8 个字段**（含 `meta` 计第 1）= 第 7 个 Map 组件。
★ 但 `EconomyData.java:170` 与 `EconomyChangeSet.java:172` **都写「第 9 个组件」** —— 台账措辞与代码不符，见 §7 的 C 档 ⑨。

`markets` 的语义：**逐格一个 `Market(numeraire, prices)`**（`simos-economy/.../model/Market.java:46`），
**不含任何区/成员/半径/制度字段**；`EconomyData.java:170-180` 三条口径（缺格=无市场、键是格不是市场 id、
不含任何数量）。★ 同处 `:176` 明写 `MarketId` 那个契约"留给跨格市场节点的后续增量"。

### 2.3 证据 ②：变更集组件面

`EconomyChangeSet`（`simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java:109-141`）：
有 `FieldDelta<Market> markets` `:117`，diff 在 `:273`（`FieldDelta.diff(base.markets(), target.markets())`）、
rebuild 在 `:311`、`isEmpty` 在 `:360`；**没有任何 region/topology/regulation 维度**。
（检索：`grep -ni "market|numeraire|region|topology" <该文件>` 命中 `:27/:45/:70/:117/:173/:174/:273/:311/:360`，逐条看都是
`markets` 或 `ShipmentBatch`。）

### 2.4 证据 ③：Codec / Snapshot / Differ 面（零命中，已做阳性对照）

检索写法（**glob pathspec 会静默 0 命中，故加了阳性对照**）：

```bash
# 被测检索
grep -rln "MarketRegion\|MarketTopology\|MarketRegulation\|regionByHex" \
  --include=*Codec*.java --include=*ChangeSet*.java --include=*Snapshot*.java --include=*Differ*.java \
  simos-*/src/main
# ⇒ 0 命中（exit 1）

# 阳性对照：同一 --include 集合搜一个必然存在的符号
grep -rln "EconomyData" \
  --include=*Codec*.java --include=*ChangeSet*.java --include=*Snapshot*.java --include=*Differ*.java \
  simos-*/src/main
# ⇒ 3 命中：EconomyCodec.java / EconomySnapshot.java / EconomyChangeSet.java
```

对照通过 ⇒ 该 `--include` 集合确实覆盖到相关文件，**零命中是结论而不是写法假阴性**。

补充：

- `EconomySnapshot` = `record EconomySnapshot(StateRef ref, SimosTimestamp timestamp, EconomyData data)`
  （`simos-economy/.../EconomySnapshot.java:14`），namespace 固定 `"economy"` `:30`。**无独立区表**。
- Codec 走 **record 字段驱动**绑定：`EconomyCodec.java:628`（`PLAIN.treeToValue(node, EconomyData.class)`，快照）、
  `:729`（变更集）。`markets` 的持久化是"顺带"的 —— 全文件只有 `:99` 一句注释提到它，
  键反序列化器注册在 `:231`（`module.addKeyDeserializer(HexCoord.class, ...)`）。

### 2.5 ★★ 关键补充：`MarketRegulation` —— "市场区法定规定"的最接近既有形状，且**明确不落盘**

`simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRegulation.java:50-59`：

```
record MarketRegulation(HexCoord anchor, Map<CommodityId,Long> referencePrices,
                        long bidPerMille, long askPerMille,
                        Map<CommodityId,Long> quotaPerWindow, Map<CommodityId,Long> tariffPerUnit,
                        boolean open, List<String> rules)
```

字段语义 = **区级参考价 / 买卖限价 / 配额 / 单位税费 / 开闭市 / 制度标签**（类注 `:26-40`）——
这正是"市场区这一层的聚合规则，按区施加一次"（D-027 第 3 条的原话口径）。

★★ 类注 `:20-23` 逐字：「它是**纯值类型**——不落盘、不进 `EconomyData`/Codec/ChangeSet，
本批只作为逐轮瞬态传入 `MarketSettlement.MarketRound`；**GM 命令面/落盘是后续批次**」。

实测它的现状：

| 事项 | 证据 |
|---|---|
| 生产唯一构造点 | `simos-economy/.../time/EconomySettlement.java:1642-1664`，传的是 `MarketRegulation.defaultsFor(markets)` `:1664`（`:1662-1663` 注释自述"默认 regulation …⇒ 逐值现状"） |
| 读口构造点 | `simos-economy/.../time/MarketReadout.java:163-185`，传 `MarketRegulation.none()` `:185` |
| `defined()` | `MarketRegulation.java:107-115`：所有字段为默认 ⇒ `false`；而 `defaults(...)` `:82-84` 与 `none()` `:102-104` **都是全默认** ⇒ 生产路径 `defined()` **恒 false** |
| `regulated` 判据 | `MarketSettlement.java:798`（`boolean regulated = regulation.defined() && regulation.anchor().toString().equals(regionId);`）⇒ 今天恒 false；`:800` 的 `hasPrice` 守卫因此走 `MarketRegulation.none()` |
| app 侧引用 | `grep -rn "MarketRegulation" --include=*.java simos-app/src/main` ⇒ **0 命中**（无 GM 工具、无命令面、无落盘） |

⇒ **"区级法律规定"这层今天在代码里是"有形状、无内容、无入口"。** 缺口是**已知且被显式推迟**的，不是遗漏。

---

## 3. Q3：「GOV 总疆域 = 下属行政区 + 直辖区」今天能不能算出来？

### 3.1 层级关系在代码里怎么表达

- **层级本身**：`GovernmentFormation`（`simos-unit/src/main/java/io/mosire/simos/unit/GovernmentFormation.java:57-67`），
  组件 `(staff, governmentPostsOfHousehold, policy, Optional<UnitId> superiorGov, GovernmentLevel level, externalPosts)`。
  `superiorGov` = 上级 GOV 单位 id，中央为空（`:27`）；`level` = 中央/省（`:28`）；`GovernmentLevel` 是枚举
  （`NationSummary.java:115 central.level() != GovernmentLevel.CENTRAL`）。
- **管辖（谁管哪些行政区）**：`Unit.jurisdiction()` 挂 `Jurisdiction`
  （`simos-unit/src/main/java/io/mosire/simos/unit/Jurisdiction.java:53-58`）：
  `Map<RegionId, Long> taxRatePerMilleByRegion` —— **key 集就是管辖区域集**（`:13` 逐字：「{@code taxRatePerMilleByRegion} 的
  **key 集就是管辖区域集**，空 map = 无管辖——不把管辖权写回 Region 的 tag（用户裁定：管辖挂在 Unit 上）」）。
- **空间事实（哪些 hex 属于哪个 Region）**：`simos-map` 的 `Region.hexes()`
  （`Jurisdiction.java:12` 逐字：「空间事实（哪些 hex 属于哪个 Region）归 `simos-map` 的 `Region`」）。

### 3.2 ★ RegionId 层：**有公开函数**

`GovTerritory.nominalRegions(UnitState units, UnitId rootGovId)`
（`simos-app/src/main/java/io/mosire/simos/app/gov/GovTerritory.java:48-84`）：

- 建 `superiorGov → 直接下级 GOV` 反向索引 `:56-65`；
- 以 `rootGovId` 为起点 BFS 向下 `:67-82`，逐 GOV 取 `jurisdiction().taxRatePerMilleByRegion().keySet()` `:80` 并入结果；
- **含起点自己**（`:31-32` 明写）；查无 / 不是 GOV ⇒ `Set.of()`（`:51-54`）；环由 `seen` 兜底（`:73`）；悬空 `superiorGov` 跳过不抛（`:78`）。

★★ 但两条硬限制：

1. **量纲是 `RegionId`，不是 hex**：`:29` 逐字「返回 {@link RegionId} 集合（不是 hex）——"名义区域的集合"正是裁定 4 的
   显示语义；**逐 hex 展开是显示端的事**」。
2. **明令只做显示、绝不进任何授权判定**：`:23-25`「**只读派生，绝不进任何授权判定**…本类只给 GUI / 后续 `NationSummary`
   显示用，**不得**被 `GovScope`、`AdjudicateTick` 或任何资源判定调用」；测试侧还有结构断言把这条钉住
   （`simos-app/src/test/java/io/mosire/simos/app/gov/GovTerritoryTest.java:188-195`：公开方法**只许有一个**）。

调用者：`grep -rn "nominalRegions\|GovTerritory"` ⇒ 生产侧**零调用者**（只有 `GovScope.java:33` 的一句"别用它"注释）；
其余全是测试（`GovTerritoryTest.java`、`NationSummaryTest.java`）与 `NationSummary` 自己**重写了一遍同样的 BFS**。

### 3.3 ★ hex 层：**没有公开函数**（算了但丢弃）

最接近的是 `NationSummary.of(UnitState, GameMap, SocialData)`
（`simos-app/src/main/java/io/mosire/simos/app/gov/NationSummary.java:96-158`）：

- 只对 `GovernmentLevel.CENTRAL` 的 GOV 建项（`:113-117`）；
- 自己**再写一遍** BFS（`:101-110` 反向索引 + `:119-138` BFS，`:134-136` 把 jurisdiction 的 RegionId 并入）；
- **`:140-152` 才把它展开成 hex 并集**：
  ```java
  Set<HexCoord> countedHexes = new LinkedHashSet<>();                    // :141
  for (RegionId regionId : nominalRegions) {
    Region region = map.regions().get(regionId);
    if (region == null) { continue; }                                    // :145 悬空区跳过
    for (HexCoord hex : region.hexes()) {
      if (countedHexes.add(hex)) { totalPopulation += social.populationAt(hex); }   // :147-150
    }
  }
  ```
- ★★ `countedHexes` 是**局部变量**，只用于累加 `totalPopulation`，**算完即弃**；record 的组件面
  （`:50-55`）**只有 `totalPopulation`，没有 hex 集合**（`:37-38` 的 Javadoc 也自述"nominalRegions 覆盖的 hex 并集上…之和"）。

⇒ **明确回答：没有 `Set<HexCoord> territoryOf(govId)` 这样的函数。**
今天要算"某 GOV 的全部 hex"，必须自己把 `GovTerritory.nominalRegions` + `GameMap.regions()` + `Region.hexes()` 拼起来；
而这段拼装**已经分散写过两遍**（`NationSummary.java:142-152`，以及下面的 `GovScope`）。

### 3.4 第三处拼写：`GovScope`（授权面，给"直辖区"而不是"总疆域"）

`simos-app/src/main/java/io/mosire/simos/app/access/GovScope.java:100-114`：

```java
Set<HexCoord> authorizedHexes = new LinkedHashSet<>();   // :100
…
authorizedHexes.add(own.get());                          // :107 自己所在格恒在
for (RegionId regionId : jurisdictionRegions) {           // :108
  Region region = map.regions().get(regionId);            // :109
  …
  authorizedHexes.addAll(region.hexes());                 // :114
}
```

★ 但 2026-10-21 用户裁定**明令不沿 `superiorGov` 扩下辖**（`:46-49`、`:134-141`：`unit` 面"**不沿 `superiorGov` 扩下辖 GOV、
也不自动扩辖区外后代**"）⇒ 它给的是**本级直辖**，不是"总疆域"。

⇒ **`GovTerritory`（总疆域、对授权零参与）与 `GovScope`（直辖、授权私有）恰好互为反面，
没有任何第三个公开入口把"总疆域 = 下级行政区 + 直辖区"折成一个 hex 集合。**

### 3.5 ★★ 发行政府 ↔ 疆域：**今天零连线**（这是用户新设计最直接的输入缺口）

用户设计的第一句是「**发货币的政府的总疆域**默认就是这种货币的市场区」⇒ 需要"币种 → 发行政府 → 疆域"这条链。
实测：

1. **economy 侧 `Government`**（`simos-economy/src/main/java/io/mosire/simos/economy/model/Government.java:32-38`）：
   `(GovernmentId id, String nationRef, ActorRef treasury, Set<CurrencyId> issuable, long seignioragePerCycle, long debtIssuePerCycle)`。
   - `nationRef` 只校验"非空白"（`:63-65`）；**全仓 main 只有两处读它，都是把它写进视图 JSON**
     （`simos-app/.../gui/ApiViews.java:1627`、`simos-app/.../world/EconomySeeder.java:1424`）—— **零逻辑使用**。
   - handler 自述：「`"nationRef":"u-central"` // 必填非空白（**辖区引用；本命令不解释国家语义**）」
     （`simos-economy/.../spi/EconomyRegisterGovernmentHandler.java:46`）。
2. **GOV 单位 ↔ Government 的唯一连线 = 派生 id**：
   `GovernmentIds.ofUnit(govUnitId)` = `"gov-unit-" + govUnitId`（`simos-economy-api/.../id/GovernmentIds.java:32-35`），
   反解 `unitRefOf` `:45-56`。
   ★ 且 `:16` 明写：「**世界级政府不在此列**：`world-silver` 之类的发行主体不是任何 GOV 单位，`unitRefOf` 对它返回空（不猜）」。
3. **真档实际 seed**（`simos-app/.../world/EconomySeeder.java`）：
   - `GENESIS_GOVERNMENT_ID = new GovernmentId("world-silver")` **`:748`**；
   - `GENESIS_GOVERNMENT_NATION_REF = "world"` **`:752`**，自述「世界级政府在 `nationRef` 里的引用（**当前不是任何真实国家 id**，
     故用保留字面量）」；
   - 两种形状：
     - `governmentHouseholdGovernment(...)` `:3511-3521`（**只在 demo 政府家户世界**调用）：`issuable = Set.of(MARKET_NUMERAIRE)`；
     - `genesisAuditGovernment()` `:3527-3538`（**普通多国/多省 seed 路径**）：`issuable = Set.of()`——
       即**根本不是发行人**，自述"存在的唯一理由是 `INITIAL_ENDOWMENT` 发行记录必须有归属"。
   - `MARKET_NUMERAIRE = RegimeRelations.DEFAULT_CURRENCY` `:648`（= silver）。
4. **GOV 单位的政府**由 `economy.RegisterGovernment` 建，id = `gov-unit-<unitId>`；
   **`issuable` 是载荷驱动、缺省空集**（`EconomyRegisterGovernmentHandler.java:318-321`：
   `registration.issuableSpecified() ? registration.issuable() : (existingGovernment == null ? Set.of() : existingGovernment.issuable())`）。
5. **app 侧把 `nationRef` 填成什么**：`superiorGov.orElse(unitId)`
   （`simos-app/.../tools/write/ProvinceApplyPlan.java:657`、`GovCreateOfficePlan.java:389`）——这是**惯例**，不是契约，
   且它是 `GovernmentFormation.superiorGov` 那份层级关系的**第二处拼写**（两处无交叉校验）。
6. **没有任何代码问"谁发这个币"去推疆域**：`issuable()` 的读取者只有
   `ApiViews.java:1629-1630`（GUI 视图）、`EconomySeeder.java:1429-1430`（seed 视图）、
   `simos-economy-api/.../money/MoneyIssuance.java`（铸币登记 SPI，`:49/:68/:118/:133/:170-171`）。

**⇒ 结论：今天没有任何代码能回答"这个币的发行政府的总疆域是什么"。**
现实世界里"声称 silver 的发行人"要么是 `world-silver`（**不是任何 GOV 单位 ⇒ `GovTerritory` 无从下手，`unitRefOf` 返回空**），
要么得 GM 逐条给 GOV 单位登记 `issuable:["silver"]`（此时 `gov-unit-<unitId>` + `GovTerritory` 才拼得出来）。
**用户设计的默认规则今天没有可用的输入。**

---

## 4. Q4：退让 / 覆盖 / 合并 —— map 侧可复用的 Region 操作；以及市场区落持久状态需要新增哪些落点

### 4.1 map 侧可复用的 Region 操作（都在）

`simos-map/src/main/java/io/mosire/simos/map/ops/RegionOperations.java`（**纯函数**，只读 `base`，产出走 `MapChangeSet`，类注 `:25-33`）：

| 操作 | 行 | 用途（对应用户的三类操作） |
|---|---|---|
| `createRegion` | `:79` | 建区 |
| `updateRegion` | `:119` | 改 hexes/meta（**覆盖范围**） |
| `deleteRegion` | `:163` | 撤区 |
| `mergeRegions(GameMap base, RegionId target, Set<RegionId> sources)` | `:186` | **合并** |
| `splitRegion` | `:233` | 拆分 |
| `reassignHexes` | `:357` | 改归属（**退让/覆盖范围**） |

对应命令面 handler（`simos-map/src/main/java/io/mosire/simos/map/spi/`）：
`MergeRegionsHandler.java:38`（`"map.MergeRegions"`）、`SplitRegionHandler.java:47`（`"map.SplitRegion"`）、
`ReassignHexesHandler.java:41`（`"map.ReassignHexes"`）、`CreateRegionHandler.java:52`（`"map.CreateRegion"`）、
`DeleteRegionHandler.java:41`、`UpdateRegionHandler.java:59`、`RenameRegionHandler.java:45`。

★★ **但重叠一律允许**：`RegionOperations.java:26-28` 逐字「**重叠一律允许**（M8-U1，用户原话：「hex 只是地形块，
应当兼容多种从属」）：一个 hex 可同时属于多个区域，**不存在"重叠时谁赢"**。本类**没有任何"与已有区域相交就拒绝"的校验**，
也**不裁剪** hex 集合——重叠是正常状态」；`RegionIndex.java:14-16` 同款（`regionOf` 返回**全部**归属，按 `RegionId` 字典序）。
app 层唯一的"重叠闸门"是省籍创建：`ProvinceApplyPlan.java:166-172`（`enum Gate` / `OVERLAP_OVERRIDE_REQUIRED :171-172`），
判定 `:776-778`、`overrideOverlaps=true` 才继续 `:787-794` —— **要显式 override，不是禁止**。

★ **这些操作操作的是 map 的 `Region`，与今天的「市场区」毫无关系**：`MarketTopology` 从不读 `map.regions()`
（类注 `:42-44`：本类不读全局 `GameMap`，只收 `Set<HexCoord>` + 逐格 lambda）。
唯一把 `map.regions()` 与"区"连起来的是 `NationSummary`/`GovScope`（授权/显示面）。

### 4.2 `RegionMeta` 的两个槽（D-026 想用的机制）今天只是形状

`simos-map/src/main/java/io/mosire/simos/map/region/RegionMeta.java:10`：
`record RegionMeta(String color, String tag, String description, String annexedBy)`（**四字段都可为 null，无类型、无枚举**）。
`annexedBy` 的读取点：`grep -rn "annexedBy" --include=*.java simos-*/src/main` ⇒ 只有 `WorldgenInitializeTool.java:100-101`、
`:1016` **原样回填**（`:1016 meta.put("annexedBy", region.meta().annexedBy());`）——**零语义读取**。
`tag` 有真实语义，但只用于国家识别：`NationScope.java:72`、`NeighborNations.java:54`、`ProvinceAssignCitiesPlan.java:151`、
`HexOwner.java:64`（判 `nation:` 前缀，`simos-sd/.../sd/spi/NationTag.java:13 PREFIX = "nation:"`）。

### 4.3 如果市场区要落成持久状态：**只列落点，不设计**

（下列行号是"今天必须动的地方"，不是方案。）

1. **状态组件**：`EconomyData` record 追加字段（`:233-264`）。
   ★ 判别力来自编译：该文件有**两个便捷构造器**（`:284`、`:353`）逐参转发 canonical，
   新增组件会逼这两处及所有 `new EconomyData(...)` 调用点**当场编译失败**（这正是铁律 5 想要的护栏）。
2. **变更集**：`EconomyChangeSet` 追加 `FieldDelta<...>`（`:109-141`）+ 构造器 null→`Unchanged` 兜底（`:146-…`）
   + `diff`（`:273`）+ `rebuild`（`:311`）+ `isEmpty`（`:360`）。
   ★ 已有护栏：`EconomyChangeSet.java:79` 自述「铁律 5：变更集从完整状态类型派生，由 `EconomyRoundTripTest` 的**反射枚举**
   把守——新增状态组件若不进变更集，那个测试自动红」。
3. **Codec**：`EconomyCodec` 的 record 绑定会**自动**带上新组件（`:628`/`:729`）；但
   （a）键类型要在 `keyModule()` 里注册 `addKeyDeserializer`（现有注册点 `:195-281`，例：`HexCoord` 在 `:231`）；
   （b）旧档缺键 ⇒ 必须在 `EconomyData` 构造期归一 + `EconomyChangeSet` 构造器兜底（照 `:150-…` 同款）。
4. **命令面**：`simos-economy/src/main/java/io/mosire/simos/economy/spi/` 新增 handler
   （写法参考 `EconomySetMarketPriceHandler.java:113` 的 `markets.put(hex, new Market(existing.numeraire(), prices));`）
   + `EconomyPayloads` 的载荷解析（`:616`、`:640-642`）+ **AGENTS §一.9 要求的 INFO/DEBUG/TRACE 日志与
   `EconomyLogSource` 事件**。
5. **工具面**：`simos-app/src/main/java/io/mosire/simos/app/tools/write/` 新增窄写工具
   （参考 `MapRenameRegionTool.java`、`ProvinceApplyTool.java` 的 preview/apply 共用纯推导形态）+ `CatalogTool` 登记；
   若"政府维护的法律规定"要落到**政府权限**上，还必须动授权面 `simos-app/.../access/GovScope.java` 与
   `DecisionScopeFunctions`（★ 那时要注意：`GovTerritory` 明令不得进授权判定，`GovScope` 才是授权面的正确落点）。
6. **可直接复用的既有身份槽（今天都是死的）**：
   - `MarketId`（`simos-economy-api/.../id/MarketId.java:8`）**今天零生产引用**
     （`grep -rn "MarketId" --include=*.java simos-*/src/main` ⇒ 只命中自身 + `EconomyData.java:176` 的注释
     "留给'跨格市场节点'的后续增量"）；
   - `MarketRegulation`（形状已在，缺持久化 + GM 面，见 §2.5）；
   - `MarketNode.receiveWith`（字段已在，**零读取者**，见 §5.3）。
7. **★ 若改走 D-026 的原稿路线（"区第一性来源 = map 的 `Region`"）**：落点在
   `map.regions()` 的 `RegionMeta.tag/annexedBy` + `RegionOperations.*`，**economy 侧不需要新状态组件**；
   但需要把"区域成员集"喂给 `MarketTopology`——今天它只收 `Set<HexCoord> hexes` 入参
   （`MarketTopology.java:251`（`of` 的 `hexes`）、`:167`（`singleRegion` 的 `marketHexes`）），
   组合根改喂即可（`MarketTopologyBook.java:133`/`:196` 就是喂 `economy.markets().keySet()` 的地方）。

---

## 5. Q5：`Market.numeraire`（逐格）与"市场区法定币"是同一个概念吗？

### 5.1 不是同一个概念；而且**已经错位**

| | 逐格 `Market.numeraire` | 区 `MarketRegion.numeraire()` / `MarketNode.numeraire` |
|---|---|---|
| 声明 | `simos-economy/.../model/Market.java:46`，裁定 M1-A「**每格单一计价货币**」（`:10-14`） | `simos-economy-api/.../market/MarketRegion.java:51-54`（= `node().numeraire()`），类注 `:51`：「本区报价币种（**区内所有价格都按它计**）」 |
| 权威 | **数据**（`Market` 是 `EconomyData` 的一个组件，进 Codec/ChangeSet） | **派生**（`MarketNode` 由 `MarketTopologyBook` 现算，`MarketNode.java:10-12`） |
| 取值来源 | 由 GM/seed 写 | **只是某一个"锚格/城格"的逐格 `Market.numeraire()` 的拷贝**：`MarketTopologyBook.java:253-254`（城市节点 = **该城格自己的**市场币种）、`MarketTopology.java:209-215`（单区 = **锚格**的 `anchorMarket.numeraire()`） |

★ **没有任何独立于 `Market` 的"法定币"权威**：区的报价币 100% 从逐格市场币种抄来。
⇒ 对用户「市场区…**和其内家户实际上用啥货币没有直接关系**」这句话而言，
**今天的取值来源确实是错位的**：市场的"区币种"不是政府的法律规定，而是某一格市场数据字段的投影。

### 5.2 错位的第二层（更硬）：合区判据本身就是币种

`MarketTopologyBook.sameNumeraire`（`:209-219`）+ `:128` 的 `if (cityAuthority && sameNumeraire)`：
**币种是否全同，是"是不是一个区"的唯一判据**（除城市权威的有无）。
且 `singleRegion` 把**全部**有市场的 hex 并成一个区（`MarketTopology.java:201`）。
⇒ 用户设计说"疆域决定区"，D-027 说"同币即同区"，**代码实现的是后者，且是全局合区**（不是"每发行政府一区"）。

### 5.3 `receiveWith` 恒 `SILVER_SPECIE`：三处硬编码 + 整条链零读取者

写入点（**三处都是硬编码常量**）：

- `MarketTopology.java:215`（单区：`MoneyVocabulary.SILVER_SPECIE.id()`）
- `MarketTopology.java:423`（退化单格区：同）
- `MarketTopologyBook.java:254`（城市节点：同；且其上一行 `:253` 用的是 `market.numeraire()`，**两者并不同源**）

读取情况（精确到"方法调用"）：

```bash
grep -rn "\.receiveWith()" --include=*.java simos-*/src/main
# ⇒ 唯一命中：simos-economy-api/.../market/MarketRegion.java:58  return node.receiveWith();
```

⇒ `MarketRegion.receiveWith()` 的**唯一调用者是自己**；`MarketNode.receiveWith()` 的唯一调用者是 `MarketRegion.receiveWith()`。
**整条链没有任何外部读取者**（`grep -rn "receiveWith"` 全仓 14 命中，其余都是
`MarketNode.java:24/27/42/43` 的定义与校验、`SellOrder.java:18/33/42/65/66`（**另一个 record**）、
`ApiViews.java:1773/3366/3371` 的三处注释）。
⇒ `receiveWith`（"卖方收哪种货币工具"）今天是一个**只写不读的字段**。

### 5.4 "非 silver 锚直接跳过"复核成立（且今天无实际触发）

`MarketTopologyBook.java:239`（方法注：「非 silver 市场本批跳过（单一货币工具）」）、
**`:246-248`**：

```java
if (!MoneyVocabulary.SILVER_CURRENCY.equals(market.numeraire())) {
  return; // 本批只有 silver 一种工具（M2.0 #2）；其他币种的区域等货币工具落地后再说
}
```

★ 今天这条**无实际触发**：`MoneyVocabulary.allCurrencyDefs()` 只返回 `SILVER`、`allInstruments()` 只返回
`SILVER_SPECIE`（`simos-economy-api/.../money/MoneyVocabulary.java:87-97`；`SILVER_CURRENCY` `:59`、
`SILVER_SPECIE` `:71-74`）。
但它是**"区 = 币种"写死的一处**：非 silver 的格当不上城市节点，只能退化成 `of()` 的单格区（`:410-427`）或 `singleHex`。

---

## 6. Q6：逐格币种与区法定币不同时，代码会怎样？

### 6.1 今天：区的币种只在**一个读口**被读，撮合/计价/结算**全部只看逐格币种**

| 环节 | 读的是谁 | 行 |
|---|---|---|
| 区币种的**唯一**读取点 | `region.numeraire()` → `RegionReadout` 的第 4 个字段 | `simos-economy/.../time/MarketReadout.java:314-320`（`:318`） |
| 订单（买方）金额币种 | **买方格**的 `market.numeraire()`：`market = markets.get(hex)` 且 `hex` 是买方格 | `MarketSettlement.java:943`（取市场）、`:977`（`new BuySlot(order, buyer, hex, region, market.numeraire())`） |
| 计价（参考价） | **卖方格**的市场/区：`unitPriceOf` `:3842-3850` → `ctx.referencePriceOf(sell.regionId, sell.market, commodity)`；`sell.regionId` 来自 `topology.regionOf(sellerHex)` `:3049` | `:3846` |
| 钱腿（谁付谁收什么币） | **买方格的币种**：`mint(buy.buyer.actor, sell.seller.actor, location, Map.of(), Map.of(buy.currency, payment), MARKET_TRADE)` | `:3559-3568` |
| 运费腿 | 同买方格币种：`Map.of(buy.currency, charge.amountMilli())` | `:3572-3581` |
| 预算/可负担 | 买方格的 `market.numeraire()` | `:1352`、`:4494`、`:4537` |
| 信用池 | 买方格的 `market.numeraire()` | `:2161-2166` |
| 迁移现金 | 格市场币种 | `ModeMigrationPolicy.java:1052`、`:1203` |
| 冻结表轴键 | `(buyer actor, buy.currency)` | `:1484`、`:1560` |

### 6.2 ★★ 若买方格与卖方格币种不同：**价格按卖方币计，钱按买方币付，无换算、无拒绝、无守卫**

我逐点查过币种相等的判据，`MarketSettlement` 里**唯一**一处是 `:1707`
（`!candidate.currency.equals(buy.currency)`）——那是**出借人的币种 vs 买方币种**（借啥欠啥，D-030 第 3 条），
**不是买卖两格的币种对比**。⇒ 跨格/跨区成交时：

- 单价 `unitPrice` 来自**卖方**市场（卖方币种的数）；
- 货款 `payment` 用同一个数，但以 **`buy.currency`（买方格的币种）** 结算给卖方
  （`:3559-3568`，`Map.of(buy.currency, payment)`）。
⇒ **同一个整数被当成两种货币的量**，没有汇率、没有拒绝、没有日志。

**这一缺口在仓内已被如实记录**：`simos-economy/.../time/EnterpriseProfitBook.java:671` 的注释逐字
「逐币种读取、全部计入（**跨区成交的计价币可能不是卖方本格 numeraire**；跨币种求和的量纲缺口见收口报告）」，
且 `addRevenue`（`:662-675`）与 `addCost`（`:677-689`）**把 `numeraire` 形参完全忽略**，直接
`for (long amount : money.values()) row[0] = Math.addExact(row[0], amount);`（`:672-674`、`:686-688`）。

### 6.3 ★ 对照：仓内**已有**的正确惯例是 fail-closed

`simos-economy/.../time/DebtValuation.java:528-530`：

```java
if (numeraire != null && !numeraire.equals(market.numeraire())) {
  return 0L; // 两个价目表计价货币不同：缺价时不能用另一把尺静默折算
}
```

⇒ 债务估值路径遇到币种不一致是**具名 fail-closed（返 0）**；市场撮合路径遇到同一情形是**静默按数搬钱**。
两处口径不一致，这是落 §7 B 档的依据。

---

## 7. Q7：缺口与矛盾清单（三档）

### A 档 —— 阻塞用户 2026-10-08 设计落地（缺件，不是错件）

| # | 缺口 | 证据 |
|---|---|---|
| A1 | **「市场区 = 政府维护的法律规定」没有持久状态、没有命令面、没有 GM 面** | `EconomyData` 组件面无该维（`:233-264`）；`EconomyChangeSet` 无该维（`:109-141`）；Codec/Snapshot/Differ **零命中**（§2.4）；`MarketRegulation` 形状在但 `defined()` 恒 false（`:107-115`）、app 侧**零引用**、类注 `:20-23` 自述"落盘是后续批次" |
| A2 | **「发行政府 → 疆域」零连线** | `Government.nationRef` 是自由串（`Government.java:34`、校验 `:63-65`），**零逻辑读者**（只有 `ApiViews.java:1627`、`EconomySeeder.java:1424` 两处写视图）；唯一连线是派生 id `gov-unit-<unitId>`（`GovernmentIds.java:32-35`），而 `:16` 明写世界级发行主体不在其列；真档普通 seed 的 `genesisAuditGovernment` **`issuable = Set.of()`**（`EconomySeeder.java:3527-3538`），`world-silver` 的 `nationRef = "world"` **不是任何真实国家 id**（`:750-752`） |
| A3 | **「GOV 总疆域 → hex」没有公开函数** | `GovTerritory.nominalRegions` 只给 `RegionId`（`:29`）且**明令不得进授权**（`:23-25`）；`NationSummary` 内部算了 hex 并集但**算完即弃**（`:141`、`:147-150`，record 只有 `totalPopulation` `:50-55`）；`GovScope` 给的是**直辖**且明令不扩下辖（`:46-49`、`:134-141`） |

### B 档 —— 语义错位 / 写死（"区 = 币种"的落点，全在这几处）

| # | 写死/错位点 | 行 |
|---|---|---|
| B1 | **合区判据 = 逐格币种全同**，且同币即**全局合区**（不看政府/疆域/国界） | `MarketTopologyBook.java:209-219` + `:128` + `MarketTopology.java:201` |
| B2 | **区的报价币 = 锚格/城格的逐格 `Market.numeraire()` 拷贝**（无独立权威） | `MarketTopology.java:209-215`（单区）、`MarketTopologyBook.java:253-254`（城市节点） |
| B3 | `MarketRegion.java:51` 的 Javadoc 声称"**区内所有价格都按它计**"，代码里**没有任何一处**按区币种计价 | 对照 §6.1（价格逐格读） |
| B4 | **非 silver 锚直接跳过**（"区 = 币种"写死） | `MarketTopologyBook.java:246-248`（今天无实际触发：`MoneyVocabulary.java:87-97` 只有 silver） |
| B5 | `receiveWith` **三处硬编码 `SILVER_SPECIE`** 且**整条链零读取者** | `MarketTopology.java:215`、`:423`、`MarketTopologyBook.java:254`；调用链只有 `MarketRegion.java:58` 自转发 |
| B6 | **跨格币种不同 ⇒ 静默不换算、不拒绝**（价格按卖方币、钱按买方币） | `MarketSettlement.java:3846`（价）+ `:977`（币）+ `:3559-3568`（钱腿）；唯一币种判据 `:1707` 是出借人币种，不是买卖两格；后果已被记录在 `EnterpriseProfitBook.java:671` 与 `:672-674`/`:686-688` |
| B7 | 仓内**同一情形两种口径**：债务估值 fail-closed 返 0，市场撮合静默搬钱 | `DebtValuation.java:528-530` vs `MarketSettlement` §6.2 |
| B8 | "区域 → hex"有**三处拼写**（`GovTerritory` / `NationSummary` / `GovScope`），无单一拼写点 | `GovTerritory.java:80`、`NationSummary.java:142-152`、`GovScope.java:100-114` |
| B9 | **`MarketRegion.members()` 不是划分**（锚格重复计入），`regionByHex` 单值但取**声明序第一个**而非最近节点 | `MarketTopology.java:393-395` + `:397-400` + `:406-408`；全仓 main **零不相交守卫**（§1.6）。★ **代码层推断，未实跑** |

### C 档 —— 文档/台账措辞与代码不符（会让后续派单做错方向，AGENTS §四）

| # | 台账说 | 代码实际 |
|---|---|---|
| C1 | `EconomyData.java:170` 与 `EconomyChangeSet.java:172` 都称 `markets` 是「**第 9 个组件**」 | record 里是**第 8 个字段**（`EconomyData.java:241`）；错位原因是编号把**已退役的 `laborSupply`** 仍算在内（`EconomyChangeSet.java:69` 的清单里还留着它；`EconomyData` 已无该字段，只在 `:117` 注释留痕） |
| C2 | `RegionOperations.java:26-28` / `RegionIndex.java:14-16`：「重叠**一律允许**」（署 M8-U1 用户原话「hex 只是地形块，应当兼容多种从属」） | 与用户 **2026-10-08**「**重叠辖区是不被允许的**」**直接冲突**（用户口述 vs 用户口述，不同日期）⇒ 见 §8 矛盾 4 |
| C3 | D-026（2026-10-06）裁定「区的**第一性来源 = map 的 `Region`**（GM 可编辑；`RegionMeta.tag`/`annexedBy` 表达类型与归属）…**本裁定取代**此前 `MarketTopologyBook` 的"城市节点 + 半径最近归属"」 | **代码未实现**：实现的是同日 D-027「同币即同区」；`RegionMeta.tag` 只用于国家标签（`NationScope.java:72` 等），`annexedBy` **零语义读者**（`WorldgenInitializeTool.java:1016` 只回填）；`MarketTopology` 从不读 `map.regions()` |
| C4 | `MarketTopology.java:24`「把…现算成 **一个 hex 恰属一个区**的成员表」 | `regionByHex` 单值成立，但 `MarketRegion.members()` 可以相交（B9）——"成员表"这个词在两种读法下结论不同 |

---

## 8. 矛盾点（如实并列，**不替用户裁定**）

1. **「市场区＝法定规定」 vs 「逐格 `numeraire` 派生」**
   今天区的**存在与边界** 100% 由 `Market.numeraire`（一个逐格数据字段）决定（B1/B2）。
   用户新设计说它与"家户实际上用啥货币"**没有直接关系** ⇒ 需要一个新的、独立于 `Market` 的权威，
   而**这个权威今天一处都没有**（A1）。

2. **「市场区要不要落持久状态」**
   用户："市场区本质上是一个政府维护的**法律规定**" ⇒ 天然是**持久状态**（法律要被维护、改动、存档、被 GM 编辑）。
   仓内三处类注把它定案为**纯派生件**：`MarketTopology.java:27-29`（"它是派生件，不是状态（M2.0 定案）：没有进 `EconomyData`、
   没有变更集、没有 codec"）、`MarketNode.java:10-12`、`MarketRegion.java:14-15`。
   ⇒ **直接对立的两套口径**。★ 且 `MarketRegulation.java:20-23` 已经把"落盘"显式推给"后续批次"
   ⇒ 这个缺口是**已知且被推迟**的，不是没人想到。
   （★ 这条正是 `docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 D-026 与 D-027 的分岔点，见下。）

3. **「同币即同区」（D-027，2026-10-06，已实现） vs 「发币政府的疆域默认是市场区」（2026-10-08）**
   两者的**合区判据不同**：币种 vs 政府疆域。前者今天把同币世界合成**一个全球区**
   （`MarketTopologyBook.java:128` → `MarketTopology.java:201`），后者要求按政府切分。
   ★ 设计原稿并列：D-026 原稿（`docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 D-026 节，
   约 `:422-437`）曾裁定「区的第一性来源 = map 的 `Region`…**本裁定取代**此前 `MarketTopologyBook` 的
   "城市节点 + 半径最近归属"」，但同日 D-027（`:439-458`）第 2 条把 D-026 的节点生成/跨区聚集**降级为后续批次**，
   第 1 条改为「同币即同区」⇒ **代码实现的是 D-027**（D-026 未实现，C3）。
   ⇒ 用户 2026-10-08 的新设计**同时**与"已实现的 D-027"（判据）和"D-026 原稿"（第一性来源）都不同；按 AGENTS §四.1
   「以最新落盘文档为准」，2026-10-08 的会话裁定应覆盖 2026-10-06 的两条，但**这是用户裁定，不是我能定的**，
   故如实并列上报。

4. **「重叠辖区是不被允许的」（2026-10-08） vs 「重叠一律允许」（M8-U1，用户原话，已实现）**
   ★ **同一位用户、不同日期、相反裁定**，且两者都落到了代码/文档：
   - 前者与用户新设计的市场区直接相关；
   - 后者是 map 层身份级的实现：`RegionIndex.regionOf` 返回**全部**归属（`RegionIndex.java:14-16`、
     `:59-62`）、`RegionOperations` **零相交校验**（`:26-28`）。
   ⇒ **必须用户裁定**（我按纪律不裁定、不改代码）。注意 app 层已有"半扇门"：省籍创建要显式 `overrideOverlaps`
   （`ProvinceApplyPlan.java:171-172`、`:776-778`），但那是"要 override"而不是"禁止"。

5. **`MarketRegion.members()` 不是划分 vs "一个 hex 恰属一个区"**
   自称的是 `regionByHex`（B9）；两城半径重叠时 `members[A] ∩ members[B] ≠ ∅` 且
   `regionOf` 取的是**声明序第一个**而非最近节点。若用户新设计要把"区的成员格"落成持久状态，
   就会把今天的**派生期歧义固化成状态**——这是落盘前必须解决的语义空洞（B9，代码层推断、未实跑）。

---

## 9. 关键 file:line 速查

```
# 派生链
simos-app/src/main/java/io/mosire/simos/app/time/MarketTopologyBook.java:88-93,107-140   from() 两入口
  :124-125 cityAuthority / sameNumeraire          :128 if (cityAuthority && sameNumeraire)
  :131-136 singleRegion(...)                      :138-139 byCityRadius(...)
  :146-203 byCityRadius                           :209-219 sameNumeraire(...)
  :239-255 addNode                                :246-248 非 silver 跳过
  :253-254 new MarketNode(..., market.numeraire(), SILVER_SPECIE.id())
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketTopology.java
  :54,88-89,120 字段与冻结                          :137-140 singleHex
  :165-231 singleRegion                            :203,234-242 canonicalFirstHex
  :209-215 单区节点（anchorMarket.numeraire()）     :217-220 byHex.put（单区）
  :337-440 of(...) 主路径                          :359-368 去重/跳锚格无市场
  :370-396 逐格归属最近节点                         :397-400 锚格无条件进自己成员表
  :401-409 byHex.putIfAbsent（**单值性落点**）      :410-427 退化单格区
  :448-454 regionOf                                :461-463 contains
  :215,423 SILVER_SPECIE 硬编码
simos-economy-api/src/main/java/io/mosire/simos/economy/api/market/{MarketNode.java:26-27, MarketRegion.java:26,51-59}

# 持久状态（三个独立证据）
simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java:233-264（markets 在 :241 = 第 8 字段）
simos-economy/src/main/java/io/mosire/simos/economy/change/EconomyChangeSet.java:109-141（markets :117）
simos-economy/src/main/java/io/mosire/simos/economy/codec/EconomyCodec.java:628,729（record 字段驱动）
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketRegulation.java:20-23,50-59,82-84,102-104,107-115
simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java:1642-1664（defaultsFor）
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketReadout.java:163-185,314-320（region.numeraire() 唯一读取点 :318）

# GOV 疆域
simos-unit/src/main/java/io/mosire/simos/unit/GovernmentFormation.java:57-67（superiorGov :27、level :28）
simos-unit/src/main/java/io/mosire/simos/unit/Jurisdiction.java:13,53-58
simos-app/src/main/java/io/mosire/simos/app/gov/GovTerritory.java:23-25,29,48-84
simos-app/src/main/java/io/mosire/simos/app/gov/NationSummary.java:50-55,96-158（hex 并集 :140-152）
simos-app/src/main/java/io/mosire/simos/app/access/GovScope.java:46-49,100-114,134-141
simos-economy/src/main/java/io/mosire/simos/economy/model/Government.java:26,32-38,63-65
simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/GovernmentIds.java:16,32-35,45-56
simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java:648,748,750-752,3511-3521,3527-3538
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyRegisterGovernmentHandler.java:46,318-321

# Region 操作 / 重叠
simos-map/src/main/java/io/mosire/simos/map/ops/RegionOperations.java:25-33,26-28,79,119,163,186,233,357
simos-map/src/main/java/io/mosire/simos/map/region/RegionIndex.java:14-16,59-62
simos-map/src/main/java/io/mosire/simos/map/region/RegionMeta.java:10
simos-map/src/main/java/io/mosire/simos/map/spi/{MergeRegionsHandler.java:38, SplitRegionHandler.java:47, ReassignHexesHandler.java:41}
simos-app/src/main/java/io/mosire/simos/app/tools/write/ProvinceApplyPlan.java:166-172,776-778,787-794

# 逐格币种 vs 区币种 / 跨币种
simos-economy/src/main/java/io/mosire/simos/economy/model/Market.java:10-14,46
simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java:943,977,1352,1707,2161,3049,3559-3568,3572-3581,3842-3850,4494,4537
simos-economy/src/main/java/io/mosire/simos/economy/time/EnterpriseProfitBook.java:662-675,677-689（:671 已知量纲缺口；循环 :672-674/:686-688）
simos-economy/src/main/java/io/mosire/simos/economy/time/DebtValuation.java:528-530（fail-closed 对照）
simos-economy-api/src/main/java/io/mosire/simos/economy/api/money/MoneyVocabulary.java:59,71-74,87-97
```

---

## 10. 我没核到的

1. **没有跑过任何 Maven / 测试 / 世界**（任务纪律禁止）。⇒ 全篇所有"会怎样"都是**静态代码路径推断**；
   §1.6 的 `members()` 相交、§6.2 的跨币种静默搬钱，**都没有运行证据**。
2. **§1.6 的 `members()` 相交在真档世界里是否实际发生**：需要真实城市间距/半径数据（需跑 worldgen 或读真实世界配置），
   我**未核**。只确认了代码路径允许它发生、且**零守卫**。
3. **真档世界实际走 `MarketTopologyBook.from` 的哪一条分支**（`sameNumeraire` 在真档是否为真、
   `social.cities()` 是否非空）：**未核**（未跑真档）。因此"今天是一个全球区"是**在 `sameNumeraire==true` 前提下的推断**，
   不是实测读数。
4. **`MarketRegulation` 的 GM 面是否已在别的分支 / 未合并的工作树里存在**：只搜了本工作树的 `simos-*/src/main`，
   未搜 `.claude/worktrees/**` 与 git 其它分支 ⇒ **未核**。
5. **2026-10-07 ~ 10-08 之间是否还有关于市场区的新设计文档**：我只读了
   `docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 D-026/D-027 两节与任务书里的用户口述，
   **未全量扫 `docs/superpowers/specs/` 目录** ⇒ 若存在更新的落盘设计，本报告的"最新文档"判断会变（AGENTS §四.1）。
6. **真档里 `GovernmentId` 与 GOV `UnitId` 的实际配对情况**（有几个 GOV 单位政府、有几个声称 `issuable` 含 silver）：
   **未核**。只核了 seed 侧的两种形状与命令面的缺省语义。
7. **`GovTerritory` / `NationSummary` 的 BFS 在真档 GOV 树上是否会产生与 `GovScope` 不同的辖区分界**：**未核**。
8. **`MarketId` 是否在测试里被用到**（我只核了 `src/main`；生产零引用这点已核）。
