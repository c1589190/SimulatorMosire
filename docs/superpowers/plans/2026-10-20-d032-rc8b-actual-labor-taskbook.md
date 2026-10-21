# RC8b 任务书：实际家户劳动/需求接入生产 + 劳动承诺修复 + 动态雇佣

> **状态：约束设计书/实现任务书；只写生产代码到 `-DskipTests compile` 通过。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父计划：`docs/superpowers/plans/2026-10-20-d032-v4-full-repair-plan.md` §4 RC8b。
> 依赖：RC8a 已 compile 绿。
> 本单是用户点名的 #2 主修复：**单个家户的实际总劳动、实际总口粮需求必须成为生产计划的当前输入，而不是创世 roster 快照。**

---

## 1. 目标运行序列锚点

```text
S0 Social
  - SocialData.householdLaborMilli：逐户、逐成员批次、年龄档/性别/labor provisioning 计算实际劳动；
  - SocialData.householdNaturalNeeds：逐户、逐成员批次、年龄档/性别/demand provisioning 计算实际日需求；
  - App 每 tick 注入 economy。

S1 经济载入
  - HouseholdEconomy.laborMilli / naturalNeeds 是当 tick 的只读当前事实。

S2 组织
  - 生产组织/名册/劳动位置刷新时，必须读取当前 laborMilli / naturalNeeds，而不是创世 plannedLabor。

S3/S4/S6
  - organizer 的自耕劳动、hired 的劳动目标、wage demand 必须随当前户人口变化。
  - target 无地户新增人口必须能进入 hired 位置；不能只作为人口数字停留在 target 户。
  - 迁移时 source 的生产承诺按“剩余人口比例”保留；target 需要承接时显式建立/刷新位置。
```

---

## 2. 当前事实（file:line）

- `SocialData.householdLaborMilli`（`simos-social/.../SocialData.java:774-800`）：
  - 逐成员 `PeopleLotId` → 当前 `AgeBracket` / `Sex` → `LaborCoefficient`；
  - 乘 `satietyPerMille`。
- `SocialData.householdNaturalNeeds`（`...:844-900`）：
  - 逐成员、年龄档/性别、provisioning demand coefficient；
  - 输出逐户 `Map<CommodityId, Long>`。
- App 已每 tick 调：
  - `PopulationEconomyTimeParticipant.java:2169-2177`（labor）；
  - `...:2202-2217`（needs）。
- `ProductionRoster` / `ProductionParticipant` 当前 `plannedLaborMilliPerDay` 是创世时计划快照，不是当前实际劳动权威。
- `ProductionPlanBridge.effectivePlannedLaborMilliPerDay`（`...:180-201`）：
  - 若 participant plannedLabor > 0，直接返回旧值；不按当前户人口重算。
- `ProductionRuntimeSeeder`（`simos-app/...:392-465`）：
  - 每个 farm 创世时固定一个 hired participant，plannedLabor = 初始无地户 40 人的劳动；
  - 之后人口增长不更新该 participant。
- `HouseholdTransferSettlement.java:388`：
  - `after = before * population / sourcePopulationBefore`，部分迁移时方向错误。
- 当前 360：
  - `hiredLaborMilli` 长期约 54–70 万，target 无地户人口已 180+；
  - `D032_P3_EXPECTED_PLAN plannedHouseholds` 66 → 27 → 10 → 3 → 2；
  - harvest 2.05B → 0.59B → 0.32B。

---

## 3. 必须达到的行为

### 3.1 当前实际总劳动成为生产计划输入

- 不做第二套年龄/性别/需求公式；Economy/Production 只能消费 Social 已注入的：
  - `HouseholdEconomy.laborMilli`；
  - `HouseholdEconomy.naturalNeeds`。
- organizer 的自耕劳动必须是当前户 `laborMilli` 的可用部分，而不是创世 `plannedLabor`。
- hired 劳动者的可用劳动必须来自当前 target 户的实际 `laborMilli`，不是创世 22,040。
- 当前户实际劳动变化（人口出生/死亡/迁移）必须在下一个生产 tick 影响：
  - expected production plan；
  - actual production plan；
  - labor queue / commitments；
  - wage demand / wage settlement。

### 3.2 动态雇佣位置

当前 32hex 每个农村 hex 有 1 个 LANDED + 1 个 LANDLESS。修复后至少：

```text
LANDED 户的 farm 在 S3 发现的 hired_laborer 位置，必须能吸收同 hex 当前 LANDLESS 户的实际劳动；
LANDLESS 户人口从 40 → 180 后，下一个生产 tick 的 hired 目标/可提供劳动随之增长；
不能再固定为 22,040。
```

实现可以是以下之一，但必须有唯一权威、不得双计：

- A. 每 tick 生成/刷新当前 effective roster（保持 `ProductionRoster` 结构，participants 的 plannedLabor 改为当前 actual labor）；
- B. 新增瞬态 `HiredLaborPosition`/effective labor plan，供 `ProductionPlanBridge`/`ProductionRunSettlement`/`LaborQueuePlanBook` 统一读取；
- C. 在保留持久 roster 的情况下，引入一个 transient `CurrentProductionLaborPlan`，在 S3/S4/S6 之间共享。

不允许：

- 在 economy 重算年龄/性别系数；
- 让 `ProductionRoster.plannedLabor` 继续作为当前权威；
- target 户多出来的人只留人口数字、没有生产位置；
- 把同一批劳动同时计入 organizer 和 hired（必须靠 `LaborQueueBook` 的预算/全局分配）。

### 3.3 迁移劳动承诺

`HouseholdTransferSettlement` 部分迁移：

```text
source 保留比例 = (sourcePopulationBefore - population) / sourcePopulationBefore
```

- 160 人迁 16 人：84,093 → 约 75,684，而不是 8,408；
- source 的 PRODUCTION 承诺只按剩余人口缩，不得把剩余 90% 人口的生产承诺一并删掉；
- target 若需要承接生产位置，必须在同一 revision/约定位置显式建立或刷新；
- 不把 LAND 随人搬走。

### 3.4 需求口径

- 本单不实现完整 `DailyDemandBill`（RC8c），但必须保证 current `HouseholdEconomy.naturalNeeds` 已经按 Social 当前人口刷新，并被生产/市场路径读取；
- 不允许使用 `population × 固定系数` 或创世 naturalNeeds 代替当前 Social 输出；
- `naturalNeeds` 的乘 5 只允许在 S7 需求派生处，不得写回 Social 或生产预算。

---

## 4. 允许修改的文件

优先只改以下文件；若确实需要新增 transient 类型，优先放在 `simos-economy/time`：

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionPlanBridge.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayExpectedProductionPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayActualProductionPipeline.java
simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueuePlanBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/LaborQueueSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/WageQuoteBridge.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionRunSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdTransferSettlement.java
simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionRoster.java（仅必要时）
simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionParticipant.java（仅必要时）
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayContext.java（仅 transient 字段）
simos-app/src/main/java/io/mosire/simos/app/world/ProductionRuntimeSeeder.java（仅当必须改 seed roster shape；优先不改）
```

禁止：

- 改 Social 的年龄/性别公式或 provisioning 数据；
- 改 LAND 市场/价格/债务/工资公式之外的数值；
- 改测试；
- 跑 test/test-compile/verify/package；
- commit/push；
- 碰主树。

---

## 5. 实现建议（不强制类结构，但必须满足外部行为）

### 5.1 先修一眼可见的承诺方向错误

`HouseholdTransferSettlement.shrinkSourceProductionCommitments` 的公式改为：

```java
long remaining = sourcePopulationBefore - population;
long after = empties ? 0L : Math.multiplyExact(before, remaining) / sourcePopulationBefore;
```

并保留：

- 只缩不增；
- source 人口迁空才 0；
- target 不凭空获得 source 的承诺；
- 非 PRODUCTION 承诺仍按原规则不缩/不搬。

### 5.2 当前劳动覆盖 roster plannedLabor

至少在以下路径中，participant 的 effective labor 必须读当前 `HouseholdEconomy.laborMilli`：

- `ProductionPlanBridge.effectivePlannedLaborMilliPerDay`；
- `ProductionPlanBridge.probeRosterOf` 的 `ProductionRosterProbeParticipant`；
- `ProductionPlanBridge.evaluateRoster` 的 `plannedByParticipant` / `PositionPlan`；
- `WageQuoteBridge.wageDemandsOf` / `hiredWageLaborByRole`；
- `ProductionRunSettlement` 的 roster contribution 校验（必须接受当前有效参与项；不能因旧 roster 没有某户而 `role-unknown`）。

建议把“当前 effective participant labor”收敛到一个 transient 方法/记录，禁止在 4 处各写一套 `Math.min/max`。

### 5.3 hired 目标不能再用标准角色固定值

当前 `LaborQueuePlanBook` 的 money wage offer 使用标准角色 target，容易把 hired 需求固定成 22,040。必须让 hired 目标来自：

```text
本 unit/本生产位置的实际需求
（至少：probe.laborRequiredMilliPerDay − organizer 当前实际自耕劳动；或等价的 effective labor plan）
```

- 不得用 target 户的 **实际总劳动** 直接冒充 employer 需求；
- 多户同时响应同一 unit 时，仍由 `LaborQueueBook` 的全局预算/分配封顶；
- 同一户的 labor 不得在 organizer + hired 两处同时计入；
- target 人口增长后，下一个生产 tick 的 hired 目标/可提供量必须变化。

### 5.4 动态雇佣的最小行为

当前 32hex 中：

- LANDED 户 160→20，LANDLESS 户 40→180；
- 下一个生产 tick，LANDLESS 户应有能力向同 hex farm 提供远大于 22,040 的劳动；
- 但 `LaborQueueBook` 仍只授予 farm 实际需要的部分，不能把 180 人的全部劳动都塞进一个只种 681 亩的 farm；
- `hiredLaborMilli` 不应再全年固定约 54–70 万；
- 若 farm 只需要固定劳动，超过需求的 LANDLESS 劳动应 idle，不得通过虚假产出吸收。

### 5.5 需求注入

- App 已注入 `naturalNeeds`，本单不改 Social；
- 需确认 `SettlementDayProductionPipeline.settleConsumption` 使用当前 `naturalNeeds`；
- `MarketIntentBook` 的 `naturalNeeds*5` 仍仅代表基础消费 5 tick 前瞻；
- 不要在本单顺手实现 `DailyDemandBill`（RC8c）。

---

## 6. 验收锚点

### 正例

1. Social 逐户 labor/naturalNeed 与 economy 当 tick 投影逐户一致；
2. day10 第一次 flow（160 人迁 16 人）后 source production commitment 约 84,093→75,684，而不是 8,408；
3. source 20 人时，其 organizer 劳动不再按 160 人历史值计算；
4. target 180 人时，其可提供 hired 劳动不再固定 22,040；farm 实际需要的部分能被 hired/queue 使用；
5. `D032_P3_EXPECTED_PLAN plannedHouseholds` 不再因一个 target 户被固定 old plannedLabor 而断崖式下降；
6. `hiredLaborMilli` 与 source/target 实际劳动和 farm 实际需求同步；
7. 迁移后 target 的生产参与位置/承诺与 source 的剩余人口比例一致；
8. 不出现同一户同一劳动同时进入 organizer 和 hired（预算超发）。

### 负例

- 用 `population × 固定系数` 重算 labor/need ⇒ 红；
- 用创世 `plannedLabor` 继续当当前权威 ⇒ 红；
- 160 人迁 16 人后 source commitment 变成 10% ⇒ 红；
- target 180 人但 hired 仍固定 22,040 ⇒ 红；
- 同一户劳动在 organizer/hired 双计 ⇒ 红；
- 为吸收劳动而伪造超需求产出 ⇒ 红；
- target 没有 roster 参与项却被 `ProductionRunSettlement` 拒绝为 `role-unknown`（若它是当前有效参与户）⇒ 红。

---

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑测试；不 commit/push；不碰主树；
- 账本落 `.superpowers/sdd/2026-10-20-d032-rc8b-actual-labor/impl-ledger.md`；
- 报告必须列出：
  - 当前 labor/need 数据流；
  - effective roster/labor plan 的唯一入口；
  - hired 目标的新来源；
  - 劳动承诺公式修复；
  - 可能被旧测试断言锁死的形状；
  - 未做/未验证/blocker。

---

## 8. 用户原话附录

来源：本会话 2026-10-20。

```text
2我认为是主要问题，单个家户的实际总提供劳动力、实际总口粮需求等，都是要根据据家户的人员规模（甚至根据Social的设计，还得分年龄段）来算的，这是不是没做？
```

```text
5 可以适当减小，减到1%这个数量级
```

```text
7 是之前一连串错误的必然结果，暂时不直接修
```

```text
理论上应该按V4架构完整实现
```
