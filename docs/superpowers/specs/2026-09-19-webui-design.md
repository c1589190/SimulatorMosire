# M7 设计 —— WebUI 可视化骨架（Simos 工作台）

> **范围**：把 M5 交付的三页只读 WebUI 改造成**单页可视化工作台**：开场即地图 + 顶部五模式栏 + 底部线型时间轴 + 左栏详情 + 右侧区域面板 + 单位倒树。
> **本里程碑只做"只读 + 单位操作"**；**不含任何地图写面**（地图编辑/区域编辑归 **M8**）。
> 前置：M0~M6 已完成。裁定见 §〇（用户裁定 U1 + 控制器裁定 S1~S10，**已批准**）。

---

## 〇 裁决

### 〇.1 判据（**每条都要可判定**；这是 M7 的关账口径）

| # | 判据 | 可判定形态（落点） |
|---|---|---|
| **①** | **开场即地图**：`GET /` 回来的**就是**地图工作台（不再是链接首页）；顶栏**五个模式按钮齐全**，其中"地图编辑""区域编辑"**可见但禁用**（标"归 M8"） | `WebuiAssetsTest`（`/` 指向主应用、五按钮在册、两个带 disabled 标记）+ `GuiApiTest`（`GET /` 200 且含模式栏标识） |
| **②** | **时间轴**：底部一条线，节点数 = `head`；**拖动到任一节点**⇒ 全部面板切到该 revision 的**只读**快照（**不产生任何 revision**）；**仅当游标在分支末端**时"创建节点/分岔"可用，拖到中间节点**两者置灰** | `TimelineApiTest`（`/api/timeline` 的节点与 `listRevisions` 逐值一致）+ `GuiApiTest`（预览前后 `revisions` 行数不变）+ 前端断言（末端判定）|
| **③** | **点选详情**：点 hex ⇒ 左栏出 `{q,r,terrain(含定义),height,region,该处单位,人口}`；点单位 ⇒ 左栏出单位详情 | `GuiApiTest`（`/api/map/hex` 回 `region`/`terrainType`；`/api/map/region/{id}` 回 hex 集合） |
| **④** | **区域查看**：右栏按 `RegionMeta.tag` **分组**列出区域（无 tag 归"未标注"）；点区域 ⇒ 仅该区域高亮；点标签 ⇒ 该标签下**所有**区域一起高亮 | `GuiApiTest`（overview 的 region 项含 `meta`）+ 前端断言（分组结果与期望逐项相等） |
| **⑤** | **单位移动与编辑**：地图上点单位 + 点目标格 ⇒ 发 `unit.PlaceAt`（或 `unit.PlanRoute`）；编制改动（`ReparentUnit`/`CreateUnit`/`DisbandUnit`/`SetStrength`）⇒ 提交成功且 `head` 前进 | `GuiApiTest`（`POST /api/command` 后 `head` +1，且 `initiator=player:gui`） |
| **⑥** | **编制倒树**：左栏单位详情以**倒置树**展示隶属（**根在下、下级向上生长**），**分岔点加粗放大**，点分岔点 ⇒ 展开该分支下的单位详情 | `UnitTreeTest`（给定固定 units 集合 ⇒ 树的父子结构与深度逐节点相等；分岔点识别正确） |

### 〇.2 用户裁定 U1（时间轴分岔）

**只从分支末端分岔**（**零 Core 改动**）。拖到中间节点时"创建节点/分岔"置灰。
- 依据（已核实）：`head(branch)` 是 `SELECT MAX(revision)`，纯计算；`CoreSimos.writePostCommitCheckpoint` 对 `ForkBranch` 已做 `maybeWriteCheckpoint(source, expectedRevision)`——**replay 出分岔点状态再落 checkpoint**。两侧都"随时可算"。
- **`expectedRevision` 不拆**：U1 下"CAS 期望"与"分岔点"恒等，拆字段是 M8+ 才可能的选项（记为开口项）。

### 〇.3 控制器裁定 S1~S10

见台账 `.superpowers/sdd/2026-09-19-webui-plan/progress.md` §二（用户已批准，原文在案）。要点：不新建模块；唯一 Core 改动是 S2 的只读列表法；事件读面不做；区域 hex 集合懒拉；单位倒树纯前端。

### 〇.4 取代说明（相对 M5 spec）

| M5 spec 原文 | M7 取代 |
|---|---|
| §8.3「**前端三页**（无构建）」：`/map` `/unit` `/social` 三张独立页 | **单页工作台**（`/` = 地图 + 模式栏 + 时间轴 + 面板）。旧三页**暂留为调试页**（含命令表单与人口序列，是现有回归面），关账时再定退役 |
| §8.2「`/api/state` 回 `{branches, heads, current meta}`」 | **不动**，新增 `/api/timeline` 作为"节点清单"专门端点 |
| §8.3「`/map`：只读 Canvas……」 | 升级为**可缩放平移**的 Canvas + 区域填充 + 点选联动（**仍只读**） |
| S2「**不做地图编辑**」（M5 递延到"M6+"） | 该递延**在 M7 到期但顺延到 M8**；M7 只放**禁用占位** |

### 〇.5 不做（M7）

事件读面（点节点看"这一跳发生了什么"）／撤销重做／审批面板改动／TLS 鉴权／区域标签的增删（`tag` 只是 `RegionMeta.tag` 字符串）／单位"类型"字段（**领域里不存在**）／地图编辑与区域编辑两模式的**功能**（只留禁用按钮）／CJK 字体等运行环境问题。

---

## 一 交付物

| 层 | 交付 |
|---|---|
| **Core**（唯一改动） | `Timeline.listRevisions(BranchId) -> List<RevisionRow>`（新 SQL：`SELECT … WHERE branch=? ORDER BY revision ASC`，**只读**）+ `CoreSimos.revisions(BranchId)` 暴露 |
| **app 只读面** | `GET /api/timeline?branch=`；`/api/map/hex` 补 `region`+`terrainType`；`/api/map/overview` 的 `terrainTypes` 回**完整定义**、region 项补 `meta`；新增 `GET /api/map/region/{id}` |
| **app 前端** | 单页主应用（`/`）+ 模式栏 + 时间轴 + 左栏 + 右栏 + 倒树；沿用"无框架/无构建/无 CDN" |
| **测试** | `TimelineApiTest`（新）、`UnitTreeTest`（新）、`GuiApiTest`/`WebuiAssetsTest` 扩条 |

---

## 二 边界与依赖

- **模块**：不新建模块（S1）。只读扩展在 `simos-app`；M8 的地图命令在 `simos-map`。
- **Core 改动上限**：**只有 §一 的那一个只读方法**。不得碰写入语义、schema、`ForkBranch` 载荷。
- **前端纪律**（沿用 M5）：无 npm、无构建、无 CDN、同源；字节在 classpath `/webui/**`；由 `WebuiAssetsTest` 的判定器守住（含"判定器自证"用例）。
- **铁律**：所有写仍只经 `CoreSimos.submit`（本里程碑只有既有 unit 命令）；GUI 不打开任何 store/timeline 写面（R1 的 app 扫描继续有效）。

---

## 三 后端只读扩展（S2 / S6 / S7）

### 3.1 S2 时间轴节点清单

```java
// simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java
public List<RevisionRow> listRevisions(BranchId branch)   // SELECT ... WHERE branch=? ORDER BY revision ASC
// CoreSimos.java
public List<RevisionRow> revisions(BranchId branch)        // 纯委托，只读，不触发封存
```
- `RevisionRow` 已是 public record（`branch, revision, parent(Optional<StateRef>), timestamp, commandId, correlationId, initiator, commandType, changesetJson`）⇒ app 直接取需要的四五个字段，**不新增 DTO 类型**。
- **不暴露 `changesetJson`** 到 JSON（它含全限定类名、体积大且对 UI 无用）。

`GET /api/timeline?branch=main` →
```json
{"branch":"main","head":3,
 "nodes":[{"revision":1,"tick":7,"commandType":"core.Bootstrap","initiator":"system:bootstrap","parent":null},
          {"revision":2,"tick":7,"commandType":"unit.RenameUnit","initiator":"player:gui","parent":{"branch":"main","revision":1}}]}
```
- 分支不存在 ⇒ 404（与既有读端点口径一致）。

### 3.2 S6 hex 详情与地形定义

- `/api/map/hex?q=&r=` 的响应对每个 hex 增加：
  - `region`: `RegionId` 字符串或 `null`（用 **`MapResolver.regionOfHex`**——已存在、O(1)、当前 app 零调用点 ⇒ 接线即可）；
  - `terrainType`: `TerrainType` **完整定义** `{key,name,color,minHeight,maxHeight,food,gold,stone,moveCost,description}`。
- `/api/map/overview` 的 `terrainTypes` 从 `[key…]` 改为 `[{完整定义}…]`（按 `TerrainCatalog.KEYS` 的高度升序）。
  ★ **T2 执行期澄清（裁定 68）**：**数据源是状态里的 `map.terrainTypes()`（子集），不是无条件输出全 7 项**——词表只有一个来源（T1 口径），`TerrainCatalog.KEYS` **只用来定序**；词表外的 key 排末尾并按字典序（保证响应字节可复现）。若改成无条件全 7 项，就制造了"第二份真相"。
- **前端因此不再硬编码色表**（删掉 `map.js` 里那 15 项 `TERRAIN_COLORS`，改由后端权威给出）。

### 3.3 S7 区域读面

- `/api/map/overview` 的 region 项：`{id,name,hexCount}` → **补 `meta`**（`color,tag,description,annexedBy`，均可 null）。
- **新增** `GET /api/map/region/{id}?branch=&revision=` → `{id,name,meta,hexCount,hexes:[{q,r}…]}`（404 若不存在）。
  - **只回 hex 集合，不回 boundary 环**：M7 的"高亮/淡显"用 **hex 填充**实现（`RegionBoundary` 的顶点标签要前端做一次映射，留到需要描边的 Milestone）。
  - **懒拉**：`overview` 里塞 19441 格地图的区域 hex 会爆载荷 ⇒ 点选时才拉。

---

## 四 前端形态（单页工作台）

```
┌──────────────────────────────────────────────────────────────┐
│ 模式栏: [常规查看] [区域查看] [地图编辑(禁用)] [区域编辑(禁用)] [单位移动与编辑]  │
├───────────────┬──────────────────────────────┬───────────────┤
│  左栏          │        Canvas 六角网格         │   右栏         │
│  · hex 详情    │   （缩放/平移/点选/区域填充）    │ · 区域标签分组  │
│  · 单位详情    │                              │ · 区域列表      │
│  · 编制倒树    │                              │ · 待批（保留）  │
├───────────────┴──────────────────────────────┴───────────────┤
│ 时间轴: ●─●─●─●─▶  [创建节点] [分岔]     分支: main · rev 3 · tick 7 │
└──────────────────────────────────────────────────────────────┘
```

- **单页 + 模式状态机**：一个 `app.js` 持有 `{mode, branch, revision, selection}`；模式只改"哪些面板可见/可交互"，**不改数据来源**。
- **静态资源重构**（仍在 `webui/`，无构建）：拆为 `app.js`（状态机/模式栏/轮询）、`timeline.js`、`map.js`（Canvas+缩放平移+点选）、`panels.js`（左栏详情/右栏区域）、`unitTree.js`、`api.js`、`styles.css`。
- **旧三页**：`map.html`/`unit.html`/`social.html` **保留可访问**（调试与回归面），但不再是主入口。
- **可用性补项（控制器加，可推翻）**：Canvas **滚动缩放 + 拖拽平移**——19441 格的真实图没有它不可用；现有的"每格边长输入"升级为缩放系数显示。

---

## 五 时间轴交互（U1 的落地形态）

1. **画**：`GET /api/timeline?branch=` 拿节点 ⇒ 一条线 + 每节点一个点；节点标签 = `rev n · <commandType 短名>`；`/api/state` 拿 branches/heads 以支持多分支（分岔后出现第二条线）。
2. **拖**：拖动游标 ⇒ 更新 `{branch, revision}` ⇒ **所有面板**改走 `?branch=&revision=` 的只读端点（**现状已支持**）。**拖动不写任何东西**。
3. **末端判定**：`cursor.revision == head(branch)` ⇒ **才**启用"创建节点"（`POST /api/command` 或 `/api/advance`）与"分岔"（`POST /api/fork`）；否则**置灰**（U1）。
4. **分岔**：`POST /api/fork {source, expectedRevision=head, newBranch}` ⇒ 刷新 `/api/state` + `/api/timeline`，时间轴出现新分支（从分岔点长出）。
5. **冲突**：任何写返回 409 ⇒ 提示"末端已移动"并**自动重取 head**（不静默重试）。

---

## 六 单位倒树（判据⑥）

- **数据**：`/api/units` 已回每个单位的 `{id, name, parent, position, member, equipment, speed, mobilityPerMille, movement}`，且 `parent` 支持**任意多级** ⇒ **前端自组树，零后端改动**。
- **方向**：**倒置**——**根在下、下级向上生长**（按原话"倒着的树状图"字面实现）。⚠️ 若原意是普通自上而下的树，改一处方向即可（记为可推翻项）。
- **分岔点（"标大"）**：有 ≥2 个子节点的单位 ⇒ 该节点**加粗 + 放大**（字号/半径）。点它 ⇒ 展开/收起**该分支下的详情**（该节点 + 其全部后代）。
- **详情区**：点任意节点 ⇒ 左栏显示该单位详情（字段与 `/api/unit/{id}` 同源）。
- **孤例**：`parent` 为空的单位都是根；多根并列。环在领域层已被 `UnitState` 拒绝，前端**不做**环检测（不造第二份真相）。

---

## 七 护栏清单（每条都要**故意违规用例 + 变异自证**）

| # | 护栏 | 变异（期望红在哪） |
|---|---|---|
| R1 | **只读预览不写盘**：拖动时间轴后 `revisions` 行数不变 | 让预览端点误走 `submit` ⇒ 行数断言红 |
| R2 | **末端才可写**：`cursor != head` 时"创建节点/分岔"置灰；服务端并发下 409 不被静默吞掉 | 去掉前端置灰 ⇒ 前端断言红；吞掉 409 ⇒ 红 |
| R3 | **时间轴节点与库一致**：`/api/timeline` 的节点集合 == `listRevisions(branch)` 逐值 | 把 `listRevisions` 的 `WHERE branch` 去掉 ⇒ 节点数断言红 |
| R4 | **区域分组**：按 `tag` 聚合、无 tag 归"未标注"、点标签选中的集合 == 该 tag 下全部区域 | 分组时漏掉 null tag ⇒ 断言红 |
| R5 | **hex 详情完整性**：`region`/`terrainType` 两字段在场；`terrainType` 与 `TerrainCatalog` 逐字段相等 | 把 `regionOfHex` 换成常返 `null` ⇒ 红 |
| R6 | **前端资产纪律**：无绝对 URL / 无 CDN / 相对引用 / 判定器自证（沿用 M5 的 `WebuiAssetsTest` 判定器） | 往任一 js 塞 `https://cdn…` ⇒ 红 |
| R7 | **倒树结构**：给定固定 units 集合，树的父子与深度**逐节点**相等；分岔点（≥2 子）识别正确 | 少挂一个子 ⇒ 深度断言红 |
| R8 | **替代性**（防"新页面绕开旧护栏"）：`/` 的写**只能**经 `POST /api/command|advance|fork`，且 GUI 源码**仍无** store/timeline 写面（沿用 M5 R1 扫描） | 在新前端里直连 store ⇒ 扫描红 |

- **形态纪律**（沿用 CLAUDE.md 的九道门禁）：干净世界 md5 / 变异体字节不同 / 白名单推成**目标类名** / 清陈旧 `.class` / `COMPILATION ERROR`=0 且 `Tests run≥1` / surefire 报告 mtime 落轮内 / 红点落被保护断言 / `cp` 逐字节还原 / 日志自指。

---

## 八 任务分解（粗粒度；bite-sized 步骤见计划）

| # | 任务 | 依赖 | 备注 |
|---|---|---|---|
| T1 | Core 只读扩展 + 只读 API（S2/S6/S7） | — | 唯一碰 Core 的任务；先做，后面全依赖它 |
| T2 | 单页骨架 + 模式栏 + 静态资源重构 | T1 | `/` 变主应用；五模式（两禁用）；旧三页保留 |
| T3 | 时间轴组件（画/拖/末端写与分岔，U1） | T1, T2 | 判据②；R1/R2/R3 |
| T4 | Canvas 升级（缩放平移/区域填充/点选联动） | T2 | 判据③的一部分；可用性补项 |
| T5 | 左栏详情 + 单位倒树 | T1, T4 | 判据③/⑥；R5/R7 |
| T6 | 区域查看面板（tag 分组/点区域/点标签） | T1, T4 | 判据④；R4 |
| T7 | 单位移动与编辑模式 | T4, T5 | 判据⑤；只有既有 unit 命令 |
| T8 | M7 关账（判据逐条实测值 + R1~R8 点验 + 门禁 + `CLAUDE.md` + 报告） | T1~T7 | — |

**串行约束**：T1 必须先完成（其余全依赖）；T4 与 T2 之后 T4/T5/T6/T7 有共享前端文件（`app.js`/`styles.css`/`map.js`）⇒ **同一文件被两任务改 ⇒ 串行**，后关账者必须重跑前者的变异轮（CLAUDE.md 通则）。

---

## 九 未决 / 实测项

1. **时间轴实时预览的性能未测**：每次 `replay` 无缓存（`QueryService` 明说不做），默认 checkpoint 间隔 `N=100`，而 M6 的真地图 **19441 格** ⇒ 拖动时每次切换都整份解码。**T3 要实测一次**；不达标则考虑调小 `N` 或加一层只读缓存（需裁决）。
2. **倒树的"倒着"方向**：按字面（根在下）实现；若原意是普通树，改一处。
3. **区域描边**：M7 用 hex 填充实现高亮/淡显；`RegionBoundary` 环到屏幕坐标的映射留到需要**描边**时。
4. **`PlanRoute` 稀疏路点**（M5 挂起项）：地图上拖路径若要不逐格，需要补载荷字段——M7 先按"逐格相邻"实现，缺口如实记。
5. **单位"类型"字段**：领域里不存在（只有 `name` + `equipment` 键）；M7 不造。
6. **旧三页的退役**：M7 关账时决定是否删除 `unit.html`/`social.html`（它们含既有回归面）。
7. **CJK 字体**：本机已补装（`~/.local/share/fonts/NotoSansSC*`）；**换机需重装**，属运行环境不属代码。
