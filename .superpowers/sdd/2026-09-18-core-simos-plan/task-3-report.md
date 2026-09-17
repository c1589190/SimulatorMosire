# Task 3 报告 —— JSON 地基（★ 最高风险）

> 执行者：Task 3 实现者 · 2026-09-18 · worktree `.claude/worktrees/b1` · 分支 `m4/b1`
> 本报告里每个 Expected 都有当日跑过的痕迹；"实测"与"推断"严格分家，分不开的地方进「我未能核实的」。

## 〇、门禁输出（第 0 步硬门禁）

```
$ git log --oneline -1
b43d115 docs(m4): M4 计划/ spec / SDD 台账 入库 + CLAUDE.md 两处工具陷阱修正
```

与计划要求的 `b43d115` 一致，门禁通过后才动第一行代码。

## 一、交付物与提交

| 提交 | 内容 |
|---|---|
| `e874542` | `simos-util/.../util/json/SimosObjectMapper.java`（单点装配，`create(Module... extraModules)`，裁定 16）+ `simos-util/.../util/state/FieldDelta.java` 的 Jackson 类型信息（裁定 4）+ `SimosObjectMapperTest`（4 条）+ `FieldDeltaJsonTest`（5 条） |
| `7ca7109` | `simos-map/.../codec/MapCodec.java`（namespace=map）+ `MapCodecTest`（6 条） |
| `0c98c2a` | `simos-social/.../codec/SocialCodec.java`（namespace=social）+ `simos-unit/.../codec/UnitCodec.java`（namespace=unit）+ 各自 6 条测试 |

共 3 个提交、11 个文件、全在授权边界内；未碰任何 `pom.xml`、`simos-core/src/main/**`、`docs/**`、`CLAUDE.md`、台账 `progress.md`。未 push、未切分支、未 merge、未 `mvn install`。

## 二、实测命令汇总（关账口径）

| 命令 | 实测结果 |
|---|---|
| `./mvnw -pl simos-util,simos-map,simos-social,simos-unit -am verify` | **BUILD SUCCESS**（rc=0）；用例合计 **532**（util 168 / map 254 / social 36 / unit 74），Failures 0 / Errors 0；**`BugInstance size is 0` × 4**（四个模块各一条）；**ERROR 0 条**；WARNING 1 条（根聚合模块的 `No files found to generate report on`，M0 起既有，无代码可扫） |
| `-Dtest=SimosObjectMapperTest` / `FieldDeltaJsonTest` | 4 + 5 条全绿 |
| `-Dtest=MapCodecTest` / `SocialCodecTest` / `UnitCodecTest` | 各 6 条全绿 |

每条 Bash 都按环境纪律先 `export JAVA_HOME/PATH`；迭代全程用定点命令（`-pl <模块> -am -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false`），没跑过全 reactor 的 `clean verify`（2 核机器，控制器指示）。

覆盖面（与计划的验收对照）：
- **快照往返**：三个模块各带历注 + 无历注两向，全部 `equals` 断言（裁定 12：不做字节断言）。
- **变更集往返**：`FieldDelta` 四变体（Unchanged/Upsert/Remove/Patch）在三个模块里逐条过线，且各有**前置断言**证明夹具确实落在了四条变体上（防止"夹具根本没造出 Patch"的假绿）。
- **值绑定**：HexCell / PopulationSeries / Unit 在反序列化后仍是本类型、不是 `Map`。
- **apply（C28）**：新快照的 ref/timestamp 来自 `newMeta`、base 原样不动——三个模块各一条。
- **键类型**：map 注册 5 个（HexCoord/RegionId/CityId/PathwayId/EdgeRef）、social 注册 HexCoord、unit 只注册 UnitId（HexCoord 在 unit 树里只当**值**不当键，见取代说明 5）。

## 三、裁定 4 的决策点：注解 vs mixin —— **选注解**，两形都实测过

| 形态 | 实测 | 判定 |
|---|---|---|
| **mixin**（mapper 侧 `addMixIn`） | 机械上**能跑通**（草稿层级实测 `equals=true`）；但**静默失败面**实测在案：裸 `new ObjectMapper()` 读同一份 JSON 死于 `Cannot construct instance ... (no Creators, like default constructor, exist): abstract types either need ... additional type information`——**没有任何编译期或装配期信号**提醒"这台 mapper 少配了 mixin"。Task 7/8 的 Core 侧若有人自建 mapper 读 checkpoint，踩中的就是这个坑 | 否（作为 FieldDelta 的载体） |
| **注解**（`@JsonTypeInfo` + `@JsonSubTypes` 写在 `FieldDelta` 上） | 同一轮实测四变体 `equals=true`；wire 形态与 mixin 完全一致（`"@class":"upsert"` 等） | **取** |

理由（写入 FieldDelta 的 Javadoc）：
1. **线格式是类型自己的契约**。`FieldDelta` 是密封接口、四变体封闭，"JSON 里要有 `@class`"这件事跟着类型走， mapper 怎么造都拆不掉——探针实测过的"裸 mapper 静默炸"正是 mixin 形态的结构性风险。
2. **纯度代价为零**。`FieldDelta` 本来就住在 simos-util，而 util 的唯一允许依赖就是 Jackson；**领域类型（Map/Social/Unit 的 record）全程零 Jackson 注解**——mixin 的正当用途（给够不着的第三方类型补注解）在这里不存在。
3. m3 变异轮进一步证明注解是**承重墙**：拔掉注解，5 条用例里 4 条当场死（见 §五）。

`@class` 属性名带 `@` 前缀，与四个 record 组件名（`changed`/`unchanged` 等）无碰撞面。

## 四、自证点：Id.CLASS vs Id.NAME —— **选 Id.NAME**，两形都实测过

实测（同一切换、同一夹具、两种配置各跑一轮往返）：

| 配置 | 实测 wire 形态 | 往返 |
|---|---|---|
| `Id.NAME` | `{"delta":{"@class":"upsert","entries":{…}}}` | `equals=true` |
| `Id.CLASS` | `{"delta":{"@class":"io.mosire.simos.util.state.FieldDelta$Upsert","entries":{…}}}` | `equals=true` |

两者都能活，取舍在**演化风险**而非正确性：
1. **Id.CLASS 把持久化线格式耦合到类的全限定名**。改包名、改类名、把 `Upsert` 改名 = 历史存档全部作废；`Id.NAME` 的 `upsert/remove/patch/unchanged` 是**语义名**，重构不伤存档。
2. **Id.CLASS 是任意类实例化面**：线格式里出现什么 FQCN，Jackson 就尝试实例化什么；`Id.NAME` 的子类型集是**封闭枚举**，没登记的名字直接拒。
3. 本项目铁律 2 的存档要活几十年，线格式稳定性优先。

决定记入 FieldDelta Javadoc。

## 五、变异自证（五形态，7 轮）

纪律执行：每个变异体先落盘到 `task-3-evidence/mutants/`（md5 与原件**字节不同**，diff 留档）、按**目标类名**拷入、每轮开跑前原件 md5 对表、跑后恢复原件并重编验证 md5 相同（7 轮全部干净收场）；每轮强制断言 `grep -c "COMPILATION ERROR"`。原件存 `task-3-evidence/orig/`。

| 轮 | 变异体（目标类名拷入） | 红线（实测消息） | COMPILATION ERROR | 判定 |
|---|---|---|---|---|
| m1 | `SimosObjectMapper` 拔 `new Jdk8Module()` | `MapCodecTest` 两条快照往返**序列化期**死：`Java 8 optional type Optional<String> not supported by default ... MapSnapshot["timestamp"]->SimosTimestamp["calendarLabel"]` | 0 | 红 ✓（红的正是被保护行：没注册 Jdk8Module 则 Optional 直接写不出） |
| m1b | `MapCodec` 拔 HexCoord 键解串器 | `MapCodecTest` 两条快照往返**反序列化期**死：`Cannot find a (Map) Key deserializer for type ... HexCoord`；同轮 `SocialCodecTest` 6/6 **绿**——map 单独变异不影响 social（每家自注册键解串器，裁定 16 的行为面直证） | 0 | 红 ✓ |
| m2 | 计划原稿：`apply` 拔 `(MapChangeSet)` cast | 无红线——**编译不过**：`ChangeSet cannot be converted to MapChangeSet`（MapCodec.java:127） | **1** | **当场作废**（纪律：计数 ≠ 0 即作废）。这轮的结论本身有价值：cast 被类型系统守着，不存在静默漂移形态 |
| m2r | m2 的运行期替补：`apply` 无视变更集（`next = mapBase.map()`） | `MapCodecTest.applyProducesNewSnapshotStampedWithNewMeta:123` 红（`applied.map()` ≠ `MapChangeSet.apply(cs, base.map())`）；**其余 5 条全绿** ⇒ 往返用例守不住 apply，专门用例是必需品 | 0 | 红 ✓ |
| m2b | `apply` 用 base 的 ref/timestamp 盖戳（不打 `newMeta`） | 同一用例 **121 行**红（`ref()`/`timestamp()` ≠ newMeta 的）——C28 的两半各有独立断言线，两轮各杀其一半 | 0 | 红 ✓ |
| m4 | `MapCodec` 拔 `addMixIn(MapChangeSet, …Mixin)` | 两条变更集往返**反序列化期**死：`Unrecognized field "empty" ... (7 known properties: "terrainTypes", "edges", ...)`——开发期实测故障的变异级重现 | 0 | 红 ✓（mixin 是承重墙；严格读入把"静默写脏存档"变成"当场响"） |
| m3 | `FieldDelta` 拔 `@JsonTypeInfo`/`@JsonSubTypes` | `FieldDeltaJsonTest` 5 条中 3 失败 + 2 错误，全部**反序列化期**死：`Cannot construct instance of FieldDelta (no Creators, like default constructor, exist): abstract types either need ... additional type information` | 0 | 红 ✓（注解是承重墙） |

**存活项：0**（m2 是作废轮、不是存活——没有跑出三向全绿的形态）。变异产物与 md5 存 `task-3-evidence/`。

为什么红的核对都做了：m1/m1b/m4/m3 的死法消息都点名被拔的那一行机制本身；m2r/m2b 红在 C28 用例的两个不同断言行、其余用例绿——红因不是"编译挂了"也不是"夹具塌了"。

## 六、「我未能核实的」清单（如实，非空）

1. **mixin 形态没有在真实的泛型 `FieldDelta` 上实测过**——草稿实测是在自包含的非泛型层级上做的；"mixin 也能套在 sealed 泛型接口上"是外推，不是实测。已选注解，此路不通对本任务无影响，但报告里的对照表第 m1 行之外别引用它。
2. **`@JsonCreator static parse` 与 KeyDeserializer 两条路线的等价性没测**——六个键类型全走了 KeyDeserializer；"用注解构造器也能接 Map 键"是推断的替代品，没跑过。
3. **探针失败 ④（非 null `addition` 的 lambda 序列化成 `{}`）在真实快照树里没有触发路径**——"生产侧 addition 恒为 null"是**读代码**得出的（Unit 树 requireNoEvents、PopulationSeries 的 growth 禁事件、ADDITION 是 static 常量），不是跑出来的。若未来某领域类型带上非 null addition，本任务的往返守卫**不会响**（`equals` 对 lambda 恒等，读代码结论）。
4. **大地图跨 JVM 字节决定论没测**——`Region.hexes` 的 `Set.copyOf` 漂移概率未量化；裁定 11 里"就算不抛也打不中靶"的部分是从 M2 Task 5 的键数实测**外推**的。
5. **C26 不透明载荷的消费端未验证**——这段 JSON 嵌进 checkpoint/重放（Task 7/8）后是否真的吃得下，本任务只证到"编码产物是合法 JSON 文本、能被同构 mapper 读回"。
6. **关账 verify 的告警明细没有逐条看过**——只核了汇总口径（`BugInstance size is 0` ×4、ERROR 0 / WARNING 1）；没有展开读每个模块的 SpotBugs/Checkstyle 报告原文。
7. **`-pl` 不带 `-am` 的失败点没展开**——清理现场时误发过一次无 `-am` 的定点命令，实测 BUILD FAILURE（与裁定 6 方向一致）；但 `~/.m2` 里 simos-util 快照缺失/陈旧的具体形态没有进一步核实，当即改回带 `-am` 重跑通过。

## 七、取代说明（计划 vs 实测冲突，全按实测走）

1. **计划的 m2 变异（拔 cast）编译不过 ⇒ 作废**，替补运行期变异 m2r（+ 自加的 m2b）。实测结论：cast 由类型系统守住，比测试更早一步。
2. **计划预测的 m3 红线消息是 `missing type id property '@class'`；实测是 `Cannot construct instance ... (no Creators ...)`**。前者属于 `activateDefaultTyping` 形态的死法（探针实测过它修不了 final record）；注解形态的 `Id.NAME` 拔掉后的死法是"抽象类型没.Creator"。红线本身成立，消息以实测为准。
3. **计划没预见的 `isEmpty()` 线格式污染**：变更集往返首跑即炸（`Unrecognized field "empty"`）——探针只往返过**快照**、没往返过**变更集**。修复是三个 codec 各自给自家 ChangeSet 挂 `@JsonIgnore isEmpty()` mixin（领域类型保持零注解），m4 变异轮证明它承重。此项同时是裁定 12"严格不关"的价值直证：宽松配置会把这个静默吞掉。
4. **计划 Step 6 的日志配置文件：判定不需要**。pom 侧依赖已由控制器提前就位；codec 不打日志（编码失败以 `IllegalStateException` 出面）；日志接线是 Core 侧后续任务的事。未新增任何 `log4j2` 配置文件。
5. **unit 模块只注册 UnitId 键解串器**。若按"每个模块都把用到的类型注册全"的字面理解去给 unit 挂 HexCoord，是**凭空的注册**（HexCoord 在 unit 树里只作为值出现在 `position`/`route`，从不做 Map 键）——与 map 侧 `terrainTypes` 键不注册的 R-48-j 同一纪律。
6. **执行序调整**：控制器中途追加"增量提交"硬要求（每绿一个里程碑就提交），故落成 `e874542`→`7ca7109`→`0c98c2a` 三个提交而非计划草案的单提交；2 核机器上全程定点命令，未跑计划草案里的全量 `clean verify`，关账改跑四模块子 reactor 的 `verify`（口径见 §二）。
