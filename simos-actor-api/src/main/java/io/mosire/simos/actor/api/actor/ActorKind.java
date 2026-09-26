package io.mosire.simos.actor.api.actor;

import java.util.Arrays;

/**
 * 经济主体的**种类**（设计稿 §2/§4/§5/§7 四类 + R2 的**生产关系三类**）：人口批次、军事单位、政府、组织者（公司/合作社等），以及 家户 / 庄园 / 作坊。
 *
 * <p>★ 这是 {@link ActorRef#kind()} 的词表，故意**不**做成引用 {@code UnitId}/{@code GovernmentId} 的 sealed 多例——
 * 那样会把共用契约层绑到各领域模块的内部类型上（设计稿 §2：api 只依赖 util/map）。
 *
 * <p>★★ **R2 追加的后三档**（第三阶段设计稿 §二 的三层拆分：{@code PopulationGroup} 回答"是谁"、{@code EconomicActor}
 * 回答"谁持有"、{@code LaborAllocation} 回答"人与主体是什么关系"）：原四档里**没有**家户/庄园/作坊，而"这批人的劳动给了谁" 必须能指名一个**生产主体** ——
 * 拿 {@code ORGANIZATION} 顶替等于把"制度"这一维抹掉（庄园 ≠ 作坊 ≠ 家户，三者的劳动义务与产出归属都不同）。
 *
 * <p>★ **本轮的用法**（R2 = 劳动底座，尚无 {@code EconomicActor} 的完整类型）：
 *
 * <ul>
 *   <li>{@link #ESTATE} / {@link #WORKSHOP} —— **产业**的两种制度身份（农业 = 庄园、手工业 = 作坊）：创世时每格按"农村批次 → 庄园 /
 *       城镇批次 → 作坊"发放配额，{@code actor.id()} 就是该产业的 id（{@code farm@0_0}）。设计稿 §十.6 的 actor 粒度 = **格 ×
 *       制度**，在本轮退化为"每格每产业一个主体"（每格每制度恰有一个产业）；
 *   <li>{@link #HOUSEHOLD} —— 家户（自给自足的家庭经济单位）：本轮**只由夹具**使用（"同一批人农闲织布"那条压力测试）；它不属任何产业，
 *       故在结算里**不占任何产业的劳动投入**，但照样进守恒（{@code Σ allocated ≤ available}）与读口。 ★ 真正让它产出布的配方（{@code FIBER
 *       + LABOR + TOOL → CLOTH}）属 R3 的 V7 —— 本轮不给它产出，也**不**假装给了。
 * </ul>
 *
 * <p>★ **词表只认种类，不认粒度**：粒度的判据（每格每制度一个聚合主体）在产出方（{@code EconomicActor}，spec §十.6），
 * 不在本枚举——把"每格几个"写进词表会把制度与地理两件事混成一件。
 */
public enum ActorKind {
  PEOPLE_LOT,
  UNIT,
  GOVERNMENT,
  ORGANIZATION,

  /** 家户（自给自足的家庭经济单位：农闲织布一类；R3 的落点）。 */
  HOUSEHOLD,

  /** 庄园（封建制度下的农业生产主体）。 */
  ESTATE,

  /** 作坊（手工业的生产主体）。 */
  WORKSHOP;

  /**
   * 按词表解析（设计稿 §2 的四类出处：PeopleLot / 单位生产 / 政府 / "组织者"）。
   *
   * <p>词表外的输入即抛，消息里列出全部合法值——静默返回 {@code null} 或默认值都会让"写错主体种类"变成运行时幽灵。
   */
  public static ActorKind parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ActorKind 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 ActorKind: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}
