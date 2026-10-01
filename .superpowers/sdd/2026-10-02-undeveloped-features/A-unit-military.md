# A 线调查报告：军事 / 单位 / 战斗缺口（2026-10-02）

- 调查性质：**只读**。未改代码、未写世界、未跑 Maven/构建/测试、未调用运行中世界（本报告是唯一产物）。
- 代码基线：`/home/cna/SimulatorMosire` @ `aff48be9`（`refactor/class-first-economy`，`git status` 干净）。
- 方法：读源码 + `rg --hidden --no-ignore` 全树检索；世界事实引用 `/home/cna/simos-testspace/worlds/dashu-v2/logs/*`（单行 JSON 记为 `:1`）。
- 证据口径：`文件:行号` 均相对仓库根；每条结论后的「关键片段」为摘要，不是全文。
- ★ 先修正一处台账措辞：清单说「缺 `simos.command.submitBatch`」——**Core 里已有原子批 API**（`simos-core/.../CommandBus.java:236-265`、`CoreSimos.java:207-213`），缺的是**通用 MCP 工具面**（全树 `simos.command.submitBatch` 0 代码命中，只有若干专用组合工具在内部调用它）。详见 A7。

## A1. 部众 / 兵力转移命令缺失
- 结论：**确认缺失**。`unit.MergeFormation` / `unit.SplitFormation` 只改编制树（parent/attached），**不合并、不拆分 member/equipment**；没有 `unit.TransferMembers` / `unit.MergeStrength` / `unit.SplitStrength` 或等价命令。
- 证据：
  - `simos-unit/src/main/java/io/mosire/simos/unit/spi/MergeFormationHandler.java:18-23,51`：载荷只有 `childId,parentId`，处理就是 `UnitOperations.mergeFormation(...)`；没有任何人数/装备参数。
  - `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java:1032-1068`：`mergeFormation` = 同格判（1037-1056）+ 子必须 MOVING（1057-1060）+ `attachSubtree`（1061）+ **只把存活方 speed 改成整支 min**（1062-1068）。全文无 `member`/`equipment` 运算。
  - `UnitOperations.java:1407-1433`（`copyFormation`）：只改 parent/position/attached/offset，`unit.member()` / `unit.equipment()` 原样带过——编入后人数表不动。
  - `simos-unit/src/main/java/io/mosire/simos/unit/spi/SplitFormationHandler.java:19-24,52-57`；`UnitOperations.java:979-1000`（逐个 `detachUnit`）；`UnitOperations.java:875-888`：detach 只落一条 `attached=false`，后代的 parent/兵力都不动。
  - 命令面：运行世界快照 `/home/cna/simos-testspace/worlds/dashu-v2/logs/command_catalog.txt:1` 的 `types` 无上述三命令；主树 `rg -F` 对 `unit.TransferMembers|unit.MergeStrength|unit.SplitStrength` = **0 个代码文件**（仅清单文档自述）。
  - 唯一"带守恒的人员移动"是 GOV 专用：`simos-app/src/main/java/io/mosire/simos/app/tools/write/GovAbsorbUnitPlan.java:28-29,96-110` 明确拒 `ArmyFormation` 源与 `GovFormation` 源，只收**无 module 的纯人员单位**，目标是带 `GovFormation` 的 GOV（`unit.RecruitStaff` 改 roster，不是 unit.member）。
- 现状可用 workaround：
  1. **整队 attach/reparent**：`unit.AttachUnit`（`AttachUnitHandler.java:18-24`，级联、同格）/ `unit.ReparentUnit` / `unit.ReparentSubtree` / `unit.MergeFormation`——只搬编制，不并人；
  2. **GM 手工加减**：`unit.CreateUnit` 建目标 + `unit.SetStrength` / `unit.ApplyCasualties` 改源；多 revision、无守恒（见 A2/A7）；
  3. **纯人员 → GOV**：`simos.gov.absorbUnit`（批内 ApplyCasualties + RecruitStaff + 可选 DisbandUnit + PutInfo，`GovAbsorbUnitTool.java:208` 一条 revision），但目标必须是 GOV、源必须无编制，**不能并入 Army**。
- 若实现：建议命令（对齐现有命名与"纯函数 op + handler + CommandTargets"三层）：
  - `unit.TransferMembers`：`{fromId, toId, personnel(≥1), equipment?{键:正整数}, requireSameHex?(缺省 true)}`——同一条 handler 内同时改两个 Unit，源减、目标加，`UnitChangeSet.between` 一条 revision；
  - `unit.MergeStrength`：`{sourceId, targetId, disbandSource?(缺省 false), takeMinSpeed?(缺省 true)}`——把源**全部剩余** member/equipment 并入目标，可选解散空源；
  - 触及文件：`UnitOperations.java`（新增纯函数，复用 `copy`/`withUnit` 的 16 参 canonical 拷贝）、`simos-unit/.../spi/TransferMembersHandler.java`、`MergeStrengthHandler.java`、`Shell.java` 的 handler 列表（unit 段约 `:461-480`）、`CatalogTool.java` 的 `PAYLOAD_HINTS`（构造期强制覆盖，缺项即抛，`:416-426`）、`SimosToolSource.java` unit 窄工具段（`:452-473`，建议 GM 桶）；
  - ChangeSet/Codec：**不新增 Unit 字段** ⇒ `UnitChangeSet.between` / `UnitCodec` / 往返不变式零改动（两个单位同属 `unit` namespace，单命令=单 revision，`CommandBus.java:716-731`）；守恒式写进 op 并在测试期做变异；
  - 命令目标：handler 实现 `CommandTargets`，返回 `[fromId,toId]`（两条都判，照 `AttachUnitHandler.java:28-34`）；
  - GmOnly：建议给 `MergeStrength`/`TransferMembers` 标 `GmOnlyCommand`（`simos-util/.../GmOnlyCommand.java:3-17`）或至少打 GM 窄工具；理由同 A7——现存 `SetStrength` 可被决策令嵌入。
  - MCP：`unit.TransferMembers` / `unit.MergeStrength` 各一条 GM 窄工具；前端 `/api/command`（`GuiServer.java:768-772`）与 `simos.command.submit` 自动可见，无需新 GUI 端点。
- 依赖与风险：同格要求可能让"异地并兵"不可达（是否允许异地由裁定；现 attach/merge 都硬要求同格，`:821-841`）；装备键在目标侧可能不存在（要定义"新建键/拒未知键"，现 `ApplyCasualties` 是**拒未知键**，`:175-179`）；`fromId==toId`、源不足、int 溢出要具名拒；编制树与人数是两套语义，转移**不应**顺带改 parent/attached；若转移后源为 0 是否自动解散是独立选择。
- 未核实项：没有审计当前世界 83 个单位的实际 member/equipment 分布（依赖给定调查日志）；没有检查历史提交里是否曾有过被删的转移命令；`GovAbsorbUnitPlan` 的守恒只覆盖"纯人员→GOV roster"这一条特殊路径，没有对 Army 的等价实现。

## A2. 按人数拆兵 / 留守备：无原子命令
- 结论：**确认缺失**（没有"从既有单位按人数拆出新子单位"的单命令/单 revision 原子原语）；存在多条**部分替代路径**，但都不能从既有 Army 扣人后原子地落到新单位。
- 证据：
  - `UnitOperations.java:114-129`（`setStrength`）只把入参写进 Unit，**无上界、无守恒**；唯一硬约束是 `Unit.java:80-83` 的 `member ≥ 0`、`:103-105` 的字段合法性。
  - `UnitOperations.java:154-201`（`applyCasualties`）只允许 `≤0` 增量且有逐项上界（人员 `:161-164`、装备 `:183-186`），**只能减不能增**。
  - `simos-unit/src/main/java/io/mosire/simos/unit/spi/CreateUnitHandler.java:59-121`：`member`/`equipment` 是创建入参，**与任何源单位无关**（没有"从 X 扣 N"字段）；`position` 与 `parent` 二选一/同格规则见 `:75-95`。
  - 多命令 workaround 的 revision 事实：`simos-app/.../tools/write/CommandSubmitTool.java:96-110` 一个信封只提交一条命令；`CommandBus.java:716-731` 的 `commit` 把变更集包成**单 namespace** `WorldChangeSet(Map.of(namespaceOf(type), changeSet))`；被拒/冲突不落 revision（`:628-641`），但**已提交的前序 revision 不会回滚**。
  - 部分路径 1（新建 + 抽人口，四片同批一条 revision）：`RaiseUnitPlan.java:33-43`、`RaiseUnitTool.java`（`unit.CreateUnit + actor.AdjustAccounts + social.SeedGroups + sd.PutInfo`）——它是从**区域人口**抽人，不是从既有 Army 转移。
  - 部分路径 2（GM 直接建军，任意 member/装备，unit+sd 同批）：`SpawnArmyPlan.java:28-37`（同批 `unit.CreateUnit → [unit.SetArmyFormation] → sd.CreateArmy → sd.PutInfo`；GM 特权不抽人口，member 直接写）、`SpawnArmyTool.java:432`（`core.submitBatch`）。
  - 部分路径 3（带守恒的 count 转移，但目标是 GOV roster）：`GovAbsorbUnitPlan.java:23-26` 批顺序、`:31-32` 守恒说明、`:219-221` 构造期互校。
  - 现存"拆分"`unit.SplitFormation` 只看既有子节点（A1），`unit.AttachUnit` 要求同格且**不改人数**（`UnitOperations.java:821-841`）。
- 现状可用 workaround（"留少量守备"）：`unit.CreateUnit` 建守备队（member=N）+ 主军 `unit.SetStrength`/`unit.ApplyCasualties` 减员 + `unit.AttachUnit` 挂树。三种失败形态：
  1. 先建后减：守备已存在、主军未减 ⇒ 兵力总量虚增；
  2. 先减后建：主军已减、建队失败 ⇒ 兵力凭空损失；
  3. 任意一步因 expectedRevision 冲突/载荷被拒而中断 ⇒ 半成品，且 `ChangeSet` 是绝对值差分，只能靠再发逆命令人工补救。
- 若实现：建议 `unit.SplitStrength`：`{sourceId, newUnitId, name, personnel(≥1), equipment?{键:正整数}, parentId?, position?, status?, speed?, mobilityPerMille?}`；语义 = 同一个 `UnitState` 里源减、新建目标（复用 `UnitOperations.create` 的 16 参构造与同格校验），返回一个 `UnitChangeSet` ⇒ 一条 revision；可选 `disbandSourceIfEmpty`。另建议 `unit.MergeStrength`（A1）作为反向命令。文件/注册/catalog/工具面同 A1；ChangeSet/Codec 零改动。新单位 `visionRadius` 只能取默认 1（`CreateUnitHandler.java:113-114`；除非同时做 A6）。
- 依赖与风险：与 A1 共用守恒/装备键/同格口径；新单位编制 module 默认空（要建 Army 需同批 `unit.SetArmyFormation`/`sd.CreateArmy`，属跨命名空间 ⇒ 若一条命令不够，走 app 组合工具 `submitBatch`，`CommandBus.java:265-355`）；新单位的 jurisdiction/编制/速度等 16 个组件必须逐项显式给全（本仓最贵教训形态，`Unit.java:26-43`、`UnitOperations.java:1399-1406`）。
- 未核实项：没有在世界里实际试跑 workaround；没有验证"守备队"需要的默认状态/速度；没有检查 `simos.economy`/`actor` 是否有账户/粮饷守恒要求（拆兵本身不碰账）。

## A3. 休整（RESTING）语义
- 结论：**部分具备**。`RESTING` 是持久三态之一，但**只有两个消费面**：速度因子 500‰ + 阻断 merge/自动回归；**没有**恢复、士气、补给、训练、readiness 模型，也没有"休整数日"的到期效果。
- 证据（主树全部 `UnitStatus`/`status()` 读取点）：
  - `simos-unit/.../UnitStatus.java:10-26`：三态与 `factorPerMille()`（MOVING=1000/RESTING=500/ENGAGED=250）。
  - `Unit.java:125-127`：`effectiveSpeed = max(1, speed×factor+500 / 1000)`。
  - `UnitOperations.java:238-264`：`planRoute` 把 `state.formationSpeed(root,at)` 冻进 `Movement.speedAtDeparture`（`:262-263`）——RESTING 只影响**此后新下的路线**；`SetStatusHandler.java:20-22` 明说在途不回溯（P6）。
  - `UnitState.java:178-185`：`formationSpeed` 取整支 `effectiveSpeed` 最小值（休整的慢下属会拖慢整支）。
  - `UnitOperations.java:1057-1060`：`mergeFormation` 要求 child 是 MOVING，否则拒——RESTING 不能合体。
  - `UnitOperations.java:1223-1252`（`rejoinRoute`），`:1234-1235`：非 MOVING 直接返回 empty——RESTING 不自动回归（引用保留，回到 MOVING 后恢复）。
  - 除此之外：`UnitTimeParticipant.java:212,233` 与 `UnitMoves.java:72` 只是把 status 原样拷过 16 参构造；`simos-sd` / `simos-army` 主源码零 `UnitStatus` 读取。
  - 状态字段本身：`Unit.java:45-61` 的 16 个组件里没有任何 morale/readiness/supply/training；全树 `rg -ni 'morale|readiness|training|recover'` 在 unit/sd 主源码只命中文档性文字与 `LossClass.RECOVERABLE`（后者"只记类别、不设回池"，`RecordCasualtiesHandler.java:38-39`）。
- 现状可用 workaround：`unit.SetStatus` 切 RESTING/ENGAGED；"休整数日"只能 GM 到点再切回 MOVING。`sd.RegisterEffect(SCHEDULED + enqueue_unit_command unit.SetStatus)` 理论上可到期自动切，但**生产路径不 drain**（见 A4 证据：`AdvanceTool.java:121` 与 `GuiServer.java:1435` 都直接 `core.submit(AdvanceTime)`；`Shell.advanceAndDrain`（`:1121-1126`）在全仓**没有调用者**），所以效果只会被 `SdTimeParticipant` 标成 FIRED，不会落成 unit 命令；要落地必须 GM 手工提交或改 drain 接线。
- 若实现（两条路线，建议先裁后做）：
  - 路线 1（小、先解锁国策）：不改状态形状，新增 `unit.RestUntil {unitId, untilTick}` 这类"命令 + TimeParticipant 自动复原"，并在 `UnitTimeParticipant` 里读 RESTING + 到期 tick 自动回 MOVING；或修 drain 接线（把 `Shell.advanceAndDrain` 接进 `AdvanceTool`/`GuiServer` 的 advance 路径，注意 `SdCommandDrain` 自己已声明"跨 revision、不假装原子"，`SdCommandDrain.java:31-32`）。
  - 路线 2（完整 readiness）：给 `Unit` 加第 17 个组件（如 `Optional<Readiness>`：moralePerMille/supplyDays/trainingPerMille），`UnitTimeParticipant` 逐日恢复并消耗补给。代价：`UnitCodec` 线格式、`UnitChangeSet`、全部 `new Unit(...)` 拷贝点（`UnitOperations.java:1368-1560` 有 7 处 canonical 拷贝，另有 `UnitTimeParticipant`/`UnitMoves` 等）和兼容构造器都要加字段；铁律 5 的往返测试是唯一护栏。
- 依赖与风险：`RESTING` 当前**不阻断收新路线**（只是半速），若"休整=不能动"需要另加 op 闸（影响 `planRoute` 的语义与既有测试）；补给要接 actor/economy 账（当前 unit 与粮账没有绑定）；"休整数日"若用 sd 效果，跨模块原子性仍受 A4 的限制。
- 未核实项：没有在世界里实际跑 advance 观察 RESTING；没有读 live DB 确认现有单位状态；没有检查 `simos-app/.../world/WorldgenInitializeTool.java:185` 初建 RESTING 的后续是否有人工切回流程。

## A4. 攻城 / 炮击 / 城防 / 工事
- 结论：**部分具备（仅抽象交战数据面）+ 领域语义确认缺失**。没有 `sd.Bombard` / `unit.Siege` / 城墙/城防/工事/火炮模型；现有 combat 五命令只维护数据与阶段链，**不自动选结局、不自动造成伤害**，且 `OutcomeOption.casualties` / 权重 / `minDurationTicks,maxDurationTicks` 全是**只存不读**。
- 证据：
  - 语义不存在：主树对 `sd.Bombard|unit.Siege|bombard|siege|artiller|fortif|城墙|城防|攻城|工事|火炮|炮击` 的代码检索 0 命中（仅清单文档）；运行目录 `command_catalog.txt:1` 无对应 type。
  - `simos-sd/.../spi/CreateCombatHandler.java:48-58`：只建 `Combat(id,name,stages=[],participants,finalOutcome=empty)`，participants 必须已存在；无自动开战。
  - `simos-sd/.../spi/AddStageHandler.java:66-95`：首阶段同时建 `CombatState`（hex + currentStage）；`:58-64` 只拒"后续阶段再给 combatStateId/hex"。阶段只是 entry/exit 触发条件 + outcome 表（`CombatStage.java:20-27`；`Trigger.java:21-30` 七种触发器）。
  - `SetOutcomeTableHandler.java`：只替换 outcome 表；`CommitOutcomeHandler.java:58-80`：GM/裁决者**手工**选一个表中结局，已选过不覆盖；**没有按 weight 抽样/掷骰**（主树 `.weight()` 在 sd 无读取点）。
  - `RecordCasualtiesHandler.java:66-102`：只做上界校验（`:108-122`）后写 **sd 自己的** `LossRecord` 与 `CombatState.losses`，**完全不改 unit.member/equipment**（`:98-102` 只构造 `SdState`）。
  - `SdTimeParticipant.java:94-158`：逐日自动做的只有两类——effect 触发（`:98-120`）与**当前阶段 exit 全满足时切下一阶段**（`:122-157`）；它不选结局、不掷 outcome、不减兵。
  - `OutcomeOption.java:11` 有 `casualties: CasualtySpec`，`SdPayloads.java:306,312-319` 会解析它，但主树 `\.casualties\(\)` 在 sd/app **0 读取点**；`CombatStage.min/maxDurationTicks` 除 `SetOutcomeTableHandler.java:78-79` 的拷贝外也无读取点。
  - "自动接战/自动结算"缺失的接线证据：`Action.EnqueueUnitCommand`（`Action.java:13-14,66-77`）依赖 app 层 `SdCommandDrain`（`SdCommandDrain.java:25-32,46-88`）；但 MCP `simos.advance` 是 `AdvanceTool.java:121` 的 `core.submit(AdvanceTime)`，GUI `/api/advance` 是 `GuiServer.java:1435` 的 `core.submit(AdvanceTime)`，都不调 `Shell.advanceAndDrain`；全仓 `advanceAndDrain` 除定义外 **0 个调用者**（`git grep` 仅历史 evidence 副本命中）。⇒ 连"用 `sd.RegisterEffect` 排一条 unit 命令"这条路在生产路径也不会自动执行。
  - 跨模块写限制：`CommandBus.java:716-731` 一条命令只允许**一个 namespace**；"改 unit 兵力 + 记 sd 战损 + 更新 combat"不可能由单条 sd 命令完成，必须 app 组合工具走 `submitBatch`（如 `AdjudicateTickTool.java:397`）。
  - "城防/工事"没有可落字段：`RegionMeta` 只有 `color/tag/description/annexedBy` 四个字符串（`simos-map/.../region/RegionMeta.java:8`）；`SocialCity.props` 是自由 `Map<String,Object>`（`SocialCity.java:26-28`），`social.UpdateCity` 可合并写 props（`UpdateCityHandler.java:20-27,76-80`），但**没有任何战斗/攻城代码读它**。
- 用现有抽象路径表达"轰城"的完整步骤（全部 GM 手工）：
  1. `sd.CreateCombat {combatId,name,participants:[攻方,守方或空]}`；
  2. `sd.AddCombatStage`（首阶段）给 `combatStateId` + `hex{q,r}`（如 34,-55）+ stage entry/exit（可用 `at_or_after_tick` / `after_ticks` / `unit_at_hex` / `threshold_kills`）+ outcomes（可带 `casualties`，但不会被读）；
  3. 需要时 `sd.SetStageOutcomeTable` 改表；
  4. `simos.advance` 只会按 exit 触发器**自动翻到下一阶段**（不会选结局）；
  5. `sd.CommitCombatOutcome` 手工选定结局（每场仅一次）；
  6. `sd.RecordCasualties` 记损失（有上界校验，但不改 unit）；
  7. 逐单位 `unit.ApplyCasualties` 真正扣人/扣装备（或用 `sd.RegisterEffect` 入队，但如上**不自动 drain**）；
  8. `sd.PutInfo` 记叙事/城防毁损。
- 若实现：
  - 最小解锁（建议先做）：app 级 GM 组合工具 `simos.sd.bombard`（或 `simos.sd.resolveCombat`），在**一个 `submitBatch`** 里固定顺序提交 `sd.CreateCombat → sd.AddCombatStage → sd.CommitCombatOutcome → sd.RecordCasualties → 每个单位 unit.ApplyCasualties → sd.PutInfo`；GM 给结局 id 与逐单位损失，工具用同一份数值同时写 LossRecord 与 unit（守恒、可回滚到批前 revision）。它不新增领域状态，直接把"轰城"变成一个可重复的原子动作；参考 `GovAbsorbUnitTool`/`AdjudicateTickTool` 的批装配与 `CommandTargets` 判权。
  - 完整模型：新增 `Fortification`/`Defense` 实体（城墙值、耐久、火炮/攻具类别），落在 map region/social city 或新模块；新增 `unit.Siege` / `sd.Bombard` 或 `simos.army.bombard` 命令；消费 `OutcomeOption.casualties` 与权重（需要随机源，当前 sd 无随机数使用）；把 A5 的装备目录作为"炮/攻具"语义前提。若加 State 字段：对应模块 `*ChangeSet`/`*Codec`/snapshot 与铁律 5 往返测试全部要改；跨模块伤害仍须 app 批。
  - 自动结算可选：在 `SdTimeParticipant`/新 participant 中按 outcome 表 + 随机源选结局、生成 `RecordCasualties`，再经 `SdCommandDrain` 落 unit（同时修 drain 接线）。
- 依赖与风险：不修 drain 接线，任何 `enqueue_unit_command` 都不会自动执行；`OutcomeOption.casualties` 现在是"看起来有语义、实际不读"的假声明风险点（容易让模型误以为已生效）；跨 namespace 单命令被 `CommandBus` 结构性禁止，别把 `sd.Bombard` 设计成单命令改 unit；随机/掷骰会引入"回放不重跑 LLM"之外的随机复现问题（当前 verdict 是冻结数据，`VerdictFreezer` 思路可借用）。
- 未核实项：没有实际在世界里创建 combat 或推进；没有读 live DB 检查是否已有 combat 记录；没有验证 GUI combat 视图（`ApiViews.java:5211+`）对 stage 的展示与工具读口是否一致；没有确认运行中世界 (34,-55)/(31,-60) 的城市/守军现状（依赖给定日志）。

## A5. 装备无目录 / 语义
- 结论：**部分具备**。`equipment` 是 `Map<String,Integer>` 自由键值（形状校验 + 保序冻结），有 3 条写路径与 2 个读口；**没有装备目录、类别、炮/攻具/甲胄语义校验**。
- 证据：
  - 形状：`simos-unit/.../Unit.java:51`（record 组件）、`:321-336`（`copyEquipment`：键非空白、值 ≥0、`Collections.unmodifiableMap` 冻在赋值处）。
  - 写路径 ①：`CreateUnitHandler.java:70,97-119`（创建时整表给入）；②：`SetStrengthHandler.java:20-21,46-47`（**整份替换**，可新增任意键）；③：`ApplyCasualtiesHandler.java:20-21,49-50` + `UnitOperations.java:154-201`（**只减**，提及未知键 ⇒ 拒，`:175-179`）。
  - `sd.RecordCasualties` 也会读装备并做同样上界校验（`RecordCasualtiesHandler.java:108-122`），但只写 LossRecord，不改 unit。
  - 读口：`simos-app/.../gui/ApiViews.java:5109`（`view.put("equipment", new LinkedHashMap<>(unit.equipment()))`，`simos.unit.get` / `simos.unit.list` 共用）与 `simos-unit/.../resolve/UnitResolver.java:127-152`（三段地址 `unit:<id>:equipment.<名>` → `typeName="Equipment"`）；`UnitAgentAttachPolicy.java:13-14` 明确 Equipment 不可绑决策人。
  - 无目录/语义：主树无 equipment 白名单/类别/校验表（`rg` 只命中上述解析与透出）；没有"从账本抽装备"的路径（`RaiseUnitPlan.java:66-67` 注释：装备只走参数、不从账本抽）。
- 现状可用 workaround：把 `"火炮": 12` 或 `"攻城锤": 3` 当作自由键写进 equipment，再由 GM 在 `sd` combat 叙事/outcome 里当作"有炮"的依据；读侧能在 `simos.unit.get` 里看到。**没有任何规则消费它**，且 `ApplyCasualties` 要求键先在表里（新键必须走 `SetStrength` 整表替换）。
- 若实现：
  - 命令 `unit.AddEquipment` / `unit.RemoveEquipment`（`{unitId, equipment{键:正增量}}`，unit 内守恒：≤现有量）或直接在 `unit.TransferMembers`（A1）里带装备；写侧仍走 `UnitChangeSet.between`，无 Codec 改动。
  - 目录：静态 `EquipmentCatalog`（放 `simos-unit`，或从配置文件加载）定义 `id → {类别 BASIC|ARMOR|ARTILLERY|SIEGE, 攻/防/射程参数}`；在 `UnitPayloads.requireEquipment`/`Unit` 构造期按 catalog 校验（**旧档兼容**：目录外键默认放行 + 显式迁移开关，否则老世界可能启动即拒）。
  - 炮/攻具语义：A4 的 `Fortification`/`Bombard` 读 catalog 里的 ARTILLERY/SIEGE 键计算效果；未装备则拒 `unit.Siege`。
  - 模块/文件：`simos-unit`（catalog + ops + handlers）、`simos-army`/`simos-sd`（消费者）；CommandTargets 返回 `unitId`；GM 窄工具 + catalog `PAYLOAD_HINTS`；前端透出不变（`ApiViews.unit` 已发 equipment）。
- 依赖与风险：更严的校验会与"自由键"现状冲突，必须定义旧档/未知键策略；装备数量目前与人员没有绑定（"1000 人配 12 门炮"没有编制校验）；如果目录要支持"从 actor 商品账扣装备"，那是跨模块（economy/actor）⇒ 只能 app 组合工具 + `submitBatch`。
- 未核实项：没有读 live DB/世界读口确认现有单位的 equipment 实际内容（给定世界日志未列）；没有检查 economy 的 `CommodityId` 是否已有军事商品命名约定。

## A6. 无军队视野半径命令
- 结论：**部分具备**。`visionRadius` 字段、默认值、读取链路都在；**写命令与读口暴露缺失**（`unit.SetVisionRadius` 0 代码命中；`ApiViews.unit` 也不发该键）。
- 证据：
  - 字段与默认：`Unit.java:31-34,59,63-64`（`DEFAULT_VISION_RADIUS=1`，`0`=只看自身格）；构造期 `visionRadius ≥0`（`:103-105`）；兼容构造器一律取默认（`:170-177,210-218`）。
  - 拷贝保真：`UnitOperations.java:1386-1392`、`UnitTimeParticipant.java:216,237`、`UnitMoves.java:76` 等 canonical 16 参拷贝都带 `visionRadius`——**生产拷贝点已齐全**（这也是为什么不能靠漏传手改）。
  - 创建时**不可指定**：`CreateUnitHandler.java:113-114` 写死 `Unit.DEFAULT_VISION_RADIUS`，注释明说"载荷里没有该字段，不凭空发明输入"；且解析器不拒未知键——载荷里塞 `visionRadius` 会被**静默忽略**。
  - 唯一消费算法：`simos-army/.../ArmyVision.java:44-58`（`effectivePosition` + `HexGrid.withinRadius(pos, unit.visionRadius())`，无遮挡、纯半径）；`simos-app/.../access/ArmyScope.java:62-99` 把它投影成 map/social/unit/actor 四命名空间前缀，算不出 ⇒ deny-all。
  - 影响读口（Army 归属 DM）：`DecisionScopeFunctions.java:46-52`（Army ⇒ ArmyScope）→ `DecisionCallerFactory.resourceScopesFor`（`:248-261`）→ ① `sd.AdjudicateTick` 逐令资源预检（`AdjudicateTickTool.java:350`）；② 单个决策人详情 `simos.sd.decision-maker`（`DecisionMakerTool.java:52-58,88-91`，经 `RedactingQueryService.computedScopeOf`）；③ `simos.sd.decision-results` / `simos.sd.verdicts` / `simos.sd.decision-docs` 的脱敏读（`RedactingQueryService.java:119`）；④ GUI `?as=` 视角与决策 Agent 权限组（`GuiServer.java:428-431`）；⑤ 决策运行时的工具权限（`DecisionCallerFactory.permissionsFor`）。
  - **不影响** `simos.sd.decision-makers`（复数）：它直接调 `SdQueryService.listDecisionMakers` 后原样出 `ApiViews.decisionMakers`，不算范围（`DecisionMakersTool.java:61-71`）；且它是 GM-only 读口（`GmOnlyRead`）。
- 现状可用 workaround：无写口。要让某支军队 DM 看到更远，只能改代码里的默认值（全仓影响）或改 DM 配权模型；`sd.SetDecisionMakerAccess` 只能**收紧**（`DecisionCallerFactory.resourceScopesFor` 用 `narrowTo`，`:248-261`），不能扩大。
- 若实现：
  - 命令 `unit.SetVisionRadius {unitId, visionRadius(≥0)}`：`UnitOperations.setVisionRadius`（canonical 16 参拷贝，只换一个组件）+ handler + `CommandTargets→[unitId]`；涉及 `simos-unit/.../ops/UnitOperations.java`、`simos-unit/.../spi/SetVisionRadiusHandler.java`、`Shell.java` 注册、`CatalogTool.java` PAYLOAD_HINTS。
  - **建议标 `GmOnlyCommand`**：它直接放大 DM 的可见范围；若允许嵌入令，会与"`SetDecisionMakerAccess` 只能收紧"的权限模型冲突。
  - 读口 additive：`ApiViews.unit` 增 `visionRadius` 键（旧键不变，照 P4 先例，`ApiViews.java:5103-5140`），`UnitGetTool` 描述同步。
  - ChangeSet/Codec：字段已在线格式里（`UnitCodec` 按 record 组件自动编解码），零格式改动；往返测试不变；风险只在**漏传拷贝点**（新增写路径必须走 canonical，不要用兼容构造器）。
  - MCP：GM 窄工具 `unit.SetVisionRadius`；`simos.command.submit` 与 GUI `/api/command` 自动可达；`simos.sd.decision-maker` 的 `scope` 计数会随之下一次现算变化（无需缓存失效，范围每次现算）。
- 依赖与风险：视野只影响**Army 归属 DM**的授权面；Gov/Nation DM 不受益（它们的 scope 来自 jurisdiction/region tag）。跨战区"中央政府紧急回应"若继续用 Gov 中央 DM，本命令不解决（见世界日志的外交阻塞，属于 sd DM 建模问题，不在本线）。
- 未核实项：没有读 live DB 确认当前单位实际 `visionRadius`（代码保证新建=1）；旧档理论上可带任意已落盘值；没有验证 GUI 对 scope 计数的展示文案。

## A7. 军力守恒 / 原子性
- 结论：**部分具备**。`ApplyCasualties` 有逐项上界（只减）、单条命令内部原子；`SetStrength`/`CreateUnit` **无上界、无守恒**，且这两条命令目前可被**决策令嵌入**（决策人可给自己的单位凭空增兵）；跨命令 workaround 无回滚，半成品风险真实存在。
- 证据：
  - 无守恒：`UnitOperations.java:114-129`（setStrength 直接写入）、`Unit.java:80-83`（只判 member ≥0）、`CreateUnitHandler.java:69-70`（member/equipment 直接来自载荷）——可任意造兵/裁兵，没有"增减必须来自另一个单位/人口/账"的校验。
  - 局部上界：`UnitOperations.java:154-201`（战损只减 + `|Δ|≤current` + 未知装备键拒）；`RecordCasualtiesHandler.java:108-122` 同样只做上界校验，且**不跨模块写 unit**。
  - 决策令可嵌入造兵命令：`DirectiveWhitelist.java:31-47` 只排除 `sd.*` 与 `simos.command.submit`；`Shell.java:585-591` 的 `directiveCommandTypes = 注册面 − GmOnlyCommand`；`SetStrengthHandler.java:23` / `CreateUnitHandler.java:44` 都**没有实现 `GmOnlyCommand`** ⇒ `unit.SetStrength`（以及 CreateUnit/DisbandUnit）可进 `sd.IssueDirective`。`AdjudicateTickTool.java:350` 按出令 DM 的资源 scope 预检：Army DM 的视野圈内包含自己的军队 ⇒ 可对自己单位 `SetStrength` 到任意值。窄工具桶里的 UnitSetStrengthTool 只在 GM 桶（`SimosToolSource.java:452-456`），但**它挡不住令里嵌入**（★ 措辞漂移：`UnitSetStrengthTool.java:11-12` 的类注仍写"进 GM 桶与决策人桶（用户裁定 D-1）"，而装配处 `SimosToolSource.java:452-456` 只在 GM 桶——回代码核实以代码为准）。
  - 单命令原子：`CommandBus.java:716-731` 一条 Applied ⇒ 一行 revision；`reject`/`conflict` 不落行（`:628-641`）；handler 拿的是同一 base 状态的全部切片，`UnitChangeSet.between(base,target)` 是绝对值差分（`simos-unit/.../change/UnitChangeSet.java:28-43`），回退靠再发逆命令。
  - 跨命令非原子：`CommandSubmitTool.java:96-110` 一次一个信封；Core 的 `submitBatch` 才保证"一批=一条 revision、全成/全不成"（`CommandBus.java:236-263,265-355`；要求同一 branch/expectedRevision，且涉及 namespace 的 codec 必须实现 `ModuleDiffer`，`:470-480`）。当前它**没有通用 MCP 工具**；专用组合工具（`SpawnArmyTool.java:432`、`GovAbsorbUnitTool.java:208`、`AdjudicateTickTool.java:397`）各自写死批顺序。
  - 已有的回滚口只有特定场景：`CoreSimos.submitRestore`（`:215-230`）唯一调用方是 `sd.VoidAdjudication`（作废一次裁决），不是通用 undo。
- 现状可用 workaround：组合写若必须原子，只能用已存在的专用组合工具（raiseUnit/spawnArmy/absorbUnit/levyRegion/adjudicateTick），或把命令写进某 DM 的 `sd.IssueDirective` 再用 `sd.AdjudicateTick` 一批执行——但后者受白名单、DM scope、tick 与审批链限制，不是 GM 通用批。
- 若实现：
  1. 守恒：新增 `unit.TransferMembers`/`unit.SplitStrength`/`unit.MergeStrength`（A1/A2）为唯一合法增减路径；把 `unit.SetStrength`（以及 `unit.CreateUnit` 的 `member` 直写面）标 `GmOnlyCommand` **并**在 op 里加可选守恒模式（如 `mode:"set"|"delta"` + `expectedMember` 乐观校验），至少把"决策令凭空造兵"堵住；
  2. 通用批：新增 GM-only MCP 工具 `simos.command.submitBatch {branch, expectedRevision, commands:[{type,payloadJson}...]}`，内部调现成 `CoreSimos.submitBatch`（同 branch/expectedRevision、逐条 outcomes 返回）；审批门要用**一条** Ask，summary 列出各 type（不能默默把 N 条敏感写塞进一个门）；资源面沿用 GM 的 unlimited，不给决策人桶；
  3. ChangeSet/Codec：以上都是既有字段上的命令，`UnitChangeSet`/`UnitCodec` 与往返不变式零改动；新增批工具也不改 Core（C16 封闭集不变，`submitBatch` 已是 Core 公开 API）。
- 依赖与风险：标记 `GmOnlyCommand` 会改变决策人令的可用命令集（现有世界若已有此类令会在 `AdjudicateTick` 重建白名单时被拒——需检查存量 `Directive`，本次未查）；严格守恒校验可能拒掉配平用的旧脚本/夹具；批工具降低审批粒度，需要安全裁定；"兵力守恒"是否跨 GUID/人口/降卒（新兵从无到有）需要领域裁定，不能只做减法。
- 未核实项：没有查历史 revision / 存量 directive 里是否已有人用 `unit.SetStrength` 造兵；没有读 live DB 对账"LossRecord 累计 vs 各 unit.member 实际值"；没有检查 economy/actor 侧是否有别的直接写 unit 的路径（只审计了 app/core 的写入口）。

## 本线建议的实现批次与优先级（按对当前世界大蜀国策的解锁程度）
1. **批次 1（P0，直接解锁国策 1"北谷残兵打散编入 + 留守备"）**：A1 `unit.TransferMembers` / `unit.MergeStrength` + A2 `unit.SplitStrength`。三条都是 unit 域内、单命令单 revision、零新字段/零 Codec 改动的纯增量能力；同时把 `unit.SetStrength`/`unit.CreateUnit` 标 GmOnly（A7 的最小半边），堵住决策令造兵。验收可直接用当前世界：把一支残兵单位并入 `大蜀-army`、从 `大蜀-army` 拆一个守备队，断言两 revision 前后 `Σmember` 与装备逐键守恒。
2. **批次 2（P0，解锁国策 1 的"休整"）**：A3 最小版——`unit.RestUntil`（或等效"状态+到期"命令）让 RESTING 有恢复语义，并修复 effect drain 接线（否则 `sd.RegisterEffect(enqueue_unit_command)` 在生产路径永不执行）。若裁为完整 readiness 模型，则把新 Unit 组件的 Codec/拷贝点审计一并列入。
3. **批次 3（P1，解锁国策 4"对铁门坎第一次轰城"）**：A4 最小版 GM 组合工具 `simos.sd.bombard`（现有 combat 五命令 + `unit.ApplyCasualties` 同批一条 revision，GM 给结局与损失）+ A5 最小装备目录（至少给"炮/攻具"一个可判定的类别，不强制迁移旧档）。这一步不需要新领域实体就能把"轰城"变成可重复、可审计、守恒的原子动作。
4. **批次 4（P2，解锁跨战区中央/军队紧急回应）**：A6 `unit.SetVisionRadius` + `ApiViews.unit` 读回。它只放大 **Army 归属 DM** 的视野；若"中央政府看不见全国"仍要解，需另立 sd DM 建模（Nation DM / 占领区 tag / Gov jurisdiction 扩张），不在本报告实现建议内。
5. **批次 5（P2/P3，基础设施与完整模型）**：A7 通用 `simos.command.submitBatch`（GM-only）+ 存量令/脚本审计；A4 完整城防/工事实体与自动结算（含随机源、`OutcomeOption.casualties`/权重的真实消费）；A3 完整 morale/supply/training；A5 完整装备目录与账本联动。它们解锁面更广，但不阻塞当前四条国策的最小可执行版本。

## 我没做 / 没验证的
- 未跑任何 Maven/构建/测试/格式化；本报告不含任何"编译/测试通过"的结论。
- 未对运行中世界发任何写请求；也**没有发 HTTP GET**——所有世界事实均来自给定日志文件（`dashu_policy_feasibility_20261002.md`、`command_catalog.txt`）。
- 未读 live DB（`/home/cna/simos-testspace/worlds/dashu-v2/store/`）或 checkpoint，未核对当前 83 个单位的 member/equipment/visionRadius/status 实际值；A5/A6 的世界现状只有代码层保证（新建默认）。
- 未核查 `.claude/worktrees/**` 下的并行工作树（检索时已排除），也未逐条读 git 历史找"曾被删除的等价命令"；本报告只对当前树 + live 命令目录快照下结论。
- 未检查前端 JS/GUI 渲染（`simos-app/.../gui` 的 HTML/JS）对新命令/新读键的展示；只检查了后端 API 与工具面装配。
- 未审计 enforcer/pom 边界对新增 app 组合工具的传递依赖影响（只确认 `simos-army` 依赖面与模块归属）。
- 未验证"新旧世界/旧档"在装备目录、严格守恒、字段扩容下的迁移行为。
- 未做变异自证（本轮是只读调查，写测试留到实现阶段按 AGENTS §三.0 统一做）。
