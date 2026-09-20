package io.mosire.simos.sd.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.resolve.ResolveContext;
import org.junit.jupiter.api.Test;

/** {@code sd:} 解析器：canonical 回显、链式阶段/结局、未存在与非法形态一律空候选。 */
class SdResolverTest {

  private static final SdResolver RESOLVER = new SdResolver();

  @Test
  void namespaceIsSd() {
    assertThat(RESOLVER.namespace()).isEqualTo("sd");
  }

  @Test
  void resolvesNationRootToItsCanonicalForm() {
    ResolvedSubject subject = one("sd:nation.n1");
    assertThat(subject.canonicalAddress()).isEqualTo("sd:nation.n1");
    assertThat(subject.id()).isEqualTo(new SubjectId("sd", "nation.n1"));
    assertThat(subject.typeName()).isEqualTo("Nation");
  }

  @Test
  void resolvesArmyDecisionMakerDirectiveAndEffect() {
    assertThat(one("sd:army.a1").typeName()).isEqualTo("Army");
    assertThat(one("sd:decision-maker.dm1").id())
        .isEqualTo(new SubjectId("sd", "decision-maker.dm1"));
    assertThat(one("sd:directive.d1").typeName()).isEqualTo("Directive");
    assertThat(one("sd:effect.e1").typeName()).isEqualTo("Effect");
  }

  @Test
  void resolvesCombatStageChain() {
    ResolvedSubject stage = one("sd:combat.c1:stage.s1");
    assertThat(stage.canonicalAddress()).isEqualTo("sd:combat.c1:stage.s1");
    assertThat(stage.typeName()).isEqualTo("CombatStage");
    assertThat(stage.id()).isEqualTo(new SubjectId("sd", "combat.c1:stage.s1"));
  }

  @Test
  void resolvesOutcomeOnlyWithinItsStage() {
    ResolvedSubject outcome = one("sd:combat.c1:stage.s1:outcome.o1");
    assertThat(outcome.canonicalAddress()).isEqualTo("sd:combat.c1:stage.s1:outcome.o1");
    assertThat(outcome.typeName()).isEqualTo("CombatOutcome");

    assertThat(candidates("sd:combat.c1:stage.s1:outcome.o2"))
        .as("o2 属于 s2，不属于 s1 ⇒ 空候选")
        .isEmpty();
  }

  @Test
  void unknownEntityYieldsNoCandidates() {
    assertThat(candidates("sd:nation.ghost")).isEmpty();
    assertThat(candidates("sd:combat.ghost")).isEmpty();
    assertThat(candidates("sd:combat.c1:stage.ghost")).isEmpty();
  }

  @Test
  void unknownOrMissingKindYieldsNoCandidates() {
    assertThat(candidates("sd:hex.3")).as("未知 kind").isEmpty();
    assertThat(candidates("sd:n1")).as("缺 kind 的裸主体").isEmpty();
    assertThat(candidates("sd:nation.n1:extra.x")).as("根实体后接多余段").isEmpty();
  }

  @Test
  void foreignNamespaceYieldsNoCandidates() {
    assertThat(candidates("map:Map1")).isEmpty();
  }

  private static ResolvedSubject one(String text) {
    java.util.List<ResolvedSubject> list = candidates(text);
    assertThat(list).as("期望恰一个候选：%s", text).hasSize(1);
    return list.get(0);
  }

  private static java.util.List<ResolvedSubject> candidates(String text) {
    SdState sd = SdFixtures.full();
    return RESOLVER
        .resolve(Address.parse(text), new ResolveContext(SdWorlds.world(sd), SdWorlds.T0))
        .candidates();
  }
}
