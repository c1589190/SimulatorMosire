package io.mosire.simos.app.household;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>Z4/C4 跨切片引物一致性：{@code GovernmentPostOfHousehold.tierId} 必须指向该 GOV 的 {@code
 * GovAdministrationPlan.postTiers()} 档位</b>（Z3d 起<b>内部 {@code householdPosts} 与外部 {@code
 * externalPosts} 同权覆盖</b>；两表互斥，遍历 = 两表并入）。
 *
 * <pre>
 * post.tierId 非空  ⇒  tierId ∈ administrationPlanOrDefault(unitId).postTiers().map(GovPostTier::tierId)
 * post.tierId 为空  ⇒  legacy/未指派（豁免；旧档零迁移）
 * </pre>
 *
 * <p>★★ <b>为什么放在 app 组合根</b>：岗位在 unit 切片、档位目录在 gov 切片（Z2 的 {@link GovAdministrationPlan}），
 * 两边互不可见（模块边界）；只有 app 同时看得见两片。unit 侧 {@code unit.AssignGovPost} 把 {@code tierId} 当不透明引用解析并以 {@code
 * Rejected} 兜字段形状；这里把"引用必须存在于目录"这条契约判死（GM 裸 {@code simos.command.submit} 也绕不过）。
 *
 * <p>★ <b>只读 + 纯函数</b>：不改入参；不一致以 {@link Mismatch} 具名列出，{@link #requireConsistent} 折成一条异常。 缺 gov 计划的
 * GOV 走 {@link GovState#administrationPlanOrDefault} 的中性默认（Z2：默认 3 档目录），因此 只有"目录确实不含该 tierId"才报。
 */
public final class GovernmentPostTierConsistency {

  /** 契约故障日志通道：与推进入口同一 logger，来源 {@link AppLogSource#DAILY_LOOP}（GOV 编制/日循环契约）。 */
  private static final LogChannel CONSISTENCY = EventLog.channel(AppLog.time());

  private GovernmentPostTierConsistency() {}

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

  /** 全量只读校核（按 unit id 稳定序遍历；结果只依赖状态内容）。 */
  public static List<Mismatch> mismatches(GovState govState, UnitState units) {
    Objects.requireNonNull(govState, "govState");
    Objects.requireNonNull(units, "units");
    List<Unit> ordered = new ArrayList<>(units.units().values());
    ordered.sort(Comparator.comparing(unit -> unit.id().value()));
    List<Mismatch> out = new ArrayList<>();
    for (Unit unit : ordered) {
      if (!(unit.module().orElse(null) instanceof GovernmentFormation formation)
          || !formation.hasAnyPosts()) {
        continue;
      }
      Set<String> tierIds = new LinkedHashSet<>();
      for (GovPostTier tier : govState.administrationPlanOrDefault(unit.id()).postTiers()) {
        tierIds.add(tier.tierId());
      }
      for (GovernmentPostOfHousehold post : formation.allPosts().values()) {
        if (!post.hasTier()) {
          continue; // ★ 空串 = legacy/未指派（旧档豁免）
        }
        if (!tierIds.contains(post.tierId())) {
          out.add(
              new Mismatch(
                  "GOV_POST_TIER_UNKNOWN",
                  "unit "
                      + unit.id()
                      + " 的岗位家户 "
                      + post.householdId()
                      + " 指派到不存在的档位 tierId="
                      + post.tierId()
                      + "（该 GOV 计划目录="
                      + tierIds
                      + "）；先 simos.gov.setEstablishment 配好目录，或改用存在的档位"));
        }
      }
    }
    return List.copyOf(out);
  }

  /** 校核 + 具名 fail-closed（不一致 ⇒ 先 ERROR 一行、再 {@link IllegalStateException}，消息最多列 5 条）。 */
  public static void requireConsistent(GovState govState, UnitState units) {
    List<Mismatch> mismatches = mismatches(govState, units);
    if (!mismatches.isEmpty()) {
      // ★ AGENTS §一.9（2026-10-23）：契约/跨切片一致性故障 = ERROR 不降级；先落证据再 fail-closed。
      CONSISTENCY.error(
          LogEvent.of(
              "GOV_POST_TIER_CONSISTENCY_FAULT",
              AppLogSource.DAILY_LOOP,
              "count",
              mismatches.size(),
              "first",
              mismatches.get(0)));
      throw new IllegalStateException(
          "GOV 岗位档位引用与 GovAdministrationPlan.postTiers 不一致（Z4/C4）："
              + mismatches.size()
              + " 处："
              + mismatches.subList(0, Math.min(5, mismatches.size())));
    }
  }
}
