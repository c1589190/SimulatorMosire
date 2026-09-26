### Task 3: `ProductionSettlement` —— 结算**计算**（纯函数 + 公式表）

**Files:** Create `simos-economy/src/main/.../time/ProductionSettlement.java` + `ProductionSettlementTest.java`

**Interfaces:**
- Produces:
```java
public final class ProductionSettlement {
  /** 一条产权条目：+ 收 / − 付（毫单位）。账户 = (actor, location)。 */
  public record ActorEntry(ActorRef actor, HexCoord location, CommodityId commodity, long delta) {}
  /** 一次关账的结算结果：产权条目（**按落账次序**）+ cohort 入账 + 待 S2 的货币规则（I5.3）。 */
  public record Outcome(
      List<ActorEntry> actorEntries,
      Map<CohortKey, Map<CommodityId, Long>> cohortIntake,
      List<CompensationRule> deferredMoney) {}
  /** 结算的事实输入：**全部来自本周期**（付款上限见 R6 ⇒ 不需要任何历史余额）。 */
  public record Facts(
      HexCoord location,
      Map<CommodityId, Long> gross,
      Map<CommodityId, Long> net,
      Map<CommodityId, Long> inputs,
      Map<CohortKey, Long> laborOfCohort,
      Map<ActorRef, Long> assetOfActor) {}
  public static Outcome settle(ProductionRelation relation, Facts facts) { ... }
}
```
- [ ] **Step 1: 写失败的测试**（★ 期望值全部**手算**、算式写进注释；★ 夹具用**非派生值**）
  - **I5.2**：同一份产出，`OUTPUT_SHARE × GROSS_OUTPUT 30%` 与 `OUTPUT_SHARE × NET_AFTER_INPUTS 30%` 的**实得数不同** —— 恰好差 `30% × 损耗`（夹具取 `gross=100_000`、`loss=3_000` ⇒ 两数分别 `30_000` 与 `29_100`）。
  - **I5.3**：`FIXED_MONEY_RENT` ⇒ `actorEntries` 与 `cohortIntake` **都空**，且 `deferredMoney` **含它**（并断言消息口径"待 S2"由读口/用例读出）。
  - **I5.4**：两条规则**只交换 `priority` 值**（数据改动、无代码分支）⇒ 结果不同（`OPERATOR_SURPLUS` 规则排在前/后，实得数不同）。
  - **R6**：规则要得比产出多 ⇒ 实付 = 产出（**不抛、不造账**），且 `Σ付款 ≤ 净产`。
  - **R7**：`cohortIntake` 的键是 `CohortKey`（不是行键）；`laborOfCohort` 里没有的 cohort ⇒ 该条归零、不产生条目。
- [ ] **Step 2: 跑红（当场捕获）**
- [ ] **Step 3: 写实现**（公式表 = 判据，逐条兑现）：

| 规则 × basis | 数量（毫单位，向下取整） |
|---|---|
| `SELF_RETENTION` | **0**（不动；余额归 `residualOwner`） |
| `OUTPUT_SHARE` × `GROSS_OUTPUT` | `gross_j × rate ÷ 1000` |
| `OUTPUT_SHARE` × `NET_AFTER_INPUTS` | `net_j × rate ÷ 1000` |
| `OUTPUT_SHARE` × `OPERATOR_SURPLUS` | `(net_j − 已付_j) × rate ÷ 1000`（**已付按 priority 序累计** ⇒ 次序是数据） |
| `OUTPUT_SHARE` × `LABOR_AMOUNT` | `net_j × rate ÷ 1000 × 本受方劳动 ÷ Σ劳动`（Σ 取 `laborOfCohort` 全体） |
| `OUTPUT_SHARE` × `ASSET_QUANTITY` | `net_j × rate ÷ 1000 × 本受方资产量 ÷ Σ资产量` |
| `FIXED_IN_KIND_PER_LABOR`（basis `LABOR_AMOUNT`） | `⌊本受方劳动 ÷ 1000⌋ × fixedAmount` |
| `FIXED_IN_KIND_RENT`（basis `FIXED_AMOUNT`） | `fixedAmount`（每周期一笔） |
| `FIXED_MONEY_*` | **不产生条目**，进 `deferredMoney`（I5.3） |

  - ★ 次序：**按 `priority` 升序、同值按规则在 `rules` 里的序**（保序、可复现）；
  - ★ **付款上限**（R6）：每条实付 = `min(应付, 本周期该商品剩余可用)`，可用 = 自己收到的产出（`+net`）− 已付；
  - ★ cohort 受方与 actor 受方**共用同一套数量公式**，差别只在**落到哪里**（cohort 进 `cohortIntake`、actor 进 `actorEntries`）；
  - ★ **货币规则不许**进 `actorEntries`（I5.3 的判别力就在这一条）。
- [ ] **Step 4: 跑绿** ⇒ `ProductionSettlementTest` PASS
- [ ] **Step 5: 变异自证（三条，逐条当场捕获 RED）**：① 把 `priority` 排序换成"表序"且夹具的两条规则**表序 ≠ priority 序** ⇒ I5.4 红；② `OPERATOR_SURPLUS` 的"已付"改成 0 ⇒ 该用例红；③ 货币规则照常产生条目 ⇒ I5.3 红（★ **若第 ③ 条不红，说明断言只看"返回空"而没看"确实没条目"——改断言，别改实现**）
- [ ] **Step 6: 提交**；★ **关账点 A**：`./mvnw clean verify` 全绿（T1–T3 行为未变）

---

