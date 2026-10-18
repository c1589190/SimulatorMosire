package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyPayloadSupport.enumValue;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.evidence;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalArray;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalOptionalLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.optionalText;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireInt;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireLong;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireObject;
import static io.mosire.simos.economy.spi.EconomyPayloadSupport.requireText;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.debt.DefaultRemedy;
import io.mosire.simos.economy.api.debt.InterestTiming;
import io.mosire.simos.economy.api.debt.MonetaryConversion;
import io.mosire.simos.economy.api.debt.RepaymentRule;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/** 债务/危机信号的机械解析 helper（P0.5-C2）。 */
final class EconomyDebtPayloads {

  // ── E4c：债务合同的载荷解析 ─────────────────────────────────────────────────────────

  /**
   * ★★ <b>E4c：顶层可选 {@code debtContracts} 数组（放开非空）</b>。
   *
   * <pre>
   * {"debtContracts":[
   *   {"debtor":"h-0_0-rural-poor_peasant","creditor":"h-0_0-rural-landlord",
   *    "unit":{"kind":"money","currency":"silver"},
   *    "terms":{"interestRatePerMillePerCycle":20,"interestTiming":"AFTER_REPAYMENT_ON_CLOSE",
   *             "repaymentRule":"AVAILABLE_SURPLUS_SHARE","monetaryConversion":"NOT_ALLOWED",
   *             "defaultRemedy":"MARK_DEFAULTED"},
   *    "source":"ORDINARY_LOAN",
   *    "principal":1000,"openedDay":0,"lastInterestDay":null,"dueCycle":1,"status":"NORMAL"}]}
   * </pre>
   *
   * <ul>
   *   <li>{@code id} 可省：由 {@code (debtor, creditor, unit, terms)} 经 {@link DebtContractId#idOf} 派生；
   *       给了就必须与派生值逐字相同（否则构造期守卫拒绝，不许手写第二份身份）；
   *   <li>★★ {@code terms} <b>必填</b>（不是"省略 ⇒ legacyDefault"）：种子必须把 {@code
   *       interestRatePerMillePerCycle / interestTiming / repaymentRule / monetaryConversion /
   *       defaultRemedy} 五个键给全 —— 缺键 fail-closed，不把漏写静默补成默认档（资本化路径的 `legacyDefault` 只在结算层显式使用，并由
   *       {@code termsSource} 标注）；
   *   <li>★★ {@code source} <b>必填</b>：普通贷款 seed 显式写 {@code ORDINARY_LOAN}；缺键 ⇒ {@code
   *       debt-source-missing}，不得静默给 {@code LEGACY_UNKNOWN} 或工资债；
   *   <li>{@code openedDay} 缺省 = 0；{@code lastInterestDay}/{@code dueCycle} 缺省 = 空；{@code status}
   *       缺省 = NORMAL；
   *   <li>单位：P2c 起只接受 {@code {"kind":"money","currency":"silver"}}；商品债在形成时折货币， {@code
   *       kind=commodity} 稳定拒绝，不写旧档兼容位。
   * </ul>
   *
   * <p>★★ <b>结构/引用/条款校验不在这里重复实现</b>：{@link DebtContract} 与 {@code EconomyData} 的构造期守卫会判 id
   * 派生、debtor/creditor 行存在、{@code HouseholdEconomy.debts} 引用存在。
   *
   * <p>★★ <b>初始债务的配套责任（必须写清）</b>：本方法只把债权记进 economy 状态；它<b>不搬任何 actor 库存/货币/权利</b>。 如果 seed 声明"H1 欠
   * H2 1000 粮"，app 侧的 actor.Seed 协调器必须已经在 H1/H2 的账户里备好对应的真实粮/钱 （否则这条债没有对价，是凭空造出的债权名册）。economy 不自动搬
   * actor 库存，也不为演示凭空补配套。
   */
  static Map<DebtContractId, DebtContract> debtContracts(JsonNode payload) {
    Map<DebtContractId, DebtContract> contracts = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "debtContracts")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("debtContracts 的每项必须是对象: " + node);
      }
      HouseholdId debtor = HouseholdId.parse(requireText(node, "debtor"));
      HouseholdId creditor = HouseholdId.parse(requireText(node, "creditor"));
      DebtUnit.Money unit = debtUnit(requireObject(node, "unit"));
      DebtTerms terms = debtTerms(requireObject(node, "terms"));
      DebtSource source = parseDebtSource(node);
      long principal = requireLong(node, "principal");
      long openedDay = optionalLong(node, "openedDay", 0L);
      OptionalLong lastInterestDay = optionalOptionalLong(node, "lastInterestDay");
      OptionalLong dueCycle = optionalOptionalLong(node, "dueCycle");
      DebtStatus status =
          node.hasNonNull("status") ? debtStatus(requireText(node, "status")) : DebtStatus.NORMAL;
      DebtContractId derived = DebtContractId.idOf(debtor, creditor, unit, terms);
      if (node.hasNonNull("id")) {
        DebtContractId declared = DebtContractId.parse(requireText(node, "id"));
        if (!declared.equals(derived)) {
          throw new IllegalArgumentException(
              "debtContracts[].id 必须与四元组派生值一致（不许手写第二份身份）：声明=" + declared + " 派生=" + derived);
        }
      }
      DebtContract contract =
          new DebtContract(
              derived,
              debtor,
              creditor,
              unit,
              terms,
              source,
              principal,
              openedDay,
              lastInterestDay,
              dueCycle,
              status);
      if (contracts.putIfAbsent(derived, contract) != null) {
        throw new IllegalArgumentException("同一份载荷里债务合同 id 重复: " + derived);
      }
    }
    return contracts;
  }

  /**
   * ★★★ R-11：seed/JSON 的债务来源必须显式给出；缺键/空白 ⇒ {@code debt-source-missing}，不得猜
   * LEGACY_UNKNOWN，也不得把普通贷款静默当工资债。
   */
  static DebtSource parseDebtSource(JsonNode node) {
    if (!node.hasNonNull("source")) {
      throw new IllegalArgumentException("debt-source-missing: debtContracts[].source 必须显式给出");
    }
    try {
      return DebtSource.parse(requireText(node, "source"));
    } catch (IllegalArgumentException failure) {
      throw new IllegalArgumentException(
          "debt-source-missing: debtContracts[].source 非法（" + failure.getMessage() + "）", failure);
    }
  }

  /** ★ E4c/P2c：债务单位节点只接受 {@code kind=money}；商品债不再可写入。 */
  static DebtUnit.Money debtUnit(JsonNode node) {
    String kind = requireText(node, "kind");
    return switch (kind) {
      case "commodity" ->
          throw new IllegalArgumentException(
              "legacy-commodity-debt-unsupported: debtContracts[].unit.kind=commodity 已删除；"
                  + "商品欠款必须先按活动所在地市场区价格折成本区计价货币: "
                  + node);
      case "money" -> {
        if (node.hasNonNull("commodity")) {
          throw new IllegalArgumentException(
              "debtContracts[].unit.kind=money 不得同时给 commodity（空要省略）: " + node);
        }
        yield new DebtUnit.Money(CurrencyId.parse(requireText(node, "currency")));
      }
      default ->
          throw new IllegalArgumentException(
              "debtContracts[].unit.kind 只认 money（commodity 已删除）: " + kind);
    };
  }

  /** ★ E4c：条款节点（五维必填；{@code dueCycle}/{@code dueDay} 可空；缺键 fail-closed，不静默补 0）。 */
  static DebtTerms debtTerms(JsonNode node) {
    int rate = requireInt(node, "interestRatePerMillePerCycle");
    InterestTiming timing =
        enumValue(InterestTiming.class, requireText(node, "interestTiming"), "interestTiming");
    RepaymentRule repayment =
        enumValue(RepaymentRule.class, requireText(node, "repaymentRule"), "repaymentRule");
    MonetaryConversion conversion =
        enumValue(
            MonetaryConversion.class,
            requireText(node, "monetaryConversion"),
            "monetaryConversion");
    DefaultRemedy remedy =
        enumValue(DefaultRemedy.class, requireText(node, "defaultRemedy"), "defaultRemedy");
    OptionalLong dueCycle = optionalOptionalLong(node, "dueCycle");
    OptionalLong dueDay = optionalOptionalLong(node, "dueDay");
    return new DebtTerms(rate, timing, repayment, conversion, remedy, dueCycle, dueDay);
  }

  /** ★ E4c：状态词（词表外具名抛）。 */
  static DebtStatus debtStatus(String text) {
    return enumValue(DebtStatus.class, text, "status");
  }

  /**
   * ★★ E5a：顶层可选 {@code crisisSignals} 数组（缺键 ⇒ 空表）。每项：
   *
   * <pre>{@code
   * {"hex":"0_0","kind":"FOOD","severity":1,"day":120,
   *  "evidence":{"grainGap":-3},"households":["hh-…"],"classes":["poor_peasant"],"reason":"…"}
   * }</pre>
   *
   * <p>★ {@code id} 是可选键：给了必须与 {@link CrisisSignalId#idOf(HexCoord, String)} 一致（不一致 ⇒ 抛）； 不给就由
   * {@code (hex, kind)} 派生。★ {@code evidence} 的值为原始触发量（<b>可为负</b>，见 {@code HexCrisisSignal}
   * 类注）；{@code households}/{@code classes}/{@code evidence} 缺键 ⇒ 空表。 ★★ <b>同 hex 同 kind
   * 只保留最新一条</b>： 数组中后出现的项直接覆盖先前的项（覆盖即更新；本层不追加、不报重复）。
   */
  static Map<CrisisSignalId, HexCrisisSignal> crisisSignals(JsonNode payload) {
    Map<CrisisSignalId, HexCrisisSignal> signals = new LinkedHashMap<>();
    for (JsonNode node : optionalArray(payload, "crisisSignals")) {
      if (!node.isObject()) {
        throw new IllegalArgumentException("crisisSignals 的每项必须是对象: " + node);
      }
      HexCoord hex = HexCoord.parse(requireText(node, "hex"));
      HexCrisisSignal.Kind kind =
          enumValue(HexCrisisSignal.Kind.class, requireText(node, "kind"), "crisisSignals[].kind");
      CrisisSignalId derived = CrisisSignalId.idOf(hex, kind.name());
      optionalText(node, "id")
          .ifPresent(
              id -> {
                if (!derived.equals(CrisisSignalId.parse(id))) {
                  throw new IllegalArgumentException(
                      "crisisSignals[].id 必须与 (hex, kind) 派生值一致: id=" + id + "，派生=" + derived);
                }
              });
      int severity = requireInt(node, "severity");
      long day = requireLong(node, "day");
      Map<String, Long> evidence = evidence(node);
      List<HouseholdId> households = new ArrayList<>();
      for (JsonNode household : optionalArray(node, "households")) {
        if (!household.isTextual() || household.asText().isBlank()) {
          throw new IllegalArgumentException("crisisSignals[].households 的每项必须是非空字符串: " + node);
        }
        households.add(HouseholdId.parse(household.asText()));
      }
      List<SocialClassId> classes = new ArrayList<>();
      for (JsonNode socialClass : optionalArray(node, "classes")) {
        if (!socialClass.isTextual() || socialClass.asText().isBlank()) {
          throw new IllegalArgumentException("crisisSignals[].classes 的每项必须是非空字符串: " + node);
        }
        classes.add(SocialClassId.parse(socialClass.asText()));
      }
      HexCrisisSignal signal =
          new HexCrisisSignal(
              derived,
              hex,
              kind,
              severity,
              day,
              evidence,
              List.copyOf(households),
              List.copyOf(classes),
              requireText(node, "reason"));
      // ★ 同 hex 同 kind 只保留最新一条：后出现者覆盖先出现者（覆盖即更新，见方法注释与 EconomyData 守卫）。
      signals.put(derived, signal);
    }
    return signals;
  }

  /**
   * ★★ E6a：顶层可选 {@code modeTransitions} 数组（缺键 ⇒ 空表；旧载荷逐值不变）。每项：
   *
   * <pre>{@code
   * {"organizationId":"org-…","fromModeId":"…","toModeId":"…","retainOriginalPerMille":400,
   *  "requestedDay":120,"effectiveDay":121,"status":"PENDING","reason":"…","id":"mt-…"?}
   * }</pre>
   *
   * <p>★ {@code id} 是可选键：给了必须与 {@link ModeTransitionId#idOf(ProductionOrganizationId,
   * ProductionModeId, long)} 一致（不一致 ⇒ 抛）；不给就派生。★ 同一数组里派生 id 重复 ⇒ 抛（同一请求不得写两条）。 组织/mode 引用与"同一组织至多一条
   * PENDING"由 {@code EconomyData} 构造期守卫判。
   */
}
