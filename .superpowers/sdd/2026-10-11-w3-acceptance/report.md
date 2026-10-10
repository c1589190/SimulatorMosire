# W3 验收报告 —— 两段式 tick + 死亡率移交（经济只报缺口、社会算死）

- **角色**：W3 验收 Agent（**只读验收** —— 不改仓库任何源码/测试/文档；装置、store、脚本、日志全落 `/tmp/w3acc/**`）
- **被验对象**：`97108970`（HEAD）= W3 `00765c45` + W3-fix `97108970`（W3 的设计父提交 `57d74f26`，代码父提交 `e3279670` = W2-fix7）
- **判据来源**：设计书 `docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md` **v3.11** §2 S-10/S-11、§6.1 的 W3 行、§6.2 G1–G7；实现账本 `.superpowers/sdd/2026-10-11-w3-two-phase-tick-and-mortality/impl-ledger.md`（W3 / W3b / W3-fix 三节）；本任务书 §1 的 9 项
- **时间**：2026-10-11 05:54 – 06:10（CST）
- **一句话结论**：**项 1 绿（次序与同 revision 都有原文证据）、项 2 绿（179/179 逐户一致、Σ 精确）、项 3 绿（经济侧零判死 + 饥荒死亡真进了 `FlowRow.deaths`）、项 4 绿（两种世界都真有饥荒：行数/原文/曲线/死因齐）、项 5 绿（缺口=0 的 43 天：0 行 FAMINE_MORTALITY、22 死 vs 基准预期 22.31）、项 6 绿（三份 dump md5 全等、0/68,963 叶差）、项 7 绿（Δ=1；中位 7.364 s，同会话 A/B 对 W1 基线中位 7.453 s）、项 8 部分（人口/货币/债务精确；货物只能单边）、项 9 部分（`HOUSEHOLD_VITAL_WRITEBACK` 真行 + INFO 门对；MORTALITY 灯判据真被满足、GM 构造下 HTTP 读得到，但自然世界里被一条既存 NPE 挡在读口外）。**
  **两条真缺陷**：**D1** `CrisisMonitor` 的 FOOD 灯 `stressByAgeBracket=null` 撞 `Map.copyOf` ⇒ `GET /api/social/population` 500（既存，但 W3 的缺粮读数让它大面积触发，并**遮蔽 MORTALITY 灯**）；**D2** `famineDeaths` 读数**恒 0**（单户单日 1 人死亡时 `floorDiv(1×1000, 1033)=0`），实测 377 个 `cause=BASE_PLUS_FAMINE` 死亡事件全部被读成 0。

---

## 1. 装置

### 1.1 jar（从头构建；**未覆盖任何运行中服务的 jar**）

```bash
# 开工时 pgrep -af java → 无 simos 服务在跑；git status --porcelain → 空
tools/mvn-lock.sh -DskipTests package -pl simos-app -am      # BUILD SUCCESS，16/16，3.162 s（增量）
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar
```

| | 值 |
|---|---|
| 被验 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，**md5 `1e978b767e8d3f276602cff0493d35d0`**（27,100,148 字节，**05:55** 构建） |
| 构建日志 | `/tmp/w3acc-build.log`（`BUILD SUCCESS` / `Total time: 3.162 s` / `rc=0`） |
| 快照副本（本次所有跑用它） | `/tmp/simos-shaded-09InCu.jar`（同 md5；`tools/run-shaded.sh` 会把 jar 复制成进程独占快照） |
| W1 基线 jar（性能对照） | `/tmp/simos-shaded-EZy0IT.jar`，md5 `a90e11a9ec533010a8b8b568efdad067`（= W2 验收报告 §1.1 的"改前"侧） |
| W2 jar（读数前后对照） | `/tmp/simos-shaded-8wPhQ4.jar`，md5 `c8e64c97c9fc061f4b582b56c6ba05ac`（= W2 验收报告 §1.1 的"改后"侧） |

**jar 内容自证**（不是只看 md5）：

```
io/mosire/simos/app/household/FamineMortalityBridge.class            True
io/mosire/simos/social/population/FamineMortality.class              True
io/mosire/simos/economy/time/EconomyHouseholdGap.class               True
EconomySettlement.class:  "HOUSEHOLD_VITAL_WRITEBACK" in bytes = True
EconomySettlement.class:  applyFamine = False , scaleLaborOfUnit = False
SettlementStage.class:    RATION_GAP_REPORT = True , FAMINE_MORTALITY = False
HouseholdBook.class:      FAMINE_MORTALITY = True
```

### 1.2 启动命令 / 配置 / store / 端口

```bash
bash tools/run-shaded.sh <shaded.jar> --store /tmp/w3acc/store-<tag> --world=<small-world|three-powers> \
  --config /tmp/w3acc/shell-*.json --gui-port P --mcp-port P+1 --approval-port P+2
# 日志档（JAVA_TOOL_OPTIONS）：-Dsimos.economy.logLevel=… [-Dsimos.social.logLevel=…] [-Dsimos.app.logLevel=DEBUG]
# 推进：POST /api/advance {"branch":"main","expectedRevision":R,"from":A,"to":B}
# GM： POST /api/command {"type":"…","payloadJson":"{…}","branch":"main","expectedRevision":R}
# 读口：GET /api/social/population?q=&r= / GET /api/economy/overview
```

| 配置 | 内容 | md5 |
|---|---|---|
| `/tmp/w3acc/shell-sw.json` | small-world / checkpointInterval=1 | `7c1a82f8507e27d74287e8d1f558158b` |
| `/tmp/w3acc/shell-sw-full.json` | small-world / interval=1000 | `c6729a689045c2ec168a7f335d8fcb2f` |
| `/tmp/w3acc/shell-tp.json` | three-powers / interval=1 | `8d877ef31c88e149632692d8fe55f767` |
| `/tmp/w3acc/shell-tp-full.json` | three-powers / interval=1000 | `c6e8f196da4b22dfeb7dee656e4157e7` |

**运行清单**（**一次只有一个世界实例**、一次只有一个 Maven；端口 103xx/104xx）：

| tag | 世界 | 推进 | 端口 | 日志档 | 结果 |
|---|---|---|---|---|---|
| `sw180` | small-world | 0→180（18 次 ×10 天，每段 dump） | 10301 | economy DEBUG | 200 全绿；180 天日读数 |
| `sw10trace` | small-world | 0→10 | 10303 | **economy TRACE + social DEBUG + app DEBUG** | 逐户缺口/ famine 明细 |
| `sw3day` | small-world | 0→3 **逐日**（3 次 advance） | 10305 | DEBUG | 单日 revision 的 changeset 取证 |
| `b2one` / `b2seg` / `b2daily` | small-world | 0→360 一次 / 0→120→240→360 / **逐 tick 360 次** | 10430/10434/10438 | INFO | 三份 dump 全等 |
| `tp600` | three-powers | 0→600 单次 | 10330 | DEBUG | **200**（W2 的 day540 回归已不复现） |
| `sw1000` | small-world | 0→1000 单次 | 10402 | INFO | 200、Δ=1、16.79 s |
| `perf-w3-1..3` | small-world | 0→360 单次 ×3 | 10461–63 | INFO | 见 §2.7 |
| `ab-w1-1..3` / `ab-w3-1..3` | small-world | 0→360 单次，**两 jar 交替** | 10470–80 | INFO | 见 §2.7 |
| `p1famine`→`famine` | small-world | 0→60 → **GM 搬走全部家户存粮** → 60→90（5 天/段） | 10321/10311 | economy+social DEBUG | 人工缺粮世界 |
| `p1nogap`→`nogap` | small-world | 0→5 → **GM 给每户补足 240 天口粮** → 5→65 | 10323/10313 | economy+social DEBUG | **缺口=0 世界（项 5）** |
| `mort` | small-world | 0→5 → **GM 把 `hh-0_0-urban-poor_peasant` 死亡率抬到 100000 ppm/tick** → 5→15 逐日 | 10327 | DEBUG | **点亮 MORTALITY 灯（项 9）** |
| `probesw180` / `probefamine` / `probestack` / `t120probe` | 已有 store 上**只读探针**（不发 advance） | 10317/10315/10319/10325 | — | 危机灯 500 定位 |

### 1.3 脚本（全在 `/tmp/w3acc`，**未落仓**）

| 脚本 | 作用 | md5 |
|---|---|---|
| `runner.py` | 起服务→等就绪→按 plan 推进/发命令/落 dump/HTTP 只读探针→收进程；结果含 revisions 表 | `f5a2ab48e341142ad408e8d4a0c7f33d` |
| `canon2.py` | checkpoint→只含状态的规范串（**剔各模块 ref/timestamp**）+ 逐叶深比对 | `a565a5c506c29ed06c4e63d2ce5b3c4d` |
| `an.py` | 逐户 Social vs Economy 人口一致 / 世界级总量 / flows 生死 | `a972f40f718b53835b51804d12049b3f` |
| `w3ev.py` | 日志事件读数（count/series/raw/order） | `09d93f480af71d4323e26e8d191d67b6` |
| `events.py` | 事件解析（`event=NAME k=v`，花括号内不误切） | `0706d5077785072593e370b1b5d4bda3` |
| `lights.py` | 按 CrisisMonitor 判据从状态现算每格红灯（定位"哪些格该亮哪盏灯"） | `e4d739e85f23a9205f241872b00f47bb` |
| `mkgm.py` | 生成 GM 载荷（搬走粮食 / 补足口粮） | `aaf0b23995335dcba384a017ca040255` |
| `runs.sh` / `gmrun3.sh` / `perf-ab.sh` / `mort.sh` / `t120.sh` / `probe-*.sh` | 各轮驱动 | `f2c39d94…` / `5fdd6af3…` / `d503cf0c…` / `62dad35f…` / `468aec69…` / `ca9a3947…`·`7f84ef99…`·`d1c3b25e…` |

**★ 装置自证（本报告引用的每个数字都来自这套装置）**：`canon2.py` 用 W2 验收报告 §2.6 的三份 dump 回归，
**逐字复现出 W2 报告写的 md5 `d2b232c5a881af455537c71397c97c22`**（三份全等）——
证明本报告的规范串口径与 W2 验收**同一把尺**（`/tmp/w2acc/canon.py` 原样比会因模块级 `ref` 不同而给出不同 md5，
故本批用的是剔除后的 `canon2.py`；这一点也在 W2 报告里核过，不是本批新造口径）。

**读数四路**：① `<store>/checkpoints/main/<rev>.json` 全量信封；② `revisions` 表的 `changeset_json`（**逐 revision 的模块级 upsert**）；
③ 服务进程 stdout 的具名事件日志；④ HTTP 读口（`/api/social/population`、`/api/economy/overview`）。

---

## 2. 逐项真数字

### 2.1 项 1 —— 两段式次序真的生效（同一 tick 内的原文证据）

**同一天（day 3）的日志次序**（`sw180`，economy DEBUG；原文按出现序，只删了 `origin=` 便于阅读）：

```
05:56:14.720 INFO  io.mosire.simos.economy.settlement - event=DAY_START day=3 rows=178 population=4801 …
05:56:14.778 DEBUG io.mosire.simos.economy.settlement - event=HOUSEHOLD_GAP_POOL day=3 householdsEvaluated=178
        householdsWithRationGap=8 householdsWithProductionCostGap=105 rationGapTotalMilli=29400
        rationNeedTotalMilli=320728 productionCostGapTotalMilli=138867236 marketRan=true
        cycleGrainUnmetCarriedMilli=0 why=w3-report-only: economy-no-longer-kills-or-scales-labor
05:56:14.778 INFO  io.mosire.simos.economy.settlement - event=HOUSEHOLD_GAP_REPORTED day=3 householdsWithRationGap=8
        householdsWithProductionCostGap=105 rationGapTotalMilli=29400 rationNeedTotalMilli=320728
        productionCostGapTotalMilli=138867236 marketRan=true why=S11-second-half: ration-gap-goes-to-social
05:56:14.778 INFO  io.mosire.simos.economy.settlement - event=DAY_END day=3 population=4801 deaths=0 unmet=41160
        borrowedGrainMilli=190160 repaidGrainMilli=0 debtContracts=84 transfers=106 marketFills=23 marketUnfilled=427
05:56:14.779 INFO  io.mosire.simos.social.settle     - event=POPULATION_SETTLE_START day=3 households=178 groups=632 remainders=662
05:56:14.781 INFO  io.mosire.simos.social.settle     - event=POPULATION_SETTLE day=3 households=178 births=4 deaths=2
        events=6 deltaHouseholds=6 remainders=663 cleanedRemainders=0 famineInputHouseholds=105 famineHouseholds=8
        famineUnknownHouseholds=0 famineDeaths=0 maxStarvationRatioPerMille=1000 famineRateMaxPpmPerTick=1000
05:56:14.781 INFO  io.mosire.simos.social.settle     - event=FAMINE_MORTALITY day=3 householdsWithRationGap=8 famineDeaths=0
        maxStarvationRatioPerMille=1000 firstHousehold=hh--1_-1-rural-poor_peasant(gapMilli=5350,needMilli=5350,ratioPerMille=1000)
        famineRateMaxPpmPerTick=1000 why=w3: economy-reports-gap-social-decides-mortality (S-11 second half)
05:56:14.782 INFO  io.mosire.simos.economy.population - event=HOUSEHOLD_VITAL_WRITEBACK households=6 births=4 deaths=2
        unit=person why=w3-fix: social-is-population-authority; economy-receives-births-deaths-into-flowrow
05:56:14.782 INFO  io.mosire.simos.app.time       - event=FAMINE_MORTALITY_BRIDGED day=3 householdsWithRationGap=8
        rationGapTotalMilli=29400 rationNeedTotalMilli=320728 famineDeaths=0 deaths=2 births=4
        why=w3: economy-reports-gap-social-decides-mortality (S-11 second half)
```

⇒ 次序 = **经济 DAY_START → 经济报缺口（HOUSEHOLD_GAP_POOL/REPORTED，在 DAY_END 之前）→ 经济 DAY_END → 社会 POPULATION_SETTLE（含饥荒项）
→ 社会 FAMINE_MORTALITY → 经济 HOUSEHOLD_VITAL_WRITEBACK（人口 delta 回写）→ App FAMINE_MORTALITY_BRIDGED**，
与账本 §2.2 声称的 ①—⑥ 一致（账本把"报缺口"记在 DAY_END 之后，实际是**结算步内、DAY_END 之前**——见 §3 D-口径注）。

**同一条 revision 的证据（两路）**：

1. **`revisions` 表**：`sw180` 18 次 advance → 19 行（创世 1 + 18），每次 advance **恰好 +1**：
   `[1:Bootstrap/tick0], [2:AdvanceTime/tick10], …, [19:AdvanceTime/tick180]`；`b2daily` 360 次 advance → 361 行（Δ=360=推进次数）。
2. **单日 revision 的 changeset 同时含两腿**（`sw3day`，`revision 4 = tick 3`，一条 `core.AdvanceTime` 的 changeset 里有 **6 个模块**：
   `actor/economy/gov/sd/social/unit`）：

| 户 | revision 3（tick 2） | revision 4（tick 3） |
|---|---|---|
| `hh--1_-1-rural-rich_peasant` | social 成员 **30** / economy `classes.population` **30** / `flows.deaths` **0** | social 成员 **29** / economy `population` **29** / `flows.deaths` **1** |
| `hh--1_2-rural-middle_peasant` | 71 / 71 / 0（births 1） | **70** / **70** / deaths 1（births 1） |

⇒ 同一条 revision 的同一份 changeset 里，**Social 判的那条死亡**与**经济收到的 `deaths` 与人口**是同一笔；
服务端日子也在同一次 `ADVANCE_END … revision=14 from=85 to=90 spanDays=5`（一次 advance 一段区间，一次落盘）。

### 2.2 项 2 —— 逐户一致（Social 户人口 == Economy `HouseholdEconomy.population`）

`an.py hhpop`：逐户取 Social `households[h].members` 求和 与 Economy `classes[h].population` 逐值比。

| 世界 / 检查点 | Social 户数 | Economy 行数 | **一致/总数** | 只在一侧 | Σ Social | Σ Economy |
|---|---|---|---|---|---|---|
| small-world `sw180-d00010`（tick 10） | 178 | 178 | **178 / 178** | 0 / 0 | 4799 | 4799 |
| small-world `sw180-d00090`（tick 90） | 179 | 179 | **179 / 179** | 0 / 0 | 4615 | 4615 |
| small-world `sw180-d00180`（tick 180） | 179 | 179 | **179 / 179** | 0 / 0 | 4517 | 4517 |
| small-world `t120probe-d00120`（tick 120） | 179 | 179 | **179 / 179** | 0 / 0 | 4525 | 4525 |
| 人工缺粮 `famine-d00090`（tick 90） | 179 | 179 | **179 / 179** | 0 / 0 | 4567 | 4567 |
| 人工无缺口 `nogap-d00065`（tick 65） | 178 | 178 | **178 / 178** | 0 / 0 | 4821 | 4821 |

⇒ **一致户数 = 总户数（179/179、178/178），Σ 逐值相等**（P0 的 138/138 式，本批 179/179）。

**Σ 人口守恒（事件账）**：创世 Social Σ = **4800**；`POPULATION_SETTLE` 逐日累加 **Σbirths=145、Σdeaths=428**
⇒ `4800 + 145 − 428 = 4517` = tick 180 的 Social Σ **= Economy Σ 4517**（差 0）。
（`sw1000` 的同期账：Σbirths=682、Σdeaths=2576，见 §2.9 人口曲线。）

### 2.3 项 3 —— 死亡只在社会侧判定

**`simos-economy/src/main` 里"写 `deaths`"的点，逐个结论**（HEAD 实测行号）：

| # | `file:line` | 是不是"判定" | 证据 |
|---|---|---|---|
| 1 | `FlowRow.java:106`（record 组件）、`:136-137`（非负守卫）、`:241`（`withAdditionalGrainTaxPaid` 原样搬） | ❌ 存储/守卫/搬运 | 无任何公式；`deaths < 0` 具名拒 |
| 2 | `EconomySettlement.java:5153` / **`:5163` `base.deaths() + deaths`**（`withLifecycle`，**唯一累加点**） | ❌ 落账 | 入参由调用方原样传来；方法内零判定、零公式、不缩劳动 |
| 3 | ↳ 调用点 `EconomySettlement.java:4665`（批次侧，数来自 `LotChange.deaths()`） | ❌ 接收 | **`src/main` 零调用点**：`applyPopulationChange`/`applyPopulationChangeInto`（`:4569`/`:4586`）在 main 里只有自身定义与 `EconomyDayStepper.applyPopulationChange`（`:534`）这个同样无人调用的包装 |
| 4 | ↳ 调用点 `EconomySettlement.java:4902`（逐户侧，**W3-fix 新增**） | ❌ 接收 | 数由 `VitalSettlementResult.deathsByHousehold()` 原样传来（Social 结算产物）；入口先 fail-closed 交叉核（`:4843-4878`）再落账，**不删债、不缩劳动** |
| 5 | `EconomySettlement.java:3249-3259`（`settleOneDayInto` 重建流水） | ❌ 搬运 + 清账 | `(acc==null?0:acc.deaths()) + dayDeaths`，而 `:3235 long dayDeaths = 0L`（经济侧本步不判死）；`acc==null` 只在**新周期第一天** |
| 6 | `EconomyCodec.java:471`（`LegacyFlowRowDeserializer`） | ❌ 反序列化 | Jackson 读回字段值 |

**"判死通道已整条删除"的 grep 证据**（零代码引用，命中全在注释/javadoc）：

```
$ grep -rnE "applyFamine|scaleLaborOfUnit|FAMINE_MORTALITY_PER_MILLE|famineMortalityPerMille|deathsToday" \
    --include=*.java simos-economy/src/main/java
7 命中（EconomyDayStepper:132、EconomySettlement:379/3230/4554/4556-4557/9893、FlowRow:37）
⇒ 全部是注释/javadoc；binaries 侧复核：EconomySettlement.class 里 applyFamine / scaleLaborOfUnit 均 False
```

**"famine 死亡确实进了经济读数"的真样本**（不是静态链条）：

- Social 侧事件（`sw3day` 世界 tick 3 的 Social `populationEvents`）：
  `vital:DEATH:hh--1_-1-rural-rich_peasant:rural:-1_-1:FEMALE:s2-1:3:FEMALE`，
  `reason = "ratePpm=33 faminePpm=1000 starvationRatioPerMille=1000 cause=BASE_PLUS_FAMINE"`，`count=1`。
- 经济侧同一 revision（`revision 4 = tick 3` 的 changeset）：该户 `economy.flows[...].deaths = 1`、`classes.population 30 → 29`、Social 成员 30 → 29。
- 长窗口计数（`sw180` tick 180 的 `populationEvents` 窗口，覆盖 day 0..180）：**DEATH 事件 428 条里
  377 条 `cause=BASE_PLUS_FAMINE`、51 条 `cause=BASE`**，分布在 128 个不同日子。
- `FlowRow.deaths` 的读口真数（`sw180-d00180`）：**51 个户 `deaths > 0`，本周期 Σdeaths=55、Σbirths=47**
  （对照 W2 的 `p180-d00180.json`：`flowDeathsThisCycle=0`、`householdsWithDeaths=0`）。

> ★ 但**`famineDeaths` 这一列读出来是 0**（真缺陷 D2，见 §3）：377 条饥荒致死事件 ⇔ Σ`famineDeaths` = 0。

### 2.4 项 4 —— 饥荒死亡率真的发生（新机制）

**世界不是造的，是自然就有的**：small-world 的粮在市场上是**结构性短缺**（W2 已知 D≫S），
于是有一批家户**天天吃不上**（`why=ration-short-after-all-relief`）。逐户原文（`sw10trace` TRACE）：

```
day=3 household=hh--1_-1-rural-poor_peasant  population=80  rationGapMilli=5350 rationNeedMilli=5350
      productionCostGapByCommodity={cloth=6895, grain=3523250} marketRan=true why=ration-short-after-all-relief
day=3 household=hh--1_-1-rural-rich_peasant  population=30  rationGapMilli=2000 rationNeedMilli=2000
      productionCostGapByCommodity={cloth=2555, grain=1278000} marketRan=true why=ration-short-after-all-relief
```

#### ① `FAMINE_MORTALITY`（social INFO）与 `HOUSEHOLD_GAP_REPORTED`（economy INFO）的行数与原文

| 世界 / 窗口 | `HOUSEHOLD_GAP_POOL`(DEBUG) | `HOUSEHOLD_GAP_REPORTED`(INFO) | `FAMINE_MORTALITY`(social INFO) | `FAMINE_MORTALITY_BRIDGED`(app INFO) | `HOUSEHOLD_VITAL_WRITEBACK` |
|---|---|---|---|---|---|
| small-world 180 天（`sw180`） | 180 | **174** | **174** | 174 | 162（144 INFO + 18 DEBUG） |
| small-world 1000 天（`sw1000`，INFO） | — | — | **993** | — | 876（全 INFO） |
| three-powers 600 天（`tp600`） | 600 | **590** | **590** | 590 | 515 |
| 人工缺粮 30 天（`famine`，day 61-90） | 30 | **30** | **30** | 30 | 30 |
| 人工无缺口 43 天（`nogap`，day 6-48） | 43 | **0** | **0** | 0 | 45（全程 day 6-65；该事件无 `day` 字段，无法按天切 —— D4） |

**行数与"有缺口的天"严格同集（集合级等价，实测）**：`sw180` 的 180 天里 `FAMINE_MORTALITY` 覆盖 **174 天**，
没覆盖的 6 天 = **[1, 2, 120, 121, 122, 123]**，与 `POPULATION_SETTLE.famineHouseholds == 0` 的那 6 天**完全相同**（两边交集为空、并集 180）
—— 前 2 天尚未开市、后 4 天是周期切换（需求/流水归零）；`nogap` 的干净窗口 43 天 = **0 行**。

**原文一条（economy 报缺口，`sw180` day 3）**：

```
event=HOUSEHOLD_GAP_REPORTED origin=economy-population originKind=tick day=3 householdsWithRationGap=8
householdsWithProductionCostGap=105 rationGapTotalMilli=29400 rationNeedTotalMilli=320728
productionCostGapTotalMilli=138867236 marketRan=true why=S11-second-half: ration-gap-goes-to-social
```

**原文一条（social 算死，`sw180` day 3）**：

```
event=FAMINE_MORTALITY origin=social-settle originKind=tick day=3 householdsWithRationGap=8 famineDeaths=0
maxStarvationRatioPerMille=1000 firstHousehold=hh--1_-1-rural-poor_peasant(gapMilli=5350,needMilli=5350,ratioPerMille=1000)
famineRateMaxPpmPerTick=1000 why=w3: economy-reports-gap-social-decides-mortality (S-11 second half)
```

**逐户明细（`sw10trace`，social DEBUG）**：

```
event=FAMINE_MORTALITY_HOUSEHOLD day=3 household=hh--1_-1-rural-poor_peasant rationGapMilli=5350 rationNeedMilli=5350
starvationRatioPerMille=1000 faminePpmPerTick=1000 productionCostGapByCommodity={cloth=6895, grain=3523250}
why=w3-famine-rate-is-social-authority: linear-in-realized-ration-shortfall
```

#### ② 人口曲线（逐 10 天）+ 死因分布

**small-world 未干预（`sw180`）**：

| 天 | 10 | 30 | 50 | 70 | 90 | 110 | 120 | 140 | 160 | 180 |
|---|---|---|---|---|---|---|---|---|---|---|
| 人口 | 4799 | 4769 | 4707 | 4662 | 4615 | 4558 | 4525 | 4522 | 4520 | **4517** |
| 本周期累计死亡 | 13 | 58 | 135 | 196 | 264 | 332 | 372 | 24 | 36 | 55 |

（180 天 **−283 人**；W2 同期同世界是 **+46 人**（4811→4857），这是本批**有意的数值行为改变**。）

**人工缺粮（`famine`，day 60 把 27 户的 80,089,353 毫粮全部搬走）**：

| 天 | 60 | 65 | 70 | 75 | 80 | 85 | 90 |
|---|---|---|---|---|---|---|---|
| 人口 | 4687 | 4666 | 4641 | 4630 | 4612 | 4588 | **4567** |
| 断顿户数 | — | 106 | 106 | 106 | 106 | 106 | 106 |
| 当日死亡 | — | 4 | 5 | 3 | 3 | 10 | 7 |

（30 天 **−120 人 = −2.6%**；同期未干预的 `sw180` 同窗口是 4687 → 4615 = **−72 人 = −1.5%**。）

**死因分布（`populationEvents` 的 `reason`：`cause=BASE_PLUS_FAMINE` vs `cause=BASE`）**：

| 世界 / 窗口 | famine 项参与（`BASE_PLUS_FAMINE`） | 纯基准（`BASE`） | Σ`famineDeaths` 读数 |
|---|---|---|---|
| small-world `sw180`（day 0-180，N=428） | **377** | 51 | **0** ← 缺陷 D2 |
| 人工缺粮 `famine`（day 61-90，N=145） | **145** | 0 | **0** ← 缺陷 D2 |
| 人工无缺口 `nogap`（day 6-48，N=22） | **0** | 22 | 0（**正确**） |
| three-powers `tp600`（600 天，Σdeaths=901） | —（INFO 档未采事件窗口） | — | **0** ← 缺陷 D2 |
| small-world `sw1000`（1000 天，Σdeaths=2576） | —（同上） | — | **0** ← 缺陷 D2 |

#### ③ 断顿户数 / 比例

来自 `HOUSEHOLD_GAP_POOL`（economy，逐日，`householdsEvaluated` = 当时户数）：

| 世界 / 窗口 | 有缺口的天 | 断顿户数（最小/中位/最大） | 最大比例 |
|---|---|---|---|
| small-world `sw180`（180 天） | **174 / 180（96.7%）** | 0 / **56** / **87** | **87/179 = 48.6%** |
| three-powers `tp600`（600 天） | **590 / 600** | —（最大 **160** 户） | — |
| 人工缺粮 `famine`（day 61-90） | **30 / 30** | 106 / 106 / **108** | **108/179 = 60.3%** |
| 人工无缺口 `nogap`（day 6-48） | **0 / 43** | 0 / 0 / 0 | 0% |

**marketRan 与缺口**：缺口不是"没开市"造成的 —— day 3 是开市日（`marketRan=true`、`marketFills=23`），仍有 8 户 100% 断顿。

### 2.5 项 5 —— 负向：缺口 = 0 ⇒ 饥荒死亡率不触发（GM 构造，真跑）

**构造**（把世界内容改成"人人手里有粮"，不改代码）：day 5 用
`POST /api/command {"type":"actor.AdjustAccounts","payloadJson":{"entries":[{"household":"hh-…","goods":{"grain":+N}}…]}}`
给 86 户补足到 **240 天口粮**（+56,931,775 毫），回执 `{"result":"committed","ref":{…,"revision":3}}`。

| 口径 | 值 |
|---|---|
| 干净窗口 | **day 6 – 48（43 天）**（day 49 起补的粮被消耗/卖出，重新出现 4–16 户缺口） |
| `FAMINE_MORTALITY` 行数（窗口内） | **0**（全部 17 行落在 day 49–65） |
| `HOUSEHOLD_GAP_REPORTED`（窗口内） | **0** |
| `POPULATION_SETTLE` 的 `famineHouseholds` | **每天 0**；`famineDeaths` 每天 0 |
| 死亡事件按死因 | **22 条全部 `cause=BASE`、0 条 `BASE_PLUS_FAMINE`** |
| 实测死亡 | **22 人**（day 6-48） |
| **基准率预期**（Social 率表 67/33/667 ppm × day-5 组成 × 43 天） | **22.31 人** ⇒ **观测/预期 = 0.986** |
| 人口 | 4803（day 5）→ 4813（day 45）→ 4821（day 65）：**只走基准生死率、缓慢上升** |

对照同世界的缺粮侧（`famine` day 61-90）：106 户断顿、145 条死亡全带 famine 项、30 天 −120 人。
⇒ **S-11 第②半的"缺口=0 ⇒ 不触发"这条缺省中性**在真实世界里成立。

### 2.6 项 6 —— B2 分段等价（G3，不许丢）

同世界（small-world）、同命令、同 jar、`checkpointInterval=1`，三次推进到 **tick = 360**：

| 跑法 | advance 次数 | 末 revision | 耗时 | 规范状态 md5（剔各模块 ref/timestamp） |
|---|---|---|---|---|
| `0→360` 一次 | 1 | 2 | 6.958 s | **`8cee1eb8c6667d8d53cb412985890139`** |
| `0→120→240→360` 三段 | 3 | 4 | 7.656 s | **`8cee1eb8c6667d8d53cb412985890139`** |
| **逐 tick（360 次 advance）** | 360 | 361 | 29.638 s | **`8cee1eb8c6667d8d53cb412985890139`** |

- **差异叶数 = 0 / 68,963**（三份两两比对，`canon2.py diff`）。
- 装置自证：同一把尺量 W2 的三份 dump 也得 **0 叶差**且 md5 逐字等于 W2 报告写的 `d2b232c5…`（见 §1.3）。
- 这直接证明"缺口 outbox **逐日现算、逐日取走**"没有跨日/跨 advance 的瞬态记忆。

### 2.7 项 7 —— 性能（Δ=1 + 同世界墙钟）

**(a) Revision Δ**（一次 advance = 一次落盘，不随天数增长）：

| 命令 | Δ | 行数 | 耗时 |
|---|---|---|---|
| small-world `0→360` 单次 | **1** | 创世 + 1 | 6.958 s（b2one） |
| small-world `0→360` 三段 | 3（=推进次数） | 创世 + 3 | 7.656 s |
| small-world 逐 tick 360 次 | 360（=推进次数） | 创世 + 360 | 29.638 s |
| small-world `0→1000` 单次 | **1** | 创世 + 1 | 16.785 s |
| small-world `0→180` 分 18 段 | 18 | 创世 + 18 | 5.847 s（均 0.325 s/段） |
| three-powers `0→600` 单次 | **1** | 创世 + 1 | 65.24 s（★ 含 4.2 GB DEBUG 日志开销，不可与 W2 的 33.5 s 直接比） |

**(b) 同世界墙钟（small-world，`advance 0→360` 单次，同 config，`INFO`）**

| 侧 | jar | 样本（s） | 中位 |
|---|---|---|---|
| 本会话 W1 基线（交替 A/B） | `a90e11a9…` | 7.453 / 7.052 / 8.816 | **7.453** |
| **W3（交替 A/B）** | `1e978b76…` | 8.929 / 7.019 / 7.250 | **7.250** |
| W3（全部 7 个样本） | `1e978b76…` | 6.958 / 7.019 / 7.250 / 7.364 / 7.683 / 8.773 / 8.929 | **7.364** |

⇒ 同会话 A/B：**W3 中位 7.250 vs W1 基线 7.453（−2.7%）**；样本极差 6.958–8.929（±13% 噪声）。
参考 W2 验收报的 6.650（其同会话中位）与 W1 报的 7.158 —— **本批无显著性能劣化，但 W3 相对 W2 报告的 6.650 偏慢约 10%，
落在本机噪声带内，未做逐路径归因**（如实记，见 §5）。

**(c) 结构证据**：本批三个提交没有新增存盘路径 —— `git show 00765c45 97108970 --stat` 只动
`simos-economy/social/app` 的结算与桥接文件；新增类型全是**瞬态**（`EconomyHouseholdGap` / `FamineMortalityInput`）。

### 2.8 项 8 —— 守恒（人口 / 货币 / 债务 + 死者的债与资产）

**(1) 人口：精确**（见 §2.2）：创世 4800 + Σbirths 145 − Σdeaths 428 = **4517** = Social Σ = Economy Σ；
逐户 179/179 一致（6 个检查点）。

**(2) 货币：精确守恒（唯一增量 = 具名铸币）**

| 币 | 创世 | day 10 | day 120 | day 130 | day 180 |
|---|---|---|---|---|---|
| silver | 267,200 | 369,200 | 369,200 | **371,200** | 371,200 |
| copper | 100,000 | 100,000 | 100,000 | 100,000 | 100,000 |

核对式：`367,200（创世） + 2,000 × 2（GOV_SEIGNIORAGE day 1 / day 121，原文见 §2.8 附） = 371,200` ✔；
两次 `GOV_DEBT_ISSUE` 是"债权人 → 国库"的转移（总量不变）。
`/api/economy/overview` 同源读数：`fiscalIssue silver 2000`、`circulation {copper:100000, silver:269200}`。

**原文**：

```
event=GOV_SEIGNIORAGE day=1   government=world-silver period=1 currency=silver amount=2000 treasuryBefore=0    treasuryAfter=2000
event=GOV_SEIGNIORAGE day=121 government=world-silver period=5 currency=silver amount=2000 treasuryBefore=1900 treasuryAfter=3900
```

**(3) 债务：非关账窗口逐值对上（人工缺粮世界 day 61→85）**

| 天 | 60 | 65 | 70 | 75 | 80 | 85 |
|---|---|---|---|---|---|---|
| 货币债 Σprincipal（毫） | 1,973 | 1,973 | 1,975 | 1,978 | 1,980 | **1,981** |
| 粮食债 Σprincipal（毫） | 2,046,710 | 2,046,710 | 2,046,710 | 2,046,710 | 2,046,710 | 2,046,710 |
| 合同总数 | 121 | 121 | 122 | 123 | 123 | 123 |

`Δ货币债 = 1,981 − 1,973 = **+8**` = `Σ MARKET_DEFERRED_PAYMENT.paymentMilli`（day 68/70/73(×2)/75/78/80/83 = **8**）✔ **差 0**；
粮食债在窗口内**一字不动**（无粮债新发生/还清）；day 90（关账日）粮债 +27,344 = 资本化利息（同日 `DEBT_REPAY_ROUND`）。

**(4) 死了的人，其债务 / 资产怎么处置 —— 具名读数**

- **口径**：资产与债务都挂在**家户**账上（`actor.accounts[household].balances/money`、`economy.debtContracts[debtor/creditor]`），
  **个人不持有**；W3-fix 的逐户回写路径只写 `population` 与流水生死，**不删债、不核销、不动资产**。
- **具名样本**（人工缺粮世界，`hh-1_-1-rural-middle_peasant`）：

| 时点 | Social/Economy 人口 | 该户库存 | 作为债务人的合同 | 本周期 `deaths` |
|---|---|---|---|---|
| day 60 | 68 / 68 | `fiber 6,402,632`、钱 `{}` | **1 笔，Σprincipal 55,651** | 3 |
| day 90（其间又死了 3 人） | 65 / 65 | `fiber 6,402,632`（**一件没动**） | **1 笔，Σprincipal 56,764**（债**变大**了：关账日利息资本化） | 6 |

- **无核销事件**：三个世界日志里 `grep -iE "WRITE|OFF|FORGIVE|CANCEL|DEFAULT"` 的 `event=` **0 命中**（只有 `HOUSEHOLD_VITAL_WRITEBACK` 等无关名）。
- **代码面**：`writeOffDebtsForDeaths` 的唯一调用点 `EconomySettlement.java:4670` 在**批次路径** `applyPopulationChangeInto` 里，
  而该路径在 `src/main` **零调用点**（见 §2.3）。
- **货物**：只能单边核对（延续 W2 的 D7）—— 本批**扫了全部 66 份 checkpoint**（actor 的 `balances`/`frozenBalances`/`money`/`frozenMoney`、
  economy 的 `classes.population`/`laborMilli`/`debtContracts.principal`、social 的 `households.members` 份额），**0 处负数**；
  但**没有产出/损耗读数面**，无法给出"产出 = 消费 + 损耗 + 存量增"的闭合式（如实记，见 §5）。

### 2.9 项 9 —— 读数面

**(a) `HOUSEHOLD_VITAL_WRITEBACK` 真行**（economy，唯一写口 `withLifecycle` 的接收日志）：

| 世界 | 总行数 | 其中 INFO（`deaths>0`） | 其中 DEBUG（只有出生） |
|---|---|---|---|
| small-world `sw180`（180 天） | 162 | **144** | 18 |
| three-powers `tp600`（600 天） | 515 | **365** | 150 |
| 人工缺粮 `famine`（30 天） | 30 | **29** | 1 |
| 人工无缺口 `nogap`（60 天） | 45 | **28** | 17 |

原文（`sw180` day 3，与 Social 的 `deaths=2` 同日同数）：

```
event=HOUSEHOLD_VITAL_WRITEBACK origin=economy-population-write originKind=system households=6 births=4 deaths=2
unit=person why=w3-fix: social-is-population-authority; economy-receives-births-deaths-into-flowrow
```

（★ 该事件**没有 `day` 字段** —— 见 D4。）

**(b) `CrisisMonitor` 的 MORTALITY 灯可以亮（改前结构性不可达）**

- **改前**：`FlowRow.deaths` 在主路径恒 0（W2 的 `p180-d00180.json` 实测 `flowDeathsThisCycle=0`、`householdsWithDeaths=0`）
  ⇒ `deaths×1000/population > 20‰` 永不成立。
- **改后（GM 构造到阈值，真跑）**：day 5 把 `hh-0_0-urban-poor_peasant` 的死亡率抬到 100000 ppm/tick（`social.SetHouseholdVitalRates`，HTTP 200 rev 3），
  随后 `GET /api/social/population?q=0&r=0` **HTTP 200**：

| 天 | 6 | 8 | 10 | 12 | 15 |
|---|---|---|---|---|---|
| `evidence.deathsThisCycle` | 28 | 76 | 115 | 147 | **183** |
| `evidence.mortalityPerMille` | **32** | 91 | 145 | 194 | **254** |
| `evidence.population` | 875 | 827 | 788 | 756 | 720 |

  原文（day 6）：`{"kind":"MORTALITY","evidence":{"deathsThisCycle":28,"mortalityPerMille":32,"population":875}}`
  ⇒ **MORTALITY 灯读得到**（阈值 20‰）。
- **自然世界里的同一条判据也被满足**：`lights.py` 按 CrisisMonitor 的同一算式从状态现算 —— 人工缺粮世界 tick 90
  19 个格**全部** `MORTALITY`（mort 30–96‰，如 `-1_-1`: deaths 18 / pop 186 = **96‰**）；`t120probe` tick 120 有 16 个格 mort 91–146‰。
- **★ 但这些格在读口上读不到**：`/api/social/population` 对它们返回 **HTTP 500**（D1：FOOD 灯的 `null` evidence 撞 `Map.copyOf`）。
  ⇒ 结论：**灯"可亮"是绿（判据满足 + GM 构造下 HTTP 200 真读到了）；"在自然缺粮世界里可读"是红（被 D1 挡住）**。

---

## 3. 缺陷 / 异常清单（**只报不修**，每条最小复现）

### D1 ★★★【真缺陷·既存，但 W3 下触发面变大】`CrisisMonitor` 的 FOOD 灯让 `/api/social/population` 500（NPE），并**遮蔽 MORTALITY 灯**

- **根因（栈原文）**：

```
06:05:01.040 DEBUG io.mosire.simos.app.gui - event=GUI_REQUEST_FAILED_STACK path=/api/social/population
java.lang.NullPointerException
	at java.base/java.util.Objects.requireNonNull(Objects.java:233)
	at java.base/java.util.ImmutableCollections$MapN.<init>(ImmutableCollections.java:1193)
	at java.base/java.util.Map.ofEntries(Map.java:1680)
	at java.base/java.util.Map.copyOf(Map.java:1748)
	at io.mosire.simos.app.crisis.CrisisMonitor$Light.<init>(CrisisMonitor.java:335)   ← evidence = Map.copyOf(evidence)
	at io.mosire.simos.app.crisis.CrisisMonitor.lightsAt(CrisisMonitor.java:201)        ← FOOD 灯：evidence.put("stressByAgeBracket", null)
	at io.mosire.simos.app.crisis.CrisisMonitor.lightsAt(CrisisMonitor.java:139)
	at io.mosire.simos.app.gui.ApiViews.population(ApiViews.java:5675)
	at io.mosire.simos.app.gui.GuiServer.populationReply(GuiServer.java:1317)
```

  `CrisisMonitor.java:199-200` 明写"保留键给 null + 具名 unavailable 原因"（Batch B 的退役读数），而 `Light` 的构造期
  `Map.copyOf(evidence)` **拒 null 值** ⇒ 只要 FOOD 灯亮，整条响应就 500。
- **最小复现**（只读探针，不发 advance）：

```bash
curl -s "http://127.0.0.1:10317/api/social/population?q=1&r=-1"     # store-sw180（tick 180）→ 500 {"error":"internal error"}
curl -s "http://127.0.0.1:10317/api/social/population?q=0&r=0"      # 同实例同 tick → 200（该格 grainSat=1000，FOOD 不亮）
```

- **触发面（三档实测）**：`sw180` tick 180 → **3/19 格 500**（1_-1、1_0、1_1）；`t120probe` tick 120 → **16/19 格 500**；
  人工缺粮世界 tick 90 → **19/19 格 500**。`nogap`（缺口=0）tick 65 → 0/19 格 500（只有 CLOTH/DEBT）。
- **期望 vs 实际**：期望 `200` + `crisis:[{kind:FOOD, evidence:{…, stressByAgeBracket:null, stressByAgeBracketUnavailable:"…"}}]`；
  实际 `500 {"error":"internal error"}`（GUI 的 WARN 只给 `error=NullPointerException message=none`，**栈要开 `-Dsimos.app.logLevel=DEBUG`** 才落盘）。
- **是不是 W3 引入**：**不是**（`git log -S'stressByAgeBracketUnavailable'` → `3de6a88a`，早于 W3）；但 W3 的缺粮读数
  让 FOOD 灯在**大片格**上亮起，于是**它现在挡住了 MORTALITY 灯的读口**（§2.9b 的"红"这一半就是它）。

### D2 ★★★【真缺陷·W3-fix 新接线引入】`famineDeaths` 读数**恒 0**（单户单日 1 人死亡时 `floorDiv` 归零）

- **落点**：`HouseholdBook.java:959-963`
  `famineShareDeaths = floorDiv(deaths × famineRatePerMillionPerTick, effectiveDeathPpm)`；
  饥荒 ppm ≤ 1000、基准 33/67/667 ⇒ `effectiveDeathPpm = faminePpm + base`，**当 `deaths == 1` 时商恒 < 1 ⇒ 归 0**。
  ⇒ 只有"同一户同一天死 ≥ 2 人"才会读出非 0。
- **最小复现**（`sw3day` 世界，day 3）：

| 面 | 真数 |
|---|---|
| Social 事件 | `vital:DEATH:hh--1_-1-rural-rich_peasant:rural:-1_-1:FEMALE:s2-1:3:FEMALE`，`count=1`，`reason=ratePpm=33 faminePpm=1000 starvationRatioPerMille=1000 cause=BASE_PLUS_FAMINE` |
| 同日 Social 汇总 | `POPULATION_SETTLE day=3 … famineHouseholds=8 **famineDeaths=0** maxStarvationRatioPerMille=1000` |
| 同日 App 汇总 | `FAMINE_MORTALITY_BRIDGED day=3 … **famineDeaths=0** deaths=2 births=4` |
| 经济读数 | 同 revision 该户 `FlowRow.deaths=1`（**死亡本身进了经济读数**，只有"饥荒那一份"没进） |

- **量化失真**（窗口 `populationEvents`）：

| 世界 | `cause=BASE_PLUS_FAMINE` 死亡事件 | 按 ppm 占比的应有份额（≈N×1000/1033） | Σ`famineDeaths` 读数 |
|---|---|---|---|
| small-world `sw180`（180 天） | **377** | ≈ **365** | **0** |
| 人工缺粮（30 天） | **145** | ≈ **140** | **0** |
| small-world `sw1000`（1000 天，Σdeaths 2576） | — | — | **0**（993 天有缺口） |
| three-powers `tp600`（600 天，Σdeaths 901） | — | — | **0**（590 天有缺口） |

- **影响**：W3 判据⑤"一条 INFO『谁因什么死了多少』"里的**死亡数失真为 0**；Social/App 两条 INFO 的名字与其余字段（断顿户数、比例）都对，
  只有 `famineDeaths` 这一列错。`famineDeaths ≤ deaths` 不变量仍成立（0 ≤ deaths），**不会抛、不会红**，所以门禁抓不到。
- **建议口径**（供控制方裁定，不改）：把折算改成"按 faminePpm 占比的**期望值**"（保留小数或按 `deaths` 加权取整，
  如 `deaths==1 ⇒ faminePpm > base ? 1 : 0`），或直接以事件 `reason` 的 `cause=BASE_PLUS_FAMINE` 计数为准。

### D3 ★【文档 vs 实现漂移·既存】`actor.AdjustAccounts` 的载荷形状：文档写 `owner{kind,id}+q+r`，解析器要顶层 `household`

- **实测回执**（我第一次按文档发）：

```
POST /api/command {"type":"actor.AdjustAccounts","payloadJson":{"entries":[{"owner":{"kind":"HOUSEHOLD",
  "id":"hh-0_0-rural-landless_laborer-displaced"},"q":0,"r":0,"goods":{"grain":-42666}}]}}
→ HTTP 422 {"result":"rejected","reason":"entries[0] 的家户不合法: 字段 household 必须是家户 id 字符串: {…}"}
```

- **代码实际**：`AccountPayloads.household(node,"household")`（`:60-75`）要求 `{"household":"hh-…"}`，**且账本键 `HouseholdAccountKey` 只有家户、没有格**
  （`simos-actor/.../model/HouseholdAccountKey.java:28`）。
- **漂移面**：`AdjustAccountsHandler` 类注（`:31-44`，示例就在 `:35-37`）与 GM 工具 `ActorAdjustAccountsTool.description()` 都还写旧形状（`owner/q/r`）
  ⇒ **按工具描述发的模型会被 422 拒**。改成正确形状后同日提交成功：`{"result":"committed","ref":{…,"revision":8}}`。

### D4 ★【日志面·W2 的 D5 同族】`HOUSEHOLD_VITAL_WRITEBACK` 没有 `day` 字段

- 实测：`sw180` 该事件 **162 行，`grep -c ' day='` = 0**；而同一写口的邻居（`POPULATION_SETTLE` / `FAMINE_MORTALITY` / `HOUSEHOLD_GAP_*`）都带 `day`。
- 影响：这条"今天社会报来多少死亡"的关键读数**无法按天检索/对账**，只能按时间戳推（本报告 §2.1 的次序证据正是这样推的）。
- 最小复现：`python3 w3ev.py raw results/sw180.log HOUSEHOLD_VITAL_WRITEBACK --limit 1`（原文里没有 `day=`）。

### D5 ★★【观察·需控制方知晓，不是代码缺陷】小世界的"结构性缺粮"在 W3 下变成持续饥荒死亡，且量级很大

- 事实：small-world 长期以来有 **8–87 户（中位 56，最高 48.6%）每轮 100% 吃不上**
  （`rationGapMilli == rationNeedMilli`，`marketRan=true`，世界粮存量却有 3.7 亿毫）。
  W2 时这只是一个"缺口读数"（致死率旋钮被钉在 0‰），**W3 把它变成了 85% 的死亡来源**：
  180 天 428 死里 377 条带 famine 项、人口 **−283**（W2 同期 +46）。
- 判定"谁吃不上"的是**市场队列的先后**，而排序（S-4 的家户/区排序）是 **W4** 的活；
  ⇒ 本批的"谁死"很大程度上等于"谁排在后面"，**这不是 W3 的缺陷，但它是 W3 数值行为改变的第一大来源**，
  W4 落地后这条曲线会再变一次（W6 标定应把它算进去）。

### D6 ★【读数面缺口·延续 W2 的 D7】货物守恒无法闭合

- `goodsTotalWithTransit` 可核对"无负值、无凭空造物"，但**没有产出/损耗读数面**（`FlowRow.income` 不是完整产出账，无 LOSS 事件）
  ⇒ §2.8(4) 的货物一支仍只能单边核对。

### D7 ★【既存·播种期】创世时 4 户的 Social 人口 ≠ Economy `population`（tick 1 起自洽）

- `dumps/sw1000-tick1000.json`（revision 1 = 创世）实测：`hh--2_0-rural-middle_peasant` 68 vs 70、
  `hh--2_0-rural-poor_peasant` 78 vs 80、`hh-unit:gov-central` 2 vs 0、`hh-unit:gov-province` 2 vs 0
  （Σ 仍相等：两处都是 4800 —— 差异互相抵消）。
- `sw180-d00010`（tick 10）起全部一致（178/178），`sw3day` 的 revision 2（tick 1）changeset 里该户已是 78。
  ⇒ **播种期的一次性偏差**，不影响本批判据；但"创世状态里 Social/Economy 人口不一致"本身值得记一条（根因未查，如实记）。

### D8 ★【口径注·不是缺陷】MORTALITY 灯的窗口是"本周期累计死亡 ÷ 当日人口"

- `CrisisMonitor.java:213`：`deaths（`FlowRow.deaths` = 本周期累计）× 1000 / population（当日存量）`。
- 实测后果：同世界的同一些格，tick 120（周期末）mort = 91–146‰（灯应亮），到 tick 180（新周期 60 天后）掉到 < 20‰（灯灭）。
  ⇒ 读它的人必须知道"周期切换会把灯关掉，即使人还在死"。

---

## 4. 结论（逐项 绿 / 红 / 未验）

| # | 判据 | 结论 | 一句话依据 |
|---|---|---|---|
| 1 | 两段式次序生效 + 全在同一条 revision | **绿** | day 3 原文次序：`HOUSEHOLD_GAP_*` → `DAY_END` → `POPULATION_SETTLE`/`FAMINE_MORTALITY` → `HOUSEHOLD_VITAL_WRITEBACK`；`revision 4` 的 changeset 同时含 social 成员 30→29 与 economy `population` 30→29、`flows.deaths` 0→1；每次 advance **Δ=1** |
| 2 | 逐户一致 + Σ 守恒 | **绿** | 6 个检查点全 **179/179**（或 178/178）一致，Σ 逐值相等；`4800 + 145 − 428 = 4517 = ΣSocial = ΣEconomy` |
| 3 | 死亡只在社会侧判定 + famine 真进经济读数 | **绿**（读数列有 D2） | economy `src/main` 的全部 `deaths` 写点逐个定性 = 存储/守卫/搬运/接收（唯一累加点 `withLifecycle:5163`），判死通道 grep 7 命中**全是注释**、binaries 亦无；真样本：famine 事件 377 条 → 同 revision `FlowRow.deaths=1`；**但 `famineDeaths` 读数恒 0（D2）** |
| 4 | 饥荒死亡率真的发生 | **绿** | `FAMINE_MORTALITY` 174/180（sw）、590/600（tp）、30/30（人工缺粮）行，行数 = 有缺口的天数；原文齐；人口曲线与死因分布（377 famine vs 51 base；人工缺粮 145/145 famine）；断顿 56 户中位（最高 87 户 = 48.6%） |
| 5 | 负向：缺口=0 ⇒ 不触发、只走基准率 | **绿** | GM 补粮后的 43 天：`FAMINE_MORTALITY` **0 行**、`famineHouseholds` 每天 0、22 条死亡事件**全 `cause=BASE`**、实测 22 死 vs 基准率预期 **22.31**（0.986）、人口缓升 4803→4821 |
| 6 | B2 分段等价 | **绿** | 一次/三段/逐 tick 三份 dump 规范 md5 全 = `8cee1eb8c6667d8d53cb412985890139`，**差异叶 0 / 68,963**（同一把尺复现了 W2 的 `d2b232c5…`） |
| 7 | 性能（Δ=1 + 墙钟） | **绿（附噪声注）** | `0→360/600/1000` 单次 Δ 全 = 1（分段 = 3 / 360 = 推进次数）；同会话 A/B：W3 中位 **7.250 s** vs W1 基线 **7.453 s**（−2.7%），7 个 W3 样本中位 7.364 s；相对 W2 报告的 6.650 偏慢 ~10%（落在 ±13% 噪声带内，未归因） |
| 8 | 守恒（人口 / 货币 / 债务 + 死者债资） | **部分** | 人口精确（逐户 + Σ）；货币精确（367,200 + 2,000×2 = 371,200）；债务非关账窗口逐值（Δ货币债 +8 = Σ未付 8）；死者债/资产具名读数（1 笔债 55,651→56,764、库存一件没动、无核销事件）；**货物只能单边核对**（D6） |
| 9 | 读数面（WRITEBACK 真行 + MORTALITY 灯可亮） | **部分（绿/红各半）** | `HOUSEHOLD_VITAL_WRITEBACK` 144 条 INFO（`deaths>0`）+ 18 条 DEBUG，门对；MORTALITY 灯：GM 构造下 **HTTP 200 读到**（`mortalityPerMille` 32→254，阈值 20），自然缺粮世界判据也全满足（96‰ 等）**但读口 500** —— 被既存 D1（FOOD 灯 NPE）遮蔽 |

**总评**：W3 的机制（两段式次序、判死移交、缺口通道、逐户一致、B2、Δ=1）在真实世界里**逐条可验且数字自洽**，
"有意改变数值行为"这件事也真的发生了（人口 180 天 −283，其中 85% 的死亡带饥荒项）。
**但两条读数缺陷必须先处置**：D1（FOOD 灯 NPE）让**危机灯读口在大片缺粮格上不可用**、并且**遮住了 MORTALITY 灯**；
D2 让"死因读数"里的 `famineDeaths` **系统性为 0** —— 两条都属"账面看起来正常、数字是假的"那一族（门禁抓不到）。

---

## 5. 我没做 / 没验证的（如实）

1. **门禁**：本报告**没跑** `clean verify`（提交信息自带 16/16、3407 条、SpotBugs 15/15、前端 412/412）；验收只跑真实世界与读数，**未独立复跑门禁**。
2. **性能归因**：只做了同会话 A/B 的墙钟（6 个样本 + 3 个额外样本），**没做逐路径归因**、没测内存/GC，也没用同一条 A/B 比 `three-powers`
   （W2-fix7 的 jar 不在本机，A/B 的对照侧用的是 W1 基线 `50e9817e`，不是 W3 的代码父 `e3279670`）。
3. **D1 没修、没验证修法**：只给了栈 + 三档触发面 + 最小复现；`MORTALITY` 灯在"自然缺粮世界"里**始终没读到 200**（读到的 200 是 GM 构造世界）。
4. **D2 没做变异自证**：只做了实例化证据（377/145/2576 条事件的读数全 0）；`deaths==1` 时的 `floorDiv` 结论来自代码 + 数值推演，**没有**改成"≥2 死"去实证非 0 分支。
5. **货物守恒**：没闭合（D6）；`FlowRow.income` 与实测产出的差额（W2 的 D7）本批**没复测**。
6. **D7（创世 4 户不一致）**：只给了现象与"tick 1 起自洽"，**根因没查**。
7. **`carried`（周期累计缺口）不再入判据**这条口径：本报告只从日志侧确认 `cycleGrainUnmetCarriedMilli=0` 与逐户 `faminePpm` 由当日缺口算出，
   **没有**构造"周期前期饿过、当日没饿"的样本去证伪/证实。
8. **`FamineMortality.FAMINE_MAX_PPM_PER_TICK` 标定**：没测；本报告只给"当前取值下 100% 断顿 1‰/天 ⇒ 单户单日死亡常为 1 人（余数跨日累加）"的实测形态。
9. **three-powers 0→600 的 65.24 s 含 4.2 GB DEBUG 日志开销**，与 W2 的 33.5 s **不可直接比**；它的用途只是证明 **W2 的 D1 回归（day 540 400）已不复现**（HTTP 200、tick 600）。
10. **未验的口径**：`blockedByPortMilli`、口岸/运费场景、`0 价 + 跨格运费`、`D=S ⇒ 价格不动` 等 W2 遗留未验项 —— 本批一行没碰。
11. **没跑**：`--world=corridor` / `v17levant` / `RichWorld`；没测 >1000 天；没测并发多实例；没测 worker 线程数变化。
12. **报告纪律**：`git status --porcelain` 收尾为空（与开工一致）；未改仓库任何源码/测试/文档；未覆盖运行中服务的 jar；报告落在 git-ignored 的 `.superpowers/sdd/2026-10-11-w3-acceptance/`。

---

## 附：原始产物位置（全部在 `/tmp`，未落仓）

| 内容 | 路径 |
|---|---|
| 脚本（md5 表见 §1.3） | `/tmp/w3acc/*.py`、`/tmp/w3acc/*.sh`、`/tmp/w3acc/shell-*.json`、`/tmp/w3acc/plan-*.json` |
| dump（全量 checkpoint 信封） | `/tmp/w3acc/dumps/{sw180,sw10trace,sw3day,b2one,b2seg,b2daily,tp600,sw1000,famine,nogap,mort,t120probe,...}-*.json` |
| 服务日志 | `/tmp/w3acc/results/<tag>.log`（`sw180` 41 MB DEBUG、`sw10trace` 9.9 MB TRACE、`tp600` 4.2 GB DEBUG 已剪成 `tp600-w3.log` 22,693 行） |
| 运行结果（revisions / 耗时 / dump md5 / HTTP 探针原文） | `/tmp/w3acc/results/<tag>.result.json`、`<tag>-get*.json` |
| GM 载荷 | `/tmp/w3acc/payload-strip.json`（27 户 −80,089,353 毫）、`payload-seed.json`（86 户 +56,931,775 毫） |
| 构建日志 | `/tmp/w3acc-build.log` |
| store（每个 tag 一份，可复跑探针） | `/tmp/w3acc/store-<tag>` |
