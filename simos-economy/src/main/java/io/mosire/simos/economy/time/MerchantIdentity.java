package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>M-C：「是不是跑商 / 是不是纯商号」的唯一拼写点</b>（§12 H-2、§11 J-A、§13 I-A/I-D）。
 *
 * <p>★★ <b>为什么要有这个类</b>：M-A1 已把「商号行」（{@code MerchantFirm} / {@code EconomyData.merchantFirms}） 整体退役
 * ⇒ 「纯商号」不能再判「有没有商号行」，只能判<b>位置</b>（用户 2026-10-10 裁定："选择跑商那就算提供运力了"）。 ★ 判据此前散在 {@code
 * MerchantCapacityPool} 的私有方法里，M-C 又要在**结算路径**（免运费 / 运费独立计算）与 **利润读数**里用同一个判据 ⇒
 * 抽成单一拼写点，<b>不许出现第二处</b> {@code DefaultProductionModes.MERCHANT} 比较。
 *
 * <pre>
 * selectsMerchant   「选了跑商」= effectivePositionIds()（主业 ∪ 副业）里含任一 modeId == merchant 的位置
 *                   ⇒ M-A1 的**运力池成员判据**（J-A）；★ 与 M-A1 逐字同式，只是搬到本类
 * isPureMerchant    「纯商号」  = **主业**（currentPositionId）的 modeId == merchant
 *                   ⇒ §12.4 H-2 的退役后判据 + §13 I-A「主业 = 排序表第 1 项」：全职务商号
 * 「顺便跑商」     = 运力池成员 ∧ ¬纯商号（merchant 位置只在 participatingPositionIds 副业集合里）
 *                   ⇒ §12 H-F「顺便承担的跑商 ⇒ 运费独立计算」；它就是池条目上的 pureMerchant=false，
 *                     不另开判据（同一个事实的补集，两处拼写必然漂开）
 * </pre>
 *
 * <p>★★ <b>口径边界（如实记，见实现账本 §3 J-1）</b>：{@link #isPureMerchant} 取<b>主业</b>而不是"任一有效位置"。
 * 理由是它与冻结的利润算式必须自洽：纯商号的算式<b>没有"运费收入"腿</b>（设计书 §12.3、计划 §2.4 M1）—— 只有当"纯商号承运 ⇒
 * 免运费、不收运费"（H-A/H-B）时，这条算式才与结算逐值一致；若把"副业跑商"也算纯商号， 则运力池里的提供者会**全部**成为纯商号 ⇒ H-F
 * 的"顺便跑商"这一段永远不可达、而它的运费收入又落在算式之外。
 *
 * <p>★ <b>纯查询、无状态、无随机（I7）</b>：只读既有的 {@code HouseholdClassMembership} 与 {@code classPositions}
 * 两张表；{@code null} 一律判否（缺数据 ≠ 是跑商，方向 fail-closed）。
 */
final class MerchantIdentity {

  private MerchantIdentity() {}

  /**
   * ★★ <b>该家户"选了跑商"吗（J-A：运力池成员判据）</b>：{@code effectivePositionIds()} 里任一位置的 {@code modeId ==
   * merchant}。
   *
   * @param standing 家户的阶层归属（主业 ∪ 副业）；{@code null} ⇒ false
   * @param positions 位置表（{@code EconomyData.classPositions()}）；{@code null} ⇒ false
   */
  public static boolean selectsMerchant(
      HouseholdClassMembership standing, Map<ClassPositionId, ProductionRole> positions) {
    if (standing == null || positions == null) {
      return false;
    }
    for (ClassPositionId positionId : standing.effectivePositionIds()) {
      if (isMerchantPosition(positions.get(positionId))) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ <b>该家户是"纯商号"吗（H-2 退役商号行后的位置判据）</b>：<b>主业</b>（{@code currentPositionId}）的位置 {@code modeId ==
   * merchant}。
   *
   * <p>★ 段位口径：纯商号 = 全职跑商（§13 I-A/I-D「第一项是跑商 ⇒ 主业为商户」）。
   */
  public static boolean isPureMerchant(
      HouseholdClassMembership standing, Map<ClassPositionId, ProductionRole> positions) {
    if (standing == null || positions == null) {
      return false;
    }
    return isMerchantPosition(positions.get(standing.currentPositionId()));
  }

  /** 一个位置是不是 merchant 生产方式下的位置（{@code merchant.*}；本类唯一的 mode 比较点）。 */
  private static boolean isMerchantPosition(ProductionRole position) {
    return position != null && DefaultProductionModes.MERCHANT.equals(position.modeId());
  }

  /**
   * ★★ <b>本轮"纯商号"家户集合（H-2）</b>—— 免运费判据（H-A/H-G：自运自货）与利润读数分流的**范围**。
   *
   * <p>★ 保序不可变、按家户 id 升序（I7：内容的纯函数）；{@code standings}/{@code positions} 为 {@code null} ⇒ 空集（⇒ 谁都不豁免
   * ⇒ 逐值退回 M-A2）。
   */
  static Set<HouseholdId> pureMerchants(
      Map<HouseholdId, HouseholdClassMembership> standings,
      Map<ClassPositionId, ProductionRole> positions) {
    Set<HouseholdId> out = new LinkedHashSet<>();
    if (standings == null || positions == null) {
      return Collections.unmodifiableSet(out);
    }
    List<HouseholdId> households = new ArrayList<>(standings.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      if (isPureMerchant(standings.get(household), positions)) {
        out.add(household);
      }
    }
    return Collections.unmodifiableSet(out);
  }

  // ★★★ A3（2026-10-10）：此处原有 {@code merchants(standings, positions)} —— §16.4 ①（提交 b22da5b7）
  //   为"给跑商家户下夹一趟工具"这一段挂单保留而加的**范围化判据**。该保留已撤回（{@link MarketSettlement}
  //   的 necessaryInputsOf 尾部有逐条理由），本方法随之删除 ⇒ 本类只剩 {@link #selectsMerchant} 这一个判据拼写点
  //   与 {@link #pureMerchants} 这一个范围查询。
}
