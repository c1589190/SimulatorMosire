# Task 5 报告：`map` 包 —— `HexCell` / `City` / `GameMap`

**状态**：DONE_WITH_CONCERNS（等控制器复核）
**BASE**：`07a49f0`；分支 `feat/m2-map-simos`
**需求来源**：`.superpowers/sdd/2026-09-16-map-simos-plan/task-5-brief.md`（唯一需求来源）
**提交**：见本文件末尾「提交」一节

---

## 一 交付物

| 文件 | 内容 |
|---|---|
| `simos-map/src/main/java/io/mosire/simos/map/HexCell.java` | `record HexCell(String terrain, double height)`；**两条守卫的次序是要害**：`!Double.isFinite(height)` 排在 `[0,1]` 范围校验**之前**（NaN 与范围比较恒 false，调序会让 NaN 漏过去） |
| `simos-map/src/main/java/io/mosire/simos/map/CityId.java` | 三件套（非空白守卫 + **手写裸值 `toString()`** + `static parse`），与 `RegionId`/`PathwayId` 同形制（R-48-f） |
| `simos-map/src/main/java/io/mosire/simos/map/City.java` | `record City(CityId, String name, HexCoord at, RegionId region, Map<String,Object> props)`；`props` **保序不可变**、键值拒 null；`region` **可为 null**（= 不在任何区域内） |
| `simos-map/src/main/java/io/mosire/simos/map/GameMap.java` | 8 组件状态根（**本项目完整状态类型**，铁律 5）；7 个 map 一律**保序不可变**；8 个 `with*`；`regionIndex()` 派生；**无 `boundaryOf`**（U2 后已删） |
| `simos-map/src/main/java/io/mosire/simos/map/generate/GenerationSpec.java` | **骨架**：`record GenerationSpec(long seed)` + `static defaults(long)`。不再多写一个字段（Task 8 的参数面不归本任务定） |
| `simos-map/src/test/java/io/mosire/simos/map/HexCellTest.java` | 5 条 |
| `simos-map/src/test/java/io/mosire/simos/map/CityTest.java` | 6 条（**超出 brief 的文件清单**，理由见「顾虑 C2」） |
| `simos-map/src/test/java/io/mosire/simos/map/GameMapTest.java` | 16 条 |

`GenerationSpec` 骨架、`empty()` 的 `0L` 种子、`spec` 从不 null（R-48-e）、两个死字段与两个废弃 record 的删除，均按 brief 与控制器补充裁定落。

---

## 二 门禁（`./mvnw -pl simos-map -am clean verify`）

**最终一次是干净的**：`BUILD SUCCESS`，Spotless / Checkstyle / **SpotBugs** 三段全过；
`simos-util` 156 条、`simos-map` 132 条（HexCellTest 5 + CityTest 6 + GameMapTest 16 + Task 1~4 既有）。
原始输出：`task-5-evidence/gate-clean-verify.txt`。

★ **第一次门禁是红的，而且红得有价值**（原始输出留着：`task-5-evidence/gate-clean-verify-FAILED-before-fix.txt`）：
SpotBugs 报 **7 条 `EI_EXPOSE_REP`**，全部指向 `GameMap` 的 7 个 map 访问器（`hexes()/regions()/…/edges()`）。

- **实测现象**：当时冻结写在私有 helper `ordered(...)` 里（`return Collections.unmodifiableMap(copy);`），SpotBugs 判不出字段不可变；同一个写法**内联在赋值处**时（`EdgeTags`/`Pathway`/`PathwayGroup` 三处既有先例）就不报。
- **修法**：把冻结挪到赋值处 —— `hexes = Collections.unmodifiableMap(copyOf(hexes, "hexes"));`，`copyOf` 只留"拷一份 + 逐个键值查 null"。语义一字未变（仍是 `LinkedHashMap` 保序 + `unmodifiableMap` 冻结）。
- **机理（推导）**：SpotBugs 只认它在调用点**看得见**的 `Collections.unmodifiableMap`，不看私有方法内部。
  故记成一条可复用的门禁经验：**冻结这一步别藏进 helper**。

---

## 三 变异实验室（7 轮，全部实测）

装置：`task-5-evidence/run.sh` + `mutate.py`（Task 4 的装置逐字继承，只换 TARGET 表、镜像/清单范围、变异动作；
**不变式一条没松**：干净世界 → 逐文件 md5 清单（多一个 `.java` 即作废）→ 清 `target/{classes,test-classes,surefire-reports}`
→ **按目标类名原地写入**（不按变异文件名拷入）→ md5 自证落盘字节与原件不同 → 断言除目标外无文件被改动
→ 跑模块测试 → **强制断言 `grep -c "COMPILATION ERROR"` 为 0** → 断言 `Running io.mosire.simos.map` 计数 > 0）。
逐轮原始输出：`task-5-evidence/rounds/m5v-*.kept`，整轮日志：`rounds/run-all.log`。

每轮的头两行是**自证**：`md5 原件≠变异体`（落盘的确是与原件字节不同的那份）、`除目标外无文件被改动`；
`编译错误=0` 与 `跑过的测试类数=15` 是**入表的前提**，任一不成立这一轮就作废（表里 7 轮全部成立）。

| 轮次 | 变异（就写在目标类名的文件里） | 目标文件 | md5 原件 → 变异体 | 结果 | 红了哪条用例 | 为什么红 |
|---|---|---|---|---|---|---|
| **m5v-1** | 给 `HexCell` 加回 `edgeTags` 组件（L2 的第二份存储） | `map/HexCell.java` | `8df1ac9a…` → `5c1b91d1…` | **红**（Failures 1） | `HexCellTest.hasNoConnectivityField:61` | 反射读出组件数是 3、名字多了 `edgeTags`；`hasSize(2)` 与 `containsExactly("terrain","height")` 双双不成立 |
| **m5v-2** | 给 `GameMap` 加回 `gridSize` 死字段 | `map/GameMap.java` | `fc1431f9…` → `b7c09f29…` | **红**（Failures 3） | `componentCountIsExactlyEight:194`、`componentNamesAreFrozenList:200`、`noGridSizeNoHexOrientation:206` | 组件数变 9；冻结的组件名清单对不上；组件名里出现了 `gridSize` |
| **m5v-3** | 保序拷贝改用 `Map.copyOf`（brief 第 3 行的**等价实际落点**） | `map/GameMap.java` | `fc1431f9…` → `829f552f…` | **红**（Failures 2） | `mapsAreInsertionOrdered:229`、`withMethodsPreserveOtherComponents:399→assertOnlyComponentChanged:524` | `Map.copyOf` 把 7 个 map 的迭代序按哈希散开，落盘序与冻结的插入序字面量不符；第二条是"改 hexes 时不该重排 cities 的键序" |
| **m5v-4** | `empty()` 里改用 `Map.copyOf`（brief 第 3 行的**字面落点**） | `map/GameMap.java` | `fc1431f9…` → `a7053a7a…` | **绿**（0 失败，`BUILD SUCCESS`） | — | **预期绿**：`empty()` 的 7 个 map 全是空的，空 map 只有一个迭代序；with 链还会经构造器重新包装。⇒ 这条落点**不可观测**，等价落点见 m5v-3 |
| **m5v-5** | `withHexes` 里顺手把 `regions` 的第一项丢掉 | `map/GameMap.java` | `fc1431f9…` → `10318464…` | **红**（Failures 1） | `withMethodsPreserveOtherComponents:399→assertOnlyComponentChanged:520`：`[改 hexes 时不该动 regions]` | 只许动一个组件：其余 7 个必须逐项 equals 原值，被改掉的那个组件当场被逮住 |
| **m5v-6** | `withRegions` 里把每个 `Region` 的 `boundary` 抹成空环 | `map/GameMap.java` | `fc1431f9…` → `48680c31…` | **红**（**Errors 2**） | `regionsCarryTheirBoundary:445`、`withMethodsPreserveOtherComponents:402` | ★ **红来自 `Region` 的紧凑构造器**：`IllegalArgumentException: boundary 与 hexes 不一致：hexes 重算得 RegionBoundary[rings=[[…]]]，传入的是 RegionBoundary[rings=[]]`。即"从 Map 这一侧塞进不一致的 `Region`"也被拦住 —— U2 的钉子穿到了 `GameMap` 层（控制器裁定第 4 条要求的正是这一条） |
| **m5v-7** | `City.props` 改用 `Map.copyOf`（控制器给的第四轮：`City` 支） | `map/City.java` | `b9a6fe30…` → `019be67e…` | **红**（Failures 1） | `CityTest.propsPreservesInsertionOrder:40` | props 的迭代序被散开，与冻结的 6 键插入序字面量不符（**这条第一次跑是绿的**，见 §四 —— 加宽夹具后复跑才红） |

**brief 第 5 步五行 → 本表的对照**（一行不落）：

| brief 行 | 我的轮次 | 实测 |
|---|---|---|
| 给 `HexCell` 加回一个 `edgeTags` 组件 | m5v-1 | 红 ✓ |
| 给 `GameMap` 加回 `gridSize` | m5v-2 | 红 ✓ |
| `empty()` 里改用 `Map.copyOf` | m5v-4（字面）+ **m5v-3（等价落点）** | 绿（不可观测）／红 ✓ |
| `withHexes` 里顺手把 `regions` 也改了 | m5v-5 | 红 ✓ |
| `withRegions` 里把 `boundary` 抹成空环 | m5v-6 | 红（**Errors，异常来自 `Region` 构造器**）✓ |
| （控制器补充的第四轮，`City` 支） | m5v-7 | 红 ✓ |

### 三点说明（都来自实测，不是猜测）

1. **brief 第 3 行的字面落点是不可观测的**：`empty()` 的 7 个 map **全是空的**，空 map 只有一个迭代序，
   而且 with 链还会经构造器重新包装。故 **m5v-4（字面落点）实测绿**，我另跑了 **m5v-3（等价的实际落点**：
   把保序拷贝换成 `Map.copyOf`）**实测红**。两轮都在表里，绿的也留着 —— 它证明的是"这条变异在这个位置本来就观测不到"，
   不是"钉子没响"。
2. **m5v-6 的红来自 `Region` 的紧凑构造器**（控制器补充裁定第 4 条要求写明）：报错是
   `IllegalArgumentException: boundary 与 hexes 不一致：hexes 重算得 RegionBoundary[rings=[[…]]]，传入的是 RegionBoundary[rings=[]]`
   —— 从 **Map 这一侧**塞进去的不一致 `Region` 也被拦住，U2 的钉子穿到了 `GameMap` 层。
3. **m5v-3 红了 2 条用例**：除 `mapsAreInsertionOrdered`，`withMethodsPreserveOtherComponents` 的
   "改 hexes 时不该重排 cities 的键序"也响了（每次构造都要重新包装全部 7 个 map，`Map.copyOf` 会把它们一起散掉）。
   这是额外的判别力，不是噪声。

---

## 四 保序钉子：**键数不够时它会假绿**（本轮最值得记的一条）

brief 与控制器补充裁定第 1 条要求：保序必须用**冻结的插入序字面量**钉，且**不许**写成"跟源 map 比"
—— 理由是 `Map.copyOf` 的迭代序**按 JVM 加盐**（`ImmutableCollections` 的 SALT），同一份数据在不同 JVM 上顺序不同。

**实测把这句警告量出来了**（装置：`task-5-evidence/order-probe/`，30 次独立 JVM 启动，每次采一个 SALT 样本；
样本原文 `order-probe/samples-30.txt`）：

| 键集 | 3 键：`Map.copyOf` **恰好落回插入序**（= 变异体打不响） | 4 键 | 6 键 |
|---|---|---|---|
| `City.props`（String） | **10/30（33%）**（第一次探测 13/30 = 43%） | — | **0/30** |
| `hexes`（HexCoord） | 2/30 | **0/30** | 0/30 |
| `regions`（RegionId） | 2/30 | **0/30** | 0/30 |
| `cities`（CityId） | 3/30 | **0/30** | 0/30 |
| `terrainTypes`（String） | **12/30（40%）** | **0/30** | 0/30 |
| `pathways`（PathwayId） | 3/30 | **0/30** | 0/30 |
| `pathwayGroups`（String） | 6/30 | **0/30** | 0/30 |
| `edges`（EdgeRef） | 3/30 | **0/30** | 0/30 |

⇒ **这是实测**：最初 `CityTest` 的 props 只有 3 个键、`GameMapTest` 每表 3 键，
so m5v-7 第一次跑**假绿**（`Map.copyOf` 恰好落回插入序，`propsPreservesInsertionOrder` 不响）。
**修法**：City 的 props 夹具加到 6 键、GameMap 每表加到 4 键（都实测 0/30）—— 钉子仍是"冻结字面量 + `containsExactly`"，
只是**键数够到能响**。改后 m5v-7 与 m5v-3 都实测红。

★ 残留（实测所能给的上限）：30 次采样里 0 次命中，按 rule of three，真值 < 10%（95% 置信）。
**任何在单个 JVM 内做的行为断言都无法把"按 JVM 加盐的顺序"钉成绝对**，这条做到了 0/30 这个量级，到此为止。

---

## 五 我未能验证的

1. **变异轮不跑 SpotBugs**：实验室跑的是 `mvn test`（SpotBugs 在 `verify` 阶段），故 7 轮**都不覆盖门禁三段**；
   门禁是在**未变异**的源码上单独跑干净的（§二）。想让"变异体也会被 SpotBugs 抓"成为实测，需要另一套装置，不在本任务。
2. **`GenerationSpec` 没有变异轮**：控制器给的第四轮是"`GenerationSpec.java` **或** `City.java`"，我选了 `City`
   （它有真守卫：null 校验、保序、不可变，变异有东西可打）。`GenerationSpec` 是 4 行骨架，
   它的用例（`generationSpecDefaultsCarryTheSeed`）**没有**变异自证。⇒ 这条护栏目前是"装饰候选"。
3. **`CityId` / `City` 的其他守卫没有变异自证**：m5v-7 只打了 `City.props` 的保序；
   `CityId` 的三件套（裸值 `toString` / `parse`）与 `City` 的 null 校验只有用例、没有故意违规的变异轮。
4. **Task 6/7 能不能如设计消费 `GameMap`**：本任务只保证"组件清单、名与序被冻结字面量钉住"。
5. **JSON 逐字节往返、同 seed 复现**（L7 的另一半）不在本任务，未验证。
6. **顺序钉子的跨机行为**：本机（WSL2 / 本 JDK）实测如上；换 JVM/机器后 SALT 分布只是不同样本，
   不改变结论（假绿率是概率，不是常量），但我没有第二台机器可测。

---

## 六 顾虑（**推导**与**实测**分开）

### 实测

- **C-实测-1｜SpotBugs 与"藏进 helper 的冻结"**：见 §二。修法与差量都是当场跑的；
  只有"为什么"（它只认调用点看得见的 `unmodifiableMap`）是推导。
- **C-实测-2｜保序钉子的判别力取决于键数**：见 §四。**m5v-7 第一次跑假绿是本任务实际发生过的**，
  不是假设的风险。已按测量把夹具加宽。
- **C-实测-3｜brief 第 3 行的字面落点不可观测**：见 §三-1。

### 推导

- **C1｜`City.region` 可为 null 是我定的口径**。brief 只写"构造期校验 + 保序不可变"，
  没说 `region` 能不能为 null。"不在任何区域内"是合法状态（区域是画出来的，格可以没有归属），
  与 `RegionIndex.regionOf` 返回 null 同口径 —— 但这条**没有出处**，请控制器确认或推翻。
- **C2｜`CityTest.java` 超出了 brief 的文件清单**。brief 的 Test 只列了 `GameMapTest`、`HexCellTest`；
  但控制器补充裁定第 1 条要求 `City.props` 是"保序不可变"且 `mapsAreInsertionOrdered` 必须用冻结字面量钉，
  这条钉子**需要一个文件落脚**（`City.props` 是 `City` 自己的不变量，塞进 `GameMapTest` 名不正）。
- **C3｜`spec` 在 §7.3 与 §7.4 之间看着有张力，但已被 §九覆盖**：spec 的 §7.3 说"反射枚举 `GameMap`
  **全部** record 组件"逐组件制造差异并断言 `cs` 不是 `Unchanged`"，而 §7.4 说 `GenerationSpec` **不进变更集**
  —— `spec` 恰恰是 8 个组件之一。**这不是缺口**：§九（spec 第 660-661 行）已明确要求**豁免集 `Set.of("spec")`**，
  且"往豁免集里再加一个名字 ⇒ 必须红"。⇒ 留给 Task 6/7 的动作是：**实现那个豁免集，并带上它自己的 G13 守卫**。
  本任务只是把 `spec` 做成第 8 个组件并把序钉死，故这里提醒一句，不擅自替 Task 6 决定。
- **C4｜`empty()` 用 `Map.of()`**：brief 说"所有 Map 都用保序不可变包装"；我写的是 `Map.of()`（本身不可变、空表无顺序），
  构造器随后仍会过一遍 `copyOf` + `unmodifiableMap`。空表看不出差别（m5v-4 实测绿），
  故这属于写法选择，不是行为差异。

---

## 七 证据清单（`.superpowers/sdd/2026-09-16-map-simos-plan/task-5-evidence/`）

| 文件 | 是什么 |
|---|---|
| `run.sh` | 变异装置（Task 4 逐字继承 + 本轮加严的两条：每轮清 `surefire-reports`、断言 simos-map 用例真跑过） |
| `mutate.py` | 7 条变异（多步变异：m5v-1/m5v-2 补兼容构造器免得红变编译错误；m5v-7 先摘 import 免得 checkstyle 先炸） |
| `rounds/m5v-*.kept` | 每轮的实测留痕（md5 原/变、自证、编译错误计数、跑过的测试类数、失败用例与断言消息） |
| `rounds/run-all.log` | 整轮原始日志（含每轮 `Tests run` 与 `BUILD SUCCESS/FAILURE`） |
| `rounds/manifest-{main,test}.txt` | 干净世界的逐文件 md5 清单（22 主源 + 15 测试） |
| `gate-clean-verify.txt` | **最终门禁原始输出（干净）** |
| `gate-clean-verify-FAILED-before-fix.txt` | 第一次门禁的原始输出（7 条 EI_EXPOSE_REP，§二的由来） |
| `order-probe/OrderProbe.java` + `samples-30.txt` | 保序假绿率的量测程序与 30 个样本原文（§四的表就是它数出来的） |

---

## 提交

- 实现：`16140002c59c1236f6d7b105139750b797647948`
  `feat(map): GameMap——8 组件状态，删死字段与缓存，加海拔与生成参数`（8 个文件，+1057 行）
- 本报告与证据：紧随其后的 `docs(sdd)` 提交（`.superpowers/sdd/**` 被 gitignore，用 `git add -f` 显式入库）。

**本次提交后仍未关账的事**（留给控制器）：`GenerationSpec` 与 `CityId`/`City` 的其余守卫尚无变异自证（§五-2/3）、
`City.region` 可为 null 的口径待确认（§六 C1）、`CityTest.java` 是超出 brief 文件清单的新增（§六 C2）。
