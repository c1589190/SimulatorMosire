# Task 7 报告：往返框架 + 反射组件枚举 + 自证（M2 判据 4 / G13 / 铁律 5 的机械落地）

- 分支 `feat/m2-map-simos`，起点 HEAD `4bba61e`
- 交付提交：见文末「提交」
- 证据：`task-7-evidence/`（`mutate.py`、`run.sh`、`rounds/m7v-1..9.kept`、`gate-clean-verify.txt`）

---

## 一、交付物

| 文件 | 动作 | md5（原件参照，控制器可直接 `md5sum` 复核） |
|---|---|---|
| `simos-map/src/test/java/io/mosire/simos/map/change/RoundTripComponentsTest.java` | **新建**（5 个用例） | `a935eed43ee0842c5a1c0e80be72bab5` |
| `simos-map/src/main/java/io/mosire/simos/map/change/MapChangeSet.java` | **一字未动**（反射未查出缺口） | `dcfe2525ad7ef68647154d7633fa8414` |
| `simos-map/src/test/java/io/mosire/simos/map/change/MapChangeSetTest.java` | 一字未动 | `0241f4ad8aca82c35970a405c1ea3653` |
| `simos-map/src/main/java/io/mosire/simos/map/GameMap.java` | 一字未动 | `fc1431f9f0ea72fb6a10c866a1834710` |
| `simos-map/src/test/java/io/mosire/simos/map/GameMapTest.java` | 一字未动 | `01c008748417842a4ae7cb7c93b93bc7` |

★ **这张表是证据链的锚**：`rounds/*.kept` 里每轮记的 `md5 原件=…` 必须与上面这五个值逐一相等，否则那一轮的世界就不是交付的世界。上面五个值已当场复核（`md5sum` 输出与各 `.kept` 头部逐字相同）。

**反射没有查出缺口** ⇒ `MapChangeSet` 的 7 个组件与 `GameMap` 的 8 个组件（`spec` 有意豁免）**本来就是对得上的**，
`RoundTripComponentsTest` 一写就是绿的（改前基线 157 用例全绿，见每一轮 `.kept` 的「改前」段）。

### 用例清单（5 条）

| 用例 | 钉什么 |
|---|---|
| `everyGameMapComponentParticipatesInTheChangeSet` | ★ 反射枚举 `GameMap` 的 record 组件，逐组件造差异 + **三条断言** |
| `theExclusionListIsExactlySpec` | 钉死豁免集本身（`containsExactly("spec")`）—— 豁免口不是洞 |
| `everyChangeSetComponentCorrespondsToAGameMapComponent` | 反方向：变更集的每个组件在 `GameMap` 里都有同名者 |
| `changeSetHasExactlySevenComponents` | ★ R-48-g：`MapChangeSet.class.getRecordComponents().length == 7` |
| `specIsDeliberatelyExcludedFromTheChangeSet` | 显式钉 8 vs 7 的不对称 + **变更集组件 ∪ 豁免集 == GameMap 全部组件** |

### 控制器两处裁定的落实

- **裁定 1（R-48-l）**：循环体**三条**断言（不是两条），顺序与文案逐字照用派单给的代码块。
  第二条 `changedOf(cs, name)` 走**独立的** `switch`（`name → FieldDelta`），不依赖第一条。
- **裁定 2（R-48-o）**：`mutate` 与 `changedOf` **两个 `switch` 分开写**，`default` 都写成
  `throw new IllegalStateException("未登记的组件: " + name)`。**没有任何温和兜底**
  （`default -> base` / `default -> true` 一律不存在）——`m7v-3` 正是靠这个 `default` 红的。
  `mutate` 只登记变更集的 7 个组件、**故意不给 `spec` 写 case**：谁把 `spec` 从豁免集里拿掉，
  它就会以「未登记的组件: spec」当场响（而不是被静默放过）。

### U2 的连锁（brief 要求明写）

`RegionBoundary` 是 `Region` 的**组件**（U2）⇒ `MapChangeSet` **不需要**为边界新开组件：
`regions` 整个 `Region` 值被比对，而 `Region.equals` 逐组件含 `boundary`。
⇒ **组件数仍是 8 vs 7，`spec` 仍是唯一豁免项，V6 照旧。**
这句话同时写进了 `RoundTripComponentsTest` 的类注释，免得后人看「boundary 没进变更集」以为是漏掉的。

---

## 二、门禁

`./mvnw clean verify`（工作树，`gate-clean-verify.txt`）：**BUILD SUCCESS**

| 项 | 结果 |
|---|---|
| Spotless / Checkstyle | 0 violations（`You have 0 Checkstyle violations.` ×5 模块） |
| SpotBugs（`effort=More, threshold=Low`） | `BugInstance size is 0` ×5 |
| simos-map 用例 | **157**（Task 6 关账时 152 + 本任务 5），Failures 0 / Errors 0 |
| simos-util / simos-core | 156 / 15，全绿 |

**SpotBugs 是在 `clean verify` 里跑的**（不是 `mvn test`），本任务未加任何 excludeFilter、未改 `pom.xml`。

---

## 三、变异实验室：装置与纪律

- **装置**：`task-7-evidence/run.sh` + `task-7-evidence/mutate.py`（与 Task 4/5/6 同形，逐条继承），
  另加 Task 7 的两处形态修正，都在脚本注释里写明了理由：
  1. **变异全部在 `/tmp/m7lab/repo` 的副本上做**（派单明文要求）。每轮开头 `rsync` 一份**全新**副本
     （排除 `target/`）⇒ 上一轮的 `.class` 无从活到下一轮，「干净世界」由**重建**保证、由 md5 清单自证。
     `run.sh` 结尾打印 `git status --short`，每轮都只有一行 `?? RoundTripComponentsTest.java` —— 工作树一字未动。
  2. **跨文件共适应**：V3 的「加了字段、改了构造、忘了变更集」在现实中必然要动多个构造点，
     故每轮**显式声明**文件集合，自证改成「**除声明的文件外**无文件被改动」（声明写在 `.kept` 里，看得见）。
- **每轮的自证**（读结果**之前**）：① 副本与 `$ROOT` 的 md5 清单逐文件一致、文件数 95 不多不少；
  ② 落盘变异体的 md5 **与原件不同**（逐文件列出两个 md5）；③ 与清单不符的文件集合 **== 声明的文件集合**；
  ④ `grep -c "COMPILATION ERROR"` 为 0（否则当场作废）；⑤ simos-map 的用例**真的跑过**（17 个测试类，
  不是「构建挂在用例之前」）。
- **改前是真的跑在 HEAD 上**：改前 = 从工作树 rsync 的副本（`4bba61e` + 本任务新测试）。
  六条要求的改前（V1~V6）**各跑了一次**，原始输出都在各自 `.kept` 的「改前」段（每次都绿，
  也顺带证了确定性）。
- 变异体一律**写在目标类名的文件里**（不按变异文件名拷入），且都顺手清掉失效 import
  （留着的会撞 checkstyle `UnusedImports`，「红」就不是断言红了）。

---

## 四、逐轮结果（V1~V6 + 3 条补充轮）

期望栏是 **brief/派单写的预测**；「红在哪条断言」是**实测**。原始输出见同名 `.kept`。

| 轮 | 变异 | 声明文件数 | 编译错误 | 期望 | 实际 |
|---|---|---|---|---|---|
| m7v-1 | **V1** 删 `MapChangeSet` 的 `edges` 组件 | 1 | **1 ⇒ 作废** | 红 | **编译期**：`cannot find symbol: method edges()` ×4（见 §五） |
| m7v-2 | **V2** 删 `terrainTypes` 组件（留恒 `Unchanged` 占位访问器） | 1 | 0 | 红在第二条 | ★ 红在**第一条**（预测不符，见 §六） |
| m7v-3 | **V3** `GameMap` 加组件 `foo`，不进变更集（构造点全改到编得过） | 4 | 0 | 红 | ★ **测试期 · Error**（见 §五） |
| m7v-4 | **V4** `between` 的 `edges` 比较改成恒 `Unchanged` | 1 | 0 | 红在第二条 | ★ 红在**第一条**（预测不符，见 §六） |
| m7v-5 | **V5** `apply` 重建时丢掉 `edges` | 1 | 0 | 红在第三条 | ✅ **第三条**，正是预测 |
| m7v-6 | **V6** 豁免集加一项 `"edges"` | 1 | 0 | 红在 `theExclusionListIsExactlySpec` | ✅ 正是那一条，且**只有**那一条 |
| m7v-7 | 补充：V3 的「也加了 `case`」形态 | 5 | 0 | — | ★ **测试期 · Failure**，第一条断言 |
| m7v-8 | 补充：V3 的「只加组件、构造点一个不动」 | 1 | **1 ⇒ 作废** | — | **编译期** |
| m7v-9 | 补充：`changedOf` 的 `edges` 映射被复制粘贴错 | 1 | 0 | — | ★ **只有第二条红**，第一条绿 |

### 逐条：目标 / 期望 / 实际 / 红在哪条断言

**m7v-1（V1）** 目标：`MapChangeSet` 的 record 组件里删掉 `edges`（`between`/`isEmpty`/`apply` 跟着少一路，
`apply` 那一格用 `base.edges()` 顶上，并删掉随之失效的 `EdgeRef`/`EdgeTags` 两个 import）。
实际：**编译期接住**，4 处 `cannot find symbol: method edges()`：

```
[ERROR] .../MapChangeSetTest.java:[247,18] cannot find symbol
  symbol:   method edges()
  location: variable cs of type io.mosire.simos.map.change.MapChangeSet
[ERROR] .../MapChangeSetTest.java:[338,27]  （同上）
[ERROR] .../MapChangeSetTest.java:[442,18]  （同上）
[ERROR] .../RoundTripComponentsTest.java:[179,29] cannot find symbol  ← 本护栏自己的 name→访问器映射
[INFO] 4 errors
```

⇒ 按 brief 的规矩**本轮作废**（`COMPILATION ERROR count = 1`），**不算测试期护栏**。

**m7v-2（V2）** 目标：删 `terrainTypes` 组件，但**留一个恒 `Unchanged` 的占位访问器**
（组件真没了、调用点还在），把「删组件」这个方向推到测试期。实际：编译 0 错，红了 6 条，
其中本护栏 3 条：

```
Tests run: 157, Failures: 6, Errors: 0
  RoundTripComponentsTest.everyGameMapComponentParticipatesInTheChangeSet:88 [组件 terrainTypes 必须进变更集（漏了它 ⇒ 变更集整个为空）]
  RoundTripComponentsTest.changeSetHasExactlySevenComponents:124 [变更集的组件数必须是 7…]   ← 6 个
  RoundTripComponentsTest.specIsDeliberatelyExcludedFromTheChangeSet:144 [变更集组件 ∪ 豁免集 必须恰好覆盖…]  ← 少 terrainTypes
  （另 3 条在 MapChangeSetTest：applyRebuildsTargetExactly / betweenDetectsChangedTerrainType / everyComponentIsComparedIndependently）
```

**m7v-3（V3，主形态）** 目标：`GameMap` 加第 9 个组件 `Map<String,String> foo`
（record 头 + 紧凑构造器冻结 + `empty()` + `withFoo` + 7 个 wither 原样透传），
**把所有构造点改到编译得过**（`apply` 那一格传 `base.foo()` —— 这就是「忘了变更集」的形态），
`MapChangeSet` 一行不加。实际：编译 0 错，红在

```
RoundTripComponentsTest.everyGameMapComponentParticipatesInTheChangeSet:84->mutate:161
java.lang.IllegalStateException: 未登记的组件: foo          ← Error（不是 Failure）
RoundTripComponentsTest.specIsDeliberatelyExcludedFromTheChangeSet:144 [变更集组件 ∪ 豁免集 必须恰好覆盖…]
（另有 GameMapTest.componentCountIsExactlyEight / componentNamesAreFrozenList —— Task 5 的组件清单钉子）
```

**m7v-4（V4）** 目标：`between` 里 `diff(base.edges(), target.edges())` → `new FieldDelta.Unchanged<EdgeTags>()`。
实际：`RoundTripComponentsTest.everyGameMapComponentParticipatesInTheChangeSet:88
[组件 edges 必须进变更集（漏了它 ⇒ 变更集整个为空）] Expecting value to be false but was true` —— **第一条**（预测是第二条）。另红 3 条在 `MapChangeSetTest`。

**m7v-5（V5）** 目标：`apply` 里 `rebuild(base.edges(), cs.edges(), EdgeRef::parse)` → `base.edges()`。
实际：`everyGameMapComponentParticipatesInTheChangeSet:92 [组件 edges 的往返]`，
`expected: …edges={0_0|5_5=EdgeTags[byPathway={road={width=2}}]}… but was: …edges={}…`
—— **第三条**，与预测一致。

**m7v-6（V6）** 目标：`EXCLUDED_FROM_CHANGE_SET` 由 `Set.of("spec")` 改成 `Set.of("spec", "edges")`。
实际：**只有** `theExclusionListIsExactlySpec:99` 红（循环里 `edges` 被 `continue` 跳过，第一条/第二条/第三条全绿）
—— 豁免口的「加一项就能糊过 V1/V3」这条退路被钉死了，与预测一致。

**m7v-7（补充：V3 的 Failure 形态）** 目标：在 m7v-3 之上，把**两个 `switch` 都补上 `case "foo"`**，
并把 `GameMapTest` 的两条组件清单钉子按新世界更新到 9（「把提示你改的灯都改了」）。实际：

```
RoundTripComponentsTest.everyGameMapComponentParticipatesInTheChangeSet:88
  [组件 foo 必须进变更集（漏了它 ⇒ 变更集整个为空）] Expecting value to be false but was true      ← Failure
RoundTripComponentsTest.specIsDeliberatelyExcludedFromTheChangeSet:144 [变更集组件 ∪ 豁免集 必须恰好覆盖…]
（GameMapTest 与 MapChangeSetTest **全绿** —— 其它护栏都被更新到了新世界）
```

**m7v-8（补充：V3 的编译期形态）** 目标：只往 `GameMap` 的 record 头加 `foo`、紧凑构造器加一行冻结，
**任何构造点都不动**。实际：`COMPILATION ERROR count = 1` ⇒ **本轮作废**，
`constructor GameMap … actual and formal argument lists differ in length`（`GameMap.java:86/103/…`）。

**m7v-9（补充：隔离第二条断言）** 目标：`changedOf` 里 `case "edges" -> cs.edges();` 被复制粘贴成
`cs.pathwayGroups()`。实际：

```
RoundTripComponentsTest.everyGameMapComponentParticipatesInTheChangeSet:90
  [组件 edges 必须被 between 报成非 Unchanged] Expecting value to be true but was false   ← 只有第二条红
```

—— 第一条（`isEmpty()`）**是绿的**：`edges` 确实变了。故第二条**不是**第一条的影子。

---

## 五、★ V3 落在三层中的哪一层（如实分辨）

V3 的本体（m7v-3）= 「加了字段、改了构造、忘了变更集」＝ GSimulator 出事的那个形态。**它落在第 2 层：测试期 · Error**，
不是编译期、不是 Failure。三层我都**实测**过（不是推导）：

| 层 | 世界 | 实测轮 | 原始输出 |
|---|---|---|---|
| 1 编译期 | 只加 record 组件，构造点不管 | m7v-8 | `COMPILATION ERROR count = 1`；`required: …9 个参数… found: …8 个…`，`reason: actual and formal argument lists differ in length` |
| 2 **测试期 · Error** | 加组件 + **所有构造点改到编得过**（`apply` 传 `base.foo()`） | **m7v-3** | `everyGameMapComponentParticipatesInTheChangeSet:84->mutate:161` / `java.lang.IllegalStateException: 未登记的组件: foo` |
| 3 测试期 · Failure | 同上，**且两个 `switch` 都加了 `case "foo"`** | m7v-7 | `:88 [组件 foo 必须进变更集（漏了它 ⇒ 变更集整个为空）] Expecting value to be false but was true` |

**要点**：

- 第 1 层**不是**本护栏的功劳（`javac` 就接住了），按 brief **不计入测试期护栏**；m7v-8 那一轮**作废**。
- 第 2 层是 V3 最现实的形态：**现实里为了编过，`apply` 必须给 `foo` 补一个实参**，
  而它唯一能给的来源就是 `base.foo()` —— 于是「新字段只读、写不进去」这个漂移**编译期看不见**，
  由 `mutate` 的 `default` 抛出、消息带组件名而响。
- 第 3 层是**最像 GSimulator**、也是 R-48-l 存在的理由：连两个 `switch` 都补上了 `case`，
  **第一条断言**（`cs.isEmpty()`）照样红 —— 它**不依赖** name→访问器的映射，
  这正是「新组件在变更集里根本没有对应项」的正面钉子。
- m7v-3 与 m7v-7 里 `specIsDeliberatelyExcludedFromTheChangeSet` 的并集断言**也**红了
  （`变更集组件 ∪ 豁免集 必须恰好覆盖 GameMap 的全部组件`），是同一件事的第二条独立证据。
- m7v-3 里 `GameMapTest` 的两条钉子（Task 5 的 8 组件冻结清单）**也**红了 —— 如实记：
  那一轮并非「只有本护栏响」。m7v-7 把这两条钉子按新世界更新之后，**只剩本护栏红**，
  这才是「本护栏有独立判别力」的干净证据。

---

## 六、★ 与控制器预测不符的一处（如实报）

派单预测「V1/V2 → 第二条断言；V4 → 第二条断言」。**实测 V2/V4 都红在第一条**（V1 更早，落在编译期）。
根因是**结构性的、不是偶然**：

> 循环用的夹具是「base 只在一个组件上与 target 不同」的**单组件**夹具。而
> `MapChangeSet.isEmpty()` = 「7 个组件全 `changed()` 为假」。于是对该夹具：
> **`isEmpty()` 为假 ⟺ 那一个组件 `changed()`**。第二条断言一旦该红，`isEmpty()` **必然同时**为真
> ⇒ **第一条先响**，第二条根本没机会被求值。

也就是说：在**单组件**夹具下，第一条 ⊇ 第二条；第二条的独立判别力**打不出来**。
我把这件事单独做成 m7v-9（`changedOf` 的映射被复制粘贴错）：那时 `edges` 真变了（第一条绿），
而按名取到的那份是 `pathwayGroups`（第二条红）—— **第二条确实有独立判别力**，只是 V1/V2/V4
这三条变异**不是**能把它逼出来的形态。

**供控制器裁定的一点**：若想让 V1/V2/V4 这类「组件没进变更集」的漂移同时把第二条打出来，
夹具得让 base 与 target 在**多个**组件上不同（例如 base 先放一份非空夹具再单改一个组件），
但那样第一条就不再是「漏了它 ⇒ 变更集整个为空」的正面钉子了 —— 两条断言各自的理由会互相侵蚀。
本任务按派单给的循环体一字不改地实现，把实测差异如实报上来。

---

## 七、遗留与顾虑

1. **★ `MapChangeSet` 的类注释把把守者指错了类**（未改，仅报告）：
   `MapChangeSet.java:26` 写「本类型由 `{@code MapChangeSetTest}` 的**反射枚举**把守」，
   而 `MapChangeSetTest` 自己的类注释（`:32-33`）明说「反射式的逐组件枚举在 Task 7，本文件只做**非反射**的那一半」——
   真正的反射护栏是 `RoundTripComponentsTest`。**一个字（类名）的文档修正**，属 Task 6 的文件。
   我没有动它，两个理由：① 派单规定 `MapChangeSet.java` 「仅在反射查出真缺口时才动」，
   这不是反射缺口；② 更硬的理由是**证据链**：本报告第一节那张 md5 表与 `rounds/*.kept` 的
   `md5 原件=dcfe2525…` 是绑死的，动它会让控制器按 md5 复核时对不上。**建议控制器当场改掉**（一行），
   或留到下一步一并处理。
2. **`assertion 2` 与 `assertion 1` 的耦合**（§六）：夹具形态问题，不是实现问题，需控制器裁定是否要动夹具。
3. **`m7v-1`/`m7v-8` 两轮按 brief 作废**，不作护栏证据；它们只证明「这两个方向被 `javac` 接住」。
4. **豁免集里只有 `spec`，且只被一条用例钉死**：V6 证明了「往里加一项」会红；
   但「**同时**改 `EXCLUDED_FROM_CHANGE_SET` 与 `theExclusionListIsExactlySpec` 的期望值」这种**共谋式**改动，
   任何测试都拦不住（它已经是一次有意的决定了）。这是豁免口设计上的固有边界，不是缺陷 ——
   记在这里免得后人误以为它「绝对」。
5. **变异的可复现性**：`run.sh init` + `bash run.sh` 可在任意机器上复现全部 9 轮（4.3 s/轮 + 0.5 s 变异）。
   `.kept` 里的 md5 原件值锚在上述第一节那 5 个交付文件上，控制器可逐轮独立复核。

---

## 八、提交

| # | 信息 | 内容 |
|---|---|---|
| 1 | `test(map): 反射把守的往返框架——组件漂移即红` | `RoundTripComponentsTest.java`（5 用例） |
| 2 | `docs(sdd): Task 7 报告 + 变异实验室证据入库` | 本报告 + `task-7-evidence/`（`mutate.py`、`run.sh`、`rounds/m7v-1..9.kept`、`gate-clean-verify.txt`） |
