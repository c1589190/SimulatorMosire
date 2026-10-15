package io.mosire.simos.app.access;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.army.ArmyAffiliations;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.ga.spi.DecisionMakerAffiliationPort;
import io.mosire.simos.gov.GovAffiliations;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.NationAffiliations;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * ★★ <b>"这个决策人归属谁"的唯一拼装点</b>（2026-10-12 乙收尾）：把<b>各模块自报的归属表</b>拼成一张 {@code 决策人 →
 * 归属列表}的索引，并向三处提供同一份口径——
 *
 * <ol>
 *   <li>{@link DecisionScopeFunctions}：每个 kind → 该种类的范围函数（**未注册 kind ⇒ 响亮失败**；读不到 ⇒ deny-all）；
 *   <li>{@link DecisionMakerAffiliationPort}（{@code ga-spi}，本类实现）：决策人身份消息的"归属：…"与外交署名；
 *   <li>读侧各消费者（文档可见性、决策人视图、GOV 工具视角）：受众标签集 / 归属类型文本。
 * </ol>
 *
 * <p>★★★ <b>用户口径的兑现点</b>（「<b>归属肯定归模块啊，后面新加模块 ga 还得显式地重新添加？不可能的</b>」）：
 *
 * <ul>
 *   <li>每个种类的<b>名字</b>（{@code KIND}）、<b>表</b>、<b>解释/裁剪</b>、<b>给人看的那句话</b>全部住<b>该模块自己</b> （{@code
 *       NationAffiliations} / {@code GovAffiliations} / {@code ArmyAffiliations}）；
 *   <li>组合根只做一件事：把每个模块的读口登记进 {@link #SOURCES}（<b>加一个新模块 = 各域加一行</b>）；
 *   <li>★ <b>{@code simos-ga} 与 {@code simos-ga-spi} 一个字都不用改</b>——它们只见 {@link
 *       DecisionMakerAffiliationPort} 的"不透明标签 + 一句话"（没有归属种类的枚举、switch、instanceof）。
 * </ul>
 *
 * <p>★ <b>受众标签的唯一拼写点</b>：{@link #label(String, String)} = {@code kind + ":" + ref} （{@code
 * DecisionInfoEntry.affiliations} 里写的就是这串；写入方自报，读取方只做集合成员判定）。
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺切片/类型不对）当场抛（{@link ToolSupport} 的口径）；<b>世界数据</b>问题
 * （没有这个决策人、表里那行指向的实体已不在）降权——各模块的 {@code resolve} 把那些行裁掉，读侧拿不到归属 ⇒ deny-all。
 */
public final class AppDecisionMakerAffiliations implements DecisionMakerAffiliationPort {

  /** 无状态实现（{@code Shell} 注入给决策人运行流与两个外交写工具）。 */
  public static final AppDecisionMakerAffiliations INSTANCE = new AppDecisionMakerAffiliations();

  /**
   * ★★ <b>各模块自报的归属种类</b>——<b>加一个新模块 = 这里加一行</b>（不在 {@code ga} / {@code ga-spi} 里加任何东西）。
   *
   * <p>★ 每一项都引用<b>该模块自己的</b> {@code KIND} 常量与它的读口，故"种类名"全仓只有一处拼写。
   */
  private static final List<KindSource> SOURCES =
      List.of(
          new KindSource(
              NationAffiliations.KIND,
              AppDecisionMakerAffiliations::nationRefs,
              NationAffiliations::describe,
              NationAffiliations::affiliationText,
              true),
          new KindSource(
              GovAffiliations.KIND,
              AppDecisionMakerAffiliations::govRefs,
              GovAffiliations::describe,
              GovAffiliations::affiliationText,
              false),
          new KindSource(
              ArmyAffiliations.KIND,
              AppDecisionMakerAffiliations::armyRefs,
              ArmyAffiliations::describe,
              ArmyAffiliations::affiliationText,
              false));

  private AppDecisionMakerAffiliations() {}

  /**
   * 一个归属种类的读口（**模块自报**）。
   *
   * @param kind 种类名（该模块自己的 {@code KIND} 常量）
   * @param refs 该种类的归属表（决策人 → 实体引用原文；已按模块自己的权威裁剪过不存在/形状坏的行）
   * @param describe 该种类的"一句人话"（提示语用）
   * @param affiliationText 收窄前 {@code Affiliation.toString()} 的原文（工具拒因用；逐字保留对外文本）
   * @param diplomatic 本种类是不是"外交署名身份"的来源（组合根的表态：外交是 gov 的 nation 那一轴）
   */
  private record KindSource(
      String kind,
      Function<SimulationState, Map<DecisionMakerId, String>> refs,
      Function<String, String> describe,
      Function<String, String> affiliationText,
      boolean diplomatic) {}

  /** ★★ <b>受众标签的唯一拼写点</b>：{@code kind + ":" + ref}（写入方自报的串与读侧的判定串都取自这里）。 */
  public static String label(String kind, String ref) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(ref, "ref");
    return kind + ":" + ref;
  }

  /**
   * 该决策人的一条归属（**app 内部形态**：带种类名；对外的 {@link AffiliationClaim} 刻意不带种类）。
   *
   * @param kind 种类名（该模块自己的 {@code KIND}）
   * @param ref 实体引用原文
   * @param description 给人看的一句话（模块渲染）
   * @param affiliationText 收窄前 {@code Affiliation.toString()} 原文（工具拒因用）
   */
  public record Claim(String kind, String ref, String description, String affiliationText) {

    /** 受众标签（{@code kind:ref}；见 {@link #label}）。 */
    public String label() {
      return AppDecisionMakerAffiliations.label(kind, ref);
    }
  }

  /**
   * 该决策人此刻的<b>全部归属</b>（按 kind 的登记序；同一个决策人可能被多个模块同时认领）。
   *
   * <p>★ 空 = 裸决策人（用户原话：「和 Unit 一样，不但允许创建没有更多附加状态的单个决策人」）。
   */
  public static List<Claim> claimsOf(DecisionMakerId decisionMaker, SimulationState state) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    Objects.requireNonNull(state, "state");
    List<Claim> claims = new ArrayList<>();
    for (KindSource source : SOURCES) {
      String ref = source.refs().apply(state).get(decisionMaker);
      if (ref != null) {
        claims.add(
            new Claim(
                source.kind(),
                ref,
                source.describe().apply(ref),
                source.affiliationText().apply(ref)));
      }
    }
    return List.copyOf(claims);
  }

  /** 该决策人此刻命中的<b>种类名</b>（{@link DecisionScopeFunctions} 的分派依据；顺序 = 登记序）。 */
  public static Set<String> kindsOf(DecisionMakerId decisionMaker, SimulationState state) {
    Set<String> kinds = new LinkedHashSet<>();
    for (Claim claim : claimsOf(decisionMaker, state)) {
      kinds.add(claim.kind());
    }
    return Set.copyOf(kinds);
  }

  /** 该决策人此刻的<b>受众标签集</b>（{@code DecisionInfoEntry.affiliations} 用的那一套串）。 */
  public static Set<String> labelsOf(DecisionMakerId decisionMaker, SimulationState state) {
    Set<String> labels = new LinkedHashSet<>();
    for (Claim claim : claimsOf(decisionMaker, state)) {
      labels.add(claim.label());
    }
    return Set.copyOf(labels);
  }

  /**
   * 该决策人的<b>归属类型文本</b>（收窄前 {@code Affiliation.toString()} 的原样拼接，供工具拒因/告警文案用）。 多轴时按登记序用 {@code +}
   * 连接；没有归属 ⇒ 空串（调用方自己决定怎么说"没有归属"）。
   */
  public static String affiliationTextOf(DecisionMakerId decisionMaker, SimulationState state) {
    StringBuilder text = new StringBuilder();
    for (Claim claim : claimsOf(decisionMaker, state)) {
      text.append(claim.affiliationText());
    }
    return text.toString();
  }

  /** 全世界的归属索引（**一次性**读齐，供 GUI/查询面按需使用）：决策人 → 它的全部归属。 */
  public static Map<DecisionMakerId, List<Claim>> indexOf(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Map<DecisionMakerId, List<Claim>> index = new LinkedHashMap<>();
    for (KindSource source : SOURCES) {
      for (Map.Entry<DecisionMakerId, String> entry : source.refs().apply(state).entrySet()) {
        index
            .computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>())
            .add(
                new Claim(
                    source.kind(),
                    entry.getValue(),
                    source.describe().apply(entry.getValue()),
                    source.affiliationText().apply(entry.getValue())));
      }
    }
    Map<DecisionMakerId, List<Claim>> frozen = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, List<Claim>> entry : index.entrySet()) {
      frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
    }
    return Map.copyOf(frozen);
  }

  // ── DecisionMakerAffiliationPort（ga-spi）：ga 只见"不透明标签 + 一句人话" ──────────────────────

  @Override
  public List<AffiliationClaim> affiliationsOf(
      DecisionMakerId decisionMaker, SimulationState state) {
    List<AffiliationClaim> out = new ArrayList<>();
    for (Claim claim : claimsOf(decisionMaker, state)) {
      out.add(new AffiliationClaim(claim.label(), claim.description()));
    }
    return List.copyOf(out);
  }

  /**
   * ★ <b>外交署名身份</b>：{@link KindSource#diplomatic()} 为真的那个种类的引用原文（今天 = gov 的 nation 那一轴）。
   *
   * <p>★ 这个"哪一轴算外交身份"的表态住<b>组合根</b>（各模块自报"我这一轴是什么"，app 决定它算不算外交）⇒ ga 侧不需要任何归属种类字样。
   */
  @Override
  public Optional<String> diplomaticSignatoryOf(
      DecisionMakerId decisionMaker, SimulationState state) {
    Objects.requireNonNull(decisionMaker, "decisionMaker");
    Objects.requireNonNull(state, "state");
    for (KindSource source : SOURCES) {
      if (!source.diplomatic()) {
        continue;
      }
      String ref = source.refs().apply(state).get(decisionMaker);
      if (ref != null) {
        return Optional.of(ref);
      }
    }
    return Optional.empty();
  }

  // ── 各模块的读口（每一项都只做"取表 + 交给该模块自己的 resolve 裁剪 + 取裸值"）──────────────

  private static Map<DecisionMakerId, String> nationRefs(SimulationState state) {
    GovState gov = ToolSupport.govState(state);
    NationAffiliations resolved =
        NationAffiliations.resolve(gov.nationAffiliations(), gov.nations());
    return refs(resolved.byDecisionMaker(), NationId::value);
  }

  private static Map<DecisionMakerId, String> govRefs(SimulationState state) {
    GovState gov = ToolSupport.govState(state);
    GovAffiliations resolved =
        GovAffiliations.resolve(gov.govAffiliations(), ToolSupport.unitState(state).units());
    return refs(resolved.byDecisionMaker(), UnitId::value);
  }

  private static Map<DecisionMakerId, String> armyRefs(SimulationState state) {
    io.mosire.simos.army.ArmyData army = ToolSupport.armyData(state);
    ArmyAffiliations resolved = ArmyAffiliations.resolve(army.armyAffiliations(), army.armies());
    return refs(resolved.byDecisionMaker(), ArmyId::value);
  }

  /** 保序不可变地取裸值（{@code value()}，**不是** {@code toString()}）。 */
  private static <T> Map<DecisionMakerId, String> refs(
      Map<DecisionMakerId, T> table, Function<T, String> rawValue) {
    Map<DecisionMakerId, String> out = new LinkedHashMap<>();
    for (Map.Entry<DecisionMakerId, T> entry : table.entrySet()) {
      out.put(entry.getKey(), rawValue.apply(entry.getValue()));
    }
    return Map.copyOf(out);
  }
}
