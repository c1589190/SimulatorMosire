# Task 6 报告：`change` 包 —— `FieldDelta` / `MapChangeSet`

分支 `feat/m2-map-simos`，派单基线 `11a8de5`。需求来源：`task-6-brief.md`（签名、测试名、变异表、提交信息照抄）。
本任务解**铁律 5**：变更集从完整状态类型派生，`apply(changeSet, base)` 逐字段重建 target。

## 一 交付物

| 文件 | 内容 |
|---|---|
| `simos-map/src/main/java/io/mosire/simos/map/change/FieldDelta.java` | sealed 接口 + 三个 record：`Unchanged` / `Upsert(Map<String,T> entries)` / `Remove(Set<String> keys)`；`changed()`、`lookup(String)` |
| `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java` | record，7 组件（hexes/regions/cities/terrainTypes/pathways/pathwayGroups/edges）；`between` / `apply` / `isEmpty` |
| `simos-map/src/test/java/io/mosire/simos/map/change/MapChangeSetTest.java` | 18 例 |

**与 brief 的两处差异，都记在这里**：

1. ★ **控制器裁定的一处修正（已在报告要求的范围内照办）**：`Remove.keys` 与 `Upsert.entries` 的容器不用
   `Set.copyOf`/`Map.copyOf`，改为**保序**的 `Collections.unmodifiableSet(new LinkedHashSet<>(…))` /
   `unmodifiableMap(new LinkedHashMap<>(…))`。理由是 `ImmutableCollections` 的迭代序**不是内容的纯函数**
   （Task 5 实测），否则变更集的字节会跨运行漂移。**这是对 brief 正文的唯一一处修正**，brief 其余各处（签名、
   测试名、变异表四行、提交信息）逐字照抄。
2. ★ **实现形态上的一处展开**（不是语义修正）：构造器里"拷一份 + 冻一层"是**逐字展开**的，没抽 helper。
   理由见 §六之一——抽成 helper 会同时踩 SpotBugs 的两条（`EI_EXPOSE_REP` ×2 与 `UPM_UNCALLED_PRIVATE_METHOD` ×2），
   门禁要求 `BugInstance size is 0`。

**形状的边界（需要控制器裁决的一条，见 §六之二）**：一条组件只能有一种变体。同一组件**又增又删**
（如"删掉 H_D、同时改 H_A"）时，`between` **当场抛 `UnsupportedOperationException`** 而**不静默丢弃任何一侧**。
brief 没规定这种输入的归宿；我选了"响"，理由与代价见 §六之二。

## 二 门禁

`./mvnw clean verify`（六个模块全跑，`task-6-evidence/gate-clean-verify.txt` 是本次的原始输出）：

```
UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos …… SUCCESS
[INFO] You have 0 Checkstyle violations.      （每模块各一行）
[INFO] BugInstance size is 0                  （每模块各一行）
[INFO] BUILD SUCCESS
```

用例合计：`simos-util` 156 + `simos-map` 150 + `simos-core` 15；`simos-map` 150 里 `MapChangeSetTest` 18 例。

## 三 变异实验室（5 轮，全部实测）

装置：`task-6-evidence/run.sh` + `mutate.py`，**逐字继承 Task 5 的 lab**，只换 `TARGET` 表与路径。
每轮都实测到：干净世界 OK（pristine 镜像 + md5 清单逐文件比对 + 文件数不多不少）→ 变异体落盘后
**md5 与原件不同**（自证）→ 断言**只有目标文件**变了 → `COMPILATION ERROR count = 0` →
`simos-map 跑过的测试类数 = 16`（不是 0，即"红"发生在断言上、不是构建挂在用例之前）。

| 轮次 | 变异（都落在 `map/change/MapChangeSet.java`） | 期望 | **实测红** | 红在哪一行 |
|---|---|---|---|---|
| m6v-1 | `between` 里 hexes 的比较 → 恒 `Unchanged` | 红 | ✅ 7 例 | **`betweenDetectsChangedHexValue:286`**（brief 表第 1 行点名的那条）、`betweenDetectsAddedHex`、`betweenDetectsRemovedHex`、`applyRebuildsTargetExactly`、`everyComponentIsComparedIndependently`、`deltasPreserveInsertionOrder`、`betweenRefusesMixedUpsertAndRemoveInOneComponent` |
| m6v-2 | `between` 里 terrainTypes 的比较 → 恒 `Unchanged` | 红 | ✅ 3 例 | **`betweenDetectsChangedTerrainType:300`**（★ R-48-i 点名的那条）、`everyComponentIsComparedIndependently:344 [terrainTypes 被改了 ⇒ 它必须不是 Unchanged]`、`applyRebuildsTargetExactly` |
| m6v-3 | `between` 两边全等时返回 `null` | 红 | ✅ 4 例 | **`betweenIdenticalIsAllUnchanged:240`**（`assertThat(cs).isNotNull()`）、`betweenAndApplyRejectNullInputs:604`、`applyOfAllUnchangedReturnsBase`（NPE `cs`）、`emptyDiffStillEntersApply`（NPE，`allUnchanged` 是 null） |
| m6v-4 | `isEmpty()` → `!hexes.changed()` | 红 | ✅ 4 例 | **`emptyDiffStillEntersApply:444 [只改了 edges ⇒ 变更集**不得**为空]**（★ R-48-k）、`everyComponentIsComparedIndependently:351 [只改 regions ⇒ 变更集非空]`、`betweenDetectsChangedTerrainType:306`、`componentEmptiedIsRemoveNotUnchanged` |
| m6v-5 | `apply` 的 `base.spec()` → `GenerationSpec.defaults(0L)`（brief 表之外，自己加的） | 红 | ✅ 5 例 | **`applyTakesSpecFromBase:403`**（`expected: GenerationSpec[seed=42] / but was: GenerationSpec[seed=0]`）、`applyOfAllUnchangedReturnsBase`、`applyRebuildsTargetExactly`、`everyComponentIsComparedIndependently:352 [hexes 的单组件往返]`、`emptyDiffStillEntersApply:434` |

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

（`rounds/*.kept` 是每轮的要点行；**只**砍掉了末尾 AssertJ 把整张 GameMap 打出来的 `expected/but was` 大段
——那份 dump 有 32KB 且无信息量。每轮的开头（干净世界/md5/编译计数/跑过的类数）与失败清单都原文保留。）

## 四 brief 之外补的用例（都在 18 例里）

brief 点名 10 条：`betweenIdenticalIsAllUnchanged`、`betweenDetectsAddedHex`、`betweenDetectsRemovedHex`、
`betweenDetectsChangedHexValue`、`betweenDetectsChangedTerrainType`、`applyRebuildsTargetExactly`、
`applyOfAllUnchangedReturnsBase`、`upsertCannotBeEmptyOrRemoveCannotBeEmpty`、`deltasAreImmutable`、
`emptyDiffStillEntersApply`。**全部落地且名字逐字一致。**

补的 8 条，各自的理由：

| 用例 | 理由 |
|---|---|
| `everyComponentIsComparedIndependently` | R-48-i 的一般化：7 个组件逐个钉，不只 hexes/terrainTypes 两处 |
| `applyTakesSpecFromBase` | R-48-e：spec 从 base 取，两边 spec 分叉才看得见（m6v-5 自证） |
| `componentEmptiedIsRemoveNotUnchanged` | "变为空"是 `Remove` 不是 `Unchanged`——GSimulator 混淆的正是这两者 |
| `betweenRefusesMixedUpsertAndRemoveInOneComponent` | 本类型的形状边界（§六之二），并断言**单侧**输入不抛 |
| `deltasPreserveInsertionOrder` | 保序钉子的**冻结字面量**（不是"与源比"那种会假绿的写法），键数取 4 |
| `deltasRejectNullKeysAndValues` | 拒收口径连同**消息文本**一起钉住（形态 2 要求精确匹配） |
| `lookupReadsOnlyFromUpsert` | `Unchanged`/`Remove` 的 `lookup` 一律缺席——这三条实现差别大，值得一条 |
| `betweenAndApplyRejectNullInputs` | 四个入口的 `Objects.requireNonNull` 都精确匹配消息（`hasMessage`，不是 `hasMessageContaining`） |

## 五 我未能验证的

- **`between` 的"混合增删"抛异常这条路径，没有下游消费者验证过**：M2 目前还没有
  `Command → ChangeSet` 的生产者（RiverBuilder/RegionRandomizer 等在 Task 8 之后）。若将来的生产者
  天然要同时增删同一组件，本类型的形状就要改（§六之二）。
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

### 之二：`between` 遇到"同一组件又增又删"会抛（**推导**的风险，已实测的部分在下）

`FieldDelta` 的三条变体表达不了"同一组件既增又删"（brief 的 sketch 如此，我照抄）。我的选择是
**`between` 当场抛 `UnsupportedOperationException`**，消息里带 `component` 名、`upserts=[…]`、`removals=[…]`，
并提示"拆成两条变更集先后 apply"。

- **为什么不静默选一侧**：丢"删"正是 GSimulator"只改了一条边产生空 diff"的病根（静默丢数据）；
  丢"增"同样不可接受。
- **为什么不把 `Upsert` 定义成全量替换**：那样"混合"就不存在了（一次 `Upsert` 带全量即可），
  但会**改掉 brief 对 `Upsert` 的语义**（"新增或覆盖"），且下游生产者（RiverBuilder 等）建的是**局部**
  upsert，在全量语义下会把没提到的条目抹掉——那是更隐蔽的静默数据损失。
- **代价（推导）**：若某个下游生产者天然要"删几条 + 改几条"一起提交，它必须拆两次 `between`/`apply`，
  而两次 apply 之间会经过一个**中间状态**。M2 现在还没有这类生产者，我无法实测这个代价有多大。
- **建议的最小修法（若要收口）**：给 `FieldDelta` 加第四条变体
  `record Patch<T>(Map<String,T> entries, Set<String> keys) implements FieldDelta<T>`（约 4 行），
  `between` 的混合分支返回它、`rebuild` 加一路（先删后覆盖）。**本任务没有擅自加**——那会改掉 brief
  规定的封闭变体集（sealed 的"封闭"是它的卖点），且反射式的变体枚举也还没有（Task 7）。**请控制器裁决**。

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
| `run.sh` / `mutate.py` | 变异装置（逐字继承 Task 5；`TARGET` 表 5 轮全指 `map/change/MapChangeSet.java`） |
| `rounds/m6v-{1..5}.kept` | 每轮要点：干净世界、md5 自证、编译计数、跑过的类数、失败清单 |
| `gate-clean-verify.txt` | 冻结字节上的 `./mvnw clean verify` 原始输出（BUILD SUCCESS） |

## 提交

`feat(map): change 包——与 GameMap 组件一一对应的变更集`（3 个文件、+893 行）；
证据与报告另起一个 `docs(sdd):` 提交（与 Task 5 同形）。
