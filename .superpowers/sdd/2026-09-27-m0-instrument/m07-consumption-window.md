# m07 口径窗口复盘：`ΣgrainDailyConsumption×120` 与 `Σpop×10,000` 的 1.58%~5.34% 差额

> **性质**：只读分析（未改一行 Java、未跑 Maven、未重跑模拟、未 `git commit`）。
> **对象**：MASTER PLAN §1.7「口径疑点」第 **丁** 条；它是 M0.3 / M0.4 / M0.6 三份读数的共同分母口径。
> **输入（md5 已钉死；不符时脚本当场拒绝运行）**：
>
> | 文件 | md5 |
> |---|---|
> | `.superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick120.json` | `6aad52957a33f4dc54002aa64331b6ae` |
> | `.superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick240.json` | `b1e77b37ba1b774e85bbaaaa0f6902c5` |
> | `.superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick360.json` | `7efb22d1c08fa4e1d6a7d87270856a27` |
>
> **复算命令**（本报告全部数字的唯一来源；脚本只读、只打印）：
>
> ```bash
> cd /home/cna/SimulatorMosire
> python3 .superpowers/sdd/2026-09-27-m0-instrument/m07_consumption_window.py
> ```
>
> **脚本 md5**：`m07_consumption_window.py` = `a22e67412e6931403a46704e8e469606`。
> **脚本原样 stdout**：见附录 A（185 行，本报告正文的所有表都从那里摘出）。
> **源码版本**：分析期间（2026-09-27 21:03）有另一个写者对 `ApiViews.java` / `EconomySettlement.java` 落过一笔
> M1.5 改动（债权人索引等）；本文引用的函数**在改动后仍逐字存在**，行号已按改动后复核。
> 被引用源码的 md5 见附录 A 开头的 `[被引用的源码 md5]` 段（并见 §1.5）。

---

## 0. 一句话结论

**两个量差的不是粮食、不是种子、也不是取整累积，而是同一天里的两个人口时点。**
`grainDailyConsumption` 是**第 d 天结算时用“日初人口”写下的当天口粮**；dump 里的 `pop` 是**第 d 天日末
（同一天月末出生/死亡回写之后）的人口**。逐行恒等式（本报告用现存读数逐行精确验证）：

```text
daily_r × 120 − p_end_r × 10,000  ==  10,000 × (deaths_d,r − births_d,r) + round_r
round_r ∈ {0, 40, 80}   （毫粮；逐行逐值验证，19,176 行 0 例外）
```

三个 tick 的差额 **99.81%~99.99% 来自“单日人口变动项”**，取整只占 **0.0104%~0.1932%**。
`120/240/360` 全是 `PopulationDynamics.SETTLEMENT_DAYS = 30` 的整数倍 ⇒ 三个 dump 日**全部**踩在
月末出生/死亡结算日上，所以差额非零；逐格最大偏差随 tick 变大，是**首都格的单日月死亡批**在变大
（3,710 → 10,417 → 15,433 人）、同时分母人口在缩小（360,862 → 328,882 → 273,770）造成的尾部效应。
**两个量口径本就不可比**：要可比，只能统一到“同一天同一人口的日率”，或改成逐日累加的“真正周期需求”
（后者现存读数里没有，见 §4）。

---

## 1. 口径：两个量到底是什么（回答问 1）

### 1.1 量的定义链（逐字回代码，不采信注释的转述）

| 步骤 | 代码（原样；行号按 §1.5 的复核版） | 说明 |
|---|---|---|
| ① dump 字段 | `ApiViews.economyHex`：`grainDailyConsumption += row.naturalNeeds().getOrDefault(GRAIN, 0L);`（`ApiViews.java:467`） | 逐格把该格每一条家户行的 `naturalNeeds["grain"]` 相加 |
| ② `naturalNeeds` 的唯一写入点 | `EconomySettlement.consumeOwnStock`：`ClassRow row = withDailyNeed(rows.get(key), day);`（`EconomySettlement.java:2303`） | 每天结算的消费步先写需求，再吃饭；`day` 是绝对世界日 |
| ③ 写什么 | `EconomySettlement.withDailyNeed`：`EconomyVocabulary.dailyNeedsMilli(row.population(), day)`（`EconomySettlement.java:4054-4057`） | **用当天的行人口现算**；非累计 |
| ④ 日耗公式 | `EconomyVocabulary.dailyRationMilli(p, day) = cumulativeRationMilli(p, day) − cumulativeRationMilli(p, day−1)`（`EconomyVocabulary.java:160`） | 累计口粮的逐日差分，残差不丢 |
| ⑤ 累计公式 | `cumulativeRationMilli(p, days) = p × 10,000 × days ÷ 120`（向下取整；`EconomyVocabulary.java:138`） | `RATION_MILLI_PER_PERSON=10_000L`、`RATION_CYCLE_DAYS=120L`（`EconomyVocabulary.java:73,76`） |
| ⑥ `pop` 字段 | `ApiViews.economyHex`：`population += row.population();` | dump 时刻的行人口求和 |

`naturalNeeds` 是**逐日覆盖**的：每次结算把当天那一份写进去；dump 里读到的是**最近一次结算日**那一份
（`ApiViews.java:492-494` 的注释也如实写着“最近一次结算那天”“本方法入参没有 tick，物理上复算不出结算当天那个数”）。
它**不是**周期累计，也**不是**周期平均；它就是“第 d 天那一份日耗”。

### 1.2 日循环次序：为什么同一天会有两个人口

`Shell.java:513` 在真档世界只注册了 `new PopulationEconomyTimeParticipant(config.mapId())`（没有第二个经济写者）。
它的日循环次序是（`PopulationEconomyTimeParticipant.java`）：

```text
181:  ProductionLedger ledger = stepper.step(day);        // ① 经济结算一天：写 naturalNeeds（用此刻的行人口=日初人口）
202:  applyDailyStress(...);                                // ② 读当天的需求/实得，更新生理压力
204:  if (day % PopulationDynamics.SETTLEMENT_DAYS == 0L)  // ③ 每 30 天一次
208:      stepper.applyPopulationChange(outcome.changeList()); //   月度出生/死亡 → 回写经济侧行人口
```

- `PopulationDynamics.SETTLEMENT_DAYS = 30L`（`PopulationDynamics.java:69`）。
- 回写代码 `EconomySettlement.applyPopulationChange`（`EconomySettlement.java:1071`）：
  `rows.put(key, withPopulationAndLabor(row, remaining + birthsParts[j], ...))`，
  其中 `remaining = population − deathsParts[j]`，即 **p_end = p_start − deaths_d + births_d**；
  `withPopulationAndLabor` 保留 `row.naturalNeeds()`——所以日末回写后，**日耗字段仍是日初人口那一份**。
- 默认饿死通道 `FAMINE_MORTALITY_PER_MILLE = 0`（`EconomySettlement.java:341`），所以 dump 里
  `flow.deaths` 的人口变动来自月度出生/死亡这一条路（`FlowRow.deaths` 类注也写明两条来源）。
- `120 % 30 = 240 % 30 = 360 % 30 = 0` ⇒ 三个 dump 日**都是**月末回写日；dump 发生在整天推进结束之后。

### 1.3 两个量的统计窗口（问 1 的直接回答）

| 量 | 统计窗口 | 单位 | 人口时点 |
|---|---|---|---|
| `grainDailyConsumption` | 最近一次结算日 d 的**当天**自然需求（逐日覆盖；非累计、非平均） | 毫粮/日 | **日初人口** `p_start`（= d−1 日日末） |
| `economy.population`（= `Σpop`） | **d 日日末**快照（当天 step + 当天月末回写之后） | 人 | **日末人口** `p_end` |
| `grainDailyConsumption × 120` | “如果今天这个日率一直持续 120 天”的外推 | 毫粮 | 只含 `p_start` |
| `Σpop × 10,000` | “**日末**人口 × 每人每 120 天 10,000 毫粮”的配额（= `cumulativeRationMilli(pop,120)`） | 毫粮 | 只含 `p_end` |

**所以：两者不是“周期均值 vs 期末快照”，而是“单日快照（日初人口）” vs “期末人口×整周期配额”。**
在人口逐日变化的这个世界的三个 dump 日上，它们本来就不该相等。

### 1.4 结论：口径本就不可比；要可比该怎么定义

- 现存/任务口径的 `daily×120` 与 `pop_end×10,000`，在任何人口变动下都**不可互推**：
  前者是单日率外推，后者是期末人口配额。差额的完整表达式见 §3.1。
- 真要一个数，只能在下面两者中选一个，并写清用哪个人口快照：
  - **日率口径**：`grainDailyConsumption`（d 日日初人口）对 `Σ_r dailyRationMilli(p_end_r, d)`（d 日日末人口的同日需求）；
  - **周期口径**：`cycleNaturalNeedMilli = Σ_{day∈周期} Σ_r dailyRationMilli(pop_r(day), day)`（逐日人口）——
    这才是“本周期实际需求”；现存读数和结算代码里**没有**这个量（见 §4）。
- ★ 注意代码本身也同时用了两种人口时点，且都不是“真正周期需求”：`MarketSettlement.sellableOf`
  在关账日市场里用 `row.population()`（此时是**日初人口**）乘整周期配额（`MarketSettlement.java:363, 402`）；
  `ApiViews.grainDiagnosis` 的 `cycleNeed` 用**读口时刻**的行人口乘整周期配额（`ApiViews.java:598`）；
  `applyFamine` 也用当前人口乘整周期配额作分母；而 M0.3 的 `coverageDays = grainStock / dailyNeed`
  又用单日日初人口的日耗。**同一个 view 里的 `coverageDays` 与 `cycleNeed` 就已经是两个人口时点。**
  这是 M2 读数设计必须收掉的潜在口径分叉（本报告只指出，不改代码）。

### 1.5 代码版本与并发编辑说明（如实记）

- 本报告引用的逻辑在 21:03 的 M1.5 改动前后**逐字未变**；改动只是新增债权人索引/守恒分栏等，未动
  `grainDailyConsumption` 求和、`withDailyNeed`、日循环次序、`applyPopulationChange`、`FlowRow` 窗口注释。
- 21:03 复核版行号：`ApiViews.java:467`（求和）、`ApiViews.java:492-494`（窗口注释）、`ApiViews.java:598`
  （`grainDailyConsumption` 的 cycleNeed）；`EconomySettlement.java:2303`（`withDailyNeed` 调用）、
  `4054-4057`（写入点）、`1071`（月末回写）、`341`（致死率 0）。
- 本会话早先读到的改动前行号是 `ApiViews.java:450`、`EconomySettlement.java:3981`；与上面是同一段代码。
- 被引用源码 md5 由脚本运行时打印（见附录 A 开头）。**这些文件是活工作区**，若之后再被编辑，行号可能再漂移；
  但 `h6raw_tick*.json` 的三个 md5 已被脚本钉死，本报告的数字只对那三个文件成立。

---

## 2. 实测数字（问 2 的“差多少”）

### 2.1 全球三 tick

命令与输出见附录 A `[二]`。核心表：

| tick | Σdaily（毫粮/日） | Σpop（人） | LHS=Σdaily×120 | RHS=Σpop×10,000 | 差额 LHS−RHS | 差额/RHS | m04 报告口径 max\|LHS−RHS\|/max(LHS,RHS) | 出现在 |
|---:|---:|---:|---:|---:|---:|---:|---:|:---|
| 120 | 979,278,743 | 11,598,141 | 117,513,449,160 | 115,981,410,000 | 1,532,039,160 | 1.32093511% | **1.578180%** | 霍赫兰伯国 (-30,-58) |
| 240 | 948,820,672 | 11,359,780 | 113,858,480,640 | 113,597,800,000 | 260,680,640 | 0.22947684% | **3.070161%** | 德意志第二帝国 (-39,-71) |
| 360 | 946,485,865 | 11,349,353 | 113,578,303,800 | 113,493,530,000 | 84,773,800 | 0.07469483% | **5.336398%** | 德意志第二帝国 (-39,-71) |

- 最后一列逐值复现 `m04_attribution.py` §1.2 的 **1.578180% / 3.070161% / 5.336398%**
  （m04 的分母 = `max(|LHS|,|RHS|,1)`；这三处 LHS>RHS ⇒ 分母=LHS）。
- 注意“m04 报告口径 max”与“全球相对 RHS”不是一回事：全球差其实**在缩小**（1.32% → 0.23% → 0.075%），
  而最大逐格偏差在变大。§2.3 / §3.4 解释这个差异。
- RHS 口径的逐格最大相对偏差是 **1.603486% / 3.167406% / 5.637222%**（同一批格；见附录 A `[四]` 第 9 列）。

### 2.2 逐格最大偏差的完整账

| tick | 国 | (q,r) | LHS | RHS | 差额 | m04 口径相对 | RHS 口径相对 | 日初人口 p_start | 日末人口 p_end | 单日净减（人） | 取整残差 | 本周期 deaths | 本周期 births |
|---:|:---|:---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 120 | 霍赫兰伯国 | (-30,-58) | 58,300,080 | 57,380,000 | 920,080 | 1.578180% | 1.603486% | 5,830 | 5,738 | 92 | 80 | 188 | 30 |
| 120 | 德意志第二帝国 | (-39,-71) | 3,645,720,360 | 3,608,620,000 | 37,100,360 | 1.017641% | 1.028104% | 364,572 | 360,862 | 3,710 | 360 | 7,962 | 3,633 |
| 240 | 德意志第二帝国 | (-39,-71) | 3,392,990,280 | 3,288,820,000 | 104,170,280 | 3.070161% | 3.167406% | 339,299 | 328,882 | 10,417 | 280 | 32,060 | 80 |
| 360 | 德意志第二帝国 | (-39,-71) | 2,892,030,240 | 2,737,700,000 | 154,330,240 | 5.336398% | 5.637222% | 289,203 | 273,770 | 15,433 | 240 | 55,190 | 78 |

- p_start 不是猜的：它是把 dump 的 `naturalNeeds["grain"]` **逐行**代入口粮公式反解出唯一人口后，
  再按该格 8 行求和（表中的 p_start 是格级合计）；p_end 是 dump 里的 `economy.population`（= social、= Σclasses，799/799）。
  ★ `dailyRationMilli` 对人口**不可加**（floor 差），所以不能拿格级 daily 直接反解格级人口；
  格级恒等式是 8 条逐行恒等式相加的结果。表中的“取整残差”同样是 8 行的 `round_r` 之和
  （因此是 0~640 的 40 的倍数；**逐行** `round_r` 只能是 0/40/80，见 §3.3）。
- 首都的“单日净减 / 本周期 deaths”＝ 3,710/7,962 = 46.60%（120）、10,417/32,060 = 32.49%（240）、
  15,433/55,190 = 27.96%（360）：数量级完全在“月度死亡批”的合理范围内（且本报告还核了上界可行性，见 §3.5）。
- tick120 的最大格不是首都，而是霍赫兰的小人口格 (-30,-58)：同样约 92 人的单日净减，分母只有 57,380，
  所以相对偏差更大。这说明**最大偏差是“单日净减/期末人口”的尾部效应**，不是全球性的口径漂移。

### 2.3 方向与国别（为什么“全球差”与“最大差”不是一回事）

| tick | LHS>RHS 格 | LHS<RHS 格 | LHS=RHS 格 | 正差合计 | 负差合计 | 净差 |
|---:|---:|---:|---:|---:|---:|---:|
| 120 | **799** | 0 | 0 | 1,532,039,160 | 0 | 1,532,039,160 |
| 240 | 188 | 611 | 0 | 329,023,160 | −68,342,520 | 260,680,640 |
| 360 | 101 | 698 | 0 | 302,929,800 | −218,156,000 | 84,773,800 |

- tick120 全部 799 格都是“日末日初少人”（全球第一周期末月普遍死亡）；tick240/360 则多数格是
  “日末比日初多人”（出生多/死亡少），只有少数高压格还是净减。
- 全球净差因此被大面积的“反向格”抵消，而最大逐格偏差只看单格，故两者趋势相反（§3.4）。

---

## 3. 差额的精确分解（问 2 的“为什么”）

### 3.1 逐行恒等式（本报告的骨架）

对每一行 r、dump 日 d（= tick）：

```text
C(p, day) := cumulativeRationMilli(p, day) = floor(p × 10,000 × day ÷ 120)
daily_r   := C(p_start_r, d) − C(p_start_r, d−1)          // dump 的 naturalNeeds["grain"]
p_end_r   := dump 的 classes[].population
p_start_r := 使 daily_r == C(p, d) − C(p, d−1) 的唯一 p   // 反解（唯一性见 §3.2）

daily_r × 120 − p_end_r × 10,000
   == [daily_r × 120 − p_start_r × 10,000] + [(p_start_r − p_end_r) × 10,000]
   == round_r + 10,000 × delta_r
```

其中：
- `delta_r = p_start_r − p_end_r` = **第 d 天这一天**的人口净变化。代码次序保证 `p_end = p_start − deaths_d + births_d`
  （`applyPopulationChange` 的 `remaining + birthsParts[j]`），且默认饿死通道不致命（`FAMINE_MORTALITY_PER_MILLE=0`）
  ⇒ `delta_r = deaths_d,r − births_d,r`（净额精确；拆分见 §3.6）。
- `round_r = daily_r×120 − p_start_r×10,000`。因为 d ∈ {120,240,360} 都是 3 的倍数：
  `C(p,d) = p×10,000×d/120` 是整数，而 `C(p,d−1) = floor(p×250(d−1)/3)`，余数只能是 0、1/3、2/3；
  `daily = p×250/3 + frac`（frac∈{0,1/3,2/3}）⇒ `round_r ∈ {0, 40, 80}`。逐行数值验证 19,176 行 0 例外。

★ 注意：`dailyRationMilli` 对人口不可加（floor 的差），所以格级 `p_start` 必须**逐行反解后求和**，
不能拿格级 `daily_hex` 直接反解格级人口；逐行恒等式相加即得逐格/全球恒等式，**没有拟合、没有残差**。

### 3.2 反解的唯一性（p_start 为什么可信）

- `dailyRationMilli(·, d)` 在人口上严格递增：本脚本对 p∈[0, 500,000] 逐值核过，
  相邻步长只有 83 或 84（三个 d 全部如此）；dump 里单行最大人口约 15 万，覆盖有余。
- 对三个 tick 的 **全部 19,176 行**做“反解 → 回代 `dailyRationMilli(p_start,d)==daily_r`”的往返：
  `反解失败 0`（见附录 A `[一]` 的三个 tick 行）。
- `daily_r == 0` 的行反解为 p_start=0；dump 里“无 grain 键但 pop>0 的行”= 0，
  不存在“0 需求却有人口”的行（见附录 A `[一]`）。

### 3.3 全球分解表（完整、无残差）

| tick | 差额 LHS−RHS | 单日人口变动项 = 10,000×Σ(p_start−p_end) | 其中净减少人口（人） | 取整残差项 | 人口项占比 | 残差项占比 | 恒等式核验 |
|---:|---:|---:|---:|---:|---:|---:|:---:|
| 120 | 1,532,039,160 | 1,531,880,000 | 153,188 | 159,160 | 99.989611% | 0.010389% | OK |
| 240 | 260,680,640 | 260,520,000 | 26,052 | 160,640 | 99.938377% | 0.061623% | OK |
| 360 | 84,773,800 | 84,610,000 | 8,461 | 163,800 | 99.806780% | 0.193220% | OK |

逐行取整残差的完整分布（`round_r` 只能是 0/40/80）：

| tick | round=0 | round=40 | round=80 | 其它 | max\|round_r\| | 合计（= 0×a + 40×b + 80×c） |
|---:|---:|---:|---:|---:|---:|---:|
| 120 | 3,729 | 1,347 | 1,316 | 0 | 80 | 1347×40 + 1316×80 = **159,160** |
| 240 | 3,740 | 1,288 | 1,364 | 0 | 80 | 1288×40 + 1364×80 = **160,640** |
| 360 | 3,670 | 1,349 | 1,373 | 0 | 80 | 1349×40 + 1373×80 = **163,800** |

口径自检：对样本人口 p ∈ {1, 100, 5830, 289203}，三个整周期窗口 [1,120]/[121,240]/[241,360] 的
`Σ dailyRationMilli(p,day)` 都精确等于 `p×10,000`。**整周期累计本来就是精确的**；
`daily_r×120` 只是单日快照的 120 倍外推，差额不是“逐日 floor 损失累积”。

### 3.4 为什么逐格最大偏差随 tick 变大（2.3/2.2 的机制）

- **首都是主因**。三个 tick 首都 `delta_day` = 3,710 → 10,417 → 15,433 人，`p_end` = 360,862 → 328,882 → 273,770 人；
  `delta/p_end` = **1.028% → 3.167% → 5.637%**，几乎就是最大偏差本身（再加零点几个 ‰ 的取整）。
  也就是说：**“快照日一天死了多大比例的人”**在变大——第一周期末月 1.03%，第三周期末月 5.64%。
  这与该格的社会侧读数一致：tick360 首都 `FOOD` crisis 满足率 0‰、stress 2966‰、周期死亡率 201‰。
- **全球在缩小**，因为 tick240/360 大多数格在快照日是“出生多于死亡”（§2.3 的 611/698 格反向），
  抵消了少数高压格的正差；最大偏差只挑单格，所以呈现出“全球 0.075%、单格 5.34%”的并存。
- **d 的选择是结构性的**：120/240/360 都是 30 的倍数。若在 d−1（119/239/359）落同一份 dump，
  按代码路径 `day%30 != 0` ⇒ 当天没有月度回写 ⇒ `p_start = p_end` ⇒ 差额只剩取整项
  （每格最多 8 行×80 = 640 毫粮，相对 <0.01%）。**这条是代码路径推导，未用 d−1 dump 直接验**（现存材料没有）。

### 3.5 MASTER PLAN 丁条点名的候选成因，逐项判

| tick | 差额实际值 | ①整周期净减少×10,000（若用它归因） | ①/实际 | ②取整残差 | ③单日时点错位 = 差额−取整 | ④种子/投入 | ⑤缺格/分层 |
|---:|---:|---:|---:|---:|---:|---|---|
| 120 | 1,532,039,160 | 2,318,590,000 | 1.5134× | 159,160 | 1,531,880,000 | 0（两量都不含） | 0（完整性检查全过） |
| 240 | 260,680,640 | 2,383,610,000 | 9.1438× | 160,640 | 260,520,000 | 0（两量都不含） | 0（完整性检查全过） |
| 360 | 84,773,800 | 104,270,000 | 1.2300× | 163,800 | 84,610,000 | 0（两量都不含） | 0（完整性检查全过） |

**① 期内人口下降 → 机制不成立（只是形式上有量级）。**
自然需求逐日覆盖，dump 的 `daily` 只是第 d 天那一份，所以差额不含整周期累计人口变化。
用整周期净减少（本周期 deaths−births）去预测，120/240/360 分别会给出实际的 1.51× / 9.14× / 1.23×；
240 那期整整放大 9.14 倍（单日净减 26,052 vs 整周期净减 238,361 ⇒ 单日只占 10.93%）。
单日净减 / 整周期净减 = 153,188/231,859 = 66.07%（120）、26,052/238,361 = 10.93%（240）、
8,461/10,427 = 81.15%（360）。**“期内人口下降”如果指整周期，方向/量级都不对；如果指快照日那一天，它就是正解。**

**② 逐格/逐日取整 → 存在，但极小。**
逐行残差只能 0/40/80 毫粮（19,176 行 0 例外，max 80）；占差额 0.0104% / 0.0616% / 0.1932%。
它不是“逐日 floor 损失的累积”：整周期望远镜求和 `Σ_d daily == p×10,000` 对样本人口三个窗口全部精确成立。

**③ 人口与日耗不同时点 → 这就是全部剩余差额（99.81%~99.99%）。**
精确链路：`step(day)` 先写 `naturalNeeds`（日初人口）→ 同一天 `day%30==0` 才做月度出生/死亡并回写行人口；
120/240/360 都是 30 的倍数 ⇒ dump 日必然踩在这条缝上。差额 = `10,000×(deaths_d−births_d) + 取整项`。
**未拆分部分**：`deaths_d` 与 `births_d` 各自是多少（现有 dump 只有本周期累计，见 §3.6/§4）。

**④ 种子/生产投入 → 两量都不含，排除。**
`grainDailyConsumption` 只读 `naturalNeeds`（grain）；`naturalNeeds` 由 `dailyNeedsMilli` 写（grain+cloth）；
种子/原料走 `drawCycleInputs` → `FlowRow.consumed`（`FlowRow.java:48` 明确写 consumed 含“日耗 + 现扣周期投入”）。
数量级对照：`Σ flow.consumed.grain ÷ Σ naturalNeeds.grain` = **65.324956 / 113.142450 / 112.916500**
（tick120/240/360）——`consumed` 里确实有大量种子/投入，但它**不在** LHS，也**不在** RHS。

**⑤ 缺格/城乡分层 → 排除。**
799/799 格 activated、坐标唯一（799 unique/799 rows）；每格 **8 行**（rural/urban × landlord/middle/poor/rich）；
`economy.population == social.population` 799/799；`== Σclasses.population` 799/799；
`grainDailyConsumption == Σ naturalNeeds["grain"]` 799/799；`social.at.tick` 逐格 = dump tick；
三国格数 430/138/231（合计 799）。
另有跨切片对平：凡有 `social.crisis` `MORTALITY` evidence 的格，
`deathsThisCycle == Σ economy flow.deaths` 的比例为 **799/799、641/641、41/41**（逐 tick 不等 0）
⇒ 坐实 `flow.deaths/births` 的窗口 = **本周期累计**。

**窗口对平（跨周期）**：`pop(tick120) − pop(tick360)` 与第二、三周期的 `deaths−births` 逐值对上：

| 对象 | pop(120) − pop(360) | [deaths(240)+deaths(360)] − [births(240)+births(360)] | 核验 |
|---|---:|---:|:---:|
| 全球 | 248,788 | 508,712 − 259,924 = 248,788 | OK |
| 首都 (-39,-71) | 87,092 | 87,250 − 158 = 87,092 | OK |
| tick120 最大格 (-30,-58) | 60 | 198 − 138 = 60 | OK |

这同时证明 dump 的 `population` 是**日末**（新周期结算后的存档）、`flow.deaths/births` 是**本周期累计**。

### 3.6 仍然“分不出”的那一块（具名）

把差额 100% 分成“单日人口变动项 + 取整项”之后，**单日人口变动项的符号与净额是精确的**，
但 `deaths_d` 与 `births_d` 的**当日拆分**分不出：
- 现有 dump 的 `flow.deaths`/`flow.births` 是**本周期累计**（窗口注明见 `FlowRow.java:67`），只能给可行性上界：
  `0 ≤ deaths_d ≤ flow.deaths`、`0 ≤ births_d ≤ flow.births`；
- 本脚本对 3 tick × 6,392 行做了该可行性检查：`delta>deaths` 或 `−delta>births` 的行数 = **0 / 0 / 0**；
- 但在这个上界内，`deaths_d` 与 `births_d` 有无穷多组可行解（例如首都 tick360 的 `delta=15,433`，
  可以 `deaths=15,433,births=0`，也可以 `deaths=15,433+80,births=80`，因为本周期 births=78 的分配未知）。
  ⇒ 现有材料**不足以把净额唯一拆成 deaths/births**。补读数方案见 §4.3(a)。

---

## 4. 能不能从现存读数精确重建（问 3）

### 4.1 能精确重建的

- 每一行的 **p_start_r（日初人口）**：由 dump 的 `naturalNeeds["grain"]` 唯一反解（严格单调 + 往返 0 失败，§3.2）。
- 每一行/格/全球的 **delta_r = p_start_r − p_end_r**：快照日一天的人口净变化（= deaths_d − births_d **净额**）。
- 每一行的 **round_r**：精确值，且只能 0/40/80；公式见 §3.1。
- 因此“差额 = 10,000×Σdelta + Σround”是**逐值恒等式**，不是拟合；逐格最大偏差 1.58%/3.07%/5.34% 的
  完整账已经摊开（§2.2）。

### 4.2 不能从现存读数重建的（具名）

1. **d 日的 deaths_d / births_d 拆分**：`flow.deaths/births` 只有本周期累计（§3.6）。
2. **周期内逐日人口/日耗序列**：`naturalNeeds` 逐日覆盖，dump 只留最后一天那一份；
   `population` 只有三个关账日的快照。
3. **真正的“本周期实际需求”** `cycleNaturalNeedMilli = Σ_{day∈周期} Σ_r dailyRationMilli(pop_r(day), day)`：
   现存代码/读数都没有这个量；`ApiViews.grainDiagnosis.cycleNeed` 与 `MarketSettlement.selfNeedOf`
   用的是“某一天人口 × 整周期配额”，不是逐日累加。
4. **“快照日的人口变化是否全部来自月度结算”的直接核对**：代码路径（`day%30==0` 才 `applyPopulationChange`，
   真档只注册了 `PopulationEconomyTimeParticipant`）支持这一点，但没有 d−1 的 dump 可以直接验证。
5. **首都被扣人口在年龄×性别×压力上的分解**：dump 的 `social.groups` 是逐格聚合（total/urban/rural/ageBrackets/sex/stress），
   没有逐 `PopulationGroup` 的年龄锚点/性别/压力；因此本报告没有从 `PopulationDynamics.monthly` 第一性原理复算首部的
   15,433 人，只核到“它是本周期 deaths 的 27.96% 且量级/上界相容”。

### 4.3 最小补读数方案（供 M2 读数设计）

**(a) 零 Java 改动，先补 d−1 的 dump（最便宜、判别力最强）。**
每个关账日追加一次 `h6sim_dump.py` 在 `119/239/359` 的同一份逐格 dump（或在中间任何一天落一份）。于是：

- `flow.deaths(tick d) − flow.deaths(tick d−1) = deaths_d`（逐行精确；tick d−1 不是结算日，累计值不会被清零，
  见 flow 的清零点=新周期第一天）；`births` 同理；
- `economy.population(tick d−1)` 应逐行等于本脚本反解出的 `p_start(d)`：这是对整条链的**独立验收**；
- 还能用 `naturalNeeds(tick d−1)` 反解出 d−1 的日初人口，得到连续两天的人口序列（顺带给出 day d 的真实单日
  `naturalNeeds` 变化）。

**(b) 要看“真正周期需求”，加一个逐行累加器（M2 范畴，一行 Java 级新增）。**
在 `ClassRow`/`FlowRow` 增加 `cycleNaturalNeedMilli`（毫粮，grain；可扩展为逐商品）：
每天 `+= dailyRationMilli(row.population(), day)`，新周期第一天归零；由 `ApiViews` 读出。
它与今天的 `naturalNeeds` **同源**，不另立公式，是唯一能在人口逐日变化时给出准确分母的读法。

**(c) 读口补“这个数用的人口快照”。**
今天 `economyHex` 入参没有 tick，`grainDailyConsumption` / `naturalNeeds` 没有 `lastSettledDay` 或
`populationAtNeed`；建议在 view 里显式带出 `tick`/`lastSettledDay` 与“本值用的人口快照”，
否则读者无法知道 `daily` 与本格 `population` 是不是同一时点（本报告的问题正是这么产生的）。

**(d) 口径定义（要可比只有二选一）。**

- **日率口径**：同一天、同一人口比日耗；例如 `grainDailyConsumption`（d 日日初）对
  `Σ_r dailyRationMilli(p_end_r, d)`（d 日日末）——差额就是“单日人口变动/120”，可解释、可复核。
- **周期口径**：`cycleNaturalNeedMilli`（逐日累加）对 `cumulativeRationMilli(周期初或各日人口, cycleDays)`；
  人口不变时二者才相等。
- **禁止**继续把“单日快照×120”与“日末人口×10,000”并排当作同一分母——它们在任何人口变动下都不可互推。

---

## 5. 对 M0.3 / M0.4 / M0.6 的直接影响（本报告不重跑，只指出口径）

- **M0.4**：`m04_attribution.py` 的正式口径 = `grainDailyConsumption×120`（= 本报告 LHS），敏感性口径 =
  `Σpopulation×10,000`（= 本报告 RHS）；m04 §2.4 的两行数字与本报告 §2.1 的 LHS/RHS 逐值一致。
  差额的实际影响已由 m04 自己做成敏感性表；本报告补的是**为什么差**和**还差多少没拆**。
- **M0.6**：其 `cycleNeedDaily = grainDailyConsumption×120` 就是 LHS；若改用 RHS，全球分母变化
  ≤1.32%（三个 tick 分别 1.32% / 0.23% / 0.075%），但逐格最大可到 5.34%（首都 tick360），
  且**两者都不是“本周期实际需求”**（§4.2 第 3 条）。所以 M0.6 里“资本格”这类逐格数字对口径最敏感，
  跨格/跨 tick 比较要留这条尾巴；本报告没有重跑 M0.6 的档位/覆盖率。
- **M0.3**：`grainDiagnosis` 在同一个 view 里 `coverageDays = grainStock / dailyNeed`（日初人口日耗）与
  `cycleNeed = cumulativeRationMilli(readtime_pop, cycleDays)`（读口时刻人口×配额）各用一个人口时点；
  dump 里没有 `grainDiagnosis`（m04 已记 0/799），所以本报告没有复算它的具体数值，只指出口径分叉。

---

## 6. 我没做、没验证的

- **没有**改任何 Java / `docs/**` / `AGENTS.md` / `src/**`；**没有**跑 Maven、模拟、`git commit`。
  只新建了本报告与 `m07_consumption_window.py` 两个文件，并用 `/tmp` 暂存脚本 stdout（仓外临时文件）。
- **没有** d−1（119/239/359）的 dump ⇒ §3.4 里“非月末日差额只剩取整项”是**代码路径推论**，
  没有用数据直接验证；§4.3(a) 的方案正是为补这条。
- **没有**把 `deaths_d`/`births_d` 拆开：现有 dump 只有周期累计；本报告只做了可行性上界检查（0 违反）。
- **没有**从 `PopulationDynamics.monthly` 第一性原理复算首部的单日死亡；`social.groups` 是聚合视图，
  缺逐批次年龄/性别/压力。首部的 15,433 只核到“占本周期 deaths 27.96%、量级/上界相容”。
- **没有**重跑 M0.3/M0.4/M0.6；只引用了 m04 的 1.578180/3.070161/5.336398 并逐值复现、核对了 LHS/RHS。
  没有评估 M0.6 的结论会不会翻（只给出分母量级）。
- **没有**核 `EconomyMeta.lastClosedCycle` / store 内部状态（读口不暴露）；判断“d 就是最后结算日”用的是
  `social.at.tick` 逐格 = dump tick + `PopulationEconomyTimeParticipant` 的日循环代码。
- **没有**核世界创世基线（本报告没有 genesis dump）。“整周期净减少”的比较用的是 dump 的 `flow.deaths/births`
  本周期累计，不是从创世累计。
- **没有**核 `.claude/worktrees/**` 下的副本/旧版；也不对并发编辑期间除 §1.5 点名的引用函数之外的源码负责。
- **旁证（不属于丁的结论，未深挖）**：§1.7 甲组的“首都人口对不上”在本批数据里可解释为**两个 tick 混用**——
  `360,862（城镇 345,908）/ 3,100 亩 / 库存 236,854,357 / 日耗 30,381,003` 逐值 = **tick120** 的 (-39,-71)；
  `273,770（城镇 259,184）/ 需求 2,737,700` 逐值 = **tick360** 的同一格（RHS=2,737,700,000）。
  本报告只报这个数值对应，没有去改 MASTER PLAN、也没有进一步核“覆盖率 7.744 天”等其余数字。

---

## 附录 A：脚本原样 stdout（2026-09-27 复算运行）

命令：

```bash
cd /home/cna/SimulatorMosire
python3 .superpowers/sdd/2026-09-27-m0-instrument/m07_consumption_window.py
```

stdout（原样；脚本自身带输入 md5 校验）：

```text
=== m07 口径窗口复盘：grainDailyConsumption×120 vs Σpop×10,000（只读） ===

[输入文件与 md5]
  .superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick120.json  md5=6aad52957a33f4dc54002aa64331b6ae  expected=6aad52957a33f4dc54002aa64331b6ae  OK
  .superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick240.json  md5=b1e77b37ba1b774e85bbaaaa0f6902c5  expected=b1e77b37ba1b774e85bbaaaa0f6902c5  OK
  .superpowers/sdd/2026-09-27-economic-cycle-impl/h6raw_tick360.json  md5=7efb22d1c08fa4e1d6a7d87270856a27  expected=7efb22d1c08fa4e1d6a7d87270856a27  OK

[被引用的源码 md5（当前工作区这一版；只记录，不改）]
  ApiViews.java: a976e5b91b5d26f6515ad4aa9f76d360  (simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java)
  PopulationEconomyTimeParticipant.java: 4329dfa62bb28b44fe4f8a9a2c0cb4d0  (simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java)
  EconomySettlement.java: 01e0ac7184b1c3a0c8c5ab5e1a525869  (simos-economy/src/main/java/io/mosire/simos/economy/time/EconomySettlement.java)
  MarketSettlement.java: 1da7869c6d55fd56bd62a84611dd3476  (simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java)
  FlowRow.java: 8e3c11b4a9cb670f41151ac6fa01b19e  (simos-economy/src/main/java/io/mosire/simos/economy/model/FlowRow.java)
  EconomyVocabulary.java: d3b0d371e1f51ee4f98cd7f4f30063b9  (simos-util/src/main/java/io/mosire/simos/util/economy/EconomyVocabulary.java)
  PopulationDynamics.java: e9999c1e4ed01a71d11c49151be51f5b  (simos-social/src/main/java/io/mosire/simos/social/population/PopulationDynamics.java)

[零、口径常量（脚本写死；逐个回代码核过）]
  RATION_MILLI_PER_PERSON=10000 毫粮/人/周期；RATION_CYCLE_DAYS=120 天；PopulationDynamics.SETTLEMENT_DAYS=30 天；EconomySettlement.FAMINE_MORTALITY_PER_MILLE=0（默认饿死通道不死人）。
  关账日 tick % 30 = [0, 0, 0] ⇒ 三个 dump 日全部是月末出生/死亡结算日（这正是本差额的结构性来源）。

[一、数据完整性检查（先证明不是读数缺格/分层漏算）]
  | tick | 格数 | 每格 class 数 | activated | pop==social（格） | pop==Σclasses（格） | 日耗==ΣnaturalNeeds（格） | social.at.tick 全等 |
  |---:|---:|---:|---:|---:|---:|---:|:---:|
  | 120 | 799 | 8 | 是 | 799/799 | 799/799 | 799/799 | 是 |
  | 240 | 799 | 8 | 是 | 799/799 | 799/799 | 799/799 | 是 |
  | 360 | 799 | 8 | 是 | 799/799 | 799/799 | 799/799 | 是 |
  · 三国格数 = 德意志第二帝国=430 / 奥斯特马克侯国=138 / 霍赫兰伯国=231；坐标唯一 = 是（逐格 dump 无缺格、无重复格）。
  · tick 120: social.crisis 的 MORTALITY evidence 出现 799 格；deathsThisCycle == Σ economy flow.deaths：799/799（不等 0）—— 跨切片坐实 flow.deaths 的窗口=本周期累计。
  · tick 240: social.crisis 的 MORTALITY evidence 出现 641 格；deathsThisCycle == Σ economy flow.deaths：641/641（不等 0）—— 跨切片坐实 flow.deaths 的窗口=本周期累计。
  · tick 360: social.crisis 的 MORTALITY evidence 出现 41 格；deathsThisCycle == Σ economy flow.deaths：41/41（不等 0）—— 跨切片坐实 flow.deaths 的窗口=本周期累计。
  · tick 120: 行数 6392（pop=0 的行 2392，无 grain 键 2392，无 grain 键但 pop>0 的行 0，daily>0 的行 4000）；反解失败 0；单日净变化违反 deaths/births 上界的行 0；dailyRationMilli 在 p∈[0,500000] 严格递增=True（最小步长 83）。
  · tick 240: 行数 6392（pop=0 的行 2392，无 grain 键 2392，无 grain 键但 pop>0 的行 0，daily>0 的行 4000）；反解失败 0；单日净变化违反 deaths/births 上界的行 0；dailyRationMilli 在 p∈[0,500000] 严格递增=True（最小步长 83）。
  · tick 360: 行数 6392（pop=0 的行 2392，无 grain 键 2392，无 grain 键但 pop>0 的行 0，daily>0 的行 4000）；反解失败 0；单日净变化违反 deaths/births 上界的行 0；dailyRationMilli 在 p∈[0,500000] 严格递增=True（最小步长 83）。

[二、全球三 tick：两个量、差额、报告口径的最大逐格相对偏差]
  | tick | Σdaily（毫粮/日） | Σpop（人） | LHS=Σdaily×120 | RHS=Σpop×10,000 | 差额 LHS−RHS | 相对 RHS | m04 报告口径 max|LHS−RHS|/max(LHS,RHS) | 出现在 |
  |---:|---:|---:|---:|---:|---:|---:|---:|:---|
  | 120 | 979,278,743 | 11,598,141 | 117,513,449,160 | 115,981,410,000 | 1,532,039,160 | 1.32093511% | 1.578180% | 霍赫兰伯国(-30,-58) |
  | 240 | 948,820,672 | 11,359,780 | 113,858,480,640 | 113,597,800,000 | 260,680,640 | 0.22947684% | 3.070161% | 德意志第二帝国(-39,-71) |
  | 360 | 946,485,865 | 11,349,353 | 113,578,303,800 | 113,493,530,000 | 84,773,800 | 0.07469483% | 5.336398% | 德意志第二帝国(-39,-71) |

  注：m04_attribution.py §1.2 的 1.578180% / 3.070161% / 5.336398% 用的就是最后一列
      （分母 = max(LHS,RHS)，这三处 LHS>RHS ⇒ 分母=LHS）；本脚本逐值复现。

[三、精确分解（逐行恒等式，无残差、无拟合）]
  逐行：daily_r × 120 − p_end_r × 10,000 == 10,000 × (p_start_r − p_end_r) + round_r
        p_start_r = 反解的日初人口；round_r = daily_r×120 − p_start_r×10,000（逐日差分取整残差）
  逐行相加 = 全球/逐格差额的完整分解。
  | tick | 差额 LHS−RHS | 单日人口变动项 = 10,000×Σ(p_start−p_end) | 其中净减少人口（人） | 取整残差项 | 人口项占比 | 残差项占比 | 恒等式核验 |
  |---:|---:|---:|---:|---:|---:|---:|:---:|
  | 120 | 1,532,039,160 | 1,531,880,000 | 153,188 | 159,160 | 99.989611% | 0.010389% | OK |
  | 240 | 260,680,640 | 260,520,000 | 26,052 | 160,640 | 99.938377% | 0.061623% | OK |
  | 360 | 84,773,800 | 84,610,000 | 8,461 | 163,800 | 99.806780% | 0.193220% | OK |

  逐行取整残差的完整分布（round_r 只能是 0/40/80；这是 daily = floor 累计之差 的直接后果）：
  | tick | round=0 | round=40 | round=80 | 其它 | max|round_r| | p 单调性 |
  |---:|---:|---:|---:|---:|---:|:---:|
  | 120 | 3729 | 1347 | 1316 | 0 | 80 | 严格递增 |
  | 240 | 3740 | 1288 | 1364 | 0 | 80 | 严格递增 |
  | 360 | 3670 | 1349 | 1373 | 0 | 80 | 严格递增 |
  · 口径自检（本脚本当场算的）：对样本人口 p=[1, 100, 5830, 289203]，三个整周期窗口
      [1,120] / [121,240] / [241,360] 的 Σ dailyRationMilli(p,day) 都 == p×10,000；核验=OK。
      ⇒ 整周期累计是精确的；“单日×120”只是单日快照的外推，差额由上面两项完整解释。

[四、逐格最大偏差的完整账（m04 点名的那几格 + 首都）]
  | tick | 国 | (q,r) | LHS | RHS | 差额 | m04 口径相对 | RHS 口径相对 | 日初人口 p_start | 日末人口 p_end | 单日净减 | 取整残差 | 本周期 deaths | 本周期 births |
  |---:|:---|:---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
  | 120 | 霍赫兰伯国 | (-30,-58) | 58,300,080 | 57,380,000 | 920,080 | 1.578180% | 1.603486% | 5,830 | 5,738 | 92 | 80 | 188 | 30 |
  | 120 | 德意志第二帝国 | (-39,-71) | 3,645,720,360 | 3,608,620,000 | 37,100,360 | 1.017641% | 1.028104% | 364,572 | 360,862 | 3,710 | 360 | 7,962 | 3,633 |
  | 240 | 德意志第二帝国 | (-39,-71) | 3,392,990,280 | 3,288,820,000 | 104,170,280 | 3.070161% | 3.167406% | 339,299 | 328,882 | 10,417 | 280 | 32,060 | 80 |
  | 360 | 德意志第二帝国 | (-39,-71) | 2,892,030,240 | 2,737,700,000 | 154,330,240 | 5.336398% | 5.637222% | 289,203 | 273,770 | 15,433 | 240 | 55,190 | 78 |

  · 三 tick 逐格明细（按 m04 口径相对偏差降序前 5；首都即使不是第一也列出）：
    | tick | 国 | (q,r) | 差额 | 相对(max分母) | 日初−日末（人） | 取整 |
    |---:|:---|:---|---:|---:|---:|---:|
    | 120 | 霍赫兰伯国 | (-30,-58) | 920,080 | 1.578180% | 92 | 80 |
    | 120 | 霍赫兰伯国 | (-41,-56) | 920,200 | 1.571911% | 92 | 200 |
    | 120 | 霍赫兰伯国 | (-39,-64) | 940,160 | 1.571907% | 94 | 160 |
    | 120 | 霍赫兰伯国 | (-29,-67) | 920,120 | 1.570435% | 92 | 120 |
    | 120 | 霍赫兰伯国 | (-26,-64) | 940,120 | 1.569217% | 94 | 120 |
    | 120 | 德意志第二帝国 | (-39,-71) | 37,100,360 | 1.017641% | 3,710 | 360 |
    | 240 | 德意志第二帝国 | (-39,-71) | 104,170,280 | 3.070161% | 10,417 | 280 |
    | 240 | 奥斯特马克侯国 | (-31,-76) | 17,760,440 | 2.442774% | 1,776 | 440 |
    | 240 | 奥斯特马克侯国 | (-21,-85) | 5,900,360 | 1.612515% | 590 | 360 |
    | 240 | 奥斯特马克侯国 | (-30,-68) | 5,780,400 | 1.599179% | 578 | 400 |
    | 240 | 霍赫兰伯国 | (-39,-59) | 5,160,320 | 1.523385% | 516 | 320 |
    | 360 | 德意志第二帝国 | (-39,-71) | 154,330,240 | 5.336398% | 15,433 | 240 |
    | 360 | 奥斯特马克侯国 | (-31,-76) | 24,830,440 | 3.843362% | 2,483 | 440 |
    | 360 | 霍赫兰伯国 | (-39,-59) | 6,950,160 | 2.178664% | 695 | 160 |
    | 360 | 奥斯特马克侯国 | (-21,-85) | 6,770,440 | 1.974059% | 677 | 440 |
    | 360 | 奥斯特马克侯国 | (-21,-82) | 6,150,160 | 1.904604% | 615 | 160 |

[五、方向与国别（为什么“全球差”与“逐格最大差”不是一回事）]
  | tick | LHS>RHS 格 | LHS<RHS 格 | LHS=RHS 格 | 正差合计 | 负差合计 | 净差 |
  |---:|---:|---:|---:|---:|---:|---:|
  | 120 | 799 | 0 | 0 | 1,532,039,160 | 0 | 1,532,039,160 |
  | 240 | 188 | 611 | 0 | 329,023,160 | -68,342,520 | 260,680,640 |
  | 360 | 101 | 698 | 0 | 302,929,800 | -218,156,000 | 84,773,800 |

  | tick | 国 | 格数 | 日末人口 | 单日净减（人） | 差额合计 | 取整残差合计 |
  |---:|:---|---:|---:|---:|---:|---:|
  | 120 | 德意志第二帝国 | 430 | 6,106,465 | 81,423 | 814,316,960 | 86,960 |
  | 120 | 奥斯特马克侯国 | 138 | 3,017,476 | 36,901 | 369,037,040 | 27,040 |
  | 120 | 霍赫兰伯国 | 231 | 2,474,200 | 34,864 | 348,685,160 | 45,160 |
  | 240 | 德意志第二帝国 | 430 | 5,982,653 | 7,493 | 75,017,320 | 87,320 |
  | 240 | 奥斯特马克侯国 | 138 | 2,950,676 | 17,431 | 174,336,040 | 26,040 |
  | 240 | 霍赫兰伯国 | 231 | 2,426,451 | 1,128 | 11,327,280 | 47,280 |
  | 360 | 德意志第二帝国 | 430 | 5,979,241 | 3,432 | 34,407,560 | 87,560 |
  | 360 | 奥斯特马克侯国 | 138 | 2,928,738 | 7,719 | 77,218,440 | 28,440 |
  | 360 | 霍赫兰伯国 | 231 | 2,441,374 | -2,690 | -26,852,200 | 47,800 |

[六、跨周期窗口对平（证明 dump 的 population 是“日末”、flow.deaths/births 是“本周期累计”）]
  恒等式：pop(tick120) − pop(tick360) == [deaths(240)+deaths(360)] − [births(240)+births(360)]（都为正数则成立）
    全球：11,598,141 − 11,349,353 = 248,788；deaths 508,712 − births 259,924 = 248,788；核验=OK
    首都 (-39,-71)：360,862 − 273,770 = 87,092；deaths 87,250 − births 158 = 87,092；核验=OK
    tick120 最大格 (-30,-58)：5,738 − 5,678 = 60；deaths 198 − births 138 = 60；核验=OK

  · flow 的窗口（FlowRow 类注）：本周期累计、新周期第一天归零；关账日读到的是一整个周期的量。
  · 因此“本周期人口下降”不是本差额的机制：自然需求逐日覆盖，快照只记住最后一天；
    见下面第七节把“整周期净减少”与“单日净减少”逐 tick 对出来。

[七、MASTER PLAN §1.7 丁 点名的候选成因，逐项判]
  | tick | 差额实际值 | 候选①整周期净减少×10,000（若成立会是多少） | 候选①/实际 | 候选②取整残差 | 候选③单日时点错位 = 差额−取整 | 候选④种子/投入 | 候选⑤缺格/分层 |
  |---:|---:|---:|---:|---:|---:|---|---|
  | 120 | 1,532,039,160 | 2,318,590,000 | 1.5134× | 159,160 | 1,531,880,000 | 0（两量都不含，见下） | 0（完整性检查全过） |
  | 240 | 260,680,640 | 2,383,610,000 | 9.1438× | 160,640 | 260,520,000 | 0（两量都不含，见下） | 0（完整性检查全过） |
  | 360 | 84,773,800 | 104,270,000 | 1.2300× | 163,800 | 84,610,000 | 0（两量都不含，见下） | 0（完整性检查全过） |

  ① 期内人口下降：**机制不成立**。自然需求逐日覆盖，dump 里的 daily 只是第 d 天那一份；
     差额恒等于“第 d 天日初→日末”的一天净变化，不是整周期累计变化。
     证据：单日净减少 / 整周期净减少 = 153,188/231,859 = 66.07%（120）、26,052/238,361 = 10.93%（240）、
           8,461/10,427 = 81.15%（360）—— 240 那期整周期净减少是单日的 9.15 倍，若按整周期算会把差额放大 9 倍。
  ② 取整：**存在但极小**。逐行残差只能 0/40/80 毫粮（本脚本对 19,176 行逐行验证，0 个例外）；
     占差额 0.0104% / 0.0616% / 0.1932%。它来自“单日口粮×120”与“整周期 p×10,000”的望远镜残差，
     不是逐日 floor 损失的累积（整周期 Σdaily == p×10,000 是精确的）。
  ③ 时点错位：**这是全部剩余差额（99.81%~99.99%）**。精确链路——
     step(day) 先写 naturalNeeds（日初人口）→ 同一天 day%30==0 才做月度出生/死亡并回写行人口；
     120/240/360 都是 30 的倍数 ⇒ dump 日必然踩在这条缝上。gap = 10,000×(deaths_d−births_d) + 取整项。
     未拆分部分：deaths_d 与 births_d 各自是多少（见第八节）。
  ④ 种子/生产投入：**两量都不含**。grainDailyConsumption 只读 naturalNeeds（grain），
     naturalNeeds 由 dailyNeedsMilli 写（grain+cloth）；种子/原料走 drawCycleInputs → FlowRow.consumed。
  ⑤ 缺格/城乡分层：**排除**。799/799 格 activated；每格 8 行（rural/urban × 4 阶层）；
     pop==social、pop==Σclasses、daily==ΣnaturalNeeds 三项逐格 799/799 精确；
     三国格数 430/138/231、social.at.tick 逐格 = dump tick。

[八、种子/投入对照（把④量出来）]
  | tick | Σ naturalNeeds.grain（=LHS/120） | Σ flow.consumed.grain（含种子/原料） | consumed ÷ naturalNeeds | Σ flow.unmetNeed.grain（本周期累计） |
  |---:|---:|---:|---:|---:|
  | 120 | 979,278,743 | 63,971,340,361 | 65.324956 | 72,499,138,932 |
  | 240 | 948,820,672 | 107,351,895,836 | 113.142450 | 12,656,403,661 |
  | 360 | 946,485,865 | 106,873,871,087 | 112.916500 | 11,271,777,057 |

[九、现存读数能精确重建什么、不能重建什么]
  能（本脚本已逐值做出来）：
    · p_start_r：每行“日初人口”由 daily_r 反解唯一确定（dailyRationMilli 在 p 上严格递增，p≤500,000 已核）；
    · delta_r = p_start_r − p_end_r：快照日一天的人口净变化（**净额精确**）；
    · round_r ∈ {0,40,80}：取整残差精确；
    · 因此逐行/逐格/全球“差额 = 单日人口变动项 + 取整残差项”是**恒等式**，不是拟合。
  不能（现有 JSON 里没有）：
    · deaths_d 与 births_d 的**当日拆分**：flow.deaths/births 是“本周期累计”，只给 0≤deaths_d≤累积、0≤births_d≤累积；
      （本脚本对 3 tick 全部 6,392 行/个做了该可行性检查，逐 tick 违反数见第一节。）
    · 周期内逐日人口/日耗序列：naturalNeeds 逐日覆盖，dump 只留最后一天那一份；
    · 真正的“本周期实际需求” Σ_{day∈周期} Σ_row dailyRationMilli(pop_row(day), day)：现存代码/读数都没有这个量；
    · 快照日的人口变化是否**全部**来自月度结算：由代码次序与 day%30==0 推出，但没有 d−1 的 dump 直接核对。
  最小补读数方案（供 M2 设计，零 Java 改动的部分先做）：
    (a) 每个关账日追加一次 **d−1** 的同一份 h6sim_dump（119/239/359）。于是
        flow.deaths(d) − flow.deaths(d−1) = deaths_d（births 同理，逐行精确）；
        pop(d−1) 应逐行 == 本脚本反解出的 p_start(d)，这是对整条链的独立验收。
    (b) 若要看“真正的周期需求”，在结算侧加一个**逐行累加器** cycleNaturalNeedMilli =
        Σ_d dailyRationMilli(row.population(day), day)（新周期第一天归零），由 ApiViews 读出；
        这需要一行 Java 级改动（M2 读数设计），但与今天的 naturalNeeds 同源、不另立公式。
    (c) 读口补 tick/lastSettledDay 与“本值用的人口快照”字段：今天 economyHex 入参没有 tick，
        物理上无法告诉读者 naturalNeeds 是哪一天、哪一份人口的。
    (d) 要定义“可比口径”，只能二选一：
        · 日率口径：grainDailyConsumption vs dailyRationMilli(当日日初人口, d)；
        · 周期口径：cycleNaturalNeedMilli vs cumulativeRationMilli(周期初人口, cycleDays)（人口不变时才相等）。
        “单日快照×120”与“日末人口×10,000”在任何人口变动下都不可互推。

[附：顺带核到的一个旁证（不属于丁的结论）]
  §1.7 甲组“首都 360,862（城镇 345,908）/ 3,100 亩 / 库存 236,854,357 / 日耗 30,381,003”
    = 本批 tick120 的 (-39,-71)（pop=360,862，daily=30,381,003）；
  §1.4 的“273,770（城镇 259,184）/ 需求 2,737,700” = 本批 tick360 的同一格
    （pop=273,770，RHS=pop×10,000=2,737,700,000）。两者都对，只是不同 tick。

=== m07 结束：以上所有数字都来自三个 md5 已钉死的 JSON，可由本脚本重算。 ===

```


## 附录 B：证据索引

| 结论 | 证据落点 |
|---|---|
| `grainDailyConsumption` = Σ `naturalNeeds[grain]` | `ApiViews.java:467`；脚本 `[一]`（799/799）；`[二]`（Σdaily） |
| `naturalNeeds` 用日初人口、逐日覆盖 | `EconomySettlement.java:2303, 4054-4057`；`EconomyVocabulary.java:138,160`；脚本 `[三]` 的 p_start 反解 |
| dump 的 `population` 是日末 | `PopulationEconomyTimeParticipant.java:181,202,204,208`；`PopulationDynamics.java:69`；`EconomySettlement.java:1071`；脚本 `[六]` 跨周期对平 |
| 差额 = 单日人口变动项 + 取整项 | 脚本 `[三]`（恒等式核验 OK；人口项 99.81%~99.99%） |
| 取整只能是 0/40/80 | 脚本 `[三]` 分布表（0 例外；max 80）；推导见 §3.1 |
| 不是种子/投入 | `FlowRow.java:48`；脚本 `[八]`（consumed/naturalNeeds = 65.32/113.14/112.92） |
| 不是缺格/分层 | 脚本 `[一]`（799 格、8 行/格、三项 799/799、坐标唯一、430/138/231） |
| flow.deaths/births 窗口=本周期累计 | 脚本 `[一]`（MORTALITY evidence 全等 799/641/41）；`FlowRow.java:67`；脚本 `[六]` 跨周期对平 |
| 随 tick 变大的主因是首都 | 脚本 `[四]`（首都 delta 3,710→10,417→15,433、p_end 360,862→328,882→273,770） |
| 整周期下降不是机制 | 脚本 `[七]`（候选① 1.51×/9.14×/1.23×；单日/整周期 = 66.07%/10.93%/81.15%） |

---

**报告结束。** 以上所有数字均可由附录 A 的命令重算；三个输入 JSON 的 md5 已在脚本里钉死，不符即拒绝运行。
