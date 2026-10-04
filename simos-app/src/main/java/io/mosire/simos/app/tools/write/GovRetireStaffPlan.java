package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.gov.retireStaff} 的<b>纯推导</b>（阶段 13A 人员流转，GOV/Army 计划 §2.6）： 离编 + 按政策从国库一次性支付退休待遇
 * + <b>社会回写</b>（并入指定 hex 既有批次里 id 最小的一条）+ 留行动记录， 一批落一条 revision——<b>不碰</b> {@link
 * io.mosire.agentlib.tool.ToolContext}/{@code CoreSimos}。
 *
 * <p>★★ <b>待遇口径复用 {@link GovDismissPlan}</b>：本类不写第二份支付推导——离编/roster 校验、{@code
 * policy.retirementPerStaff × count}、国库可支配（余额 − 冻结）不足整条拒（带 requested/available/缺口）都由 {@link
 * GovDismissPlan#plan} 一处给出；本类只在其上加“社会回写”那一段。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code unit.DismissStaff} →（{@code payment > 0}）{@code
 * actor.AdjustAccounts}（国库银负增量）→（给了 reinsertQ/reinsertR）{@code social.SeedGroups}（把 count 并入该 hex
 * 既有批次里 id 最小的一条；<b>不新建批次</b>）→ {@code sd.PutInfo}（地址 = GOV canonical，key={@code retireStaff}）。
 *
 * <p>★★ <b>“并入最小 id 批次”是具名的近似</b>：回写不重建退休者的身份/年龄/性别分档，而是把人数并入当地既有批次 里 id
 * 最小的一条（其余字段原样）——读面上表现为“这批人多了 count”，不是“来了一批退休老人”。一期先这样落， 后续阶段可细化（单独建批次 / 按年龄分档回写）。
 *
 * <p>★★ <b>回写校验</b>：{@code reinsertQ}/{@code reinsertR} 必须成对；该 hex 必须在 {@code
 * SocialData.populations} 里有序列（{@code social.SeedGroups} 的跨组件校验前置）；该 hex 必须<b>已有至少一条
 * 批次</b>——否则具名拒，<b>不静默丢人</b>。
 *
 * <p>★ <b>守恒</b>：GOV roster 前 − count == roster 后；待遇支付额 == {@code retirementPerStaff × count}；回写时
 * {@code 原批次 count + count == 新批次 count}（其余字段原样）。Plan 构造期逐值互校。
 */
final class GovRetireStaffPlan {

  /** {@code unit.DismissStaff} 的命令类型（与 {@code DismissStaffHandler.type()} 同字面）。 */
  static final String DISMISS_STAFF_TYPE = "unit.DismissStaff";

  /** {@code actor.AdjustAccounts} 的命令类型（仅待遇 &gt; 0 才落）。 */
  static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** {@code social.SeedGroups} 的命令类型（仅给了回写格才落）。 */
  static final String SEED_GROUPS_TYPE = "social.SeedGroups";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  private GovRetireStaffPlan() {}

  /**
   * 纯推导入口（见类注的待遇/回写口径）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 离编主体（带 GovFormation 的 GOV）
   * @param roleText 行政角色词表（SCRIBE|YAMEN|POST）
   * @param count 离编人数（≥ 1，且不得超过现有在编）
   * @param reinsertQ 回写格 q（可选；与 reinsertR 成对）
   * @param reinsertR 回写格 r（可选；与 reinsertQ 成对）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      String roleText,
      long count,
      Optional<Long> reinsertQ,
      Optional<Long> reinsertR) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(reinsertQ, "reinsertQ");
    Objects.requireNonNull(reinsertR, "reinsertR");
    GovDismissPlan.Plan dismissal = GovDismissPlan.plan(state, unitId, roleText, count);
    if (reinsertQ.isPresent() != reinsertR.isPresent()) {
      throw new IllegalArgumentException(
          "reinsertQ 与 reinsertR 必须成对给出（要么都给、要么都不给）: reinsertQ="
              + reinsertQ.orElse(null)
              + "，reinsertR="
              + reinsertR.orElse(null));
    }
    Optional<HexCoord> reinsertAt = Optional.empty();
    Optional<PopulationGroup> reinsertTarget = Optional.empty();
    long reinsertCountAfter = 0L;
    if (reinsertQ.isPresent()) {
      int q = toInt(reinsertQ.get(), "reinsertQ");
      int r = toInt(reinsertR.get(), "reinsertR");
      HexCoord hex = new HexCoord(q, r);
      SocialData social = ToolSupport.socialData(state);
      if (social.groupsAt(hex).isEmpty()) {
        throw new IllegalArgumentException(
            "回写格 "
                + hexText(hex)
                + " 没有任何人口批次：没有可并入的批次，本工具不静默丢人"
                + "（先向该格落一批人口，或改指已有批次的格）");
      }
      List<PopulationGroup> groups = social.groupsAt(hex);
      if (groups.isEmpty()) {
        throw new IllegalArgumentException(
            "回写格 " + hexText(hex) + " 没有任何人口批次：没有可并入的批次，本工具不静默丢人" + "（先向该格落一批人口，或改指已有批次的格）");
      }
      PopulationGroup smallest = groups.get(0);
      for (PopulationGroup group : groups) {
        if (group.id().value().compareTo(smallest.id().value()) < 0) {
          smallest = group;
        }
      }
      long after;
      try {
        after = Math.addExact(smallest.count(), dismissal.count());
      } catch (ArithmeticException e) {
        throw new IllegalArgumentException(
            "回写后批次人数溢出 long: 批次 "
                + smallest.id().value()
                + " 现有 "
                + smallest.count()
                + " + count "
                + dismissal.count(),
            e);
      }
      reinsertAt = Optional.of(hex);
      reinsertTarget =
          Optional.of(
              new PopulationGroup(
                  smallest.id(),
                  smallest.sex(),
                  after,
                  smallest.ageAtAnchorDays(),
                  smallest.anchorTick(),
                  smallest.physiologicalStress()));
      reinsertCountAfter = after;
    }
    return new Plan(dismissal, reinsertAt, reinsertTarget, reinsertCountAfter);
  }

  /** long → int（回写格坐标；超 int ⇒ 具名拒，不静默截断）。 */
  private static int toInt(long value, String field) {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(field + " 超出 int 坐标范围: " + value);
    }
    return (int) value;
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /**
   * 一份退休计划（全部字段是状态的纯函数；{@code dismissal} 是共享支付推导的产物）。
   *
   * @param dismissal 离编 + 待遇支付那一半（见 {@link GovDismissPlan.Plan}）
   * @param reinsertAt 社会回写格（可选）
   * @param reinsertTarget 回写后的批次（可选；人数 = 原批人数 + dismissal.count，其余字段原样）
   * @param reinsertCountAfter 回写后批次人数（未回写时 0）
   */
  record Plan(
      GovDismissPlan.Plan dismissal,
      Optional<HexCoord> reinsertAt,
      Optional<PopulationGroup> reinsertTarget,
      long reinsertCountAfter) {

    Plan {
      Objects.requireNonNull(dismissal, "dismissal");
      Objects.requireNonNull(reinsertAt, "reinsertAt");
      Objects.requireNonNull(reinsertTarget, "reinsertTarget");
      if (reinsertAt.isPresent() != reinsertTarget.isPresent()) {
        throw new IllegalArgumentException("内部分摊不自洽：reinsertAt 与 reinsertTarget 必须同时有/无");
      }
      if (reinsertAt.isPresent()) {
        PopulationGroup target = reinsertTarget.get();
        if (target.count() < dismissal.count() || reinsertCountAfter != target.count()) {
          throw new IllegalArgumentException(
              "守恒破坏：回写批次人数 "
                  + target.count()
                  + " / reinsertCountAfter="
                  + reinsertCountAfter
                  + " 与 count="
                  + dismissal.count()
                  + " 不自洽");
        }
      } else if (reinsertCountAfter != 0L) {
        throw new IllegalArgumentException("内部分摊不自洽：未回写却记了回写后人数");
      }
    }

    /** 是否要落 {@code actor.AdjustAccounts}（待遇 &gt; 0 才落）。 */
    boolean hasPayment() {
      return dismissal.hasPayment();
    }

    /** 是否要落 {@code social.SeedGroups}（给了回写格才落）。 */
    boolean hasReinsert() {
      return reinsertAt.isPresent();
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(DISMISS_STAFF_TYPE);
      if (hasPayment()) {
        types.add(ADJUST_ACCOUNTS_TYPE);
      }
      if (hasReinsert()) {
        types.add(SEED_GROUPS_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** {@code unit.DismissStaff} 载荷（复用共享推导的载荷）。 */
    String dismissStaffPayloadJson() {
      return dismissal.dismissStaffPayloadJson();
    }

    /** {@code actor.AdjustAccounts} 载荷（复用共享推导的载荷；仅 {@link #hasPayment()} 时合法）。 */
    String adjustAccountsPayloadJson() {
      return dismissal.adjustAccountsPayloadJson();
    }

    /** {@code social.SeedGroups} 载荷：只写“并入最小 id 批次”的那一条整组覆盖（其余字段原样；人数 = 原 + count）。 */
    String seedGroupsPayloadJson() {
      if (!hasReinsert()) {
        throw new IllegalStateException("批不自洽：未给回写格却要组装 social.SeedGroups 载荷");
      }
      PopulationGroup target = reinsertTarget.get();
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", target.id().value());
      entry.put("q", reinsertAt.get().q());
      entry.put("r", reinsertAt.get().r());
      entry.put("sex", target.sex().name());
      entry.put("count", target.count());
      entry.put("ageDays", target.ageAtAnchorDays());
      entry.put("anchorTick", target.anchorTick());
      entry.put("stress", target.physiologicalStress());
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", List.of(entry));
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含 role/count/待遇/reinsert hex）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", dismissal.unitId());
      value.put("role", dismissal.role().name());
      value.put("count", dismissal.count());
      value.put("tick", dismissal.tick());
      value.put("staffBefore", dismissal.staffBefore());
      value.put("staffAfter", dismissal.staffAfter());
      value.put("retirementPerStaff", dismissal.retirementPerStaff());
      value.put("payment", dismissal.payment());
      value.put(
          "treasury", dismissal.treasuryLocation().map(GovDismissPlan::treasuryView).orElse(null));
      value.put("availableSilver", dismissal.availableSilver());
      value.put("reinsert", reinsertView());
      value.put("socialApproximation", "并入该 hex 既有批次里 id 最小的一条（身份/年龄不细分档）");
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "退休离编 "
          + dismissal.unitId()
          + " 的 "
          + dismissal.role()
          + " "
          + dismissal.count()
          + " 人（tick "
          + dismissal.tick()
          + "）：在编 "
          + dismissal.staffBefore()
          + "→"
          + dismissal.staffAfter()
          + "，待遇 "
          + dismissal.payment()
          + " 银"
          + (hasPayment()
              ? "（国库 @ " + hexText(dismissal.treasuryLocation().get()) + "）"
              : "（政策为 0，无支付命令）")
          + "；回写="
          + (hasReinsert()
              ? hexText(reinsertAt.get())
                  + " 批次 "
                  + reinsertTarget.get().id().value()
                  + " "
                  + (reinsertTarget.get().count() - dismissal.count())
                  + "→"
                  + reinsertTarget.get().count()
                  + "（★ 近似：并入最小 id 批次，身份/年龄不细分档）"
              : "(未指定回写格：人不回写社会)")
          + "；reason="
          + reason;
    }

    /** 回写视图（{@code {q,r,batchId,batchCountBefore,batchCountAfter,approximation}}；未回写时 null）。 */
    Map<String, Object> reinsertView() {
      if (!hasReinsert()) {
        return null;
      }
      PopulationGroup target = reinsertTarget.get();
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("q", reinsertAt.get().q());
      view.put("r", reinsertAt.get().r());
      view.put("batchId", target.id().value());
      view.put("batchCountBefore", target.count() - dismissal.count());
      view.put("batchCountAfter", target.count());
      view.put("approximation", "并入该 hex 现有批次里 id 最小的一条（其余字段原样）");
      return view;
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }
}
