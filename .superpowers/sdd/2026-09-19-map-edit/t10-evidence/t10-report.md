# M8 T10 报告 —— 区域编辑模式 UI

> 分支 `m8/t10`（worktree `.claude/worktrees/m8t10`）；基线 `0466877`（M8 T7+T8 合并）。
> 证据全在 `.superpowers/sdd/2026-09-19-map-edit/t10-evidence/`。★ 本报告只写**实测值**；推断单列 §8。

## 0 一句话结论

区域编辑模式（`data-mode="region-edit"`）从"只放行三条命令的白名单"变成**可操作 UI**：
**新建区域**（真 pointer 拖选 ⇒ 一条 `map.CreateRegion`，**重叠不报错**）、**改已有区域 hex 集合**
（右栏选区域 ⇒ 载入 hex ⇒ 图上增/删 ⇒ 一条 `map.UpdateRegion`）、**删除**（**二次确认**后一条 `map.DeleteRegion`）；
编辑中**焦点区域正常色、其它区域淡色**（同色相低透明度，`body[data-region-focus]` + debug 可断言）；
选区预览复用 T8 刷子形制并有持久选区层。**纯前端 5 文件（零 Java、零依赖、零 npm/CDN）**，
全量门禁 **924 不变（delta 0）**，真档 19441 格 e2e **27 断言 ALL PASS**，**3 变异全杀**、原档 md5 未变、5817/5818 未动。

## 1 改动（逐文件 + 行）

| 文件 | 行数 | 内容 |
|---|---|---|
| `webui/app.js` | +17/−0 | 状态机加 `regionFocus`（与 `highlightRegions` 分开）；`setRegionFocus`；`setMode` 清 focus；`applyMode` 写 `body[data-region-focus]`；导出 `setRegionFocus`。 |
| `webui/index.html` | +45/−0 | 新增 `section.region-editor[data-modes="region-edit"]`（新建 / 绘制开关 / 加入·移除选择器 / 清空 / 载入 / 保存 / 删除 + 二次确认框）；把 T8 的「区域信息编辑」（`#region-info-status/-detail` + `#region-meta-editor`）**从 `section.map-editor` 移出**成 `section.region-info-editor[data-modes="map-edit region-edit"]` ⇒ 两模式**复用**同一 meta 编辑器（不重写）。 |
| `webui/map.js` | +599/−28 | 渲染器：持久**选区层** `draftKeys`/`setDraftHexes`/`paintDraft`、拖刷**加入/移除**双色预览（`setBrushOp`）、逐条目 alpha 高亮、`highlightAlphas` debug；宿主：`reloadRegionEditHighlight`（焦点先入 + 淡色）、`fadeRegionColor`、`commitRegionPaint`、`submitCreateRegion`/`submitUpdateRegion`/`submitDeleteRegion`（二次确认）、`onRegionFocusChanged`、`renderRegionEditor`、`wireRegionEditor`、`regionEditDebug`/`regionPaintForTest`、`paintEnabled`/`onPaintCommit` 模式分派、`renderRegionInfo` 允许 region-edit。 |
| `webui/panels.js` | +9/−2 | region-edit 下右栏点区域 ⇒ `setRegionFocus`（其它模式仍 `setHighlightRegions`）；选中态把 focus 算进去。 |
| `webui/styles.css` | +64/−0 | `.region-editor` / `.region-edit-row` / `.region-edit-group` / `.region-delete-confirm`（红框二次确认）/ `.region-info-editor`。 |

★ **未改任何 Java**、未加依赖/npm/CDN、未改 spec/plan/台账。

## 2 交互（选区 / 确认 / 淡色）

- **新建**：点「新建区域」⇒ 清 focus、清选区、自动开启绘制、`regionId` 填**建议值**（`region-1`，可改，Q3 由调用方给）。
- **选区**：`绘制选区：开` 后左键点/拖（真 pointer）⇒ 预览复用 T8 刷子（加入=白底蓝边；移除=红底红边）；松手把格并入/移出**持久选区层**（松手后仍显示）。「选区操作」下拉切换加入/移除。
- **创建**：填 id + 名称 ⇒ 点「创建区域」⇒ **一条** `map.CreateRegion`；成功 ⇒ `head` +1、右栏区域列表刷新、`/api/map/hex` 可读新从属、focus 切到新区域、地图高亮/淡色刷新。
- **改 hex**：右栏点已有区域（或「新建区域」后从 select 走）⇒ 自动`setRegionFocus` ⇒ **载入其 hex 到选区**、开启绘制；图上增/删后点「保存选区为新 hex 集合」⇒ **一条** `map.UpdateRegion{regionId,hexes}`。meta 走 T8 的 `#region-meta-editor`（region-edit 下同样可见，只改 meta 不带 hexes）。
- **删除**：点「删除目标区域」⇒ **只展开确认框，零写**；点「取消」⇒ 收起、仍零写；点「确认删除」⇒ **恰一条** `map.DeleteRegion`。
- **淡色**：编辑时**焦点区域原色（alpha 0.52）**、**其它区域淡色（同色相，alpha 0.13）**；`body[data-region-focus]` + `window.SimosMap.regionEditDebug()` 的 `focus` / `fadedRegions` / `draftHexCount` + `debug().highlightColors`/`highlightAlphas` 均可断言。★ 焦点条目**先入高亮集合**——否则与别区重叠的焦点格会被淡色盖掉（见 §7 分歧 4，这是本单 e2e 抓到的真缺陷）。

## 3 ★ 重叠正例（核心判据）

真档副本（`/tmp/m6-import-verify/test_integration` 的副本）上，用**真 pointer 拖选** `(-18,0)..(-14,0)`（这 5 格**已经**属于 `test_nation`，`(-18,0)` 还属于 `test_annex_target`），填 `t10_overlap` ⇒ 点「创建区域」。

**实测**：`CreateRegion` POST **恰 1 条**、载荷 5 格、`head 1→2`、`regions` 2→3（`["test_annex_target","test_nation","t10_overlap"]`）、重叠格 `regions` 3 项。

### 3.1 ★ 原始 JSON 片段（专用 probe，真 server，`/api/map/hex?q=-18&r=0`）

```
=== BEFORE (revision=1) ===
{"q":-18,"r":0,"terrain":"low_hills","height":0.6000000000000001,
 "regions":["test_annex_target","test_nation"],
 "terrainType":{"key":"low_hills","name":"低矮丘陵","color":"#A8B36A",...},"facets":[]}

=== POST /api/command  map.CreateRegion t10_overlap (5 hexes) ===
{"result":"committed","ref":{"branch":"main","revision":2}}

=== AFTER (revision=2) ===
{"q":-18,"r":0,"terrain":"low_hills","height":0.6000000000000001,
 "regions":["t10_overlap","test_annex_target","test_nation"],
 "terrainType":{"key":"low_hills","name":"低矮丘陵","color":"#A8B36A",...},"facets":[]}
```

★ 新建的重叠区与原有两区**同时**出现在 `regions`（字典序）——"多对多、无谁赢"的端点级证明。（probe 日志 `logs/overlap-raw-json.log`，全程只用副本，原档 md5 跑前=跑后。）

### 3.2 ★ 反向证据（方向性护栏；证明"若前端加禁止重叠的拦截，这条会红"）

- 正例断言 `b1` 要求 **恰 1 条 `CreateRegion` POST + 5 格载荷 + `head+1`**；`b2` 要求 `/api/map/hex` 列出 `t10_overlap` 与 `test_nation`（≥2）；`b3` 额外要求 `focus==t10_overlap` 且 **draft 仍 5 格**（前端**没有**把"已属别区的格"丢掉）。
- **变异 m1** 正是给前端加"与已有区域相交就拒绝"的拦截 ⇒ e2e **8 条红**，含 **`b1-overlap-one-command-head-plus-one`**（POST 数=0、head 不变、新区域不存在）与 `b2`/`b3`（`logs/m1-device.log`）。⇒ 该正例确实在守"重叠允许"这条裁定；**谁逆着加校验，它必红**。

## 4 改 hex / 删除的前后值（实测）

**改 hex（`test_annex_target`，201 格）**：右栏点该区 ⇒ focus 载入 201 格 ⇒ 真 pointer「移除」`(-18,0)..(-16,0)`（选区 201→198）+「加入」`(-2,0)..(0,0)`（198→201）⇒ 保存。

| 指标 | 值 |
|---|---|
| `UpdateRegion` POST 条数 | **1**（`payload.regionId=test_annex_target`，`hexes` **201**） |
| `hexCount` 前后 | **201 → 201**（删 3 加 3） |
| 移除格 `-18,0`（`H_RM`） | 保存后**不在**集合（`afterContainsRm=false`） |
| 加入格 `-2,0`（`H_ADD`） | 保存后**在**集合（`afterContainsAdd=true`） |
| `head` | **3 → 4** |

**删除（`t10_overlap`，5 格）**：

| 阶段 | 实测 |
|---|---|
| 点「删除目标区域」（未确认） | 确认框**可见**、`deleteArmed=true`、`DeleteRegion` POST **0 条** |
| 点「取消」 | 确认框**隐藏**、focus 仍 `t10_overlap`、POST 仍 **0 条** |
| 点「确认删除」 | `DeleteRegion` POST **恰 1 条**、`head 2→3`、`regions` 3→2、`(-18,0).regions` 回到 `["test_annex_target","test_nation"]`、focus→null |

## 5 验证实测值

### 5.1 门禁（`./mvnw clean verify`）

**rc=0、924 条 = 170/321/45/131/161/96、7/7 reactor 项、`BugInstance size is 0` ×6、`[ERROR]` 0 行**（`logs/full-verify.log`）。与派单基线 **924 逐模块相同 ⇒ delta 0**（纯前端，未动任何 Java）。★ 跑前先 `spotless:apply`（无 Java 改动，仅走流程）。

### 5.2 Playwright e2e（真档副本 + 真 `ShellMain`，端口 463xx；`logs/clean-after-mutants-e2e.log`）

**27 断言 ALL PASS**、0 `pageerror`。要点：

| 组 | 断言 | 实测 |
|---|---|---|
| a | `a-region-edit-mode` | `data-mode=region-edit`、当前模式「区域编辑」、右栏/编辑面板/meta 面板可见、白名单 create/update/delete=true、SetTerrain=false |
| b0 | `pre-m9-blocks-baseline` / `b0-*` | 基线 `blockCount=44`、`unmergedCount=0`；新建清空 draft、draw 开；拖动中 `painting=true brushHexCount=3`、松手 draft=5 |
| b1 | `b1-overlap-one-command-head-plus-one` | 1 条 CreateRegion、5 格、`head 1→2`、regions 2→3 |
| b2 | `b2-overlap-allows-multi-membership` | `(-18,0).regions=[t10_overlap,test_annex_target,test_nation]` |
| b3 | `b3-overlap-draft-kept` | focus=t10_overlap、draft=5（前端未丢重叠格） |
| c | `c1-fade-focus-set` / `c2-fade-colors-distinct` | focus=t10_overlap、`body[data-region-focus]=t10_overlap`、faded=[annex,nation]；`colors=[#00e5ff,#dbadcc,#ceaecf]`、`alphas=[0.13,0.52]` |
| e | `e1-delete-unconfirmed-zero-write` / `e2-delete-confirmed-one-command` | §4 |
| d0 | `d0-focus-loads-hexes` / `d0b-fade-annex-focus` | 载入 201 格；清空后 focus=annex、faded=[nation]、alphas=[0.13,0.52] |
| d1/d2 | `d1-update-draft-add-remove` / `d2-update-one-command-before-after` | §4 |
| f | `f1-duplicate-id-rejected-reason` | `区域已存在: test_nation`（原文） |
| f | `f2-empty-selection-rejected-reason` | `hexes 不得为空：一个区域至少要有一格`（原文；draft=0 也真发到服务端） |
| f | `f3-offmap-rejected-reason` | `hex 不在图上: 9999_9999`、`kind=rejected` |
| f | `f4-negatives-no-revision` | `head` 不变（4→4）、时间轴节点数不变（4→4） |
| g1 | `g1-region-edit-whitelist` | 本阶段 6 条非 GET **全部** `/api/command`，type ⊆ {CreateRegion,UpdateRegion,DeleteRegion} |
| g2 | `g2-map-edit-brush-not-regressed` | 地图编辑拖 5 格仍**1 条** `map.SetTerrain`（载荷 5 格 ocean） |
| g3 | `g3-blocks-not-regressed` | 写后 `blockCount>0`、`unmergedCount=0`、点 `(-18,0)` 选中正确 |
| g4 | `g4-route-not-regressed` | 右键 `unit.PlanRoute`×1、左键取消 `unit.CancelRoute`×1、`routeCount 1→0` |
| g5 | `g5-no-pageerror` | `[]` |

非 GET 全过程（`NONGET`）：`CreateRegion`(正例 1 + 负例 3) / `DeleteRegion` 1 / `UpdateRegion` 1 / `SetTerrain` 1 / `CreateUnit` / `PlanRoute` / `CancelRoute`。**region-edit 阶段零越界写。**

### 5.3 原档保护

- 原档 `/tmp/m6-import-verify/test_integration/simos.db` 跑前=跑后=`2348b9365e5b107945a305d06fad8fab`（全程只用副本）。
- `5817`/`5818` 全程未 kill（`ss -ltnp` 实测仍在监听）；e2e 自选 463xx 端口、自起自收；无残留服务（`ss -ltnp` 无 463xx）。

## 6 变异（3 轮 × 装置自证 + 真 e2e，全杀；`mutants/logs/m*-device.log`）

装置 `mutants/mut-run.sh`：每一轮先断言 `src == orig`（**装置自证**，防陈旧快照把工作树写回旧版）→ 变异体字节**不同** → `node --check`（语法不过则本轮作废）→ `run-e2e.sh` 起服务前把 `src→target/classes/webui` **逐字节同步并自证**（防跑旧字节）→ 跑 e2e → `cp` 逐字节还原 src+classes 并比 md5 回 orig → **断言红点里含指定的那条断言**（红在对的地方才算杀）。日志自指 `orig_md5/mutant_md5/restored_md5`。

| m | 护栏 | 变异 | 期望红 | 实测红点 | 结论 |
|---|---|---|---|---|---|
| m1 | ★★ **重叠允许** | 前端 `submitCreateRegion` 加"与已有区域相交就拒绝" | 重叠正例 | **8 FAIL**，含 `b1-...`（POST 0 条）`b2`/`b3` | **KILLED** |
| m2 | 删除要确认 | `#region-delete` 直接发 `DeleteRegion`（去掉二次确认） | 未确认零写 | **2 FAIL** `e1-delete-unconfirmed-zero-write`,`e2-...` | **KILLED** |
| m3 | 淡色聚焦 | 高亮所有区域**同色同 alpha**（去掉淡色分档） | 淡色断言 | **1 FAIL** `c2-fade-colors-distinct` | **KILLED** |

逐轮门禁：`syntax_rc=0`、`e2e_rc=1`、`restored_md5==restored_classes_md5==orig_md5`。存活项：**无**。
（m1 下 c2/e1 等亦红是**级联**：创建被拦 ⇒ 无新区域可聚焦/删除；红点仍含预期的 `b1`。）

## 7 与派单/spec 的分歧（以源码/spec 原文为准）

1. ★ **不改 Java 做到了**：本单**零 Java 改动**，全量 delta 0 ⇒ 派单的"不改 Java"按字面满足（T7 曾因改测试断言偏离，本单无此问题）。
2. **空选区的"服务端 reason"**：为让"空选区 ⇒ 显示**服务端** reason 原文"成立，`submitCreateRegion` **不做**"选区为空"的前端拦截，而是把 `hexes:[]` 真发到服务端，由 `RegionOperations` 以 `hexes 不得为空：一个区域至少要有一格` 拒绝并原文显示（f2 实测）。派单把它们并列为"负例"，此处明确"空选区走服务端"这一选择。
3. **图外负例**：UI 无法选中图外格（pointer 只命中图内），故 f3 用 `app.writeCommand("map.CreateRegion",{...,hexes:[{q:9999,r:9999}]})` **直调同一写入口**取证；显示层没有对应表单路径（如实记）。
4. ★ **本单 e2e 抓到并当场修掉的真缺陷**：焦点区域（`t10_overlap` ⊂ `test_nation`）的高亮被**更早入集合的淡色区域**按"先者胜"盖掉（`alphas` 只剩 `[0.13]`、无 `0.52`）⇒ 修法：`reloadRegionEditHighlight` **把焦点区域先入**高亮集合。这不是笔误，是"重叠 + 先者胜"两处正确语义的**组合缺口**。
5. ★ **淡色从"同 alpha、色相变浅"改为"同色相 + alpha 分档"**：第一版只有颜色变浅，视觉 QA 判"看不出淡色"；改为 `REGION_FOCUS_ALPHA=0.52` / `REGION_FADE_ALPHA=0.13` 后视觉 QA 明确确认"强/淡两层"。`setHighlightHexes` 条目因此支持可选 `alpha`（缺省仍 `HIGHLIGHT_ALPHA=0.42`，**区域查看模式行为不变**）。
6. **meta 编辑器位置**：从 `section.map-editor` 移到独立的 `section.region-info-editor[data-modes="map-edit region-edit"]`，两模式复用同一 DOM（派单 MUST DO #2「复用别重写」）；`WebuiAssetsTest` 不依赖其位置，门禁 924 不变。
7. **`fadedRegions` 的排序**：按 `/api/map/overview` 的 regions 顺序（字典序），非独立排序；断言用 `indexOf`（集合语义），不用逐位相等。

## 8 实测 vs 推断

| 项 | 类型 | 依据 |
|---|---|---|
| 全量 924 绿 / BugInstance 0×6 / ERROR 0 / delta 0 | **实测** | `logs/full-verify.log` |
| 重叠正例：1 条 CreateRegion / head+1 / regions 3 项 / 原始 JSON | **实测** | `logs/clean-after-mutants-e2e.log` + `logs/overlap-raw-json.log` |
| 改 hex 前后 201→201、增删格逐值、1 条 UpdateRegion | **实测** | 同 e2e（result.json） |
| 删除未确认零写 / 取消零写 / 确认 1 条 | **实测** | 同 e2e |
| 淡色 focus/faded 集合 + 颜色/alpha 双档 + 截图 | **实测** | 同 e2e + 视觉 QA（三张截图） |
| 负例三条 reason 原文 + 不留 revision | **实测** | 同 e2e（f1/f2/f3/f4） |
| 不退化：白名单 / T8 拖刷 / M9 块 / 路线 / 0 pageerror | **实测** | 同 e2e（g1~g5） |
| 原档 md5 不变、5817/5818 未动、无残留服务 | **实测** | `md5sum` + `ss -ltnp` |
| 3 变异全杀 + 装置自证（含 classes 同步自证） | **实测** | `mutants/logs/m*-device.log` + 各 `m*-e2e.log` |
| 触摸/触控笔、dpr>1 的淡色像素 | **未测** | 仅 mouse pointer、本机 dpr=1 |
| >2 个从属的浏览器渲染 | **本轮实测到 3 从属**（`(-18,0)` 显示「3 个区域」，见截图 2） | 但未做 4+ |
| `regionFocus` 持久化 / 页面刷新恢复 | **推断不可能**（纯前端内存态） | 无 localStorage 读写点 |

## 9 我未能核实的

1. **真档上未做 4 个及以上区域的浏览器渲染**：本轮最多 3 从属（1 新建 + 2 原有）。
2. **触摸/触控笔拖选** 与 **HiDPI（dpr>1）** 下的淡色像素采样未测。
3. **`UpdateRegion` 改 name 无入口**（spec 载荷不含 name，T4 已记），本单未加。
4. **409 冲突路径**（写后游标被别处推进）未在 e2e 复现；本单只测 422。
5. **前端护栏仍不进 Maven 门禁**（T2 未做，spec §五 开口项）——本单的 e2e 与变异仍是**证据级**。
6. **`regionPaintForTest`/`regionEditDebug` 是留在生产 JS 的断言钩子**（与既有 `bench*`/`commitPaintForTest` 同形），未加编译期隔离。
7. **probe 脚本 `setsid` 起服务的清理**：probe 自身 trap 收尾、端口实测已释放；但若脚本被强杀，`/tmp/m8t10-overlap-probe` 目录可能残留（非仓内产物）。

## 10 证据索引

```
t10-evidence/
  t10-report.md                         # 本报告
  e2e/e2e.cjs                           # Playwright e2e（27 断言）
  e2e/run-e2e.sh                        # 起真 ShellMain（真档副本；起服务前 src→classes 逐字节同步+自证）
  logs/full-verify.log                  # 全量 924 绿（最终）
  logs/clean-after-mutants-e2e.log      # 变异后干净轮全文（27 PASS / 0 FAIL）
  logs/clean-after-mutants/             # result.json + screenshot-region-edit-focus-faded.png
  logs/clean-final/                     # 干净轮：result.json + focus-faded/focus/selection 三截图
  logs/overlap-raw-json.log             # ★ 重叠正例原始 JSON（BEFORE / committed / AFTER）
  logs/server-clean-*.log               # 各轮真 ShellMain 服务端日志
  mutants/mut-run.sh                    # 变异装置（自证 + 指定红点 + src/classes 双还原）
  mutants/orig/{modes,app,map}.js       # 逐字节基线（map.js md5 4950d1b8…）
  mutants/logs/m{1,2,3}-device.log      # 3 轮变异：自指 md5 + result_line + KILLED
  mutants/logs/m{1,2,3}-e2e.log         # 各轮 e2e 全文
  mutants/logs/m{1,2,3}-syntax.log      # node --check
```

## 11 截图

| 路径 | 内容 |
|---|---|
| `logs/clean-final/screenshot-region-edit-selection.png` | 新建拖选中（focus 未选，全部淡色；3 格刷子预览可见） |
| `logs/clean-final/screenshot-region-edit-focus.png` | 重叠区域创建后（focus=t10_overlap，5 格选区 + 右栏新区域高亮） |
| `logs/clean-final/screenshot-region-edit-focus-faded.png` | ★ focus=test_annex_target 且清空选区 ⇒ **强色焦点区 vs 淡色 test_nation** 两层清晰可辨（视觉 QA 确认） |
| `logs/clean-after-mutants/screenshot-region-edit-focus-faded.png` | 同上的变异后干净轮复现 |
