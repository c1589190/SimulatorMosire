# M9 T13+T14 报告 —— 块多边形端到端（服务端发块 + 前端画块）

> 分支 `m9/t13`（基线 `df44dd6` = M9 T6 关账）。证据全部在 `.superpowers/sdd/2026-09-19-map-perf/t13-evidence/`。
> 真档 = `/tmp/m6-import-verify/test_integration`（19441 格）；**只操作副本** `/tmp/m9t13/arch-live`，原档 md5 跑前/跑后均 `2348b9365e5b107945a305d06fad8fab`。
> `5817/5818` **全程未动**（实测仍在监听）；我自起的 5821/5822/5823/5824/5825 **已全部收干净**（实测无监听）。

## 0 一句话结论

服务端 overview **只发 44 个权威块多边形**（不再发逐格地形），客户端 **Pass 1 画块（evenodd 带洞）+ 沿块边界描边 + 点在多边形内拾取**：
overview **703,053 → 227,377 B（−67.66%）**，同一状态两次调用**逐字节相同**；真档 e2e **26/26 PASS**、0 pageerror；全量门禁 **869**（+2）；**6 轮变异全部 KILLED**。

## 1 ★ 关键裁定：`hexes` 逐格数组**整个删除**（与派单 A2 的"优先保留"相反，理由如下）

| 依据 | 内容 |
|---|---|
| spec §七.4（原文） | 「服务端**发块多边形**而非逐格 ⇒ 载荷从 1,043,837 B 降到**块数量级**」 |
| spec §四·档 2 / 派单 §2 | 「客户端**根本不需要逐格 terrain** ⇒「没有进入窗口范围的 hex 不加载」以**更强的形式**达成：**压根不传逐格数据**」 |
| 派单 A2 | 「去留由**你**决定并报告：**优先保留**…但评估删除后的字节；若删除，必须确认全仓无消费者」 |

**★ 两处口径冲突，我选了 spec/设计目标（删除）并指出**：A2 的"优先保留"与 §2 的"压根不传逐格数据"、§7.4 的"块数量级"直接矛盾。保留 `{q,r}` 只能降到 ~340KB（−52%），达不到 §7.4 的"块数量级"；删除后 **227,377 B**（−67.66%）且逐格地形在客户端**已无任何渲染/拾取用途**。

**删除后的全仓消费者核对（`git grep`）**：
- `webui/`：无任何 `overview.hexes` 读取（`region.hexes` 来自 `/api/map/region/{id}`，**未受影响**）。
- `map.js`：`terrainCounts`（图例）改走 `SimosBlocks.countsByTerrain(blocks)`；`worldBounds` 改走块环顶点；`pickAt` 改走 `SimosBlocks.blockAtPoint`。
- `panels.js`：`movementReadout` 改走 `SimosBlocks.terrainAt(blocks,q,r)`（实测 `每格成本 1500 / 总成本 3000 / 预计到达 tick 7`，与 M7b 逐值相同）。
- **MCP `ToolSupport.mapOverview`**：是**另一份独立装配**（发逐格 height+terrain），**不消费** `ApiViews`，**未受影响**（`McpCoverageTest`/`SimosToolsTest` 全绿）。
- `/api/map/hex`：**未动**（仍发 `terrain`+`height`+`regions`+`facets`+`terrainType`）。
- 旧调试页 `map.html`：与工作台**共享**更新后的 `map.js` + `blocks.js`，实测 4/4 PASS（见 §6）。

## 2 改动（逐文件）

| 文件 | 改动 |
|---|---|
| `simos-app/.../gui/ApiViews.java` | `mapOverview` 增发 `blocks`、**删除 `hexes`**；新增 `blockViews/closedRingView/vertexView/quantize`；块按 `BlockId` 全序、顶点量化 3 位小数、环闭合。 |
| `simos-app/.../gui/MapOverviewBlocksTest.java`（新） | 2 条：块视图（洞/闭合/量化/分割/字节相同）+ 块顺序与状态插入序无关。 |
| `simos-app/.../gui/GuiApiTest.java` | `mapOverviewMatchesTheMapSlice`/`mapOverviewOmitsHeightWhileMapHexKeepsIt` 按新形状改（断言 `hexes` **不存在**、`blocks` 在场），**未改成恒真**。 |
| `simos-app/.../gui/WebuiAssetsTest.java` | 新资产 `blocks.js` 入 `ALL_ASSETS`（存在 + 打包）。 |
| `simos-map/.../guard/RepresentationLeakGuardTest.java` | `.terrain()` 的**唯一白名单**：`ApiViews.java`（T13 指定的块视图装配点）；其余文件一处不许；`HexCell::terrain` 全禁。 |
| `webui/blocks.js`（新） | 纯几何：`pointInBoundaries`（evenodd）、`blockAtPoint`、`terrainAt`、`countsByTerrain`。无 DOM/IO，map.js 与 panels.js 共用。 |
| `webui/map.js` | Pass 1 画块（同色聚合 Path2D + `evenodd`）；Pass 2 余量（恒 0）；边框沿块环一条 Path2D；`pickAt` 走块；`worldBounds` 走块环；图例走块；`setCellSize`/`resize` 适配；删 `hexes/hexIndex/visibleHexes/逐格 paint 分支`；debug 增块计数。 |
| `webui/panels.js` | `movementReadout` 地形走 `SimosBlocks.terrainAt`；删 `coordKey` 与逐格表。 |
| `webui/index.html` / `webui/map.html` | 引 `blocks.js`（相对路径，无 CDN）。 |

`git diff --stat`：8 文件 +270/−255（另有 2 新文件）。

## 3 实测：overview 字节数（旧→新）+ 块数 + 逐字节相同

| 项 | 值 |
|---|---|
| overview 字节（T6 基线） | **703,053 B** |
| overview 字节（现在） | **227,377 B**（−475,676，**−67.66%**） |
| `blocks` 载荷 | 44 块 / **64 条环**（**4 块带洞**）/ **9,694 个顶点** |
| 块 hexCount 之和 | **19,441 == hexCount**（余量 `unmergedCount = 0`） |
| 地形直方图（块口径） | `plains=10719  ocean=4506  low_hills=3330  mountains=886`（与 T6 逐值相同） |
| 两次调用逐字节 | **md5 均 `9eb39b19a3c736beaa06eea02bd1495e`**（`BYTE_IDENTICAL=yes`） |
| 环闭合 / 顶点 > 2 / 量化 | bad=0（首尾同点、每条环 > 2 顶点、全部 ≤3 位小数） |

> 洞实例：`low_hills@-50_-10` rings=[1091,7,115]、`ocean@18_53` rings=[821,11]。

## 4 T6 → 现在 对照表（真档副本、1280×800、同 harness 口径）

| 指标 | T3 | T6 | **现在** | 判定 |
|---|---|---|---|---|
| `firstInteractiveMs` | 420.7 | 441.2 | **304.3** | 更快 |
| `apiBytes` | 1,406,966 | 1,406,966 | **455,614** | −67.6% |
| `allBytes` | 1,578,149 | 1,578,149 | **628,769** | −60.2% |
| `apiCount` | 11 | 11 | **11** | 不变 |
| `render()` p50/p95 (ms) | 1.65/3.25 | 1.75/2.075 | **0 / 0.1** | 离屏 blit |
| `render()`（scale=3）p95 | — | — | 1.75 | 无退化 |
| pan 3s 帧数 / p50 | — | 165~167 | **181 / 21.7ms** | ≥30fps |
| zoom 2s 帧数 / p50 | — | — | 120 / 17.9ms | 好 |
| `pageErrors` | [] | [] | **[]** | 0 |
| 启动非 GET 请求 | — | — | **[]** | ⊆ `{/api/command,/api/advance,/api/fork}` |

## 5 真档端到端：点选对拍值 + 截图

对拍口径：**`/api/map/hex`（逐格权威，未改）** vs **前端块多边形拾取 `SimosMap.hexAtScreen(screenPointOf(q,r))`**；height 取 `/api/map/hex` 与 M6 旧 `overview.json` 对拍。**10/10 全部一致**（含 4 个"洞内格"）：

| (q,r) | 旧 overview terrain/height | `/api/map/hex` terrain/height | 前端块拾取 terrain | 一致 |
|---|---|---|---|---|
| (0,0) | plains / 0.375 | plains / 0.375 | plains | ✅ |
| (-5,-59) | plains / 0.375 | plains / 0.375 | plains | ✅ |
| (-64,72) | low_hills / 0.6 | low_hills / 0.6 | low_hills | ✅ |
| (18,53) | ocean / 0.15 | ocean / 0.15 | ocean | ✅ |
| (1,1) | plains / 0.375 | plains / 0.375 | plains | ✅ |
| (40,-40) | plains / 0.375 | plains / 0.375 | plains | ✅ |
| **(-42,-1)** ★洞 | plains | plains | **plains**（不是外层 low_hills） | ✅ |
| **(-11,56)** ★洞 | mountains | mountains | **mountains**（不是 low_hills） | ✅ |
| **(69,-78)** ★洞 | plains | plains | **plains**（不是 ocean） | ✅ |
| **(-68,3)** ★洞 | mountains | mountains | **mountains**（不是 plains） | ✅ |

- **像素色**：10 个采样点在 canvas 上的实测 RGB 与后端 `terrainTypes[].color` 距离 ≤12（全部通过）⇒ 填色/洞的视觉正确。
- **点选**：点 (0,0) ⇒ 左栏出现 `terrain plains（平原） / height 0.375 / regions test_nation`。
- **区域面板**：`/api/map/overview` 2 区域、`/api/map/region/{id}` 200 且有 hexes、`setHighlightRegions` 后 `highlightHexCount > 0`。
- **截图**：`t13-evidence/screenshot-5821.png`（块多边形地图，图例 `共 19441 格 · 2 区域 · 单位 0 · ocean=4506 plains=10719 low_hills=3330 mountains=886`，与 T6 直方图逐值相同）。

## 6 不破坏既有面

| 面 | 装置 | 结果 |
|---|---|---|
| 工作台 `/` | `harness/t13-e2e.cjs` | **26/26 PASS**、0 pageerror、非 GET [] |
| 旧调试页 `/map` | `harness/t13-oldpage.cjs` | **4/4 PASS**（200、44 块载入、点选读到 `plains`、0 pageerror） |
| 单位移动读数（`panels.js`） | `harness/t13-panels.cjs` | **6/6 PASS**：`每格成本 1500 / 总预算 2000 / 总成本 3000 / 预计到达 tick 7`（与 M7b 逐值相同） |
| M7c e2e | 本构建（demo） | FAIL `c-timeline-node-plus-1` |
| M7e e2e | 本构建（demo） | FAIL `a-selection-cleared,b-first-click-no-write,b-node-plus-1,d-node-plus-1` |

**★ M7c/M7e 是既有失败，非本单引入（对照实验，T2 的做法）**：用 `df44dd6` 的旧后端+旧前端（主树 `target/classes`，`map.js` md5 `63cc339…`、无 `blocks.js`）起 5825 跑**同一脚本**，红点**逐条完全相同**（m7c `c-timeline-node-plus-1`；m7e 四条同上）。日志 `logs/old-m7c-e2e.log`、`logs/old-m7e-e2e.log`。

## 7 门禁数字

`./mvnw clean verify`：**rc=0、869 条 = 170/272/45/131/155/96、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0 行**（`logs/full-verify.log`）。

| 模块 | 基线(867) | 现在 | delta | 解释 |
|---|---|---|---|---|
| UtilSimos | 170 | 170 | 0 | 未动 |
| MapSimos | 272 | 272 | 0 | 仅 `RepresentationLeakGuardTest` 加白名单，条数不变 |
| SocialSimos | 45 | 45 | 0 | 未动 |
| UnitSimos | 131 | 131 | 0 | 未动 |
| CoreSimos | 155 | 155 | 0 | 未动 |
| SimosApp | 94 | **96** | **+2** | `MapOverviewBlocksTest`（2 条） |
| **合计** | **867** | **869** | **+2** | |

## 8 变异表（6 轮，全部 KILLED；装置 `mutants/mut-round.sh`，每轮自指 md5）

每轮：还原原件→断言 md5==原→推变异体（断言字节不同）→跑目标→断言无编译错误 + 红点命中→还原→断言 md5 复原。汇总 `logs/mut-*.log.summary`。

| m | 护栏 | 变异 | 目标 | 红点（实测原文） | 结论 |
|---|---|---|---|---|---|
| **m1** | 环含洞 | `blockViews` 只发第 1 条环 | `MapOverviewBlocksTest` | `[外圈块带一个洞环 ⇒ 2 条环]` | **KILLED** |
| **m2** | 块序确定 | 去掉 `BlockId` 全序排序（靠状态插入序） | `blockOrderIsCanonical…` | `[块按 BlockId 全序发]` | **KILLED** |
| **m2b** | 两次字节相同 | 每次调用 `shuffle(out)` | `overviewEmits…` | `[同一状态两次 overview 逐字节相同]` | **KILLED** |
| **m3** | evenodd 画洞 | `fill(...,"evenodd")` → `fill(...)`（nonzero） | `t13-e2e.cjs` | `FAIL pixel--68_3 expected mountains 122,127,133 got 156,203,91 dist=152` | **KILLED** |
| **m4** | 同地形合并（诉求②） | `TerrainBlocks.split` 不做连通合并（每格一块） | `MapOverviewBlocksTest` | `[中心 desert + 外圈 plains ⇒ 2 块]`（实得 7） | **KILLED** |
| **m5** | 拾取排除洞 | `pointInBoundaries` 任一环命中即算在内 | `t13-e2e.cjs` | `FAIL pick--42_-1 expected plains got low_hills`（另 2 条） | **KILLED** |

**★ 与派单 m2 的出入（指出派单写错的地方）**：派单 m2 说"HashMap 迭代 ⇒ 两次响应不逐字节相同"。**这在本机 JVM 下不成立**：`HashMap`/`HashSet` 对**同一键集**的迭代序在同一 JVM 内是确定的（`BlockId` 是值 record，hashCode 纯函数），两次调用仍逐字节相同。⇒ 我把 m2 落成**"块序不靠插入序"**（真风险：`MapChangeSet.apply` 会按命令顺序追加块），并**另补 m2b**（每次 shuffle）证明"两次字节相同"这条断言**真的有牙**。

**★ 与派单 m4 的出入**：派单 m4 是"Pass 2 仍逐格画全表 ⇒ 路径 ops/帧时间红"。**删除逐格载荷后该变异不可表达**（没有逐格表可画；`P6` 全部建块 ⇒ 余量恒 0）。⇒ 落成更强的等价护栏：**服务端不做连通合并（每格一块）** ⇒ 块数 2→7（真档 44→19441）、载荷暴涨、渲染退化，被块数断言杀。

## 9 "实测 vs 推断"两栏

| 项 | 类型 | 依据 |
|---|---|---|
| overview 703,053→227,377、两次 md5 相同、44 块/64 环/9694 顶点 | **实测** | `/tmp/m9t13/ov1.json`、`ov2.json` 逐字节 cmp |
| 10 点对拍（含 4 洞）、像素色 ≤12、点选、区域、截图 | **实测** | `harness/t13-e2e.json`、`screenshot-5821.png` |
| firstInteractive 304.3 / pan 181 帧 / render ~0 / 0 pageerror / 非 GET [] | **实测** | `harness/measure-now.json`、`logs/measure-now.log` |
| 旧页 `/map` 4/4、panels 6/6 | **实测** | `harness/t13-oldpage.json`、`t13-panels.json` |
| M7c/M7e 既有失败（旧字节同红） | **实测** | `logs/old-m7c-e2e.log`、`old-m7e-e2e.log` |
| 全量 869 绿、SpotBugs 0×6 | **实测** | `logs/full-verify.log` |
| 6 轮变异全杀 | **实测** | `logs/mut-*.log.summary` |
| 前端在 **HiDPI（dpr>1）** 下块拾取/像素的精度 | **推断** | 块环坐标乘 `cellSize` 与 dpr 无关，逻辑上成立；**未在 dpr=2 实测** |
| `blocks` 载荷在**其它真档**上的规模 | **推断** | 只跑了 `test_integration` 一份真档 |
| 跨 JVM 的响应字节稳定 | **推断** | 块序 `BlockId` 全序 + 顶点量化 ⇒ 生成确定；**未跨 JVM 实测** |

## 10 我未能核实的

1. **HiDPI（dpr>1）**：harness 固定 `deviceScaleFactor:1`；块环像素精度/点选在 dpr=2 未实测。
2. **其它真档**：只对 `test_integration` 一份跑；`demo` 只用于 M7/panels 回归。
3. **`blocks` 的更激进省流形态**：`[x,y]` 数组（约 −34%）或整数顶点标签 `{u,w}`（约 −66%）**未实现**；当前用 `{x,y}` 与全仓 JSON 风格一致。
4. **`/api/map/terrain` 分块端点（T11/T12）**：本单未做；块传输目前仍是**一次性整包 227KB**（spec §四·档 1 的"屏外不传"未在本单覆盖）。
5. **M7c/M7e 失败的根因**：仍只证"old==new 同红"，未定位触发条件（T2 的欠账延续）。
6. **gzip**：P4 已降级为可选，本单未做；227KB 在 gzip 下未实测。

## 11 证据索引

```
t13-evidence/
  t13-report.md
  screenshot-5821.png                     # 真档块多边形渲染
  logs/full-verify.log                    # 869 绿
  logs/measure-now.log                    # harness 量测
  logs/server-5821.log / server-5824*.log / server-5825-old-demo.log
  logs/m7c-e2e.log / m7e-e2e.log          # 本构建
  logs/old-m7c-e2e.log / old-m7e-e2e.log  # 旧字节对照
  logs/mut-m{1,2,2b,3,4,5}.log(.summary)  # 变异逐轮
  harness/measure.cjs                     # 复用自 t2-evidence（T6 报告已指出它不在 t3）
  harness/t13-e2e.cjs  + t13-e2e.json / t13-e2e-clean.json
  harness/t13-oldpage.cjs + .json
  harness/t13-panels.cjs + .json
  mutants/mut-round.sh
  mutants/m{1,2,2b,3,4,5}.*               # 变异体
  mutants/orig/{ApiViews,TerrainBlocks}.java, map.js, blocks.js
```
