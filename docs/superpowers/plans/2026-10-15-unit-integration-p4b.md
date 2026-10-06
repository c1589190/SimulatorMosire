# 2026-10-15 Unit 对接 P4b：ArmyFormation 军俸政策 + Unit 内部分摊桥接 P4a

> 依据：`docs/superpowers/plans/2026-10-14-unit-integration-p4a.md` §6（P4b 待办）；
> 用户 2026-10-14 确认路线 C+PARTIAL：P4a 通用规则/执行器已落，P4b 做 Unit 政策与内部分摊；
> GM/决策人窄工具端仍后置。
> 基线：`HEAD 426a0e67`（P4a 已推送）。

## 1. 现状与目标

- P4a 已有 Economy 通用周期规则表 + 无状态到期 + 部分支付执行器；规则由 GM 命令显式注册。
- 缺 Unit 侧“谁交、交多少、怎么分”的声明式政策：`ArmyFormation` 只有 `masterGov/role/militaryDutiesOfHousehold`。
- 目标：给 `ArmyFormation` 增加 **`MilitaryPayPolicy`**（周期 + 逐家户显式份额，不做跨户平均）；
  新增 `unit.SetArmyPayPolicy` 命令（非 GmOnly，未来决策人可嵌令）；
  app 在日循环把政策**派生**成 P4a 规则（payer = 认领 GOV 的国库家户，payee = 政策点名的军户），
  交给同一个 P4a 执行器；不新增第二套扣账路径。

## 2. 数据模型

### 2.1 `MilitaryPayPolicy`（simos-unit）

```text
record MilitaryPayPolicy(
    long periodDays,                 // > 0
    long phaseDay,                   // [0, periodDays)
    long startsOnDay,                // >= 0；day 0 是创世，执行从 day>=start 起
    OptionalLong expiresOnDay,       // 空 = 永久；给了 >= startsOnDay
    Map<HouseholdId, Long> grainPerHouseholdPerCycle,   // 键 ⊆ Unit.households；值 > 0
    Map<HouseholdId, Long> clothPerHouseholdPerCycle,
    Map<HouseholdId, Long> moneyPerHouseholdPerCycle)   // 三张表至少一腿非空
```

- `disabled()`：三张空表 + periodDays=1/phase=0/starts=0/expires 空，`enabled()==false`；
- 键必须出现在所属 `Unit.households()`（`UnitState` 构造期强判，拒绝“给不存在于本单位的人发钱”）；
- 值必须 > 0；不得含 null；保序不可变；
- 这是“内部分摊”的唯一载体：每个家户拿多少由政策显式给出，**不做人口平均/跨户比例**；
- 周期/相位语义与 P4a `HouseholdPeriodicAdjustment` 完全一致（无状态 due）。

### 2.2 `ArmyFormation` 第 4 组件

- 追加 `@JsonProperty("militaryPayPolicy") MilitaryPayPolicy militaryPayPolicy`；
- canonical 构造器：null/缺键 ⇒ `MilitaryPayPolicy.disabled()`（旧档兼容）；
- 保留旧 2 参/3 参便捷构造器（policy=disabled）；
- `UnitState` 构造期追加：policy 三张表的键必须 ⊆ `Unit.households`（与 militaryDuties 同款）。

### 2.3 命令 `unit.SetArmyPayPolicy`

- 载荷：

```json
{
  "unitId": "army-1",
  "periodDays": 3, "phaseDay": 0, "startsOnDay": 0,
  "expiresOnDay": null,
  "grainPerHouseholdPerCycle": {"hh-unit:army-1": 300},
  "clothPerHouseholdPerCycle": {},
  "moneyPerHouseholdPerCycle": {"hh-unit:army-1": 50}
}
```

- 语义：单位必须存在且带 `ArmyFormation`；同类型重复设置 = 整体替换 policy；
  未给的表 = 空表；三表全空 = `disabled()`（允许，表示停发）；
- `UnitOperations.setArmyPayPolicy(state,id,policy)`：只换 policy，`masterGov/role/militaryDuties` 原样带过；
- 非 GmOnly（unit 域日常政策命令，未来可进决策令）；注册 Shell + CatalogTool。

## 3. app 桥接：政策 → P4a 规则

新增 `simos-app/.../time/MilitaryPayRuleBridge`（纯函数）：

```text
derive(UnitState units, SocialData social, EconomyData economy, long day)
  → List<HouseholdPeriodicAdjustment>
```

逐 Army 单位（unitId 升序）：
1. policy 为空/disabled ⇒ 跳过；
2. `masterGov` 空 ⇒ 具名读数量跳过（不阻断其它单位）；
3. `payer = GovernmentHouseholds.of(masterGov.value())`；该家户必须已在 Social 且账户已登记/有账
   （否则该单位跳过，ex Ante gap 读数；不自动造户/造账）；
4. 逐政策家户（householdId 升序）：若该家户不在 `unit.households()`（坏数据）⇒ 跳过并 gap；
   构造一条 `HouseholdPeriodicAdjustment`：
   - id = `army-pay:<unitId>:<householdId>`；
   - payer = 国库家户；payee = 该军户；
   - goods = `{GRAIN: grainPerHouseholdPerCycle.get(hh)（缺省不落键）}` + `{CLOTH: cloth...}`；
   - money = `{SILVER: money...}`；
   - reason = `MILITARY_SALARY`；period/phase/starts/expires 取 policy；
   - policySource = `army:<unitId>`；
   - 空腿家户不生成规则；
5. 多条规则按 P4a 执行器的 id 升序自然执行；部分支付/缺账 gap/shortfall 全部复用 P4a 语义。

### 3.1 执行器接线

- `PeriodicHouseholdAdjustmentExecutor.applyDue(...)` 增加“额外瞬态规则”入口（保持纯函数、不把政策写进 EconomyData）：
  `applyDue(EconomyData economy, Collection<HouseholdPeriodicAdjustment> extraRules, AccountSession accounts, long day)`；
  内部先把 economy 持久规则与 extraRules 按 id 合并/去重（同 id 冲突 ⇒ 具名拒或持久规则优先，实现时定并注释）；
- `PopulationEconomyTimeParticipant` 日循环：
  - 从 `units`（已有）与 `currentSocial` 现算 `MilitaryPayRuleBridge.derive(...)`；
  - 传入执行器；执行器只读取不修改 extraRules；
  - 日志：`MILITARY_PAY_BRIDGE` DEBUG 汇总（units/policies/rules/gaps），逐规则由 P4a 执行器日志承担。

### 3.2 边界

- P4b 不注册任何持久规则到 `EconomyData`；政策是唯一权威，规则是每日现算的派生件。
- 政策变更（SetArmyPayPolicy）在 day 边界后的下一次 advance 生效；无状态 due。
- GM/决策人窄工具、审批、FlowRow 军俸维度、GOV/军俸共享国库预算优先级仍留 P4c/工具批。
- 不做 per-person 军职人头/跨户平均；要做时另开 Social/Unit 具名维度，不在 P4b 猜。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；`package` rc=0；
2. fresh harness（复用 P3/P4a 的 `Shell + gmToolAuthorizer/command.submit` 模式）：
   - `simos.gov.createOffice` 建 GOV（`hh-gov-<govId>` 国库账户）+ `simos.unit.raiseUnit` 建军（`hh-unit:<armyId>` 人口户/经济行/账户）；
   - 用 GM/命令给国库种粮/钱；
   - `unit.SetArmyPayPolicy`：periodDays=3/phase=0，grain/money 按军户给额；
   - advance 到 due−1/due/due+1：
     - 非到期日两户逐值不变；
     - 到期日国库减少 == 政策请求（或部分支付时 == paid，shortfall 读数正确）；
       军户增加 == paid；世界总量相应守恒；
     - due+1 不再扣；
     - 政策 disabled/删除后下一到期日不再扣；
   - 1×N == N×1：同一 base 一次 advance vs 逐日，账户最终逐值相等；
   - 重启同 store：ArmyFormation policy / 账户 / head 重载一致；
   - 非法政策载荷（period≤0/phase 越界/键不在 Unit.households/值≤0/null）⇒ 命令 Rejected、零 revision；
3. 不跑 test/test-compile/verify；不 commit/push（控制方最后做）。

## 5. 文件范围

**允许**
- `simos-unit/src/main/java/io/mosire/simos/unit/MilitaryPayPolicy.java`（新）
- `simos-unit/src/main/java/io/mosire/simos/unit/ArmyFormation.java`
- `simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java`（policy 键 ⊆ households 守卫）
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java`
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/SetArmyPayPolicyHandler.java`（新）
- `simos-app/src/main/java/io/mosire/simos/app/time/MilitaryPayRuleBridge.java`（新）
- `simos-app/src/main/java/io/mosire/simos/app/time/PeriodicHouseholdAdjustmentExecutor.java`
- `simos-app/src/main/java/io/mosire/simos/app/time/PopulationEconomyTimeParticipant.java`
- `simos-app/src/main/java/io/mosire/simos/app/Shell.java`、`tools/read/CatalogTool.java`
- 必要的类注/javadoc 同步

**禁止**
- 改 `SocialData`/`EconomyData` 持久组件（P4a 规则表已够用）；
- 改 `GovernmentFormation`/`OfficePolicy`/GovDaily 语义；
- 引入 economy-api 依赖到 simos-unit（军俸政策只用 unit 自己的资源命名 grain/cloth/money；商品/币种 id 在 app 桥接处映射）；
- 改测试；commit/push。

## 6. P4c/工具端仍待办

- GM 窄工具 `simos.unit.armyPayPolicy`（preview/apply、before/after 读数）；
- 决策人受限工具与审批链；`DirectiveWhitelist` 是否放开 `unit.SetArmyPayPolicy`（现 unit 命令本就非 GmOnly，准入由 scope 决定）；
- 军俸 FlowRow/ledger 维度与读口；
- 与 GOV 行政俸禄共享国库的预算/缺口优先级（当前 P4a 分别顺序扣，先税→GovDaily→军俸规则）。

---

## 7. 实施状态（2026-10-15）

✅ 已实现并验收：

- `MilitaryPayPolicy` + `ArmyFormation` 第 4 组件（旧档缺键 ⇒ disabled；旧 2/3 参构造器保留）；
  `UnitState` 强判 policy 三表键 ⊆ `Unit.households`；
- `unit.SetArmyPayPolicy`（非 GmOnly）已注册；`UnitOperations.setArmyPayPolicy` 只换 policy；
  `SetArmyFormationHandler` 载荷未给 policy 时保持既有（给了则整体替换）；
- app `MilitaryPayRuleBridge.deriveReport(units, social, economy, day)` 把政策派生为
  `army-pay:<unitId>:<householdId>` 瞬态规则；P4a 执行器新增 extraRules 入口（持久规则优先）；
  日循环 GovDaily 后接线，DEBUG `MILITARY_PAY_BRIDGE` 汇总；
- 主控独立 smoke（`/tmp/P4bSmoke.java`，102 断言全 PASS）：
  - SetArmyFormation 不清 policy；due−1 两户逐值不变；
  - due：国库 grain 1000→700、money 30→0；军户 grain 0→300、money 0→30；
    `paidMoney={silver=30} shortfallMoney={silver=20}`（部分支付）；世界账户总量守恒；
  - 第二到期日 grain 再扣 300、money 全额缺（shortfall 50）；due+1 不重复扣；
  - 三表全空=disabled 后跨过原到期日不再扣；
  - 1×N == N×1 账户逐值相等；重启 policy/账户/head 重载一致；
  - 非法载荷（period 0/phase 越界/键不在 unit/值 0/expires 倒置/enabled 空表）全部 Rejected 零 revision；
- compile/package rc=0；未跑 Java test/verify/spotless。

P4c 仍待办（GM/决策人窄工具、审批/白名单、FlowRow/ledger 军俸维度、与 GovDaily 共享国库预算优先级）。
