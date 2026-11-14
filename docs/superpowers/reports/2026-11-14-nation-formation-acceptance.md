# 国家与完整军队编制编辑验收

> **状态**：已验收（2026-11-14）。
> **目标设计**：[国家与完整军队编制编辑](../specs/2026-11-14-nation-formation-editor-design.md)。
> **实现台账**：[领域工具、宿主与网页实现](2026-11-14-nation-formation-impl-ledger.md)。
> **统一测试**：[测试报告](2026-11-14-nation-formation-test-report.md)。

## 1. 实际交付

- `simos.gov.editNation` 一次 JSON 创建/修改国家、领土并集、名称/颜色及可选中央 GOV；默认拒绝重叠，显式 transfer 才扣他国格；新 GOV 的 0 人家户同批建立。
- `simos.army.editFormation` 一次 JSON 创建/修改整个师→旅→营树、真实 Social 人员、装备、隶属与附属关系；未提及节点保留，不逐营调用，不初始化经济/Actor/GA。
- `simos.gov.nations` / `simos.army.formation` 共用领域读视图，后者返回本级/子树人数与真正 PNG ImageContent。网页国家层按 Nation 实体合并领土与文字；编制图有宽窗、适应窗口、原图、缩放、滚动和 Esc。
- 子级默认附属并在创建时写实父坐标；新根默认独立。脱离的旅仍保留隶属，带其附属营独立移动。
- 国家、军队计划与绘图分别在 gov/army；单位与移动仍在 unit，人口仍在 social，几何仍在 map。app 只固定工具桥、共享宿主写入和工件发布。

## 2. 实机与协议证据

| 验证 | 实际结果 |
|---|---|
| 真实 Shell / editor / 隔离世界 | 一次国家/GOV、一次 10 单位、3340 Social 人员；无经济 data 变化 |
| 真实推进与冷启动 | 第一天师旅营同移；第二天 detach 第2旅，其3营随旅独立、其他单位随师；关壳重开位置相等 |
| 真 Chromium 完整编辑 | 国家一次 POST、整师一次 POST、自动图与可再次保存的 JSON、改名再保存、负数拒绝；0 未捕获 JS 异常 |
| 大图 | 同一张 PNG，无额外写请求；宽窗 fit/原图/缩放/Esc 正常 |
| 真 MCP 查询 | 四工具实际可发现；国家和编制返回 Text + PNG ImageContent；本级60、合计3340、10节点 |
| 真 MCP 写入 | 独立世界一次 create 建10节点；一次 update 只点3节点，另7节点保留，人数3340→3440；真实图读回 |
| 最终打包与重启 | 最终 shaded jar 替换后，同 store 六组世界视图逐项相等；只读 Chromium 大图 0 POST / 0 JS 异常，MCP 图再读成功 |
| full gate | rc=0 / 302s；517 份 fresh XML：3847 Java / 0 fail / 0 error / 7 skip；前端497/497；validate和全部静态检查通过 |

隔离演示是原非洲之角地图的 SQLite backup + checkpoint/参考图副本：1392 格、32 条河流边、16 条演示道路边；新增虚构国家 `demo-nation`（12格）、中央 GOV `demo-gov`、演示师 `demo-formation`（10节点，3340人）。原演示 store 保留。

## 3. 使用

演示网页：<http://127.0.0.1:18521/>；MCP：<http://127.0.0.1:18525/mcp>。
进入“地图编辑”或“单位编辑”，展开“国家与完整编制”，选择现有对象载入，编辑整份 JSON 一次保存。军队选择“虚构编制演示师”后点击“查看大图”。模板与当前选格快捷按钮可用于新对象。

新入口以 `mode=create|update` 明确新增/修改，以稳定 `nationId/armyId/Unit.id` 定位；apply 带 `expectedRevision`，preview 可选。树节点的 `personnel` 是本级目标，不含下属；`equipment` 给出时是该级装备整表。修改时不写的节点/字段保持。需要独立移动时设 `attached=false`；组织隶属仍由树结构表示。

## 4. 验收边界

- 本次交付工具和示例；示例国家/师为虚构，不代表也门、埃塞俄比亚、厄立特里亚、索马里真实疆域或军力。四国年份、疆域口径和公开资料编制尚未制作。
- 人数校准通过 Social 工单建立/增减实际成员，新成员默认成年男性锚点18岁，工具有明示；它不假装完成现实招募或真实人口构成。
- transfer 扣空他国某个区域时具名拒绝，保留 Map 区域非空与引用完整性；不静默删除区域。
- 节点上限512、深度16有负向守卫；未声称512节点的大图可读性/性能压力已实测。
- 未开展双AI多年推演；未修复旧经济机制和所有旧高阶建局势工具。
- 本轮改动留在工作树，未再次提交/推送。

## 5. 固化证据

[结构化证据](2026-11-14-nation-formation-evidence.json)、[最终编制树 PNG](2026-11-14-nation-formation-tree.png)、[大图窗口实机截图](2026-11-14-nation-formation-preview.png)、[国家 PNG](2026-11-14-nation-formation-nation.png)。

启动脚本：`run/nation-formation-demo-v3/start.sh`；PID 140907；最终 jar 32358355 字节，SHA-256 `7eef89356f010e9ba94dbf109c80105204fed695b779626c08f0b87fac576a8a`。外部 Java 探针及输入 JSON 留在 `run/nation-formation-demo-v3/qa/`，原始运行日志和逐项前后读回保留在演示目录。

门禁原始日志：`/tmp/simos-nation-formation-full5.log`、`/tmp/simos-gate-full-verify-pqUyAX.log`、`/tmp/simos-gate-full-validate-cjVxsS.log`。此前 full 的封装/格式/样本表问题已修复，记录不借用旧地图门禁数字；record 可变集合采用真实防御性复制，共享宿主参考图服务仅恢复其既有精确身份契约注解。
