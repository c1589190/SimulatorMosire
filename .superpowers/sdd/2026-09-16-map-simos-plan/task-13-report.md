# Task 13 报告 —— MapResolver（map: 命名空间寻址）

**状态：DONE**（6/6 变异轮全部红在声明靶子上，`./mvnw verify` rc=0、`[ERROR]` 行数 0）

## 交付物

| 文件 | 形态 |
|---|---|
| `simos-map/src/main/java/io/mosire/simos/map/MapSnapshot.java` | 新建，`record MapSnapshot(StateRef, SimosTimestamp, GameMap) implements Snapshot`，三组件逐个 null 校验（显式 if + IAE，City/GameMap 形制），`namespace()` 返回 `"map"` |
| `simos-map/src/main/java/io/mosire/simos/map/resolve/MapResolver.java` | 新建，`public final class` 实现 `Resolver`；静态查询入口 `regionOfHex(GameMap, HexCoord) -> Optional<RegionId>`（R-13-i） |
| `simos-map/src/test/java/io/mosire/simos/map/resolve/MapResolverTest.java` | 新建，**15 条用例**（条数口径见 Concerns §4），夹具为真实 `SimulationState`（不 Mockito） |

证据：`task-13-evidence/rounds/m13v-{1..6}.kept` + `task-13-evidence/gate-clean-verify.txt`（436 行完整日志）。

## brief Step 1 要求的「第 2 段判定结论」原文

> **`map:<mapId>` 的 mapId 段是 `Entity`，且 kind 缺省——`Entity(∅, mapId)`。**
> 依据 M1 spec §3.2 段类型表：「第 2 段 | 裸词 | **Entity，kind 缺省**（`Map1`、`U`）」，以及同节「**第 2 段永远是这个命名空间的"根主体"**（总纲 §4.4 的整张地图 `map:Map1`……都落在这一位）」。它**不是** Property（第 ≥3 段的裸词才判 Property，§3.2 表末行）、**不是** Index（`[...]` 表面形式才判）。
> 本机实测（JDK 21 单文件探针，`simos-util/target/classes`）：`Address.parse("map:m1")` 的第 2 段 `2nd segment class=Entity kind=Optional.empty`。

## supplement §0 的逐条复核（当场跑过，非转抄）

单文件探针（`java -cp simos-util/target/classes Probe.java`）逐条复现了控制器 §0 的表，全部一致：

```
map:m1:hex:0_0  -> [Ns(map), Entity(∅,m1), Property(hex), Property(0_0)]   ★ 两个 Property，不是 hex
map:m1:hex.0_0  -> [Ns(map), Entity(∅,m1), Entity(hex,0_0)]                ✓
map:m1:region.r1 / map:m1:city.c1                                          ✓ 同上（Entity(kind,name)）
map:m1         -> [Ns(map), Entity(∅,m1)]                                  ✓
map:m1:[0,0]   -> [Ns(map), Entity(∅,m1), Index([0,0])]                    ✓
map:"Map:1":hex.0_0 -> [Ns(map), Entity(∅,Map:1), Entity(hex,0_0)] canonical=map:"Map:1":hex.0_0  ✓
map:m1:hex.abc -> 解析层不抛（Entity(hex,abc)），IAE 由 HexCoord.parse("abc") 在解析器里抛  ✓（R-13-f）
```

另探针确认（未逐条进用例，见 Self-review §3）：`map:m1:hex.04_003`、`map:m1:[+4,003]`、`map:m1:hex.4_3` 三种 Human 写法收敛到**同一个** canonical `map:m1:hex.4_3`、localId `4_3`（`HexCoord.toString()` 的紧形式）。

## 实现的裁定落法（R-13-a~j 逐条）

- **R-13-a** 只认 `kind.name` 点号形式；冒号形式落成两个 Property 段 ⇒ 4 段 ⇒ 空候选。
- **R-13-b** `namespace() != "map"` ⇒ 空候选（在触碰 ctx 之前判，`social:` 地址不要求 map 模块存在）。
- **R-13-c** 第 2 段必须 `Entity` 且 `kind()` 为空；`map:[4,3]`、`map:hex.4_3` ⇒ 空候选。
- **R-13-d** `Entity(hex,·)` 与 `Index` 恰 2 元同走查格；canonical 一律 `hex.<q>_<r>`（`hex.toString()`，非回显输入名）。
- **R-13-e** 其它 kind / Property 段 / 缺 kind Entity / Index 元数≠2 / 段数>3 / 图中不存在 ⇒ 空候选不抛。
- **R-13-f** 唯一名字抛点 = 认领 kind 后的 `HexCoord.parse` / `RegionId.parse` / `CityId.parse` 原样抛。
- **R-13-g** mapId 只回显；canonical 全部经 `new Address(List.of(new Namespace("map"), Entity.of(mapId)[, Entity.of(kind, localId)])).canonical()` 产出，零手拼。
- **R-13-h** `ctx.state().module("map")` 缺席 ⇒ IAE（消息含「没有 map 模块切片」）；在位但非 `MapSnapshot` ⇒ IAE（消息含 `MapSnapshot` 与实际类名）。**判定顺序**：命名空间检查在 `mapOf` 之前，模块检查在形状检查之前——任何被认领的 `map:` 地址都先过装配故障这一关，不存在"某条查询路径静默 miss"。
- **R-13-i** `regionOfHex` 一行委托 `map.regionIndex().regionOf`，`Optional.ofNullable` 包装。
- **R-13-j** 源码无 `java.io` / `java.nio` / `Files.` / `Paths.` / `ProcessBuilder` / `Runtime.getRuntime`。

## 逐条用例（15 条，关键口径与判别力）

| # | 用例 | 关键口径 | 判别力 |
|---|---|---|---|
| 1 | `resolvesHexByAddress` | `map:m1:hex.0_0` ⇒ 1 候选，id=`SubjectId("map.hex","0_0")`、typeName `Hex`、canonical 逐字相等；**且** `HexCoord.parse(localId)` 回到图里真实那格（不只断字符串） | 变异 m13v-5 实测红 |
| 2 | `resolvesIndexFormToTheSameHex` | `map:m1:[0,0]` 的 canonical = `map:m1:hex.0_0`（**不是** `[0,0]`），与 Entity 形式同 id 同 canonical | Human 进、canonical 出（总纲 §4.2） |
| 3 | `resolvesRegionByAddress` | `region.r1` ⇒ `SubjectId("map.region","r1")`、`Region`、canonical 逐字 | 变异 m13v-5 实测红 |
| 4 | `resolvesCityByAddress` | `city.c1` ⇒ `SubjectId("map.city","c1")`、`City`、canonical 逐字 | 变异 m13v-5 实测红 |
| 5 | `resolvesMapItself` | `map:m1` ⇒ `SubjectId("map","m1")`、`Map`、`map:m1` | 变异 m13v-3/5 实测红 |
| 6 | `unknownHexGivesEmptyNotException` | `hex.9_9`/`region.no-such`/`city.no-such` ⇒ 空，**不抛**（空列表 = 没有候选） | 变异 m13v-2 实测红 |
| 7 | `wrongNamespaceIsRejected` | `namespace()=="map"` + `social:Map1:hex.4_3` ⇒ 空候选 | 变异 m13v-3 实测红 |
| 8 | `regionOfHexUsesTheIndex` | 夹具先钉住 `regions` 迭代序 = `[r2, r1]`（插入序与字典序相反），断言 `regionOfHex(H00)` 含 **r1**；线性扫描给 r2 ⇒ 红 | 变异 m13v-1 实测红，红点原文 `Optional[r2] to contain r1` |
| 9 | `malformedHexIndexIsRejected` | `hex.abc` ⇒ IAE 且 **`hasMessage("非法坐标串: abc")`**（HexCoord.parse 原消息，证明不包不吞不改） | 变异 m13v-4 实测红（`Expecting code to raise a throwable`） |
| 10 | `resolverDoesNotDoIO` | 读源码断言 6 个禁串一个不含（surefire 工作目录 = 模块根） | 源码级护栏 |
| 11 | `mapIdIsEchoedIntoCanonicalAddress` | `m1`/`whatever-map`/`地图甲` 三个 mapId，canonical 逐个回显 | 变异 m13v-5 的**主**判别（用了与夹具不同的 mapId） |
| 12 | `quotedMapIdIsCanonicalized` | `map:"Map:1":hex.0_0` ⇒ canonical **带引** | 变异 m13v-6 实测红，红点原文见下表 |
| 13 | `missingMapModuleFailsLoudly` | 无 map 切片 ⇒ IAE（消息含「没有 map 模块切片」）；塞 `AlienSnapshot`（namespace 对得上但类型不对）⇒ IAE（消息含 `MapSnapshot`） | 装配故障 vs 没有候选的分界 |
| 14 | `registeredThroughRegistry` | 注册表 `namespaces()==["map"]`；经注册表与直连的 `QueryResult` **相等**（record 值语义）且候选 id 一致 | 变异 m13v-3 实测红（注册表按 `namespace()` 建键） |
| 15 | `unservedShapesGiveEmptyCandidates` | `terra.Grass`（合法不服务）、`hex:0_0`（brief 冒号形式）、`hex.0_0:height`（4 段）、`[4]`/`[0,0,1]`（元数≠2）、`population`（Property）、`[4,3]`/`hex.4_3`（第 2 段非根主体）⇒ 全部空、不抛 | R-13-e 的落法 |

## 变异表（6 轮，全部红；自证头逐轮齐备）

**共同自证头（每轮 .kept 开头，六轮一致）**：干净世界 = 从工作树 `rsync` 全新副本（排除 `target/`/`.git`/`.serena`/`.superpowers`），逐文件 md5 对照清单（**111 个 .java**，文件数不多不少）→ 改前先绿（**156（util）+ 236（map）全绿**，`COMPILATION ERROR count = 0`）→ 变异体写进**目标类名**文件（`simos-map/.../resolve/MapResolver.java`，不是变异文件名）→ 原件 md5 六轮同为 `d00e0dad9e17b1a6fe1b73b402849509`（且与工作树提交字节一致，已另跑 `md5sum` 核对）、六轮变异体 md5 两两相异 → 「除声明的文件外无文件被改动」OK → 改后 `COMPILATION ERROR count = 0`、**simos-map 23 个测试类真的跑过**。

| 轮 | 变异 | 变异体 md5 | 红点原文（surefire） |
|---|---|---|---|
| m13v-1 | `regionOfHex` 改成遍历 `regions().values()` 线性扫描 | `c7636c33…` | `MapResolverTest.regionOfHexUsesTheIndex:166`：`Expecting actual: Optional[r2] to contain: r1 but did not.` —— 线性扫描沿插入序先撞 r2，索引按字典序先写入者胜给 r1，**被保护的正是"用索引"这条** |
| m13v-2 | 不存在的坐标改成抛 | `54207710…` | `MapResolverTest.unknownHexGivesEmptyNotException:145->resolve:84 » IllegalArgument 未知坐标: 9_9` —— 用例体内收到 IAE（Error 形态的红，见 Concerns §2），红因 = "不抛"被破坏 |
| m13v-3 | `namespace()` 改成 `"mapx"`（不动 `NAMESPACE` 常量） | `42f0c927…` | `registeredThroughRegistry:237`：`Expecting actual: ["mapx"] to contain exactly: ["map"]`；**另红** `wrongNamespaceIsRejected:154`：`expected: "map" but was: "mapx"` —— 两处红同缝（注册表按 `namespace()` 建键 / 测试自证 namespace），声明靶子在列 |
| m13v-4 | catch 掉 `HexCoord.parse` 的 IAE、静默返回空 | `e8271ae1…` | `malformedHexIndexIsRejected:173`：`Expecting code to raise a throwable.` —— 不抛即红，外科 |
| m13v-5 | mapId 硬编码 `"Map1"` | `3a251701…` | **8 处红**，全部同一根因（canonical/localId 嵌 mapId）：`mapIdIsEchoedIntoCanonicalAddress:195`（**声明靶子**，主判别——用了非 m1 的 mapId）：`expected: "map:m1:hex.0_0" but was: "map:Map1:hex.0_0"`；另 7 处为 `resolvesHexByAddress` / `resolvesIndexFormToTheSameHex` / `resolvesRegionByAddress` / `resolvesCityByAddress` / `resolvesMapItself` / `quotedMapIdIsCanonicalized` / `registeredThroughRegistry`（夹具 mapId 是 m1，硬编码后必然一起红——非外科是这条变异的固有属性，已在 mutate.py 模块注释**预先声明**） |
| m13v-6 | canonical 手拼字符串（不做 §3.4 加引） | `cd665703…` | `quotedMapIdIsCanonicalized:205`：`expected: "map:\"Map:1\":hex.0_0" but was: "map:Map:1:hex.0_0"` —— ★ 手拼串**能**通过 `ResolvedSubject` 的往返校验（`map:Map:1:hex.0_0` 再 parse 成 4 段 AST、其 canonical 等于自身），所以红来自断言本身——这正是"canonical 必须走 AST"的判别力所在；裸词 mapId 下手拼与真 canonical 逐字相同，故只有带引用例红，外科 |

**为什么红 / 为什么只有这些红**：六轮的红点全部是**断言层**（12 次 maven 运行 `COMPILATION ERROR` 计数全 0、每轮改后 23 个 map 测试类都跑了）；每轮红点集合与该变异破坏的不变式一一对应，无一轮出现"红在别处"的漂移。

## 门禁实测数字

- `./mvnw -q spotless:apply` ⇒ rc=0。
- `./mvnw verify`（完整日志 `task-13-evidence/gate-clean-verify.txt`，436 行）：**rc=0，`[ERROR]` 行数 0，`BUILD SUCCESS` ×1**，SpotBugs 执行（日志含 35 处 spotbugs 相关行），耗时 02:18。
- simos-map：`Tests run: 236, Failures: 0, Errors: 0`，其中 `MapResolverTest`：`Tests run: 15, Failures: 0, Errors: 0`；simos-core：15/0/0；simos-util：156/0/0。
- 单跑命令（迭代用）：`./mvnw -q -pl simos-map -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MapResolverTest test` ⇒ rc=0。

## Self-review 发现

1. **实现期一处编译错**：`new AlienSnapshot()` 漏了 record 形参，改为 `new AlienSnapshot(REF, TS)`；修后 15/15 绿（这是本轮唯一一次非门禁红灯，发生在用例编写期，非变异轮）。
2. **`mapOf` 的调用顺序是一个设计决定**（命名空间 → 模块 → 形状）：被认领的 `map:` 地址一律先过装配故障检查，连 `terra.Grass` 这类不服务形状也不例外（模块缺失时它们也抛而不是返回空）。已在代码注释写明。
3. **宽松 hex 名的归一只探针钉过、没用例钉**：`hex.04_003` ⇒ canonical `hex.4_3`（探针实测三种写法收敛一致）。`resolvesIndexFormToTheSameHex` 已钉 `[0,0]` 一侧；`04_003` 一侧与之同源（都经 `HexCoord` 构造再 `toString()`），未单列用例。
4. **夹具判别力自证**：`regionOfHexUsesTheIndex` 里先用 `containsExactly(r2, r1)` 钉住"插入序确实相反"——夹具被无意改回字典序时，该用例会先在夹具断言上红，而不是假绿。
5. **`QueryResult` 值等价**（record）使 `registeredThroughRegistry` 能直接断言"经注册表 == 直连"，比逐字段比对更严（多一个候选或顺序不同都会红）。

## Concerns 与挂起项

1. **（挂起，R-13-g 原文）** MapSimos 没有地图身份：`GameMap` 无 id 组件 ⇒ `map:<mapId>` 的 mapId 只回显、不可校验。将来 `GameMap` 有 id 字段时应收紧（届时 canonical 与 `SubjectId("map", mapId)` 的语义随之收窄，下游别假设 mapId 已被校验）。
2. m13v-2 的红是 **Error 形态**（用例体内收到 IAE），surefire 记为 Errors 而非 Failures——装置的 `red_points` 两类都收，报告里以用例名 + 异常原文为准，不误读为"没跑到断言"（同轮 23 类真跑过、`COMPILATION ERROR`=0）。
3. `resolverDoesNotDoIO` 依赖 surefire 工作目录 = 模块根（R-13-j 指定的形态）：从仓库根直接跑单测（不走 surefire 默认 basedir 的场景）会因读不到文件而失败——那是测试装置红，不是解析器越界。
4. **用例条数与派单文字不符**：brief 列 9 条、supplement 标题写"9+4=13"、但 supplement §2 逐条清点是 **15** 条（`resolvesIndexFormToTheSameHex` 与 `unservedShapesGiveEmptyCandidates` 是标题漏数的两条 ★ 新增）。本报告按逐条清点交付 15 条——多出的两条恰是 R-13-d（Index 人类形式）与 R-13-e 的直接靶子。
5. 空 mapId（`Entity(∅, "")`）只能由直接构造 AST 产生（`Address.parse` 不可达），当前会在 `SubjectId` 构造期抛 IAE——不可达路径，未加专门守卫，未写用例。
