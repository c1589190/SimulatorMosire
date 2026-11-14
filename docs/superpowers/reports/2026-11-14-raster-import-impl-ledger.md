# 底图导入实现责任区账本

> 父序列：[底图识别与六角地图导入目标运行设计](../specs/2026-11-14-raster-import-runtime-design.md) §1 R0–R5；写入仍嵌父设计 S2。
> 当前状态：**生产收尾已交回**（2026-11-14 20:23）。本责任区只写生产代码/资源与账本；不写/改测试、不运行 `test/verify/package`、不 commit/push。
> 交回判据仅是 scoped spotless、JS `node --check`、`-DskipTests compile` 通过；**不等于验收通过**。

目标运行段：本地底图解码与草稿 → 六角区域采样 → 独立画布识别/修整 → `map.ImportRaster` 单命令 → 原子地图变更 → 地形刷修整。map 模块拥有命令、操作、工具和识别前端；app 只装配及接线。

## 1. 父序列位置与嵌入点

- 父序列锚点：`2026-11-14-raster-import-runtime-design.md` §1 R0–R5；父设计 S8 扩为 R0–R5，写入仍嵌父序列 S2。
- 本责任区：R0–R5 的单一生产实现，覆盖 map 后端导入、map 前端算法/流程/样例、工作台宿主接线、`blank-editor` 与组合根注册。
- 状态所有权：图像/预览/人工修整/IndexedDB 草稿归 map 前端；`GameMap` 与导入命令归 `simos-map`；app 只注册/装配/桥接。
- 非目标：不恢复经济/生产/文化；不接模型；不做真实 DEM/OCR/全球投影；不清空用户世界；不改旧 CLI 导入器。

## 2. 实际实现（按 R0–R5）

- R0/R1：工作台 `map-edit` 的 `image-import` 子工具、宽 `<dialog>` 工作区、内置非洲之角样例、32MB/32M 像素拒绝、对象 URL 释放、IndexedDB 按 origin+branch+mapId 保存原图/参数/修整；恢复不保存可提交计划。
- R2：独立 `map-raster-core.js` 做整六角区域采样；晕渲按格内蓝水占比 + 陆地纹理、灰度按方向/黑白校准/海陆线、颜色图例按容差；生成图幅/当前格网、裁剪、起点、12–160 列、最多 12000 格；地形词表/颜色取当前地图 overview。
- R3：独立 Canvas2D + HexGeom 预览、原图/叠加/结果、统计与不确定格、点格修整；预览冻结 `branch/revision/tick/writeEpoch`，参数/图像/节点/分支/写代次变化使旧计划失效；确认前零写入。
- R4：`map.ImportRaster` handler/领域操作/GM 窄工具/catalog 提示/注册；`patch` 只改现格，`merge` 新增/覆盖并保留图外、其他组件和原格土地树；环绕地图扩展拒绝；完整校验后一次 `new GameMap` 再 `MapChangeSet.between`；不实现 `DmUsableCommand`。
- R5：成功后进入主图地形刷并聚焦导入范围；底图可叠加；`blank-editor` 只组装十个空领域快照、标准地形/通路词表，不调用业务播种器。

## 3. 两项 P2 的收尾处理

### P2-1 图片加载与参数/恢复代次分离

- `loadToken` 只负责图片解码/新图载入；`parameterVersion` 只负责识别参数；`restoreVersion` 只负责恢复上下文/新图选择/清除/分支变化。识别参数 input 只递增 `parameterVersion` 并 `invalidate` 计划/在途预览，**不再递增 `loadToken`**，因此选大图 B 后立即调参数不会取消 B 的解码。
- 普通新图载入只在解码成功时替换 `image/blob/pixels/sourceVersion`；参数变化不会把旧图 A 静默当成 B，也不会让旧图覆盖新图。载入失败保留旧图时明确提示“保留此前底图，需重新识别”，没有静默继续。
- 异步草稿恢复的启动只看 `draftKey/loadToken/restoreVersion`，不看 `parameterVersion`；`parameterVersion` 仅决定是否回填草稿参数：用户在恢复期间改过参数 ⇒ **不回填旧参数**，但仍恢复原图与修整，并提示“已恢复底图，保留本次编辑的参数；请重新识别”。恢复期间若用户换图/清除/换分支，恢复直接放弃，不覆盖新选择。
- 恢复仍只回原图、参数和人工修整，不恢复可直接提交的旧计划；`node --check` 已过，浏览器生命周期留统一验收。

### P2-2 在途导入锁定与成功/拒绝回调隔离

- `apply` 在发命令前设置 `applying`，`lockWorkspace` 快照并禁用工作区全部可变更控件（文件、示例、清除、参数、识别、点格、显示、检查确认、应用、关闭、进入修整等），同时禁用画布指针并给 dialog 加 `aria-busy`；`cancel`（Esc）在 `applying` 时 `preventDefault`。
- `clear/loadExample/preview/open/loadSource/pickPreview/review/refine/close/onToolChange` 及文件 change、确认勾选、参数 input 均先判 `applying`；参数 input 在锁定期还会回填 `activeSubmission.parameters`，不修改草稿。`openWorkspace` 另加 `contextLoading` 保护，避免重复打开产生第二个上下文/恢复竞态。
- `captureSubmission` 冻结本次确认的 `plan`（含 assignments/options/overlay）、`image/blob/fileName/sourceVersion/draftKey`、草稿记录、参数、`branch/revision/tick`。成功回调先 `persistDraft(submission.draftKey, submission.record, submission.sourceVersion)` 写回确认时的源/键；`submissionMatchesCurrent` 再核对 branch/revision/tick、图像对象、`sourceVersion`、`draftKey` 与 `isWritable`。不匹配则清 overlay、清旧计划并提示“已提交至确认时的节点；当前目标已变化”，不把 A 计划配到 B 图上。
- 成功/拒绝/异常都在 `finally` 清 `applying/activeSubmission`，按快照恢复按钮原 disabled 状态，再由 `refreshAvailability` 重算识别/检查/应用三键；成功进入完成态，拒绝/冲突保持待审草稿并禁用旧计划，不自动重试。
- 外部分支/节点变化：非在途时由 `signature()` 失效旧计划，分支变化清 `draftKey` 并重置图像；在途时只标记 `foreignContext`，由 `finally` 统一重置，旧草稿写入始终使用确认时的 key。`applying` 分支同步更新 `lastBranch`，避免 apply 结束后用旧分支值再次误清另一份草稿。

## 4. 对父序列的影响

| 维度 | 影响 |
|---|---|
| 顺序 | R0–R5 与父序列一致；没有新增阶段；确认写入仍是 S2 的单个 `map.ImportRaster` 命令。 |
| 数据所有权 | 图像/预览/修整/草稿仍归 map 前端；`GameMap`、导入操作与工具归 `simos-map`；app 只装配、分桶和注册。 |
| 身份/持久化 | hex q/r 不重编号，实体 ID 不重建；地图仍走 `Command → ChangeSet → Revision` 与 checkpoint；草稿只进 IndexedDB；没有新服务端文件系统。 |
| 预算/冻结 | 不调用模型、不调用经济播种器；预览冻结 branch/revision/tick/writeEpoch，应用前比较实时游标。 |
| 失败/原子性 | 全载荷先校验，再由完整 target 派生 `MapChangeSet.between`；拒绝无部分写入；冲突不自动重试；在途锁定工作区变更与关闭。 |
| 日志 | 仅记录导入 mode、格数、新增/更新、拒绝原因；不记录图片像素或 Blob。 |
| 验证 | 本责任区只做 scoped spotless、JS 语法与 compile；统一测试、真实浏览器、重启回读由下一责任区执行。 |

## 5. 生产文件清单

map 模块：

- `simos-map/src/main/java/io/mosire/simos/map/ops/RasterImportOperations.java`（新增）
- `simos-map/src/main/java/io/mosire/simos/map/spi/ImportRasterHandler.java`（新增）
- `simos-map/src/main/java/io/mosire/simos/map/tools/write/MapImportRasterTool.java`（新增）
- `simos-map/src/main/resources/webui/modules/map/map-png.js`（重写为导入工作区流程）
- `simos-map/src/main/resources/webui/modules/map/map-raster-core.js`（新增，纯函数）
- `simos-map/src/main/resources/webui/modules/map/map-raster-draft.js`（新增，IndexedDB 草稿）
- `simos-map/src/main/resources/webui/modules/map/manifest.json`（登记资源）
- `simos-map/src/main/resources/webui/modules/map/examples/horn-of-africa.png`（用户截图原样副本）

app 组合根/宿主：

- `simos-app/src/main/java/io/mosire/simos/app/world/BlankEditorWorld.java`（新增）
- `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（注册 handler）
- `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`（装配 GM 窄工具）
- `simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java`（载荷提示）
- `simos-app/src/main/java/io/mosire/simos/app/world/WorldRegistry.java`（登记 `blank-editor`）
- `simos-app/src/main/resources/webui/index.html`、`map-mapeditor.js`、`map.js`、`modes.js`、`styles.css`、`map.html`（工作区/子工具/写白名单/样式/只读页指路）

后端审阅：`RasterImportOperations`、`ImportRasterHandler`、`MapImportRasterTool` 的形状与校验符合 R4；本轮未扩大重写。原有地图环绕、寻路、renderer 等 dirty 原样保留。

## 6. 验证记录（本责任区实际执行）

- 定点格式化：`tools/mvn-lock.sh -q -pl simos-map,simos-app spotless:apply -DspotlessFiles='.*/simos-(map|app)/src/main/java/io/mosire/simos/(map/(ops/RasterImportOperations|spi/ImportRasterHandler|tools/write/MapImportRasterTool)|app/(world/BlankEditorWorld|world/WorldRegistry|Shell|tools/SimosToolSource|tools/read/CatalogTool))\.java'`，rc=0；同范围 `spotless:check` 先通过，apply 幂等，未扫到测试。
- JS 语法：`node --check` 对 `map-png.js`、`map-raster-core.js`、`map-raster-draft.js` 均通过。
- 编译：`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am`，rc=0，耗时 7s（Maven 汇总 6.113s），`BUILD SUCCESS`；日志 `/tmp/simos-raster-compile-final.log`。
- 未运行任何 `test/verify/package`；未启动浏览器/HTTP/服务；未做重启回读；未 commit/push。

## 7. 未做 / 未验证 / 停止点

- 未写/改/跑测试。旧测试中固定子工具数量、工具/世界清单、PNG 模块依赖和旧 3×3 语义需要统一测试责任区按新设计更新；不得当已验收。
- 未做真实浏览器样例识别、点格修整、确认、拒绝/冲突、Esc 锁定、IndexedDB 刷新恢复与清除、`blank-editor` 重启回读；这些是 §5 验收项，当前均未验证。
- 未跑聚焦/完整门禁、前端门禁、full gate；未验证 12000 格边界、透明/混合海岸、灰度反向、颜色阈值 0 等负向用例。
- 生产收尾停在此处：下一责任区按 R0–R5 §5 统一写测试并执行浏览器/重启验收；本责任区不再继续测试、不派代理、不操作浏览器、不提交。
