package io.mosire.simos.ga.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>判据 C：本层"接口契约"的形状不变量 —— 取值域 / default 语义 / 不许长出第二处声明面</b>。
 *
 * <h2>它守的是哪种坏法</h2>
 *
 * <p>这一层是<b>契约</b>：它的坏法全都"编译得过、运行期没有症状"，因为契约的消费方（桶装配、判类审计、决策包执行器） 各自按自己的读法理解它：
 *
 * <ul>
 *   <li>{@link ToolRole} 是 {@code roles()} 的<b>取值域</b>，而全仓到处是 {@code Set<ToolRole>}（类型系统拦不住新常量） ⇒
 *       加一个角色<b>不会</b>让任何一处编译失败，却会让"谁属于决策人面"的口径分叉（判类只看"含不含 DM"）；
 *   <li>{@link DecisionProposable} 的两个方法都是 {@code default}：默认值就是 fail-closed 的方向，改宽了<b>所有</b>提议
 *       工具一起放宽；而它故意<b>没有</b> {@code toolName()} / {@code tool()}（本类注：那是冗余的第三处声明）；
 *   <li>{@link GmOnlyRead} 是迁移中间态的<b>纯标记</b>（B4 起由 {@code roles() = {GM}} 表达）：它一旦长出成员，就多一处
 *       可漂的声明面，而实现者一行都不用改（没有编译期提醒）；
 *   <li>{@link DecisionMakerAffiliationPort#NONE} 是"什么都不报"的缺省实现：它的空答复正是外交写入的 fail-closed 依据。
 * </ul>
 */
class SpiContractInvariantsTest {

  /**
   * ★★ <b>判据 C：{@code ToolRole} 的取值域恰好是 {@code {GM, DM}}</b>。
   *
   * <p>守的坏法：新角色（例如将来把"系统"或"观察者"也做成角色）加进本枚举时，<b>没有一处编译会红</b>——到处是 {@code Set<ToolRole>}，而桶装配按 {@code
   * contains(GM)}/{@code contains(DM)} 分流、判类审计按"含不含 DM"判 ⇒ 一条 {@code {DM, 新角色}}
   * 的工具会<b>静默</b>落进决策人面（可达面被放大）。这条断言把"加角色"变成一次 <b>必须同时改口径与台账</b>的动作。
   */
  @Test
  void theToolRoleValueDomainIsExactlyGmAndDm() {
    assertThat(ToolRole.values())
        .as("★★ 判据 C：roles() 的取值域就是这两个（加第三个 = 桶装配/判类口径静默分叉，且无编译错误）")
        .containsExactly(ToolRole.GM, ToolRole.DM);
  }

  /**
   * ★★ <b>判据 C + B：{@link DecisionProposable} 的 default 语义是"窄"的那一侧，且默认集合不可变</b>。
   *
   * <p>守的坏法（两个 default 各自放宽一条越界判定）：
   *
   * <ul>
   *   <li>{@code createsUnit()} 默认改成 {@code true} ⇒ 目标提取会对<b>所有</b>提议工具跳过"与参数/预览里 {@code newUnitId}
   *       逐字相等的单位 id"的判定（本意只给创建型工具）；
   *   <li>{@code skipKeys()} 默认非空 ⇒ 所有工具的某个字段静默不参与目标提取（"判不了就当没有目标"）。
   * </ul>
   *
   * <p>★ 并断言默认集合<b>真不可变</b>：返回一个共享的可变默认集 ⇒ 谁都能改掉"所有工具的默认"，而它是<b>同一个实例</b>。
   */
  @Test
  void decisionProposableDefaultsAreTheNarrowOnesAndItsSetIsImmutable() {
    DecisionProposable bare = new DecisionProposable() {};

    assertThat(bare.skipKeys()).as("★★ 判据 C：默认不跳过任何字段（非空默认 = 所有工具的目标提取静默漏字段）").isEmpty();
    assertThatThrownBy(() -> bare.skipKeys().add("newUnitId"))
        .as("★★ 判据 C+B：默认集合必须真不可变（可变默认集 = 一处改动影响所有工具）")
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(bare.createsUnit())
        .as("★★ 判据 C+B：默认**不是**创建型工具（默认 true = 所有工具都享受「新 id 尚不存在」的豁免）")
        .isFalse();
  }

  /**
   * ★★ <b>判据 C：{@link DecisionProposable} 只有两个 {@code default} 方法，<b>没有</b>承载名字的第三处声明</b>。
   *
   * <p>守的坏法：旧形状（{@code simos-ga} 版）有 {@code toolName()} / {@code tool()}——它们与 {@code
   * AgentTool.name()} / {@code this} 是<b>两处互不相干的声明</b>，漂移无症状（本接口的类注逐字记着"故删除"）。 谁把它们加回来（哪怕作为
   * default），实现者就会重新开始自报名字。
   */
  @Test
  void decisionProposableCarriesNoThirdNameSurface() {
    Method[] declared = DecisionProposable.class.getDeclaredMethods();

    assertThat(Arrays.stream(declared).map(Method::getName))
        .as("★★ 判据 C：本接口只声明「跳过哪些键」与「是不是创建型」两件事；toolName()/tool() 是已删除的第三处声明")
        .containsExactlyInAnyOrder("skipKeys", "createsUnit");
    assertThat(declared)
        .as("★ 两个方法都必须是 default（实现者只需实现本接口即可被提议，不必再报名字）")
        .allSatisfy(
            method ->
                assertThat(method.isDefault()).as("%s 必须是 default", method.getName()).isTrue());
  }

  /**
   * ★ <b>判据 C：{@link GmOnlyRead} 保持<b>纯标记</b>（零成员）</b>。
   *
   * <p>它是 2026-10-12 B1 迁入的<b>迁移中间态</b>：类注写明"B4 起『只给 GM 看』由 {@code roles()} 表达（{@code {GM}}），
   * 本标记随旧文件一并退场"。守的坏法：它长出成员（哪怕 default）就变成第二处可漂的"归属"声明，而实现它的工具类 <b>一行都不用改</b>（default 方法不强制实现）⇒
   * 没有编译期提醒。
   */
  @Test
  void gmOnlyReadStaysAPureMarker() {
    assertThat(GmOnlyRead.class.getDeclaredMethods())
        .as("★ 判据 C：GmOnlyRead 是纯标记（零方法）——归属由 roles() = {GM} 表达，这里多一个方法就是第二处声明")
        .isEmpty();
    assertThat(GmOnlyRead.class.getDeclaredFields()).as("★ 判据 C：纯标记也不该带常量（常量会长成第二处口径）").isEmpty();
  }

  /**
   * ★★ <b>判据 B + C：{@link DecisionMakerAffiliationPort#NONE} 恒报"没有归属 / 没有外交身份"</b>。
   *
   * <p>守的坏法：该端口的类注写着 {@code diplomaticSignatoryOf} "空 = 他没有外交身份 ⇒ 外交写入一律拒（fail-closed）"。 {@code
   * NONE} 正是"没装配真实现"（旧构造器 / 夹具 / 不跑提示语）时的取值 ⇒ 它若回落成任何一个默认署名， <b>外交写入会从 deny-all
   * 变成"谁都能以某个名义写"</b>，而组合根那边看不出差别。
   */
  @Test
  void theNoOpAffiliationPortReportsNothingSoDiplomaticWritesStayClosed() {
    SimulationState state =
        new SimulationState(
            new StateMeta(
                new StateRef(new BranchId("main"), new RevisionId(7)), SimosTimestamp.of(3)),
            Map.of(),
            InMemoryInfoSystem.empty());
    DecisionMakerId decisionMaker = new DecisionMakerId("alice");

    assertThat(DecisionMakerAffiliationPort.NONE.affiliationsOf(decisionMaker, state))
        .as("★ 判据 B：NONE 恒报空（空 = 裸决策人 ⇒ 调用方按「没有归属」处置）")
        .isEmpty();
    assertThat(DecisionMakerAffiliationPort.NONE.diplomaticSignatoryOf(decisionMaker, state))
        .as("★★ 判据 B：NONE 没有外交身份 ⇒ 外交写入一律拒（回落成默认署名 = 静默放宽）")
        .isEmpty();
  }
}
