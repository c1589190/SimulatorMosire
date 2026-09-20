# Unit 扩容 —— SDD 台账（裁定与结论）

> 计划：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md`；spec：`docs/superpowers/specs/2026-09-20-unit-extension-design.md`
> worktree：`.claude/worktrees/uet12`，分支 `ue/t12`。本文件只记**裁定与结论**，不记取证过程（证据在 `tN-evidence/`）。

## T1 模型地基（Block 所有下游）

- 落地：`UnitStatus` / `RelativeOffset` / `CommandChainId` / `CommandChain` 四新类型；`Unit` +4 组件 + 兼容构造器；`UnitState` +`commandChains` + `effectivePosition` 五行情形；`UnitChangeSet` 二组件；`UnitCodec` 键反序列化器；四个 canonical 拷贝点连带。
- **结论**：旧行为一字不变（`attached=true`/`offset=empty`/`status=MOVING`/`rejoinTarget=empty`）；`simos-unit` 计数 **131 → 148**（+17）。
- **S1 未触发**：`SegmentedSeries<Boolean>` 与 `SegmentedSeries<Optional<RelativeOffset>>` 均实测可 JSON 往返（`UnitCodecTest` 绿）⇒ 不退化普通字段，时态序列形态保留。
- **裁定（兼容构造器锚时刻）**：取 `parent.segments().get(0).from()`（计划 §三 T1 步骤 3 原文）。语义安全：`SegmentedSeries.baseValueAt` 对 anchor 之前向前恒定延拓，故 `attached` 恒为 true、`offset` 恒为 empty，与旧行为等价。
- **裁定（`requireChainReferencesResolve` 的检查位置）**：放在 `UnitState` 紧凑构造器（与既有 `requireNoCycleAtKeyTimes` 同层）；commander 与每个 member 逐一查 `units`。这是 spec §一.6 不变量 1。
- **变异**：m1/m2/m3/m4 **全 KILLED，0 存活**（日志 `t1-evidence/logs/`），每轮 `restored_md5 == orig_md5`。
- **未触发/未做**：跨 JVM 字节稳定、真档兼容、SpotBugs（归关账轮）——见 `t1-report.md` §五。

## T2 三态速度（E3 / P5 / P6 / P13）

- 落地：`UnitStatus.factorPerMille()`（1000/500/250‰）；`Unit.effectiveSpeed()`；`UnitOperations.planRoute` 冻 `effectiveSpeed`；`UnitOperations.setStatus`；`UnitPayloads.requireStatus/optionalStatus`；`CreateUnitHandler` 可选 `status`（缺省 MOVING）；新增 `SetStatusHandler`。
- **结论**：三态出发速度 8/4/2 可区分且有序；在途改状态不回溯（`Movement` 一字不变）；`CreateUnit` 缺省 MOVING、可显式、未知串拒；`MovementStatus` 与 `UnitStatus` 正交（`UnitMoves.java` 零改动）。
- **裁定 U1（effectiveSpeed 的 clamp）**：`max(1, floorDiv(speed × factorPerMille + 500, 1000))`。理由：`Movement.speedAtDeparture ≥ 1` 硬约束；夹具用 `speed=8` 保三档可区分，`speed≤2` 专钉 clamp。
- **计数**：`simos-unit` **148 → 167**（+19）。
- **变异**：m1/m2/m3 **全 KILLED，0 存活**（`t2-evidence/logs/`），每轮 `restored_md5 == orig_md5`。
- **未做**：`unit.SetStatus` 未注册进 `Shell`（归 T9）；真档 / SpotBugs 归关账轮——见 `t2-report.md` §五。

## 合并后门禁（`feat/adr1-core-scope`，T1+T2 快进合并 `8b8c0c0`）

- `./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t2-evidence/logs/merged-full-verify.log` + `verify-rc.txt`。
- **逐模块**：`util 170 / map 362 / social 45 / unit 167 / core 174 / sd 62 / app 116` = **1096**。
- ★ **基线口径更正（诚实披露）**：派单写"基准 1000 = 170/362/45/131/169/110/13"。**该 1000 与本次起始 HEAD `f9f5f0c` 不符**——A3~A6 的 sd 合并（`924bb49 → f9f5f0c`）给 core/sd/app 加了 **+5/+49/+6 = +60** 条用例（git diff 实测），而它**未碰 simos-unit**。⇒ 起始树实测应为 **1060**（`…/unit 131/core 174/sd 62/app 116`）。**本次 T1+T2 的净增量恰为 `simos-unit 131 → 167 = +36`**，其余模块逐值不变（diff 范围仅 `simos-unit` + `.superpowers`）。**1096 = 1060 + 36**。

## T3 编制命令 A（attach 级联 / detach 只节点 / offset）

- **worktree** `.claude/worktrees/uet3`，分支 `ue/t3`，基线 `4138c5d`，实现提交 **`5adb574`**。
- **落地**：`UnitOperations.attachSubtree`（级联，P3）/ `detachUnit`（只节点）/ `setOffset` + 私有 `subtreeOf`/`parentAt`/`copyFormation`；`UnitPayloads.optionalInt`；三 handler（`AttachUnitHandler` / `DetachUnitHandler` / `SetFormationOffsetHandler`）。生产 diff **5 文件**（`UnitOperations` +132、`UnitPayloads` +16/−2、3 新 handler），`Co-Authored-By` 命中 **0**。
- **计数**：`simos-unit` **167 → 183**（+16；`UnitOperationsTest` 16→25、`UnitCommandHandlersTest` 34→41）。
- **变异 5 轮 5 KILLED / 0 SURVIVED**（`t3-evidence/logs/m1..m5.log`，各九道门禁）；★ **m3**（删环校验）红点 = `UnitOperationsTest.attachRejectsAParentInsideTheSubtree:356`，控制器原文核过失败清单。
- **裁定 T3-a（`UnitPayloads` 新增 `optionalInt`）——接受**：计划 §三 T3 第 5 步**自身互斥**（"`dq`/`dr` 用 `requireInt`" vs "允许任一缺失 ⇒ 清偏移"），`requireInt` 缺失即抛、无法表达"可选"。实现者新增的 `optionalInt` **镜像既有 `optionalText`/`optionalHex`**，是"尽量不新增 helper"的有意偏离 ⇒ 接受。
- **裁定 T3-b（spec §五.2 `:362` 的「已是父」不实现）——接受不实现，spec 已回填**（该行改为 `不存在；环 〔★ 见下注〕` + 表下补注）。依据三条：① `:143` 明写**合体 = 同格前提下重新 attach**，而 detach 只翻 `attached`、**不动 `parent`** ⇒ 拒「已是父」会把**拆→合往返**堵死（E2 核心）；② `:369` 的 `MergeFormation` op **就是 attach** 且拒绝条件**不含**「已是父」⇒ 表内自相矛盾；③ `UnitOperations.java:201` 明写操作面不判"无变化命令"。**⇒ T4 不得补这条守卫。**
- **★ 带裁定的遗留 T3-L1（`dq`/`dr` 无上界 ⇒ 静默溢出）——记，不在 T3 修**：实测 `RelativeOffset.appliedTo` 是裸 int 加法（`hex.q() + dq`），`HexCoord` 无紧凑构造器校验、`RelativeOffset` 无范围护栏 ⇒ `dq = 2147483647` 时结果**静默回绕**为负数（`1 + MAX_VALUE = -2147483648`），单位"瞬移"。**不修的理由**：① 修点应落在 **T1 已关账的 `RelativeOffset`**（只堵 handler 层则 **codec 反序列化路径仍开着**，那比不堵更坏——看着像有护栏）；② spec **P2 明文"无范围约束"**，加界是**动一条已裁定的 P 项**；③ 按**裁定 42** 补护栏必须自带变异轮，而这会牵动 T1 的证据链。⇒ **留给 T10 关账轮连 P2 一起裁**；`unit.SetFormationOffset` 现有的"非法偏移"拒绝（非整数 / 超 int）已兑现 spec `:365` 的载荷层部分。
- **★ 控制器修的既有 flake（M10 遗留，非 T3 引入）**：合并后首轮全量 `clean verify` **rc=1**，红在 `simos-app` 的 `GuiAccessLogTest.logLinesAreActuallyCaptured`（"捕获为空"）。**根因**：该用例的 javadoc 自称前提断言，却用**裸读** `accessLines()`，而**同类其余三条都用了 `awaitAccessLines`**——而后者的注释**自己就写着**"日志在 `finally` 写出，与客户端拿到响应之间有一个极短窗口"。控制台原文可证那行 `access GET / -> 200 2ms`（`02:09:08.521`）**出现在断言之后**。⇒ 全类唯一会随机红的正是它；T3 未碰 `simos-app`。**修法**：前提断言改用 `awaitAccessLines(1)`（**裸读不是判据更强，只是更脆**）。
- **★ 改动被测文件 ⇒ M10 的 m1 对新字节重跑（九道门禁，控制器）**：M10 原装置 `mut-round.sh` 把 `WT` 钉死在**另一台机器**（`/home/cna/…`，本机跑不了）⇒ 新写本机版 `m10-deploy-evidence/mutants/mut-m1-rerun.sh`，日志 `m10-deploy-evidence/logs/mut-m1-rerun-20260921.log`。**结果：m1 KILLED**——`orig_md5=91267b3d… == pre/restored_md5`、`mutant_md5=83dcbc6e…`（与原件不同、`logAccess` 计数 2→1）、`compile_errors=0`、`Tests run: 4, Failures: 4`、surefire mtime 落在本轮内、★ **红点含 `logLinesAreActuallyCaptured:97`**（即 await 版前提断言**仍会红** ⇒ 那一行没有把护栏改弱）。**这一段是本轮唯一的新证据，其余 T3 结论均引实现者的 `t3-evidence/`。**

## 合并后门禁（`feat/adr1-core-scope`，T3 快进合并 `5adb574`）

- `./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t3-evidence/logs/merged-full-verify.log` + `merged-verify-rc.txt`（红的那一轮**留档不删**：`merged-full-verify.attempt1-flake.log`）。
- **逐模块**：`util 170 / map 362 / social 45 / unit 183 / core 174 / sd 62 / app 116` = **1112**。
- **核对**：`1112 = 1096 + 16`，且 **delta 干净**——只有 `simos-unit` 167→183，其余六个模块**逐值不变**（与 T3 "只碰 `simos-unit`" 的范围一致）；`simos-app` 116 不变（控制器改的是**既有断言**，未加用例）。

## T3 编制命令 A（E1 / P1 / P2 / P3）

- **本任务树**：`.claude/worktrees/uet3`，分支 `ue/t3`，基线 HEAD `4138c5d`（T1+T2 合并后）。
- 落地：`UnitOperations.attachSubtree` / `detachUnit` / `setOffset`（+ 私有 `subtreeOf` / `parentAt` / `copyFormation`）；`AttachUnitHandler`（`id, parent`，`parent` 必填）/ `DetachUnitHandler`（`id`）/ `SetFormationOffsetHandler`（`id, dq?, dr?`）；`UnitPayloads.optionalInt`。
- **结论（判据实测）**：attach **级联**（`id` 与全部后代 `attached=true`，且不溢出子树；只有 `id` 换父）／detach **只节点**（子节点与父都不动；`id` 是根 ⇒ 拒）／`attached=true` + 无自身位置 + `offset=(1,0)` ⇒ 有效位置 = 父位⊕(1,0) 且沿父链传播、清偏移 ⇒ 回父位（`T10` 历史值不受影响）／`offset` **不强制落图内**（`(-9999,9999)`）／detached + 无自身位置 ⇒ 空（带偏移也不回退父）／成环 ⇒ **op 内可读理由**、状态不变／部分分量 `{"dr":-2}` ⇒ `(0,-2)`、两者皆缺 ⇒ 清。
- **裁定 1（计划表述缺口，就地裁）**：计划 §三 T3 第 5 步说 `dq`/`dr` 用 `requireInt`，但 `requireInt` 对**缺失**字段是抛，无法表达"任一缺失 = 部分更新"（同句要求"允许其中任一缺失"）——**两句互斥，是计划自身的缺口**。⇒ 在 `UnitPayloads` **新增 `optionalInt`**（镜像既有 `optionalText`/`optionalHex` 形制：缺失/`null` ⇒ 空，非整数 ⇒ 抛），把载荷形状留在载荷类里，handler 不直接摸 `JsonNode`。这是对"**尽量不新增 helper**"的**有意偏离**，理由如上。
- **裁定 2（spec 表未实现的一格，待控制器裁）**：spec §五.2:362 给 `unit.AttachUnit` 列了三条拒绝"不存在；**已是父**；环"——**"已是父"未实现**（计划与派单均未提）。理由：① 重挂同一父正是 **P9 "合体 = 重新 attach"** 的语义，拒掉会把合体堵死；② 级联对子树仍有效（`attached` 段照追加，不是无变化命令）；③ 本操作面**无任何操作**判"无变化命令"（`reparent`/`placeAt`/`setStatus` 同款），单为 attach 加会开先例。同理 **`attached` 已 `true` 的节点不拒**（spec 未要求）。**⇒ 改 spec 表还是补实现，请控制器裁。**
- **裁定 3**：`SpiFixture.java` **零改动**（计划写"若需真三层夹具"）——三层树夹具 `formation(subAttached, leafAttached)` 建在被测类自己的测试里更贴近，且不必动 T1/T2 的既有夹具。三个根一律 `attached=false`：缺省 `true` 会把"级联溢出子树"整个掩盖掉（判别力要求）。
- **计数**：`simos-unit` **167 → 183**（+16 = `UnitOperationsTest` +9 / `UnitCommandHandlersTest` +7）；`util 170 / map 362` 逐值未变。
- **变异**：m1（detach 改级联）/ m2（attach 不级联）/ m3（删 op 内环校验）/ m4（`setOffset` 一律写空）/ m5（handler 层部分分量当清）**全 KILLED，0 存活**（`t3-evidence/logs/`），每轮 `restored_md5 == orig_md5`、`compile_errors=0`、`verdict=OK`。★ **m3 的价值**：删掉 op 的显式拒**不会**让命令通过（`UnitState` 构造期仍抛"编制树…成环"）⇒ 只断言 `IllegalArgumentException` 的话 m3 **会存活**；断言写成 `hasMessageContaining("子树")` 才杀得掉，红点正是那条**消息**断言——护栏保护的是"抛的是命令边界那条可读理由"，不是"会抛"。
- **未做**：三条 handler **未注册进 `Shell`**（归 T9）⇒ 端到端冒烟未做；跨机字节稳定 / 真档兼容未测；`subtreeOf` 复杂度（`O(units × 深度)`）未压测；`parentAt` 对"父链指向不存在 id"的容错无专门用例；**`dq`/`dr` 无上界 ⇒ `appliedTo` 裸 int 加法静默溢出**（当场实测 `1 + Integer.MAX_VALUE = -2147483648`，`HexCoord` 无紧凑构造器校验）——护栏归属待裁；SpotBugs 只在本树 `-am` 的 util/map/unit 三个模块跑过。详见 `t3-evidence/t3-report.md` §五。

## T4 编制命令 B（子树迁移 / 拆分 / 合体）

- **worktree** `.claude/worktrees/uet4`，分支 `ue/t4`，基线 `9747448`，实现提交 **`234f5ce`** + 控制器收口提交 **`725d9c4`**。
- **落地**：`UnitOperations.reparentSubtree` / `splitFormation` / `mergeFormation` + 私有 `requiredParentAt`；`UnitPayloads.requireTextArray`；三 handler。生产 diff **7 文件 +794/−6**（`UnitOperations` +138、三新 handler +46/+48/+52、`UnitPayloads` +16、两测试文件 +259/+241），`Co-Authored-By` 命中 **0**。
- **计数**：`simos-unit` **183 → 201**（+18；`UnitOperationsTest` 26→35、`UnitCommandHandlersTest` 40→49）；模块级 `-pl simos-unit -am verify` rc=0（170/362/201、`BugInstance size is 0` ×3）。
- **裁定 T4-A1（Reading B′）——接受**：`reparentSubtree` 让**后代也在同 `at` 追加 `parent` 段，值仍是它本来的父**。依据是 spec `:137` 机制列原文「给 `rootId` **及其全部后代**在同 `at` 追加 `parent` 段」；计划 `:292` 的括注「后代父不变」只是**值层面**提示。⇒ **计划 `:292`/`:311` 已由控制器回填**（带日期脚注），**spec 不动**（它本来就是对的）。
- **裁定 T4-A6（m1 的杀点在段数）——接受**：实现者把计划的 m1 原样跑掉，红点是**段数**（`Expected size: 2 but was: 1` @ ops `:510`/`:562`、SPI `:662`），**无一处是值断言**——B′ 下后代新段的值不变，值维度**结构性**杀不掉「只改 root」。⇒ 判据按段数落；★ 若把「后代不动」读成实现语义，m1 会退化成**零差异**变异体。
- **裁定 T4-A7（`mergeFormation` 复用 `attachSubtree`）——接受**：spec `:369` 的 op 列**就写着 attach**、`:143` 明写「合体 = 同格前提下重新 attach」、P3 attach 级联 ⇒ 复用即继承级联。**m6（只挂 child 不级联）实测被杀**（`ops:668` `Expecting value to be true but was false`）⇒ 该决定**自身有护栏**。
- **两个前置各自独立**：m3（删「同格」相等那一半）与 m4（删 MOVING）**各自独立被杀**，红点 `:649`/`:764` 与 `:638`/`:781` 不重叠。★ **未加「已是父」守卫**（T3-b 点名）——拆→合往返有用例钉住。
- **★ 控制器收口：m9 由「存活」转为「被杀」**（本轮唯一实质性改动）。首轮 9 轮 **8 KILLED / 1 SURVIVED**，存活项 `m9_UnitPayloads`（删 `requireTextArray` 的 `!value.isArray()`）。**根因不是缺用例，而是判据弱于行为**——`UnitCommandHandlersTest:636` **确实**传了 `subUnitIds:{}`（对象），却只断言 `.contains("subUnitIds")`，而**域层兜底消息**「subUnitIds 不得为空」**同样含该 token** ⇒ 形状层被绕过后断言照样通过。（实现者已**双向如实报出**「存活」+「判据弱于行为」，**未伪造红点、未改判据凑杀**——判断正确，交接正确。）
  - **修法**：两处 `.contains(...)` 收紧为断言**形状消息原文**（`"元素必须是非空字符串"` / `"必须是 [字符串…] 数组"`）；**逐行数中性（+2/−2，无行位移）** ⇒ 各轮红点的行号引用全部保持有效；**生产字节零改动**（`baseline-md5.txt` 五条 `md5sum -c` 全 OK）。
  - **按裁定 42（改动护栏必须自带变异轮）九轮全部重跑**（`logs/rerun-*.log` + `rerun-rounds-summary.txt`）：9/9 `verdict=OK`、`rc=1`、`compile_errors=0`、各轮 `restored_md5 == orig_md5 == baseline`，且变异体 md5 与首轮**逐字节相同**；**m9 RED，红点 = `UnitCommandHandlersTest:638 [必须是数组]`**。⇒ **T4 变异汇总更正为 9 被杀 / 0 存活**。
- **合并后门禁**（`feat/adr1-core-scope`，快进 `9747448 → 725d9c4`）：`./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、**1130** = 170/362/45/**201**/174/62/116、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t4-evidence/logs/merged-full-verify.log` + `merged-verify-rc.txt`。**delta 干净**：只有 `simos-unit` 183→201（恰 +18），其余六模块逐值不变。
- **★ 环境级实测（与「后台被杀」那条并列，不是取代）**：本轮 `clean verify` **耗时 991 s**（T3 那轮 568 s）⇒ **超过 Bash 工具 600 s 上限**，在 600 s 处被 harness **摘到后台**，但**仍然跑完、rc=0**。⇒ **「前台起跑、超时后被摘到后台」与「用 `run_in_background` 起跑」不是同一件事**：前者实测能跑完（本轮即是），后者实测会被内存守卫杀（M8 T12 两轮）。★ 连带事实：本树判定门禁的耗时**已超 600 s 上限**——「被杀」仍然既不是红也不是绿，但「被摘到后台后等通知」这条路**是通的**。
- **未做 / 归下游**：三 handler **未注册进 `Shell`**（归 T9）⇒ 端到端冒烟在 T9/T10；`subtreeOf` 复杂度 O(units × 深度) 未压测；真档兼容未测。详见 `t4-evidence/t4-report.md` §八/§九。
- **★ 同族判据清扫项（控制器记，归 T10）**：`everyHandlerRejectsMalformedPayload` 里另有 **13 处**「只断言字段 token」的载荷断言（`:620`–`:631`，如 `.contains("hex")` / `.contains("waypoints")` / `.contains("status")`）。它们的变异体已各自被杀，故**不在 T4 动**（改它们要重跑 T2/T3 的轮次，超出本任务范围）；但**同族风险仍在**——凡「载荷层与域层都会提到同一字段名」的命令，token 断言判不出是**哪一层**拒的。归 **T10 关账轮**统一扫。

## T5 命令链（`command_chain`：Create / Update + 多属 + Map 键往返 + disband 交互裁定）

- **worktree** `.claude/worktrees/uet5`，分支 `ue/t5`，基线 `3e7d33e`。只碰 `simos-unit`。
- **落地**：`UnitOperations.createChain`（重 id 拒 / 引用不存在拒；**签名无 `at`**——见 T5-L1）/ `updateChain(state, id, Optional<String> name, Optional<UnitId> commander, Optional<List<UnitId>> members)`（未给字段不动；给 members ⇒ commander 必须 ∈ 新 members）/ `requireNotInAnyChain`（**disband 双向前置**：commander 与 member 两个方向；消息带**链 id** + **「先改链」**，风格对齐既有的「先改编、再解散」）/ **T5-U2 五处修复**（`state.withUnits(next)`）；`UnitPayloads.optionalTextArray`；新 handler `CreateCommandChainHandler` / `UpdateCommandChainHandler`（untracked，提交时须显式 add）。生产 diff **3 改 + 2 新**，`Co-Authored-By` 命中 **0**。
- **★ T5-U2 逐处守卫（五处各自有守卫、各自被对应变异体红）**：`UnitOperations.java:204` disband → `disbandRemovesAChainFreeUnitAndKeepsTheChains:989`（t5m06，两处同时红）；`:251` attachSubtree → `formationCommandsKeepTheChains:1005`（t5m08）；`:325` reparentSubtree → `:1009`（t5m09）；`:543` 私有 `withUnit`（**最宽**，七条命令都经它）→ `everyWithUnitRoutedOperationKeepsTheChains:1028`（t5m04）；`UnitTimeParticipant.java:122` → `advanceKeepsCommandChainsWhilePositionAndMovementChange:196`（t5m05）。`DemoWorld.java:126` **按裁定未动**。
- **计数**：`simos-unit` **201 → 216**（+15 = 新增 `@Test` 数）；`./mvnw -o -pl simos-unit -am verify` **rc=0**（util 170 / map 362 / unit 216、`BugInstance size is 0` ×3、`[ERROR]` 0）；`git diff --stat 3e7d33e -- simos-app simos-core …` **空**（其余六模块一行未动）。
- **变异**：**28 轮 / 26 被杀 / 2 存活**，九道门禁 28/28 `verdict=OK`。T3/T4 的 14 个变异体里 **12 个从新字节再生**、**2 个复用**（`SetFormationOffsetHandler` `749654ad…`、`ReparentSubtreeHandler` `f4b82888…` 字节未变且与 T3/T4 轮**逐字节相同**，实测 md5 相等）；再生的 12 个与旧轮 md5 全不同（靶文件已含 T5 的代码）⇒ **不复用**正是"旧变异体会把 T5 的修复一起回退"的形态。★ 五个 site 还原体带 **⑩ 道自证**：`orig_hits=0 pushed_hits=1`。
- **★ 存活两项（两种写法一起出现）**：**t5m03**（删 `UnitCodec.java:57`，即计划的 **m3 靶子**）与 **t5m14**（删整个 `keyModule()`）——`CommandChainId` 是**单 String record** ⇒ Jackson 默认 Map 键路径本来就能建它，**显式注册不承重**（等价变异体 / 设计缺口，**不是**"门禁没跑到"：两者 `verdict=OK`、`Tests run: 117, Failures: 0`）。★ 判别力由补的 **t5m13**（键**值**改坏）证明：**KILLED**，红在 `commandChainIdsSurviveRoundTripAsMapKeysOnOpProducedStates:221` ⇒ **Map 键判据本身有判别力**。两条**同时**报出，不伪造红点、不放宽判据。
- **★ 装置修正（自证过的教训）**：⑩ 原判据数整份文件的 `new UnitState(` —— site 5 的靶文件里 T5 自己的注释**合法地**写着该字样 ⇒ 原件计数 1、**当场作废该轮**（日志 0 字节）。改成**逐条自己的代码片段**后，五个 site 的九轮（18..23）全部重跑。另修：逐类 surefire 取 `tail -1` 会抽到**恰好没红**的那个类 ⇒ 装置加**模块级** `module_summary`；`t5m03` 首轮 `VOID` 的根因是**删行后留下未用 import**，Checkstyle（test 阶段、surefire 之前）先拦 ⇒ 同意图下再拼一笔删 import，装置**拒绝**把"没有报告"当"杀"。
- **待控制器裁的缺口**：**T5-L1** 计划 `:352` 写 `createChain(state, chain, at)`，实现**无 `at`**（`CommandChain` 四组件全是标量、无处可用；§五.2 命令表同样无时刻）——**按命令表实现**，是否回填计划文本待裁；**T5-L2** 计划要求 op 层对 `commander∉members` 给可读理由，但 `CommandChain` 构造器（`:40`）已先拒 ⇒ **结构性不可达**；**T5-L3** spec §五.3 `:387`/§八 #19 的"不注册 ⇒ 解码期抛"**实测不成立**（见上）；**T5-L4** `UnitState` 的 1 参兼容构造器**仍在**（T5-U2 六处事故的共同根因，编译期与门禁都不响）——是否 T6+ 删除/加废待裁；**T5-L5** 注释里复述被禁代码会让"整份文件计数"型判据误报；**T5-L6** `everyHandlerRejectsMalformedPayload` 的 token 级断言（与 T4 同条，归 T10）。
- **未做 / 未核实**：前端 **88/88 未重跑**（simos-app 不在允许的 reactor 切片，但 `git diff` 实测 0 行）；全量 `clean verify` 归控制器合并轮；两条新命令**未注册进 Shell**（T9 的活）；`~/.m2` 探针构件前提未复核（本轮 Maven 一律 `-am`、未跑 `install`）。详见 `t5-evidence/t5-report.md`（§六 诚实清单、§七 缺口表）。

## 合并后门禁（`feat/adr1-core-scope`，T5 快进合并 `3e7d33e → 69d3c7d`）

- `./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、**1145** = 170/362/45/**216**/174/62/116、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t5-evidence/logs/merged-full-verify.log`（188,166 B）+ `t5-evidence/merged-verify-rc.txt`（`rc=0`）。
- **核对**：`1145 = 1130 + 15`，**delta 干净**——只有 `simos-unit` **201 → 216**（恰 +15），其余六个模块（170/362/45/174/62/116）与 T4 轮**逐值不变**；与 T5 "只碰 `simos-unit`" 的范围一致。
- **★ 先有一轮被杀（留档不删）**：首轮在 `simos-social` 编译处（模块 3/8、残日志 21,086 B）被**内存守卫**杀；harness 通知原文存 `t5-evidence/logs/merged-full-verify.attempt1-killed.harness-output.txt`、残日志存 `…attempt1-killed.log`。★ **归因（环境级，非构建失败）——★ 已被 T7 更正，见下方「T7 归因更正」**：该轮起跑**没有**显式给超时（用了 Bash 工具默认值）⇒ 未到 600 s 就被摘到后台，随即被内存守卫杀；改回显式 `timeout=600000` 前台重跑，一次 rc=0。★ **当时我写下的「归因 = 默认 120 s 超时」是错的**：T7 实测**给了显式 600 s 仍然被杀**（见下文），真正的变量是**门禁耗时压在 600 s 线附近**这个事实本身，**不是超时值**。原文保留在此以免抹掉更正轨迹。⇒ 与 M8 T12「后台起跑被杀」、T4「600 s 后被摘到后台仍跑完」并列：**"被杀"仍然既不是红也不是绿**，且**判定门禁必须显式给足超时**。
- **★ 控制器自记（踩坑，与结论无关但值得记）**：本轮我在**主检出**（`3e7d33e`）跑 `git grep 'new UnitState('`，一度据"六处未改"得出**与 T5 报告相反**的结论；修复实际在 **worktree `.claude/worktrees/uet5`（分支 `ue/t5`）** 上。正是 CLAUDE.md 已记的「**『哪一份』是按树的，别照抄路径**」。**结论以 worktree 为准**（五处已是 `state.withUnits(next)`、`disband` 的 `requireNotInAnyChain` 在）。

## T5 控制器裁定（T5-L1~L6）

- **T5-L1（`createChain` 无 `at`）——按实现收，计划已回填**：`CommandChain` 四组件全标量、`at` 无处可用，且 spec §五.2 命令表同样无时刻 ⇒ 以**命令表**为准。计划 §三 T5 第 1 步已改为 `createChain(state, chain)` 并附注。
- **T5-L2（op 层给 `commander∉members` 的可读理由）——接受"结构性不可达"，不补用例**：`CommandChain` 紧凑构造器（`:40`）先拒，op 层那句**永不可达**。★ **连带判据纪律**：针对该消息的任何断言都是在测**死代码** ⇒ 不得为它补用例（补了会**假装有护栏**）。
- **T5-L3（"不注册键反序列化器 ⇒ 解码期抛"）——实测推翻，spec 已回填**：`t5m03`/`t5m14` 双双**存活**。根因：`CommandChainId` 是**单 String record**，Jackson 默认 Map 键路径本来就能建它 ⇒ 显式注册**非承重**。★ 判别力**另证**：补的 **`t5m13`（键值改坏）KILLED**，红在 `commandChainIdsSurviveRoundTripAsMapKeysOnOpProducedStates:221` ⇒ **Map 键判据本身有判别力**，存活是**等价变异体**而非"门禁没跑到"。★ **同族第二例**：`sd-simos` 台账 `:92` 早有 **A3-m4**（`NationId` 显式键反序列化器在单 String record 上非承重）。**spec §五.3 注 ① 与 §八 #19 已回填**；注册**保留**（记作"判据覆盖不到的风险点"，不是错误）。★ **不得由本例推出"复合键也无需注册"**——那一格**未被任何判据测过**。
- **T5-L4（1 参兼容构造器 `UnitState(Map)` 仍在）——暂留，立通则，删除归 T10**：它是 **T5-U2 六处事故的共同根因**，而**编译期与门禁都不响**（静默丢 `commandChains`）。★ **立为余下任务通则：任何重建状态的代码一律用 `state.withUnits(...)` / `state.withCommandChains(...)`，不得写 `new UnitState(units)`**（已回填计划）；是否**删掉**该构造器（牵动 `UnitState.empty()`、`DemoWorld` 与既有测试）归 **T10** 裁；本轮 `DemoWorld.java:126` **按裁定未动**。
- **T5-L5（注释里复述被禁代码 ⇒ "整份文件计数"型判据误报）——装置已修，立为形态**：⑩ 道原判据数**整份文件**的 `new UnitState(`，而 site 5 的靶文件里 T5 自己的注释**合法地**写着该字样 ⇒ 原件计数 1、**当场作废该轮**（日志 0 字节）。★ **通则**：变异自证的计数判据必须**逐条按被改的那一行/那个片段**判，**不得按整份文件**——**判据的粒度必须与被保护对象一致**。
- **T5-L6（`everyHandlerRejectsMalformedPayload` 的 token 级断言）——与 T4 同条，归 T10 统一扫**（`:620`~`:631` 一带 13 处；T5 又添同族风险）。
- **★ T5-U2 的控制器定性（本轮实质缺陷）**：计划把它写成"`disband` 留下**悬空链引用**"这一**窄**问题；**实测范围更宽**——**6 处**静默清空**整个 `commandChains` 组件**，含 T3/T4 **自己已关账**的 op 与**每次 tick**。今天不可见的原因是：**没有任何用例先造出"链非空"的状态再调 op**。⇒ 五处生产代码 + `UnitTimeParticipant:122` **各配自己的守卫**、**各被自己的变异体**红（t5m04/05/06/08/09），并带 **⑩ 道自证** `orig_hits=0 pushed_hits=1`。
- **★ 复用/再生 md5 实测（不靠推断）**：`ReparentSubtreeHandler` `f4b82888…` 与 T4 `baseline-md5.txt` **逐字节相同**、`SetFormationOffsetHandler` `749654ad…` 字节未变 ⇒ **复用合法**；`UnitOperations` `09840d49…→e3731e15…`、`UnitPayloads` `9cfd978a…→b56b2460…` **均变** ⇒ **必须再生且已再生**（旧变异体会把 T5 的修复**一起回退**）。

## 合并后门禁（`feat/adr1-core-scope`，T6 合并）

- `./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、**1162** = 170/362/45/**233**/174/62/116、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t6-evidence/logs/merged-full-verify.log` + `t6-evidence/merged-verify-rc.txt`。★ 前台起跑、显式 `timeout=600000`，**一次 rc=0**（未再被摘到后台）。
- **核对**：`1162 = 1145 + 17`，**delta 干净**——只有 `simos-unit` **216 → 233**（恰 +17），其余六个模块（170/362/45/174/62/116）与 T5 轮**逐值不变**。
- ★ **合并形态：rebase 后 ff（本轮特有，记录在案）**：控制器**在派单之后**提交了 `.serena/project.yml`（`6a475df`），而 `ue/t6` 基于 `e2dee0f` ⇒ `--ff-only` 会失败。**处置**：`git rebase feat/adr1-core-scope`（只多一条不碰 `simos-unit` 的提交 ⇒ 无冲突）后 `--ff-only` 合并，**保持线性历史**。**连带事实**：T6 报告里引用的 `0d40cdd`/`70dc4de` 是 **rebase 前**的哈希，合并后为 **`5e32402`（实现）/ `103f51d`（证据）**——同一份内容、逐字节相同，只是父提交变了。

## T6 稀疏路线（`unit.PlanSparseRoute`：A\* 逐段展开 / 不可达命令期拒 / 装配注入 U3）

- **worktree** `.claude/worktrees/uet6`，分支 `ue/t6`，基线 `e2dee0f`。只碰 `simos-unit`。
- **落地**：新 handler `PlanSparseRouteHandler`（80 行，**U3 构造器注入 `MovementCost`** + `mapOf` 照 `UnitTimeParticipant` 形制、**装配故障当场炸**）；`UnitOperations` +48（纯函数 `expandSparsePath` + `planSparseRoute` **复用既有 `planRoute`**）；测试 **纯追加 596 插入 / 0 删除**（ops +193 = 7 条、SPI +275 = 10 条）⇒ **无既有护栏证据作废**。
- **★ `UnitPayloads.java` 一行未改（有据）**：先核过 `requireWaypoints` 现成可用、且它**不管条数**；"waypoints 至少两个"由 `Route` 构造器给、稀疏路径上照样落得到 ⇒ **不需要新增守卫**。这与 T3 的 `optionalInt`（确需新增）形成对照：**核过再决定动不动，而不是"顺手加个 helper"**。
- **计数**：`simos-unit` **216 → 233**（+17）。模块门禁 `./mvnw -o -pl simos-unit -am verify` **rc=0**（170/362/233）；★ 收口轮先 `rm -rf simos-unit/target` 再跑。★ **实现轮首跑曾红**（留档 `logs/gate-impl-attempt1-red.log`）：装配故障用例的夹具被 `SimulationState` 构造期拒 ⇒ 修法是 `ImpostorSnapshot` + 两个切片都放（**`UnitSnapshots.of` 必须在 `mapOf` 之前调用**——这条顺序是实测出来的）。
- **变异：8 轮 / 8 杀 / 0 存活**。十道门禁全 OK、`mvn_rc=1`、逐轮 `restored_md5 == baseline_md5`；收口后仓根 `md5sum -c baseline-md5.txt` 两份靶文件 OK、源树零残留 `.java`。红点分布：t6m1（去 A\*）7 条 / t6m2（静默截断）3 条 / t6m3（删起点校验）4 条（含 2 条**既有** `PlanRoute` 用例）/ t6m4（跨段重复顺手去重）3 条 / t6m5（写死 `TerrainMovementCost.INSTANCE`）1 条**恰是注入判据** / t6m6（`mapOf` 静默兜底）2 条 / **t6m7（`new UnitState(next)`，T5-L4 靶子）2 条、含 T5 立的保链守卫** / t6m8（`type()` 改成 `unit.PlanRoute`）1 条。
- ★ **作废轮 1 次（留档不删）**：t6m6 首轮因变异体把 `Snapshot` 变成**未用 import** ⇒ Checkstyle `UnusedImports` 在 surefire **之前**拦 ⇒ 门禁 ⑥ 判 **VOID**，留档 `t6m6.log.VOID-1` + `.note`；改写为仍用 `Snapshot` 的形态后**前台重跑 ⇒ RED(被杀)**。**"没跑到"不等于"没红"**——装置**拒绝**把缺报告当杀。
- ★ **t6m4 的两种写法（同一条，不许只报一个）**：**判据弱于行为的一面**——该轮断言里有一部分是拒绝**理由文本**（"重复"），一个**只断言 `Rejected`** 的写法会放过这个变体（去重后 `Route` 仍会因"首尾"拒）；**但它不是存活者**——同轮另有 `expandSparsePathJoinsSegmentsWithoutRepeatingTheJoint`（**逐值钉住原始拼接**）把它杀掉。⇒ **真正的承重判据是逐值拼接断言**，理由文本那条只是**放大器**。**区分型变异体就是 t6m4 本身**。
- ★ **一条不充当护栏的用例（如实登记）**：`sparseExpansionMatchesPlanRouteForAdjacentWaypoints` 作为"与 `PlanRoute` 等价"的证据成立，但**无独立判别力**（等价子域，t6m1 下也红）⇒ 报告 §四 已登记为**不算护栏**。
- **裁定（G1，控制器）**：spec §二.2 原写 handler 内**写死** `TerrainMovementCost.INSTANCE`，与 U3 冲突 ⇒ **回填 spec**（不是"记 U3 取代 spec 文本"）：spec 是设计权威，留着矛盾句会误导 **T9 的装配**。已在 §二.2 加**注 ①**，并记明该决定**已被 t6m5/t6m6 实测**。
- **裁定（G3）**：`requireWaypoints` **可复用、未改**；**载荷层不补"≥2"守卫**——`Route` 已兜住，补了就是**新增守卫**（按裁定 42 须自带变异轮），而它挡的是**同一个坑**，边际判别力为零。
- **未做 / 归下游**：handler **未注册**（T9 的活）；"`revisions` 行数不变"只证到**机制级**（`Rejected` ⇒ 无 `UnitChangeSet`），端到端归 T9/T10；夹具**全是合成小图**、未在真档（19441 格）跑过；A\* 大图代价与跨 JVM 决定论未测（既有开口项）；`mapOf` 的 `instanceof` 分支**无真实触发路径**（仅测试替身）⇒ 归 T9/T10 观察。详见 `t6-evidence/t6-report.md`（§五 诚实清单、§六 缺口表）。
- ★ **本轮的"通则兑现"**：T5-L4 立的通则**在这一轮直接产出了自己的变异体（t6m7）并被杀**，且红点**含 T5 立的那条保链守卫** ⇒ 通则不是文字，是**有护栏的**。

### T7 收口（控制器，2026-09-21 07:13）
- **合并后门禁绿（★ 第 4 次尝试才落地）**：`./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、**1175** = 170/362/45/**246**/174/62/116、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t7-evidence/logs/merged-full-verify.log`（188,157 B）+ `t7-evidence/merged-verify-rc.txt`。反应堆耗时：父 17.747s / util 1:31 / map 2:06 / social 38.158s / unit 1:07 / core 1:24 / sd 58.088s / app 1:46。
- **delta 干净**：与 T6 的 1162 对差，**只有 `simos-unit` 233→246（+13）**，其余六个模块计数逐字不变（170/362/45/174/62/116）⇒ 本轮范围外零改动，实测背书。
- ★★ **T7 归因更正（推翻我上一轮写进台账的那条）**：T5 那轮我写下「被杀是因为用了 Bash 默认 120 s 超时」。**T7 实测：显式 `timeout=600000` 照样被杀，而且连杀 3 轮**（留档 `merged-full-verify.attempt{1,2,3}-killed.log` + 同名 `.harness-output.txt`，**不删**）。⇒ 真正的机制是：**本树全量门禁耗时正好压在 600 s 线附近** ⇒ 一旦越过，harness 把进程**摘到后台**，而**后台态正是内存守卫管的**——**被摘后台 × 内存压力 = 被杀**，与超时值无关。T5 那次「一次 rc=0」不是因为我"改对了超时"，而是**那一轮没越过 600 s**（T6 亦然：模块耗时合计 587 s，线下）。实测序列：T5 1 杀 1 成 / T6 0 杀 1 成 / **T7 3 杀 1 成**。★ **连带结论：全量门禁在这台机器上不是可重入的绿灯装置**，它的通过本身带运气成分 ⇒ 记录门禁结果时必须一并记「第几次尝试」，**不许把"某轮绿了"读成"该装置稳定"**。（我此前把「前台跑能跑完」当通则写进环境结论，现收紧为：**前台是必要条件，不是充分条件**。）
- **合并方式与哈希漂移**：`ue/t7` 先 `rebase feat/adr1-core-scope`（干净）再 `--ff-only`。原因同 T6——我在派发后才提交了树外文件，导致分支基点不再是 main 的祖先。T7 的实现提交 `5768809` ⇒ 合并后在 main 上为 **`851e61b`**。★ 连带：T7 报告里引用的**自身**哈希随之漂移（这是控制器造成的，非实现者的错）。
- **T7 交付复核（控制器侧独立核过）**：分支只动 `simos-unit/`（5 个文件）+ `t7-evidence/`（29 个文件）；`simos-app` / `simos-core` / `simos-sd` / `simos-social` **零改动**；提交 **0 trailer**；worktree 干净。
- **变异（实现者交）**：**7 轮 / 7 被杀 / 0 存活 / 0 作废**，十道门禁逐轮 `verdict=OK`（含 ⑩ 逐片段自证：`orig 0 / pushed 1` 或 delete 型反向 `orig 1 / pushed 0`）。★ **m4（返回前改回 `new UnitState(units)`）的红点含 T5 既有的保链守卫** `advanceKeepsCommandChainsWhilePositionAndMovementChange:205` + T7 新立的 `rejoinTickKeepsCommandChains:462` ⇒ **T5-L4 通则第二次兑现，且这次是"被下游任务用自己的变异体撞上既有护栏"的形态**。
- ★ **m5 的两种形态一起报（裁定「不许只报一个写法」的又一次照办）**：形态① 被杀（结构判据 `stateHasNoPlaceToPersistAnEndpointHex` + `UnitRoundTripTest` 的"未登记的组件: rejoinAt"）；形态② **回归场景 6 条用例在 m5 下全绿**（那字段谁都不读 ⇒ **行为层没有判别力**）。⇒ 结论如实登记：**这类变异体只能靠结构/往返判据区分，行为判据对它无判别力**；它**不是存活项**（另有判据同时存在），且实现者**没有**为造红而放松任何判据。
- **裁定（U4/U5/U6，控制器确认在案）**：U4 存储 = `Unit.rejoinTarget: Optional<UnitId>` 普通字段（T7 **未改 `Unit.java`**）；U5「状态允许移动」= `status == MOVING`（`UnitOperations.rejoinRoute` 里的早退，注释标 `裁定 U5`）；U6 物化 = 每 tick 现算 + 写**新** `Movement`，唯一持久事实是引用。三者**各自有判据**（`:1395` / `:402`+`:1495`+m3 / ④ 逐值 + m1 + m7 + ⑤ 结构判据）。
- ★ **实现者的一处自我更正（当轮就地改，证据链处理正确）**：测试注释原写"本用例是唯一能区分 m5 的判据"，实测 `UnitRoundTripTest` 两条也能区分 ⇒ 改为实测口径后**重跑门禁**，并**只作废 A 轮那条日志（留档不删）**、说明**变异轮证据不受影响**（变异轮基线的生产字节 md5 与两轮门禁逐字节相同）。⇒ 这正是「同一文件被改动 ⇒ 旧证据对应旧字节；**md5 才是判据，mtime 不是**」的正确用法。
- **裁定（G1，控制器）——挂账，不在 T7 补**：`disband` 之后可能留**悬空 `rejoinTarget`**。现状：运行期口径安全（`effectivePosition` 对不存在 id 返空 ⇒ 不回归、不写任何东西），但**无判据覆盖**。★ **判据**：`disband` 属 T3/T4 已关账的 op 族，在 T7 里改它 = **改动既有护栏**（按裁定 42 须连带重跑 T3/T4 的轮次），收益是补一条**已被代码口径兜住**的前置 ⇒ **边际判别力低于证据代价**。⇒ **归 T10 关账轮**，与「同族判据清扫项」（13 处 token-only 断言）**同批**处理。
- **裁定（G2）**：`UnitPayloads` 类注仍写"十六个 handler"（实为 **18**）⇒ **归 T10 统一扫**（与 T5-L6 / T4 的 token 清扫同批；纯注释、零行为）。
- **裁定（G3）**：spec §9.1「不做 `REPLAN_EVERY_STEP`」vs P8「每 tick 重规划」**是两条轨道、不矛盾**——§9.1 说的是**在途普通路线**（照旧不重规划），P8 说的是**回归轨道**（每 tick 现算、产新 `Movement`）。★ **不回填 spec**：两条都在 spec 里各说各的对象，**不存在互相矛盾的句子**（与 T6-G1 那次"spec 里有矛盾句会误导下游"的形态**不同**）；实现者已在 `UnitTimeParticipant` 类注写明两轨关系。⇒ 若 T9 装配时仍读出歧义，届时按 T6-G1 的先例回填。
- **裁定（G4）**：T5-L4 的 1 参兼容构造器 `new UnitState(units)` **仍在**，维持原判 **归 T10**（T7 未改 `UnitState.java`；m5 轮改它的是**变异体**，原文件 md5 未变）。
- **裁定（G5，控制器）**：回归与**在途普通路线**同时存在时的交互**无 spec 依据**（现状：回归行程**替换**在途行程，`withMovement` 语义）。★ **不在 T7 补策略**：P8 只说"每 tick 重规划"，**没说与在途路线的关系**，凭空定策略就是**发明需求**；而这条**无判据**的状态已如实登记。⇒ **归 T10**，与 G1 同批；若届时仍无上游依据，**记为"未定策略"而非"已实现"**。
- **未做 / 开口项（与 T6 同族，如实结转）**：真档（19441 格）上的回归行为**未验**（判据全在合成小图 + 成本替身上）；`Movement.speedAtDeparture` 与 M3 口径**未逐值对拍**；多单位/多链同时回归的规模未测（用例是两单位规模）；前端 88/88 是**未改的复核**、不是"改过还绿"。详见 `t7-evidence/t7-report.md`（§五 诚实清单、§六 缺口表、§七 我未能核实的）。

### T8 收口（控制器，2026-09-21 07:54）
- **合并后门禁一次绿**：`./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、**1191** = 170/362/45/**257**/**177**/62/116、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t8-evidence/logs/merged-full-verify.log`（188,339 B）+ `t8-evidence/merged-verify-rc.txt`。
- **delta 干净**：与 T7 的 1175 对差 = **unit 246→257（+11）+ core 174→177（+3）= +16**，其余六个模块（170/362/45/62/116 与 util/map/social/sd/app）逐字不变 ⇒ 范围外零改动，实测背书。
- ★★ **环境模型再收紧一档（T8 是"被摘后台但不死"的第二个实例）**：本轮同样**越过 600 s 被 harness 摘到后台**，但**跑完了、rc=0、没被杀**。⇒ 我 07:15 写的「被摘后台 × 内存压力 = 被杀」**成立**（T8 恰好证明了那个乘号：**被摘后台本身不致命**，致命的是摘后台窗口内的内存压力）。实测序列更新为：T5 1 杀 1 成 / T6 0 杀 1 成（587 s，恰在线下）/ T7 **3 杀** 1 成 / **T8 0 杀 1 成（被摘后台、跑完）**。⇒ **全量门禁的成败是概率事件，不是"通道"属性**；唯一可操作的两条是：①走前台、②**独占**（本轮 T8 门禁期间控制器**一条命令都没跑**）。
- **合并方式与哈希漂移（第三次同形态）**：`ue/t8` 基于 `851e61b`，而我在派单后提交了 T7 收口（`aa3e308`）⇒ 分支分叉 ⇒ `git merge --ff-only` 报 `Diverging branches` ⇒ `rebase feat/adr1-core-scope`（干净：收口提交只碰 `CLAUDE.md`/`progress.md`/`t7-evidence`，与本任务 5 个 java 文件零交集）后再 ff。实现提交 `02b9dfa` ⇒ 合并后在 main 上为 **`8cc872d`**。★ **这是连续第三次**（T6/T7/T8）⇒ 记为本仓当前工作流的**系统性副作用**：**"派单"与"提交树外文档"不能同序**——以后要么先提交完再派单，要么接受一次 rebase。
- **T8 交付复核（控制器侧独立核过）**：改动面 = `simos-core` 新测试 1 个 + `simos-unit` 4 个（2 生产 + 2 测试）；**`simos-app` 与任何 `pom.xml` 改动数 = 0**；`git diff --numstat 851e61b HEAD` 五个文件全部 **纯增行（0 删除）** ⇒ **既有护栏字节未动** ⇒ T5/T7 旧证据不作废，裁定 42 由新护栏自带轮满足；提交 0 trailer；worktree 干净；`CommandBus.java` 等既有生产文件**不在改动面**。
- **变异（实现者交）**：**8 个变异体 / 9 轮 / 全部被杀 / 存活 0**，十道门禁装置（沿用 T7 同法），每轮 `restored == orig` 逐字节还原。★ **t8m5 撞上的是"面"不是"点"**：它改的是共享助手 `withUnit` ⇒ 连带打红 T5/T7 的三条既有护栏（不影响其证据——那些文件字节没动）。**T5-L4 通则第三次兑现。**
- ★ **t8m6 首轮"被反应堆短路"（★ 如实登记，且没有当成功劳）**：`simos-unit` 先红 ⇒ Maven 在到 `simos-core` 前就收工 ⇒ **core 侧断言根本没跑**。实现者**拒绝**把那轮记成"core 判据被杀"，补跑 `t8m6b`（同变异体 + `-Dmaven.test.failure.ignore=true`，**该轮不以 rc 判杀**，标志已写进日志 `extra_flags`）才取到 core 侧红点 `R2：100 + (−30) = 70`；两轮都留档。⇒ **"没跑到"不等于"没红"，这一点与 T6 的 VOID 轮同族，处理正确。**
- ★ **m2 的判据"强于行为"（两种写法一起报）**：`Unit` **构造期也**把守 `member ≥ 0`（`Unit.java:57` 实测抛 `member 必须 ≥ 0: -30`）⇒ 删掉 T8 的上界守卫**并不能**让负数落盘，红的是**领域理由文本**（`人员战损超出当前值`）。⇒ 结论如实登记：「**上界 ⇒ 可读的领域理由**」成立，「**值不变**」那半由构造期**独立**保证（对照变异体即 t8m2）。**未为造红放松任何判据。**
- ★★ **变异轮之后有一次被测文件改动（本轮唯一一处），controller 已独立复核，判"证据不作废"**：首轮模块门禁 rc=1 **但不是测试红**——`spotless:check` 判其新增 Javadoc 折行不合格式 ⇒ `spotless:apply`（**只动注释**）后复跑 rc=0。⇒ 这落在「**同一文件被改动 ⇒ 旧证据对应旧字节**」的地带，处理如下：① `pre-spotless_*.java` **原始字节留档**（锚点仍可复现）；② 实现者用 `strip-compare.py` 证「注释/空格外零改动」；③ ★ **控制器用另一份自己写的去注释脚本独立重算**（处理 `//`、`/* */`、字面量内转义）⇒ `UnitOperations.java` 去注释后 md5 `05159296…`、`ApplyCasualtiesHandler.java` `407a244c…`，**两份各自两侧相等**；④ 据此记 `t8-evidence/mutants/LINE-DRIFT-NOTE.md`。★ **但发现一处实现者措辞掩盖掉的细节**：`ApplyCasualtiesHandler.java` 的 baseline md5 与现状**本就相同**（`1d6b30a0…`）⇒ spotless **实际只改了 `UnitOperations.java` 一个文件**，报告里「两份文件均 IDENTICAL」对 handler 而言是**恒真**、不是"改过之后仍相同"——**已在该 NOTE 里更正**。
- **裁定（行号漂移：记档，不重跑）**：`UnitOperations.java` 因注释重排导致 **884→883 行、`applyCasualties` 178→140、`copy` 816→792** ⇒ t8m1/t8m2 日志里的**栈帧**引用指向旧行号。**判"证据不作废、也不重跑"**，理由：锚点规则护的是「**可复现**」，而此处 ①锚点字节在档 ②代码面同一性被**双重独立**证明 ③**承重的红点在未被 spotless 改动的测试文件里**（`UnitOperationsTest.java:1285/1259/1344/1300` 等，漂移的只是 `at …UnitOperations.copy(UnitOperations.java:816)` 这类栈帧）④⑩ 道自证的锚是**片段**不是**行号**。⇒ 把行号映射写进 NOTE 供读者对照；重跑（约 15 分钟跑道）换不到新的判别力，且同族漂移在 T9/T10 还会再出现。
- **裁定（G1/G2，控制器）——`equipment` / `personnel` 维持必填**：spec §五.2 与计划 T8 的载荷签名**都没有 `?`** ⇒ 依设计权威判**必填**，故 `{"id","personnel"}` 会被拒。★ **连带后果必须写明**：**只报人员战损时也得显式传 `equipment: {}`**。这是 **spec 文本 与 易用性** 的张力，**不在实现里私自放宽**（与 T6-G1「spec 是权威、留矛盾句会误导下游」同一条纪律）；若日后要改为可选，**先改 spec 再改用例**。
- **裁定（G3，控制器）——"真覆写历史"轮不做**：handler 是**纯函数**、射程内无法改写历史；真正的覆写落在 `Timeline`/`CommandBus` 落盘路径，那是 **T-core 已关账的代码**。⇒ 若做，就是**拿 T8 的标签去测别人的护栏**。已覆盖的命题是「**战损增量走的是普通命令路径**」，由 `UnitCasualtyRevisionTest` + t8m6b 的 core 侧红点实测成立。
- **裁定（G4，控制器）——不回填计划**：计划里 `simos-core` 的 `169 + K` 是**立项时数字、已过期**（实测 `174 + 3 = 177`）。**不改计划**：改了反而**抹掉"计划 vs 实测"的偏差记录**（与我在 T5 归因上「保留更正轨迹、不抹掉」同一条纪律）。⇒ 偏差记在台账 + `CLAUDE.md`（一律以**实测**为口径）。
- **裁定（G5，控制器）——接受为范围声明，不是缺口**：正 Δ / 缺失字段 / 非 JSON **未在 core 侧复验**，因为它们是**域层 + unit 侧**的判据（已在 `UnitOperationsTest` / `UnitCommandHandlersTest` 覆盖）；`UnitCasualtyRevisionTest` 的目标命题是**revision 与回退**，与它们不同轴。
- **未做 / 开口项（如实结转，**不得读作"已实现"**）**：前端 88/88 是**未改的复核**（本轮只碰 unit/core）；**T9 的 `Shell` 装配未做** ⇒ `ApplyCasualtiesHandler` **未注册**、catalog **未含新 type** ⇒ **"真 `Shell` 端到端"本轮未验证**；全量门禁之外的平台级验证（真档规模、跨 JVM 决定论）仍是既有开口项。详见 `t8-evidence/t8-report.md`（§五 诚实清单、§六 缺口表、§七 未验证）与 `t8-evidence/mutants/LINE-DRIFT-NOTE.md`。

### 控制器自记：T5-L5 那条教训在**复查工具自身**上复发（2026-09-21 07:58）
- **现象**：我在提交交接文档前跑 `git diff --cached | grep -ciE '^\+.*(Co-Authored-By|Generated with)'` 查 trailer，结果 **报了 1 处命中**。★ **是假阳性**——命中的是 `HANDOFF.md` 第 69 行**纪律文本本身**（「**不加 \`Co-Authored-By\`**」这句话）。
- **这正是 T5-L5 立的规矩**：「自证判据**必须逐条按被改的那个片段判，不得按整份文件**——注释里合法地提到被禁字样会让'整文件计数'型判据**误报**」。⇒ **同一条坑从我写的变异装置，搬到了我写的复查命令里**；形态一模一样，只是主客体互换了。
- **正确判法（已改用）**：`git log -1 --format=%B <sha> | git interpret-trailers --parse` ⇒ **按 trailer 格式解析**（`Key: value` 行），而不是"整份文本里找字符串"。实测结果：**`origin/main..HEAD` 全部 20 条提交、20/20 无 trailer**；单作者（`ConstantinXIV`）。
- **连带（写给下一个会话）**：本仓的 `CLAUDE.md` 与诸交接/台账文档**必然**包含被禁字样的**引用**（它们就是用来记这条纪律的）⇒ **任何"整文件/整 diff 计数"型的 trailer 或密钥扫描，在这些文件上一定假阳性**，必须按**格式**判、或按**逐片段**判。★ 同理适用于密钥扫描：文档里写的**字段名/纪律文本**不是密钥值——**值**才进不了日志。
- **为什么记这条**：这不是"我犯了个小错"，而是**一个已被立为通则的东西，在确立它之后不到一天就被它的确立者违反**——说明这类判据的**默认写法**（整文件计数）就是错的，**必须显式写成逐片段/按格式**，否则每次都要靠人复看才发现误报。**这条本身即是对 T5-L5 的加固。**
