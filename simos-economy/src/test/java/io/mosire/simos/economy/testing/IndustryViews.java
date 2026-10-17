package io.mosire.simos.economy.testing;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.production.contract.AllocationRule;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★ P1 测试共享夹具：把旧 12 参 {@code economy.model.Industry} 模板形状适配成 14 参 {@link
 * IndustryDefinitionView}（技能缺省 = 未绑跨地区技能键；铸币缺省 = 不铸币）。
 *
 * <p>旧类型删于 D-031 P1；本助手只服务既有测试的夹具构造，保持逐值字段不变，不改任何断言。
 */
public final class IndustryViews {

  private IndustryViews() {}

  /** 旧 12 参模板形状。 */
  public static IndustryDefinitionView industry(
      IndustryId id,
      String name,
      RegimeId regime,
      long cycleDays,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
      long dailyLaborPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      List<ClassSlot> slots,
      AllocationRule allocation) {
    return new IndustryDefinitionView(
        id,
        name,
        regime,
        Optional.empty(),
        cycleDays,
        0L,
        capacityPerUnit,
        dailyInputPerUnit,
        dailyLaborPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        allocation,
        MoneyOutput.NONE,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }

  /** 旧 12 参 + 显式铸币产出。 */
  public static IndustryDefinitionView industry(
      IndustryId id,
      String name,
      RegimeId regime,
      long cycleDays,
      Map<AssetKind, Long> capacityPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> dailyInputPerUnit,
      long dailyLaborPerUnit,
      long laborPerUnit,
      Map<CommodityId, Long> outputPerUnit,
      Map<AssetKind, Map<CommodityId, Long>> cycleInputPerUnit,
      List<ClassSlot> slots,
      AllocationRule allocation,
      MoneyOutput moneyOutputPerUnit) {
    return new IndustryDefinitionView(
        id,
        name,
        regime,
        Optional.empty(),
        cycleDays,
        0L,
        capacityPerUnit,
        dailyInputPerUnit,
        dailyLaborPerUnit,
        laborPerUnit,
        outputPerUnit,
        cycleInputPerUnit,
        slots,
        allocation,
        moneyOutputPerUnit,
        ProductionExperienceUse.NONE,
        List.of(),
        Set.of(AssetRightKind.OWNED));
  }
}
