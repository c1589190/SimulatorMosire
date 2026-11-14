# 底图导入统一测试报告

> **状态**：统一测试完成（2026-11-14 20:46 CST）；`tools/gate.sh full` PASS。真实浏览器/截图仍由控制方独立验收。
> **父序列**：[底图识别与六角地图导入目标运行设计](../specs/2026-11-14-raster-import-runtime-design.md) R0–R5、§3 影响表、§5 验收。
> **实现账本**：[底图导入实现责任区账本](2026-11-14-raster-import-impl-ledger.md)。

本责任区沿“本地图片与草稿 → 识别/修整 → 单命令确认 → 原子地图写入 → 关壳重开回读”验证。只改测试、测试资源/清单/数量契约与本报告；生产实现保持停写。结论：**测试目标完成，未发现需要交回修复的真实生产缺陷**；浏览器内真实图片解码、视觉识别质量、真实 IndexedDB 刷新与截图不在本轮执行范围。

## 1. 源码基线

| 项 | 事实 |
|---|---|
| 分支 / HEAD / upstream | `feature/d036-node-editor` / `299bfc6f` / `299bfc6f`（本轮无 commit，ahead=0） |
| 生产 compile（控制方交回） | `tools/mvn-lock.sh -DskipTests compile -pl simos-app -am`，rc=0，Maven 6.113s；日志 `/tmp/simos-raster-compile-final.log` |
| 本轮生产改动 | **无**。变异实验临时改 `RasterImportOperations.java` 后已按 SHA-256 完全还原（§5） |
| 工作树 | 保留开工前 dirty 与控制方生产实现/文档；未 commit、未 push、未起浏览器/服务 |
| 测试代码 | map 域/handler、app blank-editor/端到端/资源契约、JS 独立算法与生命周期测试；同步精确工具/命令/资源数量契约 |

## 2. 覆盖与真实行为

### 2.1 simos-map：域操作、handler、编解码

新增 `RasterImportOperationsTest`（7 tests）与 `ImportRasterHandlerTest`（5 tests），按真实生产接口而不是旧命令语义：

- `merge` 新增 + 覆盖：新增格土地树为空；已有格 `withHeight` 保留 `HexLand`；图外格、区域、城市、通路、通路组、边与 spec 逐项不变；地形块 `requirePartition` 且等于按最终全图重切的结果；完整 `MapChangeSet.between(base, target)` 往返回 target。
- `patch` 只改点名格，未点名格逐字段保持；对同值 patch 产生**空变更集**（不是重复写）。
- 重复坐标整体拒绝，不允许 last-write-wins；`height` 的 -0.001/1.001/NaN/±Infinity 全部拒绝，且合法前项不会先落盘。
- 未知 terrain、patch 图外格、非法 mode、空 cells、12001 格整体拒绝；1..12000 且高度 0/1 端点接受；12000 格真实构造并读回。
- 环绕图允许更新现格、拒绝新增键；wrap 不变。
- handler：`targetPaths` 逐格声明真实资源路径 `Map1/hex/-2_3`；非法 q/r（小数、int 溢出、字符串、null、布尔）不截断；非数值/越界 height、坏 JSON、非对象、重复坐标全部 `Rejected`；完整 ChangeSet codec 往返与 MapSnapshot 往返；不实现 `DmUsableCommand`。
- `MapCodecTest` 增加 wrap spec 随 MapSnapshot 往返；`MapWrapTest` 3 tests（既有 dirty 的环绕语义）随套件执行。
- 实测：`simos-map` 全模块 **417 tests / 0 failures / 0 errors / 0 skipped**。

同轮同步 `RegressionGuardsTest` 的 `new GameMap(` 冻结白名单：R4 要求“一次构造完整 GameMap 再 `MapChangeSet.between`”，`RasterImportOperations` 是设计允许的第 4 个登记点，不是放宽守卫。

### 2.2 simos-app：blank-editor、GM-only、单命令重启回读

新增 `BlankEditorGenesisTest`（2 tests）：

- 真 `Shell` + 真 `bootstrapGenesis` + 真 checkpoint 重放；`blank-editor` 恰好十个空领域快照；map 零格但标准 terrain/pathway 词表在；social populations/cities/groups/households、economy、production、culture 全部为空；重放状态与 `BlankEditorWorld.state` 逐字段相等。
- 产品缺省 world 仍是 `v17levant`（描述含 59223），`blank-editor` 只是新增选择，不替换旧世界。既有 `ShellMainGenesisTest` 的 59223 格/非空库不覆盖断言继续通过。

新增 `RasterImportEndToEndTest`（3 tests）：

- 真 GUI `POST /api/command` 提交一次 `map.ImportRaster`（两格）⇒ 恰一条 revision；`GET /api/map/hex` 读回 0.37/0.8125 与 plains/mountains；overview 两格、两个地形块各 1 格；**关壳重开同一 store** 后 revision 数、高度、地形、块 JSON 逐字一致；`shell.coreSimos().replay` 再做 `TerrainBlocks.requirePartition`。
- 重复坐标真命令返回 **422**，revision 不变（仍为 1）、地图零格、该格 GET 404：失败不部分写入。
- GM 桶含 `map.ImportRaster`，决策人桶既无导入也无 `map.SetTerrain`：真实分桶的 GM-only，不是仅 handler 不自称。

`ShellMainParseTest` 增加 `--world=blank-editor` 解析与未知 world 具名拒绝；`WebuiAssetsTest` 增加 map 模块 manifest `version=2`、四个资产在册且 classpath 可读（模块数量仍 1）。精确契约同步：

- `ShellAssemblySmokeTest` GM 188→**189**；`ToolClassAuditAssemblyTest` GM_ONLY 137→**138**、UNION 201→**202**、GM 188→**189**；`SimosToolsTest` MAP_WRITE/窄写/EXPECTED_COMMAND_TYPES/真工具构造表加 `map.ImportRaster`，扫描 150→**151**、GM 188→**189**、窄写 67→**68**、磁盘 Tool 178→**179**；`McpServerTest`/`McpPortTopologyTest`/`McpCoverageTest` 精确集合与合法 patch 样本；`AdjudicateTickToolTest` 的 CommandTargets 样本表 100→**101**（导入逐格目标 `MAP_ID/hex/1_3`）。
- 实测：`simos-app` 全模块 **1084 tests / 0 failures / 0 errors / 7 skipped**（7 条为既有 `RealLlm*` 实模型用例，本轮未启用）；同轮前端门禁 **455/455**。

### 2.3 前端：算法与生命周期

新增 `map-raster-core.test.cjs`（12 tests，合成像素可判定）：

- 整六角内部采样：只在中心 3×3 留绿色、其余六角内部涂红，中位色必须来自边缘多数区域（中心取色的实现会红）。
- 透明/图外样本跳过；`relief` 用格内蓝水占比 + 陆地纹理：纯水=ocean、平滑陆=plains、高频明暗=mountains；3 像素宽细河不整格涂海。
- `height` 灰度：黑低白高、反向、黑白点校准、海陆线；高度落在沙漠带也不自动判沙漠；坏黑白点/海陆界拒绝。
- `palette` 阈值 0：颜色不相近 ⇒ 0 格且 skipped，**不兜默认地形**；精确颜色 ⇒ distance=0 接受；负阈值拒绝。
- 词表缺 plains 时 relief 跳过而非兜默认；生成图幅 12..160 整数、起点/裁剪、pointy-top 轴向平移、12000 上限真实触发拒绝；现有格网 offset/scale 映射用左右半图判别；generate=merge、existing=patch 与新增/覆盖计数。

新增 `map-raster-lifecycle.test.cjs`（14 tests，真实事件 + 可控 Promise + IndexedDB 替身，不做源码扫描）：

- **P2-1**：选图后改参数不取消正在解码的新图；A→B 快速换图时 A 的旧 `Image.onload` 不覆盖 B。
- **P2-2**：导入在途锁定工作区 DOM（文件/清除/关闭/确认/修整等 disabled、canvas `aria-disabled`、dialog `aria-busy`）；`clear/preview/loadExample/open` 公开操作与文件 change 全部拒绝；dialog `cancel` 被 `preventDefault`；成功/拒绝后按快照解锁；成功进入完成态、拒绝保留待审计划且不报成功；旧 preview 响应不能在参数变化后恢复应用按钮；旧 apply 响应不能把已切换的上下文标成完成。
- 参数、branch、tick、revision、writeEpoch 任一变化都使冻结预览失效；非 map-edit、历史节点（revision≠head）、非 `image-import` 子工具提交 0 条命令并使计划失效。
- 确认前 **0 次** `writeCommand`；确认后恰 **1 次**完整命令，cell 只含 `q/r/terrain/height`；成功后“进入地形修整”真的切回 terrain 刷、关工作区并 `setView` 对准图幅；示例按钮重置为 relief+generate 并载入示例名。
- IndexedDB 替身：恢复原图/参数/人工修整但 `plan=null` 且 apply 禁用；清除删除 `origin|branch|mapId` 键并清图；保存失败明确“仅本次有效”；32MB/32M 像素拒绝且不产生草稿。
- 既有颜色图例兼容保留（`map-png.test.cjs` 仍断言 palette 最近色/阈值/跳过）；`modes.test.cjs` 与 `map-edit-suboptions.test.cjs` 断言 `image-import` 只允许 `map.ImportRaster`，其他子工具/模式仍拒。

## 3. 实际命令与结果

| 命令 | rc | 真实结果 | 日志 |
|---|---|---|---|
| `tools/gate.sh focused simos-map test` | 0 | 417 tests / 0 failures / 0 errors / 0 skipped | `/tmp/simos-raster-map-all2.log` |
| `tools/gate.sh focused simos-app test` | 0 | 1084 tests / 0 failures / 0 errors / 7 skipped；前端 455/455 | `/tmp/simos-raster-app-all2.log` |
| `tools/mvn-lock.sh -pl simos-map,simos-app -am spotless:check` | 0 | BUILD SUCCESS | `/tmp/simos-raster-spotless-finalcheck.log` |
| `tools/gate.sh docs` | 0 | DOCS CHECK PASSED / SKILLS LINT PASSED | `/tmp/simos-raster-docs-gate.log` |
| `tools/gate.sh full` | **0** | clean verify + validate，289s；见 §4 | `/tmp/simos-raster-gate-full.log` |
| 变异轮（§5） | 1（预期红） | 2 个行为测试失败 | `/tmp/simos-raster-mutation-run.log` |

## 4. full gate 真实证据

- `tools/gate.sh full` **无参数、无 skip**，rc=0，耗时 **289s**；完整日志 `/tmp/simos-raster-gate-full.log`。
- `clean verify` rc=0（`/tmp/simos-gate-full-verify-DYiklO.log`），`-DskipTests validate` rc=0（`/tmp/simos-gate-full-validate-cjGZJg.log`）。
- 独立重算本轮 510 个 Surefire XML（全 `clean` 后新报告）：**tests=3801 / failures=0 / errors=0 / skipped=7**；跳过项仅为既有 `RealLlmGovScenarioTest`（3）与 `RealLlmUnitDecisionLoopTest`（4）。
- 前端门禁真实执行：`[frontend-gate] OK tests=455 pass=455 fail=0`。
- 插件真实执行：`spotless:check`、`checkstyle:check`、`spotbugs:check`、`exec (frontend-unit-tests)`、`shade (shade-executable)`、`enforcer` 均在 verify 日志出现；shaded jar 存在且为 32,152,500 字节（`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`）。
- 7 条护栏逐类 `tests>0 / failures=0 / errors=0`，含真构造 `Shell` 的 `ShellAssemblySmokeTest`（3 tests）。

相关本轮 XML（独立读取）：`RasterImportOperationsTest` 7、`ImportRasterHandlerTest` 5、`MapCodecTest` 8、`MapWrapTest` 3、`BlankEditorGenesisTest` 2、`RasterImportEndToEndTest` 3、`ShellMainParseTest` 9、`WebuiAssetsTest` 11、`SimosToolsTest` 65、`ToolClassAuditAssemblyTest` 5、`McpCoverageTest` 1、`McpServerTest` 5、`McpPortTopologyTest` 4、`RegressionGuardsTest` 9、`AdjudicateTickToolTest` 16，全部 failures=0/errors=0。

## 5. 关键变异与完全还原

选择“移除重复坐标拒绝”作为行为反证：

| 阶段 | SHA-256 | 结果 |
|---|---|---|
| 变异前 | `e4ec0fd675ff86e57f1ed3f33f8bd66a809c3db7a8662b48ee390224617428a8` | 基线生产文件 |
| 变异后 | `ad92e3c608e243a0772c77cf6b6eb0250b12a333dc6304186c37bbddbaff829f` | 把“重复即抛”改成 `seen.add` |
| 还原后 | `e4ec0fd675ff86e57f1ed3f33f8bd66a809c3db7a8662b48ee390224617428a8` | 与备份 `cmp` 逐字节相同 |

- 命令：`tools/gate.sh focused simos-map -Dtest='RasterImportOperationsTest,ImportRasterHandlerTest' test`，rc=1（预期红），日志 `/tmp/simos-raster-mutation-run.log`。
- 红得具体：`RasterImportOperationsTest.duplicateCoordinatesAreRejectedInsteadOfLastWriteWinning` 报 “Expecting code to raise a throwable”；`ImportRasterHandlerTest.malformedPayloadsAndDuplicateCoordinatesAreRejected` 报实际为 `Applied`、期望 `Rejected`，并显示后写覆盖前写后的 `0_0=HexCell[height=0.1]`。共 2 failures / 0 errors。
- 还原后未留变异；随后 `simos-map` 全模块与 full gate 均以还原后的文件通过。
- 补充观察：另试过把 height 上界检查 1 改成 2，但 `HexCell` 自身还有 0..1 守卫，行为未变、测试仍绿，故**不作为反证**；未留下改动。这说明高度边界目前是两层防御，单层移除不改变用户可见行为。

## 6. 未覆盖 / NOT_RUN

- **真实浏览器**：Chromium 中实际 PNG/JPEG 解码、Canvas 取像素、用户非洲之角样例的识别轮廓、点格修整、确认后的视觉刷新、截图与命令回读——由控制方按 §5 独立验收；本轮 JS 测试用可控 DOM/Image/IndexedDB 替身，不等于浏览器验收。
- **真实 IndexedDB**：刷新页面后的草稿恢复/清除只以替身验证键和调用，未在真实 origin 上重启浏览器复验。
- **真实 12000 格 UI 交互**：域层构造 12000 格已跑；浏览器内 12000 格识别/绘制未跑。
- **外部 MCP 客户端**：工具面/命令目录/真实 Shell HTTP 写已测；未另起外部 MCP 会话调用 `map.ImportRaster`。
- **识别质量**：合成像素只验证确定性规则与负例，不评价样例图的真实地形准确度（设计明确为近似识别，伪造 DEM/OCR/河网不在范围）。
- 本轮不调用模型、不 commit/push、不操作浏览器；7 条既有 `RealLlm*` 跳过不是本轮新增。

## 7. 结论与交回

- 统一测试完成，R0–R5 的可自动化部分有真实行为回归：域往返/拒绝、GM-only 注册、blank-editor 空切片、单命令重启回读、算法/生命周期/草稿降级、完整前端与 full gate。
- **未发现真实生产缺陷**，因此没有需要交回生产修复的 findings；仅同步了测试自身的清单/数量/构造点契约（`RegressionGuardsTest`、`AdjudicateTickToolTest` 等），未改生产以吞缺陷。
- 控制方剩余事项：真实浏览器样例识别与修整、IndexedDB 刷新恢复/清除、截图与重启回读证据；按项目规则更新 `STATUS.md`。本责任区在交回本报告后停止。
