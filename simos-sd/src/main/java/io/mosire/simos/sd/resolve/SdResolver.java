package io.mosire.simos.sd.resolve;

import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.AddressSegment;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.Resolver;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code sd:} 命名空间的地址解析器（spec §二.2，N15）。
 *
 * <p>**canonical 形态**：{@code sd:nation.<id>} / {@code sd:army.<id>} / {@code sd:combat.<id>} /
 * {@code sd:combat.<id>:stage.<stageId>} / {@code
 * sd:combat.<id>:stage.<stageId>:outcome.<outcomeId>} / {@code sd:decision-maker.<id>} / {@code
 * sd:directive.<id>} / {@code sd:effect.<id>}。canonical 一律由 {@link Address} AST 构造后 {@link
 * Address#canonical()} 产出，**不手拼**。
 *
 * <p>★ **空候选与抛的分工**同 {@code MapResolver}/{@code UnitResolver}：合法但不服务/不存在的 ⇒ 空候选；抛只有一处—— 装配故障（缺 sd
 * 切片、或切片类型不对）。
 *
 * <p>★ 决策人地址**不可用 {@code agent:}**（N15）：那是 AgentLib 的绑定值，不进地址。
 */
public final class SdResolver implements Resolver {

  private static final String NAMESPACE = "sd";

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
    SdState state = stateOf(ctx);
    List<AddressSegment> segments = address.segments();
    if (!(segments.get(1) instanceof Entity root) || root.kind().isEmpty()) {
      return empty();
    }
    return switch (root.kind().get()) {
      case "nation" ->
          rootOnly(segments, root, SdResolver::parseNation, state.nations()::containsKey, "Nation");
      case "army" ->
          rootOnly(segments, root, SdResolver::parseArmy, state.armies()::containsKey, "Army");
      case "decision-maker" ->
          rootOnly(
              segments,
              root,
              SdResolver::parseDecisionMaker,
              state.decisionMakers()::containsKey,
              "DecisionMaker");
      case "directive" ->
          rootOnly(
              segments,
              root,
              SdResolver::parseDirective,
              state.directives()::containsKey,
              "Directive");
      case "effect" ->
          rootOnly(segments, root, SdResolver::parseEffect, state.effects()::containsKey, "Effect");
      case "combat" -> resolveCombat(state, segments, root.name());
      default -> empty();
    };
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

  private static Optional<NationId> parseNation(String text) {
    return safe(() -> NationId.parse(text));
  }

  private static Optional<ArmyId> parseArmy(String text) {
    return safe(() -> ArmyId.parse(text));
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
