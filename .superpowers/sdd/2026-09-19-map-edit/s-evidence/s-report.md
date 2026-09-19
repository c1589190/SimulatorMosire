# M8-S 报告 —— ① 区域边界还原为精确 hex 外缘（撤销 RDP）；② 建区重名主动提示

- worktree：`.claude/worktrees/m8s`，分支 `m8/s`，基线 `e7f87ab`
- 权威依据：`docs/superpowers/specs/2026-09-19-region-editor-redesign.md` **§九**（**它推翻 §八.1 第 3 条**）
- 交付形态：**纯前端两文件**（`map.js`、`index.html`），**零 Java 改动**、无新依赖/npm/CDN。
- 结论：判据 14/15 逐条实测通过；回归项全过、**0 pageerror**；变异 m10~m12 **全部被杀（0 存活）**；
  全量门禁 **924 = 170/321/45/131/161/96，delta 0**；真档 md5 写前=写后=`2348b936…`。

## 一 改动（逐文件）

### `simos-app/src/main/resources/webui/map.js`

| 行（改后） | 改动 |
|---|---|
| `:62` | 删 `REGION_OUTLINE_RDP_EPS`，注释改为「边界不做任何简化/平滑（RDP 已撤销）」 |
| `:276-277` | `regionOutlines` 注释去「RDP 简化后」；`outlineVerticesBefore/After` → **`outlineVertexCount`**（精确顶点数） |
| `:784-787` | `regionBoundaryRings` 文档补：顶点坐标与 Java `HexVertex`（`x=u·√3/2, y=w/2`）逐值一致 ⇒ 结果 == `RegionBoundary.of(hexes)` |
| `:863-867`（删 ~72 行） | **删除 `perpDistance` / `rdpOpen` / `rdpClosed`**（RDP 整段） |
| `:869-888` | `setRegionOutlines`：**直接用 `regionBoundaryRings` 的精确环**（不再简化），只累计 `outlineVertexCount` |
| `:1689` | `debug()`：`outlineVerticesBefore/After` → `outlineVertexCount` |
| `:1875` | `host.regionNameConflict: null`（重名挂起态） |
| `:1953-1968` | ★ `fetchRegionCached` 修 **await 求值序竞态**（见 §五-1） |
| `:2074` | `reloadRegionEditHighlight` 注释改为「精确的逐 hex 外缘闭合环」 |
| `:2578-2592` | `renderRegionEditor`：显隐 `#region-name-conflict` 并写提示文案 |
| `:2693-2863` | ★ 新增 `findSameNameRegion` / `regionIdExists` / `requestCreateRegion` / `submitCreateRegionNow` / `resolveNameConflictCreateNew` / `resolveNameConflictMerge` / `cancelNameConflict`；`submitCreateRegion` 改走 `requestCreateRegion` |
| `:2897-2903` | `onLassoCommit` 改走 `requestCreateRegion`（同名 ⇒ 弹二选一，**不静默建/合并**） |
| `:3106-3108` | `wireRegionEditor` 绑 `#region-name-conflict-new/-merge/-cancel` |
| `:3829-3836` | `regionEditDebug` 暴露 `nameConflict {id,name,existingId,hexCount}`（只读测试面） |

### `simos-app/src/main/resources/webui/index.html`

- `#region-create-form` 内新增 `#region-name-conflict`（hidden）+ 文案 `#region-name-conflict-msg` + 三个按钮
  `#region-name-conflict-new`（新建同名区域，不同 id）/ `#region-name-conflict-merge`（合并到同名已有区域）/ `#region-name-conflict-cancel`。

## 二 ★★ 判据 14：渲染边界 == 权威 `Region.boundary`（撤销 RDP）

**★ 先报缺口（派单 §4 要求）：`/api/map/region/{id}` 拿不到 `boundary`。**
源码原文：`simos-app/.../gui/ApiViews.java:320` 的 `regionDetail` 只发 `{id,name,meta,hexCount,hexes}`，
**不含 `boundary`**（`overview` 的 region 条目亦同）。⇒ **判据 14 的字面形态「渲染直接用 API 给的权威边界」在不改 Java 时不可满足**；
按派单「如实报缺口，别自己改 Java」处理：**渲染改为直接用「权威 `hexes` + `RegionBoundary.of` 的等价算法」算出的精确外缘**，
并用**只读 Java 探针**（`BoundaryProbe`，`/tmp` 编译，**不改仓内 Java**）对拍真 `RegionBoundary.of`，证明二者**逐值相同**。

| 目标区域 | hexCount | 环数 | **渲染顶点数（精确）** | JS 复刻顶点数 | **Java 探针顶点数** | 渲染==JS | **渲染==Java** | 顶点格点最大残留 | 顶点到最近 hex 心距 |
|---|---|---|---|---|---|---|---|---|---|
| `test_nation` | 701 | 1 | **246** | 246 | **246** | ✅ | ✅ | `5.68e-5` | `0.99996` |
| `m8s_lasso`（套索建） | 19 | 1 | **30** | 30 | **30** | ✅ | ✅ | `4.61e-5` | `0.99997` |

- 抽样坐标（权威 `HexVertex` 标签 `u:w`，渲染侧换算所得与 Java 探针**逐值相同**）：
  - `test_nation`：`-37:-1, -37:1, -36:2, -36:4, -35:5, -34:4 …`
  - `m8s_lasso`：`-39:5, -39:7, -38:8, -38:10, -37:11, -37:13 …`
- **落在 hex 顶点格点上**：`maxLatticeResidual < 6e-5`（世界坐标、格边长=1；容差 `<1e-3` 为 PASS）。
- **不落在任何 hex 中心**：顶点到最近 hex 心距 `≈0.99996`（== 格边长 1 ⇒ 是顶点，不是中心；0.9~1.1 为 PASS）。
- 对照 **m10**（把 RDP/抽稀重新引入）：上述 4 条等式断言全红 ⇒ 判据 14 有判别力。

★ 保留（未一起回退）：**逐格网格线全删** + **边框只画区域边界、地形块之间不画**（§八 第 1/2 条）；
本轮只撤销 §八.1 第 3 条（RDP）。截图见 §四。

## 三 ★ 判据 15：建区重名主动提示（二选一，不许静默）

现状（`T4`）：服务端只拒**重复 `regionId`**，**同 name 不同 id 不拦**。本轮在**前端提交前**查左栏 `overview.regions` 的名字。

| 步骤 | 实测载荷/值 |
|---|---|
| 触发（`test_nation` 同名，草稿 3 格） | 弹 `#region-name-conflict`：`visible=true`、`existingId=test_nation`；**提交后到弹窗期间写命令 0 条**、`head` 未变（`2→2`） |
| 选 **(i) 新建同名区域** | **恰 1 条** `map.CreateRegion`，`{regionId:"m8s_dup_new", name:"test_nation", hexes:[-19,1;-19,0;-18,-1]}`；API 回读 `name="test_nation"`、`hexCount=3` ⇒ **同 name、不同 id** |
| 触发（`test_annex_target` 同名） | 弹窗 `visible=true`、`existingId=test_annex_target`；**写 0 条**、`head` 未变（`3→3`） |
| 选 **(ii) 合并到同名已有区域** | **恰 1 条** `map.UpdateRegion`，`regionId=test_annex_target`，`hexes` 计数 `204 == 并集 204`（既有 201 ∪ 新 3），**原有 201 格全在** ⇒ 非覆盖 |
| `map.UpdateRegion` 替换语义的护栏 | `payload.EqualsUnion=true`、`allOriginalHexesRetained=true` |

- **不许禁止重名**：路径 (i) 证明**重名合法**、可建两个同名区域（仅 id 不同）。
- 对照 **m11**（去掉重名检测=直接提交）：4 条 c15 断言全红 ⇒ 判据 15 有判别力。
- 对照 **m12**（选 (ii) 时误用"只取新 hex"覆盖）：`c15ii2` 红（并集逐值被破）⇒ 合并语义有判别力。

### 非 GET 清单（按阶段）
```
A-lasso-create  : ["map.CreateRegion"]
B-dup-prompt-i  : []                    ← 弹二选一时零写
B-dup-new       : ["map.CreateRegion"]
C-dup-merge     : ["map.UpdateRegion"]
R-merge         : ["map.UpdateRegion"]
R-exclude       : ["map.UpdateRegion"]
```
全部落在 `/api/command`（R8 allowlist）。左键平移阶段 `writes=0`。

### 不退化（回归，真档 + 真 pointer）
| 项 | 实测值 |
|---|---|
| 右键套索仍 1 条 `CreateRegion` | 19 格（与 e2e 独立 flood、API 三方逐值相同） |
| 小点只对 focus 区域 | `focused=12` / `unfocused=0` |
| 合并=并集（按钮路径） | **1 条 `UpdateRegion`**、`22 == 22`（逐值） |
| 剔除=差集（按钮路径） | **1 条 `UpdateRegion`**、`19 == 19`（逐值） |
| 左键仍只平移、零写 | `tx/ty` 均变、`writes=0` |
| M9 块渲染与点选 | `blockCount=44 / unmergedCount=0`；`hexAtScreen(screenPointOf(-16,0)) = {-16,0,plains}` |
| `pageerror` | **0** |

## 四 截图

```
s-evidence/logs/clean-1/
├── screenshot-exact-boundary.png           # m8s_lasso @5.5×（区域外缘折线）
├── screenshot-exact-boundary-closeup.png   # m8s_lasso @10×（贴着六边形外缘）
└── screenshot-dup-prompt.png               # 重名二选一提示（弹窗）
```

## 五 与派单 / 既有实现的分歧（以源码为准）

1. ★★ **`fetchRegionCached` 的一处真竞态（本轮实测到的 2 次 `pageerror` 根因，已修）**：
   `host.regionCache[key] = await api.mapRegion(...)` —— JS 在 `await` **之前**就求值了赋值左侧的
   `host.regionCache` **旧对象引用**；若 `await` 期间 `scheduleTargetReload` 执行了 `host.regionCache = {}`，
   结果写进旧对象，随后 `return host.regionCache[key]` 读新对象 ⇒ **`undefined`** ⇒
   `reloadRegionEditHighlight` 里 `detail.meta` 抛 `TypeError`。改为**先存局部变量、再写回、返回局部变量**。
   （这是 M8-R 遗留的潜在缺陷，本轮 e2e 的快速 focus 切换触发；非本轮新引入。）
2. **「顶点数」口径**：判据 14 要的是**精确值**（不简化）。`test_nation`(701 格)=**246** 顶点、`m8s_lasso`(19 格)=**30** 顶点。
   §八 曾记的 `378→43` 是**另一组 20 格区域 + RDP** 的旧值，与这两个区域不可比（区域不同）。
3. **`#region-name-conflict` 是新增 DOM**（非复用 `#region-delete-confirm`）：删除确认与重名确认是两套语义，分开更可断言。
4. **重名匹配口径**：`trim` 后**精确同名**（区分大小写）。派单未指定，取最保守口径。

## 六 变异表（m10~m12；九道门禁全过，0 存活）

装置 `s-evidence/mutants/mut-run.sh`：① src==orig 自证；② 聚合 md5 **非空**；③ 变异后 md5 != orig；
④ `node --check` rc=0；⑤ 同步 `target/classes/webui` 并核 md5；⑥ 跑 e2e（期望红）；⑦ 从 `orig` **逐字节还原**；
⑧ src/classes md5 复原；⑨ webui 聚合 md5 复原（`7880bd77…`）；每轮 `orig/mutant/classes/aggregate` md5 追加进 `runs.log`（形态 6 自指）。

| m | 护栏 | 变异 | 期望红 | 实测红点 | 结果 |
|---|---|---|---|---|---|
| **m10** | 判据 14（边界精确） | 渲染重新引入简化（隔点抽稀，等价 RDP 的压顶点） | 判据 14 红 | 4 条 c14 等式断言全红（`render-equals-js` / `render-equals-java` ×2 区域） | **KILLED** |
| **m11** | 判据 15（重名提示） | 去掉重名检测（直接提交） | 判据 15 红 | `c15i1` / `c15i2` / `c15ii1` / `c15ii2` 4 条红（无弹窗、直接写、head 已进） | **KILLED** |
| **m12** | 合并=并集（重名路径） | 选 (ii) 时误用"只取新 hex"覆盖 | 并集逐值红 | `c15ii2-merge-update-union-value-exact` 红 | **KILLED** |

- 3/3 轮：`orig_md5 == restored_md5 == restored_classes_md5 == 51deaab336332cbba9986a7d5a7418c8`；
  `orig_aggregate_md5 == restored_aggregate_md5 == 7880bd77f03c6fbcd23c02d8aa701fb7`（**非空且逐字节相同**）。
- **m11 首轮曾 `e2e_rc=2`（装置崩溃）**：无弹窗 ⇒ 点隐藏按钮 `page.click` 超时。
  已把两条重名路径改为**容错**（弹窗不可见 ⇒ 记 FAIL 不点），第二轮起 m11 稳定 `rc=1`。**这是装置缺陷、非护栏问题**，如实记账。

## 七 门禁

- `./mvnw -q spotless:apply`（rc=0，无 Java 改动 ⇒ 无文件被改）→ `./mvnw -o clean verify`：**BUILD SUCCESS**（rc=0）。
- **924 = 170/321/45/131/161/96**（7/7 模块全 SUCCESS）、`BugInstance size is 0` **×6**、`[ERROR]` **0** 行。
- 基线 **924 = 170/321/45/131/161/96** ⇒ **delta 0**（纯前端）。日志 `logs/full-verify-final.log`。
- **原档写前/后 md5 一致**：`2348b9365e5b107945a305d06fad8fab`（全程只用 `/tmp/m8t1-e2e-store` 副本；e2e 装置在起服务前/停服务后各核一次）。

## 八 我未能核实的

- ★ **M8-R 的 m1~m8 未重跑**（本轮只按派单跑了 m10~m12）。`map.js` 同文件被改，按纪律后关账者应重跑前者变异轮；
  但 M8-R 的 `mut-run.sh` 锚点（如 m1 的 `submitCreateRegion` 段、m9 的 `REGION_OUTLINE_RDP_EPS`）已被本轮改动**结构性删除/移动**，
  不能原样复用；**m9 因 RDP 整段删除而永久作废**。⇒ 记为**带裁定的遗留**：M8-R 的 m1~m8 证据对应的是**旧字节**，
  其中覆盖当前行为的等价性由本轮的回归项（§三"不退化"：套索/小点/合并/剔除/左键）与 m10~m12 部分补足，但**不构成逐条重跑**。
- **多环/带洞区域未在浏览器实测**：本次两个目标（`test_nation`、`m8s_lasso`）都只有 **1 条环**。
  `regionBoundaryRings` 理论支持多环（多块/带洞），但**本轮未构造带洞区域验证**（M8-R 亦未）。
- **重名匹配未测大小写/空白边界情形之外的形式**（当前 `trim` 后精确同名；大小写不同视为不同名）。
- **路径 (i) 的 id 冲突分支**（用户手填的 id 已被占用 ⇒ 自动换 `suggestRegionId()`）**只从代码推断**，未在 UI 上构造实测。
- **m12 会覆盖 `test_annex_target`（201→3）**——变异轮里确已发生（e2e 断言即红），但那是变异体行为；clean 轮未发生。
- **HiDPI（dpr>1）**、触摸/触控笔、超大区（数千顶点）的绘制/命中代价未测（沿用 M9/M8-R 遗留）。
- **前端护栏仍不进 Maven 门禁**（本树无 `exec-maven-plugin`/`node --test`）：判据 14/15 全靠 e2e 证据级装置。
- Java 探针 `BoundaryProbe` 是**只读对拍**（`/tmp` 编译，不入仓），**不证明 API 未来会暴露 `boundary`**。

## 九 实测 vs 推断

| 项 | 实测（当场跑过） | 推断（未测） |
|---|---|---|
| 判据 14 顶点数 | `test_nation=246`、`m8s_lasso=30`；渲染==JS==**Java 探针**逐值 | 带洞/多环区域 |
| 顶点落点 | 残留 `<5.7e-5`；到最近 hex 心 `≈0.99996` | dpr>1 下的坐标换算 |
| 判据 15(i) | 弹窗 + 零写；1 条 `CreateRegion`（同 name 不同 id） | 手填 id 冲突时的自动改 id |
| 判据 15(ii) | 弹窗 + 零写；1 条 `UpdateRegion`、`204==并集204`、原 201 格保留 | 空并集/已有区被删等边界 |
| 回归 | 套索 19 格、小点 `12/0`、合并 `22`、剔除 `19`、左键零写、M9 44 块、点选准、0 pageerror | 长路线/单位模式细分 |
| 门禁 | **924**、delta 0、BugInstance 0×6、ERROR 0 | —— |
| 原档 | 副本 md5 写前=写后=`2348b936…` | —— |

## 十 证据索引

```
.superpowers/sdd/2026-09-19-map-edit/s-evidence/
├── s-report.md                          ← 本文件
├── e2e/
│   ├── run-e2e.sh                       ← 装置（同步 webui、编译探针、起 ShellMain、真 pointer、核原档 md5）
│   ├── e2e.cjs                          ← 判据 14/15 + 回归（含 JS 复刻 RegionBoundary.of）
│   ├── BoundaryProbe.java               ← ★ Java 权威边界只读探针（/tmp 编译，不入仓）
│   └── probe-classes/                   ← 探针编译产物（证据）
├── logs/
│   ├── full-verify-final.log            ← clean verify（924、BugInstance 0×6、ERROR 0）
│   └── clean-1/                         ← ★ 最终 clean 轮：ALL PASS + result.json + 三张截图 + c14-debug-*.json
└── mutants/
    ├── mut-run.sh                       ← 九道门禁 + 逐字节还原自证 + 聚合 md5
    ├── runs.log                         ← 每轮 orig/mutant/classes/orig_aggregate md5（自指）
    ├── orig/                            ← 原件快照（还原源）
    └── logs/m10~m12-{e2e.log,server.log,syntax.log,e2e/result.json}
```
