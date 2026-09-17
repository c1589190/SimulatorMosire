# SDD ledger — plan: docs/superpowers/plans/2026-09-18-core-simos-plan.md

> **本文件是 M4（CoreSimos）的台账与恢复地图。**
> M1 台账在 `.superpowers/sdd/2026-09-16-util-simos-plan/`，M2 在 `2026-09-16-map-simos-plan/`，
> M3 在 `2026-09-17-social-unit-simos-plan/` —— 只读，不要写回去。本目录是本轮唯一可写台账。
> **台账记裁定与结论，不记取证过程**（CLAUDE.md 纪律；体量不得压过代码本身）。

**上游**：M4 spec `docs/superpowers/specs/2026-09-18-core-simos-design.md`（**已获用户批准 2026-09-18**）；
ADR-1 `2026-09-18-spi-layering-design.md`；总纲 `2026-09-16-simos-master-design.md`。
M1/M2/M3 spec 与计划均已关账。

**执行基线**：分支 `feat/adr1-core-scope`，BASE = `91fd1cc`（ADR-1 重构提交，已推送）。
执行者 = 控制器 + 实现者子代理。
**时间盒**：用户 2026-09-18 裁定「直接干到 M4 完成，或者早上 8:30」；08:20 有一发一次性 cron 收口。

**跨里程碑承接约束**（M1/M2/M3 关账时补记，M4 同样适用）：
1. 含 `ADD` 事件的 `TemporalSeries` 一律用模块级 `static final` 的 `addition`（各写各的 lambda ⇒ 假红）。
2. `Map.copyOf` / `Set.copyOf` 迭代序非内容纯函数 ⇒ 保序一律 `LinkedHashMap` + `unmodifiableMap`。
   ★ 且**夹具键数要当场量**：3 键实测 7%~40% 恰好落回插入序（假绿），4~6 键 0/30。
3. 变异自证五形态（CLAUDE.md 纪律节）：字节不同先自证 / 白名单推成目标类名 / 每轮干净世界 /
   `COMPILATION ERROR` 计数为 0 / 红点必须在被保护那行。
4. **恢复基线一律写成"回到开跑前的工作树状态"**，**绝不** `git checkout -- <file>`
   （未提交改动会被静默销毁 ⇒ 假绿；2026-09-18 ADR-1 变异轮已踩过一次）。

---

## 任务地图（17 任务）

| # | 任务 | 状态 |
|---|---|---|
| 1 | 契约收敛（`ChangeSet` 标记接口 + 删 `assertSnapshotRoundTrip`） | ✅ Batch A `d1ad67b` |
| 2 | `util.spi` 五类型 + `package-info` | ✅ Batch A `d1ad67b` |
| 3 | JSON 地基（★ 最高风险） | ✅ Batch B1 `m4/b1` → 合并 `acfcb14`（4 提交，532 用例全绿，7 轮变异存活 0） |
| 4 | `WorldChangeSet` + `Envelope`（C26）+ R1 | ✅ Batch B2 `m4/b2` → 合并 `068769d` |
| 5 | `SqliteStore` | ✅ Batch B3 `m4/b3` → 合并 `2ec17ff` |
| 6 | `Timeline` | 🔄 Batch C1 `m4/b6`（基线 `acfcb14`） |
| 7 | `CheckpointStore` | ⏳ Batch C1 |
| 8 | `Replay` | ⏳ Batch C1 |
| 9 | `CommandRegistry` + `CommandBus` | ⏳ Batch C2 ★ **依赖 Task 6**（见裁定 24） |
| 10 | 乐观并发两处检查（C17）+ R7 | ⏳ Batch C2 |
| 11 | 可观测性 + `correlationId` 全链 + R6 | ⏳ Batch C2 |
| 12 | 两阶段推进六步（C25）+ R9/R10/R14 | ⏳ Batch E |
| 13 | `CoreSimos` 装配门面 | ⏳ Batch E |
| 14 | 判据一——分岔端到端 | ⏳ Batch E |
| 15 | 判据三——真实并发加固 | ⏳ Batch E |
| 16 | unit 侧最小真实链路（U15 乙）+ R16 | 🔄 只派了 **SPI 两类** `m4/b16`（基线 `acfcb14`）；端到端要 Task 13（裁定 20） |
| 17 | M4 关账 | ⏳ Batch F |

**派单批次**：A(1,2) → **B1(3) ‖ B2(4) ‖ B3(5)** → **C1(6,7,8) ‖ C2(9,10,11)** → E(12,13,14,15,16) → F(17)。

★★ **本行改过两次，两次都记在案**：
- 原写 `A→B(3,4)→C1‖C2`。2026-09-18 05:5x 抠 `Files:` 后改：**Task 3 与 Task 4 文件集不相交**
  （3 = `util/json/` + 三个 `*/codec/`；4 = `core/state/` + `core/store/Envelope.java`），
  且 **Task 4 不依赖 Task 3**（`Envelope` 按 C26 把载荷当**不透明字符串**，全程不碰 Jackson 多态）。
- 2026-09-18 06:1x 再并进 **Task 5（`SqliteStore`）**：抠每个任务的 `Consumes:` 行**实测它零依赖**
  ⇒ 3/4/5 三者互相独立，合成**三路并行**。（★ 这条是**实测**不是推导：`Consumes:` 行是计划里逐任务写死的。）

**动因是时间盒**：串行总时长实测约 235 分钟，超截止；两处并行约 155 分钟只是压线。
★ **06:13 实测，已比原估算晚 28 分钟起飞**（原文按 05:45 起算）⇒ **本轮到点未完成的批次按
"带裁定的遗留条目"往下走，不加轮**（纪律节的"评审不超过 3 轮"）。

**边界**（三份，互斥）：

| 批次 | 任务 | 独占路径 |
|---|---|---|
| B1 | 3 | `simos-util/src/**/util/json/**`、`simos-{map,social,unit}/src/**/codec/**`、`simos-util/src/**/util/state/FieldDelta.java`、`simos-core/src/test/resources/**` |
| B2 | 4 | `simos-core/src/**/core/state/**`、`simos-core/src/**/core/store/Envelope.java` |
| B3 | 5 | `simos-core/src/**/core/store/SqliteStore.java` |

★★ **三份都不得改任何 `pom.xml`**：`simos-util`（jdk8）与 `simos-core`（sqlite-jdbc main / log4j test）
的依赖**已由控制器在切分之前一次性落盘并提交（`ce98212`）**——这正是为了消灭这个三方冲突点。
（裁定 10 原把 `simos-core/pom.xml` 划给 C1，**该条已随之消解**：也没人需要改它了。）
**三份都禁 `mvn install`**（裁定 8）。

★★★ **`isolation:"worktree"` 实测不可用，本轮 worktree 由控制器手工建**（这条要记牢，别再踩）：
本机实测该参数把工作树建在 **`f5c8485`（M1 期提交）**，而 `ce98212` **不是它的祖先**
（`git diff --stat ce98212 f5c8485` = **511 files changed / 80028 deletions**）——那棵树里
**没有 `util/spi/*.java`、没有 `FieldDelta.java`、`simos-util/pom.xml` 里没有 jdk8**，
即**陈旧三个里程碑**。已派出的三个 agent 因此被控制器 `TaskStop` 停掉（未落任何盘）。

**现行做法**：控制器
`git worktree add -b m4/b<N> .claude/worktrees/b<N> <当轮 HEAD>`，
派单时把**绝对路径**给执行者，并要求**第 0 步先自证基线**：
`git -C <wt> log --oneline -1` 必须**等于派单里写死的 commit**，**不匹配就停下回报**。
★ 副产物：`.claude/` **不在 `.gitignore` 里**（实测 `git check-ignore -v .claude/worktrees` 返回**未忽略**），
故 worktree 目录会让主树 `git status` 出现 `?? .claude/`——**它不是产物，别提交、别 `-A`**。

---

## 控制器裁定（本轮新增，2026-09-18）

| # | 裁定 | 由来 |
|---|---|---|
| 1 | **C28**：`ModuleCodec.apply` 加第三参数 `StateMeta newMeta` | spec 原冻结的 2 参签名**不可满足**：模块 `apply` 返回的状态类型**不带 ref/timestamp**，而 spec §5.4 第 4 项要求返回的快照携带**新** ref/tick。在三个 codec 写出来**之前**发现 |
| 2 | `simos-util` 加 `jackson-datatype-jdk8` | `Optional` 在快照树里且处于**嵌套泛型位置**（`Unit.java:23/24/29`）。手写替代方案 = 又一份"手工对着状态类型维护的平行结构" = L1 事故形态 |
| 3 | **计划原文"依赖白名单"是错的**（已当场更正） | 实测 `simos-util/pom.xml` **只有 `bannedDependencies` 黑名单**（excludes 仅 simos 模块 + agentlib，无 `includes`）⇒ 加 jdk8 **不会**让 enforcer 失败。回填目标是 **`CLAUDE.md` 模块表 + spec §二/§〇.3**，**不是 pom** |
| 4 | **`FieldDelta` 必须加 Jackson 类型信息**（④ 的答案） | 探针 run0 实测：`Unchanged`/`Upsert` 均 `SER_OK` 但 `DESER_FAIL`（`missing type id property '@class'`）。形态：sealed interface，4 个**带泛型**的 record 变体，`Patch` **嵌套**另两个 ⇒ **加在哪（注解 vs mixin）是 Task 3 必须显式选的决策点** |
| 5 | **`GameMap` 原样往返成功**（本阶段最大风险解除） | 探针 run0 实测 `SER_OK|len=3503` + `DESER_OK|equals=true`；六键类型 `KeyDeserializers` 注册有效 |
| 6 | **`~/.m2` 现在有 simos 自己的构件 ⇒ 一律 `-pl <模块> -am`** | 探针为跑 scratch 测试 `install` 了 util/map/social/unit（此前只有 agentlib + parent）。**不带 `-am` 会解析到 05:15 的快照**，`simos-core` 会拿到缺 `util.spi` 的旧 jar ⇒ 报"类型找不到"，**看起来像自己写错了** |
| 7 | **当场实测：`~/.m2` 里那份 `ChangeSet.class` 未被污染** | `javap -p` 得 `public interface ChangeSet {}` = Task 1 之后的**正确**形态。本次**未**污染——此条是**预防**不是事后追认 |
| 8 | **变异轮期间工作树必须独占；并行 agent 的共享写点只有 `~/.m2`，故并行必须禁 `install`** | 第 6 条的 `install` **恰好**落在安全时刻，是运气：它 05:12 落盘时 Batch A 正在往 `ChangeSet.java` 推 mutant。错开几分钟就会把 **mutant 冻进 `~/.m2`**，且**扩散到之后每个不带 `-am` 的构建、没有任何测试会红** |
| 9 | C1/C2 并行段用 **`git worktree`** 隔离 | 5~8 与 9~11 **同属 `simos-core`** ⇒ 同模块同 `target/`，同树并行必然互踩构建产物。★ 原写"C1/C2 **文件集不相交**"是**未实测的推导**，抠完 `Files:` 后发现**不成立**（Task 11 也往 `core/store/` 放文件）⇒ 见裁定 10 的边界契约 |
### ★ 10. C1 ‖ C2 的**边界契约**（控制器强制，不许执行者自行放宽）

抠完两批每个任务的 `Files:` 后发现的**真实交叠**（实测，非推导）：

- **Task 11 也往 `core/store/` 里放文件**（`EventRow.java` / `EventStore.java`）——那是 C1 的地盘；
- **Task 11 的计划原文写着"或并入 `SqliteStore`，执行者二选一"** ⇒ 若 C2 选"并入"，就是**同文件冲突**；
- **两批都可能想改 `simos-core/pom.xml`**（Task 5 要 `sqlite-jdbc`，实测该 pom **当前没有** sqlite 依赖；Task 3 Step 6 加 log4j 两个）。

**边界裁定**：

| 资源 | 归谁 | 对另一方的硬约束 |
|---|---|---|
| `core/store/SqliteStore.java` | **C1 独占** | **C2 绝不许改**。"并入 `SqliteStore`"这个选项**被控制器取消**——Task 11 一律**独立文件** `EventStore.java`（取代说明回填进计划） |
| `core/timeline/**`、`core/store/CheckpointStore.java`、`core/store/Replay.java` | **C1 独占** | C2 不许碰 |
| `core/command/**`、`core/observe/**`、`core/store/EventRow.java`、`core/store/EventStore.java` | **C2 独占** | C1 不许碰 |
| `simos-core/pom.xml` | **C1**（加 `sqlite-jdbc`，main scope） | **C2 不许改 pom**；确需新依赖就**停下来回报**，由控制器裁决 |
| `core/store/Envelope.java`、`core/state/**` | **Batch B**（先于切分完成） | 双方都**复用不改** |

**判据**：两批产物**同处 `core/store/` 但文件不相交** ⇒ `git merge` 应当是**无冲突**的。
真出现冲突 = **有人越了界**——**冲突本身就是越界信号**，不要去"解决"它，先回报。
★ **并行两批一律禁 `mvn install`**（裁定 8）：`~/.m2` 是它们唯一的共享写点。


### ★ 11~14. 探针实测（2026-09-18 05:4x 回报，84 次调用）带来的裁定

探针全量结论已回填计划末节 **「探针结论」**；此处只记**由它产生的裁定**。

| # | 裁定 | 由来（探针实测） |
|---|---|---|
| 11 | **不启用 `ORDER_MAP_ENTRIES_BY_KEYS`**；也不采用"关 `FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` 绕过" | 开启即抛 `Cannot order Map entries by key of incomparable type RegionId`（`RegionId`/`CityId`/`PathwayId`/`UnitId` 非 `Comparable`）。**且就算不抛也打不中靶**：真正的漂移源是 `Region.hexes` 的 **`Set.copyOf` 数组序**，而该 feature 只管 Map 键序 ⇒ **既抛异常又无效** |
| 12 | **M4 任何测试不许拿"快照 JSON 的字节"当断言依据**；往返一律断 `equals` | 探针三次独立 JVM 得 **2 种字节**（run0==run1 ≠ run2）。字节断言会有约 1/3 概率假红/假绿 |
| 13 | **Task 7/8 的"内容指纹"不得直接哈希快照 JSON** | 同 12。要么先规范化（排序），要么改用 `equals` 语义比较 |
| 14 | ~~`Region.hexes` 的 `Set.copyOf` **记为上游发现**~~ ★★ **当场实测后撤回"缺陷"定性（2026-09-18 07:4x）** | 原写"与 CLAUDE.md 形态 1 的既有教训直接冲突、**疑似 M2 遗留缺陷**"——**这个定性是错的，撤回**。控制器当场读码实测：<br>① `Region.java:14` 的 javadoc **明写这是有意的**（"`hexes` 用 `Set.copyOf`（不保序）**是有意的**：它是**集合语义**，迭代序不该被依赖"）；<br>② **补偿机制真实存在且是承重的**：`RegionBoundary.of(Set<HexCoord>)` 的 javadoc 写着"**纯函数**：同集合必得同结果，与迭代序无关"，且 `RegionBoundary` 的紧凑构造器把每条环经 `canonicalRing` 化成规范形（旋转/反转取字典序小者）、再把环表按首顶点排序（`RegionBoundary.java:41-53`）。⇒ **内容相同的两个 Region 必然相等，与散列序无关**。<br>③ 这正是形态 1 那条教训要求的做法，**只是手段是"规范化"而不是"保序"**——所以它不是反例。<br>**真正剩下的后果窄得多**：含 `hexes` 的 **JSON 字节**跨 JVM 不稳定（Set 迭代序 = 散列槽位序）⇒ **已被裁定 12/13 覆盖**（不许拿快照 JSON 的字节当断言依据；内容指纹不得直接哈希快照 JSON）。<br>⇒ **关账时不呈报为缺陷**。若 M5 需要字节级决定论（MCP 传输/签名），须先规范化再哈希——那是一条**已知代价**，不是 bug。<br>★ **本条的教训与它自己同源**：原定性正是"把**推导出来的风险**当既成事实"（CLAUDE.md 形态 5）——写台账时**没有读码**，只凭"`Set.copyOf` ⇒ 与形态 1 冲突"推了出来 |
| 15 | Task 17 增加一条动作：**用绿色源码重跑 `install` 覆盖 `~/.m2`** | 探针自陈"无法证明当时 main 不含 mutant"（install 时撞上 Batch A 的变异轮）。控制器 `javap` 只抽查了 `ChangeSet` 一例，**不足以覆盖全部类** |
| 16 | Task 3 的 `SimosObjectMapper` 签名改为 **`create(Module... extraModules)`** | 六个键类型**全在领域模块**（5 个在 `simos-map`、1 个在 `simos-unit`）⇒ `simos-util` **够不着**，键反序列化器只能各 codec 自己注册（铁律 3）。原"三个 codec 共用一份配置"不能理解成"键反序列化也统一注册"——**那行代码写不出来** |
| 17 | **裁定 15 已提前执行**：06:16 在 `b43d115`（绿色源）上跑 `./mvnw -DskipTests install` 覆盖 `~/.m2` | 一举三得：① 覆掉探针那份**无法自证未被污染**的快照；② 预下 `sqlite-jdbc` / `log4j-*` / `jdk8`，**消灭三个并行 agent 同时下载的竞争**；③ 建立"开跑前工作树"的绿色基线。实测 `BUILD SUCCESS` 2:41，六模块全 SUCCESS，`BugInstance size is 0`。⇒ Task 17 那条动作**已完成**，关账时只需复核 |
| 18 | 三个并行 agent 的**测试范围不得重叠到同一模块的同一批测试类** | 三份边界各自独占文件，但都在 `simos-core` / `simos-util` 上跑 `verify` ⇒ 报告里的用例**条数会互相包含**（B2 的 verify 会把 B1 的 util 测试也算进去）。**判据不是条数**，是"**本任务新增的类全都跑了且全绿**"。★ 冲突信号仍是 `git merge` 冲突（裁定 10） |
| 19 | **Task 16 派单前必须钉死的两条**（2026-09-18 06:2x 控制器当场 `find`/`sed` 实测，非推导） | **① 路径更正**：`UnitOperations` 真实路径是 `simos-unit/src/**main**/java/io/mosire/simos/unit/**ops**/UnitOperations.java`——**有 `/ops/` 一层**，计划原文漏写（类名/行号 66/签名逐字都对，**只有路径错**）。`UnitMoves` 那条 `.../unit/move/UnitMoves.java:31` 是对的。**② `UnitMoves.evaluate` 有两个会抛的前置条件**，Task 16 必须处理：`evaluate(Unit, SimosTimestamp, GameMap, MovementCost)` 实测（`UnitMoves.java:31-36`）在 ① 单位**没有在途路线**时抛 `IllegalArgumentException`，② `at` **早于** `departedAt` 时也抛。⇒ 参与者**只能对持有在途 `Movement` 的单位调它**（与计划 Step 1 一致），且 **`range.to` 早于某单位 `departedAt`** 这个边界**必须显式决定**（跳过 / 还是照调让它抛）——**不要靠"不会发生"** |
| 20 | **Task 16 的主产物可在 B1 落地后立即并行**（不必等 Task 6/13） | `UnitTimeParticipant` + `RenameUnitHandler` 的 `Consumes:` 实测只有 `TimeParticipant`/`CommandHandler`（Task 2，**已完成**）+ `UnitCodec`（Task 3）+ M3 已有的纯函数 ⇒ **不碰 Timeline/SqliteStore**。⇒ 它们**不需要**排在 C1 之后。★ 但 `RealmEffectEndToEndTest`（判据四端到端）要 `CoreSimos`（Task 13）⇒ **端到端那条测试必须晚于 Task 13**，届时单独补 |
| 21 | **Task 6 的 `Consumes:` 行漏了 Task 3 与 Task 4 ⇒ 暂不派发**（2026-09-18 07:1x 当场读 B2 产物实测） | Task 6 Step 4 要求 `fork` 写 **「变更集 = `WorldChangeSet.empty()`」**，但它的 `Consumes:` 只写了 `SqliteStore`。**实测**（读 `.claude/worktrees/b2/.../core/state/WorldChangeSet.java`）：`WorldChangeSet` 是 `record WorldChangeSet(Map<String, ChangeSet> modules) implements ChangeSet`，而 `ChangeSet` 是**标记接口**（连 namespace 访问器都没有）⇒ 要落成 `RevisionRow.changesetJson` 那个 **`String`**，**必须过 Jackson + 类型信息** = Task 3 的 mapper + Task 4 的裁定 4。**⇒ Task 6 的真实依赖 = Task 5 + Task 3 + Task 4**，不是"只有 Task 5"。**处置：不派**，等 B1/B2 合进来再开 `b6`；已建好的 `b6` 工作树**已移除**（基线不成立，留着就是陷阱表里刚记的那条陈旧基线）。两个理由同时成立：① 正确性；② 2 核约束下正好把并发维持在 2，不再招 503 |
| 22 | **`Consumes:` 行不可信，必须对着步骤里的代码复核**（同族问题第二次） | 第一次是"Task 3 与 Task 4 文件集不相交"（未实测的推导，后来被抠 `Files:` 推翻），这次是 `Consumes:` 漏依赖。**根因**：这些行是**控制器写计划时手工推的**，而步骤正文里的一句 `XxxClass.empty()` 就足以引入一整条依赖。⇒ **派单前必须把该任务每一步正文里出现的类型名，逐个与 `Consumes:` 对一遍**；对不上就以正文为准并回填 |
| 23 | **R15 装置在 worktree 下恒绿——已当场修掉（`50921bc`）** | B2 报「上游发现，请控制器裁决」⇒ 控制器做**四格实测**（真实类，非重实现；`-Duser.dir` 切树根，**不 `cd`**）：`isScannableJava` 遍历的是**绝对路径的每一个名字元素**，而 worktree 的绝对路径含 `.claude` ⇒ 每个文件都被 `startsWith(".")` 滤掉 ⇒ `javaFilesUnder` 返回**空**。<br>**修前**：主树 `hits=44` / worktree b1 `hits=0` ← **缺陷当场复现**；**修后**：主树 44（**无回归**）/ worktree b1 **45** = 44 + B1 新落的 `util/json` 一个文件（即修后**真的看见了 B1 的新代码**）。<br>★ **同型缺陷 core 侧的 R1 装置已先修**（B2 自己那件），本文件是后补的同款。★ **连带结论：本轮所有在 worktree 里跑出来的 R15 结论都是空真，一律不作数**——权威结论只取**主树**的 verify（收口动作第 3 条）；这不影响 B1/B2/B3 各自交付物的成立（R15 是 Task 1 的判据，不是它们的），但它们报告里若出现"R15 绿"这一行按空真处理 |
| 24 | ★★ **`C1 ‖ C2` 从来就不成立——Task 9 依赖 Task 6** | 派发前按裁定 22 复核 Task 9 的步骤正文时发现：Task 9 的 `Consumes:` 行**自己写着** `Timeline`（6），Step 1 的 `ForkBranch → Core 的分岔（Task 6）` 也指向它。**⇒ 9 必须排在 6 之后**，计划里"C1(6,7,8) ‖ C2(9,10,11)"这个并行划分是**错的**（手工推的批次划分，与 `Consumes:` 行自身矛盾）。<br>★ **连带**：`CommandBus.submit(AdvanceTime)` 指向 **Task 12** 的推进管线——而 Task 12 排在 C2 **之后** ⇒ **9 与 12 之间还有一个方向未定的接缝**（9 先于 12，但 9 的分派表要指向 12 的产物）。派发 Task 9 时**必须先裁定这个接缝**（建议：9 定义注入点/函数式接口，12 填实现），否则执行者要么卡住、要么自己发明设计。**本轮未派 9，留给下一轮** |
| 25 | **陈旧分支 `m4/b6` 残留 ⇒ `git worktree add -b m4/b6` 失败** | 裁定 21 当时**移除了 b6 工作树但留下了同名分支**（停在 `2ec17ff`）。它**缺 Task 3 的 `util/json` 与 Task 4 的 `WorldChangeSet`**——正是裁定 21 判定"不能派"的那棵树。★ **危险动作是"顺手复用已有分支"**（`git worktree add <path> m4/b6`）：那样会**建出一棵看起来完好、实则陈旧三个任务**的树，与本轮已两度踩过的陈旧基线陷阱**同型**。已 `git branch -D` 后按当前 HEAD 重建。⇒ **移除工作树时必须一并处理同名分支**；`add -b` 报"branch already exists"本身就是一条**值得听的告警** |
| 26 | **`cd` 进工作树的陷阱——控制器自己复发了**（记账而不是记功） | 07:2x 为查 B1 的 diff，命令以 `cd /home/dev/SimulatorMosire/.claude/worktrees/b1` 开头 ⇒ 环境随即报 **"Primary working directory: …/worktrees/b1"**，会话工作目录**再次被带偏**。**上一轮已在陷阱表里写过这条，仍然复发**。★ 这次的危害比上次更具体：**下一步就是 `git merge m4/b1`**——若没发现，那次合并会**在 b1 的工作树里执行**，往 `m4/b1` 上打提交，而输出**完全正常**。<br>⇒ **规则要写成可执行的形态，不是"要小心"**：**控制器检查任何工作树内的文件，一律用 `git -C <绝对路径>` 或写全绝对路径，命令里出现 `cd .claude/worktrees` 即为违规**；且**每轮 git 写操作之前**无条件跑一次 `pwd && git rev-parse --abbrev-ref HEAD && git rev-parse --git-dir`（`.git` = 主树，文件路径 = worktree） |
| 27 | **`~/.m2` 复核：未被污染，但确实陈旧 ⇒ 裁定 6 的 `-am` 由"预防"升为"硬要求"** | 07:4x 当场 `ls -l` + `jar tf` 实测：`simos-{util,map,core}` 三个 jar 的时间戳全在 **06:14~06:16**（= 裁定 17 那次绿色 install），此后**无人 install** ⇒ **裁定 8 担心的"变异轮里把 mutant 冻进 `~/.m2`、且扩散到之后每个不带 `-am` 的构建、没有任何测试会红"没有发生**——三个 agent 都守住了"不许 `mvn install`"。<br>★ **但它确实陈旧**：`jar tf simos-util-*.jar \| grep -c "util/json/"` = **0**（对照 `util/spi/` = 9 个条目）⇒ Task 3 的 `SimosObjectMapper` **从没被 install 过**。⇒ **裁定 6 的 `-am` 不是预防性建议**：不带 `-am` 的 `-pl simos-core` 会解析到缺 `util/json` 的 jar，报"类型找不到"，**看起来像执行者自己写错了**。收口那次 verify **必须带 `-am`**。<br>★ 顺带实测（**同族陷阱的第四个成员**）：本机 **`unzip` 不存在、`jar` 也不在默认 PATH 上**（`jar` 来自 JDK，要 `export JAVA_HOME`）⇒ 两次 `grep -c` 都打印 **0**，而 **"工具根本没跑"与"jar 里确实没有"输出一模一样**。判别法：先 `command -v <工具>`，**别把工具的失败读成被测对象的属性** |

★ **探针明确"未核实"的 6 条**已逐条抄进计划末节，**不许当成已结论**——尤其
**`@JsonTypeInfo` 用 `Id.CLASS` 还是 `Id.NAME` 未测**，Task 3 执行者必须自测并记录。

### ★ 工具陷阱（本轮实测，已回填 `CLAUDE.md`）

| 陷阱 | 症状 | 判据 |
|---|---|---|
| **★ 护栏装置自己会静默失效** | 自建扫描器按**绝对路径**判隐藏段 ⇒ 在 worktree 里扫 **0 命中** ⇒ **R15 恒绿**。跑起来**没有任何症状**：用例全绿、构建成功、"全是绿的" | 判据同 CLAUDE.md 形态 1（故意违规看它红不红），★ 但要**在该装置真正会被使用的每一种目录形态下各测一次**（主树 / worktree）——只在主树测过等于没测。**这是 ugrep / `git grep` 那族"把没搜到伪装成不存在"的第三个成员，只不过这次的"工具"是我们自己写的** |
| **`git grep` 不看 untracked** | 对**刚写的**文件 `git grep <串>` → **无输出、rc=1**；文件里其实有 77 处 | 与 ugrep 同族：把"没搜到"伪装成"不存在"，且**恰在开发期发作**（那时新文件全是 untracked）。用 `git grep --untracked` |
| **★ Agent 工具的 `isolation:"worktree"` 从陈旧提交分叉** | 工作树**建起来了、`mvnw`/`pom.xml` 都在**，一切看起来正常——但**基线是 `f5c8485`（M1 期）**，不是当前 HEAD。产物会**不可合并**，且**当场看不出来** | **别信"建起来了"就等于"建对了"**：建完先 `git -C <wt> log --oneline -1` 与派单里写死的 commit 比对，并对一个新近才存在的文件做存在性检查（如 `util/spi/`）。**本轮改用控制器手工 `git worktree add -b <br> <path> <commit>`** |
| **★ Bash 的 `cd` 会**持久**改掉整个会话的工作目录** | 为查一个文件而 `cd` 进某个工作树 ⇒ **之后所有命令都在那棵树里跑**。若接着执行 `git add`/`git commit`/`git merge`，**提交会落到那棵树的分支上**，而不是主线——**而命令的输出来看一切正常**（`git log` 显示提交成功） | **控制器绝不 `cd` 进工作树做检查**：一律 `git -C <worktree> <子命令>`，或 `grep <路径>` 写全路径。**发现 cwd 被带偏后第一件事是 `pwd && git rev-parse --abbrev-ref HEAD && git log --oneline -1` 三连自证**（2026-09-18 06:53 本会话**差点**在 `m4/b1` 上执行主线操作，靠环境提示才发现） |
| **Maven 不读 `HTTP_PROXY`/`HTTPS_PROXY`** | `dependency:get` **静默超时**，像"外网不通" | 只认 `settings.xml` 的 `<proxies>`；`-Dhttps.proxyHost` 实测**也不生效**。已建 `~/.m2/settings.xml` |
| **`.superpowers/` 不是 gitignore 的** | 照 CLAUDE.md 旧条文会去用 `git add -f` | 实测 `git check-ignore` 返回"未忽略"。**普通 `git add` 即可** |

---

## 执行日志

- **2026-09-18 07:4x**：`task-4-report.md` §6.1 自陈**未能核实**"util 侧 RepoSourceScan 在 worktree 下是否真的空真——只读码确认了同型缺陷，没跑到 util 去复现"。**该缺口已由裁定 23 的四格实测补上**：修前 worktree `hits=0`、主树 `hits=44`——即"扫描为空而测试仍绿"这一步**实测复现了**，报告里那句"全绿与空真不矛盾但也不构成复现证明"现在有了证明。★ 顺带记一条**做法**：B2 把"读码确认"与"实测复现"**分开写**（§5.3 说缺陷、§6.1 说没复现），这正是 CLAUDE.md 形态 5 要的形态——**它没有把读码结论冒充成实测结论**，所以缺口一眼可见、可补。

（待各批次回填：`task-N-brief.md` = 派单扫描结论，`task-N-report.md` = 实测处置，本文件 = 关账裁定）

---

## ★★ 环境硬约束：**本机只有 2 个核**（2026-09-18 06:50 实测，**推翻三路并行的算术**）

`nproc` = **2**。06:50 实测 load average **3.02**，三路并行时把一个 agent 打成了
**服务端 503**（`system cpu overloaded (current: 99.6%, threshold: 90%)`，
错误文本点名 `127.0.0.1:3000` 的推理网关）——即 **Maven 的 JVM 把推理网关一起饿死了**。
**06:54 第二个 503**（B1/Task 3，`99.8%`）⇒ **三路并行被实测否证两次，不再是"风险"而是"已知故障"**。

★ **根因**：`127.0.0.1:3000` 上的推理网关是**本机在服务**的——**每一次 agent 回合都要它生成 token，
而它和 Maven 抢同样的 2 个核**。⇒ 这不是"网络抖动"，是**资源竞争**，重试不解决，**只能降并发**。

⇒ **结论：并行的上限不是磁盘、不是依赖、是 CPU。**
- **同时最多 2 个 agent**（不是 3）。第 3 个必须在有空位后再派。
- 派单时**要求执行者用定点命令**（`-pl <模块> -am -Dtest=<类名>`），**不要**全 reactor `clean verify`。
- 503 是**可恢复的**：agent 的**工作树与未提交改动都还在盘上**，`SendMessage` 可在原上下文里续跑
  ⇒ **被打断不等于白干**，但**别把"再派一个"当默认反应**（新 agent 要重新 ramp-up，且又占一个核）。

★ **这条与"工具陷阱"同族但不同源**：陷阱表里那些是**工具骗你**；这条是**环境骗你**——
`isolation:"worktree"` 让三路并行**看起来**可行（磁盘上确实是三棵独立的树），
**但真正的稀缺资源是 CPU，它不体现在任何 git/文件系统状态里**。
⇒ 引出的纪律：**"文件集不相交"只是并行可行性的必要条件，不是充分条件**；
声明可并行之前还要量**机器上有几个核、每个任务平均吃多少**。

**时间盒**：用户裁定「直接干到 M4 完成，**或者早上 8:30**」。08:20 有一发一次性 cron 收口。

### 一、临界路径的实测账（为什么大概率跑不完 17 个任务）
抠完 `Consumes:` 行后，**从 B 批次起的最长链是 7 层**：
`B3(5) → 6 → 9 → {10,11} → 12 → 13 → {14,15}`。
按 Batch A 实测的 ~25 分钟/任务、每层都要一次派发+回填，**2 小时装不下 7 层**
⇒ **预案按"到点未完成"写，不按"侥幸跑完"写。**

### 二、收口动作（按序，不许跳）
1. **停止派发新波次。** 已在跑的 agent：**要么等它自己完结，要么 `TaskStop`**——**不许**把在跑的
   分支半途合并。
2. **只合绿色的。** 逐个 `git merge --no-ff m4/b<N>`：
   - 合并前**先看该分支的 `task-N-report.md`** 与它的 `./mvnw verify` 结论行；
   - **红的分支一律不合并**，改为在台账记「带裁定的遗留条目」+ 分支名 + 停在哪一步；
   - ★ **`git merge` 出现冲突 = 有人越了界**（裁定 10）：**不要"解决"它**，先回报并把冲突文件列出来。
3. **合并后在主树跑一次 `./mvnw -pl simos-util,simos-map,simos-social,simos-unit,simos-core -am verify`**，
   记下**真实**的：rc、用例条数、`BugInstance size is 0` 出现次数、ERROR/WARNING 计数。
   ★ **不许拿分支上的数字冒充合并后的数字**（那是"我验过了"与"我记得是这样"混为一谈）。
4. **清理 worktree**：`git worktree list` 逐个 `remove`；★ 但**先确认该分支已合并或已记档**。
   `.claude/` 本来就不入库，别 `-A`。
5. **回填**：
   - ~~spec §二 / §〇.3 补 `simos-util` 的 jdk8~~ ★ **实测：spec 里早就有了**（〇.3 第 9 条 + §二 的那条），本台账这条**是陈旧的**。当场复核反而揪出真问题：那两处都写着"白名单要同步改，否则 enforcer 让构建失败"——**实测是错的**（`simos-util/pom.xml:52` 的 enforcer 只有 `bannedDependencies` 的 `excludes`、**无 `includes`**）⇒ 已按实测改正，并把"推导出来的风险当成既成事实"这层教训一并写进 spec（CLAUDE.md 模块表**早就改对了**，只有 spec 那两行是错的）；
   - **spec §6.2 的事务写法**（Task 5 探针实测）：`setAutoCommit(false)` + `BEGIN IMMEDIATE` 在 sqlite-jdbc 3.53.4.0 上抛 `cannot start a transaction within a transaction`（该驱动在 autoCommit=false 时自己开事务）⇒ 已把 §6.2 改成"保持 autoCommit 出厂值 + 显式 SQL 边界"并附两个探针的实测表；
   - ~~**`Region.hexes` 的 `Set.copyOf` 上游发现**~~ ★ **当场读码后撤回**：那是 M2 **有意**的设计（`Region.java:14` javadoc 明写），且 `RegionBoundary` 有**承重的规范化补偿**（`canonicalRing` + 环表排序）⇒ 内容相等与散列序无关。**关账不呈报为缺陷**；剩下的只是"JSON 字节跨 JVM 不稳定"这条已知代价，已被裁定 12/13 覆盖（详见裁定 14 的更正）；
   - Task 17 的那条 `install` **已在 06:16 提前执行**（裁定 17），关账只需复核。
6. **如实写"未完成"**：没跑完的任务**不写成"待办"**，写成**带裁定的遗留条目**——
   每条要含：任务号 / 依赖已满足到哪一步 / 下一个该派什么 / 分支名（若有半成品）。

### 三、分支与工作树台账（收口时照此核对）
| 批次 | 任务 | 分支 | 工作树 | 状态 |
|---|---|---|---|---|
| B1 | 3 | `m4/b1` | `.claude/worktrees/b1` | ✅ 已合并 `acfcb14` |
| B2 | 4 | `m4/b2` | `.claude/worktrees/b2` | ✅ 已合并 `068769d` |
| B3 | 5 | `m4/b3` | `.claude/worktrees/b3` | ✅ 已合并 `2ec17ff` |
| C1 | 6 | `m4/b6` | `.claude/worktrees/b6` | 🔄 在跑（基线 `acfcb14`） |
| E(部分) | 16 的 SPI 两类 | `m4/b16` | `.claude/worktrees/b16` | 🔄 在跑（基线 `acfcb14`） |
| 主线 | — | `feat/adr1-core-scope` | 仓根 | 已合并 1~5 |

★ **收口清理时**：`git worktree remove` **之后还要 `git branch -D <同名分支>`**——
本轮 `m4/b6` 就是只移了树、留了分支，直接导致下次 `add -b` 失败，且**诱导"复用旧分支"这个陈旧基线陷阱**（裁定 25）。

★ **续派的最优形态已定**：B3 完结后**用 `SendMessage` 让同一个 agent 接着做 Task 6**
（它刚写完 `SqliteStore`，schema 与事务边界在它上下文里最热，省一次 ramp-up）；
B1 完结后同理接 **Task 16 的两个 SPI 类**（它刚写完 `UnitCodec`）——
★ 但 Task 16 的 `RealmEffectEndToEndTest` 要 `CoreSimos`（Task 13），**必须留到 13 之后**（裁定 20）。
