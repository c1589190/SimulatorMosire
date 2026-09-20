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
