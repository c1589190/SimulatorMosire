# G3d 复验账本 —— `ffa363e9`（G3 遗留三处小修）唯一改行为项：tool 预算判据时点收窄

> 角色：**验证 Agent**（可起独立世界 / 读日志；**未改任何代码、测试、文档、pom**；本账本与 `/tmp/g3d-*` 是唯一写盘）。
> 日期：2026-10-10。前置基线：`.superpowers/sdd/2026-10-10-g3c-verify-tool-gate/impl-ledger.md`（上一轮数字）、
> `.superpowers/sdd/2026-10-10-diagnose-debt-anomaly/impl-ledger.md` §2（Q2 定性）。
> ★ 一句话结论：**判据时点确实改对了（A 的过期镜像拦截 5,824→0，且改后有真放行证据）**，但
> **题面判据「下降但不归零」在 A 不成立（归零）**；同一新口径在 B **重复扣减**（活视图已含本轮燃烧），
> 把过严从 A 搬到 B（B 新增 25 趟假拦 / 真烧 91→88）。三条回归（`day` 字段、债务本金分列、A 0→360）**全部通过**。

## 0. 装置与 jar 身份

| 项 | 值 |
|---|---|
| HEAD | `ffa363e95e1cd67521b06db593b6fed8bb9e7dc1`（commit 09:38:34），`git status --porcelain` = **空**（0 行） |
| jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`7643902192dbeee9f6cfa5f44cfe8f4e`**，27,037,168 B，mtime `09:44:35` |
| 构建 | `MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package` → **BUILD SUCCESS**（16 模块，3.844 s）；`find . -name '*.java' -newer <jar>` = **0** |
| 构建前实例检查 | `pgrep -af java \| grep -i simos` = 仅 pid 2081：`java -jar /tmp/simos-shaded-NwvHbq.jar --store …/2026-10-09-scale-probe/store --gui-port 6111`。`/proc/2081/fd` 只持 `/tmp` 快照，**无任何进程持 target jar** ⇒ 继续、**未停它、未动它**（收工后仍在跑） |
| jar↔源码 | 我的构建与 09:43:24 的既有产物 **md5 逐字节相同**；jar 内含 `ffa363e9` 新增串：`debtPrincipalByUnit`/`creditPrincipalByUnit`（各 2）、`releasedMilli`/`logHouseholdAssembly`/`debtPrincipalNote`（各 1） |
| 装置 A（核心判据 + 回归） | 原生多市场区（`g3c-setup1/2` 按端口 sed 复制：numeraire/zone/口岸税/官方汇率/铜区价/铸 token），`setup1 → 0→5 → setup2 → 5→10 → 10→360`；store `/tmp/simos-g3d-store-a`；端口 8811/8815/8813；日志 `/tmp/g3d-runA.log` = 177,070,850 B / 489,562 行 / md5 `a1a974ba59d2fa1d1601e4b7a05a3b4e` |
| 装置 B（跨格成交方向） | 缺省 `--world=small-world`，**0→360 一次推进**；store `/tmp/simos-g3d-store-b`；端口 8911/8915/8913；日志 `/tmp/g3d-runB.log` = 139,894,260 B / 377,798 行 / md5 `ee9e85f7af2dc5e994094a9912de8130` |
| 口径自校 | 本轮脚本跑**上一轮**日志复现：A ①0/②0/③0、B 7,055/84/91/397、blocked 2,257 行/Σ94,813（frozen 49,369/short 39,620/**budget 5,824·29 行**）、人口 A 4,926 / B 4,923、债务 A 4,200,694 / B 20,372 —— 与 g3c 账本**逐值相同** ⇒ 可对照 |

## 1. ★ Q2：A 世界 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`（判据时点的靶子）

| 读数（A 世界） | 上一轮 `43a28364` | 本轮 `76439021` | 判定 |
|---|---|---|---|
| 行数（(day,household) 唯一，无重复键） | 2,257 | **2,567** | — |
| Σ`blockedRuns` | 94,813 | **94,570**（−243，−0.26%） | 几乎不变 |
| **`reason=tool-budget-exhausted` 行数** | **29** | **0** | ★ 归零 |
| **Σ`budgetBlockedRuns`** | **5,824** | **0** | ★ 归零（不是"下降但不归零"） |
| Σ`frozenBlockedRuns` / Σ`shortBlockedRuns` | 49,369 / 39,620 | **50,625 / 43,945** | 预算档的拦被搬到这两档 |
| 新字段 `releasedMilli` | 无此字段（0/2,257 行） | **2,567/2,567 行** | ✅ 全带 |
| 自解释（`available − released×1000 < needed`） | 0/2,257 | **2,567/2,567 成立** | ✅ 成立 |

**29 行的逐行映射（旧 budget 行 → 新同 (day,hh) 行）**：`25 行仍被拦但改归因 tool-short` + **`4 行不再被拦`** + 0 行仍是 budget。
- 样本 day=125 `hh-0_0-urban-rich_peasant`：旧 `avail=1879 mirror=575 → budget`；新 `stock=879 avail=879 released=1000 → tool-short`。
- ★ 1879 − 879 = **1000 = 恰好一趟的燃烧**（该户当日真放行了 1 趟）⇒ 这不是"静默跳过"，是**过期镜像拦截消失后真烧了一趟、
  之后活视图真的<一趟 ⇒ 归因改成真原因**。day=215/220/225/230/235 甚至 `released=0`（当日没放行）而 stock 735~770 ⇒ 前日燃烧的残留。
- 行为侧配套：A `MERCHANT_HAUL_RUN` **35 → 48**（+37%，48/48 全 `toolBurnedMilli>0`）、`CARRIER_FEE_PAID` 35 → 48、
  `MARKET_FILL` 1,687 → 1,719（+1.9%，跨格 646 → 659）⇒ 放行真的变成了跑商。

## 2. ★ Q2 的"分支未死"证据：B 世界（缺省）

| 读数（B 世界） | 上一轮 | 本轮 | 判定 |
|---|---|---|---|
| blocked 行数 / Σ`blockedRuns` | 506 / 4,102 | **511 / 4,094** | ≈ |
| **Σ`budgetBlockedRuns` / 行数** | **0 / 0** | **25 / 6** | ★ 0→25，分支**未死** |
| 其中 `releasedMilli` 取值 | — | 6/6 行均 `released>0`（1000×3、2000×2、4000×1） | ✅ 正是"真放行后用尽仍应拦" |
| `releasedMilli` 全行覆盖 / 自解释 | 0/506 | **511/511；511/511 成立** | ✅ |
| `MARKET_FILL`（跨格） | 7,055（5,949） | **7,045（5,941）** | −10（−0.14%）**未回升** |
| `CARRIER_FEE_PAID` | 84 | **82** | −2（−2.4%） |
| `MERCHANT_HAUL_RUN` 真烧趟次 | 91（全 burn>0） | **88（全 burn>0）** | **−3（−3.3%）** |
| `reason=LOGISTICS_CAPACITY`（锚 `MARKET_UNFILLED`） | 397 | **393** | −4（−1.0%） |
| `MARKET_CREDIT_FILL` / `MARKET_UNFILLED` / `LANE_TRUNCATED` | 6,200 / 25,419 / 2,260 | 6,198 / 25,425 / 2,255 | ≈（−0.03% / +0.02% / −0.22%） |
| `MERCHANT_CAPACITY_HOUSEHOLD`（同装置锚） | 13,680 | 13,680 | 逐值相同 |

⇒ **方向**：A 侧回升（真烧 +37%、成交 +1.9%），**B 侧未回升、各项 −0.1%~−3.3%**。

## 3. 不许把门槛放坏（三项硬判据，A/B 都 0）

| 判据 | A 上一轮 → 本轮 | B 上一轮 → 本轮 | 判定 |
|---|---|---|---|
| ① `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` | 0 → **0** | 0 → **0** | ✅ 且事件**未删**（`MarketSettlement.java:7050` + jar 内字符串各 1）⇒ 0 = 条件不触发 |
| ② (day,household)：SHORT ∧ `toolBurnedMilli=0` 的 HAUL_RUN | 0 → **0** | 0 → **0** | ✅ |
| ③ ②中同日同户仍有 `CARRIER_FEE_PAID` | 0 → **0** | 0 → **0** | ✅ |
| 强化式：`HAUL_RUN 且 toolBurnedMilli=0` | 0 → **0** | 0 → **0** | ✅ |

## 4. 回归

| 项 | 上一轮 | 本轮 | 判定 |
|---|---|---|---|
| ① A 原生多区 0→5/5→10/10→360 committed rev | 11/20/21 | **11/20/21**；head 21 / tick 360；9.34 s | ✅ |
| A 异常 | 0 / WARN 1,994 | `Exception`=0、` ERROR `=0、`IllegalStateException`=0；WARN `MARKET_SUBJECT_COLLECTIVE_UNRESOLVED`=**1,994**（逐值相同）；`RegisterHousehold`=0、`EnsureHouseholdAccount`=0 | ✅ 原生、无异常 |
| ② A 人口 / 粮 / 货币 | 4,926 / 1,071,755,306 / 流通==发行 | **4,926 / 1,071,755,304**（−2 毫）/ `circulation==baseMoney`（copper 100,000·silver 273,200·token 5,000）✅ | ✅ 同量级、守恒 |
| A 成交 / 信用 | FILL 1,687 / CREDIT_FILL 2,848 | 1,719 / **2,848** | ✅ |
| A 债务（两侧） | debt 4,200,694 / 1,932；credit 4,200,694 | **4,200,694 / 1,933**；credit 4,200,694（两侧逐值相等） | ✅ |
| A 逐格（19 格 × 6 字段） | — | 仅 `0_0` 三处不同：`grainStock −2`、`debtCount +1`、`creditCount +1` | ✅ 世界几乎不动 |
| B 人口 / 粮 / 货币 | 4,923 / 3,867,997,193 / ✔ | **4,923 / 3,867,997,184**（−9 毫）/ ✔ | ✅ |
| B 债务 | 20,372 / 1,092 | **20,375 / 1,092**；credit 20,375 | ✅ 同量级（g3c 报告过的"B 债务塌陷 −98%"**不是本提交造成的**，量级未变） |
| B 逐格 | — | 19 字段不同（粮在格间重分布、信用笔数 ±1） | 轻微分叉 |
| ③ `MERCHANT_CAPACITY_HOUSEHOLD` 带 `day` | A 0/13,680、B 0/13,680 | **A 13,680/13,680、B 13,680/13,680**（`day` 紧随 `originKind`，首字段；样例 `day=1 hex=-1_-1 …`） | ✅ |
| ③ `debtPrincipalByUnit`/`creditPrincipalByUnit` + Note | 不存在 | **A/B 各 19/19 格出现 4 键**；**Σ 逐值==标量 38/38 成立、0 不成立**（A 合并 = grain 4,200,581 + tool 113 = 4,200,694；B = grain 769 + silver 19,606 = 20,375） | ✅ |

## 5. ★ 新发现缺陷（只报不改）

**★★ 新判据在"活视图已包含本轮燃烧"时重复扣减 ⇒ B 侧 25 趟假拦（过严从 A 搬到 B）。**
- 现场（B，`/tmp/g3d-runB.log`）：`day=213 hh-0_0-urban-landlord stockMilli=1465 frozenMilli=0 availableMilli=1465
  neededMilli=1000 releasedMilli=2000 toolBudgetRemainingMilli=1465 reason=tool-budget-exhausted`（6 行同型，`released∈{1000,2000,4000}`）。
- 门槛式 `available − released×1000`：`1465 − 2000 < 1000` ⇒ 拦。但**两种成文口径都会放行**：
  ① 类注/doc 自述「判据 = **当刻**现货−冻结够不够一趟」⇒ `1465 ≥ 1000` 放行；
  ② "预算 = 装配时点可用量"口径 ⇒ `A0 = mirror + released = 1465 + 2000 = 3465`，已放行 2 趟用掉 2,000，第 3 趟只到 3,000 ≤ 3,465 ⇒ 也放行。
- **"活视图已含燃烧"有直证**：A 的 29 行映射里 `旧 avail 1879 → 新 stock 879`（同一 (day,hh)，差 **恰好 1000** = 一趟燃烧）
  ⇒ 同一天内放行一趟，活视图就少 1000；此时再减 `released×1000` = 把同一笔算两次。
- 后果（B）：`budgetBlockedRuns` 0→**25**、真烧 91→**88**、运费腿 84→**82**、`MARKET_FILL` 7,055→**7,045**（全部方向为"更严"，量级小）。
- 归因口径未随之更新（非缺陷但值得记）：`reason` 仍由未改的 `toolBlockReason(stock, available)`（`:828-835`）按**活视图**判定，
  而闸门用**减后量** ⇒ `tool-budget-exhausted` 现在的含义是"活视图够、减项后不够"。新字段 `releasedMilli` 使该行**自解释**
  （已逐行核对 100%，见 §1/§2），可作读日志依据。

## 6. 我没做 / 没验证（如实）

1. **没跑任何 Maven `test`/`verify`**（只 `-DskipTests package` 到编译/打包）：门禁、单测、前端门禁**不在本报告结论内**。
2. **无因果隔离**：两世界都分叉（A 仅 1 格 3 字段；B 19 字段），A/B 的差值 = "轨迹 vs 轨迹"，**不能分解**"修复效应"与"分叉效应"；
   §1 的 29 行映射是**行级**（每行的计数是当日累计的多次 select），未做**逐调用**对账。
3. 未跑 3600/3650 tick、`--world=three-powers`、N7 确定性双跑、FX 部分成交、GM 家户补丁世界。
4. 未核 `remainingToolMilli` 变负（工具上升时）对其它读数的影响面；未核本轮新增 DEBUG 行（A 2,567 / B 511 行）的日志量上界。
5. **B 债务塌陷（≈20.4k，比 g3b 的 1.17M 低 98%）仍未归因**：本轮只证"量级与上一轮一致、非本提交引入"。
6. 未核"`MERCHANT_CAPACITY_HOUSEHOLD` 搬出 `assemble` 后有无第三方脚本按旧行序解析"（字段集与顺序逐字未变、只多首字段 `day`）。

## 7. 复现命令（关键几条）

```bash
cd /home/cna/SimulatorMosire
git rev-parse HEAD; git status --porcelain | wc -l
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package        # BUILD SUCCESS
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar     # 7643902192dbeee9f6cfa5f44cfe8f4e
find . -name '*.java' -newer simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar | wc -l   # 0

bash /tmp/g3d-runA.sh    # 装置 A（8811/8815/8813，store /tmp/simos-g3d-store-a）→ /tmp/g3d-runA.log
bash /tmp/g3d-runB.sh    # 装置 B（8911/8915/8913，store /tmp/simos-g3d-store-b）→ /tmp/g3d-runB.log
bash /tmp/g3d-dumpB.sh   # 重启 B 的 store 只读 dump → /tmp/g3d-state-b.json

python3 /tmp/g3d-budget.py  /tmp/g3c-runA.log /tmp/g3d-runA.log /tmp/g3c-runB.log /tmp/g3d-runB.log  # Σbudget 5,824→0 / 0→25
python3 /tmp/g3d-count.py   /tmp/g3c-runA.log /tmp/g3d-runA.log /tmp/g3c-runB.log /tmp/g3d-runB.log  # ①②③ 全 0
python3 /tmp/g3d-released.py /tmp/g3d-runA.log /tmp/g3d-runB.log                                     # released 覆盖与取值
python3 /tmp/g3d-health.py  /tmp/g3c-state-a.json /tmp/g3d-state-a.json /tmp/g3d-state-b.json        # 人口/粮/货币/债务/byUnit
python3 /tmp/g3d-block-stats.py /tmp/g3d-runA.log /tmp/g3d-runB.log                                  # 自解释逐行核对
```

**产物**：日志 `/tmp/g3d-run{A,B}.log`（+ `g3d-runB2.log`）；读数 `/tmp/g3d-state-{a,b}.json`；推进结果 `/tmp/g3d-adv*`；
读数文本 `/tmp/g3d-{A,B}-counts.txt`、`/tmp/g3d-oldbudget-map.txt`；脚本 `/tmp/g3d-{runA,runB,dumpB}.sh`、
`/tmp/g3d-{count,budget,block-stats,released,health}.py`；store `/tmp/simos-g3d-store-{a,b}`（进程已全停）。
