# W7e 测试批实现架构账本（2026-10-11）

> **责任区**：W7 系列（W7 + W7-fix + W7-fix2，均**未提交**，HEAD = `be963bbb`）的**测试迁移 + 指纹重基线 + 真读数**。
> **硬边界遵守情况**：改动面 = `simos-economy/src/test/**`、`simos-app/src/test/**`（新 1 个测试类 + 迁移 3 个测试文件）；
> `docs/**` / `AGENTS.md` / 其他模块 / `src/main/**` **一字未改**（★ 变异自证临时改过 4 个 main 文件，每轮立即还原并
> `md5sum -c` 逐条 OK，见 §6）；**未 commit**；**未跑 `clean verify`**（归控制方门禁）。

---

## 0. 批信息与装置（先读这一节，后面的数字都挂在这里）

| 项 | 值 |
|---|---|
| 基线 | HEAD `be963bbb` + W7/W7-fix/W7-fix2 未提交工作树 |
| 被验 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`4180bedd2f9a9566dd880c7e9c760614`**（27,187,520 B），构建于 19:33:22 |
| jar 内的 W7-fix2 常量（逐字节核对，非推断） | `javap -constants`：`MerchantTradeTemplate.TOOL_PER_UNIT = 120l`、`TOOL_MILLI_PER_UNIT_CYCLE = 100l`；常量池含 `NO_MERCHANT_CAPACITY:no-labor` / `no-tool-input` / `no-tool-input-to-host-on-demand-trade-industry` |
| 真世界运行器 | `/tmp/w6acc/w6run.py`（W6/W7d 同一台尺；起服务 → `POST /api/advance` → 抽 checkpoint dump） |
| 运行清单（**受管后台作业，逐个记 PID 于 `logs/<tag>.result.json`**） | `swgen`(10967) 0 天创世档 · `sw360`(10961) 一次 0→360 + 逐日 361→600 + 逐日 601→689 · `sw700`(10962) **从 689 档续跑** →700 · `sw3seg`(10963) 0→120→240→360 · `swtick`(10964) 逐日 0→360 · `tp360`(10965) 一次 0→360 + 逐日 361→660 · `swperf`(10966) INFO 档 0→360 |
| store | `/tmp/w7e/stores/<tag>`（`sw360` 用 `--keep-store` 供 `sw700` 续跑） |
| 日志级别 | `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE -Dsimos.social.logLevel=INFO -Dsimos.app.logLevel=INFO`（`swperf` 例外：全 INFO） |
| 读数窗口口径（★ AGENTS §九.4） | 本节所有"本期"数字都**显式带窗口**（`a:b` = day 闭区间）；**时点**读数 = checkpoint dump；**整轮**读数 = 该事件没有 `day` 字段时只能给全程合计（**逐条标注**） |
| 产物 | `logs/`（世界日志 + `*.result.json`）、`dumps/`（checkpoint JSON）、`results/`（读数 JSON/TXT）、`evidence/`（判别性证据）、`plans/`、`run-worlds.sh`、`w7log.py`、`w7state.py`、`eventsplit.py`、`fingerprint.py`、`mutate.sh` |
| 大文件纪律 | `tp360.log` 3.9 GB 已 `gzip -1` → **161 MB**；`EconomyCycleHealthTest` 的 surefire XML 1.1 GB **跑完即清**（见 §11） |

**复跑命令**（一次一个 Maven，全部经 `tools/mvn-lock.sh`）：

```bash
cd /home/cna/SimulatorMosire
tools/mvn-lock.sh test-compile -q                          # 全 reactor 测试编译（本批：rc=0）
tools/mvn-lock.sh -DskipTests package -pl simos-app -am    # 造被验 jar（md5 4180bedd…）
bash .superpowers/sdd/2026-10-11-w7e-test-batch/run-worlds.sh all      # 7 个世界（本例分模式跑：gen/main/b2/tp/perf/w8）
python3 .superpowers/…/w7log.py   sw360 logs/sw360.log 0:360 361:600 601:689 > results/sw360.readings.json
python3 .superpowers/…/w7log.py   tp360 logs/tp360.log.gz 0:360 361:600 601:660 > results/tp360.readings.json
python3 .superpowers/…/w7state.py dumps/swgen-d0.json dumps/sw360-d360-oneshot.json dumps/sw360-d600.json dumps/sw360-d689.json > results/state-sw.json
python3 .superpowers/…/w7state.py dumps/tp360-d360-oneshot.json dumps/tp360-d660.json > results/state-tp.json
python3 .superpowers/…/fingerprint.py logs/tp360.log.gz 0 240          # 装置 B（指纹对拍）
python3 /tmp/w6acc/canon2.py md5 dumps/sw360-d360-oneshot.json dumps/sw3seg-d360-3seg.json dumps/swtick-d360-tick.json
tools/mvn-lock.sh test -pl simos-app -am                   # 全量测试（本批最终：909 条，见 §4.4）
bash .superpowers/…/mutate.sh                              # 6 轮变异自证（收工 md5sum -c 全 OK）
```

---

## 1. ① 迁移既有测试（旧口径 → 新口径，逐条具名；★ 未放宽任何语义）

**全 reactor `test-compile` 的真实红点只有一个**（W7-fix2 说"app 测试面没查全" ⇒ 本批跑了**全**）：

```
tools/mvn-lock.sh test-compile -q   →  rc=1
[ERROR] …/simos-economy/…/time/MerchantOnDemandTradeW7Test.java:[182,63] cannot find symbol
  symbol: variable CATTLE_PER_UNIT   location: class …model.MerchantTradeTemplate
```

修完这条后全 reactor `test-compile -q` **rc=0**（含 `simos-app` 的 66 个测试类）⇒ **app 测试面没有第二处旧口径编译红**。

### 1.1 `MerchantOnDemandTradeW7Test`（simos-economy，本批改动量最大）

| # | 旧（W7 测试批写的） | 新（本批） | 依据 / 为什么必须这样改 |
|---|---|---|---|
| M1 | `:107 prospectWithoutTradeIndustryAndWithoutCattleIsInfeasibleByName`：断言 `NO_MERCHANT_CAPACITY:no-carrier-asset` | 拆成 **两条各自具名**的负向：`prospectWithoutLaborIsInfeasibleByName`（劳动 0 ⇒ `…:no-labor`）＋ `prospectWithoutToolInputIsInfeasibleByName`（劳动够、账户无 `tool` ⇒ `…:no-tool-input`） | 用户裁定 v3.28 §7；旧理由 `no-carrier-asset` **已从 main 删除**（§一.11）。判据换成两维 ⇒ 只把断言改宽（仍只断 `NO_MERCHANT_CAPACITY` 前缀）会**丢掉"卡在哪一道"的判别力**；★ 夹具对两维分别自证：no-labor 那条的工具**是够的**（账户 12,000 毫），no-tool-input 那条的劳动**是够的**（100,000 毫小时） |
| M2 | `:141 prospectWithSelfSuppliedCattlePassesTheCarrierAssetGate`（自备 CATTLE ⇒ 放行） | `prospectWithLaborAndToolPassesTheInputGate`（劳动 + 工具 ⇒ 放行，理由不再是 `NO_MERCHANT_CAPACITY:*`） | 同上（CATTLE 已不是门槛） |
| M3 | `:173 merchantTradeTemplateIsPureAndCarriesNoStock`：`:182` 断言 `capacityPerUnit` 含 `{CATTLE: CATTLE_PER_UNIT}`（**编译不过**）、`:186` 断言周期投入挂在 `AssetKind.CATTLE` 下 | `merchantTradeTemplateIsPureAndHasNoCattleField`：含 `{TOOL: TOOL_PER_UNIT}`、**不含** `CATTLE` 键、`cycleInputPerUnit` **只有** `TOOL` 一维（值 = `TOOL_MILLI_PER_UNIT_CYCLE`）、`capacityKinds() == [TOOL]` | ★ 这里删掉的是**旧机制本身**（"押 1 头牲口/单位规模"）——§一.11 的理由；新断言**更严**（逐键逐值 + 负向 `doesNotContainKey(CATTLE)`），不是放宽 |
| M4 | `:201` 执行层负向：带不来 **CATTLE** ⇒ 跳过（断言 `reason=no-carrier-asset-to-host-on-demand-trade-industry`、字段 `carriedCattle`） | `migrationWithoutCarriedToolInputIsSkippedByNameAndCreatesNothing`：带不来 **TOOL 份额** ⇒ 跳过（断言 `reason=no-tool-input-to-host-on-demand-trade-industry`）＋**加断言**"旧字段名 `carriedCattle` 不得再出现" | 判据改读 `MerchantTradeTemplate.capacityKinds()`；理由/字段随裁定改名（§一.11 不留旧名）⇒ 旧串是"旧机制的名字"，删；新串 + "旧名绝迹"两条一起断 |
| M5 | `:250 migrationWithSelfSuppliedCattleCreatesTheTemplateAndConservesCattle`：CATTLE 8 → 源留 5 / 目标得 3，ΣCATTLE = 8 | `migrationWithCarriedToolInputCreatesTheTemplateAndConservesTool`：TOOL 份额 **12,000** → 源留 **7,200** / 目标得 **4,800**（⌊12,000×40÷100⌋），ΣTOOL 前后逐值相同；＋断言"长出来的商号 `capacityPerUnit` 只有 `TOOL` 一维"、"目标那份份额挂在 `trade@H2` 下" | 守恒对象随作业资本那一维改变（CATTLE → TOOL）；★ 12,000 是**创世作业资本**的逐值（`EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI`；本类**不引用**它——跨模块不可引用，改由 §2 的守卫钉住） |
| M6 | `:323 fullMoveCarriesAllCattleAndKeepsTheTotalUnchanged` | `fullMoveCarriesAllToolInputAndKeepsTheTotalUnchanged`（整户迁空 ⇒ 12,000 全带走、源 0、Σ 不变） | 同上（第二条取整分支必须单独测，否则会静默存活） |
| M7 | （无） | **新增** `cattleShareDoesNotSatisfyTheToolInputGate`：源户**只有** 8,000 毫 CATTLE 份额 ⇒ 必须与"什么都没有"同命运（整笔跳过 + 具名原因 + 不凭空把 CATTLE 折成 TOOL） | 用户原话「**能不能先把牲口要求取消掉啊**」的**反证用例**：若哪天执行期闸门偷偷退回"数 CATTLE"，本类其它用例（夹具里没有 CATTLE）仍会全绿 ⇒ 这条是唯一能抓住它的 |

### 1.2 其它两处旧口径（逐条具名）

| 文件 | 旧 | 新 | 依据 |
|---|---|---|---|
| `simos-app/src/test/java/io/mosire/simos/app/world/HaulServiceCommoditySeedTest.java:130` | `assertThat(trade.capacityPerUnit()).as("运力资产维：1 头 CATTLE / 1 单位规模").hasSize(1)` | `containsOnlyKeys(AssetKind.TOOL).containsEntry(AssetKind.TOOL, MerchantTradeTemplate.TOOL_PER_UNIT)` | 旧断言只剩"恰一项" ⇒ **对"是哪一维"没有判别力**（把键换成 CATTLE 照样绿）；W7-fix 后世界那一维 = TOOL。★ 描述串也是旧口径，一并改 |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/HaulServiceCommodityAcceptanceTest.java:336-338` | 注释指向"世界 `trade` 那一份 = `{CATTLE:{tool:100}}`" | 注释更正为 `{TOOL:{tool:100}}`（值侧 100 一字未改），并**点明本夹具自己的 `{CATTLE:1}` 是夹具口径**（判据 = "预留 = 声明投入"，与是哪一维资产无关） | 纯注释陈旧（读数面上的"第二个拼写点"），不做语义改动 |

### 1.3 复核过、**不需要改**的（避免"为改而改"）

- `ExpectedProfitBookTest:310/328`：断言是 `startsWith("NO_MERCHANT_CAPACITY")`，夹具走 `no-labor` 或 `no-tool-input` 都满足 ⇒ 实跑绿（见 §4.4 的 909 条）。
- `grep -rn "CATTLE" simos-*/src/test`：其余命中都是**夹具自己**的产业配方（`ProductionEfficiencySettlementTest`、`EconomyRoundTripTest`、`EconomyCodecTest`、`EconomyInvariantsTest`、`HaulServiceCommodityAcceptanceTest` 的本地 fixture）——它们测的是通用资产/产业管线，与"跑商作业资本是哪一维"无关 ⇒ **不动**。
- `HaulGlobalPoolW7Test`（W7 新增，6 条）：W7-fix/fix2 **未破**它；本批实跑 6/6 绿（F1/F2 的读数面断言仍成立）。

---

## 2. ② 跨模块耦合守卫（W7-fix2 点名的那一条）

**新增**：`simos-app/src/test/java/io/mosire/simos/app/world/MerchantTradeScaleAnchorCouplingTest.java`（3 条，全绿）。

| # | 用例 | 断言 | 判据出处 |
|---|---|---|---|
| G1 | `genesisToolEndowmentOverTemplateAnchorIsExactlyOneHundredScaleUnits` | `MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI % TOOL_PER_UNIT == 0` ∧ `… ÷ … == **100**`（城市格 trade 规模锚不许变） | W7-fix2 裁定 E2（"锚保 100"）+ 派单 ② |
| G2 | `bothSidesOfTheCouplingAreStillAtTheirCalibratedValues` | 分子 = **12,000**、分母 = **120**、且锚 **≠** 周期投入（`TOOL_PER_UNIT ≠ TOOL_MILLI_PER_UNIT_CYCLE`） | 只钉比值 ⇒ "两侧同时翻倍"会静默存活；锚 ≠ 周期投入是 W7-fix2 明确保留的区分（退回相等 = 退回 W7-fix 的规模 120） |
| G3 | `everySeededTradeUnitStillStartsWithCapacityScaleOneHundred` | **真播种**（紧凑三国一国 → 真 `EconomySeedHandler` → 真 `EconomyData`）后，逐 `trade@hex`：`capacityPerUnit` 只有 TOOL、值 = 模板锚、该产业下 TOOL 份额合计 = 12,000、`⌊份额 ÷ 锚⌋ == 100` | ★ 不用算术推导，读**落盘后**的世界 ⇒ "模板锚改了但创世没跟上"（或反过来）也会当场红 |

**为什么只能写在 app 的测试里**：两端分属两模块（`simos-app` 的 `EconomySeeder` 常量 ÷ `simos-economy` 的 `MerchantTradeTemplate` 常量），
而 `simos-economy/pom.xml` 的 `bannedDependencies` 禁 `simos-app` ⇒ economy 侧只能**字面量复述**分子（`TOOL_PER_UNIT = 12_000L / 100L`），
那份复述没有编译期护栏。★ 反向也成立：**economy 的 14 条测试对锚值没有判别力**（见 §6 的 M1：锚改坏后它们 14/14 全绿）⇒ 这条守卫是唯一的口。

**变异自证（判别力当场证明，两轮都真红）**：

| 轮 | 变异体 | 结果 | 红在哪 |
|---|---|---|---|
| **M1** | `MerchantTradeTemplate.TOOL_PER_UNIT`：`12_000L/100L` → `12_000L/120L`（=100，即"锚 = 一周期工具投入"） | guard **3/3 红**；economy 那两类 **14/14 仍绿** | `expected: 100L but was: 120L`（G1/G3）+ G2 绝对值 |
| **M2** | `EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI`：`12L` → `11L`（=11,000） | guard **3/3 红** | `expected: 100L but was: 91L`（含**真播种世界**那条） |

---

## 3. ③ 指纹重基线（`simos-app/…/time/EconomyCycleHealthTest`，旧 → 新具名）

W7 系列改了运力分配/运费定价/可行性判据 ⇒ 逐轮成交分布必变。本批**按实跑真值**重钉（**只换期望值**）：

```
W7 旧指纹（W7d 钉的；本批实跑复现的第一行红，与本文件原先钉的值逐值相同）
  {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 42=3, 43=2, 44=10,
   45=8, 46=5, 47=10, 48=3, 49=1, 50=1, 53=1, 55=1, 56=1, 66=1, 151=1, 198=1}
  min/中位/max = 15 / 42.0 / 198，轮数 96，零成交轮 0，Σfills = 3648
W7e 新指纹（本批实跑真值）
  {15=1, 21=11, 22=8, 23=4, 24=6, 25=5, 26=10, 30=1, 41=1, 42=3, 43=2, 44=10,
   45=8, 46=5, 47=10, 48=3, 49=1, 52=1, 53=1, 55=1, 57=1, 66=1, 151=1, 198=1}
  min/中位/max = 15 / 42.0 / 198（三项都没变），轮数 96，零成交轮 0，Σfills = 3651（Δ = +3）
逐档差异（旧 → 新）：**只有两档动** —— 50×1 → 52×1（某一轮 +2 笔）、56×1 → 57×1（某一轮 +1 笔）
```

**改了哪两处（逐字节可核）**：`baselineFills()` 的两档计数（`50L/56L` → `52L/57L`）＋ 类注释新增「W7e」一节。
★ **五条牙一字未动**（`BASELINE_MARKET_ROUNDS = 96` / 同日不得两轮 / 相邻间隔 ≤ 5 天 / **零成交轮次 = 0** / `Σ逐轮 fills == MARKET_FILL 行数`）——
`assertEveryMarketRoundTraded` 的断言体**一个字符没改**，`git diff` 可核。

**依据（为什么 +3 笔）**：① 可行性判据 `CATTLE → 劳动 + 工具`（候选集与拒绝理由变 ⇒ 迁移/主业排序输入变 ⇒ 市场参与者变）；
② 作业资本那一维 `CATTLE → TOOL`（资产占用/租赁/随迁口径变）而**规模锚刻意保持 100**（W7-fix2）⇒ 产出与周期投入逐值不变、变的只是"押住规模的是哪一维资产"；
③ F1（归因改读全池）与 ④ F2（两条理由合并到唯一拼写点）都是**只读**面 ⇒ 如实记：**它们不是这次 +3 笔的原因**。

**两套独立装置逐值对拍（AGENTS §三.5）**：

| 装置 | 装置形态 | 轮数 | 零成交轮 | 同日两轮 | 相邻最大间隔 | min/中位/max | Σfills == MARKET_FILL | 分布 |
|---|---|---|---|---|---|---|---|---|
| **A** | JUnit 进程内真 `Shell` + `AppLogCapture`（`tests run 1, failures 0`） | 96 | 0 | —— | ≤5（测试内断） | 15/42.0/198 | 3651 == 3651 | 24 档 = 上面新指纹 |
| **B** | 真世界 HTTP：`tp360` 创世新播、0→360（再逐日到 660），窗口 day 0–240 | 96 | 0 | 否 | 3 天 | 15/42.0/198 | 3651 == 3651 | 24 档**逐值相同** |

⇒ 两套装置（进程内 vs 真 HTTP + 独立 store + 独立 jar md5 记录）给出**同一份指纹** ⇒ 不是产物陈旧、不是非确定性。
读数：`results/tp360.fingerprint-0-240.txt`；装置 A 的五条牙读数行落 `simos-app/target/economy-cycle-invariant/summary.txt`：
`轮次=96 零成交轮次=0 min/中位/max=15/42.0/198 Σfills=3651（== MARKET_FILL 3651 行）`。

---

## 4. ④ 真读数（本批核心；每条都带窗口/时点标注）

### 4.1 非城市格有没有长出跑商 —— **没有，一户都没有**（两世界一致）

**计数（本期）**

| 事件 | small-world 0→689 | three-powers 0→660 | 健康世界（装置 A，tp 240 天） |
|---|---|---|---|
| `MERCHANT_TRADE_INDUSTRY_CREATED` | **0** | **0** | **0** |
| `ONDEMAND_TRADE_MIGRATION_SKIPPED` | **0** | **0** | **0** |
| `MIGRATION_MOVE targetMode=merchant` | **26**（0:360 = 18 / 361:600 = 8 / 601:689 = 0） | **60**（0:360 = 30 / 361:600 = 24 / 601:660 = 6） | 5（W7d 口径，本批未重取） |
| `MODE_MIGRATION_PROSPECT_INFEASIBLE` | **9,547**（全部落在 0:360） | **21,443**（全部落在 0:360） | —— |

**逐户明细（26 / 60 笔 merchant 迁移的落点）** —— ★ 全部迁往**已经有 `trade` 模板的城市格**，即"格内换人/并入既有商号"，**不是**"非城市格长出跑商"：

| 世界 | 逐笔（`logs/…-merchant-moves.txt`） | 去重落点 |
|---|---|---|
| small-world | 26 笔全部 `hh-0_0-urban-middle_peasant → hh-0_0-urban-landlord @0,0`（day 120/150/…/480，每轮 2 笔，population 1） | `targetHex=0,0` × 26（0,0 是**城市格**，创世就有 `trade@0_0`） |
| three-powers | 60 笔，落点 = `-3,3` / `0,-3` / `3,0`（day 240/270/300…） | 三格**都是城市格**（创世各有 `trade@-3_3` / `trade@0_-3` / `trade@3_0`） |

**★ 卡在哪一道（`NO_MERCHANT_CAPACITY:*` 的逐理由计数分布，本期 0:360）**

| 世界 | `no-labor` | `no-tool-input` | 其它 | 合计 | W7d 对照（旧口径） |
|---|---|---|---|---|---|
| small-world | **8,844**（92.6%） | **703**（7.4%） | 0 | 9,547 | 9,927 = `no-carrier-asset` 9,581 + `no-capacity` 346 |
| three-powers | **17,610**（82.1%） | **3,833**（17.9%） | 0 | 21,443 | 22,368 = 22,076 + 292 |

- ★ **卡在"劳动"那一道（第一道），不是"工具"那一道**：绝对多数失败户**根本没有劳动**（人口 0 的壳户 + 劳动 0 的城镇地主户）。
- **执行期闸门一次都没触发**（`ONDEMAND_TRADE_MIGRATION_SKIPPED` = 0）：因为**没有任何一笔按需迁移走到执行期** —— 决策层已经把它们全部挡下。这解释了为什么"带不来作业资本 ⇒ 跳过"这条负向在真世界里读数为 0。
- ★★ **W7-fix2 记的遗留门槛 E3 被实测证实、并给出更强的读数**：作业资本份额**只有"商号本金主"持有** ——
  - small-world 创世档（`swgen-d0`，时点）：38 个 merchant 家户 / 19 格中，**只有 2 户**持 TOOL 份额，各 **12,000 毫**（`hh-0_0-urban-landlord` @0_0、`hh-0_2-urban-landlord` @0_2）；
  - three-powers d360（时点）：74 个 merchant 家户中 **5 户**持 TOOL 份额（11,134 / 6,774 / 79 / 11,062 / **1** 毫）——★ 其中两个大户 `hh--3_3-urban-landlord`（11,134 毫）与 `hh-3_0-urban-landlord`（11,062 毫）的 `laborMilli = **0**` ⇒ 它们**卡在劳动维**（`no-labor`），**不是**卡在工具维。
- ★ **未取到的读数（如实记）**：`MIGRATION_MOVE` 行**不带"随迁了多少作业资本"字段**（只有 day/source/target/targetHex/targetMode/population/moneyByCurrency/debtMilli/speedPerMille/reason）⇒ "每一笔带了多少作业资本"**无法从日志逐笔读出**，只能给时点份额前后对照（§4.5）。

### 4.2 粮有没有运出去（改名后的两条理由 + F1 归因 + 未服务运力量；与 W7d 同窗口对照）

**① `LOGISTICS_SERVICE_SUPPLY_REJECTED`（货侧，**有 `day` 字段**，可按窗口）—— 本期**

| 世界 | 窗口 | 笔数 | Σ`truncatedMilli` | 理由分布 | W7d 同窗口对照 |
|---|---|---|---|---|---|
| small-world | 0:360（144 轮） | **11,777** | **61,441,260** | 100% `no-carrier-with-deliverable-service` | 11,871 / 61,432,735 |
| small-world | 361:600（96 轮） | **1,774** | **438,455** | 同上 | 1,675 / 398,077 |
| small-world | 601:689（35 轮） | **0** | **0** | —— | 0 / 0 |
| three-powers | 0:360（144 轮） | **1,425,041** | **7,549,473,894** | 同上 | 1,422,094 / 7,549,296,662 |
| three-powers | 361:600（96 轮） | **0** | **0** | —— | **216,754 / 5,984,446** ★ 见下 |
| three-powers | 601:660（24 轮） | **0** | **0** | —— | 0 / 0 |

★ **tp 361–600 从 21.7 万笔掉到 0**：本批用**独立第二遍直读 gz**复核过（不经过 `w7log.py`）——`LOGISTICS_SERVICE_SUPPLY_REJECTED` 在全日志里只有 `day ≤ 360` 的 1,425,041 行；
同一份日志在 361–660 里 `MARKET_REPORT`（96+24 轮）、`MARKET_FILL`（3,420+848 笔）、`HAUL_PRICE_SIGNAL`（各市场 264 行）都在 ⇒ **不是日志被截**。
**原因未验证**（W7d 对 sw 后窗归零也留了同一条"未验证"，成因同族：跨格价差/派生需求归零或价格崩塌）。

**② F1 的 `out-of-derived-radius`（W7-fix 的新归因；读数面）**

| 世界 | 落在哪个事件 | 笔数 | Σ（毫） |
|---|---|---|---|
| small-world | `MERCHANT_CAPACITY_LANE_TRUNCATED` | **2,672** | 3,319,569 |
| small-world | `CAPACITY_DEMAND_GAP_WHY`（`reason=capacity-budget-below-demand-or-out-of-derived-radius`） | 562（0:360 = 300 / 361:600 = 192 / 601:689 = 70） | —— |
| three-powers | `MERCHANT_CAPACITY_LANE_TRUNCATED` | **1,066,313** | 210,945,983 |
| three-powers | 同上，`out-of-derived-radius+sub-unit-residual` | 394 | 53,243 |
| 两世界 | `no-merchant-household-in-shipping-hex`（F1 要消掉的那条误报） | **0** | 0 |

**③ `MERCHANT_CAPACITY_LANE_TRUNCATED` 的整轮读数（★ 该事件**没有 `day` 字段**，只能给全程合计）**

| 世界 | 窗口 | 笔数 | Σ`unallocatedMilli` | 理由分布 | W7d 对照（其窗口标注有误，见 §9） |
|---|---|---|---|---|---|
| small-world | **整轮 0→689** | **16,314** | **65,293,776** | `no-carrier-with-deliverable-service` 13,551 / 61,879,715；`out-of-derived-radius` 2,672 / 3,319,569；`capacity-exhausted` 91 / 94,492 | 16,141 / 65,142,802 |
| three-powers | **整轮 0→660** | **2,507,410** | **7,854,589,006** | `no-carrier…` 1,425,041 / 7,549,473,894；`out-of-derived-radius` 1,066,313 / 210,945,983；`capacity-exhausted` 15,662 / 94,115,886；`out-of-derived-radius+sub-unit-residual` 394 / 53,243 | 2,586,245 / 7,856,959,786 |

**④ 成交笔数 / 成交额（本期，按窗口；成交额与运费**按币分列、不跨币相加**）**

| 世界 | 窗口 | 轮数 | 成交笔数 | 零成交轮 | min–max | 成交额 | 运费实收 |
|---|---|---|---|---|---|---|---|
| small-world | 0:360 | 144 | **1,753**（W7d 1,746） | 0 | 3–160 | silver **109,099**（W7d 109,107） | silver 37,113（W7d 37,278） |
| small-world | 361:600 | 96 | **1,259**（W7d 1,333） | 0 | 7–317 | silver 2,585（W7d 2,448） | silver 25,629（W7d 27,798） |
| small-world | 601:689 | 35 | **296**（W7d 295） | 0 | 8–13 | silver 0 | silver 83 |
| three-powers | 0:360 | 144 | **5,584**（W7d 5,583） | 0 | 15–198 | copper 94,094 + gold 91,796 + silver 107,433（W7d 94,094 + 91,796 + 107,434） | copper 13,343 + gold 27,703 + silver 23,661 |
| three-powers | 361:600 | 96 | **3,420**（W7d 3,405） | 0 | 28–46 | 三币均 0 | copper 717 + silver 9,827 |
| three-powers | 601:660 | 24 | **848**（W7d 849） | 0 | 34–37 | 三币均 0 | 三币均 0 |

★ **"是否上移"的可比读数（同装置同窗口）**：**成交笔数基本持平、略上移** —— 装置 B 的 day 0–240（96 轮）**3,648 → 3,651（+3，+0.08%）**；
同窗口成交额（silver）109,107 → 109,099（**−8**）⇒ **没有上移**（如实记）。后窗成交额为 0 是"价格崩塌到 0 的白送语义"（W2 口径），不是"没有成交"。

**⑤ `haul` 牌价逐轮轨迹（缺口升、充足降；两个方向都实测到）**

| 世界/市场 | 首 / 末 | 最小 / 最大 | 升 / 降 | 缺口期（S=0） | 充足期 |
|---|---|---|---|---|---|
| sw `single-region` | 5 → **958**（W7d 5→1030） | 5 / **6,014**（W7d 6,482） | **192 / 80**（W7d 197/77） | day3 `D=8,405,466 S=0 α=50‰ 4→5 providers=0`；day5 `5→6`；day8 `6→7`；day10 `7→8`；day13 `8→9` | day675 `D=1,753 S=92,105 1300→1235`；day678 `→1174`；day680 `→1116`；day683 `→1061`（providers=1） |
| tp `c-tp-copper` | 5 → **237,097**（W7d 109,604） | 5 / 237,097 | 241 / 22 | day3 `D=215,979,229 S=0 4→5` | day648 `D=8,731 S=6,030 →212,455` |
| tp `c-tp-gold` | 5 → **2,128,784**（W7d 同值） | 5 / 2,128,784 | 263 / **0**（全程无可交付服务 ⇒ 只涨不落，如实记） | day3 `D=…` S=0 | —— |
| tp `c-tp-silver` | 5 → **640,578**（W7d 60,448） | 5 / 640,578 | 251 / 12 | —— | —— |

### 4.3 两把尺：`hh-0_2-urban-landlord` 一类的前后理由对照

| 世界 | W7d（旧） | W7e（本批） |
|---|---|---|
| small-world | `PRIMARY_MODE_RANKING_EXCLUDED` **37 行**：`hh-0_2-urban-landlord` **10× `no-deliverable-service-capacity`** + **3× `no-bargaining-power`**（@0_2）；`hh-0_2-rural-middle_peasant` 22× `no-bargaining-power`；`hh-0_2-urban-rich_peasant` 2× `no-deliverable-service-capacity` | **0 行**（`no-bargaining-power` **消失**、`no-deliverable-service-capacity` 也 0） |
| three-powers | （W7d 未给 tp 的该表） | **39 行**：`no-bargaining-power` 36（`hh--1_-2-rural-landlord` 25、`hh--3_3-urban-landlord` 6、`hh-3_0-urban-landlord` 5）＋ `no-deliverable-service-capacity` 3（全在 601:660） |

**★ sw 里 `no-bargaining-power` 为什么消失（机制 + 逐户证据，不是"被删了"）**：
W7-fix 把"劳动 + 工具"的门槛挪到**候选评估的最前面**（`ExpectedProfitBook.merchantProspect`）⇒ 过去"**能进排序表、再被排序输入挡下**"的那些户，
现在**根本进不了候选**，理由是**更具体的事实**而不是推论。逐户证据（同一条链的两端）：

```
W7d：household=hh-0_2-urban-landlord modeId=merchant hex=0_2 reason=no-deliverable-service-capacity ×10
      household=hh-0_2-urban-landlord modeId=merchant hex=0_2 reason=no-bargaining-power            ×3
W7e：household=hh-0_2-urban-landlord modeId=merchant  →  只出现在 MODE_MIGRATION_PROSPECT_INFEASIBLE：
      hex=1_1 / 0_1 / -1_2 各 3× reason=NO_MERCHANT_CAPACITY:no-tool-input ＋ 各 1× …:no-labor
```

**★ 新理由没有变成死代码**：`no-deliverable-service-capacity` 在 tp 仍发了 **3** 次 —— 它来自**当前主业就是跑商**的那条表（`ModeMigrationPolicy.rankingTable` 对 `current.modeId == merchant` 的**当前行**也算 `merchantGateReason`，**不经过**新的输入门槛）：
`hh-3_0-urban-landlord` / `hh-3_0-urban-rich_peasant` @601:660。⇒ 口径：新理由的**可达路径收窄为"当前主业跑商、但当轮拿不出可交付运力"**，不再是"新候选的通用门槛"。

### 4.4 不回归（逐条给读数）

| 判据 | 读数 | 装置/命令 |
|---|---|---|
| **B2 分段等价** | 三形态 day-360 状态规范 md5 **全等** `28a2e4779f5e528b09c6418fac5959c3`；**差异叶 0 / 总叶 75,570**；各模块 md5 也逐模块全等 | `canon2.py`（`sw360` / `sw3seg` / `swtick` 三份 dump），`evidence/canon-b2-all.txt` |
| **Revision Δ** | 一次 `0→360` ⇒ **Δ1**（`swperf` revisionDelta=1）；三段 ⇒ **3**；逐日 ⇒ **360**；`sw360` 全程（一次 + 逐日到 689）⇒ 330 | 各 `logs/<tag>.result.json` |
| **性能（单次墙钟）** | `0→360` 一次 = **6.658 s**（`swperf`，INFO；W7d 6.298 s ⇒ +5.7%；W6 验收改前 6.738 s ⇒ **−1.2%**）；DEBUG+TRACE 轮 = **8.311 s**（W7d 8.260 s）★ 两档分列，不混比 | `logs/swperf.result.json` / `logs/sw360.result.json` |
| **跨进程确定性（附加）** | `swperf`（INFO）与 `sw360`（DEBUG+TRACE）的 day-360 **dump 文件 md5 逐字节相同** `c5934128bd959f9852865a60c0b268b5` | `md5sum dumps/swperf-d360-perf.json dumps/sw360-d360-oneshot.json` |
| **W4 排序禁自指** | W7 系列 diff **不含**任何排序/区序文件（`git status` 里 grep `Order/order` 只命中设计文档）＋ B2 三形态状态 md5 全等（排序若是内容以外的东西，分段必不等） | `git status --porcelain` + B2 ★ **未复跑** W4 验收的 309/309 窗口吻合实验 |
| **W5 铸币** | `MINT_PRODUCTION` 累计：day360 = **1,500 毫** → day600/689/700 = **2,500 毫**；逐币种货币守恒差 **0/0**；`MintProductionSettlementTest` 在全量轮里绿 | `w7state.py` + §4.4 全量测试 |
| **W5.5 货币市场** | `MONEY_MARKET_ROUND` **275** 行 / `MONEY_MARKET_RATE_WRITTEN` **275** 行（sw 0→689）；`sw700` 续跑窗口 **5 / 5**；`tp360` **264 / 247**；`MoneyMarketSettlementTest` 绿 | `grep -c` + 全量测试 |
| **W8 溢出不致命** | 从 day-689 档**重启服务后续跑**到 day 700：11 次逐日推进全部成功（rev **331 → 342**，tick 689 → 700）⇒ 改前"day 690 溢出即墙"**已不存在** | `sw700` 运行（`logs/sw700.result.json`） |
| **W8 空户不计息** | sw：14 份"人口 0 且未结清"合同本金 **1,043,790 毫 → 1,043,790 毫**（day689→700 逐值不变），同期全世界本金 **560,453,777,942,014,901 → 1,079,682,931,648,527,064**（仍涨）；tp：2 份空户合同 **60,591 毫 → 60,591 毫**（day360→660，300 天逐值不变），同期全世界本金 219,570,460 → 1,510,280,530,079；`DEBT_INTEREST_EXTINCT_HOUSEHOLD_SKIPPED` sw700 窗口 **13** 行 | `w7state.py` + `grep` |
| **W8 户绝销账** | ★ **世界级未观察到**：`HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF` 在 sw700 窗口 = **0** 行（该 11 天里没有"带债户绝"）；语义由单测 `DeathDebtWriteOffTest` **3/3 绿**覆盖（全量轮） | `grep` + 全量测试 |
| **全量测试（最终整轮，重基线之后）** | `tools/mvn-lock.sh test -pl simos-app -am` ⇒ **BUILD SUCCESS**：15 个模块 / 411 个测试类 / **3,445 条：Failures 0、Errors 0、Skipped 5**（simos-economy **316**、simos-app **909**、simos-social 217、simos-unit 518、simos-map 379、simos-sd 232、simos-core 231、simos-util 217、simos-actor 130、simos-gov 111、simos-army 91、simos-economy-api 50、simos-calendar 33、simos-actor-api 8、simos-social-api 3）；★ 本轮之前那次同命令的读数是 **909 条里 1 红 = 指纹**（即重基线前），重基线后同一命令全绿 | surefire 报告（411 份，mtime 全落在最终轮） |

### 4.5 守恒（时点读数 + 发行式）

| 项 | small-world d0 / d360 / d600 / d689 / d700 | three-powers d360 / d660 | 判定 |
|---|---|---|---|
| **作业资本（`AssetKind.TOOL` 份额合计，毫工具）** | **24,192 / 24,192 / 24,192 / 24,192 / 24,192** | **36,262 / 36,262** | ✅ 逐时点**逐值不变**（份额行数 80 / 72 / 73 / 73 / 73 —— 行数变是**合并/拆分**，合计不变） |
| 其构成（创世，时点） | 2 × **12,000**（`trade@0_0`、`trade@0_2` 的商号本金主）+ 192（其它产业） | 3 × **12,000**（三个城市格）+ 262 | —— |
| 份额归属变化（**不是泄漏**） | `trade@0_2` 的 12,000：d0 属 `hh-0_2-urban-landlord` → d360 属 `hh-0_2-urban-middle_peasant`（同格商人并入）；d600 起被部分拆分（11,520 / 11,240…），合计仍 24,192 | —— | ✅ 守恒式的对象是**合计**；逐产业/逐户金额随迁移进出 |
| **CATTLE 总量（份额合计）** | **0 / 0 / 0 / 0 / 0**（份额行数 0） | **0 / 0** | ★ 见下 |
| **货币（逐币种，账户余额合计）** | copper **100,000**；silver 267,200 → **268,700 → 269,700**（MINT_PRODUCTION 1,500 → 2,500） | copper 136,600 / gold 129,400 / silver 424,600（d360 与 d660 逐值相同；tp 无铸币产业） | ✅ |
| **货币守恒差（Σ账户 − Σ发行，逐币种）** | **0 / 0**（每个时点都 0/0） | **0 / 0 / 0**（两个时点都 0/0/0） | ✅ |
| 对照：工具**商品账**（会产出/消耗，**不是**守恒量） | 504,000 → 508,116 → 463,716 → 447,011 → 447,011 | —— | 只作上下文，不当守恒律用 |
| 人口（Social 权威）/ 债务本金 | 4,800 / 3,872 / 3,299 / 3,103 / 3,067；债务 0 → 5.60e17 → 1.08e18（W6 D1"利率无上界"已知开放项，**不是本批回归**） | 5,019 / 4,022；债务 2.20e8 → 1.51e12 | —— |

**★ CATTLE 现在还有没有判定作用（逐条回代码核 + 读数）**：

1. **真世界读数**：sw 与 tp 的**每一个**时点档里 CATTLE 份额合计 = **0**、份额行数 = **0** ⇒ 世界数据层面它**不存在**（W7d 的旧世界是 200 / 300 头）。
2. **跑商配方/判据**：`MerchantTradeTemplate` 里**一个 CATTLE 字段都没有**（`capacityPerUnit = {TOOL: TOOL_PER_UNIT}`、`capacityKinds() = {TOOL}`、`cycleInputPerUnit` 外层键 = `TOOL`）；`ExpectedProfitBook` 的可行性判据 = 劳动 + 工具，**不含 CATTLE**。
3. **它还出现在哪些算式里（如实列举，均为通用机制、在 CATTLE=0 时恒为惰性）**：
   `ModeMigrationSettlement.isMobileAsset`（`CATTLE/SHIP/MACHINE/TOOL` 可移动 —— 通用资产分类）；
   `ExpectedProfitBook.shipCattleUpkeepOf` + `CATTLE_UPKEEP_PER_UNIT_MILLI = 10`（船舶/牲口维护费，只有持 CATTLE/SHIP 份额才非 0）；
   `EconomySettlement` 的饲料口径常量（V7 起**取值 0**，"没有牲口就没有饲料口径"）；
   `EconomySeeder.PRODUCTION_RUNTIME_ASSET_KINDS`（`LAND/TOOL/WORKSHOP/SHIP/CATTLE` —— 给全部资产种类播**资产规则**，与份额无关）；
   `EconomyAdjustTool` 的枚举文档串；若干注释。
   ⇒ **结论**：CATTLE 在"跑商"这条链上**零判定作用**；在世界数据里**零存量**；只在**通用资产机制**里作为枚举成员/维护费科目存在（惰性）。

---

## 5. ⑤ 新用例清单与判据出处

| 用例（类#方法） | 判据出处（用户原话 / 设计书 / 派单） | 断言要点 | 实跑 |
|---|---|---|---|
| `MerchantTradeScaleAnchorCouplingTest` G1/G2/G3 | W7-fix2 裁定 E2（锚保 100）+ 派单 ②「唯一耦合点，要带判别力」 | 比值 100 + 整除 + 两侧绝对值 + 真播种世界规模 100 | ✅ 3/3 |
| `MerchantOnDemandTradeW7Test` × 8（§1.1 的 M1–M7） | 用户原话「跑商不是生产方式吗…」「**能不能先把牲口要求取消掉啊？…算作工具需求得了**」；设计书 v3.28 §7；W7 派单 ③⑤；§6.3 判据 1「守恒式」+ 判据 3 负向 | 两维各自具名的负向 + 正向闸 + 模板无 CATTLE + 执行期跳过具名 + TOOL 守恒（部分/整户两支）+ CATTLE 不再顶用 | ✅ 8/8 |
| `HaulGlobalPoolW7Test` × 6（W7 新增，本批未改） | 用户原话「运力提供不应该也是全市场区共享的吗」；v3.26；W7 派单 ①④⑥ | 全池共享 / 行程闸 / 口径闸 / 两把尺 / 既有理由保留 / 改名不混淆 | ✅ 6/6（F1/F2 后仍绿） |
| `HaulServiceCommoditySeedTest`（③ 改严 1 条） | T-H1（haul 进词表/价格表/配方）+ §一.11 | 真播种的 `trade@hex` 作业资本那一维 = TOOL 且值 = 模板锚 | ✅ |

---

## 6. ⑥ 变异自证记录（改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5）

**纪律**：变异体只**临时**改 `src/main`（4 个文件），`mutate.sh` 每轮从 pristine 副本还原并 `md5sum -c`；
**收工 `md5sum -c` 四条全 OK，且当前工作树这 4 个文件的 md5 与 pristine 逐字节相同**（见下方 md5 值）。
★ 每轮先删上一轮的 surefire 报告（第一遍实测踩到"reactor 在 economy 就停 ⇒ 打印出上一轮 app 报告"的**假证据**，已修）。

| 轮 | 关键项 | 变异体（**全部已还原**） | 变异体 md5 | 结果 | 红在哪一行 |
|---|---|---|---|---|---|
| **M1** | 跨模块耦合守卫（economy 侧锚） | `MerchantTradeTemplate.TOOL_PER_UNIT`：`12_000L/100L` → `12_000L/120L` | `322295c6e4af8c35645d7eaf269be9c5` | guard **3/3 红**；economy 14/14 仍绿 | `expected: 100L but was: 120L` |
| **M2** | 跨模块耦合守卫（app 侧禀赋） | `EconomySeeder.MERCHANT_GENESIS_TOOL_PER_HOUSEHOLD_MILLI`：`12L` → `11L` | `7ca709d861d2a2365df1e49a435d512f` | guard **3/3 红** | `expected: 100L but was: 91L`（含真播种世界那条） |
| **M3** | 新判据的**工具维** | `ExpectedProfitBook.merchantInputFailureReason`：`if (toolMilli <= 0L)` → `if (false)` | `c050950f209a966babe4d84e15332881` | `MerchantOnDemandTradeW7Test` **1/8 红** | `prospectWithoutToolInputIsInfeasibleByName` |
| **M4** | 作业资本 = TOOL | `MerchantTradeTemplate.capacityKinds()`：`List.of(TOOL)` → `List.of(CATTLE)` | `9c5d124048072c1336f5efa7bcd00b24` | **4/8 红** | `cattleShareDoesNotSatisfyTheToolInputGate` / `migrationWithCarriedToolInput…` / `merchantTradeTemplateIsPureAndHasNoCattleField` / `fullMoveCarriesAllToolInput…` |
| **M5** | 具名理由改名 | `ModeMigrationSettlement`：`no-tool-input-to-host-on-demand-trade-industry` → 旧名 `no-carrier-asset-…` | `8ce3b161f48d20b20c8870475db3f187` | **2/8 红** | `cattleShareDoesNotSatisfyTheToolInputGate` / `migrationWithoutCarriedToolInputIsSkipped…` |
| **M6** | 静默付 0（拒绝不再具名可读） | `ONDEMAND_TRADE_MIGRATION_SKIPPED` 的 `.info(` → `.trace(` | `ba81c9f57238959eecb39cbb20653207` | **2/8 红** | 同上两条（"必须有一条具名 INFO"那条断言） |

**收工核验（md5 与工作树状态）**：

```
simos-economy/…/model/MerchantTradeTemplate.java          6452cee5ad01d84473c01d117574fd8c   OK
simos-economy/…/time/ExpectedProfitBook.java              8e7439d2491dbab48b7cc423dc72c2b2   OK
simos-economy/…/time/ModeMigrationSettlement.java         5ec8c5ac2ade6c4916323988c0428085   OK
simos-app/…/world/EconomySeeder.java                      dd9c4f672681e6c71e6e932ebcffce8c   OK
（`git status -- '*/src/main/*'` = W7 系列的既有 12 项，无新增/无回退）
```

**等价存活 / 未自证（如实写）**：M1 显示 **economy 侧 14 条测试对规模锚没有判别力**（这是设计：锚的守卫在 app 侧 G1–G3）；
"断粮/口粮"（W3/W5.5 面）与"静默付 0"的字面面（借钱不补进订单预算）W7 系列未改，本批**未**对它们做变异（W5.5 测试批已做）。

---

## 7. ⑦ 我没做 / 没验证的（逐条，别读成"通过"）

1. **`clean verify` 未跑**（纪律：归控制方门禁）；`spotless:check` / `spotbugs` 未单独跑（本批只跑过 `spotless:apply`，rc=0，且未顺带动别的文件）。
2. **W4 验收的 309/309 窗口吻合实验未复跑**；只做了"改動面不含排序文件 + B2 状态 md5 全等"两条间接证据。
3. **W8 的"户绝销账"在世界级未观察到**（`HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF` = 0 行；该窗口没有带债户绝事件）⇒ 只有单测覆盖，没有真世界读数。
4. **每笔 merchant 迁移"带了多少作业资本"读不到**（`MIGRATION_MOVE` 行没有该字段）⇒ 只给了时点份额对照。
5. **tp 的 `LOGISTICS_SERVICE_SUPPLY_REJECTED` 在 361–600 归零的原因未验证**（只证实了"不是日志被截"：同窗口 `MARKET_REPORT` / `MARKET_FILL` / `HAUL_PRICE_SIGNAL` 都在）。
6. **`-Dsimos.*` 系统属性轮未跑**：本批用 `JAVA_TOOL_OPTIONS` 传（与 W6/W7d 同一形态）；`log4j2.xml` 的系统属性路径未单独验证。
7. **真世界的 `out-of-derived-radius` 未做归因对拍**（没有"改前同世界"的对照跑；F1 的"改前会报 `no-merchant-household-in-shipping-hex`"只有单测层的判别力证据）。
8. **装置 A（`EconomyCycleHealthTest`）本批只跑了 2 次**（1 次旧基线红 + 1 次新基线绿；+1 次最终全量轮）——史上该形态的稳定性证据来自 W5.5-fix3（56 次）与 W7d（装置 A/B 各 1 次），本批**没有**做 3+ 次重复采样。
9. **`tp360` 没有 `result.json`**：装置收尾把 3.9 GB 日志整份读进内存做 `log-stripped` 时被 **SIGTERM/OOM 杀**（与 W7d 记的 F4 同款缺陷）⇒ **tp 的墙钟/`revisionDelta` 读数缺失**（世界数据、dump、日志完好；读数我已流式取全）。**未修**（装置在 `/tmp/w6acc`，不在交付面）。
10. **未做 `simos-social` / `simos-unit` / `simos-sd` 的单独复跑**：它们不在 W7 改动面内；全量轮（`-pl simos-app -am`）已把它们的测试都跑了一遍（全绿）。
11. **注释陈旧未改**（main，只报不改）：见 §9。
12. **health 世界的 `PRIMARY_MODE_RANKING_EXCLUDED`（装置 A）未单独统计**（装置 A 的日志捕获没有落盘口径）；本批的两把尺读数全部来自真世界日志（sw/tp）。

---

## 8. ⑧ 未完成 / BLOCKED

- **无 BLOCKED 项**（没有设计冲突、没有需要用户裁定的开放点；main 的 bug 未发现）。
- 交接给下一批的**开放项（本批不实现，如实记）**：
  1. **E3 遗留门槛**（W7-fix2 记）：只有"商号本金主"持作业资本份额 ⇒ 非城市格/其它城镇跑商户**在决策层就被 `no-labor`/`no-tool-input` 挡下**（本批给全了逐理由计数）。候选方案（未选、未做）：给每个城镇 merchant 户播 TOOL 份额 / 让按需商号规模直接由该户自己的工具与劳动派生。
  2. **F3**（W7d 记，本批复核仍成立）：`HaulService.NO_DELIVERABLE_SERVICE_GOODS_REASON`（货侧那条名字）在 main **没有发射点**，真世界 0 次；F2 只做到"两条名字不同值 + 池侧引用它"。
  3. **W6 D1**：债务本金无上界（本批读数 5.60e17 → 1.08e18），与 W7 系列无关。
  4. **装置缺陷**：`w6run.py` 收尾的 `log-stripped` 会把整份日志读进内存（3.9 GB ⇒ OOM）——建议下一批改为流式 md5。

---

## 9. ⑨ 装置修正 + 只报不改（本批的"发现但没动"）

**① 装置修正（本批做的，属测试/装置面）**：`w7log.py` 的 `MERCHANT_CAPACITY_LANE_TRUNCATED` **没有 `day` 字段**，
W7d 版用 `num(f.get("day"))`（缺省 0）⇒ 把**全程合计**误标成窗口 `"0:360"`（W7d 账本里那两行的窗口标注因此是错的）。
本批改为"无 `day` ⇒ `no-day(整轮)`"并**加逐理由拆分**，W7d 的数也按同一工具重算过（16,141 是全程值，不是 0:360 窗口值）。
★ 其余窗口（`LOGISTICS_SERVICE_SUPPLY_REJECTED`、`MARKET_REPORT`、`MIGRATION_MOVE` 等有 `day` 的事件）标注不受影响。

**② `simos-app/src/main/.../EconomySeeder.java` 的注释陈旧（★ 本批不许改 main ⇒ 只报不改，行号为本批实测）**：

| 行 | 现文（陈旧） | 与代码实际不符之处 |
|---|---|---|
| `:278-283` | 「规模锚 = `trade.capacityPerUnit = {TOOL: TOOL_PER_UNIT}`**(100)** … ⇒ 规模上界 **120 单位** … 每 1 单位规模·周期要 100 毫工具 ⇒ 12,000 毫工具恰好支撑 **120 单位规模**一个周期 ⇒ 该格每周期工具投入 = 120 × 100 = **12,000 毫工具**（…自洽）」 | W7-fix2 后 `TOOL_PER_UNIT = 120` ⇒ 规模 = 12,000 ÷ 120 = **100 单位**；每周期工具投入 = **10,000 毫**、留 **2,000 毫**余量（"恰好烧完"的自洽性**已放弃**，理由见 `MerchantTradeTemplate.TOOL_PER_UNIT` 注） |
| `:4745` | `capacityPerUnit = {TOOL: MerchantTradeTemplate.TOOL_PER_UNIT}  // ★ W7-fix：100 毫工具作业资本 / 1 单位运力` | 值现为 **120** 毫工具/单位规模；且它**不是**"一周期工具投入"（那是 `TOOL_MILLI_PER_UNIT_CYCLE = 100`） |
| `:4777` | `capacityPerUnit = {TOOL: MerchantTradeTemplate.TOOL_PER_UNIT}(100)  ← 作业资本那一维（= 一周期工具投入）` | 同上：值 120，且"= 一周期工具投入"这一等式 W7-fix2 **明确取消** |

★ 这几行**只是注释**，不参与任何算式（`javap` 实测 class 里内联的是 `120l`，见 §0）；但它们是下一个读者的**误导源**，
建议控制方在提交前顺手改（或在下一批的 main 改动里带上）。

---

## 10. 附：本批改动的测试文件（唯一交付面）

| 文件 | 改动 | 实跑 |
|---|---|---|
| `simos-economy/src/test/java/io/mosire/simos/economy/time/MerchantOnDemandTradeW7Test.java` | **重写**（8 条：两维负向各自具名 + 正向闸 + 模板无 CATTLE + 执行期负向/正向/整户迁空 + CATTLE 不顶用） | 8/8 ✅ |
| `simos-app/src/test/java/io/mosire/simos/app/world/MerchantTradeScaleAnchorCouplingTest.java` | **新增**（3 条跨模块耦合守卫，含真播种世界） | 3/3 ✅ |
| `simos-app/src/test/java/io/mosire/simos/app/world/HaulServiceCommoditySeedTest.java` | ③ 旧断言改严（`hasSize(1)` → 逐键逐值 TOOL）+ 描述串更正 | ✅ |
| `simos-economy/src/test/java/io/mosire/simos/economy/time/HaulServiceCommodityAcceptanceTest.java` | 注释更正（`{CATTLE:{tool:100}}` → `{TOOL:{tool:100}}` + 夹具口径说明） | ✅ |
| `simos-app/src/test/java/io/mosire/simos/app/time/EconomyCycleHealthTest.java` | 指纹重基线（两档 + 类注释「W7e」节 + 装置 A/B 证据）；五条牙未动 | 1/1 ✅ |

## 11. 收尾状态（本批结束时）

- **最终整轮**：`tools/mvn-lock.sh test -pl simos-app -am` ⇒ **BUILD SUCCESS / rc=0**，15 模块 · 411 类 · **3,445 条 / 0 失败 / 0 错误 / 5 跳过**（`EconomyCycleHealthTest` 单跑 1/1 绿，五条牙读数行见 `simos-app/target/economy-cycle-invariant/summary.txt`）。
- 大文件：`simos-app/target/surefire-reports/TEST-…EconomyCycleHealthTest.xml`（1.1 GB）**已删**；`logs/tp360.log` gz 后 161 MB；
  `logs/` 合计 ~806 MB、`dumps/` 66 MB、`results/` 468 KB（读数证据，全部保留；`logs/swgen.log` 与各 `*.result.json` 亦保留）。
- 无 java/python 进程残留（服务逐个由装置收工；`pgrep -af shaded` 空）。
- `git status`：`src/main` 侧 = W7 系列既有 12 项（**一字未增未减**，变异自证已逐字节还原）；`src/test` 侧 = §10 表里 5 个文件；
  `.superpowers/**` 被 gitignore（控制方 `git add -f`）。
