package io.mosire.simos.economy.api.money;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import java.util.Optional;

/**
 * ★★ <b>货币工具</b>（M1.1）：一张<b>具体的钱</b> —— "{@link #currency() 哪一种币} + {@link #kind() 哪一种东西} + 由谁发行"。
 *
 * <pre>
 * 币种（CurrencyId）   "这是银"          ← 计价单位，M1.1 之前只有这一维
 * 工具（本类型）        "这是银币 / 国库宝钞 / 钱庄存款"  ← M1.1 新增的那一维
 * </pre>
 *
 * <p>★★ <b>它只加身份，不动账</b>（铁律 2 不受影响）：本类型<b>不是</b>余额的容器 —— 钱仍然只存在 {@code HouseholdInventory.money}
 * 里、键仍然是 {@link CurrencyId}（裁定"旧的 {@code CurrencyId} 读口保留"）。 故 M1.1
 * <b>不产生任何新的写入点</b>：没有命令、没有账、没有算式。★ 铸熔、成色、兑现都不在本批（M4+）。
 *
 * <p>★★ <b>构造期守卫（三条，逐条给理由）</b>：
 *
 * <ol>
 *   <li><b>五个组件都不是 null</b>（含两个 {@code Optional} 容器本身）—— "缺值"与"没填"在本仓是两件事：前者用 {@code
 *       Optional.empty()}，后者是坏数据，当场抛。
 *   <li><b>{@link InstrumentKind#STATE_NOTE} / {@link InstrumentKind#BANK_DEPOSIT} 必须有 {@code
 *       issuer}</b>： 它们的价值<b>就是</b>对发行人的索取 ⇒ 少了发行人，这张工具在账上读不出"谁欠我"，也就没有价值来源。
 *   <li><b>{@link InstrumentKind#SPECIE} 的 {@code issuer} 必须为空</b>（见下面那条 ★★）。
 * </ol>
 *
 * <p>★★ <b>{@code SPECIE} 的 issuer 该不该"必须为空"？—— 该，理由三条</b>（这是被点名要想清楚的那一处，不拍脑袋）：
 *
 * <ol>
 *   <li><b>字段的语义是"这张工具的价值是对谁的索取"（债权人槽位），不是"谁做的"</b>。金属币的价值来自金属本身（成色/重量）⇒
 *       <b>没有</b>这样一个对象。把"铸主"填进来，读的人会把它读成"某人欠我一张银币" —— 正是本仓最反对的"看起来在记、其实被 误读"。
 *   <li><b>"谁铸的"是另一个事实</b>（铸熔/成色，M4+）。同一个字段不许承载两个语义：M4 真要记铸主时，加的是<b>另一个</b> 字段（{@code minter}
 *       之类），而不是复用这个槽位。
 *   <li><b>与"缺 issuer 就抛"合起来，{@code kind ⇒ issuer} 是一条全覆盖的规则</b>（{@code SPECIE} ⇒ 必空、{@code
 *       STATE_NOTE}/{@code BANK_DEPOSIT} ⇒ 必非空），没有"看情况"的第三档 —— 读的人不必记例外，测试可以逐档钉死。
 * </ol>
 *
 * ★ <b>反向代价如实记</b>：M1.1 因此<b>记不下</b>"这枚银币是哪家铸的"。这是刻意的（铸熔属 M4+），且它<b>不是</b>把守卫放松 就能补的：放松守卫 =
 * 静默开始接受以前判为非法的状态，而加字段是机械改动。
 *
 * <p>★★ <b>与 master plan §M1.1 的签名有一处有意的差异</b>：那里写的是 {@code ActorRef issuer}，本类写成 {@code
 * Optional<ActorRef> issuer} —— 因为"{@code SPECIE} 可无发行人"在非空 {@code ActorRef} 上<b>根本无法表达</b>， 除非允许
 * {@code null}；而 {@code null} 在本仓不是"没有"的形制（同族的"可无"一律用 {@code Optional}：{@code Debt.commodity} /
 * {@code ActorData.meta} / {@code Transfer.settles}）。★ 语义逐条不变：守卫仍是上面那三条。
 *
 * <p>★ <b>{@code redeemer} 是具名留位</b>（本仓禁"看起来在记、其实永远不被读"的<b>静默</b>字段 ⇒ 具名写在这里）： 它回答"谁来把它兑回"，而<b>兑现属
 * M4+</b> ⇒ 本批唯一读它的是 {@code ApiViews} 的读口（发出去给人看），<b>没有任何算式读它</b>。 ★ 本批<b>不</b>给它加规则（"有 issuer 才有
 * redeemer"之类）：兑现的设计还不存在，拍一条守卫就是编造设计 ⇒ M4 兑现落地时 在这里补规则 + 用例。
 *
 * @param id 工具的稳定身份（非 null；与"币种"是两个命名空间）
 * @param currency 这张工具是**哪一种币**（非 null；计价单位）
 * @param kind 这张工具是**哪一种东西**（非 null；决定 {@code issuer} 的规则）
 * @param issuer 发行人 / 索取对象：{@code STATE_NOTE} 与 {@code BANK_DEPOSIT} <b>必须有</b>；{@code SPECIE}
 *     <b>必须为空</b>
 * @param redeemer 兑现人（**留位**：兑现属 M4+，本批不判也不读进任何算式）
 */
public record MoneyInstrument(
    InstrumentId id,
    CurrencyId currency,
    InstrumentKind kind,
    Optional<ActorRef> issuer,
    Optional<ActorRef> redeemer) {

  public MoneyInstrument {
    if (id == null) {
      throw new IllegalArgumentException("MoneyInstrument.id 不得为 null");
    }
    if (currency == null) {
      throw new IllegalArgumentException("MoneyInstrument.currency 不得为 null（说不出币种就不是一张钱）");
    }
    if (kind == null) {
      throw new IllegalArgumentException("MoneyInstrument.kind 不得为 null（说不出工具种类就不是一张钱）");
    }
    if (issuer == null) {
      throw new IllegalArgumentException(
          "MoneyInstrument.issuer 不得为 null（没有发行人用 Optional.empty()）");
    }
    if (redeemer == null) {
      throw new IllegalArgumentException(
          "MoneyInstrument.redeemer 不得为 null（没有兑现人用 Optional.empty()）");
    }
    if (kind.requiresIssuer() && issuer.isEmpty()) {
      throw new IllegalArgumentException(
          "货币工具 " + id + "（" + kind.label() + "）必须有发行人：它的价值就是对发行人的索取，少了发行人就读不出「谁欠我」");
    }
    if (!kind.requiresIssuer() && issuer.isPresent()) {
      throw new IllegalArgumentException(
          "货币工具 "
              + id
              + "（"
              + kind.label()
              + "）没有发行人：issuer 是「这张工具的价值是对谁的索取」的槽位，而金属币的价值来自金属本身（"
              + "「谁铸的」是 M4 铸熔/成色的另一个事实，不许填进这个槽位）");
    }
  }
}
