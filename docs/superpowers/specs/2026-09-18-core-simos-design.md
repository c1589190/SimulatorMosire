# CoreSimos 模块设计（M4 spec）

> **权威层级**：总纲 `2026-09-16-simos-master-design.md` > **ADR-1 `2026-09-18-spi-layering-design.md`** > 本 spec > 实现计划。
> ADR-1 修订了总纲 §3.1 的最后一条；本 spec 不重复其论证，只在受影响处标注「见 ADR-1 §x」。
> 本 spec 里"**冻结**"= 实现、夹具与测试都必须照此；计划的代码草图若与本 spec 冲突，**以本 spec 为准**。
>
> **写作纪律**：本文每个数字都有当场跑过的出处；**读文档/推导得出的**（而非跑出来的）单独标注。

## 〇 裁决记录

### 〇.1 用户裁定

2026-09-18（延续 ADR-1 的 U12~U14，见 `2026-09-18-spi-layering-design.md` §〇.1）：

| # | 待决项 | 裁定 |
|---|---|---|
| U12 | 契约层是否拆出 `simos-spi` | **不拆**。不新建模块、不搬包 |
| U13 | 契约面的稳定性 | **一旦成型即稳定**；后续要动 = 大版本更新 |
| U14 | Core 的依赖 | main scope **只依赖共享层** |

2026-09-18（本 spec 新增，动笔前裁决）：

| # | 待决项 | 裁定 |
|---|---|---|
| U15 | M4 的域侧深度 | **乙：机制 + unit 最小真实链路**——Core 机制全量交付，另加 `UnitTimeParticipant`（在途 `Movement` 推进到区间终点、写回 `position`）与**一条**域命令走信封全链。理由是"机制不空转"需要一条真实证据，且它把 M3 §8.3 的挂起项接上；unit 的其余 7 条命令留待需要时再补 |
| U16 | 事件日志与 revisions 的落点 | **甲：自持一个库，`revisions` + `events` 同库同事务**。代价是自写 store（约 150 行）；买到的是"落盘"与"留痕"不可能不一致。**不复用** `agentlib.event.SqliteEventStore` |

### 〇.2 控制器裁定（可在评审时推翻）

| # | 项 | 裁定与理由 |
|---|---|---|
| C12 | `RevisionId` 的编号 | **分支内序号，从 1 起**。尊重 M1 已冻结的契约语义（`BranchId` 的 javadoc：「`RevisionId` 只在分支内有意义」）。代价是主键成了 `(branch, revision)` 复合键、父指针要两列；买到的是不推翻一条已冻结的契约 |
| C13 | 分岔是不是一条 revision | **是**。`ForkBranch` 在新分支上产生 **revision 1**，`parent` 指向源分支当时的 head，**变更集为空**（`WorldChangeSet.empty()`）、模拟时刻继承父。⇒ **DAG 只有一种边**，重放只有一条路径。若不是 revision，源 head 就无处记录（"只有 revisions 表"），该信息会丢 |
| C14 | Resolve 的冲突判定 | **写-写 ⇒ 拒绝整次推进**（且**不得留下 revision**）；**读-写 ⇒ 记一条 `simos.timeline.conflict` 警告事件并放行**。理由：所有 proposal 都从同一份 base 算出，读-写重叠在语义上是"某个模块读了别人正要写的东西"，但两阶段模型下无法靠排序修复，只能报告；一律拒绝会把整条时间线卡死 |
| C15 | 读写集的形态 | **canonical 地址字符串的精确集合**（v1 不做前缀包含——前缀会引入地址语义，而 Core 不懂地址语义）。冲突报告**按字典序排序**后落事件：`Set.copyOf` 的迭代序不是键集的纯函数（M2 Task 5 的教训，30 次实测），排序才能让事件逐字节可比 |
| C16 | Core 对自己两条命令用 `switch` | **允许**。ADR-1 §七 的"不 `switch` 类型、不 `instanceof`"**限定在领域载荷上**——Core 自己的命令集是**封闭**的，加一条本来就要改 Core。禁令要保的性质是"加一个新模块不必改 Core"，与此无关。**Core 对领域载荷一律不 `switch`、不 `instanceof`，这条是硬的** |
| C17 | 乐观并发的检查点 | **两处**：① 入口检查（快速失败）；② **提交时在锁内复查（权威）**。**handler 在锁外执行**——否则一个慢 handler 会阻塞全部写。这也是判据三能用**真实并发**验的原因：替身 handler 在屏障上对齐时，两线程都已越过入口检查，胜负由锁内复查决出 |
| C18 | checkpoint 的地位 | **纯优化**。文件缺失**不导致失败**：回退到更早的 checkpoint，最坏从创世重放，记一条 WARNING。⇒ `revisions` 表是唯一真相，这一点不因文件系统而破 |
| C19 | checkpoint 的可派生性 | **存在性完全由 `revisions` 表派生**（不另建表、不另存索引）：`(b, r)` 有 checkpoint ⟺ `r % N == 0` **或** `(b, r)` 是某分支 revision 1 的 parent **或** `(b, r) == (main, 1)`（创世）。第二项正是"分岔强制一次"的落点，同时也是"重放上界 ≤ N"的保证 |
| C20 | 事件类型的冻结清单 | 照总纲 §8.2，**减去** `simos.revision.created`（与 `revisions` 表**完全重复**——同一事实两个来源就是漂移风险，正是本项目最贵教训的形态），**加上** `simos.timeline.conflict`（C14 的落点，别处无可记录） |
| C21 | 发起者身份的形态 | `String initiator`，约定形态 `<kind>:<id>`（`player:local` / `agent:a-17` / `mcp:session-3` / `script:import`）。**不设枚举**——枚举会随接入方增长而每次都要改，而这列只需要"可区分 + 可显示" |
| C22 | `correlationId` 的形态与来源 | `String`（UUID 文本）。**由调用方在信封上给出**；单命令链缺省 `correlationId = commandId`，多命令链由调用方显式传同一个 |
| C23 | SQLite 的并发模型 | 照 agentlib 的形态：**一个连接 + 全部公开方法走同一把私有锁**（用私有 `Object` 而非 `synchronized` 修饰符——后者会触发 SpotBugs `USO_UNSAFE_METHOD_SYNCHRONIZATION`，且实例经静态工厂暴露时外部持锁者能干扰内部互斥）。事务：`setAutoCommit(false)` + `BEGIN IMMEDIATE` + 显式 `commit()`/`rollback()` |
| C24 | Post-commit 要不要模块钩子 | **不要**。总纲 §六 的"⑥ 索引刷新"在 M4 落成 **Core 自己的动作**（按 C19 写 checkpoint）。模块级派生索引（M2 的 `RegionIndex`）是**按需构建**的，没有装配点与消费者——引入钩子 = 又一个死代码环（M2 `contourCacheMax` 的教训） |
| C25 | Prepare 的具体内容 | **Core 侧动作，不设模块契约**：① 冻结本次推进的 base `SimulationState`；② 冻结参与者清单，**按 namespace 字典序**定序（决定论）。"准备"若不需要模块参与，就不该凭空发明一个 `prepare()` 契约 |
| C26 | 模块载荷如何进出 Core | Core **用 `ObjectNode` 亲手拼装/拆解信封**，模块载荷一律以 **JSON 文本（`String`）**进出，Core **从不**反射或反序列化模块类型。⇒ 多态反序列化的整类问题在 Core 侧不存在 |
| C27 | `assertSnapshotRoundTrip` 删掉后，它守的性质怎么办 | 它守的是"变更集必须相对于它被施加的那个 base"。**该性质在 M4 从测试断言升级为结构不变量**：revision 的 `parent_revision` 指针**就是**那个 base，`apply` 只能拿父行指向的变更集作用在父行重建出的状态上。⇒ 不是"丢了一条护栏"，是"护栏换了更强的形态" |
| **C28** | **`ModuleCodec.apply` 的签名**（**执行期裁定，2026-09-18**，写 spec 时未预见） | **加第三个参数**：`Snapshot apply(ChangeSet cs, Snapshot base, StateMeta newMeta)`。**原二参形态不可满足**——实测三个模块的 `apply` 一律是「收具体 ChangeSet、返回**模块状态类型**」（`GameMap` / `SocialData` / `UnitState`，见 §〇.3 第 10 条），**不带 `ref` / `timestamp`**；而 §5.4 第 4 项要求 `apply` 后的快照**必须带上新的 `ref` 与 `tick`**。二参下 codec 无从知道新 revision ⇒ 只能照抄 `base` 的（**陈旧且错**）或凭空造一个。**第三个参数用 M1 既有的 `StateMeta(StateRef, SimosTimestamp)`**——不新造类型，且 Core 本来就要算它。**代价**：契约面在 M4 落地时即定型（U13），故**必须在写第一个 codec 之前定**，否则三个 codec 全部返工 |

### 〇.3 偏离清单（相对总纲 / ADR-1，逐条给理由）

1. **补 `BranchId` 组件到 Core 的两条命令上**（ADR-1 §七 的形状漏了"哪条分支"）：
   ```java
   record AdvanceTime(BranchId branch, RevisionId expectedRevision, TimeRange range) implements Command
   record ForkBranch (BranchId source, RevisionId expectedRevision, BranchId newBranch) implements Command
   ```
   ADR-1 写形状时把 `branch` 放在了信封里，而这两条**不走信封**（C16）⇒ 必须自带。这是**澄清**不是推翻：ADR-1 §七 论证的是"领域命令以不透明载荷跨边界"，那一条原样成立。
2. **不复用 `agentlib.event.SqliteEventStore`**（总纲 §10.5 曾把它列为可直接复用，U16 甲）：它的写路径是**单条 INSERT、autocommit、不暴露事务边界**，而 M4 判据二要求「一条命令从入口追到落盘」——`revisions` 行与它的全部事件行**必须同一个事务**，否则进程在两步之间死掉会留下"有 revision 无事件"或反过来的残迹。**表结构照抄**（列名、索引、WAL、busy_timeout、C23 的锁形态），差的只是事务边界。
3. **`events` 表加一条 `correlation_id` 索引**：agentlib 的 `idx_events_type_correlation_id_seq` 是 `(type, correlation_id, seq)`，`type` 是**前导列** ⇒ **服务不了"只按 correlationId 查"**，而判据二正是这个查询。补 `idx_events_correlation_id_seq ON events (correlation_id, seq)`。
4. **`simos.revision.created` 不单独立事件**（C20）。
5. **不引入 Post-commit 模块钩子**（C24）。
6. **Prepare 不设模块契约**（C25）。
7. **不做只读模式 / 不做 schema 迁移机制**：agentlib 的只读两级退化（`mode=ro` / `PRAGMA query_only`）是为"审计别进程正在写的库"而生的，M4 **没有这个消费者**；迁移靠 `IF NOT EXISTS` 的幂等 DDL（照 agentlib），**不引入 `user_version`**。按纪律"没有消费者的东西是死代码"。
8. **core 的 pom 显式声明 `jackson-databind` 与 `sqlite-jdbc`**：虽然 jackson 可经 `simos-util` 传递得来，但**依赖传递不是契约**。两者都是共享层构件，**不违反 ADR-1**（禁的是领域模块）。版本由父 POM 的 `dependencyManagement` 提供，`sqlite-jdbc` 取 **3.53.4.0**——与 agentlib 一致，避免 classpath 上出现两份 SQLite native。
9. ★ **`simos-util` 的 pom 加 `jackson-datatype-jdk8`**（**执行期裁定，2026-09-18**，写 spec 时未预见）：
   **由来**——本 spec §13.1 第 1 条把"Jackson 能否直接往返"标成未实测项，当时只想到 `Optional` 是个风险。执行期当场 `git grep` 三个模块 main 源码，**实测确证** `Optional` 确实在快照树里，而且在**嵌套泛型位置**：
   | 位置 | 形态 |
   |---|---|
   | `simos-unit/.../Unit.java:23` | `SegmentedSeries<Optional<UnitId>> parent` |
   | `simos-unit/.../Unit.java:24` | `SegmentedSeries<Optional<HexCoord>> position` |
   | `simos-unit/.../Unit.java:29` | `Optional<Movement> movement` |
   三处都在 `UnitSnapshot → UnitState → Map<UnitId, Unit>` 之下。`jackson-databind` **本体不处理 `Optional`**（会序列化成 `{"present":…}`，**值直接丢**）。
   **裁定：加 `jackson-datatype-jdk8`**，理由三条：① 它是 **Jackson 家族**构件、**不是领域模块** ⇒ 不违反铁律 3、不影响 ADR-1，只动"依赖白名单"这条自我约束；② 版本由**父 POM 已 import 的 `jackson-bom`** 提供（`jackson.version = 2.22.2`）⇒ 不需新增版本属性；③ 手写替代方案要在 `SegmentedSeries<Optional<…>>` 这种**嵌套泛型位置**工作 ⇒ 那是一份**手工对着状态类型维护的平行结构**，**正是 L1 事故（`MapDiff` 漂移）的形态**，也正是铁律 5 的由来。
   **代价（2026-09-18 修正）**：★ **本节原写"`simos-util` 的依赖白名单与它的 `bannedDependencies` 要一并改，否则 enforcer 让构建失败"——该句是错的，已撤**。
   控制器当场实测：`simos-util/pom.xml:52` 的 enforcer 只有 `<bannedDependencies>` 的 `<excludes>`，**没有 `<includes>`** ⇒ 它是一份**黑名单**、不是白名单，**加 Jackson 家族构件不会触发它，enforcer 块一个字都不用改**。
   **实证**：`ce98212` 只往 `<dependencies>` 里加了 `jackson-datatype-jdk8`（main scope，`simos-util/pom.xml:28`）、未碰 enforcer，其后 `simos-util` 的 verify 实测 **BUILD SUCCESS**（Task 3 落地的 532 用例全绿、`BugInstance size is 0` ×4）。
   ⇒ 要同步的**只有 `CLAUDE.md` 的模块表**（已改），**没有第二个真相来源**。
   ★ **这一句的教训本身就是 CLAUDE.md 形态 5 的禁忌**：它把**推导出来的风险**（"加依赖当然要改白名单"）当成了既成事实写进 spec，而当时的 spec 草案里并没有人跑过 enforcer。
   **仍未核**：`MovementState` 的 `Optional`/`OptionalLong` 是 `UnitMoves.evaluate` 的**返回值**，是否进快照树——不影响本裁定。
10. ★ **三个模块的 `apply` 一律「收具体 ChangeSet、返回模块状态类型」**（**执行期实测**，2026-09-18 05:1x）：

    ```java
    public static GameMap    MapChangeSet.apply(MapChangeSet cs, GameMap base)           // MapChangeSet.java:80
    public static SocialData SocialChangeSet.apply(SocialChangeSet cs, SocialData base)  // SocialChangeSet.java:32
    public static UnitState  UnitChangeSet.apply(UnitChangeSet cs, UnitState base)       // UnitChangeSet.java:32
    ```

    **这直接推出 C28**：返回值**不带 `ref` / `timestamp`** ⇒ `ModuleCodec.apply` 的第三个参数（`StateMeta newMeta`）是**必需**的，不是"顺手加一个"。

### 〇.4 不做清单（照总纲，明确划界）

- **不做 GUI / MCP / AgentBinding / AgentLib 权限集成**（总纲 §5.4 的第 6~9 项）——全是 **M5**。
- **不做 GSimap 导入器**（M6）。
- **不做 Facet 的装配点**（M3 §〇.4 已留白；M4 的注册时机**不足以**支撑它——`unitsHere` 的消费者是查询层，属 M5）。
- **不做 unit 的其余 7 条命令**（U15 乙只带 1 条）。
- **不做 social 的时间参与者**：`SocialData` 的人口是 `PopulationSeries` 的**纯函数**（`populationAt(t)` 从 anchor 现算，M3 C1），随时间推进**状态里没有任何东西改变** ⇒ 它**不需要**时间参与者。这一条是 M3 设计的直接推论，不是遗漏。
- **不做 `LOCK_ROUTE` / `REPLAN_EVERY_STEP` 路线策略**（M3 §〇.3 第 4 条：没有消费者的枚举是死代码）。
- **不做模块级派生索引的刷新钩子**（C24）。

---

## 一 判据与交付物

### 1.1 判据（总纲 §11 / 主计划 §二 M4 行，逐条给落点）

| # | 判据 | 落点 |
|---|---|---|
| ① | **时间线能分岔** | §3.4 + 护栏 R8：从 `(main, r)` 分岔出 `b2`，两侧各自推进互不影响；`b2` 的父链指回 `(main, r)`；回到 `main` 推进，`b2` 的 head 与状态**一字不变** |
| ② | **`correlationId` 能一条命令从入口追到落盘** | §7.2 + 护栏 R6：一次 `AdvanceTime` 后，`events WHERE correlation_id=?` 的**类型序列逐条断言**（不是只数个数），且 `revisions WHERE correlation_id=?` **恰 1 行**，两处的 correlationId 逐字节相同 |
| ③ | **CONFLICT 有真实并发用例** | §4.4 + 护栏 R7：两线程用**同一个** `expectedRevision` 提交，`CyclicBarrier` 让两者都越过入口检查，**重复 K 轮，每轮恰一个 `Committed`、恰一个 `Conflict`**，且败者看到的 `current` == 胜者的新 ref |
| ④ | **（U15 增补）「推进 → 新 revision」有真实领域效果** | §9.1 + 护栏 R16：unit 有在途 `Movement` 时 `AdvanceTime` 到区间终点 ⇒ 单位 `position` 真的变了、`movement` 真的清了，且该状态**能从新 revision 重放出来**（推进结果与重放结果 `equals`） |

> 判据 ④ 是本 spec 增补的。总纲 M4 行的三条判据全部可由**测试替身**满足；U15 乙 的价值正在于让"机制不空转"也有一条判据——否则两阶段推进有可能整条跑通而世界毫无变化。

### 1.2 交付物

| 模块 | 交付 |
|---|---|
| simos-util | `state.ChangeSet` **收窄为标记接口**（去掉 `baseRevision()`）；删 `verify.RoundTripAssertions.assertSnapshotRoundTrip`；**新增 `io.mosire.simos.util.spi`**：`CommandHandler` / `HandlerOutcome` / `ModuleCodec` / `TimeParticipant` / `TimeProposal` + `package-info`（U13 的书面落点） |
| simos-map | `MapChangeSet implements ChangeSet`（**字段与测试零变化**） |
| simos-social | `SocialChangeSet implements ChangeSet`（同上） |
| simos-unit | `UnitChangeSet implements ChangeSet`（同上）；**新增** `UnitTimeParticipant`（§9.1）与 `RenameUnit` 的 `CommandHandler`（§9.2） |
| simos-core | `state.WorldChangeSet`（`util.state.ChangeSet` 的**首个真实现者**）；`command.*`（`CommandEnvelope` / `CommandBus` / `CommandRegistry` / `CommandResult`）；`timeline.*`（`Timeline` / `RevisionRow` / `ForkOutcome`）；`advance.*`（`TimeAdvance` 六步 / `AdvanceConflict` / `TimeProposalResolver`）；`store.*`（`SqliteStore` / `CheckpointStore` / `Replay`）；`CoreSimos`（装配门面）+ `CoreConfig` |

---

## 二 边界与依赖

```
UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos
```

- **ADR-1 已生效**：`simos-core` 的 main scope 只有 `simos-util` + `agentlib-mosire`；map/social/unit 在 **test scope**，且由 `enforce-core-boundaries` 在构建期钉住（含故意违规自证，已绿）。
- **本 spec 给 core 的 pom 加两个 main scope 依赖**：`jackson-databind`、`sqlite-jdbc`（〇.3 第 8 条）。两者都**不是领域模块** ⇒ `bannedDependencies` 与 ADR-1 均不受影响，**不需要改 enforcer 块**。
- ★ **`simos-util` 的 pom 加 `jackson-datatype-jdk8`**（2026-09-18 执行期裁定，〇.3 第 9 条）：它同样是 Jackson 家族构件、**不是领域模块** ⇒ ADR-1 不受影响。★ **本行原接着写"白名单本身要同步改，否则 enforcer 会让构建失败"——据实测撤回**：`simos-util` 的 enforcer 只有 `bannedDependencies` 的**黑名单**（无 `includes`），加构件**不触发它**；修正与实证见 〇.3 第 9 条。
- Core **不 import 任何 `io.mosire.simos.{map,social,unit}`**——这条由构建期强制，不由 reviewer 盯。
- 三个模块**不依赖 core**（各自的 `bannedDependencies` 已就位，M3 现状）。
- 跨模块可见性（"某 hex 上有哪些单位"）本轮**仍不落地**（§〇.4）。

---

## 三 时间线 DAG

### 3.1 坐标与编号（冻结）

- **坐标** `StateRef(BranchId branch, RevisionId revision)`——M1 已冻结，不改。
- **`revision` 是分支内序号，从 1 起**（C12）。
- **`(main, 1)` 是创世**：`parent_branch` / `parent_revision` 均为 NULL，变更集**为空**，其状态由**必有的一份 checkpoint** 承载（C19）。
- **分岔**（C13）：`b2` 的 **revision 1** 的 parent 指向源分支当时的 head；这条 revision 的变更集为空，模拟时刻继承父。⇒ **它不是"世界变了"，是"时间线多了一条边"**。
- **head(b)** = `SELECT MAX(revision) FROM revisions WHERE branch = b`。

### 3.2 `revisions` 表 schema（冻结）

```sql
CREATE TABLE IF NOT EXISTS revisions (
  branch          TEXT    NOT NULL,
  revision        INTEGER NOT NULL,
  parent_branch   TEXT,                       -- 创世为 NULL
  parent_revision INTEGER,                    -- 创世为 NULL
  tick            INTEGER NOT NULL,           -- 模拟时刻（SimosTimestamp.tick）
  calendar_label  TEXT,                       -- 可空（SimosTimestamp.calendarLabel）
  command_id      TEXT    NOT NULL,
  correlation_id  TEXT    NOT NULL,
  initiator       TEXT    NOT NULL,           -- 形态 <kind>:<id>（C21）
  command_type    TEXT    NOT NULL,           -- "core.AdvanceTime" / "core.ForkBranch" / "unit.RenameUnit"
  changeset_json  TEXT    NOT NULL,           -- 信封（C26），模块载荷是其中的一段文本
  PRIMARY KEY (branch, revision),
  FOREIGN KEY (parent_branch, parent_revision) REFERENCES revisions (branch, revision)
);

CREATE INDEX IF NOT EXISTS idx_revisions_correlation
    ON revisions (correlation_id, branch, revision);
CREATE INDEX IF NOT EXISTS idx_revisions_parent
    ON revisions (parent_branch, parent_revision);
```

**`PRAGMA foreign_keys = ON` 必须显式打开**——SQLite **默认关闭**外键，不开的话上面那条 FK 是**装饰**（这正是本项目"护栏必须自证"要防的形态，故列入 R2 变异自证）。

### 3.3 一切派生

| 查询 | 来源 |
|---|---|
| 分支清单 | `SELECT DISTINCT branch` |
| head(b) | `MAX(revision) WHERE branch = b` |
| 分岔点(b) | `SELECT parent_branch, parent_revision WHERE branch = b AND revision = 1` |
| 父链 | `parent_branch` / `parent_revision` 递归 |
| 某 (b, r) 是否有 checkpoint | **纯函数**，见 C19 的三项判定 |
| 某 correlationId 的落盘 | `SELECT * WHERE correlation_id = ?` |

> **没有第二来源可漂移。** 这是「只有 `revisions` 表」这条裁定的全部价值。

### 3.4 `ForkBranch` 的语义（冻结）

```
ForkBranch(source=main, expectedRevision=100, newBranch="b2")
  ① 入口检查：head(source) == expectedRevision ⇒ 否则 CONFLICT{ current: (source, head) }
  ② 锁内复查（C17）②
  ③ 新行：(b2, 1)，parent=(main, 100)，变更集 = WorldChangeSet.empty()，tick 继承 (main,100)
  ④ 按 C19 第二项，⇒ (main, 100) 必须有一份 checkpoint（写不出就记 WARNING，不失败）
  ⑤ 同事务落一行 revision + `received`/`committed` 两条事件
```

**分岔不改变世界，只增加一条边。** 两个分支此后**各推各的**，`expectedRevision` 天然把两者隔开（`b2` 的命令带 `(b2, k)`，`main` 的命令带 `(main, m)`）。

### 3.5 Checkpoint（C18 / C19）

- **内容**：该 `(b, r)` 的**完整** `SimulationState`（§6.3 的信封 JSON）。
- **位置**：`<storeDir>/checkpoints/<branch>/<revision>.json`。分支名做**文件名安全校验**（不得含 `/`、`\`、`..`；`BranchId` 的构造已禁空白，此处补路径字符校验）。
- **周期**：`N`（默认 **100**），由 `CoreConfig` 给出。
- **写入时机**：**事务提交之后**。checkpoint 是派生物，**绝不进事务** ⇒ DB 永远是唯一真相。
- **缺失**：回退到更早的 checkpoint，最坏从创世重放；记 WARNING。**绝不因为缺文件而失败**（C18）。

---

## 四 Command Bus

### 4.1 三种命令形状（C16）

```java
// ── 信封：领域命令过边界的形式（Core 拥有） ──
record CommandEnvelope(String commandId, String correlationId, String initiator,
                       BranchId branch, RevisionId expectedRevision,
                       String type,            // "unit.RenameUnit"
                       String payloadJson)     // 不透明：Core 只当字符串
    implements Command { }

// ── Core 自己的两条（ADR-1 §七 + 本 spec 补的 branch） ──
record AdvanceTime(BranchId branch, RevisionId expectedRevision, TimeRange range) implements Command
record ForkBranch (BranchId source, RevisionId expectedRevision, BranchId newBranch) implements Command
```

`Command`（util，M1 冻结）只有 `expectedRevision()`；三个 record 的访问器天然满足它。

### 4.2 分派（C16 的落点）

```
submit(cmd):
  ├─ AdvanceTime  → Core 的推进管线（§五）
  ├─ ForkBranch   → Core 的分岔（§3.4）
  └─ CommandEnvelope → registry.byType(cmd.type())   ← 唯一按 type 找 handler 的地方
```

**Core 对自己的两条命令 `switch` 是允许的**（C16）：那是 Core 自己的**封闭**集合。
**Core 对领域载荷一律不 `switch`、不 `instanceof`**——它手里只有 `type` 字符串与 `payloadJson` 文本。

**`payloadJson` 必须原样转交**：Core 不得 trim、不得 re-serialize、不得做任何规范化（M1 归纳的"纯转发型 SPI"形态，列入 R11 变异自证）。

### 4.3 注册表

```java
// util.spi —— 模块实现，app 层装配
interface CommandHandler {
  String type();                                          // "unit.RenameUnit"
  HandlerOutcome handle(SimulationState state, String payloadJson);
}
sealed interface HandlerOutcome {
  record Applied(ChangeSet changeSet) implements HandlerOutcome { }
  record Rejected(String reason)     implements HandlerOutcome { }
}
```

同一 `type` 注册两次 ⇒ **构造期抛**（静默覆盖会让一个模块的 handler 永远不生效）。

### 4.4 乐观并发（C17）

```
① 入口检查     head(cmd.branch) == cmd.expectedRevision() ？  否 ⇒ Conflict(current = head)
② handler     在锁外执行（C17）
③ 锁内复查     再次比对；不等 ⇒ Conflict(current = 现在的 head)
④ 提交         revision 行 + 全部事件行，同一事务
```

```java
sealed interface CommandResult {
  record Committed(StateRef ref)      implements CommandResult { }   // 新 revision 的坐标
  record Rejected(String reason)      implements CommandResult { }
  record Conflict(StateRef current)   implements CommandResult { }   // 真实 head（含 branch）
}
```

**为什么必须两处**（C17）：只有入口检查 ⇒ 两个线程都通过、都提交，最后写的赢，**乐观并发形同虚设**；只在提交时检查 ⇒ 慢命令白跑一趟。两处一起才既快又对，且**判据三因此可以用真实并发验**。

---

## 五 两阶段时间推进

### 5.1 六步（冻结）

```
AdvanceTime(branch, expectedRevision, [from, to))
  ① Prepare      Core 冻结 base SimulationState + 参与者清单（按 namespace 字典序，C25）
  ② Propose      逐个 participant.simulate(base, range) → TimeProposal
                 ★ 每个参与者拿到的都是**同一份** base；顺序不影响结果
  ③ Resolve      读写集相交（C14/C15）
  ④ Validate     机械校验（下述）
  ⑤ Commit       汇总 WorldChangeSet → 新 revision + 全部事件，一个事务
  ⑥ Post-commit  按 C19 写 checkpoint（若命中）+ advance.finished 事件
```

**② 的关键**：`simulate` 是**纯函数**——拿 base、吐提案，**不写状态**。这是"两阶段"的全部意义：没有模块能在别人提案之前就把自己的改动落下去。

### 5.2 `TimeProposal`（冻结）

```java
// util.spi
record TimeProposal(String namespace, ChangeSet changeSet,
                    Set<String> reads, Set<String> writes) { /* 防御性拷贝 */ }

interface TimeParticipant {
  String namespace();
  TimeProposal simulate(SimulationState state, TimeRange range);
}
```

- `reads` / `writes` 是 **canonical 地址字符串**（C15）。
- `TimeProposal` **是** `ChangeSet` 的候选形态（总纲 §六）：模块自己算出、尚未跨模块解算、尚未校验、尚未提交。**它是提案，不是事实。**

### 5.3 Resolve 的冲突判定（冻结，C14/C15）

| 形态 | 判定 | 落点 |
|---|---|---|
| **写-写**：两个参与者声明的 `writes` 有交集 | **拒绝整次推进** | 返回 `Rejected`，**不产生任何 revision**，发一条 `simos.timeline.conflict`（`kind=write_write`） |
| **读-写**：甲的 `reads` 与乙的 `writes` 有交集 | **放行** | 发一条 `simos.timeline.conflict`（`kind=read_write`），**不拒绝** |

- **地址列表按字典序排序**后落事件（C15）。
- 自交不算冲突（同一参与者的 `reads ∩ writes` 是它自己的事）。
- **对称性**：`reads(A) ∩ writes(B)` 与 `reads(B) ∩ writes(A)` 都要查——只查一个方向会漏掉一半。

### 5.4 Validate 的五项（C25 的推论，机械校验）

Core 不懂领域语义，所以 Validate 只能是机械的：

0. **`range.to` 必须存在**——无上界的推进**无法落成一条 revision**（revision 的 `tick` 是确定值）。
   缺 `to` ⇒ `Rejected`。★ 这条是**必须**的：`TimeRange.to` 在 M1 契约里是 `Optional`（为"至今为止"这类查询而生），
   而 `AdvanceTime` 是**写**操作，语义上不允许开区间。
1. 每个 proposal 的 `namespace` 有**已注册**的 `ModuleCodec`；
2. `ChangeSet`（非空时）能被该 codec **`encodeChangeSet` 成功**（即"能被落盘"）；
3. `codec.apply(cs, baseSnapshot)` **不抛**；
4. apply 后的快照，其 `namespace()` 与键一致、且**被填入新的 `ref` 与 `tick`**（`Snapshot` 接口上有这三个访问器 ⇒ Core 查得到）。

任一项失败 ⇒ `Rejected`，**不产生 revision**。

### 5.5 Commit 与 Post-commit

- Commit：`WorldChangeSet` = `{namespace → 模块自己的 ChangeSet}`；一个事务里落 **1 行 revision + N 行事件**。
- Post-commit：按 C19 判定是否写 checkpoint；发 `advance.finished`。**两件事都在事务之外**——它们失败不影响已经落盘的事实。

---

## 六 存储

### 6.1 一个库、两张表（U16 甲）

`revisions`（§3.2）+ `events`（建表语句与 agentlib **一字不差**，`IF NOT EXISTS` 幂等 DDL）：

```sql
CREATE TABLE IF NOT EXISTS events (
  seq            INTEGER PRIMARY KEY AUTOINCREMENT,
  ts             TEXT    NOT NULL,
  type           TEXT    NOT NULL,
  agent          TEXT    NOT NULL,           -- 存 initiator 原文（C21）
  payload        TEXT    NOT NULL DEFAULT '',
  correlation_id TEXT    NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_events_type_correlation_id_seq
    ON events (type, correlation_id, seq);
CREATE INDEX IF NOT EXISTS idx_events_correlation_id_seq        -- ★ 本 spec 补，见 〇.3 第 3 条
    ON events (correlation_id, seq);
```

打开时：`PRAGMA journal_mode = WAL`、`PRAGMA busy_timeout = 5000`、`PRAGMA foreign_keys = ON`。
关闭时：`PRAGMA wal_checkpoint(TRUNCATE)` 再 `Connection.close()`——进程退出后不留 `-wal` 文件。

### 6.2 事务边界（C23）

```java
// ★ 保持 autoCommit 的出厂值不动，事务边界一律用**显式 SQL** 驱动（见下方修正）
try (Statement s = conn.createStatement()) { s.execute("BEGIN IMMEDIATE"); }
  ... INSERT revisions ... INSERT events ...
try (Statement s = conn.createStatement()) { s.execute("COMMIT"); }
// 任何异常 ⇒ s.execute("ROLLBACK")，且清理自身失败不得顶掉原异常
```

`BEGIN IMMEDIATE` 在事务一开始就拿写锁 ⇒ 不出现"读事务升级为写事务"时的 `SQLITE_BUSY` 僵局。单连接 + 私有锁下这是冗余的，但冗余的方向是安全。

★★ **修正（2026-09-18 执行期实测，Task 5 当场探针，/tmp 不进仓库）——本节原先的 `setAutoCommit(false)` + `BEGIN IMMEDIATE` 组合在 sqlite-jdbc 3.53.4.0 上根本跑不起来**：

| 探针 | 做法 | 实测结果 |
|---|---|---|
| 1 | `setAutoCommit(false)` 后执行 `BEGIN IMMEDIATE` | `[SQLITE_ERROR] (cannot start a transaction within a transaction)` |
| 2 | 保持 autoCommit 出厂值（true） | 显式 `BEGIN IMMEDIATE` / `COMMIT` / `ROLLBACK` **可行**；而 `conn.commit()` / `conn.rollback()` 抛 `database in auto-commit mode` |

**根因**：该驱动在 `autoCommit=false` 时**由驱动自己开事务**（字节码可见 `DB.execute(sql, autoCommit)` 传标 + `JDBC3Connection.tryEnforceTransactionMode()`）⇒ 再手写 `BEGIN` 就成了"事务里开事务"。

**落地**：保持 autoCommit 出厂值不动，用显式 SQL 驱动事务边界。**C23 的语义（单事务、写锁前置、显式边界、异常即回滚）一样不少**，变的只是 JDBC 层的手段；类 Javadoc 里已写明。⇒ **本节是"手段"的权威不再是唯一来源，`SqliteStore` 的类 Javadoc 与实现才是。**

### 6.3 快照 JSON（C26）

**信封由 Core 用 `ObjectNode` 手工拼装**，模块载荷是其中的**一段文本**：

```json
{ "ref": {"branch":"main","revision":100},
  "timestamp": {"tick":480,"calendarLabel":null},
  "modules": { "map": "<MapCodec.encodeSnapshot 的返回值，原样内嵌>",
               "social": "…", "unit": "…" },
  "info": { … } }
```

⇒ **Core 从不反序列化模块类型**，多态反序列化的整类问题在 Core 侧不存在。模块的 JSON 形态是**各模块自己的事**。

**`info` 段**：`InfoSystem` 与 `Address` 都是 **util 的类型**，Core 可以自己序列化（不违反不透明原则——那不是领域类型）。

### 6.4 重放

```
replay(branch, target):
  path = []
  cur  = (branch, target)
  while !hasCheckpoint(cur):            # C19 的三项判定
      path.push(cur)
      cur = parent(cur)                 # 可能跨分支（分岔的 parent 指向源分支）
      if cur == null: break             # 走到了创世之前（不可能，创世必有 checkpoint）
  state = loadCheckpoint(cur)           # 或创世的 checkpoint
  for rev in reverse(path):
      state = applyWorld(state, decodeEnvelope(rev.changeset_json))
  return state
```

- `applyWorld` 逐模块调 `ModuleCodec.apply`。**Core 不 cast**——cast 在模块自己的 `apply` 里。
- **重放步数上界 ≤ N**：走到 checkpoints 每 N 条一次（同分支内），而跨分支的第一步落在分岔点上，那个点按 C19 第二项**必有**一份（§3.4 ④）。

---

## 七 可观测性

### 7.1 事件类型（冻结，C20）

```
simos.command.received
simos.command.rejected
simos.command.conflicted
simos.command.committed
simos.time.advance.started
simos.time.advance.finished
simos.module.proposal            （每模块每次推进一条）
simos.timeline.conflict          （C14 的落点；payload: {kind, namespaces, addresses}）
```

**不含** `simos.revision.created`（与 `revisions` 表完全重复，C20 / 〇.3 第 4 条）。

`payload` 一律 JSON 文本；**参数摘要复用 `Digest`（sha256 前 16 字节）——不记明文**（总纲 §8.1）。

### 7.2 `correlationId` 全链（判据二的落点）

一次成功的 `AdvanceTime`，`correlation_id = X` 的行**恰好**是：

| 表 | 行 |
|---|---|
| `events` | 1× `command.received` + 1× `time.advance.started` + N× `module.proposal` + 1× `time.advance.finished` + 1× `command.committed`（N = 参与者数） |
| `revisions` | **恰 1 行** |

⇒ **"从入口追到落盘"是一条 SQL 的事**：`WHERE correlation_id = ?`。注入 `commandId` / `initiator` / `expectedRevision` 都在同一行的不同列上。

### 7.3 日志（人读）

关键路径另发 SLF4J 日志（"人类可读日志，便于直接 debug，不需查库"，总纲 §八）：命令接收/拒绝/冲突/提交、推进起止、checkpoint 写入与**缺失回退**。

---

## 八 `util.spi`：Core 与模块之间的三个契约（ADR-1 C9）

**位置冻结**：`io.mosire.simos.util.spi`。**既有契约原地不动**（ADR-1 C9）。

```java
// 1. 命令处理：模块把自己的领域命令接上总线
interface CommandHandler { String type(); HandlerOutcome handle(SimulationState state, String payloadJson); }

// 2. 状态编解码与施加：Core 与模块之间唯一触碰状态形状的地方
interface ModuleCodec {
  String namespace();
  ChangeSet decodeChangeSet(String json);   String encodeChangeSet(ChangeSet cs);
  Snapshot  decodeSnapshot(String json);    String  encodeSnapshot(Snapshot s);
  Snapshot  apply(ChangeSet cs, Snapshot base, StateMeta newMeta);  // 模块自己 cast，Core 不 cast（★ C28）
}

// 3. 时间参与者：两阶段推进的 proposer
interface TimeParticipant { String namespace(); TimeProposal simulate(SimulationState state, TimeRange range); }
```

`package-info.java` 是 **U13「契约面一旦成型即稳定；后续要动 = 大版本更新」的书面落点**：写明这条约定、写明三个接口各自的稳定性承诺、写明"新增契约放本包、既有契约原地不动"。

---

## 九 域侧最小真实链路（U15 乙）

### 9.1 `UnitTimeParticipant`（simos-unit）

**做**：对每个持有在途 `Movement` 的单位，用 `UnitMoves.evaluate`（M3 已有，纯函数）算到 `range.to` 的位置。

M3 的签名（实测，`simos-unit/.../move/UnitMoves.java:31`）：

````java
public static MovementState evaluate(Unit unit, SimosTimestamp at, GameMap map, MovementCost cost)
````

⇒ 参与者要**从 `SimulationState` 里取地图**：`state.module("map")` → `MapSnapshot` → `GameMap`。
**这不违反铁律 3**——`simos-unit` → `simos-map` 本来就是允许的依赖（§二 的 DAG）。
`MovementCost` 由装配注入（与 M3「走 `GameMap` 参数显式传入，不依赖任何地图单例」同一口径）。

**产出**：

- 已抵达 ⇒ 提案：`position` 段写入抵达点、清空 `movement`
- 未抵达 ⇒ 提案：`position` 段写入当前所在格（**不改路线**；`NEED_REPLAN` 的判定沿用 M3）
- 无在途 `Movement` 的单位 ⇒ **不进变更集**（`Unchanged`）

**不做**：不改路线策略、不做 `REPLAN_EVERY_STEP`、不碰 social。

**读写集**：`reads` = 涉及单位的 `unit:<id>` 与其路线经过的 `map:<mapId>:hex.q_r`；`writes` = 被写单位的 `unit:<id>`。**这一条是判据 ④ 之外的第二重价值**——它让 Resolve 的读写集在**真实参与者**上跑过一次，而不只在替身上。

### 9.2 一条域命令走信封全链（simos-unit）

`RenameUnit`——选它是因为它**最简单**（一个 `UnitId` + 一个 `String`），从而把"信封全链"这件事本身暴露成唯一的被测对象：

```
app/测试 构造 CommandEnvelope{type:"unit.RenameUnit", payloadJson:{"id":"u-1","name":"新名"}}
  → 总线按 type 找 handler（Core 全程只看 type 字符串）
  → UnitModuleHandler 自己反序列化 payload、调 UnitOperations.rename（M3 已有）
  → 返回 HandlerOutcome.Applied(UnitChangeSet)
  → Core 用 UnitCodec.apply 落到快照上、落一行 revision
```

它的 `TimeProposal`/读写集**不涉及**（不是时间命令）⇒ 该路径的读写集为空集。

---

## 十 契约收敛（M4 对既有代码的改动，全部是 U 裁定的落实）

**实测爆炸半径**（`git grep -n baseRevision -- '*.java'`，2026-09-18 当场跑，共 **14 处**）：

| 位置 | 收窄后 | 处置 |
|---|---|---|
| `state/ChangeSet.java:9` | 被删的就是这一行 | 删 |
| `verify/RoundTripAssertions.java:33,37` | `assertSnapshotRoundTrip` 的方法体 | 随方法删 |
| **`state/SnapshotProtocolTest.java:29-30`** | **编不过** | **改写**（见下） |
| `verify/RoundTripAssertionsTest.java`（6 处） | **照样编译** | 只留下过时注释 |
| `verify/RoundTripAssertionsDriftTest.java`（3 处） | **照样编译** | 同上 |

**为什么第三行才是唯一的编译破坏**：`SnapshotProtocolTest` 里写的是

````java
ChangeSet changeSet = () -> new RevisionId(2);        // ← lambda 实现
assertThat(changeSet.baseRevision()).isEqualTo(new RevisionId(2));
````

`ChangeSet` 一旦没有抽象方法就**不再是 SAM**，lambda 当场编不过；`.baseRevision()` 也随之消失。
**而这个用例断言的就是「ChangeSet 是单方法接口」这件事本身**——它不是被误伤，是**必须随裁定一起改写**：
改成钉住新契约（`ChangeSet` 无抽象方法、`Command` 仍是 SAM），即护栏 **R18**，可变异自证。

**为什么后两行照样编译**：那三个测试 record 各带一个 `baseRevision` **record 组件**，而它们的 `apply` 签名收的是**具体类型**（`PlainChangeSet` / `ToyChangeSet` / `DriftingChangeSet`）——`changeSet.baseRevision()` 解析到的是 **record 访问器**，不是接口方法。⇒ **接口收窄对它们是透明的。**

> 这条清单本身是一次纠错：spec 初稿写的是「实测：`git grep baseRevision` 只命中那一处」——**那是没跑就写的**。
> 跑出来 14 处，测试侧 3 个实现者与 2 个受影响文件都不在原判断里。按纪律「「我验过了」与「我记得是这样」必须分开」，纠正过程记在此处。

| # | 改动 | 说明 |
|---|---|---|
| 1 | `util.state.ChangeSet` **收窄为标记接口** | 去掉 `baseRevision()`；爆炸半径见上表 |
| 2 | `WorldChangeSet implements ChangeSet` | util 的 `ChangeSet` **首次获得 main 源码里的真实现者**（此前 main 侧 **0** 个、test 侧 **3** 个——ADR-1 §2.3 那句「0 个实现者」说的是前者，本 spec 补全口径） |
| 3 | `MapChangeSet` / `SocialChangeSet` / `UnitChangeSet` 各加 `implements ChangeSet` | **字段与测试零变化**（U 裁定原话） |
| 4 | 删 `RoundTripAssertions.assertSnapshotRoundTrip` | 连带删 `RoundTripAssertionsTest` 里它的三个用例；`assertRoundTrip` 与其余用例**不动**。该守卫的性质改由 §3.2 的 parent 指针承担（C27） |

**护栏**：R1 钉住 **main 源码里** `implements ChangeSet` 的实现者恰 4 个（World/Map/Social/Unit）；R15 钉住 `assertSnapshotRoundTrip` 全仓 0 处；**R18** 钉住 `ChangeSet` 无抽象方法、`Command` 仍是 SAM。

---

## 十一 护栏清单（每条都要 G13 变异自证）

| # | 护栏（用例） | 形态 |
|---|---|---|
| R1 | **main 源码里** `implements ChangeSet` 的实现者**恰 4 个**（World/Map/Social/Unit）；test 侧另有 3 个玩具实现者，**不计入** | 扫描 + 计数 |
| R2 | `revisions` 的父指针完整性：**插一条 parent 不存在的行 ⇒ 抛**（证明 `PRAGMA foreign_keys = ON` 真的生效，不是装饰） | 负向（DB） |
| R3 | checkpoint 的**可派生性**：对一批 `(b, r)`，`hasCheckpoint` 的判定与磁盘上文件的存在性**逐条一致** | 跨件一致性 |
| R4 | **重放对拍**：同一 `(b, r)`，「从最近 checkpoint 重放」与「从创世全量重放」结果 `equals` | 对拍（★ checkpoint 是纯优化 ⇒ 它必须与全量重放等价） |
| R5 | 重放步数 **≤ N**：任取一个 target，走到 checkpoint 前的 `apply` 次数不超过 N | 计数 |
| R6 | **判据二**：一条命令的 `correlationId` 在两张表上的**类型序列逐条断言** + `revisions` 恰 1 行 | 逐条断言 |
| R7 | **判据三**：真实并发 K 轮（K=50），每轮恰一 `Committed` 恰一 `Conflict`，且 `Conflict.current == Committed.ref` | 并发 + 计数 |
| R8 | **判据一**：分岔后两侧独立；父链指回 `(main, r)`；`main` 再推进不影响 `b2` | 行为 |
| R9 | **写-写 ⇒ 拒绝，且不留 revision**（拒绝必须是原子的：拒绝后 `revisions` 行数不变） | 负向 + 计数 |
| R10 | **读-写 ⇒ 放行**，且事件里的地址列表**按字典序** | 行为 + 序 |
| R11 | **不透明载荷原样转交**：替身 handler 收到的 `payloadJson` 与信封里的**逐字节相同** | 转发（M1 形态 4） |
| R12 | `CommandHandler` 同 `type` 注册两次 ⇒ **构造期抛** | 负向（构造） |
| R13 | **事务原子性**：直接调 store 写一行 revision + 一条**违反 NOT NULL 的事件** ⇒ 抛，且 `revisions` **不留残行** | 负向（DB） |
| R14 | 未注册 namespace 的 proposal ⇒ `Rejected` 且不留 revision | 负向 |
| R15 | 全仓 `assertSnapshotRoundTrip` **0 处** | 扫描 + 计数 |
| R16 | **判据四**：`AdvanceTime` 推进后位置真的变、`movement` 真的清，且**重放结果与原状态 `equals`** | 逐值 + 对拍 |
| R17 | checkpoint 路径的**分支名安全校验**：含 `/`、`\`、`..` 或空的分支名 ⇒ **构造期抛**（`BranchId` 已禁空白，此处补路径字符；不做校验就会写穿 `checkpoints/` 目录） | 负向（构造） |
| R18 | `util.state.ChangeSet` **无抽象方法**（标记接口）、`Command` **仍是 SAM**（恰 1 个抽象方法） | 反射 + 计数 |

**口径**（M1/M2 归纳，形态清单是权威）：变异体必须与原件**字节不同**、按**白名单推成目标类名**、每轮恢复干净世界、断言 `COMPILATION ERROR` 计数为 0、红点必须落在被保护的那一行上；红了要问"为什么红"，没红要问"为什么没红"。
**还原基准一律写成"回到开跑前的工作树状态"**，不得写成 `git checkout -- <file>`（未提交的改动会被它整个删掉，之后的绿就是假绿——2026-09-18 ADR-1 的变异轮上踩到过，见该次提交信息）。

---

## 十二 任务分解草案（交给 writing-plans 展开成 bite-sized）

| # | 任务 | 依赖 |
|---|---|---|
| 1 | 契约收敛 §十：`ChangeSet` 收窄 + 三模块 `implements` + 删 `assertSnapshotRoundTrip`（连带其 3 个用例）+ **改写 `SnapshotProtocolTest` 那个 lambda 用例** + R1/R15/R18 | — |
| 2 | `util.spi` 五类型 + `package-info`（U13 书面落点） | 1 |
| 3 | **JSON 地基**：`CoreConfig` 的 `ObjectMapper` 装配 + 三模块的 `ModuleCodec` 实现 + 快照往返（**本任务的实测结论见 §十三 第 1 条**） | 2 |
| 4 | `WorldChangeSet` + 信封的 `ObjectNode` 拼装/拆解（C26）+ 往返 | 3 |
| 5 | `SqliteStore`：建表、`PRAGMA`、`BEGIN IMMEDIATE` 事务、私有锁 + R2/R13 | — |
| 6 | 时间线读写：`appendRevision` / `fork` / `head` / `父链` / 派生查询 + `hasCheckpoint`(C19) | 5 |
| 7 | `CheckpointStore`（路径字符校验 + 写/读/缺失回退）+ R3/R17 | 6 |
| 8 | `Replay` + R4/R5 | 7 |
| 9 | `CommandRegistry` + `CommandBus` 分派（C16）+ `CommandEnvelope` + R11/R12 | 4, 6 |
| 10 | 乐观并发两处检查（C17）+ `CommandResult` 三形态 | 9 |
| 11 | 可观测性：事件类型冻结 + `Digest` 摘要 + `correlationId` 全链 + R6 | 9 |
| 12 | 两阶段推进六步（C25）+ `Resolve` 读写集（C14/C15）+ R9/R10/R14 | 10, 11 |
| 13 | `CoreSimos` 装配门面（注册 codec/handler/participant）+ Post-commit 写 checkpoint（C24） | 8, 12 |
| 14 | 判据一：分岔端到端 + R8 | 13 |
| 15 | 判据三：真实并发 + R7 | 10 |
| 16 | unit 侧最小真实链路：`UnitTimeParticipant` + `RenameUnit` handler + R16 | 13 |
| 17 | M4 关账：`./mvnw clean verify` + 四条判据逐条核 + `CLAUDE.md` 状态更新 + 报告 | 全部 |

**顺序说明**：任务 3 是**风险最高的一个**（§十三 第 1 条），故排在很靠前——它的结论会反过来影响 4/7/8 的形态，晚做等于返工。任务 5 与 1~4 **无依赖**，可并行。

---

## 十三 挂起项与未自证项

### 13.1 本 spec 写作时**尚未实测**的（按纪律单独标注）

1. **Jackson 能否直接往返三个模块的状态类型**——本 spec 写作时项目**从未做过任何 JSON 序列化**（实测：`com.fasterxml` 在 `src/main` 与 `src/test` 下均 **0 处**，`json` 字样在 `src/main` 下 **0 处**；`simos-util` 的 pom 从 M0 起声明的 `jackson-databind` 至今**零消费者**）。`GameMap` 是仓里最大的 record 树，还带 `FieldDelta` 密封接口、`Map<HexCoord,…>` / `Map<EdgeRef,…>` 自定义键、`Optional` 组件。**序列化本身就是总纲列出的设计理由之一**（实测原文，`2026-09-16-simos-master-design.md:204`：「Simos 后面明确有 MCP / Agent / GUI / 序列化 / 历史 diff」）——即**序列化从第一天起就是协议级要求**，只是到 M4 才第一次兑现。 ⇒ 探针实测中，结论落到本 spec 的修订与任务 3。
   **在设计上必须先承认的一点**：无论探针结果如何，**"快照 JSON"是 M4 的独立风险项**，不是"顺手就能有"的附属品。

### 13.2 挂起项（随 M4 记录，不在本轮实现）

1. **unit 的其余 7 条命令**（U15 乙只带 1 条）：`Create` / `Reparent` / `SetStrength` / `PlaceAt` / `PlanRoute` / `CancelRoute` / `Disband` 的 `CommandHandler`。
2. **`Disband` 与历史 revision 的关系**：解散一个单位后，旧 revision 里它仍在。查询层要不要"按 revision 过滤已解散单位"——属 M5 的查询层。
3. **`map` / `social` 的操作面**（总纲 §十三 的 `Command 类型清单` 只裁到操作级：unit 8 项 + 两条 Core 命令；map/social 的操作面按各自 spec 后补——**现在补等于编造设计**）。
4. **Facet 的装配点**（M3 §〇.4 已留白）。
5. **`InfoSystem` 挂到属性地址上**（M3 §8.2 的挂起项）：`social:…:population`、`unit:…:member` 的解析与 Info 挂载属查询层，M5。
6. **读取集的前缀语义**（C15 v1 只做精确匹配）：`map:Map1` 与 `map:Map1:hex.4_3` 的包含关系未定义。
7. **Resolve 的算力**：参与者两两相交是 O(n²)，n = 模块数（现实是 3~5），**不优化**。
8. **checkpoint 的 GC**：只增不删。多少份、留多久、按什么策略清理——等有实际占用数据再说。
9. **SQLite 的性能**：单连接 + 私有锁，写是串行的。真到瓶颈再升级连接池/queue（照 agentlib 的记录口径）。
10. **`branch` 的命名与生命周期**：谁能创建分支、分支能否删除、名字是否唯一——U15 只裁到"分岔能跑通"。
11. **`GameMap` 无 id** ⇒ `map:<mapId>` 的 `mapId` 只回显不可校验（M2/M3 遗留，M4 的 `changeset_json` 里同样只回显）。
12. **属性段地址不服务**（M3 §〇.3 第 3 条）在 M4 的 `Resolver` 装配里**仍是空候选**——M4 不改三个模块的 Resolver。
