package io.mosire.simos.economy.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.map.hex.HexCoord;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ **制度 → 默认生产关系**的推导表（S1 阶段 4+5 Task 2；spec §六 + 裁定 E2/E4/E9 + 计划 R7/R8）。
 *
 * <p>★★ **判别力的载具是夹具，不是断言**（本仓阶段 3 独立实测三次）：本文件的期望值**全是字面量** （`300` / `144` / `700` / `600` / `1_000`
 * / `20_000_000` / `"grain"` / `"cloth"` / 逐条 `priority`）， **没有一处**从 {@code RegimeRelations}
 * 的常量或函数再读一遍 —— 否则"把出厂值改成别的"这种变异体会全绿。
 *
 * <p>★ **四档的差异是"可观测地不同"的**：`feudal` 5 条粮规则 / `household` 4 条布规则 / `handicraft` 4 条布规则 + 4 条货币规则 /
 * `tenant` 1 条固定粮租 ⇒ 任何"两档指向同一组规则" （V2 的反面）或"一律走第一档"的实现都会在某一条上用**不同的条数/商品/率**当场红。
 */
class RegimeRelationsTest {

  private static final IndustryId FARM = new IndustryId("farm@0_0");
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final CommodityId GRAIN = new CommodityId("grain");

  /**
   * ★★ H2 的币种位夹具：**独立字面量**（不引用 {@code RegimeRelations.DEFAULT_CURRENCY}）—— 往返夹具的纪律是
   * "期望值不许从被测物派生"，引用生产的出厂值会让"币种真的过了线格式"这条断言变成自证。
   */
  private static final CurrencyId CURRENCY = new CurrencyId("silver");

  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ 纤维（农业的**副产**）：{@code feudal} 档最后四条规则给的就是它。 */
  private static final CommodityId FIBER = new CommodityId("fiber");

  /**
   * ★★ **`feudal` 档逐值**（R8 第 1 行）：实物给养（`FIXED_IN_KIND_PER_LABOR`，按劳动量，粮） 给该格**四个阶层** cohort 各一条，+
   * 地租（`OUTPUT_SHARE × GROSS_OUTPUT` 300‰，粮）**显式**给 `(hex, landlord)` cohort（E4：不显式给，地主 cohort
   * 的粮源会凭空消失）。
   */
  @Test
  void theFeudalRegimePaysSubsistenceToEveryStratumAndRentToTheLandlord() {
    ProductionRelation relation =
        RegimeRelations.defaultRelation(
            new RegimeId("feudal"), FARM, ESTATE, java.util.Set.of(ResidenceKind.RURAL));

    assertThat(relation.activity()).isEqualTo(FARM);
    assertThat(relation.operator()).isEqualTo(ESTATE);
    assertThat(relation.residualOwner())
        .as(
            "★ E9：余额归 residualOwner —— 四档都不写 SELF_RETENTION 规则（见 theFourTablesExpressSelfRetentionAsTheResidualOwner）")
        .isEqualTo(ESTATE);
    assertThat(relation.rules())
        .as("四个阶层的给养各一条（R7/R8）+ 一条地租 + ★四个阶层的**副产纤维**各一条")
        .containsExactly(
            subsistence(SocialClassId.POOR_PEASANT),
            subsistence(SocialClassId.MIDDLE_PEASANT),
            subsistence(SocialClassId.RICH_PEASANT),
            subsistence(SocialClassId.LANDLORD),
            grainShare(
                RuleType.OUTPUT_SHARE,
                Pool.GROSS_OUTPUT,
                Weight.NONE,
                300,
                SocialClassId.LANDLORD,
                20),
            byproduct(SocialClassId.POOR_PEASANT),
            byproduct(SocialClassId.MIDDLE_PEASANT),
            byproduct(SocialClassId.RICH_PEASANT),
            byproduct(SocialClassId.LANDLORD));
    // ★★ **副产那四条为什么必须在**（实现时实测到的收口）：产出自 T4 起不再写进阶层行（R5 ②），行里的实物只能经
    //   关系规则回来。少了它，农田的纤维留在 operator 账上 ⇒ 「同格取材」（R4 的 T0：从**行**取材）无料可取 ⇒
    //   织机第 2 个周期起停工（EconomyRealScaleClothTest / PopulationR4Test / WorldgenInitializeToolTest
    // 三条端到端红）。
    assertThat(
            relation.rules().stream().filter(rule -> rule.commodity().orElseThrow().equals(FIBER)))
        .as("（上一条已逐值钉住；这里补一条**可读**的说法）副产纤维给四个阶层 cohort，各 1000‰ × 净产")
        .hasSize(4);
  }

  /**
   * {@code feudal} 的**副产纤维**：{@code OUTPUT_SHARE × NET_AFTER_INPUTS} 1000‰，给四个阶层 cohort（各自的 {@code
   * priority} 为 30）。★ 四条同率不是笔误：付款上限逐条咬合 ⇒ **Σ实付 == 净产**，按劳动量分。
   */
  private static CompensationRule byproduct(SocialClassId stratum) {
    return new CompensationRule(
        RuleType.OUTPUT_SHARE,
        cohort(stratum),
        Pool.NET_AFTER_INPUTS,
        Weight.NONE,
        1000,
        0L,
        Optional.of(FIBER),
        Optional.empty(),
        30);
  }

  /**
   * ★★ **`household` 档 = 裁定 E2 的落点**（对 spec §六「纯 `SELF_RETENTION`」的修订）： 实物分成给劳动者
   * cohort（`OUTPUT_SHARE × LABOR_AMOUNT`，**布**）+ 自留（余额归 operator）。
   *
   * <p>★★ 为什么必须是**布**：spec §七 V3 要「**织布的人拿到布**」，而织布的人 = `(hex, 阶层)` cohort （operator 是 actor）。若这里写
   * `SELF_RETENTION`，布会全留在 operator 账上 ⇒ V3/I6.3 永远开不了账。
   */
  @Test
  void theHouseholdRegimeSharesTheClothWithTheWeaversInsteadOfRetainingEverything() {
    ProductionRelation relation =
        RegimeRelations.defaultRelation(
            new RegimeId("household"),
            FARM,
            new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"),
            java.util.Set.of(ResidenceKind.RURAL));

    assertThat(relation.rules())
        .as("E2：四个阶层各一条实物分成（700‰ × LABOR_AMOUNT，给的是**布**）")
        .containsExactly(
            clothShare(SocialClassId.POOR_PEASANT, 700, 10),
            clothShare(SocialClassId.MIDDLE_PEASANT, 700, 10),
            clothShare(SocialClassId.RICH_PEASANT, 700, 10),
            clothShare(SocialClassId.LANDLORD, 700, 10));
    assertThat(relation.rules())
        .as("★ E2：一条 SELF_RETENTION 都没有 —— 那正是被打红的那条 spec 口径")
        .noneMatch(rule -> rule.type() == RuleType.SELF_RETENTION);
  }

  /** ★ `handicraft` 档（R8 第 3 行）：实物工资（布 600‰ × 劳动量）+ ★货币工资档**只定义不结算**（I5.3）。 */
  @Test
  void theHandicraftRegimePaysAWageInClothAndDefinesButDoesNotSettleAMoneyWage() {
    ProductionRelation relation =
        RegimeRelations.defaultRelation(
            new RegimeId("handicraft"),
            FARM,
            new ActorRef(ActorKind.WORKSHOP, "farm@0_0"),
            java.util.Set.of(ResidenceKind.RURAL));

    assertThat(relation.rules())
        .containsExactly(
            clothShare(SocialClassId.POOR_PEASANT, 600, 10),
            clothShare(SocialClassId.MIDDLE_PEASANT, 600, 10),
            clothShare(SocialClassId.RICH_PEASANT, 600, 10),
            clothShare(SocialClassId.LANDLORD, 600, 10),
            moneyWage(SocialClassId.POOR_PEASANT, 20),
            moneyWage(SocialClassId.MIDDLE_PEASANT, 20),
            moneyWage(SocialClassId.RICH_PEASANT, 20),
            moneyWage(SocialClassId.LANDLORD, 20));
    assertThat(relation.rules().stream().filter(rule -> rule.type().money()).toList())
        .as("★ I5.3：货币档**定义得出来**（`commodity` 为空是类型事实）")
        .hasSize(4)
        .allMatch(rule -> rule.commodity().isEmpty() && rule.fixedAmount() == 1_000L);
  }

  /** ★★ `tenant` 档（R8 第 4 行）：**固定实物租**给 `(hex, landlord)` cohort（E4）+ 佃农家户自留。 */
  @Test
  void theTenantRegimePaysAFixedRentInGrainToTheLandlordCohortOnly() {
    ProductionRelation relation =
        RegimeRelations.defaultRelation(
            new RegimeId("tenant"),
            FARM,
            new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"),
            java.util.Set.of(ResidenceKind.RURAL));

    assertThat(relation.rules())
        .containsExactly(
            new CompensationRule(
                RuleType.FIXED_IN_KIND_RENT,
                new Recipient.ToCohort(
                    new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, SocialClassId.LANDLORD)),
                Pool.FIXED_AMOUNT,
                Weight.NONE,
                0,
                20_000_000L,
                Optional.of(GRAIN),
                Optional.empty(),
                10));
    assertThat(relation.residualOwner()).as("佃农家户（operator）自留：余额归它").isEqualTo(relation.operator());
  }

  /**
   * ★★ **E4 的载体**：两档的租都**显式**写给 `ToCohort((hex, landlord))`（地主既不在劳动账里、也不是 actor）。
   *
   * <p>★ 判别力：把租的受方改成 `ToActor(operator)`（"地主就是经营者"的旧口径）⇒ 本用例红。
   */
  @Test
  void theRentIsAddressedToTheLandlordCohortExplicitlyInBothRentPayingRegimes() {
    Recipient landlord =
        new Recipient.ToCohort(
            new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, SocialClassId.LANDLORD));

    assertThat(
            RegimeRelations.defaultRelation(
                    new RegimeId("feudal"), FARM, ESTATE, java.util.Set.of(ResidenceKind.RURAL))
                .rules()
                .stream()
                // ★ 只看**粮**那一族（地租）：本条问的是"租写给谁"，副产纤维那四条不在本条的判据里。
                .filter(
                    rule ->
                        rule.type() == RuleType.OUTPUT_SHARE
                            && rule.commodity().orElseThrow().equals(GRAIN))
                .map(CompensationRule::recipient)
                .toList())
        .as("feudal 的地租：显式给 (hex, landlord) cohort")
        .containsExactly(landlord);
    assertThat(
            RegimeRelations.defaultRelation(
                    new RegimeId("feudal"), FARM, ESTATE, java.util.Set.of(ResidenceKind.RURAL))
                .rules()
                .stream()
                .filter(rule -> rule.commodity().orElseThrow().equals(FIBER))
                .map(CompensationRule::recipient)
                .toList())
        .as("★ 副产纤维那四条**不写给地主**：它们给四个阶层 cohort（劳动分成，与地租各管一种商品）")
        .containsExactly(
            new Recipient.ToCohort(
                new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, SocialClassId.POOR_PEASANT)),
            new Recipient.ToCohort(
                new CohortKey(
                    new HexCoord(0, 0), ResidenceKind.RURAL, SocialClassId.MIDDLE_PEASANT)),
            new Recipient.ToCohort(
                new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, SocialClassId.RICH_PEASANT)),
            landlord);
    assertThat(
            RegimeRelations.defaultRelation(
                    new RegimeId("tenant"),
                    FARM,
                    new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0"),
                    java.util.Set.of(ResidenceKind.RURAL))
                .rules()
                .get(0)
                .recipient())
        .as("tenant 的固定租：显式给 (hex, landlord) cohort")
        .isEqualTo(landlord);
  }

  /**
   * ★★ **R7/R8：受方是 cohort、且"劳动者" = 该格的四个阶层各一条**（人口为 0 的那些自然解析不到、留在 operator）。
   *
   * <p>★ 判别力：把受方写成行键 / 只写一条"劳动者" ⇒ 本用例红。
   */
  @Test
  void everyLaborRuleAddressesOneOfTheFourStratumCohortsAtTheIndustrysHex() {
    ProductionRelation relation =
        RegimeRelations.defaultRelation(
            new RegimeId("household"), FARM, ESTATE, java.util.Set.of(ResidenceKind.RURAL));

    assertThat(
            relation.rules().stream()
                .map(CompensationRule::recipient)
                .map(recipient -> (Recipient.ToCohort) recipient)
                .map(Recipient.ToCohort::cohort)
                .map(CohortKey::stratum)
                .toList())
        .as("四个阶层各一条（保序：贫 → 中 → 富 → 地）")
        .containsExactly(
            SocialClassId.POOR_PEASANT,
            SocialClassId.MIDDLE_PEASANT,
            SocialClassId.RICH_PEASANT,
            SocialClassId.LANDLORD);
  }

  /** ★ **地点取自产业 id**（`IndustryHexKeys` 是唯一拼写点）：非零格上 cohort 的居住格必须跟着走（写死 `(0,0)` 会红）。 */
  @Test
  void theCohortResidenceFollowsTheIndustrysHexKey() {
    CohortKey cohort =
        ((Recipient.ToCohort)
                RegimeRelations.defaultRelation(
                        new RegimeId("tenant"),
                        new IndustryId("farm@3_-2"),
                        ESTATE,
                        java.util.Set.of(ResidenceKind.RURAL))
                    .rules()
                    .get(0)
                    .recipient())
            .cohort();

    assertThat(cohort)
        .isEqualTo(new CohortKey(new HexCoord(3, -2), ResidenceKind.RURAL, SocialClassId.LANDLORD));
    assertThat(cohort.toString()).as("规范串（阶段 6 的 receipt 表会拿它作键）").isEqualTo("3_-2|rural|landlord");
  }

  /** ★ 四档**保序**（spec §六 表序）+ 每档的标志性规则类型；未登记的制度**即抛并列出档位**（fail-closed）。 */
  @Test
  void theRegisteredTableIsExactlyThoseFourAndAnUnregisteredRegimeIsRefused() {
    assertThat(RegimeRelations.registered().keySet())
        .containsExactly("feudal", "household", "handicraft", "tenant");
    assertThat(RegimeRelations.registered())
        .as("档位 → 该档**第一条**规则的规则类型（派生视图，非第二份规则表）")
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of(
                "feudal", RuleType.FIXED_IN_KIND_PER_LABOR,
                "household", RuleType.OUTPUT_SHARE,
                "handicraft", RuleType.OUTPUT_SHARE,
                "tenant", RuleType.FIXED_IN_KIND_RENT));

    assertThatThrownBy(
            () ->
                RegimeRelations.defaultRelation(
                    new RegimeId("capitalist"),
                    FARM,
                    ESTATE,
                    java.util.Set.of(ResidenceKind.RURAL)))
        .as("★ 未登记的制度不许猜（同 RegimeOperators 的 R1 口径）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capitalist")
        .hasMessageContaining("tenant");
    assertThatThrownBy(
            () ->
                RegimeRelations.defaultRelation(
                    new RegimeId("Feudal"), FARM, ESTATE, java.util.Set.of(ResidenceKind.RURAL)))
        .as("字面量大小写敏感（本表不做归一）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ **拿不到格键 ⇒ 抛**（理由：关系必须有地点）；三个参数 null 也各自抛（同 `RegimeOperators` 的守卫口径）。 */
  @Test
  void anIndustryWithoutAHexKeyOrAnyNullArgumentIsRefused() {
    assertThatThrownBy(
            () ->
                RegimeRelations.defaultRelation(
                    new RegimeId("feudal"),
                    new IndustryId("farm"),
                    ESTATE,
                    java.util.Set.of(ResidenceKind.RURAL)))
        .as("产业 id 里没有 '@' ⇒ 推不出地点 ⇒ 抛（不许拿 (0,0) 顶替）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("farm");
    assertThatThrownBy(
            () ->
                RegimeRelations.defaultRelation(
                    null, FARM, ESTATE, java.util.Set.of(ResidenceKind.RURAL)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RegimeRelations.defaultRelation(
                    new RegimeId("feudal"), null, ESTATE, java.util.Set.of(ResidenceKind.RURAL)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RegimeRelations.defaultRelation(
                    new RegimeId("feudal"), FARM, null, java.util.Set.of(ResidenceKind.RURAL)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★★ **E9 的处置（写下来，让"加回一条 SELF_RETENTION"这种改动当场红）**：四档的"自留"都由 `residualOwner = operator` 表达，**不写**
   * `SELF_RETENTION` 规则。
   *
   * <p>★ 理由：`SELF_RETENTION` 属**实物档** ⇒ 按二选一守卫**必须带 `commodity``（T1 的契约不动）；
   * 而"自留"在语义上是**对全部商品**的自留，给它挑一个商品反而把话说小了；且 T3 的公式表里它的数量恒为 0 ⇒ 「不写这条规则」是**等价路径**（空表 ⇒ 全归
   * residualOwner）。
   */
  @Test
  void theFourTablesExpressSelfRetentionAsTheResidualOwner() {
    for (String regime : List.of("feudal", "household", "handicraft", "tenant")) {
      ProductionRelation relation =
          RegimeRelations.defaultRelation(
              new RegimeId(regime), FARM, ESTATE, java.util.Set.of(ResidenceKind.RURAL));

      assertThat(relation.residualOwner()).as("档 %s：自留 = 余额归 operator", regime).isEqualTo(ESTATE);
      assertThat(relation.rules())
          .as("档 %s：一条 SELF_RETENTION 都不写（等价路径，见本用例的类注）", regime)
          .noneMatch(rule -> rule.type() == RuleType.SELF_RETENTION);
      assertThat(relation.rules())
          .as("档 %s：每条规则都带商品（实物档）或显式为货币档（E9 的二选一）", regime)
          .allSatisfy(
              rule ->
                  assertThat(rule.commodity().isPresent())
                      .as("规则 %s 的商品存在性与 type.money() 必须一致", rule)
                      .isEqualTo(!rule.type().money()));
    }
  }

  // ── 夹具：期望值**全是字面量**（不读 RegimeRelations 的常量/函数）────────────────────────

  /** {@code feudal} 的给养：每 1000 千分劳动给 **144 毫粮**（= 一人的周期口粮 10,000 毫粮 ÷ 一人的周期劳动 69.6）。 */
  private static CompensationRule subsistence(SocialClassId stratum) {
    return new CompensationRule(
        RuleType.FIXED_IN_KIND_PER_LABOR,
        cohort(stratum),
        Pool.NET_AFTER_INPUTS,
        Weight.LABOR_AMOUNT,
        0,
        144L,
        Optional.of(GRAIN),
        Optional.empty(),
        10);
  }

  /**
   * 实物分成档（{@code household} / {@code handicraft} 共用）：{@code OUTPUT_SHARE × LABOR_AMOUNT}，给的是**布**。
   */
  private static CompensationRule clothShare(
      SocialClassId stratum, int ratePerMille, int priority) {
    return new CompensationRule(
        RuleType.OUTPUT_SHARE,
        cohort(stratum),
        Pool.NET_AFTER_INPUTS,
        Weight.LABOR_AMOUNT,
        ratePerMille,
        0L,
        Optional.of(CLOTH),
        Optional.empty(),
        priority);
  }

  /** 粮的分成档（{@code feudal} 的地租）—— ★ H2 起池与权重是两个独立的实参。 */
  private static CompensationRule grainShare(
      RuleType type,
      Pool pool,
      Weight weight,
      int ratePerMille,
      SocialClassId stratum,
      int priority) {
    return new CompensationRule(
        type,
        cohort(stratum),
        pool,
        weight,
        ratePerMille,
        0L,
        Optional.of(GRAIN),
        Optional.empty(),
        priority);
  }

  /** 货币工资（I5.3：`commodity` **空** = 货币档 ⇒ 只定义、不结算）。 */
  private static CompensationRule moneyWage(SocialClassId stratum, int priority) {
    return new CompensationRule(
        RuleType.FIXED_MONEY_WAGE,
        cohort(stratum),
        Pool.FIXED_AMOUNT,
        Weight.NONE,
        0,
        1_000L,
        Optional.empty(),
        Optional.of(CURRENCY),
        priority);
  }

  /** 本文件默认那一格（{@code 0_0}）上的某个阶层 cohort。 */
  private static Recipient cohort(SocialClassId stratum) {
    return new Recipient.ToCohort(new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, stratum));
  }
}
