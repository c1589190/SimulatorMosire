package io.mosire.simos.economy.spi;

import static io.mosire.simos.economy.spi.EconomyGmAdjustments.ADJUSTMENTS;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.COMMAND;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.DERIVED_REJECTION;
import static io.mosire.simos.economy.spi.EconomyGmAdjustments.Projection;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** GM 调整拆分 helper（由 EconomyGmAdjustments 抽取；行为不变）。 */
final class GmAdjustmentSupport {

  private GmAdjustmentSupport() {}

  // ★★ B2b：`setOutputQuantity` / `clearOutputQuantity` 两个投影方法已**整体删除** ——
  //   它们写的 `outputQuantityOverrides` 组件已从 EconomyData 删除。命中这两个 kind 时 `project(...)`
  //   在 switch 里直接给具名拒（见 OUTPUT_QUANTITY_MOVED_REASON）。
  //   ★ B2c（裁定八）：`upsertCandidate` 投影方法**同批删除**（候选面退役）—— 命中该 kind 时 `project(...)`
  //     在 switch 里直接给具名拒（见 PLAN_DEFINITION_MOVED_REASON）。

  // ── Z1：产品产出数量覆盖 ─────────────────────────────────────────────────────────────
  //   ★★ B2b：整段（setOutputQuantity / clearOutputQuantity 的投影 + 它们的解析/拷贝助手）已删除 ——
  //   `outputQuantityOverrides` 组件已从 economy 移除，唯一权威在 production（见 OUTPUT_QUANTITY_MOVED_REASON）。

  // ── P7 形状/解析助手 ──────────────────────────────────────────────────────────────────────

  /** 幂等 no-op 的 Projection：投影就是 base、changeSet Unchanged、不报任何 Change。 */
  static Projection noOp(String adjustment, String reason, long day, EconomyData base) {
    return new Projection(
        adjustment, reason, day, base, EconomyChangeSet.between(base, base), List.of());
  }

  // ★ B12 / DEF-T6-4 #2（UPM_UNCALLED_PRIVATE_METHOD）：`optionalVersion(...)` 已删 —— 全仓零调用点
  //   （B5 删掉两个 mode 版本推进 kind 之后它就是纯死代码）；`VERSION_ABSENT` 哨兵随之删除，
  //   下面的 B5 记录（"连同 `VERSION_ABSENT` / 全部 mode 版本推进判据"）至此逐字成立。
  //   删除不改任何入参校验：它只是把 `EconomyCommandPayloads.optionalInt` 再包一层，没有调用方就没有语义。

  /** 缺失即具名拒绝的既有字段取值（更新分支的 fallback）。 */
  static <T> T requireExistingField(String label, String field, T fallback) {
    if (fallback == null) {
      throw new IllegalArgumentException(label + " 新建对象缺少必填字段: " + field + "（既有对象更新时可省略）");
    }
    return fallback;
  }

  /** 可选枚举：给了 ⇒ 词表解析；没给 ⇒ fallback（null 则具名拒绝）。 */
  static <E extends Enum<E>> E optionalEnumOrExisting(
      String label, JsonNode parameters, String field, E fallback, Class<E> type) {
    if (hasValue(parameters, field)) {
      return enumValue(
          label, field, EconomyCommandPayloads.requireText(label, parameters, field), type);
    }
    return requireExistingField(label, field, fallback);
  }

  /** 可选布尔：给了 ⇒ 必须是 JSON 布尔；没给 ⇒ fallback（null 则具名拒绝）。 */
  static boolean booleanField(String label, JsonNode parameters, String field, Boolean fallback) {
    if (hasValue(parameters, field)) {
      JsonNode node = parameters.get(field);
      if (!node.isBoolean()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 必须是布尔值: " + node);
      }
      return node.asBoolean();
    }
    if (fallback == null) {
      throw new IllegalArgumentException(label + " 新建对象缺少必填字段: " + field);
    }
    return fallback;
  }

  static boolean hasValue(JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    return node != null && !node.isNull();
  }

  static IllegalArgumentException derivedRejection(String adjustment) {
    return new IllegalArgumentException(
        DERIVED_REJECTION
            + ": "
            + adjustment
            + "（"
            + COMMAND
            + " 只允许源状态调整："
            + String.join(" | ", ADJUSTMENTS)
            + "）");
  }

  static DebtContractId parseDebtId(String label, String text) {
    try {
      return DebtContractId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 debtContractId 非法: " + e.getMessage());
    }
  }

  static AssetRuleId parseAssetRuleId(String label, String text) {
    try {
      return AssetRuleId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 assetRuleId 非法: " + e.getMessage());
    }
  }

  static int intInRange(String label, String field, long value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          label + " 的 " + field + " 必须 ∈ [" + min + ", " + max + "]: " + value);
    }
    return (int) value;
  }

  static long nonNegative(String label, String field, long value) {
    if (value < 0L) {
      throw new IllegalArgumentException(label + " 的 " + field + " 不得为负: " + value);
    }
    return value;
  }

  /** {@code AssetKind} 词表解析的唯一入口（旧 {@code TOOL} ⇒ {@code asset-kind-retired:TOOL}）。 */
  static io.mosire.simos.actor.api.asset.AssetKind assetKind(
      String label, String field, String text) {
    try {
      return io.mosire.simos.actor.api.asset.AssetKind.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label + " 的 " + field + " 不是生产资料种类: " + text + "；" + e.getMessage(), e);
    }
  }

  static <E extends Enum<E>> E enumValue(String label, String field, String text, Class<E> type) {
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

  /** 可选 int：给了 ⇒ 整数且 ∈ int；没给 ⇒ fallback（null 则具名拒绝）。 */
  static int intField(String label, JsonNode parameters, String field, Integer fallback) {
    if (hasValue(parameters, field)) {
      long value = EconomyCommandPayloads.requireLong(label, parameters, field);
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(label + " 的 " + field + " 超出 int 范围: " + value);
      }
      return (int) value;
    }
    if (fallback == null) {
      throw new IllegalArgumentException(label + " 新建对象缺少必填字段: " + field);
    }
    return fallback;
  }

  static JsonNode requireObjectNode(String label, JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(label + " 的 " + field + " 必须是对象: " + node);
    }
    return node;
  }

  static List<HouseholdId> parseHouseholdList(
      EconomyData base, String label, JsonNode parameters, String field) {
    JsonNode node = requireArrayNode(label, parameters, field);
    List<HouseholdId> out = new ArrayList<>();
    Set<HouseholdId> seen = new LinkedHashSet<>();
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 每项必须是非空文本: " + element);
      }
      HouseholdId household = householdId(label, element.asText());
      if (!seen.add(household)) {
        throw new IllegalArgumentException(label + " 的 " + field + " 含重复家户: " + household.value());
      }
      if (!base.classes().isEmpty() && !base.classes().containsKey(household)) {
        throw new IllegalArgumentException(
            label + " 的 " + field + " 指名的家户不存在: " + household.value());
      }
      out.add(household);
    }
    return List.copyOf(out);
  }

  static List<AssetShareId> parseOwnershipStakeList(
      EconomyData base, String label, JsonNode parameters, String field, ActorRef organizer) {
    JsonNode node = requireArrayNode(label, parameters, field);
    List<AssetShareId> out = new ArrayList<>();
    Set<AssetShareId> seen = new LinkedHashSet<>();
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 每项必须是非空文本: " + element);
      }
      AssetShareId shareId = assetShareId(label, element.asText());
      if (!seen.add(shareId)) {
        throw new IllegalArgumentException(label + " 的 " + field + " 含重复份额: " + shareId.value());
      }
      if (!base.assetShares().isEmpty()) {
        OwnershipStake share = base.assetShares().get(shareId);
        if (share == null) {
          throw new IllegalArgumentException(
              label + " 的 " + field + " 指名的资产份额不存在: " + shareId.value());
        }
        if (!share.operator().equals(organizer)) {
          throw new IllegalArgumentException(
              label
                  + " 的 "
                  + field
                  + " 必须由 organizer 经营：份额="
                  + shareId.value()
                  + "，份额 operator="
                  + share.operator()
                  + "，organizer="
                  + organizer);
        }
      }
      out.add(shareId);
    }
    return List.copyOf(out);
  }

  static List<Payee> parsePayeeList(String label, JsonNode parameters, String field) {
    JsonNode node = requireArrayNode(label, parameters, field);
    List<Payee> out = new ArrayList<>();
    for (JsonNode element : node) {
      if (!element.isObject()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 每项必须是对象: " + element);
      }
      out.add(EconomyPayloads.recipient(element, field));
    }
    return List.copyOf(out);
  }

  static JsonNode requireArrayNode(String label, JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException(label + " 的 " + field + " 必须是数组: " + node);
    }
    return node;
  }

  static ProductionModeId productionModeId(String label, String text) {
    try {
      return ProductionModeId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 modeId 非法: " + e.getMessage());
    }
  }

  static ClassStructureId classStructureId(String label, String text) {
    try {
      return ClassStructureId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 classStructureId 非法: " + e.getMessage());
    }
  }

  static ClassPositionId classPositionId(String label, String text) {
    try {
      return ClassPositionId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 classPositionId 非法: " + e.getMessage());
    }
  }

  static ProductionOrganizationId productionEnterpriseId(String label, String text) {
    try {
      return ProductionOrganizationId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 organizationId 非法: " + e.getMessage());
    }
  }

  static ProductionUnitId productionProcessId(String label, String text) {
    try {
      return ProductionUnitId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 activity/unitId 非法: " + e.getMessage());
    }
  }

  static HouseholdId householdId(String label, String text) {
    try {
      return HouseholdId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 householdId 非法: " + e.getMessage());
    }
  }

  static AssetShareId assetShareId(String label, String text) {
    try {
      return AssetShareId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 assetShareId 非法: " + e.getMessage());
    }
  }
}
