package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.ProductionRole.RelationToMeans;
import io.mosire.simos.economy.model.ProductionRole.SurplusRole;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>W4（2026-10-11）：阶层"级"的唯一拼写点</b> —— ① 一个家户<b>当前</b>位置怎么解析；② "下一级"是哪个位置。
 *
 * <p>★★ <b>它从哪来（不是新设计）</b>：这两件事原先是 {@code EconomyLiquidationSettlement}（E5b）的私有静态方法 （{@code
 * resolvablePosition} / {@code resolveDownwardTarget} / {@code compareRoleRank} / {@code
 * POSITION_HEIGHT_ORDER}）。W4 把"阶层滑落"从 E5b 的<b>阈值触发</b>改成<b>速度触发、市场轮最末结算</b> （用户 C-3
 * 裁定：「阶层滑落算的是速度，并且不能跳级，慢慢滑落就行」）⇒ 这段阶梯算法<b>整体搬到这里</b>， E5b 的那一份删除（AGENTS §一.11：旧设计直接删，不留第二份真相）。
 *
 * <p>★★★ <b>"一级"到底是什么（如实记，这是本批最容易被读歪的一点）</b>：本模型里<b>没有</b>一条把 {@code SocialClassId}
 * 的七个词（贫农→中农→富农→地主→雇农→工匠→官吏）排成社会位阶的权威。 唯一存在的位阶是 {@link ProductionRole} 的<b>角色元组</b>：
 *
 * <pre>
 * relationToMeans 序（越大越高）：OWNER(4) &gt; MIXED(3) &gt; OPERATOR(2) &gt; DIRECT_LABORER(1)
 * surplusRole     序（越大越高）：SURPLUS_RECEIVER(3) &gt; SELF_SUBSISTENCE(2) &gt; WAGE_EARNER(1) &gt; DEPENDENT(0)
 * 低位判据 = 角色元组 (relation, surplus) <b>严格更低</b>；"下一级" = 所有严格更低者里<b>最高的那一个</b>
 * </pre>
 *
 * <p>⇒ <b>"不能跳级"在本实现里的确切含义</b>：<b>每次滑落只走"最近的一个严格更低位置"</b>，绝不一步跨到更低的位阶； 同一市场轮内一个家户最多走这一步（见 {@link
 * ClassSlideSettlement}）。★ <b>已知边界（具名）</b>：{@code legacy} 结构里 poor/middle/rich_peasant
 * 三个位置的角色元组<b>完全相同</b>（MIXED + BOTH + SELF_SUBSISTENCE） ⇒
 * 它们在这种机械序下<b>互相之间没有高低</b>，"富农→中农→贫农"这条社会阶梯<b>表达不出来</b>（改动前 E5b 的同一函数也是这个行为，
 * 不是本批引入的）。要把这条阶梯做出来必须给位置表加显式语义链（{@code ruleExtensions.downwardPositionId}，本类的 {@link
 * #DOWNWARD_POSITION_EXTENSION_KEY} 就是它的键名）—— 那是位置表的内容问题，不是本类的判决问题。
 *
 * <p>★ <b>不写状态</b>：本类全是纯函数，不落盘、不进变更集、不认识会话。
 */
final class ClassLadderBook {

  /** ★ 下滑目标的规则扩展位键名（优先于机械序）。 */
  static final String DOWNWARD_POSITION_EXTENSION_KEY = "downwardPositionId";

  private ClassLadderBook() {}

  /** 一次"下一级"解析的结果：{@code target} 空 = 没有下一级（{@code failureReason} 具名）。 */
  record DownwardTarget(Optional<ClassPositionId> target, String failureReason) {

    DownwardTarget {
      Objects.requireNonNull(target, "DownwardTarget.target");
    }
  }

  /**
   * <b>户当前位置</b>：有 standing 用 standing；无 standing 用旧 stratum 的默认位置映射（含"位置表空"的兼容口径）。
   *
   * @param householdEconomy 该户的行（无 standing 时用它推旧 stratum）；可为 null = 行不存在
   */
  static Optional<ClassPositionId> resolvablePosition(
      HouseholdId household,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<ClassPositionId, ProductionRole> positions,
      HouseholdEconomy householdEconomy) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(classMemberships, "classMemberships");
    Objects.requireNonNull(positions, "positions");
    HouseholdClassMembership classMembership = classMemberships.get(household);
    if (classMembership != null) {
      return Optional.of(classMembership.currentPositionId());
    }
    if (householdEconomy == null) {
      return Optional.empty();
    }
    ClassPositionId derived = LegacyClassStructure.positionIdOf(householdEconomy.view().stratum());
    if (positions.isEmpty() || positions.containsKey(derived)) {
      return Optional.of(derived);
    }
    return Optional.empty();
  }

  /**
   * <b>下一级</b>：{@code ruleExtensions} 的 {@link #DOWNWARD_POSITION_EXTENSION_KEY} 优先；否则同 mode
   * 内按角色元组取 <b>最近严格低位</b>（见类注："不能跳级"的确切含义）。
   */
  static DownwardTarget nearestLower(
      ClassPositionId currentId, Map<ClassPositionId, ProductionRole> positions) {
    Objects.requireNonNull(currentId, "currentId");
    Objects.requireNonNull(positions, "positions");
    ProductionRole current = positions.get(currentId);
    if (current == null) {
      return new DownwardTarget(Optional.empty(), "current-position-not-in-table");
    }
    String extension = current.ruleExtensions().get(DOWNWARD_POSITION_EXTENSION_KEY);
    if (extension != null && !extension.isBlank()) {
      try {
        ClassPositionId extensionId = new ClassPositionId(extension);
        ProductionRole extensionPosition = positions.get(extensionId);
        if (extensionPosition != null && !extensionId.equals(currentId)) {
          return new DownwardTarget(Optional.of(extensionId), null);
        }
        return mechanicalNearestLower(
            current, positions, "invalid-" + DOWNWARD_POSITION_EXTENSION_KEY);
      } catch (IllegalArgumentException invalid) {
        return mechanicalNearestLower(
            current, positions, "invalid-" + DOWNWARD_POSITION_EXTENSION_KEY);
      }
    }
    return mechanicalNearestLower(current, positions, "absent-" + DOWNWARD_POSITION_EXTENSION_KEY);
  }

  private static DownwardTarget mechanicalNearestLower(
      ProductionRole current,
      Map<ClassPositionId, ProductionRole> positions,
      String absentOrInvalidReason) {
    List<ProductionRole> lower = new ArrayList<>();
    for (ProductionRole candidate : positions.values()) {
      if (candidate.modeId().equals(current.modeId()) && compareRoleRank(candidate, current) < 0) {
        lower.add(candidate);
      }
    }
    if (lower.isEmpty()) {
      return new DownwardTarget(
          Optional.empty(), absentOrInvalidReason + ";no-strictly-lower-position-in-mode");
    }
    lower.sort(POSITION_HEIGHT_ORDER);
    return new DownwardTarget(Optional.of(lower.get(0).id()), absentOrInvalidReason);
  }

  /**
   * ★★ <b>机械回退的"严格更低"判据</b>：只在<b>角色元组</b>（relationToMeans 序 → surplusRole 序）上严格更低才算低位。
   *
   * <p>★ 为什么不把 {@code ClassPositionId.value()} 也塞进"更低"的比较：id 是稳定拼写，不是社会位阶。若把同角色元组内 id
   * 更小/更大也算"更低"，legacy 结构里的"贫农"会被机械序判成"比中农高/低"——会出现<b>向上迁移</b>，违反 "阶层下滑"与"不得乱降"的意图。{@code
   * Id.value()} 只在<b>同角色档的多个候选之间</b>做确定性 tiebreak（见 {@link #POSITION_HEIGHT_ORDER}）。
   */
  static int compareRoleRank(ProductionRole left, ProductionRole right) {
    int relation =
        Integer.compare(
            relationOrdinal(left.relationToMeans()), relationOrdinal(right.relationToMeans()));
    if (relation != 0) {
      return relation;
    }
    return Integer.compare(surplusOrdinal(left.surplusRole()), surplusOrdinal(right.surplusRole()));
  }

  /**
   * 机械下滑序：{@code (relationToMeans 语义序, surplusRole 语义序, ClassPositionId.value())} 的降序。★ 它只是显式
   * {@code downwardPositionId} 的兜底；语义链请显式写规则扩展位。
   */
  private static final Comparator<ProductionRole> POSITION_HEIGHT_ORDER =
      Comparator.comparingInt(
              (ProductionRole position) -> relationOrdinal(position.relationToMeans()))
          .reversed()
          .thenComparing(
              Comparator.comparingInt(
                      (ProductionRole position) -> surplusOrdinal(position.surplusRole()))
                  .reversed())
          .thenComparing(position -> position.id().value());

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.29 §7 第 2 条）：同一套位阶的<b>升序</b>形态 —— "取能支撑的最高档"用它</b>。
   *
   * <p>★ <b>为什么必须落在这里</b>：位阶的唯一拼写点是本类（{@link #POSITION_HEIGHT_ORDER} 与两个 {@code ordinal} 函数）。W9
   * 的转业档位要在**同一个 mode 的可生产位置**里从低到高取"能支撑的最高那一个"；若复用降序表取首项，方向就反了 （"库存最少也落最高档"）——
   * 这种反向错误不抛、不报，只会让世界静默变样。⇒ 升序表与降序表**同处一文件、共用同一组 ordinal**，只差方向； tie-break 仍是 {@code
   * ClassPositionId.value()} 升序（确定性 I7）。
   */
  static final Comparator<ProductionRole> POSITION_LADDER_ASCENDING =
      Comparator.comparingInt(
              (ProductionRole position) -> relationOrdinal(position.relationToMeans()))
          .thenComparingInt(position -> surplusOrdinal(position.surplusRole()))
          .thenComparing(position -> position.id().value());

  /** 与生产资料的关系序（越大越高）：OWNER &gt; MIXED &gt; OPERATOR &gt; DIRECT_LABORER。 */
  static int relationOrdinal(RelationToMeans relation) {
    return switch (relation) {
      case OWNER -> 4;
      case MIXED -> 3;
      case OPERATOR -> 2;
      case DIRECT_LABORER -> 1;
    };
  }

  /** 剩余角色序（越大越高）：SURPLUS_RECEIVER &gt; SELF_SUBSISTENCE &gt; WAGE_EARNER &gt; DEPENDENT。 */
  static int surplusOrdinal(SurplusRole surplus) {
    return switch (surplus) {
      case SURPLUS_RECEIVER -> 3;
      case SELF_SUBSISTENCE -> 2;
      case WAGE_EARNER -> 1;
      case DEPENDENT -> 0;
    };
  }

  /** 一行人类可读的"位阶"读数（只服务日志/证据，不参与任何判定）。 */
  static String describeLevel(ProductionRole position) {
    if (position == null) {
      return "unknown";
    }
    return position.relationToMeans()
        + "/"
        + position.surplusRole()
        + "("
        + relationOrdinal(position.relationToMeans())
        + ","
        + surplusOrdinal(position.surplusRole())
        + ")";
  }
}
