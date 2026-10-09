# 责任区 K 修复账本：**同一棵树内 JSON 映射键序抖动**（`EconomyData`）

- 批次：2026-10-10 键序确定性批（任务书 `task-30`；**未能 claim**：任务板报 `agent "1f524073-…" is not a member of an active Agent Team`，按任务书"不要卡住"继续执行）
- 责任区：让**状态表（及其载荷/落盘字节）的迭代序 = 内容的纯函数**；**只改序、不改值**
- 结论：**定位到 `EconomySeeder` 的 7 处两键 `Map.of` ⇒ 已修；economy 快照字节跨 JVM 由 4/6 不同 → 1/8 唯一**；同族普查另报两条**范围外**发现（`simos-map` 的 `Region.hexes`、`simos-util` 的 `SimulationState.modules`）
- 编译：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` → **exit=0**（`EconomySeeder.class` mtime 01:30:51 > 源 01:30:2x，确认真重编）
- 改动面：**1 个文件 / +29 −7**：`simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`

---

## 1. 复现装置（一次性探针，落 `/tmp`，**不进仓库、不进 `src/test`**）

- 探针：`/tmp/koprobe/ProbeKeyOrder.java`（package `io.mosire.simos.app.time`，为用包内可见的 `MarketTopologyBook`）
  - 编译/运行 classpath 沿用 R2 批的 `/tmp/r2probe/cp.txt`（14 个模块 `target/classes` + `dependency:build-classpath` 的依赖）
  - 四个模式：
    - `snap <outDir> <rounds>`：同 JVM 内**重建世界 N 次**（同一份字节、同一世界、同一 tick=0）→ 逐模块编码 → sha256 **字节 digest** + **顺序无关（canonical，递归按键排序）digest**；写 `<outDir>/run<i>/<module>.json`；并做 run0↔runR 的**逐路径定位**（键序差异 / 值差异分列）
    - `locate <dirA> <dirB>`：跨 JVM 两份输出逐模块逐路径比较（同上分列）
    - `advance <outDir> <days> <rounds>`：真 `EconomyDayStepper` 日循环（组合根三样只读投影按 app 同名私有 helper 逐字复制，与 R2 `ProbeIP8` **同一份装置**）→ 末态逐模块 digest + **末态再序列化两次**（同对象再编码 / 编码→解码→再编码）
    - `worlds <outDir>`：**同族普查**——本仓四个世界（three-powers / small-world / corridor-world / rich-world）创世态逐模块 digest
  - 模块集合：`map / social / unit / sd / economy / actor / gov / army`（8 个 namespace 各自 codec）
  - 另计：`CheckpointEncoder.encode(state, codecs)`（**整世界 checkpoint 信封**）与 `EconomyCodec.encodeChangeSet(EconomyChangeSet.between(empty, seeded))`（**journal 的 `changeset_json`**）

- 命令（每次都是**新进程**）：
  ```bash
  cd /tmp/koprobe && CP=$(cat /tmp/r2probe/cp.txt)
  java -cp "out:$CP" io.mosire.simos.app.time.ProbeKeyOrder snap   /tmp/koprobe/final/same 6      # 同 JVM ×6
  java -cp "out:$CP" io.mosire.simos.app.time.ProbeKeyOrder snap   /tmp/koprobe/final/xjvm-$i 1  # 跨 JVM，i=0..7
  java -cp "out:$CP" io.mosire.simos.app.time.ProbeKeyOrder advance /tmp/koprobe/final/adv-$i 30 1
  java -cp "out:$CP" io.mosire.simos.app.time.ProbeKeyOrder advance /tmp/koprobe/final/adv2  30 2
  java -cp "out:$CP" io.mosire.simos.app.time.ProbeKeyOrder worlds  /tmp/koprobe/worlds/jvm$i
  ```

## 2. 第一步先分清：**同 JVM 不抖，只有跨 JVM 抖**（⇒ 是 per-JVM 盐，不是动力学）

| 装置 | 修复前 |
|---|---|
| **同 JVM 内创世 ×6 轮** | 8 个模块**全部 1/6 唯一**（economy `c797240a977e`×6）⇒ 同 JVM 内本就确定（该轮日志在本会话终端输出，未落盘） |
| **跨 JVM 创世 ×6 进程** | **economy = 4/6 个不同 digest**：`24299044ba96 / 40edfdb058a6 / 96c1dc5fc748 / bd0900381ce0`；而**顺序无关 digest 1/6**（`b5f996f39417`）⇒ **只有键序抖，键集与值不抖** |
| 同上，其他模块 | social `6f745fa2475c`、unit `e5f0a69ffdc7`、sd `2f7039f12cbe`、actor `0edcefed1f6f`、gov `13ba793fe453`、army `77918c85e631` 各 **1/6** |
| **跨 JVM 真跑 30 天 ×3 进程** | economy **3/3 个不同**（`c2938529068e / 6fd3abe3f3c3 / f63243f8688f`），顺序无关 digest 仍 **1/3**（`c68b20118f31`）⇒ 抖动与"是否创世态"无关 |
| map（**范围外**，见 §7） | 5/6 不同，且**顺序无关 digest 也 5/6** ⇒ 不是键序，是**数组序** |

## 3. 定位：哪张表、哪一行、输入源是什么

逐路径定位（修复前 jvm0↔jvmR，`locate` 输出的 `JITTER` 行）：

```
JITTER economy $.data.industries.farm@<q>_<r>.outputPerUnit              A=[grain, fiber]  B=[fiber, grain]   × 37 格
JITTER economy $.data.industries.craft@<q>_<r>.outputPerUnit             A=[cloth, tool]   B=[tool, cloth]    × 3 格
JITTER economy $.data.industries.craft@<q>_<r>.cycleInputPerUnit.WORKSHOP A=[tool, fiber]  B=[fiber, tool]    × 3 格
```
**值差异路径 = 0**（`VALUEDIFF economy` 计数恒 0）。

### 3.1 输入源（逐条核，不是"大概是 `Map.of`"）

`EconomySeeder` 里所有的**两键** `Map.of` 恰好就是这三张会抖的表（**一键 `Map.of` = `Map1`，迭代序天然是内容的纯函数，实测全部稳定**）：

| # | 建表语句 | 落到哪张状态表 | 实测 |
|---|---|---|---|
| ① | `EconomySeeder.java:4053`（原 `:4031`）`Map.of(COMMODITY_GRAIN, GRAIN_OUTPUT_PER_MU, COMMODITY_FIBER, FIBER_OUTPUT_PER_MU)` | `farm.recipe().outputPerUnit` | **抖**（37 格） |
| ② | `EconomySeeder.java:4127`（原 `:4105`）`Map.of(COMMODITY_CLOTH, …, COMMODITY_TOOL, …)` | `craft.recipe().outputPerUnit` | **抖**（3 格） |
| ③ | `EconomySeeder.java:4137`（原 `:4113`）`Map.of("WORKSHOP", Map.of(COMMODITY_FIBER, …, COMMODITY_TOOL, …))` 的**内层** | `craft.recipe().cycleInputPerUnit["WORKSHOP"]` | **抖**（3 格；外层一键不抖） |
| ④–⑦ | `:4046`（split 700/300）、`:4090`（300/700）、`:4124`（400/600）、`:4168`（1000/0） | `allocation` 载荷对象键序（→ `events.payload_digest`），**不进状态树** | 见 §5 |

**完整因果链（逐环节读代码确认）**：

1. `Map.of` 的 **≥2 键**形态是 `ImmutableCollections.MapN`，槽位 = `Math.floorMod(键.hashCode() ^ SALT, 表长)`，`SALT` 取自 **JVM 启动时的 `nanoTime`**（JDK 9+ `ImmutableCollections` 静态初始化）⇒ **同一份内容在不同 JVM 上迭代序可以不同**；
2. `EconomySeeder.industry(...)`（`:4273`，修复前 `:4251`）把这三张表**原样**塞进 `LinkedHashMap` 载荷（`payload.put("outputPerUnit", outputPerUnit)`）⇒ 载荷 JSON 的**对象键序**继承 `Map.of` 的迭代序；
3. `EconomyPayloads.industry(JsonNode)`（`:1362`）`commodityMap/assetCommodityMap` 按 **JSON 出现序**建表（Jackson 给 `LinkedHashMap`）；
4. `Industry` 的紧凑构造器（`Industry.java:259-…`）**按入参迭代序**拷进 `LinkedHashMap` + `Collections.unmodifiableMap` ⇒ 冻结的是**上游的随机序**（这正是"保序冻结"口径的代价面）；
5. 状态树 → `EconomyCodec.encodeSnapshot` 的 JSON 键序 = 该表迭代序 ⇒ **快照字节**；同一条路也进 `EconomyChangeSet` ⇒ **journal 的 `changeset_json`**。

### 3.2 判别力自证（变异体：只把 ① 改回 `Map.of`）

- 变异体（仅一行）→ 重编 → 跨 JVM ×8：**economy 2/8 个不同 digest**（`bd0900381ce0`×6 / `4be534eb449d`×2），`locate` 给出 **37 条抖动路径，全部是 `farm@*.outputPerUnit` `[grain, fiber]` ↔ `[fiber, grain]`，值差异 0**
- ⇒ 该行就是该表的抖源（②③ 同形同源，路径与格数逐条对上）
- 还原后重编 → 再跑跨 JVM ×8：economy **1/8 唯一**（`bd0900381ce0`），`git diff --stat` 与变异前逐字一致（+29 −7）

## 4. 修法与口径（为什么这样修）

**修在生产者（`EconomySeeder`），不修序列化层、不排序输出**：

```java
  /** ★★ <b>保序的两键表</b>（LinkedHashMap + Collections.unmodifiableMap，**不用 Map.of**）。… */
  private static Map<String, Object> orderedTable(
      String firstKey, Object firstValue, String secondKey, Object secondValue) {
    Map<String, Object> table = new LinkedHashMap<>();
    table.put(firstKey, firstValue);
    table.put(secondKey, secondValue);
    return Collections.unmodifiableMap(table);
  }
```

- **口径 = 本仓既有口径**：`LinkedHashMap` 拷贝 + `Collections.unmodifiableMap`（**不用 `Map.copyOf`**：它不保证序）。同一条纪律在本文件早已写过一遍——`factoryPrices()`（`:1725`）的类注原文就是"★ 为什么不直接 `Map.of(...)`：它的迭代序不是内容的纯函数 ⇒ 载荷字节会抖"。本批是**把这条纪律补到配方表上**，不是新立规矩。
- **保的序 = 源码书写序**（grain→fiber / cloth→tool / fiber→tool）：这三个序**今天本来就各有一半的 JVM 会产出**（① 的两支实测都出现过），所以"书写的那个"是**既有行为之一**，不是新拍的政策；也没有引入任何新的排序规则（不发明"按 id 字典序"）。
- **不在序列化层"排序后输出"**（任务书点名的掩盖法）：内存态与落盘态必须一致；`Industry` 的"迭代序原样冻结"语义**一字未动**，改的只是**喂进去的那张表**。
- **不在 `EconomyPayloads` 入口做规范化**（任务书允许的另一支）：那会让**外来载荷**（GM `economy.UpsertIndustry`、旧档）也按本仓规则重排序——超出本缺陷、且会把"输入字节决定的状态序"变成另一套策略；最小修法是在唯一的创世生产者处去掉随机源。偏离记录见 §8。
- **7 处一起修**：① ② ③ 是状态表（本缺陷）；④–⑦ 是**同一条根因在同文件的另四张两键表**——它们的键序进 `economy.Seed` **载荷 JSON**，而 `CommandBus:867` 落盘的 `events.payload_digest = Digest.sha256(payloadJson)` ⇒ **审计列也不是纯函数**（对状态树不可见：`AllocationRule.Split` 是具名字段的 record）。同族同源，一并改（§5 有前后的实测）。

## 5. ★ 分层证据：**同一装置、同一 N=8** 的修复前 ↔ 修复后对照

装置：`snap <dir> 1`，8 个**新进程**（同一份 classpath、同一份字节、同一世界、同一 tick=0）。
"修复前" = `git stash push -- simos-app/.../EconomySeeder.java` 回到 HEAD（`73237009`）源码后重编（compile **exit=0**）；
"修复后" = `git stash pop` + 重编（compile **exit=0**，且 `sha256sum -c` 证明该文件与 stash 前**逐字节相同**：`093378f569a7893b…`）。

| 字节流 | **修复前** 唯一数/8 | **修复后** 唯一数/8 | 结论 |
|---|---|---|---|
| `economy` 快照 | **3/8**（`24299044ba96 / 96c1dc5fc748 / c797240a977e`；顺序无关 **1/8**） | **1/8** `bd0900381ce0`（顺序无关 1/8） | **抖 → 唯一** |
| `changeset_json`（economy 播种变更集） | **3/8**（`41accd133c7b / 4e0487ac1a78 / f17d25b98210`；顺序无关 1/8） | **1/8** `f143e5d7c8a4`（顺序无关 1/8） | **抖 → 唯一** |
| `social / unit / sd / actor / gov / army` 快照 | 各 **1/8** | 各 **1/8**（`6f745fa2475c / e5f0a69ffdc7 / 2f7039f12cbe / 0edcefed1f6f / 13ba793fe453 / 77918c85e631`） | 本就唯一、未被影响 |
| `map` 快照（**范围外**） | 6/8（顺序无关也 6/8） | 8/8（顺序无关也 8/8） | 数组序缺陷；两轮都非唯一（§7.3-1） |
| checkpoint 信封（`CheckpointEncoder.encode`） | 6/8 | 8/8（**逐模块比：只有 map 一段不同**，其余 7 段逐字节相同；`modules` 键序恒为 `actor,army,economy,gov,map,sd,social,unit`） | 由 map 拖累 |

（本表是"同装置同次数"的判据；§2 表里的 6 进程数是**修复前的另一组同装置重复**，两者结论一致。）

## 5.1 修复后：全部 digest（**最终树**，一次连贯的成套轮次）

| 装置 | economy | 其他 7 个模块 |
|---|---|---|
| **同 JVM ×6 轮（创世）** | **1/6** `bd0900381ce0` | 全部 **1/6**（map `a02952753e17`、social `6f745fa2475c`、unit `e5f0a69ffdc7`、sd `2f7039f12cbe`、actor `0edcefed1f6f`、gov `13ba793fe453`、army `77918c85e631`） |
| **跨 JVM ×8（创世）** | **1/8** `bd0900381ce0` | social/unit/sd/actor/gov/army 各 **1/8**；**map 8/8 不同**（范围外，§7） |
| **跨 JVM ×6（真跑 30 天）** | **1/6** `5c98dd9e7fd7` | social `898d41771711`、unit、sd、actor、gov、army 各 **1/6**；map 5/6（范围外） |
| **同 JVM ×2 轮（真跑 30 天）** | **1/2** `5c98dd9e7fd7` | 全部 1/2（含 map 1/2） |
| **跨 JVM ×3（真跑 120 天＝一个完整周期）** | **1/3** `3860d9b580c4` | social 1/3 `fc91064104ce` |
| **同族普查：四个世界创世 ×4 进程** | **1/4**（three-powers `bd0900381ce0` / small-world `f61261993307` / corridor `005286418936` / rich `e948f5d3b164`） | social/unit/sd/actor/gov/army 各 **1/4**；map 三个世界不唯一（范围外） |

**末态"再序列化两次"**（30 天后）：`once==twice: true`（8/8 模块）、`once==roundTrip: true`（economy/social/unit/sd/actor/gov/army；**map 为 false**——`Set.copyOf` 每次重建都重新随机，范围外）；`POPULATION_ECONOMY=6371` 与修复前逐值相同。

**落盘字节的另一半（journal / checkpoint）——变异体（仅 ① 一处回退）的同装置 6 次重复**：

| 字节流 | 修复前（变异体仅 ① 一处回退） | 修复后 |
|---|---|---|
| `changeset_json`（economy 播种变更集） | **2/6 个不同**（`f143e5d7c8a4`×4 / `5d2d54db05af`×2；顺序无关 1/6） | **1/6** `f143e5d7c8a4`（顺序无关 `c2ae6c4a0302`） |
| checkpoint 信封（`CheckpointEncoder.encode`） | 逐模块比：**economy 与 map 两段不同** | **只有 map 一段不同**（economy/actor/army/gov/sd/social/unit 七段逐字节相同） |

## 6. ★ "只改序不改值"的证据（本批的硬判据）

1. **顺序无关 digest 恒等**：
   - 创世 economy `b5f996f39417`（修复前 6 进程 **1/6** ↔ 修复后 8 进程 **1/8**，**同一个值**）；
   - 30 天 economy `c68b20118f31`（修复前 3 进程 **1/3** ↔ 修复后 6 进程 **1/6**，**同一个值**）；
   - 其余 7 个模块的 sem digest 修复前后**逐值相同**。
2. **修复前 vs 修复后逐路径比较**（`locate`）：
   - 创世：economy `keyOrderPaths=40 / valuePaths=0`；30 天：`keyOrderPaths=15 / valuePaths=0`；
   - social/unit/sd/actor/gov/army：`bytesEqual=true`（逐字节相同）；
   - map：`valuePaths=58` —— 那是**数组序**（范围外缺陷），其键序差异为 0。
3. **数值行为不变**：30 天真跑末态 `POPULATION_ECONOMY=6371`（修复前 6371）；economy/social 的**顺序无关**末态 digest 与修复前逐值相同。

## 7. 同类普查结果（还改了什么 / 还有什么）

### 7.1 已修（同族同源，全部在 `EconomySeeder`）
`orderedTable` 落点 7 处：`agriculture` 的 outputPerUnit+split、`householdWeaving` 的 split、`handicraft` 的 split+outputPerUnit+cycleInputPerUnit["WORKSHOP"]、`trade` 的 split。**其余 `Map.of` 一律保留**（一键=稳定；多键的常量表经查是纯查表，见 7.2）。

### 7.2 查过、**判定不是缺陷**、未改（逐条给依据）
| 位置 | 形制 | 为什么不改 |
|---|---|---|
| `EconomySeeder:392 ACTIVITY_SEX_WEIGHT_PER_MILLE`（3 键） | `Map.of` | 只有 `:3861 .get(activity)` 查表；键序从不被迭代 |
| `EconomySeeder:485 INITIAL_RATION_DAYS_BY_CLASS`（4 键） | `Map.of` | 只有 `:2496 .get(classId)` 查表 |
| `EconomySeeder:565 AGE_LABOR_COEF_BY_SEX`（2 键） | `Map.of` | 只有 `:2393 .get(group.sex())` 查表 |
| 配方里其余 `Map.of`（capacity / capacityPerUnit / dailyInputPerUnit / weave 的 output 与 cycleInput） | 一键 `Map1` | 一键迭代序 = 内容的纯函数；实测 6 进程/8 进程全稳定 |
| `PopulationSeeder:57 SEX_SHARE_PER_MILLE`（2 键） | `Map.of` | 调用点 `:348` 用 `Sex.values()[i]` 显式定序后 `.get(...)` ⇒ 迭代序不进任何结果 |
| `ThreePowersWorld:285 LOW_HILLS_HEXES`（5 键）、`SmallWorld:234`（4 键） | `Set.of` | 只有 `:576`/`:425` 的 `contains(hex)` 查表 |
| `MarketTopologyBook:88 TIER_RADIUS_HEX`（4 键） | `Map.of` | `:397 getOrDefault(tier, …)` 查表 |
| `HexCrisisSignal`/`SignalDraft` 的 evidence 生产点（`GovDaily:617/625`、`GovernmentServiceDesertionBridge:925`、`EconomyLiquidationSettlement:371/592/655`） | **`LinkedHashMap`** | 同族里**已经**用对口径的样本（危机信号是 `EconomyData.crisisSignals` 状态组件，若这里写 `Map.of` 会抖；实测四个世界 120 天未见抖动，与此一致） |

### 7.3 范围外发现（**本批文件所有权禁止我改**，均为**预存量**，仅上报 + 给最小修法建议）
1. ★★ **`simos-map`：`Region.hexes` 的数组序**（**会让 map 模块快照字节跨 JVM 不同**）
   - 现场：`simos-map/src/main/java/io/mosire/simos/map/region/Region.java:19,28` —— `hexes` 字段是 `Set<HexCoord>`，紧凑构造器 `hexes = Set.copyOf(hexes)`；**类注 `:14-16` 明说这是有意的**（"集合语义，迭代序不该被依赖"）。
   - 实测：three-powers 创世 map **8/8 个不同 digest**（顺序无关也 8/8），差异路径 = `$.map.regions.province-{silver,copper,gold}.hexes[i].{q,r}` 整段重排；`RESERIALIZE map once==roundTrip=false`；checkpoint 信封因此 6/6 不同（其余 7 段逐字节相同）。
   - 为什么它进字节：`MapCodec` 把这个 `Set` 序列化成 **JSON 数组** ⇒ 集合的迭代序泄漏成数组序。
   - 最小修法（**需 simos-map 的所有权 + 会影响 `Set.copyOf` 的"集合语义"类注，属设计口径变更，建议单独裁定**）：`hexes` 落盘时按 `(q,r)` 规范序输出（或在状态里存 `List`/保序 `Set`）。**本批未做**：文件所有权禁碰 `simos-map/**`，且任务书禁止"在序列化层排序后输出"来掩盖（这一条的"内存态=落盘态"论证需要单独写）。
   - ⇒ 任务书自证项"逐模块都唯一（…map 各一行 digest）"**现状达不到**，且**不是本批能合的边界**：7/8 模块唯一，map 由上述预存量缺陷占据。
2. `simos-util`：`SimulationState` 的 `modules = Map.copyOf(modules)`
   - 现场：`simos-util/src/main/java/io/mosire/simos/util/state/SimulationState.java:20`；`TimeAdvance:144` 的类注已把它记作裁定 44（"迭代序不是键集的纯函数"）。
   - **实测不泄漏成字节**：`CheckpointEncoder:56` 用 `TreeMap` 收集模块、`TimeAdvance` 参与者用 `TreeMap`、`CommandBus`/`Replay` 显式排序 ⇒ **6 个 JVM 的 checkpoint `modules` 键序完全相同**。
   - ⇒ 判定"已由调用方兜住、无需改 util 状态机制"（任务书亦禁止改 `simos-util` 状态机制）。仅留痕。
3. `EconomyLiquidationSettlement` 的 6 处两键 `Map.of("principalMilli",…, "pledgeQuantity",…)`（`:762/776/791/807/823/838`）
   - 去处：`AuditEntry.evidence` → `ProductionLedger.LiquidationAudit`，而 `ProductionLedger` 是**当天瞬态读数**（`EconomyData:196` 明写"读数在当天的 `ProductionLedger` 里"），**不是状态组件** ⇒ 不进快照/变更集字节；键序只影响 TRACE 日志行文本。
   - **未修**（属"瞬态读数的建表序"，不是本批的"状态/落盘字节"缺陷；改了也只是换一种日志文本顺序）。**如实列为残留**：若要把"迭代序=内容纯函数"推广到全部表，这里是 6 处 + 一个小 helper。

## 8. 偏离记录（与任务书不一致之处，主动申报）

| # | 偏离 | 原因 / 性质 |
|---|---|---|
| D1 | 修在**生产者**（`EconomySeeder`）而**不是**入口（`EconomyPayloads`）规范化 | 任务书两种都允许。入口规范化会让**外来载荷**（GM/旧档）也按本仓规则重排序，超出本缺陷范围且是策略变更；生产者修法等同于既有 `factoryPrices()` 口径，改动最小 |
| D2 | **多修了 4 处 `split` 表**（④–⑦） | 同一条根因、同一文件同形制；虽对状态树不可见（`AllocationRule.Split` 是具名字段 record），但进 `economy.Seed` 载荷 JSON ⇒ `events.payload_digest` 不纯（§5 实测 2/6→1/6）。属"同类普查发现即修" |
| D3 | 第 ④ 处（trade 的 split）在建表处多缩进一级（原 `Map.of` 在 `industry(...)` 调用的末参位置） | 保持 google-java-format 既有的换行形态（仅方法名变长），未手工调行宽 |
| D4 | **未跑 `spotless:apply` / `verify`** | 任务书只允许 `-DskipTests compile`；格式按 gjf 既有形态手写（§10 列为未验证项） |
| D5 | **未能 claim `task-30`** | 任务板报"不是 active Agent Team 成员"；按任务书"不要卡住"继续执行并在此如实记录 |

## 9. 会改变数值行为的清单（预期：**空**）

**空**。逐条依据：本批只改"建表序"，未动任何算式/阈值/标定值；状态表**键集与值逐项相同**（§6 的 `valuePaths=0` 与顺序无关 digest 恒等）；30 天/120 天真跑末态的人口与顺序无关 digest 与修复前一致。唯一"行为面"的变化是**依赖迭代序的写序**（`grossByCommodity`/`netByCommodity` 等局部 `LinkedHashMap` 的插入序按新表序），其取值按商品各自独立、整数加法可交换 ⇒ 逐值不变（§6 实测）。

## 10. 会让既有测试失效的清单

- **预期：空**（没有任何测试断言这三张表的键序；`EconomyCodecTest.encodingIsByteLevelStableForEconomyData` 是"编码→解码→再编码逐字节相等"，与本次修改方向一致）。
- **但**：既有夹具里若有**跨 JVM 逐字节对照**的用例，它们**修复前就是 flaky 的**（在同一 JVM 内不抖，故本机很难红）；修复后**只会更稳**。
- ★ **我没跑任何测试/门禁**（任务书禁止）：`test`/`verify`/`package` 一律未跑 ⇒ 上表是**读断言得来**，不是实测。测试代理需要重点核：`EconomySeederTest`、`EconomyCodecTest`、以及任何 `EconomySeed` 载荷的**逐字节/digest 断言**（其期望值若取自"某一次运行"的字节，可能落在另一支序上——修后恒为**书写序**：grain→fiber / cloth→tool / fiber→tool）。

## 11. 未完成 / 未验证 / BLOCKED

1. **BLOCKED（边界）**：`map` 模块的数组序抖动（`Region.hexes = Set.copyOf`）——文件所有权禁碰 `simos-map/**`，且最小修法触及"集合语义 vs 落盘序"的设计口径 ⇒ **需控制方裁定/另开批**。任务书自证项"7 个模块 digest 各唯一"因此**只达成 6/7**。
2. **未验证**：`test` / `verify` / `package` / `spotless:check` **一律未跑**（任务书禁止）；格式是否 gjf-canonical **未验证**（`orderedTable(` 与 `Map.of(` 同形换行，行宽 < 100，但无工具背书）。
3. **未验证**：`events.payload_digest` 的"修复后唯一"**未端到端实测**（`ThreePowersWorld.state()` 不暴露 `economy.Seed` 的载荷文本）。已实测的替代物是**同一根因、同一条表序**的 `changeset_json`（2/6→1/6）与 §3.1 的代码链；**如需端到端**，测试代理可在命令入口抓 `payloadJson` 后 sha256 两次跨进程比对。
4. **未做**：GM 写路（`economy.UpsertIndustry`）与旧档读回的键序**未单独实测**——它们**按输入字节**决定序（本批不引入随机源，但也不做规范化，见 D1）。
5. **未做**：`EconomyLiquidationSettlement` 6 处瞬态 `evidence` 表（§7.3-3），已如实留痕。
6. **未落盘的历史证据**：修复前"同 JVM ×6 轮全部 1/6 唯一"那一轮的日志只在本会话终端输出，未写文件（其余每一组都有 `/tmp/koprobe/**` 日志与 `run<i>/*.json` 原件留存）。
7. **提交提醒（不是缺陷）**：`.superpowers/sdd/.gitignore` 是 `*`，本账本**未被 git 跟踪**（`git status` 看不到它）⇒ 控制方提交时需 `git add -f .superpowers/sdd/2026-10-10-key-order-determinism/fix-ledger.md`（与最近几个 SDD 目录同款：`git ls-files` 各 1 个强制入库文件）。
