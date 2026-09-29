package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>阶层保留份额（{@code ClassShare}）的稳定身份</b>（理想架构 §2.9/§7.2；E6a）：一次模式变迁里某个家户在某个阶层位置上 的保留千分比记录。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，不做格式再解释。 ★
 * <b>规范串不含 {@code "."}</b>：地址 {@code economy:<mapId>:classShare.<id>} 由 {@code AddressParser}
 * 在第一个点处切段，含点会把名字截断成另一个名字 ⇒ 本类型在构造期当场抛。
 *
 * <p>★★ <b>新 id 的唯一拼写点</b> = {@link #idOf(ModeTransitionId, HouseholdId, ClassPositionId)}： {@code
 * cs-<transitionId>-<householdId>-<classPositionId>}。它是 {@code (transitionId, householdId,
 * classPositionId)} 的纯函数 ⇒ 同一变迁同一家户同一位置重放/重跑得到同一个 id（禁止随机数/时间戳/UUID）。
 *
 * <p>★ <b>为什么 position 段在 id 里</b>：一份变迁对一个家户可以同时有"保留原位置 X‰"与"迁入新位置 (1000−X)‰"两条记录； 位置身份是区分它们的那一维。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ClassShareId(String value) {

  /** 新 id 的固定前缀（{@link #idOf(ModeTransitionId, HouseholdId, ClassPositionId)} 的唯一拼写点）。 */
  public static final String PREFIX = "cs-";

  public ClassShareId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ClassShareId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassShareId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束（新 id 的拼写点属命令层）。 */
  public static ClassShareId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClassShareId 不得为空白: " + text);
    }
    return new ClassShareId(text);
  }

  /**
   * ★★ <b>新 id 的唯一拼写点</b>：{@code cs-<transitionId>-<householdId>-<classPositionId>}（不含 {@code
   * "."}）。
   *
   * @param transitionId 所属模式变迁；不得为 null
   * @param householdId 家户稳定身份；不得为 null
   * @param classPositionId 该份额对应的阶层位置；不得为 null
   */
  public static ClassShareId idOf(
      ModeTransitionId transitionId, HouseholdId householdId, ClassPositionId classPositionId) {
    if (transitionId == null) {
      throw new IllegalArgumentException("ClassShareId.idOf 的 transitionId 不得为 null");
    }
    if (householdId == null) {
      throw new IllegalArgumentException("ClassShareId.idOf 的 householdId 不得为 null");
    }
    if (classPositionId == null) {
      throw new IllegalArgumentException("ClassShareId.idOf 的 classPositionId 不得为 null");
    }
    String value =
        PREFIX + transitionId.value() + "-" + householdId.value() + "-" + classPositionId.value();
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassShareId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ClassShareId(value);
  }
}
