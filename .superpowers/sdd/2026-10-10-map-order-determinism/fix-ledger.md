# 责任区 K2 修复账本：**`simos-map` 快照的集合序抖动**（`Region.hexes`）

- 批次：2026-10-10 map 序确定性批（任务书 `task-31`）
- ★ **未能 claim**：`team_task_get task-31` 报 `Error: agent "fbc5eb87-…" is not a member of an active Agent Team` ⇒ 按任务书「不要卡住」继续执行，并在此如实记录（同 K 批 D5）。`task-31` 的 `action=complete` 因此**也未做成**。
- 责任区：让 `simos-map` **进字节的集合**「迭代序 = 内容的纯函数」；**只改序、不改值**；不写/不跑测试、不 commit
- 结论：**定位到 `Region` 紧凑构造器的 `Set.copyOf(hexes)`（1 处根因）⇒ 已修为「自然序 + LinkedHashSet + unmodifiableSet」**；
  - map 段快照字节 **跨 JVM 8/8 不同 → 1/8 唯一**；**checkpoint 信封 8/8 不同 → 1/8 唯一**（逐模块 8 段全部唯一）
  - 30 天真跑：map 也是 **1/8**，`once == roundTrip` **false → true**；其余 7 模块修复前后**逐字节相同**
- 编译：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` → **exit=0**（本轮共 4 次编译，见 §9）
- 改动面：**3 个文件 / +37 −13**（全部在 `simos-map/src/main/**`）：
  `region/Region.java`（根因 + 类注）、`ops/RegionOperations.java`（4 处同族别名去源 + 1 处类注）、`region/RegionBoundary.java`（1 处类注过时前提）

---

## 1. 复现装置（一次性探针，落 `/tmp`，**不进仓库 `src/test`**）

沿用 K 批装置（`task-30`）并**加一处**：`advance` 模式补写末态 checkpoint 信封。装置副本已随本账本入库
（`evidence/`：`ProbeKeyOrder.java`、`cross.sh`、`modules.py`、`canon.py`）。

| 文件 | 作用 |
|---|---|
| `evidence/ProbeKeyOrder.java` | 探针（package `io.mosire.simos.app.time`）。模式：`snap`（同 JVM N 轮 / 每次重建世界后逐模块编码 + checkpoint + economy 变更集）、`locate`、`advance <days>`（真跑日循环 + 末态逐模块 digest + **再序列化两次**）、`worlds`（四个世界创世态） |
| `evidence/cross.sh` | 跨 JVM 驱动：**每次一个新进程**，逐模块 sha256 汇总 |
| `evidence/modules.py` | checkpoint 信封**逐模块** digest（信封里 `modules` 的 value 是 **JSON 文本**（C26），故直接取字符串 sha256，**不 re-serialize**） |
| `evidence/canon.py` | 三种规范化对照：`bytes`（原始）/ `sem`（对象键序无关）/ **`deep`（数组序也无关 = 元素多重集）**；`diff` 逐路径分类 `objKeyOrderPaths / arrayOrderPaths / valuePaths` |

命令（classpath 用 R2 批的 `/tmp/r2probe/cp.txt`：14 个模块 `target/classes` + 依赖）：

```bash
bash evidence/cross.sh <outBase> 8 snap                 # 跨 JVM ×8（创世态）
bash evidence/cross.sh <outBase> 8 advance 30           # 跨 JVM ×8（真跑 30 天，含末态 checkpoint）
cd /tmp/koprobe && java -cp "out:$CP" …ProbeKeyOrder snap <outDir> 6   # 同 JVM ×6
python3 evidence/canon.py diff <dirA>/run0 <dirB>/run0  # 逐路径分类
```

★ 探针**不进 `src/test`**、不改任何生产代码；`cross.sh`/`modules.py`/`canon.py` 均为只读比对工具。

## 2. 第一步先分清：**同 JVM 不抖，只有跨 JVM 抖**（⇒ 是 per-JVM 盐，不是动力学）

| 装置 | 修复前（HEAD `b4bfe988`） | 修复后（最终树） |
|---|---|---|
| **同 JVM 内创世 ×6 轮** | 8 个模块**全部 1/6 唯一**（map `1ea4ecad6a35…`） | 8 个模块**全部 1/6 唯一**（map `40c2442f6ec1…`） |
| **跨 JVM 创世 ×8 进程** | **map = 8/8 个不同 digest**；social/unit/sd/economy/actor/gov/army **各 1/8** | **8 个模块全部 1/8**（map `40c2442f6ec1…`） |
| 同 JVM checkpoint（×6 轮） | 1/6 `1c63ddf6f77f` | 1/6 `600d2e544df2` |

**跨 JVM ×8 修复前 map 的 8 份完整 digest（贴全）**：

```
80a2699e21a1  d0c20cac26ab  def1dc4ace98  f777fb82bda9
3ec70552ead6  d8a50c758b30  a02952753e17  ae5d7a6f9df3      ← 8/8 互不相同（抖动率 100%）
```

**修复后（最终树）map 全 64 hex 位**：

```
40c2442f6ec1c52be97724a3621f54e6ba5b17b3c1b64dca721e6aca124545b3   × 8（1/8）
```

其余 7 模块（修复前后**逐值相同**）：
`social 6f745fa2475c…` / `unit e5f0a69ffdc7…` / `sd 2f7039f12cbe…` / `economy bd0900381ce0…` /
`actor 0edcefed1f6f…` / `gov 13ba793fe453…` / `army 77918c85e631…`

## 3. 定位：序从哪里进来（`file:line` + 输入源）

### 3.1 根因（一行）

```
修复前 simos-map/src/main/java/io/mosire/simos/map/region/Region.java:28
    hexes = Set.copyOf(hexes);
```

`Set.copyOf` 对 **≥2 元素**返回 `ImmutableCollections.SetN`（1~2 元素为 `Set12`），**槽位 = `probe(hashCode, 表长)`**，而
`ImmutableCollections` 的静态 `SALT` 取自 **JVM 启动时的 `nanoTime`** ⇒ 同一份内容在不同进程里迭代序不同。
★ 这是全仓**唯一**的 per-JVM 随机源：`new HashSet`/`new HashMap` 的散列是内容的纯函数（JDK 无盐），`Set.of`/`Map.of` 的
**≥2 键**形态才吃 `SALT`（K 批修的正是 `Map.of` 那一支）。

### 3.2 完整因果链（逐环节读代码 + 实测确认）

1. **`Region.hexes` 是 `Set<HexCoord>`**，紧凑构造器把上游集合打散成散列槽位序（`Region.java:28`）；
2. **`MapCodec.encodeSnapshot`（`MapCodec.java:160-165`）**把整个 `MapSnapshot` 交给 Jackson ⇒ **`Set` 被写成 JSON 数组** ⇒ 迭代序**直接进字节**；同一路径也进 `MapChangeSet`（journal 的 `changeset_json`）与 checkpoint 信封（`CheckpointEncoder.encode` 把模块 JSON **当文本**内嵌，故模块段的字节就是这条路径的产物）；
3. **信封因此被拖累**：实测跨 8 JVM 的 checkpoint 信封 **8/8 不同**，而**逐模块比只有 `map` 一段不同**（其余 7 段逐字节相同）。

### 3.3 输入源（谁喂的 `hexes`）——**上游全都已经是保序集合**

| 输入源 | 现场 | 喂进来的序 |
|---|---|---|
| 创世 world（three-powers） | `simos-app/.../world/ThreePowersWorld.java:594`（`Region.of`）→ `:597` | `new LinkedHashSet<>(hexesOfZone(zone))` |
| 创世 world（small-world） | `simos-app/.../world/SmallWorld.java:442`→`:445`、`:450`→`:453` | `new LinkedHashSet<>(…)` |
| GM 命令（create/update/split/reassign） | `spi/MapPayloads.java:151-165` `requireHexes` | `LinkedHashSet`（**JSON 数组序**） |
| 编辑算子（merge/split/reassign） | `RegionOperations.java:195,284,403,416` | `LinkedHashSet` |
| 反序列化 | `MapCodec.decodeSnapshot` → Jackson 的 `Set` | 保序（**实测**：修复后 `once == roundTrip: true`，若解码丢序则不可能成立） |

⇒ **病不在上游，在 `Region` 这一处"把有序集合打散"的构造**。这一点也解释了为什么 `Set.copyOf` 在
`RegionOperations:206/292/413/418` 与 `Region:28` 两处同族出现（§6.2）。

### 3.4 逐路径定位（修复前 jvm0 ↔ jvm1，`canon.py diff`）

```
DIFF map      objKeyOrderPaths=0 arrayOrderPaths=3 valuePaths=0
   ARRORDER $.map.regions.province-silver.hexes  len=9  元素多重集相同、次序不同
   ARRORDER $.map.regions.province-copper.hexes  len=16 元素多重集相同、次序不同
   ARRORDER $.map.regions.province-gold.hexes    len=12 元素多重集相同、次序不同
DIFF social / unit / sd / economy / actor / gov / army：objKeyOrderPaths=0 arrayOrderPaths=0 valuePaths=0
```

★ **只有 3 条路径、全是数组序、元素多重集相同**（K 批记的 `valuePaths=58` 是"按位置比较"的假象；本批的 `canon.py`
在"多重集相同"时**不再按位置递归**，故 `valuePaths=0` 是干净口径）。
★ 关键旁证：修复前的 map **`deep`（数组序不敏感）digest 也恒为 `219fea7eeea2`** ⇒ 抖的只是序，不是集合成员。

## 4. 修法与口径（为什么是"自然序"）

```java
// Region.java:46-49（紧凑构造器内，赋值处）
// ★ 自然序 + LinkedHashSet：迭代序（因而落盘字节）只由集合内容决定，跨 JVM 稳（与 TerrainBlock 同形制）。
List<HexCoord> sorted = new ArrayList<>(hexes);
sorted.sort(Comparator.naturalOrder());
hexes = Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
```

**四条理由**（为什么"自然序"而不是"保留上游插入序"）：

1. **任务口径的字面要求**：`迭代序 = 内容的纯函数`。"保留插入序"只保证"序 = 上游序"，而上游有 4 处 `Set.copyOf`、
   将来还可能有新生产者 ⇒ 弱保证；自然序**无条件**是内容的纯函数（集合内容不含序）。
2. **本仓既有口径的先例**，且是**同模块同形制**的近亲：`block/TerrainBlock.java:50-53` 就是"自然序 + LinkedHashSet +
   `Collections.unmodifiableSet`"，类注原文即"迭代序（因而 toString 的字节）只由集合内容决定，跨 JVM 稳"；
   `generate/MapGenerator.java:147` 的 R-10-h 也已用"自然序"破解 `HexGrid.withinRadius` 的 `Set.copyOf`。
   本批是**把这条纪律补到 `Region` 上**，不是新立规矩。
3. **集合语义一字未动**：对外仍是 `Set`，`contains` / `equals`（逐元素判等）/ `hashCode` 语义照旧；
   `Region.equals` 逐组件比较**没有被碰**；`RegionBoundary.of` 仍是纯函数。
4. **旧档会收敛**：`Set.copyOf` 写的存档（散列序）在**下次重存**时即被规范化 ⇒ 不在存量里留永久抖动。

★ **没有在序列化层"排序后输出"**：排序发生在**紧凑构造器**（内存态），故**内存态 = 落盘态**；`MapCodec` **一行未改**。
★ **改的是序的来源，不是序的用途**：`RegionPart` 的载荷序、`Pathway.edges` 的链走向序、`TerrainCatalog` 的声明序**都没动**。

## 5. ★ "只改序不改值"的证据（同装置同次数，修复前 ↔ 修复后）

| 字节流 | 修复前 | 修复后 | 结论 |
|---|---|---|---|
| **map 快照（创世）跨 JVM ×8** | **8/8 不同** | **1/8** `40c2442f6ec1` | 抖 → 唯一 |
| **map 快照（真跑 30 天）跨 JVM ×8** | 非唯一（见 K 批 5/6） | **1/8** `40c2442f6ec1` | 与是否创世态无关 |
| **checkpoint 信封（创世）跨 JVM ×8** | **8/8 不同**（`5555507cceea / 685534fe7c0c / 8d789f2081c3 / a15809cbdfe8 / a618baf96c3f / b3955f71f796 / cf32003e992f / fe05cb17308e`） | **1/8**（8 份**同一串** `600d2e544df2dfc2…`） | 由 map 拖累 → 唯一 |
| **checkpoint 逐模块（创世）** | map 8/8，其余 7 段各 1/8 | **8 段全部 1/8** | 逐模块逐一唯一 |
| **checkpoint 信封（30 天）跨 JVM ×8** | 未测（`advance` 当时不写信封） | **1/8**（8 份同一串 `59796d60462ffe28…`）+ 逐模块 8 段全部 1/8 | 长跑后同样唯一 |
| **`once == roundTrip`（30 天）** | map **false** | map **true**（8/8 JVM 全 true；`once == twice` 两轮全 true） | 往返成立 |
| **其余 7 模块（创世 + 30 天）** | 各 1/8 | 各 1/8，且与修复前**逐字节相同** | 未被影响 |
| **四世界创世 ×4 JVM** | 未测（K 批记 map 三个世界不唯一） | 8 模块**全部 1/4**；map：three-powers `40c2442f6ec1` / small-world `a868cf1d26e3` / corridor `75d53956f5f0` / rich `488c1b0e9297` | 不是 three-powers 独有 |

**"只改序"的四条正面证据**：

1. **逐路径分类**（修复前 jvm0 ↔ 修复后 jvm0）：`map objKeyOrderPaths=0 arrayOrderPaths=3 valuePaths=0`；其余 7 模块
   `bytesEqual=True`（`canon.py sem`）。
2. **顺序无关 digest 恒等**：map 创世与 30 天的 `deep` 均 = **`219fea7eeea2`**（修复前后同一个值）。
3. **逐项核对**：三个 region 的 `hexes` **元素集合差 = 0**；`boundary` 对象**逐字节相同**；
   `hexes / terrainBlocks / terrainTypes / spec` 等其余 map 组件**逐字节相同**；修复后 `hexes` 数组**确为自然序**
   （`sorted=True`，修复前为 `sorted=False`）。
4. **真跑 30 天后**：`POPULATION_ECONOMY=6371`（修复前 6371，8/8 JVM 全同）；economy/social/unit/sd/actor/gov/army
   **逐字节相同**。

### 5.1 ★ 变异自证（判别力：改回修复前形态 ⇒ 抖动必须当场回来）

- 变异体 = **只把 `Region` 那一处改回 `hexes = Set.copyOf(hexes);`**（并删 3 个随之未用的 import；首轮编译
  **exit=1** 正是 Checkstyle 的 `UnusedImports` 挡下的 —— 见 §9.3）
- 变异体编译 exit=0 → 跨 JVM ×4：**map 4/4 个不同**（`b6b741cd20f8 / 31c65a7c6d2f / def1dc4ace98 / c482dab5990a`），
  social/unit/sd/economy/actor/gov/army 各 **1/4**；**信封 4/4 不同**（`41d0209cfced / 8d789f2081c3 / bec2438f6a3d / f10a2e8bf9cc`），
  逐模块比**只有 map 一段**不同（`MOD map b6b741cd20f8` ↔ `31c65a7c6d2f`，`MOD social 6f745fa2475c` 相同）
- **还原**：`cp` 回修复版 ⇒ `md5sum -c` = **OK**（`4ab4cf1b7247d6ac1a94320ee14a3ce5`）⇒ 重编 exit=0 ⇒
  `Region.class` md5 回到 **`c5e31c2b40d6be986a6ef4cfe860927e`**（与变异前逐字节相同）⇒ 再跑跨 JVM ×4：**map 1/4 `40c2442f6ec1`**

⇒ 该行就是该抖动的**充分且必要**来源；本批的修复不是"顺手改多处碰巧好了"。

## 6. 同类普查（`simos-map` 全域：「建表/冻结序不是纯函数」逐条核）

### 6.1 已修

| # | 位置 | 改法 | 为什么 |
|---|---|---|---|
| ① | `region/Region.java:28`（根因，现 `:46-49`） | `Set.copyOf` → 自然序 + `LinkedHashSet` + `unmodifiableSet` | 序进字节（§3.2），且 `Set.copyOf` 吃 JVM 盐 |
| ② | `ops/RegionOperations.java:206,292,413,418` | 4 处 `withHexes(Set.copyOf(x))` → `withHexes(x)` | **同族别名去源**。★ 严格说**它不是本缺陷所必需**（`Region` 构造期已把序规范化，这 4 处的随机序到不了字节）；改它是为了（a）模块内"进字节路径上的 `Set.copyOf`"归零，便于一句 grep 复核；（b）防回潮——`Region` 的口径将来若被改，这 4 处会**静默**变回缺陷。传集合直接进 `Region` 有先例（`RegionOperations:294,331` 的 `part.hexes()` 本就直接传） |

### 6.2 查过、**判定不是缺陷**、未改（逐条给依据）

| 位置 | 形制 | 为什么不改 |
|---|---|---|
| `region/RegionBoundary.java:70`（`HashMap adjacency`）+ `walkRings` | 散列序 | ★ **任务书点名的那类"真的需要与迭代序无关"**：`walkRings` 的环表**在紧凑构造器里按各自首顶点字典序排序**（`:53`），而两条环**互不相交**（顶点各属一条）⇒ 首顶点互异 ⇒ 排序是全序 ⇒ `RegionBoundary` 只由集合内容决定。**实测**：跨 8 JVM 的 `boundary.rings` 数组**逐字节相同**（§3.4 的 diff 里 boundary 一条路径都没出现）。**保持不变** |
| `hex/HexGrid.java:20,83`（`Set.copyOf`） | 散列序 | `HexGrid` **不是状态组件**（`MapSnapshot = (ref, timestamp, map)`；`GameMap` 的 9 个组件里没有它）⇒ **序不进字节**。且 `HexGrid.of`/`cells()` **全仓零调用点**（实测 grep）；`withinRadius` 的生产消费者**各自定序**：`MapGenerator:150`（`.sorted()`）、`army/ArmyVision:57`（`TreeSet`）、`app/gov/ProvinceDivider:492`（`naturalSorted` + 纯 `contains`）、测试 `ScopeFixtures:79`（`TreeSet`）。序不入任何结果 |
| `generate/MapGenerator.java:187-192`、`GameMap.java:100-107`、`generate/RiverBuilder.java:219,222`、`pathway/PathwayGroup.java:66,67`、`spi/MapPayloads.java:324` 的 `Map.of()` | `Map.of` | **全部 0 键或 1 键**（`EMPTY_MAP` / `Map1`）⇒ 迭代序天然是内容的纯函数；实测 8 JVM 全部稳定（这些模块 digest 恒定） |
| `ops/RegionOperations.java:455` `RegionPart`（含 `:462` 的 `LinkedHashSet` 冻结） | 载荷侧瞬态 | 不进状态树；其序 = **载荷 JSON 数组序**（输入字节的纯函数，不是 JVM 盐）；进 `Region` 后被规范化。**保留**（只更新了它的类注，见 §7） |
| `region/RegionIndex.java:45,51`（`HashMap`） | 散列序 | 类注明写"**不进变更集、不进存档**"；且 `of` 先按 `RegionId` 字典序处理、逐格追加 ⇒ 每格列表天然有序、与入参序无关 |
| `resolve/MapResolver.java:195`（`HashSet`） | 散列序 | 只作 membership 判定；输出按 `map.regions().keySet()` **插入序重建**（`:197`）⇒ 纯函数（且 `HashSet` 的序本就无 JVM 盐） |
| `Pathway.edges` / `TerrainCatalog.defaults()` / `PathwayGroup.defaults()` / `City.props` / `EdgeTags` / `GameMap.copyOf` | 已保序 | `List.copyOf`（链走向**是语义**）或 `LinkedHashMap`+`unmodifiableMap`（声明序语义）。**一行未动** |
| `block/TerrainBlock.java:50-53` | 已自然序 | 本轮口径的**先例**：同一模块、同为「hex 集合 + `RegionBoundary` 组件」 |

### 6.3 范围外（本批文件所有权禁碰，仅上报）

1. **`simos-util/state/SimulationState.java:20` `Map.copyOf`** —— K 批已实测**不泄漏成字节**（`CheckpointEncoder:55`
   用 `TreeMap` 收集模块；实测 6 JVM 的 `modules` 键序完全相同）。本批**未复核**（K 的结论 + 本批 8 JVM 信封逐模块唯一与之相容）。
2. **app 侧已自行定序的消费者**（不动，但如实记）：`access/NationScope.java:68`（`TreeSet`）、`access/ArmyScope.java:75`
   （`TreeSet`）、`time/JurisdictionDailyTax.java:317-318`（`hexes.copy → sort(Comparator.comparing(HexCoord::toString))`）、
   `gov/ProvinceDivider.java:492`（`naturalSorted`）、`market`/`generate` 侧 `RegionRandomizer.java:76`（`.stream().sorted()`）。
   ⇒ **改 `Region` 的序对它们零影响**（它们本来就自己排序或只用 `contains`）。
3. **`HexGrid` 的 `Set.copyOf`（2 处）** 保留的理由见 §6.2（非状态组件）。

## 7. 对那段「不保序是有意的」类注的处置（复核 → 更正）

### 7.1 原文（`Region.java:14-16`，修复前）

> ★ `hexes` 用 `Set#copyOf`（**不保序**）是有意的：它是**集合语义**，迭代序不该被依赖。需要保序的只有 `TerrainCatalog`
> （落盘的是它），两处的理由不同，**不要统一**。这条在 U2 之后多了一层后果：`Region.equals` 逐组件比较，故
> `RegionBoundary#of` 必须是 `hexes` 的**纯函数**（与迭代序无关），否则内容相同的两个 Region 会不相等。

### 7.2 复核结论（逐句判）

| 原句 | 判定 |
|---|---|
| "它是**集合语义**" | **成立**，且本批一字未动（对外仍是 `Set`，`equals`/`contains` 照旧） |
| "迭代序**不该被依赖**" | **成立**，且本批在**保留**它（新类注明写"代码逻辑依然不得依赖迭代序"） |
| "需要保序的只有 `TerrainCatalog`（落盘的是它）" | ★ **前提错误**：`MapCodec` 把 `Region.hexes` 写成 **JSON 数组**，**它的序也在落盘字节里**。这句话把"谁有**语义序**"与"谁的**序进字节**"混为一谈 |
| "两处的理由不同，不要统一" | **半成立**：`TerrainCatalog` 是**声明序**（语义），`Region.hexes` 应是**内容派生序**（规范）——两者确实不该统一成同一条规则；但**不能**由此推出"`Region.hexes` 可以不保序" |
| "`RegionBoundary#of` 必须是 `hexes` 的纯函数" | **成立**，保留（且本批**实测**：8 JVM 的 `boundary.rings` 逐字节相同） |

### 7.3 处置：**改了注，并说明理由**（不是删掉留痕，而是**更正 + 标注轮次**）

- 新类注（`Region.java:19-34`）四段：① 迭代序 = 内容的纯函数（自然序），落盘字节因此稳定；② **为什么**（`MapCodec`
  把它写成 JSON 数组 ⇒ 序进字节）；③ **集合语义一字未动** + "迭代序不该被依赖 vs 迭代序必须唯一"不矛盾 + **两处仍然不要统一**；
  ④ 保留 `RegionBoundary.of` 纯函数那条。
- **明确写出"旧注的前提与事实相反，本轮更正"**，并留下轮次（2026-10-10）——符合 §五.4「历史不篡改，要更正就追加标注」的精神
  （代码注释不能"追加在文件末尾"，故在**同一位置**写明被更正的原句与轮次）。
- **为什么不能不改**：这句注释是"机制性描述"，而它的前提已被实测证伪（§3、§5）；留着它，下一个读代码的人会照着
  "不保序是有意的"去拒绝任何字节序修复——这正是 AGENTS §四「台账/计划 vs 代码：机制性描述一律回代码核」要防的损失。

### 7.4 连带更正的两处过时类注（同族事实已被本批改变）

| 文件 | 原句 | 处置 |
|---|---|---|
| `region/RegionBoundary.java:25-27` | "`Region.hexes` 是 `Set.copyOf`（不保序）" | 前提已不成立 ⇒ 改成"入参是**任意** `Set`（`TerrainBlock.of`、反序列化、测试都直接调）" ⇒ **论点（必须与序无关）不变且更贴切** |
| `ops/RegionOperations.java:451`（`RegionPart` 类注） | "与 `Region` 的落盘序口径一致" | 事实已变（`Region` 现在**规范化**成自然序）⇒ 改成"本类型的序 = 载荷数组序，只影响校验/目标清单次序；**落盘序不由它决定**" |

★ 另有一条**不在我所有权内**、但事实已变的注（**留给测试代理/控制方**）：`simos-map/src/test/java/io/mosire/simos/map/RegressionGuardsTest.java:269-273`
的「保序断言的边界」裁定（"唯独 `Region.hexes` 用 `Set.copyOf`，迭代序明确不许依赖……把它纳入逐字节断言等于要求一个设计上不成立的性质"）。
修复后这个前提消失：`Region.hexes` **可以**纳入逐字节序断言。**该测试不会失败**（它只断言 `GameMap.hexes` 与 `terrainTypes`
的键序），故本批只上报、不改（§8.3）。

## 8. 会改变数值行为的清单（预期：**空**）

**空**。逐条依据：

1. **只改"冻结序"**：没有动任何算式、阈值、标定值、状态语义、命令语义；`Region.equals` 逐组件比较**一字未动**。
2. **真跑 30 天（同一份字节、同一世界、同一 tick）**：修复前 ↔ 修复后，**economy / social / unit / sd / actor / gov / army
   七段快照逐字节相同**（`bytesEqual=True`）；map 只差 3 条数组序（`arrayOrderPaths=3 / valuePaths=0`），
   `deep` digest 恒等 `219fea7eeea2`。`POPULATION_ECONOMY=6371`（8/8 JVM）。
3. **逻辑论证**：修复前的迭代序是**per-JVM 随机**（`SALT` = 启动 `nanoTime`）⇒ 任何**依赖它的确定行为**在修复前就会
   跨进程抖；实测 8 JVM 的 7 个模块状态逐字节相同 ⇒ 这些路径不依赖它。
4. **app 侧迭代 `Region.hexes()` 的消费者**：已自行定序（§6.3-2）或只做成员判定（`NationScope`/`MapOverlapsTool`/
   `MarketTopologyBook`）⇒ 序变化不改变其**值**；`JurisdictionDailyTax` 的整数税基按家户独立累加 ⇒ 可交换。
   ★ **唯一可能的"输出次序"变化**：以 `Region.hexes()` 原序渲染的清单（如 `gui/ApiViews`、`tools/read/MapRenderTool`）
   由"随 JVM 变的序"变成"自然序"。这是**序**的变化，不是值的变化，且是**去抖**方向。
5. **未做**（如实记）：我没有逐条证明**每一个** app 侧消费者的输出逐个字节不变（见 §10）。

## 9. 编译与门禁（本批只被允许跑 `-DskipTests compile`）

| # | 时刻 | 命令 | 结果 |
|---|---|---|---|
| ① | 改动后 | `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` | **exit=0**；`Region.class` mtime `01:43:30` > 源 `01:43:10` ⇒ **真重编** |
| ② | 类注重排（仅注释）后 | 同上 | **exit=0**；`Region.class`/`RegionOperations.class` md5 **不变**；`RegionBoundary.class` md5 变 —— **已证只是行号表**：`javap -c -p`（不含调试信息）两份 md5 **同为 `56abdad6f85e999cd7e56a81b6528837`**，`javap -l` 不同 |
| ③ | 变异体 | `-pl simos-map -am` | 首轮 **exit=1**（Checkstyle `UnusedImports` ×3 ⇒ 变异体需连 import 一起改），改后 **exit=0** |
| ④ | 还原后 | `-pl simos-app -am` | **exit=0**；`Region.class` md5 回到 `c5e31c2b40d6be986a6ef4cfe860927e` |

★ **意外收获（如实记）**：`compile` 生命周期**含 Checkstyle**（③ 的失败点是 `maven-checkstyle-plugin:check (checkstyle-check)`）
⇒ **本批的最终树是 Checkstyle-clean 的**（① ② ④ 全绿）；`LineLength` 未启用（① 之前我曾写出一行 106 列而编译仍绿，
随后按 gjf 的 100 列口径重排了 3 行）。
★ **未跑**：`spotless:apply` / `spotless:check` / `spotbugs:check` / `test` / `verify` / `package`（任务书禁止）⇒ 格式**没有工具背书**（§10）。

## 10. 会让既有测试失效的清单

**预期：空**（逐条读断言得来，**未跑测试**）：

1. `RegionTest`：`containsExactlyInAnyOrderElementsOf`（`:85`）、`reversedOrder(HEXES)` 与正序**必须相等**（`:106`）、
   `isEqualTo(moved)`（`:183`，Set 判等）、`isUnmodifiable()`（`:86`，`Collections.unmodifiableSet` 通过）⇒ **全部与序无关**。
2. `RegionOperationsTest` / `RegionHandlersTest` / `RegionIndexTest` / `MapResolverTest` / `GovScopeTest`：`containsExactly(H)` 只出现在
   **单元素**上，多元素一律 `containsExactlyInAnyOrder`。
3. `MapCodecTest`：类注原文"**往返只断 `equals`，绝不拿字节当断言**（台账裁定 12：跨 JVM 实测 2 种字节）"⇒ 方向与本批**一致**。
4. `RegressionGuardsTest.L7`：只钉 `GameMap.hexes`（插入序 map）与 `terrainTypes` 的词表序 ⇒ **不涉及 `Region.hexes`**。
5. `MapCodecLegacyTest`：旧形状迁移按字段遍历建表、内容驱动 ⇒ 与数组序无关。
6. `RandomizeRegionHandlerTest`："载荷数组序不进变更集" —— 该性质由 `TerrainBlock`（已自然序）+ `TerrainBlocks.split`
   （起点按自然序、块表 `TreeMap`）保证，**本批未动**；其"逆序载荷解析序必须真的不同"断言针对 `MapPayloads`，也未动。

**但有两处"事实已变、断言未变"的测试内注释**（★ 我**不许碰** `src/test/**`，故上报）：

- `simos-map/src/test/.../RegressionGuardsTest.java:269-273`：**"唯独 `Region.hexes` 用 `Set.copyOf`，迭代序明确不许依赖"** ——
  修复后 `Region.hexes` 是**自然序规范序**，该裁定可以**收紧**（建议测试代理补一条"跨 JVM / 两轮重建逐字节稳定"的用例，
  把那 3 条数组路径钉住；判据：`map` 段 digest 恒定 `40c2442f6ec1…`，或直接断言 `hexes` 数组 = 自然序）。
- `simos-map/src/test/.../codec/MapCodecTest.java:36` 类注"（台账裁定 12：跨 JVM 实测 2 种字节）" + `region/RegionBoundaryTest.java:108`
  "`hexes` 是 `Set.copyOf`（不保序）" —— 均为**事实过时**，断言本身**不会红**。
- ★ 这两条是"测试里写着的旧事实"，与代码里的旧类注同族；**建议由测试代理在同一批里更新**（我不越界）。

## 11. 偏离记录（与任务书不一致之处，主动申报）

| # | 偏离 | 性质 / 原因 |
|---|---|---|
| D1 | **排序口径选"自然序"而非"保留插入序"** | 任务书说既有口径是 `LinkedHashSet`/`LinkedHashMap` 冻结，也说"迭代序 = 内容的纯函数"。两者在"上游可能喂 HashSet"时不等价 ⇒ 取**强的那一个**（自然序），依据 §4 四条（含 `TerrainBlock` 同模块先例）。**若控制方要"保留插入序"，改动面是 `Region.java` 一处 + 保留 `RegionOperations` 4 处 `Set.copyOf`**（那时它们重新变成缺陷），回退成本极低 |
| D2 | **多改了 4 处 `Set.copyOf`（`RegionOperations`）** | 同族别名去源，**非本缺陷所必需**（`Region` 已规范化）；理由是"进字节路径上的 `Set.copyOf` 归零"便于复核 + 防回潮。**可单独回退这 4 行而不影响修复** |
| D3 | **多更正了 2 处同族类注**（`RegionBoundary`、`RegionPart`） | 它们的事实前提被本批改变；留着就是"机制性描述 ≠ 代码实际"（AGENTS §四）。改动**纯注释**（② 轮 md5 证据见 §9） |
| D4 | **未跑 `spotless:apply` / `verify`** | 任务书只允许 `-DskipTests compile`。已按 gjf 口径手工重排（全部行 ≤100 列；Checkstyle 绿），但**无 spotless 背书** |
| D5 | **未能 claim / complete `task-31`** | 任务板报"不是 active Agent Team 成员"，按任务书"不要卡住"继续执行 |
| D6 | **改了 `/tmp/koprobe/ProbeKeyOrder.java`**（`advance` 补写 checkpoint） | 一次性探针（`/tmp`，不进仓库、不进 `src/test`）；K 批已收工，无并发写者。装置副本已入 `evidence/` |

## 12. 未完成 / 未验证 / BLOCKED

1. **未跑任何测试与门禁**：`test` / `verify` / `package` / `spotless:check` / `spotbugs:check` **一律未跑**（任务书禁止）
   ⇒ §10 的"预期空"是**读断言**得来，不是实测。测试代理需要重点核：`RegionTest`、`RegionOperationsTest`、`RegionHandlersTest`、
   `MapCodecTest`、`RegressionGuardsTest.L7`、`MapChangeSetTest`、app 侧 `ProvinceDivider`/`JurisdictionDailyTax` 相关 e2e。
2. **未验证 spotless 格式**（gjf-canonical）：只做了"≤100 列 + Checkstyle 绿"两条弱背书。
3. **未做**：`changeset_json`（map 的 `MapChangeSet`）跨 JVM 的唯一性**未单独实测**——本批的强证据是**同一根因、同一条
   `Region` 序列化路径**（§3.2）+ checkpoint 信封逐模块唯一。**如需端到端**：测试代理可在命令入口抓 `MapChangeSet` 的 JSON 两次跨进程比对。
4. **未做**：GM 写路（`map.CreateRegion`/`UpdateRegion`/`SplitRegion`/`MergeRegions`/`ReassignHexes`）的**跨 JVM 字节唯一性未端到端实测**——
   这些路径的输入是**载荷字节**，其序是"输入字节的纯函数"；已由 `Region` 规范化兜底（代码链 §3.3）但**未跑**。
5. **未逐条证明 app 侧每个 `Region.hexes()` 消费者的输出逐个字节不变**（§8.4）：已核 10+ 处（多数自行定序/仅成员判定），
   并实测"30 天后 7 模块逐字节相同"；但**未穷举**（未做覆盖率统计）。
6. **未复核**：`simos-util` 的 `SimulationState.modules = Map.copyOf`（K 批已实测不泄漏成字节；任务书亦禁改 util 状态机制）。
7. **未验证**：`HexGrid` 的 `Set.copyOf`（2 处）我判为"非状态组件 ⇒ 不进字节"，依据是"`MapSnapshot`/`GameMap` 组件枚举里没有它
   + `HexGrid.of/cells()` 全仓零调用点 + 4 个 `withinRadius` 消费者各自定序"；**没有**用探针直接证明"某条 payload 里不含 HexGrid 序"。
8. **提交提醒（不是缺陷）**：`.superpowers/sdd/.gitignore` 是 `*` ⇒ 本账本与 `evidence/**` **未被 git 跟踪**，
   控制方提交时需 `git add -f .superpowers/sdd/2026-10-10-map-order-determinism/`。
9. **证据留在 `/tmp`**（`/tmp/maporder/**`：`before/` `after/` `final/` `mutant/` `restored/` 的逐 JVM 原件 + 日志）——
   `/tmp` 会被清 ⇒ 关键**命令输出已逐条抄进本账本**，装置源码已入 `evidence/`，**可重放**。
