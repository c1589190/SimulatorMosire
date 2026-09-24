package io.mosire.simos.app.render;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 字符图：同一块视野的<b>文本形态</b>（每格一字符、错行表示六角排布）——给"没有视觉能力的模型"看的回落形态。
 *
 * <p>语义对齐 GSimulator 的字符查看器（{@code gsimap_render_text}）：中心 + 半径、地形一字符一格、末尾图例。 与 {@link
 * MapImageRenderer} <b>共用同一份取数口径</b>（同一块视野、同一个有效位置规则），差别只在呈现。
 *
 * <p>★ 覆盖顺序：单位 &gt; 城市 &gt; 地形——单位是"最会被问到的东西"（"我的军队在哪"），地形是背景。
 */
public final class MapTextRenderer {

  /** 地形键 → 字符（与 simos 的 7 个地形键一一对应；未知键给 {@code ?}，不静默当平原）。 */
  private static final Map<String, Character> TERRAIN_CHARS =
      Map.of(
          "ocean", '~',
          "plains", '.',
          "desert", ':',
          "low_hills", 'n',
          "plateau", '=',
          "mountains", '^',
          "plateau_mountains", 'M');

  private static final char CITY_CHAR = 'C';
  private static final char UNIT_CHAR = 'A';
  private static final char UNKNOWN_CHAR = '?';

  private MapTextRenderer() {}

  /**
   * 渲染一块视野的字符图。
   *
   * @return 多行文本（首行标题、末尾图例）；半径越大行越宽——调用方负责别把它塞进过窄的上下文
   */
  public static String render(
      GameMap map,
      SocialData social,
      UnitState units,
      SimosTimestamp at,
      HexCoord center,
      int radius) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(center, "center");
    if (radius < RenderRequest.MIN_RADIUS || radius > RenderRequest.MAX_RADIUS) {
      throw new IllegalArgumentException(
          "radius 必须在 "
              + RenderRequest.MIN_RADIUS
              + ".."
              + RenderRequest.MAX_RADIUS
              + ": "
              + radius);
    }

    Set<HexCoord> visible = new LinkedHashSet<>(MapProjection.hexesWithin(center, radius));
    Map<HexCoord, Character> overlays = overlayIndex(social, units, at, visible);

    StringBuilder out = new StringBuilder();
    out.append("世界视图（字符图） 中心(")
        .append(center.q())
        .append(',')
        .append(center.r())
        .append(") 半径 ")
        .append(radius)
        .append('\n');

    Set<String> terrainKeysUsed = new LinkedHashSet<>();
    boolean citySeen = false;
    boolean unitSeen = false;
    boolean unknownSeen = false;
    for (int r = center.r() - radius; r <= center.r() + radius; r++) {
      // 错行：奇偶行各缩进半格（pointy-top 六角在文本里的最近似）
      out.append(Math.floorMod(r, 2) == 0 ? "  " : " ");
      boolean first = true;
      for (int q = center.q() - radius; q <= center.q() + radius; q++) {
        HexCoord coord = new HexCoord(q, r);
        if (!visible.contains(coord) || !map.hexes().containsKey(coord)) {
          if (!first) {
            out.append(' ');
          }
          out.append(' ');
          first = false;
          continue;
        }
        String terrainKey = map.terrainAt(coord);
        if (terrainKey != null) {
          terrainKeysUsed.add(terrainKey);
        } else {
          unknownSeen = true;
        }
        Character overlay = overlays.get(coord);
        if (overlay != null) {
          citySeen |= overlay == CITY_CHAR;
          unitSeen |= overlay == UNIT_CHAR;
        }
        char cell = cellChar(map, overlays, coord);
        if (cell == UNKNOWN_CHAR) {
          unknownSeen = true;
        }
        if (!first) {
          out.append(' ');
        }
        out.append(cell);
        first = false;
      }
      out.append('\n');
    }

    out.append("图例: ");
    boolean firstEntry = true;
    for (Map.Entry<String, Character> entry : TERRAIN_CHARS.entrySet()) {
      if (!terrainKeysUsed.contains(entry.getKey())) {
        continue;
      }
      if (!firstEntry) {
        out.append(" · ");
      }
      out.append(entry.getValue()).append(' ').append(terrainName(map, entry.getKey()));
      firstEntry = false;
    }
    if (citySeen) {
      out.append(firstEntry ? "" : " · ").append(CITY_CHAR).append(" 城市");
      firstEntry = false;
    }
    if (unitSeen) {
      out.append(firstEntry ? "" : " · ").append(UNIT_CHAR).append(" 单位");
      firstEntry = false;
    }
    if (unknownSeen) {
      out.append(firstEntry ? "" : " · ").append(UNKNOWN_CHAR).append(" 未知地形");
    }
    return out.toString();
  }

  /** 视野内每格的覆盖字符（单位 &gt; 城市）。 */
  private static Map<HexCoord, Character> overlayIndex(
      SocialData social, UnitState units, SimosTimestamp at, Set<HexCoord> visible) {
    Map<HexCoord, Character> out = new LinkedHashMap<>();
    for (SocialCity city : social.cities().values()) {
      if (visible.contains(city.at())) {
        out.put(city.at(), CITY_CHAR);
      }
    }
    for (Unit unit : units.units().values()) {
      HexCoord here = units.effectivePosition(unit.id(), at).orElse(null);
      if (here != null && visible.contains(here)) {
        out.put(here, UNIT_CHAR);
      }
    }
    return out;
  }

  private static char cellChar(GameMap map, Map<HexCoord, Character> overlays, HexCoord coord) {
    Character overlay = overlays.get(coord);
    if (overlay != null) {
      return overlay;
    }
    String key = map.terrainAt(coord);
    Character terrain = key == null ? null : TERRAIN_CHARS.get(key);
    return terrain == null ? UNKNOWN_CHAR : terrain;
  }

  private static String terrainName(GameMap map, String key) {
    TerrainType type = map.terrainTypes().get(key);
    return type == null || type.name() == null || type.name().isBlank() ? key : type.name();
  }
}
