# S1 阶段 2：切片 `simos-actor`（身份与资产）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让「Actor 的存在与产权」**有地方住** —— 建出 `simos-actor-api`（契约）与 `simos-actor`（切片），装进组合根；**不接管任何现有职责**。

**Architecture:** 先建契约模块，把 `ActorRef`/`ActorKind` 从 `economy-api` **上移**进去（依赖方向变成 `economy-api → actor-api`），再在切片里建 `Actor` / `AssetHolding` / `GoodsAccount` 三个**互不嵌套**的聚合（资产与库存都**不在 Actor 本体里**）。全程**不动 `simos-economy` 一行**。

**Tech Stack:** Java 21 · Maven（`./mvnw`）· JUnit 5 + AssertJ · 本仓的 Snapshot / ChangeSet / ModuleCodec / Resolver / CommandHandler 五件套

**Spec:** `docs/superpowers/specs/2026-09-26-s1-actor-property-design.md`（§2.1 / §2.3 / §三 / §五 / §十一）
**Breakdown:** `docs/superpowers/plans/2026-09-26-s1-stage-breakdown.md` §三 阶段 2

---

## ★ 先修正 breakdown 的两处（**已回代码核过**，2026-09-26）

**① 「1 → 2 硬依赖 `CohortKey`」是误写 —— 阶段 2 不需要 `CohortKey`。**

spec 里 Actor 侧的身份键**全部**是 `ActorRef`（`ProductionActivity.operator`、`AssetHolding.owner`、
`ProductionRelation.operator/residualOwner`）；而 `CohortKey` 在 spec 里出现的每一处都在**劳动侧/消费侧**
（`LaborProvider`、`LaborAllocation`、`ConsumptionReceipt`）。breakdown 那句的括注「`ProductionRelation` 的
recipient 侧」指的其实是 `CompensationRule.recipient` —— **属阶段 5/6 的结算侧**，被写成了 Actor 的身份键。

⇒ **与阶段 1 的收窄（`ClassKey → CohortKey` 推后到阶段 4）不冲突**：两句说的是不同的事。
本阶段**不建 `CohortKey`**。

**② 「`ActorRef` / `ActorKind` / `AssetClassKey` 从 `economy-api` 上移」对第三项不成立。**

清点（全仓 `find` + `grep`，含隐藏目录）：`ActorRef` / `ActorKind` **各 1 份**，同住
`simos-economy-api/.../economy/api/actor/`；而 **`AssetClassKey` 全仓不存在**（0 份，只在 2 个 md 里被提及）。
`AssetHolding` / `GoodsAccount` / `Actor` 本体同样**都不存在**。

⇒ 阶段 2 的真实改动面是 **上移 2 个 + 新建 4 个**。

---

## ★ 本计划的裁定（**请重点看这 5 条**）

### R1. `AssetKind` 随 `ActorRef` 一起上移 —— 否则 `AssetClassKey` 只能做成 fail-open

spec §2.3 L113 要的是「`AssetClassKey` 编码同质性条件（**而非只有 LAND/LOOM/WORKSHOP 三个粗类型**）」
⇒ `AssetClassKey` **必须包含那个粗类型**。而粗类型的唯一词表是
`simos-economy/model/AssetKind`（6 档）。三条路：

| 选项 | 后果 |
|---|---|
| (a) 自由 `String kind` | **fail-open** —— 正是阶段 1 刚用 `SocialClassId` 堵掉的那类口子，自相矛盾 |
| (b) 在 actor-api 另立一套词表 | 同一个概念两份词表 = AGENT.md 点名的"同一资源的两个形状" |
| **(c) 把 `AssetKind` 一起上移（本计划选它）** | 与 `ActorRef` 同一手法的机械移动；**资产种类本就属产权层** |

★ **代价**：`AssetKind` 用法面 **17 文件 / 131 次**（`simos-economy`）+ 9 文件 / 37 次（`simos-app`），
远大于 `ActorRef` 的 4 文件 / 6 行。**这是本阶段最大的一处扩张。**

★★ **裁定（2026-09-26）：R1 通过。附一条执行纪律 —— 必须是纯机械迁移，不趁机改 `AssetKind` 语义。**

```
LAND / CATTLE / TOOL / WORKSHOP / MACHINE / SHIP
```
**原值、序列化名字（`name()`）、解析行为，一律不动。** 168 次引用不是设计风险，是**机械变更风险**。
⇒ **「移动词表」与「重新设计资产类型」绝不许塞进同一个 task。**

★ 依据：`ActorClassKey` 的粗类型已经不属于"生产结算私有概念"，而是**产权领域的基础词表**；
留在 economy 只能走向 `actor-api → economy-api → actor-api` 循环、或复制词表、或退化成 `String`。

### R2. `GoodsAccount` 落 `simos-actor`（切片），**不在** `actor-api`

它的余额要按 `CommodityId` 索引，而 `CommodityId` 住在 `simos-economy-api`
（spec §三 L264 列 actor-api 契约面时**没有**它）⇒ 若把 `GoodsAccount` 放进 `actor-api`，
就成 `actor-api → economy-api → actor-api` **循环**。

⇒ 落在切片里：**契约在 api、状态在切片**，与 economy 先例（`CommodityId` 在 api、`ClassRow` 在切片）同制。
代价：`simos-actor` 依赖 `simos-economy-api`。

★★ **裁定（2026-09-26）：R2 通过。准确的定性不是"切片互不依赖的一次例外"，而是：**

> **actor implementation 消费 economy context 发布的商品身份契约，但不依赖 economy implementation。**

⇒ **两条护栏**（写进 `simos-actor/pom.xml` 的 `bannedDependencies`，**不只是一句注释**）：

| 护栏 | 形式 |
|---|---|
| ✅ 可以依赖 `simos-economy-api` | 允许（契约层） |
| ❌ **禁止依赖 `simos-economy`** | `<exclude>io.mosire:simos-economy</exclude>` |
| ❌ **禁止依赖 `simos-ledger`** | `<exclude>io.mosire:simos-ledger</exclude>` |

message 原文写：「**只允许类型依赖，不允许运行时领域控制流反向流入**（S1 阶段 2 裁定）」。
★ 即：可以 `import CommodityId`，**不许**调用 economy 的 repository / settlement。

★ **不要为尚未发生的问题新增第七个基础模块** —— 若将来 `market`/`transport`/`government`/`ledger`
都开始依赖 `CommodityId`，届时再议是否提取 `simos-goods-api` / shared kernel。

### R3. I2.2 只关**前半句**；后半句归 I3.2

I2.2 原文要"地主持 `LAND`@hex + **另一个 actor 是 operator**"。但 `operator` 是 `Industry` 的字段，
属**阶段 3**（且阶段 2 明写"`economy` 一行不改"）⇒ 后半句本阶段**开不了账**，
而 breakdown 自己已在 **I3.2** 写了"租佃档存在且可表达 AssetOwner ≠ Operator"。

⇒ 阶段 2 关：聚合键 `(owner, hex, assetClass)` 逐值成立 + 「owner ≠ operator」是**两条互不牵连的记录**
（模型里**没有任何一处**把二者绑起来）；绑定本身留给 I3.2。

### R4. `ActorRef.parse` 签名**原封不动**

它是**两参** `parse(kindText, idText)`，**不是** `toString()` 的逆（`"UNIT:u-1"` 喂不回去）。
上移**不改变**这个不对称；★ **不许顺手"修好"它** —— 那会改读侧契约并打破
`EconomyIdsTest` 的既有断言。

### R5. 上移时**不加** deprecated 转发壳

I2.3 要的是"`economy-api` **不再拥有** `ActorRef`"。转发壳会让它继续拥有（只是换个名字）。
⇒ 直接改全仓 import，**一次搬干净**（**编译失败就是迁移清单**）。

★ **裁定（2026-09-26）：R5 通过。** 理由：留下 `@Deprecated economy.api.ActorRef` 等于表达
"economy-api 仍是这个概念的公开入口之一" ⇒ **两个 canonical import path**，后续极难彻底清理。
这是**仓内模块迁移**，不是对外发布数年的 SDK ABI 兼容问题。

### R6. `GoodsAccount` 是**新产权模型的唯一真源**；`ledger.Account` 保持 legacy/unwired

★★ **这是本轮唯一一处"临时裁定"，必须写进 spec 与计划**（用户 2026-09-26 明令）：

> **S1 起，`GoodsAccount` 是新产权模型中商品余额的 `authoritative state`；
> 既有 `simos-ledger.Account` 保持 legacy/unwired —— 不与 `GoodsAccount` 双写、不做镜像同步。**

**最忌讳的不是两个类同时存在**，而是：

```
actor.GoodsAccount = 100 grain
ledger.Account     = 100 grain     ← 谁是"真的"？
```

⇒ 现状明确为：`GoodsAccount` = 新模型真源；`ledger.Account` = **旧死代码：不读、不写、不同步**，
去留（D1）另裁。

★ **反面纪律**：**不许**为了"复用"而把 `ledger.Account` 强行拉活 —— 那会提前把
S2 的 `money` / `reserved` / settlement 一大坨**未裁领域**拖进 S1。

### R7. 三段**硬隔离**：搬家 / 新契约 / 新模型，不许混改

R1 波及 168 处 ⇒ **若 Task C 爆了，必须能判断"是新模型错"还是"168 处迁移漏了"**。
⇒ 计划里存在三道**关账点**，每道**全仓恢复绿**才进下一道：

| 段 | 内容 | 关账条件 |
|---|---|---|
| **A** | Task 1 + Task 2：`ActorRef` / `ActorKind` / `AssetKind` **上移** | ★ **只搬家、不改语义**；全仓 `clean verify` 绿；`grep` 旧包名 = 0 |
| **B** | Task 3：`actor-api` 的**新**领域类型（`AssetClassKey`） | ★ **不得回头修改迁移契约**（`ActorRef`/`ActorKind`/`AssetKind` 的签名与行为） |
| **C** | Task 4–9：`simos-actor` 切片（`Actor` / `AssetHolding` / `GoodsAccount` / codec / SPI / 装配） | 各任务自己的验收 + 最终 I2.1/I2.2/I2.3 |

★ **A 段单独提交**，提交信息里逐条列出"哪些模块改了几个 import"。
★ B/C 段若发现迁移契约定错了 ⇒ **不许就地改**，回头开一个新提交修 A 段。

---

## Global Constraints

- **Java 21**；只走 `./mvnw`
- **门禁 fail-closed**：看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，**不看 rc**
- **测试一律一次一个类**（`-Dtest=A,B` 多类过滤会假绿 —— R2 踩过）
- ★★ **`-pl X -am` 与 `-Dtest=Y` 并用会在依赖模块报 `No tests matching pattern`** ⇒ 必须加
  `-Dsurefire.failIfNoSpecifiedTests=false`，**并以 surefire 报告核对真的跑了那个类**
- **一次只能跑一个 Maven**；跑前 `pgrep -af "surefirebooter|classworlds.launcher"`
- ★★ **逐任务的验证命令必须含 `spotless:check`**（**Task 1 实测教训**）：本仓 `spotless-check` 绑在
  `verify`，而各任务 brief 里的命令多是 `package` ⇒ **格式违规看不到、一路积累到收尾 `clean verify` 才炸**。
  发现违规**用本仓自己的 `./mvnw spotless:apply` 修**（**别手写折行**）。
- **收尾前台跑 `./mvnw clean verify`**，把 Reactor 逐模块结果抄进报告
- **注释与提交信息用中文**（`AGENT.md` §五.8）
- **护栏必须自证**：每条新断言都要**变异自证**（改坏 ⇒ 当场红 ⇒ `Edit` 反向重写还原 ⇒ 复绿）
- ★★ **变异体必须真的打到被测的那一层**（**Task 1 实测教训**）：brief 原写"加一条**不带版本号**的依赖"
  来验 enforcer —— 那种写法在 **POM 校验阶段**就红（根 pom 的 `dependencyManagement` 不管该坐标），
  **根本走不到 `bannedDependencies`** ⇒ "红"来自别处，等于没测。
  ⇒ 写变异体前先自问：**若这条规则不存在，这个变异体还会红吗？会红就换个变异体。**
- ★ **不许放宽既有断言**；因本阶段而变红的，按新口径**手算重算**期望值（算式写进注释），**不许抄实际值**
- ★ **不 `git add -A`**；按批次提交
- ★ **本阶段不许改 `simos-economy` 的 main 代码**（I2.1 是"纯增量"）—— 唯一的例外是
  **import 行**（上移导致的机械修正），且必须在提交信息里逐条列出改了几行、改了什么

## Review Focus

spec 隐含要求但**没有任何任务覆盖**的五类，最可能咬到人：

1. **`ActorData` 的三张表不许出现"同一事实记两处"** —— `AssetHolding` 与 `GoodsAccount` 都以
   `(owner, hex)` 定位；若哪天有人把库存同时写进 `Actor` 本体，spec §三 L283 的禁令就被绕过。
   期望：**结构断言**（`Actor` 没有 goods/money/debts 字段）
2. **同一 `(owner, hex, assetClass)` 建两条 `AssetHolding`** —— 期望：**后写覆盖前写**（聚合键的唯一性），
   且**数量是相加还是覆盖要说清**（本计划定为**覆盖**：调用方给的是"该余额是多少"，不是"加多少"）
3. **同一个 owner 在两个 hex 各有一份 `AssetHolding`** —— 期望：**两条，不合并**（hex 是键的一部分）
4. **`AssetClassKey` 的 `qualities` 顺序不同**（`{quality=B, arable=true}` vs `{arable=true, quality=B}`） ——
   期望：**同一个键**（规范串按键排序 ⇒ `equals`/`hashCode`/`toString` 都稳定），否则同一块地会有两份产权
5. **空 `AssetData`（未激活）经 codec 往返** —— 期望：往返后**逐字段相同**，不是"任意空也相等"

---

## File Structure

| 文件 | 职责 |
|---|---|
| `simos-actor-api/src/main/java/io/mosire/simos/actor/api/actor/ActorRef.java` | **移动**（原 `economy/api/actor/`） |
| `simos-actor-api/.../actor/ActorKind.java` | **移动**（七档词表） |
| `simos-actor-api/.../asset/AssetKind.java` | **移动**（R1）—— 六档粗类型 |
| `simos-actor-api/.../asset/AssetClassKey.java` | **新建**：粗类型 + qualities，规范串排序 |
| `simos-actor/src/main/java/io/mosire/simos/actor/model/Actor.java` | **新建**：身份本体，**不含**资产/库存 |
| `simos-actor/.../model/AssetHolding.java` + `AssetHoldingKey.java` | **新建**：产权，聚合键 `(owner, hex, assetClass)` |
| `simos-actor/.../model/GoodsAccount.java` + `GoodsAccountKey.java` | **新建**：库存余额，键 `(owner, hex)` |
| `simos-actor/.../ActorData.java` / `ActorMeta.java` / `ActorSnapshot.java` | **新建**：状态树 + 落盘切片 |
| `simos-actor/.../change/ActorChangeSet.java` | **新建**：`implements ChangeSet` |
| `simos-actor/.../codec/ActorCodec.java` | **新建**：`implements ModuleCodec, ModuleDiffer` |
| `simos-actor/.../resolve/ActorResolver.java` | **新建** |
| `simos-actor/.../spi/ActorPayloads.java` / `ActorSeedHandler.java` / `ActorSnapshots.java` | **新建**：命令面 |

---

### Task 1: 建 `simos-actor-api` 模块骨架（含 enforcer 变异自证）

**Files:**
- Modify: `pom.xml:16-39`（`<modules>`）、`pom.xml:66-135`（`<dependencyManagement>`）
- Create: `simos-actor-api/pom.xml`、`simos-actor-api/src/main/java/io/mosire/simos/actor/api/package-info.java`

**Interfaces:**
- Produces: 一个能被 reactor 构建、且**拦得住越界依赖**的空契约模块（坐标 `io.mosire:simos-actor-api`）

- [ ] **Step 1: 加进 reactor**

`pom.xml` 的 `<modules>` 里，在 `<module>simos-economy-api</module>` **之前**插一行
`<module>simos-actor-api</module>`（它在依赖图上更底层）。
`<dependencyManagement>` 里照第 87-89 行的形制追加 `simos-actor-api` 一条。

- [ ] **Step 2: 写 `simos-actor-api/pom.xml`**

照 `simos-economy-api/pom.xml` 抄，改四处：
- 坐标三行（`artifactId` / `name` / `description`）
- dependencies：**`simos-util`** + `jackson-databind` + `junit-jupiter` + `assertj-core`
  + `spotbugs-annotations:4.10.4:provided`
  ★ **不加 `simos-map`**（SDD 预检裁定 R-e）：`AssetHolding` 要 `HexCoord`，但它住
  **`simos-actor`（切片）不在本 api**；`AssetClassKey` 也只用 `AssetKind` + `Map<String,String>`。
  ⇒ 本模块**此刻**不需要 map —— 与 economy-api 那份"声明了却零 import 的死依赖"不同，
  这里是**干脆不声明**（YAGNI）。要加就等真有 api 侧类型用到 `HexCoord` 时再加。
- ★ **enforcer 的 ban 列表要回填本仓全部模块**（`AGENT.md:55-58` 点名批评过"新增模块从未回填"）：
  `simos-economy-api`、`simos-economy`、`simos-ledger`、`simos-social`、`simos-unit`、`simos-sd`、
  `simos-core`、`simos-app`、`agentlib-mosire` —— message 写
  「actor-api 是最底层的共用契约：只剩 util/map/jackson 可用」

- [ ] **Step 3: 写 `package-info.java`**

照 `simos-economy-api/.../api/package-info.java` 的结构：声明本模块只放**共用契约**、
依赖方向（`actor-api ← economy-api ← {economy, ledger}`）、以及"不许反向依赖任何领域/编排模块"。

- [ ] **Step 4: 空跑一次构建**

Run: `./mvnw -pl simos-actor-api -am -DskipTests package`
Expected: **BUILD SUCCESS**，且 reactor 里出现 `ActorApiSimos`

- [ ] **Step 5: ★ 变异自证（enforcer 真的拦得住）**

临时在 `simos-actor-api/pom.xml` 的 `<dependencies>` 里加一条
`<dependency><groupId>io.mosire</groupId><artifactId>simos-app</artifactId></dependency>`。

Run: `./mvnw -pl simos-actor-api -am -DskipTests package`
Expected: **BUILD FAILURE**，消息含 `actor-api 是最底层的共用契约`

⇒ `Edit` 反向重写删掉那条 ⇒ 复跑 ⇒ **BUILD SUCCESS**。把两次的实际输出抄进台账。

- [ ] **Step 6: 提交**

```bash
git add pom.xml simos-actor-api/pom.xml simos-actor-api/src/main/java/io/mosire/simos/actor/api/package-info.java
git commit -m "build(actor-api): 新建 simos-actor-api 契约模块（S1 阶段 2 第一步）"
```

---

### Task 2: 上移 `ActorRef` / `ActorKind`（+ `AssetKind`，R1）

**Files:**
- Move: `simos-economy-api/src/main/java/io/mosire/simos/economy/api/actor/{ActorRef,ActorKind}.java`
  → `simos-actor-api/src/main/java/io/mosire/simos/actor/api/actor/`
- Move: `simos-economy/src/main/java/io/mosire/simos/economy/model/AssetKind.java`
  → `simos-actor-api/src/main/java/io/mosire/simos/actor/api/asset/`
- Modify: `simos-economy-api/pom.xml`（加 `simos-actor-api` 依赖）、`simos-economy/pom.xml`、
  `simos-ledger/pom.xml`、`simos-app/pom.xml`（各加 `simos-actor-api`）
- Modify: **全仓 import 行**（清点见下）

**Interfaces:**
- Produces: `io.mosire.simos.actor.api.actor.ActorRef(ActorKind kind, String id)`、
  `io.mosire.simos.actor.api.actor.ActorKind`（七档）、
  `io.mosire.simos.actor.api.asset.AssetKind`（六档） —— **签名与 `toString`/`parse` 一字不改**

**清点（Agent 实测，2026-09-26）：**

| 类型 | 需改 import 的模块 | 生产代码 | 测试 |
|---|---|---|---|
| `ActorRef` | `simos-economy-api` `simos-economy` `simos-ledger` `simos-app` | **4 文件 / 6 行** | 7 文件 |
| `ActorKind` | 同上 | 同上 | 同上 |
| `AssetKind` | `simos-economy` `simos-app` | 26 文件 / 168 次 | 视情况 |

★ `simos-util` / `simos-map` / `simos-social` / `simos-unit` / `simos-core` / `simos-sd` **六模块零引用** ——
不用碰。

- [ ] **Step 0: ★ 先跑一遍基线（**搬家之前**）**

Run: `./mvnw clean verify`（改任何一行**之前**）
Expected: **BUILD SUCCESS** —— 把逐模块测试数抄进台账。★ 这是 A 段的**对照基线**：
搬完之后若红了，红的东西**就是迁移漏的**，不用猜。

- [ ] **Step 1: ★ characterization test 先行（裁定 R4 明令）**

**先钉住既有行为，再搬。** 两处：

1. `simos-economy/.../spi/EconomySeedHandlerTest.java`：对现存的
   `"actor":{"kind":"ESTATE","id":"farm@0_0"}` 载荷加一条**显式的逐值断言**
   （`EconomyPayloads.parse(payload)` 之后 `ActorRef` 的 `kind()`/`id()` 逐值还原，
   且**再编码回去仍是同一串**）。
2. `simos-ledger/.../codec/LedgerCodecTest.java` 已有的往返用例**保持原样**并跑一遍
   —— 它就是 `Claim`/`Transfer`/`Account` 三处 `ActorRef` 字段的 characterization test。

★ 这是上移**唯一**的格式护栏（本仓**没有** JSON 黄金夹具，I2.1 只能靠测试）。

Run（**一次一个类**）：
`./mvnw -pl simos-economy -am test -Dtest=EconomySeedHandlerTest -Dsurefire.failIfNoSpecifiedTests=false`
`./mvnw -pl simos-ledger -am test -Dtest=LedgerCodecTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **PASS**（两条都要以 surefire 报告核对真的跑了）

- [ ] **Step 2: 移动文件（用 `git mv`，保住历史）**

```bash
git mv simos-economy-api/src/main/java/io/mosire/simos/economy/api/actor/ActorRef.java \
       simos-actor-api/src/main/java/io/mosire/simos/actor/api/actor/ActorRef.java
git mv simos-economy-api/src/main/java/io/mosire/simos/economy/api/actor/ActorKind.java \
       simos-actor-api/src/main/java/io/mosire/simos/actor/api/actor/ActorKind.java
git mv simos-economy/src/main/java/io/mosire/simos/economy/model/AssetKind.java \
       simos-actor-api/src/main/java/io/mosire/simos/actor/api/asset/AssetKind.java
```

三份文件的 `package` 行改成新包。

- [ ] **Step 3: 改 POM 依赖**

- `simos-economy-api/pom.xml`：加 `simos-actor-api`（`LaborAllocation.actor` 持有 `ActorRef`，
  不加就编译不过）★ 注意 I2.3 与"`LaborAllocation` 仍用 `ActorRef`"是**两个共存事实** ——
  "不再拥有定义" ≠ "不再引用"
- `simos-economy/pom.xml` / `simos-ledger/pom.xml` / `simos-app/pom.xml`：各加 `simos-actor-api`
  （原先靠 `simos-economy-api` 传递拿到）
- ★ `simos-app/pom.xml:30-32` 有明文自认"靠传递依赖是缺陷" ⇒ 必须**显式**加

- [ ] **Step 4: 改全仓 import**

`grep -rln "economy.api.actor.ActorRef\|economy.api.actor.ActorKind\|economy.model.AssetKind" simos-*/src`
⇒ 逐个 `Edit`（**不许用 sed 批量**：R4 曾用脚本切片误删 10 个测试方法）。

- [ ] **Step 5: 跑受影响的全部测试（一次一个类）**

`EconomyIdsTest` / `LaborTypesTest` / `EconomySeedHandlerTest` / `LedgerCodecTest` /
`LedgerModelInvariantsTest` / `LedgerRoundTripTest` / `EconomyCodecTest` / `EconomyRoundTripTest` /
`EconomyInvariantsTest` / `GuiApiTest` / `EconomyTestWorld`（及引用它的各类）。

★ 其中 `LedgerCodecTest` / `LedgerRoundTripTest` / `EconomyCodecTest` / `EconomyRoundTripTest`
是 **I2.1 的唯一格式护栏**（本仓无 JSON 夹具可 diff）—— **必须真跑**。

- [ ] **Step 6: 把 ActorKind/ActorRef 的契约测试搬去它们的家**

`simos-economy-api/src/test/java/io/mosire/simos/economy/api/EconomyIdsTest.java` 里的四条
（`actorKindCoversTheSevenDocumentedKinds`、`actorKindParseRejectsTextOutsideTheVocabularyAndListsLegalValues`、
`actorRefValidatesKindAndId`、`actorRefParseHasBothRefusalReasons`）**移到** `simos-actor-api` 的测试目录，
`EconomyIdsTest` 里那四条**删掉**（不是复制 —— 复制会变成两处真相）。
★ `IRef` 的 `toString` 断言（`"UNIT:u-1"`）跟着搬。

- [ ] **Step 7: 变异自证（R4 的不对称是**故意的**）**

临时把 `ActorRef.parse` 改成"吃 `toString()` 的单参逆"，跑搬过来的那条测试。
Expected: **编译错或断言红** ⇒ 证明"保持两参"这条裁定是**被测试钉住的**，不是随手写的。
⇒ `Edit` 反向重写还原 ⇒ 复绿。

- [ ] **Step 8: 门禁 + 提交**

Run: `./mvnw clean verify`
Expected: **BUILD SUCCESS**（I2.1 全绿；I2.3 达成：`grep -rn "class ActorRef\|enum ActorKind" simos-economy-api/src/main` 为空）

提交信息里**逐条列出**改了哪些 import（模块 → 文件数）。

---

### Task 3: `AssetClassKey`（新建，TDD）

**Files:**
- Create: `simos-actor-api/src/main/java/io/mosire/simos/actor/api/asset/AssetClassKey.java`
- Test: `simos-actor-api/src/test/java/io/mosire/simos/actor/api/asset/AssetClassKeyTest.java`

★★ **B 段纪律（裁定 R7）**：本任务**不得回头修改 A 段的迁移契约** ——
`ActorRef` / `ActorKind` / `AssetKind` 的**签名、`name()`、`parse`、`toString` 行为一个字都不许动**。
发现自己需要改它们 ⇒ **停下来，回头另开一个提交修 Task 1/2**，不许就地改。

**Interfaces:**
- Consumes: `AssetKind`（Task 2）、`java.util.Map`
- Produces: `record AssetClassKey(AssetKind kind, Map<String, String> qualities)` +
  `parse(String)` / `toString()`；规范串 `"<KIND>|k1=v1|k2=v2"`（**qualities 按键排序**）

- [ ] **Step 1: 写失败的测试**

```java
class AssetClassKeyTest {

  /** ★★ **qualities 的顺序不许影响身份** —— 否则同一块地会有两份产权。 */
  @Test
  void keyOrderInQualitiesDoesNotChangeIdentity() {
    AssetClassKey a = new AssetClassKey(AssetKind.LAND, Map.of("arable", "true", "quality", "B"));
    AssetClassKey b = new AssetClassKey(AssetKind.LAND, Map.of("quality", "B", "arable", "true"));
    assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).hasToString(b.toString());
  }

  /** ★ spec §2.3 点名的那个实例。 */
  @Test
  void landArableQualityBIsExpressible() {
    assertThat(AssetClassKey.land(Map.of("arable", "true", "quality", "B")))
        .hasToString("LAND|arable=true|quality=B");
  }

  /** ★ 往返：`parse(toString()) == 自身`（它是 Map 的键，这条挂了会**静默丢产权**）。 */
  @Test
  void roundTripsThroughItsCanonicalString() {
    for (String text : List.of("LAND|arable=true|quality=B", "CATTLE", "TOOL|tech=T1")) {
      assertThat(AssetClassKey.parse(text)).hasToString(text);
    }
  }

  /** ★ **词表外即抛**（fail-closed）；qualities 的键值不许含分隔符（否则规范串不可逆）。 */
  @Test
  void rejectsMalformedText() {
    assertThatThrownBy(() -> AssetClassKey.parse("NOPE|a=b"))
        .as("粗类型词表外必须抛，且消息里点名那个值")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NOPE");
    assertThatThrownBy(() -> AssetClassKey.parse("LAND|noEqualsSign"))
        .as("qualities 段没有 `=` ⇒ 不可逆 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AssetClassKey.parse("LAND|k=a|k=b"))
        .as("★ 同一个键出现两次 ⇒ 规范串不可逆（解析回来只剩一个）⇒ 必须抛")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 无 qualities 的粗类型也算合法键（`CATTLE` 就是一个）；`null` 与空 Map 等价。 */
  @Test
  void bareKindIsAValidKey() {
    assertThat(AssetClassKey.parse("CATTLE")).isEqualTo(new AssetClassKey(AssetKind.CATTLE, Map.of()));
    assertThat(new AssetClassKey(AssetKind.CATTLE, null))
        .as("null qualities 归一成空表，不是 NPE")
        .isEqualTo(new AssetClassKey(AssetKind.CATTLE, Map.of()));
  }
}
```

- [ ] **Step 2: 跑测试确认它失败**

Run: `./mvnw -pl simos-actor-api -am test -Dtest=AssetClassKeyTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: **FAIL** —— 编译错（`AssetClassKey` 不存在）

- [ ] **Step 3: 写实现**

```java
/**
 * ★★ **资产同质性键**（S1 spec §2.3）：`kind` 是**封闭词表** {@link AssetKind} 的粗类型，
 * `qualities` 是**影响生产的同质性条件** —— "一块 B 等地"与"一块 C 等地"是两个不同的键。
 *
 * <p>★★ **qualities 按键排序**：规范串 `"<KIND>|k1=v1|k2=v2"` 里键必须有序，否则
 * `Map.of("a","1","b","2")` 与 `Map.of("b","2","a","1")` 会变成**两个键** —— 同一块地两份产权。
 *
 * <p>★ **fail-closed**：粗类型词表外即抛；qualities 的键/值不许含 `|` 或 `=`（否则规范串不可逆）。
 *
 * <p>★ **`qualities` 具体编码哪些维度**：spec §十一 明标**未决策** —— 本类型**不预设任何维度名**，
 * 只保证"给定一组键值，身份唯一且规范"。
 */
public record AssetClassKey(AssetKind kind, Map<String, String> qualities) { ... }
```

★ **只给 `land(Map)` 一个工厂**（把 spec §2.3 的点名实例 `LAND(arable, quality=B)` 拼出来）。

★★ **不许**顺手加一个 `loom(Map)` 工厂把织机映射到某个 `AssetKind` ——
`AssetKind` 的六档是 `LAND/CATTLE/TOOL/WORKSHOP/MACHINE/SHIP`，**没有 `LOOM`**，
而 spec §2.3 的 `LOOM(handloom, tech=T1)` 只是**文档举例**。凭空发明一个映射
正是 R1 明令禁止的"趁机改 `AssetKind` 语义"。⇒ **要就等 `AssetKind` 真的增档时再加**。

- [ ] **Step 4: 跑测试确认通过** ⇒ **PASS（5/5）**

- [ ] **Step 5: 变异自证**

把 `qualities` 改成 `TreeMap` → `LinkedHashMap`（丢掉排序），跑 `AssetClassKeyTest`。
Expected: **FAIL**（`keyOrderInQualitiesDoesNotChangeIdentity` 红 —— 两个 Map 的 `equals` 本就相等，
但 `toString` 会不同，故 `hasToString` 那半条当场红）。
★ **如实记录你实际用的变异体与它红在哪一条**（若这一变异不红，说明判别力不在 `toString` 而在别处，
把它找出来）。

- [ ] **Step 6: 提交**

---

### Task 4: `Actor` + `ActorData` + `ActorSnapshot` + `ActorChangeSet` + 往返测试

**Files:**
- Create: `simos-actor/src/main/java/io/mosire/simos/actor/{ActorData,ActorSnapshot,ActorMeta}.java`
- Create: `simos-actor/.../model/Actor.java`
- Create: `simos-actor/.../change/ActorChangeSet.java`
- Create: `simos-actor/src/test/java/io/mosire/simos/actor/change/ActorRoundTripTest.java`
- Create: `simos-actor/pom.xml`（照 `simos-economy/pom.xml`，依赖 `simos-util` + `simos-actor-api`
  + `simos-economy-api`（R2）+ jackson + junit + assertj）
  ★★ **R2 的两条护栏（写进 enforcer，不是注释）**：
  `<exclude>io.mosire:simos-economy</exclude>`、`<exclude>io.mosire:simos-ledger</exclude>`，
  message：「只允许类型依赖，不允许运行时领域控制流反向流入（S1 阶段 2 裁定）」
  ⇒ ★ **变异自证**：临时加一条 `simos-economy` 依赖 ⇒ 构建**必须红** ⇒ 删掉 ⇒ 复绿
- Modify: `pom.xml`（`<modules>` 里 `<module>simos-actor</module>` 放 `simos-actor-api` 之后、`simos-app` 之前；
  `<dependencyManagement>` 加一条）

**Interfaces:**
- Produces: `record Actor(ActorRef ref, String label)`；
  `ActorChangeSet implements ChangeSet`；`ActorSnapshot implements Snapshot`（`namespace()` 恒返回 `"actor"`）

★★ **`ActorData` 的三张表按任务顺序**增量**加 —— 每加一张，同一个提交里同步改
`ActorData` 的组件列表 + `ActorChangeSet` 的字段 delta + `ActorRoundTripTest` 的往返断言**：

| 任务 | `ActorData` 此刻的组件 |
|---|---|
| **Task 4（本任务）** | `(Optional<ActorMeta> meta, Map<ActorRef, Actor> actors)` |
| Task 5 | `+ Map<AssetHoldingKey, AssetHolding> holdings` |
| Task 6 | `+ Map<GoodsAccountKey, GoodsAccount> accounts` |

★ 这样每个任务**自身可编译、可测、可评审**（不让 Task 4 去引用还不存在的类型）。

- [ ] **Step 1: 写 `Actor`（身份本体，**明确不含**资产/库存）**

```java
/**
 * ★★ **Actor 的身份本体**（S1 spec §三 L277）：**只有身份**。
 *
 * <p>★★ **为什么资产与库存都不在这里**（spec §2.3 L109、§三 L283/L290）：
 * "资产是 Actor **拥有的关系**，不是 Actor **本体的一部分**" —— 卖掉 30% 土地只改
 * {@code AssetHolding}，不用打开整个 Actor aggregate。库存同理（{@code GoodsAccount}）。
 *
 * <p>★★ **禁令**（spec §三 L283 原文）：**不许**出现 `ActorRow { Money money; List<Debt> debts; }`
 * 这种形状 —— 它会把 S1 刚拆开的产权/消费混合当场复活。
 */
public record Actor(ActorRef ref, String label) { /* 校验：label 非空白 */ }
```

- [ ] **Step 2: 写 `ActorData` + `ActorSnapshot`**

照 `EconomyData` / `EconomySnapshot` 的形制。★ `namespace()` **必须**返回字面量 `"actor"`
（`SimulationState` 构造期校验"modules 的键 == snapshot.namespace()"）。

- [ ] **Step 3: 写 `ActorChangeSet` + 往返测试（铁律 5）**

照 `EconomyChangeSet` / `EconomyRoundTripTest`。★ 往返测试必须**逐字段**断言
（`apply(changeSet, base)` 重建 target），**不是**"两个空对象也相等"。

- [ ] **Step 4: 跑测试** ⇒ `ActorRoundTripTest` **PASS**

- [ ] **Step 5: 结构断言（Review Focus ①）**

加一条测试：`Actor` 的 record 组件**只有** `ref` 与 `label`
（用反射断言组件名集合），**防**将来有人把 goods/money/debts 塞回来。

- [ ] **Step 6: 变异自证**（把 `ActorChangeSet` 的某个字段 delta 删掉 ⇒ 往返红 ⇒ 还原）

- [ ] **Step 7: 提交**

---

### Task 5: `AssetHolding`（聚合键 + I2.2 前半句）

**Files:**
- Create: `simos-actor/.../model/AssetHolding.java`、`AssetHoldingKey.java`
- Test: `simos-actor/src/test/java/io/mosire/simos/actor/model/AssetHoldingTest.java`

**Interfaces:**
- Produces: `record AssetHoldingKey(ActorRef owner, HexCoord location, AssetClassKey assetKey)`；
  `record AssetHolding(ActorHoldingKey key, long quantity)`
- `ActorData.holdings: Map<AssetHoldingKey, AssetHolding>`

- [ ] **Step 1: 写真值表式的测试**

```java
  /** ★★ I2.2 前半句：聚合键是 (owner, hex, assetClass) —— 三者任一不同就是**另一份**产权。 */
  @Test
  void theAggregationKeyIsOwnerHexAndAssetClass() { ... }

  /** ★ 同一 owner 在两个 hex 各持一份 ⇒ **两条，不合并**（hex 是键的一部分）。 */
  @Test
  void theSameOwnerAtTwoHexesHasTwoHoldings() { ... }

  /** ★ 同一个 hex 上，owner A 与 owner B 各持一份 ⇒ **两条**（这正是佃制的形状）。 */
  @Test
  void twoOwnersAtTheSameHexAreTwoHoldings() { ... }

  /** ★★★ I2.2 后半句的**可表达性**（R3）：owner ≠ operator 是**两条互不牵连的记录** —— 
   *  模型里**没有任何一处**把二者绑起来（本阶段没有 operator 字段，故这条断言是"形状断言"）。 */
  @Test
  void ownershipAndOperationAreTwoIndependentFacts() { ... }

  /** ★ 负数量即抛（数量是余额，不是增量）。 */
  @Test
  void rejectsNegativeQuantity() { ... }
```

- [ ] **Step 2-3: 跑红 ⇒ 实现 ⇒ 跑绿**

- [ ] **Step 4: 变异自证**（把聚合键里的 `location` 抽掉 ⇒ 第三条红 ⇒ 还原）

- [ ] **Step 5: 提交**

---

### Task 6: `GoodsAccount`（Review Focus ⑤ · ★ 裁定 R6 的落点）

★★ **R6：本类型一建出来就是新产权模型的 `authoritative state`。**
既有 `simos-ledger.Account` 保持 **legacy/unwired** —— **不读、不写、不同步、不做镜像**。
⇒ 本任务的类注里要**明写这一句**；并且**不许**为了"复用"去引用 `simos-ledger` 的任何类型
（R2 的 enforcer 已经把它 ban 了 —— 若你真想引用，编译器会拦住你，那正是它存在的意义）。

**Files:**
- Create: `simos-actor/.../model/GoodsAccount.java`、`GoodsAccountKey.java`
- Test: `simos-actor/src/test/java/io/mosire/simos/actor/model/GoodsAccountTest.java`

**Interfaces:**
- Produces: `record GoodsAccountKey(ActorRef owner, HexCoord location)`；
  `record GoodsAccount(GoodsAccountKey key, Map<CommodityId, Long> balances)`
- `ActorData.accounts: Map<GoodsAccountKey, GoodsAccount>`

- [ ] **Step 1: 写测试**

```java
  /** ★ 键是 (owner, hex)：同一个 owner 在两格的库存是两本账。 */
  @Test
  void theAccountKeyIsOwnerAndHex() { ... }

  /** ★★ **库存是存量**（spec §2.5 L166）：余额不得为负；0 余额**保留**（不是删键）——
   *  否则"这一格这个人手里还有 0 斤粮"和"这个人不在这格"就分不开了。 */
  @Test
  void zeroBalanceIsKeptNotDropped() { ... }

  @Test
  void rejectsNegativeBalance() { ... }
```

- [ ] **Step 2-3: 跑红 ⇒ 实现 ⇒ 跑绿**

- [ ] **Step 4: 变异自证**

- [ ] **Step 5: 提交**

---

### Task 7: `ActorCodec`（JSON 往返 + namespace 三处同字面）

**Files:**
- Create: `simos-actor/.../codec/ActorCodec.java`
- Test: `simos-actor/src/test/java/io/mosire/simos/actor/codec/ActorCodecTest.java`

**Interfaces:**
- Produces: `ActorCodec implements ModuleCodec, ModuleDiffer`，`namespace()` 返回 `"actor"`

- [ ] **Step 1: 写往返测试**（照 `EconomyCodecTest`：正例 + 空态 + 非法态）
- [ ] **Step 2: 跑红 ⇒ 实现 ⇒ 跑绿**
- [ ] **Step 3: ★ Review Focus ⑤**：空 `ActorData` 往返后**逐字段相同**（不是"任意空也相等"）
- [ ] **Step 4: 变异自证**（把 `namespace()` 改成别的字面量 ⇒ 装配期校验红）
- [ ] **Step 5: 提交**

---

### Task 8: SPI（`ActorPayloads` + `ActorSeedHandler` + `ActorSnapshots`）+ `ActorResolver`

**Files:**
- Create: `simos-actor/.../spi/{ActorPayloads,ActorSeedHandler,ActorSnapshots}.java`
- Create: `simos-actor/.../resolve/ActorResolver.java`
- Test: 各一份

**Interfaces:**
- `ActorSeedHandler implements CommandHandler, CommandTargets`，`type()` 返回 `"actor.Seed"`
- 载荷形状照 economy 的 `entries[]`（逐格），字段：`actors[]` / `holdings[]` / `goods[]`

★ **本任务的存在理由**：没有写入口的切片是"空壳"，`ActorData` 只能在单测里被构造 ⇒
"有地方住"这句话就落不到实处。★ 若 `actor.Seed` 与将来的 `actor.*` 命名冲突，以本任务为准。

- [ ] **Step 1-5: TDD 循环**（载荷校验：词表外即抛 / 负数量即抛 / 重复格即拒）
- [ ] **Step 6: 变异自证**
- [ ] **Step 7: 提交**

---

### Task 9: 装配进组合根 + 回填文档 + 门禁

**Files:**
- Modify: `simos-app/pom.xml`（显式加 `simos-actor-api` + `simos-actor` 两条）
- Modify: `simos-app/.../Shell.java`（**5 个注册点**：第 385-390 codec / 第 393-455 handler /
  第 507-514 resolver / 第 878 起 `gmPermissionSet` 加 `ToolSupport.ACTOR_NAMESPACE`
  —— ★ 并在 `ToolSupport.java` 里照 `ECONOMY_NAMESPACE`（第 96 行）的形制加这个常量，
  **字面量必须是 `"actor"`**，与 `ActorSnapshot.namespace()` / `ActorCodec.namespace()` **三处同字面**）
- Modify: `simos-app/.../world/RichWorld.java:88-105`（空切片 + `codecTable()`）
- Modify: `simos-app/.../world/CorridorWorld.java:96`
- Modify: `simos-app/.../gui/ApiViews.java`（照第 1406-1417 加 `actorData(SimulationState)`）
- Modify: **4 个 app 测试的硬编码 codec 清单**：`ShellSmokeTest.java:233`（★ 第 109 行还有一条
  `.as("壳应注册 map/social/unit/sd/economy 五个 codec…")` 的**断言文案**要改）、
  `ShellEndToEndTest.java:431`、`McpCoverageTest.java:604`、`ShellApprovalTest.java:396`
- Modify: `AGENT.md:28-63`（模块表 + 依赖图 + ban 列表回填那一节）

**Interfaces:**
- Produces: 一个**真的起得来**、`/api/actor/hex` 读得通、MCP 面认识的第六个切片

- [ ] **Step 1: 逐处接线**（照上表，**一处一处 Edit**）
- [ ] **Step 2: 检查 `simos-core/pom.xml:88-125`**

若新模块会经已有模块**传递**进 core 的 test classpath，需在 `<includes>` 补
`io.mosire:simos-actor-api:*:*:test`（第 110-118 行那套"宽 exclude + 窄 include"）——
★ 这是 **R1 实测踩过的坑**，不是理论。

- [ ] **Step 3: 起一个实例判活**（用 PID 收工，**不要 `pkill -f`**；curl 一律 `--noproxy '*'`）

```bash
./mvnw -DskipTests package -pl simos-app -am
tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store ~/.claude/jobs/s1a-stage2/store --gui-port 5877 --mcp-port 5775 --approval-port 5773
```

判活：`/`、`/index.html`、`/styles.css` 全 200 + `/api/actor/hex?q=0&r=0` 200 + 启动日志含 `外发工具 N 个`。

- [ ] **Step 4: ★ 回填 ban 列表**（`AGENT.md:55-58` 点名批评的欠账）

`simos-util` / `simos-map` / `simos-social` / `simos-unit` 的 pom 各加一条
`<exclude>io.mosire:simos-actor</exclude>`（照 `simos-social/pom.xml:74` 的先例）。

- [ ] **Step 5: 收尾门禁**

Run: `./mvnw clean verify`
Expected: **BUILD SUCCESS**（11 → **13** 个模块）；SpotBugs `BugInstance size is 0`；
**全部 surefire 报告 mtime 落在本轮**（fail-closed 口径）。

- [ ] **Step 6: 验收读数**（写进 `.superpowers/sdd/2026-09-26-s1-stage2-actor-slice/readings.md`）

| 判据 | 怎么读 |
|---|---|
| **I2.1** 既有测试全绿 | `clean verify` 的 reactor 逐模块结果 + 测试总数（对照阶段 1 的 2306） |
| **I2.2** 聚合键 `(owner, hex, assetClass)` | Task 5 的逐值用例 + 佃制两正交事实的形状断言 |
| **I2.3** `economy-api` 不再拥有 `ActorRef` | `grep -rn "class ActorRef\|enum ActorKind" simos-economy-api/src/main` 为空 |
| **★ 增量性** | `git diff f971b66..HEAD --stat -- simos-economy/src/main` 只应出现 **import 行** |

- [ ] **Step 7: 提交**

---

## Self-Review

**Spec coverage**（对照 spec §2.3 / §三）：

| Spec 要求 | 落点 |
|---|---|
| `ActorRef`/`ActorKind` 上移到 `actor-api` | Task 2 |
| `AssetClassKey` 编码同质性条件 | Task 3（R1 给它接上封闭词表） |
| `AssetHolding { id, owner, location, assetKey, quantity }` | Task 5 —— ★ **`id` 与 `quantity` 的类型 spec 未给**，本计划定为：`id` **不要**（用聚合键代替）、`quantity: long` |
| 资产不在 Actor 本体里 | Task 4（结构断言）+ Task 5 |
| `GoodsAccount` 独立、不塞 Actor 本体 | Task 4（结构断言）+ Task 6 |
| 禁令 `ActorRow { Money; List<Debt> }` | Task 4 的类注 + 结构断言 |
| 不碰 money/debts | 全程无 `Money`/`Debt` 类型（它们住 `simos-ledger`，本阶段零引用） |
| **R2**：只允许类型依赖，不许运行时控制流反向流入 | Task 4 的 `pom.xml` enforcer + **变异自证** |
| **R6**：`GoodsAccount` 是唯一真源；`ledger.Account` 不读不写不同步 | Task 6 的类注 + R2 的 enforcer（引用不到 `simos-ledger`） |
| **R7**：三段硬隔离 | Task 1-2（A）/ Task 3（B）/ Task 4-9（C）各自的关账点 |

**Placeholder scan**：无 TBD/TODO。★ Task 2 Step 7 与 Task 3 Step 5 的变异体给了"若这一变异不红就换一个"
的处理 —— 那不是 placeholder，而是**判别力可能不在预期位置**时的诚实处理（并要求如实记录实际用了哪个）。

**Type consistency**：`AssetHoldingKey` / `GoodsAccountKey` 在 Task 5/6 定义、Task 7 的 codec 消费；
`ActorSnapshot.namespace()` 与 `ActorCodec.namespace()` 与 `ToolSupport` 三处**必须同字面** `"actor"`。

**Review Focus coverage**：① → Task 4 Step 5；② ③ → Task 5 Step 1；④ → Task 3 Step 1；
⑤ → Task 7 Step 3。

## 本计划的验证边界

- **未评估**：`SocialClassId` 是否也该在阶段 2 上移 `actor-api`（阶段 1 计划已标"未评估"，
  现在的落点是 `economy-api`）
- **未裁**：`simos-ledger` 的去留（D1）—— `GoodsAccount` 与 `ledger.Account` 语义重叠，
  但一个在 `simos-actor`、一个在 `simos-ledger`，**本阶段建前者不需要先裁后者**
- **未做**：`ProductionRelation` / `ConsumptionReceipt`（阶段 5/6）
- **未清点**：`ClassKey → CohortKey` 的波及面（spec §十一 L414 自认未清点，属阶段 4）
