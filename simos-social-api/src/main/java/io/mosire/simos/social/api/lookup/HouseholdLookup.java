package io.mosire.simos.social.api.lookup;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.household.HouseholdView;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.List;
import java.util.Optional;

/**
 * 家户只读查询 SPI（2026-10-09 家户/人口架构 §3.1 / §5）：Unit/Gov/Economy/Culture/Religion 只依赖本接口， 不依赖 {@code
 * simos-social} 的状态本体。
 *
 * <p>★ 三个查询都是只读、无副作用；查不到时 {@link #household(HouseholdId)} 返回 {@link Optional#empty()}，
 * 两个列表查询返回空表（不返回 null）。
 */
public interface HouseholdLookup {

  /** 按稳定身份查家户；不存在 ⇒ {@link Optional#empty()}。 */
  Optional<HouseholdView> household(HouseholdId householdId);

  /** 某一格上的全部家户（空 ⇒ 空表）。 */
  List<HouseholdView> at(HexCoord hex);

  /** 某个 unit 上的全部家户（空 ⇒ 空表）。 */
  List<HouseholdView> inUnit(String unitId);
}
