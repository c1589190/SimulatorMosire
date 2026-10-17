package io.mosire.simos.culture.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.culture.CultureData;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.culture.codec.CultureCodec;
import io.mosire.simos.culture.contract.CultureId;
import io.mosire.simos.culture.contract.CultureTag;
import io.mosire.simos.culture.contract.CustomTag;
import io.mosire.simos.culture.contract.HouseholdCulture;
import io.mosire.simos.culture.contract.ReligionId;
import io.mosire.simos.social.api.id.HouseholdId;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ <b>铁律 5 在 culture 切片的机械化落地</b>（D-031 spec §11 V1；照 {@code GovRoundTripTest} 的形制）。
 *
 * <p>★★ <b>判别力来自"反射枚举 + 两个 switch 的 default 抛"</b>：往 {@link CultureData} 加组件而不改变更集 ⇒ 当场红。
 *
 * <p>★★ <b>D-032 S2（D2）之后本切片只剩一个组件</b>：文化 → 生产效率倍率链（{@code ProductionAptitudeRule} / {@code
 * aptitude.*} / {@code CultureLookup.rulesFor} / {@code CultureData.aptitudeRules}）已按裁定<b>整条退役</b>
 * ⇒ 组件数 2 → <b>1</b>（只有 {@code householdCultures}）；{@code CultureChangeSet} 同步 2 → 1。★ culture
 * 模块本体与文化 标签面保留（U7「留着模块占位置就行」）。这不是"少了一项"，是那条链路不存在了。
 *
 * <p>★★ <b>键必须非空</b>：{@code CultureCodec} 注册了 {@code HouseholdId} 键反序列化器；空表根本不经过键的字面量往返 （{@link
 * FieldDelta#diff} 把表键经 {@code toString()} 写成规范串、{@link FieldDelta#rebuild} 再用 {@code
 * HouseholdId::parse} 读回）⇒ 夹具必须让该表非空，且值内有 {@code CultureTag}/{@code ReligionId}/{@code CustomTag}
 * 三层嵌套。
 */
class CultureRoundTripTest {

  private static final SimosTimestamp AT = new SimosTimestamp(120L, Optional.empty());

  private static final HouseholdId H1 = new HouseholdId("hh-1");
  private static final HouseholdId H2 = new HouseholdId("hh-2");
  private static final HouseholdId H3 = new HouseholdId("hh-3");

  private static final CultureId LATIUM = new CultureId("latium");
  private static final CultureId GRAECIA = new CultureId("graecia");
  private static final CultureId PERSIA = new CultureId("persia");

  /** ★ 唯一豁免集合（本轮无任何豁免项）。加一项就是一次有意决定，会在 diff 里现形。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── ① 反射枚举：逐组件三条断言 ────────────────────────────────────────────────────────

  @Test
  void everyCultureDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : CultureData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      CultureData base = full();
      CultureData target = mutate(base, name);
      CultureChangeSet cs = CultureChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被报成非 Unchanged", name).isTrue();
      assertThat(CultureChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void stateHasExactlyOneComponent() {
    assertThat(CultureData.class.getRecordComponents())
        .as(
            "CultureData 恰 1 个组件（householdCultures）—— D2 把 aptitudeRules 整条退役；"
                + "多加一个而不改变更集，本断言与往返用例同时红")
        .hasSize(1);
    assertThat(componentNames(CultureData.class))
        .as("名字逐字相等（顺序即声明序）")
        .containsExactly("householdCultures");
  }

  @Test
  void changeSetHasExactlyOneComponentMatchingTheState() {
    assertThat(CultureChangeSet.class.getRecordComponents()).hasSize(1);
    assertThat(componentNames(CultureChangeSet.class))
        .as("变更集的每个组件都必须在 CultureData 里有同名 record 组件")
        .isSubsetOf(componentNames(CultureData.class));
    assertThat(componentNames(CultureData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(CultureChangeSet.class));
  }

  // ── ② 豁免集为空 ──────────────────────────────────────────────────────────────────

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("CultureData 没有豁免项：该表必须进变更集").isEmpty();
  }

  @Test
  void changeSetCoversEveryStateComponentWithNoExemptions() {
    Set<String> covered = new LinkedHashSet<>(componentNames(CultureChangeSet.class));
    covered.addAll(EXCLUDED_FROM_CHANGE_SET);
    assertThat(covered)
        .as("变更集组件 ∪ 豁免集 必须恰好覆盖 CultureData 的全部组件")
        .containsExactlyInAnyOrderElementsOf(componentNames(CultureData.class));
  }

  // ── ③ 非空表的逐字段往返（含"又删又改又加"与嵌套内层）──────────────────────────────────

  @Test
  void theNonEmptyTableRoundTripsFieldByFieldWithRemovalsAndOverwrites() {
    CultureData base = full();

    // householdCultures：删 H2、覆盖 H1、加 H3 ⇒ 变更集必须是 Patch（删了谁、加了谁两侧都不许丢）。
    Map<HouseholdId, HouseholdCulture> targetCultures = new LinkedHashMap<>();
    targetCultures.put(H1, culture(H1, LATIUM, 2));
    targetCultures.put(H3, culture(H3, PERSIA, 1));

    CultureData target = new CultureData(targetCultures);
    CultureChangeSet cs = CultureChangeSet.between(base, target);

    assertThat(cs.isEmpty()).isFalse();
    assertThat(cs.householdCultures()).as("又删又改又加 ⇒ Patch").isInstanceOf(FieldDelta.Patch.class);

    CultureData rebuilt = CultureChangeSet.apply(cs, base);

    assertThat(rebuilt.householdCultures()).containsOnlyKeys(H1, H3);
    assertThat(rebuilt.householdCultures().get(H1).tag().culture()).as("H1 被覆盖").isEqualTo(LATIUM);
    assertThat(rebuilt.householdCultures().get(H1).tag().religion())
        .as("内层 ReligionId 被覆盖")
        .isEqualTo(new ReligionId("roman-2"));
    assertThat(rebuilt.householdCultures().get(H1).tag().customs())
        .as("内层 CustomTag 集合往返")
        .containsExactly(new CustomTag("latium-custom"));
    assertThat(rebuilt.householdCultures().get(H3).tag().culture())
        .as("新增的 HouseholdId 键被解析回来")
        .isEqualTo(PERSIA);
    assertThat(rebuilt.householdCultures().get(H3).tag().customs())
        .containsExactly(new CustomTag("persia-custom"));
    assertThat(rebuilt.householdCultures()).as("被删的 H2 不许留在重建结果里").doesNotContainKey(H2);

    assertThat(rebuilt)
        .as("铁律 5 的原文：apply(between(base, target), base) 逐字段重建出 target")
        .isEqualTo(target);
  }

  @Test
  void emptyFixtureRoundTripsToTheFullFixture() {
    RoundTripAssertions.assertRoundTrip(
        CultureData.empty(), full(), CultureChangeSet::between, CultureChangeSet::apply);
  }

  // ── ④ 变更集自身语义 ────────────────────────────────────────────────────────────────

  @Test
  void identicalStatesProduceAnEmptyChangeSetOfUnchangedComponents() {
    CultureData state = full();
    CultureChangeSet cs = CultureChangeSet.between(state, state);

    assertThat(cs.isEmpty()).isTrue();
    assertThat(cs.householdCultures()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(CultureChangeSet.apply(cs, state)).as("一字未动的往返必须恒等").isEqualTo(state);
  }

  @Test
  void aChangeSetWithMissingComponentKeysIsNormalizedToUnchanged() {
    CultureChangeSet fromMissingKeys = new CultureChangeSet(null);

    assertThat(fromMissingKeys.isEmpty()).as("缺键 ⇒ Unchanged").isTrue();
    assertThat(fromMissingKeys.householdCultures()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(CultureChangeSet.apply(fromMissingKeys, full())).as("不抛且恒等").isEqualTo(full());
  }

  // ── ⑤ 状态集合隔离 ────────────────────────────────────────────────────────────────

  @Test
  void sourceMapMutationDoesNotLeakIntoStateAndReturnedMapsAreUnmodifiable() {
    Map<HouseholdId, HouseholdCulture> source = new LinkedHashMap<>();
    source.put(H1, culture(H1, LATIUM, 1));
    CultureData data = new CultureData(source);

    source.put(H2, culture(H2, GRAECIA, 1));

    assertThat(data.householdCultures()).as("源 map 后改不得渗进状态").containsOnlyKeys(H1);

    assertThatThrownBy(() -> data.householdCultures().put(H3, culture(H3, PERSIA, 1)))
        .as("对外交出的表必须不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void applyToTakesTheNewMetaCoordinates() {
    CultureSnapshot base =
        new CultureSnapshot(new StateRef(new BranchId("main"), new RevisionId(7L)), AT, full());
    CultureData target = mutate(full(), "householdCultures");
    CultureChangeSet cs = CultureChangeSet.between(base.state(), target);
    StateMeta newMeta =
        new StateMeta(
            new StateRef(new BranchId("main"), new RevisionId(9L)),
            new SimosTimestamp(200L, Optional.empty()));

    CultureSnapshot applied = cs.applyTo(base, newMeta);

    assertThat(applied.ref()).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(9L)));
    assertThat(applied.timestamp()).isEqualTo(new SimosTimestamp(200L, Optional.empty()));
    assertThat(applied.state()).isEqualTo(target);
  }

  // ── ⑥ codec 的 JSON 往返：键读侧 + diff/apply 接缝 ──────────────────────────────────

  @Test
  void cultureCodecJsonRoundTripsAllTables() {
    CultureCodec codec = new CultureCodec();
    CultureData state = full();
    CultureSnapshot snapshot =
        new CultureSnapshot(new StateRef(new BranchId("main"), new RevisionId(7L)), AT, state);

    String json = codec.encodeSnapshot(snapshot);
    CultureSnapshot decoded = (CultureSnapshot) codec.decodeSnapshot(json);

    assertThat(decoded.state()).as("非空表过 JSON 后逐字段相同").isEqualTo(state);
    assertThat(decoded.state().householdCultures().keySet())
        .as("HouseholdId 键读回来了")
        .containsExactly(H1, H2);
    assertThat(decoded.state().householdCultures().get(H1).tag().culture())
        .as("嵌套 CultureId 读回来了")
        .isEqualTo(LATIUM);
    assertThat(decoded.state().householdCultures().get(H1).tag().religion())
        .as("嵌套 ReligionId 读回来了")
        .isEqualTo(new ReligionId("latium-religion"));
    assertThat(decoded.state().householdCultures().get(H2).tag().customs())
        .as("嵌套 CustomTag 集合读回来了")
        .containsExactly(new CustomTag("graecia-custom"));

    CultureData target = mutate(state, "householdCultures");
    CultureChangeSet cs = CultureChangeSet.between(state, target);
    CultureChangeSet decodedCs =
        (CultureChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(cs));
    assertThat(CultureChangeSet.apply(decodedCs, decoded.state())).isEqualTo(target);

    CultureSnapshot targetSnapshot =
        new CultureSnapshot(new StateRef(new BranchId("main"), new RevisionId(8L)), AT, target);
    assertThat(codec.diff(decoded, targetSnapshot)).isEqualTo(cs);

    String emptyJson =
        codec.encodeSnapshot(
            new CultureSnapshot(
                new StateRef(new BranchId("main"), new RevisionId(7L)), AT, CultureData.empty()));
    assertThat(((CultureSnapshot) codec.decodeSnapshot(emptyJson)).state())
        .isEqualTo(CultureData.empty());

    StateMeta meta = new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9L)), AT);
    CultureSnapshot applied = (CultureSnapshot) codec.apply(decodedCs, decoded, meta);
    assertThat(applied.ref()).isEqualTo(new StateRef(new BranchId("main"), new RevisionId(9L)));
    assertThat(applied.state()).isEqualTo(target);
  }

  @Test
  void namespaceLiteralsAgreeBetweenSnapshotAndCodec() {
    assertThat(new CultureCodec().namespace())
        .as("codec 与 snapshot 的 namespace 同字面（装配期有牙）")
        .isEqualTo(
            new CultureSnapshot(
                    new StateRef(new BranchId("main"), new RevisionId(1L)), AT, CultureData.empty())
                .namespace())
        .isEqualTo("culture");
  }

  // ── 夹具与反射小工具 ────────────────────────────────────────────────────────────────

  private static CultureData full() {
    Map<HouseholdId, HouseholdCulture> cultures = new LinkedHashMap<>();
    cultures.put(H1, culture(H1, LATIUM, 1));
    cultures.put(H2, culture(H2, GRAECIA, 1));
    return new CultureData(cultures);
  }

  private static HouseholdCulture culture(HouseholdId household, CultureId culture, int variant) {
    return new HouseholdCulture(
        household,
        new CultureTag(
            culture,
            new ReligionId(variant == 2 ? "roman-2" : culture.value() + "-religion"),
            Set.of(new CustomTag(culture.value() + "-custom"))));
  }

  private static CultureData mutate(CultureData base, String name) {
    return switch (name) {
      case "householdCultures" -> base.withHouseholdCulture(culture(H2, PERSIA, 2));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(CultureChangeSet cs, String name) {
    return switch (name) {
      case "householdCultures" -> cs.householdCultures().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }
}
