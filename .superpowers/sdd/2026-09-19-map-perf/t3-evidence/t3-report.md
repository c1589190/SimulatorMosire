# M9 T3 —— 服务端：读路径缓存 + 停发 overview 的 `height`（Java 侧）— t3-report.md

> 分支 `m9/t3`（worktree `.claude/worktrees/m9t3`，基线 `b17649c`）。**Java 改动 4 个生产文件 + 2 个测试文件**。
> 真档 `/tmp/m6-import-verify/test_integration`（19441 hex，**只读**，`simos.db` md5 全程 `2348b9365e5b107945a305d06fad8fab` 未变）。
> **一句话**：同一坐标的读请求不再反复"重读+解码 1.1MB checkpoint"（第二次 `checkpointReadCount` 增量为 **0**）；overview 停发死重量 `height` ⇒ 响应 **1,044,970 → 703,053 B（−341,917 B，32.72%）**；`/api/map/hex` 的 `height` **保留**。全量门禁 **850**（基线 844，delta **+6**，逐模块恰为新增 6 条用例）。

---

## 1. 改动（逐文件 + 行）

| 文件 | 改动 | 行 |
|---|---|---|
| `simos-app/.../gui/ApiViews.java` | **删除** `mapOverview` 里唯一一行 `hex.put("height", entry.getValue().height());`（原 `:162`）；**`mapHex` 的 `view.put("height", cell.height());`（现 `:247`）原样保留** | −1 行 |
| `simos-app/.../query/QueryService.java` | 新增有界 LRU 状态缓存 + 命中/未命中计数：容量常量 `:58`；`stateCache`/两个 `LongAdder` `:70-73`；`stateAt` 解析坐标后查缓存/回填 `:99-108`；`stateCacheHits()`/`stateCacheMisses()` `:111-119`；`StateCache`（`LinkedHashMap` 访问序 + `removeEldestEntry`）`:121-137`；类注更新 `:33-46` | +75 |
| `simos-core/.../store/CheckpointStore.java` | 新增 `readCount`（`LongAdder` 字段 `:41`、`read()` 入口自增 `:88`、只读访问器 `:101-104`） | +10 |
| `simos-core/.../CoreSimos.java` | 新增只读委托 `checkpointReadCount()`（`:270-277`），把"缓存命中 ⇒ 不读档"变成可断言数字 | +9 |
| `simos-app/.../query/QueryServiceTest.java` | 新增 4 条：`:151` 第二次不读 checkpoint、`:171` 提交后可见新 revision、`:188` 跨分支不串味、`:206` 缓存值不可变；命令夹具（`renameEnvelope`/`forkEnvelope`） | +108 |
| `simos-app/.../gui/GuiApiTest.java` | 新增 2 条：`:187` overview 无 `height` 且单格有、`:204` 第二次 overview 不读 checkpoint | +35 |

**未改**：前端（`webui/**` 零改动）、spec、plan、台账。**未加依赖**。

---

## 2. 断言实测值（真档、真服务）

### 2.1 overview 停发 `height`
| 指标 | 修前 | 修后 | 差 |
|---|---:|---:|---:|
| overview 响应体字节 | **1,044,970**（用户 `:5818`，主树 b17649c/T2 期类） | **703,053**（worktree `:45921`） | **−341,917（−32.72%）** |
| `"height"` 键出现次数 | **19,441** | **0** | −19,441 |
| hex 数（不变） | 19,441 | 19,441 | 0 |

* **修前来源**：用户常驻的 `:5818`（`--store …/test_integration`，主树 b17649c 类，含 height）——**只做只读 GET，未触碰**。
* **修后**：worktree 类自起 `:45921`，连续两次 GET 逐字节相同（703,053）。
* `python3` 断言第一个 hex：修前 `{'q':-5,'r':-59,'terrain':'plains','height':0.375}` → 修后 `{'q':-5,'r':-59,'terrain':'plains'}`（**无 height 键**）。

### 2.2 单格 `/api/map/hex` 的 `height` 必须保留
```
GET /api/map/hex?q=-5&r=-59 → 200, 282 B
keys: [facets, height, q, r, regions, terrain, terrainType]
height = 0.375   ✅ 保留
```

### 2.3 同一 target 第二次请求不读 checkpoint（可观测计数）
`coreSimos().checkpointReadCount()` 委托 `CheckpointStore.readCount()`（每次真正调用 `read` 自增）。在**真档**上起进程内 `Shell`（避开 5817/5818），用真 HTTP 连打两次 `/api/map/overview`：

```
checkpointReadCount  beforeFirst=0  afterFirst=1  afterSecond=1   ⇒ secondDelta=0
stateCacheHits=1  stateCacheMisses=1
overview.hasHeightKey=false
```
⇒ **第二次 overview 未触发任何 checkpoint 读取**（计数增量为 0），且 `hits` 恰 +1。JUnit 版同断言：`QueryServiceTest.secondRequestForTheSameTargetDoesNotReadACheckpoint`（`:151-169`）+ `GuiApiTest.secondOverviewForTheSameTargetDoesNotReadACheckpoint`（`:204-220`，走真 `GuiServer` HTTP）。

### 2.4 提交后立刻可见新 revision
`QueryServiceTest.commitIsVisibleOnTheNextHeadQuery`（`:171-186`）：真 `core.submit(new CommandEnvelope("unit.RenameUnit", …))` ⇒ `Committed(ref=main@2)`；紧接着 `queryService.stateAt(head(main))` 返回 `revision=2` 且单位名已变。**不靠读代码，靠真提交 + 真 head 查询。**

### 2.5 门禁（全量 `./mvnw clean verify`，rc=0）
| | util | map | social | unit | core | app | 合计 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 基线（b17649c） | 170 | 256 | 45 | 131 | 154 | 88 | 844 |
| **现在** | 170 | 256 | 45 | 131 | 154 | **94** | **850** |

* **delta = +6，逐模块解释**：只有 `simos-app` 变（88→94），恰为新增用例 — `QueryServiceTest` +4、`GuiApiTest` +2；其余五模块一例未动。
* `BugInstance size is 0` ×**6**，`[ERROR]` **0** 行，`BUILD SUCCESS`。
* 日志：`logs/full-verify.log`。

### 2.6 T2 harness 复跑（同装置、同真档、同视口 `1280×800`）
| 指标 | T2 new1 | T2 new2 | **现在** |
|---|---:|---:|---:|
| `firstInteractiveMs` | 509.4 | 377 | **420.7** |
| `render()` p50 | 1.75 | 1.65 | **1.65** |
| `apiBytes` | 2,090,800 | 2,090,800 | **1,406,966** |
| 启动窗口（<2s）字节 | 2,261,555 | 2,261,555 | **1,577,721** |
| `pageerror` | 0 | 0 | **0** |

* 启动字节下降 `2,261,555 − 1,577,721 = 683,834 = 2 × 341,917`（harness 首屏含两次 overview：无 target + `revision=1` 各一次）——与 §2.1 的字节差**逐字节吻合**。
* `firstInteractiveMs` 在 T2 的 0.38~0.51s 区间内（本机噪声）；`render()`/`pageerror` 不变。
* 产物：`run-now.json`、`logs/measure-now.log`；对照读自 `t2-evidence/run-new/baseline-new{1,2}.json`（同口径）。

### 2.7 写保护
`simos.db` md5 跑前 = 跑后 = `2348b9365e5b107945a305d06fad8fab`（与 T1/T2 相同）。**未用副本**——所有服务均直接指向真档，GET 只读。

---

## 3. 变异表

装置：`mutants/run-mutations.sh`（每轮**先还原原件并比 md5**、推变异体为规范类名并比 md5、清对应 `.class`、强制断言 `COMPILATION ERROR` 为 0、跑门禁、记红点、再还原自证）。汇总日志 `logs/mutations-summary.log`，各轮 `logs/m{1..4}.log`。

| id | 变异（`mutants/`） | 预期红点 | 实际红点 | 判定 |
|---|---|---|---|---|
| **m1** | `m1.QueryService.java`：缓存键**丢掉 revision**（恒 `RevisionId(1)`）⇒ 提交后 head 查询命中旧键 | `commitIsVisibleOnTheNextHeadQuery` | ✅ 红在 `QueryServiceTest.commitIsVisibleOnTheNextHeadQuery:183`（另连红 `cachedEntriesAreSeparatedByBranch` 及 3 条写后读的 GuiApi 断言） | ✅ 杀 |
| **m2** | `m2.QueryService.java`：缓存键**漏 branch**（恒 `main`）⇒ 跨分支串味 | `cachedEntriesAreSeparatedByBranch` | ✅ 红在 `QueryServiceTest.cachedEntriesAreSeparatedByBranch:202`（键漏 branch ⇒ `main@2` 与 `side@2` 串味） | ✅ 杀 |
| **m3** | `m3.QueryService.java`：命中路径改用**共享可变 modules map**（`new LinkedHashMap<>(state.modules())` 交给 `SimulationState`） | 第 2 次请求被污染 | ❌ **存活**（rc=0，无红点）——见下 | ⚠️ **不可实现** |
| **m4** | `m4.ApiViews.java`：**顺手把单格端点的 `height` 也删了** | 单格断言红 | ✅ 红在 `GuiApiTest.mapOverviewOmitsHeightWhileMapHexKeepsIt:199` + `GuiApiTest.mapHexBuildsTheCanonicalFacetSubject:166` | ✅ 杀 |

★ **m3 存活是结构性的，不是护栏装饰——如实记录，不伪造红点**（与 T2 §4.4 同口径）：
`SimulationState` 的紧凑构造器做 `modules = Map.copyOf(modules)`，**任何**传入的可变 map 在构造时即被拷贝冻结；`GameMap` 的 7 张表全 `Collections.unmodifiableMap`；各切片是 record；`InMemoryInfoSystem` 构造期深拷贝。⇒ 缓存值类型是**深度不可变**的，`stateAt` 返回同一实例也不会被下游就地修改。m3 的"下游就地修改缓存对象"在**不先削弱 `simos-state` 类型**（`simos-util`，跨模块、动它即动铁律 5 的往返断言）的前提下**无法表达**。已写了等价强度的护栏 `QueryServiceTest.cachedStateIsDeeplyImmutableSoDownstreamCannotPolluteIt`（`:206-219`）：实测 `first.modules().put(...)` 抛 `UnsupportedOperationException`，且两次读返回**同一实例**、内容未变。
（**请裁决**：接受"不可变使 m3 结构上不可杀"，还是要求把缓存值类型换成可变载荷以便 m3 可杀？后者会破坏现状且无收益。）

---

## 4. 缓存策略说明

* **位置**：`QueryService.stateAt`（app 层读门面）。所有只读端点（overview/units/state/hex/path/region/population）都经它取状态 ⇒ 一处缓存覆盖全部读路径。
* **键 = 已解析的 `StateRef(branch, revision)`**。`head` 在查缓存**之前**解析成具体 revision ⇒ **提交/分岔/推进产生新 revision ⇒ 新键 ⇒ 天然未命中**；新分支有独立键空间。⇒ **失效是键的函数，不装任何"提交时清缓存"的钩子**（钩子是与时间线并存的第二份真相，漏掉任一写路径就会读到旧 revision；键即失效条件则无从漏）。m1/m2 正是这一设计的两个反例变异。
* **值 = `SimulationState`，直接返回、不做防御性拷贝**：该类型深度不可变（见 §3 的 m3 说明）。选**不可变**而非拷贝的理由——拷贝无额外保护（值本就不可变），却会抹掉"同坐标两次读拿到同一实例"这层可观测性、且白耗内存/时间。
* **上界 = 8 条有界 LRU（访问序）**。理由：启动三端点共享同一坐标只需 1 条；时间轴预览在少量坐标间跳；8 条覆盖"一个分支的近期工作集"，同时把内存上界钉在 `8 × 单状态`。淘汰最久未用者（`get` 也更新新鲜度）。
* **并发**：GUI 走虚拟线程，`Collections.synchronizedMap` 保护单次 `get/put`；同一坐标的**并发首读**可能各重放一次（幂等、仅白干），但不会互相覆盖出错误值。**未做并发压力测试**（见 §6）。

---

## 5. 实测 vs 推断

| # | 命题 | 实测？ | 依据 |
|---|---|---|---|
| 1 | overview 修前 1,044,970 → 修后 703,053 B（−341,917，32.72%） | **实测** | 用户 `:5818` 与 worktree `:45921` 的 `%{size_download}`；`live/measurements.json` |
| 2 | overview 无 `height` 键、单格有 | **实测** | `grep -c '"height"'`=0；`/api/map/hex` 返回 `height:0.375` |
| 3 | 第二次同坐标不读 checkpoint（增量 0） | **实测** | 真档进程内 probe：`afterFirst=1, afterSecond=1, secondDelta=0`；+ 两条 JUnit |
| 4 | 提交后 head 查询立刻见新 revision | **实测** | `QueryServiceTest.commitIsVisibleOnTheNextHeadQuery`（真 submit） |
| 5 | 跨分支不串味（漏 branch 会红） | **实测** | m2 变异红在 `cachedEntriesAreSeparatedByBranch` |
| 6 | 缓存值不可被下游就地修改 | **实测** | `modules().put` 抛 `UnsupportedOperationException`；m3 变异存活即其反证 |
| 7 | 门禁 850 = 170/256/45/131/154/94 | **实测** | `logs/full-verify.log` |
| 8 | T2 harness 启动字节降 683,834 = 2×341,917 | **实测** | `run-now.json` vs `baseline-new{1,2}.json` |
| 9 | 写保护：`simos.db` md5 未变 | **实测** | 跑前/跑后同 `2348b936…` |
| 10 | 8 条 LRU 的内存上界（8 × 单状态） | **推断** | 未量单状态实际驻留内存；容量 8 基于"工作集小"的设计判断 |
| 11 | LRU 淘汰在长会话下的行为 | **推断** | 未构造 >8 个不同坐标的会话实测淘汰 |
| 12 | 并发首读"幂等、不覆盖错误值" | **推断** | 由 `synchronizedMap` + 纯重放确定性推得；未做并发压测 |
| 13 | `Map<String,Object>` 的 props 值是裸 Object、不相对于缓存额外保护 | **推断（读类型得）** | `City/Pathway/EdgeTags` 只冻结外层/内层 map，不深冻 value；真实导入档 `cities` 为空，无实测样本 |
| 14 | HiDPI / 其他浏览器 / 其他真档 | **未测** | 仅 headless Chromium dpr=1 + 本机真档 |

---

## 6. 我未能核实的

1. **m3 无法作为合法变异杀掉**（原因见 §3）；未提供能杀 m3 的变异，请裁决。
2. **缓存内存上界未实测**：8 条状态的驻留内存、大图下单状态大小均未量；容量 8 是设计判断。
3. **并发未压测**：只用 `synchronizedMap` 保证单次操作安全；"同一坐标并发首读各重放一次"的代价与 LRU 访问序并发语义未测。
4. **LRU 淘汰未实测**：>8 个不同坐标后最旧被逐出、以及逐出后再次读的代价，未构造会话测。
5. **props 裸 `Object` 值的可变性问题**：`City`/`Pathway`/`EdgeTags` 的 `Map<String,Object>` 值不深冻——若状态里真放了可变对象，本缓存不额外保护（这是 domain 类型的既有边界，非本缓存回归）；真实导入档无该样本，未实测。
6. **只在真实 `test_integration` 档（19441 hex）+ 本机 headless Chromium dpr=1**；其他档、HiDPI、其他浏览器未测。
7. **未在 `:5817`/`:5818` 上执行任何写操作**；对 `:5818` 仅做了只读 GET（按纪律）。

---

## 7. 证据索引（全部在 `t3-evidence/`）

| 文件 | 内容 |
|---|---|
| `t3-report.md` | 本文件 |
| `live/measurements.json` | 修前/修后字节、单格 height、probe 计数、T2 对照、门禁数字 |
| `run-now.json` | T2 harness 复跑原始结果（now） |
| `logs/full-verify.log` | 全量 `clean verify`（850、0 ERROR、BugSize 0 ×6、BUILD SUCCESS） |
| `logs/measure-now.log` | harness 运行日志 |
| `logs/server-after.log` + `server-after.pid` | worktree `:45921` 真档服务日志（含停止信号） |
| `logs/simos-db-before.txt` + `live/measurements.json` | 写保护 md5 前后 |
| `logs/mutations-summary.log` + `logs/m{1,2,3,4}.log` | 4 轮变异自证与红点 |
| `mutants/run-mutations.sh` | 变异装置（干净世界 + md5 自指 + 编译错误作废 + 还原自证） |
| `mutants/orig.{QueryService,ApiViews}.java` / `m1..m4.*` | 原件与 4 个变异体 |
| `mutants/*.md5`（在 summary 内） | 每轮 orig/mutant/pushed md5 |

---

## 8. 与任务书矛盾处 / 指出的笔误

1. **m3 预期"红"，实测"存活"**（§3）。根因：任务书假设缓存值可变，而 `SimulationState` 是深度不可变的；"下游就地修改"在不改 `simos-util` 类型的前提下无法表达。**这是任务书对类型的假设有误，不是实现没做**。请裁决。
2. 任务书 (B)1 说"只删 `ApiViews.java` 的 `mapOverview` 那一行"——**已严格照做**；顺带指出**同仓另有** `ToolSupport.mapOverview`（MCP `simos.map.overview` 工具，`ToolSupport.java:310`）与 `MapHexTool.java:78` 仍发 `height`，**本单按 MUST 未动**（GUI overview 与 MCP overview 是两条面）。
3. 任务书给的基线 **844** 与源码一致（S 和 = 844），本单 delta +6 如上；无笔误。
