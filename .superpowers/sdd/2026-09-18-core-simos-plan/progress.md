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

★★ **时间闸（2026-09-18 07:4x 预先钉死，避免到点在压力下临时改主意）**：
- **08:00** —— 对**已写完 `task-N-report.md` 且自陈绿**的分支做合并；**没有报告 = 没完成，不合**。
- **08:05** —— **停止一切新合并**。此后主树**冻结**，开始跑最终 verify。
- **08:20** —— cron 收口，写关账报告。
★ 判定依据是**报告文件是否存在**，不是"工作树里看着写得差不多了"——半成品合并进来比不合并更糟。


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

---

# 关账（2026-09-18 08:0x，Task 17）

**结论：M4 未完成，按期收口。** 完成 **5/17**（Task 1~5），主树**冻结于 `a2bc3ce`**。
关账报告见 **`task-17-report.md`**（含「四条判据一律不评」与「我未能核实的」清单）。
权威证据：`task-17-evidence/final-freeze-verify.log`。
★ 本节**取代**上文「任务地图」与旧的分支台账。

### 一、最终 verify（照实记，不引用记忆）
- `./mvnw clean verify` @ `a2bc3ce` → **rc=0 / `BUILD SUCCESS` / 04:20 / 6-6 模块 SUCCESS**
- 用例 **168 / 254 / 36 / 74 / 36 = 568**，Failures **0** / Errors **0** / Skipped **0**
- `BugInstance size is 0` × **5**；`[ERROR]` **0** 行、`[WARNING]` **1** 行（父 POM 无 class 可查，既有常态）
- ★ 跑的是 **`clean`**，不是增量——排除 `target/` 陈旧产物冒充绿
- ★ 该命令是台账 §二.3 所写 `-pl …` 形式的**超集**（多覆盖了 parent 模块），结论更强，不冲突

### 二、★ 裁定 28 —— 早前那条「绿」是**记忆**，不是**日志**
本会话早前我写过「主树 merge 后 verify 绿：BUILD SUCCESS / 4:38 / 568 条」。
收口查证：`/tmp/m4verify/` 下**唯一**一份 `main-1-5.log` 是 **07:44:31 的 `BUILD FAILURE`**
（spotless 打回了**控制器自己手工折行**的 javadoc）。**那份「绿」没有任何日志支撑 ⇒ 作废。**
上面 §一 那组是当场跑出来并**落盘入库**的，取代它。
⇒ CLAUDE.md 形态 5 的又一实例，且这次犯错的是**控制器本人**——
**「我记得是这样」不得写进交给别人当依据的文档；没当场跑过的期望输出，不许落笔。**

### 三、★ 裁定 29 —— 08:00 的闸门**按报告文件判**，Task 6 未合并
08:00 时 `task-6-report.md` **不存在**（该 agent 刚建出 `timeline/` 目录、正在写码），
按预设闸门「**没有报告 = 没完成，不合**」**未合并**。
⇒ 主树因此保持 1~5 的已验证状态，**§一 那条 verify 对冻结树依然成立**——
**不为「没发生的事」重跑一遍 verify**（重跑要吃掉两个核，本会话已因同类操作杀过一个 agent）。

### 三之二、★ 裁定 30 —— 08:13 复检：**它绿了，我仍然没合**
08:13 复检 `m4/b6`：已提交 `ca5b446`（`Timeline.java` 414 行 + `TimelineTest.java` 291 行 +
`RevisionRow.java` 47 行 = **752 行**），**基线确为绿**（`task-6-evidence/timeline-test-green-run.log`：
`Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`）；m1 变异在**受保护的那一行**
`hasCheckpointFollowsTheThreeCriteria:203` 上准确变红，`COMPILATION_ERROR_COUNT=0`，有 MD5 自证与干净世界复位。
**看起来可以合了，我仍然决定不合**，理由两条：
1. 闸门是「**报告文件存在**才算完成」——报告不存在 = **作者没自陈完成**，且收口时它**还在跑 m2 轮、
   还在改同一个文件**；合并一个作者仍在改的分支，正是闸门要挡的「半成品」；
2. 合并后**必须重跑一次主树 verify**（实测 4:20 干净构建），而距收口只剩几分钟——
   一旦红了，就是**在截止点上把已验证的主树弄坏**，比不合并**糟得多**。
★ **要防的正是「到点了、它看着挺绿、顺手合了吧」这个念头**——这正是 07:4x 预先钉死时间闸要防的事。
合并成本极低（一次 `--no-ff` + 一次 verify），留给下一轮。
★ **一处已知的无害冲突点**：`m4/b6` 的基线 `acfcb14` **早于** spotless 修复 `9e59f5d`，故在该分支里跑
`spotless:apply` 会把 `RepoSourceScan.java` 重新折行（其 worktree 观测到该文件为 `M`）。
**同一输入的格式化是确定性的** ⇒ 两侧逐字节相同 ⇒ **合并不冲突**。
该 agent 已把这段 churn 存证为 `task-6-evidence/reposourcescan-spotless-churn.diff`——
即我在 08:0x 预测的那件事，**实测吻合**。
★ **另注意**：`task-6-evidence/` 目前仍是 **untracked**，续派时要**逐个文件 `add`** 入库（不用 `-A`）。

### 三之三、★ 裁定 31 —— Task 6 的**报告到了**（08:13:21），**我仍没合**，这次是**实测**挡住的
`task-6-report.md` 于 **08:13:21** 落盘，自陈**「已完成（含变异自证 3 轮）」**，提交 `ca5b446`。
⇒ **裁定 30 的理由 (a)（"没有报告 = 作者没自陈完成"）到此解除**，我**确实**重新考虑过合并
（报告是齐的、代码是提交过的、闸门字面条件已满足）。
**最终没合，理由是实测出来的，不是保守**：
1. 报告 §5.1 自陈「**全量 verify 未跑**（控制器指令）——SpotBugs 对 Timeline/RevisionRow 的判定、
   Checkstyle、与既有测试的相互作用，全部未测；**接手者关账前必须跑一次**」
   ⇒ 该分支**从未过过门禁**：`ca5b446` 只跑过 `-Dtest=TimelineTest test`。
2. 合并后**必须**跑一次主树 verify（实测 **4:20** 干净构建）才算数，而此刻
   **Bash 的安全分类器连续多次不可用**（`glm-5.3-flash is temporarily unavailable (rate-limited)`）。
   ★★ **归因更正（08:16 拿到真凭据后改口）**：我起初把这条读成「**同一个 CPU 饥饿症状**、
   即第三次 503 的同一根因」——**这是错的**。Task 6 的 agent 在 **08:16:48** 死于
   **HTTP 429**：`[1308][已达到 5 小时的使用上限。您的限额将在 2026-09-18 09:01:53 重置]`
   （`model sent to the API: glm-5.3-flash`，request id `202609180016479551811728268d9d6tMwP08Qd`）。
   ⇒ 分类器与 agent 用的是**同一个 glm-5.3-flash**，它的"rate-limited"是**账号级 5 小时配额耗尽**，
   **不是** CPU 过载。**503（CPU）与 429（配额）是本会话里两个不同的失效模式，别合并成一条**。
   ⇒ 执行工具（Bash/Monitor）在 **09:01:53** 前都可能不可用。
   在**命令行本身不可靠**的情况下起一个 4:20 的构建，**一旦中途失联就会把已验证的主树留在半合并态**。
   **这比不合并糟得多**，尤其在截止点上。
★ **教训（写给下一轮）**：**"闸门条件满足"不等于"能安全执行"**——还要有**能跑完验证的环境**。
裁定 30 的理由 (b)（验证成本）在 08:13 **依然是**决定性理由，只是换了形态：从"来不及"变成"跑不动"。
★★ **接手者第一件事**：`git -C .claude/worktrees/b6 add`（逐个文件）`task-6-report.md` + `task-6-evidence/`
→ commit 到 `m4/b6` → `--no-ff` 合并 → 主树 `./mvnw clean verify`。
**报告 §4 的 9 条取代说明与 §5 的 6 条"未能核实"必须一起读**，其中两条对下游有直接后果：
- §4.3/§4.4：changeset 落盘用 **mixin + `Id.CLASS`**（`ChangeSet` 是 util 冻结契约，注不上注解）；
  **代价**：`changeset_json` 里含全限定类名，类挪包即旧档不可读。
- §4.9：分岔行的 `changeset_json` 是 **`WorldChangeSet` 的 JSON**（`{"@class":…,"modules":{}}`），
  **不是** checkpoint 信封 —— Task 8 若按"信封"想象它就是错的。
- §3 m2：**计划对 `MAX vs COUNT` 的判别力推导被实测推翻**（真杀点是"查不存在的分支 ⇒ `MAX` 为 NULL"，
  不是"两条长度不同的分支"）⇒ 后续再写同类变异，**夹具必须含"查不存在的分支"**。

### 三之四、★ 控制器自读 diff（08:1x，`Timeline.java` 414 行通读）
> 依据 CLAUDE.md「**代码量小时，控制器自己读 diff 就是评审**」。**未发现缺陷**；
> 两条**接缝**留给下一轮 —— 二者都是「**没测到的地方**」，**不是已确证的缺陷，别当结论引用**。

1. ★ **`hasCheckpoint` 会做数据库 I/O，但 javadoc 自称"纯函数"**：读码确认它经 `isForkParent`
   → `store.inTransaction`，**每次调用都开事务、发一条 SQL**。该处的"纯"是指
   「**不碰文件系统**」（C18 的语境），**不是**"无副作用"。
   ⇒ **待查**：若在别的 `inTransaction` 块**内部**调 `hasCheckpoint`，就构成**嵌套事务**；
   `SqliteStore.inTransaction` **是否支持嵌套未测**（报告 §5.4 也把并发/原子性列为未测）。
   下一轮宜补一条用例，或在 javadoc 里写明"不可在事务内调用"。
2. ★ **`chainToGenesis` 无环检测**：只有「起点行缺席 ⇒ 空清单」与「中段父行缺席 ⇒ 炸」两条路径。
   `parent` 指针成环时（只能由**绕过本类**的写造成）会在**事务内死循环**，
   与本类别处「库被绕开本类写坏 ⇒ 当场炸」的姿态不一致。
   API 层构造不出环（插入只能指向**已存在**的行），属**防御性缺口**，**非现行缺陷**。

**已确认无问题的点**（省下一轮重复看）：全部 SQL 走 `PreparedStatement` 绑定参数、
表名/列名是**编译期常量**（无注入面）；`Statement`/`ResultSet` **全在 try-with-resources 内**；
`mapRow` 的 `wasNull()` **紧跟** `getLong("parent_revision")`（顺序正确，前面那次 `getString` 不干扰它）；
`branches()` 用 `LinkedHashSet` + `unmodifiableSet` **保住了 `ORDER BY` 的迭代序**
（**没有**误用 `Set.copyOf` —— 那正是 M2 裁定 12/13 的已知代价点）；`fork` 的「检查 head + 插入新行」
确在**同一事务**内（C21/C22 的原子性诉求成立）。

---

## 六、★★ 交接：主树里有**未提交**的文档改动（08:2x，必须下一轮补交）

**原因**：账号级 **HTTP 429**——`[1308][已达到 5 小时的使用上限。您的限额将在 2026-09-18 09:01:53 重置]`。
它同时打死两样东西：① Task 6 的 agent（08:16:48）；② **Bash / Monitor 的安全分类器**
（同一账号、同一 `glm-5.3-flash`）⇒ **收口时执行工具全部不可用**，只能读文件、改文件，**不能 commit/push**。

**只落在工作树、未提交的两份**：
1. `.superpowers/sdd/2026-09-18-core-simos-plan/task-17-report.md`（裁定 31/30 的更正 + 控制器自读的接缝）
2. `.superpowers/sdd/2026-09-18-core-simos-plan/progress.md`（关账节、裁定 28~32、控制器自读 diff）

**下一个人第一件事（逐条照抄）**：
```bash
cd ~/SimulatorMosire                                              # 主树，不是 worktree
pwd && git rev-parse --abbrev-ref HEAD && git rev-parse --git-dir # 期望：.git
git add .superpowers/sdd/2026-09-18-core-simos-plan/task-17-report.md \
        .superpowers/sdd/2026-09-18-core-simos-plan/progress.md
git diff --cached --stat                                          # 先扫：确认**只有这两个**文件
git commit -m 'docs(m4): 更正裁定 31 归因（429 配额 ≠ 503 CPU）+ 控制器自读 Timeline 的接缝'
git push origin feat/adr1-core-scope
```
★ **已提交并推送的最后一个是 `acd7c52`** —— **任务 1~5 的验证结论与推送状态不受影响**，
本节的未提交项**纯粹是文档**，不含任何代码。
★ **警告**：`git status` 里**只应**看到这两个 markdown + `?? .claude/`（worktree 目录，本就不入库）。
**若出现别的文件，先停下来查清楚再 add**——`target/`、证据日志、`.serena/project.local.yml` 都可能被误扫。

### 四、分支与工作树台账（收口时实测）
| 批次 | 任务 | 分支 | 工作树 | 状态 |
|---|---|---|---|---|
| B1 | 3 | `m4/b1` | `.claude/worktrees/b1` | ✅ 已合并 `acfcb14` |
| B2 | 4 | `m4/b2` | `.claude/worktrees/b2` | ✅ 已合并 `068769d` |
| B3 | 5 | `m4/b3` | `.claude/worktrees/b3` | ✅ 已合并 `2ec17ff` |
| C1 | 6 | `m4/b6` | `.claude/worktrees/b6` | ❌ **未合并**：已提交 `ca5b446`（Timeline+用例+RevisionRow，**752 行**）、**基线绿 14/14**（`task-6-evidence/timeline-test-green-run.log`）、m1/m2 变异轮已跑，**但没有 `task-6-report.md`** ⇒ 遗留 1 |
| E(部分) | 16 Step1/2 | `m4/b16` | `.claude/worktrees/b16` | ❌ **零提交**（503 杀掉，未写盘）→ 遗留 2 |
| 主线 | — | `feat/adr1-core-scope` | 仓根 | **冻结于 `a2bc3ce`**，1~5 已合并并验证 |

### 五、下一轮开工照这个顺序
1. **Task 6** —— 它一个人卡着 **7 / 8 / 9** 三个任务，是本阶段咽喉。
2. **并行 Task 16 的 Step 1/2**（`UnitTimeParticipant` + `RenameUnitHandler`）——与 Task 6 **无依赖**，
   依赖（Task 2 ✅ / Task 3 ✅ / M3 ✅）**已全部满足**。
3. ★ **并发上限是 2**（`nproc=2`，且 127.0.0.1:3000 的本地推理网关与 Maven 抢同样的核）；
   ★ **不要在 agent 活着的时候跑全量 verify**。
4. **续派前先裁决裁定 24 记下的 9↔12 接缝**（`submit(AdvanceTime)` 指向后置的 Task 12）。

---

# 关账之后 · 续跑轮（2026-09-18 15:2x 起）

> ★★ **本节取代上文若干陈旧条目**——上文是 **08:2x 收口时**的快照，其「Task 6 未合并 / 遗留 1 / 遗留 2 /
> 四之分支表里的 b6 行 / 五、下一轮顺序」**均已作废**。**以本节为准。** 保留上文是为了留痕，不是现状。
> 具体取代关系：遗留 1（Task 6 未合并）→ **已合并并验证**；遗留 2（Task 16 零提交）→ **已重开并派发**；
> §四 表里 b6 的「未合并」与 b16 的「零提交」→ **两者都已被删除重建**；§五 的顺序 → **已执行到第 4 条**。

## 甲、Task 6 已关账（6/17）

| 步骤 | 实测 |
|---|---|
| 报告与证据入库 | `80677ec`（`m4/b6`）——agent 死在 commit 前，**文件已 staged**，控制器只补了落锤 |
| 合并 | `5e49816`（`--no-ff`）——17 个文件，**零冲突**（spotless churn 如预判未冲突） |
| ★ **全量门禁** | `c76b2b6` 附日志：`./mvnw clean verify` **rc=0 / BUILD SUCCESS / 04:51**、6-6 模块、**582** 条用例（168/254/36/74/**50**，= 基线 568 + `TimelineTest` 14）、`BugInstance size is 0` ×5、`[ERROR]` **0 行** |
| 文档 | `ed60fd8`——CLAUDE.md 的 M4 行 5/17 → 6/17；推送状态行订正 |

★ **这一步正是补上 Task 6 报告 §5.1 自陈「全量 verify 未跑」的那道门禁**——`TimelineTest` 首次在
**SpotBugs + Checkstyle + Spotless + 整个 reactor** 下通过。**报告自陈的缺口，由控制器用一条日志填上了。**

## 乙、裁定 32 —— 9↔12 接缝：**用注入解耦，不重排任务**（裁定 24 由此结案）

**当场把依赖图抠出来**（实测：`awk` 扫计划里每条 `Consumes`）：

```
2,6,4 → 9 ；  9 → 10, 11 ；  10,11 → 12 ；  8,12 → 13
```
而 Task 9 Step 1 的分派表写着 `submit(AdvanceTime) → Core 的推进管线（Task 12）` ⇒ **9 → 12 与
12 → 10/11 → 9 构成闭环**。★ 且 **Task 9 的 `Consumes` 行根本没写 Task 12**——**行与它自己的
Step 1 分派表不一致**（与 Task 16 那条"grep 到签名却把路径凭印象补全"**同族**：引用没当场对齐）。

**裁决**：`AdvanceTime` 那一支的目标**由注入给出**，Task 9 不认识 Task 12。
- Task 9 定义 `core.command.AdvanceRoute`（函数式接口，`CommandResult run(AdvanceTime cmd)`），
  `CommandBus` 构造期收它；Task 9 自己的用例传**替身**。
- ★ **必须配一条用例**钉住「`AdvanceTime` 确实被路由到注入的实现、且请求原样转交」——
  否则这个注入点本身没有判别力（形态 4：纯转发型 SPI）。
- Task 12 的 `TimeAdvance` **实现** `AdvanceRoute`（其 `Consumes` 追加 Task 9 的
  `CommandResult`/`AdvanceRoute`），**不回头改 Task 9**。
- Task 13 装配时把真的 `TimeAdvance` 传进 `CommandBus`——**join 落在计划本来放装配的那一处**。

**为什么不重排**：Task 12 Step ⑤（Commit 落 revision）要用 Task 10 的乐观并发检查，
Step ③/⑥（冲突事件、`advance.finished`）要用 Task 11 的事件类型 ⇒ **12 确实在 10/11 下游**，重排不成立。
**为什么不留桩**：留 `UnsupportedOperationException` 是**运行时地雷**，且 Task 9 的门禁会**不覆盖**
自己分派表的一整支。**不影响 C16**：`switch` 仍在 Core 自己的**封闭**命令集上，只是其中一支的目标由注入给出。

## 丙、续跑轮的分支与工作树（实测）

| 批次 | 任务 | 分支 | 工作树 | 基线 |
|---|---|---|---|---|
| C2 | 7 `CheckpointStore` | `m4/b7` | `.claude/worktrees/b7` | `ed60fd8` |
| E | 16 Step 1/2 | `m4/b16` | `.claude/worktrees/b16` | `ed60fd8` |

★ **`m4/b6` 与旧的 `m4/b16` 已 `git branch -d` 删除、worktree 一并移除**（裁定 25：只移树留分支会
诱导"复用旧分支"的陈旧基线陷阱）。两个新 worktree **都从当时的 HEAD `ed60fd8` 重开**，不是从旧基线。

## 丁、下一轮的候选（按依赖）

- **Task 8 `Replay`**：`Consumes` 6 ✅ / **7（本轮在跑）** / 4 ✅ / 3 ✅
- **Task 9 `CommandRegistry` + `CommandBus`**：`Consumes` 2 ✅ / 6 ✅ / 4 ✅ ⇒ **已解锁**，
  **必须按裁定 32 的注入形态做**
- Task 10/11 ← 9 ；Task 12 ← 10/11 ；Task 13 ← 8/12 ；Task 14/15 ← 13

---

# 第二次 429 阻塞（2026-09-18 16:2x）—— **Task 7 已合并但未关账**

**失效模式与早上同一条**（**不是** 503）：账号级 **HTTP 429**，
`[1308][已达到 5 小时的使用上限。您的限额将在 2026-09-18 20:19:36 重置]`，`model sent to the API: glm-5.3-flash`。
它同时打死三样：① Task 8 的 agent（16:20:34）；② Task 9 的 agent（16:22:16）；③ **Bash 的安全分类器**（同账号、同模型）。
⇒ 16:2x 起**只能读文件、改文件**：`./mvnw`、`git add/commit/push`、派 agent **全部不可用**（已实测，`./mvnw -v` 被拒）。
用户当日做了**自动路由**，但**本会话进程早于该改动起跑**、分类器链路仍走旧配置 ⇒ **未生效**。

## 己、Task 7 已合并（**7/17，门禁缺口未补**）

| 步骤 | 实测 |
|---|---|
| 报告与证据 | `a2029f2`（`m4/b7`）——16 文件（主类 106 行 + 测试 266 行 + 报告 + 证据目录） |
| 控制器自读 diff | **未发现缺陷** |
| 合并 | `684c757`（`--no-ff`）——16 文件，**零冲突** |
| ★ **全量门禁** | ❌ **未跑**（分类器不可用）⇒ **状态是「已合并、未关账」，不得记为已验** |
| 推送 | ✅ `origin` 到 `684c757`——**由用户在会话里用 `!` 前缀亲自执行**（`618f840..684c757`）。★ **分类器不可用时这是唯一通路**，值得记住 |

**控制器自读的两条要点**（省下一轮重复看）：
- R3 用例（`diskFilesMatchHasCheckpointRowByRowAcrossABatch`）**不是走过场**：先下**防夹具退化断言**
  （`hasSize(4)`，钉住这批判定"四真三假"再继续），C19 三条判据**各有见证**
  （创世 `main@1` / `4%4==0` 的 `main@4` / 两个分叉点 `main@3`+`b2@1`），
  末尾以 `containsExactlyInAnyOrder` 把真值集**逐项钉死**——这是该用例**唯一**独立于 `hasCheckpoint` 的期望来源。
- C18 用例记下了 log4j2 两个**实测**坑：Logger facade 的 `setLevel` **不落** live `LoggerConfig`
  （事件在 appender 之前就被滤掉）、appender 不 `start()` 会**静默丢弃**。属"没红也要问为什么没红"那一族。

**取代说明我认了一条**：计划写 R17「**构造期**抛」，但构造函数只拿得到 `storeDir`、拿不到分支名
（分支名随每次 `write`/`read` 的 `StateRef` 来）⇒ 落成「**路径构造时**抛」，收在 `fileFor` **单一校验点**、write/read 共用。
**计划那句话本身有歧义**，执行者的处理是对的。
**报告 §5 的 5 条「未能核实」**（read 的 I/O 异常路径无直接测试、`..` 段路径的 ENOENT 机理未定、并发写未测、
C24 是结构保证而非时序用例、全量 verify 未跑）——**均如实存档，未当成结论**。

## 庚、裁定 33 —— Task 9 / 10 / 11 的分工（**计划没写死的那条接缝**）

**问题**：Task 9 的 `Produces` 里有 `CommandResult.Committed(StateRef)`（要求**真落盘**）
与 `Conflict(StateRef)`（要求**已有并发检查**），而「四步」写在 **Task 10** 名下。
★ **spec 不回答这个**——它描述的是**最终设计**（C17 的两处检查），**计划的切分是计划的事**。
不裁定就会让执行者猜，把 9 与 10 的设计搅在一起。

**从计划自身抠出的三条证据**：
1. Task 10 的 Files 写的是 **`Modify: CommandBus.java`**（**不是** `New`）⇒ Task 9 已交出可运行的 `CommandBus`；
2. Task 10 的变异 `m1 = 去掉③锁内复查（**只留①**）`——**「只留①」预设①在 Task 10 之前就已存在**；
3. **事件是 Task 11 的产物**（Task 11 = 可观测性：事件类型 + `Digest` + `correlationId` 全链）
   ⇒ Task 9 的「④提交」**不可能**是「revision 行 + 全部事件行」的完整形态。

**裁决**：
- **Task 9**：分派（C16 的 3 支）+ **① 入口检查** + **④ 落 revision**（经 `Timeline`，**单条、无事件**）。
  **单线程正确**；无锁、无③。
- **Task 10**：**插入 ③ 锁内复查 + 锁纪律**，并落 R7 的真实并发用例
  （`CyclicBarrier` 让两线程都越过入口检查 + 「同时进入数 ≥ 2」的**自证断言**）。
- **Task 11**：把**事件行**并入 Task 9 已建立的那条提交路径的**同一事务**（判据二的落点）。
- ★ **执行者仍须先读 spec §4 与 Task 10/11 原文核对本裁定**；若计划原意不同，**以计划为准并记取代说明**。
  **本条是从计划自身的证据推出来的，标注为推定，不是引文。**

## 辛、第三处疑点（**未证**，派 Task 8 前先当场验）

**`SimulationState` / `Snapshot` 有没有值相等语义？** R4 的对拍靠 `equals`——若只有**引用**相等，
对拍会**恒假或恒真**，该护栏就是装饰。已写进 Task 8 的派单，要求执行者**先写最小探针当场测出来**，
测不出就在取代说明里另找有判别力的对拍方式。

## 壬、工作树现状（实测）

| 分支 | 工作树 | HEAD | 提交 |
|---|---|---|---|
| `m4/b8` | `.claude/worktrees/b8` | `684c757` | **零提交**（agent 死在探针阶段，草稿未落盘） |
| `m4/b9` | `.claude/worktrees/b9` | `6e93cd5` | **零提交**（agent 死在「设计定稿、准备一次写出 7 个主文件」） |
| `m4/b7` / `m4/b16` | 已移除 | — | 已合并，分支已 `-d` 删除（裁定 25） |

★★ **限额回来后的第一件事（顺序不能换）**：
1. 主树 `./mvnw clean verify` —— **必须**，这是 Task 7 那个门禁缺口；
2. 归档日志 → 记 CLAUDE.md **7/17**（**只有第 1 步绿了才记**）；
3. 再派 **Task 9**（按裁定 32 的注入形态 + 裁定 33 的边界）与 **Task 8**（含 §辛 的探针要求）。

---

## 癸、M4 门禁第一次真正过线 —— 抓到 2 个真 defect（2026-09-18）

**补跑 Task 7 欠的那次全量 `clean verify`（`task-7-evidence/merged-full-verify.log`，红）→ 修完第二次跑绿
（`unitcodec-cast-evidence/merged-full-verify.log`）。**

### 1. `simos-unit` SpotBugs：`UnitCodec` 两条 `BC_UNCONFIRMED_CAST`（已修）

**实测对照（两边都真编译真分析，`compile spotbugs:check`）**：

| 树的类集 | `BugInstance size` | rc |
|---|---|---|
| `c76b2b6` 原样 | 0 | 0 |
| `684c757` 原样 | **2** | 1 |
| 旧树 + 仅 `UnitSnapshots.java`（同模块内 `instanceof`） | 0 | 0 |
| 旧树 + 仅 14 行探针（`instanceof MapSnapshot`，**跨模块**） | **2** | 1 |

`UnitCodec.java` 在 `c76b2b6` 与 `684c757` **逐字节相同**（`git diff` 无输出）。

⇒ **结论（实测）**：SpotBugs 对**未改动文件**的判定**不是该文件的纯函数**——它随模块类集变化；触发点已定位到
「模块内首次出现对兄弟模块 `Snapshot` 实现的引用」（旧树 + 那 14 行探针即复现）。**内部机理（类型图解析不动就保守弃报）
是推断，未证，不作为依据。**

⇒ **这条比那 2 行代码重要**：`c76b2b6` 那次「`BugInstance size is 0` ×5」里，`simos-unit`/`simos-map`/`simos-social`
的 0 **是假的**——不是它们的 cast 干净，是分析器没解出类型图。属**「把没搜到伪装成不存在」**同族
（与 ugrep 尊重 .gitignore、`git grep` 漏 untracked、M4 R15 的自建扫描器并列），**该族新增一形态**。

**修复**：三个 codec（`UnitCodec`/`MapCodec`/`SocialCodec`）的裸 `(XSnapshot)` 一律改成私有 `asXSnapshot`：
`instanceof` 模式匹配 + 指名道姓的 `IllegalStateException`（与既有 `UnitSnapshots.of`/`UnitTimeParticipant.mapOf` 同口径）。
**改完连 cast 都不存在**，不是压制告警。★ 门禁**只对 `simos-unit` 响了**；`map`/`social` 是**同型预防**
（那两个模块的 0 同样不可信），**不是「门禁抓到 3 个」**。

**护栏自证（`unitcodec-cast-evidence/`）**：m1 = helper 换回裸 cast（md5 `786fdbb9` ≠ 基线 `53ed6c2b`，
**字节不同已自证**）→ 白名单推成**目标类名** → `UnitCodecTest` **红**、编译错误 **0**、红的理由正是保护行本身
（`ClassCastException: ForeignSlice cannot be cast to UnitSnapshot`，`applyAndEncodeSnapshotRejectForeignSlice:147`）
→ 拷回原件（**非 `git checkout`**，md5 复核与基线相同）→ 绿。

### 2. `simos-core` SpotBugs：`CheckpointStore.write` 的 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`（已修）

Medium，`CheckpointStore.java:68` 的 `Files.createDirectories(file.getParent())`。`Path.getParent()` 是**可空返回**；
此处实际不为 null（`fileFor` 保证两级父路径），但**「实际不为 null」不是不变量**。

**修复（改构造，不是加断言）**：`fileFor(ref)` 拆成 `branchDir(ref)`（目录由 `resolve` **构造**出来）+ `fileName(ref)`；
`write` 不再经 `getParent()`。**取代说明**：先前台账记的「R17 校验在 `fileFor` 里、路径构造时抛」**随迁到 `branchDir`**，
语义不变（仍是构造路径时抛）。`CheckpointStoreTest` 5/5 绿，`simos-core` `BugInstance size is 0`。

★ **这是 Task 7 的代码第一次真正过 SpotBugs**——合并时被 429 挡着，之后那次全量 verify 里 `simos-core` 是 `SKIPPED`。
我逐行读过那 106 行的控制器评审**没看出这条**：静态分析抓的是「依赖可空 API」这类读代码不容易当回事的东西。

### 3. 第二次 `clean verify` 结果（`unitcodec-cast-evidence/merged-full-verify.log`）

rc=0、6/6 模块 SUCCESS、`BugInstance size is 0` ×5、`[ERROR]` **0** 行、`[WARNING]` 1（父 POM 的 spotbugs report goal，无害）、04:26。
用例 **608**（168/255/37/93/55）——较 `c76b2b6` 的 600 多 8 = 3 条新守卫用例 + `CheckpointStoreTest` 的 5 条
（那次 `simos-core` 是 SKIPPED，从未计入）。

### 4. 另一条实测（不当结论用，只立规矩）

`./mvnw -pl <mod> spotbugs:check` **直调不跑生命周期、不编译**。在没编译过的树里它会 **rc=0 / 日志 0 行 / 无任何提示** 地通过。
本会话第一次「旧提交 rc=0」就是这么来的，随即自查发现（`target/classes` **0 个类**、日志 0 行）当场作废重做。
**要跑门禁就跑 `verify`，别直调单点 goal。**

---

## 子、第三次派单（2026-09-18，门禁转绿之后）

**前置已办**：
1. 全量 `clean verify` **绿**（rc=0、6/6 模块、608 用例、`BugInstance size is 0` ×5、`[ERROR]` 0、04:26）——
   Task 7 欠的那道门禁**已补上**，见 §癸。
2. 提交 `adfb871` 已推上 `origin/feat/adr1-core-scope`（`684c757..adfb871`）。
3. CLAUDE.md M4 行 → **7/17**，并把 §癸 的实测升为**纪律形态 6**。
4. ★ **顺手清掉一条陈旧指引**：CLAUDE.md 原写「下一轮：Task 16 的 Step 1/2」，但 `git log` 实测那两步
   **已于 `eaa8093` 落地**（`UnitTimeParticipant`/`RenameUnitHandler`/`UnitSnapshots` 三者都在库里）。
   若照旧指引走，下一轮会**重做完工的事**。已改成「下一轮：Task 9 与 Task 8；Task 16 的 Step 3 等 Task 13」。

**两棵树都 `reset --hard adfb871`**（`m4/b8`/`m4/b9` 均**零提交**，重置不丢任何已提交工作）。

★ **b8 树根留有一份 `Task8-probe-draft-from-dead-agent.java.txt`**——上轮死在探针阶段的 agent 留下的
**未经核实**草稿（307 行）。处置：**从 `src/test/java/` 移出到树根**（移出 ≠ 删除），
既保持"干净世界"（它不会被编译进构建），又不销毁别人的工作；派单里已写明**可以读、必须自己重跑、只信自己跑出来的、绝不许进提交**。

| 批次 | 任务 | 分支 | 工作树 | 基线 |
|---|---|---|---|---|
| D1 | 9 `CommandRegistry`+`CommandBus` | `m4/b9` | `.claude/worktrees/b9` | `adfb871` |
| C2 | 8 `Replay` | `m4/b8` | `.claude/worktrees/b8` | `adfb871` |

**派单里写进去的硬约束**（除计划原文外，控制器另加的）：
- Task 9：裁定 32 的 `AdvanceRoute` **注入**形态 + 形态 4 的转发用例；裁定 33 的边界（**明示"③与锁纪律归 Task 10"**，
  且**明示裁定 33 是推定、须与 spec §4 及 Task 10/11 原文核对，不符则以计划为准并记取代说明**）。
- Task 8：**先探针、后写码**（§辛）；`fromGenesis` 开关而**不改 `hasCheckpoint`**；用例必须带**跨分支 target**；
  `N` 为构造参数（`CoreConfig` 尚无，如何注入由执行者定并记取代说明）。
- 两单都写了 **ADR-1 的落地形态**：`simos-core` main **不许 import 领域模块**，codec 表与 `AdvanceRoute` 都是**注入**。
- 两单都写了 **`-am` 必带**（不带会从**可能陈旧的 `~/.m2`** 解析兄弟模块——`~/.m2` 里那份 simos 构件是
  2026-09-18 05:12 探针 `install` 写进去的）、**禁 `install`**、**禁改 `pom.xml`**、**全量 verify 只跑一次且在最后**。
- ★ **并发上限 2 已用满**；两单都提示了"另一棵树上有 agent 在跑，2 核机器慢是正常的，别把慢当挂死"。

---

## 丑、第三次 429 —— 两个 agent 同时被打死，改**控制器内联执行**（2026-09-18 17:24）

**实测**：`17:24:49` 与 `17:24:51`，Task 9 与 Task 8 的 agent **相隔 2 秒**双双死于同一条
`HTTP 429 / [1308] 5 小时使用上限 / 限额将于 2026-09-18 20:19:36 重置 / model sent to the API: glm-5.3-flash`。
`ListAgents` 复核：两个都 `failed`，无存活子进程。

★ **但 `./mvnw -v` 当场实测 rc=0（可跑），控制器的调用也活着** —— 差别在**模型路由**：
agent 走 `glm-5.3-flash`（限额中），主会话走另一路。⇒ **本次 429 只打死 subagent，没打死 Bash 分类器**
（与 16:2x 那次不同，那次三样一起死）。

⇒ **裁决：改控制器内联执行**（`M0` 就是本会话内联做完的，有先例）。**不再重试派单**——错误信息已明确写出
账户级上限与重置时刻，重试是确定性失败。

**两棵树**（`m4/b8`/`m4/b9`，均零提交、均在 `adfb871`）**本轮闲置**；内联在主树 `feat/adr1-core-scope` 上做。
★ 若之后限额恢复仍要派单，**必须先 `reset --hard` 到当时的 HEAD**——它们现在指向的基线会变陈旧（裁定 25 的陷阱）。

## 寅、裁定 34 —— 9↔8 接缝：**注入 `StateLoader`**（与裁定 32 同法，但成因不同）

**当场发现**（读 spec §4.4 的 ①②③④ 与计划 Task 9 的 Steps）：**② handler 需要一个 `SimulationState`**，
而装配状态的唯一来源是 `Replay.replay(StateRef)`（Task 8 / spec §6.4）。但
**Task 9 的 `Consumes` 里没有 8，Task 8 的 `Consumes` 里也没有 9** —— 计划把这条缝**漏掉了**
（与 Task 9 `Consumes` 漏掉 Task 12 **同族**：引用没当场对齐）。

**为什么这次不"让 9 直接依赖 8"**：技术上无环（`9 → 8` 合法，两者同在 simos-core），但
① 会让 Task 9 的用例被迫搭一整套 store+timeline+checkpoint 才能验一条 R11 转发；
② `Replay` 是重协作对象，而 Task 9 只需要"给定坐标给我状态"这一件事。

**裁决**：Task 9 定义 `core.command.StateLoader`（函数式接口，`SimulationState load(StateRef ref)`），
`CommandBus` 构造期收它；Task 9 自己的用例传替身；Task 13 装配时传 `replay::replay`。
- ★ **必须配一条用例**钉住「loader 被**以确切的坐标**问过、且它返回的状态**就是**交给 handler 的那个」
  （形态 4：纯转发型 SPI）。★ 坐标是 `(envelope.branch(), head(branch))` ——**不是** `expectedRevision`：
  两者在 ① 通过后相等，但读的是"当前 tip"这个语义。
- **与裁定 32 的区别（别混为一谈）**：裁定 32 是**解环**（9↔12 构成闭环，非注入不可）；本条**不是解环**，
  是**为可测性与职责分离选的注入**。理由不同，结论同形。

## 卯、裁定 35 —— 领域命令的 revision **继承父的时刻**

**当场发现**：`revisions.tick` 是 **NOT NULL**（spec §3.2 冻结 schema），而 spec §4.1 的 `CommandEnvelope`
**不带任何时刻字段**（只有 commandId/correlationId/initiator/branch/expectedRevision/type/payloadJson）。

⇒ **裁决**：领域命令（信封支）落 revision 时，`SimosTimestamp` **继承父行**——与 `Timeline.fork` 同一口径。
**理由**：时刻只由时间推进改变（spec §五的两阶段推进是唯一改 tick 的路径），一条 `unit.RenameUnit` 不该移动时钟。
★ **这条是推定**（从"信封无时刻字段 + tick NOT NULL"两个事实推出来的，spec 没有明文写）；
Task 12 落地后（`AdvanceTime` 走自己的路径）回来复核；若与 §五冲突，以 §五为准并记取代说明。

## 辰、裁定 36 —— Core 自己的两条命令**补上身份三件套**（取代 spec §4.1 的 record 形状）

**当场发现**：`revisions` 表的 `command_id` / `correlation_id` / `initiator` **三列都是 NOT NULL**（spec §3.2 冻结
schema），而 spec §4.1 的 `ForkBranch(source, expectedRevision, newBranch)` **三件套一个都没有** ——
`Timeline.fork(...)` 却要收这三个参数。**信封支没有这个问题**（`CommandEnvelope` 自带三个）。

⇒ **裁决**：Core 自己的两条命令**各补三个组件**，与 `CommandEnvelope` **同形同序**：
- `AdvanceTime(commandId, correlationId, initiator, branch, expectedRevision, range)`
- `ForkBranch(commandId, correlationId, initiator, source, expectedRevision, newBranch)`

**理由**：① schema 强制三列 NOT NULL，数据必须有来源；② 对称——**没道理信封带身份、Core 自己的命令不带**；
③ **避免第五个注入点**（否则 `CommandBus` 还得再收一个身份源，而 Task 11 的 correlationId 全链又要动它）。
**标注：这是对 spec §4.1 的取代**，Task 12/13 必须按新形状写。`CommandEnvelope` **一字不改**（计划的 Produces 行是平铺的，照抄）。

## 巳、裁定 37 —— `type` 的**命名空间前缀**是 Core 与模块之间的约定

**当场发现**：`HandlerOutcome.Applied` 只携带一个 `ChangeSet`（util.spi，Task 2 冻结，**不动它**），
而 `WorldChangeSet` 是 `Map<namespace, ChangeSet>` ⇒ **Core 必须知道这个 ChangeSet 属于哪个 namespace**，
可 `CommandHandler` **没有 namespace() 访问器**。

**裁决**：约定 `CommandHandler.type()` **必须**形如 `<namespace>.<Command>`（`RenameUnitHandler` 实测正是
`"unit.RenameUnit"`），namespace = 第一个 `.` 之前的部分。
- **校验点放 `CommandRegistry` 构造期**：`type()` 不含 `.`、或 `.` 在首尾 ⇒ **当场抛**。理由：这是装配错误，
  早响比晚响好，且**不许**在 `CommandBus` 的每次提交里重复校验。
- `CommandBus` 用同一规则把 `Applied(ChangeSet)` 装进 `WorldChangeSet`（单键）。
- ★ **这不违反 C16/C26**：Core 没有 `instanceof` 任何模块类型、没有 parse 载荷，只用了一个**由 Core 自己规定的字符串形状**。
- ★ **推论（当场）**：`CommandRegistry` 的重复注册校验与命名空间校验**都在构造期**，故 `CommandRegistry`
  **不提供可变的 `register()`**——构造期收一个 `Collection<CommandHandler>`。这与计划 Produces 行写的
  `register(CommandHandler)` **不同**，记在此处作取代说明：可变注册与 spec §4.3 的「**构造期**抛」自相矛盾。

## 午、Task 9 已关账（**8/17**，2026-09-18 17:5x，控制器内联执行）

**交付**：`core/command/` 8 个主源文件（`CommandEnvelope` / `AdvanceTime` / `ForkBranch` / `CommandResult` /
`AdvanceRoute` / `StateLoader` / `CommandRegistry` / `CommandBus`）+ 2 个测试类（**6 + 13 = 19 条**）。
全部走**裁定 32 / 33 / 34 / 35 / 36 / 37** 的形态——**本任务是 M4 里与 spec 分歧最多的一个**（六条真设计缺口，
不是笔误），逐条裁定见 §乙/§庚/§寅/§卯/§辰/§巳。

**绿轮（全量，不是单类）**：`./mvnw clean verify` → **rc=0**，5 分 23 秒，**627 条用例**
（168/255/37/93/**74**——core 55→74，**+19 恰为 6+13**），`[ERROR]` **0 行**，`BugInstance size is 0` ×5，
`BUILD SUCCESS`。日志 `task-9-evidence/merged-full-verify.log`。
★ 单类绿轮首跑用了 `-q`（无汇总行）——按形态 1 当场摘掉 `-q` 重跑，确认 19 条**确实执行**，排除
`No tests matching pattern` 的假绿。

**变异自证 2 轮，0 存活**：m1 分派路径 trim 载荷 ⇒ 恰红 `payloadJsonIsForwardedByteForByte:169`（R11），
**红的理由正是 trim 本身**（expected 带首尾空白、actual 被削），且 **13 条里只红这 1 条**——12 条用 `"{}"`
的用例全绿，与计划表预判逐字吻合；m2 删重复检查改静默覆盖 ⇒ 恰红 `duplicateTypeFailsAtConstruction:44`（R12），
理由 `Expecting code to raise a throwable`。两轮均 `COMPILATION ERROR` = 0、落盘 md5 == 变异体 ≠ 原件、
恢复后 md5 逐字节归位（`0c60d6e0…` / `5d62c1d7…`），**未用 `git checkout --`**。

**★ 装置自检新增一条（形态 1 的装置家族）**：m2 的**首跑作废**——那条命令忘了 `export JAVA_HOME`，
`mvnw` 根本没启动，但 **`rc=1` 成立、`grep -c "COMPILATION ERROR"` 仍给出 0**。
⇒ **「编译错误计数为 0」单独不足以证明这一轮真的跑过**。装置自检**必须**再加
`grep -cE 'Tests run: [0-9]+' <轮日志>` **≥ 1**，否则当场作废整轮（已按纪律先恢复干净世界再整轮重做）。
与「**没红也要问'为什么没红'**」同源：`0` 与「空」都能同时由**成功**和**根本没跑**产生。

**留给下游的硬接缝**（Task 10 必读，详见 `task-9-report.md` §5）：
1. **`submit` 现在不是线程安全的，且是故意的**（裁定 33）：① 入口检查与 ④ 落行之间**无锁**，
   两次并发提交可同时通过入口检查并各落一行；③ 的锁内复查**必须**补上。
2. **revision 号并发分配语义未定**：`commit` 用 `base.revision().value() + 1`，并发下两提交算出**同一个**号，
   主键会拒第二条 ⇒ Task 10 要么靠锁排除、要么把失败折成 `Conflict`，**两条路都还没选**。
3. **裁定 35 是推定**（时刻继承父行），**Task 12 落地后必须复核**：若时间推进语义要求领域命令也动 `tick`，本条要改。
4. **`StateLoader` 的真实装配（`replay::replay`）从未跑过**——Task 8 未落地，全部用例用替身 `ref -> STUB_STATE`；
   `CommandBus` 与 `Replay` 的组合**从未在真状态上执行过**，Task 13 装配时第一个要看这里。
5. **裁定 37 未做真集成**：命名空间前缀只用替身 type 验过，**没有真的把 `RenameUnitHandler` 装进
   `CommandRegistry` 跑一遍**——归 Task 13。

**下一轮**：**Task 8（`Replay`）**——开工前先做**强制探针**（台账 §辛）：当场实测
`SimulationState` / `Snapshot`（及 `GameMap` / `SocialData` / `UnitState`）**有没有值相等语义**，
没有则 R4 对拍是装饰。★ 工作树 `b8`/`b9` 仍停在 `adfb871`，**派发前必须 `reset --hard` 到当时的 HEAD**（裁定 25 的陈旧基线陷阱）。
