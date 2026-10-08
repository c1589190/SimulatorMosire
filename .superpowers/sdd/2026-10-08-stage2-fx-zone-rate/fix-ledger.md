# B4 接线修复账本：区级官方汇率 ⇒ 外汇窗口

> 责任人：办事子 Agent（写代码）。批次：阶段 2-B 第四批（**接线修复**，2026-10-08）。
> 任务：task-16（★ 任务板报"不是 active team 成员" ⇒ **未能 claim**，按任务书正文执行）。
> 证据：探针 `/tmp/fz-probe/`（**不进仓库、不进 `src/test`**；全部走真 `Shell.start` + 真创世 + 真 GM 命令 + 真推进，**零 `core.register`**）。

---

## 0. 一句话

根因 = 窗口组装只把 `governments/moneyIssuances` 交给 `FxRoundInput.of`（2 参，内部 `government.officialRates().isEmpty() ⇒ continue`）
⇒ B2 的**区级覆盖**（`MarketZone.officialRates`）从不进这条组装。本批新增 3 参入口（**区表为空逐字退回 2 参**），生产路径改走它：
three-powers 的"铜区 900/950、金区 1100/1150"从 **0 轮外汇** ⇒ **360 tick / 80 轮 `FX_ROUND`**。

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 事实 | 位置（改动前） | 影响 |
|---|---|---|---|
| 1 | 窗口只读 GOV 级：`for (GovernmentId …) { if (government.officialRates().isEmpty()) continue; … }` | `FxRoundInput.java:112-128` | 区级覆盖无路进窗口 ⇒ 缺陷本体 |
| 2 | 生产**唯一**调用点 | `EconomySettlement.java:1715` | 改一处即可接上（无第二条组装路径） |
| 3 | 区级写入面已就绪（真命令 + 具名拒 + `ZONE_OFFICIAL_RATE_SET`） | `EconomySetOfficialRateHandler.java:203-297` | "两条都真提交成功"是状态事实，不是缺陷 |
| 4 | 读面口径已就绪（区级优先、回落该区发行 GOV）但只服务**逐 hex 读数** | `MarketZoneBook.java:150-165` | 口径已有，只是没接到窗口装配 |
| 5 | 类注自己写着"窗口仍按 GOV 级装配，区级覆盖只落状态 + 命令面 + 读数" | `MarketZoneBook.java:146-148`、`EconomySetOfficialRateHandler.java:54-55` | 这是**已知的边界**，不是隐藏 bug；本批把它合上（注释同步更新） |
| 6 | 跨表守卫保证 `zone.issuingGov` 已登记且 `issuable` 含法定币 | `EconomyData.java:1831-1848` | 区级报价不会因"发行 GOV 未登记"被静默丢弃（不需要额外的吞并分支） |
| 7 | **零测试**引用 `FxRoundInput` / `FX_WINDOWS` / `FX_ROUND`（`grep -rn … simos-*/src/test` = 0 命中） | —— | 这条接线此前没有护栏 ⇒ 缺陷能静默存在【给测试 Agent 的输入】 |

---

## 2. 实现架构（我定的）

**落点**（全部在允许写的 `simos-economy/src/main/**`；未动 `simos-economy-api`）：

1. **新入口** `FxRoundInput.of(governments, moneyIssuances, marketZones)`（`FxRoundInput.java:136`）
   - `marketZones == null || isEmpty()` ⇒ **`return of(governments, moneyIssuances)`**（`:145`，逐字走 A2a 老路径）；
   - 否则逐 GOV（规范序）取 `MarketZoneBook.effectiveRatesOf(government, marketZones)`，空 ⇒ 该 GOV 没有窗口；否则逐条发窗口（属主/国库/储备上限公式**一字不动**）。
2. **旧 2 参签名逐字保留**（`:93`）：方法体只做**机械抽取**（发行量索引 → `issuanceByGovernmentCurrency` `:180`；GOV 排序 → `sortedGovernmentIds`；报价排序+落窗口 → `addWindows` `:207`）。
3. **"生效报价"的唯一拼写点放读面** `MarketZoneBook`（B2 类注自称"本区官方汇率是多少、没有区级覆盖时回落到谁"就归它）：
   - `zones(Map)`（`:56`，规范序；`zones(EconomyData)` 改为委托它 ⇒ 唯一排序拼写点）；
   - `zonesCovering(...)`（`:197`）全部覆盖该 GOV 该币对的区（规范序）；
   - `zoneCovering(...)`（`:222`）= 第一个；
   - `effectiveRateOf(...)`（`:242`）区级优先、**按币对**回落；
   - `effectiveRatesOf(...)`（`:270`）整张生效表（币对升序；空 = 没有窗口）。
4. **为什么逐 GOV 而不是逐区**（关键判断）：窗口的**身份**是"哪个政府开的"——属主 GOV / 国库 actor / 储备上限三项都挂在 GOV 上（`Window` 的 4 个组件），而区级覆盖说的是"这个区的报价"。
   ⇒ 把区级报价**投到该区发行 GOV 的窗口**上：**一个 GOV × 一个币对恰一个窗口**。
   逐区各发一条会让同一 GOV 同一币对重复投放容量（撮合簿里就是两张同价同量的窗口单 = 静默的数值放大），所以不那样做。
5. **没有扩大形状**：`Window` 仍是 4 组件、`FxSettlement` 一行未动 ⇒ **撮合域仍是世界级**（本批红线：按区收窄是待用户裁定的开放点）。
6. **日志（§一.9）**：`EconomySettlement.logFxWindows`（`:7834`，fx 分类，与 `FX_ROUND` 同一个 logger）
   - **INFO** `FX_WINDOWS_ASSEMBLED`（`:7846`，有窗口时一条）：`windows / zones / zonesWithOfficialRates / governments`（"这一轮开张了"）；
   - **DEBUG** `FX_WINDOWS_ASSEMBLED_DETAIL`（`:7871`，**无论有没有窗口**）：多一个"为什么"——没有窗口时写明"没有任何生效报价 ⇒ fail-closed 不开张"；
   - **DEBUG** `FX_WINDOW_ASSEMBLED`（`:7895`，逐窗口）：属主 / 币对 / 买价 / 卖价 / 储备上限 / `rateSource`（`market-zone:<id>` 还是 `government`）；
   - **DEBUG** `FX_ZONE_RATE_CONFLICT`（`:7921`，仅冲突时）：`chosenZone` + `droppedZoneRates` 具名 —— 一个 GOV 下辖多区、同币对多价时被覆盖的那几条**不许静默消失**。
   - 原有的 `MARKET_FX_WINDOWS`（TRACE/DEBUG）**原样保留**，未替换。

---

## 3. 旧行为不变的证据（三条独立判据）

1. **结构判据**：3 参入口在区表空时**委托**给 2 参入口（`FxRoundInput.java:145`）⇒ `small-world` / `corridor` / `v17levant`（区表空）走的就是老路径本身，不是"看起来一样的新路径"。
2. **diff 判据**：2 参方法体只把三段搬进私有助手（`git diff` 可逐行核：发行量索引的排序键、`issuanceByGovCurrency.merge`、`RATE_ORDER` 全部逐字保留）。
3. **实测判据**（同一份探针源，改动前 / 改动后在**同一 JVM 装配**下各跑一次）：

| 用例（真 Shell + 真创世 + 真命令 + 11 tick） | 改动前 | 改动后 |
|---|---|---|
| `gov-rates`（只设 GOV 级：铜 900/950、金 1100/1150） | 窗口 2：`copper/silver=900/950 cap=50000`、`gold/silver=1100/1150 cap=50000` | **逐值相同**（含次序与 cap），且探针判据"回落路径与旧接线逐值相同" ✓ |
| `small-world`（区表空、无任何汇率） | 窗口 0 / FX 事件 0 | 窗口 0 / FX 事件 0 |
| `three-powers-none`（区表 3 条、**一条汇率都没有**） | 窗口 0 | 窗口 0（"有区表也不自己造价"） |

---

## 4. 回落与优先级的判据

- **判据来源**：`MarketZoneBook.officialRateFor`（B2 已裁的读数口径：**区级优先、回落该区发行 GOV 的 GOV 级报价**）。本批把同一条规则提升为**窗口装配口径**（同一个语义，两处读口），不另立口径。
- **回落按币对判**（不是"这个区有区级表就整表回落"）：某区只覆盖 A/B 时，该区发行 GOV 的 C/D 报价仍然有效 —— 与 `officialRateFor` 的逐 hex/逐币对口径一致。
- **冲突**（一个 GOV 下辖多区、同币对多价）：**规范序第一个区胜出**（`zones(Map)` 的规范序，与写入史无关），其余进 `FX_ZONE_RATE_CONFLICT` DEBUG。
- **实测**：
  - `zone-wins`（GOV 级 900/950 **与** 区级 1100/1150 同名币对）：窗口 = **1100/1150**（区级胜出）✓；同状态下 2 参读数 = 900/950（改动前区级被完全忽略）。
  - `gov-rates`（无区级）：2 窗口（回落成立）✓。
  - 读面探针（合成两区同 GOV，插入序故意倒给 `{b,a}`）：`zonesCovering` = `[a-zone, b-zone]`；`copper/silver ⇒ a-zone 1100/1150`（区级胜 GOV 级、且规范序第一个胜）；`gold/silver ⇒ b-zone 600/650`（**按币对**回落/覆盖）；空区表 ⇒ 逐值 = GOV 级 `{900/950, 700/750}` ✓。

---

## 5. 探针装置与真实输出（自指：证据对最终字节）

**装置**（`/tmp/fz-probe/`，不进仓库、不进 `src/test`）：

```bash
CP="$(cat /tmp/b3-probe/cp.txt)"                                  # 15 个模块 target/classes + 三方差分包
javac -nowarn -cp "$CP" -d /tmp/fz-probe/out /tmp/fz-probe/FzProbe.java /tmp/fz-probe/FzReadFaceProbe.java
java -cp "/tmp/fz-probe/out:$CP" io.mosire.simos.app.FzProbe zone-rates    # 区级汇率（控制方场景）
java -cp "/tmp/fz-probe/out:$CP" io.mosire.simos.app.FzProbe gov-rates     # 只设 GOV 级 ⇒ 回落
java -cp "/tmp/fz-probe/out:$CP" io.mosire.simos.app.FzProbe zone-wins     # 两级都给 ⇒ 区级胜出
java -cp "/tmp/fz-probe/out:$CP" io.mosire.simos.app.FzProbe three-powers-none
java -cp "/tmp/fz-probe/out:$CP" io.mosire.simos.app.FzProbe small-world
java -Dsimos.economy.logLevel=DEBUG -cp "/tmp/fz-probe/out:$CP" io.mosire.simos.app.FzProbe zone-rates
java -cp "/tmp/fz-probe/out:$CP" io.mosire.simos.app.FzReadFaceProbe       # 合成两区同 GOV（冲突/优先级读面）
```

**探针的关键设计**：窗口读数走"**生产装配口径**"——先反射找 3 参入口（B4 起才有），找不到（旧字节）就退回 2 参，并把用了哪条接线打印出来
⇒ **同一份探针源**在改动前报 `wiring=legacy(2-arg) windows=0`（缺陷现场），改动后报 `wiring=zone-aware(3-arg) windows=2`。

**最终字节（源码 md5；探针输出即对这份字节）**

```
1a67195c11c2964d775268dadcef40ed  simos-economy/src/main/java/io/mosire/simos/economy/time/FxRoundInput.java
9328f2b39a79746ef89059ba5d8fae52  simos-economy/src/main/java/io/mosire/simos/economy/model/MarketZoneBook.java
ed9419887d2f0ec2814a12fdc7ad1141  simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java
e8f6fe889d843346f863f5fbae1d939e  simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java
c2fa99bff566361a6c0d6b0df68dfd77  simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomySetOfficialRateHandler.java
```

**① 对照（同一探针，改动前 → 缺陷基线）**

```
✗ ★ 区级汇率 ⇒ 生产口径 FxRoundInput 非空且窗口数 ≥ 1（实际 0 窗口 / wiring=legacy(2-arg)）
✓ ★ 旧接线（2 参 = 只读 GOV 级）在同一状态下 = 0 个窗口（缺陷根因现场）（0 窗口）
  PROBE-FX-EVIDENCE mode=zone-rates wiring=legacy(2-arg) windows=0
FX_ROUND 0 / FX_FILL 0 / FX_REJECTED 0   （11 tick，命令两条都 Committed：head 1→2→3）
```

**② 改动后（`final-zone-rates.log`，11 tick，全部 ✓ 11/11）**

```
   区 c-tp-copper 发行GOV=gov-unit-gov-tp-copper 法定币=copper 区级rates=[copper/silver=900/950] | GOV级rates=(空)
   区 c-tp-gold   发行GOV=gov-unit-gov-tp-gold   法定币=gold   区级rates=[gold/silver=1100/1150] | GOV级rates=(空)
   接线 legacy-2arg：窗口数 = 0
   接线 zone-aware(3-arg)：窗口数 = 2
     · gov=gov-unit-gov-tp-copper pair=copper/silver buy=900 sell=950 capBase=50000
     · gov=gov-unit-gov-tp-gold pair=gold/silver buy=1100 sell=1150 capBase=50000
✓ ★ 区级汇率 ⇒ 生产口径 FxRoundInput 非空且窗口数 ≥ 1（2 窗口 / wiring=zone-aware(3-arg)）
✓ ★ 旧接线（2 参 = 只读 GOV 级）在同一状态下 = 0 个窗口（0 窗口）
✓ 铜区窗口取区级 900/950   ✓ 金区窗口取区级 1100/1150
  PROBE-FX-EVIDENCE mode=zone-rates wiring=zone-aware(3-arg) windows=2
== 合计 11 项，失败 0 项 ==
```

**③ 生产路径（FX 面真的开张；`final-zone-rates-360.log`，**360 tick**）**

```
FX_WINDOWS_ASSEMBLED 80   FX_ROUND 80   FX_REJECTED 199   FX_WINDOW_BUY_BLOCKED 160
INFO io.mosire.simos.economy.fx - event=FX_WINDOWS_ASSEMBLED origin=economy-fx originKind=tick day=3  windows=2 zones=3 zonesWithOfficialRates=2 governments=4
INFO io.mosire.simos.economy.fx - event=FX_ROUND               origin=economy-fx originKind=tick day=3  windows=2 fills=3 baseVolumeMilli=3865 windowFills=3 rejections=4
INFO io.mosire.simos.economy.fx - event=FX_ROUND               origin=economy-fx originKind=tick day=360 windows=2 fills=0 baseVolumeMilli=0 windowFills=0 rejections=4
（`event=FX_ROUND` 计 80 条：fills= 合计 14、windowFills= 合计 12 ⇒ 家户成交 14 笔；
  ★ `FX_FILL` 本身是 TRACE 级（`simos.economy.traceLevel`，默认关）⇒ grep 默认日志为 0，成交数看 `FX_ROUND` 的 `fills=`）
```

**④ 旧世界/回落/区级优先（同批探针）**

```
gov-rates：legacy=2  zone-aware=2  逐值相同 ✓ ；   small-world：0 / 0 ✓ ；   three-powers-none：0 / 0 ✓
zone-wins：legacy=1（GOV 级 900/950）  zone-aware=1（区级 1100/1150）✓ 区级胜出
读面探针：11/11 ✓（规范序第一个区胜、按币对回落、空区表 = GOV 级逐值）
```

**⑤ DEBUG 级（新增"为什么"日志真的产出）**

```
DEBUG io.mosire.simos.economy.fx - event=FX_WINDOWS_ASSEMBLED_DETAIL day=3 windows=2 zones=3 zonesWithOfficialRates=2 governmentsWithRates=0 reason=有生效报价（区级优先、按币对回落 GOV 级）
DEBUG io.mosire.simos.economy.fx - event=FX_WINDOW_ASSEMBLED day=3 government=gov-unit-gov-tp-copper base=copper quote=silver buyPerMille=900 sellPerMille=950 reserveCapBaseMilli=50000 rateSource=market-zone:c-tp-copper
DEBUG io.mosire.simos.economy.fx - event=FX_WINDOWS_ASSEMBLED_DETAIL day=3 windows=0 zones=3 zonesWithOfficialRates=0 governmentsWithRates=0 reason=没有任何生效报价 ⇒ 本轮没有外汇窗口（fail-closed：没有政策价可锚）   ← three-powers-none
```

---

## 6. 会改变数值行为的清单（给测试 Agent / 控制方）

| # | 世界形态 | 变不变 | 依据 |
|---|---|---|---|
| 1 | **区表非空 + 有区级覆盖** | **变（本批目的）**：区级报价现在产生/改写窗口；FX 面开张后家户货币余额、后续商品市场随之变化 | 探针 ②③（360 tick：80 轮 FX_ROUND、14 笔成交、199 条具名拒） |
| 2 | 区表非空 + **无**区级覆盖 | **不变**（窗口逐值相同，含次序与 cap） | 探针 `gov-rates` 判据"回落路径与旧接线逐值相同" |
| 3 | 区表为空（small-world / corridor / v17levant） | **不变**（直接委托旧路径） | `FxRoundInput.java:145`；`small-world` 探针 |
| 4 | 有区表但一条汇率都没有 | **不变**（0 窗口） | `three-powers-none` 探针 |
| 5 | 一个 GOV × 一个币对 | **新判据：恰一个窗口**（不重复投放容量） | §2.4 |
| 6 | 一个 GOV 下辖多区且同币对多价 | **新判据：规范序第一个区胜出**（其余 DEBUG 具名） | §4；读面探针 |
| 7 | 不同 GOV 的两个区对**同一币对**报价 | 两个窗口仍进**同一本世界级币对簿**（A2 既有语义；B4 未新增歧义类别 —— 旧路径本来就允许多 GOV 报同一币对） | `FxSettlement.java:189-207`（未改动） |
| 8 | 日志 | 新增 INFO/DEBUG 事件；**不改状态、不改公式、不影响结算** | §2.6 |

**受影响的硬编码字面量**：无新增；沿用的仍是 `GovFxWindow.DEFAULT_RESERVE_CAP_PER_MILLE`（储备上限口径未动）。

---

## 7. 未完成 / 未验证 / 偏离（如实记）

1. **未把 FX 撮合域按区收窄**（任务书红线 + 待用户裁定）。⇒ 现存的歧义形态只有一个：**不同 GOV 的两个区对同一币对报价**时，两窗口共处同一本币对簿，
   家户可能与被"政策价更高/更低"的那一侧成交 —— 这与 A2 阶段"多个 GOV 报同一币对"是**同一类**既有语义，本批没有新增歧义类别，故不构成 fail-closed blocker；
   但"哪个区的人跟哪个窗口成交"仍是**待用户裁定**的开放点（阶段 3 口岸/管制）。
2. **`FX_ZONE_RATE_CONFLICT` 未在真世界触发**：three-powers 是"一 GOV 一区"，造"同 GOV 两区"需要真 GM 命令先腾出区格，超本批范围。
   ⇒ 该日志行**代码路径已复核、未跑**；它依赖的"谁胜出"判据由读面探针覆盖（11/11）。
3. **未跑** `test` / `verify` / `package`（任务书禁止；本机有服务在跑 `--gui-port 6051` 且占着 shaded jar）。
   ⇒ 未跑 Spotless/Checkstyle/SpotBugs 全量门禁。格式自证换成：google-java-format **1.28.0 与 1.30.0 双版本输出逐字节相同且 dry-run 收敛**
   （只改我自己的行，diff 逐 hunk 核对过）；`compile` 已含 Checkstyle（`validate` 阶段）✓。
4. **未做整轮数值 diff**（旧码 worktree vs 新码，360 tick 逐值对照）：本批判据是"窗口逐值对照 + FX 事件 0 → 80"，不是全量经济读数 diff。
5. **一处小重复（如实记）**：`(base, quote)` 升序比较器在 `MarketZoneBook.RATE_ORDER` 与 `FxRoundInput.RATE_ORDER` 各一份私有副本
   （两处服务同一约定，都是"确定序"而非"政策"）；未合并以免把比较器提升成公共面。
6. 探针**未覆盖**：决策人权限桶（区级报价不给决策人开，B2 已裁）、`corridor` / `v17levant`（区表为空，与 `small-world` 同形）、
   存档往返（区级报价的 Codec/ChangeSet 是 B2 的账，本批未动状态形状）。
