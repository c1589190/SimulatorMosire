# CoreSimos（M4）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `simos-core` 从空模块建成 M4 交付物：**可分岔的时间线 DAG**（`revisions` 单表 + 派生一切）、**两阶段时间推进**（Prepare→Propose→Resolve→Validate→Commit→Post-commit）、**Command Bus**（领域命令以不透明 JSON 载荷跨边界 + 乐观并发两处检查）、**存储**（`revisions` + `events` 同库同事务、checkpoint 是纯优化、重放对拍）、**可观测性**（8 类事件 + `correlationId` 全链）；并交付 **U15 乙**的域侧最小真实链路（`UnitTimeParticipant` + 一条域命令走信封全链）。

**Architecture:** Core 是**集成点**，不是领域模块。它的结构由 ADR-1 的三条硬约束定形：

1. **Core 的 main scope 看不见领域类型**（enforcer 已生效）⇒ 命令跨边界是**不透明载荷**：Core 手里只有 `type` 字符串与 `payloadJson` 文本，**不 `instanceof`、不 `switch` 领域载荷**（C16）。
2. **三个契约放 `io.mosire.simos.util.spi`**（U12：不拆独立模块；ADR-1 §六：既有契约原地不动）。
3. **Core 从不反序列化模块类型**（C26）：信封由 `ObjectNode` 手工拼装，模块载荷是其中的**一段 JSON 文本** ⇒ 多态反序列化的整类问题在 Core 侧**不存在**。

**Tech Stack:** Java 21、Maven（`./mvnw`）、JUnit 5 + AssertJ、Jackson databind、SQLite JDBC、SLF4J、google-java-format（Spotless）、Checkstyle、SpotBugs。

**Spec:** `docs/superpowers/specs/2026-09-18-core-simos-design.md`（M4 spec，610 行，13 节）。上游：总纲 `2026-09-16-simos-master-design.md`、**ADR-1 `2026-09-18-spi-layering-design.md`**（修订总纲 §三 最后一条）。
**执行者必须读 M4 spec 全文**（本计划的每一步都从它派生）；**spec 与 `src` 才是权威**。

**权威层级**（spec 首行已写，此处重申）：总纲 > **ADR-1** > spec > 本计划。**本计划的代码草图若与 spec 冲突，以 spec 为准**；spec 与 `src`（已落地的 M1/M2/M3）冲突时以 **`src` 为准**并在关账时记取代说明。

> **⚠️ 本计划的代码草图是计划期产物。** M1 的教训：草图**编译得过但可能跑不过**。
> 执行期就地校正处**一律保留草图原貌 + 加取代说明**，**不要抹掉计划原文**——抹掉它等于抹掉"spec 在执行期被磨尖过"这件事。

> **⚠️ 取值纪律（与 M2/M3 同形，但 M4 的取值来源不同）：** 本计划出现的每个数字——**`100`（checkpoint 周期 N）**、**`50`（判据三的 K 轮）**、**`0`（R15 的命中数）**、**`4`（R1 的实现者数）**、**`16`（Digest 的截断字节数）**、**`5000`（`busy_timeout`）**——**全部由 M4 spec 冻结**（依次为 §3.5 / §十一 R7 / §十一 R15 / §十一 R1 / §七.1 / §6.1）。
> **照抄，不得自行发挥**；跑出来对不上 ⇒ 是**实现或本计划**有错，当场记录取代说明，**不许改夹具去迁就实现**。

---

## Global Constraints

- **Java 21**（`maven.compiler.release=21`）；Maven `[3.8,)`；父 POM `io.mosire:simos-parent:0.1.0-SNAPSHOT`，**不继承** `io.mosire:mosire-parent`
- **依赖白名单**（enforcer 构建期强制，越界即构建失败）：
  - `simos-util` / `simos-map` / `simos-social` / `simos-unit` 的白名单**与 M3 相同，一个都不许加**（util 仅 `jackson-databind` + `slf4j-api`；map 加 `simos-util`；social/unit 加 `simos-util` + `simos-map`）
  - **`simos-core`**：main scope = `simos-util` + `agentlib-mosire` **+ 本任务新声明的 `jackson-databind`、`sqlite-jdbc`**（spec 〇.3 第 8 条）**+ 日志实现**（`log4j-core` + `log4j-slf4j2-impl`，test scope 亦可，见 Task 3 Step 6）；map/social/unit **只在 test scope**，由 `enforce-core-boundaries` 钉住
  - ★ **`simos-core` 的 `bannedDependencies` 块一字不改**：两个新依赖都不是领域模块，ADR-1 不受影响（spec §二 已核）
- **Core 的 main 源码不得出现 `io.mosire.simos.map` / `.social` / `.unit`**——构建期强制，不是约定。**反过来，三个模块不得出现 `io.mosire.simos.core`**（各自 enforcer 已就位）
- **`simos-core` 的 main 源码不得 `switch` / `instanceof` 领域载荷**（C16）。允许的两处例外：`AdvanceTime` 与 `ForkBranch`（Core **自己的封闭集合**）
- **Core 不做任何领域计算**（铁律 4）：不 import `GameMap` / `Unit` / `PopulationSeries`，不写人口公式、不写寻路、不算移动——**一律经 `ModuleCodec.apply` 转交**
- **中文注释与文档**；Javadoc **不手工调行宽**（google-java-format 按字符数折行，CJK 计 1 列）——写完跑 `./mvnw -q spotless:apply`
- **测试风格**：JUnit 5 + AssertJ；测试类包级私有、**类名与方法名用英文**（沿用 M2/M3 风格），Javadoc 与注释用中文
- **迭代只跑相关单条用例**：`./mvnw -q -pl simos-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=<类名> test`（其余模块同理；都在仓库根执行）
- **关账门禁**：`./mvnw clean verify` = Spotless(check) + Checkstyle(validate) + SpotBugs(verify) + enforcer + Surefire。**`mvn test` 不跑 SpotBugs**
- **不可变与 equals**：所有状态类型是 record；`equals`/`hashCode` **一律由 record 提供、禁止手写**。需要保序的 `Map` 用 `Collections.unmodifiableMap(new LinkedHashMap<>(…))`，**绝不用 `Map.copyOf`**（迭代序不是内容的纯函数，M2 Task 5 实测 30 次）
- **SQLite 的事务纪律**（C23）：单连接 + **私有 `Object` 锁**（不是 `synchronized` 修饰符——会触发 SpotBugs `USO_UNSAFE_METHOD_SYNCHRONIZATION`）+ `setAutoCommit(false)` + `BEGIN IMMEDIATE` + 显式 `commit()`/`rollback()`；`PRAGMA foreign_keys = ON` **必须显式打开**（SQLite 默认关闭，不开的话 FK 是装饰）
- **提交纪律**：只 `git add <本步明确列出的文件>`，**绝不 `git add -A`**；提交前扫 `git diff --cached`；**该推就推**（私有仓库，用户 2026-09-17 原话「你爱推就推反正是私有仓库」）
- **提交信息不加 `Co-Authored-By` trailer**：本仓既有提交**都没有**（`git log --format=%B` 实测）⇒ 沿用仓库风格
- **本机 `grep` 是 `ugrep`**：尊重 `.gitignore` 且跳过隐藏目录，会**静默返回空**。查全仓用 `git grep`，或 `grep --hidden --no-ignore-files`

### 护栏自证（G13）——本计划每条护栏都要跑

**形态清单是权威**（CLAUDE.md 纪律节五条），M4 特别相关的是第 ①⑤ 两条。**装置必须照下面这个骨架写**：

1. **变异体先自证字节不同**：开跑前把原件拷一份作参照，比 `md5sum`，证明落盘的确实是与原件**字节不同**的那份（否则 javac 编的可能还是原件，三向全绿）
2. **按白名单推成目标类名**：拷成 `target/classes` 对应的**目标类名**（不是变异文件名）——按变异文件名拷入会让"红"变成**编译错误**，不算数
3. **每轮恢复干净世界**：先重编原件、比 md5，再开跑；`target/classes` 里的旧 `.class` 会**活到下一轮**
4. **强制断言 `grep -c "COMPILATION ERROR"` 为 0**，不为 0 就当场作废这一轮
5. **红点必须落在被保护的那一行上**——红了要问"为什么红"，没红要问"为什么没红"（空输出可能只是根本没跑到）
6. ★★ **还原基准一律写成"回到开跑前的工作树状态"**，**不得写成 `git checkout -- <file>`**。ADR-1 的变异轮上踩过：改动**未提交**时 `git checkout` 会把被测对象整个删掉，之后的绿是**假绿**。正确做法是**开跑前先 `cp` 一份字节级备份**（如 `/tmp/<file>.orig` + 记 md5），回滚用 `cp` 回填、再比 md5

### 评审与派单纪律

- **单个模块开发任务的评审不超过 3 轮**（用户裁定，2026-09-16）。数的是**该任务上以"发现问题 / 判是否可关账"为目的的独立派发**
- ★★ **2026-09-17 用户重申**：「别他妈一个模块跑几轮十几轮评审，**这个代码没多少，评审用的上下文比项目大了**」⇒ **代码量小时，控制器自己读 diff 就是评审**，不必派评审者；实现者**自带的变异自证**已经是"测试"，不要在外面再套一层评审去复现它
- **台账记裁定与结论，不记取证过程**

## 环境预置（★ 执行者必读，2026-09-18 由控制器当场实测并处置）

```bash
export JAVA_HOME="$HOME/.local/opt/jdk-21"
export PATH="$HOME/.local/opt/jdk-21/bin:$HOME/.local/opt/maven/bin:$PATH"
```

**shell 状态不跨命令保留**——每条 `Bash` 调用都要重新 `export`，否则会得到
`The JAVA_HOME environment variable is not defined correctly`（看起来像"工具链坏了"，其实只是变量没了）。

### Maven 的代理陷阱（★ 本项目**新踩到**的坑，值得记进 `CLAUDE.md` 的换设备自检清单）

本机 `HTTP_PROXY` / `HTTPS_PROXY` = `http://127.0.0.1:7890`（端口实测开着），`curl` 走它**可达 Maven Central**（实测 HTTP/2 200）。
**但 Maven 不读这两个环境变量**——它只认 `settings.xml` 的 `<proxies>`；JVM 的 `-Dhttps.proxyHost` / `-Dhttp.proxyHost` **实测也不生效**。
**症状**：`./mvnw dependency:get …` **静默超时**，看起来像"外网不通 / 依赖不可得"，**实际只是没配代理**。
★ 这是"**工具的静默假阴性**"的又一形态——和 ugrep 跳过隐藏目录那条同族：**失败的样子长得像"不存在"**。

**处置**：已创建 `~/.m2/settings.xml`（**此前不存在**）带 `<proxies>` 段，注释里写了由来与撤销方法（删文件即可）。
**它只影响需要联网解析构件的场景**；`~/.m2` 已缓存的构件不受影响。

### 已预取的构件（实测落盘在 `~/.m2`）

| 构件 | 版本 | 谁用 |
|---|---|---|
| `com.fasterxml.jackson.datatype:jackson-datatype-jdk8` | 2.22.2 | Task 3（`Optional`，见下） |
| `org.apache.logging.log4j:log4j-core` | 2.26.1 | Task 3 Step 6（test scope） |
| `org.apache.logging.log4j:log4j-slf4j2-impl` | 2.26.1 | Task 3 Step 6（test scope） |

其余（`sqlite-jdbc 3.53.4.0`、`slf4j-api 2.0.19`、`agentlib-mosire 0.1.0-SNAPSHOT`）**本机早已在**，实测确认。

### ★ `~/.m2` 里现在多了 simos 自己的构件（2026-09-18 05:1x 由探针的 `install` 写入）

探针为了在 `simos-core` 里跑 scratch 测试，`install` 了 `simos-util` / `simos-map` / `simos-social` / `simos-unit`
（`~/.m2/repository/io/mosire/`，**此前只有 `agentlib-mosire` 与 `mosire-parent`**）。

**这带来一个新的陈旧解析陷阱**：此后任何 `-pl <模块>` **不带 `-am`** 的构建，会从 `~/.m2` 解析到
**05:15 那一刻的快照**，而不是工作树里的当前源码。尤其 `simos-core` 的 main scope 依赖 `simos-util`——
单独 `mvn -pl simos-core test` 会拿到**缺 Task 2 `util.spi` 类型**的旧 jar，报出一堆"类型找不到"，
**看起来像自己写错了，其实是解析到了旧构件**。

⇒ **M4 全程一律 `-pl <模块> -am`**（`-am` 会把上游模块拉进 reactor，reactor 优先于 `~/.m2`）。
这本来就是 `CLAUDE.md` 的既有要求，此处只是补上"为什么今天尤其要紧"。
★ 与 `AgentLibMosire` 那条同族（见 `CLAUDE.md`）：**`~/.m2` 的陈旧构建比"测试变红"更早一步，
表现为编译失败**。判断构件新鲜度**看类数/内容，不看时间戳**。

### ★★ 交叉纪律：**变异轮期间，工作树必须独占**（2026-09-18 新增）

上面那条 `install` **恰好**落在一个安全时刻，但这是**运气**：探针 `install` 的 05:12 那一轮，
Batch A 正在做 Task 1 的变异自证（会**临时把 mutant 推进 `ChangeSet.java`**）。
若两者错开几分钟，`~/.m2` 里就会冻进一份 **mutant 版 `simos-util`**——而 `~/.m2` 是**跨工作树共享**的，
它会把污染**扩散到之后每一个不带 `-am` 的构建**，且**没有任何测试会因此变红**。

**当场实测（05:2x，控制器）**：`javap -p` 拆开 `~/.m2` 里那份 `ChangeSet.class`，
得到 `public interface io.mosire.simos.util.state.ChangeSet {}` ——**是 Task 1 之后的正确形态**（标记接口），
**不是 mutant**。即本次**未污染**，此条是**预防**，不是事后追认。

⇒ **纪律**：变异轮（以及任何会临时改写 `src/` 的活动）**要求独占工作树，且期间不得 `mvn install`**。
需要并行时，用 `git worktree` 给每个 agent 一棵独立树，并**明确禁止 `install`**（`test`/`verify` 足够）。
**判据**：并行 agent 之间**除了 `~/.m2` 没有别的共享写点**——而只要有一个 agent 会 `install`，
`~/.m2` 就成了**绕过工作树隔离的旁路**。

---

## 文件结构（M4 全景）

| 文件 | 职责 |
|---|---|
| `simos-util/.../util/state/ChangeSet.java` | ★ **改**：收窄为**标记接口**（去掉 `baseRevision()`） |
| `simos-util/.../util/verify/RoundTripAssertions.java` | ★ **改**：删 `assertSnapshotRoundTrip`（其余不动） |
| `simos-util/.../util/spi/CommandHandler.java` | 领域命令接总线 |
| `simos-util/.../util/spi/HandlerOutcome.java` | `sealed`：`Applied` / `Rejected` |
| `simos-util/.../util/spi/ModuleCodec.java` | ★ Core 与模块之间**唯一**触碰状态形状的地方 |
| `simos-util/.../util/spi/TimeParticipant.java` | 两阶段推进的 proposer |
| `simos-util/.../util/spi/TimeProposal.java` | 提案（**不是事实**） |
| `simos-util/.../util/spi/package-info.java` | U13「契约面一旦成型即稳定」的**书面落点** |
| `simos-util/.../util/json/SimosObjectMapper.java` | ★ **本计划增补**：`ObjectMapper` 的**单点装配**（理由见 Task 3 Step 5） |
| `simos-map/.../map/change/MapChangeSet.java` | ★ **改**：加 `implements ChangeSet`（字段零变化） |
| `simos-map/.../map/codec/MapCodec.java` | `ModuleCodec` 的 map 实现（JSON 形态是 **map 自己的事**） |
| `simos-social/.../social/change/SocialChangeSet.java` | ★ **改**：加 `implements ChangeSet` |
| `simos-social/.../social/codec/SocialCodec.java` | `ModuleCodec` 的 social 实现 |
| `simos-unit/.../unit/change/UnitChangeSet.java` | ★ **改**：加 `implements ChangeSet` |
| `simos-unit/.../unit/codec/UnitCodec.java` | `ModuleCodec` 的 unit 实现 |
| `simos-unit/.../unit/spi/UnitTimeParticipant.java` | ★ U15 乙：在途 `Movement` 推进到区间终点 |
| `simos-unit/.../unit/spi/RenameUnitHandler.java` | ★ U15 乙：一条域命令走信封全链 |
| `simos-core/.../core/CoreConfig.java` | 装配参数（storeDir / checkpointInterval N / ObjectMapper） |
| `simos-core/.../core/CoreSimos.java` | 装配门面（注册 codec/handler/participant + 提交入口） |
| `simos-core/.../core/state/WorldChangeSet.java` | ★ `util.state.ChangeSet` 的**首个 main 源码实现者** |
| `simos-core/.../core/command/CommandEnvelope.java` | 领域命令过边界的形态 |
| `simos-core/.../core/command/CommandRegistry.java` | `type → handler`（同 type 注册两次 ⇒ **构造期抛**） |
| `simos-core/.../core/command/CommandBus.java` | 分派（C16 的落点）+ 乐观并发两处检查（C17） |
| `simos-core/.../core/command/CommandResult.java` | `sealed`：`Committed` / `Rejected` / `Conflict` |
| `simos-core/.../core/timeline/RevisionRow.java` | `revisions` 表一行的值形态 |
| `simos-core/.../core/timeline/Timeline.java` | 时间线读写 + 一切派生查询（§3.3）+ `hasCheckpoint`（C19） |
| `simos-core/.../core/advance/TimeAdvance.java` | 两阶段推进**六步** |
| `simos-core/.../core/advance/TimeProposalResolver.java` | Resolve：读写集相交（C14/C15） |
| `simos-core/.../core/advance/AdvanceConflict.java` | 冲突报告（地址列表**字典序**） |
| `simos-core/.../core/store/SqliteStore.java` | 一个连接 + 私有锁 + `BEGIN IMMEDIATE`；建表与 PRAGMA |
| `simos-core/.../core/store/CheckpointStore.java` | `<storeDir>/checkpoints/<branch>/<revision>.json` |
| `simos-core/.../core/store/Replay.java` | 从最近 checkpoint 重放到 target |
| `simos-core/.../core/store/Envelope.java` | ★ C26：`ObjectNode` 手工拼装/拆解信封 |
| `simos-core/.../core/observe/EventTypes.java` | 8 类事件类型名（冻结，C20） |
| `simos-core/.../core/observe/Digest.java` | sha256 前 16 字节（**不记明文**） |

测试文件与实现同包，位于各模块 `src/test/java/.../<pkg>/`。**子包不必加 `package-info.java`**（M2 的 `hex`/`terrain`/`region`/`pathway` 子包即无，只有模块根有）。**`io.mosire.simos.util.spi` 是唯一例外**——它要 `package-info.java`（U13 的书面落点）。

## 任务地图（按依赖排序，逐个提交）

| # | 任务 | 交付物 | 依赖 | 派单批次 |
|---|---|---|---|---|
| 1 | 契约收敛 §十：`ChangeSet` 收窄 + 三模块 `implements` + 删 `assertSnapshotRoundTrip` + 改写 `SnapshotProtocolTest` + **R15/R18** | util/map/social/unit 五处改动 | — | **A** |
| 2 | `util.spi` 五类型 + `package-info`（U13 书面落点） | 三个契约 + 两个值类型 | 1 | **A** |
| 3 | **JSON 地基**：`SimosObjectMapper` 装配 + 三模块 `ModuleCodec` + 快照往返（**结论见 spec §13.1 / 本任务 Step 0**） | 三个 codec | 2 | **B** |
| 4 | `WorldChangeSet` + `Envelope`（C26 的 `ObjectNode` 拼装）+ 往返 + **R1** | Core 首个实现者 | 3 | **C** |
| 5 | `SqliteStore`：建表、PRAGMA、`BEGIN IMMEDIATE`、私有锁 + **R2/R13** | 存储底座 | — | **C** |
| 6 | `Timeline`：`appendRevision` / `fork` / `head` / 父链 / 一切派生 + `hasCheckpoint`(C19) | 时间线读写 | 5 | **D** |
| 7 | `CheckpointStore`（分支名路径校验 + 写/读/缺失回退）+ **R3/R17** | checkpoint | 6 | **D** |
| 8 | `Replay` + **R4/R5** | 重放 | 7 | **D** |
| 9 | `CommandRegistry` + `CommandBus` 分派（C16）+ `CommandEnvelope` + **R11/R12** | 总线 | 4, 6 | **E** |
| 10 | 乐观并发两处检查（C17）+ `CommandResult` 三形态 + **R7**（判据三） | 并发正确性 | 9 | **E** |
| 11 | 可观测性：`EventTypes` 冻结 + `Digest` + `correlationId` 全链 + **R6**（判据二） | 留痕 | 9 | **E** |
| 12 | 两阶段推进六步（C25）+ `TimeProposalResolver`（C14/C15）+ **R9/R10/R14** | 推进管线 | 10, 11 | **F** |
| 13 | `CoreSimos` 装配门面 + Post-commit 写 checkpoint（C24）+ **R8** 前半 | 门面 | 8, 12 | **F** |
| 14 | **判据一**：分岔端到端 + R8 | 分岔 | 13 | **G** |
| 15 | **判据三**：真实并发 K=50（若 Task 10 已足则本任务只做 K 轮与反例加固） | 并发 | 10 | **G** |
| 16 | unit 侧最小真实链路：`UnitTimeParticipant` + `RenameUnitHandler` + **R16**（判据四） | 域侧证据 | 13 | **G** |
| 17 | M4 关账：`./mvnw clean verify` + 四条判据逐条核 + `CLAUDE.md` + 报告 | 关账 | 全部 | **H** |

**顺序说明**：任务 3 是**风险最高的一个**（spec §13.1 第 1 条），故排在很靠前——它的结论会反过来影响 4/7/8 的形态，晚做等于返工。任务 5 与 1~4 **无依赖**，可作为并行的备选（但**同一个工作树不能并行**，见下）。

**★ 派单批次 ≠ 并行**：全部任务在**同一个工作树**里串行执行（两个 agent 同时改同一棵工作树会互相踩 `git index`、文件写入与 `mvn` 构建）。批次只表示"哪些任务共用一次 `verify` 周期、可合派给同一个执行者"，**不表示可并发**。

**★ 护栏落地位置的两个已知取舍**（写在这里，免得执行者以为漏了）：

1. **R1（main 源码 `implements ChangeSet` 恰 4 个）落在 Task 4，不在 Task 1**——`WorldChangeSet` 到 Task 4 才存在，Task 1 时那个数字是 3。
2. **R1 与 R15 的源码扫描各自带一份**：R15 在 `simos-util/src/test`（Task 1），R1 在 `simos-core/src/test`（Task 4）。**不做共享**——test scope 的类不跨模块可见，为此造一条 `test-jar` 依赖是**真的耦合**（core 的测试会依赖 util 的测试产物），代价大于 20 行重复。M2 的 `RegressionGuardsTest` 已是一个独立先例。

---

### Task 1: 契约收敛（spec §十）

**Files:**
- Modify: `simos-util/src/main/java/io/mosire/simos/util/state/ChangeSet.java`（**删掉 `baseRevision()`**，收窄为标记接口）
- Modify: `simos-util/src/main/java/io/mosire/simos/util/verify/RoundTripAssertions.java`（删 `assertSnapshotRoundTrip` 及其 `RevisionId` import）
- Modify: `simos-util/src/test/java/io/mosire/simos/util/state/SnapshotProtocolTest.java`（**改写**第二个用例为 R18）
- Modify: `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsTest.java`（删三个 `assertSnapshotRoundTrip` 用例 + 其专用玩具）
- Modify: `simos-util/src/test/java/io/mosire/simos/util/verify/RoundTripAssertionsDriftTest.java`（同上）
- Modify: `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java`（加 `implements ChangeSet`）
- Modify: `simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java`（同上）
- Modify: `simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java`（同上）
- Test: `simos-util/src/test/java/io/mosire/simos/util/verify/RepoSourceScan.java`（**新建**，test scope 的极简扫描助手）+ R15 用例落 `RoundTripAssertionsTest`

**Interfaces:**
- Consumes: 无（本任务在最上游）
- Produces: `ChangeSet` 变成**标记接口**（无抽象方法）；`RoundTripAssertions.assertRoundTrip(S, S, BiFunction<S,S,C>, BiFunction<C,S,S>)` **签名与行为逐字节不变**；三个模块变更集获得 `ChangeSet` 身份（**字段与测试零变化**）

**实测爆炸半径**（spec §十已跑，共 **14** 处 `baseRevision`，**唯一编译破坏**是 `SnapshotProtocolTest:29-30`）：

| 位置 | 收窄后 | 处置 |
|---|---|---|
| `state/ChangeSet.java:9` | 被删的就是这一行 | **删** |
| `verify/RoundTripAssertions.java:33,37` | `assertSnapshotRoundTrip` 的方法体 | **随方法删** |
| **`state/SnapshotProtocolTest.java:29-30`** | **编不过**（lambda 不再满足 SAM） | **改写**为 R18 |
| `verify/RoundTripAssertionsTest.java`（6 处） | **照样编译**（record 组件解析） | 只删随 `assertSnapshotRoundTrip` 走的用例 |
| `verify/RoundTripAssertionsDriftTest.java`（3 处） | **照样编译**（同上） | 同上 |

> **为什么"照样编译"**：那三个测试 record 各带一个 `baseRevision` **record 组件**，而它们的 `apply` 签名收的是**具体类型**（`PlainChangeSet` / `ToyChangeSet` / `DriftingChangeSet`）——`changeSet.baseRevision()` 解析到的是 **record 访问器**，不是接口方法。⇒ **接口收窄对它们是透明的。**

- [ ] **Step 1: 收窄 `ChangeSet`**

```java
package io.mosire.simos.util.state;

/**
 * 变更集（总纲 §4.5）：字段清单由**各模块从自己的 Snapshot 类型派生**（铁律 5）， Util 只给接口与往返断言工具（{@code
 * io.mosire.simos.util.verify.RoundTripAssertions}）。
 *
 * <p>★ **本接口是标记接口**（M4 / U 裁定，spec §十）：原 `baseRevision()` 已删。**版本戳不属于变更集**——
 * 它是 {@code Revision} 层的事实，在 M4 由 revision 行的 `parent_revision` 指针承担（C27）： {@code apply}
 * 只能拿父行指向的变更集作用在父行重建出的状态上，**这是结构不变量，比测试断言更强**。 原 {@code
 * assertSnapshotRoundTrip} 守卫的正是这条，故它随之一并删除。
 *
 * <p>★ **契约面一旦成型即稳定**（U13）：后续要动 = 大版本更新。
 */
public interface ChangeSet {}
```

- [ ] **Step 2: 删 `assertSnapshotRoundTrip`**

删掉 `RoundTripAssertions.java` 的 **29~43 行整段**（javadoc + 方法），并删掉随之不再使用的 `import io.mosire.simos.util.state.RevisionId;`。
**`assertRoundTrip`（22~27 行）与 `checkApplied`（45~61 行）一字不动**——`assertRoundTrip` 本就是通用形，从不碰版本戳。

- [ ] **Step 3: 三模块变更集各加 `implements ChangeSet`**

三处都是**只改类声明那一行 + 补 import**，**字段与既有测试零变化**：

```java
// simos-map/.../map/change/MapChangeSet.java  —— 在既有构造器参数表之后追加
public record MapChangeSet(/* …M2 既有组件，一字不动… */) implements ChangeSet {
//  ↑ 补 import io.mosire.simos.util.state.ChangeSet;
```

`SocialChangeSet`（`simos-social/.../social/change/`）、`UnitChangeSet`（`simos-unit/.../unit/change/`）同形。
★ 三个 record 的**组件、构造校验、`diff`/`apply` 全部不动**——这是 U 裁定的原话"字段与测试零变化"。

- [ ] **Step 4: 删随 `assertSnapshotRoundTrip` 走的用例**

`RoundTripAssertionsTest.java` 里**以 `assertSnapshotRoundTrip` 为被测对象**的用例（三个）连同**只服务它们的玩具**（`PlainState` / `PlainChangeSet`）一并删；**其余用例（`assertRoundTrip` 的）一字不动**。
`RoundTripAssertionsDriftTest.java` **整份删除**——它整个类就是"盖错版本戳时 `assertSnapshotRoundTrip` 要抛"，被删的方法没了，它也就没有被测对象了。

> ⚠️ **执行者先跑 `git grep -n assertSnapshotRoundTrip` 确认到底几处、分别在哪个用例里**，再动手。spec §十的"三个用例"是 spec 写作时的实测；**若与现场不符，以现场为准并记取代说明**（这正是 M1 反复踩的"我验过了 vs 我记得是这样"）。

- [ ] **Step 5: 改写 `SnapshotProtocolTest` 的 lambda 用例为 R18**

原用例（`:26-32`）断言的**就是"`ChangeSet` 是单方法接口"这件事本身**——所以它不是被误伤，是**必须随裁定一起改写**：

```java
  /** R18：`ChangeSet` 收窄为标记接口后，这两个协议接口的形状被钉住——不许有人偷偷加回抽象方法。 */
  @Test
  void changeSetIsAMarkerAndCommandIsStillASingleMethodInterface() {
    assertThat(ChangeSet.class.getDeclaredMethods())
        .as("ChangeSet 必须是标记接口（U 裁定 / spec §十）：版本戳归 Revision 层，不归变更集")
        .hasSize(0);
    assertThat(Command.class.getDeclaredMethods())
        .as("Command 必须仍是 SAM——信封 record 靠访问器天然满足它，多一个抽象方法就破坏这条")
        .hasSize(1);
    assertThat(Command.class.getDeclaredMethods()[0].getName()).isEqualTo("expectedRevision");
  }
```

> ★ **为什么用 `getDeclaredMethods()` 而不是 `isInterface() && methods().length`**：`getMethods()` 会把 `Object` 的公共方法也算进来，计数不稳；`getDeclaredMethods()` 只数本接口自己声明的。**这类"计数型"断言必须写清数的是什么**，否则变异体一加就红、红点却不在被保护的那行上。

- [ ] **Step 6: R15——全仓 `assertSnapshotRoundTrip` 0 处**

新建 `simos-util/src/test/java/io/mosire/simos/util/verify/RepoSourceScan.java`（**test scope**，20 行量级）：从 `user.dir` 向上找到含 `simos-parent` 的 `pom.xml` 作仓根，给出 `javaFilesUnder(String relativeDir)` 与 `rawContent(Path)`。

```java
  /** R15：删掉的方法不许在别处复活——它守的性质已升级为结构不变量（C27），留着就是两个来源。 */
  @Test
  void snapshotRoundTripAssertionIsGoneFromTheWholeRepo() throws IOException {
    List<String> hits =
        RepoSourceScan.javaFilesUnder(".").stream()
            .filter(p -> RepoSourceScan.rawContent(p).contains("assertSnapshotRoundTrip"))
            .map(RepoSourceScan::relative)
            .toList();
    assertThat(hits).as("全仓应为 0 处（spec §十一 R15）").isEmpty();
  }
```

> ⚠️ **本条用例的扫描范围必须包含 `src/test`**（否则它扫不到自己，也扫不到 `RoundTripAssertionsTest` 的残留引用）。**而 `RepoSourceScan.java` 自身含这个串吗？——不含**（它只提供扫描能力，不写那个方法名）。**R15 的变异自证**：把 `assertSnapshotRoundTrip` 的签名行注回 `RoundTripAssertions.java` ⇒ 本用例必须红在 `isEmpty()` 那行。

- [ ] **Step 7: 跑验证**

```bash
export JAVA_HOME="$HOME/.local/opt/jdk-21" PATH="$HOME/.local/opt/jdk-21/bin:$HOME/.local/opt/maven/bin:$PATH"
./mvnw -q -pl simos-util,simos-map,simos-social,simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -q spotless:apply && ./mvnw clean verify
```

**Expected**：全绿。M2 的 416 条 + M3 的 517 条既有用例是**回归网**——本任务不改任何行为，用例数应只减（删掉的 `assertSnapshotRoundTrip` 用例）不减错。

- [ ] **Step 8: 变异自证 R18 与 R15**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | 给 `ChangeSet` 加回 `RevisionId baseRevision();` | `changeSetIsAMarkerAndCommandIsStillASingleMethodInterface` 的 `hasSize(0)`（★ 同时 R15 不受影响——两个护栏各管各的） |
| m2 | 给 `Command` 加第二个抽象方法 `String label();` | 同一用例的 `hasSize(1)` |
| m3 | 在 `RoundTripAssertions.java` 里注回 `public static … assertSnapshotRoundTrip(…) {}`（空体即可） | `snapshotRoundTripAssertionIsGoneFromTheWholeRepo` 的 `isEmpty()` |

**六条装置纪律照 Global Constraints 的"护栏自证"节**——尤其：**还原基准是"回到开跑前的工作树状态"**，本任务改动**尚未提交**，**绝不能用 `git checkout -- <file>`**。

---

### Task 2: `util.spi` 五类型 + `package-info`

**Files:**
- New: `simos-util/src/main/java/io/mosire/simos/util/spi/{CommandHandler,HandlerOutcome,ModuleCodec,TimeParticipant,TimeProposal}.java`
- New: `simos-util/src/main/java/io/mosire/simos/util/spi/package-info.java`（**U13 的书面落点**）
- Test: `simos-util/src/test/java/io/mosire/simos/util/spi/SpiShapesTest.java`

**Interfaces:**
- Consumes: `io.mosire.simos.util.state.{ChangeSet, Snapshot, SimulationState}`、`io.mosire.simos.util.time.TimeRange`（全部是 M1 既有）
- Produces: `io.mosire.simos.util.spi` 包 —— 三个契约接口 + 两个值类型（**Task 3~16 全部消费它**）

- [ ] **Step 1: `HandlerOutcome`**

```java
package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import java.util.Objects;

/**
 * 命令处理的结果（spec §4.3）：**只有两种**——要么给出一个变更集，要么给出拒绝理由。
 *
 * <p>★ 用 {@code sealed} 而不是枚举 + 字段：两种形态的载荷**不同型**（一个是 {@link ChangeSet}，一个是
 * {@link String}），枚举会让其中一半永远是 {@code null}。封闭性由 permits 保证 ⇒ Core 的 {@code switch}
 * 是穷尽的（这**不违反** C16：被 switch 的是 Core 自己的封闭类型，不是领域载荷）。
 */
public sealed interface HandlerOutcome {

  /** 处理成功：变更集交给 Core 落盘。 */
  record Applied(ChangeSet changeSet) implements HandlerOutcome {
    public Applied {
      Objects.requireNonNull(changeSet, "changeSet");
    }
  }

  /** 处理失败：理由进 `simos.command.rejected` 事件的 payload。 */
  record Rejected(String reason) implements HandlerOutcome {
    public Rejected {
      Objects.requireNonNull(reason, "reason");
    }
  }
}
```

- [ ] **Step 2: `CommandHandler`**

```java
package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.SimulationState;

/**
 * 领域命令接上总线（spec §4.3 / ADR-1 C9）。
 *
 * <p>★ **{@code payloadJson} 必须原样使用**：Core 保证把它逐字节转交（R11），实现者不得假定它被规范化过。
 *
 * <p>★ **实现者自己反序列化**自己的载荷——Core 从不理解它的结构（C26）。载荷的 JSON 形态是**模块自己的事**。
 */
public interface CommandHandler {

  /** 信封上的 {@code type}，形如 {@code "unit.RenameUnit"}。 */
  String type();

  /** 纯函数：读 {@code state}、吐结果，**不写状态**。 */
  HandlerOutcome handle(SimulationState state, String payloadJson);
}
```

- [ ] **Step 3: `ModuleCodec`**

```java
package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;

/**
 * 状态编解码与施加（spec §八）：Core 与模块之间**唯一**触碰状态形状的地方。
 *
 * <p>★ {@link #apply} 里的 cast 发生在**模块自己的实现里**——Core 从不 cast、从不反射模块类型（C26）。
 * 多态反序列化的整类问题因此在 Core 侧不存在。
 *
 * <p>★ 快照的 JSON 形态是**各模块自己的事**（Core 只把它当一段文本内嵌，spec §6.3）。
 */
public interface ModuleCodec {

  /** 与 {@code Snapshot.namespace()} 一致；Core 用它把载荷路由回正确的模块。 */
  String namespace();

  ChangeSet decodeChangeSet(String json);

  String encodeChangeSet(ChangeSet changeSet);

  Snapshot decodeSnapshot(String json);

  String encodeSnapshot(Snapshot snapshot);

  /**
   * 把变更集施加到 base 上，返回**新的**快照（不得就地改 base）。
   *
   * <p>★ **第三个参数 `newMeta` 是执行期裁定 C28**（spec §〇.2）：模块的 {@code XChangeSet.apply} 返回的是
   * **模块状态类型**（{@code GameMap} / {@code SocialData} / {@code UnitState}），**不带 `ref` 与 `timestamp`**
   * ——实测见 spec §〇.3 第 10 条。而 spec §5.4 第 4 项要求施加后的快照**必须带上新的 `ref` 与 `tick`**。
   * ⇒ 新坐标只能由 Core 告诉 codec。用 M1 既有的 {@link StateMeta} 承载，**不新造类型**。
   *
   * <p>★ **二参形态是不可满足的**——没有它，codec 只能照抄 base 的（陈旧且错）或凭空造一个。这正是本 spec
   * 从"纸面冻结"到"真写实现"之间被磨出来的那一处。
   */
  Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta);
}
```

- [ ] **Step 4: `TimeProposal`**

```java
package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.ChangeSet;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一次时间推进里**某一个模块的提案**（spec §5.2）。
 *
 * <p>★ **它是提案，不是事实**：模块自己算出、尚未跨模块解算、尚未校验、尚未提交。
 *
 * <p>★ {@code reads} / {@code writes} 是 **canonical 地址字符串**（C15）。v1 **只做精确集合匹配**，不做前缀
 * 包含——前缀会引入地址语义，而 Core 不懂地址语义。
 *
 * <p>★ 组件**防御性拷贝**且**保序**：{@link Set#copyOf} 的迭代序不是键集的纯函数（M2 Task 5 实测 30 次），
 * 而 Resolve 的冲突报告要求**字典序可比**（C15），故用 {@code LinkedHashSet} 包一层不可变视图。
 */
public record TimeProposal(
    String namespace, ChangeSet changeSet, Set<String> reads, Set<String> writes) {

  public TimeProposal {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(changeSet, "changeSet");
    reads = immutableCopy(reads, "reads");
    writes = immutableCopy(writes, "writes");
  }

  private static Set<String> immutableCopy(Set<String> source, String name) {
    Objects.requireNonNull(source, name);
    LinkedHashSet<String> copy = new LinkedHashSet<>();
    for (String element : source) {
      copy.add(Objects.requireNonNull(element, name + " 的元素"));
    }
    return Collections.unmodifiableSet(copy);
  }
}
```

> ★ **`Objects.requireNonNull(element, name + " 的元素")` 这一层是必须的**：少了它，集合里的 `null` 会活到 Resolve 的 `retainAll` 才炸，红点离肇事点很远。**这也是它自己的变异自证点**（R-2-m1）。

- [ ] **Step 5: `TimeParticipant`**

```java
package io.mosire.simos.util.spi;

import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.TimeRange;

/**
 * 两阶段推进的 proposer（spec §5.2）。
 *
 * <p>★ {@link #simulate} 是**纯函数**——拿 base、吐提案，**不写状态**。这是"两阶段"的全部意义：
 * 没有模块能在别人提案之前就把自己的改动落下去。
 *
 * <p>★ 每个参与者拿到的都是**同一份** base（C25）⇒ **调用顺序不影响结果**。
 */
public interface TimeParticipant {

  String namespace();

  TimeProposal simulate(SimulationState state, TimeRange range);
}
```

- [ ] **Step 6: `package-info.java`——U13 的书面落点**

```java
/**
 * Core 与模块之间的契约面（ADR-1 C9）。位置冻结，**既有契约原地不动**。
 *
 * <p>★ **契约面一旦成型即稳定**（U13，2026-09-18 用户裁定）：本包的类型一旦落地，后续**要动就是大版本更新**。
 * 加字段、改签名、改语义都算"动"。**新增契约放本包，既有契约原地不动**（ADR-1 C9）。
 *
 * <p>三个接口各自的稳定性承诺：
 *
 * <ul>
 *   <li>{@link CommandHandler}——{@code type()} 的字符串形态是**跨进程协议**（M5 的 MCP 会照抄它），改它 =
 *       改协议
 *   <li>{@link ModuleCodec}——{@code namespace()} 与 {@code Snapshot.namespace()} 绑定；JSON 形态**不在承诺内**
 *       （那是各模块自己的事）
 *   <li>{@link TimeParticipant}——{@code simulate} 的**纯函数性**是承诺：实现不得就地改传入的 state
 * </ul>
 *
 * <p>为什么不拆独立的 {@code simos-spi} 模块（U12）：**没有独立消费者**。三个实现模块（map/social/unit）
 * 都依赖 {@code simos-util}，拆出去只会多一个模块、多一次版本同步，买到的是零。包分离 + 本文件即是书面约定；
 * 真正的强制来自 {@code bannedDependencies}（模块级），而那是 **artifact 级**的——拆包买不到任何它给不了的
 * 强制（ADR-1 §二）。
 */
package io.mosire.simos.util.spi;
```

- [ ] **Step 7: `SpiShapesTest`**

```java
class SpiShapesTest {

  /** 形状护栏：`HandlerOutcome` 封闭且**恰两个**变体。多一个就说明有人在 Core 里塞了新形态。 */
  @Test
  void handlerOutcomeIsSealedWithExactlyTwoVariants() {
    assertThat(HandlerOutcome.class.isSealed()).isTrue();
    assertThat(HandlerOutcome.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(HandlerOutcome.Applied.class, HandlerOutcome.Rejected.class);
  }

  /** 防御性拷贝：改传入的集合不得影响已构造的提案。 */
  @Test
  void timeProposalCopiesItsSetsDefensively() {
    Set<String> reads = new LinkedHashSet<>(List.of("unit:u-1"));
    TimeProposal proposal =
        new TimeProposal("unit", emptyChangeSet(), reads, new LinkedHashSet<>());
    reads.add("unit:u-2");
    assertThat(proposal.reads()).containsExactly("unit:u-1");
    assertThatThrownBy(() -> proposal.reads().add("unit:u-3"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** 保序：`reads` 的迭代序 = 传入序（不是散列槽位序）。 */
  @Test
  void timeProposalPreservesIterationOrder() {
    TimeProposal proposal =
        new TimeProposal(
            "unit",
            emptyChangeSet(),
            new LinkedHashSet<>(List.of("b:2", "a:1", "c:3")),
            new LinkedHashSet<>());
    assertThat(proposal.reads()).containsExactly("b:2", "a:1", "c:3");
  }

  /** 集合里的 null 在**构造期**就炸，不留到 Resolve 的 `retainAll` 才炸。 */
  @Test
  void nullAddressInsideTheSetIsRejectedAtConstruction() {
    Set<String> withNull = new LinkedHashSet<>();
    withNull.add("unit:u-1");
    withNull.add(null);
    assertThatThrownBy(() -> new TimeProposal("unit", emptyChangeSet(), withNull, Set.of()))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("reads 的元素");
  }
}
```

> ⚠️ **`emptyChangeSet()` 请写成 `() -> {}`？——不行**。Task 1 之后 `ChangeSet` 是**标记接口**（无抽象方法）⇒ **它仍然可以是 lambda 吗？** `ChangeSet` 有 0 个抽象方法 ⇒ **不是函数式接口** ⇒ **lambda 不合法**。故写 `private static ChangeSet emptyChangeSet() { return new ChangeSet() {}; }`（**匿名类**）。
> ★ 这条正是 Task 1 那个编译破坏的**同源形态**，执行者若在此处写 lambda 会得到同一类错误——**这是本 Task 最容易踩的坑，写在计划里而不是留给执行者发现**。

- [ ] **Step 8: 跑 + 变异自证**

```bash
./mvnw -q -pl simos-util -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='SpiShapesTest' test
./mvnw -q spotless:apply && ./mvnw clean verify
```

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | `TimeProposal` 去掉 `immutableCopy` 的逐元素 `requireNonNull` | `nullAddressInsideTheSetIsRejectedAtConstruction` |
| m2 | `immutableCopy` 改成 `Set.copyOf(source)` | `timeProposalPreservesIterationOrder`（★ 但注意：**3 键在 `Set.copyOf` 下实测 7%~40% 恰好落回插入序**——M2 Task 5 的实测结论。**为让 m2 稳定变红，本用例的键要么用 4~6 个（实测 0/30），要么改成多轮重复。执行者当场量一次**，别凭"看起来不像巧合"） |
| m3 | `HandlerOutcome` 去掉 `sealed` | `handlerOutcomeIsSealedWithExactlyTwoVariants` |

---

### Task 3: JSON 地基（★ 最高风险）

**Step 0（★ 先做这一步，不要跳过）**：读 **spec §13.1 第 1 条**与**本计划末节"探针结论"**——那里是 Jackson 能否直接往返三个模块状态类型的**实测结论**。
**本 Task 的 Step 5 与 Step 2~4 的 JSON 形态由该结论决定**：结论为"能"则照下面的草图；结论为"不能"则**照探针给出的修法改**，并**在 spec §13.1 与本计划末节各记一条取代说明**。

**Files:**
- New: `simos-util/src/main/java/io/mosire/simos/util/json/SimosObjectMapper.java`
- New: `simos-map/src/main/java/io/mosire/simos/map/codec/MapCodec.java`
- New: `simos-social/src/main/java/io/mosire/simos/social/codec/SocialCodec.java`
- New: `simos-unit/src/main/java/io/mosire/simos/unit/codec/UnitCodec.java`
- Modify（可能）: `simos-util/pom.xml`、`simos-core/pom.xml`（依赖，**见 Step 6**）
- Test: 三个模块各自的 `…/codec/<X>CodecTest.java`

**Interfaces:**
- Consumes: `io.mosire.simos.util.spi.ModuleCodec`（Task 2）
- Produces: `MapCodec` / `SocialCodec` / `UnitCodec`，`namespace()` 依次为 `"map"` / `"social"` / `"unit"`；`SimosObjectMapper.create()` 返回**唯一形态**的 `ObjectMapper`（三个 codec 共用同一份配置）

- [ ] **Step 1: 为什么需要 `SimosObjectMapper`（**本计划相对 spec §1.2 的增补**，写明理由）**

spec §1.2 的交付物表里没有这个类。**本计划加上它**，理由：三个 codec 分处三个模块，若各自 `new ObjectMapper()` 并各自配 feature，**配置必然漂移**——而"快照的 JSON 形态"是 **checkpoint 与重放**的共同输入，两份配置就等于**两个真相**（正是本项目最贵教训的形态）。配置本身**不含任何领域类型**，故放共享层不违反铁律 3。

★ 这条增补**要在关账时回填进 spec §1.2**，不是自行其是。

- [ ] **Step 2: `SimosObjectMapper`**

```java
package io.mosire.simos.util.json;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 全项目**唯一**的 {@code ObjectMapper} 装配点（M4 计划增补，spec §1.2 未列）。
 *
 * <p>★ 为什么要单点：三个 {@code ModuleCodec} 分处三个模块，各自 {@code new ObjectMapper()} 就等于
 * **两份配置、两个真相**——而快照 JSON 同时是 checkpoint 与重放的输入。配置里**不含任何领域类型**，
 * 故放共享层不违反铁律 3。
 *
 * <p>★ 配置的**每一项**都要有理由，不许"顺手加上"：
 *
 * <ul>
 *   <li>{@code ORDER_MAP_ENTRIES_BY_KEYS}——快照 JSON 要**逐字节可比**（对拍、diff、人读）。默认的
 *       {@code HashMap} 序不是内容的纯函数（M2 Task 5 实测），落盘结果会在两次进程间无理由地不同
 *   <li>不启用 {@code FAIL_ON_UNKNOWN_PROPERTIES} 的关闭——**保持默认的严格**：多出来的字段是**漂移信号**，
 *       静默吞掉它等于把铁律 5 的守卫拆掉一半
 * </ul>
 */
public final class SimosObjectMapper {

  private SimosObjectMapper() {}

  /** 返回一份**新**的、配置固定的 mapper（调用方不得再改它的 feature）。 */
  public static ObjectMapper create(Module... extraModules) {
    JsonMapper.Builder builder = JsonMapper.builder();
    for (Module module : extraModules) {
      builder.addModule(module); // ★ 只允许追加"键反序列化"这类模块，见下文 E 条
    }
    return builder.build();
  }

  // ★★ 原草图的 .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS) 已被实测否定，见下方长注。
  //    新签名 create(Module... extraModules) 的理由（E 条）：键反序列化器装在各领域模块自己的
  //    SimpleModule 里，因为 HexCoord/UnitId 等类型**不在 util**，util 够不着（铁律 3）。
  //    基础 feature 仍是**单一来源**（本类），只有"追加模块"这一个受控口子。
}
```

> ★★ **`ORDER_MAP_ENTRIES_BY_KEYS` 实测"不可用"——原草图的这条 feature 已删（2026-09-18 05:4x 探针实测）**
>
> 探针原话：**开启即抛** `InvalidDefinitionException: Cannot order Map entries by key of incomparable
> type io.mosire.simos.map.region.RegionId, consider disabling FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY`。
> 根因：`RegionId` / `CityId` / `PathwayId` / `UnitId` **不是 `Comparable`**（`HexCoord` / `EdgeRef` 是），
> 而 `GameMap` 的七个 Map 里就有拿它们当键的。
>
> **更关键的是：就算它不抛，也解决不了它本来要解决的问题。** 探针把跨 JVM 的字节漂移**当场 diff 定位**
> 到了 **`Region.hexes`（一个 `Set.copyOf`）的数组序**——`ORDER_MAP_ENTRIES_BY_KEYS` 管的是 **Map 的键序**，
> **根本管不到 Set 的元素序**；而 `GameMap` 的七个 Map **本来就是 `LinkedHashMap` 保序**的。
> ⇒ 这个 feature 对本树**既抛异常、又打不中靶**。
>
> **⇒ 控制器裁定**：
> 1. **不启用 `ORDER_MAP_ENTRIES_BY_KEYS`**；也**不采用**"关掉 `FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY`
>    绕过它"——那只是让一个打不中靶的 feature 开始工作。
> 2. **跨 JVM 的字节级决定论，本阶段实测"不成立"**：探针三次独立 JVM 得 **2 种字节**（run0==run1 ≠ run2）。
>    ⇒ **M4 任何测试都不许拿"快照 JSON 的字节"当断言依据**（约 1/3 概率假红或假绿）。
>    **往返用例一律断 `equals`**——探针已实测三类快照在 `Jdk8Module` + 6 个键反序列化器下 `equals=true`。
> 3. ★ **传给 Task 7（CheckpointStore）/ Task 8（Replay）/ 各判据**：需要"内容指纹"时
>    **不能直接哈希快照 JSON**，要么先规范化（排序），要么改用 `equals` 语义比较。
> 4. ★ **另记一条上游发现（M4 不修，留给关账裁决）**：`Region.hexes` 用 `Set.copyOf`，与 `CLAUDE.md`
>    形态 1 的既有教训（"保序一律 `LinkedHashMap`/`LinkedHashSet` + `unmodifiable*`"）**直接冲突**，
>    可能是 M2 遗留的真实决定论缺陷。**M4 不就地改 `simos-map`**（会动到已关账里程碑的语义与守卫），**只记录**。

> ⚠️ **`Optional` 组件：已实测，不再"等探针"**（2026-09-18 05:0x 控制器 `git grep` + 05:4x 探针实测**双向确认**）：
>
> | 位置 | 形态 |
> |---|---|
> | `simos-unit/.../Unit.java:23` | `SegmentedSeries<Optional<UnitId>> parent` |
> | `simos-unit/.../Unit.java:24` | `SegmentedSeries<Optional<HexCoord>> position` |
> | `simos-unit/.../Unit.java:29` | `Optional<Movement> movement` |
>
> 三处**都在 `UnitSnapshot → UnitState → Map<UnitId, Unit>` 之下** ⇒ **`Optional` 确实在快照树里**，而且在**嵌套泛型位置**——比"普通 `Optional` 组件"更难办。`jackson-databind` 本体不处理 `Optional`（会序列化成 `{"present":…}`，**值直接丢**）。
>
> **⇒ 计划裁定（Step 0 不再需要就此事等探针）：`simos-util/pom.xml` 加 `jackson-datatype-jdk8`。** 理由三条：
> 1. 它是 **Jackson 家族**构件、**不是领域模块** ⇒ 不违反铁律 3、不影响 ADR-1，只动"依赖白名单"这条自我约束；
> 2. 版本由**父 POM 已 import 的 `jackson-bom`** 提供（实测 `jackson.version = 2.22.2`）⇒ **不需要新增版本属性**；
> 3. 手写的替代方案要在 `SegmentedSeries<Optional<…>>` 这种**嵌套泛型位置**上工作 ⇒ 那是一份**手工对着状态类型维护的平行结构**，**正是 L1 事故（`MapDiff` 漂移）的形态**，也正是铁律 5 的由来。
>
> **★ 白名单：计划原文写错了，已当场更正（2026-09-18 05:2x，控制器实测）**——原文写「`simos-util/pom.xml` 的依赖白名单**一并加它**，否则 enforcer 会让构建失败」。**实测：`simos-util/pom.xml` 里根本没有第三方白名单**，只有一条 `bannedDependencies` 的**黑名单**（excludes 仅 `io.mosire:agentlib-mosire` + `simos-map/social/unit/core`，**无 `includes`**）。
> ⇒ **加 `jackson-datatype-jdk8` 不会让 enforcer 失败**，**不需要改 `simos-util/pom.xml` 的 enforcer 段**。
> ★ **执行者注意**：你若照着原文去找"白名单"会找不到；**找不到是对的**。这也意味着**"构建仍然全绿"不能当作"依赖加成功了"的证据**——那条 enforcer 对这个依赖**根本不发声**。
> **真正要同步的是文档**：`CLAUDE.md` 的模块表写着 UtilSimos「**仅** Jackson databind + SLF4J」——那是**约定**、不是机器强制的白名单，本裁定**修订的就是这句**。故回填目标是 **`CLAUDE.md` 模块表 + spec §二 + §〇.3 第 8 条**，**不是 pom**。
> （这条本身是"**不引用没实测过的条文**"的又一实例：计划原文把一条**惯例**写成了**门禁行为**。）
> ★ **本裁定要回填进 spec §二 与 §〇.3 第 8 条**（那里写"本 spec 给 core 的 pom 加两个 main scope 依赖"——现在多了一处：**util 加 jdk8**）。
>
> **仍未核**：`MovementState` 的 `Optional`/`OptionalLong` 是 `UnitMoves.evaluate` 的**返回值**，未必进快照树。**不影响本裁定**（`Unit` 那三处已定案）。

- [ ] **Step 3: `MapCodec`**

```java
package io.mosire.simos.map.codec;

/**
 * map 模块的 {@link ModuleCodec} 实现（spec §八）。
 *
 * <p>★ **快照与变更集的 JSON 形态是 map 自己的事**——Core 只把它当一段文本内嵌（C26）。因此本类的
 * {@code encode*} / {@code decode*} 返回/接收的是**裸 JSON 文本**，不带任何 Core 侧的信封。
 *
 * <p>★ {@link #apply} 里的 cast 发生在这里（模块自己的地盘），Core 从不 cast。
 */
public final class MapCodec implements ModuleCodec {
  // namespace()    -> "map"
  // encodeSnapshot -> mapper.writeValueAsString(snapshot)         // MapSnapshot 是 record
  // decodeSnapshot -> mapper.readValue(json, MapSnapshot.class)
  // encodeChangeSet-> mapper.writeValueAsString(changeSet)
  // decodeChangeSet-> mapper.readValue(json, MapChangeSet.class)
  // apply(cs, base, newMeta) -> new MapSnapshot(newMeta.ref(), newMeta.timestamp(),
  //                              MapChangeSet.apply((MapChangeSet) cs, ((MapSnapshot) base).map()))
  //    ★ 两层 cast 都在**模块自己的地盘**：先 (MapSnapshot) base 取 GameMap，再 (MapChangeSet) cs。
  //    ★ 已实测（spec §〇.3 第 10 条）：MapChangeSet.apply 收**具体类型**、返回 GameMap（不是 Snapshot）
  //      ⇒ 返回值必须由 codec 重新包成 MapSnapshot。三个 codec 同形。
  //    ★ 新快照的 ref/timestamp **来自 newMeta**（C28），**不是** base 的——用 base 的会得到陈旧坐标，
  //      而 spec §5.4 第 4 项会当场把它判成 Rejected。
}
```

`SocialCodec`（`SocialSnapshot` / `SocialChangeSet`）、`UnitCodec`（`UnitSnapshot` / `UnitChangeSet`）同形。
★ **`apply` 里"新快照的 `ref` / `timestamp` 怎么定"** 见 Task 12 Step 4——**那是 Core 的 Validate 会检查的事**（spec §5.4 第 4 项），此处先按"M2/M3 既有 `apply` 返回什么就用什么"落地，Task 12 再对齐。**执行者不要在这里自创 ref 语义。**

- [ ] **Step 4: 三个 codec 的往返用例（R-3 系列）**

每个模块一条**快照往返**：构造一个**非平凡**的快照 → `encodeSnapshot` → `decodeSnapshot` → `equals`。
"非平凡"的判据：**至少覆盖一个 `Optional` 组件、一个自定义键的 `Map`、一个密封接口字段**（`GameMap` 三个都有）。
★ 这三个用例是 **spec §13.1 那个风险的兑现点**——探针的结论若为"不能"，**红的就是这里**。

- [ ] **Step 5: 配置与依赖的最终形态**

按 Step 0 的探针结论落定 `SimosObjectMapper.create()` 的 feature 清单与是否需要 `jackson-datatype-jdk8`。
**改 pom 的话，`./mvnw clean verify` 必须重跑全量**（enforcer 会重新判白名单）。

- [ ] **Step 6: 日志实现（`simos-core` 的 pom）**

spec §7.3 要求关键路径发 SLF4J 日志。`slf4j-api` 经 `simos-util` 传递可见，但**实现**要显式声明，否则 M4 跑起来只有一条 "SLF4J: No SLF4J providers were found" 警告——**那是噪音，不是日志**。

`simos-core/pom.xml` 加 `log4j-core` + `log4j-slf4j2-impl`（版本由父 POM `dependencyManagement` 管，**已在**）。**scope 用 `test`**：Core 是库，日志实现的选择权归 **app 层**（M5）；但 M4 的测试要有真日志可看。★ 这条是**计划级裁定**，理由写在此处：main scope 加日志实现会让下游 app 被迫接受一个后端。

- [ ] **Step 7: 跑 + 变异自证**

```bash
./mvnw -q spotless:apply && ./mvnw clean verify
```

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | **不注册 `Jdk8Module`**（把它从三个 codec 的注册里去掉） | 三个往返用例**全部**红在**序列化期**，消息含 `Java 8 optional type ... not supported by default`（探针已实测此即裸 mapper 的失败形态）；红点必须是 `SimosTimestamp.calendarLabel` 那条链 |
| m1b | **只摘掉一个键反序列化器**（如 `HexCoord`） | map 快照往返红在**反序列化期**，消息含 `Cannot find a (Map) Key deserializer for type ... HexCoord`（探针实测原话）。★ 这条同时证明**其余 5 个注册确实各自生效**，不是"注册一个就全好了" |
| m2 | `MapCodec.apply` 里把 `(MapSnapshot) base` 改成传 `base`（即去掉 cast） | **编译不过** ⇒ **本轮作废**（这正是"按变异文件名拷入会变成编译错误"的同族陷阱；换成"cast 到错误的具体类型"来制造运行期红） |
| m3 | `FieldDelta` 的 `@JsonTypeInfo` 拿掉 | ChangeSet 往返用例红在反序列化期，消息含 `missing type id property '@class'`（探针实测原话）。★ **这条用来钉住"D 条那个决策真的落地了"** |

---

### Task 4: `WorldChangeSet` + `Envelope`（C26）+ R1

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/state/WorldChangeSet.java`
- New: `simos-core/src/main/java/io/mosire/simos/core/store/Envelope.java`
- Test: `.../core/state/WorldChangeSetTest.java`、`.../core/store/EnvelopeTest.java`、`.../core/ArchitectureGuardsTest.java`

**Interfaces:**
- Consumes: `ChangeSet`（Task 1）、`io.mosire.simos.util.spi.ModuleCodec`（Task 2）
- Produces:
  - `record WorldChangeSet(Map<String, ChangeSet> modules) implements ChangeSet` + `static WorldChangeSet empty()`
  - `Envelope`：`static ObjectNode encode(StateMeta, Map<String, String> moduleJson, String infoJson)` / `static Decoded decode(String json)`

- [ ] **Step 1: `WorldChangeSet`**

```java
package io.mosire.simos.core.state;

/**
 * 一次推进/一条命令产生的**全模块**变更集（spec §5.5）：{@code namespace → 模块自己的变更集}。
 *
 * <p>★ 它是 {@link ChangeSet} 的**首个 main 源码实现者**（此前 main 侧 0 个、test 侧 3 个，spec §十）。
 * 这条正是"util 的契约不空转"的证据。
 *
 * <p>★ **Core 不知道 value 的具体类型**——它只按 {@code namespace} 把 value 交回对应 codec。
 */
public record WorldChangeSet(Map<String, ChangeSet> modules) implements ChangeSet { /* 保序不可变拷贝 */ }
```

- [ ] **Step 2: `Envelope`——手工 `ObjectNode` 拼装（C26 的落点）**

按 spec §6.3 的形态：

```json
{ "ref": {"branch":"main","revision":100},
  "timestamp": {"tick":480,"calendarLabel":null},
  "modules": { "map": "<MapCodec.encodeSnapshot 的返回值，原样内嵌>", "social": "…", "unit": "…" },
  "info": { … } }
```

★ **`modules` 的 value 是 JSON 文本（`String`），不是嵌套对象**——这是 C26 的字面要求：Core 把模块载荷**当文本**，因此**从不反序列化模块类型**，多态反序列化的整类问题在 Core 侧不存在。
★ `info` 段 Core **可以自己序列化**（`InfoSystem` 与 `Address` 都是 **util 的类型**，不违反不透明原则）。

- [ ] **Step 3: 往返用例**

`encode` → `decode` → 各字段逐条 `equals`；**其中 `modules` 的每个 value 必须与传入的字符串 `isEqualTo`（逐字节）**——这条同时是 R11 的**同族形态**（Core 不得 trim / re-serialize / 规范化模块载荷）。

- [ ] **Step 4: R1——main 源码 `implements ChangeSet` 恰 4 个**

```java
  /** R1：`ChangeSet` 的实现者在 main 源码里**恰 4 个**（World/Map/Social/Unit）——多一个少一个都是信号。 */
  @Test
  void changeSetHasExactlyFourMainSourceImplementors() {
    List<String> hits =
        RepoSourceScan.javaFilesUnder("simos-map/src/main", "simos-social/src/main",
                "simos-unit/src/main", "simos-core/src/main")
            .stream()
            .filter(p -> RepoSourceScan.rawContent(p).contains("implements ChangeSet"))
            .map(RepoSourceScan::relative)
            .sorted()
            .toList();
    assertThat(hits).hasSize(4);
  }
```

★ **`simos-core/src/test` 自带一份 `RepoSourceScan`**（Task 1 那份在 `simos-util/src/test`，不跨模块可见——理由见"任务地图"节的两条取舍）。
★ **test 侧的 3 个玩具实现者不计入**：它们不在 main 源码里。**这条例外必须写在用例的 javadoc 里**，否则下一个人会以为漏扫了。

- [ ] **Step 5: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | 从 `SocialChangeSet` 删掉 `implements ChangeSet` | R1 的 `hasSize(4)`（★ 注意：**这会同时让它编译不过吗？——不会**，`implements` 是纯标记，删掉后其余代码照样编译。这正是它是个好变异体的理由） |
| m2 | `Envelope` 的 `encode` 里对模块 JSON 做 `trim()` | Step 3 的 `isEqualTo` 逐字节断言 |

---

### Task 5: `SqliteStore`

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/store/SqliteStore.java`
- Test: `.../core/store/SqliteStoreTest.java`

**Interfaces:**
- Consumes: 无（与 1~4 无依赖）
- Produces: `SqliteStore implements AutoCloseable`，持有一个 `Connection`、私有 `Object` 锁、`setAutoCommit(false)`；`<T> T inTransaction(SqlFunction<T>)`（`BEGIN IMMEDIATE` + `commit`/`rollback`）；建表与 PRAGMA 在打开时。

- [ ] **Step 1: 打开与建表**

逐字照 spec §3.2 + §6.1 的 DDL（**四张 `CREATE`**：`revisions` 表、两个 revisions 索引、`events` 表、两个 events 索引——共 **2 表 + 4 索引**）。`events` 的建表语句与 agentlib **一字不差**，**只多** `idx_events_correlation_id_seq`。
打开时三条 PRAGMA：`journal_mode = WAL`、`busy_timeout = 5000`、`foreign_keys = ON`。
关闭时 `PRAGMA wal_checkpoint(TRUNCATE)` 再 `close()`。

- [ ] **Step 2: 事务边界（C23）**

```java
  public <T> T inTransaction(SqlFunction<T> work) {
    synchronized (lock) {                      // ★ 私有 Object，不是 synchronized 修饰符
      try {
        try (Statement s = conn.createStatement()) {
          s.execute("BEGIN IMMEDIATE");
        }
        T result = work.apply(conn);
        conn.commit();
        return result;
      } catch (Exception e) {
        rollbackQuietly();                      // ★ 清理自身失败不得顶掉原异常
        throw new IllegalStateException("事务失败，已回滚", e);
      }
    }
  }
```

★ **三条纪律，逐条都是被踩过的**：① 锁是**私有 `Object`**（`synchronized` 修饰符会触发 SpotBugs `USO_UNSAFE_METHOD_SYNCHRONIZATION`，且静态工厂暴露实例时外部持锁者能干扰内部互斥）；② **`BEGIN IMMEDIATE`** 在事务一开始就拿写锁，不出现"读事务升级为写事务"时的 `SQLITE_BUSY` 僵局；③ **`rollbackQuietly` 自己抛异常不得顶掉原异常**（否则现场被覆盖，debug 时看到的是 rollback 的错，不是根因）。

- [ ] **Step 3: R2——外键真的生效（证明 `PRAGMA foreign_keys = ON` 不是装饰）**

插一条 `parent_branch` / `parent_revision` **指向不存在行**的 revision ⇒ **抛**。
★ **本条的判别力来源**：`PRAGMA foreign_keys` **默认是 OFF**（SQLite 的设计如此）⇒ 删掉那条 PRAGMA，本用例**必须从红变绿**。**这就是变异体 m1**。

- [ ] **Step 4: R13——事务原子性**

直接调 store，在同一事务里写一行 revision + 一条**违反 NOT NULL 的 events 行**（如 `type = null`）⇒ 抛，且 `SELECT COUNT(*) FROM revisions` **不变**（不留残行）。

- [ ] **Step 5: 跑 + 变异自证**

```bash
./mvnw -q -pl simos-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='SqliteStoreTest' test
```

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | 删掉 `PRAGMA foreign_keys = ON` | R2 用例（★ **这是本项目"护栏必须自证"的教科书形态**：一条 PRAGMA 少写，FK 就成了纯装饰，而正常路径**完全看不出来**） |
| m2 | `inTransaction` 的 catch 里去掉 `rollbackQuietly()` | R13（残行会留下） |

---

### Task 6: `Timeline`——读写与一切派生

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/timeline/RevisionRow.java`、`Timeline.java`
- Test: `.../core/timeline/TimelineTest.java`

**Interfaces:**
- Consumes: `SqliteStore`（Task 5）
- Produces:
  - `record RevisionRow(BranchId branch, RevisionId revision, Optional<StateRef> parent, SimosTimestamp timestamp, String commandId, String correlationId, String initiator, String commandType, String changesetJson)`
  - `Timeline.appendRevision(RevisionRow)` / `fork(BranchId source, RevisionId expected, BranchId newBranch)` / `head(BranchId) → Optional<RevisionId>` / `branches() → Set<BranchId>` / `row(StateRef) → Optional<RevisionRow>` / `parent(StateRef) → Optional<StateRef>` / `chainToGenesis(StateRef) → List<StateRef>` / `byCorrelation(String) → List<RevisionRow>` / **`hasCheckpoint(StateRef) → boolean`（C19 纯函数）**

- [ ] **Step 1: 建表常量与行映射**

`RevisionRow` 的 `parent` 是 `Optional<StateRef>`——创世的 `parent_branch` / `parent_revision` **均为 NULL**。

★★ **DDL 归 Task 5（`SqliteStore`），本任务不得重复建表**（控制器 2026-09-18 06:2x 补注）：
`revisions` 表的 2 表 + 4 索引由 `SqliteStore` 在**打开时**建（Task 5 Step 1 逐字照 spec §3.2 + §6.1）。
本任务的"建表常量"**只指列名/表名的共享常量与 `RevisionRow` ↔ `ResultSet` 的行映射**。
**理由**：schema 有两个来源就是**第二真相来源**（Task 6 Step 2 的 ★ 原话），而 C1 与 B3 是**两批**——
重复建表会得到"看起来一样、实际会漂移"的两份 DDL。**发现 Task 5 没建某张表/某个索引 ⇒ 回报，不要自己补。**

- [ ] **Step 2: 派生查询（spec §3.3 逐条）**

| 查询 | SQL |
|---|---|
| 分支清单 | `SELECT DISTINCT branch` |
| head(b) | `SELECT MAX(revision) WHERE branch = ?` |
| 分岔点(b) | `SELECT parent_branch, parent_revision WHERE branch = ? AND revision = 1` |
| 父链 | 沿 `parent_branch`/`parent_revision` 递归 |
| 某 correlationId 的落盘 | `SELECT * WHERE correlation_id = ?` |

★ **没有第二个真相来源可漂移**——这是「只有 `revisions` 表」这条裁定的全部价值。

- [ ] **Step 3: `hasCheckpoint`——C19 的三项判定（纯函数，不碰磁盘）**

```java
  /**
   * (b, r) 是否**应当**有 checkpoint（C19）——**纯函数，只看 revisions 表，不碰文件系统**。
   *
   * <p>三项任取其一：① r % N == 0；② (b, r) 是某分支 revision 1 的 parent（分岔强制一次）；
   * ③ (b, r) == (main, 1)（创世）。
   *
   * <p>★ 为什么要"纯函数"：checkpoint 是**纯优化**（C18），缺文件不回退失败。若存在性要看磁盘，
   * 那磁盘就成了第二真相来源 ⇒ 违反 C18。**这里判"应当有"，Task 7 判"实际有没有"。**
   */
```

- [ ] **Step 4: `fork`（spec §3.4 的五步）**

入口检查 `head(source) == expectedRevision`，不等 ⇒ 不写（`Timeline` 只写，冲突判定归 `CommandBus`）。
新行 `(b2, 1)`，parent = `(source, 100)`，**变更集 = `WorldChangeSet.empty()`**，`tick` **继承父**。

- [ ] **Step 5: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | `hasCheckpoint` 去掉第 ② 项（分岔强制） | Task 8 的 R5（重放步数上界）；**本 Task 内**则是一条"分岔点处 `hasCheckpoint == true`"的断言 |
| m2 | `head` 的 `MAX(revision)` 改成 `COUNT(*)` | 分岔后的 head 断言（`COUNT` 会把 `b2` 的 r1 与 main 的 r100 混起来——**只有当两条分支的 revision 数不同**才分叉，故**用例里必须有两条长度不同的分支**） |

---

### Task 7: `CheckpointStore`

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/store/CheckpointStore.java`
- Test: `.../core/store/CheckpointStoreTest.java`

**Interfaces:**
- Consumes: `Timeline.hasCheckpoint`（Task 6）、`Envelope`（Task 4）
- Produces: `CheckpointStore.write(StateRef, String envelopeJson)` / `read(StateRef) → Optional<String>`；构造期对 `storeDir` 做存在性检查。

- [ ] **Step 1: 路径与校验**

`<storeDir>/checkpoints/<branch>/<revision>.json`。
★ **R17**：分支名做**文件名安全校验**——不得含 `/`、`\`、`..`，不得为空 ⇒ **构造期抛**（不做校验就会**写穿 `checkpoints/` 目录**）。
★ `BranchId` 的构造**已禁空白**（M1），此处**补的是路径字符**——两层各管一段，不重复。

- [ ] **Step 2: 缺失回退（C18——**绝不因为缺文件而失败**）**

`read` 缺失 ⇒ `Optional.empty()` + **记 WARNING 日志**。**不是异常**。
★ 这条是 C18 的字面要求，也是**判据四之外**本模块最容易写错的语义：写成抛异常，重放就会在有 checkpoint 缺失时整条挂掉，而 checkpoint 本来是**纯优化**。

- [ ] **Step 3: 写时机（C24）**

**事务提交之后**（Task 13 调用）。checkpoint **绝不进事务**（§3.5）⇒ **DB 永远是唯一真相**。

- [ ] **Step 4: R3——可派生性与磁盘逐条一致**

对一批 `(b, r)`，`hasCheckpoint(ref)` 的判定与**磁盘上文件的存在性**逐条一致（**在按 C19 写完应有的 checkpoint 之后**）。
★ 这条是**跨件一致性**护栏：它同时钉住 Task 6 的纯函数与 Task 7 的写路径。

- [ ] **Step 5: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | 去掉分支名的路径字符校验 | R17 用例（`newBranch = "../evil"` ⇒ 写穿目录） |
| m2 | `read` 缺失时改抛 `IllegalStateException` | 一条"缺失回退不失败"的用例 |

---

### Task 8: `Replay`

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/store/Replay.java`
- Test: `.../core/store/ReplayTest.java`

**Interfaces:**
- Consumes: `Timeline`（6）、`CheckpointStore`（7）、`Envelope`（4）、`ModuleCodec` 表（3）
- Produces: `Replay.replay(StateRef target) → SimulationState` + **`Replay.lastReplayApplyCount()`**（供 R5 计数；★ 见 Step 2 的说明）

- [ ] **Step 1: 算法（spec §6.4 逐行）**

```
path = []
cur  = target
while !hasCheckpoint(cur):
    path.push(cur)
    cur = parent(cur)          # 可能跨分支（分岔的 parent 指向源分支）
    if cur == null: break      # 走到了创世之前（不可能，创世必有 checkpoint）
state = loadCheckpoint(cur)    # 或创世的 checkpoint
for rev in reverse(path):
    state = applyWorld(state, decodeEnvelope(rev.changeset_json))
```

★ **`applyWorld` 逐模块调 `ModuleCodec.apply`。Core 不 cast**——cast 在模块自己的 `apply` 里。

- [ ] **Step 2: R5 的计数怎么拿到**

**不引入可变静态状态**（M3 Task 12 踩过：可变静态状态会被 SpotBugs 与并发用例一起咬）。
**做法**：`replay` 返回一个 `record ReplayResult(SimulationState state, int applyCount)`，或让 `Replay` 是**每次调用新建**的短生命周期对象并记录在实例字段上。★ **执行者二选一，在取代说明里写明选了哪个与为什么**。

- [ ] **Step 3: R4——重放对拍（本任务的核心）**

同一 `(b, r)`，「**从最近 checkpoint 重放**」与「**从创世全量重放**」结果 `equals`。
★ **为什么这是必须的**：checkpoint 是**纯优化**（C18）⇒ 它**必须与全量重放等价**。不等价就说明 checkpoint 写错了、或重放路径选错了。
★ **构造"最近 checkpoint 重放"的方法**：临时把 `hasCheckpoint` 的判定改成"只在创世为真"（即**强制全量**）——**但更干净的做法是给 `Replay` 一个显式的 `fromGenesis` 开关供测试用**。**执行者选后者**（改生产代码的判定会给 R3 埋雷）。

- [ ] **Step 4: R5——步数上界 ≤ N**

任取一个 target，走到 checkpoint 前的 `apply` 次数**不超过 N（=100）**。
★ 用例里要有**跨分支**的 target（分岔后 `b2` 的某个 revision）——**这一条才真正验到 C19 第 ② 项**（分岔点必有 checkpoint）。**用例的 N 要用小值**（如 `N = 4`）才能不构造 100 条 revision 就压到边界；★ **N 是构造参数**（`CoreConfig.checkpointInterval`），不是硬编码 100——**这条设计本身就是为可测性服务的**。

- [ ] **Step 5: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | `hasCheckpoint` 去掉第 ② 项（分岔强制） | R5 的跨分支用例（步数会超出 N） |
| m2 | `loadCheckpoint` 读到的状态不施加 `path` 里的变更（直接返回 checkpoint 状态） | R4 对拍 |

---

### Task 9: `CommandRegistry` + `CommandBus` 分派 + `CommandEnvelope`

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/command/{CommandEnvelope,CommandRegistry,CommandBus,CommandResult}.java`
- Test: `.../core/command/{CommandRegistryTest,CommandBusDispatchTest}.java`

**Interfaces:**
- Consumes: `CommandHandler` / `HandlerOutcome`（Task 2）、`Timeline`（6）、`Envelope`（4）
- Produces:
  - `record CommandEnvelope(String commandId, String correlationId, String initiator, BranchId branch, RevisionId expectedRevision, String type, String payloadJson) implements Command`
  - `record AdvanceTime(BranchId branch, RevisionId expectedRevision, TimeRange range) implements Command`、`record ForkBranch(BranchId source, RevisionId expectedRevision, BranchId newBranch) implements Command`（**放 `core.command`，与信封同包**——它们是 Core 自己的封闭命令集）
  - `sealed interface CommandResult { Committed(StateRef), Rejected(String), Conflict(StateRef) }`
  - `CommandRegistry.register(CommandHandler)`（同 type 两次 ⇒ **构造期抛**）/ `byType(String) → Optional<CommandHandler>`
  - `CommandBus.submit(Command) → CommandResult`

- [ ] **Step 1: 分派（C16 的落点）**

```
submit(cmd):
  ├─ AdvanceTime     → Core 的推进管线（Task 12）
  ├─ ForkBranch      → Core 的分岔（Task 6）
  └─ CommandEnvelope → registry.byType(cmd.type())   ← 唯一按 type 找 handler 的地方
```

★ **Core 对自己的两条命令 `switch` 是允许的**（C16）：那是**封闭**集合，加一条本来就要改 Core。**禁令要保的性质是"加一个新模块不必改 Core"**，与此无关。
★ **Core 对领域载荷一律不 `switch`、不 `instanceof`——这条是硬的。**

- [ ] **Step 2: R11——不透明载荷原样转交**

替身 handler 收到的 `payloadJson` 与信封里的**逐字节相同**。
★ **构造一个有判别力的载荷**：带**首尾空白**、**键序非字典序**、**转义字符**（如 `{"name":"  a\"b  "}`）。**只测 `"{}"` 的用例对"Core 有没有 trim / re-serialize"零判别力**——M1 归纳的"纯转发型 SPI"形态 4 就在治这个。

- [ ] **Step 3: R12——同 type 注册两次 ⇒ 构造期抛**

★ **理由写在 javadoc 里**：静默覆盖会让一个模块的 handler **永远不生效**（而且不报错）。

- [ ] **Step 4: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | 分派路径上对 `payloadJson` 做 `payloadJson.trim()` | R11（★ **只测 `"{}"` 的用例不变红**——所以 Step 2 的载荷构造是本条的判别力来源） |
| m2 | `CommandRegistry` 改成静默覆盖（`map.put`） | R12 |

---

### Task 10: 乐观并发两处检查（C17）+ R7（判据三）

**Files:**
- Modify: `.../core/command/CommandBus.java`
- Test: `.../core/command/OptimisticConcurrencyTest.java`

**Interfaces:**
- Consumes: Task 9
- Produces: `CommandBus.submit` 的四步：① 入口检查 ② **handler 在锁外执行** ③ 锁内复查 ④ 提交

- [ ] **Step 1: 四步（spec §4.4 逐行）**

```
① 入口检查     head(cmd.branch) == cmd.expectedRevision() ？  否 ⇒ Conflict(current = head)
② handler     在锁外执行（C17）
③ 锁内复查     再次比对；不等 ⇒ Conflict(current = 现在的 head)
④ 提交         revision 行 + 全部事件行，同一事务
```

★ **为什么必须两处**（写进 javadoc）：只有入口检查 ⇒ 两个线程都通过、都提交，最后写的赢，**乐观并发形同虚设**；只在提交时检查 ⇒ 慢命令白跑一趟。**两处一起才既快又对。**

- [ ] **Step 2: R7——真实并发（判据三）**

两线程用**同一个** `expectedRevision` 提交，`CyclicBarrier` 让两者**都越过入口检查**，**重复 K = 50 轮，每轮恰一个 `Committed`、恰一个 `Conflict`**，且败者看到的 `current` **==** 胜者的新 ref。

★ **`CyclicBarrier` 是这条用例能成立的全部关键**：没有它，线程 A 可能在 B 进入之前就提交完了，B 在**入口检查**就被挡下——那样验的是"入口检查"，**不是"锁内复查"**。有屏障才能保证两者都已越过入口检查（C17 的原话）。
★ **替身 handler 要在屏障/闩锁上对齐**，且**执行足够慢**（几毫秒）——否则两个线程难以真重叠。**这是本任务最容易写成假绿的地方**：用例可能"通过"了，但从来没并发过。
★ **怎么确认它真的并发过**：替身 handler 里**计数"同时进入的线程数"**，断言**至少有一轮观察到 2**。★ 没有这条自证的 R7 就是装饰。

- [ ] **Step 3: 跑 + 变异自证**

```bash
./mvnw -q -pl simos-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='OptimisticConcurrencyTest' test
```

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | 去掉③锁内复查（只留①） | R7（会出现两个 `Committed`） |
| m2 | 去掉①入口检查 | 步数/`Rejected` 语义断言（★ **本变异不一定让 R7 红**——两处检查的**分工**是"快失败"与"权威"，去掉①只影响快慢。**执行者若发现 m2 存活，如实记录为"等价变体"**，这正是"没红也要问为什么没红"） |
| m3 | ②的 handler 挪进锁内 | R7 **仍应通过**（正确性不受影响），但"同时进入数"断言会暴露串行化 ⇒ ★ **这条变异是检验 Step 2 那条自证断言有没有用的唯一办法** |

---

### Task 11: 可观测性——事件类型 + `Digest` + `correlationId` 全链 + R6（判据二）

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/observe/{EventTypes,Digest}.java`
- New: `.../core/store/EventRow.java`、`.../core/store/EventStore.java`
  ★★ **控制器裁定 10（2026-09-18）：原文的"或并入 `SqliteStore`，执行者二选一"已作废。**
  `SqliteStore.java` 归 **C1 独占**，C2 **绝不许改**——Task 11 一律用**独立文件** `EventStore.java`。
  理由：C1（5~8）与 C2（9~11）**并行**，两者都往 `core/store/` 放文件；
  若 C2 选"并入"，就是**同文件冲突**。**判据**：两批产物同处 `core/store/` 但文件不相交
  ⇒ `git merge` 应当**无冲突**；**真出现冲突 = 有人越了界**，先回报，不要"解决"它。
- Test: `.../core/observe/{EventTypesTest,DigestTest}.java`、`.../core/command/CorrelationChainTest.java`

**Interfaces:**
- Consumes: Task 9
- Produces: `EventTypes` 的 8 个常量（**冻结，C20**）；`Digest.of(String) → String`（sha256 前 **16** 字节的 hex）；事件写入路径与 `correlationId` 的贯通

- [ ] **Step 1: 8 类事件类型（C20 冻结清单）**

```
simos.command.received / .rejected / .conflicted / .committed
simos.time.advance.started / .finished
simos.module.proposal
simos.timeline.conflict
```

★ **不含** `simos.revision.created`（与 `revisions` 表**完全重复**——同一事实两个来源就是漂移风险，**正是本项目最贵教训的形态**）。
★ **`simos.timeline.conflict` 是 C14 的落点**，别处无可记录。

- [ ] **Step 2: `Digest`**

`sha256` 前 **16** 字节（spec §7.1）的 hex。★ **参数摘要复用 `Digest`——不记明文**（总纲 §8.1）。

- [ ] **Step 3: R6——判据二（逐条断言，不是只数个数）**

一次成功的 `AdvanceTime`，`correlation_id = X` 的行**恰好**是：

| 表 | 行 |
|---|---|
| `events` | 1× `received` + 1× `time.advance.started` + **N×** `module.proposal` + 1× `time.advance.finished` + 1× `command.committed`（N = 参与者数） |
| `revisions` | **恰 1 行** |

⇒ 断言**类型序列逐条**（`containsExactly`），**不是** `hasSize`；两处的 correlationId **逐字节相同**。
★ **为什么"逐条"而不是"数个数"**：数个数对**顺序错**与**类型张冠李戴**零判别力（M1 归纳的形态）。
★ 「从入口追到落盘」是**一条 SQL** 的事：`WHERE correlation_id = ?`。

- [ ] **Step 4: 日志（§7.3）**

关键路径发 SLF4J 日志：命令接收/拒绝/冲突/提交、推进起止、checkpoint 写入与**缺失回退**。
★ **人读日志存在的理由**（总纲 §八原文）："便于直接 debug，**不需查库**"。

- [ ] **Step 5: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | 把某条事件的 `correlationId` 写成 `commandId`（而非信封上的） | R6（**只有当用例的 `correlationId != commandId` 时才分叉**——★ 单命令链缺省是 `correlationId = commandId`（C22），**所以用例必须显式传一个不同的 correlationId**，否则本变异恒绿。**这是本任务最容易写成假绿的地方**） |
| m2 | 把 `module.proposal` 事件的写入挪到事务外 | R6 的 `revisions` 恰 1 行 + 事件序列（进程内不易造；**若造不出，如实记为"未自证"**，不假装测过） |

---

### Task 12: 两阶段推进六步（C25）+ R9/R10/R14

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/advance/{TimeAdvance,TimeProposalResolver,AdvanceConflict}.java`
- Test: `.../core/advance/{TimeAdvanceTest,TimeProposalResolverTest}.java`

**Interfaces:**
- Consumes: Task 10、11
- Produces: `TimeAdvance.run(AdvanceTime cmd) → CommandResult`；`TimeProposalResolver.resolve(List<TimeProposal>) → Either<AdvanceConflict, WorldChangeSet>`

- [ ] **Step 1: 六步（spec §5.1 逐行）**

```
① Prepare      Core 冻结 base SimulationState + 参与者清单（按 namespace 字典序，C25）
② Propose      逐个 participant.simulate(base, range) → TimeProposal
③ Resolve      读写集相交（C14/C15）
④ Validate     机械校验（下述五项）
⑤ Commit       汇总 WorldChangeSet → 新 revision + 全部事件，一个事务
⑥ Post-commit  按 C19 写 checkpoint（若命中）+ advance.finished 事件
```

★ **② 的关键**：`simulate` 是**纯函数**——拿 base、吐提案，**不写状态**。这是"两阶段"的全部意义：**没有模块能在别人提案之前就把自己的改动落下去**。
★ **参与者清单按 namespace 字典序定序**（C25）——决定论。

- [ ] **Step 2: ③ Resolve（C14/C15 逐条）**

| 形态 | 判定 | 落点 |
|---|---|---|
| **写-写**：两个参与者声明的 `writes` 有交集 | **拒绝整次推进** | 返回 `Rejected`，**不产生任何 revision**，发 `simos.timeline.conflict`（`kind=write_write`） |
| **读-写**：甲的 `reads` 与乙的 `writes` 有交集 | **放行** | 发 `simos.timeline.conflict`（`kind=read_write`），**不拒绝** |

★ **地址列表按字典序排序**后落事件（C15）——`Set.copyOf` 的迭代序不是键集的纯函数（M2 Task 5 实测 30 次）。
★ **自交不算冲突**（同一参与者的 `reads ∩ writes` 是它自己的事）。
★ ★ **对称性**：`reads(A) ∩ writes(B)` **与** `reads(B) ∩ writes(A)` **都要查**——**只查一个方向会漏掉一半**。

- [ ] **Step 3: ④ Validate 的五项（spec §5.4）**

0. **`range.to` 必须存在** ⇒ 缺 `to` 则 `Rejected`。★ **这条是必须的**：`TimeRange.to` 在 M1 契约里是 `Optional`（为"至今为止"这类查询而生），而 `AdvanceTime` 是**写**操作，**语义上不允许开区间**。
1. 每个 proposal 的 `namespace` 有**已注册**的 `ModuleCodec`；
2. `ChangeSet`（非空时）能被该 codec **`encodeChangeSet` 成功**；
3. `codec.apply(cs, baseSnapshot)` **不抛**；
4. apply 后的快照，其 `namespace()` 与键一致、且**被填入新的 `ref` 与 `tick`**。

任一项失败 ⇒ `Rejected`，**不产生 revision**。

★ **第 4 项与 C28 是一对**：`ModuleCodec.apply` 的第三参 `newMeta` 由 **Core 在 Commit 前算好**（新 revision 的坐标 + 推进终点的 tick），codec 照它产出新快照；Validate 第 4 项随后**核对 codec 真的用了它**（`namespace()` 与键一致、`ref`/`timestamp` == `newMeta`）。⇒ 这条校验**不是装饰**：codec 若照抄 base 的坐标（最容易犯的错），第 4 项当场判 `Rejected`。
★ **这条已不再是"待回填"**——写 spec 时它是个待定项，执行期由 C28 定死了（见 Task 2 Step 3 与 Task 3 Step 3 的草图）。
★ **Core 不懂领域语义，所以 Validate 只能是机械的**——这五条**一条都不涉及"这个变更集对不对"**。

- [ ] **Step 4: R9 / R10 / R14**

| # | 断言 |
|---|---|
| R9 | 写-写 ⇒ `Rejected`，**且拒绝后 `revisions` 行数不变**（拒绝必须是**原子的**） |
| R10 | 读-写 ⇒ **放行**，且事件里的地址列表**按字典序** |
| R14 | 未注册 namespace 的 proposal ⇒ `Rejected` 且不留 revision |

★ **R9 用替身参与者**（`TimeParticipant` 的两个假实现，writes 有交集）——不必牵扯真实模块。
★ **R10 的"字典序"断言要能分叉**：喂进去的地址集合必须是**乱序**且**键数 ≥4**（M2 实测：3 键有 7%~40% 恰好落回插入序）。

- [ ] **Step 5: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | Resolve 只查 `reads(A) ∩ writes(B)` 一个方向 | R10 的对称性用例（★ **用例必须让反方向才是唯一命中的那个**——否则本变异恒绿） |
| m2 | 写-写改成"记警告并放行" | R9 |
| m3 | Validate 去掉第 0 项（`to` 可空） | 缺 `to` 的用例 |
| m4 | 冲突事件的地址列表不排序 | R10 的序断言（★ **键数 ≥4 才有判别力**） |

---

### Task 13: `CoreSimos` 装配门面

**Files:**
- New: `simos-core/src/main/java/io/mosire/simos/core/{CoreSimos,CoreConfig}.java`
- Test: `.../core/CoreSimosTest.java`

**Interfaces:**
- Consumes: Task 8、12
- Produces: `record CoreConfig(Path storeDir, int checkpointInterval, ObjectMapper mapper)`；`CoreSimos.register(ModuleCodec)` / `register(CommandHandler)` / `register(TimeParticipant)` / `submit(Command) → CommandResult` / `replay(StateRef) → SimulationState` / `close()`

- [ ] **Step 1: 装配**

`CoreSimos` 持 `SqliteStore` + `Timeline` + `CheckpointStore` + `Replay` + `CommandRegistry` + codec/handler/participant 三张表。
★ **Post-commit 只做 Core 自己的动作**（C24）：按 C19 写 checkpoint。**不引入模块钩子**——模块级派生索引（M2 的 `RegionIndex`）是**按需构建**的，**没有装配点与消费者**，引入钩子 = 又一个死代码环（**M2 `contourCacheMax` 的教训**）。

- [ ] **Step 2: R8 前半——分岔的机制面**

`submit(ForkBranch(...))` ⇒ 新分支 revision 1、parent 指回源 head、变更集为空、**tick 继承父**；② 按 C19 第二项，源 head 处**写一份 checkpoint**（写不出就记 WARNING，**不失败**）。

- [ ] **Step 3: R8 后半——判据一（端到端，Task 14 展开）**

本 Task 先落"两侧各推各的、`expectedRevision` 天然把两者隔开"的机制断言；端到端归 Task 14。

- [ ] **Step 4: 跑 + 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | `ForkBranch` 后不写源 head 的 checkpoint | Task 14 的 R8 与 Task 8 的 R5（跨分支步数上界） |
| m2 | 新分支的 revision 从 0 起（或沿用源 head 的号） | R8 的"新分支 revision 1"断言 |

---

### Task 14: 判据一——分岔端到端

**Files:**
- Test: `.../core/BranchingEndToEndTest.java`

- [ ] **Step 1: R8（判据一）**

从 `(main, r)` 分岔出 `b2`：
① 两侧**各自推进互不影响**；
② `b2` 的**父链指回 `(main, r)`**；
③ 回到 `main` 推进，`b2` 的 head 与状态**一字不变**。

★ **"一字不变"要逐值断言**（`b2` 的 head revision + 重放出的状态 `equals` 快照），**不是"看它没报错"**。

- [ ] **Step 2: 用**真实**的域命令跑（不只替身）**

分岔后两侧各跑一次 Task 16 的 `RenameUnit`（**如果 Task 16 已落地**），否则用替身 handler。★ **执行者若在 Task 16 之前跑本任务，记一条取代说明**。

- [ ] **Step 3: 变异自证**

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | `head(b)` 忽略 `branch` 参数（全局 `MAX`） | 判据一③（`main` 推进后 `b2` 的 head 会跟着变） |

---

### Task 15: 判据三——真实并发加固

**Files:**
- Modify: `.../core/command/OptimisticConcurrencyTest.java`（若 Task 10 已足，本任务只做加固）

- [ ] **Step 1: K = 50 轮 + 反例加固**

Task 10 Step 2 已落 K=50 的骨架。本任务补：
① **每轮**的 `Committed` / `Conflict` 计数（不是总计数）；
② 败者的 `current` **==** 胜者的新 ref（**逐轮**）；
③ "同时进入数 ≥ 2 至少一次"的自证断言（Task 10 Step 2 已要求，此处**复核它真的在**）。

- [ ] **Step 2: 变异自证**

★ 复用 Task 10 的 m1 / m3。**若两处都已在 Task 10 跑过，本任务不再重复跑**——"实现者自带的变异自证已经是测试，不要在外面再套一层"。

---

### Task 16: unit 侧最小真实链路（U15 乙）+ R16（判据四）

**Files:**
- New: `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java`
- New: `simos-unit/src/main/java/io/mosire/simos/unit/spi/RenameUnitHandler.java`
- Test: `.../unit/spi/{UnitTimeParticipantTest,RenameUnitHandlerTest}.java`；判据四端到端 `simos-core/src/test/java/.../core/RealmEffectEndToEndTest.java`

**Interfaces:**
- Consumes: `TimeParticipant` / `CommandHandler`（Task 2）、`UnitCodec`（Task 3）、M3 的 `UnitMoves.evaluate` / `UnitOperations`
- Produces: `UnitTimeParticipant`（`namespace() == "unit"`）、`RenameUnitHandler`（`type() == "unit.RenameUnit"`）

- [ ] **Step 1: `UnitTimeParticipant`（spec §9.1）**

**做**：对每个持有在途 `Movement` 的单位，用 `UnitMoves.evaluate`（M3 已有，**纯函数**）算到 `range.to` 的位置。

M3 的签名（**实测**，`simos-unit/.../move/UnitMoves.java:31`）：

```java
public static MovementState evaluate(Unit unit, SimosTimestamp at, GameMap map, MovementCost cost)
```

⇒ 参与者要**从 `SimulationState` 里取地图**：`state.module("map")` → `MapSnapshot` → `GameMap`。
★ **这不违反铁律 3**——`simos-unit` → `simos-map` **本来就是允许的依赖**（§二 的 DAG）。
★ **`MovementCost` 由装配注入**（与 M3「走 `GameMap` 参数显式传入，**不依赖任何地图单例**」同一口径）。

**产出**：
- 已抵达 ⇒ 提案：`position` 段写入抵达点、**清空 `movement`**
- 未抵达 ⇒ 提案：`position` 段写入**当前所在格**（**不改路线**；`NEED_REPLAN` 的判定**沿用 M3**）
- 无在途 `Movement` 的单位 ⇒ **不进变更集**（`Unchanged`）

**不做**：不改路线策略、不做 `REPLAN_EVERY_STEP`、**不碰 social**。

**读写集**：`reads` = 涉及单位的 `unit:<id>` 与其路线经过的 `map:<mapId>:hex.q_r`；`writes` = 被写单位的 `unit:<id>`。
★ **这一条是判据 ④ 之外的第二重价值**——它让 Resolve 的读写集在**真实参与者**上跑过一次，而**不只在替身上**。

- [ ] **Step 2: `RenameUnitHandler`（spec §9.2）**

选 `RenameUnit` 是因为它**最简单**（一个 `UnitId` + 一个 `String`），从而把"信封全链"这件事本身暴露成**唯一的被测对象**：

```
app/测试 构造 CommandEnvelope{type:"unit.RenameUnit", payloadJson:{"id":"u-1","name":"新名"}}
  → 总线按 type 找 handler（Core 全程只看 type 字符串）
  → RenameUnitHandler 自己反序列化 payload、调 UnitOperations.rename（M3 已有）
  → 返回 HandlerOutcome.Applied(UnitChangeSet)
  → Core 用 UnitCodec.apply 落到快照上、落一行 revision
```

它的 `TimeProposal` / 读写集**不涉及**（不是时间命令）⇒ 该路径的**读写集为空集**。

★ **已实测（2026-09-18 05:1x，控制器当场 `grep`）**：`UnitOperations` 的操作面里**确有** `rename`：

```java
public static UnitState rename(UnitState state, UnitId id, String name)   // UnitOperations.java:66
```

同批核到的还有 `create`/`reparent`/`setStrength`/`placeAt`/`planRoute`/`cancelRoute`/`disband`（共 8 项，与 M3 spec §4.6 一致）。
⇒ spec §9.2 的引用**成立**，**不需要**另选等价操作。

- [ ] **Step 3: R16——判据四（端到端）**

unit 有在途 `Movement` 时 `AdvanceTime` 到区间终点 ⇒
① 单位 `position` **真的变了**、`movement` **真的清了**（**逐值**）；
② 该状态**能从新 revision 重放出来**（**推进结果与重放结果 `equals`**）。

★ **判据 ④ 是本 spec 增补的**：总纲 M4 行的三条判据**全部可由测试替身满足**；U15 乙 的价值正在于让"**机制不空转**"也有一条判据——**否则两阶段推进有可能整条跑通而世界毫无变化**。

- [ ] **Step 4: 跑 + 变异自证**

```bash
./mvnw -q -pl simos-unit,simos-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='UnitTimeParticipantTest,RenameUnitHandlerTest,RealmEffectEndToEndTest' test
```

| 变异 | 做法 | 期望红在哪 |
|---|---|---|
| m1 | `UnitTimeParticipant` 对已抵达的单位**不清 `movement`** | R16 的"`movement` 真的清了" |
| m2 | 参与者的 `writes` 返回空集 | R10 在真实参与者上的断言（**若本 Task 没落这条，如实记为"未自证"**） |

---

### Task 17: M4 关账

- [ ] **Step 1: 全量门禁**

```bash
export JAVA_HOME="$HOME/.local/opt/jdk-21" PATH="$HOME/.local/opt/jdk-21/bin:$HOME/.local/opt/maven/bin:$PATH"
./mvnw -q spotless:apply
./mvnw clean verify 2>&1 | tee /tmp/m4-verify.log
echo "rc=$?"
grep -c 'BugInstance size is 0' /tmp/m4-verify.log
grep -cE '^\[ERROR\]' /tmp/m4-verify.log
```

**Expected**：`rc=0`、`BugInstance size is 0` **×5**（五个模块）、`ERROR` 计数与 M3 关账时持平或更少。

- [ ] **Step 2: 四条判据逐条核**

| # | 判据 | 用例 | 实测值 |
|---|---|---|---|
| ① | 时间线能分岔 | `BranchingEndToEndTest`（R8） | ☐ |
| ② | `correlationId` 全链 | `CorrelationChainTest`（R6） | ☐ |
| ③ | CONFLICT 有真实并发 | `OptimisticConcurrencyTest`（R7，K=50） | ☐ |
| ④ | 推进有新 revision + 真实领域效果 | `RealmEffectEndToEndTest`（R16） | ☐ |

★ **逐条写实测值**（不是"通过了"）——判据②要写"类型序列恰好是 …、`revisions` 恰 1 行"；判据③要写"50 轮、每轮 1 Committed / 1 Conflict"。

- [ ] **Step 3: 护栏 R1~R18 逐条点验**

**每条护栏都要有故意违规用例自证**。★ **存活/未自证的条目如实存档**（M3 的先例：Task 8 m1 等价、Task 9 m1/m3、Task 12 m3b、Task 4 m2 探针两轮存活后补序用例杀掉）——**不假装测过**。

- [ ] **Step 4: `CLAUDE.md` 状态更新**

在"当前状态"表加 M4 行（照 M2/M3 行的形制：完成数 / spec 路径 / 计划路径 / SDD 台账路径 / 四条判据逐条核过的实测值 / 关账报告路径 / **挂起项**）。
★ 同时更新 **"模块结构与依赖硬约束"表**里 `simos-core` 那行（加 `jackson-databind` + `sqlite-jdbc`）。

- [ ] **Step 5: 关账报告**

`.superpowers/sdd/2026-09-18-core-simos-plan/task-17-report.md`（**gitignore 下，以 `git add -f` 入库**），**含"我未能核实的"清单**（照 M2/M3 的先例）。

- [ ] **Step 6: SDD 台账**

`.superpowers/sdd/2026-09-18-core-simos-plan/progress.md`——逐任务裁定与跨任务约束；**台账记裁定与结论，不记取证过程**。

- [ ] **Step 7: 提交与推送**

**显式 `git add` 每个文件**（**绝不 `git add -A`**），扫 `git diff --cached`，提交，推 `origin`。

---

## 计划自审（writing-plans 的第三步，已跑）

**1. spec 覆盖**：
- §〇 的 U12~U16 与 C12~C27 逐条有落点：U12/U13→Task 2 Step 6；U14→Task 1（enforcer 已在 ADR-1 落地）；U15→Task 16；U16→Task 5/11；C12→Task 6；C13/C14/C15→Task 12；C16→Task 9；C17→Task 10；C18→Task 7；C19→Task 6 Step 3；C20→Task 11 Step 1；C21/C22→Task 9；C23→Task 5；C24→Task 13；C25→Task 12；C26→Task 4；C27→Task 1。
- §1.1 四条判据 → Task 14（①）、11（②）、10+15（③）、16（④）。
- §三~§九 逐节 → Task 4~13（§三→6/7、§四→9/10、§五→12、§六→5/7/8、§七→11、§八→2、§九→16）。
- §十 → Task 1；§十一 R1~R18 逐条落表；§十二 的 17 任务与本计划任务表**一一对应**；§13.1 → Task 3 Step 0；§13.2 挂起项**原样保留在 spec**，本计划不实现。
- **无遗漏。**

**2. 占位符扫描**：无 `TBD` / `TODO` / "类似 Task N"。
**显式标注的待定项**（不是占位符，是**有判据的决策点**）：① Task 3 Step 0 的探针结论（**有明确判据**：能往返 ⇒ 照草图，不能 ⇒ 照探针修法 + 记取代说明）；② Task 3 Step 3 的 `apply` ref/tick 语义（**Task 12 Step 3 回填**，已写明）；③ Task 8 Step 2 的计数载体（**执行者二选一 + 记理由**）；④ Task 11 的 `EventStore` 是否独立成类（**二选一 + 记理由**）；⑤ Task 16 Step 2 的 `UnitOperations.rename` 是否存在（**当场 `git grep` 核**）。

**3. 类型一致性**：`ChangeSet` 在 Task 1 变标记接口后，**Task 2 的 `TimeProposal.changeSet`、Task 4 的 `WorldChangeSet.modules` 的值类型、Task 9 的 `HandlerOutcome.Applied`** 三处都不需要抽象方法 ⇒ **一致**。★ **反过来的一条硬约束**：Task 1 之后 **`ChangeSet` 不再是 SAM**，**任何 `() -> …` 都编不过**——Task 2 Step 7 的 `emptyChangeSet()` 必须写**匿名类**，已在计划里显式标出。
`ModuleCodec` 的五个方法在 Task 3（三处实现）与 Task 8/12/13（消费）逐处一致；`TimeProposal` 的四组件在 Task 2/12/16 三处一致；`CommandResult` 的三形态在 Task 9/10/12/13 四处一致；`CoreConfig` 的三组件在 Task 3 Step 6（日志 scope）与 Task 13 Step 1 两处一致。

**4. 上游 API 核对（写完计划后当场做的，不是记忆）**：本计划写完后跑 `git grep` 逐条读了 M1/M2/M3 的真实形态，确认了：
- `ChangeSet.java:9` 的 `RevisionId baseRevision();`（**待删的那一行**）
- `RoundTripAssertions.java:33,37` 的用法与 `:22-27` 的 `assertRoundTrip` 形
- `SnapshotProtocolTest.java:26-32` 的 lambda 用例（**唯一的编译破坏**）
- `MapSnapshot` / `SocialSnapshot` / `UnitSnapshot` **均已 `implements Snapshot`**（三个模块的 Snapshot 侧**不需要改动**）
- 三个模块的 ChangeSet **均尚未 `implements ChangeSet`**（`MapChangeSet` 是 `public record MapChangeSet(`，`SocialChangeSet` / `UnitChangeSet` 同形）
- `UnitMoves.evaluate` 的真实签名（spec §9.1 已引）
**执行期补核（2026-09-18 05:1x，控制器当场 `grep`；**这一批是在 Batch A 已经动手之后跑的**，故对 Batch A 正在改的文件只作"结构性"结论、不把行号当准）：

- **`UnitOperations.rename(UnitState, UnitId, String)` 存在**（`UnitOperations.java:66`）⇒ Task 16 Step 2 的前提成立。同批核到 8 项操作面齐全。
- ★ **三个模块的 `apply` 签名一律是「收具体 ChangeSet 类型、返回模块状态类型」**，**不是返回 `Snapshot`**：

  ```java
  public static GameMap    MapChangeSet.apply(MapChangeSet cs, GameMap base)          // MapChangeSet.java:80
  public static SocialData SocialChangeSet.apply(SocialChangeSet cs, SocialData base) // SocialChangeSet.java:32
  public static UnitState  UnitChangeSet.apply(UnitChangeSet cs, UnitState base)      // UnitChangeSet.java:32
  ```

  ⇒ **直接影响 Task 3 的 codec 写法**：`ModuleCodec.apply(ChangeSet, Snapshot)` 的实现**必须自己 cast 两层**——先 `(MapSnapshot) base` 取出 `GameMap`，再 `(MapChangeSet) cs`，然后把 `MapChangeSet.apply(...)` 的**返回值重新包成 `MapSnapshot`**。计划 Task 3 Step 3 的草图已是这个形态，**此处只是把它从"推测"升为"实测"**。
  ★ **另一条推论**：`XChangeSet.apply` 返回的是**模块状态类型**，**它不带 `ref` / `timestamp`** ⇒ 「新快照的 ref/tick 从哪来」**完全由模块 codec 决定**，Core 只能**事后校验**（Task 12 Step 3 的第 4 项）。**这正是 spec §5.4 第 4 项存在的理由。**

**⚠️ 一条方法论教训（写在这里，因为它是本计划写作期真实发生的）**：上表里 "`SocialChangeSet` / `UnitChangeSet` **尚未** `implements ChangeSet`" 那句，**在写下的 10 分钟后就被 Batch A 改掉了**（实测：两文件从 19 行变 22 行、并长出了 `implements ChangeSet`）。⇒ **agent 正在同一棵树里干活时，任何"当前状态"的实测结论都有保鲜期**；把它们当依据前**先重跑一次**，别让一句过期的"实测"变成下游的"我记得是这样"。

**仍未核实的（如实标注）**：`MovementState` 的 `Optional`/`OptionalLong` 是否进快照树（**不影响任何裁定**）；`GameMap` 的 5 个 record 键类型各自的 `toString`/`parse` 形态（**探针在处理**）。

---

## 探针结论（Task 3 Step 0 的输入）

> 本节由 **Jackson 往返探针**（2026-09-18 04:2x 派出，`general-purpose`）的实测结论回填。
> **探针跑完之前本节为空**——**不许先写结论**（纪律：「不写没实测过的期望输出」）。
> 探针的原始输出在 `.superpowers/sdd/2026-09-18-core-simos-plan/probe-jackson.md`。

**状态**：✅ **探针已完成**（2026-09-18 04:2x 派出 → 05:4x 回报，84 次工具调用）。
原始日志 270 行已留档 `.superpowers/sdd/2026-09-18-core-simos-plan/probe-jackson.md`。
**探针已自清现场**：`ScratchJacksonProbe.java` 已删、`simos-core/pom.xml` 的临时依赖已精确还原
（控制器复核 `git status`：`simos-core/` 下 **0 条**）。

### 探针的**一句话结论**（原文）

> **裸 `new ObjectMapper()` 三个快照一个都往返不了**：map/social/unit 全部**死在序列化阶段的 `Optional`**
> （`SimosTimestamp.calendarLabel`）；`GameMap` 本体能序列化、反序列化**死在 record 键**
> （`Cannot find a (Map) Key deserializer`）。`Jdk8Module` + 6 个 `KeyDeserializer`
> （**均在 codec 侧注册、零主源码改动**）后，三类快照**全部 `equals=true` 往返通过**；
> 但**跨 JVM 字节级决定论不成立**（3 次 JVM 得 2 种字节，`Set.copyOf` 所致）；
> `FieldDelta` 必须改主源码加 `@JsonTypeInfo`（实测 `activateDefaultTyping(NON_FINAL)` 修不了它）。

| 快照 | 裸序列化 | 补救后（`Jdk8Module` + 6 键反序列化器） |
|---|---|---|
| `MapSnapshot` | ❌ `Java 8 optional type ... not supported by default` | ✅ 2709B、`equals=true` |
| `SocialSnapshot` | ❌ 同上 | ✅ 384B、`equals=true` |
| `UnitSnapshot` | ❌ 同上（嵌套泛型 `SegmentedSeries<Optional<…>>` 也在树内） | ✅ 1009B、`equals=true` |

**失败清单（探针去重后 5 条，全部有实测异常文本）**：
① `Optional`/`OptionalLong`（序列化期）→ 加 `Jdk8Module`，**不改主源码**；
② record 键 Map 反序列化 → codec 侧 6 行 `addKeyDeserializer`，**不改主源码**；
③ sealed interface `FieldDelta`（`activateDefaultTyping` 修不了）→ **必须改主源码**；
④ `SegmentedSeries.addition`（`BinaryOperator` lambda）：**序列化静默写成 `{}`、反序列化抛** —— **未解决**；
⑤ `ORDER_MAP_ENTRIES_BY_KEYS` 开启即抛（见 Task 3 Step 2 的长注，已裁定**不用**）。
★ 实测附注：四个模块 main 源码当前 **0 条 Jackson 注解**（探针 `git grep` 实测）——
**③ 的"注解方案"必然打破这一点**，这正是 D 条要求执行者"显式选并写理由"的原因。

**★ 探针明确列出"我未能核实的"6 条**（照抄，**不许当成已结论**）：
1. `@JsonTypeInfo(use=Id.CLASS)` vs `(Id.NAME, property="@class")` **哪个能往返——未测**；
   `DefaultTyping.EVERYTHING` 同未测 ⇒ **Task 3 执行者必须自测并记录**。
2. 关掉 `FAIL_ON_ORDER_MAP_BY_INCOMPARABLE_KEY` 后的字节稳定性——未测（且对 `Set.copyOf` 数组序理论上无效）。
3. `@JsonCreator` 加在 6 个 `parse` 上 vs codec 侧 `KeyDeserializer` 是否等效——只测了后者。
4. `SegmentedSeries.addition` **非 null**（含 `ADD` 事件）的往返方案——**未测**，只测出"静默劣化 + 抛"。
5. 大区域/多键下 `Set.copyOf` 撞槽的漂移**概率**——未测（**2 格的小区域已实测漂移 1/3 次**）。
6. C26"不透明载荷"能否把多态挡在 Core 外——源码侧核实三快照树**不含** `FieldDelta`，
   但"Core 存储层实际如何反序列化载荷"属 M4 未落码部分，**未测**。

**★ 探针关于 `~/.m2` 的移交事实（要害，与裁定 6/7/8 直接相关）**：它 `install` 了四个 simos 构件
（`-pl` 四模块 + `-Dmaven.test.skip=true`，**跳了 spotless/checkstyle/spotbugs/enforcer**），
jar mtime **05:12:08**，`simos-util` jar md5 `4529f2aa344a4b2c4dfaae1ee8564146`。
它自查后说：**"无法证明当时 main 不含 mutant"**，建议**关账前用绿色源码重跑一次 `install` 覆盖**。
⇒ **采纳该建议，记为 Task 17 的一条动作**。（控制器 05:2x 的 `javap` 抽查只见 `ChangeSet` 一例为正确形态，
**不足以覆盖全部类**——探针这条自我保留是对的。）
★ 探针另报：当时 `./mvnw -pl simos-core -am` 会红在 `RoundTripAssertionsTest` 引用旧方法名
`assertSnapshotRoundTrip`——那是 **Batch A 在途的 main/test 短暂不同步**，非探针造成。

### 控制器当场实测（2026-09-18 05:2x）——探针跑完前**已确证**的部分

> 以下**不是**探针结论，是控制器**自己跑过**的：`cat` 三个源文件 + 探针 run0 日志。
> 标"探针"的才是探针的实测。**两者都标了出处**，不许混。

**A. `FieldDelta` 的确切形态**（`simos-util/.../state/FieldDelta.java`，控制器 `cat` 实测）：
**sealed interface，四个 record 变体**——`Unchanged<T>()`、`Upsert<T>(Map<String,T> entries)`、
`Remove<T>(Set<String> keys)`、`Patch<T>(Upsert<T> upserts, Remove<T> removals)`。
⇒ **`Patch` 嵌套了另外两个变体**，且**全体带泛型参数 `T`**。

**B.（探针 run0 实测）** `FieldDelta` 两个变体**序列化能出字节、反序列化必失败**：
`SER_OK|FieldDelta-Unchanged|len=2`（即 `{}`）、`SER_OK|FieldDelta-Upsert|len=82`，
两条 `DESER_FAIL` 都是 `InvalidTypeIdException: missing type id property '@class'`。
⇒ **④ 的答案：sealed interface 必须加类型信息，裸往返不可能。**

**C.（探针 run0 实测）** `KeyDeserializers` 注册 `HexCoord/EdgeRef/RegionId/CityId/PathwayId/UnitId` 六个后，
`map` 快照 `SER_OK|len=3503` + `DESER_OK|equals=true` ⇒ **`GameMap` 原样往返成功**（本任务最大风险已解除）。

**D. ⇒ Task 3 新增一个**原文没写的**决策点（执行者必须显式选并写理由）**：
类型信息加在**哪里**？两条路——
1. **直接在 `FieldDelta` 上加 `@JsonTypeInfo` / `@JsonSubTypes`**（`simos-util` 本来就有 `jackson-databind`）——
   简单，但**状态类型从此带 Jackson 注解**；
2. **用 Jackson mixin**（`SimosObjectMapper` 也在 `simos-util`，够得着 `FieldDelta`）——状态类型保持纯净，
   但**泛型 sealed interface 的 mixin**更绕，且**"mixin 忘了注册"是一个静默失效**（退化成今天的 `missing type id`）。
★ 另有一条**已实测的坑**：`Upsert` / `Remove` 的 compact 构造器**对空集合抛 `IllegalArgumentException`**
（源码里 `if (entries.isEmpty()) throw`），而 `Unchanged` 恰恰序列化成 `{}` ——
**加了 `@JsonTypeInfo` 之后两者才区分得开**；若沿用"靠 `{}` 判空"的写法，`Unchanged` 会在**反序列化时抛异常**而非静默。
**这条要在 Step 4 的往返用例里有对应用例**（`Unchanged` 与 `Remove` 各一条）。

**E. ⇒ Task 3 的第二个新增决策点（原文没预见，控制器 05:3x 实测）：六个键类型的 `KeyDeserializer` 不能放在 `SimosObjectMapper` 里。**

实测它们的**归属模块**（`git grep -l "record <T>("`）：

| 键类型 | 所在模块 |
|---|---|
| `HexCoord` / `EdgeRef` / `RegionId` / `CityId` / `PathwayId` | **`simos-map`**（5 个） |
| `UnitId` | **`simos-unit`** |

**六个全在领域模块里，`simos-util` 一个都没有**（各自都有 `static <T> parse(String)`，实测 6/6 命中）。

**⇒ 后果**：`SimosObjectMapper` 在 `simos-util`，**它看不见 `HexCoord`/`UnitId`**——
**铁律 3 + `bannedDependencies` 都禁止它引用**。所以 Step 2 那句"**三个 codec 共用同一份配置**"
**不能理解成"键反序列化也在共享层统一注册"**：那一行代码**写不出来**。

**⇒ 正确形态**（Step 2 要照此落地，并把理由写进 javadoc）：
`SimosObjectMapper` 只提供**共享的基座配置**（feature 清单），**并开一个受控的口子**让各 codec 追加
**自己的** `SimpleModule`（只装键反序列化器）——建议 `create(Module... extra)` 或 `create().copy()` 后注册，
**哪一种由执行者定并写理由**。要点是：**基础 feature 单一来源**（防漂移，这是本 Step 的原意），
**而键反序列化天然是各模块自己的事**（因为类型就在它自己家里）。
★ 探针是**在 test scope 里注册**才绕过去的（`simos-core` 的 test classpath 看得见三个领域模块）——
**test scope 能做的事，main scope 的 `simos-util` 做不了**，别照抄探针的写法。
**这条要回填 spec §1.2 与本 Task 的 Step 2。**

---

## 执行期取代说明汇总（Task 1~17 关账时回填）

> M4 执行期发现并处置的计划缺陷/取值校正，目前只在各 brief/report/台账里；本节是**索引**——只记"计划原文在哪、取代后写成什么、详情在哪"，**不重写、不删除任何草图原文**。
> 出处三件套：`task-N-brief.md`（派单扫描结论）+ `task-N-report.md`（实测处置）+ 台账 `progress.md`（关账裁定），均在 `.superpowers/sdd/2026-09-18-core-simos-plan/` 下。

| 任务 | 计划原文所在 | 取代后写法（一句） | 详情出处 |
|---|---|---|---|
| （待回填） | | | |
