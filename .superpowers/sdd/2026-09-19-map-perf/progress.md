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
