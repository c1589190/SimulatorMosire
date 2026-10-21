package io.mosire.simos.economy.land;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.api.land.LandArableSupply;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.map.HexLand;
import io.mosire.simos.map.LandAttributeKey;
import io.mosire.simos.map.LandAttributeValue;
import io.mosire.simos.map.LandComponent;
import io.mosire.simos.map.LandTypeArea;
import io.mosire.simos.map.LandTypeId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * ★★★ <b>D-032 L4：economy 的瞬态 LAND 组件账本</b>（从 Map 的 {@link HexLand} 构造，不落盘）。
 *
 * <p>★★ <b>唯一权威</b>：Map component 的 {@code attributes["owner"]} 是 LAND 所有权的唯一持久权威； 本类只是本 tick 的工作副本
 * + 给现有市场/债务/合户/迁移代码消费的聚合视图：
 *
 * <pre>
 * Map&lt;HexCoord, HexLand&gt; componentsByHex   （本轮工作副本）
 * Map&lt;HexCoord, Long&gt; arableByHex           （从 Map 的 arable component 求和派生）
 * LinkedHashMap&lt;LandHoldingKey, LandHolding&gt; holdings （owner 聚合视图，瞬态）
 * </pre>
 *
 * <p>★★ <b>不落盘</b>：不进 {@code EconomyData}/{@code EconomyChangeSet}/{@code Codec}；轮末由 app 调 {@code
 * LandComponentOperations.setArableOwners} 把最终聚合写回 Map，并在同一 revision 提交 {@code MapChangeSet}。
 *
 * <p>★ <b>失败语义</b>：arable 类型有正面积 component 但缺少非空 {@code owner} STRING 属性 ⇒ fail-closed（ {@code
 * land-owner-missing}）。空 arable / 0 面积是合法空账本。
 */
public final class LandComponentLedger {

  private final Map<HexCoord, HexLand> componentsByHex;
  private final LandArableSupply arableSupply;
  private final LinkedHashMap<LandHoldingKey, LandHolding> holdings;

  private LandComponentLedger(
      Map<HexCoord, HexLand> componentsByHex,
      LandArableSupply arableSupply,
      LinkedHashMap<LandHoldingKey, LandHolding> holdings) {
    this.componentsByHex = Collections.unmodifiableMap(new LinkedHashMap<>(componentsByHex));
    this.arableSupply = Objects.requireNonNull(arableSupply, "arableSupply");
    this.holdings = holdings;
  }

  /** 合法空账本（只用于没有 map/没有土地的夹具；真生产路径必须从 Map 构造）。 */
  public static LandComponentLedger empty() {
    return new LandComponentLedger(Map.of(), LandArableSupply.empty(), new LinkedHashMap<>());
  }

  /**
   * 旧夹具兼容：只有 arable 总量快照、没有 Map 组件的瞬态账本。
   *
   * <p>★ 这不是生产入口（Map component.owner 才是唯一权威）；真生产路径必须用 {@link #from(Map)}。
   */
  public static LandComponentLedger fromArableSupplyOnly(LandArableSupply supply) {
    Objects.requireNonNull(supply, "supply");
    return new LandComponentLedger(Map.of(), supply, new LinkedHashMap<>());
  }

  /**
   * 从 Map 的逐 hex 土地树构造瞬态账本：逐 hex 求 arable 总量，按 component 的 owner 属性聚合成 holding。
   *
   * @param componentsByHex Map 的 {@code hexes[].land()}；不得为 null
   */
  public static LandComponentLedger from(Map<HexCoord, HexLand> componentsByHex) {
    Objects.requireNonNull(componentsByHex, "componentsByHex");
    TreeMap<HexCoord, HexLand> orderedComponents = new TreeMap<>();
    TreeMap<HexCoord, Long> arableByHex = new TreeMap<>();
    TreeMap<LandHoldingKey, LandHolding> orderedHoldings = new TreeMap<>();
    for (Map.Entry<HexCoord, HexLand> entry : componentsByHex.entrySet()) {
      HexCoord hex = Objects.requireNonNull(entry.getKey(), "componentsByHex 的 key 不得为 null");
      HexLand land = Objects.requireNonNull(entry.getValue(), "componentsByHex 的 value 不得为 null");
      orderedComponents.put(hex, land);
      LandTypeArea area = land.byType().get(LandTypeId.ARABLE);
      if (area == null || area.components().isEmpty()) {
        arableByHex.put(hex, 0L);
        continue;
      }
      long total = 0L;
      for (LandComponent component : area.components()) {
        total = Math.addExact(total, component.quantityMilliMu());
        String owner = ownerOf(hex, component);
        LandHoldingKey key = new LandHoldingKey(hex, HouseholdId.parse(owner));
        LandHolding prior = orderedHoldings.get(key);
        long quantity =
            prior == null
                ? component.quantityMilliMu()
                : Math.addExact(prior.quantityMilliMu(), component.quantityMilliMu());
        orderedHoldings.put(key, new LandHolding(key.owner(), hex, quantity));
      }
      arableByHex.put(hex, total);
    }
    LinkedHashMap<LandHoldingKey, LandHolding> holdings = new LinkedHashMap<>();
    holdings.putAll(orderedHoldings);
    return new LandComponentLedger(orderedComponents, new LandArableSupply(arableByHex), holdings);
  }

  /** app 从 Map 构造的每 hex 可耕总量瞬态快照（不从持有反推）。 */
  public LandArableSupply arableSupply() {
    return arableSupply;
  }

  /** 全部持有的稳定只读视图（无 0 行、按 {@link LandHoldingKey} 自然序）。 */
  public Map<LandHoldingKey, LandHolding> holdingsOrEmpty() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(holdings));
  }

  /**
   * 本会话的**可变工作副本**（现有市场/债务/合户/迁移代码的写口；与 {@link #holdingsOrEmpty()} 是同一份 瞬态聚合，不直接写 Map）。
   *
   * <p>★ 调用方必须保持每 hex 合计等于 {@link #arableSupply()}；轮末写回前由 app 复核。
   *
   * <p>★★ <b>B12 登记：EI_EXPOSE_REP 是孤立例外，不是缺陷</b>——返回的是本会话单线程、用完即弃的<b>可变工作副本</b>， 不是公开状态读口（只读读口是
   * {@link #holdingsOrEmpty()}）；调用方本就要求就地修改这份同一份瞬态聚合，防御性拷贝会切断工作副本语义。例外由下面这条**具名**
   * {@code @SuppressFBWarnings} 登记（不是静默排除、不是排除清单）。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification =
          "孤立例外：holdings() 是本会话单线程、用完即弃的可变工作副本（现有市场/债务/合户/迁移代码的就地写口），不是公开状态读口（只读出口是 holdingsOrEmpty()）；调用方需要就地修改同一份瞬态聚合，防御性拷贝会切断工作副本语义。登记见 .superpowers/sdd/2026-10-21-d032-spotbugs-land-ledger/impl-ledger.md")
  public LinkedHashMap<LandHoldingKey, LandHolding> holdings() {
    return holdings;
  }

  /** 某户在某 hex 的持有；无持有返回 {@code 0}。 */
  public LandHolding holdingOf(HexCoord hex, HouseholdId owner) {
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(owner, "owner");
    return holdings.get(new LandHoldingKey(hex, owner));
  }

  /** 用最终聚合账本替换瞬态持有；不改 {@link #componentsByHex()}，也不直接写 Map。 */
  public void replaceHoldings(Map<LandHoldingKey, LandHolding> nextHoldings) {
    Objects.requireNonNull(nextHoldings, "nextHoldings");
    TreeMap<LandHoldingKey, LandHolding> sorted = new TreeMap<>();
    for (Map.Entry<LandHoldingKey, LandHolding> entry : nextHoldings.entrySet()) {
      LandHoldingKey key = Objects.requireNonNull(entry.getKey(), "holdings 的键不得为 null");
      LandHolding holding = Objects.requireNonNull(entry.getValue(), "holdings 的值不得为 null");
      if (!key.equals(holding.key())) {
        throw new IllegalArgumentException(
            "land-holdings-key-mismatch: 键必须与 LandHolding.key() 一致: key="
                + key
                + " holding.key="
                + holding.key());
      }
      if (sorted.putIfAbsent(key, holding) != null) {
        throw new IllegalArgumentException("land-holding-duplicate: 重复 (hex, owner): " + key);
      }
    }
    // 用 LandBook 的同一套校验：每 hex 合计必须等于 Map 派生的 arable 总量。
    LandBook.from(arableSupply, sorted.values());
    holdings.clear();
    holdings.putAll(sorted);
  }

  /** 本轮工作副本的逐 hex 土地树（只读；不含后续写回）。 */
  public Map<HexCoord, HexLand> componentsByHex() {
    return componentsByHex;
  }

  /** 某 hex 的 owner→数量聚合（只读、按 owner 字符串序；未知 hex 返回空表）。 */
  public Map<String, Long> ownerQuantitiesAt(HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    TreeMap<String, Long> out = new TreeMap<>();
    for (LandHolding holding : holdings.values()) {
      if (holding.hex().equals(hex)) {
        out.put(holding.owner().value(), holding.quantityMilliMu());
      }
    }
    return Collections.unmodifiableMap(out);
  }

  /** 所有 arable component 是否都有非空 owner STRING 属性（空 arable 视为 true）。 */
  public boolean hasOwnerAttrs() {
    for (HexLand land : componentsByHex.values()) {
      LandTypeArea area = land.byType().get(LandTypeId.ARABLE);
      if (area == null) {
        continue;
      }
      for (LandComponent component : area.components()) {
        LandAttributeValue owner = component.attributes().get(LandAttributeKey.OWNER);
        if (owner == null || owner.stringValue().isBlank()) {
          return false;
        }
      }
    }
    return true;
  }

  private static String ownerOf(HexCoord hex, LandComponent component) {
    LandAttributeValue owner = component.attributes().get(LandAttributeKey.OWNER);
    if (owner == null || owner.stringValue().isBlank()) {
      throw new IllegalArgumentException(
          "land-owner-missing: arable component 缺少非空 owner STRING 属性: hex="
              + hex
              + " component="
              + component.id().value());
    }
    return owner.stringValue();
  }
}
