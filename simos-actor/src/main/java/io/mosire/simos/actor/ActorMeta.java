package io.mosire.simos.actor;

/**
 * actor 切片的<b>激活元信息</b>（照 {@code EconomyMeta} 的<b>形制</b>，不是照抄它的字段集）。
 *
 * <p>★★ <b>"未激活"由承载它的 {@code Optional} 表达，不由本类型表达</b>（同 {@code EconomyMeta} 的口径）：{@code
 * ActorData.meta} 为空 {@code Optional} ⇒ 本世界尚未落 actor 切片。故本类型<b>没有</b>"未激活"这一档状态 —— 它一旦存在，就是"已激活"。
 *
 * <p>★★ <b>为什么只有三个组件</b>（控制方 2026-09-26 裁定）：{@code EconomyMeta} 有五件，逐件对照后：
 *
 * <ul>
 *   <li>{@code lastClosedCycle}（最后关账的<b>产业周期</b>序号）—— <b>不要</b>：那是产业周期量纲，actor 切片没有周期；
 *   <li>{@code migrationSource}（迁移来源）—— <b>不要</b>：{@code EconomyMeta} 有它是因为<b>旧的经济载荷格式真的存在过</b>；而
 *       spec §十.4 明令"旧档重建也没关系、不做迁移工具" ⇒ 本切片的这个字段<b>永远只会是空</b>。**不加一个永远为空的字段**（与本阶段 R-e / R-h / R-j
 *       的口径一致：不声明用不到的依赖、不提前加还不存在的表）。
 * </ul>
 *
 * <p>★ <b>量纲</b>：{@code activatedDay} 是<b>日</b>（日制底座 1 tick = 1 天）。
 *
 * @param mapId 本切片所属地图的 ID（<b>只存不校验</b>：地图自己另有身份来源）
 * @param activatedDay 激活日（创世 = 0）
 * @param rulesVersion 规则版本（本切片规则的版本标签）
 */
public record ActorMeta(String mapId, long activatedDay, String rulesVersion) {

  public ActorMeta {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("ActorMeta.mapId 不得为空白");
    }
    if (activatedDay < 0) {
      throw new IllegalArgumentException("ActorMeta.activatedDay 不得为负: " + activatedDay);
    }
    if (rulesVersion == null || rulesVersion.isBlank()) {
      throw new IllegalArgumentException("ActorMeta.rulesVersion 不得为空白");
    }
  }
}
