# WebUI 阶段修复（第 2 批）—— 设计增补（U1 / U2 / U3 / U5）

> 状态：**已裁定**（U1~U3、U5 的落地形态由本文件裁定；**U4 不在本任务范围**，见 §五）。
> 作者：控制器（AI 代笔）。日期：2026-09-22。仓库：`feat/adr1-core-scope`；基线 = `3cb823f`。
> 前置：`docs/superpowers/specs/2026-09-22-webui-fix2-feedback.md`（**用户原话逐字**，U1~U5）、
> `docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md` 与 `-plan.md`（上一阶段的纪律 / 门禁口径 / 证据落点）。
> ★ **本文不修改** feedback 文档与上一阶段已关账的 spec/plan 正文；本批新增的已核实事实与裁定写在这里。
> 计划见 `docs/superpowers/plans/2026-09-22-webui-fix2-plan.md`。

---

## §〇 裁定表

| # | 裁定 | 依据 / 理由 |
|---|---|---|
| **U1-1** | 区域模式的"地形压暗" = **全画布深色 scrim**（`#0a0d12`，alpha **0.55**），**只**在 `region` / `region-edit` 两模式画；插入点 = `paintTerrain`/blit 之后、`paintHighlights` 之前 | 用户原话「hex地形底图保持高亮，导致区域根本看不清」；现状 `render()`（`map.js:999-1019`）地形与高亮之间**没有任何压暗层**（全仓 `scrim|darken` 零命中）。scrim 在屏幕空间画（`setTransform(dpr…)` + `fillRect(0,0,cssW,cssH)`），不随缩放改变强度 |
| **U2-1** | 区域名的**位置数据由服务端 `overview` 增发** `regions[].label = {q,r}`（区域 hex 的**质心 hex**，取整）；**不由客户端拉取全部区域 hex 现算** | M9 的教训：overview 已是 78,648 B 的**块**载荷，逐区域 hex（97 个 × 数百格）会再引入 **N+1 请求 + MB 级载荷**。质心是 2 个整数/区域 ⇒ 增量可忽略 |
| **U2-2** | 渲染配方**照 GSimulator**（`render.js:348-364`）：质心 `hexToPixel` 居中、字号 `max(8,min(40,√hexCount×1.8))/zoom`（≈常量屏幕字号、∝√格数）、黑描边白字 | 用户明说「GSimulator后期所带有的区域名称显示也没做出来」⇒ 是"没做"，照现成配方补 |
| **U2-3** | 区域名**默认开**、`localStorage` 持久化（键 `simos.regionNames.v1`），顶栏加开关；★ 并加**最小缩放阈值**（`scale ≥ 0.25` 才画） | GSimulator 默认开 + `province.js:394-408` toggle+localStorage。阈值是本项目补充：世界视图（demo 实测 `scale≈0.06`）下 97 个标签会堆叠成噪声 |
| **U3-1** | 高亮语义引入 `highlightKind: "group" \| "single"`：**点 tag / 多从属格 = group**（全部等亮，alpha **0.42**，**沿用现状**）；**选中单个区域 = single**（该区域 alpha **0.62** 更亮；**淡色只压同 tag 的其他区域**） | 用户原定计划逐字（feedback §二 U3）；现状 `buildRegionHighlightPlan`（`map.js:2319-2388`）的 `faded` 是**除焦点外的所有区域**（不限同 tag），且单区域与 tag 全选同为 `focusAlpha=0.42` ⇒ 用户要的"更高亮度"当前不成立 |
| **U3-2** | 单区域的两个来源：**右栏列表点一条**（`panels.js:541-548`）与**地图点击**（`/api/map/hex` 的 owner 数 **== 1** 时单区）；owner 数 **> 1 仍高亮全部**（保留 M8-U1「hex 兼容多种从属」与 T9 的既有能力） | 修 feedback §二 U3 的「地图点击只到 hex 粒度」：owner 数即区域命中判据；多从属格选择"一个区域"是歧义的，故退回 group |
| **U5-1** | 右侧空卡片的根因 = `index.html:237` 的 `#right-panel` **没有 `data-modes`** ⇒ 永不隐藏；在 `view`/`map-edit`/`unit` 模式下其子元素全隐藏 ⇒ 右边缘留一个 300px 空卡片。修法：给 `#right-panel` 加 `data-modes="region region-edit decision"` | `styles.css:296-298` 的 `[hidden]{display:none!important}`（隐藏 section 不占位）+ `app.js:155-158` 按 `[data-modes]` 切 `hidden`。实测：`view` 模式右栏 `x=968,w=300,h=631` **可见且全空** |
| **U5-2** | 面板**按内容**定尺寸、**不再写死 300px**：`align-items: flex-start`（高度=内容，不撑满一列空白）+ `.col-left/.col-right` 改内容宽（`flex: 0 1 auto; width: fit-content; min-width: 200px; max-width: 340px`） | 用户原话「左边的栏目占了一大块空白，右边被挤占到最边上一行了」；现状 `.wb-body{align-items:stretch}` + `.panel{max-height:100%}`（`styles.css:912-944`）⇒ 300×631 的空卡片。回归判定：T7（`349d3ce`）只往 CSS 末尾追加 ⇒ 布局来自 M7g |
| **U5-3** | 同 tag 判定 = `overview.regions[].meta.tag` **逐字相等**（含 `null` 相等） | 与 `nationTagOf`（`map.js:2205`）同口径；overview 已发 `meta`（`ApiViews.java:184`） |

★ **U1~U5 编号沿用 feedback §二**；本文件新增的裁定用 `U1-1`… 子号，**不与 feedback 的 U 号混用**。

---

## §一 U1 —— 区域模式的地形压暗

**现状（已核实）**：`render()`（`map.js:999-1019`）顺序 = `paintTerrain`/`blitTerrainCache` → `paintHighlights` → `paintRegionOutlines` → …；地形是纯色全不透明（`paintTerrain` `:807-823`），区域填充只是半透明叠加（`HIGHLIGHT_ALPHA=0.42` `:31`）。**中间没有压暗层**。

**落地**：
- 常量：`REGION_DIM_COLOR="#0a0d12"`、`REGION_DIM_ALPHA=0.55`、`REGION_DIM_MODES=["region","region-edit"]`。
- 纯函数 `terrainDimAlpha(mode)`：模式 ∈ 列表 ⇒ `0.55`，否则 `0`（供断言与调试）。
- `render()` 在 terrain 之后、`paintHighlights` 之前：
  `ctx.setTransform(dpr,0,0,dpr,0,0); ctx.fillStyle=withAlpha(REGION_DIM_COLOR, alpha); ctx.fillRect(0,0,cssW,cssH); worldTransform();` 并累加 debug 计数 `dimPasses`。

**判据**：`terrainDimAlpha("view")===0`、`("region")===0.55`、`("region-edit")===0.55`；浏览器**像素级**：同一世界点在 view 与 region 模式下采样，region 的那一像素**严格更暗**（三通道各自 ≤ 且至少一通道 <，或亮度严格下降）。

---

## §二 U2 —— 区域名显示

**现状（已核实）**：simos 全部 `fillText` 只有 `map.js:783-784`（**单位 id**）；**没有区域名**。数据侧 `ApiViews.java:182` 已发 `name`、`:184` 发 `meta`（含 tag），但**无位置**。

**服务端（小改动）**：`ApiViews.mapOverview` 的每个 region item 增发 `label`：
- 取 `region.hexes()` 的质心 `sq/n, sr/n`，`Math.round` 得质心 hex；`hexes` 为空 ⇒ `label = null`。
- 用 `long` 累加避免溢出；输出 `{q,r}`（复用 `hexCoord`）。
- 确定性：同一状态两次调用逐字节相同（与 overview 其余部分同口径）。

**客户端（照 GSimulator 配方）**：
- 纯函数 `regionLabelLayout(region, scale)`：`{q,r,fontSize,text}`；`fontSize = max(8,min(40,√hexCount×1.8))/scale`；无 `label`/`hexCount≤0` ⇒ `null`。
- `paintRegionNames(ctx)`：对 `host.overviewRegions` 逐个画；黑描边（`rgba(0,0,0,0.85)`，宽 `3/scale`）+ 白字；仅当 `regionNamesEnabled && view.scale ≥ REGION_NAME_MIN_SCALE(0.25)`。
- 开关：`index.html` 顶栏 `#region-name-toggle`（checkbox，默认 `checked`）；`map.js` 读 `window.localStorage["simos.regionNames.v1"]`（`typeof` 守卫，node 宿主无 localStorage）、change 时写回 + `scheduleRender()`。
- 绘制位置：在 `paintRegionOutlines` 之后、`drawEdges` 之前（压在填充/边界之上、单位之下）。
- 调试投影 `regionNameDebug()`：`{enabled, minScaleOk, drawn, labels:[{text, screenX, screenY}]}`（供 e2e 取色）。

**判据**：`regionLabelLayout` 逐值（质心、字号公式、null 分支）；静态源码含 `strokeText`+`fillText`；服务端 `label` 与手算质心一致、两次调用逐字节相同；浏览器**取色级**：在某个 label 的屏幕坐标附近采样到**近白像素**（描边/字心）。

---

## §三 U3 —— 选择粒度（tag 全等亮 / 单区域更亮 + 同 tag 淡色）

**现状（已核实）**：`panels.js:513-524` 点 tag ⇒ `setHighlightRegions(ids)`（全部）；`:541-548` 点单区域也 ⇒ `setHighlightRegions([id])`；而 `buildRegionHighlightPlan` 对两者都用 `focusAlpha=0.42`，且 `faded` = 除焦点外**所有**区域 ⇒ 单区域**并不更亮**、淡色**不限同 tag**。

**落地**：
- `app.js` state 增 `highlightKind: "group"`；`setHighlightRegions(ids, kind)` 第二参可选，缺省推断（`ids.length===1 ? "single" : "group"`）；`setMode` 重置为 `"group"`。
- `map.js` 的 `buildRegionHighlightPlan` 增两个 option：
  - `singleFocusAlpha`（焦点恰好 1 个时用的更亮值）；
  - `fadeScope`：`"all"`（默认，**group 沿用现状**）| `"same-tag"`（single：只把**同 tag** 的非焦点区域淡色；异 tag 区域**不画**）。
- 新参数集 `REGION_SINGLE_HIGHLIGHT = { focusAlpha: 0.62, fadeAlpha: 0.13, fadeWhenNoFocus: false, fadeScope: "same-tag", singleFocusAlpha: 0.62, focusOutlineAlpha: 1, fadeOutlineAlpha: 0.45 }`。
- `reloadHighlights()`：`state.highlightKind==="single"` ⇒ 用 `REGION_SINGLE_HIGHLIGHT`，否则 `REGION_VIEW_HIGHLIGHT`。
- 调色板条目带 `tag`（`region.meta.tag`，逐字 / null）—— `reloadRegionHighlight` 从 overview 的 region 取。
- 来源：`panels.js` tag 按钮 ⇒ `("group")`、单项 ⇒ `("single")`；`map.js` `selectRegionOfHex` owner 数 1 ⇒ `("single")`、>1 ⇒ `("group")`。
- `regionViewDebug()` 增 `highlightKind`，便于断言"来源确实分了两档"。

**判据**：
- 纯函数：single 计划里焦点 `alpha=0.62`、同 tag 兄弟 `alpha=0.13`、异 tag 区域**零条目**；group 计划逐值**不变**（M8/T9 既有断言不动）。
- 页面：单区域点击走 `("single")`、tag 点击走 `("group")`（静态源码断言 + `regionViewDebug`）。
- 浏览器**取色级**：同一区域"tag 全选"vs"单选中"两次采样，后者更接近区域色（更饱和）。

---

## §四 U5 —— 详情页三栏布局

**现状（已核实，已在 5817 实测截图存档）**：`view` 模式右栏 `x=968,w=300,h=631` **可见且全空**；左栏 `300×631` 仅 3 行内容 ⇒ 大块空白。根因见 §〇 U5-1/U5-2。

**落地**：
- `index.html`：`#right-panel` 加 `data-modes="region region-edit decision"`。
- `styles.css`：
  - `.wb-body { align-items: flex-start; }`（面板高度=内容；不再撑满一列）；
  - `.col-left` / `.col-right`：`flex: 0 1 auto; width: fit-content; min-width: 200px; max-width: 340px;`（删掉 `flex: 0 0 300px; max-width: 320px`）。
  - `.wb-gap` 保留 `flex: 1 1 auto`（两侧仍各贴一边，但**空卡片消失、宽度随内容**）。

**判据**：
- 静态：`index.html` 的 `#right-panel` 带 `data-modes` 且**不含** `view`；CSS 不再有 `flex: 0 0 300px`。
- 浏览器布局级：`view` 模式 `#right-panel` `hidden=true`（`offsetParent===null` / `getComputedStyle().display==="none"`）；`region` 模式可见；左栏宽度 < 300（内容少时不再撑满 300）。

---

## §五 不做什么

- **U4（换到 n0008 最全区域）**：用户明说「这个得等修完前两项再说」 ⇒ 本任务**不做**。
- 不改五条铁律 / 模块依赖；不引入 npm/CDN/构建；不做真正的多用户认证。
- 不改上一阶段已关账的 spec/plan 正文；不回改 T9/M8 的既有断言为恒真。

## §六 我未能核实的（如实登记）

- `regionLabelLayout` 的字号/质心在**极端形状**（带洞 / 多环 / 细长区域）下的观感：质心可能落在区域外（GSimulator 同样如此），本任务只证"数值按配方算"。
- 真浏览器 e2e 只跑 `--demo`/可访问实例；**触摸 / 高 DPI（dpr>1）下的标签清晰度未测**。
- `localStorage` 持久化跨刷新**在 e2e 里未独立断言**（node 宿主无 localStorage；浏览器侧只验默认开与 toggle 生效）。
