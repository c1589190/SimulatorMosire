# Task 8 报告：`Replay`——checkpoint 纯优化的兑现处（2026-09-18）

**执行形态**：**控制器内联**（非派发）。理由同 Task 9——本轮派发的 agent 全部死于 429（账号级五小时额度），
且 429 是账号级的，换 agent 不解决。步骤已按计划 Task 8 的 Step 1~5 逐条走完。

## 1. 交付物

| 文件 | 状态 | 行数 |
|---|---|---|
| `simos-core/src/main/java/io/mosire/simos/core/store/Replay.java` | 新增 | 287 |
| `simos-core/src/test/java/io/mosire/simos/core/store/ReplayTest.java` | 新增 | 539（11 条用例） |
| `simos-core/src/test/java/io/mosire/simos/core/timeline/TimelineTest.java` | 改（14 → 15 条） | 新增裁定 39 的护栏 1 条 + 夹具 3 个 |
| `simos-util/src/main/java/io/mosire/simos/util/json/SimosObjectMapper.java` | 改（裁定 39 的修法） | +62 |

★ 计划的 Files 行只列了前两个。**后两个是执行期炸出来的**（见 §4 取代说明 3 与 §3 的 m4）：
`changeset_json` 的往返在**真模块变更集**上根本不成立，这是 Task 6 的一处真偏离在 Task 8 结算。

## 2. 实测结论行（照抄日志）

**关账全量绿轮**（`./mvnw clean verify`，日志 `task-8-evidence/full-verify.log`）：

```
rc=0
Tests run: 170 / 255 / 37 / 93 / 88   （util / map / social / unit / core）＝ 合计 643
BugInstance size is 0  ×5
[ERROR] 行数：0
```

★ **与上一绿轮（631 ＝ 170/255/37/93/76）逐模块对差**：util 170→170、map 255→255、social 37→37、unit 93→93
**一个都没动**（`SimosObjectMapper` 那条改动**没有扰动任何模块 codec 的字节**），core **76→88**，恰 +12 ＝
`ReplayTest` 11 + `TimelineTest` 1。⇒ 增量与预期逐条对上，没有"顺手多绿了几条"这种事。

**迭代轮**（`replaytest-run3.log`）：`ReplayTest` + `TimelineTest` 合并跑 → `Tests run: 26, Failures: 0, Errors: 0`，`BUILD SUCCESS`。

**首轮是红的**（`replaytest-run1.log`）：5 个 `UnrecognizedPropertyException: Unrecognized field "empty"`。这不是夹具写错，
是**真缺陷**，见 §4。

★ **一次 spotless 违规**（`full-verify.log` 之前的首跑）：`SimosObjectMapper` 的中文 Javadoc 手工断行被
google-java-format 判违规，`spotless:apply` 后重跑即绿。**这正是 CLAUDE.md 那条"不要手工调行宽"的又一次实例**——
我写注释时按语义断的行，格式器按字符数断。

## 3. 变异自证（逐轮）

装置：`task-8-evidence/mutants/mut-round.sh`，用法 `mut-round.sh <规范源> <变异体> <-Dtest=选择器> <日志> <期望红>`。
每轮**自证四件事**（形态 1 的装置要求）：① 开跑前工作树 md5 **必须等于**原件（否则作废这一轮）；
② 变异体 md5 **必须不等于**原件（否则 javac 编的可能还是原件，三向全绿不算数）；
③ 按**白名单**把变异体推成**目标类名**；④ 跑完 `cp orig → 规范路径` 并**校验 md5**——**不是 `git checkout --`**。
每轮打印 `COMPILATION_ERROR_lines` 与 `Tests_run_lines`，**前者必须为 0、后者必须 ≥ 1**。

| 轮 | 变异 | 期望红在哪（计划） | 实际红在哪（实测） |
|---|---|---|---|
| m1 | `Timeline.hasCheckpoint` 去掉第 ② 项（分岔强制） | R5 的跨分支用例 | ✅ `ReplayTest.replayStaysWithinTheCheckpointIntervalEvenAcrossAFork:290`，消息 `[R5：跨分支的 target 也必须 ≤ N = 4（靠 C19 第②项「分岔点强制一次 checkpoint」）]`；ReplayTest 3 failures ＋ TimelineTest 1 failure（`hasCheckpointFollowsTheThreeCriteria`） |
| m2 | `Replay`：`decodeCheckpoint` 之后 `path.clear();`（读到的 checkpoint 状态直接返回，不施加 path） | R4 对拍 | ✅ 6 failures，含 `replayRebuildsTheIndependentlyConstructedTruthAtEveryCoordinate:250`、`replayFromTheNearestCheckpointEqualsReplayFromGenesis:266`、`replayFallsBackToAnEarlierCheckpointWhenTheNearestFileIsMissing:317` |
| m3 | `Replay`：**照抄伪码**——走到"应当有" checkpoint 就 `orElseThrow`（即"文件不在就炸"） | （计划未列，见下） | ✅ `replayFallsBackToAnEarlierCheckpointWhenTheNearestFileIsMissing:313 » IllegalState m3: 只看应当有，文件不在就炸` |
| m4 | `SimosObjectMapper`：删掉 `changesetsWithoutDerivedPredicates()` | （计划未列，见下） | ✅ `TimelineTest.changeSetJsonRoundTripsRealModuleChangeSetsNotJustStandIns:278 [写侧不得把派生判断 empty 写进线格式（它是判断不是状态，裁定 39）]` |

**计划外补的两轮**（Step 5 的表只列了 m1/m2）：m3 与 m4 各自保护**一条计划里没有、执行期才出现的**护栏——
m3 保护**实现期校正 ①**（C18 的回退必须按文件可用性、不能按"应当有"），m4 保护**裁定 39 的修法**。
补它们的理由：这两条是本任务里**最容易被下一个人"照伪码改回去"**的两处，不钉住就是给未来埋雷。

★ 四轮的 `COMPILATION_ERROR_lines` **全为 0**、`Tests_run_lines` **全 ≥ 1**，还原 md5 逐轮校验通过。

## 4. 取代说明（计划/派单函 vs 实测，以实测为准）

### 4.1 Produces 行的 `lastReplayApplyCount()` → `ReplayResult` record（计划 Step 2 给了二选一，选前者）

计划 Step 2 让执行者二选一：`record ReplayResult(SimulationState, int applyCount)`，**或**实例字段。
**选 record**，三条理由（已写进 `ReplayResult` 的 Javadoc）：

1. 实例字段把 `Replay` 变成**有状态**的——两次并发重放互相覆盖计数，R5 的用例读到的可能不是自己那次的值。
   `Replay` 在 Task 13 的装配里是**长生命周期单件**、Task 15 要上真实并发 ⇒ 这不是洁癖。
2. "计数"与"状态"是同一次计算的**两个产物**，绑在一起返回就**不存在**"忘了先调 `replay` 再读计数"的用法错误。
3. M3 Task 12 的教训是**可变静态状态**；实例字段只是把它缩小到实例级，**形态相同**。

### 4.2 Step 1 伪码的循环条件：`while !hasCheckpoint(cur)` → **按文件可用性回退**（实现期校正 ①）

伪码只看「**应当**有 checkpoint」（`hasCheckpoint` 是 C19 的纯函数判定）；而 C18 明说 checkpoint **缺失不回退失败**、
要「回退到更早的 checkpoint，最坏从创世重放」。二者合起来要求循环判的是「**这一坐标的 checkpoint 文件此刻读得出来吗**」。
照抄伪码的话，`replayFallsBackToAnEarlierCheckpointWhenTheNearestFileIsMissing` 那条用例会**当场炸**——m3 就是它。

### 4.3 Step 3 的 `fromGenesis` 开关 → **做成四参构造参数**，不是方法参数

计划说"给 `Replay` 一个显式的 `fromGenesis` 开关供测试用"。**做成构造参数**：它是**装配期属性**，
不该在运行期被逐次选择；放在构造上，调用点必须**显式写出意图**，而不是在某次调用里悄悄传个 `true`。
生产装配恒为三参构造（`fromGenesis = false`）。★ 计划 Step 3 同时说"**不要**改 `hasCheckpoint` 的生产判定
（会给 R3 的『`hasCheckpoint` 与磁盘文件逐条一致』埋雷）"——本条遵守了：`hasCheckpoint` 一个字节没动。

### 4.4 ★★ `decodeEnvelope(rev.changeset_json)` → 实测是 **Task 6 的一处真偏离**（裁定 39）

**这不是措辞问题。** spec §3.2 把 `changeset_json` 定义成「**信封（C26），模块载荷是其中的一段文本**」——
即 Core 只搬**不透明文本**。而 Task 6 落成了 `WorldChangeSet` 的整体 JSON ＋ `Id.CLASS` 多态类型信息，
**Core 于是内省了模块类型**（C26 的原意被破）。

偏离在 Task 8 当场炸出来（日志 `probe-changeset-wire-BEFORE.log`）：

```
PROBE[map]-HAS_EMPTY_PROPERTY=true
PROBE[map]-CAUSE=UnrecognizedPropertyException: Unrecognized field "empty"
   (class io.mosire.simos.map.change.MapChangeSet), not marked as ignorable
   (7 known properties: "terrainTypes","edges","hexes","pathwayGroups","regions","cities","pathways")
PROBE[unit]-HAS_EMPTY_PROPERTY=true   （同型）
```

**根因**：三个模块的变更集都有 `public boolean isEmpty()`（**派生判断，不是状态组件**）。Jackson 的 bean 内省
把它当成属性 `empty` **写进字节**，而读侧 `FAIL_ON_UNKNOWN_PROPERTIES` 保持默认的严格
⇒ **写出来的档，自己读不回**。`Timeline.changeSetJson` 出来的 JSON 含 `"empty":false`，
`Timeline.readChangeSet` 随即抛 `UnrecognizedPropertyException`。

**为什么三个模块 codec 挡不住**：`MapCodec`/`SocialCodec`/`UnitCodec` **各自**用 mixin 把 `isEmpty()` 摘了出去
（M4 Task 3 的实测发现，三处都做对了）——而 `Timeline` 是从 `SimosObjectMapper.create(...)` 另起**第四台** mapper，
它按 ADR-1 **看不见任何领域类型**，装不上那三个 mixin。**三处各自都对、中间却没有装配点。**

**为什么 Task 6 的既有护栏没抓到**：`TimelineTest.changeSetJsonCarriesTypeInfoAndRoundTrips`（第 213~223 行）
用的是替身 `record ToyChangeSet(int v) implements ChangeSet {}`——**它没有 `isEmpty()`**。判别力差的就是**那一个方法**。

**修法：落在共享层**（`SimosObjectMapper.changesetsWithoutDerivedPredicates()`，一个 `SimpleModule`
带 serializer + deserializer modifier，条件是 `ChangeSet.class.isAssignableFrom(...)`）。三条理由：

1. **它为什么能放共享层**：`ChangeSet` 是 **util 自己的标记接口**，`"empty"` 是一个字符串——本条**不认识任何领域类型**（铁律 3 不破）。
2. **为什么不能用 mixin**：mixin 在同一个 target 上**只有一份**（`SimpleMixInResolver` 的语义），
   而 `Timeline` 已经给 `ChangeSet` 装了 `@JsonTypeInfo` 的 mixin——再加一个会**碰撞**。用 modifier 才与它**相加**。
3. **它不是"把严格关掉"**：`FAIL_ON_UNKNOWN_PROPERTIES` 保持默认。这里声明的是**一个具名的派生判断不进线格式**
   ——与三个模块 codec 的 mixin 是**同一条事实**，只是从"各模块各写一遍"提为"共享层写一遍"。

**三条模块级 mixin 因此成为冗余，但保留**（改动面越小越好，且它们是更具体的同一句话）。
读侧的 `addIgnorable` 是给**旧字节**的（本修法落地前产出的档里带着 `empty`），落地后的字节本就不含该属性。

**范围止于此处**：把载荷真的改成不透明文本会改 `WorldChangeSet` 的**类型**，牵动 Task 4/6/9 三个**已关账**任务
⇒ **不在此裁决**，记成台账**裁定 39** 的「带裁定的遗留条目」。

### 4.5 `Replay` 类注的校正 ② 曾写错，已改正

我第一版写的是"伪码措辞松散、列名与内容名不同"——**错了**。实测（读 spec §3.2 第 166 行）显示 **spec 规定的是不透明文本、
Task 6 偏离了**。类注已重写为如实记录，并指向裁定 39。★ 记在这里是因为**这正是形态 5**：我差点把一个"规范 vs 实现"的
**真偏离**写成一次"用词不严谨"。

## 5. 我未能核实的（不许当结论引用）

1. **`Replay` 从未在真由 `CommandBus` 写出来的 revision 上跑过。** `ReplayTest` 的 revision 行是夹具**直接落盘**的
   （`timeline.appendRevision(...)`），不是 `CommandBus.submit` 的产物。⇒ Task 9 报告 §5 那条"`StateLoader` 的真实装配
   `replay::replay` **从未在真状态上跑过**"**依然成立**，本任务没有消掉它。Task 13 装配时第一个要看这里。
2. **R5 的上界是条件成立的**，不是无条件。它依赖「C19 说应当有的 checkpoint 确实都在」。劣化形态（文件缺失）
   **会超 N**——`replayFallsBackToAnEarlierCheckpointWhenTheNearestFileIsMissing` 把这条**写成了断言**
   （`isEqualTo(8)` ＋ `isGreaterThan(N)`），免得下一个人把 R5 读成无条件。
3. **裁定 39 的深层形态未做**：`changeset_json` 目前仍是 `WorldChangeSet` 的整体 JSON ＋ `Id.CLASS`
   ⇒ **JSON 里含全限定类名，挪包即旧档不可读**（Task 6 已记的坑，本任务没动它）。
4. **`changesetsWithoutDerivedPredicates()` 在 `simos-util` 内没有自己的守卫。** 它的护栏落在 `simos-core`
   的 `TimelineTest`（**有意如此**：这条规则的**意义**是四台 mapper 一致，只有跨 mapper 的用例验得了它；
   在 util 里写一条"摘得出 empty"的用例是**同义反复**）。但如实记下：**util 的用例数没变（170），
   这次的纯增量全在 core。**
5. **它是"按名的规则"，不是"按语义的规则"。** `"empty"` 是个字符串常量。若将来某个 `ChangeSet` 实现的
   **状态组件真的叫 `empty`**，本条会把它一并误摘。**已当场核查三个实现都没有**（`MapChangeSet` 7 个组件、
   `UnitChangeSet` 1 个、`SocialChangeSet` 同型），但这是**当时为真**，不是**结构上保证**。
6. **`readInfo` 落到具体类型 `InMemoryInfoSystem` 是接缝，不是终局。** `InfoSystem` 是接口且 util **没有**给它的 SPI。
   真出现第二个实现时，这里要跟着长出编解码口子（或者 Core 干脆不碰它）。
7. **并发未测**：`Replay` 没有并发用例。Task 10（③ 与锁纪律）与 Task 15 的范围。
8. **跨 JVM 的字节漂移未测**（与 Task 6 同一条未测项，本任务没扩大也没缩小它）。
9. **`ReplayTest` 的真值覆盖的是"三模块都被改过"的链**，没有覆盖"某个模块从始至终一次没被改过"的极端形态
   ——`truths` 里三个模块的快照坐标都有推进。`applyWorld` 的"变更集里没提到的模块保持原样"这一句，
   在**每一步**都被验到了（每步只改一个模块），但**整条链上一次都没提名的模块**没验。

## 6. 证据清单（`task-8-evidence/`）

| 文件 | 是什么 |
|---|---|
| `full-verify.log` | ★ 关账全量绿轮（643 条、rc=0） |
| `replaytest-run1.log` | **首轮红**：5 个 `UnrecognizedPropertyException`（真缺陷的现场） |
| `replaytest-run2.log` / `replaytest-run3.log` | 中间轮 / 迭代绿轮（26 条） |
| `probe-changeset-wire-BEFORE.log` | ★ 裁定 39 的**修前**取证（`HAS_EMPTY_PROPERTY=true` ＋ 必抛） |
| `probe-changeset-wire-AFTER.log` | 修后对照（`HAS_EMPTY_PROPERTY=false`） |
| `m1.log`~`m4.log` | 四轮变异的 maven 日志（红在哪、消息是什么） |
| `mutants/mut-round.sh` | 变异装置（自证四件事，见 §3） |
| `mutants/{m1..m4}.*.java` | 四个变异体 |
| `mutants/orig/{Timeline,Replay,SimosObjectMapper}.java` | 原件参照（还原源） |

★ 装置的 stdout（`orig=` / `mutant=` / `worktree_before=` / `worktree_restored=` 那些自证行）**不在 log 里**
——`mut-round.sh` 把 maven 输出重定向进 `$LOG`，自证行打到脚本的 stdout。四轮的自证行都在会话记录里逐轮核过，
`worktree_restored` 与 `orig` 的 md5 逐轮相等。
