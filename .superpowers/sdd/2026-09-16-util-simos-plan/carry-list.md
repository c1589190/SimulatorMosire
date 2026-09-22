# M1（util-simos）终审必须携带的遗留项清单

**对着 HEAD 重推的时点**：**`git rev-parse HEAD` = `28c7e77`**（`docs: M1 UtilSimos 关账——判据核对、计划期细则回填 spec、状态表同步`）。
> 核查期间 HEAD 动了两次：起手是 `54ef235`，T11「M1 关账」在我写这份清单的过程中先后把它 `git add` 进索引、随后提交为 `28c7e77`。**下面的结论以 `28c7e77` 为准**；`28c7e77` **只动了 4 个文档**（`CLAUDE.md` / `simos-master-plan.md` / `util-simos-plan.md` / `util-simos-design.md`，`git show --name-only | grep -c 'src/'` = **0**），**未碰任何 `src/` 文件**，故所有代码/测试侧的条目不受这次提交影响——我逐条重跑过接缝空格那两条计数命令，`28c7e77` 上仍是 **7 文件 / 8 行**。

**来源**：`.superpowers/sdd/2026-09-16-util-simos-plan/progress.md`（**1530 行**）。**所有行号均按这一版复核过**。
**谁写的**：只读核查代理（控制器派发）。除本文件外**未修改任何文件**，未 `git add`/`commit`/`checkout`，**未跑 Maven**。
**纪律**：下面每一个「已关闭」都附我亲自看过的**提交 SHA 或 文件:行**；每一个「仍开着」都附我亲自看过的**仍在的原文**；没能亲自确认的一律写「无法核实」并写明卡在哪。**宁可少写，不可编。**

**条数**：**54 条** = 仍开着 **29** + 已关闭 **21** + 无法核实 **4**。
> 编号说明：`开-` 的编号**有 4 处空缺**（`开-25`/`开-26`/`开-27`/`开-31`）——这四条在核查途中被 `28c7e77` 关掉了，已原地改号为 `关-18`/`关-19`/`关-20`/`关-21`。因此 **`关-` 的 21 条在表里不是物理连续的**：其中 18 条在表尾，另 4 条仍留在原来的台账行序位置上（按台账行号读更顺，便于终审拿着台账逐行走）。

**三组之外，还有两条不属任何组的提醒，见文末「控制器口径存疑处」与「我没能核实的」。**

状态只取三值：**仍开着** / **已关闭** / **无法核实**。

| # | 出处（台账行号 / 任务号） | 条目的原话（可截断） | 归属文件:行 | 对着 HEAD 核实的结论 | 状态 |
|---|---|---|---|---|---|
| 开-1 | `:70`（T1 Out-of-Scope） | 「`Namespace.java:8`、`Property.java:8/:17` 三处异常文案以散文形式重复裸词字符集「不含 : . [ ] "」，将来集合变动会静默漂移」 | `address/Namespace.java:8`、`address/Property.java:8`、`address/Entity.java:17` | `git grep -n '不含 : '` 命中**恰三处**，与台账说有「三处」相符；但**台账的落点写错**：第三处是 `Entity.java:17`，不是 `Property.java:8/:17`——`Property.java` **只有 16 行**（`wc -l` = 16），自创建以来没有过第 17 行。三处文案原文仍在 | **仍开着**（台账自标 Out-of-Scope；但落点须更正） |
| 开-2 | `:102`（T2 Park 1）/ `:121` | 「`AddressParser.java:139` 消息「第 3 段 是空 Index」多一个空格」 | `address/AddressParser.java:139` | 原文：`throw new IllegalArgumentException(where + " 是空 Index：`" + token + "`（地址：`" + whole + "`）");`，而 `where` = `"第 " + (position + 1) + " 段"` ⇒ 渲染为「第 3 段 」**多一个空格**。无任何用例断言该文案 | **仍开着**（文案级，无用例） |
| 开-3 | `:102`（T2 Park 2）/ `:198` | 「`Integer.parseInt` 接受非 ASCII 数字（`map:Map1:[٤]`、`[４]` → `Index([4])`），超出 §3.5 新明文清单」 | `address/AddressParser.java:144` | 原文：`coords.add(Integer.parseInt(part.strip()));`——**未收紧为 `[+-]?[0-9]+`**，JDK 21 的 `parseInt` 接受非 ASCII 十进制数字（我在 JDK 21.0.12 上以 jshell 复核过） | **仍开着** |
| 开-4 | `:102`（T2 Park 3） | 「`Character.isWhitespace` 为 false 的空白类字符（NBSP/U+2007）不算空白：canonical 里人眼不可区分，spec §3.2/§3.4 的"空白"未钉字符类」 | `address/AddressText.java:24` | 原文：`if (Character.isWhitespace(s.charAt(i))) {`——**仍只问 `isWhitespace`**（NBSP 为 false、`isSpaceChar` 为 true，jshell 复核） | **仍开着** |
| 开-5 | `:102`（T2 Park 4） | 「`map:Map1:region.` 的诊断文案建议"加引号"而非写成 `""`（实现者自陈，无用例覆盖）」 | `address/AddressParser.java:194-195` | 原文：`"名字组件必须加引号（含 : [ ] \" 或空白）：`" + component + "`（地址：`" + whole + "`）"`——**仍无「加引号」字样**，无用例断言该文案 | **仍开着** |
| 开-6 | `:121`（T4 Park ①） | 「`SimosTimestamp.of(long, String)` 传 null 抛**无消息 NPE**（走 `Optional.of`，与构造器 `NPE("calendarLabel")` 口径不一；brief 逐字如此）」 | `time/SimosTimestamp.java:25-27` | 原文：`public static SimosTimestamp of(long tick, String calendarLabel) { return new SimosTimestamp(tick, Optional.of(calendarLabel)); }`——**仍是 `Optional.of`**（JDK 21 下 `Optional.of(null)` 抛消息为 `null` 的 NPE，jshell 复核） | **仍开着** |
| 开-7 | `:121`（T4 Park ③） | 「`SimosTimestampTest:24` 的「原实例不变」与 `:30` 的同型重复为**行为冗余断言**（可接受，已点名，非空转缺陷）」 | `time/SimosTimestampTest.java:24`、`:32` | `:24` 仍是 `assertThat(t).isEqualTo(SimosTimestamp.of(10, "第 10 日")); // 原实例不变`；`:32` 仍是同型断言 | **仍开着**（已记档，非缺陷） |
| 开-8 | `:137`（T5 Park ②） | 「「撤销一条 Info」无表达（只能新开有效期覆盖，「从此无值」无写法）→ 留 M4/领域 spec，**待决项**」 | （M4 尚未开始） | `ls docs/superpowers/specs/` 只有 `2026-09-16-simos-master-design.md` 与 `2026-09-16-util-simos-design.md`——**M4 spec 不存在**，该待决项未被任何 spec 承接 | **仍开着**（跨里程碑） |
| 开-9 | `:137`（T5 Park ③） | 「同 key 重叠条目永久驻留（历史可查 vs 单调增长）→ M4 存储侧」 | （M4 尚未开始） | 同上：无 M4 spec，无存储层实现 | **仍开着**（跨里程碑） |
| 开-10 | `:137`（T5 Park ④） | 「`InfoEntry.value` 是裸 `Object`，往返断言依赖其自身 `equals` → M4 序列化须明文限定允许的 value 形态」 | `info/InfoEntry.java`（`Object value`） | `InfoEntry.java:11` 的 Javadoc 仍在讲「Info ≠ 领域字段」，但**没有任何 spec 限定 value 形态**；M4 spec 不存在 | **仍开着**（跨里程碑） |
| 开-11 | `:137`（T5 Park ⑤） | 「总纲 §4.6 代码块仍是草案签名（`get` 无 key、`void put`）→ **T11 关账回填**」 | `docs/superpowers/specs/2026-09-16-simos-master-design.md:262-264` | 原文仍在：`interface InfoSystem {` / `Optional<InfoEntry> get(Address subject, SimosTimestamp at);` / `void put(Address subject, InfoEntry entry);`。`git status --porcelain` 对该文件**输出为空** ⇒ **总纲未被触碰**，回填未发生（T11 现在改的是 `util-simos-design.md`，不是总纲） | **仍开着** |
| 开-12 | `:173`（T5 fix-3 Park）/ `:180` | 「`InMemoryInfoSystem` 的 map 值为 `null` 那臂无用例（原评审标"可选"，非阻塞）」 | `info/InMemoryInfoSystemTest.java:188-204` | `theExposedMapIsImmutable`（`:188-195`）与 `nullBySubjectIsRejected`（`:202`）测的都是 **map 本身为 null**；全类 `grep -n 'null'` 显示**没有任何用例构造"map 值 = null"**（需 `new HashMap<>()` 后 `put(k, null)` 才可达，`Map.of` 拒绝 null 值） | **仍开着** |
| 开-13 | `:173`（T5 fix-3 Minor 2 / Ruling "不改代码，记档"） | 「`theExposedMapIsImmutable` 后两条断言只有复合变异能红 → **不改代码，记档**（避免后续评审误当单点护栏）」 | `info/InMemoryInfoSystemTest.java:192-194` | 三条断言原文仍在（UOE ×2 + `hasSize(1)`）；**没有**单点可判别的写法，与台账记档一致 | **仍开着**（有意记档，终审复核裁定） |
| 开-14 | `:180` | 「纪律：变异恢复后必须 `touch` 强制重编译，或直接 `./mvnw -pl simos-util clean test`」 | 只存在于 `.superpowers/sdd/.../progress.md:146,150,179,249,313` 与 `task-5-rereview.md:87` | 该纪律**已随 T6+ 派单下达并在余下任务里生效**；但 `grep -rn 'cp -p' CLAUDE.md task-11-brief.md` **两处都无命中** ⇒ **唯一载体是台账/派单文本**，而 SDD 收尾要删工作区 | **仍开着**（无耐久载体；终审后随工作区删除即失传） |
| 开-15 | `:198`（T3 Park ③） | 「`QueryResult(null)` 抛 NPE 而非 IAE（plan-mandated，与兄弟守卫不一致）」 | `identity/QueryResult.java:9` | 原文：`candidates = List.copyOf(candidates);`——仍走 `List.copyOf`，JDK 21 下抛的是 **NPE**（消息点名 `coll`，jshell 复核），与 identity 包其余守卫的 IAE 口径不一致 | **仍开着** |
| 开-16 | `:198`（T3 Park ④）/ `:216` | 「`valueSemantics` 里混放拒绝断言、无负向相等用例（brief 位置，非实现者选择）」 | `identity/ResolvedSubjectTest.java:20-30` | `valueSemantics` 内只有一条正向相等（`:21`）+ 两条 IAE 拒绝（`:24-29`）；**无「值不同 ⇒ 不相等」的负向用例** | **仍开着** |
| 开-17 | `:290`（T6 minor 1）/ `:314` | 「`SimulationState.java:31-33` — `module(null)` 抛 NPE 而非返回 `Optional.empty()`…无需求被违反，但**记录在错误前提上的决策**须纠正：记 `@throws`、或加守卫、或至少更正报告」 | `state/SimulationState.java:31-33` | 原文：`public Optional<Snapshot> module(String namespace) { return Optional.ofNullable(modules.get(namespace)); }`——**未加 `namespace == null` 守卫、未记 `@throws`** | **仍开着** |
| 开-18 | `:292`（T6 minor 3） | 「`SimulationStateTest.java:29-35` — `moduleKeysMustMatchSnapshotNamespace` 只断异常类型，未像其余 8 条那样钉消息…属 brief 范围内的遗漏」 | `state/SimulationStateTest.java:29-35` | 原文仍只有 `.isInstanceOf(IllegalArgumentException.class);`，**没有 `.hasMessage(...)`** | **仍开着** |
| 开-19 | `:293`（T6 minor 4） | 「`SimulationStateTest.java:58-62` — 自证用例钉的是**检查器的列清单行为**，未证明真的 `containsExactlyInAnyOrder` 断言会响；夹具清单从未与合规的 7 名清单比对。可选加固」 | `state/SimulationStateTest.java:58-62` | 原文仍是 `assertThat(publicMethodNames(NonCompliantState.class)).contains("map");` + `assertThat(new NonCompliantState().map()).isEmpty();`——**仍只断 `contains("map")`**，未与 7 名合规清单比对 | **仍开着**（可选加固） |
| 开-20 | `:294`（T6 minor 5） | 「`SimulationStateTest.java:52-54` — 该钉法覆盖**全部**公开方法，将来合法新增…也会转红；且 `getDeclaredMethods` 看不见包级私有访问器。测试注释表明有意为之，仅记为维护触点」 | `state/SimulationStateTest.java:52-54` | 原文仍在：`.containsExactlyInAnyOrder("meta", "modules", "info", "module", "equals", "hashCode", "toString")` | **仍开着**（维护触点） |
| 开-21 | `:295`（T6 minor 6） | 「`SimulationState.java:20` — `modules` 的**值为 null** 时会走到 `Map.copyOf` 并抛出消息为 null 的 NPE，正是控制器在意的"消息判别力"缺口，无用例覆盖。brief 未要求」 | `state/SimulationState.java:20` | 原文：`modules = Map.copyOf(modules);`——**无用例构造"值为 null 的 map"**（`SimulationStateTest` 无此类夹具） | **仍开着** |
| 开-22 | `:296`（T6 minor 7） | 「格式化后中文行残留多余空格（`SimulationState.java:11`、`ChangeSet.java:8`）——gjf 不懂中文标点规则，外观问题」 | `state/SimulationState.java:11`、`state/ChangeSet.java:4` | `SimulationState.java:11` 确有接缝空格（`Facet （铁律 3/4）`）；**但 `ChangeSet.java:8` 是空行**（`wc -l` = 10），真正的接缝在 **`:4`**（`（铁律 5）， Util`），`e092780` 起即如此。**台账落点写错** | **仍开着**（行号须更正） |
| 开-23 | `:348`（T7 Concern 3） | 「`SimosTimestamp` 无负值校验，而 `of(-99)` 的向前延拓用例依赖这一点…**park 为 minor 且是"有意的耦合"**」 | `time/SimosTimestamp.java` | 类中**没有任何 tick 非负校验**；`SimosTimestampTest` 里仍有用例依赖负 tick | **仍开着**（有意 park） |
| 开-24 | `:346`（T7 Concern 2）/ `:397` / `:494` | 「`valueAt` 扫描全部事件（含未来事件），因 `at` 非递减本可提前 break…**park 为 minor**…评审者原话 'deferring to M2 is reasonable'」 | `time/SegmentedSeries.java:51-60` | 原文：`for (Event<T> event : events) { if (event.at().compareTo(t) <= 0) { … } }`——**循环内无 `break`**；对照 `baseValueAt` 确有提前退出，两条路径仍不对称 | **仍开着**（park 到 M2） |
| 关-18 | `:350-352`（跨里程碑待办） | 「**M2 的 MapSimos / SocialSimos spec 必须明写 `addition` 共享实例纪律**：任何含 `ADD` 事件的 `TemporalSeries` 一律用模块级 `static final` 算子构建」 | `docs/superpowers/plans/2026-09-16-simos-master-plan.md`（`28c7e77` 版） | **已关闭**。T11 关账提交 `28c7e77` 把该约束**写进了实现计划的 M1 关账段**：「**M2 规范必须明确的一条跨里程碑约束**（M1 关账时补记）：**任何含 `ADD` 事件的 `TemporalSeries`，一律用模块级 `static final` 的 `addition` 构建**……M2 的 `MapChangeSet` 若含时态序列，其 spec 与测试都必须照此办理」。M1 spec `:285` 的原始加粗句亦仍在。**注意**：M2 spec 本身仍不存在，故"照此办理"是**将来**的合规检查点，不是当下已满足 | **已关闭**（`28c7e77`；载体 `simos-master-plan.md`） |
| 关-19 | `:512`（系统性观察的 Ruling） | 「**Ruling：把两条规则连同"确认方法"补进 CLAUDE.md 的"纪律"节，作为 T11 Step 4 的一项**」 | `CLAUDE.md:88-104`（`28c7e77` 版） | **已关闭**。提交 `28c7e77` 在「纪律」节「护栏必须自证」下新增 5 条：`CLAUDE.md:92` 第 1 条（"把被保护的那行**删掉**…**红了还要问"为什么红"**…**没红也要问"为什么没红"**"）、`:97` 第 2 条（`requireNonNull` 只有精确匹配 `hasMessage` 才有判别力）、第 3 条（同刻/相等口径的输入须落在两种实现分叉处）、第 4 条（纯转发型 SPI 与返回处加固要逐处自证）、`:102` 第 5 条。**⚠️ 第 5 条的标签是「**（另一族）**」，正文不含「甲族」「乙族」「二十五」字样**——按那三个词 grep 会漏（我第一遍就漏了） | **已关闭**（`28c7e77`；代码位置 `CLAUDE.md:92-104`） |
| 关-20 | `:1056`（控制器错误 #15 的裁定） | 「**但这条错误本身要进最终评审的携带清单**，因为 T11 会把同类"预期输出"写进**耐久文档**…**Expected 必须来自实测，不能来自想象**」 | `CLAUDE.md:102`（`28c7e77` 版）第 5 条 | **已关闭**。`28c7e77` 写入的第 5 条含：「写给别人当依据的每个 **Expected / 事实 / 出处** 都要有**当场跑过的痕迹**——不写没实测过的期望输出；不把**工具的静默假阴性**当"不存在"；不把**推导出来的风险**当既成事实；不引用**还只活在待写文件里**的条文；不把**推导出来的"护栏边界"**当结论」 | **已关闭**（`28c7e77`；代码位置 `CLAUDE.md:102-104`） |
| 开-28 | `:604-614`（控制器候选缺口）/ `:1318` | 「**待最终评审必须携带的条目（防丢失）**：…本节候选缺口（拒绝注册的后置条件无用例）」；`:1318` 判定为「**需在最终评审前重判**」 | `resolve/ResolverRegistry.java:25`、`resolve/ResolverRegistryTest.java:46-53` | 守卫原文：`if (resolvers.putIfAbsent(namespace, resolver) != null) {`；用例原文只断 `.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("map")`——**无「抛之后注册表仍是原样」的断言**。**且该 needle 本身弱**：`:26` 抛出的消息是 `"命名空间 " + namespace + …`，`namespace` 恰为 `"map"`，故 `hasMessageContaining("map")` 由命名空间名本身满足，换成 `put` 也照样绿 **★ 控制器实测补充（2026-09-16，装置 `/tmp/rrt`，跑在 `target/classes` 副本上）**：已由静态推演升级为实测。三向变异、**每个变异体先自证「产物字节 ≠ 原件」**（第一版装置漏了这步、编的是原件，导致三向全绿 + 我两次误判——详见台账同日条目）：M1 消息不再插值 `namespace` → **红 1**（needle 咬住的是命名空间名）；M2 消息只剩 `"命名空间 " + namespace`、正文全删 → **绿**（正文判别力为零）；M3 消息完全为空 → 红 1（装置会红，M2 的绿才可信）。**⇒ 两个独立侧面**：(a) needle 由**输入数据**满足；(b) 后置条件零覆盖。**同文件 `:61` 同形**（needle `"map"` 由 `address.namespace()` 插值满足）；**`:69/:73` 的 `"不得为空白"` 经核是好**。**★★ 裁定已于同日推翻（后一条取代前一条）**：原裁定「并入终审的那一次 fix dispatch 修，不单独开轮」**作废**——理由：那等于赌「终审一定产生一次 fix dispatch」；**若终审干净，SDD 直接删工作区，已确证的修复会被静默丢掉**。**新裁定：作为独立的 src 修复轮 01，在 T11 关账后立刻做掉，且在终审之前**（需求书 `.superpowers/sdd/2026-09-16-util-simos-plan/src-fix-01-brief.md`）。**不与 T11 的重审并行**：两个实现者在同一 `git index` 上会打架。**★ 变异集亦已更正为五条**（原记的 M1/M2/M3 三条表述不准）：`M0` 删整块守卫 → 改前改后**都红**（G13 底，非判别力证据）；`M2'` 消息正文删空 → **改前绿、改后红**；`M3'` `putIfAbsent`→`put` → **改前绿、改后红**；`M4` 删 `resolve` 里的守卫 → 改前改后**都红**；`M5'` 处 B 消息改为只投 `address.namespace()` → **改前绿、改后红**。**★ 另外控制器当场纠正了自己拟的后置断言**：`namespaces()` 接不住 `M3'`（换的是**值**不是键，键集仍 `["map"]`），正确形态是钉住「解析出来的仍是 m-1」。**这两条更正本身即为「引用一个没打开的落点」的第 5、6 次当场拦截。** | **已裁定·修中**（src 修复轮 01，在终审之前落地；终审请核「修复是否真的落地且有判别力」，**不必再判「是否该修」**） |
| 开-29 | `:703`（T9 评审的设计约束） | 「**M2 起若再出现第三个纯映射注册表，须先抽取基类再落地**」 | （M2 尚未开始） | `ResolverRegistry` 与 `FacetRegistry` 是当前仅有的两个同形注册表；第三个尚不存在，**约束未被违反也未被行使** | **仍开着**（约束，M2 起生效） |
| 开-30 | `:1392-1401`（Ruling：接缝空格 **won't-fix**） | 「**不是孤例**：`git grep '， '` 在 `*/src/main/java/**/*.java` 命中 **8 个文件**（…`verify/RoundTripAssertions` ×2 行）…**裁定：不修，作为已知项交给终审**」 | 7 个文件 / 8 行（明细见下） | 原文仍在。**我独立复核：`git grep -l` = 7 个文件、`git grep -o … \| wc -l` = 8 行**——两者都由我实跑确认。命中文件：`social/package-info.java:6`、`util/info/InMemoryInfoSystem.java:15`、`util/info/InfoEntry.java:11`、`util/resolve/ResolverRegistry.java:13`、`util/state/ChangeSet.java:4`、`util/time/SegmentedSeries.java:13`、`util/verify/RoundTripAssertions.java:12` **与 `:14`**（唯一的两行文件）。**台账自己的括号里就只列了 7 个路径并注明「×2 行」——「8」是行数不是文件数** | **仍开着**（已裁定 won't-fix，交终审复核；**但计数口径须更正**） |
| 关-21 | `:1447`（「表格不是证据」） | 「**真实问题只是表述**：§十二 的 `SnapshotProtocolTest` 那一行没写明它还盖着这两个件。**已补进 T11 Step 3**」 | `docs/superpowers/specs/2026-09-16-util-simos-design.md:375-377,386,388`（`28c7e77` 版） | **已关闭**。被指名的测试确实存在：`SnapshotProtocolTest.changeSetAndCommandExposeTheirStamps()` 在 `:27`，类 javadoc `:8` 亦明写「ChangeSet/Command 都是单方法接口」。提交 `28c7e77` 已回填 spec：`:388` 写明「这两个件是**单方法接口、无行为可测**，故不另开测试类」；另新增 `:375`「**本表是下限，不是上限**」、`:376`「**表格也不是证据**」、`:377` 点名这次误判、`:386` 补 `TimeRangeTest` 行 | **已关闭**（`28c7e77`；代码位置 `util-simos-design.md:375-377,386,388`） |
| 关-22 | T11 限域重审的唯一 Minor（`task-11-rereview.md`） | 「`6eb37e6` 的**提交信息**写『现测 5462，原数不可复现』，而**该提交自身给 `master-plan` 加了净 1 行**（`git show --numstat` 实测 `5 4`，1309→1310），落地后四份实为 **5463**——数在写下的那一刻就已失真」 | `6eb37e6`（仅提交信息；`CLAUDE.md` 落地的只有「5000+ 行」量级措辞，**不构成 ship 出来的假话**） | 控制器当场复核：四份 = 686(`master-design`) + 1310(`master-plan`) + 409(`util-design`) + 3058(`util-plan`) = **5463**，评审者分毫不差（★ 我起初误以为「四份」含 `CLAUDE.md` 自身而得 4928，**先量再判才没去纠正一个正确的评审者**）。**Ruling：不改写该提交。** 理由：限域重审的结论**锚在 `6eb37e6` 这个哈希上**（它逐字节核过三个被审文件），且台账 / 本清单 / `final-review-dispatch.md` 三处都引用该哈希——**拿锚换一个括注里的数是坏交易**。代价：`git log` 的读者会算出 5463 ≠ 5462。**已把「提交信息里的数一律用量级措辞」并入开-14 的耐久载体问题交终审一并裁** | **已关闭**（Ruling：不改写哈希；纪律归属见开-14） |
| 开-32 | `:1096` / `:1424`① / `:1426` | 「`.superpowers/sdd/2026-09-16-util-simos-plan/` 是"半入库"状态…被跟踪的 **29 个**…**未**被跟踪的 18 项…**⇒ 关键后果**：终审干净后要删工作区，但那 29 个已跟踪文件会表现为 **29 个 deletion**…**已列入收尾决策清单**」 | `.superpowers/sdd/2026-09-16-util-simos-plan/` | 我复核：`task-4-report.md` 等确为**已跟踪**（`git ls-files --error-unmatch` 通过），`task-10-report.md` / `task-11-brief.md` 的状态与台账陈述一致（后者**已被跟踪**，`22aad1f` 那批 `-f` 入库）。**台账明写「不在本阶段自行处置…列入收尾决策清单」**——该决策尚未作出 | **仍开着**（交用户决策） |
| 开-33 | `:1424`② | 「`.serena/`：**未跟踪**，28K，含 `.gitignore`、`project.yml`、`memories/`…入库 / 加进 `.gitignore` / 不动，三选一」 | `.serena/`（仓库根） | `git status --porcelain` 显示 `?? .serena/`——**仍未跟踪**，未处置 | **仍开着**（交用户决策） |
| 关-1 | `:604`/`:610`（终审遗留 Minor 2）/ `:1306-1308` | 「Minor 2（`resolve` 是否原样转发调用方的 `address`/`ctx` 无用例钉住）：…替换地址或塞 `null` ctx 的实现照样全绿」 | `resolve/ResolverRegistryTest.java:109` | **已关闭**。用例 `forwardsTheCallersAddressAndContextVerbatim` **确实存在且确实钉住了两个参数**：`address` 侧由 `:135-136` `assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo(address.canonical());` 钉（被替换过的地址在此转红）；`ctx` 侧由 `:132-134` `assertThat(seenContexts).hasSize(1)` + `assertThat(seenContexts.get(0)).isSameAs(ctx)` 钉——**用同一性而非 `containsExactly`**，注释明写「containsExactly 走 equals，值相等的替身它放行」。且 `:131` 的实现侧 `registry.resolve(address, ctx)`（`ResolverRegistry.java:44`）确实原样转发。收口提交 **`3bcfe6b`**（补齐护栏）+ **`aaa9489`**（收紧为同一性） | **已关闭**（`3bcfe6b` / `aaa9489`；代码位置 `ResolverRegistryTest.java:109,132-136`） |
| 关-2 | `:383`（T8 pre-flight ④） | 「测试助手 `provider(String namespace, String facetName, FacetEntry... entries)` 的**第一个参数 `namespace` 未被使用**…**处置：不动**」 | `facet/FacetRegistryTest.java:196` | **已关闭**。当前签名为 `private static FacetProvider provider(String facetName, FacetEntry... entries)`——**死参已删**。（台账 `:1216` 之后的处置与 "不动" 相反，实际是删了；`task-11-brief.md:45` 也记「死参已删」） | **已关闭**（代码位置 `FacetRegistryTest.java:196`） |
| 关-3 | `:417-419`（③「一条判别力偏弱的断言，裁定不动」） | 「`aBrokenRoundTripReportsAllThreeStates` 只钉了 `target` 与 `actual` 两个状态名，`base` 未钉…**Ruling：接受现状，park 为 minor**」 | `verify/RoundTripAssertionsTest.java:104-109` | **已关闭（且该 mid-flight 裁定已被推翻并修正）**。现原文钉**四整行**：`:104` `"  base      = " + base`、`:105` `"  target    = " + target`、`:106` `"  actual    = " + expectedActual`、`:107` `"  changeSet = " + broken`，另 `:109` 钉提示行 `"这正是 L1 事故的形态"`（M-1）。代码内注释 `:90-91` 自陈原写法「只钉了 `actual`，`target` 是空转的」。关闭提交 **`13b96a8`** | **已关闭**（`13b96a8`；代码位置 `RoundTripAssertionsTest.java:104-109`） |
| 关-4 | `:496`（Out-of-scope 观察 ②，控制器裁定收进 fix round 2） | 「`requireStrictlyAscending` 走 `compareTo` 判同刻，但**没有任何用例构造"同 tick、不同 calendarLabel"的两段**…**Ruling：开 fix round 2，只加一个用例**」 | `time/TemporalSeriesTest.java:112-124` | **已关闭**。新增独立方法 `sameInstantDifferentLabelsAreStillRejectedAsDuplicateSegments()`，构造 `SimosTimestamp.of(10, "第 10 日")` 与 `SimosTimestamp.of(10, "第 10 日夜")` 两段并断言抛 IAE；注释明写「**独立方法**是为了让变异证据只红一条」。关闭提交 **`aaead31`** | **已关闭**（`aaead31`；代码位置 `TemporalSeriesTest.java:112-124`） |
| 关-5 | `:509-511`（T8 缺口的 Ruling） | 「T8 brief 的 **6 条用例一条都没碰它**…删掉这两条，record 只会照存 null、什么都不抛…**Ruling：补 `resolveContextRejectsNullParts`**（两条断言，各用 `.hasMessage("state")` / `.hasMessage("at")` 精确匹配）」 | `resolve/ResolverRegistryTest.java:158-167` | **已关闭**。用例 `resolveContextRejectsNullParts` 在 `:158`，两条断言分别为 `.hasMessage("state")` / `.hasMessage("at")`（**精确匹配**），辅助方法 `state()` 已抽出（`:169`）。守卫原文在 `ResolveContext.java:14-17`。收口提交 **`3bcfe6b`** | **已关闭**（`3bcfe6b`；代码位置 `ResolverRegistryTest.java:158-167`） |
| 关-6 | `:198`（T3 Park ②）/ `:211` | 「`SubjectId`/`ResolvedSubject` 四个复合守卫的 **null 分支**无用例…→ **随 T4 携带补 4 条断言**」 | `identity/SubjectIdTest.java:28-35`、`identity/ResolvedSubjectTest.java:33-49` | **已关闭**。四臂均在：`SubjectIdTest.nullPartsAreRejected`（`:28-35`，`new SubjectId(null, …)` 与 `new SubjectId(…, null)`）、`ResolvedSubjectTest.nullIdIsRejected`（`:33-37`）、`ResolvedSubjectTest.nullAddressOrTypeNameIsRejected`（`:40-50`，null address 与 null typeName 各一） | **已关闭**（代码位置如上；T4 携带项已落地） |
| 关-7 | `:198`（T3 Park ② 的方法论）/ `:180` 第 3 次撞到 | 「**本任务第 3 次撞到「只钉异常类型 → 守卫空转」**…→ 已固化为契约：**守卫断言一律默认钉字段级消息**」 | `identity/*Test.java`、`time/*Test.java` 等 | **已关闭（行为上）**。`ResolvedSubjectTest.java:22-26`、`:41-49` 均已改为 `.hasMessageContaining("canonicalAddress")` / `"typeName"`；`SubjectIdTest` 同类。**但「默认钉字段级消息」这条契约本身**只活在派单/台账文本里，**未进 CLAUDE.md**（见开-26） | **已关闭**（代码已合规；契约的耐久化见开-26） |
| 关-8 | `:290`（T6 minor 2） | 「报告 §4.2 M8 括号注「`Map.copyOf(null)` 的 NPE 消息为 null」不实——实际消息为 `Cannot invoke "java.util.Map.isEmpty()" because "map" is null`」 | `.superpowers/sdd/2026-09-16-util-simos-plan/task-6-report.md`（**已跟踪**） | **已关闭**。该报告末尾已有「## 控制器更正（2026-09-16，Task 6 评审后追加）」节，逐字记录了正确消息并写明「已记入台账 minor #2」 | **已关闭**（代码位置 `task-6-report.md` 控制器更正节；`git ls-files` 确认该报告已跟踪） |
| 关-9 | `:137`（T5 Park ⑥） | 「实现者自加的 `put` 两条 null 用例**保留**（同裁决精神）」 | `info/InMemoryInfoSystemTest.java:109,111` | **已关闭（无需动作）**。两条用例仍在：`:109` `info.put(null, entry(...))`、`:111` `info.put(HEX, null)).withMessage("entry")` | **已关闭**（代码位置 `InMemoryInfoSystemTest.java:109,111`） |
| 关-10 | `:137`（T5 Park ⑦）/ `:586` | 「方法论：「返回新实例」只有复合变异可判别，单点删 `Map.copyOf` 不转红——已并入 G13 契约」；`:586`「归为 spec 锚点而非护栏，**不开修复轮**。此判断记档」 | `facet/FacetRegistryTest.java:154-165` | **已关闭（有意记档）**。`valuesStayStructured` 的相关断言仍在，台账已把「由类型声明本身强制、写不出合法变异」的判据逐条写下，防终审当新缺口 | **已关闭**（代码位置 `FacetRegistryTest.java`；裁定已记档于 `:586`） |
| 关-11 | `:1324-1326` | 「**⇒ 规矩（记此为据）**：**最终评审的携带清单必须在派发前对着 HEAD 重推一遍，不得照抄台账里的中途快照。**」 | 本文件 | **已关闭**。本清单即该规矩的执行物：`关-1` 是台账 `:614` 快照里**已修好**的条目，`开-28` 是快照里**尚需重判**的条目，`开-30` 是快照里**仍开着**的条目——三条我都对着 HEAD 重推过，未照抄 | **已关闭**（执行物：本文件） |
| 关-12 | `:1056` 的 Expected 错误本身 | 「该名字在用例里只作静态方法限定符出现，javac 自然报 `variable RoundTripAssertions`（16 处）…**这正是本项目最忌讳的"声称了核不到的东西"**」 | `.superpowers/sdd/2026-09-16-util-simos-plan/task-10-brief.md` | **已关闭（记录层面）**。该 brief **已被 T11 修改（`M`）**，`:1216` 段与更正块在其中；`task-11-brief.md:67` 已把该错误列为乙族实例之一。**待办**只剩「进 CLAUDE.md」这一半（见开-27） | **已关闭**（记录层面）；耐久化见开-27 |
| 关-13 | `:1218` / `:1228-1234` | 「**顾虑 5（零残留三证）→ 接受**」；「⑤"残余边界"被实测证伪——乙族第 6 例…**已在 `task-10-brief.md` ⑤ 加更正块**」 | `.superpowers/sdd/2026-09-16-util-simos-plan/task-10-brief.md`（`M`） | **已关闭（记录层面）**。该 brief 确处于 `M` 状态（T11 在改），台账 `:1232-1240` 附了完整变异表（R0 6/6 绿、R1/R2/R3 各红 2） | **已关闭**（记录层面） |
| 关-14 | `:1300` | 「**⇒ 判"本机构件重建成功与否"一律看类数（`jar tf … \| grep -c '\.class$'` ≥ 118），不看时间戳**」 | `.superpowers/sdd/2026-09-16-util-simos-plan/task-11-brief.md:64` | **已关闭**。`task-11-brief.md:64` 确实载有该条，含「mtime 仍是 `2026-09-15 01:02`」的反直觉实测观测，并写成必写项 | **已关闭**（代码位置 `task-11-brief.md:64`） |
| 关-15 | `:1520-1530`（姊妹族计数更正） | 「**⇒ 乙族 9 → 10**，两族合计 **24 → 25**。`task-11-brief.md` 已同步。」 | `.superpowers/sdd/2026-09-16-util-simos-plan/task-11-brief.md:67` | **已关闭**。`task-11-brief.md:67` 原文含「**两族合计二十五处，全部出自控制器自己写的文本。**」并附甲族四条逐条枚举与乙族十处的完整清单 | **已关闭**（代码位置 `task-11-brief.md:67`） |
| 关-16 | `:1330-1350`（T10 复产评审 Important 1 → 限域重审） | 「**6/6 ADDRESSED ⇒ 可以关账** —— **T10 complete**」；范围 `13b96a8..54ef235` | `verify/RoundTripAssertionsTest.java:112-128` | **已关闭**。新用例 `aBrokenRoundTripThroughTheSnapshotEntryIsAlsoCaught` 在 `:113`，钉 `checkApplied` 独有措辞 `"往返不变式破裂"`（`:127`），注释明写「否则"因错误的原因转红"——即被版本戳守卫拦下——也会通过」。关闭提交 **`54ef235`** | **已关闭**（`54ef235`；代码位置 `RoundTripAssertionsTest.java:112-128`） |
| 关-17 | `:944-946` | 「重审新引入的 2 条 Minor 的裁定：**不另开修复轮**…两条均落在**未入库的报告文本**里」 | （未跟踪的报告草稿） | **已被裁定**（不修），**但我无法核实那两条 Minor 的原文**——见「无法核实」栏 | **已关闭**（裁定；原文核实见 `无法核实-2`） |
| 无法核实-1 | `:121`（T4 Park ②） | 「报告 M7 行号记 48、实测 47（断言链归因，结论不受影响）」 | `.superpowers/sdd/2026-09-16-util-simos-plan/task-4-report.md` | **卡在哪**：要判定「实测是 47 还是 48」必须**重跑那条变异**（删除守卫、跑 `-Dtest=` 目标用例、读失败行号）。本任务明令**不得跑 Maven**（另一代理正占用工作树），故我**只能核实报告里写的是 48**，无法核实实测值 | **无法核实（卡在只读约束：不能跑变异）** |
| 无法核实-2 | `:944-946` | 同上，那两条 Minor 的原文 | 报告草稿（未跟踪，我未定位到文件名） | **卡在哪**：台账只说「落在未入库的报告文本里」，未点名文件；工作区里未跟踪的报告有多份，我**无法确定是哪一份的哪一句**，故不臆断 | **无法核实（卡在原文未点名、我无法定位）** |
| 无法核实-3 | `:1218-1240` | ⑤"残余边界"的实测表（R0 6/6 绿、R1/R2/R3 各红 2） | `.superpowers/sdd/2026-09-16-util-simos-plan/task-10-brief.md` | **卡在哪**：同上，复现该表需在 `/tmp` 编译并跑 junit-platform-console，属「跑测试」。我只核实了**台账与 brief 里的记录一致**，未独立复现。**但该结论已被 `:1457-1470` 的 T10 限域重审 6/6 ADDRESSED 独立覆盖**，故不影响终审 | **无法核实（卡在只读约束）；已有独立替代证据** |
| 无法核实-4 | `:1090` / `:1424`③ | 「分支 `feat/m1-util-simos` **领先 `main` 33 个提交**（`56836f0` 起，含 M0），**全部本地，从未推送**」 | `refs/remotes/origin/feat/m1-util-simos` | **卡在哪（且已可判定为错）**：我**能**核实的是——`main..HEAD` = **33** ✓；但 `origin/feat/m1-util-simos` **存在**，指向 `22aad1f`，且 `origin/feat/m1-util-simos..HEAD` = **13** ⇒ 「全部本地、从未推送」**与 git 事实不符**（台账 `:136` 自己也记了那次 `git push -u origin feat/m1-util-simos` 成功）。**我无法核实的只是"推送是何时被再次遗忘的"这一过程**，事故本身已确证 | **无法核实（过程）／事实层面已判为错**；见「控制器口径存疑处」 |

## 控制器口径存疑处

以下四处是我在对着 HEAD 重推时**实跑发现与台账/派单口径不符**的：

**① 「接缝空格命中 8 个文件」——单位错了，是「7 个文件、8 行」。**
台账 `:1396` 原文写「`git grep '， '` 在 `*/src/main/java/**/*.java` 命中 **8 个文件**（social/package-info、info/InMemoryInfoSystem、info/InfoEntry、resolve/ResolverRegistry、state/ChangeSet、time/SegmentedSeries、verify/RoundTripAssertions ×2 行）」。**它的括号里只列了 7 个路径**，并在最后一个后注明「×2 行」——**台账自己已经写出了真答案，只是把行数当成了文件数**。两条独立命令复核：
- `git grep -l '， ' -- '*/src/main/java/**/*.java' | cat -n` → **7**（末行 `RoundTripAssertions.java`）
- `git grep -o '， ' -- '*/src/main/java/**/*.java' | wc -l` → **8**

这个口径**有实际后果**：台账 `:1401` 的代价估算是「改全部 8 处 ⇒ 一次纯外观的清扫」——按「处」算是 8，按「文件」算是 7；若终审决定修，**动的是 7 个文件**。（Maven 那半 `spotless:check` 我按令未跑。）

**② 「`Property.java:8/:17` 三处」——第三处在 `Entity.java:17`，`Property.java` 只有 16 行。**
台账 `:70` 与 `:102` 都把这个落点写成 `Property.java:8/:17`。`wc -l Property.java` = **16**，自创建提交 `b40b28f` 起未曾有过第 17 行。`git grep -n '不含 : '` 给出的三处真身是 `Namespace.java:8`、`Property.java:8`、**`Entity.java:17`**。数量「三处」是对的，落点错了——**照抄会让终审去一个不存在的行号上找**。

**③ 「`ChangeSet.java:8` 有接缝空格」——接缝在 `:4`，`:8` 是空行。**
台账 `:296` 与 `:1385` 一带都引 `ChangeSet.java:8`。该文件 `wc -l` = **10**，`:8` 为**空行**；接缝空格的真身在 **`:4`**（`（铁律 5）， Util`），`e092780` 起即如此，未变。

**④ 「全部本地，从未推送」——已推送，远端 `origin/feat/m1-util-simos` 在 `22aad1f`。**
台账 `:1424`③ 原文「分支 `feat/m1-util-simos` 领先 `main` 33 个提交…**全部本地，从未推送**」，`:1432`（④ 的末句）进一步写「**推送尚未发生——现在是唯一不必 force 的时点**」。实测：`git for-each-ref refs/remotes/` 里 `refs/remotes/origin/feat/m1-util-simos 22aad1f` 真实存在，`git rev-list --count origin/feat/m1-util-simos..HEAD` = **13**（即 20 个提交已在远端）。台账 `:136`（Task 6 中止那节）自己也记了「**已推送**：`git push -u origin feat/m1-util-simos` 成功」——**同一个文件里两处互相矛盾，`:1424` 那处是旧的印象**。
这直接影响 ④ 的裁定依据：那 6 个缺 `Co-Authored-By` 尾注的提交里，**只有 `ec2f10d` 是纯本地的**，`f7ac329` / `c7d32cf` / `b55be33` / `3ebb891` / `4109eb5` **都已在 origin 上**（`git merge-base --is-ancestor` 逐个判过）。所以「若用户要求统一，代价是分支早期的 SHA 全部改写」这句是对的，**但它的附带理由「不必 force」是错的——改写那 5 个必然要求 force-push。** 这一条我建议在问用户之前先更正。

**（另记，非错误但值得留意）** 本次核查期间台账从 1487 行涨到 **1530 行**，`docs/` 与 `.superpowers/` 均处 `M`——T11 在并发执行。凡标「在飞」的条目，其状态在 T11 提交后会变。

## 我没能核实的

1. **`无法核实-1`：T4 报告里 M7 的归因行号（47 还是 48）。** 卡在**只读约束**——判定它必须重跑那条变异（删守卫、`-Dtest=` 跑目标用例、读失败行号），而本任务明令不得跑 Maven（另一代理正占用工作树）。我能核实的只有「报告里写的是 48」这一半。
2. **`无法核实-2`：`:944-946` 那两条 Minor 的原文。** 卡在**台账未点名文件**——只说「落在未入库的报告文本里」，工作区里未跟踪的报告有多份，我不愿猜。若终审需要，请要求 T11 或控制器点名后再核。
3. **`无法核实-3`：`:1218-1240` 的变异表（R0/R1/R2/R3）。** 卡在**同一只读约束**（复现要在 `/tmp` 编译并跑 junit-platform-console）。我只核实了台账与 `task-10-brief.md` 的记录彼此一致，**未独立复现**。不过该结论已有替代证据：`:1457-1470` 的 T10 限域重审 6/6 ADDRESSED 是独立复审者实跑出来的。
4. **所有 Maven 侧结论我一律未跑**（`spotless:check`/`verify`/`clean test`/任何变异实验）。因此凡台账里以「实跑」为依据的门禁数字（如 `156/0`、`SpotBugs 0`、接缝空格 exit 0），**我一律未独立复核**，只核了它们的**代码侧前提**（文件是否存在、断言原文、守卫原文）。这一条对 `开-30` 尤其要紧：我证实的是**命中范围是 7 文件 8 行**，「删掉空格 Spotless 是否仍 exit 0」那半按令未跑。
5. **T11 的 Step 3 / Step 4 尚未提交**，故 `关-19`/`关-20`/`关-21` 三条的最终状态（**原文按旧编号写作 `开-26`/`开-27`/`开-31`；那四个号已在第 11 行说明中改号为 `关-18`~`关-21`，此处由控制器更正以免终审追一个不存在的编号**）**在我写下这句话时都不成立**——我只能说「此刻 **HEAD（`54ef235`）上**还没有，**索引里有**」。这三条会在 T11 提交的那一刻同时翻面，**终审前请以那次提交后的 `git show --stat` 为准重判**。
6. **`开-32` / `开-33` / `无法核实-4` 三条涉及仓库历史与远端状态，我未做任何处置**（按令只读）。其中 `无法核实-4`（远端已存在 `origin/feat/m1-util-simos`）是我唯一建议**在问用户之前先更正台账**的一条，因为「不必 force」这个判断会直接影响用户的选择。

---

# 控制器裁定栏（2026-09-16，终审派发前由控制器逐条加上）

**为什么加这一节**：本清单的 29 条「仍开着」里，**多数已在别处裁过**（won't-fix / 有意 park / 跨里程碑 / 交用户）。不加区分地整份丢给终审 = 请终审把控制器的裁定重打一遍，既费它的注意力，也把"我裁过的"与"真未决的"混成一堆。**下面按处置分组；终审只对 D 组负判断责任，A/B/C/E 组只需"确认裁定仍成立"，若发现裁定本身错了才报。**

## A 组·已裁定，终审只需确认裁定仍成立（若你认为裁定错了，那是 Finding）
- **开-7**（`SimosTimestampTest:24/:32` 行为冗余断言）——已点名，**裁定：非缺陷**（可接受）。
- **开-13**（`InMemoryInfoSystemTest:192-194` 只有复合变异能红）——**裁定：不改代码，记档**，避免后人误当单点护栏。
- **开-23**（`SimosTimestamp` 无 tick 非负校验，且有用例依赖负 tick）——**裁定：有意 park 为 minor**（"有意的耦合"）。
- **开-24**（`SegmentedSeries.valueAt` 无提前 `break`）——**裁定：park 到 M2**（原评审者原话 deferring to M2 is reasonable）。
- **开-29**（第三个纯映射注册表须先抽基类）——**约束，M2 起才生效**，当前未违反也未行使。
- **开-30**（接缝空格）——**裁定：won't-fix**。理由：`git grep '， '` 在 `*/src/main/java/**/*.java` 命中 **7 个文件 / 8 行**（`RoundTripAssertions.java` 是唯一 2 行的），属**全模块主流样式而非孤例**；只改 1 个文件会与另 6 个不一致，全改则是一次纯外观清扫、动的是 main 源码且恰在终审范围生成前，会把已审过的行重新卷进 diff。**理由里的数字已更正为 7 文件/8 行。**
- **开-15**（`QueryResult(null)` 抛 NPE 而非 IAE）——**plan-mandated**，与兄弟守卫不一致但**不改**。

## B 组·跨里程碑，M1 修不了，终审只须确认"记录没丢"（**开-14 不属本组**，它无耐久载体、单列于 D 组末节）
- **开-8 / 开-9 / 开-10**（Info 的"撤销"无表达 / 同 key 重叠条目永久驻留 / `InfoEntry.value` 是裸 `Object` 须限定形态）——三条都 route 到 **M4**，而 **M4 spec 尚不存在**。终审请确认：这三条**确实被记在能被 M4 spec 写作时读到的地方**（当前唯一载体是本工作区，**SDD 收尾会删它**——见开-14 的同病）。
- **开-11**（总纲 §4.6 仍是草案签名 `get` 无 key、`void put`）——**回填未发生**。**控制器倾向：该回填**（M1 关账 = 声称 util 已按 spec 落地，而总纲里那段是与落地形态不符的草案）。**请终审判：回填总纲是本轮该做的，还是 M1 之后的事。**

## C 组·交用户决策，终审不要判
- **开-32**（`.superpowers/sdd/2026-09-16-util-simos-plan/` 半入库：29 个已跟踪文件，终审后删工作区会表现为 **29 个 deletion**）
- **开-33**（`.serena/` 未跟踪 28K：入库 / gitignore / 不动）

## D 组·★ 真正待终审判或待修的实现侧条目
- **开-28**（`ResolverRegistryTest:46-53` 与 `:61` 两处无判别力的 needle + 处 A 缺后置条件断言）——**已由控制器实测确证，含两个独立侧面**。**★ 原裁定「并入终审的那一次 fix dispatch 修」已作废**（会赌一次未必发生的派单）；**新裁定：独立开 src 修复轮 01，在终审之前修完**。⇒ **因此本条在终审时应当已经是「已修」状态**：终审请核 `src-fix-01-report.md` 里 **M2'/M3'/M5' 三条「改前绿、改后红」的成对原始输出**是否成立，**不必重跑变异、也不必再判是否该修**。若报告缺失或三条证据不成立，**那才是 Finding**。
- **开-3**（`AddressParser:144` 的 `Integer.parseInt` 接受非 ASCII 数字 `[٤]`/`[４]`，超出 spec §3.5 明文清单）——**这是行为面缺陷，不是文案**（`parseInt` 在 JDK 21 确实接受非 ASCII 十进制）。**请判：M1 必修，还是记入 spec 已知项。** 控制器倾向：**要么收紧为 `[+-]?[0-9]+`，要么在 spec §3.5 明写"接受 JDK 的十进制数字定义"**——两条路都行，但**当前"实现比 spec 宽"这个状态本身该被消除**。
- **开-4**（`AddressText:24` 只问 `Character.isWhitespace`，NBSP/U+2007 不算空白，而 spec §3.2/§3.4 的"空白"未钉字符类）——**请判：该钉的是 spec（文档侧）还是实现（收紧）**。控制器倾向：**spec 侧钉字符类**，因为 canonical 里人眼不可分的空白正是本模块要防的东西。
- **开-6**（`SimosTimestamp.of(long,String)` 走 `Optional.of` ⇒ null 抛**无消息 NPE**，与构造器的 `NPE("calendarLabel")` 口径不一）——请判是否 M1 修（一行：改 `requireNonNull`）。控制器倾向：**修**，因为"读配置/入参只打印路径+长度"类的口径一致在本项目是硬要求。
- **开-12 / 开-21**（`InMemoryInfoSystem` 的 map **值为 null** 那臂 / `SimulationState` 的 `modules` **值为 null** 那臂——两者都无用例，而两者都会抛出**消息为 null 的 NPE**）——**同一形态的两处**。请判是否 M1 补用例。控制器倾向：**至少补一处并写明这是"消息判别力"缺口**；若判不补，请写明理由。
- **开-17**（`SimulationState.module(null)` 抛 NPE 而非返回 `Optional.empty()`；原评审记「无需求被违反，但**记录在错误前提上的决策**须纠正」）——**★ 控制器核对分组时发现它掉了队**：它是「仍开着」的条目，却不在 A–E 任何一组里，照原样交给终审**这一条永远不会被看到**。**请判**：记 `@throws`、加 `namespace == null` 守卫、还是判「维持现状但更正报告」。（控制器倾向：**记 `@throws`**——一行 Javadoc，不动行为、不新增分支，且消掉「决策记在错误前提上」这个真实问题。）
- **开-18**（`SimulationStateTest:29-35` 只断异常类型、未像其余 8 条那样钉消息）——**注意：这不是空转**（删守卫会红），是**风格契约的遗漏**。请判。
- **开-14**（`cp -p`/`touch` 强制重编译纪律无耐久载体）——**单列于本组末节**，并已追加**同族第二、三例**（「变异体也要自证」与「耐久文本里凡会漂的数一律用量级措辞」）。**请一并裁这三条各该不该进 `CLAUDE.md`。**
- **开-1 / 开-22**（两条**落点写错**：开-1 的第三处是 `Entity.java:17` 而非 `Property.java:17`——该文件只有 16 行；开-22 的接缝在 `ChangeSet.java:4` 而非 `:8`——`:8` 是空行）——**只请核"更正后的落点对不对"**，不建议在 M1 修文案。

## E 组·可选加固，终审不必花时间
- **开-2 / 开-5**（两处诊断文案：`AddressParser:139` 多一个空格、`:194-195` 未写"加引号"）——文案级、**无任何用例断言**，won't-fix(M1)。
- **开-16**（`ResolvedSubjectTest` 无"值不同 ⇒ 不相等"的负向用例）、**开-19**（自证用例未与合规 7 名清单比对）、**开-20**（`containsExactlyInAnyOrder` 的维护触点）——可选加固，请判但不阻塞。
- **开-14 → 不属 E 组**。它无耐久载体，单列于 **D 组末节**（「★★ D 组里的一条独立强调」）。

## ★★ D 组里的一条独立强调：开-14 是**唯一会在收尾时失传**的条目
`cp -p` / `touch` 强制重编译那条纪律（变异恢复后必须强制重编译，否则 javac 会拿旧 class 骗你），**当前唯一载体是台账与派单文本**（`grep -rn 'cp -p' CLAUDE.md task-11-brief.md` 两处都无命中），而 **SDD 收尾要删工作区**。
**⇒ 终审请给一条明确建议：它该进 `CLAUDE.md` 纪律节，还是随工作区一起消失。** 控制器倾向：**进 CLAUDE.md**——本条与本轮新发现的"**变异体也要自证**"（见台账同日条目：第一版变异装置编的是原件，导致三向全绿）是**同一条纪律的两半**，而后者是控制器在本里程碑内亲身踩到的。

**★ 同族第三例（2026-09-16 追加）：写死的数。** `关-22` 记的 `6eb37e6` 提交信息「现测 5462」在落地时已变成 5463，**与本里程碑连开三个提交（`70297a5`/`ac8a77b`/`6eb37e6`）去治的是同一个病**：**凡会随下一次改动而变的值，写进耐久文本的那一刻就在制造假话。** `CLAUDE.md` 纪律第 5 条（「不写没实测过的期望输出」）**可能已经覆盖了它**，也可能不够——**请终审裁**：是给 `CLAUDE.md` 补一句「耐久文本里凡会漂的数一律用量级措辞」，还是判第 5 条已足。（控制器倾向：**补一句**。理由：第 5 条讲的是「要有当场跑过的痕迹」，讲不到「跑过了、但数在写入时已过期」这一层。）

> **注**：本节的"控制器倾向"**是倾向，不是裁定**——D 组与开-14 的最终判断权在终审。A/B/C/E 组的"裁定"则是已生效的，终审推翻它们需要给出理由。
