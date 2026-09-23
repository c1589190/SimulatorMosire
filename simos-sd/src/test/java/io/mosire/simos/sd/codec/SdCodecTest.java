package io.mosire.simos.sd.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** sd 模块的 JSON 往返守卫（与 {@code MapCodecTest}/{@code UnitCodecTest} 同制）。 */
class SdCodecTest {

  private static final SdCodec CODEC = new SdCodec();

  @Test
  void namespaceIsSd() {
    assertThat(CODEC.namespace()).isEqualTo("sd");
  }

  @Test
  void snapshotRoundTripsWithAddressValuesAndLabeledTimestamp() {
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)),
            SimosTimestamp.of(10, "弘光元年"),
            SdFixtures.full());
    SdSnapshot back = (SdSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /**
   * ★ M11 判据：绑定的 {@code providerId} 经**真 JSON** 往返一字不丢（铁律 5 的线格式那半）。变异靶子 m2 = 线格式丢 providerId（如裸往返把
   * {@code Optional} 写坏、或加了 {@code @JsonIgnore}）。
   */
  @Test
  void boundProviderSurvivesTheJsonRoundTrip() {
    SdState sd =
        SdFixtures.empty()
            .withDecisionMakers(
                Map.of(SdFixtures.DM1, SdFixtures.boundDecisionMaker(SdFixtures.DM1, "p-json")));
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)), SimosTimestamp.of(10), sd);

    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(json).as("绑定必须真的写进字节").contains("p-json");

    SdSnapshot back = (SdSnapshot) CODEC.decodeSnapshot(json);
    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().decisionMakers().get(SdFixtures.DM1).providerId()).contains("p-json");
  }

  /**
   * ★★ **老档兼容**（本任务最硬的一条）：**没有** {@code conversationGeneration} 字段的旧字节，必须读成**世代 0**。
   *
   * <p>★ 为什么这是硬要求：现场已经落盘的老检查点/老档里没有这个字段，读不回来 = **整个世界打不开**。世代 0 同时还是"会话 id 与旧格式逐字相同"那一半（老会话因此接得上）。
   *
   * <p>★★ **做法是把字段从真字节里删掉**，不是在测试里手写一份"看起来像老档"的 JSON：手写的那份只能证明"我写的 JSON 我能读" ——真档是**写出来的那台 mapper
   * 写出来的**，两者不是一回事。故：先编码 ⇒ 逐字删字段（并断言命中恰好 1 次，否则说明线格式 漂移了，本用例**当场红**而不是恒真）⇒ 再解码。
   */
  @Test
  void aLegacyArchiveWithoutTheGenerationFieldReadsAsGenerationZero() {
    SdState sd =
        SdFixtures.empty()
            .withDecisionMakers(
                Map.of(
                    SdFixtures.DM1,
                    SdFixtures.decisionMakerAtGeneration(SdFixtures.DM1, 2),
                    SdFixtures.DM2,
                    SdFixtures.decisionMaker(SdFixtures.DM2)));
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)), SimosTimestamp.of(10), sd);

    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(countOf(json, "\"conversationGeneration\":"))
        .as("★ 先证明这个字段真的写进了字节（两个决策人各一个；写成 0 也照写）")
        .isEqualTo(2);
    String legacy = json.replace(",\"conversationGeneration\":2", "");
    assertThat(countOf(legacy, "\"conversationGeneration\":"))
        .as("删掉的恰好是 DM1 那一个（剩 DM2 的那个）；锚点没命中 ⇒ 这里是 2，本用例据此自证而不是恒真")
        .isEqualTo(1);

    SdSnapshot back = (SdSnapshot) CODEC.decodeSnapshot(legacy);
    assertThat(back.state().decisionMakers().get(SdFixtures.DM1).conversationGeneration())
        .as("★ 老字节（没有这个字段）读回来就是世代 0——世界里没有「重置过」这件事")
        .isZero();
    assertThat(back.state().decisionMakers().get(SdFixtures.DM1).id())
        .as("其余字段一个不少")
        .isEqualTo(SdFixtures.DM1);
  }

  /**
   * ★★ **老档兼容（第 3 波第 1 步 + Docs 的 affiliations）**：没有 {@code id}/{@code tick}/{@code tags}/{@code affiliations}
   * 四个键的旧 INFO 条目字节，**必须读回来不炸**， 且缺省落在**安全的那一侧**：{@code tags = 空集}（无主 ⇒ 不进任何按决策人的归属裁决）、{@code
   * affiliations = 空集}（不按归属发 ⇒ 老条目**不会**因为新增这一轴突然对某个国家/军队可见）、{@code tick = 0}、{@code id =
   * legacy:<key>@<at>}（内容派生，纯函数）。
   *
   * <p>★ 做法与 {@link #aLegacyArchiveWithoutTheGenerationFieldReadsAsGenerationZero} 同源：**在真字节上删键**
   * （真档是写出来的那台 mapper 写的），先自证四个键确实在线格式里（否则本用例恒真）。
   */
  @Test
  void aLegacyArchiveWithoutTheDecisionResultKeysReadsWithFailClosedDefaults() throws Exception {
    SdState sd =
        SdFixtures.empty().withInfo(Map.of("map:Map1", List.of(SdFixtures.infoEntry("k1"))));
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)), SimosTimestamp.of(10), sd);

    String json = CODEC.encodeSnapshot(snapshot);
    // 删键：解析成树 ⇒ 先**自证**四个键确实在线格式里（真档是写出来的那台 mapper 写的）⇒ 再逐条目删。
    ObjectMapper treeMapper = SimosObjectMapper.create();
    JsonNode root = treeMapper.readTree(json);
    ObjectNode info = (ObjectNode) root.get("state").get("info");
    ObjectNode entryNode = (ObjectNode) info.get("map:Map1").get(0);
    assertThat(entryNode.has("id")).as("★ 先证明 id 键真的写进字节").isTrue();
    assertThat(entryNode.has("tick")).as("★ 先证明 tick 键真的写进字节").isTrue();
    assertThat(entryNode.has("tags")).as("★ 先证明 tags 键真的写进字节").isTrue();
    assertThat(entryNode.has("affiliations")).as("★ 先证明 affiliations 键真的写进字节").isTrue();
    entryNode.remove("id");
    entryNode.remove("tick");
    entryNode.remove("tags");
    entryNode.remove("affiliations");
    String legacy = treeMapper.writeValueAsString(root);
    assertThat(countOf(legacy, "\"tags\":")).as("删干净了（tags 只出现在 INFO 条目上）").isZero();

    SdSnapshot back = (SdSnapshot) CODEC.decodeSnapshot(legacy);
    var entry = back.state().info().get("map:Map1").get(0);
    assertThat(entry.key()).as("其余字段一个不少").isEqualTo("k1");
    assertThat(entry.tags()).as("★ 缺省 tags = 空集（无主，fail-closed）").isEmpty();
    assertThat(entry.affiliations())
        .as("★ 缺省 affiliations = 空集（不按归属发，fail-closed）")
        .isEmpty();
    assertThat(entry.tick()).as("★ 缺省 tick = 0").isZero();
    assertThat(entry.id())
        .as("★ 缺省 id = 内容派生 legacy:<key>@<at>")
        .isEqualTo(new SdInfoId("legacy:k1@1"));
  }

  @Test
  void changeSetRoundTripsAllFourDeltaVariants() {
    SdState full = SdFixtures.full();
    SdState empty = SdFixtures.empty();

    SdChangeSet unchanged = SdChangeSet.between(full, full);
    SdChangeSet upsert = SdChangeSet.between(empty, full);
    SdChangeSet remove = SdChangeSet.between(full, empty);
    SdChangeSet patch = SdChangeSet.between(full, patchTarget(full));

    assertThat(unchanged.nations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.nations()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.nations()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.nations()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch))).isEqualTo(patch);
  }

  @Test
  void reEncodeIsByteStable() {
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)),
            SimosTimestamp.of(10),
            SdFixtures.full());
    String first = CODEC.encodeSnapshot(snapshot);
    String second = CODEC.encodeSnapshot(CODEC.decodeSnapshot(first));
    assertThat(second).isEqualTo(first);
  }

  @Test
  void applyAndEncodeSnapshotRejectForeignSlice() {
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));
    SdSnapshot base =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)),
            SimosTimestamp.of(10),
            SdFixtures.full());
    SdChangeSet changeSet = SdChangeSet.between(base.state(), base.state());
    StateMeta meta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SdSnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SdSnapshot");
  }

  /**
   * ★ {@code SdCodec} 同时实现 {@link io.mosire.simos.util.spi.ModuleDiffer}（"一批命令 = 一条 revision"
   * 的原子批量提交需要）： 从**两个完整状态切片**派生，语义委托 {@link SdChangeSet#between}——本类不重新实现比较（铁律 5）。
   */
  @Test
  void diffDerivesTheChangeSetFromTwoFullSlices() {
    SdSnapshot base = snapshot(SdFixtures.empty());
    SdSnapshot target = snapshot(SdFixtures.full());

    assertThat(CODEC.diff(base, target))
        .as("diff(base, target) == SdChangeSet.between(base.state, target.state)")
        .isEqualTo(SdChangeSet.between(base.state(), target.state()));
    assertThat(CODEC.diff(base, base))
        .as("全相等 ⇒ 各组件 Unchanged（仍是一份合法变更集）")
        .isEqualTo(SdChangeSet.between(base.state(), base.state()));
  }

  /** diff 的两侧切片都必须属于本模块：转错切片 ⇒ 当场炸，不给出一份错变更集。 */
  @Test
  void diffRejectsForeignSlice() {
    SdSnapshot sd = snapshot(SdFixtures.full());
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.diff(foreign, sd))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SdSnapshot");
    assertThatThrownBy(() -> CODEC.diff(sd, foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SdSnapshot");
  }

  private static SdSnapshot snapshot(SdState state) {
    return new SdSnapshot(
        new StateRef(new BranchId("main"), new RevisionId(3)), SimosTimestamp.of(10), state);
  }

  /** 子串出现次数（老档兼容那条用来自证"真删掉了一个字段"）。 */
  private static int countOf(String haystack, String needle) {
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
  }

  private static SdState patchTarget(SdState full) {
    Map<NationId, Nation> patched = new LinkedHashMap<>(full.nations());
    patched.remove(SdFixtures.N2);
    NationId extra = new NationId("n-extra");
    patched.put(extra, SdFixtures.nation(extra, new RegionId("r-extra")));
    return full.withNations(patched);
  }

  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "map";
    }
  }
}
