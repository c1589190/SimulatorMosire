# M9 计划 —— 大图性能（bite-sized）

> 配套 spec：`docs/superpowers/specs/2026-09-19-map-perf-design.md`（**判据见其 §五 / §七.4**）
> 裁定：**P1 权威块 / P2 先档 0 / P3 最好省 height / P4 gzip 降级为可选 / P5 确定性 BlockId / P6 全部建块**
> ★ 执行期纪律：本计划的代码草图是**计划期产物**，与 `src` 分歧处**以源码为准**，分歧记台账。

## 〇 通则（每任务适用）

- 派单只用 `subagent_type="deepseek-flash-go"`；**单任务 worktree**；**一次只跑一个 Maven**；本机 `:5817`/`:5818` **别 kill**。
- 每任务：`spotless:apply` → 定向测试 → **合并后主树 `clean verify`**（关账前）。
- **护栏必须自证**：每个新护栏配**故意违规**用例 + **≥2 轮变异**（九道门禁；日志自指 + **先断言聚合 md5 非空**）。
- **同一文件被两任务改 ⇒ 串行**，后关账者**重跑前者的变异轮**。
- 不 `git add -A`；`.superpowers/**` 用 `git add -f`；不加 `Co-Authored-By`；**该推就推**；证据放 `.superpowers/sdd/2026-09-19-map-perf/tN-evidence/`（**不许放仓根**）。

---

# Batch A —— 档 0 止血（先量基准，否则"优化了多少"没有对照）

## T1 基线实测（test-only，**必须先做**）
**目标**：在 19441 格真档上量出**当前**的首屏与交互成本，作为后续每步的对照。
**必须做**：起真服务（`--store /tmp/m6-import-verify/test_integration`，端口自选）→ Playwright 打开 `/` → 记录：
① **首屏字节**（按 URL 分列，**标出 overview/units/state 各请求了几次**）；② **导航→可交互耗时**（`performance.timing` + 自设 `requestAnimationFrame` 首帧标记）；③ **pan/zoom 帧时间**（连续拖 3 秒，取 p50/p95）；④ **gzip 关闭状态**（记录 `Content-Encoding` 缺失）；⑤ overview 响应体字节数。
**产物**：`t1-evidence/baseline.json`（数字）+ `t1-report.md`。**不许写优化代码**。
**变异**：无（纯测量）——但**必须复跑一次**证明数字可复现（两次差值 < 20% 才可用）。

## T2 去重启动请求
**目标**：overview/units/state 由 **各 2 次 → 各 1 次**（`panels.js` 与 `map.js` 共享缓存）。
**必须做**：抽出共享的按 target 记忆化取数层；`panels.js`/`map.js` 都走它；target 变化时统一失效。
**变异**：m1：去掉 memo ⇒ 请求计数回到 2（红）；m2：target 变化不失效 ⇒ 旧数据（红）。
**判据**：T1 的请求清单里每个 URL × target **恰好 1 次**。

## T3 服务端 `(branch,revision) → state` 缓存
**目标**：杀掉"每读请求重读+解码 1.1MB checkpoint"。
**必须做**：在 `QueryService`（或 `CoreSimos`）加**按 target 的 state 缓存**；**失效策略必须显式**（新 revision / 新分支 / 提交后失效）；**缓存对象必须不可变或防御性拷贝**（★ 别把可变状态漏出去）。
**变异**：m1：提交后不失效 ⇒ 读到旧状态（红）；m2：缓存键漏 branch ⇒ 跨分支串味（红）。
**判据**：同 target 第二次请求**不触发 checkpoint 读取**（计数为 0）；且 `CommandBus.submit` 之后**立刻**能读到新 revision。

## T4 前端地形层离屏位图缓存
**目标**：pan 时**不重建路径**，直接 `drawImage` blit。
**必须做**：地形层画进 `OffscreenCanvas`（或隐藏 canvas）；**pan** ⇒ blit；**数据变 / zoom 结束 / resize** ⇒ 重绘。★ 缩放过程中允许降级（先拉伸位图，交互结束再重绘）。
**变异**：m1：pan 也重绘 ⇒ "pan 期间路径重建次数" 断言红；m2：数据变不重绘 ⇒ 地形陈旧（红）。
**判据**：pan 3 秒内**路径重建次数 == 1**（或 0）；帧时间较 T1 基准**显著下降**（给数字）。

## T5 `updateLegend` 记忆化 + P3 核实
**必须做**：① `updateLegend` 记忆化（现在 `setData` 与 `setUnits` **各扫 19441**）；② ★ **核实 `height` 是否被渲染/交互使用**（grep + 运行时探针），**给出结论**：若不用 ⇒ overview **停发 `height`**（并在 spec 记账）；若用 ⇒ `height` 走**独立紧凑通道**（如定长数组），**不进主载荷**。
**变异**：m1：`height` 仍被某个渲染分支读（若结论是"不用"）⇒ 停发后该分支崩（红）——**这条变异就是"核实"本身的护栏**。
**判据**：per-load 扫描次数 == 1；`height` 的去留**有实测支撑**。

---

# Batch B —— P1 权威块（地基；Batch A 可与 T6 并行，但 T6 起串行）

## T6 ★ 权威 `terrainBlocks` 落地
**目标**：地形由逐格改为**权威块**（`Map` 层级）；`HexCell` 只剩 `height`。
**必须做**：
1. 新增 `TerrainBlock`（`terrain key` + `Set<HexCoord> hexes` + **带洞边界多边形**）与 `BlockId`；★ **`BlockId` 确定性**（地形 key + 最小 hex，如 `plains@-5_-59`，P5）。
2. `GameMap` 新增权威组件 `terrainBlocks: Map<BlockId, TerrainBlock>`；`HexCell(terrain,height)` ⇒ **只剩 `height`**。
3. ★★ **分割不变式**：块 `hexes` **并集 == 全部 hex、两两不交**；构造期或专用校验器强制。
4. **`hex → block` 反查是派生的**（同 `regionIndex()`：不进组件/变更集/存档）。
5. **`terrainAt(HexCoord)` 访问器**（稳定接口，表示法不泄漏）。
6. 同地形连通分量切分（BFS，六邻域）+ 多边形生成（外环 + 洞）；★ **全部建块**（P6，无散格通道）。
**变异**：m1：并集少一块（有 hex 无主）⇒ 分割不变式红；m2：允许重叠（一个 hex 属两块）⇒ 红；m3：`BlockId` 不确定性（随机/自增）⇒ "两次运行同 id" 红。
**判据**：spec §七.4 前三条的实测值。

## T7 变更集与往返（**与 T6 串行：同文件**）
**必须做**：`MapChangeSet` 由 7 ⇒ **8 组件**（`terrainBlocks` 加入、`hexes` 语义变"仅高度"）；`RoundTripComponentsTest` **两个 `default -> throw` 的 switch + 豁免集显式改**（`changeSetHasExactlySevenComponents` ⇒ 八）；`L2_hexCellHasNoConnectivityField` 按新形状改（**不得恒真**）；`GameMapTest.noRiversNoRoadsNoTerrainBlocksNoCompressedRegions` **显式撤销那条**（P1 直接逆转，已记账）；`MapCodec` 支持块序列化；`L7` 键序复审。
**变异**：m1：`isEmpty()` 漏问新组件 ⇒ 空变更集被判"有内容"（红）；m2：`apply` 不从 base 取 `spec`/漏重建块 ⇒ 往返红；m3：块不进变更集 ⇒ 组件数断言红。

## T8 写点改块
**必须做**：`RegionRandomizer`（逐格 Bernoulli 改地形）⇒ **重算受影响块的切分**；`MapGenerator` / `DemoWorld` ⇒ 直接生成块。
**变异**：m1：随机化后不重新分块 ⇒ 分割不变式红；m2：随机化只改块内一 hex 不改块 ⇒ 地形与块不一致（红）。
**判据**：真命令（`map.RandomizeRegion` 若已存在）经 `CoreSimos` 后块与逐格高度**逐值**对上。

## T9 读点改访问器 + 表示法不泄漏
**必须做**：`ApiViews`/`GuiServer`/`ToolSupport`/`TerrainMovementCost`/`RiverBuilder`/`MapHexTool` 等**全部改走 `terrainAt(hex)`**。
**变异**：无（本任务由 **grep 断言**守卫）：★ **断言 `simos-map` 之外无 `HexCell.terrain` / `.terrain()` 的直读**（写成用例；故意在 map 外加一处直读 ⇒ 红）。

## T10 M6 py 脚本生成块
**必须做**：`tools/gsimap_import.py` 的 `new_hexes` ⇒ **生成块**（同 T6 的切分与 `BlockId` 规则）；★ **SQL schema 不变**（地形活在 `changeset_json`/checkpoint JSON）。
**判据**：M6 原有的逐字段对拍**仍 ALL PASS**（2026-09-19 M6 关账的判据），且导入档能被 Java 真读路径接受。

---

# Batch C —— 档 1 分块传输（诉求①）

## T11 服务端分块端点
**必须做**：新增 `GET /api/map/terrain?chunk=<cq>,<cr>&…`（或 bbox），**只返回该块内的块与 hex 高度**；块尺寸建议 32×32 hex。★ **屏外不请求**。
**变异**：m1：返回整图（忽略 chunk）⇒ "返回 hex 数 < 总数" 红；m2：chunk 边界算错（漏/重）⇒ 并集不等于全图（红）。
**判据**：请求 URL 带 chunk；返回计数 < 总数；**请求清单证明屏外块未被请求**。

## T12 前端块缓存 + 视口驱动加载 + LOD
**必须做**：chunk key = target + chunk；视口移动补齐缺失块；**LOD**：远缩放发块多边形、近缩放发逐格高度。
**变异**：m1：不缓存 ⇒ pan 反复请求（红）；m2：target 变不清缓存 ⇒ 跨 revision 串味（红）。

---

# Batch D —— 档 2 前端画多边形（诉求②的渲染侧）

## T13 服务端发块多边形
**必须做**：overview/分块响应含**块多边形（外环 + 洞环）**；`height` 走 P3 结论的通道。
**变异**：m1：只发外环丢洞 ⇒ 洞被填实（红——用真档里的环/洞断言）。

## T14 前端 Pass 1 画多边形
**必须做**：`render()` 改为 Pass 1 `fill('evenodd')` 画块 + Pass 2 只画**未合并余量**（若 P6 全部建块则余量为 0）；边界描边改为**沿块边界**（而非逐 hex）。
**变异**：m1：不画洞（`nonzero` 替代 `evenodd`）⇒ 视觉红；m2：Pass 2 仍画全部 hex ⇒ "路径重建 ops 数" 断言红。

## T15 判据端到端 + 关账
**必须做**：spec §五 六条判据**逐条实测值**（首屏字节/可交互耗时/帧时间/屏外不传/分割不变式/不退化）；M7 系列 e2e 全绿；每任务变异汇总；**"我未能核实的"清单**；关账报告。

---

## 批次与串行约束

```
T1(基线) → T2 → T3 → T4 → T5        [Batch A，T2~T5 文件多不相交，但建议串行以免踩 T1 基准]
T6 → T7 → T8 → T9 → T10             [Batch B：同文件，必须串行；后关账者重跑前者变异]
T11 → T12                            [Batch C]
T13 → T14                            [Batch D]
T15 ← 全部
```
★ A 与 B 的 T6 **可并行**（文件不相交）——但**本机一次只跑一个 Maven**，故只在"一方在写代码、另一方在跑测试"时才有真并行。
★ **T6 是分水岭**：它之前都是"不改数据结构"的止血；它之后是地基改动。
