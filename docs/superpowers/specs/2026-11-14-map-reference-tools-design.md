# 底图判读与自由地图工具目标运行设计

> **状态**：有效，2026-11-14 用户授权实现。
> **父序列**：[节点编辑器](2026-11-13-node-docs-runtime-target-design.md) S8/S2；复用[底图导入设计](2026-11-14-raster-import-runtime-design.md) R0–R5 的图像、格网、命令与空白世界。
> **本文局部取代**：LLM 判图不采用“提案/不确定 → 强制复核 → 确认应用”的流程；以下是可自由调用的能力，不是必须依次通过的阶段。原有自动识别工作区仍可选用。

## 1. 目标运行时能力与触发

### T0 底图与坐标可供模型读取

用户在地图编辑的底图工作区选择图片、格网/裁剪/起点后，可随时共享底图给工具，也可创建缺失格网。两项能力独立：共享只产生不可变参考工件，不改世界；创建格网经已有 `map.ImportRaster` merge，仅为缺失坐标填入所选默认地形，不覆盖已有格。新图上不必先进行纹理分类。来源图与格网是固定的一份绑定，更改参数后须重新共享，不能让同一 referenceId 偷换图或坐标。

原图、文件名、尺寸与 `{q,r,imageX,imageY,imageRadius}` 格网绑定存入当前 store 下的参考工件库，ID 为内容指纹，跨重启可读。不属于 GameMap/Revision 的业务事实；与既有渲染 ArtifactStore 同属宿主输入/输出工件，发布工件不修改世界。持久世界变化仍全部走 Command → ChangeSet → Revision。

### T1 自由查看原图和当前地图

GM 工具 `simos.map.reference`：无 referenceId 列出工件；带 referenceId 返回原图概览或指定中心 q/r、radius 的局部原图，叠加清晰六角边界和 q/r，附当前地形/高度/是否已建格及实际 branch/revision。可选当前地形半透明叠加，原图始终可看。局部保留邻格背景，不生成虚构像素。图通过既有 ToolResult assetIds → ArtifactStore → MCP ImageContent 出站，不只返回一个模型无法读取的文件路径。GM-only，不泄露至 DM 工具桶。

参数：`{referenceId?,branch?,revision?,q?,r?,radius?,terrainOverlay?}`；q/r 成对，局部半径 1..8，默认 4，中心必须在该参考格网内；缺中心取概览。文本包含 referenceId、图像尺寸、格网范围、当前状态游标、局部 cells。概览不输出几千格文本，局部至多 217 格。输出 PNG 边长不超过 1280，有限字节预算，不能为了预算把标签压到不可读后仍声称可判图。

### T2 自由直接改多格地形

新增 GM-only 命令/结构化工具 `map.PaintTerrain`：`{cells:[{q,r,terrain,height?}]}`，工具顶层另有 branch/expectedRevision。全部格须已存在，1..12000；同一批可含不同地形；height 给出时为有限 [0,1]，未给则沿用 `TerrainHeights.paintHeight` 的标准编辑高度（通常为高度带中值；沙漠遵守既有平原 +0.005 规则）。**直接写成可用地形，没有提案、不确定状态、review checkbox 或强制读图前置**。可反复覆盖修改。未知地形、非法坐标、重复格、图外格或任一非法高度整体拒绝。复用 RasterImportOperations 与完整 ChangeSet 往返，保留土地树/其他组件。

### T3 自由连接与擦除指定格之间的连通性

新增 GM-only 命令/结构化工具 `map.ConnectHexes`：`{kind:"river",hexes:[{q,r},...],op:"merge"|"remove"}`，工具另有 branch/expectedRevision。按顺序连接相邻两格，2..12001 点、最多 12000 边；river/road 来自当前 pathwayGroups，不新造词表。merge 只添加这些边上的该 kind，保留全部其他边、kind 与 props；remove 只摘除指定边的该 kind，空标签边删除，不全图替换。边是既有 EdgeRef/EdgeTags，不另造道路/河流状态权威；分支水系由多次调用构成。

全路径先校验后单次提交；端点必须存在、相邻（环绕依现有 MapWrap 邻接）、无自环、无重复无向边。跨格跳跃整体拒绝并指出哪一段，不猜测/自动寻路，不因坐标 int 溢出把远格判断成相邻。可重复使用闭环起点，但不重复同一条边。只变更 edges，不改变地形/土地/通路实体。命令声明逐格目标，保持 GM-only，不进入 DmUsableCommand 白名单。

### T4 网页自由工具区与即时结果

地图编辑的底图子功能中增加“判图工具”区域：共享底图/列出与选择参考图、创建缺失格网、查看带坐标局部图、点图或输入多格坐标、批量地形/高度、河流道路连接/擦除、查看可复制的 LLM 工具参数。没有步骤锁或“完成上一步”的门禁；仅对确实缺少输入、历史只读节点和在途重复写做约束。调用写操作后立即刷新主图与参考图。所有写入经**唯一的宿主写入口** `SimosApp.writeCommand` 和乐观并发；该入口新增可选第三参 `frozenTarget`（冻结 `branch/revision`，旧两参调用行为不变），工具在同步校验冻结目标未变后**不插入 await**地立即调用。`writeCommand` 在提交前捕获 branch/revision，成功与 409 只在**当前游标仍等于提交前游标**时才 `setRevision`；`refreshState` 只更新 heads/writeEpoch，不抢当前游标。新工具成功提示必须比较实际提交 ref 与当前游标（不能只比 branch/tick）。拒绝不报成功，冲突不自动重试。节点/分支变化后坐标选择可以保留供用户使用，但旧 expectedRevision 不自动提交。

新增静态 JS 属 simos-map；app HTML/CSS 只接线。读图/写地图工具在 MCP 可直接使用，网页不是唯一写入口。本期不强绑某个模型、不增加自动循环或聊天代理；控制方实际读原图并用工具链生成演示地形和河流，清楚区分“工具可用”和“某模型自主绘完整图”。

## 2. 用户原话与影响映射

2026-11-14：

> 2不是标记不确定，是直接修改为可用的；也可以根据图片，在指定的多个格子之间创建连通性，例如后续所要用的道路，以及目前其实就能搞的河流等 3 没必要做的这么流程化，工具调用是自由的；先提交并推送到远程一下当前版本，然后记录文档、开始实现这个功能，并启动网页端给我展示一下成果

| 裁定 | 影响能力 | 运行后果 | 不涉及 |
|---|---|---|---|
| 直接修改可用地形 | T2/T4 | 调工具即提交命令，无提案/不确定状态 | 不取消输入和并发校验 |
| 多格连通性、道路河流 | T3/T4 | 有序格链添加/擦除既有 edges 的 kind | 不推断真实水文、河流方向/流量 |
| 自由调用 | T0–T4 | 能力独立可重复，无工作流状态机 | 不绕过节点只读/乐观并发 |
| 先保存当前版本 | 实施前 | 已提交推送 b9d8fe1d 至 origin/feature/d036-node-editor | 新功能不自动提交推送 |

## 3. 现状证据和嵌入影响

- `map-png.js:458` 已有公开入口与图像/网格上下文，可新增只读导出参考输入，复用六角生成几何；旧自动预览流程保留。
- `RasterImportOperations.java:25` 已能完整校验后原子构造目标图；`MapSetTerrainTool.java:56` 原工具已能同类多格刷写。
- `EdgeOperations.java:117` / `SetEdgeHandler.java:49` 已支持 river/road 标注，但原 replace 全图语义不适合局部擦除，有序格链也缺一个严格邻接入口。
- `MapRenderTool.java:179` 已用 ToolResult assetIds 出图片；`ArtifactStore.java:40` 为内容寻址工件库。
- `GuiServer.java:313` route 表、`Shell.java:1324` 工件/渲染装配、`SimosToolSource.java:1463/2080` 地图工具装配是宿主接线点。

| 维度 | 对父序列影响 |
|---|---|
| 顺序 | 新增自由能力，与自动识别并存；不要求 T0→T1→T2→T3 顺序 |
| 数据所有权 | 格网/裁切/图像渲染/连接和命令/工具归 map；app 仅 HTTP、工件发布能力和组合接线 |
| 身份 | q/r 稳定；参考 sourceId 绑定图与格网内容，不替代世界实体 ID |
| 持久化 | 原图/坐标清单在 store 的参考工件目录；地形/连通性沿既有 MapChangeSet，无 GameMap schema 改动 |
| 预算 | 输入≤32MB/32M像素、格网≤12000，视图输出有尺寸/字节预算；不调用模型 |
| 失败 | 命令整体拒绝/冲突无部分写；源图缺失/非法 ID/坐标/半径清晰失败；工件失败不冒充地图完成 |
| 日志 | ID、格数、边数、kind、游标和错误；不输出原图/base64 |
| 验证 | 域正负例/往返、GM-only/结构化 schema、MCP 真 ImageContent、网页自由操作、重启工件与地图读回 |

## 4. 文件责任与宿主契约

单个生产责任区：simos-map 的 reference 服务/元数据/格网渲染与工具、新领域命令/handler/ops/tools、模块 JS/manifest；simos-app 的 Shell、SimosToolSource、CatalogTool、RenderService/ArtifactStore 窄适配、GuiServer HTTP 接线、index/styles/modes。

工件服务位于 map，接收目录/PNG 发布窄函数，不依赖 app/core。可由 RenderService 持有并暴露同一服务（只组装，不实现参考图业务）；GuiServer 新带服务重载保留旧构造，生产 Shell 与工具共用。允许更简单且同边界的注入，禁止静态全局注册表。

HTTP：`POST /api/map/references` 接收 `{fileName,imageBase64,cells:[{q,r,imageX,imageY,imageRadius}]}`，严格独立 body 上限（base64 放大后≤46MB），服务验证真实尺寸与 PNG/JPEG、预检像素后解码、canonical 元数据与内容寻址保存。`GET /api/map/references` 列表；`GET /api/map/reference?referenceId=...&q=...&r=...&radius=...&branch=...&revision=...&terrainOverlay=...` 返回视图摘要与 GUI 用 imageBase64；工具只将 PNG 作为 assetId 附件，不把 base64 放文本。新参考图接口拒绝 `as` 读法，GM 输入工件不进入 DM 视野。不读取任意路径或远程 URL。

map.PaintTerrain / map.ConnectHexes 的窄工具采用顶层结构化参数；原窄工具继续支持 payloadJson。如需为共同窄基类增加 payload 构造钩子，默认行为须保持。Catalog 完整登记，tool role 唯一声明，handler 实现 CommandTargets。

## 5. 验收与负例

1. 独立工具随意交替：参考图查看、多地形一次写、重复覆盖、river/road 添加/移除，均即时生效；无“先标不确定/先复核”的流程。
2. 真 MCP tools/list 包含结构化 GM 工具，DM 桶不含；reference 调用实际收到 ImageContent，文本有可定位坐标。
3. Paint 的 0/1 高度合法；未知类/重复/非整数/溢出/越界/非有限/图外整体拒绝，原土地树、外部格与其他模块逐字段保持，ChangeSet/Codec 往返。
4. Connect 路径邻接、环绕邻接、闭环；图外/断点/重复边/自环/未知kind/非法op整体拒绝；remove 局部且保留其他 kind 和 props；复合水系多次增量调用保持；往返落盘。
5. 图像真实解码、超限预检、ID/path traversal、格坐标/像素/半径边界/重复、缺参考、错误 q/r/radius 清晰拒绝；参考工件及视图跨服务重启可读。
6. 网页用用户非洲之角图：共享、直接创建格网，不做自动纹理分类也能显示坐标；自由多格刷地形与连续河流/道路、局部擦除；刷新/重启读回。无 JS 未捕获异常。
7. 统一测试含关键变异反证；完整 JS 和 Java focused/full，控制方真实浏览器/MCP/截图和保留演示服务。

## 6. 非目标与停止条件

不恢复经济，不下载 DEM、不从晕渲反演精确海拔；不引入 LLM 自动多轮执行器、不配置用户模型账号；不改旧 CLI/多年 AI。生产只 compile、语法/格式检查，不改/跑测试、不 package/verify、不 commit。编译交回后统一测试；真实缺陷返修不得靠放松断言吞掉。当前版本已推送，新功能完成后保留待用户审阅。


## 7. 修订记录

- **2026-11-14（第二轮生产审阅第 10 项 + 用户当次指令）**：T4 原文“所有写入经 SimosApp.writeCommand 和乐观并发；拒绝不报成功，冲突不自动重试。节点/分支变化后坐标选择可以保留供用户使用，但旧 expectedRevision 不自动提交。”原文保留的目标不变；原因是返修中发现局部 `submitFrozen` 另写了 `api.submitCommand`/刷新/409/游标逻辑，违反“唯一宿主写入口”并重复了成功/冲突状态机。新决定：收敛回 `SimosApp.writeCommand`，为其增加**可选** `frozenTarget`（旧两参兼容）；提交前捕获 branch/revision，成功/409 只在当前游标仍为提交前游标时推进 revision，`refreshState` 只刷新 heads/writeEpoch；工具成功提示比较实际 ref 与当前游标。来源：`/tmp/simos-reference-review-notes.md` 第 10 项与本轮用户指令；影响仅限 T4 宿主写入口与前端接线，不改变 T0–T3 的命令/工件形状与 §5 验收面。

- **2026-11-14（默认高度说明）**：T2 原文“未给则用该标准地形编辑高度中值”细化为沿用既有 `TerrainHeights.paintHeight`，明确沙漠的既有 +0.005 例外；不另造与现有地形刷竞争的默认规则，显式 null 仍为非法给值。
