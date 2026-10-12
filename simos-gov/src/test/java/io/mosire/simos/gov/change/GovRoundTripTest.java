package io.mosire.simos.gov.change;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.gov.GovOfficeState;
import io.mosire.simos.gov.GovRemittanceState;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.gov.nation.DiplomaticEvent;
import io.mosire.simos.gov.nation.DiplomaticEventId;
import io.mosire.simos.gov.nation.DiplomaticRelation;
import io.mosire.simos.gov.nation.DiplomaticRelationKey;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.verify.RoundTripAssertions;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ <b>铁律 5 在 gov 切片的机械化落地</b>（本仓原先唯一没有往返护栏的切片：{@code find simos-gov/src/test -name '*RoundTrip*'}
 * = 0）。形制照 {@code SdRoundTripTest} / {@code MapRoundTripComponentsTest} （反射枚举状态组件 → 逐组件造差异 →
 * 三条断言），不另创一套。
 *
 * <p>★★ <b>判别力来自"反射枚举 + 两个 switch 的 default 抛"</b>：往 {@link GovState} 加一个组件而<b>不</b>同步 {@link
 * GovChangeSet} 时，{@link #mutate} 与 {@link #changedOf} 的 {@code default -> throw} 当场响，且 {@link
 * #stateHasExactlyFiveComponentsIncludingTheDiplomaticTables} 的 {@code containsExactly} 与 {@link
 * #changeSetCoversEveryStateComponentWithNoExemptions} 的"并集相等"同时红 —— 三处 各自独立，不靠"人记得改"。
 *
 * <p>★★ <b>为什么夹具每张表都必须非空</b>：{@link FieldDelta#diff} 把表键经 {@code toString()} 写成规范串、 {@link
 * FieldDelta#rebuild} 再用各自的 {@code parse} 读回 —— 空表<b>根本不经过键的字面量往返</b>，
 * 于是"键读侧缺失"这一类缺陷在空夹具下永远看不见（{@code apply(between(empty, empty), empty)} 恒真）。本类因此一律用<b>非空</b> 五张表造差异。
 *
 * <p>★ <b>豁免集为空且被单独钉死</b>：{@code GovState} 的五个组件都必须进变更集，本类<b>不设</b>任何豁免项；写成一个被 {@link
 * #theExclusionListIsEmpty} 单独断言的集合，加名字是一次显式动作、在 diff 里现形（照 {@code RoundTripComponentsTest} 的
 * "豁免口必须自己现形"口径）。
 *
 * <p>★★ <b>2026-10-12 批次 3c 的同批扩写（0a 建立的护栏，§12.3 例外允许改本文件）</b>：{@code GovState} 2 → 5 组件（外交三表自
 * {@code simos-sd} 迁入）⇒ ①组件数/名字断言 2 → 5；②{@code mutate}/{@code changedOf} 各加三支；③非空夹具与"又删又改又加"往返扩到
 * 外交三表；④<b>新增一条 codec 的 JSON 往返</b>（{@link #govCodecJsonRoundTripsAllFiveTables}）——本批在 {@code
 * GovCodec} 新注册了 <b>三个键反序列化器</b>（{@code NationId} / {@code DiplomaticRelationKey} / {@code
 * DiplomaticEventId}），而"键读侧注册漏了"只在 <b>对应表非空</b>时才会炸（{@code SdCodec}/{@code GovCodec} 类注的同一条教训）⇒
 * 必须有一条真过 JSON 的用例把守。
 */
class GovRoundTripTest {

  private static final SimosTimestamp AT = new SimosTimestamp(120L, Optional.empty());

  private static final UnitId U1 = new UnitId("gov-1");
  private static final UnitId U2 = new UnitId("gov-2");
  private static final UnitId U3 = new UnitId("gov-3");

  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  private static final NationId N1 = new NationId("大蜀");
  private static final NationId N2 = new NationId("西陵");
  private static final NationId N3 = new NationId("南越");
  private static final RegionId R1 = new RegionId("大蜀-京畿");
  private static final RegionId R2 = new RegionId("西陵-京畿");

  private static final DiplomaticEventId E1 = new DiplomaticEventId("e-1");
  private static final DiplomaticEventId E2 = new DiplomaticEventId("e-2");
  private static final DiplomaticEventId E3 = new DiplomaticEventId("e-3");

  /** ★ 唯一的豁免集合（本轮**没有**任何豁免项）。**加一项就是一次有意的决定，会在 diff 里现形。** */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── ① 反射枚举：逐组件三条断言 ────────────────────────────────────────────────────────

  /**
   * ★ <b>本任务的核心用例</b>：逐组件 ①{@code between} 看见它（变更集非空）②该组件被报成非 {@code Unchanged} ③逐字段重建出
   * target。少任何一条都会留下一类看不见的漂移（三者的分工见 {@code RoundTripComponentsTest} 的类注释）。
   */
  @Test
  void everyGovStateComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : GovState.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue; // ★ 本轮恒不进入：EXCLUDED 为空，且被 theExclusionListIsEmpty 钉死
      }
      String name = rc.getName();
      GovState base = full();
      GovState target = mutate(base, name);
      GovChangeSet cs = GovChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 该组件的变化看不见）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(GovChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  /** ★ 反射断言：{@code GovState} 的 record 组件**恰五个**、名字逐字相等（声明序）。 */
  @Test
  void stateHasExactlyFiveComponentsIncludingTheDiplomaticTables() {
    assertThat(GovState.class.getRecordComponents())
        .as("GovState 恰 5 个组件 —— 多加一个而不改变更集，本断言与往返用例同时红")
        .hasSize(5);
    assertThat(componentNames(GovState.class))
        .as("名字逐字相等（顺序即声明序）")
        .containsExactly(
            "offices", "remittanceStates", "nations", "diplomaticRelations", "diplomaticEvents");
  }

  /** ★ 变更集这边同样恰 5 个组件，且与状态组件集**互相包含**（两个方向都钉，不是只钉一边）。 */
  @Test
  void changeSetHasExactlyFiveComponentsMatchingTheState() {
    assertThat(GovChangeSet.class.getRecordComponents()).hasSize(5);
    assertThat(componentNames(GovChangeSet.class))
        .as("变更集的每个组件都必须在 GovState 里有同名的 record 组件")
        .isSubsetOf(componentNames(GovState.class));
    assertThat(componentNames(GovState.class))
        .as("反向也成立 ⇒ 两边组件集相同（挡不住'两边同时多出一个同名组件'，故上面另有 hasSize(5)）")
        .isSubsetOf(componentNames(GovChangeSet.class));
  }

  // ── ② 豁免集为空 ──────────────────────────────────────────────────────────────────

  /** ★ 钉死豁免集本身 —— 否则"加字段忘了改"的补救方式会变成"往豁免集里塞一项"，护栏出现一个正好等于新字段大小的洞。 */
  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("GovState 没有豁免项：五张表都必须进变更集").isEmpty();
  }

  /** ★ 最后一条兜底：变更集组件 ∪ 豁免集必须**恰好**覆盖 {@code GovState} 的全部组件（多一项 = 有组件被豁免掉，少一项 = 有组件谁都没管）。 */
  @Test
  void changeSetCoversEveryStateComponentWithNoExemptions() {
    Set<String> covered = new LinkedHashSet<>(componentNames(GovChangeSet.class));
    covered.addAll(EXCLUDED_FROM_CHANGE_SET);
    assertThat(covered)
        .as("变更集组件 ∪ 豁免集 必须恰好覆盖 GovState 的全部组件")
        .containsExactlyInAnyOrderElementsOf(componentNames(GovState.class));
  }

  // ── ③ 五张表都非空的逐字段往返（含"又删又改"）──────────────────────────────────────────

  /**
   * ★★ <b>非空 → 非空</b>的逐字段往返：目标态对五张表**各删一个键、各改一个键、各加一个键**（{@link FieldDelta.Patch}
   * 的两侧），于是"键读侧"（{@code toString → parse}）、"值覆盖"、"键删除"三条路径全部走到，而不是只走"从空表往里加"。
   *
   * <p>★ 判别力：若 {@code GovChangeSet.apply} 漏重建任一张表、或把 {@code Patch} 的某一侧丢掉，{@code
   * rebuilt.equals(target)} 与 下面逐字段的断言都会红（整态 equals 只是最后一条，**不能**是唯一一条 —— 两个空对象也相等）。
   *
   * <p>★ 外交三表的键<b>形态各不同</b>（普通 id / 长度前缀复合键 / 普通 id）⇒ 这一条同时把三把不同的 key-parser 都走过一遍。
   */
  @Test
  void allFiveNonEmptyTablesRoundTripFieldByFieldWithRemovalsAndOverwrites() {
    GovState base = full();

    // target：五张表各自把第 2 个键**删掉**、第 1 个键的值**覆盖**、再**新增**第 3 个键。
    Map<UnitId, GovOfficeState> targetOffices = new LinkedHashMap<>();
    targetOffices.put(U1, office(U1, 9L, 700L, 300L));
    targetOffices.put(U3, office(U3, 9L, 55L, 5L));
    Map<UnitId, GovRemittanceState> targetRemittances = new LinkedHashMap<>();
    targetRemittances.put(U1, remittance(0L, 0L, 30L, 25L, 5L, 40L, 35L, 5L, 9L));
    targetRemittances.put(U3, remittance(7L, 8L, 9L, 9L, 0L, 10L, 10L, 0L, 11L));
    Map<NationId, Nation> targetNations = new LinkedHashMap<>();
    targetNations.put(N1, nation(N1, "大蜀（改）", R1, 99));
    targetNations.put(N3, nation(N3, "南越", R2, 3));
    Map<DiplomaticRelationKey, DiplomaticRelation> targetRelations = new LinkedHashMap<>();
    targetRelations.put(edge(N1, N2), relation("兄弟之盟（改）", 40L));
    targetRelations.put(edge(N2, N3), relation("互市", 41L));
    Map<DiplomaticEventId, DiplomaticEvent> targetEvents = new LinkedHashMap<>();
    targetEvents.put(E1, event(E1, 12L, List.of(N1, N3), "会盟（改）"));
    targetEvents.put(E3, event(E3, 13L, List.of(N2, N3), "换质"));
    GovState target =
        new GovState(
            targetOffices, targetRemittances, targetNations, targetRelations, targetEvents);

    GovChangeSet cs = GovChangeSet.between(base, target);

    assertThat(cs.isEmpty()).isFalse();
    assertThat(cs.offices()).as("五张表各自又删又改 ⇒ Patch（两侧都不许丢）").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.remittanceStates()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.nations()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.diplomaticRelations()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.diplomaticEvents()).isInstanceOf(FieldDelta.Patch.class);

    GovState rebuilt = GovChangeSet.apply(cs, base);

    // ⑤ 先逐字段（整态相等放最后：它自己挡不住"空对空"）
    assertThat(rebuilt.offices()).containsOnlyKeys(U1, U3);
    assertThat(rebuilt.offices().get(U1).tick()).as("U1 读数被覆盖（不是留在 7）").isEqualTo(9L);
    assertThat(rebuilt.offices().get(U1).lastAssessedGoods()).containsEntry(GRAIN, 700L);
    assertThat(rebuilt.offices().get(U1).lastPaidGoods()).containsEntry(GRAIN, 300L);
    assertThat(rebuilt.offices().get(U1).lastShortfallGoods()).containsEntry(GRAIN, 400L);
    assertThat(rebuilt.offices().get(U1).securityEfficiencyPerMille()).isEqualTo(250L);
    assertThat(rebuilt.offices().get(U3)).as("新增键（键读侧真的被解析回来）").isNotNull();
    assertThat(rebuilt.offices().get(U3).unitId()).isEqualTo(U3);
    assertThat(rebuilt.offices()).as("被删掉的 U2 不许留在重建结果里").doesNotContainKey(U2);

    assertThat(rebuilt.remittanceStates()).containsOnlyKeys(U1, U3);
    assertThat(rebuilt.remittanceStates().get(U1).lastPaidGrainMilli())
        .as("U1 的上缴读数被覆盖（25，不是 30 也不是相加）")
        .isEqualTo(25L);
    assertThat(rebuilt.remittanceStates().get(U1).lastShortfallGrainMilli()).isEqualTo(5L);
    assertThat(rebuilt.remittanceStates().get(U1).lastCycleCloseDay()).isEqualTo(9L);
    assertThat(rebuilt.remittanceStates().get(U3).cycleSilverCollectedMilli()).isEqualTo(8L);
    assertThat(rebuilt.remittanceStates()).doesNotContainKey(U2);

    assertThat(rebuilt.nations()).containsOnlyKeys(N1, N3);
    assertThat(rebuilt.nations().get(N1).name()).as("国家的名字被覆盖").isEqualTo("大蜀（改）");
    assertThat(rebuilt.nations().get(N1).adminBudgetPerTick()).isEqualTo(99);
    assertThat(rebuilt.nations().get(N1).homeRegion()).isEqualTo(R1);
    assertThat(rebuilt.nations().get(N3).name()).as("新增的 NationId 键被解析回来").isEqualTo("南越");
    assertThat(rebuilt.nations()).doesNotContainKey(N2);

    assertThat(rebuilt.diplomaticRelations()).containsOnlyKeys(edge(N1, N2), edge(N2, N3));
    assertThat(rebuilt.diplomaticRelations().get(edge(N1, N2)).text())
        .as("关系边的自然语言被覆盖")
        .isEqualTo("兄弟之盟（改）");
    assertThat(rebuilt.diplomaticRelations().get(edge(N1, N2)).updatedTick()).isEqualTo(40L);
    assertThat(rebuilt.diplomaticRelations().get(edge(N2, N3)).text()).isEqualTo("互市");
    assertThat(rebuilt.diplomaticRelations())
        .as("被删掉的反向边不许留在重建结果里")
        .doesNotContainKey(edge(N2, N1));

    assertThat(rebuilt.diplomaticEvents()).containsOnlyKeys(E1, E3);
    assertThat(rebuilt.diplomaticEvents().get(E1).text()).isEqualTo("会盟（改）");
    assertThat(rebuilt.diplomaticEvents().get(E1).participants()).containsExactly(N1, N3);
    assertThat(rebuilt.diplomaticEvents().get(E3).tick()).isEqualTo(13L);
    assertThat(rebuilt.diplomaticEvents()).doesNotContainKey(E2);

    assertThat(rebuilt)
        .as("铁律 5 的原文：apply(between(base, target), base) 逐字段重建出 target")
        .isEqualTo(target);
  }

  /** ★ 通用往返断言（util 的 {@code RoundTripAssertions}）：空夹具 → 满夹具（照 {@code SdRoundTripTest} 同款）。 */
  @Test
  void emptyFixtureRoundTripsToTheFullFixture() {
    RoundTripAssertions.assertRoundTrip(
        GovState.empty(), full(), GovChangeSet::between, GovChangeSet::apply);
  }

  // ── ④ 变更集自身语义（照 {@code ActorRoundTripTest} 的同款；{@code SdRoundTripTest} 未单列这两条）─────

  /** ★ 两侧相同 ⇒ 各组件 {@link FieldDelta.Unchanged}（不是 null、也不是"空对象"），且往返恒等。 */
  @Test
  void identicalStatesProduceAnEmptyChangeSetOfUnchangedComponents() {
    GovState state = full();
    GovChangeSet cs = GovChangeSet.between(state, state);

    assertThat(cs.isEmpty()).as("全相等 ⇒ isEmpty()（读法不是 null）").isTrue();
    assertThat(cs.offices()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.remittanceStates()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.nations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.diplomaticRelations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(cs.diplomaticEvents()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(GovChangeSet.apply(cs, state)).as("一字未动的往返必须恒等").isEqualTo(state);
  }

  /**
   * ★ 变更集里<b>缺组件键</b>（Jackson 绑成 null）⇒ 归一成 {@link FieldDelta.Unchanged}，**此处不抛**。
   *
   * <p>钉的是 {@link GovChangeSet} 紧凑构造器里那条 fail-closed 归一（"没提该组件 = 没动它"）；重建时 {@code Unchanged} 直接返回
   * base、连键解析器都不会被调用。
   */
  @Test
  void aChangeSetWithMissingComponentKeysIsNormalizedToUnchanged() {
    GovChangeSet fromMissingKeys = new GovChangeSet(null, null, null, null, null);

    assertThat(fromMissingKeys.isEmpty()).as("缺键 ⇒ 五个组件都是 Unchanged").isTrue();
    assertThat(fromMissingKeys.offices()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.remittanceStates()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.nations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.diplomaticRelations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(fromMissingKeys.diplomaticEvents()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(GovChangeSet.apply(fromMissingKeys, full())).as("一字未动的往返必须恒等（不抛）").isEqualTo(full());
  }

  // ── ⑤ codec 的 JSON 往返：新注册的三个键读侧（本批 3c 新增的护栏面）──────────────────────────

  /**
   * ★★ <b>{@code GovCodec} 的 JSON 往返必须覆盖五张非空表</b>——本批给 {@code GovCodec} 新注册了三个<b>键反序列化器</b>（ {@code
   * NationId} / {@code DiplomaticRelationKey} / {@code
   * DiplomaticEventId}）；而"键读侧漏注册"<b>只在对应表非空时</b>才会在 解码期炸（表空着时测不到，见 {@code GovCodec}/{@code SdCodec}
   * 的类注）。
   *
   * <p>★ 往返走**编码 → 解码**两趟：解码成功 = 三个键都被认识；再断言逐字段等于原状态 = 值也往返对了。★ 顺带把 {@code diff}（{@link
   * io.mosire.simos.util.spi.ModuleDiffer}）与 {@code apply}（C28：新坐标由 codec 装）也各走一遍 ——那是 codec
   * 与变更集两条路的接缝。
   */
  @Test
  void govCodecJsonRoundTripsAllFiveTables() {
    GovCodec codec = new GovCodec();
    GovState state = full();
    GovSnapshot snapshot =
        new GovSnapshot(new StateRef(new BranchId("main"), new RevisionId(7L)), AT, state);

    String json = codec.encodeSnapshot(snapshot);
    GovSnapshot decoded = (GovSnapshot) codec.decodeSnapshot(json);

    assertThat(decoded.state()).as("五张非空表过 JSON 后逐字段相同").isEqualTo(state);
    assertThat(decoded.state().nations().keySet()).as("国家表的键读回来了").containsExactly(N1, N2);
    assertThat(decoded.state().diplomaticRelations().keySet())
        .as("复合键（长度前缀编码）读回来了")
        .containsExactly(edge(N1, N2), edge(N2, N1));
    assertThat(decoded.state().diplomaticEvents().keySet())
        .as("外交事件的键读回来了")
        .containsExactly(E1, E2);

    // 变更集 JSON：编码 → 解码 → 施加（本批新增的三个组件也必须能过线）
    GovState target =
        mutate(mutate(mutate(state, "nations"), "diplomaticRelations"), "diplomaticEvents");
    GovChangeSet cs = GovChangeSet.between(state, target);
    GovChangeSet decodedCs = (GovChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(cs));
    assertThat(GovChangeSet.apply(decodedCs, decoded.state())).isEqualTo(target);

    // 切片接缝：ModuleDiffer 从两个解码后的切片派生变更集
    GovSnapshot targetSnapshot =
        new GovSnapshot(new StateRef(new BranchId("main"), new RevisionId(8L)), AT, target);
    assertThat(codec.diff(decoded, targetSnapshot)).isEqualTo(cs);

    // 空片也必须能过（第二片永远从空开始）
    String emptyJson =
        codec.encodeSnapshot(
            new GovSnapshot(
                new StateRef(new BranchId("main"), new RevisionId(7L)), AT, GovState.empty()));
    assertThat(((GovSnapshot) codec.decodeSnapshot(emptyJson)).state()).isEqualTo(GovState.empty());

    // apply：新坐标由 codec 装（C28），不是照抄 base 的
    StateMeta meta = new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9L)), AT);
    GovSnapshot applied = (GovSnapshot) codec.apply(decodedCs, decoded, meta);
    assertThat(applied.ref()).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(9L)));
    assertThat(applied.state()).isEqualTo(target);
  }

  // ── 夹具与反射小工具 ────────────────────────────────────────────────────────────────

  /** 五张表都**非空**的起点（同一份夹具给"逐组件往返"与"JSON 往返"共用：两边要钉的是同一组形状）。 */
  private static GovState full() {
    Map<UnitId, GovOfficeState> offices = new LinkedHashMap<>();
    offices.put(U1, office(U1, 7L, 100L, 40L));
    offices.put(U2, office(U2, 7L, 200L, 50L));
    Map<UnitId, GovRemittanceState> remittances = new LinkedHashMap<>();
    remittances.put(U1, remittance(10L, 20L, 30L, 30L, 0L, 40L, 40L, 0L, 5L));
    remittances.put(U2, remittance(1L, 2L, 3L, 3L, 0L, 4L, 4L, 0L, 6L));
    Map<NationId, Nation> nations = new LinkedHashMap<>();
    nations.put(N1, nation(N1, "大蜀", R1, 10));
    nations.put(N2, nation(N2, "西陵", R2, 5));
    Map<DiplomaticRelationKey, DiplomaticRelation> relations = new LinkedHashMap<>();
    relations.put(edge(N1, N2), relation("兄弟之盟", 30L));
    relations.put(edge(N2, N1), relation("称臣纳贡", 31L));
    Map<DiplomaticEventId, DiplomaticEvent> events = new LinkedHashMap<>();
    events.put(E1, event(E1, 12L, List.of(N1, N2), "会盟"));
    events.put(E2, event(E2, 12L, List.of(N2, N1, N3), "三方换质"));
    return new GovState(offices, remittances, nations, relations, events);
  }

  /**
   * 造一个"只在 {@code name} 这个组件上与 {@code base} 不同"的状态。
   *
   * <p>★ <b>{@code default} 必须抛</b>：往 {@link GovState} 加组件时这个 {@code switch} 当场响，**这正是想要的** ——
   * 它迫使加字段的人来读这个测试（照 {@code RoundTripComponentsTest} 的同一条纪律）。
   */
  private static GovState mutate(GovState base, String name) {
    return switch (name) {
      case "offices" -> base.withOffice(office(U2, 9L, 999L, 888L));
      case "remittanceStates" ->
          base.withRemittanceState(U2, remittance(0L, 0L, 3L, 2L, 1L, 4L, 4L, 0L, 8L));
      case "nations" -> {
        Map<NationId, Nation> next = new LinkedHashMap<>(base.nations());
        next.put(N2, nation(N2, "西陵（改）", R2, 6));
        yield base.withNations(next);
      }
      case "diplomaticRelations" -> {
        Map<DiplomaticRelationKey, DiplomaticRelation> next =
            new LinkedHashMap<>(base.diplomaticRelations());
        next.put(edge(N2, N1), relation("称臣纳贡（改）", 32L));
        yield base.withDiplomaticRelations(next);
      }
      case "diplomaticEvents" -> {
        Map<DiplomaticEventId, DiplomaticEvent> next = new LinkedHashMap<>(base.diplomaticEvents());
        next.put(E2, event(E2, 12L, List.of(N2, N1, N3), "三方换质（改）"));
        yield base.withDiplomaticEvents(next);
      }
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /** 按组件名取出变更集里对应的那一份差异，问它"变了没有"。★ {@code default} 必须抛（同 {@link #mutate}）。 */
  private static boolean changedOf(GovChangeSet cs, String name) {
    return switch (name) {
      case "offices" -> cs.offices().changed();
      case "remittanceStates" -> cs.remittanceStates().changed();
      case "nations" -> cs.nations().changed();
      case "diplomaticRelations" -> cs.diplomaticRelations().changed();
      case "diplomaticEvents" -> cs.diplomaticEvents().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  /** 某个 record 类型的组件名，**按声明序**。 */
  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }

  // ── 夹具：读数 / 周期账 / 国家 / 外交边 / 外交事件（数值全 ≥ 0；外交边不建自环）────────────────

  /** 一条行政读数：商品/货币各一张评估表 + 实付表 + 缺口表（缺口 = 评估 − 实付，非负）。 */
  private static GovOfficeState office(UnitId id, long tick, long assessed, long paid) {
    return new GovOfficeState(
        id,
        tick,
        Map.of(GRAIN, assessed),
        Map.of(GRAIN, paid),
        Map.of(GRAIN, assessed - paid),
        Map.of(SILVER, assessed),
        Map.of(SILVER, paid),
        Map.of(SILVER, assessed - paid),
        500L,
        600L,
        300L,
        0L,
        250L,
        275L);
  }

  /** 一条 remittance 周期账（9 个字段全给，便于逐字段判往返丢了哪一个）。 */
  private static GovRemittanceState remittance(
      long cycleGrain,
      long cycleSilver,
      long dueGrain,
      long paidGrain,
      long shortGrain,
      long dueSilver,
      long paidSilver,
      long shortSilver,
      long closeDay) {
    return new GovRemittanceState(
        cycleGrain,
        cycleSilver,
        dueGrain,
        paidGrain,
        shortGrain,
        dueSilver,
        paidSilver,
        shortSilver,
        closeDay);
  }

  private static Nation nation(NationId id, String name, RegionId homeRegion, int budget) {
    return new Nation(id, name, homeRegion, budget);
  }

  /** 一条有向外交边（{@code from != to}）。 */
  private static DiplomaticRelationKey edge(NationId from, NationId to) {
    return new DiplomaticRelationKey(from, to);
  }

  private static DiplomaticRelation relation(String text, long updatedTick) {
    return new DiplomaticRelation(Optional.of("盟约"), text, updatedTick);
  }

  private static DiplomaticEvent event(
      DiplomaticEventId id, long tick, List<NationId> participants, String text) {
    return new DiplomaticEvent(id, tick, participants, text);
  }
}
