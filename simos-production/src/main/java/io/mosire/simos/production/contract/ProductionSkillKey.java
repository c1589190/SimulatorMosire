package io.mosire.simos.production.contract;

/**
 * ★★ <b>跨地区稳定的生产技能键</b>（D-031 spec §6.1/§6.4；修正③（控制方））：文化与生产加成按它索引，<b>不是</b> {@code IndustryId}。
 *
 * <p>★★ <b>为什么不能拿 {@code IndustryId} 当技能键</b>：{@code IndustryId} 是"一整个产业"的身份（ {@code
 * simos-economy-api/.../id/IndustryId.java}），真实世界里的实例 id 带 hex 语义（{@code farm@0_0}）；拿它当跨地区技能键会让
 * "同在农业这一行"的两个格匹配不上同一条加成规则。本类型是**技能**身份，与地点无关。
 *
 * <p>★ <b>铁律 1 三件套</b>：非空白守卫 + 裸值 {@code toString()} + {@code static parse}（供变更集 String key 与 JSON
 * key 还原）。
 *
 * <p>★ <b>可缺</b>（修正 B）：{@code ProductionSkillLookup.keyOf(IndustryId)} 未命中 ⇒ {@code
 * Optional.empty()}； 调用方照常返回完整真实产业定义，只把文化贡献按中性处理。
 *
 * <p>★ 本轮只立 typed 契约：不因接口先行自动生成传播/升级行为，也不提供"解析产业 id 猜技能"的算法。
 *
 * @param value 非空白的稳定技能键
 */
public record ProductionSkillKey(String value) {

  public ProductionSkillKey {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ProductionSkillKey 不得为空白");
    }
  }

  /** 裸值（见类注：它是身份/地址形式，不是调试输出）。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛（与构造器同口径：宁抛不静默）。 */
  public static ProductionSkillKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionSkillKey 不得为空白: " + text);
    }
    return new ProductionSkillKey(text);
  }
}
