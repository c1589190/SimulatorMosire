# Task 6 报告：`change` 包 —— `FieldDelta` / `MapChangeSet`

分支 `feat/m2-map-simos`，派单基线 `11a8de5`。需求来源：`task-6-brief.md`（签名、测试名、变异表、提交信息照抄）。
本任务解**铁律 5**：变更集从完整状态类型派生，`apply(changeSet, base)` 逐字段重建 target。

## 一 交付物

| 文件 | 内容 |
|---|---|
| `simos-map/src/main/java/io/mosire/simos/map/change/FieldDelta.java` | sealed 接口 + **四个** record：`Unchanged` / `Upsert(Map<String,T> entries)` / `Remove(Set<String> keys)` / **`Patch(Upsert<T> upserts, Remove<T> removals)`**；`changed()`、`lookup(String)` |
| `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java` | record，7 组件（hexes/regions/cities/terrainTypes/pathways/pathwayGroups/edges）；`between` / `apply` / `isEmpty` |
| `simos-map/src/test/java/io/mosire/simos/map/change/MapChangeSetTest.java` | 20 例 |

**与 brief 的三处差异，都记在这里**：

1. ★ **控制器裁定的一处修正（已在报告要求的范围内照办）**：`Remove.keys` 与 `Upsert.entries` 的容器不用
   `Set.copyOf`/`Map.copyOf`，改为**保序**的 `Collections.unmodifiableSet(new LinkedHashSet<>(…))` /
   `unmodifiableMap(new LinkedHashMap<>(…))`。理由是 `ImmutableCollections` 的迭代序**不是内容的纯函数**
   （Task 5 实测），否则变更集的字节会跨运行漂移。**这是对 brief 正文的唯一一处修正**，brief 其余各处（签名、
   测试名、变异表四行、提交信息）逐字照抄。
2. ★ **控制器裁定新增第四条变体 `Patch`**（本报告第一稿曾在 §六之二把它提成"待裁决"，裁决是**要修**，
   理由是"`between` 是铁律 5 的派生函数，`apply(between(b,t), b)` 必须对**任意** (b,t) 成立，而 Task 7
   的往返用例天生会造出'同一组件又增又删'这种对"）。形态按裁定用**嵌套形**
   `Patch(Upsert<T> upserts, Remove<T> removals)`：两侧复用已冻好的那两个 record，"非空/保序/冻结/null 校验"
   全部继承，`Patch` 自己只写两行 `requireNonNull`。**语义：先删后增**（= 先走 `Remove` 那一路、再走
   `Upsert` 那一路；两侧同键时增胜），`rebuild` 侧用**递归调用复用既有那两路**，不重实现。
3. ★ **实现形态上的一处展开**（不是语义修正）：构造器里"拷一份 + 冻一层"是**逐字展开**的，没抽 helper。
   理由见 §六之一——抽成 helper 会同时踩 SpotBugs 的两条（`EI_EXPOSE_REP` ×2 与 `UPM_UNCALLED_PRIVATE_METHOD` ×2），
   门禁要求 `BugInstance size is 0`。

**顺带删掉的一条死参数**：`diff` 原来收一个 `component` 字符串，只被"混合情形"那份异常消息用着；
混合情形改走 `Patch` 之后它没有任何用处，故**连同参数一起删掉**（`between` 的 7 个调用点相应少一个实参）。
"为不存在的世界写代码"的反面同样是"留着已死的参数"。

## 二 门禁

`./mvnw clean verify`（六个模块全跑，`task-6-evidence/gate-clean-verify.txt` 是本次的原始输出）：

```
UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos …… SUCCESS
[INFO] You have 0 Checkstyle violations.      （每模块各一行）
[INFO] BugInstance size is 0                  （每模块各一行）
[INFO] BUILD SUCCESS
```

用例合计：`simos-util` 156 + `simos-map` 152 + `simos-core` 15；`simos-map` 152 里 `MapChangeSetTest` 20 例。

## 三 变异实验室（7 轮，全部实测）

装置：`task-6-evidence/run.sh` + `mutate.py`，**逐字继承 Task 5 的 lab**，只换 `TARGET` 表与路径。
每轮都实测到：干净世界 OK（pristine 镜像 + md5 清单逐文件比对 + 文件数不多不少）→ 变异体落盘后
**md5 与原件不同**（自证）→ 断言**只有目标文件**变了 → `COMPILATION ERROR count = 0` →
`simos-map 跑过的测试类数 = 16`（不是 0，即"红"发生在断言上、不是构建挂在用例之前）。

**7 轮都是在加了 `Patch` 之后的源码上跑的**（`run.sh init` 重建过 pristine 与 md5 清单；第 1/2 轮的匹配串
相应去掉了已删的尾参），故下表与提交的字节一一对应。

| 轮次 | 变异（都落在 `map/change/MapChangeSet.java`） | 期望 | **实测红** | 红在哪一行 |
|---|---|---|---|---|
| m6v-1 | `between` 里 hexes 的比较 → 恒 `Unchanged` | 红 | ✅ 9 例 | **`betweenDetectsChangedHexValue:286`**（brief 表第 1 行点名的那条）、`betweenDetectsAddedHex`、`betweenDetectsRemovedHex`、`applyRebuildsTargetExactly:368`、`everyComponentIsComparedIndependently:344 [hexes 被改了 ⇒ 它必须不是 Unchanged]`、`deltasPreserveInsertionOrder:528`、`betweenDetectsAddedAndRemovedHex:599`、`mixedChangeRoundTrips:630`、`patchIsNotSilentlyHalfApplied:646` |
| m6v-2 | `between` 里 terrainTypes 的比较 → 恒 `Unchanged` | 红 | ✅ 3 例 | **`betweenDetectsChangedTerrainType:300`**（★ R-48-i 点名的那条）、`everyComponentIsComparedIndependently:344 [terrainTypes 被改了 ⇒ 它必须不是 Unchanged]`、`applyRebuildsTargetExactly:375` |
| m6v-3 | `between` 两边全等时返回 `null` | 红 | ✅ 4 例 | **`betweenIdenticalIsAllUnchanged:240`**（`assertThat(cs).isNotNull()`）、`betweenAndApplyRejectNullInputs:668`、`applyOfAllUnchangedReturnsBase:386`（NPE `cs`）、`emptyDiffStillEntersApply:433`（NPE，`allUnchanged` 是 null） |
| m6v-4 | `isEmpty()` → `!hexes.changed()` | 红 | ✅ 4 例 | **`emptyDiffStillEntersApply:444 [只改了 edges ⇒ 变更集**不得**为空]**（★ R-48-k）、`everyComponentIsComparedIndependently:351 [只改 regions ⇒ 变更集非空]`、`betweenDetectsChangedTerrainType:306`、`componentEmptiedIsRemoveNotUnchanged:415` |
| m6v-5 | `apply` 的 `base.spec()` → `GenerationSpec.defaults(0L)`（brief 表之外，自己加的） | 红 | ✅ 6 例 | **`applyTakesSpecFromBase:403`**（`expected: GenerationSpec[seed=42] / but was: GenerationSpec[seed=0]`）、`applyOfAllUnchangedReturnsBase:386`、`applyRebuildsTargetExactly:379`、`everyComponentIsComparedIndependently:352 [hexes 的单组件往返]`、`emptyDiffStillEntersApply:434`、`mixedChangeRoundTrips:632` |
| **m6v-6** | **`diff` 的混合分支只返回 `Upsert`（丢掉 `removals`）** | 红 | ✅ 3 例 | **`betweenDetectsAddedAndRemovedHex:599`**（"两侧都在"那两条断言）、**`patchIsNotSilentlyHalfApplied:646`**（被删的键还在）、`mixedChangeRoundTrips:630` |
| **m6v-7** | **`rebuild` 的 `Patch` 分支跳过 `removals`（只做 upserts）** | 红 | ✅ 2 例 | **`mixedChangeRoundTrips:632`**（往返对不上）、`patchIsNotSilentlyHalfApplied:646`（被删的键还在） |

**m6v-6 / m6v-7 就是为 `Patch` 这个新面配的自证**（控制器裁定"只加两轮，别铺开"）：一条掐"派生侧丢一半"
（`between`），一条掐"应用侧丢一半"（`rebuild`）——**两侧各丢一半都会红**，且红的是那三条新用例，不是别的。

### 三点说明（每点都是实测，不是推断）

1. ★ **R-48-k 的变异**（m6v-4）红在 **444 行**那条断言上，**不是** 433 行。这一点是本轮要害：
   433 行是"全 Unchanged 的变更集也要进 apply"，它在 `isEmpty() → !hexes.changed()` 之下**照样绿**
   （hexes 确实没变）——**若 brief 里 `emptyDiffStillEntersApply` 只有这一半，这条变异就是 no-op（白跑）**。
   真正抓住它的是我按裁定补的后半：**只改 edges、hexes 一字未动 ⇒ `isEmpty()` 必须为 false**。
   实测确认：433 行不在失败清单里，444 行在，且带 `.as("只改了 edges ⇒ 变更集**不得**为空")` 的描述文本。
   ⇒ **L1 的第四个成因（老仓 `MapResolver` 只在 `!diff.isEmpty()` 时调 `applyDiff`，而它的
   `isEmpty()` 排除 edges）现在有了正面的钉子。**
2. ★ **R-48-i 的变异**（m6v-2）红的**确实包含** `betweenDetectsChangedTerrainType`——因为该用例的夹具是
   "同 key 换了一个 `TerrainType`"，与 hexes 无关。三条 `betweenDetects*`（added/removed/changed-value）
   围着 hexes 转，另有一条专门钉 terrainTypes，故摘掉任一比较都有对应用例变红。
   另加一条 `everyComponentIsComparedIndependently`：7 个组件**逐个**"只有它变、其余六个必须 `Unchanged`，
   且单组件变更能独立往返"，把"某个组件整条漂移出去"（GSimulator 的 6 组件漂移就是这种形态）钉在**每一个** `diff` 调用点上。
3. **m6v-5 是 brief 表之外的第五轮**，为的是给 `applyTakesSpecFromBase` 一份判别力自证：`spec` 不进变更集，
   故"两边 spec 相同"的往返夹具**看不见** `apply` 从哪里取 spec；只有让两边 spec 分叉（base=42、target=99）
   才抓得住。实测该变异红，说明这条钉子不是装饰。
4. **`Patch` 的两轮都是"先红再问为什么红"**：m6v-6 红在 `betweenDetectsAddedAndRemovedHex` 的
   `isInstanceOf(Patch)` 与"`removals` 含 `2_-4`"上（派生侧把删除整个丢了），m6v-7 红在
   `mixedChangeRoundTrips` 的 `isEqualTo(target)` 与"被删的键还在"上（应用侧没删）。两条红的**理由都是被保护的那行本身**，
   不是编译错误、也不是别的组件连带。

（`rounds/*.kept` 是每轮的要点行；**只**砍掉了末尾 AssertJ 把整张 GameMap 打出来的 `expected/but was` 大段
——那份 dump 有 32KB 且无信息量。每轮的开头（干净世界/md5/编译计数/跑过的类数）与失败清单都原文保留。）

## 四 brief 之外补的用例（都在 18 例里）

brief 点名 10 条：`betweenIdenticalIsAllUnchanged`、`betweenDetectsAddedHex`、`betweenDetectsRemovedHex`、
`betweenDetectsChangedHexValue`、`betweenDetectsChangedTerrainType`、`applyRebuildsTargetExactly`、
`applyOfAllUnchangedReturnsBase`、`upsertCannotBeEmptyOrRemoveCannotBeEmpty`、`deltasAreImmutable`、
`emptyDiffStillEntersApply`。**全部落地且名字逐字一致。**

补的 10 条，各自的理由：

| 用例 | 理由 |
|---|---|
| `everyComponentIsComparedIndependently` | R-48-i 的一般化：7 个组件逐个钉，不只 hexes/terrainTypes 两处 |
| `applyTakesSpecFromBase` | R-48-e：spec 从 base 取，两边 spec 分叉才看得见（m6v-5 自证） |
| `componentEmptiedIsRemoveNotUnchanged` | "变为空"是 `Remove` 不是 `Unchanged`——GSimulator 混淆的正是这两者 |
| ★ `betweenDetectsAddedAndRemovedHex` | `Patch` 的派生侧：两侧**都在**，且单侧输入**不**产出 `Patch` |
| ★ `mixedChangeRoundTrips` | **本任务为 `Patch` 这个新面欠的账**：`apply(between(b,t), b).equals(t)`，其中 t 在同一组件上既加又删（铁律 5 在新情形上的原文）；顺带钉"先删后增"的键序 |
| ★ `patchIsNotSilentlyHalfApplied` | `Patch` 不许**只落一半**：被删的键真没了、被加的键真在、没动的键原样 |
| `deltasPreserveInsertionOrder` | 保序钉子的**冻结字面量**（不是"与源比"那种会假绿的写法），键数取 4 |
| `deltasRejectNullKeysAndValues` | 拒收口径连同**消息文本**一起钉住（形态 2 要求精确匹配）；含 `Patch` 两条 `requireNonNull` 的精确消息 |
| `lookupReadsOnlyFromUpsert` | `Unchanged`/`Remove` 的 `lookup` 一律缺席——这几条实现差别大，值得一条 |
| `betweenAndApplyRejectNullInputs` | 四个入口的 `Objects.requireNonNull` 都精确匹配消息（`hasMessage`，不是 `hasMessageContaining`） |

（第一稿里的 `betweenRefusesMixedUpsertAndRemoveInOneComponent` 随裁定**删掉**了：它钉的"当场抛"行为已不存在。）

## 五 我未能验证的

- **`Patch` 的"两侧同键"分支没有实测**：裁定规定"增胜"，且**明确不为重叠写守卫**（`between` 是唯一生产者、
  不可能产出重叠）。故 `Patch` 的两侧同键只能**手搓**出来，我没有为它写用例（写了就等于为不存在的输入写代码）。
  这一条是**按裁定有意留的**，不是遗漏。
- **跨运行字节稳定性只做到了"容器保序 + 冻结"这一步**：我没有跑"同一份数据在两次 JVM 运行里产出同一串字节"
  的探针（Task 5 的 `order-probe` 是那类探针的形态）。本任务钉的是**插入序**（`deltasPreserveInsertionOrder`
  用冻结字面量断言），而插入序是内容的纯函数——这一点是**推导**，不是本轮实测。
- **`MapChangeSet` 与 `GameMap` 的组件数对应关系是手写的 7 对 7**：反射版的枚举（新增 GameMap 组件时
  自动红）在 Task 7，本任务**没有**做。故"新增一个状态组件而忘了加进变更集"这类漂移，**现在还没有
  编译期或测试期的护栏**——这是铁律 5 的另一半，明确留给 Task 7。

## 六 顾虑（**推导**与**实测**分开）

### 之一：SpotBugs 把实现形态逼成了展开式（实测）

第一版把"拷一份 + 冻一层"抽成接口的 `private static orderedCopy(...)`，门禁实测报 4 条：

```
Low:    Private method FieldDelta.orderedCopy(Map, String) is never called      UPM_UNCALLED_PRIVATE_METHOD
Low:    Private method FieldDelta.orderedCopy(Set, String) is never called      UPM_UNCALLED_PRIVATE_METHOD
Medium: FieldDelta$Remove.keys() may expose internal representation             EI_EXPOSE_REP
Medium: FieldDelta$Upsert.entries() may expose internal representation          EI_EXPOSE_REP
```

两条的根因不同，但都指向"**把冻结藏进 helper**"：

- `EI_EXPOSE_REP`：`GameMap` 的类注释早就记过这一条——"SpotBugs 只认它**看得见**的
  `Collections.unmodifiableMap`"。我把 wrap 藏进 helper 的 `return`，于是两个 record 的访问器都被判成
  暴露内部表示。⇒ 改成在**赋值处** `entries = Collections.unmodifiableMap(copy);`，两条消失。
- `UPM_UNCALLED_PRIVATE_METHOD`：调用方是嵌套 record（`FieldDelta$Upsert`），被调方是接口
  （`FieldDelta`）的私有静态方法。**运行期确实被调用**（拒收 null 的用例就是靠它红的），
  是 SpotBugs 追不到这种跨类的私有接口方法调用。⇒ 干脆不抽 helper，两个构造器各自展开。
  代价：两段各 ~10 行的循环重复了一遍。**这是"门禁干净"换来的，不是设计偏好**；若将来允许
  SpotBugs 的 excludeFilter，这段可以退回去（本任务**没有**动门禁配置，`pom.xml` 一字未改）。

### 之二：`Patch` 变体（**已裁决并已实现**，此处只留结论）

第一稿把"同一组件又增又删"的归宿提成待裁决项（当时 `between` 当场抛 `UnsupportedOperationException`）。
**控制器裁决：要修**，理由比"下游要不要"更强——`between` 是铁律 5 的派生函数，`apply(between(b,t), b)`
必须对**任意** (b,t) 成立，而 Task 7 的往返用例**天生会造出这种对**（逐组件造分叉时，"同一组件里加一个键、
删另一个键"是最自然的造法），不改 Task 7 当场撞墙。

已按裁定实现（形态也用裁定给的**嵌套形**，不是第一稿建议的 `Patch(Map, Set)`）：

- **形态**：`record Patch<T>(Upsert<T> upserts, Remove<T> removals)` —— 两侧复用已冻好的那两个 record，
  "非空 / 保序 / 冻结 / null 校验"**全部继承**，`Patch` 只写两行 `requireNonNull`。
  裁定否掉 `Patch(Map, Set)` 的理由是那样会**第三次**展开"拷一份 + 冻一层"（三份逐字重复）。
- **语义**：**先删后增**；两侧同键时**增胜**。`rebuild` 侧写的是
  `rebuild(rebuild(base, patch.removals(), parse), patch.upserts(), parse)` —— **递归复用**既有那两路，
  不重实现（重实现就有与那两条纯情形分叉的可能，而 `Patch` 的正确性恰恰等于它们）。
- **不写重叠守卫**（裁定明确）：`between` 是唯一生产者、不可能产出重叠，为不存在的输入写守卫正是
  R-48-e 反对的"为不存在的世界写代码"。
- **`changed()` 用默认实现**（`Patch` 不是 `Unchanged`）⇒ `isEmpty()` 无需改动。
- **自证**：m6v-6（派生侧丢 `removals`）与 m6v-7（应用侧跳过 `removals`）两轮都实测红，红在那三条新用例上。

**第一稿里的一条判断被裁定推翻，记在这**：我当时认为"下游还没有生产者 ⇒ 无法评估代价 ⇒ 先记成待裁决"。
裁定指出这**不是下游要不要的问题，是本任务自己就欠的账**——`between` 作为派生函数的普遍性是它自己的合同，
不需要等消费者来证明。这个教训比这次的 4 行代码值钱。

### 之三：`terrainTypes` / `pathwayGroups` 的 key 是恒等（实测）

两处的 `keyOf` 与 `parse` 都是 `Function.identity()`（R-48-j）：这两张 map 的 key **本来就是 `String`**，
**不**给它们写凭空的 `parse`。代码里体现为 `STRING_KEY` 一处常量 + `apply` 侧两个调用点，Javadoc 写明了理由。

### 之四：`RegionIndex` / `spec` 不进变更集（实测）

- `RegionIndex` 是 `regions` 的纯函数 ⇒ 不进；`Region.boundary()` 是 `Region` 的**组件**，
  故随 `regions` 的值整体比对，**不单开组件**（`Region` 的 `equals` 覆盖到它，`applyRebuildsTargetExactly`
  的逐组件比对里就含它）。
- `spec` 是生成输入、不是可变更状态 ⇒ 不进；`apply` 显式写 `base.spec()`（不写 null 兜底，R-48-e）。
  `between` 的两边 spec 分叉时变更集为**空**——这是**有意**的，`applyTakesSpecFromBase` 把它钉住了。

## 七 证据清单（`.superpowers/sdd/2026-09-16-map-simos-plan/task-6-evidence/`）

| 文件 | 内容 |
|---|---|
| `run.sh` / `mutate.py` | 变异装置（逐字继承 Task 5；`TARGET` 表 7 轮全指 `map/change/MapChangeSet.java`） |
| `rounds/m6v-{1..7}.kept` | 每轮要点：干净世界、md5 自证、编译计数、跑过的类数、失败清单 |
| `gate-clean-verify.txt` | 冻结字节上的 `./mvnw clean verify` 原始输出（BUILD SUCCESS） |

## 提交

- `feat(map): change 包——与 GameMap 组件一一对应的变更集`（3 个文件、+893 行）
- `feat(map): change 包——与 GameMap 组件一一对应的变更集（补 Patch 变体）`（裁定后的第四条变体 + 三条新用例）
- `docs(sdd):` 两次（证据与报告，与 Task 5 同形）
