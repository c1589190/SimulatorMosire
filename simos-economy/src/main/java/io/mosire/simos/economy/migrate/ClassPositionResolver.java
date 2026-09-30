package io.mosire.simos.economy.migrate;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>阶层位置解析器（E1b）</b>：旧 {@code ClassRow.view.stratum} ↔ 新 {@code ClassStanding} / {@code
 * ClassPosition} 之间的唯一纯函数入口。
 *
 * <p>★★ <b>它不参与结算，也不改变旧路径</b>：旧 {@code HouseholdClassRule} / {@code 旧结算引擎（R3a 已删除）} / {@code
 * EconomySeedHandler} / {@code EconomyStateBuilder} <b>全部原样不动</b>（旧路径仍以 {@code ClassRow.view}
 * 为准）；本类只有"未来显式迁移器或读口主动调用"时才起作用。调用与不调用都不构成行为变化 —— 唯一的区别是后者状态树里多/少四张新表。
 *
 * <p>★★ <b>只读解析的唯一口径 {@link #resolveCurrent(EconomyData, HouseholdId)}</b>（按优先级）：
 *
 * <ol>
 *   <li>{@code classStandings} 里有该户 ⇒ 返回其 {@link ClassStanding#currentPositionId()}。<b>新状态优先</b>：
 *       有显式归属后，不再回看旧 stratum，也不在这里做再分类（分类是 {@code HouseholdClassRule} 的职责）。
 *   <li>否则该户在旧 {@code classes} 里有行 ⇒ 按 {@link LegacyClassStructure#positionIdOf} 把 {@code
 *       view.stratum} 映射成默认结构的对应位置。<b>这一步不要求 {@code classPositions} 已经存在</b>：
 *       未迁移的旧档四张新表全空，此时读口仍应能回答"这户在默认结构里对应哪个位置"（否则"旧档空表兼容"会退化成 "读口必须先迁移才能读"）。
 *   <li>都没有 ⇒ {@link Optional#empty()}（不猜、不造位置）。
 * </ol>
 *
 * <p>★★ <b>迁移种子 {@link #seedLegacyClassStandings(EconomyData)} 的契约</b>：
 *
 * <ul>
 *   <li><b>幂等（同一实例）</b>：若新状态（{@code modes} / {@code classStructures} / {@code classPositions} /
 *       {@code classStandings} 任一）非空，原样返回入参<b>同一个引用</b> —— 第二次调用不会 重算、不会多写一张表，{@code
 *       EconomyChangeSet.between(before, after)} 因此没有可提交的差异；
 *   <li><b>无事可做</b>：新状态为空且旧 {@code classes} 也为空 ⇒ 同样返回入参同一实例（没有可播种的家户）；
 *   <li><b>显式播种</b>：新状态为空且旧 {@code classes} 非空 ⇒ 返回一个新的 {@code EconomyData}，写入 E1a 的 四张表：默认 mode +
 *       默认 classStructure + 7 个默认 classPositions + 每户一条 {@code ClassStanding} （{@code original =
 *       current = positionIdOf(row.view().stratum)}，{@code retainedShares} 空表， {@code
 *       lastTransitionDay = 0}，{@code reason = LegacyClassStructure.SEED_REASON}）；
 *   <li><b>最小改动面</b>：只读旧 {@code ClassRow.view.stratum} 做映射，<b>不回写</b> {@code ClassRow}，不动 {@code
 *       view}/{@code debts}/{@code memberships}/{@code assetShares} 等任何已有组件；四张新表的写入走 {@code
 *       EconomyData.with*} 逐组件写口，不新增绕过变更集的入口。本方法返回的仍是状态值，交给调用方按 Command → ChangeSet → Revision 提交。
 * </ul>
 *
 * <p>★★ <b>为什么 {@code retainedShares} 留空而不是写 1000‰</b>：{@code original == current} 已经表达"没有发生
 * 模式变迁、全部仍属原位"；空表 = "没有保留比例的变迁记录"。写 1000‰ 会凭空造出一条"曾经迁移过并保留 100%"的 记录，与 {@code lastTransitionDay =
 * 0}（尚无迁移）自相矛盾，E6 迁移器也难以区分"创世种子"和"真的保留过"。
 *
 * <p>★★ <b>确定性</b>：全部是静态纯函数；家户按 {@code data.classes()} 的稳定迭代序处理，位置 id 由 {@link
 * LegacyClassStructure} 的纯函数生成；<b>无随机、无时钟、无 UUID、无环境依赖</b>。同一入参在任意进程、 任意线程恒得逐字段相同的返回值（幂等分支则恒得同一引用）。
 */
public final class ClassPositionResolver {

  private ClassPositionResolver() {}

  /**
   * ★★ <b>只读解析一个家户的当前阶层位置</b>（新状态优先，其次旧 stratum 映射；口径见类注释）。
   *
   * @param data 经济状态；不得为 null
   * @param household 家户稳定身份；不得为 null
   * @return 当前位置；新状态里没有该户、且旧 {@code classes} 里也没有该户 ⇒ 空
   */
  public static Optional<ClassPositionId> resolveCurrent(EconomyData data, HouseholdId household) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(household, "household");
    ClassStanding standing = data.classStandings().get(household);
    if (standing != null) {
      return Optional.of(standing.currentPositionId());
    }
    ClassRow row = data.classes().get(household);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(LegacyClassStructure.positionIdOf(row.view().stratum()));
  }

  /**
   * ★★ <b>把旧档 {@code classes} 显式播种成新阶层归属</b>（幂等；不改旧路径、不改 {@code ClassRow.view}）。
   *
   * <p>它只给未来的显式迁移命令 / 读口调用，生产路径当前无人调用。<b>不调用它时旧档行为逐值不变</b>； 调用它只增加四张新表，不触碰旧结算读取的任何字段。
   *
   * @param data 经济状态；不得为 null
   * @return 新状态为空且旧 {@code classes} 非空时返回播种后的新状态；否则返回入参同一实例（无变更）
   */
  public static EconomyData seedLegacyClassStandings(EconomyData data) {
    Objects.requireNonNull(data, "data");
    if (hasAnyNewState(data)) {
      return data; // ★ 幂等：新状态已非空 ⇒ 不重播、不产差异（同一实例）
    }
    if (data.classes().isEmpty()) {
      return data; // ★ 无事可做：没有旧家户可映射
    }
    Map<HouseholdId, ClassStanding> standings = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : data.classes().entrySet()) {
      ClassPositionId positionId =
          LegacyClassStructure.positionIdOf(entry.getValue().view().stratum());
      standings.put(
          entry.getKey(),
          new ClassStanding(
              entry.getKey(),
              positionId, // original = 旧档当前所属
              positionId, // current  = 同一位置（播种不是一次阶层变迁）
              Map.of(), // ★ 没有模式变迁 ⇒ 无保留比例记录（理由见类注释）
              0L, // ★ E5a：连续债务压力周期从 0 起（旧档/创世没有压力历史；递增/清零在 E5b）
              0L, // ★ 创世/旧档种子没有迁移日
              LegacyClassStructure.SEED_REASON));
    }
    // ★ 逐组件写口：每一段都是 EconomyData 的公开 with*（对侧为空时引用完整性分段放开，四段按依赖顺序落），
    //   不直接改运行对象、不绕过状态构造。播种后的完整状态再过一次全部守卫（键身份、mode↔structure↔position
    //   闭环、standing 的 household/position 存在性）。
    return data.withModes(
            Map.of(LegacyClassStructure.defaultModeId(), LegacyClassStructure.defaultMode()))
        .withClassStructures(
            Map.of(
                LegacyClassStructure.defaultClassStructureId(),
                LegacyClassStructure.defaultClassStructure()))
        .withClassPositions(LegacyClassStructure.defaultClassPositions())
        .withClassStandings(Collections.unmodifiableMap(standings)); // ★ 冻在赋值处
  }

  /** ★ "新状态"是否已有任何一格被写过（四个组件任一非空即视为已播种/已由别的路径写入）。 */
  private static boolean hasAnyNewState(EconomyData data) {
    return !data.modes().isEmpty()
        || !data.classStructures().isEmpty()
        || !data.classPositions().isEmpty()
        || !data.classStandings().isEmpty();
  }
}
