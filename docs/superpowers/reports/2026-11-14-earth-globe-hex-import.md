# 地球比例六角地图：圆柱面 → simos 导入试验（5×）

> **日期**：2026-11-14
> **状态**：圆柱面拓扑试验报告（不是生产世界登记；生成物在 `run/earth-globe/`，已被 `.gitignore` 忽略）
> **分支**：`feature/d036-node-editor`

![ Lambert 圆柱等面积六角地球预览](2026-11-14-earth-globe-preview.png)

## 1. 结论

可以做，而且已经从“平面椭圆图”推进到**左右周期闭合的圆柱面六角图**：

```text
Natural Earth 110m land
  → Lambert 圆柱等面积投影（标准纬线 30°）
  → pointy-top 六角格（每格球面等面积）
  → map spec: MapWrap(wrapX=true, qMin, qWidth)
  → GSim root full map + regen samples
  → tools/gsimap_import.py --regen-samples
  → simos store / WebUI
```

当前结果：

| 项 | 结果 |
|---|---|
| 目标粒度 | 5×（相对最初 3,965 格） |
| 实际格数 | **19,701** |
| 陆地 / 海洋 | **5,732 (29.10%) / 13,969 (70.91%)** |
| 地形 | ocean 13,969 / plains 3,612 / desert 840 / low_hills 298 / mountains 786 / plateau 196 |
| 区域 | 8 个大陆分组 |
| 地形块 | 94 块，覆盖全部 19,701 格 |
| map spec | `wrapX=true`, `qMin=-99`, `qWidth=199` |

陆地比例已经接近真实地球（约 29.2%）。地图左右边缘是同一周期；接缝经过太平洋，并在接缝带保留一条海洋通道，避免大陆区域跨接缝断裂。

## 2. 圆柱拓扑落地了什么

不是只改生成器：这次把“左右联通”接进了地图域和路径搜索。

### 2.1 数据/地图域

- `MapWrap`：`wrapX / qMin / qWidth`，按 pointy-top 的行偏移计算每行周期起点；
- `GenerationSpec.wrap`：随 map snapshot 持久化，旧档缺字段时回退 `MapWrap.none()`；
- `GameMap.neighbors(...)` / `GameMap.distance(...)`：跨接缝邻格与圆柱最短距离；
- `TerrainBlocks.split(terrain, wrap)`：地形连通块跨接缝合并；
- `TerrainOperations` / `RegionRandomizer` / `MapGenerator`：地形编辑与随机化都传 wrap。

### 2.2 寻路/单位移动

- `PathFinder` 的邻居展开与 A* 启发都走 `GameMap.neighbors/distance`；
- `TerrainMovementCost` 的相邻性判断也走圆柱距离；
- 因此从 `q=0` 到 `q=4` 在 5 列测试图上可以一步跨接缝（新增单测）。

### 2.3 前端

- `/api/map/overview` 下发：

```json
"wrap": {"wrapX": true, "qMin": -99, "qWidth": 199}
```

- renderer 接收 `setWrap`，地形块按 `±qWidth` 生成左右副本；
- 拖图/缩放后 `tx` 按周期折回；
- 点选/刷地形用 `wrapWorldX` 规范化跨接缝 x，再落到 canonical hex；
- 地图编辑刷接缝时，服务端 `map.SetTerrain` 的地形块切分也跨接缝。

## 3. 实际导入与编辑验证

导入：

```text
hex 总数 : 19701
地形块   : 94 块 / 覆盖 19701 格
结果     : OK
modules  : actor, army, culture, economy, ga, gov, map, production, social, unit
```

启动后：

```text
GET /api/map/overview
mapId=earth-globe-cyl  hexCount=19701
wrap={"wrapX":true,"qMin":-99,"qWidth":199}
```

真实 Chrome/WebUI：

- 地图以矩形圆柱面加载；
- 在接缝格 `q=99, r=0` 用 `low_hills` 右键刷成功：

```text
status = 已改 1 格为 low_hills（一条命令，已写入当前节点）
revision = 1
writeEpoch 0 → 1
GET /api/map/hex?q=99&r=0 -> low_hills
```

- 把视图平移到接缝外侧时，地形块的左右副本连续出现。

## 4. 导入兼容修复（当前 HEAD 必需）

`tools/gsimap_import.py` 原本按旧世界线格式写：

1. 当前 `HexCell` 是 `{height, land}`，但重建模式只写 `{height}` ⇒ `HexCell.land 不得为 null`；
2. 空切片仍用旧 namespace `sd`、旧 `EconomyData.classFirst`、旧 `SocialData` 缺 `provisioning`；
3. 当前 Shell 有 10 个 codec，旧导入器只补 8 个空切片。

已修：

- 重建模式逐格写 `"land": {"byType": {}}`；
- 空切片使用当前 10 模块格式（`ga` 取代 `sd`，economy/social 当前格式）；
- source map 的 `simosWrap` 写进 `GenerationSpec.wrap`。

## 5. 复现命令

```bash
curl -L -sS \
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_land.geojson \
  -o run/earth-globe/ne_110m_land.geojson

python3 tools/build_earth_hex_map.py \
  run/earth-globe/ne_110m_land.geojson \
  run/earth-globe/earth-cyl-map.json \
  --samples run/earth-globe/earth-cyl-samples.json \
  --preview run/earth-globe/earth-cyl-preview.png \
  --projection cylindrical \
  --target-hexes 20000

python3 tools/gsimap_import.py \
  run/earth-globe/earth-cyl-map.json \
  run/earth-globe/import-cyl \
  --regen-samples run/earth-globe/earth-cyl-samples.json \
  --report-json run/earth-globe/report-cyl.json

tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --config run/earth-globe/shell-cyl.json --profile editor
```

本机测试入口：**http://127.0.0.1:5823/**（当前 demo shell）。

## 6. 仍不是“完整球体”

现在是**圆柱面**：左右闭合，南北极仍是上下边界。要变成真正的球体，需要 Goldberg/二十面体六角化（12 个五边形缺陷），并重做五边形邻接、区域边界和渲染。圆柱面适合先把“左右世界连通”落地；球面是下一层。

另外，接缝带被保持为海洋通道，所以本次没有让大陆区域跨接缝；如果以后要把接缝穿过大陆，`RegionBoundary` 也需要跨接缝环语义。

## 7. 本机生成物

```text
run/earth-globe/ne_110m_land.geojson
run/earth-globe/earth-cyl-map.json
run/earth-globe/earth-cyl-samples.json
run/earth-globe/earth-cyl-preview.png
run/earth-globe/import-cyl/            # simos store
run/earth-globe/report-cyl.json
run/earth-globe/shell-cyl.json
```

以上都在 `run/` 下，不进入版本库。
