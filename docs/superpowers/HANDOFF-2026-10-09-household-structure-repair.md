# HANDOFF 2026-10-09：家户结构修复（Social 人口/劳动/需求权威 + Economy 消费投影）

> 本文件保存本批跨会话进度。新会话先读本文件与
> `docs/superpowers/plans/2026-10-09-household-structure-repair-plan.md`，
> 再按 §6 继续。
> 当前状态：**Social 权威链、Economy 消费投影、GM 命令/工具已落地并推送；出生为 0 的独立 bug 仍未修。**

## 0. 一句话

- 仓库：`/home/cna/SimulatorMosire`，分支 `main`；
- 代码提交：`ea7945eb`（Social 权威 + Economy 消费注入投影）、`fca92e30`（需求/劳动系数 GM 命令 + 窄工具）；
- 文档提交：本文件所在提交；
- 已落地：`SocialProvisioning`（全局默认 + 家户覆盖）、`householdLaborMilli/householdNaturalNeeds`、
  `HouseholdEconomyProjection` 1:1、app 每日注入、Economy 消费改造、`simos.social.demand` / `simos.social.labor`；
- 未修：出生为 0 的完整 bug 链（首日播种/口粮、逐批次整除、两套生死引擎）、GM 读口、
  `EconomySeeder` 创世口径、测试迁移、`clean verify`。

## 1. 本批改了什么

### 1.1 Social 权威

- `SocialData` 第 6 组件 `SocialProvisioning`：
  - 全局默认需求/劳动表；
  - 逐家户覆盖表；
  - 键：需求 `(AgeBracket, Sex, CommodityId)`，劳动 `(AgeBracket, Sex)`；
  - 初始默认值按计划 §3.5（粮、布、劳动的六档表）。
- `SocialData` 纯函数：
  - `householdLaborMilli(HouseholdId, day, CalendarClock)`：逐成员求和；
  - `householdNaturalNeeds(HouseholdId, day, CalendarClock)`：逐成员求和后，粮按 120 天家户层一次取整、布按 `YearFraction`。
- `SocialChangeSet` 第 6 组件；旧档缺组件 = 不可读（用户裁定“一切从新”，不做迁移/双读）。

### 1.2 经济投影与消费

- `HouseholdEconomyProjection`：改为 `HouseholdId` 1:1；
  - 删除 `(格,居住)` 分组 + 加权平均；
  - small-world 创世态 `unresolved` 135 → 0。
- `PopulationEconomyTimeParticipant`：每日从 `currentSocial` 展开 `population / labor / naturalNeeds`，
  在 `stepper.step(day)` 前注入 `EconomyDayStepper.updateNaturalNeeds(...)`。
- `EconomySettlement.consumeOneHousehold`：读注入 `naturalNeeds`，删除 `withDailyNeed(population)`；
  粮需求累加进 `cycleNaturalNeedMilli`。
- 前瞻需求消费点（市场保留、债务容量、自给守卫、进入预留、覆盖率、危机读口）改逐户
  `HouseholdEconomy.expectedNeedMilli(...)`；饥荒分母改 `cycleNaturalNeedMilli`。

### 1.3 GM 命令与工具

- 命令：
  - `social.SetDemandCoefficient`
  - `social.SetLaborCoefficient`
  - 两命令均 `GmOnlyCommand`；
- GM 窄工具：
  - `simos.social.demand`
  - `simos.social.labor`
- 语义：`householdId` 缺席=全局默认；给了=家户覆盖；系数缺席=清家户覆盖并回落全局；全局不允许删键；
  preview 纯 copy-with、apply 单命令 = 一条 revision。
- 日志：`SOCIAL_PROVISIONING_*`、`SOCIAL_HOUSEHOLD_*_EXPANDED`、`SOCIAL_DEMAND_COEFFICIENT_*`、
  `SOCIAL_LABOR_COEFFICIENT_*`、`CLASSROW_POPULATION_PROJECTED/SKIPPED`。

## 2. 实测验证（本批）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` → **rc=0**；
- `tools/mvn-lock.sh -q -pl simos-app -am -Dmaven.test.skip=true package` → **rc=0**，前端门禁 412/412；
- fresh `small-world` 独立 store/端口：
  - `/api/advance` `0→365`，最终 `main@...`、tick=365；
  - **无 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED`**；
  - 最终 `finalPopulation=3970`（初始 4000；累计死亡 30、出生 0）；
  - 无新增运行时 ERROR；
- `/api/command` 实测 GM 命令：
  - 全局需求设置、家户覆盖设置/清除、劳动覆盖设置/清除全部 `committed`；
  - 日志事件成对出现。
- 未跑：`test` / `test-compile` / `clean verify`；测试未迁移。

## 3. 当前已知缺口（按优先级）

1. **出生为 0**：三类原因都未修：
   - 首日 `PLANTING_DRAWS_BEFORE_CONSUMPTION` 把口粮当种子、初始库存只够 65 天口粮；
   - `PopulationDynamics.birthsOf` 逐批次整数截断（1098 育龄女性实际只生 2/月，先汇总应为 21）；
   - `PopulationDynamics` 与 `HouseholdBook.settleVitalEvents` 两套生死引擎未统一。
2. **GM 读口**：查看全局默认/家户覆盖/有效 needs·labor 的读工具未做。
3. **EconomySeeder 创世口径**：初始库存/口粮种子仍按 `population × 统一系数`；需要随 Social 分档口径收口。
4. **`ApiViews.unmetPersonDays`**：仍是全局 83 毫粮/人日的量级读数，已具名标注。
5. **`expectedNeedMilli` 是线性外推**：当前注入日值 × 天数；精确本周期窗口用 `cycleNaturalNeedMilli`。
6. **GOV/Army**：small-world 仍无 Unit/Army；GOV 走 `simos.gov.createOffice`，Army 走 `raiseUnit`/手工批，
   `spawnArmy` apply 当前被 manpower 退役拒绝。
7. **军俸/通用周期库存增减**：未做；等 GOV/Army smoke 后按“经济通用机制 + Unit 决策人内部分摊”落。
8. **Economy → Social 工单 P9**：未接线。
9. **测试迁移 / `clean verify` / 前端**：后置。

## 4. 下一批建议顺序

1. 修首日播种/口粮（决定 reserve / 初始库存 / 播种亩数）；
2. 修出生逐批次整除 + 统一生死引擎；
3. GM 读口 + `EconomySeeder` 创世口径收口；
4. 小世界 GOV/Army 补建；
5. 通用周期库存增减 + Unit 决策人军俸规则；
6. Economy → Social 工单 P9；
7. 测试迁移 + `clean verify`。

## 5. 快速恢复命令

```bash
cd /home/cna/SimulatorMosire
git status --short --branch
git log --oneline -6

# 生产编译门禁（本批应为绿）
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile

# 诊断构建（绕过仍未迁移的 test-compile）
tools/mvn-lock.sh -q -pl simos-app -am -Dmaven.test.skip=true package

# fresh smoke（独立 store/端口；完事记得停）
./run-small-world.sh
```

## 6. 文档地图

- `docs/superpowers/plans/2026-10-09-household-structure-repair-plan.md`：本批施工计划 + §10 进度；
- `docs/superpowers/reports/2026-10-09-p2-population-economy-bug-investigation.md`：调查 + §11 修复后复测；
- `AGENTS.md` §〇：social/economy 模块边界已补“Social 权威 / Economy 消费投影”说明；
- `docs/superpowers/HANDOFF-2026-10-09-p2-social-workorder-rename.md`：上一批存档。
