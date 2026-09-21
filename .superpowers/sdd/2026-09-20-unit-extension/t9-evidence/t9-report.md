# unit-ext T9 报告：SPI 装配（Shell 注册 12 新 handler + cost 注入 + catalog 全 type）

> 分支 `ue/t9`，基线 `bc7c8a4`。实现提交见本报告末「提交」。
> ★ 本报告里的钟点一律由 `git log` 从提交哈希解析（可核验）；正文不写"我读的钟"。

## 〇、一句话

把 T1~T8 造出、但从未注册的 **12 条 handler** 接进 `Shell`，并补齐 **12 条真能生效的端到端
载荷**；`catalog` 从 18 条变成 30 条，与全仓 30 个 `CommandHandler` 实现**集合相等**。
`./mvnw clean verify` 绿（**第 1 次尝试**），3 个变异体全杀、0 存活。

## 一、改了什么（3 个文件，生产面仅 1 个）

| 文件 | 改动 | 性质 |
|---|---|---|
| `simos-app/.../Shell.java` | handlers 列表 `List.of(...)` 内追加 12 条（含 `new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE)`）；import +12；三处 Javadoc 的 handler 计数 `十八/八 → 三十` | **生产**（组合根） |
| `simos-app/.../McpCoverageTest.java` | `EXPECTED_COMMAND_TYPES` 18→30；`MINIMAL_PAYLOADS` +12（语义合法序）；创世夹具 +3 个单位；随命令数/顺序更新的派生断言 | test |
| `simos-app/.../tools/SimosToolsTest.java` | `EXPECTED_COMMAND_TYPES` 18→30 + `hasSize`；**新增强判据用例** `catalogCoversEveryCommandHandlerImplementation`（扫描三模块 main 源码抽 `type()`，与 catalog 比集合） | test |

`git diff --stat`：3 files changed, 166 insertions(+), 17 deletions(-)。

## 二、注册清单（12 条，全部落在 `List.of(...)` 之内）

`SetStatus` / `AttachUnit` / `DetachUnit` / `ReparentSubtree` / `SetFormationOffset` /
`SplitFormation` / `MergeFormation` / `PlanSparseRoute(cost)` / `SetRejoinTarget` /
`CreateCommandChain` / `UpdateCommandChain` / `ApplyCasualties`。

★ **`unit.SetStatus` 自 T2 造出来起从未被注册**（HANDOFF §三的提醒属实）——本轮首次注册。
★ `MovementCost` 注入照 `Shell.java:221`（`UnitTimeParticipant`）同一来源 `TerrainMovementCost.INSTANCE`，
**且写进 `List.of(...)` 之内**（HANDOFF §三.2 的"注册了却没进 catalog"唯一真实路径，已被 m2 变异体实测钉住）。
★ catalog **不需要单独改**：`commandTypes` 由遍历 `handlers` 现场构建（`Shell.java:228-232`），注册即入 catalog。

## 三、强判据（catalog == 全仓 30 个实现的 `type()` 集合）

- `SimosToolsTest.catalogCoversEveryCommandHandlerImplementation`：扫描
  `simos-unit`/`simos-map`/`simos-sd` 的 `src/main/java/**/*Handler.java`，正则
  `public String type\(\)\s*\{\s*return\s*"([^"]+)"` 抽每个文件的 type 串，断言
  **集合恰为 30**（非空自证）且 **== catalog**。
- `McpCoverageTest`：`catalog == EXPECTED_COMMAND_TYPES`（双向）+ `MINIMAL_PAYLOADS.keySet() == catalog`（双向）。
- `SimosToolsTest.catalogListsExactlyTheRegisteredCommandTypes`：`hasSize(30)` + 集合相等。

★ **机械核过**：`*Handler.java` 共 30 个（map 6 + sd 4 + unit 20）；正则对 30 个文件**各命中 1 条**、
30 个唯一串。**近名对**（`ReparentUnit`/`ReparentSubtree`、`PlanRoute`/`PlanSparseRoute`、
`SetStatus`/`SetStrength`/`SetFormationOffset`）全部用**集合相等**判，不用前缀/包含。

## 四、门禁（★ 第 1 次尝试即绿）

- 命令：`./mvnw clean verify`；**尝试次数 = 1**（未被摘后台、未被杀）。
- 证据：`t9-evidence/logs/clean-verify.attempt1.log` + `verify-rc.txt`（**rc=0**）。
- 总数（★ **从原始日志重算**，非引用文档）：
  `grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' <log> | … | paste -sd+ | bc`
  ⇒ **1190** = `170 / 362 / 45 / 257 / 177 / 62 / 117`（util/map/social/unit/core/sd/app）。
- 7/7 模块 + 父 POM = 8 行 SUCCESS；`BugInstance size is 0` **×7**；`[ERROR]` **0 行**；
  前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。
- **delta 干净**：T8 关账 = 1189 = `170/362/45/257/177/62/116` ⇒ 本树只有 **app 116 → 117（+1）**，
  恰为新增的强判据用例 `catalogCoversEveryCommandHandlerImplementation`；前六模块逐值不变。

## 五、端到端（`[T11-COVERAGE]`，真 MCP 客户端走真 socket）

30 条 type **逐条 committed**，revision `main@2 … main@31`；随后 `simos.advance` ⇒ `main@32`、
`simos.fork` ⇒ `mcp-branch@1`；反向坏载荷 ⇒ `rejected` 且 `revisions` 行数不变。
证据：`McpCoverageTest` 的 `system-out`（30 行 `[T11-COVERAGE]`）。

## 六、★ 执行期发现（计划/HANDOFF 未写，本任务实测）

1. ★★ **`SegmentedSeries` 的段必须严格升序 ⇒ 同刻对同一条段序列只能落一段**。
   `state.meta().timestamp()` 对信封命令**恒等于创世时刻**（`CommandBus:359` 继承父行时刻），
   故 12 条新命令里**会追加段的 6 条**（`Attach`/`Detach`/`ReparentSubtree`/`Split`/`Merge`/`SetFormationOffset`）
   若都打同一个单位，第二条起会因"同刻两段"被拒。
   ⇒ **载荷时序设计**：创世夹具加 3 个锚在 `T0` 的单位 `u-3`/`u-4`/`u-5`，把 4 次 `parent` 落段与
   4 次 `attached` 落段**分散到互不冲突的段序列**上（`u-1`/`u-3`/`u-4`/`u-5` 各承一份）；
   `MergeFormation` 的 `u-5` 与 `u-2` 同在 `H12`、且 `u-5` 为 `MOVING`。
   ★ 这是 HANDOFF「顺序即语义合法序」背后**真正的机械约束**，不是风格问题。
2. ★ **HANDOFF §三的爆炸半径表漏了一个红点**：`SimosToolsTest.java:105` 的
   `EXPECTED_COMMAND_TYPES` 也是**硬编码 18 元表**，注册 12 条后**必红**。已一并更新（并在其上加了强判据用例）。
3. `PlanSparseRoute` 在 3 格合成走廊（`H11-H12-H13`，沙漠 `moveCost=1`）上只能展开**相邻段**
   （起点必须等于单位当前位置 `H12`，非相邻会与 `path` 去重不变量冲突）⇒ 该载荷走的是
   `expandSparsePath` 的**单段路径**；真正的"非相邻展开"由 T6 的 `simos-unit` 单测覆盖。

## 七、变异（3 轮 / 3 杀 / 0 存活；另 1 轮 VOID 留档不删）

装置：`t9-evidence/mutants/mut-round.sh`（照 T8 的十道门禁：白名单 / baseline md5 锚定 /
逐字节 `cp` 还原（绝不 `git checkout --`）/ 日志自指（orig/mutant/pushed/restored 四个 md5 入日志）/
⑩ 按**片段+方向**自证）。靶文件 = `Shell.java`（T9 唯一生产改动）。
★ **`Shell.java` 在 T1~T8 无既有变异轮**（全仓 manifests 里除本轮外无它）⇒ 裁定 42 的"重跑被改动文件的既有轮"**空满足**。

| 轮 | 变异 | 方向 | 结果 | 红点（被保护断言） |
|---|---|---|---|---|
| t9m1 | 删 `new SetStatusHandler(),`（连未用 import） | delete | **KILLED** rc=1 | `McpCoverageTest:249` + `SimosToolsTest:218` + `SimosToolsTest:194` |
| t9m2 | 把该注册**移出 `List.of(...)`**、改在 `commandTypes` 循环之后 | revert | **KILLED** rc=1 | 同上三条（"注册了却没进 catalog"的唯一真实路径） |
| t9m3 | `PlanSparseRouteHandler` 注入**恒不可通行**的匿名 `MovementCost` | delete | **KILLED** rc=1 | `McpCoverageTest:262` `type=unit.PlanSparseRoute … [mosire:code=REJECTED]` |
| ~~t9m1（首轮）~~ | ~~同上，但**未删 import**~~ | delete | **VOID** | 红在 **Checkstyle 未用 import**、surefire 根本没跑 ⇒ 按纪律判 VOID，**不是杀** |

- 每轮 `compile_errors=0`、`restored==orig yes`、`report_mtime` 落在本轮内。
- VOID 轮留档：`t9-evidence/mutants/t9m1.void-checkstyle.log`（**不删**）。
- 日志：`t9m1.log` / `t9m2.log` / `t9m3.log`（各含 ⑩ self_proof 与四个 md5）。

## 八、★ 三处静默面（**本任务不动，归 T10；此处仅声明**）

1. **GUI 写白名单**：`simos-app/src/main/resources/webui/modes.js` 的 `unit` 模式 `writes` 仍只列
   **6 条**；`isWriteAllowed` 逐条精确匹配 + **fail-closed** ⇒ 本轮 12 条新命令**GUI 直接发不出**，且**无测试会红**。
   （★ 既有缺口同族：`unit.RenameUnit`/`unit.PlaceAt` 也不在任何模式的 `writes` 里。）
2. **agent 侧载荷提示**：`tools/read/CatalogTool.java:28` 的 `PAYLOAD_HINTS` 仍只有 **8 条**，
   取值 `getOrDefault(type, "")` ⇒ T9 后 **30 条里 22 条为空提示**（静默兜底）。
3. **`Shell.java` 日志**：`participant=1` 仍是**硬编码字面量**（`Shell.java:308`），
   将来注册第二个 participant 会**静默说谎**（不编译错、不测试红）。

★ **范围声明**：T9+T10 做完后，**unit-ext 在 GUI/API 面上仍不可见**（白名单 fail-closed）；
交付时不得把"全绿"读成"界面上能用"。

## 九、★ 我未能核实的

1. **`agentlib-mosire` 是外部依赖**（`pom.xml`，`0.1.0-SNAPSHOT`，不在本仓）⇒ 门链、MCP 传输、
   工具框架的**内部读不到**。⇒ **"新命令是否与既有命令走完全相同的审批/传输路径"我未能核实**
   （**不是**"已确认无洞"）。本任务只能证"经真 MCP 客户端走真 socket、经审批 APPROVE_ONCE 后 committed"。
2. **真档未上**：全部证据在**合成小图**（3 格走廊 + 4 单位）上；19441 格真档上的新命令未验。
3. **`PlanSparseRoute` 的非相邻段**：3 格走廊上无法构造"非相邻且 `path` 无重复"的稀疏段
   （见 §六.3），故该载荷只覆盖单段展开；非相邻展开的正确性引 T6 的 `simos-unit` 单测，**本轮未复验**。
4. **变异体 `t9m3` 的"不可通行"语义**：匿名成本恒返回空 ⇒ 命令期拒绝；但"部分段不可达 / 段中途地图变化"
   等更细的拒绝路径**未覆盖**（引 T6 单测）。
5. **全量门禁的稳定性**：本树本轮 `clean verify` **第 1 次尝试即绿（47.5s）**，
   但环境级纪律在案（"被杀既不是红也不是绿"）⇒ 单轮绿**不构成装置稳定的证据**。
6. **前端门禁未改**：`tests=88 pass=88` 是**未改的复核**（T9 零前端改动）。
7. `catalog` 的**顺序**未断言（三处都是集合相等）——`CatalogTool` 是否排序未核（与判据无关）。

## 十、提交

- 分支 `ue/t9`，基线 `bc7c8a4`。
- 实现提交：`<见提交短 SHA，由控制器合并时回填>`。
