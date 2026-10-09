# C 批实现账本：市场区删 `issuingGov` + 一个区里放开多政府

> 责任人：办事子 Agent（写代码）。批次：**C**（约束设计书 `docs/superpowers/specs/2026-10-09-market-zone-and-gov-money-design.md` §7 第一行）。
> 任务：`task-21`（★ 任务板报"不是 active Team 成员" ⇒ **未能 claim**，按任务书正文执行）。
> 探针：`/tmp/c-probe/`（**不进仓库、不进 `src/test`**；真 `Shell.start` + 真创世 + 真 GM 命令，**零 `core.register`**）。
> 对照树：`git worktree` `/tmp/c-before` @ `81aa5757`（当年 HEAD，改前字节）。

---

## 0. 一句话

`MarketZone` 从 7 组件瘦到 6（`zoneId, anchor, radiusHex, hexes, legalTender, officialRates`）；"谁管这种钱"改由
`Government.issuable` **反查**（`MarketZoneBook.possibleIssuersOf`，可多值、规范序）；立区删掉 `gov-not-registered` /
`currency-not-issuable` 两条（命令面 + 状态构造期各一处）；**旧档（含重放路径）可读**（类型级
`@JsonIgnoreProperties("issuingGov")`）；`three-powers` 里两个政府各自挂同一个币对的价 ⇒ **两个窗口、两边都成交**
（铜省政府 1,241 毫铜 @1100‰、银省中央 1,000 毫铜 @1025‰，day 3）。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 事实（改前） | 位置 | 影响 |
|---|---|---|---|
| 1 | `MarketZone` 7 组件 record，`issuingGov` 构造期 `requireNonNull` | `simos-economy-api/.../api/market/MarketZone.java:65-72` | 本次删除的对象 |
| 2 | 区级报价的**承挂者** = `zone.issuingGov()`：`effectiveRateOf/effectiveRatesOf/zonesCovering` 全按它过滤 | `MarketZoneBook.java:208/276/242-270` | 删字段后必须换口径 —— 换成人话＝"谁发行本区法定币"（§4.1 的反查） |
| 3 | 立区两条校验（`gov-not-registered`/`currency-not-issuable`）**各两处**：命令面 + 状态构造期 | `EconomyDefineMarketZoneHandler.java:148-170`；`EconomyData.java:1873-1891` | 用户「肯定不管」⇒ 两处一起删（只删命令面会被状态守卫拦成 `contract-violation`） |
| 4 | 区级报价还有一个"点名政府"的口子：`govUnitId` 给了就必须等于本区发行 GOV | `EconomySetOfficialRateHandler.java:215-238` | 与 #2 同族 ⇒ 一并退役（区级报价不点名政府） |
| 5 | `DefineMarketZone` 载荷里 `govUnitId` 是**必填** | `EconomyDefineMarketZoneHandler.java:318`；`ThreePowersMarketZones.java:430` | 区不记政府了 ⇒ 载荷整个删掉该字段（fail-closed：仍带 ⇒ `bad-payload`） |
| 6 | 重放路径走 Core 的**第四台 mapper**、不过 codec 整形层 | `Fix/Prior`: `.superpowers/sdd/2026-10-09-debt-ref-table/impl-ledger.md` §6.4（`Timeline.readChangeSet`） | 旧档兼容必须落在**类型**上（`HouseholdEconomy` 退役 `debts` 的同一手法） |
| 7 | `MoneyIssuance.register/syncAuthorities` + `EconomyData:1674-1693` 共守"**一币一发行人**"；发行腿按 `requireIssuerOf(币)` 判"付方是不是发行源"（透支=发行） | `simos-economy-api/.../api/money/MoneyIssuance.java:65-152`；`EconomySettlement.java:8086/8166` | ★ **本批不做**：放宽它 = 把"透支/造币"权力交给第二个政府（货币层，需另裁）——见 §5 偏离 1 |
| 8 | `ThreePowersWorld` 三个 GOV 的国库都**持有银**（`treasuryMoney` 每国 100,000 毫银），但只有中央在 `issuable` 里声明银 | `ThreePowersWorld.java:395-436`；survey 输出 | "有货币 ⇒ 能挂价" 在 GOV 级本来就成立；区级承挂者要的是 `issuable`（I-M8 唯一权威） |
| 9 | `FxSettlement` 的家户限价取 `pairs.putIfAbsent` 的**第一份**窗口价 | `FxSettlement.java:99-103`（= 设计书 F9，属 **A 批**） | 同币对两窗口时，"谁吃到量"取决于这份参考价 ⇒ 探针据此设计（见 §4） |
| 10 | 台账 F7 说"`HouseholdEconomy.money()` 是自由币种字典" | 实际 `HouseholdEconomy.money` 是 **单个 `long`**（计价币口径）；逐币种余额在 actor 侧 `AvailableStock.available(ActorData, HouseholdId, CurrencyId)` | ★ 又一处"台账 ≠ 代码"；也否决了"按**持币**判定承挂者"这条路（assembl y 拿不到逐币种余额，且会随时变） |

---

## 2. 实现架构（我定的）

### 2.1 唯一规则：区级报价的承挂者 = 本区法定币的**发行者**（反查，I-M8）

```
本 GOV 能发行某区区法定币（Government.issuable ∋ zone.legalTender）
  且该区对该币对有覆盖        ⇒ 该区级报价（规范序第一个覆盖者）
否则                          ⇒ 本 GOV 的 GOV 级报价（回落；缺 ⇒ 空）
```

- 落地：`MarketZoneBook.effectiveRateOf/effectiveRatesOf/zonesCovering/zoneCovering` 里把
  `zone.issuingGov().equals(gov.id())` 换成 `government.issuable().contains(zone.legalTender())`；
  新增 `zonesCovering(EconomyData, GovernmentId, base, quote)`（装订点日志用）。
- 逐 hex 读数 `officialRateFor` 的回落目标也从"区发行 GOV"改成"**反查列表里规范序第一个给了该币对 GOV 级报价的政府**"。
- ★ 为什么是它：设计书 §4.1 明文要求用 `possibleIssuersOf` 反查回答"谁在管这种钱"；且**逐值保住** B4 的既有验收
  （three-powers 每区一个发行者 ⇒ 区级报价的窗口数与属主一字不变）。
- ★ 新增查询 `MarketZoneBook.possibleIssuersOf(EconomyData, CurrencyId) -> List<GovernmentId>`（规范序、可多值）；
  `govUnitIdsIssuing` 改成它的投影（同一件事只有一处拼写）。**不新增任何"发行关系表"**。

### 2.2 退役键的 fail-closed 处理（两处，具名拒而不是静默忽略）

| 命令 | 载荷 | 行为 |
|---|---|---|
| `economy.DefineMarketZone` | 仍带 `govUnitId` | `bad-payload`（"govUnitId 已退役"） |
| `economy.SetOfficialRate` | 同时给 `marketZoneId` + `govUnitId` | `bad-payload`（"区级分支不接受 govUnitId"）；老路径（无 `marketZoneId`）逐字不变 |

### 2.3 旧档兼容

`MarketZone` 上加类型级 `@JsonIgnoreProperties("issuingGov")`（`simos-economy-api` 本身已用 Jackson 注解，
`jackson-databind` 是它的显式依赖 ⇒ 无需改 pom）。★ 落在类型上 ⇒ **codec 与 Core 第四台 mapper（重放）同一份语义**。

### 2.4 "同区两政府"的落法（任务书 §4，我选的路线）

**不做**：新增 GOV / 改三区边界 / 让某省"也发行银"（后者要放宽"一币一发行人"，见 §5）。
**做**：在 `three-powers` 真世界上用真 GM 命令让**两个政府各自挂自己的 `copper/silver` 价**：

```
铜省政府（自己的国库：100,000 毫铜 + 100,000 毫银）→ copper/silver = 1050/1100
银省中央（GM 给它 1,000 毫铜，用户口径「铸币…实际就是问 GM 要」）→ copper/silver = 900/950
```

⇒ 生产装配口径 `FxRoundInput` **两个窗口、属主两个政府**；家户限价取规范序第一份（铜省，ask 1100），
中央那份更便宜（950）先吃满它的 1,000 毫铜容量，余量落到铜省窗口 ⇒ **两边都成交**（§4 探针输出）。
- ★ 为什么不是"一个区跨两个辖区"：那要破坏 B3 的 `zone-hexes-vs-region` 自检与 I23 的"3 辖区铺满全图"，
  而任务书明令"不得改坏 B1/B3 的既有形态"。
- ★ 为什么不是"两个政府发同一种钱"（用户 §1.4「那个发币的政府」的字面形态）：见 §5 偏离 1。

---

## 3. 49 处 `issuingGov` 引用逐条处置（`grep -rn issuingGov --include=*.java simos-*/src/main`）

| 文件 | 处数 | 处置 |
|---|---|---|
| `simos-economy-api/.../api/market/MarketZone.java` | 9（`:22,28,62,71,78,148,160,170,185`） | **删组件** + 构期守卫 + 3 个 `with*` 传参 + `describe()` 那一栏；类注改写；加类型级 `@JsonIgnoreProperties` |
| `simos-app/.../time/MarketZoneReadout.java` | 9（`:58,59,70,71,83,120,121,183,184`） | **读数两栏退役**（`Zone` 的 `issuingGov`/`issuingGovUnit` 组件删除、两处构造点、javadoc）——设计书 §6.3 点名的预期变化 |
| `simos-app/.../world/ThreePowersMarketZones.java` | 6（`:309,312,469,470,471,472`） | 自检 ④ 改读**政府表**（`government.issuable() ∋ spec.legalTender`，仍是 fail-closed，校验的是**世界形态**不是区准入）；两条创世日志字段退役；载荷不再发 `govUnitId` |
| `simos-economy/.../EconomyData.java` | 6（`:1873,1874,1879,1881,1886,1890`） | **删状态构造期第 ⑤ 条跨表守卫**（含注释块与类注的"五条守卫"改写为"四条"） |
| `simos-economy/.../model/MarketZoneBook.java` | 5（`:101,103,176,208,276`） | 删 `issuingGovUnitOf`；`officialRateFor` 回落改反查；`zonesCovering/zoneCovering` 换谓词（签名收 `Government`，新增 `EconomyData+GovernmentId` 重载）；新增 `possibleIssuersOf` |
| `simos-economy/.../spi/EconomySetOfficialRateHandler.java` | 5（`:215,223,224,262,263`） | 删 `zone-issuer-not-a-gov-unit`/`gov-mismatch` 两条与两个日志字段；区级分支新增"不接受 `govUnitId`"具名拒；DEBUG 里加 `carriers=`（本区货币的发行者个数，语义正名） |
| `simos-economy/.../spi/EconomyDefineMarketZoneHandler.java` | 4（`:241,242,243,244`） | 删 `gov-not-registered`/`currency-not-issuable` 两条校验、载荷 `govUnitId`、日志两栏；新增退役键 `bad-payload` |
| `simos-app/.../gov/GovCurrencyLinks.java` | 4（`:28,77,79,110`） | 删 `issuingGovUnitOfZone`；`describe()` 去掉发行者列；新增 `possibleIssuersOf` 门面 |
| `simos-economy/.../spi/EconomyMergeMarketZonesHandler.java` | 1（`:175`） | 合并后的区不再带 `target.issuingGov()`；类注同步 |
| **合计** | **49** | 全部处置，**零残留**（`grep issuingGov src/main` 现在只剩注释里对"退役"的说明） |

同步改的**非 `issuingGov` 引用**（否则编译不过/口径漂）：`FxRoundInput`（类注 + `Window` 的 `@param`）、
`EconomySettlement.logFxWindows`（`zonesCovering` 新签名 + 冲突注释）、`CatalogTool` 的两条命令提示、
`EconomyDefineMarketZoneTool` 描述。

---

## 4. 探针与真实输出（自证；装置 `/tmp/c-probe/`，源码 `CProbe.java` 一份**双树通用**）

```bash
# 新码 classpath（本树 target/classes 在前，遮住 jar 里的旧字节）
NEW_CP=$(cat /tmp/c-probe/cp-new.txt)      # 16 个模块 target/classes + simos-app shaded jar
OLD_CP=$(cat /tmp/c-probe/cp-old.txt)      # /tmp/c-before（git worktree @81aa5757）编出的同一套
javac -cp "$NEW_CP" -d /tmp/c-probe/out-new /tmp/c-probe/CProbe.java
javac -cp "$OLD_CP" -d /tmp/c-probe/out-old /tmp/c-probe/CProbe.java   # 同一份源码两侧各编一次
java -cp "/tmp/c-probe/out-new:$NEW_CP" io.mosire.simos.app.CProbe <mode>
```

### ① A1：立区不看"谁发得出"（**改动前必拒**用旧树现跑出来）

```
# 旧树（81aa5757）：small-world，标本 = gov-province 持 10,000 毫银、issuable=[]
✓ A1(旧码：载荷点名「持银但不发行银」的 GOV) 立银区 ⇒ 具名拒 currency-not-issuable
    （Rejected: … 该 GOV 不发行这种钱（谁发行的不许在两处漂开）: gov=gov-unit-gov-province 法定币=silver issuable=[]）
✓ ⇒ head 不动（2）  ✓ ⇒ 零 revision（2）
# 新树：载荷不再点名政府
✓ A1 立银区（世界词表有银；无需任何政府声明发行它） ⇒ Committed@3
✓ A1 MarketZone 组件数（7→6）（实际 6 / 期望 6）
✓ A1 银的反查照实 = 声明者列表（[world-silver]）
✓ A1 立区所依据的那个 GOV（gov-province）不在银的发行者里（旧码正是因它被拒）
```

### ② A2 / N6：法定币**无任何政府能发行**也能立区；后续无窗口是**具名**（不是拒绝立区）

```
✓ A2 定义币种 probe-ghost（发行人 = gov-province） ⇒ Committed@4
✓ A2 撤销发行声明（gov-province 的 issuable 改回空） ⇒ Committed@5
✓ A2 probe-ghost 仍在世界词表里
✓ A2/N6 probe-ghost 无任何发行者（实际 [] / 期望 []）
✓ A2 该币无窗口（生产装配口径窗口数；具名见 FX_WINDOWS_ASSEMBLED_DETAIL reason）（实际 0）
✓ A2 SetMarketNumeraire(anchor2, probe-ghost) ⇒ Committed@6
✓ N6 立 probe-ghost 区（法定币无任何政府能发行）⇒ 不再拒绝 ⇒ Committed@7
✓ N6 head 前进 6 → 7        ✓ N6 区法定币 = probe-ghost
DEBUG io.mosire.simos.economy.fx - event=FX_WINDOWS_ASSEMBLED_DETAIL day=3 windows=0 zones=2
      zonesWithOfficialRates=0 governmentsWithRates=0
      reason=没有任何生效报价 ⇒ 本轮没有外汇窗口（fail-closed：没有政策价可锚）
== 探针[a12]：合计 22 项，失败 0 项 ==
```

### ③ A3/A4：同区两 GOV 都能挂价、都能成交（`three-powers` 真世界 + 真 GM 命令）

```
✓ GM 给银省中央国库 1,000 毫铜（actor.AdjustAccounts，真命令） ⇒ Committed@2
✓ 铜省政府挂 copper/silver = 1050/1100（自己的价） ⇒ Committed@3
✓ 银省中央挂 copper/silver = 900/950（自己的价）   ⇒ Committed@4
A3 生产装配口径窗口数=2
    window owner=gov-unit-gov-tp-copper treasury=HOUSEHOLD:hh-gov-gov-tp-copper pair=copper/silver buy=1050 sell=1100
    window owner=gov-unit-gov-tp-silver treasury=HOUSEHOLD:hh-gov-gov-tp-silver pair=copper/silver buy=900  sell=950
✓ A3 同一币对两窗口、属主 = 铜省政府 + 银省中央
INFO  FX_WINDOWS_ASSEMBLED day=3 windows=2 zones=3 zonesWithOfficialRates=0 governments=4
INFO  FX_ROUND day=3 windows=2 fills=2 baseVolumeMilli=2241 windowFills=2 rejections=3
TRACE FX_FILL day=3 base=copper quote=silver baseMilli=1000 quoteMilli=1025 pricePerMille=1025 venue=gov_window
      buyer=HOUSEHOLD:hh-3_0-urban-landlord   seller=HOUSEHOLD:hh-gov-gov-tp-silver   ← 银省中央窗口
TRACE FX_FILL day=3 base=copper quote=silver baseMilli=1241 quoteMilli=1366 pricePerMille=1100 venue=gov_window
      buyer=HOUSEHOLD:hh-3_0-urban-middle_peasant seller=HOUSEHOLD:hh-gov-gov-tp-copper ← 铜省政府窗口
[结算前] hh-gov-gov-tp-copper copper=100000 silver=100000   [结算后] copper=98759 silver=101206
[结算前] hh-gov-gov-tp-silver copper=1000   silver=200000   [结算后] copper=0     silver=200865
✓ A4 推进后两个窗口仍在（同区两政府各自挂价）（实际 2）
== 探针[a34]：合计 5 项，失败 0 项 ==
```
★ 口径如实记：两个窗口是**同一个币对**，家户限价取规范序第一份（`FxSettlement:99-103` = 设计书 F9，属 **A 批**）；
本探针让"更便宜那份"的容量（1,000 毫铜）小于当日需求（2,241）⇒ 余量自然落到另一窗口，**两个属主都留下成交读数**。

### ④ A7：反查（可多值、规范序）

```
区 c-tp-copper 法定币=copper ⇒ possibleIssuersOf=[gov-unit-gov-tp-copper] govUnits=[gov-tp-copper]
区 c-tp-gold   法定币=gold   ⇒ possibleIssuersOf=[gov-unit-gov-tp-gold]   govUnits=[gov-tp-gold]
区 c-tp-silver 法定币=silver ⇒ possibleIssuersOf=[gov-unit-gov-tp-silver] govUnits=[gov-tp-silver]
✓ A7 possibleIssuersOf(<每种币>) 长度 = 声明者个数（1/1/1）
✓ A7 无发行者的币 ⇒ 空表（不是猜一个）
== 探针[a7]：合计 10 项，失败 0 项 ==
```
★ **"可多值"当前不可达**（三个币各只有一个声明者）：现行"一币一发行人"守卫仍在（§1 #7）⇒ 如实报，
不拿"类型可以是 List"冒充多值证据（见 §5 偏离 1）。

### ⑤ A8：往返不变式 + 旧档（codec **与重放路径**）

```
✓ A8 往返不变式 apply(diff(base,target),base).equals(target)
✓ A8 codec 往返（encode→decode 逐值相等）
✓ A8 注入退役键成功（旧档形状）
✓ A8 旧档（含 issuingGov）codec 解码：区数（3）、法定币（copper）、成员格数（16）、锚格（0_-3）
✓ A8 未知字段仍 fail-closed（@JsonIgnoreProperties ≠ ignoreUnknown）
                       （拒：java.lang.IllegalStateException: economy 侧 JSON 解码失败: EconomySnapshot）
== 探针[roundtrip]：合计 9 项，失败 0 项 ==

# 旧档 = 旧树（81aa5757 字节）真跑 30 tick 写下的 store（change set 里带 issuingGov）
### NEW 打开旧码写的档（重放路径）###
  重放成功：head=2 revisions=2
  zones=[c-tp-copper@0_-3[16格,r=8]=copper, c-tp-gold@-3_3[12格,r=8]=gold, c-tp-silver@3_0[9格,r=8]=silver]
✓ A8 重放旧档：区数（3）  ✓ 三个区成员格非空（16/12/9）  ✓ 3 区并集 = 全图格数（37）
✓ A8 重放旧档：区记录组件数（新码 6）
✓ A8 旧档读回后仍可写（真命令：定一条区级汇率） ⇒ Committed@3
== 探针[replay]：合计 7 项，失败 0 项 ==
```

### ⑥ N1–N6 逐条负向（各自具名拒 + head 不动 + 零 revision）

```
✓ N1 成员格已被别区占用 ⇒ hex-in-other-zone（head 1→1、revision 1→1）
✓ N3 法定币不在世界词表 ⇒ currency-not-defined（词表=[silver, copper, gold]）
✓ N4a 锚格不在成员格里 ⇒ anchor-not-in-hexes       ✓ N4b 锚格没有市场行 ⇒ anchor-missing-market
✓ N5 跨币合并 ⇒ numeraire-mismatch
✓ 退役键 govUnitId（DefineMarketZone）⇒ bad-payload     ✓ 区级报价带 govUnitId（SetOfficialRate）⇒ bad-payload
✓ N2 成员格计价币 ≠ 法定币 ⇒ numeraire-mismatch（在 a12 的小世界段：head 7→7、revision 7→7）
✓ N6 法定币无任何政府能发行 ⇒ **不再拒绝**（a12 段，Committed@7）
== 探针[neg]：合计 21 项，失败 0 项 ==
```

### ⑦ 旧世界不动（同一份探针源、两侧各跑一次；digest 跳过退役键 ⇒ 逐值对照）

| 世界 | tick | 旧树 md5（81aa5757） | 新树 md5 | 结论 |
|---|---|---|---|---|
| `three-powers` | 30 | `f25249cf3eeff6d0f443481bd9991c0e` | **同** | 逐值不变（区组件 7→6 是预期差异，摘要里已跳过该键） |
| `small-world` | 30 | `2cf3421a68b258a3253b98fd08d21c10` | **同** | 逐值不变 |
| `corridor` | 0 | `d57a36c522b39adefdfb505f0a36e088` | **同** | 逐值不变 |

（读数为 `head`/`revisions`/区数/格数 + 逐组件规范摘要：16/3/3 三区、37 格、3 单位，两侧一致。）

---

## 5. 偏离设计书之处（主动记录）

1. ★★ **"一个区里多个政府"取了"两个政府各自挂价"的形态，没有取"两个政府发同一种钱"的字面形态**。
   设计书 §7 的示例（"让某省 GOV 也用银"）按字面走要放宽 **"一币一发行人"**（`MoneyIssuance.register/syncAuthorities`
   + `EconomyData:1674-1693`），而那条守卫的另一半是**发行腿**："付方不是发行源 ⇒ 拒透支"（`EconomySettlement:8086/8166`）
   —— 放宽它 = 把一个币种的**透支/造币权**交给第二个政府（货币层语义，且 `RegisterGovernment` 虽是 GM-only，
   但它改的是"谁能造钱"这条硬边界）。用户 §1.4 的字面（「那个发币的政府」「违法发币的政府」）**确实**指向多发行人，
   但那属于"政府货币发放/货币管控"的核心，需要单独设计 + 用户裁定 ⇒ 本批**不做**，如实上报（§7 BLOCKED-1）。
   ⇒ 本批给出的"同区多政府"可观察形态 = **同一个市场区里两个政府各自挂自己的价、各自用自己的国库成交**（§4③）。
2. **`DefineMarketZone` 载荷删掉 `govUnitId` 且旧载荷（带它）判 `bad-payload`**：设计书只说"删两条校验"，
   没写载荷字段怎么退。我按"区只记这一片用哪种钱"（G1）把它整个删掉，并对退役键 fail-closed（不许"算了却被忽略"）。
   ⇒ 旧命令载荷（含 3 个世界创世与历史验收脚本里的那种）会被具名拒；`ThreePowersMarketZones` 已同批更新。
3. **`SetOfficialRate` 的区级分支不再接受 `govUnitId`**（设计书 §4.2 只说"去掉'GOV 必须发行 base/quote'那类校验"）：
   区级报价的语义是"这个区挂的价"，点名政府已无归属可落 ⇒ 给了就拒。GOV 级路径逐字不变（任何政府都能给自己持有的
   任意货币挂价 —— 这条本来就没有校验）。
4. **`ThreePowersMarketZones` 自检 ④ 保留但改读政府表**：设计书 §4.3 说"不新增'至少有一个政府能发行该法定币'之类的
   **准入校验**"；我保留的是**创世自检**（校验"这个世界声明的形态"是区=辖区=该 GOV 的币），它不是立区准入规则，
   删掉它会静默丢掉 B3 的一条 fail-closed 世界自检 ⇒ 保留并改数据源（`zone.issuingGov()` → `government.issuable()`）。
5. **旧档兼容的验证方式**：旧档由**旧树字节**（worktree @81aa5757）真跑真写，不是手工拼 JSON（更硬）；
   codec 侧另做了"注入退役键 + 注入未知键"的对照（后者必须仍炸）。
6. 未做**整轮 `three-powers` 旧码 vs 新码的 FX 数值 diff**（本批三区创世无任何汇率 ⇒ FX 面沉默；带汇率的对照
   只在探针 ③ 做过一次 day3/day5 读数），如实记在 §7。

## 6. 会改变数值行为 / 会改变读数与日志的清单（给测试 Agent 与控制方）

| # | 形态 | 变不变 | 依据 |
|---|---|---|---|
| 1 | `small-world` / `corridor` / 任何**区表为空**的世界 | **不变**（digest 逐字节相同） | §4⑦ |
| 2 | `three-powers` 30 tick（三区、无任何汇率） | **不变**（digest 逐字节相同） | §4⑦ |
| 3 | **立区准入**：GM 用一个"不发行该币"的 GOV 立区 | **变**（旧：`currency-not-issuable` 具名拒；新：Commit） | §4①（用户「肯定不管」） |
| 4 | **立区载荷**：`govUnitId` | **变**（旧：必填；新：带上就 `bad-payload`） | §2.2 |
| 5 | **区级报价载荷**：`marketZoneId` + `govUnitId` | **变**（旧：须等于本区发行 GOV，否则 `gov-mismatch`；新：`bad-payload`） | §2.2 |
| 6 | **区级报价的承挂者** | 单发行者世界**逐值不变**（three-powers 三区各一发行者 ⇒ 窗口数/属主/价格不变）；多发行者世界才分化 | §2.1（B4 验收口径被保住） |
| 7 | `MarketZone` 状态形状 | **7→6 组件**；旧档多出来的 `issuingGov` 被类型级注解具名忽略 | §2.3 |
| 8 | 读数：`MarketZoneReadout.Zone` 的 `issuingGov`/`issuingGovUnit` 两栏、`GovCurrencyLinks.describe` 的发行者列、`MarketZone.describe()` 的 `/issuer` | **删**（设计书 §6.3 点名的预期变化） | §3 |
| 9 | 日志：`MARKET_ZONE_DEFINED` 少 `issuingGov/issuingGovUnit` 两字段；`ZONE_OFFICIAL_RATE_SET` 少 `issuingGov`、多 `carriers`；`THREE_POWERS_GENESIS_MARKET_ZONE_PERSISTED` 少两字段 | **变**（字段面，不改状态） | §3 |
| 10 | 外汇窗口的**装配口径** | 单发行者世界不变；`possibleIssuersOf` 可多值的世界 ⇒ 一个区级报价投到**每个**发行者的窗口上（各自用自己的国库） | §2.1 |
| 11 | 硬编码字面量 | **无新增/无改动**（`GovFxWindow.UNBOUNDED_RESERVE_CAP_BASE_MILLI` 等一字未动） | — |

## 7. 会让既有测试失效的清单（我不改测试；给测试 Agent 的输入）

| # | 测试 | 性质 | 说明 |
|---|---|---|---|
| 1 | `simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java:856` | **编译红** | `new MarketZone(ZONE, hex, 0, Set.of(hex), SILVER, ZONE_GOV, Map.of())` 是 7 参 ⇒ 删第 6 参（`ZONE_GOV`），否则 `simos-economy` 测试编译不过 |
| 2 | `simos-app/src/test/java/io/mosire/simos/app/tools/write/AdjudicateTickToolTest.java:887` | **运行红（很可能）** | `economy.DefineMarketZone` 的样例载荷带 `"govUnitId":"gov-1"` ⇒ 现在 `bad-payload`；同文件的 `SetOfficialRate` 样例只有 `marketZoneId`（无 `govUnitId`）⇒ 不受影响 |
| 3 | `simos-app/src/test/.../tools/SimosToolsTest.java`（`:334,345,500,523` 引 `economy.DefineMarketZone`） | **待核** | 引用该命令的目录/白名单/载荷提示断言：命令类型字符串没变，但 `CatalogTool` 的**提示文案改了**（去掉 govUnitId 与两条拒因）⇒ 若断言提示子串会红 |
| 4 | `simos-app/src/test/.../McpCoverageTest.java`（`:151,301,371`） | **待核** | 同上（目录覆盖断言；命令仍注册、`targetPaths` 恒空不变） |
| 5 | 任何断言"区发行 GOV 必须登记 / issuable 必须含法定币"的**状态守卫**测试 | **不存在**（grep `谁发行的不许在两处漂开`/`gov-not-registered` 于 `src/test` = 0 命中）⇒ 该守卫此前没有测试护栏 |
| 6 | 任何引用 `MarketZoneReadout.Zone.issuingGov()` / `GovCurrencyLinks.issuingGovUnitOfZone` / `MarketZoneBook.issuingGovUnitOf` / `zonesCovering(Map, GovernmentId, …)` 的测试 | **不存在**（`src/test` 零命中）⇒ 删这些 API 不产生编译红 |

## 8. 未完成 / 未验证 / BLOCKED

1. **BLOCKED-1（设计层，需用户/控制方裁定）**：用户 §1.4 的字面模型「同一币种多个政府发行、中央只能上行政强制力」
   需要放宽 **"一币一发行人"**（`MoneyIssuance` 的单一发行源 + `EconomyData` 的姿态守卫 + 发行腿的"付方必须是发行源"）。
   本批**不做**（会把透支/造币权交给第二个政府）。★ 最小额外范围：`MoneyIssuance` 改多发行源（`issuersOf(currency)`）
   + 发行腿改成"付方 ∈ 发行源集合" + `EconomyData` 守卫改写 + `ThreePowersGovBootstrap` 的"一币一发行人"创世自检放宽
   + 用户对"第二个政府能不能透支"的裁定。**fail-closed 降级（已实现）**：不做多发行人，改以"两个政府各自挂价、
   各自国库成交"兑现"同区多政府"的可观察形态（§4③）。
2. **BLOCKED-2（本批范围外，属 B 批）**：`possibleIssuersOf` 的"多值"目前不可达（每币一个声明者）；
   真正多值要等 BLOCKED-1 的裁定或 B 批的"挂价校验放开"。**查询本身已按可多值实现**（照实列全部声明者、规范序）。
3. **未验证**：`test` / `verify` / `package`（任务书禁止；只跑 `compile`）。
   ⇒ **未跑 SpotBugs**、**未跑既有回归网**。格式化用仓库自己的 Spotless（`spotless:apply` + `spotless:check` 均 **BUILD SUCCESS**，
   0 需改文件）；Checkstyle 随 `compile` 的 `validate` 阶段跑过（`-q` 无输出 = 绿）。
   ★ 如实记：`CatalogTool.java` 的提示表被 google-java-format 重排了 244/249 行（该文件在 HEAD 上**本就不满足**
   仓库格式化，我的一行改动触发了整段 `Map.ofEntries` 的重排；只动空白与拼接分行，不动字面量内容）。
4. **未验证**：整轮 `three-powers` 带汇率的旧码 vs 新码数值 diff（只做了 §4③ 的 day3/day5 读数与 §4⑦ 的无汇率 30 tick 逐值对照）。
5. **未验证**：GUI/HTTP 面（`simos.gm.*` 工具目录、`/api/*` 读数）对"读数两栏退役"的反应 —— 只做了编译与静态核对。
6. **未验证**：`v17levant`（59223 格、0 市场、0 政府）与"有区表但无法定币发行者"的自定义世界；
   探针覆盖 `three-powers` / `small-world` / `corridor`。
7. **未能 claim 任务板**：`team_task_get task-21` 报 `agent … is not a member of an active Agent Team` ⇒ 未 `claim`/`complete`。

## 9. 复现命令（控制方用）

```bash
cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am   # 编译（exit 0）
tools/mvn-lock.sh -o spotless:check -pl simos-economy-api,simos-economy,simos-app              # 格式（BUILD SUCCESS）
NEW=$(cat /tmp/c-probe/cp-new.txt); OLD=$(cat /tmp/c-probe/cp-old.txt)
java -cp "/tmp/c-probe/out-new:$NEW" io.mosire.simos.app.CProbe a12 new      # A1/A2/N2/N6（22/22）
java -cp "/tmp/c-probe/out-old:$OLD" io.mosire.simos.app.CProbe a12 old      # 改动前必拒 currency-not-issuable
java -Dsimos.economy.logLevel=TRACE -cp "/tmp/c-probe/out-new:$NEW" io.mosire.simos.app.CProbe a34 settle  # A3/A4
java -cp "/tmp/c-probe/out-new:$NEW" io.mosire.simos.app.CProbe a7           # A7（10/10）
java -cp "/tmp/c-probe/out-new:$NEW" io.mosire.simos.app.CProbe neg          # N1–N5 + 退役键（21/21）
java -cp "/tmp/c-probe/out-new:$NEW" io.mosire.simos.app.CProbe roundtrip    # A8（9/9）
java -cp "/tmp/c-probe/out-new:$NEW" io.mosire.simos.app.CProbe replay /tmp/c-probe/store-three-powers-old  # 重放旧档（7/7）
# 旧世界不动：同一个 digest 模式在两侧各跑（比对 md5）
for W in three-powers small-world corridor; do java -cp "/tmp/c-probe/out-new:$NEW" io.mosire.simos.app.CProbe digest $W 30 /tmp/x; done
```
