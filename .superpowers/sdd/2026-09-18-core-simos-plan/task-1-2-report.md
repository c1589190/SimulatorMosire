# M4 Task 1 + Task 2 报告（批次 A）

执行者：批次 A agent（Claude）｜日期：2026-09-18｜工作树：`feat/adr1-core-scope`
计划：`docs/superpowers/plans/2026-09-18-core-simos-plan.md`｜spec：`docs/superpowers/specs/2026-09-18-core-simos-design.md`

---

## 一、改了哪些文件（逐条 + 为什么）

### Task 1：契约收敛（spec §十）

| # | 文件 | 改动与理由 |
|---|---|---|
| 1 | `simos-util/src/main/java/io/mosire/simos/util/state/ChangeSet.java` | 收窄为**标记接口**（删 `baseRevision()`），Javadoc 按 spec §十 / C27 改写（版本戳归 Revision 层、parent 指针是结构不变量、U13 稳定承诺）。★ Javadoc **不写被删方法的名字**——R15 全仓扫描不分注释与代码（见取代说明 8） |
| 2 | `simos-util/src/main/java/io/mosire/simos/util/verify/RoundTripAssertions.java` | 删 `assertSnapshotRoundTrip` 整段（javadoc + 方法）；`assertRoundTrip` 与 `checkApplied` **逐字节未动**。删随之无消费者的 **两个** import：`RevisionId`（计划已写明）与 `Snapshot`（计划漏了——它只被被删方法的泛型上界用过，见取代说明 4） |
| 3 | `simos-util/src/test/java/io/mosire/simos/util/state/SnapshotProtocolTest.java` | 第二个用例（lambda 用例，唯一编译破坏）**改写为 R18**：`ChangeSet.getDeclaredMethods()==0`、`Command.getDeclaredMethods()==1` 且名字为 `expectedRevision`；注释写明数的是 `getDeclaredMethods()` 而非 `getMethods()`（计数型断言必须写清数的是什么）。类 Javadoc 同步（原"ChangeSet/Command 都是单方法接口"已不成立） |
| 4 | `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java` | 删以快照专用入口为被测对象的部分：整条删 `aMisStampedChangeSetIsRejected`、`aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught`（现场实测是 **4 处调用 / 4 个用例**，非 spec 说的"三个"，见取代说明 1）；`aCorrectRoundTripPasses` 删其快照半边；`aNullReturningDiffOrApply…` 删第三断言与过时注释。**保留** `aBrokenRoundTripReportsAllThreeStates`、`theFrameworkDoesNotRequireSnapshotImplementations` 与全部玩具类型（含计划错判要删的 `PlainState`/`PlainChangeSet`，见取代说明 3）。**新增 R15 用例**（needle 拆两半拼出，见取代说明 5） |
| 5 | `simos-util/src/test/java/io/mosire/simos/util/verify/RepoSourceScan.java` | **新建**（test scope）：从 surefire 工作目录向上找仓根（判据 = pom 自身 artifactId 是 simos-parent **且无 `<parent>` 段**，见取代说明 7）、`javaFilesUnder(String...)`（跳过 `target/` 与隐藏目录、`normalize()` 必需，见取代说明 7a）、`rawContent(Path)`、`relative(Path)` |
| 6 | `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java` | 声明行加 `implements ChangeSet` + import + 补一段实现说明 Javadoc。**7 个组件、`between`/`apply`/`isEmpty` 零变化** |
| 7 | `simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java` | 同上；**并改写原 C8 段**（原 Javadoc 明言"不实现 util 的 ChangeSet 接口（C8）"，与本次改动矛盾——C28 之后版本戳顾虑已随接口收窄消失，理由引 C27） |
| 8 | `simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java` | 同 social |

**未删**：`RoundTripAssertionsDriftTest.java` **整份保留、一字未动**——计划 Step 4 说"整份删除"，但它的唯一用例走的是 `assertRoundTrip`、与被删方法无关（见取代说明 2）。

### Task 2：`util.spi` 五类型 + `package-info`

| # | 文件 | 说明 |
|---|---|---|
| 9 | `simos-util/src/main/java/io/mosire/simos/util/spi/HandlerOutcome.java` | sealed 接口 + `Applied(ChangeSet)` / `Rejected(String)` 两 record，紧凑构造器 `requireNonNull`。照计划 Step 1 草图 |
| 10 | `…/spi/CommandHandler.java` | `type()` + `handle(SimulationState, String)`。照草图（Step 2） |
| 11 | `…/spi/ModuleCodec.java` | **三参 `apply(ChangeSet, Snapshot, StateMeta)`**——执行期裁定 **C28**（见取代说明 11）。其余五方法照草图 |
| 12 | `…/spi/TimeProposal.java` | 四组件 record；防御性拷贝**保序**（LinkedHashSet + 不可变包装）；逐元素 `requireNonNull`（消息 `<name> 的元素`）。★ `Collections.unmodifiableSet(...)` **写在字段赋值表达式上**——SpotBugs EI_EXPOSE_REP 不做跨过程分析（见取代说明 12） |
| 13 | `…/spi/TimeParticipant.java` | `namespace()` + `simulate(SimulationState, TimeRange)`。照草图（Step 5） |
| 14 | `…/spi/package-info.java` | U13 书面落点：契约面一旦成型即稳定；三接口各自的稳定性承诺；为什么不拆 `simos-spi`（U12）。照草图（Step 6） |
| 15 | `simos-util/src/test/java/io/mosire/simos/util/spi/SpiShapesTest.java` | 四用例：sealed 恰两变体 / 防御性拷贝 / **保序（5 键，乱序喂入）** / 集合内 null 构造期炸（`hasMessage` 精确匹配）。`emptyChangeSet()` 用**匿名类** `new ChangeSet() {}`（标记接口非 SAM，lambda 编不过——计划已标出的坑），注释说明缘由 |

---

## 二、实测数字

| 项 | 实测值 |
|---|---|
| `./mvnw -q spotless:apply` | rc=0 |
| `./mvnw clean verify` | **rc=0**（日志 `/tmp/m4-verify.log`） |
| 用例总数 | **520**（util 159 / map 248 / social 30 / unit 68 / core 15；M3 关账 517 − 删 2 个用例 + 增 5 个 = 520，账目吻合） |
| `BugInstance size is 0` | **×5**（五个模块各一次） |
| `^\[ERROR\]` 计数 | **0** |
| `^\[WARNING\]` 计数 | 1 |
| Task 1 完成后四模块联测 | rc=0（变异开跑前基线） |
| 全部变异轮结束后复测 | rc=0（还原干净世界后） |

---

## 三、变异表逐条结果（G13）

装置六纪律逐轮执行：原件 `cp` 备份 + md5；变异体先以临时名落盘、md5 证明字节不同；按**目标类名**推入、推入后再比 md5；每轮开跑前恢复原件重编；断言 `grep -c "COMPILATION ERROR"` 为 0；回滚一律 `cp` 回填 + md5 比对（全程未用 `git checkout --`）。

### Task 1（红点用例：`SnapshotProtocolTest` / `RoundTripAssertionsTest`）

| 变异 | 做法 | 结果 |
|---|---|---|
| m1 | `ChangeSet` 加回 `RevisionId baseRevision();` | **红**：`changeSetIsAMarkerAndCommandIsStillASingleMethodInterface`（SnapshotProtocolTest:33，`hasSize(0)`，"Expected size: 0 but was: 1"），COMPILATION ERROR=0，同类另一用例仍绿。★ 备忘：变异体初稿把方法插到了接口体**外**（会变编译错误）——staging 阶段发现即重造，未推入 |
| m2 | `Command` 加第二个抽象方法 `String label();` | **红**：同用例 ：36（`hasSize(1)`，"Expected size: 1 but was: 2"），COMPILATION ERROR=0 |
| m3 | `RoundTripAssertions` 注回空体 stub | 第 1、2 轮**作废**（见下"m3 三轮"），第 3 轮**红**：`snapshotRoundTripAssertionIsGoneFromTheWholeRepo`（RoundTripAssertionsTest:116 `isEmpty()`），命中文件清单恰为被变异的 `RoundTripAssertions.java` 一个，COMPILATION ERROR=0 |

**m3 第 1 轮（绿→作废）"为什么没红"的答案**：测试确实跑了（5 条全绿），但扫描恒空——`REPO_ROOT.resolve(".")` 会在路径里留下 `"."` 这个名字元素，我的隐藏目录过滤（`startsWith(".")`）把**所有文件**都滤掉了 ⇒ **R15 当时是恒绿的装饰**。这是本轮变异抓出的第一个真缺陷（jshell 实证 `"."` 确为路径名元素）。修复：`normalize()`。
**m3 第 2 轮（基线红→作废）**：修好扫描器后，**无变异**的干净基线反而红——扫描抓到 `ChangeSet.java` 的 **javadoc**（计划 Step 1 草图原文就含那个方法名的连续字面量）。这证明扫描有牙，也坐实了计划 Step 1 与 Step 6 自相矛盾（取代说明 8）。改写 Javadoc 后第 3 轮按上表变红。

### Task 2（红点用例：`SpiShapesTest`）

| 变异 | 做法 | 结果 |
|---|---|---|
| m1（草图形态）/ m1'（最终形态） | 去掉逐元素 `requireNonNull` | 两轮均**红**：`nullAddressInsideTheSetIsRejectedAtConstruction`（:57，"Expecting code to raise a throwable"——构造不再抛），COMPILATION ERROR=0。最终形态重跑是因为 SpotBugs 整形（见取代说明 12）之后"红点必须落在最终被保护行上" |
| m2（草图形态）/ m2c（最终形态） | 不可变拷贝改 `Set.copyOf` | 草图形态：**红**在 `timeProposalPreservesIterationOrder`，并当场量 **30/30 红、0 绿**（1 次编译运行 + 29 次独立 JVM fork，5 键夹具）。最终形态重跑同红，再量 **30/30 红、0 绿**。与 M2 Task 5"4~6 键 0/30"的实测结论一致 |
| m3 | `HandlerOutcome` 去掉 `sealed` | **红**：`handlerOutcomeIsSealedWithExactlyTwoVariants`（`isSealed()` 为 false，"Expecting value to be true but was false"），COMPILATION ERROR=0。该文件形态其后未再变，红点仍有效 |

**m2 的作废轮（如实记）**：① 第一次变异体替换后残留未用的 `import java.util.Collections`，红的是 **Checkstyle**、测试根本没跑到 → 轮次作废，变异体连 import 一起换后重来；② 最终形态第一轮又踩同坑（红=Checkstyle），且其后 14 次"全绿"的 `surefire:test` 跑的是**上一轮残留的 m1' 变异类**（`target/classes` 未重编）——**测错了对象，数据整批作废**（这正是"陈旧 .class 活到下一轮"的教科书形态）；③ 另有一次 rc=127 纯属装置失误（复合命令 `cd` 后 `./mvnw` 相对路径失效，构建根本没启动）。最终以"恢复原件→重编→基线绿→推变异→编译运行确认红在序断言→再量 29 次"的干净序列收口。

**存活（等价变体）：本批次无。** 六条有效变异（T1 m1/m2/m3、T2 m1'/m2c/m3）全部红在被保护的那一行上。

---

## 四、执行期取代说明（计划原文在哪、改成什么、为什么）

| # | 计划/spec 原文 | 现场实测 | 处置 |
|---|---|---|---|
| 1 | spec §十与计划 Task 1 Step 4："它的**三个**用例" | `assertSnapshotRoundTrip` 在测试里是 **4 处调用、分布在 4 个用例**（`aCorrectRoundTripPasses` 混合、`aMisStampedChangeSetIsRejected` 整条、`aNullReturning…` 第三断言、`aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught` 整条） | 按现场处置（计划自己写了"若与现场不符，以现场为准"）：整条删 2、半边删 1、断言删 1 |
| 2 | 计划 Task 1 Step 4："`RoundTripAssertionsDriftTest.java` **整份删除**——它整个类就是'盖错版本戳时要抛'" | 该文件唯一用例走 **`assertRoundTrip`**、从不调被删方法、也不测版本戳；spec 爆炸半径表的处置本是"照样编译，只留过时注释" | **整份保留、零改动**（spec > plan、src > plan；删它等于删一条活护栏） |
| 3 | 计划 Task 1 Step 4："连同只服务它们的玩具（`PlainState` / `PlainChangeSet`）一并删" | 这两个玩具服务的是**要保留的** `theFrameworkDoesNotRequireSnapshotImplementations`（G13 判 M-2 的那条 `assertRoundTrip` 用例） | **保留** |
| 4 | 计划 Task 1 Step 2："删掉随之不再使用的 `import …RevisionId;`" | `Snapshot` import 也随之无消费者（只被被删方法的泛型上界用过） | 两个 import 都删 |
| 5 | 计划 Task 1 Step 6 草图：`rawContent(p).contains("assertSnapshotRoundTrip")` | ① needle 以连续字面量出现在 R15 用例自己源文件里，而扫描范围含 `src/test`（计划自己的要求）⇒ **恒红**；② `rawContent` 抛受检异常，lambda（Predicate）传不出去 ⇒ **编不过**（"编译得过跑不过"的反例：直接编不过） | needle 拆两半拼出（`"assertSnapshot" + "RoundTrip"`）；受检异常经本地 `content()` 拆包成 UncheckedIOException |
| 6 | 计划 Task 2 Step 7 的 ⚠️：`emptyChangeSet()` 必须写匿名类 | 照办（`new ChangeSet() {}`），并在用例 Javadoc 写明缘由 | 无偏离，记录为"计划已标出的坑确实在" |
| 7 | 计划 Task 1 Step 6："从 `user.dir` 向上找到**含 `simos-parent` 的** `pom.xml` 作仓根" | 模块 pom 的 `<parent>` 段**同样含这个串** ⇒ 从 simos-util 向上会停在模块 pom ⇒ R15 只扫一个模块，"全仓 0 处"成**假绿**（根 pom 实测 0 个 `<parent>`、模块 pom 1 个，判据成立） | 判据改为"pom 自身 artifactId 是 simos-parent **且无 `<parent>` 段**" |
| 7a | 同上（`javaFilesUnder(".")` 的 `.`） | `resolve(".")` 留下 `"."` 名字元素 ⇒ 隐藏目录过滤把所有文件滤光（m3 第 1 轮实测） | `resolve(relativeDir).normalize()`，注释写明来历 |
| 8 | 计划 Task 1 Step 1 的 `ChangeSet` 草图 Javadoc 含"原 `assertSnapshotRoundTrip` 守卫的正是这条" | 该连续字面量会让 R15 **恒红**（扫描不分注释与代码；m3 第 2 轮的基线红就是它）——计划 Step 1 与 Step 6 自相矛盾 | 改写该句不写其名，并加一句"扫描不分注释与代码，故本文件 Javadoc 不写它的名字" |
| 9 | 保序用例键数：计划说"用 4~6 个…当场量一次" | 用 **5 键**，m2 变异下两次独立测量均 **30/30 红、0 绿** | 照计划裁量，实测数字在案 |
| 10 | spec §八 / 计划 Task 2 Step 3 的 `ModuleCodec.apply`（本会话开局读到的是二参） | 会话中途收到协调者通知：**C28** 已入 spec §〇.2/:48、§八/:468、计划 Task 2 Step 3/:438 与 Task 3/:721,1229——要求三参 `apply(ChangeSet, Snapshot, StateMeta)`。当场重读两份文档确认更新在案（我按其第 3 条要求核对：非"条文冲突"，是文档在我读取后被裁定更新） | `ModuleCodec` 改三参 + import `StateMeta`；并逐一复核其余四个类型与更新后的计划/spec——**无同类冲突**（HandlerOutcome/CommandHandler/TimeProposal/TimeParticipant/package-info 与现文逐字一致）。C28 的事实前提（三个模块 `XChangeSet.apply` 返回 `GameMap`/`SocialData`/`UnitState`、不带 ref/timestamp）与我在 Task 1 读到的三个 `apply` 签名相符 |
| 11 | 计划 Task 2 Step 4 草图：`immutableCopy` 私有方法内返回 `Collections.unmodifiableSet(...)` | `clean verify` 红：SpotBugs **EI_EXPOSE_REP ×2**（`reads()`/`writes()` "暴露内部表示"）——包装藏在私有方法里，SpotBugs 不做跨过程分析看不见 | 包装挪到**字段赋值表达式**（紧凑构造器里直接 `Collections.unmodifiableSet(orderedCopy(...))`），私有助手改返回裸 `LinkedHashSet`；`spotbugs:check` 单独验证过绿。**m1/m2 变异随后在最终形态上重跑重测**（上表 m1'/m2c） |
| 12 | —（现场处置） | `SocialChangeSet`/`UnitChangeSet` 的 Javadoc 原文明言"**不实现** util 的 `ChangeSet` 接口（C8）：版本戳属 Revision 层"，与新 `implements` 直接矛盾 | 改写该段（引 M4 / spec §十 / C27），字段与测试零变化不变 |

---

## 五、我未能核实的（与"我验过了"分开）

1. **变异轮之间的中间状态未全量回归**：六轮变异各自只编译并运行目标测试类；"改动后全绿"由 Task 1 完成后的四模块联测、全部变异结束后的四模块联测、以及最终 `clean verify` 三个点背书。中间某两轮之间若存在恰好互相抵消的双红，这三个点抓不住（我没有证据表明存在）。
2. **m2 的 0/30 是经验值**：每轮 fork 新 JVM（新哈希盐），30/30 红、0 绿；但"5 键在任何盐下都必红"我没有推演证明，也没有跨多台机器验证——与 M2 的 0/30 同属实测归纳。
3. **C28 的 spec §〇.3 第 10 条原文我没有读**（通知里引用的出处之一）；我核对的是 §〇.2 C28 行、§八 apply 行、计划 Task 2 Step 3 与 Task 3 两处，以及三个模块 `apply` 的真实签名。C28 的其余推论（Core 在 Commit 前算 newMeta、Validate 第 4 项核对）属 Task 12/13 的执行范围，本批次未验证。
4. **`git status` 里的 `M CLAUDE.md` 不是我的改动**（本会话开局时不存在），是谁改的、改了什么，我没有核实、也**没有入提交**；`docs/` 下两份未跟踪的 plan/spec 同样未入提交（不在我的白名单）。`.superpowers/sdd/2026-09-18-core-simos-plan/` 里的 `probe-jackson.md` 与 `progress.md` 形主是控制器/探针，我未读、未提交。
5. **基线"改动前"的分模块用例数没有当场跑出来**（只跑了目标三个测试类）；util 的改动前用例数 156 是由"改动后 159 − 新增 4 + 删除净 1"倒推的，与 M3 关账总量 517 的账目吻合，但不是直接实测。
6. **探针结论（Jackson 往返）与 `simos-core` 的测试**（`AgentLibAvailabilityTest` 等）只经 `clean verify` 绿间接背书，没有单独复核其内容。
7. 一次 `clean verify`（第一次）红在 SpotBugs EI_EXPOSE_REP——那是我的 TimeProposal 形态问题，已修；期间**没有**出现协调者预警的"与探针构建相撞"的可疑失败（探针文件在提交前已确认清干净，`git status` 无 simos-core 残留）。

---

## 六、提交

文件清单（显式 add，无 `git add -A`）：上文一、二节列出的 15 个 java 文件 + 本报告（`git add -f`）。**不含** `CLAUDE.md`、`docs/` 两份未跟踪文档、`probe-jackson.md`、`progress.md`。提交信息不加 Co-Authored-By trailer（尽管仓内今日的 91fd1cc 有——那是控制器所加；本批次遵 brief 指示）。
