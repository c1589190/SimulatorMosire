# 自由判图工具生产责任区实现账本

> **日期**：2026-11-14
> **责任区**：单一生产责任区（simos-map + simos-app 宿主接线 + 模块前端）
> **父锚点**：`docs/superpowers/specs/2026-11-14-map-reference-tools-design.md` T0–T4、§3 影响表、§4 接口、§5 验收
> **计划**：`docs/superpowers/plans/2026-11-14-map-reference-tools-plan.md`
> **状态**：生产代码到 compile 通过；**未跑测试/未起服务/未 commit/push**。

## 1. 本责任区落点

本责任区在父序列中的位置：

- T0/T1：`simos-map` 的参考图工件服务 + GM-only `simos.map.reference` 读工具；原图/格网/渲染归 map，app 只持有同一服务并做 HTTP 接线。
- T2：`map.PaintTerrain` 命令/handler/结构化窄工具；完整目标图仍由 `RasterImportOperations` 构造，写入仍走 Command → ChangeSet → Revision。
- T3：`map.ConnectHexes` 命令/handler/结构化窄工具；只改 `EdgeRef/EdgeTags`，使用既有 `MapWrap` 邻接语义。
- T4：`simos-map` 模块静态 `map-reference-tools.js` + app HTML/CSS/modes/subtool 写白名单接线；自由工具区不进入旧自动识别的步骤锁。

## 2. 真实实现

### T0/T1 参考图工件与查看

- 新增 `io.mosire.simos.map.reference` 包：
  - `MapReferenceService`：内容寻址参考工件库。目录由宿主注入（生产 = `ArtifactStore.directory()` 相邻的 `map-references`），PNG 发布由注入的 `PngPublisher`（生产 = `ArtifactStore::putPng`），不依赖 app/core。
  - `ReferenceImageIO`：PNG/JPEG 真实解码；先用 `ImageReader` 读文件头尺寸（32M 像素上限）再整图解码；32MiB 文件上限。
  - `ReferenceViewRenderer`：原图概览/局部裁切 + 六角边界 + q/r 标签，可选当前地形半透明叠加；输出边长 ≤1280，PNG ≤2MiB；局部保留邻格原图背景，不生成虚构像素。
  - `MapReferenceViews`：工具与 GUI HTTP 共用的唯一 JSON 形状。
- 参考工件元数据固定绑定：文件名、媒体类型、真实尺寸、图像 sha256、按 q/r 规整后的 `{q,r,imageX,imageY,imageRadius}` 格网。ID = 上述 canonical 元数据 + 图像字节的 sha256；同 id 不覆盖、不偷换图/格网。
- 新增 GM-only 读工具 `simos.map.reference`（`MapReferenceTool`，实现 `GmOnlyRead`，`toolRoles={GM}`）：
  - 无 `referenceId`：列出参考工件；
  - 有 `referenceId`：概览；
  - 有 `referenceId + q/r`：局部（radius 1..8，默认 4，中心必须在参考格网内）；
  - 文本带真实 `branch/revision`、参考图尺寸/格网范围、局部逐格 `built/terrain/height`；概览不回传几千格 cells；
  - PNG 走 `ToolResult` 的 assetIds，经既有 `ArtifactStore`/MCP ImageContent；文本不含 base64。

### T2 直接多格地形

- 新增 `map.PaintTerrain`：`PaintTerrainHandler implements CommandHandler, CommandTargets`；payload `{cells:[{q,r,terrain,height?}]}`。
- 1..12000 格；全部格必须已存在；未知地形/重复坐标/图外/非整数/超 int/height 非有限或越界/显式 `height:null` 全部整批拒绝。
- `height` **字段缺失**才取 `TerrainHeights.paintHeight(terrain)`；显式 null 不再静默当默认（见审阅项 1）。
- 复用 `RasterImportOperations.apply(base,"patch",cells)`：从完整 `GameMap` 派生变更集，保留土地树、区域、城市、通路、边与图外格；GM-only，不实现 `DmUsableCommand`。
- 新增结构化窄工具 `map.PaintTerrain`：顶层 `branch/expectedRevision/cells`；不要求 `payloadJson`。

### T3 有序格链连通性

- 新增 `EdgeOperations.connectChain(base,kind,hexes,op)`：整链先校验后单次 `MapChangeSet.between`。
- 2..12001 点；端点存在；相邻两格才成边；MapWrap 环绕邻接用 long/floorMod 计算，避免 int 溢出误判；自环/重复无向边整批拒绝；闭环允许起点再次出现。
- `merge` 只对点名边 `putIfAbsent(kind,{})`，保留该 kind 既有 props；`remove` 只摘点名边的该 kind，空 tags 边删除；其他 kind、外部边、地形/土地/通路实体不动；不整图 replace、不自动寻路。
- 新增 `map.ConnectHexes` handler（`CommandTargets`、GM-only、不实现 `DmUsableCommand`）与结构化窄工具（顶层 `branch/expectedRevision/kind/hexes/op`）。

### T4 网页自由工具区

- `index.html` 底图导入面板内新增 `#reference-tools`：共享底图、刷新/选择参考图、概览/局部、点图或按行输入坐标、多格地形/可选高度、河流道路 merge/remove、创建缺失格、可复制 LLM 工具参数。
- 明确文案：判图工具**独立于自动识别的编号步骤/确认框**；旧自动识别保留为可选工作区；披露“自动识别在浏览器本地处理；共享参考图时把当前原图上传到本机世界服务”。
- 新增“大图查看”对话框：侧栏图被压缩时仍可在大图读 q/r 并点格（审阅项 9）。
- `map-reference-tools.js` 是 simos-map 的静态模块资产；`map-png.js` 新增只读 `getReferenceInput`（blob/文件名/尺寸/当前 grid，不生成识别 plan）；`map-raster-core.js` 导出既有 `existingGrid`；`modes.js`/`map.js` 只补写白名单。
- 写入统一经唯一的宿主写入口 `SimosApp.writeCommand`（新增可选 `frozenTarget`）；创建缺失格先锁、先冻结目标/源图，读取当前地图差集，只提交缺失格 `map.ImportRaster merge`；冲突/拒绝不自动重试。冻结校验与 `writeCommand` 调用之间无 await。

## 3. 对父序列的影响表

| 维度 | 影响 |
|---|---|
| 顺序 | 新增自由能力，与自动识别并存；不要求 T0→T1→T2→T3 顺序，也没有“识别/复核/确认”前置 |
| 数据所有权 | 参考图工件/格网/渲染/地形/连通性命令与工具归 map；app 只做 RenderService 持有、Shell/GuiServer/工具装配、HTTP 与 HTML/CSS 接线 |
| 身份 | q/r 稳定；参考 `referenceId` 是内容寻址工件 id，不替代世界实体 id；地形/边仍用既有 HexCoord/EdgeRef |
| 持久化 | 原图+JSON 元数据在 `<store>/map-references`（ArtifactStore 相邻目录）；视图 PNG 在既有内容寻址 ArtifactStore；GameMap 无 schema 改动 |
| 预算 | 输入 32MiB/32Mpx；格网 ≤12000；视图输出 ≤1280 边长/≤2MiB；HTTP 参考图 body 独立 ≤46MiB |
| 失败/原子性 | 命令整批校验后单次提交；源图缺失/非法 id/坐标/半径清晰失败；工件失败不冒充地图完成；前端拒绝/冲突不自动重试 |
| 日志 | 参考图发布/查看记录 id、格数、尺寸、PNG 字节；连通性记录 kind/op/点数/边数；不记录原图/base64/载荷明文 |
| 验证 | 本责任区只到 compile + 定点 spotless + node 语法；测试/变异/真 MCP/浏览器由后续测试责任区与控制方执行 |

## 4. 审阅 10 项处理

1. **显式 height:null**：`PaintTerrainHandler.cells` 改为只有字段缺失才 `TerrainHeights.paintHeight`；`isNull()` 与非数值一样具名拒绝，消息说明“缺省请省略该字段”。
2. **整数参数截断**：`MapReferenceTool.optionalInt` 不再复用 `ToolSchema.optionalLong`；对 Number 要求有限且 `== Math.rint`，int 范围检查；字符串走 `Integer.parseInt`；HTTP query 仍 `Integer.parseInt`，均拒绝小数。未改全局 `ToolSchema`。
3. **自由区独立于旧步骤**：自由工具区文案明写“独立于自动识别；无步骤锁”；旧入口改为“可选自动识别工作区（旧流程）”；自动识别文件说明改为“浏览器本地处理，不上传原图；共享参考图时上传到本机世界服务”，载入图片后仍保留披露。
4. **代次/在途保护**：`share` 冻结目标与 sourceVersion，编码/发送前后各校验；`view` 用 viewToken + signature + sourceVersion + referenceId 校验；`loadReferences`/`refreshOverview` 各有 token（概览另加签名）防止迟到覆盖；`createMissing` 在第一个 await 前 setBusy、冻结目标与源图，读取后和发送前再校验；`write`/`share`/`createMissing` 都有 busy 防重复。
5. **app 对象误当函数**：已把所有 `app()` 改为 `app`（`app.target()`/`app.writeCommand`/`app.refreshState` 等），grep 无 `app()`。
6. **创建缺失格上下文/锁**：创建缺失格在第一个 await 前 setBusy、冻结 branch/revision 与 sourceVersion；读取当前地图差集后、发送前再校验；只把缺失格送 `map.ImportRaster merge`，已有格（含用户改过的格）不覆盖；提交使用 `SimosApp.writeCommand(..., frozenTarget)`，不另写提交状态机。
7. **可复制 LLM 参数**：`updateParams` 统一为 `{tool:"…",arguments:{…}}`；`map.PaintTerrain` 为 `{branch,expectedRevision,cells}`，`map.ConnectHexes` 为 `{branch,expectedRevision,kind,op,hexes}`，`simos.map.reference` 同形。
8. **词表不伪造**：`fillKindOptions` 在 pathwayGroups 为空时显示“没有已注册通路组”并禁用连接写，不再塞 river/road；`fillTerrainOptions` 优先保留当前选择，否则默认 `plains`（存在时），并新增“创建缺失格默认地形”说明；地形为空禁用相关写。
9. **大图可读**：新增 `#reference-viewer-dialog` 宽大图，侧栏局部图与大图都可点格；大图图片按自身尺寸显示，避免 object-fit 留白导致点格偏移；侧栏仍保留概览/局部。
10. **收敛回唯一宿主写入口**：删除 `map-reference-tools.js` 的 `submitFrozen`（不再调用 `api.submitCommand`/自写刷新/409/游标逻辑）；改为 `SimosApp.writeCommand(type,payload,frozenTarget)`。`app.js` 的 `writeCommand`/`commandEnvelope` 增加**可选**第三参（旧两参兼容），提交前捕获 branch/revision，成功与 409 仅在当前游标仍等于提交前游标时 `setRevision`；`refreshState` 只更新 heads/writeEpoch。新工具成功提示比较实际 `ref` 与当前游标，同 branch 的历史节点变化也不会冒充当前目标成功。

## 5. 文件清单

新增（simos-map）：

- `simos-map/src/main/java/io/mosire/simos/map/reference/MapReferenceService.java`
- `simos-map/src/main/java/io/mosire/simos/map/reference/ReferenceImageIO.java`
- `simos-map/src/main/java/io/mosire/simos/map/reference/ReferenceViewRenderer.java`
- `simos-map/src/main/java/io/mosire/simos/map/reference/MapReferenceViews.java`
- `simos-map/src/main/java/io/mosire/simos/map/tools/read/MapReferenceTool.java`
- `simos-map/src/main/java/io/mosire/simos/map/tools/write/MapPaintTerrainTool.java`
- `simos-map/src/main/java/io/mosire/simos/map/tools/write/MapConnectHexesTool.java`
- `simos-map/src/main/java/io/mosire/simos/map/spi/PaintTerrainHandler.java`
- `simos-map/src/main/java/io/mosire/simos/map/spi/ConnectHexesHandler.java`
- `simos-map/src/main/resources/webui/modules/map/map-reference-tools.js`

修改（simos-map）：

- `simos-map/src/main/java/io/mosire/simos/map/MapLogSource.java`（新增参考图日志来源）
- `simos-map/src/main/java/io/mosire/simos/map/ops/EdgeOperations.java`（connectChain + MapWrap 邻接）
- `simos-map/src/main/java/io/mosire/simos/map/spi/MapPayloads.java`（有序 hex 序列助手）
- `simos-map/src/main/java/io/mosire/simos/map/tools/write/AbstractNarrowWriteTool.java`（`payloadJson(context)` 钩子，默认行为不变）
- `simos-map/src/main/resources/webui/modules/map/map-png.js`（`getReferenceInput`/`blobBase64` + 上传披露）
- `simos-map/src/main/resources/webui/modules/map/map-raster-core.js`（导出 `existingGrid`）
- `simos-map/src/main/resources/webui/modules/map/manifest.json`（登记新 JS）

修改（simos-app）：

- `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（注册两个 handler；GuiServer 传同一参考图服务）
- `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`（装配参考图读工具、两个结构化写工具）
- `simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java`（登记两个新命令的载荷提示）
- `simos-app/src/main/java/io/mosire/simos/app/render/RenderService.java`（持有/暴露同一参考图服务）
- `simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java`（新参考图构造重载、GET/POST 接线、独立 body 上限、as= 拒绝）
- `simos-app/src/main/resources/webui/app.js`（`writeCommand`/`commandEnvelope` 可选 frozenTarget + 提交前游标保护）
- `simos-app/src/main/resources/webui/index.html`（自由工具区、默认地形说明、大图对话框、模块脚本）
- `simos-app/src/main/resources/webui/styles.css`（自由工具区/大图样式）
- `simos-app/src/main/resources/webui/modes.js`（map-edit 写白名单）
- `simos-app/src/main/resources/webui/map.js`（image-import 子工具写白名单）

修改（设计/账本）：

- `docs/superpowers/specs/2026-11-14-map-reference-tools-design.md`（T4 原地补宿主冻结/游标边界 + §7 修订记录）
- `docs/superpowers/reports/2026-11-14-map-reference-tools-impl-ledger.md`（本账本）

未修改任何 test / 测试 loader / 数量契约文件。

## 6. 命令日志（真实执行）

| 命令 | rc | 结果 |
|---|---:|---|
| `tools/mvn-lock.sh spotless:apply -pl simos-map,simos-app` | 0 | BUILD SUCCESS；只改本责任区 Java（11 个 map 文件 + 2 个 app 文件） |
| `tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` | 0 | BUILD SUCCESS；19 个 reactor 模块全部 SUCCESS；`simos-app` 223 source files |
| `tools/mvn-lock.sh spotless:check -pl simos-map,simos-app` | 0 | BUILD SUCCESS；MapSimos 154 / SimosApp 422 files clean |
| `node --check simos-map/.../map-reference-tools.js` | 0 | 通过 |
| `node --check simos-map/.../map-png.js` | 0 | 通过 |
| `node --check simos-map/.../map-raster-core.js` | 0 | 通过 |
| `node --check simos-app/.../modes.js` | 0 | 通过 |
| `node --check simos-app/.../map.js` | 0 | 通过 |
| `node --check simos-app/src/main/resources/webui/app.js` | 0 | 通过 |

编译日志尾部（最终轮，2026-11-14 21:14）：

```text
[INFO] MapSimos ........................................... SUCCESS
[INFO] SimosApp ........................................... SUCCESS
[INFO] BUILD SUCCESS
```

最终复跑（第 10 项修正完成后，2026-11-14 21:16）：`compile rc=0`、`map-reference-tools.js node --check rc=0`、`app.js node --check rc=0`；更早的 `spotless:check rc=0` 仍对应当前 Java 源（第 10 项只改 JS/docs）。

## 6.1 统一测试真实 finding 返修（2026-11-14 21:31）

- **真实 finding**：控制方 full 在 `simos-map` 的 `RegressionGuardsTest.L3_thereIsExactlyOneDirectionTable` 失败（`/tmp/simos-reference-full.log:378` 起）。`EdgeOperations` 为 wrap 邻接新增的 `NEIGHBOR_DELTAS int[][]` 被守卫判为第二份方向表；`HexDirection` 是唯一方向表。
- **修复**：删除 `NEIGHBOR_DELTAS`，`EdgeOperations.adjacent` 改为遍历 `HexDirection.ALL` 并取 `direction.dq()/dr()`；保留 `(long) a.q() + direction.dq()`、`(long) a.r() + direction.dr()` 的 long 加法与 `floorMod` 规范化，现有端点存在/相邻/自环/重复无向边校验语义不变。未新增任何等价方向数组，未改测试或守卫。
- **证据**：`tools/mvn-lock.sh spotless:apply -pl simos-map` rc=0（日志明确 `0 were changed`，未编辑/格式化任何 test 或守卫）；`spotless:check -pl simos-map` rc=0；`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` rc=0，`BUILD SUCCESS`（21:31:39）。按控制方要求**未重跑 test/full**。

## 6.2 full 的 4 个 SpotBugs 生产 finding 返修（2026-11-14 21:36）

测试报告 §7 记录：`tools/gate.sh full` 在 `simos-map` 被 SpotBugs `BugInstance size is 4` 挡住。本责任区只修生产源码，未改 SpotBugs 配置、未加广泛 suppress：

| finding | 修复 |
|---|---|
| `MapReferenceService.java:328` `DLS_DEAD_LOCAL_STORE`：`load` 的 `Summary summary` 赋值后未使用 | 删除死赋值，保留 `readSummary(referenceId);` 的读取/存在性校验；实际取用仍走随后 `parseMetadata` |
| `MapReferenceService.java:208` / `:212` `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`：`Path.getFileName()` 可能为空 | 新增窄 helper `fileNameOf(Path)`：取局部 `Path`、判空后转名；`list()` 的 filter 与 forEach 都经它并显式跳过空名，保留坏工件跳过语义 |
| `MapReferenceTool.java:144` `FE_FLOATING_POINT_EQUALITY`：`decimal != Math.rint(decimal)` 判整数 | 改为 `new BigDecimal(number.toString()).intValueExact()`；小数、非有限、int 越界仍抛 IAE，不使用 round/truncate；`Integer`/`Long` 快路径保留 |

证据：`tools/mvn-lock.sh spotless:apply -pl simos-map` rc=0（日志仅改生产文件 `MapReferenceTool.java`，未触碰 test）；`spotless:check -pl simos-map` rc=0；`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` rc=0，`BUILD SUCCESS`（21:36:12）。按控制方要求**未重跑 SpotBugs/full/test**；修复后由控制方重跑 full 验证。

## 7. 未做 / 未验证 / 风险

- **NOT_RUN**：任何测试（JUnit、前端 node --test、full/focused gate、SpotBugs、Checkstyle 独立命令、verify/package/clean）。统一测试发现 `RegressionGuardsTest.L3` 与 4 个 SpotBugs finding 后按控制方要求只做定点修复与 compile，**未重跑对应守卫/full/SpotBugs**。
- **NOT_RUN**：起真进程、MCP tools/list 与真 ImageContent、浏览器/网页操作、截图、参考图跨服务重启读回。
- **NOT_RUN**：真实非洲之角图片的共享/局部视图人工可读性确认；只验证了编译与语法。
- **未验证**：`PaintTerrain`/`ConnectHexes` 的域往返与负例、参考图 HTTP 上限/解码/路径穿越/重启、GM/DM 分桶与资产出站，均留给测试责任区。
- **未验证**：前端自由工具区在真实浏览器中的布局/大图点格/CSS 可读性；`node --check` 只证明语法。
- **测试 owner 需处理**：`modes.js` 与 `map.js` 写白名单新增了两条命令；若旧前端测试断言精确数组，需要按本轮目标接口更新。`CatalogTool` 的 PAYLOAD_HINTS 覆盖断言已补两项。
- **未做**：commit/push（用户明确不授权）。目标设计 T4 已按第二轮审阅第 10 项原地补宿主冻结/游标边界，并在 §7 记录原文、原因与新决定。
- **实现细节记录**：参考 id 的 canonical 元数据包含 `fileName`、媒体类型、尺寸、图像 sha256 与排序后的格网；这样同一 id 固定绑定完整元数据，不会有“同 id 换文件名/格网”的偷换。视图 PNG 预算取 2MiB（设计只规定“有限字节预算”）。
- **宿主边界记录**：`SimosApp.writeCommand` 仍是唯一宿主写入口；`frozenTarget` 只是可选信封来源，不构成第二个提交状态机。旧两参调用行为保持。
