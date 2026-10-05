package io.mosire.simos.app.household;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>中央/地方 GOV 家户与政府记录的闭环校核</b>（P2-C §13.7；app 组合根是唯一同时看得见 unit 与 economy 的地方）。
 *
 * <pre>
 * 每个带 GovFormation 的 unit：
 *   GovFormation.households 里恰一个政府家户 H = hh-gov-&lt;unitId&gt;
 *   economy.governments[gov-unit-&lt;unitId&gt;].treasury == HouseholdActors.of(H)
 *   economy.classes 里有 H 的 ClassRow（人口层/劳动层可配置、可入市）
 * </pre>
 *
 * <p>★★ <b>为什么必须有这一处</b>：三条事实分布在三个切片 —— {@link GovFormation#households()}（unit）、 {@link
 * Government#treasury()}（economy）、{@code actor} 的账户（政府家户账户）。创建侧由 {@code economy.RegisterGovernment}
 * + {@code actor.EnsureHouseholdAccount} 成对写；本类在推进入口把"三边都在且逐值对应" 判死 —— 少了任何一边都
 * fail-closed，不把"没有政府记录"读成"没有政府"、也不让某个 GOV 单位悄悄共用别家的国库。
 *
 * <p>★ <b>只读 + 纯函数</b>：不改入参；不一致以 {@link Mismatch} 具名列出，{@link #requireConsistent} 折成一条异常。
 * 世界级政府（{@code world-silver} 这类非单位派生 id）不在本类射程内（{@link GovernmentIds#unitRefOf(GovernmentId)}
 * 对它们返回空）。
 */
public final class GovernmentHouseholdWiring {

  private GovernmentHouseholdWiring() {}

  /** 一处具名不一致。 */
  public record Mismatch(String kind, String detail) {

    public Mismatch {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(detail, "detail");
    }

    @Override
    public String toString() {
      return kind + ": " + detail;
    }
  }

  /** 全量只读校核；结果按 unit id / government id 稳定序遍历（只依赖状态内容）。 */
  public static List<Mismatch> mismatches(EconomyData economy, SocialData social, UnitState units) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    List<Mismatch> out = new ArrayList<>();

    // 正向：每个 GOV 单位都要有"政府家户 → ClassRow → 政府记录 → 国库=家户"这条链。
    List<Unit> ordered = new ArrayList<>(units.units().values());
    ordered.sort(Comparator.comparing(unit -> unit.id().value()));
    for (Unit unit : ordered) {
      if (!(unit.module().orElse(null) instanceof GovFormation gov)) {
        continue;
      }
      HouseholdId governmentHousehold = null;
      for (HouseholdId household : gov.households()) {
        if (GovernmentHouseholds.isGovernment(household)) {
          if (governmentHousehold != null) {
            out.add(
                new Mismatch(
                    "GOV_MULTIPLE_HOUSEHOLDS",
                    "unit " + unit.id() + " 有多个政府家户: " + governmentHousehold + " / " + household));
          }
          governmentHousehold = household;
        }
      }
      if (governmentHousehold == null) {
        out.add(
            new Mismatch(
                "GOV_HOUSEHOLD_MISSING",
                "unit "
                    + unit.id()
                    + " 的 GovFormation.households 没有政府家户（应为 "
                    + GovernmentHouseholds.of(unit.id().value())
                    + "）"));
        continue;
      }
      HouseholdId expectedHousehold = GovernmentHouseholds.of(unit.id().value());
      if (!expectedHousehold.equals(governmentHousehold)) {
        out.add(
            new Mismatch(
                "GOV_HOUSEHOLD_ID_MISMATCH",
                "unit "
                    + unit.id()
                    + " 的政府家户应为 "
                    + expectedHousehold
                    + "，实际="
                    + governmentHousehold));
      }
      if (!social.households().containsKey(governmentHousehold)) {
        out.add(
            new Mismatch(
                "GOV_HOUSEHOLD_NOT_IN_SOCIAL",
                "unit "
                    + unit.id()
                    + " 的政府家户 "
                    + governmentHousehold
                    + " 不在 Social 家户表里（先 social.CreateHousehold 建户，再 unit.SetGovFormation）"));
      }
      if (!economy.classes().containsKey(governmentHousehold)) {
        out.add(
            new Mismatch(
                "GOV_CLASSROW_MISSING",
                "政府家户 "
                    + governmentHousehold
                    + "（unit "
                    + unit.id()
                    + "）在 economy.classes 里没有人口/劳动行（先用 economy.RegisterGovernment 登记）"));
      }
      GovernmentId expectedGovernment = GovernmentIds.ofUnit(unit.id().value());
      Government government = economy.governments().get(expectedGovernment);
      if (government == null) {
        out.add(
            new Mismatch(
                "GOV_RECORD_MISSING",
                "unit "
                    + unit.id()
                    + " 没有政府记录 "
                    + expectedGovernment.value()
                    + "（先用 economy.RegisterGovernment 登记）"));
      } else {
        ActorRef expectedTreasury = HouseholdActors.of(governmentHousehold);
        if (!expectedTreasury.equals(government.treasury())) {
          out.add(
              new Mismatch(
                  "GOV_TREASURY_MISMATCH",
                  "政府 "
                      + expectedGovernment.value()
                      + " 的国库应为政府家户账户 "
                      + expectedTreasury
                      + "，实际="
                      + government.treasury()));
        }
      }
    }

    // 反向：每条单位派生的政府记录都必须对应一个真 GOV 单位，且家户仍在它的 GovFormation.households 里。
    for (Government government : economy.governments().values()) {
      Optional<String> unitRef = GovernmentIds.unitRefOf(government.id());
      if (unitRef.isEmpty()) {
        continue;
      }
      Unit unit = units.units().get(UnitId.parse(unitRef.get()));
      if (unit == null || !(unit.module().orElse(null) instanceof GovFormation gov)) {
        out.add(
            new Mismatch(
                "GOV_RECORD_WITHOUT_UNIT",
                "政府 " + government.id().value() + " 指向不存在的 GOV 单位 " + unitRef.get()));
        continue;
      }
      HouseholdId household = GovernmentHouseholds.of(unitRef.get());
      if (!gov.households().contains(household)) {
        out.add(
            new Mismatch(
                "GOV_TREASURY_NOT_IN_FORMATION",
                "政府 "
                    + government.id().value()
                    + " 的政府家户 "
                    + household
                    + " 不在 unit "
                    + unit.id()
                    + " 的 GovFormation.households 里"));
      }
    }
    return List.copyOf(out);
  }

  /** 校核 + 具名 fail-closed（不一致 ⇒ {@link IllegalStateException}，消息最多列 5 条）。 */
  public static void requireConsistent(EconomyData economy, SocialData social, UnitState units) {
    List<Mismatch> mismatches = mismatches(economy, social, units);
    if (!mismatches.isEmpty()) {
      throw new IllegalStateException(
          "GOV 家户 / 政府记录闭环不一致（P2-C §13.7）："
              + mismatches.size()
              + " 处："
              + mismatches.subList(0, Math.min(5, mismatches.size())));
    }
  }
}
