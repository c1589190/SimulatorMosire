# M7 台账 —— WebUI 可视化骨架（只读 + 单位操作）

> 阶段：M7（M0~M6 已完成）。**本台账记裁定与结论，不记取证过程**（取证结论另有任务报告）。
> 判据见 spec `docs/superpowers/specs/2026-09-19-webui-design.md` §〇.1。
> 切分：**M7 = 可视化骨架（只读 + 单位操作，不含地图写面）**；**M8 = 地图编辑写面**（`map.*` 命令族 + 地图编辑/区域编辑两模式）。

## 〇 缘起（2026-09-19，用户提出理想 WebUI 需求）

用户原话（要点）：「开场就进入网格地图；网格地图下方一长条是线型时间轴，拖动到新位置可以点创建节点，也可以直观操作线性分支分岔；中间是六边形网格 hex，常规模式点一个 hex 显示信息在左边，有单位点一下左边显示单位情况；最上面是模式切换栏（常规查看、区域查看、地图编辑、区域编辑、单位移动和编辑）；区域查看是右边列出各种区域标签，下面放标签下的各个记录区域，点区域按钮看单个区域，点标签按钮看当前标签下所有区域；地图编辑是左边区域信息都能改，还能选地形画图、圈一块区域做地形随机化、编辑河流道路；区域编辑是选一个标签绘画新区域，选中标签编辑时以淡色显示其他区域；单位移动和编辑是挪动单位、改编制；单位编制界面也要改，左边栏显示详情时按倒着的树状图展示隶属关系，点击一个分岔点（标大！）能看这个分岔展示单位的详情」。

**控制器先做了四路取证**（现状 WebUI / 地图写面 / 时间线分支 / 单位树与查询层），结论见 §三"取证要点"。

## 一 用户裁定（U1）

| # | 项 | 裁定 |
|---|---|---|
| **U1** | 时间轴分岔语义 | **只从分支末端分岔**（零 Core 改动）。拖到中间节点时"创建节点/分岔"**置灰**。★ 用户原话：「只从尖端分岔，但是尖端可以随时自动计算啊」——核实：`head = SELECT MAX(revision)` 纯计算且 `/api/state` 已吐；且 `CoreSimos.writePostCommitCheckpoint` 对 `ForkBranch` 已 `maybeWriteCheckpoint(source, expectedRevision)`（**replay 出分岔点状态再落 checkpoint**）⇒ "随时自动计算"**是现成机制**。 |

## 二 控制器裁定（S1~S10，**用户已批准**）

| # | 项 | 裁定 |
|---|---|---|
| S1 | 模块归属 | **不新建模块**：只读扩展进 `simos-app`；M8 的地图编辑命令进 `simos-map` |
| S2 | 时间轴节点清单 | 新增只读 API：`GET /api/timeline?branch=`；Core 侧加 `Timeline.listRevisions(branch)`（**纯 SELECT，只读、无 schema/语义改动**）——**本方案唯一碰 Core 处** |
| S3 | 事件读面 | **本轮不做**（开口项） |
| S4 | 地图编辑粒度（M8） | **语义化命令**（每编辑一条，规则在 `simos-map`） |
| S5 | 圈选随机化选区（M8） | **允许任意选区**（给 `RegionRandomizer` 加吃 `Set<HexCoord>` 的重载） |
| S6 | hex 详情补字段 | `/api/map/hex` 补 `region` + `terrainType` 定义；`/api/map/overview` 的 `terrainTypes` 回完整定义 |
| S7 | 区域读面 | overview 的 region 项补 `meta`；另加 `GET /api/map/region/{id}` 回该区域 **hex 集合**（boundary 环延后，M7 用 hex 填充实现"淡显"） |
| S8 | 单位倒树 | **纯前端**（`/api/units` 已回每个 `parent`，支持任意多级）；不加第二份真相 |
| S9 | 前端形态 | `/` 变主应用（即地图）；顶栏五模式；底部时间轴；旧三页**暂留为调试页**，关账时再定退役 |
| S10 | 本轮不做 | 事件读面 / 撤销重做 / 审批面板改动 / TLS / 区域标签增删 / 单位"类型"字段（领域里不存在） |

**里程碑切分**：**M7 ‖ M8 分开**（用户裁定「肯定切啊」）。理由：地图编辑是**一整族新后端命令**，可独立测；与"前端重写"混在一起门禁与判据都会糊。

## 三 取证要点（供 spec 引用，不再展开）

1. **现状 WebUI**：4 个独立 HTML（`index`/`map`/`unit`/`social`）+ 共享 `api.js`/`app.js`/`styles.css`，共 10 文件 ≈ **1545 行**；8 个 GET + 3 个 POST（`/api/command`/`advance`/`fork`）+ 审批代理；`advance()`/`fork()` 前端**有包装无调用**；地图是只读 Canvas（**硬编码 15 色**表、不画区域边界/河流道路、无缩放平移）；单位页是**扁平表**；**无时间轴、无模式栏、UI 无 tag**。
2. **地图写面 = 0**：全仓 0 个 `map.*` Command/Handler；`simos-map` 无 `MapOperations`。但**机制完备**：`MapChangeSet`（7 组件，Upsert/Remove/Patch 增量）、`MapCodec` **已注册进 Shell**、`RegionRandomizer.randomize` 与 `RiverBuilder.build` **已返回 `MapChangeSet`**（为 Command 复用而设计）、`RoundTripComponentsTest` 守卫往返。（M8 用）
3. **时间线**：`CoreSimos.replay(StateRef)` 可只读回放任意坐标，且**所有 GET 读端点已支持 `?branch=&revision=`** ⇒ 拖动预览**已有**；缺的是"列出分支全部 revision + 元数据"（`Timeline` 无列表 SQL）；`events` 表无 branch/revision 列且 `EventStore` **从未被实例化**（只写不读）；`Timeline.fork` 的 `expected==head` 守卫 = U1 的前提；`expectedRevision` **一字段两用**（CAS + 分岔点）——U1 下两者恒等，**无需拆**。
4. **单位树/查询层**：`Unit.parent` 是 `SegmentedSeries<Optional<UnitId>>`，**任意多级**；`/api/units` 已回每个 `parent` ⇒ 倒树纯前端；`RegionMeta.tag` **字段存在但 API 一个都没吐**；`MapResolver.regionOfHex` public 但 **app 未接线**；`terrainTypes` 只回 key（**TerrainType 的 color/moveCost 全没暴露**）；`InfoSystem` **无任何读面**。

## 四 任务

| # | 任务 | 状态 |
|---|---|---|
| T1 | Core 只读扩展 + 只读 API（S2/S6/S7） | ✅ `m7/t1` `33fed9b` → 合并 `5b8fb5a`（2 变异轮 0 存活；830） |
| T2 | 单页骨架 + 模式栏 + 静态资源改造 | ⏸ |
| T3 | 时间轴组件（节点/拖动预览/末端写与分岔，U1） | ⏸ |
| T4 | 地图 Canvas 升级（缩放平移/区域填充/点选联动） | ⏸ |
| T5 | 左栏详情 + 单位倒树 | ⏸ |
| T6 | 区域查看面板（tag 分组 / 点区域 / 点标签） | ⏸ |
| T7 | 单位移动与编辑模式 | ⏸ |
| T8 | M7 关账 | ⏸ |

---

## 五 执行日志（裁定与结论随任务关账追加）

### T1（Core 只读扩展 + 只读 API）已关账（2026-09-19，子代理 + 控制器核验）

**T1**（`m7/t1` `33fed9b` → 合并 `5b8fb5a`）：`Timeline.BY_BRANCH_SQL` + `listRevisions`（**列清单复用 `ALL_COLUMNS`、行映射复用 `mapRow`——不另立一套**，正是铁律 5 的防漂移形态）+ `CoreSimos.revisions`（纯委托，不触发封存）+ `GET /api/timeline` + `/api/map/hex` 增 `region`/`terrainType` + overview 的 region 项增 `meta` + 新增 `GET /api/map/region/{id}`；测试 +5（`TimelineTest` +1 / `GuiApiTest` +4）。**2 变异轮 0 存活**（m1 R3 去 `WHERE branch=?`＋绑定参数 ⇒ 分支隔离断言红；m2 R5 `regionOfHex`→常返空 ⇒ region 断言红）。

**裁定 67 —— T1 的两处执行期校正（接受）**：
1. ★ **变异体"半截"会造出假红**：m1 若**只删 `WHERE` 而留 `setString(1, …)`**，红因是 `setString` 索引越界抛 `SQLException`（"事务失败"），**不是**被保护的分支隔离断言。⇒ **变异体必须连同绑定参数一起去掉**（形态 1："红的理由必须是被保护的那行本身"）。
2. ★ **`test` 阶段含 Checkstyle ⇒ 变异体可能要连 import 一起去**：m2 第一版只改 `regionOfHex`、留着 `MenuResolver` import，`UnusedImports` 在**测试之前**红 ⇒ `Tests run=0` + surefire 是**上一轮陈旧文件**（mtime 早于 round_start）⇒ 装置按门禁 5/6 **当场作废本轮**（rc=2）。第二轮机械地去 import 后才落到 region 断言。**装置再次证明它能识别自己的产物带状态**。

**纯增量核对**：`terrainTypes` 仍为 `[key…]` 并由断言钉住（形状切换归 T2 与前端同批）；hex 只**增**两字段；region 项只**增** `meta`；前端 `webui/**` **零改动**（`git status` 实证）。**主树合并门禁 830** = 170/255/45/131/**154**/**75**、`BugInstance` 0 ×6、`[ERROR]` 0。

★ 如实记（T1 报告 §五）：`region` hex 排序只在 2 格夹具上测过；`terrainType` 的两条来源路径（`map.terrainTypes().get` vs `TerrainCatalog.of`）**测试无法区分**（夹具同源）；`/api/timeline` 的**多分支**形状未在 HTTP 层测（库侧由 `TimelineTest` 直证）；R1 扫描的边界已核：新增的 `import …core.timeline.RevisionRow` 里的**小写 `timeline` 包名不触发**扫描（禁的是大写 `Timeline`）。
