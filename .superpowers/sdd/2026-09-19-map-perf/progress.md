# M9 台账 —— 大图性能（19441 格真图）

> 裁定：P1 权威块 / P2 先档0 / P3 最好省 height / P4 gzip 降级为可选 / P5 确定性 BlockId / P6 全部建块

## T1 ✅ 基线实测（test-only，`3e6c10e` → 合并 `362ee89`）

**实测（真服务 + 真 Chromium，两次跑，可复现）**：首屏可交互 **≈10.8s**（10806/10757，−0.5%）；`render()` 适配比例单帧 **≈5.0s**（p50 4998/5004），**scale=3 时 0.2ms** ⇒ 成本随**可见格数**走；**pan 3s 仅 1~2 帧**（帧间隔 ≈5s）；longtask 两段各 ≈5s 占满主线程 0.53s→10.8s；**0 pageerror**；全响应**无 gzip**。

**★ 纠正控制器派单**：启动期 `overview` 是 **3 次**（1 次无 target + 2 次 `revision=1`；`timeline` 初始化再次触发 target 键变化 `main#head`→`main#1`），**units 4 次**，state 3 次 ⇒ **≈3MB**。两次跑 3/3 可复现。

**★ 纪律亮点（记）**：实现者**拒绝**把 `firstFramePaintedTs ≈ 460ms` 报成"可见首帧"（它只是首个 `fill()` 后的首个 rAF）——称之为"把推断当实测（形态 5）"，改以 `firstInteractiveMs` 为口径。**自陈未隔离** 5s 的具体机制（`visibleHexes` O(19441) 扫描 vs 两遍 `addHexPath`）。

**门禁**：合并后 **843 = 170/256/45/131/154/88**，rc=0、ERROR 0。

## 下一步
档 0 止血：T2 去重请求 / T4 render 隔离+修复 / T5 updateLegend 记忆化 + P3 核实。

## T2/T4/T5 ✅ 档 0 止血（前端，`0f56c68` → 合并见下）

**实测对照（同装置/同真档/同视口 1280×800）**：首屏可交互 **11.3s → 0.38~0.51s（≈25×）**；`render()` 适配比例单帧 **5010ms → 1.9ms（≈2600×）**；pan 3s 帧数 **2 → 165~167**；启动 overview/units/state **3/4/3 → 1/1/1**，启动字节 **4.34MB → 2.26MB**。

★★ **隔离结论（先隔离后修，控制器假设被实验证实）**：**巨路径（同色 19441 子路径塞进一条 path 后一次 fill/stroke）是主因**——地形 **1405→23.5ms**、边框 **3594→27.1ms**；chunk sweep 32:23.8 / 64:30.4 / 128:40.8 / 256:65 / 512:110 ⇒ **生产取地形 32、边框 64**。⇒ **"同色批量"其实是病态化**（本项目自己埋的雷）。

**其余手段**：边框 LOD（屏幕尺寸小于阈值跳过）；地形**离屏位图 + pan blit**（数据变/resize/zoom 停 180ms 防抖才重建）；`updateLegend` 记忆化。
**★ `height` 结论：不用（实测）**——grep 全仓无读取点 + 运行时探针剥 58323 字段（3 次 overview）后 0 pageerror、点选正常、legend 逐字相同 ⇒ **overview.height 是死重量 ≈342KB = 响应 32.7%**。★ 「停发」唯一落点在 Java（`ApiViews.java:162`），本单 MUST NOT 不改 Java ⇒ **欠账归 T3**（`/api/map/hex` 的 `:248` **必须保留**）。
**写保护**：`simos.db` md5 跑前=跑后 `2348b936…`（未写）。
**既有失败**：m7c/m7e 有红点，但**用旧字节（f2a348e）跑同一脚本红点完全相同** ⇒ **非本单引入**（如实记，待裁）。
**★ 纠正控制器两处笔误**：① 「门禁 843」是**算术错**——六个模块和 = **844**（170+256+45+131+154+88），本单 delta 0；② overview 启动次数，旧码本会话可到 **4 次**（竞态），修后恒 1。

**未核实**：Skia 内部机理；未真跑"停发 height 的服务端"；HiDPI（dpr>1）未测；位图内存未量。

## T3 ✅ 服务端缓存 + 停发 height（`6f7d194` → 合并见下）

**实测**：overview **1,044,970 → 703,053 B（−341,917，−32.72%）**；`"height"` 键 **19,441 → 0**；★ **`/api/map/hex` 的 height 保留**（keys: facets,height,q,r,regions,terrain,terrainType；=0.375）。**缓存**：`checkpointReadCount` beforeFirst=0 / afterFirst=1 / afterSecond=**1** ⇒ **第二次增量 0**（可观测）。启动字节 2,261,555 → **1,577,721** = 恰 −2×341,917（与字节差逐字节吻合）。
**门禁 850**（170/256/45/131/154/**94**），delta **+6** 逐模块解释（QueryServiceTest +4 / GuiApiTest +2，其余五模块一例未动）。
**变异**：m1（缓存键丢 revision）✅杀；m2（缓存键漏 branch）✅杀；**m3 ❌存活——结构性不可表达**：`SimulationState` 构造期 `Map.copyOf` + `GameMap` 全 `unmodifiableMap` + 切片 record ⇒ 缓存值**深度不可变**，下游就地修改在**不削弱 `simos-state`**（会动铁律 5）前提下无法表达；已补**等价强度**护栏（实测 `UnsupportedOperationException` + 两次读同实例且内容未变）。m4（误删单格 height）✅杀。★ **不伪造红点。**
**未核实**：缓存内存上界未实测（容量 8 是设计判断）；并发未压测（仅 `synchronizedMap`）；LRU 淘汰未构造会话测。

## T6 ✅ P1 权威地形块（`06373bd` → 合并见下；48 文件 +1954/−414，**编译耦合必须一次落地**）

**落地**：`TerrainBlock(String terrain, Set<HexCoord> hexes, RegionBoundary boundary)`（复用 `RegionBoundary`；`hexes` 自然序不可变集合 ⇒ `toString()` 可复现；构造期重算边界比对，同 `Region` 形制）；`TerrainBlocks.split`（六邻连通分量 BFS，**P6 全部建块无阈值**，`TreeMap` 全序 ⇒ 确定性）；`GameMap` 第 2 组件 `terrainBlocks`（9 组件）；`HexCell` **只剩 `height`**；`terrainAt(HexCoord)` 稳定访问器 + `terrainIndex()`（批量）；`MapCodec` 注册 `BlockId` 键 + **旧形状回退**。

**★ 分割不变式**（`GameMap:88-89` 构造期）：失败消息**精确到 hex** —— `hex 0_0 不属于任何地形块（分割不变式要求并集覆盖全部 hex）`；`hex 5_5 同时属于地形块 plains@0_0 与 desert@5_5（分割不变式要求两两不交）`。
**★ 确定性**：两次重建 ⇒ `BlockId` 集合逐项相同、`hexes` 迭代序相同、`boundary().toString()` **逐字节相同**；`BlockId.of` 乱序入参仍取最小 hex（`plains@-5_-59`）、`parse` 往返。
**★★ 旧档兼容 = 选项 a（就地迁移）+ 实测**：真档 `checkpoints/main/1.json`（**复制**到 /tmp，原档 md5 `9e13d856…` 前后一致）；`partition=OK hexCount=19441`、**`blockCount=44`**、`histogram={plains:10719, mountains:886, ocean:4506, low_hills:3330}` **与 M6 Python 直读逐值相同**（含 `swamp→plains` 合并算术）；首格 `(-5,-59) terrain=plains height=0.375`。再用**新构建**跑**旧档副本** `:5819` ⇒ `overview 200 / 703,053 B`、零异常，**只杀自起的 5819，5817/5818 全程未动**。★ **旧变更集**（`hexes` 值带 terrain）不可静态迁移（切分依赖 base 全图）⇒ `MapCodec` **显式抛**并给重导入指引。
**变异 m1~m5 全杀**：漏 singleton 分量 / plains 吞中心一格（`expected:19 but was:20`）/ `BlockId` 自增 / `between` 不 diff `terrainBlocks` / `terrainAt` 回退默认地形（`expected:"mountains" but was:"plains"`）。
**门禁 867** = 170/272/45/131/155/94，+17，`BugInstance size is 0` ×6，ERROR 0。
**未核实**：`RegionRandomizer` 整体重切在 19441 格上的性能；`terrainAt` O(#块) 在极端碎片化图上的代价（批量已走 `terrainIndex`）；**未重跑 M7 系列浏览器 e2e**（前端零改动，只跑了 `measure.cjs`）。
