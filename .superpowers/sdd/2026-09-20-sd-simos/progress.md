# SDSimos 台账（progress）

> 配套 spec：`docs/superpowers/specs/2026-09-20-sd-simos-design.md`（§〇~§十四，已无待裁项）
> 实现计划：`docs/superpowers/plans/2026-09-20-sd-simos-plan.md`（bite-sized，A/C/D/E 分期）
> 姊妹计划（阶段 C 前置）：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md`

## 状态

| 项 | 状态 |
|---|---|
| 计划 | ✅ 已落盘（2026-09-20） |
| 阶段 A（骨架 + Nation + 不可删守卫） | ✅ **已完成（A1~A6）**——A1 ✅ / A2 ✅ / A3 ✅ / A4 ✅ / A5 ✅ / A6 ✅ |
| 阶段 C（Combat，前置 B） | ✅ **已完成（C1~C6，2026-09-21）**——见 `c-stage-report.md` |
| 阶段 D（Directive + 判决 + 渠道） | ✅ **已完成（D1~D7，2026-09-21）**——见 `d-stage-report.md` |
| 阶段 E（关账） | ✅ **已完成（E1，2026-09-21）**——见 `e-evidence/task-final-report.md` |

## 裁定与结论

（随执行累积；格式：`# 任务 / 裁定号 / 结论 / 依据`）

### A1 模块骨架与 enforcer

- **worktree**：`.claude/worktrees/sda1a2`，分支 `sd/a1a2`，基线 `4c0469767571051eb9945af7dce9a364ef88fb20`（计划 §〇 单任务 worktree；派发前已核基线）。
- **A1 结论**：`simos-sd` 落地，reactor **8/8**（父 + 7 模块）；`./mvnw -pl simos-app -am -DskipTests compile` rc=0；sd 的 `excludes` = `{simos-core, simos-app}` 且**不含** `agentlib-mosire`；core main 的 `excludes` 加 `simos-sd`（R2）。
- **裁定 A1-a（执行期取代说明，计划 m2 逻辑缺陷）**：计划 §三 A1 的 m2 写「把 `io.mosire:simos-core` 从 `simos-sd` 的 `excludes` 删掉、并给 sd 加 core 依赖 ⇒ 期望 sd enforcer 报错」。**这不可能报错**——删掉 exclude 正是**解除**禁令（实测 plan-as-written 轮 `rc=0`，日志 `a1-evidence/mutants/m2-plan-as-written/plan-as-written-run.log`）。⇒ 取代为**保留 exclude、只给 sd 加 core 依赖**（这才检验"sd 不得依赖 core"），实测 `BannedDependencies failed`、红点 = `io.mosire:simos-core ... <--- banned`。
- **裁定 A1-b（装置正则假阴性）**：首轮 m1 曾因检测正则写成小写 `bannedDependencies`（日志是 `BannedDependencies`，大写）而**假判"enforcer 未触发"**——命中 0 先怀疑自己的正则（CLAUDE.md 纪律）。已改为 `grep -ci "BannedDependencies failed"`。
- **A1 判据实测**：m1（core 加 sd 依赖）⇒ `BannedDependencies failed`、红点 `io.mosire:simos-sd ... <--- banned via the exclude/include list`；m2 ⇒ `io.mosire:simos-core ... <--- banned`；两轮 `COMPILATION ERROR=0`、md5 逐字节还原相等、接收侧干净树 `BannedDependencies passed=2`。

### A2 数据模型（ID 三件套 + spec §三 全部 record）

- **A2 结论**：落地 **11 个 ID**（含 `CombatStateId`）+ **30 个 model 类型**（spec §三 全部）；定向 `SdIdTest`(2) + `SdModelTest`(11) = **13 条绿**；`./mvnw -pl simos-sd -am verify` rc=0、sd `BugInstance size is 0`、`COMPILATION ERROR=0`。
- **裁定 A2-a（spec §二.1 ID 表漏列 `CombatStateId`）**：§二.1 的表只列 `CombatStageId`/`CombatOutcomeId`，但 §三.1 的 `SdState` 与 §三.3 的 `CombatState` 都用到 `CombatStateId`（计划 A3 的 `SdCodec` 键清单也含它）⇒ **以 §三 为准**，补齐为**第 11 个 ID**。
- **裁定 A2-b（`Condition`/`Trigger` 与 `AtTick`/`AtOrAfterTick` 二名并存）**：spec §三.3 写 `Condition`/`AtOrAfterTick`，计划 §三 A2 写 `Trigger`/`AtTick`。取 **`Trigger`**（与 §三.6 一致）与 **`AtOrAfterTick`**（与 §十一.5 判据原文一致）。
- **裁定 A2-c（spec 未定义字段形状的三个类型，实现期定形）**：`CasualtySpec`（定为非负幅度 + 冻结 map——预期损失规模；真负增量由 `CasualtyDelta` 承载）、`AdjudicationBreakpoint`（定为稳定标识 record，D2 的 `Breakpoints` 以常量引用）、`DisclosurePolicy`/`DirectiveStatus`（取值 spec 未定义，实现期定档）。**凡此均为 spec §十四 假设 5 允许的"设计形状微调"**，未改 spec 正文。
- **裁定 A2-d（sealed 多态预置 Jackson 类型信息）**：`Affiliation`/`Trigger`/`Action` 是 sealed 多态（同 `FieldDelta` 同族），裸往返不可能 ⇒ 现在就把 `@JsonTypeInfo(Id.NAME)` + `@JsonSubTypes` **注解钉在类型上**（A3 做往返时直接可用）。这是计划 A2 未点名、但为下游必需的前瞻性补齐。
- **裁定 A2-e（计划预期 sd +12，实测 +13）**：多出 1 条来自 `SdIdTest` 的"空白/null 全类型拒绝"用例。以实测为准。
- **A2 变异轮（3 个全杀）**：m1 删 `OutcomeOption.weight > 0` 校验 ⇒ `outcomeOptionRejectsNonPositiveWeight` 红；m2 删 `OutcomeTable` 空表校验 ⇒ `outcomeTableRejectsEmptyOptions` 红；m3 删 `CasualtyDelta` 装备负值校验 ⇒ `casualtyDeltaRejectsNonNegativeEquipment` 红。三轮 `COMPILATION ERROR=0`、`Tests run≥1`、surefire 报告 mtime 落本轮、`cp` 逐字节还原相等、日志自指（聚合 md5 `97e27b04e46fd33169692de4de0ccaf5`）。

### A3 `SdState` / `SdChangeSet` / `SdSnapshot` / `SdCodec` + 往返 + 架构计数 5

- **落地**：`sd/state/SdState`（10 组件 + 5 条构造期不变量）、`sd/state/SdSnapshot`、`sd/change/SdChangeSet`（10 组件）、`sd/codec/SdCodec`（第 4 个 `ModuleCodec`，9 个 ID 键反序列化器）；`ArchitectureGuardsTest` 4→5（改名 `...Five...`）；`Shell` 注册 `SdCodec`。
- **裁定 A3-a（`Combat.stages` 形态：`List<CombatStageId>` ⇒ `List<CombatStage>`）**：spec §三.3 写 ID 列表，但 §三.1 的 10 组件里**没有阶段表**，而 §三.1.3/§三.1.4 两条不变量要求从 `Combat` 读得到**阶段内容** ⇒ 阶段对象内嵌进它的交战，组件数仍为 10。（计划 A2 已落 `CombatStage` 类型、A3 计划亦要求这两条不变量——这是把三处对齐的最小改动。）改的是 A2 的 `Combat` 记录一处。
- **裁定 A3-b（`info` 键取 canonical 串，非 `Address`）**：spec §三.1 写 `Map<Address, …>`，但 `FieldDelta` 的键约定是"各 key 类型的裸值 `toString()`"，而 `Address`（util 类型）**没有**重写 `toString`，且**既有用例把"canonical ≠ toString"钉死了**（`SimosObjectMapperTest.addressMapKeysRoundTripAsCanonicalText`）⇒ 用 `Address` 作变更集键会破往返。取 `Address#canonical()` 为键，谓词层不变。故 `SdChangeSet.info` 是 `FieldDelta<List<SdInfoEntry>>`（spec §五.1 写作 `FieldDelta<SdInfoEntry>`，与 §三.1 的 `List` 值自相矛盾——取 §三.1）。
- **裁定 A3-c（`Address` 作值需绑定 ⇒ 加在共享层 `SimosObjectMapper.addressValues()`）**：SDSimos 是**第一次**把 `Address` 当**值**放进快照树（`Verdict.subject` / `Directive.target` / `Action.PutInfo.address`）。`Address` 内容是 `List<AddressSegment>`，而 `AddressSegment` 是无 Jackson 注解的 sealed 接口 ⇒ 裸往返在反序列化期必死。键绑定（裁定 38）管不到值路径。放共享层：`Address` 是 util 类型，且该 mapper 是全仓唯一装配点。**不动既有字节**（既有快照不以 `Address` 为值）。
- **裁定 A3-d（spec §三.1 把 `implements Snapshot` 写在状态树头是笔误）**：执行期按 map 的"树 / 切片分离"落地——`SdState` 是树、`SdSnapshot` 实现 `Snapshot`。
- **裁定 A3-e（第五条不变量的可判子集）**：spec §三.1.5 的"|Δ| ≤ 记录时的当前值"**在 `SdState` 构造期不可判**（sd 不拥有 unit 强度；`LossRecord` 未携带"记录时的当前值"）。A3 落地可判子集（`deltas` 非空、CombatState 引用的损失记录须同属一个 Combat）；**真上界的落点是命令期 C3**。如实记，不假装覆盖。
- **A3 判据实测**：反射枚举 10 组件逐组件往返绿；`ArchitectureGuardsTest` 命中恰 5 路径；`SdCodec` 编码→解码→再编码**逐字节相同**；5 条不变量各有故意违规用例（R4 重复 / 悬空外键 / 结局不在表 / 链断裂 / 损失记录为空+跨 Combat）。
- **A3 变异轮**：m1（`between` 的 info 恒 Unchanged）红、m2（`apply` 的 combats 不重建）红、m3（ArchitectureGuardsTest 删 sd 路径）红、**m4（删 NationId 键反序列化器）SURVIVED——等价变异体**、m4b（`asSdSnapshot` 改裸 cast）红。m4 的根因已验证：`NationId` 是单 String 分量 record，Jackson 默认键反序列化回退（String 构造）已能读回 ⇒ 删显式注册不改行为（**不是护栏失效**）。补 m4b 提供可杀的 codec 变异体。

### A4 Nation 命令族 + `SdResolver`

- **落地**：`sd.CreateNation`（R13 tag 校验）/ `sd.CreateArmy`（经 `state.module("unit")` 读根单位存在性）/ `sd.CreateDecisionMaker`（N9 白名单校验）；`NationTag`（`"nation:" + id`，G1 常量集中一处，A6 守卫引用同一常量）、`SdCommandNames.SIMOS_COMMAND_SUBMIT`；`sd/resolve/SdResolver`（canonical 八形态 + combat 的 stage/outcome 链）；`Shell` 注册 3 handler + 1 resolver。
- **裁定 A4-a（段数）**：`sd:combat.<c>:stage.<s>` 是 **3 段**、`...:outcome.<o>` 是 **4 段**（Entity 的 `kind.name` 在同一段内，段间才是 `:`）——计划期写的 4/6 段是笔误。
- **裁定 A4-b（`CreateDecisionMaker` 的 `viewScope` 创建期恒空）**：spec §四 列了 `viewScope`，但配权归 GM 专用的 `sd.SetViewScope`（D4）⇒ 创建期不解析、恒 `ViewScope.empty()`。
- **A4 判据实测**：三条命令正常 ⇒ `revisions` +1、拒绝路径行数不变；无 tag Region 拒绝且消息含 region id；`allowedTools` 含 `simos.command.submit` 拒绝；`SdResolver` canonical 回显 / 未存在与非法形态空候选。
- **A4 变异轮（3 个全杀）**：m1 删 R13 tag 校验；m2 删 N9 白名单校验；m3 `SdResolver` 无视存在性 ⇒ 空候选用例红。

### A5 R14 INFO 写路径（`SdInfoEntry` + `sd.PutInfo`）

- **落地**：`sd.PutInfo`（`address,key,value,note?`）→ `SdChangeSet.info` → 进 revision；`Shell` 注册 handler；`SdPutInfoEndToEndTest` 经**真** `CoreSimos` + **真** `Replay` 验"INfo 随 revision 重放逐字段相等"。
- **裁定 A5-a（写感知层）**：`sd.PutInfo` 默认写**感知层**（供 UI/AAR），ground truth 仍由领域模块持有（spec §六）。
- **裁定 A5-b（诚实边界）**：`SdInfoEntry.value` 是裸 `Object`，结构化值的 equals 往返不满足 ⇒ 判据只覆盖标量（String/数值）；结构化值列为挂起，不假装覆盖。
- **A5 判据实测**：`sd.PutInfo` 提交 → `Replay` → info 条目逐字段相等（`at` = 计算时 base 的 revision）；坏地址拒绝且行数不变；同址两次追加成两条。
- **A5 变异轮（2 个全杀）**：m1 `PutInfoHandler` 跳过 `Address.parse`；m2 `SdChangeSet.apply` 漏 info 归一。

### A6 ★ 不可删守卫（`MutationGuard` + `RegionDeleteGuard`）

- **落地**：`util.spi.MutationGuard`（只读、照 `AgentAttachPolicy` 形制）；`CommandBus` 在 `handler.handle` **之前**按注册序调 guard、任一拒绝 ⇒ 走 `rejected` 路径不留 revision（4 参构造保留 = 空守卫，既有调用点零改动）；`CoreSimos.register(MutationGuard)`；`sd/guard/RegionDeleteGuard`（同时读 map 的 `Region.meta.tag` 与 sd 的 `Nation.homeRegion`）；`Shell` 装配。
- **裁定 A6-a（Core 仍看不见领域类型）**：guard 是 `util.spi` 的不透明策略，Core 只按 `commandType`/`payloadJson` 转发（铁律 4）。守卫实现住 sd——只有它能同时读 map 与 sd 的切片。
- **A6 判据实测**：带 tag 的 Region `map.DeleteRegion` ⇒ 拒绝、行数不变、事件 `received+rejected`；**去 tag ⇒ 放行**；非 `map.DeleteRegion` ⇒ guard 返回空不影响；同一输入两次调用结果相同。
- **A6 变异轮（3 个全杀）**：m1 **删 `Shell` 的 guard 装配** ⇒ `taggedRegionDeleteIsRejected` 红（区域被删成功）；m2 `RegionDeleteGuard` 恒拒 ⇒ "去 tag 放行"红；m3 `CommandBus` 把 guard 调用挪到 `handler.handle` **之后** ⇒ 时序用例红（替身 handler 在守卫拒绝前即抛）。

### 执行期取代说明汇总（A3~A6）

1. **`Combat.stages` 由 `List<CombatStageId>` 改为 `List<CombatStage>`**（A3-a，改 A2 记录一处）。
2. **`SdState.info` 键取 canonical 串、`SdChangeSet.info` 为 `FieldDelta<List<SdInfoEntry>>`**（A3-b，spec §三.1 与 §五.1 自相矛盾处取 §三.1）。
3. **`SimosObjectMapper` 新增 `addressValues()`**（A3-c，util 共享层；Address 作值）。
4. **`SdState` 不 `implements Snapshot`**（A3-d，spec §三.1 笔误）。
5. **spec §三.1.5 的上界在构造期不可判**（A3-e，落命令期 C3）。
6. **`sd:combat` 链式地址是 3 / 4 段**（A4-a，计划 4/6 段笔误）。
7. **计划 §附推演的门禁数字与实际有偏差**（见 `a3a6-report.md` §五）：A4/A5 原推 app +0，实际因 `Shell` 注册 3+1 个 sd handler **牵动 M5 的 catalog 覆盖测试**（`SimosToolsTest` / `McpCoverageTest`），已按新语义扩展（保持 `containsExactly` 严格性，未改成恒真）。
8. **`ShellSmokeTest` 的 codec 计数 3→4**（A3 起第 4 个 `SdCodec`；按新语义改，非为过门禁）。
9. **A3-m4 为等价变异体**（显式键注册在单 String record 上非承重），如实记。

## 阶段 C（Combat）—— 2026-09-21

- **worktree** `.claude/worktrees/sdc`，分支 `sd/c`，基线 `a13266d`（unit-ext T10 合并后）。前置（unit-ext T1~T10）已关账。**实现提交 `ad74ea0`**。
- **门禁**：`./mvnw clean verify` **rc=0、第 1 次尝试**、**8/8 SUCCESS**、**1232** = `170/362/45/259/177/91/128`、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 90/90。基线自测 **1199** = `.../62/124`；**delta 干净**：前五模块逐字不变，`sd +29`、`app +4`。证据 `c0-baseline/`、`c-verify/`。
- **落地**：C1 `sd.CreateCombat`/`AddCombatStage`/`SetStageOutcomeTable` + `CombatStages`；C2 `sd.CommitCombatOutcome`；C3 `sd.RecordCasualties`（双轨 + 上界，读 unit 切片）；C4 `SdTimeParticipant`+`TriggerEvaluator`+`sd.RegisterEffect`/`CancelEffect`；C5 `app/sd/SdCommandDrain` + `Shell.advanceAndDrain`；C6 端到端。catalog/`PAYLOAD_HINTS` 30→37；`McpCoverageTest` 载荷 30→37；`SimosToolsTest` 强判据 30→37。
- **裁定/取代说明**（详见 `c-stage-report.md` §三）：**C1-a** 首个 `AddCombatStage` 兼建 `CombatState`（`combatStateId`/`hex` 首阶段必填，因 spec §三.3 只有这条路径能给出合法的 `currentStage`）；**G6** drain 幂等键 = `"drain:"+effectId`、以 revision 行 `commandId` 判"已 drain"（进程重启仍成立）；**sd 切片对可推进世界必需**（`TimeAdvance` ④Validate）⇒ `DemoWorld` 与 9 个 app 测试夹具补 `SdSnapshot`（+`SdCodec`）；**MCP/GUI 的 advance 未自动串 drain**（开口项，自然收口点 D 阶段）；`ThresholdKills` 取全局人员损失累计。
- **变异**：15 个变异体 **14 KILLED / 1 等值存活**（`c4m2`：重复处理 `FIRED` 效果在 `EnqueueUnitCommand` 下状态无差异 ⇒ 补 `firedPutInfoEffectDoesNotAppendAgain` 后 `c4m2b` KILLED）。装置 `mutants/mut-run.py`、日志 `mutants/mut-run.log` + `logs/*.log`。
- **实测到的陷阱**：变异装置 `clean test` 后 `cp` 还原源文件，但 `target/classes` 仍是变异体 `.class`；随后**非 `clean`** 的 `mvn test` 复用它 ⇒ 干净代码上误判"红"。**判红必须 `clean`**（CLAUDE.md 形态 1 的新实例）。

## 阶段 D（Directive + 判决 + 渠道）—— 2026-09-21

- **worktree** `.claude/worktrees/sdd`，分支 `sd/d`，基线 `e5b96ac`（阶段 C 合并后）。**实现提交短 SHA 见本段末**。
- **门禁**：`./mvnw clean verify` **rc=0**、**8/8 SUCCESS**、**1281** = `170/362/45/259/177/124/144`、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 90/90。基线自测 **1232** = `…/91/128`；**delta 干净**：前五模块逐字不变，`sd +33`、`app +16`。**第 1 次尝试 FAILURE（SpotBugs 9 条）→ 修后第 2 次 SUCCESS → 变异轮后第 3 次复跑仍绿**（`d-evidence/logs/`）。
- **落地**：D1 `DirectiveWhitelist`+`IssueDirectiveHandler`（R4 命令期 + 白名单禁自指/通用写 + 执行原文落 INFO）；D2 `sd/adjudication` 八件（`Judgement` 三态 / `DecisionAdjudicator` / `AdjudicationRequest` / `LlmClient`+`LlmRequest` / `Breakpoints`（D1+D3 合并=7 组）/ `AdjudicationSchemas` / `LlmDecisionAdjudicator` N13 降级）；D3 `VerdictFreezer`+`SubmitVerdictHandler`（判决冻结、`atRevision`、N8）；D4 `SetViewScopeHandler`+`RedactingQueryService`（GUI `/api/map/overview`、`/api/units` 的 `?as=`；读工具 `map.overview`/`unit.list` 的 `actor`）；D5 `sd/channel` 四件 + app 四渠道（N16/N17/N18/R9）；D6 三条窄工具 + `SimosToolSource.Role` 三桶；D7 `AdjudicatorRunner`。`catalog` 37→40；`McpCoverageTest`/`SimosToolsTest` 各补 3 条真载荷与强判据。
- **裁定/取代说明**（详见 `d-stage-report.md` §三）：**D1** 执行原文落 `sd:directive.<id>` 的 INFO（key `intent`）；**D1** 白名单从注册面派生、`Shell` 注册顺序随之调整；**D2** 合并用 `Breakpoints.callGroups()`=7 表达；**D3** `VerdictFreezer` **先判 subject 面、再判 schema**；**D4** redaction 只接 2 端点 + 2 读工具（计划写的 9 条读工具**未全接**，列开口项）；**D5** `submit` 保 `void` 但**抛**；渠道 actor 声明为**动态 supplier**；**D6** 既有构造器委托 `Role.EXTERNAL`（12 工具逐条不变）、运行时 MCP 仍外部桶；**D7** 只自动落 D1/D3/D6 判决，其余草案不自动发指令。
- **门禁抓到 9 条新代码 SpotBugs**（7 条 `US_USELESS_SUPPRESSION_ON_METHOD`——`CoreSimos` 是 final 故 `EI_EXPOSE_REP2` 不触发；2 条 `CT_CONSTRUCTOR_THROW`——abstract 基类构造器 `requireNonNull`）⇒ 删多余抑制、构造器不再抛。**`mvn test` 不跑 SpotBugs，故只有 `verify` 抓得到**。
- **变异 13 体 13 杀 0 存活**（`d-evidence/mutants/`，九道门禁）：d1m1~d1m3 / d2m1~d2m2 / d3m1~d3m2 / d4m1~d4m2 / d5m1 / d6m1 / d7m1~d7m2。★ 装置首轮把 `d1m3`（期望方法名写错）与 `d7m1`（模块写错致 `Tests run=0`）误判 SURVIVED，修正后重跑均 KILLED——**未伪造红点**。
- **实现提交短 SHA**：`838a12a`（分支 `sd/d`，基线 `e5b96ac`）。

## 阶段 E（关账）—— 2026-09-21

- **worktree** `.claude/worktrees/sde`，分支 `sd/e`，基线 `c49668e`（阶段 D 合并后 = 主树 HEAD）。**零生产 / 零测试改动**（只新增 `e-evidence/` + 改本台账 + `CLAUDE.md`）。
- **门禁**：`./mvnw clean verify`（前台、独占）**rc=0、第 1 次尝试**、**8/8 SUCCESS**（显示名 `SimulatorMosire/UtilSimos/MapSimos/SocialSimos/UnitSimos/CoreSimos/SDSimos/SimosApp`）、**1281** = `170/362/45/259/177/124/144`、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 `tests=90 pass=90 fail=0`。证据 `e-evidence/logs/clean-verify.log`（`md5=50cea6463a49e0179d64f0a086abe29c`）+ `verify-rc` 记于 `e-evidence/logs/attempts.txt`。★ **总数与逐模块数全部现场重算**（`logs/recomputed.txt`），不引用任何文档里的现成数字；与台账/`CLAUDE.md` 一致只用于发现分歧。★ 本机 `nproc=8`、约 48s、无被杀轮（CLAUDE.md 的 `nproc=2` 机器"摘后台/被杀"结论**不适用本机**）。
- **判据**：spec §十一 **15/15 有实测值、无占位符**（逐条值 + `文件:行` 证据锚 + 变异保证见 `e-evidence/task-final-report.md` §二）。R/N 点验见其 §三；变异汇总 **46 体 / 44 KILLED / 2 等价存活（均有可杀同族）** 见其 §四。
- **关账裁定（新增三条判别力说明，未改 spec/代码）**：① **#6「权重归一」是措辞**——实现是 `weight>0` 的 categorical 表 + 恰选一个（LLM/人选、不采样），**无数值归一**；spec §三.3 只要求 `weight>0`；② **#3「含判决的推进后 Replay」**——判决冻结与推进冻结**两组分各测到**（`SdVerdictFreezeEndToEndTest` / `SdCombatEndToEndTest`），**合取未独立覆盖**；③ **#12 sd 专属并发**——只到机制级（R4 + `CommandBus` ③④ 同锁 + `OptimisticConcurrencyTest` 替身 `RenameUnit`）。
- **遗留逐条处置**：spec §十二 14 条（多数为"有意不实现"/外部盲区，逐条见报告 §五.1）+ 计划 G1~G7（**7 条全消**，见 §五.2）+ 各报告"我未能核实"归并为 **L1~L22**（§五.3，逐条给"已消/未消 + 为什么"）。
- **本关账轮未发现需"就地修"的新缺陷**（门禁一次绿、15 条判据全有实测支持、46 变异中 2 个等价存活均有可杀同族）。

## 我未能核实的
- 计划期**未跑任何 Maven**：§附的门禁数字为**推演**，非实测（A1/A2 已实测，见下）。
- A1/A2 本机实测：worktree `./mvnw -pl simos-sd -am verify` rc=0、sd `BugInstance size is 0`、13 条 sd 用例；**主树全量 `clean verify` 的逐模块数字以关账轮为准**（见 `a1a2-report.md`）。
- `simos-sd` 的 reactor 计数已实测 **8/8**（父 + 7 模块）。
- 姊妹计划 `2026-09-20-unit-extension-plan.md` 是否落盘/关账**未核实**（不属 A1/A2 范围）⇒ 阶段 C 前置状态未核。
- `SdState`/`SdChangeSet`/`SdCodec`（A3）**尚未落地**；`Affiliation`/`Trigger`/`Action` 的 Jackson 往返**只在 A3 才会真跑**（注解已预置，但"能往返"未经 A2 实测）。
- **A3~A6 收口（2026-09-20）**：A3~A6 已在 worktree 落地、`./mvnw clean verify` rc=0（8/8 模块、**1060** = 170/362/45/131/174/**62**/**116**、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 88/88）。
- **A3-m4 是等价变异体**（显式 `NationId` 键反序列化器在单 String record 上非承重，Jackson 默认回退已能读回）——不做进一步加固，如实存档。
- **跨 revision 的 drain / LLM / 窄工具 / 渠道**（C/D 阶段）未实现、未验。
- **`sd.PutInfo` 的结构化值往返**未验（挂起项，只覆盖标量）。
- **守卫只覆盖 `map.DeleteRegion`**（spec §九 v1 范围）；其余跨模块写前校验未预设。
- 变异轮的**等价强度**：a5-m2/a6-m2 等使整类用例多条红（红点落被保护断言，但非最小红点）；未逐条做"最小杀伤面"分析。
- **阶段 C 收口后仍未核实**（详见 `c-stage-report.md` §五）：`agentlib-mosire` 外部依赖盲区（C 阶段未触 LLM/渠道）；MCP/GUI 的 advance **未自动串 drain**（端到端"经 MCP 推进 ⇒ 自动 unit 战损"未验）；跨 revision drain 原子性仍挂起；`ThresholdKills` 是**全局**累计（非 per-combat）语义未验；`AfterTicks`/`UnitAtHex`/`OutcomeSelected`/`And`/`Or` 无独立判据；`min/maxDurationTicks` 未参与推进；多 `CombatState` 指向同一 `Combat` 的语义未定；真档/A\* 未上；前端零改动。
- **阶段 D 收口后仍未核实**（详见 `d-stage-report.md` §六）：**`agentlib-mosire` 外部依赖盲区**（D 只用 lambda 假客户端，**未接真实 LLM/未联网/未核其 LLM API**）；**N12 sd 专属并发**未新增端到端（沿用 M4 Task 10 的 `RenameUnit` 并发用例）；**D4 redaction 只接 2 端点 + 2 读工具**（其余 7 条读工具未接；`seeOwnUnits` 只在合成夹具验；hex/region 级裁剪未单测）；**D5 per-session MCP 身份仍挂起**、渠道 actor 声明=当前世界全部决策人（非按 Agent 绑定）；**CLI/HTTP 两个渠道实现无独立判据**（只经编译）；**D6 角色桶是"可测结构"而非运行时路由**（运行中 MCP 仍发外部桶 12 工具、含通用写）；**D7 只自动落 D1/D3/D6 判决**、subject 由调用方给、**未挂进 tick 循环**；判据 §十一.10 的"去掉插桩则相同"只由变异体 d4m1 间接证；**LLM 选得对不对不在任何判据内**（N14 设计如此）。
- **★ 阶段 E（关账）后仍未核实 / 未跑**（汇总于 `e-evidence/task-final-report.md` §〇/§五.3/§六）：**只跑了 1 次全量门禁（第 1 次尝试即绿）**，未在**另一台机器 / 从模块目录**跑（CLAUDE.md 形态：护栏要在每种环境形态各证一次）；未跑限域 verify、e2e、真档、真实 LLM。**L1~L22 未消项**逐条在报告 §五.3（外部依赖盲区 / N12 sd 专属并发只到机制级 / redaction 未全接 / per-session 身份 / CLI·HTTP 渠道无独立判据 / 角色桶非运行时路由 / `AdjudicatorRunner` 未挂 tick 循环 / `SdInfoEntry` 结构化值往返 / 守卫只覆盖 `map.DeleteRegion` / `ThresholdKills` 全局语义 / 其余 Trigger 分支无判据 / `min·maxDurationTicks` 未参与推进 / 多 `CombatState` 语义未定 / 真档·A\* 未上 / 第 5 条不变量构造期不可判 / A1 独立全量未跑 / 等价性只对 `NationId` 实测 / 跨 JVM 字节稳定未测 / WARNING 未清点）。**三条判别力说明（非缺陷）**：判据 #6「权重归一」是措辞（无数值归一）、#3「含判决的推进后 Replay」合取未独立覆盖、#12 见 N12 条。
