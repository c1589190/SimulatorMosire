package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.time.DebtContractBook;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@code economy.GmAdjust} 的纯函数项目</b>（E6b）：handler（{@code simos-economy}）与 GM 窄写工具 {@code
 * simos.economy.adjust}（{@code simos-app}）<b>共用同一份</b>调整语义 —— 载荷解析、白名单拒绝、前后差异与 {@link
 * EconomyChangeSet} 都在这里算一次，两处只做各自的边界折叠（handler → {@code Rejected}/`Applied`；工具 → {@code
 * BAD_REQUEST} /预览视图）。
 *
 * <p>★★ <b>只改源状态、只走既有写口</b>：本类不碰任何表，只构造副本后调用两个既有写口 ——
 *
 * <ul>
 *   <li>{@code forgiveDebt}：{@link DebtContractBook#forgive}（E4c 的公开写口）+ {@link
 *       EconomyData#withDebtContracts}。只减/清本金；<b>不搬任何粮/钱</b>（库存仍只经 {@code applyTransfer} 换手）；
 *   <li>{@code setLiquidationPolicy}：{@link EconomyData#withLiquidationPolicies}（E5a 的写口）做 upsert。
 * </ul>
 *
 * <p>★★ <b>派生读数不可直写</b>：{@code adjustment} 只承认 {@link #FORGIVE_DEBT} / {@link
 * #SET_LIQUIDATION_POLICY} 两项；其余（包括 {@code flows} / {@code demandBook} / {@code crisisSignals} /
 * {@code classStandings.consecutiveDebtStressCycles} / {@code debtCapacity} 这类派生读数）一律以 {@link
 * #DERIVED_REJECTION} 具名拒绝 —— 派生读数只能由结算从源状态现算，不能从这里写进去。
 *
 * <p>★ <b>确定性</b>：同一 {@code (base, adjustment, parameters, reason, day)} ⇒ 逐字段相同的 {@link
 * Projection}。复制既有表用 {@link LinkedHashMap}（保序），id 用各稳定 ID 的 {@code parse}（唯一拼写点），不做任何 与迭代序 / 时钟 /
 * 随机数有关的事。
 *
 * <p>★ <b>reason 的落点</b>：本类把 {@code reason} 原样带进 {@link Projection} 与 {@code forgive} 写口（E4c 的
 * {@code Forgiveness} 返回里含原因）；命令载荷本身也带 {@code reason}（见 handler 与工具）。本阶段不新增持久审计组件。
 */
public final class EconomyGmAdjustments {

  /** {@code adjustment} 白名单项：减免债务（部分/全额）。 */
  public static final String FORGIVE_DEBT = "forgiveDebt";

  /** {@code adjustment} 白名单项：按 {@code assetRuleId} upsert 清算政策。 */
  public static final String SET_LIQUIDATION_POLICY = "setLiquidationPolicy";

  /** 白名单外调整的统一拒绝短语（handler 折 {@code Rejected}、工具折 {@code BAD_REQUEST} 都用它）。 */
  public static final String DERIVED_REJECTION = "派生读数不可由 GM 调整工具直写";

  private static final String COMMAND = EconomyGmAdjustHandler.TYPE;

  private EconomyGmAdjustments() {}

  /**
   * 计算一次 GM 调整的项目（<b>纯函数，不写任何状态</b>）。
   *
   * @param base 当前 {@link EconomyData}（只读；不得为 null）
   * @param adjustment 调整名；白名单外一律 {@link IllegalArgumentException}（具名 {@link #DERIVED_REJECTION}）
   * @param parameters 调整参数对象（形状见 {@link #FORGIVE_DEBT}/{@link #SET_LIQUIDATION_POLICY}）
   * @param reason 调整原因；必填非空白
   * @param day 世界当前日（只进审计摘要；不改状态）
   * @return 投影后的 {@link EconomyData}、{@link EconomyChangeSet} 与前后差异清单
   * @throws IllegalArgumentException 参数缺失/类型不对/引用不存在/白名单外（消息可直接进 {@code Rejected} / {@code
   *     BAD_REQUEST}）
   */
  public static Projection project(
      EconomyData base, String adjustment, JsonNode parameters, String reason, long day) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(parameters, "parameters");
    if (adjustment == null || adjustment.isBlank()) {
      throw new IllegalArgumentException(COMMAND + " 缺少非空文本字段: adjustment");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException(COMMAND + " 的 reason 必填且非空白");
    }
    if (!parameters.isObject()) {
      throw new IllegalArgumentException(COMMAND + " 的字段 parameters 必须是 JSON 对象: " + parameters);
    }
    if (day < 0L) {
      throw new IllegalArgumentException(COMMAND + " 的 day 不得为负: " + day);
    }
    return switch (adjustment) {
      case FORGIVE_DEBT -> forgive(base, parameters, reason, day);
      case SET_LIQUIDATION_POLICY -> setLiquidationPolicy(base, parameters, reason, day);
      default -> throw derivedRejection(adjustment);
    };
  }

  /** {@code forgiveDebt}：{@code debtContractId} + 可选 {@code amount}（缺省 = 全额本金）。 */
  private static Projection forgive(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + FORGIVE_DEBT;
    DebtContractId id =
        parseDebtId(label, EconomyCommandPayloads.requireText(label, parameters, "debtContractId"));
    DebtContract existing = base.debtContracts().get(id);
    if (existing == null) {
      throw new IllegalArgumentException(label + " 债务合同不存在: " + id.value());
    }
    if (existing.principal() <= 0L) {
      throw new IllegalArgumentException(label + " 合同本金已为 0，无可减免: " + id.value());
    }
    long amount = existing.principal();
    JsonNode amountNode = parameters.get("amount");
    if (amountNode != null && !amountNode.isNull()) {
      amount = EconomyCommandPayloads.requireLong(label, parameters, "amount");
    }
    if (amount <= 0L) {
      throw new IllegalArgumentException(label + " 的 amount 必须 > 0: " + amount);
    }
    if (amount > existing.principal()) {
      throw new IllegalArgumentException(
          label + " 的 amount 超过本金（不可下溢）：amount=" + amount + "，本金=" + existing.principal());
    }
    Map<DebtContractId, DebtContract> contracts = new LinkedHashMap<>(base.debtContracts());
    // ★ 唯一写口：只减/清本金（部分减免保留原状态、全额 ⇒ FORGIVEN），不碰粮/钱库存。
    DebtContractBook.forgive(contracts, id, amount, reason);
    EconomyData projected = base.withDebtContracts(contracts);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        FORGIVE_DEBT,
        reason,
        day,
        projected,
        changeSet,
        List.of(
            new Change("debtContracts", id.value(), existing, projected.debtContracts().get(id))));
  }

  /** {@code setLiquidationPolicy}：按 {@code assetRuleId} upsert 一条清算政策（六个字段全必填）。 */
  private static Projection setLiquidationPolicy(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + SET_LIQUIDATION_POLICY;
    AssetRuleId ruleId =
        parseAssetRuleId(
            label, EconomyCommandPayloads.requireText(label, parameters, "assetRuleId"));
    if (!base.assetRules().containsKey(ruleId)) {
      throw new IllegalArgumentException(label + " 引用的 AssetRule 不存在: " + ruleId.value());
    }
    int maxLiquidatePerMille =
        intInRange(
            label,
            "maxLiquidatePerMille",
            EconomyCommandPayloads.requireLong(label, parameters, "maxLiquidatePerMille"),
            0,
            1000);
    long protectedReserve =
        nonNegative(
            label,
            "protectedReserve",
            EconomyCommandPayloads.requireLong(label, parameters, "protectedReserve"));
    LiquidationPolicy.PriceSource priceSource =
        enumValue(
            label,
            "priceSource",
            EconomyCommandPayloads.requireText(label, parameters, "priceSource"),
            LiquidationPolicy.PriceSource.class);
    long policyValuePerUnitMilli =
        nonNegative(
            label,
            "policyValuePerUnitMilli",
            EconomyCommandPayloads.requireLong(label, parameters, "policyValuePerUnitMilli"));
    if (priceSource != LiquidationPolicy.PriceSource.POLICY && policyValuePerUnitMilli != 0L) {
      throw new IllegalArgumentException(
          label
              + " 的 policyValuePerUnitMilli 只在 POLICY 档可为非 0：priceSource="
              + priceSource
              + "，值="
              + policyValuePerUnitMilli);
    }
    LiquidationPolicy.RecipientRule recipientRule =
        enumValue(
            label,
            "recipientRule",
            EconomyCommandPayloads.requireText(label, parameters, "recipientRule"),
            LiquidationPolicy.RecipientRule.class);

    LiquidationPolicy policy =
        new LiquidationPolicy(
            ruleId,
            maxLiquidatePerMille,
            protectedReserve,
            priceSource,
            policyValuePerUnitMilli,
            recipientRule);
    Map<AssetRuleId, LiquidationPolicy> policies = new LinkedHashMap<>(base.liquidationPolicies());
    LiquidationPolicy before = policies.put(ruleId, policy);
    EconomyData projected = base.withLiquidationPolicies(policies);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes =
        policy.equals(before)
            ? List.of() // 逐值相同的 upsert = 幂等 no-op（changeSet 也是 Unchanged），不报"变了"
            : List.of(new Change("liquidationPolicies", ruleId.value(), before, policy));
    return new Projection(SET_LIQUIDATION_POLICY, reason, day, projected, changeSet, changes);
  }

  private static IllegalArgumentException derivedRejection(String adjustment) {
    return new IllegalArgumentException(
        DERIVED_REJECTION
            + ": "
            + adjustment
            + "（"
            + COMMAND
            + " 只允许源状态调整："
            + FORGIVE_DEBT
            + " | "
            + SET_LIQUIDATION_POLICY
            + "）");
  }

  private static DebtContractId parseDebtId(String label, String text) {
    try {
      return DebtContractId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 debtContractId 非法: " + e.getMessage());
    }
  }

  private static AssetRuleId parseAssetRuleId(String label, String text) {
    try {
      return AssetRuleId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 assetRuleId 非法: " + e.getMessage());
    }
  }

  private static int intInRange(String label, String field, long value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          label + " 的 " + field + " 必须 ∈ [" + min + ", " + max + "]: " + value);
    }
    return (int) value;
  }

  private static long nonNegative(String label, String field, long value) {
    if (value < 0L) {
      throw new IllegalArgumentException(label + " 的 " + field + " 不得为负: " + value);
    }
    return value;
  }

  private static <E extends Enum<E>> E enumValue(
      String label, String field, String text, Class<E> type) {
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label
              + " 的 "
              + field
              + " 未知: "
              + text
              + "；合法值: "
              + Arrays.toString(type.getEnumConstants()));
    }
  }

  /**
   * 一次调整的项目：**投影后的状态 + 完整变更集 + 前后差异**。
   *
   * <p>★ {@code projected} 只服务预览/差异（工具<b>不得</b>把它写回任何地方）；落盘一律由 handler 把 {@link #changeSet} 交给
   * Core，走 {@code Command → ChangeSet → Revision}。
   *
   * @param adjustment 调整名（白名单项）
   * @param reason 调整原因（原样，必填非空白）
   * @param day 世界当前日
   * @param projected 投影后的完整 {@link EconomyData}
   * @param changeSet {@code between(base, projected)}（handler 的落盘载荷）
   * @param changes 逐键前后差异（工具预览渲染用；本次调整实际碰过的源状态键）
   */
  public record Projection(
      String adjustment,
      String reason,
      long day,
      EconomyData projected,
      EconomyChangeSet changeSet,
      List<Change> changes) {

    public Projection {
      Objects.requireNonNull(adjustment, "adjustment");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(projected, "projected");
      Objects.requireNonNull(changeSet, "changeSet");
      changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
  }

  /**
   * 一个稳定键的前后差异。
   *
   * @param component 源状态组件名（如 {@code debtContracts} / {@code liquidationPolicies}）
   * @param keyId 该组件内的稳定键（规范串）
   * @param before 调整前的值；新增时为 {@code null}
   * @param after 调整后的值
   */
  public record Change(String component, String keyId, Object before, Object after) {

    public Change {
      Objects.requireNonNull(component, "component");
      Objects.requireNonNull(keyId, "keyId");
    }
  }
}
