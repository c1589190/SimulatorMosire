# Task 9 报告：`CommandRegistry` + `CommandBus`——命令分派与四步（2026-09-18）

> 状态：**已完成（含变异自证 2 轮，0 个变异存活）**。**控制器内联执行**（非派发 agent——本轮 agent 全部死于 429，见台账 §丑）。主树 `feat/adr1-core-scope`。交付 8 个主源文件 + 2 个测试类（6 + 13 = 19 条用例）。

## 1. 交付物

| 文件 | 行 | 内容 |
|---|---|---|
| `core/command/CommandEnvelope.java` | 50 | spec §4.1 的信封；身份三件套 + 分支 + 期望坐标 + `type` + `payloadJson`（**文本，不 parse**，C26） |
| `core/command/AdvanceTime.java` | 45 | 时间推进命令（**裁定 36** 形态：与信封同形的身份三件套） |
| `core/command/ForkBranch.java` | 44 | 分岔命令（**裁定 36** 形态；`commandType` **刻意不做分量**——归 `Timeline.FORK_COMMAND_TYPE`） |
| `core/command/CommandResult.java` | 38 | `Committed` / `Rejected` / `Conflict` 三态封闭接口 |
| `core/command/AdvanceRoute.java` | 20 | **裁定 32** 的解环注入点（`CommandResult run(AdvanceTime)`，纯转发型 SPI） |
| `core/command/StateLoader.java` | 26 | **裁定 34** 的状态装配注入点（`SimulationState load(StateRef)`） |
| `core/command/CommandRegistry.java` | 85 | **R12 + 裁定 37**：构造期收全量、不可变、两处构造期校验 |
| `core/command/CommandBus.java` | 175 | 分派（C16 三支）+ ① 入口乐观检查 + ② handler + ④ 落一行 revision |
| `test/…/CommandRegistryTest.java` | 95 | 6 条：在册查找 / 未注册返空不抛 / R12 重复 type / 裁定 37 三种非法形状 / 空表合法 |
| `test/…/CommandBusDispatchTest.java` | 441 | 13 条：两个注入点的形态 4 用例、R11 逐字节、① 三态、②+④ 落行、`ForkBranch` 三态、封闭集兜底 |

## 2. 实测结论行（照抄日志）

```
命令：./mvnw clean verify
rc=0   用时 5 分 23 秒
[INFO] Tests run: 168, Failures: 0, Errors: 0, Skipped: 0   （simos-util，未变）
[INFO] Tests run: 255, Failures: 0, Errors: 0, Skipped: 0   （simos-map，未变）
[INFO] Tests run:  37, Failures: 0, Errors: 0, Skipped: 0   （simos-social，未变）
[INFO] Tests run:  93, Failures: 0, Errors: 0, Skipped: 0   （simos-unit，未变）
[INFO] Tests run:  74, Failures: 0, Errors: 0, Skipped: 0   （simos-core，55 → 74）
[INFO] BugInstance size is 0    ×5
[INFO] BUILD SUCCESS
[ERROR] 行数 = 0
（日志：task-9-evidence/merged-full-verify.log）
```

**627 条用例**（608 → 627，**+19 恰为 Task 9 的 6 + 13**）。单类绿轮的结论行：

```
[INFO] Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- in …CommandRegistryTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 -- in …CommandBusDispatchTest
```

★ **绿轮"真的跑了"的自证**：首次单类跑用了 `-q`，输出里没有汇总行——按纪律形态 1「**没红也要问'为什么没红'**」当场摘掉 `-q` 重跑，确认 19 条**确实执行**（而非 `No tests matching pattern` 的假绿）。`spotless:apply` 已跑，对本任务文件**零 churn**（`git diff --stat` 空）。

## 3. 变异自证（五形态逐轮）

参照原件 md5：`CommandBus.java` = `0c60d6e0878ec91aca123bc65367a7d9`、`CommandRegistry.java` = `5d62c1d7c2f744e1c2eaf45e0a53e066`（`task-9-evidence/orig/`）。变异体按**白名单**推成**目标类名**（非按变异文件名）；每轮开跑前先把工作目录恢复成干净世界（拷回原件 + 比 md5）；每轮落盘 md5 == 变异体、≠ 原件；恢复一律**从证据目录拷回**并比 md5，**未用 `git checkout --`**。

| 轮 | 变异做法 | 期望红 | 实际红 | 存活？ | 为什么 |
|---|---|---|---|---|---|
| m1 | 分派路径上 `payloadJson` → `payloadJson().trim()` | R11（★ 只测 `"{}"` 的用例**不变红**） | `payloadJsonIsForwardedByteForByte:169`（**13 条里恰红这 1 条**） | **杀** | 红的理由**正是 trim 本身**：AssertJ 打印的 expected 是 `"  {"z":1, …}\n\t "`、actual 是削掉首尾空白的那份。**12 条用 `"{}"` 的用例全绿**——与计划表的预判逐字吻合，证明该条的判别力**全部**来自夹具（首尾空白 + 非字典序键） |
| m2 | `CommandRegistry` 删掉重复检查，改 `table.put(type, handler)` 静默覆盖 | R12 | `duplicateTypeFailsAtConstruction:44`（6 条里恰红这 1 条） | **杀** | 红的理由 `Expecting code to raise a throwable`——正是被删掉的那处构造期校验；裁定 37 的两条形状用例仍绿（本轮只动重复检查，判别力互相独立） |

**装置自检（每轮）**：`grep -c "COMPILATION ERROR"` = **0**（两轮皆是）；恢复后 md5 与基线**逐字节相同**。

### 3.1 ★ 本轮新增的装置自检乙（形态 1 的装置家族又添一条）

**m2 的第一次执行是作废轮**：那条命令忘了 `export JAVA_HOME`，`mvnw` **根本没启动**（日志全文 3 行，结尾 `The JAVA_HOME environment variable is not defined correctly`）。但——

> **`rc=1` 成立、`grep -c "COMPILATION ERROR"` 仍然给出 0。**

即：**「编译错误计数为 0」单独并不足以证明这一轮真的跑过。** 这一次是我自己多看了一眼"红的落点"（空输出）才发现异常。⇒ 装置自检**必须再加一条**：

```
grep -cE 'Tests run: [0-9]+' <轮日志>     # 必须 ≥ 1，否则这一轮根本没跑到用例，当场作废
```

这与形态 1 的「**没红也要问"为什么没红"**」同源：`0` 与"空"都能同时由**成功**和**根本没跑**产生。按纪律**整轮重做**（先恢复干净世界、再重推变异体、再跑），重做后的 m2 才是上表里那轮。

## 4. 取代说明（计划/派单函 vs 实测，以实测为准）

本任务是 M4 里**与 spec 分歧最多**的一个。六条全部是**执行期发现的**、且都不是笔误而是**真设计缺口**，故逐条落为编号裁定（台账 §丑~§巳），此处只记结论：

1. **裁定 32（解环）**：计划里 Task 9 的 `Consumes` 行与其分派表**自相矛盾**——分派表要求 `AdvanceTime → Task 12`，而 Task 12 又 `Consumes` Task 9，构成 `9 ↔ 12` 闭环。裁定：**注入，不重排、不打桩**。Task 9 定义 `AdvanceRoute`，`CommandBus` 构造期收下；Task 12 实现、Task 13 装配。配**形态 4 用例**（`isSameAs` 钉住原样转发，连"重建一个字段相同的 `AdvanceTime`"都算红）。
2. **裁定 33（任务边界）**：Task 9 = 分派 + ① 入口检查 + ② handler + ④ 落一行 revision，**单线程正确即可**；③ 的锁内复查与锁纪律归 **Task 10**；事件行归 **Task 11**。⇒ `submit` 现在**不是线程安全的、且故意如此**，类注里写明。**标为推定**，待 Task 10 落地时复核。
3. **裁定 34（注入，但**不是**解环）**：计划 Task 9 的 `Consumes` 里**没有 Task 8**，而 ② 步需要一个 `SimulationState`，其唯一来源是 `Replay.replay(StateRef)`。裁定：注入 `StateLoader`（装配时传 `replay::replay`）。★ **与裁定 32 的形态区别要记牢**：32 是**解环**（非注入不可），34 **不是**（`9 → 8` 本就无环），是**为可测性与职责分离选的注入**。理由不同、结论同形，**别把两者都记成"解环"**。坐标语义：loader 被问的是 `(branch, head(branch))` = **当前 tip**，不是信封上的 `expectedRevision`。
4. **裁定 35（推定）**：`revisions.tick` 是 NOT NULL（spec §3.2），但 `CommandEnvelope` **不带任何时刻字段**（spec §4.1）⇒ 领域命令落的行**继承父行的 `SimosTimestamp`**，与 `Timeline.fork` 同口径。依据：时刻只由时间推进改变（spec §五）。**标为推定**，Task 12 落地后复核。
5. **裁定 36（取代 spec §4.1）**：`revisions` 的 `command_id` / `correlation_id` / `initiator` 三列均 NOT NULL，而 spec §4.1 的 `ForkBranch(source, expectedRevision, newBranch)` **一个都没有**。裁定：**两个 Core 自有记录都补上身份三件套**，形状与次序**与 `CommandEnvelope` 完全同形**。`CommandEnvelope` 自身**未改**（计划已冻结）。
6. **裁定 37（取代计划 Produces 行）**：`HandlerOutcome.Applied` 只带一个**无命名空间**的 `ChangeSet`，而 `WorldChangeSet` 是 `Map<namespace, ChangeSet>`。裁定：`type()` 必须形如 `<namespace>.<Command>`（已证实既有实现符合：`RenameUnitHandler.type()` = `"unit.RenameUnit"`），**在 `CommandRegistry` 构造期校验**，`CommandBus` 用同一条规则装**单键** `WorldChangeSet`。**推论**：`CommandRegistry` **没有可变 `register()`**——计划 Produces 行写的是 `register(CommandHandler)`，但 spec §4.3 要求"同 type 注册两次 ⇒ **构造期**抛"，而可变注册表里**不存在"构造期"这个时刻**，两条写法自相矛盾；取 spec 的语义（早响），注册发生在构造。副作用是 `CommandBus` 手里那张表**天生线程安全**，Task 10 加锁时不必管它。
7. **`Command` 的封闭性由兜底守住**：`Command` 是 util 的**开放**接口，Core 的封闭集只有三种（C16）。三条之外**当场抛 `IllegalArgumentException`**，不静默吞——配了专门用例（`commandOutsideTheClosedSetThrows`）。
8. **`ForkBranch` 支不做"先读 head 再 fork"**：直接 fork，**失败时**才去读 head（正常路径一次事务）。失败原因由 head 的存在性区分：**有 head ⇒ 冲突**（报真实 head），**无 head ⇒ 拒绝**（此时没有 head 可报，编一个坐标出来才是错的）。

## 5. 我未能核实的（不许当结论引用）

1. **并发正确性完全未测**——① 的入口检查与 ④ 的落行之间**没有任何锁**（裁定 33：那是 Task 10 的范围）。两条并发提交可能都通过入口检查、都落一行，**当前实现允许这一点**。Task 10 不补上 ③ 就是漏。
2. **裁定 35（时刻继承）是推定，未与 spec 作者口径核对**——依据只有"`tick` NOT NULL"与"只有时间推进改时钟"两条。若 Task 12 的时间推进语义要求领域命令也推进 `tick`（例如按 `TimeRange` 落一个中间时刻），本轮结论要改。
3. **`StateLoader` 的真实装配（`replay::replay`）未跑过**——Task 8 尚未落地，本任务全部用例用的是替身 `ref -> STUB_STATE`。⇒ **`CommandBus` 与 `Replay` 的组合从未在真状态上执行过**；Task 13 装配时这是第一个要看的点。
4. **revision 号的并发分配语义未定**——`commit` 用的是 `base.revision().value() + 1`。单线程下等于 head+1；并发下两个提交会算出**同一个** revision 号，而 `revisions` 的主键会拒绝第二条。Task 10 要么靠锁排除、要么把失败折成 `Conflict`，**两条路都还没选**。
5. **`SpotBugs` 对 `CommandBus` 的判定只在这个类集下成立**（纪律形态 6）——本轮 `BugInstance size is 0` 是 627 条用例、5 模块的类集下测的；换类集不保证仍是 0。
6. **未做真正的多模块 handler 集成**——裁定 37 的命名空间前缀只用一个替身 type（`"unit.RenameUnit"`）验过，**没有真的把 `RenameUnitHandler` 装配进 `CommandRegistry` 跑一遍**；那个组合归 Task 13。

## 6. 证据清单（`task-9-evidence/`）

`orig/CommandBus.java`、`orig/CommandRegistry.java`（参照原件）、`md5-baseline.txt`、`mutants/m1.trim-payload.CommandBus.java`（md5 `ffc88205…`）、`mutants/m2.silent-overwrite.CommandRegistry.java`（md5 `2713caa4…`）（**均 ≠ 各自原件**）、`m1-red.log` / `m2-red.log`（两轮红轮，m2 的那份是**重做后**的有效轮）、`rounds-summary.txt`（两轮结论 + 作废轮记录）、`merged-full-verify.log`（§2 结论行出处）。
