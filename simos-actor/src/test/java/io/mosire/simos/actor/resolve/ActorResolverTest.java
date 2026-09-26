package io.mosire.simos.actor.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@code actor:} 命名空间的地址解析（照 {@code EconomyResolver} 的形制与"空候选 / 抛"分工）。
 *
 * <p>★ <b>认两类主体</b>（{@code ActorData} 的两张带记录的表，每类各一个 kind）：{@code actor}（主体） / {@code goods}（库存）。 ★
 * 库存那一类的名字是**它聚合键的规范串**（{@code <owner>|<location>}）—— 它的逆住在那 个类型自己的 {@code parse}
 * 里，<b>本解析器不复述任何格式</b>； 名字里带 {@code :} 时 canonical 会自动加引（§3.4 的按需加引，由 {@code Address} AST
 * 产出，本类不手写）。
 *
 * <p>★ <b>2026-09-27 裁定 S3</b>：产权（{@code holding} kind）整块退役 ⇒ 本类不再认领它，相关用例随之删除。
 */
class ActorResolverTest {

  private static final ActorResolver RESOLVER = new ActorResolver();
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final ActorRef FARM = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ActorRef LOT = new ActorRef(ActorKind.PEOPLE_LOT, "rural:0_0:MALE:1");
  private static final GoodsAccountKey GRAIN = new GoodsAccountKey(FARM, new HexCoord(0, 0));

  /** 一份已激活的切片：一个庄园主体（AGE）+ 一本账，外加一个 id 里含 {@code :} 的人口批次。 */
  private static final ActorData DATA =
      ActorData.empty()
          .withMeta(Optional.of(new ActorMeta("Map1", 0, "actor-v1")))
          .withActors(
              Map.of(
                  FARM, new Actor(FARM, "农业庄园"),
                  LOT, new Actor(LOT, "0_0 的男性批次")))
          .withAccounts(
              Map.of(GRAIN, new GoodsAccount(GRAIN, Map.of(new CommodityId("grain"), 7L))));

  @Test
  void namespaceIsActor() {
    assertThat(RESOLVER.namespace()).isEqualTo("actor");
  }

  /** 两段：切片根主体。 */
  @Test
  void resolvesTheSliceRoot() {
    ResolvedSubject subject = only("actor:Map1");

    assertThat(subject.id().namespace()).isEqualTo("actor");
    assertThat(subject.id().localId()).isEqualTo("Map1");
    assertThat(subject.typeName()).isEqualTo("ActorData");
    assertThat(subject.canonicalAddress()).isEqualTo("actor:Map1");
  }

  /** 主体：{@code actor.<KIND>.<id>}（id 不含结构字符时不加引）。 */
  @Test
  void resolvesAnActorByItsKindAndId() {
    ResolvedSubject subject = only("actor:Map1:actor.ESTATE.farm@0_0");

    assertThat(subject.id().namespace()).isEqualTo("actor.actor");
    assertThat(subject.id().localId()).as("localId 是主体规范串").isEqualTo("ESTATE:farm@0_0");
    assertThat(subject.typeName()).isEqualTo("Actor");
    assertThat(subject.canonicalAddress()).isEqualTo("actor:Map1:actor.ESTATE.farm@0_0");
  }

  /**
   * ★★ id 里含 {@code :}（人口批次的 {@code rural:0_0:MALE:1}）⇒ **名字整体加引**，且 canonical 由 AST **自动**加
   * （本类不手写加引规则）。
   *
   * <p>★ 加的是**整个名字**（{@code "PEOPLE_LOT.rural:0_0:MALE:1"}）而不是只有 id：§3.4 的加引单位是"名字"， 而 {@code :}
   * 是**结构字符**（{@code AddressText.hasStructuralChar}）—— 与 {@code social} / {@code unit} 侧对含 {@code
   * .} 的名字加引是同一条规则。
   */
  @Test
  void anActorIdWithAColonIsQuotedInTheCanonicalForm() {
    ResolvedSubject subject = only("actor:Map1:actor.PEOPLE_LOT.\"rural:0_0:MALE:1\"");

    assertThat(subject.id().localId()).isEqualTo("PEOPLE_LOT:rural:0_0:MALE:1");
    assertThat(subject.canonicalAddress())
        .as("canonical 是**可解析回来**的那一份（§3.4 的按需加引）")
        .isEqualTo("actor:Map1:actor.\"PEOPLE_LOT.rural:0_0:MALE:1\"");
    assertThat(resolve(subject.canonicalAddress()))
        .as("canonical 是自反的（解析回来仍是同一个主体）")
        .singleElement()
        .extracting(candidate -> candidate.id().localId())
        .isEqualTo("PEOPLE_LOT:rural:0_0:MALE:1");
  }

  /** 库存：名字是 {@code GoodsAccountKey} 的规范串。 */
  @Test
  void resolvesAGoodsAccountByItsCompositeKey() {
    ResolvedSubject subject = only("actor:Map1:goods.\"ESTATE:farm@0_0|0_0\"");

    assertThat(subject.id().namespace()).isEqualTo("actor.goods");
    assertThat(subject.id().localId()).isEqualTo(GRAIN.toString());
    assertThat(subject.typeName()).isEqualTo("GoodsAccount");
    assertThat(subject.canonicalAddress()).isEqualTo("actor:Map1:goods.\"ESTATE:farm@0_0|0_0\"");
  }

  /** ★ 合法但**没有记录**的主体/库存 ⇒ **空候选**（不是错误）。 */
  @Test
  void rowsThatDoNotExistAreEmptyCandidates() {
    assertThat(resolve("actor:Map1:actor.ESTATE.farm@9_9")).isEmpty();
    assertThat(resolve("actor:Map1:goods.\"HOUSEHOLD:house@0_0|0_0\"")).isEmpty();
  }

  /** ★ 本模块不服务的形态一律**空候选**：别的 kind、属性段、Index 段、段数 &gt; 3、缺 kind 的实体。 */
  @Test
  void unservedShapesAreEmptyCandidates() {
    assertThat(resolve("actor:Map1:unit.u-1")).as("其它 kind").isEmpty();
    assertThat(resolve("actor:Map1:actor.ESTATE.farm@0_0:label")).as("属性段（4 段）").isEmpty();
    assertThat(resolve("actor:Map1:[0,0]")).as("Index 段：本切片没有位置型主体").isEmpty();
    assertThat(resolve("actor:Map1:\"Nation.区域A\"")).as("缺 kind 的实体").isEmpty();
    assertThat(resolve("actor:actor.ESTATE")).as("第 2 段（根主体）不许带 kind：那是命名空间自己的位置").isEmpty();
    assertThat(resolve("actor:Map1:holding.\"ESTATE:farm@0_0|0_0\""))
        .as("★ 已退役的 holding kind：不再认领 ⇒ 空候选（不是抛，也不再解析产权键）")
        .isEmpty();
  }

  /** ★ 别的命名空间的合法地址 ⇒ 空候选（认领与否由返回值表达；未知命名空间抛是注册表的职责）。 */
  @Test
  void anotherNamespaceIsNotClaimed() {
    assertThat(resolve("economy:Map1")).isEmpty();
  }

  /**
   * ★★ **认领了的 kind 里名字解析失败 ⇒ 抛**（不包不吞）：词表外的主体种类、缺接缝的库存键各抛它自己那份 IAE。
   *
   * <p>判别力：若把这些异常吞成空候选，"拼错主体种类"与"这个主体不存在"就再也分不开了。
   *
   * <p>★ 第三条原以产权键（{@code holding.noseparator}，{@code AssetHoldingKey.parse} 抛）为夹具；裁定 S3 之后 {@code
   * holding} 已不是本解析器认领的 kind（落进"其它 kind ⇒ 空候选"），故改用同形的**库存键**夹具。
   */
  @Test
  void aBadNameInsideAClaimedKindThrows() {
    assertThatThrownBy(() -> resolve("actor:Map1:actor.MANOR.farm@0_0"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MANOR")
        .hasMessageContaining("ESTATE");
    assertThatThrownBy(() -> resolve("actor:Map1:actor.ESTATE"))
        .as("缺 <id> 那一段（只给了 KIND）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("KIND");
    assertThatThrownBy(() -> resolve("actor:Map1:goods.noseparator"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 装配故障：state 里没有 actor 切片 ⇒ 抛（**不是**"没有候选"）。 */
  @Test
  void aMissingSliceIsAnAssemblyFault() {
    SimulationState withoutActor =
        new SimulationState(new StateMeta(REF, T7), Map.of(), InMemoryInfoSystem.empty());

    assertThatThrownBy(
            () ->
                RESOLVER.resolve(Address.parse("actor:Map1"), new ResolveContext(withoutActor, T7)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("装配故障");
  }

  /** ★ 装配故障：键对得上但类型不对 ⇒ 同样抛（那条分支可达，见 {@code ActorSnapshotsTest} 的同款用例）。 */
  @Test
  void aSliceOfAnotherTypeIsAnAssemblyFault() {
    Snapshot impostor =
        new Snapshot() {
          @Override
          public StateRef ref() {
            return REF;
          }

          @Override
          public SimosTimestamp timestamp() {
            return T7;
          }

          @Override
          public String namespace() {
            return "actor";
          }
        };

    assertThatThrownBy(() -> RESOLVER.resolve(Address.parse("actor:Map1"), ctx(impostor)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是 ActorSnapshot");
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static List<ResolvedSubject> resolve(String address) {
    return RESOLVER
        .resolve(Address.parse(address), ctx(new ActorSnapshot(REF, T7, DATA)))
        .candidates();
  }

  /** 断言恰好一条候选并取它（本类所有正例都恰好一条）。 */
  private static ResolvedSubject only(String address) {
    List<ResolvedSubject> candidates = resolve(address);
    assertThat(candidates).as("恰好一个候选: " + address).hasSize(1);
    return candidates.get(0);
  }

  private static ResolveContext ctx(Snapshot snapshot) {
    return new ResolveContext(
        new SimulationState(
            new StateMeta(REF, T7), Map.of("actor", snapshot), InMemoryInfoSystem.empty()),
        T7);
  }
}
