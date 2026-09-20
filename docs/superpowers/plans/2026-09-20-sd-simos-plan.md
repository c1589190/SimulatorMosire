# SDSimos 实现计划 —— 国家·交战·决策（bite-sized）

> 配套 spec：`docs/superpowers/specs/2026-09-20-sd-simos-design.md`（**§〇~§十四，已无待裁项**；用户 2026-09-20「开始吧」＝采纳 §〇.3 全部建议）。
> 姊妹设计：`docs/superpowers/specs/2026-09-20-unit-extension-design.md`（★ **本计划只引用它的命令名与边界，不重复实现**：`unit.PlanSparseRoute` / `unit.ApplyCasualties` 见其 **§五.2**）。
> 姊妹计划：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md`（★ **计划期尚未落盘**；**阶段 C 的前置 = 姊妹计划的 B 阶段完成**，见 §二）。
> 格式模板：`docs/superpowers/plans/2026-09-19-map-edit-simos-plan.md`。
> 台账：`.superpowers/sdd/2026-09-20-sd-simos/progress.md`（裁定、结论、"我未能核实的"）。
> ★ **执行期纪律**：本计划的代码草图是**计划期产物**，与 `src` 分歧处以**源码为准**，分歧记入台账。
> ★ 本文**只写计划**，不含实现代码；**不跑 Maven**。

---

## 〇 通则（每个任务都适用）

- **派单**：用**默认子代理类型**（Claude Code 里省略 `subagent_type`；opencode 外壳用 `deepseek-flash-go`，**禁用 `deepseek-flash` / `category=`**——见 `CLAUDE.md` 纪律段，**子代理类型按机器/外壳不同，照抄前先核**）。
- **worktree 隔离**：单任务 worktree；派发前 `git -C <worktree> reset --hard <基线>`（陈旧基线陷阱）。★ **`CLAUDE.md` 与台账都用 worktree 内的绝对路径改**，改完 `git -C <worktree> status` 确认变的是**本树**。
- **一次只跑一个 Maven**：本机 `nproc=2`，本地推理网关与 Maven 抢核；**agent 活着时不跑全量 verify**。`clean verify` 走**前台**（后台跑会被内存守卫杀；被杀轮留档不删、既不是红也不是绿）。
- **每任务流程**：`./mvnw -q spotless:apply` → 定向单测（`-Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`）→ **合并后主树 `./mvnw clean verify`**（关账前）。
- **护栏必须自证**：每个新护栏配**故意违规**用例 + **≥1 轮变异**；**九道门禁**照 `CLAUDE.md`：干净世界 / 变异体字节不同（md5 自证）/ 白名单推成目标类名 / 清陈旧 `.class` / 断言 `COMPILATION ERROR`=0 / surefire mtime 落轮内且读**本名轮那块** / 红点**落被保护断言** / `cp` 逐字节还原 / **日志自指**（把本轮 md5 追加进日志）。
- ★ **同一文件被两任务改 ⇒ 必须串行**，后关账者**重跑前者的变异轮**（旧证据的对象已被改掉）。本计划的同文件清单见 §二.3。
- **不 `git add -A`**；`git add` **显式路径**；`.superpowers/sdd/**` 本机实测被 `.superpowers/sdd/.gitignore` 忽略（`git check-ignore -v` 实测命中）⇒ 要入库用 `git add -f`（★ **换机器先 `git check-ignore -v` 再决定**）。
- **不加 `Co-Authored-By`**；**该推就推**（私有仓库）。
- **证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/tN-evidence/`（**不许放仓根**）。

---

## 一 目标与总判据

### 一.1 目标

让 Agents / MCP / 玩家经**同一条** `Command → ChangeSet → Revision` 路径，在一个**可生长的模拟引擎**里做**国家决策与交战判决**：

- 新模块 **`simos-sd`**（国家 / 军队归属 / 交战 / 决策人 / 决策 / 效果 / 判决 / 损失记录 / sd 侧 INFO）；
- **Core 编译期看不见 sd**（R2 / ADR-1 / 铁律 4）；
- **判决是数据、随时可回放**（N7）：回放/分岔**绝不重跑 LLM**；
- **AI 只"在合法选项里选一个 + 写理由"**（spec §八.4），四类客观约束**由代码判**。

### 一.2 总判据（引用 spec §十一 的 15 条，不复制正文）

1. 模块边界自证（sd 可依赖 agentlib + 四模块；**core main 不得依赖 sd**）。
2. R4 单指令（同 dm 同 tick 第二条 `sd.IssueDirective` ⇒ 拒绝、`revisions` 行数不变）。
3. N7 判决冻结（含判决的推进后 `Replay` ⇒ `SdState` 逐字段相同、**`FakeLlmClient` 调用计数 = 0**）。
4. N8 `verdictMeta` 三字段非空。
5. N1 条件驱动（`AtOrAfterTick` 与 `ThresholdKills` 各实测；**上一 exit == 下一 entry** 不满足时命令拒绝）。
6. N2 categorical（权重归一、**恰选一个**；同一 revision 两次读出**逐字节相同**）。
7. N3 双轨 + 上界（Δ 逐项为负且 |Δ| ≤ 当前；越界拒绝；`LossRecord` 可回放）。
8. R6 延期效果（条件未达成**不**产生；达成后**只产生一次**）。
9. 变更集往返（`SdChangeSet` 全组件含 `info` 绿；`ArchitectureGuardsTest` 计数 = 5）。
10. R10/N6 可见性（两个不同 `ViewScope` 的 dm 调同一读端点 ⇒ 返回**不同**数据；`sd.SetViewScope` 写入 revision）。
11. N9 窄工具（GM 与决策 Agent 工具有效集里**都没有** `simos.command.submit`；有 `sd.*` 窄工具）。
12. N12 配额同事务（并发两条同 tick 指令 ⇒ 恰一条成功、无半写 revision）。
13. §九 守卫（带 Nation tag 的 Region `map.DeleteRegion` ⇒ 拒；**去掉 tag ⇒ 放行**）。
14. N13 降级（`FakeLlmClient` 抛超时 / 非法 JSON ⇒ 本 tick 明确降级、不卡死、后续 tick 继续）。
15. §五.3 drain（跨模块效果经 `SdCommandDrain` 落成真 revision；drain 幂等、重放不重复提交）。

### 一.3 ★ 如何判定"判决 / LLM 相关"任务（N14）

**判据只断言结构 + 约束 + 冻结 + 可回放，绝不断言 LLM 的具体选择。** 具体四条：

1. **结构**：`Judgement` 三态（`Accepted` / `Abstained` / `Failed`）、`AdjudicationRequest` 的四个字符串分量非空、`Verdict.payloadJson` 能被模块内 schema 读回；
2. **约束**：非法输出（越界 outcome / 白名单外命令 / 空 `verdictMeta`）⇒ handler 拒绝、**不产生 revision**；
3. **冻结**：`Verdict` 落进 revision 后，`Replay` 逐字段相等且 `FakeLlmClient` 调用计数 **不增**；
4. **可回放**：同一坐标两次 `Replay` **逐字节相同**；分岔阅读不触发任何 LLM 调用。

★ **测试输入全部用 `FakeLlmClient`（返回固定字节或抛指定异常）**——判据**完全离线**，不联网、不依赖真实 key（N10 / spec §八.5）。

### 一.4 ★ 门禁基线（2026-09-20，M10 关账终态）

| 项 | 基线值 |
|---|---|
| 主树 `./mvnw clean verify` | **rc=0** |
| 用例总数 | **987** = `util 170 / map 362 / social 45 / unit 131 / core 169 / app 110` |
| 模块数 | **7/7**（父 POM + 6 模块）；新增 `simos-sd` ⇒ **8/8**（父 + 7 模块） |
| SpotBugs | `BugInstance size is 0` **×6**；新增 `simos-sd` ⇒ **×7** |
| 日志 | `[ERROR]` **0 行** |
| 前端门禁 | `tests=88 pass=88 fail=0`（`exec-maven-plugin` 独立跑，**不并入** surefire 合计） |

★ **每个任务的"预期门禁数字"以本表为基准推导**（见 §三各任务 + §附推演表）。**实现期以实现期实测为准**；关账用**实测值**，不用本计划的推演值。

---

## 二 任务总表（依赖与串并行）

### 二.1 分期与依赖

```
A 骨架+Nation（Block：C/D 全部）        C Combat（前置：B）            D Directive+判决+渠道（前置：C）
A1→A2→A3→A4→A5→A6                      C1→C2→C3→C4→C5→C6             D1→D2→D3→D4→D5→D6→D7
                  │                              │                              │
                  └──────────→ C ◄───────────────┘                              │
                                                                                 E1 ← 全部
```

- **阶段 B 在姊妹计划里**（`docs/superpowers/plans/2026-09-20-unit-extension-plan.md`，**计划期未落盘**）：编制两级 / 三态 / 战损增量 / 稀疏路点。★ **C 的硬前置 = B 完成**（否则 C3 的战损上界读不到单位绝对值、C4/C5 的 drain 发不出 `unit.ApplyCasualties`）。若 B 未落盘，**C 不得开工**，先把 B 落盘。
- ★ 本计划**不重复** B 的任何内容（编制树 / `command_chain` / `Formation` / 三态 / 拆合执行 / 战损命令本体），只按 `unit-extension-design.md §五.2` 的命令名**引用**。

### 二.2 任务总表

| 任务 | 名称 | 依赖 | 阶段 | 主要模块 | 预期用例增量（推演） |
|---|---|---|---|---|---|
| A1 | `simos-sd` 骨架 + enforcer | — | A | pom/reactor | 0（新增 8/8 模块） |
| A2 | 数据模型（ID 三件套 + spec §三 全部 record） | A1 | A | sd | sd +12 |
| A3 | `SdState` / `SdChangeSet` / `SdSnapshot` / `SdCodec` + 往返 + 计数 5 | A2 | A | sd、app、core | sd +8 |
| A4 | Nation 命令族 + `SdResolver`（`sd.CreateNation` / `sd.CreateArmy` / `sd.CreateDecisionMaker`） | A3 | A | sd、app | sd +10 |
| A5 | R14 INFO 写路径（`SdInfoEntry` + `sd.PutInfo`） | A4 | A | sd、app | sd +6 |
| A6 | ★ 不可删守卫（`MutationGuard` + `RegionDeleteGuard`） | A4 | A | util、core、sd、app | core +5、sd +6、app +3 |
| C1 | Combat 场/阶段/结局命令（N1+N2 数据层） | A6 + **B** | C | sd、app | sd +10 |
| C2 | `sd.CommitCombatOutcome`（N2 恰一个） | C1 | C | sd、app | sd +5 |
| C3 | `sd.RecordCasualties`（N3 双轨 + 上界） | C1 + **B** | C | sd、app | sd +7 |
| C4 | Effect 命令 + ★ `SdTimeParticipant`（R6 延期 + N1 阶段推进） | C3 | C | sd、app | sd +10、app +2 |
| C5 | ★ `SdCommandDrain`（跨模块效果，app） | C4 + **B** | C | app | app +6 |
| C6 | Combat 端到端判据（条件驱动 + 冻结） | C5 | C | app | app +4 |
| D1 | Directive + R4 + `sd.IssueDirective` | C6 | D | sd、app | sd +10 |
| D2 | ★ `DecisionAdjudicator` SPI + `LlmClient` + `FakeLlmClient` + 8 断点 schema + N13 降级 | D1 | D | sd | sd +8 |
| D3 | ★ 判决冻结 + `sd.SubmitVerdict` + `VerdictMeta`（N7/N8） | D2 | D | sd、app | sd +6、app +4 |
| D4 | 可见性 + GM 配权（`ViewScope` / `sd.SetViewScope` / `RedactingQueryService`） | D3 | D | sd、app | sd +6、app +8 |
| D5 | ★ 决策提交渠道（`DecisionChannel` + N16/N17/N18 + app 适配） | D4 | D | sd、app | sd +6、app +6 |
| D6 | ★ 专用窄工具（N9：`sd.SubmitVerdict` / `sd.IssueDirective` / `sd.SetViewScope`）+ 工具面分载 | D5 | D | app | app +8 |
| D7 | 8 个 AI 断点落点 + `AdjudicatorRunner`（app） | D6 | D | app | app +5 |
| E1 | 判据逐条 + R 点验 + 关账 | 全部 | E | — | 0 |

### 二.3 ★ 同文件串行清单（后关账者重跑前者变异轮）

| 文件 | 被哪些任务改 | 串行结论 |
|---|---|---|
| `simos-app/src/main/java/io/mosire/simos/app/Shell.java` | **A3/A4/A5/A6/C1/C3/C4/C5/D1/D2/D3/D4/D5/D6/D7** | **几乎全部任务** ⇒ 装配类任务**一律串行**。★ **建议**：把 sd 的装配收进 **app 层单一助手 `io.mosire.simos.app.sd.SdWiring`**（`Shell` 只调 `SdWiring.install(...)`），后续任务改 `SdWiring.java`；即便如此 `SdWiring.java` 仍被多任务改 ⇒ **仍串行** |
| `simos-core/src/test/java/io/mosire/simos/core/ArchitectureGuardsTest.java` | A3（4→5） | 仅 A3；后续若再有人加第 6 个 `ChangeSet` 实现者，**必须**同步 |
| `simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java` | A6（guard 调用点） | 仅 A6 |
| `simos-core/src/main/java/io/mosire/simos/core/CoreSimos.java` | A6（guard 注册） | 仅 A6 |
| `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java` | D4/D6 | D4→D6 串行 |
| `pom.xml`（root） | A1 | 仅 A1 |
| `simos-core/pom.xml` | A1 | 仅 A1 |
| `simos-app/pom.xml` | A1 | 仅 A1 |

★ **跨阶段并行**：A 与 C 不能并行（C 依赖 A）；C 内部近全串行（同改 `Shell.java`/`SdWiring.java`）。**唯一可安全并行的是"文件不相交"的测试类编写**，但本机**一次只跑一个 Maven**，故**实际执行仍串行**。

---

## 三 任务明细

> 每个任务写全：**目标 / 依赖 / 文件清单 / bite-sized 步骤 / 判据 / 变异思路 / 证据落点 / 提交信息样式**。
> ★ 所有"预期门禁"为**计划期推演**（§附），实现期以实现期实测为准。

---

## 阶段 A —— 骨架 + Nation（含不可删守卫）

### A1 模块骨架与 enforcer（Block：全部下游）

**目标**：新建 `simos-sd` 并接线进 reactor；**Core 编译期看不见 sd**；app 可依赖 sd。**本任务不写领域逻辑**（先只放 `package-info.java`）。

**依赖**：无。

**预期门禁**：总用例 **987 不变**；模块 **8/8**；`BugInstance size is 0` **×7**；`[ERROR]` 0；前端 **88/88**。

**文件清单**
- 新建 `simos-sd/pom.xml`（`artifactId=simos-sd`、`name=SDSimos`、父 POM `io.mosire:simos-parent`；main 依赖：`simos-util` / `simos-map` / `simos-social` / `simos-unit` / `agentlib-mosire`（R5） / `jackson-databind`；★ enforcer `bannedDependencies` **只禁 `io.mosire:simos-core` 与 `io.mosire:simos-app`**，**不得**把 `agentlib-mosire` 写进 `excludes`——它正是 R5 要显式依赖的）。
- 新建 `simos-sd/src/main/java/io/mosire/simos/sd/package-info.java`。
- 改 `pom.xml`（root）：`<modules>` 插入 `<module>simos-sd</module>`，置于 `simos-core` **之后**、`simos-app` **之前**；`dependencyManagement` 登记 `simos-sd`（若需要）。
- 改 `simos-core/pom.xml`：main-scope `bannedDependencies` 的 `excludes` 加 `io.mosire:simos-sd`（R2 的结构化：Core 编译期看不见 sd）；**v1 不预设** core test scope 的 `includes`（sd 的端到端测试在 app 层）。
- 改 `simos-app/pom.xml`：加 `simos-sd` 依赖（无版本，父 POM 管）。

**bite-sized 步骤**
1. 照 `simos-unit/pom.xml` 形制写 `simos-sd/pom.xml`（改坐标/描述/enforcer 名单；依赖表按 spec §一.2）。★ 四个上游模块的 enforcer **不动**（sd 是它们的下游，不在其检查范围）。
2. 写 `package-info.java`（中文 Javadoc，说明定位与铁律 3 边界）。
3. 改 root `pom.xml` 加 module 行（+ `dependencyManagement` 登记）。
4. 改 `simos-core/pom.xml` 加 sd 到 main excludes；改 `simos-app/pom.xml` 加 sd 依赖。
5. 定向编译：`./mvnw -q -pl simos-app -am -DskipTests compile`（只编译，不跑全量）。

**判据（可实测值）**
- `./mvnw -q -pl simos-app -am -DskipTests compile` **rc=0**（app 真的能看见 sd；reactor 含 sd）。
- 读 `simos-sd/pom.xml`：`excludes` = `{simos-core, simos-app}`、**不含** `agentlib-mosire`。
- 模块数：root 全量后 **8/8**（父 + 7 模块）。

**变异思路（≥1 个能杀掉它 + 期望红点）**
- **m1**：把 `io.mosire:simos-sd` 加进 **`simos-core` 的 main 依赖** ⇒ 跑 `./mvnw -pl simos-core -am validate` ⇒ 期望 `enforce-core-boundaries` 报 `bannedDependencies` 失败（**红点 = enforcer 报错行**）。用完即 `cp` 逐字节还原。
- **m2**：把 `io.mosire:simos-core` 从 `simos-sd/pom.xml` 的 `excludes` 删掉、并给 sd 加 core 依赖 ⇒ 期望 `simos-sd` 的 enforcer 报错。
- ★ 两个变异体都要**自证字节不同**（`md5sum`）与**还原后 md5 相等**。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/a1-evidence/`（`compile.log`、`enforcer-effective.log`、`mutants/` 含 `orig`/`m1`/`m2` + md5 自记）。

**提交信息样式**：`feat(sd): 新建 simos-sd 骨架与 enforcer 边界（A1）`

---

### A2 数据模型（ID 三件套 + spec §三 全部 record）

**目标**：落地 spec §三 的**全部**实体 record 与 ID 三件套——`SdState` 的 10 个组件所引用的类型在此一次定形（只定形状与构造期字段不变量，**不写命令**）。

**依赖**：A1。

**预期门禁**：总用例 **987 → 999**（`sd +12`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**（均在 `simos-sd/src/main/java/io/mosire/simos/sd/`）
- `id/`：`NationId` / `ArmyId` / `CombatId` / `CombatStageId` / `CombatOutcomeId` / `DecisionMakerId` / `DirectiveId` / `EffectId` / `VerdictId` / `LossRecordId`（**裸值 `toString()` + `static parse` 三件套**，★ **不自增、不用随机 UUID**）。
- `model/`：`Nation` / `Army` / `DecisionMaker` / `Affiliation`（`Nation(NationId)` | `Army(ArmyId)`） / `Combat` / `CombatStage` / `CombatState` / `OutcomeTable` / `OutcomeOption` / `CasualtySpec` / `CasualtyDelta` / `LossClass` / `LossRecord` / `Directive` / `DirectiveCommand` / `DirectiveStatus` / `Verdict` / `VerdictMeta` / `Effect` / `EffectKind` / `EffectStatus` / `Trigger`（sealed：`AtTick`/`AfterTicks`/`UnitAtHex`/`ThresholdKills`/`OutcomeSelected`/`And`/`Or`） / `Action`（sealed：`PutInfo`/`SetStage`/`RecordCasualties`/`EnqueueUnitCommand`） / `ViewScope` / `DisclosurePolicy` / `SdInfoEntry`。
- 冻结纪律：集合一律 **`LinkedHashMap`/`LinkedHashSet` + `Collections.unmodifiable*` 写在赋值处**；**禁用 `Map.copyOf` / `Set.copyOf`**（迭代序不是内容的纯函数，M2 Task 5 实测）。构造期不变量照 spec §三各节（`OutcomeOption.weight > 0`、`OutcomeTable.options` 非空、`CasualtyDelta.personnel <= 0` 且 equipment 值为负）。★ **包装那一层必须留在构造器体/赋值处**（SpotBugs `EI_EXPOSE_REP` 只认构造器体内可见的包装调用，M4 裁定 50 / 形态 7）。
- 测试 `simos-sd/src/test/java/io/mosire/simos/sd/model/`：ID `parse(toString())` 往返、`OutcomeTable` 权重非法拒绝、`CasualtyDelta` 符号校验。

**bite-sized 步骤**
1. 写 10 个 ID record（各含 `parse`）。
2. 写 `Affiliation` + `Nation`/`Army`/`DecisionMaker`/`ViewScope`/`DisclosurePolicy`。
3. 写 `Combat`/`CombatStage`/`CombatState`/`OutcomeTable`/`OutcomeOption`/`CasualtySpec`。
4. 写 `CasualtyDelta`/`LossClass`/`LossRecord`。
5. 写 `Directive`/`DirectiveCommand`/`DirectiveStatus`/`Verdict`/`VerdictMeta`/`SdInfoEntry`。
6. 写 `Effect`/`EffectKind`/`EffectStatus`/`Trigger`/`Action`。
7. 写测试类并跑定向：`./mvnw -q -pl simos-sd -am -Dtest='*IdTest,*ModelTest' -Dsurefire.failIfNoSpecifiedTests=false test`。

**判据（可实测值）**
- 全部 ID：`parse(x.toString()).equals(x)` 逐值绿。
- `OutcomeOption(weight=0)` / `CasualtyDelta(personnel=+1)` 构造**抛**（`hasMessage` 精确匹配）。
- 集合字段不可变（`UnsupportedOperationException`）。
- 定向测试 rc=0。

**变异思路**
- **m1**：`OutcomeOption` 删掉 `weight > 0` 校验 ⇒ 非法权重用例**不红**（红点落该断言）。
- **m2**：`OutcomeTable` 允许空 `options` ⇒ 空表用例不红。
- **m3**：`CasualtyDelta` 删掉"equipment 值为负"校验 ⇒ 正装备 delta 通过。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/a2-evidence/`。

**提交信息样式**：`feat(sd): 数据模型与 ID 三件套（A2，spec §三）`

---

### A3 `SdState` / `SdChangeSet` / `SdSnapshot` / `SdCodec` + 往返 + 架构计数 5

**目标**：第 5 个 main `ChangeSet` 实现者落地；`SdState` 10 组件与 `SdChangeSet` 10 组件**一一对应**且**往返不变式**（铁律 5）；`SdCodec` 成为第 4 个 `ModuleCodec` 并注册进 `Shell`。

**依赖**：A2。

**预期门禁**：总用例 **999 → 1007**（`sd +8`；`ArchitectureGuardsTest` 是**改写既有类**、计数不增）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/state/SdState.java`（状态树；`record SdState(Map<…> …)` 10 组件 + 5 条构造期不变量）。★ 与 `SdSnapshot` 分工照 map 的 `GameMap`（树）/ `MapSnapshot`（切片）。
- `simos-sd/.../sd/change/SdChangeSet.java`（`implements ChangeSet`；10 组件；`between`/`apply`/`isEmpty` 三件套照 `simos-unit/.../change/UnitChangeSet.java`）。
- `simos-sd/.../sd/state/SdSnapshot.java`（`implements Snapshot`，`namespace()="sd"`）。
- `simos-sd/.../sd/codec/SdCodec.java`（第 4 个 `ModuleCodec`；mixin 摘 `SdChangeSet.isEmpty()` 的 `empty` 属性；本模块 **Map 键反序列化器**：10 个 ID 类型凡作 `Map` 键者全注册——`NationId`/`ArmyId`/`CombatId`/`CombatStateId`/`DecisionMakerId`/`DirectiveId`/`EffectId`/`VerdictId`/`LossRecordId`；★ `Address` 键由 `SimosObjectMapper` 已提供，**不重复注册**）。
- 改 `simos-app/.../Shell.java`：`codecs` 列表加 `new SdCodec()`（第 4 个）。
- 改 `simos-core/src/test/.../ArchitectureGuardsTest.java`：`changeSetHasExactlyFourMainSourceImplementors` **4→5**（★ 方法名与注释里的 "four" 会误导，**建议同时改名 `changeSetHasExactlyFiveMainSourceImplementors`**，改动只在本类内）；`containsExactly` 加 sd 路径；`RepoSourceScan.javaFilesUnder(...)` 加 `"simos-sd/src/main"`。
- 测试：`SdRoundTripTest`（全组件往返 + 空变更集 + `info` 组件）+ `SdCodecTest`（encode/decode 对称）。

**bite-sized 步骤**
1. 写 `SdState`（10 组件 + 5 条构造期不变量：R4 唯一性 / 引用完整性 / 结局一致性 / 阶段链 / 损失上界）。
2. 写 `SdChangeSet`（10 组件，委托 `FieldDelta.diff`/`rebuild`）。
3. 写 `SdSnapshot` + `SdCodec`（mixin + 键反序列化器）。
4. 写 `SdRoundTripTest`（`RoundTripAssertions.assertRoundTrip`，夹具覆盖 10 个组件各自非空）与 `SdCodecTest`。
5. 改 `ArchitectureGuardsTest` 计数 4→5 + 路径 + 扫描目录。
6. 改 `Shell.java` 注册 `SdCodec`。
7. 定向（两条，分别跑各自模块）：`./mvnw -q -pl simos-sd -am -Dtest='SdRoundTripTest,SdCodecTest' -Dsurefire.failIfNoSpecifiedTests=false test`；`./mvnw -q -pl simos-core -am -Dtest='ArchitectureGuardsTest' -Dsurefire.failIfNoSpecifiedTests=false test`。

**判据（可实测值）**
- `SdChangeSet` 全组件（含 `info`）`assertRoundTrip` 绿。
- `SdCodec` 编码→解码→再编码**逐字节相同**。
- `ArchitectureGuardsTest` 绿且命中**恰 5 个**路径。
- 五个构造期不变量各有**故意违规**用例（R4 重复 / 悬空外键 / 结局不在表 / 链断裂 / 损失越界）⇒ 构造抛。

**变异思路**
- **m1**：`SdChangeSet` 少一个组件（如去掉 `info`）⇒ `SdRoundTripTest` 红（红点落 `assertRoundTrip` 的字段漂移消息）。
- **m2**：`SdState` 加一个不在变更集里的组件 ⇒ 同一条往返红。
- **m3**：`ArchitectureGuardsTest` 计数改回 4（或删 sd 路径）⇒ 该测试红。
- **m4**：`SdCodec` 不注册某个 ID 键反序列化器 ⇒ 含该键的 `Map` 解码抛（`SdCodecTest` 红）。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/a3-evidence/`。

**提交信息样式**：`feat(sd): SdState/SdChangeSet/SdCodec 与往返守卫（A3）`

---

### A4 Nation 命令族 + `SdResolver`（`sd.CreateNation` / `sd.CreateArmy` / `sd.CreateDecisionMaker`）

**目标**：三条 Nation 域命令 + `sd:` 地址解析器；R13 的"国家区域 = 带 tag 的 Region"在 `CreateNation` 期校验；N9 的"决策人工具白名单不含通用写"在 `CreateDecisionMaker` 期校验。

**依赖**：A3。

**预期门禁**：总用例 **1007 → 1017**（`sd +10`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/CreateNationHandler.java`（`type()="sd.CreateNation"`；拒绝：id 已存在 / `homeRegion` 不存在 / **该 Region 无国家 tag**（R13））。
- `simos-sd/.../sd/spi/CreateArmyHandler.java`（拒绝：id 已存在 / `nationId` 不存在 / `rootUnitId` 不存在——存在性经 `state.module("unit")` 读）。
- `simos-sd/.../sd/spi/CreateDecisionMakerHandler.java`（拒绝：id 已存在 / `affiliation` 目标不存在 / `allowedTools` 含 `simos.command.submit` ⇒ 拒绝（N9））。
- `simos-sd/.../sd/spi/SdPayloads.java`（载荷解析助手；包私有，形制照 `simos-unit/.../spi/UnitPayloads.java`）。
- `simos-sd/.../sd/spi/SdSnapshots.java`（`SimulationState → SdSnapshot` 的验后转型；+ 读 unit 切片的存在性助手）。
- `simos-sd/.../sd/resolve/SdResolver.java`（`namespace()="sd"`；canonical 形态见 spec §二.2：`sd:nation.<id>` / `sd:army.<id>` / `sd:combat.<id>` / `sd:combat.<id>:stage.<stageId>` / `...:outcome.<id>` / `sd:decision-maker.<id>` / `sd:directive.<id>` / `sd:effect.<id>`）。
- 改 `simos-app/.../Shell.java`：handlers 列表加 3 条（`commandTypes` 仍按 `handler.type()` 收）；`resolverRegistry.register(new SdResolver())`。
- 测试：`sd` 侧 handler 单测（正常 / 重复 id / 区域无 tag / 无 unit / 白名单含通用写）+ `SdResolverTest`。

**bite-sized 步骤**
1. 写 `SdPayloads` + `SdSnapshots`（含 unit 存在性读取）。
2. 写 `CreateNationHandler`（R13 tag 校验）。
3. 写 `CreateArmyHandler`（`state.module("unit")` 读根单位存在性）。
4. 写 `CreateDecisionMakerHandler`（N9 白名单校验）。
5. 写 `SdResolver`（子实体链式解析 + 候选 canonical 回显）。
6. 改 `Shell.java` 注册 3 handler + 1 resolver。
7. 定向测试：`./mvnw -q -pl simos-sd -am -Dtest='Sd*HandlerTest,SdResolverTest' -Dsurefire.failIfNoSpecifiedTests=false test`。

**判据（可实测值）**
- 三条命令各：正常提交 ⇒ `revisions` 行数 +1；拒绝路径 ⇒ **行数不变**（拒绝是原子的）。
- `CreateNation` 对无国家 tag 的 Region ⇒ 拒绝且消息含 region id。
- `CreateDecisionMaker` 的 `allowedTools` 含 `simos.command.submit` ⇒ 拒绝。
- `SdResolver`：`sd:nation.X` 解析出 canonical `sd:nation.X`；未存在 ⇒ 空候选（不抛）；非法形态 ⇒ 空候选。
- ★ **国家 tag 字符串约定**：spec 未定死 ⇒ **待裁**（见 §六）；**建议** `"nation:" + nationId`，`NationTag` 常量集中一处，`CreateNation` 与 A6 的守卫**引用同一常量**（避免两处字面量漂移）。

**变异思路**
- **m1**：`CreateNation` 删掉"该 Region 有国家 tag"校验 ⇒ 无 tag 用例**不红**。
- **m2**：`CreateDecisionMaker` 删掉 N9 白名单校验 ⇒ 含通用写的用例不红。
- **m3**：`SdResolver` 对不存在的 nation **编造一个候选**（而非空）⇒ 空候选用例红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/a4-evidence/`。

**提交信息样式**：`feat(sd): Nation/Army/DecisionMaker 命令与 sd 解析器（A4）`

---

### A5 R14 INFO 写路径（`SdInfoEntry` + `sd.PutInfo`）

**目标**：sd 自有 INFO 覆盖层写路径（R14）——`sd.PutInfo` → `SdChangeSet.info` → 进 revision，可重放、受铁律 5 往返守卫。★ **不复用全局 `InfoSystem`**（spec §六）。

**依赖**：A4。

**预期门禁**：总用例 **1017 → 1023**（`sd +6`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/PutInfoHandler.java`（`type()="sd.PutInfo"`；载荷 `address,key,value,note?`；拒绝：地址非法）。
- 改 `simos-app/.../Shell.java`：handlers 加 `PutInfoHandler`。
- 测试：正常 / 坏地址拒绝 + **往返**（info 组件随 revision 重放逐字段相等）。

**bite-sized 步骤**
1. 写 `PutInfoHandler`（`Address.parse` 失败 ⇒ 拒绝；`value` 用 `Object`）。
2. 改 `Shell.java` 注册 handler。
3. 写测试（含"重放后 INFO 仍在"）。
4. 定向测试。

**判据（可实测值）**
- `sd.PutInfo` 提交后 `Replay` 到新坐标 ⇒ `info` 条目**逐字段相等**（key/value/note/sourceDirective）。
- 坏地址文本 ⇒ 拒绝且行数不变。
- ★ **诚实边界（spec §六 + M4 裁定 38 同口径）**：`SdInfoEntry.value` 是**裸 `Object`**，**结构化值（Map/List）的 `equals` 往返不满足** ⇒ 本任务判据**只覆盖 `String`/数值等标量值**；结构化值列为**挂起**（见 §六），**不假装覆盖**。
- ★ **写的是感知层**：spec §六倾向"`sd.PutInfo` 默认写**感知层**（供 UI/AAR 展示），真值保留在领域模块"——本任务按此实现，并在 Javadoc 写明。

**变异思路**
- **m1**：`PutInfoHandler` 不做 `Address.parse`（直接存）⇒ 坏地址用例不红。
- **m2**：`SdChangeSet.info` 的 `between`/`apply` 漏归一 ⇒ 往返红（补一条**info 有内容**的往返）。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/a5-evidence/`。

**提交信息样式**：`feat(sd): sd.PutInfo 与 sd 侧 INFO 覆盖层（A5，R14）`

---

### A6 ★ 不可删守卫（`MutationGuard` + `RegionDeleteGuard`）

**目标**：新增**写前跨模块策略**契约（`util.spi`，只读，照 `AgentAttachPolicy` 形制），Core 在信封支 `handler.handle` **之前**依次调用；`RegionDeleteGuard`（住 `simos-sd`，只有它能同时读 map 的 `Region.meta.tag` 与 sd 的 `Nation.homeRegion`）拒绝删除**带国家 tag 的区域**。★ **Core 仍看不见领域类型**（铁律 4）。

**依赖**：A4（`Nation.homeRegion` 与 tag 约定）。

**预期门禁**：总用例 **1023 → 1037**（`core +5`、`sd +6`、`app +3`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- 新建 `simos-util/.../util/spi/MutationGuard.java`（`String name(); Optional<String> rejection(SimulationState state, String commandType, String payloadJson);`——**只读、不写状态**）。
- 改 `simos-core/.../core/command/CommandBus.java`：`dispatch` 在 `handler.handle` **之前**依次调已注册 guard；任一拒绝 ⇒ 照 `HandlerOutcome.Rejected` 落 `received+rejected`、**不留 revision**。
- 改 `simos-core/.../core/CoreSimos.java`：guard 的**注册入口**（★ 具体 API 形状**待裁**，见 §六；**建议**照既有 `register(CommandHandler)` 形制加 `register(MutationGuard)`，封存时收全量传给 `CommandBus` 构造器）。
- 新建 `simos-sd/.../sd/guard/RegionDeleteGuard.java`（`name()="sd.region-delete"`；对 `commandType="map.DeleteRegion"` 解析 `regionId`，若该 Region 有国家 tag 或任一 `Nation.homeRegion` 指向它 ⇒ `Optional.of(理由)`；否则 `Optional.empty()`）。
- 改 `simos-app/.../Shell.java`：装配 `RegionDeleteGuard`。
- 测试：util 侧契约（转发/空返回）；core 侧 `CommandBus` guard 调用**先于** handler + 拒绝不留 revision；sd 侧 `RegionDeleteGuard` 真值表（带 tag 拒 / 去 tag 放行）。

**bite-sized 步骤**
1. 写 `MutationGuard`（`util.spi`）。
2. 改 `CommandBus`：构造器收 `List<MutationGuard>`，`dispatch` 在 handler 前调用。
3. 改 `CoreSimos`：加注册入口 + 封存时传入。
4. 写 `RegionDeleteGuard`。
5. 改 `Shell.java` 装配 guard。
6. 写三处测试。
7. 定向测试：`./mvnw -q -pl simos-util -am -Dtest='*MutationGuard*' -Dsurefire.failIfNoSpecifiedTests=false test`，再 `simos-core`、`simos-sd` 各一条。

**判据（可实测值）**
- 带国家 tag 的 Region `map.DeleteRegion` ⇒ **拒绝**、`revisions` 行数**不变**、事件含 `received+rejected`。
- ★ **去掉 tag ⇒ 放行**（证明**不是恒拒**）——必须**两侧都测**（只证拒 = 判别力不足）。
- 非 `map.DeleteRegion` 命令 ⇒ guard 返回空、不影响。
- 同一输入的两次调用**逐结果相同**（确定性）。

**变异思路**
- **m1**：**删掉 guard 装配**（Shell 不注册）⇒ 带 tag 删除用例**应删除成功**（红点落"拒绝"断言）——这是 spec §九 点名的自证。
- **m2**：`RegionDeleteGuard` 改成**恒拒** ⇒ "去 tag 放行"用例红。
- **m3**：`CommandBus` 把 guard 调用挪到 `handler.handle` **之后** ⇒ 拒绝时机用例红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/a6-evidence/`。

**提交信息样式**：`feat(sd): MutationGuard 契约与不可删区域守卫（A6，spec §九）`

---

## 阶段 C —— Combat（★ 前置：姊妹计划 B 完成）

> ★ **开工前置**：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md` 落盘且 B 关账（编制两级 / 三态 / 战损增量 / 稀疏路点）。**B 未完成则 C 不得开工**（C3 读单位绝对值、C4/C5 的 drain 发 `unit.ApplyCasualties` 都依赖它）。
> ★ 本阶段**只引用** `unit-extension-design.md §五.2` 的命令名（`unit.ApplyCasualties` / `unit.PlanSparseRoute` 等），**不实现** unit 侧任何内容。

### C1 Combat 场/阶段/结局命令（N1 + N2 数据层）

**目标**：`sd.CreateCombat` / `sd.AddCombatStage` / `sd.SetStageOutcomeTable`；N1 的**链式条件**（上一 exit == 新 entry）与 N2 的**权重合法性**在**命令期**判定。

**依赖**：A6 + **B**。

**预期门禁**：总用例 **1037 → 1047**（`sd +10`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/CreateCombatHandler.java` / `AddStageHandler.java` / `SetOutcomeTableHandler.java`。
- `simos-sd/.../sd/model/CombatStages.java`（链式条件校验助手；`stages[i].exit.equals(stages[i+1].entry)`，不满足 ⇒ 拒绝）。
- 改 `Shell.java`（注册 3 handler）。
- 测试：链式条件相等/不等、权重 ≤0、空表、阶段 `participants` 与 `Combat.participants` **两层集合**。

**bite-sized 步骤**
1. 写 3 个 handler。
2. 写 `CombatStages` 链式校验。
3. 写 outcomeTable 校验（`weight>0`、非空）。
4. 改 `Shell.java` 注册。
5. 定向测试。

**判据（可实测值）**
- N1：`AddCombatStage` 的新 `entry` ≠ 上一 `exit` ⇒ **拒绝**；相等 ⇒ 提交成功。
- N2：权重 ≤0 / 空表 ⇒ 拒绝（`hasMessage` 精确）。
- `CombatState.participants` 与 `Combat.participants` 是**两层**：阶段可加**独立参与单位**（spec §三.3）。
- `sd.CreateCombat` 的 `participants` 里单位不存在 ⇒ 拒绝。

**变异思路**
- **m1**：`CombatStages` 删掉链式校验 ⇒ 断裂用例不红。
- **m2**：权重校验放宽到 `>= 0` ⇒ 0 权重用例不红。
- **m3**：`CreateCombatHandler` 跳过单位存在性校验 ⇒ 悬空 unit 用例不红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/c1-evidence/`。

**提交信息样式**：`feat(sd): Combat 场/阶段/结局命令（C1，N1+N2）`

---

### C2 `sd.CommitCombatOutcome`（N2 恰一个）

**目标**：选定唯一实际结局；`CombatState.selectedOutcome` **恰一个**；同一 revision 两次读出**逐字节相同**。

**依赖**：C1。

**预期门禁**：总用例 **1047 → 1052**（`sd +5`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/CommitOutcomeHandler.java`（`type()="sd.CommitCombatOutcome"`；拒绝：阶段/结局不存在 / 该结局**不在此阶段表内** / 已选过）。
- 改 `Shell.java`。
- 测试：正常 / 越界 outcome / 重复选。

**bite-sized 步骤**
1. 写 handler。
2. 改 `Shell.java` 注册。
3. 写测试（含"同一 revision 两次读出逐字节相同"）。
4. 定向测试。

**判据（可实测值）**
- 合法结局 ⇒ 提交成功、`selectedOutcome` 恰一个。
- 不在该阶段 `outcomeTable` 的结局 ⇒ 拒绝。
- 已选过 ⇒ 拒绝（**不覆盖**）。
- 同一 revision 两次 `Replay` 读出的 `SdState` **逐字节相同**。

**变异思路**
- **m1**：去掉"结局必须在表内"校验 ⇒ 越界用例不红。
- **m2**：允许重复选择（覆盖）⇒ 重复用例不红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/c2-evidence/`。

**提交信息样式**：`feat(sd): 结局选定命令与恰一个不变量（C2，N2）`

---

### C3 `sd.RecordCasualties`（N3 双轨 + 上界）

**目标**：战损以 **delta（事件）** 记入 `LossRecord`；人员/装备双轨；**代码侧上界校验**（Δ 为负且 |Δ| ≤ 当前值）。★ 上界的"当前值"从 **unit 切片**读（依赖 B 的单位状态）。

**依赖**：C1 + **B**。

**预期门禁**：总用例 **1052 → 1059**（`sd +7`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/RecordCasualtiesHandler.java`（`type()="sd.RecordCasualties"`；拒绝：单位不存在 / Δ 为正 / |Δ| > 当前）。
- `simos-sd/.../sd/spi/SdSnapshots.java` 补**读 unit 绝对值**的助手（只读，形制照 `unit` 侧快照访问器）。
- 改 `Shell.java`。
- 测试：正常 / Δ 为正 / |Δ| 超界 / 单位不存在 / `LossRecord` 重放逐值。

**bite-sized 步骤**
1. 写 unit 绝对值读取助手。
2. 写 handler（逐项校验 + 构造 `LossRecord`）。
3. 改 `Shell.java` 注册。
4. 写测试（含 `LossRecord` 随 revision 重放逐值相等）。
5. 定向测试。

**判据（可实测值）**
- Δ `personnel` 为正 / `equipment` 值为正 ⇒ **拒绝**。
- |Δ| > 当前值 ⇒ **拒绝**（命令期，**不靠事后**）。
- 合法 delta ⇒ 提交成功，`LossRecord` 可回放且**逐值**相等。
- `RECOVERABLE` v1 **只记类别、不设回池**（spec §〇.3）——测试只断言类别被记录。

**变异思路**
- **m1**：删掉上界校验 ⇒ 越界用例不红。
- **m2**：把上界改成 `|Δ| <= 当前 + 1`（差一错误）⇒ 恰好越界 1 的用例红。
- **m3**：`LossRecord` 的 `atRevision` 不写 ⇒ 重放逐值红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/c3-evidence/`。

**提交信息样式**：`feat(sd): 战损双轨记录与上界校验（C3，N3）`

---

### C4 Effect 命令 + ★ `SdTimeParticipant`（R6 延期 + N1 阶段推进）

**目标**：`sd.RegisterEffect` / `sd.CancelEffect`（ECA 规则，spec §三.6）；**恰一个** `SdTimeParticipant`（`namespace="sd"`）在 `simulate` 里用 `range.to` 求值 `Effect.trigger` 与 `CombatStage.entry/exit`，产出**本模块** `SdChangeSet`。★ **绝不放进 ③Resolve**；★ **`reads/writes` 显式声明**（canonical 地址字符串）。

**依赖**：C3。

**预期门禁**：总用例 **1059 → 1071**（`sd +10`、`app +2`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/RegisterEffectHandler.java` / `CancelEffectHandler.java`（拒绝：id 已存在 / trigger 引用不存在实体 / action 引用非法地址或命令；`CancelEffect` 仅 `PLANNED/COMMITTED` 可取消）。
- `simos-sd/.../sd/time/SdTimeParticipant.java`（`namespace()="sd"`；纯函数；评估 trigger / 阶段推进；产出 `SdChangeSet`）。
- `simos-sd/.../sd/time/TriggerEvaluator.java`（纯函数：`AtTick`/`AfterTicks`/`UnitAtHex`/`ThresholdKills`/`OutcomeSelected`/`And`/`Or`）。
- 改 `Shell.java`：注册 `SdTimeParticipant`（**恰好一个** `sd` namespace；`putIfAbsent` 重复即抛）与 2 handler。
- 测试：条件未达成不产生 / 达成后只产生一次 / 阶段链推进 / `reads/writes` 声明（精确集合匹配）。

**bite-sized 步骤**
1. 写 `TriggerEvaluator`（纯函数）。
2. 写 `SdTimeParticipant.simulate`（R6 + N1 + reads/writes）。
3. 写 `RegisterEffectHandler` / `CancelEffectHandler`。
4. 改 `Shell.java` 注册 participant + 2 handler。
5. 写测试（含 `AtOrAfterTick` 与 `ThresholdKills` 两类各实测推进）。
6. 定向测试。

**判据（可实测值）**
- R6：条件**未达成**前 tick 推进 ⇒ 效果**不发生**（`EffectStatus` 不变）；达成后**只产生一次**（第二次推进不重复）。
- N1：`exit` 满足 ⇒ `CombatState.currentStage` 推进到下一阶段（链式）；`AtOrAfterTick` 与 `ThresholdKills` **各一条**。
- `reads/writes` 是**显式集合**（非空、canonical 地址字符串），且与 `TimeProposal` 构造期冻结一致。
- 恰一个 `sd` participant：注册第二个 ⇒ `TimeAdvance` 构造期抛。
- ★ **`range.to` 缺省**（无上界推进）⇒ 零变更提案、**不抛**（照 `UnitTimeParticipant` 的边界）。
- ★ **order-latency（N5）落点**：spec §〇.3 定"v1 每命令一个延迟值"，但**字段挂在哪**（`DirectiveCommand.delayTicks` 还是 `Effect.readyAtTick`）spec 未定 ⇒ **待裁**（见 §六）；**建议**放 `Effect.readyAtTick`（延期效果本就带"何时可发生"），本任务只实现**机制形状**，不引入 Command Ops 数值。

**变异思路**
- **m1**：把条件评估挪进 ③Resolve（或 participant 里"未达成即产生"）⇒ R6 用例红。
- **m2**：达成后**重复产生**（不去重）⇒ "只产生一次"用例红。
- **m3**：阶段推进用**时间硬编码**替代 `entry/exit` ⇒ 推进点不符用例红。
- **m4**：注册第二个 `sd` participant ⇒ `putIfAbsent` 应抛（红点落该抛）。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/c4-evidence/`。

**提交信息样式**：`feat(sd): 延期效果与 sd 时间推进参与者（C4，R6+N1）`

---

### C5 ★ `SdCommandDrain`（跨模块效果，app）

**目标**：`sd` participant **只写 sd**（跨模块效果落成 `EffectAction.EnqueueUnitCommand`）；app 层 `SdCommandDrain` 在**一次 `AdvanceTime` 提交成功之后**，读取新 head 的 `sd` 切片里 `FIRED 且未 drain` 的指令，按顺序经 `CoreSimos.submit(CommandEnvelope)` 提交。★ **幂等**（重放不重复提交）。

**依赖**：C4 + **B**（`unit.ApplyCasualties` 等命令必须已存在）。

**预期门禁**：总用例 **1071 → 1077**（`app +6`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-app/.../app/sd/SdCommandDrain.java`（读 sd 切片的 pending 指令 → 经 `CoreSimos.submit` 提交；用 **drain 前读到的 head** 作 `expectedRevision`；撞车由既有乐观并发折 `Conflict`）。
- `simos-sd/.../sd/model/EffectAction.java` 的 `EnqueueUnitCommand` + **幂等键**（如 `(effectId, revision)`；spec 未定 ⇒ **待裁**，见 §六，建议用 `effectId` + drain 轮次）。
- 改 `Shell.java`：`AdvanceTime` 提交成功后调 drain（★ **不改 Core**；写面仍是 `CoreSimos.submit`，不碰 store/timeline ⇒ `AppWritePathGuardTest` 的 R1 不破）。
- 测试：跨模块效果落成**真 revision**；drain **幂等**（重放不重复提交）；中途失败留中间态**如实测**。

**bite-sized 步骤**
1. 写 `EnqueueUnitCommand` 幂等键与"未 drain"判定。
2. 写 `SdCommandDrain`（只经 `CoreSimos.submit`；★ **不得**出现 `Timeline`/`SqliteStore`/`CheckpointStore` 串，否则 `AppWritePathGuardTest` 红）。
3. 改 `Shell.java` 在 `AdvanceTime` 提交后调 drain。
4. 写测试（真 revision + 幂等）。
5. 定向测试。

**判据（可实测值）**
- 跨模块效果：sd 推进后 ⇒ `SdCommandDrain` 发 `unit.ApplyCasualties`（**真 revision**：`revisions` 行数增加）。
- **幂等**：对同一坐标重放 / 重复 drain ⇒ **不重复提交**（行数不再增加）。
- `AppWritePathGuardTest` 仍绿（drain 不碰存储写面）。
- ★ **如实记代价**：drain 是"提交后再提交、跨多个 revision"；中途失败会出现"sd 已记 effect、unit 未改"的中间态——**测试要能看见这个中间态**，不假装原子（跨 revision 原子性列为挂起，spec §十二）。

**变异思路**
- **m1**：去掉幂等键 ⇒ 重复 drain ⇒ 行数重复增加（幂等用例红）。
- **m2**：drain 直接用"当前 head"而非 drain 前读到的 head ⇒ 并发/重放场景抢 revision（用例红）。
- **m3**：drain 不判定 `FIRED` 状态（把 `PLANNED` 也发）⇒ "条件未达成不产生"用例红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/c5-evidence/`。

**提交信息样式**：`feat(sd): SdCommandDrain 跨模块效果与幂等（C5，spec §五.3）`

---

### C6 Combat 端到端判据（条件驱动 + 冻结）

**目标**：把 C1~C5 串成**端到端**：真 `SdCodec` + 真 `SdTimeParticipant` + 真 store，经 `CoreSimos`；对照 spec §十一 的 5/6/7/8 条。

**依赖**：C5。

**预期门禁**：总用例 **1077 → 1081**（`app +4`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-app/src/test/.../sd/SdCombatEndToEndTest.java`（test-only）。
- 测试夹具：合成 `Nation`/`Combat`/`Stage`/`Effect`/`LossRecord`（★ **嵌套泛型与 Optional 位置**已验证需 `Jdk8Module`；按既有 codec 夹具形制）。

**bite-sized 步骤**
1. 写端到端夹具（经**真写路径** `CoreSimos.submit` 造数据）。
2. 写四条判据断言（N1 条件驱动 / N2 恰一个 / N3 上界 / R6 一次性）。
3. 定向测试。

**判据（可实测值）**
- 条件驱动：`AtOrAfterTick` 到点前/后**两个 tick** 的 `currentStage` 逐值不同。
- 冻结：同一坐标两次 `Replay` 的 `SdState` **逐字节相同**。
- 全部走真 `submit`（**不是**夹具直接落盘）——★ 这是 M4 的教训（`Replay` 曾在夹具行上跑过）。

**变异思路**
- **m1**：把阶段推进改成"每 tick 无条件推进" ⇒ 条件驱动用例红。
- **m2**：`SdCodec.apply` 照抄 base 坐标 ⇒ Core ④Validate 第 4 项红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/c6-evidence/`。

**提交信息样式**：`test(sd): Combat 条件驱动与冻结端到端（C6）`

---

## 阶段 D —— Directive + 判决 + 渠道（★ 前置：C）

### D1 Directive + R4 + `sd.IssueDirective`

**目标**：`sd.IssueDirective` 落 `Directive`；★ **R4 硬不变量**（一决策人一 tick 至多一条）在**命令期与状态期两处**校验（spec §十一.2）；`DirectiveCommand.type` 走**命令白名单**；`target` 地址合法；**N12 配额与发 revision 同一事务**（复用 `CommandBus` 既有事务边界，不另造锁）。

**依赖**：C6。

**预期门禁**：总用例 **1081 → 1091**（`sd +10`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/IssueDirectiveHandler.java`（`type()="sd.IssueDirective"`；拒绝：R4 违反 / `decisionMakerId` 不存在 / `commands[].type` 不在白名单 / `target` 地址非法；★ **`sd` 自己的命令也不许出现在 `DirectiveCommand` 里递归生成指令**——v1 明确禁，spec §四）。
- `simos-sd/.../sd/spi/DirectiveWhitelist.java`（合法命令集；注册期收全量 `commandTypes`——`Shell` 已有该集合）。
- 改 `Shell.java`：注册 `IssueDirectiveHandler`；把 `commandTypes` 注入白名单。
- 测试：R4 第二条拒绝 + **`revisions` 行数不变** / 白名单外命令拒绝 / 自指命令拒绝 / 非法 target / 并发两条同 tick 恰一条成功（N12）。

**bite-sized 步骤**
1. 写 `DirectiveWhitelist`（收 `commandTypes`，**禁 `sd.*` 自指**）。
2. 写 `IssueDirectiveHandler`（R4 + dm 存在性 + 白名单 + target）。
3. 改 `Shell.java` 注册 + 注入白名单。
4. 写测试（含并发一条）。
5. 定向测试。

**判据（可实测值）**
- 同 dm 同 tick 第二条 ⇒ **拒绝**、`revisions` 行数**不变**。
- `commands[].type` 不在白名单 ⇒ 拒绝；`sd.IssueDirective` 自指 ⇒ 拒绝。
- N12 并发：两线程同 tick 两条 ⇒ **恰一条 committed、另一条 rejected/conflict**，**无半写 revision**（复用 `CommandBus` 的 ①③ 乐观并发 + 主键）。
- `Directive.intentInfoKey` 与 `commands` **分开存**（spec §三.5），不被互相推导。

**变异思路**
- **m1**：删 R4 唯一性校验 ⇒ 第二条通过（红点落"拒绝"与行数不变）。
- **m2**：白名单放行 `simos.command.submit`（通用写）⇒ 白名单外用例红。
- **m3**：允许 `sd.*` 自指 ⇒ 自指用例红。
- **m4**：把配额检查挪到事务外（自造"先查后写"）⇒ 并发出现两条或半写（N12 用例红）。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/d1-evidence/`。

**提交信息样式**：`feat(sd): Directive 命令与 R4/N12 配额（D1）`

---

### D2 ★ `DecisionAdjudicator` SPI + `LlmClient` + `FakeLlmClient` + 8 断点 schema + N13 降级

**目标**：模块内定义 `DecisionAdjudicator` / `Judgement` / `AdjudicationRequest` / `LlmClient`（N10）；**边界内聚**（契约 / schema 校验器 / 脱敏视图构造 / 工具名单 / 配额强制 / 判决冻结）；**执行上浮**（key / 重试 / 超时留 app）；8 个断点表的输出 schema；**N13 降级**（超时/非法 ⇒ `Failed`/`Abstained`，明确行为、不卡死）。

**依赖**：D1。

**预期门禁**：总用例 **1091 → 1099**（`sd +8`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/adjudication/DecisionAdjudicator.java`（`String name(); Judgement adjudicate(AdjudicationRequest request);`）。
- `simos-sd/.../sd/adjudication/Judgement.java`（sealed：`Accepted(String payloadJson)` / `Abstained(String reason)` / `Failed(String reason)`）。
- `simos-sd/.../sd/adjudication/AdjudicationRequest.java`（`breakpoint, redactedBriefJson, outputSchemaJson, commandWhitelistJson`）。
- `simos-sd/.../sd/adjudication/LlmClient.java`（`String complete(LlmRequest request);`）+ `LlmRequest`。
- `simos-sd/.../sd/adjudication/Breakpoints.java`（8 个断点常量 D1~D8；★ D1 + D3 **合并为同一次调用**，spec §八.2）。
- `simos-sd/.../sd/adjudication/AdjudicationSchemas.java`（各断点输出 schema 要点 + 校验器：`StageVerdict` / `DirectiveDraft` / `EngagementDecision` / `FormationIntent` / `PostCombatDecision` / `DiplomaticAction` / `DomesticAction`）。
- 测试 `FakeLlmClient`（返回固定字节 / 抛超时 / 返回非法 JSON）。
- 测试：结构（三态）/ 约束（非法输出拒绝）/ N13（超时与非法 JSON ⇒ 本 tick 明确降级、后续 tick 继续）。

**bite-sized 步骤**
1. 写四个契约类型 + `LlmRequest`。
2. 写 `Breakpoints`（D1~D8 常量 + D1/D3 合并）。
3. 写 `AdjudicationSchemas` 校验器。
4. 写 `FakeLlmClient`（可控返回/异常/调用计数）。
5. 写测试（结构 + 约束 + N13）。
6. 定向测试。

**判据（可实测值）**
- `Judgement` 三态构造正确；`AdjudicationRequest` 四分量非空。
- `FakeLlmClient` 抛超时 ⇒ 该断点 `Failed`，**tick 不中断**、下一 tick 继续（N13）。
- `FakeLlmClient` 返回非法 JSON ⇒ `Failed`/`Abstained`（**不是**抛出逃逸）。
- ★ **不断言具体选择**（N14）：用例只断言"输出 ∈ 合法集 + 结构正确"。

**变异思路**
- **m1**：让异常逃逸（不折成 `Judgement`）⇒ N13 用例红（tick 中断）。
- **m2**：`AdjudicationSchemas` 删掉一个约束（如空 `rationaleText` 也通过）⇒ 约束用例红。
- **m3**：`Breakpoints` 把 D1/D3 合成两个独立断点 ⇒ "合并调用"计数用例红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/d2-evidence/`。

**提交信息样式**：`feat(sd): DecisionAdjudicator SPI 与 8 断点 schema（D2，N10+N13）`

---

### D3 ★ 判决冻结 + `sd.SubmitVerdict` + `VerdictMeta`（N7/N8）

**目标**：`sd.SubmitVerdict` 落 `Verdict`（**数据**）、进 revision；`VerdictMeta(model/promptVersion/inputBriefDigest)` 三字段非空（N8）；★ **回放/分岔不重跑 LLM**（N7）——`FakeLlmClient` 调用计数在 `Replay` 中 **= 0**。

**依赖**：D2。

**预期门禁**：总用例 **1099 → 1109**（`sd +6`、`app +4`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/SubmitVerdictHandler.java`（`type()="sd.SubmitVerdict"`；拒绝：payload 不过 schema / **subject 不在该断点的裁决面** / meta 字段空（N8）；★ **GM/裁决者专用窄工具**（N9，见 D6））。
- `simos-sd/.../sd/adjudication/VerdictFreezer.java`（把 `Accepted(payloadJson)` 折成 `Verdict`，含 **`atRevision`**；★ 只读已有数据，**不调 adjudicator**）。
- 改 `Shell.java`：注册 `SubmitVerdictHandler`。
- 测试（app 层端到端）：含判决的推进后 `Replay` ⇒ `SdState` 逐字段相同、**`FakeLlmClient` 调用计数 = 0**；同一坐标两次 `Replay` 逐字节相同；meta 空 ⇒ 拒绝。

**bite-sized 步骤**
1. 写 `VerdictFreezer`（把已接受载荷冻结进 `Verdict`）。
2. 写 `SubmitVerdictHandler`（schema + subject + meta 校验）。
3. 改 `Shell.java` 注册。
4. 写 app 层端到端测试（真推进 + 真重放 + `FakeLlmClient` 计数）。
5. 定向测试。

**判据（可实测值）**
- N7：推进包含判决 ⇒ `Replay` 的 `SdState` **逐字段相同**；**`FakeLlmClient` 调用计数在 Replay 中 = 0**（★ 在 replay 路径调 adjudicator ⇒ 计数 > 0，即红）。
- N8：任一 meta 字段空 ⇒ 拒绝。
- subject 不在断点裁决面 ⇒ 拒绝。
- 判决**进 revision**（`revisions` 行数 +1；坐标可查）。

**变异思路**
- **m1**：在 replay 路径调 adjudicator ⇒ N7 用例红（计数 > 0）。
- **m2**：删任一 meta 字段校验 ⇒ N8 用例红。
- **m3**：`Verdict` 不落 `atRevision` ⇒ 重放逐值红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/d3-evidence/`。

**提交信息样式**：`feat(sd): 判决冻结与 verdictMeta（D3，N7+N8）`

---

### D4 可见性 + GM 配权（`ViewScope` / `sd.SetViewScope` / `RedactingQueryService`）

**目标**：R10 + N6 的**两层机制**——工具白名单（已有）+ **数据 redaction 层（新建）**；`ViewScope` 存进 `DecisionMaker`/revision（**可回放、可回退分岔**）；`sd.SetViewScope` 为 GM 专用、标 `sensitive=true` 走 `ToolGate.Ask`（经审批留痕）；★ **插桩点在 app 层**（`QueryService` 四个读方法**都没有 caller 参数**，spec §七.1）。

**依赖**：D3。

**预期门禁**：总用例 **1109 → 1123**（`sd +6`、`app +8`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/spi/SetViewScopeHandler.java`（`type()="sd.SetViewScope"`；拒绝：dm 不存在；★ **GM 专用**（N11），工具侧 `sensitive=true`）。
- `simos-app/.../app/query/RedactingQueryService.java`（★ **app 层新建**：先按调用者身份取 `ViewScope`，再裁剪 `QueryService` 结果；GUI 用 GM/玩家 scope、MCP 决策 Agent 用其绑定 scope）。
- 改 `simos-app/.../app/gui/GuiServer.java`：读端点**统一经** `RedactingQueryService`（spec §七.3）。
- 改 `simos-app/.../app/tools/read/*`（9 条读工具）：统一经 redaction 层（★ 与 D6 同文件 ⇒ **D4→D6 串行**）。
- 改 `Shell.java`：装配 redaction 层与 `SetViewScopeHandler`。
- 测试：两个不同 `ViewScope` 的 dm 调同一读端点 ⇒ 返回**不同**数据；`sd.SetViewScope` 写入 revision；redaction **只报可观察项**（否定式报告不生成）。

**bite-sized 步骤**
1. 写 `SetViewScopeHandler`（GM 专用语义 + dm 存在性）。
2. 写 `RedactingQueryService`（按身份取 scope + 裁剪）。
3. 改 `GuiServer` 读端点接 redaction。
4. 改 9 条读工具接 redaction。
5. 改 `Shell.java` 装配。
6. 写测试（不同 scope 不同数据 + 写入 revision）。
7. 定向测试。

**判据（可实测值）**
- 两个不同 `ViewScope` 的 dm 调**同一读端点** ⇒ 返回**不同**数据（对手/未探测项被 redact）；★ **去掉 redaction 插桩 ⇒ 两响应相同**（这才是判别力）。
- `sd.SetViewScope` 写入 revision（`revisions` 行数 +1；坐标可查）。
- ★ **不泄 ground truth**：redaction 只报可观察项，不报"未探测到 X"（spec §七.3）。
- ★ **per-session MCP 身份**：现状整 server 只有一个 `ToolContext`（`Shell.mcpCaller()`）⇒ v1 只能"GUI 用 GM/玩家 scope、MCP 用其绑定 scope"；**per-session 身份列为挂起**（spec §十二）。

**变异思路**
- **m1**：去掉 redaction 插桩（读端点直连 `QueryService`）⇒ "两响应不同"用例红。
- **m2**：`sd.SetViewScope` 不写 revision（只改内存）⇒ 可回放用例红。
- **m3**：redaction 生成"未探测到 X"的否定式条目 ⇒ 该约束用例红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/d4-evidence/`。

**提交信息样式**：`feat(sd): 可见性 redaction 与 GM 配权（D4，R10+N6）`

---

### D5 ★ 决策提交渠道（`DecisionChannel` + N16/N17/N18 + app 适配）

**目标**：照 AgentLib `ApprovalChannel` 形制定 `DecisionChannel`（`channelId()` / `representableActors()` / `submit(...)`，可选 `available()`）；★ **模块侧强制四条**（渠道不得代劳）：actor ∈ 声明集合 / **视图按 actor 的 `viewScope` 取**（渠道拿不到全量）/ 1 令每 tick 与发 revision 同事务 / **留痕**（渠道 id + actor 进 `verdictMeta` / 事件）。app 侧 GUI/MCP/CLI/外部 HTTP 各一实现（**新增渠道不改领域代码**）。

**依赖**：D4。

**预期门禁**：总用例 **1123 → 1135**（`sd +6`、`app +6`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-sd/.../sd/channel/DecisionChannel.java`（契约；★ `ActorId` / `DecisionRequest` 是**新类型**，住 `simos-sd`；`DecisionRequest` 只承载"actor 想做什么"，**不含视图数据**）。
- `simos-sd/.../sd/channel/ActorId.java` / `DecisionRequest.java`。
- `simos-sd/.../sd/channel/ChannelAdmission.java`（模块侧校验：actor ∈ `representableActors()`；★ **渠道自己的声明不算数**，模块判）。
- `simos-app/.../app/sd/channel/GuiDecisionChannel.java` / `McpDecisionChannel.java` / `CliDecisionChannel.java` / `HttpDecisionChannel.java`（app 适配；外部 HTTP 照 `HttpApprovalChannel` 先例**不持有 server**）。
- 改 `Shell.java`：装配渠道；渠道最终仍写同一落点（`sd.IssueDirective` / `sd.SubmitVerdict`）。
- 测试：伪造 actor（不在声明集合）⇒ 拒绝；视图按 actor `viewScope` 取（渠道拿不到全量）；留痕含渠道 id + actor。

**bite-sized 步骤**
1. 写 `ActorId` / `DecisionRequest` / `DecisionChannel` 契约。
2. 写 `ChannelAdmission`（模块侧 actor 校验）。
3. 写 app 侧 4 个渠道实现（GUI 端点形态**待裁**，见 §六；建议 `POST /api/sd/decision`）。
4. 改 `Shell.java` 装配。
5. 写测试（伪造 actor / 视图隔离 / 留痕）。
6. 定向测试。

**判据（可实测值）**
- ★ **N16**：渠道声明 `representableActors()` 后，**伪造**一个不在集合里的 actor 提交 ⇒ **模块侧拒绝**（渠道自己说"可以"不算数）。
- ★ **N17**：渠道请求里**拿不到全量视图**；脱敏视图由 sd 侧按 actor `viewScope` 构造。
- ★ **N18**：留痕含 `channelId` + actor（进 `verdictMeta` / 事件）。
- ★ **R9**：无论哪条渠道，最终写**同一落点**（`sd.IssueDirective` / `sd.SubmitVerdict`），走**同一条** `Command → ChangeSet → Revision` 路径。
- ★ **安全边界**：渠道是**新攻击面**，三条（身份以模块校验 / 视图由模块构造 / 留痕强制）是**安全边界**，不是风格。

**变异思路**
- **m1**：让渠道自报 actor 即放行（模块不校验）⇒ 伪造 actor 用例红。
- **m2**：渠道直接构造全量视图 ⇒ N17 用例红。
- **m3**：留痕不写渠道 id ⇒ N18 用例红。
- **m4**：某渠道绕过配额（自己写状态）⇒ N12/铁律 2 用例红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/d5-evidence/`。

**提交信息样式**：`feat(sd): 决策提交渠道与身份校验（D5，N16/N17/N18）`

---

### D6 ★ 专用窄工具（N9）+ 工具面分载

**目标**：`sd.SubmitVerdict` / `sd.IssueDirective` / `sd.SetViewScope` 三条**专用窄工具**（内核仍是 `CommandHandler`，最终走 Command 路径）；★ **不给决策 Agent 通用 `simos.command.submit`**——工具面按角色分载：**GM 桶**（配权 + 窄工具，无通用写）、**决策 Agent 桶**（仅窄工具）、**外部 MCP 桶**（保留现状或另行收敛，挂起）。

**依赖**：D5（★ 与 D4 同改读工具/`SimosToolSource` ⇒ **D4→D6 串行**）。

**预期门禁**：总用例 **1135 → 1143**（`app +8`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-app/.../app/tools/write/SubmitVerdictTool.java`（`NAME="sd.SubmitVerdict"`；`ToolSpec.sensitive=true`，走审批）。
- `simos-app/.../app/tools/write/IssueDirectiveTool.java`（`NAME="sd.IssueDirective"`）。
- `simos-app/.../app/tools/write/SetViewScopeTool.java`（`NAME="sd.SetViewScope"`；GM 专用）。
- 改 `simos-app/.../app/tools/SimosToolSource.java`：分桶（GM / 决策 Agent / 外部 MCP）；★ **决策 Agent 桶不含 `CommandSubmitTool`**（现状 M5 的 12 工具里**有**它：`CommandSubmitTool.java:35`、`SimosToolSource.java:84`——**这是要改的点**）。
- 改 `Shell.java`：按角色装配工具面。
- 测试：GM 与决策 Agent 的**工具有效集**里都没有 `simos.command.submit`；有 `sd.*` 窄工具。

**bite-sized 步骤**
1. 写 3 条窄工具（`ToolSupport` + `core.submit`）。
2. 改 `SimosToolSource` 分桶。
3. 改 `Shell.java` 按角色装配。
4. 写测试（有效集断言）。
5. 定向测试。

**判据（可实测值）**
- ★ **GM 桶**工具有效集：**无** `simos.command.submit`、**有** `sd.SetViewScope` / `sd.IssueDirective` / `sd.SubmitVerdict`。
- ★ **决策 Agent 桶**工具有效集：**无** `simos.command.submit`、**有**窄工具。
- 三条窄工具**最终仍走** `Command → ChangeSet → Revision`（真 revision）。
- ★ **外部 MCP 桶**现状（有通用写）**保留**，列为挂起（spec §八.3）。

**变异思路**
- **m1**：把 `CommandSubmitTool` 加回决策 Agent 桶 ⇒ 有效集断言红。
- **m2**：窄工具绕过 `core.submit`（直接改状态）⇒ 铁律 2 用例红（且 `AppWritePathGuardTest` 可能红）。
- **m3**：GM 桶塞入通用写 ⇒ GM 有效集断言红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/d6-evidence/`。

**提交信息样式**：`feat(sd): 专用窄工具与角色分桶（D6，N9/N11）`

---

### D7 8 个 AI 断点落点 + `AdjudicatorRunner`（app）

**目标**：app 侧 `AdjudicatorRunner`——按 tick 编排 8 个断点（D1~D8）的调用：取**模块已脱敏视图** → 调 `DecisionAdjudicator` → 输出过 schema/约束 → 落 `Verdict`/`Directive`；★ **key / 重试 / 超时**留 app（N10）；★ 真实 `LlmClient` 后端**挂起**（spec §十二）。

**依赖**：D6。

**预期门禁**：总用例 **1143 → 1148**（`app +5`）；模块 8/8；`BugInstance size is 0` ×7；`[ERROR]` 0；前端 88/88。

**文件清单**
- `simos-app/.../app/sd/AdjudicatorRunner.java`（断点编排；★ 用 `FakeLlmClient` 可测；真实后端挂起）。
- 测试：8 断点常量与调用次数；D1+D3 合并为一次；失败降级后 tick 继续。

**bite-sized 步骤**
1. 写 `AdjudicatorRunner`（断点→请求→判决→落点）。
2. 写测试（`FakeLlmClient` 计数 + 断点合并）。
3. 定向测试。

**判据（可实测值）**
- 一次 tick 内断点调用次数符合 §八.2 的 cadence；★ **D1 + D3 合并为同一次**。
- `FakeLlmClient` 失败 ⇒ 该断点无判决、tick 继续。
- ★ **不断言 LLM 选择**（N14）。

**变异思路**
- **m1**：D1/D3 拆成两次调用 ⇒ 合并用例红。
- **m2**：断点失败时中断 tick ⇒ N13 用例红。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/d7-evidence/`。

**提交信息样式**：`feat(sd): AI 断点编排与 AdjudicatorRunner（D7）`

---

## 阶段 E —— 关账

### E1 判据逐条 + R 点验 + 关账

**目标**：spec §十一 的 15 条**逐条实测值**（不是"通过/不通过"）；R1~R18 / N1~N18 点验；每任务变异轮汇总；**"我未能核实的"清单**；关账报告。

**依赖**：全部。

**预期门禁**：总用例 **1148**（`util 170 / map 362 / social 45 / unit 131 / core 174 / sd 110 / app 156`）；模块 **8/8**；`BugInstance size is 0` **×7**；`[ERROR]` **0**；前端 **88/88**。★ 以**实测值**为准。

**文件清单**
- `.superpowers/sdd/2026-09-20-sd-simos/task-final-report.md`（关账报告；含逐条判据实测值 + R/N 点验 + `§〇 诚实披露` + `§六 我未能核实的`）。
- 改 `.superpowers/sdd/2026-09-20-sd-simos/progress.md`（台账收口）。

**bite-sized 步骤**
1. 主树前台 `./mvnw clean verify`（rc、逐模块计数、`BugInstance size is 0` ×N、`[ERROR]` 计数、`frontend-gate` 计数）。
2. 关账前单独 `./mvnw spotbugs:check`（`mvn test` **不跑** SpotBugs）。
3. spec §十一 15 条**逐条**写实测值（数字 / 字节 / 行数）。
4. R/N 点验（含"报告在案、日志未入库"的如实标注）。
5. 写 `task-final-report.md`（含**未能核实清单**）。
6. `git add` **显式路径**（`docs/**`、`simos-sd/**`、改动过的 `simos-*/**`、`.superpowers/sdd/2026-09-20-sd-simos/**` 用 `-f`），提交。

**判据（可实测值）**
- `./mvnw clean verify` **rc=0**；8/8 模块 SUCCESS；`BugInstance size is 0` ×7；`[ERROR]` 0 行；`[frontend-gate] OK tests=88 pass=88 fail=0`。
- 15 条判据**每条**有数字（不是"绿"）。
- **未能核实清单**非空或明确写"无"（诚实清单）。

**变异思路**：关账轮本身不做新变异；**汇总各任务变异轮**并**点验存活项**（存活项如实存档，不伪造红点）。

**证据落点**：`.superpowers/sdd/2026-09-20-sd-simos/e1-evidence/`（`clean-verify.log`、`verify-rc.txt`、`task-final-report.md`）。

**提交信息样式**：`docs(sd): SDSimos 关账报告与判据实测（E1）`

---

## 四 执行期取代说明（留空）

> **本节点留空**。执行期的**就地校正**（计划草图与 `src` 分歧、spec 未预见的真缺口、行号漂移）逐条记入
> `.superpowers/sdd/2026-09-20-sd-simos/progress.md`，并由控制器在此汇总。
>
> 已知**可能**触发取代说明的候选（计划期预判，**尚未发生**，不预先编造结论）：
> - `SdCodec` 的 mixin 与 `SimosObjectMapper` 的 `changesetsWithoutDerivedPredicates()` 是否**双重处理** `empty`（既有两者冗余，spec §一.3 第 4 点与 `SimosObjectMapper` 类注均记此冗余为**有意保留**）；
> - `SdState` 的字段名与 `SdChangeSet` 组件名在实现期微调（spec §十四 假设 5 明示"字段名可微调"）；
> - `MutationGuard` 的注册 API 形状（见 §六 待裁）。

---

## 五 纪律与门禁

### 五.1 五条铁律（对 SDSimos 的落点）

1. **稳定实体**：sd 的每个 ID 都是"裸值 `toString` + `static parse`"；`sd:` 地址可解析（A2/A4）。
2. **唯一写路径**：sd 全部写走 `Command → ChangeSet → Revision`；`SdCommandDrain` 也只经 `CoreSimos.submit`；★ **不新增绕过 `submit` 的写面**（`bootstrapGenesis` 是唯一既有例外，**本计划不动**）。
3. **只拥有自己的数据**：sd 的写入**从不直接改** unit/map/social；跨模块改动走命令（C5 drain）。
4. **Core 只组合调度**：Core **编译期看不见 sd**（A1 enforcer）；`MutationGuard` 是 `util.spi` 的**不透明策略**，Core 只按 `commandType`/`payloadJson` 转发（A6）。
5. **变更集派生 + 往返**：`SdChangeSet` 从完整 `SdState` 派生（10 组件一一对应），`assertRoundTrip` 守卫（A3 / C6）。

### 五.2 模块依赖硬约束（enforcer）

- `simos-sd` 可依赖 `util` + `map` + `social` + `unit` + **`agentlib-mosire`**（R5）；**禁** `simos-core` / `simos-app`（A1 的自有 enforcer）。
- `simos-core` **main scope 禁 `simos-sd`**（A1 加进 core 的 `excludes`）；领域模块只在 test scope（既有 `includes` 机制）。
- `simos-app` **不设 enforcer**（组合根）。
- ★ 四个上游模块（util/map/social/unit）的 enforcer **零改动**（sd 是它们的下游）。

### 五.3 门禁

- 每任务：`./mvnw -q spotless:apply` → 定向 `-Dtest=…` → **合并后主树 `./mvnw clean verify`**。
- **关账前** `./mvnw spotbugs:check`（`mvn test` 不跑 SpotBugs）。
- **跑门禁就跑 `verify`，不直调单点 goal**（`spotbugs:check` 直调不跑生命周期、不编译，会在没编译过的树里 rc=0 静默通过）。
- `clean verify` **前台跑**（后台会被内存守卫杀）；看到"绿"先问它**分析了几个类**。
- 前端门禁：若某任务**新增/改动前端**，`run-gate.cjs` 与 `gate-contract.test.cjs` 的**下界两处都要改**，且新文件进 `REQUIRED_FILES`。★ 本计划大部分任务**零前端改动**（前端保持 **88/88**）；仅 **D5** 若引入 GUI 渠道前端页面才需要同步。

### 五.4 证据与变异纪律（九道门禁）

见 §〇。★ 特别重申：
- **读 surefire 数字必须先跑干净轮，并核对报告 mtime 落在本轮内**；不拿"上次留下的绿/红"当本轮结论。
- **mtime 不是"字节变了"的判据**——`cp` 重写会更新 mtime 而不变字节；判据是 **md5**。
- **装置的每份产物都要自指**（把本轮 md5 追加进日志本身）；核对脚本**先断言自己读到非空**再下结论。
- **红了要问"为什么红"**（红的理由必须是**被保护的那行**）；**没红也要问"为什么没红"**（空输出可能只是**没跑到**）。
- **拒收判据与接收判据都要有一条真样本**（Maven 3.9 的 `BUILD FAILURE` 是 `[INFO]` 不是 `[ERROR]`）。

### 五.5 Git 纪律

- **不 `git add -A`**；`git add` **显式路径**；提交前扫 `git diff --cached`（本仓有 `target/`、证据日志、`.serena/project.local.yml`、`.omo/`）。
- `.superpowers/sdd/**` 本机被 `.superpowers/sdd/.gitignore` 忽略 ⇒ 要入库 `git add -f`；**换机器先 `git check-ignore -v`**。
- **不加 `Co-Authored-By`**；**该推就推**。

---

## 六 风险与挂起

### 六.1 ★ 待裁（本计划不替用户决定；给建议）

| # | 待裁项 | 建议 | 影响任务 |
|---|---|---|---|
| G1 | **国家区域 tag 的字符串约定**（spec R13 只说"带特定 tag"） | `"nation:" + nationId`，`NationTag` 常量集中一处；`CreateNation` 与 `RegionDeleteGuard` 引用同一常量 | A4 / A6 |
| G2 | **`MutationGuard` 的注册 API 形状**（spec §九 只说"已注册 guard"） | 照既有 `register(CommandHandler)` 形制加 `CoreSimos.register(MutationGuard)`；封存时收全量传给 `CommandBus` 构造器 | A6 |
| G3 | **N5 order-latency 的"每命令一个延迟值"字段落点**（spec §〇.3 只定"每命令一个值"） | 放 `Effect.readyAtTick`（延期效果本就带"何时可发生"）；**v1 不引入 Command Ops 数值** | C4 |
| G4 | **`DecisionChannel.available()` 是否强制**（spec §十三.1 明说"不强制、列为待定"） | v1 作为**可选**方法（默认 `true`），装配层可 `markUp` | D5 |
| G5 | **GUI 决策渠道的端点形态**（spec §十三.3 只说"GUI 各一实现"） | `POST /api/sd/decision`（body 含 actor + 决策草稿），经 redaction 层取视图 | D5 |
| G6 | **drain 幂等键的确切形态**（spec §五.3 只说"幂等"） | `(effectId, drain 轮次)` 或 `effectId + revision`；实现期定，测试钉住"不重复提交" | C5 |
| G7 | **AI 断点输出 schema 的字段名**（spec §八.2 是设计形状） | 实现期可微调（spec §十四 假设 7） | D2 / D7 |

### 六.2 挂起 / 不实现（spec §十二 + 本计划识别）

| 项 | 处置 | 依据 |
|---|---|---|
| **跨 revision 的 drain 原子性** | 挂起；v1 以幂等 + 可重放补偿 | spec §五.3 / §十二 |
| **`SdInfoEntry.value` 结构化值的往返** | 挂起；v1 只覆盖标量（M4 裁定 38 同口径） | A5 / spec §十四 |
| **`RECOVERABLE` 回池速率** | 挂起（v1 只记类别） | spec §〇.3 / N3 |
| **HQ 积压 / 饱和** | 挂起（v1 order-latency 每命令一个值） | spec §〇.3 / N5 |
| **per-session MCP 身份** | 挂起（现状无 per-session） | spec §七.3 / §十二 |
| **`LlmClient` 真实后端 / key / 重试 / 超时** | 挂起（归 app；本计划只用 `FakeLlmClient`） | N10 / spec §十二 |
| **外部 MCP 桶是否收窄通用写** | 挂起（v1 保留现状） | D6 / spec §八.3 |
| **CRT / Lanchester 具体数值真值** | **不引入**（只借机制形状） | spec §十二 |
| **EBO / EBAO 框架** | **不认**（只借词汇） | spec §十二 |
| **全局 `InfoSystem` 写路径（选项 b）** | **不做**（R14 选 sd 自有 ChangeSet） | spec §十二 |
| **`agent:` 命名空间的 resolver** | **不新建**（决策人走 `sd:`，N15） | spec §十二 |
| **撤销 / 重做** | **不做**；要撤销就回退分叉 | spec §十二 / M8 Q1 |
| **预制 command / "决策立刻生效"的 Command** | **不做** | spec §十二 |

### 六.3 主要风险

- ★ **C 的 B 前置**：姊妹计划未落盘 ⇒ C 阻塞。**先落 B 再开 C**。
- ★ **Shell.java 热点**：几乎每个任务都改它 ⇒ 强串行；建议 `SdWiring` 助手收拢（仍串行）。
- ★ **判决/LLM 判据的假绿**：若 `FakeLlmClient` 的调用计数没被断言，N7 可能变成装饰 ⇒ **计数必须是非零基线可区分的**（先在推进中计到 >0，再在 Replay 中断言 =0）。
- ★ **redaction 的判别力**：只断言"两响应不同"不够 ⇒ 必须同时有"**去掉插桩则相同**"的反向自证。
- ★ **`SdState` 的 5 条构造期不变量**与 spec §三.1 逐条对齐；漏一条即铁律 5 的缺口。

---

## 附：门禁数字推演表（计划期，以实测为准）

| 任务 | 总用例 | util | map | social | unit | core | sd | app | 模块 | BugInstance × | 前端 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 基线 | 987 | 170 | 362 | 45 | 131 | 169 | — | 110 | 7/7 | 6 | 88/88 |
| A1 | 987 | 170 | 362 | 45 | 131 | 169 | 0 | 110 | 8/8 | 7 | 88/88 |
| A2 | 999 | 170 | 362 | 45 | 131 | 169 | 12 | 110 | 8/8 | 7 | 88/88 |
| A3 | 1007 | 170 | 362 | 45 | 131 | 169 | 20 | 110 | 8/8 | 7 | 88/88 |
| A4 | 1017 | 170 | 362 | 45 | 131 | 169 | 30 | 110 | 8/8 | 7 | 88/88 |
| A5 | 1023 | 170 | 362 | 45 | 131 | 169 | 36 | 110 | 8/8 | 7 | 88/88 |
| A6 | 1037 | 170 | 362 | 45 | 131 | 174 | 42 | 113 | 8/8 | 7 | 88/88 |
| C1 | 1047 | 170 | 362 | 45 | 131 | 174 | 52 | 113 | 8/8 | 7 | 88/88 |
| C2 | 1052 | 170 | 362 | 45 | 131 | 174 | 57 | 113 | 8/8 | 7 | 88/88 |
| C3 | 1059 | 170 | 362 | 45 | 131 | 174 | 64 | 113 | 8/8 | 7 | 88/88 |
| C4 | 1071 | 170 | 362 | 45 | 131 | 174 | 74 | 115 | 8/8 | 7 | 88/88 |
| C5 | 1077 | 170 | 362 | 45 | 131 | 174 | 74 | 121 | 8/8 | 7 | 88/88 |
| C6 | 1081 | 170 | 362 | 45 | 131 | 174 | 74 | 125 | 8/8 | 7 | 88/88 |
| D1 | 1091 | 170 | 362 | 45 | 131 | 174 | 84 | 125 | 8/8 | 7 | 88/88 |
| D2 | 1099 | 170 | 362 | 45 | 131 | 174 | 92 | 125 | 8/8 | 7 | 88/88 |
| D3 | 1109 | 170 | 362 | 45 | 131 | 174 | 98 | 129 | 8/8 | 7 | 88/88 |
| D4 | 1123 | 170 | 362 | 45 | 131 | 174 | 104 | 137 | 8/8 | 7 | 88/88 |
| D5 | 1135 | 170 | 362 | 45 | 131 | 174 | 110 | 143 | 8/8 | 7 | 88/88 |
| D6 | 1143 | 170 | 362 | 45 | 131 | 174 | 110 | 151 | 8/8 | 7 | 88/88 |
| D7 | 1148 | 170 | 362 | 45 | 131 | 174 | 110 | 156 | 8/8 | 7 | 88/88 |
| E1 | 1148 | 170 | 362 | 45 | 131 | 174 | 110 | 156 | 8/8 | 7 | 88/88 |

★ **本表是计划期推演**（按各任务声明的用例数累加），**不是实测**。执行期以 `./mvnw clean verify` 输出为准；出现偏差**记入台账**，**不要拿本表当证据**。
★ `map`/`social`/`unit` 三个模块本计划**预期零改动**；若某任务因 `MutationGuard`（util）或 Read 工具（app）牵连到它们，**以实测值为准**。
