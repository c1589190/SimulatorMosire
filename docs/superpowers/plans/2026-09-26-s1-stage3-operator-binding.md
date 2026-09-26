# S1 阶段 3：绑定 `Industry.operator` Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每个生产活动**显式绑定**一个经营主体；`regime` 只做**初始化推导**。本阶段**只绑不定** —— 产出归属（阶段 4）与结算（阶段 5）不在这里。

**Architecture:** 先建**唯一的推导表**（`RegimeOperators`：四档 + 未知档 fail-closed），再把 `operator: ActorRef` 加为 `Industry` 的第 16 个组件（**必填、null 即抛**），缺省只在**载荷边缘**补；最后暴露读口 + 钉住"不丢失"。

**Tech Stack:** Java 21 · `./mvnw` · JUnit 5 + AssertJ · 本仓 `record` + 构造期守卫 + Snapshot/ChangeSet/Codec 三件套

**Spec:** `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md`（§2.1 / §2.2 / **§六** / §十一）
**Breakdown:** `docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md` §三 阶段 3 + §四「2 → 3」

---

## ★ 先修正上游文档的六处（**已回代码核过**，2026-09-26）

**① spec §六 的默认 operator 写作 `ESTATE@hex`，代码里 actor 的 id 拼法是「产业 id」。** `EconomySeeder.appendAllocation` 写 `actor.id = industry.value()`（`farm@0_0`）；`ActorKind` 类注原文「`actor.id()` 就是该产业的 id」；`EconomyData` 的构造期守卫**按 `actor.id()` 把劳动归到产业**。⇒ 改用真 hex 会让同一个庄园拿到**两个 `ActorRef`**（劳动侧 / 经营侧），阶段 4 得在两种身份之间对照。**⇒ R3。**

**② 代码里 `feudal` 的注释写着"封建租佃"，spec §六 的 `feudal` 是"领主自营庄园" —— 同词两义。** `EconomySeeder.java:70`、`Industry` 的 `@param regime`、`AllocationRule.Split(700,300)` 类注三处同款；而 spec §六 把 `feudal` 定为**自营**、另立**新租佃档**（本阶段交付物）。★ 行为不冲突（`feudal` 默认 `ESTATE`，正合"自营"），冲突的是词 ⇒ 那三处是**留痕**（`AGENT.md` §五.4），**不改**；新档**另取名**（R5）并在此点名。

**③ breakdown 阶段 3 的"改动面"漏了「不丢失」的真风险面。** `EconomySettlement.withCycleState`（`EconomySettlement.java:1726`）是**结算每天重建 `Industry`** 的唯一一处（只传 15 个字段）—— 漏传 ⇒ **每个结算日静默重置**；测试侧另有 **3 处**手工重建（`EconomyCycleBoundaryTest` ×2、`EconomyRealScaleSeedBottleneckTest` ×1）。⇒ 单列 **Task 3**。

**④ spec §2.2 把 `Industry` 改名为 `ProductionActivity`，但七个阶段没有一段认领这个改名**（阶段 3 写「`Industry` 加 `operator`」，阶段 4 才用新名字）⇒ **本阶段不改类名**：结束时它**仍叫 `Industry`**。

**⑤ 随包的 `worlds/v17levant.json` 不含 economy 切片**（实测 4,228,111 字节，`economy`/`industries`/`regime` 零命中）⇒ §十.4 那条"`null ⇒ 空表`，**否则随包文件打不开**"的**理由对 economy 不成立**（它成立的是 map/social/unit）。**⇒ R2 的取舍依据。**

**⑥ `RegimeId` 没有词表**（`parse` 只校验非空白）⇒「四档」**不是**封闭集合，而 spec §六 **没写"未知制度怎么办"**。不是假想：`EconomyCodecTest.java:448` 现在就有 `new RegimeId("capitalist")`。**⇒ R1。**

---

## ★ 本计划的裁定（**请重点看这 6 条**）

### R1. 推导表唯一，且**未知制度 fail-closed**

新建 `RegimeOperators`（`simos-economy/model/`，形制照 `IndustryHexKeys`：`final` + 私有构造 + 纯函数），表是**唯一**一处：

| `regime` | 默认 `ActorKind` | spec §六 |
|---|---|---|
| `feudal` | `ESTATE` | 领主自营庄园 |
| `household` | `HOUSEHOLD` | 家户自给 |
| `handicraft` | `WORKSHOP` | 雇佣作坊 |
| **`tenant`**（本阶段新加） | `HOUSEHOLD` | 土地出租（佃农家户） |

★ **未登记的制度 + 缺 `operator` ⇒ 当场抛**（消息列出四档）。**理由**：否则"缺 `operator` 按 regime 补"会退化成"**随便哪个制度都能凭空得到一个经营主体**" —— 那正是"operator 是标签"被写成 fail-open。**新制度 = 新生产关系 ⇒ 必须显式说清谁经营。** **代价**：`RegimeId` 的开放性被**部分收窄**（新制度先登记）—— 这是**故意的**；`capitalist` 那类夹具改为**显式给 operator**（它本来就该显式，见 R4）。

### R2. 缺省推导的调用点只有一个：**载荷边缘**；`Industry.operator` 保持 fail-closed

`EconomyPayloads.industry()` 里 **缺键 ⇒ `RegimeOperators.defaultOperator(regime, id)`**（与 `progressDays` / `cycleLaborMilli` 的缺省同一处口径）；`Industry` 紧凑构造器 **`null` 即抛**。**理由**：现有 15 个组件**全都** null 即抛，为一个组件开 fail-open 口子 ⇒ **25 处构造点里任一处漏传都静默取默认值**，而"静默换成默认值"正是"显式绑定的 operator 被悄悄改回去"这个最难查的形态。**代价（如实记）**：**落盘于本阶段之前的 economy 归档**打不开（`Industry.operator 不得为 null`）。依据 ⑤ + §十.4「旧世界存档可作废」，这是**已被裁过**的取舍。★ **备选**（若控制方要覆盖归档边缘）：在 `Industry` 紧凑构造器里 `null ⇒ 推导`；**代价换了但不消失** —— null 合法 ⇒ 漏传**静默换值**（不崩），且 Task 3 的夹具纪律必须跟着重述。

### R3. 默认 operator 的 id 用**产业 id**，不用裸 hex

`defaultOperator("feudal", "farm@0_0")` ⇒ `ActorRef(ESTATE, "farm@0_0")`（★ id 是 `farm@0_0`，不是 `0_0`）。**理由**：与劳动侧（`appendAllocation` 写的 `actor`）**同字面** ⇒ 阶段 4 的"产出落 operator"与"劳动由谁收"指向**同一个** `ActorRef`，不必在两种拼法之间对照（`AGENT.md` 点名的"同一资源的两个形状"）。spec §六 的 `@hex` 记为**简写**：每格每制度**恰有一个**产业（`ActorKind` 类注），二者 1:1。

### R4. ★★ **不许**加 `regime` ↔ `operator` 的跨字段不变量

`operator` 与 `regime` 只在"缺省"这一条路上挂钩；**显式给了之后 `Industry` 不看 `regime`** ⇒ `feudal` + `HOUSEHOLD` operator 必须**照常构造成功**。**理由**（spec §2.4 原文）：「同一个 `feudal` 可以有 A 格地租 30% / B 格五五分成 / C 格领主直营 —— **制度可以渐变而不用先改产业类型**」。加了那种守卫，这条裁定作废，`capitalist` 夹具也失去覆盖。**这条同时是 I3.1 的结构性载体**："operator 不是标签"的**类型层证据**。

### R5. 新租佃档的字面量 = `tenant`

依据：`ContractId` 类注已写「劳动合同、**租佃合同**的稳定身份」；且全仓 9 处夹具本就在用 `new RegimeId("tenant")` 当"另一个制度"（R1 之前语义为空，此后**成真**）。★ 与 `feudal` 不冲突：它保留原义（默认 `ESTATE` = spec 的"领主自营"），见 ② 的留痕说明。

### R6. 读口形状 = `{"kind":"ESTATE","id":"farm@0_0"}`

与**同一份视图**里既有的 `actors[]`（`ApiViews.java:1106-1110`：`kind`/`id`/`laborMilli`）及**写侧**载荷的 `actor` **同形**。★ 不用 `ActorRef.toString()` 的规范串：那是**键**的形制，而 `operator` 在状态树里是**值** ⇒ 本阶段**不新增键类型**（"新键类型必须自带裸 `toString()` + 单参 `parse`"那条硬约束**不适用**）。

---

## Global Constraints

- **Java 21**；只走 `./mvnw`；注释与提交信息**中文**
- ★★ **验证命令用 `verify` 不是 `package`**：`spotless:check` 与 `spotbugs` 都绑在 **`verify`**（阶段 2 实测：brief 写 `package` ⇒ 格式违规一路积累到收尾才炸）
- **一次只能跑一个 Maven**；跑前 `pgrep -af "[s]urefirebooter|[c]lassworlds.launcher"`（★ 方括号技巧免自匹配假阳性）
- **测试一次一个类**（多类过滤会假绿）；`-pl X -am` 与 `-Dtest=Y` 并用要加 `-Dsurefire.failIfNoSpecifiedTests=false`
- **门禁 fail-closed**：看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，**不看 rc**
- **不 `git add -A`**；**机械改动逐个 `Edit`**（绝不用 `sed`/脚本批量 —— 本仓曾用脚本切片误删 10 个测试方法）
- ★★ **变异体必须真的打到被测的那一层** —— 写变异体前先自问"**若这条规则不存在，这个变异体还会红吗？**"（本仓已**三次**踩到：红在 POM 校验 / 红在 checkstyle / 打在不经 codec 的入口）
- ★★ **RED 要当场捕获**（回放补拍的 RED，时序证据弱）：跑红步骤必须在实现之前跑出真红，日志落 `.superpowers/sdd/2026-09-26-s1-stage3-operator-binding/`
- ★★ **新键类型必须自带"裸 `toString()` + 单参 `parse`"** —— 本阶段**不新增键类型**（R6）⇒ **不适用**；★ 但**不许**顺手把 `ActorRef` 变成 `FieldDelta` 的键（那会立刻触发这条约束）
- ★★ **不许放宽既有断言**；因本阶段变红的按新口径**手算重算**期望值（**算式写进注释**），**不许抄实际值**
- ★★ **25 处构造点 / 14 个文件**：逐处 `Edit`，**透传 `industry.operator()`**（重建点）或**显式给出**（夹具）；★ **不许**在重建点改用 `defaultOperator(…)` —— 那会把"漏传"退化成"重新推导"，抹掉 Task 3 的判据
- **`simos-economy` 现在可以改**（阶段 2 的"一行不改"**已失效**），但**只改本阶段需要的**
- ★ **拒绝 placeholder**：每步都要能真的写出来；★ 代码块的 Java **必须逐字照抄就能编译**（中文引号用 `「」`，**字符串字面量里绝不嵌 ASCII 双引号**）
- **收尾前台跑 `./mvnw clean verify`**，把 Reactor 逐模块结果抄进台账

## Review Focus

spec 隐含、但**没有任务显式覆盖**的四类：

1. **`operator` 该不该进 `EconomyData` 的构造期守卫**（如"必须是被声明的 actor"）—— 期望：**不进**。理由同 `ActorData` 既有口径「表与表之间没有引用完整性约束」是**故意**的（逐组件增量落盘 ⇒ 先落产业后落主体是合法写序）；存在性由装配期与命令面校验。★ `operator` **可以指向尚不存在的主体** —— 不是漏写守卫。
2. **同一个 `IndustryId` 认不出格**（`IndustryHexKeys.hexKeyOf` 返回 empty，如 id = `"farm"`）—— 期望：**推导照样成立**（`actor.id` 就是那个 id），**不抛**。"谁经营"与"产业在哪一格"是两件事。
3. **`operator.id` ≠ `industry.value()`**（如 `HOUSEHOLD:"house-7"`）—— 期望：**照常构造**（R4：不看 `regime`，更不看 id 拼法）。它是阶段 5「同制度不同关系」的入口。
4. **载荷里 `operator` 形状不对**（缺 `id` / 给了字符串 / 数组）—— 期望：**当场抛**。★ 但 `"operator":null` 与**缺键**在 Jackson 里**不可分** ⇒ 两者都走**缺省推导**（与 `progressDays` 既有口径逐字相同）。

---

## File Structure

| 文件 | 职责 |
|---|---|
| `simos-economy/src/main/.../model/RegimeOperators.java` | **新建**：`regime → 默认 operator` 的**唯一拼写点** |
| `simos-economy/src/main/.../model/Industry.java` | Modify：加第 16 个组件 `operator`（末尾）+ 守卫 + 类注 |
| `simos-economy/src/main/.../spi/EconomyPayloads.java` | Modify：`operator` 读侧 + 缺键推导 + 抽 `actorRef(JsonNode)` |
| `simos-economy/src/main/.../time/EconomySettlement.java` | Modify：`withCycleState` 透传 `operator` |
| `simos-app/src/main/.../gui/ApiViews.java` | Modify：`industryView` 发 `operator` |
| `simos-app/src/main/.../tools/read/CatalogTool.java` | Modify：`economy.Seed` 的载荷提示补 `operator?` |
| **14 个文件的 25 处 `new Industry(`** | Modify：末尾补第 16 个实参（清单见 Task 2 Step 4） |
| `simos-economy/src/test/.../model/RegimeOperatorsTest.java` | **新建**：Task 1 |
| `simos-app/src/test/.../world/S1Stage3TenancyTest.java` | **新建**：Task 5（I3.2 —— 只有 app 同时认识两切片） |

---

### Task 1: `RegimeOperators`（四档推导表 + 未知档 fail-closed）

★ **为什么它排在"加字段"之前**（与 breakdown 顺序相反）：`operator` 一旦成为**必填**组件，载荷边缘就**必须当场回答"缺键怎么办"** —— 而既有两处载荷（`EconomySeedHandlerTest.PAYLOAD`、`EconomySeeder` 产出物）**都没有** `operator` 键 ⇒「先加字段、后做推导」在本仓**不可独立成步**。⇒ **先有表，再加字段**（同阶段 2 的"先建契约、再接线"）。

**Files:** Create `simos-economy/src/main/java/io/mosire/simos/economy/model/RegimeOperators.java` + 同包 `RegimeOperatorsTest.java`

**Interfaces:**
- Consumes: `RegimeId`/`IndustryId`（`simos-economy-api`）、`ActorKind`/`ActorRef`（`simos-actor-api` —— `Industry` 已在 import 同模块的 `AssetKind`，**不加依赖**）
- Produces: `public static ActorRef defaultOperator(RegimeId regime, IndustryId industry)`；
  `public static Map<String, ActorKind> registered()`（**保序**）；
  常量 `FEUDAL`/`HOUSEHOLD`/`HANDICRAFT`/`TENANT`（四个 `String`）

- [ ] **Step 1: 写失败的测试**

```java
class RegimeOperatorsTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");

  /** ★★ spec §六 四档**逐值** + 裁定 R3 的 id 拼法。 */
  @Test
  void theFourDocumentedRegimesMapToTheirDocumentedKinds() {
    assertThat(RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM))
        .isEqualTo(new ActorRef(ActorKind.ESTATE, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("household"), FARM))
        .isEqualTo(new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("handicraft"), FARM))
        .isEqualTo(new ActorRef(ActorKind.WORKSHOP, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("tenant"), FARM))
        .as("★★ 本轮新加的租佃档：佃农家户经营（不是地主）")
        .isEqualTo(new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"));
    assertThat(RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM).toString())
        .as("★ R3：id 是产业 id、不是裸 hex（否则同一个庄园会有两个 ActorRef）")
        .isEqualTo("ESTATE:farm@0_0");
  }

  /** ★★ 裁定 R1：未登记的制度**不许猜**（fail-closed）；★ 字面量大小写敏感（本表不做归一）。 */
  @Test
  void anUnregisteredRegimeIsRefusedRatherThanGuessed() {
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(new RegimeId("capitalist"), FARM))
        .as("★ 现存反例：EconomyCodecTest 的 WageFirst 夹具正用 capitalist")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capitalist")
        .hasMessageContaining("tenant");
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(new RegimeId("Feudal"), FARM))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 登记面就是这四个（保序 = spec §六 表序）；null 不猜（同 `Industry` 一族构造期守卫）。 */
  @Test
  void theRegisteredTableIsExactlyThoseFourAndNullIsRejected() {
    assertThat(RegimeOperators.registered().keySet())
        .containsExactly("feudal", "household", "handicraft", "tenant");
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(null, FARM))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RegimeOperators.defaultOperator(new RegimeId("feudal"), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: 跑红（当场捕获）**

Run: `./mvnw -pl simos-economy -am test -Dtest=RegimeOperatorsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **FAIL** —— 编译错（`RegimeOperators` 不存在）⇒ 日志落台账

- [ ] **Step 3: 写实现**（下面五条全部是判据，逐条兑现；其余自由）

1. 类注明写"`regime → 默认经营主体` 的**唯一拼写点**"+"制度只负责初始化（spec §2.4）⇒ 本表只回答缺 `operator` 时填谁，**没有**反向查询"+"未登记即抛（R1）"；
2. `private static final Map<String, ActorKind> BY_REGIME` 用**静态初始化块 + `LinkedHashMap` + `Collections.unmodifiableMap`** 建（★ **绝不用 `Map.of`**：它的迭代序不是内容的纯函数，而错误消息要列出档位）；
3. `defaultOperator` 两个参数**各自判 null 即抛**，然后 `BY_REGIME.get(regime.value())`，`null ⇒` 抛
   `"未登记的制度，无法推导默认经营主体：" + regime.value() + "；已登记的档: " + BY_REGIME.keySet()`；
4. 返回 `new ActorRef(kind, industry.value())`（★ **id 取产业 id**，R3）；
5. `registered()` 返回 `BY_REGIME` 本身（不可变）。
   私有构造器 + `final class`；import 只需 `actor.api.actor.{ActorKind,ActorRef}`、`economy.api.id.{IndustryId,RegimeId}`、`java.util.{Collections,LinkedHashMap,Map}`。

- [ ] **Step 4: 跑绿** ⇒ `RegimeOperatorsTest` **PASS（3/3）**（以 surefire 报告核对真的跑了）

- [ ] **Step 5: 变异自证（两条，逐条当场捕获 RED）**

1. 表里 `HANDICRAFT → ActorKind.ESTATE` ⇒ 第一条**红**。
2. "未登记即抛"改成 `kind == null → return new ActorRef(ActorKind.ESTATE, industry.value())` ⇒ 第二条**红**。

⇒ 各 `Edit` 反向还原 ⇒ 复跑 ⇒ **绿**；实际输出抄进台账。

- [ ] **Step 6: 提交**

```bash
git add simos-economy/src/main/java/io/mosire/simos/economy/model/RegimeOperators.java \
        simos-economy/src/test/java/io/mosire/simos/economy/model/RegimeOperatorsTest.java
git commit -m "feat(economy): RegimeOperators —— regime 到默认经营主体的唯一拼写点（S1 阶段 3）"
```

★ 中间态：本类在 main 里**暂时没有调用点**（只有测试）。这是**故意**的顺序；Task 2 立刻接线。**不许**为"消除未使用"提前把它接进 `Industry`。

---

### Task 2: `Industry.operator` + 25 处构造点 + 载荷边缘缺省（★ 第一道关账点）

**Files:** Modify `simos-economy/src/main/.../{model/Industry,spi/EconomyPayloads,time/EconomySettlement}.java` + **14 个文件的 25 处 `new Industry(`**；Test `.../spi/EconomySeedHandlerTest.java`（加断言，**不改 `PAYLOAD`**）、`.../model/EconomyInvariantsTest.java`（加 R4 用例）

**Interfaces:**
- Produces: `Industry.operator() → ActorRef`（**第 16 个组件、追加在末尾** —— 与 spec §2.2 字段序一致）；守卫 `operator == null ⇒ 抛`；载荷新增**可选**键 `operator`：`{"kind":"ESTATE","id":"farm@0_0"}`

- [ ] **Step 1: 改 `Industry`**

在 `cycleInputUsedMilli` **之后**追加组件 `ActorRef operator`；`@param` 加：

```java
 * @param operator ★★ 经营主体（S1 spec §2.1 的 {@code ProductionOperator}）：**谁组织这次生产**。
 *     <p>★★ <b>它不是从 {@code regime} 派生的标签</b>（裁定 R4）：本类<b>不校验</b>两者的对应关系 ——
 *     同一个 {@code feudal} 可以有"A 格地租 30% / B 格五五分成 / C 格领主直营"（spec §2.4），
 *     那些差异**在数据里**，不在类型上。缺省推导只发生在**载荷边缘**，本类<b>不做推导</b>：
 *     收 null ⇒ 抛，与其余 15 个组件同口径。
```

紧凑构造器里加（与 `if (regime == null)` 同一族）：

```java
    if (operator == null) {
      throw new IllegalArgumentException("Industry.operator 不得为 null（缺省由载荷边缘按 regime 推导）");
    }
```

★ 组件放**末尾**：`record` 组件序不影响线格式（Jackson 按**名字**绑定）与 `equals`，而 spec §2.2 也列在最后 ⇒ 追加比插入的**误改面**小。

- [ ] **Step 2: 改 `EconomyPayloads`（读侧 + 缺键推导 + 抽 `actorRef`）**

```java
    // ★★ S1 阶段 3：经营主体。**缺键 ⇒ 按 regime 推导**（R1/R2；与 progressDays / cycleLaborMilli
    //   的缺省同一处口径）。★ 推导只在这一层发生 —— Industry 收了 null 是抛，不是补。
    JsonNode operatorNode = optionalObject(node, "operator");
    ActorRef operatorRef =
        operatorNode == null ? RegimeOperators.defaultOperator(regime, id) : actorRef(operatorNode);
```

★ **顺手消重**（同一形状的第二处）：把 `allocation()` 里内联的两参 `new ActorRef(ActorKind.parse(...), requireText(...))` 抽成私有 helper、**两处共用**；各字段自己的**拒因文案一字不改**（消息是契约）：

```java
  /** {@code {"kind","id"}}：主体引用（{@code allocations[].actor} 与 {@code industries[].operator} 共用**同一个**形状与解析）。 */
  private static ActorRef actorRef(JsonNode node) {
    return new ActorRef(ActorKind.parse(requireText(node, "kind")), requireText(node, "id"));
  }
```

★ `allocation()` 里那句 `optionalObject(node, "actor") == null ⇒ 抛（"劳动分配的字段 actor 必须是对象"）`**原样保留** —— 缺键在 `actor` 上是**拒**、在 `operator` 上是**推导**，口径不同，不许合并。

- [ ] **Step 3: 口径确认（写进注释，别踩）**

`"operator":null` 与**缺键**在 Jackson 里**不可分**（`optionalObject` 把 `isNull()` 与缺键一并收成 `null`）⇒ 两者都走**推导**。不是漏判：与 `progressDays` / `cycleLaborMilli` **逐字相同**。**形状给错**（字符串 / 数组）照旧**抛**。

- [ ] **Step 4: 逐处修 25 个构造点（逐个 `Edit`，绝不用脚本）**

| 文件 | 处 | 怎么给 |
|---|---|---|
| `economy/src/main/.../{spi/EconomyPayloads, time/EconomySettlement}.java` | 各 1 | 前者 Step 2 的 `operatorRef`；后者 ★ **`industry.operator()` 透传**（`withCycleState`） |
| `economy/src/test/.../model/EconomyInvariantsTest.java` | 7 | 派生（`tenant`） |
| `economy/src/test/.../time/EconomyCycleBoundaryTest.java` | 3 | ★ 两处**重建点**：`farm.operator()` 透传 |
| `economy/src/test/.../codec/EconomyCodecTest.java` | 2 | `tenant` 派生；★ **`capitalist` 显式给**（R1：未登记 ⇒ 不许猜） |
| `economy/src/test/.../time/{EconomySettlementTest 2, EconomySowingTest 1, EconomyFlowCycleTest 1, EconomyDebtTest 1}.java` | 5 | 派生（`feudal`） |
| `economy/src/test/.../{change/EconomyRoundTripTest, time/EconomyLaborAllocationTest}.java` | 各 1 | 派生（`tenant` / 按它的 `regime` 参数） |
| `app/src/test/.../world/EconomyTestWorld.java` | 1 | 派生（**不改它 8 参签名** ⇒ 8 个调用点零改动） |
| `app/src/test/.../{gui/GuiApiTest 2, world/EconomyRealScaleSeedBottleneckTest 1}.java` | 3 | 前者派生；★ 后者是**重建点** ⇒ `industry.operator()` 透传 |

★★ **判别力纪律**：通用夹具用 `defaultOperator(...)`（少写字面量）；**判据用例必须用显式非默认值**（Task 3 Step 1）—— 否则"漏传 ⇒ 重新推导"的变异体会**存活**（假绿）。

- [ ] **Step 5: 加断言（I3.1「写得进」+ I3.3「缺 operator 的旧载荷」）**

`EconomySeedHandlerTest`：★ **`PAYLOAD` 一个字都不改** —— 它**就是**"旧载荷"的真样本（写于本阶段之前、无 `operator` 键），比新造样本更硬。

```java
    // ★★ I3.3：既有 PAYLOAD **没有** operator 键 ⇒ 走 regime 推导。
    assertThat(industry.operator())
        .as("缺 operator 的旧载荷按 regime 补默认值（载荷边缘）")
        .isEqualTo(new ActorRef(ActorKind.ESTATE, "farm@0_0"));
```

再加一条**显式写入**用例（I3.1「写得进」）：复制同一份 PAYLOAD、在 `regime` 后插 `"operator":{"kind":"HOUSEHOLD","id":"house-7"}` ⇒ 断言逐值等于它**且不等于**推导值（★ 非默认值，否则"读没读这个键"测不出来）。

`EconomyInvariantsTest` 加一条（R4 的类型层证据）：

```java
  /** ★★ 裁定 R4：`regime` 与 `operator` 之间**没有**不变量 —— "operator 不是标签"的结构性证据。 */
  @Test
  void theRegimeDoesNotConstrainTheOperator() {
    Industry industry = /* 既有 tenant 夹具，第 16 个实参换成 new ActorRef(ActorKind.ESTATE, "estate-7") */;
    assertThat(industry.operator().kind()).as("制度是租佃、经营主体是庄园 ⇒ 照常构造").isEqualTo(ActorKind.ESTATE);
  }
```

- [ ] **Step 6: 跑受影响的测试（一次一个类，别合并）**

`RegimeOperatorsTest`·`EconomyInvariantsTest`·`EconomySeedHandlerTest`·`EconomyCodecTest`·`EconomyRoundTripTest`·`EconomyCycleBoundaryTest`·`EconomySettlementTest`·`EconomySowingTest`·`EconomyLaborAllocationTest`·`EconomyFlowCycleTest`·`EconomyDebtTest`·`GuiApiTest`·`EconomyRealScaleSeedBottleneckTest`·`EconomySeederTest`

Run 形如：`./mvnw -pl simos-economy -am test -Dtest=EconomyInvariantsTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 7: 变异自证（三条）**

1. `EconomyPayloads` 的 `defaultOperator(regime, id)` → `null` ⇒ `EconomySeedHandlerTest` **红**（证明"缺键推导"真在跑）。
2. `withCycleState` 的 `industry.operator()` → `null` ⇒ `EconomySettlementTest`/`EconomyCycleBoundaryTest` **红**（证明透传**被测**）。
3. ★★ **两处拼写的漂移守卫（既有，实测有效）**：把 `EconomySeeder.REGIME_FEUDAL` 的值改成 `"feudal_x"` ⇒ `EconomyRealScaleSeedBottleneckTest` **红**（真播种器载荷经真 handler ⇒ 未登记制度 ⇒ 不是 `Applied`）。★ 这条证明"推导表"与"播种器写的制度串"**已由一条既有端到端用例咬住**（`EconomyRealScaleSeedBottleneckTest:77-84`），**不需要**再加手维护清单式 anti-drift 断言。

⇒ 逐条还原 ⇒ 复跑 ⇒ **绿**。

- [ ] **Step 8: 关账点 —— 全仓门禁**：`./mvnw clean verify` ⇒ **BUILD SUCCESS**（13 模块；SpotBugs `BugInstance size is 0`；全部 surefire 报告 mtime 落在本轮）。★ 红了**不许往下走**。

- [ ] **Step 9: 提交**（提交信息里逐条列出改了哪 14 个文件的几个构造点）

---

### Task 3: 透传与「不丢失」（I3.1 的判据）

★ 本任务**不写生产代码**（透传已被编译器逼着做完）—— 它证明**那处透传真的在跑**，且显式绑定的 operator 不会在任何重建路径上被换掉。

**Files:** Test `simos-economy/src/test/.../{change/EconomyRoundTripTest, time/EconomySettlementTest, codec/EconomyCodecTest}.java`

- [ ] **Step 1: 写测试（★ 夹具必须是**非默认** operator）**

```java
  /**
   * ★★ I3.1「不丢失」：**显式绑定的、与制度默认值不同的** operator，走完一日结算仍是它。
   *
   * <p>★★ <b>为什么夹具必须是非默认值</b>：若用 `feudal ⇒ ESTATE:farm@0_0` 这个默认值，
   * 那么 `withCycleState` 漏传时会**被重新推导成同一个值** ⇒ 变异体存活、测试恒真。
   * 本用例取 `HOUSEHOLD:"house-7"`（制度 feudal、经营主体是**外来的家户**）—— 正是 R4 的"同制度不同关系"。
   */
  @Test
  void anExplicitOperatorSurvivesADayOfSettlement() {
    ActorRef household = new ActorRef(ActorKind.HOUSEHOLD, "house-7");
    EconomyData next = EconomySettlement.settle(fixtureWithOperator(FARM, household), day, period);
    assertThat(next.industries().get(FARM).operator())
        .as("★ 一日结算不得改写经营主体（defaultOperator 会给 ESTATE:farm@0_0 ⇒ 那样当场红）")
        .isEqualTo(household);
  }
```

`EconomyRoundTripTest` 加一条**变更集**往返：

```java
  /** ★★ I3.1「不丢失」的第二个面：operator 参与 `FieldDelta<Industry>` 的差异与重建。 */
  @Test
  void anOperatorThatIsNotTheRegimeDefaultSurvivesTheChangeSet() {
    EconomyData base = withIndustry(FARM, RegimeOperators.defaultOperator(new RegimeId("feudal"), FARM));
    EconomyData target = withIndustry(FARM, new ActorRef(ActorKind.HOUSEHOLD, "house-7"));
    EconomyChangeSet cs = EconomyChangeSet.between(base, target);
    assertThat(cs.industries().changed()).isTrue();
    assertThat(EconomyChangeSet.apply(cs, base)).isEqualTo(target);
  }
```

★ 判别力来自 `operator` 是 **record 组件**：`equals` / `FieldDelta` / Jackson 自动带上它（**没有**任何手写字段列表）。★ 若哪天有人把它改成派生访问器（`public ActorRef operator() { return RegimeOperators.defaultOperator(regime, id); }`），这条**当场红**。

- [ ] **Step 2: RED 的捕获方式（如实记）**

本任务无实现 ⇒ 直接跑 Step 1 两条：**绿**则立刻做 Step 3 的变异体把红点**当场**补出来（**不许回放补拍**）；**红**则说明 Task 2 的透传没做 ⇒ 回 Task 2 修。

Run: `./mvnw -pl simos-economy -am test -Dtest=EconomySettlementTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: 变异自证（三条）**

1. `withCycleState` 的 `industry.operator()` → `null` ⇒ 第一用例**红**（`Industry` 抛）。
2. `withCycleState` 的 `industry.operator()` → `defaultOperator(industry.regime(), industry.id())` ⇒ 第一用例**红且红在断言**（不是构造期）。★ 这一条**必须试**：它证明"夹具用非默认值"这条纪律是**承重的**（★ 若这一变异体**存活**，如实记下来 —— 判别力不在你以为的地方）。
3. 删掉 Step 4 的 codec 断言 ⇒ **如实记**它红在哪一条。

- [ ] **Step 4: codec 过线断言**（`EconomyCodecTest.deltaValuesSurviveAsIndustryWithCommodityKeys` 里**只加**一行，既有断言一字不动）

```java
    assertThat(farm.operator())
        .as("★ S1 阶段 3：经营主体必须真的过线（缺它 ⇒ 往返后 operator 没了）")
        .isEqualTo(new ActorRef(ActorKind.ESTATE, "farm@0_0"));
```

- [ ] **Step 5: 提交**

---

### Task 4: 读口暴露 `operator`（I3.1「读得出」）

**Files:** Modify `simos-app/src/main/.../gui/ApiViews.java`（`industryView`，第 473 行起）、`.../tools/read/CatalogTool.java`；Test `simos-app/src/test/.../gui/GuiApiTest.java`

**Interfaces:** Produces: `GET /api/economy/hex` 与 MCP `simos.economy.hex` 的 `industries[].operator = {"kind","id"}`

★ **一处改动覆盖两条读口**（`AGENT.md` §8.3：GUI 与 MCP 读工具**共用** `ApiViews` 这一份视图；`EconomyHexTool → ToolSupport.economyHex → ApiViews.economyHex`）⇒ **不在路由层另拼一份**。

- [ ] **Step 1: 写失败的断言**（`GuiApiTest` 既有读口用例里，既有断言不动）

```java
    assertThat(industry.get("operator").get("kind").asText())
        .as("★ S1 阶段 3：经营主体读得出来（形状与 actors[] / 写侧 actor 同形）")
        .isEqualTo("ESTATE");
    assertThat(industry.get("operator").get("id").asText())
        .as("★ id 是**产业 id**，不是裸 hex（裁定 R3）")
        .isEqualTo("farm@1_1");
```

Run: `./mvnw -pl simos-app -am test -Dtest=GuiApiTest -Dsurefire.failIfNoSpecifiedTests=false` ⇒ **红**（当场捕获日志）。

- [ ] **Step 2: 实现（`industryView` 里紧跟 `regime`）**

```java
    view.put("regime", industry.regime().value());
    // ★★ S1 阶段 3：把经营主体发出来（同视图的 actors[] 与写侧载荷的 actor 同形：{kind,id}）。
    Map<String, Object> operator = new LinkedHashMap<>();
    operator.put("kind", industry.operator().kind().name());
    operator.put("id", industry.operator().id());
    view.put("operator", operator);
```

★ **不要**折算成 `ActorRef.toString()` 的规范串（R6：那是**键**的形制）。

- [ ] **Step 3: `CatalogTool` 的提示串补 `operator?`**：`economy.Seed` 那条串里 `...industries[{id,name,regime,...}]` 改成 `industries[{id,name,regime,operator?{kind,id},...}]`，串尾补一句「★ `operator` 缺省 ⇒ 按 regime 推导：`feudal→ESTATE` / `household→HOUSEHOLD` / `handicraft→WORKSHOP` / `tenant→HOUSEHOLD`」。★ 这是给人/模型看的提示、不参与执行；但 `PAYLOAD_HINTS` 有"覆盖全部已注册 type"的构造期护栏 ⇒ **别动 `economy.Seed` 这个 key**（改了 key 就红）。

- [ ] **Step 4: 跑绿** ⇒ `GuiApiTest` **PASS**（以 surefire 报告核对）

- [ ] **Step 5: 变异自证**：删掉 Step 2 的三行 `view.put("operator", …)` ⇒ `GuiApiTest` **红** ⇒ 还原 ⇒ 复绿。

- [ ] **Step 6: 提交**

★ **不做**：前端面板（`webui/panels.js` + `economy-panel.test.cjs`）**不加** `operator` 列 —— 本阶段判据读的是**读口**（API/MCP）而非面板；而加一列要同步动 `run-gate.cjs` 的 `MIN_TESTS` 与 `gate-contract.test.cjs` 的 `MIN_ASSERTIONS`（**两处同值**，`AGENT.md` §六.5）⇒ 收益为零、改动面 ×3。

---

### Task 5: I3.2 —— 租佃档 + `AssetOwner ≠ Operator`（端到端）

★ **为什么只能住 app**：`simos-actor` 被 enforcer 禁止依赖 `simos-economy`（同层切片互不依赖），`simos-economy` 也不依赖 `simos-actor` ⇒ **只有 `simos-app` 同时认识两切片**（`ApiViews` 就是这么做的）。

**Files:** Create `simos-app/src/test/java/io/mosire/simos/app/world/S1Stage3TenancyTest.java`

**Interfaces:** Consumes `EconomySeedHandler`（真 handler，照 `EconomyRealScaleSeedBottleneckTest:284-292` 的 `REF` / `snapshots(...)` 写法）、`ActorData`/`Actor`/`AssetHolding`/`AssetHoldingKey`/`AssetClassKey`（`simos-actor`）

- [ ] **Step 1: 写测试**

```java
class S1Stage3TenancyTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final AssetClassKey ARABLE_B =
      AssetClassKey.land(Map.of("arable", "true", "quality", "B"));

  /** ★★ I3.2：租佃档**存在**（登记在推导表里），且默认经营主体是**佃农家户**、不是地主。 */
  @Test
  void theTenancyRegimeIsRegisteredAndDefaultsToTheTenantHousehold() {
    Industry industry = seededIndustry(RegimeOperators.TENANT);
    assertThat(industry.operator().kind())
        .as("★ 佃农家户经营（spec §六 第四行）—— 不是 ESTATE")
        .isEqualTo(ActorKind.HOUSEHOLD);
    assertThat(industry.operator()).isNotEqualTo(ESTATE);
  }

  /**
   * ★★ I3.2 的核心：`AssetOwner ≠ Operator` 不只是"可表达"，在本用例里**已经成立**。
   *
   * <p>两个正交事实各写各的：产权在 {@code ActorData.holdings}（主体 = 庄园），
   * 经营在 {@code Industry.operator}（主体 = 佃农家户）。**没有任何一处把二者绑起来**。
   */
  @Test
  void theAssetOwnerIsNotTheOperator() {
    Industry industry = seededIndustry(RegimeOperators.TENANT);
    ActorData data =
        ActorData.empty()
            .withActor(new Actor(ESTATE, "庄园"))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L));

    assertThat(data.holdings().keySet())
        .as("★ 这块地归庄园 —— 「谁的地」只有一个答案")
        .allMatch(key -> key.owner().equals(ESTATE));
    assertThat(data.holdings().keySet())
        .as("★★ 反向：经营者名下**一条产权都没有** ⇒ 两个事实互不牵连")
        .noneMatch(key -> key.owner().equals(industry.operator()));
    assertThat(industry.operator())
        .as("★★ 同一格：地是庄园的、活是佃农家户干的（spec §2.3 的原文形状）")
        .isNotEqualTo(ESTATE);
  }

```

★ 真载荷（**没有** `operator` 键）+ 真 handler 的 helper，照下面拼（`REF` / `snapshots(...)` 抄 `EconomyRealScaleSeedBottleneckTest:284-292`）：

```java
  private static Industry seededIndustry(String regime) {
    String payload =
        "{\"mapId\":\"tenancy\",\"rulesVersion\":\"aggregate-v1\",\"entries\":[{\"q\":0,\"r\":0,"
            + "\"industries\":[{\"id\":\"farm@0_0\",\"name\":\"农业\",\"regime\":\"" + regime + "\","
            + "\"cycleDays\":120,\"capacityPerUnit\":{\"LAND\":1000},\"laborPerUnit\":143,"
            + "\"allocation\":{\"@class\":\"split\",\"meansWeightPerMille\":700,\"laborWeightPerMille\":300},"
            + "\"slots\":[{\"id\":\"poor_peasant\",\"name\":\"贫农\",\"laborParticipationPerMille\":950}]}]}]}";
    SimulationState empty =
        new SimulationState(
            new StateMeta(REF, SimosTimestamp.of(0)),
            snapshots(EconomyData.empty()),
            InMemoryInfoSystem.empty());
    HandlerOutcome outcome = new EconomySeedHandler().handle(empty, payload);
    assertThat(outcome).as("真载荷必须被真 handler 接受：%s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return EconomyChangeSet.apply(
            (EconomyChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), EconomyData.empty())
        .industries()
        .get(FARM);
  }
}
```

★ `classes` / `outputPerUnit` / `cycleInputPerUnit` 在本载荷里**都省略**（`optionalArray` / `optionalObject`）—— 只保留 `slots`（必填）与 `capacityPerUnit`（构造期要求非空）。
★ **actor 侧用 `ActorData` 直接构造、不走 `ActorSeedHandler`** —— 本判据要证的是"**模型能表达**两个正交事实"，而 `ActorData` 的构造期守卫（键必须等于值内 `key()`）已把"产权挂谁名下"钉死；再套载荷解析只会把它与 actor.Seed 的载荷校验耦合（那两个面的护栏已各自存在）。

- [ ] **Step 2: 跑红（当场捕获）**

`tenant` 已在 Task 1 登记 ⇒ 两条**预期直接绿** ⇒ **RED 由 Step 4 的变异体补**，如实记「本用例的 RED 来自变异体、不是实现缺口」（**不许**把补拍写成"先红后绿"）。

- [ ] **Step 3: 跑绿** ⇒ `S1Stage3TenancyTest` **PASS（2/2）**

- [ ] **Step 4: 变异自证（两条，各打一条判据）**

1. 表里 `TENANT → ActorKind.ESTATE` ⇒ 第一条**红**，且第二条末一条断言**也**红（两条都记）。
2. 第二条夹具里的 `new Actor(ESTATE, …)` 换成 `new Actor(new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"), …)` 并把产权挂它名下 ⇒ `noneMatch(...)` 那条**红** —— 证明"两件事互不牵连"这半句真被测。

- [ ] **Step 5: 提交**

---

### Task 6: 逐条验收 + 关账（I3.1 / I3.2 / I3.3）

**Files:** Create `.superpowers/sdd/2026-09-26-s1-stage3-operator-binding/readings.md`；**无**生产/测试改动（发现缺口 ⇒ 回对应 Task，**不许就地补**）

- [ ] **Step 1: 收尾门禁（前台）**：`./mvnw clean verify` ⇒ **BUILD SUCCESS**（13 模块；测试总数 **≥ 2454 + 本阶段新增**；SpotBugs `BugInstance size is 0`；**全部 surefire 报告 mtime 落在本轮**）。

- [ ] **Step 2: 逐条判据读数（写进 `readings.md`）**

| 判据 | 怎么读 | 期望 |
|---|---|---|
| **I3.1** 读得出 | `GuiApiTest` 的 `industries[].operator` + `CatalogTool` 提示串 | `{kind,id}` 逐值 |
| **I3.1** 写得进 | `EconomySeedHandlerTest` 的显式 `operator` 用例（**非默认值**） | 逐值等于载荷里那个 |
| **I3.1** 不丢失 | Task 3 三条（一日结算 / 变更集往返 / codec 过线） | 全绿 |
| **I3.2** 租佃档存在 | `RegimeOperatorsTest` 第 4 档 + `S1Stage3TenancyTest` 两条 | 逐值 |
| **I3.2** `AssetOwner ≠ Operator` | `S1Stage3TenancyTest.theAssetOwnerIsNotTheOperator` | 两个方向都断言 |
| **I3.3** 旧载荷升级 | `EconomySeedHandlerTest`：**未修改的** `PAYLOAD`（无 `operator` 键） | 派生为 `ESTATE:farm@0_0` |
| **R4** 制度不约束 operator | `EconomyInvariantsTest.theRegimeDoesNotConstrainTheOperator` | 构造成功 |
| **R1** 未知制度 fail-closed | `RegimeOperatorsTest.anUnregisteredRegimeIsRefusedRatherThanGuessed` | 抛 + 消息含档名 |

- [ ] **Step 3: 增量性核对**

```bash
git diff <阶段3起点 sha>..HEAD --stat -- simos-economy/src/main simos-app/src/main
```
Expected：`simos-economy/src/main` 只 3 个文件；`simos-app/src/main` 只 2 个。★ 多出来的 ⇒ **停下来核**。

- [ ] **Step 4: 文档回填 —— 本阶段没有要改的常驻文档**

`AGENT.md` 的模块表 / 依赖图 / ban 列表**一字不改**（不新增模块、不改依赖方向 —— 与阶段 2 不同）。★ 唯一动作是把 ②（`feudal` 注释写"封建租佃"、spec §六 写"领主自营"）如实记进 `readings.md`，**不去改** `EconomySeeder` / `Industry` / `AllocationRule` 那三处旧注释（留痕不篡改，`AGENT.md` §五.4）。

- [ ] **Step 5: 提交**

---

## Self-Review

**Spec coverage**（对照 spec §2.1 / §2.2 / §六 / §十一）：

| Spec 要求 | 落点 |
|---|---|
| `ProductionActivity.operator: ActorRef`（§2.2） | Task 2（类名沿用 `Industry`，见修正 ④） |
| `ProductionOperator` 与 `AssetOwner` 不默认相同（§2.1） | Task 5（两个方向都断言） |
| §六 四档 → 默认 operator | Task 1（表）+ Task 2（接线）+ Task 6（逐档读数） |
| ★ **租佃档是本轮要新加的**（"没有它就证明不了 §2.1 那条判据"） | Task 1（`tenant`）+ Task 5（I3.2） |
| `regime` 只做初始化（§2.4） | 裁定 R4 + Task 2 Step 5 的 `EconomyInvariantsTest` 用例 |
| §十一「四档默认规则的**参数值**未定」 | 本阶段**只给主体、不给参数**（地租 30% 属阶段 5） |

**Placeholder scan：** 无 TBD/TODO。★ 两处"红从哪来"**已如实处理**：Task 3 Step 2（本任务无实现 ⇒ RED 来自变异体，**当场**捕获、不许回放补拍）、Task 5 Step 2（用例预期直接绿 ⇒ RED 来自变异体）。**没有**把它们写成"先红后绿"。

**Type consistency：** `RegimeOperators.defaultOperator(RegimeId, IndustryId)` 在 Task 1 定义、Task 2/4/5 消费；`Industry.operator()` 在 Task 2 定义、Task 3/4/5 消费；读口形状 `{"kind","id"}` 由 R6 钉死、与 `ApiViews` 既有 `actors[]`（第 1106-1110 行）**同形**；`ActorKind` 只用到 `ESTATE`/`HOUSEHOLD`/`WORKSHOP` 三个（**不新增档位**）。

**Review Focus coverage：** ①（不加守卫）与 ②（id 拼法）→ Task 1 Step 1 + Task 2 Step 4 的拼法；③ → Review Focus 第 3 条（`Industry` 不看 id）；④ → Task 2 Step 3（`null` 与缺键不可分，口径写进注释）。

## 本计划的验证边界

- **未跑 Maven**：所有 `Expected` 都是**预测**（`AGENT.md` §9.2：与实际不符 ⇒ 先核算式、先查被测物，**不许把 Expected 改成实际值**）。
- **未实测**：`EconomyRealScaleSeedBottleneckTest` 作为"两处拼写漂移守卫"的**有效性**只是**读码推断**（真播种器载荷 → 真 handler → `RegimeOperators`）⇒ Task 2 Step 7.3 **必须真跑**。
- **未清点**：25 处构造点里哪些"透传"、哪些"派生"、哪些"必须显式"（`capitalist`）是按类定的口径，**逐个文件核**（清单出自文本级 `grep -c`，未逐条读上下文）。
- **未决策**（留给控制方，见 R2 备选）：归档边缘（Jackson 路径）**故意不兜底**。要覆盖 ⇒ `Industry` 紧凑构造器**一行**，但 Task 3 的夹具纪律要跟着重述。
- **未做**：`Industry → ProductionActivity` 改名（修正 ④）；`ProductionRelation`（阶段 5）；`ConsumptionReceipt`（阶段 6）；产出归属（阶段 4）。
- **未评估**：`operator` 是否该进 `EconomyData` 的构造期守卫 —— Review Focus ① 的结论是"不该"，依据是既有口径（**推断**），未与阶段 4 的需求对账。

---

## ★★ 控制方裁定（2026-09-26，计划交付后 / 派活前）—— **执行者照这个做**

计划撰写者提出 5 个待拍板的设计点与 6 处上游文档不符。**逐条裁定如下**（都能从 spec + 代码 + 本仓口径推出，
故不上升给用户）：

### 裁定 D1：缺省推导**只在载荷边缘**（`EconomyPayloads`），`Industry` 保持 fail-closed

`Industry` 的构造期守卫：`operator` **为 null 即抛**。缺省推导**只**发生在载荷解析那一步
（缺 `operator` 键 ⇒ 查 `RegimeOperators` 得默认值）。
—— **依据**：本仓一以贯之的口径 —— **静默接受会让"写错"变成运行时幽灵**（`SocialClassId` 词表外即抛、
`AssetClassKey` 词表外即抛、`ActorData` 悬空 owner 当场拒）。
—— **代价**：**旧 economy 归档打不开** —— 与 spec §十.4「**旧档重建也没关系**」的既有裁定一致。
—— **被否的备选**（`Industry` 里 null ⇒ 推导）：25 处构造点**任一漏传就静默换成默认值、不崩** —— 正是本仓最反对的形态。

### 裁定 D2：未知 `regime` **fail-closed**

未登记的制度 + 载荷又没给 `operator` ⇒ **抛**，且消息里**列出四档**。
—— **依据**：否则"operator 是标签"会以 **fail-open** 的形式复活。
—— **代价**：`RegimeId("capitalist")` 那类既有夹具**必须显式给 operator**（计划撰写者认为本来就该给，我同意）。

### 裁定 D3：默认 operator 的 **id 拼法 = 产业 id**（`ESTATE:farm@0_0`）

—— **依据**：代码里 actor 的 id 本来就是**产业 id**（`EconomySeeder.appendAllocation` 用 `industry.value()`；
`EconomyData` 按 `actor.id()` 归集劳动）。用真 hex 会让**同一个庄园有两个 `ActorRef`**。
—— **代价**：低（与劳动侧同字面，本来就是一处拼写）。

### 裁定 D4：新租佃档的字面量 = **`tenant`**

—— **依据**：`ContractId` 的类注已用「租佃合同」，且**9 处既有夹具已在用 `tenant` 这个字面量** ——
换名会让那 9 处语义悬空。
—— **代价**：低。

### 裁定 D5：**都不加** —— 不加「`operator` 必须是被声明的 actor」守卫，也不加「`regime` ↔ `operator` 一致性」守卫

—— **依据**：前者同 `ActorData` 既有口径（存在性归命令面）；后者会被 spec §2.4「**同制度、不同关系**」
**当场证伪**，并让 `capitalist` 那类夹具**失去覆盖**。
—— **代价**：低（真需要时在命令面加）。

### 裁定 D6：`Industry` → `ProductionActivity` 的**改名不在阶段 3 做**

spec §2.2 提了改名，但**七个阶段没有一段认领它**（计划撰写者的发现 #4）。
—— **判定**：改名是**纯词汇**改动、不含行为，而 `Industry` 有 **25 处构造点** ⇒ 现在做是**零收益的大范围 churn**。
—— **落点**：**阶段 4**（产出归属真的从 `Industry` 移走时，命名才名副其实）。
—— **记为一个具名欠账**，不是遗忘。

### 裁定 D7：`feudal` 一词的两义**必须就地消除**

代码里 `feudal` 的注释写「**封建租佃**」（3 处），而 spec §六 的 `feudal` 是「**领主自营庄园**」、
租佃另立新档 ⇒ **同词两义，行为不冲突、词冲突**。
—— **判定**：阶段 3 落地新档时**顺手把那 3 处注释改对** —— **写错的注释会传给后来人**（本阶段已因同类问题栽过一次）。
