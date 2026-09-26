# SDD 台账 —— 经济循环实现（H0–H5 + 关账）

Plan: `docs/superpowers/plans/2026-09-27-economic-cycle-implementation-plan.md`
Goal: `goal-19ff41be-ca9f-40d8-be89-8b9149ea69bf`（用户 2026-09-27：「一口气跑完」）

## 执行方式（用户裁定，优先于任何 Skill）

- ★ **开发期不写新测试、不做变异自证**（`AGENT.md` §三.0）。唯一硬底线 = **编译必须绿**。
- ★ 既有测试仍在跑；既有用例变红 ⇒ 按新口径**重算期望值**，**不许删或放宽断言**（照 S1 先例）。
- ★ **一次只能跑一个 Maven** ⇒ 所有 Maven 经 `tools/mvn-lock.sh`（本轮新建，已自证互斥）。
- ★ 一个文件一个 owner；派单写明文件清单。

## 控制方裁定（执行前已定，见 plan §三）

K1 家户账 = actor 持久真源 + economy 会话工作副本（不进 `EconomyData`）
K2 行键 = `CohortKey`（`(格, 居住类型, 阶层)`）= 家户键；`ClassKey` 删除
K3 产能从行搬到 `Industry`；`ClassRow.meansOfProduction` 删除（代价：真档数值会变）
K4 转移原语只认 `ActorRef`（住 `economy-api`，已依赖 `actor-api`，无循环）
K5 货币与市场**同批**落地
K6 ★ 用户裁定：作坊投入改 `{FIBER, TOOL}`、`IRON` **留作留位但必须有读者**（`allCommodityIds()` + 读口）
K7 既有用例变红优先判"夹具/口径错"，重算不放宽
K8 台账纠错：`ClassKey` 实测 **298 处**（main 174 / test 124），旧台账的 259 把 `AssetClassKey` 算进去了

## 进度

### H0 身份与观测收口

- [x] **契约（控制方亲做）**：`ResidenceKind` 新建 · `CohortKey` 加居住维（三参 + 三段规范串）·
      `PopulationLots` 的前缀改为转发到 `ResidenceKind.lotPrefix()`（**消除第二处拼写点**）
      —— 编译实测：`-pl simos-economy-api,simos-social -am` **exit 0**
- [ ] H0.1/H0.3/H0.4 `simos-economy` 主源（**Agent B 在跑**）
- [ ] H0.1/H0.7 `simos-app` 播种器与读口（**Agent C 在跑**）
- [ ] H0.5 `AssetHolding`/`AssetClassKey`/`ASSET_QUANTITY` 退役（**待 B 落地后再动**：
      `Basis` 与 `ProductionSettlement` 在 B 的文件里，避免互相覆盖）
- [ ] H0.6 产权读口（GUI + MCP）+ 600 天聚合脚本纳入 actor 账
- [ ] H0 批次编译（`-DskipTests package`，**含测试编译**）⇒ 提交

### H0 的判据（阶段边界由控制方跑）

- I1.1：迁移前后逐格人口/劳动/需求**逐值相同**（H0 不应改变经济行为）★ H0.4 例外（K3 已认代价）
- 结构：`ClassKey` 零残留（`grep -v AssetClassKey` = 0）；行键全部 `(格,居住,阶层)` 形状
- 全部 `*Test` 编译通过（不许删文件来"通过"）

---

## H1 执行设计（控制方先钉死，H0 落地后立即派单）

> 目的：H1 是**唯一改变经济行为**的批，也是最容易失控的一批。先把形状钉死，再派活。

### H1.0 身份映射（**已完成**）

`economy-api/cohort/HouseholdActors`：家户 actor = `ActorRef(HOUSEHOLD, "<hex>:<residence>:<stratum>")`，
`of(CohortKey)` / `cohortOf(ActorRef)` **互逆 + fail-closed**。★ 裁定 K9（不能用 `CohortKey.toString()`，含 `|` 会撞 `GoodsAccountKey` 的接缝）。

### H1.1 家户账 = actor 切片的 `GoodsAccount`（唯一持久真源）

键 `(actor, hex)`；家户的 hex = `CohortKey.hex()`。**不新增账户类型**（复用 S1 已建的 `GoodsAccount`）。

### H1.2 会话工作副本（裁定 K1）

- `EconomyDayStepper` 新增会话态：`LinkedHashMap<CohortKey, Map<CommodityId, Long>> householdGoods`
  —— **与它已经持有的 `flows` 累加器完全同形**（先例，不是新形态）。
- `EconomySettlement.settleOneDay(...)` 新增该参数（**就地更新**），与 `flows` 同一待遇。
- 协调器（`PopulationEconomyTimeParticipant`）：推进前从 actor 侧**载入** → 逐日 step → `finish()` 交出 → **逐日落回 actor**。
- ★ **它不进 `EconomyData`、不进变更集、不跨 revision 存活** ⇒ 不是第二本账（判据：`ClassRow` 无 `goods` 且守恒式无 `ΔΣRowGoods`）。

### H1.3 结算改道（★ 一处净简化）

- `ProductionSettlement.Outcome.cohortIntake` **删除**：受方就是**唯一那个家户** ⇒ 直接产出
  `ActorEntry(HouseholdActors.of(cohort), hex, commodity, +paid)`（与 actor 受方同一支）。
- `EconomySettlement.deliverCohortIntake` **删除**（E17 的 `+unresolved` 兜底随之不再需要：
  受方恒存在，除非该 cohort 没有 actor —— 那种情况要 **fail-closed 抛**，不许静默留账）。
- `ProductionLedger.cohortIntake` 字段**删除**（账里只剩 毛产 / 损耗 / 投入 / 产权条目 / 货币待办）。

### H1.4 消费与投入改读家户账

- `settleHexes`：`grainOf(row)` → 家户账余额；同格借粮的"可贷余粮"`lendableOf` 也读家户账。
- `drawCycleInputs` / `transferIntraHexInputs`：从**供给该产业的 cohort 的家户账**扣料
  （供方集合 = H0.3 已建的"由配额表推 cohort"那条推导）。
- `ClassRow.goods` **删除**（I6.1 与 I7.1 一次达成）。

### H1.5 播种

新增 `app/world/HouseholdSeeder`：对每个 `population > 0` 的行建一个家户 actor，
并把该行的创世库存搬进它的 `GoodsAccount`；`ClassRow` 只留人口 / 劳动 / 参与率 / 需求 / 压力。

### H1.6 守恒式去掉过渡项

`ΔΣRowGoods` 那一项**从式子里删掉**（它是"两套账并存"的产物）。★ **它还在 ⇒ 还有一本账没搬完**。

### H1.7 判据

- 结构：`ClassRow` 无 `goods`/`money`/`debts`/`meansOfProduction`；`ProductionLedger` 无 `cohortIntake`
- 逐值：I4.1（逐 actor 守恒）与 I4.2（全系统守恒，**无过渡项**）成立
- 真档：**改前有饭吃 ⇒ 改后仍有饭吃**（人口不因改造而崩）

### ★ 偏离（如实记）：H1.2 的"货币余额"推迟到 H4

今天**没有任何货币**（`MoneyAuthority` 无实现者、`FIXED_MONEY_*` 只定义不结算），
而 `ClassRow.money` 在 H1 里被删除 ⇒ **全仓没有任何"钱"字段**，
"别用裸 `long`"这条纪律**空转成立**。等 H4 真要有钱时**一次定型**，
比现在造一个恒空的钱包更省，也不会留下"看起来在记、其实没人读"的死字段（本仓明文反对）。
