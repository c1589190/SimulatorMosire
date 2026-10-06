package io.mosire.simos.sd.testing;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Action;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.CasualtyDelta;
import io.mosire.simos.sd.model.CasualtySpec;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.Effect;
import io.mosire.simos.sd.model.EffectKind;
import io.mosire.simos.sd.model.EffectStatus;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.LossClass;
import io.mosire.simos.sd.model.LossRecord;
import io.mosire.simos.sd.model.MergedEffect;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.model.OutcomeOption;
import io.mosire.simos.sd.model.OutcomeTable;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.model.Trigger;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.model.VerdictMeta;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** A3 的共享测试夹具：一份**十四个组件全非空**的合法 {@link SdState}，以及逐组件变体。 */
public final class SdFixtures {

  public static final SimosTimestamp T0 = SimosTimestamp.of(0);

  public static final RevisionId REV1 = new RevisionId(1);

  public static final NationId N1 = new NationId("n1");
  public static final NationId N2 = new NationId("n2");
  public static final ArmyId A1 = new ArmyId("a1");
  public static final ArmyId A2 = new ArmyId("a2");
  public static final CombatId C1 = new CombatId("c1");
  public static final CombatId C2 = new CombatId("c2");
  public static final CombatStageId S1 = new CombatStageId("s1");
  public static final CombatStageId S2 = new CombatStageId("s2");
  public static final CombatStateId CS1 = new CombatStateId("cs1");
  public static final CombatStateId CS2 = new CombatStateId("cs2");
  public static final CombatOutcomeId O1 = new CombatOutcomeId("o1");
  public static final CombatOutcomeId O2 = new CombatOutcomeId("o2");
  public static final DecisionMakerId DM1 = new DecisionMakerId("dm1");
  public static final DecisionMakerId DM2 = new DecisionMakerId("dm2");
  public static final DirectiveId D1 = new DirectiveId("d1");
  public static final DirectiveId D2 = new DirectiveId("d2");
  public static final EffectId E1 = new EffectId("e1");
  public static final EffectId E2 = new EffectId("e2");
  public static final VerdictId V1 = new VerdictId("v1");
  public static final VerdictId V2 = new VerdictId("v2");
  public static final LossRecordId L1 = new LossRecordId("l1");
  public static final LossRecordId L2 = new LossRecordId("l2");
  public static final DiplomaticRelationKey DR12 = new DiplomaticRelationKey(N1, N2);
  public static final DiplomaticRelationKey DR21 = new DiplomaticRelationKey(N2, N1);
  public static final DiplomaticEventId DE1 = new DiplomaticEventId("de1");
  public static final DiplomaticEventId DE2 = new DiplomaticEventId("de2");
  public static final DecisionPacketId P1 = new DecisionPacketId("pkt-dm1-0");
  public static final DecisionPacketId P2 = new DecisionPacketId("pkt-dm2-0");
  public static final MergedEffectPlanId MP1 = new MergedEffectPlanId("merge-0-1");
  public static final MergedEffectPlanId MP2 = new MergedEffectPlanId("merge-0-2");
  public static final UnitId U1 = new UnitId("u-1");
  public static final RegionId R1 = new RegionId("r1");
  public static final RegionId R2 = new RegionId("r2");
  public static final Address SUBJECT = Address.parse("map:Map1:region.r1");
  public static final Address INFO_ADDR = Address.parse("map:Map1");

  private SdFixtures() {}

  public static SdState empty() {
    return SdState.empty();
  }

  /** 十四个组件全非空的合法状态。 */
  public static SdState full() {
    return new SdState(
        Map.of(N1, nation(N1, R1), N2, nation(N2, R2)),
        Map.of(A1, army(A1, N1), A2, army(A2, N1)),
        Map.of(C1, combat(C1), C2, combat(C2)),
        Map.of(
            CS1,
            combatState(CS1, C1, Optional.empty()),
            CS2,
            combatState(CS2, C1, Optional.empty())),
        Map.of(DM1, decisionMaker(DM1), DM2, decisionMaker(DM2)),
        Map.of(D1, directive(D1, DM1, 0), D2, directive(D2, DM1, 1)),
        Map.of(E1, effect(E1), E2, effect(E2)),
        Map.of(V1, verdict(V1), V2, verdict(V2)),
        Map.of(L1, lossRecord(L1), L2, lossRecord(L2)),
        Map.of(INFO_ADDR.canonical(), List.of(infoEntry("k1"))),
        Map.of(
            DR12,
            new DiplomaticRelation(Optional.of("称臣纳贡"), "N1 向 N2 称臣纳贡（夹具）", 7L),
            DR21,
            new DiplomaticRelation(Optional.empty(), "N2 与 N1 互市（夹具）", 9L)),
        Map.of(
            DE1,
            new DiplomaticEvent(DE1, 7L, List.of(N1, N2), "N1 与 N2 谈判（夹具）"),
            DE2,
            new DiplomaticEvent(DE2, 9L, List.of(N2, N1), "N2 与 N1 联动裁决（夹具）")),
        Map.of(P1, draftPacket(P1, DM1), P2, mergedPacket(P2, DM2)),
        Map.of(MP1, mergedEffectPlan(MP1, false), MP2, mergedEffectPlan(MP2, true)));
  }

  /** 在 {@code base} 上只让指定组件多出一个条目；其余组件原样。 */
  public static SdState mutated(SdState base, String component) {
    return switch (component) {
      case "nations" -> {
        Map<NationId, Nation> next = new LinkedHashMap<>(base.nations());
        next.put(new NationId("n-extra"), nation(new NationId("n-extra"), new RegionId("r-extra")));
        yield base.withNations(next);
      }
      case "armies" -> {
        Map<ArmyId, Army> next = new LinkedHashMap<>(base.armies());
        next.put(new ArmyId("a-extra"), army(new ArmyId("a-extra"), N1));
        yield base.withArmies(next);
      }
      case "combats" -> {
        Map<CombatId, Combat> next = new LinkedHashMap<>(base.combats());
        next.put(new CombatId("c-extra"), combat(new CombatId("c-extra")));
        yield base.withCombats(next);
      }
      case "combatStates" -> {
        Map<CombatStateId, CombatState> next = new LinkedHashMap<>(base.combatStates());
        next.put(
            new CombatStateId("cs-extra"),
            combatState(new CombatStateId("cs-extra"), C1, Optional.empty()));
        yield base.withCombatStates(next);
      }
      case "decisionMakers" -> {
        Map<DecisionMakerId, DecisionMaker> next = new LinkedHashMap<>(base.decisionMakers());
        next.put(new DecisionMakerId("dm-extra"), decisionMaker(new DecisionMakerId("dm-extra")));
        yield base.withDecisionMakers(next);
      }
      case "directives" -> {
        Map<DirectiveId, Directive> next = new LinkedHashMap<>(base.directives());
        next.put(new DirectiveId("d-extra"), directive(new DirectiveId("d-extra"), DM1, 2));
        yield base.withDirectives(next);
      }
      case "effects" -> {
        Map<EffectId, Effect> next = new LinkedHashMap<>(base.effects());
        next.put(new EffectId("e-extra"), effect(new EffectId("e-extra")));
        yield base.withEffects(next);
      }
      case "verdicts" -> {
        Map<VerdictId, Verdict> next = new LinkedHashMap<>(base.verdicts());
        next.put(new VerdictId("v-extra"), verdict(new VerdictId("v-extra")));
        yield base.withVerdicts(next);
      }
      case "lossRecords" -> {
        Map<LossRecordId, LossRecord> next = new LinkedHashMap<>(base.lossRecords());
        next.put(new LossRecordId("l-extra"), lossRecord(new LossRecordId("l-extra")));
        yield base.withLossRecords(next);
      }
      case "info" -> {
        Map<String, List<SdInfoEntry>> next = new LinkedHashMap<>(base.info());
        next.put("unit:u-1", List.of(infoEntry("k2")));
        yield base.withInfo(next);
      }
      case "diplomaticRelations" -> {
        Map<DiplomaticRelationKey, DiplomaticRelation> next =
            new LinkedHashMap<>(base.diplomaticRelations());
        next.put(
            new DiplomaticRelationKey(N1, new NationId("n-extra")),
            new DiplomaticRelation(Optional.of("互市"), "新增外交边（夹具）", 11L));
        yield base.withDiplomaticRelations(next);
      }
      case "diplomaticEvents" -> {
        Map<DiplomaticEventId, DiplomaticEvent> next = new LinkedHashMap<>(base.diplomaticEvents());
        DiplomaticEventId extra = new DiplomaticEventId("de-extra");
        next.put(extra, new DiplomaticEvent(extra, 11L, List.of(N1, N2), "追加外交事件（夹具）"));
        yield base.withDiplomaticEvents(next);
      }
      case "decisionPackets" -> {
        Map<DecisionPacketId, DecisionPacket> next = new LinkedHashMap<>(base.decisionPackets());
        DecisionPacketId extra = new DecisionPacketId("pkt-extra");
        next.put(extra, draftPacket(extra, DM1));
        yield base.withDecisionPackets(next);
      }
      case "mergedEffectPlans" -> {
        Map<MergedEffectPlanId, MergedEffectPlan> next =
            new LinkedHashMap<>(base.mergedEffectPlans());
        MergedEffectPlanId extra = new MergedEffectPlanId("merge-extra");
        next.put(extra, mergedEffectPlan(extra, false));
        yield base.withMergedEffectPlans(next);
      }
      default -> throw new IllegalStateException("未登记的组件: " + component);
    };
  }

  /** 一条 DRAFT 决策包（夹具）：两条 PENDING call，未裁决。 */
  public static DecisionPacket draftPacket(DecisionPacketId id, DecisionMakerId proposer) {
    return new DecisionPacket(
        id,
        "main",
        0L,
        proposer,
        PacketStatus.DRAFT,
        "夹具意图-" + id.value(),
        List.of(
            new FormattedCall(
                0,
                "simos.unit.raiseUnit",
                "{\"unit\":\"u-1\"}",
                List.of(new CommandTarget("unit", "u-1")),
                "{\"preview\":\"新增\"}",
                List.of("scope-ok", "preview-ok"),
                CallStatus.PENDING,
                Optional.empty(),
                Optional.empty()),
            new FormattedCall(
                1,
                "sd.PutInfo",
                "{\"key\":\"k1\"}",
                List.of(new CommandTarget("sd", "nation/n1")),
                "{}",
                List.of("scope-ok"),
                CallStatus.PENDING,
                Optional.empty(),
                Optional.empty())),
        1L,
        Optional.empty(),
        OptionalLong.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /** 一条已 MERGED 的决策包（夹具）：call 0 并入 {@link #MP1} 且带执行摘要，call 1 已批准。 */
  public static DecisionPacket mergedPacket(DecisionPacketId id, DecisionMakerId proposer) {
    return new DecisionPacket(
        id,
        "main",
        0L,
        proposer,
        PacketStatus.MERGED,
        "夹具合并意图-" + id.value(),
        List.of(
            new FormattedCall(
                0,
                "simos.unit.raiseUnit",
                "{\"unit\":\"u-1\"}",
                List.of(new CommandTarget("unit", "u-1")),
                "{\"preview\":\"新增\"}",
                List.of("scope-ok", "preview-ok"),
                CallStatus.MERGED,
                Optional.of(MP1.value()),
                Optional.of("{\"applied\":true}")),
            new FormattedCall(
                1,
                "sd.PutInfo",
                "{\"key\":\"k2\"}",
                List.of(new CommandTarget("sd", "nation/n2")),
                "{}",
                List.of("scope-ok"),
                CallStatus.APPROVED,
                Optional.empty(),
                Optional.empty())),
        2L,
        Optional.of("gm"),
        OptionalLong.of(5L),
        Optional.of("info-reason"),
        Optional.of("并入合并集"));
  }

  /** 合并效果集（夹具）：{@code executed=true} 的带 outcome 回写、{@code false} 的未执行。 */
  public static MergedEffectPlan mergedEffectPlan(MergedEffectPlanId id, boolean executed) {
    return new MergedEffectPlan(
        id,
        0L,
        List.of(DM1, DM2),
        List.of(
            new MergedEffect(
                "simos.unit.raiseUnit", "{\"unit\":\"u-1\"}", List.of(P1.value() + ":0"))),
        List.of(P1.value() + ":0"),
        Optional.of("info-merge"),
        executed ? Optional.of("{\"applied\":true}") : Optional.empty());
  }

  public static Nation nation(NationId id, RegionId region) {
    return new Nation(id, "nation-" + id, region, 10);
  }

  public static Army army(ArmyId id, NationId nation) {
    return new Army(id, nation, U1, "army-" + id);
  }

  public static Combat combat(CombatId id) {
    CombatStage s1 =
        new CombatStage(
            S1,
            "s1",
            Set.of(U1),
            List.of(new Trigger.AtOrAfterTick(0)),
            List.of(new Trigger.AtOrAfterTick(5)),
            0,
            10,
            new OutcomeTable(List.of(option(O1))));
    CombatStage s2 =
        new CombatStage(
            S2,
            "s2",
            Set.of(U1),
            List.of(new Trigger.AtOrAfterTick(5)),
            List.of(new Trigger.AtOrAfterTick(9)),
            0,
            10,
            new OutcomeTable(List.of(option(O2))));
    return new Combat(id, "combat-" + id, List.of(s1, s2), Set.of(U1), Optional.empty());
  }

  public static CombatState combatState(
      CombatStateId id, CombatId combat, Optional<CombatOutcomeId> outcome) {
    return new CombatState(id, combat, S1, new HexCoord(0, 0), Set.of(U1), outcome, Set.of());
  }

  public static DecisionMaker decisionMaker(DecisionMakerId id) {
    return new DecisionMaker(
        id, new Affiliation.Nation(N1), Set.of("sd.SubmitVerdict"), AccessLimit.empty(), 1);
  }

  /**
   * 会话世代非零的决策人（往返夹具）：{@code conversationGeneration} 是"重置为新会话"那条命令写的世界事实。
   *
   * <p>★ **必须单独有这个夹具**：世代 0 是**每一个**旧夹具的缺省值，故"世代被打通"这件事在缺省夹具下**看不见**。
   */
  public static DecisionMaker decisionMakerAtGeneration(DecisionMakerId id, long generation) {
    return new DecisionMaker(
        id,
        new Affiliation.Nation(N1),
        Set.of("sd.SubmitVerdict"),
        AccessLimit.empty(),
        1,
        Optional.empty(),
        generation);
  }

  /** 绑定了 LLM provider 引用的决策人（M11 往返夹具）：世代 0。 */
  public static DecisionMaker boundDecisionMaker(DecisionMakerId id, String providerId) {
    return new DecisionMaker(
        id,
        new Affiliation.Nation(N1),
        Set.of("sd.SubmitVerdict"),
        AccessLimit.empty(),
        1,
        Optional.of(providerId),
        0L);
  }

  public static Directive directive(DirectiveId id, DecisionMakerId dm, long tick) {
    return new Directive(
        id,
        dm,
        tick,
        Optional.of(SUBJECT),
        "intent-" + id,
        List.of(),
        Set.of(),
        Optional.empty(),
        DirectiveStatus.PLANNED);
  }

  public static Effect effect(EffectId id) {
    return new Effect(
        id,
        EffectKind.SCHEDULED,
        new Trigger.AtOrAfterTick(5),
        new Action.PutInfo(INFO_ADDR, "k", "v"),
        EffectStatus.PLANNED,
        0);
  }

  public static Verdict verdict(VerdictId id) {
    return new Verdict(
        id,
        new AdjudicationBreakpoint("D1"),
        SUBJECT,
        "{}",
        new VerdictMeta("model", "prompt-1", "digest"),
        REV1);
  }

  public static LossRecord lossRecord(LossRecordId id) {
    return new LossRecord(
        id,
        C1,
        S1,
        REV1,
        List.of(new CasualtyDelta(U1, -1, Map.of("tank", -1), LossClass.PERMANENT)));
  }

  public static SdInfoEntry infoEntry(String key) {
    // ★ 决策结果三件套（第 3 波第 1 步）：夹具给**非平凡**的 id / tick / tags——缺省（空集 / 0）看不见
    //   "这几个字段被线格式/变更集打通没有"，故 tags 挂 DM1（真往返一个 DecisionMakerId 值）。
    return new SdInfoEntry(
        new SdInfoId("info-" + key),
        0L,
        Set.of(DM1),
        Set.of(),
        key,
        "value-" + key,
        Optional.of("note"),
        REV1,
        Optional.empty(),
        Optional.empty());
  }

  public static OutcomeOption option(CombatOutcomeId id) {
    return new OutcomeOption(id, "label-" + id, 1, new CasualtySpec(0, Map.of()));
  }
}
