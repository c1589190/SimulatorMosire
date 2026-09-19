# M8 计划 —— 地图编辑写面（bite-sized）

> 配套 spec：`docs/superpowers/specs/2026-09-19-map-edit-simos-design.md`（**判据见其 §〇.1**，七条）
> 台账：`.superpowers/sdd/2026-09-19-map-edit/progress.md`（裁定与结论）
> ★ **执行期纪律**：本计划的代码草图是**计划期产物**，与 `src` 分歧处以**源码为准**，分歧记入台账。

## 〇 通则（每个任务都适用）

- 派单只准 `subagent_type="deepseek-flash-go"`；**单任务 worktree**；**一次只跑一个 Maven**。
- 每任务必须：`./mvnw -q spotless:apply` → 定向测试 → **合并后主树 `./mvnw clean verify`**（关账前）。
- **护栏必须自证**：每个新护栏配**故意违规**用例 + **≥2 轮变异**（九道门禁：干净世界 / 变异体字节不同 / 白名单推成目标类名 / 清陈旧 `.class` / `COMPILATION ERROR`=0 / surefire mtime 落轮内 / 红点落被保护断言 / `cp` 逐字节还原 / **日志自指**）。
- **同一文件被两任务改 ⇒ 串行**，后关账者**重跑前者的变异轮**。
- **不 `git add -A`**；`.superpowers/**` 用 `git add -f`；不加 `Co-Authored-By`；**该推就推**。
- 证据放 `.superpowers/sdd/2026-09-19-map-edit/tN-evidence/`（**不许放仓根**）。

---

## T1 多从属地基（Block：所有下游）

**目标**：区域从属由"单值"改为**多值**（M8-U1），且**输出确定**（字典序）。

**必须做**
1. `RegionIndex`：`hex → Set<RegionId>`（或有序 List，**规范化排序**）；仍是**派生件**（可从 `GameMap.regions` 重建）。
2. `MapResolver.regionOfHex` → **`List<RegionId>`**（字典序）。★ 检查**所有调用点**（不只 `ApiViews`）。
3. `/api/map/hex`：`region: id` ⇒ **`regions: [id…]`**（字典序；**空数组 = 无从属**）。
4. **既有断言改造**：M2 的 `RegionIndexGuardTest`、M5 的 `ApiViews`（含 T4 的 `region` 断言）⇒ 按新语义改，**不得改成恒真**。
5. 前端：左栏 hex 详情由单 `region` 改为**列出 `regions`**（多值时逐条显示）。
6. 重叠夹具：**必须用真重叠数据**（`test_integration` 的 701/201 已重叠 ⇒ 可用；另补一个合成夹具覆盖"3 个区域重叠同一格"）。

**不许做**：不删 `RegionIndex` 的**重建**能力；不改铁律 5 的往返框架；不动 `RegionMeta.hexCount` 的语义（只在 UI 侧**禁止求和当并集**）。

**变异（≥2 轮）**
- m1：`regionOfHex` 只回**第一个**（丢多值）⇒ `regions` 少一条 ⇒ 红。
- m2：`regions` **不排序**（按 `Set` 迭代序）⇒ 同一状态两次输出可能不同 ⇒ 红（★ 注意 M2 教训：**夹具键数不足会假绿**，重叠夹具要有**足量**键）。

**判据**：spec 判据 ③；并要求 `/api/map/hex` 两次调用**逐字节相同**。

---

## T2 ★ 前端单元测试接入 Maven 门禁

**目标**：把"证据级"的前端纯函数护栏**搬进门禁**（回应 M7 系统性开口项）。

**必须做**
1. `exec-maven-plugin` 调 `node --test`（**零 npm 依赖**），绑到 `simos-app` 的 `test` 阶段。
2. **Node 不可用 ⇒ 明确失败**（不许静默跳过；这是"装了护栏不跑 = 装饰"的反面）。
3. 搬入 M7 时代的纯函数测试：时间轴**列布局**（`(列−1)×列宽`，列由 `parent` 递归决定）、**tick 分组**、几何换算、`buildTree`、**写路径 allowlist**。
4. ★ **故意违规自证**：给一个**未声明的写路径**塞进 allowlist 用例 ⇒ 门禁**真红**。

**不许做**：不引入 npm/package.json/网络依赖。（★ Node 版本差异若导致不稳定，**记录**而非绕过。）

**变异**：m1：allowlist 用例改成永真断言 ⇒ 故意违例**不红** ⇒ 装置自证失败（应被拒绝）；
m2：`buildTree` 少一层 ⇒ 红。

**判据**：spec 判据 ⑦ 的前半（前端测试**在门禁内**）；`./mvnw clean verify` 里能看到 node 测试**跑过且计数**。

---

## T3 `map.SetTerrain`（地图地形命令）

**目标**：一条命令改**多个** hex 的地形（Q5）。

**必须做**
- 命令在 `simos-map`：`map.SetTerrain(hexes: Set<HexCoord>, terrain: String)`；`type()` = `map.SetTerrain`（构造期校验，M4 裁定 37）。
- **词表 fail-closed**：地形必须 ∈ `TerrainCatalog`（7 类高度带），**词表外拒绝**（不是兜底色）。
- hex **必须在 `GameMap` 边界内**，否则拒绝。
- `MapChangeSet` 更新 `terrainTypes`（**从完整状态派生** + **往返不变式测试**，铁律 5）。
- 注册进 `CommandRegistry`；handler 单测覆盖：正常 / 词表外 / 越界 / 空 hexes。

**变异（≥2 轮）**：m1：词表校验删掉 ⇒ 越界/词表外用例红；m2：只改第一个 hex ⇒ 红；m3：往返不变式删一字段 ⇒ 红。

---

## T4 区域命令（`Create` / `Update` / `Delete`）

**目标**：区域增删改，**重叠允许**（M8-U1），**`RegionId` 调用方给**（Q3）。

**必须做**
- 三条命令 + 三条 handler；`RegionId` 已存在 ⇒ `Create` **拒绝**；`Update`/`Delete` 目标不存在 ⇒ **拒绝**（**不做静默幂等**）。
- `UpdateRegion`：`hexes?` 与 `meta?` **至少给一个**。
- **重叠不校验、不报错**（正常状态）；★ 但 `Create` **必须**校验 `boundary.equals(RegionBoundary.of(hexes))`（M6 证过的硬校验：边界必须与 hex 集合自洽）。
- 往返不变式（铁律 5）。

**变异（≥2 轮）**：m1：`Create` 对重复 id 静默覆盖 ⇒ 红；m2：`Delete` 对不存在静默成功 ⇒ 红；m3：`boundary` 自洽校验删掉 ⇒ 红。

---

## T5 `map.SetEdge`（连通性：河流/道路）

**目标**：`EdgeTags` 编辑，**`replace`/`merge` 显式**（Q2）。

**必须做**
- `map.SetEdge(kind: RIVER|ROAD, edges, mode: replace|merge)`；**`mode` 无默认值**（缺了 ⇒ 反序列化/校验失败 ⇒ 拒绝）。
- `replace`：整份覆盖该 kind；`merge`：**只加不删**。
- ★ **必须实测 merge 不丢既有 tag**（M2 挂起项：重建河流曾整份覆盖 `EdgeTags`）。
- 使用 `test_integration` 真档里那条**非空 `edges`**（`mcp_smoke_test`，M6 记录）做夹具（若无则合成）。

**变异（≥2 轮）**：m1：`mode` 给默认值 `replace` ⇒ "未显式声明"用例红；m2：`merge` 变成覆盖 ⇒ 既有 tag 丢失 ⇒ 红。

---

## T6 `map.RandomizeRegion`（任意选区随机化）

**目标**：圈一块 hex 直接随机化（S5），**seed 调用方给**（确定性）。

**必须做**
- 给 `RegionRandomizer` 加吃 **`Set<HexCoord>`** 的重载（**不改**原 region-id 入口的语义）。
- 命令 `map.RandomizeRegion(hexes, seed)`：**同一 seed + 同一 base ⇒ 逐字节相同**（可断言）。
- 结果的种子表**逐值断言**（照 M3 的人口种子表与移动逐值表的做法）。

**变异（≥2 轮）**：m1：忽略 seed（用随机源）⇒ 复现用例红；m2：把 `Set` 迭代序当输入序 ⇒ 同一 seed 两次不同 ⇒ 红。

---

## T7 UI 模式框架（五模式 + 白名单）

**必须做**
- 五模式（常规 / 区域查看 / 地图编辑 / 区域编辑 / 单位移动编辑）；模式栏**从占位变真控件**。
- **写权限白名单**（spec §三）——**前端是唯一防线**（服务端另有统一校验）；非当前模式的写命令**前端不得发出**。
- ★ 白名单必须写成**纯函数**并**进 T2 的门禁**；**故意违规**用例自证。
- e2e 断言：**每个模式 × 非 GET 清单**（常规/区域查看 ⇒ **只有 `/api/command` 零次或仅 advance**；编辑模式 ⇒ 含对应命令 type）。

**变异（≥2 轮）**：m1：白名单里给"区域查看"放行一个写命令 ⇒ 断言红；m2：切模式不清选中/不改 UI ⇒ 红。

---

## T8 地图编辑模式 UI

**必须做**：地形调色板（**只列 `TerrainCatalog` 的 7 类**）+ **拖刷**（拖过一串 hex ⇒ **一条** `map.SetTerrain`，**不是每格一条**）+ 区域信息编辑（改 `RegionMeta`）。
**e2e**：拖 3 格 ⇒ `hexes` 长度 3、**head +1**、**时间线只多一个节点**（tick 分组保住）；重放后地形**逐值**对上。
**变异**：m1：每格发一条命令 ⇒ "节点/tick 数"断言红；m2：调色板列出词表外地形 ⇒ 红。

---

## T9 区域查看模式 UI

**必须做**：点 hex ⇒ **高亮所有从属区域**（多值！）+ 左栏列 `regions`；选区域 ⇒ **其他淡色**；★ **禁止把各区域 `hexCount` 求和当并集**（裁定 72.1）。
**e2e**：重叠格点选 ⇒ 高亮数 == `regions.length`（**>1**）；淡色状态可断言。
**变异**：m1：只高亮第一个 ⇒ 红；m2：显示求和后的"总面积" ⇒ 红。

---

## T10 区域编辑模式 UI

**必须做**：选标签 ⇒ 绘新区域（**重叠不报错**）；改已有区域 hex 集合（`UpdateRegion`）；删除（`DeleteRegion`，要确认）。
**e2e**：画一个**与已有区域重叠**的新区域 ⇒ **成功**、`regions` 列出两者、重放一致；★ **负例**：重复 id ⇒ 收到拒绝且**不产生 revision**（行数不变）。
**变异**：m1：前端对重叠**先报错**（自造限制）⇒ 红；m2：删除无确认 ⇒ 红。

---

## T11 连通性 + 随机化 UI

**必须做**：河流/道路绘制 + **`replace`/`merge` 选择器**（**必须显式**，无默认）；圈选随机化（选区 + seed 输入）。
**e2e**：`merge` 后既有 tag **仍在**；`replace` 后只剩新的；随机化同 seed 两次 ⇒ **逐字节相同**。
**变异**：m1：UI 静默给 `replace` 默认 ⇒ 红；m2：seed 输入被忽略 ⇒ 红。

---

## T12 判据端到端 + 关账

**必须做**：spec §〇.1 七条**逐条实测值**（不是"通过/不通过"，要**数字**）；R1~Rn 点验；每任务变异轮汇总；**"我未能核实的"清单**；关账报告 `task-12-final-report.md`。
**门禁**：主树 `./mvnw clean verify`（rc=0、逐模块计数、`BugInstance size is 0` ×7、`[ERROR]` 0）——★ **前端测试必须在门禁输出里可见**。

---

## 附：批次与串行约束

```
T1 ──┬─→ T2 ──→ T7 ──┬─→ T8  (T3 →)
     │                ├─→ T9  (T1 →)
     ├─→ T3 ──────────┤
     ├─→ T4 ──────────├─→ T10 (T4 →)
     ├─→ T5 ──────────├─→ T11 (T5, T6 →)
     └─→ T6 ──────────┘
T12 ← 全部
```
★ T3~T6 都在 `simos-map` + `CommandRegistry` ⇒ **串行**；T2 与 T3~T6 文件不相交 ⇒ **可并行**。
★ 本机 `nproc` 实测 8，但**本地推理网关与 Maven 抢核** ⇒ **一次只跑一个 Maven**。

---

## ★★ 执行期取代说明（2026-09-19，M9 T6 之后）

**M9（大图性能）的 T6 改动了地形数据模型**，本计划 T3~T11 的相关描述**按此取代**：
- `HexCell(terrain, height)` ⇒ **`HexCell(height)`**；地形改由 **`GameMap.terrainBlocks: Map<BlockId,TerrainBlock>` 权威承载**（`TerrainBlock = terrain + Set<HexCoord> + 带洞 RegionBoundary`）。
- ★★ **分割不变式**（`GameMap` 构造期强制）：块 `hexes` **并集 == 全部 hex、两两不交**；失败消息精确到 hex。
- **`terrainAt(HexCoord)`** 是唯一稳定访问器（`terrainIndex()` 供批量）；**`simos-map` 之外不许直读块内部**（有 grep 守卫用例）。
- `MapChangeSet` 由 7 ⇒ **8 组件**（含 `terrainBlocks`）；`RoundTripComponentsTest` 的组件数与两个 `switch` 已同步。
- **`BlockId` 确定性**：`<terrain>@<最小hex>`（如 `plains@-5_-59`）；`TerrainBlocks.split` 用 `TreeMap` 全序。
- ⇒ **`map.SetTerrain`（T3）必须"改块 + 重切分"**（合并/拆分受影响块），**不得逐格写地形**；`map.RandomizeRegion`（T6）同理。
- 真档参考值：**19441 格 = 44 个块**（`test_integration`，旧档经 `MapCodec` 就地迁移）。
