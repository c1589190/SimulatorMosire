package io.mosire.simos.app.household;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>家户位置的唯一解析落点</b>（2026-10-09 唯一列表裁定）：{@code HouseholdLocation.UNIT(unitId)} → 该 unit 当刻
 * {@link UnitState#effectivePosition} 的 hex；{@code HEX} 原样返回。
 *
 * <p>★★ <b>为什么必须有它</b>：政府家户（以及 {@code unit.RaiseUnit} 造的小家户）的位置不再是创建时钉死的 HEX，而是 {@code
 * UNIT(unitId)}。{@code unit.PlaceAt} / 行军 / 迁都只改 unit 的位置，家户的“有效 hex”必须由 resolver 现算——
 * 否则政府家户的国库/入市/生产组织会停在旧格。{@code HouseholdAccountKey} 早已只按 {@code HouseholdId}（P2-A §13.3）⇒ 账户键不因此变。
 *
 * <p>★★ <b>谁必须走它</b>（本批接线）：
 *
 * <ul>
 *   <li>{@link #alignHouseholdEconomyViews}：经济侧 {@code HouseholdEconomy.view.hex} 是市场参与 / 生产组织 /
 *       贷款等读位置的载体； UNIT 家户的行视图由 unit 当刻位置刷新（HEX 家户原样不动），于是 economy 的既有 {@code view().hex()} 读点全部跟随；
 *   <li>{@link #hexOfLot}：app 层“批次在哪一格”的读点（逐日生计满足率）；
 *   <li>{@link #effectiveHex}：读口/汇总（如区域账本汇总）取家户有效格。
 * </ul>
 *
 * <p>★ <b>只读 + 纯函数</b>：不抛“查无此人”的模糊错——{@code UNIT} 单位不存在/无位置时返回 {@link Optional#empty()}； 调用方按各自口径
 * fail-closed（一致性校核另在 {@link HouseholdUnitConsistency} / {@link GovernmentHouseholdWiring}）。 ★
 * {@link #alignHouseholdEconomyViews} 对“有流水行”的家户只在目标格有产业登记（或经济里存在无格键产业）时才动车—— {@code EconomyData}
 * 构造期要求有流水的家户视图落点有产业登记；移不动的留在原格，属本批具名缺口（见报告）。
 */
public final class HouseholdPositionResolver {

  private HouseholdPositionResolver() {}

  /** 一次 class row 视图对齐的结果：{@code data} = 对齐后的经济状态（无变化时原样返回入参）；{@code moved} = 移动的行数。 */
  public record Alignment(EconomyData data, int moved) {

    public Alignment {
      Objects.requireNonNull(data, "data");
      if (moved < 0) {
        throw new IllegalArgumentException("moved 不得为负: " + moved);
      }
    }
  }

  /** 家户位置的当刻有效格：{@code HEX} 原样；{@code UNIT} → 该 unit 在 {@code at} 的有效位置；unit 不存在/无位置 ⇒ 空。 */
  public static Optional<HexCoord> resolve(
      HouseholdLocation location, UnitState units, SimosTimestamp at) {
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(at, "at");
    if (location instanceof HouseholdLocation.Hex hex) {
      return Optional.of(hex.hex());
    }
    if (location instanceof HouseholdLocation.Unit unit) {
      return units.effectivePosition(UnitId.parse(unit.unitId()), at);
    }
    throw new IllegalStateException("未知 HouseholdLocation 实现: " + location.getClass().getName());
  }

  /** 某家户在 {@code at} 的有效格（家户不存在 ⇒ 空）。 */
  public static Optional<HexCoord> effectiveHex(
      HouseholdId household, SocialData social, UnitState units, SimosTimestamp at) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(social, "social");
    Household holder = social.households().get(household);
    return holder == null ? Optional.empty() : resolve(holder.location(), units, at);
  }

  /** 某人口批次在 {@code at} 的有效格（先经所属家户，再按位置档解析；批次/家户不存在 ⇒ 空）。 */
  public static Optional<HexCoord> hexOfLot(
      PeopleLotId lot, SocialData social, UnitState units, SimosTimestamp at) {
    Objects.requireNonNull(lot, "lot");
    Objects.requireNonNull(social, "social");
    return social.householdOfLot(lot).flatMap(holder -> resolve(holder.location(), units, at));
  }

  /**
   * ★★ <b>把 UNIT 家户的经济行视图对齐到 unit 当刻位置</b>：逐家户（按 {@link HouseholdId#value()} 稳定序）找 {@code
   * economy.classes} 的同 id 行；位置是 {@code UNIT(u)}、解析出的 hex 与行视图不同时，只改 {@code view.hex}（{@code
   * residence}/{@code stratum} 与其余字段原样）。有流水行的家户还要求目标格具备产业登记 （构造期约束）；HEX 家户、无经济行的家户、移不动的家户一律不动 ⇒
   * 无变化时返回入参本身（零变更）。
   *
   * <p>★ 目标格的产业登记判据与 {@code EconomyData} 构造期 {@code requireIndustryRegistered} 同源：存在无格键产业 ⇒
   * 任何格都算已登记；否则该格必须恰有一个产业 id 的格键逐字相等。
   */
  public static Alignment alignHouseholdEconomyViews(
      EconomyData economy, SocialData social, UnitState units, SimosTimestamp at) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(at, "at");
    List<Household> ordered = new ArrayList<>(social.households().values());
    ordered.sort(Comparator.comparing(household -> household.id().value()));
    Map<HouseholdId, HouseholdEconomy> nextHouseholdEconomies = null;
    int moved = 0;
    for (Household household : ordered) {
      if (!(household.location() instanceof HouseholdLocation.Unit)) {
        continue;
      }
      HouseholdEconomy householdEconomy = economy.classes().get(household.id());
      if (householdEconomy == null) {
        continue;
      }
      Optional<HexCoord> atHex = resolve(household.location(), units, at);
      if (atHex.isEmpty() || atHex.get().equals(householdEconomy.view().hex())) {
        continue;
      }
      if (economy.flows().containsKey(household.id())
          && !hexHasRegisteredIndustry(economy, atHex.get())) {
        continue; // 该家户有流水行 ⇒ EconomyData 构造期要求其视图落点有产业登记；移不动则留在原格（具名缺口）。
      }
      if (nextHouseholdEconomies == null) {
        nextHouseholdEconomies = new LinkedHashMap<>(economy.classes());
      }
      nextHouseholdEconomies.put(
          household.id(),
          householdEconomy.withView(
              new CohortKey(
                  atHex.get(),
                  householdEconomy.view().residence(),
                  householdEconomy.view().stratum())));
      moved++;
    }
    if (nextHouseholdEconomies == null) {
      return new Alignment(economy, 0);
    }
    return new Alignment(economy.withHouseholdEconomies(nextHouseholdEconomies), moved);
  }

  /** 目标格是否有产业登记（判据与 {@code EconomyData} 构造期同源；见 {@link #alignHouseholdEconomyViews}）。 */
  private static boolean hexHasRegisteredIndustry(EconomyData economy, HexCoord hex) {
    String target = IndustryHexKeys.hexKey(hex.q(), hex.r());
    for (IndustryId id : economy.industries().keySet()) {
      Optional<String> key = IndustryHexKeys.hexKeyOf(id);
      if (key.isEmpty() || key.get().equals(target)) {
        return true;
      }
    }
    return false;
  }
}
