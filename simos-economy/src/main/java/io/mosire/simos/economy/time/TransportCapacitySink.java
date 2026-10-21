package io.mosire.simos.economy.time;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;

/**
 * ★★★ <b>D-032 C6：生产收获把非库存运输服务产出交给 economy 的唯一 sink。</b>
 *
 * <p>production 的运输配方只产出内部服务键 {@code CaravanTransportTemplate.SERVICE_OUTPUT}；该键不是普通商品， 不能 credit 到
 * {@code HouseholdGoods}、不能进入 {@code RoleDistribution}。收获阶段识别它之后，只通过本接口 把 {@code capacityWorkMilli}
 * 交给 economy 的批次簿。
 *
 * <p>契约：实现方必须用 {@code operatorHousehold} 当刻 homeHex 与当前市场区生成批次；operator 户或市场区 解析不到 ⇒
 * fail-closed（具名抛），不得静默丢容量。
 */
@FunctionalInterface
public interface TransportCapacitySink {

  /**
   * 接收一笔运输能力。
   *
   * @param operatorHousehold 运输能力归属的 operator OWNER 家户；不得为 null
   * @param homeHex 生产时该家户的 homeHex；不得为 null
   * @param marketRoundId 本批次将结算的市场日（绝对 tick）
   * @param capacityWorkMilli 本笔运输能力（毫服务单位；必须 ≥ 0）
   */
  void accept(
      HouseholdId operatorHousehold, HexCoord homeHex, long marketRoundId, long capacityWorkMilli);
}
