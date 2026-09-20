# M9 T11/T12 报告 —— 地图分块传输（"屏外不传"）+ HiDPI(dpr>1) 实测

> 分支 `m9/t11`（基线 `a0c3694` = M8 T12 关账）。证据全部在 `.superpowers/sdd/2026-09-19-map-perf/t11-evidence/`。
> 真档 = 本会话用 `tools/gsimap_import.py` 从 `~/DevMosire/GSimulator/worlds/test_integration/nodes/n0000_map.json`
> 重新导入的 19441 格档（原 `/tmp/m6-import-verify/test_integration` 在本机已被清空：`revisions=0`）。
> **全程未动** `5817`/`5818`（实测 5817 在监听，是用户会话）；自起的 5831/5832/5833 收尾后清理（见 §10）。

## 0 一句话结论

**T11/T12 的字面形式（视口分块端点）在当前架构下已无意义**（量化证明见 §1），改为实现 spec 的**意图**——
减少每次传输/解析的字节：**块顶点由 `{"x":…,"y":…}` 浮点对象改为整数标签 `[u,w,…]`**，
`/api/map/overview` **227,377 → 78,648 B（−65.4%）**，首屏总字节 **753,955 → 458,376 B（−39.2%）**，
**帧率零退化**（pan p50 16.7ms 两版相同），**HiDPI dpr=2 实测锐利**（过渡带 0 设备像素、pixel 对拍 6/6、pan 182 帧/3s）。
干净轮 e2e **16/16 PASS ×2（dpr=1/2）**、旧调试页 **4/4**；**4 轮变异全部 KILLED**；`./mvnw clean verify` **rc=0、977 条**。

## 1 ★ 判定：字面 T11/T12 无意义（量化证明，装置 `analysis/bbox-chunking-proof.py`）

spec §四·档 1 的字面要求是"服务端新增分块端点、只返回该块内 hex、屏外不请求"。**当前架构下它不可达**：

| 证据 | 实测值 | 含义 |
|---|---|---|
| overview 载荷构成 | **blocks 226,243 B = 99.5%**（227,377 总计） | 载荷几乎全是块多边形；**逐格地形通道已不存在** |
| 逐格数据 | `hexes` 键 **0**、`height` 键 **0**（e2e 断言 `wire-no-per-hex-channel`） | "屏外 hex 不传"以**更强形式**达成：**压根没有逐格通道可传** |
| 最大块 `plains@-80_0` | **10,496 格（54%），bbox 覆盖全图 86.02% 面积**，占 **42.63% 顶点** | 块是**同地形连通分量** ⇒ bbox 极扁/极大；**bbox 交集**是最宽判据，任一视口几乎必然命中它 |
| 分块最好情况（视口面积占比 f，遍历 64 个放置取**最小**被取顶点占比） | f=2% → **45.1%**；f=10% → **46.5%**；f=25% → **65.3%**；f=50% → **79.5%**；f=100% → **100%** | 即便视口只占 2% 面积，仍要传 **45%** 的顶点 ⇒ **省不掉**；初始 fit 视图 f≈100% ⇒ **省 0%** |
| pan/zoom 网络 | **0 条 map-data 请求**（e2e `pan-zoom-issue-no-map-data-requests`，见 §5） | 现状 pan/zoom **不发任何取数**；分块反而会在跨块时**反复请求**（净变差） |

**⇒ 结论**：分块端点在"44 个连通块、最大块 bbox 覆盖 86%"的数据形态下，**初始加载省 0%、最激进也省不到一半**，
且会给本来零请求的 pan 引入请求 churn。**硬造端点 = 为了形式牺牲性能**（spec §七.4 的判据"载荷降到块数量级"已在 T13 达成）。

## 2 ★ 执行期取代说明（本项目惯例）

> **取代**：计划 T11「服务端分块端点 `GET /api/map/terrain?chunk=…`」+ T12「前端块缓存 + 视口驱动加载 + LOD」。
> **理由**：§1 的量化证明（架构已无逐格通道；块 bbox 交集收益趋零且引入 pan 请求）。
> **替代实现**（满足 spec 的**意图**："减少每次交互的传输/解析"）：把块多边形的**线格式**由浮点坐标对象
> `{"x":-69.282,"y":1.5}` 改为**整数顶点标签** `[u,w]`（`u,w` 本就是 `HexVertex` 的整数身份，
> `x=u·√3/2、y=w/2` 客户端精确还原）。这条是 T13 报告 §10-3 自己列出的**未做省流项**，现补上。
> **保留**：视口驱动的"不传"语义以**更强形式**存在——`blocks` 是唯一地形通道，逐格数据从未进入网络。

## 3 改动（逐文件，`git diff --stat`：6 文件 +123/−50 + 1 新文件）

| 文件 | 改动 |
|---|---|
| `simos-app/.../gui/ApiViews.java` | `blockViews` 的 `boundaries` 改发**整数标签环** `[u0,w0,…]`（闭合）；新增 `closedLabelRing`；删 `vertexView`/`quantize`/`closedRingView`（浮点坐标与量化不再需要，**反而更精确**）。 |
| `webui/blocks.js` | 新增 `vertexXY`/`decodeRing`/`decodeBlocks`（**唯一**格式转换点：整数标签 → `{x,y}`）；导出 `decodeBlocks`。 |
| `webui/api.js` | `cachedGet` 支持解码器；新增 `decodeOverview`（在**取数层**一次性解码，结果进缓存 ⇒ 下游形状不变）；`mapOverview`/`cachedMapOverview` 均解码。 |
| `simos-app/.../gui/MapOverviewBlocksTest.java` | 环断言由"量化 x/y"改"整数标签 + 偶长 + 闭合"；★ **新增强护栏**：中心格顶点标签集 == `HexVertex.at(hex,corner)` 六点（钉住"标签不是任意整数"）；断言全 JSON 无 `"x":`/`"y":`。 |
| `simos-app/src/test/js/block-codec.test.cjs`（新） | 6 条：坐标还原逐值、元数据保留、环闭合/洞数、**不污染输入**、`terrainAt` 走解码块、`countsByTerrain`。 |
| `simos-app/src/test/js/run-gate.cjs` | `MIN_TESTS` 82 → **88**。 |
| `simos-app/src/test/js/gate-contract.test.cjs` | `MIN_ASSERTIONS` 82 → **88**；`REQUIRED_FILES` 加 `block-codec.test.cjs`（**两处下界同改**，T11 的旧欠账）。 |

## 4 实测：传输 / 帧率 / HiDPI

### 4.1 传输（真档 19441 格，同装置）

| 指标 | PRE（`a0c3694` 字节） | POST | Δ |
|---|---|---|---|
| `/api/map/overview` 字节 | **227,377** | **78,648** | **−148,729（−65.41%）** |
| overview 两次调用逐字节 | — | **md5 `bbff3323bc27878aca7492ba660250c8` 相同** | 确定 |
| 首屏 `apiBytes`（12 次） | 466,623 | **169,165** | **−63.75%** |
| 首屏 `allBytes` | 753,955 | **458,376** | **−39.2%** |
| `firstInteractiveMs` | 262.8 | 254.9 | 持平 |
| 块数 / 环数 / 顶点数 | 44 / 64 / 9,694 | 44 / 64 / 9,694 | 几何不变 |
| 离线 gzip(level 6) 全资源 | — | **109,844 B**（overview 单独 **25,945 B**） | 判据 2 在启用 gzip 时成立 |

> ★ **判据 2（首屏 <150KB gzip 后）**：启用 gzip 时实测 109,844 B < 150KB **成立**；但 **P4 已把 gzip 降为可选**，
> 故线上仍是不压缩的 **458,376 B**（这是未启用 gzip 的诚实读数，不是"已达标"）。

### 4.2 帧率（`measure.cjs`，同装置）

| 指标 | PRE | POST |
|---|---|---|
| pan 3s 帧数 / p50 / p95 | 181 / 16.7 / 17.0 ms | **181 / 16.7 / 17.0 ms** |
| zoom 帧 | 见 `measure-*.json` | 无退化 |

### 4.3 ★ HiDPI（dpr>1）—— 本机 dpr=1，用 Playwright `deviceScaleFactor:2` 造

| 指标 | dpr=1 | **dpr=2** | 判读 |
|---|---|---|---|
| `canvas.width×height` | 1280×800 | **2560×1600** | backing store 随 dpr 缩放（`hidpi-canvas-backing-scaled` PASS） |
| 设备坐标像素对拍（6 格，权威 `/api/map/hex`） | 6/6 | **6/6**（dist ≤12） | 证明**变换含 dpr**（若漏 dpr，`pt×2` 会落到错格/界外） |
| ocean↔plains 边界过渡带 | **1 设备像素** | **0 设备像素** | **锐利**（若 1x 渲染后放大，过渡带会 ≥2dpr） |
| 边界最大相邻像素色差 | 144 | **154** | dpr=2 更接近两地形满距（ocean↔plains ≈179）⇒ 更锐 |
| pan 3s 帧数 / p50 | 182 / 16.7 ms | **183 / 16.7 ms** | HiDPI 无帧率退化 |
| pageerror | 0 | **0** | — |

> **锐度判据的诚实边界**：`canvas.width==cssW·dpr` + "设备坐标像素对拍 6/6" 两条**直接**证明渲染发生在设备分辨率
> （漏 dpr 的两种实现都会被杀，见变异 s2）；过渡带/色差是**佐证**。见 §9。

## 5 干净轮 e2e（`harness/t11-e2e.cjs`，真 Chromium 1234，1280×800）

| 断言 | dpr=1 | dpr=2 |
|---|---|---|
| `wire-no-per-hex-channel` / `wire-no-xy-objects-uv-int-labels` / `wire-single-shot-under-150k` / `wire-two-calls-byte-identical` | ✅ | ✅ |
| `blocks-partition-covers-all`（`unmergedCount=0`） | ✅ | ✅ |
| `hidpi-canvas-backing-scaled` | ✅ | ✅ |
| `pixel-{0,0}/0,-1/3,18/-11,-1/-5,-59/18,53`（6 条） | ✅ 6/6 | ✅ 6/6 |
| `click-selects-hex`（左栏出现 `plains`） | ✅ | ✅ |
| `pan-zoom-issue-no-map-data-requests` | ✅ **[]** | ✅ **[]** |
| `pan-frames-at-least-30fps` | ✅ 182 | ✅ 183 |
| `hidpi-transition-is-sharp` | ✅ | ✅ |
| **合计** | **16/16** | **16/16** |

- 点选一次发出的请求（实测）：`GET /api/map/hex?q=0&r=0…`、`GET /api/social/population?q=0&r=0…`、`GET /api/map/region/test_nation…`
  —— **不含任何地形整图重取**。
- pan/zoom 期间只出现后台轮询 `GET /api/state`、`GET /api/approvals`（**非视口驱动**，两版皆有）⇒ 断言只卡 map-data 通道。
- 旧调试页 `/map`：**4/4 PASS**（44 块、19441 格、点选读到 terrain）。
- ★ **诚实披露**：`final-dpr1` 的**首次**跑出现一次瞬态卡顿（3s 内仅 52 帧、p50 仍 16.7ms），
  紧接的两次重跑均 **182 帧 rc=0** ⇒ 判为**新起 JVM 的预热/首次任务**，非代码缺陷（`logs/final-dpr1.log` 为重跑后的绿轮）。

## 6 门禁数字（`./mvnw clean verify`，前台，日志 `logs/clean-verify.log`）

**rc=0**；Java **977 = 170/362/45/131/169/100**（六模块合计）；**7/7 模块 SUCCESS**；
`BugInstance size is 0` **×6**；`[ERROR]` **0 行**；`[frontend-gate] OK tests=88 pass=88 fail=0`。

- **Java 条数与 M8 关账同为 977**：只改了既有 `MapOverviewBlocksTest` 的断言，**未新增 Java 用例方法**。
- **前端 82 → 88**：新增 `block-codec.test.cjs` 的 6 条，**两处下界**（`run-gate.cjs` + `gate-contract.test.cjs`）与 `REQUIRED_FILES` 已同改。

## 7 变异表（4 轮，全部 KILLED；装置 `mutants/mut-round.sh`，九道门禁逐轮自证）

| 轮 | 载体 | 变异 | 杀点（实测原文） | 结果 |
|---|---|---|---|---|
| **j1** | Java `ApiViews.closedLabelRing` | 丢掉闭合重复点 | `[环首尾同点（u 闭合）] expected:-1 but was:-3` | **KILLED**（surefire Tests=2 Failures=1，mtime 本轮内） |
| **j2** | Java `ApiViews.closedLabelRing` | 交换 u/w（含闭合点） | `[顶点恰是 HexVertex.at(hex,corner) 的整数标签]` | **KILLED** |
| **s1** | JS `blocks.js vertexXY` | y 尺度 0.5 → 0.25 | `not ok 1 - decodeBlocks-restores-vertex-world-coordinates` | **KILLED**（node 门禁） |
| **s2** | JS `map.js resize` | `dpr = window.devicePixelRatio\|\|1` → `dpr = 1` | `hidpi-canvas-backing-scaled` + 全部 `pixel-*` + `hidpi-transition-is-sharp` 红 | **KILLED**（dpr=2 e2e） |

- 九道门禁：① 干净世界 md5（目标==原件）② 变异体字节确实不同 ③ 按白名单推成目标名 ④ 清陈旧 `.class`（Java）
  ⑤ `COMPILATION ERROR`=0 且 `Tests run≥1` ⑥ surefire 报告 mtime 落在本轮内 ⑦ 红点**必须**落在被保护的那条断言上
  ⑧ `cp` 逐字节还原（**绝不 `git checkout --`**）⑨ 日志自指（`orig_md5`/`mutant_md5`/`restored_md5` 写入日志）。
- ★ **j2 的一处纠偏**：首版 j2 只换循环内的 u/w，结果**红在闭合断言而非标签断言**——按"红了要问为什么红"，
  改为**含闭合点一起换**后，红点才落到标签集断言（`顶点恰是…`）。
- ★ **spotless 使旧证据失效**：`spotless:apply` 改了 `ApiViews.java` 的字节（md5 `5a63fe8c…` → `08b87b2b…`），
  j1/j2 已**对当前字节重跑**（`orig_md5=08b87b2b…`）；s1/s2 的 JS 文件未受 spotless 影响（md5 未变）。

## 8 实测 vs 推断（两栏）

| 项 | 类型 | 依据 |
|---|---|---|
| overview 227,377→78,648、两次 md5 相同、44 块/64 环/9694 顶点、全 int 标签 | **实测** | `raw/overview-{pre,post}-t11.json`、e2e `wire-*` |
| 首屏 apiBytes/allBytes/firstInteractive、pan 181 帧 | **实测** | `logs/measure-{PRE,POST}.json` |
| 块 bbox 86.02%、分块最好情况 45.1%@2% 视口 | **实测** | `analysis/bbox-chunking-proof.py`、`raw/bbox-chunking.json` |
| dpr=2：canvas 2560×1600、pixel 6/6、过渡带 0、154 色差、183 帧 | **实测** | `logs/final-dpr{1,2}.json` |
| pan/zoom 0 map-data 请求；点选 3 请求 | **实测** | `logs/final-dpr*.json` 的 `viewportMapDataRequests`/`selectRequests` |
| 全量 977 绿、BugInstance 0×6、前端 88 | **实测** | `logs/clean-verify.log` |
| 4 轮变异全杀 | **实测** | `logs/mut-{j1,j2,s1,s2}.log(.run.log)` |
| gzip 后 109,844 B | **实测（离线）** | §4.1 的离线压缩（**未在服务端启用 gzip**） |
| "过渡带 0 设备像素"的成因（Skia 内部） | **推断** | 只测了观测值；未深入 Skia |
| 其它真档 / 跨 JVM 的块规模与字节 | **推断** | 只跑 `test_integration` 一份真档；字节稳定性靠整数标签与块序，未跨 JVM 实测 |

## 9 我未能核实的

1. **gzip 未在服务端启用**：§4.1 的 gzip 数字是**离线压缩**，判据 2 只在"启用 gzip"前提下成立（P4 把 gzip 降为可选，本单未做）。
2. **"锐利"的强判别**：`canvas.width==cssW·dpr` + 设备坐标 pixel 6/6 能杀两种 dpr 实现缺陷；
   但"设备分辨率矢量渲染"与"1x 渲染后 2x 放大"在**过渡带宽度**上的差异不总是显著——故锐度结论以**前两条直接证据**为主，过渡带为佐证。
3. **瞬态卡顿**：§5 首次 dpr=1 跑的 52 帧未复现（两次 182 帧），未定位其确切触发（疑 JVM/浏览器预热）。
4. **其它真档**：只对 `test_integration` 一份跑；`demo` 未跑本 harness。
5. **跨 JVM 字节稳定**：整数标签 + `BlockId` 全序在逻辑上确定，但**未跨 JVM 实测**响应逐字节。
6. **分块端点的"如果真做"**：未实现，故 §1 的收益上界是**几何推导 + 实测 bbox**，不是端到端跑出来的省流值。
7. **M7c/M7e 浏览器 e2e 的既有失败**（T2/T13 的欠账）本单未触碰、未定位。

## 10 证据索引

```
t11-evidence/
  t11-report.md
  analysis/bbox-chunking-proof.py            # 分块不可达性量化证明
  harness/t11-e2e.cjs                        # 线格式 + pan/zoom 网络 + 点选 + dpr 锐度/帧率
  mutants/mut-round.sh                       # 九道门禁变异装置（自指 md5）
  mutants/orig/{ApiViews.java,blocks.js,map.js}
  mutants/{j1,j2}/ApiViews.java  mutants/{s1}/blocks.js  mutants/s2/map.js
  logs/clean-verify.log + verify-rc.txt      # rc=0 / 977 / BugInstance 0×6 / ERROR 0 / 前端 88
  logs/measure-{PRE,POST}.json(.log)         # 首屏字节 + pan 帧（before/after）
  logs/final-dpr{1,2}.json(.log)             # 干净轮 e2e（16/16 ×2）
  logs/final-oldpage.json                    # 旧调试页 /map 4/4
  logs/t11-e2e-PRE-dpr{1,2}.json(.log)       # 旧字节对照（14/16，线格式两条按预期红）
  logs/mut-{j1,j2,s1,s2}.log(.run.log)       # 4 轮变异
  logs/bbox-chunking.log
  raw/overview-{pre,post}-t11.json           # 227,377 / 78,648 原始响应
  raw/bbox-chunking.json
```

**收尾**：自起的 `5831`（旧字节）/`5832`/`5833` 已关闭；`5817`（用户会话）全程未动。
