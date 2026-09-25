package io.mosire.simos.economy.api.actor;

import java.util.Arrays;

/**
 * 经济主体的**种类**（设计稿 §2/§4/§5/§7）：人口批次、军事单位、政府、组织者（公司/合作社等）。
 *
 * <p>★ 这是 {@link ActorRef#kind()} 的词表，故意**不**做成引用 {@code UnitId}/{@code GovernmentId} 的 sealed 多例——
 * 那样会把共用契约层绑到各领域模块的内部类型上（设计稿 §2：api 只依赖 util/map）。
 */
public enum ActorKind {
  PEOPLE_LOT,
  UNIT,
  GOVERNMENT,
  ORGANIZATION;

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
