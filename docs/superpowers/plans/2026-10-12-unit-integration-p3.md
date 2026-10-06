# 2026-10-12 Unit 对接 P3：UNIT 家户经济行登记（打通 raiseUnit 次日 advance）

> 依据：`docs/superpowers/plans/2026-10-11-unit-integration-p1.md` §3 P1.3 / §5.5；
> P1.3 smoke 已实测：`social.SubmitHouseholdWorkOrder` + `unit.CreateUnit(households=[hh-unit:<id>])` 的 apply
> 本身提交成功、人口守恒，但**次日 advance** 在
> `CLASSROW_POPULATION_PROJECTION_UNRESOLVED: Social 家户缺经济行 hh-unit:<id>` 处 fail-closed。
> 基线：`HEAD c0218a5d`。

## 1. 问题

- `hh-gov-<unitId>` 有经济行：`GovCreateOffice` 同批发 `economy.RegisterGovernment`（它顺带建 classes 行）。
- `hh-unit:<newUnitId>` 没有：`RaiseUnit` 只发 Social + unit + actor(粮钱) + sd，未登记 `HouseholdEconomy`，
  也没给新家户补 actor 账户。
- `HouseholdEconomyProjection` 对“Social 有家户、Economy 缺行”fail-closed；推进随后在时间预算投影处抛
  “时间预算指向不存在的家户”。
- 这是 raiseUnit 的既有缺口（P1.3 未引入），P3 必须闭环，否则任何新建 Unit 人口家户的路径都不能过 advance。

## 2. 方案：通用 `economy.RegisterHousehold` + actor 账户同批

### 2.1 新命令 `economy.RegisterHousehold`（GmOnly）

作用：给任意 Social 家户补一条 `HouseholdEconomy` 行（经济侧的人口/劳动/需求物化视图落点），
不建政府、不建资产、不建债务、不写成员；成员真值仍在 Social。

载荷形状：

```json
{
  "household": "hh-unit:u-1",
  "q": 0, "r": 0,
  "residence": "urban|rural",          // 可选；缺省 urban
  "stratum": "landless_laborer",       // 可选；缺省 landless_laborer（词表内）
  "participationPerMille": 0,          // 可选；缺省 0
  "reason": "raise-unit:u-1"
}
```

行为：
- 行不存在 ⇒ 新建 `HouseholdEconomy(id, CohortKey(hex, residence, stratum), population=0,
  laborMilli=0, participationPerMille=载荷值, money=0, debts=[], naturalNeeds={}, effectiveDemand={},
  cycleNaturalNeedMilli=0)`；
- 行已存在 ⇒ 幂等：`q/r/residence/stratum` 与既有 view 不一致 ⇒ 具名拒（要搬家走
  `economy.MigrateHousehold`）；一致 ⇒ 只按显式 `participationPerMille` 更新，其余保留；
- 不创建 `FlowRow`、不创建 `HouseholdClassMembership`、不创建账户（账户归 actor，同批
  `actor.EnsureHouseholdAccount` 补）；
- 不要求 `q/r` 有产业（`EconomyData` 只在 flows 行存在时要求 view 有产业；新行没有 flow）；
- GmOnly（结构身份写口，不许决策令直接调）；仍然可由 app 组合工具在 GM 授权上下文中同批提交。

### 2.2 `RaiseUnit` 批序扩展

在 P1.3 的批序基础上插入两条：

```text
1. social.SubmitHouseholdWorkOrder           // CREATE_HOUSEHOLD(hh-unit:<id>, UNIT) + TRANSFER_MEMBERS
2. unit.CreateUnit                           // households=[hh-unit:<id>]，无 manpower
3. economy.RegisterHousehold                 // 新建 hh-unit:<id> 的经济行（0 人/0 劳动）
4. actor.EnsureHouseholdAccount              // 给 hh-unit:<id> 补零余额账户
5. [actor.AdjustAccounts]                    // 粮/钱 >0 时照旧
6. sd.PutInfo
```

- 位置：`economy.RegisterHousehold.q/r = at`（unit 落点）；
- `residence` 默认：`at` 是 `SocialData.cities()` 里某城的 `at()` ⇒ `URBAN`，否则 `RURAL`；
- `stratum` 默认：`landless_laborer`（词表内、无资产的中性档）；未来 Unit 决策人/阶层模型可显式改；
- `participationPerMille=0`：由 Social 逐户 labor 预算在后续日循环注入，不在登记时猜。

### 2.3 为什么不让 projection 自动补行

- projection 的职责是“把 Social 真值同步到既有经济行”，不是结构注册；让它隐式造行会让
  “哪个命令创建了经济身份”无法审计；
- 自动补行还需要账户/Class 归属等 actor 侧动作，projection 拿不到；显式命令 + 同批 actor 账户是
  与 `GovCreateOffice`/`economy.RegisterGovernment` 同形的正确路线。

## 3. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. fresh harness：`simos.unit.raiseUnit`（manpower=5）→ apply committed；
3. **同一 store 继续 `simos.advance` 5 天**：
   - 0 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED`；
   - 0 `时间预算指向不存在的家户` / `UNIT_HOUSEHOLD` 不一致；
   - `hh-unit:<id>` 经济行存在，`population == Social.householdPopulation(hh-unit:<id>)`（首日 5，随后按
     每 tick 生死自然变化）；
   - 世界 Social 总人口变化 == 该 UNIT 家户 births−deaths（其余家户守恒）；
4. 同 store 重启重载：新经济行 + 家户 + 账户都还在；
5. 不跑 test/test-compile/verify；不 commit/push（控制方最后做）。

## 4. 文件范围

**允许**
- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyRegisterHouseholdHandler.java`（新）
- `simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPayloads.java`（若需解析辅助，按窄改）
- `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（注册 handler）
- `simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java`（PAYLOAD_HINTS，如命令列表要求）
- `simos-app/src/main/java/io/mosire/simos/app/tools/write/RaiseUnitPlan.java`、`RaiseUnitTool.java`
- 必要的类注/javadoc 同步

**禁止**
- 改 `Unit`/`UnitState`/`GovernmentFormation`/`SocialData` 持久组件形状；
- 改 `HouseholdEconomy` 持久字段；
- 触 `GovCreateOffice`/`economy.RegisterGovernment` 现有语义；
- 改测试；commit/push。

## 5. 已知边界 / 后续

- 本批只登记 UNIT 家户的**中性经济行**；"军官/士兵/官署" 的阶层身份、工资/给养、Unit 决策人内部
  分摊仍是 P4 范围；
- `GovSelectExaminees`、`GovDispatchTeam`、`SpawnArmy` 仍 plan 级 fail-closed（P1.0），待后续接
  同形批次（work order + RegisterHousehold + EnsureHouseholdAccount）；
- Unit 家户的生死/迁移 smoke 在本批补；Unit 家户跨格移动由 resolver/P2 范围处理。

---

## 6. 实施状态（2026-10-12）

✅ 已实现并验收：

- `economy.RegisterHousehold`（GmOnly）已注册进 Shell；`CatalogTool.PAYLOAD_HINTS` 已同步；
- `RaiseUnit` 批序扩展为 `SubmitHouseholdWorkOrder → unit.CreateUnit → economy.RegisterHousehold →
  actor.EnsureHouseholdAccount → [actor.AdjustAccounts] → sd.PutInfo`；`residence` 按 at 是否城市格取
  urban/rural，`stratum=landless_laborer`、participation=0；
- 主控独立 smoke（small-world，harness `/tmp/P3Smoke.java`，101 断言）：
  - apply 后 `hh-unit:<id>` Social 家户 5 人、economy 行存在（0 人物化视图）、actor 账户存在、
    `unit.households=[hh-unit:<id>]`、Unit 无 manpower；
  - 同 store 连续 `advance` 5 天：0 `CLASSROW_POPULATION_PROJECTION_UNRESOLVED`、0 时间预算缺行、
    0 UNIT_HOUSEHOLD 异常；每天 Social 逐户人口 == economy 行人口；世界人口变化 == births−deaths；
  - 重启同 store：head/家户/经济行/账户全部重载一致；
- `compile` / `package` rc=0（前端 412/412）；未跑 Java test/verify/spotless。

后续：P4 仍是 Unit 决策人/军俸通用周期扣增；`GovSelectExaminees` / `GovDispatchTeam` / `SpawnArmy`
仍 plan 级 fail-closed，可照 raiseUnit 的同形批次接线。
