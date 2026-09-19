# M8 T7+T8 报告 —— 五模式框架 + 地图编辑模式 UI

> 分支 `m8/t7`（worktree `.claude/worktrees/m8t7`），基线 `ea00569`（M8 T4 合并）。证据全在 `.superpowers/sdd/2026-09-19-map-edit/t7-evidence/`。
> ★ 本报告只写**实测值**；推断单列（§8）。

## 0 一句话结论

五模式（常规 / 区域查看 / 地图编辑 / 区域编辑 / 单位移动编辑）**从占位变真控件**，当前模式在 `#mode-current` 可见、`aria-pressed`/`body[data-mode]` 可断言；**写权限白名单是一个纯函数模块** `modes.js`（`SimosModes.isWriteAllowed`），在 `app.js` 的 `writeCommand` **发请求之前**把关（fail-closed）——常规/区域查看模式**真拖 + 直调写命令都是零写**。地图编辑模式有**地形调色板**（完全取自后端 `terrainTypes`，不硬编码）+ **拖刷**（真 pointer 事件，拖过 5 格 ⇒ **一条** `map.SetTerrain`）+ **区域信息编辑**（多值 `regions` + 只改 meta 的 `map.UpdateRegion`）。全量门禁 **924** 绿、变异 **5 轮 0 存活**；真档 19441 格（副本）e2e **27 断言全 PASS**，原档 md5 未变、5817/5818 未动。

## 1 改动（逐文件 + 行）

### 1.1 新增（纯前端）

| 文件 | 行 | 内容 |
|---|---|---|
| `webui/modes.js` | 88 | ★ **五模式 + 白名单纯函数**（无 DOM / 无 IO）：`MODES` 表 + `modeIds()`/`modeLabel()`/`allowedWrites()`/`isWriteAllowed(mode,type)`；未知模式/空 type **拒绝**（fail-closed）；`allowedWrites` 返回**快照**（调用方改不动内部）。 |

### 1.2 修改（纯前端）

| 文件 | 改动 |
|---|---|
| `webui/index.html` | 模式栏 5 按钮：去掉 `disabled`/`data-milestone`，按 spec §三 改标签为「常规 / 区域查看 / 地图编辑 / 区域编辑 / 单位移动编辑」；新增 `#mode-current`（当前模式中文名）；左栏新增 `section.map-editor[data-modes="map-edit"]`（调色板 `#terrain-palette` + 拖刷状态 `#brush-status` + 两个置灰的 T11 占位按钮 `data-pending="map.SetEdge"/"map.RandomizeRegion"` + 区域信息 `#region-info-detail` + 元数据编辑器 `#region-meta-editor`）；右栏区域面板改为 `data-modes="region region-edit"`；引入 `modes.js`。 |
| `webui/app.js` | `+16`：`applyMode` 同步 `#mode-current`；`setMode` 切模式清 `selection`/`highlightRegions`；`writeCommand` 开头加白名单闸（拒绝时返回 `{ok:false,kind:"mode-denied"}`，**不发请求**）。 |
| `webui/map.js` | `+424`：渲染器加**刷子预览**（`setBrushHexes`/`brushList`/`paintAt`/`paintBrush`，模式感知的 pointer 处理）；宿主加**调色板**（`renderTerrainPalette`/`selectTerrain`/`updatePaletteSelection`）、**拖刷提交**（`commitBrush`：去重集合 → 一条 `map.SetTerrain`）、**区域信息面板**（`renderRegionInfo`/`fillRegionMetaEditor`/`loadRegionMeta`/`submitRegionMeta`）、模式切换清刷子/路线、`mapEditDebug`/`commitPaintForTest` 断言钩子。 |
| `webui/styles.css` | `+98`：`.mode-current` 徽章、`.terrain-palette`/`.terrain-swatch`（含 `.active` 选中态）、`.map-edit-pending`、`.region-meta-editor`。 |
| `simos-app/src/test/.../WebuiAssetsTest.java` | **★ 唯一 Java 改动（见 §7 分歧 1）**：把 M7 期"两个编辑模式必须 disabled + `data-milestone="M8"`"的断言，改为"五个模式都是可点击真控件"；方法数不变（8→8）。 |

★ 未加依赖/npm/CDN；未改 M9 块渲染路径核心（只在 `render()` 里加一层 `paintBrush`）；未改任何生产 Java/命令/端点；未改 spec/plan/台账。

## 2 ★ 五模式与白名单（实测）

来源：`modes-selfcheck.cjs`（纯函数）+ e2e `b-whitelist-pure-function`（浏览器内同一模块）。

| 模式 id | 标签 | 白名单写命令（实测） | 本轮是否真发过 |
|---|---|---|---|
| `view` | 常规 | **无** | 否（零写断言） |
| `region` | 区域查看 | **无** | 否（零写断言） |
| `map-edit` | 地图编辑 | `map.SetTerrain` / `map.SetEdge` / `map.RandomizeRegion` / **`map.UpdateRegion`**（见 §7 分歧 3） | `SetTerrain` ✅、`UpdateRegion` ✅；`SetEdge`/`RandomizeRegion` 白名单放行但 UI 置灰未发 |
| `region-edit` | 区域编辑 | `map.CreateRegion` / `map.UpdateRegion` / `map.DeleteRegion` | 否（UI 归 T10） |
| `unit` | 单位移动编辑 | `unit.PlanRoute` / `unit.CancelRoute` **+ `ReparentUnit` / `SetStrength` / `DisbandUnit` / `CreateUnit`**（见 §7 分歧 2） | `PlanRoute` ✅、`CancelRoute` ✅、`CreateUnit` ✅ |

实测 `isWriteAllowed`（e2e）：`view:false, region:false, map-edit:true, map-edit/SetEdge:true, map-edit/Randomize:true, region-edit/UpdateRegion:true, unit/PlanRoute:true, 未知模式:false, 空 type:false`。
★ **常规/区域查看零写**：两模式下各做一次**真拖**（canvas 25 步）+ 直调 `writeCommand("map.SetTerrain")` ⇒ `deniedView/deniedRegion = {ok:false, kind:"mode-denied"}`，且**非 GET 请求数 = 0**（`readonlyNonGet=[]`）。
★ 白名单是**前端**概念（spec §三）：服务端仍是 `CommandBus` 统一校验；模式不构成安全边界。

## 3 ★ 拖刷一条命令的请求载荷（实测）

真档副本、真 `pointer` 事件（`page.mouse.down/move/up`，25 步），拖过 `test_nation` 里同一行连续的 5 格：

```
POST /api/command
payloadJson = {"hexes":[{"q":-18,"r":0},{"q":-17,"r":0},{"q":-16,"r":0},{"q":-15,"r":0},{"q":-14,"r":0}],"terrain":"ocean"}
```

| 指标 | 实测值 |
|---|---|
| `map.SetTerrain` POST 条数（本次拖动） | **1**（不是每格一条） |
| `hexes` 长度 | **5** |
| `head` | **1 → 2**（恰 +1） |
| 时间轴节点数（revision 数） | 1 → **2** |
| 时间轴 tick 组数 | **1 → 1（不变）** |
| 头节点 `data-count`（命令明细） | 1 → **2** |
| 拖动中预览（松手前） | `painting=true, brushHexCount=3`（拖到第 3 格时截图） |

⇒ M7f 的**按 tick 分组**语义未被破坏：同 tick 的多条命令视觉归并，只多一条**命令明细**、不新增节点。

## 4 验证实测值

### 4.1 门禁（`./mvnw clean verify`）

**rc=0、924 条 = 170/321/45/131/161/96、7/7 reactor 项（6 个 jar 模块）、`BugInstance size is 0` ×6、`[ERROR]` 0 行**（`logs/full-verify-final.log`）。

| 模块 | 基线 924 | 本单 | delta | 解释 |
|---|---|---|---|---|
| UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos | 170/321/45/131/161 | 同 | 0 | 纯前端未动后端 |
| SimosApp | 96 | **96** | **0** | `WebuiAssetsTest` 仅改一个用例的**函数体**（方法数 8→8），条数不变 |
| **合计** | **924** | **924** | **0** | |

### 4.2 Playwright e2e（真档副本 + 真 `ShellMain`，端口 45946；`logs/clean-after-mutants-e2e.log`）

**27 断言 ALL PASS**（`E2E RESULT: ALL PASS`），0 `pageerror`。要点：

| # | 断言 | 实测值 |
|---|---|---|
| a | 五模式可切 + 当前模式可见 | 五个 `#mode-current` 文案/`body[data-mode]`/`aria-pressed=true` 全对 |
| a2 | 切模式清状态（切走再切回） | `before {sel:(-18,0), hl:2, debugHl:701}` → `after {sel:null, hl:0, debugHl:0, brush:0}` → `back {sel:null, hl:0}` |
| b | 白名单 | §2 的 `isWriteAllowed` 全对；常规/区域查看 `mode-denied` + `nonGet=[]` |
| c | 拖刷一条命令 | §3 |
| d | 地形真变 | 改前 `{-18,0:low_hills, -17,0:plains, -16,0:plains, -15,0:plains, -14,0:mountains}` → 改后**五格全 `ocean`** |
| d | 离屏位图重建 | `terrainRebuilds 4→5`；canvas 像素采样 `[31,95,160]` == ocean `#1F5FA0` = `[31,95,160]` |
| e | 调色板只列后端词表 | `palette=["ocean","plains","low_hills","mountains"]` == 后端 `terrainTypes`；不含 `forest`/`tundra` |
| e | 选中态可见 | `selectedTerrain="ocean"` 且该按钮 `.active` |
| — | 区域信息多从属 | `(-14,0)` 显示 `regions: test_annex_target、test_nation`（**2 个**），元数据编辑器可见 |
| — | 区域元数据编辑 | 一条 `map.UpdateRegion`，`{regionId:"test_annex_target", meta:{color:"#123456",…}}`（**无 `hexes`**）；`head 2→3`；重取 `/api/map/region/test_annex_target` 得 `color=#123456`（改前 `#fc6dce`） |
| f | 负例（词表外/图外） | 状态栏原文 `未知地形类型: forest` / `hex 不在图上: 9999_9999`；`head` 不变、时间轴行数不变 |
| g | 不退化 | 右键路线 `unit.PlanRoute`×1 + `routeCount=1`；左键取消移动 `unit.CancelRoute`×1 + `routeCount=0`；浮层不穿透（点 `#view-reset`：非 GET/`map/hex` 增量均 0）；0 `pageerror` |
| h | 截图 | `logs/clean-after-mutants/screenshot-map-edit-brush.png`（调色板 + 选中 ocean + 3 格蓝色拖刷预览）、`.../screenshot-map-edit-after.png`（5 格变 ocean + 左栏 `terrain ocean（海洋）`） |

非 GET 清单（全程）：`["/api/command"×7]` = SetTerrain 1 + UpdateRegion 1 + 负例 SetTerrain 2 + CreateUnit 1 + PlanRoute 1 + CancelRoute 1。**常规/区域查看阶段为 0**。

### 4.3 原档保护

- 原档 `/tmp/m6-import-verify/test_integration/simos.db` 跑前 = 跑后 = `2348b9365e5b107945a305d06fad8fab`（全程只用副本；副本 md5 变化是写入的预期）。
- `5817`/`5818` 全程未动（`ss -ltnp` 实测仍在监听，pid 424178/424180）。
- 无残留 e2e/probe Java 进程（`ss -ltnp` 无 459xx）。

## 5 变异（5 轮 × 语法自检 + 真 e2e，全杀）

装置 `mutants/mut-run.sh`：`src` 必须逐字节 == `orig`（**装置自证**，见 §6 教训）→ 变异体字节不同 → `node --check`（**语法不过则本轮作废**，避免"假红"）→ 同步进 `target/classes/webui`（**否则 e2e 跑旧字节**）并比 md5 → 跑对应验证（期望红）→ `cp` 逐字节还原 src+classes 并比 md5 回 `orig`。日志自指 md5。

| m | 目标 | 变异 | 红点（实测原文） | 结论 |
|---|---|---|---|---|
| m1 | `modes.js` | 给「区域查看」放行 `map.SetTerrain` | selfcheck `region-no-writes` 红；e2e `2 FAIL b-whitelist-pure-function,b-readonly-zero-write` | **KILLED** |
| m2 | `app.js` | `setMode` 删掉清 `selection`/`highlightRegions` | e2e `2 FAIL a2-mode-switch-clears-state,d-offscreen-bitmap-rebuilt`（后者是残留高亮把像素染色的**级联**） | **KILLED** |
| m3 | `map.js` | `commitBrush` 改成**每格一条**命令 | e2e `4 FAIL c-one-command-one-head,c-payload-five-hexes,c-timeline-node-unchanged-detail-plus-one,c-command-detail-plus-one` | **KILLED** |
| m4 | `map.js` | 调色板硬编码多列一个 `forest` | e2e `1 FAIL e-palette-only-backend-vocab` | **KILLED** |
| m5 | `map.js` | `setData` 删 `terrainDirty=true`（数据变不重建位图） | e2e `1 FAIL d-offscreen-bitmap-rebuilt` | **KILLED** |

逐轮门禁：`syntax_rc=0`、`classes_md5==mutant_md5`、`restored_md5==orig_md5`（src 与 classes 双还原）。
存活项：**无**。

## 6 ★ 装置教训（形态 1 新实例，已在本单当场修掉）

变异装置第一版用 `cp -n` 建 `mutants/orig/`。我随后改了 `src/modes.js`（+`map.UpdateRegion`），`orig` 却因 `-n` **未刷新**；跑 m1 时 **还原把 `src/modes.js` 写回了旧版本**，连带 m2~m5 的 e2e 全部在 `region-meta-update-one-command` 上出现**假红**（`mode-denied`）。这正是 CLAUDE.md 形态 1 的"陈旧副本"族——**加害者不是被测物，是装置的基线快照**。
修法已落进装置：每轮开跑前 **断言 `src` 逐字节等于 `orig`**，不一致直接 `DEVICE FAIL`，不再静默还原。修后重跑 m1~m5，红点全部干净（§5）。

## 7 与派单/spec 的分歧（以源码/spec 原文为准）

1. ★★ **我改了 Java（派单 MUST NOT「不改 Java」）**，仅此一处：`simos-app/src/test/.../WebuiAssetsTest.java` 的 `editModesAreVisibleButDisabledAndMarkedM8` **硬断言**「地图编辑/区域编辑必须 `disabled` 且带 `data-milestone="M8"`」——这是 M7 占位期的断言，与 T7「从占位变真控件」**直接矛盾**，不改则 `./mvnw clean verify` 必红。
   - 该改动是 **test-only**（不碰任何生产 Java/端点/命令），**只换一个方法的断言方向**，**方法数 8→8**、`SimosApp` 仍 96、**全量 delta 0**。
   - 我**没有**采用"HTML 里保留 `disabled`、运行时用 JS 摘掉"的写法：那会让门禁继续断言一个**已经为假**的事实（把假绿留在护栏里），违背本项目"护栏必须自证、不留假绿"的纪律。
   - ⇒ 派单的"不改 Java"应理解为"**不改生产 Java / 不加端点**"；若确指"连测试也不许动"，则本单**无法完成**（T7 的定义就是推翻该断言）。
2. **单位模式白名单比 spec §三 多 4 条**：spec §三 只列 `unit.PlanRoute`/`unit.CancelRoute`，但 M7 T7 起「单位移动与编辑」面板**已存在** `ReparentUnit`/`SetStrength`/`DisbandUnit`/`CreateUnit` 四个真写入口。若严格按 spec 只放行 2 条，这四个既有功能会被白名单**当场挡死**（违背 MUST NOT「不破坏既有能力」）。⇒ 白名单按**源码实际写面**列全 6 条，并在此登记分歧。
3. **地图编辑白名单补了 `map.UpdateRegion`**：spec §三 的写列只列 `SetTerrain`/`SetEdge`/`RandomizeRegion`，但**同一行的 UI 列**明写「区域信息编辑」，且本单 MUST DO #8 要求该面板改 `RegionMeta` 走 `map.UpdateRegion`。⇒ 写列漏了一条，按 UI 列补上（**只改 meta，不动 hexes**；区域内容编辑仍归 region-edit）。
4. ★ **调色板实测列 4 类，不是 7 类**：`/api/map/overview` 的 `terrainTypes` 来自**状态里的** `map.terrainTypes()`（M7 裁定 68「取状态词表子集」，`ApiViews.terrainTypeDefinitions`），真档 `test_integration` 实测 `{ocean, plains, low_hills, mountains}`。调色板**正好列出这 4 类**（完全取自后端、零硬编码）。派单/我期望的"7 类"是 `TerrainCatalog.KEYS` 的全集；服务端有意只发状态词表。**要把 7 类都列出来需要改服务端**（另开单），本单按"列不出来就说"如实报告。
5. 模式标签由 M7 的「常规查看 / 单位移动与编辑」改为 spec §三 的「常规 / 单位移动编辑」（测试常量同步更新）。
6. 本机 `:5817`/`:5818` 全程未 kill；e2e 自选 459xx 端口、自起自收。

## 8 实测 vs 推断

| 项 | 类型 | 依据 |
|---|---|---|
| 全量 924 绿 / BugInstance 0×6 / ERROR 0 | **实测** | `logs/full-verify-final.log` |
| 五模式可切、白名单真值、常规/区域查看零写 | **实测** | `logs/clean-after-mutants-e2e.log` + `logs/modes-selfcheck.log` |
| 拖刷一条命令：载荷 5 格 / head+1 / 节点数不变 / 明细+1 | **实测** | 同 e2e + `logs/clean-after-mutants/result.json` |
| 地形前后逐值、像素==ocean、位图重建计数 4→5 | **实测** | 同 e2e |
| 区域元数据编辑 `map.UpdateRegion`（只 meta） | **实测** | 同 e2e（含重取 `/api/map/region` 得 `#123456`） |
| 原档 md5 不变、5817/5818 未动 | **实测** | `md5sum` + `ss -ltnp` |
| 5 变异全杀 + 装置自证 | **实测** | `mutants/logs/m*-device.log` |
| `SetEdge`/`RandomizeRegion` 端到端可用 | **未测（UI 归 T11）** | 白名单放行 + UI 置灰；从未真发 |
| 触摸/触控笔拖刷 | **未测** | 仅 mouse pointer |
| HiDPI（dpr>1）像素采样 | **未测** | 本机 dpr=1 |
| 409 冲突（写后游标过期）走前端 | **未测** | 沿用 M7 的 `writeCommand` 409 分支；本单只测了 422 |
| region-edit 模式的实际编辑 UI | **未测（T10）** | 只证模式可切 + 白名单放行 |

## 9 我未能核实的

1. **浏览器里 >2 个区域同属**：e2e 命中格最多 2 从属；T1 的 Java 探针证过 3 从属，但**浏览器渲染 >2 未测**。
2. **`map.SetEdge` / `map.RandomizeRegion` 经 GUI 提交**：白名单放行、按钮置灰，**从未真发**（T11）。
3. **区域编辑模式 UI**（T10）不存在，只有模式切换与白名单。
4. **409 冲突路径**（写后游标被别处推进）未在 e2e 复现；422 已实测。
5. **触摸/触控笔**与 **dpr>1** 未测。
6. `commitPaintForTest` 是留在生产 JS 里的**断言钩子**（与既有 `bench*` 同形态），未加编译期隔离。
7. 前端护栏仍**不进 Maven 门禁**（T2 未做）——本单的 JS 白名单自检是**证据级** node 运行，非 CI。

## 10 证据索引

```
t7-evidence/
  t7-report.md                         # 本报告
  modes-selfcheck.cjs                  # 白名单纯函数自检（10 CHECK，ALL PASS）
  e2e/e2e.cjs                          # Playwright e2e（27 断言）
  e2e/run-e2e.sh                       # 起真 ShellMain（真档副本，端口自选）
  logs/full-verify-final.log           # 全量 924 绿（最终）
  logs/full-verify.log                 # 全量 924 绿（首轮，Spotless 修复前；保留）
  logs/modes-selfcheck.log             # 纯函数自检
  logs/clean-final/                    # 干净轮（含 result.json + 2 截图）
  logs/clean-after-mutants-e2e.log     # 变异后干净轮（27 断言 ALL PASS）
  logs/clean-after-mutants/            # 同上：result.json + screenshot-map-edit-brush.png / -after.png
  logs/e2e-server-clean-after-mutants.log
  mutants/mut-run.sh                   # 变异装置（语法自检 + src/classes 双同步 + 双还原 + 自证）
  mutants/orig/{modes,app,map}.js      # 逐字节基线（与当前 src 一致）
  mutants/logs/m{1..5}-device.log      # 5 轮变异（自指 md5 + 红点原文 + 还原 md5）
  mutants/logs/m{1..5}-e2e.log         # 各轮 e2e 全文
  mutants/logs/m1-selfcheck.log        # m1 纯函数自检红
```
