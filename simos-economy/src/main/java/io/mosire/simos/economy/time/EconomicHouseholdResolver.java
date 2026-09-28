package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ProductionUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>E1：一个 {@link ProductionUnit} 的"关联经济家户"解析器（唯一拼写点）</b>。
 *
 * <p>★★ <b>为什么必须只有一处</b>：旧实现把"经营者 → 家户"写成 {@code householdOfActor.get(unit.operator())}， 只有
 * operator 恰为家户 actor 时命中；ESTATE / WORKSHOP / 聚合 weave 这类主体的债务压力因此恒 false（它们永远走不到 {@code
 * INDEBTED/SUSPENDED/EXITED}，计划 §2.E1 点名的缺口）。本类把"谁是这家户"按固定次序解析一次，债务压力与退出处置共用同一答案。
 *
 * <pre>
 * ① operator 本身是家户 actor（HouseholdActors.of(HouseholdId)）        ⇒ 该 HouseholdId
 * ② relation.residualOwner（ActorRef 反查）或 inputSupplier（ToHousehold / 可反查的 ToActor）⇒ 该 HouseholdId
 * ③ unit 名下 AssetShare 的 owner（先）或 operator（后）能反解成家户 actor ⇒ 该 HouseholdId
 * ④ 否则 Optional.empty()（ESTATE / WORKSHOP / 聚合 weave：不伪造家户、不强行借债；仍可缩产/停业/退出）
 * </pre>
 *
 * <p>★★ <b>③ 为什么 owner 先于 operator</b>：{@code AssetShare.owner} 是所有权事实，"这份生产的地/工具是谁的"比"谁在用"更接近
 * "这块生产关联到哪个家户"；operator 兜底服务的是"实际经营者本身就是一个未登记为行 actor 的家户"这种旧数据。两者都只做反向查表 （不解析/不猜 id）。
 *
 * <p>★ <b>只做纯查表</b>：{@code householdOfActor} 由 {@code SettlementIndex.householdByActor()} 一次建好（键 =
 * 现存家户行的 actor）；本类不扫全表、不解析 id、不抛异常（解析不到不是坏数据，是"这个主体没有家户"）。
 */
final class EconomicHouseholdResolver {

  private EconomicHouseholdResolver() {}

  /**
   * 按 ①→③ 的顺序解析一个 unit 的关联家户。
   *
   * @param unit 生产单元；不得为 null
   * @param relation 该 unit 的生产关系；可为 null（尚无/旧档）
   * @param unitShares 该 unit 名下（{@code industry + operator} 作用域）的份额快照；可为 null
   * @param householdOfActor 家户 actor → 家户身份的反查表（键集 = 现存家户行）；不得为 null
   * @return 解析到的家户；解析不到 ⇒ {@link Optional#empty()}（不伪造）
   */
  static Optional<HouseholdId> resolve(
      ProductionUnit unit,
      ProductionRelation relation,
      List<AssetShare> unitShares,
      Map<ActorRef, HouseholdId> householdOfActor) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(householdOfActor, "householdOfActor");
    // ① operator 本身是家户 actor（tenant / artisan / 家户自营 weave 的主路径）。
    HouseholdId direct = householdOfActor.get(unit.operator());
    if (direct != null) {
      return Optional.of(direct);
    }
    // ② 关系里的家庭受方/余额归属（residualOwner 先于 inputSupplier：它是余额归属，比投入来源更接近"这家生产归谁"）。
    //   residualOwner 的类型是 ActorRef ⇒ 用 householdOfActor 反查（聚合主体不在表里 ⇒ null）；
    //   inputSupplier 是 Recipient：ToHousehold 直接给，ToActor 再走同一张反查表（旧数据/显式家户 actor 两种写法归一）。
    if (relation != null) {
      HouseholdId residualOwner = householdOfActor.get(relation.residualOwner());
      if (residualOwner != null) {
        return Optional.of(residualOwner);
      }
      HouseholdId inputSupplier = recipientHousehold(relation.inputSupplier(), householdOfActor);
      if (inputSupplier != null) {
        return Optional.of(inputSupplier);
      }
    }
    // ③ 份额的 owner（先）或 operator（后）能反解成家户 actor。
    if (unitShares != null) {
      for (AssetShare share : unitShares) {
        HouseholdId owner = householdOfActor.get(share.owner());
        if (owner != null) {
          return Optional.of(owner);
        }
      }
      for (AssetShare share : unitShares) {
        HouseholdId operator = householdOfActor.get(share.operator());
        if (operator != null) {
          return Optional.of(operator);
        }
      }
    }
    // ④ 解析不到：不伪造家户、不借债；市场/投入压力仍可推进状态机。
    return Optional.empty();
  }

  /**
   * 关系某一端的受方 → 家户：{@code ToHousehold} 直接取；{@code ToActor} 走家户 actor 反查表（不是家户 actor ⇒ null）；其余变体（含旧
   * {@code ToCohort}）⇒ null（不猜）。
   */
  private static HouseholdId recipientHousehold(
      Recipient recipient, Map<ActorRef, HouseholdId> householdOfActor) {
    return switch (recipient) {
      case Recipient.ToHousehold toHousehold -> toHousehold.household();
      case Recipient.ToActor toActor -> householdOfActor.get(toActor.actor());
      case Recipient.ToCohort ignored -> null;
    };
  }
}
