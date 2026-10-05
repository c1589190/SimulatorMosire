package io.mosire.simos.social.api.id;

import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>政府家户身份的唯一拼写点</b>（P2-C §13.7）：中央/地方 GOV 各恰一个政府家户，其 {@link HouseholdId} 是 <b>GOV 单位稳定 id
 * 的纯函数</b>：{@code hh-gov-<govUnitId>}。
 *
 * <p>★★ <b>为什么必须有它</b>：政府家户的身份不能再由"先到者胜"或"播种顺序"决定。GOV 单位 id 一旦给定（铁律 1：ID 是身份），
 * 它的政府家户身份就唯一确定；重复建户/重复登记会撞上同一把键（具名拒），不会静默分叉成第二个家户。{@code Government.treasury} 必须指向这个家户的账户（{@code
 * HouseholdActors.of(...)}）—— 该不变量由 {@code EconomyData} 与 {@code EconomyRegisterGovernmentHandler}
 * 在状态/命令边界判死。
 *
 * <p>★ <b>它住在 social-api 的理由</b>：unit（{@code Unit.households} 与 {@code GovernmentFormation}）与经济/actor（国库账户）两边都要拼同一个 id，
 * 而两边都只共享 {@code social-api} 这个契约层。字符串格式在这里定死一处，别处不得再拼。
 *
 * <p>★ <b>字符约束（不是洁癖）</b>：{@code |} 与 {@code .} 被明确拒绝 —— {@code HouseholdActors.idOf} 把 {@code |}
 * 换成了 {@code :}（含 {@code |} 的家户 id 无法在 actor 命名空间里往返），而 {@code .} 是经济资源地址 {@code
 * economy:<mapId>:class.<id>} 的切段符。空白（含首尾空白）同样拒绝：身份是稳定串，不做规范化。
 */
public final class GovernmentHouseholds {

  /** 政府家户 id 前缀（唯一拼写点）。 */
  public static final String PREFIX = "hh-gov-";

  private GovernmentHouseholds() {}

  /**
   * GOV 单位稳定 id → 该单位的政府家户稳定身份（{@code hh-gov-<govUnitId>}）。
   *
   * @param govUnitId GOV 单位的稳定 id（非空白；不含 {@code |}/{@code .}；不得带首尾空白）
   * @throws IllegalArgumentException 空白 / 含非法分段符 / 已带前缀
   */
  public static HouseholdId of(String govUnitId) {
    requireReference(govUnitId);
    return new HouseholdId(PREFIX + govUnitId);
  }

  /** 这个家户身份是不是政府家户（前缀判别）。 */
  public static boolean isGovernment(HouseholdId household) {
    Objects.requireNonNull(household, "GovernmentHouseholds.isGovernment 的 household 不得为 null");
    return household.value().startsWith(PREFIX);
  }

  /** 政府家户身份 → 它对应的 GOV 单位稳定 id（{@link #of(String)} 的逆）；非政府家户 ⇒ {@link Optional#empty()}（不猜）。 */
  public static Optional<String> unitRefOf(HouseholdId household) {
    Objects.requireNonNull(household, "GovernmentHouseholds.unitRefOf 的 household 不得为 null");
    if (!isGovernment(household)) {
      return Optional.empty();
    }
    String ref = household.value().substring(PREFIX.length());
    if (ref.isBlank()) {
      // 前缀 + 空白不是本类能造出来的形状；读到它就是坏数据，不猜。
      return Optional.empty();
    }
    return Optional.of(ref);
  }

  /** 共享的引用校验（GOV 单位 id 与 {@code UnitId} 共用同一条"裸值 + 稳定串"纪律）。 */
  private static void requireReference(String govUnitId) {
    if (govUnitId == null || govUnitId.isBlank()) {
      throw new IllegalArgumentException("GovernmentHouseholds.of 的 govUnitId 不得为空白: " + govUnitId);
    }
    if (!govUnitId.equals(govUnitId.trim())) {
      throw new IllegalArgumentException(
          "GovernmentHouseholds.of 的 govUnitId 不得带首尾空白（身份不做规范化）: '" + govUnitId + "'");
    }
    if (govUnitId.startsWith(PREFIX)) {
      throw new IllegalArgumentException(
          "GovernmentHouseholds.of 的 govUnitId 不得已带政府家户前缀 " + PREFIX + ": " + govUnitId);
    }
    if (govUnitId.indexOf('|') >= 0) {
      throw new IllegalArgumentException(
          "GovernmentHouseholds.of 的 govUnitId 不得含 '|'（actor id 会把 '|' 换成 ':'，往返会断）: " + govUnitId);
    }
    if (govUnitId.indexOf('.') >= 0) {
      throw new IllegalArgumentException(
          "GovernmentHouseholds.of 的 govUnitId 不得含 '.'（经济资源地址按点切段）: " + govUnitId);
    }
  }
}
