# A1 实现架构账本：货币身份（显示名 + 逐世界词表 + DM 三工具 + 第二币种创世）

> 责任区：阶段 2 第一批 **A1**（约束设计书 `docs/superpowers/specs/2026-10-08-currency-exchange-stage2-design.md` §3.1 / §5 I16–I24 / §6.1 F1·F8 / §7 M5·M6 / §8）。
> 写代理：办事子 Agent（一个责任区一个写代理）。只写生产代码、只 compile、不写/不跑测试、不 `git commit`。
> 交账时间：2026-10-08。**状态：生产代码编译通过 + 43 条探针断言全绿；决策人工具链的"注册面"两处跨责任区文件未落地 ⇒ 见 §5「BLOCKED」。**

---

## 1. 关键调查结论（`file:line` → 结论 → 影响）

| # | 证据（file:line，开工时实测） | 结论 | 对实现的影响 |
|---|---|---|---|
| 1 | `simos-economy-api/.../money/CurrencyDef.java`（2 组件 record）；`MoneyVocabulary.java:88/97` `List.of(SILVER)` / `List.of(SILVER_SPECIE)` | `CurrencyDef` 无显示名；词表是**硬编码常量**（世界级唯一，不可逐世界不同） | 必须给 `CurrencyDef` 加第三组件；词表必须进**世界状态**（B 的前置） |
| 2 | `simos-app/.../gui/ApiViews.java:4118/4287/4305`：`currencyDefViews()`/`moneyInstrumentViews()` 调**无参静态** `MoneyVocabulary.allCurrencyDefs()` / `allInstruments()` | 唯一的词表**读口**是静态无参的，且它住在 **A1 文件所有权之外**（`app/gui/**` 不在允许写清单里） | ⇒ "词表从世界状态读"**不能**靠改读口签名实现（会越权改别人的文件）；只能：状态为权威 + 静态门面**由状态同步**（照 `MoneyIssuance.syncAuthorities` 先例）。见 §3 判断 1 |
| 3 | `simos-economy/.../model/Government.java:32-39` `implements MoneyAuthority`，`issuable` 是"谁发行什么钱"的**唯一**权威；`MoneyIssuance.requireIssuerOf` 从登记表查 | "发行人"就是 `Government.issuable`（不是新概念） | `DefineCurrency` 必须**同批**把币种登记给某个 GOV，否则新币种永远发不出来（"有名字没人能发"） |
| 4 | `simos-economy/.../time/GovernmentSeigniorage.java:101-121`：周期铸币 = 国库账户 `+amount` + 一条 `FISCAL_ISSUE` 审计 | "发行 = 余额腿 + 审计腿"的既有形制；但它写在结算内部，不是命令 | DM 发行必须走命令面 ⇒ 两侧命令（`actor.AdjustAccounts` + 新命令）同批，见 §2 |
| 5 | `simos-core/.../command/CommandBus.java:17-19,111-113`：批内"每条命令的变更集施加到**累积候选状态**，后一条才看得见前一条" | 一批 = 一条 revision，且**顺序敏感** | 创世命令序与工具批序都可依赖"前一条已生效" |
| 6 | `simos-app/.../world/SmallWorld.java:269,315-325` + `GovWorldBootstrap.java`（`apply(...)` 尾部注资）+ `SmallWorld:applyCommand`（handler → `codec.apply`） | 小世界的 GOV/国库是**创世命令链**（不是 GM 工具）；创世每条命令也走 `codec.apply` | 第二币种 copper 的注入点 = `GovWorldBootstrap`（GOV 单位与国库都在那里产生）；创世也吃得到 codec 边界的词表同步 |
| 7 | `simos-app/.../access/GovScope.java:148-163`（只表态 map/unit/social/actor，**没有 economy**）+ `ResourceAuthorizer` 判定表（调用者未表态 ⇒ 取**工具缺省策略**） | 决策人的 `economy` 面"未表态" ⇒ 工具声明 `economy: UNRESTRICTED` 即放行；`actor` 面**逐格**授自己（+superior）国库格 | 三个 DM 工具声明 `economy`（+`issueMoney` 的 `actor`）即可**不扩权**地工作；国库格断言由身份派生的当刻位置算 |
| 8 | `simos-app/.../access/DecisionCallerFactory.java:111-178`（`WHITELIST` 静态集合）+ 同名测试 212-229（断言"白名单 == 决策人桶，逐条精确相等"） | 决策人能调哪些工具由 `WHITELIST` 决定；只加桶不加白名单 ⇒ ①运行期被权限组拦 ②该测试**当场红** | ⇒ **跨责任区缺口**：`app/access/**` 不在 A1 文件所有权内。见 §5 |
| 9 | `simos-app/.../Shell.java:590-700`（命令 registry 是**显式列表**） | 新命令 handler 必须在 `Shell` 的列表里注册，否则 `core.submit` 报"未知命令类型" | ⇒ 第二个**跨责任区缺口**：`app/Shell.java` 不在 A1 文件所有权内。见 §5 |
| 10 | `simos-economy/.../EconomyData.java`：31 组件；`with*` 32 个；`EconomyChangeSet` 31 组件；`EconomyCodec` 通用 Jackson 绑定 + 键反序列化器 | 加两个状态组件要动：record 头、compact 构造期归一/守卫、**32 个 `with*`**、`between`/`apply`/`isEmpty`、codec 键注册 | 漏掉任何一个 `with*` = 一次无关写入静默抹掉词表（本仓最贵的那类 bug） ⇒ 用脚本一次性补齐 + 编译兜底（见 §3 判断 3） |
| 11 | `simos-app/.../tools/write/GovToolSupport.java`（身份派生 + `GovRejectedException` + `submitBatch` 折叠）、`GovSetEstablishmentTool`（两面并列、门禁不同） | DM 工具的既有形制：身份派生 → 资源断言 → preview/apply → 结局折叠 | 三个新工具**照抄形制**，不发明第二套 |

---

## 2. 实现架构（拆分、类/方法、数据流与调用次序）

### 2.1 词表：状态权威 + 门面同步

```
权威（世界状态）  EconomyData.currencies : Map<CurrencyId, CurrencyDef>
                 EconomyData.moneyInstruments : Map<InstrumentId, MoneyInstrument>
                        ↑ 新增两个组件（#34/#35 位置：record 末尾），进 ChangeSet / Codec（铁律 5）
读口（对外的表）  MoneyVocabulary.allCurrencyDefs() / allInstruments()   ← 静态门面（唯一拼写点不变）
同步点（唯一）    EconomyCodec.apply(...) 与 EconomyCodec.decodeSnapshot(...)
                 两条状态物化路径各一次 ⇒ "状态每变一次，门面跟着变一次"
```

- **归一（旧档兼容）**：`currencies` 空/缺键 ⇒ 旧世界默认 `[silver]`；`moneyInstruments` 空/缺键**且词表整体缺席** ⇒ `[silver-specie]`（只给币种不给工具是合法显式态，不补）。
- **构造期守卫**：键 == 值内 id；工具币种必须在币种表里有定义；**政府 `issuable` ⊆ 币种表**（说不出"这是什么钱"就不许发行）。
- **`MoneyVocabulary.install(defs, instruments)`**：进程级重装（幂等、加锁、坏数据当场抛、空表回落旧默认）；`installLegacyDefault()` 给旧档/夹具自解释口。
- `CurrencyDef(id, scale, displayName)`：`displayName` 非空白、不唯一；旧形状 2 参构造器保留（显示名 = id）⇒ **既有调用点/测试零串改**；`withDisplayName(...)` 是改名唯一形态（拿不到"顺手改 id"的口子）。

### 2.2 三条命令（`simos-economy/.../spi/`，都标 `GmOnlyCommand`）

| 命令 | 写什么 | 关键具名拒 |
|---|---|---|
| `economy.DefineCurrency` | `currencies` ∪ {币种} + `moneyInstruments` ∪ {`<id>-specie`} + 该 GOV `issuable` ∪ {币种} | `economy-not-activated` / `government-not-registered` / `currency-already-defined` / `currency-already-issued`（一币一发行人）/ `instrument-id-taken` |
| `economy.RenameCurrency` | **只写** `currencies` 一个组件的显示名 | `currency-not-defined` / `government-not-registered` / `not-issuer`（M6）/ `display-name-unchanged` |
| `economy.RecordMoneyIssuance` | **只写** `moneyIssuances`（一条 `MoneyIssuanceRecord`；id = `gov-issue-<gov>-<day>-<币种>-<序号>`，确定性、无随机） | `currency-not-defined` / `currency-not-issuable`（M6）/ `amount ≤ 0` / `WITHDRAWAL`（回笼属后续批次） |

★ 三条都在拒绝时记 **INFO**（发生了什么 + 稳定拒因短名）+ **DEBUG**（字段级"为什么"）——§一.9 的级别约定与派单书"拒绝路径具名 DEBUG"同时满足，见 §3 判断 5。
★ `EconomyMeta.currentCycleNumber()`（`lastClosedCycle + 1`，`@JsonIgnore`）是 `MoneyIssuanceRecord.period` 的唯一来源（≥ 1）。

### 2.3 三个 DM 工具（`simos-app/.../tools/write/`）

- 形制照 `GovSetEstablishmentTool`：`preview`（缺省 true = 一个字节不写）/ `preview=false` 必须给 `expectedRevision` / 敏感写 `ToolSpec.level(DEFAULT, true, false)` + `ToolGate.Ask(SENSITIVE)` / `GovToolSupport.resolveGov` 身份派生 / `GovRejectedException → REJECTED`、`IllegalArgumentException → BAD_REQUEST`、`ResourceDeniedException` 原样逃。
- `simos.gov.defineCurrency`（`economy.DefineCurrency`）、`simos.gov.renameCurrency`（`economy.RenameCurrency`）：资源面 `economy`；断言 `economy:*`。
- `simos.gov.issueMoney`：**一批两条命令 = 一条 revision**（`core.submitBatch`）：
  ```
  ① actor.AdjustAccounts        国库家户账 money[currency] += amountMilli  （余额腿；缺账由该命令建账）
  ② economy.RecordMoneyIssuance MoneyIssuanceRecord(kind=FISCAL_ISSUE)     （审计腿）
  ```
  资源面 `actor + economy`；`actor` 断言**身份派生的国库格路径**（`ResourcePaths.actor(自己 GOV 当刻位置)`）⇒ 结构上不可能"给别人的国库加钱"。
- 注册：`SimosToolSource.addGmWrites`（GM 桶，显式 `govUnitId`）+ `addDecisionAgentWrites`（决策人桶，身份派生）。**白名单那半不在本责任区**（§5）。

### 2.4 创世（`GovWorldBootstrap`）

命令序（两级 GOV 之后，**顺序不可换**）：

```
economy.DefineCurrency(中央 GOV, copper, scale=3, displayName="铜")
→ actor.AdjustAccounts(两国库 粮/布/银；**中央**国库再 +100,000 毫铜)
→ economy.RecordMoneyIssuance(中央 GOV, copper, 100,000, INITIAL_ENDOWMENT)
```

⇒ 中央 GOV 是 copper 唯一发行人；省 GOV `issuable` 仍空（"一币一发行人"）。币种 id/精度/显示名都取 `MoneyVocabulary.COPPER_*` 常量（字面量单点）。

---

## 3. 关键判断（为什么这样拆、推翻过什么）

1. **词表形态选 ①（新状态组件）+ 静态门面由 codec 边界同步**，不是"纯静态表"（②）：
   - ②（只在创世注入一张进程内表、不进状态）会**静默死分支**：新进程从盘上读回旧档/快照时没有任何东西可重建词表 ⇒ 世界里有 copper、读口只有 silver。这正是阶段 1 的教训（"探针要打在边界之后那一侧，否则漏掉克隆/委派丢字段"）。
   - ①的代价如实记：`MoneyVocabulary` 是**进程级**的（最后物化的那个状态说了算），跨世界/跨分支并发读会互相影响 —— 与 `MoneyIssuance.syncAuthorities` 的既有限制**同源**，不是本批新引入的机制。**权威永远是 `EconomyData.currencies()`**，要"某个 revision 的词表"必须走状态。
   - 为什么不改 `ApiViews` 直接读状态：它在 `app/gui/**`，**不在 A1 文件所有权内**（§一.2 一个文件一个 owner）⇒ 越权改会与别的责任区互相覆盖。
2. **`defineCurrency` 必须同时登记发行人**（一次写三处）：只写词表 ⇒ 新币种永远发不出来（`requireIssuerOf` 查不到），世界得到一个"有名字、没人能发的钱"。这是**权限授予**，所以它由 GM 审批链把关，并明确写进工具描述。
3. **32 个 `with*` 一次性补齐**（脚本 + 编译兜底）：只加组件不改 `with*` ⇒ 一次无关写入（连带 `EconomyStateBuilder` 的日结算回写、`EconomyClearRegionHandler` 的清区域、`EconomySeedHandler` 的补种）会静默把世界词表打回出厂值。★ **`EconomyData` 的旧形状便捷构造器保留**（默认旧世界词表）⇒ 既有调用点/测试零串改，但它**不是**状态迁移路径（注释里写死）。
4. **`issuable ⊆ currencies` 跨表守卫**（构造期 fail-closed）：与既有"发行记录的 `governmentId` 必须已存在"同族。★ 它在探针里当场抓出我自己的夹具错误（把新世界词表键摘掉造"旧档"⇒ 政府声称发行 copper 而无定义 ⇒ 拒），说明这条守卫有判别力；真正的旧档（银本位）不受影响。
5. **拒绝路径的日志级别**：派单书写"拒绝路径具名 DEBUG"，AGENTS §一.9（2026-10-23 裁定）写"业务拒绝 = INFO"。两者不冲突的读法是"INFO = 发生了什么 + 具名拒因；DEBUG = 为什么（字段级）"⇒ **两条都发**，不做取舍。
6. **推翻过的一版**：最初想在 `EconomySeed` 载荷里也声明词表（"创世注入的逐世界表"）。放弃理由：那会给词表**两个写入口**（seed 覆写 + DefineCurrency 命令），而铁律 2 要求"所有修改都是 Command → ChangeSet"；创世直接用同一条 `DefineCurrency` 命令 ⇒ 创世与运行期**同一个写路径**，探针也因此顺带验了那条命令。
7. **`EconomyMeta.currentCycleNumber()` 用派生读法而非新状态字段**：`period` 必须 ≥ 1，而命令面没有结算的 `currentCycle` 上下文；从权威 `lastClosedCycle + 1` 现算不新增第二份真相（并 `@JsonIgnore` 守住线格式）。

---

## 4. 偏离约束设计书之处及原因

| # | 设计书原文 | 实际做法 | 原因 |
|---|---|---|---|
| 1 | §3.1-2「`allCurrencyDefs()` / `allInstruments()` 从硬编码常量改为**从世界状态读**」 | 状态为**权威**（`EconomyData.currencies()`/`moneyInstruments()`）；静态门面由 **codec 边界**按状态重装 | 静态无参签名**读不到** state（`economy-api` 比状态树底层），而唯一读口 `ApiViews` 不在本责任区 ⇒ 只能"状态权威 + 门面同步"。**建议后续责任区把 `ApiViews` 两栏改成读状态**（那时门面可退役） |
| 2 | §3.1-3「`gov.issueMoney`：落账 = 国库余额增加 + `MoneyIssuance` 审计」 | 一条工具 = **一批两条命令**（`actor.AdjustAccounts` + `economy.RecordMoneyIssuance`），一批 = 一条 revision | 账户在 `actor`、审计在 `economy`，一条命令只写一个命名空间（铁律 3/4）。形制同 `GovCreateOfficeTool` |
| 3 | §3.1-3「三个 DM 工具：命令 handler + 决策人 catalog + GM 桶 + 工具描述/示例，四面同源」 | handler ✓、GM 桶 ✓、决策人桶 ✓、描述/示例 ✓；**命令 registry（`Shell`）与决策人白名单（`DecisionCallerFactory`）未落地**（不在文件所有权内） | 见 §5 BLOCKED。工具本身已按"注册后即可用"写成，探针里由探针自行注册并**真跑通** |
| 4 | §3 A 阶段「1 个 GOV；发行人 = 该 GOV」 | 小世界现状是 **2 个 GOV 单位**（中央 / 省，Z5 起）⇒ 取**中央 GOV** 为 copper 发行人，省 GOV 不发行 | 与现状事实对齐；"一币一发行人"不变。设计书 §2.3 的"1 个 GOV"是 2026-10-08 的旧事实 |
| 5 | §3.4「政府第二币种从创世注入（INITIAL_ENDOWMENT，记审计）」 | 注入点是 `GovWorldBootstrap`（不是 `EconomySeeder`），量 = 100,000 毫铜（具名常量，与银同阶） | GOV 单位/国库/`issuable` 都由 `GovWorldBootstrap` 产生；数额不是判据，判据是"国库真持有 + 审计对得上"（探针逐值验） |
| 6 | §3.1-3「`defineCurrency`（id + scale + displayName）」 | 额外自动成对建立 `<id>-specie` 工具（SPECIE，无发行人） | `moneyInstruments` 也必须逐世界可配置（§3.1-2）；不建工具 ⇒ 新币种的持有量在读口落进 `unclassifiedCurrencies`。本批**只做 SPECIE 一档**（STATE_NOTE/BANK_DEPOSIT 必须有发行人兑现承诺，属后续批次） |
| 7 | §7 M6「决策人给**别国**发币/改名 ⇒ 具名拒绝」 | 越权走 `ToolResult.error("REJECTED", 具名原因)`；工具内把"不是发行人"判为 **REJECTED**（不是 BAD_REQUEST） | 探针第一轮把这条判成 BAD_REQUEST，**当场发现并改正**（权限判据 ≠ 参数形状错） |

---

## 5. BLOCKED / 未完成（必须由控制方裁定或另派 owner）

> 依 AGENTS §一.10：做不到 ⇒ **BLOCKED，不是 DONE**；并给出"最小额外范围"与"已实现的最强 fail-closed 降级"。

**BLOCKED-1：新命令未进生产 registry** — `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（命令 handler 列表，约 590-700 行区，`new EconomyRegisterGovernmentHandler()` 一带）缺 3 行：

```java
new EconomyDefineCurrencyHandler(),
new EconomyRenameCurrencyHandler(),
new EconomyRecordMoneyIssuanceHandler(),
```

后果（精确）：GM / DM 工具在**运行期**提交这三条命令 ⇒ `core.submit` 找不到 handler ⇒ 命令被拒（`未知命令类型`）。生产路径缺的**只是登记**，不是能力 —— 探针里由探针自己 `core.register(...)` 注册后**三条命令全部真跑通**（含 commit + 落盘 + 读回）。
最小额外范围：`simos-app/src/main/java/io/mosire/simos/app/Shell.java` 一个文件、3 行（无 pom/无测试改动）。
安全降级：**已实现** —— 三条命令都标 `GmOnlyCommand`（令 / `RegisterEffect` / 决策人命令目录三条路径不放大），且工具在提交前把所有守卫（身份派生、发行人、币种存在、金额）判完，注册缺失只会"调用被拒"，不会造成半截状态。

**BLOCKED-2：决策人白名单未加三条工具** — `simos-app/src/main/java/io/mosire/simos/app/access/DecisionCallerFactory.java:111-178` 的 `WHITELIST` 缺：

```java
GovDefineCurrencyTool.NAME,
GovRenameCurrencyTool.NAME,
GovIssueMoneyTool.NAME,
```

后果（精确）：
1. 决策人**看得到**桶里的三条工具，但权限组不含它们 ⇒ 调用在 `ToolExecutionGuard` 段被拒（"看得见调不动"）；
2. 既有测试 `DecisionCallerFactoryTest.theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites`（`simos-app/src/test/.../DecisionCallerFactoryTest.java:212-229`，断言"白名单 == 决策人桶，逐条精确相等"）**会当场红** —— 它正是"桶与白名单必须同源"的钉子。**这条红不是回归，是指向缺失的那半**。
最小额外范围：`app/access/DecisionCallerFactory.java` 3 行 +（测试侧）该用例的期望集合（测试归测试代理）。
安全降级：**已实现** —— `addDecisionAgentWrites` 的注册是幂等的"多注册无害"；白名单缺失时行为是 **fail-closed 拒绝**，不存在放大。

**未完成 / 未验证**：

- 铸币生产方式、商品采购窗口、FX 订单 / 官方汇率 / 实际汇率 / 币种校验 / 跨币种 1:1 收口、市场区持久化 —— **按设计书 §8 明确留给 A2 / B**，本批一个字没碰。
- `ApiViews.currencyDefs / moneyInstruments` 两栏仍走静态门面（未按状态直读）—— 见 §4-1，建议后续责任区改。
- `CatalogTool` 的 `PAYLOAD_HINTS` 未加三条新命令的载荷提示（新命令未注册 ⇒ 暂不进 catalog；随 BLOCKED-1 一起补更合适）。
- **未跑**：`test` / `verify` / `package`（按派单书）；既有测试的**行为**回归只在探针里间接覆盖（`test-compile` 全绿：既有测试源码**仍然编译**）。
- **未验证**：跨进程"新进程读旧盘"的端到端（探针在同一 JVM 内用 `decodeSnapshot` 模拟）；多世界并发读的门面语义（已知限制，见 §3 判断 1）。

---

## 6. 报告模板（交账数据）

### 6.1 改动文件（`git status --porcelain`）

**新增（6）**：
```
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyDefineCurrencyHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyRenameCurrencyHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyRecordMoneyIssuanceHandler.java
simos-app/src/main/java/io/mosire/simos/app/tools/write/GovDefineCurrencyTool.java
simos-app/src/main/java/io/mosire/simos/app/tools/write/GovRenameCurrencyTool.java
simos-app/src/main/java/io/mosire/simos/app/tools/write/GovIssueMoneyTool.java
```

**修改（12）**：
```
simos-economy-api/.../api/money/CurrencyDef.java          显示名 + withDisplayName + 旧形状构造器
simos-economy-api/.../api/money/MoneyVocabulary.java      copper 常量 + install/门面 + legacy 默认
simos-economy/.../EconomyData.java                        两个新组件 + 归一/守卫 + 33 处构造点（32 with* + empty）
simos-economy/.../change/EconomyChangeSet.java            两个新组件（null 兜底/between/apply/isEmpty）
simos-economy/.../codec/EconomyCodec.java                 键反序列化器 + apply/decodeSnapshot 装词表
simos-economy/.../model/EconomyMeta.java                  currentCycleNumber()（@JsonIgnore）
simos-economy/.../EconomyLogSource.java                   新来源 ECONOMY_MONEY
simos-economy/.../spi/EconomySeedHandler.java             补种合并两张词表（不抹）
simos-economy/.../spi/EconomyClearRegionHandler.java      清区域原样带过两张词表
simos-economy/.../time/EconomyStateBuilder.java           日结算回写原样带过两张词表
simos-app/.../tools/write/GovWorldBootstrap.java          copper 创世三步 + 国库注铜 + 类注
simos-app/.../tools/SimosToolSource.java                  GM 桶 + 决策人桶各 3 条注册
```

### 6.2 编译命令与实际结果

| 命令 | 结果 |
|---|---|
| `./mvnw -q -o -DskipTests compile -pl simos-economy -am` | **exit 0** |
| `./mvnw -q -o -DskipTests compile -pl simos-app -am`（派单书指定命令） | **exit 0**（新类 `*.class` 逐个核实存在：`EconomyDefineCurrencyHandler` / `EconomyRenameCurrencyHandler` / `EconomyRecordMoneyIssuanceHandler` / `GovDefineCurrencyTool` / `GovRenameCurrencyTool` / `GovIssueMoneyTool`） |
| `./mvnw -q -o spotless:apply -pl simos-economy-api,simos-economy,simos-app -DspotlessFiles="…"`（**只格式化本批文件**，不碰别人文件） | exit 0；`git status` 只列本批 18 个文件 |
| `./mvnw -o -DskipTests test-compile -pl simos-app -am`（**不跑测试**，只验既有测试源码还能编译） | **BUILD SUCCESS** |
| 探针（见 §6.6） | `java … A1Probe` ⇒ **exit 0，43/43 断言通过** |

★ 未跑 `package`（服务在跑，禁覆盖 shaded jar）；未跑 `test` / `verify`（按派单书 §一.5/§三.0）。

### 6.3 会改变数值行为的清单（给测试代理当输入）

1. **小世界创世多了一个币种与一笔国库铜**：`GovWorldBootstrap` 之后
   - `EconomyData.currencies` = 2（silver + copper）、`moneyInstruments` = 2（+`copper-specie`）；
   - 中央 GOV 的 `Government.issuable` = `[copper]`（此前空集）；
   - 中央国库（`hh-gov-gov-central`）`money.copper = 100,000` 毫（此前无该键）；
   - `EconomyData.moneyIssuances` 多一条 `gov-issue-gov-unit-gov-central-0-copper-1`（`INITIAL_ENDOWMENT` / 100,000 / day 0 / period 1）⇒ **一切对 `moneyIssuances` 求和/计数的读数都会 +1 条、+100,000**（`initialEndowment`、`MoneyStock.circulationMinusNetIssuance`、dashboard 的发行栏）；
   - 创世命令链多 3 条（`DefineCurrency` + `AdjustAccounts` 的铜腿 + `RecordMoneyIssuance`）⇒ 创世后的 dump/快照字节与旧档**不同**（预期内）。
2. **`silver` 一切数值不变**：探针逐值断言"改名前后所有账户逐币种余额、逐币种总量不变"、"发行铜时银一分未动"。
3. **经济切片组件数 31 → 33**（`currencies` / `moneyInstruments`）⇒ 任何"按组件计数/逐组件快照比对"的用例/工具要 +2。
4. **`EconomyMeta` 多一个派生读法**（`currentCycleNumber()`，`@JsonIgnore`）⇒ **不进线格式**；按 `EconomyMeta` 字段逐值断言的用例不受影响。
5. **进程级门面**：任何经 `EconomyCodec` 物化的状态都会把 `MoneyVocabulary` 的当前表换成那个世界的表 ⇒ **同一 JVM 内"最后物化的世界"说了算**（跨测试类有顺序依赖风险，见 §6.5 第 2 条）。
6. **省 GOV**：`issuable` 仍为空集（未变）；**世界级 `world-silver` 政府**仍发行 silver（未变）⇒ "一币一发行人"未破。
7. `economy.RegisterGovernment` 若给某 GOV 写了**词表里没有的币种**，现在会**当场拒**（构造期跨表守卫）—— 这是新增的 fail-closed 行为。

### 6.4 受影响的硬编码字面量

| 字面量 | 位置 | 说明 |
|---|---|---|
| `"silver"` | `MoneyVocabulary.SILVER_CURRENCY_ID`（唯一一处，**未动**） | 源扫描护栏 `EconomyVocabularyGuardTest.silverCurrencyIdLiteralIsWrittenExactlyOnce` 仍恰 1 处 ✓ |
| `"copper"`（新） | `MoneyVocabulary.COPPER_CURRENCY_ID`（唯一一处） | 全仓 `src/main` 只此一处；`GovWorldBootstrap` 走 `MoneyVocabulary.COPPER_*` 常量 |
| `"铜"`（新） | `MoneyVocabulary.COPPER_DISPLAY_NAME` | 显示名，非身份 |
| `"银"`（新） | `MoneyVocabulary.SILVER_DISPLAY_NAME` | 同上；**不改** `SILVER_CURRENCY_ID` ⇒ 老世界的身份与账一律不动 |
| `"<id>-specie"` | `EconomyDefineCurrencyHandler.specieInstrumentId`（唯一拼写点） | 与既有 `silver-specie` 同形 |
| `id = "gov-issue-…"` | `EconomyRecordMoneyIssuanceHandler.ID_PREFIX` | 确定性 id 前缀（无随机、无时钟 ⇒ 判据 F7 不受影响） |
| `100_000` | `GovWorldBootstrap.TREASURY_COPPER_MILLI_PER_GOV` | 具名常量；与银同阶（100 铜） |
| `"gov-central"` / `"gov-province"` | `GovWorldBootstrap.CENTRAL_GOV_ID` / `PROVINCE_GOV_ID`（既有） | 未新增字面量 |
| 未新增 | 任何就地的 `new CurrencyId("…")` / `CommodityId("…")` | 护栏 `noModuleSpellsTheSilverCurrencyInline` 仍 0 命中 |

### 6.5 会让既有测试断言失效的清单（给测试代理）

1. **必红（预期，指向缺失的那半）**：`simos-app/src/test/java/io/mosire/simos/app/access/DecisionCallerFactoryTest.java:212-229`
   `theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites` —— 断言"白名单 == 决策人桶逐条相等"；本批给决策人桶加了 3 条工具、白名单那半在别人文件里 ⇒ **当场红**。
   **正确处置**：`DecisionCallerFactory.WHITELIST` 加 3 条（BLOCKED-2），**不是**把工具从桶里删掉（那就把 F8 整条判据拆了）。
2. **顺序依赖风险（必须显式处理）**：`GuiApiTest.moneyVocabularyIsPublishedThroughTheEconomyReadout`（`GuiApiTest.java:1095-1121`）断言 `currencyDefs` **恰 1 条**、`moneyInstruments` 恰 1 条 —— 它读的是**进程级门面**。
   - 它自己的 `@BeforeEach seedGenesis()` 会物化自己的世界（银本位）⇒ 门面在自己的用例里是对的；
   - 但若同 JVM 内**先**跑了小世界创世的用例（`GovZ6WorldFixture` / `GovGenesisZ6Test` / `GovToolsZ6Test` / `Z7RemittanceE2ETest` / `Z7D1CapitalProvinceTest` 用的都是 `SmallWorld`，现在带 copper）且**在断言前没有重新物化**，这两栏就会是 2 条 ⇒ 红。
   - **建议**（测试代理/夹具 owner 二选一）：① 该用例断言**该世界自己的**词表（从状态读，而不是从门面读）；② 或夹具在 `@BeforeEach` 显式 `MoneyVocabulary.installLegacyDefault()` 后自行装表。
   - 我**不能**在 A1 里改这个测试（`src/test/**` 禁碰），也不该为了它把门面同步拆掉（那会让铜世界的 GUI 读口静默只剩 silver）。
3. **可能红（需测试代理核）**：任何对**创世后 dump / 快照字节 / 逐组件计数**做逐值断言的用例 —— 见 §6.3 第 1、3 条（多一个币种、多一张工具、多一条发行记录、多 2 个组件）。
4. **应当仍绿（已用 `test-compile` + 设计核对）**：
   - `MoneyIdentityTest`（economy-api）：`new CurrencyDef("silver", 3)` 走旧形状构造器仍编译；`SILVER.id()/scale()`/`allCurrencyDefs()` 自洽断言在默认（旧世界）门面下成立；
   - `EconomyRoundTripTest`：组件面与 `EconomyChangeSet` 一一对应（新增两个都在），`between/apply` 逐组件覆盖；
   - `EconomyVocabularyGuardTest`：`SILVER_CURRENCY_ID = "silver"` 仍恰 1 处、无就地 `CurrencyId("silver")`；
   - `EconomySeedHandler` 相关用例：首次播种仍是银本位（词表键缺省 ⇒ 旧默认），补种合并不抹。

### 6.6 探针输出（自证；一次性、`/tmp`、不进仓库、不进 `src/test`）

`/tmp/a1-probe/A1Probe.java`（源码在 /tmp，编译/运行都用真 core + 真 store）：

```
== A1 探针（store=/tmp/a1-probe-store…）==
  ✓ ① 新世界币种数 = 2
  ✓ ① 新世界含 silver + copper
  ✓ ① 新世界工具数 = 2
  ✓ ① copper 显示名 = 铜
  ✓ ① 门面（创世经 codec 边界后）= 2 种
  ✓ ① 中央 GOV 是 copper 发行人
  ✓ ① 中央国库铜 = 100000
  ✓ ① 审计主体 = 中央 GOV 的政府记录
  ✓ ① copper 的 INITIAL_ENDOWMENT 审计恰一条
  ✓ ① 审计金额 = 国库那笔铜
  ✓ ① 旧档（缺词表键）币种数 = 1
  ✓ ① 旧档工具数 = 1（silver-specie）
  ✓ ① 旧档读回后门面 = 1 种（codec 边界同步）
  ✓ ① 旧档读回后门面工具 = 1 张
  ✓ ① 从 store 读回（decodeSnapshot 路径）门面 = 2 种
  ✓ ② 越权：省 GOV 载荷点名中央 GOV 改名 ⇒ REJECTED
  ✓ ② 越权：省 GOV 发行中央的 copper ⇒ REJECTED（不是发行人；权限判据不是参数错）
  ✓ ② 越权：省 GOV 给中央 GOV 定义币种 ⇒ REJECTED
  ✓ ② 越权三次都没有落 revision（零写入）
  ✓ ③ preview 不写盘
  ✓ ③ 改名真提交成功
  ✓ ③ 改名 = 一条 revision
  ✓ ③ 显示名改了
  ✓ ③ 币种 id 集不变
  ✓ ③ 精度不变（scale 仍 3）
  ✓ ③ 工具表逐值不变
  ✓ ③ 政府表逐值不变（发行权没被改名碰到）
  ✓ ③ 发行审计表逐值不变
  ✓ ③ 逐账户逐币种余额**逐值不变**
  ✓ ③ 逐币种总量逐值不变
  ✓ ③ 门面显示名跟着改
  ✓ ④ DM 发行真提交成功
  ✓ ④ 一批两条命令 = 恰一条 revision
  ✓ ④ 国库铜 +5000
  ✓ ④ 国库银一分未动
  ✓ ④ 审计记录 +1
  ✓ ④ 新审计 = FISCAL_ISSUE / copper / 5000 / 中央 GOV
  ✓ ⑤ DM 定义新币种真提交成功
  ✓ ⑤ 词表 = 3 种
  ✓ ⑤ 工具表 = 3 张
  ✓ ⑤ 新币种发行权归中央 GOV
  ✓ ⑤ 门面 = 3 种
  ✓ ⑤ 别名不当发行权（省 GOV 仍未发行任何币）

== 全部断言通过 ==   [exit=0]
```

探针**打在边界之后**：每次写都经真 `CommandBus → EconomyCodec.apply` 落到真 core 的 sqlite store，再从 store 读回（`QueryService` → `decodeSnapshot`）断言 —— 读的是"真状态里变成了什么"，不是"命令返回了什么"。
★ 探针**自己**把三条 handler 注册进 `core`（`core.register(...)`）—— 这正是 BLOCKED-1 缺的那三行；也就是说"注册后能真跑通"已被验到，生产路径缺的只是登记。
★ 未覆盖（如实记）：宿主审批链（`AutoApproveGate → ConfirmGate → PendingApprovals`）与 `DecisionCallerFactory` 白名单段不属本探针（那是宿主 `ToolCallAuthorizer` 的职责，且白名单本身就是 BLOCKED-2）。

### 6.7 与约束设计书不一致处

见 §4（7 条，逐条给了原因与代价）与 §5（两处跨责任区 BLOCKED + 后续责任区建议）。
