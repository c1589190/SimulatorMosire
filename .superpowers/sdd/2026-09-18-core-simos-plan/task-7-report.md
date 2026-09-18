# Task 7 报告：`CheckpointStore`——checkpoint 的文件读写（2026-09-18）

> 状态：**已完成（含变异自证 2 轮，0 个变异存活）**。工作树 `b7`（分支 `m4/b7`，基线 `ed60fd8`）。交付时工作树只含本任务的 2 个源文件 + 本报告 + `task-7-evidence/`。

## 1. 交付物

- `simos-core/src/main/java/io/mosire/simos/core/store/CheckpointStore.java`——`write(StateRef, String envelopeJson)` / `read(StateRef) → Optional<String>` / 构造期 storeDir 存在性检查；路径 `<storeDir>/checkpoints/<branch>/<revision>.json`；R17 文件名安全校验（write/read 共用的唯一校验点）；C18 缺失回退（空返回 + WARNING）；C24 时序契约与写失败处置契约钉在 Javadoc。
- `simos-core/src/test/java/io/mosire/simos/core/store/CheckpointStoreTest.java`——5 条用例（信封逐字节往返 + 路径形态 / C18 缺失空返回 + WARNING 断言 / storeDir 存在性检查 / R17 / R3 逐条一致）。

## 2. 实测结论行（照抄日志）

```
命令：./mvnw -pl simos-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CheckpointStoreTest' test
rc=0
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.060 s -- in io.mosire.simos.core.store.CheckpointStoreTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
（日志：task-7-evidence/green-run.log；COMPILATION ERROR 计数=0；Checkstyle 随 test 阶段已跑，rc=0）
```
恢复基线的收口绿轮同参数：`final-restore-green.log`，rc=0，`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。**全量 `verify` / SpotBugs 未跑**（控制器指令），见 §5。

## 3. 变异自证（五形态逐轮）

参照原件 md5=`715706a8fc0212222771579adb6311a4`（`task-7-evidence/orig/CheckpointStore.java`）；变异体按**白名单**推成目标类名 `CheckpointStore.java`（非按变异文件名）；每轮开跑前恢复原件并重编（m2 轮另有独立基线绿轮 `baseline-before-m2.log`，rc=0 全绿）；每轮落盘 md5 == 变异体、≠ 原件；每轮 `grep -c "COMPILATION ERROR"` = 0；恢复一律**从证据目录拷回**（轮内 md5 对账自证 `715706a8…`），**未用 `git checkout --`**。

| 轮 | 变异做法 | 期望红 | 实际红 | 存活？ | 为什么 |
|---|---|---|---|---|---|
| m1 | 删 `fileFor` 里分支名的路径字符校验（R17 守卫行） | R17 用例（`"../evil"` ⇒ 写穿目录） | `rejectsBranchNamesThatAreNotFilenameSafe:127`（恰是 R17 用例，其余 4 条全过） | **杀** | 守卫删除 ⇒ `write("../evil")` 不再抛 `IllegalArgumentException`，而是解析出 `<tmp>/checkpoints/../evil/1.json` 触达文件系统（本环境死于 I/O，见 §4.7）。红点=被保护那行本身 |
| m2 | `read` 缺失改抛 `IllegalStateException`（违反 C18） | 一条"缺失回退不失败"的用例 | `readMissingReturnsEmptyAndLogsWarning:85`（主红点，恰是 C18 语义行）；连带 `diskFilesMatchHasCheckpointRowByRowAcrossABatch:189`（R3 读非 checkpoint 坐标同样抛——缺失回退被破坏的同一后果） | **杀** | 缺失即抛正是被变异的行为，异常消息 `checkpoint 缺失: …` 当场出现在用例里 |

## 4. 取代说明（计划/派单函 vs 实测，以实测为准）

1. **`storeDir` 是构造参数，不从 `SqliteStore` 取**：派单函说"`storeDir` 从它（SqliteStore）来"，实测 `SqliteStore` **没有任何**暴露库文件/目录的访问器（`dbFile` 私有，唯一入口 `open(Path)`）。而计划的 Task 13 `CoreConfig(Path storeDir, int checkpointInterval, ObjectMapper mapper)` 本来就持有 storeDir ⇒ 构造参数 `CheckpointStore(Path storeDir)` 是正确接缝，Task 13 从 CoreConfig 传入；未动 Task 5 的文件（不在本任务范围）。
2. **R17 的"构造期抛"落为 write/read 入口（构造路径时）抛**：本类构造期只见 storeDir、见不到分支名；分支名最早在 `fileFor(StateRef)` 出现，校验钉在那里（write/read 共用的唯一校验点）。字面意义的"分支名构造期"只有 `BranchId` 构造——那是 util 冻结契约（已禁空白），禁路径字符这一层归本类路径构造时抛 `IllegalArgumentException`。
3. **"不得为空"保留为纵深防御、实际不可达**：`BranchId` 禁 `isBlank` ⇒ 空分支名在本仓无法构造，`name.isEmpty()` 分支不可能被触发（用例也造不出该形态）；保留它与 spec R17 文本一致，代价为零。
4. **写失败（I/O）= 上抛 `UncheckedIOException`；"记 WARNING、不失败"归 Task 13 调用点**：spec §3.4 ④（分岔流程）写明"写不出就记 WARNING，不失败"，那是 C24 调用点的动作；本类若静默吞 I/O 异常，存储故障会被伪装成"没写过 checkpoint"。两条契约（C24 时序 + §3.4 ④ 处置）均已钉在类注/write 的 Javadoc。
5. **"文件在但读不出"（read 的 I/O 异常）= 上抛，不算 C18 的"缺失"**：C18 字面只规定"文件缺失 ⇒ 不失败"；静默把读故障当缺失会吞掉真实存储故障。spec 未规定此分支，属执行期补齐 + Javadoc 注明；Task 8（Replay）若要对该形态也做回退，须在重放层显式处理。
6. **新增 WARNING 断言用例与两个 log4j2 实测坑**（计划 Step 2 只写"记 WARNING"，本用例把它变成可断言的护栏）：log4j-core 2.26.1 实测——① `Logger` facade 的 `setLevel` **不落到 live LoggerConfig**（level 仍 ERROR ⇒ 事件在 appender 之前被滤掉、捕获清单恒空），正确做法是给本类的名字挂专属 `LoggerConfig` 并走 `Configuration.addLogger/removeLogger` 的正式生命周期；② appender 不 `start()` 则**静默丢弃**事件。两坑均以 jshell/java 探针当场定位（Probe7，/tmp，不进仓库）。
7. **m1 的红因是"尝试写穿"而非"成功写穿"**：本环境对含 `..` 段的路径，`Files.createDirectories` 返回后 `writeString` 仍 `NoSuchFileException`（独立探针复现；确切机理疑为本机沙箱拦阻，未深究——见 §5.3）。守卫存在与否决定了**异常类型**（IllegalArgumentException vs 触达文件系统后的 I/O），判别力不受影响；若换一个放行该路径的环境，用例里 `Files.exists(tempDir.resolve("evil"))` 的负向断言会在同一测试内红。

## 5. 我未能核实的（不许当结论引用）

1. **全量 `./mvnw verify` / SpotBugs 未跑**（控制器指令：关账由控制器统一跑）——SpotBugs 对 CheckpointStore 的判定（含文件 I/O / Path 相关 detector）、全 reactor 相互作用未测。Checkstyle 已随 test 阶段通过（rc=0），`spotless:apply` 已跑且对其他文件零 churn（`git status` 仅本任务两个新文件）。
2. **"文件在但读不出"的 read 路径无直接测试**——只靠 Javadoc 契约（§4.5）；造该形态需撤目录读权限/损坏文件，未做。
3. **`..` 段路径在 `createDirectories` "成功"后 `writeString` 仍 ENOENT 的确切机理未定**——独立探针复现了现象，但没有定位是 JDK 路径处理还是本机沙箱拦阻；只影响 §4.7 的字面"写穿"场景，不影响 m1 的判别力结论。
4. **并发写未测**——两个 CheckpointStore 实例（或跨进程）写同一坐标文件的行为未定义未测；spec 的场景里没有并发写 checkpoint（单命令流 + C24 post-commit），如 Task 13 的装配引入并发提交，届时需补。
5. **C24 的"事务提交之后"没有专门的时序用例**——CheckpointStore 结构上不接触任何 Connection，"绝不进事务"是**结构保证**而非测试保证；R3 用例只模拟了"事务已提交后的独立调用"形态（write 与 hasCheckpoint 判定同处 store 打开期间）。真正的调用时序归 Task 13 兑现。

## 6. 证据清单（`task-7-evidence/`）

`green-run.log`（绿轮，§2 结论行出处）、`mut-m1.log` / `mut-m2.log`（两轮红）、`m1-round.log` / `m2-round.log`（每轮 md5 对账 + 红因分析 + 恢复自证）、`baseline-before-m2.log`（m2 轮开跑前的重编原件绿轮）、`final-restore-green.log`（收口恢复绿轮）、`md5-baseline.txt`、`orig/`（参照原件：主类 + 测试类）、`mutants/m1.CheckpointStore.java` / `mutants/m2.CheckpointStore.java`（变异体，md5 `ba6994f4…` / `572b7020…`，均 ≠ 原件 `715706a8…`）。
