# SDSimos — A1 + A2 执行报告

> 范围：计划 `docs/superpowers/plans/2026-09-20-sd-simos-plan.md` 的 **§三 A1（模块骨架与 enforcer）** 与
> **§三 A2（数据模型）**，按 **§〇 通则** 执行。
> 分支：`sd/a1a2`（worktree `.claude/worktrees/sda1a2`）→ 快进合并入 `feat/adr1-core-scope`（主树）。
> 基线：`4c0469767571051eb9945af7dce9a364ef88fb20`。
> 提交：**A1 = `02b4253`**、**A2 = `8454691`**（均为 worktree 内提交，已 ff 合并入主树）。

---

## 一 A1 —— 模块骨架与 enforcer

### 1.1 落地物

| 项 | 值 |
|---|---|
| 新模块 | `simos-sd`（`artifactId=simos-sd`，`name=SDSimos`，根包 `io.mosire.simos.sd`） |
| reactor | root `pom.xml` 在 `simos-core` 后、`simos-app` 前插入 `<module>simos-sd</module>`；`dependencyManagement` 登记 |
| sd main 依赖 | `simos-util` / `simos-map` / `simos-social` / `simos-unit` / **`agentlib-mosire`**（R5）/ `jackson-databind` |
| sd enforcer | `bannedDependencies.excludes` = **恰 `{io.mosire:simos-core, io.mosire:simos-app}`**；**不含** `agentlib-mosire` |
| core enforcer | main `excludes` 新增 `io.mosire:simos-sd`（R2：Core 编译期看不见 sd）；v1 不为 sd 开 test-scope `includes` |
| app | `simos-app` 加 `simos-sd` 依赖（无版本，父 POM 管） |
| package-info | 中文 Javadoc 写明铁律 3/4 边界 |

### 1.2 判据实测值

| 判据 | 实测值 |
|---|---|
| `./mvnw -pl simos-app -am -DskipTests compile` | **rc=0**，reactor 含 SDSimos（日志 `a1-evidence/compile.log`） |
| reactor 模块数 | **8/8** = 父 + 7 模块（`a1a2-evidence/clean-verify.log` 的 Reactor Summary） |
| sd `excludes` | grep 实测 `excludes` 恰两条、且**无** `agentlib-mosire` |
| core `excludes` | grep 实测含 `io.mosire:simos-sd` |

### 1.3 变异轮（2 个全杀，构建期门禁）

| 变异体 | 做法 | 命令 | 实测红点 |
|---|---|---|---|
| **m1** | 往 `simos-core/pom.xml` main `<dependencies>` 插 `io.mosire:simos-sd` | `./mvnw -pl simos-core,simos-sd validate` | `rc=1`；`BannedDependencies failed`；`io.mosire:simos-sd ... <--- banned via the exclude/include list` |
| **m2**（取代写法） | **保留** sd 的 core exclude，只给 sd 加 core 依赖 | 同上 | `rc=1`；`BannedDependencies failed`；`io.mosire:simos-core ... <--- banned` |

- 两轮：`COMPILATION ERROR=0`；`cp` 逐字节还原后 `restored_md5 == orig_md5`；干净树对照 `BannedDependencies passed=2`（接收侧真样本）。
- 装置与日志自指：`a1-evidence/enforcer-effective.log`、`a1-evidence/mutants/mut-enforcer.sh`。

---

## 二 A2 —— 数据模型

### 2.1 落地物

- **ID（11 个，`io.mosire.simos.sd.id`）**：`NationId`/`ArmyId`/`CombatId`/`CombatStageId`/`CombatStateId`/
  `CombatOutcomeId`/`DecisionMakerId`/`DirectiveId`/`EffectId`/`VerdictId`/`LossRecordId`。
  统一形状：**裸值 `toString()` + `static parse` + 空白/null 即抛**；不自增、不用随机 UUID。
- **model（`io.mosire.simos.sd.model`）**：`Nation`/`Army`/`Affiliation`(sealed)/`DecisionMaker`/`ViewScope`/
  `DisclosurePolicy`/`Combat`/`CombatState`/`CombatStage`/`OutcomeTable`/`OutcomeOption`/`CasualtySpec`/
  `CasualtyDelta`/`LossClass`/`LossRecord`/`Directive`/`DirectiveCommand`/`DirectiveStatus`/`Verdict`/
  `VerdictMeta`/`AdjudicationBreakpoint`/`SdInfoEntry`/`Effect`/`EffectKind`/`EffectStatus`/`Trigger`(sealed)/
  `Action`(sealed)。
- **冻结纪律**：集合一律 `LinkedHashMap`/`LinkedHashSet` + `Collections.unmodifiable*`（**写在赋值处**），List 用
  `List.copyOf`；**禁用 `Map.copyOf`/`Set.copyOf`**。
- **sealed 多态**：`Affiliation`/`Trigger`/`Action` 预置 `@JsonTypeInfo(Id.NAME)` + `@JsonSubTypes`（见取代说明 R-d）。

### 2.2 判据实测值

| 判据 | 实测值 |
|---|---|
| 定向测试 `SdIdTest` + `SdModelTest` | **13 条全绿**（2 + 11），`a2-evidence/clean-targeted-test.log` |
| 全部 ID `parse(x.toString()).equals(x)` | `SdIdTest.everyIdToStringIsTheBareValueAndParseRoundTrips` 逐值绿 |
| `OutcomeOption(weight=0)` 构造抛 | `hasMessage("weight 必须 > 0: 0")` |
| `CasualtyDelta(personnel=+1)` 构造抛 | `hasMessage("personnel 必须 ≤ 0: 1")` |
| 集合字段不可变 | `SdModelTest` 对 `OutcomeTable.options`/`Combat.participants`/`DecisionMaker.allowedTools`/`ViewScope.visibleUnits`/`Directive.effects`/`CombatState.participants`+`losses`/`CasualtyDelta.equipment` 逐处断言 `UnsupportedOperationException` |
| `./mvnw -pl simos-sd -am verify`（合并前） | **rc=0**、sd `BugInstance size is 0`（`a2-evidence/pre-merge-verify.log`） |

### 2.3 变异轮（3 个全杀）

| 变异体 | 做法 | 红点（被保护断言） |
|---|---|---|
| **m1** | `OutcomeOption` 删 `weight > 0` 校验 | `outcomeOptionRejectsNonPositiveWeight` |
| **m2** | `OutcomeTable` 删空表校验 | `outcomeTableRejectsEmptyOptions` |
| **m3** | `CasualtyDelta` 删装备负值校验 | `casualtyDeltaRejectsNonNegativeEquipment` |

- 三轮：`COMPILATION ERROR=0`、`Tests run≥1`、surefire 报告 mtime **落本轮**（`report_fresh=yes`）、`cp` 逐字节还原
  相等、日志自指（聚合 md5 `97e27b04e46fd33169692de4de0ccaf5`）。见 `a2-evidence/model-mutants.log`、
  `a2-evidence/mutants/mut-model.sh`。

---

## 三 ★ 执行期取代说明（未改 spec/计划正文）

| # | 项 | 事实 | 处置 |
|---|---|---|---|
| **R-a** | 计划 §三 A1 的 **m2 逻辑缺陷** | 「删 sd 的 core exclude 并加 core 依赖」**不可能触发 enforcer**——删 exclude 即解除禁令（实测 plan-as-written 轮 `rc=0`） | 取代为**保留 exclude、只加 core 依赖**；as-written 轮日志留档 `a1-evidence/mutants/m2-plan-as-written/` |
| **R-b** | 装置检测正则假阴性 | 首轮 m1 用**小写** `bannedDependencies` 匹 `BannedDependencies` ⇒ 假判"未触发" | 改 `-i` 精确匹配；已记台账（"命中 0 先怀疑正则"） |
| **R-c** | spec §二.1 ID 表**漏列 `CombatStateId`** | §三.1 `SdState` 与 §三.3 `CombatState` 都用到它，计划 A3 的键清单也含它 | 以 §三 为准，补齐为**第 11 个 ID** |
| **R-d** | `Condition`/`Trigger`、`AtTick`/`AtOrAfterTick` 二名并存 | spec §三.3 写 `Condition`/`AtOrAfterTick`；计划 A2 写 `Trigger`/`AtTick` | 取 `Trigger`（与 §三.6 一致）+ `AtOrAfterTick`（与 §十一.5 判据一致） |
| **R-e** | spec 未定义形状的三个类型 | `CasualtySpec` / `AdjudicationBreakpoint` / `DisclosurePolicy`·`DirectiveStatus` | 实现期定形：CasualtySpec=非负幅度+冻结 map；AdjudicationBreakpoint=稳定标识 record；后两者定档枚举。属 spec §十四 假设 5 |
| **R-f** | sealed 多态预置 Jackson 类型信息 | `Affiliation`/`Trigger`/`Action` 与 `FieldDelta` 同族，裸往返不可能 | 现在钉上 `@JsonTypeInfo(Id.NAME)`+`@JsonSubTypes`；A3 做往返时直接可用（计划 A2 未点名，前瞻性补齐） |
| **R-g** | 计划预期 **sd +12**，实测 **+13** | 多 1 条 = `SdIdTest` 的"空白/null 全类型拒绝"用例 | 以实测为准 |

---

## 四 门禁（合并后主树，`./mvnw clean verify`，前台）

```
rc=0
Tests run 逐模块：util 170 / map 362 / social 45 / unit 131 / core 169 / sd 13 / app 110  ⇒ 合计 1000
reactor：8/8 SUCCESS（父 + 7 模块）
BugInstance size is 0 ×7
[ERROR] 0 行
[frontend-gate] OK tests=88 pass=88 fail=0
```

- 相对基线 **987 → 1000**，增量 **+13** 恰等于 `simos-sd` 的两条新用例类（2 + 11）；其余六模块逐值不变。
- 证据：`a1a2-evidence/clean-verify.log` + `a1a2-evidence/verify-rc.txt`。
- ★ A1 的**独立**全量 verify 未单独跑：A1 结束时跑的是 `-pl simos-app -am -DskipTests compile`（判据）+ enforcer 变异轮；
  **8/8 的结构性结论由本次合并后全量 verify 覆盖**（A2 未改 A1 的装配面）。

---

## 五 我未能核实的

1. **A1 的独立全量门禁**未跑（只跑定向 compile + enforcer 变异）；8/8 由合并后全量 verify 覆盖——**不是** A1 单独关账轮的数字。
2. **A1/A2 只在 worktree 与主树（`/home/cna/SimulatorMosire`）跑过**；另一台机器 / 从模块目录起跑**未验**（CLAUDE.md 形态：护栏要在每种环境形态各证一次）。
3. `Affiliation`/`Trigger`/`Action` 的 **Jackson 往返未经实测**——注解已预置，但真正往返在 A3（`SdCodec`/`SdSnapshot`）才跑。
4. `CasualtySpec` / `AdjudicationBreakpoint` / `DisclosurePolicy` / `DirectiveStatus` 的**取值与形状是执行期定形**，spec 未定义 ⇒ 若用户/spec 后续裁定不同，需回改（已记台账 R-e）。
5. `SdState`/`SdChangeSet`（A3）**尚未落地** ⇒ spec §三.1 的 5 条构造期不变量（R4 唯一性 / 引用完整性 / 结局一致性 / 阶段链 / 损失上界）**未实现、未测**——A2 只落 record 形状与字段级不变量。
6. A2 的 `set` 冻结保护依赖**可变输入**（测试传 `LinkedHashSet`/`LinkedHashMap`）；若后续有人把 record 的集合组件改为 `Set.of`/`Map.of` 语义，判别力会下降——**当前测试已用可变输入**，故这一条不影响本轮结论。
7. 计划 §一.4 的门禁基线表（987/7-模块）与本次实测一致（除 sd 新增）；`BugInstance ×7` 只在本次类集下成立（CLAUDE.md 形态 6：分析器判定不是被分析文件的纯函数）。
