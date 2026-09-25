package io.mosire.simos.ledger.model;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 账本切片的**激活元信息**（增量 2 spec §3 逐字）：激活日、最后关账日、规则版本、迁移来源。
 *
 * <p>★★ **"未激活"由承载它的 {@code Optional} 表达，不由本类型表达**：{@code LedgerData.economyMeta} 为空 {@code
 * Optional} ⇒ 日制世界尚未激活经济（设计稿 §2/§4：可先沿用简化人口查询，但日期与参数一律按天解释）。 故本类型**没有**"未激活"这一档状态——它一旦存在，就是"已激活"。
 *
 * <p>★ **量纲**：{@code activatedDay}/{@code lastClosedDay} 都是**日**（日制底座 1 tick = 1 天；spec §〇）。
 *
 * <p>★ 不变量（spec §3 只列了账本数据的那些，本类型不额外发明）：{@code mapId}/{@code ruleVersion} 非空白； 两个 {@code Optional*}
 * 组件**不得为 null**（缺席一律用 {@code Optional.empty()}/{@code OptionalLong.empty()}， 照 {@code
 * SocialCity.region} 的形制）。
 *
 * @param mapId 本账本所属地图的 ID（**只存不校验**：地图自己另有身份来源）
 * @param activatedDay 激活日（创世 = 0）
 * @param lastClosedDay 最后关账日；**未关过账 = {@code OptionalLong.empty()}**
 * @param ruleVersion 规则版本（配方/税则的版本标签）
 * @param migrationSource 迁移来源（旧档坐标）；**直接创世 = {@code Optional.empty()}**
 */
public record EconomyMeta(
    String mapId,
    long activatedDay,
    OptionalLong lastClosedDay,
    String ruleVersion,
    Optional<String> migrationSource) {

  public EconomyMeta {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("EconomyMeta.mapId 不得为空白");
    }
    if (ruleVersion == null || ruleVersion.isBlank()) {
      throw new IllegalArgumentException("EconomyMeta.ruleVersion 不得为空白");
    }
    if (lastClosedDay == null) {
      throw new IllegalArgumentException(
          "EconomyMeta.lastClosedDay 不得为 null（没关过账用 OptionalLong.empty()）");
    }
    if (migrationSource == null) {
      throw new IllegalArgumentException(
          "EconomyMeta.migrationSource 不得为 null（直接创世用 Optional.empty()）");
    }
  }
}
