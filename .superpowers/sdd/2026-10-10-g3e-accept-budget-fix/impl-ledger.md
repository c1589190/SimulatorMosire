# G3e 验收账本 —— `b6e494ea`（G3-fix-3 去掉 tool 判据的重复扣减）三条判据 + 一次回归

> 角色：**验证 Agent**（可起独立世界 / 读日志；**未改任何代码、测试、文档、pom**；本账本与 `/tmp/g3e-*` 是唯一写盘）。
> 日期：2026-10-10。基线：`.superpowers/sdd/2026-10-10-g3d-verify-budget-timing/impl-ledger.md`（上一轮数字）、
> `.superpowers/sdd/2026-10-10-g3c-verify-tool-gate/impl-ledger.md`（更早）、`.superpowers/sdd/2026-10-10-g3-fix-3/impl-ledger.md`（本次修法）。
> ★ 一句话结论：**三条判据全部通过** —— B 假拦 6 行/25 趟 → **0/0**；三条 0 判据仍全 0；A 真烧 **48（不下降）**、B 真烧 **91（88→91）**；
> 回归通过（A 事件计数 8/8 与 dump 21/21 **逐值同上一轮**、0→360 rev 11/20/21、0 异常）。

## 0. 装置与 jar 身份

| 项 | 值 |
|---|---|
| HEAD | `a807b4a085b1626a62137a6932ab80ab70090d5b`（= 修复提交 `b6e494ea` + 其后 docs 提交，09:59:03），`git status --porcelain` = **空**（0 行） |
| 构建 | `MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package` → **BUILD SUCCESS**（16 模块，3.86 s，09:59:28）；随后为**强制干净重编**再跑一次（`rm -rf simos-economy/target/classes`，14.06 s，09:59:55） |
| 被测 jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`a8767ed8dfad005a69542eed61f4f89b`**，27,037,172 B，mtime `2026-10-10 09:59:55`（★ 两次构建 md5 不同：shade 内嵌时间戳；**被测的是这一个**） |
| jar↔源码 | `find . -name '*.java' -newer <jar>` = **0**；**强制自证**：干净重编后 jar 内 `…/time/MerchantCapacityPool.class` md5 = **`cea016063b15558b3cdc08787eeed14d`** == `simos-economy/target/classes/…` 同名 class md5（逐字节相同）⇒ jar 里的字节就是 HEAD 源码（含 `+ : toolAvailableMilli;` 修复行）编出来的 |
| 跑前实例检查（题面要求） | `pgrep -af java \| grep -i simos` = 仅 **pid 2081**：`java -jar /tmp/simos-shaded-NwvHbq.jar --store ~/simos-runs/2026-10-09-scale-probe/store --world=three-powers --gui-port 6111 …`；`/proc/*/maps` 扫 `SimulatorMosire/simos-app/target` = **0 持有者** ⇒ **继续、未停它、未覆盖共享 jar**；`run-shaded.sh` 把 jar 复制成每进程独占快照（本轮 = `/tmp/simos-shaded-QuMjD4.jar`）。收工后 `ps` 仍只剩 2081 |
| 装置 A（判据③ + 回归） | **原生多市场区**（`g3b-setup1/2` 按端口 sed 复制到 9115：numeraire/zone/口岸税/官方汇率/铜区价/铸 token；`SetMarketNumeraire`=1 佐证），`setup1 → 0→5 → setup2 → 5→10 → 10→360`；store **`/tmp/simos-g3e-store-a`**（9.7M）；端口 GUI 9111 / MCP 9115 / 审批 9113；日志 `/tmp/g3e-runA.log` = 177,070,850 B / 489,562 行 / md5 `95bd724fb863a663c9122c593fa3a48a`。无家户补丁：`RegisterHousehold`=0、`EnsureHouseholdAccount`=0 |
| 装置 B（判据①） | 缺省 `--world=small-world`（`SetMarketNumeraire`=0），**0→360 一次推进**；store **`/tmp/simos-g3e-store-b`**（5.5M）；端口 GUI 9011 / MCP 9015 / 审批 9013；日志 `/tmp/g3e-runB.log` = 139,904,401 B / 377,830 行 / md5 `0f9ec6f54a34ec83bff42fd240d41cad` |
| 日志档位 | 两世界均 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`（与上一轮同档）；驱动沿用 `/tmp/g3b-mcp.py` / `g3b-adv.sh` / `g3-dump.py`，setup 仅按端口 sed 复制 |
| 口径自校 | 本轮新写的判据①脚本跑**上一轮**日志 `/tmp/g3d-runB.log` → **6 行 / 25 趟**（budget 批次），与 g3d 账本 §2 逐值相同 ⇒ 分析器与该口径可直接对照、**有判别力** |

## 1. ★ 判据①：B 世界"假拦"归零（上一轮 6 行 / 25 趟）

口径：拦 ⇔ 当刻可用量 `max(0, 现货 − 冻结)` < 门槛；**假拦** = 行内当刻可用量 ≥ 门槛却仍被拦。

| 读数 | 上一轮 `76439021`（g3d） | 本轮 `a8767ed8`（g3e） | 判定 |
|---|---|---|---|
| B `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 行数 / 唯一 (day,hh) / 重复键 | 511 / 511 / 0 | **506 / 506 / 0** | — |
| B Σ`blockedRuns`（frozen/short/**budget**） | 4,094（2,216/1,853/**25**） | **4,102（2,222/1,880/0）** | budget 归零 |
| **★假拦行数 / 假拦趟次** | **6 / 25** | **0 / 0** | ✅ **归零** |
| `reason=tool-budget-exhausted` 行数 | 6 | **0** | ✅ 分支消失 |
| 真拦（可用量<门槛）行 / 趟 | 505 / 4,069 | **506 / 4,102** | 逐行自解释 |
| `availableMilli != max(0,stock−frozen)` 行数 | 0 | **0** | 口径一致 |
| `releasedMilli` 覆盖 | 511/511 | **506/506**（纯日志字段仍全带） | ✅ |

同一脚本跑 **A 世界**：假拦 **0 行 / 0 趟**（2,567 行 / Σ94,570 = frozen 50,625 + short 43,945，budget 0）；上一轮 g3d A 亦为 0（`reason=tool-budget-exhausted` = 0 行）⇒ **A 本无假拦，本提交对 A 无行为影响**（见 §3 的 8/8 逐值同）。

**上一轮 6 行假拦的去向（逐 (day,hh) 映射，直证"不是行消失而是真放行"）**：

| 上一轮（budget 假拦） | 本轮同 (day,hh) | 同户当日 `HAUL_RUN`（旧→新） |
|---|---|---|
| day=213 `hh-0_0-urban-landlord` avail=1465 rel=2000 → 拦 | 仍有行：**avail=465**（−1000，恰一趟燃烧）→ `tool-short` **真拦** | 2 → **3** |
| day=303 同户 avail=1885 rel=1000 → 拦 | 仍有行：**avail=885**（−1000）→ `tool-short` **真拦** | 1 → **2** |
| day=333 同户 avail=4781 rel=4000 → 拦 | **整行消失**（当日不再被拦） | 4 → **6** |
| day=335 同户 avail=2781 rel=2000 → 拦 | 仍有行：**avail=785**（≈−2 趟）→ `tool-short` **真拦** | 2 → 1（轨迹已分叉） |
| day=340 同户 avail=1781 rel=1000 → 拦 | 仍有行：**avail=890** → `tool-short` **真拦** | 1 → 0 |
| day=360 `hh-0_2-urban-landlord` avail=1311 rel=1000 → 拦 | 仍有行：**avail=311**（−1000）→ `tool-short` **真拦** | 1 → 0（当日有 `loan_repayment` 货流，非纯燃烧） |

⇒ 6 行中 **5 行同 (day,hh) 仍在但已成"当刻可用量真不够"的真拦**，1 行（day=333）放行后彻底不再拦；被放行的趟数可在 `HAUL_RUN` 上直接看到。

## 2. ★ 判据②：三条保持为 0（A/B 都 0）

| 判据 | A（上一轮→本轮） | B（上一轮→本轮） | 判定 |
|---|---|---|---|
| ① `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` | 0 → **0** | 0 → **0** | ✅ |
| ② (day,household)：SHORT ∧ `toolBurnedMilli=0` 的 `HAUL_RUN` | 0 → **0** | 0 → **0** | ✅ |
| ③ ②中同日同户仍有 `CARRIER_FEE_PAID` | 0 → **0** | 0 → **0** | ✅ |
| 强化式：`HAUL_RUN 且 toolBurnedMilli=0`（条数） | 0 → **0**（0/48） | 0 → **0**（0/91） | ✅ |
| 事件未删 | jar 内 `MarketSettlement.class` 含 `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` ×1 | 同 | ✅ 0 = 条件不触发 |

## 3. ★ 判据③：A 真烧不下降（+ B 真烧）

| 读数 | 上一轮 | 本轮 | 判定 |
|---|---|---|---|
| **A `MERCHANT_HAUL_RUN` 且 `toolBurnedMilli>0`** | **48**（全 `=1000`） | **48（48/48 `toolBurnedMilli=1000`）** | ✅ **不下降（持平）** |
| A `CARRIER_FEE_PAID` | 48 | **48** | ✅ |
| **B `MERCHANT_HAUL_RUN` 且 `toolBurnedMilli>0`** | **88**（全 `=1000`） | **91（91/91 `toolBurnedMilli=1000`）** | ✅ +3（+3.4%） |
| B `CARRIER_FEE_PAID` | 82 | **84** | ✅ +2 |
| B `MARKET_FILL`（跨格） | 7,045（5,941） | **7,055（5,949）** | ✅ 回升 |
| B `MARKET_UNFILLED` / `LANE_TRUNCATED` / `LOGISTICS_CAPACITY` | 25,425 / 2,255 / 393 | **25,419 / 2,260 / 397** | ✅ 未恶化 |
| B `MERCHANT_CAPACITY_HOUSEHOLD`（装置锚） | 13,680 | **13,680** | ✅ 同装置 |
| **A 全事件计数对照（8 事件）** | — | **8/8 与上一轮逐值相同**（2,567 / 48 / 48 / 1,719 / 2,848 / 50,370 / 48,261 / 13,680） | ✅ A 未分叉 |
| B 与更早 `c7f38fed`（fix-2，减项引入前） | — | 91 / 84 / 7,055 / 6,200 逐值相同 | ✅ 减项副作用被完全回退 |

## 4. 回归

| 项 | 上一轮 | 本轮 | 判定 |
|---|---|---|---|
| A `advance` 0→5 / 5→10 / 10→360 committed rev | 11 / 20 / 21 | **11 / 20 / 21**；head **21**、tick **360**；10→360 用 **9.77 s** | ✅ |
| A 异常 | 0 | `Exception`=**0**、` ERROR `=**0**、`IllegalStateException`=**0**、`at io.mosire`=**0**；WARN **2,022** = `MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` 1,994 + `ACCOUNT_SUBJECT_UNRESOLVED` 17 + `POPULATION_SETTLE_REMAINDERS_CLEANED` 11（与上一轮同类同数） | ✅ |
| A 人口 / 粮 / 货币 | 4,926 / 1,071,755,304 / 流通==发行 | **4,926 / 1,071,755,304**（逐值同）/ `circulation==baseMoney` True（copper 100,000·silver 273,200·token 5,000） | ✅ 守恒 |
| A 成交 / 信用 | FILL 1,719 / CREDIT_FILL 2,848 | **1,719 / 2,848** | ✅ |
| A 债务（两侧） | 4,200,694 / 1,933 | **4,200,694 / 1,933**；credit 4,200,694（两侧逐值相等）；byUnit Σ==标量 **38/38 成立**、0 不成立（grain 4,200,581 + tool 113） | ✅ |
| A 状态 dump 逐键对照 | — | **与上一轮 21/21 键完全相同（差异 0）** | ✅ 同轨迹 |
| B 人口 / 粮 / 货币 | 4,923 / 3,867,997,184 / ✔ | **4,923 / 3,867,997,193**（+9 毫）/ ✔ | ✅ 同量级、守恒 |
| B 成交 / 信用 | FILL 7,045 / CREDIT_FILL 6,198 | **7,055 / 6,200** | ✅ |
| B 债务 | 20,375 / 1,092 | **20,372 / 1,092**；credit 20,372；byUnit Σ==标量 **38/38**（grain 769 + silver 19,603） | ✅ 同量级（= fix-2 时的 20,372 逐值相同） |
| B 异常 | 0 | `Exception`=0、` ERROR `=0；WARN 7（`POPULATION_SETTLE_REMAINDERS_CLEANED`） | ✅ |

## 5. 我没做 / 没验证（如实）

1. **未跑 `test` / `verify` / 前端门禁 / 确定性双跑（N7）**：本轮只 `-DskipTests package` 到打包（为 jar 身份）+ 起两世界读日志；单测/门禁**不在本报告结论内**。
2. **未跑 3600/3650 tick、`--world=three-powers`、FX 部分成交、GM 家户补丁世界**。
3. **无逐调用级因果分解**：A 侧因"dump 21/21 逐键相同 + 事件计数 8/8 相同"可视作**同一轨迹**（因此判据③ 48→48 是净效应）；
   B 侧是**跨轮次轨迹对照**（首行被放行后轨迹即分叉），91 vs 88 只能读作"方向与量级"，不能逐趟归因。
4. **`tool-budget-exhausted` 分支的生产可达性构造不出**：A/B 两世界 `budgetBlockedRuns` Σ 均 = 0、reason 0 行 ⇒ 只能核"常量仍在 jar 内"（`MerchantHaul.class` 含该串 ×1），**未能**在真实世界里构造出该分支。
5. **B 债务塌陷（20.4k ≈ g3b 1.17M 的 2%）仍未归因**：本轮只证量级与上一轮一致，且 fix-3 把它恢复到与 `c7f38fed`（fix-2）**逐值相同**（20,372 / 1,092）⇒ 非本提交引入，遗留。
6. 未核逐户日志量增长上界（只报实测：B 506 行 / A 2,567 行）。

## 6. 新发现 / 核实（只报不改）

1. **`tool-budget-exhausted` / `budgetBlockedRuns` 已成本装置上的死分支**：A 2,567 行、B 506 行里 Σ `budgetBlockedRuns` **全 0**，
   而字段 `toolBudgetRemainingMilli` 仍在每行打印 ⇒ 后人若按该 reason/计数统计"预算用尽"会**恒得 0**（与 fix 账本 §3-4 自述一致，**本次实测核实为真**）。
2. **归因与闸门口径现已完全同源**：`reason` 由同一活视图 `max(0,现货−冻结)` 判定，逐行核对 **B 506/506、A 2,567/2,567** 满足 `availableMilli < neededMilli`，且 `availableMilli` 与 `max(0,stock−frozen)` **逐行相等（不符 0 行）** ⇒ 被拦行自解释，不会再有"活视图够却仍被拦"的旧型假拦。
3. 未见**新**缺陷；未发现日志字段/事件形状变化（`releasedMilli` 仍在、仍只作日志字段）。

## 7. 复现命令（关键几条）

```bash
cd /home/cna/SimulatorMosire
git rev-parse HEAD; git status --porcelain | wc -l        # a807b4a0…；0
MVN_LOCK_WAIT=1800 tools/mvn-lock.sh -DskipTests package # BUILD SUCCESS（09:59:55 产物被跑）
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar   # a8767ed8dfad005a69542eed61f4f89b
find . -name '*.java' -newer simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar | wc -l   # 0
unzip -p simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar io/mosire/simos/economy/time/MarketSettlement.class | strings | grep -c MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT   # 1

bash /tmp/g3e-runall.sh        # 先 B（9011/9015/9013，g3e-store-b）后 A（9111/9115/9113，g3e-store-a）+ B dump
python3 /tmp/g3e-gate.py   /tmp/g3e-runB.log /tmp/g3e-runA.log   # ★假拦：B 6/25（旧）→ 0/0；A 0/0
python3 /tmp/g3e-gate.py   /tmp/g3d-runB.log                    # 口径自校：复现 6 行 / 25 趟
python3 /tmp/g3e-count.py  /tmp/g3e-runB.log /tmp/g3e-runA.log   # ①②③ = 0/0/0；HAUL_RUN burn>0 = 91 / 48
python3 /tmp/g3d-health.py /tmp/g3e-state-a.json /tmp/g3d-state-a.json /tmp/g3e-state-b.json /tmp/g3d-state-b.json
```

**产物**：日志 `/tmp/g3e-run{A,B}.log`（+ `g3e-runB2.log`）；读数 `/tmp/g3e-state-{a,b}.json`；推进结果 `/tmp/g3e-adv*`；
脚本 `/tmp/g3e-{runall,runA,runB,dumpB}.sh`、`/tmp/g3e-{gate,count}.py`、`/tmp/g3e-setup{1,2}.py`；
store `/tmp/simos-g3e-store-{a,b}`（进程已全停，仅剩他人的 pid 2081 未动）。
