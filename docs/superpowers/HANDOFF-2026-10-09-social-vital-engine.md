# HANDOFF 2026-10-09：Social 每 tick 生死引擎（Batch A/B/C + P0 迁移旁路关闭）

> 新会话先读本文件 + `docs/superpowers/plans/2026-10-09-social-vital-rates-per-tick-plan.md`
> （施工依据与 §7 Unit 对接规划）。本文件是本批完成的存档与下一批入口。

## 0. 一句话

- 仓库：`/home/cna/SimulatorMosire`，分支 `main`；
- 本批完成：Social 每 tick 生死引擎（ppm/tick + 全局默认率表 + 家户覆盖 + 余数累加器含稳定哈希初相位）、
  App 日初接线、经济逐户 population delta 同步、`physiologicalStress` / `PopulationDynamics` 生产路径删除；
- 实测：fresh small-world `0→360`，出生 258 / 死亡 157（vital engine 基线）；
- **2026-10-10 更新**：P0 迁移写人旁路已关闭（`ModeMigrationSettlement` 投影账 + outbox，
  `MigrationSocialBridge` 同 revision 落 Social 工单）；fresh 0→360 逐户 138/138 Social == Economy、
  世界总人口 4100 = 4100；下一步 = Unit 四件套。设计/实施见
  `docs/superpowers/plans/2026-10-10-p0-mode-migration-social-outbox.md`。
- 测试迁移仍后置：`test-compile` 仍红，生产编译/打包用 `-DskipTests` / `-Dmaven.test.skip=true`。

## 1. 本批落地内容

### 1.1 Social 侧

- `HouseholdVitalRate` 改 ppm/tick 字段：`birthRatePerMillionPerTick` / `deathRatePerMillionPerTick`；
- 新组件 `SocialVitalRates`（全局默认）+ `Household.vitalRates`（家户覆盖），查找顺序覆盖 > 全局；
  缺键具名拒，不静默给 0；
- 初始默认：死亡 0-14=67 / 15-59=33 / 60+=667 ppm/tick（男女同值）；出生 15-59 FEMALE=667，其余 0；
  育龄精确窗口 15 ≤ ageYears < 45；
- `SocialVitalRemainders` / `SocialVitalRemainder` / `VitalKind` 新组件；
- `HouseholdBook.settleVitalEventsResult(...)` / `settleOneTick(...)`：逐家户、逐批次算 DEATH/BIRTH，
  余数跨 tick 累加；`VitalSettlementResult` 返回新状态 + 逐户 population delta + births/deaths + events；
- ★ 首见余数键给 **FNV-1a 64 稳定哈希初相位**（不是 0）：修正零初值导致的“首年死亡率只有配置值 13%”问题；
- 删除 `PopulationDynamics`（含基础死亡率/压力传导/月度引擎）、`PopulationGroup.physiologicalStress`；
- 保留 `HouseholdBook.settleVitalEvents(...)` 旧签名只返回 `SocialData`。

### 1.2 App / Economy 侧

- `PopulationEconomyTimeParticipant` 每日循环最前：
  1. `HouseholdBook.settleOneTick(currentSocial, day, CalendarClock.julianDefault())`；
  2. `stepper.updateComposition(...)`；
  3. `stepper.recomputeLaborBudgets(...)`；
  4. `stepper.updateNaturalNeeds(...)`；
  5. `stepper.applyHouseholdPopulationDeltas(vital.populationDeltas())`；
  6. `stepper.step(day)`。
- 删除月结 `PopulationDynamics.monthly(...)`、日末 `applyDailyStress(...)` 与压力辅助；
- `EconomyDayStepper.applyHouseholdPopulationDeltas(Map<HouseholdId, Long>)`
  → `EconomySettlement.applyHouseholdPopulationDeltasInto(...)`：缺行/负结果/0 delta 具名拒，
  `laborMilli` 不二次缩放（调用方已按新 Social 重算）；
- App/GUI/Crisis 的压力读口：保留键给 `null` + 具名 unavailable，不填 0 冒充；
- 率工具/Catalog/Payload 字段改 ppm 名；旧 `stress` 载荷出现即具名拒。

## 2. 实测验证（Batch C）

```bash
cd /home/cna/SimulatorMosire
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile        # rc=0
tools/mvn-lock.sh -q -pl simos-app -am -Dmaven.test.skip=true package   # rc=0；前端门禁 412/412

JAVA_TOOL_OPTIONS='-Dsimos.social.logLevel=DEBUG -Dsimos.economy.logLevel=DEBUG' \
SIMOS_SMALL_WORLD_STORE=/tmp/batchc-phase-store \
SIMOS_SMALL_WORLD_GUI_PORT=5931 SIMOS_SMALL_WORLD_MCP_PORT=5935 \
SIMOS_SMALL_WORLD_APPROVAL_PORT=5933 ./run-small-world.sh &

curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"branch":"main","expectedRevision":1,"from":0,"to":360}' \
  http://127.0.0.1:5931/api/advance
```

读数：

```text
POPULATION_SETTLE days = 360
births = 258   deaths = 157          # FNV 相位模拟预期 259 / 156
final social population = 4101
final economy population = 4101
CLASSROW_POPULATION_PROJECTION_UNRESOLVED = 0
runtime ERROR = 0
MIGRATION_APPLIED day=120/240/360 moves=7 rowsBefore=138 rowsAfter=138
```

零初值对照（修正前留档）：

```text
360 tick deaths = 20；率表连续期望 = 156；Python 逐步模拟 = 20
⇒ 136 人冻在 450 个批次的余数里，限窗内死亡率只有配置值的 13%
```

## 3. P0：关掉经济写人旁路（✅ 2026-10-10 已关闭，先于 Unit）

问题（已修复留痕）：`ModeMigrationSettlement.apply` 直接改 `HouseholdEconomy.population`（并迁资产/钱/债），
Social 不知道；360 天后逐户漂移，例如：

```text
hh-0_1-rural-middle_peasant   Social 72  Economy 69
hh-0_1-rural-rich_peasant     Social 30  Economy 27
hh-0_2-urban-middle_peasant   Social 110 Economy 131
```

实施（设计/实测见 `docs/superpowers/plans/2026-10-10-p0-mode-migration-social-outbox.md`）：

1. `ModeMigrationSettlement` 用 `plannedPopulation/plannedLabor` 投影账，**不再写已有行的 population/laborMilli**；
   每笔 move 记 `EconomySession` 瞬态 outbox `EconomyPopulationTransfer`；
2. App 在 `stepper.step(day)` 后 drain outbox，`MigrationSocialBridge` 翻成 Social 工单
   （newTarget 先 `CREATE_HOUSEHOLD`，再按 `ProportionalSplit` 确定性选批次 `TRANSFER_MEMBERS` 恰好 count 人）；
3. 同一条 revision 内：Social 工单落人 → 经济行逐户净 delta 回写 → 刷新 composition/labor/needs；
   Social 工单失败 ⇒ 整个 advance 具名拒，不落 revision；
4. 实测：fresh 0→360 逐户 138/138 Social == Economy、世界总人口 4100 = 4100，0 ERROR / 0 投影 unresolved。

## 4. 下一批：Unit 对接四件套（计划 §7.3）

顺序：P1 征兵/退伍工单化 → P2 战斗伤亡回写 Social → P3 UNIT 家户 smoke →
P4 经济通用“周期家户库存扣增” + Unit 决策人内部分摊规则。

要点：

- Unit 只拥有编制/装备/位置/命令；人的增减一律走 Social 工单；
- 军俸不是 Army 专属常量：Economy 提供通用周期库存/货币扣增机制，Unit 决策人定义“谁交、交多少、发给谁”；
- `Unit.households` + `HouseholdPositionResolver` 是 UNIT 家户位置/经济视图的唯一组合路径；
- 不再用 `social.SetHouseholdLocation(HEX)` 钉 UNIT 家户位置。

## 5. 未做清单（按优先级）

1. ~~P0 `ModeMigrationSettlement` → Social 工单 outbox~~ ✅ 2026-10-10 已关闭；
2. Unit 四件套 + Unit 决策人模型（当前最高优先级）；
3. `FlowRow.births/deaths` 仍不承载每 tick 生死，`CrisisMonitor.MORTALITY` 看不到 ppm 死亡；
   （本批已知边界，读口/结算批补）
4. GM/决策人工具：计划 §6 清单已完整落盘（率表 delta、直接加减人口、受限决策人版、Catalog/前端/审计/测试）；
5. 测试迁移（`test-compile` 红：`PopulationDynamicsTest` 等旧 API 引用）；
6. `clean verify` / 全模块 spotless / 前端；
7. 世界历法：App 目前用 `CalendarClock.julianDefault()`，世界若换历法要统一接线（当前世界就是儒略历）。

## 6. 快速恢复命令

```bash
cd /home/cna/SimulatorMosire
git status --short --branch
git log --oneline -6

tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile
tools/mvn-lock.sh -q -pl simos-app -am -Dmaven.test.skip=true package

# smoke 完事必须停：不要留服务占用端口 / jar
pkill -f 'simos-shaded' || true
```
