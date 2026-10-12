package io.mosire.simos.ga.spi;

import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Optional;

/**
 * ★★ <b>决策人归属的只读口</b>（<b>端口在 {@code ga-spi}、实现在 app、装配期注入</b>）——2026-10-12 乙收尾（用户裁定
 * 「<b>归属肯定归模块</b>」＋「单个决策人的具体归属，交给调用 ga 的具体模块不行吗？<b>同样搞工单</b>」）。
 *
 * <p>★★ <b>为什么需要它</b>：本批把归属的<b>表与写口</b>都归位到各持有模块（{@code GovState.nationAffiliations} / {@code
 * GovState.govAffiliations} / {@code ArmyData.armyAffiliations}），而 {@code ga}
 * 仍有<b>两处</b>必须知道"这个人属于谁"：
 *
 * <ol>
 *   <li>{@link io.mosire.simos.ga.decision.DecisionAgentRunner} 的空会话身份消息（"你是决策人 X，归属：…"）；
 *   <li>{@link io.mosire.simos.ga.tools.write.DiplomaticWriteSignature} 的外交写署名（"以谁的名义写入"）。
 * </ol>
 *
 * <p>⇒ 走端口。<b>ga 与 ga-spi 都不认识任何归属种类</b>（没有 {@code nation}/{@code army}/{@code gov} 的枚举、没有 {@code
 * switch}、没有 {@code instanceof}）：本端口只交出<b>不透明受众标签</b>与<b>给人看的一句话</b>，两件事都由<b>持有该归属的模块</b> 渲染（{@code
 * NationAffiliations.KIND/describe} 等）。★ 加一个新模块<b>不需要改 ga 一个字</b>——组合根注册一行即可。
 *
 * <p>★ <b>fail-closed 的分工</b>：<b>装配</b>问题（缺切片/类型不对）当场抛；<b>世界数据</b>问题（没有这个决策人、没有这条归属） 降到"空"——空 =
 * 裸决策人 ⇒ 调用方<b>一律</b>按"没有归属"处置（读不到归属 ⇒ deny-all）。
 */
public interface DecisionMakerAffiliationPort {

  /**
   * <b>什么都不报</b>的实现（{@link #affiliationsOf} 恒空、{@link #diplomaticSignatoryOf} 恒空）：给"旧构造器 / 夹具 /
   * 不跑提示语"的调用方用（形制同 {@code DecisionAgentRunner.OpeningSnapshot#NONE}）。
   *
   * <p>★ <b>它只影响提示语文本与外交署名判定，不影响任何资源范围</b>（权限面走的是 app 的 {@code DecisionScopeFunctions}，不吃本端口）； ★
   * <b>生产装配必须给真实现</b>（{@code Shell} 那一份），否则模型看到的身份消息会写"没有归属"。
   */
  DecisionMakerAffiliationPort NONE =
      new DecisionMakerAffiliationPort() {
        @Override
        public List<AffiliationClaim> affiliationsOf(
            DecisionMakerId decisionMaker, SimulationState state) {
          return List.of();
        }

        @Override
        public Optional<String> diplomaticSignatoryOf(
            DecisionMakerId decisionMaker, SimulationState state) {
          return Optional.empty();
        }
      };

  /**
   * 该决策人此刻的归属（**各模块自报**，按标签升序；同一个决策人可能同时被多个模块认领）。
   *
   * <p>★ <b>空 = 裸决策人</b>（用户原话：「和 Unit 一样，不但允许创建没有更多附加状态的单个决策人」）⇒ 调用方一律按"没有归属" 处置，<b>不抛、不回落</b>。
   */
  List<AffiliationClaim> affiliationsOf(DecisionMakerId decisionMaker, SimulationState state);

  /**
   * 该决策人<b>以谁的名义</b>做外交写入（引用原文）；<b>空 = 他没有外交身份</b> ⇒ 外交写入一律拒（fail-closed）。
   *
   * <p>★★ <b>为什么以"能做什么"命名、而不是以"哪种归属"命名</b>：解释"哪一类归属算外交身份"是<b>提供方</b>（组合根 + 各模块）的事 ⇒ ga
   * 侧因此不含任何归属种类的字样（这是用户「后面新加模块 ga 还得显式地重新添加？不可能的」的兑现点）。
   */
  Optional<String> diplomaticSignatoryOf(DecisionMakerId decisionMaker, SimulationState state);

  /**
   * 一条归属（<b>不含种类名</b>——种类是各模块自己的事，ga 不需要也不该知道）。
   *
   * @param label <b>不透明受众标签</b>（{@code SdInfoEntry.affiliations} 里用的就是同一套串；写入方自报、读取方只做集合成员判定）
   * @param description 给人看的一句话（提示语/拒因里嵌的那段文本，由持有该归属的模块渲染）
   */
  record AffiliationClaim(String label, String description) {}
}
