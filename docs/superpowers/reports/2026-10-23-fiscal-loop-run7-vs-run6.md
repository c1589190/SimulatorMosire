# 财政闭环 run7 实测：上缴 500‰ → 抗税 0‰（对照 run6 无上缴）

> 状态：**实测运行 + 只读核账**。生产代码未改动；唯一仓库写盘 = 本报告。代码基线 `c4e113d1`（Z7e-1 测试与全仓 `clean verify` 已过，见 §7）。
> 事实来源：run7 现场 `/home/cna/simos-runs/2026-10-23-sw19-run7/`（`service.log`、`seg.log`、`refusal.log`、`dumps/seg-0*.json`、`dumps-refusal/refuse-*.json`、`snap-tick360.json`、`snap-tick480.json`、`tax-sums.txt`、`setup_tick0.py`、`run_refusal.py`）+ run6 对照 `/home/cna/simos-runs/2026-10-23-sw19-run6/` + 代码 `file:line`。
> 裁定依据：`docs/superpowers/specs/2026-10-23-fiscal-loop-design.md` §2/§9/§22/§23（用户原文：上缴"定期，但省政府一定要有能力改，也就是抗税"；D1"中央辖区本身也是一个省"）。

---

## 0. 一句话结论

1. **上缴闭环成立**：省按"当周期实收税的 500‰"在周期关账日足额上缴，13 笔合计 **966,011,756 粮 / 33,597 银**，与"阶段一日收 1,932,023,520 粮 × 500‰"逐周期 floor 后只差 **4 毫粮**（银差 3 毫）。
2. **抗税成立**：省单方面把 `remittancePerMilleToSuperior` 500→0 后，4 个关账日全部 `GOV_REMITTANCE_SKIPPED reason=rate-zero`；120 天内省粮 **+868,291,567**，中央粮 **−56,673**（只花自己的俸禄，无收入）。
3. **没有出现"中央官吏逃亡"**：中央国库 tick360 有 **966,810,484 粮**存量（≈5800 天俸禄），抗税 120 天窗口内不可能见底。能成立的结论只是**结构事实**：中央独立财政收入 **恒为 0**（`capital-province` 税率 0‰、无铸币、无自辖税收），100% 依赖上缴；"上缴断→中央财政危机"在现行参数下需要远长于 120 天的窗口，或 F3（铸币/发债）落地后才可复现。
4. **全 480 天零告警、零逃亡**：`GOV_BUDGET_ALERT` = 0；480 个 `GOV_SERVICE_DESERTION_DAY` 全部 `rises=0 falls=0 tierCrossings=0 flights=0`；两个官吏户 satiety 恒 1000、fleeRate 恒 0。
5. **首跑（污染轮）根因已定位并规避**：`simos.gov.setBudgetPolicy` 是**整表替换**语义，只传 `remittancePerMilleToSuperior` 会清空 `orderedCategories` + `officialSalaryRule` → 次日起 `ADMIN_PLAN_MISSING`、day1 起 stipend/salary 全欠 → day3 起官吏逃亡。本轮用**完整预算载荷**重跑后该现象完全消失（见 §4，含待裁定的修复建议）。

---

## 1. 两轮可比性（run6 vs run7）

| 维度 | run6 | run7 |
|---|---|---|
| 端口（GUI/MCP/审批） | 5891 / 5895 / 5893 | 5901 / 5905 / 5903 |
| 代码基线 | Z7 前（run6 报告） | `c4e113d1`（Z7a–Z7d 全量） |
| 创世 | 同一 bootstrap：2 GOV + office + 官吏户 + 承诺 + 行政计划 + 5 类预算 + 薪资 10/1 + 注资 | 同左 |
| 军队/军俸 | 首都卫队 100（属中央）、镇戍 60（属省）；120 天周期 | 同左（rev 2–5） |
| 省税率 | bootstrap 100‰（`UNIT_SET_TAX_RATE_APPLIED ratePerMille=100`） | bootstrap 100‰（同） |
| 上缴 | **0 笔**（`GOV_REMITTANCE` 事件 = 0） | rev6 设 500‰；tick360 后设 0‰ |
| 段长 | 12×30 = 0→360 | 阶段一 12×30 = 0→360；阶段二 4×30 = 360→480 |

⇒ run6/run7 的税率、创世、军队、军俸完全一致，**唯一自变量 = 上缴率（0 vs 500‰，后 120 天再回 0）**，是干净的 A/B。

---

## 2. 阶段一（tick 0→360，上缴 500‰）

### 2.1 上缴流水（13 笔全部足额）

| 关账日 | dueGrain | paidGrain | dueSilver | paidSilver |
|---:|---:|---:|---:|---:|
| 30 | 9,985,907 | 9,985,907 | 12,563 | 12,563 |
| 60 | 6,035,495 | 6,035,495 | 2,529 | 2,529 |
| 90 | 4,156,919 | 4,156,919 | 1,319 | 1,319 |
| 120 | 16,790,860 | 16,790,860 | 951 | 951 |
| 150 | 183,526,570 | 183,526,570 | 1,409 | 1,409 |
| 180 | 83,371,246 | 83,371,246 | 1,973 | 1,973 |
| 210 | 36,516,335 | 36,516,335 | 2,034 | 2,034 |
| 240 | 35,419,709 | 35,419,709 | 2,554 | 2,554 |
| **243** | 45,279,556 | 45,279,556 | 252 | 252 |
| 270 | 282,720,891 | 282,720,891 | 2,534 | 2,534 |
| 300 | 150,901,445 | 150,901,445 | 1,873 | 1,873 |
| 330 | 67,772,330 | 67,772,330 | 1,948 | 1,948 |
| 360 | 43,534,493 | 43,534,493 | 1,658 | 1,658 |
| **合计** | **966,011,756** | **966,011,756** | **33,597** | **33,597** |

`shortfallGrain=0 / shortfallSilver=0` 全程；13 个 `GOV_REMITTANCE_DAY` 全是 `INFO`。

### 2.2 关账节奏 = 经济产业周期关账（不是日历 30 天）

- 代码：`GovRemittanceBridge.java:138` `if (!cycleClosed) { ... }` —— `cycleClosed` 来自 `EconomyDayStepper.lastCycleClosed()`（类注 `:58`），即**只有经济侧报告"有产业周期关账"的那一天才结算**。
- 日志交叉验证：13 个上缴日集合 ⊆ `PRODUCTION_EFFICIENCY_HARVEST` 出现的日集合（`remit ⊆ harvest: True`）。
- 因此出现 **day 240 → day 243 的 +3 漂移**（下一次 243→270 自动 −3 回正），day 360 → 363 同型。这不是上缴桥的 bug，而是经济周期关账本身的短周期。

### 2.3 无重复上缴（累加器清零正确）

按日实收税（`TAX_DAILY_END`）逐段验算 500‰：

| 周期实段 | 实收粮 | ×500‰ | 对应 `GOV_REMITTANCE_DAY` 实付 | 对齐 |
|---|---:|---:|---:|---|
| day 211–240 | 70,839,418 | 35,419,709 | day240 = 35,419,709 | ✓ |
| day 241–243 | 90,559,113 | 45,279,556.5 → 45,279,556 | day243 = 45,279,556 | ✓ |
| day 331–360 | 87,068,987 | 43,534,493.5 → 43,534,493 | day360 = 43,534,493 | ✓ |

⇒ day243 的 45.3M 不是"未清零的重复上缴"，而是 day240 大关账（存量税尖峰：assessed 168,158,163，前一日 2,531,217）之后 3 天 90.6M 实收的一半。周期累加器在每个关账日正确清零。

### 2.4 总量对账

- 阶段一日收：**1,932,023,520 粮 / 67,201 银**（480 条 `TAX_DAILY_END` 求和，`tax-sums.txt`）。
- ×500‰ = 966,011,760 粮 / 33,600 银；实付 966,011,756 / 33,597。
- 差 **4 毫粮 / 3 毫银** = 13 个周期逐次 `floor` 的累计（每个周期最多丢 1 毫），量级正确、无系统性漏账。

### 2.5 ticket 360 双库读数（`snap-tick360.json`）

| | 中央 `gov-central` | 省 `gov-province` |
|---|---:|---:|
| 粮 | 966,810,484 | 966,810,963 |
| 布 | 98,560 | 98,560 |
| 银 | 122,013 | 122,054 |
| 逃亡率 | 0 | 0 |
| satiety | 1000 | 1000 |
| 效率 | 1000‰ | 250‰ |

- 两库几乎相等 = "上缴 500‰ 对半分"的直观结果：中央 = 初始 1,000,000 + 上缴 966,011,756 − 自身俸禄/工资支出（≈201,272）；省 = 初始 1,000,000 + 税后留存 966,011,760 − 自身支出（≈201,000）。
- 省效率 250‰ 是 bootstrap 计划口径的内生结果：省行政计划两维各 2×标准劳动定额、实配 2 名官吏 ⇒ 覆盖 500‰/500‰ ⇒ `500×500/1000 = 250‰`（中央计划各 1×、同样 2 名 ⇒ 1000‰）。不是缺陷，是创世规模差异。

### 2.6 世界读数 tick360（run6 vs run7）

| | run6 | run7 |
|---|---:|---:|
| 人口 | 5,078 | 5,078 |
| 粮存量 | 3,250,188,618 | 3,255,230,807 |
| 债务本金 | 126,971,967 | 104,827,149 |

- run6 债务更高：其省税在 **day1–173 抽了中央国库 173 次**（D1）且 **day50 市场把中央国库粮腿清零**（D2），两项都在 run7 被 Z7a/Z7b 修掉；上缴把省的一半税收转给中央后，省的留存变少、存量税基数路径也变。

### 2.7 Z7a/Z7b 修复的正面证据

- **D1（辖区互斥）**：`household=hh-gov-gov-central reason=jurisdiction_tax` 命中数 run6 = **173**、run7 = **0**。
- **D2（国库不被市场卖单）**：run7 中央国库全部扣减事件按 reason 分布 = `admin_upkeep`（960 条，2 腿/天×480 天）+ `admin_salary`（480 条，1 腿/天）+ `military_salary`（4 条，120 天周期×4 次），**无任何市场/抽税腿**；粮额 480 天保持 966M 量级，未出现 run6 的 day50 清零。

---

## 3. 阶段二（tick 360→480，抗税 0‰）

### 3.1 操作

`run_refusal.py` 在推进前调用 `simos.gov.setBudgetPolicy`（**完整预算载荷**：5 类 `orderedCategories` + `officialSalaryRule 10/1` + `remittancePerMilleToSuperior:0`），preview→apply：`noop=False`，`remitBefore=500 → remitAfter=0`（rev 19）。

### 3.2 上缴侧

- 4 个关账日（390/420/450/480，另 day363 短周期）全部 `GOV_REMITTANCE_SKIPPED ... unit=gov-province ... reason=rate-zero`（`INFO`）；中央自身也每个关账日 `SKIPPED reason=rate-zero`（无 superior）。
- 阶段二实收 **868,349,992 粮 / 5,724 银**；应缴 500‰ = 434,174,996 粮 **未离开省库**。

### 3.3 国库轨迹（tick360 → tick480，`snap-tick480.json`）

| | 中央 | 省 |
|---|---:|---:|
| 粮 | 966,810,484 → 966,753,811（**−56,673**） | 966,810,963 → 1,835,102,530（**+868,291,567**） |
| 银 | 122,013 → 118,143 | 122,054 → 123,920 |
| 布 | 98,560 → 98,080 | 98,560 → 98,080 |
| 逃亡率 | 0 → 0 | 0 → 0 |
| 效率 | 1000‰ → 1000‰ | 250‰ → 250‰ |

- 中央 120 天只花不挣：−56,673 粮 ≈ 日俸 166 + 军俸分摊，**存量可撑 ≈5800 天**。
- 省 120 天净增 868.29M ≈ 全部实收（868,349,992）− 自身双俸支出（≈58,425）。
- 世界读数 tick480：人口 5,142、粮存量 4,046,222,086、债务本金 207,849,183（债务继续按 D3"存量税 + 每日评估"路径增长，属既有特性）。

### 3.4 全期零异常

- `GOV_BUDGET_ALERT`：**0**（全 480 天）。
- `GOV_SERVICE_DESERTION_DAY`：480 天，全部 `rises=0 falls=0 tierCrossings=0 flights=0 fledPopulation=0`。
- day1 起两 GOV `GOV_OFFICE_UPKEEP_EVALUATED grainPaid=166 clothPaid=4`（首跑污染轮为 `grainPaid=0/clothPaid=0`）。

---

## 4. ★ 首跑污染根因：`setBudgetPolicy` 整表替换语义（待裁定修复）

### 4.1 现象（首跑 run7，已作废）

- rev6 只传 `remittancePerMilleToSuperior:500` 调 `simos.gov.setBudgetPolicy` 后：
  - day1–5 `GOV_BUDGET_ALERT kind=ADMIN_PLAN_MISSING`；
  - `GOV_OFFICE_UPKEEP_EVALUATED gp=gov-province grainPaid=0 clothPaid=0`；
  - `GOV_SERVICE_DESERTION_DAY` 自 day3 起 `stipendShortfallMilli=170` → 官吏户逃亡；
  - 省效率崩塌、`GOV_REMITTANCE_DAY` 只剩 day30 一笔。

### 4.2 根因

- `GovPayloads.budgetPolicy`（`simos-gov/src/main/java/io/mosire/simos/gov/spi/GovPayloads.java:124-146`）：**omitted `orderedCategories` = 空表（无自动付）**、omitted `officialSalaryRule` = 0/0、omitted `remittancePerMilleToSuperior` = 0。
- 即 `gov.SetBudgetPolicy` 的载荷是**整表替换**，不是 patch。工具描述也已写明"缺省空表=不自动付"，但"决策人只想改上缴率"这个高频动作会顺手清空预算。

### 4.3 影响与规避

- 影响：省/中央决策人（走决策人桶 + GM 审批）在改上缴率或调薪资规则时，若不回填其余字段，会静默清空预算 → 下次日结停俸 → 逃亡。**这是可用性缺陷，不是机制设计缺陷**。
- 本轮规避：setup 与抗税脚本均用**完整载荷**（`setup_tick0.py` / `run_refusal.py` 已固化该写法，并带 preview 断言"预算未丢"）。

### 4.4 修复候选（**未动手，待裁定**）

| 方案 | 内容 | 代价/风险 |
|---|---|---|
| **A（推荐）** | `GovPayloads.budgetPolicy` 改 patch 语义：缺省字段保留现值；另加显式 `clearOrderedCategories:true`（或 `orderedCategories:[]` 明示）才清空 | 需要区分"缺省=保留"与"显式空=清空"，JSON 里 `[]` vs 缺省可区分；要改 round-trip 测试 |
| B | 工具层加 `mode:"patch"|"replace"`（缺省 replace，保持兼容） | 语义显式，但调用方仍会忘 |
| C | 语义不动，工具在缺字段时把"现政策"回填进 preview 展示（"将保留 N 类"） | 最保守，但真正写库的仍可能是被清空的载荷 |

---

## 5. 与 F3/F4 的关系（本批未做）

- **F3 中央铸币/发债**：本轮量化了中央独立收入 = 0（`capital-province` 0‰ + 无 mint + 无自辖税），且存量 966M 在 120 天窗口内完全掩盖依赖。要不要给中央铸币/发债权仍是开口决策。
- **F4 全覆盖校准**：省效率 250‰ 的覆盖口径（2 官吏 vs 2×标准计划）本轮未调；中央 1000‰/省 250‰ 的对比是创世参数结果。
- **D3 存量税 + 每日评估 = 特性**：本轮不动；它正是 day240 大关账（assessed 168M）与阶段二债务继续增长的来源。

---

## 6. 复现

```bash
# 服务（新库）
cd /home/cna/SimulatorMosire
SIMOS_SMALL_WORLD_JAR=.../simos-app-0.1.0-SNAPSHOT-shaded.jar \
SIMOS_SMALL_WORLD_STORE=/home/cna/simos-runs/2026-10-23-sw19-run7/store \
SIMOS_SMALL_WORLD_GUI_PORT=5901 SIMOS_SMALL_WORLD_MCP_PORT=5905 \
SIMOS_SMALL_WORLD_APPROVAL_PORT=5903 ./run-small-world.sh

# rev2-6：2 军队 + 军俸 + gov-province 完整预算载荷（含 remittance=500‰）
python3 /home/cna/simos-runs/2026-10-23-sw19-run7/setup_tick0.py

# 阶段一 0→360（12×30，19 格 dump → dumps/）
python3 /home/cna/simos-runs/2026-10-23-sw19-run7/run_segments.py

# 阶段二 360→480（先完整载荷把 remittance 设 0，4×30 → dumps-refusal/）
python3 /home/cna/simos-runs/2026-10-23-sw19-run7/run_refusal.py
```

关键读数命令：`/tmp/govsnap.py 5905`（双 GOV 国库/逃亡/效率快照）、`grep -c 'GOV_BUDGET_ALERT' service.log`、`grep 'event=GOV_REMITTANCE_DAY' service.log`、`grep -c 'household=hh-gov-gov-central reason=jurisdiction_tax' service.log`。

---

## 7. 边界（诚实声明）

- **没跑到**：中央财政危机/官吏逃亡（需要更长窗口先把 966M 存量耗掉，或 F3 铸币落地）。
- **没做**：`setBudgetPolicy` patch 语义修复（§4.4 待裁定）；F3/F4；省效率覆盖校准。
- **未逐格核**：阶段二 19 格 dump 已落盘（`dumps-refusal/refuse-390/420/450/480.json`），本报告只做了 GOV 级与总量级核账；逐格分布留待需要时再取。
- 证据目录在仓库外 `/home/cna/simos-runs/...`，未入库。
