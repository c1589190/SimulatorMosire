# Task 6 报告：`Timeline`——读写与一切派生（2026-09-18）

> 状态：**已完成（含变异自证 3 轮）**。提交 `ca5b446`（基于 `acfcb14`）。工作树收口时干净（仅本报告与 `task-6-evidence/` 未跟踪后随本提交入库）。

## 1. 交付物

- `simos-core/src/main/java/io/mosire/simos/core/timeline/RevisionRow.java`——`revisions` 表一行的内存形态（spec §3.2 镜像；创世 parent 双 NULL ⇒ `Optional.empty()`）。
- `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java`——`appendRevision` / `fork` / `head` / `branches` / `row` / `parent` / `chainToGenesis` / `byCorrelation` / `hasCheckpoint`（C19 三项，N 为构造参数）+ `changeSetJson` / `readChangeSet`（changeset 落盘唯一口）。DDL 一行未写（归 Task 5，本类只有 SQL 常量与行映射）。
- `simos-core/src/test/java/io/mosire/simos/core/timeline/TimelineTest.java`——14 条用例。

## 2. 实测结论行

```
命令：./mvnw -pl simos-core -am -Dtest=TimelineTest -Dsurefire.failIfNoSpecifiedTests=false test
rc=0；Tests run: 14, Failures: 0, Errors: 0, Skipped: 0；COMPILATION ERROR 计数=0；BUILD SUCCESS
（日志：task-6-evidence/timeline-test-green-run.log）
```
SpotBugs/Spotless：`spotless:apply` 已跑（rc=0）；**关账全量 `verify` 未跑**（控制器 07:53/08:12 两条指令明确不跑，主树冻结 verify 已由控制器完成）。BugInstance / ERROR-WARNING 计数 ⇒ **未测，不作数**。

## 3. 变异自证（五形态逐轮）

参照原件 md5=`0b06e05e…`（`task-6-evidence/orig/Timeline.java`）；每轮：变异体 md5 ≠ 原件、按目标类名推入 `timeline/Timeline.java`、落盘 md5 == 变异体；每轮 `grep -c "COMPILATION ERROR"` = 0；轮间恢复原件并 md5 自证（`RESTORED_MD5_MATCH=YES` / `FINAL_RESTORE_MD5_MATCH=YES`），**未用 `git checkout --`**（原件先已提交，恢复=从 evidence 拷回）。

| 轮 | 变异做法 | 期望红 | 实际红 | 存活？ | 为什么 |
|---|---|---|---|---|---|
| m1 | 删 `hasCheckpoint` 第②项（`isForkParent` 分岔强制） | 分岔点 `hasCheckpoint==true` 的断言 | `hasCheckpointFollowsTheThreeCriteria:203`（恰是 ② 那行断言，其余 13 条全过） | **杀** | 红点=被保护那行本身 |
| m2v1 | `MAX(revision)`→`COUNT(*)`，**保留 WHERE**（计划的字面形态） | 计划预测：分岔后 head 断言、需两条长度不同的分支 | `headIsMaxRevisionPerBranch:87`——**是 ghost 分支那条** | **杀**（红点≠计划预测） | 计划的判别力推导不成立：分支内编号连续 ⇒ 非空分支上 `COUNT==MAX` 恒等（main 3/3、b2 1/1）；真分叉在**空集**：`MAX`→NULL→`Optional.empty()`，`COUNT`→0→`Optional.of(0)`。**计划的"两条长度不同的分支"夹具条件对 WHERE 保留形态没有判别力，"未知分支⇒空"这条才是杀点** |
| m2v2 | `COUNT(*)` 且 **丢 WHERE**（计划括注"把 b2 的 r1 与 main 的 r100 混起来"的形态） | 同上 | 9 条 ERROR，全部死在 `fork→head` 的 `setString(1,…)`：`ArrayIndexOutOfBoundsException`（无占位符的 SQL 绑参数） | **杀**（红点=绑定崩溃，非语义分叉） | 该形态在到达语义断言之前就被驱动层炸掉；红仍由变异行引起，但"混数"语义没有当场演出来 |

结论：**0 个变异存活**；3 轮里计划的"期望红在哪"与实测全部不一致（见 §4）。

## 4. 取代说明（计划/派单函 vs 实测，以实测为准）

1. **`fork` 签名 3 参 → 6 参**（`+commandId, correlationId, initiator`）：`revisions` 行的这四列 NOT NULL（Task 5 DDL），值只能来自 ForkBranch 命令（C21/C22），Timeline 无权凭空造；`commandType` 由本类钉为常量 `FORK_COMMAND_TYPE="core.ForkBranch"`（spec §3.2 注释冻结值）。返回 `Optional<StateRef>`（成功 ⇒ `(newBranch,1)`；冲突 ⇒ 空、零写入）——计划的"不等 ⇒ 不写"落成空返回，冲突判定仍归 CommandBus。
2. **`hasCheckpoint` 的 N 是构造参数**（`new Timeline(store, checkpointInterval)`，校验 ≥1）：计划 `Produces` 写 `hasCheckpoint(StateRef)` 而 C19 第①项要 `r % N`，N 无处可来；spec §3.5"N 由 CoreConfig 给出（默认 100）"，CoreConfig 归 Task 13，届时传 100。
3. **changeset 落盘机制 = mixin 而非裁定 4 的注解**：`ChangeSet` 是 util 冻结契约（U13），不在本任务可写路径上 ⇒ 无法照抄 FieldDelta 的"注解钉接口"方案；在 `Timeline` 内用 `SimpleModule.setMixInAnnotation` 给 `ChangeSet` 挂 `@JsonTypeInfo(Id.CLASS, As.PROPERTY, "@class")`。**必须带类型信息是实测结论**（jshell 探针 P7：裸 mapper 序列化的非空变更集读回必死 `InvalidDefinitionException`；P11：无 `@class` 字节经 typed mapper 读回 `InvalidTypeIdException`），探针输出在 `task-6-evidence/jackson-mixin-probe-output.txt`。
4. **`Id.CLASS` 而非 `Id.NAME`**：裁定 4 对 FieldDelta 选 `Id.NAME` 的理由（封闭子类集 + 类名不入档）在这里倒过来——变更集子类在三个领域模块，对 Core 是**开放集**（铁律 4），封闭名单写不出来。代价：`changeset_json` 行内存 `WorldChangeSet` 的全限定类名（空集行也是：P3），重命名/挪包即旧档不可读——开放集下唯一选项，记为已知代价。
5. **新增公开静态 `readChangeSet(String)`**（计划 `Produces` 未列）：写口的逆操作必须同 mapper 同类型信息，否则每次读回都要各处重配 mixin（忘了=静默失效，裁定 4 拒注解的同一理由）。Task 8（Replay）的 `decodeEnvelope(rev.changeset_json)` 应走它。
6. **新增常量 `FORK_COMMAND_TYPE`**（见 1）；**创世分支名 `"main"` 目前是 Timeline 私有常量**——Task 12/13 建创世行时若也要用，届时提升为共享常量，勿再手抄。
7. **m2 的计划判别力推导被实测推翻**（§3 表）：真杀点是"未知分支 ⇒ `MAX` NULL / `COUNT` 0"的分叉，不是"两条长度不同的分支"。后续谁再写 `MAX vs COUNT` 类变异，夹具必须含**查不存在的分支**。
8. **`chainToGenesis` 对"起点行不存在"返回空清单**（实现与 Javadoc 一致），只对"中段父行缺席"抛 `IllegalStateException`——spec §3.3 未规定此分支，属执行期补齐，测试钉住。
9. **Task 7/8 的读取面**：分岔行的 `changeset_json` 实测形态为 `{"@class":"…WorldChangeSet","modules":{}}`（P3）——Task 8 若按"信封（ref/timestamp/modules/info）"想象它就是错的，它是 **WorldChangeSet 的 JSON**，不是 checkpoint 信封。

## 5. 我未能核实的（不许当结论引用）

1. **全量 `./mvnw -pl simos-core -am verify` 未跑**（控制器指令）——SpotBugs 对 Timeline/RevisionRow 的判定、Checkstyle、与 C2 批次测试的相互作用，全部未测。接手者关账前必须跑一次。
2. **`hasCheckpoint` 的 ② 在"行存在但分支很多"时的性能/索引命中未测**（EXPLAIN QUERY PLAN 没跑）；`idx_revisions_parent` 是否真被 `FORK_PARENT_EXISTS_SQL` 用上，未核实。
3. **`readChangeSet` 对领域模块真实变更集（MapChangeSet 等）的往返未测**——核心 main scope 看不见领域模块，本任务的往返证据用的是 test 侧玩具实现 `ToyChangeSet`（record、单 int 字段）。真实类型的字段形态更复杂，**不排除**需要各 codec 侧再注册东西。
4. **并发下的 fork 原子性未测**（两线程同时 fork 同一 source）——实现上检查与插入同事务，但没有像 Task 5 R2 那样的并发用例直接压过它。
5. **`Id.CLASS` 的跨版本可读性**（类挪包后旧档如何迁移）未设计未测，仅记为已知代价（§4.4）。
6. **`forkRefusesWhenHeadMovedOn`/`forkRefusesUnknownSource` 只验了"不写"**，未验"部分写入被回滚"的字节级残留（信任 Task 5 的 R13 结论，未重复自证）。

## 6. 证据清单（`task-6-evidence/`）

`timeline-test-green-run.log`（绿轮）、`mut-m1.log` / `mut-m2v1.log` / `mut-m2v2.log`（三轮红）、`m1-round.log` / `m2-round.log`（md5 与判别力记录）、`orig/`（参照原件）、`mutants/m1| m2v1 | m2v2.Timeline.java`（变异体）、`jackson-mixin-probe.jsh` + `jackson-mixin-probe-output.txt`（P1~P11 实测）、`reposourcescan-spotless-churn.diff`（spotless 对非本任务文件的折行 churn 留档，该文件已回滚到 HEAD，未提交）。
