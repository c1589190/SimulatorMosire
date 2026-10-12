package io.mosire.simos.app.testing;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>2026-10-13 收尾测试第 1 层（机械修复）新增</b>：三个切片的载体。
 *
 * <p><b>为什么需要它</b>：2026-10-12 架构轮（批次 3c/3d-1/乙收尾）之后，原先同住 {@code SdState}/{@code GaState} 的三张表
 * <b>各归其主</b>——国家（{@code nations}）住 {@code gov} 切片、军队身份（{@code armies}）住 {@code army} 切片、决策人（{@code
 * decisionMakers}）住 {@code ga} 切片。测试夹具里"造一个小世界"的辅助方法原本一次返回一个 {@code GaState}（那时国家与军队还在里面），
 * 现在必须一次交回三片。
 *
 * <p>★ 这<b>不是新用例</b>：它只是把被架构轮拆散的三片重新打成一个包，让既有夹具的返回值有地方放（否则夹具体内的国家/军队会被静默丢弃）。
 *
 * <p>★ <b>第 1 层未做的事 / 第 3 层已补</b>：归属行（{@code GovState.nationAffiliations()} / {@code
 * ArmyData.armyAffiliations()}）第 1 层一律未写（决策人 id 由用例稍后决定）⇒ 只产裸决策人的夹具在 {@code DecisionScopeFunctions}
 * 下一律 deny-all。★ <b>2026-10-13 收尾测试第 3 层</b>补上三个落表口（{@link #withNationAffiliation} / {@link
 * #withGovAffiliation} / {@link #withArmyAffiliation}），与生产写口 {@code
 * gov.AssignDecisionMakerNation/Gov} / {@code army.AssignDecisionMakerArmy} <b>同形</b>。
 */
public record WorldSlices(GovState gov, ArmyData army, GaState ga) {

  public WorldSlices {
    Objects.requireNonNull(gov, "gov");
    Objects.requireNonNull(army, "army");
    Objects.requireNonNull(ga, "ga");
  }

  public static WorldSlices of(GovState gov, ArmyData army, GaState ga) {
    return new WorldSlices(gov, army, ga);
  }

  /** 只有 {@code ga} 片（{@code gov}/{@code army} 为空）——"这个夹具不关心国家与军队"时的缺省。 */
  public static WorldSlices gaOnly(GaState ga) {
    return new WorldSlices(GovState.empty(), ArmyData.empty(), ga);
  }

  /** 只换 {@code ga} 片（国家/军队原样）——方便既有的 {@code .withDecisionMakers(...)} 一类链式造法。 */
  public WorldSlices withGa(GaState next) {
    return new WorldSlices(gov, army, next);
  }

  // ── 归属行（★★ 2026-10-13 收尾测试第 3 层）────────────────────────────────────────────

  /**
   * ★★ 往 {@code gov} 切片的 {@code nationAffiliations} 表里落一行（{@code dmId → nationId}）。
   *
   * <p><b>为什么需要它</b>：乙收尾把 {@code affiliation} 从 {@code DecisionMaker} 上删掉（用户裁定「归属肯定归模块」）⇒
   * 归属改住各模块自己的表、<b>按 dmId 索引</b>。夹具里"这个决策人是某国的"从此必须<b>显式落表</b>；只造裸决策人 = 谁都够不着（{@code
   * DecisionScopeFunctions} 给四命名空间 deny-all）⇒ 一切可见性断言退化成恒真式。
   *
   * <p>★ 这与生产写口 {@code gov.AssignDecisionMakerNation} <b>同形</b>（键与值逐字相同）；差别只是本方法直接改切片、不落 revision
   * —— 手搭创世 checkpoint 的夹具没有命令总线可走（{@code ScopeFixtures.affiliates} 同款取舍）。
   */
  public WorldSlices withNationAffiliation(DecisionMakerId maker, NationId nation) {
    return new WorldSlices(
        gov.withNationAffiliations(put(gov.nationAffiliations(), maker, nation)), army, ga);
  }

  /** ★★ 往 {@code gov} 切片的 {@code govAffiliations} 表里落一行（{@code dmId → govUnitId}）。 */
  public WorldSlices withGovAffiliation(DecisionMakerId maker, UnitId govUnit) {
    return new WorldSlices(
        gov.withGovAffiliations(put(gov.govAffiliations(), maker, govUnit)), army, ga);
  }

  /** ★★ 往 {@code army} 切片的 {@code armyAffiliations} 表里落一行（{@code dmId → armyId}）。 */
  public WorldSlices withArmyAffiliation(DecisionMakerId maker, ArmyId armyId) {
    return new WorldSlices(
        gov, army.withArmyAffiliations(put(army.armyAffiliations(), maker, armyId)), ga);
  }

  /** 保序拷贝 + 覆写一个键（夹具里不追求不可变共享，只求语义清楚）。 */
  private static <K, V> Map<K, V> put(Map<K, V> base, K key, V value) {
    Map<K, V> next = new LinkedHashMap<>(base);
    next.put(key, value);
    return next;
  }
}
