# M7b T2 关账报告 —— 移动可见化（movement 对象 + 左栏 MP/成本/ETA + Canvas 路线折线）

> 工作树 `/home/cna/SimulatorMosire/.claude/worktrees/m7bt2`，分支 `m7b/t2`，基线 `ceb2a4c`。
> **全部工作在本工作树内**；未碰 `simos-core` 一行、未加写端点、未加依赖、未改 `progress.md`、未 push。

## 一 改了什么 / 为什么

| 文件 | 改动 | 为什么 |
|---|---|---|
| `simos-app/.../gui/ApiViews.java` | `movement` 由**布尔**改**对象**；`unit(...)`/`units(...)` 增 `GameMap` 形参；新增 `movement/route/hexCoords` 私有视图 | 路线与在途状态**从未暴露**（台账 §二 第 4 条）；状态字段**必须由 `UnitMoves.evaluate` 现算**，app 层不重写预算算法 |
| `simos-app/.../tools/ToolSupport.java` | 同形改动（MCP 面） | `/api/unit/{id}`、`/api/units`、`simos.unit.get`、`simos.unit.list` **四处同形**（工具面与 GUI 面不造第二份真相） |
| `simos-app/.../gui/GuiServer.java` | 两处调用点补 `ApiViews.gameMap(state)` | 传 `GameMap` 给 `evaluate` |
| `simos-app/.../tools/read/UnitGetTool.java` / `UnitListTool.java` | 调用点补 `ToolSupport.gameMap(state)` | 同上 |
| `simos-app/resources/webui/panels.js` | 左栏移动读数（MP / 每格成本 / 总成本 / status / currentHex / nextHex / remaining / **预计到达 tick**）；新增共享总览缓存 `loadOverview()` 并让右栏复用它 | 规则不可见是产品缺陷（台账 §二 第 2 条）；缓存避免每选单位多拉一次 ~1MB overview |
| `simos-app/resources/webui/map.js` | 路线折线：**整条淡色 + 未走完部分亮色**（`remainingPath` 纯函数）；`debug().routes` 钩子 | S3 定调；前端无数据画线（台账 §二 第 4 条） |
| `GuiApiTest.java` / `SimosToolsTest.java` | 各增用例：**movement 是对象**逐值 / **无路线 movement 为 null** | Java 侧护栏（含变异自证对象） |

**响应形状**（`/api/unit/{id}`、`/api/units`、`simos.unit.get`、`simos.unit.list` 四处逐字同形）：

```json
"movement": null
```
或
```json
"movement": {
  "route": {"waypoints":[{"q","r"}...], "path":[{"q","r"}...]},
  "departedAt": {"tick":5, "calendarLabel":null},
  "speedAtDeparture": 2,
  "mobilityPerMilleAtDeparture": 500,
  "status": "IN_TRANSIT",
  "currentHex": {"q":1,"r":1},
  "nextHex": {"q":1,"r":2},
  "remainingMillis": 1500
}
```

★ `status/currentHex/nextHex/remainingMillis` 全部由 `UnitMoves.evaluate(unit, at, map, TerrainMovementCost.INSTANCE)` **现算**；`at = state.meta().timestamp()`。`route`/`departedAt`/两个 departure 冻结值直接取 `Movement`。

## 二 我推出的 ETA 算式

预算模型（`UnitMoves`）：`budget(t) = speedAtDeparture × 1000 × (t − departedAt.tick)`（毫 MP）；
每格成本（`TerrainMovementCost`）：`cost = 地形(目标格).moveCost × mobilityPerMilleAtDeparture`（毫 MP，`scale` 的 `+500` 对 `moveCost×1000` 永进位，故等价）。

设 `totalCost = Σ 沿途每格成本`。抵达 ⇔ `budget(t) ≥ totalCost`，故

```
ETA tick = departedAt.tick + ceil( totalCost / (speedAtDeparture × 1000) )
```

**只用已暴露字段**（`departedAt.tick` / `speedAtDeparture` / `mobilityPerMilleAtDeparture` / `route.path`）+ `/api/map/overview` 的 `terrainTypes[].moveCost` 与 `hexes[].terrain`，**不需要当前时刻**。
不可计算的形态（`NEED_REPLAN`、或缺地形定义/不可通行）⇒ 面板显示 `—（需重规划）`。

demo 核对：走廊 3 格 desert（`moveCost=3`）、`mobility=500` ⇒ 每格 `3×500=1500` 毫 MP；`path` 3 点 ⇒ `totalCost=3000`；`speed=2` ⇒ 速率 `2000` 毫 MP/tick ⇒ **0.75 tick/格**；`departedAt.tick=5` ⇒ `ETA = 5 + ceil(3000/2000) = 7`。

## 三 逐模块数字（`./mvnw clean verify`）

| | simos-util | simos-map | simos-social | simos-unit | simos-core | simos-app | 合计 |
|---|---|---|---|---|---|---|---|
| 基线（`ceb2a4c`，M7 关账） | 170 | 255 | 45 | 131 | 154 | 78 | **833** |
| 本次（`feat`） | 170 | 255 | 45 | 131 | 154 | **81** | **836** |
| delta | 0 | 0 | 0 | 0 | 0 | **+3** | **+3** |

`+3` 恰为 `GuiApiTest`（16→18，新增 movement 对象/ null 两条）与 `SimosToolsTest`（11→12，新增一条）。
门禁：`rc=0`、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` **0** 行。日志 `logs/full-verify.log`（首轮）与 `logs/full-verify-final.log`（变异轮后重跑，**逐值与首轮同**）。

## 四 e2e 每步实测值（真 `ShellMain --demo`，Playwright：43 条 `STEP …: PASS` / 0 条 `: FAIL`）

| 步 | 断言 | 实测 |
|---|---|---|
| a | `unit.PlanRoute`（逐格 (1,1)→(1,2)→(1,3)） | `committed main@2` |
| b | movement **是对象** | `{"route":{...path 3 点...},"departedAt":{"tick":5},"speedAtDeparture":2,"mobilityPerMilleAtDeparture":500,"status":"IN_TRANSIT","currentHex":{1,1},"nextHex":{1,2},"remainingMillis":1500}` |
| b | `/api/units` 同形 | path 3 点，逐值同上 |
| c | 左栏每格成本 | **`路线每格成本（毫 MP）` = 1500** |
| c | 左栏预算/总成本 | `本 tick 预算 = 2000`、`路线总成本 = 3000` |
| c | 左栏 status/current/next/remaining | `IN_TRANSIT` / `q=1, r=1` / `q=1, r=2` / `1500` |
| c | 左栏**预计到达 tick** | **`7`**（=5+ceil(3000/2000)） |
| d | 折线点数 | `debug().routes[0] = {id:"u-1", totalPoints:3, remainingPoints:3, status:"IN_TRANSIT"}` |
| d | 颜色分层 | `baseColor="rgba(255, 214, 130, 0.35)"` vs `remainingColor="#ffd27a"`（不相等） |
| e | `POST /api/advance` (5→6) | `committed main@3` |
| e | currentHex 前进 | `{q:1,r:2}`；nextHex `{q:1,r:3}`；**remainingMillis 1500→1000** |
| e | 面板随之变 | `currentHex=q=1, r=2`、`nextHex=q=1, r=3`、`remainingMillis=1000` |
| e | **剩余段变短** | `totalPoints 3 → remainingPoints 2` |
| f | 无路线单位（新建 `u-2`） | `/api/unit/u-2.movement === null`、`/api/units` 同；面板 `无（无在途路线）`；`routes` 无 u-2、`routeCount===1`（u-1 线仍在）；**零 pageerror** |

截图：`screenshots/screenshot-route-map.png`（三段走廊上的淡色折线可见）、`screenshots/screenshot-movement-panel.png`（左栏读数：movement 有 / 路线格数 3 / 本 tick 预算 2000 / 路线每格成本 1500 / 路线总成本 3000 / status IN_TRANSIT / currentHex q=1,r=1 / nextHex q=1,r=2 / remainingMillis 1500 / 预计到达 tick 7）。
逐值 JSON：`e2e/b-movement-object.json`、`e2e/d-route-debug.json`、`e2e/e-after-advance.json`、`e2e/e2e-values.json`。

## 五 变异表（九道门禁；Java 目标编译 + 推 `target/classes`，资源目标源 + classpath 两份）

| m | 目标 | 变异 | 期望红 | 实测红点 |
|---|---|---|---|---|
| **m1** | `ApiViews.java` | `movement` 退回布尔 | b 步"是对象且含 route.path" | **`STEP b-movement-is-object: FAIL {"movement":true}`**（后续 `TypeError: reading 'path'`） |
| **m2** | `ApiViews.java` | 不调 `evaluate`，`status/currentHex/nextHex/remainingMillis` 恒 null | b/e 的 status/currentHex 断言 | **`STEP b-status-in-transit: FAIL null`**（后续 `reading 'q'` of null） |
| **m3**（额外，证资源路径） | `panels.js` | `appendMovementRows(...)` 退回布尔行 | c 步面板读数 | **`STEP c-panel-step-cost-1500: FAIL {...movement:"true"}`**（连带 c/e/f 面板全红） |

装置 `mutants/t2b-mut-round.sh` 的九道门禁逐条自证：① 干净世界（源 md5==备份；Java 另核 `.class` 备份存在）② 变异体字节不同 ③ 清陈旧 `.class` ④ `javac` 错误数 0 且 `.class` 产出 ⑤ 服务器真起（`GUI 服务器已启动`）⑥ e2e 真红（`E2E RESULT: FAIL`）⑦ 红点落在被保护步骤 ⑧ 源与 class/资源**逐字节还原**（聚合 md5 相等且**非空**）⑨ 日志自指（本轮推送字节的 md5 追加进日志本身）。
★ **本轮修过装置自身的一个假绿**：初版用 `"$CLASS_NAME".*.class` 聚合 `.class` md5，多一个点 ⇒ 模式只匹 `ApiViews.X.class`、**不匹 `ApiViews.class`** ⇒ 聚合读到空串（`d41d8cd9…` = md5("")），于是"还原相等"变成 `空==空` 的恒真断言。改为 `"$CLASS_NAME"*.class` 并**先断言非空**再比较（`pushed_classes_agg`/`restored_classes_agg`/`class_backup_agg` 均非空且相等）。**这正是"装置产物自己也要自指/先怀疑自己的读取"的同族实例**。

## 六 偏离候选（与派单书的分歧，以源码为准）

1. **路径**：派单书写 `simos-unit/**`，实际在 `simos-unit/src/main/java/io/mosire/simos/unit/move/`（`UnitMoves`/`MovementState`/`TerrainMovementCost`）；`MovementState` 的字段名是 `remainingEdgeCostMillis`（JSON 键按派单书用 `remainingMillis`）。
2. **`remainingMillis` 同刻取值**：领域命令**继承父行时刻**（M4 裁定 35）⇒ `departedAt.tick == 查询 tick`，同刻预算 0 ⇒ `remainingMillis = 1500`（整段未付）。这是设计事实，不是 app 层重算。
3. **额外读数**：面板除派单书要求的项外，另显示 `路线格数 / 路线总成本 / 出发 tick / 出发速度 / 出发机动‰`（"至少"允许）；`movement` 行由原来的 `true/false` 改为 `有/无（无在途路线）`。
4. **右栏小重构**：`panels.js` 的 `renderRight` 改用共享 `loadOverview()`（行为不变、数据同源），只为让左栏 ETA 复用同一份 `/api/map/overview`，避免二次 1MB 取数。
5. **额外变异 m3**：派单书只列 m1/m2；多做一轮 `panels.js` 资源变异，用来**证明装置的"前端资源 + classpath 两份"路径真的能杀**（前端护栏强度是本项目系统性开口项）。
6. **陈旧消费方（未改，故意）**：历史证据里的 e2e 脚本仍按旧布尔断言——`t7-evidence/e2e/e2e.cjs:257`（`movement === true`）、`t5-evidence/e2e/e2e.cjs:271`（`String(uf.movement)`）。它们是**已关账任务的存档证据、不在任何门禁里**，改历史证据 = 篡改留痕，故不改，在此记明。
7. **旧调试页 `unit.js`** 的 `body.movement ? "有" : "无"` 未改：对象为 truthy ⇒ 仍显示"有"，行为正确（旧三页裁定保留为调试页）。

## 七 我未能核实的（如实记）

1. **折线像素未逐点采样**：d 步用 `window.SimosMap.debug().routes` 钩子断言点数/颜色分层；截图目视折线为淡色，但**没有**对 canvas 做 `getImageData` 像素证明"淡层与亮层确实各画了一次"。色值常量与 `remainingPath` 结果是可断言的，`strokePolyline` 的绘制调用**只由代码路径保证**。
2. **`ARRIVED` / `NEED_REPLAN` 未在页面观察**：e2e 只覆盖 `IN_TRANSIT`（推进 1 格）。`ARRIVED` 需再推进（届时 materialize 会清 `movement`，可能直接变 null），`NEED_REPLAN` 需地图变到不可通行——**无真路径**，面板的 `—（需重规划）` 分支未实测。
3. **u-2「不画线」只有钩子证据**：用 `routeCount==1` 且 `routes` 无 u-2 证明，**未做像素对照**。
4. **异质地形的 ETA 未测**：demo 是单一 desert；`totalCost` 的多地形求和/不可通行短路只在纯函数逻辑里，**未用真地图 e2e**。
5. **只本机、单浏览器**：本机 headless Chromium（`chromium-1234`），未跨浏览器/未移动端。
6. **m2 轮 e2e 未走完全程**：`b-status` 红后，因 `currentHex` 为 null 触发 JS `TypeError` 提前终结；红点确是被保护断言，但**未观察到 m2 下 e2e 的后续步骤**。
7. **MCP 面只断言路径长与 status**：`SimosToolsTest` 对 `simos.unit.list/get` 的 movement 只逐值断言 `route.path` 长度 / `status` / `currentHex.r` / `nextHex.r` / `remainingMillis`，"与 GUI **完全同形**"靠代码 review（两处 `movement(...)` 手写重复，无跨面逐字节对拍）。
8. **`map.js` 的 classpath 资源路径未单独变异**：m3 只变异了 `panels.js`；`map.js` 走同一装置同一机制，未再跑一轮。

## 八 证据索引（`t2b-evidence/`）

| 路径 | 内容 |
|---|---|
| `logs/full-verify.log` / `logs/full-verify-final.log` | 全量门禁首轮 / 变异后重跑（836 = 170/255/45/131/154/81） |
| `logs/e2e-clean.log` / `logs/e2e-clean-after-mutants.log` | 干净 e2e（变异前 / 变异后重跑，全 PASS） + 各自 `.server.log` |
| `logs/t2b-m1.log` `m2` `m3`（+ `.server.log` / `.javac.log`） | 三轮变异日志（含各自"装置补记"自指段） |
| `mutants/{m1,m2}/ApiViews.java`、`mutants/m3/panels.js` | 变异体 |
| `mutants/orig/…` | 原件与资源副本（还原基准）；★ Java 的 `.class` 还原基准被 `.gitignore` 的 `*.class` 忽略，**未入库**，但其 md5 在 `mut-manifest.md5` 中；它可由原件源码经 Maven 构建复得 |
| `mutants/t2b-mut-round.sh`、`e2e/run-e2e.sh`、`e2e/e2e.cjs` | 装置 |
| `mut-manifest.md5` | 变异体/原件/装置逐文件 md5 |
| `e2e/*.json` | b/d/e 逐值 |
| `screenshots/*.png` | ① 有路线的地图 ② 左栏移动读数面板 |

## 九 阻塞

无。
