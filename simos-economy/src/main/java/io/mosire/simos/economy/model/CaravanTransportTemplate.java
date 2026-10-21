package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IntraRoleSplitRule;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionAvailabilityCondition;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.contract.RoleShareRemainder;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>D-032 C5b：商队运输生产方式/角色/配方的全仓唯一拼写点。</b>
 *
 * <p>本模板只建立运输生产骨架，不 seed 生产单元/名册、不写马匹占用、不创建每 tick 批次、不接市场或价格。 运行期消费方必须只读本类：
 *
 * <ul>
 *   <li>C6 只读 {@link #SERVICE_OUTPUT} 与 {@link #industry(HexCoord)} 的配方，完成 non-inventory 服务产出路由；
 *   <li>C9 只读 {@link #industry(HexCoord)} 与 {@link #ROLES} seed unit/roster，不得复制配方或角色表。
 * </ul>
 *
 * <p>材料通道：运输马匹<b>只</b>进 {@code durableCommodityCapacityPerUnit} 作为容量锚，<b>不</b>进 {@code
 * inputPerUnit}、不按趟扣减，也不建 {@code AssetKind.HORSE}/{@code OwnershipStake} 第二本账。一个家户的 {@code
 * transport_horse} 库存是唯一数量权威。
 *
 * <p>服务通道：{@link #SERVICE_OUTPUT} 是内部服务键，不属于 {@link EconomyVocabulary#allCommodityIds()}，不得进入普通
 * 市场价表或 {@code HouseholdInventory}；C6 负责把它从生产回执转成 economy 的运输能力批次。
 *
 * <p>★ 所有值都是本类内的工程常量（H/L/C 各只出现一次）；静态方法均为纯函数，不读状态、不写状态、无随机/时钟依赖。
 */
public final class CaravanTransportTemplate {

  private CaravanTransportTemplate() {}

  /** 产业 kind（{@code IndustryHexKeys} 的 kind 维；每 hex 的产业 id 形如 {@code caravan_transport@q_r}）。 */
  public static final String KIND = "caravan_transport";

  /** 展示名。 */
  public static final String DISPLAY_NAME = "商队运输";

  /** 制度：复用既有 merchant 制度；不新增第二套商人身份。 */
  public static final RegimeId REGIME = new RegimeId(RegimeOperators.MERCHANT);

  /** 运输生产周期（天）：每 tick 可组织。 */
  public static final long CYCLE_DAYS = 1L;

  /**
   * 每 1 单位运输规模占用的运输马匹数量（毫商品单位）。
   *
   * <p>1 匹马 = {@link EconomyVocabulary#MILLI_PER_COMMODITY_UNIT} 毫；本值只作 durable 容量锚，不消耗。
   */
  public static final long HORSE_MILLI_PER_SCALE_UNIT = EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;

  /** 每 1 单位运输规模需要的劳动（毫小时/周期）。 */
  public static final long LABOR_MILLI_PER_SCALE_UNIT = 1_000L;

  /** 每 1 单位运输规模产出的内部运输服务量（毫服务单位/周期；C6 按同一量纲折成 workMilli）。 */
  public static final long SERVICE_MILLI_PER_SCALE_UNIT = 1_000L;

  /** 运输马匹商品 id（唯一拼写点仍是 {@link EconomyVocabulary#TRANSPORT_HORSE_COMMODITY_ID}）。 */
  public static final CommodityId TRANSPORT_HORSE =
      new CommodityId(EconomyVocabulary.TRANSPORT_HORSE_COMMODITY_ID);

  /**
   * 内部运输能力服务输出键的字面量（唯一拼写点）。
   *
   * <p>它<b>不是</b>普通商品：不加入 {@link EconomyVocabulary#allCommodityIds()}，不进入普通价表/库存/订单簿。
   */
  public static final String SERVICE_OUTPUT_ID = "transport_capacity_service";

  /**
   * C6 唯一可识别的运输能力服务输出键。
   *
   * <p>键类型沿用生产契约现有的 {@link CommodityId} 形状，但语义是 production/economy 的内部 non-inventory 服务键；
   * 普通商品词表不登记它。C6 只认这个常量，不得另写字面量。
   */
  public static final CommodityId SERVICE_OUTPUT = new CommodityId(SERVICE_OUTPUT_ID);

  /** C9 唯一可读的两个运输角色定义；id 与本类 {@link #industry(HexCoord)} 的 mode 位置一一对应。 */
  public static final List<ProductionRoleDefinition> ROLES =
      List.of(
          new ProductionRoleDefinition(
              requiredRoleId(DefaultProductionModes.ROLE_CARAVAN_OPERATOR),
              "商队经营者",
              Optional.empty(),
              1_000L,
              IntraRoleSplitRule.EQUAL,
              0L,
              Optional.empty(),
              List.of(),
              new RolePayment.ProductShare()),
          new ProductionRoleDefinition(
              requiredRoleId(DefaultProductionModes.ROLE_HIRED_DRIVER),
              "受雇车夫",
              Optional.empty(),
              0L,
              IntraRoleSplitRule.EQUAL,
              LABOR_MILLI_PER_SCALE_UNIT,
              Optional.empty(),
              List.of(),
              new RolePayment.MoneyWage()));

  /** 每 hex 的稳定运输产业 id：{@code caravan_transport@q_r}。 */
  public static IndustryId industryId(HexCoord hex) {
    if (hex == null) {
      throw new IllegalArgumentException("CaravanTransportTemplate.industryId 的 hex 不得为 null");
    }
    return IndustryHexKeys.id(KIND, hex.q(), hex.r());
  }

  /**
   * 每 hex 的运输生产方案定义（纯函数；同一 hex 始终返回逐字段同值的新不可变记录）。
   *
   * <pre>
   * mode = MERCHANT
   * cycleDays = 1
   * capacityPerUnit = {}
   * durableCommodityCapacityPerUnit = {transport_horse: HORSE_MILLI_PER_SCALE_UNIT}
   * inputPerUnit = {}
   * laborPerUnit = LABOR_MILLI_PER_SCALE_UNIT
   * outputPerUnit = {SERVICE_OUTPUT: SERVICE_MILLI_PER_SCALE_UNIT}
   * roles = ROLES
   * </pre>
   */
  public static IndustryDefinitionView industry(HexCoord hex) {
    Map<CommodityId, Long> outputPerUnit = new LinkedHashMap<>();
    outputPerUnit.put(SERVICE_OUTPUT, SERVICE_MILLI_PER_SCALE_UNIT);

    Map<CommodityId, Long> durableCommodityCapacityPerUnit = new LinkedHashMap<>();
    durableCommodityCapacityPerUnit.put(TRANSPORT_HORSE, HORSE_MILLI_PER_SCALE_UNIT);

    return new IndustryDefinitionView(
        industryId(hex),
        DISPLAY_NAME,
        REGIME,
        Optional.empty(),
        CYCLE_DAYS,
        0L,
        Map.<AssetKind, Long>of(),
        Map.<AssetKind, Map<CommodityId, Long>>of(),
        0L,
        LABOR_MILLI_PER_SCALE_UNIT,
        outputPerUnit,
        Map.<AssetKind, Map<CommodityId, Long>>of(),
        IndustryClassSlots.slots(),
        new AllocationRule.Split(1_000, 0),
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.<ProductionAvailabilityCondition>of(),
        Set.<AssetRightKind>of(),
        ROLES,
        RoleShareRemainder.UNALLOCATED,
        List.of(),
        Optional.of(DefaultProductionModes.MERCHANT),
        durableCommodityCapacityPerUnit);
  }

  /** 该键是否是模板声明的内部运输能力服务输出（C6 路由的唯一判据拼写点）。 */
  public static boolean isServiceOutput(CommodityId commodityId) {
    return SERVICE_OUTPUT.equals(commodityId);
  }

  /** mode 位置 id 必须存在；目录与模板漂开时具名失败，不猜第二个 id。 */
  private static ProductionRoleId requiredRoleId(String role) {
    return DefaultProductionModes.positionId(DefaultProductionModes.MERCHANT, role)
        .map(ClassPositionId -> ProductionRoleId.parse(ClassPositionId.value()))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "CaravanTransportTemplate 缺少 merchant 位置：role="
                        + role
                        + "（DefaultProductionModes 与运输模板漂开）"));
  }
}
