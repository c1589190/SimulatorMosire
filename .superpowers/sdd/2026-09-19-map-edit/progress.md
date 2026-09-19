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
