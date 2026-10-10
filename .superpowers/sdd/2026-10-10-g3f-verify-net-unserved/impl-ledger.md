# G3f 验收账本 —— `b24dafbb`（D-1b 同车道未服务量净额记账）在**真实 world** 的后果与回归

> 角色：**验证 Agent**（起独立世界 / 读日志；**未改任何代码、测试、文档、pom**；本账本与 `/tmp/g3f-*` 是唯一写盘）。
> 日期：2026-10-10。基线：`.superpowers/sdd/2026-10-10-g3e-accept-budget-fix/impl-ledger.md`（上一轮真实 world）、
> `.superpowers/sdd/2026-10-10-fix-d1b-net-unserved/impl-ledger.md`（修法与 /tmp 实测 9,500→9,823）、
> `docs/superpowers/status/2026-10-10-resume-points-and-backlog.md` §7.11。
> ★ 一句话结论：**B 世界与上一轮逐值（含整档 md5）完全相同；A 世界读数自 day 3 起按 D-1b 的预期变小并分叉，
> 新读数落在"每份未服务量记一次"的区间内（比值 ∈ [0.25, 0.6676]，恒 ≤ 1，84/103 天恰为 1/4 = 池请求主导重数 4 的倒数）；
> 回归量级一致（人口/粮/货币守恒/信用/债务逐值相同，成交 −3）。**

## 0. 装置与 jar 身份

| 项 | 值 |
|---|---|
| HEAD | `b42e983a…`（= D-1b `b24dafbb` + 其后 docs 提交，11:02:10），`git status --porcelain` = **0 行** |
| 跑前实例检查 | `pgrep -af java \| grep -i simos` = 仅 **pid 2081**：`java -jar /tmp/simos-shaded-NwvHbq.jar --store ~/simos-runs/2026-10-09-scale-probe/store --world=three-powers …`；`grep -c 'SimulatorMosire/simos-app/target' /proc/2081/maps` = **0** ⇒ **无实例持有 target jar**，继续构建、未停它、未覆盖共享 jar（`run-shaded.sh` 每进程独占快照：本轮 B=`/tmp/simos-shaded-21yJXu.jar`、A=`/tmp/simos-shaded-UtEC2k.jar`） |
| 被测 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`f0e97d0a74133c6461182e635be19a82`**，27,038,739 B，mtime `2026-10-10 11:03:41` |
| jar↔源码 | `find . -name '*.java' -newer <jar>` = **0**；**强制自证**：`rm -rf simos-economy/target/classes simos-app/target/classes` 后重编（EconomySimos 200 文件 + SimosApp 328 文件），jar 内 `…/time/LaneUnservedBook.class` md5 `1ea2e9bba2ca1bc788bccadb6990a808`、`…/time/MarketSettlement.class` md5 `2d409ed1f107097d7e60ffc2639d21f1` —— **与 `target/classes` 同名 class 逐字节相同**；jar 内 `LaneUnservedBook` ×1、`MarketSettlement.class` 含 `MERCHANT_CAPACITY_LANE_BLOCKED` ×1 与 `recordedMilli` ×1 |
| 装置 A | 原生多市场区（`g3f-setup1/2` = g3b 版按端口 sed 复制到 9215：numeraire/3 区/口岸税/官方汇率/铸 token；`SetMarketNumeraire`=1 佐证、`govMarketMandate authorize` 同基线 REJECTED），`setup1 → 0→5 → setup2 → 5→10 → 10→360`；store **`/tmp/simos-g3f-store-a`**（9.7M）；端口 GUI 9211 / MCP 9215 / 审批 9213；日志 `g3f-runA.log` = **176,328,458 B / 490,948 行 / md5 `cbbc781e0a48aa842b6f87cf6c304e00`**（收工后终值；跑动中量到 176,321,687 B / 490,926 行 / `1cd338dd…`，差值 = JVM 关停尾巴） |
| 装置 B | 缺省 `--world=small-world`（`SetMarketNumeraire`=0），**0→360 一次推进**；store **`/tmp/simos-g3f-store-b`**（5.5M）；端口 GUI 9311 / MCP 9315 / 审批 9313；日志 `g3f-runB.log` = **139,822,986 B / 377,831 行 / md5 `74599f3081fe891987d982a9b0efab2f`**（收工后终值；跑动中量到 139,822,874 B / 377,830 行 / `4d9587ea…`） |
| 日志档位 | 两世界均 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`（与上一轮同档）；驱动沿用 `/tmp/g3b-mcp.py` / `g3b-adv.sh` / `g3-dump.py`，分析器 `/tmp/g3f-{d1b,day,firstdiff,norm}.py` |
| 纪律 | 一次一个 Maven、一次一个实例（B → A → B 只读 dump 顺序执行）；四轮日志/档只读，未 `git commit`、未改任何仓内文件 |

## 1. 回归（A/B 都 committed、0 异常、量级一致）

| 项 | A 基线(g3e)→本轮 | B 基线(g3e)→本轮 |
|---|---|---|
| `advance` committed rev | 11 / 20 / 21 → **11 / 20 / 21**；head 21、tick 360 | 2（0→360，tick 360）→ **2**，同 |
| `Exception` / ` ERROR ` / `IllegalStateException` / `at io.mosire` | 0/0/0/0 → **0/0/0/0** | 0/0/0/0 → **0/0/0/0** |
| WARN | 2,022（`MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` 1,994 + `ACCOUNT_SUBJECT_UNRESOLVED` 17 + `POPULATION_SETTLE_REMAINDERS_CLEANED` 11）→ **2,022，构成同数** | 7（`POPULATION_SETTLE_REMAINDERS_CLEANED`）→ **7** |
| 人口 Σ | 4,926 → **4,926** | 4,923 → **4,923** |
| 粮 Σ | 1,071,755,304 → **1,071,755,304** | 3,867,997,193 → **3,867,997,193** |
| 货币守恒 | circulation==baseMoney **True**（copper 100,000 / silver 273,200 / token 5,000）→ 同 | **True**（copper 100,000 / silver 273,200）→ 同 |
| 成交 `MARKET_FILL` / 信用 `MARKET_CREDIT_FILL` | 1,719 / 2,848 → **1,716（−3，−0.17%）/ 2,848** | 7,055 / 6,200 → **7,055 / 6,200** |
| 债务 principal/count（credit 同值） | 4,200,694 / 1,933 → **同**；`byUnit Σ==标量` 38/38 → **38/38** | 20,372 / 1,092 → **同**；38/38 → **38/38** |
| 状态 dump | 21 顶层键中 **20 个不同**（§2） | **21/21 相同**；整档 md5 `4147153e67cb3ef85442c83d386ab9de` **与上一轮逐字节相同** |
| 归因 | 所有计数差异都可追到 D-1b（见 §2；`503969cc` 只删了 3 个纯日志字段 `toolBudgetBlockedRuns`/`budgetBlockedRuns`/`toolBudgetRemainingMilli`，比对时已抹平） | 160 种事件 **0 种**计数不同 |

## 2. ★ D-1b 的行为面

**③① 截断存在吗？** 存在，但**不在** `MERCHANT_CAPACITY_LANE_BLOCKED` 这条路径上：

| 读数 | A 旧→新 | B 旧→新 |
|---|---|---|
| `MERCHANT_CAPACITY_LANE_BLOCKED` 行数 | **0 → 0** | **0 → 0** |
| `MERCHANT_CAPACITY_LANE_TRUNCATED`（池侧承运分配不足） | 48,261 → **48,901** | 2,260 → **2,260** |
| `MARKET_UNFILLED reason=LOGISTICS_CAPACITY` | 4,497 → **4,525** | 397 → **397** |
| `Σ goodsBlockedByCapacityMilli`（= Σ 买槽 `capacityTruncatedMilli`，V-20 的唯一价格输入读数） | 73,164,714 → **28,817,292** | 15,160,581 → **15,160,581** |
| 逐日 `CAPACITY_DEMAND_TOTAL` 8 字段序列 | 132 行中**自 day 3 起不同** | **82/82 行逐值相同** |
| 204 种事件里计数不同的种类 | **15/204** | **0/160** |

**B = 逐值不变（强证据）**：整档状态 dump md5 与上一轮相同（19 格 `market` + 19 格 `marketReadout`、goods/population/debt/credit/classes/industries、`__gov`、`__overview` 全含）；`MARKET_FILL` 序列 7,055 行逐行相同、逐日 VWAP 0 天不同 ⇒ **价格表应逐值不变，且实测就是逐值不变**。

**A = 有截断，读数按预期变小且分叉**：首个**行为**差异 = day 3 `goodsBlockedByCapacityMilli` 11,618,146 → **7,756,594**（比值 1.0000 → 0.6676）；此后 day 4 `LABOR_QUEUE_DECISION` 单位估值 170,000→173,000，day 13 首笔成交价 `tool@0,0` 19→20，21/360 天逐日 VWAP 不同；终局 `market`/`marketReadout` 仍 **19/19 相同**，人口/粮/债务/信用逐值相同，只有家户阶层间银钱分布（landlord 3,627→3,643、rich_peasant 156,812→156,781、poor_peasant 6,844→6,859）与产业流水变。

**③② 自解释性（用替代读数，因为 `recordedMilli` 无样本）**：

| 判据 | 读数 | 判定 |
|---|---|---|
| 逐日 `goodsBlocked ≤ demandQuantity − servedQuantity`（截断量 ≤ 未服务量） | 新 A 132 行 **0 违反**，max 比值 **0.6676**；旧 A 亦 0 违反（比值恰为 **1.0000**） | ✅ 不超记 |
| `goodsBlocked / Σ池侧 unallocatedMilli` 逐日 | 旧 **103/103 天 = 1.0000**；新 **103/103 天 ≤ 1**，**84 天恰为 0.25（=1/4）**，min **0.25**、max 0.6676 | ✅ 只削重复 |
| 池侧请求重复结构（新 A 与旧 A 同形） | 5,452（旧）/5,495（新）个唯一请求中 **86.8% / 86.9%** 重复 ≥2 次；重数分布含 ×4（2,640）、×8（703）、×12/×16/×24/×32/×64/×72 | 重复观察真实存在 |
| 比值下界恰为 **1/4** = 池侧主导重数 4 的倒数，且**从不低于** 0.25 | 84/103 天 | ✅ "每份量只记一次"，且**无**整条车道级水位误伤（`:5291` 疑似未触发） |
| B 的对照 | B 池侧仅 22% 请求重复（1707 唯一/2260 行，Σ唯一=12,813,832 < Σall=15,160,581），而 **D-1b 对 B 的折叠为 0**（比值仍 1.0000） ⇒ 池侧重复**不等于**逐对重复 | ⚠ 见 §4-3 |

## 3. 可观测性（④）

- **`recordedMilli` 在字节里、在真实日志里 0 次**：它只由 `MERCHANT_CAPACITY_LANE_BLOCKED` 发射（`MarketSettlement:6880`），而该事件在**四轮日志（A/B × 旧/新）全部 = 0 行** ⇒ **真实 world 里取不到任何 `recordedMilli` 样本**（字段存在性由 jar 内 class 字节证明）。
- **`capacityTruncatedMilli` 无自己的日志字段**：唯一可观测代理是 `CAPACITY_DEMAND_TOTAL.goodsBlockedByCapacityMilli`（其定义就是各买槽 `capacityTruncatedMilli` 之和），§2 已逐日对照。
- `capacityTruncatedMilli ≤ 该车道未服务量`：**只有日级聚合代理**（max 0.6676 ≤ 1，0 违反）；**逐槽/逐车道**读数在日志里不存在 ⇒ 未验到那一层。
- `LOGISTICS_CAPACITY` 具名（A 4,525 / B 397）与 `MERCHANT_CAPACITY_LANE_TRUNCATED` 都在；`MERCHANT_CAPACITY_HOUSEHOLD` 13,680/13,680 同装置锚。

## 4. 未验证 / 构造不出 / 如实记

1. **`recordedMilli` 样本取不到**（该事件 0 行）⇒ 判据 ③② 无法按其字面取样，本节用"逐日比值 + 池侧重复结构"替代。
2. **`:5291`（路线窗口用尽 / `claimLane`）无日志行** ⇒ 无法直接证明它未触发；只有"比值从不低于 0.25"这条下界可作间接推断（若它曾以车道级大水位写入，后续逐对认领会整条被压到 0 ⇒ 比值会远低于 1/4）。
3. **池侧 4/8 次重复 ≠ 逐对重复（B 是反例）** ⇒ "被折叠掉的那几次观察是不是同一份货"**无法从日志逐条证明**；能证明的只到"pair-key 去重确实发生（比值 < 1）"与"代码语义上该参数是同一次观察的未承运量、重试不会让它变大"。D-1b 的判据只能算**高度吻合**（下界 1/4 = 池请求主导重数 4 的倒数），不是逐条铁证。
4. 无逐槽 `capacityTruncatedMilli` 读数 ⇒ ④ 的"≤ 该车道未服务量"只到日级聚合。
5. **未跑** `test` / `verify` / 前端门禁 / 3600-3650 tick / `--world=three-powers` / N7 确定性双跑 / FX / GM 家户补丁世界。
6. B 的"逐值不变"是**整档字节级 + 事件计数全等**；未逐行 diff 全部 377,830 行（A 侧做了规范化逐行对齐，见 §6）。
7. A 的 day-360 `market` 表相同**不能**当"轨迹未变"的证据（15 类事件计数已不同）—— 本账本不据此下"价格未误改"的结论。

## 5. 新发现（只报不改）

1. **池侧 `MERCHANT_CAPACITY_LANE_TRUNCATED.unallocatedMilli` 本身是重复观察的量**：同一 (day, lane, requested, allocated, unallocated) 在旧 A 档出现 2–8（乃至 72）次，Σ `unallocatedMilli` = 单份量的 **3.45 倍**（73,164,714 vs 唯一副本 21,177,060）。D-1b 只削了槽侧、未动池侧 ⇒ **该 DEBUG 读数的 Σ 不能当"真实未承运量"使用**（非本提交引入，但会让后人误判"修后少记了 60%"）。
2. **旧读数"两侧逐日完全相等（1.0000, 103/103）"看起来自洽，恰恰是陷阱**：只拿"槽侧 == 池侧"当判据，会得出"D-1b 削掉了真量"的相反结论；真正揭示重复的是池侧重复率 86.8% 这条独立读数。
3. **A 世界 day-360 价格表逐值相同但轨迹已分叉** ⇒ "末态相同"不能当"未误改"的证据（应看逐日序列/首个差异行）。
4. 未见任何新异常/守恒破坏：A/B 的 `Exception`/`ERROR` 全 0，人口/粮/货币/债务/信用守恒式全成立。

## 6. 复现命令（关键几条）

```bash
cd /home/cna/SimulatorMosire
pgrep -af java | grep -i simos; grep -c 'SimulatorMosire/simos-app/target' /proc/2081/maps   # 0 ⇒ 可构建
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package                                     # BUILD SUCCESS
rm -rf simos-economy/target/classes simos-app/target/classes && MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar     # f0e97d0a74133c6461182e635be19a82
find . -name '*.java' -newer simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar | wc -l      # 0
unzip -p <jar> io/mosire/simos/economy/time/LaneUnservedBook.class | md5sum                    # == target/classes 同名
bash /tmp/g3f-runall.sh        # B（9311/9315/9313，g3f-store-b）→ A（9211/9215/9213，g3f-store-a）→ B 只读 dump
python3 /tmp/g3f-d1b.py  /tmp/g3f-runA.log /tmp/g3f-csvA-new.csv    # §2 行 1–4
python3 /tmp/g3f-day.py  /tmp/g3e-runA.log /tmp/g3f-runA.log        # 逐日 goodsBlocked vs Σ池侧 unallocated
python3 /tmp/g3f-firstdiff.py /tmp/g3e-runA.log /tmp/g3f-runA.log   # 首个行为差异 = day 3 goodsBlocked
python3 /tmp/g3d-health.py /tmp/g3e-state-a.json /tmp/g3f-state-a.json /tmp/g3e-state-b.json /tmp/g3f-state-b.json
md5sum /tmp/g3e-state-b.json /tmp/g3f-state-b.json                  # 4147153e… 两边相同
```

**产物**：日志 `/tmp/g3f-run{A,B,B2}.log`；读数 `/tmp/g3f-state-{a,b}.json`；逐日 CSV `/tmp/g3f-csv{A,B}-{old,new}.csv`、`/tmp/g3f-day-*.csv`；推进 `/tmp/g3f-adv{A,B}-*`；store `/tmp/simos-g3f-store-{a,b}`（进程已全停，仅剩他人的 pid 2081 未动；jar md5 未变）。
