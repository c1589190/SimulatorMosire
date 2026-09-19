# M7 实现计划 —— WebUI 可视化骨架

> 设计见 `docs/superpowers/specs/2026-09-19-webui-design.md`（**已批准**：U1 + S1~S10 + 切分）。
> 本计划给 **bite-sized 步骤**（真实路径、真实命令）。判据见 spec §〇.1；护栏见 spec §七。
> **M8**（地图编辑写面）另起 spec 与计划。

## 〇 执行纪律（沿用，逐条有效）

- **子代理**：`deepseek-flash-go`；**单任务隔离 worktree**（`.claude/worktrees/m7tN`，分支 `m7/tN`）；**一次只跑一个 Maven**。
- **同一文件被两任务改 ⇒ 串行**；后关账者必须**重跑前者的变异轮**。
- **绝不 `git add -A`**；`.superpowers/**` 用 `-f`；**该推就推**；禁 `mvn install`。
- **每任务必带**：① 故意违规用例 + 变异自证（九道门禁）；② 任务内全量 `clean verify`；③ 证据目录 `tN-evidence/`；④ `tN-report.md`（含"我未能核实的"）。
- **本计划的"原子性"约定**：**每个任务结束时全量门禁必须绿**，故**破坏性响应形状变更必须与消费它的前端改动同批**（见 T1/T2 的分工）。

## 一 任务总表

| # | 任务 | 依赖 | 判据 | 串行约束 |
|---|---|---|---|---|
| T1 | Core 只读扩展 + 只读 API（**纯增量**） | — | ②③④ 的后端面 | 必须先做（其余全依赖） |
| T2 | 单页骨架 + 模式栏 + 资源重构（含 `terrainTypes` 形状切换） | T1 | ① | 与 T3~T7 共享前端文件 ⇒ 串行 |
| T3 | 时间轴组件（U1） | T1,T2 | ② | 改 `timeline.js`/`app.js` |
| T4 | Canvas 升级（缩放平移/区域填充/点选联动） | T2 | ③ | 改 `map.js` |
| T5 | 左栏详情 + 单位倒树 | T1,T4 | ③⑥ | 改 `panels.js`/`unitTree.js` |
| T6 | 区域查看面板 | T1,T4 | ④ | 改 `panels.js`（⇒ 与 T5 串行） |
| T7 | 单位移动与编辑模式 | T4,T5 | ⑤ | 改 `app.js`/`map.js`（⇒ 与 T3/T4 串行） |
| T8 | M7 关账 | T1~T7 | 全部 | 控制器内联 |

**批次**：`T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8`（T5/T6 因共享 `panels.js` 串行；T3/T4/T7 因共享 `app.js`/`map.js` 串行）。

---

## T1 Core 只读扩展 + 只读 API（**纯增量，不改任何既有响应形状**）

**交付物**
1. `CoreSimos.revisions(BranchId)` + `Timeline.listRevisions(BranchId)`（新 SQL：`SELECT branch, revision, parent_branch, parent_revision, tick, calendar_label, command_id, correlation_id, initiator, command_type, changeset_json FROM revisions WHERE branch = ? ORDER BY revision ASC`，列名**以 `SqliteStore` 的 DDL 为准**）。
2. `GET /api/timeline?branch=` → `{branch, head, nodes:[{revision,tick,commandType,initiator,parent:{branch,revision}|null}]}`；**不含 `changesetJson`**；分支不存在 ⇒ 404。
3. `/api/map/hex` 响应**新增** `region`（`RegionId` 字符串或 null，走既有 `MapResolver.regionOfHex`）与 `terrainType`（完整 `TerrainType` 定义）。
4. `/api/map/overview` 的**每个 region 项新增** `meta`（`color/tag/description/annexedBy`）。
5. **新增** `GET /api/map/region/{id}?branch=&revision=` → `{id,name,meta,hexCount,hexes:[{q,r}…]}`；不存在 ⇒ 404。
6. 测试：`simos-core` 的 `TimelineTest` 扩条（`listRevisions` 的分支隔离 + 升序）；`GuiApiTest` 扩条（timeline 端点、hex 新字段、region 端点、overview 的 meta）。

**步骤**
1. `simos-core/.../timeline/Timeline.java`：加 SQL 常量 + `public List<RevisionRow> listRevisions(BranchId branch)`（**只读**，`store.inTransaction`）。
2. `simos-core/.../CoreSimos.java`：加 `public List<RevisionRow> revisions(BranchId branch)`（纯委托，**不触发 `sealIfNeeded`**——与 `branches()`/`head()` 同口径）。
3. `simos-core/src/test/.../TimelineTest.java`：加 `listRevisionsIsPerBranchAndAscending`（**造两个分支**，断言各自只回自己的节点且升序）。
4. `simos-app/.../gui/ApiViews.java`：加 `timeline(List<RevisionRow>)`；`mapHex(...)` 加 `region`/`terrainType`（terrainType 走 `TerrainCatalog.of(key)` 或 `map.terrainTypes().get(key)`——**以状态里的实际词表为准**，不得硬编码）；`mapOverview(...)` 的 region 项加 `meta`。
5. `simos-app/.../gui/GuiServer.java`：加 `GET /api/timeline`（`GET_ROUTES` + `handleGet` + `timelineReply`）；加 `/api/map/region/` 前缀分支；`/api/map/hex` 接线 `MapResolver.regionOfHex`。
6. `QueryService`：若需新方法（如 `regionAt`/`regionDetail`），**只读**加，口径与既有 `stateAt` 一致。
7. `simos-app/src/test/.../gui/GuiApiTest.java`：扩条（4 条以上：timeline 形状与 404、hex 的 region/terrainType、overview 的 meta、region 端点的 hex 集合与 404）。
8. `./mvnw -q spotless:apply` → 定向测试 → `./mvnw clean verify`。

**护栏与变异**
- **R3**（时间轴节点与库一致）变异：`listRevisions` 去掉 `WHERE branch=?` ⇒ 分支隔离断言红。
- **R5**（hex 详情完整性）变异：`regionOfHex` 换成常返 `Optional.empty()` ⇒ `region` 断言红。

**证据** `t1-evidence/`：定向/全量日志 + 变异轮（含九道门禁自指）+ `t1-report.md`。

---

## T2 单页骨架 + 模式栏 + 资源重构

**交付物**
1. `GET /` 回**主应用**（`index.html` 变成工作台；旧 `map.html`/`unit.html`/`social.html` **仍可访问**）。
2. 顶栏五模式按钮：`常规查看 / 区域查看 / 地图编辑(disabled) / 区域编辑(disabled) / 单位移动与编辑`；禁用的两个带 `data-milestone="M8"` 与 `disabled`。
3. `webui/` 重构：新增 `timeline.js`、`panels.js`、`unitTree.js`；`app.js` 变**状态机**（`{mode, branch, revision, selection}`）；`styles.css` 加三栏 + 底栏布局。
4. **`terrainTypes` 形状切换**：`/api/map/overview` 的 `terrainTypes` 由 `[key…]` 改为 `[完整定义…]`（**破坏性**，与前端重写同批），并**同时**改掉旧 `map.js` 里对它的用法（图例）——保证任务末门禁绿。
5. 测试：`WebuiAssetsTest` 扩条（主应用在 `/`、五按钮在册、两个 disabled 带 M8 标记、新 js 在册且非空、无 CDN/绝对 URL 判定器仍自证）。

**步骤**
1. 先写 `WebuiAssetsTest` 的新断言（红）。
2. 改 `index.html` → 工作台骨架（模式栏 + 三栏 + 底栏容器）。
3. 加 `app.js` 状态机 + 模式切换（只切可见性/可用性）。
4. 拆 `panels.js`/`timeline.js`/`unitTree.js` 骨架（空实现 + 挂载点）。
5. 改 `styles.css`（三栏 + 底栏 + 深色主题沿用）。
6. 改 `GuiServer`/`ApiViews` 的 `terrainTypes` 形状 + 同步修旧 `map.js` 图例。
7. `spotless:apply` → 定向 → `clean verify`。

**护栏与变异**：**R6**（资产纪律）变异：往任一 js 塞 `https://cdn…` ⇒ 判定器红；**R1**（只读）变异：让预览端点误走 `submit` ⇒ 行数断言红（若 T2 尚无预览路径，则该变异落在 T3）。

---

## T3 时间轴组件（U1）

**交付物**：底部时间轴条——按 `/api/timeline` 画节点（`rev n · 命令短名`）；游标可拖动 ⇒ 改 `{revision}` ⇒ **所有面板**改走 `?branch=&revision=`；**末端判定**：`cursor.revision == head` 才启用"创建节点/分岔"，否则置灰；分岔后刷新 `/api/state`+`/api/timeline` 出现第二条线；409 ⇒ 提示"末端已移动"并重取 head。

**步骤**：写前端断言（末端置灰逻辑可被单测的纯函数）→ 实现 `timeline.js` → 接 `api.timeline()`/`api.fork()`/`api.advance()` → 接 conflict 处理 → `spotless:apply` → 定向 → `clean verify`。

**护栏与变异**：**R2** 变异：去掉"末端才可写"的判定 ⇒ 前端断言红；409 静默吞掉 ⇒ 红。

---

## T4 Canvas 升级

**交付物**：缩放（滚轮）+ 平移（拖拽）；**区域填充**（从 `/api/map/region/{id}` 拉 hex 集合，按模式高亮）；点选（hex/单位）联动左栏；地形色**取自后端 `terrainType.color`**（删除硬编码 15 色表）。

**步骤**：写几何单测（`pixelToHex` 往返、缩放系数）→ 实现缩放平移 → 区域填充层 → 点选联动 → `spotless:apply` → 定向 → `clean verify`。

**护栏与变异**：色表变异：把 `terrainType.color` 改成常量 ⇒ 断言红（证明颜色确实来自后端）。

---

## T5 左栏详情 + 单位倒树

**交付物**：左栏按模式显示 hex 详情（`terrain/height/region/单位/人口`）或单位详情；单位详情含**倒置树**（**根在下**）、**分岔点加粗放大**、点分岔点展开该分支详情。

**步骤**：写 `UnitTreeTest`（纯函数组树：父子/深度/分岔点识别，**给定固定 units 集合逐节点断言**）→ 实现 `unitTree.js` → 接左栏 → `spotless:apply` → 定向 → `clean verify`。

**护栏与变异**：**R7** 变异：少挂一个子 ⇒ 深度断言红；把分岔点判定改成 `>=1` 子 ⇒ 断言红。

---

## T6 区域查看面板

**交付物**：右栏按 `RegionMeta.tag` 分组（无 tag 归"未标注"）；点区域 ⇒ 仅该区高亮；点标签 ⇒ 该标签下**所有**区域一起高亮。

**步骤**：写分组纯函数断言（含 null tag）→ 实现 → 接 `map.js` 的高亮层 → `spotless:apply` → 定向 → `clean verify`。

**护栏与变异**：**R4** 变异：分组漏 null tag ⇒ 断言红。

---

## T7 单位移动与编辑模式

**交付物**：该模式下点单位 ⇒ 选中；点目标格 ⇒ 发 `unit.PlaceAt`（载荷 `{id, hex:{q,r}}`）；"下路线"按钮 ⇒ `unit.PlanRoute`（**M7 按逐格相邻口径**，稀疏路点缺口如实记）；编制改动表单（`ReparentUnit`/`CreateUnit`/`DisbandUnit`/`SetStrength`）⇒ 经 `POST /api/command` 提交；成功后 `head` 前进并刷新。

**步骤**：实现模式交互 → 接命令提交（**复用既有 unit 命令，不新增 handler**）→ 冲突处理 → `spotless:apply` → 定向 → `clean verify`。

**护栏与变异**：**R8** 变异：在新前端里直连 store/timeline ⇒ app 源码扫描红（沿用 M5 R1 的扫描用例）。

---

## T8 M7 关账（控制器内联）

1. 判据①~⑥**逐条实测值**（不是"通过"）。
2. 护栏 R1~R8 点验（每条有变异自证；存活项如实存档）。
3. 主树全量 `./mvnw clean verify` 绿 + 数字（逐模块对差）。
4. `CLAUDE.md`：M7 行（判据实测值 + 遗留）+ 模块表若有变 + 推送状态。
5. `task-8-final-report.md`（含"我未能核实的"）。
6. 提交推送 + 清理 worktree。
