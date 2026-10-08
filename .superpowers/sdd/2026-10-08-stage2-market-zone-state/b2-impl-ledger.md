# 实现架构账本 —— 阶段 2-B2：市场区持久状态 + 命令面 + 疆域连线

> 责任区：**B2**（约束设计书 `docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md` §4.2/§4.3/§5 I22/§6.2 G2·G4/§7 M9 与 §8 的 B2 行）。
> 形态：AGENTS §一.5「一个责任区一个写代理」——只写生产代码、只过编译、不写/不跑测试、不 commit。
> 证据：探针 `/tmp/b2-probe/`（**不进仓库、不进 `src/test`**）；对照装置 `/tmp/b2-probe/SmallWorldDump.java` + 旧码 worktree。

---

## 1. 关键调查结论（file:line → 结论 → 影响）

| # | 事实（改动前） | 位置 | 影响 |
|---|---|---|---|
| 1 | 市场区是**纯派生件**：`regionByHex` 每轮现算，`EconomyData` 无该组件，Codec/ChangeSet 零命中 | `MarketTopologyBook.from`（`simos-app/.../time/MarketTopologyBook.java:123-156`）、`MarketTopology.of`（`simos-economy/.../time/MarketTopology.java:337-440`） | 划界/退让/覆盖/合并**没有可写的对象** ⇒ B2 必须先把成员格落成状态 |
| 2 | 区数由两处决定：同币 ⇒ `singleRegion`（1 区）；异币 ⇒ 城市节点 + tier 半径 + 兜底单格区 | 同上 + B1 账本 | 持久区必须能**表达同样的形状**，否则"只换权威不改数值"无法自证 |
| 3 | `EconomyData` 现有 35 个组件，`EconomyChangeSet` 逐组件对应；`with*` 每条都显式携带全部组件 | `EconomyData.java:271-436`、`EconomyChangeSet.java:118-434` | 新组件必须同时进 ctor/ChangeSet/Codec/**每个 `with*`**（漏一处 = 静默丢字段） |
| 4 | 组件物化的**四条**路径：`EconomyStateBuilder.build`（日推进）、`EconomyClearRegionHandler`（清区域）、`EconomySeedHandler`（补种）、`EconomyPayloads`（创世载荷） | 各自 `new EconomyData(...)` 调用点 | 前三条**必须**带过旧表；漏了 = 一次无关写入抹掉区表 |
| 5 | 官方汇率 A2a 挂在 `Government.officialRates`（第 7 组件），FX 窗口按 **GOV** 装配、世界级匹配域 | `Government.java:145`、`FxRoundInput.of`（`simos-economy/.../time/FxRoundInput.java:82-130`）、`EconomySettlement.java:1715` | 区级报价要落状态；**把窗口按区收窄会改 A2 的撮合语义与 F2/F3/F4 读数** ⇒ 本批不碰（见 §4 偏离 1） |
| 6 | `Government.nationRef` 是自由串、零逻辑读者；**「GOV 单位」id 与「政府身份」的连线**只有 `GovernmentIds.ofUnit/unitRefOf` | `GovernmentIds.java:33-70` | §4.3 的"某 GOV 发行哪些币 / 某币的发行 GOV"必须经这条连线，不能拿 `nationRef` 当 GOV 单位 |
| 7 | 没有「GOV 总疆域 → hex」公开函数：`GovTerritory.nominalRegions` 只给 `RegionId`；`NationSummary` 内部算过即弃 | `GovTerritory.java:48-84`、`NationSummary.java:118-152` | G4 的"逐 hex 断言"需要新增公开函数（本批交付） |
| 8 | `three-powers` 世界：3 GOV（`gov-tp-silver` 中央 + 两个省，上级=中央）、3 区（16/12/9 格，共 37 格 = 全部市场格）、3 币 | `ThreePowersWorld.java:126-194`、`ThreePowersGovBootstrap.java:363-416` | ① G4 的"中央 = 下辖 + 直辖"在这个世界可逐 hex 验证；②★ **37 格全被覆盖** ⇒ 新区没有"空格可占"，合并必须可达（见 §3 判断 3） |
| 9 | `MarketZoneReadout.describe` 的文本被既有探针/日志消费 | `MarketZoneReadout.java:110-128` | 读数格式**不许改**（否则"旧档不动"的对照面本身变了） |

---

## 2. 实现架构（代码怎么把这个世界长出来）

### 2.1 状态组件（第 36 个）

```
MarketZoneId(String value)                              simos-economy-api/.../api/id/
MarketZone(zoneId, anchor, radiusHex, hexes, legalTender, issuingGov, officialRates)
                                                        simos-economy-api/.../api/market/
EconomyData.marketZones : Map<MarketZoneId, MarketZone> （第 36 个组件）
```

- `hexes` **规范序**（(q,r) 升序）冻结在 `MarketZone` 构造期 ⇒ 迭代序是内容的纯函数（结算序/读数序可复现，也保证字节级往返稳定）。
- `radiusHex` 是**声明值**：只服务 `MarketTopology.adjacent` 的可达判据与读数；**绝不**参与成员格派生（"两套逻辑同时说了算"的堵口）。
- `officialRates` 是**区级**币对表（键 `base|quote`），与 GOV 级并列；读取口径见 `MarketZoneBook.officialRateFor`（区级优先 ⇒ 回落该区发行 GOV 的 GOV 级 ⇒ 空）。

### 2.2 `EconomyData` 构造期五条 fail-closed 守卫（`EconomyData.java` compact 构造器 B2 段）

1. 键 == 值内 `zoneId`；② 一个 hex 至多属于一个区（I22 在**状态层**判死）；③ 法定币 ∈ 词表；④ 区级汇率币对 ∈ 词表；
⑤ 发行政府已登记**且其 `issuable` 含该法定币**（"谁发行的"不许两处漂开）。遍历按 **zoneId 规范序**（报错文本可复现）。

### 2.3 命令面（三条新命令 + 一条扩展）

| 命令 | handler | 语义要点 |
|---|---|---|
| `economy.DefineMarketZone` | `spi/EconomyDefineMarketZoneHandler` | 划界：zoneId/anchor/hexes/legalTender/govUnitId(+radiusHex)；**8 条具名拒**（bad-payload / economy-not-activated / zone-already-defined / anchor-not-in-hexes / anchor-missing-market / currency-not-defined / gov-not-registered / currency-not-issuable / hex-in-other-zone / numeraire-mismatch）。判据顺序 = "先问你立的是什么区，再问这些格腾出来了吗" |
| `economy.ReassignZoneHexes` | `spi/EconomyReassignZoneHexesHandler` | 退让·覆盖：一格从 A 划到 B（源区少格=退让、目标区收格=覆盖）；**7 条具名拒**（含 `from-zone-would-be-empty`、`from-zone-anchor-would-move`、`numeraire-mismatch`）；**不改半径** |
| `economy.MergeMarketZones` | `spi/EconomyMergeMarketZonesHandler` | 合并 = **撤区**：源区格与区级汇率并入目标区，源区删除；拒 `zone-not-found`/`same-zone`/`numeraire-mismatch`/`official-rate-conflict` |
| `economy.SetOfficialRate`（**扩展**） | `spi/EconomySetOfficialRateHandler` | 新增可选 `marketZoneId`：给了就写**该区**的官方汇率覆盖（`govUnitId` 变可选、给了须等于该区发行 GOV 单位）；**老载荷逐字走老路径**（A2 的行为与拒因不变） |

三条新命令全部 `GmOnlyCommand` + `CommandTargets.targetPaths` 恒空（世界级区表，无单格资源目标）。

### 2.4 单一权威的交接（§4 判断 1）

```
MarketTopologyBook.from(state)
  ├─ economy.marketZones 非空 ⇒ byPersistentZones（MarketTopology.ofZones：成员格**照抄**持久状态；未覆盖的市场格 ⇒ 单格区兜底）
  └─ 空表 ⇒ 既有派生路径逐字不变（singleRegion / byCityRadius / craft@ 退化）——**两条路径不同时生效**
```

`MarketZoneReadout.zones/persistentZones/officialRateFor` 与 `GovCurrencyLinks` 都走同一条权威。

### 2.5 §4.3 查询面与疆域

- `GovCurrencyLinks`（app/gov）：`currenciesIssuedBy` / `govUnitIdsIssuing` / `issuingGovUnitOfZone` / `zoneOfHex` / `officialRateFor` / `describe`（全部委托 economy 侧 `MarketZoneBook`，不在 app 重算区归属）。
- `GovTerritory.nominalHexes(UnitState, GameMap, UnitId)` + `(SimulationState, UnitId)`：`nominalRegions` 的逐 hex 展开（缺失 Region 跳过；含起点直辖区 ⇒ 中央 = 下辖 + 直辖、省级 = 本区）。

### 2.6 日志（§一.9）

- 新来源 `EconomyLogSource.ECONOMY_MARKET_ZONE`（`economy-market-zone`，SYSTEM）+ 来源 `AppLogSource.APP_MARKET_TOPOLOGY`（TICK）。
- **INFO**：`MARKET_ZONE_DEFINED` / `MARKET_ZONE_HEXES_REASSIGNED` / `MARKET_ZONE_MERGED` / `ZONE_OFFICIAL_RATE_SET` / 全部具名拒（业务拒绝 = INFO）。
- **DEBUG**：各自的 `*_DETAIL` + `MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES`（source=persistent、区数、锚格缺市场、逐格币种漂移计数）。

---

## 3. 关键判断（为什么这样拆）

1. **持久区 vs 派生区的权威交接：单一权威、空表退化，而不是"两套都算一遍取其一"**。
   区表非空 ⇒ 成员格**只**由状态给定（`MarketTopology.ofZones` 采信成员表，不按半径重算）；空表 ⇒ 派生路径逐字保留。
   这样 ① 既有世界（small-world/corridor/v17levant）一个数都不动（实测见 §5.2）；② 新世界的 3 个区可以"只换权威"地逐值对齐派生基线（探针 B6/1 段实测），从而把"改了权威"与"改了数值"两件事分开证明。
   ★ 反例（**没做**）：把半径也写进归属计算 —— 那是两个权威同时说了算，一旦漂开没有任何一处能判谁对。

2. **`radiusHex` 留在记录里、但明令不参与派生**。`MarketTopology.adjacent` 用半径判"跨区可达"，若持久区一律取 0，会把 3 区世界的跨区候选悄悄关掉（B1 的 3 区派生半径是 8）。把半径落成**声明值**，既保住邻接口径，又堵住"改半径顺带改归属"。

3. **合并的守卫改成"逐格"而不是"两区法定币必须相同"**（实现中推翻过一次）。
   最初写的是 `legal-tender-mismatch`；探针把它暴露成**不可达命令**：three-powers 的 37 格**全部**被 3 个区覆盖 ⇒ 建不出第 4 个区、三种币又没有两个同币的区 ⇒ 合并永远被拒。
   改成"被并入的每一格（凡有市场行者）的计价币必须 = 目标区法定币"之后：跨币合并**三步可达**（`SetMarketNumeraire` ×N → 合并），而**没有任何一次静默换汇**（I17/I24 不放松）。★ 这一条与设计书不冲突（§4.2 只要求"合并"这条命令），但属于实现方对**可达到性**的补充判断，记在这里备控制方复核。

4. **"一个 hex 至多属于一个区"判在构造期**（状态层），不只在命令层。命令层判据是"提前给具名拒因"，构造期才是"任何路径都造不出坏状态"的那道闸（包含 codec 读入、夹具、将来的别的写入者）。

5. **范围外的一律不碰**：FX 窗口仍按 GOV 装配（`FxRoundInput.of` 未改）、`Government.officialRates` 未迁、没有 DM 面、没有 GUI 读口、没有拆区命令。理由逐条见 §4。

---

## 4. 偏离约束设计书之处（主动记录）

1. ★★ **§4.3 括注的"官方汇率在此迁到市场区"只落了"状态 + 命令面 + 读数 + 解析函数"，FX 结算侧未迁**。
   现状：`FxRoundInput.of(governments, moneyIssuances)` 仍按 GOV 遍历 `Government.officialRates`，窗口是**世界级匹配域**。
   为什么不迁：要让区级报价真的决定成交，必须给 `FxRoundInput.Window` 加"区/格作用域"并改 `MarketSettlement` 的窗口匹配域 —— 那是**行为变更**，直接落在 A2 的 F2/F3/F4 读数上（阶段 2-A 的验收已经通过），且"一个 GOV 多区不同价"的裁决规则（先到者胜？按区分别开窗？）在用户原话里没有裁定。⇒ 本批按"最保守且不撒谎"落地：状态/命令/读数/解析齐全，结算面留待阶段 3（口岸/管制）与控制方裁定。**这是本批最大的未完成面**（见 §7）。
2. **区管理的权限面：三条命令全部 GM-only，不给决策人桶开**。任务书写的是"越权（划别国的区）具名拒"，其前提是"决策人也能管理市场区"。本批按**权限单调性**取舍：用户 §1.2 的口岸政策语境（"每个行政区可以对其管理的市场区……"）需要**身份派生的作用域**（DM 只能动自己 GOV 下辖/本国的那部分区），那是与口岸政策一起设计的活（阶段 3）；在没有作用域的情况下把区命令挂到决策人桶 = 新增一条可改全世界市场区的决策人能力（权限放大）。⇒ 本批的"越权"形态改为**跨币划格的 fail-closed**：把别国货币计价的格划进本区 ⇒ 具名拒 `numeraire-mismatch`（探针第 4 段实测），并额外实测"决策人桶与 `WHITELIST` 都不含这三条"。
3. **"退让"必须有目标区**（不提供"退给三不管"的形态）：只退让不覆盖会把那些格变成未覆盖市场格 ⇒ 拓扑按既有兜底把每格退化成单格区（= 一次静默降级）。要表达"这个区不要了"走 `MergeMarketZones`（用户原话里就是"合并"）。**拆区**（从大区里划出一块给新区）本批未做 —— 它需要一条"carve-out"命令或在 Define 里加显式掠格开关，属权限/边界设计。
4. **`MarketTopology.ofZones` 与 `MarketZoneBook` 是新增的公开面**（经济侧），设计书未点名；理由是"成员格采信"与"区/币/发行者查询"需要唯一拼写点，散在 app 会重复实现。
5. **`MarketZoneReadout.Zone` 加了 4 个组件**（authority / officialRates / issuingGov / issuingGovUnit）：读口要能区分"这次读的是持久区还是派生区"。全仓 `src/test` 对 `MarketZoneReadout` **零引用**（已 grep），`describe()` 文本格式保持不变。

---

## 5. 报告（交账模板）

### 5.1 改动文件（27 个：17 改 + 10 新增；全部在允许写范围内）

**新增**
| 文件 | 作用 |
|---|---|
| `simos-economy-api/.../api/id/MarketZoneId.java` | 区稳定身份（形制同 `CurrencyId`/`MarketId`） |
| `simos-economy-api/.../api/market/MarketZone.java` | 持久区记录（成员格/法定币/发行者/区级汇率；三条构造期守卫） |
| `simos-economy/.../model/MarketZoneBook.java` | 区间/区→hex/发行者↔GOV 连线/官方汇率解析的**唯一拼写点** |
| `simos-economy/.../spi/EconomyDefineMarketZoneHandler.java` | `economy.DefineMarketZone` |
| `simos-economy/.../spi/EconomyReassignZoneHexesHandler.java` | `economy.ReassignZoneHexes` |
| `simos-economy/.../spi/EconomyMergeMarketZonesHandler.java` | `economy.MergeMarketZones` |
| `simos-app/.../gov/GovCurrencyLinks.java` | §4.3 查询面（某 GOV 发哪些币 / 某币的发行 GOV / 区→GOV 单位 / hex→区 / 官方汇率） |
| `simos-app/.../tools/write/EconomyDefineMarketZoneTool.java` | GM 薄工具 `simos.economy.defineMarketZone` |
| `simos-app/.../tools/write/EconomyReassignZoneHexesTool.java` | GM 薄工具 `simos.economy.reassignZoneHexes` |
| `simos-app/.../tools/write/EconomyMergeMarketZonesTool.java` | GM 薄工具 `simos.economy.mergeMarketZones` |

**修改**
| 文件 | 改了什么 |
|---|---|
| `simos-economy/.../EconomyData.java` | 第 36 组件 `marketZones` + B2 旧组件面便捷构造器（35 参）+ 五条构造期守卫 + `withMarketZones` + **33 处 `with*` 全部带过** + 类注 |
| `simos-economy/.../change/EconomyChangeSet.java` | `marketZones` 组件（ctor 兜底 / between / apply / isEmpty） |
| `simos-economy/.../codec/EconomyCodec.java` | `MarketZoneId` 键反序列化器 + 类注（旧档缺键 ⇒ 空表） |
| `simos-economy/.../time/EconomyStateBuilder.java` | 日推进构造点带过 `base.marketZones()`（★ 漏了 = 每次 advance 抹掉区表） |
| `simos-economy/.../spi/EconomyClearRegionHandler.java` | 清区域构造点带过 `staged.marketZones()` |
| `simos-economy/.../spi/EconomySeedHandler.java` | 补种构造点带过 `base.marketZones()` |
| `simos-economy/.../spi/EconomyCommandPayloads.java` | 新增 `requireHexArray`（格数组解析：非空、去重保序） |
| `simos-economy/.../spi/EconomySetOfficialRateHandler.java` | 扩展：可选 `marketZoneId`（区级覆盖），老路径逐字保留 |
| `simos-economy/.../time/MarketTopology.java` | 新增 `ofZones`（显式成员拓扑；未覆盖市场格 ⇒ 单格区兜底） |
| `simos-economy/.../EconomyLogSource.java` | 新来源 `ECONOMY_MARKET_ZONE` |
| `simos-app/.../time/MarketTopologyBook.java` | **持久区优先**（`byPersistentZones`），空表走原派生路径；DEBUG 装配诊断 |
| `simos-app/.../time/MarketZoneReadout.java` | `Zone` 加 authority/officialRates/issuingGov/issuingGovUnit；新增 `persistentZones` 与 `officialRateFor`；`describe` 格式**不变** |
| `simos-app/.../gov/GovTerritory.java` | 新增 `nominalHexes`（UnitState+GameMap / SimulationState 两个入口） |
| `simos-app/.../AppLogSource.java` | 新来源 `APP_MARKET_TOPOLOGY` |
| `simos-app/.../Shell.java` | 注册 3 个 handler |
| `simos-app/.../tools/SimosToolSource.java` | GM 桶注册 3 条薄工具 |
| `simos-app/.../tools/read/CatalogTool.java` | 3 条新命令的 `PAYLOAD_HINTS` + `economy.SetOfficialRate` 提示补区级分支 |

### 5.2 编译命令与 exit

```
$ pgrep -af "classworlds.launcher|surefirebooter"   # ⇒ 无命中（§一.1）
$ ./mvnw -q -o -DskipTests compile -pl simos-app -am
[exit=0]
```
（此前各阶段增量编译同样 exit=0；未跑 `test`/`verify`/`package`/`test-compile` —— 按任务书与 §一.5。）

### 5.3 自证（探针，真实生产装配；`/tmp/b2-probe/`，零 `core.register`）

**装置**
```
CP="$(ls -d $PWD/simos-*/target/classes | tr '\n' ':')$(cat /tmp/a2b-probe/cp-third.txt)"
javac -nowarn -cp "$CP" -d /tmp/b2-probe/out /tmp/b2-probe/B2Probe.java
java -cp "/tmp/b2-probe/out:$CP" io.mosire.simos.app.B2Probe        # ⇒ run5.log
java -Dsimos.app.logLevel=DEBUG -Dsimos.economy.logLevel=DEBUG …    # ⇒ run5-debug.log
```
`B2Probe` 走**真 `Shell.start`（`three-powers`）+ 真 `ShellMain.seedGenesisIfEmpty` + 真命令总线**（无 `core.register`）。

**终局：81 ✓ / 0 ✗ / exit=0**。逐段证据：

| 段 | 判据 | 实测 |
|---|---|---|
| 0 | 既有世界形态未改 | `three-powers` 创世后 `marketZones` 空；派生区 = 3 个（每区 `authority=derived`、发行者为空） |
| 1 | 命令面·定义区 | 3 条 `economy.DefineMarketZone` **真 Committed**（16/12/9 格；copper/silver/gold ↔ `gov-tp-{copper,silver,gold}`），每条 revision 恰 +1；持久区读数与派生基线**逐值相同**（zoneId/anchor/radius/成员格序/法定币/receiveWith）；拓扑读数全部 `authority=persistent` |
| 1b | 接线四处 | 3 条命令类型已在注册面（`Shell.start` 成功 ⇒ `PAYLOAD_HINTS` 齐全）；3 条 GM 薄工具在 GM 桶；**决策人桶与 `DecisionCallerFactory.WHITELIST` 都不含**这三条 |
| 2 | 区级官方汇率 | 带错 `govUnitId` ⇒ 具名拒 `gov-mismatch` 零 revision；只给 `marketZoneId` ⇒ Committed；读数 1000/1050；**GOV 级报价一字未动**；老载荷（无 `marketZoneId`）仍 Committed 写 `governments[gov-unit-gov-tp-silver].officialRates`（区表逐值未动）；老载荷形状错 ⇒ `bad-payload` |
| 3 | **G2 往返** | `decode(encode(state))` 的区表 **record 相等** + 逐字段（含 hexes 集合**与迭代序**、法定币、发行者、区级汇率）相同；`encode(decode(enc))` **字节相同**；`EconomyChangeSet.between/apply` 往返相等；变更集 JSON 往返后再 apply 仍同一份区表；`{}`（旧档变更集）⇒ 该组件 Unchanged 且 apply 后不动 |
| 4 | 命令面·划格 | 跨币划格（金区 gold 计价格 → 铜区）⇒ 具名拒 `numeraire-mismatch` 零 revision；先 `economy.SetMarketNumeraire` 再划 2 格 ⇒ Committed，金区 12→10、铜区 16→18，逐格归属正确，源区锚格未动 |
| 5 | 命令面·合并 | 金区仍有 gold 计价格 ⇒ 具名拒 `numeraire-mismatch` 零 revision；逐格改币后合并 ⇒ Committed，区表 3→2、源区撤销、目标区 28 格（= 18+10）、法定币/发行者取目标区、区级汇率并集保留 |
| 6 | 负向对照 | 空 hexes⇒`bad-payload`、未知币种⇒`currency-not-defined`、未知发行者⇒`gov-not-registered`、发行 GOV 不发该币⇒`currency-not-issuable`、重复格⇒`hex-in-other-zone`、锚格不在成员里⇒`anchor-not-in-hexes`、锚格无市场⇒`anchor-missing-market`、合并/改划不存在区⇒`zone-not-found` —— **每条都具名拒且 head 不动** |
| 7 | **G4 疆域** | 3 个 GOV 逐 hex：手工重算（BFS 子树 × `Region.hexes()`）与 `GovTerritory.nominalHexes` **逐格相同**；中央 `gov-tp-silver` = 直辖区(9 格) + 下属两个省(28 格) = 37 格；两个省各 = 本区 |
| 8 | 日推进 | 10 tick 后区表**逐值不变**（`EconomyChangeSet` 那四条物化路径都不丢组件） |

**日志（§一.9）实测**：INFO `MARKET_ZONE_DEFINED`×3 / `MARKET_ZONE_HEXES_REASSIGNED`×1 / `MARKET_ZONE_MERGED`×1 / `ZONE_OFFICIAL_RATE_SET`×1 / `MARKET_ZONE_REJECTED`×7；DEBUG 档下 `MARKET_TOPOLOGY_FROM_PERSISTENT_ZONES`×15（`source=persistent zones=3 marketHexes=37 anchorWithoutMarket=0 numeraireDriftHexes=0`）、`*_DETAIL` 各命中（define 3 / reassign 1 / merge 1 / rejected 7+2+2 / rate 1）。

### 5.4 旧档不动（**旧码 vs 新码**实测；不是论证）

装置 `/tmp/b2-probe/SmallWorldDump.java`：真 `Shell.start` + 真创世 + 真推进，落盘 economy/actor 切片**原文快照**与区读数；
**同一份源码**分别编译到（a）本树（新码）（b）`git worktree add /tmp/b2-old 957905bf`（旧码，即本批改动前的 HEAD）。

```
small-world：60 tick；corridor：创世（0 tick）
对照方式：JSON 按"对象键序无关 / 数组序有关"规范化；新码侧删掉新增的空组件键 marketZones 后比较
```
| 切片 | 结果 |
|---|---|
| `small-world.economy`（60 tick，规范化 2,086,890 B） | **OK 逐值相同** |
| `small-world.actor`（货币/库存） | **OK 逐值相同** |
| `small-world.readout.txt`（区/hex→区） | **OK 逐字节相同（531 B）** |
| `corridor.economy` / `.actor` / `.readout.txt` | **OK 逐值/逐字节相同** |
| 原始 JSON 体量 | 新 2,016,716 B vs 旧 2,016,699 B，差 17 B = 恰为 `,"marketZones":{}` |

⇒ 空表语义 = **逐值沿用现行"城市+半径"派生**，既有世界（含 60 tick 推进后的全部经济数值）一个数都不动。

### 5.5 会改变数值行为的清单

| 项 | 是否会改既有世界的数 |
|---|---|
| `EconomyData` 新组件（空表） | **否**（§5.4 实测：small-world 60 tick + corridor 逐值相同；多出的 17 字节是空表键） |
| `MarketTopologyBook` 持久区优先 | **否**（空表 ⇒ 走原路径；探针第 0 段实测派生区仍是 3 个） |
| `MarketZoneReadout`/`GovTerritory`/`GovCurrencyLinks` | **否**（只读；`describe` 文本格式未改，旧/新逐字节相同） |
| `economy.SetOfficialRate` 扩展 | **老载荷否**（逐字走老路径，探针实测 GOV 级写入不变）；新载荷（带 `marketZoneId`）只写区表 |
| **新世界（区表非空）** | **是**：成员格/锚/半径由状态给定 ⇒ 拓扑读数与跨区候选按持久区算（这是本批的目的）。★ 探针 B6 证明"持久区镜像派生基线时读数逐值相同" ⇒ 数值变化只来自**真的改了区**，不来自"换了权威" |
| 三条新命令 | 只在被调用时写区表；不调用则一个数不动 |
| FX 结算 | **未改**（`FxRoundInput`/`FxSettlement`/`MarketSettlement` 零改动） |

### 5.6 会让既有测试失效的清单（**未跑测试**，按源码核对；基线已含 A1/A2 未回填的漂移）

| 测试 | 预期影响 | 依据 |
|---|---|---|
| `simos-app/.../tools/SimosToolsTest` | 三处硬编码计数失配：命令类型 `hasSize(128)`、GM 桶 `hasSize(153)`、`*Tool.java` NAME `hasSize(163)` —— 本批 **+3 / +3 / +3**（且 HEAD 已含 A1(+3handler,+3tool) 与 A2(+1,+1) 的未回填漂移 ⇒ 实际差额更大） | `SimosToolsTest.java:863/900/1645`（实读） |
| `simos-app/.../McpServerTest` | 第 315 行断言"现有口恰好 153 条" ⇒ 本批 **+3 = 156**（叠加 A1/A2 后 ≥160） | `McpServerTest.java:315` |
| `simos-app/.../McpPortTopologyTest` | 第 248 行同款计数（37 读 + 56 非窄写 + 60 窄写 = 153） ⇒ **+3 窄写** | `McpPortTopologyTest.java:240-248` |
| `simos-app/.../McpCoverageTest` | `EXPECTED_COMMAND_TYPES` 需 +3，且 `MINIMAL_PAYLOADS` 必须覆盖每个**可提交**的 catalog type（或归入 `PRECONDITION_REJECT_TYPES`）——★ 三条新命令在 MCP 夹具世界里**不可能合法提交**（无政府/币种/市场 ⇒ 只会 `economy-not-activated`），故只能走"具名拒且不推 revision"那一档 | `McpCoverageTest.java:130/838-840` |
| `simos-app/.../access/CatalogVisibilityTest` | 计数是**现算**（`REGISTERED.size()`）⇒ **应仍绿** | `CatalogVisibilityTest.java:82` |
| `simos-app/.../tools/write/AdjudicateTickToolTest` | 若数的是"可嵌入令面"⇒ **不变**（三条都 GmOnly）；若数注册面 ⇒ +3。**未验证** | A2 账本 87→91 的口径 |
| `simos-economy/.../EconomyRoundTripTest`（反射枚举组件） | 本批 `EconomyData`/`EconomyChangeSet` **两侧同步 +1** ⇒ 应仍绿。**未验证** | `EconomyChangeSet` 与 `EconomyData` 逐条对应 |
| 任何引用 `MarketZoneReadout.Zone` 构造器的测试 | **无**（全仓 `src/test` 对 `MarketZoneReadout` 零命中，已 grep） | — |
| 任何直接 `new EconomyData(...)` 的测试 | **无**（全仓 `src/test` 零命中，已 grep）；旧 35/31/29/28 参形态由便捷构造器保留 | — |

### 5.7 未完成 / 未验证 / BLOCKED

1. **BLOCKED（设计面，需裁定）**：官方汇率的**结算侧**迁移（`FxRoundInput.Window` 加区作用域 + 窗口匹配域收窄 + 多区不同价的裁决规则）。现状 = 区级报价是**状态与读数**，但**不改变任何成交**。见 §4 偏离 1。
2. **未做（本批范围外，与任务书"明确不做"一致）**：口岸/关税/结汇/走私、商品采购窗口、铸币生产方式、`MarketReport.regulatedTariffMilli()` 的 WeakHashMap 隐患、`MARKET_CURRENCY_MISMATCH_REJECTED` 日志噪声、拆区（carve-out）命令、决策人侧的区管理作用域、GUI 读口。
3. **未验证**：
   - 未跑 `test`/`verify`/`package`/`test-compile` ⇒ **门禁未绿**；§5.6 是源码核对，不是跑出来的。
   - 未跑 `three-powers` 的真实 CLI 长跑（探针只到 10 tick + 真命令；B1 已独立跑过 120/150 tick，本批未重跑）。
   - `F7/G5` 级别的"同代码跑两遍逐值相同"未单独跑（本批的对照装置跑的是"旧码 vs 新码"，不是"新码 vs 新码"）。
   - 区表**非空**世界跑满一个产业周期的数值影响（跨区套利 G3）未测 —— 属后续批次。
   - `Spotless/Checkstyle/SpotBugs` 未跑（`verify` 归控制方/测试代理）：本批新代码未过格式化与静态检查；`EconomyData` 的 `@SuppressFBWarnings` 文案仍写"30 张 Map 组件"（未更新，属文案）。
   - 探针未覆盖：`RawCommand` 面（`simos.command.submit` 直提三条命令的成功路径 —— 已由 `core.submit` 覆盖，工具层只验了注册面）。
4. **未能 claim 任务板**：`team_task_get task-14` 返回 `agent "4ef1…" is not a member of an active Agent Team` ⇒ 无法 claim/complete（按任务书继续，此处如实记）。
