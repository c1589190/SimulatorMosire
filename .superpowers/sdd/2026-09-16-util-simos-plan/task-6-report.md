# Task 6 报告：版本坐标、三个协议接口与 `SimulationState`（`state` 包）

- 分支：`feat/m1-util-simos`（就地工作，未建分支/worktree）
- BASE：`0876838`（工作树干净，仅控制器自己的 `progress.md` 修改）
- 提交：`e092780` feat(util): 版本坐标、三个协议接口与 SimulationState（M1 Task 6）
- 状态：**DONE**
- 关账门禁：`./mvnw -pl simos-util clean verify` → **BUILD SUCCESS**
  （Spotless `spotless:check` + Checkstyle + Surefire + SpotBugs `BugInstance size is 0`；
  **115 测试 / 0 failures / 0 errors**，96 → 115，净增 19）
- 未推送。提交后 `git diff --stat HEAD`（simos-util 范围内）为空

---

## 1. 实现了什么

brief Step 3/Step 4 的八个类型**逐字落地**（唯一差异是 `spotless:apply` 对 record 头、Javadoc
与长参数的折行，见 §6）：

| 文件 | 内容 |
|---|---|
| `.../util/state/BranchId.java` | `record BranchId(String value)`；紧凑构造器：`null` 或空白 → IAE（消息 `BranchId.value 不得为空白`） |
| `.../util/state/RevisionId.java` | `record RevisionId(long value) implements Comparable<RevisionId>`，`compareTo` 用 `Long.compare` |
| `.../util/state/StateRef.java` | `record StateRef(BranchId branch, RevisionId revision)`，两条 `requireNonNull`，消息即字段名 |
| `.../util/state/StateMeta.java` | `record StateMeta(StateRef ref, SimosTimestamp timestamp)`，两条 `requireNonNull` |
| `.../util/state/Snapshot.java` | 接口三方法 `ref()` / `timestamp()` / `namespace()`；Javadoc 点明"不用万能父类"与 `namespace()` 的由来（spec §十-D1） |
| `.../util/state/ChangeSet.java` | 单方法接口 `RevisionId baseRevision()`；Javadoc 点明字段清单由各模块从自己的 Snapshot 派生（铁律 5） |
| `.../util/state/Command.java` | 单方法接口 `RevisionId expectedRevision()`；Javadoc 点明信封字段属 Core 的 Command Bus（spec §十-D6） |
| `.../util/state/SimulationState.java` | `record SimulationState(StateMeta, Map<String,Snapshot>, InfoSystem)`：三条 `requireNonNull` → `Map.copyOf` 防御性拷贝 → 逐项校验"键 == 快照 `namespace()`"；唯一取用入口 `Optional<Snapshot> module(String)`；**无跨模块访问器** |

接口签名与 spec §五/§六、总纲 §4.1/§4.5 **逐字一致**，未即兴发挥（Task 7/8/10 按原签名消费）。

## 2. 测试了什么、结果如何

`SimulationStateTest` 8 条、`StateRefTest` 9 条、`SnapshotProtocolTest` 2 条，共 **19 条**：

| 用例 | 覆盖 | 来源 |
|---|---|---|
| `moduleIsTheOnlyLookup` | `module()` 命中/未命中；模块互不串 | brief |
| `moduleKeysMustMatchSnapshotNamespace` | 键 ≠ `namespace()` → IAE | brief |
| `modulesMapIsDefensivelyCopied` | 构造后改调用方 map 不影响本体；`modules()` 不可写 | brief |
| `stateExposesNoCrossModuleAccessor` | 公开方法清单**恰好**是 7 个 | brief |
| `theAccessorCheckFlagsAReplicaThatHasOne` | 同一检查器对违规夹具报出 `map` | brief |
| `branchAndRevisionFormTheCoordinate` / `revisionsAreOrdered` / `stateMetaCarriesRefAndTimestamp` | 坐标、排序、元信息 | brief |
| `blankBranchIsRejected` | 空白分支 → IAE | brief |
| `toySnapshotImplementsTheThreeProtocolMethods` / `changeSetAndCommandExposeTheirStamps` | 三接口最小协议面（玩具快照 + 两个 lambda 实现） | brief |
| `nullBranchIsRejected`（新） | `BranchId(null)` → IAE + `hasMessageContaining("BranchId.value")` | 控制器第 1 条 |
| `nullBranchInStateRefIsRejected`（新） | → NPE + `withMessage("branch")` | 控制器第 1 条 |
| `nullRevisionInStateRefIsRejected`（新） | → NPE + `withMessage("revision")` | 控制器第 1 条 |
| `nullRefInStateMetaIsRejected`（新） | → NPE + `withMessage("ref")` | 控制器第 1 条 |
| `nullTimestampInStateMetaIsRejected`（新） | → NPE + `withMessage("timestamp")` | 控制器第 1 条 |
| `nullMetaIsRejected`（新） | → NPE + `withMessage("meta")` | 控制器第 1 条 |
| `nullModulesIsRejected`（新） | → NPE + `withMessage("modules")`（**关键**：删掉守卫后 `Map.copyOf(null)` 仍抛 NPE，只断类型会空转） | 控制器第 1 条 |
| `nullInfoIsRejected`（新） | → NPE + `withMessage("info")` | 控制器第 1 条 |

新增用例恰好 8 条，与控制器裁决的八条枚举一一对应。

运行命令与结果（`clean` 消掉增量编译陷阱）：

```
./mvnw -pl simos-util -Dtest='StateRefTest,SnapshotProtocolTest,SimulationStateTest' clean test   # exit=0
  Tests run: 9, ... StateRefTest
  Tests run: 8, ... SimulationStateTest
  Tests run: 2, ... SnapshotProtocolTest
  Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
./mvnw -pl simos-util clean verify                                                                 # exit=0
  Tests run: 115, Failures: 0, Errors: 0, Skipped: 0   （模块总计，96 → 115）
  spotless:3.10.2:check (spotless-check) 通过；BugInstance size is 0
```

## 3. TDD 证据（brief Step 2 / Step 5 原命令）

**RED**（三个测试文件先落地、`state` 包 main 目录尚不存在）：

```
./mvnw -pl simos-util -Dtest='StateRefTest,SnapshotProtocolTest,SimulationStateTest' test   # exit=1
[INFO] Compiling 12 source files with javac [debug release 21] to target/test-classes
[ERROR] COMPILATION ERROR :
[ERROR] .../SimulationStateTest.java:[106,44] cannot find symbol
  symbol:   class Snapshot
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.16.0:testCompile
        (default-testCompile) on project simos-util: Compilation failure
（本次输出共 136 处 cannot find symbol：StateRef / RevisionId / BranchId / StateMeta /
 Command / ChangeSet / Snapshot / SimulationState）
```

失败原因即预期：被测类型全部不存在，测试无法编译（brief Step 2 写的就是"编译失败——`cannot
find symbol: class StateRef`"）。完整日志 `/tmp/t6/red.log`。

**GREEN**（八个类型落地后）：

```
./mvnw -pl simos-util -Dtest='StateRefTest,SnapshotProtocolTest,SimulationStateTest' clean test   # exit=0
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

完整日志 `/tmp/t6/green.log`。

## 4. 变异自证证据

### 4.1 裁决 2：跨模块访问器的变异（命令 + 两侧输出）

**加访问器**（用 python 精确插入到 record 体内，`SimulationState.java` 变成
`sha256=77821a93fec3c5ed…`）：

```
./mvnw -pl simos-util -Dtest=SimulationStateTest clean test    # exit=1
[ERROR] Tests run: 8, Failures: 1, Errors: 0, Skipped: 0
[ERROR] SimulationStateTest.stateExposesNoCrossModuleAccessor -- <<< FAILURE!
java.lang.AssertionError:
Expecting actual:
  ["equals", "hashCode", "info", "map", "meta", "module", "modules", "toString"]
to contain exactly in any order:
  ["meta", "modules", "info", "module", "equals", "hashCode", "toString"]
but the following elements were unexpected:
  ["map"]
	at SimulationStateTest.stateExposesNoCrossModuleAccessor(SimulationStateTest.java:53)
[INFO] BUILD FAILURE
```

**撤访问器**（整文件回退，回退后 `sha256=9dfaa5f7934a54ec9cd8db3407d7fe4a415c9601b3caaca7d6367bf7a2e289b2`，
与变异前**逐字节一致**）：

```
./mvnw -pl simos-util -Dtest=SimulationStateTest clean test    # exit=0
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

日志：`/tmp/t6/final-mutation-added.log`、`/tmp/t6/final-mutation-reverted.log`。
**自陈**：第一次施加变异时我把方法追加到了 record 的**闭合花括号之后**，checkstyle 报
`37:9: no viable alternative at input 'Optional'`（解析失败，不是断言失败）——那是无效实验，
已纠正插入位置后重跑，上表是有效运行的两侧输出。

### 4.2 G13：11 条守卫变异（删/改 main 守卫 → 对应用例是否转红）

方法：`/tmp/t6/mutate.py`，单文件**精确串替换**（锚点必须命中且恰好唯一，否则脚本中止）；
每条变异只跑目标测试类且**带 `clean`**（消掉陈旧报告与增量编译两个陷阱）；跑完从内存快照
整文件恢复并 `sha256` 复核。全部输出 `/tmp/t6/mutations.log`。

| # | 变异（改哪里） | 期望转红 | 实测转红（用例名） | 模块汇总 |
|---|---|---|---|---|
| M1 | `BranchId`：删 `value == null \|\|` 臂 | `nullBranchIsRejected` | ✅ `nullBranchIsRejected`（NPE 而非 IAE） | 9 run / 1 fail |
| M2 | `StateRef`：删 `requireNonNull(branch,"branch")` | `nullBranchInStateRefIsRejected` | ✅ 同名 | 9 run / 1 fail |
| M3 | `StateRef`：删 `requireNonNull(revision,"revision")` | `nullRevisionInStateRefIsRejected` | ✅ 同名 | 9 run / 1 fail |
| M4 | `StateRef`：两条消息**串位**（`branch↔revision`） | 两条 null 用例 | ✅ 两条都红 | 9 run / **2 fail** |
| M5 | `StateMeta`：删 `requireNonNull(ref,"ref")` | `nullRefInStateMetaIsRejected` | ✅ 同名 | 9 run / 1 fail |
| M6 | `StateMeta`：删 `requireNonNull(timestamp,"timestamp")` | `nullTimestampInStateMetaIsRejected` | ✅ 同名 | 9 run / 1 fail |
| M7 | `SimulationState`：删 `requireNonNull(meta,"meta")` | `nullMetaIsRejected` | ✅ 同名 | 8 run / 1 fail |
| M8 | `SimulationState`：删 `requireNonNull(modules,"modules")` | `nullModulesIsRejected` | ✅ 同名（`Map.copyOf(null)` 的 NPE 消息为 null，被钉住的消息判据拆穿） | 8 run / 1 fail |
| M9 | `SimulationState`：删 `requireNonNull(info,"info")` | `nullInfoIsRejected` | ✅ 同名 | 8 run / 1 fail |
| M10 | `SimulationState`：架空 namespace 校验循环 | `moduleKeysMustMatchSnapshotNamespace` | ✅ 同名 | 8 run / 1 fail |
| M11 | `SimulationState`：删 `modules = Map.copyOf(modules)` | `modulesMapIsDefensivelyCopied` | ✅ 同名 | 8 run / 1 fail |

11/11 命中期望，且**每次失败数恰好等于预期条数**（M4 两条，其余一条）——没有连带转红，说明
用例之间互相独立。每条变异后 sha256 与快照一致，最终四个被动过的 main 文件哈希复核通过
（`BranchId ee8b4ae8…`、`StateRef ccb41618…`、`StateMeta 4e90d0f5…`、`SimulationState 9dfaa5f7…`）。
**M4 是控制器第 1 条那句"`requireNonNull(x,"branch")` 换成 `requireNonNull(y,"branch")` 串位"的直接验证**：
只钉字段级消息才抓得住，只断异常类型会全绿。

### 4.3 裁决 3：合成方法清单是否稳

**稳，未出现合成/桥接方法**。`stateExposesNoCrossModuleAccessor` 用
`containsExactlyInAnyOrder` 从第一次运行起就通过；唯一一次失败是 4.1 的**真实**新增访问器，
actual 列表为 8 个名字（含 `map`）。`SimulationState` 不实现任何泛型接口，record 只生成
`equals`/`hashCode`/`toString` + 三个访问器，与用例的 7 项清单完全吻合。
**没有**把清单硬编码或放宽成 `contains`，判别力完好（4.1 已证）。

## 5. 文件变更

新增 11 个文件、358 行（`git diff --cached --stat` 复核后提交；`git add` 只加 brief Step 6
列出的两个目录，未用 `git add -A`）：

```
simos-util/src/main/java/io/mosire/simos/util/state/{BranchId,RevisionId,StateRef,StateMeta,
  Snapshot,ChangeSet,Command,SimulationState}.java
simos-util/src/test/java/io/mosire/simos/util/state/{StateRefTest,SnapshotProtocolTest,
  SimulationStateTest}.java
```

提交 `e092780` 后的工作树：除控制器自己的 ` M progress.md` 与本报告外无改动，
`git diff --stat HEAD -- simos-util/` 为空（提交内容 == 已验证内容）。
未改 `pom.xml`（无新依赖，enforcer 的 `bannedDependencies` 在 verify 中通过）、未改
`.gitattributes`、未做任何全树行尾操作。

## 6. 自审发现

1. **`spotless:apply` 的重排**：只动了 record 头/Javadoc/长参数的折行，无语义变更；
   `spotless:check` 在 `clean verify` 中通过，故提交内容即规范形态。
2. **Javadoc 里的领域词**（`Snapshot` 的 `"map"/"social"/"unit"`、`SimulationState` 的
   `state.map().units()`）是约束明文允许的"Javadoc 举例"；main 源码其余部分无领域词汇，
   也已确认无 `java.io`/`java.nio.file`/`Files`/`Path`、无手写 `equals`/`hashCode`/`toString`。
3. **`moduleKeysMustMatchSnapshotNamespace` 只断异常类型**（brief 原文，非 null/空白守卫，
   不属控制器第 1 条范围）：没有额外加消息判据以免超出 brief。它的判别力已由 M10 证明
   （架空校验循环即转红），**不是空转护栏**。
4. **`module()` 未做 `null` 入参守卫**：brief/spec 未要求，`Map.get(null)` 对不可变 map 返回
   null → `Optional.empty()`（不抛）。属"未定义"而非"错误"，留给需要者裁决，我未擅自加。
5. **`Map.copyOf` 的迭代顺序未定义**：多键同时不匹配时 IAE 落在哪个键上不确定；用例只造单键
   场景，未依赖顺序。

## 7. 问题与关注点（非阻塞）

1. **`simos-core` 的全仓构建仍然失败——先于本任务存在**。`./mvnw clean verify` 止步于
   `simos-core` 的 `testCompile`：`AgentLibAvailabilityTest` 找不到
   `io.mosire.agentlib.permission.ResourceAuthorizer` / `io.mosire.agentlib.tool.ToolCallAuthorizer`，
   正是 CLAUDE.md 记的 **M0 硬阻塞项**（`~/.m2` 的 `agentlib-mosire` 过时，49 类 vs 118 类）。
   **A/B 证明与本次改动无关**：把新增的两个 `state` 目录整体移出工作树后重跑 `./mvnw clean verify`，
   `simos-core` 报**完全相同**的失败，而 `simos-util` 在两侧都绿（日志 `/tmp/t6/verify-baseline-ab.log`
   对比 `/tmp/t6/verify.log`）。修复方式见实现计划 Task 1（`cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install`），
   不属本任务范围。
2. **工作树里出现了非本任务产生的 Eclipse/m2e 工件**（未跟踪）：根 `.project`、`.settings/`、
   各模块的 `.classpath`/`.project`/`.settings/`、`simos-core/.factorypath`，时间戳落在本会话期间
   （21:05:29 起），疑似有 IDE 实例在自动导入 Maven 项目。它们**未被 `git add`**，`.gitignore`
   目前也不覆盖 Eclipse 工件。我未删除（不确定归属），留给控制器裁决是否补 `.gitignore`。
   `.serena/`（serena MCP）同样是未跟踪的本机产物。
3. 报告中引用的中间日志都在 `/tmp/t6/`（`red.log` / `green.log` / `mutations.log` /
   `final-mutation-*.log` / `verify*.log` / `mutate.py`），换机即失效；关键输出已全文摘进本报告。

---

## 控制器更正（2026-09-16，Task 6 评审后追加；原作者内容一律保留）

本节由控制器追加，用于更正本报告中被评审者实证推翻的两处技术前提。**实现代码不变，结论不变**，仅更正记录，以免后续任务据此推出错误结论。

1. **§6.4 关于 `module(null)` 的辩护前提不成立。** 原文称"`Map.get(null)` 对不可变 map 返回 null → `Optional.empty()`（不抛）"。评审者在 JDK 21 上实测：`Map.copyOf(Map.of("map","x")).get(null)` 抛
   `NullPointerException: Cannot invoke "Object.equals(Object)" because "o" is null`；空 map 情形经 `requireNonNull` 同样抛 NPE。
   故 `SimulationState.module(null)` **实际抛 NPE**，不是返回 `Optional.empty()`。
   影响：无需求被违反（`module(String)` 的契约未规定 null 行为），但"行为是 X"的记录是错的。已记入台账 minor #1，交最终全支评审分诊（记 `@throws`、或加 `namespace == null → Optional.empty()` 守卫、或维持现状但更正文档）。
2. **§4.2 M8 的括号注不实。** 原文称"`Map.copyOf(null)` 的 NPE 消息为 null"；实际消息为
   `Cannot invoke "java.util.Map.isEmpty()" because "map" is null`。
   影响：无——M8 的结论（消息 ≠ `"modules"` → 用例转红）仍然成立，只是支撑细节写错。已记入台账 minor #2。
