# 2026-10-23 weave（家庭纺织）不作为市场主体 —— 约束设计书（裁定 B）

> 派单前置约束设计书（AGENTS §一.8）。**只冻结必须冻结的**：契约、不变量、验收判据、文件所有权；
> 实现细节（改哪几行、注释怎么写）由实现 Agent 决定并写实现架构账本。

## 1. 需求来源（用户原话，原样照录）

- 用户 2026-10-23 对"家庭纺织集体 unit 解析不到单一家户"的修法选择：
  > 「**B**，生产方式就hr生产方式，所有就是所有，不要搞混」
- 上一轮 B 的定义（用户原话）：
  > 「**明确 weave 不是市场主体，产出/库存直接落成员家户，并且不要把它注册进市场主体表**」

**概念纪律（用户强调，不得混淆）**：
- **生产模式（production mode）** = `DefaultProductionModes` 的目录项（`handicraft_workshop`、`wage_farm`、`tenancy_*`…）；
- **产业 / 生产单位（industry / `ProductionUnitId`）** = `farm@hex` / `craft@hex` / `weave@hex` / `trade@hex`。
- 本次修的是**产业/单位是否登记为市场主体**，**不动生产模式目录**。

## 2. 现状事实（2026-10-23 回代码核 + run2 实测）

- `MarketSettlement.participantsFor`（`simos-economy/.../time/MarketSettlement.java:4551-4640`）：
  - 单一家户（`economicHouseholdOf(unit)` 非空）⇒ 挂到该家户参与者；
  - 解析不到单一家户但 `householdsOf(unit)` 非空（集体经营）⇒ 现在发
    `MARKET_SUBJECT_COLLECTIVE` **WARN** + `continue`（成员家户各自入市）；
  - 无劳动家户的合法空壳 ⇒ `MARKET_SUBJECT_EMPTY_UNIT` WARN + 跳过；
  - 有产能/资产却解析不到 ⇒ 具名抛（保留）。
- `EconomySeeder.householdWeaving`（`simos-app/.../world/EconomySeeder.java:3859-3879`）建 `weave@hex` 产业
  （regime=household、产能 `TOOL`）；`collectiveRelation`（:4302）以 `plan.operator()`（合成 `HOUSEHOLD:weave@hex`，
  P2-A 后不持账）为 payee/operator。
- run2（19 hex / 360 tick / 全 DEBUG）实测：**1425 条 `MARKET_SUBJECT_COLLECTIVE` = 75 市场轮 × 19 个
  `unit-weave@hex`**；`unit-craft@hex`/`unit-farm@hex` 走单一家户解析、零警告。
- 2026-10-09 只读调查已把该行为记为「**不完全符合**（集体 unit 没有被显式挂到可参与市场的家户主体，靠 WARN 跳过）」
  （`docs/superpowers/reports/2026-10-09-cross-module-household-conformance.md`）。
- 测试侧：无测试断言 `MARKET_SUBJECT_COLLECTIVE`（本轮已核）；`weave` 只在 `EconomyTestWorld` 等夹具里被引用。

## 3. 目标行为（B）

1. **集体经营产业（weave）不登记为市场主体**：`participantsFor` 在集体分支**不再发 WARN**，直接跳过（保持成员家户各自入市）。
2. **可观测性**：保留一条 **TRACE**（默认关）`MARKET_SUBJECT_COLLECTIVE_SKIPPED`，`reason=by-design-not-a-market-subject`，
   便于审计；**不得**用 WARN/INFO。
3. **生产/结算/身份一律不动**：weave 仍是产业（`WEAVE`/`weave@hex`）、仍由 `collectiveRelation` 结算、
  产出/库存仍落成员家户；合成 `HOUSEHOLD:weave@hex` operator 继续作为结算 payee（清理它是另一个责任区，不在本批）。
4. `MARKET_SUBJECT_EMPTY_UNIT`（合法空壳）与"有产能却解析不到 ⇒ 具名抛"两条**保持不动**。

## 4. 不变量（不可破坏）

- 无状态组件 / Codec / ChangeSet / 载荷 / 命令 / API 变更；无 `ProductionUnitId`、mode id、产业定义变更；
- **市场参与者列表与改动前逐值一致**（该 unit 原本就是 `continue` 跳过，只去掉日志）；
- 不得改 `DefaultProductionModes`、`ActorKind` 词表、`EconomySeeder` 的播种行为（只允许改注释）；
- 不得为消警告而把 weave 改成单一家户或改 `collectiveRelation` 语义（那是另一条路线，已弃）。

## 5. 验收判据与负向用例

- 编译：`tools/mvn-lock.sh -q spotless:apply` + `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile` rc=0。
- **负向（主判据）**：同一 tick0 世界重跑 ≥30 tick，`grep -c 'MARKET_SUBJECT_COLLECTIVE'` **= 0**；
  `MARKET_SUBJECT_EMPTY_UNIT` 仍能出现（用构造/既有探针证明它没被一起删掉）。
- **不变性（逐值）**：与 run2 同时段（day1-30）的 `MARKET` / `DAY_END`（fills / creditFills / unfilled / unmet / transfers）
  **逐值一致**（证明只是日志变化，不是行为变化）。
- 允许 TRACE 断言（默认关时零输出）。
- 交账：改动文件 + 编译 rc + **实现架构账本** `.superpowers/sdd/2026-10-23-weave-not-market-subject/impl-ledger.md`
  + 未验证项；账本记录关键判断与偏离。

## 6. 文件所有权

- ✅ 允许改：`simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java`（集体分支与相关注释）；
  `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java`（**仅注释**，说明 weave 非市场主体）。
- ✅ 允许建：`.superpowers/sdd/2026-10-23-weave-not-market-subject/impl-ledger.md`。
- ❌ 禁止：其他任何文件（含 `src/test/**`、`docs/**`、`AGENTS.md`、`pom.xml`、`log4j2.xml`、`DefaultProductionModes`）。

## 7. 后续（不在本责任区）

- 合成 `HOUSEHOLD:weave@hex` operator 的清理/迁移（涉及结算 payee，另开）；
- `ESTATE`/`WORKSHOP` 词表收尾（P2-A 已裁定退役，残余引用清理另开）；
- 债务累积口径（用户已给方向：初始粮与生产效率对半砍）——另开一个责任区。
