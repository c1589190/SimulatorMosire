package io.mosire.simos.economy.api.cohort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.map.hex.HexCoord;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ S1 阶段 4+5 的**契约层**护栏：{@link CohortKey} 的规范串与它的逆 + {@code api.relation} 包的构造期守卫。
 *
 * <p>★ <b>为什么两类东西同处一个测试类</b>：本任务（Task 1）只有**一个**测试文件（brief 的 Files 行点名 {@code
 * CohortKeyTest}）；契约的六份产物是一体交付的（受方身份 + 规则类型 + 关系类型），断言的分组见下面的小节注释。
 *
 * <p>★ <b>本任务的实现里零公式</b>（结算计算是 Task 3 的 {@code ProductionSettlement}）⇒ 这里测的全是
 * <b>构造期守卫</b>与<b>形状事实</b>，没有一条算术断言。
 *
 * <p>★★ <b>判别力来自夹具，不来自断言</b>（本仓实测三次）：坐标取 {@code (3, -2)} / {@code (-4, 7)}（{@code q ≠ r} 且 {@code
 * r} 为负），阶层取词表的<b>末位</b> {@code landlord} 与首位的 {@code poor_peasant} —— 「两段写反」「阶层硬写首位」
 * 「按最后的接缝切」这几类变异体因此都<b>当场红</b>，而不是靠断言堆出来。
 */
class CohortKeyTest {

  /** 夹具商品：**非**「第一个」商品（{@code grain} 在 {@code EconomySeeder} 的清单里居首，故另取一个名字以避开「硬写首项」的巧合）。 */
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** 夹具商品（粮食）—— 与 {@link #CLOTH} 成对，用来测「商品是数据、不是默认值」。 */
  private static final CommodityId GRAIN = new CommodityId("grain");

  /** H2 的币种位夹具（★ 只作**夹具字面量**：本模块没有任何"出厂货币"，出厂值在 {@code RegimeRelations}）。 */
  private static final CurrencyId CURRENCY = new CurrencyId("silver");

  // ── 一、CohortKey：规范串与它的逆 ────────────────────────────────────────────────

  /** ★★ R9：新键类型必须自带裸 toString() + 单参 parse（家户的账 / 转移记录都以它作键）。 */
  @Test
  void cohortKeyRoundTripsThroughItsCanonicalText() {
    CohortKey key =
        new CohortKey(new HexCoord(3, -2), ResidenceKind.RURAL, new SocialClassId("poor_peasant"));
    assertThat(key.toString()).isEqualTo("3_-2|rural|poor_peasant");
    assertThat(CohortKey.parse(key.toString())).isEqualTo(key);
  }

  /**
   * ★ 上一条的**判别力补强**（同一条契约、另一组夹具）：{@code q ≠ r}、{@code r > 0}、阶层取词表末位、 ★ **居住类型取另一档（{@code
   * urban}）**。
   *
   * <p>★ 为什么需要它：只测 {@code (3, -2) | rural | poor_peasant} 时，「{@code toString()} 写反段」
   * 的变异体只有一半会被抓，而「阶层段硬写首位 {@code poor_peasant}」「坐标段硬写 {@code 3_-2}」 「**居住段硬写 {@code
   * rural}**」这类变异体<b>会存活</b> —— 夹具恰好等于它硬写的那个值。 换一组<b>非默认</b>夹具（含另一档居住类型）即可杀。
   */
  @Test
  void cohortKeyRoundTripsForANonDefaultStratumAndAsymmetricCoordinates() {
    CohortKey key = new CohortKey(new HexCoord(-4, 7), ResidenceKind.URBAN, SocialClassId.LANDLORD);

    assertThat(key.toString()).as("规范串的形状 = <q>_<r>|居住类型|阶层").isEqualTo("-4_7|urban|landlord");
    assertThat(CohortKey.parse(key.toString())).as("parse 是 toString 的逆").isEqualTo(key);
    assertThat(CohortKey.parse("-4_7|urban|landlord").stratum()).isEqualTo(SocialClassId.LANDLORD);
    assertThat(CohortKey.parse("-4_7|urban|landlord").residence())
        .as("★ residence 现在是**居住类型**（H0.1 新增的那一维），不再是格")
        .isEqualTo(ResidenceKind.URBAN);
    assertThat(CohortKey.parse("-4_7|urban|landlord").hex())
        .as("格在 hex 上")
        .isEqualTo(new HexCoord(-4, 7));
  }

  /**
   * ★ <b>「按第一、第二个接缝切三段」是可观测的</b>：第二个接缝之后<b>整段</b>都是阶层段 ⇒ 非法串的报错来自<b>阶层词表</b>，不是坐标轴、也不是居住词表。
   *
   * <p>★ 为什么只能用<b>非法</b>输入来观测它：三个分量都不含接缝 ⇒ 在<b>合法</b>串上任何切法恒等。 接缝位置因此只在 fail-closed
   * 的<b>报错来源</b>上留下痕迹 —— 这正是本类"宁抛不静默"品质的一部分。 （H0.1 之前是两段、只有一处接缝；现在是三段、两处接缝，而"尾巴整段交给阶层词表"这条性质不变。）
   */
  @Test
  void parseTreatsEverythingAfterTheSecondSeamAsTheStratum() {
    assertThatThrownBy(() -> CohortKey.parse("3_-2|rural|poor_peasant|junk"))
        .as("★ 第二个接缝之后整段交给阶层词表 ⇒ 消息里带的是那整段（不是坐标轴、也不是居住词表的错）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("poor_peasant|junk");
  }

  @Test
  void cohortKeyRejectsMissingHalvesAndMalformedText() {
    assertThatThrownBy(() -> new CohortKey(null, ResidenceKind.RURAL, SocialClassId.POOR_PEASANT))
        .as("hex 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CohortKey(new HexCoord(0, 0), null, SocialClassId.POOR_PEASANT))
        .as("★ 居住类型不得为 null —— 少了它，农村与城镇的同阶层家户会并账（裁定 R-N1-A）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, null))
        .as("stratum 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(() -> CohortKey.parse(null))
        .as("null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse(""))
        .as("空白")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse("3_-2"))
        .as("没有接缝")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse("|rural|poor_peasant"))
        .as("接缝在首（格段缺失）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse("3_-2|rural"))
        .as("★ 只有一处接缝（缺居住段之后的那一段）⇒ 三段形状不足")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse("3_-2|rural|"))
        .as("第二接缝在尾（阶层段缺失）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse("3_-2||poor_peasant"))
        .as("★ 居住段为空")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse("3_-2|nomad|poor_peasant"))
        .as("★ 词表外的居住类型 ⇒ 交给 ResidenceKind 的词表守卫抛（本类不复述居住词表）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CohortKey.parse("3_-2|rural|noble"))
        .as("★ 词表外的阶层 ⇒ 交给 SocialClassId 的词表守卫抛（本类不复述阶层词表）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 二、RuleType / Basis：两套六档词表 ──────────────────────────────────────────

  /**
   * ★★ <b>两套词表都恰是六档、且保序</b>（spec §2.4 的表序 + 本计划的补档）：{@code containsExactly} 同时钉住
   * <b>成员</b>与<b>次序</b> ⇒ 少一档、多一档、换序都红。
   *
   * <p>★ {@code Basis.FIXED_AMOUNT} 是<b>第 6 档</b>（裁定 E5）：spec §2.4 的五个 {@code basis} 说的是「每单位什么」， 而
   * {@code FIXED_IN_KIND_RENT} / {@code FIXED_MONEY_*} 的「数量从哪来」在那五档里<b>没有落点</b>。
   *
   * <p>★ <b>fail-closed</b>：词表外的输入即抛、消息列出合法值（照 {@code ActorKind.parse} 的形制）—— 静默兜底会让
   * "写错规则类型"变成运行时幽灵。
   */
  @Test
  void bothVocabulariesAreExactlySixInOrderAndParseFailsClosed() {
    assertThat(RuleType.values())
        .containsExactly(
            RuleType.SELF_RETENTION,
            RuleType.OUTPUT_SHARE,
            RuleType.FIXED_IN_KIND_PER_LABOR,
            RuleType.FIXED_IN_KIND_RENT,
            RuleType.FIXED_MONEY_WAGE,
            RuleType.FIXED_MONEY_RENT);
    assertThat(Basis.values())
        .containsExactly(
            Basis.GROSS_OUTPUT,
            Basis.NET_AFTER_INPUTS,
            Basis.OPERATOR_SURPLUS,
            Basis.LABOR_AMOUNT,
            Basis.FIXED_AMOUNT);

    assertThat(RuleType.parse("FIXED_IN_KIND_RENT")).isEqualTo(RuleType.FIXED_IN_KIND_RENT);
    assertThat(Basis.parse("FIXED_AMOUNT")).isEqualTo(Basis.FIXED_AMOUNT);

    assertThatThrownBy(() -> RuleType.parse("MONEY_WAGE"))
        .as("★ 词表外即抛（不做前缀猜测、不做归一）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FIXED_MONEY_WAGE");
    assertThatThrownBy(() -> Basis.parse("GROSS"))
        .as("★ 词表外即抛，且消息列出六档")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("FIXED_AMOUNT");
    assertThatThrownBy(() -> RuleType.parse(null))
        .as("null 也走同一条 fail-closed")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Basis.parse(" "))
        .as("空白同上")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★★ <b>「货币档」这条事实的唯拼写点在 {@link RuleType}</b>（{@code money()}）：Task 3 的结算据它把规则分流进 {@code
   * deferredMoney}（I5.3「只定义、不结算」）。若把这条事实改写成别处的字符串判断（{@code name().contains}）， 就是同一个格式的第二处拼写点 ——
   * 本测试把它钉在类型上。
   */
  @Test
  void moneyFlagIsPinnedPerRuleType() {
    assertThat(RuleType.FIXED_MONEY_WAGE.money()).isTrue();
    assertThat(RuleType.FIXED_MONEY_RENT.money()).isTrue();
    assertThat(RuleType.SELF_RETENTION.money()).isFalse();
    assertThat(RuleType.OUTPUT_SHARE.money()).isFalse();
    assertThat(RuleType.FIXED_IN_KIND_PER_LABOR.money()).isFalse();
    assertThat(RuleType.FIXED_IN_KIND_RENT.money()).isFalse();
  }

  // ── 三、Recipient：sealed ⇒「恰其一」是类型事实 ─────────────────────────────────

  /**
   * ★★ 判据②：{@code Recipient} 是 <b>sealed</b> 且只有 {@code ToActor} / {@code ToCohort} 两个变体 ⇒ 「受方是
   * actor 还是 cohort，<b>恰其一</b>」是<b>类型事实</b>，不是运行时检查（不许出现"两个都填了怎么办"的分支）。
   *
   * <p>★ {@code getPermittedSubclasses()} 是这条事实唯一的机械读法：加第三个变体、或去掉 {@code sealed} 都当场红。 上游（spec §2.4
   * 的两个规则列表）正是靠这条事实被合成了<b>一张</b>表（裁定 E4）。
   */
  @Test
  void recipientIsSealedSoActorAndCohortAreExactlyOne() {
    assertThat(Recipient.class.isSealed()).as("★ 判据②：sealed").isTrue();
    assertThat(Recipient.class.getPermittedSubclasses())
        .as("★ 恰两个变体（加第三个 ⇒ 「恰其一」失守）")
        .containsExactlyInAnyOrder(Recipient.ToActor.class, Recipient.ToCohort.class);

    ActorRef estate = new ActorRef(ActorKind.ESTATE, "farm@0_0");
    CohortKey cohort =
        new CohortKey(new HexCoord(2, -1), ResidenceKind.RURAL, SocialClassId.LANDLORD);

    assertThat(new Recipient.ToActor(estate).actor()).isEqualTo(estate);
    assertThat(new Recipient.ToCohort(cohort).cohort()).isEqualTo(cohort);

    assertThatThrownBy(() -> new Recipient.ToActor(null))
        .as("ToActor 的 actor 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Recipient.ToCohort(null))
        .as("ToCohort 的 cohort 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 四、CompensationRule：构造期守卫 ───────────────────────────────────────────

  /**
   * ★★ 货币档只定义字段、不结算（I5.3）：commodity 空 = 货币规则；实物规则必须带 commodity —— ★ <b>H2 起这条"二选一"有两对</b>：商品侧（空 ⟺
   * 货币）与币种侧（非空 ⟺ 货币），两对互为反相、都由构造期守卫判死。
   */
  @Test
  void moneyRulesCarryNoCommodityAndInKindRulesRequireOne() {
    Recipient rec =
        new Recipient.ToCohort(
            new CohortKey(
                new HexCoord(0, 0), ResidenceKind.RURAL, new SocialClassId("poor_peasant")));
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_MONEY_WAGE,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    5_000L,
                    Optional.of(new CommodityId("grain")),
                    Optional.of(CURRENCY),
                    10))
        .as("★ 货币规则带了商品 ⇒ 抛（二选一是类型事实，不许两处都能填）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_MONEY_WAGE,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    5_000L,
                    Optional.empty(),
                    Optional.empty(),
                    10))
        .as("★ H2：货币规则没有币种 ⇒ 抛（『1000 毫钱』没说是什么钱）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    5_000L,
                    Optional.empty(),
                    Optional.empty(),
                    10))
        .as("★ 实物规则没有商品 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    5_000L,
                    Optional.of(new CommodityId("grain")),
                    Optional.of(CURRENCY),
                    10))
        .as("★ H2：实物规则带了币种 ⇒ 抛（实物不是钱）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * ★ 上一条的<b>许可面</b>（判别力来自夹具的另一半）：{@code FIXED_MONEY_*} <b>不带</b>商品、<b>带</b>币种时必须能构造出来。
   *
   * <p>★ 为什么必须补这一条：否则「货币规则一律抛」这种过度实现也会让上一条全绿 —— 上一条只证明了「带商品 ⇒ 抛」， 没证明「不带商品 ⇒ 收」。I5.3
   * 要的是<b>定义得住</b>（货币档在位、字段齐、待 S2 结算），不是<b>构造不出来</b>。
   */
  @Test
  void moneyRulesConstructFineWithoutACommodity() {
    Recipient toActor = new Recipient.ToActor(new ActorRef(ActorKind.WORKSHOP, "craft@1_0"));
    CompensationRule rule =
        rule(
            RuleType.FIXED_MONEY_RENT,
            toActor,
            Pool.FIXED_AMOUNT,
            Weight.NONE,
            0,
            5_000L,
            Optional.empty(),
            Optional.of(CURRENCY),
            10);

    assertThat(rule.type()).isEqualTo(RuleType.FIXED_MONEY_RENT);
    assertThat(rule.commodity()).as("★ 货币档的商品位是空的（这就是『只定义、不结算』的字段形态）").isEmpty();
    assertThat(rule.currency()).as("★ H2：币种位必须说清是哪一种钱（实物档那一侧恒空）").contains(CURRENCY);
    assertThat(rule.fixedAmount()).as("★ 但固定额在（字段是齐的，待 S2 的 ledger 来结算）").isEqualTo(5_000L);
  }

  /** ★ 判据④：{@code ratePerMille ∈ [0, 1000]}、{@code fixedAmount ≥ 0}；判据③：{@code priority ≥ 0}。 */
  @Test
  void compensationRuleRejectsOutOfRangeNumbersAndNullHalves() {
    Recipient rec =
        new Recipient.ToCohort(
            new CohortKey(new HexCoord(0, 0), ResidenceKind.RURAL, SocialClassId.POOR_PEASANT));

    // ★ 边界合法：1000‰ = 全给出去；0‰ = 这一档不分成
    assertThat(
            inKind(
                    RuleType.OUTPUT_SHARE,
                    rec,
                    Pool.GROSS_OUTPUT,
                    Weight.NONE,
                    1_000,
                    0L,
                    Optional.of(GRAIN),
                    0)
                .ratePerMille())
        .isEqualTo(1_000);
    assertThat(
            inKind(
                    RuleType.OUTPUT_SHARE,
                    rec,
                    Pool.GROSS_OUTPUT,
                    Weight.NONE,
                    0,
                    0L,
                    Optional.of(GRAIN),
                    0)
                .ratePerMille())
        .isZero();
    // ★ 固定额档与分成档**各自**可以为 0（两个字段独立：一条固定额规则的 ratePerMille 就该是 0）
    assertThat(
            inKind(
                    RuleType.FIXED_IN_KIND_RENT,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    7_000L,
                    Optional.of(GRAIN),
                    3)
                .fixedAmount())
        .isEqualTo(7_000L);

    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.OUTPUT_SHARE,
                    rec,
                    Pool.GROSS_OUTPUT,
                    Weight.NONE,
                    1_001,
                    0L,
                    Optional.of(GRAIN),
                    0))
        .as("超 1000‰ ⇒ 抛（分成率不可能超过全额）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.OUTPUT_SHARE,
                    rec,
                    Pool.GROSS_OUTPUT,
                    Weight.NONE,
                    -1,
                    0L,
                    Optional.of(GRAIN),
                    0))
        .as("负分成率 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.FIXED_IN_KIND_RENT,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    -1L,
                    Optional.of(GRAIN),
                    0))
        .as("负固定额 ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.FIXED_IN_KIND_RENT,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.NONE,
                    0,
                    1L,
                    Optional.of(GRAIN),
                    -1))
        .as("负 priority ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () -> inKind(null, rec, Pool.FIXED_AMOUNT, Weight.NONE, 0, 1L, Optional.of(GRAIN), 0))
        .as("type 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.OUTPUT_SHARE,
                    null,
                    Pool.GROSS_OUTPUT,
                    Weight.NONE,
                    300,
                    0L,
                    Optional.of(GRAIN),
                    0))
        .as("recipient 不得为 null（受方必须恰有一个）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.OUTPUT_SHARE, rec, null, Weight.NONE, 300, 0L, Optional.of(GRAIN), 0))
        .as("pool 不得为 null（否则『从哪一层取』无从回答）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.OUTPUT_SHARE,
                    rec,
                    Pool.GROSS_OUTPUT,
                    null,
                    300,
                    0L,
                    Optional.of(GRAIN),
                    0))
        .as("★ H2：weight 不得为 null（否则『池怎么分』无从回答；不分请显式给 NONE）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CompensationRule(
                    RuleType.FIXED_IN_KIND_RENT,
                    rec,
                    Pool.FIXED_AMOUNT,
                    Weight.LABOR_AMOUNT,
                    0,
                    1L,
                    Optional.of(GRAIN),
                    Optional.empty(),
                    0))
        .as("★ H2：固定额配了权重 ⇒ 抛（固定额没有『按什么分』这一维）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                inKind(
                    RuleType.OUTPUT_SHARE, rec, Pool.GROSS_OUTPUT, Weight.NONE, 300, 0L, null, 0))
        .as("★ Optional 本身不得为 null（宁抛不静默：null 会退化成 NPE）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 五、ProductionRelation：单列表 + priority ──────────────────────────────────

  /** ★★ 判据③：{@code rules} <b>保序不可变</b>、逐项非空、{@code priority} <b>允许重复</b>。 */
  @Test
  void productionRelationKeepsRuleOrderAndAllowsDuplicatePriorities() {
    ActorRef operator = new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0");
    Recipient landlord =
        new Recipient.ToCohort(
            new CohortKey(new HexCoord(2, -1), ResidenceKind.RURAL, SocialClassId.LANDLORD));
    CompensationRule first =
        inKind(
            RuleType.FIXED_IN_KIND_RENT,
            landlord,
            Pool.FIXED_AMOUNT,
            Weight.NONE,
            0,
            4_000L,
            Optional.of(GRAIN),
            10);
    CompensationRule second =
        inKind(
            RuleType.OUTPUT_SHARE,
            landlord,
            Pool.NET_AFTER_INPUTS,
            Weight.NONE,
            300,
            0L,
            Optional.of(CLOTH),
            10);
    List<CompensationRule> incoming = new ArrayList<>(List.of(first, second));

    ProductionRelation relation =
        new ProductionRelation(new IndustryId("farm@0_0"), operator, null, incoming, operator);

    assertThat(relation.rules())
        .as("★ 保序：与传入次序逐一相同（次序是数据 —— Task 3 按它排 priority）")
        .containsExactly(first, second);
    assertThat(relation.rules().get(1).commodity()).as("★ 商品是数据、不是默认值：第二条是布").contains(CLOTH);

    incoming.add(
        inKind(
            RuleType.SELF_RETENTION,
            new Recipient.ToActor(operator),
            Pool.OPERATOR_SURPLUS,
            Weight.NONE,
            0,
            0L,
            Optional.of(GRAIN),
            0));
    assertThat(relation.rules()).as("★ 建好之后改外部表 ⇒ 已建的关系逐项不变").hasSize(2);
    assertThatThrownBy(() -> relation.rules().add(first))
        .as("★ 不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void productionRelationRejectsMissingHalvesAndNullRulesButAllowsNoRules() {
    ActorRef operator = new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0");
    IndustryId activity = new IndustryId("farm@0_0");
    List<CompensationRule> rules =
        List.of(
            inKind(
                RuleType.FIXED_IN_KIND_RENT,
                new Recipient.ToCohort(
                    new CohortKey(
                        new HexCoord(2, -1), ResidenceKind.RURAL, SocialClassId.LANDLORD)),
                Pool.FIXED_AMOUNT,
                Weight.NONE,
                0,
                4_000L,
                Optional.of(GRAIN),
                10));

    assertThatThrownBy(() -> new ProductionRelation(null, operator, null, rules, operator))
        .as("activity 不得为 null（身份 = 它结算的那个 activity，不另造 id）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProductionRelation(activity, null, null, rules, operator))
        .as("operator 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProductionRelation(activity, operator, null, null, operator))
        .as("rules 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ProductionRelation(activity, operator, null, rules, null))
        .as("residualOwner 不得为 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ProductionRelation(
                    activity, operator, null, Arrays.asList(rules.get(0), null), operator))
        .as("★ 逐项非空：一条 null 规则不许混进去")
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(new ProductionRelation(activity, operator, null, List.of(), operator).rules())
        .as("★ 空表合法：一条规则都没有 ⇒ 产出全部归 residualOwner（自留是缺省，不是坏数据）")
        .isEmpty();
  }

  /**
   * ★★ 判据⑤（H2 重述）：{@code Optional} <b>只用于两个位置</b> —— 机械读法 = 「{@code CompensationRule} 的 {@code
   * Optional} 组件恰是 {@code commodity} 与 {@code currency}，且各自只有一个含义」。
   *
   * <p>★ 为什么值得一条断言：{@code commodity} 的"空"是 <b>I5.3 的信号本身</b>（空 = 货币档、待 S2）； {@code currency} 的"空"是
   * <b>H2 的信号本身</b>（非空 = 货币档）—— ★★ <b>两者互为反相</b>（一个空、另一个必非空），
   * 由构造期守卫判死，故"空"仍然<b>只有两种含义且可互相判定</b>：别处再冒出第三个 {@code Optional}，货币档的判别力才会消失。
   */
  @Test
  void optionalAppearsOnlyOnTheCommodityAndCurrencyComponents() {
    assertThat(optionalComponents(CompensationRule.class))
        .as("★ CompensationRule 的 Optional 组件恰有两个：commodity（空 = 货币档）与 currency（非空 = 货币档），互为反相")
        .containsExactly("commodity", "currency");
    assertThat(optionalComponents(ProductionRelation.class))
        .as("★ ProductionRelation 一个 Optional 都没有")
        .isEmpty();
  }

  // ── 六、E4 / E5 的**可表达性**见证（契约装得下 R8 的佃制档） ────────────────────────

  /**
   * ★★ <b>E4 的落点</b>：租<b>显式</b>给 {@code (hex, landlord)} cohort —— 在一张单列表里它就<b>只是一条规则</b>。
   *
   * <p>★ 这一条是"契约<b>装得下</b> R8 的佃制档"的见证（<b>不是</b>公式）：固定实物租 → 地主 cohort（同格）+ 自留 →
   * operator（residualOwner）。真档的出厂表属 Task 2（{@code RegimeRelations}）—— 本任务只证明这些形状造得出来、 又逐值读得回。
   *
   * <p>★ 为什么"显式给"是必须的：地主既不是劳动者（不在 {@code laborOfCohort} 里），也不是 actor ⇒ 不显式给一条 {@code ToCohort((hex,
   * landlord))} 的规则，<b>地主 cohort 的粮源会凭空消失</b>（裁定 E4 第 2 条）。
   *
   * <p>★ <b>E5 的落点</b>：{@code FIXED_IN_KIND_RENT} 的池 = {@link Pool#FIXED_AMOUNT}（旧档的 {@code
   * FIXED_AMOUNT} 那一档）—— spec 的五个 {@code basis} 全是「每单位什么」，固定额没有单位，故没有它们的位置。
   */
  @Test
  void theSingleRuleListCanAddressTheLandlordCohortExplicitly() {
    HexCoord hex = new HexCoord(0, 0);
    ActorRef tenantHousehold = new ActorRef(ActorKind.HOUSEHOLD, "farm@0_0");
    Recipient landlord =
        new Recipient.ToCohort(new CohortKey(hex, ResidenceKind.RURAL, SocialClassId.LANDLORD));

    CompensationRule rent =
        inKind(
            RuleType.FIXED_IN_KIND_RENT,
            landlord,
            Pool.FIXED_AMOUNT,
            Weight.NONE,
            0,
            4_000L,
            Optional.of(GRAIN),
            10);
    CompensationRule selfRetention =
        inKind(
            RuleType.SELF_RETENTION,
            new Recipient.ToActor(tenantHousehold),
            Pool.OPERATOR_SURPLUS,
            Weight.NONE,
            0,
            0L,
            Optional.of(GRAIN),
            20);

    ProductionRelation relation =
        new ProductionRelation(
            new IndustryId("farm@0_0"),
            tenantHousehold,
            null,
            List.of(rent, selfRetention),
            tenantHousehold);

    assertThat(relation.rules().get(0).recipient())
        .as("★ 地租的受方是**地主 cohort**（不是 actor、也不是行键）")
        .isEqualTo(landlord);
    assertThat(((Recipient.ToCohort) relation.rules().get(0).recipient()).cohort().stratum())
        .as("★ 地主是同格的一个**阶层**（产业无关的身份）")
        .isEqualTo(SocialClassId.LANDLORD);
    assertThat(((Recipient.ToCohort) relation.rules().get(0).recipient()).cohort().hex())
        .as("★ 而且带**格**（cohort 的居住格，不是产业的 id）")
        .isEqualTo(hex);
    assertThat(((Recipient.ToCohort) relation.rules().get(0).recipient()).cohort().residence())
        .as("★ 居住类型也在键里（H0.1 新增的那一维）")
        .isEqualTo(ResidenceKind.RURAL);
    assertThat(relation.rules().get(0).pool())
        .as("★ E5 + H2：固定额规则的池是 FIXED_AMOUNT（旧档的第 5 档 FIXED_AMOUNT 逐值映射过来）")
        .isEqualTo(Pool.FIXED_AMOUNT);
    assertThat(relation.rules().get(0).weight())
        .as("★ H2：固定额的权重不适用 ⇒ 恒 NONE")
        .isEqualTo(Weight.NONE);
    assertThat(relation.rules().get(0).priority())
        .as("★ 次序是数据：地租（10）排在自留（20）之前 ⇒ 分成类规则按 priority 序累计")
        .isLessThan(relation.rules().get(1).priority());
    assertThat(relation.residualOwner())
        .as("★ 佃农家户自留：余额归 operator（同时也是 residualOwner）")
        .isEqualTo(tenantHousehold);
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>九参构造器的短名</b>（本类要构造很多条规则，省去重复的完整签名）——H2 起 {@code basis} 换成 {@code pool} × {@code weight}
   * 并补了币种位，故短名也从七参变九参。
   */
  private static CompensationRule rule(
      RuleType type,
      Recipient recipient,
      Pool pool,
      Weight weight,
      int ratePerMille,
      long fixedAmount,
      Optional<CommodityId> commodity,
      Optional<CurrencyId> currency,
      int priority) {
    return new CompensationRule(
        type, recipient, pool, weight, ratePerMille, fixedAmount, commodity, currency, priority);
  }

  /** <b>实物规则</b>的短名：币种恒空（二选一的实物那一侧，逐值由构造期守卫判死）。 */
  private static CompensationRule inKind(
      RuleType type,
      Recipient recipient,
      Pool pool,
      Weight weight,
      int ratePerMille,
      long fixedAmount,
      Optional<CommodityId> commodity,
      int priority) {
    return rule(
        type,
        recipient,
        pool,
        weight,
        ratePerMille,
        fixedAmount,
        commodity,
        Optional.empty(),
        priority);
  }

  /** 判据⑤ 的机械读法：该 record 里类型为 {@link Optional} 的组件名（保序）。 */
  private static List<String> optionalComponents(Class<?> type) {
    return Arrays.stream(type.getRecordComponents())
        .filter(component -> component.getType() == Optional.class)
        .map(RecordComponent::getName)
        .toList();
  }
}
