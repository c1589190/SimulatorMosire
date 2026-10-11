package io.mosire.simos.gov.tools;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.util.state.SimulationState;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 政府工具面的**人力口**：选人瀑布（"从哪些家户抽哪几个人"）与家户劳动量读数。
 *
 * <p>★★ <b>为什么必须注入、不许在域内重写</b>：选人的唯一实现在 app 的 {@code HouseholdManpowerAllocator}（六条调用链共用， 含
 * share-aware 瀑布、{@code MALE+ADULT} 口径、候选取序与"整条拒、不部分抽取"的失败语义），而它的签名要吃 {@code CalendarClock}（{@code
 * simos-calendar} 不在 gov 允许面）⇒ 时钟住在 app 适配器里，域侧只看见"给我 count 个人"。 先例：第五域 unit 的 {@code
 * UnitToolAllocations}（同一形制、同一理由）。
 *
 * <p>★ <b>返回的是**镜像记录**</b>（{@code requested/available/shares} + {@code shares} 的四字段）：gov
 * 侧要用它拼工单与视图， 而 app 侧的 {@code Allocation}/{@code ManpowerShare} 是包内类型 ⇒ 由适配器逐字段搬运（值逐字相同）。
 */
public interface GovToolManpower {

  /**
   * 辖区 hex 扫描 + {@code MALE + ADULT} 口径的选人（{@code
   * HouseholdManpowerAllocator.allocateMalesOfAdult}）。
   *
   * @throws IllegalArgumentException 参数校验失败或合格份额总量不足（整条拒，不部分抽取；文案逐字不变）
   * @throws IllegalStateException SocialData 不变量已破坏（同既有实现）
   */
  Allocation allocateMalesOfAdult(
      SimulationState state,
      List<Set<HexCoord>> jurisdictionHexesInOrder,
      long requested,
      long tick,
      Set<HouseholdId> excludedHouseholds);

  /** 单个家户 + 可选性别/年龄过滤的选人（{@code allocateFromHousehold}；{@code Optional.empty()} = 不限）。 */
  Allocation allocateFromHousehold(
      SimulationState state,
      HouseholdId householdId,
      long requested,
      long tick,
      Optional<Sex> sexFilter,
      Optional<AgeBracket> ageBracketFilter);

  /** 指定家户集合 + 可选过滤 + 额外排除的选人（{@code allocateFromHouseholds}）。 */
  Allocation allocateFromHouseholds(
      SimulationState state,
      Set<HouseholdId> householdIds,
      long requested,
      long tick,
      Optional<Sex> sexFilter,
      Optional<AgeBracket> ageBracketFilter,
      Set<HouseholdId> excludedHouseholds);

  /** 家户当刻劳动量（毫小时；{@code social.householdLaborMilli} + 组合根的历法时钟）。 */
  long householdLaborMilli(SocialData social, HouseholdId household, long tick);

  /**
   * 同上，但直接吃状态（按 {@link GovToolReads#socialData} 的逐字口径取 social 切片）。
   *
   * <p>★ 两种入口都保留：{@code GovExpandHouseholdTool} 要算的是**投影后**的 SocialData（工单应用后的世界），不是状态里的那一片。
   */
  default long householdLaborMilli(SimulationState state, HouseholdId household, long tick) {
    return householdLaborMilli(GovToolReads.socialData(state), household, tick);
  }

  /** {@code HouseholdManpowerAllocator.Allocation} 的镜像（三个字段一个不少）。 */
  record Allocation(long requested, long available, List<ManpowerShare> shares) {

    public Allocation {
      Objects.requireNonNull(shares, "shares");
      shares = Collections.unmodifiableList(new java.util.ArrayList<>(shares));
    }
  }

  /**
   * {@code HouseholdManpowerAllocator.ManpowerShare} 的镜像。
   *
   * <p>★ {@code hex} 允许为 null（只有 HEX 家户的来源才有格；UNIT 家户没有格，不伪造位置）——{@link #hexOptional()} 是既有读法。
   */
  record ManpowerShare(HouseholdId householdId, PeopleLotId lotId, HexCoord hex, long taken) {

    public ManpowerShare {
      Objects.requireNonNull(householdId, "householdId");
      Objects.requireNonNull(lotId, "lotId");
    }

    /** 来源格（{@code HEX} 家户才有）；{@code UNIT} 家户没有格 ⇒ {@link Optional#empty()}。 */
    public Optional<HexCoord> hexOptional() {
      return Optional.ofNullable(hex);
    }
  }
}
