package io.mosire.simos.economy.api.id;

import io.mosire.simos.actor.api.actor.ActorRef;

/**
 * 生产单元 ID（设计稿 §4.2；R3B.2 起真正被 {@code ProductionProcess} 使用）：一次单位生产的稳定身份，归 {@code production} 切片；不复用军事
 * {@code Unit} 的 ID。
 *
 * <p>裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。★ {@code parse} 是
 * <b>opaque</b> 的：旧档里已经落盘的 id 原样读回，不因本类新增工厂而重算/改写。
 *
 * <p>★★ <b>新 id 的唯一拼写点</b> = {@link #idOf(IndustryId, ActorRef)}：{@code
 * unit-<industry>-<operator.kind>-<operator.id>}。它是 {@code (产业, 经营者)} 的纯函数 ⇒ 同一对主体重放/分支必然得到同一个 id
 * （禁止随机数/时间戳）。
 */
public record ProductionUnitId(String value) {

  public ProductionUnitId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ProductionUnitId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static ProductionUnitId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionUnitId 不得为空白: " + text);
    }
    return new ProductionUnitId(text);
  }

  /**
   * ★★ <b>新 id 的唯一拼写点</b>：{@code unit-<industry>-<operator.kind>-<operator.id>}（不含 {@code "."}）。
   *
   * <p>★ <b>为什么在契约层</b>：{@code EconomyPayloads}（播种）与 {@code EconomyCodec}（旧档 reshape）都要生成默认
   * unit，两处各拼一遍必然漂开 ⇒ 同一 {@code (industry, operator)} 会得到两个"身份"。
   *
   * <p>★ <b>不含 {@code "."}</b>：地址 {@code economy:<mapId>:productionProcess.<id>} 会被 {@code
   * AddressParser} 在第一个点处截断；含点 ⇒ 当场抛，不静默造一个解析不到的地址。
   *
   * @param industry 技术模板身份；不得为 null（它的 id 里已带格键，unit 的地点由它回答）
   * @param operator 实际经营者；不得为 null
   */
  public static ProductionUnitId idOf(IndustryId industry, ActorRef operator) {
    if (industry == null) {
      throw new IllegalArgumentException("ProductionUnitId.idOf 的 industry 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("ProductionUnitId.idOf 的 operator 不得为 null");
    }
    String value = "unit-" + industry.value() + "-" + operator.kind() + "-" + operator.id();
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ProductionProcess id 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ProductionUnitId(value);
  }
}
