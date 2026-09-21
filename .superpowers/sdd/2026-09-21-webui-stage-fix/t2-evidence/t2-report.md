# T2 报告 —— 地图编辑下挂二级子选项（地形 / 连通性）

> 任务：`docs/superpowers/plans/2026-09-21-webui-stage-fix-plan.md` **§T2**（本任务只做 T2）。
> 设计依据：`docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md` **§三（编辑线）/ R1 / D?**（§〇.1 已裁）。
> 调查依据：`docs/superpowers/specs/2026-09-21-webui-stage-fix-research.md` **§A（旧仓连通性是独立工具 + 完全不同的手势）/ §B.1（新增模式要改 9 处）**。
> 分支 `wsf/t2`，worktree `.claude/worktrees/wsf-t2`，基线 `611465f`。日期 2026-09-21。

## 〇 一句话

地图编辑模式内新增**二级子选项**「地形」/「连通性」：地形势=属性（地形刷 + 圈选随机化），连通性=拓扑（河流/道路 + merge/replace）。
两个子面板**互斥可见**；写白名单**不变**（仍 `map-edit` 的 4 条）；**未做**连通性手势（T3）、**未动** `EdgeOperations.KINDS`（T3）。
全量门禁 **第 1 次尝试 rc=0**；5 个变异体全 KILLED，红点均落被保护断言。

## 一 改动清单

| 文件 | 改动 |
|---|---|
| `webui/index.html` | `map-edit` 段：删 4 工具 radio 组 `#map-edit-tools`；新增子选项控件 `#map-edit-subtools`（`name="map-edit-subtool"`，`terrain`/`connectivity`）；`#terrain-tool-controls[data-subtool=terrain]` 内加地形工具组 `#terrain-tool-select`（地形刷/圈选随机化）并把 `#randomize-controls` 移入其内；`#edge-controls[data-subtool=connectivity]` 内加 kind 组 `#edge-kind-select`（河流/道路） |
| `webui/map.js` | 新增纯函数 `MAP_EDIT_SUBTOOLS` / `mapEditSubtoolState` / `mapEditSubtoolTools` / `mapEditSubtoolDefaultTool` / `mapEditSubtoolOf` / `mapEditPanelVisibility` / `mapEditWriteAllowed` / `mapEditWriteGate`（均导出到 `window.SimosMap`）；宿主加 `host.mapEditSubtool`、`setMapEditSubtool`、`checkRadio`/`checkedRadioValue`/`wireRadioGroup`/`syncMapEditRadios`；`selectMapEditTool` 改按子选项切面板；三个写点（`commitBrush`/`submitRandomize`/`commitEdge`）各加写门；`mapEditDebug` 改读三个 radio 组并报 `subtool`/`hostSubtool` |
| `webui/styles.css` | 新增 `.map-edit-subtools`（segmented 两格） |
| `test/js/map-edit-suboptions.test.cjs` | **新增** 12 条（纯函数 8 + 静态 4） |
| `test/js/run-gate.cjs` | `MIN_TESTS` 96 → **108** |
| `test/js/gate-contract.test.cjs` | `MIN_ASSERTIONS` 96 → **108**；`REQUIRED_FILES` 加 `map-edit-suboptions.test.cjs`（排序在 `map-edit-tools` 前） |

**未动**（按任务边界）：`webui/modes.js`（白名单不变：`map-edit` 仍恰 4 条）、连通性手势与 `EdgeOperations.KINDS`（T3）、
`WebuiAssetsTest.java`（无新 webui 资产 ⇒ 无需改；仍 8 条不变）、任何 Java 生产代码。

## 二 判据实测（spec C1 / C2 + 计划三条）

现场命令与读数见 `logs/criterions.txt`（由本报告 §二逐条命令生成）。

| 判据 | 期望 | 实测 |
|---|---|---|
| **C1** 子选项控件存在（默认「地形」/「连通性」） | 在 | `#map-edit-subtools` ×1、`name="map-edit-subtool"` ×2（值 `terrain`/`connectivity`） |
| **C1** 面板互斥：地形控件可见 ⇔ 子选项=地形；edge 控件可见 ⇔ 子选项=连通性 | 恰一个 | `mapEditPanelVisibility`：`terrain`⇒`{t:1,c:0}`、`river`/`road`⇒`{t:0,c:1}`；有效工具下 `t+c==1`（逐工具断言） |
| **C2** 子选项=地形 ⇒ 不发 `map.SetEdge` | 拒 | `mapEditWriteAllowed("terrain","map.SetEdge")===false`；`mapEditWriteGate("terrain","map.SetEdge")==={ok:false,reason:"wrong-subtool"}` |
| **C2** 子选项=连通性 ⇒ 不发 `map.SetTerrain` | 拒 | `mapEditWriteAllowed("connectivity","map.SetTerrain")===false`；`road` 同理 |
| **C2** 写调用受子选项门控（静态） | 三个写点各有门 | `map.js` 含 `mapEditWriteGate(host.mapEditTool, "map.SetTerrain"/"map.RandomizeRegion"/"map.SetEdge")` 逐字 |
| 模式栏仍 **5** 个按钮（子选项不是新模式） | 5 | `index.html` 按 `data-mode=` 计数 **5**；无 `data-mode="terrain|connectivity"` |
| `modes.js` 白名单**不变** | 不变 | `git status` 该文件**未改**；`modes.test.cjs` 的 `map-edit-allows-exactly-four-writes` 仍绿 |
| 子选项=地形时 `#terrain-tool-controls` 可见、`#edge-controls` `hidden` | — | 初始 `setMapEditSubtool("terrain")` ⇒ `panels.terrain=true, panels.connectivity=false`（纯函数判定 + 宿主据此设 `hidden`） |

**运行时（served assets，非浏览器）**：独立端口（5891/5895/5893）起 shade jar `--demo` ⇒
`GET /` **200**，`id="map-edit-subtools"` ×1、`name="map-edit-subtool"` ×2、`id="terrain-tool-select"` ×1、
`id="edge-kind-select"` ×1、**旧 `id="map-edit-tools"` ×0**、**旧 `name="map-edit-tool"` ×0**；
`GET /map.js` **200** 且 md5 与源**逐字节相同**（`5078c2c2…`）。自起实例已停（只 kill 自己的 pid `87804`）；
**`5818` 实例（pid `64974`）全程未动**。日志 `logs/served-e2e.log`。

## 三 门禁（`./mvnw clean verify`，**第 1 次尝试**，rc=0，49s）

日志：`logs/clean-verify.log`（`log_md5=0139aabccf42351a221fcbfabbb121b1`，2320 行），重算：`logs/recomputed.txt`。

- **模块 8/8 `SUCCESS [`**（显示名）：`SimulatorMosire`（父 POM）/ `UtilSimos` / `MapSimos` / `SocialSimos` / `UnitSimos` / `CoreSimos` / `SDSimos` / `SimosApp`。
- **用例总数 1281** = `170 / 362 / 45 / 259 / 177 / 124 / 144`（现场从汇总行重算，**只取无 `-- in` 的行**）。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**。
- 前端：`[frontend-gate] OK tests=108 pass=108 fail=0`。

**基线对照（自己实测，不引用文档现成数字）**：T1 关账现场为 Java **1281** + 前端 **96**（本任务**未在本树改动前单独重跑全量**，
以 T1 报告 §三 的现场重算为基线）。**delta 干净且可解释**：

- Java 逐模块**逐值不变**（1281 不变）——本次**零 Java 用例增删**、**零 Java 源改动**（`git status` 仅 5 个前端/门禁文件 + 1 个新 JS 测试）。
- 前端 **96 → 108（+12）** ＝ 新增 `map-edit-suboptions.test.cjs` 的 12 条；两处下界同改（`run-gate.cjs:20`、`gate-contract.test.cjs:32`）+ `REQUIRED_FILES`。
- ★ JS 断言由 `exec-maven-plugin` 独立跑、**不并入** surefire 合计 ⇒ **1281 不变是正确的**；"下界是真护栏"的证据**不是数字变大，是它红过**（见 §四 m5）。

## 四 变异（九道门禁的 JS 适用子集；5 体全 KILLED）

装置：`mutants/mut-round.sh` + `mutants/make-mutant.py`；逐轮日志 `mutants/logs/{m1..m5}.log(.gate)`。

| 体 | 靶 | 变异 | 结果 | 红点（被保护断言） |
|---|---|---|---|---|
| **m1** | `map.js` | `mapEditPanelVisibility` 地形恒可见（去掉互斥） | **KILLED** | `not ok 16 - panel-visibility-is-mutually-exclusive` |
| **m2** | `map.js` | `MAP_EDIT_SUBTOOL_WRITES.terrain` 加入 `map.SetEdge`（跨线） | **KILLED** | `not ok 14 - write-allowed-is-per-subtool-and-cross-line-is-denied`、`not ok 15 - write-gate-resolves-tool-then-subtool` |
| **m3** | `map.js` | `commitEdge` 去掉写门（子选项门控失效） | **KILLED** | `not ok 19 - map-js-gates-every-map-write-with-the-matching-type` |
| **m4** | `map.js` | `mapEditSubtoolOf` 未知工具兜成 `"terrain"`（破 fail-closed） | **KILLED** | `not ok 13 - mapEditSubtoolOf-maps-tools-and-rejects-unknown`（连带 15/16） |
| **m5** | `gate-contract.test.cjs` | `REQUIRED_FILES` 漏掉新测试文件 | **KILLED** | `not ok 7 - all-required-test-files-are-present` |

**九道门禁逐条落点**（`mutants/logs/*.log` 自指）：① 干净世界（`clean_world_md5==orig_md5` 才开跑，逐轮实测相等）；
② 变异体字节不同（`mutant_md5 != orig_md5`，逐轮记录）；③ 推成**规范名目标文件**（`map.js`/`gate-contract.test.cjs`，非 variant 名）；
④ 清陈旧 `.class` —— **N/A**（node 直读源，无编译产物）；⑤ `Tests run>=1`（断言 `# tests 108`）；⑥ surefire mtime
—— **N/A**（无 surefire），改以**本轮专属** `mutants/logs/mN.log.gate`；⑦ 红点落被保护断言（逐轮列出，见上表）；
⑧ `cp` **逐字节**还原（`restored_md5==orig_md5`，逐轮记录；**未用 `git checkout`**）；⑨ 日志自指（orig/mutant/restored
三个 md5 写入本日志，读取处 `req()` **先断言非空**）。

**还原复核**（独立于装置）：5 个被触碰文件 live md5 == `mutants/orig/` pristine md5，**全部 `OK`**（见 §五 命令）。

## 五 交付后复核命令（可重放）

```bash
WT=/home/cna/SimulatorMosire/.claude/worktrees/wsf-t2
for f in index.html map.js styles.css run-gate.cjs gate-contract.test.cjs; do …（对照 mutants/orig 的 md5）; done
node simos-app/src/test/js/run-gate.cjs          # => [frontend-gate] OK tests=108 pass=108 fail=0
```

## 六 取代说明（执行期）

1. 计划 §T2 的步骤 1 写"加子选项控件 + 两个容器（`data-subtool=…`）"**已采用**（`#terrain-tool-controls[data-subtool=terrain]`、`#edge-controls[data-subtool=connectivity]`）。
2. 计划写"`map.js` 加 `setMapEditSubtool(...)`"**已采用**；另把"面板可见性"抽成**纯函数** `mapEditPanelVisibility`（C1 的互斥判定只有一处实现），
   否则"两容器同时可见"的变异**杀不掉**（纯函数才是可断言面）。计划未点名该函数名，属执行期补强。
3. 计划写"圈选随机化归「地形」"**已采用**：`#randomize-controls` 移入 `#terrain-tool-controls` 之内（静态断言：其位置在 terrain 容器与 edge 容器之间）。
4. 原 4 工具 radio 组 `#map-edit-tools`（`name="map-edit-tool"`）**拆成三个 radio 组**：
   `map-edit-subtool`（编辑线）/ `map-edit-terrain-tool`（线内地形工具）/ `map-edit-edge-kind`（线内连通性类型）。
   ⇒ 旧的 `id="map-edit-tools"` 与 `name="map-edit-tool"` **消失**（served 检查实测 ×0）；
   `mapEditDebug` 的 `tool` 字段改为按子选项从对应组读，并新增 `subtool`/`hostSubtool`。
   ★ **已知副作用**：M8 期的历史 Playwright e2e（`.superpowers/sdd/2026-09-19-map-edit/*/e2e/e2e.cjs`）用
   `page.check('input[name="map-edit-tool"]…')`，其选择器**已过时**——那是**历史证据**（不在 CI、且指向另一台机器的真档），
   **本任务不改历史证据**；后续若要重跑该 e2e，须同步更新其选择器。
5. 计划说"写白名单不变（`modes.js:29`）"**已遵守**：`modes.js` 一行未改。

## 七 我未能核实的

1. **浏览器内的真实点击/拖动未实测**：C1 的"切换子选项 ⇒ 面板互斥可见"与 C2 的"切换后拖动 ⇒ 不发越线写命令"
   **运行时半边**，本报告只证到 **纯函数（判定）+ 静态（结构/门控调用）+ served assets（结构确实被服务）**三层；
   **真 Chromium 的 DOM 事件层未跑**（本机无 `playwright`/`jsdom`，T1 报告 §七.1 同）。⇒ "拖一下看有没有发请求"这一观测**未做**。
2. **CSS 视觉未做像素证明**：`.map-edit-subtools` 的观感（segmented 两格）仅静态阅读，无截图/像素断言。
3. **子选项切换时的"线内工具回退到默认"是设计选择、未经用户确认**：地形→连通性切回时，连通性工具回到 `river`（该线默认），
   不记忆上次选的是 `road`。spec/计划未规定，本任务取"默认工具"语义（见 `mapEditSubtoolDefaultTool`）。
4. **`randomize` 与 `terrain` 都在「地形」线内**：spec §三.2 明写圈选随机化归地形；线内如何二选一由 `#terrain-tool-select` 的 radio 承担
   （旧 UI 是 4 选 1，现为"先选线、再选线内工具"两级）——这是**执行期形态决定**，spec 未细化到控件层次。
5. **门禁基线未在本 worktree 改动前单独重跑**（以 T1 关账现场 1281/96 为基线，见 §三）；
   其"Java 逐值不变"建立在**零 Java 改动**这一结构性事实（`git status` 可证）上。
6. **plan 提到的 `[待裁]` 子选项命名/粒度**（spec §三.2）本任务按默认「地形」/「连通性」落地；用户若另有命名偏好需回改（label 是常量）。
