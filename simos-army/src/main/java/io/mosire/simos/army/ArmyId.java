package io.mosire.simos.army;

/**
 * 军队 id（调用方给的短名，spec §二.1）：军队身份（{@link Army}）的稳定身份。裸值 {@code toString()} + {@code static parse}
 * 三件套（铁律 1：地址是定位方式，**ID 是身份**）；不自增、不用随机 UUID。
 *
 * <p>★★ <b>2026-10-12 批次 3d-1：本类型自 {@code io.mosire.simos.sd.id} <b>整条搬进</b> {@code simos-army}</b>
 * ——用户裁定「<b>战斗模块，包括阶段之类的，归 Army 模块管</b>」＋「<b>sd 整个弃用！</b>」⇒ 军队身份（连同 {@code armies} 表与两条 {@code
 * army.*} 命令）归 army。类型形状（record 组件、构造期判据、异常文案、{@code toString()}/{@code parse} 语义）
 * <b>逐字未改</b>，只换了包。
 *
 * <p>★★ <b>为什么它必须落在 army 而不是留在 sd</b>：{@code sd} 的 enforcer <b>禁 {@code simos-army}</b>（防环）， 而
 * {@code armies} 表搬进 army 后 sd 连它的存在性都判不了 ⇒ 留一份在 sd 就是"同一身份两处拼写点"，且 {@code SdState} 再也 无法持有这张表。搬走之后
 * sd 侧只剩一个 <b>不透明引用</b>（{@code Affiliation.Army(String)}，见该类型的类注）。
 *
 * <p>★ <b>方向是"更底层"</b>：army 的允许面（util + map + unit + social + gov + ga-spi）里，{@code unit} 与 {@code
 * app} 都能看见它（{@code app} 认识全部模块）⇒ {@code Affiliation.Army} 的不透明串由 <b>army 自己</b>解释 （{@link
 * ArmyAffiliations}），不需要任何人反向依赖 sd。
 */
public record ArmyId(String value) {

  public ArmyId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ArmyId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static ArmyId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ArmyId 不得为空白: " + text);
    }
    return new ArmyId(text);
  }
}
