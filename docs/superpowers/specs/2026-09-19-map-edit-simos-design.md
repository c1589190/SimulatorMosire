# M8 spec —— 地图编辑写面（`map.*` 命令族 + 五模式）

> 状态：**待执行**。作者：控制器。日期：2026-09-19。
> 前置：M0~M7g 已完成；`main` == `feat/adr1-core-scope` == `0d86077`。
> 台账：`.superpowers/sdd/2026-09-19-map-edit/progress.md`

## 〇 〇.1 关账判据（M8 完成 = 这七条逐条实测）

1. **地图编辑端到端**：从 WebUI **选地形 + 拖一串 hex** ⇒ 真 `Command → ChangeSet → Revision` ⇒ **重放后地形是新值**、时间线**多一个节点**、`head` 前进。
2. **区域编辑端到端（含重叠）**：新建一个区域，其 hex 与已有区域**重叠** ⇒ **成功、不报错** ⇒ `/api/map/hex` 的 `regions` **同时列出两者** ⇒ 重放后一致。
3. ★ **多从属地基（M8-U1）**：重叠 hex 解析出**多个** region（**不存在"谁赢"**）；`regions` **按 RegionId 字典序**排序 ⇒ 同一状态**两次输出逐字节相同**。
4. **`EdgeTags` 语义显式**：`replace` 与 `merge` **两条都有实测**，且 **merge 不丢既有 tag**。
5. **模式白名单**：五个模式各自的**写权限可断言**（常规/区域查看模式**写不了**）；**非 GET 清单按模式断言**。
6. **既有能力不退化**：M7/M7b~M7g（右键路线、左键取消移动、tick 分组、推进 N、全屏底图、浮层不穿透）**全绿**。
7. **门禁**：`./mvnw clean verify` rc=0；**前端单元测试已在门禁内**（见 §五）；每任务**变异轮全杀**。

## 〇.2 裁定汇总

| # | 项 | 裁定 | 来源 |
|---|---|---|---|
| **M8-U1** | 区域从属 | ★★ **多对多**：hex 可**同时属于多个区域**；**不存在"重叠时谁赢"** | **用户原话** |
| **S4** | `map.*` 粒度 | **语义化命令**（每编辑一条，规则在 `simos-map`）；**不做**通用 `ApplyChangeSet` | M7 已批 |
| **S5** | 圈选随机化选区 | **允许任意选区** | M7 已批 |
| **Q1** | 撤销/重做 | **不做**；想撤销就**回退到前一节点**（分叉） | 用户批准 |
| **Q2** | 河流/道路 | 命令**必须显式声明 `replace` 还是 `merge`**；**默认 `replace`** 但必须显式写 | 用户批准 |
| **Q3** | 区域命令面 | **三条**：`Create` / `Update` / `Delete`；**`RegionId` 由调用方给**（不自动生成） | 用户批准 |
| **Q4** | `GenerationSpec` | **不可改**（铁律 5：`spec` 不进变更集） | 用户批准 |
| **Q5** | 地形画图粒度 | **一条命令带多个 hex**（拖一串 = 一条） | 用户批准 |
| **Q6** | `regions` 排序 | **按 RegionId 字典序**（否则 JSON 随哈希序变 ⇒ 用例不稳） | 用户批准 |
| **Q7** | 五模式 | 常规 / 区域查看 / 地图编辑 / 区域编辑 / 单位移动编辑——**每模式一份写权限白名单** | 用户批准 |

★ **用户授权**：「允许推翻之前的设计、改之前的代码」⇒ 本 spec 可以改 M2/M5/M7 的既有代码与断言（**但断言不得改成恒真**）。

## 一 地基：多从属（M8-U1）

现状（M2）：`RegionIndex` 把 hex 映到**一个** region；M5 的 `/api/map/hex` 回单个 `region`。
实测证据（T4 期）：`test_nation`(701) 与 `test_annex_target`(201) 重叠，`test_nation` 首格经权威解析**归了** `test_annex_target` ⇒ 现状是"后写覆盖"，**依赖写入顺序、不可复现**。

**改成**：
| 位置 | 旧 | 新 |
|---|---|---|
| `RegionIndex` | `hex → RegionId` | **`hex → Set<RegionId>`**（或有序 `List`，**规范化排序**） |
| `MapResolver.regionOfHex` | `Optional<RegionId>` | **`List<RegionId>`**（字典序） |
| `/api/map/hex` | `region: id` | **`regions: [id…]`**（字典序，**空数组表示无从属**） |
| 区域高亮（T6 的 `highlightRegions`） | 已是数组 | 语义名正言顺，**多区域同亮** |
| 画/改区域 | —— | **重叠不报错**（正常状态） |
| `RegionMeta.hexCount` | 单区域计数 | ⇒ **不得再用"各区域 hexCount 之和"当并集**（裁定 72.1） |

★ **保留**：`RegionIndex` 仍是**派生件**（可从 `GameMap.regions` 重建）⇒ 铁律 5 的往返不变式**不受影响**；重建逻辑本身必须有**故意违规**用例自证。

## 二 命令族（`namespace.Command`，构造期校验；M4 裁定 37）

| 命令 type | 载荷 | 语义要点 |
|---|---|---|
| `map.SetTerrain` | `hexes: Set<HexCoord>`, `terrain: String` | **一条命令多个 hex**（Q5）；地形必须在 `TerrainCatalog` 词表内（**词表外拒绝**，fail-closed）；hex 必须在 `GameMap` 内 |
| `map.CreateRegion` | `regionId: RegionId`, `hexes: Set<HexCoord>`, `meta` | **重叠允许**；`regionId` 已存在 ⇒ **拒绝** |
| `map.UpdateRegion` | `regionId`, `hexes?`（新集合）, `meta?` | 二者至少给一个；**不存在的 region ⇒ 拒绝** |
| `map.DeleteRegion` | `regionId` | **不存在 ⇒ 拒绝**（不做静默幂等） |
| `map.SetEdge` | `kind: RIVER\|ROAD`, `edges: Set<EdgeKey>`, `mode: replace\|merge` | ★ **必须显式** `mode`（Q2）；`replace` 整份覆盖该 kind，`merge` 只加不删 |
| `map.RandomizeRegion` | `hexes: Set<HexCoord>`, `seed: long` | 任意选区（S5）；**seed 由调用方给**（确定性 ⇒ 可复现、可断言） |

★ **所有命令**：走 `Command → ChangeSet → Revision`（铁律 2）；`MapChangeSet` 从**完整状态类型派生** + **往返不变式测试**（铁律 5）。
★ **不自动生成 `RegionId`**（Q3）——自动生成会让"同一操作两次不同"，破坏可复现与可断言。

## 三 五模式（Q7）

| 模式 | 允许的写命令 | 主要 UI |
|---|---|---|
| **常规** | **无**（只读） | 选中/单位/时间轴（现状） |
| **区域查看** | **无**（只读） | 点 hex ⇒ **高亮所有从属区域** + 左栏列 `regions` 多值；选区域 ⇒ **其他淡色** |
| **地图编辑** | `map.SetTerrain` / `map.SetEdge` / `map.RandomizeRegion` | 地形调色板（7 类词表）+ **拖刷** + 区域信息编辑 + 河流/道路（含 `replace/merge` 选择器）+ 圈选随机化 |
| **区域编辑** | `map.CreateRegion` / `UpdateRegion` / `DeleteRegion` | 选标签 ⇒ 绘新区域（**重叠不报错**）；改已有区域 hex 集合 |
| **单位移动编辑** | `unit.PlanRoute` / `unit.CancelRoute` | 现状（右键） |

★ **白名单是硬约束**：非该模式的写命令**前端不得发出**；**且要能断言**（模式 × 非 GET 清单）。
★ **服务端不因模式而变**——模式是**前端**概念（防误操作）；服务端仍是 `CommandBus` 的统一校验（词表/存在性/权限）。

## 四 不改的东西（边界）

- **不加依赖**、无 npm/无 CDN/无外部字体（M7 的形态保持）。
- 铁律 1~5 不动；Core 的 main scope **仍看不见领域模块**（ADR-1）⇒ `map.*` 命令的**规则在 `simos-map`**，Core 只转发信封。
- 事件链形状（R6）**不动**：信封支 5 条 / `AdvanceTime` 支由 route 写全。
- 旧三页（`/map` 等调试页）**保留**。

## 五 ★ 前端护栏进 CI（本 spec 新增，回应已知系统性开口项）

现状（M7 关账记录）：**本项目没有 JS 测试器** ⇒ 前端护栏（时间轴纯函数、几何换算、`buildTree`、分组、写路径 allowlist）**全是"证据级"**，不进 Maven 门禁 ⇒ **有静默腐烂风险**（`AppWritePathGuardTest` 只扫 Java、**不扫 `webui/**`**）。

**M8 要求**：把前端**纯函数**单元测试接进 `./mvnw verify`（建议 `exec-maven-plugin` 调 `node --test`，**零 npm 依赖**；Node 不可用时**必须有明确失败**，不许静默跳过），并把 M7 时代"证据级"的纯函数（时间轴列布局/几何换算/`buildTree`/写路径 allowlist）**搬到门禁内**。
★ **自证**：给一个**故意违规**用例（如 allowlist 里塞一个未声明的写路径）证明门禁**真会红**（"护栏必须自证"，否则等于装饰）。

## 六 任务分解

| # | 任务 | 批次 | 依赖 |
|---|---|---|---|
| **T1** | 多从属地基：`RegionIndex` 多值 + `MapResolver` + `/api/map/hex` `regions` + 前端适配 + 既有断言改造（**含 M2 的 `RegionIndexGuardTest`、M5 的 `ApiViews`**） | A 地基 | —— |
| **T2** | ★ **前端单元测试接入 Maven 门禁**（`node --test` + `exec-maven-plugin`；搬 M7 纯函数；故意违规自证） | A 地基 | T1（避免同时动前端文件） |
| **T3** | `map.SetTerrain`（多 hex 一条；词表 fail-closed） | A 命令 | T1 |
| **T4** | `map.CreateRegion` / `UpdateRegion` / `DeleteRegion`（**重叠允许**；`RegionId` 调用方给） | A 命令 | T1 |
| **T5** | `map.SetEdge`（`replace` / `merge` **显式**；merge 不丢既有 tag） | A 命令 | T1 |
| **T6** | `map.RandomizeRegion`（任意选区 + 调用方 seed；`RegionRandomizer` 吃 `Set<HexCoord>` 的重载） | A 命令 | T1 |
| **T7** | UI 模式框架：五模式 + 白名单 + 状态机（可断言） | B UI | T2 |
| **T8** | 地图编辑模式 UI：地形调色板 + **拖刷**（多 hex 一条命令）+ 区域信息编辑 | B UI | T3, T7 |
| **T9** | 区域查看模式 UI：**多从属**显示 + 多区域同亮 + 其他淡色 | B UI | T1, T7 |
| **T10** | 区域编辑模式 UI：绘新区域（**重叠不报错**）+ 改已有区域 | B UI | T4, T7 |
| **T11** | 连通性 + 随机化 UI：河流/道路（`replace/merge` 选择器）+ 圈选随机化 | B UI | T5, T6, T7 |
| **T12** | 判据①~⑦端到端实测 + 变异点验 + 关账报告 | C | 全部 |

**并行/串行纪律**：同文件被两任务改 ⇒ **串行**，后关账者**必须重跑前者的变异轮**（旧证据的对象已被改掉）。Batch A 的 T3~T6 都在 `simos-map` + `CommandRegistry` 注册表 ⇒ **T1 → (T3, T4, T5, T6 串行)**；T2 与 T3~T6 可并行（文件不相交）。

## 七 遗留（带裁定）

- `GameMap` **无 id** ⇒ `map:<mapId>` 的 mapId 只回显不可校验（M2 起挂起）。
- 属性段地址不服务；人口 cache 未做；A\* 规模与跨 JVM 决定论未测。
- `PlanRoute` **稀疏路点缺口**（M7b 挂起）。
- 大图性能：**服务端 overview 40–85ms**，瓶颈是 **~1MB 传输（浏览器内 ~2.6s）+ 首帧渲染 ~2.55s** ⇒ 视口分级 / gzip（M7 裁定 70）。
- 倒树方向（根在下）可推翻。
- 审批**超时与会话键未验**；`fork` **不发事件**；R9 长连分支未覆盖。
- `simos.db` **DDL 由 `tools/gsimap_import.py` 内联** ⇒ Core 改 DDL 时脚本会**静默失配**（无编译期护栏）。
