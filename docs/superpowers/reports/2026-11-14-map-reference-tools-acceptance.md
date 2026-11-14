# 自由判图地图工具验收与演示

> **状态**：2026-11-14 已实现，完整门禁、真实浏览器/MCP 与进程重启验收通过。本地演示服务保留运行。
> **目标能力**：[T0–T4 设计](../specs/2026-11-14-map-reference-tools-design.md)；[实现账本](2026-11-14-map-reference-tools-impl-ledger.md)、[统一测试报告](2026-11-14-map-reference-tools-test-report.md)、[结构化证据](2026-11-14-map-reference-tools-evidence.json)。

## 1. 当前可操作入口

- WebUI：<http://127.0.0.1:18511/>；进入「地图编辑 → 底图导入 → 判图工具」。可在原图工作区载入图片并共享，也可直接选择已共享参考图。查看、大图点格、多格地形、连接和擦除均可独立调用，不要求先识别或确认。
- MCP：<http://127.0.0.1:18515/mcp>（Streamable HTTP，GM 工具面）。
- 世界：`run/map-reference-demo/world`，`--profile editor --world=blank-editor`，隔离于已有存档；快照 jar `run/map-reference-demo/simos.jar`，32,221,686 字节。
- 重新启动：在仓库根执行 `run/map-reference-demo/start.sh`。端口已占用时不要重复启动；停止当前服务后可用同一脚本重启，非空 store 保留已有世界。
- 当前版本快照 **b9d8fe1d** 已按用户指令先提交推送至 `origin/feature/d036-node-editor`；本次新工具和文档留在工作区，尚未再次提交推送。

## 2. 工具使用

`map.PaintTerrain` 和 `map.ConnectHexes` 的参数直接结构化提供，无提案/不确定状态。每次调用使用最新实际 revision；冲突会拒绝，不自动重试。`simos.map.reference` 返回真实图片附件及坐标摘要，须由支持图片输入的模型消费。

```json
{"name":"simos.map.reference","arguments":{"branch":"main"}}
```

先取得 referenceId 后可任意选择局部；以下坐标属于当前演示格网：

```json
{"name":"simos.map.reference","arguments":{"branch":"main","referenceId":"4c39064bc155a22bfb5290dcd2cefd3c6510f5baabed8cfbbbdda5b2b25daa5e","q":13,"r":14,"radius":4}}
{"name":"map.PaintTerrain","arguments":{"branch":"main","expectedRevision":1,"cells":[{"q":13,"r":14,"terrain":"mountains"},{"q":12,"r":15,"terrain":"low_hills","height":0.61}]}}
{"name":"map.ConnectHexes","arguments":{"branch":"main","expectedRevision":1,"kind":"river","hexes":[{"q":13,"r":14},{"q":12,"r":15},{"q":11,"r":15}],"op":"merge"}}
```

`op=remove` 只摘点名边的该 kind；不会删除其他河段、道路或同边其他 kind。跨格跳跃拒绝并指出段号，不猜测中间路线。当前同 tick 编辑留在 `main@1`，刷新依赖 writeEpoch，不能仅凭 revision 未增加判断写入失败。

## 3. 非洲之角实际演示

控制方阅读用户原图和 MCP 返回的带坐标局部图后，以粗略地貌轮廓构造批量调用。浏览器没有运行纹理识别算法，也没有生成识别计划；共享工件零世界写入，创建缺失格仅一条 `map.ImportRaster`。随后经真实 MCP 调用 `map.PaintTerrain` 一次、`map.ConnectHexes` 两次，直接得到：

| 数据 | 实测 |
|---|---:|
| 六角格 | 1392（横向 48 格） |
| 平原 / 丘陵 / 山地 / 水体 | 771 / 89 / 169 / 363 |
| 河流边 / 道路边 | 32 / 16 |
| 参考图 | 1；原图与格网绑定跨重启保持 |

这是用于展示工具的粗略地图，不是已校准的地理成果；河流按图中蓝色河道概括，道路是演示线。图像不包含真实 DEM，未声称还原绝对海拔、水文方向或流量。

[地图截图](2026-11-14-map-reference-tools-preview.png) · [带坐标原图大图](2026-11-14-map-reference-tools-labeled-tile.png)

## 4. 真实验证

- **完整门禁**：`tools/gate.sh full` rc=0 / 281s；`clean verify` 与 `validate` 均成功。515 份本轮新 Surefire XML 合计 **3835 tests / 0 failures / 0 errors / 7 skipped**；前端 **482/482**。原始日志 `/tmp/simos-reference-full-green.log`、`/tmp/simos-gate-full-verify-4k2CyK.log`、`/tmp/simos-gate-full-validate-z83V3Q.log`。生产源码及测试未在此轮门禁窗口修改。
- **修复后门禁**：此前 L3 重复方向表和 4 项 SpotBugs 失败均已修正，保留原护栏/静态分析。单独 SpotBugs 检查曾因依赖产物不完整误报旧经济类；全依赖 `clean compile spotbugs:check` 后通过，未改经济代码，最终 full 为唯一全量结论。变异及还原 SHA 见统一测试报告。
- **真实 MCP**：外部 Java SDK 连接实际监听端口，tools/list 包含三工具的结构化 schema；`simos.map.reference` 返回 TextContent + **ImageContent**，PNG **1050×942 / 1,928,602 字节**，可清楚读取 q/r。模型读到的是图片，不是仅有文件路径。
- **真实 Chromium**：从零世界载入原图、48 列格网、共享、创建缺失格；另一个浏览器上下文在没有原图 Blob/识别计划的情况下选择服务器参考图并编辑；大图点击准确落到标注格。两次地形写入、添加道路、分别擦除两段共 5 次有效命令，加一次非法跳格命令：共 6 请求，非法段无自动重试，revision/writeEpoch 不变，全部页面未捕获 JS 错误为 0。
- **局部擦除互证**：同一边原有 river+road，移除第一段 road 后仍有 river；第二段仍有 river+road，随后移除第二段 road 恢复演示线路。
- **进程重启**：停掉第一个 Shell 进程，以同快照 jar/同 store 冷启动；地图 overview、全部 48 边、参考图列表、样格 `12,12` 的高度/地形逐字段相同。重启后 MCP 再取原图 PNG，字节 SHA 与重启前相同。
- **无业务播种**：checkpoint 的人口/家户、产业、市场、债务、货币发行、单位、政府、军队及决策人等业务集合为空；标准币种/供给/人口参数定义保留（不是经济业务播种）。

真实运行日志、请求、读回 JSON、QA 源脚本保留在 `run/map-reference-demo/`；该目录由既有 `.gitignore` 排除。实际浏览器脚本使用本机 Playwright/Chromium，是验收工具，不是产品运行依赖。

## 5. 未做 / 未验证

- 未部署内置自动判图 Agent，也未验证某个外部模型自主绘完整图；本期交付自由工具，由控制方读图调用作演示。
- 未完成派系局势、真实道路考据、精确地理配准、DEM 或多年双 AI 闭环；经济残余和旧 CLI 导入问题仍在此前调查范围。
- 草稿的旧自动识别流程仍可选用；本轮真浏览器主要验证新自由工具，旧 IndexedDB 全流程视觉验收不在新能力结论中。
- 新工具和演示文档尚未再次提交推送；本地服务保留运行供用户查看。
