# M8 台账 —— 地图编辑写面（`map.*` 命令族 + 地图编辑/区域编辑两模式）

> 阶段：M8。**本台账记裁定与结论，不记取证过程**。
> 前置：M0~M7g 已完成、`main` 已与 `feat/adr1-core-scope` 同步（`2fd3518`）。

## 一 范围

把 M7 建的**只读工作台**扩展出**编辑能力**（用户 M7 期原话的剩余部分）：
- **地图编辑模式**：改区域信息、**选地形画图**、**圈一块区域做地形随机化**、**编辑河流/道路**；
- **区域编辑模式**：选一个**区域标签**绘**新区域**、**选中标签编辑时以淡色显示其他区域**。

## 二 已裁定（M7 期已批，无需再问）

| # | 项 | 裁定 |
|---|---|---|
| **S4** | `map.*` 命令**粒度** | **语义化命令**（每编辑一条，规则放 `simos-map`；**不做**通用 `ApplyChangeSet`——那会把"什么是合法地图编辑"推给浏览器，且重建河流的 `EdgeTags` 合并语义等会**无人负责**） |
| **S5** | 圈选随机化的**选区** | **允许任意选区**（给 `RegionRandomizer` 加吃 `Set<HexCoord>` 的重载，不强制先建 Region；原版种子从 region id 派生 ⇒ 新重载需一个**确定的**种子来源） |

## 三 ★★ 地基裁决 M8-U1（用户，2026-09-19）

> 用户原话：「**hex 只是地形块，应当兼容多种从属**」

**裁定**：**区域从属是多对多**——一个 hex **可以同时属于多个区域**；**不存在"重叠时谁赢"这个问题**。
★ 这条**否掉了控制器提问的前提**：控制器给的三档（禁止重叠 / 定归属规则 / 后写覆盖）**全都预设了"唯一归属"**，前提就是错的。

**连带后果（必须逐条落地，否则新语义只落一半）**：
1. `RegionIndex`（M2 派生件）由 `hex → 1 region` 改为 **`hex → Set<RegionId>`**；
2. `MapResolver.regionOfHex` 由 `Optional<RegionId>` 改为**多值**（候选集/列表）；
3. `/api/map/hex` 的 `region` 字段改为 **`regions: [...]`**；
4. **画/改区域不因重叠报错**（重叠是**正常状态**，不是错误）；
5. 区域查看的**多区域同亮**（T6 的 `highlightRegions` 已是数组）**语义上名正言顺**，可直接用。
★ **代价（先说清）**：牵动 **M2 的 `RegionIndexGuardTest`** 与 **M5 的 `ApiViews.mapHex`**（含 T4 加的 `region` 断言）⇒ **既有断言要按新语义改，改完必须仍有判别力**（不得改成恒真）。

## 四 待 spec 裁决的其余项（控制器将逐条提裁定，用户可推翻）

| # | 项 | 控制器初步意见 |
|---|---|---|
| Q1 | 编辑**是否要撤销/重做** | M7-S10 已定"不做"；编辑流靠时间线分岔/回退 ⇒ 建议**不做**，但要写清"想撤销就 fork 到前一节点" |
| Q2 | **河流/道路编辑**做到什么程度 | `EdgeTags` 的**合并语义**（M2 台账挂起项：重建河流会整份覆盖）**必须在 spec 里定**；建议"编辑命令必须显式声明是**替换**还是**合并**" |
| Q3 | 区域**创建/删除**面 | 建议：`map.CreateRegion` / `map.DeleteRegion` / `map.UpdateRegion`（改 hex 集合或 meta）三条；`RegionId` 由调用方给（**不自动生成**，便于复现） |
| Q4 | `GenerationSpec` 能否改 | **不能**（铁律 5：`spec` 不进变更集、`apply` 从 base 取）⇒ 重生成必须走别的路；spec 里写明 |
| Q5 | 地形**画图**的粒度 | 「选地形 + 点/拖画一串 hex」⇒ 一条命令带**多个 hex**（不是每格一条），便于撤销与节点干净 |

## 五 任务

| # | 任务 | 状态 |
|---|---|---|
| T0 | **spec**（含 §三 地基裁决的落地清单 + §四 Q1~Q5 裁定 + 判据 + 任务分解） | ⏳ |
| T1+ | 待 spec 定 | ⏸ |

## T3 ✅ `map.SetTerrain`（块感知）（`6c65c23` → 合并见下；10 文件 +858/−15）

**语义**：`map.SetTerrain(hexes, terrain)` 落在 `simos-map`；**改块 + 整体重切分**（`TerrainBlocks.split` 重切 ⇒ 合并/拆分自然发生），**不逐格写地形**；变更集**只有 `terrainBlocks` 非 Unchanged**（`hexes` 高度恒 Unchanged ⇒ 逐组件独立性保住）。校验序：判空 → `TerrainCatalog.of`（**词表 fail-closed**）→ hexes 非空 → 每格 `hexes().containsKey`。
**真档实测**（**副本**，原档 md5 `9e13d856…` 未变；5817/5818 全程未动）：19441 格，直方图 `ocean 4506→4516 / plains 10719→10709`（**+10/−10 算术对上**）；`replayByteIdentical=true`。
**负例三连**：`Rejected[hexes 不得为空…]` / `Rejected[未知地形类型: forest]` / `Rejected[hex 不在图上: 9999_9999]`，**`revisionsRowsUnchanged=true rows=2`**。
**变异 m1~m3b 全杀**：删词表校验 / 只改块字段不重切（★ 被 `GameMap` 构造期**分割不变式**当场抓：`地形块键 desert@1_0 与块内容不符（内容派生得 plains@1_0）`）/ 打乱序装块 / `TreeMap→HashMap`。
**门禁 890** = 170/**289**/45/131/**158**/96，SpotBugs 0×6，ERROR 0。
**未核实**：跨 JVM 字节稳定（同 JVM 已测；无 HashMap 迭代序进结果）；重切单独耗时（226.88ms 是 `submit` 全链）；**GUI `/api/command` 发该命令未跑**（前端调色板归 T8）。

## T4 ✅ 区域三命令（`f5d1964` → 合并见下；11 文件 +1150/−11）

**三命令**（`simos-map` 的 `ops/RegionOperations` + 三 `spi` handler）：`map.CreateRegion{regionId,name,hexes,meta?}` / `map.UpdateRegion{regionId,hexes?,meta?}` / `map.DeleteRegion{regionId}`；**`RegionId` 调用方给**（Q3）；重复 id 的 Create **拒绝**、Update 二者**至少给一**、Delete 不存在**拒绝**；★ **重叠一律允许**（不校验、不裁剪）；边界一律经 **`Region.of` 重算**（不手造）；三者**只换 `regions` 组件**（`hexes`/`terrainBlocks` 恒 Unchanged，逐条断言）。
**★ 重叠正例（真档副本，非合成）**：`regionCount 2→3`、`regions=[test_annex_target, test_nation, t4_overlap]`；重叠格 **`(-18,0)` ⇒ 3 个从属**（字典序）；`replayRegionsByteIdentical=true`；原档 md5 `2348b936…` 跑前=跑后。
**变异 m1~m4 全杀**，★ **m3 是方向性护栏**——**给 `createRegion` 加"与已有区域相交就拒绝"** ⇒ 重叠正例当场红（**谁加了这条限制，测试立刻抓**）。
**门禁 924** = 170/**321**/45/131/**161**/96，SpotBugs 0×6，ERROR 0。
**未核实**：浏览器内 3 从属的渲染（归 T9/T10）；`UpdateRegion` **改 name 无入口**（spec 载荷不含 name）；超大区域 `RegionBoundary.of` 重算耗时未单测。

## T7+T8 ✅ 五模式框架 + 地图编辑 UI（`3a06a70` → 合并见下；6 文件 +682/−29）

**T7**：五模式（常规/区域查看/地图编辑/区域编辑/单位移动编辑）**从占位变真控件**（`#mode-current` + `aria-pressed` + `body[data-mode]` 可断言）；★★ **白名单是纯函数模块** `modes.js`（`SimosModes.isWriteAllowed`，无 DOM/IO），在 `app.js` 的 `writeCommand` **发请求之前**把关（**fail-closed**）；未知模式/空 type ⇒ 拒绝；`allowedWrites` 返回**快照**。★ **常规/区域查看：真拖（canvas 25 步）+ 直调 `writeCommand` ⇒ `{ok:false,kind:"mode-denied"}`、非 GET = 0**。切模式清 `selection`/`highlightRegions`。
**T8**：**地形调色板**（完全取自后端 `terrainTypes`，实测 `["ocean","plains","low_hills","mountains"]`，**无 forest/tundra**）+ ★★ **拖刷**（真 pointer 事件，拖 5 格 ⇒ **一条** `map.SetTerrain`：`hexes` 长 5、**`head` 1→2 恰 +1**、命令明细 +1、M7f 的 tick 分组未破）+ **区域信息编辑**（多值 `regions`；只改 meta 的 `map.UpdateRegion` ⇒ `color #fc6dce→#123456`）+ 提交后**离屏位图重建**（`terrainRebuilds 4→5`，像素 `[31,95,160]`==ocean `#1F5FA0`）。
**e2e 27 断言 ALL PASS**（真档 19441 格副本，原档 md5 未变、5817/5818 未动）；负例原文 `未知地形类型: forest` / `hex 不在图上: 9999_9999`；非 GET 清单 `["/api/command"×7]`，**常规/区域查看阶段为 0**；0 `pageerror`。截图 `t7-evidence/logs/clean-after-mutants/screenshot-map-edit-brush.png`、`…/screenshot-map-edit-after.png`。
**变异 5 轮 0 存活**：m1 给"区域查看"放行写 / m2 切模式不清状态 / **m3 每格一条命令** / m4 调色板硬编码 `forest` / **m5 数据变不重建位图**。★ **自曝装置坑**：m1 还原时把 `src/modes.js` 写回旧版，致 m2~m5 e2e 出现**假红**——**先怀疑自己的装置**，如实记。
**门禁 924** 不变（纯前端 delta 0）。
**未核实**：浏览器内 **>2 从属**未渲染（Java 探针证过 3 从属）；`SetEdge`/`RandomizeRegion` 白名单放行但 **UI 置灰、从未真发**（T11）；**区域编辑模式 UI（T10）不存在**。
