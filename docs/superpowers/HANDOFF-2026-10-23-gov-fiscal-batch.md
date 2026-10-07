# HANDOFF 2026-10-23：经济生产效率框架 + gov 行政服务模式 + 税制/财政闭环（Z 批收口）

> **恢复入口**。当前 `main` 工作树干净、0 未推送，最后提交 `52781f5a`（已 push `origin/main`）。
> 本会话三批（经济生产效率 / gov 行政服务模式 / 税制-财政闭环）全部做完、实测、门禁全绿；剩余开口见 §4。
> 上一份 handoff：`docs/superpowers/HANDOFF-2026-10-22-d5-decision-turn-economy.md`；
> 计划内未实现盘点（仍有效的总清单）：`docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md`。

---

## 0. 一句话状态

- **HEAD**：`52781f5a` `docs(gov): Z7e-3 台账补真服务探针证据`。
- **门禁**（`06c6a65b` 上最后一次全仓）：`clean verify` **BUILD SUCCESS 4:04** —— Surefire **3359 / 0 失败 / 0 错误 / 5 跳过**，
  SpotBugs **15/15 模块零**，前端 **412/412**，Spotless 绿。
- **运行现场**：无 `simos-shaded` 进程在跑（run7 与 Z7e-3 探针都已停）；`/home/cna/simos-runs/**` 的 store / dumps / 日志原样保留。
- **用户裁定在效**：本文件 §2 各 spec 顶部的"用户原话（逐字）"节即权威；与本文件冲突以 spec + 用户最新裁定为准。

---

## 1. 本会话完成的三批

### 1.1 经济：单 tick 生产效率框架（Z1–Z4b）

- 公式（用户逐字冻结）：`产出 = 投入劳动力 × 生产资料满足率 × 产品产出数量常数 × 修正参数 + 余数结转`；
  只有**产品产出数量常数**对 GM 可改（`outputQuantityOverrides`），**修正参数**只能被程序内机制逐 tick 注入、GM 改不了。
- `ProductionEfficiencyBook` / 4 位余数结转 / `GOV_SERVICE` commitment kind（C7 不可缩）/ 逐 tick 注入面。
- 文档：`specs/2026-10-23-production-efficiency-framework.md`；台账 `.superpowers/sdd/2026-10-23-production-efficiency-framework/{z1,z2,z4a,z4b}-impl-ledger.md`。
- 提交链：`e59e4aa8`（34/15 参数）、`0cfd8fc6`、`f279cc62`、`8eff7bb7`、`83c6c78a`、`6c0836e1`、`db4f899d`。

### 1.2 gov：行政 / 服务模式（Z1a–Z6b）

- `GovAdministrationPlan`（两维计划劳动 + 3 档岗位 + 4 静态修正 + 超标 `⌊√(excess/equivalentPost × k)⌋`，都不封顶）；
  静态修正 GM 可改、动态修正程序内注入；覆盖不足 ⇒ 效率下降（"政府不招人凭什么有行政效率"）。
- `GovBudgetPolicy`：有序类别优先级 `ADMIN_STIPEND / MILITARY_STIPEND / ADMIN_SALARY / DEBT_SERVICE / OTHER`
  + `GovOfficialSalaryRule` + `remittancePerMilleToSuperior`；预算=共享国库池、按优先级执行、缺额只告警。
- 工具面补齐（读口 `simos.gov.info`、写口窄工具、catalog 同源）；有问题只给 GM/决策人一个告警。
- 文档：`specs/2026-10-23-gov-service-mode-design.md`（§13–§23 为控制器裁定）、
  `reports/2026-10-23-gov-service-mode-feasibility.md`（C1–C9 冲突与 11 条裁定）；
  台账 `.superpowers/sdd/2026-10-23-gov-service-mode-design/{z1a..z6a}-impl-ledger.md`。
- 提交链：`be6ef44b`…`e4c52657`（见 `git log --oneline` 2026-10-23 段）。

### 1.3 税制 / 财政闭环 + 饥饿-逃亡（Z7a–Z7e）

- **D1**：中央辖区 `capital-province` 是**独立省级区划**（"直辖市、直辖区就是独立的省份"），省= `small-world`；
  中央对新辖区初始税率 0‰（中央自定）。
- **D2**：国库户 / `hh-unit:*` 市场排除（run6 day50 清仓的根因）。
- **D4**：预算逐腿账本 `requested/authorized/paid/shortfall` + `PARTIAL`/`SKIPPED`，缺额只告警。
- **remittance**：周期关账日（锚定经济产业周期关账）按本周期实收税 × ‰ 上缴 superior；
  省可随时改率含 0 = **抗税**，中央不能强制。
- **Social 饥饿**：`satietyPerMille` 按户持久，直接折算 `householdLaborMilli`，其他模块不再自算。
- **逃亡**：欠俸/饥饿快升、满足慢降；成员级转移、**不带走公家财产**、去向按 容量余量×预期利润 → 人均财富 → id。
- 文档：`specs/2026-10-23-fiscal-loop-design.md`（§0 用户原话 + §10 Z7e-3 双模）、
  `reports/2026-10-23-fiscal-loop-investigation.md`（D1/D2/D4/闭环根因）、
  `reports/2026-10-23-fiscal-loop-run7-vs-run6.md`（实测）；
  台账 `.superpowers/sdd/2026-10-23-fiscal-loop-design/{z7a,z7b,z7c,z7d1,z7d2,z7e1,z7e3}-impl-ledger.md`。

### 1.4 Z7e-3：`gov.SetBudgetPolicy` 双模（本会话最后一个代码批）

- 用户裁定原文："AB同时应用吧" ⇒ PATCH（`mode` 缺省，缺省字段保留现值）+ 显式 `mode:"REPLACE"`（旧整表替换）。
- `orderedCategories:[]` 才清空；工资规则逐内层字段合并；`mode` 不落状态；命令/工具/目录三面同源，preview 带 `editMode`。
- 提交：`06c6a65b`（实现+测试+设计书 §10+台账）、`52781f5a`（真服务探针证据）。
- 动因是 run7 首跑污染：只传 remittance 改 0 抗税 ⇒ 清空预算 ⇒ 停俸 ⇒ 官吏逃亡（报告 §4 有完整证据链）。

---

## 2. 实测结论（run6 / run7，证据在仓库外）

| 项 | run6 | run7 |
|---|---|---|
| 端口（GUI/MCP/审批） | 5891 / 5895 / 5893 | 5901 / 5905 / 5903 |
| 省税率 / 上缴 | 100‰ / 0 | 100‰ / 500‰（0→360）→ 0（360→480 抗税） |
| 上缴合计 | — | 13 笔 **966,011,756 粮 / 33,597 银**，逐周期 floor 只差 4 毫 |
| 中央国库 tick360 | 被省抽税 173 天 + day50 市场清零 | **966,810,484 粮**（省库 966,810,963，对半） |
| 抗税 120 天 | — | 省 **+868,291,567** / 中央 **−56,673**（只花俸禄） |
| 告警 / 逃亡 | — | **0 告警、480 天 0 rises / 0 flights** |
| tick360 世界 | pop 5078 / grain 3,250,188,618 / debt 126,971,967 | pop 5078 / grain 3,255,230,807 / debt 104,827,149 |

- **中央独立财政收入 = 0**（capital-province 0‰、无铸币、无自辖税收）⇒ 上缴断供在 120 天窗口内不会危机（966M 存量够 ≈5800 天俸禄）；
  要复现"上缴断→中央危机"必须等 F3 铸币/发债落地或拉长窗口。
- 关账节奏 = 经济产业周期关账（`GovRemittanceBridge.java:138` 锚定 `lastCycleClosed()`），有 day240→243 的 +3 漂移（下一周期 −3 回正），
  累加器清零已按日税收对账（211–240 → 35.4M；241–243 → 45.3M，均 = 实收 × 500‰）。
- 证据目录（仓库外，勿删）：
  `/home/cna/simos-runs/2026-10-23-sw19-run4|run5|run6|run7/`（`service.log`、`seg.log`、`dumps/`、`dumps-refusal/`、`snap-tick*.json`、`setup_tick0.py`、`run_segments.py`、`run_refusal.py`）、
  `/home/cna/simos-runs/2026-10-23-sw19-z7e3-probe/`（`probe.txt` + `service.log`）。

---

## 3. 如何恢复现场（复现命令）

```bash
cd /home/cna/SimulatorMosire
# 全仓门禁（一次只能一个 Maven；长命令后台跑 + 轮询）
tools/mvn-lock.sh clean verify

# run7 服务（新库/指定端口）
R7=/home/cna/simos-runs/2026-10-23-sw19-run7
SIMOS_SMALL_WORLD_JAR=$PWD/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
SIMOS_SMALL_WORLD_STORE=$R7/store SIMOS_SMALL_WORLD_GUI_PORT=5901 \
SIMOS_SMALL_WORLD_MCP_PORT=5905 SIMOS_SMALL_WORLD_APPROVAL_PORT=5903 \
setsid nohup ./run-small-world.sh < /dev/null > $R7/service.log 2>&1 &

# MCP 一次性调用 helper（端口 5925/5905/5895 各有副本）
python3 /tmp/mcpcall5905.py simos.gov.info '{"govUnitId":"gov-province"}'
```

- run 脚本注意：`setBudgetPolicy` 现在缺省 PATCH；如需旧整表替换语义用 `mode:"REPLACE"`（工具顶层 `mode` 参数会覆盖 payloadJson）。
- 长 Maven 用 `setsid nohup … &` + `sleep` 轮询；本机一次只允许一个 Maven（AGENTS §一.1）。
- 真 LLM 场景测试的开关与轮数见 `HANDOFF-2026-10-22` §1.2。

---

## 4. 剩余开口（保留，本会话不做）

### 4.1 本批明确留出的（有证据、有指向）

1. **F3 中央铸币 / 发债**（= 盘点 §1.1 "铸币生产方式 M1"）：run7 证明中央收入 100% 依赖上缴；铸币仍只有
   `seignioragePerCycle` 直接记国库，无 `Industry` 货币产出 / `MintRule` / `mintScale`。
2. **F4 全覆盖校准**：中央 1000‰ / 省 250‰ 的差异来自创世计划规模（省 2× 标准定额、2 名官吏），未做覆盖口径调参。
3. **MarketReadout `hh-unit:*` 读口边界**：国库/单位户被市场排除后，相关读口的"可见性/口径"未审计。
4. **逃亡目的地主指标回退**：真实小世界里 `ProductionProcessBook + ExpectedProfitBook` 无机会时退化为人均财富；
   该退化路径只在探针层面验证过。
5. **旧 store 无真实回读**：Z7a/Z7b/Z7c 只对**新创世**验证，旧库迁移/回读未测。

### 4.2 盘点清单里**因本批变化**的三条（更新口径）

| 盘点条目 | 现状态 |
|---|---|
| §1.1「军俸与 GOV 行政俸禄共享国库的预算/缺口优先级」 | ✅ **已关闭**：Z3c 起 5 类预算共用一个国库池、按序执行，run6/run7 有逐腿读数 |
| §1.1「军俸 FlowRow/ledger 维度与读口」 | ❌ 仍开：军俸仍走瞬态规则 + 账户扣款；本批只补了 GOV 侧 `budgetSettlement`/`GOV_OFFICE_UPKEEP_EVALUATED` 读口 |
| §1.1「决策人受限军俸工具 + 审批链」 | ❌ 仍开：只有 GM 的 `simos.gm.armyPayPolicy` |
| §1.1「铸币 M1」 | ❌ 仍开（= 上文 F3） |
| §1.5「enforcer 回填」「工具面下沉到模块」 | ❌ 仍开，未动 |

其余盘点 §1/§2/§5/§6 原样有效，**不要当成本批遗留**（本批只覆盖经济生产效率、gov 行政服务、税制/财政闭环三条线）。

### 4.3 待用户裁定（沿用盘点 §5，本批未新增）

DM 自动派发、跨市场区 D-026、城防数据形状、单格命名、批工具审计、DeleteNation 级联、人口城籍、
铸币 M1 形状、文化/宗教、默认世界配置、`unit.SetComposition` 权限、SpawnArmy 造人、Region 互斥去重。

---

## 5. 本批文档索引

| 类型 | 路径 |
|---|---|
| spec（契约） | `specs/2026-10-23-production-efficiency-framework.md`、`specs/2026-10-23-gov-service-mode-design.md`、`specs/2026-10-23-fiscal-loop-design.md`（§10 = Z7e-3 双模） |
| 排查报告 | `reports/2026-10-23-editability-and-efficiency-investigation.md`、`reports/2026-10-23-gov-tax-treasury-investigation.md`、`reports/2026-10-23-gov-service-mode-feasibility.md`、`reports/2026-10-23-fiscal-loop-investigation.md` |
| 实测报告 | `reports/2026-10-23-sw19-run5-efficiency-formula-vs-run4.md`、`reports/2026-10-23-gov-batch-run6-vs-run5.md`、`reports/2026-10-23-fiscal-loop-run7-vs-run6.md` |
| 实现台账 | `.superpowers/sdd/2026-10-23-production-efficiency-framework/*`、`.superpowers/sdd/2026-10-23-gov-service-mode-design/*`、`.superpowers/sdd/2026-10-23-fiscal-loop-design/*`（含 z7e3） |

---

## 6. 纪律提醒（下次会话）

- 先读本文件 + 盘点文档 §0/§5，再决定动工；**调查完先给用户方案、确认后再改代码**。
- 用户原话逐字进 spec 的"用户原话"节（AGENTS §一.8.1）；提交信息诚实标注未验证边界；不 `git add -A`。
- 每次全仓门禁用 `tools/mvn-lock.sh clean verify`；改动后先 `spotless:apply`（否则 verify 的 Spotless 阶段会红）。
- 证据目录 `/home/cna/simos-runs/**` 是仓库外现场，删了就没了；新 run 建议同构命名。
