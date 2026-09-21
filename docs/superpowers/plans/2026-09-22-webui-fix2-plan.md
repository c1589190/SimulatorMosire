# WebUI 阶段修复（第 2 批）—— 实现计划（bite-sized）

> 配套 spec：`docs/superpowers/specs/2026-09-22-webui-fix2-design.md`（**§〇 裁定表**）。
> 前置：`docs/superpowers/specs/2026-09-22-webui-fix2-feedback.md`（用户原话）、上一阶段 `-plan.md`（纪律/门禁口径/证据落点）。
> ★ 本文只写计划，不含生产代码；`[待裁]` 无（本批 spec §〇 已全裁）。

## 〇 通则（每个任务都适用）

- **worktree 隔离**：`/home/cna/SimulatorMosire/.claude/worktrees/wsf2`，分支 `wsf2/u1u2u3u5`，基线 `3cb823f`。
- **一次只跑一个 Maven**；`clean verify` **走前台**；被杀轮留档不删、**既不是红也不是绿**；记结果**必须带"第几次尝试"**。
- **证据落点**：`.superpowers/sdd/2026-09-22-webui-fix2/`（`tN-evidence/`）；台账 `progress.md`。`git add` 显式路径、**不加 `Co-Authored-By`**、中文提交信息、**不 `git add -A`**、**不 kill 5818/5817**。
- **门禁口径**：`./mvnw clean verify` **rc=0**、模块判据 `SUCCESS [` **8/8**、`[ERROR]` 0、`BugInstance size is 0` ×7、前端 `tests=N pass=N fail=0`（**基线现场重算**；本任务新增断言 ⇒ `run-gate.cjs` 的 `MIN_TESTS` 与 `gate-contract.test.cjs` 的 `MIN_ASSERTIONS`/`REQUIRED_FILES` **三处同改**）。
- **变异九道门禁**：干净世界 / 变异体与原件的 md5 不同且自证 / 白名单推成目标文件 / 清陈旧状态 / 断言真的跑到（TAP 有 `# tests` 与 `# fail`）/ 红点落**被保护断言** / `cp` 逐字节还原并比 md5 / 日志自指（把本轮字节 md5 追加进日志）/ 红没红都要问为什么。
- **改既有文件 ⇒ 按裁定 42 自带重跑轮**（本批改 `map.js`/`panels.js`/`app.js`/`index.html`/`styles.css`/`ApiViews.java` ⇒ 受影响的旧变异体要重跑）。
- **e2e 若跑**：真 Chromium（`executablePath=/home/cna/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome`，playwright 1.63 可驱动）+ `--noproxy '*'` + 点击前 `scrollIntoViewIfNeeded()` + **点击后断言"真的发生了"**。

## 一 目标与总判据

修 U1（地形压暗）/ U2（区域名）/ U3（选择粒度）/ U5（三栏布局）；**U4 不做**。判据见 spec §一~§四。

## 二 任务

### T1 —— U1 地形压暗（前端）
1. `map.js`：加常量 `REGION_DIM_COLOR/ALPHA/MODES` + 纯函数 `terrainDimAlpha(mode)`。
2. `render()`：terrain 之后、`paintHighlights` 之前画全画布 scrim（屏幕空间），累加 `dimPasses`。
3. `debug()` 暴露 `terrainDimAlpha`、`dimPasses`；加 `pixelAt(cssX,cssY)` 取色钩子（e2e 用）。
4. 测试：纯函数三模式返回值；源码级断言 scrim 在 `paintHighlights` 之前。
5. e2e：同一点 view vs region 像素，region 更暗。

### T2 —— U2 区域名（服务端 label + 前端绘制）
1. `ApiViews.java`：纯函数 `regionLabelHex(Region)`（`long` 累加质心、空 ⇒ null）；`mapOverview` 的 region item 加 `label`。
2. Java 测试：`MapOverviewBlocksTest`（或新用例）断言 label == 手算质心、两次调用逐字节相同、空区域 null。
3. `map.js`：纯函数 `regionLabelLayout`；`paintRegionNames`；`regionNamesEnabled`（默认开 + localStorage 守卫）；`regionNameDebug()`；绘制插到 `paintRegionOutlines` 之后。
4. `index.html`：顶栏加 `#region-name-toggle`（默认 checked）。
5. 测试：`regionLabelLayout` 逐值；静态源码 `strokeText`+`fillText`；toggle 存在。
6. e2e：缩放后在某 label 屏幕坐标采样到近白像素。

### T3 —— U3 选择粒度（前端）
1. `app.js`：state 加 `highlightKind`；`setHighlightRegions(ids, kind)`；`setMode` 重置。
2. `map.js`：`buildRegionHighlightPlan` 增 `singleFocusAlpha` / `fadeScope`；`REGION_SINGLE_HIGHLIGHT`；palette 带 `tag`；`reloadHighlights` 按 kind 选参数；`selectRegionOfHex` 按 owner 数分档；`regionViewDebug` 加 `highlightKind`。
3. `panels.js`：tag ⇒ `("group")`、单项 ⇒ `("single")`。
4. 测试：single 计划逐值（焦点 0.62 / 同 tag 兄弟 0.13 / 异 tag 零条目）；group 计划**逐值不变**；来源分档。
5. e2e：同区域 tag 全选 vs 单选中，像素更饱和。

### T4 —— U5 布局（前端）
1. `index.html`：`#right-panel` 加 `data-modes="region region-edit decision"`。
2. `styles.css`：`align-items: flex-start`；`.col-left/.col-right` 内容宽。
3. 测试：静态断言（data-modes / 无 `flex: 0 0 300px`）。
4. e2e：view 模式右栏 display none；region 模式可见；左栏宽 < 300。

### T5 —— 门禁与变异
1. 新增 JS 测试文件 `webui-fix2.test.cjs` 进 `REQUIRED_FILES`，`MIN_TESTS`/`MIN_ASSERTIONS` 同步。
2. 变异：U1 删 scrim / U2 不画名 / U3 单区域不更亮 / U3 淡色不限同 tag / U5 右栏去掉 data-modes / U5 恢复固定宽 ⇒ 各应红在**被保护断言**。
3. 受本批改动影响的**既有**变异体（若有）重跑。

### T6 —— 关账
1. `./mvnw clean verify` 前台；贴 8/8 SUCCESS 逐模块 + 基线现场重算 + 增量 + 第几次尝试 + **最终绿轮文件名**。
2. 证据目录 + 报告（含 §我未能核实的）+ commit 短 SHA。
