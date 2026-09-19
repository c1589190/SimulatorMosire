# M7g T1 报告 —— 全屏底图 + 浮层控件

分支 `m7g/t1`（worktree `.claude/worktrees/m7gt1`，基线 `23ece5a`）。**纯前端三文件、零 Java 改动**。

## 1. 改了什么

| 文件 | 改动 |
|---|---|
| `simos-app/src/main/resources/webui/index.html` | DOM 重排：`#canvas-mount` 提为 body 直接子元素（全屏底图）；新增 `.wb-overlay` 浮层容器（topbar / wb-body 左右栏 / map-hud / timeline）；新增 `#view-reset`、`#panel-toggle-left`、`#panel-toggle-right` 三个按钮；把原 `col-center` 里的 `#zoom-level`/`#map-status`/`#legend` 移入 `.map-hud`；`#canvas-mount` 保留 `col-center` 类**仅为满足 `WebuiAssetsTest` 的骨架断言（不许改 Java）** |
| `simos-app/src/main/resources/webui/styles.css` | 末尾追加一节（全部作用域限定 `body.workbench-page`，**旧三页零影响**）：底图 `position:fixed; inset:0; 100vw×100vh`、去边框/圆角；`.wb-overlay{position:fixed;inset:0;pointer-events:none}`；四类浮层半透明 + `backdrop-filter:blur()`；左右栏 `max-height/overflow:auto`；`[hidden]` 折叠 |
| `simos-app/src/main/resources/webui/map.js` | `resize()`：工作台改用 `window.innerWidth/innerHeight`（旧页仍父容器宽 + 620 固定高）；`fit()` 抽出 `computeFit()`；`reloadOverview`：工作台**只在首次** `fit()`（`host.viewInitialized`），不再随目标/分支变化重塞视口；新增 `resetToWorldCenter()`、`wirePanelToggle()`、`wireWorkbenchControls()`（按钮 + Home 快捷键）；`bindActive` 暴露 `SimosMap.computeFit` |

### 浮层清单与定位方式

| 控件 | 定位 | 指针 | 可断言钩子 |
|---|---|---|---|
| 底图 `#canvas-mount`/`#canvas` | `position:fixed; inset:0`（`z-index:0`） | 默认 auto | `boundingBox ≈ {0,0,vw,vh}`；`border/radius = 0px` |
| `.wb-overlay`（承载全部浮层） | `position:fixed; inset:0`（`z-index:10`） | `pointer-events:none`（透明区透传给底图） | `elementFromPoint` 在控件中心命中控件 |
| topbar（标题 + 模式栏 + 三个按钮 + shell-state） | flex 列首行（`flex:0 0 auto`） | `auto` | `#mode-bar`、`#view-reset`、`#panel-toggle-*` |
| 左栏 `#left-panel` | `.wb-body` 左端（`flex:0 0 300px`） | `auto`（不穿透） | `hidden` 折叠 + `overflowY:auto` |
| 右栏 `#right-panel` | `.wb-body` 右端（`flex:0 0 300px`） | `auto` | 同上 |
| 中部空隙 `.wb-gap` | `flex:1` | `none`（点它=点底图） | `c-outside-is-canvas` |
| `.map-hud`（缩放/状态/legend） | `.wb-overlay` 内、时间轴之上 | `auto` | `#zoom-level`/`#map-status`/`#legend` |
| 时间轴 `#timeline-bar` | `.wb-overlay` 列尾（`flex:0 0 auto`） | `auto` | `barBottom <= vh`、折叠左栏后仍可见 |

## 2. e2e a~h 实测值（真 `ShellMain --demo`、全新 store、Playwright + Chromium）

**1280×800**：`E2E RESULT: PASS`（`logs/clean.log`）；**1024×700**：`E2E RESULT: PASS`（`logs/clean-1024x700.log`）。

| 步 | 断言 | 1280×800 实测 |
|---|---|---|
| a | 底图铺满视口 / 无盒子 / DPR 清晰 | `cbox={0,0,1280,800}`；`border=0px radius=0px mountPos=fixed`；`canvasW=1280==vw*dpr` |
| b | 四浮层在底图之上 | mode-bar→`BUTTON`、timeline→`#timeline-fork`、left/right→`SECTION#left-panel/#right-panel`；`hitIsCanvas=false` ×4 |
| c | 面板外可点选 + 面板不穿透 | `c-outside-is-canvas` `{tag:CANVAS}`；点 (1,2)⇒`selection={hex,1,2}`；点左栏 h2⇒命中 `H2`、selection 不变、`nonGet 0→0` |
| d | 时间轴常驻 + 左栏栏内滚动 + 折叠 | `barBox.y+height=800==vh`；`left scrollHeight=1501 > clientHeight=629, overflowY=auto`；滚动到底 `barBox` 不变；折叠 `hidden=true`、按钮 `展开左栏/pressed=true`；折叠后 canvas 仍 `1280×800` |
| e | resize 不重置视图 | 缩放+平移后 `v1={scale:8.1671,tx:-201.92,ty:-353.05}`；`setViewportSize(1120×710)` 后 `v2≡v1`（逐字段相等）；canvas 仍 `1120×710` |
| f | 回到中心/适配（按钮 + Home） | `computeFit()={scale:3.2569,tx:176.41,ty:22.80}`；按钮后逐字段相等；Home 前 `vZoomF={5.2634,…}`→Home 后逐字段相等 |
| g | 回归 | **M7e**：右键空白 `selection=null`、`routeCount` 保持 1、`nonGet 1→1`；左键第一次选中(无写)、第二次 `routeCount 1→0` 且 `head 2→3`（真 `CancelRoute`）。**M7f**：下路线 `nodes 1→1` 且明细 `count 3→4`、`apiTicks [5]→[5]`；推进 N=3 ⇒ `apiLastTick 5→8`、`nodes 1→2`、`head 4→5`。**M7b**：`midRev 4 ≠ head 5`、knob 14×14 可见、拖回末端 `revBack=5`。**T4**：缩放后点 (1,2) ⇒ `selection={hex,1,2}`（点前 `elementFromPoint=CANVAS`） |
| h | R8 allowlist + 零 pageerror | `NON_GET_LIST=["/api/command","/api/command","/api/command","/api/advance"]`（⊆ allowlist，violations=[]）；`pageErrors=[]` |

> 1024×700 逐条同型 PASS；差异仅为数值（`v0.scale=3.2110`、`barBox.y=631`、`left clientHeight=486`、`blank=(340,110)`、knob 在 `y=659`）。证据见 `logs/clean-1024x700.log`。

## 3. 变异表（3 轮，九道门禁装置 `mutants/m7g-mut-round.sh`）

| 轮 | 目标 | 变异 | 期望 | 实测红点（`STEP …: FAIL`） |
|---|---|---|---|---|
| m1 | `styles.css` | `.canvas-mount` 退回普通流（`position:static` + margin + 60vw/60vh + 边框） | a | ✅ `a-canvas-covers-viewport`（`cbox={0,0,768,800}`）、另 `a-canvas-no-box`/`d`/`e` 连带红 |
| m2 | `styles.css` | `.wb-body .panel{padding-events:auto→none}` | c | ✅ `c-panel-hit-is-control`（`hit=#canvas, hitInControl=false`）、另 `b-left/right-panel` 连带红 |
| m3 | `map.js` | resize 监听器里加 `active.fit()` | e | ✅ `e-resize-keeps-view`（`v1.scale=8.167 → v2.scale=3.257`）、另 `f-reset-changed-view` 连带红 |

三
轮均 `⇒ 杀死…源与 classpath 资源均逐字节还原`（rc=0）。装置自证：干净世界（源==classpath==备份 md5）→ 变异体语法有效且字节不同 → 两份推送一致 → 服务器真起 → e2e 真红 → 红点落被保护断言 → 源/资源逐字节还原 → 日志自指（md5 追加进日志）。

## 4. 门禁

- `./mvnw -q spotless:apply`：rc=0（纯前端，无 Java 变更 ⇒ 无 diff）
- 定向：`./mvnw -q -pl simos-app -am -Dtest=WebuiAssetsTest -Dsurefire.failIfNoSpecifiedTests=false test`：rc=0
- 全量 `./mvnw clean verify`：**rc=0、841 条 = 170/255/45/131/154/86、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0**（`logs/full-verify.log`）。**delta = 0**（与基线逐模块同值）

## 5. 偏离候选 / 与派单不一致处

1. ★ **派单要求用 `deepseek-flash-go` 子代理，但本会话可用的派单工具只支持 `explore`/`librarian`** ⇒ 无法派 `deepseek-flash-go`，本任务由**控制器内联执行**（与 M4 Task 8~12 同一形态）。此为实现路径偏离，非设计偏离。
2. ★ **m2 的"c 步红"落点**：派单表写"c 步红"，实现中 `c-panel-no-passthrough-selection`（选中态不变）**红不了**——因为 demo 只有居中 3 格，左右栏（x∈[12,312]/[968,1268]）**从不与任何格重叠**，穿透点击落在"无格区"，`workbenchSelect` 走 `!inMap` 分支不改选中态。真正能红的 c 断言是 **`c-panel-hit-is-control`**（`elementFromPoint` 命中 canvas）。已按此裁定装置期望步。
3. ★ **`WebuiAssetsTest.rootServesTheWorkbenchSkeleton` 硬断言 index.html 含 `col-center`**（Java，不许改）⇒ 把 `col-center` 类挂在 `#canvas-mount` 上（CSS 中 `body.workbench-page .canvas-mount` 的 `overflow:hidden` 覆盖旧 `.col-center{overflow:auto}`，行为中性）。**这条派单没提到，但跳过会破坏门禁。**
4. e2e 的"点面板"用 `page.mouse.click(中心)`（原始事件），**不用 `locator.click`**——后者对 `pointer-events:none` 元素会 actionability 超时，令 m2 轮作废而非红在 c。这是 M7d 纪律"点击后必须断言真的发生了"的延伸。
5. 派单写"模式栏、时间轴、左栏、右栏全部变成浮层"；实现额外把 topbar（标题 + 按钮 + shell-state）与 `.map-hud`（缩放/状态/legend）也做成浮层（否则无处安放）。

## 6. 我未能核实的

- **触摸/真鼠标**：全部交互是 Playwright 合成事件；触摸屏、触控板惯性、右键长按未测。
- **`backdrop-filter` 的视觉强度**：只由截图肉眼确认"半透明 + 模糊"存在，**无像素级模糊量测**；不支持该属性的浏览器回退（纯半透明）未在真实旧浏览器验证（Chromium 支持）。
- **HiDPI**：headless `devicePixelRatio=1`，只证了 `canvas.width==vw*dpr` 在 dpr=1 成立；dpr>1 的真实清晰度未跑。
- **视口尺寸**：只跑 1280×800 与 1024×700；更窄（<900）或超高未测。
- **`d` 左栏滚动**：用"单位模式 + 选中 u-1"的富内容触发；view/region 模式下左栏是否也会溢出未逐一核。
- **5818 区域面板逻辑**：按派单"不要求跑"，仅保证代码未动（全量门禁绿含 `GuiApiTest`，但未在浏览器里走区域分组交互）。
- **旧三页观感**：未在浏览器里目视 `/map`、`/unit`、`/social`；只有 Java 门禁（`GuiApiTest`/`WebuiAssetsTest`）绿。
- **Home 快捷键在输入框内不误触发**：代码有 `INPUT/TEXTAREA/SELECT` 守卫，但**未写断言**证明在输入框聚焦时按 Home 不重置视图。
- **CSS 变异器的语法检查是"含 `{`"的启发式**，不是真 CSS 解析器（m1/m2 靠浏览器真渲染判红，故实际有效；但装置本身不构成语法证明）。
- **`#view-reset` 在动画/连续 resize 下的抖动**未测。

## 7. 证据索引

```
m7g-t1-evidence/
├── e2e/e2e.cjs                     # a~h 全部断言（可传 WxH 跑第二视口）
├── e2e/run-e2e.sh                  # 起 ShellMain --demo + node Playwright
├── e2e/clean-out/                  # 1280×800 的 JSON 逐值 + e2e-values.json
├── e2e/clean-out-1024x700/         # 1024×700 同上
├── logs/clean.log                  # 1280×800 全 PASS（a~h 逐行）
├── logs/clean-1024x700.log         # 1024×700 全 PASS
├── logs/clean.server.log / clean-1024x700.server.log
├── logs/full-verify.log            # 全量门禁 rc=0、841 条、BugInstance 0×6
├── logs/m1.round.log / m2.round.log / m3.round.log          # 变异轮结论 + 日志自指块
├── logs/m1.log / m2.log / m3.log                            # 各轮 e2e 原始输出
├── logs/m1.server.log / m2.server.log / m3.server.log
├── mutants/m1/styles.css           # 底图退回流式（期望 a）
├── mutants/m2/styles.css           # 面板穿透（期望 c）
├── mutants/m3/map.js               # resize 重置视图（期望 e）
├── mutants/m7g-mut-round.sh        # 九道门禁装置
├── mutants/orig/{styles.css,map.js}                  # 源原件（md5==工作树）
├── mutants/orig/classes-resource/{styles.css,map.js} # classpath 原件
├── screenshots/screenshot-fullscreen-all-1280x800.png     # ① 全屏底图 + 全部浮层
├── screenshots/screenshot-left-collapsed-1280x800.png     # ② 左栏折叠后
├── screenshots/screenshot-fullscreen-all-1024x700.png     # ③ 小视口 1024×700
├── screenshots/screenshot-left-collapsed-1024x700.png
└── mut-runs/m1/ m2/ m3/            # 各变异轮 e2e 输出与截图
```

**提交**：`m7g/t1`（不 push；`.superpowers/**` 用 `git add -f`）。
