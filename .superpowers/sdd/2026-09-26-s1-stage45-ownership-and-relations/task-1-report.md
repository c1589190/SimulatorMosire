# Task 1 报告：契约 —— `CohortKey` + `relation` 包（S1 阶段 4+5）

**状态：完成** · 提交 **f3ff906** `feat(economy-api): CohortKey 与关系契约（S1 阶段 4+5）`（7 文件 / +981 行）
**门禁：`./mvnw -pl simos-economy-api -am verify` BUILD SUCCESS**（checkstyle ✓ · spotless:check ✓ ·
spotbugs `BugInstance size is 0` · `CohortKeyTest` 14/14 · 模块合计 23/23）
**证据：** `.superpowers/sdd/2026-09-26-s1-stage45-ownership-and-relations/task-1-evidence/`

---

## 一、实现了什么

### 1. `CohortKey`（新包 `io.mosire.simos.economy.api.cohort`）

| 件 | 内容 |
|---|---|
| 形状 | `record CohortKey(HexCoord residence, SocialClassId stratum)` |
| **规范串** | `"<q>_<r>|<stratum>"`，例如 `new CohortKey(new HexCoord(3, -2), POOR_PEASANT)` ⇒ **`3_-2|poor_peasant`**（brief 的逐字期望值） |
| **`toString()` / `parse`** | **同处一个文件**（见 §五）；`parse` 按**第一个** `|` 切（照 `GoodsAccountKey` / `AssetHoldingKey` 先例） |
| 落点 | `economy-api`，**不落 `actor-api`**（裁定 E3：后者主依赖为零，装不下 `HexCoord`/`SocialClassId`，硬放会成 `actor-api → economy-api → actor-api` 循环） |
| 类注如实记 | R9（**它不是行键**，本阶段不合并 `ClassKey` ⇒ V9/I1.2 未达成）；R7（城市格里"农村贫农/城镇贫农"并成一个 cohort）；阶段 6 的 receipt 表拿它作键 |

### 2. `relation` 包（新包 `io.mosire.simos.economy.api.relation`）—— 完整类型清单

| 类型 | 形状 | 本任务的判据/守卫 |
|---|---|---|
| `RuleType` | **enum 六档**：`SELF_RETENTION` / `OUTPUT_SHARE` / `FIXED_IN_KIND_PER_LABOR` / `FIXED_IN_KIND_RENT` / `FIXED_MONEY_WAGE` / `FIXED_MONEY_RENT` | `money()` = **「哪一档是货币」的唯一拼写点**（构造参数，非 `name().contains`）；`parse` **fail-closed** |
| `Basis` | **enum 六档**：`GROSS_OUTPUT` / `NET_AFTER_INPUTS` / `OPERATOR_SURPLUS` / `LABOR_AMOUNT` / `ASSET_QUANTITY` / **`FIXED_AMOUNT`** | 第 6 档 = **裁定 E5**；`parse` fail-closed |
| `Recipient` | **sealed interface**（`permits ToActor, ToCohort`）+ 两个嵌套 record | 判据②：「actor 与 cohort **恰其一**」是**类型事实**；两变体各自判 null |
| `CompensationRule` | `record(RuleType type, Recipient recipient, Basis basis, int ratePerMille, long fixedAmount, Optional<CommodityId> commodity, int priority)` | 8 条构造期守卫（见 §四）；**本类型唯一的 `Optional`** |
| `ProductionRelation` | `record(IndustryId activity, ActorRef operator, List<CompensationRule> rules, ActorRef residualOwner)` | 非空 / 逐项非空 / **保序不可变** / 空表合法（全归 `residualOwner`）；**不另造 id**（身份 = `activity`） |

★ **零公式**：本任务没有任何算术（结算计算是 T3 的 `ProductionSettlement`），全部断言都是构造期守卫与形状事实。

---

## 二、★ E4：单列表 + `priority` —— "租给 `(hex, landlord)` cohort" 怎么表达

**表达方式：它就是同一张 `rules` 表里的再一条规则。** 见证用例
`CohortKeyTest.theSingleRuleListCanAddressTheLandlordCohortExplicitly`（真构造、逐值读回）：

```java
Recipient landlord = new Recipient.ToCohort(new CohortKey(hex, SocialClassId.LANDLORD));
CompensationRule rent        = new CompensationRule(RuleType.FIXED_IN_KIND_RENT, landlord,
                                 Basis.FIXED_AMOUNT, 0, 4_000L, Optional.of(GRAIN), 10);
CompensationRule selfRetention = new CompensationRule(RuleType.SELF_RETENTION,
                                 new Recipient.ToActor(tenantHousehold),
                                 Basis.OPERATOR_SURPLUS, 0, 0L, Optional.of(GRAIN), 20);
new ProductionRelation(new IndustryId("farm@0_0"), tenantHousehold, List.of(rent, selfRetention),
                       tenantHousehold);   // residualOwner = 佃农家户 ⇒ 自留
```

三个要点（都写进了类注）：

1. **`Recipient` 的多态把"两张表"降级成"一维"**：spec §2.4 的 `laborCompensationRules` /
   `assetCompensationRules` 想区分的两件事（受方是谁、按什么算），已经由 `recipient`（sealed）与
   `basis`（`LABOR_AMOUNT` / `ASSET_QUANTITY`）表达 ⇒ 两张表是**第二拼写点**（同一批规则能被分成两处）。
2. **次序成了数据**：`priority` 是规则上的一个 `int`（允许重复）⇒「先给养、后按剩下的算地租」这类真实制度
   **改一个整数就能表达**，不需要代码分支（T3 的 `OPERATOR_SURPLUS` 就靠它）。
3. **"显式给"是硬要求（E4 第 2 条）**：地主**既不在劳动账里**（不是劳动者 cohort），**也不是 actor**（经营者是
   佃农家户）⇒ 不显式写一条 `ToCohort((hex, LANDLORD))`，**地主 cohort 的粮源会凭空消失**。契约现在**装得下**这条
   规则（`CohortKey(hex, LANDLORD)` 可构造、可 `parse`、可作为 `recipient`）；**真档出厂表属 T2**（`RegimeRelations`）。

---

## 三、★ E5：`FIXED_AMOUNT` 落在哪

- **落点**：`Basis` 枚举的**第 6 个常量**，排在那五个"每单位什么"之后。
- **依据（写进 `Basis` 类注）**：spec §2.4 的五档全是「**每单位什么**」（毛产/净产/经营剩余/劳动量/资产量），
  而 `FIXED_IN_KIND_RENT` / `FIXED_IN_KIND_PER_LABOR` / `FIXED_MONEY_*` 的"数量从哪来"在它们里**没有落点**——
  **固定额没有单位**，它不按任何比例算。⇒ 与「`CohortKey` 的落点」同款修正：**spec 的枚举不全，按结算公式表补一档**。
- **可判性**：`bothVocabulariesAreExactlySixInOrderAndParseFailsClosed` 用
  `assertThat(Basis.values()).containsExactly(..., FIXED_AMOUNT)` 钉住**成员 + 次序** ⇒ 少一档（含删掉
  `FIXED_AMOUNT`）、多一档、换序都红；且 `CompensationRule` 的固定额档必须用 `Basis.FIXED_AMOUNT` 才构造得出
  （见 §二 的 `rent` 与 §四的守卫）。
- **有意不加的**：`type × basis` 的**组合**守卫（哪些组合有公式）—— 那是 T3 的公式表说了算，在契约里先禁掉一批
  组合等于把还没定的设计写进类型（写进了 `Basis` 类注）。

---

## 四、★ 货币档"只定义不结算"（I5.3）怎么表达 / 怎么报"待 S2"

**两件事，本任务只有前面那半**：

1. **字段形态（本任务，可判）**：`commodity` 是 `Optional<CommodityId>`，**空 = 货币规则**、**非空 = 实物规则**，
   二者由构造期守卫**判死**：
   ```java
   if (type.money() && commodity.isPresent()) throw new IllegalArgumentException("货币规则不得带商品（…）…");
   if (!type.money() && commodity.isEmpty())  throw new IllegalArgumentException("实物规则必须带商品（…）…");
   ```
   ⇒「二选一是**类型事实**，不许两处都能填」（brief 的逐字判据）。`type.money()` 是**唯一**判断点（`RuleType`
   的构造参数）——结算侧（T3）照它把规则分流进 `deferredMoney`，**别处不许再判一遍**（那会是第二拼写点）。
2. **"待 S2"怎么报**（**属 T3**）：本任务的契约只提供**可判别性**——货币档**构造得出来**（`moneyRulesConstructFineWithoutACommodity`：
   `FIXED_MONEY_RENT` + `Optional.empty()` 收下，`fixedAmount` 在、`commodity` 空），于是 T3 能在
   `ProductionSettlement.Outcome.deferredMoney` 里**让它零条目 + 明确报「待 S2」**（I5.3 的读数在
   `ProductionSettlementTest`，本任务不越界）。★ 本任务**不**写任何"待 S2"的字符串，也不假装结算（S1 没有 ledger）。

---

## 五、★ 新键的 `toString()` / `parse` 配对（同处一个文件）

`CohortKey.java` **一个文件之内**有三件东西：分隔符常量、`toString()`、`parse`：

```java
private static final String SEGMENT_SEPARATOR = "|";              // ① 唯一拼写点
@Override public String toString() { return residence + SEGMENT_SEPARATOR + stratum; }  // ②
public static CohortKey parse(String text) { … indexOf(SEGMENT_SEPARATOR) … }            // ③
```

- **为什么必须有这一对**（硬约束 R9）：`FieldDelta`（`simos-util`）把状态表的键压成 `toString()` 的产物、重建时用
  `parse` 还原 ⇒ 缺了配对，下游（阶段 6 的 receipt 表）就被迫自己写规范串的**逆**，于是**同一个格式有了两处拼写点**。
  先例：`ActorRef.parseCanonical` / `AssetHoldingKey` / `GoodsAccountKey`。
- **两段各自交给上游的逆**（`HexCoord.parse` / `SocialClassId.parse`）：本类**不复述**它们的格式 ⇒ 上游改了规范串，
  本类的往返**当场跟着红**。
- **按第一个接缝切**（不是最后一个）：两个分量都不含 `|`（坐标是 `数字_数字`、阶层是四词词表）⇒ 合法串上两种切法
  **恒等**；差别只在坏输入上报错**来自哪一层**（见 §六 M1）。
- **宁抛不静默**：`null` / 空白 / 无接缝 / 接缝在首 / 接缝在尾 ⇒ 一律 `IllegalArgumentException`。

---

## 六、TDD 证据

### RED（当场捕获）

**命令**（brief Step 2 逐字）：
```
./mvnw -pl simos-economy-api -am test -Dtest=CohortKeyTest -Dsurefire.failIfNoSpecifiedTests=false
```
**日志**：`task-1-evidence/log-RED.txt` · **rc=1**

```
[INFO] Building EconomyApiSimos 0.1.0-SNAPSHOT                            [5/5]
[ERROR] COMPILATION ERROR :
[ERROR] .../cohort/CohortKeyTest.java:[11,44] package io.mosire.simos.economy.api.relation does not exist
[ERROR] .../cohort/CohortKeyTest.java:[50,25] cannot find symbol   symbol: class CohortKey
[ERROR] Failed to execute goal ...maven-compiler-plugin:3.16.0:testCompile (default-testCompile)
        on project simos-economy-api: Compilation failure
```
**为什么这个失败是预期的**：测试先写、实现尚不存在 ⇒ 失败**只可能**是"类型不存在"的**编译错**（`maven-compiler-plugin:testCompile`），
**surefire 一条都没跑**（日志里没有任何 `Tests run`）—— 这正是 TDD 的 RED：先证明"断言在测的东西还不存在"。
★ 注意 `-rf :simos-economy-api` 说明**上游四个模块编译通过**，失败点精确落在本任务的新测试上。
★ 反应堆摘要逐条为证：`SimulatorMosire` / `UtilSimos` / `MapSimos` / `ActorApiSimos` = **SUCCESS**，
`EconomyApiSimos` = **FAILURE**（`log-RED.txt:410-419`）。

### GREEN

**命令 1**（spotless:apply 之前）：同上 ⇒ 日志 `log-GREEN-1.txt`，`Tests run: 14, Failures: 0, Errors: 0`，
`BUILD SUCCESS`，rc=0；surefire 报告 `io.mosire.simos.economy.api.cohort.CohortKeyTest.txt` 的
**mtime = 2026-09-26 21:29:47（本轮）**，内容 `Tests run: 14, Failures: 0, Errors: 0, Skipped: 0`。
**格式**：`./mvnw -pl simos-economy-api spotless:apply` ⇒ **只动了我这 7 个新文件**
（`git status` 里模块内只有那三个新目录，**没有**任何既有文件被改动）。

**命令 2**（**最终字节**上重跑）：`log-GREEN-2-final-bytes.txt` ⇒ `Tests run: 14, Failures: 0, Errors: 0`，
`BUILD SUCCESS`，rc=0。最终字节的 md5 记在 `md5-final-post-spotless.txt`。

**门禁**：`./mvnw -pl simos-economy-api -am verify` ⇒ `log-VERIFY.txt`：
`checkstyle:check` ✓ · `spotless:check` ✓ · `CohortKeyTest` 14/14 · 模块合计 **23/23** ·
**`BugInstance size is 0`** · **BUILD SUCCESS**（rc=0）。
★ 此刻的 surefire 报告 mtime = **2026-09-26 21:32:31**（21:33 读，**落在本轮**）；它对应的是**最终字节**
（§八 的 7 个文件、md5 = `md5-final-post-spotless.txt`）。

---

## 七、★ 变异自证（6 条，逐条当场捕获 RED）

**方法**：每条变异**改一处** ⇒ 跑同一个测试类 ⇒ 记录**红在哪条断言** ⇒ 还原 ⇒
`md5sum -c md5-baseline.txt`（**逐字节**确认还原，本仓 §9 的口径：字面量没改不如 md5 未变硬）。
`md5sum -c` 每轮**零 mismatch** ⇒ 各条 GREEN 证据适用于**最终的同一份字节**。

| # | 变异体 | 自问"若这条规则不存在，这个变异体还会红吗？" | 结果（红在哪条断言） |
|---|---|---|---|
| **M1** | `CohortKey.parse` 按**最后**一个接缝切（brief ① 逐字） | 会 —— 但**红的机制与 brief 写的不同**，见下 ★ | **1 红**：`parseTreatsEverythingAfterTheFirstSeamAsTheStratum`（**往返那两条仍绿**） |
| **M2** | 去掉「实物规则必须带 commodity」守卫（brief ② 逐字） | 会 —— 它是 I5.3 二选一的一半 | **1 红**：`moneyRulesCarryNoCommodityAndInKindRulesRequireOne` |
| **M3** | `CohortKey.parse` 把两段**接反**（规范串的逆写错） | 会 —— 这才是"往返"能抓到的逆错误 | **1 失败 + 2 错误**：`cohortKeyRoundTripsThroughItsCanonicalText`、`…ForANonDefaultStratum…`（**往返红**）+ 接缝那条 |
| **M4** | `Basis.parse` 词表外**兜底**（fail-open） | 会 —— 六档词表的 fail-closed 就靠它 | **1 红**：`bothVocabulariesAreExactlySixInOrderAndParseFailsClosed` |
| **M5** | `RuleType.FIXED_MONEY_WAGE(false)`（把货币工资标成实物档） | 会 —— `money()` 是 I5.3 的唯一拼写点 | **2 红**：`moneyRulesCarryNoCommodity…` + `moneyFlagIsPinnedPerRuleType` |
| **M6** | 去掉 `Recipient` 的 `sealed` 与 `permits` | 会 —— 判据②的"类型事实"当场失守 | **1 红**：`recipientIsSealedSoActorAndCohortAreExactlyOne` |

**★ M1 是本任务最值得记的一条（差点成了第 5 次"绿得骗人"）**：
brief 写「`parse` 按最后一个 `|` 切 ⇒ **往返红**」，**实测往返不红**——因为两个分量都不含 `|`
（坐标 `数字_数字`、阶层四词词表）⇒ 合法串里接缝**恰一个**，`indexOf ≡ lastIndexOf`。即：**按 brief 给的
测试夹具（只有 4 条断言），M1 是一个等价变异体（存活）**。它**能**抓到的地方是**坏输入上报错来自哪一层**：
```
Expecting throwable message: "For input string: \"-2|poor_peasant\"" to contain: "poor_peasant|junk" but did not.
  at io.mosire.simos.map.hex.HexCoord.parse(HexCoord.java:105)   ← 变异后：报错落到了坐标轴
```
⇒ 处置：**补一条断言**（`parseTreatsEverythingAfterTheFirstSeamAsTheStratum`：非法串的报错来自**阶层词表**，
即"接缝之后整段都是阶层段"）—— 于是 M1 **确实红**、且红在**被测的那一层**（规范串的接缝）。
★ brief Step 5 ① 的"往返红"这句**需要更正**：往返能抓的是「逆写错」（M3），抓不到"按哪一端切"。
★ 还原后的绿：`md5sum -c` 全 OK + `log-GREEN-2-final-bytes.txt`（14/14）。
★ **M1/M2 在 `spotless:apply` 之后的最终字节上重跑过**（`log-M1-final-lastIndexOf.txt`、`log-M2-final-noInKindGuard.txt`，
红点与上表**逐字相同**）；M3–M6 在 apply **之前**的字节上捕获（apply 是纯格式化：`git status` 证明只动了这 7 个文件、
且 javadoc 重排不改任何 token；M3–M6 不依赖行号或 javadoc）。

---

## 八、改了/新增哪些文件（相对 `e6d6947`）

**新增 7 个（全部 `A`，无任何既有文件被改）**：

```
simos-economy-api/src/main/java/io/mosire/simos/economy/api/cohort/CohortKey.java          (+87)
simos-economy-api/src/main/java/io/mosire/simos/economy/api/relation/RuleType.java         (+82)
simos-economy-api/src/main/java/io/mosire/simos/economy/api/relation/Basis.java            (+56)
simos-economy-api/src/main/java/io/mosire/simos/economy/api/relation/Recipient.java        (+49)
simos-economy-api/src/main/java/io/mosire/simos/economy/api/relation/CompensationRule.java (+82)
simos-economy-api/src/main/java/io/mosire/simos/economy/api/relation/ProductionRelation.java (+68)
simos-economy-api/src/test/java/io/mosire/simos/economy/api/cohort/CohortKeyTest.java     (+557)
```
与 R2 表 / `File Structure` 表**逐条对上**（6 主文件 + 1 测试），**无计划外文件**。
★ **没动** POM（依赖已够：`map`/`actor-api`/`util` 都是既有主依赖）、**没动** `package-info`（见 §九.4）、
**没放宽任何既有断言**（本任务没碰任何既有文件）、**没加旧归档夹具**。

---

## 九、自审发现与疑虑（**请控制方过目**）

1. **★★ brief 的变异体 ① 的机制写错了**（详见 §七 M1）：按 brief 的夹具，`lastIndexOf` 是**等价变异体**。
   我补了一条**接缝位置**的断言把它变成可判，并另加 M3 覆盖"往返"那一层。⇒ 若控制方各处 brief 都写了
   "按最后一个接缝切 ⇒ 往返红"，**这句话在同类键类型上是错的**（本仓 `GoodsAccountKey` / `AssetHoldingKey` 同理）。
2. **★ `SELF_RETENTION` 必须带 `commodity`（二选一守卫的推论，T2 会撞）**：`SELF_RETENTION` 不是货币档 ⇒
   按 brief 逐字的判据「**实物规则**必须带 commodity」，它也要带。⇒ **T2 的 `RegimeRelations`** 若想为
   household/tenant 档写一条"自留"规则，必须给它一个商品；或者**干脆不写这条规则**（空表/余额归
   `residualOwner` ⇒ 等价）。已写进 `RuleType.SELF_RETENTION` 与 `ProductionRelation` 的类注。
   ★ 若控制方认为 `SELF_RETENTION` 应当允许*无*商品，那是**契约改动**（要连着改断言 + 一个变异体），现在提出来最便宜。
3. **`type × basis` 的组合守卫有意不加**（哪些组合有公式 = T3 的公式表）⇒ T3 见到"没定义的组合"时**必须自己
   fail-closed**（抛），契约层不替它兜。已在 `Basis` 类注写明。
4. **没有为两个新包建 `package-info.java`**（有意）：brief 的 Files 行点名"6 个文件 + `CohortKeyTest`"，而 T8 Step 3
   的增量性核对会"多出来的 ⇒ 停下来核"；且本仓 104 个含 Java 的包里只有 16 个有 `package-info`（非硬约定，
   `api/population` 就没有）。⇒ 要补的话**属 T2/T3 那一轮**。
5. **`api/package-info.java` 的口径需要放宽一格**（计划 R2 的"★ 代价"已明说）：现在写的是「只放共用契约 —— 稳定 ID、
   `CommodityId`、跨模块事件与转移意图」，而 `LaborAllocation`（既有）与本次的 `CohortKey` / `CompensationRule` /
   `ProductionRelation` 都是**数据记录**。★ 本任务**没动**该文件（不在 6 文件清单里）⇒ **建议 T2/T8 顺手加一句
   "数据记录可以有"**（否则下一轮的读者会以为这些类型放错了）。
6. **未达成的判据（如实记，不静默）**：V9 / I1.2（行键未合并，R9）——本任务**只**引入了受方身份；`AssetHolding`
   真档未种入 ⇒ `ASSET_QUANTITY` 只有夹具覆盖。二者都不是本任务引入的债，但**在本任务的类注里有落点**，T8 收账时可直接引用。
7. **未做的迁移**：`ClassKey → CohortKey` 的 259 处引用**一行未动**（R9）；旧档作废、不做迁移工具（spec §十.4）✓。
8. **工作区里不属于我的东西（我没有碰）**：
   - `.superpowers/sdd/2026-09-26-s1-stage2-actor-slice/progress.md` 有**未提交的修改**（不是我做的）；
   - `docs/superpowers/plans/2026-09-26-s1-stage45-ownership-and-relations.md` **仍未入库**（本阶段的计划文档）；
   - `.superpowers/sdd/2026-09-26-s1-stage45-…/` 整目录被 `.superpowers/sdd/.gitignore`（内容为 `*`）忽略
     ⇒ 本报告与证据要 `git add -f` 才进仓库（前几阶段的 `task-N-evidence/` 就是这么进去的）——见本任务第二个提交。
