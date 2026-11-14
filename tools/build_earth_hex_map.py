#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""build_earth_hex_map.py —— Natural Earth 陆地 → 等面积球面投影六角地图。

用法（在仓根）：
    python3 tools/build_earth_hex_map.py \
      run/earth-globe/ne_110m_land.geojson \
      run/earth-globe/earth-map.json \
      --samples run/earth-globe/earth-samples.json \
      --preview run/earth-globe/earth-preview.png

设计：
  * 使用 **Equal Earth**（Šavrič et al. 2018）等面积投影；六角格在投影平面上规则排布，
    因此每个格子在球面上的面积相同，海陆比例不会被普通矩形经纬图拉伸。
  * 六角格 geometry 与 simos 的 pointy-top 轴向坐标一致：
        x = size * sqrt(3) * (q + r/2)
        y = size * 1.5 * r
  * 只保留中心落在 Equal Earth 投影轮廓内的格；地图外缘因此是投影轮廓上的锯齿边。
  * 输出两份 JSON：
      1) GSim root full map（给 tools/gsimap_import.py）；
      2) regen samples（importer 的 --regen-samples，高度/地形按地球近似带生成）。
    源 GeoJSON 只读；不联网、不写第三方依赖。
"""

import argparse
import json
import math
from pathlib import Path

# Equal Earth projection constants (PROJ / Šavrič et al. 2018).
A1 = 1.340264
A2 = -0.081106
A3 = 0.000893
A4 = 0.003796
SQRT3 = math.sqrt(3.0)
HEX_AREA_FACTOR = 3.0 * SQRT3 / 2.0


def project(lon_deg, lat_deg):
    lam = math.radians(lon_deg)
    phi = math.radians(lat_deg)
    theta = math.asin(SQRT3 / 2.0 * math.sin(phi))
    t2 = theta * theta
    poly = A1 + 3.0 * A2 * t2 + 7.0 * A3 * t2 * t2 + 9.0 * A4 * t2 * t2 * t2
    x = 2.0 * SQRT3 * lam * math.cos(theta) / (3.0 * poly)
    y = theta * poly
    return x, y


def inverse_project(x, y):
    """Equal Earth inverse; fixed-point solve for theta, then recover lon/lat."""
    theta = y / A1
    for _ in range(12):
        t2 = theta * theta
        poly = A1 + 3.0 * A2 * t2 + 7.0 * A3 * t2 * t2 + 9.0 * A4 * t2 * t2 * t2
        theta = y / poly
    t2 = theta * theta
    poly = A1 + 3.0 * A2 * t2 + 7.0 * A3 * t2 * t2 + 9.0 * A4 * t2 * t2 * t2
    lat = math.degrees(math.asin(max(-1.0, min(1.0, 2.0 / SQRT3 * math.sin(theta)))))
    lon = math.degrees(x * 3.0 * poly / (2.0 * SQRT3 * math.cos(theta)))
    return lon, lat




# Lambert cylindrical equal-area, standard parallel 30°: area is preserved and
# the map is a true rectangle whose left/right edges can be identified.
CEA_PHI0 = math.radians(30.0)
CEA_COS0 = math.cos(CEA_PHI0)
CEA_XMAX = math.pi * CEA_COS0
CEA_YMAX = 1.0 / CEA_COS0


def cylindrical_project(lon_deg, lat_deg):
    lam = math.radians(lon_deg)
    phi = math.radians(lat_deg)
    return lam * CEA_COS0, math.sin(phi) / CEA_COS0


def cylindrical_inverse(x, y):
    lon = math.degrees(x / CEA_COS0)
    lat = math.degrees(math.asin(max(-1.0, min(1.0, y * CEA_COS0))))
    return lon, lat


def projected_area():
    """Numerically integrate the projection area on a 1-degree grid (stdlib only)."""
    total = 0.0
    n = 180
    for i in range(n):
        lat1 = -90.0 + 180.0 * i / n
        lat2 = -90.0 + 180.0 * (i + 1) / n
        for j in range(2 * n):
            lon1 = -180.0 + 360.0 * j / (2 * n)
            lon2 = -180.0 + 360.0 * (j + 1) / (2 * n)
            pts = [
                project(lon1, lat1),
                project(lon2, lat1),
                project(lon2, lat2),
                project(lon1, lat2),
            ]
            s = 0.0
            for k in range(4):
                x1, y1 = pts[k]
                x2, y2 = pts[(k + 1) % 4]
                s += x1 * y2 - x2 * y1
            total += abs(s) * 0.5
    return total


def unwrap_ring(ring):
    """Unwrap longitudes across the antimeridian so ray casting sees a continuous ring."""
    if not ring:
        return ring
    out = [(float(ring[0][0]), float(ring[0][1]))]
    prev = out[0][0]
    for lon, lat in ring[1:]:
        lon = float(lon)
        while lon - prev > 180.0:
            lon -= 360.0
        while lon - prev < -180.0:
            lon += 360.0
        out.append((lon, float(lat)))
        prev = lon
    return out


def ring_bbox(ring):
    xs = [p[0] for p in ring]
    ys = [p[1] for p in ring]
    return min(xs), min(ys), max(xs), max(ys)


def point_in_ring(lon, lat, ring, bbox):
    xmin, ymin, xmax, ymax = bbox
    if lon < xmin or lon > xmax or lat < ymin or lat > ymax:
        return False
    inside = False
    j = len(ring) - 1
    for i in range(len(ring)):
        xi, yi = ring[i]
        xj, yj = ring[j]
        if ((yi > lat) != (yj > lat)) and (
            lon < (xj - xi) * (lat - yi) / (yj - yi) + xi
        ):
            inside = not inside
        j = i
    return inside


class LandMask:
    """Natural Earth land polygons with antimeridian unwrapping and bbox culling."""

    def __init__(self, geojson_path):
        data = json.loads(Path(geojson_path).read_text(encoding="utf-8"))
        if data.get("type") != "FeatureCollection":
            raise ValueError("GeoJSON must be a FeatureCollection")
        self.polygons = []
        for feature in data.get("features", []):
            geom = feature.get("geometry") or {}
            if geom.get("type") == "Polygon":
                polygons = [geom.get("coordinates") or []]
            elif geom.get("type") == "MultiPolygon":
                polygons = geom.get("coordinates") or []
            else:
                continue
            for rings in polygons:
                if not rings:
                    continue
                outer = unwrap_ring(rings[0])
                holes = [unwrap_ring(r) for r in rings[1:]]
                self.polygons.append(
                    {
                        "outer": outer,
                        "outer_bbox": ring_bbox(outer),
                        "holes": [(r, ring_bbox(r)) for r in holes],
                    }
                )
        if not self.polygons:
            raise ValueError("GeoJSON contains no land polygons")

    def contains(self, lon, lat):
        # Test the canonical longitude plus the two antimeridian copies.
        for shift in (-360.0, 0.0, 360.0):
            x = lon + shift
            for poly in self.polygons:
                if not point_in_ring(x, lat, poly["outer"], poly["outer_bbox"]):
                    continue
                if any(point_in_ring(x, lat, ring, bbox) for ring, bbox in poly["holes"]):
                    continue
                return True
        return False


# Approximate visual geography.  These are intentionally coarse: the *shape* and
# *land ratio* come from Natural Earth; terrain only colors the continents.
MOUNTAIN_BOXES = (
    (65.0, 25.0, 105.0, 40.0),    # Himalaya
    (-80.0, -55.0, -64.0, 12.0),  # Andes
    (-130.0, 30.0, -100.0, 60.0), # Rockies
    (5.0, 43.0, 20.0, 48.0),      # Alps
    (55.0, 50.0, 65.0, 70.0),     # Urals
    (145.0, -38.0, 152.0, -15.0), # Great Dividing Range
    (35.0, 5.0, 42.0, 15.0),      # Ethiopian Highlands
    (-10.0, 30.0, 10.0, 37.0),    # Atlas
)
PLATEAU_BOXES = (
    (75.0, 28.0, 105.0, 38.0),    # Tibetan Plateau
    (-72.0, -25.0, -62.0, -10.0), # Altiplano
)
DESERT_BOXES = (
    (-17.0, 15.0, 40.0, 32.0),    # Sahara
    (35.0, 12.0, 60.0, 32.0),     # Arabian
    (75.0, 35.0, 115.0, 50.0),    # Gobi / Taklamakan
    (115.0, -30.0, 145.0, -18.0), # Australian interior
    (12.0, -30.0, 25.0, -18.0),   # Kalahari / Namib
    (-75.0, -30.0, -65.0, -18.0), # Atacama
    (-120.0, 30.0, -100.0, 40.0), # SW US / northern Mexico
)
HILL_BOXES = (
    (-85.0, 30.0, -70.0, 45.0),   # Appalachians
    (100.0, 20.0, 120.0, 32.0),   # southern China hills
    (20.0, 45.0, 40.0, 55.0),     # eastern Europe uplands
    (-50.0, -25.0, -35.0, -5.0),  # Brazilian highlands
    (130.0, -35.0, 150.0, -20.0), # eastern Australia
)


def in_box(lon, lat, box):
    lon1, lat1, lon2, lat2 = box
    return lon1 <= lon <= lon2 and lat1 <= lat <= lat2


def terrain_for(lon, lat):
    if any(in_box(lon, lat, box) for box in PLATEAU_BOXES):
        return "plateau"
    if any(in_box(lon, lat, box) for box in MOUNTAIN_BOXES):
        return "mountains"
    if any(in_box(lon, lat, box) for box in DESERT_BOXES):
        return "desert"
    if any(in_box(lon, lat, box) for box in HILL_BOXES):
        return "low_hills"
    return "plains"


CONTINENT_BOXES = (
    ("africa", -20.0, -35.0, 55.0, 37.0, "#C8A45E"),
    ("europe", -25.0, 35.0, 60.0, 72.0, "#7EA6D9"),
    ("asia", 25.0, -10.0, 180.0, 80.0, "#D8B46A"),
    ("north-america", -170.0, 10.0, -50.0, 85.0, "#9BC77A"),
    ("south-america", -85.0, -60.0, -30.0, 15.0, "#D38B67"),
    ("australia", 110.0, -50.0, 180.0, 0.0, "#C99A63"),
    ("antarctica", -180.0, -90.0, 180.0, -60.0, "#D8E2EA"),
)


def continent_for(lon, lat):
    if lat < -60.0:
        return "antarctica"
    # Africa first: it overlaps the Asian box at its eastern edge.
    if -20.0 <= lon <= 55.0 and -35.0 <= lat <= 37.0:
        return "africa"
    if -25.0 <= lon <= 60.0 and 35.0 <= lat <= 72.0:
        return "europe"
    if 25.0 <= lon <= 180.0 and -10.0 <= lat <= 80.0:
        return "asia"
    if -170.0 <= lon <= -50.0 and 10.0 <= lat <= 85.0:
        return "north-america"
    if -85.0 <= lon <= -30.0 and -60.0 <= lat <= 15.0:
        return "south-america"
    if 110.0 <= lon <= 180.0 and -50.0 <= lat <= 0.0:
        return "australia"
    return "other"


SAMPLE_TERRAIN = {
    "ocean": "water",
    "plains": "lowland",
    "desert": "desert",
    "low_hills": "hills",
    "mountains": "mountain",
    "plateau": "plains",
}
HEIGHT_BY_TERRAIN = {
    "ocean": 0.12,
    "plains": 0.37,
    "desert": 0.50,
    "low_hills": 0.60,
    "mountains": 0.73,
    "plateau": 0.82,
}
COLORS = {
    "ocean": "#1F5FA0",
    "plains": "#9CCB5B",
    "desert": "#E7C86E",
    "low_hills": "#A8B36A",
    "mountains": "#7A7F85",
    "plateau": "#B99B6B",
}


def build_hexes(target_hexes, land_mask):
    # Equal Earth's unit-sphere image has area ~11.37; hex area = 3*sqrt(3)/2 * size^2.
    size = math.sqrt(projected_area() / (target_hexes * HEX_AREA_FACTOR))
    xmax, _ = project(180.0, 0.0)
    _, ymax = project(0.0, 90.0)
    rmax = int(math.ceil(ymax / (1.5 * size))) + 1
    rows = []
    for r in range(-rmax, rmax + 1):
        # 画布/世界 y 向下；Equal Earth 的 y 向上。轴向 r 取负，让北半球显示在上方。
        y = 1.5 * size * r
        proj_y = -y
        half = (SQRT3 / 2.0) * r * size
        qlo = int(math.floor((-xmax - half) / (SQRT3 * size))) - 1
        qhi = int(math.ceil((xmax - half) / (SQRT3 * size))) + 1
        row = []
        for q in range(qlo, qhi + 1):
            x = SQRT3 * size * (q + r / 2.0)
            lon, lat = inverse_project(x, proj_y)
            if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
                continue
            # Reject points outside the projected globe domain (rectangle corners).
            px, py = project(lon, lat)
            if abs(px - x) > 1e-6 or abs(py - proj_y) > 1e-6:
                continue
            land = land_mask.contains(lon, lat)
            terrain = terrain_for(lon, lat) if land else "ocean"
            row.append(
                {
                    "q": q,
                    "r": r,
                    "x": x,
                    "y": y,
                    "lon": lon,
                    "lat": lat,
                    "land": land,
                    "terrain": terrain,
                    "continent": continent_for(lon, lat) if land else None,
                }
            )
        rows.extend(row)
    return rows, size, xmax, ymax


def build_cylindrical_hexes(target_hexes, land_mask):
    """Rule-of-thumb rectangular cylinder grid; all rows have the same q width."""
    target = max(100, int(target_hexes))
    size = 0.01
    for _ in range(32):
        q_width = max(3, int(round(2.0 * CEA_XMAX / (SQRT3 * size))))
        r_max = int(math.ceil(CEA_YMAX / (1.5 * size)))
        count = q_width * (2 * r_max + 1)
        size *= math.sqrt(count / target)
    q_width = max(3, int(round(2.0 * CEA_XMAX / (SQRT3 * size))))
    r_max = int(math.ceil(CEA_YMAX / (1.5 * size)))
    q_min = -(q_width // 2)
    rows = []
    for r in range(-r_max, r_max + 1):
        # World y is down; inverse wants projection y up.
        world_y = 1.5 * size * r
        proj_y = -world_y
        # pointy-top 行错位：行内 q 起点随 r 平移，保证每行屏幕 x 覆盖同一矩形。
        row_q_min = q_min - (r // 2)
        for q in range(row_q_min, row_q_min + q_width):
            x = SQRT3 * size * (q + r / 2.0)
            lon, lat = cylindrical_inverse(x, proj_y)
            if not (-90.0 <= lat <= 90.0 and -180.0 <= lon <= 180.0):
                continue
            # Keep the seam band ocean: the cylinder seam is a real antimeridian
            # water channel, so no land region/terrain block has to straddle the seam.
            land = land_mask.contains(lon, lat) and abs(lon) < 179.0
            terrain = terrain_for(lon, lat) if land else "ocean"
            rows.append(
                {
                    "q": q,
                    "r": r,
                    "x": x,
                    "y": world_y,
                    "lon": lon,
                    "lat": lat,
                    "land": land,
                    "terrain": terrain,
                    "continent": continent_for(lon, lat) if land else None,
                }
            )
    return rows, size, CEA_XMAX, CEA_YMAX, {
        "wrapX": True,
        "qMin": q_min,
        "qWidth": q_width,
    }


def make_source_map(cells, wrap=None):
    hexes = {}
    provinces = {}
    by_continent = {}
    for cell in cells:
        key = "{}_{}".format(cell["q"], cell["r"])
        hexes[key] = {"terrain": "water" if not cell["land"] else "lowland"}
        if cell["land"]:
            by_continent.setdefault(cell["continent"], []).append(key)
    for continent, keys in sorted(by_continent.items()):
        box = next((b for b in CONTINENT_BOXES if b[0] == continent), None)
        color = box[5] if box else "#B0B0B0"
        provinces[continent] = {
            "hexes": keys,
            "color": color,
            "tag": "continent",
            "description": "Natural Earth land sampled on an equal-area hex grid.",
        }
    out = {
        "hexOrientation": False,
        "hexes": hexes,
        "provinces": provinces,
        "cities": {},
        "edges": {},
        "pathwayGroups": {},
    }
    if wrap is not None:
        out["simosWrap"] = wrap
    return out


def make_samples(cells):
    hexes = {}
    for cell in cells:
        terrain = cell["terrain"]
        hexes["{}_{}".format(cell["q"], cell["r"])] = {
            "terrain": SAMPLE_TERRAIN[terrain],
            "height": HEIGHT_BY_TERRAIN[terrain],
        }
    land = sum(1 for c in cells if c["land"])
    return {
        "seed": 20261113,
        "radius": max(max(abs(c["q"]), abs(c["r"]), abs(c["q"] + c["r"])) for c in cells),
        "landRatio": land / len(cells),
        "hexes": hexes,
    }


def render_preview(cells, size, xmax, ymax, out_path):
    from PIL import Image, ImageDraw

    width, height = 1400, 760
    pad = 24
    sx = (width - 2 * pad) / (2 * xmax)
    sy = (height - 2 * pad) / (2 * ymax)
    scale = min(sx, sy)
    ox = width / 2.0
    oy = height / 2.0
    image = Image.new("RGB", (width, height), "#0D1117")
    draw = ImageDraw.Draw(image)
    for cell in cells:
        cx = ox + cell["x"] * scale
        cy = oy + cell["y"] * scale
        radius = size * scale
        corners = []
        for i in range(6):
            angle = math.radians(60 * i - 30)
            corners.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
        draw.polygon(corners, fill=COLORS[cell["terrain"]], outline=(8, 12, 18))
    image.save(out_path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("geojson", help="Natural Earth land GeoJSON (e.g. ne_110m_land.geojson)")
    parser.add_argument("map_json", help="output GSim root full map JSON")
    parser.add_argument("--samples", help="output regen samples JSON")
    parser.add_argument("--preview", help="optional PNG preview")
    parser.add_argument("--target-hexes", type=int, default=4000)
    parser.add_argument(
        "--projection",
        choices=("equal-earth", "cylindrical"),
        default="equal-earth",
        help="equal-earth = oval flat map; cylindrical = rectangular left/right-wrap map",
    )
    args = parser.parse_args()

    if args.target_hexes < 100:
        raise SystemExit("--target-hexes must be >= 100")
    land = LandMask(args.geojson)
    if args.projection == "cylindrical":
        cells, size, xmax, ymax, wrap = build_cylindrical_hexes(args.target_hexes, land)
    else:
        cells, size, xmax, ymax = build_hexes(args.target_hexes, land)
        wrap = None
    if not cells:
        raise SystemExit("no hexes generated")
    source = make_source_map(cells, wrap)
    samples = make_samples(cells)
    Path(args.map_json).parent.mkdir(parents=True, exist_ok=True)
    Path(args.map_json).write_text(
        json.dumps(source, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    if args.samples:
        Path(args.samples).write_text(
            json.dumps(samples, ensure_ascii=False, separators=(",", ":")) + "\n",
            encoding="utf-8",
        )
    if args.preview:
        Path(args.preview).parent.mkdir(parents=True, exist_ok=True)
        render_preview(cells, size, xmax, ymax, args.preview)

    land_count = sum(1 for c in cells if c["land"])
    print(
        "projection={} hexes={} land={} ({:.2f}%) ocean={} ({:.2f}%) grid_size={:.6f} extents=({:.4f},{:.4f})".format(
            args.projection,
            len(cells),
            land_count,
            100.0 * land_count / len(cells),
            len(cells) - land_count,
            100.0 * (len(cells) - land_count) / len(cells),
            size,
            xmax,
            ymax,
        )
    )
    for terrain in COLORS:
        count = sum(1 for c in cells if c["terrain"] == terrain)
        print("  {:10s} {:5d}".format(terrain, count))
    print("map: {}".format(args.map_json))
    if args.samples:
        print("samples: {}".format(args.samples))
    if args.preview:
        print("preview: {}".format(args.preview))


if __name__ == "__main__":
    main()
