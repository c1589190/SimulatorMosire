package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>模式变迁（{@code ModeTransition}）的稳定身份</b>（理想架构 §2.9/§4.4；E6a）：一次"某生产组织从 fromMode 切到 toMode、在第
 * effectiveDay 生效"的请求。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，不做格式再解释。 ★
 * <b>规范串不含 {@code "."}</b>：地址 {@code economy:<mapId>:modeTransition.<id>} 由 {@code AddressParser}
 * 在第一个点处切段，含点会把名字截断成另一个名字 ⇒ 本类型在构造期当场抛，不静默造一个解析不到的地址。
 *
 * <p>★★ <b>新 id 的唯一拼写点</b> = {@link #idOf(ProductionOrganizationId, ProductionModeId, long)}：
 * {@code mt-<organizationId>-<toModeId>-<effectiveDay>}。它是 {@code (organizationId, toModeId,
 * effectiveDay)} 的纯函数 ⇒ <b>同一请求重复提交得到同一个 id</b>（幂等；禁止随机数/时间戳/UUID）。★ <b>不含 {@code
 * fromModeId}</b>：fromMode 是请求发生时从组织读出的当前 mode，同一组织同一目标 mode 同一生效日就是同一次请求；把它写进 id 只会让同一请求在服务端 mode
 * 已被改写后算出第二个身份。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ModeTransitionId(String value) {

  /** 新 id 的固定前缀（{@link #idOf(ProductionOrganizationId, ProductionModeId, long)} 的唯一拼写点）。 */
  public static final String PREFIX = "mt-";

  public ModeTransitionId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ModeTransitionId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ModeTransitionId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束（新 id 的拼写点属命令层）。 */
  public static ModeTransitionId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ModeTransitionId 不得为空白: " + text);
    }
    return new ModeTransitionId(text);
  }

  /**
   * ★★ <b>新 id 的唯一拼写点</b>：{@code mt-<organizationId>-<toModeId>-<effectiveDay>}（不含 {@code "."}）。
   *
   * @param organizationId 变迁所属的生产组织；不得为 null
   * @param toModeId 目标生产方式；不得为 null
   * @param effectiveDay 生效世界日；不得为负
   */
  public static ModeTransitionId idOf(
      ProductionOrganizationId organizationId, ProductionModeId toModeId, long effectiveDay) {
    if (organizationId == null) {
      throw new IllegalArgumentException("ModeTransitionId.idOf 的 organizationId 不得为 null");
    }
    if (toModeId == null) {
      throw new IllegalArgumentException("ModeTransitionId.idOf 的 toModeId 不得为 null");
    }
    if (effectiveDay < 0L) {
      throw new IllegalArgumentException(
          "ModeTransitionId.idOf 的 effectiveDay 不得为负: " + effectiveDay);
    }
    String value = PREFIX + organizationId.value() + "-" + toModeId.value() + "-" + effectiveDay;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ModeTransitionId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ModeTransitionId(value);
  }
}
