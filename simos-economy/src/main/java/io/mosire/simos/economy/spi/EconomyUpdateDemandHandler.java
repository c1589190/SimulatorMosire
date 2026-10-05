package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code economy.UpdateDemand}（P2-B §13.6）：**更新**一条已存在的需求（{@link DemandEntry}）。
 * 取消走既有的 {@code economy.CancelDemand}，新增走既有的 {@code economy.AddDemand} —— 三条命令各管一件事。
 *
 * <pre>{@code
 * {"demand":"demand-HOUSEHOLD-hh-...-grain-RECURRING-TOTAL-0",
 *  "scope"?:"HOUSEHOLD","household"?:"hh-...","commodity"?:"grain","kind"?:"RECURRING",
 *  "unit"?:"PER_CAPITA","quantityPerCycle"?:52,"createdDay"?:120,"expiresDay"?:-1,
 *  "priority"?:0,"source"?:"gm","at"?:{"q":0,"r":0}}
 * }</pre>
 *
 * <p>★ <b>语义（逐条）</b>：
 *
 * <ul>
 *   <li>{@code demand} 必须已存在；缺省字段**逐字段沿用旧值**（部分更新，不是整体替换）；
 *   <li>{@code scope} 可换：HOUSEHOLD ⟷ HEX。换档时必须给新档的属主（{@code household} 或 {@code hex}），且不得同时
 *       给另一档的属主；{@link DemandEntry} 的构造期守卫再判一遍互斥；
 *   <li>价格存在性：按**更新后的**属主格判（HOUSEHOLD ⇒ 该户居住格；HEX ⇒ 该格），缺市场/缺该商品价 ⇒ 具名拒绝并指名
 *       {@code economy.SetMarketPrice}（与 {@code AddDemand} 同一条口径）；{@code PER_CAPITA} 还做溢出预检；
 *   <li>只写 {@code demands} 一张表；不改商品/货币/账户/市场；
 *   <li>{@code at} 是给 {@link CommandTargets} 的目标声明（见 {@link HouseholdEconomyCommands}）：HOUSEHOLD 档必须
 *       等于该户居住格；HEX 档必须等于目标格；不给 ⇒ 不进决策令（GM 直通照常）。
 * </ul>
 *
 * <p>★ <b>权限</b>：非 {@code GmOnlyCommand}（与 {@code AddDemand}/{@code CancelDemand} 同待遇）。
 */
public final class EconomyUpdateDemandHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "economy.UpdateDemand";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    EconomyCommandPayloads.requireText(TYPE, payload, "demand");
    if (payload.hasNonNull("hex")) {
      HexCoord hex = EconomyCommandPayloads.requireHex(TYPE, payload, "hex");
      return List.of(io.mosire.simos.util.spi.ResourcePaths.economy(hex.q(), hex.r()));
    }
    // HOUSEHOLD 档的格键只能由调用方显式声明（签名拿不到状态）—— 用 at。
    return HouseholdEconomyCommands.targetPaths(HouseholdEconomyCommands.optionalAt(TYPE, payload));
  }

  // ★ 豁免 RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT：两处 Math.multiplyExact 只作溢出探测（与 AddDemand 同款）。
  @SuppressFBWarnings(
      value = "RV_RETURN_VALUE_IGNORED_NO_SIDE_EFFECT",
      justification = "Math.multiplyExact 仅作溢出探测；溢出由 ArithmeticException 承接，乘积不参与后续计算")
  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      DemandId demandId =
          DemandId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "demand"));
      DemandEntry existing = base.demands().get(demandId);
      if (existing == null) {
        return new HandlerOutcome.Rejected("需求不存在（新增请用 economy.AddDemand）: " + demandId.value());
      }
      DemandEntry.DemandScope scope =
          payload.hasNonNull("scope")
              ? EconomyAddDemandHandler.parseScope(
                  EconomyCommandPayloads.requireText(TYPE, payload, "scope"))
              : existing.scope();
      DemandEntry.DemandKind kind =
          payload.hasNonNull("kind")
              ? EconomyAddDemandHandler.parseKind(
                  EconomyCommandPayloads.requireText(TYPE, payload, "kind"))
              : existing.kind();
      DemandEntry.DemandUnit unit =
          payload.hasNonNull("unit")
              ? EconomyAddDemandHandler.parseUnit(
                  EconomyCommandPayloads.requireText(TYPE, payload, "unit"))
              : existing.unit();
      CommodityId commodity =
          payload.hasNonNull("commodity")
              ? CommodityId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "commodity"))
              : existing.commodity();
      long quantityPerCycle =
          payload.has("quantityPerCycle")
              ? EconomyCommandPayloads.requireLong(TYPE, payload, "quantityPerCycle")
              : existing.quantityPerCycle();
      if (quantityPerCycle <= 0L) {
        return new HandlerOutcome.Rejected("quantityPerCycle 必须 > 0: " + quantityPerCycle);
      }
      long createdDay =
          payload.has("createdDay")
              ? EconomyCommandPayloads.requireLong(TYPE, payload, "createdDay")
              : existing.createdDay();
      if (createdDay < 0L) {
        return new HandlerOutcome.Rejected("createdDay 不得为负: " + createdDay);
      }
      long expiresDay =
          payload.has("expiresDay")
              ? EconomyCommandPayloads.requireLong(TYPE, payload, "expiresDay")
              : existing.expiresDay();
      int priority =
          payload.has("priority")
              ? EconomyCommandPayloads.requireInt(TYPE, payload, "priority")
              : existing.priority();
      if (priority < 0) {
        return new HandlerOutcome.Rejected("priority 不得为负: " + priority);
      }
      String source =
          EconomyCommandPayloads.optionalText(TYPE, payload, "source", existing.source());
      Optional<HexCoord> at = HouseholdEconomyCommands.optionalAt(TYPE, payload);

      Optional<HouseholdId> household = Optional.empty();
      Optional<HexCoord> hex = Optional.empty();
      if (scope == DemandEntry.DemandScope.HOUSEHOLD) {
        if (payload.hasNonNull("hex")) {
          throw new IllegalArgumentException("HOUSEHOLD 范围不得给 hex（同一件事不许两处拼写）");
        }
        HouseholdId householdId =
            payload.hasNonNull("household")
                ? HouseholdId.parse(EconomyCommandPayloads.requireText(TYPE, payload, "household"))
                : existing
                    .household()
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                TYPE + " 换成 HOUSEHOLD 范围必须给 household: " + demandId.value()));
        ClassRow row = base.classes().get(householdId);
        if (row == null) {
          return new HandlerOutcome.Rejected("家户不存在: " + householdId.value());
        }
        if (at.isPresent() && !row.view().hex().equals(at.get())) {
          throw new IllegalArgumentException(
              TYPE + " 的 at 必须等于家户当刻居住格：家户=" + householdId.value() + " at=" + at.get());
        }
        EconomyAddDemandHandler.requirePriced(base, row.view().hex(), commodity, "该家户居住格");
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
      } else {
        if (payload.hasNonNull("household")) {
          throw new IllegalArgumentException("HEX 范围不得给 household（同一件事不许两处拼写）");
        }
        HexCoord hexCoord =
            payload.hasNonNull("hex")
                ? EconomyCommandPayloads.requireHex(TYPE, payload, "hex")
                : existing
                    .hex()
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                TYPE + " 换成 HEX 范围必须给 hex: " + demandId.value()));
        if (at.isPresent() && !hexCoord.equals(at.get())) {
          throw new IllegalArgumentException(
              TYPE + " 的 at 必须等于目标格：hex=" + hexCoord + " at=" + at.get());
        }
        EconomyAddDemandHandler.requirePriced(base, hexCoord, commodity, "该格");
        if (unit == DemandEntry.DemandUnit.PER_CAPITA) {
          long population = EconomyAddDemandHandler.populationAt(base, hexCoord);
          try {
            Math.multiplyExact(quantityPerCycle, population);
          } catch (ArithmeticException e) {
            return new HandlerOutcome.Rejected(
                "quantityPerCycle × 该格人口 超出 long（拒绝：订单路径会当场溢出）: "
                    + quantityPerCycle
                    + " × "
                    + population);
          }
        }
        hex = Optional.of(hexCoord);
      }
      DemandEntry after =
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
      if (after.equals(existing)) {
        return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base));
      }
      Map<DemandId, DemandEntry> demands = new LinkedHashMap<>(base.demands());
      demands.put(demandId, after);
      io.mosire.simos.economy.EconomyLog.market()
          .info(
              "event=DEMAND_UPDATE demand={} scope={} owner={} commodity={} kind={} unit={} quantityPerCycle={} priority={}",
              demandId.value(),
              scope.name(),
              household.map(h -> h.value()).orElse(hex.map(Object::toString).orElse("-")),
              commodity.value(),
              kind.name(),
              unit.name(),
              quantityPerCycle,
              priority);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base.withDemands(demands)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
