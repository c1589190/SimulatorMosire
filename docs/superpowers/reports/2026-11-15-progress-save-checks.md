# 当前进度保存检查

> **状态**：历史检查记录（2026-11-15）；实时状态见[STATUS](../../agents/STATUS.md)。本次是进度快照，不是区域显示修复验收。
> **用户原话**：「我看见了，原来你没有做四国的完整疆域啊？那算了，提交并推送到远程一下吧」

## 1. 保存范围

地图参考图/直接多格地形/河流道路工具、国家/完整军队编制编辑工具、网页编辑器、相应测试和报告，以及已编译但未验收的区域显示草稿。后者仅改前端，不包含四国完整疆域或真实公开军队资料。当前停止功能推进，所有 dsh 会话已交回，无 Maven 在跑。

不提交本机 `.opencode/`、根目录截图重复件、`run/` 下存档/快照/浏览器产物、临时配置或凭据。保留现有18511和18521服务及存档；18521运行的仍是区域显示改动之前的已验证 jar，不将源码草稿冒充已部署成果。

## 2. 真实验证边界

| 检查 | 结果 |
|---|---|
| 国家/编制功能在区域显示改动之前的 `tools/gate.sh full` | PASS，3847 Java / 0 failures / 0 errors / 7 skipped；前端497/497，302s，见[原验收报告](2026-11-14-nation-formation-acceptance.md) |
| 区域显示生产 Agent 的四个JS `node --check` | PASS，worldmodel/renderer/map/panel-right |
| 区域显示生产 Agent 的 `tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am` | rc=0；仅编译，不代表验收；原命令用了 `-q`，保存时如实记录 |
| 保存前 `node simos-app/src/test/js/run-gate.cjs` | **FAIL，rc=1；497 tests / 493 pass / 4 fail / 0 skipped**，日志 `/tmp/simos-save-20261115-js.log` |
| `tools/gate.sh docs` | PASS，文档结构与 Skill 契约；日志 `/tmp/simos-save-20261115-docs.log` |
| `git diff --check` | PASS，后续暂存后仍需检查 |
| 含区域显示草稿的full / 真浏览器 / 新jar部署 | NOT_RUN |

前端失败项：

1. `f1-layer-visibility-keeps-government-layers-off-and-is-fail-closed`：旧断言要求恰有九个图层键，草稿新增 `regions`。
2. `f1-world-lod-has-no-city-markers-and-region-lod-drops-small-tiers`：旧断言要求放大后国名消失，草稿放开近景国名。
3. `right-panel-carries-world-overview-in-view-mode-and-yields-when-selected`：旧断言要求地图编辑模式右栏没有内容，草稿开放区域列表。
4. `region-names-only-show-in-the-two-region-modes`：旧断言要求常规模式不显示区域名，草稿开放四公开模式。

这些失败表明现有断言与新显示设计存在冲突；尚无新回归/真实浏览器证据保证草稿正确，不能仅修改预期值就声称验收通过。测试文件原样保存，未删除、屏蔽或降门槛。

## 3. 继续条件

用户后续要求继续时，按[区域显示目标序列](../specs/2026-11-15-region-visibility-repair.md)由统一测试 Owner 判定旧断言与实现、补必要正负回归并核验名称/图层开关、异步目标失效、基底边界与高亮分离；再完成真实浏览器、完整门禁与演示 jar 更新。当前不自动执行这些工作。
