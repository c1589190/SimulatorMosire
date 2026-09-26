### Task 1: 契约（`CohortKey` + `relation` 包）

**Files:** Create 上面 R2 表里的 6 个文件 + 同包测试 `CohortKeyTest`（`simos-economy-api` 是**契约层**：测试只许用 `java.*`/junit/assertj + 本模块类型）

**Interfaces:**
- Produces: `CohortKey(HexCoord residence, SocialClassId stratum)`（`toString()` = `"<q>_<r>|<stratum>"`，`parse` 是它的逆）；
  `RuleType{SELF_RETENTION, OUTPUT_SHARE, FIXED_IN_KIND_PER_LABOR, FIXED_IN_KIND_RENT, FIXED_MONEY_WAGE, FIXED_MONEY_RENT}`；
  `Basis{GROSS_OUTPUT, NET_AFTER_INPUTS, OPERATOR_SURPLUS, LABOR_AMOUNT, ASSET_QUANTITY, FIXED_AMOUNT}`（第 6 档是本计划补的，见 Step 1）；
  `Recipient`（sealed：`ToActor(ActorRef)` / `ToCohort(CohortKey)`）；
  `CompensationRule(RuleType type, Recipient recipient, Basis basis, int ratePerMille, long fixedAmount, Optional<CommodityId> commodity, int priority)`；
  `ProductionRelation(IndustryId activity, ActorRef operator, List<CompensationRule> rules, ActorRef residualOwner)`

- [ ] **Step 1: 写失败的测试**（只写 4 条，全部是**构造期守卫**，因为本任务零公式）

```java
  /** ★★ R9：新键类型必须自带裸 toString() + 单参 parse（阶段 6 的 receipt 表会用它作键）。 */
  @Test
  void cohortKeyRoundTripsThroughItsCanonicalText() {
    CohortKey key = new CohortKey(new HexCoord(3, -2), new SocialClassId("poor_peasant"));
    assertThat(key.toString()).isEqualTo("3_-2|poor_peasant");
    assertThat(CohortKey.parse(key.toString())).isEqualTo(key);
  }

  /** ★★ 货币档只定义字段、不结算（I5.3）：commodity 空 = 货币规则；实物规则必须带 commodity。 */
  @Test
  void moneyRulesCarryNoCommodityAndInKindRulesRequireOne() {
    Recipient rec = new Recipient.ToCohort(new CohortKey(new HexCoord(0, 0), new SocialClassId("poor_peasant")));
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_MONEY_WAGE, rec, Basis.FIXED_AMOUNT,
                    0, 5_000L, Optional.of(new CommodityId("grain")), 10))
        .as("★ 货币规则带了商品 ⇒ 抛（二选一是类型事实，不许两处都能填）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT, rec, Basis.FIXED_AMOUNT,
                    0, 5_000L, Optional.empty(), 10))
        .as("★ 实物规则没有商品 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
  }
```
★ **契约里必须有的第 6 档 `Basis.FIXED_AMOUNT`**（固定额规则的 basis）：spec §2.4 只列了五个 `basis`，
但 `FIXED_IN_KIND_RENT`/`FIXED_MONEY_*` 的"数量从哪来"在那五档里**没有落点**（它们说的是"每单位什么"，而固定额没有单位）
⇒ 与修正 ③ 同款：**spec 的枚举不全，按 R5 的公式表补一档**（这是本任务第一件要定的事，别绕开）。

- [ ] **Step 2: 跑红（当场捕获）**：`./mvnw -pl simos-economy-api -am test -Dtest=CohortKeyTest -Dsurefire.failIfNoSpecifiedTests=false` ⇒ 编译错（类型不存在）⇒ 日志落台账
- [ ] **Step 3: 写实现**（判据：① 五条守卫逐条；② `Recipient` sealed ⇒ "actor 与 cohort 恰其一"是**类型事实**不是运行时检查；③ `rules` 保序不可变 + 逐项非空 + **`priority` 允许重复但必须 ≥ 0**；④ `ratePerMille ∈ [0, 1000]`、`fixedAmount ≥ 0`；⑤ `Optional` 只用于 `commodity`）
- [ ] **Step 4: 跑绿** ⇒ `CohortKeyTest` PASS（以 surefire 报告核对）
- [ ] **Step 5: 变异自证（两条）**：① `parse` 按**最后**一个 `|` 切 ⇒ 往返红；② 去掉"实物规则必须有 commodity"那条守卫 ⇒ 第二条红
- [ ] **Step 6: 提交**（`feat(economy-api): CohortKey 与关系契约（S1 阶段 4+5）`）

---

