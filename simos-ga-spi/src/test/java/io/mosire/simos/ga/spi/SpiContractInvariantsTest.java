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
 *   <li>{@link ToolRole} 是 {@code toolRoles()} 的<b>取值域</b>，而全仓到处是 {@code Set<ToolRole>}（类型系统拦不住新常量）
 *       ⇒ 加一个角色<b>不会</b>让任何一处编译失败，却会让"谁属于决策人面"的口径分叉（判类只看"含不含 DM"）；
 *   <li>{@code DecisionProposable} 已随「所有 Ask 自动入包」退役；若它被加回来，就会重新长出第二套「可提议面」；
 *   <li>{@link GmOnlyRead} 是迁移中间态的<b>纯标记</b>（B4 起由 {@code toolRoles() = {GM}} 表达）：它一旦长出成员，就多一处
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
        .as("★★ 判据 C：toolRoles() 的取值域就是这两个（加第三个 = 桶装配/判类口径静默分叉，且无编译错误）")
        .containsExactly(ToolRole.GM, ToolRole.DM);
  }

  /**
   * ★★ <b>判据 C + 设计 §7.3：{@code DecisionProposable} 已退役，不得重新长出第二套「可提议面」</b>。
   *
   * <p>所有 {@code ToolGate.Ask} 现在都由 DM 回合绑定自动入包；能否入包由权限链与 Ask 决定，不再由工具自报。这个类型若被加回来，
   * 就会再次出现「谁能入包」的第二处声明，且没有任何编译错误提醒调用方。
   */
  @Test
  void decisionProposableIsRetired() {
    assertThatThrownBy(
            () ->
                Class.forName(
                    "io.mosire.simos.ga.spi.DecisionProposable",
                    false,
                    SpiContractInvariantsTest.class.getClassLoader()))
        .as("★★ 设计 §7.3/§10 D4：DecisionProposable 已退役；此类型存在 = 第二套可提议面回流")
        .isInstanceOf(ClassNotFoundException.class);
  }

  /**
   * ★ <b>判据 C：{@link GmOnlyRead} 保持<b>纯标记</b>（零成员）</b>。
   *
   * <p>它是 2026-10-12 B1 迁入的<b>迁移中间态</b>：类注写明"B4 起『只给 GM 看』由 {@code toolRoles()} 表达（{@code {GM}}），
   * 本标记随旧文件一并退场"。守的坏法：它长出成员（哪怕 default）就变成第二处可漂的"归属"声明，而实现它的工具类 <b>一行都不用改</b>（default 方法不强制实现）⇒
   * 没有编译期提醒。
   */
  @Test
  void gmOnlyReadStaysAPureMarker() {
    assertThat(GmOnlyRead.class.getDeclaredMethods())
        .as("★ 判据 C：GmOnlyRead 是纯标记（零方法）——归属由 toolRoles() = {GM} 表达，这里多一个方法就是第二处声明")
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
