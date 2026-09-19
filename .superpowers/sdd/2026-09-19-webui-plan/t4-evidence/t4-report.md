# M7 T4 报告 —— Canvas 升级（缩放平移 / 后端地形色 / 区域填充 / 点选联动）

> 任务：WebUI 可视化骨架第四个任务。工作树 `/home/cna/SimulatorMosire/.claude/worktrees/m7t4`，
> 分支 `m7/t4`，基线 `7a7b008`（`docs(m7): T3 关账`）。设计见 `docs/superpowers/specs/2026-09-19-webui-design.md`
> §〇.1 判据③ / §〇.4 可用性补项 / §3.2·3.3 / §四；计划见 `docs/superpowers/plans/2026-09-19-webui-plan.md` T4。

---

## 一 改了什么 / 为什么

**纯前端**（`simos-app/src/main/resources/webui/` 七文件）；**零 Java 改动、零新端点、零新依赖、无 npm/构建/CDN**。

| # | 文件 | 改动 | 为什么 |
|---|---|---|---|
| 1 | `webui/map.js` | **重写为共享渲染器**（963 行）：纯几何（`hexToPixel`/`pixelToHex`/`worldToScreen`/`screenToWorld`/`zoomAt`/`fitView`）+ `createRenderer`（视变换 `{scale,tx,ty}`、滚轮以指针为锚缩放、拖拽平移、区域填充层、单位优先命中）+ 宿主接线（工作台与旧页 `/map` 共用）。**删除硬编码 15 色 `TERRAIN_COLORS`** | MUST DO 1/2/3/4；旧页 `/map` 与工作台同一份渲染器（spec §3.2「前端不再硬编码色表」） |
| 2 | `webui/api.js` | 新增 `mapRegion(id, target)`（+ 导出） | `/api/map/region/{id}` 的取数包装（T1 加了端点，前端一直没接）；懒拉区域 hex |
| 3 | `webui/app.js` | 状态机加 `highlightRegions` + `setHighlightRegions(ids)` | MUST DO 3「当前要高亮的区域集合接进状态机」 |
| 4 | `webui/panels.js` | 左栏由骨架改为**真读数**：hex ⇒ `q/r/terrain（含定义名）/height/region/该处单位`；单位 ⇒ `id/name/parent/position/member`。**所有取数经 `withTarget(..., target())`**；浮点读数去二进制尾（`0.6000000000000001→0.6`） | MUST DO 4（T3 约定「未被真面板消费」的收口） |
| 5 | `webui/index.html` | 引 `map.js`；中心栏加 `#zoom-level`、`#legend`；`#canvas-mount` 清空（canvas 由 map.js 注入） | 工作台接真 Canvas |
| 6 | `webui/map.html` | 说明改「后端权威色 + 滚轮缩放/拖拽平移」；标题加 `#zoom-level` | 旧页同步（spec §四 可用性补项） |
| 7 | `webui/styles.css` | `.canvas-mount` 由 flex 占位改 `display:block` + canvas 尺寸/`touch-action:none` | 让 canvas 在栅格列里正确铺开 |

**未改**：`simos-core` / `simos-app` 的 Java 源码、`GuiServer` / `ApiViews`（`git status` 实证零 Java 改动）；
`timeline.js` / `unitTree.js` / `unit.js` / `social.js` / `social.html` / `unit.html`；`api.js` 的写端点。

### 与派单书的分歧（以源码为准）

1. **"几何单测"没有做成 Maven 用例**：本仓无 JS 测试运行器（无 npm/构建），Java 21 无内置 JS 引擎 ⇒ 几何断言做成
   **两道**：① `e2e/geometry-check.cjs`（node 直接 require `map.js`，钉 `pixelToHex` 往返 7 坐标×3 尺寸、
   `zoomAt` 锚点漂移 0、`screenToWorld∘worldToScreen` 逆、缩放上下夹）；② e2e 的 `b-zoom-select` 在真浏览器里
   走真鼠标。**故全量用例 delta = 0**（派单书已写"期望 delta 0"，与此一致）。
2. **区域填充色用固定半透明色**（`rgba(255,210,80,0.42)`），**没有**用 `RegionMeta.color`。真地图两区
   `meta.color` 实为 `#fc6dce`/`#d370d6`，用它更"好看"，但像素断言会多一层"按区域取色"的耦合；固定色让
   "填充层确实画上了"成为可判定的纯几何断言。**建议 T6 再切到 `meta.color`**（届时分组列表本来就要读 meta）。
3. **T4 的"选中区域"入口 = 区域查看模式下点一个格**（取其权威 `region` 字段），**不做** tag 分组列表（T6 的活）。
   `highlightRegions` 是数组，已支持多区；T6 只需往里塞更多 id。
4. **`map.js` 在 `window.SimosMap` 暴露 e2e 钩子**（`screenPointOf`/`debug`/`resetView`/`currentView`/`hexAtScreen`），
   照 T3 暴露 `SimosTimeline.isAtTip` 的形态；它们也是渲染器的真实只读读面。
5. **旧页的"每格边长"输入保留**为世界基准尺寸，另加 `#zoom-level` 显示缩放系数（spec §四 的"升级为缩放系数显示"
   在两处都给了显示，但没有删掉旧页的输入——旧页是调试回归面，删了会动它的回归面）。
6. **`reloadOverview` 只在分支变化时 `fit()`**：若每次 revision 变化都 refit，拖时间轴会把用户的缩放/平移弹掉。
   取数仍一律带 `withTarget`（revision 变化会以 120ms 去抖重取 overview+units+高亮）。

---

## 二 测试条数与逐模块数字

- **基线**（派单书给定，与 T3 关账同值）：`./mvnw clean verify` = **833** 条 = **170 / 255 / 45 / 131 / 154 / 78**（util/map/social/unit/core/app）。
- **终态**（`logs/full-verify.log`，`./mvnw clean verify`）：rc=0，**833** 条 = **170 / 255 / 45 / 131 / 154 / 78**，
  `BugInstance size is 0` ×**6**，`[ERROR]` **0** 行。
- **逐模块 delta 全 0**——本任务纯前端，**未加/未改任何 Java 测试**。
- **定向**（`logs/targeted.log`）：`WebuiAssetsTest` **8** 条全绿。

---

## 三 端到端实测（真 `ShellMain` + 真 `StaticHandler` + Playwright 驱动真页面）

装置：`e2e/run-e2e.sh`（`demo` 起 `--demo` 新库；`realmap` 复制 M6 导入档、**不给 `--demo`**）
+ `e2e/e2e.cjs`（Playwright 1.63.0，chromium `~/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome`）。
**两轮全 PASS**（`logs/e2e-demo-clean.log`、`logs/e2e-realmap-clean.log`）。

### 3.1 demo 世界（`--demo`，3 格走廊 + 单位 u-1）

| 步 | 断言 | 实测值 |
|---|---|---|
| seed | 造 ≥3 revision（bootstrap + 2×`unit.RenameUnit`） | `head=3 rows=3` |
| **a** | **地形色 == 后端 `terrainTypes[].color`**（像素取样 + 内部色表双证） | 后端 `#E7C86E`；画布像素 `[231,200,110]`；`colorByTerrain.desert=#E7C86E`；`fallbackWarned=false` |
| **b** | **缩放后点选准**（滚轮 -700，锚在目标格） | `scale 1→6.748`；锚点漂移 `[1.72,1.64]px`；点选 `{kind:"hex",q:1,r:3}` |
| **c** | **平移后点选准**（真鼠标拖 90×55） | `tx 100.67→190.67`、`ty 15.41→70.41`；点选 `{kind:"hex",q:1,r:2}` |
| unit | 点单位标记 ⇒ 左栏真读数 | `{kind:"unit",id:"u-1"}`；面板 `idu-1 / name乙 / parent— / positionq=1, r=1 / member100` |
| **d** | **拖游标到旧 revision(2) 后点选** | 真鼠标从节点 3 拖到节点 2 ⇒ `revision===2` |
| **d** | 请求 URL 含 `revision=2` | `http://127.0.0.1:45801/api/map/hex?q=1&r=1&branch=main&revision=2` |
| **d** | **面板读数来自该 revision** | 面板 `q1 r1 terrain desert（沙漠） height 0.5 region — 该处单位 u-1 甲`；同 revision API：`/api/units?...revision=2` 的 u-1 名 `甲`、`/api/map/hex?...revision=2` 的 `height=0.5`（**rev3 的名是 `乙`** ⇒ 面板确实随游标切） |
| d | 只读（点选/缩放/预览不写盘） | `rows 3→3` |
| old | **旧页 `/map` 仍可用**（同一渲染器） | `/map` 载入、点 (1,2) ⇒ `#hex-detail="q1r2terraindesertheight0.5"`、`#legend="共 3 格 · 0 区域 · 单位 1 · desert=3"`、`pageerror` 0 |
| — | 截图 | `screenshot-zoom-pan.png`、`screenshot-hex-selected.png`（1440×900） |

### 3.2 真地图（`/tmp/m6-import-verify/test_integration` 的副本，19441 格，2 区域）

| 步 | 断言 | 实测值 |
|---|---|---|
| load | 真地图可载入 | `loadMs=3019`；`hexCount=19441` |
| **e** | **区域填充：填充层出现**（像素前后不同） | 点击前 `[168,179,106]`（low_hills）→ 后 `[204,192,95]` |
| **e** | 填充是半透明叠加（== 期望混色） | 实测 `[204,192,95]` vs 期望 `[205,192,95]`（±3 内） |
| **e** | **高亮 hex 数 == 该区域 `hexes` 数** | `regionId=test_annex_target`、`expected=201`、`actual=201` |
| smoke | 缩放/平移/点选冒烟 | 缩放至 `scale≥1.5`；拖拽+点选 ⇒ `{kind:"hex",q:0,r:-1}` |
| — | 截图 | `screenshot-region-fill.png`（1440×900） |

★ **区域重叠的实测事实**：`test_nation` 的第 0 个 hex `(-18,0)` 经 `/api/map/hex` 权威解析实归
**`test_annex_target`**（201 格）；`MapResolver.regionOfHex` 是权威，e2e 以它为准（首版 e2e 假设成 `test_nation`
⇒ 超时；已改）。两区是否"子集/相交"未核（见 §六）。

### 3.3 大图操作耗时（spec §九-1，T3 未履行的实测项）

装置在真地图上量（`e2e/realmap-out/realmap-timing.json`）：

| 量 | 实测 | 说明 |
|---|---|---|
| 页面载入→地图就绪 | **3019 ms** | 含启动两次 overview 取数（游标初始化）与整图渲染 |
| `/api/map/overview`（1MB）**浏览器内** | **~2.62 s**（两次 2620/2616） | 拆分：**transfer 2620ms、decode 3ms、parse 3ms** ⇒ 全在传输 |
| 同一服务端 `/api/state`（小载荷）浏览器内 | **45 ms** | 同一 `stateAt` 重放 ⇒ 服务端重放快 |
| 服务端 curl / node 取 overview | **40–85 ms** | 19441 格重放+序列化 |
| 整图渲染（19441 格，fit） | **~2.55 s**（同步） | 全图无剔除 |
| 缩放后拖拽+点选 | **~0.41 s** | 有视口剔除 |

**结论**：spec §九-1 担心的"每次切换整份解码"**不是瓶颈**——服务端重放/解析约 40–85ms；瓶颈是
**1MB 响应体到 Chromium 的传输（~2.6s）**与**整图首帧渲染（~2.5s）**。根因未证（见 §六-1）。

---

## 四 变异表（九道门禁，2 轮，0 存活）

装置 `mutants/mut-round-e2e.sh`：**资源类目标 map.js**，护栏是 **e2e 行为断言** ⇒ 变异体推给
**源码 + classpath 两份**（`StaticHandler` 从 classpath `/webui/` 读字节），起真 ShellMain + Playwright 观察红点。
门禁：干净世界（源/classpath/备份三 md5 一致）/ 变异体字节不同 / 两份推送一致 / 服务器真起 /
e2e 真跑 / 红点是被保护断言 / server 日志 mtime 落本轮 / 源与 classpath 逐字节还原 / 日志自指。

| m | 护栏 | 变异体（相对原件） | 期望红 | 实测红点（日志） |
|---|---|---|---|---|
| **m1** | **地形色来自后端** | `map.js` 的 `terrainColor()` 里 `var color = colorByTerrain[terrain]` → `var color = "#123456"` | e2e a 步（像素 == 后端色） | **`STEP a-color-from-backend: FAIL {"backend":"#E7C86E","pixel":[18,52,86],"mapColor":"#E7C86E"}`**（`#123456`=rgb(18,52,86)）；`E2E RESULT: FAIL a-color-from-backend`（`logs/m1.log`） |
| **m2** | **缩放后点选准确** | `map.js` 的 `pickAt()` 里 `var world = screenToWorld(point, view)` → `{x:point.x,y:point.y}`（忽略 transform） | e2e b 步（点选 q/r） | **`STEP b-zoom-select: FAIL {...,"selB":null}`**（缩放后按屏幕坐标当世界坐标 ⇒ 落在地图外的格 ⇒ 不选中）；连带 c/unit/d/old-page 红；`E2E RESULT: FAIL b-zoom-select,…`（`logs/m2.log`） |

- 两轮均：`rc=1`、红点落在**被保护断言本身**、源与 classpath **逐字节还原**（`restored_src`/`restored_classes` == `orig_md5`）、
  日志自指段在案（`grep -c 装置补记` = 1）。md5 见 `mut-manifest.md5`。
- **"为什么红"**：m1 的红是像素 `[18,52,86]` = 被替换进去的常量色 `#123456`，正是"色不再来自后端"的后果本身；
  m2 的红是 `selB=null`——缩放后命中换算错，点在了不存在的格上，正是"忽略 transform"的直接后果。
- ★ **门禁当场生效一次**：m2 首跑（端口 45812）因上一轮 socket 未释放 ⇒ `BindException: Address already in use`
  ⇒ 装置按"服务器没起"**当场作废本轮**（`rc=2`，不产出任何结论），换端口 45822 重跑才得上面的红点。

---

## 五 偏离 / 取代说明候选（供控制器裁决）

1. **区域填充色固定**（非 `RegionMeta.color`）——见 §一 分歧 2。建议 T6 切 `meta.color`。
2. **T4 的选区入口 = 区域查看模式点格**——见 §一 分歧 3；tag 分组归 T6。
3. **旧页保留"每格边长"输入**——见 §一 分歧 5。
4. **overview 只在分支变化 refit、revision 变化去抖重取**——见 §一 分歧 6；所有取数仍带 `withTarget`。
5. **`window.SimosMap` 的 e2e 钩子**——见 §一 分歧 4。
6. **未改 `#shell-state` 顶栏语义**：它显示 `/api/state` 的 head（非游标 revision）——这是 T2/T3 的既有行为
   （T3 报告已记 `timeline-meta` 才是游标读数），T4 未动。截图里可见"顶栏 rev 3 / 地址栏 main@2"的并存。

---

## 六 我未能核实的

1. **浏览器取 overview 2.6s 的根因未证**：服务端 curl/node 40–85ms、同端点 `/api/state` 浏览器内 45ms，
   而 1MB overview 浏览器内 2.6s 且 99.8% 在 `transfer`（decode/parse 各 3ms）。**只测了现象，没证机理**
   （未试 HTTP/2、gzip、分块、`fetch` 参数、不同浏览器）。形态 5：不把推导当结论。
2. **整图首帧 2.5s 同步渲染未优化**：19441 格在 fit 下无剔除；本任务未做增量/瓦片/离屏缓存。大图"手感"仅以
   一次拖拽+点选（0.41s）间接反映，**持续交互的帧率未测**。
3. **区域重叠语义未核**：`test_annex_target`(201) 与 `test_nation`(701) 是否子集/相交、`regionOfHex` 取哪个是
   "对"的，只按端点实测值使用，**没有读 map 层代码确认设计意图**。
4. **多区域同时高亮未测**：`highlightRegions` 是数组、`reloadHighlights` 支持 N，但 e2e 只设过 1 个区。
5. **词表外地形兜底色路径未实测**：`FALLBACK_COLOR`+`console.warn` 一次的逻辑在 demo/真地图都**未触发**
   （两世界的地形都在词表内）；e2e 只断言了 `fallbackWarned===false`。**兜底分支没有红过**。
6. **旧页 `/map` 的缩放/平移未直接 e2e**：只测了旧页载入+点选+详情（`old-page-map`）；缩放/平移与工作台共用
   渲染器，但旧页上的滚轮/拖拽**没有当场跑过**。
7. **`PAGE-ERROR: 404` 未定性**：每次载入都有一条资源 404（疑似 `favicon.ico`），**没有抓到 URL**，不排除其它。
8. **`d-readonly` 只数 `revisions` 行**：未证明事件表/checkpoint 目录无写（本任务无写调用，但只测了这一张表）。
9. **多分支下的地图重取未测**：只跑 `main`（与 b2 分岔无关）；分支切换时 `reloadOverview` 的 refit 行为未在
   多分支上验证。
10. **CJK 仅由截图间接证明**（无豆腐块）；无像素级字体断言。
11. **Playwright/chromium 版本耦合**（1.63.0 期望 revision 1243、本机 1234）靠 `executablePath` 显式指定才跑通，
    换机器需按本机缓存重取路径（运行环境，非代码）。
12. **`withTarget` 只在被点选的那次请求上抓了 URL**（d 步）；overview/units/region 的 `revision=` 参数只由代码
    路径保证（`api.mapOverview/units/mapRegion` 都传 `app.target()`），**未逐端点抓 URL 断言**。

---

## 七 证据索引（`.superpowers/sdd/2026-09-19-webui-plan/t4-evidence/`）

| 文件 | 内容 |
|---|---|
| `logs/full-verify.log` | 终态 `./mvnw clean verify`（833、delta 0、BugInstance 0×6、ERROR 0） |
| `logs/targeted.log` | `WebuiAssetsTest` 8 条 |
| `logs/e2e-demo-clean.log` | demo e2e 10 项全 PASS |
| `logs/e2e-realmap-clean.log` | 真地图 e2e 5 项全 PASS + 耗时 |
| `logs/geometry-check.log` | 几何纯函数自检（往返/锚点/逆/夹取） |
| `logs/m1.log` / `logs/m1.server.log` | 变异 m1（含自指补记 + server 真起） |
| `logs/m2.log` / `logs/m2.server.log` | 变异 m2（同上；含首跑作废的 BindException 记录） |
| `e2e/e2e.cjs` / `e2e/run-e2e.sh` / `e2e/geometry-check.cjs` | 装置 |
| `e2e/demo-out/` / `e2e/realmap-out/` | e2e 原始产物（`realmap-timing.json`、`demo-hex-requests.json`、截图） |
| `screenshot-zoom-pan.png` / `screenshot-region-fill.png` / `screenshot-hex-selected.png` | 三张截图（1440×900） |
| `mutants/mut-round-e2e.sh` / `mutants/orig/` / `mutants/m1/` / `mutants/m2/` | 变异装置与产物 |
| `mut-manifest.md5` | 全部证据 + 改动的 webui 源文件的 md5 清单（45 行） |
| `t4-report.md` | 本报告 |
