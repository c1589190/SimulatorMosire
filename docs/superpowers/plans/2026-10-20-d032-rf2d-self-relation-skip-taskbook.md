# D-032 RF2d 任务书：跳过自转移关系规则

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md`
> **状态：** 可派实现；只写生产代码到编译过，不写/不跑测试。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`

## 1. 现场

RF2c 复跑在 day120 关账时触发：

```text
java.lang.IllegalArgumentException:
Transfer 的两端不得相等（自转移不是一条发生额，是坏数据）:
HOUSEHOLD:hh-0_0-urban-middle_peasant
at ProductionOrganizationSettlement.emitLegs(...)
at ProductionRunSettlement... routeRelationTransfer(...)
```

checkpoint 120 的 `relations` 显示：

```text
unit-craft@0_0-HOUSEHOLD-hh-0_0-urban-middle_peasant
  operator = hh-0_0-urban-middle_peasant
  rules 中包含 recipient = hh-0_0-urban-middle_peasant 的
  OUTPUT_SHARE 和 FIXED_MONEY_WAGE
```

即关系规则把付款方（operator）和受方配成了同一个家户。自支付不是经济发生额；当前代码在 `ProductionOrganizationSettlement.emitLegs`
生成 `Transfer` 时被 `Transfer` 的自转移守卫拒绝，导致整次 advance 失败。

## 2. 目标

- 关系结算遇到 **payee actor 与 relation.operator() 完全相同** 的规则时，跳过该规则，不生成 Transfer，不产生自债/自付。
- 其它规则（不同家户/主体）保持原顺序、原金额、原转账。
- 新增具名日志，便于审计和后续把错误的 craft 关系种子改掉。
- 这是防御性业务规则修复；不改变工资/债务/租佃/共享收入口径，也不把自支付解释成“已支付”。

## 3. 约束设计

- 位置：`ProductionSettlement.settle(...)` 的逐规则循环入口。
- 计算 `payeeOf(rule)`，与 `relation.operator()` 比较；相等 ⇒ 跳过该 rule。
- 跳过发生在任何支付量计算、`ledger.mint`、`owed/arrears` 累加之前。
- 日志事件：`RELATION_SELF_RULE_SKIPPED`（INFO 或 DEBUG，视现有日志纪律），字段至少：
  `activity`/`unit`（若可得）、`operator`、`payee`、`ruleType`、`priority`、`reason=self-payee-equals-operator`。
- 不改变 `ProductionSettlement.Outcome` 的字段形状；自规则不进入 paid/owed/transfers 读数。
- 不在 `ProductionOrganizationSettlement.emitLegs` 里静默丢自腿；如果在结算入口已过滤，`emitLegs` 不应再碰到此 case。
- 可以增加一个窄的防御断言/具名异常在 `emitLegs`（若仍收到自腿，报出 unit/rule 上下文），但不得把自腿当成正常转账。

## 4. 允许文件

- `simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionSettlement.java`
- 仅在编译必需时：同目录最小相邻文件（必须先报告）

禁止：测试、EconomyData/ChangeSet/Codec、production 定义/app seeder、主树、GUI/服务。

## 5. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

真实 rc=0。报告：改动方法、日志字段、数值行为变化、受影响硬编码/既有断言、未完成/未验证；
账本 `.superpowers/sdd/2026-10-20-d032-rf2d-self-relation-skip/impl-ledger.md`（gitignored）。

## 6. 验收锚点（测试 owner 后补）

- `relation.operator()` 与 `payeeOf(rule)` 相同 ⇒ 不产生 Transfer、不产生自债、Outcome 不记该规则支付；
- 不同家户规则仍按原顺序/金额生成；
- day120 craft relation 不再触发 `Transfer 的两端不得相等`；
- 360 tick 的 `status` 不再出现该 `ADVANCE_EXCEPTION`。

## 7. 用户原话

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```
