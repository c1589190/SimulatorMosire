# Task 4 报告 —— `WorldChangeSet` + `Envelope`（C26）+ R1

- 分支：`m4/b2`（worktree `/home/dev/SimulatorMosire/.claude/worktrees/b2`）
- 日期：2026-09-18
- 独占改动面：`simos-core/src/main/java/io/mosire/simos/core/state/WorldChangeSet.java`、`simos-core/src/main/java/io/mosire/simos/core/store/Envelope.java`，及三个测试文件 + 一份 core 侧扫描装置（来历见 §3）。未触碰任何禁区文件（SqliteStore.java / core/timeline/** / core/command/** / core/observe/** / test resources / 任何 pom.xml / CLAUDE.md / docs/** / progress.md），未 push、未切分支、未 merge、未 `mvn install`。

## 1. 第 0 步门禁输出（开工前实测）

```
$ git log --oneline -1
b43d115 docs(m4): M4 计划/ spec / SDD 台账 入库 + CLAUDE.md 两处工具陷阱修正
```

与门禁要求一致，随即开工。收尾时（提交前）该 HEAD 未变。

## 2. 交付物与构建证据（全部为实测原文）

新增 6 个源文件：

| 文件 | 内容 |
| --- | --- |
| `simos-core/src/main/java/io/mosire/simos/core/state/WorldChangeSet.java` | `record WorldChangeSet(Map<String, ChangeSet> modules) implements ChangeSet`；紧凑构造器做 LinkedHashMap 保序拷贝 + `Collections.unmodifiableMap` + 键/值 `requireNonNull`；`static WorldChangeSet empty()` |
| `simos-core/src/main/java/io/mosire/simos/core/store/Envelope.java` | `static ObjectNode encode(StateMeta, Map<String,String>, String)` / `static Decoded decode(String)`；`record Decoded(StateMeta meta, Map<String,String> modules, String infoJson)` |
| `simos-core/src/test/java/io/mosire/simos/core/state/WorldChangeSetTest.java` | 5 个用例 |
| `simos-core/src/test/java/io/mosire/simos/core/store/EnvelopeTest.java` | 7 个用例 |
| `simos-core/src/test/java/io/mosire/simos/core/ArchitectureGuardsTest.java` | 1 个用例（R1） |
| `simos-core/src/test/java/io/mosire/simos/core/RepoSourceScan.java` | core 侧测试扫描装置（来历见 §3.2） |

### 2.1 收尾 verify（关闭门禁命令，实测）

命令：`./mvnw -pl simos-core -am verify -Dsurefire.failIfNoSpecifiedTests=false`（每条 Bash 均先 export JDK21/Maven 路径），退出码 0。

- `BugInstance size is 0`：**5 个模块各 1 行，共 5 行**。
- 模块级用例数（`Tests run` 汇总行）：simos-util **159**、simos-map **248**、simos-social **30**、simos-unit **68**、simos-core **28**，合计 **533**，Failures/Errors/Skipped 全 0，`BUILD SUCCESS`。
- 日志中 `ERROR` 行计 **0**；`WARNING` 行计 **1**，其原文归属已核实：`spotbugs:4.10.4.1:spotbugs @ simos-parent` 的 `No files found to generate report on`——聚合 POM 自身无编译产物，属既有无害现象，与本次改动无关。
- simos-core 模块内与本任务直接相关的绿用例（实测行）：
  - `Tests run: 7 ... -- in io.mosire.simos.core.store.EnvelopeTest`
  - `Tests run: 5 ... -- in io.mosire.simos.core.state.WorldChangeSetTest`
  - `Tests run: 1 ... -- in io.mosire.simos.core.ArchitectureGuardsTest`
  - （模块 28 = 上述 13 + 既有 AgentLibAvailabilityTest 15）

### 2.2 SpotBugs 确实扫到了新类（防"模块级 0 是假的"）

`simos-core/target/spotbugsXml.xml`（4.10.4）的 `FindBugsSummary`：`total_classes='4'`、`total_bugs='0'`；`ClassStats` 逐一列出 `io.mosire.simos.core.state.WorldChangeSet`（size 20）、`io.mosire.simos.core.store.Envelope`（size 76）、`Envelope$Decoded`（size 18）及 package-info。两个新主类都被真实分析过。

## 3. R1 判据说明 + 两个"清单外"文件的来历

### 3.1 ArchitectureGuardsTest.java —— 在 Task 4 文件清单里

R1（spec §十一）：全仓 main 源码里 `implements ChangeSet` 的文件**必须恰好是 4 个**（World/Map/Social/Unit），防止后来人给别的类型偷偷实现标记接口、稀释"变更集"这个概念。

实测基线（开工时 `git grep -n "implements ChangeSet" -- '*/src/main/*'`）：3 处 —— `MapChangeSet.java:50`（折叠形态）、`SocialChangeSet.java:22`、`UnitChangeSet.java:22`；加上本任务的 `WorldChangeSet` 恰为 4。该测试扫描 `simos-{map,social,unit,core}/src/main`，对每个文件做 `contains("implements ChangeSet")`，断言为 `containsExactly` 四条精确路径（比计划草稿的 `hasSize(4)` 更强：数量对、路径也要对，见 §5 取代说明第 1 条）。测试侧的玩具实现者（`new ChangeSet() {}`）不在扫描范围（只扫 main），天然豁免。

### 3.2 RepoSourceScan.java —— 计划 Step 4 正文点名要求的装置，非我自加

计划 Task 4 正文（★ 条目）明确要求："simos-core/src/test 自带一份 RepoSourceScan"（测试类跨模块不可见，util 侧那份 core 看不见，必须自带）。因此它是 Task 4 R1 判据的组成部分，落位 `simos-core/src/test/java/io/mosire/simos/core/RepoSourceScan.java`。

实现与 util 侧同构，但有一处**必要偏差**（见 §5 第 3 条）：文件可扫性过滤只作用于 `rootDir.relativize(path)` 的相对段，不再检查绝对路径的每一段。原因：本仓 worktree 路径含隐藏段 `.claude/worktrees/...`，按绝对段过滤会把所有文件滤光——R1 首跑实测 `actual=[]`（0 个文件被扫），若沿用旧写法，`containsExactly` 会红（暴露问题），而弱断言版本会假绿。Javadoc 里记录了这个坑。

## 4. 变异轮逐条表（五-form 纪律，全部实测）

实验台 `/tmp/m4t4/`，原件 md5：SocialChangeSet `d03435637610c1566410575ca69c157e`、Envelope `356cd8c32894e77a44d1ccf28358e7c8`、WorldChangeSet `93f826331f25af3d9c399a717e90e33c`。每轮：变异文件先与原件 md5 对比证明字节不同 → 以显式类名安装（cp 白名单目标）→ 干净世界（先恢复+md5 验证）→ `mvn test`（-pl simos-core -am）→ 断言日志 `grep -c "COMPILATION ERROR"` == 0 → 判红 → 恢复原件 + md5 核对。

| 变异体 | 变异内容（diff 实证） | 红的用例 | 红的那一行 | COMPILATION ERROR 计数 | 判定 |
| --- | --- | --- | --- | --- | --- |
| m1 | SocialChangeSet 仅删 `implements ChangeSet`（留 import） | 无（未到 surefire） | checkstyle：`SocialChangeSet.java:6,8 UnusedImports`，`BUILD FAILURE` | 0 | **作废**：红不在被保护行上（checkstyle 先于 surefire） |
| m1b | m1 + 连带删除孤儿 import（计划 m1 的可执行版） | `ArchitectureGuardsTest.changeSetHasExactlyFourMainSourceImplementors` | `ArchitectureGuardsTest.java:40`（containsExactly 缺 SocialChangeSet.java） | 0 | 红 ✓ |
| m2 | Envelope.encode 载荷 `requireNonNull(...).trim()` | `embedsModulePayloadsAsTextNotAsObjects` + `roundTripsAllFieldsAndKeepsPayloadsByteExact` | `EnvelopeTest.java:73` 与 `:46 [C26：载荷逐字节保真]` | 0 | 红 ✓ |
| m3 | WorldChangeSet `unmodifiableMap` → `Map.copyOf`（原设计） | 未运行 | 预检发现会孤儿化 `java.util.Collections` import，与 m1 同型必作废 | — | **未跑即弃**，由 m3b 取代 |
| m3b | m3 + 连带删 import（可执行版） | `WorldChangeSetTest.preservesIterationOrder` | `WorldChangeSetTest.java:49`（5 键顺序被 Map.copyOf 打乱） | 0 | 红 ✓ |
| m4 | WorldChangeSet 紧凑构造器去掉键/值 `requireNonNull` | `WorldChangeSetTest.rejectsNullKeyAndValueAtConstruction` | `WorldChangeSetTest.java:57` | 0 | 红 ✓ |
| m5 | Envelope.decode `value.asText()` → `value.toString()`（JsonNode 的 toString 带结构改写） | `roundTripsAllFieldsAndKeepsPayloadsByteExact` | `EnvelopeTest.java:46 [C26：载荷逐字节保真]` | 0 | 红 ✓ |
| m6 | Envelope.decode 删除 `isTextual()` 形态守卫（静默收下嵌套对象） | `decodeRejectsNonTextualModulePayload` | `EnvelopeTest.java:85` | 0 | 红 ✓ |

存活项：**0**（8 个变异体中 6 个有效全红，m1 作废、m3 未跑即弃；无任何变异体在绿世界存活）。每轮结束工作树均恢复到原件（三轮 md5 全部回到基线值），最终 `git status --porcelain` 只有 6 个新文件。

## 5. 取代说明（计划/spec 与实测现实的冲突，按实测执行）

1. **R1 断言从 `hasSize(4)` 升级为 `containsExactly` 四条精确路径**。计划草稿只数数量；数量断言对"挪位置/重命名"不敏感。实测采用路径级全等（见 §4 m1b：变异红的就是路径缺失，证明该强度可被变异杀死）。
2. **变异体生成规则取代计划假设**：checkstyle 在 surefire 之前跑，"只删 implements"会留下孤儿 import 导致轮次作废（m1 实证）。计划里的 m1/m3 一律重生成"连带删 import"版本（m1b/m3b）后才有效。
3. **RepoSourceScan 的隐藏目录过滤改为只看相对段**：util 侧原版（Task 1 交付）按绝对路径全段过滤 `startsWith(".")`，在 worktree 布局下会把所有文件滤光——core 侧首跑实测 `actual=[]`。core 侧自带副本据此修正。**上游发现**：util 侧那份源码仍是旧写法（读码确认同型缺陷），但那超出 Task 4 边界，未改，上报控制器裁决。附带观察：本机最终 verify 里 util 159 用例全绿，与"该缺陷使 R15 在 worktree 下可能空真"不矛盾（空扫描下逐文件断言空真通过），见 §6。
4. **C26 实现方式**：模块载荷全程按 `String` 原文搬运（encode 不 parse/trim/重序列化，decode 逐字节回读），未用 `SimosObjectMapper`、未 import 任何 `util/json/**`，符合任务指令。Envelope 内部用自带 `ObjectMapper` 只处理外层信封结构与 `info` 的 parse（spec §6.3 的信封 JSON 形态），`infoJson` 按 JSON 树相等比较而非字节比较（信息字段无逐字节契约；载荷才有）。
5. **jackson-databind 依赖来源**：simos-core 的 pom 未直接声明 jackson-databind，经 simos-util 传递获得（spotbugsXml 的 AuxClasspathEntry 实证 `~/.m2/.../jackson-databind/2.22.2`）。未新增依赖、未改任何 pom，未触发"需新依赖即停"条款。
6. **`ChangeSet` 是 0 方法的标记接口**，非函数式接口，测试里不能写 lambda/方法引用，一律 `new ChangeSet() {}` 匿名类（WorldChangeSetTest 实测可编译通过）。
7. **WorldChangeSet 用 LinkedHashMap + `Collections.unmodifiableMap` 而非 `Map.copyOf`**：`Map.copyOf` 的迭代顺序不是内容纯函数，保序契约会靠运气（3 键夹具实测有 7–40% 假绿窗口）；m3b 变异轮证明 5 键夹具 + `containsExactly` 能稳定杀死该变异。
8. **Order 夹具键数取 5**（zulu/mike/alpha/whiskey/bravo）：短夹具在小键数下对小实现差异不敏感（见第 7 条实测数据），5 键在 m3b 下稳定红。

## 6. 「我未能核实的」

1. **util 侧 RepoSourceScan 在 worktree 下 R15 是否真的空真**——只做了读码确认（同型绝对段过滤），没有单独跑到 util 模块去复现"扫描结果为空且测试仍绿"这一步； §5 第 3 条的措辞以此为界。最终 verify 全绿与本判断不矛盾但也不构成复现证明。已如实上报，未越界改动。
2. **收尾 verify 的 WARNING=1 是否在其他模块组合下会变多**——只测了本次关闭门禁命令（-pl simos-core -am）这一种组合；全 reactor 或其他 -pl 组合下的 ERROR/WARNING 计数未测（按协调人"优先定点命令、避免全 reactor"的指示未跑）。
3. **`Envelope` 对超大载荷/深嵌套 info 的行为边界**——用例覆盖了空白符、转义、非字典序、非文本载荷拒绝，但未构造超大字符串或极端嵌套的性能/栈深边界用例（spec 未要求，列为未知而非缺陷）。

除上述 3 条外，本报告所有数字与结论均来自本次会话的实测命令输出（原始日志存 `/tmp/m4t4/`）。
