# RC8e 任务书：迁移后 enterprise.laborSources 与 roster 同步

> **状态：约束设计书/实现任务书；只写生产代码到 `-DskipTests compile` 通过。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父计划：`docs/superpowers/plans/2026-10-20-d032-v4-full-repair-plan.md` §4/§5。
> 依赖：RC8a/RC8b/RC8d 已 compile 绿。
> 目的：修掉当前 360 最后一天的 `enterprise-roster-mismatch`，让 360 不再因候选态校验失败而 reject。

---

## 1. 现象

RC8d 后 360：

```text
OK=359，REJECTED=1
day360:
第 360 天候选态校验失败（economy.production-references）:
economy.production-references 的组织 laborSources 与名册全户派生集不一致
(enterprise-roster-mismatch):
unit=unit-farm@1_3-HOUSEHOLD-hh-1_3-rural-landed_peasant
```

前置日志显示 day360 发生多次 `MODE_MIGRATION / PROFIT_WEIGHTED` 人口迁移，之后候选态校验失败。

---

## 2. 根因（file:line）

- `MigrationPopulationMove.java:343-361`：
  - 整户迁空时调用 `ProductionRosters.mergeHousehold(rosters, source, target)`；
  - roster 已把 source 家户替换/合并到 target；
- `MigrationPopulationMove.java:605-629`：
  - 旧逻辑只从 `ProductionEnterprise.laborSources` 中 `remove(source)`；
  - **没有把 target 加进去**，也没有把 `laborSources` 重新同步为 merged roster 的 households；
- `HouseholdReferenceValidation.java:294-304` 要求：
  ```text
  enterprise.laborSources() == roster.households()
  ```
  ⇒ 下一 revision 的候选态校验 fail-closed。

---

## 3. 目标语义

在一次整户迁空 / roster 合并后，凡是 `ProductionEnterprise.unitId()` 对应的 unit 存在 `ProductionRoster`：

```text
enterprise.laborSources = roster.households()
```

这必须与 `ProductionRosters.mergeHousehold` 的派生结果完全一致：

- source 被 target 替换；
- target 加入；
- 同 role 合并后的 households 集合、顺序稳定；
- 不改变 organizer / unitId / classPositionId / modeId / assetSources / inputSources / outputOwnership / status；
- 没有 roster 的 enterprise 保持旧逻辑（只 remove source，作为非名册旧路径的防御）。

---

## 4. 允许修改文件

```text
simos-economy/src/main/java/io/mosire/simos/economy/time/MigrationPopulationMove.java
```

必要时可只提取一个包内 helper；不改 `ProductionRosters`/`HouseholdReferenceValidation`/测试/seeder。

禁止：写/跑 test/test-compile/verify/package；commit/push；主树改动。

---

## 5. 实现步骤

1. 在 `MigrationPopulationMove` 中，确保 `session.sheet().productionRostersOrBase()` 可在 enterprise 同步循环中读取；
2. 对每个 `ProductionEnterprise`：
   - 若 `enterprise.unitId().isPresent()` 且 `productionRosters.get(unitId)` 存在：
     - `List<HouseholdId> derived = roster.households();`
     - 用 `derived` 重建 `ProductionEnterprise.laborSources`（保持其它字段逐值不变）；
   - 否则保留旧逻辑：仅当 `laborSources.contains(source)` 时 remove source；
3. 如果源户是 organizer 且其组织将在后面的退役分支删除，按既有顺序处理；本循环不要提前删组织；
4. 用 `LinkedHashSet`/稳定顺序，输出 `List.copyOf` 语义，不得引入 Map 迭代序；
5. 只改 `MigrationPopulationMove`，不改变迁移数量/资产/债务/人口公式。

---

## 6. 验收锚点

### 正例

- RC8d 后的 360：`OK=360, REJECTED=0`；
- 日志中 `enterprise-roster-mismatch` 为 0；
- `MODE_MIGRATION / PROFIT_WEIGHTED` 仍可发生，但迁移后候选态校验通过；
- `enterprise.laborSources` 与对应 roster households 逐户一致；
- 组织 organizer/unit/资产/输入/输出/状态未被改写。

### 负例

- 只 `remove(source)` 不补 target ⇒ 红；
- 用 `source→target` 手拼而不读 merged roster ⇒ 红；
- 无 roster 的旧 enterprise 被强行清空 laborSources ⇒ 红；
- 迁移后 roster 与 enterprise 仍不一致 ⇒ 红。

---

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 账本落 `.superpowers/sdd/2026-10-20-d032-rc8e-enterprise-roster-sync/impl-ledger.md`；
- 报告列：同步判据、改动文件、compile rc、未验证项。

---

## 8. 用户原话附录

```text
还有其他裁定吗，没有的话把修复计划落盘，然后开始修复，直到能重跑360tick
```

```text
理论上应该按V4架构完整实现
```
