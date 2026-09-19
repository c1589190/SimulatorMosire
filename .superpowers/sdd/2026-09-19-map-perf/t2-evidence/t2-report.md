# M9 档 0 止血（T2 去重 / T4 render 隔离+修复 / T5 记忆化+核实）—— t2-report.md

> **性质：纯前端（`webui/**` 六文件）。零 Java 改动。** 真档 `/tmp/m6-import-verify/test_integration`（19441 hex，只读，`simos.db` md5 全程未变）、真 Chromium（Playwright 1.63.0）、视口 1280×800。
> **一句话**：首屏可交互 **11.3s → 0.38~0.51s（≈25×）**；`render()` 适配比例单帧 **5010ms → 1.9ms（≈2600×）**；pan 3s 帧数 **2 → 165~167**；启动 overview/units/state **3/4/3 → 1/1/1**，启动字节 **4.34MB → 2.26MB**。瓶颈**已隔离并证伪/证实**：**巨路径（19441 子路径一次 fill/stroke）是主因**，分块后地形 1405→23.5ms、边框 3594→27.1ms。

---

## 1. 环境与装置

| 项 | 值 |
|---|---|
| 服务 | `java -cp <worktree webui 资源优先 + 主树 6 模块 target/classes + /tmp/t9b-cp.txt> io.mosire.simos.app.ShellMain --store /tmp/m6-import-verify/test_integration --gui-port 45911 --mcp-port 45915 --approval-port 45913` |
| 数据 | `/tmp/m6-import-verify/test_integration`（M6 导入真档，19441 hex，**不给 `--demo`**） |
| 浏览器 | `/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome`（headless） |
| 视口 | **1280×800**，deviceScaleFactor=1（与 T1 逐项同口径） |
| 端口 | 45911（真档）/ 45931~45961（M7 回归 demo 档）；**5817/5818 全程未被触碰** |
| 装置 | `harness/measure.cjs`（T1 原样复用）、`harness/micro.cjs`（隔离微基准）、`harness/t2-e2e.cjs`（断言）、`harness/height-probe.cjs`、`harness/run-mutations.py` |
| 写保护 | `simos.db` md5 跑前=跑后 = `2348b9365e5b107945a305d06fad8fab`（与 T1 相同，**未写**） |
| 账外自证 | 全量 verify 前已停自起服务；产物全部在 `t2-evidence/`（未放仓根） |

★ **一次只跑一个 Maven**：测量与变异全程无 Maven 并发；`clean verify` 在测量全部收工后单独跑。

---

## 2. (A) 去重启动请求

### 改动
- `api.js`：新增**共享记忆化取数层** `cachedGet(path,target)`（键 = `withTarget(path,target)` 的**完整 URL**；并发同键合流；失败不缓存；FIFO 上限 64）；导出 `cachedMapOverview` / `cachedUnits`。
- `api.js`：`/api/state` 合流 —— 在途合流 + 250ms TTL + **epoch**（`invalidateState()` 使写后既不读旧缓存、也不搭上写前在途请求）。
- 消费者统一改走共享层：`map.js`（`reloadOverview`/`reloadUnits`）、`panels.js`（`loadOverview`/`renderHex`）、`unitTree.js`（`refresh`）。**`panels.js` 原自带一份 overview 缓存已删除**（那份与 map.js 不共享，正是重复来源）。
- 写命令（`submitCommand`/`advance`/`fork`）开头 `invalidateState()`；`app.refreshState(force)` 支持强制；`timeline.refresh(force)` 在 advance/fork 后强制。

### 断言实测（`micro/e2e-clean3.json`，真档首屏窗口）
| 断言 | 结果 |
|---|---|
| `a-overview-no-target-once` | PASS `count=1` |
| `a-overview-rev1-once` | PASS `count=1` |
| `a-units-no-target-once` | PASS `count=1` |
| `a-units-rev1-once` | PASS `count=1` |
| `a-state-startup-once` | PASS `count=1` |
| `a-memo-no-new-request` | PASS `overview+0 units+0`（同 target 再取不发请求） |
| `a-target-invalidates` | PASS `new-revision requests=1`（`main#1→main#2` 必发新请求）|

### 变异表（`micro/mutations.json`，7/7 killed）
| id | 变异 | 实际红点 | 判定 |
|---|---|---|---|
| A-m1-remove-memo | `cachedGet` 不再命中缓存 | `a-memo-no-new-request` + `a-overview-rev1-once` + `a-units-rev1-once` | ✅ 杀 |
| A-m2-target-not-in-key | 键退化为 `path`（忽略 target） | `a-target-invalidates` 等 6 条 | ✅ 杀 |

---

## 3. (B) ★★ `render()` 隔离 + 修复

### 3.1 隔离表（微基准，**先隔离后修**；真档适配比例 scale=0.0967，19441 格全可见）

`micro/isolation-b.json`（**修前 = 巨路径 + 边框恒绘 + 无缓存**，`benchStages(7)` p50）：

| 段 | p50 (ms) | 占比 |
|---|---:|---:|
| `visibleHexes()` | 0.3 | 0.0% |
| 地形（路径构建 + `fill()`，巨路径） | **1410.5** | **28.2%** |
| 区域填充 highlights | 2.5 | 0.1% |
| **边框（路径构建 + `stroke()`，巨路径）** | **3582.8** | **71.8%** |
| `drawCities` | 0.0 | 0.0% |
| `drawUnits` | 0.0 | 0.0% |
| 合计（`render()`） | 4990.0 | 100% |

⇒ **`visibleHexes()` 的 O(19441) 扫描不是瓶颈（0.3ms）**；成本全在**两条巨型路径**上，且**边框 > 地形**。

### 3.2 「巨路径 vs 分块 Path2D vs 逐格」实验（同一数据集，`benchStages` 同源函数）

`micro/final.json`（p50，单位 ms）：

| 层 | 巨路径 | 分块 256 | 分块 64 | 分块 32 | 逐格 |
|---|---:|---:|---:|---:|---:|
| 地形 fill | 1405.4 | 65.0 | 30.4 | **23.8** | 44.9（max 695，有离群） |
| 边框 stroke | 3593.8 | 64.2 | **27.1** | — | 44.1 |

**结论：巨路径是主因，已证实。** 同色所有可见格塞进**一条含 19441 个子路径**的 path 后一次 `fill()`/`stroke()`，在 Blink/Skia 下光栅化病态：地形分块后 **60×**、边框分块后 **133×**。逐格 p50 尚可但**不稳**（地形逐格曾出现 695ms 离群），故**生产用分块**。分块大小扫描（地形）：32→23.8 / 64→30.4 / 128→40.8 / 256→65.0 / 512→110.0 ⇒ **取 32**（边框取 64）。

### 3.3 修复（三件事）
1. **分块 Path2D**：地形 `terrainChunk=32`、边框 `borderChunk=64`；每块 `ctx.fill(path2d)`/`stroke(path2d)`（`map.js` `paintTerrain`/`paintBordersChunked`）。
2. **边框 LOD**：`cellSize*view.scale < borderMinScreenPx(4)` 时跳过边框层。适配比例下 `borderScreenPx=3.29 < 4` ⇒ 一小遍路径直接省掉（且此时边框是纯噪声）。
3. **地形离屏位图缓存**：`document.createElement("canvas")` 作隐藏位图（视口 + 四周 256px margin，dpr 感知）；**pan 时 `drawImage` blit**；**数据变 / resize / zoom 停（180ms 防抖）才重建**；缩放中先拉伸旧位图降级。

### 3.4 断言实测（`micro/e2e-clean3.json`）
| 断言 | 结果 |
|---|---|
| `b-render-warm-p50-lt33` | PASS `p50=2.5`（生产默认，缓存命中=blit） |
| `b-render-cold-p50-lt33` | PASS `p50=27.6`（生产默认、关缓存=真冷重建；`final.json` 复测 25.7，max 27.1） |
| `b-terrain-giant-path-is-cause` | PASS `giant=1414 chunked=28.8`（>5×） |
| `b-border-giant-path-is-cause` | PASS `giant=3586 chunked=28.1`（>5×） |
| `b-border-lod-skipped-at-fit` | PASS `draws 0→0 screenPx=3.29` |
| `b-pan-does-not-rebuild` | PASS `rebuilds 2→2 blits 20→22`（+50px pan 只 blit） |
| `b-resize-rebuilds-terrain` | PASS `rebuilds 2→3` |

### 3.5 变异表（7/7 killed）
| id | 变异 | 期望红点 | 实际红点 | 判定 |
|---|---|---|---|---|
| B-m1-pan-rebuilds | pan 也重建位图 | `b-pan-does-not-rebuild` | `b-pan-does-not-rebuild` + `b-render-warm-p50-lt33` | ✅ |
| B-m2-resize-no-rebuild | resize 不置 dirty | `b-resize-rebuilds-terrain` | `b-resize-rebuilds-terrain` | ✅ |
| B-m3-no-border-lod | LOD 阈值改 0 | `b-border-lod-skipped-at-fit` | `b-border-lod-skipped-at-fit` | ✅ |
| B-m4-giant-reverted | 默认 `terrainMode="giant"` | `b-render-cold-p50-lt33` | `b-render-cold-p50-lt33` | ✅ |

★ **B-m4 第一版存活**：当时冷重建断言**自己把 `terrainMode` 强设为 `chunked`**，因此根本没测生产的默认路径 —— 变异体杀不掉 = 装饰。已修为「只关缓存、**不动 terrainMode**」，重跑后当场杀红。这是本任务自己踩中的判别力缺陷，如实记录。

---

## 4. (C) `updateLegend` 记忆化 + `height` 核实

### 4.1 记忆化
`map.js` 新增 `terrainCounts()`：以 `overview` 对象引用为键缓存地形计数（`legendScans` 计数）。`setData`（overview 变）才重扫；`setUnits`（只有单位数变）复用。**修前 `setData` 与 `setUnits` 各扫 19441 格一次，修后每次 overview 只扫一次。**

**断言**（旧页 `/map` 点 reload 同时触发 setData+setUnits）：
`c-legend-memoized-scan-once-per-overview` PASS `scans 1→2`（期望 +1）。

**变异**：`C-m1-legend-no-memo`（恒重扫）⇒ 该断言红（`scans` 变 1→4）✅ 杀。

### 4.2 ★ `height` 是否被渲染/交互使用 —— **结论：不用**
- **grep**：`overview` 的 `height` 只被 `map.js:286` 存进 `hexes[].height`，**全仓无任何读取点**；`/api/map/hex` 的 `height` 才是被用的（`panels.js:270`、旧页 `map.js:1820`）——两者是**不同端点**。
- **运行时探针**（`harness/height-probe.cjs`，`addInitScript` 包 `fetch`，把 overview 每个 hex 的 `height` 删掉）：`STRIP=1` ⇒ **剥掉 58323 个字段（3 次 overview）**，`ready=true`、`pageErrors=0`、点选正常（`selLen=60`、`leftStatus=q=0, r=0 · main@1`）、legend 与对照**逐字相同**；`STRIP=0` 对照亦同。⇒ **overview.height 是死重量**。
- **载荷**：19441 × `"height":<num>` = 322,476 B，加 19,441 个逗号 ≈ **341,917 B ≈ 342 KB ≈ 该响应（1,044,970 B）的 32.7%**。

### 4.3 ⚠️ 未「停发」——与任务书 MUST NOT 的冲突（以源码/MUST NOT 为准）
任务书 (C)2 说「若不用 ⇒ overview 停发 height」；但 **MUST NOT 明写「不改 Java（T3 服务端缓存是另一单）」**，而「停发」唯一落点是 **Java**：`simos-app/.../gui/ApiViews.java:162` `hex.put("height", entry.getValue().height());`（`mapOverview` 内）。它属服务端面 ⇒ **本单不动**。**欠账（归 T3）**：删该行即可（`/api/map/hex` 的 `ApiViews.java:248` **必须保留**），19441 真档可再降 ~342 KB/次。

### 4.4 关于 (C)3 变异
因 4.3 未停发，(C)3 的「某渲染分支仍读 height ⇒ 停发后崩」**不适用于本单出货代码**（没有「停发」这个动作）。探针本身已证明「删了也不崩」，即该变异在"若停发"的世界里只会**不红**（因为无分支读 height）——这与结论「不用」一致。**如实记，不伪造红点。**

---

## 5. T1 → 现在 对照表（同装置、同真档、同视口）

| 指标 | T1（run1 / run2） | old1（本会话，修前） | **现在（new1 / new2）** |
|---|---:|---:|---:|
| `firstInteractiveMs` | 10806 / 10757 | 11260 | **509 / 377** |
| `render()` p50（适配比例） | 4998 / 5004 | 5010 | **1.8 / 1.6** |
| `render()` p50（scale=3） | 0.2 / 0.2 | 0.2 | 0.2 / 0.2 |
| pan 窗口帧数（3s） | 2 / 2 | 2 | **167 / 165** |
| pan `maxGapMs` | 5048 / 5012 | 5037 | **55.6 / 78.6** |
| pan p95 gap | — | 5037 | **33.6 / 34.5** |
| zoom 窗口帧数（2s） | 4 / 4 | 4 | **98 / 98** |
| zoom `maxGapMs` | 5084 / 5109 | 4639 | **42 / 40** |
| `overview` 启动次数 | **3** / 3 | 4 | **1 / 1** |
| `units` 启动次数 | **4** / 4 | 5 | **1 / 1** |
| `apiBytes` | 3,137,534 / 3,137,670 | 4,182,516 | **2,090,800 / 2,090,800** |
| 启动窗口（<2s）字节 | 3,291,638（逐字节相同） | 4,336,484 | **2,261,555 / 2,261,555** |
| `pageerror` | 0 / 0 | 0 | **0 / 0** |

★ T1 的 overview 3 次含竞态；旧码本会话跑到 4 次（`?branch=main` 2 + `&revision=1` 2）。**修后每个 URL×target 恒为 1**，不再随竞态漂移。
★ 首屏长任务：修前两段各 ≈5s（0.53s→10.8s 占满主线程）；**修后 `longTasks` 只有 ~65~96ms**（最大 96ms）。

---

## 6. 门禁（全量 `./mvnw clean verify`，rc=0）

- 逐模块：**170 / 256 / 45 / 131 / 154 / 88**（util/map/social/unit/core/app），`[ERROR]` **0** 行，`BugInstance size is 0` **×6**。
- ★ **doc'd 基线「843」是笔误**：`170+256+45+131+154+88 = 844`；T1 自己的 `t1-evidence/merged-full-verify.log` 逐模块也是这六个值（和=844）。**本单 delta = 0（向量逐模块相同）**，`WebuiAssetsTest` 8/8、`AppWritePathGuardTest` 2/2 全绿。
- `spotless:apply` rc=0（纯前端，未动 Java 文件）。

---

## 7. M7 系列既有 e2e（不退化）

| suite | 覆盖 | 结果 |
|---|---|---|
| **m7g** | 全屏底图 / 浮层不穿透 / 右键路线 / 左键取消移动 / tick 分组 / 推进 N / 分岔 / R8 allowlist | **PASS（rc=0）**，含 `g-route-created`、`g-leftclick2-cancels-movement`、`g-advance-3-tick`、`g-route-no-new-node`、`g-route-detail-plus-one`、`h-nonget-allowlist` |
| **m7f** | tick 分组 + 推进 N | **PASS（rc=0）** |
| m7c / m7e | 左键不瞬移 / 交互表 | rc=1，但**与 f2a348e 旧前端逐条相同**（见下） |

**非 GET 清单（m7g `h-nonget-allowlist`，已打印）**：`["/api/command","/api/command","/api/command","/api/advance"]`，`violations: []` ⇒ **⊆ {`/api/command`,`/api/advance`,`/api/fork`}**。measure 只读轮询的 nonGET = `[]`。

★ **m7c/m7e 的失败是既有失败，非本单引入**（对照实验）：把前端换成 **f2a348e 旧字节**（`/tmp/m9t2/old-res`，`curl /map.js` 实测返回 `36c15adb…` 旧 md5）跑同一脚本，**红点完全相同**：
- m7c：`c-timeline-node-plus-1`（`nodesC=null`，等待超时）；
- m7e：`a-selection-cleared` / `b-first-click-no-write` / `b-node-plus-1` / `d-node-plus-1`。
且 **m7g 已用同语义的 `g-*` 断言覆盖这些行为并全 PASS**（含右键空白清选中、左键取消移动、下路线不多节点）。⇒ 本单**零新增失败**。

---

## 8. 实测 vs 推断

| # | 命题 | 实测？ | 依据 |
|---|---|---|---|
| 1 | `visibleHexes()` 0.3ms、地形 1410ms、边框 3583ms（适配比例，修前） | **实测** | `micro/isolation-b.json` 的 `benchStages(7)` p50 |
| 2 | **巨路径是主因**（地形 / 边框） | **实测** | 同源函数三档对比：地形 1405→23.8、边框 3594→27.1 |
| 3 | 分块最优区间 32~64 | **实测** | chunk sweep：32:23.8 / 64:30.4 / 128:40.8 / 256:65 / 512:110 |
| 4 | 修后 render warm 1.9ms / cold 25.7ms | **实测** | `micro/final.json` |
| 5 | 边框 LOD 适配比例下不绘（0→0） | **实测** | e2e `b-border-lod-skipped-at-fit` |
| 6 | pan 只 blit 不重建；resize 必重建 | **实测** | e2e `b-pan-does-not-rebuild` / `b-resize-rebuilds-terrain` + `debug` 计数 |
| 7 | 每 URL×target 恰好 1 次；target 变必失效 | **实测** | e2e A 组 + `measure.cjs` 请求清单（1/1/1/1/1） |
| 8 | overview `height` 不用 | **实测** | 运行时探针剥 58323 字段，0 pageerror、功能不变 |
| 9 | height 占 ~342KB | **实测（字节计数）** | 对导入档 JSON 计数（322,476 + 19,441 逗号） |
| 10 | `render()` 下降到 <33ms | **实测** | warm 1.9ms、cold 25.7ms（max 27.1） |
| 11 | 巨路径在 **Skia 内部**为何病态 | **未证实（推断）** | 只测了外部耗时；未做 Skia trace/反汇编 |
| 12 | 「删 height 后服务器省 342KB」 | **推断** | 由响应对拍 + 探针推得；**未真改 Java 后测传输** |
| 13 | M7c/m7e 既有失败的**根因** | **未诊断** | 只证「old==new 同红」，未定位其触发条件 |
| 14 | 其他浏览器 / HiDPI / 其他机器 | **未测** | 仅 headless Chromium dpr=1 |

---

## 9. 我未能核实的

1. **未在 5817/5818 实例上测**（按纪律避开）；本报告只对 45911（真档）与 45931~45961（demo）自起实例成立。
2. **未做 Skia 级根因**：只证明「巨路径病态」，未解释 Blink/Skia 内部机理。
3. **未真跑「停发 height 的服务端」**：探针是浏览器侧剥字段；Java 未改（MUST NOT）。
4. **HiDPI（dpr>1）**：离屏位图与 dpr 的乘积、blit 质量/耗时**未测**（本机 dpr=1）。
5. **位图内存未量**：`(cssW+512)×(cssH+512)×dpr²` 约 1.8MB（1280×800×1），未实测峰值。
6. **`terrainSettleTimer` 边界**：缩放停后 180ms 重建；未测「极慢缩放 vs 重建」的交互退化。
7. **FIFO 淘汰**：缓存上限 64、按插入序淘汰（非 LRU）；未测长期写多 revision 的内存行为。
8. **M7c/m7e 失败根因**未定位（只证非本单引入）。
9. **未测 >1 分支 / 有单位的真档**（本档 units=0；M7 回归用的是 `--demo` 小档）。
10. 前端护栏仍**不进 Maven 门禁**（`harness/*.cjs` 是证据级；M8 T2 的前端 CI 未落地）——本单未改变这一系统性开口项。

---

## 10. 与任务书矛盾处（以源码/MUST NOT 为准）

1. **「overview 停发 height」vs「不改 Java」** 直接冲突。停发的唯一落点是 `ApiViews.java:162`（Java）。**按 MUST NOT 不动**，欠账归 T3（见 §4.3）。请裁决。
2. **「门禁基线 843」**：六个模块实际和 = **844**（T1 自己的日志也是这六个数）。本单 delta 0，**无需解释**；「843」是文档里的算术笔误。
3. **T1 报告说 overview 启动 3 次**：本会话旧码实测可达 **4 次**（竞态）。修后恒为 **1 次**，「恰好 1 次」的断言才成立。
4. 任务书说「`panels.js` 与 `map.js` 共用一个缓存」：实测 **`unitTree.js` 也拉 `/api/units`**，不纳入则 units 不可能恒为 1 次 —— 已一并纳入共享层（超出字面范围，为达「每 URL×target 恰好 1 次」所必需）。

---

## 11. 证据索引（全部在 `t2-evidence/`）

| 文件 | 内容 |
|---|---|
| `t2-report.md` | 本文件 |
| `harness/measure.cjs` | T1 装置（原样复用，只读） |
| `harness/micro.cjs` | 隔离微基准 + 三档路径实验 |
| `harness/t2-e2e.cjs` | A/B/C 三组断言（真档） |
| `harness/height-probe.cjs` | `height` 运行时剥字段探针（STRIP=0/1 对照） |
| `harness/run-mutations.py` | 变异装置（每轮恢复干净世界 + md5 自证 + 把"本轮字节"写进日志） |
| `micro/isolation-b.json` | **修前**隔离表（巨路径 + 边框恒绘） |
| `micro/final.json` | 修后生产默认 + 三档路径 + chunk sweep |
| `micro/e2e-clean3.json` | 干净轮 16/16 PASS |
| `micro/mutations.json` | **7 轮变异 7 killed**（含 clean/mutant md5、restore_ok） |
| `micro/height-strip.json` / `height-keep.json` | height 探针（剥/不剥） |
| `run-old/baseline-old1.json` | 本会话修前基线 |
| `run-new/baseline-new1.json` / `baseline-new2.json` | 修后两次跑 |
| `logs/full-verify.log` | 全量 `clean verify`（844、0 ERROR、Bug size 0 ×6） |
| `logs/mutations.log` | 变异轮完整输出 |
| `logs/m7-*.log` | M7 回归（m7f/m7g PASS；m7c/m7e 既有失败） |
| `m7-regress/*` | M7 回归产物 |
| `logs/height-*.log` / `micro-*.log` / `new*.log` | 各装置日志 |

---

## 12. 提交
分支 `m9/t2`。纯前端六文件 + `t2-evidence/**`。**不 push**。
