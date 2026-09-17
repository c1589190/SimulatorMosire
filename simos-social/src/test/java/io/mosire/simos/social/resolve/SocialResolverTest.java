package io.mosire.simos.social.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * social: 寻址。夹具：两格有人口（经 SocialSnapshot 装进真实 SimulationState）。
 *
 * <p>★ R12：空候选 vs 抛的分工逐条；★ R13：canonical 由 AST 产出（mapId 含 {@code :} 时引号必须自动正确）。
 */
class SocialResolverTest {

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp TS = SimosTimestamp.of(5);
  private static final HexCoord H00 = new HexCoord(0, 0);

  private final SocialResolver resolver = new SocialResolver();

  private static PopulationSeries population(long people) {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), people),
        new SegmentedSeries<>(List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
        List.of());
  }

  private static ResolveContext ctx(String namespace, Snapshot snapshot) {
    SimulationState state =
        new SimulationState(
            new StateMeta(REF, TS), Map.of(namespace, snapshot), InMemoryInfoSystem.empty());
    return new ResolveContext(state, TS);
  }

  private static ResolveContext goodCtx() {
    return ctx(
        "social", new SocialSnapshot(REF, TS, new SocialData(Map.of(H00, population(18036)))));
  }

  @Test
  void rootOfTheNamespaceResolves() {
    var result = resolver.resolve(Address.parse("social:Map1"), goodCtx());
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().get(0).id().localId()).isEqualTo("Map1");
    assertThat(result.candidates().get(0).typeName()).isEqualTo("Social");
    assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo("social:Map1");
  }

  @Test
  void hexWithARecordResolvesAndCanonicalises() {
    var result = resolver.resolve(Address.parse("social:Map1:hex.0_0"), goodCtx());
    assertThat(result.candidates()).hasSize(1);
    assertThat(result.candidates().get(0).id().localId()).isEqualTo("0_0");
    assertThat(result.candidates().get(0).typeName()).isEqualTo("HexPopulation");
    // 别只断字符串：候选的 localId 必须解析回切片里真实存在的那条记录
    assertThat(result.candidates().get(0).id().namespace()).isEqualTo("social.hex");
  }

  @Test
  void humanIndexFormCanonicalisesToTheKindNameForm() {
    var result = resolver.resolve(Address.parse("social:Map1:[0,0]"), goodCtx());
    assertThat(result.candidates()).as("Human 进、canonical 出").hasSize(1);
    assertThat(result.candidates().get(0).canonicalAddress()).isEqualTo("social:Map1:hex.0_0");
  }

  // ── R12：空候选（合法但本模块不服务 / 不存在） ──────────────────────

  @Test
  void absentHexIsAnEmptyCandidateNotAnError() {
    assertThat(resolver.resolve(Address.parse("social:Map1:hex.9_9"), goodCtx()).candidates())
        .isEmpty();
  }

  @Test
  void attributeSegmentsAndOverlongAddressesAreEmptyCandidates() {
    assertThat(resolver.resolve(Address.parse("social:Map1:population"), goodCtx()).candidates())
        .isEmpty();
    assertThat(
            resolver
                .resolve(Address.parse("social:Map1:hex.0_0:population"), goodCtx())
                .candidates())
        .isEmpty();
    assertThat(resolver.resolve(Address.parse("social:Map1:city.c1"), goodCtx()).candidates())
        .isEmpty();
  }

  @Test
  void foreignNamespaceIsAnEmptyCandidate() {
    assertThat(resolver.resolve(Address.parse("map:Map1"), goodCtx()).candidates()).isEmpty();
  }

  // ── R12：抛（装配故障） ──────────────────────────────────────────

  @Test
  void missingSliceThrows() {
    ResolveContext noSlice =
        new ResolveContext(
            new SimulationState(new StateMeta(REF, TS), Map.of(), InMemoryInfoSystem.empty()), TS);
    assertThatThrownBy(() -> resolver.resolve(Address.parse("social:Map1"), noSlice))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("social");
  }

  @Test
  void wrongSliceTypeThrows() {
    Snapshot impostor =
        new Snapshot() {
          @Override
          public StateRef ref() {
            return REF;
          }

          @Override
          public SimosTimestamp timestamp() {
            return TS;
          }

          @Override
          public String namespace() {
            return "social";
          }
        };
    assertThatThrownBy(
            () -> resolver.resolve(Address.parse("social:Map1"), ctx("social", impostor)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── R13：canonical 只由 AST 产出（m2 变异的靶子） ────────────────────

  /**
   * mapId 含 {@code :} 的判别力靶子（G13 m2）：现有用例的 mapId 全是裸词（{@code Map1}），手写拼接与 AST 输出逐字相同、 抓不到。含 {@code
   * :} 的 mapId 解析回来段 2 是 {@code Entity(∅,"m:1")}；AST canonical ⇒ {@code social:"m:1"} / {@code
   * social:"m:1":hex.0_0}（引号自动正确）；手写拼接 ⇒ 丢引号 ⇒ 红。 social 的 mapId 只回显、不校验（GameMap 无 id，M2
   * 遗留挂起项），故同一夹具即可服务。
   */
  @Test
  void colonMapIdKeepsQuotesInCanonical() {
    var root = resolver.resolve(Address.parse("social:\"m:1\""), goodCtx());
    assertThat(root.candidates()).hasSize(1);
    assertThat(root.candidates().get(0).id().localId()).isEqualTo("m:1");
    assertThat(root.candidates().get(0).canonicalAddress()).isEqualTo("social:\"m:1\"");

    var hex = resolver.resolve(Address.parse("social:\"m:1\":hex.0_0"), goodCtx());
    assertThat(hex.candidates()).hasSize(1);
    assertThat(hex.candidates().get(0).id().localId()).isEqualTo("0_0");
    // localId 解析回切片里真实存在的记录（不只断字符串）
    assertThat(hex.candidates().get(0).typeName()).isEqualTo("HexPopulation");
    assertThat(hex.candidates().get(0).canonicalAddress()).isEqualTo("social:\"m:1\":hex.0_0");
  }
}
