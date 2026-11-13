# WebUI 真实浏览器实测与修复（D-036 前端优化）

> **日期**：2026-11-13
> **性质**：实测报告（历史证据；当前状态以 [`docs/agents/STATUS.md`](../agents/STATUS.md) 为准）
> **环境**：本机 `small-world` demo，`http://127.0.0.1:5811/`，editor profile，Chrome 156 headless + CDP。
> **视口**：1600×857、1280×800、1024×768；截图存本机 `run/ui-*.png`（`run/` 不入库）。

## 1. 实测路径

1. 启动 `small-world`（19 hex / 2 区域 / 2 GOV / tick 0 / main rev 1），用 Chrome headless 打开。
2. 等待世界总览、时间轴、地图加载完成（首帧截图容易拍到空白，需等约 6s）。
3. 逐个点击：常规、地图编辑、单位编辑、战役、文档面板。
4. 在文档面板搜索“小世界”，点击结果，确认地址/标题/正文回填。
5. 地图编辑里滚动到 PNG 面板，载入 1200×1200 纯色 PNG，点“按格心取色预览”，确认 overlay 与计数。
6. 切回常规，确认 PNG overlay 不再遮挡世界视图。
7. 1280/1024 视口检查顶栏、模式栏、三栏布局。

## 2. 实测发现并修复的 bug

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | `/webui/modules/map/map-png.js` 404，PNG 功能不可用 | `StaticHandler` 把 `/webui/modules/...` 又拼了一次 `/webui/` | 路径已以 `webui/` 开头时去掉重复前缀；新增 `WebuiAssetsTest.staticHandlerDoesNotDoublePrefixWebuiRoot` |
| 2 | 顶栏“区域名”被压成竖排黑块 | `.region-name-toggle` 可被 flex 压缩，文字换行 | `flex: 0 0 auto; white-space: nowrap` |
| 3 | 1280/1024 顶栏标题竖排、当前模式 pill 重复占位 | 顶栏无换行规则，`mode-current` 与按钮重复 | 顶栏 `flex-wrap`、h1/shell-state nowrap；≤1450px 隐藏 mode-current；≤1100px 模式栏/动作栏换行 |
| 4 | 文档搜索结果被压成一条、标题截断 | `.docs-results` 作为 flex 子项被压缩 | results/editor `flex: 0 0 auto` |
| 5 | 战役模式右栏只剩空框 | 右栏 `data-modes` 含 battle 但没有 battle 内容 | 右栏移除 battle；战役信息留在左栏 |
| 6 | 切回常规后 PNG 绿色 overlay 仍遮挡地图 | overlay 不按模式门控 | `paintPngOverlay` 仅在 `map-edit` 绘制 |
| 7 | 调整 offset/scale/threshold 后“应用为地形”仍用旧计划 | 取色计划未失效 | 参数变化清空 `lastPlan`、禁用应用、提示重新预览 |
| 8 | editor profile 下经济/审批 404 显示“未载入/未接入” | 前端把 404 当普通失败 | 经济/审批 404 显示“editor profile 未挂载…”；通知 404 静默隐藏并停轮询 |
| 9 | 新 2+1 模式下区域名完全不可见 | `REGION_NAME_MODES` 仍只含旧 `region/region-edit` | 加入 `map-edit`（区域编辑已并入）；常规仍不显示 |
| 10 | 小世界与首都省同质心，区域名叠字 | 两个 region 的 `label` 都是 `(0,0)` | `regionNamePlan` 同点标签居中纵向错开；最小字号 8→13 |
| 11 | 战役 id 为空点“打开战役文档”无反馈 | status 写在隐藏的文档面板 | 战役面板新增 `#battle-status` 就地提示 |

## 3. 验证

```text
node simos-app/src/test/js/run-gate.cjs
[frontend-gate] OK tests=425 pass=425 fail=0

tools/mvn-lock.sh -q -pl simos-app -am -Dtest=WebuiAssetsTest test
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
```

新增/调整：

- `frontend-optimization.test.cjs`：6 条（区域名不竖排、子工具不竖排、窄屏规则、PNG 模式门控、editor profile 文案、PNG 旧计划失效）；
- `webui-fix2.test.cjs`：区域名在 `map-edit` 可见；最小字号 13；同质心标签错开；
- `WebuiAssetsTest`：`/webui/modules/...` 单前缀；
- 前端门禁下界：`MIN_TESTS/MIN_ASSERTIONS = 424`（实际 425）。

## 4. 未做 / 已知边界

- PNG 文件选择仍是原生 `<input type=file>` 外观（已做宽度/颜色约束，未做完全自定义按钮）；
- 1024px 以下未做专门的移动端布局；当前 1024×768 可用但三栏较挤；
- 本文未重跑 full gate；本轮为前端/静态资源修复，跑的是前端门禁 + `WebuiAssetsTest`。上一轮 D-036 full gate 为 PASS（313s）。
