# M9 spec —— WebUI 大图性能（19441 格真图）

> 状态：**待裁决**。日期：2026-09-19。缘起：用户实测「网页浏览器我根本进不去一点——地形区没有压缩，整个浏览器一进网页直接爆炸」，
> 并点名「**学习一下 GSimulator 的方案**」：① **没有进入窗口范围的 hex 不加载**；② **同地形的 hex 不但在渲染层应当成为同一块，在数据记录层也应该是同一块**。
> 四路取证（前端渲染 / 服务端传输 / GSimulator 方案 / simos 存储模型与铁律）已完成，**结论见 §一、§二**。

## 一 实测基准（本机当前构建，打 `/tmp/m6-import-verify/test_integration` 真档）

| 项 | 实测值 | 出处 |
|---|---|---|
| `GET /api/map/overview` 响应体 | **1,044,970 B** | 真请求 |
| 其中 `hexes` 数组 | **1,043,837 B = 99.89%**（53.7 B/格；**4 字段/格**：`q,r,terrain,height`） | 同上 |
| 压缩 | **无 gzip**（带 `Accept-Encoding: gzip` 仍返回原文；源码无 `Content-Encoding`） | 真请求 + 源码 |
| ★ **gzip 后**（把真响应体压一遍实测） | level 6 = **68,835 B（6.6%）**；level 9 = **58,820 B（5.6%）** | offline 实测 |
| 启动请求 | overview **×2**、units **×2**、state **×2**（`panels.js` 与 `map.js` **各一份、无共享缓存**） | 源码 |
| 每请求成本 | **每个读端点都跑 `Replay`**（`CheckpointStore` 无缓存 ⇒ 每次重读+解码 **1.1MB** checkpoint）；overview 服务端 30–65ms | 源码 + 实测 |
| 每帧绘制 | `visibleHexes()` **O(19441) 扫描**（无空间索引）+ **两遍路径**（地形 + 边框）⇒ ~**233k path ops/帧**；**无离屏/分层/脏矩形**；canvas = **视口 × DPR** | 源码 |
| 初始视图 | `fitView` 把整张图缩进视口 ⇒ **首帧几乎所有 19441 格都在视口内**，裁剪基本无效 | 源码推断 |
| 地图形状 | 半径 80 的六边形球（3·80·81+1 = 19441） | 计数吻合 |

## 二 ★★ 关键事实：GSimulator 并没有"在数据层合并"，而且真图上没启用压缩

| 事实 | 出处 |
|---|---|
| 权威地形**逐格**：`hexes: Map<String,HexCell>`；**无任何 RLE/块/四叉树/空间索引**（全仓搜 `run.?length|rle|quadtree|spatial|chunk|hilbert|morton` 零命中） | `~/DevMosire/GSimulator/gsim-map/.../MapData.java:33-46` |
| "同地形合并成一块" = `CompressedRegion`（同地形**连通分量** BFS，≥100 格才建，`hexKeys` + 带洞多边形） | `CompressionService.java:34-35,44-106` |
| ★ 它**明确是纯渲染缓存**：原文 "hexes() is always the authoritative data source"、"pure rendering optimization — it can be regenerated at any time"、`HexKeys are the truth — boundaries are derived` | `CompressionService.java:20-23,39-40`；`CompressionValidator.java:174-176` |
| 压缩是**手动 UI 按钮**（`doCompress()` → `POST /compress`），**无自动压缩** | `ui.js:264-284`；`MapWebUIHandler.java:337-352` |
| ★★ **19441 格真图 `compressedRegions=0`、`terrainBlocks=0`**（`test_integration`、`f3qa` 均如此；只有两个 diff 文件有 8/12 个 CR） | 扫 `worlds/` |
| 其渲染：视口裁剪**有但是 O(N) 全量扫描**；`drawHexBatch` **每格 `beginPath`+`fill`+`stroke`**（只批量了 `fillStyle`）——**比 simos 的"同色合成一条 path"更差**；CR 多边形 Pass 1 **无裁剪**；无离屏/DPR 处理 | `render.js:2-14,33-46,88-98,100-122` |
| 加载：**一次整图 JSON**，无视口/分块 | `map-api.js:26-32`；`MapWebUIHandler.java:178-203` |

**⇒ 结论（必须记账）**：用户的记忆里"GSimulator 的方案"是指**它具备但未启用的能力**（一个手动压缩按钮 + 一个渲染缓存）；**它的权威数据层从未合并**。M6 丢弃 `compressedRegions` 的理由（"纯渲染缓存"）**与源码一致，成立**。⇒ **不能把"照抄 GSimulator"当作设计依据**——它自己也没解决这个问题。

## 三 诉求② 的硬约束：块**不能**是权威数据

| 事实 | 后果 |
|---|---|
| ★ `HexCell(String terrain, double height)`：**地形是 7 选 1，高度是连续值** | 块承载不了逐格高度 |
| ★ `RegionRandomizer.java:82` 改地形时**高度原样保留** ⇒ **高度不是地形的函数** | 块无法从地形反推高度 |
| `RiverBuilder`（读逐格 height）、回归护栏 `L7`（钉 `height` 存活）依赖逐格 height | 丢高度 = 破坏既有能力 |
| `RegionRandomizer` 逐格 Bernoulli ⇒ **同地形可以不连通** | 块要么允许不连通，要么每次命令后重算 |
| **项目内同型先例**：`GameMap.regionIndex()` **每次重算、不进组件/变更集/存档**；其 Javadoc 原文把缓存定性为「第二份可漂移的副本（GSimulator 的 `compressedRegions` 正是那个形态）」 | **派生块**是本项目的既定形态 |
| M6 丢弃 `terrainBlocks` 的理由逐字：「以 hex 上的 terrain 为权威」 | **权威块 = 逆转已落盘裁定** |
| 若做权威块，须改：`MapChangeSet`(7 组件) / `RoundTripComponentsTest`(两个 `default -> throw` 的 switch + 豁免集) / `L2_hexCellHasNoConnectivityField` / `GameMapTest.noRiversNoRoadsNoTerrainBlocksNoCompressedRegions` / `MapCodec` JSON 形态 / `tools/gsimap_import.py` | 代价高一个数量级 |
| 铁律 5 **本身不禁止**任一种（只要求"从完整状态派生 + 往返不变式"）；冲突点是**既有测试冻结与设计裁定**，不是铁律 | —— |

**⇒ 控制器建议**：**诉求② 落成派生块索引**（服务端据此**发块多边形**而非逐格 hex，客户端画多边形）——这是它**唯一能既省传输又不丢高度**的形态；权威数据仍逐格（`terrain`+`height`），**铁律 5 零冲突**。**若坚持权威块，须用户显式裁定并承担 §三 全部改写。**

## 四 分档优化方案（按性价比排序）

### 档 0 —— 无设计改动，先做（预期解决"进不去"）
| # | 手段 | 预期 | 自证 |
|---|---|---|---|
| **0-1** | **gzip** GUI 响应（至少 overview；静态文件同办） | 1,044,970 → **~69KB（-93%）** | 断言响应头含 `Content-Encoding: gzip` **且字节数下降**；变异：关掉 gzip ⇒ 体积断言红 |
| **0-2** | **去重启动请求**：overview/units/state 各 2 次 ⇒ 1 次（共享缓存） | 首屏传输**再砍一半** | 断言每 URL 每 target 只发 1 次（计数） |
| **0-3** | 服务端 `(branch,revision) → SimulationState` 缓存 | 杀掉每请求 replay（40–85ms/次） | 断言同 target 第二次请求的 checkpoint 读取次数为 0 |
| **0-4** | 前端**地形层离屏位图缓存**（pan 时 `drawImage` blit；zoom 结束/数据变才重绘） | pan/zoom 帧时间降一个量级 | 断言 pan 期间路径重建次数为 0 |
| **0-5** | `updateLegend` **记忆化**（现在 setData + setUnits 各扫 19441） | 小 | 断言 per-load 扫描次数 = 1 |
| **0-6** | 评估 overview **是否可以不发 `height`**（渲染路径疑似不用） | 再省 **~36%**（★ 需先核实前端是否真不用） | 断言渲染不读 height；**核实不了就不做** |

### 档 1 —— ★ 诉求① 视口外不加载
- 服务端新增**分块**端点（如 `GET /api/map/terrain?chunk=<cq>,<cr>&…` 或 bbox），**只返回该块内的 hex**；静态地形块尺寸建议 32×32 hex。
- 前端：**块级缓存**（key = target + chunk），视口驱动加载（pan/zoom 时补齐缺失块）；**LOD**：远缩放时不发逐格而发**块多边形**（与档 2 合流）。
- 自证：断言请求 URL 带 chunk/bbox、**返回 hex 数 < 总数**、屏外块**不被请求**（列出请求清单）。

### 档 2 —— ★ 诉求② 同地形合并（**派生**）
- 新增**派生块索引**（同 `regionIndex()` 形态：不进组件/变更集/存档）：同地形**连通分量** + 带洞外环多边形（算法与 GSimulator 同型，可直接借鉴 `CompressionService` + `hexGeometry`）。
- 服务端在 overview（或新的分块端点）里**发块多边形 + 未合并的剩余格**；客户端 **Pass 1 画多边形**（`fill('evenodd')`，支持洞），**Pass 2 只画剩余格**。
- ★ **必须保留逐格 `height` 的传输通道**（块不带 height）——按需流式/分块传，或确认渲染不需要 height。
- 自证：断言同地形格**只出现在一个块里**（不重不漏，块 hexKeys 并集 == 原 hex 集合）；往返/重放不变（派生件不进存档）。

### 档 3 —— 渲染细化（若档 0-4 不够）
- 分层 canvas（静态地形层 / 动态选中·路线·单位层）；**脏矩形**；`hexAt` 已 O(1) 无需改；`visibleHexes` 引入**空间索引**（块格网）消除每帧 O(n) 扫描。

## 五 需求与判据（待用户裁定后定稿）

**建议判据**（每条要**数字**，不接受"通过/不通过"）：
1. **首屏可用**：19441 格真图 `http://127.0.0.1:5818/` 从导航到可交互 **< 1.5s**（先测当前基准）。
2. **传输**：首屏总字节 **< 150KB**（gzip 后）。
3. **交互**：pan/zoom 帧时间 **< 33ms**（≥30fps）。
4. **视口**（诉求①）：屏外 hex **不被传输**（给出请求清单 + 返回 hex 计数 < 总数）。
5. **合并**（诉求②）：同地形在**渲染层**合并为一条 path/一个多边形；**数据层**有一个**可查可测**的派生块表示。
6. **不退化**：M7/M7b~M7g 的 e2e（右键路线、左键取消移动、tick 分组、推进 N、全屏底图、浮层不穿透）全绿。

## 六 ★ 裁定（2026-09-19，用户）

| # | 裁定 | 用户原话 / 依据 |
|---|---|---|
| **P1** | ★★ **权威块**（`Map` 层级） | 「**Map层级的权威块，因为需要压缩的东西目前不是什么精确到单个hex的具体数据，而是hex-hex间通用、仅被map模块本身维护的层级terra数据**」 |
| **P2** | **先做档 0**，再分块 | 「肯定是」 |
| **P3** | **最好省掉 `height`**（以核实为条件） | 「最好是」 |
| **P4** | ★ **gzip 降级为"可选/实测决定"**，不再是主推 | 用户反问「**为啥要gzip？**」——**问得对**，见 §七 |

### P4 的正面回答（控制器撤回原排序）

**gzip 是治标，不是治本。** 它在原方案里排 #1，**只因为当时载荷是 1MB**；而 **P1（权威块）+ P2（分块）落地后，载荷本身就是几 KB 的块多边形**，此时：
- gzip 的**边际收益趋零**（几 KB 压不压都无所谓）；
- 而 **JDK 的 `HttpServer` 不原生支持 gzip** ⇒ 要自己写 `GZIPOutputStream` 流式响应 + 设 `Content-Encoding` + 处理 `Accept-Encoding`，**是实打实的代码与状态**，**收益 / 复杂度 不划算**。

⇒ **P4 结论**：**不把 gzip 当性能手段**。仅保留两条**条件性**用法：① **静态文件**（JS/CSS，一次性、收益固定）可按需开；② 若档 0 量完之后**仍有大载荷**，再实测决定。**排序从 #1 降到"可选"**——真手段是**别再发 1MB**。

## 七 ★ P1 设计：权威地形块 + 逐格高度

### 7.1 为什么用户是对的（推翻控制器在 §三 的疑虑）

控制器的疑虑是"块承载不了逐格 `height`"。**用户点破了关键**：要压缩的**不是精确到单 hex 的具体数据**，而是**粗粒度、层级化、仅 map 模块维护的地形分类**。据此：

| 数据 | 性质 | 归属 |
|---|---|---|
| **地形（`terrain`）** | **粗粒度**（7 类高度带）、**hex 间通用**（相邻同带）、**仅 `simos-map` 维护** | ★ **权威块**（`Map` 层级，一个同地形连通块 = 一条记录 + 一个带洞多边形） |
| **高度（`height`）** | **精确到单 hex**、连续、**不是地形的函数**（`RegionRandomizer:82` 改地形**保留 height**） | **仍逐格**（不进块） |

★ **`RegionRandomizer` 改地形而保留 height ⇒ 地形在**活状态**里不是 height 的函数 ⇒ 地形必须**独立权威存储**（这正是"权威块"而非"height 派生"的根本理由）。** 控制器原先"地形可由 height 重算"的隐含假设**是错的**。

★ 同时满足 **铁律 3**（仅 map 模块维护；对下游只暴露 `terrainAt(HexCoord)` 访问器，**表示法不泄漏**）。

### 7.2 设计要点

1. **`GameMap` 新增权威组件 `terrainBlocks: Map<BlockId, TerrainBlock>`**，`TerrainBlock = (terrain key, Set<HexCoord> hexes, boundary 带洞多边形)`；`HexCell` 由 `(terrain, height)` 收窄为**只剩 `height`**。
2. ★★ **分割不变式（partition）**：所有块的 `hexes` **并集 == 全部 hex，且两两不交**（每个 hex 恰属一块）。**这是本改动的核心护栏**，必须有用例 + 故意违规自证（缺一块 / 重叠一块 ⇒ 红）。
3. **`hex → block` 反向索引是派生的**（同 `regionIndex()` 形态：**不进组件/变更集/存档**，每次重算）⇒ 不会出现"第二份可漂移的副本"。
4. **`terrainAt(HexCoord)` 访问器保持稳定**：`ApiViews`/`GuiServer`/`ToolSupport`/`TerrainMovementCost`/`RiverBuilder`/`MapHexTool` 等**读点改为走访问器**，表示法不泄漏到模块外。
5. **写点改为写块**：`RegionRandomizer`（逐格 Bernoulli 改地形）⇒ 改为**重算受影响块的切分**；`MapGenerator`/`MapDemo`/M6 导入 ⇒ 直接生成块。

### 7.3 受影响清单（P1 的代价，逐条要做）

| 位置 | 影响 |
|---|---|
| `MapChangeSet` | `hexes` 语义变为"仅高度"；**新增 `terrainBlocks` 组件**（组件数 7 → 8） |
| `RoundTripComponentsTest` | 两个 `default -> throw` 的 `switch` + 豁免集 **必须显式改**（设计如此：改名/加组件会当场红） |
| `HexCell` 冻结守卫 `L2_hexCellHasNoConnectivityField` | 冻结的是 `[terrain, height]` ⇒ **按新形状改，不得改成恒真** |
| `GameMapTest.noRiversNoRoadsNoTerrainBlocksNoCompressedRegions` | **必须显式撤销**（这是 P1 直接逆转的那一条） |
| `MapCodec` | `GameMap` JSON 形态变化；块的多边形/hexKeys 需序列化 |
| `tools/gsimap_import.py` | 生成 `new_hexes` 改为**生成块**（内联 DDL 无地形列 ⇒ **SQL schema 不变**） |
| `RegressionGuardsTest.L7` | 钉 `hexes`/`terrainTypes` 键序 ⇒ 按新形状复审 |
| 所有 `cell.terrain()` 读点 | 改走 `terrainAt(hex)`（§3b 已列全） |

★ **M6 的旧裁定被逆转**（原文「以 hex 上的 terrain 为权威」）⇒ 本 spec **显式记账**：属**用户授权范围内的推翻**（「允许推翻之前的设计、改之前的代码」）。

### 7.4 判据（P1 部分）

- **分割不变式**：块并集 == hex 全集、两两不交；**故意违规**（少一块 / 重叠）⇒ 用例红。
- **表示法不泄漏**：`simos-map` 之外**没有任何**代码读 `HexCell.terrain`（用 grep 断言）。
- **往返**：`apply(between(base,target),base).equals(target)` 逐组件成立（含新组件）。
- **重放一致**：真命令（含 `RegionRandomizer`）经 `CoreSimos` 后，块与逐格高度都逐值对上。
- **渲染收益**：服务端**发块多边形**而非逐格 ⇒ 载荷从 1,043,837 B 降到**块数量级**（实测值）。

## 八 待裁定（剩余）

| # | 项 | 控制器建议 |
|---|---|---|
| **P5** | `BlockId` 的生成方式 | **确定性**（由地形 key + 最小 hex 坐标派生，如 `plains@-5_-59`）——保证同一状态两次运行块 id 相同（可复现、可断言） |
| **P6** | 块的最小尺寸 | 小于 100 格的同地形连通块**是否也建块**（GSimulator 用阈值 100 留作散格）⇒ 若建，散块很多；若不建，需一格一格发 | 建议**全部建块**（保证分割不变式完整、无"剩余散格"通道），配合分块传输 |
