package io.mosire.simos.social.provisioning;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import java.util.Optional;

/**
 * ★★ <b>Social 需求/劳动系数表的 GM 编辑纯推导</b>（2026-10-09 家户结构修复计划 Batch 4）： {@code
 * social.SetDemandCoefficient} / {@code social.SetLaborCoefficient} 两条命令与 app 侧 {@code
 * simos.social.demand} / {@code simos.social.labor} 两条窄工具<b>共用这一份语义</b>—— 命令 handler
 * 与工具预览因此不可能各写一套"要不要推断全局口径 / 能不能清键"的分叉。
 *
 * <p>★★ <b>纯函数</b>：不写任何外部状态、不落 revision、不自己拼四张表；每一处只调用 {@link SocialProvisioning} 的不可变
 * copy-with（{@code withGlobalDemand} / {@code withHouseholdDemand} / {@code withoutHouseholdDemand}
 * / 同名 labor 版本），最后经 {@link SocialData#withProvisioning(SocialProvisioning)} 写回一份新的 {@link
 * SocialData}。所有不变量（null、负值、period/cycleDays 自洽、重复键）仍在 {@link DemandCoefficient} / {@link
 * LaborCoefficient} / {@link SocialProvisioning} 的构造期校验， 本类<b>不复制</b>那些校验。
 *
 * <p>★★ <b>命令语义（两条命令同制）</b>：
 *
 * <ul>
 *   <li>{@code householdId == null} = 改全局默认；给了 = 改该家户覆盖（家户必须存在）；
 *   <li>{@code amountMilli}/{@code milliHoursPerTick} 给了 = upsert 该键；
 *   <li>系数缺席 = 删除该家户覆盖键（只允许 householdId 在场；全局删键具名拒，避免破坏默认表完整性）；
 *   <li>需求系数 {@code period}/{@code cycleDays}：两者都缺席 ⇒ 从该商品的全局默认口径推断（ {@link
 *       SocialProvisioning#globalDemandBasis(CommodityId)} 找不到 ⇒ 具名拒）；两者都给 ⇒ 按值构造； 只给一个 ⇒
 *       具名拒。家户覆盖若显式给口径，必须与全局口径一致（全局没有该商品行时允许显式口径， 让"其它商品由 GM 显式写入行"的路径仍可用）；
 *   <li>删除家户覆盖键时该键必须存在（不存在 ⇒ 具名拒，不落一条假的成功 revision）。
 * </ul>
 *
 * <p>★★ <b>旧档作废、不迁移</b>（用户 2026-10-09 裁定）：本类只认第 6 组件 {@code provisioning} 已经存在的 新档；缺该组件的旧档由 {@link
 * SocialData} / {@code SocialChangeSet} 的构造期具名拒，不在这里做缺省补值 / 双读。
 *
 * <p>★ 本类别名 {@code 命令层}：它不认识命令信封、载荷 JSON、revision——那些是 {@code simos-social/spi} 与 {@code simos-app}
 * 的私事。
 */
public final class SocialProvisioningEdits {

  private SocialProvisioningEdits() {}

  /**
   * ★★ <b>upsert 一条需求系数</b>：{@code householdId == null} 改全局默认，否则改该家户覆盖。
   *
   * @param base 当前社会状态（第 6 组件必须是新档 provisioning）；不得为 null
   * @param householdId 家户 id；{@code null} = 全局默认
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   * @param amountMilli 每人每个时间口径的最小计量单位数；不得为负
   * @param period 时间口径；{@code null} = 与 {@code cycleDays} 一并从全局口径推断
   * @param cycleDays {@code PER_CYCLE_DAYS} 的周期天数；{@code null} = 与 {@code period} 一并从全局口径推断
   * @throws IllegalArgumentException 家户不存在 / period 与 cycleDays 只给一个 / 找不到该商品的全局口径 /
   *     家户覆盖显式口径与全局口径不一致 / 系数或表的不变量不成立
   */
  public static SocialData setDemand(
      SocialData base,
      HouseholdId householdId,
      AgeBracket ageBracket,
      Sex sex,
      CommodityId commodity,
      long amountMilli,
      DemandPeriod period,
      Long cycleDays) {
    requireBase(base);
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    requireArg(commodity, "commodity");
    requireHouseholdExists(base, householdId);
    if ((period == null) != (cycleDays == null)) {
      throw ProvisioningReject.reject(
          "period 与 cycleDays 必须同时给或同时省略: period=" + period + " cycleDays=" + cycleDays);
    }
    DemandBasis basis;
    if (period == null) {
      // 两者都缺席：按该商品的全局默认口径推断；找不到 ⇒ 具名拒（不静默给 0/不猜天数）。
      basis =
          base.provisioning()
              .globalDemandBasis(commodity)
              .orElseThrow(
                  () ->
                      ProvisioningReject.reject(
                          "找不到商品 "
                              + commodity
                              + " 的全局需求口径（period/cycleDays 也未显式给）；"
                              + "请显式给 period 与 cycleDays"));
    } else {
      basis = new DemandBasis(period, cycleDays); // 构造期校验周期自洽
      if (householdId != null) {
        // 家户覆盖必须与全局口径一致（全局没有该商品行时允许显式口径，见类注）。
        Optional<DemandBasis> globalBasis = base.provisioning().globalDemandBasis(commodity);
        if (globalBasis.isPresent() && !globalBasis.get().equals(basis)) {
          throw ProvisioningReject.reject(
              "家户需求覆盖的时间口径必须与全局一致: commodity="
                  + commodity
                  + " global="
                  + globalBasis.get()
                  + " override="
                  + basis);
        }
      }
    }
    DemandCoefficient coefficient =
        new DemandCoefficient(
            ageBracket, sex, commodity, amountMilli, basis.period(), basis.cycleDays());
    SocialProvisioning next =
        householdId == null
            ? base.provisioning().withGlobalDemand(coefficient)
            : base.provisioning().withHouseholdDemand(householdId, coefficient);
    return base.withProvisioning(next);
  }

  /**
   * ★★ <b>删除一条家户需求覆盖键</b>（删除后回落全局默认；该键的全局行可以不存在）。
   *
   * @param base 当前社会状态；不得为 null
   * @param householdId 家户 id；不得为 null（全局删键在命令语义里明确不允许）
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   * @throws IllegalArgumentException householdId 缺席（= 试图删全局键）/ 家户不存在 / 该户没有这条覆盖键
   */
  public static SocialData clearDemand(
      SocialData base,
      HouseholdId householdId,
      AgeBracket ageBracket,
      Sex sex,
      CommodityId commodity) {
    requireBase(base);
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    requireArg(commodity, "commodity");
    if (householdId == null) {
      throw ProvisioningReject.reject("全局需求默认表不允许删键（会破坏默认完整性）；请给 householdId 指定要清除覆盖的家户");
    }
    requireHouseholdExists(base, householdId);
    if (base.provisioning()
        .householdDemandOverride(householdId, ageBracket, sex, commodity)
        .isEmpty()) {
      throw ProvisioningReject.reject(
          "家户 "
              + householdId
              + " 没有这条需求覆盖键，无需清除: ageBracket="
              + ageBracket.key()
              + " sex="
              + sex
              + " commodity="
              + commodity);
    }
    return base.withProvisioning(
        base.provisioning().withoutHouseholdDemand(householdId, ageBracket, sex, commodity));
  }

  /**
   * ★★ <b>upsert 一条劳动系数</b>：{@code householdId == null} 改全局默认，否则改该家户覆盖。
   *
   * @param base 当前社会状态；不得为 null
   * @param householdId 家户 id；{@code null} = 全局默认
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param milliHoursPerTick 每人每 tick 的毫小时预算；不得为负
   * @throws IllegalArgumentException 家户不存在 / 系数或表的不变量不成立
   */
  public static SocialData setLabor(
      SocialData base,
      HouseholdId householdId,
      AgeBracket ageBracket,
      Sex sex,
      long milliHoursPerTick) {
    requireBase(base);
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    requireHouseholdExists(base, householdId);
    LaborCoefficient coefficient = new LaborCoefficient(ageBracket, sex, milliHoursPerTick);
    SocialProvisioning next =
        householdId == null
            ? base.provisioning().withGlobalLabor(coefficient)
            : base.provisioning().withHouseholdLabor(householdId, coefficient);
    return base.withProvisioning(next);
  }

  /**
   * ★★ <b>删除一条家户劳动覆盖键</b>（删除后回落全局默认）。
   *
   * @param base 当前社会状态；不得为 null
   * @param householdId 家户 id；不得为 null（全局删键明确不允许）
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @throws IllegalArgumentException householdId 缺席（= 试图删全局键）/ 家户不存在 / 该户没有这条覆盖键
   */
  public static SocialData clearLabor(
      SocialData base, HouseholdId householdId, AgeBracket ageBracket, Sex sex) {
    requireBase(base);
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    if (householdId == null) {
      throw ProvisioningReject.reject("全局劳动默认表不允许删键（会破坏默认完整性）；请给 householdId 指定要清除覆盖的家户");
    }
    requireHouseholdExists(base, householdId);
    if (base.provisioning().householdLaborOverride(householdId, ageBracket, sex).isEmpty()) {
      throw ProvisioningReject.reject(
          "家户 " + householdId + " 没有这条劳动覆盖键，无需清除: ageBracket=" + ageBracket.key() + " sex=" + sex);
    }
    return base.withProvisioning(
        base.provisioning().withoutHouseholdLabor(householdId, ageBracket, sex));
  }

  /** base 不得为 null（具名拒，供 handler 折 Rejected / 工具折 BAD_REQUEST）。 */
  private static void requireBase(SocialData base) {
    if (base == null) {
      throw ProvisioningReject.reject("SocialProvisioningEdits.base 不得为 null");
    }
  }

  /** householdId 非 null 时必须存在；null = 全局默认，是合法形态。 */
  private static void requireHouseholdExists(SocialData base, HouseholdId householdId) {
    if (householdId != null && !base.households().containsKey(householdId)) {
      throw ProvisioningReject.reject("家户不存在: " + householdId);
    }
  }

  /** 引用参数非 null 校验：坏参数据名拒（与 SocialProvisioning 同制）。 */
  private static void requireArg(Object value, String field) {
    if (value == null) {
      throw ProvisioningReject.reject("SocialProvisioningEdits." + field + " 不得为 null");
    }
  }
}
