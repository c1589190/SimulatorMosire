# gov 整批真档验证：run6（新创世行政链）vs run5/run4（旧口径）

> 目的（设计书 §11 Z6b）：用空库首启的新创世世界跑 360 tick，验证 Z1a~Z5 的行政链在真档里能收税、能付俸禄/军俸、
> 能按预算顺序执行、能发告警，并对照 run4/run5（旧公式/旧 setup）看债务与人口/粮储走向。
> 结论先行：**F1 从 0 → 25% 实收；F2 军俸 no-payable-leg 归零、省俸禄/工资全程支付；债务开始跨关账日累积**
> （tick360 = 126,971,967 毫粮，run4/run5 仅 6,312）——但累积机制主要是**每日存量税把家户粮抽进国库**，
> 且发现**省辖区把中央国库家户也征税**、中央 49 天后停付等 4 个待裁/待修点。

## 1. 装置

| 项 | run4 | run5 | run6（本批） |
|---|---|---|---|
| 公式/口径 | 旧 `GovEfficiency`（两维取 min + 超编加成） | 同 run4（经济新公式，gov 未动） | Z1a~Z5 全新：承诺→两维效率、预算顺序、ADMIN_SALARY、告警、外部岗位 |
| tick0 建场 | 脚本建 2 GOV(SCRIBE:2)+2 军+税 100‰ | 同 run4 | **创世内置**：2 GOV + office 产业/unit + 官吏户/POST/tier-3 + GOV_SERVICE 32000 + 计划(16000/32000)+预算+国库注资；脚本只补 2 军+军俸 |
| 官吏规模 | 0 YAMEN、2 SCRIBE | 同 run4 | 每 GOV 1 官吏户（2 名成年男，POST/tier-3），中央满覆盖、省半覆盖 |
| 分段/日志 | 12×30，全 DEBUG | 同左 | 同左（端口 5891/5895/5893） |

run6 tick0（`simos.gov.info` 真读）：中央计划 (16000,16000)、供给 (16000,16000)、效率 **1000‰**；省计划 (32000,32000)、
供给 (16000,16000)、**250‰**；两 GOV 国库粮 1,000,000 / 布 100,000 / 银 100,000 毫；承诺 `GOV_SERVICE 32000`/户；
岗位 POST/tier-3；`projectedStaff POST=2`；`GovServiceFlow` 两维 16000/16000（产出=有效=消费，未用 0）。

## 2. 三段读数对照（19 格求和，毫）

| tick | run4/5 pop | run4/5 grain | run4/5 debt | run6 pop | run6 grain | run6 debt |
|---|---|---|---|---|---|---|
| 30 | 4970 | 78,271,727 | 530,406 | 4970 | 80,239,848 | 12,941 |
| 120（关账） | 4995 | 1,290,106,869 | **12,514** | 4997 | 1,253,962,855 | **6,048** |
| 240（关账） | 5047 | 2,542,095,612 | **4,609** | 5047 | 2,511,368,733 | **47,855,522** |
| 360（关账） | 5085 | 3,868,556,871 | **6,312** | 5078 | 3,250,188,618 | **126,971,967** |

run6 债务在 day240 起不再清零、day360 达 1.27 亿毫（run5 的 ~2 万倍）；人口少 7 人；家户粮储少 6.18 亿毫
（= 被抽进省国库的税，见 §3）。

## 3. 政府指标（run6 实测）

**F1 税：0 → 25% 实收**（省效率 250‰；中央无辖区）：

| day | grainAssessed | grainCollected | 实收率 | adminShortfall |
|---|---|---|---|---|
| 1 | 8,905,333 | 2,226,320 | 25.0% | 6,679,013 |
| 120 | 120,019,428 | 30,004,809 | 25.0% | 90,014,619 |
| 240 | 174,767,593 | 43,691,854 | 25.0% | 131,075,739 |
| 360 | 124,004,669 | 31,001,118 | 25.0% | 93,003,551 |

360 天 `grainCollected` 合计 **2,040,450,189 毫**（≈204 万粮），全部落省国库。

**F2 俸禄/工资/军俸**：

- 省 `GOV_ADMIN_SALARY_DAY` **360/360 天**实付 粮 320+银 32（合计工资 115,200 毫）；`GOV_OFFICE_UPKEEP_EVALUATED`
  360/360 天粮 166 实付（合计 59,760 毫）。
- 中央：工资/俸禄只付到 **day49**（之后 311 天全 0），原因见 §4-D1/D2。
- `PERIODIC_ADJUSTMENT_RULE` 539 条全部 `EXECUTED`，**`no-payable-leg` = 0**（run4 是 day2/122/242 全跳过）；
  但 day122 首都卫队粮腿 `paidGoods={}`（只付了银 30），属部分支付，见 §4-D4。
- 360 天实付合计：工资 130,880 毫、行政俸禄 67,894 毫 —— 远小于税收 2.04B（国库只进不出，见 §4-D3）。

**告警**：`GOV_BUDGET_ALERT` 475 条，全部 `ADMIN_BUDGET_SHORTFALL`（中央 `treasuryLimitedValue=522/日`；
省多日 `requested=170, authorized=166, shortfall=4`）；无 `ADMIN_PLAN_MISSING`/`ADMIN_SERVICE_FLOW_ZERO`/`ADMIN_CONTRACT`。
效率读数：中央 1000‰、省 250‰（coverage 1000/1000 与 500/500）；`GOV_STAFF_PROJECTION_MISMATCH` = 0。

## 4. 发现（4 个待裁/待修 + 2 个旧开口）

**D1（设计/裁定级）省辖区把中央国库家户也征税**：日志逐日出现
`HOUSEHOLD_STOCK_DEDUCTED household=hh-gov-gov-central reason=jurisdiction_tax to=hh-gov-gov-province`，
共 **173 天**（day1~173）——中央座位格在 `small-world` 省内，中央的 100 万毫国库粮被下级省按税率抽走，
中央国库最终见底。需要裁定：政府家户免税、辖区互斥、或中央/省之间的显式拨款（不得默认"下级向上级征税"）。

**D2（缺陷）预算 oracle 与实扣时序不一致**：中央 day49 停付，但 `jurisdiction_tax` 扣款持续到 day173；
`ADMIN_BUDGET_SHORTFALL` 从 day3 起报"treasury-limited"。说明 `GovBudgetExecutionBridge.treasuryAvailable`
（`AccountSession.householdAccount(treasury)`）与税收扣款/执行器落账的"可用账/冻结/最小保留"口径有偏差；
`ADMIN_SALARY(requested=352, authorized=0)` 与实际仍有部分粮腿支付并存。需要一次定点排查（账户键/冻结/保留）。

**D3（裁定级）存量税 + 每日评估 = 指数式抽干**：税率 100‰×效率 25% → 每天抽走家户库存的一部分，
国库 2.04B 只花 0.2B ⇒ 家户粮被持续抽进国库、关账日无力偿债、债转本。**债务累积达标**（用户要的形态），
但机制里"税基=存量、每日评估、无最低口粮保护"正是 F1/R2 一直待裁的口径；需要你把税基定成
存量/流量/混合、是否保护最低口粮、国库是否必须有支出回路（否则就是黑hole）。

**D4（缺陷）军俸"部分支付"语义**：day122 首都卫队粮腿空、状态仍 `EXECUTED`、`shortfallGoods={}`；
与 run4 的 `no-payable-leg` 口径不同，需要明确"EXECUTED + 空腿"是否应记具名缺口/告警。

**旧开口**：F3（`world-silver`/M1 铸币仍不接，注资走显式命令）原样；F4（19 hex 满覆盖需 100/139 吏员）——
run6 用 1/1（中央）与 2/2（省）的小世界标定，满覆盖留给决策人招募（工具已做、无自动招募）。

## 5. 结论

- **正面**：新行政链在真档里跑通——承诺→两维供给→效率（1000/250‰）→ 按承诺小时发工资、预算五类顺序、
  军俸不再全跳过、六类告警只告警不自动、服务流量逐 tick 不落库、`simos.gov.info` 全域可读；
  税收从"全额行政损耗"变为按效率实收（25%）；**债务首次跨关账日累积**（1.27 亿毫）。
- **要改**：D1/D2/D4 是缺陷级（辖区税/GOV 家户、oracle 一致性、部分支付语义），D3 是税制裁定级；
  这些不阻塞本批"框架落地"的结论，但阻塞"税制/财政闭环"的下一批。
- 建议下一批（Z6c/税制批次）：① GOV 家户免税或辖区互斥；② 预算 oracle 与实扣同源；③ 税基/最低口粮裁定；
  ④ 军俸部分支付语义；⑤ F4 小世界满覆盖标定与决策人招募闭环；⑥ F3 M1/world-silver 关系。

## 6. 证据路径

- run6：`/home/cna/simos-runs/2026-10-23-sw19-run6/{service.log,seg.log,dumps/seg-*.json,setup_tick0.py,setup-tick0.json,store}`
- run5/run4 对照：`/home/cna/simos-runs/2026-10-23-sw19-run5|run4/{service.log,dumps}`
- 关键日志：`TAX_DAILY_END`（day1/120/240/360）、`GOV_ADMIN_SALARY_DAY`、`GOV_OFFICE_UPKEEP_EVALUATED`、
  `PERIODIC_ADJUSTMENT_RULE`、`GOV_BUDGET_ALERT`、`HOUSEHOLD_STOCK_DEDUCTED ... reason=jurisdiction_tax`。
