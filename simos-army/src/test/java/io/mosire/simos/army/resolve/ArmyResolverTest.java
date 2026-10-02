package io.mosire.simos.army.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.testing.ArmyFixtures;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code army:} 命名空间的地址解析（T2a / D-012；形制照 {@code ActorResolverTest} 的"空候选 / 抛"分工）。
 *
 * <p>认领范围恰是 {@code army:combat.<id>}（两段、根主体 kind = {@code combat}）：其它段数 / 其它 kind / 不存在 ⇒
 * 空候选；缺切片或切片类型不对 ⇒ 抛（装配故障不是"没有候选"）。
 */
class ArmyResolverTest {

  private static final ArmyResolver RESOLVER = new ArmyResolver();

  @Test
  void namespaceIsArmy() {
    assertThat(RESOLVER.namespace()).isEqualTo("army");
  }

  @Test
  void resolvesAnExistingCombatRecord() {
    ResolvedSubject subject = only("army:combat.c-1");

    assertThat(subject.id()).isEqualTo(new SubjectId("army", "combat.c-1"));
    assertThat(subject.typeName()).isEqualTo("CombatRecord");
    assertThat(subject.canonicalAddress()).isEqualTo("army:combat.c-1");
  }

  /** ★ canonical 是自反的：解析出来的 canonical 再解析回同一个主体（不手拼地址）。 */
  @Test
  void canonicalIsReflexive() {
    ResolvedSubject subject = only("army:combat.c-2");
    ResolvedSubject again = only(subject.canonicalAddress());

    assertThat(again.id()).isEqualTo(subject.id());
    assertThat(again.typeName()).isEqualTo(subject.typeName());
    assertThat(again.canonicalAddress()).isEqualTo(subject.canonicalAddress());
  }

  @Test
  void anIdThatDoesNotExistIsAnEmptyCandidate() {
    assertThat(resolve("army:combat.nope")).as("合法但不存在的记录 ⇒ 空候选，不是错误").isEmpty();
  }

  @Test
  void otherKindsAreEmptyCandidates() {
    assertThat(resolve("army:unit.u-1")).as("其它 kind 不在认领范围").isEmpty();
    assertThat(resolve("army:c-1")).as("根主体缺 kind（只有两段但没有 combat. 前缀）").isEmpty();
  }

  @Test
  void moreThanTwoSegmentsAreEmptyCandidates() {
    assertThat(resolve("army:combat.c-1:label")).as("属性段（3 段）").isEmpty();
    assertThat(resolve("army:combat.c-1:label:x")).as("更多段").isEmpty();
  }

  @Test
  void anotherNamespaceIsNotClaimed() {
    assertThat(resolve("map:Map1")).as("别的命名空间的合法地址 ⇒ 空候选").isEmpty();
  }

  @Test
  void aMissingSliceIsAnAssemblyFault() {
    SimulationState withoutArmy =
        new SimulationState(
            new StateMeta(ArmyFixtures.REF, ArmyFixtures.T7), Map.of(), InMemoryInfoSystem.empty());

    assertThatThrownBy(
            () ->
                RESOLVER.resolve(
                    Address.parse("army:combat.c-1"),
                    new ResolveContext(withoutArmy, ArmyFixtures.T7)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("装配故障");
  }

  @Test
  void aSliceOfAnotherTypeIsAnAssemblyFault() {
    Snapshot impostor =
        new Snapshot() {
          @Override
          public StateRef ref() {
            return ArmyFixtures.REF;
          }

          @Override
          public SimosTimestamp timestamp() {
            return ArmyFixtures.T7;
          }

          @Override
          public String namespace() {
            return "army";
          }
        };

    assertThatThrownBy(() -> RESOLVER.resolve(Address.parse("army:combat.c-1"), ctx(impostor)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是 ArmySnapshot");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static List<ResolvedSubject> resolve(String address) {
    return RESOLVER
        .resolve(
            Address.parse(address),
            ctx(new ArmySnapshot(ArmyFixtures.REF, ArmyFixtures.T7, ArmyFixtures.sampleData())))
        .candidates();
  }

  private static ResolvedSubject only(String address) {
    List<ResolvedSubject> candidates = resolve(address);
    assertThat(candidates).as("恰好一个候选: " + address).hasSize(1);
    return candidates.get(0);
  }

  private static ResolveContext ctx(Snapshot snapshot) {
    return new ResolveContext(
        new SimulationState(
            new StateMeta(ArmyFixtures.REF, ArmyFixtures.T7),
            Map.of("army", snapshot),
            InMemoryInfoSystem.empty()),
        ArmyFixtures.T7);
  }
}
