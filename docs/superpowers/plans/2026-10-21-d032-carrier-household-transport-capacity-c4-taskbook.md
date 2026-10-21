# D-032 C4 责任区任务书：向下 population-only 流动（2026-10-21）

> **状态：可派单的实现任务书；只写生产代码到 `compile` 绿，不写/不跑测试，不 commit/push。**
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`
> **父设计：** `docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md` §8.3/§8.4/§11/§13
> **父计划：** `docs/superpowers/plans/2026-10-21-d032-carrier-household-transport-capacity-plan.md` §6 C4
> **上游：** C1/C2/C3 已完成且 compile 绿；C2 已允许 no-mode outbox，C3 已留 LAND upward primitive。

---

## 0. 用户原话与冻结口径

```text
阶级向下流动不需要有产者家户失去所有生产资料，只需要随着负债增加，给一个家户迁移数值就行，只迁移人口的那种——我记得家户人员流动表有搞吧？
```

```text
是的，HouseholdFlowBook不记库存也不记债，只记人净身出户，也就是方向B
```

冻结结论：

- `HouseholdFlowBook` 只迁人口；不搬钱、货、债、LAND、非 LAND 资产、经验；
- source 保留生产资料 ⇒ 仍是 OWNER；target 仍是 PROLETARIAN；
- source 劳动承诺按迁出人口比例缩减；target 不承接 source 的库存/债/承诺；
- `lastObservedDebtPrincipalMilli` 只是触发基线，不是搬债；
- 目标同 hex、同 culture 优先，不要求 productionMode；
- target 不存在 ⇒ 建 0 人口 PROLETARIAN 壳，无 mode/currentPosition/classStanding；
- 每市场轮每户最多一次，source 至少保留 1 人；
- 1% 量级、债务增量触发、hysteresis/cooldown 沿用现有口径。

---

## 1. 目标运行序列

```text
S7-B（C3）完成后
S7-C HouseholdFlowBook：
  对每个 OWNER source（population>0，HouseholdId 稳定序）：
    读 sourceHex / cultureKey / 当前未偿本金 / 上轮 lastObservedDebt
    维护 HouseholdFlowTable（sourceHex, targets, lastObservedDebtPrincipalMilli, lastFlowDay）
    目标 = 同 hex、PROLETARIAN、正人口（含本轮 projected 已流入）
           同 culture 优先；否则最小 HouseholdId + INFO mismatch
           没有正人口 target ⇒ 建 0 人口 PROLETARIAN 壳，无 mode/position/standing
    触发 = 非首次观察 + 债务本金增量达到阈值 + day>lastFlowDay
    迁出量 = min(floor(sourcePopulation×10/1000), sourcePopulation−1)
    执行 population-only：
      source 劳动承诺按迁出人口比例缩减
      只追加人口 outbox（targetMode=Optional.empty）
      不搬钱/货/债/LAND/非 LAND 资产/经验
    source 仍保留原生产资料 ⇒ OWNER
S7-END
  HouseholdFlowBook.pruneStaleTables 继续按当刻 ownershipClass 清陈表
```

---

## 2. 责任区六要素

### 2.1 问题现场（C1/C2/C3 后）

- `HouseholdFlowBook.settle` 仍读 `currentModeOf(source)`，目标选路仍要求 `candidateMode.equals(sourceMode)`。
- 没有同 mode 正人口目标时仍有 `household-flow-wage-position-missing` 旧风险。
- 触发后仍调用会搬钱/货/债/经验的 `HouseholdTransferSettlement.apply`。
- 目标壳仍创建 `HouseholdClassMembership` / currentPosition，并给 targetMode。
- `pruneStaleTables` 已切到 ownershipClass，保留。
- `HouseholdTransferSettlement` 的 `targetMode` 已在 C2 变为 Optional，但其完整 apply 不能用于 C4。

### 2.2 责任归属

C4 writer 负责：
- 去掉 `HouseholdFlowBook` 的 mode/currentPosition 依赖；
- 新建 population-only 执行变体；
- 目标壳无 mode/position/standing；
- 保留 trigger/hysteresis/1%/source 保留 1 人；
- 保留 flow table 持久形状和 baseline；
- 不改 C3 LAND upward、不改 C5–C8。

### 2.3 最终能力

1. 只有债务本金增量触发向下人口流；
2. 目标同 hex、同 culture 优先、无 mode 要求；
3. target 不存在可建 0 人口 PROLETARIAN 壳；
4. 执行只迁人口，source 钱/货/债/LAND/非 LAND 资产/经验不减少；
5. source 劳动承诺按迁出比例缩减；
6. target 不承接 source 资产/债/承诺；
7. 每市场轮最多一次，source 至少 1 人；
8. `lastObservedDebtPrincipalMilli` 仅基线/观察；
9. 所有排序确定性、同一 revision 原子；
10. compile 绿。

### 2.4 不可破坏机制

- Social 人口唯一权威；经济只追加 `EconomyPopulationTransfer` outbox。
- LAND 只走 LandBook；C4 不碰。
- 账户/库存/债务/经验/C6 批次不在本执行器。
- 不持久化 class；source OWNER/target PROLETARIAN 由当刻资产派生。
- 不改 flow table 持久形状；不改 C2 merge key/outbox Optional。
- 不写/跑测试、不改 pom/tools/world/docs（账本除外）、不碰主树。

### 2.5 可动范围

见 §6；只改 main 代码到 compile 绿。

### 2.6 交账

写 `.superpowers/sdd/2026-10-21-d032-carrier-c4/impl-ledger.md`，报告编译、改动文件、
population-only 方法形状、no-mode target shell、数值影响、测试失效面、未完成/blocker。

---

## 3. 冻结契约

### 3.1 Flow trigger/baseline

保持现有：

```text
firstObservation = previous == null
currentDebt = debtor principal > 0 合计
baseline = previous == null ? currentDebt : previous.lastObservedDebtPrincipalMilli()
threshold = max(1, baseline / 100)
trigger = !firstObservation
          && currentDebt > baseline
          && currentDebt - baseline >= threshold
          && day > lastFlowDay
flowPopulation = min(floor(P*10/1000), P-1)
```

- 债务下降/轻微反弹不改基线；
- 触发后基线推进至 currentDebt；
- 每市场轮一次由调用位置保证，不新增日级触发。

### 3.2 Target 选择

```text
同 hex
排除 source
当刻 OwnershipClass == PROLETARIAN
candidate population = projectedTargetPopulation.getOrDefault(candidate,row.population)
candidate population > 0
同 cultureKey 优先（人口→LAND→id）
无同 culture ⇒ 最小 HouseholdId + INFO mismatch
无正人口 target ⇒ 创建 0 人口 PROLETARIAN 壳
```

- 不读 productionMode/currentPosition/classStanding；
- target shell 无 mode/position/standing；
- target shell ID 确定性；空账户；`targetIsNew=true` 仅首次迁入时进 outbox；
- 无 mode 用 `Optional.empty()`，不写 magic mode。

### 3.3 Population-only 执行器

必须新增/冻结一个只迁人口的 primitive（名称由实现定，语义冻结）：

```text
applyPopulationOnly(
  session, accounts, day, ordinal,
  source, target, population, targetHex,
  targetIsNew, reason,
  sourcePopulationBefore, targetPopulationBefore
)
```

执行只允许：

- 校验 source/target/人口/递减后的 sourcePopulationBefore；
- 缩减 source 的 **PRODUCTION** labor commitment（按迁出比例；非 PRODUCTION 不动）；
- 追加 `EconomyPopulationTransfer(source,target,population,targetHex,
  Optional.empty(),targetIsNew,reason)`；
- 不移动 money/goods/debt/LAND/non-LAND assets/experience；
- 不创建 target 库存/债/承诺；
- 不直接写 `HouseholdEconomy.population/laborMilli`。

**不得调用**完整 `HouseholdTransferSettlement.apply`（它会搬钱货债经验）。
可以复用其私有 shrink 逻辑，但必须把“population-only”做成唯一入口，避免复制算法。

### 3.4 Target shell

```text
household = deterministic id
population=0, laborMilli=0, participation=0, money=0
empty goods/money/account
empty naturalNeeds/demand
无 classStanding/currentPosition/productionMode
LAND=0
```

- 不允许伪造 mode/position 或复用 source 的 position；
- target class 由零资产派生为 PROLETARIAN；
- 无迁移时不发 Social CREATE；首次有人口迁入时 `targetIsNew=true` 让 Social 创建；
- target shell 只能通过 `accounts.registerHousehold` 建空账户。

### 3.5 Source / target 语义

- source 保留原 LAND/马匹/assetShares/库存/债/经验；
- source population 只由 Social outbox 回流；经济行当刻人口不改；
- target population 用 projected map 供同轮后续候选/日志；
- source 劳动承诺缩减后可为 0，但 source 人口至少保留 1；
- source 不因 flow 自动变 PROLETARIAN；只有资产实际归零才派生 PROLETARIAN；
- target 仍是 PROLETARIAN，领取人口后仍无资产。

### 3.6 Prune

- `pruneStaleTables` 继续：
  - source 行/population>0/OWNER/hex 匹配；
  - target 行/population>0/PROLETARIAN/hex 匹配；
- 不因 target 无 mode/standing 清表；
- prune 只改 flow table，不改人口/账户/资产。

---

## 4. 不变量与失败语义

1. `HouseholdFlowBook` 不读 mode/currentPosition。
2. target 只按 PROLETARIAN + 同 hex + culture；无 mode 门。
3. 只迁人口；source 的 money/goods/debt/LAND/assets/experience 逐值不变。
4. source labor commitment 按比例缩减；target 无新 commitment。
5. `lastObservedDebtPrincipalMilli` 只作 baseline。
6. target shell 无 mode/position/standing，空账户，确定 ID。
7. 每市场轮 source 最多一次 flow；source 至少 1 人。
8. 失败整次 advance 丢弃，不半提交。
9. 不写 Social；只 outbox。
10. 所有排序确定性。

失败：

- target mode/currentPosition 仍被读取 ⇒ 红；
- 钱/货/债/LAND/资产/经验被搬 ⇒ 红；
- target 承接 source 库存/债/承诺 ⇒ 红；
- target shell 伪造 mode/position ⇒ 红；
- source 被搬空或 sourcePopulationBefore 未递减 ⇒ 红；
- 基线被无条件刷新/触发阈值丢失 ⇒ 红；
- 无正人口 target 却不建壳而静默跳过 ⇒ 红。

---

## 5. 验收锚点（供 test owner，不在本任务运行）

- A-C4-01：债务增量达阈值 ⇒ 同 hex PROLETARIAN target 收到人口。
- A-C4-02：同 culture target 优先；无同 culture 时确定性 fallback。
- A-C4-03：target 无 mode/currentPosition 仍可接收人口。
- A-C4-04：target 不存在 ⇒ 0 人口 PROLETARIAN 壳；首次迁入 targetIsNew=true。
- A-C4-05：source 钱/货/债/LAND/资产/经验逐值不变。
- A-C4-06：source 劳动承诺按比例缩减；target 无新承诺。
- A-C4-07：target 不收到 source 库存/债。
- A-C4-08：source 保留 1 人；每市场轮最多一次。
- A-C4-09：baseline/cooldown/hysteresis 与旧触发算式一致。
- A-C4-10：source 仍 OWNER；target 仍 PROLETARIAN。
- A-C4-11：prune 不因无 mode/standing 清合法 target。
- A-C4-12：失败无半状态。

负例：

- 走完整 `HouseholdTransferSettlement.apply` 搬钱货债经验 ⇒ 红；
- target mode 不匹配就跳过 ⇒ 红；
- 无正人口 target 静默丢流 ⇒ 红；
- target shell 写 magic mode/position ⇒ 红；
- source 资产随人走 ⇒ 红；
- baseline 无条件刷新 ⇒ 红；
- source 被搬空 ⇒ 红。

---

## 6. 文件所有权

### 允许写

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/**
simos-economy/src/main/java/io/mosire/simos/economy/**
simos-production/src/main/java/io/mosire/simos/production/contract/**
simos-production/src/main/java/io/mosire/simos/production/spi/**
simos-app/src/main/java/io/mosire/simos/app/**
```

### 禁止写

```text
**/src/test/**
**/pom.xml
tools/**
test-world/**
主树 /home/cna/SimulatorMosire
docs/**（除 `.superpowers` 账本）
```

- 前序约 293 项未提交改动；只改本责任区 main 文件，不回滚/格式化。
- 不改 C3 LAND primitive、C5 马匹/批次、C6/C7 价格锚/运输需求。

---

## 7. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
```

只到 compile 绿；不跑 test / test-compile / verify / gate / 360；不 commit/push。

报告：改动文件、编译 rc/输出、population-only 方法形状、target shell 规则、数值影响、
既有测试失效清单、C6 批次/后续接口、未完成/blocker。

---

## 8. 非目标

- 不实现 C3 向上 LAND primitive 改造（已完成）。
- 不实现 C5 马匹/耐久资本。
- 不实现 C6 运输批次/价格锚。
- 不实现 C7 运输需求/lane。
- 不实现 C8 混合买入。
- 不写测试/不跑测试/不 commit/push/不碰主树。
