package io.mosire.simos.economy.model;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.Recipient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>生产组织</b>（理想架构 §2.4；E2）：生产方式 + 阶层结构 + 可支配劳动 + 可用 {@link AssetShare} 之间的桥 ——
 * 它回答"本期应当存在哪条生产活动、由谁组织、用哪些劳动/资产、产出先归谁、是否短缺"。
 *
 * <pre>
 * ProductionOrganization(id, modeId, classPositionId,
 *                       unitId,              // 对应的 ProductionUnit（SHORTAGE 早期可以为空：还没建出可生产的 unit）
 *                       organizer,           // ActorRef：谁组织/经营（经济主体，不等于阶层本身、也不等于地主）
 *                       laborSources,        // 参与劳动的家户（稳定身份；批次维度在各 LaborAllocation 里）
 *                       assetSources,        // 实际使用的 AssetShare（份额身份，不复制数量）
 *                       inputSources,        // 谁出种子/原料/工具（来自 relation.inputSupplier）
 *                       outputOwnership,     // 产出先归谁（来自 relation.residualOwner）
 *                       relationTemplateRef, // 规则模板的具名来源（mode / existing unit）
 *                       status, statusReason)
 * </pre>
 *
 * <p>★★ <b>状态四档与"不得静默"</b>：{@link Status#ACTIVE}（有 unit、劳动/资产/投入三路都过）、{@link
 * Status#SHORTAGE}（缺资产/投入/劳动/产业模板等，{@code statusReason} 必须具名）、{@link Status#SUSPENDED} （E3+
 * 的状态机停业）、{@link Status#EXITING}（E6 模式变迁的退出处置）。★ <b>本记录不把"没组织"当成合法行为</b>： 应当生产的阶层若组织不起来，必须落一条
 * SHORTAGE 并带具名原因（{@code statusReason}），不许静默跳过。
 *
 * <p>★ <b>身份与视图分离</b>：{@code id} 是稳定的组织身份（含 mode/position/household/地点，见 {@code
 * ProductionOrganizationId}）；{@code unitId} 是本组织<b>当前</b>指向的生产单元 —— 模式变迁时旧组织可以先转 EXITING、 新组织指向新
 * unit，而不改历史组织的身份。
 *
 * <p>★★ <b>不可变与构造期 fail-closed</b>：
 *
 * <ol>
 *   <li>{@code id}/{@code modeId}/{@code classPositionId}/{@code organizer}/{@code outputOwnership}
 *       非 null；三个集合与两个 {@code Optional} 组件不得为 null（空用 {@code Optional.empty()}）；
 *   <li>{@code Status.ACTIVE} 与 {@code Status.EXITING} ⇒ {@code unitId} 必须有值（没有 unit 的"在产/退出"说不通）；
 *   <li>{@code Status.SHORTAGE} ⇒ {@code statusReason} 不得为空白（缺口必须有名字）；
 *   <li>三个集合逐项非 null、去重（保序）、冻结在赋值处；{@code relationTemplateRef} 给了就必须非空白。
 * </ol>
 *
 * @param id 组织稳定身份；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param modeId 所属生产方式；不得为 null
 * @param classPositionId 本组织对应的阶层位置；不得为 null
 * @param unitId 对应的生产单元；{@code ACTIVE/EXITING} 必须有值
 * @param organizer 组织者（组织/经营这条生产活动的经济主体）；不得为 null
 * @param laborSources 参与劳动的家户（保序、去重、不可变；可空表 = 尚未登记）
 * @param assetSources 使用的资产份额（保序、去重、不可变；可空表 = 尚未使用任何份额）
 * @param inputSources 投入的提供者（来自 relation.inputSupplier；可空表 = 尚未生成关系）
 * @param outputOwnership 产出先归谁；不得为 null
 * @param relationTemplateRef 规则模板来源（空 = 尚未生成关系）；给了必须非空白
 * @param status 组织状态；不得为 null
 * @param statusReason 状态原因（SHORTAGE 必须具名；其余可空串）
 */
// ★ 豁免 EI_EXPOSE_REP（R4a，3 条）：三张表由 freezeDistinct 逐项非空校验 + 保序去重 + unmodifiableList；
//   SpotBugs 看不穿该私有 helper 的返回值，和 ArmyPlan 的 copyCounts 同款。
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "三张来源表由 freezeDistinct 复制并 unmodifiableList；SpotBugs 看不穿该私有 helper 的返回值")
public record ProductionOrganization(
    ProductionOrganizationId id,
    ProductionModeId modeId,
    ClassPositionId classPositionId,
    Optional<ProductionUnitId> unitId,
    ActorRef organizer,
    List<HouseholdId> laborSources,
    List<AssetShareId> assetSources,
    List<Recipient> inputSources,
    Recipient outputOwnership,
    Optional<String> relationTemplateRef,
    Status status,
    String statusReason) {

  /** ★ 生产组织状态四档（设计稿 §2.4；E2 只生产 ACTIVE/SHORTAGE，SUSPENDED/EXITING 由 E3/E6 写）。 */
  public enum Status {
    /** 在产：有 unit，劳动/资产/投入三路约束都已满足。 */
    ACTIVE,
    /** 短缺：组织不起来，{@code statusReason} 具名（缺资产/投入/劳动/产业模板/许可…）。 */
    SHORTAGE,
    /** 停业（E3+ 的经营者状态机；本阶段不写）。 */
    SUSPENDED,
    /** 退出中（E6 模式变迁；旧 unit 退出处置期间）。 */
    EXITING
  }

  public ProductionOrganization {
    Objects.requireNonNull(id, "ProductionOrganization.id 不得为 null");
    Objects.requireNonNull(modeId, "ProductionOrganization.modeId 不得为 null");
    Objects.requireNonNull(classPositionId, "ProductionOrganization.classPositionId 不得为 null");
    Objects.requireNonNull(
        unitId, "ProductionOrganization.unitId 不得为 null（没有 unit 请用 Optional.empty()）");
    Objects.requireNonNull(organizer, "ProductionOrganization.organizer 不得为 null");
    Objects.requireNonNull(outputOwnership, "ProductionOrganization.outputOwnership 不得为 null");
    Objects.requireNonNull(
        relationTemplateRef,
        "ProductionOrganization.relationTemplateRef 不得为 null（没有请用 Optional.empty()）");
    Objects.requireNonNull(status, "ProductionOrganization.status 不得为 null");
    Objects.requireNonNull(statusReason, "ProductionOrganization.statusReason 不得为 null（没有就给空串）");
    if (laborSources == null) {
      throw new IllegalArgumentException("ProductionOrganization.laborSources 不得为 null（没有给空表）");
    }
    if (assetSources == null) {
      throw new IllegalArgumentException("ProductionOrganization.assetSources 不得为 null（没有给空表）");
    }
    if (inputSources == null) {
      throw new IllegalArgumentException("ProductionOrganization.inputSources 不得为 null（没有给空表）");
    }
    if (!statusReason.isBlank() && (status == Status.ACTIVE)) {
      throw new IllegalArgumentException(
          "ProductionOrganization.ACTIVE 不携带缺口原因（要写原因请用 SHORTAGE）: " + statusReason);
    }
    if (status == Status.SHORTAGE && statusReason.isBlank()) {
      throw new IllegalArgumentException("ProductionOrganization.SHORTAGE 必须带具名原因（不许静默短缺）");
    }
    if ((status == Status.ACTIVE || status == Status.EXITING) && unitId.isEmpty()) {
      throw new IllegalArgumentException(
          "ProductionOrganization." + status + " 必须有 unitId（没有 unit 的在产/退出说不通）");
    }
    if (relationTemplateRef.isPresent() && relationTemplateRef.get().isBlank()) {
      throw new IllegalArgumentException("ProductionOrganization.relationTemplateRef 给了就必须非空白");
    }
    laborSources = freezeDistinct(laborSources, "laborSources");
    assetSources = freezeDistinct(assetSources, "assetSources");
    inputSources = freezeDistinct(inputSources, "inputSources");
  }

  /** 保序去重 + 逐项非 null + 冻结（三张表的同一条口径；绝不用 {@code Set.copyOf} —— 迭代序不是内容的纯函数）。 */
  private static <T> List<T> freezeDistinct(List<T> values, String field) {
    Set<T> seen = new LinkedHashSet<>();
    for (T value : values) {
      if (value == null) {
        throw new IllegalArgumentException("ProductionOrganization." + field + " 不得含 null");
      }
      seen.add(value);
    }
    return Collections.unmodifiableList(new ArrayList<>(seen)); // ★ 冻在赋值处
  }
}
