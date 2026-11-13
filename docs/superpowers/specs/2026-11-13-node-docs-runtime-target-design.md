# 节点编辑器与文档系统目标运行设计

> **状态**：有效（2026-11-13 起）· **性质**：目标运行序列 + 子设计嵌入表，不是实现计划。
> **父设计锚点**：
> - D-034 [`2026-11-13-timeline-node-editor-and-address-query-design.md`](2026-11-13-timeline-node-editor-and-address-query-design.md)
> - D-035 [`2026-11-13-node-version-world-docs-and-asset-pipeline-design.md`](2026-11-13-node-version-world-docs-and-asset-pipeline-design.md)
> - D-036 [`2026-11-13-node-address-and-docs-ownership-design.md`](2026-11-13-node-address-and-docs-ownership-design.md)（本文冲突时以 D-036 为准）
> - 用户设计原稿 [`2026-10-02-architecture-design-source.md`](2026-10-02-architecture-design-source.md) D-034~D-036
> **实现计划**：[`../plans/2026-11-13-node-editor-refactor-plan.md`](../plans/2026-11-13-node-editor-refactor-plan.md)

---

## 1. 目标运行序列（先写“代码实际怎么跑”）

### S0 启动与基线

1. `Shell` 加载 codec / 领域快照，打开 SQLite store。
2. `CoreSimos` 读取分支 head；按 checkpoint + revision delta 重建 `SimulationState`。
3. `QueryService`、`GuiServer`、`SimosToolSource` 装配只读口/写口/工具桶。
4. 世界文档服务 `WorldDocsService` 独立初始化 `docs/world/`，**不读 world revision**。
5. 目标运行阶段：经济/生产/文化最终从装配面卸载；过渡期仍保留，卸载见 S7。

**失败语义**：store 打不开 / codec 缺失 ⇒ 启动失败，不进入半装配；文档根不可写 ⇒ 文档工具失败，但不阻止世界启动（文档是独立持久面）。

### S1 读：地址查询

1. 调用方给出地址文本。先判 `NodeAddress.looksLikeNodeAddress`。
2. 是节点地址 ⇒ `NodeAddress.parse("tree1-360/unit/army/vg-berlin")`：
   - `NodeRef(branch=tree1, tick=360)`；
   - `Path=[unit, army, vg-berlin]`。
3. `CoreSimos.revisionAtTick(branch,tick)` 取该 tick 的 revision：
   - **editor profile 已实现**：一个 `(branch,tick)` 恰有一个节点 revision（同 tick 命令原地更新、推进只落目标节点）；
   - **full profile（测试/兼容）**：仍可能有多条 revision，取该 tick 最后一条；地址对外始终是 `tree-tick`，不暴露 rev。
4. 实体路径映射到现有 namespace 地址（app 层）：
   - `hex/<q,r>` → `map:<mapId>:hex.<q_r>`
   - `region/<id>` → `map:<mapId>:region.<id>`
   - `unit/[subtype]/<id>` → `unit:<id>`（`gov/<id>` 同）
   - `battle/<id>` → `army:combat.<id>`
   - `battle/<id>/stage/<sid>` → `army:combat.<id>:stage.<sid>`
   - `army/<id>` → `army:army.<id>`
5. `ResolverRegistry` / `FacetRegistry` 按 `QueryTarget(branch, revision)` 重放并解析。
6. 只给节点（`tree1-360`）⇒ 返回 `node:tree1-360` 候选，`typeName=Node`。

**失败语义**：
- 语法坏 ⇒ `IllegalArgumentException` → HTTP 400 / MCP `BAD_REQUEST`；
- 节点/分支不存在 ⇒ 同上（明确“节点不存在”，不编造空候选）；
- 实体不存在 ⇒ 现有 resolver 语义（空候选或未注册命名空间失败）。

### S2 写：命令提交

1. GUI/MCP 只提交 `Command`；`CommandBus` 做入口乐观检查（`expectedRevision == head`）。
2. 领域 handler 读当前状态、产出候选态；`ChangeSet` 由完整状态类型派生（铁律 5）。
3. 锁内复查 head；`Timeline.appendRevision` 与全部事件同一事务落盘。
4. 返回值 `CommandResult.Committed(StateRef)`；GUI/MCP 用新坐标刷新。

**节点语义**：
- **editor profile 已实现**：同一 `(branch,tick)` 只有一个节点；当前 tick 的 head 节点可继续编辑，离开 tick 后冻结；历史节点不改写。同 tick 后续命令原地更新该 revision，变更集由父节点完整态派生，并直接重写该节点 checkpoint。
- **full profile（测试/兼容）**：同一 tick 多次写仍会落多条 revision；`revisionAtTick` 取最后一条。旧档/legacy 路径不做物理压缩。
- 任何历史写仍被 `expectedRevision == head` 拒绝；要改历史 ⇒ 从该节点分叉（S4）。

### S3 时间推进

1. `AdvanceTime` 按日运行时间参与者，逐日产生 proposal/changeSet/incident。
2. **editor profile 已实现**：一次推进到目标 tick，只在目标 tick 落**一个节点**；中途急停则在急停 tick 落一个节点；中间日只在内存推进，事件累积到终结日一次落盘。
3. **full profile（测试/兼容）**：仍逐日落 revision；推进语义不变。
4. 经济/生产/文化卸载后，时间参与者只保留 unit/army/gov/ga/actor/social 所需部分。

**失败语义**：并发冲突折成 `Conflict`（报真实 head）；候选态校验失败 ⇒ 该日/该次推进不落 revision；急停 ⇒ 先 drain 跨模块效果，再返回 `StopReport`。

### S4 分叉

1. `ForkBranch(source, expectedRevision, newBranch)`。
2. `Timeline.fork` 检查父 revision **存在**（不再要求它是 head）。
3. 写新行 `(newBranch, revision=1)`：
   - `parent=(source, expected)`；
   - changeset = 空；
   - 模拟时刻继承父。
4. 新分支 head = `(newBranch,1)`；旧分支 head 不动。
5. 同 tick 的另一版本 = 新分支（如 `tree2-0`），**不是**同 tick 的 rev2。

**失败语义**：源分支不存在 ⇒ `Rejected("分支不存在")`；目标 revision 不存在 ⇒ `Rejected("分岔目标节点不存在")`；新分支重名 ⇒ 存储层约束失败（后续补明确拒绝）。

### S5 世界文档

1. 文档根 = 仓库 `docs/`（可用 `simos.docs.root` / `SIMOS_DOCS_ROOT` 覆盖）；世界文档在 `docs/world/`。
2. 地址 → 安全目录：
   - `entries/<安全地址>-<hash8>/meta.json`
   - `current.md`
   - `history/vN.md`
   - `.recycle/<删除日期>/<安全地址>-vN.md`
3. `create`：地址未存在 ⇒ 写 current + meta（version=1）。
4. `edit`：旧 current 复制到 `history/vN.md`，version+1，再写新 current。
5. `delete`：current 移入回收站，meta 写 `deletedAt`；历史保留。
6. `get/search/versions/contentAt`：按完整/部分地址、标题、名称查询；默认排除已删除，`includeDeleted=true` 时包含。
7. GUI/MCP 通过 `docs/*` 或 `simos.docs.*` 读写；**不进入 world revision**。

**原子性/失败语义**：文档写用同目录临时文件 + `ATOMIC_MOVE`；错误码 `INVALID / NOT_FOUND / EXISTS` 映射 HTTP 400/404/409、MCP 同名错误。世界写与文档写**不要求跨面原子**（文档独立持久）。

### S6 GUI 2+1

1. **已实现**：模式栏收敛为常规 / 地图编辑 / 单位编辑 / 战役；区域编辑并入地图编辑。
2. **已实现**：战役模式提供当前 tick 战报与战役/阶段文档入口；阶段地址为 `battle/<id>/stage/<sid>`。
3. **已实现最小机制**：模块资产放 `src/main/resources/webui/modules/<id>/`，app 通过 `modules.index.json` + `GET /api/webui/modules` 发现；`StaticHandler` 从 classpath 统一服务。
4. **已实现**：战役/阶段详情提供“文档入口”（S5 地址映射）。

### S7 经济/生产/文化卸载

1. **已实现（editor profile）**：`simos.profile=editor` / CLI `--profile editor`；economy/production/culture 的 handler、resolver、validator、工具、GUI API 不注册/404。
2. **已实现**：editor 时间参与者只注册 unit/ga/army；population/economy/land 不参与。
3. **过渡**：codec 仍保留以读取含旧切片的 store；世界生成器未裁剪经济 seed（编辑器路径不推进经济）。旧经济存档不承诺可读。
4. 保留 gov 完整国家/外交、ga 决策人、actor 库存；决策人查看/编辑模式暂缓。

### S8 PNG 底图（已实现第一版）

1. **已实现**：地图编辑面板载入 PNG，设置 world offset / 图像缩放 / 透明度，renderer 按 world 坐标叠加。
2. **已实现**：按 hex 中心 3×3 取色，最近地形色分类，预览计数后按地形分组发 `map.SetTerrain`。
3. 底图本身不写入 world 数据；目前只作为对齐/取色辅助层，持久化策略仍可后续增强。

---

## 2. 数据所有权与原子性

| 数据 | 所有者 | 持久化 | 写入口 |
|---|---|---|---|
| revision/checkpoint/事件 | `simos-core` | SQLite | `CommandBus` / `Timeline` |
| 领域状态/ChangeSet | 各领域模块 | revision delta | 领域 handler |
| 节点坐标/地址语法 | `simos-util` | 无（派生） | `NodeRef` / `NodeAddress` |
| 世界文档 | `simos-app` 宿主服务 | `docs/world/**` 文件 | `WorldDocsService` / docs 工具 |
| GUI 模式/前端资源 | 各模块 + app 聚合 | 静态资源 | 构建期打包 |

- **自然语言**：领域模块只存结构化数据（ID/地址/数值/枚举/引用）；描述/战报/叙述归文档系统。专名/显示名（`Region.name`/`Unit.name`/`Nation.name`/`Army.name`）保留为结构化字段。
- **世界写**：`Command → ChangeSet → Revision`，不因节点/文档改造而放宽。
- **文档写**：文件原子写；不产生 revision；与世界的关联靠地址字符串，不复制正文。

---

## 3. 裁定影响映射

| 裁定 | 影响阶段 | 运行后果 | 不涉及阶段 |
|---|---|---|---|
| D-036 节点地址 `treeXXX-tickXXX`、弃 rev | S1/S4/S6 | 地址只暴露 branch+tick；同 tick 另一版本走新分支 | S5 文档内部版本 |
| D-036 战役 +1、阶段独立地址 | S1/S6 | `battle/<id>/stage/<sid>` 可直接查询/编辑；GUI 有阶段入口 | S2 命令总线 |
| D-036 自然语言归文档 | S5/S6 | 领域模块存文档地址/结构化字段；文档服务独占正文 | S0 启动（文档可失败） |
| D-036 gov/ga/actor 保留 | S7 | 卸载只含 economy/production/culture；决策人模式暂缓 | S1 读口 |
| D-036 文档根 `docs/` | S5 | 文档独立持久，不随 revision 删除 | S2 世界写 |
| D-036 清理授权 | S0 | 旧 worktree/SDD 证据已清理；本地经济分支保留 | 运行序列 |
| D-035 历史不改写/任意节点分叉 | S2/S4 | 历史写拒绝；任意 revision 可分叉 | S5 |
| D-035 中间 revision 压缩 | S2/S3 | 目标一 tick 一节点；当前过渡后补 | S5 |

---

## 4. 子设计嵌入表

### 4.1 任意节点分叉（P1.1，已实现）

- **父序列锚点**：S4。
- **嵌入位置**：S4 第 2 步。
- **输入**：`source`, `expectedRevision`, `newBranch`。
- **输出**：`StateRef(newBranch,1)` 或空。
- **影响**：
  - 顺序：不变（仍在 `CommandBus` 路由内）；
  - 身份/持久化：父指针可指向任意 revision；新分支 revision=1；
  - 失败：`CommandBus` 区分“分支不存在/目标节点不存在”；
  - 测试：`TimelineTest` / `CommandBusDispatchTest` 历史 fork 正例 + 未知节点负例。

### 4.2 节点地址查询（P1.2a，已实现）

- **父序列锚点**：S1。
- **嵌入位置**：S1 第 1~4 步。
- **输入**：地址文本、fallback `QueryTarget`、mapId。
- **输出**：`QueryResult` / `List<FacetEntry>`。
- **影响**：
  - 顺序：先节点解析，失败再走旧 namespace 地址；
  - 身份：对外 `tree-tick`，内部 `revisionAtTick`；
  - 失败：节点不存在明确抛；
  - 测试：`NodeAddressTest`、`TimelineTest.revisionAtTick...`、`QueryServiceTest` 节点地址三项。

### 4.3 世界文档服务 + 工具（P2a，已实现）

- **父序列锚点**：S5。
- **嵌入位置**：S5 第 1~7 步。
- **输入**：docsRoot、地址、标题、正文、名称、删除日期。
- **输出**：`WorldDocView` / `WorldDocMeta` / 文件。
- **影响**：
  - 顺序：文档读写不经过 `CommandBus`；
  - 预算/冻结：无 world revision 预算；
  - 持久化：独立文件；`meta.json` + `current.md` + `history/` + `.recycle/`；
  - 失败：`INVALID/NOT_FOUND/EXISTS` 稳定错误码；
  - 日志：暂无专用 INFO 事件（后续补）；
  - 测试：`WorldDocsServiceTest` 8 例；docs 工具进入 GM 桶并纳入读工具覆盖表。

### 4.4 一 tick 一节点（P1.3，editor 已实现）

- **父序列锚点**：S2/S3。
- **实现方式**：不事后删除 revision，而是**从源头不制造中间 revision**：
  - 同 tick 命令原地更新当前节点 revision（`Timeline.updateRevision`）；
  - 一次 `AdvanceTime` 只在目标/急停 tick 落一个 revision；
  - 节点变更集由父节点/推进起点完整态派生（`ModuleDiffer`，铁律 5）；
  - 节点 checkpoint 从候选态直接重写（不 replay 旧 checkpoint）。
- **边界**：full profile / legacy 仍保留旧逐命令/逐日 revision 语义；旧档不做迁移/压缩。
- **影响**：见 `ShellAssemblySmokeTest` 的 0→2 / 2→5 / 同 tick 双写功能测试。

---

## 5. 非目标

- 不重写 `.git` 历史；不删除本地经济分支。
- 不做旧经济存档兼容/迁移。
- 不做决策人查看/编辑模式（本期只保留 ga/actor）。
- 不在本期实现 PNG 持久化细节（S8 后置）。
- 不把世界文档正文塞回领域状态。

---

## 6. 验收判据（正例/负例）

1. `tree1-360/unit/army/vg-berlin` 能解析到正确节点 revision 和 unit 候选；负例：`tree1-999/...` 明确失败。
2. 从历史 revision 分叉成功，父指针正确；负例：未知 revision 拒绝，head 不动。
3. 文档 create/edit/delete 后：当前正文、历史版本、回收站、查询各就位；负例：重复 create、路径穿越、已删除 edit 被拒。
4. GUI/MCP 都能看到 `simos.docs.*`；读工具覆盖表逐条覆盖。
5. 经济卸载后：时间推进不调用 economy/production/culture；gov/ga/actor/social 仍可读。
6. 节点压缩后：`(branch,tick)` 唯一；回放与压缩前逐字段相等；`atRevision` 不断。

---

## 7. 实现状态（2026-11-13）

| 切片 | 状态 | 关键证据 |
|---|---|---|
| P1.1 任意节点分叉 | ✅ | `TimelineTest`、`CommandBusDispatchTest` |
| P1.2a `tree-tick` 地址 | ✅ | `NodeAddressTest`、`QueryServiceTest` |
| P1.3 editor 一 tick 一节点 | ✅ | `ShellAssemblySmokeTest` 0→2/2→5/同 tick 双写 |
| P2a 文档服务 | ✅ | `WorldDocsServiceTest` 8 例 |
| P2b 文档 GUI / 战役入口 | ✅ | `docs.js`、`events.js`、前端门禁 418/418 |
| P3 2+1 模式 / 模块前端 manifest | ✅ 第一版 | `modes.test.cjs`、`WebuiAssetsTest.moduleFrontendManifestIsDiscoverable` |
| P4 editor profile 卸载 | ✅ 第一版 | `ShellAssemblySmokeTest` 无 economy 工具、participants=army/ga/unit |
| P5 PNG 取色 | ✅ 第一版 | `map-png.test.cjs`、`GuiApiTest.mapHexes...`、前端 418/418 |

**尚未完成/明确边界**：
- 领域模块中既有的自然语言字段（`RegionMeta.description`、`CombatRecord.text`、`CombatStage.text` 等）尚未逐项迁成文档地址；新文档系统已就位，但旧字段迁移需要单独 schema 改造。
- full profile / 旧档的中间 revision 压缩未做；editor 新世界不再产生中间 revision。
- 世界生成器仍可能 seed 经济数据（editor 不推进它）。
- 文档 GUI 目前是通用地址面板 + 战役/阶段入口，尚未做每个实体的专用文档嵌入。

---

## 8. 修订记录

| 日期 | 原文 | 改为什么 | 原因/来源 |
|---|---|---|---|
| 2026-11-13 | D-035 §1.4：节点身份 `tick+rev`，同一 tick 多版本 | `treeXXX-tickXXX`，弃 rev；同 tick 另一版本走新分支 | 用户补裁 D-036 原话：「重写成treeXXX-tickXXX，弃用掉rev…让节点版本跟着tick是非常坏的」 |
| 2026-11-13 | D-035 §3：暂停范围待定 | economy/production/culture；gov/ga/actor/social 保留 | 用户补裁 D-036：「gov保留完整国家、外交等，ga和actor…不和经济模块一同删除」 |
| 2026-11-13 | 文档根待定 | 仓库 `docs/`，独立持久 | 用户补裁 D-036：「放docs，文档是独立且持久的」 |
