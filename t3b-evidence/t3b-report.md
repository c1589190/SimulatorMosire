# M7b T3 报告 —— 钢铁雄心式右键移动（服务端 A\* → `unit.PlanRoute` → 画线）

> 分支 `m7b/t3`（基线 `faf6aff`），工作树 `.claude/worktrees/m7bt3`。**未 push**。
> 交付：**一个只读 GET 端点** + 前端右键接线 + Java/前端各自护栏；**未碰 `simos-core`**、未加写端点、未加依赖、未改台账 `progress.md`、未加 npm/CDN。

## 一 改了什么 / 为什么

| 文件 | 改动 | 为什么 |
|---|---|---|
| `simos-app/.../gui/ApiViews.java` | `pathResult(reachable, path)`：`{reachable, path:[{q,r}…]}` | 新端点的 JSON 视图；`path` 原样透出 `PathFinder` 的逐格序列（可当 PlanRoute 的 waypoints） |
| `simos-app/.../gui/GuiServer.java` | 新只读路由 `GET /api/map/path` + `mapPathReply` | 起点由服务端 `effectivePosition` 取（权威）；内部调 `PathFinder.findPath`，**不在 app 层另写寻路**（避免第二份真相） |
| `simos-app/.../webui/api.js` | `mapPath(unitId,q,r,target)` + 导出 | 只读取数层新增一个 GET，带上 `{branch,revision}` 目标 |
| `simos-app/.../webui/map.js` | canvas `contextmenu` 监听 + `handleContextMenu` + `submitPathRoute` | 右键 = 服务端 A\* ⇒ `app.writeCommand("unit.PlanRoute", {id, waypoints: path})`（**唯一写入口**）⇒ 写后 `refreshState` ⇒ T2 折线自绘 |
| `simos-app/.../gui/GuiApiTest.java` | +5 用例 | 端点逐值（3 点逐格相邻）、图外 `reachable:false`、未知单位 404、**只读不推进 head**、**path 可原样喂 PlanRoute** |

**关键纪律落点**：
- **起点服务端取**：前端根本不传起点 ⇒ 没有"前端算起点"的第二份真相。
- **寻路只有一份**：app 层直接调 `PathFinder.findPath(map,start,goal,unit,TerrainMovementCost.INSTANCE)`；`PathFinder` 从此**有了生产调用者**（此前零接线）。
- **写面唯一**：右键成功后走 `app.writeCommand` → `POST /api/command`；R8 allowlist 实测只有 `/api/command`。
- **只读模式守卫在 map.js 的 `handleContextMenu`**：`app.getState().mode !== "unit"` 立即 `return false`（不 preventDefault、不发任何写）。这是变异 m2 的被保护点。

## 二 门禁数字（逐模块）

`./mvnw clean verify` 绿（rc=0），日志 `logs/full-verify-final.log`：

| 模块 | 基线（T2 关账） | T3 后 | Δ |
|---|---|---|---|
| UtilSimos | 170 | 170 | 0 |
| MapSimos | 255 | 255 | 0 |
| SocialSimos | 45 | 45 | 0 |
| UnitSimos | 131 | 131 | 0 |
| CoreSimos | 154 | 154 | 0 |
| **SimosApp** | **81** | **86** | **+5** |
| **合计** | **836** | **841** | **+5** |

- `+5` = `GuiApiTest` 18→23（新增 5 条：`mapPathReturnsStepByStepCorridorPath` / `mapPathIsUnreachableForHexOutsideTheMap` / `mapPath404sForUnknownUnit` / `mapPathIsReadOnlyAndDoesNotAdvanceHead` / `mapPathResultFeedsPlanRoute`）；另在 `unknownApiPathIs404AndWrongMethodIs405` 内补一条 `/api/map/path` 的 405/GET 断言（不新增用例）。
- `BugInstance size is 0` ×6、`[ERROR]` 0 行。
- 定向轮 `logs/targeted.log`：`GuiApiTest` 23/0/0、`WebuiAssetsTest` 8/0/0。

## 三 e2e a~g 实测值（真 `ShellMain --demo` + Playwright + 真 socket）

装置 `e2e/run-e2e.sh`（`--demo`、全新 store、端口 5821）；驱动 `e2e/e2e.cjs`；全量输出 `logs/e2e-clean.log`。

| 步 | 断言 | 实测 |
|---|---|---|
| a | 切「单位移动与编辑」、点选 u-1 | `a-mode-unit PASS unit`；`a-unit-selected PASS {"kind":"unit","id":"u-1"}` |
| b | 右键 (1,3) ⇒ `/api/map/path` | `status=200`、`reachable=true`、`PATH_B=(1,1)->(1,2)->(1,3)`（**3 点、逐格相邻、含首尾**）|
| c | PlanRoute committed / head+1 / 时间轴+1 / route.path==3 点 / 折线 3 点 | `rev 1→2`；`/api/unit/u-1` 的 `movement.route.path` = (1,1),(1,2),(1,3)；时间轴 DOM 节点 `1→2`；`debug().routes` `totalPoints=3`、`status=IN_TRANSIT` |
| d | 右键 (1,2) ⇒ **替换** | `D_PATH=(1,1)->(1,2)`；`route.path` 变 2 点（**替换而非追加**）；`rev 2→3`；折线 `totalPoints=2` |
| e | 右键图外 (2,1) | `hexAtScreen` 实测 `inMap=false`；`/api/map/path` 返回 `{"reachable":false,"path":[]}`；非 GET `2→2`（**无写**）；UI `不可达：u-1 → (2,1)` |
| f | 切回「常规查看」后右键 | `body[data-mode]=view`；非 GET `2→2`（**只读模式不被污染**）|
| g | R8 allowlist | `NON_GET_LIST=["/api/command","/api/command"]`，⊆ `{/api/command,/api/advance,/api/fork}`，violations=[] |
| z | 页面零错误 | `pageErrors=[]` |

- 变异后**干净轮重跑**：`logs/e2e-clean-after-mutants.log` —— `E2E RESULT: PASS`、`PATH_B=(1,1)->(1,2)->(1,3)`、`NON_GET_LIST=["/api/command","/api/command"]`。
- 截图 2 张：`screenshots/screenshot-route-polyline.png`（右键后地图折线；左栏 `已下路线: u-1 (2 格, 替换原路线)`、`路线格数 3`）、`screenshots/screenshot-unreachable.png`（左栏 `不可达: u-1 → (2,1)`）。

## 四 变异（九道门禁，2 轮）

装置 `mutants/t3b-mut-round.sh`（干净世界 → 变异体字节不同 → 清陈旧 `.class` → `COMPILATION ERROR=0` 且产出 → 服务器真起 → e2e 真红 → 红点是被保护步 → 源/class 逐字节还原 → **日志自指**）；生成器 `mutants/make-mutants.py`（锚点恰匹配 1 次 + 替换后字节必变自证）。

| 轮 | 类型/目标 | 变异 | 期望步 | 实测红点 |
|---|---|---|---|---|
| m1 | java `ApiViews.java` | `pathResult` 只返回**首尾两点**（≥3 时） | `b-path-3-points` | **`STEP b-path-3-points: FAIL (1,1)->(1,3)`**；连带 `b-path-stepwise/endpoints`、`c-*` 全红（path 2 点 ⇒ PartRoute 被 Route 相邻性拒绝 ⇒ head 不推进）；`E2E RESULT: FAIL`、`rc=1` |
| m2 | resource `map.js` | 删掉 `handleContextMenu` 的 **`mode !== "unit"` 守卫**（所有模式都提交 PlanRoute） | `f-view-mode-no-write` | **`STEP f-view-mode-no-write: FAIL {"nonGetBeforeF":2,"nonGetAfterF":3}`**；`NON_GET_LIST=["/api/command","/api/command","/api/command"]`（多出的正是常规查看下右键发出的写）；`E2E RESULT: FAIL`、`rc=1` |

- **红了问为什么红**：m1 的红因是返回值被裁剪成首尾（日志 `PATH_B=(1,1)->(1,3)`），不是别的；m2 的红因是常规查看模式多出一条 `/api/command`，与非 GET 清单逐项吻合。
- **自指**：每轮日志尾有"装置补记"段，含 `orig_md5`/`pushed_src_md5`/`pushed_classes_agg`/`restored_*`/`e2e_rc`/`server_log_mtime`；脚本在比较前**先断言聚合 md5 非空**（T2 的假绿同族预防）。
- 还原核验：`ApiViews.java`、`map.js`、`ApiViews.class`、`target/classes/webui/map.js` 的 md5 均回到备份值（脚本断言 + 人工复核）。
- 清单 `mut-manifest.md5`。

## 五 偏离候选（与派单书/spec 措辞不同处，以源码为准）

1. **进度文档 S3 写的是 `/api/map/path?from=&to=&unit=&branch=&revision=`；T3 派单书写的是 `unit=&q=&r=`。** 我按**派单书**实现（`unit`+`q`+`r`），因为起点必须由服务端取（前端传 `from` 就等于让前端决定起点 = 第二份真相），且这与既有 `/api/map/hex?q=&r=` 同形。S3 的 `from=` 应视为被本任务取代。
2. **右键落在单位自身所在格**（path 长度 1）：`Route` 构造期硬要求 `path` 至少 2 格 ⇒ 不可表示为"到当前格的路线"。前端**不发写**，提示"已在目标格（未改路线）"。派单书未规定此格；记为实现期决定。
3. **左键点目标格仍是 `PlaceAt`（瞬移）**（T7 既有行为）。M7b-U2 说 `PlaceAt` 不作为正常编辑手段，但 T3 的 MUST NOT 明确"不做 M8 地图编辑/不新增命令"，故**未动**左键流程。⇒ 右键是新增的移动语义，左键瞬移仍共存，**归 M7b 关账或后续裁决**。
4. **前端对图外 hex 仍调端点**（不短路 `pick.inMap`），以便服务端 `reachable:false` 可被观测（派单书步骤 e 要求观测该返回值）。

## 六 我未能核实的

- **折线未做像素级证明**（同 T2）：只有 `debug().routes.totalPoints` 钩子 + 截图。截图①里 3 点折线画在黄色沙漠上、与底色对比度低，**目视不易分辨**（同一张装置的第②张截图里 2 点折线反而清晰）——功能断言可靠，视觉对比度是既有配色（`ROUTE_BASE_COLOR` 半透明黄）遗留。
- **前端护栏不进 Maven 门禁**：右键模式守卫只由 e2e + 变异证（本项目系统性开口项：`AppWritePathGuardTest` 只扫 Java、不扫 `webui/**`）⇒ 有静默腐烂风险。
- **`ARRIVED` / `NEED_REPLAN` 状态下右键替换**未在页面观察（只覆盖 `IN_TRANSIT`）。
- **`区域查看`模式右键**未单独测（逻辑上走同一条 `mode !== "unit"` 分支返回 false；只测了 `常规查看`）。
- **单位无有效位置（`effectivePosition` 空）** ⇒ `reachable:false` 的分支只有 Java 代码路径，**无真用例**（demo 单位恒有位置）。
- **触摸/笔/移动端右键**未测；仅本机 headless Chromium。
- **非 demo 大图 / 远端点 A\* 的性能与响应体大小**未测（demo 3 格；M7 已记 overview 传输是瓶颈，寻路端点未量）。
- **`queryService.stateAt` 每次重放**的代价未在本端点单独量（沿用既有只读端点口径）。

## 七 证据索引

| 文件 | 内容 |
|---|---|
| `logs/full-verify-final.log` | 最终 `clean verify`（rc=0、841=170/255/45/131/154/86、BugInstance 0×6、ERROR 0） |
| `logs/targeted.log` | 定向 `GuiApiTest`(23)+`WebuiAssetsTest`(8) |
| `logs/e2e-clean.log` / `.server.log` | 干净 e2e a~g + 服务器日志 |
| `logs/e2e-clean-after-mutants.log` / `.server.log` | 变异后干净轮（PASS） |
| `logs/m1.log` / `m1.javac.log` / `m1.server.log` | m1 变异轮（含装置补记） |
| `logs/m2.log` / `m2.server.log` | m2 变异轮（含装置补记） |
| `e2e/e2e.cjs` / `run-e2e.sh` | e2e 装置 |
| `e2e/e2e-values.json`、`b-path.json`、`d-replaced-route.json`、`e-unreachable.json`、`g-nonget-list.json` | e2e 逐值输出（含 path 逐值 + 非 GET 清单） |
| `screenshots/screenshot-route-polyline.png` / `screenshot-unreachable.png` | 截图 2 张 |
| `mutants/`（`make-mutants.py`、`t3b-mut-round.sh`、`m1/`、`m2/`、`orig/`） | 变异装置与产物 |
| `mut-manifest.md5` | 装置产物 md5 清单 |
| `mut-runs/m1`、`mut-runs/m2`、`mut-runs/clean-after-mutants` | 各轮 e2e 输出 |
