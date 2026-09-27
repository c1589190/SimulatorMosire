package io.mosire.simos.economy.api.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>M1.1：币种与货币工具身份</b>（{@link CurrencyDef} / {@link InstrumentId} / {@link InstrumentKind} /
 * {@link MoneyInstrument} / {@link MoneyVocabulary}）的逐条守卫。
 *
 * <p>★★ <b>判别力从哪来（每条守卫至少一条用例，且每条都能被一行错代码弄红）</b>：
 *
 * <ul>
 *   <li><b>null 一档</b>：五个组件各打一路 —— "缺值"（{@code Optional.empty()}）与"没填"（{@code null}）是两件事；
 *   <li><b>负 scale</b>：{@code -1} 抛；★ 边界 {@code 0} <b>必须通过</b>（"没有小数位"是合法精度，不是坏数据）；
 *   <li><b>缺 issuer</b>：{@code STATE_NOTE} / {@code BANK_DEPOSIT} 逐档各一条（价值就是对发行人的索取）；
 *   <li><b>多余 issuer</b>：{@code SPECIE} 带上发行人 ⇒ 抛 —— ★ 这一条是 M1.1 被点名要想清楚的那处： "金属币的 issuer 必须为空"（理由见
 *       {@link MoneyInstrument} 的类注）；
 *   <li><b>词表自洽</b>：{@code silver} 迁成"一个 {@link CurrencyDef} + 一个 {@code SPECIE} 工具"；旧 {@link
 *       CurrencyId} 读口<b>仍在</b>（{@code MoneyVocabulary.SILVER_CURRENCY} 与 {@code new
 *       CurrencyId("silver")} 同值）。
 * </ul>
 */
class MoneyIdentityTest {

  private static final ActorRef TREASURY = new ActorRef(ActorKind.GOVERNMENT, "treasury@0_0");

  private static final ActorRef BANK = new ActorRef(ActorKind.ORGANIZATION, "bank@0_0");

  private static final CurrencyId SILVER = new CurrencyId("silver");

  // ── CurrencyDef：id 非空白 + scale ≥ 0 ────────────────────────────────────────────

  @Test
  void currencyDefRejectsBlankIdAndTakesTheScaleAsIs() {
    assertThatThrownBy(() -> new CurrencyDef(null, 3))
        .as("id 为 null ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CurrencyDef.id 不得为空白");
    assertThatThrownBy(() -> new CurrencyDef("  ", 3))
        .as("id 为纯空白 ⇒ 抛（一条空白 id 的币种是坏数据，不是'无名币'）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CurrencyDef.id 不得为空白");

    CurrencyDef def = new CurrencyDef("silver", 3);
    assertThat(def.id()).isEqualTo("silver");
    assertThat(def.scale()).as("scale 原样保留（本类不做归一、也不做折算）").isEqualTo(3);
  }

  @Test
  void currencyDefRejectsNegativeScaleButAcceptsZero() {
    assertThatThrownBy(() -> new CurrencyDef("silver", -1))
        .as("★ 负 scale ⇒ 抛（最小单位精度不可能是负的）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CurrencyDef.scale 是最小单位精度、不得为负");

    assertThatCode(() -> new CurrencyDef("cash", 0))
        .as("★ 边界：scale = 0 合法（'没有小数位'是一种精度，不是坏数据）")
        .doesNotThrowAnyException();
    assertThat(new CurrencyDef("cash", 0).scale()).isZero();
  }

  @Test
  void currencyDefBridgesToTheRuntimeCurrencyIdInOnePlace() {
    assertThat(new CurrencyDef("silver", 3).currencyId())
        .as("★ 词表条目（String id）→ 运行时身份（CurrencyId）只有 currencyId() 这一处转换")
        .isEqualTo(SILVER);
    assertThat(new CurrencyDef("silver", 3).currencyId().value()).isEqualTo("silver");
  }

  // ── InstrumentId：与同族 22 个稳定 ID 同形（另在 EconomyIdsTest 登记）───────────────

  @Test
  void instrumentIdRejectsNullAndBlankAndKeepsABareToString() {
    assertThatThrownBy(() -> new InstrumentId(null))
        .as("构造器拒 null")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> InstrumentId.parse(" \t "))
        .as("parse 拒纯空白（宁抛不静默）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("InstrumentId 不得为空白");
    assertThat(new InstrumentId("silver-specie"))
        .as("★ toString 是裸值（键的线格式走它 + parse 的配对）")
        .hasToString("silver-specie");
    assertThat(InstrumentId.parse("silver-specie")).isEqualTo(new InstrumentId("silver-specie"));
  }

  // ── InstrumentKind：三档 + "要不要发行人"一处拼写 ────────────────────────────────

  @Test
  void instrumentKindHasExactlyThreeKindsAndOneIssuerRuleEach() {
    assertThat(InstrumentKind.values())
        .as("★ 三档词表：加一档必须同时回答'它要不要发行人'（requiresIssuer 是无 default 的 switch 表达式 ⇒ 编译期拦）")
        .containsExactly(
            InstrumentKind.SPECIE, InstrumentKind.STATE_NOTE, InstrumentKind.BANK_DEPOSIT);

    assertThat(InstrumentKind.SPECIE.requiresIssuer()).as("★ 金属币：价值来自金属本身 ⇒ **不要**发行人").isFalse();
    assertThat(InstrumentKind.STATE_NOTE.requiresIssuer())
        .as("★ 国币：价值来自国家的兑付承诺 ⇒ **要**发行人")
        .isTrue();
    assertThat(InstrumentKind.BANK_DEPOSIT.requiresIssuer())
        .as("★ 银行存款：价值来自钱庄的兑付承诺 ⇒ **要**发行人")
        .isTrue();
    assertThat(InstrumentKind.SPECIE.label()).isEqualTo("金属币");
    assertThat(
            List.of(
                InstrumentKind.SPECIE.name(),
                InstrumentKind.STATE_NOTE.name(),
                InstrumentKind.BANK_DEPOSIT.name()))
        .as("★ 线格式：枚举走 Jackson 默认的 name()（同 ActorKind）⇒ 这三串就是落盘字面量，改名字就是改线格式")
        .containsExactly("SPECIE", "STATE_NOTE", "BANK_DEPOSIT");
  }

  // ── MoneyInstrument：五条 null + 缺 issuer + 多余 issuer ──────────────────────────

  @Test
  void moneyInstrumentRejectsNullInEveryComponent() {
    InstrumentId id = new InstrumentId("silver-specie");
    assertThatThrownBy(
            () ->
                new MoneyInstrument(
                    null, SILVER, InstrumentKind.SPECIE, Optional.empty(), Optional.empty()))
        .as("id 为 null ⇒ 抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MoneyInstrument.id 不得为 null");
    assertThatThrownBy(
            () ->
                new MoneyInstrument(
                    id, null, InstrumentKind.SPECIE, Optional.empty(), Optional.empty()))
        .as("currency 为 null ⇒ 抛（说不出币种就不是一张钱）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MoneyInstrument.currency 不得为 null");
    assertThatThrownBy(
            () -> new MoneyInstrument(id, SILVER, null, Optional.empty(), Optional.empty()))
        .as("kind 为 null ⇒ 抛（说不出工具种类就不是一张钱）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MoneyInstrument.kind 不得为 null");
    assertThatThrownBy(
            () -> new MoneyInstrument(id, SILVER, InstrumentKind.SPECIE, null, Optional.empty()))
        .as("★ issuer 为 null（不是 Optional.empty()）⇒ 抛 —— '没有发行人'的形制是空 Optional，不是 null")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MoneyInstrument.issuer 不得为 null");
    assertThatThrownBy(
            () -> new MoneyInstrument(id, SILVER, InstrumentKind.SPECIE, Optional.empty(), null))
        .as("★ redeemer 为 null ⇒ 抛（同上）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MoneyInstrument.redeemer 不得为 null");
  }

  @Test
  void aNoteOrADepositWithoutAnIssuerIsRejected() {
    InstrumentId note = new InstrumentId("treasury-note");
    InstrumentId deposit = new InstrumentId("bank-deposit");

    assertThatThrownBy(
            () ->
                new MoneyInstrument(
                    note, SILVER, InstrumentKind.STATE_NOTE, Optional.empty(), Optional.empty()))
        .as("★★ 缺 issuer：国币的价值就是对发行人的索取 ⇒ 读不出「谁欠我」就等于没有价值来源")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须有发行人");
    assertThatThrownBy(
            () ->
                new MoneyInstrument(
                    deposit,
                    SILVER,
                    InstrumentKind.BANK_DEPOSIT,
                    Optional.empty(),
                    Optional.empty()))
        .as("★★ 缺 issuer：银行存款同理（逐档各一条，不许只测一档）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须有发行人");

    // ★ 对照：带上发行人 ⇒ 两档都合法（否则上面两条可能只是"这一类永远构造不出来"）
    assertThatCode(
            () ->
                new MoneyInstrument(
                    note,
                    SILVER,
                    InstrumentKind.STATE_NOTE,
                    Optional.of(TREASURY),
                    Optional.empty()))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                new MoneyInstrument(
                    deposit,
                    SILVER,
                    InstrumentKind.BANK_DEPOSIT,
                    Optional.of(BANK),
                    Optional.empty()))
        .doesNotThrowAnyException();
  }

  @Test
  void aSpecieWithAnIssuerIsRejected() {
    InstrumentId coin = new InstrumentId("silver-specie");

    assertThatThrownBy(
            () ->
                new MoneyInstrument(
                    coin, SILVER, InstrumentKind.SPECIE, Optional.of(TREASURY), Optional.empty()))
        .as(
            "★★ 多余 issuer：金属币**必须为空** —— issuer 是「价值是对谁的索取」的槽位，而金属币的价值来自金属本身；"
                + "把铸主填进来会被读成「某人欠我一张银币」（谁铸的属 M4 铸熔/成色，是另一个事实）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有发行人");

    assertThatCode(
            () ->
                new MoneyInstrument(
                    coin, SILVER, InstrumentKind.SPECIE, Optional.empty(), Optional.empty()))
        .as("对照：空 issuer 才是金属币的合法态（否则上面那条可能只是'SPECIE 构造不出来'）")
        .doesNotThrowAnyException();
  }

  @Test
  void theRedeemerSlotIsOptionalInBothDirections() {
    InstrumentId coin = new InstrumentId("silver-specie");
    InstrumentId deposit = new InstrumentId("bank-deposit");

    assertThat(
            new MoneyInstrument(
                    coin, SILVER, InstrumentKind.SPECIE, Optional.empty(), Optional.empty())
                .redeemer())
        .as("★ redeemer 空 = 没有兑现人（本批常态：兑现属 M4+）")
        .isEmpty();
    assertThat(
            new MoneyInstrument(
                    deposit,
                    SILVER,
                    InstrumentKind.BANK_DEPOSIT,
                    Optional.of(BANK),
                    Optional.of(BANK))
                .redeemer())
        .as("★ redeemer 非空也得活着 —— 它是**具名留位**，本批不判兑现，但字段不许是死的（写进去读得出来）")
        .contains(BANK);
  }

  // ── 世界词表：silver = 一个 CurrencyDef + 一个 SPECIE 工具；旧 CurrencyId 读口仍在 ──

  @Test
  void silverMigratesToOneCurrencyDefAndOneSpecieInstrument() {
    assertThat(MoneyVocabulary.SILVER_CURRENCY_ID).isEqualTo("silver");
    assertThat(MoneyVocabulary.SILVER.id()).isEqualTo("silver");
    assertThat(MoneyVocabulary.SILVER.scale())
        .as("★ 毫银 = scale 3（1 银 = 1000 毫银）—— 这个数在 M1.1 之前没有一处写下来")
        .isEqualTo(3);

    MoneyInstrument specie = MoneyVocabulary.SILVER_SPECIE;
    assertThat(specie.id()).hasToString("silver-specie");
    assertThat(specie.currency()).isEqualTo(SILVER);
    assertThat(specie.kind()).isEqualTo(InstrumentKind.SPECIE);
    assertThat(specie.issuer()).as("★ 金属币没有发行人（守卫要求为空；本批 MoneyIssuance 零注册 ⇒ 也说不出谁是发行人）").isEmpty();
    assertThat(specie.redeemer()).as("兑现属 M4+ ⇒ 兑现人留位为空").isEmpty();
  }

  @Test
  void theOldCurrencyIdReadPathIsKept() {
    assertThat(MoneyVocabulary.SILVER_CURRENCY)
        .as("★★ 旧的 CurrencyId 读口保留（裁定「本批只加身份、不动账」）：币种身份与 M1.1 之前逐值相同")
        .isEqualTo(SILVER);
    assertThat(MoneyVocabulary.SILVER.currencyId())
        .as("★ 词表条目与运行时身份逐值一致（CurrencyDef.id ↔ CurrencyId.value 不许漂）")
        .isEqualTo(SILVER);
  }

  @Test
  void theVocabularyIsSelfConsistentAndOrdered() {
    List<CurrencyDef> defs = MoneyVocabulary.allCurrencyDefs();
    List<MoneyInstrument> instruments = MoneyVocabulary.allInstruments();

    assertThat(defs).as("词表非空（否则下面几条是白给的）").isNotEmpty();
    assertThat(instruments).isNotEmpty();
    assertThat(defs).extracting(CurrencyDef::id).as("币种 id 不许重复").doesNotHaveDuplicates();
    assertThat(instruments)
        .extracting(instrument -> instrument.id().value())
        .as("工具 id 不许重复")
        .doesNotHaveDuplicates();
    for (MoneyInstrument instrument : instruments) {
      assertThat(defs)
          .extracting(CurrencyDef::id)
          .as("★ 每张工具的币种都必须在币种词表里有定义（否则它就是「没有币种的工具」）")
          .contains(instrument.currency().value());
      assertThat(instrument.issuer().isPresent())
          .as("★ 词表逐条满足 kind ⇒ issuer 的守卫（守卫不是只写在构造器里，词表本身也得过）")
          .isEqualTo(instrument.kind().requiresIssuer());
    }
  }
}
