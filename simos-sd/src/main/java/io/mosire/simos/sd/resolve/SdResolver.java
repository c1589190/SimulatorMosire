package io.mosire.simos.sd.resolve;

import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.spi.DecisionTablesLookup;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd:} 命名空间的地址解析器（spec §二.2，N15）。
 *
 * <p>**canonical 形态**：{@code sd:combat.<id>} / {@code sd:combat.<id>:stage.<stageId>} / {@code
 * sd:combat.<id>:stage.<stageId>:outcome.<outcomeId>} / {@code sd:decision-maker.<id>} / {@code
 * sd:directive.<id>} / {@code sd:effect.<id>}。canonical 一律由 {@link Address} AST 构造后 {@link
 * Address#canonical()} 产出，**不手拼**。
 *
 * <p>★★ <b>2026-10-12 批次 3c：{@code nation} 支<b>整支删除</b></b>（用户裁定：{@code sd:nation.<id>}
 * 地址族「<b>直接扔</b>」）—— {@code nations} 表随外交搬进 {@code simos-gov}，本解析器连它的存在性都判不了（{@code sd} 不得依赖 {@code
 * gov}）。 ⇒ 今天 {@code sd:nation.<id>} 一律走 {@code default ->
 * empty()}（<b>空候选</b>，不是抛：那是"这个地址不存在"，与"路径形状非法"同一档）； 国家身份的读面走 gov 自己的面。★ 这是规划 §4 批次 3
 * 列的<b>不可逆点</b>，用户已批。
 *
 * <p>★★ <b>2026-10-12 批次 3d-1：{@code army} 支<b>整支删除</b></b>（同款处置，本批逐字照抄 3c）—— {@code armies}
 * 表与军队身份（{@code Army} / {@code ArmyId}）搬进 {@code simos-army}，而 {@code sd} 不得依赖 {@code army} ⇒ 同理落到
 * {@code default -> empty()}。军队身份的读面走 <b>army 自己的支</b>：{@code army:army.<id>} （见 {@link
 * io.mosire.simos.army.resolve.ArmyResolver}）。★ 这也是规划 §4 列的不可逆点，用户已批（「sd 整个弃用」）。
 *
 * <p>★ **空候选与抛的分工**同 {@code MapResolver}/{@code UnitResolver}：合法但不服务/不存在的 ⇒ 空候选；抛只有一处—— 装配故障（缺 sd
 * 切片、或切片类型不对）。
 *
 * <p>★ 决策人地址**不可用 {@code agent:}**（N15）：那是 AgentLib 的绑定值，不进地址。
 *
 * <p>★ <b>日志（2026-10-23 L2）</b>：只记空候选与装配故障（DEBUG，{@code SD_RESOLVE_*}/{@code
 * SD_RESOLVE_UNAVAILABLE}），**不逐次记成功查询**；地址串是领域 id，不是载荷原文。
 *
 * <p>★★ <b>{@code directive} 与 {@code decision-maker} 两支的存在性判据住 ga</b>（{@code directives} 随 3b-1、
 * {@code decisionMakers} 随 3b-2 搬进 {@code simos-ga}），而 {@code simos-sd} <b>不得</b>依赖 {@code
 * simos-ga}（成环） ⇒ 本类经构造期注入的 {@link DecisionTablesLookup} 问它（实现 {@code GaDecisionTablesLookup} 在
 * ga、组合根 {@code Shell} 接线）。★ <b>地址族串一字不改</b> （{@code sd:directive.<id>} / {@code
 * sd:decision-maker.<id>} 仍是本解析器的 对外形态；地址族的裁量属批次 3e）——变的只是"表住哪个切片"。
 *
 * <p>★ 其余两支（{@code effect}/{@code combat}）仍读 sd 切片，一字未动（{@code effect} 随 3b-4、{@code combat} 随 3d-2
 * 再搬）。
 */
public final class SdResolver implements Resolver {

  private static final String NAMESPACE = "sd";

  private static final LogChannel RESOLVE = EventLog.channel(SdLog.resolve());

  private final DecisionTablesLookup decisionTables;

  /**
   * @param decisionTables ga 侧 {@code directives} / {@code decisionMakers} 两张表的只读口（装配期注入；非
   *     null——缺了它 {@code directive} / {@code decision-maker} 两支无从判存在性）
   */
  public SdResolver(DecisionTablesLookup decisionTables) {
    this.decisionTables = Objects.requireNonNull(decisionTables, "decisionTables");
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public QueryResult resolve(Address address, ResolveContext ctx) {
    Objects.requireNonNull(address, "address");
    Objects.requireNonNull(ctx, "ctx");
    if (!NAMESPACE.equals(address.namespace())) {
      return empty();
    }
    SdState state;
    try {
      state = stateOf(ctx);
    } catch (IllegalArgumentException e) {
      if (RESOLVE.isDebugEnabled()) {
        RESOLVE.debug(
            LogEvent.of(
                "SD_RESOLVE_UNAVAILABLE",
                SdLogSource.SD_RESOLVE,
                "reason",
                "sd 切片缺席或类型不对（装配故障）",
                "address",
                address.canonical()));
      }
      throw e;
    }
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isEmpty()) {
      if (RESOLVE.isDebugEnabled()) {
        RESOLVE.debug(
            LogEvent.of(
                "SD_RESOLVE_EMPTY",
                SdLogSource.SD_RESOLVE,
                "reason",
                "路径形状非法或根段缺 kind",
                "address",
                address.canonical(),
                "segments",
                segments.size()));
      }
      return empty();
    }
    QueryResult result =
        switch (root.kind().get()) {
          // ★ 2026-10-12 批次 3c：`nation` 支已整支删除（用户裁定「sd:nation.<id> 直接扔」）——
          //   nations 表搬进 simos-gov，本解析器判不了它的存在性（sd 不得依赖 gov）⇒ 落到 default -> empty()。
          // ★★ 2026-10-12 批次 3d-1：`army` 支**同款整支删除**（用户裁定「sd 整个弃用！」；本批逐字照抄 3c 对
          //   sd:nation.<id> 的处置）——armies 表与军队身份（Army / ArmyId）搬进 simos-army，而 sd 不得依赖
          //   army ⇒ 判不了"这个军队存在吗"。⇒ 今天 `sd:army.<id>` 一律走 `default -> empty()`
          //   （**空候选**，不是抛：那是"这个地址不存在"，与"路径形状非法"同一档）。
          //   ★ 军队身份的读面改走 army 自己的解析器支：`army:army.<id>`
          //   （见 io.mosire.simos.army.resolve.ArmyResolver 与 ArmyAddresses.ARMY_KIND）。
          //   ★ 这是规划 §4 批次 3 列的**不可逆点**（地址族），用户已批。
          case "decision-maker" ->
              rootOnly(
                  segments,
                  root,
                  SdResolver::parseDecisionMaker,
                  id -> decisionTables.decisionMakers(ctx.state()).containsKey(id),
                  "DecisionMaker");
          case "directive" ->
              rootOnly(
                  segments,
                  root,
                  SdResolver::parseDirective,
                  id -> decisionTables.containsDirective(ctx.state(), id),
                  "Directive");
          case "effect" ->
              rootOnly(
                  segments, root, SdResolver::parseEffect, state.effects()::containsKey, "Effect");
          case "combat" -> resolveCombat(state, segments, root.name());
          default -> empty();
        };
    if (result.candidates().isEmpty() && RESOLVE.isDebugEnabled()) {
      RESOLVE.debug(
          LogEvent.of(
              "SD_RESOLVE_EMPTY",
              SdLogSource.SD_RESOLVE,
              "reason",
              "候选为空（id 非法/实体不存在/路径不受支持）",
              "address",
              address.canonical(),
              "root",
              root.kind().get(),
              "segments",
              segments.size()));
    }
    return result;
  }

  private static QueryResult resolveCombat(
      SdState state, List<AddressSegment> segments, String name) {
    Optional<CombatId> combatId = parseCombat(name);
    if (combatId.isEmpty()) {
      return empty();
    }
    Combat combat = state.combats().get(combatId.get());
    if (combat == null) {
      return empty();
    }
    if (segments.size() == 2) {
      return single("combat." + name, address("combat", name), "Combat");
    }
    if (segments.size() == 3 && isKind(segments.get(2), "stage")) {
      String stageName = ((Entity) segments.get(2)).name();
      CombatStage stage = stageOf(combat, stageName);
      if (stage == null) {
        return empty();
      }
      return single(
          "combat." + name + ":stage." + stageName,
          address("combat", name, "stage", stageName),
          "CombatStage");
    }
    if (segments.size() == 4
        && isKind(segments.get(2), "stage")
        && isKind(segments.get(3), "outcome")) {
      String stageName = ((Entity) segments.get(2)).name();
      String outcomeName = ((Entity) segments.get(3)).name();
      CombatStage stage = stageOf(combat, stageName);
      if (stage == null || !hasOutcome(stage, outcomeName)) {
        return empty();
      }
      return single(
          "combat." + name + ":stage." + stageName + ":outcome." + outcomeName,
          address("combat", name, "stage", stageName, "outcome", outcomeName),
          "CombatOutcome");
    }
    return empty();
  }

  private static <T> QueryResult rootOnly(
      List<AddressSegment> segments,
      Entity root,
      java.util.function.Function<String, Optional<T>> parse,
      java.util.function.Predicate<T> exists,
      String typeName) {
    if (segments.size() != 2) {
      return empty();
    }
    Optional<T> id = parse.apply(root.name());
    if (id.isEmpty() || !exists.test(id.get())) {
      return empty();
    }
    return single(
        root.kind().get() + "." + root.name(), address(root.kind().get(), root.name()), typeName);
  }

  private static Optional<DecisionMakerId> parseDecisionMaker(String text) {
    return safe(() -> DecisionMakerId.parse(text));
  }

  private static Optional<DirectiveId> parseDirective(String text) {
    return safe(() -> DirectiveId.parse(text));
  }

  private static Optional<EffectId> parseEffect(String text) {
    return safe(() -> EffectId.parse(text));
  }

  private static Optional<CombatId> parseCombat(String text) {
    return safe(() -> CombatId.parse(text));
  }

  private static <T> Optional<T> safe(java.util.function.Supplier<T> supplier) {
    try {
      return Optional.of(supplier.get());
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private static boolean isKind(AddressSegment segment, String kind) {
    return segment instanceof Entity entity
        && entity.kind().isPresent()
        && entity.kind().get().equals(kind);
  }

  private static CombatStage stageOf(Combat combat, String stageName) {
    CombatStageId wanted;
    try {
      wanted = CombatStageId.parse(stageName);
    } catch (IllegalArgumentException e) {
      return null;
    }
    for (CombatStage stage : combat.stages()) {
      if (stage.id().equals(wanted)) {
        return stage;
      }
    }
    return null;
  }

  private static boolean hasOutcome(CombatStage stage, String outcomeName) {
    CombatOutcomeId wanted;
    try {
      wanted = CombatOutcomeId.parse(outcomeName);
    } catch (IllegalArgumentException e) {
      return false;
    }
    for (OutcomeOption option : stage.outcomes().options()) {
      if (option.id().equals(wanted)) {
        return true;
      }
    }
    return false;
  }

  private static QueryResult single(String localId, Address canonical, String typeName) {
    return new QueryResult(
        List.of(
            new ResolvedSubject(
                new SubjectId(NAMESPACE, localId), canonical.canonical(), typeName)));
  }

  private static Address address(String kind, String name) {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(kind, name)));
  }

  private static Address address(String kind, String name, String childKind, String childName) {
    return new Address(
        List.of(new Namespace(NAMESPACE), Entity.of(kind, name), Entity.of(childKind, childName)));
  }

  private static Address address(
      String kind,
      String name,
      String childKind,
      String childName,
      String leafKind,
      String leafName) {
    return new Address(
        List.of(
            new Namespace(NAMESPACE),
            Entity.of(kind, name),
            Entity.of(childKind, childName),
            Entity.of(leafKind, leafName)));
  }

  private static QueryResult empty() {
    return new QueryResult(List.of());
  }

  /** 切片只能从 sd 模块拿（铁律 3/4）：缺席或类型不对都是装配故障。 */
  private static SdState stateOf(ResolveContext ctx) {
    Snapshot snapshot =
        ctx.state()
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "状态里没有 sd 模块切片——SdResolver 需要 SdSnapshot（装配故障，不是\"没有候选\"）"));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalArgumentException("sd 模块切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }
}
