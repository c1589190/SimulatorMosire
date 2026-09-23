package io.mosire.simos.social.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * R-3-social：social 模块的 JSON 往返守卫（M4 Task 3 Step 4，与 {@code MapCodecTest} 同制，夹具是 social 自己的）。
 *
 * <p>★ 覆盖：{@code Optional}（历注两侧向 + {@code SegmentedSeries.addition=null} 的 null 组件往返 + {@code
 * SocialCity.region} 两侧向）、自定义键 {@code HexCoord}/{@code CityId}、密封接口 {@code FieldDelta} 四变体、 城市节点与
 * props 的**字节级**往返。往返只断 {@code equals}（裁定 12），字节级另有一条。
 */
class SocialCodecTest {

  private static final HexCoord H00 = new HexCoord(0, 0);

  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final SocialCodec CODEC = new SocialCodec();

  @Test
  void namespaceIsSocial() {
    assertThat(CODEC.namespace()).isEqualTo("social");
  }

  /** 非平凡快照往返：带历注 + {@code Map<HexCoord,…>} 自定义键 + 人口序列（growth 的 addition 是 null）。 */
  @Test
  void snapshotRoundTripsWithLabeledTimestamp() {
    SocialSnapshot snapshot = snapshotOf(onePopulation(H00), SimosTimestamp.of(10, "弘光元年"));
    SocialSnapshot back = (SocialSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** Optional 的另一个方向：无历注（empty）也要活着。 */
  @Test
  void snapshotRoundTripsWithUnlabeledTimestamp() {
    SocialSnapshot snapshot = snapshotOf(onePopulation(H00), SimosTimestamp.of(11));
    SocialSnapshot back = (SocialSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** 变更集往返：四条变体各造一条（Unchanged / Upsert / Remove / Patch），逐条过线。 */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    SocialData full = onePopulation(H00);
    SocialData moved = onePopulation(H11);

    SocialChangeSet unchanged = SocialChangeSet.between(full, full);
    SocialChangeSet upsert = SocialChangeSet.between(SocialData.empty(), full);
    SocialChangeSet remove = SocialChangeSet.between(full, SocialData.empty());
    SocialChangeSet patch = SocialChangeSet.between(full, moved);

    // 前置：夹具确实落在了四条变体上
    assertThat(unchanged.populations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.populations()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.populations()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.populations()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /** 值类型的绑定不能在读入侧丢成 Map：PopulationSeries 得还是 PopulationSeries。 */
  @Test
  void deltaValuesSurviveAsPopulationSeriesNotAsMaps() {
    SocialChangeSet back =
        (SocialChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(
                    SocialChangeSet.between(SocialData.empty(), onePopulation(H00))));
    FieldDelta.Upsert<PopulationSeries> upsert =
        (FieldDelta.Upsert<PopulationSeries>) back.populations();
    assertThat(upsert.entries().get("0_0")).isInstanceOf(PopulationSeries.class);
  }

  /** cities 的键（CityId）与值（SocialCity）都得活着回来，不能退化成 Map。 */
  @Test
  void deltaValuesSurviveAsSocialCityNotAsMaps() {
    SocialData target = dataWithCity(cityWithRegion());
    SocialChangeSet back =
        (SocialChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(SocialChangeSet.between(SocialData.empty(), target)));
    FieldDelta.Upsert<SocialCity> upsert = (FieldDelta.Upsert<SocialCity>) back.cities();
    assertThat(upsert.entries().get("c1")).isInstanceOf(SocialCity.class);
  }

  /** 城市快照往返：{@code region} 两侧向（有归属 / 无归属）+ props 多键（含 List）。 */
  @Test
  void snapshotRoundTripsWithCityNodes() {
    SocialData data =
        new SocialData(
            onePopulation(H00).populations(),
            Map.of(
                new CityId("c1"), cityWithRegion(),
                new CityId("c2"), cityWithoutRegion()));
    SocialSnapshot snapshot = snapshotOf(data, SimosTimestamp.of(10, "弘光元年"));

    SocialSnapshot back = (SocialSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** 城市变更集往返：增 / 改 / 删三条各过线。 */
  @Test
  void changeSetRoundTripsWithCityDeltas() {
    SocialData full = dataWithCity(cityWithRegion());
    SocialData renamed = dataWithCity(cityWithRegion().withName("改名后"));

    SocialChangeSet upsert = SocialChangeSet.between(SocialData.empty(), full);
    SocialChangeSet modify = SocialChangeSet.between(full, renamed);
    SocialChangeSet remove = SocialChangeSet.between(full, SocialData.empty());

    assertThat(upsert.cities()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(modify.cities()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.cities()).isInstanceOf(FieldDelta.Remove.class);

    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(modify)))
        .isEqualTo(modify);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
  }

  /**
   * ★ **字节级往返**：编码 → 解码 → **再编码**，两次编码**逐字节相等**。
   *
   * <p>这条比 {@code equals} 往返更严：它能抓住"解码时把有序容器换成 {@code Map.copyOf}/{@code
   * Set.copyOf}"这类**内容相等而迭代序漂移**的 退化（那正是本仓反复强调的字节漂移源）。
   */
  @Test
  void encodingIsByteLevelStableForCityBearingData() {
    SocialData data =
        new SocialData(
            onePopulation(H00).populations(),
            Map.of(
                new CityId("c1"), cityWithRegion(),
                new CityId("c2"), cityWithoutRegion()));

    String snapshotOnce = CODEC.encodeSnapshot(snapshotOf(data, SimosTimestamp.of(10, "弘光元年")));
    String snapshotTwice = CODEC.encodeSnapshot(CODEC.decodeSnapshot(snapshotOnce));
    assertThat(snapshotTwice).as("快照的字节级往返").isEqualTo(snapshotOnce);

    SocialChangeSet changeSet =
        SocialChangeSet.between(SocialData.empty(), dataWithCity(cityWithRegion()));
    String changeOnce = CODEC.encodeChangeSet(changeSet);
    String changeTwice = CODEC.encodeChangeSet(CODEC.decodeChangeSet(changeOnce));
    assertThat(changeTwice).as("变更集的字节级往返").isEqualTo(changeOnce);
  }

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    SocialSnapshot base = snapshotOf(onePopulation(H00), SimosTimestamp.of(10));
    SocialChangeSet changeSet = SocialChangeSet.between(base.data(), onePopulation(H11));
    StateMeta newMeta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    SocialSnapshot applied = (SocialSnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(applied.ref()).isEqualTo(newMeta.ref());
    assertThat(applied.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(applied.data()).isEqualTo(SocialChangeSet.apply(changeSet, base.data()));
    assertThat(base.data()).isEqualTo(onePopulation(H00));
  }

  /**
   * ★ 下转型守卫的自证：喂一个**别的模块的切片**，{@code apply} 与 {@code encodeSnapshot} 都必须当场 {@link
   * IllegalStateException}。
   *
   * <p>这条用例**只在改成 {@code instanceof} 之后**才有判别力——裸 cast 同样会抛（{@code ClassCastException}），
   * 所以断言钉的是**异常类型 + 消息**，不是"抛了就算"。
   */
  @Test
  void applyAndEncodeSnapshotRejectForeignSlice() {
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));
    SocialChangeSet changeSet = SocialChangeSet.between(SocialData.empty(), SocialData.empty());
    StateMeta meta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SocialSnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SocialSnapshot");
  }

  /**
   * ★ {@code SocialCodec} 同时实现 {@link io.mosire.simos.util.spi.ModuleDiffer}（"一批命令 = 一条 revision"
   * 的原子批量提交需要）： 从**两个完整状态切片**派生，语义委托 {@link SocialChangeSet#between}（铁律 5，本类不重新实现比较）。
   */
  @Test
  void diffDerivesTheChangeSetFromTwoFullSlices() {
    SocialSnapshot base = snapshotOf(SocialData.empty(), SimosTimestamp.of(10));
    SocialSnapshot target = snapshotOf(onePopulation(H00), SimosTimestamp.of(10));

    assertThat(CODEC.diff(base, target))
        .isEqualTo(SocialChangeSet.between(base.data(), target.data()));
    assertThat(CODEC.diff(base, base))
        .as("全相等 ⇒ 各组件 Unchanged（仍是一份合法变更集）")
        .isEqualTo(SocialChangeSet.between(base.data(), base.data()));
  }

  /** diff 的两侧切片都必须属于本模块：转错切片 ⇒ 当场炸，不给出一份错变更集。 */
  @Test
  void diffRejectsForeignSlice() {
    SocialSnapshot social = snapshotOf(onePopulation(H00), SimosTimestamp.of(10));
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.diff(foreign, social))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SocialSnapshot");
  }

  /**
   * ★★ **老档兼容：升级前的 `social` 快照必须读得回来**（这条是 16 条真红的现场复现）。
   *
   * <p>字节取自真档：`simos-app/src/main/resources/worlds/v17levant.json` 的 `modules.social` —— 那个键**没有**
   * `cities`。若让构造器对 null 抛，等于"整个世界打不开"（`RichWorldTest` 全组当场红）。缺省方向是 fail-closed：旧档没有城市 ⇒ 空表。
   */
  @Test
  void legacySnapshotWithoutCitiesKeyDecodesToEmptyCities() {
    String legacy =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},"
            + "\"timestamp\":{\"tick\":0,\"calendarLabel\":null},"
            + "\"data\":{\"populations\":{}}}";

    SocialSnapshot back = (SocialSnapshot) CODEC.decodeSnapshot(legacy);

    assertThat(back.data().populations()).isEmpty();
    assertThat(back.data().cities()).as("旧档没有城市 ⇒ 空表，不抛").isEmpty();
  }

  /**
   * ★★ **老档兼容：升级前的 `social` 变更集必须读得回来**。
   *
   * <p>升级前落盘的每条 social revision 都只有 `populations` 一个组件。缺省 = {@link FieldDelta.Unchanged} （"一字未动"）——
   * 若读成 null，`isEmpty()` 与 `apply` 都会 NPE，**旧 revision 全部重放不了**。
   */
  @Test
  void legacyChangeSetWithoutCitiesKeyDecodesToUnchangedCities() {
    String legacy = "{\"populations\":{\"@class\":\"unchanged\"}}";

    SocialChangeSet back = (SocialChangeSet) CODEC.decodeChangeSet(legacy);

    assertThat(back.cities()).as("旧档没提城市 ⇒ Unchanged，不抛").isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.isEmpty()).as("两个组件都未变 ⇒ 这份旧变更集是空的").isTrue();
  }

  /** 别的模块的切片：本测试只借它的**类型**，不借语义。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "unit";
    }
  }

  // ── 夹具 ──

  private static SocialData onePopulation(HexCoord at) {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    populations.put(
        at,
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 10000L),
            new SegmentedSeries<>(
                List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
            List.of()));
    return new SocialData(populations, Map.of());
  }

  private static SocialSnapshot snapshotOf(SocialData data, SimosTimestamp timestamp) {
    return new SocialSnapshot(
        new StateRef(new BranchId("main"), new RevisionId(3)), timestamp, data);
  }

  /** 有归属的城：props 多键（Integer/String/Boolean/List），用于钉住 props 的字节稳定。 */
  private static SocialCity cityWithRegion() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tier", 3);
    props.put("catchmentHexes", 7);
    props.put("tag", "core");
    props.put("isCapital", true);
    props.put("neighbours", List.of("c2", "c3"));
    return new SocialCity(
        new CityId("c1"), "城甲", H00, Optional.of(new RegionId("r1")), 12000L, props);
  }

  /** 无归属的城：{@code region = Optional.empty()} 的往返方向。 */
  private static SocialCity cityWithoutRegion() {
    return new SocialCity(new CityId("c2"), "无主城", H11, Optional.empty(), 500L, Map.of());
  }

  private static SocialData dataWithCity(SocialCity city) {
    return new SocialData(onePopulation(H00).populations(), Map.of(city.id(), city));
  }
}
