package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code economy.AddDemand}（R4-E2）：GM 注入一条需求（家户范围或格范围）。
 *
 * <pre>{@code
 * {"id":"demand-wool-1"?, "scope":"HEX", "hex":{"q":-20,"r":-81},
 *  "commodity":"wool", "kind":"RECURRING", "unit":"PER_CAPITA",
 *  "quantityPerCycle":52, "createdDay":120?, "expiresDay":-1?, "priority":0?, "source":"gm"?}
 * }</pre>
 *
 * <p>★★ <b>本命令的校验（逐条对应 E2a 任务书）</b>：
 *
 * <ul>
 *   <li>{@code price} 存在性：HOUSEHOLD 范围用**其居住格**的价表；HEX 范围用该格价表。缺市场/缺该商品价 ⇒ {@link
 *       HandlerOutcome.Rejected}，消息**指名 {@code economy.SetMarketPrice}**；
 *   <li>{@code household} 必须存在（HOUSEHOLD 范围）；HEX 范围的 {@code hex} 以"该格有市场行"为存在证据；
 *   <li>scope ↔ household/hex 的互斥与必填（{@link DemandEntry} 构造期再判一遍）；{@code quantityPerCycle > 0}、
 *       {@code createdDay ≥ 0}、{@code priority ≥ 0}；
 *   <li>{@code id} 缺省 ⇒ 按 {@code demand-<scope>-<主体>-<商品>-<kind>-<unit>-<序号>} 确定性生成（序号 = 同前缀 现有 id
 *       尾段最大值 + 1；尾段不可解析 ⇒ 拒，不猜）；给了 id 且已存在 ⇒ 拒（要改请先 CancelDemand）；
 *   <li><b>只写 {@code demands}</b>；不造商品/货币/账户/市场。
 * </ul>
 *
 * <p>★★ <b>class-first 世界拒绝</b>：{@link EconomyData#classFirst()} 非空时本命令由 {@link
 * ClassFirstCommandGuard} 在读取 base 后立即具名拒绝 —— class-first 消费由结算按口粮/非必要品规则决定，<b>不读</b> {@code
 * demands}（需求账本不参与 class-first 结算），对应工具未接（后续阶段）；{@code classFirst} 为空（旧档/未播种）时本命令行为逐字不变。
 */
public final class EconomyAddDemandHandler implements CommandHandler {

  private static final String COMMAND = "economy.AddDemand";

  /** class-first 拒绝的理由主体（不读什么 + 真值在哪 + 指路）。 */
  private static final String CLASS_FIRST_GUIDANCE =
      "class-first 消费由结算按口粮/非必要品规则决定，不读 demands（需求账本不参与 class-first 结算）；" + "对应工具未接（后续阶段）";

  @Override
  public String type() {
    return COMMAND;
  }

  // ★ 豁免 RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT（R4a）：两处 Math.multiplyExact 只用于探测
  //   quantityPerCycle × 人口 的溢出；溢出会抛 ArithmeticException，乘积本身不参与后续计算。
  @SuppressFBWarnings(
      value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
      justification = "Math.multiplyExact 仅作溢出探测；溢出由 ArithmeticException 承接，乘积不参与后续计算")
  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Optional<HandlerOutcome> classFirstRejection =
        ClassFirstCommandGuard.rejectIfClassFirst(COMMAND, base, CLASS_FIRST_GUIDANCE);
    if (classFirstRejection.isPresent()) {
      return classFirstRejection.get();
    }
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
      DemandEntry.DemandScope scope =
          parseScope(EconomyCommandPayloads.requireText(COMMAND, payload, "scope"));
      DemandEntry.DemandKind kind =
          parseKind(EconomyCommandPayloads.requireText(COMMAND, payload, "kind"));
      DemandEntry.DemandUnit unit =
          parseUnit(EconomyCommandPayloads.requireText(COMMAND, payload, "unit"));
      CommodityId commodity =
          CommodityId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "commodity"));
      long quantityPerCycle =
          EconomyCommandPayloads.requireLong(COMMAND, payload, "quantityPerCycle");
      if (quantityPerCycle <= 0L) {
        return new HandlerOutcome.Rejected("quantityPerCycle 必须 > 0: " + quantityPerCycle);
      }
      long createdDay = EconomyCommandPayloads.optionalLong(COMMAND, payload, "createdDay", 0L);
      if (createdDay < 0L) {
        return new HandlerOutcome.Rejected("createdDay 不得为负: " + createdDay);
      }
      long expiresDay = EconomyCommandPayloads.optionalLong(COMMAND, payload, "expiresDay", -1L);
      int priority = EconomyCommandPayloads.optionalInt(COMMAND, payload, "priority", 0);
      if (priority < 0) {
        return new HandlerOutcome.Rejected("priority 不得为负: " + priority);
      }
      String source = EconomyCommandPayloads.optionalText(COMMAND, payload, "source", "gm");

      Optional<HouseholdId> household = Optional.empty();
      Optional<HexCoord> hex = Optional.empty();
      String ownerToken;
      if (scope == DemandEntry.DemandScope.HOUSEHOLD) {
        if (payload.hasNonNull("hex")) {
          throw new IllegalArgumentException("HOUSEHOLD 范围不得给 hex（同一件事不许两处拼写）");
        }
        HouseholdId householdId =
            HouseholdId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "household"));
        ClassRow row = base.classes().get(householdId);
        if (row == null) {
          return new HandlerOutcome.Rejected("家户不存在: " + householdId.value());
        }
        requirePriced(base, row.view().hex(), commodity, "该家户居住格");
        if (unit == DemandEntry.DemandUnit.PER_CAPITA) {
          try {
            Math.multiplyExact(quantityPerCycle, row.population());
          } catch (ArithmeticException e) {
            return new HandlerOutcome.Rejected(
                "quantityPerCycle × 家户人口 超出 long（拒绝：订单路径会当场溢出）: "
                    + quantityPerCycle
                    + " × "
                    + row.population());
          }
        }
        household = Optional.of(householdId);
        ownerToken = householdId.value();
      } else {
        if (payload.hasNonNull("household")) {
          throw new IllegalArgumentException("HEX 范围不得给 household（同一件事不许两处拼写）");
        }
        HexCoord hexCoord = EconomyCommandPayloads.requireHex(COMMAND, payload, "hex");
        requirePriced(base, hexCoord, commodity, "该格");
        if (unit == DemandEntry.DemandUnit.PER_CAPITA) {
          try {
            Math.multiplyExact(quantityPerCycle, populationAt(base, hexCoord));
          } catch (ArithmeticException e) {
            return new HandlerOutcome.Rejected(
                "quantityPerCycle × 该格人口 超出 long（拒绝：订单路径会当场溢出）: "
                    + quantityPerCycle
                    + " × "
                    + populationAt(base, hexCoord));
          }
        }
        hex = Optional.of(hexCoord);
        ownerToken = hexCoord.toString();
      }
      DemandId demandId =
          payload.hasNonNull("id")
              ? DemandId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "id"))
              : generatedId(base, scope, ownerToken, commodity, kind, unit);
      if (base.demands().containsKey(demandId)) {
        return new HandlerOutcome.Rejected("需求 id 已存在（要替换请先 CancelDemand）: " + demandId.value());
      }
      DemandEntry entry =
          new DemandEntry(
              demandId,
              scope,
              household,
              hex,
              commodity,
              kind,
              unit,
              quantityPerCycle,
              createdDay,
              expiresDay,
              priority,
              source);
      Map<DemandId, DemandEntry> demands = new LinkedHashMap<>(base.demands());
      demands.put(demandId, entry);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base.withDemands(demands)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 该格必须有市场行、且该商品有价；否则拒绝并**指名** {@code economy.SetMarketPrice}。 */
  private static void requirePriced(
      EconomyData base, HexCoord hex, CommodityId commodity, String where) {
    Market market = base.markets().get(hex);
    if (market == null || market.priceOf(commodity) <= 0L) {
      throw new IllegalArgumentException(
          where
              + " "
              + hex
              + " 没有商品 "
              + commodity.value()
              + " 的价（请先用 economy.SetMarketPrice 给该格定价）");
    }
  }

  /** 该格家户人口（没有家户行 ⇒ 0；HEX 需求的 PER_CAPITA 用它做溢出预检与摊分口径）。 */
  private static long populationAt(EconomyData base, HexCoord hex) {
    long population = 0L;
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    for (Map.Entry<HouseholdId, ClassRow> entry : base.classes().entrySet()) {
      String rowHex =
          IndustryHexKeys.hexKey(
              entry.getValue().view().hex().q(), entry.getValue().view().hex().r());
      if (rowHex.equals(hexKey)) {
        population = Math.addExact(population, entry.getValue().population());
      }
    }
    return population;
  }

  /** 确定性新 id：同前缀现有 id 的尾段序号最大值 + 1；尾段不可解析 ⇒ 抛（fail-closed，不猜）。 */
  private static DemandId generatedId(
      EconomyData base,
      DemandEntry.DemandScope scope,
      String ownerToken,
      CommodityId commodity,
      DemandEntry.DemandKind kind,
      DemandEntry.DemandUnit unit) {
    String prefix =
        "demand-"
            + scope.name()
            + "-"
            + ownerToken
            + "-"
            + commodity.value()
            + "-"
            + kind.name()
            + "-"
            + unit.name()
            + "-";
    long max = -1L;
    for (DemandId id : base.demands().keySet()) {
      String value = id.value();
      if (!value.startsWith(prefix)) {
        continue;
      }
      String tail = value.substring(prefix.length());
      if (tail.isEmpty()) {
        throw new IllegalArgumentException("同前缀需求 id 没有序号尾段（fail-closed，不猜）: " + value);
      }
      for (int i = 0; i < tail.length(); i++) {
        char c = tail.charAt(i);
        if (c < '0' || c > '9') {
          throw new IllegalArgumentException("同前缀需求 id 的尾段不是数字（fail-closed，不猜）: " + value);
        }
      }
      try {
        max = Math.max(max, Long.parseLong(tail));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("同前缀需求 id 的序号超出 long（fail-closed，不猜）: " + value);
      }
    }
    if (max == Long.MAX_VALUE) {
      throw new IllegalArgumentException("无法为需求分配新序号：现有最大序号已达 Long.MAX_VALUE（fail-closed）");
    }
    return new DemandId(prefix + (max + 1L));
  }

  private static DemandEntry.DemandScope parseScope(String text) {
    try {
      return DemandEntry.DemandScope.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 scope: "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(DemandEntry.DemandScope.values()));
    }
  }

  private static DemandEntry.DemandKind parseKind(String text) {
    try {
      return DemandEntry.DemandKind.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 kind: "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(DemandEntry.DemandKind.values()));
    }
  }

  private static DemandEntry.DemandUnit parseUnit(String text) {
    try {
      return DemandEntry.DemandUnit.valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 unit: "
              + text
              + "；合法值: "
              + java.util.Arrays.toString(DemandEntry.DemandUnit.values()));
    }
  }
}
