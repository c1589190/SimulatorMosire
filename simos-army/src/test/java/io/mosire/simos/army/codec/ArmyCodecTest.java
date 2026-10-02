package io.mosire.simos.army.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * army 模块的 JSON 往返守卫（T2a / D-012；与 {@code ActorCodecTest}/{@code SdCodecTest} 同制）。
 *
 * <p>★ 覆盖：快照/变更集往返（含 stages → outcomes → losses 的嵌套与各处保序）、旧档缺 {@code combats} 键的两条兼容口径、 新坐标（C28）由
 * {@code apply} 的 meta 提供、切片下转型的先验后转。
 */
class ArmyCodecTest {

  private static final ArmyCodec CODEC = new ArmyCodec();

  @Test
  void namespaceIsArmy() {
    assertThat(CODEC.namespace()).isEqualTo("army");
  }

  @Test
  void snapshotRoundTripsWithNestedStagesAndOrder() {
    ArmySnapshot snapshot = ArmyFixtures.snapshot(ArmyFixtures.sampleData(), ArmyFixtures.T7);

    String json = CODEC.encodeSnapshot(snapshot);
    ArmySnapshot back = (ArmySnapshot) CODEC.decodeSnapshot(json);

    assertThat(back).as("整快照逐字段相等").isEqualTo(snapshot);
    assertThat(json)
        .as("嵌套结构真的写进了字节（不是空对象）")
        .contains("stages")
        .contains("outcomes")
        .contains("losses");
  }

  /** ★ 保序断言落在**树节点**上：记录表、阶段表、参与单位、结局表各查一次。 */
  @Test
  void snapshotBytesKeepEveryListAndMapOrder() throws Exception {
    ArmySnapshot snapshot = ArmyFixtures.snapshot(ArmyFixtures.sampleData(), ArmyFixtures.T7);
    String json = CODEC.encodeSnapshot(snapshot);
    ObjectMapper treeMapper = SimosObjectMapper.create();
    JsonNode root = treeMapper.readTree(json);

    JsonNode combats = root.get("data").get("combats");
    assertThat(fieldNames((ObjectNode) combats))
        .as("★ 记录表的键序 = 插入序（c-2 → c-1），不是 id 排序")
        .containsExactly("c-2", "c-1");

    JsonNode stages = combats.get("c-1").get("stages");
    assertThat(stages).hasSize(2);
    assertThat(stages.get(0).path("id").path("value").asText()).as("阶段序 s1 → s2").isEqualTo("s1");
    assertThat(stages.get(1).path("id").path("value").asText()).isEqualTo("s2");

    JsonNode participants = combats.get("c-1").get("participants");
    assertThat(participants.get(0).path("value").asText())
        .as("participants 序 u-2 → u-1")
        .isEqualTo("u-2");
    assertThat(participants.get(1).path("value").asText()).isEqualTo("u-1");

    JsonNode outcomes = stages.get(0).get("outcomes");
    assertThat(outcomes.get(0).path("id").path("value").asText())
        .as("outcomes 序 o1 → o2")
        .isEqualTo("o1");
    assertThat(outcomes.get(1).path("id").path("value").asText()).isEqualTo("o2");
    assertThat(outcomes.get(0).get("losses").get(0).get("manpower").get(0).get("amount").asLong())
        .as("嵌套损失的有符号增量一字不差")
        .isEqualTo(-30L);

    String again = CODEC.encodeSnapshot(backOf(json));
    assertThat(again).as("同一份状态编码两次逐字节相同（字节是内容的纯函数）").isEqualTo(json);
  }

  /** ★ {@code isEmpty()} 是派生判断，不进线格式：{@code ArmyChangeSetMixin} 缺失时 Jackson 会写出 "empty" 并炸掉严格读入。 */
  @Test
  void derivedIsEmptyDoesNotLeakIntoTheWireFormat() {
    String unchanged =
        CODEC.encodeChangeSet(ArmyChangeSet.between(ArmyData.empty(), ArmyData.empty()));
    String upsert =
        CODEC.encodeChangeSet(ArmyChangeSet.between(ArmyData.empty(), ArmyFixtures.sampleData()));

    assertThat(unchanged).as("派生属性 empty 不进 JSON").doesNotContain("\"empty\"");
    assertThat(upsert).doesNotContain("\"empty\"");
    assertThat((ArmyChangeSet) CODEC.decodeChangeSet(unchanged))
        .isEqualTo(ArmyChangeSet.between(ArmyData.empty(), ArmyData.empty()));
  }

  @Test
  void changeSetRoundTripsAllFourDeltaVariants() {
    ArmyData full = ArmyFixtures.sampleData();
    ArmyData empty = ArmyData.empty();
    ArmyData patched =
        ArmyFixtures.data(
            ArmyFixtures.duelRecord(ArmyFixtures.C3, 12L), full.combats().get(ArmyFixtures.C1));

    ArmyChangeSet unchanged = ArmyChangeSet.between(full, full);
    ArmyChangeSet upsert = ArmyChangeSet.between(empty, full);
    ArmyChangeSet remove = ArmyChangeSet.between(full, empty);
    ArmyChangeSet patch = ArmyChangeSet.between(full, patched);

    assertThat(unchanged.combats()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.combats()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.combats()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.combats()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((ArmyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((ArmyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((ArmyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((ArmyChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /**
   * ★ 旧档兼容（第一条）：没有 {@code data.combats} 键的快照字节必须读成**空表**，不是 null/NPE；ref/timestamp 照旧。
   *
   * <p>★ 做法是在**真字节**上删键（先自证这个键确实写进线格式，否则本用例恒真）。
   */
  @Test
  void aLegacySnapshotWithoutTheCombatsKeyReadsAsAnEmptyTable() throws Exception {
    ArmySnapshot snapshot = ArmyFixtures.snapshot(ArmyFixtures.sampleData(), ArmyFixtures.T7);
    String json = CODEC.encodeSnapshot(snapshot);
    ObjectMapper treeMapper = SimosObjectMapper.create();
    JsonNode root = treeMapper.readTree(json);
    ObjectNode data = (ObjectNode) root.get("data");
    assertThat(data.has("combats")).as("★ 先证明 combats 键真的写进了字节").isTrue();
    data.remove("combats");

    ArmySnapshot back = (ArmySnapshot) CODEC.decodeSnapshot(treeMapper.writeValueAsString(root));

    assertThat(back.data().combats()).as("缺键 ⇒ 空表（fail-closed：还没落过 army 的世界仍能读回）").isEmpty();
    assertThat(back.ref()).as("ref 照旧").isEqualTo(ArmyFixtures.REF);
    assertThat(back.timestamp()).as("timestamp 照旧").isEqualTo(ArmyFixtures.T7);
  }

  /**
   * ★ 与上一条**方向相反**：缺整个 {@code data}（不是缺里面的组件）是坏字节，必须响亮失败，不能静默收成空切片。
   *
   * <p>判别力：若把这里的 null 归一成 {@code ArmyData.empty()}，"存档截断/键名写歪"就会伪装成"这个世界还没有交战记录"。
   */
  @Test
  void aSnapshotMissingTheWholeDataKeyFailsLoudly() {
    String truncated =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},"
            + "\"timestamp\":{\"tick\":7,\"calendarLabel\":null}}";

    assertThatThrownBy(() -> CODEC.decodeSnapshot(truncated))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("解码失败");
  }

  /**
   * ★ 旧档兼容（第二条）：没有 {@code combats} 键的**变更集**字节必须读成 {@link FieldDelta.Unchanged}；应用到含记录的 base
   * 上不得清空它。
   */
  @Test
  void aLegacyChangeSetWithoutTheCombatsKeyReadsAsUnchanged() throws Exception {
    ArmyData full = ArmyFixtures.sampleData();
    ArmyChangeSet changeSet = ArmyChangeSet.between(ArmyData.empty(), full);
    String json = CODEC.encodeChangeSet(changeSet);
    ObjectMapper treeMapper = SimosObjectMapper.create();
    assertThat(treeMapper.readTree(json).has("combats")).as("★ 先证明 combats 键真的写进了字节").isTrue();
    ObjectNode root = (ObjectNode) treeMapper.readTree(json);
    root.remove("combats");

    ArmyChangeSet legacy =
        (ArmyChangeSet) CODEC.decodeChangeSet(treeMapper.writeValueAsString(root));

    assertThat(legacy.combats()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(ArmyChangeSet.apply(legacy, full)).as("旧变更集不得误删 base 里的记录").isEqualTo(full);
  }

  @Test
  void applyTakesMetaFromTheNewCoordinates() {
    ArmySnapshot base = ArmyFixtures.snapshot(ArmyData.empty(), ArmyFixtures.T0);
    ArmyChangeSet changeSet = ArmyChangeSet.between(ArmyData.empty(), ArmyFixtures.sampleData());
    StateMeta newMeta =
        new StateMeta(
            new StateRef(ArmyFixtures.REF.branch(), new RevisionId(9)), SimosTimestamp.of(20));

    ArmySnapshot next = (ArmySnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(next.ref()).isEqualTo(newMeta.ref());
    assertThat(next.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(next.data()).isEqualTo(ArmyFixtures.sampleData());
  }

  @Test
  void diffDerivesTheChangeSetFromTwoFullSlices() {
    ArmySnapshot base = ArmyFixtures.snapshot(ArmyData.empty(), ArmyFixtures.T0);
    ArmySnapshot target = ArmyFixtures.snapshot(ArmyFixtures.sampleData(), ArmyFixtures.T7);

    assertThat(CODEC.diff(base, target))
        .as("diff 委托 ArmyChangeSet.between，不另实现一份比较")
        .isEqualTo(ArmyChangeSet.between(base.data(), target.data()));
    assertThat(CODEC.diff(base, base)).isEqualTo(ArmyChangeSet.between(base.data(), base.data()));
  }

  @Test
  void foreignSlicesAreRejectedInsteadOfSilentlyMisread() {
    Snapshot foreign = new ForeignSlice(ArmyFixtures.REF, ArmyFixtures.T7);
    ArmySnapshot army = ArmyFixtures.snapshot(ArmyFixtures.sampleData(), ArmyFixtures.T7);
    ArmyChangeSet changeSet = ArmyChangeSet.between(ArmyData.empty(), army.data());
    StateMeta meta = new StateMeta(ArmyFixtures.REF, ArmyFixtures.T7);

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ArmySnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ArmySnapshot");
    assertThatThrownBy(() -> CODEC.diff(foreign, army))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ArmySnapshot");
    assertThatThrownBy(() -> CODEC.diff(army, foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ArmySnapshot");
  }

  private static ArmySnapshot backOf(String json) {
    return (ArmySnapshot) CODEC.decodeSnapshot(json);
  }

  private static List<String> fieldNames(ObjectNode node) {
    List<String> names = new ArrayList<>();
    node.fieldNames().forEachRemaining(names::add);
    return names;
  }

  /** 其它模块的切片（namespace 不同）——用于验"先验后转"的失败路径。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "map";
    }
  }
}
