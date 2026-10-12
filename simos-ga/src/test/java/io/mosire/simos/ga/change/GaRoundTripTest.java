package io.mosire.simos.ga.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.MergedEffectPlanId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AdjudicationBreakpoint;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.MergedEffect;
import io.mosire.simos.sd.model.MergedEffectPlan;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.model.Verdict;
import io.mosire.simos.sd.model.VerdictMeta;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.verify.RoundTripAssertions;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>铁律 5 在 ga 切片的机械化落地</b>（2026-10-12 批次 3a 建；批次 3b′ 随 {@code directives} / {@code verdicts}
 * 两张表迁入而扩到 <b>四组件</b>）。形制照 {@code GovRoundTripTest} / {@code SdRoundTripTest}（反射枚举状态组件 → 逐组件造差异 →
 * 三条断言），不另创一套。
 *
 * <p>★★ <b>判别力来自"反射枚举 + 两个 switch 的 default 抛"</b>：往 {@link GaState} 加一个组件而<b>不</b>同步 {@link
 * GaChangeSet} 时，{@link #mutate} 与 {@link #changedOf} 的 {@code default -> throw} 当场响，且 {@link
 * #stateHasExactlyFourComponents} 的 {@code containsExactly} 与 {@link
 * #changeSetCoversEveryStateComponentWithNoExemptions} 的"并集相等"同时红 —— 三处各自独立，不靠"人记得改"。
 *
 * <p>★★ <b>为什么夹具四张表都必须非空</b>：{@link FieldDelta#diff} 把表键经 {@code toString()} 写成规范串、 {@link
 * FieldDelta#rebuild} 再用 {@code DecisionPacketId::parse} / {@code MergedEffectPlanId::parse} 读回 ——
 * 空表 <b>根本不经过键的字面量往返</b>，于是"键读侧缺失"这一类缺陷在空夹具下永远看不见（{@code apply(between(empty, empty), empty)}
 * 恒真）。{@link io.mosire.simos.ga.codec.GaCodec} 只注册这四个键的读侧，漏一个就只在"表非空"时炸 ⇒ 本类因此一律用<b>非空</b>四张表造差异。
 *
 * <p>★ <b>豁免集为空且被单独钉死</b>：{@code GaState} 的四个组件都必须进变更集，本类<b>不设</b>任何豁免项；写成一个被 {@link
 * #theExclusionListIsEmpty} 单独断言的集合，加名字是一次显式动作、在 diff 里现形（照 {@code GovRoundTripTest} 的"豁免口
 * 必须自己现形"口径）。
 */
class GaRoundTripTest {

  private static final DecisionPacketId P1 = new DecisionPacketId("pkt-dm-1-360");
  private static final DecisionPacketId P2 = new DecisionPacketId("pkt-dm-2-360");
  private static final DecisionPacketId P3 = new DecisionPacketId("pkt-dm-3-360");

  private static final MergedEffectPlanId M1 = new MergedEffectPlanId("merge-360-1");
  private static final MergedEffectPlanId M2 = new MergedEffectPlanId("merge-360-2");
  private static final MergedEffectPlanId M3 = new MergedEffectPlanId("merge-360-3");

  private static final DecisionMakerId DM1 = new DecisionMakerId("dm-1");
  private static final DecisionMakerId DM2 = new DecisionMakerId("dm-2");

  private static final DirectiveId D1 = new DirectiveId("d-360-1");
  private static final DirectiveId D2 = new DirectiveId("d-360-2");
  private static final DirectiveId D3 = new DirectiveId("d-361-3");

  private static final VerdictId V1 = new VerdictId("v-360-1");
  private static final VerdictId V2 = new VerdictId("v-360-2");
  private static final VerdictId V3 = new VerdictId("v-361-3");

  private static final SimosTimestamp AT = SimosTimestamp.of(360L, "第 360 日");

  /** ★ 唯一的豁免集合（本轮**没有**任何豁免项）。**加一项就是一次有意的决定，会在 diff 里现形。** */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── ① 反射枚举：逐组件三条断言 ────────────────────────────────────────────────────────

  /**
   * ★ <b>本任务的核心用例</b>：逐组件 ①{@code between} 看见它（变更集非空）②该组件被报成非 {@code Unchanged} ③逐字段重建出
   * target。少任何一条都会留下一类看不见的漂移（三者的分工见 {@code GovRoundTripTest} 的类注释）。
   */
  @Test
  void everyGaStateComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : GaState.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue; // ★ 本轮恒不进入：EXCLUDED 为空，且被 theExclusionListIsEmpty 钉死
      }
      String name = rc.getName();
      GaState base = full();
      GaState target = mutate(base, name);
      GaChangeSet cs = GaChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 该组件的变化看不见）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(GaChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  /** ★ 反射断言：{@code GaState} 的 record 组件**恰四个**、名字逐字相等（声明序）。 */
  @Test
  void stateHasExactlyFourComponents() {
    assertThat(GaState.class.getRecordComponents())
        .as("GaState 恰 4 个组件 —— 多加一个而不改变更集，本断言与往返用例同时红")
        .hasSize(4);
    assertThat(componentNames(GaState.class))
        .as("名字逐字相等（顺序即声明序）")
        .containsExactly("decisionPackets", "mergedEffectPlans", "directives", "verdicts");
  }

  /** ★ 变更集这边同样恰 4 个组件，且与状态组件集**互相包含**（两个方向都钉，不是只钉一边）。 */
  @Test
  void changeSetHasExactlyFourComponentsMatchingTheState() {
    assertThat(GaChangeSet.class.getRecordComponents()).hasSize(4);
    assertThat(componentNames(GaChangeSet.class))
        .as("变更集的每个组件都必须在 GaState 里有同名的 record 组件")
        .isSubsetOf(componentNames(GaState.class));
    assertThat(componentNames(GaState.class))
        .as("反向也成立 ⇒ 两边组件集相同（挡不住'两边同时多出一个同名组件'，故上面另有 hasSize(4)）")
        .isSubsetOf(componentNames(GaChangeSet.class));
  }

  // ── ② 豁免集为空 ──────────────────────────────────────────────────────────────────

  /** ★ 钉死豁免集本身 —— 否则"加字段忘了改"的补救方式会变成"往豁免集里塞一项"，护栏出现一个正好等于新字段大小的洞。 */
  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET)
        .as("GaState 没有豁免项：四张表（决策包 / 合并效果集 / 决策记录 / 判决）都必须进变更集")
        .isEmpty();
  }

  /** ★ 最后一条兜底：变更集组件 ∪ 豁免集必须**恰好**覆盖 {@code GaState} 的全部组件（多一项 = 有组件被豁免掉，少一项 = 有组件谁都没管）。 */
  @Test
  void changeSetCoversEveryStateComponentWithNoExemptions() {
    Set<String> covered = new LinkedHashSet<>(componentNames(GaChangeSet.class));
    covered.addAll(EXCLUDED_FROM_CHANGE_SET);
    assertThat(covered)
        .as("变更集组件 ∪ 豁免集 必须恰好覆盖 GaState 的全部组件")
        .containsExactlyInAnyOrderElementsOf(componentNames(GaState.class));
  }

  // ── ③ 两张表都非空的逐字段往返（含"又删又改"）──────────────────────────────────────────

  /**
   * ★★ <b>非空 → 非空</b>的逐字段往返：目标态对四张表**各删一个键、各改一个键、各加一个键**（{@link FieldDelta.Patch}
   * 的两侧），于是"键读侧"（{@code toString → parse}）、"值覆盖"、"键删除"三条路径全部走到，而不是只走"从空表往里加"。
   *
   * <p>★ 判别力：若 {@code GaChangeSet.apply} 漏重建任一张表、或把 {@code Patch} 的某一侧丢掉，{@code
   * rebuilt.equals(target)} 与下面逐字段的断言都会红（整态 equals 只是最后一条，**不能**是唯一一条 —— 两个空对象也相等）。
   */
  @Test
  void allNonEmptyTablesRoundTripFieldByFieldWithRemovalsAndOverwrites() {
    Map<DecisionPacketId, DecisionPacket> basePackets = new LinkedHashMap<>();
    basePackets.put(P1, packet(P1, DM1, PacketStatus.DRAFT, 0L, "拟稿 A"));
    basePackets.put(P2, packet(P2, DM2, PacketStatus.PENDING, 0L, "拟稿 B"));
    Map<MergedEffectPlanId, MergedEffectPlan> basePlans = new LinkedHashMap<>();
    basePlans.put(M1, plan(M1, 360L, List.of(DM1), "raiseUnit", Optional.empty()));
    basePlans.put(M2, plan(M2, 360L, List.of(DM2), "moveUnit", Optional.empty()));
    Map<VerdictId, Verdict> baseVerdicts = new LinkedHashMap<>();
    baseVerdicts.put(V1, verdict(V1, AdjudicationBreakpoint.parse("D1"), "冻结的判定 v1"));
    baseVerdicts.put(V2, verdict(V2, AdjudicationBreakpoint.parse("D2"), "冻结的判定 v2"));
    Map<DirectiveId, Directive> baseDirectives = new LinkedHashMap<>();
    baseDirectives.put(D1, directive(D1, DM1, 360L, DirectiveStatus.ISSUED, Optional.of(V1)));
    baseDirectives.put(D2, directive(D2, DM2, 360L, DirectiveStatus.CANCELLED, Optional.empty()));
    GaState base = new GaState(basePackets, basePlans, baseDirectives, baseVerdicts);

    // target：P2/M2 从两张表**删掉**、P1/M1 的值**被覆盖**、P3/M3 是**新增**键。
    Map<DecisionPacketId, DecisionPacket> targetPackets = new LinkedHashMap<>();
    targetPackets.put(P1, packet(P1, DM1, PacketStatus.APPROVED, 7L, "拟稿 A 已改写"));
    targetPackets.put(P3, packet(P3, DM1, PacketStatus.MERGED, 9L, "拟稿 C"));
    Map<MergedEffectPlanId, MergedEffectPlan> targetPlans = new LinkedHashMap<>();
    targetPlans.put(M1, plan(M1, 361L, List.of(DM1, DM2), "raiseUnit+moveUnit", Optional.of("{}")));
    targetPlans.put(M3, plan(M3, 363L, List.of(DM2), "disbandUnit", Optional.empty()));
    // 判决：V2 删掉、V1 的 payload 被覆盖、V3 新增；★ 外键仍成立（D1→V1、D3→V3 都在目标态里）。
    Map<VerdictId, Verdict> targetVerdicts = new LinkedHashMap<>();
    targetVerdicts.put(V1, verdict(V1, AdjudicationBreakpoint.parse("D1"), "覆盖后的判定 v1"));
    targetVerdicts.put(V3, verdict(V3, AdjudicationBreakpoint.parse("D3"), "冻结的判定 v3"));
    Map<DirectiveId, Directive> targetDirectives = new LinkedHashMap<>();
    targetDirectives.put(D1, directive(D1, DM1, 360L, DirectiveStatus.EXECUTED, Optional.of(V1)));
    targetDirectives.put(D3, directive(D3, DM2, 361L, DirectiveStatus.ISSUED, Optional.of(V3)));
    GaState target = new GaState(targetPackets, targetPlans, targetDirectives, targetVerdicts);

    GaChangeSet cs = GaChangeSet.between(base, target);

    assertThat(cs.isEmpty()).isFalse();
    assertThat(cs.decisionPackets())
        .as("两张表各自又删又改 ⇒ Patch（两侧都不许丢）")
        .isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.mergedEffectPlans()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.directives()).as("决策记录同样又删又改 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.verdicts()).as("判决同样又删又改 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);

    GaState rebuilt = GaChangeSet.apply(cs, base);

    // ⑤ 先逐字段（整态相等放最后：它自己挡不住"空对空"）
    assertThat(rebuilt.decisionPackets()).containsOnlyKeys(P1, P3);
    assertThat(rebuilt.decisionPackets().get(P1).status())
        .as("P1 状态被覆盖（不是留在 DRAFT）")
        .isEqualTo(PacketStatus.APPROVED);
    assertThat(rebuilt.decisionPackets().get(P1).createdAtRevision()).isEqualTo(7L);
    assertThat(rebuilt.decisionPackets().get(P1).intent()).isEqualTo("拟稿 A 已改写");
    assertThat(rebuilt.decisionPackets().get(P1).calls()).hasSize(2);
    assertThat(rebuilt.decisionPackets().get(P1).calls().get(1).status())
        .as("逐 call 的字段也要活过往返（MERGED + mergedPlanId）")
        .isEqualTo(CallStatus.MERGED);
    assertThat(rebuilt.decisionPackets().get(P1).calls().get(1).mergedPlanId())
        .contains("merge-360-1");
    assertThat(rebuilt.decisionPackets().get(P3)).as("新增键（键读侧真的被解析回来）").isNotNull();
    assertThat(rebuilt.decisionPackets().get(P3).id()).isEqualTo(P3);
    assertThat(rebuilt.decisionPackets().get(P3).proposerId()).isEqualTo(DM1);
    assertThat(rebuilt.decisionPackets()).as("被删掉的 P2 不许留在重建结果里").doesNotContainKey(P2);

    assertThat(rebuilt.mergedEffectPlans()).containsOnlyKeys(M1, M3);
    assertThat(rebuilt.mergedEffectPlans().get(M1).tick()).as("M1 被覆盖（361，不是 360）").isEqualTo(361L);
    assertThat(rebuilt.mergedEffectPlans().get(M1).participantIds()).containsExactly(DM1, DM2);
    assertThat(rebuilt.mergedEffectPlans().get(M1).orderedEffects()).hasSize(2);
    assertThat(rebuilt.mergedEffectPlans().get(M1).outcome()).contains("{}");
    assertThat(rebuilt.mergedEffectPlans().get(M3).sources())
        .as("新增键 M3 的来源引用逐字活过往返")
        .containsExactly("pkt-src-merge-360-3:0", "pkt-src-merge-360-3:1");
    assertThat(rebuilt.mergedEffectPlans()).doesNotContainKey(M2);

    assertThat(rebuilt.directives()).containsOnlyKeys(D1, D3);
    assertThat(rebuilt.directives().get(D1).status())
        .as("D1 状态被覆盖（EXECUTED，不是留在 ISSUED）")
        .isEqualTo(DirectiveStatus.EXECUTED);
    assertThat(rebuilt.directives().get(D1).tick()).isEqualTo(360L);
    assertThat(rebuilt.directives().get(D1).verdict()).as("Optional<VerdictId> 活过往返").contains(V1);
    assertThat(rebuilt.directives().get(D1).intentInfoKey()).isEqualTo("intent");
    assertThat(rebuilt.directives().get(D1).target())
        .as("Optional<Address> 活过往返（canonical 相等）")
        .contains(Address.parse("sd:combat.c1"));
    assertThat(rebuilt.directives().get(D1).commands()).hasSize(2);
    assertThat(rebuilt.directives().get(D1).commands().get(0).type()).isEqualTo("unit.PlanRoute");
    assertThat(rebuilt.directives().get(D1).commands().get(0).payloadJson()).isEqualTo("{\"q\":1}");
    assertThat(rebuilt.directives().get(D1).effects()).containsExactly(new EffectId("e-1"));
    assertThat(rebuilt.directives().get(D3)).as("新增键（键读侧真的被解析回来）").isNotNull();
    assertThat(rebuilt.directives().get(D3).id()).isEqualTo(D3);
    assertThat(rebuilt.directives().get(D3).verdict()).contains(V3);
    assertThat(rebuilt.directives()).as("被删掉的 D2 不许留在重建结果里").doesNotContainKey(D2);

    assertThat(rebuilt.verdicts()).containsOnlyKeys(V1, V3);
    assertThat(rebuilt.verdicts().get(V1).payloadJson()).as("V1 的载荷被覆盖").isEqualTo("覆盖后的判定 v1");
    assertThat(rebuilt.verdicts().get(V1).breakpoint())
        .isEqualTo(AdjudicationBreakpoint.parse("D1"));
    assertThat(rebuilt.verdicts().get(V1).meta().promptVersion()).isEqualTo("p1");
    assertThat(rebuilt.verdicts().get(V1).atRevision()).isEqualTo(new RevisionId(7));
    assertThat(rebuilt.verdicts().get(V3).subject())
        .as("Address 值活过往返")
        .isEqualTo(Address.parse("sd:combat.c3"));
    assertThat(rebuilt.verdicts()).doesNotContainKey(V2);

    assertThat(rebuilt)
        .as("铁律 5 的原文：apply(between(base, target), base) 逐字段重建出 target")
        .isEqualTo(target);
  }

  /** ★ 通用往返断言（util 的 {@code RoundTripAssertions}）：空夹具 → 满夹具（照 {@code GovRoundTripTest} 同款）。 */
  @Test
  void emptyFixtureRoundTripsToTheFullFixture() {
    RoundTripAssertions.assertRoundTrip(
        GaState.empty(), full(), GaChangeSet::between, GaChangeSet::apply);
  }

  // ── ③b codec 的键读侧（本批最容易被"空表"掩盖的一处）──────────────────────────────────

  /**
   * ★★ <b>JSON 往返：四张表都非空</b>。
   *
   * <p>★ <b>为什么这条必须在这里</b>：{@link GaCodec} 的四个键反序列化器（{@code DecisionPacketId} / {@code
   * MergedEffectPlanId} / {@code DirectiveId} / {@code VerdictId}）是<b>读侧</b>注册，写侧靠各类型自带的 {@code
   * toString()}。<b>表空着时这条路径根本不会被走到</b> —— {@code {}} 解出来两个空表，"漏注册"与"注册对了"完全同形。故夹具必须非空，且这里走的是<b>真正的
   * Jackson 编解码</b> （{@link GaChangeSet#between}/{@link GaChangeSet#apply} 那条路走的是 {@code
   * FieldDelta} 的 {@code toString → parse}，与 codec 的键注册是两条独立的路，互相挡不住）。
   *
   * <p>★ 判别力：删掉 {@code GaCodec.keyModule()} 里任一行 {@code addKeyDeserializer} ⇒ 本条当场红（解码期 {@code
   * UnrecognizedPropertyException}/反序列化失败），而其余各条<b>全绿</b>。
   */
  @Test
  void allNonEmptyTablesSurviveTheJacksonRoundTripThroughTheCodec() {
    GaCodec codec = new GaCodec();
    GaState state = full();
    GaSnapshot snapshot =
        new GaSnapshot(new StateRef(new BranchId("main"), new RevisionId(7)), AT, state);

    String snapshotJson = codec.encodeSnapshot(snapshot);
    assertThat(snapshotJson).as("四张表的键真的写进了字节（先证明夹具不是空表）").contains("pkt-dm-1-360");
    assertThat(snapshotJson).contains("merge-360-1");
    assertThat(snapshotJson).contains("d-360-1");
    assertThat(snapshotJson).contains("v-360-1");
    assertThat(codec.decodeSnapshot(snapshotJson))
        .as("快照 JSON 往返：apply/encode 出来的字节必须能被自己逐字段读回（铁律 5）")
        .isEqualTo(snapshot);

    GaChangeSet cs = GaChangeSet.between(GaState.empty(), state);
    String changeSetJson = codec.encodeChangeSet(cs);
    assertThat(changeSetJson).as("变更集里四张表的键也真的写进了字节").contains("pkt-dm-1-360");
    assertThat(changeSetJson).contains("merge-360-1");
    assertThat(changeSetJson).contains("d-360-1");
    assertThat(changeSetJson).contains("v-360-1");
    assertThat(codec.decodeChangeSet(changeSetJson)).as("变更集 JSON 往返（含四个顶层表的键读侧）").isEqualTo(cs);
    assertThat(
            GaChangeSet.apply((GaChangeSet) codec.decodeChangeSet(changeSetJson), GaState.empty()))
        .as("解码回来的变更集必须与原件同语义")
        .isEqualTo(state);
  }

  // ── ④ 变更集自身语义（照 {@code GovRoundTripTest} 的同款）──────────────────────────────

  /** ★ 两侧相同 ⇒ 各组件 {@link FieldDelta.Unchanged}（不是 null、也不是"空对象"），且往返恒等。 */
  @Test
  void identicalStatesProduceAnEmptyChangeSetOfUnchangedComponents() {
    GaState state = full();
    GaChangeSet cs = GaChangeSet.between(state, state);

    assertThat(cs.isEmpty()).as("全相等 ⇒ isEmpty()（读法不是 null）").isTrue();
    assertThat(cs.decisionPackets()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.mergedEffectPlans()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.directives()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.verdicts()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(GaChangeSet.apply(cs, state)).as("一字未动的往返必须恒等").isEqualTo(state);
  }

  /**
   * ★ 变更集里<b>缺组件键</b>（Jackson 绑成 null）⇒ 归一成 {@link FieldDelta.Unchanged}，**此处不抛**。
   *
   * <p>钉的是 {@link GaChangeSet} 紧凑构造器里那条 fail-closed 归一（"没提该组件 = 没动它"）；重建时 {@code Unchanged} 直接返回
   * base、连键解析器都不会被调用。
   */
  @Test
  void aChangeSetWithMissingComponentKeysIsNormalizedToUnchanged() {
    GaChangeSet fromMissingKeys = new GaChangeSet(null, null, null, null);

    assertThat(fromMissingKeys.isEmpty()).as("缺键 ⇒ 四个组件都是 Unchanged").isTrue();
    assertThat(fromMissingKeys.decisionPackets()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.mergedEffectPlans()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.directives()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.verdicts()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(GaChangeSet.apply(fromMissingKeys, full())).as("一字未动的往返必须恒等（不抛）").isEqualTo(full());
  }

  // ── 夹具与反射小工具 ────────────────────────────────────────────────────────────────

  /** 四张表都**非空**的起点：两个决策包 + 两个合并效果集 + 两条决策记录 + 两条判决。 */
  private static GaState full() {
    Map<DecisionPacketId, DecisionPacket> packets = new LinkedHashMap<>();
    packets.put(P1, packet(P1, DM1, PacketStatus.DRAFT, 0L, "拟稿 A"));
    packets.put(P2, packet(P2, DM2, PacketStatus.PENDING, 0L, "拟稿 B"));
    Map<MergedEffectPlanId, MergedEffectPlan> plans = new LinkedHashMap<>();
    plans.put(M1, plan(M1, 360L, List.of(DM1), "raiseUnit", Optional.empty()));
    plans.put(M2, plan(M2, 360L, List.of(DM2), "moveUnit", Optional.empty()));
    Map<VerdictId, Verdict> verdicts = new LinkedHashMap<>();
    verdicts.put(V1, verdict(V1, AdjudicationBreakpoint.parse("D1"), "冻结的判定 v1"));
    verdicts.put(V2, verdict(V2, AdjudicationBreakpoint.parse("D2"), "冻结的判定 v2"));
    Map<DirectiveId, Directive> directives = new LinkedHashMap<>();
    // D1 带 verdict 引用（外键必须真有那条判决 ⇒ 覆盖 GaState 的跨表判据），D2 是终态（不占"末位生效"名额）。
    directives.put(D1, directive(D1, DM1, 360L, DirectiveStatus.ISSUED, Optional.of(V1)));
    directives.put(D2, directive(D2, DM2, 360L, DirectiveStatus.CANCELLED, Optional.empty()));
    return new GaState(packets, plans, directives, verdicts);
  }

  /**
   * 造一个"只在 {@code name} 这个组件上与 {@code base} 不同"的状态。
   *
   * <p>★ <b>{@code default} 必须抛</b>：往 {@link GaState} 加组件时这个 {@code switch} 当场响，**这正是想要的** ——
   * 它迫使加字段的人来读这个测试（照 {@code GovRoundTripTest} 的同一条纪律）。
   */
  private static GaState mutate(GaState base, String name) {
    return switch (name) {
      case "decisionPackets" ->
          base.withDecisionPackets(
              merged(
                  base.decisionPackets(),
                  P2,
                  packet(P2, DM2, PacketStatus.APPROVED, 3L, "拟稿 B 改过")));
      case "mergedEffectPlans" ->
          base.withMergedEffectPlans(
              merged(
                  base.mergedEffectPlans(),
                  M2,
                  plan(M2, 361L, List.of(DM2), "moveUnit2", Optional.of("{}"))));
      case "directives" ->
          base.withDirectives(
              merged(
                  base.directives(),
                  D3,
                  directive(D3, DM1, 361L, DirectiveStatus.CANCELLED, Optional.empty())));
      case "verdicts" ->
          base.withVerdicts(
              merged(
                  base.verdicts(),
                  V2,
                  verdict(V2, AdjudicationBreakpoint.parse("D2"), "refreshed")));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /** 按组件名取出变更集里对应的那一份差异，问它"变了没有"。★ {@code default} 必须抛（同 {@link #mutate}）。 */
  private static boolean changedOf(GaChangeSet cs, String name) {
    return switch (name) {
      case "decisionPackets" -> cs.decisionPackets().changed();
      case "mergedEffectPlans" -> cs.mergedEffectPlans().changed();
      case "directives" -> cs.directives().changed();
      case "verdicts" -> cs.verdicts().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /** 覆写一个键的保序表（同 id 替换 = 只换值、位置不变，与 handler 的整包覆盖同款）。 */
  private static <K, V> Map<K, V> merged(Map<K, V> base, K key, V value) {
    Map<K, V> next = new LinkedHashMap<>(base);
    next.put(key, value);
    return next;
  }

  /** 某个 record 类型的组件名，**按声明序**。 */
  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  // ── 夹具：一个决策包 + 一个合并效果集（键 == 值内身份；callIndex 严格递增）──────────────

  /** 一个决策包：两条 call（第 0 条 PENDING、第 1 条 MERGED 且带 mergedPlanId）。 */
  private static DecisionPacket packet(
      DecisionPacketId id,
      DecisionMakerId proposer,
      PacketStatus status,
      long createdRevision,
      String intent) {
    List<FormattedCall> calls =
        List.of(
            call(0, "simos.unit.raiseUnit", CallStatus.PENDING, Optional.empty()),
            call(1, "simos.unit.moveUnit", CallStatus.MERGED, Optional.of("merge-360-1")));
    return new DecisionPacket(
        id,
        "main",
        360L,
        proposer,
        status,
        intent,
        calls,
        createdRevision,
        Optional.of("gm-1"),
        OptionalLong.of(createdRevision + 1),
        Optional.empty(),
        Optional.of("裁决留痕"));
  }

  /** 一条 call：targets 走跨命名空间的 {@code {namespace,path}} 形状。 */
  private static FormattedCall call(
      int callIndex, String toolName, CallStatus status, Optional<String> mergedPlanId) {
    return new FormattedCall(
        callIndex,
        toolName,
        "{\"q\":1,\"r\":2}",
        List.of(new CommandTarget("map", "Map1/hex/1_2")),
        "{\"preview\":true}",
        List.of("scope-ok"),
        status,
        mergedPlanId,
        Optional.empty());
  }

  /** 一个合并效果集：两条有序效果 + 两条来源引用。 */
  private static MergedEffectPlan plan(
      MergedEffectPlanId id,
      long tick,
      List<DecisionMakerId> participants,
      String toolName,
      Optional<String> outcome) {
    return new MergedEffectPlan(
        id,
        tick,
        participants,
        List.of(
            new MergedEffect(toolName, "{\"q\":1}", List.of(refOf(id) + ":0")),
            new MergedEffect(toolName + "-2", "{\"q\":2}", List.of(refOf(id) + ":1"))),
        List.of(refOf(id) + ":0", refOf(id) + ":1"),
        Optional.empty(),
        outcome);
  }

  /** 计划 id 与来源引用串的确定性映射（夹具用；只求"同 id 同串"）。 */
  private static String refOf(MergedEffectPlanId id) {
    return "pkt-src-" + id.value();
  }

  /**
   * 一条决策记录：两条结构化命令 + 一个效果引用 + {@code target} 用战斗地址（{@code Address} 作值那条绑定）。
   *
   * <p>★ {@code verdict} 非空时，**同态里必须真有那条判决**（{@code GaState} 构造期的跨表判据）——夹具的调用点负责保证。
   */
  private static Directive directive(
      DirectiveId id,
      DecisionMakerId decisionMakerId,
      long tick,
      DirectiveStatus status,
      Optional<VerdictId> verdict) {
    return new Directive(
        id,
        decisionMakerId,
        tick,
        Optional.of(Address.parse("sd:combat.c1")),
        "intent",
        List.of(
            new DirectiveCommand("unit.PlanRoute", "{\"q\":1}"),
            new DirectiveCommand("unit.RaiseUnit", "{\"q\":2}")),
        new LinkedHashSet<>(List.of(new EffectId("e-1"))),
        verdict,
        status);
  }

  /** 一条判决：subject 用 {@code Address} 作值（与 {@code target} 同一条绑定），meta 三字段齐全。 */
  private static Verdict verdict(VerdictId id, AdjudicationBreakpoint breakpoint, String payload) {
    String combat =
        breakpoint == AdjudicationBreakpoint.parse("D1")
            ? "sd:combat.c1"
            : breakpoint == AdjudicationBreakpoint.parse("D2") ? "sd:combat.c2" : "sd:combat.c3";
    return new Verdict(
        id,
        breakpoint,
        Address.parse(combat),
        payload,
        new VerdictMeta("m1", "p1", "digest-" + id.value()),
        new RevisionId(7));
  }
}
