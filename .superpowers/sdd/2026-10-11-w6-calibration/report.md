# W6 标定读数（2026-10-11）—— 给用户拍板五个数

> **只读批**：本批不动仓库任何源码/测试/文档。开工 `git status --porcelain` = 空；收尾 = 1 行（**不是本批写的**：设计书被追加 v3.34，mtime 22:04:49）——见 §1.6 与 §5.12。
> 权威：`docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（v3.23 标定节 + v3.24–v3.33 §7 后续裁定）。
> 装置：`scripts/w6run.py` = W6 总验收装置 `/tmp/w6acc/w6run.py` 的**逐字副本**（仅改 OUT/DUMPS/cwd 三处落点）⇒ 与 W6/W7e/W9d/W9e/W9f **同一台尺**。
> **所有读数由当前代码（HEAD `5e64685e`，jar md5 `24c8a0a2…`）重跑产出**；历史批 `.superpowers/sdd/2026-10-11-w6-acceptance/`（W5.5 时代）的数**一律不作为现值引用**，只在必要处标"旧数"作对照。

---

## §0 一页纸：五个数 × 现值/出处/可调性/实测/调档后果

**口径**：实测世界 = `small-world`(sw) 与 `three-powers`(tp)，**均从创世新播 0→600**；窗口标注见每格。
**"往哪调会怎样"全部是【推理】**（除注明实测者）——算式写在 §2 各节。

| # | 数（现值） | 出处 `file:line` | 今天能 GM 调吗 | 实测（本批，0→600） | 往**大**调一档【推理】 | 往**小**调一档【推理】 |
|---|---|---|---|---|---|---|
| **1** | `FAMINE_MAX_PPM_PER_TICK = 1000`（=1‰/tick 上限） | `simos-social/src/main/java/io/mosire/simos/social/population/FamineMortality.java:48` | ❌ **常量，无任何写口** ⇒ 需**新写口**（改一个常量可临时改值，但不满足"GM 可调"） | **饥荒死亡占全部死亡 sw 96.37%（1856/1926）、tp 92.33%（1866/2021）**；人口 **sw 4800→3311（−31.0%）、tp 6350→4916（−22.6%）**；断顿户/日 **sw 中位 92/101 户、tp 113/175 户**；**faminePpm 顶格(==1000) 占死亡 99.25%/99.89%、占逐户·日 98.67%/99.65%** ⇒ **长期顶格，不是"接近"上限而是"贴死"** | 1000→2000：日饥荒死亡率 1‰→2‰ ⇒ 人口衰减约**翻倍**（sw 约 −50%） | 1000→500：衰减约**减半**（sw 约 −15%）；但**断顿结构不变**（顶格占比不降，只是每人死得慢） |
| **2** | `SLIDE_SPEED_PER_MILLE_PER_RATIO = 100`<br>`SLIDE_COST_PER_LEVEL = 1000`<br>`SLIDE_ROUND_DAYS = 5`（=`MARKET_RESTOCK_INTERVAL_DAYS`） | `simos-economy/.../time/ClassSlideSettlement.java:77` / `:83` / `:86`；`MarketSettlement.java:125` | ❌ **三个都是常量，无写口** ⇒ 需**新写口**（现只可改常量） | **600 天滑落 sw 46 次/45 户（25.3% 户）、tp 54 次/51 户（14.9%）**；**每次恰好一级（`levels=1` 46/46、54/54，不跳级）**；speed 与 requiredDays **逐值符合算式 0/46、0/54 不符**；本级停留 `elapsedDays` **sw 中位 210 天、tp 235 天**；**滑落少的主因不是斜率**：`skippedNoLowerTarget` **57.8%(sw)/39.7%(tp)**、`skippedNoDebt` 37.3%/54.8%、`skippedCooldown` 4.7%/5.0%（Σevaluated 25680/48096 户·轮） | 斜率 100→200：`requiredDays=⌈5000÷speed⌉` 减半 ⇒ 达标更早 ⇒ 滑落次数**约 ×2**；但受 `noLowerTarget`(57.8%) 与 `noDebt` 封顶 ⇒ 实际增幅 **< 2×** | 斜率 100→50：每级天数翻倍 ⇒ 滑落次数**约减半**；`SLIDE_COST_PER_LEVEL` 1000→2000 也等效于减半（它只进 requiredDays 的分子） |
| **3** | `MINT_MILLI_PER_FURNACE_CYCLE = 500`（毫/规模·周期）<br>`MINT_LABOR_SHARE_PER_MILLE = 30`（‰ 城镇池日劳动） | `simos-app/.../world/EconomySeeder.java:4011` / `:599` | ⚠️ **两个都只在"创世播种"时消费**（写进世界载荷 `moneyOutputPerUnit` / 劳动配额）⇒ 运行期**改不了**；有 `economy.UpsertIndustry`（GM-only，窄工具 `simos.economy.upsertIndustry`）+ `economy.UpsertGovUnit`（`simos.economy.upsertGovUnit`）可**追加第二座铸炉**，但**原地替换被引用的产业被具名拒**、也**没有删 unit 的写口** ⇒ 要"改既有铸炉产出率"需**新写口**。★ 真正 GM 可调的是**政策旋钮** `Government.mintScalePerCycle`（`economy.SetGovernmentMintScale` + `simos.economy.setGovernmentMintScale`） | **sw：4 笔 `MINT_PRODUCTION`，各 500 毫，共 2000 毫 = m0 367,200 的 +0.545%**（d120/240/360/480）；**第 5 轮（d600）没产**——工具投入耗尽（`cycleInputPerUnit={WORKSHOP:{tool:2000}}`、创世工具 8000 ⇒ 只够 4 轮；d600 该户 `tool` 余额 = 0）；**政策上限 2000 毫/轮从未成为瓶颈（实际 500）**；**tp：0 笔**——three-powers **根本没有铸币产业**（sw 45 个产业**有** `mint@0_0`；tp 83 个产业**没有** `mint@`），且 4 个政府 `mintScalePerCycle` 全 0 ⇒ m600 == m0 | 500→1000：每轮 1000 毫 ⇒ 仍只 4 轮（工具仍限）⇒ 累计 4000 毫 = **+1.09%** | 500→250：累计 1000 毫 = **+0.27%**；`MINT_LABOR_SHARE_PER_MILLE` 30→15 会把配额打到 `349160×15‰ ≈ 5237`（再按批次摊薄 ≈9.9% ⇒ **≈4,719 < 8,000**）⇒ **规模 0 ⇒ 铸币彻底停产**（实测该比值链：requested 10474 → granted 9440 ÷ perUnit 8000 = **1**） |
| **4** | `DEFAULT_LOAN_RATE_ALPHA_PER_MILLE = 50`（自适应速率 ‰/轮）<br>`DEFAULT_LOAN_RATE_PER_MILLE = 20`（基准利率 ‰/周期） | `simos-economy/.../model/MarketTable.java:110` / `:103` | **α：✅ 有写口**（`economy.SetMarketZoneLoanRateAlpha`，窄工具 `simos.economy.setMarketZoneLoanRateAlpha`）<br>**基准 20‰：❌ 无写口**（只在 `MarketTable` 构造默认；`economy.DefineMarketZone` 载荷不含它） | **sw 240 轮**：需求中位 **3,294,504 毫** vs 供给中位 **977 毫**；**Σlent/Σdemand = 0.02%**；利率 **20‰ → 峰值 252,761‰**（d600 价表 41,987‰）；池**全空**轮次 **0/240**（但 204 条 `MONEY_MARKET_UNFILLED` **100% 归因 `pool-exhausted`**）；**未偿 `money:silver` 8.03×10¹⁴ 毫 vs 全世界银 269,200 毫 = 2.98×10⁹ 倍**（d480 起爆）。<br>**tp 720 轮（3 区）**：Σlent/Σdemand = 0.10%；利率中位 **91.5‰**、峰值 **3,407‰**；**真池空 17/720 轮**；未偿 `money:{copper 7.85×10¹⁰, silver 2.81×10⁸, gold 1.33×10⁸}`（也爆但远小于 sw） | α 50→100：利率自适应更快 ⇒ **更早爆表**（【推理】；量级不变） | α 50→0：利率恒 20‰（不涨），**D≫S 仍在 ⇒ 该借不到的仍借不到**，但债务不再指数爆（对本世界 ≈ 关掉利率信号） |
| **5** | `MIGRATION_SPEED_CAP_PER_MILLE = 100`（每市场轮每户可转人口上限）<br>`PROFIT_GAP_SPEED_SLOPE_PER_MILLE = 100`（收益差斜率） | `simos-economy/.../time/ModeMigrationPolicy.java:162` / `:134` | ❌ **两个都是常量，无任何写口**（代码类注自述"GM 写口本批只具名、未实现"）⇒ **需先补写口**（宿主应为**世界级政策组件**，不是逐区 `MarketZone`、也不是逐辖区 `Government`） | **迁移 sw 258 笔/43 户、tp 554 笔/84 户**（20 轮，每 30 天一轮）；速度 **sw min/中位/max = 10/67/101、tp = 5/99/126**；**>100‰ 各只有 1 笔（0.39%/0.18%）、==1000‰ 0 笔**；**逐值判别：闸门在结果上从未限制过任何一笔**（sw 0 户·轮生效；tp 1 户·轮算术生效但该户只迁出 1 人 < 允许的 3 人）；市场 **Σfills 2911、零成交轮次 0/240**；转业失败理由首档是 `NO_ASSET:<产业>@<hex>`（缺作业资本）与 `DEPENDENT` | **cap 100→1000：几乎无效果**（实测：cap 现在根本不生效）；**斜率 100→200 才会真起作用**（速度翻倍 ⇒ 更多笔触到 cap ⇒ 世界搬得更快，正是 W9d 踩过的坑） | cap 100→50：**会真夹住**（中位速度 67/99、大量笔在 61–100 区间）⇒ 迁移量下降（更稳但更钝）；斜率 100→50：迁移量约减半 |

### 运维读数（同批实测）

| 项 | 读数 |
|---|---|
| **性能 0→360 (one-shot, DEBUG+TRACE)** | **本批 3 样本**：sw **11.291 / 11.851 / 14.202 s**（中位 11.851）；tp **48.223 / 53.908 / 56.434 s**（中位 53.908）。<br>**同机同会话三 jar 交错各 3 次**（见 §2.6）：W9-fix `c29ba84c` **10.637/9.424/9.334**（中位 9.424）、W9-fix2 `ce0bf3ba` **8.976/10.596/10.042**（中位 10.042）、HEAD `24c8a0a2` **9.510/9.128/8.985**（中位 9.128）。<br>★ **结论：三个 jar 在统计上不可区分**（区间互相重叠），**9 次 dump 逐字节相同**（`89bbecfa…`）⇒ **"W9f 10.650 vs W9e 8.226" 是跨会话方差，不是代码变慢**；本机同二进制同世界的墙钟带约 **9.0–14.2 s** |
| **600 天逐日推进（装置累计）** | sw **105.172 s**（601 天）、tp **233.317 s** |
| **人口曲线（每 30 天，时点）** | sw 4800→(d120)4442→(d240)4227→(d360)3876→(d480)3631→**3311**；tp 6350→5889→5613→5434→5225→**4916** |
| **货币总量（时点，对照 m0）** | sw **m0 367,200 → m600 369,200（+0.545%**，全部来自 4 笔铸币）；tp **m0 = m600 = 690,600（Δ=0**，无铸币产业） |
| **滑落/转业** | 滑落 sw 46/tp 54；转业 sw 258/tp 554 |
| 产物落点 | `.superpowers/sdd/2026-10-11-w6-calibration/{store,daily,dumps,logs,readings,results,scripts,plans}` |

---

## §1 装置（可复现）

### 1.1 构建与 jar
- 构建前 `pgrep -af "surefirebooter|classworlds.launcher"` **无命中**（本仓无 Maven 在跑）；**无任何 java 服务在跑**。
- 命令：`tools/mvn-lock.sh -DskipTests package -pl simos-app -am`（日志 `logs/00-build.log`，`BUILD SUCCESS`，3.375 s ⇒ 无源码需重编）。
- **产物新鲜度自证**（AGENTS §三.5 纪律：先核产物再判"两个结果"）：逐模块比"最新 `src/main/*.java` mtime" vs "最旧 `target/classes/*.class` mtime"，**12/12 模块 OK**（最旧 class 均新于最新源码）⇒ jar 不含陈旧 class。
- **jar md5 = `24c8a0a2291f5cdbeaff49edca71a13b`**（`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，27,195,899 B）。
- 起服务一律走 `tools/run-shaded.sh`（把 jar 拷成进程独占快照再 exec）⇒ **本批任何 `mvn package` 都不会踩到"运行中 jar 被就地重写"**。

### 1.2 脚本与 md5（本批目录内）
| 文件 | md5 | 说明 |
|---|---|---|
| `scripts/w6run.py` | `f93ab3af1555a4378aa789dbe8ca4b3e` | W6 装置逐字副本（原始 `/tmp/w6acc/w6run.py` md5 `b7b8b0f501e19a643e8470fa342689d3`；**只改 `OUT`/`DUMPS`/子进程 `cwd` 三处落点**） |
| `scripts/w6lib.py` | `5253b7f7bb7a61d5059d67be13d702c8` | 逐字副本（逐日紧凑读数 extract） |
| `scripts/run-worlds.sh` | `de321ed10a6c550dcd9cb8a2ba1ff75b` | 长跑 + 性能驱动 |
| `scripts/ab-jars.sh` | `0c2b4eae10e6eb4bbcf56160c7a54296` | 三 jar 交错 A/B |
| `scripts/calib5.py` | `efd25b90e191b52300408187af4e7928` | 五个数读数抽取（本报告 §2 全部数字的生成器） |
| `scripts/gate_probe.py` | `0bb09535f68d243379c4631f88f5d9a9` | ⑤ 人口闸吃紧的逐值判别 |

### 1.3 运行清单
见 `run-inventory.txt`（tag / jar_md5 / store / 端口 / Δrev / tick / PID / ready s / advance s / logStrippedMd5 + 全部 dump 与逐日读数 md5）。
- **世界与端口**：`sw600` 10901、`tp600` 10902、`sw360perf1-3` 10905、`tp360perf1-3` 10908、`ab360-*-r1-3` 10921–10929。
- **JVM 开关**（逐字）：`-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE -Dsimos.social.logLevel=DEBUG -Dsimos.social.traceLevel=INFO -Dsimos.app.logLevel=DEBUG`
  （★ 派单要求 `economy.logLevel=DEBUG`；**另加 `traceLevel=TRACE`** 是因为 `MIGRATION_MOVE` / `MONEY_MARKET_LOAN` 两条逐笔读数只在 TRACE 分类下发射，且这正是 **W9f 的性能口径**（其 sw360 = DEBUG+TRACE）⇒ 性能可比。`social=DEBUG` 是为了 `FAMINE_MORTALITY_HOUSEHOLD` 逐户读数；`app=DEBUG` 是为了创世 `SEED_MINT_LABOR` 一行——实测几乎不增行数（+1 行/60 天）。）
- **`--log-keep` 正则**（逐字见 `run-inventory.txt`）：排除已知逐户洪泛事件（`CAPACITY_DEMAND_INSTANCE`/`HOUSEHOLD_GAP`），保留 §2 需要的全部读数事件（含 `MARKET_FILL`，用于核 `Σfills == MARKET_FILL 行数`）。

### 1.4 store / 逐日读数 / dump
- store：`store/sw600`（233 MB）、`store/tp600`（419 MB）；`--prune` ⇒ 每个 store 只留最新 checkpoint。
- 逐日读数：`daily/daily-sw600.jsonl.gz`、`daily/daily-tp600.jsonl.gz`（**各 601 行 = day 0..600**，W6 `daily/*.jsonl.gz` 同形制）。
- dump：`dumps/sw600-d600.json`（8,409,214 B，md5 `a8206a67…`）、`dumps/tp600-d600.json`（13,604,548 B，md5 `d30e219b…`）；性能/A-B 的 d360 dump 共 15 份（见 §2.6）。

### 1.5 起停
- 起：`bash tools/run-shaded.sh <jar> --store … --world=… --config … --gui-port …`；装置等 `GET /api/economy/overview` 返回 `activated=true` 记 ready。
- 停：**装置对该次进程 PID 发 `SIGTERM`**（`proc.send_signal(SIGTERM)` → `wait(180)` → 兜底 SIGKILL），**全程未用 `pkill -f`**。
- 收工核对：`pgrep -af "simos-shaded|classworlds"` 无命中；`df -h /` 41 GB 可用（跑前 42 GB）。

### 1.6 硬纪律核对
- **`git status --porcelain`**：**开工 = 空**；**收尾 = 1 行**（` M docs/superpowers/specs/2026-10-10-…-design.md`，+17 行 = 追加 v3.34，**mtime 22:04:49，不是本批写的**——本批全程只读；未回滚、未接触）。详见 §5.12。
- 本批全部产物落 `.superpowers/sdd/2026-10-11-w6-calibration/`（`.superpowers/sdd/.gitignore` = `*`，gitignore 下，控制方按需 `git add -f`）。
- 大文件已清/压：三份 1.4 GB 的 tp 性能日志 + 性能 store 已删（时间与 md5 留在 `logs/*.result.json`）；两份 600 天日志与去时间戳版已 gzip（1.8 GB + 1.7 GB → 62 MB）。

---

## §2 五个数的实测细则（含算式与窗口）

> 生成器：`python3 scripts/calib5.py --tag <sw600|tp600> --daily daily/daily-<t>.jsonl.gz …`；全量输出在 `readings/<tag>-readings.txt`（人读）与 `-readings.json`（机读）。
> **窗口口径（§九.4）**：人口/货币/满足率/国库 = **时点**；死亡/faminePpm/铸币/滑落/迁移/市场轮 = **当日（或当轮）发生额**；"累计"= 0..600 全窗求和。均已在读数文本里逐段标注。

### 2.1 ① 饿死率快慢 · `FAMINE_MAX_PPM_PER_TICK = 1000`

**算式**（`FamineMortality.java:76-83`）：
```
faminePpm = ⌊FAMINE_MAX_PPM_PER_TICK × starvationRatioPerMille ÷ 1000⌋      // 线性、向下取整
starvationRatioPerMille = ⌊rationGapMilli × 1000 ÷ rationNeedMilli⌋          // 当日已实现的口粮缺口
若 gap ≤ 0 ⇒ 0
实际日死亡率 = 基准率 + faminePpm（基准：0-14 岁 67 ppm、15-59 岁 33 ppm、60+ 667 ppm；SocialVitalRates.java:23/50-51）
```
⇒ **faminePpm 的上限 1000 ppm = 成年基准 33 ppm 的 30 倍**（成人死亡率 33 → 1033 ppm）。

**实测（sw600 / tp600，0→600）**

| 读数 | sw600 | tp600 |
|---|---|---|
| 饥荒死亡占比（累计） | **96.37%**（1856 / 1926；BASE 70） | **92.33%**（1866 / 2021；BASE 155） |
| 人口净变化 | 4800 → **3311**（−1489，**−31.02%**） | 6350 → **4916**（−1434，**−22.58%**） |
| 断顿户/日（satiety<1000） | min 66 / **中位 92** / max 97（分母 n 中位 101） | min 72 / **中位 113** / max 163（分母 175） |
| 其中 satiety==0（一点没吃上） | 中位 **81** 户/日 | 中位 **84** 户/日 |
| 全额满足户占比（逐日） | min 0.0% / **中位 5.9%** / max 32.0% | min 0.0% / **中位 36.7%** / max 58.9% |
| 平均满足率 `Σsatiety/(1000n)` | min 0.041 / **中位 0.103** | min 0.106 / **中位 0.462** |
| **faminePpm 顶格(=1000)** — 死亡事件口径 | **1842/1856 = 99.25%** | **1864/1866 = 99.89%** |
| **faminePpm 顶格(=1000)** — 逐户·日口径（`FAMINE_MORTALITY_HOUSEHOLD`） | **47003/47637 = 98.67%**（均值 994.1） | **57940/58141 = 99.65%**（均值 999.3） |
| `maxStarvationRatioPerMille` == 1000 的天数 | **600 / 600** | **600 / 600** |
| 逐 30 天人口曲线 | 4800, 4720, 4634, 4549, 4442, 4365, 4327, 4271, 4227, 4136, 4067, 3965, 3876, 3811, 3751, 3689, 3631, 3546, 3474, 3402, 3311 | 6350, 6231, 6111, 5998, 5889, 5762, 5703, 5650, 5613, 5552, 5519, 5470, 5434, 5393, 5350, 5291, 5225, 5132, 5066, 4987, 4916 |
| **谁在饿**（逐户 `FAMINE_MORTALITY_HOUSEHOLD` 按户类型计数，sw600 共 47637 行） | `rural-middle_peasant` 10303、`rural-rich_peasant` 9919、`rural-poor_peasant` 9379、`rural-landless_laborer-displaced` 8834、`rural-landlord` 6948；**城市各类合计 ≈ 2254**（urban-landless 536 / poor 494 / middle 454 / rich 439 / landlord 331）⇒ **农村 : 城市 ≈ 95 : 5**，且**农村各阶层都在饿**（含地主/富农） | （未逐类型统计，见 §5.8） |

**读法**：`FAMINE_MAX_PPM_PER_TICK` **不是"接近上限"，是"贴死上限"**——98.7%/99.65% 的"断顿户·日"其 `starvationRatio == 1000`（当日口粮缺口 = 100%）。⇒ 这个旋钮在本世界里是**纯线性缩放器**：调它只改"饿死多快"，不改"谁饿"。
**对照旧数（W5.5 时代，不作为现值）**：旧读数 `faminePpm=1000`、饥荒占 96%、sw 4800→3312。本批 sw = **3311**（差 1 人 / 600 天）⇒ **人口曲线几乎未变**（W7/W8/W9 未改动饥荒支）。

**调档后果【推理】**：日饥荒死亡率 ≈ `faminePpm ppm`（线性、无阈值）。1000→2000 即 1‰→2‰/日 ⇒ 若断顿户集合不变，人口衰减约翻倍（sw 约 −50%、tp 约 −40%）；1000→500 约减半。★ 但"线性 vs 带阈值/超线性"这个设计问题在本世界**读不出来**：因为 `ratio` 恒 1000，任何**单调且过原点**的形状在此点取值相同 ⇒ 要标定形状，得先让世界出现"部分断顿"（见 §4 结论）。

### 2.2 ② 阶层滑落斜率 · `SLIDE_SPEED_PER_MILLE_PER_RATIO / SLIDE_COST_PER_LEVEL / SLIDE_ROUND_DAYS`

**算式**（`ClassSlideSettlement.java:380-391`；`SLIDE_ROUND_DAYS = MarketSettlement.MARKET_RESTOCK_INTERVAL_DAYS = 5`，`MarketSettlement.java:125`）：
```
ratioPerMille  = ⌊债 × 1000 ÷ max(1, 资产)⌋
speedPerMille  = ⌊ratio × SLIDE_SPEED_PER_MILLE_PER_RATIO ÷ 1000⌋，夹到 [0, 1000]
requiredDays   = ⌈SLIDE_ROUND_DAYS × SLIDE_COST_PER_LEVEL ÷ speed⌉ = ⌈5000 ÷ speed⌉
触发：市场轮末，该户在本级已停留 elapsedDays ≥ requiredDays ⇒ **恰好降一级**（不跳级），并进 cooldown
```

**实测**（`CLASS_SLIDE` 逐笔 + `CLASS_SLIDE_ROUND` 逐轮；窗口 = 累计 0→600）

| 读数 | sw600 | tp600 |
|---|---|---|
| 滑落次数 / 涉户 | **46 次 / 45 户**（占 178 户的 25.3%） | **54 次 / 51 户**（占 342 户的 14.9%） |
| 滑落轮数 | 240 | 240 |
| 每户每轮滑落率 | 0.1077% | 0.0658% |
| **是否恰好一级** | `levels` 分布 = **{1: 46}** | `levels` 分布 = **{1: 54}** |
| **逐值核对 `speed == ⌊ratio×100÷1000⌋`** | **不符 0 / 46** | **不符 0 / 54** |
| **逐值核对 `requiredDays == ⌈5000÷speed⌉`** | **不符 0 / 46** | **不符 0 / 54** |
| 本级停留 `elapsedDays` | min 48 / **中位 210** / max 513 | min 62 / **中位 235** / max 423 |
| `ratioPerMille` | min 139 / 中位 821 / max 6500 | （见读数文本） |
| `speedPerMille` | min 13 / 中位 81.5 / max 650 | — |
| `requiredDays` | min 8 / 中位 61.5 / max 385 | — |
| **滑落为什么少（Σ跳过原因 / Σevaluated）** | evaluated **25680**；`skippedNoLowerTarget` **14854 (57.8%)**；`skippedNoDebt` 9577 (37.3%)；`skippedCooldown` 1203 (4.7%)；`unpricedDebtHouseholds` 3115 (12.1%) | evaluated **48096**；`skippedNoDebt` **26373 (54.8%)**；`skippedNoLowerTarget` 19074 (39.7%)；`skippedCooldown` 2423 (5.0%)；`unpricedDebtHouseholds` 8691 (18.1%) |
| 主要层级迁移对（sw） | `DIRECT_LABORER/WAGE_EARNER(1,1)→DIRECT_LABORER/DEPENDENT(1,0)` 21 次；`OWNER/SURPLUS_RECEIVER(4,3)→OPERATOR/SURPLUS_RECEIVER(2,3)` 19 次 | — |

**读法**：**当前约束不在斜率**。`elapsedDays` 中位 210 天 ≫ `requiredDays` 中位 61.5 天 ⇒ 达标门槛早就过了；真正卡住的是 **(a) 多数户没有债务**（noDebt 37–55%）与 **(b) 有债的户常常已在最低档/无更低目标**（noLowerTarget 40–58%）。
**对照旧数**：W6 旧读数"滑落 sw 41 次/40 户、tp 25 次/23 户，每次恰好一级" ⇒ 本批 sw 46/45（+12%）、tp 54/51（**+116%**，W9 转业改变了阶层构成）——**旧数不可当现值**。

### 2.3 ③ 铸币产出率 · `MINT_MILLI_PER_FURNACE_CYCLE = 500`（`EconomySeeder.java:4011`）/ `MINT_LABOR_SHARE_PER_MILLE = 30`（`:599`）

**算式**（`MintSettlement.java:45-49`、`EconomySeeder.java:3976-4011`、`MintSettlement.java:165/278/341`）：
```
单位规模 scale = min(劳动链, 产能链, 投入链)；劳动链 = ⌊周期平均日劳动 ÷ MINT_LABOR_MILLI_PER_FURNACE(8000)⌋
毛产出(币种 c) = scale × moneyOutputPerUnit.perUnit[c] = scale × MINT_MILLI_PER_FURNACE_CYCLE(500)
闸门：本轮铸币 ≤ min(mintScalePerCycle, 上一铸币轮印量)（首轮无上一轮 ⇒ = mintScalePerCycle）
关账日：mint 产业的 cycleDays = 120 ⇒ 每 120 天一产
```
**实测（sw600）**：`MINT_PRODUCTION` @ **d120 / d240 / d360 / d480 各 500 毫 silver**（共 **2000 毫**）；**d600（第 5 轮）无产出**。
- 创世读数（`SEED_MINT_LABOR`，app DEBUG）：`poolDailyLaborMilli=349160 sharePerMille=30 requestedLaborMilli=10474 slackAvailableMilli=5018977 grantedLaborMilli=9440 perUnitLaborMilli=8000 unitScalesSupported=1`
  ⇒ **30‰ 的份额刚好跨过 1 个规模**（9440 ≥ 8000），余量 18%（与 v3.17 "选 30 而非 ≈26" 一致）。
- `MINT_LIMIT_WINDOW`：d240/360/480/600 均 `mintScalePerCycle=2000 limit=2000 previousCycleMinted=500` ⇒ **政策上限从未成为瓶颈**（差 4 倍）。
- **第 5 轮为什么不产（具名到常量）**：`EconomySeeder.java:4240` `MINT_SEED_CYCLES = 4` ⇒ 创世工具库存 = `furnaces(1) × 4 × MINT_TOOL_MILLI_PER_FURNACE(2000) = 8000`；`cycleInputPerUnit={WORKSHOP:{tool:2000}}` ⇒ **恰好只够 4 轮**；d600 该政府家户（`hh-gov-world-silver`）**`tool` 余额 = 0** ⇒ 投入链 = 0 ⇒ scale = 0。**这是投入约束按设计生效（"吃完要么买、要么停工"），不是 bug**（v3.17 已预言"600 tick ⇒ 只有 4 条"）。
- **货币总量（时点）**：`m0 = 367,200`（copper 100,000 + silver 267,200）→ `m600 = 369,200`（silver 269,200）⇒ **Δ = +2000（+0.545%）**；`issuance_identity` = `{copper: 100000, silver: 269200}` **逐币种自洽**。
- **国库曲线（时点，每 120 天，silver）**：`world-silver` `{}`→500→1000→1500→**2000**；`gov-unit-gov-central` 100,000→96,160→92,320→88,480→**80,800**（军俸/行政支出）；`gov-unit-gov-province` 100,000→152,154→160,790→168,695→**172,889**。
  ★ **铸币量（2000）远小于国库再分配量（±19,200）** ⇒ 铸币对货币面目前不是主导项。
**实测（tp600）**：**0 笔**。three-powers 的产业种类 = `craft/farm/office/trade/weave`（**无 `mint`**），4 个政府 `mintScalePerCycle` 全 **0**；`m600 == m0 == 690,600`（Δ=0）。
  ⇒ 根因（回代码核）：`EconomySeeder.seedGovernmentMint` 只在 `seeding.governmentHousehold()` 存在时调用（`EconomySeeder.java:3819-3826`，注释自述"**demo 世界才铸币**"）⇒ **两个世界只有 sw 会铸币**。**"铸币产出率"在 tp 上无论怎么调都是 0。**

**对照旧数**：设计书 v3.24 写"铸币 sw 500 毫/120 天（600 天货币总量 **+0.68%**）"（= 2500 毫 ⇒ 5 轮），而 v3.17 写"600 tick ⇒ **只有 4 条**"。**本批实测 = 4 条 / +0.545%** ⇒ v3.24 的 +0.68% 与本批不符（**记为一处旧数偏差**，以本批为准）。

### 2.4 ④ 借贷利率 · `DEFAULT_LOAN_RATE_ALPHA_PER_MILLE = 50` + 默认 20‰

**算式**（`MoneyMarketSettlement.java:344`、`MarketTable.java:103/110/114`）：利率与商品价格同一形态的自适应式，`α=50‰/轮` 控制"每轮往均衡挪多少"；基准 `loanRatePerMille=20‰/周期`。

**实测（sw600；逐市场轮，240 轮；tp600 对照见下表右列）**

| 读数 | 值 |
|---|---|
| 需求 `demandMilli` | min 0 / **中位 3,294,504** / max 28,582,274；**Σ = 1,491,202,603** |
| 供给 `supplyMilli` | min 127 / **中位 977** / max 12,370；Σ = 504,775 |
| 成交 `lentMilli` | min 0 / 中位 201 / max 12,370；**Σ = 347,973** ⇒ **Σlent/Σdemand = 0.02%** |
| 未满足 `unmetMilli` | min 0 / 中位 1,048 / max 958,384；Σ = 8,700,252 |
| 放款笔数 `Σloans` | 见 `readings/sw600-readings.txt`（MONEY_MARKET_WHY 的 `loans` 栏求和）；平均每笔 = Σlent ÷ Σloans |
| **利率轨迹** | **20‰（d3）→ 30 → 40 → 60 → 93 → 145 → 231 → 370 → 597 → 964 → 1,565 → 2,544 → 4,139 → 6,737 → 10,967 → 17,857 → 29,081 → 47,365 → 77,146 → 125,657 → 204,675 → 252,761（峰值）**；d528 起 `demand = 0`（利率顶到无人借） |
| 价表 `loanRatePerMille`（时点曲线） | d0 20 → d60 48 → d120 133 → d240 1,289 → d360 13,328 → d480 138,535 → d540 143,774 → **d600 41,987** |
| **放贷池"全空"轮次** | **0 / 240**（`MONEY_MARKET_WHY` 的 `supply==0 ∧ demand>0`） | **17 / 720**（tp 有 3 个区 × 240 轮） |
| **一笔未成交轮次** | **0 / 240** —— 每轮都有一点点成交（中位 201 毫） | **17 / 720** |
| **实际放款** | **Σloans = 1346 笔**，Σ借户 863 / Σ贷户 690，**平均每笔 258.5 毫**；零需求轮次 35/240 | **Σloans = 1779 笔**，Σ借户 1081 / Σ贷户 998，**平均每笔 314.5 毫**；零需求轮次 408/720 |
| 需求 / 供给 / 成交（Σ） | 1,491,202,603 / 504,775 / 347,973 毫 | 583,212,557 / 12,218,619 / 559,482 毫（**Σlent/Σdemand = 0.10%**） |
| 利率（‰/周期） | min 20 / 中位 4,041 / **max 252,761** | min 0 / 中位 **91.5** / **max 3,407** |
| **池空（一分钱都没有）** | **0 条** | **17 条**（`pool-empty`：真正没有任何家户有闲钱） |
| **`MONEY_MARKET_UNFILLED` 归因** | **204 条，100% `pool-exhausted`**（Σ缺口 8,700,252 毫） | 见 `readings/tp600-readings.txt`（另有 `pool-empty` 一族） |
| `alphaPerMille` | 240/240 = **50**（写口现值一致） | 720/720 = **50** |
| **后果：未偿 `money:silver`** | d0 0 → d120 100,278 → d240 1,905,972 → d360 4,593,322 → d480 **1,508,381,349** → **d600 803,437,656,108,472**（≈8.03×10¹⁴）vs 全世界银 **269,200** = **2.98×10⁹ 倍**；d600 当日 `interestDue = 802,633,860,830,592` |
**读法**：**结构性 D≫S** —— 需求中位 330 万毫 vs 供给中位 **977 毫**（差 3,400 倍）。这不是 α 失灵，是"放贷池里根本没多少钱"（S-12⑥ 国库默认不出借）。利率因此被"永远填不满的需求"一路顶到 25 万‰，随后需求自己归零（d528 起 demand=0）。
**对照旧数**：v3.24 记"利率峰值 333,388‰（d525）、未偿 2.926×10¹⁴（d600）"；本批峰值 **252,761‰**、未偿 **8.03×10¹⁴** ⇒ 量级同型、数值已变（W7/W8/W9 改了货币与债务面）⇒ **旧数不可当现值**。
**与用户裁定的关系**：用户 v3.25 已裁定「**利率爆表不用管**，富人不利滚利、穷人死了账一并消除」。本批读数与该裁定不冲突，仅如实记录现值。

### 2.5 ⑤ 转业上限与斜率 · `MIGRATION_SPEED_CAP_PER_MILLE = 100` / `PROFIT_GAP_SPEED_SLOPE_PER_MILLE = 100`

**算式**（`ModeMigrationPolicy.java:134/162`、`:2113-2114`、`:2168-2180`）：
```
speedPerMille = clamp(⌊SLOPE × (收益_其他 − 收益_当前) ÷ max(|收益_当前|,|收益_其他|,规模基准)⌋, 0, 1000)
moveable      = min( max(1,⌊人口 × 最快speed ÷ 1000⌋),  max(1,⌊人口 × MIGRATION_SPEED_CAP_PER_MILLE ÷ 1000⌋) )
频率：只在**周期关账钩子**（实测每 30 天一轮）；滑落（每市场轮 5 天）先算、转业后算（v3.31 次序）
```

**实测（0→600）**

| 读数 | sw600 | tp600 |
|---|---|---|
| `MIGRATION_MOVE` 笔数 / 涉源户 | **258 笔 / 43 户** | **554 笔 / 84 户** |
| `MIGRATION_APPLIED` 轮数 / 间隔 | 20 轮 / **每 30 天** | 20 轮 / **每 30 天** |
| 落点是否新建户 | 20/20 `rowsBefore == rowsAfter`（**全部并入既有户**） | 20/20 同 |
| **速度分布** | min 10 / **中位 67** / max **101**；均值 60.8 | min 5 / **中位 99** / max **126**；均值 84.1 |
| `speed > 100‰` 笔数 | **1 / 258 = 0.39%** | **1 / 554 = 0.18%** |
| `speed == 1000‰` 笔数 | **0** | **0** |
| `speed ≤ 100‰`（闸门**不可能**生效） | 257 / 258 = **99.61%** | 553 / 554 = **99.82%** |
| Σ搬迁人口 / 期末人口 | 678 / 3311 = **20.48%** | 1271 / 4916 = **25.85%** |
| 单笔最大搬迁人口 | 18 | 11 |
| `targetMode` 分布 | wage_farm 154 / tenancy_fixed_kind 48 / handicraft_workshop 30 / merchant 26 | merchant 232 / wage_farm 176 / handicraft_workshop 125 / family_farm 21 |
| `targetTierIndex` 分布 | {0:247, 1:11} | {0:514, 1:33, 2:7} |
| **市场 Σfills / 零成交轮次** | **2911 / 0（0.00%）**，`Σfills == MARKET_FILL 行数` ✅；min/中位/max = 3 / 9 / 168 | **8472 / 0（0.00%）**，`Σfills == MARKET_FILL 行数` ✅；min/中位/max = **15 / 35 / 193** |
| 转业失败首档理由（Σ 全窗） | `NO_ASSET:farm@-1_0` 910、`NO_ASSET:farm@0_1` 846、`NO_ASSET:farm@0_-1` 840、`DEPENDENT` 346 … | `DEPENDENT` 1567、`NO_ASSET:farm@2_0` 1357、`NO_ASSET:farm@-2_2` 1342 … |
| 候选闸 | ΣcandidateRows 15950、ΣnoPop 1460、ΣnoLabor 1705 | （见读数文本） |

**人口闸吃紧比例（逐值判别，`readings/gate-probe.txt`）**——方法：闸门只在 `speed > 100` 时**可能**生效（因为两侧同为 `max(1,⌊pop×‰÷1000⌋)`）；对每笔越限迁移，从 store 的 `revisions.changeset_json` 取该 tick 的 `classes.entries[源户].population` 与 `flows.entries[源户].{births,deaths}` 反推迁出前人口 `pre = post − births + deaths`，再逐值复算两侧：

| 世界 | 越限笔（唯一） | 反推 pre | speedMoveable | capMoveable | 结论 |
|---|---|---|---|---|---|
| sw600 | d210 `hh-0_2-urban-landlord` speed=101 | **13** | 1 | 1 | **闸门不生效**（pre±3 邻域结论稳定） |
| tp600 | d270 `hh-0_-3-rural-middle_peasant` speed=126 | **39** | 4 | 3 | **闸门算术上生效**，但该户当日只迁出 **1 人 < 允许的 3 人** ⇒ **结果未被闸门限制** |

⇒ **`MIGRATION_SPEED_CAP_PER_MILLE = 100` 在 600 天内从未成为任何一笔迁移的实际约束**（生效比例的**上界** = 0.39% / 0.18%，实际"结果被限" = **0/812 笔**）。真正决定迁移量的是**斜率**（速度中位 67/99，峰值 101/126 都贴着 100 附近）与 `NO_ASSET`/`DEPENDENT` 两条硬闸。
**对照 W9f 的 222/389（0→360）**：本批 0→600 是 258/554 ⇒ 360→600 这 240 天只新增 36/165 笔（**sw 迁移在 d360 后基本停**：逐轮 d540/570/600 各 1 笔）。**旧数（222/389）是 0→360 窗口的，不可与 0→600 直接比**。

### 2.6 性能：3 样本 + 同机三 jar 交错 A/B（回答"是否真变慢"）

**(a) 本批 3 样本**（0→360 one-shot，DEBUG+TRACE，各自独立 store/端口；`advance` HTTP 200）：

| 世界 | 样本 1 | 样本 2 | 样本 3 | min / 中位 / max / 均值 |
|---|---|---|---|---|
| small-world | **11.851** | **14.202** | **11.291** | 11.291 / **11.851** / 14.202 / 12.448 |
| three-powers | **56.434** | **53.908** | **48.223** | 48.223 / **53.908** / 56.434 / 52.855 |

**(b) 决定性判别 —— 同机同会话三 jar 交错 A/B**（`scripts/ab-jars.sh`；三个 jar 的快照都还在 `/tmp/simos-shaded-*.jar`；顺序 fix→fix2→head ×3 轮，把热漂移摊平）：

| jar | 身份 | 3 样本（s） | 中位 |
|---|---|---|---|
| `c29ba84c7499c5e990c66ce6fc36d73c` | W9-fix（**W9e 那个 8.226 s 的来源**） | 10.637 / 9.424 / 9.334 | **9.424** |
| `ce0bf3ba8f388492b0f0ab8ae079c22c` | W9-fix2（**W9f 那个 10.650 s 的来源**） | 8.976 / 10.596 / 10.042 | **10.042** |
| `24c8a0a2291f5cdbeaff49edca71a13b` | 本批 HEAD `5e64685e` | 9.510 / 9.128 / 8.985 | **9.128** |

★ **9 次运行的 d360 dump 全部逐字节相同**（整文件 md5 `89bbecfa44e5e6320507d781dd5577c4`）⇒ **工作内容完全一样**。
★ 而这三个 md5 还与 **W9e、W9f 两批的 sw360 dump 完全相同**（跨批逐字节一致）。
⇒ **结论：三 jar 的墙钟区间互相重叠（9.0–10.6 s），统计上不可区分；"W9f 10.650 vs W9e 8.226"是跨会话方差（本机同二进制同世界的墙钟带 ≈ 9.0–14.2 s），没有证据说 W9-fix2/HEAD 变慢。**
⇒ 方法论：**单样本跨会话比较在本机不可用**（见 §3 异常项 A2 的同一现象）。

### 2.7 逐日读数与 dump 的确定性（顺带取证）
- **sw360 dump：本批 12 份（3 性能样本 + 9 次 A/B，覆盖 3 个 jar）全部逐字节相同**（`89bbecfa44e5e6320507d781dd5577c4`），再并入 W9e、W9f 两批各 1 份 ⇒ **同一世界跨 3 个 jar、跨 3 个批次共 14 份逐字节相同**。
- **tp360 dump：本批 3 份 + W9f 1 份逐字节相同**（`838477e41aaf5b9db732d12a424c7605`）。
- 逐日存档：sw600/tp600 的 `revisions` 表 Δrev 恰 = 600 / 600，tick 单调到 600。
- ★ **反面**：**日志**（去时间戳后）**不是**逐字节稳定的 —— 差异只来自 §3 A1 的启动竞态（0 或 15 行）；状态侧 0 差异。W6 旧结论"结算序日志逐行相同"在本机当前状态下**不成立**（差分已定位）。

---

## §3 异常 / 缺陷清单（**只报不修**，各给最小复现）

| # | 现象 | 证据（本批） | 最小复现 | 影响面 | 定性 |
|---|---|---|---|---|---|
| **A1** | **起服务后、创世 checkpoint `main@1` 落盘前的 GUI 请求会失败**：`WARN io.mosire.simos.app.gui event=GUI_REQUEST_FAILED … path=/api/economy/overview error=IllegalStateException message=重放回退到父链的根仍无可用 checkpoint（C19 第③项保证 (main, 1) 应当有；文件缺失且无更早者可退…）: main@1` + 14 行栈（`Replay.java:181` → `CoreSimos.java:252` → `QueryService.java:105` → `GuiServer.java:831/710/616`） | `logs/logdet2.log.stripped` 第 3–17 行；**3 次同命令只有 1 次命中**（`logdet1/3` md5 `e6b15867…` 无此行、`logdet2` md5 `85e6c7d7…` 有 15 行）；本批 9 次 A/B 里另有 2 次 md5 与其余 7 次不同（`ab360-fix2-r1`、`ab360-fix-r3`；这两份日志已删 ⇒ **未逐行核对**是否同因）；**两次 600 天长跑 0 命中** | 同一条命令连跑 3 次，`grep GUI_REQUEST_FAILED logs/*.log`：约 1/3 命中 | **纯读数/竞态**：状态不受影响（3 次 dump 逐字节相同）；但它是"本机日志 md5 不稳定"的唯一来源，会**污染任何"日志逐行相同"的判据**（W6 旧的"结算序日志逐行相同"结论在本机当前状态下不成立——差分只有这 15 行） | 真缺陷（**小**，观测面/启动时序）；建议：GUI 在创世 checkpoint 落盘后再开监听，或让该路径等/重试 |
| **A2** | **同二进制同世界墙钟带宽达 9.0–14.2 s（±23%）**，单样本跨会话比较会得出假的"变慢/变快" | §2.6：本批 3 样本 11.291–14.202；同会话 A/B 9 次 8.976–10.637；W9e 8.226 / W9f 10.650 | 同 jar 同世界连跑 3 次 0→360 | 性能判据（任何"比上批慢 X%"的结论）不可用 | 方法论/环境（非代码缺陷）。**纪律**：性能结论必须同会话多样本或交错 A/B |
| **A3** | **`w6lib.extract` 的 `loans`（未偿贷款）读数在当前形状下恒 0**：它按 `debtContract.kind == "LOAN_PRINCIPAL"` / `.origin == "LOAN_PRINCIPAL"` 认贷款，而当前 `DebtContract`（`simos-economy/.../model/DebtContract.java:46-56`）**没有 kind/origin 字段**（只有 `DebtTerms`）⇒ 恒读到 0 | `readings/sw600-readings.txt` 的 `[loans]` 恒 0，而同世界 `MONEY_MARKET_WHY` 明确有放款（Σlent 347,973、`loans` 栏 > 0） | `python3 -c "import sys;sys.path.insert(0,'scripts');import w6lib;print(w6lib.extract('dumps/sw600-d600.json')['loans'])"` ⇒ `{'open': 0, ...}` | **只影响本批的"贷款存量为 0"这一栏**（本报告改用 `MONEY_MARKET_WHY` 的逐轮 `loans` 求和，并在 §2.4 标注）；**不改仓库** | 装置（读数库）陈旧，非 main 缺陷 |
| **A4** | **`CLASS_SLIDE_SPEED`（逐户速度/门槛读数，TRACE on `migration` 分类）在 `-Dsimos.economy.logLevel=DEBUG` 下不发射**（它要的是 `migration` 分类的 TRACE，而 logLevel 与 traceLevel 是两把开关） | 600 天日志里 `CLASS_SLIDE_SPEED` = **0 行**，而 `logLevel=TRACE` 的 W6 老批能读到 | 起服务带 `-Dsimos.economy.logLevel=DEBUG -Dsimos.economy.traceLevel=TRACE`，`grep -c CLASS_SLIDE_SPEED logs/x.log` = 0 | 本批"各级停留天数"只能用 `CLASS_SLIDE`（自带 `elapsedDays/requiredDays/speed/ratio`，**只覆盖真的滑落的那一次**）⇒ **"接近滑落但没滑"的户读不到**（已计入 §5 未验证） | 读数覆盖缺口（级别开关口径），非缺陷 |
| **A5** | **three-powers 结构上不会铸币**：无 `mint@` 产业、4 个政府 `mintScalePerCycle` 全 0 | `dumps/tp600-d600.json`：产业种类 `craft/farm/office/trade/weave`（83 个）无 `mint`；`moneyIssuances` 无 `MINT_PRODUCTION`；m600 == m0 | `python3 -c "import json;ed=json.loads(json.load(open('dumps/tp600-d600.json'))['modules']['economy']);print([k for k in ed['industries'] if 'mint' in k])"` ⇒ `[]` | **"铸币产出率"这个数在 tp 上没有作用面**（标定只能在 sw 上做）——不是缺陷，是**世界设置**（`EconomySeeder.java:3819` 注释自述"demo 世界才铸币"），但**必须让用户知道**，否则会以为 tp 的 0 是调参结果 | 世界设置（合法状态，需具名） |

---

## §4 结论：每个数是"稳 / 需调 / 需先补写口"

| # | 数 | 判定 | 依据（一句话） |
|---|---|---|---|
| 1 | `FAMINE_MAX_PPM_PER_TICK = 1000` | **需先补写口**（并且**先补"能否出现部分断顿"的读数面**，否则调它只是线性缩放） | 98.7%/99.65% 的断顿户·日 `ratio` 恒 1000 ⇒ 这个旋钮在本世界里**没有形状信息**，只有"死多快"；且它**没有 GM 写口**（用户既有裁定：「**凡"行为"一律做成 GM 可调的预设；代码不替 GM 做价值判断**」——原话「我认为全都应该算GM可以改的行为预设——GM是不是老傻子是GM的事，**项目代码应当是自由的**」，`docs/superpowers/specs/2026-09-25-aggregate-economy-v2-design.md:489`（第 18 条）；设计书 §7 v3.24 已把它列为"应改成政策预设"） |
| 2 | 滑落三常量 | **需先补写口**；**不建议现在调值** | 现值**逐值自洽**（speed/requiredDays 46/46、54/54 全对）且"每次恰好一级"成立；滑落稀少的主因是 `noLowerTarget`(40–58%) 与 `noDebt`(37–55%)，**不是斜率** ⇒ 调斜率买不到想要的曲线变化 |
| 3 | `MINT_MILLI_PER_FURNACE_CYCLE` / `MINT_LABOR_SHARE_PER_MILLE` | **需先补写口**（两常量只在创世消费）+ **需用户先定"铸币要不要成规模"** | 铸币对货币面 **+0.545%/600 天**、上限闸门（2000/轮）从未用满、**tp 完全没有铸币**。现只有"追加第二座铸炉"的绕道（`upsertIndustry`+`upsertGovUnit`），没有"改既有铸炉产出率/停炉"的写口 |
| 4 | 利率 α=50 + 基准 20‰ | **α 稳（且已可调）**；**基准 20‰ 需补写口**（若用户想调基准）；**数值本身建议维持不动**（用户已裁定"爆表不用管"） | α 写口实测 240/240 轮生效；D≫S 是结构问题（供给中位 977 毫 vs 需求中位 330 万），不是 α 的病 |
| 5 | `MIGRATION_SPEED_CAP_PER_MILLE` / `PROFIT_GAP_SPEED_SLOPE_PER_MILLE` | **需先补写口**；**且"上限 100"目前是空转的**（实测从未限制任何一笔） | 0/812 笔结果被闸门限制；市场"零成交轮次 = 0"这条硬牙**守住**（Σfills 2911）⇒ 现值安全，但**调它的杠杆在斜率、不在上限** |

**一句话给用户**：五个数里**只有第四节（利率 α）今天就能 GM 调**；① ② ⑤ 是常量、③ 是"创世消费值"，按用户既有裁定「凡行为一律做成 GM 可调的预设」都**应先补写口**。而**在补写口之前先要回答的一个前置问题**是：**①（饥荒）当前是"贴死上限"状态（98.7% 户·日 ratio=1000）⇒ 调它只能线性改死亡速度，不能改"谁饿"**；如果用户要的是后者，标定对象就不是这个常量，而是"为什么 80–95% 的户口粮缺口是 100%"。

---

## §5 未做 / 未验证项（**没跑过的写"没跑"**）

1. **没有做"调档实测"**：五个数的"往大/往小各调一档"全部是**算式推理**（§0/§2 已逐格标【推理】）。本批**未**改任何常量重跑世界（派单要求只读、且改常量需改源码 ⇒ 越界）。
2. **未验证 `CLASS_SLIDE_SPEED` 的"接近滑落未滑"户**（A4：该事件在 DEBUG 档不发射）⇒ 读不到"若把斜率调小，会有多少户立刻滑"的直接证据。
3. **未做逐户人口时点的补充读数**（`--pop-daily` 通道已写在 `calib5.py` 但**未跑**）：⑤ 的人口闸吃紧比例改用 **store changeset 反推**（`scripts/gate_probe.py`）得到，已做 pre±3 邻域稳定性检查；**未**逐户全窗校验该反推与逐日 checkpoint 的一致性。
4. **未测"饥荒形状"**（线性 vs 带阈值 vs 超线性）：本世界 `ratio` 恒 1000，任何过原点的单调形状在此点同值 ⇒ **该问题在本世界不可判**（需先造"部分断顿"场景）。
5. **未跑** `clean verify` / 全量测试 / SpotBugs / 前端门禁（本批只 `-DskipTests package`）⇒ **本批不构成任何"回归绿"的证据**。
6. **未跑** B2 分段等价（0→120→240→…）与逐 tick/一次推进的等价对拍；本批只做了"同世界多次 one-shot dump 逐字节相同"（**sw 14 份跨 3 jar/3 批次、tp 4 份**，见 §2.7）——**这不等于**"一次推进 == 分段推进 == 逐 tick 推进"。
7. **未验证** `MONEY_MARKET_LOAN`（逐笔借款，TRACE）的逐笔明细读数：本批日志**保留了**该事件（KEEP 正则含 `MONEY_MARKET[A-Z_]*`），但 `calib5.py` 只汇总了轮级 `MONEY_MARKET_WHY`/`UNFILLED`，**未逐笔统计**（逐笔明细仍在 `logs/sw600.log.gz` 里，可后补）。
8. **未逐档展开** tp 的完整速度直方图（24 档在 `readings/tp600-readings.txt` 里；§2.5 只摘了 min/中位/max 与越限笔数）。
9. **未做**旧档兼容性检查（按 AGENTS §一.11 明确**不做**，本批全部从创世新播）。
10. **未做** GUI 交互面（`?as=` / 前端）读数；本批只走 `POST /api/advance` 与两条 `GET`。
11. **未做**"两个结果"的产物陈旧排查（§三.5）：本批开工先做了**逐模块源码/class mtime 比对**（12/12 OK）与"无并发 Maven"确认；**未**在跑完后再次比对（因期间未再编译）。
12. 收尾：`git status --porcelain` **开工时为空**；**收尾时为 1 行**——` M docs/superpowers/specs/2026-10-10-unified-price-and-ordered-settlement-design.md`（+17 行 = 追加 **v3.34**），**mtime 2026-10-11 22:04:49，不是本批写的**（本批全程只读、只写 `.superpowers/sdd/2026-10-11-w6-calibration/`；该文件我在 21:52 只 `grep`/`sed` 读过）。**未回滚、未接触**（AGENTS §一.2：别人正在写的文件不接手）。本批全部新增产物在 `.superpowers/sdd/2026-10-11-w6-calibration/`（gitignore 下）。

### ★ 收尾时发现的新裁定（v3.34，22:04，**不是本批产物**）对本报告的影响

`docs/superpowers/specs/2026-10-10-…-design.md` 在 22:04:49 被追加 **v3.34**：
> 用户原话：「我建议是，**现在其他生产方式的客观规模限制都没做，直接把单hex的所有产业限制拉到无上限得了**，后面再完善」
> 裁定：① 所有产业（含农业/手工业/跑商）的规模上限 = **无上限**（`capacityPerUnit` 不再当准入门槛）；② 规模改由劳动与投入派生；③ `reserveIdleAssetsFor`/`planAssetMoves` 的**作业资本门票不再需要**；`LAND`/`WORKSHOP`/`TOOL` 份额**退为记账与收益归属**；④ 客观规模限制后续再完善。

⇒ **对本报告三处读数的影响（供用户拍板时一并考虑；v3.34 尚未落代码，HEAD `5e64685e` 仍是旧口径，故本批读数对当前代码有效）**：
- **⑤ 的 `NO_ASSET:<产业>@<hex>` 失败理由族**（sw 首档 910/846/840…，tp 1342–1567…）**正是"作业资本门票"**⇒ v3.34 落地后这一族应大幅消失，**迁移量会变**（`MIGRATION_SPEED_CAP_PER_MILLE`/斜率都要在新口径下重标）。
- **③ 的规模链**含产能门票（`mint` 的 `capacityPerUnit={WORKSHOP:1}`）⇒ 门票退为记账后，"单位规模"的门槛会变，**铸币每轮 500 毫这个数要在新口径下重读**。
- **各产业的 `capacityPerUnit` 门票实测值**（`dumps/sw600-d600.json`）：`farm@0_0 = {LAND:1000}`、`weave@0_0 = {TOOL:1}`、`craft@0_0 = {WORKSHOP:1}`、`mint@0_0 = {WORKSHOP:1}`、`trade@0_0 = {TOOL:120}` ⇒ v3.34 要动的就是这几行。
- **但"门票 ⇒ 饥荒"这条因果本批读不出来（明确记为未验证）**：逐户 famine 行按户类型计数（sw600，47637 行）显示饥荒**遍及农村各阶层**——
  `rural-middle_peasant 10303 / rural-rich_peasant 9919 / rural-poor_peasant 9379 / rural-landless_laborer-displaced 8834 / rural-landlord 6948`，
  城市各类合计仅 ~2254 行（**农村 : 城市 ≈ 95 : 5**）⇒ 断顿不是"无地户"专属，而是**农村整体 ration 缺口 100%**。
  ⇒ ① 的标定对象更可能是"农村–城市"这条线（或粮食可及性），**不是**阶层/土地门票；**该因果未在本批验证**。
