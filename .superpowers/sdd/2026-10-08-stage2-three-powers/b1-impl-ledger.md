# B1 实现架构账本 —— `three-powers`：3 市场区 / 3 GOV / 3 币 + 行政区互斥守卫

> **批次**：阶段 2-B 的第一批（B1）。约束设计书 = `docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md`
> （§1.6 用户原话、§2.2/§2.3 现状事实、§4.1 测试世界、§4.2 市场区持久化、§5 I22/I23、§6.2 G1、§7 M9、§8 分批）。
> **上位裁定**：用户 2026-10-08 第 8 轮 —— 「**重叠辖区是不被允许的**，一个地方只可能有一个行政政府，要么就是重划、变成三不管地带」。
> **交付**：生产代码一次写完 + **只 compile**（不 test/verify/package、不碰 `src/test/**`、不 commit），并自证（真 Shell + 真创世 + 真命令）。
> **落点**：`simos-app/src/main/java/io/mosire/simos/app/{world,time}/**`、`app/Shell.java`、`simos-app/.../world/EconomySeeder.java`。

---

## 0. 一句话结论

**G1 全部达成且已真跑自证**：`--world=three-powers` 起真实例后读数为 **3 个市场区 / 3 个 GOV / 3 种货币，每区法定币两两不同
（silver / copper / gold），37 格每格恰被 1 个 GOV 辖区覆盖、任两辖区交集 = 0 格**；M9 负向对照（两个 GOV 授同一 hex）在
**三条入口**上都具名拒；世界推进 **30 tick 无异常**（0 ERROR / 0 异常，逐币种总量守恒）。**不是 BLOCKED。**
核心技术风险（"3 个区"）**不需要动持久状态组件** —— 只需 `MarketTopologyBook.addNode` 去掉"非 silver 锚一律跳过"那一行
（+ 世界按两条构造性前提造），详见 §1.2。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

### 1.1 现状事实核对（对着**改动前**的 `git HEAD` 逐条核过，行号是改动前的）

| # | 位置（改动前） | 结论 | 影响 |
|---|---|---|---|
| 1 | `app/time/MarketTopologyBook.java:209-219` `sameNumeraire` | 所有有市场格的 `Market.numeraire` 逐值相同 ⇒ `from()` 直接走 `MarketTopology.singleRegion`（**恰 1 个区**） | 每区法定币不同 ⇒ 该判据为假 ⇒ 改走 `byCityRadius`（"城市节点 + tier 半径"） |
| 2 | `MarketTopologyBook.java:240-255` `addNode`，其中 **`:246-248` 显式 `if (!SILVER_CURRENCY.equals(market.numeraire())) return;`** | **多币种世界里除银区外的每一座城都成不了节点**；它们的腹地只能落进 `MarketTopology.of` 的"兜底单格区"分支 ⇒ "几个区"变成"1 个城域区 + N 个单格区"这种既非 1 也非 3 的形态 | 本批唯一的**语义**改动点（见 §2.1 改动 ③）：节点币种取本格市场自己的 numeraire |
| 3 | `MarketTopology.of(...)`（`simos-economy/.../time/MarketTopology.java:337-440`） | 区数 = **"锚格有市场"的节点数 + 兜底单格区数**（`byId` 去重、`markets` 里没被任何半径覆盖的格各自成 0 半径区） | ⇒ 要**恰好 3 个区**，两条前提缺一不可：① 每座城的锚格有市场且币种 = 本区法定币；② 每座城的半径覆盖本区**全部**有市场的格 |
| 4 | `MarketTopologyBook.java:75-83` `TIER_RADIUS_HEX`（MarketTown 2 / Town 4 / City 8 / MajorCity 16）+ `DEFAULT_CITY_RADIUS_HEX=8`；`tierOf(props)` 读 `props.tier` | 半径由城市 `props.tier` 决定；`SmallWorld` 的 `CreateCity` 载荷**不发 props** ⇒ 它的两座城都用默认 8 | 本世界**显式发** `props.tier=City`（半径 8）：不把"3 个区"挂在一个默认值上（`ThreePowersWorld.createCityPayload`） |
| 5 | `app/world/EconomySeeder.java:718` `MARKET_FACTORY`（一个共享 `Market`）+ `:1519` `marketNode`（只发 `numeraire`/`prices` 两个值）+ `:3358` `markets.put(hex, MARKET_FACTORY)` + `:2038/:2055` `genesisMoney(...)` 钱包 | 市场计价币与家户钱包币种**都由硬编码 `MARKET_NUMERAIRE`（银）决定**，且**同一处接缝** | 多币种世界必须让**两者同源**：钱包币 ≠ 本格市场币 ⇒ 该格家户的每一次买盘都撞 I19 的 `currency_mismatch`（本地市场对它关门） |
| 6 | `app/world/EconomySeeder.java:3058` `planProductionRuntime`（逐格循环）/ `:3342` 作坊主工资周转金 | 逐格派生量（亩/织机/作坊…）在循环里一次算好 | 逐格计价币函数在这里落地最省事、也最不可能漂（同一处算，喂给市场 + 全部钱包） |
| 7 | `simos-economy/.../spi/EconomySetMarketNumeraireHandler.java`（A2b 新增，GmOnly） | **已有**"改某一格市场计价币"的真命令，其类注明写"改了 ⇒ D-027 同币判据不再成立，市场拓扑退回城市节点 + tier 半径装配" | 该命令是"多计价币世界"的**既有**写口；但**只在创世后**可用（要求币种已进世界词表）⇒ 本批不用它改币，改用**创世期一次播种**（见 §3 判断 2） |
| 8 | `simos-economy/.../spi/EconomyRecordMoneyIssuanceHandler.java:110-120` | `INITIAL_ENDOWMENT` 记录**要求该政府 `issuable` 含该币种**（否则具名拒 `currency-not-issuable`） | **实测踩到**（§4 偏离 2）：省国库的银余额没有发行者 ⇒ 发行审计只对"该 GOV 真正发行的币"发 |
| 9 | `simos-economy/.../spi/EconomyRegisterGovernmentHandler.java`（载荷含 `"issuable"?:[…]`）+ `EconomyData` 构造期"同一币种只能有一个发行主体" | **银**在词表里早已定义 ⇒ `economy.DefineCurrency` 以 `currency-already-defined` 拒它 ⇒ "谁是银的发行政府"只能在 `RegisterGovernment` 的 `issuable` 里声明 | 中央 GOV 用 `issuable:["silver"]` ⇒ 银也有 GOV 发行主体，三区口径一致（§2.3） |
| 10 | `app/tools/write/GovWorldBootstrap.java`（833 行） | 它把"中央 + 省"两级 GOV **写死**（id/座位/辖区/名额/第二币种全是类常量），且 `GovSpec`/`bootstrapGov`/`SET_GOV_FORMATION_TYPE`/`RaiseUnitPlan.householdIdFor` 都是**包内可见** | ⇒ 跨包既不能改也不能调；B1 只能**另写一份 N-GOV 的同类链**（§2.2），并用各 handler 自己公开的 `TYPE` |
| 11 | `simos-sd/.../guard/RegionDeleteGuard.java`（100 行，A6 的形制）+ `simos-core/.../CommandBus.java:396/680`（handler **之前**按注册序调用）+ `app/Shell.java:869-870`（注册点） | `MutationGuard` 的契约 = **只读、只回答可不可以**；Core 不认识领域类型 | 互斥守卫照此形制落组合根（§2.4） |
| 12 | `simos-map/.../spi/{UpdateRegion,ReassignHexes,MergeRegions,SplitRegion,CreateRegion,RandomizeRegion}Handler.java` | 能改"区 → 成员格"的命令不止一条；`map.RandomizeRegion` 只随机化**地形**、不动成员格 | 守卫覆盖"授辖区"的三条真入口；其余四条**明确不拦**并写清理由（§2.4 的边界表） |

### 1.2 ★ "3 个市场区"到底由什么决定（本批核心技术风险的完整判据）

```
区数 = 1                                   若 sameNumeraire 为真（所有市场同币）
     = |{锚格有市场的城市}| + |{没被任何半径覆盖的市场格}|   否则
```

`three-powers` 按**两条构造性前提**把第二式钉成 3：

1. **每格市场的计价币 = 该格所属区的法定币** ⇒ 三个区三币 ⇒ `sameNumeraire` 为假；
2. **每座城（tier=City，半径 8）覆盖本区全部有市场的格；且每区恰好一座城、城的锚格就在本区** ⇒ 节点数 = 3、兜底单格区数 = 0。

37 格 = 半径 3 完整六边形；三个城锚取**相隔 120° 的三个角**（`(3,0)/(0,-3)/(-3,3)`，两两 hex 距离 6）⇒
"半径内最近节点 + 同距按 nodeId 字典序"的划分给出 **9 / 16 / 12 格**三片（Python 独立复算过，与 Java 侧一致）。
★ 三区划分函数 `ThreePowersWorld.zoneOf` 与市场拓扑的归属规则**逐字同源**（同用 `HexCoord.distanceTo`、同距同取"城市 id 字典序小者"，
且城市 id 序 = 声明序）⇒ **行政区与市场区逐格重合**（探针第 3 条逐区断言 16/16、12/12、9/9 全等）。

---

## 2. 实现架构（代码怎么把这个世界长出来）

### 2.1 改动面（4 改 + 4 新；详见 §5 清单）

① **`EconomySeeder`：新增"逐格计价币"入口（老入口逐值不变）**
- `marketFor(CurrencyId)`（`:732`）：价格表恒为出厂表、只换计价币；`marketFor(MARKET_NUMERAIRE)` 与 `MARKET_FACTORY` **逐值相等**
  （载荷只发 numeraire/prices 两个值 ⇒ 逐字节同）。
- 新公开重载 `plan(mapId, seeding, map, perCapita, profile, conditions, Function<HexCoord,CurrencyId> numeraireOf)` + 便捷重载
  `plan(mapId, seeding, map, numeraireOf)`；老入口一律传 `DEFAULT_NUMERAIRE_OF`（`:1715`，恒 `MARKET_NUMERAIRE`）。
- 逐格循环里 `CurrencyId numeraire = numeraireOf.apply(hex)`（`:3252`，null ⇒ 具名抛，不静默落银），同一处喂给：
  ① 本格市场 `markets.put(hex, marketFor(numeraire))`（`:3501`）；② 本格家户钱包 `genesisMoney(pop, perCapita, numeraire)`
  （新 3 参重载 `:1790`，2 参老口等价传银）；③ 作坊主工资周转金 `wallet.merge(numeraire, …)`（`:3483`）。
- **不折算任何价格/余额**（I17：世界无汇率）—— 换计价币就是按新币读同一个数。

② **`MarketTopologyBook`：`addNode` 去掉"非 silver 锚一律跳过"**（`:272`，旧 `:246-248`）
- 节点币种 = 本格市场的 `numeraire`；`receiveWith` 取该币在**世界状态**里的工具 id（`receiveInstrumentOf` `:296`；
  查无 ⇒ 退回 `SILVER_SPECIE` 占位，该字段**全仓零读取者**）。
- **对既有世界零影响的三重理由**：既有世界逐格市场都是银 ⇒ `sameNumeraire` 真 ⇒ 走 `singleRegion`，`addNode` **根本不会被调**；
  唯一会调到它的形态就是本批新增的"计价币不一致"世界；且**实测对照**（§6.2）证明 `small-world` 仍 = 1 个区且经济快照与旧码逐值相同。

③ **`ThreePowersWorld`（新，887 行）：世界形态 + 创世编排**
- 常量全具名：37 格 / 三个行政区（`province-silver|copper|gold`）/ 三城（`c-tp-silver|copper|gold`，tier=City）/
  三 GOV（`gov-tp-silver`=中央、`gov-tp-copper`/`gov-tp-gold`=省，上级=中央）/ 三币（`silver`+`copper`+`gold`）；
  人口 37×150 农村 + 800 城镇 = 6,350。
- 创世命令序与 `SmallWorld` 同形：`social.SetPopulation` → `social.CreateCity`×3 → `social.SeedGroups` →
  `economy.Seed`（**带逐格计价币函数**）→ `actor.Seed` → `ThreePowersGovBootstrap.apply`（3 个 GOV）→ 创世日志。
- 区划分/币种映射/格集全部公开只读（`zoneOf` / `currencyOfZone` / `numeraireAt` / `hexesOfZone`）⇒ 探针与后续批次可直接读，不必反射。
- 日志（§一.9）：`THREE_POWERS_GENESIS`（总览）/`_ZONES`（几个区）/`_GOVS`（几个 GOV）/`_CURRENCIES`（几种货币）/
  `_ZONE_NUMERAIRE`（**每区法定币，逐区一条**）/`_EXCLUSIVITY_OK`（I23 逐 hex 断言 37/37、overlaps=0），全 INFO + `origin=shell-lifecycle`。

④ **`ThreePowersGovBootstrap`（新，978 行）：N 个 GOV 的行政链**
- 逐 GOV 的命令序与 `GovWorldBootstrap` **逐条一致**（官署产业 → CreateUnit → 政府家户+账户 → SetGovFormation →
  RegisterGovernment →[新币] DefineCurrency → SetJurisdiction → UpsertGovUnit → 官吏户+转移 2 名成年男性 →
  校验 Social 投影劳动 → SetUnitHouseholds → RegisterHousehold → 账户 → AssignGovPost → SetHouseholdLabor →
  SetGovServiceCommitment → SetAdministrationPlan → SetBudgetPolicy）。
- 之后统一：`actor.AdjustAccounts`（逐 GOV 国库 粮/布/银 + 本区法定币）+ 逐 GOV × **其真正发行的币**一条
  `economy.RecordMoneyIssuance`（INITIAL_ENDOWMENT）。
- **创世期 I23 自检**（`apply` 入口）：座位在图上、辖区存在且含座位、辖区 id 不重复、**逐 hex 两两不相交**、
  上级先于下级、币种可定义且一币一发行人 —— 任一不过 ⇒ 整次创世具名抛（不落半截世界）。
  ★ 必要性的理由：创世走 `bootstrapGenesis`、**绕过命令总线** ⇒ 组合根守卫在创世期不会被调到。

### 2.2 为什么另写 `ThreePowersGovBootstrap` 而不是复用 `GovWorldBootstrap`

见 §1.1 #10：后者写死 2 个 GOV、且其内部零件包内可见（跨包改不了也调不到），而它的文件不在 B1 的文件所有权内。
⇒ 取舍是"**复制同一命令序**（逐条对照）+ 只依赖各 handler 公开的 `TYPE`"，并在类注里双向写明"改一处必须同时改另一处"。
本批只用到 4 个包内可见的字符串常量（`unit.SetGovFormation`/`unit.SetJurisdiction` 各拼一次、`hh-unit:` 前缀、
`ActorAdjustAccountsTool.NAME` 是 public）—— 全部在类注与 §4 里点名。

### 2.3 每区"法定币 + 发行政府"

| 区 | 城（节点 id） | 行政区 | 法定币 | 发行政府 | 国库（创世禀赋） |
|---|---|---|---|---|---|
| 银（中央） | `c-tp-silver` (3,0) 9 格 | `province-silver` | `silver` | `gov-tp-silver`（`RegisterGovernment.issuable=["silver"]`） | 银 200,000 毫 |
| 铜（省） | `c-tp-copper` (0,-3) 16 格 | `province-copper` | `copper` | `gov-tp-copper`（`DefineCurrency`） | 铜 100,000 + 银 100,000 |
| 金（省） | `c-tp-gold` (-3,3) 12 格 | `province-gold` | `gold` | `gov-tp-gold`（`DefineCurrency`） | 金 100,000 + 银 100,000 |

★ 银的"发行政府"用 `issuable` 声明而不是 `DefineCurrency`（银已在词表里，见 §1.1 #9）——这是**本批新增的一处世界事实**，
不是设计书的杜撰：设计书 §9-2 的建议是"silver（央）/copper（省甲）/gold（省乙）"，本实现把它落成"三币各有 GOV 发行主体"。

### 2.4 行政区互斥守卫（`GovJurisdictionGuard`，组合根注册）

- **住哪**：`app/world/`（组合根）。理由（类注里写全）：判据要**同时**看 map 的 `Region→hexes` 与 unit 的
  `Unit.jurisdiction`；`simos-map` 永远不知道 GOV（铁律 3），`simos-gov` 也看不见区表 ⇒ 只有组合根看得见两者
  （与 `sd.RegionDeleteGuard` 必须住 `simos-sd` 同一条推理）。★ **没有**写进 `simos-map`。
- **拦哪三条**（"授辖区"的三条真实入口）：

| 命令 | 判据 | M9 证据 |
|---|---|---|
| `unit.SetJurisdiction`（主入口） | 载荷 `regions[]` 的 hex 并集 ∩ **另一个 GOV** 的辖区 hex ≠ ∅ ⇒ 拒 | 探针 10/11/12（拒绝 + 具名 + 不留 revision） |
| `map.UpdateRegion` | 该区正被某 GOV 管辖、且载荷 `hexes[]` ∩ 另一个 GOV 的辖区 ≠ ∅ ⇒ 拒 | 探针 14 |
| `map.ReassignHexes` | `toRegionId` 正被某 GOV 管辖、且载荷 `hexes[]` ∩ 另一个 GOV 的辖区 ≠ ∅ ⇒ 拒 | 探针 15 |

- **拒因具名**（日志与 reason 同一份）：命令 + hex + 对方 GOV 的辖区 + 本方 GOV + 点名区 + 用户第 8 轮原话引文。
  实测样例：`行政区互斥（I23，用户 2026-10-08 第 8 轮裁定「重叠辖区是不被允许的」，命令 unit.SetJurisdiction）：hex 1_0 已属 GOV gov-tp-silver 的辖区 province-silver，gov-tp-copper 不得再授（本次点名区: province-silver）—— 要改请先重划/退让，或让它成为三不管地带`
- **日志**：拒绝时一条具名 DEBUG `GOV_JURISDICTION_OVERLAP_REJECTED`（`origin=command-entry`；命令/双方 GOV/hex/区）；
  **拒绝本身**由 `CommandBus` 按既有口径落 INFO（`COMMAND_REJECTED` + reason），守卫不重复报。
  ★ **级别偏离任务书一处**：任务书写"守卫拒绝具名 DEBUG"，而 AGENTS §一.9（2026-10-23 用户裁定）是"业务拒绝 = INFO" ——
  本实现让**两者同时成立**：总线的 INFO（既有）+ 守卫的 DEBUG 具名理由（本批新增），不新增第三个 INFO 源。
- **明确不拦（如实记，不是漏写）**：`map.CreateRegion`（新区从零开始，不被任何 GOV 管辖 ⇒ 造不出 GOV 间重叠）、
  `map.MergeRegions`/`SplitRegion`/`DeleteRegion`（源区**被删** ⇒ 覆盖它的 GOV 辖区随之**悬空**，那是"辖区悬空"另一类问题，
  不制造"两 GOV 覆盖同一 hex"；拦了会改变这些命令在既有世界里的合法用法）、`map.RandomizeRegion`（只随机化地形）。
- **fail-closed 与确定性**：载荷坏 / 切片缺席 / 点名的区查无 / `regionId` 坏 ⇒ **放行**（判不了的不猜：坏载荷由 handler 具名拒，
  缺切片是装配故障、会由 handler 当场炸出来）；GOV 按 `UnitId.value()` 升序、冲突 hex 取 `(q,r)` 最小者 ⇒ 同一份坏状态永远给同一条理由。
- **正向对照**：铜 GOV 重授**自己**的辖区 ⇒ **Committed**（探针 13）—— 守卫不是"一律拒"的装饰。

### 2.5 只读读数（`MarketZoneReadout`，app/time）

`MarketTopologyBook` 是包内可见 ⇒ "几个区"此前**没有包外读口**，而 G1 恰是"3 个区"⇒ 新增公开只读门面：
`zones(state)` / `zoneCount(state)` / `zoneOf(state, hex)` / `describe(state)`，纯值、不可变、不改任何状态、不进 `EconomyData`
（市场区本批**仍是派生件**，I22 的持久化是 B2）。

---

## 3. 关键判断（为什么这样拆、推翻过什么）

1. **"3 个区"不需要市场区持久化** ⇒ 本批**不**动 `EconomyData`/Codec/ChangeSet（B2 的范围）。
   判据：§1.2 的两条构造性前提都在"派生层 + 创世播种"里。
2. **推翻过"用 `economy.SetMarketNumeraire` 创世后逐格改币"**：它要求币种**已进世界词表**，而
   `economy.DefineCurrency` 又要求"政府已登记"、登记又要求"economy 已激活（先 Seed）" ⇒ 命令序被锁成
   `Seed(银) → 登记 GOV → DefineCurrency → SetMarketNumeraire ×25 → 逐户换钱包`，最后一档（把每户的银换成铜/金）
   既要对 148 个家户各发命令、又会与 `INITIAL_ENDOWMENT` 的逐币种审计**互相矛盾**（记录说银、钱包是铜）。
   ⇒ 改为**创世播种期一次到位**（逐格计价币函数同时决定市场币与钱包币），审计与余额天然同源。
   `SetMarketNumeraire` 仍是既有命令、留给 GM 的事后用（它的类注正好描述了本批的世界形态）。
3. **逐格计价币只做"播种入口"，不做"状态组件"**：`plan(...)` 的重载语义是"这一格按什么钱报价 + 这格家户手里是什么钱"，
   两件事**同源**（§1.1 #5 的病根）。B2 做持久化时，区→法定币的**单一权威**会从"调用方传的函数"变成"状态里的字段"，
   本批的接缝正好是那个替换点。
4. **3 个 GOV 走 GOV 单位路径（不建世界级政府家户）**：`PopulationSeeder.seed(plan, tick)`（不传 `governmentRef`）⇒
   `genesisAuditGovernment()`（不持户、`issuable` 空）——这是本仓"多国/多省播种"的既有路径，家户禀赋的 `INITIAL_ENDOWMENT`
   审计归属 `world-silver`（既有约定），三个 GOV 的国库禀赋各有自己的记录。
5. **官吏来源逐区各取一条批次**（而不是 SmallWorld 的"前 N 条"）：确定性不变，但保证三个 GOV 的官吏来自各自的区
   （世界形态更像"三个政府"而不是"一个政府的三份拷贝"）。
6. **不设税率**：`unit.SetTaxRate` 本批不发（新地区从 0‰ 起）。理由是现行税收面是**银本位**的
   （`JurisdictionDailyTax.SILVER`），对持铜/金的家户征银只会得到"应纳 > 0 / 实纳 0"的噪声；要不要征是 GM 的判断。
   实测读数：`TAX_UNIT_SKIPPED reason=no-rated-region`（DEBUG，每 GOV 每天一条，非异常）。

---

## 4. 偏离约束设计书之处（逐条如实记）

| # | 设计书说 | 本批实际 | 原因 / 影响 |
|---|---|---|---|
| 1 | §4.1「3 个市场区 / 3 个 GOV / 3 种货币 / 至少 3 座城市」+ §9-2 建议"三币 = silver（央）/copper/gold" | 一致；但**银的发行主体**是中央 GOV（`RegisterGovernment.issuable`），不是"创世审计政府 world-silver" | 银已在词表里 ⇒ `DefineCurrency` 拒它（§1.1 #9）。★ 影响：`INITIAL_ENDOWMENT` 的家户禀赋记录仍归 `world-silver`（播种器既有约定），**国库**禀赋记录归各自的 GOV —— 两个主体不同源，读口需按 `kind` + `governmentId` 分别读。这是既有约定的延续，不是本批新造的歧义 |
| 2 | §4.2「市场区 = 持久状态 + 命令面」是 B 的范围 | 本批**不做**（B2）；市场区仍是派生件，`addNode` 的最小改动代替"区→法定币的持久字段" | 任务书明确 B1 只做"能落成 3 个区"的最小面；I22 留 B2 |
| 3 | 设计书未提"国库的银工作余额" | 每个 GOV 国库都发 100,000 毫银（现行行政结算/官吏工资规则的记账币是银），**省的银没有发行审计**（它不是银的发行政府） | `economy.RecordMoneyIssuance` 的 `currency-not-issuable` 具名拒把这条钉死了（实测踩到）。口径与 `GovWorldBootstrap` 对银的做法逐字相同；"省国库的银怎么来"= 财政调拨，而**调拨的命令面不在 B1** |
| 4 | §4.3「发行政府 ↔ GOV 连线」的**完整查询面**不在 B1 | 本批只落"发行事实"（`Government.issuable`），未加查询函数 | 任务书"明确不做"清单第 3 条 |
| 5 | §5 I22「市场区单一权威 = 持久状态」 | 本批**未达成**（仍是派生） | 同上；本批交付的是"3 个区真的落成"这条 G1 判据 |
| 6 | 任务书文件所有权：允许写 `app/world/**`、`app/time/**`、`Shell.java`、`economy/main/**` | 守居住在 `app/world/GovJurisdictionGuard.java`（而非 `app/gov/`） | `app/gov/**` 不在允许写范围内；且守卫与"创世世界形态"同属本批的责任区。★ 已把理由写进类注 |
| 7 | 任务书："守卫拒绝具名 DEBUG" | 同时保留总线的 INFO（既有）+ 新增守卫 DEBUG | 见 §2.4；AGENTS §一.9 的"业务拒绝 = INFO"由总线满足 |
| 8 | — | `MarketZoneReadout`（新公开读口）不在任务书列举的交付物里 | 必要性：`MarketTopologyBook` 包内可见 ⇒ 没有它就无法**真读**"几个区"（G1 判据），只能反射或读代码推断（两者都不是证据） |

---

## 5. 报告模板（交账用）

### 5.1 改动文件

| 文件 | 类型 | 内容 |
|---|---|---|
| `simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersWorld.java` | 新（887 行） | 世界形态（37 格/3 区/3 城/3 GOV/3 币）+ 创世编排 + 创世日志 + 公开只读（`zoneOf`/`currencyOfZone`/`numeraireAt`/`hexesOfZone`） |
| `simos-app/src/main/java/io/mosire/simos/app/world/ThreePowersGovBootstrap.java` | 新（978 行） | N 个 GOV 的行政链（命令序逐条同 `GovWorldBootstrap`）+ 创世期 I23 自检 + 国库禀赋/发行审计 |
| `simos-app/src/main/java/io/mosire/simos/app/world/GovJurisdictionGuard.java` | 新（429 行） | 行政区互斥 `MutationGuard`（三条入口 + 具名拒 + DEBUG） |
| `simos-app/src/main/java/io/mosire/simos/app/time/MarketZoneReadout.java` | 新（129 行） | 市场区只读读数（几个区 / 每区法定币 / 成员格） |
| `simos-app/src/main/java/io/mosire/simos/app/time/MarketTopologyBook.java` | 改（+58/-6） | `addNode` 去掉非 silver 跳过；节点币种 = 本格市场币；`receiveInstrumentOf` |
| `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java` | 改（+171/-14） | 逐格计价币入口（`marketFor` / `plan(...,numeraireOf)` / `genesisMoney(...,numeraire)` / 逐格 `numeraire`）；老入口传 `DEFAULT_NUMERAIRE_OF` |
| `simos-app/src/main/java/io/mosire/simos/app/world/WorldRegistry.java` | 改（+17/-3） | 新增 `THREE_POWERS` 常量 + 登记项 + 类注 |
| `simos-app/src/main/java/io/mosire/simos/app/Shell.java` | 改（+5） | 组合根注册 `GovJurisdictionGuard` + import |

### 5.2 编译命令与 exit

```bash
# 跑前确认本仓无 Maven（§一.1）
pgrep -af classworlds.launcher | grep -v grep || echo "(no maven running)"   # ⇒ (no maven running)
tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am
echo "[compile exit=$?]"   # ⇒ [compile exit=0]
tools/mvn-lock.sh -q spotless:apply   # ⇒ exit=0；git status 只出现上面 8 个文件（无越界格式化）
```
★ 未跑 `test`/`verify`/`package`（任务书明令）；未 `git commit`。

### 5.3 会改变数值行为的清单（给测试代理）

| 改动 | 会不会改既有世界的数值 | 依据 |
|---|---|---|
| `EconomySeeder` 逐格计价币入口 | **不会**（老入口恒传银） | 代码路径 + §6.2 的**旧码 vs 新码结构对照**（`small-world`/`corridor` 经济快照逐值相同） |
| `MarketTopologyBook.addNode` | **不会**（既有世界同币 ⇒ 走 `singleRegion`，`addNode` 不被调用） | §1.1 #5 + 探针实测 `small-world` 仍 1 个区、成员 19 格 |
| 新世界 `three-powers` | **新数值**（新世界，不影响任何既有世界） | 探针 §6.1 |
| 组合根新增守卫 | 只可能**拒绝**命令（不写状态）；对非 GOV 单位与非重叠授辖区**无影响** | 探针 13（正向对照 Committed）+ §7.2 |
| `Shell.java` 注册一行 | 无 | — |

### 5.4 会让既有测试失效的清单（**静态审查结论；本批未跑测试**）

- **高风险（需测试代理实跑确认）**：`simos-app` 里"用真 `Shell`/真命令总线 + 两个及以上 GOV 单位 + 授/改辖区造成重叠"的用例。
  逐条静态排查了 5 个可疑文件，**未发现**这样的用例：
  - `gui/SdDecisionDocsApiTest.java:275-278` 的 `map.UpdateRegion` **只给 `meta`、不给 `hexes`** ⇒ 守卫放行（无重叠可言）；
  - `access/CatalogVisibilityTest`、`AdjudicateTickToolTest:600/647/808`、`SimosToolsTest`、`McpServerTest`、
    `McpPortTopologyTest`、`McpCoverageTest` 里的 `unit.SetJurisdiction`/`map.UpdateRegion`/`map.ReassignHexes` 都是
    **目标路径样本/目录断言**（`CommandTargets` 声明），不经过命令总线 ⇒ 守卫不被调到；
  - `LevyRegionToolTest`/`LevyRegionPlanTest` 只在**消息断言**里提到 `unit.SetJurisdiction`。
- **中风险**：任何用 `SmallWorld`（2 个 GOV、辖区互斥）后**故意**把 `small-world` 区扩到含首都格的用例 ——
  这在新口径下是**应当被拒**的（用户第 8 轮裁定），若有用例依赖它，属"用例在验证旧语义"，应由测试代理按新裁定迁移。
- **低风险/无影响**：`simos-map`/`simos-unit`/`simos-core` 的 `map.*`/`unit.SetJurisdiction` 用例自建总线、不注册本守卫；
  `WorldRegistry`/`ShellMainParse` 无用例钉"已登记世界清单"或未知 worldId 的消息文本（已 grep 确认）。
- **其它**：`EconomyVocabularyGuardTest` 的 `"silver"` 字面量恰一处 —— 本批新增代码**只用 `MoneyVocabulary` 常量**，
  新写的字面量只有 `gold`（未被任何护栏钉死）；`ArchitectureGuardsTest` 的 `implements ChangeSet` 恰 9 个 —— 本批没加 ChangeSet。

### 5.5 未完成 / 未验证项

- **未做（B2 及之后，任务书"明确不做"）**：市场区持久状态组件（`EconomyData` 新组件 + Codec + 往返不变式）/ 退让·覆盖·合并命令面 /
  发行政府 ↔ GOV 完整查询面 / GOV 总疆域 → hex 公开函数 / 跨区汇率套利 / 口岸关税 / 商品采购窗口 / 铸币。
- **未做（跨区贸易的可交易性）**：三区两两相邻但币种不同 ⇒ 跨区订单在**每一条**成交尝试上都撞 I19 具名拒
  （实测 30 tick 内 `MARKET_CURRENCY_MISMATCH_REJECTED` 42,004 条 INFO）。**区内**成交正常（每市场轮 ~180 笔成交），
  逐币种总量守恒。这是设计书 §4.4 预期的状态（跨区要靠 FX，B2/A2 的窗口面），但它同时是一条**日志量**问题：
  建议后续把该事件降到 TRACE 或按轮聚合（不在本批范围，且它是 economy 既有行为，本批没改）。
- **未验证（本批明令不跑）**：`test`/`verify`/`package` 全未跑 ⇒ 上表 §5.4 的"既有测试影响"是**静态审查**，不是实测。
  ④ F7/G5 的"两遍逐值相同"未做（本批只跑了一遍 30 tick + 三遍创世对照）。
- **未验证（二次确认项）**：`--world=three-powers` 的**真实 CLI 起进程 + GUI 200** 未跑（探针只验到 `ShellMain.parse` 解析出
  worldId 且已登记 + `ShellMain.seedGenesisIfEmpty` 真创世成功）。真起服务需要端口与 jar（§二 禁止覆盖运行中 jar），留给控制方。
- **已知边界**：`GovJurisdictionGuard` 不覆盖"区域被删/被合并 ⇒ GOV 辖区悬空"（§2.4 表下注），也不覆盖
  `map.CreateRegion` 造出的新区（它不被任何 GOV 管辖）。

---

## 6. 自证（探针；`/tmp`，不进仓库、不进 `src/test`）

- 探针源码：`/tmp/b1-probe/B1Probe.java`（真 `Shell.start` + 真 `ShellMain.seedGenesisIfEmpty` + 真命令总线，**零 `core.register`**）。
- 运行命令：
  ```bash
  CP="$(ls -d $PWD/simos-*/target/classes | tr '\n' ':')$(cat /tmp/a2b-probe/cp-third.txt)"
  javac -nowarn -cp "$CP" -d /tmp/b1-probe/out /tmp/b1-probe/B1Probe.java
  java -Dsimos.app.logLevel=DEBUG -cp "/tmp/b1-probe/out:$CP" io.mosire.simos.app.B1Probe
  ```
- 终局输出：`/tmp/b1-probe/final.log`（**27 ✓ / 0 ✗，exit=0**）。

### 6.1 终局读数（逐字摘录）

```
  ✓ 1 G1 市场区数 = 3（3）
  ✓ 2 G1 每区法定币两两不同（3 种）（[copper, gold, silver]）
  ✓ 3 区 c-tp-copper 的市场成员格 == 行政区 province-copper 的格集（16/16）
  ✓ 3 区 c-tp-gold 的市场成员格 == 行政区 province-gold 的格集（12/12）
  ✓ 3 区 c-tp-silver 的市场成员格 == 行政区 province-silver 的格集（9/9）
  ✓ 4 区 c-tp-copper/gold/silver 的逐格市场计价币 == 本区法定币 copper/gold/silver（不符 0 格）
  ✓ 5 G1 GOV 数 = 3（[gov-tp-copper, gov-tp-gold, gov-tp-silver]）
  ✓ 6 G1 货币种数 = 3（[silver(银), copper(铜), gold(金)]）
  ✓ 7 I23 逐 hex：任意两 GOV 辖区交集 = 0 格（重叠 0 格）
  ✓ 8 I23 逐 hex：37 格每格恰被 1 个 GOV 覆盖（37/37；并集 37 格）
  ✓ 9 区 c-tp-copper 法定币 copper 有发行主体（gov-tp-copper）（[copper]）
  ✓ 9 区 c-tp-gold 法定币 gold 有发行主体（gov-tp-gold）（[gold]）
  ✓ 9 区 c-tp-silver 法定币 silver 有发行主体（gov-tp-silver）（[silver]）
  ✓ 10 M9 负向对照：铜 GOV 授银区（重叠）⇒ 具名拒（Rejected）
  ✓ 11 M9 拒因具名（点名 hex + 双方 GOV + I23）
  ✓ 12 M9 被拒命令不留 revision（1 vs 1）
  ✓ 13 正向对照：铜 GOV 重授**自己**的辖区 ⇒ 落 revision（Committed head=2）
  ✓ 14 M9 第二条入口：map.UpdateRegion 把铜区一格划进银区 ⇒ 具名拒
  ✓ 15 M9 第三条入口：map.ReassignHexes 把铜区一格划给银区 ⇒ 具名拒
  ✓ 16 推进 30 tick（0 → 30）（30）
  ✓ 17 30 tick 后市场区仍 = 3（3）
  ✓ 18 30 tick 后 GOV 仍 = 3
  ✓ 19 30 tick 逐币种总量不变（无铸币/无外部注资）（前 {copper=136600, gold=129400, silver=424600} 后 {…同…}）
  ✓ 20 CLI --world=three-powers 解析出该 worldId 且已登记（three-powers / [v17levant, small-world, corridor, three-powers]）
      [30 tick] 人口=6371（创世 6350） 家户=342 市场格=37
      [30 tick 国库] gov-tp-silver = {silver=199040}
      [30 tick 国库] gov-tp-copper = {copper=100000, silver=99040}
      [30 tick 国库] gov-tp-gold   = {gold=100000, silver=99040}
      [30 tick] 银区城 3,0 = silver；铜区城 0,-3 = copper；金区城 -3,3 = gold
```
- 30 tick 段**0 ERROR / 0 异常**（`grep -cE " ERROR " run log` = 0、`Exception` = 0）；唯一 WARN 是既有的
  `POPULATION_SETTLE_REMAINDERS_CLEANED` ×1；市场轮照常成交（第 30 天 `fills=181`）。

### 6.2 创世日志（§一.9 四件事 + I23）与守卫 DEBUG

```
INFO io.mosire.simos.app.shell event=THREE_POWERS_GENESIS origin=shell-lifecycle … hexes=37 regions=3 cities=3 population=6350 markets=37
INFO … event=THREE_POWERS_GENESIS_ZONES … zones=3 zoneIds=[c-tp-copper, c-tp-gold, c-tp-silver]
INFO … event=THREE_POWERS_GENESIS_GOVS … govs=3
INFO … event=THREE_POWERS_GENESIS_CURRENCIES … currencies=3 currencyIds=[silver, copper, gold]
INFO … event=THREE_POWERS_GENESIS_ZONE_NUMERAIRE … zone=c-tp-copper anchor=0_-3 hexes=16 numeraire=copper
INFO … event=THREE_POWERS_GENESIS_ZONE_NUMERAIRE … zone=c-tp-gold   anchor=-3_3 hexes=12 numeraire=gold
INFO … event=THREE_POWERS_GENESIS_ZONE_NUMERAIRE … zone=c-tp-silver anchor=3_0  hexes=9  numeraire=silver
INFO … event=THREE_POWERS_GENESIS_EXCLUSIVITY_OK … regions=3 hexesChecked=37 overlaps=0
DEBUG io.mosire.simos.app.tool event=GOV_JURISDICTION_OVERLAP_REJECTED origin=command-entry command=unit.SetJurisdiction gov=gov-tp-copper regions=province-silver hex=1_0 owner=GOV gov-tp-silver 的辖区 province-silver
DEBUG … command=map.UpdateRegion   gov=gov-tp-silver regions=province-silver hex=-3_0 owner=GOV gov-tp-copper 的辖区 province-copper
DEBUG … command=map.ReassignHexes  gov=gov-tp-silver regions=province-silver hex=-3_0 owner=GOV gov-tp-copper 的辖区 province-copper
```

### 6.3 ★ 既有世界回归对照（旧码 vs 新码；同一份探针字节）

`/tmp/b1-probe/LegacyProbe3.java` 把两个既有世界的**经济切片原文快照**落盘，外部脚本按"**对象键序无关 / 数组序有关**"做结构对照。

```bash
CP="$(ls -d $PWD/simos-*/target/classes | tr '\n' ':')$(cat /tmp/a2b-probe/cp-third.txt)"
# ① 新码 ×3（稳定性自证）+ ② 旧码（git show HEAD 的 EconomySeeder 放在 classpath 更前遮蔽）
java -cp "/tmp/b1-probe/out:$CP" io.mosire.simos.app.LegacyProbe3 /tmp/b1-probe/new1   # ×new2 ×new3
java -cp "/tmp/b1-probe/out:/tmp/b1-probe/old-out:$CP" io.mosire.simos.app.LegacyProbe3 /tmp/b1-probe/old
# ③ 结构对照（json.loads + 排序键规范化）
new1 vs new2: SAME / new1 vs new3: SAME / new2 vs new3: SAME
small-world  new1 vs old: SAME
corridor     new1 vs old: SAME
```
⇒ **"老入口逐值不变"是实测的，不是推演的**；`small-world` 仍 `zones=[single-region@-2_0[19格]=silver]`（1 个区，既有基线形态）。

★ **顺带记一条既有性质（不是本批引入）**：`ModuleCodec.encodeSnapshot` 的**原文 sha256 不是内容的纯函数**
（同一份代码连跑三次，长度都 664357、摘要三个都不同）—— 根因是 util 既有的 `Map.copyOf` 迭代序。
用它做对照会得出**假红**；本批因此改用"规范化结构对照"。这条对将来的"F7/G5 两遍逐值相同"判据**很关键**
（要么按结构比、要么按规范化串比，不能直接比原文摘要）。

---

## 7. 遗留与建议（交给控制方/测试代理）

1. **测试代理优先实跑**：`simos-app` 全量（守卫在组合根，风险面在 app）、`simos-economy` 全量（`MarketTopology`/`SetMarketNumeraire` 邻域），
   以及"`three-powers` 起来即有 3 区/3 GOV/3 币"的 G1 用例 + M9 三条入口的负向用例 + I23 逐 hex 断言。
2. **B2 的接缝已备好**：`EconomySeeder.plan(..., numeraireOf)` 就是"区→法定币"从"调用方函数"换成"状态字段"的替换点；
   `MarketZoneReadout` 就是 G2 往返不变式的对照读数口。
3. **日志量建议**（不属本批）：三区异币世界中 `MARKET_CURRENCY_MISMATCH_REJECTED` 每 tick ~1,400 条 INFO ——
   建议降 TRACE 或按轮聚合（§一.9 的"逐笔明细 = TRACE"口径）。
4. **`run-small-world.sh` 同款脚本**：本批**未加** `run-three-powers.sh`（脚本不在文件所有权内）。
   起法 = `--world=three-powers`（已实测 `ShellMain.parse` 解析 + 登记 + 真创世成功）。
