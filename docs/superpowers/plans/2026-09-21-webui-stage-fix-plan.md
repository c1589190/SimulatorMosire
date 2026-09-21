# WebUI 阶段修复 —— 实现计划（bite-sized）

> 配套 spec：`docs/superpowers/specs/2026-09-21-webui-stage-fix-design.md`（**§〇.2 有 11 项 `[待裁]`（D1、D3~D11、D13）；★ D2/D12 已裁，见 spec §〇.1；未裁前该表各任务的"实现形态"待定**）。
> 前置依据：`2026-09-21-webui-stage-fix-brainstorm.md`（用户原话 + 裁定）、`2026-09-21-webui-stage-fix-research.md`（只读调查，锚点出处）、`CLAUDE.md`（铁律 / 门禁口径 / 纪律）。
> 格式模板：`docs/superpowers/plans/2026-09-20-sd-simos-plan.md`。
> ★ 本文**只写计划**，不含生产代码；**不跑 Maven**。
> ★ **`[待裁]` 项**（spec §〇.2）的步骤**待裁后补**；本文对它们一律给**默认形态**的步骤，并标 `[待裁]`。

---

## 〇 通则（每个任务都适用）

### 〇.1 派单与 worktree

- **派单**：用**默认子代理类型**（Claude Code 里省略 `subagent_type`；opencode 外壳用 `deepseek-flash-go`，**禁用 `deepseek-flash` / `category=`**）——★ **子代理类型按机器/外壳不同，照抄前先核**（`CLAUDE.md` 纪律段）。
- **worktree 隔离**：单任务 worktree（命名 `wsf/tN`，如 `wsf/t1`）；派发前 `git -C <worktree> reset --hard <基线>`（陈旧基线陷阱）。
- ★ **`CLAUDE.md` 与台账都用 worktree 内的绝对路径改**，改完 `git -C <worktree> status` 确认变的是**本树**（主检出与 worktree 各有一份同名文件）。
- **一次只跑一个 Maven**：本地推理网关与 Maven 抢核；**agent 活着时不跑全量 verify**。`clean verify` 走**前台**（后台跑会被内存守卫杀；被杀轮留档不删、**既不是红也不是绿**）。
- ★ **记门禁结果必须一并记"第几次尝试"**，不许把"某轮绿了"读成"装置稳定"。

### 〇.2 证据落点

- `.superpowers/sdd/2026-09-21-webui-stage-fix/tN-evidence/`（**不许放仓根**）；变异体与还原自记照九道门禁。
- **台账**：`.superpowers/sdd/2026-09-21-webui-stage-fix/progress.md`（裁定、结论、"我未能核实的"）。★ 本机实测 `.superpowers/sdd/` 下**没有** `.gitignore`、`git check-ignore` 返回"未忽略" ⇒ 普通 `git add` 即可；**换机器先 `git check-ignore -v <台账路径>` 再决定**。

### 〇.3 门禁口径（照 `CLAUDE.md` 与既有计划）

- `./mvnw clean verify` **rc=0**；判"模块覆盖"用 **`SUCCESS [`**（★ 模块**显示名**是 `UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp`，**不是 artifactId**）；`[ERROR]` **0 行**；`BugInstance size is 0`（模块数 = 7）。
- 前端：`[frontend-gate] OK tests=N pass=N fail=0`；★ 加/改 JS 测试 ⇒ `run-gate.cjs` 的 `MIN_TESTS` 与 `gate-contract.test.cjs` 的 `MIN_ASSERTIONS` **两处同改**，新文件进 `REQUIRED_FILES`。
- ★ **基线现场重算**：`grep -E '^\[INFO\] Tests run:' <log> | … | paste -sd+ | bc`；★ 逐类行与模块汇总行会**双重计数**，只取**汇总行**（或在模块目录内取 `Tests run: … ` 汇总行）。**门禁总数一律现场重算，不得引用任何文档里的现成数字**（含本计划）。
- ★ **新增/改动 `catalog` 里已有命令的注册 ⇒ 必须喂饱 `McpCoverageTest` 的双向载荷断言**（每个注册 type 有可提交载荷 + head 前进）。
- ★ **护栏必须自证**：每个新护栏配**故意违规**用例 + **≥1 轮变异**；九道门禁照 `CLAUDE.md`（干净世界 / 变异体字节不同 md5 自证 / 白名单推成目标类名 / 清陈旧 `.class` / `COMPILATION ERROR`=0 / surefire mtime 落轮内且读**本名轮那块** / 红点落被保护断言 / `cp` 逐字节还原 / 日志自指）。
- **同一文件被两任务改 ⇒ 必须串行**，后关账者**重跑前者的变异轮**（旧证据的对象已被改掉）。同文件清单见 §二.3。
- **不 `git add -A`**；`git add` **显式路径**；**不加 `Co-Authored-By`**；**该推就推**（私有仓库）。

### 〇.4 本阶段特有

- ★ **前端护栏必须进 `simos-app/src/test/js/` 门禁**（不是"证据级"）——这是 M7/M8 的系统性开口项。
- ★ **`tools/**` 不入 Maven reactor**（M6 行）：T11/T12 的导入器/文档脚本改动**不在门禁内**，须靠**对拍脚本 + 产物计数断言**自证。
- ★ **被测文件改动 ⇒ 旧证据作废**（裁定 42）：本计划的同文件串行表（§二.3）列出必须重跑前置变异轮的任务。

---

## 一 目标与总判据

### 一.1 目标

修正已交付物的**形态错误**（不是加功能）：① 地图编辑分"地形 / 连通性"两条编辑线；② 审批从右栏挪到右下角通知栏 + 决策模式；③ 决策人 / GM 界面从"缺席"到"可看可用"；④ **端口拓扑**让 GM 与决策人各有面；⑤ **富世界**替代贫瘠的 `--demo`。

### 一.2 总判据

引用 spec **§七**的 `C1`~`C34`（**不复制正文**）。落地时逐条给实测值。

### 一.3 门禁基线（★ 开工现场重算，不引用本表）

> 本表来自 **2026-09-21 SDSimos 阶段 E 关账的现场重算**（`.superpowers/sdd/2026-09-20-sd-simos/e-evidence/logs/recomputed.txt`，`log_md5=50cea6463a49e0179d64f0a086abe29c`，`rc=0`）。★ **任务书写的 HEAD=`bc7c8a4` 是陈旧的**——实测当前 HEAD=`92444b2`（`bc7c8a4` 是它的**祖先**，落后 15 个提交）。**开工时以实际 HEAD + 现场重算为准。**

| 项 | 基线值（2026-09-21 阶段 E） |
|---|---|
| 主树 `./mvnw clean verify` | **rc=0** |
| 用例总数 | **1281** = `UtilSimos 170 / MapSimos 362 / SocialSimos 45 / UnitSimos 259 / CoreSimos 177 / SDSimos 124 / SimosApp 144` |
| 模块数 | **8/8**（父 POM + 7 模块） |
| SpotBugs | `BugInstance size is 0` **×7** |
| 日志 | `[ERROR]` **0 行** |
| 前端门禁 | `tests=90 pass=90 fail=0` |

★ **每个任务的"预期门禁数字"以本表为基准推导**；实现期以**实测**为准，关账用**实测值**。

---

## 二 任务总表（依赖与串并行）

### 二.1 分期与依赖图

```
阶段 P（前端形态修正，互不依赖）
  T1 撤审批+通知栏   T2 地图编辑子选项   T3 连通性手势

阶段 Q（MCP 拓扑 + 查询面 + 可见性）
  T4 端口拓扑 ───────────┐
  T5 决策人查询面 ────┐  │
  T6 redaction 收口   │  │
                      │  │
阶段 R（决策模式 UI / GM 界面 / 信号 / 入口）
  T7 决策模式+交互 ←──┼──┘
  T8 GM 交互界面 ←────┼─ T4
  T9 待决信号 ←───────┼─ T5, T7
  T10 开始决策入口 ←──┴─ T4, T5, T7

阶段 S（富世界，独立可推进）
  T11 富世界地图+区域 → T12 富世界文档.md

阶段 E
  T13 关账 ← 全部
```

### 二.2 任务总表

| 任务 | 名称 | 依赖 | 阶段 | 主要模块/文件 | 预期用例增量（推演） |
|---|---|---|---|---|---|
| **T1** | 撤右栏审批 + 右下角通知栏 | — | P | `webui/index.html`、`app.js`、`styles.css`、新 JS 测试、`WebuiAssetsTest` | 前端 +k（新测试文件） |
| **T2** | 地图编辑下挂二级子选项（地形/连通性） | — | P | `webui/index.html`、`map.js`、`styles.css`、JS 测试 | 前端 +k |
| **T3** | 连通性手势对齐 GSimulator + 往返守卫覆盖连通性 | — | P | `webui/map.js`、JS 测试；`simos-map` 往返测试（若缺） | 前端 +k；map +0/1 |
| **T4** | 端口拓扑：现有口扩 GM ∪ EXTERNAL + 决策人口 | — | Q | `Shell.java`、`ShellConfig.java`、`ShellMain.java`、`SimosToolSource.java`、app 测试 | app +4~6 |
| **T5** | 决策人查询面（后端只读） | — | Q | `GuiServer.java`、`QueryService`/新 `SdQueryService`、`ApiViews`、app 测试 | app +5~7 |
| **T6** | redaction 洞收口（disclosure/fields 生效 + 端点覆盖） | T5（推荐） | Q | `RedactingQueryService.java`、`GuiServer.java`、app 测试 | app +4~6 |
| **T7** | 决策模式（一模式两子页）+ 决策人交互 | T5 | R | `modes.js`、`index.html`、`panels.js`、`map.js`、`styles.css`、`WebuiAssetsTest`、JS 测试 | 前端 +k；app +0~2 |
| **T8** | GM 交互界面（底栏按钮 + 全屏，仅显示 GM MCP 工具使用） | T4 | R | `index.html`、新 JS、`styles.css`、app 测试 | app +2~4 |
| **T9** | "建议/待决"信号（服务端计算 + 列表显示） | T5, T7 | R | `QueryService`/新服务、`GuiServer`、`panels.js`、JS 测试 | app +3~5 |
| **T10** | "开始决策"入口与权限（用户直接 / GM Agent 审批） | T4, T5, T7 | R | 新 `sd.StartDecision` handler（`simos-sd`）、`GuiServer`、`SimosToolSource`、`CatalogTool`、`McpCoverageTest` | sd +3~5；app +3~5 |
| **T11** | 富世界地图 + 区域复刻（`--demo` 升级） | — | S | `tools/gsimap_import.py`、新 `RichWorld`、`DemoWorld`/资源、`ShellMain`、app 测试 | app +4~6 |
| **T12** | 富世界文档 `.md` 产出（绝不录 Info） | T11 | S | 新 `tools/` 脚本、`docs/worlds/v17levant/`、计数校验 | app +1~2（Info 不变量） |
| **T13** | 关账（判据逐条 + R 点验 + 报告） | 全部 | E | — | 0 |

★ **可并行性**：T1/T2/T3 文件面**部分相交**（`index.html` 被 T1/T2/T7/T8 改；`map.js` 被 T2/T3/T7 改）⇒ 见 §二.3。**执行上仍串行**（一次只跑一个 Maven）。

### 二.3 ★ 同文件串行清单（后关账者重跑前者变异轮）

| 文件 | 被哪些任务改 | 串行结论 |
|---|---|---|
| `simos-app/src/main/resources/webui/index.html` | **T1/T2/T7/T8** | **串行**；T1→T2→T7→T8 |
| `simos-app/src/main/resources/webui/map.js` | **T2/T3/T7** | 串行；T2→T3→T7 |
| `simos-app/src/main/resources/webui/app.js` | **T1**（撤 `mountApprovals`） | 仅 T1 |
| `simos-app/src/main/resources/webui/panels.js` | **T7/T9** | 串行；T7→T9 |
| `simos-app/src/main/resources/webui/modes.js` | **T7** | 仅 T7 |
| `simos-app/src/main/resources/webui/styles.css` | **T1/T2/T7/T8** | 串行（同上序） |
| `simos-app/src/test/java/.../gui/WebuiAssetsTest.java` | **T1/T7/T8** | 串行（同上序） |
| `simos-app/src/test/js/run-gate.cjs` + `gate-contract.test.cjs` | **所有加 JS 测试的任务（T1/T2/T3/T7/T8/T9）** | **每次改一处必须同步两处 + `REQUIRED_FILES`**；串行 |
| `simos-app/src/main/java/.../app/gui/GuiServer.java` | **T5/T6/T10** | 串行；T5→T6→T10 |
| `simos-app/src/main/java/.../app/Shell.java` | **T4/T5（若接线）/T6（若接线）/T8（若接线）/T10** | ★ **串行**；建议把新接线收进 app 层助手（如 `SdExplainWiring`）以减少碰撞，但**仍串行** |
| `simos-app/src/main/java/.../app/ShellConfig.java` + `ShellMain.java` | **T4/T11**（T11 改 `--demo` 落点） | 串行；T4→T11 |
| `simos-app/src/main/java/.../app/tools/SimosToolSource.java` | **T4/T10** | 串行；T4→T10 |
| `simos-app/src/main/java/.../app/tools/read/CatalogTool.java` | **T10** | 仅 T10 |
| `simos-app/src/test/java/.../app/McpCoverageTest.java` | **T4/T10** | 串行；T4→T10 |
| `simos-sd/src/main/java/.../sd/spi/*`（新 handler） | **T10** | 仅 T10 |
| `simos-app/src/main/java/.../app/demo/DemoWorld.java` / 新 `RichWorld` | **T11** | 仅 T11 |
| `tools/gsimap_import.py` | **T11** | 仅 T11 |
| `simos-map/src/main/java/.../map/ops/EdgeOperations.java` + 新 `map.RegisterPathwayGroup` handler | **T3** | 仅 T3 |

★ **通则**：**同一文件被两个任务改 ⇒ 串行**，后关账者**重跑前者的变异轮**。

---

## 三 任务明细

> 每个任务写全：**目标 / 依赖 / 文件清单 / bite-sized 步骤 / 判据 / 变异靶子 / 证据落点 / 提交信息样式**。
> ★ 涉及 spec §〇.2 `[待裁]` 的步骤标 `[待裁]`，默认形态照 spec 的默认建议。

---

### T1 撤右栏审批 + 右下角通知栏

**目标**：撤掉右栏审批计数 UI（`index.html:210-211`、`app.js:368-386`），改在**右下角 `position:fixed` 通知栏**显示审批摘要；**后端 `/api/approvals` 保留**。

**依赖**：无。

**文件清单**
- `simos-app/src/main/resources/webui/index.html`：删 `<h3>待批</h3>` + `<p id="approvals-count">`（`:210-211`）；删 `boot({approvals:...})`（`:246` → 改 `boot({...})`，无 `approvals`）；新增右下角通知栏元素（如 `<div id="notifications" class="notify-bar" hidden>`）。
- `simos-app/src/main/resources/webui/app.js`：删 `mountApprovals`（`:367-386`）、删 `boot` 里的 `if (opts.approvals)`（`:393-395`）、删导出（`:450`）；新增通知栏渲染（读 `/api/approvals`，**保留** `api.approvals`）。
- `simos-app/src/main/resources/webui/styles.css`：通知栏样式。
- `simos-app/src/test/js/`：新 `notifications.test.cjs`（或并入现有）——断言"通知栏存在且读 `/api/approvals`"。
- ★ `simos-app/src/test/js/write-allowlist.test.cjs`：**不动**（`api.approvals` 保留，`:112` 仍有效）；**若**实现选择删 `api.approvals`，**必须**把 `:112` 的**被측对象**换成通知栏模块并保住断言数（**不许删断言**）。
- `simos-app/src/test/js/run-gate.cjs` + `gate-contract.test.cjs`：新文件进 `REQUIRED_FILES`，两处下界同改。
- `WebuiAssetsTest.java`：若新增脚本资产，更新 `WORKBENCH_SCRIPTS`/`ALL_ASSETS`。

**bite-sized 步骤**
1. 删 `index.html` 的两行审批元素 + `boot` 的 approvals 参数。
2. 删 `app.js` 的 `mountApprovals` + 挂载 + 导出。
3. 新增通知栏元素与 JS 渲染（独立模块文件，避免 `app.js` 继续膨胀）。
4. 写 JS 测试（静态：`index.html` 不含 `approvals-count`；动态：通知栏模块打 `GET /api/approvals`；`write-allowlist` 仍绿）。
5. 改两处下界 + `REQUIRED_FILES`。
6. `./mvnw -q -pl simos-app -am -Dtest='WebuiAssetsTest,AppWritePathGuardTest' -Dsurefire.failIfNoSpecifiedTests=false test`；JS：`node --test simos-app/src/test/js/notifications.test.cjs`。

**判据（可实测值）**
- `index.html` **不含** `approvals-count` 与 `<h3>待批</h3>`（`grep -c` = 0）。
- `app.js` **不含** `mountApprovals`（`grep -c` = 0）。
- 通知栏元素存在，且其模块的 fetch 记录里含 `GET /api/approvals`。
- `write-allowlist.test.cjs` **绿**（`api.approvals` 保留）。
- `./mvnw clean verify` 绿（spec `C20`/`C21`）。

**变异靶子**
- **m1**：把 `index.html` 的审批块加回 ⇒ 静态断言红（`index.html` 不含 `approvals-count`）。
- **m2**：通知栏不读 `/api/approvals` ⇒ 动态 fetch 记录断言红。
- **m3**：删 `api.approvals` 但不改 `write-allowlist.test.cjs` ⇒ 该文件红（证明隐藏断点真实存在；跑完即还原）。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t1-evidence/`（clean verify 日志 + JS 测试输出 + mutants/ 含 md5 自记）。

**提交信息样式**：`fix(webui): 撤右栏审批计数，改右下角通知栏（T1）`

---

### T2 地图编辑下挂二级子选项（地形 / 连通性）

**目标**：`map-edit` 模式内新增**二级子选项**「地形」/「连通性」；两子面板**互斥可见**；写白名单不变（`modes.js:29`）。

**依赖**：无（`index.html` 与 T1 冲突 ⇒ 排在 T1 之后）。

**文件清单**
- `simos-app/src/main/resources/webui/index.html`：在地图编辑 `<section data-modes="map-edit">`（`:88`）顶部加子选项控件（radio/segmented），并把 `#terrain-tool-controls`（地形 + 调色板 + randomize）与 `#edge-controls`（连通性）分组。
- `simos-app/src/main/resources/webui/map.js`：子选项状态 + 面板切换（现 `updateMapEditToolControls` 一带，`:2586-2615`）；**圈选随机化**归「地形」。
- `simos-app/src/main/resources/webui/styles.css`：子选项样式。
- JS 测试：新 `map-edit-suboptions.test.cjs`（纯函数/静态）。
- 两处下界 + `REQUIRED_FILES`。

**bite-sized 步骤**
1. 加子选项控件 + 两个容器（`data-subtool="terrain|connectivity"`）。
2. `map.js` 加 `setMapEditSubtool(...)`：切地形 ⇒ `terrain-tool-controls` 可见、`edge-controls` hidden；反之亦然。
3. 把 randomize 控件并入「地形」容器。
4. 写 JS 测试：子选项切换的面板可见性；静态断言子选项控件存在。
5. 改两处下界 + `REQUIRED_FILES`；跑定向。

**判据**
- 子选项=地形 ⇒ `#terrain-tool-controls` 可见、`#edge-controls` `hidden`；子选项=连通性 ⇒ 反之（spec `C1`）。
- 子选项=地形时拖动**不产生** `map.SetEdge`；子选项=连通性时拖动**不产生** `map.SetTerrain`（spec `C2`）。
- 模式栏仍 5 个按钮（子选项**不是**新模式）；`modes.js` 白名单不变。

**变异靶子**
- **m1**：两容器同时可见（去掉互斥）⇒ 可见性断言红。
- **m2**：子选项=地形时仍允许 `map.SetEdge`（门控写错）⇒ `C2` 的写请求断言红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t2-evidence/`。

**提交信息样式**：`fix(webui): 地图编辑下挂地形/连通性子选项（T2）`

---

### T3 连通性手势 + 词表可自定义 + 往返守卫覆盖连通性

**目标**：① 连通性编辑**照搬 GSimulator 手势**（右键画 / 左键删 / 起点→续点→结束 / 12px 阈值 / 双向写边）；simos 侧重写（缺格不静默建、可加相邻校验）；② ★ **词表 = 默认 + 可自定义**（spec §三.6、用户裁定「对，需要改成默认+可自定义」）：`EdgeOperations.KINDS` 改为从状态里的 `pathwayGroups` 派生、新增组注册命令；③ **铁律 5 往返守卫覆盖连通性**。

**依赖**：T2（同改 `map.js` 与子选项）。

**文件清单**
- `simos-app/src/main/resources/webui/map.js`：连通性手势状态机（对照 research §A.2 的 `js/pathway.js` 语义重写）；kind 候选读已注册组。
- ★ `simos-map/src/main/java/.../map/ops/EdgeOperations.java`：`KINDS` 从 `base.pathwayGroups().keySet()` 派生（去掉 `:37` 的硬编码 `Set.of("river","road")`；注释 `:26` 同步改），未注册 `kind` 仍 fail-closed。
- ★ 新 `simos-map` 命令 + handler：`map.RegisterPathwayGroup`（载荷 = `PathwayGroup` 字段；构造期守卫照 `PathwayGroup.java` 既有：id/name 空白即抛、color 须 `#RRGGBB`）。
- `simos-map/src/main/java/.../spi/SetEdgeHandler`（若 D14 选"加相邻校验"，则加校验 + 用例）。
- JS 测试：`map-edit-tools.test.cjs`（既有）扩充 + 或新文件；捕 `EdgeRef` 双向不产生两条边。
- `simos-map/src/test/.../change/RoundTripComponentsTest.java`（或 `EdgeOperationsTest`）：**非空 `edges` 的往返**（若缺）+ **词表可自定义**用例。

**bite-sized 步骤**
1. 实现手势状态机（起点 → waypoint → 结束条件）；12px 命中阈值（复用/对齐 `findSegmentAtPixel` 语义）。
2. 右键画边（连续多段合一条 `map.SetEdge`）；左键命中边即删边。
3. 缺格 ⇒ **不写、可见提示**；非相邻续点 ⇒ 提示。
4. ★ **词表改造**：`EdgeOperations.KINDS` 改为 `base.pathwayGroups().keySet()` 派生；新增 `map.RegisterPathwayGroup` 命令 + handler；默认提供 `river`/`road` 两组（既有真档/用例不变）。
5. ★ 加/补**非空 `edges` 往返用例**（`FieldDelta` 全变体覆盖）。
6. 写 JS 测试 + 词表用例 + 跑定向。
7. `./mvnw -q -pl simos-map -am -Dtest='RoundTripComponentsTest,EdgeOperationsTest' -Dsurefire.failIfNoSpecifiedTests=false test`。

**判据**
- 手势逐条（spec `C3`）。
- ★ 非空 `edges` 往返绿；**删 `MapChangeSet.edges` 组件 ⇒ 该用例红**（spec `C4`）。
- 非相邻 `map.SetEdge` 被拒（若 D14 加校验）⇒ `revisions` 行数不变（spec `C5`）。
- ★ **词表可自定义（spec `C34`）**：注册自定义组 `canal` 后 `map.SetEdge{kind:canal}` **成功**（落 revision）；**未注册 `kind` 仍被拒**；默认 `river`/`road` 不受影响。

**变异靶子**
- **m1**：手势状态机"同格结束"条件去掉（无限续点）⇒ 用例红。
- **m2**：`EdgeRef` 双向去重去掉（A→B 与 B→A 各成一条）⇒ 双向断言红。
- **m3**：`MapChangeSet` 去掉 `edges` 组件 ⇒ 往返用例红。
- **m4**（若加校验）：`SetEdgeHandler` 去掉相邻校验 ⇒ 非相邻用例红。
- ★ **m5**：`EdgeOperations.KINDS` 改回硬编码 `Set.of("river","road")` ⇒ 注册 `canal` 后仍被拒、`C34` 红。
- ★ **m6**：`kind` 校验恒放行（去掉"未注册即拒"）⇒ "未注册 `kind` 被拒"断言红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t3-evidence/`。

**提交信息样式**：`fix(webui): 连通性手势 + 词表默认可自定义 + 往返守卫覆盖（T3）`

---

### T4 端口拓扑（现有口扩 GM ∪ EXTERNAL + 决策人口）

**目标**：现有 `--mcp-port` = **GM ∪ EXTERNAL**（★ **D2 已裁**="加"，见 spec §〇.1）；**新开决策人端口**（`DECISION_AGENT` 桶）；`ShellConfig`/`ShellMain`/`Shell` 接线；两 server 同起同关。★ **N9 冲突已记账**（spec §二.5）：现有口保留通用写（`simos.command.submit`/`advance`/`fork`），即该口持有者可绕过窄工具——**这是用户裁定的取舍、不是缺陷**。

**依赖**：无。

**文件清单**
- `simos-app/src/main/java/.../app/tools/SimosToolSource.java`：新增**复合桶**（如 `Role.EXTERNAL_WITH_GM`，或让构造器接受桶集合）——★ [待裁 D8] 的形态。
- `simos-app/src/main/java/.../app/ShellConfig.java`：加 `decisionAgentMcpPort`（缺省 `5717`，D3）+ `withPorts` 配套。
- `simos-app/src/main/java/.../app/ShellMain.java`：加 `--decision-agent-mcp-port`。
- `simos-app/src/main/java/.../app/Shell.java`：建第二个 `ToolRegistry` + `McpSourceBridge` + 第二次 `startHttp`；`close()` 次序加新 server；装配日志加新端口。
- `simos-app/src/test/java/.../app/McpServerTest.java` / `McpCoverageTest.java`：工具面断言（现有口含 GM 窄工具 + 通用写；决策人口无通用写）。
- 可能：`ShellLifecycleTest` / `BindAddressTest` 适配新端口。

**bite-sized 步骤**
1. `SimosToolSource` 加复合桶（EXTERNAL 写 + GM 窄写；读共享）。
2. `ShellConfig` 加端口 + `ShellMain` 加开关。
3. `Shell` 建第二个注册表/桥/server；`close()` 次序（GUI → MCP1 → MCP2 → 审批）。
4. ★ 喂饱 `McpCoverageTest`：现有口提交路径不变；新增"决策人口工具面"断言。
5. 跑定向：`./mvnw -q -pl simos-app -am -Dtest='McpServerTest,McpCoverageTest,ShellLifecycleTest,ShellMainParseTest' -Dsurefire.failIfNoSpecifiedTests=false test`。

**判据**
- spec `C6`（现有口含 3 通用写 + 3 GM 窄工具）/ `C7`（决策人口含 2 窄工具、无通用写/无 `SetViewScope`）/ `C8`（两口同监听、关闭后都不可连）/ `C9`（端口是唯一边界：新口调通用写 ⇒ 工具不存在）。
- `McpCoverageTest` 所有注册 type 仍双向断言（spec `C33`）。

**变异靶子**
- **m1**：现有口丢掉 GM 窄工具（只留 EXTERNAL）⇒ `C6` 断言红。
- **m2**：决策人口错挂外部桶（含 `simos.command.submit`）⇒ `C7` 红。
- **m3**：`close()` 漏关第二个 server ⇒ `C8`（关闭后仍可连）红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t4-evidence/`。

**提交信息样式**：`feat(app): MCP 端口拓扑——现有口扩 GM，决策人另开端口（T4）`

---

### T5 决策人查询面（后端只读）

**目标**：新增**按 affiliation 列决策人 + 单个详情**的只读面（GUI 端点；可选读工具），供 T7/T9/T10 使用。

**依赖**：无。

**文件清单**
- `simos-app/src/main/java/.../app/gui/GuiServer.java`：加 `GET /api/sd/decision-makers`（可选 `?affiliation=nation:<id>` / `army:<id>`）与 `GET /api/sd/decision-makers/{id}`；`GET_ROUTES` 更新。
- 新 `simos-app/src/main/java/.../app/query/SdQueryService.java`（或扩展 `QueryService`）：从 `SdState.decisionMakers()/armies()/nations()` 组视图。
- `simos-app/src/main/java/.../app/gui/ApiViews.java`：决策人视图序列化。
- 可选：`SimosToolSource` 加读工具（★ **先核实 `SimosToolsTest`/`McpCoverageTest` 的工具面断言形状**）。
- app 测试：新 `SdDecisionMakerApiTest.java`（真 `Shell` + `GuiServer`）。

**bite-sized 步骤**
1. `SdQueryService`：`listDecisionMakers(affiliationFilter)` / `decisionMaker(id)`。
2. `ApiViews`：视图（id / affiliation 解析名 / cadence / allowedTools / viewScope 摘要）。
3. `GuiServer` 路由 + `GET_ROUTES`。
4. 测试：空库 ⇒ 空列表（**非 500**）；有 `sd.CreateDecisionMaker` ⇒ 列表含它、过滤正确、详情字段逐值。
5. 跑定向。

**判据**
- `GET /api/sd/decision-makers` 空库 ⇒ `200 {"decisionMakers":[]}`（**不是** 404/500）。
- 过滤 `?affiliation=nation:<id>` ⇒ 恰返回该国决策人（逐值）。
- 详情字段与 `SdState` 逐值一致（spec `C13` 的后端半边）。
- 端点**只读**（`AppWritePathGuardTest` 扫描不新增写路径）。

**变异靶子**
- **m1**：列表不过滤 affiliation（返回全部）⇒ 过滤断言红。
- **m2**：详情缺 `cadence` 字段 ⇒ 字段断言红。
- **m3**：空库抛异常（500）⇒ 空列表断言红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t5-evidence/`。

**提交信息样式**：`feat(app): 决策人只读查询面（T5）`

---

### T6 redaction 洞收口

**目标**：`adjudicationDisclosure` / `redactedFields` **真正生效**；**所有读端点**带 `as=` 时走 redaction；未接的**逐条 fail-closed**（D9 默认"修"）。

**依赖**：T5（推荐，需决策人夹具）。

**文件清单**
- `simos-app/src/main/java/.../app/query/RedactingQueryService.java`：应用 disclosure（判决字段按口径披露/隐藏）+ `redactedFields`（按字段名剔除）。
- `simos-app/src/main/java/.../app/gui/GuiServer.java`：把 `/api/state`、`/api/timeline`、`/api/map/hex`、`/api/unit/{id}`、`/api/social/population`、`/api/resolve`、`/api/facets` 在带 `as=` 时接 redaction；未接的改为拒绝。
- `simos-app/src/test/java/.../app/query/RedactingQueryServiceTest.java`：扩展现有（已有 3 条）。
- 可能：`GuiApiTest` 加 `as=` 断言。

**bite-sized 步骤**
1. `RedactingQueryService` 加 `applyDisclosure` / `applyRedactedFields`。
2. 逐端点接 redaction（带 `as=`）；未接的返回拒绝（fail-closed）。
3. 测试：两 `ViewScope` 返回不同；`WITHHELD` 判决字段消失；`redactedFields=["position"]` 字段消失。
4. 列出"未接端点清单"（代码注释 + spec 对应）。
5. 跑定向。

**判据**
- spec `C28`/`C29`/`C30`。
- 既有 `RedactingQueryServiceTest`（3 条）**仍绿**。

**变异靶子**
- **m1**：`applyDisclosure` 恒回显（不按口径）⇒ `WITHHELD` 用例红。
- **m2**：`redactedFields` 不生效 ⇒ `position` 字段用例红。
- **m3**：某端点漏接且**静默返回全量** ⇒ fail-closed 用例红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t6-evidence/`。

**提交信息样式**：`fix(app): redaction 收口——disclosure/redactedFields 生效 + 端点覆盖（T6）`

---

### T7 决策模式（一模式两子页）+ 决策人交互

**目标**：新增第六模式「决策」（id `decision`），含两子页「决策人查看」/「审批」；子页 A 实现 R12 的三处交互（国家高亮 / 单位 / 右侧分类列表）+ 左栏信息。

**依赖**：T5。

**文件清单**
- `simos-app/src/main/resources/webui/modes.js`：加 `{id:"decision", label:"决策", writes:[]}`（D5 若选新命令则加 `sd.StartDecision`）。
- `simos-app/src/main/resources/webui/index.html`：模式栏加按钮；新 `<section data-modes="decision">`（左栏/右栏/子页控件）。
- `simos-app/src/main/resources/webui/panels.js`：左栏决策人信息渲染 + 右侧分类列表渲染（复用/扩 `groupByTag` 口径）。
- `simos-app/src/main/resources/webui/map.js`：国家 tag ⇒ 高亮区域集合（复用 M8 T9 的 `buildRegionHighlightPlan` 家族）。
- `simos-app/src/main/resources/webui/styles.css`。
- `WebuiAssetsTest.java`：`MODE_LABELS` 加「决策」；`allFiveModesAreEnabledRealControls` **改名/改断言为六个**（★ 方法名含 "Five" 会误导）。
- JS 测试：`modes.test.cjs` 加第六模式白名单断言；新 `decision-mode.test.cjs`（子页切换、列表分组纯函数）。
- 两处下界 + `REQUIRED_FILES`。

**bite-sized 步骤**
1. `modes.js` 加模式。
2. `index.html` 加按钮 + section + 子页控件。
3. `map.js` 国家 tag 高亮（调 T5 的 `/api/sd/decision-makers` 取归属）。
4. `panels.js` 左栏信息 + 右侧分类列表。
5. 扩展 `WebuiAssetsTest`（五→六）+ `modes.test.cjs`。
6. 写交互 JS 测试；改下界；跑定向。

**判据**
- spec `C10`/`C11`/`C12`/`C13`/`C14`/`C15`。
- `modes.test.cjs` 白名单与 `modes.js` 一致；`workbench-write-calls-are-all-whitelisted` 仍绿。

**变异靶子**
- **m1**：右侧列表不按类型分组（混排）⇒ 分组断言红。
- **m2**：国家 tag 高亮只高亮第一个区域 ⇒ 集合相等断言红。
- **m3**：`modes.js` 加了模式但 `index.html` 没加按钮 ⇒ `WebuiAssetsTest` 六模式断言红。
- **m4**：子页切换失效（两子页同时显示）⇒ 子页断言红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t7-evidence/`。

**提交信息样式**：`feat(webui): 决策模式与决策人交互（T7）`

---

### T8 GM 交互界面（底栏按钮 + 全屏，仅显示 GM MCP 工具使用）

**目标**：底栏加按钮 ⇒ 全屏 GM 交互界面；**只显示 GM MCP 的工具使用**（工具名 + 结果），**不做对话**（R9）。

**依赖**：T4（GM 面）。

**文件清单**
- `simos-app/src/main/resources/webui/index.html`：底栏（`#timeline-bar` 区）加按钮；全屏浮层容器。
- 新 `simos-app/src/main/resources/webui/gm.js`（或并入 `timeline.js` 之外的新资产）。
- `simos-app/src/main/resources/webui/styles.css`。
- 数据来源：GM 工具使用记录。★ **先核实**：现有是否有工具调用事件/日志可读（`EventStore` / 观测面）；**若无**，T8 需新造一个只读"GM 工具使用"读面（归 T8 范围，写明）。
- `WebuiAssetsTest.java`：新资产入册。
- app 测试 + JS 测试。

**bite-sized 步骤**
1. 核实 GM 工具使用记录的**既有可读面**（`simos-core` 的事件表 / `EventTypes`；research 未覆盖 ⇒ **推断，需现场核实**）。
2. 若无现成读面：加只读端点 `GET /api/gm/tool-usage`（从事件表/工具链读）。
3. 底栏按钮 + 全屏浮层 + 渲染（工具名/时间/结果）。
4. 测试：按钮存在；点击后面板可见；渲染项来自服务端记录（非写死）。
5. 跑定向。

**判据**
- spec `C22`。
- 界面**不含**对话输入框（静态断言）。

**变异靶子**
- **m1**：全屏面板不读服务端记录（写死空）⇒ 渲染断言红。
- **m2**：底栏按钮未接 panel ⇒ 可见性断言红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t8-evidence/`。

**提交信息样式**：`feat(webui): GM 交互界面（仅显示 GM MCP 工具使用）（T8）`

---

### T9 "建议/待决"信号

**目标**：服务端计算 `due`（D7 公式：`tick − lastDirectiveTick ≥ cadence`）+ 决策模式列表显示。

**依赖**：T5、T7。

**文件清单**
- `simos-app/src/main/java/.../app/query/SdQueryService.java`（T5 建的）：加 `due(dm, tick)` 计算。
- `GuiServer`：列表/详情响应加 `due` / `lastDirectiveTick` / `ticksSinceLast`。
- `panels.js`：列表项显示待决标记。
- app 测试 + JS 测试。

**bite-sized 步骤**
1. 实现 `due`（扫 `SdState.directives()` 取该 dm 最大 tick；无 ⇒ 恒 `due`）。
2. 响应加字段。
3. 列表显示。
4. 测试：造 2 个不同 cadence / 不同最后决策 tick 的 dm ⇒ `due` 逐值 = 离线公式（spec `C17`）。
5. 跑定向。

**判据**
- spec `C16`（推进不产生决策标记）/ `C17`（`due` 集合 = 离线公式）。
- **R4 口径**：同一 `(dm, tick)` 两条 `sd.IssueDirective` ⇒ 第二条拒、`revisions` 不变（既有不变量，回归断言）。

**变异靶子**
- **m1**：`due` 恒 `true` ⇒ 公式断言红。
- **m2**：`lastDirectiveTick` 取最小而非最大 ⇒ 公式断言红。
- **m3**：推进时自动产生"决策"标记 ⇒ `C16` 红。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t9-evidence/`。

**提交信息样式**：`feat(app): 待决/建议信号（T9）`

---

### T10 "开始决策"入口与权限

**目标**：新命令 `sd.StartDecision`（D5 默认，落 revision）；用户 GUI 直发（直接生效）；GM Agent 经 GM 口发（**过审批门链**，D6）；决策人口**无**此命令。

**依赖**：T4、T5、T7。

**文件清单**
- `simos-sd/src/main/java/.../sd/`：新命令类型 + handler（`StartDecisionHandler`，构造期校验）。
- `simos-app/src/main/java/.../app/gui/GuiServer.java`：`POST /api/sd/start-decision`（经 `CoreSimos.submit`，`initiator="player:gui"`）。
- `simos-app/src/main/java/.../app/tools/SimosToolSource.java`：GM 桶加该工具；决策人桶**不加**。
- `simos-app/src/main/java/.../app/tools/read/CatalogTool.java`：`PAYLOAD_HINTS` 加该 type（**构造期强制**，漏了 app 起不来）。
- `simos-app/src/test/java/.../app/McpCoverageTest.java`：**双向载荷断言**（新 type 可提交 + head 前进）。
- sd 测试 + app 测试（审批路径）。

**bite-sized 步骤**
1. 定义命令 + handler（载荷：`decisionMakerId` + 可选说明；写一条"已发起"记录）。
2. `CatalogTool.PAYLOAD_HINTS` 加项。
3. `SimosToolSource` GM 桶加工具（★ 若 T4 的复合桶形态不允许，需一并调整）。
4. `GuiServer` 加端点（用户路径）。
5. `McpCoverageTest` 喂载荷（★ 其真实提交序列有**时序设计**——参考 M5/M8 的 `McpCoverageTest` 写法）。
6. 测试审批路径：GM Agent 发 ⇒ 未批不落 revision；批后 +1（spec `C18`）。
7. 跑定向。

**判据**
- spec `C18`/`C19`。
- `McpCoverageTest` 绿且新 type 在列；`CatalogTool` 构造期不缺项。

**变异靶子**
- **m1**：`sd.StartDecision` 未经 `submit`（绕过铁律 2）⇒ `AppWritePathGuardTest` / `C18` 红。
- **m2**：GM Agent 路径跳过审批（直接生效）⇒ `C18`② 红。
- **m3**：决策人桶错加该工具 ⇒ `C18`③ 红。
- **m4**：`PAYLOAD_HINTS` 漏项 ⇒ `CatalogTool` 构造抛（app 起不来）。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t10-evidence/`。

**提交信息样式**：`feat(sd,app): 开始决策入口与权限（T10）`

---

### T11 富世界地图 + 区域复刻（`--demo` 升级）

**目标**：`--demo` 种入**复刻 `v17levant`** 的富世界（地形 + 河流 + 区域信息）；非空库不覆盖；**绝不录 Info**。

**依赖**：无。

**文件清单**
- ★ `tools/gsimap_import.py`：**扩导入器**（D12 已裁）——把 legacy 连通性**忠实搬到** simos `edges`：`edgeTags[dir] → EdgeRef`（方向码 `0`~`5` 按旧仓 `edgeKey` 语义解析成相邻 hex 对，`kind="river"`）；★ `riverMask` 与 `edgeTags` **计数相同（均 248）** ⇒ 以 `edgeTags` 为准、`riverMask` 仅交叉校验（不一致 ⇒ 报错）；生成区域（`provinces`，`tag` 取真值 **97 Nation + 1 王国**）。
- ★ **地形映射（含 `lowland`/`swamp` 缺口）**：`v17levant` 实测 **`lowland` 16933 格（28.6%）+ `swamp` 99 格**，simos 词表**两者皆无对应**。现有 `TERRAIN_MAP` **两者都 → `plains`**（`tools/gsimap_import.py:39`/`:44`），但 **`LOSSY_KEYS` 只含 `swamp`**（`:48`）⇒ **本步必须把 `lowland` 补进 `LOSSY_KEYS`**（否则是"未标 LOSSY 的静默丢失"）。**默认**：lossy → `plains`，并在产出资源/文档里**显式标 LOSSY**（记录格数）。★ 若建议**新增地形词表**（如 `lowland`）⇒ 标 `[待裁]`，**默认不新增**（新增会动 `TerrainType`/`TerrainCatalog` 与 M6 映射表，超本阶段范围）。
- 新 `simos-app/src/main/java/.../app/demo/RichWorld.java`（或等价）：从**签入的紧凑资源**构造 `SimulationState`（供 `--demo`）。
- 新资源（如 `simos-app/src/main/resources/worlds/v17levant.json`）：由离线脚本产出、签入。
- `ShellMain.java`：`--demo` 改用 `RichWorld`（`DemoWorld` 保留为测试夹具，见判据）。
- app 测试：新 `RichWorldTest.java`（计数/直方图/edges 对拍）+ 既有 `DemoWorldTest` 不动。
- ★ 对拍脚本：`tools/` 下的计数校验（不入 reactor）。

**bite-sized 步骤**
1. 扩 `gsimap_import.py`：**连通性融合优先级 `edges` > `edgeTags` > `riverMask`**（`v17levant` 是 `edges` 空、`edgeTags` 权威）；`edgeTags → EdgeRef` 忠实转换；`riverMask` 交叉校验；区域生成。
2. ★ 地形映射：`lowland`/`swamp` → `plains`（**LOSSY，显式记录**）；若走"新增词表"分支则先落 `[待裁]` 裁定。
3. 产出紧凑资源并签入（含 hexes/terrain/provinces/edges/river 标注）。
4. 写 `RichWorld`（确定性构造，照 `DemoWorld` 形制）。
5. `ShellMain --demo` 切到 `RichWorld`。
6. 写对拍测试（**hexCount 59223 / provinces 98 / 地形直方图 7 类 / 河流 `edges` 逐值**）。
7. 跑定向。

**判据**
- spec `C23`/`C24`/`C25`/`C26`。
- ★ `InfoSystem` / `SdInfoEntry` 条目数**不因富世界而变**（`C26`）。
- ★ 连通性：导入后的 `edges` 与源档 `edgeTags` **逐边对拍**（来源 = `v17levant` 权威 `edgeTags`）。
- ★ 地形：`lowland`/`swamp` 的格子落在 `plains` 且**总数可核对**（lossy 有账）。
- `DemoWorldTest`（5 条）**不动仍绿**（`DemoWorld` 仍在）。

**变异靶子**
- **m1**：`RichWorld` 不写 `edges`（丢河流）⇒ `C23` 河流对拍红。
- **m2**：`ShellMain` 去掉"空库才种"判定 ⇒ `C25` 红（覆盖已有世界）。
- **m3**：`RichWorld` 把文本塞进 `InMemoryInfoSystem` ⇒ `C26` 红。
- ★ **m4**：导入器把 `edges` 的融合优先级写反（`edges` 空时不回退 `edgeTags`）⇒ `edges` 对拍红。
- ★ **m5**：`riverMask` 与 `edgeTags` 不一致时静默取一 ⇒ 交叉校验用例红。
- ★ **装置注意**：`tools/**` 不在 reactor ⇒ 对拍脚本必须显式断言计数（非零），防"扫描为空恒真"。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t11-evidence/`（含对拍脚本产物 + md5）。

**提交信息样式**：`feat(app): 富世界（v17levant 复刻）与 --demo 升级（T11）`

---

### T12 富世界文档 `.md` 产出（绝不录 Info）

**目标**：把 `v17levant` 的 `checkpoints` 文本整合成 `docs/worlds/v17levant/*.md`，供另一个 Agent 阅读；**不录 Info**。

**依赖**：T11。

**文件清单**
- 新 `tools/v17levant_docs.py`（离线，标准库 only）：读**最全副本** `~/DevMosire/testspace/worlds/v17levant_2/nodes/` 的 node JSON `checkpoints`，写 md。
- 产出 `docs/worlds/v17levant/` 下分文件（D11 默认）：`factions.md` / `worldview.md` / `narrative.md` / `map.md` / `characters.md` / `internal.md`。
- 新 `tools/check_v17levant_docs.py`（或断言脚本）：计数校验。
- app 测试（可选）：仅 `C26` 的 Info 不变量（可与 T11 合并断言）。

**bite-sized 步骤**
1. 写脚本：按 **6 类** checkpoint 分文件；`narrative` 按节点/回合分节。
2. ★ 在 `map.md` 里**明确标注**：**16 个**有名势力有资料、**82 个** `区域NNN` 是程序化噪声（无文本）。
3. 产出 md 并签入。
4. 写计数校验：md 里含 **615** 条 checkpoint 元素（narrative 170 / map 318 / internal 67 / factions 52 / worldview 6 / characters 2，逐值）。
5. 跑校验脚本。

**判据**
- spec `C27`。
- 产出目录**只有 `.md`**（`find` 断言）。
- 计数逐值（615 = 170/318/67/52/6/2）。
- ★ **16/82 标注**：有名势力 vs 程序化噪声区域的划分与实测交集一致。

**变异靶子**
- **m1**：脚本漏写 `narrative` 一类 ⇒ 计数校验红。
- **m2**：把噪声区域标成有文本 ⇒ "无文本标注"断言红。
- ★ **装置注意**：脚本**不在 reactor** ⇒ 校验脚本必须**先断言"读到的源档存在且非空"**再下结论（防 ugrep/空扫描同族陷阱）。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t12-evidence/`。

**提交信息样式**：`docs(worlds): v17levant 文档 md 产出（T12）`

---

### T13 关账

**目标**：spec §七 `C1`~`C34` 逐条实测；`[待裁]` 项处置记录；门禁现场重算；关账报告。

**依赖**：全部。

**文件清单**
- `.superpowers/sdd/2026-09-21-webui-stage-fix/task-13-final-report.md`（含"我未能核实的"清单）。
- 台账 `progress.md` 收口。

**bite-sized 步骤**
1. 逐条核 `C1`~`C34`，给实测值 + 观测点。
2. 门禁现场重算（`paste -sd+ | bc`，只取汇总行）；记"第几次尝试"。
3. R 点验（R1~R13 + D1~D13 处置；★ D2/D12 已裁 ⇒ 核其落实）。
4. 遗留清单（spec §八）。
5. 报告落盘。

**判据**
- `C31`~`C34` 实测在案；无占位符。
- 门禁 rc=0、8/8 `SUCCESS [`、`[ERROR]` 0、`BugInstance size is 0` ×7、前端全绿。

**变异靶子**：本任务不新增生产代码，无变异靶子（若有小修则配对应靶子）。

**证据落点**：`.superpowers/sdd/2026-09-21-webui-stage-fix/t13-evidence/`。

**提交信息样式**：`docs(webui-stage-fix): 关账——判据逐条实测 + 门禁现场重算（T13）`

---

## 四 执行顺序建议（照任务书）

1. **先做不依赖新查询面的**（P 阶段 + Q 阶段）：T1 撤审批/通知栏 → T2 子选项 → T3 连通性 → T4 端口拓扑。
2. **再做依赖新造的**：T5 决策人查询面 → T6 redaction → T7 决策模式 → T9 信号 → T10 开始决策入口。
3. **T8 GM 界面**可在 T4 完成后任何时点做（与 T5~T7 文件面基本不相交，但一次只跑一个 Maven ⇒ 实际串行）。
4. **最后富世界**：T11 → T12（最重、可独立推进、改 `tools/` 与 `DemoWorld` 面）。
5. **T13 关账**。

---

## 五 `[待裁]` 项对计划的影响（供用户裁后回填）

| 待裁 | 影响的任务 | 裁后要回填的位置 |
|---|---|---|
| **D1** `/api/approvals` 保留与否 | T1 | 若收，T1 加"撤 api.approvals + 改 `write-allowlist.test.cjs` 被측对象" |
| **D2/D8** 现有口"加"vs"换"、复合桶形态 | T4 | ★ **D2 已裁="加"**（见 spec §〇.1；T4 按"加"落，通用写不动）；**D8**（复合桶形态）仍待裁 |
| **D3** 决策人口默认端口 | T4 | 端口常量 |
| **D4** 子页命名 | T7 | label 文案 |
| **D5** "开始决策"入口形态 | T10 | 若选 GUI 专用（不合铁律 2）⇒ T10 整个人改；**默认新命令** |
| **D6** GM Agent 路径 | T10 | 默认审批门链 |
| **D7** `due` 公式 | T9 | 公式与字段 |
| **D9** redaction 修/记限制 | T6 | 若记限制 ⇒ T6 缩小为"清单 + fail-closed"，`C28`/`C29` 降级 |
| **D10** 通知栏落点 | T1 | CSS |
| **D11** 文档目录/文件划分 | T12 | 脚本输出路径 |
| **D12** 导入路径 | T11 | ★ **D12 已裁="扩 M6 导入器"**（见 spec §〇.1）；T11 按"扩导入器 + `edgeTags→EdgeRef` 忠实转换 + `riverMask` 交叉校验"落 |
| **D13** 决策人信息结构 | T5/T7 | 视图字段 |
| **D14**（三.3 衍生）自动建格 / 相邻校验 | T3 | 手势与 handler 校验 |

---

## 附：本计划与既有文档的关系

- 本文**只写计划**；`[待裁]` 项（spec §〇.2）的实现步骤待裁后回填。
- 门禁基线以**开工现场重算**为准（§一.3 的表是 2026-09-21 阶段 E 值，且**任务书写的 HEAD 已陈旧**）。
- 本计划**不修改** brainstorm / research / spec 三份既有文档（要补充写进 spec 并注明"补充"）。
