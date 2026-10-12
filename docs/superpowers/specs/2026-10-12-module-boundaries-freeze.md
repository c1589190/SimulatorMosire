# 模块语义冻结（2026-10-12）：权威边界表 + enforcer 闭合

> **性质**：约束设计书（供派单实现，AGENTS §一.8 / §一.8.1）。**目标 = 让下次改不越界**：
> 把"哪个模块是什么、只许依赖谁"从**分散在 AGENTS 表与各 pom 注释里的描述**，收敛成**一处权威表 + 构建期强制**。
> **不含**任何功能改动；本批只做**边界**（enforcer 禁列闭合 + 文档回代码重写）。

---

## 1. 用户原话附录（逐字照录，不改写、不缩写、不意译）

**2026-10-12**：

> 「**甲，先把手头上的工作做完，然后冻结各个模块的语义，确保下次改不越界**」

**同族既有原话（本节的依据，逐字）**：

> 「**那是隔壁项目，编译门禁只是用于同一个项目文件！**」（2026-10-23；mvn-lock 作用域 = 本仓）
>
> 「**旧设计和数据类型直接重建不用留，删就删了，谁跟你说不能删的？**」（2026-10-10；§一.11）
>
> 「**存档废掉就废掉了！！！！没人让你存老档！写死在AGENTS.md里，Alpha阶段没做完考虑啥兼容性！**」（2026-10-11）

---

## 2. 现状（2026-10-12 回代码核实，HEAD=`a5d79afb` + 本批工作树改动）

**核实方法（控制方亲跑）**：
1. 逐模块解析 `pom.xml` 的 `<dependency>`（区分 scope）与 enforcer `<exclude>`；
2. 逐模块扫 `src/main/**/*.java` 的 `import io.mosire.simos.*`，**按包精确归属到模块**
   （`<mod>.api.*` ⇒ `<mod>-api`；`economy.api.*` ⇒ `economy-api`；此归属规则是本表可信度的前提）；
3. `git status --porcelain` 确认工作树改动范围。

**结论（三条）**：

| # | 结论 | 证据 |
|---|---|---|
| **B-1** | **零越界**：15 个模块的 `src/main` **没有任何一处 import 违反自己 pom 的 ban 列** | 逐模块 `imports ∩ bans = ∅` |
| **B-2** | **零"传递依赖冒充契约"**：没有任何模块 import 了**自己没声明**的模块 | `imports − declared = ∅`（app 的 `app.*` 自引用除外） |
| **B-3** | **依赖图是无环分层 DAG**；`core` 的 main 只 import `util`；领域模块 `*Tool.java` = 0 | 拓扑排序无环；`simos-core` main imports = {util} |

**但强制面是半开的**（本批要修的就是这个）：

| 缺口 | 现状 |
|---|---|
| **四个模块完全没有 enforcer** | `simos-app`（文档明文"不设"）、**`simos-economy-api` / `simos-economy` / `simos-sd` / `simos-unit` 的 `<exclude>` 列表为空**（有插件块但没禁列，或整块缺失） |
| **禁列只禁"文档点过名的"** | 例：`simos-map` 禁了 `social/unit/actor/calendar/core`，**没禁 `economy*`/`gov`/`army`/`sd`/`app`** |
| **pom 注释与禁列不一致** | `simos-util` 的 `<message>` 写"不得依赖…**任何 simos 模块**"，而禁列只有 6 个 |

---

## 3. 权威边界表（**冻结**；"允许面"= 现有声明 ∧ 现有使用，两者都核过）

> 读法：**允许面**列出的模块可依赖；**禁列**是补进 enforcer 的（= 全模块 − 自己 − 允许面）。
> 本表是**唯一权威**；AGENTS.md 的模块表与各 pom 注释**以本表为准**（AGENTS.md 同步回填）。

| 模块 | 语义（一句话） | 允许面（依赖） | 本批补进的禁列 |
|---|---|---|---|
| `simos-util` | 零依赖地基：地址/身份/时间/快照/变更集协议/SPI（不碰文件系统） | （无 simos 依赖） | `actor-api` `app` `army` `economy` `economy-api` `gov` `ledger` `sd` `social-api` |
| `simos-map` | 地图与区域；**永不知道** social/unit/actor 存在 | `util` | `actor-api` `app` `army` `economy` `economy-api` `gov` `ledger` `sd` `social-api` |
| `simos-calendar` | 纯计算历法（儒略历 + 节气 + 分带季节），无状态无存储 | `util` `map` | `ledger` |
| `simos-social-api` | 人员/家户的**低层身份契约** | `map` | `ledger` |
| `simos-social` | 人口的**唯一权威**（生死/劳动/需求/家户工单） | `util` `map` `calendar` `social-api` `economy-api` | `ledger` |
| `simos-unit` | 编制树与单位（**不依赖社会实现**） | `util` `map` `social-api` `social` | `actor-api` `app` `army` `economy` `economy-api` `gov` `ledger` `sd` |
| `simos-actor-api` | actor 切片最底层契约（`ActorRef`/`ActorKind`/`AssetKind`），主依赖为零 | （无） | `ledger` `util` |
| `simos-actor` | 产权与商品库存（键到格） | `actor-api` `util` `map` `economy-api` `social-api` | `ledger` |
| `simos-economy-api` | 经济切片共用契约（稳定 ID / 商品 / 市场 / 货币 / 关系 / 转移形状） | `actor-api` `util` `map` `social-api` | `army` `calendar` `economy` `gov` `ledger` |
| `simos-economy` | 聚合式经济切片（产业/市场/债务/货币/政府政策），**机制与规则的归属地** | `actor-api` `util` `economy-api` `map` `social-api` `social` | `army` `calendar` `gov` `ledger` |
| `simos-gov` | 政府：行政编制、国库预算、区划服务、**外交**（Nation/关系/事件） | `util` `map` `unit` `social` `economy-api` | `actor-api` `army` `economy` `ledger` |
| `simos-army` | 交战记录与军方编排；**军队身份 + 战斗（含阶段）**（★ 2026-10-12 用户裁定：「战斗模块，包括阶段之类的，归Army模块管，原则和GOV差不多」） | `util` `map` `unit` `social` **`gov`**（★ 2026-10-12 用户裁定「**Army 很显然可以依赖 GOV**」；批次 0b 落地） | `actor-api` `economy` `economy-api` `ledger` `social-api`（★ 本列原为示意、与 pom 不完全一致 —— **唯一权威是 `simos-army/pom.xml` 的禁列**） |
| `simos-sd` | ~~**推演框架层**：决策人（dm）与推演人（gm）的总实现 —— 决策流程、权限范围、工具协议与两个工具面~~ ★★ **已被取代（2026-10-12 用户裁定：「sd整个弃用！」）⇒ 本行作废，模块整体退役** | ~~`util` `map` `social` `unit`~~ | —— |
| `simos-core` | 组合与调度（**编译期看不见任何领域类型**；铁律 4 的结构化） | `util`（+ agentlib/jackson/sqlite）；领域模块**仅 test scope** | `actor-api` `army` `calendar` `gov` `ledger` |
| `simos-app` | **组合根**：装配、GUI、MCP 口、工具面、权限范围函数（**不设 enforcer**，文档明文） | 全部 | （不设；由"领域模块 0 工具类"等专门判据守） |

★ **`simos-sd` 那一行是 2026-10-12 的语义扩张**：用户裁定「改造 sd 模块，扩大其语义为整个决策人-推演人（dm-gm）的总实现，
和其他功能模块实现相关工具的接口」。**本批只冻结语义表述**；实现迁移见后续批（`docs/superpowers/specs/` 另立）。
★★ **2026-10-12 追加更正（同日晚，用户第五轮裁定）**：该行**已被取代** —— 用户裁定「**sd整个弃用！**」，
且更早的「ga 应当维护标准的一般决策人」（`NationId` 归 **gov**、`sd:*` 地址族「**直接扔**」）。
⇒ 上面那条语义扩张**不再作为 sd 的现状描述**；sd 的去向 = 决策族 7 组件 → `ga`、外交 3 组件 → `gov`、
战斗 4 组件 → `army`、模块整体退役。规划见 `docs/superpowers/plans/2026-10-12-full-dispatch-plan.md` §4 批次 3。
★ 另注（同批核实）：`freeze.md` 原文所指的"推演框架层"其实**早在 B2a/B3 就已不成立** —— 决策人运行流与两个工具面
已搬进 `simos-ga`（`AGENTS.md` ga 行）。本行是**双重过期**，一并更正。

★ **外交归 gov**（用户 2026-10-12：「外交是 gov 的事」）：本表已把 `Nation`/`DiplomaticRelation`/`DiplomaticEvent`
写进 `simos-gov` 的语义；**类型迁移是后续批**。
★★ **2026-10-12 追加更正（该前置实测为假）**：原文写"前置：`simos-map` 现用了 `Nation`，需先解该引用" ——
**实测不成立**：`simos-map` 对 `io.mosire.simos.sd` 的 import = **0**；`map`/`gov` 里出现的 `Nation` 全是
`{@link}` javadoc 或工具参数文案（`MapToolPerspective:20`、`GovToolPerspective:61`），**无类型引用**。
⇒ 真正的迁移前置是：**`NationId` 落点**（用户已裁：**GOV**）+ `gov`/`sd` 互禁 + **`ga` 不持归属**（用户第五轮裁"归属归模块"）。

---

## 4. 不变量（本批必须守住）

| # | 不变量 |
|---|---|
| I-F1 | **不破坏既有构建**：补进的禁列**一个都不许**落在任何模块的"允许面"或真实传递依赖上；`clean verify` 必须仍全绿 |
| I-F2 | **不加 `includes` 白名单**（`bannedDependencies` 只加 `exclude`）—— `includes` 会变成"只许这些"的强约束，与既有"允许面 = 声明 ∪ 使用"口径不符 |
| I-F3 | **不改任何模块的 `<dependency>` 声明**（本批只动 enforcer）；声明层的收口（如 `unit→social` 死声明）另开裁定 |
| I-F4 | **禁列以"对称闭合"为口径**：每模块禁掉"自己 + 允许面之外"的**全部** simos 模块（含当前还不存在的 `ledger`，historical） |
| I-F5 | 文档层：`AGENTS.md` 模块表**回代码重写**，补 `social-api`/`gov`/`army` 三行，修 `unit`/`sd`/`actor`/`economy` 四处过期表述 |

---

## 5. 验收判据

1. `tools/mvn-lock.sh clean verify` 全绿（含 enforcer 在 `validate` 阶段对 15 个模块各跑一次）；
2. **负向验证（本批核心）**：临时在**任选一个**模块里加一条被禁依赖（例如在 `simos-map/pom.xml` 加 `simos-economy`），
   ⇒ `validate` 阶段**当场红**，且报错文案指向该模块；还原后比 md5（证明字节回到原样）；
3. `simos-app` 仍**无** enforcer（文档明文，不许顺手加）；
4. 本表每一行的"允许面"逐条与 pom 声明一致（机器比对，不靠肉眼）。

---

## 6. 文件所有权

- **允许写**：14 个 `simos-*/pom.xml` 的 enforcer `<exclude>` 块（**只加 exclude**）、`docs/**`、
  `AGENTS.md` 的模块表那一段。
- **禁止碰**：任何 `<dependency>` 声明、任何 `src/**` 代码、`simos-app/pom.xml` 的 enforcer（保持不设）、
  设计书与台账的历史行（§五.4 不篡改留痕）。

---

## 7. 未核项（如实）

1. 本表基于 `import` 静态扫描 + pom 解析，**未跑 Maven**（enforcer 实跑留给实现批的 `verify`）。
2. "允许面"取"声明 ∧ 使用"的**并集语义**：若某模块**声明了但零 import**（今天有 3 处：`army→social`、
   `unit→social 实现`、`economy-api→util`），本表按"声明即允许"处理（不因此判越界），**收口另裁**。
3. 未核 `<scope>test</scope>` 的传递链在补禁列后是否被逼出例外（实现批的 `verify` 会给答案）。
