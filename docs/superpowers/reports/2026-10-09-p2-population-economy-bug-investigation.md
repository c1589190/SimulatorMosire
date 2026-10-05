# 2026-10-09 调查：small-world 真实循环暴露的人口/经济问题

> 状态：**只读调查 + /tmp 探针；未改任何生产代码。**
> 基线：`HEAD a1e3ac2f`（`origin/main` 同点）；工作树干净。
> 本文记录 2026-10-09 一次“跳过测试编译、跑真实 small-world 365 天、查 Log”的发现，供后续修复批次使用；不篡改任何历史文档。

## 0. 一句话结论

真实循环本身能跑：`-Dmaven.test.skip=true package` 可绕过当前红掉的 test-compile，small-world 365 天 **0 ERROR / 0 Exception**。
但 smoke 暴露出一条值得认真修的 bug 链：

```text
家户行人口对账（CLASSROW_POPULATION_PROJECTION）在正常世界里整批失效
  → 经济结算读到过期/不完整的 HouseholdEconomy.population
  → 需求、饥荒、市场、迁移、债务按旧人口算
出生为 0 另有直接原因：生理压力 1066/1526 > 500，生育抑制归零；
底层还有逐批次整数截断，以及“首日播种先扣、口粮被吃光”的强嫌疑。
```

---

## 1. 现场与验证（本轮实测）

### 1.1 生产编译与打包

| 命令 | 结果 |
|---|---|
| `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` | **rc=0，绿** |
| `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests test-compile` | **rc=1，红**（首断 `simos-economy-api`） |
| `tools/mvn-lock.sh -q -pl simos-app -am -Dmaven.test.skip=true package` | **rc=0，成功**；前端门禁 412/412 通过 |

重要口径：

- `-DskipTests` **不够**：它仍会 `test-compile`，当前必红；
- 要用 `-Dmaven.test.skip=true`，它同时跳过测试编译与执行；
- 这是诊断路径，不替代最终 `clean verify`。

### 1.2 small-world 365 天 smoke

- 独立 store：`/tmp/simos-smoke-store`；独立端口：GUI 5911 / MCP 5915 / 审批 5913；
- `0 → 30`、`30 → 365` 两次 `/api/advance`，最终 `main@3`、tick=365；
- Log：`/tmp/simos-smoke.log`，约 35,025 行；
- **0 ERROR、0 Exception**；
- 事件面：365 个 `DAY_START/STEPPER_STEP/DAY_END`、123 轮 `MARKET`、60 次 `DEFICIT_LENDING`、28 次 `DEBT_FORGIVE`、11 次生产组织、7 次人口回写、4 次政府铸币/发债；
- **没有** `TAX_*` / `GOV_UPKEEP` / `GOV_DAILY` 事件——small-world 当前不带 Unit/Army，`govActive=false`，税与行政俸禄路径一次都没触发。

---

## 2. 发现 A：`CLASSROW_POPULATION_PROJECTION_UNRESOLVED`（人口对账整批失效）

### 2.1 它是什么

`simos-app` 的 `HouseholdEconomyProjection` 是 **Social → Economy 的单向人口对账/投影器**：

```text
Social 家户成员批次之和（人口真值）
        ↓
Economy 侧 HouseholdEconomy.population（经济结算用的行人口视图）
```

它不移动人、不改 Social、不新建/合并家户；它只调整经济行的 `population`，并把 `laborMilli` 同比例缩放。
一旦有任何无法无损对账的情况，它 **fail-closed：返回原经济数据，不做任何修改**，并累积 `unresolved`。

### 2.2 实测结果（small-world 创世态）

用探针对 `SmallWorld.state("small-world")` 的 economy/social 直接调用 `HouseholdEconomyProjection.project`：

```text
projected=false unresolved=135
  68 条：同一个 (格, 居住类型) 下有多个 Social 家户
  53 条：家户成员批次为空 / 推不出居住类型
  13 条：经济侧有该 (格,居住) 的行，但没有对应的 Social 家户
   1 条：两侧人口总数不一致
```

构成可解释：

- 15 个格各有 rural 池，每池 5 户（4 常规阶层 + 1 流民户）⇒ 除首户外 4 条重复，共 15×4=60；
- 2 个城市格的 urban 池各有 5 户（4 常规 + 1 流民户）⇒ 除首户外 4 条重复，共 2×4=8；
- 以上“同 (格,居住) 多户”合计 68；
- 13 个非城市格的 urban 池各有 4 个零成员户（合法空壳），推不出居住类型 ⇒ 13×4=52；加政府家户无成员 ⇒ 53；
- 对应 13 个非城市格的 urban 经济行找不到 Social 户 ⇒ 13；
- 总数不一致 ⇒ 1。

### 2.3 根因

- P2-A 之后 Social 家户粒度 = `(格, 居住类型, 阶层)` 一户；
- 但 `HouseholdEconomyProjection` 的分组键仍是 `(格, 居住类型)`，默认“一个组只有一户 Social”；
- 它拿不到多户时该组的 Social 总人口，也不会把“流民户/空壳户/政府户”分开处理；
- `UNIT` 家户虽已有排除逻辑，但不能解决 HEX 侧的多户问题。

### 2.4 影响（重点）

由于任何一条 `unresolved` 都会让 `projected=false`：

```text
Social → Economy 的人口对账在当前任何正常 seeded 世界里从未真正生效。
```

经济侧 `HouseholdEconomy.population` 被大量经济算式读取，漂移时至少影响：

- 每日口粮/衣着需求、消费与 `unmetNeed`；
- 饥荒死亡比例与劳动缩放；
- 生产组织、劳动分配权重、自给保留；
- 市场参与者、自用保留、放贷能力、需求；
- 迁移/阶级转换的数量与“是否迁空”判断；
- 债务容量、政府发债/放贷对象；
- 经济读口/GUI/MCP 的人口与汇总读数。

**特别说明（避免误解）**：

- 现有需求不是“全国家户平均”，是**逐户** `population × 人均定额`；
- 但 `population` 可能是旧的，所以这个逐户求和也是错的；
- `laborMilli` 已每 tick 从 Social 成员结构现算（见发现 C），基础劳动预算方向正确；
- `HouseholdEconomyProjection` 里那种“按权重分摊”只针对旧身份模型（一户对四行）的补丁；P2-A 后应按 `HouseholdId` 1:1 对账，**不应对家户平均**。

### 2.5 修复方向

1. 经济行与 Social 户按 `HouseholdId` 1:1 投影；同一 `(格,居住,阶层)` 内确有多户才做组内加权，绝不跨阶层平均；
2. 对零成员家户、UNIT 家户、政府家户、流民户分别具名处理，不再让它们把整批投影判死；
3. 投影恢复后，经济侧 `population` 才是 Social 的实时投影，而不是可长期不更新的缓存。

---

## 3. 发现 B：出生为 0 是复合 bug

### 3.1 直接原因：生理压力把生育抑制到 0

重开 tick 365 状态读口，首都格 `(0,0)` 实测：

```text
population=860
0-14=303，15-59=467，60+=90，FEMALE=428
physiologicalStress: average=1066，max=1526
```

`PopulationDynamics.birthsOf` 的抑制公式：

```text
抑制 = max(0, 1000 − 压力 × FERTILITY_SUPPRESSION_PER_STRESS) / 1000
FERTILITY_SUPPRESSION_PER_STRESS = 2
压力 ≥ 500 ⇒ 抑制 = 0 ⇒ 出生 = 0
```

压力 1066 时出生必为 0。

### 3.2 底层 bug 1：逐批次整数除法截断

用创世态在压力 0 下做月结算探针：

```text
fertileWomen = 1098
实际 per-group 出生 = 2
若先汇总 fertileWomen 再乘率 = 21
groupsWithBirth = 2
```

原因：`birthsOf` 对每个批次做：

```text
count × 20 / 1000
```

大量 10~40 人的批次被整除截断成 0。压力探针：

```text
stress=0    births=2
stress=300  births=0
stress=500  births=0
stress=1000 births=0, deaths=4
```

即：**即使没有饥荒，4000 人小世界每月也只有约 2 个新生儿**，远低于应得的约 21 个；压力到 300 时已经归零。这是确定的 bug。

### 3.3 底层 bug 2（强嫌疑）：首日播种吃掉口粮，触发长期饥荒

创世 checkpoint 实测：

```text
初始全仓粮食库存 = 21,666,661
全人口每日口粮需求 = 333,306
库存 ≈ 65 天口粮
```

但 day 1：

```text
[day=1] INPUTS+CONSUMPTION consumedQuantities=21,710,821
        deficitHouseholds=60 deficitGrainMilli=237,480
        DEFICIT_LENDING unmetAfter=216,741（≈每日需求的 65%）
```

首日“投入 + 消费”的消耗量和**全仓初始粮库存同量级**，而真正的口粮只有：

```text
333,306 − 237,480 = 95,826
```

这与 `PLANTING_DRAWS_BEFORE_CONSUMPTION = true` 高度吻合：播种日先按“8 粮/亩”扣种子，且没有保留口粮约束，于是首日把 household 粮食几乎全部扣成种子，随后消费没粮、粮食满足率约 29%，压力从 day 1 开始累积。
**尚未用 TRACE 逐笔坐实具体扣款路径，是下一步第一项验证。**

### 3.4 底层 bug 3：两套生死引擎没接上

- 生产路径调用的是 `PopulationDynamics.monthly`，用**硬编码** `FERTILITY_PER_MILLE_PER_MONTH=20`，完全忽略家户 `vitalRates()`；
- `HouseholdBook.settleVitalEvents` 才是按 `(年龄档, 性别)` 查家户出生/死亡率表的实现，但 **main 里没有生产调用者**；
- seed 出来的家户都是 `HouseholdVitalRates(List.of())` 空表；
- 结论：`social.SetHouseholdVitalRates` / Social 工单里的 `SET_VITAL_RATES` 目前只改读口表象，**不会影响真实出生/死亡**。

### 3.5 正确修复顺序（建议）

1. 先修首日播种/口粮：决定增加初始口粮、降低播种亩数，还是 `drawCycleInputs` 保留 subsistence reserve；修完重跑一年 smoke 看压力/粮食曲线；
2. 再修逐批次整除：跨批次累积分子后一次取整，避免小批次吞掉全部出生；
3. 最后统一生死引擎：让 `vitalRates` 真正进生产路径，或明确废除其中一套。

---

## 4. 发现 C：需求与劳动口径

### 4.1 需求当前口径

`EconomySettlement.withDailyNeed`：

```java
EconomyVocabulary.dailyNeedsMilli(householdEconomy.population(), day, ...)
```

- **逐户**算，不是全国加总、也不是平均；
- 但只按 `population × 人均定额`：
  - 粮：每人每 120 天 10 粮；
  - 布：每人每年定额；
- **不看年龄、性别、实际消费结构**。

### 4.2 劳动当前口径（方向是对的）

`PopulationEconomyTimeParticipant.laborBudgetsOf(social, day)`：

```text
逐户遍历 Household.members
  → 每个成员批次按年龄档、性别查 HouseholdLaborTimeTable
  → 相加
```

即：**劳动基础预算已经是按家户成员结构求和**。问题是需求没有照这个方式做；另外经济结算里还有一部分算式拿 `population` 当权重/上限/保留量，会受过期人口影响。

### 4.3 正确目标形状（用户口径）

```text
每个 HouseholdId：
  members = Social.households[h].members
  demand_grain(h) = Σ_member count × 该成员当日人均口粮
  demand_cloth(h) = Σ_member count × 该成员当日人均衣需
  labor(h)        = Σ_member count × 该成员小时预算
  population(h)   = Σ_member count   ← 只是投影结果，不是独立权威
```

- 同一 `(格,居住,阶层)` 多户时，**每户各自求和**，不平均；
- Unit 多户时同样：Unit 只是关系列表 + 决策层规则，经济账始终按家户分别结算；
- 待定：粮/布需求系数继续全年龄同额，还是按年龄/性别分档。前者只是把求和来源换成 Social 成员；后者才是完整“实际需求”。

---

## 5. 发现 D：Unit 多户 / `MARKET_SUBJECT_COLLECTIVE`

用户口径：**一个 Unit 自然可以挂多个家户；文官集团和武将集团利益不一致，应由 Unit 的决策人自己维护内部规则。**

当前代码事实：

- `Unit.households` 是唯一“谁在这个 Unit 里”的列表；
- `GovernmentFormation.governmentPostsOfHousehold` / `ArmyFormation.militaryDutiesOfHousehold` 只是逐家户角色配置，没有金额、周期预算、分摊规则；
- `MarketSettlement` 遇到“解析不到单一户、但名下有多户”的 Unit 时，打 `MARKET_SUBJECT_COLLECTIVE`，**不把该 Unit 当市场参与者**，让其名下各户自行参与市场；
- 365 天 smoke 中该警告 **1845 次 = 123 轮市场 × 15 个 collective weave unit**，是每轮命中；
- 全仓目前没有 “Unit → 决策人 / 代表账户 / 内部分摊规则表”；
- `sd` 的 `Affiliation.Army` 用 `ArmyId`，`Affiliation.Gov` 用 `Gov(UnitId)`，但 `Unit` 本身不直接持有决策人。

结论：这不是“多户非法”，而是 **Unit 决策人维护内部利益的机制还没建**。正确方向是：

```text
Unit 决策人定义内部分摊规则
  → 经济侧按每个 HouseholdId 分别执行扣除/发放
  → 绝不把 Unit 压成一户，也不做跨户平均
```

---

## 6. 发现 E：small-world 的 GOV / Army 补建路径

`SmallWorld` 当前明确不带 unit/army，只有一个 world-silver 政府家户与国库，因此 smoke 未触发税/俸禄。

- **GOV：现成路径。** `simos.gov.createOffice` 是完整 GM 组合工具，一批包含：
  `unit.CreateUnit` → `social.CreateHousehold(hh-gov-<unitId>, UNIT 位置)` → `actor.EnsureHouseholdAccount` → `unit.SetGovFormation` → `economy.RegisterGovernment` → 管辖 → `sd.CreateDecisionMaker`。
- **Army：`simos.unit.spawnArmy` 目前不能 apply。** 其类注明确写着 `Unit.manpower` 已退役、尚未接线到家户来源，apply 会被 `unit.CreateUnit` 具名拒。应改用：
  - `simos.unit.raiseUnit`（从 Social 家户抽人）；或
  - 手工组合：Social 家户/工单 → `unit.CreateUnit` → `unit.SetUnitHouseholds` → `unit.SetArmyFormation` → `sd.CreateArmy`。
- **军俸/维护费还没有数据落脚点**：`ArmyFormation`/`Unit` 都没有薪额、预算、分摊字段；这正好是“通用周期库存增减 + Unit 决策人政策”的落地位置。

---

## 7. 本轮新增的用户裁定 / 口径

1. 军俸可做成“单位周期性维护成本”；
2. 经济模块应提供**通用、可被外部自定义**的周期性家户库存增减机制（不只扣，也可增）；
3. Social 工单应尽快在经济模块上真正用起来；
4. Unit 可以自然挂多个家户；文官/武将利益不通，由 Unit 决策人维护；
5. 需求应等于“家户内各成员实际需求相加”，**不应该平均**；投影里的平均是旧模型遗留，应改为按 `HouseholdId` 1:1 对账；
6. 小世界没有 GOV/Army 就补建，用真实 GOV/Army 定义扣什么，模拟真实循环。

---

## 8. 建议的修复顺序

1. **修 `HouseholdEconomyProjection`**：按 `HouseholdId` 1:1；空壳/流民/UNIT/政府户具名处理；`unresolved=135` 应力争归零。
2. **查并修首日播种/口粮**：确认 `drawCycleInputs` 是否在首日把全部口粮当种子；决定 subsistence reserve / 初始库存 / 播种亩数；重跑 365 天。
3. **需求改成员求和**：至少先保证从 Social 实时的家户人口求和；再决定是否按年龄/性别分档需求系数。
4. **修出生量化 + 统一生死引擎**：跨批次累积取整；让 `vitalRates` 真正生效或明确废除一套。
5. **建 GOV smoke**：用 `simos.gov.createOffice` 在 small-world 加中央 GOV，确认 `TAX_*` / `GOV_UPKEEP` 事件出现。
6. **建 Army + 周期军俸**：用 `raiseUnit`/手工批建军；实现通用 `RecurringHouseholdStockEffect`，由 Army/GOV 定义规则，Unit 决策人维护内部分摊。
7. 最后再做测试迁移、`clean verify`、真实小世界总验收。

---

## 9. 未做 / 未验证（诚实边界）

- 未改任何生产代码；本文只是调查。
- 首日“种子吃掉口粮”尚未用 TRACE 逐笔坐实，是强嫌疑，不是最终结论。
- 未实际在 small-world 里建 GOV/Army；只盘点了现有工具路径与阻塞点。
- 未修 `HouseholdEconomyProjection`、未跑修复后的对照 smoke。
- 测试仍未迁移，`test-compile` 仍红；`clean verify` 仍未跑。

---

## 10. 证据与复现命令

### 10.1 打包与 smoke

```bash
cd /home/cna/SimulatorMosire
tools/mvn-lock.sh -q -pl simos-app -am -Dmaven.test.skip=true package

# 独立 store / 端口；后台起
JAVA_TOOL_OPTIONS='-Dsimos.economy.logLevel=DEBUG -Dsimos.actor.logLevel=DEBUG' \
SIMOS_SMALL_WORLD_STORE=/tmp/simos-smoke-store \
SIMOS_SMALL_WORLD_GUI_PORT=5911 \
SIMOS_SMALL_WORLD_MCP_PORT=5915 \
SIMOS_SMALL_WORLD_APPROVAL_PORT=5913 \
./run-small-world.sh > /tmp/simos-smoke.log 2>&1 &

# 等 /api/state 可读后，两次推进
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"branch":"main","expectedRevision":1,"from":0,"to":30}' \
  http://127.0.0.1:5911/api/advance
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"branch":"main","expectedRevision":2,"from":30,"to":365}' \
  http://127.0.0.1:5911/api/advance
```

### 10.2 关键探针输出（当时实测）

```text
# 投影
projected=false unresolved=135
multiple-social-households-per-(hex,residence)=68
no-residence-from-members=53
economy-view-without-social-household=13
total-population-mismatch=1

# 出生
fertileWomen=1098
perGroupBirths(monthly actual)=2 groupsWithBirth=2
aggregatedBirthsIfSummedFirst=21
stress=0    births=2 deaths=0
stress=300  births=0 deaths=0
stress=500  births=0 deaths=0
stress=1000 births=0 deaths=4

# 首日粮食
初始全仓粮食 = 21,666,661
全人口每日口粮需求 = 333,306
day1 INPUTS+CONSUMPTION consumedQuantities=21,710,821
day1 deficitGrainMilli=237,480
day1 DEFICIT_LENDING unmetAfter=216,741
```

### 10.3 tick 365 人口读口 `(0,0)`

```text
population=860
0-14=303，15-59=467，60+=90
FEMALE=428
physiologicalStress: average=1066, max=1526
```

---

## 11. 修复后复测（2026-10-09 实施轮）

> 对应计划：`docs/superpowers/plans/2026-10-09-household-structure-repair-plan.md` §10。
> 代码提交：`ea7945eb`（Social 权威 + Economy 消费注入投影）、`fca92e30`（GM 命令/工具）。

### 11.1 发现 A 已修：人口对账恢复 1:1

- `HouseholdEconomyProjection` 已按 `HouseholdId` 1:1，不再有 `(格,居住)` 分组平均；
- fresh small-world 创世态探针：`unresolved=0`（修复前 135）；
- 365 天 smoke：**无 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED`**；
- 经济行人口回归 Social 投影；`laborMilli/naturalNeeds` 由 app 每日从 Social 逐成员展开后注入。

### 11.2 分档需求/劳动机制已落地

- 全局默认与家户覆盖都在 `simos-social` 的 `SocialProvisioning`（第 6 组件）；
- 默认六档值按计划 §3.5（粮 6000/6000/10000/9000/7000/7000；
  布 600/600/1000/1200/800/900；劳动 4000/4000/16000/8000/0/0）；
- 粮按 120 天累计、家户层一次取整；布按 `YearFraction`；劳动逐成员求和；
- GM 命令 `social.SetDemandCoefficient` / `social.SetLaborCoefficient` 与工具
  `simos.social.demand` / `simos.social.labor` 已注册；fresh smoke 中经 `/api/command`
  实测全局设置、家户覆盖设置/清除、劳动覆盖设置/清除全部成功。

### 11.3 fresh 365 天 smoke 读数

- `-Dmaven.test.skip=true package`：rc=0，前端门禁 412/412；
- 独立 store/端口，`/api/advance` `0→365`：`main` 推进到 tick 365；
- 最终 `finalPopulation=3970`（初始 4000；累计死亡 30、出生 0）；
- **0 个运行时 ERROR**（启动瞬间 `/api/state` 在创世 checkpoint 落盘前被探测到的一次 replay 失败是探针时序，不是新机制错误）；
- `POPULATION_WRITEBACK` 从 day 120 起每月一次，`births=0` 仍未变。

### 11.4 未修项（与 §3/§4 对应）

- **出生仍为 0**：首日播种/口粮优先次序、逐批次整数截断、两套生死引擎未统一这三件事都未在本轮修；
  分档需求降低了部分人群需求，但没有解决出生机制问题；
- `EconomySeeder` 创世初始库存/口粮仍用 population 统一口径；
- `ApiViews.unmetPersonDays` 仍是全局 83 毫粮/人日的量级读数；
- GM 读口未做；
- 测试未迁移，`test-compile`/`clean verify` 未跑。

### 11.5 结论

- 发现 A（人口对账失效）已闭环；
- 发现 C（需求/劳动权威链）已按用户口径 B 落地并写成代码；
- 发现 B（出生为 0）仍是下一批的第一优先 bug。

---

## 12. 360 tick 基线复跑（用户建议；HEAD `a5b28bc8`）

> 目的：在需求权威链修复后，单独跑一轮 360 tick，判断“出生为 0”是不是原需求问题的单纯连带 bug。

### 12.1 运行

- fresh store `/tmp/goal-b360-store`，独立端口 5941/5945/5943；
- 一次 `/api/advance` `from=0,to=360`；`main` revision 1→2；
- 日志：`/tmp/goal-b360.log`。

### 12.2 结果

```text
projection warnings = 0
runtime ERROR      = 0
最终人口           = 3970（初始 4000；死亡 30、出生 0）
每月写回           = births=0（day 120/180/210/240/270/300/330）
tick 360 (0,0)     = stress average=1012, max=1590
day1 投入+消费     = consumedQuantities=21,692,009
初始全仓粮库存     = 21,666,661
day1 deficitGrain  = 190,500
day1 unmetAfter    = 174,908（借贷后）
```

### 12.3 解释

- **出生仍为 0，不能归因于原来的投影/人口对账 bug**：投影已 0 unresolved，365/360 tick 内需求注入正常；
- 分档需求确实改变幅度：day1 粮缺口从修复前 `237,480` 降到 `190,500`，unmetAfter 从 `216,741` 降到 `174,908`，但没有改变“首日播种把口粮吃光”的结构；
- 当前主要链条仍然是：

```text
day1 播种先扣、口粮被吃光 → 长期粮食满足率低 → 压力 >1000
  → 生育抑制 >=500 归零；
与此同时 PopulationDynamics.birthsOf 的逐批次整数截断
  → 即使压力归零，1098 育龄女性也只生约 2/月（应先汇总为 21）；
另有 PopulationDynamics 与 HouseholdBook.settleVitalEvents 两套生死引擎未统一。
```

- 结论：出生为 0 是**独立且复合的 bug 链**，不是原需求/投影 bug 的单纯连带结果；360 tick 复跑可作为下一批“首日播种/口粮 + 出生机制”修复的基线。

### 12.4 下一批建议

1. 先修首日播种/口粮：`drawCycleInputs` / `sowIfCycleStart` 保留 subsistence reserve，或调整初始库存/播种亩数；
2. 再修 `PopulationDynamics.birthsOf` 的逐批次整除：跨批次累积后一次取整；
3. 最后收敛 `PopulationDynamics` 与 `HouseholdBook.settleVitalEvents` 两套生死引擎；
4. 每步用 360 tick 复跑对照本节基线。

### 12.5 追加诊断：压力为什么这么大

用同一份 360 tick 日志 + day1 provisioning 展开做逐日复盘：

```text
day1 全国自然需求（Social 逐户展开合计）：
  grain = 267,514 毫粮/日
  cloth =   9,808 毫布/日
  合计  = 277,322 毫/日

DAY_END 的 unmet 最大值 = 277,326 ≈ 合计需求
  ⇒ 当天粮+布“全缺”；
  360 天里 unmet ≥ 99% 需求的天数 = 81 天；
  unmet > 50% 需求的天数 = 204 天；
  unmet = 0 的天数 = 119 天（从 day240 起才稳定为零）。
```

压力公式（`PopulationDynamics.stressAfter`）：

```text
满缺粮：+10 压力/天；满缺布：+1 压力/天；
满满足：-6 压力/天；
两者都满足时才按 6/天消退。
```

近似复盘（按 `unmet / 277,322` 折算满足率）得到：

```text
day30≈36、day60≈291、day90≈621、day120≈935、
day180≈1340、day240≈1865；
day240 后 unmet 稳定为 0，按 -6/天消退，
day360 仍≈1145（实测平均 1012、最大 1590，同量级）。
```

结论：

- 压力高不是当前（tick360）还在饥荒：tick360 `grainDailyNeed` 已满足、本周期 grain unmet=0；
- 高压力是**前 240 天全缺/严重缺货的历史累积**；
- 压力模型的加减不对称放大了它：满缺 +11/天、满满足只 -6/天；
  两种商品同时缺时，满足率低于约 55% 压力就持续上升，低于约 62.5%（只缺粮）同理；
- 如果“首日播种先扣”是既定设计，那么要解决的是**播种后到首个收获日之间的口粮过渡**（例如种子不计入 subsistence、初始库存覆盖到首次收获、或允许借贷/市场过渡）；
- 如果认为播种/库存都没问题，则下一步应校准压力衰减/门槛参数（`STRESS_DECAY_PER_DAY=6`、`FERTILITY_SUPPRESSION_PER_STRESS=2`、`STRESS_MORTALITY_THRESHOLD=300`），否则 240 天积累需要 300+ 天退清，人口会长期处在低生育区。

### 12.6 储备粮验证：30× 初始粮食储备

按用户建议做最小验证：不改播种流程、不改压力公式，只把 `EconomySeeder` 的初始口粮天数统一放大
`GENESIS_GRAIN_RESERVE_MULTIPLIER = 30`（验证参数，见该常量 Javadoc）。

实测（fresh small-world，`0→360`，同口径独立 store/端口）：

```text
day1 INPUTS+CONSUMPTION consumedQuantities = 344,599,264
day1 deficitGrainMilli                      = 80,250
day1 借贷后 unmetAfter                      = 9,808（≈ 当日 cloth 需求）
最终人口                                    = 4016（初始 4000）
每月写回                                    = births=2、deaths=0（day150 起每月 +2）
tick360 (0,0) stress                        = average=0、max=0
tick360 grain satisfaction                  = 1000‰
本次 errors / projection warnings           = 0 / 0
```

结论：

1. **储备粮是主因之一**：把粮食储备放大后，day1 种子扣款不再吃光全部口粮，grain 当天满足；
2. **压力是链式结果**：粮食满足后，压力没有再累积；360 tick 后 `(0,0)` stress 归零；
3. **出生不再为 0**，但只有 **2 人/月**：这证明“出生 0”主要是压力 ≥500 抑制的结果，同时把
   `PopulationDynamics.birthsOf` 的逐批次整除 bug 露了出来——1098 名育龄女性本应先汇总出约 21 人/月；
4. 因此下一批的正确顺序是：
   - 先修 `birthsOf` 的逐批次整除（跨批次累积后一次取整）；
   - 再校准储备粮的正式口径（30× 只是验证值，不是最终值）；
   - 最后统一 `PopulationDynamics` / `HouseholdBook.settleVitalEvents` 两套生死引擎；
   - 每步继续用 360 tick 对照本节与 §12 前文基线。

---

## 13. 后续收口（2026-10-09 Batch A/B/C，本文调查之后的实现）

上文 §12.6 的“下一步”已实施，口径改为 **Social 每 tick 生死引擎**（不再修 `PopulationDynamics.birthsOf`，
该类连同压力/月度路径整体删除）。施工依据、Unit 对接规划与实测留档：

- `docs/superpowers/plans/2026-10-09-social-vital-rates-per-tick-plan.md`（§7.2 是 Unit 对接前的 P0 旁路，
  §10 是本批实测）；
- `docs/superpowers/HANDOFF-2026-10-09-social-vital-engine.md`（下一会话入口）。

本批对本文结论的更新：

```text
10.2 的 fertileWomen=1098 / 月出生=2 / 应约 21  → 旧 PopulationDynamics 口径，已作废；
10.2 的 stress 读数与 §12 高压力复盘        → physiologicalStress 已删除，读口不再有数；
12.6 的“逐批次整除 bug / 两套引擎未统一”    → 随 PopulationDynamics 删除而关闭；
本批 fresh 0→360：births=258、deaths=157、final social=economy=4101、ERROR=0；
余数零初值在 360 tick 只实现出 20 死亡（连续期望 156），已改稳定哈希初相位修正，见计划 §10.3。
```

仍未关闭：`ModeMigrationSettlement` 直接改经济行人口（计划 §7.2 P0）、`FlowRow.births/deaths` 读口、
GM/决策人工具、测试迁移、Unit 四件套。
