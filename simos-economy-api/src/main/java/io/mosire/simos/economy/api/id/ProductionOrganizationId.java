package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>生产组织（{@code ProductionOrganization}）的稳定身份</b>（理想架构 §2.4/§3.3；E2）：一个
 * 生产组织回答"按当前生产方式与阶层结构，本期应当存在哪条生产活动、由谁组织、用哪些劳动/资产"。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，不做格式再解释。 ★
 * <b>规范串不含 {@code "."}</b>：地址 {@code economy:<mapId>:productionOrganization.<id>} 由 {@code
 * AddressParser} 在第一个点处切段，含点会把名字截断成另一个名字 ⇒ 本类型在构造期当场抛，不静默造一个解析不到的地址。
 *
 * <p>★★ <b>新 id 的唯一拼写点</b> = {@link #idOf(ProductionModeId, ClassPositionId, HouseholdId, String)}：
 * {@code org-<mode>-<position>-<household>-<q_r>}。它是 {@code (mode, position, household, hex)} 的纯函数
 * ⇒ 同一四元组重放/分支必然得到同一个 id（禁止随机数/时间戳/UUID）。<b>地点进 id</b> 的理由：家户迁移到另一格后， 旧格的组织不能继续冒充新格的组织（旧 unit
 * 留在旧格的产业 id 下），新格因此得到一条新组织记录。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ProductionOrganizationId(String value) {

  public ProductionOrganizationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ProductionOrganizationId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ProductionOrganizationId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束（新 id 的拼写点属命令层）。 */
  public static ProductionOrganizationId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionOrganizationId 不得为空白: " + text);
    }
    return new ProductionOrganizationId(text);
  }

  /**
   * ★★ <b>新 id 的唯一拼写点</b>：{@code org-<mode>-<position>-<household>-<q_r>}（不含 {@code "."}）。
   *
   * @param modeId 本组织所属的生产方式；不得为 null
   * @param positionId 本组织对应的阶层位置；不得为 null
   * @param household 组织者家户；不得为 null
   * @param hexKey 组织所在格的键（{@code q_r}，由 {@code IndustryHexKeys.hexKey} 给出）；非空白
   */
  public static ProductionOrganizationId idOf(
      ProductionModeId modeId, ClassPositionId positionId, HouseholdId household, String hexKey) {
    if (modeId == null) {
      throw new IllegalArgumentException("ProductionOrganizationId.idOf 的 modeId 不得为 null");
    }
    if (positionId == null) {
      throw new IllegalArgumentException("ProductionOrganizationId.idOf 的 positionId 不得为 null");
    }
    if (household == null) {
      throw new IllegalArgumentException("ProductionOrganizationId.idOf 的 household 不得为 null");
    }
    if (hexKey == null || hexKey.isBlank()) {
      throw new IllegalArgumentException("ProductionOrganizationId.idOf 的 hexKey 不得为空白");
    }
    String value =
        "org-" + modeId.value() + "-" + positionId.value() + "-" + household.value() + "-" + hexKey;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ProductionOrganization id 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ProductionOrganizationId(value);
  }
}
