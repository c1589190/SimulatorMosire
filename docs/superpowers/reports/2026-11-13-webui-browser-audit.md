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
| 12 | **地图编辑刷地形/连边/套索在有 GOV/城市/交战图标的格上无反应** | 编辑手势复用通用 `pickAt`，单位/城市/⚔ 优先于 hex ⇒ brushList 为空、命令不发出 | 编辑手势改用纯 hex `hexAt`；选择手势仍保留单位/城市优先 |
| 13 | **地图编辑命令返回成功，但地图/读口仍是旧地形** | 同 tick 原地更新 revision 不变；QueryService 按 revision 缓存、前端 target/共享数据缓存均误判“没变化” | 新增 world `writeEpoch`：Timeline 写成功递增；`/api/state` 下发；QueryService 按 epoch 清缓存；前端 target/withTarget/地图/事件/右栏缓存键携带 epoch |
| 14 | 成功状态写“head 已前进”，但 revision 明明没变 | 旧文案只适配普通 revision 追加；D-036 同 tick 合并节点 | 地图/区域编辑全部成功状态改为“已写入当前节点”，并加静态断言 |

## 3. 验证

```text
node simos-app/src/test/js/run-gate.cjs
[frontend-gate] OK tests=428 pass=428 fail=0

tools/mvn-lock.sh -q -pl simos-app -am -Dtest=QueryServiceTest,GuiApiTest,WebuiAssetsTest,ShellAssemblySmokeTest test
QueryServiceTest 17 / 0；GuiApiTest 35 / 0；WebuiAssetsTest 10 / 0；ShellAssemblySmokeTest 3 / 0
```

真实地图编辑复现（Chrome CDP 右键事件）：

```text
中心格 (0,0) 同时有 GOV / 城市 / 区域；选中 low_hills：
status = 已改 1 格为 low_hills（一条命令，已写入当前节点）
/api/state writeEpoch = 1，revision 仍 = 1
/api/map/hex?q=0&r=0 terrain = low_hills
右键拖 (0,0)->(1,0)：status = 已改 2 格为 plains；两格读回 plains；writeEpoch = 2
```

新增/调整：

- `frontend-optimization.test.cjs`：9 条（原有 6 条 + 编辑手势 `hexAt` + 同 tick `writeEpoch` 贯通 target/缓存 + 状态文案不宣称 head 前进）；
- `webui-fix2.test.cjs`：区域名在 `map-edit` 可见；最小字号 13；同质心标签错开；
- `WebuiAssetsTest`：`/webui/modules/...` 单前缀；
- 前端门禁下界：`MIN_TESTS/MIN_ASSERTIONS = 427`（实际 428）。

## 4. 未做 / 已知边界

- PNG 文件选择仍是原生 `<input type=file>` 外观（已做宽度/颜色约束，未做完全自定义按钮）；
- 1024px 以下未做专门的移动端布局；当前 1024×768 可用但三栏较挤；
- 本轮最终 full gate：**PASS，321s**；全仓 Surefire 3777 / 0 failures / 0 errors / 7 skipped；前端 428/428（2026-11-13）。
