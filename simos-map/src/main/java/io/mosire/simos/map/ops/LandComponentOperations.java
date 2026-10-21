package io.mosire.simos.map.ops;

import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandComponentId;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 土地组件的纯操作：不碰 GameMap / ChangeSet，只把旧 {@link HexLand} 变成新 {@link HexLand}。
 *
 * <p>所有操作都保持：
 *
 * <ul>
 *   <li>同一 type 下“除 id/quantity 外属性全同”的 component 合并，保留字典序最小 id；
 *   <li>不同 type 永不合并；
 *   <li>0 数量行删除（构造层本就不接受 0）；
 *   <li>component id 的 ordinal 由 {@link LandTypeArea#nextComponentOrdinal()} 落盘分配，不使用进程计数器/随机/UUID。
 * </ul>
 */
public final class LandComponentOperations {

  /** 旧单 component setter 遇到多 component 时的具名拒绝理由。 */
  public static final String MULTIPLE_COMPONENTS_REASON = "land-type-has-multiple-components";

  private LandComponentOperations() {}

  /**
   * 创世/首建：生成一个只含该 type 一个 component 的 {@link HexLand}。首个组件使用 ordinal 0，next = 1。
   *
   * @param hex 所在格（只用于稳定 id）
   * @param type 土地类型
   * @param quantity 面积（毫亩，{@code > 0}）
   * @param attributes 属性（非 null；owner 是否必填由调用方/B2 播种决定）
   */
  public static HexLand seedSingle(
      HexCoord hex,
      LandTypeId type,
      long quantity,
      Map<LandAttributeKey, LandAttributeValue> attributes) {
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(attributes, "attributes");
    LandComponent component =
        new LandComponent(new LandComponentId(componentId(type, hex, 0L)), quantity, attributes);
    LandTypeArea area = new LandTypeArea(type, List.of(component), 1L);
    Map<LandTypeId, LandTypeArea> byType = new LinkedHashMap<>();
    byType.put(type, area);
    return new HexLand(byType);
  }

  /** 某土地类型总量（毫亩）；没有该 type 时 0。 */
  public static long total(HexLand land, LandTypeId type) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(type, "type");
    return land.totalMilliMu(type);
  }

  /**
   * 插入或按 component id 覆盖一个 component，随后归一化（排序 + 合并）。新 component 的 ordinal 必须高于当前 next；若调用方给的
   * ordinal 更大，则把 next 推进到 ordinal+1。
   */
  public static HexLand upsertComponent(
      HexLand land, HexCoord hex, LandTypeId type, LandComponent component) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(component, "component");
    long ordinal = LandTypeArea.ordinalOf(type, component.id());
    LandTypeArea existing = land.byType().get(type);
    List<LandComponent> components =
        existing == null ? new ArrayList<>() : new ArrayList<>(existing.components());
    boolean replaced = false;
    for (int i = 0; i < components.size(); i++) {
      if (components.get(i).id().equals(component.id())) {
        components.set(i, component);
        replaced = true;
        break;
      }
    }
    if (!replaced) {
      components.add(component);
    }
    long next =
        existing == null ? ordinal + 1L : Math.max(existing.nextComponentOrdinal(), ordinal + 1L);
    LandTypeArea area = new LandTypeArea(type, components, next);
    return normalize(putArea(land, area), hex, type);
  }

  /** 删除指定 component；不存在时幂等返回原土地（不回收 ordinal）。 */
  public static HexLand removeComponent(
      HexLand land, HexCoord hex, LandTypeId type, LandComponentId id) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(id, "id");
    LandTypeArea existing = land.byType().get(type);
    if (existing == null) {
      return land;
    }
    List<LandComponent> components = new ArrayList<>();
    boolean removed = false;
    for (LandComponent component : existing.components()) {
      if (component.id().equals(id)) {
        removed = true;
      } else {
        components.add(component);
      }
    }
    if (!removed) {
      return land;
    }
    LandTypeArea area = new LandTypeArea(type, components, existing.nextComponentOrdinal());
    return normalize(putArea(land, area), hex, type);
  }

  /** 改一个 component 的属性；id/quantity 不变，随后归一化。 */
  public static HexLand setComponentAttribute(
      HexLand land,
      HexCoord hex,
      LandTypeId type,
      LandComponentId id,
      LandAttributeKey key,
      LandAttributeValue value) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    LandTypeArea existing = land.byType().get(type);
    if (existing == null) {
      throw new IllegalArgumentException("土地类型不存在: " + type);
    }
    List<LandComponent> components = new ArrayList<>(existing.components().size());
    boolean found = false;
    for (LandComponent component : existing.components()) {
      if (component.id().equals(id)) {
        components.add(component.withAttribute(key, value));
        found = true;
      } else {
        components.add(component);
      }
    }
    if (!found) {
      throw new IllegalArgumentException("component 不存在: " + id);
    }
    LandTypeArea area = new LandTypeArea(type, components, existing.nextComponentOrdinal());
    return normalize(putArea(land, area), hex, type);
  }

  /**
   * 按 owner 转移数量：卖方 component 按 id 升序消耗；整块转走只改 owner（id 不变），部分转走保留卖方剩余 id 并用该 type 的 next ordinal
   * 生成买方新 id；最后立即归一化。
   *
   * <p>失败前置校验卖方总量，绝不返回“转了一半”的半成品。
   */
  public static HexLand transferQuantity(
      HexLand land,
      HexCoord hex,
      LandTypeId type,
      String fromOwner,
      String toOwner,
      long quantity) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(type, "type");
    requireOwner(fromOwner, "fromOwner");
    requireOwner(toOwner, "toOwner");
    if (fromOwner.equals(toOwner)) {
      throw new IllegalArgumentException("fromOwner 与 toOwner 不得相同: " + fromOwner);
    }
    if (quantity <= 0L) {
      throw new IllegalArgumentException("转移数量必须大于 0（毫亩）: " + quantity);
    }
    LandTypeArea area = land.byType().get(type);
    if (area == null) {
      throw new IllegalArgumentException("土地类型不存在: " + type);
    }
    long available = 0L;
    for (LandComponent component : area.components()) {
      if (ownerOf(component).equals(fromOwner)) {
        available = Math.addExact(available, component.quantityMilliMu());
      }
    }
    if (quantity > available) {
      throw new IllegalArgumentException(
          "卖方土地数量不足: type="
              + type
              + " owner="
              + fromOwner
              + " 需要="
              + quantity
              + " 可用="
              + available);
    }

    long remaining = quantity;
    long nextOrdinal = area.nextComponentOrdinal();
    List<LandComponent> result = new ArrayList<>();
    for (LandComponent component : area.components()) {
      if (remaining == 0L || !ownerOf(component).equals(fromOwner)) {
        result.add(component);
        continue;
      }
      long take = Math.min(remaining, component.quantityMilliMu());
      boolean whole = take == component.quantityMilliMu();
      if (whole) {
        result.add(
            component.withAttribute(LandAttributeKey.OWNER, LandAttributeValue.ofString(toOwner)));
      } else {
        result.add(component.withQuantity(component.quantityMilliMu() - take));
        LandComponentId newId = new LandComponentId(componentId(type, hex, nextOrdinal));
        nextOrdinal++;
        result.add(
            new LandComponent(
                newId,
                take,
                replaceAttribute(
                    component.attributes(),
                    LandAttributeKey.OWNER,
                    LandAttributeValue.ofString(toOwner))));
      }
      remaining -= take;
    }
    if (remaining != 0L) {
      // 前置求和已经保证到不了这里；仍是 fail-closed。
      throw new IllegalStateException("土地转移数量不守恒: remaining=" + remaining);
    }
    LandTypeArea nextArea = new LandTypeArea(type, result, nextOrdinal);
    return normalize(putArea(land, nextArea), hex, type);
  }

  /**
   * 合并同一 type 下 attributes 逐项完全相等的 component：数量相加、id 取字典序最小。不同 type 不在此方法内处理。
   *
   * <p>不比较 id/quantity；所有属性（owner/progress/crop/fertility/自定义）都参与判等。
   */
  public static LandTypeArea mergeEqualComponents(LandTypeArea area) {
    Objects.requireNonNull(area, "area");
    Map<Map<LandAttributeKey, LandAttributeValue>, LandComponent> merged = new LinkedHashMap<>();
    for (LandComponent component : area.components()) {
      LandComponent prior = merged.get(component.attributes());
      if (prior == null) {
        merged.put(component.attributes(), component);
      } else {
        long quantity = Math.addExact(prior.quantityMilliMu(), component.quantityMilliMu());
        LandComponentId id =
            prior.id().value().compareTo(component.id().value()) <= 0 ? prior.id() : component.id();
        merged.put(prior.attributes(), new LandComponent(id, quantity, prior.attributes()));
      }
    }
    return new LandTypeArea(
        area.type(), new ArrayList<>(merged.values()), area.nextComponentOrdinal());
  }

  /** 对指定 type 执行“排序 + 合并”；没有该 type 时原样返回。空 area 保留 next 序号。 */
  public static HexLand normalize(HexLand land, HexCoord hex, LandTypeId type) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(type, "type");
    LandTypeArea area = land.byType().get(type);
    if (area == null) {
      return land;
    }
    return putArea(land, mergeEqualComponents(area));
  }

  /**
   * 旧 {@code map.SetHexArea} 的“单 component 类型总量 setter”：
   *
   * <ul>
   *   <li>0 个 component ⇒ 数量 {@code >0} 时用 next ordinal 生成第一个 component；0 时保持不变；
   *   <li>1 个 component ⇒ 改它的数量；0 时删除 component（type area 保留，序号不回收）；
   *   <li>≥2 个 component ⇒ 具名拒绝 {@link #MULTIPLE_COMPONENTS_REASON}。
   * </ul>
   */
  public static HexLand setSingleComponentQuantity(
      HexLand land, HexCoord hex, LandTypeId type, long quantityMilliMu) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(type, "type");
    if (quantityMilliMu < 0L) {
      throw new IllegalArgumentException("土地总量不得为负（毫亩）: " + quantityMilliMu);
    }
    LandTypeArea area = land.byType().get(type);
    if (area != null && area.components().size() >= 2) {
      throw new IllegalArgumentException(MULTIPLE_COMPONENTS_REASON);
    }
    if (area == null || area.components().isEmpty()) {
      if (quantityMilliMu == 0L) {
        return land;
      }
      long ordinal = area == null ? 0L : area.nextComponentOrdinal();
      LandComponent component =
          new LandComponent(
              new LandComponentId(componentId(type, hex, ordinal)), quantityMilliMu, Map.of());
      LandTypeArea nextArea = new LandTypeArea(type, List.of(component), ordinal + 1L);
      return normalize(putArea(land, nextArea), hex, type);
    }
    LandComponent only = area.components().get(0);
    if (quantityMilliMu == 0L) {
      return removeComponent(land, hex, type, only.id());
    }
    if (only.quantityMilliMu() == quantityMilliMu) {
      return land;
    }
    LandTypeArea nextArea =
        new LandTypeArea(
            type, List.of(only.withQuantity(quantityMilliMu)), area.nextComponentOrdinal());
    return normalize(putArea(land, nextArea), hex, type);
  }

  /**
   * ★★★ <b>D-032 L4：按 owner 总量重设某 hex 的 arable 组件归属</b>。
   *
   * <p>语义（方案 A 的 Map 侧写口）：
   *
   * <ol>
   *   <li>只处理 {@link LandTypeId#ARABLE}，其它土地类型原样保留；
   *   <li>{@code ownerQuantities} 的 key 非空白且 value {@code > 0}，否则具名拒绝；
   *   <li>已有同 owner 的 component ⇒ 复用其 id/其它属性，仅把数量改为该 owner 的聚合总量； 同一 owner 若存在多个 component 且除
   *       owner 外的属性不同 ⇒ 聚合无法区分，具名拒绝 {@code land-owner-aggregation-unsupported}；
   *   <li>不再出现在 {@code ownerQuantities} 里的 arable component 删除；
   *   <li>新 owner 用该 type 的 {@link LandTypeArea#nextComponentOrdinal()} 生成新 id，属性至少含 owner；
   *   <li>最后执行合并规则（同属性 component 面积相加、保留字典序最小 id）。
   * </ol>
   *
   * <p>★ 本方法只改归属/数量形状，不校验“Σ ownerQuantity == 原有 arable 总量”——那是命令边界与 app 写回链的职责；
   * 传入的总量不足/超出会在下一层组件账本对账时 fail-closed。
   */
  public static HexLand setArableOwners(
      HexLand land, HexCoord hex, Map<String, Long> ownerQuantities) {
    Objects.requireNonNull(land, "land");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(ownerQuantities, "ownerQuantities");
    Map<String, Long> orderedOwners = new java.util.TreeMap<>();
    for (Map.Entry<String, Long> entry : ownerQuantities.entrySet()) {
      String owner = entry.getKey();
      Long quantity = entry.getValue();
      requireOwner(owner, "owner");
      if (quantity == null || quantity <= 0L) {
        throw new IllegalArgumentException(
            "ownerQuantity 必须大于 0（毫亩）: owner=" + owner + " value=" + quantity);
      }
      if (orderedOwners.put(owner, quantity) != null) {
        throw new IllegalArgumentException("ownerQuantities 不得含重复 owner: " + owner);
      }
    }

    LandTypeArea existing = land.byType().get(LandTypeId.ARABLE);
    if (existing == null && orderedOwners.isEmpty()) {
      return land;
    }
    long nextOrdinal = existing == null ? 0L : existing.nextComponentOrdinal();
    // owner -> 可复用 component（按 component id 规范升序扫描；同 owner 的其它属性必须完全一致）。
    Map<String, LandComponent> reusable = new LinkedHashMap<>();
    Map<String, Map<LandAttributeKey, LandAttributeValue>> reusableAttrs = new LinkedHashMap<>();
    if (existing != null) {
      for (LandComponent component : existing.components()) {
        String owner = ownerOf(component);
        if (owner.isBlank() || !orderedOwners.containsKey(owner)) {
          continue;
        }
        Map<LandAttributeKey, LandAttributeValue> nonOwnerAttrs =
            withoutOwner(component.attributes());
        LandComponent prior = reusable.get(owner);
        if (prior == null) {
          reusable.put(owner, component);
          reusableAttrs.put(owner, nonOwnerAttrs);
        } else if (!reusableAttrs.get(owner).equals(nonOwnerAttrs)) {
          throw new IllegalArgumentException(
              "land-owner-aggregation-unsupported: 同一 owner 的多个 arable component 除 owner 外属性不同，"
                  + "聚合账本无法区分: hex="
                  + hex
                  + " owner="
                  + owner
                  + " first="
                  + prior.id()
                  + " second="
                  + component.id());
        }
      }
    }

    List<LandComponent> components = new ArrayList<>();
    for (Map.Entry<String, Long> entry : orderedOwners.entrySet()) {
      String owner = entry.getKey();
      long quantity = entry.getValue();
      LandComponent prior = reusable.get(owner);
      if (prior != null) {
        components.add(prior.withQuantity(quantity));
      } else {
        LandComponentId id = new LandComponentId(componentId(LandTypeId.ARABLE, hex, nextOrdinal));
        nextOrdinal++;
        components.add(
            new LandComponent(
                id, quantity, Map.of(LandAttributeKey.OWNER, LandAttributeValue.ofString(owner))));
      }
    }
    LandTypeArea arable = new LandTypeArea(LandTypeId.ARABLE, components, nextOrdinal);
    return normalize(putArea(land, arable), hex, LandTypeId.ARABLE);
  }

  /** 去掉 owner 属性后的属性表（用于判断同 owner 的多个 component 是否可由聚合账本统一表示）。 */
  private static Map<LandAttributeKey, LandAttributeValue> withoutOwner(
      Map<LandAttributeKey, LandAttributeValue> attributes) {
    Map<LandAttributeKey, LandAttributeValue> out = new LinkedHashMap<>(attributes);
    out.remove(LandAttributeKey.OWNER);
    return out;
  }

  /** 取 component 的 owner 属性；缺失返回空串（卖方匹配不会命中）。 */
  public static String ownerOf(LandComponent component) {
    Objects.requireNonNull(component, "component");
    LandAttributeValue value = component.attributes().get(LandAttributeKey.OWNER);
    return value == null ? "" : value.stringValue();
  }

  /** component id 的唯一拼写点：{@code <type>@<hex>:<ordinal>}。 */
  public static String componentId(LandTypeId type, HexCoord hex, long ordinal) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(hex, "hex");
    if (ordinal < 0L) {
      throw new IllegalArgumentException("component ordinal 不得为负: " + ordinal);
    }
    return type.wireValue() + "@" + hex + ":" + ordinal;
  }

  private static HexLand putArea(HexLand land, LandTypeArea area) {
    Map<LandTypeId, LandTypeArea> byType = new LinkedHashMap<>(land.byType());
    byType.put(area.type(), area);
    return new HexLand(byType);
  }

  private static void requireOwner(String owner, String name) {
    if (owner == null || owner.isBlank()) {
      throw new IllegalArgumentException(name + " 不得为空白");
    }
  }

  private static Map<LandAttributeKey, LandAttributeValue> replaceAttribute(
      Map<LandAttributeKey, LandAttributeValue> attributes,
      LandAttributeKey key,
      LandAttributeValue value) {
    Map<LandAttributeKey, LandAttributeValue> next = new LinkedHashMap<>(attributes);
    next.put(key, value);
    return next;
  }
}
