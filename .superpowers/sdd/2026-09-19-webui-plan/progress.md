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
| T2 | 单页骨架 + 模式栏 + 资源重构（含 `terrainTypes` 形状切换） | ✅ `m7/t2` `6ef622e` → 合并 `676d527`（2 变异轮 0 存活；833；截图 2 张） |
| T3 | 时间轴组件（节点/拖动预览/末端写与分岔，U1） | ✅ `m7/t3` `98ed9f3` → 合并 `9d63243`（e2e 14 项全 PASS；2 变异轮 0 存活；833 不变；截图 2 张） |
| T4 | 地图 Canvas 升级（缩放平移/区域填充/点选联动） | ✅ `m7/t4`（e2e demo 10 项 + 真地图 5 项全 PASS；2 变异轮 0 存活；833 不变；截图 3 张；大图耗时已实测） |
| T5 | 左栏详情 + 单位倒树 | ✅ `m7/t5` `263236d`+`257421a`（报告补交） → 合并 `db5e22b`（树自检 8 断言 + e2e 9 步全 PASS；2 变异轮 0 存活；833 不变；截图 2 张） |
| T6 | 区域查看面板（tag 分组 / 点区域 / 点标签） | ✅ `m7/t6` `c017eed` → 合并 `bc7f434`（分组自检 14 断言 + e2e 12 步全 PASS；2 变异轮 0 存活；833 不变；截图 2 张） |
| T7 | 单位移动与编辑模式 | ✅ `m7/t7` `fbd63bb` → 合并 `7307f78`（e2e 34 步全 PASS 含 R8 allowlist；2 变异轮 0 存活；833 不变；截图 2 张） |
| T8 | M7 关账 | ✅ 本次（判据①~⑥逐条实测值 + R1~R8 点验 + 门禁 833 + `CLAUDE.md` + `task-8-final-report.md`） |

---

## 五 执行日志（裁定与结论随任务关账追加）

### T1（Core 只读扩展 + 只读 API）已关账（2026-09-19，子代理 + 控制器核验）

**T1**（`m7/t1` `33fed9b` → 合并 `5b8fb5a`）：`Timeline.BY_BRANCH_SQL` + `listRevisions`（**列清单复用 `ALL_COLUMNS`、行映射复用 `mapRow`——不另立一套**，正是铁律 5 的防漂移形态）+ `CoreSimos.revisions`（纯委托，不触发封存）+ `GET /api/timeline` + `/api/map/hex` 增 `region`/`terrainType` + overview 的 region 项增 `meta` + 新增 `GET /api/map/region/{id}`；测试 +5（`TimelineTest` +1 / `GuiApiTest` +4）。**2 变异轮 0 存活**（m1 R3 去 `WHERE branch=?`＋绑定参数 ⇒ 分支隔离断言红；m2 R5 `regionOfHex`→常返空 ⇒ region 断言红）。

**裁定 67 —— T1 的两处执行期校正（接受）**：
1. ★ **变异体"半截"会造出假红**：m1 若**只删 `WHERE` 而留 `setString(1, …)`**，红因是 `setString` 索引越界抛 `SQLException`（"事务失败"），**不是**被保护的分支隔离断言。⇒ **变异体必须连同绑定参数一起去掉**（形态 1："红的理由必须是被保护的那行本身"）。
2. ★ **`test` 阶段含 Checkstyle ⇒ 变异体可能要连 import 一起去**：m2 第一版只改 `regionOfHex`、留着 `MenuResolver` import，`UnusedImports` 在**测试之前**红 ⇒ `Tests run=0` + surefire 是**上一轮陈旧文件**（mtime 早于 round_start）⇒ 装置按门禁 5/6 **当场作废本轮**（rc=2）。第二轮机械地去 import 后才落到 region 断言。**装置再次证明它能识别自己的产物带状态**。

**纯增量核对**：`terrainTypes` 仍为 `[key…]` 并由断言钉住（形状切换归 T2 与前端同批）；hex 只**增**两字段；region 项只**增** `meta`；前端 `webui/**` **零改动**（`git status` 实证）。**主树合并门禁 830** = 170/255/45/131/**154**/**75**、`BugInstance` 0 ×6、`[ERROR]` 0。

★ 如实记（T1 报告 §五）：`region` hex 排序只在 2 格夹具上测过；`terrainType` 的两条来源路径（`map.terrainTypes().get` vs `TerrainCatalog.of`）**测试无法区分**（夹具同源）；`/api/timeline` 的**多分支**形状未在 HTTP 层测（库侧由 `TimelineTest` 直证）；R1 扫描的边界已核：新增的 `import …core.timeline.RevisionRow` 里的**小写 `timeline` 包名不触发**扫描（禁的是大写 `Timeline`）。

### T2（单页骨架 + 模式栏 + 资源拆分 + `terrainTypes` 形状切换）已关账（2026-09-19，子代理 + 控制器核验）

**T2**（`m7/t2` `6ef622e` → 合并 `676d527`）：`index.html` 变**工作台骨架**（顶栏五模式 + 三栏 + 底栏）；`app.js` 变**状态机**（`{mode,branch,revision,selection}` + `applyMode`，保留既有 `boot/mountNav/mountApprovals/pollState` 原行为）；新增 `panels.js`/`unitTree.js`/`timeline.js` 骨架（占位注明归 T5/T6/T3）；`styles.css` 追加布局（**全复用既有 `--*` 变量**）；**`terrainTypes` 形状原子切换**（`ApiViews.mapOverview` + `map.js` 图例 + T1 的那条断言**三处同批**）；`WebuiAssetsTest` +3（工作台骨架 / 两编辑模式 disabled+`data-milestone="M8"` / 按钮扫描器自证）。**2 变异轮 0 存活**（m1 R6 塞 `https://cdn…` ⇒ 判定器红；m2 去掉 `disabled` ⇒ 资产断言红）。

**裁定 68 —— T2 的两处口径（接受并回填 spec）**：
1. ★ **`terrainTypes` 是"状态里的词表（子集）"，不是无条件全 7 项**：`TerrainCatalog.KEYS` **只用于定序**，词表外 key 排末尾（字典序）以保证字节可复现。理由：词表只有一个来源（T1 口径）；改成全量输出＝制造第二份真相（且真实导入档的 `terrainTypes` 本就是子集）。**spec §3.2 已回填**。
2. **`/api/state` 轮询与 approvals 计数保留在 `app.js`**（未迁 `panels.js`）：旧三页也经 `app.boot({approvals:…})` 使用它 ⇒ 迁走会动三页的回归面。行为未丢（实测顶栏与「待批: 0」在场）。

**端到端实测（真 `ShellMain --demo` + 真 `StaticHandler`）**：`GET /` 200 / 2545B / `id="mode-bar"`×1 / `data-milestone="M8"`×2；`/map` `/unit` `/social` 全 200（旧三页仍可访问）；`/api/map/overview` 的 `terrainTypes` 已是对象数组、十字段全在场。**控制器另核**：截图 2 张（`常规查看`/`区域查看`）与设计图**逐项对上**，CJK 正常、三栏无错位。
**主树合并门禁 833** = 170/255/45/131/154/**78**、`BugInstance` 0 ×6、`[ERROR]` 0。

★ 如实记（T2 报告 §五）：**`terrainTypes` 的多词表排序未实测**（夹具与 `--demo` 世界都只有 `desert` 一项 ⇒ "按 KEYS 升序"缺 >1 项的证据）；disabled 按钮的"点击无响应"只证到视觉置灰（无 JS 单测装置）；旧三页的 JS 未被本任务测试覆盖（`/map` 200 已证，图例文本未断言）。

**下一批**：**T3（时间轴组件，U1）**——依赖 T1/T2 已满足。

### T3（底部线型时间轴，U1）已关账（2026-09-19，子代理 + 控制器核验）

**T3**（`m7/t3` `98ed9f3` → 合并 `9d63243`）：**纯前端**（`api.js` 加 `withTarget(path,target)`——**所有只读取数统一带 `?branch=&revision=`**，T4~T7 直接复用；`app.js` 状态机加 `branches`/`heads` + `refreshState`/`target()`，且 **`pollState` 只在首次初始化游标**（否则 5s 轮询会把用户拖到中间的游标弹回末端）；`index.html` 底栏加两按钮 + 状态位；`timeline.js` **377 行**（多分支线/节点/拖动 scrub/末端判定/写与分岔/409）；`styles.css` 时间轴与 `button:disabled` 灰化）。**零 Java 改动**，全量 **833 不变**。

**裁定 69 —— "创建节点"的语义（接受）**：`POST /api/advance`，参数 `from = 末端 tick`、`to = tick+1`。依据是实测的 `TimeAdvance` **第 0 项校验**：「`range.to` 缺失 ⇒ Rejected（开区间落不成 revision）」⇒ 必须给 `to`，而"推进一格"最自然的语义就是 `[tick, tick+1)`。实测每点一次 head +1 / tick +1。

**e2e 实测（真 `ShellMain --demo` + Playwright，14 项全 PASS；`t3-evidence/e2e/e2e-clean.log`）**——**判据②的每条都有值**：
| 步 | 实测值 |
|---|---|
| 造 ≥3 revision | `head=3 rows=3` |
| 节点数 == head | `nodes=3 head=3`；标签 `["rev 1 · Bootstrap","rev 2 · RenameUnit","rev 3 · RenameUnit"]` |
| `isAtTip` 纯函数（中间/末端/缺分支/null） | `{mid:false, tip:true, missing:false, empty:false}` |
| **中间节点** | 游标 rev 2；**两按钮 `disabled=true`**；★ **`head 3->3 rows 3->3`（R1：只读预览不写盘）** |
| **末端** | 两按钮可用 |
| **真鼠标拖动** | 只读预览 + 置灰，`head=3 rows=3` |
| **分岔** | `branches=["b2","main"]`、`lines=2`、游标到新末端 |
| **409 路径** | 外部先推 head（3→4）⇒ 页面 `expectedRevision` 过期 ⇒ 点「创建节点」收 409 ⇒ 提示"**末端已移动，已自动重取最新状态**"、head **自动更新为 4**、按钮随之置灰——**只重取、未静默重试写** |

**变异（2 轮 0 存活，红点均落在被保护断言本身）**：m1（`moveCursor` 里误发一次 `advance`）⇒ `STEP e-readonly: FAIL head 3->4 rows 3->4`——**红的就是"预览误写盘"的后果本身**；m2（去掉末端判定）⇒ `STEP e-mid-disabled: FAIL create_disabled=false fork_disabled=false`。装置为**资源类目标 + e2e 观察**（源与 classpath 两份推送、逐字节还原、日志自指）。

★ **带裁定的遗留（重要）**：spec §九-1 明确要求 **T3 实测"拖动时每次切换重放 19441 格地图的代价"**——本任务**只在小世界（`--demo`，3 revision / 1 hex）测过**，**大图/长历史的拖动手感仍未测**。归 **T8 关账**实测或 M8（记为**未履行**的 spec 项，不粉饰）。
★ 如实记：多分支 >2 条的渲染未实测；跨分支拖动未测；`?branch=&revision=` 约定**尚未被真面板消费**（T3 面板仍是骨架，属"读起来对"）；409 的复现依赖 5s 轮询窗口（本轮一次成功，非结构保证）。

**下一批**：**T4（Canvas 升级）**。

### T4（地图 Canvas 升级：缩放平移 / 后端地形色 / 区域填充 / 点选联动）已关账（2026-09-19，子代理执行；控制器**合并后**核验，见裁定 70）

**T4**（`m7/t4`）：**纯前端**（`map.js` 重写为共享渲染器 963 行：视变换 `{scale,tx,ty}` + 滚轮指针锚缩放 + 拖拽平移 +
区域填充 + 单位优先命中；**删硬编码 15 色表**，地形色改由 `/api/map/overview` 的 `terrainTypes[].color`，
词表外用唯一兜底色 `#ff00ff` + `console.warn` 一次；`api.js` 加 `mapRegion`；`app.js` 加 `highlightRegions`；
`panels.js` 左栏接**真读数**且一律 `withTarget(..., target())`；`index.html`/`map.html`/`styles.css` 接线）。
**零 Java 改动**，全量 **833 不变**（170/255/45/131/154/78、BugInstance 0×6、ERROR 0）。

**判据③ + 可用性补项实测**：demo e2e **10 项全 PASS**——① 地形色 == 后端（后端 `#E7C86E`、像素 `[231,200,110]`）；
② 缩放后点选准（scale 6.748、锚点漂移 1.7px、`{hex,1,3}`）；③ 平移后点选准（tx 100.67→190.67、`{hex,1,2}`）；
④ **拖游标到旧 revision 后点选 ⇒ URL 含 `revision=2`、面板出 `u-1 甲`（rev3 为 `乙`）**；⑤ 旧页 `/map` 仍可用。
真地图 e2e **5 项全 PASS**——区域填充像素前后变（`[168,179,106]→[204,192,95]`）、高亮 hex 数 == 该区 `hexes`（201==201）。

**变异（2 轮 0 存活，红点均落被保护断言）**：m1（`terrainColor` 用常量色）⇒ `STEP a-color-from-backend: FAIL`（像素 `[18,52,86]`=常量 `#123456`）；
m2（`pickAt` 忽略 transform）⇒ `STEP b-zoom-select: FAIL selB=null`。装置为资源类 + e2e 观察（源与 classpath 两份推送、逐字节还原、日志自指）。
★ 门禁当场生效一次：m2 首跑端口占用 ⇒ `BindException` ⇒ 装置按"服务器没起"**作废本轮**，换端口重跑才得红点。

★ **大图实测（补 spec §九-1 T3 未履行项）**：19441 格真地图上——服务端取 overview 40–85ms（curl/node）；
**浏览器内**同请求 **~2.6s 且 99.8% 在 `transfer`**（decode/parse 各 3ms），而同端点 `/api/state` 浏览器内 45ms
⇒ **瓶颈是 1MB 响应体到 Chromium 的传输，不是重放/解码**（根因未证）；整图首帧渲染 **~2.55s**（同步、无剔除）；
缩放后拖拽+点选 **~0.41s**。
★ **带裁定的开口项**：区域填充色用固定半透明色而非 `RegionMeta.color`（建议 T6 切）；T4 选区入口 = 区域查看模式点格（tag 分组归 T6）；
旧页保留"每格边长"输入；overview 只在分支变化 refit（revision 变化去抖重取，避免拖时间轴弹掉缩放）。
★ 如实记：词表外兜底色分支未实测（两世界地形都在词表内）；旧页缩放/平移未直接 e2e；区域重叠语义未核
（`test_nation` 第 0 格经权威解析实归 `test_annex_target`）；浏览器 2.6s 传输根因未证。详见 `t4-evidence/t4-report.md` §六。

**裁定 70 —— T4 的四条（控制器合并后核验）**：
1. ★★ **大图性能：原先担心的"拖动时间轴每次重放很贵"被实测推翻**。19441 格真地图上：**服务端**取 overview **40–85ms**（含 `Replay`）；**浏览器内**同一请求 **~2.6s 且 99.8% 时间在 `transfer`**（decode/parse 各 3ms），而同端点 `/api/state` 浏览器内 **45ms** ⇒ **瓶颈是 ~1MB 响应体的传输，不是重放、不是解码**。另一处真实代价是**整图首帧同步渲染 ~2.55s**（无剔除）。⇒ **spec §九-1 那条实测项由 T4 补上**（T3 未履行）；**行动项归 M8/后续**：(a) overview 按视口分级/分页，(b) 响应压缩（`HttpServer` 目前无 gzip）。★ 注意这是"**现象已测、根因未证**"（T4 报告 §六-1）。
2. **区域填充用固定半透明色**（不用 `RegionMeta.color`）⇒ **T6 切到 `meta.color`**（T4 的 `highlightRegions` 即 T6 的接口）。
3. **词表外兜底色 = `#ff00ff` + `console.warn` 一次**（接受）：不可能与任何真实地形色混淆；但**该分支未实测**（两个世界的地形都在词表内）⇒ 记为开口项。
4. ★ **区域重叠的归属语义未核**：`test_nation` 的第 0 格经**权威解析**实归 `test_annex_target` ⇒ `RegionIndex` 在重叠时的"谁赢"**没有查证**。M8 允许**画重叠区域**时会直接撞上这条 ⇒ **M8 spec 必须裁决**。

**下一批**：**T5（左栏详情 + 单位倒树）**。

### T5（左栏详情 + 单位编制倒树）已关账（2026-09-19，子代理执行；控制器合并后核验）

**T5**（`m7/t5` `263236d` 实现 + `257421a` 报告补交 → 合并 `db5e22b`）：**纯前端**（`unitTree.js` +290、`panels.js` +45、`styles.css` +126）。**零 Java 改动**、**未改台账**、全量 **833 不变**。

**判据⑥ 实测（我读的是日志、非其自述）**：
- **树自检（`logs/tree-check.log`，8 断言全 PASS）**：`depth-parent-child-per-node`（**4 层链 A→B→C→F**，逐节点 `depth/parent/children`）、`branch-point-recognition`（A/B 各 2 子 ⇒ true，C 1 子 ⇒ false）、`branch-teeth-single-vs-double`、`multi-root-forest`（roots=`[A,G,H]`）、`missing-or-null-parent-is-root`、**`dangling-parent-becomes-root`（`H.parent="ghost"` ⇒ `depth 0`）**、`descendant-count`、`empty-input`。
- **e2e 9 步全 PASS**：`seed head 1->7`（**用既有 `unit.CreateUnit` 造出 t5-a→t5-b→{t5-c,t5-d}、t5-c→t5-f、t5-a→t5-e 的层级**）；`tree-structure`（DOM 与 `buildTree` 逐节点一致，7 节点）；**`branch-visual` 给出数值证据 `weight 700 vs 400`、`size 15 vs 12`**（"标大"不是嘴上说的）；`branch-expand`（`visibleRows 0 → 4`）；`leaf-detail`（与 `/api/unit/t5-f` 逐字段一致）；`map-unit-link`（点地图单位 ⇒ `selectedInTree=true`）；**`revision-target-*` 的 URL 实测含 `?branch=main&revision=1`** 且面板值来自该 revision。
- 截图 2 张：**倒置树（根在下）** 与 **点分岔点后展开**；控制器目视核对：`第一连 u-1` 在底、向上长出 `甲部→乙部/戊队/丙队/丁队/己组`，分岔点 `甲部`/`乙部` 明显加粗放大。

**变异 2 轮 0 存活**（m1 少挂一个子/父子错位 ⇒ 深度断言红；m2 把"≥2 子"改成"≥1"⇒ 分岔点识别红）；九道门禁逐条在案、**无作废轮**。

**裁定 71 —— T5 的三条**：
1. **`parent` 指向不存在的 id ⇒ 当根**（`H.parent="ghost"` ⇒ `depth 0`）。**接受并立项为行为约定**——这是防御性：真实路径下 `ReparentUnit` 不接受不存在的父，但**只读面板不该因为脏数据而崩**。
2. ★★ **系统性开口项（不是本任务的错）**：本项目**没有 JS 测试器** ⇒ T3/T4/T5 的前端护栏（时间轴纯函数、几何换算、`buildTree`）**全部是"证据级"**（node 自检 / Playwright e2e / 截图），**不进 Maven 门禁** ⇒ 有**静默腐烂**风险（`tree-check.cjs` 留在证据目录，没人会再跑它）。**建议 M8 或后续裁决**：引入 JS 测试器，或把 node 自检接进 Maven（`exec-maven-plugin`）。**在此之前，前端护栏的强度低于后端**——如实记，不粉饰。
3. **倒树方向（根在下）是按原话字面实现**；spec §九.2 已标"可推翻"——若原意是普通自上而下树，改一处方向即可。

★ 如实记（T5 报告 §八，11 条）：`tree-check.cjs` 不在 CI ⇒ 可能腐烂；`PAGE-ERROR 404`（疑似 favicon）**未抓到 URL（属推断）**；CJK 仅截图间接证明；Playwright/Chromium 版本与硬编码路径耦合；**真 19441 格大图下的树未跑**；多区域/多分支下的树未测；环输入未测（领域拒绝）；`scrollIntoView` 未断言；独立的视觉评审**未做**（美术判断是它自看）。

**下一批**：**T6（区域查看面板）**。

### T6（区域查看面板）已关账（2026-09-19，子代理执行；控制器合并后核验）

**T6**（`m7/t6` `c017eed` → 合并 `bc7f434`）：**纯前端**（`panels.js` +179、`map.js` +90、`styles.css` +57）。**零 Java 改动**、**未改台账**、全量 **833 不变**。收口了 T4 的偏离项 #1（**区域填充色改用 `RegionMeta.color`**）。

**真实数据（原样，不假设）**：M6 导入器现场生成 `/tmp/t6-store`（`test_integration`，19441 格、2 条 province）：
`test_annex_target`(201格, tag=`Nation`, color=`#fc6dce`) 与 `test_nation`(701格, tag=`Nation`, color=`#d370d6`)。
★ **两区重叠**：`201 ⊂ 701` ⇒ `|union| = 701`，`ΣhexCount = 902`。**真实数据无 null tag**、两色都合法。

**判据④ + R4 实测**：分组自检 **14 断言全 PASS**（含 null/undefined/空串/纯空白/缺 meta 五种形态进"未标注"、桶序、入参不变、乱序等价、色解析 3 条）；e2e **12 步全 PASS**——`region-click-only-this-id`、`region-click-hex-count`（`701==701`）、**`region-fill-color-is-meta-color`（`filledPixel [186,151,151]` == `blend(#d370d6, base, 0.42)`）**、`tag-click-all-region-ids`（两区集合相等）、**`tag-click-distinct-hex-count-is-union`（`union 701`，并打印 `sumOfHexCounts 902`）**、`tag-click-both-colors`（两色都在场）。控制器目视截图：右栏 `Nation 2` → 两区项，地图上两色高亮且可见重叠；底部直方图 `ocean=4506 plains=10719 low_hills=3330 mountains=886` 与 M6 导入**逐值一致**。

**裁定 72 —— T6 的五条**：
1. ★★ **我的派单书写错了一处**（与裁定 65 同型：**控制器的措辞错，不是代码问题**）：§4.3 我写"多区域时 `hexCount` 之和一致"——**重叠区域下不成立**（真实数据 902 ≠ 701）。T6 按**并集去重**断言（701）并打印 902，**当场指出我写错**。以数据为准。
2. **T4 的 e2e「填充像素期望值」失效是预期内的设计变更后果**（T4 期望固定黄，T6 起来自 `meta.color`）——那是 T4 的**证据**、不是 CI 断言（本项目无 JS 测试器）⇒ 无构建红。**不重跑 T4 轮**，如实记。
3. **`setHighlightHexes` 契约变更**：入参 `[key…]` → `[{key,color}…]`；`debug().highlightHexCount` 由"数组长度"改为**去重后的 key 数**（重叠不再重复计数）。旧页无高亮入口 ⇒ 不受影响。
4. **兜底色区分**：区域用 `#00e5ff`（青）、地形用 `#ff00ff`（品红）——语义不同，不共用一个值。接受。
5. ★ **T6 把 m1 变得可杀**（对派单书的**加强**，接受）：真实数据无 null tag ⇒ 若只按我写的"未标注桶交 node 自检"，m1（丢掉未标注桶）在 e2e 里**根本不改变结果**、杀不掉。它**两层都做**（node 夹具 + e2e 页面内夹具），m1 才红在 `group-null-bucket-fixture`。**"变异要能被杀"是设计护栏时的硬约束**——记入通则。

★ **区域重叠的"先者胜"逐格像素仍未验**；T4 记的「`RegionIndex` 重叠归属未核」**仍未查证** ⇒ **继续挂到 M8**（M8 会画重叠区域，必撞）。
★ 如实记（T6 报告 §八，10 条）：兜底色分支只被 node 覆盖（真实色都合法）；"未标注"桶**从未在真页面渲染过**；多标签场景未测（真实只有 1 个 tag）；窄屏未测；e2e 硬编码 Playwright/Chromium 路径；大图 2.6s 传输代价仍在。

**下一批**：**T7（单位移动与编辑模式）**。

### T7（单位移动与编辑模式）已关账（2026-09-19，子代理执行；控制器合并后核验）

**T7**（`m7/t7` `fbd63bb` → 合并 `7307f78`）：**纯前端**（`app.js` +100、`index.html` +44、`map.js` +401、`styles.css` +50）。**零 Java 改动**、**未改台账**、**833 不变**。唯一写入口 `app.writeCommand(type,payload)`（组信封 → `POST /api/command`；409 ⇒ 重取并拉到 `current.revision`；422 ⇒ 原样回 `reason`）。

**判据⑤ 实测（e2e 34 步全 PASS，真写）**：点选式移动 `position {1,1}→{1,2}` + `head 1→2` + **时间轴节点 1→2**；路线式 `unit.PlanRoute` ⇒ `movement false→true` + `head 2→3`；编制 `parent null→"u-2"` / `member 100→123` / `equipment {步枪:50}→{步枪:7}` / `CreateUnit`(带 parent) / `DisbandUnit`(后 404)；**422 显示 `reason` 原文**「父单位不存在: ghost-parent」；**409** ⇒ 提示 + head 8→9 + **位置未变（确实没写）**。

**★ R8 的两半都有实测**：① **写路径 allowlist**——全过程 9 个非 GET，path 去重 = `["/api/command"]` ⊂ `{/api/command,/api/advance,/api/fork}`（**清单原样落盘**）；② **只读模式未被污染**——切「常规查看」后点单位+点格 ⇒ **新增非 GET = 0**，且 hex 选择仍可用。

**变异 2 轮 0 存活**：m1（写调用改到 `/api/raw-write`）⇒ `STEP e-write-allowlist: FAIL`；m2（`PlaceAt` 发选中单位自身所在格）⇒ `STEP a-position-after-target: FAIL {"before":{1,1},"after":{1,1}}`。

**裁定 73 —— T7 的三条 + M7 关账结论**：
1. ★ **"变异要能被杀"再次成为装置设计约束**：m1 会让 a/b 系列超时 ⇒ 若装置在超时处抛异常，`run()` 提前中止、**e 步的 allowlist 断言永远跑不到**，护栏就成了**杀不掉的装饰**。T7 因此把 `waitRevisionAbove` 与编辑器点击改成**不抛**（只记 `FAIL`/`CLICK-SKIP`）。与裁定 72.5 同一条通则，**已两次独立命中**。
2. ★ **e2e 装置的一个真坑（记给后续所有前端任务）**：`page.fill` 会把页面滚下去 ⇒ `#canvas` 的 `boundingBox().y` 变负 ⇒ `page.mouse.click` 落在视口外、**点击静默无效**（T7 的 b5 首次就跑中）。修法：点击前 `scrollIntoViewIfNeeded()`。**与 CLAUDE.md「把没发生伪装成没发生」同族**——**这条要进 CLAUDE.md 的纪律形态清单**。
3. **`PlanRoute` 稀疏路点缺口照旧**：客户端保证逐格相邻（每点即路径一格），**不伪造稀疏语义**；载荷补字段归后续（M5 挂起项延续）。
4. **M7 关账结论**：**8/8 完成**；判据①~⑥**逐条实测值**见 `task-8-final-report.md`；**R1~R8 每条都有变异自证**（14 轮 0 存活）；**旧三页裁定保留**为调试/回归页（spec §九.6 收口）。

**下一批**：**M8**（地图编辑写面）——开工前先裁决 **区域重叠归属**（T4/T6 两次挂起）与 **`map.*` 命令族的粒度**。

---

# M7b —— 用户实测反馈的修复包（2026-09-19）

> **缘起**：用户在 M7 关账后**亲自试用**，报了四条实测反馈。控制器先派**两路只读取证**（移动语义 / 时间轴布局），再定修法。
> **性质**：**修复包**，不另立 spec 文档（**评审体量不得压过代码本身**）；裁定与要点记在本节。

## 一 用户裁定（M7b-U1~U2）

| # | 项 | 裁定 |
|---|---|---|
| **U1** | 移动模型 | **A：时间驱动**（HoI4 味）——右键下路线，**随 `advance` 沿路线走**，MP/地形成本决定何时到 |
| **U2** | 瞬时置位（`PlaceAt`） | **不做为正常编辑手段**，归**后续控制台功能**。用户原话：「**0tick speed 还不超模？**」⇒ 记入"不做"清单 |

## 二 取证结论（决定修法的硬事实）

1. **移动规则**（准确口径）：前提是**一条显式 `Route.path`**（逐格相邻、无重复）——**单位不自寻路**；预算 `speed×1000×(tick−出发tick)` 毫 MP；每格成本 `地形(目标格).moveCost × mobilityPerMille`（★**越大越慢**，是成本倍率）；付不起 ⇒ **原地停**（`IN_TRANSIT`，`nextHex`/`remaining` **不落盘**、每 tick 重算）；付清 ⇒ `ARRIVED` 清路线。**`speed=0` 不可表示**（`Unit`/`Movement` 构造器硬拒 `<1`）。
2. ★ **用户"不知道移动逻辑"的根因是产品缺陷**：现有 UI 的"点目标格"其实是 **`PlaceAt`（瞬时传送+清路线）**，跟移动是两码事；且**界面从不显示** MP / 每格成本 / 预计到达 ⇒ 规则不可见。
3. ★ **寻路已存在但零接线**：`PathFinder.findPath(map,start,goal,unit,cost) → Optional<List<HexCoord>>`（A\*，确定性全序 `(f,h,q,r)`，**已有 `PathFinderTest`**）——**没有任何 handler/API/工具调用它**。
4. ★ **路线没暴露**：`/api/unit/{id}` 与 MCP `simos.unit.get` 的 `movement` 都是**布尔**（仅 `isPresent()`）；`route.path`/`waypoints`/`currentHex`/`nextHex`/`remaining` **全未出** ⇒ 前端**无数据画线**。
5. **`PlanRoute` 载荷只收 `waypoints`**，handler 把它**当 path**（`new Route(waypoints, waypoints)`）⇒ 必须提交**完整逐格序列** ⇒ **寻路只能放服务端**（前端自算＝第二份真相）。
6. **没有右键**：`map.js` 的 `pointerdown` 直接 `if (event.button !== 0) return`；全仓无 `contextmenu` 监听。
7. **时间轴缺陷①成因**：DOM 只有 `.timeline-line`/`.tl-branch-label`/`.tl-node`；**"游标"只是给节点切 `.active` 类**；拖动是**隐形手势**（按在整条行上、`closest(".timeline-line")`、吸附最近节点），**没有抓手**。
8. **时间轴缺陷②成因**：布局是 **CSS flex 流**（`.timeline-track` 列 + `.timeline-line` 行），**`revision` 从未参与定位**——每条分支行**独立**从 label 后从最左开始；分支按 `ORDER BY branch` 字典序 ⇒ `b2` 插在 `main` **上方**。**spec §五-4 的"从分岔点长出"从未实现**。
9. ★ **"分岔自哪里"的数据已具备**：`b2@1` 的 `parent = {branch:"main", revision:k}` 由 `Timeline.fork` 写入、由 `/api/timeline` 序列化，且前端 `refresh()` **已拉取全部分支的 timeline** ⇒ **不需要新端点**。

## 三 控制器裁定（M7b-S1~S4，可推翻）

| # | 项 | 裁定 |
|---|---|---|
| S1 | 时间轴 | 加**可见 knob**（可抓、`setPointerCapture`、吸附节点）＋ **列坐标布局**（`x=(revision−1)×列宽`）＋ 非 main 分支 rev1 **对齐 parent 所在列并画垂直连线**。★ **连线必须用新类名**——既有 e2e `g-fork` 断言 `.timeline-line` 数量==2，复用会打破它 |
| S2 | 移动可见化 | `/api/unit/{id}` 的 `movement` 由**布尔改对象**（`route.path`/`waypoints`/`departedAt`/`speedAtDeparture`/`currentHex`/`nextHex`/`remaining`）；左栏显示 **MP / 每格成本 / 预计到达 tick** |
| S3 | 右键移动 | `contextmenu` ⇒ 服务端 A\*（新增**只读**端点 `/api/map/path?from=&to=&unit=&branch=&revision=`）⇒ 前端发 `unit.PlanRoute(waypoints=path)` ⇒ 画线、随 advance 缩短。**线的画法**：整条 path 淡色 + 剩余段亮色（可推翻）。**右键语义**：**替换**当前路线（HoI4 行为，可推翻） |
| S4 | 不做 | **`PlaceAt` 瞬时置位作为正常编辑手段**（U2）；**B 模型**（0-tick 到位） |

**裁定 M7b-U3（用户当场裁定，控制器失误纠正）**：**子代理一律用 `deepseek-flash-go`（DeepSeek **V4.1** Flash）**，禁用 `deepseek-flash`（V4 Flash），**也不许用 `category=` 派单**——后者会落到 `Sisyphus-Junior` 的默认模型（= V4 Flash）。用户原话「**不许用 V4Flash！**」。
★ **由来（控制器失误）**：M7 的 T1~T7 **七个任务全部误用 `category=...` 派单**（M7b T1 第一次派单亦同），直到用户当场发现；**M5/M6 用的都是 `deepseek-flash-go`**。已把该禁令写进 `CLAUDE.md` 的**纪律**第一条。★ M7b T1 第一次派单**已取消**，实测其工作树**一行未动**（干净 `af70b83`），随后**用 `deepseek-flash-go` 重派**（`bg_47355b17`）。

## 四 任务

| # | 任务 | 状态 |
|---|---|---|
| T1 | 时间轴：可见 knob + 列布局 + 分岔连线 | ✅ `m7b/t1` `9dd1363` → 合并 `95bd45d`（e2e 21 步全 PASS；2 变异轮 0 存活；833 不变；截图 2 张） |
| T2 | 移动可见化（路线暴露 + 左栏 MP/成本/ETA + 画线） | ✅ `m7b/t2` `c32b2d7` → 合并 `7593ac5`（e2e 43 步全 PASS；**3** 变异轮 0 存活；836；截图 2 张） |
| T3 | 右键移动（服务端 A\* + PlanRoute + 画线） | ✅ `m7b/t3` `03b385b`+`2d55e49`（证据归位） → 合并 `6561362`（e2e 全 PASS；2 变异轮 0 存活；841；截图 2 张） |
| T4 | M7b 关账 | ✅ 本次（四条用户原话逐条实测值 + 不变量点验 + 门禁 841 + `CLAUDE.md` + `task-4-final-report.md`） |

## 五 M7b 执行日志

### M7c（取消工作台左键瞬移）已关账（2026-09-19，子代理 `deepseek-flash-go`；控制器合并后核验）

**缘起**：M7b 关账时控制器点名"`PlaceAt` 瞬移与右键移动共存"是**未收敛的一致性问题**（U2 说瞬移不作为正常编辑手段，而 T7 留下的**左键**点格仍是 `PlaceAt`）。**用户当场裁定：「取消掉」** ⇒ 记为 **M7b-U4**。

**M7c-T1**（`m7c/t1` `9379eb6` → 合并 `20163ad`）：**只 2 文件**（`map.js` **+8/−27**、`index.html` +1/−1）——`workbenchSelect` 的"非路线模式左键"分支去掉，**落到普通 hex 选中**；**删掉变成死代码的 `submitPlaceAt`（17 行）**（删前 grep 确认零调用者）；注释与初始提示文案同改。**零 Java 改动**，全量 **841 不变**。

**实测（干净轮 27 步全 PASS，变异前后各一次）**：★ 左键点 (1,2) ⇒ **`b-no-write {0→0}` / `b-position-unchanged {1,1}→{1,1}` / `b-head-unchanged` / `b-no-new-revision nodes 1→1` / `b-selection-hex`**；★ 右键 (1,3) ⇒ 仍 `path 3 点` + `head 1→2` + 折线 3 点；路线模式左键仍能加点；**非 GET 清单只剩 `["/api/command"]`**（b/d 确实无写）。
**变异 2 轮 0 存活**：m1（左键接回 `PlaceAt`）⇒ 红在 `b-position-unchanged`；★ **m2（右键不发 `PlanRoute`）⇒ 红在 `c-head-advanced`，而 b 步仍全 PASS**——**两个方向各自独立可杀**（防"一刀切掉移动"也能过）。

**裁定 M7b-U4（用户）**：**取消工作台左键瞬移**；移动**只由右键发起**。
★ **保留未动**：旧调试页 `webui/unit.js:50` 的 `unit.PlaceAt` **显式表单**（旧三页裁定保留为调试页；显式提交 ≠ 隐式点击）——实现者已如实指出该不一致，**控制器裁定保留**（U2 约束的是"**正常编辑**手段"）。
★ 行为说明：左键点 hex 会**取消单位选中**（与 HoI4 一致：点空地即取消选中）；要下路线需先左键选中单位 → 再右键点目标格。

---

## 六 M7b 执行日志（续）

### M7d（用户实测四缺陷）已关账（2026-09-19，子代理 `deepseek-flash-go`；控制器合并后核验）

**缘起**：用户试用后报四条（**拒绝复述**，要控制器自己找）。控制器先从 `simos.db` **还原用户操作**（`main@2 PlanRoute`@tick5 → `@3 AdvanceTime`5→6 → `@4 PlanRoute`@tick6 → `@5 AdvanceTime`6→7 ⇒ **只在测移动、且重下两次路线**），并**先自证一条**：`handleContextMenu` 在**未选中单位时 `return false` 且不提示** ⇒ 右键**静默无操作**。随后用户仍列出了四条。

**M7d-T1**（`m7d/t1` `f79fe89`+`c9ab945` → 合并 `870ca6c`）：**纯前端四文件**（`index.html`/`styles.css`/`timeline.js`/`map.js`），**零 Java**，全量 **841 不变**；e2e 19 断言全 PASS；**变异 4 轮 0 存活**（另 1 轮因端口占用**被装置当场作废**）。

**四条根因（★ 全部有实测，B 是教科书式根因隔离）**：
| # | 根因（文件:行） | 关键实测 |
|---|---|---|
| **A** 时间轴被挤出视口 | `styles.css:21-28`（body 无高度约束）+ `:353-365`（`.workbench align-items:start`、三栏不滚动）+ `:388-395`（底栏在文档流） | 修前 `barTop=1592`（视口 800）、`docScrollHeight=1661`；修后 **`barBottom==800`**、左栏内容 1762/可视 601 ⇒ **栏内滚动** |
| **B** 圆环拖不动 | ★ **非独立缺陷，根因就是 A** | `elementAtCenter === null`（knob 在**视口外**，事件到不了）；**两个对照实验**：视口拉到 2200px ⇒ `changed:true`；1280×800 **滚到底** ⇒ `changed:true` ⇒ **拖动逻辑本来是对的**（控制器原先猜的 `pointer-events`/`closest`/尺寸**全错**） |
| **C** tick 不增（显示） | ① `timeline.js:328/:339` 用**异步滞后**的 `model.heads`；② `onStateChanged` 在 loading 时 `return` **不排队** | `revisions.tick` 后端确在增（`1:5,2:5,3:6,4:7`）；`probe-c` 实测写后 **+157ms**：head 已 `{main:2}` 而模型仍 `{main:1}` ⇒ **「创建节点」瞬态置灰 + 底栏 `head 1` 造假**（★ **这就是用户"点第二次没反应/tick 没增加"的真身**）；修后连续两次 `tick 5→6→7`、按钮立即回可用 |
| **D** 移动一次两个节点 | **设计如此**（`PlanRoute` + `AdvanceTime` 两条真 revision），**不改语义** | 只加可理解性：右键成功提示「**点『创建节点』推进时间，单位才会出发**」+ 按钮改「创建节点（推进时间）」 |

★ **方法学收获（记入纪律）**：**"看不见的元素"与"不工作的逻辑"必须用对照实验分开**——B 的两个对照（放大视口 / 滚到底）**一次就把根因钉死**，而控制器的三个猜测全错。**先问"它到底有没有被点到/看到"，再问"点了有没有用"。**

**下一批**：**M7e**（用户补充的交互表 + 折线对比度）。

### M7e（交互表 + 折线对比度）已关账（2026-09-19，子代理 `deepseek-flash-go`；控制器合并后核验）

**缘起（用户原话）**：「**右键点击空白的地方取消选中而不取消路径**；**左键点击单位本身或者单位原本的地方才是取消移动**」＋ 控制器发现折线对比度低（`baseColor=rgba(255,214,130,0.35)`＝**35% 透明度的黄**叠在黄沙上，即用户"看不出发生了什么"的物理原因）。

**M7e-T1**（`m7e/t1` `22ffc70` → 合并 `0005303`）：**纯前端两文件**（`map.js` +68/−15、`index.html` +1/−1），零 Java，全量 **841 不变**；干净轮 **30 断言全 PASS**（变异前后各一次）；**变异 3 轮 0 存活**。

**实测**：★ 右键空白 ⇒ **`a-selection-cleared`** + **`a-no-write`** + **`a-movement-kept`** + **`a-polyline-kept`**（**取消选中但路线/折线都留着**）；★ 左键单位第一次 ⇒ `b-first-click-selects` + `b-first-click-no-write`（**只选中不写**）、第二次 ⇒ `b-movement-cleared null` + `b-head-advanced` + `b-node-plus-1` + **`b-polyline-gone []`**（**发真 `unit.CancelRoute`**）。
**变异**：m1（右键空白也清路线）⇒ `a-movement-kept`/`a-polyline-kept` 红；m2（左键不写）⇒ `b-movement-cleared` 等红；m3（未选中就发 CancelRoute）⇒ `b-first-click-*` 红。

**裁定 M7e-S1（控制器默认，用户未反对，**可推翻**）**：左键点单位**同时**承载"选中"与"取消移动" ⇒ 定为 **未选中⇒选中、已选中⇒再点=取消移动**（一下选中、两下取消，不用修饰键）。若要"一点即取消"，改一处分支即可。

### M7f（时间轴按 tick 分组 + 推进 N tick）已关账（2026-09-19，子代理 `deepseek-flash-go`；控制器合并后核验）

**缘起（用户原话）**：「我让单位移动，下面还是会**先创建一个新的节点**，而不是为当前节点改变单位移动状态；以及，**增加多少 tick**？点推进时间只能增加 1，那我想增加 100 怎么办？」
★ **控制器先解释了一条不可协商的约束**：**"改写当前节点"违反铁律 2**（所有修改＝`Command → ChangeSet → Revision`；时间线是**只追加** DAG，改写历史会让 `correlationId` 链 / checkpoint / 分岔全崩）。⇒ 提出**替代方案并获用户批准**：**时间轴按 tick 分组**（一个节点 = 一个 tick；同 tick 的命令**视觉归并**进同一节点；只有 tick 变化才长新节点）——**用户手感满足，铁律不破**（每条命令仍是真 revision，只是视觉归并）。
★ 用户同时裁定：**不做「推进到本路线抵达」**（原话"因为后面会有多单位互动，要额外做"）——**这个判断是对的**：单单位"算到抵达"是特例，多单位要的是"推进到下一个事件"。

**M7f-T1**（`m7f/t1` `fe9d49f` → 合并 `51cd252`）：**纯前端三文件**（`timeline.js` +221/−48、`index.html` +5、`styles.css` +26），零 Java，**841 不变**；干净轮 **45 断言全 PASS**（变异前后各一次）；**变异 3 轮 0 存活**。

**实测**：★ **`a-node-count-unchanged` + `a-detail-plus-one` + `a-command-is-planroute` + `a-api-tick-count-unchanged`**（下路线**不再多出节点**，只在该 tick 的节点里**多一条命令明细**）；`b-new-tick-is-old-plus-1`（推进 1）；★ **`c-new-tick-is-old-plus-100`**（**推进 100 真生效**）；`e-fork-aligned`（**分岔对齐保住**，列基准已从 `revision` 换成 **tick 序号**——因为 demo 的 tick 从 5 起、直接用 tick 值会留空列）。
**变异**：m1（按 `revision` 分组）⇒ `a-node-count-unchanged`/`a-detail-plus-one`/`a-command-is-planroute` 红；m2（`to=from+1` 忽略 N）⇒ `c-new-tick-is-old-plus-100` 红；m3（分支首节点不对齐 parent 列）⇒ `e-fork-aligned` 红。
**游标语义（实现期决定，已记）**：点一个 tick 节点 ⇒ 取该 tick 的**最后一个 revision**（面板看到的是该 tick 结束时的状态）。

### T1（时间轴：可见 knob + 列坐标布局 + 分岔连线）已关账（2026-09-19，子代理 `deepseek-flash-go` 执行；控制器合并后核验）

**T1**（`m7b/t1` `9dd1363` → 合并 `95bd45d`）：**纯前端两文件**（`timeline.js` / `styles.css`），**零 Java**、未改台账、全量 **833 不变**。

**判据（我核的是日志与截图，不是它的自述）**：
| 断言 | 实测 |
|---|---|
| `a-knob-exists-visible` | `knobHidden=false, display=block, knobRev=3, nodeRev=3` |
| `a-knob-on-cursor` | ★ **`delta=0`**（node `{x:323,y:804}` == knob `{x:323,y:804}`） |
| ★ `b-drag-knob-offrow` | **`offRowY=874`（行中心 804，纵向 +70px）仍改 x ⇒ rev 变 2**（`setPointerCapture` 生效） |
| `b-drag-readonly` / `e-readonly` | `head 3->3 rows 3->3`（拖动仍只读） |
| ★ `c-column-spacing` | **`COL_WIDTH=110 d1=110 d2=110`**（centers `103/213/323`） |
| ★★ `d-fork-aligned` | **`child.x=323 parent.x=323 dx=0`** |
| `d-fork-link-visible` | `.tl-fork-link` `[{x:323,y:804,h:40,w:2}]` |
| `e-main-first` | `firstLine=main` |
| 回归 | `g-fork`（`lines=2`）/`e2-drag-preview`/`d-nodes`/`d-labels`/`h-conflict` **全部仍 PASS** |

**变异 2 轮 0 存活**：m1（`renderCursor` 删掉 knob 定位）⇒ `STEP a-knob-on-cursor: FAIL delta=292.494… knob={x:31,y:787}`；m2（`columnOf` 非 main 直接 `return rev`）⇒ `STEP d-fork-aligned: FAIL child.x=103 parent.x=323 dx=220`。装置**含变异体生成器的锚点自证**（断言锚点匹配恰 1 次 + 替换后字节必变），且**变异后重跑干净轮**（`e2e-clean-after-mutants.log` PASS）——"验过"与"推出来"分开。

**裁定 M7b-S5 —— T1 的三处（控制器接受）**：
1. ★★ **我的派单公式写错了**（**第三次**被实现者当场纠正，前两次是裁定 65、72.1）：我写 `x = 左内边距 + (revision − 1) × 列宽`——**对非 main 分支不成立**。正确是 `x = 左内边距 + (列 − 1) × 列宽`，其中 **main 的列 == revision**，**分支的列由其 `parent` 递归决定**（这正是"从分岔点长出"的数学表述）。已按此实现。
2. **`#timeline-meta` 的 tick 数据源**由"分支 head 的 tick"改为**游标节点的 tick**（缺省回退 head tick）——符合 S1 的字面语义，**格式未变**。接受。
3. **报告位置**：`t1b-report.md` 放在 `t1b-evidence/` 内（派单书要求放计划目录根）——**不影响可用性**，记此一处。

★ 如实记（T1 报告 §五，8 条）：**嵌套分岔（fork 自 fork）的列递归只有代码路径、无真 e2e**；横向滚动/窗口 resize/触摸/笔未测；`columnOf` 的防环分支未触发；仅本机 headless Chromium；新 JS/CSS 只靠门禁判"无绝对 URL"、未逐行人工复核。

### T2（移动可见化：movement 布尔→对象 + 左栏 MP/成本/ETA + Canvas 路线折线）已关账（2026-09-19，子代理 `deepseek-flash-go` 执行；控制器合并后核验）

**T2**（`m7b/t2` `c32b2d7` → 合并 `7593ac5`）：Java（`ApiViews`/`ToolSupport`/`GuiServer`/两个 Tool + 两个测试）+ 前端（`panels.js`/`map.js`）。**未碰 `simos-core`**、未加写端点、未改台账。全量 **836** = 170/255/45/131/154/**81**（**+3** = `GuiApiTest` +2 / `SimosToolsTest` +1）。

**判据实测（我核日志，不是自述）**：
| 项 | 实测 |
|---|---|
| `movement` 对象 | `{"route":{"waypoints":[…],"path":[3 点]},"departedAt":{"tick":5},"speedAtDeparture":2,"mobilityPerMilleAtDeparture":500,"status":"IN_TRANSIT","currentHex":{1,1},"nextHex":{1,2},"remainingMillis":1500}` |
| 四处同形 | `/api/unit/{id}`、`/api/units`、`simos.unit.get`、`simos.unit.list` |
| 左栏读数 | **`每格成本 1500`** / `本 tick 预算 2000` / `总成本 3000` / `IN_TRANSIT` / `currentHex` / `nextHex` / `remaining 1500` / **`预计到达 tick 7`** |
| **ETA 算式**（实现者推出并写入报告） | `ETA = departedAt.tick + ceil(Σ每格成本 / (speed×1000))`；demo 核对 `5 + ceil(3000/2000) = 7` |
| Canvas 折线 | `totalPoints:3, remainingPoints:3`；`baseColor rgba(255,214,130,0.35)` ≠ `remainingColor #ffd27a`（分层） |
| **推进后** | `head 5→6`；`currentHex {1,1}→{1,2}`；`nextHex →{1,3}`；**`remainingMillis 1500→1000`**；**`remainingPoints 3→2`**（剩余段变短） |
| 无路线单位 | `movement === null`；面板 `无（无在途路线）`；不画线；**零 pageerror** |

**变异 3 轮 0 存活**：m1（`ApiViews` 退回布尔）⇒ `STEP b-movement-is-object: FAIL {"movement":true}`；m2（不调 `evaluate`）⇒ `STEP b-status-in-transit: FAIL null`；**m3（额外一轮，变异 `panels.js` 资源）** ⇒ `STEP c-panel-step-cost-1500: FAIL`——**用来证明"前端资源 + classpath 两份"的装置路径真能杀**（前端护栏强度是本项目系统性开口项，故补一轮）。

**裁定 M7b-S6 —— T2 的三条（控制器接受）**：
1. ★★ **它修好了自己装置里的一个假绿**（**"装置的产物自己也要自指"的同族新实例**）：`.class` md5 聚合模式写成 `"$CLASS_NAME".*.class`——**多一个点** ⇒ 只匹 `ApiViews.X.class`、**不匹 `ApiViews.class`** ⇒ 聚合读到**空串**（`d41d8cd9…` = `md5("")`）⇒ "还原相等"退化成 **`空==空` 的恒真断言**。改为 `"$CLASS_NAME"*.class` 并**先断言非空**再比较。**记入纪律形态（与"先怀疑自己的读取"同族）。**
2. **陈旧消费方故意不改**：历史证据 `t7-evidence/e2e/e2e.cjs:257` 与 `t5-evidence/e2e/e2e.cjs:271` 仍按**旧布尔**断言 `movement`——它们是**已关账任务的存档证据、不在任何门禁里**；**改历史证据 = 篡改留痕**，故**不改**，并在报告里记明。**接受**（这是正确的纪律判断）。
3. **`remainingMillis` 同刻取整段**：领域命令继承父行时刻（M4 裁定 35）⇒ `departedAt.tick == 查询 tick` ⇒ 同刻预算 0 ⇒ `remaining = 1500`（整段未付）。**是设计事实，不是 app 层重算**。

★ 如实记（T2 报告 §七，8 条）：折线**未逐点做 `getImageData` 像素证明**（只有钩子 + 截图）；**`ARRIVED`/`NEED_REPLAN` 未在页面观察**（只覆盖 `IN_TRANSIT`）；**异质地形的 ETA 未测**（demo 单一 desert）；MCP 面与 GUI 面"完全同形"只靠 code review（两处手写重复、无逐字节对拍）；`map.js` 的 classpath 资源路径未单独变异；m2 轮因 JS null 提前终结、未观察后续步骤。
