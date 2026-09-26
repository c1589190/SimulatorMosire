package io.mosire.simos.actor.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 {@code EconomyRoundTripTest} / {@code LedgerRoundTripTest}）：反射枚举 {@link
 * ActorData} 的 record 组件，逐组件造差异，三条断言 —— 新增状态组件若忘了进变更集，本测试自动红。
 *
 * <p>★ <b>它也是本任务的"变异自证"落点</b>：把 {@code ActorChangeSet.between} 里任一分量改成恒 {@code Unchanged}，
 * "该组件参与"那条断言当场红。
 *
 * <p>★ <b>结构断言（Review Focus ①）也在本类</b>：{@link Actor} 的组件必须恰是 {@code ref} 与 {@code label} —— spec §三
 * L283 的禁令（不许 {@code ActorRow { Money money; List<Debt> debts; }}）在结构上可判，故用反射钉死。
 *
 * <p>★ <b>往返不是"两个空对象也相等"</b>：{@link #roundTripRebuildsEveryFieldOfANonTrivialTarget()} **逐字段**断言
 * 重建结果（meta 三个组件 + 每条主体 的键 / ref / label），整体 {@code equals} 只是最后一条。
 */
class ActorRoundTripTest {

  /** 产业型主体（spec §三：{@code ESTATE} 是农业生产的制度身份之一）。 */
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");

  /** ★ 人口批次型主体：{@code id} 里**自带两个冒号**（批次的 id 就是这个形状）—— 键的规范串逆靠它取得判别力。 */
  private static final ActorRef HOUSEHOLD = new ActorRef(ActorKind.HOUSEHOLD, "rural:0_0:MALE:1");

  private static final ActorMeta META = new ActorMeta("levant", 7L, "rules-r1");

  /** ★ 唯一的豁免集合：本切片的 {@code ActorData} 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── 铁律 5：反射枚举 ────────────────────────────────────────────────────────────────

  @Test
  void everyActorDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : ActorData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      ActorData base = ActorData.empty();
      ActorData target = mutate(base, name);
      ActorChangeSet cs = ActorChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(ActorChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("ActorData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyTwoComponents() {
    assertThat(ActorChangeSet.class.getRecordComponents()).hasSize(2);
    assertThat(componentNames(ActorChangeSet.class))
        .as("变更集的每个组件都必须在 ActorData 里有同名的 record 组件")
        .isSubsetOf(componentNames(ActorData.class));
    assertThat(componentNames(ActorData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(ActorChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsActor() {
    ActorSnapshot snapshot =
        new ActorSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            ActorData.empty());
    assertThat(snapshot.namespace())
        .as("★ SimulationState 构造期校验 modules 的键 == snapshot.namespace() ⇒ 这个字面量是装配契约")
        .isEqualTo("actor");
  }

  // ── Review Focus ①：结构断言 ───────────────────────────────────────────────────────

  /**
   * ★★ <b>{@link Actor} 的组件必须恰是 {@code ref} 与 {@code label}</b>（brief Step 5）。
   *
   * <p>★ <b>用 {@code containsExactlyInAnyOrder} 而不是 {@code contains}</b>：这条断言的价值全在<b>否定性</b> ——
   * spec §三 L283 的禁令点名 {@code ActorRow { Money money; List<Debt> debts; }} 这种形状，多一个组件就必须当场红。 {@code
   * contains} 挡不住"塞回来的那个"，等于没测。
   */
  @Test
  void actorHasExactlyRefAndLabel() {
    assertThat(componentNames(Actor.class))
        .as("★★ spec §三 L283 的禁令：Actor 只许有 ref 与 label 两个组件（资产/库存/货币/债务都不在本体里）")
        .containsExactlyInAnyOrder("ref", "label");
  }

  // ── 往返：逐字段 ──────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>逐字段往返</b>（brief Step 3 的原文）：断言 {@code apply(between(base, target), base)} <b>逐字段</b> 重建出
   * {@code target}。
   *
   * <p>★ 为什么不能只写一条 {@code assertThat(rebuilt).isEqualTo(target)}：{@code base} 与 {@code target}
   * 都为"空"时它也成立 —— <b>那样一条测试的判别力为零</b>（本仓反复踩过的"判别力假货"）。故这里 target 非平凡、 且每个组件的每个字段各断言一次。
   */
  @Test
  void roundTripRebuildsEveryFieldOfANonTrivialTarget() {
    ActorData base = ActorData.empty();
    ActorData target =
        base.withMeta(Optional.of(META))
            .withActor(new Actor(ESTATE, "庄园"))
            .withActor(new Actor(HOUSEHOLD, "佃农家户"));

    ActorChangeSet cs = ActorChangeSet.between(base, target);
    ActorData rebuilt = ActorChangeSet.apply(cs, base);

    // ① 两个组件都真的进了变更集（否则"相等"可能只是因为两边都没动）
    assertThat(cs.meta().changed()).as("meta 参与").isTrue();
    assertThat(cs.actors().changed()).as("actors 参与").isTrue();
    assertThat(cs.meta()).as("空 → 有值 = Upsert").isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.actors()).as("从空表加两条 = Upsert").isInstanceOf(FieldDelta.Upsert.class);

    // ② 逐字段：meta 的三个组件
    assertThat(rebuilt.meta()).as("meta 必须被重建出来（不是空 Optional）").isPresent();
    ActorMeta meta = rebuilt.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo(META.mapId());
    assertThat(meta.activatedDay()).isEqualTo(META.activatedDay());
    assertThat(meta.rulesVersion()).isEqualTo(META.rulesVersion());

    // ③ 逐字段：actors 的键 + 每条的两个组件（先整体比对两条与它们的序，再逐字段点开）
    assertThat(rebuilt.actors()).containsExactlyEntriesOf(target.actors());
    assertThat(rebuilt.actors().get(ESTATE).ref()).isEqualTo(ESTATE);
    assertThat(rebuilt.actors().get(ESTATE).label()).isEqualTo("庄园");
    assertThat(rebuilt.actors().get(HOUSEHOLD).ref()).isEqualTo(HOUSEHOLD);
    assertThat(rebuilt.actors().get(HOUSEHOLD).label()).isEqualTo("佃农家户");

    // ④ 最后才是整体相等（它不能是唯一一条：空对象也满足它）
    assertThat(rebuilt).isEqualTo(target);
  }

  /**
   * ★ 往返的另一半：<b>删除与覆盖</b>。
   *
   * <p>★ 只测"从空表往里加"会漏掉 {@link FieldDelta} 四条变体里的 {@code Remove} 与 {@code Patch} —— 上一条用例只走到 {@code
   * Upsert}。这一条刻意让两侧<b>又删又改</b>。
   */
  @Test
  void roundTripAlsoRemovesAndOverwrites() {
    ActorData base =
        ActorData.empty()
            .withMeta(Optional.of(META))
            .withActor(new Actor(ESTATE, "庄园"))
            .withActor(new Actor(HOUSEHOLD, "佃农家户"));
    // target：撤掉家户、给庄园改名 ⇒ 同一组件上"既删又改"
    ActorData target =
        ActorData.empty().withMeta(Optional.of(META)).withActor(new Actor(ESTATE, "东庄"));

    ActorChangeSet cs = ActorChangeSet.between(base, target);
    assertThat(cs.actors()).as("既删又改 ⇒ Patch（两侧都不许丢）").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.meta())
        .as("两侧 meta 相同 ⇒ Unchanged（不是空对象）")
        .isInstanceOf(FieldDelta.Unchanged.class);

    ActorData rebuilt = ActorChangeSet.apply(cs, base);
    assertThat(rebuilt.actors()).containsExactlyEntriesOf(target.actors());
    assertThat(rebuilt.actors().get(ESTATE).label()).isEqualTo("东庄");
    assertThat(rebuilt.actors()).as("被撤掉的主体不许留在重建结果里").doesNotContainKey(HOUSEHOLD);
    assertThat(rebuilt).isEqualTo(target);
  }

  // ── 键：规范串的逆（裁定 R4 的落点） ────────────────────────────────────────────────

  /**
   * ★★ <b>键的规范串逆</b>：{@code FieldDelta} 的键是 {@code toString()} 的产物（{@code "<KIND>:<id>"}），
   * 而它的逆住在**上游** {@link ActorRef#parseCanonical(String)}（与 {@code toString()} 同处一个文件、 共用同一个分隔符常量）——
   * 本切片只委托，**不知道分隔符是什么、也不判断按第几个切**。
   *
   * <p>★ <b>判别力全在夹具的 id 上</b>：{@link #HOUSEHOLD} 的 id 自带两个冒号 ⇒ 上游若把切法改成按
   * <b>最后一个</b>冒号切、或按全部冒号切，本切片的往返**当场红**。★ 前置断言把这个前提也钉住： 夹具改简单了，这条用例会自己响。
   */
  @Test
  void roundTripParsesActorKeysWhoseIdContainsColons() {
    ActorData target = ActorData.empty().withActor(new Actor(HOUSEHOLD, "佃农家户"));

    assertThat(HOUSEHOLD.toString())
        .as("前置：夹具的规范串确实自带两个冒号（否则这条用例没有判别力）")
        .isEqualTo("HOUSEHOLD:rural:0_0:MALE:1");

    ActorRef rebuiltKey =
        ActorChangeSet.apply(ActorChangeSet.between(ActorData.empty(), target), ActorData.empty())
            .actors()
            .keySet()
            .iterator()
            .next();

    assertThat(rebuiltKey.kind()).as("分隔符之前那段是种类").isEqualTo(ActorKind.HOUSEHOLD);
    assertThat(rebuiltKey.id()).as("id 里的冒号必须原样回到 id 那一侧").isEqualTo("rural:0_0:MALE:1");
    assertThat(rebuiltKey).isEqualTo(HOUSEHOLD);
  }

  /**
   * ★ 格式不对的键<b>宁抛不静默</b>（口径照 {@code ClassKey#parse}；实现上是上游 {@link ActorRef#parseCanonical(String)}
   * 的拒绝）。
   *
   * <p>★ 这条只在"变更集不是 {@code between} 产出的"时才可达（手搓 {@link FieldDelta.Upsert}，例如将来的 codec 读进一条坏字节）——
   * 正是那种输入最需要一句<b>指名道姓</b>的抛，而不是 {@code StringIndexOutOfBoundsException}。
   *
   * <p>★★ <b>断言的是上游那句话（"规范串"）而不是切片自己的词</b>：这本身就是"拼写点在上游"的<b>可执行证据</b>——
   * 若哪天有人把逆又抄回本切片，消息文案一变，这条断言当场红。
   *
   * <p>★ 三档坏输入各打一条分支：<b>没有分隔符</b>（空串，{@code indexOf} 返回 −1）、<b>分隔符在首</b>（切出来种类为空）、 <b>分隔符在尾</b>（切出来
   * id 为空）。
   */
  @Test
  void aKeyThatIsNotAWellFormedActorRefIsRejected() {
    for (String bad : new String[] {"", "NO_SEPARATOR", ":farm@0_0", "ESTATE:"}) {
      ActorChangeSet handMade =
          new ActorChangeSet(null, new FieldDelta.Upsert<>(Map.of(bad, new Actor(ESTATE, "庄园"))));

      assertThatThrownBy(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
          .as("坏键「%s」必须抛，且消息来自上游的规范串校验", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("规范串");
    }
  }

  /**
   * ★ 上一条的对照：<b>键合法时一个都不抛</b>。
   *
   * <p>★ 为什么要有这条：否则上一条的"红"可能来自 {@code apply} 的别处（例如 meta 那一路），读不出是哪一条规则失守。
   */
  @Test
  void aWellFormedKeyIsAccepted() {
    ActorChangeSet handMade =
        new ActorChangeSet(
            null, new FieldDelta.Upsert<>(Map.of(ESTATE.toString(), new Actor(ESTATE, "庄园"))));

    assertThatCode(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
        .doesNotThrowAnyException();
    assertThat(ActorChangeSet.apply(handMade, ActorData.empty()).actors()).containsOnlyKeys(ESTATE);
  }

  // ── 写入口：键从值派生 ────────────────────────────────────────────────────────────

  /**
   * ★★ <b>{@code withActor} 的键从值派生</b>（控制方裁定："键从值派生"只许有一个拼写点）。
   *
   * <p>★ 同一个 {@code ref} 写两次 ⇒ <b>后写覆盖前写</b>（调用方给的是"这个主体是谁"，不是"追加一条"）。
   */
  @Test
  void withActorDerivesTheKeyFromTheValue() {
    ActorData once = ActorData.empty().withActor(new Actor(ESTATE, "庄园"));
    assertThat(once.actors()).containsExactly(Map.entry(ESTATE, new Actor(ESTATE, "庄园")));
    assertThat(once.actors().keySet().iterator().next())
        .as("键恰是 value.ref()（不是另一条独立入参，故调用方拼不出不一致的键）")
        .isEqualTo(once.actors().values().iterator().next().ref());

    ActorData twice = once.withActor(new Actor(ESTATE, "东庄"));
    assertThat(twice.actors()).as("同一个 ref 写两次 ⇒ 只有一条").hasSize(1);
    assertThat(twice.actors().get(ESTATE).label()).isEqualTo("东庄");
  }

  // ── 旧档兼容 ─────────────────────────────────────────────────────────────────────

  /**
   * ★ 缺键（旧档）⇒ 该组件 {@code Unchanged}，**此处不抛**（照 {@code LedgerChangeSet} / {@code EconomyChangeSet}
   * 的口径）。
   */
  @Test
  void aChangeSetFromAnOldArchiveTreatsMissingComponentsAsUnchanged() {
    ActorChangeSet fromOldArchive = new ActorChangeSet(null, null);

    assertThat(fromOldArchive.isEmpty()).as("旧档没提该组件 ⇒ 就是没动它").isTrue();
    assertThat(ActorChangeSet.apply(fromOldArchive, ActorData.empty()))
        .as("一字未动的往返必须恒等")
        .isEqualTo(ActorData.empty());
  }

  // ── 夹具与反射小工具 ──────────────────────────────────────────────────────────────

  private static ActorData mutate(ActorData base, String name) {
    return switch (name) {
      case "meta" -> base.withMeta(Optional.of(META));
      case "actors" -> base.withActor(new Actor(ESTATE, "庄园"));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(ActorChangeSet cs, String name) {
    return switch (name) {
      case "meta" -> cs.meta().changed();
      case "actors" -> cs.actors().changed();
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
