# G3c 复验账本 —— G3-fix-2（`c7f38fed`）的 tool 门槛在真实世界是否生效 + 回归

> 角色：**验证 Agent**（只读 + 起独立世界；**未改任何 `src/**` / 测试 / docs / pom**；本账本与 `/tmp` 脚本是唯一写盘）。
> 日期：2026-10-10。前置：`.superpowers/sdd/2026-10-10-g3b-reverify/impl-ledger.md`（装置与上一轮数字）、
> `.superpowers/sdd/2026-10-10-g3-fix-2/impl-ledger.md`（本次修法与自证）。
> ★ 结论一句话：**门槛修好了** —— 缺省 B 与多区 A 两世界 `SHORT_AT_COMMIT` = **0**、②③ 双双 **0**、
> `toolBurnedMilli=0` 的跑商 **0 条**；**回归通过**（A 原生多区 0→360 committed rev 21 / 0 异常）。
> ★ 但抓到 1 条实现账本被证伪的断言（`tool-budget-exhausted` 生产可达）+ 1 条大额副作用（B 债务存量 -98%）。

---

## 0. 装置与 jar 身份（可复现）

| 项 | 值 |
|---|---|
| 被测 jar（HEAD 构建） | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 **`43a283645a85e1115b5b4bd110dad350`**，27,036,511 B，mtime **`2026-10-10 09:16:07`** |
| jar↔源码判据 | HEAD=`c7f38fed`（commit 时间 `09:11:19`）⇒ jar **晚于**修复提交 4:48；`git status --porcelain` = 空；`find . -name '*.java' -newermt '2026-10-10 09:15:00'` = **0**；jar 内 `MerchantHaul.class` 含 `tool-budget-exhausted` ×1、`MerchantCapacityPool.class` 含 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` ×1（两串 `git log -S` 唯一来源 = `c7f38fed`）⇒ **确为修复后源码所构建** |
| 未跑 Maven | 本轮**完全没跑** `mvn`/`package`/`test`/`verify`（jar 已由控制方 09:16 构建）。⇒ 不存在"同树 Maven 抢 target/"与"覆盖共享 jar"两个风险 |
| 跑前实例检查（题面要求） | `pgrep -af java \| grep -i simos` = **pid 2081**：`java -jar /tmp/simos-shaded-NwvHbq.jar --store /home/cna/simos-runs/2026-10-09-scale-probe/store --world=three-powers --gui-port 6111 …`。**它跑的是 /tmp 快照 jar，不是本仓 target jar**（`/proc/*/maps` 扫描 `simos-app/target` 路径 = **0 持有者**；`run-shaded.sh` 也把 jar 复制成每进程独占快照）⇒ **未停手、未覆盖、未动它**（全程只在 8611/8715 起自己的世界，结束时全停；收工后 `ps` 仍只剩 2081） |
| 装置 B（核心判据） | 缺省 `--world=small-world`，**0→360 一次推进**；store `/tmp/simos-g3c-store-b`；端口 GUI 8611 / MCP 8615 / 审批 8613；日志 `/tmp/g3c-runB.log`（139,790,423 B / 377,829 行 / md5 `d4f177bb6046ecb6837aa74a342a491a`） |
| 装置 A（回归） | **原生多市场区**（`SetMarketNumeraire(0,-1)=copper` + zone-cap/zone-copper/zone-rural + 两个 GOV 口岸政策（含税/marketControl）+ 官方汇率 1000/1000→950/1050 + 铜区价 grain=3/cloth=8 + 铸 token）；`setup1 → adv 0→5 → setup2 → adv 5→10 → adv 10→360`；store `/tmp/simos-g3c-store-a`；端口 8711/8715/8713；日志 `/tmp/g3c-runA.log`（176,882,796 B / 488,906 行 / md5 `c740322c40d9f67f729f4858858444fc`）。**无家户补丁**：`RegisterHousehold`=0、`EnsureHouseholdAccount`=0 |
| 日志档位（与上一轮同档） | 两世界均 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`（旁证：`DEBT_UPSERT`=0，与 g3b B 跑一致）；驱动沿用上一轮 `/tmp/g3b-mcp.py` / `g3b-adv.sh` / `g3-dump.py`，setup 脚本仅按端口 sed 复制（`/tmp/g3c-setup{1,2}.py`） |
| 判据统计口径自校 | `/tmp/g3c-count.py` 跑**上一轮**日志得 **3,589 / 928 / 708**（与 g3b 账本 §2.2 逐值相同）⇒ 本轮口径与上一轮一致，可直接对照 |

---

## 1. ★ Q1：jar 与源码一致

1. md5 **`43a283645a85e1115b5b4bd110dad350`**（见上表；上一轮 jar `3e4fd80550298868e41cec9d0fb6b9cc` 已作废）。
2. jar 内含新常量（题面要求的两条，`unzip -p … | strings | grep -c` = 1/1）：
   `io/mosire/simos/economy/time/MerchantHaul.class` → **`tool-budget-exhausted`**；
   `io/mosire/simos/economy/time/MerchantCapacityPool.class` → **`MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`**。
3. 运行期自证（比字节更硬）：新事件 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` 在 B 出现 **506** 行、A **2,257** 行 ⇒ 新代码路径**真的在跑**。
4. `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` **仍在代码里**（`MarketSettlement.java:7050`，由 `711a6e94` 引入、`c7f38fed` 未删）⇒ ① = 0 是"条件不再触发"，**不是**"日志被删掉"。

---

## 2. ★ Q2：核心判据（同装置、缺省 small-world、0→360）

| 读数 | 上一轮 `3e4fd805`（G3-fix-1） | 本轮 `43a28364`（G3-fix-2） | 判定 |
|---|---|---|---|
| ① `MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT` | **3,589**（全 `reason=tool-frozen`） | **0** | ✅ 归零 |
| ② `(day,household)`：SHORT ∧ `toolBurnedMilli=0` 的 HAUL_RUN | **928** | **0** | ✅ 归零（①=0 的必然，且 burn0 组数本身也 = 0） |
| ③ ②中同日同户仍有 `CARRIER_FEE_PAID` | **708** | **0** | ✅ **判据成立** |
| 强化式（比②更硬）：`HAUL_RUN 且 toolBurnedMilli=0` 条数 | 3,589 | **0** | ✅ "货走了却不烧工具"结构上消失 |
| `HAUL_RUN` 总数（burn0 / burn>0） | 3,672（3,589 / 83） | **91（0 / 91）** | 见 Q3 |
| `CARRIER_FEE_PAID` | 2,673 | 84 | -97% |
| `MARKET_FILL`（跨格 from≠to） | 10,194（9,325） | 7,055（5,949） | -31%（跨格 -36%） |
| `MARKET_UNFILLED` 总数 | 26,547 | 25,419 | -4% |
| `reason=LOGISTICS_CAPACITY`（`MARKET_UNFILLED`） | 362 | **397** | ✅ 变多（+9.7%） |
| `MERCHANT_CAPACITY_LANE_TRUNCATED` | 1,799 | **2,260** | +26% |
| 新 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT` | —（事件不存在） | **506 行 / 4,102 趟**：frozen 2,222 / short 1,880 / budget 0 | 拦截位置 = select |
| `MERCHANT_CAPACITY_HOUSEHOLD`（池读数，作装置可比锚） | 13,680 | **13,680** | 逐值相同 ⇒ 同装置 |
| 人口 / 粮 / 货币守恒 @360 | 4,923 / 3,867,915,132 / 流通==发行 | 4,923 / 3,867,997,193 / 100,000·273,200 == | 人口逐值相同 |
| `debtPrincipal` / `debtCount` | 1,176,074 / 1,466 | **20,372 / 1,092** | ★ 见 §6-2 |

**同址逐案对照（新代码读到的就是旧代码提交时读到的同一事实）**：B day=3 / `hh--1_-1-urban-landlord`
- 上一轮（提交侧，绕过）：`… SHORT … neededMilli=1000 burnedMilli=0 stockMilli=12000 frozenMilli=12000 availableMilli=0 reason=tool-frozen`，随后照样 `MERCHANT_HAUL_RUN` + `CARRIER_FEE_PAID`；
- 本轮（选择侧，拦下）：`… MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT day=3 household=hh--1_-1-urban-landlord hex=-1_-1 blockedRuns=55 frozenBlockedRuns=55 … stockMilli=12000 frozenMilli=12000 availableMilli=0 neededMilli=1000`，**不再有**该户当日的 HAUL_RUN/FEE。
  两者读数 **12,000 / 12,000 / 0 逐值相同** ⇒ G3-fix-2 "把现读挪进 `select`" 确实读到了当轮冻结。

**A 世界同一判据（多区，原生）**：① 362（frozen 354 / short 8）→ **0**；② 137 → **0**；③ 88 → **0**；
`HAUL_RUN` 424（burn0 362 / burn>0 62）→ 35（**0 / 35**）；`FEE` 305 → 35。

⇒ **判据 ③ = 0 成立**；"SHORT 与运费腿同日同户共存"在真实世界**已不可能**（结构上：`select` 不放行 ⇒ `allocated<=0` ⇒ 整笔不成交、不铸腿、不走货）。

---

## 3. Q3：运输没被打死

| 读数 | 上一轮 | 本轮 | 说明 |
|---|---|---|---|
| B `HAUL_RUN` 且 `toolBurnedMilli>0` | **83** | **91**（+8，+9.6%） | ✅ 真运输仍在、未减少 |
| A `HAUL_RUN` 且 `toolBurnedMilli>0` | **62** | **35**（-27，-44%） | ⚠ 真烧趟次减半（见 §7-3：35 个消失的 (日,户) 中 23 个 `tool-short`（当刻 stock<1000）、5 个 `budget-exhausted`、7 个当日无被拦行） |
| B `LOGISTICS_CAPACITY` | 362 | **397** | ✅ 变多（+9.7%） |
| A `LOGISTICS_CAPACITY` | 2,736 | **4,410** | ✅ 变多（+61%，预期方向明显） |
| B/A `LANE_TRUNCATED` | 1,799 / 20,020 | 2,260 / **48,437** | ✅ 变多 |
| B/A 人口 @360 | 4,923 / 4,926 | **4,923 / 4,926** | ✅ 逐值相同 ⇒ 未饿死 |
| B/A 粮 @360 | 3.8679e9 / 1.08519e9 | 3.8680e9 / 1.07176e9 | B +0.002% / A -1.24% |
| B/A 货币守恒（流通==发行） | ✔ / ✔(+token) | ✔（100,000·273,200）/ ✔（+token 5,000） | ✅ 无破坏 |

⇒ "有工具的户仍在跑商且确实烧工具"成立（B 91 趟 / 91 全烧；A 35 趟 / 35 全烧），
但**总运输量与运费腿大幅下降**（B 跨格成交 -36%、运费腿 -97%），方向与 fix 账本 §6.1 自述的"只减不增"一致。

---

## 4. Q4：回归 —— 原生多市场区世界 0→360

| 项 | 上一轮（g3b A） | 本轮 | 判定 |
|---|---|---|---|
| `advance` 0→5 / 5→10 / 10→360 | committed rev 11 / 20 / 21 | **committed rev 11 / 20 / 21**（逐值相同） | ✅ |
| `head` / `tick` | 21 / 360 | **21 / 360** | ✅ |
| 耗时（10→360） | 9.19 s | **9.75 s** | 同量级 |
| 异常 | 0 | `Exception`=**0**、`stack at …`=**0**、` ERROR `=**0**；WARN 2,022（`MARKET_SUBJECT_COLLECTIVE_UNRESOLVED` 1,994 + `ACCOUNT_SUBJECT_UNRESOLVED` 17 + `POPULATION_SETTLE_REMAINDERS_CLEANED` 11；上一轮 1,971/17/11 同三类） | ✅ 无异常、无新 WARN 种类 |
| 家户补丁 | 0 | `RegisterHousehold`=0、`EnsureHouseholdAccount`=0 | ✅ 原生 |
| 人口 | 4,926 | **4,926** | ✅ 总量逐值相同（逐格分布有迁移差异：`0_0` 916→925、`0_1` 226→205、`-1_1` 205→207 等 7 格；B 世界 19 格**逐格**相同） |
| 粮库存（毫） | 1,085,187,066 | 1,071,755,306 | ✅ 量级一致（-1.24%） |
| 货币（流通/发行） | 100,000·273,200·token 5,000 | **同**（流通==发行） | ✅ 守恒 |
| `MARKET_FILL` / `MARKET_CREDIT_FILL` | 2,092 / 2,760 | 1,687 / 2,848 | ✅ 同量级（-19% / +3%） |
| `debtPrincipal` / `debtCount` | 4,283,250 / 1,919 | 4,200,694 / 1,932 | ✅ 同量级 |
| `LOGISTICS_CAPACITY` | 2,736 | 4,410 | 预期方向 |

⇒ **回归通过**：多区原生世界照样跑满 360、落 revision、0 异常、人口/货币守恒逐值不变。

---

## 5. 我没做 / 没验证（如实）

1. **未跑任何 Maven**（`compile`/`test`/`verify`/`package`/`spotless`）：jar 由控制方构建，我只核身份（md5 + 常量 + 时间序 + 树干净 + 新事件运行期出现）。代码门禁**不在本报告结论内**。
2. 未跑 3600/3650 tick、`--world=three-powers`、N7 两跑确定性复跑、FX 部分成交、以及带 GM 家户补丁的世界。
3. **A 世界真烧趟次 62→35 未逐笔归因**：两世界自 day 3 起数值分叉（B 亦然），差异里既有"门槛生效"也有"世界分叉"，不能读成同一世界的净效应；我只给了 35 个消失 (日,户) 的被拦行映射（23 short / 5 budget / 7 无被拦行）。
4. `budget-exhausted` 是否属"过严、应收窄"：只给读数与代码依据（`MerchantCapacityPool.java:676-691` 判据量 = `min(本轮预算余量, 当刻可用量)`，**被拦不扣预算**（`:703` 只在放行时扣）；预算 = **装配时点**可用量 `:259-267`）——**需控制方/用户裁定**，我未改代码。
5. **B 世界债务塌陷的因果未证明**（只有相关性 + 逐格读数，见 §6-2）；未逐笔核对"消失的债务是否都挂在被拦承运腿的信用链上"。
6. 未拆 `MARKET_UNFILLED` 各 reason 的分侧（买/卖）、未逐值核对 `sharePerMille`/`runsAffordable` 算式、未覆盖 `MARKET_PORT_*`/三层税（不在题面）。
7. 未对新增逐户 DEBUG 行的**日志量增长**做正式上界核对（只报实测 506 / 2,257 行）。

---

## 6. 新发现的缺陷 / 账本被证伪处（只报不改）

1. **★★ 实现账本 §3-5/§7-3 的"`tool-budget-exhausted` 生产路径不可达"被实测证伪**：A 世界出现该 reason **29 行**、
   `budgetBlockedRuns` 合计 **5,824 趟**（占 A 全部被拦 94,813 的 6.1%）；35 个"上一轮真烧、本轮消失"的 (日,户) 中有 **5 个**归因到它。
   现场样本：`stockMilli=1610 availableMilli=1610 neededMilli=1000` 却因**装配时点的预算镜像 < 1000** 被拦
   （即 fix 账本 §4-1 自述的"轮内买到工具"偏离），方向 fail-closed，但**比题面"判据 = 当刻可用量"更严**。
   ⇒ 需要裁定：是收窄成"纯当刻可用量"，还是把该偏离写成正式口径（B 世界该支 = 0）。
2. **★ B 世界债务存量塌陷 -98.3%**：`debtPrincipal` 1,176,074 → **20,372**、`debtCount` 1,466 → 1,092，**19/19 格全变**
   （每格均额 ~3,090 → ~430；`0_1` 33,682→426、`1_-1` 95,547→428），而信用成交几乎不变（`MARKET_CREDIT_FILL` 6,256 → 6,200）。
   A 世界债务几乎不变（4.28M/1,919 → 4.20M/1,932）⇒ 这是缺省世界特有的大额副作用，**未归因**。
3. **`MARKET_UNFILLED` 总量与成交同降**（B 26,547→25,419 而 `MARKET_FILL` -31%）：即被拦的承运不只是"变成未成交"，
   而是**挂单量本身也变少了**（卖侧冻结链少跑）；`LOGISTICS_CAPACITY` 只 +9.7%（B），远小于被拦的 4,102 趟
   ⇒ 用 `LOGISTICS_CAPACITY` 当"运力不足"的总量指标会严重低估（A 世界 +61% 才接近）。
4. **`MERCHANT_CAPACITY_HOUSEHOLD` 仍不带 `day`**（g3b §6-3 的老缺陷未修）：逐户池读数无法按日对齐，本轮只能靠 `MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT`（带 day）代替。
5. 新逐户 DEBUG 行实测 **506（B）/ 2,257（A）** 行；fix 账本 §7-4 的"上界 ≈ 池内被拦户 × 市场日"量级估计成立（A 38 户 × 110 日 = 4,180 上界内）。

---

## 7. 复现命令（关键几条）

```bash
# ── jar 身份（Q1）
cd /home/cna/SimulatorMosire
git rev-parse HEAD                     # c7f38fedb4fb2cf7b16bae80f6e300c973c6484a（09:11:19）
md5sum simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar   # 43a283645a85e1115b5b4bd110dad350（09:16:07）
find . -name '*.java' -newermt '2026-10-10 09:15:00' | wc -l  # 0
unzip -p simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar io/mosire/simos/economy/time/MerchantHaul.class | strings | grep -c tool-budget-exhausted            # 1
unzip -p simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar io/mosire/simos/economy/time/MerchantCapacityPool.class | strings | grep -c MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT   # 1

# ── 装置 B（Q2/Q3）：缺省 small-world 0→360
bash /tmp/g3c-runB.sh                  # 含起停；日志 /tmp/g3c-runB.log
python3 /tmp/g3c-count.py /tmp/g3b-runB.log /tmp/g3c-runB.log   # 上一轮 3589/928/708 vs 本轮 0/0/0

# ── 装置 A（Q4）：原生多市场区 0→360（GM 脚本按端口 sed 复制）
bash /tmp/g3c-runA.sh                  # 起 → setup1 → 0→5 → setup2 → 5→10 → 10→360 → dump → 停
python3 /tmp/g3c-count.py /tmp/g3b-runA.log /tmp/g3c-runA.log   # 362/137/88 → 0/0/0

# ── 拦截分支与"真烧趟次"（Q3 / §6-1）
grep -c 'event=MERCHANT_HAUL_RUN .*toolBurnedMilli=0' /tmp/g3c-runB.log /tmp/g3c-runA.log   # 0 / 0
grep -c 'reason=tool-budget-exhausted' /tmp/g3c-runA.log                                    # 29（fix 账本称不可达）
grep -m1 'MERCHANT_HAUL_TOOL_SHORT_AT_COMMIT .*day=3 household=hh--1_-1-urban-landlord' /tmp/g3b-runB.log
grep -m1 'MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT .*day=3 household=hh--1_-1-urban-landlord' /tmp/g3c-runB.log
```

**产物清单**：日志 `/tmp/g3c-runB.log`、`/tmp/g3c-runA.log`（+ 重启 dump 用的 `g3c-runB2.log`）；
读数 `/tmp/g3c-state-{a,b}.json`；推进结果 `/tmp/g3c-adv{B,A-*}.txt`；脚本 `/tmp/g3c-run{B,A}.sh`、`/tmp/g3c-dumpB.sh`、
`/tmp/g3c-count.py`、`/tmp/g3c-setup{1,2}.py`；store `/tmp/simos-g3c-store-{a,b}`（9.7M / 5.5M，已停进程）。

---

## ★ 2026-10-10 追加更正（留痕，不改上文）

**§6-3 的"`LOGISTICS_CAPACITY` 严重低估运力不足"应改写为"单位不同 + 混档"。**
只读诊断结论：它是**逐槽位 / 日终残余**归因（产生点 `EconomySettlement.java:2133-2151`，
源 `MarketSettlement.java:7540-7606`），而"4,102"是**池内条目 × select 调用**（单条 `(day,户)` 可 55 次），
两者量纲不同；且**缺工具与缺运力被记为同一档**（flag 三落点 `MarketSettlement:6773-6786/6820-6827/5290-5298`）——
不是漏算一半。B 的 Σquantity 反而 **−10.8%**。详见诊断账本 §Q3。
