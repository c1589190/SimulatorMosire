# 调查：D-034 时间线节点编辑器 / 三模式 / 地址查询的现状核对（只读）

> **状态**：调查事实（2026-11-13）· **不是设计、不是计划、不是裁定**
> **设计目标**：`docs/superpowers/specs/2026-11-13-timeline-node-editor-and-address-query-design.md`（D-034）
> **基线**：`main` @ `4f2552ce`（2026-10-23 23:22）；工作树仅原有 `.opencode/` 未跟踪
> **方法**：读代码 + 读 schema + 起临时实例实测（small-world / v17levant / map-only 导入库）+ 读 GSim 参考实现
> **边界**：本文只写“现在实际是什么”；未实现项一律写“缺/未验证”，不写成计划。

---

## 0. 结论摘要

1. **增量存储的底座已经存在**：`revisions` 表每行就是“相对父 revision 的 changeset”，checkpoint 是周期性的全量快照，分叉点强制写 checkpoint；`Replay` 从最近 checkpoint 沿父链施加 changeset。D-034 要的“父节点 + 增量”在数据模型上已经成立。
2. **但“节点”不是一等公民**：当前是**每推进一天落一条 revision**（一次 `AdvanceTime(0→3)` 实测落 3 条 revision），前端只是把同 tick 的多条 revision **视觉归并**成一个节点。没有节点表/节点标记/压缩，也没有“只在节点处存增量”的策略。
3. **分叉能力已存在，但只能从 head 分叉**：实测从 head 分叉 `tree2`、再独立推进到 tick5 成功；但从历史 revision 分叉会 409。**任何写命令也只允许对着 head 提交**，因此“在 tick0 编辑底图 / 在 tick360 再分叉”当前做不到。
4. **tick 不是唯一坐标**：同一分支同一 tick 可以有多条 revision（实测 `main` rev1/2/3 都在 tick0：bootstrap + 两条 `map.SetTerrain`）。`tree1-360` 这种地址必须先定义“该 tick 的哪个 revision/节点”，否则不唯一。
5. **地址体系已经有大部分实体**：`map:<mapId>:hex/region…`、`unit:<id>`、`army:combat.<id>`（含 stage）、`army:army.<id>` 都在；battle 记录有 `id/kind/tick/hex/participants/stages/atRevision`。缺的是“分支+节点前缀”“tick→revision 解析”“统一实体详情（位置 + 最近战斗）”。
6. **三模式与现有 GUI 是“可收敛”的**：当前 6 个模式（常规/区域查看/地图编辑/区域编辑/单位移动编辑/决策）；区域编辑可并入地图编辑，单位编辑已有 8 条写命令但缺 gov/army 类型小窗，战役只有只读浮层、没有编辑模式。
7. **“暂停经济”不是隐藏 UI 那么简单**：经济/生产/文化已深度接进 codec、handler、时间参与者、resolver、facet、工具面、GUI 和世界生成器；当前没有模块开关/profile。好在 map-only 导入库实测能启动（只差 facet/resolver 对缺切片的容忍）。
8. **现有两条跨模块守卫与 D-034 的“可删除、其他模块看到空”直接冲突**：`RegionDeleteGuard` 禁止删国家 tag/homeRegion 的区域；`GovJurisdictionGuard` 禁止会造成 GOV 辖区重叠的 `map.UpdateRegion`。暂停 gov 后前者可随 gov 一起卸，后者是否保留需要用户裁定。

---

## 1. 时间线与增量存储

### 1.1 现有存储形状（已实现）

`simes-core` 的 `revisions` 表：

```text
branch, revision, parent_branch, parent_revision, tick, calendar_label,
command_id, correlation_id, initiator, command_type, changeset_json
PRIMARY KEY (branch, revision)
```

- `changeset_json` 是**相对父 revision 的全模块变更集**（`WorldChangeSet`），由各模块 `between(base, target)` 派生；见 `simos-core/.../SqliteStore.java:42`、`Timeline.java:61`、`Timeline.java:368`。
- checkpoint 是**完整状态信封**，落在 `checkpoints/<branch>/<revision>.json`；`Timeline.hasCheckpoint` 的判定是“revision % N == 0 或分叉父或创世”，见 `Timeline.java:356`、`CheckpointStore.java` 类注。
- `Replay` 从父链上最近的可用 checkpoint 出发，逐个 `apply` 中间的 changeset；见 `simos-core/.../Replay.java:31`、`Replay.java:139`。

**结论**：D-034 的“父节点 + 增量”语义与现有 `revision.changeset_json` 基本同构；现有模型还多了“周期全量 checkpoint”这层纯缓存。

### 1.2 与 D-034 的差异：当前是“每天一条 revision”

实测（临时 small-world，`/tmp/simos-advance-probe`）：

```text
POST /api/advance {branch:main, expectedRevision:1, from:0, to:3}
=> {"result":"committed","ref":{"branch":"main","revision":4}}
GET /api/timeline => 4 nodes:
  rev1 tick0 core.Bootstrap
  rev2 tick1 core.AdvanceTime
  rev3 tick2 core.AdvanceTime
  rev4 tick3 core.AdvanceTime
```

即**一条“推进 3 天”命令落 3 条 revision**。代码注释也明写：“一次 N 天的推进落 N 条 revision（360 tick = 361 条；`checkpointInterval=1` 下旧实测 1.45 GB）”（`simos-core/.../TimeAdvance.java:70-71`，逐日循环在 `:335`）。

D-034 要求“tick0 全图，tick360 只存相对 tick0 的增量，tick0~359 不保存”。当前实现是：

- 每天一条 revision、每天一份增量；
- 每 N 条 revision 一份**全量** checkpoint（不是“节点增量”）；
- 没有节点表、没有节点标记、没有压缩/合并。

**缺的东西**：

1. “节点”实体（`(branch, tick)` 或显式 nodeId）；
2. 节点级增量（从上一节点状态到本节点状态的 `WorldChangeSet`，而不是逐日增量链）；
3. 中间 revision 的压缩/隐藏/不落盘策略；
4. 节点级 checkpoint 策略（D-034 的节点增量 vs 现有周期全量）。

### 1.3 现有 checkpoint 成本（实测）

- 19 格 small-world：`checkpoints/main/1.json` 约 **580 KB**（含 economy 等 10 个切片）。
- 59,223 格 v17levant：checkpoint 约 5 MB 量级（`test-world/store/checkpoints/main/1.json`）。
- 360 天逐日 revision + `checkpointInterval=1` 的旧实测为 **1.45 GB**（`TimeAdvance.java:71` 自述）。

这说明 D-034 的“节点级增量”如果实现，能显著减少中间态存储；但当前没有现成压缩器。

---

## 2. 分支与节点

### 2.1 分支已经能工作（从 head 分叉）

实测（同一 small-world 临时库）：

```text
从 main head(rev4,tick3) 分叉 tree2
=> {"result":"committed","ref":{"branch":"tree2","revision":1}}
tree2 推进 3 -> 5
=> tree2 rev3 tick5
GET /api/timeline?branch=tree2:
  rev1 tick3 core.ForkBranch parent={main,4}
  rev2 tick4 core.AdvanceTime parent={tree2,1}
  rev3 tick5 core.AdvanceTime parent={tree2,2}
main 保持 head=4/tick3 不变
```

- 分支名是任意非空白字符串（`BranchId`），`tree1`/`tree2` 合法。
- 分叉行是“新分支 revision 1、parent = 分叉点、空 changeset”（`Timeline.java:210-247`）。
- 前端时间轴已能画分叉连线、按 tick 分组（`timeline.js:10-13`、`timeline.js:142`）。

### 2.2 硬限制一：分叉只能从 head

`Timeline.fork` 先查 `head(source)`，不等于 `expected` 就返回冲突（`Timeline.java:219-223`）。实测：

```text
main head=2(tick1) 时，POST /api/fork {source:main, expectedRevision:1, newBranch:tree2}
=> 409 {"result":"conflict","current":{"branch":"main","revision":2}}
```

因此 D-034 的“从 `tree1-tick360` 分叉出 `tree2-tick600`”在 `tree1` 已经推进到 720 后**当前做不到**；必须先分叉、再继续推进，或者改 Core 的 fork 语义。

### 2.3 硬限制二：写命令只能对着 head 提交

`CommandBus` 的入口乐观并发检查：`head(branch)` 必须等于 `expectedRevision`（`CommandBus.java:733-735`）。实测在 head=rev2 时对 rev1 发 `map.SetTerrain`：

```text
POST /api/command {type:map.SetTerrain, branch:main, expectedRevision:1, ...}
=> 409 {"result":"conflict","current":{"branch":"main","revision":2}}
```

因此 D-034 的“地图编辑模式在所有格式化节点都可以进入、修改当前节点的地图（包括 tick0 底图）”**当前在服务端被直接拒绝**；GUI 虽然能把游标移到历史 revision、也能进入地图编辑模式，但写会 409。

### 2.4 tick 不是唯一节点坐标

实测同一分支同一 tick 可有多条 revision：

```text
main rev1 tick0 core.Bootstrap
main rev2 tick0 map.SetTerrain
main rev3 tick0 map.SetTerrain
```

原因：地图编辑等命令不推进时间，多个命令可以落在同一 tick。前端的时间轴已把同 tick 归并成一个“视觉节点”，并把游标定位到该 tick 的**最后一个 revision**（`timeline.js:13`、`timeline.js:142-175`）。

因此 `tree1-360` 若要作为唯一地址，必须明确一种节点身份约定，例如：

- `(branch, tick)` → 该 tick 的最后一个 revision（前端现有口径）；
- 或新增显式 node 表/列，给每个节点一个稳定 id；
- 或规定一个 tick 只允许一个节点 revision（会与现有“同 tick 多命令”冲突）。

**当前没有后端节点表/节点 id**；这是 D-034 地址格式必须先解决的点。

---

## 3. 地址与查询

### 3.1 现有地址语法

`Address` 是“命名空间 + 根主体 + 可选 kind.name 段”，段间 `:`、段内 `.`，首段必须是裸词命名空间（`Address.java:6`、`AddressParser.java`）。现有命名空间与 canonical 形态：

| 目标 | 现有 canonical | 代码 |
|---|---|---|
| 整图 | `map:<mapId>` | `MapResolver.java:30` |
| 单格 | `map:<mapId>:hex.<q>_<r>` | `MapResolver.java:31` |
| 区域 | `map:<mapId>:region.<id>` | `MapResolver.java:32` |
| 城市 | `map:<mapId>:city.<id>` | `MapResolver.java:33` |
| 单位 | `unit:<unitId>` | `UnitResolver.java:29-30` |
| 军队身份 | `army:army.<id>` | `ArmyAddresses.java:81` |
| 战斗 | `army:combat.<id>` | `ArmyAddresses.java:48` |
| 战斗阶段 | `army:combat.<id>:stage.<sid>` | `ArmyAddresses.java:60-70` |

**没有** `battle:`/`gov:` 命名空间；`gov` 单位是 `unit:<id>` 且 `Unit.module.kind=gov`，`army` 单位是 `unit:<id>` 且 `module.kind=army`；`army:army.<id>` 是 Army 身份层（rootUnit + masterGov），不是 Unit 本身。

### 3.2 分支/节点不在地址里

`QueryTarget` 是 `(BranchId branch, RevisionId revision)`（`QueryService.java:189`），由查询参数单独给：

- HTTP：`GET /api/resolve?address=...&branch=...&revision=...`（`GuiServer.java:2263`）；
- MCP：`simos.state.resolve` 的 `branch` / `revision` 参数（`StateResolveTool.java:66`、`ToolSupport.target`）。

因此：

- 地址本身**不能**表达 `tree1-360`；
- 不能用 tick 查，只能 revision 查；
- 读历史 revision 是支持的（`QueryService.stateAt`），写历史 revision 不支持（见 §2.3）。

### 3.3 resolve 只给身份，不给详情

- `QueryService.resolve` 返回 `QueryResult`（候选列表：id/canonicalAddress/typeName），见 `QueryService.java:162`、`ApiViews.resolveResult`。
- `facets` 只追加注册过的 facet（当前 `unitsHere`、`population`），见 `Shell.java:1239-1240`、`UnitsHereFacet`、`PopulationFacet`。
- 真正的详情分散在 `/api/units`、`/api/unit/{id}`、`/api/map/hex`、`/api/army/combats` 等域端点。

D-034 要的“输入 `tree1-360/unit/army/vg-berlin` 立刻拿到位置 + 刚刚经历的 battle”需要**一个新的统一查询视图**：解析地址 → 取节点状态 → 按类型取域视图 → 联查关联实体（位置、最近战斗）。当前没有这个端点/工具。

### 3.4 战斗地址已有，但详情联查缺

- `CombatRecord` 有 `id / kind / tick / hex / participants / text / stages / atRevision`（`CombatRecord.java:67`）。
- 地址 `army:combat.<id>` 和 `army:combat.<id>:stage.<sid>` 已实现（`ArmyAddresses.java`、`ArmyResolver.java`）。
- `/api/army/combats` 可按 tick/格读；`/api/unit/{id}` 已经删掉 `combat` 字段（`ApiViews.java` 类注：2026-10-12 批次 3d-2 删除），单位与战斗的关联要查 Army 侧。
- **没有**“按 participant 反查最近战斗”的读口；统一地址查询需要新增这个联查。

### 3.5 标准区域没有结构化 kind

`RegionMeta` 只有 `color / tag / description / annexedBy` 四个字符串（`RegionMeta.java:8`）。省/国家的语义靠 `tag` 字符串约定（例如 `NationTag` 前缀、`province`），不是结构化 kind。

- 地图编辑“按种类筛选”目前没有后端 kind 字段；前端只有按 tag 的聚焦/淡化（`map.js:606`、`map.js:798`）。
- 区域地址 `map:<mapId>:region.<id>` 可以按 id 搜，但没有“列出某 kind 的全部区域”的读口。

---

## 4. 三模式与 GUI 现状

### 4.1 当前是 6 个前端模式

`webui/modes.js:21-89`：

| 模式 id | 标签 | 写命令白名单 |
|---|---|---|
| `view` | 常规 | 无 |
| `region` | 区域查看 | 无 |
| `map-edit` | 地图编辑 | `map.SetTerrain` / `map.SetEdge` / `map.RandomizeRegion` / `map.UpdateRegion` |
| `region-edit` | 区域编辑 | `map.CreateRegion` / `map.UpdateRegion` / `map.DeleteRegion` |
| `unit` | 单位移动编辑 | `unit.PlanRoute` / `CancelRoute` / `ReparentUnit` / `SetComposition` / `DisbandUnit` / `CreateUnit` / `AttachUnit` / `DetachUnit` |
| `decision` | 决策 | `ga.StartDecision` / `ga.RunDecision` / `ga.ResetDecisionMakerConversation` |

**与 D-034 的对应**：

- 区域编辑 → 应并入地图编辑（D-034 已明确）；
- 单位移动编辑 → 保留为单位编辑，但需增加 gov/army 类型小窗；
- 决策模式 → 暂停；
- 战役编辑 → 新增，当前完全没有写入口。

### 4.2 模式只是前端概念（与 D-034 一致）

- `modes.js` 的 `isWriteAllowed` 只在前端 `app.writeCommand` 里把关（`app.js:423-431`）。
- 服务端 `POST /api/command` 对命令类型不做模式校验，直接构 `CommandEnvelope` 交给 `core.submit`（`GuiServer.java:1595-1621`）。
- 因此“工具面没有模式、只有工具组”与现有架构一致；新增战役编辑模式只需要前端白名单 + UI，不需要新“模式”协议。

### 4.3 地图编辑当前能做什么

- 地形刷、圈选随机化、河流/道路、区域套索/合并/剔除/删除、区域 meta 编辑（`map-mapeditor.js`、`map-regioneditor.js`、`index.html:153-218`、`index.html:426-447`）。
- 不能在地图编辑模式里改单位位置/删除单位：`unit.PlaceAt` / `unit.DisbandUnit` 不在 `map-edit` 白名单，只在 unit 模式或调试页（`unit.html`）里。
- 不能在历史节点写（见 §2.3）。

### 4.4 单位编辑当前能做什么

- 8 条写命令：路线、取消路线、改父、改编制、解散、新建、加入编队、脱离编队（`modes.js:54`、`map-uniteditor.js:6-8`、`index.html:103-150`）。
- 单位树/编制浮层已有（`unitTree.js`、`index.html:528+`）。
- **没有 gov/army 类型小窗**：`GovernmentFormation` / `ArmyFormation` 的专门命令（`unit.SetGovFormation`、`unit.SetGovPolicy`、`unit.SetGovSuperior`、`unit.AssignGovPost`、`unit.SetArmyFormation`、`unit.SetArmyPayPolicy`、`unit.CreateCommandChain` 等）只存在于 MCP 工具/命令面，GUI 没有对应表单。

### 4.5 战役编辑当前完全没有

- 只有只读事件浮层 `events.js`：读 `/api/army/combats`，按格/当前 tick 展示战斗详情（`events.js:1-8`、`events.js:451`）。
- 前端没有任何 `army.RecordCombat` / `army.AppendCombatStage` / `army.ResolveCombatStage` 写调用（grep 0 命中）。
- 后端有 7 个 army handler：`CreateArmy` / `RecordCombat` / `AppendCombatStage` / `ResolveCombatStage` / `SetArmyMasterGov` / `SetArmyContactWatch` / `AssignDecisionMakerArmy`。
- **没有战斗删除/改名/改 hex/改 participant 的命令**；战斗记录 id 是一次性身份，同 id 再写会被拒。
- `RecordCombatHandler` 只校验 id/kind/tick/hex/participants 形态与“不得记在未来”，**不校验 participants 是否在 hex**（`RecordCombatHandler.java:164-195`，无 position 校验）。所以“战斗强跟随单个 hex”目前只是记录字段，不是强制不变量。

---

## 5. 区域 / 单位删除语义与守卫

D-034 要求地图编辑只改“存在信息”、允许删除、其他模块按 key 查到空自行处理。现状：

- `map.DeleteRegion` 本身不做跨模块级联（`DeleteRegionHandler` 只改 map 切片）。
- `unit.DisbandUnit` 只检查“仍有下属 / 仍是命令链成员”，**不检查** army/battle/gov 的外部引用（`UnitOperations.java:1529-1560`）。
- 其他模块的读路径大多按 key 查不到就空/跳过；写路径（如 `unit.SetJurisdiction`、`gov.CreateNation`）则对缺失 region **具名拒绝**（`UnitOperations.java:711`、`CreateNationHandler` 类注）。
- **两条守卫直接冲突**：
  1. `simos-gov/.../guard/RegionDeleteGuard.java:35-75`：`map.DeleteRegion` 目标带国家 tag 或是 Nation.homeRegion ⇒ 拒绝；
  2. `simos-app/.../world/GovJurisdictionGuard.java:76+`：`unit.SetJurisdiction` / `map.UpdateRegion` / `map.ReassignHexes` 会造成 GOV 辖区重叠 ⇒ 拒绝。

暂停 gov 后 `RegionDeleteGuard` 可随 gov 一起不注册；`GovJurisdictionGuard` 是否保留、D-034 的“自由删除/改区域覆盖”如何处理，需要用户补裁。

---

## 6. “暂停经济/文化/生产力”的耦合面

### 6.1 当前装配（无模块开关）

`ShellConfig` 只有 `storeDir / checkpointInterval / guiPort / mcpPort / mcpPath / mcpInitiator / mapId / bindAddress / openingSnapshot / worldId`，**没有 enabledModules / profile**（`ShellConfig.java:24-34`）。

`Shell.start()` 无条件注册：

- 10 个 codec：map/social/unit/economy/actor/gov/army/ga/production/culture（`Shell.java:593-619`）；
- 149 条 handler 注册（`Shell.java:700-1100` 实测 `grep -oE 'new [A-Za-z]+Handler\(' | wc -l` = 149），其中 `Economy*` 前缀 38 个；
- 7 个 resolver：map/social/unit/ga/economy/actor/army（`Shell.java:1223-1236`）；
- 2 个 facet：unitsHere/population（`Shell.java:1239-1240`）；
- 3 个 time participant：`PopulationUnitTimeParticipant`（经济+人口+unit 合并）、`GaTimeParticipant`、`ArmyTimeParticipant`（`Shell.java:1158-1167`）；
- 全量工具面（`SimosToolSource`，2145 行；MCP 实测 182 条）。

### 6.2 经济与 unit 的强耦合

`PopulationUnitTimeParticipant` 明确把 `PopulationEconomyTimeParticipant` 与 `UnitTimeParticipant` 合成**一份提案**（类注 `PopulationUnitTimeParticipant.java:39-42`），因为逃亡执行要求 Social/Economy/Unit 同一条 revision。`PopulationEconomyTimeParticipant` 本身 2266 行，构造 `EconomyDayStepper`、`ProductionRuntimeEngine`，并读写 actor/map/social/gov/unit/production 多个切片。

**暂停经济不能只“不注册 economy codec”**：`PopulationUnitTimeParticipant` 会直接编译/运行依赖 economy；必须把它换成 unit-only 参与者（或加经济关闭分支）。

### 6.3 GUI/工具面耦合

- GUI 有 4 条 economy 只读端点（`GuiServer.java:286-316`：overview/gov/hex/ownership）和 economy/heatmap 面板（`webui/index.html`、`panels.js` 41 处 economy 命中）。
- MCP 182 条工具里，按名称粗分：economy 14、social 9、gov 23、ga/gm 42、unit 35、map 16、army 11、docs/workspace 17、core/read 13。
- `SimosToolSource` 是单个大类，没有按模块分组的 tool source；暂停经济需要过滤/拆分工具注册。

### 6.4 map-only 世界实测

用 `tools/gsimap_import.py` 导入 `n0000_map.json`：

- 产出 59,223 hex / 98 区域 / 240 条边，0.3 秒；
- 只保留 `map` 切片时，当前 HEAD 的 jar **能启动**，`/api/state`、`/api/map/overview` 正常（59,223 格 / 98 区域 / 782 块）；
- 但 `/api/map/hex` 报：

```text
状态里没有 unit 模块切片——UnitsHereFacet 需要 UnitSnapshot（装配故障，不是"没有候选"）
```

- 导入器原样输出（带 `social`/`unit` 旧空切片）时，整库打不开：

```text
SocialData.provisioning 不得为 null（旧档缺此组件已作废，不做缺省兜底；新世界请显式给 provisioning）
```

**结论**：map-only 产品面**已经能启动**；需要的是模块 profile（按启用模块注册 codec/handler/resolver/facet/participant/工具）和“缺切片时 facet 不注册/返回空”的策略，而不是重写存储。

---

## 7. GSim 参考实现（“老前辈”）

### 7.1 节点与增量

- `NodeSnapshot`：`nodeId / parentId / turn / worldTime / status / createdAt / checkpoints / attachments`（`~/DevMosire/GSimulator/gsim-core/.../NodeSnapshot.java:26`）。
- 根节点存完整 `MapData`；子节点存 `MapDiff`（`MapDiff.java:17-34`）：`parentNodeId / changed / removed / provinces_changed / provinces_removed / cities_added / cities_removed / rivers_added / roads_added`。
- `MapService.saveMap` 自动选择：根节点 `saveFull`，子节点 `MapDiff.compute(parent, updated)` + `saveDiff`（`MapService.java:314-330`）。
- `MapResolver` 从目标节点沿 `parentId` 走到根，反转后从根全量开始逐个 `applyDiff`（`MapResolver.java:23-31`、`:62`）。
- 节点是树（`parentId`）；`node_create` 从当前活跃节点创建子节点，`turn` 自动 +1，`worldTime` 是字符串（`NodeCreateTool`）。

### 7.2 地址查询

- `query_address` 是统一路由（`gsim-core/.../QueryAddressTool.java:30`）：
  - `gsimap:*` → `gsimap_query_by_address`（`gsimap:region:{name}` / `gsimap:hex:{q}_{r}` / `gsimap:city:{name}` / `gsimap:terrain:{key}`）；
  - `nodeId:checkpointId:key` 或 `checkpointId:key` → `query_element`；
  - 纯文本 → 按 tag 查。
- `nodeId` 是**单独参数**，不在 `gsimap:` 地址里；`gsimap_query_by_address` 的 `nodeId` 缺省取 active node。

### 7.3 与 D-034 的差异

| 维度 | GSim | simos 现状 | D-034 目标 |
|---|---|---|---|
| 节点身份 | `nodeId`（n0000…）+ turn/worldTime | `(branch, revision)` + tick；无 node 表 | `tree1-360` 式节点 |
| 增量 | 根全量 + 子 MapDiff | 每条 revision changeset + 周期全量 checkpoint | 节点增量 |
| 分叉 | `parentId` 树，可从任意节点建子节点 | 分叉只能从 head | 从任意节点分叉 |
| 地址 | `gsimap:` + 单独 nodeId | namespace 地址 + 单独 branch/revision | 分支+节点+类型+子类型+id 一体 |
| 统一查询 | `query_address` 路由 | `resolve` + `facets` + 域端点 | 统一实体详情 |

---

## 8. 与 D-034 的差距清单（按严重度）

### P0：挡住 D-034 主路径

1. **历史节点不可写**：`CommandBus` 只允许 head（`CommandBus.java:733-735`）。地图编辑“所有节点可进入、修改当前节点（含 tick0 底图）”必须新增“对非 head 节点写”的语义（新分支/重写/重放校验三选一）。
2. **分叉只能从 head**：`Timeline.fork` 的 head 检查（`Timeline.java:219-223`）。从 `tree1-tick360` 分叉需要放宽为“任意已存在节点”。
3. **没有节点实体**：`(branch, tick)` 不唯一（同 tick 多 revision 实测）；没有 `nodes` 表/节点标记；“格式化节点”无判定。
4. **没有节点级增量/压缩**：当前每天一条 revision + 周期全量 checkpoint；D-034 的“只存节点间增量”需要节点表 + 节点 `WorldChangeSet` + 中间 revision 压缩策略。

### P1：地址/查询面

5. **地址不含分支/节点**：`Address` 命名空间优先，branch/revision 是独立查询参数（`GuiServer.java:2263`）；需新语法或复合 `NodeAddress`。
6. **tick→revision 无解析**：读口只接受 revision；需要“节点解析器”（按 branch+tick 取该 tick 最后 revision 或节点 id）。
7. **resolve 只给身份**：位置/最近战斗等详情要跨域联查；没有统一 `query_address` 视图。
8. **没有按 participant 反查战斗**；单位详情已删除 combat 字段。
9. **区域没有结构化 kind**：只有 `RegionMeta.tag` 字符串；按种类筛选/搜索无后端支持。

### P1：三模式/编辑面

10. **地图编辑白名单缺单位存在信息**：`unit.PlaceAt`、`unit.DisbandUnit` 不在 `map-edit` 白名单；区域编辑仍在独立模式。
11. **单位编辑缺 gov/army 类型小窗**：相关命令已有，GUI 没有表单/子窗。
12. **战役编辑模式不存在**：只有只读浮层；战斗无删除/改 hex/改 participant 命令；`RecordCombat` 不校验 participant 在 hex。

### P1：暂停经济

13. **没有模块 profile**：Shell 无条件装配 10 codec、149 handler、7 resolver、2 facet、3 participant、182 工具。
14. **经济与 unit 时间参与者合并**：`PopulationUnitTimeParticipant` 把经济日结与 unit 移动绑在一起；暂停经济需拆/换参与者。
15. **GUI/工具面/世界生成器全耦合**：economy 4 端点、economy 面板/热力图、14 economy 工具、4 个世界生成器都默认带经济空切片/经济种子。
16. **旧档/旧切片**：map-only checkpoint 可启动；带旧 social/unit 切片不可启动；需要“按 profile 只解码已启用模块”或显式重建。

### P2：语义冲突/风险

17. **`RegionDeleteGuard` 禁止删除国家 tag/homeRegion 区域**，与“允许删除、其他模块自己处理”冲突（`RegionDeleteGuard.java:61-72`）。
18. **`GovJurisdictionGuard` 禁止会造成 GOV 辖区重叠的 `map.UpdateRegion`**，与“地图编辑自由改区域覆盖”冲突（`GovJurisdictionGuard.java:45-52`）。
19. **同 tick 多 revision 的前端归并口径未进后端**：`timeline.js:142-175` 的 `firstRevision/lastRevision` 只是视觉约定。
20. **`mapId` 与世界/分支不一致**：`config/shell.json` 固定 `mapId=small-world`；`--world=v17levant` 时 GUI 仍显示 `mapId=small-world`（2026-11-13 实测），地址会串。
21. **旧 store 全面不可读**：当前 HEAD 打不开 `run/small-world-db`（economy `productionRole`）与 `test-world/store`（map `HexCell.land`）；没有 schema 版本/迁移器。

---

## 9. 未验证 / 未做

1. 没有修改任何业务代码；没有跑 Maven 全量门禁。
2. 没有实现“节点表 / 节点增量 / 压缩 / 历史写 / 统一地址查询 / 三模式 UI”；本文只是现状核对。
3. 没有验证 59k 世界上的历史 revision 读写性能；没有验证多分支长链的存储体积。
4. 没有验证“底图修改后重放所有后代节点”的可行性；`CandidateStateValidator` 只在单次提交前调用（`Shell.java:1190` 注册 economy validator），没有 rebase/重放校验器。
5. 没有验证 map-only profile 下所有工具/端点（只验证了启动、overview、hex 的失败形态、导入器 map 切片）。
6. 没有验证 `army.RecordCombat` 的 participant 位置是否应由读取侧补校验；本文只指出当前没有校验。
7. 没有验证 GSim 的 `nodeId` 分叉是否也是任意节点（只读了 `NodeCreateTool` 当前活跃节点创建子节点 + `parentId` 树）。

---

## 10. 证据与复现

### 10.1 实测命令（临时 store，均在 `/tmp`，未碰仓库数据）

```bash
# 一次推进 3 天 -> 3 条 revision
tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store /tmp/simos-advance-probe --world=small-world --gui-port 5993 --mcp-port 5996
curl -s -X POST http://127.0.0.1:5993/api/advance \
  -H 'Content-Type: application/json' \
  -d '{"branch":"main","expectedRevision":1,"from":0,"to":3}'
curl -s 'http://127.0.0.1:5993/api/timeline'   # rev1 tick0 … rev4 tick3

# 从 head 分叉 tree2，再独立推进到 tick5
curl -s -X POST http://127.0.0.1:5993/api/fork \
  -H 'Content-Type: application/json' \
  -d '{"source":"main","expectedRevision":4,"newBranch":"tree2"}'
curl -s -X POST http://127.0.0.1:5993/api/advance \
  -H 'Content-Type: application/json' \
  -d '{"branch":"tree2","expectedRevision":1,"from":3,"to":5}'

# 历史节点写/分叉 -> 409
curl -s -X POST http://127.0.0.1:5993/api/command \
  -H 'Content-Type: application/json' \
  -d '{"type":"map.SetTerrain","payloadJson":"{\"hexes\":[{\"q\":1,\"r\":0}],\"terrain\":\"low_hills\"}","branch":"main","expectedRevision":1}'
curl -s -X POST http://127.0.0.1:5993/api/fork \
  -H 'Content-Type: application/json' \
  -d '{"source":"main","expectedRevision":1,"newBranch":"tree2"}'
```

### 10.2 map-only 导入实测

```bash
python3 tools/gsimap_import.py \
  ~/DevMosire/workspace/worlds/v17levant/nodes/n0000_map.json \
  /tmp/simos-import-probe
# 59,223 hex / 98 区域 / 240 条边
# 只保留 map 切片后当前 jar 能启动；/api/map/hex 因缺 unit 切片报错
```

### 10.3 关键代码位置

| 主题 | 位置 |
|---|---|
| revisions/changeset schema | `simos-core/src/main/java/io/mosire/simos/core/store/SqliteStore.java:42-56` |
| changeset 落盘 | `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java:61-63`、`:368` |
| checkpoint 判定 | `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java:356` |
| 重放 | `simos-core/src/main/java/io/mosire/simos/core/store/Replay.java:31`、`:139` |
| 逐日落 revision | `simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java:70-71`、`:335` |
| fork 只从 head | `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java:210-223` |
| 写只允许 head | `simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java:733-735` |
| 地址 AST | `simos-util/src/main/java/io/mosire/simos/util/address/Address.java:6`、`AddressParser.java` |
| QueryTarget | `simos-app/src/main/java/io/mosire/simos/app/query/QueryService.java:189` |
| branch/revision 查询参数 | `simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java:2263` |
| 六模式 | `simos-app/src/main/resources/webui/modes.js:21-89` |
| 时间轴 tick 分组 | `simos-app/src/main/resources/webui/timeline.js:142-175` |
| 战斗只读浮层 | `simos-app/src/main/resources/webui/events.js:1-8` |
| RegionMeta | `simos-map/src/main/java/io/mosire/simos/map/region/RegionMeta.java:8` |
| RegionDeleteGuard | `simos-gov/src/main/java/io/mosire/simos/gov/guard/RegionDeleteGuard.java:35-75` |
| GovJurisdictionGuard | `simos-app/src/main/java/io/mosire/simos/app/world/GovJurisdictionGuard.java:45-52` |
| CombatRecord | `simos-army/src/main/java/io/mosire/simos/army/CombatRecord.java:67` |
| ArmyAddresses | `simos-army/src/main/java/io/mosire/simos/army/ArmyAddresses.java:35-85` |
| 经济/unit 合并参与者 | `simos-app/src/main/java/io/mosire/simos/app/time/PopulationUnitTimeParticipant.java:39-42` |
| Shell 装配 | `simos-app/src/main/java/io/mosire/simos/app/Shell.java:593-619`、`:1158-1167`、`:1223-1240` |
| 无模块开关 | `simos-app/src/main/java/io/mosire/simos/app/ShellConfig.java:24-34` |
| GSim MapDiff | `~/DevMosire/GSimulator/gsim-map/src/main/java/com/gsim/map/map/MapDiff.java:17-34` |
| GSim 节点解析 | `~/DevMosire/GSimulator/gsim-map/src/main/java/com/gsim/map/map/MapResolver.java:23-31`、`:62` |
| GSim 统一地址 | `~/DevMosire/GSimulator/gsim-core/src/main/java/com/gsim/core/tools/worldinfo/QueryAddressTool.java:30` |
