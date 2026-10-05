package io.mosire.simos.economy.api.id;

import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>按 GOV 单位稳定 id 派生的政府身份拼写点</b>（P2-C §13.7）：一条 {@link Government} 记录对应一个 GOV 单位时， 其 id 是
 * {@code gov-unit-<govUnitId>}（GOV 单位 id 的纯函数），国库家户身份是 {@code GovernmentHouseholds.of(govUnitId)}。
 *
 * <p>★★ <b>为什么用派生 id 而不是"先到者胜"</b>：{@code governments} 是 {@code Map<GovernmentId, Government>}。若两条
 * GOV 单位的政府记录可以任意取 id，后建的省 GOV 要么覆盖、要么被第一批挡住；采用派生 id 后，"一个单位恰一份政府记录"由 <b>键</b>保证 —— 重复登记是 upsert
 * 到同一把键（幂等），两个不同单位不可能共用一条记录。
 *
 * <p>★ <b>世界级政府不在此列</b>：{@code world-silver} 之类的发行主体不是任何 GOV 单位，{@link #unitRefOf(GovernmentId)}
 * 对它返回空（不猜）。
 *
 * <p>★ <b>字符约束与 {@code GovernmentHouseholds} 同款</b>：不含 {@code |}（编码/账户分段符）与 {@code .}（经济资源地址切段符），
 * 且不做首尾空白规范化。两处若不同步，身份会漂开，故本类与 {@code GovernmentHouseholds} 逐条对齐。
 */
public final class GovernmentIds {

  /** 单位政府 id 前缀（唯一拼写点）。 */
  public static final String UNIT_PREFIX = "gov-unit-";

  private GovernmentIds() {}

  /**
   * GOV 单位稳定 id → 该单位的政府记录身份（{@code gov-unit-<govUnitId>}）。
   *
   * @throws IllegalArgumentException 空白 / 含非法分段符 / 已带前缀
   */
  public static GovernmentId ofUnit(String govUnitId) {
    requireUnitRef(govUnitId);
    return new GovernmentId(UNIT_PREFIX + govUnitId);
  }

  /** 这条政府记录是不是"某个 GOV 单位的政府"。 */
  public static boolean isUnitGovernment(GovernmentId governmentId) {
    Objects.requireNonNull(governmentId, "GovernmentIds.isUnitGovernment 的 governmentId 不得为 null");
    return governmentId.value().startsWith(UNIT_PREFIX);
  }

  /** 单位政府记录 → 它对应的 GOV 单位稳定 id；世界级政府 ⇒ {@link Optional#empty()}（不猜）。 */
  public static Optional<String> unitRefOf(GovernmentId governmentId) {
    Objects.requireNonNull(governmentId, "GovernmentIds.unitRefOf 的 governmentId 不得为 null");
    if (!isUnitGovernment(governmentId)) {
      return Optional.empty();
    }
    String ref = governmentId.value().substring(UNIT_PREFIX.length());
    if (ref.isBlank()) {
      throw new IllegalArgumentException(
          "政府 id 带 " + UNIT_PREFIX + " 前缀却无单位引用（坏数据，拒绝解释）: " + governmentId.value());
    }
    return Optional.of(ref);
  }

  /** 与 {@code GovernmentHouseholds} 共享的引用校验口径（两处必须逐条一致，否则同一单位的两把键会漂开）。 */
  private static void requireUnitRef(String govUnitId) {
    if (govUnitId == null || govUnitId.isBlank()) {
      throw new IllegalArgumentException("GovernmentIds.ofUnit 的 govUnitId 不得为空白: " + govUnitId);
    }
    if (!govUnitId.equals(govUnitId.trim())) {
      throw new IllegalArgumentException(
          "GovernmentIds.ofUnit 的 govUnitId 不得带首尾空白（身份不做规范化）: '" + govUnitId + "'");
    }
    if (govUnitId.startsWith(UNIT_PREFIX)) {
      throw new IllegalArgumentException(
          "GovernmentIds.ofUnit 的 govUnitId 不得已带单位政府前缀 " + UNIT_PREFIX + ": " + govUnitId);
    }
    if (govUnitId.indexOf('|') >= 0) {
      throw new IllegalArgumentException(
          "GovernmentIds.ofUnit 的 govUnitId 不得含 '|'（编码/账户分段符）: " + govUnitId);
    }
    if (govUnitId.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "GovernmentIds.ofUnit 的 govUnitId 不得含 '.'（经济资源地址按点切段）: " + govUnitId);
    }
  }
}
