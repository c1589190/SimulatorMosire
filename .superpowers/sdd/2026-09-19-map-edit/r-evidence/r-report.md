# M8-R 报告 —— 区域编辑器重做（右键套索 / 边界小点 / 拖点 / 合并·剔除）

- worktree：`.claude/worktrees/m8r`，分支 `m8/r`，基线 `4643b04`
- 权威依据：`docs/superpowers/specs/2026-09-19-region-editor-redesign.md`（§一 取证、§三 设计、§四 判据、§五 变异）
- 交付形态：**纯前端两文件**（`map.js` +712/−75、`index.html` +8/−7），**零 Java 改动**、无新依赖/npm/CDN。
- 结论：spec §四 判据 a~h **逐条实测通过**；变异 m1~m5 **全部被杀**（0 存活）；全量门禁 **924 = 170/321/45/131/161/96，delta 0**。

## 一 改动（逐文件）

### `simos-app/src/main/resources/webui/map.js`

| 位置（改后行号） | 改动 |
|---|---|
| `:42-56` | 新增 `DIR_VECTORS`（pointy-top 六邻），套索补点 / flood / 边界判定三处共用 |
| `:129-163` | 新增纯几何 `axialNeighbors` / `axialDistance` / `hexLine`（cube 线性插值 + `hexRound` 补点） |
| `:174-190` | `createRenderer` 新选项 `regionEditEnabled` / `onLassoCommit` / `onDotDragCommit` |
| `:215-224` | 渲染器态：`lassoKeys/lassoOrder/lassoActive`、`focusKeys/focusColorHex/focusBoundary/dotDrag` |
| `:979-1230` | 新增 `hexExists`（`SimosBlocks.blockAtHex` 权威判在图）、`addLassoPoint`、`floodFillFromWall`、`findLassoSeed`、`finishLasso`、`setFocusHexes`/`clearFocusHexes`、`boundaryDotAt`、`moveDotTo`、`flushDotDrag` |
| `:1290-1450` | `updateCursor`/`onPointerDown`/`onPointerMove`/`onPointerUp`/`onPointerCancel`：右键套索、左键命中边界点优先拖点、指针捕获抽 `capturePointer`/`releasePointer` |
| `:770-830` | 新增 `paintLasso`（品红折线 + 半透填）、`paintBoundaryDots`（**只对 `regionEditEnabled()` 且有 focus 时画**，画在边界 hex 中心） |
| `:794-800` | `render()` 叠层顺序：`highlights → draft → lasso → boundaryDots → brush` |
| `:1580` | `debug()` 增加 `lassoHexCount/lassoOrderCount/lassoActive/focusHexCount/focusBoundaryCount/dotDragging` |
| `:1336-1352,3438-3446` | 导出 `setFocusHexes/clearFocusHexes/focusHexes/focusBoundary/boundaryDotAt/lassoHexes/hexExists/lassoForTest`（只读测试面） |
| `:1861-1878` | 新增 `refreshFocusHexes()`：焦点区 hex → 渲染器（null/失败 ⇒ 不画点） |
| `:1796-1804,2016-2033` | `reloadOverview` / `onStateChange` 在 region-edit 下刷新焦点、离模式清焦点；**移除 `regionDrawEnabled` 一切残留** |
| `:2426-2470` | `renderRegionEditor`：删绘制开关同步，加 `region-merge`/`region-exclude` 启停 |
| `:2482-2514` | `newRegionDraft` 改写；**删除 `toggleRegionDraw`** |
| `:2552-2611` | `onLassoCommit`（判据 1）、`onDotDragCommit`（判据 3）、`unionHexes`/`differenceHexes`、`submitRegionMerge`（判据 4）、`submitRegionSubtract`（判据 5） |
| `:2660-2705` | `onRegionFocusChanged`：draft 不再自动载入区域 hex（临时选区独立）、推边界点 |
| `:2707-2731` | `wireRegionEditor`：去 `region-edit-draw`，加 merge/exclude |
| `:3040-3048` | `handleContextMenu`：**region-edit 早退 `return true`**（右键=套索，绝不落 PlanRoute）；unit 分支不变 |
| `:3457-3477` | `initHost`：`paintEnabled` region-edit 恒真；接 `regionEditEnabled`/`onLassoCommit`/`onDotDragCommit` |
| `:3592-3630` | `regionEditDebug` 去 `drawEnabled`，加 `focusHexCount/boundaryDotCount/lassoHexCount/lassoActive/dotDragging`；新增 `hexExists/lassoForTest/lassoHexes/boundaryDotAtScreen/focusBoundaryForTest` 只读探针 |

### `simos-app/src/main/resources/webui/index.html`

- **删除** `#region-edit-draw` 开关（"绘制选区：开/关"）→ 模式由按键决定（左键=画格、右键=套索）。
- `#region-edit-op` 文案改「左键加入选区 / 左键移除选区」；`#region-edit-draft` 改「临时选区」。
- 新增 `#region-merge`（合并＝临时选区 ∪ 目标区域）、`#region-exclude`（剔除＝目标区域 − 临时选区）。
- 帮助文案说明右键套索创建。

## 二 判据实测（真档 19441 格副本 + 真 `ShellMain` + 真 pointer 事件，端口 5827）

| 判据 | 实测值 |
|---|---|
| **a 右键套索创建** | 套索墙 **12 格**（≈ 环 R=2）+ flood 内部 **7 格** ⇒ payload **19 格**、**恰 1 条 `map.CreateRegion`**、`head 1→2`；★ **与 e2e 自写的独立 flood 逐值相同**（`payloadMatchesIndependentFlood=true`）、与 `GET /api/map/region/m8r_lasso` **逐值相同**（19/19） |
| **b 边界小点** | 选中 `m8r_lasso`：`focusHexCount=19`、**`boundaryDotCount=12` == 独立算出的边界数 12**；★ **取消选中（`setRegionFocus(null)`）⇒ `boundaryDotCount=0` / `focusHexCount=0`** |
| **c 拖小点增格** | `(-18,0)→(-18,-1)`：**1 条 `UpdateRegion`**、`19→20`、payload == `base ∪ {to}`（逐值 true）、`head 2→3` |
| **c 拖小点删格** | `(-18,-1)→(-17,-1)`：**1 条 `UpdateRegion`**、`20→19`、payload == `base − {(-18,-1)}`（逐值 true） |
| **d 合并** | 临时选区 3 格 ∪ 19 格 ⇒ **1 条 `UpdateRegion`**、payload **22** == 并集 **22**（逐值 true） |
| **e 剔除** | 22 格 − 临时选区 3 格 ⇒ **1 条 `UpdateRegion`**、payload **19** == 差集 **19**（逐值 true） |
| **f 勾选框已删 + 左键画格** | `#region-edit-draw` **不存在**、`section.region-editor` 内 `input[type=checkbox]` **0 个**；左键拖动 `draft 0→2`、**拖动期间 0 条写命令**；再「用临时选区创建区域」⇒ **恰 1 条 `map.CreateRegion`** |
| **g 右键按模式分派** | 区域编辑：写类型仅 `{map.CreateRegion, map.UpdateRegion}`、**无 `unit.PlanRoute`**；单位移动编辑：**恰 1 条 `unit.PlanRoute`**、0 条 `CreateRegion`；★ **区域编辑下即使选中单位，右键也不触发 `GET /api/map/path`（0 次）、不触发 PlanRoute（0 条）** |
| **h 不退化** | 重叠正例 `(-16,0).regions = [m8r_lasso, test_annex_target, test_nation]`（3 从属）；删除二次确认（armed 前 0 写 / 确认后 1 条）；焦点淡色 `alphas=[0.13,0.52]`；M9 `blockCount=44 / unmergedCount=0`、点选 `(-18,0)` 准；**0 `pageerror`** |

### 非 GET 清单（按阶段/模式分组，clean-final）

```
A-lasso-create              : ["map.CreateRegion"]
C-dot-add                   : ["map.UpdateRegion"]
C-dot-remove                : ["map.UpdateRegion"]
D-merge                     : ["map.UpdateRegion"]
E-exclude                   : ["map.UpdateRegion"]
F-leftpaint                 : []                       ← 左键画格只改草稿，零写
F-create-by-button          : ["map.CreateRegion","unit.CreateUnit"]
G-unit-route                : ["unit.PlanRoute"]
G3-region-edit-right-with-unit : []                    ← 选中单位也不落 PlanRoute
H-delete                    : ["map.DeleteRegion"]
```
全部落在 `/api/command`（R8 allowlist）。

### 截图
- `.superpowers/sdd/2026-09-19-map-edit/r-evidence/logs/clean-5/screenshot-lasso-inprogress.png`（套索创建中）
- `.../screenshot-region-dots.png`（选中区域 + 边界小点）
- `.../screenshot-after-merge-exclude.png`（合并·剔除后）

## 三 门禁

- `./mvnw clean verify` 绿（rc=0）：**924 = 170/321/45/131/161/96**、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0。
- 基线 **924 = 170/321/45/131/161/96** ⇒ **delta 0**（纯前端改动，符合预期）。日志 `logs/full-verify-final.log`（最终字节）与 `logs/full-verify.log`（改 `boundaryDotAtScreen` 前）。
- **原档写前/后 md5 一致**：`store_src_md5_before == store_src_md5_after == 2348b9365e5b107945a305d06fad8fab`（全程只用副本）。

## 四 变异（m1~m5，全部被杀；0 存活）

装置 `mutants/mut-run.sh`：① src==orig 自证；② 聚合 md5 **非空**；③ 应用变异后 **mutant_md5 != orig_md5**；④ `node --check` rc=0；⑤ 同步 `target/classes/webui` 并核 md5；⑥ 跑 e2e（期望红）；⑦ 从 `orig` **逐字节还原**；⑧ src/classes md5 复原；⑨ webui **聚合 md5 复原（8261638f… == 8261638f…）**；并把本轮字节 md5 追加进 `mutants/runs.log`（形态 6 自指）。

| m | 护栏 | 变异 | 期望红 | 实测红点 | 结果 |
|---|---|---|---|---|---|
| m1 | ★★ 重叠允许 | 套索创建前"与已有区域相交就拒绝" | 重叠正例红 | `a1/a2/h1` + 后续 9 条（区域未创建）| **KILLED** |
| m2 | 右键按模式分派 | 区域编辑右键仍走 `unit.PlanRoute` | 分派断言红 | `g3-region-edit-right-is-not-planroute`（`mapPathGets>0`）| **KILLED** |
| m3 | 小点只画选中区域 | 取消选中不清焦点（仍画点） | "非选中无点"红 | `b2-no-dots-when-unselected` | **KILLED** |
| m4 | 合并=并集 | 合并只取临时选区 | 并集逐值红 | `d1-merge-equals-union`（并连带 `e1`）| **KILLED** |
| m5 | 剔除=差集 | 剔除误用并集 | 差集逐值红 | `e1-exclude-equals-difference` | **KILLED** |

- 每轮 `restored_md5 == restored_classes_md5 == orig_md5 == 66fa591b9147c18762573a71e3a609e0`；`restored_aggregate_md5 == orig_aggregate_md5 == 8261638ff15eaa5c77032c8e5b81e4ef`。
- **m2 有两处必读**：① 直改"区域编辑右键走 PlanRoute"后，T7 白名单会在 `writeCommand` 处**先拦下 POST**（`unit.PlanRoute` 不在 region-edit 写列）⇒ 原断言（数 POST）**结构性打不红**；② 故断言补 `GET /api/map/path` 计数（`submitPathRoute` 在 `writeCommand` **之前**必发这条只读请求），m2 才被杀——**这是"护栏要有判别力"的又一实例**。
- **m3 的变异表达**：客户端按需只拉焦点区 hex（§3.3），"给所有区域画点"无数据可依；故取**可观测的等价形态**——"去选中后焦点集合未清 ⇒ 仍画点"。这是忠实于被测行为（"只有选中区域有点"）的变异，不是伪造红点。

## 五 与 spec / 派单的分歧（按源码原文为准）

1. **"原档 md5 `2348b936…`"的路径**：派单写原档为 `/tmp/m6-import-verify/test_integration`，但该文件现值 **`2ea3df60d0ddb5f9d26551be581c2ece`**（已被中间 M8 任务在同一路径上推进过）。**md5=`2348b936…` 的洁净副本在 `/tmp/m8t1-e2e-store`**（M8 T1 的副本），本次即用它作 STORE_SRC，写前=写后=`2348b936…`。⇒ 判据"原档 md5 一致"**满足**，但派单给的**路径**与该 md5 已不符。
2. **"勾选框"是按钮**：源码里没有 `input[type=checkbox]`，那个"显式开关"是 **`#region-edit-draw` 按钮**（文案"绘制选区：开/关"）。已删除，且 DOM 断言 `#region-edit-draw===null` **且** `section.region-editor` 内 checkbox 数 **0**。
3. **draft 语义变更（有意）**：M8 T10 的 `onRegionFocusChanged` 会把焦点区域 hex **自动载入 draft**。若保留，则「合并=临时选区 ∪ 区域」**恒等于原区域**（并集无意义）。故改为 **draft 独立为空**（临时选区靠左键画）；整份替换仍走「把目标区域 hex 载入选区」+「保存」。这与 T10 行为**有意不同**，已在此记账。
4. **m2 的第一次失败暴露了真实防御层次**：白名单 + 模式分派是两道独立护栏；只测 POST 会让分派护栏看起来"没坏"。已用 `/api/map/path` 计数补齐判别力（见上）。
5. **`PlanRoute` 的稀疏路点缺口**（M7/M8 遗留）不在本单范围，未动。

## 六 我未能核实的

- **`/tmp/m6-import-verify/test_integration` 为何从 `2348b936` 变成 `2ea3df60`** 未逐条追溯（推断是中间 M8 任务在同一路径直接以 `--store` 跑了 `ShellMain`，非本单所为；本单只用副本）。
- **自交/不闭合套索**：`finishLasso` 对 <3 点返回空、对未围住任何格返回空，这两条**只从代码路径推断**，未在 UI 上构造自交套索实测。
- **`findLassoSeed` 的 200 环上界**、**超大区（数千边界小点）的绘制/命中代价**未测。
- **HiDPI（dpr>1）**、**触摸/触控笔**、**真实用户手感**未测（沿用 M9 遗留）。
- **m3 变异轮里那条 `TypeError: Cannot read properties of undefined (reading 'meta')`** 未定位到具体行；**clean 轮 0 pageerror**，故是变异体自带的次生错误，不影响"b2 杀 m3"的结论。
- **前端护栏仍不进 Maven 门禁**：本树**没有** `exec-maven-plugin`/`node --test`（M8 T2「前端单元测试接入门禁」在此树**未见落地**）；`hexLine`/`floodFill` 等新纯函数**只有 e2e 证据**，无 Maven 级单测。这是**系统性开口项**。
- `boundaryDotAtScreen`/`lassoHexes`/`hexExists`/`lassoForTest`/`focusBoundaryForTest` 是**只读测试探针**（与既有 `hexAtScreen`/`bench*` 同型）；其中 `lassoForTest`/`focusBoundaryForTest` 当前**未被 e2e 使用**——**有意保留**以维持"已验证字节"不变（改产品字节会使本轮 5 个变异证据失效）。

## 七 实测 vs 推断

| 项 | 实测（当场跑过） | 推断（未测） |
|---|---|---|
| 套索 flood 结果 | **19 格**（12 墙 + 7 内部），与独立 flood、API 三方逐值一致 | 自交/超大套索的取值 |
| 边界小点 | **12**（== 独立边界数）；取消选中 **0** | 数千点区的绘制代价 |
| 拖点 | 增 `19→20`、删 `20→19`，各 1 条命令，逐值对拍 | 拖点跨多格的连续增删（本次用 steps=1） |
| 合并/剔除 | **22 == 并集**、**19 == 差集**，逐值 | 空并集/空差集分支只从代码推断 |
| 右键分派 | 区域编辑写类型无 PlanRoute 且 **mapPath GET=0**；单位模式 PlanRoute=1 | — |
| 不退化 | 重叠 3 从属 / 删除两步 / 淡色 0.13+0.52 / M9 44 块 / 0 pageerror | T10 旧 e2e 的 `drawEnabled`/draft 断言**必然失效**（draft 语义已变，见 §五-3） |
| 门禁 | **924**，delta 0；BugInstance 0×6 | — |
| 原档 | 副本源 md5 写前=写后=`2348b936…` | 派单写的原档路径现值 `2ea3df60…`（非本单所致） |

## 八 证据索引

```
.superpowers/sdd/2026-09-19-map-edit/r-evidence/
├── r-report.md                      ← 本文件
├── e2e/{run-e2e.sh,e2e.cjs}         ← 装置（同步 webui、起 ShellMain、真 pointer、独立 flood 复核）
├── logs/
│   ├── full-verify.log              ← clean verify（改 boundaryDotAtScreen 前）
│   ├── full-verify-final.log        ← ★ 最终字节 clean verify（924）
│   ├── clean-1/  clean-2/  clean-3/ ← 诊断轮（c3 的真根因=落点在左浮层下）
│   ├── clean-4/                     ← 首轮 ALL PASS
│   └── clean-5/                     ← ★ 最终 clean 轮 ALL PASS + 三张截图 + result.json
└── mutants/
    ├── mut-run.sh                   ← 九道门禁 + 逐字节还原自证 + 聚合 md5
    ├── runs.log                     ← 每轮 orig/mutant/classes/orig_aggregate md5（自指）
    ├── orig/                        ← 原件快照（还原源）
    └── logs/m1~m5-{e2e.log,server.log,syntax.log,e2e/result.json}
```

---

# 修正一（spec §七 统一按键模型）+ 修正二（spec §八 边框与区域边界）

- 仍纯前端两文件（`map.js`、`index.html`），**零 Java 改动**、无依赖/npm/CDN。
- 门禁：`./mvnw clean verify` 绿（rc=0）：**924 = 170/321/45/131/161/96**、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0 ⇒ **delta 0**。日志 `logs/full-verify-final2.log`。
- e2e：真档 19441 格副本 + 真 ShellMain + 真 pointer，**ALL PASS**（`logs/clean-final/result.json`）；源档 md5 写前=写后=`2348b936…`。

## 九 §七 统一按键模型（实测）

| 判据 | 实测值 |
|---|---|
| **⑨ 地形编辑左键拖动 ⇒ 零写 + 平移** | 视图 `tx 404.44→524.44、ty 808→888`（确实平移）；该阶段 **0 条写命令** |
| **⑨b 地形编辑右键拖动 ⇒ 一条 `map.SetTerrain`** | **恰 1 条**、`hexes=6`、`terrain=ocean`、`head 10→11` |
| **⑩ 所有模式左键拖动 ⇒ 无非 GET** | `[view, region, map-edit, region-edit, unit]` 五模式各 `nonGet=0` 且 `tx/ty` 均改变（打印 `LEFT_PAN_ALL_MODES`） |
| **⑩b Shift+右键逐格画 ⇒ 一条 `UpdateRegion` 改一格、不落套索** | 单击一格后 `draftHexCount=1`、`lassoActive=false`、0 条写 / 0 条 `CreateRegion`；再「合并」⇒ **1 条 `UpdateRegion`**、payload **20** == 期望 **20**（仅 +该格） |

- 按键分派落点：`map.js:1494`（`onPointerDown` 右键：`region-edit` = 套索 / Shift+右键 = `beginPaint`；`map-edit` = `beginPaint`）；左键 = 平移（唯一例外：`region-edit` 命中边界小点 ⇒ 拖点，`map.js:1527`）。
- `handleContextMenu`（`map.js:3225`）：`region-edit`/`map-edit` 右键一律 `return true`（抑制原生菜单、绝不落 `unit.PlanRoute`）；常规/单位移动编辑**不变**。
- 勾选框（旧 `#region-edit-draw` 开关）**已删**；`index.html` 文案更新为「左键=平移 / 右键=套索 / Shift+右键=逐格」。

## 十 ★ T13 冲突的查证结论（§八.2）

**先查清再改**，结论：**T13 的声称成立，但它说的是"地形块边界"层；用户看到的"每个 hex 边框"来自另一个层——区域高亮的填充缝。两者并不冲突（不是同一层）。** 证据如下：

| 层 | 实现 | 是否逐格 | 证据 |
|---|---|---|---|
| **地形块边界层**（旧 `paintBorders`，`map.js` 改前 `rebuildBordersPath`/`paintBorders`） | 逐 `block.boundaries` 环拼**一条 `Path2D`**，一次 `stroke` | **否**（合并的块环） | T13 声称正确；`l2` 探针：该层存在时 `path2dStrokes>0`。它画出的正是用户说的「**地形区之间有黑色的间隔**」（`#0d1015`） |
| **区域高亮填充层**（旧 `paintHighlights`，`radius = cellSize * 0.98`） | 每个 hex 以 `0.98×cellSize` 填充 | **不描边，但留缝** | ★ **用户看到的"区域中每一个 hex 都有边框"就是这些填充缝**：hex 满格半径是 `cellSize`，缩到 0.98 后相邻格之间留下 ~2% 的缝，露出底色 ⇒ 看起来每格都有黑边。**改回 `cellSize`（`map.js:673`）即消失** |
| **选区预览层**（旧 `paintDraft`/`paintBrush`） | 逐格 `addHexPath` + 一次 `stroke` | **是（逐格描边）** | ★ 这是**唯一真正的"逐格 stroke"**；M8-R 已把焦点区自动载入 draft 的旧行为去掉，本轮又把这两层的 `stroke` 删除（改为纯填充，`map.js:700/717`） |

⇒ **"两者不可能同时为真"由分层解释消解**：T13 说块边界（真）；用户说区域每格有边（真，但那是填充缝 + 旧的 draft 逐格描边）。**T13 的声称没有错，只是没有覆盖区域高亮层**。已在代码里就地记账（`map.js:667-673` 注释）。

## 十一 §八 边框与区域边界（实测）

| 判据 | 实测值 |
|---|---|
| **⑪ 无逐格 / 无块边界 stroke** | `__strokeStats()`（包 `CanvasRenderingContext2D.stroke`）：`sto​​kes=9, maxLineToPerStroke=20, path2dStrokes=0`（最大者=简化后的区域边界环 20 段；**无** 6×N 的逐格描边、**无** Path2D 块边界） |
| **⑫ 区域边界简化 + 仍闭合/包住** | **顶点数 378 → 43**（−88.6%）；`focusRingCount=1`、`ringCount=3`；对焦点区 20 个 hex 中心做 even‑odd 点在环内判定 ⇒ **全部 `enclosed=true`** |
| **⑬ 地形块之间无黑线** | 取相邻异地形格 `(-19,1)=low_hills` 与 `(-18,1)=plains`，在格心中点 ±8px 窗口采样 **最小亮度 171.4**（无近黑像素；阈值 >40）；对照变异 m8 恢复边框层时该值会骤降（`l3` 红） |

- 实现：`regionBoundaryRings`（`map.js:787`，逐 hex 格边，内部边成对抵消 ⇒ 边界环，多环/带洞）→ `rdpOpen`/`rdpClosed`（`map.js:874/911`，RDP）→ `setRegionOutlines`（`map.js:935`）→ `paintRegionOutlines`（`map.js:960`，一条 path 描所有环，`lineWidth=2/scale`）。RDP `eps=0.5`（`map.js:63`；经独立脚本标定：0.32 不压、1.4 会切出区域外，0.5 在压缩率与包住之间取平衡）。
- **逐格网格线全删**：旧 `paintBorders`（块边界）整个删除（连同 `bordersDirty`/`borderDraws`/`benchBorderVariants`），渲染不再调用；`paintDraft`/`paintBrush` 的逐格 `stroke` 也已删除（只填充）。
- **区域填充无缝**：`paintHighlights` 半径 `0.98·cellSize → cellSize`。
- 前后截图差异（`clean-5` 旧 vs `clean-final` 新，同状态同视口 1280×800）：**18,854 像素通道差 >30、6,757 像素 >60**，最大通道差 234 ⇒ 肉眼可见的边框/缝确已改变。

## 十二 变异 m6~m9（全部被杀，0 存活；含 m1~m5 重跑）

| m | 护栏 | 变异 | 期望红 | 实测红点 | 结果 |
|---|---|---|---|---|---|
| m6 | 地形编辑左键=平移 | 左键仍刷地形 | ⑨ 红 | `k1-terrain-left-pans-zero-write` + `k3` | **KILLED** |
| m7 | Shift+右键=逐格画 | 去掉 Shift 分支（落到套索） | ⑩b 红 | `f3` / `k4` / `k5`（+连带 `h2`） | **KILLED** |
| m8 | 无逐格/块边界描边 | 恢复旧边框层（Path2D 块边界） | ⑪+⑬ 红 | `l2-no-per-hex-or-path2d-stroke`（`path2dStrokes>0`）+ `l3`（黑线） | **KILLED** |
| m9 | 区域边界 RDP 简化 | 关掉简化（eps=0） | ⑫ 红 | `l1-outline-simplified-and-enclosing`（43→378） | **KILLED** |
| m1~m5 | （同上一节） | 重叠拒绝 / 右键误分派 / 不清焦点 / 合并=交集 / 剔除=并集 | — | 见上节 | **全部 KILLED** |

- 9/9 轮：`orig_md5 == restored_md5 == restored_classes_md5 == 140a0a9e7119c0d55a2a629662e6ff73`；`orig_aggregate_md5 == restored_aggregate_md5 == abd08474adf750453b5e1353e6a6775b`（**非空且逐字节相同**）。
- **m7 首轮曾 `e2e_rc=2`（崩溃）而非 `rc=1`**：变异令 `#region-create-submit` 在空选区下被服务端拒 ⇒ 后续 `waitForSelector('[data-region-id="m8r_btn"]')` 超时。已把该步改为**容错**（缺 `m8r_btn` ⇒ 如实记 `h2` FAIL，不崩），第二轮起 m7 稳定 `rc=1`。**这是装置缺陷、非护栏问题**，如实记账。

## 十三 与 spec / 派单的矛盾（以 spec 原文/源码为准）

1. **"每个 hex 都有边框"不是 stroke**：派单 §八.2 把它列为"逐格描边"候选之一；实测它是**区域高亮 `0.98·cellSize` 的填充缝**（另加旧 `paintDraft` 的逐格描边，M8-R 已去）。T13 的"一条 Path2D、不逐格"**没错**——它管的是**地形块边界层**。
2. **`#region-edit-draw` 是按钮不是 checkbox**（重申上一节 §五-2）。
3. **"Shift+右键逐格画能改单个格（一条 UpdateRegion）"**：§七.1 把 Shift+右键定义为"逐格画/擦"（编辑**选区**，与旧左键同语义），它本身**不发写**；要落成 `UpdateRegion` 需再点合并/保存。本单按此实现，并用「Shift+右键写一格 ⇒ 合并 ⇒ 1 条 `UpdateRegion` 恰好改一格」满足判据 ⑩b 的字面要求。
4. **左键"永不误改"的唯一例外**：`region-edit` 下左键**命中边界小点**仍是拖点（编辑手柄，非平移）——spec §七 未重定义拖点手势，保留 GSimulator 习惯；已在报告标注为**有意例外**（需精确命中手柄，非手抖可触发）。

## 十四 §修正的"我未能核实的"

- **区域边界 RDP 的包住性**：只对**本次运行的真实区域（20 格，含一个并入格）**做了 20 个 hex 中心的 even‑odd 判定（全过）。**带洞/多连通/凹形大区域**未逐一构造验证（算法按逐 hex 格边生成，理论上对洞环也成立）。
- **RDP `eps=0.5` 的普适性**：在一个半径 2 圆盘上标定（0.4/0.5/0.6 → 12/8/7 顶点，均包住；1.4 越界），**未在大陆尺度区域上标定**。
- **`maxLineToPerStroke<=300` 阈值**：实测 clean 为 20（简化后环），m8 为 Path2D（不计 lineTo）故用 `path2dStrokes` 抓；**未构造"6×N 逐格 stroke"的 ctx 版变异**去直接压这个阈值（当前靠 Path2D 计数 + m8 的像素黑线）。
- **`getImageData` 采样**在 dpr>1 下的坐标换算未测（clean 轮 dpr=1）。
- 前端护栏仍**不进 Maven 门禁**（本树无 `exec-maven-plugin`/`node --test`）——§七/§八 的判据全靠 e2e 证据级装置。

## 十五 §修正的"实测 vs 推断"

| 项 | 实测 | 推断（未测） |
|---|---|---|
| 左键平移 | 五模式 `tx/ty` 均变、`nonGet=0`；地形左键 `writes=0` | 触摸/触控笔 |
| 右键分派 | 地形右键 1 条 `SetTerrain`(6 格)；区域右键 0 `mapPath`/0 PlanRoute；Shift+右键 1 格 ⇒ 1 `UpdateRegion` | —— |
| 边界简化 | **378→43** 顶点、包住 20/20；m9 变异回 378 | 大区域/带洞的压缩率与包住 |
| 无逐格/块描边 | `path2dStrokes=0`、`maxLineTo=20`；m8 变异 ⇒ `l2/l3` 红（黑线 min 亮度骤降） | 更多地形组合的像素采样 |
| 原档 | 源副本 md5 写前=写后=`2348b936…` | —— |
