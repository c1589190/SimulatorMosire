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

## T10 ✅ 区域编辑模式 UI（`9a47714` → 合并见下；5 文件 +734/−30）

**交互**：**新建区域**（真 pointer 拖选 ⇒ **一条** `map.CreateRegion`，★ **重叠不报错**）/ **改 hex**（右栏选区域 ⇒ 载入 hex ⇒ 图上增删 ⇒ 一条 `UpdateRegion`）/ **删除**（★ **二次确认**：确认前**零写**、取消仍零写、确认后**恰一条** `DeleteRegion`）/ ★ **焦点区域原色（alpha 0.52）+ 其它淡色（同色相 alpha 0.13）**（`body[data-region-focus]` + `regionEditDebug()` 的 `focus`/`fadedRegions` 可断言）。★ T8 的 meta 编辑器**移出复用**（`section.region-info-editor[data-modes="map-edit region-edit"]`），两模式共用不重写。
**★ 重叠正例（真档，核心判据）**：`CreateRegion` POST **恰 1 条**、载荷 5 格、`head 1→2`、`regions` 2→3；重叠格 `regions=["t10_overlap","test_annex_target","test_nation"]`（**3 项，字典序**）——"多对多、无谁赢"的**端点级证明**（原始 JSON 在 `logs/overlap-raw-json.log`；原档 md5 跑前=跑后）。
**变异 3 轮全杀**：★★ **m1 前端加"与已有区域相交就拒绝" ⇒ 8 FAIL**（方向性护栏兑现）；m2 去掉删除确认 ⇒ `e1-delete-unconfirmed` 红；m3 所有区域同色同 alpha ⇒ `c2-fade-colors-distinct` 红。
★ **本单 e2e 抓到真缺陷**：**焦点区域必须"先入高亮集合"**，否则**与别区重叠的焦点格会被淡色盖掉**（overlap × focus 的交叉缺陷，只有真重叠数据能暴露）。
**门禁 924 不变（纯前端 delta 0）**；e2e **27 断言 ALL PASS**；0 pageerror；5817/5818 未动。
**未核实**：真档上 **4 个及以上区域**的浏览器渲染（本轮最多 3 从属）；触摸/触控笔拖选；HiDPI（dpr>1）下淡色像素采样；`UpdateRegion` 改 name 无入口（T4 已记）。

## M8-R ✅ 区域编辑器重做（`e80175e` + 修正 `f9e3c0d` → 合并见下；纯前端）

**交互**：★ **右键拖动 = 自由套索**（`hexLine` cube 插值补点 → 质心 seed → `floodFillFromWall` → `hexes = 内部 ∪ 墙`）⇒ **恰 1 条 `map.CreateRegion`**（e2e 自写独立 flood **逐值相同**）；★ **选中区域 ⇒ 边界小点**（画在边界 hex 中心，**只对 focus 区域**；`boundaryDotCount=12 == 独立算出 12`；取消选中 ⇒ **0**）；**拖小点** ⇒ 一条 `UpdateRegion`（`19→20`，payload == `base ∪ {to}` 逐值）；**合并**（临时选区 ∪ 目标）/**剔除**（目标 − 临时选区）⇒ 各 **1 条 `UpdateRegion`**，**与并集/差集逐值相同**。
★★ **统一按键模型（用户裁定）**：**左键拖动 = 平移地图（全模式统一，永不误改）**；**右键按模式分派**（区域编辑=套索 / 地形编辑=`map.SetTerrain`（一串 hex ⇒ **1 条**）/ 常规·单位移动=`PlanRoute` 不变）；**Shift+右键 = 逐格画/擦**；★ **勾选框 `#region-edit-draw` 删除**。实测：地形编辑左键 `tx/ty` 变且 **0 写**；右键 **恰 1 条 `SetTerrain`(hexes=6)**；**五模式左键各 `nonGet=0`**。
★★ **T13 冲突查证（控制器派单要求，结论：两者都对但不是同一层）**：**地形块边界层**确为一条 `Path2D`（T13 没错，它画出的**正是"地形区之间的黑色间隔"**）；★ **用户所见的"每个 hex 有边框"不是 stroke——是区域高亮 `0.98×cellSize` 的填充缝**（改回 `cellSize` 即消失）；**选区预览层是唯一真正的逐格 `stroke`**（已删）。证据：包 `stroke` 探针 clean `strokes=9 / maxLineToPerStroke=20 / path2dStrokes=0`；m8 恢复边框层 ⇒ `path2dStrokes>0`；改前/改后同状态截图 **18,854 通道差>30 / 最大 234**。
**★ 边界简化（RDP）**：**378 → 43 顶点（−88.6%）**，对焦点区 **20/20** hex 中心 even-odd 判定 `enclosed=true`（仍闭合、仍包住）；`eps=0.5` 经独立脚本标定（0.32 不压、1.4 切出区域外）。裁定：**边框只画区域边界**、**地形块之间不画**、**逐格线全删**。
**变异 9/9 KILLED（0 存活）**：m1 重叠拒绝（方向性，**第三次兑现**）/ m2 右键仍 PlanRoute / m3 非选中也画点 / m4 合并误用交集 / m5 剔除误用并集 / **m6 地形左键仍刷** / **m7 Shift 分支缺失** / **m8 恢复逐格描边** / **m9 关掉简化（43→378）**。★ **m7 首轮 `rc=2`（崩溃非红）**——装置缺陷（变异令按钮未建成致 `waitForSelector` 超时），**已修并如实记账**。
**门禁 924 delta 0**（纯前端）；`orig==restored==classes`、聚合 md5 `abd08474…` 非空且逐字节相同；5817/5818 未动。
**★ 实现者再次纠正控制器 4 处**：① "每 hex 边框"不是 stroke（是 0.98 填充缝）；② `#region-edit-draw` 是**按钮非 checkbox**；③ §七 的 Shift+右键 = **编辑选区不发写**（判据⑩b 的"一条 UpdateRegion"由"合并"满足）；④ 左键"永不误改"的**有意例外** = 区域编辑下**精确命中边界小点仍是拖点**（保留 GSimulator 习惯）。
**未核实**：RDP 包住性只对本次 20 格区域证过（**带洞/多连通/凹形大区域未构造**）；`eps=0.5` 只在小圆盘标定；dpr>1 与触摸未测；**前端护栏仍不进 Maven 门禁**（T2 未做）。

## M8-S ✅ 边界撤销 RDP + 重名提示（`38ce343` → 合并见下；纯前端）

**① 区域边界 = 精确 hex 外缘（推翻 §八 第 3 条）**：**RDP 整段删除**（`perpDistance`/`rdpOpen`/`rdpClosed`，~72 行）；`setRegionOutlines` **直接用 `regionBoundaryRings` 的精确环**。★ **判据 14 证据**：渲染顶点数 **== JS 复刻 == Java 探针**（逐值）；**顶点格点最大残留 `< 6e-5`**；**顶点到最近 hex 心距 `≈0.99996`**（== 格边长 1 ⇒ **是顶点不是中心**）。**保留**逐格线不画 + 边框只画区域边界。
**② 建区重名主动提示**：新增 `findSameNameRegion`/`regionIdExists`/`requestCreateRegion`/`resolveNameConflictCreateNew`/`resolveNameConflictMerge`/`cancelNameConflict` + 三个按钮；★ **不静默建/不静默合并**；★ **不做"禁止重名"**（重名合法，服务端只拦 id 重复）。
**变异 m10~m12 全杀**（m10 重引入 RDP ⇒ 判据 14 四条等式全红；m12 合并误覆盖 ⇒ 并集逐值红）；★ **m11 首轮 `e2e_rc=2`（装置崩溃非红）**——无弹窗致 `page.click` 超时，**稳定后 rc=1**，如实记账。
**门禁 924 delta 0**；真档 md5 写前=写后 `2348b936…`。
★★ **带裁定的遗留（必须归还，M8 关账前）**：**M8-R 的 m1~m8 未按纪律重跑**（同文件被本单改动 ⇒ 旧证据对应**旧字节**）。原因：M8-R 的 `mut-run.sh` 锚点已被本单**结构性删除/移动**，不能原样复用；**m9 因 RDP 整段删除 ⇒ 永久作废**。⇒ **裁定**：`m1~m8` 守护的**行为仍然存在**（重叠允许 / 右键分派 / 小点只画 focus / 合并=并集 / 剔除=差集 / 地形左键不刷 / Shift+右键逐格 / 不恢复逐格描边），**必须按新结构改锚点后重跑**；**归 T12（M8 关账）前的必办项**；`m9` 作废、以 m10 取代。

## T2 ✅ ★ 前端单元测试接入 Maven 门禁（`02a47f4` → 合并见下）——**关掉"前端护栏不进 CI"系统性开口项**

**接线**：`simos-app/pom.xml` 的 `exec-maven-plugin:3.4.1` execution **`frontend-unit-tests`** 绑 **`test` 阶段**，跑 `src/test/js/run-gate.cjs`（→ `node --test --test-reporter=tap`）；新增 `node.executable` 属性（默认 `node`，可 `-D` 覆盖）。零 npm 依赖、无 `package.json`、不联网。
★★ **门禁日志实测**：`[INFO] --- exec:3.4.1:exec (frontend-unit-tests) @ simos-app ---` ⇒ `# tests 61 / # fail 0` ⇒ **`[frontend-gate] OK tests=61 pass=61 fail=0`**（★ 有 `MIN_TESTS` 下界，**拒 skipped/todo**，**不许"0 个测试也算通过"**）。
★ 搬进门禁的纯函数：五模式白名单（逐模式允许/拒绝、未知模式/空 type fail-closed、返回快照）/ 套索几何 / 精确边界环 / 时间轴列布局与 tick 分组 / `buildTree` / 写路径 allowlist。
★★ **故意违规自证**：把**未声明的写路径**塞进 allowlist ⇒ **门禁真红**；★ **装置自证（m1b）**：同一条违规 + 把守卫改成**永真断言** ⇒ `gate_rc=0`（**违规不再红**）⇒ **证明红来自那条守卫而非别处**，且永真化用例**被拒绝**为有效装置。★ **Node 不可用 ⇒ fail-closed**：`-Dnode.executable=/nonexistent/node` ⇒ `Cannot run program` ⇒ `[ERROR]` ⇒ **构建红**（**不静默跳过**）。
**纯函数修正一处**：`m7` 首轮曾**存活**——静态计数把 `.test(`（正则/字符串里的方法调用）误算；已修。
**真档 e2e 复跑 23/23 PASS、0 pageerror；原档 md5 写前=写后 `2348b936…`**；变异 7 轮有效红 + 1 轮"装置失效"反证。
**未核实**：只在**本机 node v22.23.2** 跑过；`--test-reporter=tap` **需 node ≥19**（更老版本会因坏选项**非零退出 ⇒ 仍是 fail-closed**，但具体报错文案未实测）。
★ **实现者第 5 次纠正控制器**：派单 m1 那行"**期望红**"与"**装置自证失败**"**自相矛盾**；拆成 **m1**（违规⇒红）+ **m1b**（永真⇒不红⇒装置失效）**两条合起来**才构成完整自证。

## T5+T6 ✅ `map.SetEdge` + `map.RandomizeRegion`（5 文件 +231/−17，**全在 `src/test`，零 `src/main` 改动**；`1b14d8b`）

**范围**：两族各三段——领域操作（`EdgeOperations` / `RandomizeOperations`+`RegionRandomizer`）、SPI 边界（`SetEdgeHandler` / `RandomizeRegionHandler`）、端到端（`MapSetEdgeEndToEndTest` / `MapRandomizeEndToEndTest`：真 store / 真 checkpoint / 真 replay）。判据 T5-1~4 / T6-1~6 逐条结论、两张口径表、"我未能核实的"四条，见 `t5t6-evidence/notes/t5t6-conclusion.md`。

★★ **本轮抓到并当场修掉的真缺陷：两条 ★ 用例不在分叉点上。** `m2`（`if (REPLACE.equals(operation))` ⇒ `|| MERGE.equals(operation)`，即"merge 当 replace 使"）**只被杀中 2 点**，而两条名字里就写着"merge 不丢既有 tag"的 ★ 用例**全绿**。根因**读实现即定、非猜测**：`replace` 分支是**逐 kind** 摘标注（`withoutTag(tags, tagKey)` 只摘 `tagKey` 那一 kind），而这两条用例的夹具里**既有 tag 都在别的 kind 上**（既有 `river`、命令 `road`）⇒ 变异体**摘不到任何东西** ⇒ 两种语义**在这份输入上结果相同** ⇒ 用例恒真、是装饰。属**形态 3 的夹具版**。修法比它的描述短 ⇒ **当场修、不 park**：既有边改成带**同 kind**（外加别的 kind）、断言**逐值不变**；连带把 e2e 的 `replace` 断言**改强**（多钉一条"别的 kind 一字不动"）。★ **诚实区分**：`SetEdgeHandlerTest.appliesAMergePayload` 同样分不开两语义，但其 javadoc 明写验的是**管道**（载荷⇒Applied⇒变更集生效）、**不声称**保护语义 ⇒ **不是缺陷**。

★★ **修法先立预测、再上实测（不是事后解释）**：改夹具**之前**写死"若两条 ★ 用例真落到分叉点上，t5-m2 的 hits **必须**从 2 升到 4，且失败原文里**必须**同时出现那两条方法名"。**实测 hits=4、两条都在** ⇒ 修法生效；反之则记"修法没生效"、不许记成"已修"。★ 四轮因而**全部在改动后的字节上重跑**（裁定 42「新增/改动的护栏必须自带变异轮」＋通则「同一文件被改动 ⇒ 旧证据对应旧字节」）。

**变异**：四轮 **0 存活**（t5-m1 `2` / t5-m2 `4` / t6-m1 `4` / t6-m2 `2`），四轮 `clean_rc=0`、`mut_compilation_error=0`、`class_removed` 有值（陈旧 `.class` 已清）、`restored_md5 == orig`。★ **`hits` 是下界、不是"红了几个方法"**：t6-m1 实际红了 **5 个**，多出的 `seedOneAndFiveShareAHistogram` **被杀但不在当轮白名单**故不计。★ **`self_md5` 不是日志自身的摘要**——装置回读日志里本轮的 `mutant_md5`（"日志自指"的兑现方式），故它**必然等于** `mutant_md5`；但那句只断言**非空**、**不**断言等于 `MUT_MD5`，等价来自日志每轮 `>` 截断而非断言本身（**已知弱断言**，未改：四轮跑在同一活进程上，改脚本会让四轮跑在两个版本的装置上）。装置 v1→v4 的四个盲区见结论台账 §四。

★ **种子表不是手算、是探针在当轮字节上跑出来的**（形态 5）：`SeedTableProbe` 与夹具同形（半径 10、331 格、选区 326）。★★ **实测 `seed=1` 与 `seed=5` 的直方图完全相同**（`{desert=161, mountains=5, plains=165}`），只有**块数**分得开（25 vs 28）⇒ 只钉直方图的表在 `(1,5)` 这一对上**不判别**"忽略 seed"类变异。故种子表**同时钉直方图与块数**，并另加 `seedOneAndFiveShareAHistogram` **当场自证这条设计选择必需**（它自己也是一个杀点）。

★★ **如实记账：T5 的端到端用的是合成夹具，不是真档。** 判据原文允许"若无则合成"；本机实测**没有**真档（无 `/tmp/m6-import-verify/test_integration`、无 `*_map.json`、无 `simos.db`）⇒ 依兜底条款用合成夹具（三格链 `H00—H10—H01`、`E_LEFT` 带 `river`+`road`、`E_UP` 空）。**因此本任务不对"真档上的 `edges` 往返"作任何断言**；M6 的开口项（`edges` 非空无真实样本）**依然成立**。

**门禁**：整树 `./mvnw clean verify` 绿（rc=0、**973** 条 = 170/**362**/45/131/**169**/96、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0 行、`COMPILATION ERROR` 0、Checkstyle 违例 0×7、`[frontend-gate] OK tests=61 pass=61 fail=0`、`Total time: 07:30 min`；日志 `t5t6-evidence/logs/full-verify.log`，1539 行、已封口）。★★ **这 5 个测试文件的格式合规是这一轮才被证明的**——变异轮跑 `test` 阶段、**不跑 Spotless**（`e2d00df` 当初门禁红的唯一原因就是一个空行），故在此之前"变异全杀 + 干净轮绿"**证不到格式**；本轮 6 条 `Spotless.Java is keeping N files clean - 0 needs changes to be clean` 逐模块在案（map **88** / core **52** 含本单 5 文件）。
★ **本树没有实测的 before 值**（基线 `e2d00df` **从未跑过门禁**）⇒ **不报 delta**，不拿"上一棵树的绿"当本树的对照。能报的是**对账**：`973 = 869 + 90 + 14`，其中 **map 的 90 与 core 的 14 逐类相加恰好闭合、六个模块无剩余**（map：T3 18〔`TerrainOperationsTest` 11 + `SetTerrainHandlerTest` 7〕+ T4 31〔`RegionOperationsTest` 16 + `RegionHandlersTest` 15〕+ T5 22〔`SetEdgeHandlerTest` 9 + `EdgeOperationsTest` 13〕+ T6 19〔`RandomizeRegionHandlerTest` 7 + `RandomizeOperationsTest` 12〕= **90**；core：T3 3 + T4 3 + T5 4 + T6 4 = **14**；其余四模块 170/45/131/96 与 M9 基线**逐值相同**）⇒ 这棵树确实**只多了它该多的东西**。★ 本单 5 个类在本轮日志里的条数：`EdgeOperationsTest` **13** / `RandomizeOperationsTest` **12** / `RandomizeRegionHandlerTest` **7** / `MapRandomizeEndToEndTest` **4** / `MapSetEdgeEndToEndTest` **4**，全 0 失败。

**未核实**：真档上的 `map.SetEdge`（同上）/ `RegionRandomizer` 的大图重切性能 / `mode`·`kind` 大小写混合只在单元层验过（e2e 与浏览器未跑）/ 块表 `TreeMap` 全序在 >1000 块时未测（本夹具最大 28 块）。

## T11 ✅ 连通性（河流/道路）+ 圈选随机化 UI（`5cfbd9e`；9 文件 +602/−19、证据 115 文件）

**交互**：地图编辑模式内新增**工具选择器**（地形刷〔默认〕/ 河流 / 道路 / 圈选随机化），参数按需出现（`#edge-controls`/`#randomize-controls` 默认 hidden，可断言）。★★ **Q2 分两层读、两层各司其职**：协议层管"坏载荷进不来"（T5 已交付 `MapPayloads.requireText(payload,"mode")`），**UI 层不预选、不静默兜默认**——`#edge-mode` 首项是 `（未选）`，未选则亮警告 + 写**可见**提示，且**一条写命令都不发**（只给提示，不是只给 console）。理由三条：这是唯一能让 m1 红的读法；两层各管各的（协议层挡不住"浏览器替用户做了决定"）；代价只是一次点击。

**五个新纯函数**（全在 `webui/map.js`，进门禁）：`edgeKeyOf`（端点按 `(q,r)` **规范序**——注释专门点了"非字符串序"，即 `1_10` vs `1_2` 那个坑）/ `edgeChainEdges`（相邻才连边、**不相邻断链重起**、去重保首现序 ⇒ 一条拖动多段仍只发**一条** `SetEdge`）/ `edgeModeState`（只有显式 `merge`/`replace` 才 `ok`）/ `parseSeedInput`（空·空白·非整数·**超 JS 安全整数**一律 `not ok`，**不兜 0**）/ `randomizeSelectionState`（空选区 `not ok`）。

**★ 新增只读读路径**：`/api/map/hex` 加 `edges` 字段（`ApiViews.incidentEdges`，边按 `EdgeRef` 自然序 + `pathways` 字典序**双排序**——`/api/map/hex` 有"同 revision 两次响应**逐字节相同**"的既有断言，任一处跟着 `Map` 的迭代序走就会随 JVM 散列盐抖动）。**理由**：连通性此前在 app 层**没有任何读路径** ⇒ "`merge` 后既有 tag 仍在 / `replace` 后只剩新的"这条判据**在浏览器里观测不到**（断不了言 = 装饰）。唯一调用点 `GuiServer:393`；`GameMap.edges()` 是 record 隐式访问器、构造期已 `unmodifiableMap(copyOf(...))`。

**e2e 31 断言 ALL PASS**（`--demo` 演示世界，**本机无真档**）；`e2e_rc=0`、`z0-no-pageerror: PASS []`；非 GET 清单**恰 8 条**（3×`SetEdge` + 4×`RandomizeRegion` + 1×`SetTerrain`）、**零写期待 5 处**——★ 新增的"空选区点随机化"步**一条都没发**。★★ **端到端决定性证据**：区域信息面板渲染出 `连通性1_1|1_2[river]；1_2|1_3[road]` ⇒ Java 的 `edges` 字段**确实走到了浏览器**。

**判别力**：merge 判据落在**同一条边同一个 kind**（既有 `river` 在新 `road` 命令上 ⇒ 实测 `["river","road"]`）；replace 判据打 **E1**（不在 payload 里）⇒ E2 的 river 被整份摘掉、road 一字不动。★ **随机化按字节比、不按直方图**（T6 教训）。★ 实现者**自曝 `r2` 是弱断言**（seed 7 的产物与该世界随机化前逐字节相同 `3f5a712a…`）⇒ 强断言是 `r4`（`r3` 用 seed 99 确实变、`r4` 回到 7 又逐字节复现 ⇒ 随机化是真的且 seed 相关）。★ `x1` 非相邻跳 **0 写**——后端会接受非相邻边（probe 已实证）⇒ **只有前端守卫挡着**。

**变异 6 轮 0 存活**（逐轮 `consumed_md5 == mutant_md5`、`restored_md5 == orig_md5`）：m1 UI 静默兜 `replace`（红在 `e1`）/ m2 seed 被忽略（红在 `r1`）/ m3 `edgeChainEdges` 相邻性被削（红在 `x1`）/ m4 `parseSeedInput` 空串兜 0（红在 `r0c`）/ ★ **m5 Java 读路径**（红在 `mapHexOnASharedHexListsBothIncidentEdgesInEdgeRefOrder`，其 `orig_md5` **逐字节等于当前 `ApiViews.java` 的 `b9b16adf…`**）/ ★ **m6 空选区守卫**（红在**本轮新补的** `r0a`）。★ 裁定 42 + 判据 7 的兑现：`m5`/`m6` 是在实现者报告 §八.7 自陈缺口后**当场要求补**的（"Java 侧没有变异轮" + "`randomizeSelectionState` 无变异体"）——**修复比它的描述还短，不 park**。

**门禁 977** = 170/**362**/45/131/**169**/**100**，7/7 模块 SUCCESS，`BugInstance size is 0` ×6，`[ERROR]` **0** 行，`COMPILATION ERROR` 0，Checkstyle 违例 0×7，Spotless 六条 `keeping N files clean - 0 needs changes`（67/88/14/47/52/43），`[frontend-gate] OK tests=71 pass=71 fail=0`，`Total time: 06:45 min`。★ **delta 干净**：T5/T6 那棵树是 **973**（…/**96**），本树 **977**，**只有 `simos-app` 96→100 恰 +4**（= `MapHexEdgesApiTest`），其余五个模块**逐值未动**。★ 下界 71 已**逐值实测**：8 个 JS 测试文件按门禁自己的正则数出来正好 **71**（61 + 新文件 10）。

★★ **如实记账：整树 verify 跑了 5 次，只有第 5 次可引用。** ① attempt1 **被系统因内存不足杀掉**（243 行、停在 3/7、**无 `BUILD` 行**）；② 第 2 次**真红**——`MapHexEdgesApiTest.java:186 cannot find symbol: class UnitSnapshot`（缺 import）⇒ ★ **那 4 条 Java 测试在该轮之前从未编译过**（报告 §八.7 的"Java 侧没有变异轮"与它是同一枚硬币的两面）；③ attempt3 **也被杀掉**（277 行、停在 `spotless @ simos-map`、无 BUILD 行）；④ attempt4 **真红**——**Spotless**：`GuiServer.java:390` 超宽需折行（★ 同一份日志里 977 条测试**全绿**，含 `MapHexEdgesApiTest` 4/4）；⑤ attempt5（折行后）**绿**。★ **"被杀"不是"红"、也不是"绿"**——三份半截日志**不产出任何门禁数字**，一律改名存档（`attempt1-killed-by-oom` / `attempt3-killed-by-system`）、**一个数字都不许引用**。★ 连带实测：**本机的瓶颈不只是核数（`nproc=2`），内存也是共享资源**，且**整树 verify 必须独占**（第 3 次正是死在"verify 与 e2e 并发"上）。

★★ **纪律实例（实现者再次纠正控制器，这次错在我）**：我在派单里写"m1~m4 的杀点不受影响，因为 `map.js` 未变"——**只有一半证据**。被测字节确实逐字节相同（`f6eeed79…`），但**装置 `e2e.cjs` 在 05:08:36 被就地改过且 v1 没备份** ⇒ "改了什么"无法 diff；m4 在新装置上重跑过（被杀、原文逐字相同），**m1/m2/m3 从没重跑**，其日志（04:55–04:57）**早于那次编辑** ⇒ 对这三轮那是**论证、不是实测**。实现者**拒绝把论证当实测**，先备份旧日志、再在当前装置下**全部重跑**（v2 日志齐、`orig_md5` 逐轮等于当前 `map.js`）。★ 通则：**"同一文件被改动 ⇒ 旧证据对应旧字节"里的"文件"包括装置本身**。
★ **两处装置缺陷（实现者自查出）**：① 取数脚本 `extract-verify.sh` 的拒收正则只认 `[ERROR] BUILD FAILURE`，而 Maven 写的是 **`[INFO] BUILD FAILURE`** ⇒ 会把**跑完的红轮**判成"没跑完"——**把"有"伪装成"没有"**，与 ugrep / `git grep --untracked` 同族（CLAUDE.md 已有"命中 0 先怀疑自己的正则"，这是第二个载体）；② 其第 ⑩ 项（模块数）用 Maven 2 的 `[INFO] Packaging ` 写法 ⇒ **本机恒为 0**，一个"看着像测到过"的读数。两者都已修 + **双侧自证**（被杀轮仍 `rc=1`；跑完的红轮能提取出 `701:[INFO] BUILD FAILURE`），旧版 `cp -a` 留档、①~⑨ 项 `diff` 逐字节不变。
★ 实现者另**自查出自己报告里 5 处"伪造留痕"**（把"我数的"写成"日志里的"逐字引文，如 `PASS=31`、归并 md5 `cae9cfdc…` 实为 `cd9b97af…`）——结论未错、错在让读者以为那是从文件里抄的；已逐条改正。★ 它第一版对拍器还犯过一次 **"空==空"恒真**（把 `notes/` 数了两遍 ⇒ 报告独有值全判 OK）——与 M7b 那条 `.class` 聚合 md5 是同族，一并记在案。

**未核实**（承报告 §八，逐条保留）：**真档**（本机无档 ⇒ 全部 e2e 跑在 `--demo` 的 **3 格**演示世界，随机化样本面窄）/ 只验 `river`·`road` 两个 kind（本机词表只此两类）/ 长连拖动（>2 格链）/ seed 全域边界 / **只 Chromium，未跑第二视口**（1024×700）/ 触摸与触控笔 / 异常路径（后端 422/409 下的 UI 反应）/ ★ **`GuiServer:393` 那行接线本身没有变异体**（m5 钉的是 `ApiViews.incidentEdges` 的排序与列举）/ ★ **`e2e.cjs` v1 不可 diff**（见上）。

**带裁定的遗留（归还 T12）**：① 真档上的连通性读写仍未验——**与 T5/T6 同一条开口项**（M6 的"`edges` 非空无真实样本"**依然成立**）；② `randomizeSelectionState` 会**静默丢弃**缺 `q`/`r` 的项（选择来自内部状态、非用户文本 ⇒ 未按错误处理）；③ 触摸 / HiDPI / 第二视口随 M8 关账统一记。

## T9 ✅ 区域查看模式 UI（**已关账 `9eef9ab`**；4 文件 + 1 新增：`webui/map.js`、`webui/panels.js`、`src/test/js/{run-gate,gate-contract.test}.cjs`、新增 `src/test/js/region-view.test.cjs`；**零 Java 改动**）

**交互（判据逐值）**：点重叠格 `(1,2)` ⇒ 高亮**全部**从属区域（`highlighted=["t9_a","t9_b"]` == `/api/map/hex` 的 `regions`，长度 **2 > 1**；`/api/map/hex` 实测 `regions:["t9_a","t9_b"]` 字典序）；选区域 ⇒ **其它淡色逐值可断言**（焦点 `0.42`／区域编辑 `0.52`、淡色 `0.13` + **独立复算**的混合色 `#8a8adc`，harness 内自算 `t=0.68→203`，不是"看起来淡了"）。
★★ **两模式共用一份实现**：抽出 `buildRegionHighlightPlan` + `reloadRegionHighlight`，差别只剩两组参数常量（`REGION_VIEW_HIGHLIGHT` / `REGION_EDIT_HIGHLIGHT`）；门禁里 `fadeRegionColor(base)` **全仓只准出现 1 次**（反向断言，m5 正是红在它上面）。★ **焦点先入**（T10 抓过的真缺陷）在**浏览器路径**上有回归位：焦 `t9_b` ⇒ `at12`/`at13` 都必须是它的焦点色（m3 红成 `at12={"#dc8a8a",0.13}` —— 焦点区丢了自己的格）。

**★★ 裁定 72.1 的杀点**：左栏每个区域报**它自己的** `hexCount`（`t9_a:2, t9_b:2`），合计是**真并集**（`union=3`，与 `sum=4` **不等**），并把 "求和" 钉成红点——纯函数 `regionMembershipSummary` 算 `hexCountSum` 只为断言"和 ≠ 并集"，**求和值绝不写进 DOM**（`appendRegionMembership` 源码级反向断言）；取不到某区域 hex 列表时 `unionCount=null`（**不给数字**），**不拿求和顶替**。

**e2e 干净轮 57/57 ALL PASS**（`e2e_rc=0`、`z0-no-pageerror: PASS []`；T8/T11 的既有断言全绿，判据⑥ 相关面未退化）。★ 世界是**真写**出来的（3 条 `map.CreateRegion`，`head 9→12`）——**本机无真档**，"1 hex ≥2 区域"是在 `--demo` 3 格世界上用真写路径**造**的（见报告 §5 第 1 条）。

**变异 7 轮 0 存活**（装置 `mut-run.sh`，全 7 轮跑在同一份装置 `e0267dbd…`）：m1 只高亮第一个（红在 e2e `h2a`）/ m2 合计改求和（红在门禁 `not ok 51` **且** e2e `h3b`；实测 `union:"4"`）/ m3 去掉焦点先入（红在门禁 `not ok 42` + e2e `h4h`）/ m4 淡色也用焦点透明度（红在 e2e `h4c` `[0.42]`）/ m5 淡色用原色（红在 e2e `h4d` `#0000ff ≠ #8a8adc`）/ m6 未知焦点退化成"全都淡色"（门禁 `not ok 44`）/ m7 取不到 hex 列表拿求和顶替（门禁 `not ok 50`）。★ **本次没有 Java 变异轮**（零 Java 改动 ⇒ 无目标类）。★ 两处**如实记账**：① m2 首轮判 SURVIVED 是**装置假阴性**（门禁报 JS 用例名、e2e 报 STEP 名，装置拿后者去核前者）⇒ 改成两套命名分开钉；② m3 首轮**预测错**（预判 e2e 红在 `h2c`，实测全绿）——根因是**夹具不可判别**（h2 的焦点是两个从属区域、待淡化的排末位；h4 的焦点又是色板首位）⇒ **补 H4b**（焦居中的 `t9_b`）后当场在浏览器层红。★ 通则入档：**"高亮了谁"不能靠格数判**（m1 下 `highlightHexCount` 仍是 3——淡色条目照样占格）。

**门禁**：`./mvnw clean verify` **rc=0**（`verify-rc.txt` 当场落盘）、**977** = 170/362/45/131/169/100（**与基线逐值相同**）、7/7 SUCCESS、`BugInstance size is 0` ×6、`[ERROR]` **0** 行、`[frontend-gate] OK tests=82 pass=82 fail=0`（下界 71→**82** 双升，`Total time` 08:07 min）。★ **与派单预期的出入**：JS 断言**不并进** `simos-app` 的 surefire 合计（前端门禁由 `exec-maven-plugin` 独立跑，只进 `[frontend-gate]` 行）⇒ 977 不变是正确的；"下界是真护栏"的证据不是"+11 个数字"，是**它红过**（m2~m7 的门禁轮）。

**★★ 控制器复核（逐条**实读原始产物**，不采信转述；2026-09-20 提交前）**：
- **字节焊接**：当前树 `agg=4294de6d` / `map.js=014ffd3a` / `panels.js=940bff47` **等于** `mutants/orig/*` **等于**被引干净 e2e 轮（`clean-e2e-final.log`）自记的 `agg_md5` / `map_js_md5`；被引轮自记的装置 `e2e_cjs=75ce5aa0`、`run_e2e_sh=96288100` **等于**当前装置文件 md5 ⇒ **"跑的就是这些字节、这份装置"是读出来的，不是推出来的**。
- **被引 verify 是哪一次**：`verify-rc.txt`（06:55:28）与 `full-verify-postmutants.log`（06:55:27）同源 = **变异轮全部 `cp` 还原之后**那一次；另一份 `full-verify.log`（06:40:50，变异轮之前）逐项相同、**只有 `Total time` 06:58 vs 08:07 不同**（同工作量、不同负载）。台账引的 08:07 与 rc 同源，**引对了**。
- **门禁红点编号逐条核对**（原文 `^not ok <n> - `）：m2=**51**、m3=**42**、m4=**42/43/45**、m5=**42/45/47**、m6=**44**、m7=**50** —— 与台账逐值一致。
- ★★ **m5 的红含 `not ok 47 - page-层-uses-the-shared-plan-for-both-modes`** ⇒ 测试⑥那条**源码级反向断言**（`fadeRegionColor(base)` 计数 `=== 1`）**被真杀过**。这正是"它是不是恒真"的正面回答：**不靠论证，靠一条红**。（该用例没有单独的 `assert.ok(source.length > 0)`；判它非恒真的依据是"计数 `=== 1` 在空源码下会得 0"**加上**这条实测红 —— 两者都在，故不补。）
- **形态 5 防守实测**：`readWebui()` 走 `__dirname`（树内相对），实测 `map.js` = **157955 字节**、`fadeRegionColor(base)` 恰好 **1** 次、旧内联实现 `indexOf("if (region && region.id === focus)") = -1` ⇒ 在 worktree 下**扫到了真文件**，M4 那类"扫到 0 个 ⇒ 断言恒真"不适用。
- **核心判据的原始证据**：`h0-hex-1-2-has-exactly-two-owners: PASS {"regions":["t9_a","t9_b"]}`（**服务端自己**报 2 个从属）+ `h2a-highlight-count-equals-regions-length: PASS {"highlighted":["t9_a","t9_b"],"regions":["t9_a","t9_b"]}`（UI 高亮集 == 服务端 `regions`，长度 **2 > 1**）+ `h1-three-regions-created-through-the-real-write-path: PASS {"posts":3,"headBefore":9,"headAfter":12}`（夹具是 3 条真 `map.CreateRegion` 造出来的）。

**★★ 复核新发现一（装置自记里的作废轮）**：`m6-e2e.log` / `m7-e2e.log` 各有**两块** `--- device self-record ---`：前一块装置 `c6cd1e68…`、后一块 `e0267dbd…`（`>>` 追加 ⇒ **后一块才是本名轮**）。⇒ "全 7 轮跑在同一份装置 `e0267dbd…`"**成立**（m1~m5 各一块且即 `e0267dbd`；m6/m7 取后一块），但**引用必须取第二块**；且这两个文件名叫 `-e2e.log`，内容里 `e2e_rc=not-run` —— m6/m7 是 **gate-only 轮，根本没跑 e2e**，别把它们当"跑过浏览器的轮"。★ 与 T11 的"装置 v1 没备份"同族：**装置自己的留痕也会留下作废轮**。

**★★ 复核新发现二（通则新同族：mtime 不是"字节变了"的判据）**：`map.js` / `panels.js` 的 mtime 是 **06:46:59 / 06:47:01**，**晚于**被引的干净 e2e 轮（06:33:32）—— 看上去像"引了旧字节"。实为变异轮还原时 `cp` **重写**了文件、**字节没变**（两边 md5 都是 `014ffd3a` / `940bff47`）。⇒ 通则「同一文件被改动 ⇒ 旧证据对应旧字节」的**对偶**：**文件被重写 ≠ 字节被改**；**mtime 更新不能判旧证据失效，md5 才能**（反向也成立：md5 相同就不能因为 mtime 新而重跑）。★ 这正是装置坚持逐字节 `cp` + 双处 md5 自记的价值所在 —— 本次它把一个假警报挡掉了。

**未核实**（详见 `t9-evidence/notes/t9-report.md` §5）：**真档**（本机无档；多从属未在 19441 格档上验）/ 一个 hex **≥3 从属**只在合成夹具证过（端点与浏览器最多 2 个）/ 左栏是 **N+1** 次 `/api/map/region/<id>`（从属多时未测）/ **m6·m7 只有纯函数层的红**（e2e 造不出那两种状态，不拿门禁层的红冒充浏览器层）/ 淡色只有**入参级**证明、**无像素证明** / `regionHighlightAt` 的 alpha 回落分支无用例 / 左栏不显示区域 `name` / m4·m5 的 e2e 断言是**共用夹具**（一条断言钉多件事）。

**带裁定的遗留（归还 T12）**：① "合计"这一行**要不要留**（裁定 72.1 只禁求和当并集，没说要显示并集；产品上不要就删，比修文案省）；② **双下界同值双写**（`run-gate.cjs:17` 与 `gate-contract.test.cjs:25` 都是 82，合成一处要动 exec 参数传递，未做）；③ **装置噪声留档**：`m1-e2e-prevharness.log`、`m2-*-DEVICEBUG.log`、`m3-*-predh2c.log` 是修订/事故留档，**引用以最终扫轮的本名日志为准**；★ 另：`m6-e2e.log` / `m7-e2e.log` 里的**第二块**自记块才是本名轮（前一块是作废轮 `c6cd1e68…`），且这两个文件**不含任何 e2e 运行**（gate-only 轮）。

## T12 ✅ 关账（**2026-09-20**；报告 `task-12-final-report.md`，证据 `t12-evidence/`）——**M8 至此全部关账**

**七条判据逐条实测值见报告 §一**（每条给"值 → `文件:行` → 缺口"）。摘要：① 真档重放逐字节相同（`replayByteIdentical=true`）+ 当前字节浏览器层真写（`g2-brush-not-stolen` `head 8→9`）；② 重叠允许（真档 3 从属 `ownersAfter(-18_0)=[t4_overlap,test_annex_target,test_nation]`）+ 端点同时列出 + 真档重放一致；③ 多从属地基在**当前字节 + 门禁内 + 端点层**有实测（`GuiApiTest.java:286-294` 整份响应体两次逐字节相同）；④ `replace`/`merge` 两条都有实测、merge 不丢既有 tag；⑤ 五模式写权限**逐模式可断言**（门禁两文件 + 浏览器层非 GET 清单）；⑥ ★ **本条最弱**：六项里 4 项在当前字节上**无浏览器断言**、1 项部分覆盖、1 项被有意替换 ⇒ **既不是"通过"，也不是 M8 引入的退化，而是从未被真正测过**（归还见报告 §五）；⑦ 见下。

**门禁（干线 `fbd09ee` 当场跑）**：`./mvnw clean verify` **rc=0**、**977** = 170/362/45/131/169/100（**与基线逐值相同**）、7/7 SUCCESS、`BugInstance size is 0` **×6**（父 pom 不产该行 ⇒ 6 是对的）、`[ERROR]` **0** 行、`[frontend-gate] OK tests=82 pass=82 fail=0`、`Total time 07:41 min`。

**★★ 门禁的取得过程必须记账（三次尝试，两次被杀，都不是红）**：attempt 1（**后台**）已过 util/map/social、停在 simos-unit 编译时被**内存守卫**终止；attempt 2（**后台**）**启动同一秒**被杀、日志 **0 字节**。两轮日志内 `OutOfMemoryError|Killed|BUILD FAILURE|[ERROR]` 命中 **0** ⇒ **不是构建失败**，且**无 rc 落盘** ⇒ 不得据被杀轮报"通过/失败"。**attempt 3 改前台，一次通过**。★ **通则入档（环境级）**：本机（1.8 GiB、`vm.drop_caches` **permission denied**、PSI 有真实换页压力）**后台**跑整树 verify 会被杀、**前台**可跑完 ⇒ **"被杀"与"通道"绑定**；判定门禁走**前台**，被杀轮**留档不删**（`logs/clean-verify.attempt{1,2}-killed.*` + 守卫通知逐字原文）。★ attempt-1 的归因**含我的责任**：当时我正并发跑全仓 `find`/md5/git ⇒ **"门禁独占"不只是"不跑第二个 Maven"**。

**收口 e2e（当前字节，前台）**：`t12-evidence/e2e/run-e2e.sh 5874 …` ⇒ **`e2e_rc=0`、57/57 PASS**、`z0-no-pageerror: PASS []`；抬头自记 `agg_md5=4294de6d…`、`map_js_md5=014ffd3a…`（= 被审字节）。装置 `e2e_cjs=75ce5aa0…`（**与 T9 逐字节相同**）、`run_e2e_sh=9f203003…`（与 T9 那份**只差第 8、9 行**：`ROOT` 指干线、`EV` 指 t12-evidence）。

**★★ 变异债：M8-R 的 m1~m8 按**新锚点**重跑 = **0/8 被杀****（A 现场 `t12-evidence/m8r-rerun/`，126 文件 / 5.1 MB，装置 `f81ed3a3…` 10 轮同一份；`predictions.txt` = 先验预测 + 测量后追加的修正记录，原始预测文字一字未改）。**归因 = (c) 判据住在本机跑不了的装置里**，且**逐条排除 (a) 护栏退化 / (b) 锚点不等价**：8 条护栏在当前字节上**都还在**（报告逐条给出 `map.js` 行号）、m6 有**实测差分**（clean 轮五模式全 `moved:true` vs m6 仅 `map-edit:moved:false`）。★ **两种写法必须一起出现**：「不可观察（判据弱于行为 / 判据住在跑不了的装置里）」**∧**「旧锚点 9/9 KILLED 在本机不复现」—— 只写前者会被读成**护栏坏了**，只写后者会被读成**债已还清**。★ 对拍（排除"跑的还是原件"）：8 轮**每轮** `agg_expect == consumed_agg` 且 8 个聚合值**互不相同**、每轮 `node --check` rc=0、`restored_md5 == orig_md5`（源与 `target/classes` **双侧**）。★ **不许写"全杀"**：M8-R 0/8、T2 有 1 轮曾存活（后修）、T5/T6 的 `mut_rc=0` 不是绿（装置带 `-Dmaven.test.failure.ignore=true`）、T1 的 3 轮跑在"新增用例之前"的测试集上。

**顺带查出的真缺陷（M8-L11~L15，详见报告 §五 5.2）**：**L11** 台账 `:92` 把 m4 写作"合并误用交集"（旧本体实为"只取临时选区"）；**L12** `a5` 的 `moved` **算了、打了、没进断言** ⇒ m6 不可见的直接原因（**T12 本轮补上同一装置上的差分对**）；**L13** 不可观察 ≠ 无影响（7 轮只有推导、无差分）；**L14** m8 只覆盖"块边界描边"半边（"逐格"半边当前树无绘制点）；**L15** 装置本机不可运行 = 归因 (c) 的根因。

**裁定点验（报告 §三，10 条）**：M8-U1 / S4 / S5 / Q3 / Q4 / Q5 / Q6 / Q7 **主体可点**，Q1 / Q2 **各缺一层**。★ **替代口径**：T12 不用 M7 期的 R1~Rn 模板（那是模板泄漏），改用 spec §〇.2 裁定表逐条点验；★ 并披露 **spec §〇.2 表本身不全**——至少两条改产品行为的裁定不在表内（统一按键模型 / 区域边界=精确环）。

**★ 三处口径更正 + 一处撤回（报告 §〇，均留痕、不静默）**：① 裁定表 **10 行**（我曾写"11 条"）；② `.serena/project.yml` **不是裁定载体**；③ HANDOFF 对 `main` 记错；④ `BugInstance` **×6**（计划写 ×7）；⑤ ★ **撤回**我自己"m1~m4 缺 `agg_expect`"的判断（据中间 JSONL 误判，查**定稿日志**后 9 轮**每轮都有**且逐轮相等 ⇒ M8-L10 作废）—— 通则：**判"缺"要看定稿产物，不看中间输出**；⑥ 新增：门禁前两次被杀的记账（见上）。★ **台账三处旧口径已更正**：`t1-evidence/t1-report.md:86`、`t9-evidence/notes/t9-report.md:133`、`CLAUDE.md` 的 M8 行都曾写"≥3 从属最多 2"，实为 **T4/T10 已在真档副本上验到 3**。

**未核实（报告 §六，17 项）**：真档重放面只有 Java 探针、**浏览器层无重放装置** / 浏览器层多从属只在 **2** 上验过（≥3 的实测在 T10 轮的真档副本）/ 三元素字典序在端点层无断言 / 真档上 `edges` 往返（M6 开口项，**依然成立**）/ e2e 只覆盖"操作没发出写"、**不含"发出的写被拒"**（服务端 mode-denied 零断言）等。

**§五 遗留（归还下一里程碑）**：**5.1** spec §七 的 **7 条目 / 11 子项**；**5.2** **M8-L1~L15**；**5.3** HANDOFF 债 1~4。
