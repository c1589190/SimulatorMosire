# D-032 RF6b 任务书：清除 land 新代码的无效 SpotBugs 抑制

> **父计划：** `docs/superpowers/plans/2026-10-20-d032-rural-360-fix-plan.md` §3 F6
> **状态：** 可派实现；只清静态检查中的无效注解，不改业务语义。
> **工作树：** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`

## 1. 现场（真实门禁输出）

`tools/gate.sh full` 在 `simos-economy` 的 SpotBugs 阶段失败，`BugInstance size is 3`：

```text
Medium: Suppressing annotation EI_EXPOSE_REP on io.mosire.simos.economy.land.LandLedgerState.holdingsByKey()
  is unnecessary [LandLedgerState] at LandLedgerState.java:[line 126] US_USELESS_SUPPRESSION_ON_METHOD

Medium: Suppressing annotation EI_EXPOSE_REP on io.mosire.simos.economy.land.LandProductionProjection$Projection
  is unnecessary [LandProductionProjection$Projection] at LandProductionProjection.java:[lines 66-77]
  US_USELESS_SUPPRESSION_ON_CLASS

Medium: Suppressing annotation EI_EXPOSE_REP on io.mosire.simos.economy.land.LandStateMerge$Merged
  is unnecessary [LandStateMerge$Merged] at LandStateMerge.java:[lines 32-43]
  US_USELESS_SUPPRESSION_ON_CLASS
```

根因：这些位置已经返回构造期冻结的不可变视图，SpotBugs 认为 `EI_EXPOSE_REP` 抑制已无效；
保留无效抑制会被 `US_USELESS_SUPPRESSION_ON_METHOD/CLASS` 判为缺陷。

## 2. 目标

- 清除上述 3 处**无效**的 `@SuppressFBWarnings(value = "EI_EXPOSE_REP", ...)`；
- 同步删除仅因这些注解而存在的 import（若该文件别处不再使用）；
- 不改任何返回类型、构造器冻结逻辑、记录字段、调用方或数值行为；
- 不新增其他 `@SuppressFBWarnings`，不引入 SpotBugs 排除配置；
- 若发现另有真实 `EI_EXPOSE_REP` 或新的 SpotBugs 缺陷，只报告，不扩大修改。

## 3. 允许文件

- `simos-economy/src/main/java/io/mosire/simos/economy/land/LandLedgerState.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/land/LandProductionProjection.java`
- `simos-economy/src/main/java/io/mosire/simos/economy/land/LandStateMerge.java`

仅编译必需时可动同目录最小文件（先报告）。禁止：测试文件、其他业务逻辑、POM、配置、主树、GUI/服务。

## 4. 目标运行序列位置

本责任区**不改变** v2 `S0 Social → S1 载入 → S2 进入/迁移/组织 → S3 位置选择 → S4 生产日结 → S5 收获 → S6 工资 → S7 市场 → S8 Social 事实` 的任何阶段顺序、读写或所有权；只删除三类只读 land 视图上的无效静态分析抑制注解。对父序列影响：顺序无、预算/冻结无、身份/持久化无、失败/回滚无、日志无、数值无；仅 SpotBugs 门禁从红转绿。

## 5. 编译与交账

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
```

只写生产代码到编译过；**不写/不跑测试**，不跑 `tools/gate.sh`，不 commit/push。

交账必须写：

- 改动文件与每处删除的注解；
- 真实编译 rc；
- 数值行为变化清单（预期为空）；
- 未验证/blocker；
- 实现账本 `.superpowers/sdd/2026-10-20-d032-rf6b-spotbugs-suppression-cleanup/impl-ledger.md`。

## 6. 验收锚点

- 三个文件不再含上述 3 处 `@SuppressFBWarnings`；
- 不改 `LandHolding`/`AssetShareId`/`LandArableSupply` 等数据形状；
- `tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am` 真实 rc=0；
- 控制方随后重跑 `tools/gate.sh full`。

## 7. 用户原话

来源：当前修复批次用户裁定（2026-10-20）。

```text
需求被允许挂价了吗？家户提的需求，面对市场不管多贵都会直接接受的；其他没啥问题，记录一下你的修复方案，开始吧
```
