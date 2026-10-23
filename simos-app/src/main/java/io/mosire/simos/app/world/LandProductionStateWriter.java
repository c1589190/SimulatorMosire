package io.mosire.simos.app.world;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.inventory.LotState;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.LandComponentOperations;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★ D-032 L2a：把 carrier lot 的 LAND 生产状态写回 Map 的纯函数写回口。
 *
 * <p>目标运行位置：S4（按 carrier lot 执行、扣料并推进 stage/progress）与 S5（按 lot 收获后清空生产属性）之前的写回原语。 本类只把 {@link
 * LotState.InProduction} / {@link LotState.Idle} 编码到 ARABLE {@link LandComponent}
 * 的抽象属性，不执行生产、不扣库存、不构造 ChangeSet。
 *
 * <p>编码：
 *
 * <ul>
 *   <li>{@code crop} = planId（STRING）
 *   <li>{@code stage} = stageId（STRING）
 *   <li>{@code progress} = stageProgressDays（LONG）
 *   <li>{@code cycle_progress} = cycleProgressDays（LONG）
 *   <li>{@code start_input_paid} = startInputPaid（BOOLEAN）
 *   <li>{@code cycle_input:<commodity>} = cycleInputUsedMilli.get(commodity)（LONG，逐商品一项）
 * </ul>
 *
 * <p>纯函数：不修改 {@code base}；先复制 {@code base.hexes()} 工作副本，成功才 {@link
 * GameMap#withHexes}，失败抛具名异常且不返回半成品。 只按 hex 逐个把新 {@link HexLand} 写回工作副本，不触碰非目标 hex；同一 hex
 * 的后续写能看到前序结果。OWNER / FERTILITY / 其它非生产属性原样保留，且本类永不写 OWNER。
 */
public final class LandProductionStateWriter {

  /** cycle_input 属性键的唯一拼写点；Builder 读回时必须引用本常量。 */
  public static final String CYCLE_INPUT_KEY_PREFIX = "cycle_input:";

  private static final String LOT_MISSING_REASON = "land-production-lot-missing";
  private static final String WRITE_DUPLICATE_REASON = "land-production-write-duplicate";
  private static final String LOT_AMBIGUOUS_REASON = "land-production-lot-ambiguous";
  private static final String STATE_INVALID_REASON = "land-production-state-invalid";
  private static final String WRITE_INVALID_REASON = "land-production-write-invalid";

  private static final Set<LandAttributeKey> PRODUCTION_ATTRIBUTE_KEYS =
      Set.of(
          LandAttributeKey.CROP,
          LandAttributeKey.STAGE,
          LandAttributeKey.PROGRESS,
          LandAttributeKey.CYCLE_PROGRESS,
          LandAttributeKey.START_INPUT_PAID);

  /**
   * 一次写回请求：目标 ARABLE component 的稳定 id + 该 lot 的目标状态。
   *
   * <p>只接受 {@link LotState.InProduction} 与 {@link LotState.Idle}；{@link LotState.Complete}
   * 及其它状态具名拒绝。
   */
  public record Write(LandComponentId componentId, LotState state) {}

  private LandProductionStateWriter() {}

  /**
   * 把一组 lot 状态写回工作地图副本。
   *
   * @param base 原地图（不会被修改）
   * @param writes 写回请求（按给定顺序逐个应用；同一 component id 只能出现一次）
   * @return 写回后的新地图
   */
  public static GameMap apply(GameMap base, List<Write> writes) {
    if (base == null) {
      throw new IllegalArgumentException(WRITE_INVALID_REASON + ": base=null");
    }
    if (writes == null) {
      throw new IllegalArgumentException(WRITE_INVALID_REASON + ": writes=null");
    }
    validateWrites(writes);
    if (writes.isEmpty()) {
      return base;
    }
    Map<HexCoord, HexCell> working = new LinkedHashMap<>(base.hexes());
    for (Write write : writes) {
      HexCoord hex = locateComponentHex(working, write.componentId());
      HexCell cell = working.get(hex);
      HexLand land = cell.land();
      LandComponent component =
          LandComponentOperations.componentOf(land, LandTypeId.ARABLE, write.componentId());
      if (component == null) {
        // locateComponentHex 已保证到不了这里；仍是 fail-closed。
        throw new IllegalStateException(
            LOT_MISSING_REASON + ": lot=" + write.componentId() + ", hex=" + hex);
      }
      HexLand updated = applyOne(land, hex, component, write);
      working.put(hex, cell.withLand(updated));
    }
    return base.withHexes(working);
  }

  private static void validateWrites(List<Write> writes) {
    Set<LandComponentId> seenIds = new LinkedHashSet<>();
    for (int index = 0; index < writes.size(); index++) {
      Write write = writes.get(index);
      if (write == null) {
        throw new IllegalArgumentException(WRITE_INVALID_REASON + ": write[" + index + "]=null");
      }
      LandComponentId componentId = write.componentId();
      if (componentId == null) {
        throw new IllegalArgumentException(
            WRITE_INVALID_REASON + ": write[" + index + "].componentId=null");
      }
      if (!seenIds.add(componentId)) {
        throw new IllegalArgumentException(
            WRITE_DUPLICATE_REASON + ": lot=" + componentId + ", index=" + index);
      }
      LotState state = write.state();
      if (state == null) {
        throw new IllegalArgumentException(
            STATE_INVALID_REASON
                + ": lot="
                + componentId
                + ", hex=<unknown>, key=<state>, state=null");
      }
      if (!(state instanceof LotState.InProduction) && !(state instanceof LotState.Idle)) {
        throw new IllegalArgumentException(
            STATE_INVALID_REASON
                + ": lot="
                + componentId
                + ", hex=<unknown>, key=<state>, state="
                + state.getClass().getSimpleName());
      }
    }
  }

  /** 在 ARABLE 下按 component id 定位唯一 hex；找不到具名 missing，命中多个 hex 具名 ambiguous。 */
  private static HexCoord locateComponentHex(
      Map<HexCoord, HexCell> working, LandComponentId componentId) {
    HexCoord found = null;
    for (Map.Entry<HexCoord, HexCell> entry : working.entrySet()) {
      LandComponent component =
          LandComponentOperations.componentOf(
              entry.getValue().land(), LandTypeId.ARABLE, componentId);
      if (component == null) {
        continue;
      }
      if (found != null) {
        throw new IllegalArgumentException(
            LOT_AMBIGUOUS_REASON
                + ": lot="
                + componentId
                + ", hexes=["
                + found
                + ", "
                + entry.getKey()
                + "]");
      }
      found = entry.getKey();
    }
    if (found == null) {
      throw new IllegalArgumentException(
          LOT_MISSING_REASON + ": lot=" + componentId + ", hex=<absent>");
    }
    return found;
  }

  private static HexLand applyOne(
      HexLand land, HexCoord hex, LandComponent component, Write write) {
    LotState state = write.state();
    if (state instanceof LotState.InProduction inProduction) {
      Map<LandAttributeKey, LandAttributeValue> attributes =
          productionAttributes(write.componentId(), hex, inProduction);
      HexLand cleared =
          LandComponentOperations.removeComponentAttributes(
              land, hex, LandTypeId.ARABLE, component.id(), productionKeys(component));
      if (LandComponentOperations.componentOf(cleared, LandTypeId.ARABLE, component.id()) == null) {
        throw new IllegalStateException(
            LOT_MISSING_REASON
                + ": lot="
                + component.id()
                + ", hex="
                + hex
                + ", reason=component-disappeared-after-production-clear");
      }
      return LandComponentOperations.setComponentAttributes(
          cleared, hex, LandTypeId.ARABLE, component.id(), attributes);
    }
    if (state instanceof LotState.Idle) {
      return LandComponentOperations.removeComponentAttributes(
          land, hex, LandTypeId.ARABLE, component.id(), productionKeys(component));
    }
    // validateWrites 已保证到不了这里；仍是 fail-closed。
    throw new IllegalArgumentException(
        STATE_INVALID_REASON
            + ": lot="
            + write.componentId()
            + ", hex="
            + hex
            + ", key=<state>, state="
            + (state == null ? "null" : state.getClass().getSimpleName()));
  }

  /** 把 InProduction 编码成属性表；所有非法字段具名 fail-closed（消息含 lotId/hex/key）。 */
  private static Map<LandAttributeKey, LandAttributeValue> productionAttributes(
      LandComponentId componentId, HexCoord hex, LotState.InProduction state) {
    requireText(componentId, hex, LandAttributeKey.CROP, state.planId());
    requireText(componentId, hex, LandAttributeKey.STAGE, state.stageId());
    if (state.stageProgressDays() < 0L) {
      throw new IllegalArgumentException(
          STATE_INVALID_REASON
              + ": lot="
              + componentId
              + ", hex="
              + hex
              + ", key="
              + LandAttributeKey.PROGRESS.wireValue()
              + ", value="
              + state.stageProgressDays()
              + ", reason=negative");
    }
    if (state.cycleProgressDays() < 0L) {
      throw new IllegalArgumentException(
          STATE_INVALID_REASON
              + ": lot="
              + componentId
              + ", hex="
              + hex
              + ", key="
              + LandAttributeKey.CYCLE_PROGRESS.wireValue()
              + ", value="
              + state.cycleProgressDays()
              + ", reason=negative");
    }
    Map<LandAttributeKey, LandAttributeValue> attributes = new LinkedHashMap<>();
    attributes.put(LandAttributeKey.CROP, LandAttributeValue.ofString(state.planId()));
    attributes.put(LandAttributeKey.STAGE, LandAttributeValue.ofString(state.stageId()));
    attributes.put(LandAttributeKey.PROGRESS, LandAttributeValue.ofLong(state.stageProgressDays()));
    attributes.put(
        LandAttributeKey.CYCLE_PROGRESS, LandAttributeValue.ofLong(state.cycleProgressDays()));
    attributes.put(
        LandAttributeKey.START_INPUT_PAID, LandAttributeValue.ofBoolean(state.startInputPaid()));
    Map<CommodityId, Long> cycleInputUsedMilli = state.cycleInputUsedMilli();
    if (cycleInputUsedMilli == null) {
      throw new IllegalArgumentException(
          STATE_INVALID_REASON
              + ": lot="
              + componentId
              + ", hex="
              + hex
              + ", key="
              + CYCLE_INPUT_KEY_PREFIX
              + "*"
              + ", reason=cycleInputUsedMilli-null");
    }
    Set<String> encodedKeys = new LinkedHashSet<>();
    for (Map.Entry<CommodityId, Long> entry : cycleInputUsedMilli.entrySet()) {
      CommodityId commodity = entry.getKey();
      Long value = entry.getValue();
      if (commodity == null || commodity.value() == null || commodity.value().isBlank()) {
        throw new IllegalArgumentException(
            STATE_INVALID_REASON
                + ": lot="
                + componentId
                + ", hex="
                + hex
                + ", key="
                + CYCLE_INPUT_KEY_PREFIX
                + "<commodity>"
                + ", reason=commodity-blank");
      }
      String keyText = CYCLE_INPUT_KEY_PREFIX + commodity.value();
      if (value == null) {
        throw new IllegalArgumentException(
            STATE_INVALID_REASON
                + ": lot="
                + componentId
                + ", hex="
                + hex
                + ", key="
                + keyText
                + ", reason=value-null");
      }
      if (value < 0L) {
        throw new IllegalArgumentException(
            STATE_INVALID_REASON
                + ": lot="
                + componentId
                + ", hex="
                + hex
                + ", key="
                + keyText
                + ", value="
                + value
                + ", reason=negative");
      }
      if (!encodedKeys.add(keyText)) {
        throw new IllegalArgumentException(
            STATE_INVALID_REASON
                + ": lot="
                + componentId
                + ", hex="
                + hex
                + ", key="
                + keyText
                + ", reason=cycle-input-duplicate");
      }
      attributes.put(new LandAttributeKey(keyText), LandAttributeValue.ofLong(value));
    }
    return attributes;
  }

  private static void requireText(
      LandComponentId componentId, HexCoord hex, LandAttributeKey key, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(
          STATE_INVALID_REASON
              + ": lot="
              + componentId
              + ", hex="
              + hex
              + ", key="
              + key.wireValue()
              + ", reason=blank");
    }
  }

  /** 目标 component 上需要清掉的生产属性键：5 个固定键 + 当前全部 cycle_input:* 键。 */
  private static Set<LandAttributeKey> productionKeys(LandComponent component) {
    Set<LandAttributeKey> keys = new LinkedHashSet<>();
    for (LandAttributeKey key : component.attributes().keySet()) {
      if (PRODUCTION_ATTRIBUTE_KEYS.contains(key)
          || key.wireValue().startsWith(CYCLE_INPUT_KEY_PREFIX)) {
        keys.add(key);
      }
    }
    return keys;
  }
}
