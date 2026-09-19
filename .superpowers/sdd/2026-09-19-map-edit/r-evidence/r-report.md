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
