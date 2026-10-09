package io.mosire.simos.social.provisioning;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
   * ★★ <b>一批需求编辑的条数上限</b>（条）：超限 ⇒ 具名拒（fail-closed）——批载荷是"一次改多条"的便捷面，不是无界导入口； 1024 条足以覆盖"6 档 × 6 商品
   * × 多户"的调参场景，又不给一条命令留下无界的载荷面。
   */
  public static final int MAX_BATCH_EDITS = 1_024;

  /**
   * ★★ <b>一条需求编辑</b>（D 批 2026-10-09 的 GM 批量加减需求）：与单条命令载荷**同形**——{@code amountMilli} 给了 = upsert、 缺席
   * = 删除该家户覆盖键；{@code householdId} 缺席 = 改全局默认。
   *
   * <p>它只是"载荷 → 纯推导"的中间值：不带 JSON、不带命令信封、不认识 revision（那些是 {@code spi} 与 {@code app} 的私事）。
   * 三个引用字段（年龄档/性别/商品）在构造期非 null 校验；{@code householdId} / {@code amountMilli} / {@code period} /
   * {@code cycleDays} 的缺席各有独立语义（见 {@link #editDemands}）。
   *
   * @param householdId 家户 id；{@code null} = 全局默认
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   * @param amountMilli 每人每个时间口径的最小计量单位数；{@code null} = 删除该家户覆盖键
   * @param period 时间口径；{@code null} = 与 {@code cycleDays} 一并从全局口径推断
   * @param cycleDays {@code PER_CYCLE_DAYS} 的周期天数；{@code null} = 与 {@code period} 一并推断
   */
  public record DemandEdit(
      HouseholdId householdId,
      AgeBracket ageBracket,
      Sex sex,
      CommodityId commodity,
      Long amountMilli,
      DemandPeriod period,
      Long cycleDays) {

    public DemandEdit {
      requireArg(ageBracket, "DemandEdit.ageBracket");
      requireArg(sex, "DemandEdit.sex");
      requireArg(commodity, "DemandEdit.commodity");
    }
  }

  /**
   * ★★ <b>一次应用一批需求编辑（全有或全无）</b>——D 批的 GM 批量加减需求：批内任一条不合法 ⇒ 整批具名拒， 调用方拿不到任何新状态（⇒ 零 revision、head
   * 不动）；全部合法 ⇒ 返回**一份**新 {@link SocialData}， 由命令层落成**一条** {@code SocialChangeSet}（一条命令 = 一条
   * revision）。
   *
   * <p>★★ <b>原子性从哪来</b>：本方法是纯函数，逐条顺序调用 {@link #setDemand} / {@link #clearDemand}（同一条
   * 语义、同一份不变量校验），只把结果串在当前值上；任何一条抛 ⇒ 整个方法抛，已算出的中间 {@link SocialData} 全部作废 （它是不可变值，没有"写了一半"的形态）。
   *
   * <p>★★ <b>批内不变量</b>：
   *
   * <ul>
   *   <li>至少 1 条（空批 ⇒ 具名拒：不落一条什么都不改的假成功 revision）；
   *   <li>至多 {@link #MAX_BATCH_EDITS} 条；
   *   <li><b>目标键不得重复</b>：同一个 {@code (家户, 年龄档, 性别, 商品)} 在批内出现两次 ⇒ 具名拒（否则结果取决于
   *       载荷内部次序，"后写的悄悄赢"是本仓最反对的静默形态）；
   *   <li>{@code amountMilli} 缺席（= 删除）时不得给 {@code period}/{@code cycleDays}（与单条命令同一条拒绝口径）。
   * </ul>
   *
   * <p>★ 拒绝消息一律带**第几条**与目标键，让 GM 一眼看出是哪一行错——整批被拒时那是唯一的线索。
   *
   * @param base 当前社会状态（第 6 组件必须是新档 provisioning）；不得为 null
   * @param edits 有序编辑批；不得为 null、不得为空、不得超过 {@link #MAX_BATCH_EDITS}
   * @throws IllegalArgumentException 见上（任一条不合法都整批拒）
   */
  public static SocialData editDemands(SocialData base, List<DemandEdit> edits) {
    requireBase(base);
    requireArg(edits, "SocialProvisioningEdits.edits");
    if (edits.isEmpty()) {
      throw ProvisioningReject.reject("批量需求编辑至少要有 1 条（空批不落 revision）");
    }
    if (edits.size() > MAX_BATCH_EDITS) {
      throw ProvisioningReject.reject(
          "批量需求编辑最多 " + MAX_BATCH_EDITS + " 条（本批 " + edits.size() + " 条）");
    }
    Set<BatchKey> seen = new LinkedHashSet<>();
    SocialData current = base;
    for (int index = 0; index < edits.size(); index++) {
      DemandEdit edit = edits.get(index);
      if (edit == null) {
        throw ProvisioningReject.reject("批量需求编辑第 " + index + " 条不得为 null");
      }
      BatchKey key =
          new BatchKey(edit.householdId(), edit.ageBracket(), edit.sex(), edit.commodity());
      if (!seen.add(key)) {
        throw ProvisioningReject.reject(
            "批量需求编辑第 " + index + " 条与前面的条目目标键重复（同一个 (家户, 年龄档, 性别, 商品) 在批内只允许一次）: " + key.text());
      }
      if (edit.amountMilli() == null && (edit.period() != null || edit.cycleDays() != null)) {
        throw ProvisioningReject.reject(
            "批量需求编辑第 " + index + " 条：删除家户覆盖键时不接受 period/cycleDays（没有系数可构造）: " + key.text());
      }
      try {
        current =
            edit.amountMilli() == null
                ? clearDemand(
                    current, edit.householdId(), edit.ageBracket(), edit.sex(), edit.commodity())
                : setDemand(
                    current,
                    edit.householdId(),
                    edit.ageBracket(),
                    edit.sex(),
                    edit.commodity(),
                    edit.amountMilli(),
                    edit.period(),
                    edit.cycleDays());
      } catch (IllegalArgumentException e) {
        // ★ 具名拒 + 指明第几条（整批作废）：内层原因原样带上，不吞、不改写。
        throw ProvisioningReject.reject(
            "批量需求编辑第 " + index + " 条不合法（整批拒绝，零 revision）: " + key.text() + " ⇒ " + e.getMessage());
      }
    }
    return current;
  }

  /** 批内目标键 {@code (家户, 年龄档, 性别, 商品)}：{@code householdId == null} 表示全局默认。 */
  private record BatchKey(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex, CommodityId commodity) {

    String text() {
      return "household="
          + (householdId == null ? "global" : householdId.value())
          + " ageBracket="
          + ageBracket.key()
          + " sex="
          + sex
          + " commodity="
          + commodity.value();
    }
  }

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
