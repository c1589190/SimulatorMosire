# Task 2 报告：`EconomyData.relations`（第 8 组件）+ `RegimeRelations` 四档推导 + 载荷 `relation?`

**状态：完成** · 提交 **`93b4eb2`** `feat(economy): EconomyData.relations 第 8 组件 + RegimeRelations 四档推导 + 载荷 relation?（S1 阶段 4+5 T2）`
（19 文件 / +1451 −46）+ **`52202a0`** `feat(economy-api): Recipient 的 JSON 多态注解 + package-info 口径回填（S1 阶段 4+5 T2 前置）`

**门禁：`./mvnw -pl simos-economy -am verify` BUILD SUCCESS**（6 模块全 SUCCESS · `EconomySimos` **154/154** ·
`EconomyApiSimos` 23/23 · 每模块 `BugInstance size is 0` · checkstyle/spotless ✓ ·
surefire 报告 mtime `21:52:17` 落在本轮，21:52:19 读）
**证据：** `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/task-2-evidence/`（**34 个日志 + 3 份 md5**，共 37 个文件）

---

## 一、实现了什么

### 1. `EconomyData.relations`（第 8 组件；追加在 `allocations` 之后）

| 项 | 内容 |
|---|---|
| 键 / 值类型 | `Map<IndustryId, ProductionRelation>` |
| 缺键 | `null ⇒ Map.of()`（同其余七个组件；**空表 = 全归 `residualOwner` 的等价路径**，E9） |
| 冻结 | `LinkedHashMap` + `Collections.unmodifiableMap`，**写在字段赋值处**（同七张表的形制） |
| 新增写口 | `withRelations(Map<IndustryId, ProductionRelation>)` |
| **守卫 ①** | 键必须已有产业 ⇒ 否则 `关系指名的产业不存在: <id>` |
| **守卫 ②** | `relations[k].operator()` 必须 `equals(industries[k].operator())`（**两处拼写必须一致**） |
| **守卫 ③** | 键必须等于 `ProductionRelation.activity()`（同 `classes`/`flows` 的"键 == 值内 key"口径） |
| **有意不判** | cohort 侧的行是否存在（R3 明文；逐组件增量落盘 ⇒ **关系先到、行后到是合法写序**；人口为 0 的 cohort 解析不到行是**正常状态**） |
| 连带（铁律 5 强制） | `EconomyChangeSet` 第 8 分片 `FieldDelta<ProductionRelation>`（键解析 `IndustryId::parse`）+ `EconomySeedHandler` 追加路径 merge + 33 处 `new EconomyData(...)` 逐个补第 8 个实参 |

### 2. `RegimeRelations`（新建；形制逐字照 `RegimeOperators`）

签名照 brief：`defaultRelation(RegimeId, IndustryId, ActorRef) → ProductionRelation`；
`registered() → Map<String, RuleType>`（**保序四档**，值 = 该档**第一条**规则的规则类型，是**派生视图**、
不是第二份规则表）。★ 地点经 `IndustryHexKeys.hexKeyOf` 取（**唯一拼写点**），拿不到格键 ⇒ **抛**。

**四档各自推出的规则列表**（`priority` 写在括号里；`residualOwner` 四档都是 `operator`）：

| 档 | 规则（表序 = priority 序） |
|---|---|
| `feudal` | ①–④ 给养 `FIXED_IN_KIND_PER_LABOR` / `LABOR_AMOUNT` / 144 毫粮 / 粮 → 四个阶层 cohort 各一条（10）；⑤ 地租 `OUTPUT_SHARE` / `GROSS_OUTPUT` / 300‰ / 粮 → `(hex, landlord)`（20） |
| `household` | ①–④ 实物分成 `OUTPUT_SHARE` / `LABOR_AMOUNT` / 700‰ / **布** → 四个阶层 cohort 各一条（10） |
| `handicraft` | ①–④ 实物工资（同 household，率 600‰，布）（10）；⑤–⑧ 货币工资 `FIXED_MONEY_WAGE` / `FIXED_AMOUNT` / 1,000 毫钱 / **commodity 空** → 四个阶层 cohort 各一条（20） |
| `tenant` | ① 固定实物租 `FIXED_IN_KIND_RENT` / `FIXED_AMOUNT` / 20,000,000 毫粮 / 粮 → `(hex, landlord)`（10） |

★ 出厂值全带**量级依据 + 约束算式**的注释（信条十二：真值由 GM 按真档观察后调）：
`144 = ⌈10,000 毫粮（一人的周期口粮）÷ 69.6（一人的周期劳动 = 580‰ × 120 天 ÷ 1000）⌉`；
`700/600` 与 `EconomySeeder` 写进家庭纺织/作坊载荷的劳动权重**同值**；`20,000,000`（= 20,000 粮/周期）
≈ 一格一周期毛产 207,700 粮的 9.6%。

### 3. 载荷 `industries[].relation?`（`EconomyPayloads`；**推导只在载荷边缘**，同 D1）

- **缺键 ⇒** `RegimeRelations.defaultRelation(regime, id, industry.operator())`；
- **给了 ⇒** 逐值采纳，但 `operator` 与产业不一致 ⇒ **抛**；`residualOwner` 缺省取 `operator`、
  `rules` 缺省空表、`commodity` 缺省 = 货币档；`recipient` 恰给 `actor`（`{kind,id}`）或
  `cohort`（`CohortKey` 的**规范串**，如 `"0_0|landlord"`）之一（两个都没给/都给了 ⇒ 抛）；
  `ratePerMille`/`fixedAmount`/`priority` **三个整数必填**（不造缺省：率/额/次序被静默补 0 读起来像合法数据）；
- ★ **`activity` 不是载荷键**：关系的身份 = 它所在的那个产业（铁律 1，同一件事不许两处拼写）。

---

## 二、★ E2 怎么落的（`household` 档）

**规则列表实际是**：`SocialClassId.all()` 的**四个阶层各一条**
`OUTPUT_SHARE` / `Basis.LABOR_AMOUNT` / `ratePerMille = 700` / `commodity = cloth` / `priority = 10`，
**一条 `SELF_RETENTION` 都没有**；余下 300‰ 由 `residualOwner = operator` 表达（= 自留）。

**为什么这样就满足 V3**：受方是 `ToCohort(new CohortKey(hex, stratum))` —— 布落到**该格的那个阶层的
cohort** 上；而 operator 是 **actor**（`HOUSEHOLD:weave@hex`），它只拿 residualOwner 那 300‰。
`OUTPUT_SHARE × LABOR_AMOUNT` 的公式（T3）按**受方劳动 ÷ Σ劳动**分派 ⇒ **织布的人（`farm@hex|<阶层>`
行里的人口所属 cohort）拿到布**，而不是留在 actor 账上。★ 与 R7 的解析口径严格对齐：`weave@hex|*`
四行人口恒为 0 ⇒ 它们**永不是** cohort 受方 ⇒ 布不会落回那四行（I4.3 在 T6 读的就是这一条）。

**守着这条落地的用例**（判别力实测）：`theHouseholdRegimeSharesTheClothWithTheWeaversInsteadOfRetainingEverything`
（逐值 4 条 + `noneMatch(SELF_RETENTION)`）与 `theFourTablesExpressSelfRetentionAsTheResidualOwner`。
**变异体 M3**（把 household 写回 spec §六 的纯 `SELF_RETENTION`）⇒ 这两条 + `registered` 的标志类型那条
**当场红（3 条）**。

---

## 三、★ E4 的 `priority`：租那条怎么**显式**给 `(hex, landlord)` cohort

两档的租都是同一张 `rules` 表里的**一条普通规则**，受方写死为
`new Recipient.ToCohort(new CohortKey(hex, SocialClassId.LANDLORD))`：

- `feudal`：`OUTPUT_SHARE × GROSS_OUTPUT`，`ratePerMille = 300`，粮，**priority 20**；
- `tenant`：`FIXED_IN_KIND_RENT × FIXED_AMOUNT`，`fixedAmount = 20_000_000`，粮，**priority 10**。

**次序是数据**：给养/分成在前（10）、租与货币工资在后（20）。★ **如实记**：feudal 那两条的
`basis`（`LABOR_AMOUNT` / `GROSS_OUTPUT`）**都不读"已付"** ⇒ 在当前公式表下**次序无数值后果**，
它是给 `OPERATOR_SURPLUS` 那类规则留的数据位（spec §2.4 的"次序 = 数据"）。

**守着它的用例**：`theRentIsAddressedToTheLandlordCohortExplicitlyInBothRentPayingRegimes`（两档各一条）
+ `theCohortResidenceFollowsTheIndustrysHexKey`（非零格 `farm@3_-2` ⇒ 居住格必须 `3_-2`，写死 `(0,0)` 会红）。

---

## 四、★ E9 的处置：**选「不写这条规则」**（不给 `SELF_RETENTION` 挑商品）

**哪一档需要自留**：四档都要（余额都得有归属）⇒ 四档都**不写** `SELF_RETENTION`，一律由
`residualOwner = operator` 表达。

**为什么**：① `SELF_RETENTION` 属**实物档** ⇒ 按二选一守卫**必须带 `commodity`**（T1 的契约不动），
而"自留"在语义上是**对全部商品**的自留 —— 挑一个商品反而把话说小了（T1 的 `RuleType` 类注原文也把
"把自留写成没有这条规则"列为**等价**写法）；② T3 的公式表里 `SELF_RETENTION` 的数量**恒为 0**
（余额归 `residualOwner`）⇒ 写了不产生任何条目、还多一个要解释的商品字段。
★ 于是"改回一条 `SELF_RETENTION`"这种改动会在 `RegimeRelationsTest` 上当场红（M3 实测红了一条正是它）。

---

## 五、★ E10：`api/package-info.java` 的改动（**只改注释**）

原文写"本模块**只放共用契约**——稳定 ID、`CommodityId`，以及跨模块事件与转移意图"。补了四段：

1. ★★ **S1 阶段 4+5 起口径放宽一格**（E10）：本模块现在也放**关系契约的数据记录** —— 逐一 `{@link}`
   点名 `relation` 包五个类型 + `cohort.CohortKey`；★ 点明"它们**不是 ID**，是结算的**形状**"；
2. ★ **放宽的是"数据记录可以有"、不是"公式也可以有"**：理由 = 两侧切片（`economy` 算、`actor` 存）都要
   看得见，先例 = `LaborAllocation`/`LotChange`；★★ **"没有经济公式"那一句一字不改**（公式仍在
   `simos-economy` 的结算里；本包只有枚举、字段与构造期守卫）；
3. ★ **落点曾在 spec 里写错**（E3）：spec §三 把它列在 `simos-actor-api`，而那个模块主依赖为零 ⇒
   硬放会成 `actor-api → economy-api → actor-api` 循环；
4. （原有各段不动。）

★ **无代码改动**：本模块唯一的代码改动是 `Recipient` 的**两个注解**（见 §六.2 的同一提交）。

---

## 六、改了 / 新增哪些文件

### 1. brief 文件清单内的

| 文件 | 改动 |
|---|---|
| `simos-economy/.../EconomyData.java` | 第 8 组件 + 三条守卫 + `withRelations` + 类注（七个→八个） |
| `simos-economy/.../spi/EconomyPayloads.java` | `relation?` 解析 + 缺省推导 + `compensationRule` / `optionalText` 助手 + 类注（载荷形态样例） |
| `simos-economy/.../model/RegimeRelations.java` | **新建**（347 行，含 R8 表、E2/E4/E9 的裁定说明、四个出厂值的量级依据） |
| `simos-economy/.../model/RegimeRelationsTest.java` | **新建**（325 行 / 10 条用例） |
| `simos-economy/.../model/EconomyInvariantsTest.java` | +2 条用例（跨表守卫逐条 + 空表合法）+ 7 处构造点补参 |
| `simos-economy/.../change/EconomyRoundTripTest.java` | `mutate`/`changedOf` 各 +1 分支、`changeSetHasExactlySevenComponents` → `…Eight…`（7→8）+ 非派生关系夹具 |

### 2. ★ **超出 brief 文件清单的 5 处**（逐条给理由）

| 改动 | 为什么必须 |
|---|---|
| `EconomyChangeSet` 加第 8 分片 | **铁律 5 + 反射守卫**：`EconomyRoundTripTest` 断言"两侧组件集互为子集 + 恰 N 个"、且**豁免集必须是空集** ⇒ 不进变更集 = 当场红；且 T4 起 relations 的写入必须能落成 revision |
| `EconomySeedHandler` 追加路径 merge | 同一张表的按格追加口径（不 merge ⇒ **第二国播种时它的关系被静默丢掉**） |
| `EconomySettlement` 两处构造点补 `base.relations()` | 纯机械（8 元构造器）；**relations 原样带过**（T4 才读它） |
| `simos-economy-api/.../relation/Recipient.java` **+2 个 Jackson 注解** | ★ **T2 的往返硬前提**（M6 实测：去掉它 ⇒ `EconomyCodecTest` 5 个 error，全部是 `Cannot construct instance of Recipient (no Creators, like default constructor, exist): abstract types either need to be mapped to concrete types…`）。按本仓既定口径（`FieldDelta` 的 M4 裁定 4 / `AllocationRule`）选**类型上的注解**而不是 mixin（mixin 要在每台 mapper 上补注册，漏了是静默失效）。★ **为什么不留给 T1**：T1 时它还没有任何状态载体，改了也测不到；到 T2 它进了状态树 —— **现在不补，T4/T5 会撞上一颗地雷**（任何含非空 relations 的存档读不回来） |
| `simos-economy/.../codec/EconomyCodecTest.java` **+1 条用例** | 上面那两个注解的**唯一**落点：relations 的线格式里有第二个 sealed 多态（`Recipient`）与 `Optional` 的**空侧**（货币档）。★ 不加就是"装了护栏不跑 = 装饰"（AGENT.md §三） |
| `simos-economy-api/.../api/package-info.java` | E10（见 §五） |
| `simos-app` 两个夹具文件（`GuiApiTest` / `EconomyTestWorld`）各补第 8 个实参 | 8 元构造器的机械波及（同一份 brief 纪律"逐个核"） |

★ **没改**：`EconomySeeder`（真档载荷**缺 relation** ⇒ 走载荷边缘的推导，这正是设计要的）、
`RegimeOperators`、`Industry`、`harvest`/`settle` 的任何逻辑、`AllocationRule`。

---

## 七、★ 非派生夹具：用在哪条用例上、杀死了哪个变异体

> 本仓硬规矩："判别力来自**夹具**，不来自断言"。下列四处是**特意**写成非派生值的：

| 夹具 | 为什么必须非派生 | 杀死的变异体（实测） |
|---|---|---|
| `RegimeRelationsTest` 的**全部**期望值都是字面量（300 / 144 / 700 / 600 / 1,000 / 20,000,000 / `"grain"` / `"cloth"` / 逐条 priority） | 期望值若从 `RegimeRelations` 的常量再读一遍，"改出厂值"永远绿 | 夹具本身即判据；**M3**（household 改回纯 SELF_RETENTION）⇒ 3 条红 |
| `EconomySeedHandlerTest.aPayloadWithoutRelationDerivesTheDefaultFromItsRegime` 的制度取 **`tenant`（第四档）** | ★★ `PAYLOAD` 原本是 `feudal` —— **第一档** ⇒ "取第一条已登记档"会给出**同一个结果**（等价变异体） | **M2**：实测该用例红，且**既有 27 条照旧绿**（含所有 feudal 用例）⇒ 这就是"夹具决定判别力"的现场证据 |
| `EconomySeedHandlerTest.anExplicitRelationIsSeededValueForValue`：显式关系是 **550‰ / `middle_peasant` / priority 3**（与 feudal 推导的 5 条毫无共同之处），并前置断言"它 ≠ 推导值" | 若显式值恰好等于推导值，"读没读这个键"测不出来（I3.1 的同款教训） | 目标变异体 = "忽略显式键、一律重新推导"（**未单独跑**，如实记）；★ 该变异体的读数据 **M2 的失败输出**当场给出：重新推导时读到的是那 5 条 feudal 规则（含 144 / 300‰）⇒ 与本用例的字面量必然不同 |
| `EconomyRoundTripTest.mutate("relations")` 的 operator 取 **`HOUSEHOLD:house-7`**（本文件夹具 regime = `tenant` ⇒ 推导值是 `HOUSEHOLD:farm`） | 用派生值的话"重建点漏传 ⇒ 被重新推导"会读到**同一个值**而掩盖 | 目标变异体 = "relations 整个按 regime 重新推导"（**未单独跑**，如实记） |
| `EconomyCodecTest.relation(...)`：三条规则各是非派生值（300‰ / 5,000 毫布 / 7 毫钱 + `ToActor` / `ToCohort` / 货币档） | 空表或单变体 ⇒ 那两处线格式难点**永不被走到**（假覆盖） | **M6**（去掉 `Recipient` 的注解）⇒ 5 个 error（`Cannot construct instance of Recipient …`） |

---

## 八、TDD 证据

### RED ①（新类 `RegimeRelations`）

**命令**：`./mvnw -pl simos-economy -am test -Dtest=RegimeRelationsTest -Dsurefire.failIfNoSpecifiedTests=false`
**日志**：`task-2-evidence/log-RED-1-regime-relations.txt` · **rc=1**

```
[ERROR] COMPILATION ERROR :
[ERROR] .../model/RegimeRelationsTest.java:[49,9] cannot find symbol
[ERROR]   symbol:   variable RegimeRelations
[ERROR]   location: class io.mosire.simos.economy.model.RegimeRelationsTest
...（[76,9] [95,9] [118,9] …共 18 处）
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.16.0:testCompile
        (default-testCompile) on project simos-economy: Compilation failure
```
**为什么这个失败是预期的**：测试先写、实现尚无 ⇒ 只可能死在 `testCompile`（类型不存在），
**surefire 一条都没跑**（日志里没有任何 `Tests run`）—— 这正是"先证明断言要测的东西不存在"。

### RED ②（载荷边缘的 `relation?`）

**命令**：同上但 `-Dtest=EconomySeedHandlerTest` · **日志** `log-RED-2-payload-relation.txt` · **rc=1**

```
[ERROR] Tests run: 28, Failures: 4, Errors: 1, Skipped: 0, <<< FAILURE! -- in EconomySeedHandlerTest
[ERROR]   …aPayloadWithoutRelationDerivesTheDefaultFromItsRegime:553 [每个产业一条关系（缺键 ⇒ 推导）]
[ERROR]   …anExplicitRelationIsSeededValueForValue:624 [★ 逐值落盘（含显式 operator / residualOwner / 一条 550‰ 的规则）]
[ERROR]   …rejectsARelationWhoseOperatorDisagreesWithTheIndustry:593
[ERROR]   …rejectsARelationWhoseRuleShapeIsBroken:693->reasonOf:705 [坏关系载荷必须在命令边界被拒…]
[ERROR]   …relationOperatorAndResidualOwnerDefaultToTheIndustrysOperator:658 NullPointer …
```
**为什么是预期的**：此刻 `EconomyPayloads` 把第 8 个实参硬写 `Map.of()` ⇒ 缺省推导 / 显式采纳 /
两条拒绝判据**全都不存在**；而**既有 23 条一条没红** ⇒ 失败精确落在本任务的新行为上。

### GREEN（关键几轮，全部落在本轮 surefire 报告上）

| 轮 | 命令 | 结果 | 日志 |
|---|---|---|---|
| ① `RegimeRelationsTest` | `-Dtest=RegimeRelationsTest` | `Tests run: 10, Failures: 0` | `log-GREEN-1a-…pre-spotless.txt` |
| ② 载荷 | `-Dtest=EconomySeedHandlerTest` | `Tests run: 28, Failures: 0` | `log-GREEN-2-payload-relation-pre-spotless.txt` |
| ③ 不变量 / 往返 / 编解码 | 逐个类跑（一次一个类） | 40 / 5 / 15 全绿 | `log-GREEN-2a/2b/2c-…` |
| ④ 其余受影响类 | `EconomySettlementTest` 11 · `EconomyCycleBoundaryTest` 3 · `EconomyFlowCycleTest` 5 · `EconomySowingTest` 16 · `EconomyDebtTest` 11 · `EconomyLaborAllocationTest` 4 | 全绿 | `log-GREEN-3-<类名>.txt` |
| ⑤ **门禁（干净轮，变异体全部还原后）** | `./mvnw -pl simos-economy -am verify` | **BUILD SUCCESS** · `EconomySimos 154/154` · `EconomyApiSimos 23/23` · 6 模块成功 · 每模块 `BugInstance size is 0` · 报告 mtime `21:52:17`（本轮） | `log-VERIFY-economy-post-mutants.txt` |
| ⑥ app 侧（真播种器路径） | `-pl simos-app -am test-compile` + 逐类 | `EconomySettlementEndToEndTest` 14/14 · `EconomyRealScaleClothTest` 4/4 · `GuiApiTest` 33/33 · `WorldgenInitializeToolTest` 15/15 | `log-app-*.txt` |

---

## 九、变异自证（6 条；★ 每条：自答 → 实测红 → 还原后绿）

★ 纪律：变异体**按目标类名推入**（直接改目标文件，不是拷成另一个名字）；落盘后先 `md5sum` 证明
**字节与原件不同**；还原后再 `md5sum -c` 证明**逐字节相同**（`md5-baseline.txt` / `md5-final-post-spotless.txt`）。

| # | 变异体（打哪一层） | 期望 / 实测 | 日志 | 还原 |
|---|---|---|---|---|
| **M1** | `EconomyData` 的跨表守卫**放宽成"只查键在不在"**（删掉 operator 相等那一句） | 期望 `EconomyInvariantsTest` 新用例红；**实测恰 1 条红**（`relationsMustPointAtAnExistingIndustry…`，`Expecting code to raise a throwable`），其余 39 条绿 | `log-M1-crossTableGuardRelaxed.txt` | `md5sum -c` **OK** |
| **M2** | `RegimeRelations.defaultRelation` **不按 regime**、改"取第一条已登记档" | 期望 `EconomySeedHandlerTest` 逐值断言红；**实测恰 1 条红**（tenant 那条 ⇒ `but was` 里是 5 条 feudal 规则）★ **既有 27 条全绿** = brief 变异体②在 feudal 夹具上是**等价变异体**的实测证据；同一变异体下 `RegimeRelationsTest` **红 6 条** | `log-M2-firstRegisteredRegime.txt` / `log-M2b-…-onRegimeRelationsTest.txt` | `md5sum -c` **OK** |
| **M3** | `household` 档写回 spec §六 的**纯 `SELF_RETENTION`**（E2 的反面） | 期望红；**实测 3 条红**（E2 那条、E9 那条、`registered` 标志类型那条） | `log-M3-pureSelfRetentionHousehold.txt` | `md5sum -c` **OK** |
| **M4** | `EconomyPayloads` **删掉载荷边缘的 operator 一致性判据** | 期望 `rejectsARelationWhoseOperatorDisagreesWithTheIndustry` 红；★★ **首轮实测 rc=0（28/28 全绿，RED 缺席）** —— 状态层守卫接住了同一个载荷，断言只写"拒了"分不出是哪一层。**按本仓口径改断言不改实现**（拒因必须含载荷层那句 `relation.operator`）⇒ 再跑**恰 1 条红**（`to contain: "relation.operator"`） | `log-M4-…txt`（未红）/ `log-M4b-…-strongerAssertion.txt`（红） | 还原后 `log-GREEN-4-postMutants-seedHandler.txt` 28/28（★ 还原时**加了一段说明注释** ⇒ 该文件 md5 与变异前不同，见 `md5-final-post-mutants.txt`） |
| **M5** | `EconomyPayloads` 的缺省推导改成 **brief Step 3 的逐字写法**（再调一次 `RegimeOperators.defaultOperator`） | 期望：**被 R3 守卫自相矛盾地拒**；**实测 1 条 error**（`seedsAnExplicitOperatorValueForValue` ⇒ `HandlerOutcome$Rejected cannot be cast to HandlerOutcome$Applied` = I3.1「显式 operator」那条用例当场废）★ 这条把"我为什么偏离 brief 的伪码"从**推演**变成**实测** | `log-M5-briefLiteralDefaultOperator.txt` | `git checkout` 还原 ⇒ md5 与提交一致 + `log-GREEN-5-restored.txt` 28/28 |
| **M6** | `Recipient` **去掉类型上的 JSON 多态注解**（T2 加的那两个） | 期望解码当场炸；★ **首轮红在错的层上**（checkstyle `UnusedImports` ×2 —— 本仓"红在 checkstyle"第六次）⇒ 修好变异体（连 import 一起删）⇒ **5 个 error**，全是 `Cannot construct instance of Recipient (no Creators…): abstract types either need to be mapped to concrete types…` | `log-M6-…txt`（红在 checkstyle）/ `log-M6b-…-fixedMutant.txt`（红在规则上） | `git checkout` 还原 ⇒ md5 一致 + `log-GREEN-6-restored-codec.txt` 15/15 |

★ **残留检查**：`git grep --untracked "M1..M6 变异体"` 在三个模块里 **0 命中**；`git status` 与 HEAD **逐字节一致**。

---

## 十、自审发现（读自己的 diff 找出来的）

1. ★★ **M4 那一轮"绿得骗人"**（同一条事实的两层守卫）⇒ 已把断言钉到**载荷层那一层**的消息上，
   并在用例类注里写明"删掉任一层时另一层会接住，所以必须点名是哪一层"。**这是本轮最值得记的一条**。
2. ★★ **M5 那一轮推翻了我对 brief 伪码的"看起来等价"判断**：`defaultOperator(regime, id)` 与
   `industry.operator()` 只在**缺 operator 键**时同值，在"显式给 operator + 缺 relation"时会让
   **合法载荷被 R3 守卫自相矛盾地拒**（I3.1 用例当场废）。已改取 `industry.operator()` 并把理由写进
   `EconomyPayloads.relation` 的 javadoc。
3. ★ **`EconomyChangeSet.isEmpty()` 的语义变宽了一格**（现在含 `relations.changed()`）：这是**对的**
   （只改关系的 revision 不该被判成空），但它是**行为面上的可见变化**，记在这里。
4. ★ **守卫顺序**：键 ≠ `activity` 的判据排在"产业是否存在"之前 ⇒ 两个都坏时报的是 activity 那条。
   有意为之（身份错比悬空更根本），但要写下来。
5. ★ **`relations` 在 `EconomyData` 的守卫里用的是 `industriesCopy`**（不是冻结后的 `industries`）——
   两处同值，用后者会多一次 `unmodifiableMap` 的读；不影响语义。

---

## 十一、疑虑 / 未验 / 边界（如实记）

- ★★ **`registered()` 的返回类型**照 brief 的签名实现为 `Map<String, RuleType>`（值 = 该档第一条规则的
  类型，派生视图）。若控制方的本意是"档位 → 规则列表"，那这是一个**待裁定**的口径（改起来是一处小改动）。
- ★ **商品是出厂值的代价**：关系按 regime 推导、看不见 `outputPerUnit` ⇒ 若 GM 给一个 `household` 制的
  **农业**产业，推出的是"布"的分成规则，而该产业不产布 ⇒ T4 结算时**静默付 0**（不报错）。
  ★ 我**没有**加"规则的 commodity ⊆ outputPerUnit"这类守卫：E11 已裁定 `type × basis` 的组合守卫归
  T3 的公式表，同族的组合守卫不该在 T2 抢跑。**记为已知边界**，建议 T3/T4 在那张公式表或读口里收口。
- **未验**：`relations` 的**正读口**（GUI/MCP）—— 本任务没有读口改动，`ApiViews` 里读不到关系；
  真档播种后"每格每产业都有一条关系"的**逐值正读**留给 T6（T2 只能证明推导路径**必然被执行**且**未抛**：
  `EconomySettlementEndToEndTest` / `EconomyRealScaleClothTest` 走的就是真播种器载荷）。
- **未验**：全仓 `./mvnw clean verify`（本任务的门禁是 brief 指定的 `-pl simos-economy -am`）；
  本任务只额外跑了 app 侧 4 个相关类（§八 ⑥）。★ `simos-actor` / `simos-sd` / `simos-core` 对
  `EconomyData` 只有 javadoc 引用（`git grep` 实测 0 个 import）⇒ 8 元构造器的波及面 = economy + app。
- **未单独跑**的两个目标变异体已在 §七 表里逐条标注"未单独跑，如实记"。
- **`md5-final-post-mutants.txt`** 里 `EconomyPayloads.java` 的记录与 `md5-baseline.txt` 不同：
  **还原时加了说明注释**（不是变异体残留，已 `git grep` 证明 0 命中；该文件的最终字节 = 提交 `93b4eb2` 的字节）。
- ★ **E6（`ClassKey → CohortKey` 不合并）** 与 **V9 / I1.2 未达成** 与本任务一致（`CohortKey` 只作
  受方身份），如实随计划往下走；`AssetHolding` 未在真档种入 ⇒ `ASSET_QUANTITY` 仍只有夹具覆盖（E11）。
