# 自由判图工具统一测试报告

> **状态**：统一测试完成；**最终 `tools/gate.sh full` PASS**（2026-11-14 21:47 CST，耗时 281s）。测试责任区已停写交回。
> **父序列**：[底图判读与自由地图工具目标运行设计](../specs/2026-11-14-map-reference-tools-design.md) T0–T4、§3 影响、§5 验收。
> **计划**：[自由判图工具实施计划](../plans/2026-11-14-map-reference-tools-plan.md)。
> **实现账本**：[自由判图工具实现责任区账本](2026-11-14-map-reference-tools-impl-ledger.md)（含 §6.1 L3 与 §6.2 四项 SpotBugs 返修）。
> **本报告只写测试、loader/精确门禁清单与证据**；未改生产（变异窗口除外且已 SHA 还原）、未弱化断言、未 commit/push、未起常驻服务、未操作浏览器、未派代理、未读账号密钥。

本轮最终结论：**T0–T4 测试目标完成，最终 full gate 全绿，未遗留真实生产缺陷**。真实浏览器操作/截图、真实整张图的判图可读性、真实模型自主绘完整图仍由控制方独立验收，不在本测试责任区。

## 1. 源码基线与范围

| 项 | 事实 |
|---|---|
| 分支 / HEAD / upstream | `feature/d036-node-editor` / `b9d8fe1d` / `origin/feature/d036-node-editor` |
| 本轮生产改动 | **无**。测试责任区只写测试、测试 loader/门禁清单与本报告；生产 4 项 SpotBugs 返修由生产责任区完成 |
| 生产状态 | 返修后 `compile` 成功并再次停写；SpotBugs 未关规则、未加宽抑制 |
| 最终 full | **PASS**：`clean verify` rc=0 + `-DskipTests validate` rc=0，证据齐全 |
| 工作树 | 保留开工前 dirty 与控制方生产实现/文档；未 commit、未 push、未起浏览器或常驻服务 |

测试责任区新增/修改：

- **新增 Java**：`simos-map` 的 `MapReferenceServiceTest`、`PaintTerrainHandlerTest`、`ConnectChainOperationsTest`、`ConnectHexesHandlerTest`；`simos-app` 的 `MapReferenceEndToEndTest`。
- **修改 Java 清单/构造**：`ShellAssemblySmokeTest`、`ToolClassAuditAssemblyTest`、`SimosToolsTest`、`McpServerTest`、`McpPortTopologyTest`、`McpCoverageTest`、`AdjudicateTickToolTest`、`WebuiAssetsTest`。
- **新增 JS**：`map-reference-tools.test.cjs`（26 条真实 DOM/异步生命周期）；修改 `modes.test.cjs`、`map-raster-lifecycle.test.cjs`、`gate-contract.test.cjs`、`run-gate.cjs`。
- **报告**：本文件。

## 2. T0–T4 覆盖

### T0/T1 参考图工件与查看

`MapReferenceServiceTest`（9 tests）：

- 真实 PNG/JPEG 解码，真实宽高/媒体类型/字节数入摘要；同图同格网（换序）稳定 64 位内容 id；换文件名/换格网得到不同 id。
- 内容寻址稳定视图：同内容重复 `view` 得到相同 `assetId` 与逐字节相同 PNG；概览/局部 PNG 用 `ImageIO` 真解码并**数源色像素**（不是只 assert 非空）；输出边长 ≤1280、PNG ≤2MiB。
- 概览不输出 cells；局部 radius 1 只出半径内格；`q/r` 半给、radius 0/9/-1、中心不在格网全部明确拒绝，不默认猜中心。
- 预检：空字节、非图、GIF、>32MiB、IHDR 声称 6000×6000（32M 像素上限）都在整图解码前拒绝。
- 坏文件名/grid：空名、>200 字符、控制字符、12001 格、重复坐标、图外格心、半径 0/超原图、NaN/Infinity 像素几何。
- 非法 id：null/空/`../etc/passwd`/反斜杠穿越/63/65 位/大写/非十六进制；合法但不存在的 id 报「不存在」。
- 同目录新服务实例读取同一摘要、同一图像绑定与同一格网 `imageX`；篡改 image 文件后指纹不一致明确失败；坏元数据在 list 中被跳过而不拖垮列表。

`McpServerTest.referenceToolShipsRealPngAsImageContentAndStaysGmOnly`：

- 真 MCP `tools/call simos.map.reference` 收到 **1 个 `ImageContent`**，`image/png` base64 真解码；文本含真实 `branch/revision`、局部 q/r、`built/terrain/height`。
- 决策人桶不含 `simos.map.reference` / `map.PaintTerrain` / `map.ConnectHexes`。
- 生产返修后，`SimosToolsTest` 把整数参数断言同步到新语义：`q=1.5` / `2^31` / `true`、`radius=1.5` 都失败（消息为「必须是 int 范围内的精确整数」或「超出 int 范围」），仍严格拒绝，不截断。

### T2 直接多格地形

`PaintTerrainHandlerTest`（8 tests）：

- 一批异类地形一次提交；height 缺省取 `TerrainHeights.paintHeight(terrain)`，显式 0/1 合法；显式 `height:null` 拒绝（带「缺省请省略该字段」）。
- 土地树、未点名格/图外格、区域、城市、通路、通路组、边、spec 逐字段保持；`TerrainBlocks.requirePartition` 成立。
- `MapChangeSet` codec 与 `MapSnapshot` codec 都完整往返到同一 target。
- 负例：空/12001 格、`{}`、cells 非数组/元素非对象、q/r 小数/int 溢出/字符串/null/布尔、未知 terrain、重复坐标、图外、height 字符串/null/布尔/对象/数组/-0.001/1.001/1e999；合法格在前、非法格在后仍整批拒绝且 base 不变。
- `type == map.PaintTerrain`、`targetPaths` 精确 `Map1/hex/0_0` 等、不实现 `DmUsableCommand`。

`SimosToolsTest` 增加结构化 `map.PaintTerrain` 真实提交（顶层 `cells`，无 `payloadJson`），并保留旧 `map.SetTerrain` 的 `payloadJson` 兼容路径；非法坐标命令返回失败且 head 不变。

### T3 有序格链连通性

`ConnectChainOperationsTest`（8 tests）：

- merge 只给点名边补 kind，保留同 kind 既有 props 与其他 kind；链外边/外部 river 边逐值不动；地形/土地/通路/区域/城市/spec 不变。
- remove 只摘点名边的 kind：同一保留 road props，空标签边删除，链外 river 边不动。
- 重复 merge 幂等；复合支流多次调用保留先前边；闭环 A→B→T→A 允许首尾同格，A→B→A 因重复无向边拒绝。
- 自环、跨格跳跃（指出第几段）、图外端点、未知 kind、`replace` op、<2 点、>12001 点、null 链全部拒绝。
- 环绕图按 `MapWrap` 使 (0,0)-(2,0) 相邻，且不改变 wrap/spec。
- `Integer.MIN_VALUE/MAX_VALUE` 极端坐标不会被 int 距离溢出误判相邻；相邻极值仍可连接。
- 只有 `edges` 组件变化，其余 7 个组件 `Unchanged`，ChangeSet/Snapshot codec 往返。

`ConnectHexesHandlerTest`（4 tests）：精确有序 `targetPaths`、merge/remove 应用、领域负例具名拒绝、坏 JSON/缺字段/非对象/非整数 q/r/非字符串 op 拒绝、不实现 `DmUsableCommand`。

`SimosToolsTest` 增加 `map.ConnectHexes` 非邻接写入失败且 head 不变。

### T4 真实 Shell/GUI HTTP/重启与前端

`MapReferenceEndToEndTest`（1 个综合用例，覆盖 T0–T4 端到端）：

- 真 `Shell` + `blank-editor` + `ShellMain.seedGenesisIfEmpty`；`map.ImportRaster merge` 建三格走廊。
- 参考图 `POST /api/map/references` 不推 revision；同 body 重复发布稳定 id；`GET /api/map/references` 一格引用。
- `GET /api/map/reference` 局部返回真实可解码 PNG、真实 branch/revision、cells（半径 1 为 2 格）、`sourceCrop`；概览 cells 为空。
- `map.PaintTerrain` 一次多格异类地形；`map.ConnectHexes` river/road merge；road 局部 remove；每次各一条 revision。
- 负例：图外 paint、非邻接 connect 返回 422 且 revision 不变；GET 半 q/r、radius 9、不存在 id、路径穿越、`as=`；POST 坏 base64、空 cells、重复坐标、坏 imageX、imageRadius 0、非图、空 fileName、`as=` 全部 400。
- 关壳重开同 store：head 仍为 6；mountains 0.9 / desert 默认高度、两段边的 river/road 保留（被 remove 的 road 已消失）；参考图列表与局部视图、原图坐标仍可读。
- `WebuiAssetsTest` 同步 manifest `version=3`、`map-reference-tools.js` 在册且 classpath 可读。

`map-reference-tools.test.cjs`（26 tests，DOM/异步替身）：

- 无识别 plan 也能共享：`getReferenceInput` 的 grid 直接进 `POST /map/references`。
- 查看概览/局部：q/r/radius/terrainOverlay/branch/revision/writeEpoch 都进请求；迟到视图在换来源/换源图后不覆盖当前界面；迟到列表不覆盖新选择。
- 写地形/连接/擦除：`map.PaintTerrain` / `map.ConnectHexes` 经 `app.writeCommand(type,payload,frozen)`，不直接 `api.submitCommand`；坏格式/空词表/历史只读/重复点击零写。
- 拒绝/冲突不显示成功、不自动重试；迟到成功在换分支后显示「已写入 main@2；当前游标 b@1」，不冒充当前成功。
- 创建缺失格：只提交读取时差集；读取期间换分支/换源图/发送前目标变化都不提交旧差集；在途重复点击只读一次、只写一次。
- 前端写门：`modes.js` 只在 `map-edit` 放行两命令；`map.js` 只在 `image-import` 子工具放行，terrain/connectivity 线拒绝。
- 公共 `app.js writeCommand`：正常成功刷新并前进；换分支/换同分支历史 revision 后迟到成功/409 不拉走游标；正常 409 刷新到服务端 head 且不自动重试；422 原样返回 reason。

## 3. 精确清单同步（以实际源码/工具声明交叉核对）

| 契约 | 旧 | 新 | 落点 |
|---|---:|---:|---|
| GM 桶 | 189 | **192** | `ShellAssemblySmokeTest`、`ToolClassAuditAssemblyTest` |
| 决策人桶 | 64 | **64（不变）** | 同上 |
| 窄写工具类 | 68 | **70** | `SimosToolsTest.everyNarrowWriteToolClassIsWiredIntoTheGmBucket` |
| 全磁盘 `*Tool.java` | 179 | **182** | `SimosToolsTest.everyToolClassOnDiskIsRegisteredInSomeBucket` |
| 命令 handler | 151 | **153** | `SimosToolsTest`（扫描/catalog） |
| `CommandTargets` 样本 | 101 | **103** | `AdjudicateTickToolTest` |
| `GM_ONLY` | 138 | **141** | `ToolClassAuditAssemblyTest` |
| 两桶并集 | 202 | **205** | 同上 |
| MCP `tools/list` 精确集合 | 189 | **192** | `McpServerTest`、`McpPortTopologyTest` |
| 参考图读工具登记 | — | **`simos.map.reference`** | `READ_TOOL_NAMES`、`GM_ONLY_READ_NAMES`、最小形状、MCP 精确集 |
| 窄写名单 | — | **`map.PaintTerrain` / `map.ConnectHexes`** | `NARROW_WRITE_NAMES`、`MAP_WRITES`、GM 构造表 |
| 命令类型/catalog | — | **+2** | `SimosToolsTest.EXPECTED_COMMAND_TYPES`、`McpCoverageTest.EXPECTED_COMMAND_TYPES` + 合法最小载荷 |
| 前端文件清单/下界 | 456 | **482** | `gate-contract.test.cjs` 的 `REQUIRED_FILES`/`MIN_ASSERTIONS`、`run-gate.cjs` 的 `MIN_TESTS` |
| 模块 manifest | version 2 / 4 资产 | **version 3 / 5 资产** | `WebuiAssetsTest` |

自洽核对：GM 桶 = `GM_ONLY 141 + SHARED 51 = 192`；并集 = `141 + 25 + 39 = 205`；MCP 精确集 = GM 桶（192）；full 装配日志同步显示 `handler=153 tool=192`。没有把精确断言改成 contains/移除数字；没有把 partial full 数字相加当总数。

## 4. 实际命令与结果

### 4.1 返修后的 focused 复验

| 命令 | rc | 真实结果 | 日志 |
|---|---:|---|---|
| `tools/gate.sh focused simos-map -Dtest=MapReferenceServiceTest,RegressionGuardsTest test` | 0 | **18 tests / 0 failures / 0 errors / 0 skipped**（ReferenceService 9 + RegressionGuards 9） | `/tmp/refocus-map-postspotbugs.log` |
| `tools/gate.sh focused simos-app -Dtest=SimosToolsTest,McpServerTest,MapReferenceEndToEndTest test` | 0 | **75 tests / 0 failures / 0 errors / 0 skipped**（68 + 6 + 1） | `/tmp/refocus-app-postspotbugs3.log` |
| `tools/mvn-lock.sh -pl simos-app -am clean compile spotbugs:check` | 0 | 19 个 reactor 项目 BUILD SUCCESS；`MapSimos` 4.442s、`EconomySimos` 20.963s、`SimosApp` 14.702s 全 SUCCESS | `/tmp/spotbugs-clean-am-check.log` |

说明：先跑的 `tools/mvn-lock.sh -pl simos-app -am spotbugs:check`（非 clean）在 `MapSimos` 0 bugs，但 `EconomySimos` 报 5 个旧类分析结果（`/tmp/spotbugs-map-app-check.log`），与 20:46 的 clean full 中 `EconomySimos` 0 bugs 不一致。用 `clean compile spotbugs:check` 清掉旧 `target` 后 19 个项目全过；最终 full 的 clean verify 也为 0 bugs。因此那 5 条是**非 clean 直接目标的旧类/类路径分析结果，不是当前源码的生产 finding**；没有据此改生产或吞检查。

### 4.2 更早的 focused 覆盖（同样全绿）

- `tools/gate.sh focused simos-map -Dtest=MapReferenceServiceTest,PaintTerrainHandlerTest,ConnectChainOperationsTest,ConnectHexesHandlerTest,RegressionGuardsTest test`：**38 tests / 0 failures**（`/tmp/focus-map-final.log`）。
- `tools/gate.sh focused simos-app -Dtest=McpServerTest,SimosToolsTest,MapReferenceEndToEndTest test`：**75 tests / 0 failures**（`/tmp/focus-app-final.log`）。
- 受影响清单测试更早一轮：`ShellAssemblySmokeTest`、`ToolClassAuditAssemblyTest`、`McpPortTopologyTest`、`McpCoverageTest`、`AdjudicateTickToolTest`、`WebuiAssetsTest`、`RasterImportEndToEndTest`、`GuiApiTest`、`MapHexEdgesApiTest` 等合计 **156 tests / 0 failures**（`/tmp/focus-app4.log`）。
- JS：`node simos-app/src/test/js/run-gate.cjs` **482/482**（`/tmp/js-gate-final.log`）。

### 4.3 最终 full gate

| 项 | 真实证据 |
|---|---|
| 命令 | `tools/gate.sh full`（无 args、无 skip） |
| rc / 结论 | **0 / PASS** |
| 主日志 | `/tmp/simos-reference-full-green.log` |
| verify / validate 明细 | `/tmp/simos-gate-full-verify-4k2CyK.log`、`/tmp/simos-gate-full-validate-z83V3Q.log` |
| 总耗时 | **281s**（Maven verify 04:38 min） |
| 19 个 reactor 项目 | 全部 `SUCCESS`（父 POM + 18 个 `simos-*` 模块）；`SimosApp` SUCCESS 02:50 min |
| Java tests | **3835 tests / 0 failures / 0 errors / 7 skipped** |
| fresh Surefire XML | **515 个报告**，逐 XML 合计 tests=3835 / failures=0 / errors=0 / skipped=7；`clean verify` 后生成 |
| 前端门禁 | **`[frontend-gate] OK tests=482 pass=482 fail=0`** |
| 插件证据 | `spotless:check`、`checkstyle:check`、`spotbugs:check @ simos-app`、`exec (frontend-unit-tests) @ simos-app`、`shade (shade-executable) @ simos-app`、`enforcer (enforce-module-boundaries) @ simos-util` 均真跑 |
| shaded jar | `simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，**32,221,686 字节** |
| 7 条 skip | 既有真实模型/门禁外用例，未新增隐藏 skip；full 的 surefire 汇总为 7 skipped |
| 护栏 | 7 条 `ArchitectureGuardsTest` / `GaRoundTripTest` / `GovRoundTripTest` / `ArmyRoundTripTest` / `ShellAssemblySmokeTest` / `ProductionRoundTripTest` / `CultureRoundTripTest` 均 tests>0、0 failures、0 errors |

## 5. 变异反证与 SHA 还原

变异目标：`simos-map/src/main/java/io/mosire/simos/map/ops/EdgeOperations.java` 的路径邻接判定。

- 变异前备份与 SHA-256：`f1db05546e8756a3bf34b1ee02e4fc91ba93af4a534f6a202445beaf263cb937`。
- 变异：把 `adjacent(...)` 改成恒 `true`（去掉非邻接拒绝/极值溢出保护），变异体 SHA：
  `8665441aff6ec0e899435a341a47ee68a76646990ea94195b1e598e2a9edd717`。
- 当场红：`tools/gate.sh focused simos-map -Dtest=ConnectChainOperationsTest test` rc=1，
  `Tests run: 8, Failures: 2`；红在
  `rejectsSelfLoopsJumpsOutOfMapUnknownKindBadOpAndShortOrLongChains`（跨格跳跃不再拒）与
  `extremeCoordinatesCannotOverflowIntoTreatingDistantCellsAsAdjacent`（极值不再拒）。日志 `/tmp/mutation-connect.log`。
- 还原：从 `/tmp/mutation-EdgeOperations.java.bak` 覆盖，SHA 回到
  `f1db05546e8756a3bf34b1ee02e4fc91ba93af4a534f6a202445beaf263cb937`，`cmp -s` rc=0。
- 随后控制方为 L3 修复删除了 `NEIGHBOR_DELTAS` 并复用 `HexDirection.ALL`（生产账本 §6.1）；当前生产文件 SHA 为
  `00f18119dbe3425102bbae6f2c5cb25bc550a1a78526adc8a8fb57f911ce8f69`。测试责任区**没有**再改该文件，也没有重复变异。

## 6. 历史 full finding 与修复证据（保留）

### 6.1 L3 方向表重复（已修复）

- 前次 full 在 `RegressionGuardsTest.L3_thereIsExactlyOneDirectionTable` 红：`EdgeOperations` 为 wrap 邻接新增的 `NEIGHBOR_DELTAS int[][]` 被守卫判为第二份方向表（`/tmp/simos-reference-full.log:379` 起）。
- 控制方按实现账本 §6.1 删除该表，改为遍历 `HexDirection.ALL`，保留 long 加法与 `floorMod` 规范化；未改测试/守卫。
- 本轮**明确复跑** `RegressionGuardsTest`（未改其断言）：**9 tests / 0 failures**；新增 `ConnectChainOperationsTest` 的环绕/极值用例也全绿。

### 6.2 4 项 SpotBugs 生产 finding（已修复）

前次 full 在 `simos-map` 被 SpotBugs `BugInstance size is 4` 挡住（`/tmp/simos-reference-full-final.log:482`、`/tmp/simos-gate-full-verify-n6XB9P.log`）：

| 级别 | 文件:行 | 规则 | 修复 |
|---|---|---|---|
| Medium | `MapReferenceService.java:328` | `DLS_DEAD_LOCAL_STORE` | 删除死赋值，保留 `readSummary(referenceId);` 的存在性/读取校验 |
| Medium | `MapReferenceService.java:208` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | 新增窄 helper `fileNameOf(Path)`，局部 `Path` 判空后转名 |
| Medium | `MapReferenceService.java:212` | 同上 | `list()` 的 filter/forEach 都经 helper 并显式跳过空名 |
| Low | `MapReferenceTool.java:144` | `FE_FLOATING_POINT_EQUALITY` | 改用 `BigDecimal.intValueExact()`；小数/非有限/越界仍严格拒绝 |

返修后没有关规则/加宽抑制；`clean compile spotbugs:check` 与最终 clean full 都 0 bugs。测试侧只把整数参数断言从旧「必须是整数」同步到新「必须是 int 范围内的精确整数」/「超出 int 范围」，仍断言失败，不截断。

## 7. 未做 / 未验证 / 交回

- **NOT_RUN（控制方范围）**：真实浏览器操作/截图、真实整张非洲之角图的判图可读性、保留本地 WebUI 演示服务、跨真实浏览器重启的点格流程。DOM 替身测试**不声称**真实浏览器已跑。
- **NOT_RUN**：真实模型自主绘完整图/多年 AI 编排；不属于本测试责任区。
- **未做**：commit/push、STATUS 修改、生产修复、额外老任务、派代理、启动常驻服务、读取账号密钥。
- **交回状态**：最终 full PASS 后测试责任区已停写；没有遗留需要测试侧继续处理的生产缺陷。
