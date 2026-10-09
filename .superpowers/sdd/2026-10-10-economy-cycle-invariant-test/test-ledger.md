# 经济循环体检（真实世界 · 2 个周期 · 守恒不变量）—— 测试代理账本

- 批次：`2026-10-10-economy-cycle-invariant-test`
- 角色：**测试代理**（本批只写测试；**未改任何生产代码**）
- 交付物：`simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java`
  - md5（最终修订）：`b7219e352b3a71fc39f9e8ac1b844b9b`
  - 用例：`twoFullCyclesInRealThreePowersWorldKeepMoneyConservedAndMarketAlive`
    `@DisplayName("经济循环体检：three-powers 真世界跑 240 天（2 个周期），逐币种货币守恒 + 市场不冻结")`
- 任务板：`team_task_get task-33` 返回 `agent "4ef05e66-…" is not a member of an active Agent Team` ⇒ **未能 claim**（按任务书"不要卡住"执行，如实记在此）。

---

## 1. 装置与判据的设计理由

### 1.1 为什么用"真实世界 + 真推进"

用户原话诉求是「**有没有现有的实际经济循环测试给我看，以验证已有机制中正常？**」。本仓现状（控制方已查实，本批复核确认）：

| 现存的近似物 | 它验什么 | 为什么不够 |
|---|---|---|
| `simos-app/.../time/Z7RemittanceE2ETest` | 真小世界推到 day30，**只验财政上缴一条链路**（`GOV_REMITTANCE_DAY`） | 不是"经济循环"整体；只有 30 天、一个链路 |
| `simos-economy/.../market/TwoRoundMarketProbeTest` | **单格合成市场**的纯数学原型，两轮 | 不推时间、不是真实世界、不算真实账 |
| 十几批的验收 `/tmp` 探针 | 一次性、跑完即弃 | **不进仓库 ⇒ 用户看不到、也守不住** |

⇒ 本批把"体检"固化成一条**进 `verify` 的测试**：真 `Shell` + 真 `AdvanceTime` + 真世界。

### 1.2 为什么选 `WorldRegistry.THREE_POWERS`（而不是 `SMALL_WORLD`）

1. **3 种货币**（silver / copper / gold，每区法定币不同）⇒「逐币种货币总量守恒」这条判据在这里**非平凡**：跨区成交、外汇、跨币种工资任何一处凭空造币/静默烧币都会当场破；单币种世界少一类泄漏面。
2. **它没有周期铸币**：`ThreePowersWorld` 用 `PopulationSeeder.seed(plan, tick)`（无政府家户）⇒ 不存在 `world-silver` 那个带 `GOVERNMENT_SEIGNIORAGE_PER_CYCLE_MILLI = 2,000` 毫/周期**真的印钱**的 demo 政府；三个 GOV 走 `economy.RegisterGovernment` 且载荷**不声明** `seignioragePerCycle` ⇒ 缺省 0。而 `small-world` 恰恰走"demo 世界政府家户"路径、出厂就带印钞 ⇒ **在那个世界上"货币总量不变"本来就不该成立**（选 small-world 会把一条设计内行为误报成缺陷，或逼我把判据放宽——两者都不许）。
3. 它是**阶段 2 判据 G1 指定的验收世界**（37 hex / 3 区 / 3 币 / 辖区两两不相交），不是为这条测试临时造的夹具。
4. 规模 37 hex / 6,350 人，**分段推进可负担**（见 §5 耗时）。

### 1.3 推进口径（★ 30 天一段，不单步）

- 依据：`docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md` §12–§13 实测 —— 单步推进 **485 ms/天** vs 分段推进 **48 ms/天**（**10 倍**），根因是"每提交一次 revision 要重建/落盘一次整份状态的派生差量"（单步 240 tick = 253.6 MB 变更集）。
- 本测试：`AdvanceTime(from→to)` **8 段 × 30 天 = 240 天**（`CYCLE_DAYS = 120` ⇒ 2 个完整产业周期），只在段末读一次状态。
- "关账日"定义：段末 = 30 天的倍数 = **GOV 官署关账周期**（`ThreePowersGovBootstrap.OFFICE_CYCLE_DAYS = 30`），同时必然命中市场例行轮（`day % 5 == 0`）；第 120 / 240 天**同时**是产业周期关账日。

### 1.4 读数来源（每一条都指明"从哪读"）

| 摘要字段 | 来源 | 口径说明 |
|---|---|---|
| tick / revision | `state.meta().timestamp().tick()` / `coreSimos().head(MAIN)` | 真 replay，不是内存引用 |
| 人口 | `SocialData.households()` 的 `members()` 份额之和 | **Social 是人口/生死的唯一权威**（不读经济侧物化视图） |
| 粮 / 布存量 | `ActorData.accounts()` 的 `balances()` | actor 账本是商品余额唯一真源；**不含在途**（`ShipmentBatch` 是并列的另一个池子）——故读作"账上存量" |
| 债务本金 | `EconomyData.debtContracts()` 的 `principal()` 之和 | 连续本金余额（计息并入本金） |
| 逐币种货币总量 | `ActorData.accounts()` 的 `money()` 逐币种求和 | 家户/组织/国库/单位**全部账户**都在这张表里 |
| 成交笔数 / 未成交 top3 | 进程内 `MarketReport`（经 `MarketReportFeed.last(mapId, tick)`） | 不落盘、重启即失；本测试同进程推进后立即读，是它的合法用法；top3 按**笔数**降序（同笔数取数量降序、再取原因字典序） |

★ 本测试用**自己的 mapId**（`econ-cycle-health`，而非缺省 `Map1`）：`MarketReportFeed` 以 mapId 为键、同一 surefire JVM 里还有别的世界也叫 `Map1`，自用 mapId 才不会串味。

---

## 2. 不变量清单与它们的由来

> 全部写在测试的类 javadoc 里（用户要"读测试就知道机制该怎样"）。**按验收判据写，不按代码反推，也不写"跑出来的数字"。**

| # | 不变量 | 判据 | 由来 / 为什么它等于"机制正常" |
|---|---|---|---|
| ① | **货币守恒**（最硬） | 逐币种货币总量（Σ 全部账户）**与创世基线逐值相同**，8 段全程 | 本仓货币只有三个合法来源：创世 `INITIAL_ENDOWMENT` 发行记录、GM `RecordMoneyIssuance`、政府周期铸币 `seignioragePerCycle`；`three-powers` 创世后**一个都不在跑** ⇒ 一切经济行为（成交/借还/税/工资/迁移/跨区结算）都必须是**余额转移**。任何一处凭空造币/静默烧币当场红 |
| ② | **非负** | 人口 > 0；粮 ≥ 0；布 ≥ 0；债务本金 ≥ 0 | 库存与债务本金都是**存量**，"透支"在本仓不存在（`HouseholdInventory` 构造期就拒负）；人口为 0 = 世界死了 |
| ③ | **人口不爆不灭** | 每段 > 0；相邻两段（30 天）比值 ∈ [1/2, 2]；且与创世比值 ∈ [1/2, 2] | 30 天尺度上人口只由生死率（ppm/tick 量级）与迁移改变；6,350 人的世界 30 天内减半或翻倍不是"机制正常"。"数量级跳变"字面是 ×10，这里取 ±50% 是**更保守**的写法。★ 不写死人数（那会把测试变成快照） |
| ④ | **市场在运转** | 每个关账日 `report.day() == 当日 tick` **且** 成交笔数 > 0 | 市场调度 = 绝对日 `day%5==0` 例行开市 + 关账日保底 + 低库存追加（`MarketSettlement.triggerFor`）⇒ 30 天倍数必命中例行轮。"报告日 ≠ 当日"或"0 笔"都说明市场**冻结**了——历史缺陷（市场冻结/借贷不触发）复发的第一征兆。★ 要求"当日"而不是"最近一轮"，为排除"读到 25 天前旧报告"的假绿 |
| ⑤ | **账目闭合** | 段末 tick == 目标 tick；head revision **严格递增**；段数 == 240/30 == 8 | 时间推进是唯一的"世界前进"入口；静默少走几天会让后面所有读数错位而没人知道 |

★ **非空转守卫**（防"在 0 上断言"）：创世每币种总量必须 > 0、创世人口 > 0、创世必须恰好 **3 种货币**（实得 copper=136,600 / gold=129,400 / silver=424,600）。

★ **红了怎么办（写进 javadoc，fail-closed）**：货币守恒红 ⇒ 先查本批是否新引入发行/回笼；若确实引入，正确修法是把**发行记录**并入等式（`Σ账户 = Σ INITIAL_ENDOWMENT + Σ FISCAL_ISSUE − Σ WITHDRAWAL`），**不是**删断言或放宽成区间；若没有引入发行 ⇒ 那是**真缺陷**，停下来如实报告。市场 0 笔红 ⇒ 按缺陷报，不许把判据降级成"允许 0 笔"。

---

## 3. 摘要段原文（终轮，`simos-app/target/economy-cycle-invariant/summary.txt`）

```text
=== 经济循环体检：world=three-powers mapId=econ-cycle-health · 8 段 × 30 天 = 240 天 = 2 个产业周期（CYCLE_DAYS=120）===
单位：粮/布/债/货币 = 毫（最小定点单位）；人口 = 人。不变量①：逐币种货币总量全程 == 创世基线（下方每行应与创世行逐值相同）。
[创世  ] tick=0   rev=1    人口=6,350 粮=512,112,500毫 布=300,000毫 债务本金=0毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场=读不到报告
[段1  ] tick=30  rev=2    人口=6,371 粮=64,535,911毫 布=299,652毫 债务本金=36,146毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第30日(PERIODIC) 成交=181笔 未成交top3=[no_lendable_goods×400笔/713,490毫 no_buyer×89笔/130,590,007毫 unsold_self_usable×81笔/552,040,648毫]
[段2  ] tick=60  rev=3    人口=6,382 粮=51,739,572毫 布=299,292毫 债务本金=531毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第60日(PERIODIC) 成交=182笔 未成交top3=[no_lendable_goods×400笔/651,741毫 no_buyer×126笔/130,602,388毫 unsold_self_usable×79笔/540,620,560毫]
[段3  ] tick=90  rev=4    人口=6,391 粮=38,930,311毫 布=298,932毫 债务本金=98毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第90日(PERIODIC) 成交=185笔 未成交top3=[no_lendable_goods×400笔/653,365毫 no_buyer×126笔/130,616,095毫 unsold_self_usable×76笔/526,548,265毫]
[段4  ] tick=120 rev=5    人口=6,405 粮=1,815,197,460毫 布=8,766,579毫 债务本金=6,061毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第120日(PERIODIC) 成交=318笔 未成交top3=[unsold_self_usable×227笔/1,473,892,042毫 logistics_capacity×184笔/707,400,977毫 no_buyer×126笔/149,759,259毫]
[段5  ] tick=150 rev=6    人口=6,407 粮=926,576,748毫 布=8,301,706毫 债务本金=12,020毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第150日(PERIODIC) 成交=173笔 未成交top3=[unsold_self_usable×190笔/1,310,831,803毫 logistics_capacity×166笔/294,865,431毫 no_buyer×122笔/148,909,591毫]
[段6  ] tick=180 rev=7    人口=6,424 粮=913,782,546毫 布=7,837,025毫 债务本金=6,400毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第180日(PERIODIC) 成交=195笔 未成交top3=[unsold_self_usable×185笔/1,292,605,687毫 logistics_capacity×178笔/295,867,006毫 no_buyer×123笔/155,036,473毫]
[段7  ] tick=210 rev=8    人口=6,431 粮=900,981,018毫 布=7,372,893毫 债务本金=6,407毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第210日(PERIODIC) 成交=181笔 未成交top3=[unsold_self_usable×185笔/1,267,182,675毫 logistics_capacity×180笔/305,561,595毫 no_buyer×123笔/158,881,548毫]
[段8  ] tick=240 rev=9    人口=6,440 粮=4,453,542,049毫 布=14,503,308毫 债务本金=1,997,624毫 | 币: copper=136,600 gold=129,400 silver=424,600 | 市场 第240日(PERIODIC) 成交=168笔 未成交top3=[unsold_self_usable×226笔/3,401,344,810毫 logistics_capacity×159笔/2,012,740,654毫 no_buyer×125笔/188,758,842毫]
=== 体检通过：8 段 / 240 天，5 组不变量全部成立（货币守恒 · 非负 · 人口不爆不灭 · 市场在运转 · 账目闭合）===
```

**读这份摘要能看到什么（人可读结论）**：货币三币全程冻结在创世值（守恒）；人口 6,350 → 6,440 平稳单增（无爆无灭）；粮在周期内被"现扣投入"抽低（day30 的 6,453 万毫）→ 关账日收获回补（day120 = 18.15 亿毫、day240 = 44.5 亿毫）；布在关账日跳到 876 万 / 1,450 万毫（纺织产出）；债务本金全程非负、随关账周期滚动；**8 个关账日场场开市、成交 168~318 笔**，未成交归因从"缺可借货"（no_lendable_goods）逐周期转向"卖不动/运力"（unsold_self_usable / logistics_capacity）——这是"市场在运转且有物流约束"的正常形态。

---

## 4. 判别力自证记录（★ 两条，均在**最终修订**上做，跑完还原并比 md5）

方法：把被测读数/期望**在测试内**改成对照值（**不改生产代码、不改判据语义**），断言必须当场红；红必须红在**被保护的那一行**上。

### M1 —— 货币守恒

- 变异：`Map<CurrencyId, Long> tamperedBaseline = genesisMoney 加 1 毫（copper）`，断言从 `.isEqualTo(genesisMoney)` 改为 `.isEqualTo(tamperedBaseline)`（即"故意错的期望值"）。
- 变异体 md5：`1dda9c46d31d9de9603d17c083bfb441`（原 `b7219e352b3a71fc39f9e8ac1b844b9b`，**字节确实不同**）。
- 结果：`rc=1`、`Tests run: 1, Failures: 1`、耗时 4.433 s，红在**货币守恒那一行**（变异修订 `:248`，即被改期望的那一行；在**未变异的最终修订**里它是 `:239–243` 的 `assertThat(reading.moneyByCurrency())…isEqualTo(genesisMoney)`）：
  `expected: {copper=136601L, gold=129400L, silver=424600L} but was: {copper=136600L, gold=129400L, silver=424600L}`
  ⇒ **1 毫的差异就会红**，这条断言不是空转。
- 还原：`cp` 回参照件，md5 复为 `b7219e352b3a71fc39f9e8ac1b844b9b`，`diff -q` 无差异。

### M2 —— "市场冻结"防线（成交笔数 > 0）

- 变异：把 `assertThat(report.fills())` 换成 `assertThat(List.<MarketReport.Fill>of())`（= **冻结世界的对照读数：0 笔**，断言语义一字不改）。
- 变异体 md5：`bf9772aaaae572fa41e425ec59c9b554`（原 `b7219e352b3a71fc39f9e8ac1b844b9b`，**字节确实不同**）。
- 结果：`rc=1`、`Tests run: 1, Failures: 1`、耗时 4.472 s，红在**成交那一行**（变异修订 `:283`；在**未变异的最终修订**里它是 `:278–282` 的 `assertThat(report.fills())…isNotEmpty()`）：
  `[第 1 段（关账日 day=30，trigger=PERIODIC）：成交笔数必须 > 0 —— 0 笔就是历史缺陷「市场冻结」复发（未成交归因：no_lendable_goods×400笔/713,490毫 …）] Expecting actual not to be empty`
  ⇒ 一旦市场冻结（0 笔），这条会当场红。
- 还原：同上，md5 复为 `b7219e352b3a71fc39f9e8ac1b844b9b`。

### 未做变异的不变量（如实记，见 §6）

②非负 / ③人口带 / ⑤账目闭合 **没有**做变异自证（理由与替代证据见 §6）。

---

## 5. 耗时（实测）

| 轮次 | 用例耗时 | 说明 |
|---|---|---|
| 首次跑通（缺省 mapId） | 15.55 s | `-Dtest=EconomyCycleHealthTest -pl simos-app -am` |
| 自用 mapId 后 | 15.87 s | |
| 终轮（md5 `b7219e35…`） | **15.15 s** | 报告 mtime `2026-10-10 02:39:04` |
| 再跑一轮（确定性比对） | 15 s 级 | 两轮摘要 **md5 完全相同**（`693750b7fe988d75c9f8422e32211605`）⇒ 逐字节确定性 |
| 与 `GuiApiTest` 同 JVM 并跑 | 15.27 s + 4.60 s（35 条全绿） | 证明自用 mapId 不与其他世界串味 |
| 整个 `simos-app` 测阶段（本轮） | 21.8 s | 含前端门禁 412/412（0.37 s） |

★ 结论：**15 s 量级，未超 60 s 上限**，对全仓 `clean verify`（2026-10-23 基线 3:03）是可接受增量（约 +8%）。

---

## 6. 我没做 / 没验证的（★ 诚实边界）

1. **未能 claim `task-33`**：任务板报"不是 active Agent Team 成员"（`agent "4ef05e66-…" is not a member of an active Agent Team`）。按任务书"不要卡住"继续执行；**未**写 `complete`。
2. **没有改任何生产代码**（`*/src/main/**` 零改动）、**没有** `git commit`、**没有**碰 `pom.xml` / `docs/**` / 别的账本（`git status` 只剩我新增的那一个测试文件；另两条 `EconomyCodecTest` 修改与 `determinism/` 新目录是**别的代理的**在飞文件，我一个字没碰）。
3. **没有做全仓 `clean verify`**：本批只跑了 `-Dtest=EconomyCycleHealthTest -pl simos-app -am`（8 轮）+ `spotless:check -pl simos-app`（读、474 文件全干净）。跨模块门禁（Checkstyle/SpotBugs/前端/其余 3000+ 条）留给定稿的总验收。
4. **②③⑤ 三条不变量没做变异自证**：非负（②）在类型层就被挡（`HouseholdInventory`/`DebtContract` 构造期拒负），"让读数变负"在不改生产代码的前提下**不可表达**（等价存活）；人口带（③）与账目闭合（⑤）要变异就得伪造 tick/revision 或人口读数，那会把测试变成"验夹具"而非"验机制"——**替代证据**是 M1/M2 已证"断言与真实读数之间没有短路"，以及终轮的**真数**（tick 30/60/…/240 逐段命中、rev 2→9 严格递增、人口 6,350→6,440 在带内）。
5. **没有验"关账日之外"的日子**：市场只在段末（30 天倍数）被检查；5 天一轮的中间轮次只在未成交 top3 里间接可见（报告是当日轮）。若某中间轮冻结而关账日不冻，本测试**看不见**。
6. **没有验"在途货物"**：`ShipmentBatch`（跨区发运中的货）不在粮/布存量读数内，我也没有断言在途量的守恒。
7. **没有验负数/越界以外的数值合理性**：粮价、工资水平、债务利率是否"合理"不在判据内（本批只验守恒与机制在运转，不验经济学的"好"）。
8. **没有验 `MarketReport` 的 `unfilled` 归因是否正确**：top3 只按报告原样聚合打印，未独立复核某一笔未成交的理由分类。
9. **没有在 `--world=three-powers` 的真进程（jar）形态下跑**：本测试走的是测试内 `Shell.start`（同一条组合根装配），未起 GUI/MCP 端口（端口传 0）。
10. **`MarketReportFeed` 是进程内瞬态**：本测试依赖"推进后同进程立即读"。若将来把市场报告改成异步/延迟投递，`报告日 == 当日` 这条可能先红——那时应先判定是投递时机变了还是市场真没开市，**不许**直接把断言放宽成"最近一轮"。
11. **`spotless:apply` 用的是单文件直调**（`google-java-format 1.28.0 -i`，因本仓 `-DspotlessFiles` 与插件 glob 语义不合、且模块内另有代理在飞文件，不敢跑模块级 `apply`）；格式化正确性由 `spotless:check -pl simos-app` 背书（474 文件、0 需改）。
12. **变异轮的行号**（M1 红在 `:248`、M2 红在 `:283`）是**变异修订**里的行号；两次变异都是在**最终修订**（md5 `b7219e35…`）上重做的（不是早期修订的数字），未变异的最终修订里对应 `:239–243`（货币守恒）与 `:278–282`（成交 > 0）。
13. **账本文件本身不进 git**：`.superpowers/sdd/.gitignore` 的内容是 `*`（整个 SDD 台账目录被忽略，这是本仓既有形态）；文件已落盘在 `.superpowers/sdd/2026-10-10-economy-cycle-invariant-test/test-ledger.md`。
