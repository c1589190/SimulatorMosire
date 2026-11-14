# 图片底图空白世界与自动六角网格导入

> **日期**：2026-11-14
> **分支**：`image-world-import`
> **状态**：可用原型（独立 worktree；原圆柱面 WIP 留在主工作树，未混入本分支）

## 1. 可行，已经走通

![底图自动生成六角网格实测](2026-11-14-image-world-grid-preview.png)

新增世界 **`image-world`**：

- 空地图（0 hex），但保留 7 类地形词表和当前 10 个模块空切片；
- 在 WebUI 地图编辑模式载入 PNG 底图；
- 输入“网格列数”，自动生成 pointy-top 六角网格并按每格中心取色映射到最近地形；
- 一条 `map.ImportHexGrid` 命令原子建立整张地图；
- 不需要额外连通性/环绕语义，网格是普通平面地图。

实测：1400×760 底图、86 列 ⇒ **4644 hex**；命令成功，状态显示“已生成 4644 格”，`writeEpoch` 从 1 到 2。

## 2. 新增接口

### `map.ImportHexGrid`

载荷：

```json
{
  "hexes": [
    {"q": 0, "r": 0, "terrain": "plains"},
    {"q": 1, "r": 0, "terrain": "ocean"}
  ]
}
```

语义：

- 只在**空白地图**上可执行；非空地图具名拒绝；
- 校验坐标唯一、地形在 `TerrainCatalog` 内；
- 高度取地形代表高度，地形块按同地形六邻连通分量切分；
- 通过 `MapChangeSet` 提交，不绕过 `Command → ChangeSet → Revision`。

### 新世界

```text
WorldRegistry.IMAGE_WORLD = image-world
```

空库启动：

```bash
tools/run-shaded.sh <shaded.jar> \
  --config run/image-world.json --profile editor
```

## 3. 前端操作

1. 打开地图编辑；
2. 在 PNG 底图面板选择图片；
3. 填“自动生成网格列数”（默认 86）；
4. 点“从底图生成六角网格”。

颜色映射沿用现有 PNG 取色规则：最近地形颜色 + 阈值；无法匹配/透明像素按海洋兜底。

## 4. 已知边界

- 底图只在当前编辑会话中使用，不随节点持久化；生成后的地形/网格已写入 world store。刷新后底图需要重新载入，但地图数据保留。
- 网格是矩形自动裁取，不额外生成区域/城市/单位。
- 目前没有“清空当前网格再重生成”；重新导入请新建一个空 world/store。
- 不涉及圆柱面 wrap（原 WIP 保留在主工作树，未合入本分支）。

## 5. 验证

- `ImportHexGridHandlerTest`：3 tests PASS；
- `McpCoverageTest + SimosToolsTest`：66 tests PASS（补齐新 handler 的 catalog/命令计数）；
- 前端门禁：**430/430 PASS**；
- 真实 WebUI：空白 world → 上传 PNG → 生成 4644 格，成功；
- full gate：功能改动前/中多次被打断，本分支尚未拿到一次完整的 full gate 结论，因此不写成 full gate PASS。

## 6. 本机产物

```text
run/image-world/                 # 空白世界 store（测试时生成过网格；交付前重置）
run/image-world-server.log
run/image-world-grid.png         # 生成后的 WebUI 截图
```
