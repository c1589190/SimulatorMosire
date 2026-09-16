# Task 13 补充 —— 派单前扫描的裁定（控制器产出）

> 与 `task-13-brief.md` 一起读，**两者冲突处以本文件为准**。完整理由在 `progress.md` 的「Task 13 预扫描」。
> brief 的签名与 9 条用例名成立；本文件补的是**它没说清的三件事**：地址的**真形态**、`GameMap` **从哪来**、几条边界的口径。

## 0. 先纠一个 brief 的坑（已当场实测，不是推断）

brief 写的 `map:<mapId>:hex:<q>_<r>` 是**计划期笔误**。控制器用 `Address.parse` 实测（JDK 21，`simos-util/target/classes`）：

```
map:m1:hex:0_0    -> [Namespace(map), Entity(∅,m1), Property(hex), Property(0_0)]   ★ 两个 Property，不是 hex！
map:m1:hex.0_0    -> [Namespace(map), Entity(∅,m1), Entity(hex,0_0)]                 ✓ 这才是 hex
map:m1:region:r1  -> [Namespace(map), Entity(∅,m1), Property(region), Property(r1)]  ★ 同上
map:m1:region.r1  -> [Namespace(map), Entity(∅,m1), Entity(region,r1)]               ✓
map:m1:city:c1    -> [Namespace(map), Entity(∅,m1), Property(city), Property(c1)]    ★ 同上
map:m1:city.c1    -> [Namespace(map), Entity(∅,m1), Entity(city,c1)]                 ✓
map:m1            -> [Namespace(map), Entity(∅,m1)]                                  ✓
```

依据是 **M1 spec §3.2**（第 ≥3 段的裸词一律判 **Property**；`kind.name` 才是 Entity）与 **§3.6 冻结样例**
（`map:Map1:hex.4_3` = `Entity(hex,4_3)`）。总纲 §4.3 也是同一句：「段间只用 `:`；`.` 只在段内使用」。
⇒ **R-13-a：认 `kind.name`（点号）形式，不认冒号形式**；冒号形式落进 Property 段 ⇒ 见 R-13-e。

## 1. MapResolver 的认领规则（照办）

**R-13-b 命名空间。** `Address.namespace()` ≠ `"map"` ⇒ 返回**空候选**（不抛）。"认领"与否由返回值表达；
未知命名空间抛异常是 `ResolverRegistry` 的职责（`ResolverRegistry:resolve` 已经在做），本解析器不重复。

**R-13-c 根地址 `map:<mapId>`（2 段）。** 第 2 段必须是 `Entity` 且 **kind 缺省**（`Entity.kind()` 为 `Optional.empty()`）
—— 这正是 M1 spec §3.2「第 2 段永远是这个命名空间的**根主体**」与总纲 §4.4 的 `map:Map1` = 整张地图。
候选：`SubjectId("map", mapId)`、`typeName = "Map"`、canonical = `map:<mapId>`（按 §3.4 加引规则渲染）。
第 2 段不是 `Entity(∅,·)`（如 `map:[4,3]`、`map:hex.4_3`）⇒ **空候选**。

**R-13-d 实体地址（3 段）。** 第 3 段按类型分派：

| 第 3 段 | 语义 | 候选 |
|---|---|---|
| `Entity(hex, "<q>_<r>")` | 查格 | `SubjectId("map.hex", "<q>_<r>")`、`typeName="Hex"`、canonical = `map:<mapId>:hex.<q>_<r>` |
| `Index([q,r])`（恰好 2 元） | **人类友好形式**，同查格 | 同上（canonical 一律用 `hex.<q>_<r>` 形态 —— 总纲 §4.2：Human 进、canonical 出） |
| `Entity(region, "<id>")` | 查区域 | `SubjectId("map.region", id)`、`typeName="Region"`、canonical = `map:<mapId>:region.<id>` |
| `Entity(city, "<id>")` | 查城市 | `SubjectId("map.city", id)`、`typeName="City"`、canonical = `map:<mapId>:city.<id>` |
| 其它 | 见 R-13-e | —— |

`SubjectId.namespace` 用 `map.<类型>` 是 M1 spec §四的**原文例子**（「如 `map.hex`、`unit.equipment`」）。

**R-13-e 不算候选的形态 → 空候选**（**不抛**）：其它 kind（`terra.Grass`/`conn.river.*` 是合法地址但 M2 不服务）、
Property 段与裸词（含 brief 那个冒号形式）、缺 kind 的 Entity、Index 元数 ≠ 2、段数 > 3（如 `…:hex.4_3:height` 属性访问）。
口径：**空列表 = 没有候选，不是错误**（`QueryResult` 的原文），而"合法但没人服务"正属于这一类。

**R-13-f 只有一处抛异常**：认领了的 kind 但**名字解析失败** ⇒ 抛 `HexCoord.parse` / `RegionId.parse` **自己的**
`IllegalArgumentException`（不包不吞、不改消息）。`malformedHexIndexIsRejected` 靶子 = `map:m1:hex.abc`（`HexCoord.parse("abc")` 抛）。
★ 别把 `terra.Grass` 写成抛 —— 它是**合法地址**（总纲 §4.4 冻结表里就有），只是 M2 不服务。

**R-13-g mapId 只回显、不校验。** `GameMap` **没有 id 字段**（Task 5 的 8 组件里没有；M2 spec 也没给它定义身份）
⇒ 解析器无从校验 mapId，只能把它**原样回显进 canonical**。★ 不许硬编码 `"Map1"`。
⇒ 新增用例 `mapIdIsEchoedIntoCanonicalAddress`：地址里的 mapId 换成任意串，候选的 canonical 里就是那个串。
（**挂起项**：MapSimos 没有地图身份，`map:<mapId>` 的 mapId 目前不可校验 —— 记台账，将来有 id 字段时收紧。）
★ 新增用例 `quotedMapIdIsCanonicalized`：mapId 含 `:`（如 `Map:1`）时，canonical 必须是**带引号**的
`map:"Map:1":hex.0_0`。⇒ **canonical 一律由 `Address` AST 构造后调 `canonical()` 产出，不许手写字符串拼接**
（§3.4 的四条加引规则不许在这里重实现一遍；手拼的版本会被 `ResolvedSubject` 的构造期校验当场拦下 —— 那也是红，但你要的是**对上**）。

**R-13-h `GameMap` 从哪来（brief 漏掉的关键一环）。** `resolve(address, ctx)` 的图只能从
`ctx.state().module("map")` 拿 —— `SimulationState` **没有**跨模块访问器（铁律 3/4，其 Javadoc 明写）。
而 `Snapshot` 是个**接口**（`ref()`/`timestamp()`/`namespace()`），所以 Task 13 **必须顺带新建**
`simos-map/src/main/java/io/mosire/simos/map/MapSnapshot.java`（总纲 §4.5 行 253 已点名这个类型：
「`MapSnapshot`/`SocialSnapshot`/`UnitSnapshot` 是各自独立的 record 树，只实现 `Snapshot`」）：

```java
public record MapSnapshot(StateRef ref, SimosTimestamp timestamp, GameMap map) implements Snapshot {
  // 构造器：三个组件逐个 null 校验（照 SnapshotProtocol / 既有 record 的形制）
  @Override public String namespace() { return "map"; }   // ★ SimulationState 构造期会校验"键 == namespace()"
}
```

取用：`ctx.state().module("map")` ⇒ `Optional<Snapshot>`；**缺席或不是 `MapSnapshot` ⇒ 抛 `IllegalArgumentException`**
（这是装配故障，不是"没有候选" —— 与注册表对未知命名空间"抛，不兜底"同口径）。
★ 用例：`missingMapModuleFailsLoudly`（state 里没有 map 模块/或塞了别的 Snapshot ⇒ IAE）。

**R-13-i `regionOfHex`（L5 的守卫，brief 只给了名字）。** MapResolver 上加一个静态查询入口：

```java
public static Optional<RegionId> regionOfHex(GameMap map, HexCoord hex)   // 委托 map.regionIndex().regionOf(hex)
```

★ **判别力在哪**：`RegionIndex` 的公开出处写死了重叠区域的裁决 ——「按 `RegionId` 的**字典序**先写入者胜」。
故夹具造**两个重叠区域**、且让 `map.regions()` 的**插入序与字典序相反**（先插 `r2` 后插 `r1`），断言
`regionOfHex` 回到 **`r1`**。线性扫描（`for (Region r : map.regions().values()) if (r.contains(h)) …`）
会回到 `r2` ⇒ **红**。这就是那条变异的靶子：**不是**靠计时，也不靠反射。
（★ 另一条路已被堵死：`RegionIndex(Map)` 的构造器是**包私有**的，`MapResolverTest` 在 `…map.resolve` 包，
进不去 —— 控制器当场读过 `RegionIndex:24`。别去试计数注入。）

**R-13-j 不碰 IO。** `MapResolver` 源码里不得出现 `java.io` / `java.nio` / `Files.` / `Paths.` / `ProcessBuilder` /
`Runtime.getRuntime`。`resolverDoesNotDoIO` 用**读源码**的写法（surefire 的工作目录是模块根 `simos-map/`）：
`Files.readString(Path.of("src/main/java/io/mosire/simos/map/resolve/MapResolver.java"))` 断言上述串一个都不含
（★ 别断言裸词 `File` —— `FieldDelta` 一类名字会被误伤）。

## 2. 用例落法（brief §Step 2 的 9 条 + 新增 4 条）

夹具（`@BeforeEach` 或私有构造方法）：一张小图 + 两个区域 + 一个城市 + `MapSnapshot` + `SimulationState` + `ResolveContext`。
★ **`SimulationState` 直接 new**（`new SimulationState(new StateMeta(ref, ts), Map.of("map", snap), InMemoryInfoSystem.empty())`）
—— 它的构造器会校验"键 == `snapshot.namespace()`"，于是 `MapSnapshot.namespace() == "map"` 顺带被钉住；
**不要**用 Mockito 假状态（那会把这个接缝一起假掉）。要用到的 M1 类型：`StateRef(BranchId, RevisionId)`、
`StateMeta(StateRef, SimosTimestamp)`、`SimosTimestamp.of(long)`、`InMemoryInfoSystem.empty()`、`ResolveContext(SimulationState, SimosTimestamp)`。

- `resolvesHexByAddress`：`map:m1:hex.0_0` ⇒ 1 个候选，id = `SubjectId("map.hex","0_0")`、typeName `"Hex"`、
  canonical 逐字 = `map:m1:hex.0_0`；且 `HexCoord.parse("0_0")` 与该格相等（**别只断字符串**）。
- `resolvesIndexFormToTheSameHex`（★ 新增，R-13-d 的 Index 行）：`map:m1:[0,0]` ⇒ 候选的 canonical 是
  **`hex.0_0` 形态**（不是 `[0,0]`）。这条同时是"Human 进、canonical 出"的护栏。
- `resolvesRegionByAddress` / `resolvesCityByAddress` / `resolvesMapItself`：同形制（map 自身 typeName `"Map"`）。
- `★ unknownHexGivesEmptyNotException`：合法但不存在的坐标 ⇒ `candidates().isEmpty()`，**且不抛**。
- `★ wrongNamespaceIsRejected`：`social:Map1:hex.4_3` ⇒ 空候选（R-13-b）。
- `★ regionOfHexUsesTheIndex`：见 R-13-i（重叠区域 + 反向插入序 ⇒ 回到字典序较小者）。
- `malformedHexIndexIsRejected`：`map:m1:hex.abc` ⇒ IAE。★ **另加** `RegionId` 一侧的：`map:m1:region.` 这种空段在 `Address.parse`
  阶段就抛，不必测；测 `regionOfHex(null, …)` 之类**没意义**，别为不存在的世界写守卫。
- `resolverDoesNotDoIO`：见 R-13-j。
- ★ `mapIdIsEchoedIntoCanonicalAddress`、`quotedMapIdIsCanonicalized`：见 R-13-g。
- ★ `missingMapModuleFailsLoudly`：见 R-13-h。
- ★ `registeredThroughRegistry`：把解析器 `register` 进 `ResolverRegistry`，断言 `namespaces()` = `["map"]`、
  且经注册表 `resolve(Address.parse("map:m1"), ctx)` 拿到的候选与直接调用一致（**纯转发型 SPI 要证明参数被原样转交**）。
- ★ `unservedShapesGiveEmptyCandidates`（R-13-e）：至少覆盖 `map:m1:terra.Grass`（其它 kind）、
  `map:m1:hex:0_0`（Property 段 —— brief 的冒号形式！）两条 ⇒ 都空候选、都不抛。

## 3. 变异表（brief 4 行 + 补充 2 行）

| 变异 | 期望 | 判别的是 |
|---|---|---|
| `regionOfHex` 改成遍历 `regions().values()` 找 | 红 | `regionOfHexUsesTheIndex`（重叠 + 反向插入序：线性扫描给 `r2`，索引给 `r1`） |
| 未知坐标改成抛异常 | 红 | `unknownHexGivesEmptyNotException` |
| `namespace()` 改成 `"mapx"` | 红 | `registeredThroughRegistry`（注册表按 `namespace()` 建键，地址是 `map:` ⇒ 分发不到） |
| 非法 hex 名字静默返回空（catch 掉 `HexCoord.parse` 的 IAE） | 红 | `malformedHexIndexIsRejected` |
| ★ mapId 硬编码成 `"Map1"`（不回显地址里的） | 红 | `mapIdIsEchoedIntoCanonicalAddress` |
| ★ canonical 改成手拼字符串（不做 §3.4 加引） | 红（构造期抛即算红） | `quotedMapIdIsCanonicalized` |

★ 变异纪律照旧：干净世界（`rsync` 排除 `target/`）+ md5 清单 + 改前先绿 + 变异体按**白名单推成目标类名** +
`COMPILATION ERROR` 计数为 0 + **红了要问为什么红**（必须是被保护的那条断言红）。装置照
`task-11-evidence/{run.sh,mutate.py}` 改造成 `/tmp/m13lab` 版 —— 不重建新形态。

## 4. 交付、门禁与提交

- 交付 3 个文件：`MapResolver.java`、`MapSnapshot.java`（新建）+ `MapResolverTest.java`。
- 证据进 `.superpowers/sdd/2026-09-16-map-simos-plan/task-13-evidence/`（`rounds/*.kept` + `gate-clean-verify.txt`）。
- 本机 `grep` 是 ugrep（尊重 ignore、跳隐藏目录）：搜全仓用 `git grep`。
- 中文注释/Javadoc；`./mvnw -q spotless:apply` 再 `./mvnw -q verify`（硬门禁，含 SpotBugs）。
- 提交信息 `feat(map): MapResolver——map: 命名空间寻址`。**绝不 `git add -A`**；**提交到本地分支即可，不要推送**。
- 报告写到 `task-13-report.md`（交付物、逐条用例、**Step 1 要求的"第 2 段判定结论"原文**、变异表含每轮红点、自审发现、挂起项）。
  ★ 报告里的每个 Expected/实测数字都要是**当场跑出来的**。
