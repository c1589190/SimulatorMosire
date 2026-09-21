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

- **U4（换到 n0008 最全区域）**：用户明说「这个得等修完前两项再说」 ⇒ U1/U2/U3/U5 **那批**不做。
  ★ **U4 已由后续单任务落地，设计与实测见 §七**（本行不改写前面的裁定，只补记录）。
- 不改五条铁律 / 模块依赖；不引入 npm/CDN/构建；不做真正的多用户认证。
- 不改上一阶段已关账的 spec/plan 正文；不回改 T9/M8 的既有断言为恒真。

## §六 我未能核实的（如实登记）

- `regionLabelLayout` 的字号/质心在**极端形状**（带洞 / 多环 / 细长区域）下的观感：质心可能落在区域外（GSimulator 同样如此），本任务只证"数值按配方算"。
- 真浏览器 e2e 只跑 `--demo`/可访问实例；**触摸 / 高 DPI（dpr>1）下的标签清晰度未测**。
- `localStorage` 持久化跨刷新**在 e2e 里未独立断言**（node 宿主无 localStorage；浏览器侧只验默认开与 toggle 生效）。

---

## §七 U4 —— 富世界改用截至 n0008 回合的最全区域

> 落地单任务（U4），基线 `9c4a987`（含 U1/U2/U3/U5）。判据实测与变异见
> `.superpowers/sdd/2026-09-22-webui-fix2/u4-evidence/u4-report.md`。

**问题（已核实）**：T11 复刻用的是 `n0000_map.json`（回合 0 基础图，**98** 区域）；
`n0001~n0007` 是逐回合 MapDiff 增量、`n0008.json` 是**空壳** ⇒ 「截至 n0008 回合」的最终
区域构成只能**逐 diff 累积**；而 `tools/gsimap_import.py` **拒绝 MapDiff 输入**（顶层含
`parentNodeId/changed` 即 fail-closed）。

**裁定（U4-1 物化口径 / U4-2 范围）**：
- **U4-1**：新增可重跑脚本 `tools/materialize_v17levant.py`：把 `n0000_map.json` 依次应用
  `n0001~n0007` 的 diff，产出一份**完整 map.json** 再交给导入器。**province 口径照 GSimulator
  `MapResolver.applyDiff`（`:224-230`）**：先 `remove(provinces_removed)`、再 `put(provinces_changed)`
  （= upsert / 整对象全量替换）；并校验每份 diff 的 `parentNodeId` 与前一节点一致（链断裂 ⇒ fail-closed）。
- **U4-2**：★ **只物化 provinces，hex 段（`removed`/`changed`）有意不应用**。U4 的对象是「最全的
  **区域**构成」，判据锁定 **hex 数 59223 / 地形直方图 / 河流边数 240 不变**；实测若连 hex 段一起应用，
  直方图会变（`hills 14107→16489`、`plains 11315→9708`…）、河流有向条目 `480→894` ⇒ 与判据矛盾。
  这是**显式取舍**（GSimulator 的 `applyDiff` 也应用 hex 段；本次不跟）。
- **U4-3**：对拍装置 `tools/check_v17levant_import.py` 的期望值全部按物化档更新（区域 252、
  tag 全 `Nation`、新增**区域逐格内容 sha256** 冻结摘要、可选第 3 参数做「最全」证明）；
  `RichWorldTest` 的 98 断言改为 252 + 新增「n0000 没有、最终有」的实名区域断言（**不放松成恒真**）。
- **U4-4**：`docs/worlds/v17levant/*.md` 与 `tools/v17levant_docs.py` / `check_v17levant_docs.py`
  **保持 98**——它们是**叙事/文档产物**（从存档 `checkpoints[].elements[]` 导出，615 条），
  与"运行时世界的区域集"是两件事；U4 不改它们（记为遗留）。

**判据（实测值见报告）**：区域数 **252**（源/资源一致）；hex **59223** 不变；河流边 **240** 不变；
覆盖面 **18.67% → 32.37%**；抽查实名区域 `瓦伦狄乌斯专制国`(578 hex) / `霜脊伯国`(19) /
`大汉都护府政权`(42) / `艾达王国`(234) / `蒙特卡西诺修道院领`(10) 均在 n0000 **不存在**、最终存在；
多从属样例 `(-105,67) → {区域14, 石冠诸部}`、最大 **3** 从属；资源经**真读路径**往返一致。

---

## §八 V3（第 3 批）—— 点击 ⇒ 选中「拥有该 hex 的**最顶层区域**」

> 用户原话：「我希望在**区域查看/编辑模式下，点击一个地方，就自动选中拥有这个 hex 的最顶层区域**（其实就是防止有重叠区域）」；
> 「最顶层」的**定义**由用户当场裁定 = **按「创建/定义顺序」，后定义的在上**（用户答「1」）。

**★★ 这是一处有意的语义变更（明确推翻两条既有裁定）**：
- ★ **推翻 M8-Q6**：`/api/map/hex` 的 `regions` 由**字典序**改为**定义序**（`GameMap.regions` 的插入序）。原裁定"字典序"的
  理由（JSON 不随哈希序变 ⇒ 用例稳）依然成立——定义序同样是**确定的**（存档里的 `LinkedHashMap` 插入序，M2 起保序），
  两次调用仍逐字节相同；变的只是"哪个是末位"。
- ★ **推翻 U3-2**（本批 §〇 刚做的）："owner > 1 ⇒ 退回 group（全部等亮）" ⇒ 改为**一律取顶层那一个**（`highlightKind:"single"`）。
  ★ **点 tag 的语义不变**（仍是该 tag 全部等亮 —— 那是 `group`，别与地图点格混淆）。
- ★ **M8-U1 不破**：从属仍是多对多、重叠**全部保留**（`RegionIndex` 不改、仍只管从属）；V3 只加一条**排列约定**，不引入"谁赢"。

**落地（最小风险：序从 `GameMap.regions` 派生，`RegionIndex` 保持原样）**：
- `MapResolver.regionOfHex`：成员仍取 `GameMap.regionIndex().regionOf(hex)`（一次 `Map.get`，L5 不变），随后**按
  `map.regions().keySet()` 的插入序过滤重排** ⇒ 列表**末位 = 最顶层**。
- 前端 `map.js`：纯函数 `topRegionId(regionIds)`（**只取末位、绝不重排**）+ `selectRegionOfHex` 改用它、恒 `single`。
- ★ **不做**结构改动（`RegionIndex` / `GameMap` / `MapChangeSet` 一字未动）⇒ 铁律 5 往返不变式不受影响。

**判据**：夹具让**定义序与字典序分叉**（`MapResolverTest` 两从属 `r2,r1` 与三从属 `m,z,a`；`MapRegionEndToEndTest` 真命令路径
`zz→aa`）⇒ 断言末位 = 定义序最后者且 `isNotEqualTo` 字典序末位；前端 `topRegionId` 同款；e2e 在真富世界 `[54,-67]`
（定义序 `[大蜀, 东川]`、字典序 `[东川, 大蜀]`）上断言点击选中的是 **东川**（定义序末位）并给像素/取色证据。
