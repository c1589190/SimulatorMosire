# SimulatorMosire 当前状态

> **更新时间**：2026-11-15 00:32（Asia/Shanghai）
> **本文是唯一实时状态入口。** dated status 和旧报告只表示历史事实；设计与维护裁定仍以各自正本为准。

## 1. 当前目标

**当前动作（2026-11-15）：按用户要求停止扩展功能，提交并推送当前进度快照。** 四国完整疆域与真实军队资料尚未制作。地图自由工具、国家/整树编制编辑功能与测试已落；区域显示修复源码已编译，但统一测试/浏览器验收暂停，不列为已交付。[本次保存检查](../superpowers/reports/2026-11-15-progress-save-checks.md)记录验证边界。

**区域显示草稿（待验收）**：[目标序列](../superpowers/specs/2026-11-15-region-visibility-repair.md)、[实现台账](../superpowers/reports/2026-11-15-region-visibility-impl-ledger.md)。唯一 dsh deepseek-flash 生产 Agent 已交回停写；Java 编译与四个 JS 语法检查通过。保存前前端门禁 rc=1：**497 tests / 493 pass / 4 fail / 0 skipped**，失败涉及原有九键图层、近景国名隐藏、地图编辑右栏为空、常规模式不显示区域名的断言；未修改断言使其变绿。当前18521演示仍是修复前的已验证 jar，原存档保留，未声称新显示修复通过。

**本轮已完成：国家与完整军队编制的一体化编辑**。[目标设计](../superpowers/specs/2026-11-14-nation-formation-editor-design.md)、[验收报告](../superpowers/reports/2026-11-14-nation-formation-acceptance.md)、[结构化证据](../superpowers/reports/2026-11-14-nation-formation-evidence.json)、[实现台账](../superpowers/reports/2026-11-14-nation-formation-impl-ledger.md)、[测试报告](../superpowers/reports/2026-11-14-nation-formation-test-report.md)。GM 一次 JSON 原子创建/修改整个师旅营树、Social 人员与装备、默认附属共同移动；国家/领土/GOV 同次编辑；PNG 编制图和网页载入/保存/大图。国家与军队领域工具/绘图归 gov/army，Unit/Social/Map 数据权威保留，app 仅固定桥/宿主/工件发布。

**区域显示修复前的最终门禁**：`tools/gate.sh full` rc=0 / 302s；517 份 fresh XML 合计 **3847 Java tests / 0 failures / 0 errors / 7 skipped**，前端 **497/497**，完整 validate/依赖边界/Spotless/Checkstyle/SpotBugs 全过。原始日志 `/tmp/simos-nation-formation-full5.log`、`/tmp/simos-gate-full-verify-pqUyAX.log`、`/tmp/simos-gate-full-validate-cjVxsS.log`。变异自证 2 项均红后恢复；record 集合封装真实修复，无新增盲抑制。该结果不覆盖后续区域显示草稿。

**新演示正在运行**：GUI <http://127.0.0.1:18521/>，MCP <http://127.0.0.1:18525/mcp>，PID **140907**，最终 shaded 快照 `run/nation-formation-demo-v3/simos.jar`，store `run/nation-formation-demo-v3/world`，启动 `run/nation-formation-demo-v3/start.sh`；profile=editor/world=blank-editor。jar **32,358,355** 字节，SHA-256 `7eef89356f010e9ba94dbf109c80105204fed695b779626c08f0b87fac576a8a`。从冻结 classes 切到最终 jar 后，state/国家/军队/地图/单位/参考图六组读数逐项相等；最终 jar 真浏览器只读大图 0 POST、0 JS 异常，MCP 真图片读回正常。

控制方实机另验证：10 单位/3340 人，一次创建，第一天同移，第二天 detach 旅带其 3 营独立，关闭后重启位置一致；真实 MCP 一次整树 create、一次只提 3 节点 update 后另 7 节点保留。公开演示是原地图独立副本，含虚构的 `demo-nation`（12 格）、`demo-gov` 与 `demo-formation`（10 节点、师部60/合计3340）；原地图 store 未覆盖。真实四国资料尚未录入，未进行双 AI 多年推演。

全部 dsh 实现/测试会话已交回停写，均使用 `deepseek-account / deepseek-flash`；无 Maven 在跑。本文件随当前进度快照一起提交；提交标识由 `git log -1` 读取，之前已推送的基线为 `b9d8fe1d`。前置[只读审计](../superpowers/reports/2026-11-14-nation-army-capability-audit.md)作为实施前事实保留，不再据其缺口描述判断本轮新增入口。

以下为此前已完成的地图工具功能里程碑。

**本轮已完成（2026-11-14）**：自由 LLM 判图地图工具、网页操作与隔离非洲之角演示。工具直接写入多格可用地形、按有序相邻格增删河流/道路，参考图返回带坐标的真图片；没有提案/不确定状态与强制复核流程。[目标设计](../superpowers/specs/2026-11-14-map-reference-tools-design.md)、[验收报告](../superpowers/reports/2026-11-14-map-reference-tools-acceptance.md)、[结构化证据](../superpowers/reports/2026-11-14-map-reference-tools-evidence.json)。

此前保存版本 **b9d8fe1d** 已推送至 `origin/feature/d036-node-editor`，包括原有地图环绕/寻路/导入改动与上一轮网页底图导入。本次保存继续纳入新自由工具、国家/编制编辑、显示草稿与相应测试/文档；本机 `.opencode/` 和根目录截图重复件保留，不纳入提交（原图样例已在模块资源中）。

**此前地图工具门禁**：`tools/gate.sh full` rc=0 / 281s，515 份新 XML 合计 **3835 tests / 0 failures / 0 errors / 7 skipped**，前端 **482/482**，shaded jar 32,221,686 字节。此前方向表重复与四项 SpotBugs 已修复，原护栏/静态检查保持；[测试报告](../superpowers/reports/2026-11-14-map-reference-tools-test-report.md)。控制方核对原始日志、fresh XML 与 jar；不是旧 full 数字。

**真实演示已跑通并保留服务**：GUI <http://127.0.0.1:18511/>，MCP <http://127.0.0.1:18515/mcp>。PID **76385**，运行 `run/map-reference-demo/simos.jar` 快照，store `run/map-reference-demo/world`，启动脚本 `run/map-reference-demo/start.sh`。profile=editor/world=blank-editor，无业务播种。1392 格、32 河流边、16 道路边；真实浏览器大图准确点格、直接地形/连接/擦除/错误段拒绝，JS 未捕获异常为 0。停进程冷启动后 overview/样格/参考图逐字段相同，MCP 再取原图 PNG 的 SHA 相同。源图与地貌是粗略演示，道路是演示线，不是真实 DEM/已考据道路。

全部 dsh 生产/测试会话已交回停写，使用 `deepseek-account / deepseek-flash`；无 Maven 在跑，原地图演示与上述新国家/编制演示服务均保留。原经济/旧 CLI 导入器/派系局势/双 AI 多年闭环仍见[此前调查](../superpowers/reports/2026-11-14-map-ai-readiness-audit.md)，本期未混做。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 分支 | `feature/d036-node-editor` |
| HEAD | 本状态随进度快照提交，当前标识以 `git log -1` 为准；前置基线 `b9d8fe1d` |
| upstream | 提交/推送目标 `origin/feature/d036-node-editor`；远端结果以 Git 记录为准 |
| main 差距 | 前置基线比本地缓存 `origin/main` 多 32 个提交；本次不合并 main |
| 工作树 | 本次范围为自由判图、国家/完整编制、区域显示草稿、测试和文档；排除 `.opencode/` 与根目录截图 |
| reactor / Java | 18 个 simos 模块；本机 OpenJDK 21.0.13.0.3 |
| AgentLib | POM 为 `0.2.3-SNAPSHOT` |
| 构建与运行 | 国家/编制 full 3847/497 为显示草稿之前；当前前端493/497、编译通过、未重跑full；18521仍运行前一已验证jar |

2026-11-13 清理的历史结果仍保留：worktree/旧运行产物和 SDD 已清理，本地经济分支保留；仓库外归档在 `/home/cna/simos-cleanup-20261113/`。本轮没有继续清理或删除分支。

## 3. 最近功能门禁与真实运行证据

保存前检查（2026-11-15）：前端门禁 **FAIL，493/497，4 fail**，日志 `/tmp/simos-save-20261115-js.log`；文档/Skill **PASS**。当前源码未重跑 full，显示修复未浏览器验收。具体失败名称与下一步见[保存检查](../superpowers/reports/2026-11-15-progress-save-checks.md)。

国家/完整编制在显示草稿之前的最终 full **PASS**：`/tmp/simos-nation-formation-full5.log`（rc0/302s），3847 Java（7 skipped）、497 前端；最终 jar 与真实运行见[本轮验收](../superpowers/reports/2026-11-14-nation-formation-acceptance.md)。下面是此前地图阶段的独立证据。

自由判图工具最终 full **PASS**：`/tmp/simos-reference-full-green.log`、`/tmp/simos-gate-full-verify-4k2CyK.log`、`/tmp/simos-gate-full-validate-z83V3Q.log`。3835 Java tests（7 skipped）、482 前端 tests；真实浏览器/MCP/重启证据见[验收报告](../superpowers/reports/2026-11-14-map-reference-tools-acceptance.md)。运行日志与读回位于 `run/map-reference-demo/`，截图与 evidence 已固化入报告目录。

上一阶段底图导入 full 曾通过（3801/455），但自动识别的真实 IndexedDB 全流程仍未纳入本轮自由工具验收；不能借新功能通过声称所有旧路径已验收。下表只为更早的实施前调查。

### 实施前调查 2026-11-14

| 验证 | 结果 |
|---|---|
| `tools/gate.sh pre` | PASS；文档/Skill 结构通过，无本仓服务或 Maven 占用 |
| `tools/gate.sh focused simos-app -Dtest=MapWrapTest,MapCodecTest,PathFinderTest,ShellAssemblySmokeTest,ShellMainGenesisTest,DecisionAgentRunnerTest,DecisionPackagePersistenceTest,AdjudicationEndToEndTest,TimeParticipantWiringTest,CommandDrainTest test` | rc=0，35s；10 类共 **59 tests / 0 failures / 0 errors / 0 skipped** |
| 聚焦构建内前端门禁 | **429/429** |
| 默认 GSim 导入 → 当前 Shell 重放 | 导入 rc=0，但重放失败：缺 `SocialData.provisioning` |
| 重建模式导入 → 当前 Shell 重放 | 三格样本可读回 |
| 同目录重复导入 | rc=1，SQLite 唯一键冲突；已有 checkpoint 已被覆写，缺陷复现 |
| editor 世界初始化 / 建 GOV / 募兵 | 均因暂停域命令未注册而整批 REJECTED；worldgen dryRun 成功不代表实际写入成功 |
| editor 同 tick 批提交 | head 1→2，tick 均为 0；一 tick 一节点没有覆盖 submitBatch |
| SmallWorld editor 0→1095 天 | 成功，经济 data 保持相等；重启仍为 tick=1095 / head=3；未调用模型 |
| 无效 `--approval-port` | 日志报未知参数，但进程 rc=0；现有一键脚本仍传该参数 |
| full gate / 真模型多年运行 | **NOT_RUN** |

结论限于 **FOCUSED TEST PASSED，非 full gate**。上面的 1095 天是独立临时世界运行，不在 JUnit 内；不能称为多年 AI 推演。

证据：`/tmp/simos-20261114-audit-pre.log`、`/tmp/simos-20261114-audit-focused.log`、`/tmp/simos-20261114-audit-ozh8zn4c/`；关键输出已固化至报告旁 evidence JSON。

### 历史功能基线

2026-11-13 曾记录 full gate PASS，321s；3777 tests / 0 failures / 0 errors / 7 skipped，前端 428/428。当前门禁已重新执行，采用上方本轮数字；历史数字仅作溯源。

## 4. 当前有效设计与维护入口

| 类型 | 入口 |
|---|---|
| 常驻路由 | [根 AGENTS](../../AGENTS.md) |
| 设计原稿与索引 | [设计原稿](../superpowers/specs/2026-10-02-architecture-design-source.md)、[DESIGN_RULINGS](../superpowers/DESIGN_RULINGS.md) |
| D-034 产品收敛 | [时间线节点编辑与地址查询](../superpowers/specs/2026-11-13-timeline-node-editor-and-address-query-design.md) |
| D-035 补裁 | [节点版本、世界文档与底图](../superpowers/specs/2026-11-13-node-version-world-docs-and-asset-pipeline-design.md) |
| D-036 补裁 | [节点地址与文档所有权](../superpowers/specs/2026-11-13-node-address-and-docs-ownership-design.md) |
| 当前实现父序列 | [目标运行设计](../superpowers/specs/2026-11-13-node-docs-runtime-target-design.md)、[既有实施计划](../superpowers/plans/2026-11-13-node-editor-refactor-plan.md) |
| 维护正本 | [MAINTENANCE_RULINGS](MAINTENANCE_RULINGS.md)、[WORKFLOW](WORKFLOW.md)、[VERIFICATION](VERIFICATION.md)、[ENVIRONMENT](ENVIRONMENT.md) |
| 文档与 Skill | [docs/AGENTS](../AGENTS.md)、[SKILLS](SKILLS.md) |

既有目标运行设计中的“已实现”须结合本轮报告理解，尤其是批提交节点语义、editor 初始化和保留工具的经济调用。报告记录实现缺陷，不静默改写用户设计。

## 5. 未验证与当前 blocker

0. **区域显示草稿未验收**：保存前前端 4 项失败尚未处理；需要独立测试 Owner 按目标序列判断旧断言与生产实现，补正负测试/浏览器检查后再更新演示 jar。用户当前要求保存进度，本轮不继续此项。
1. **旧 GSim/CLI 导入入口不可靠**：默认格式不可读，重复导入可能损坏旧 checkpoint；需要先保护已有世界并统一当前输出格式。
2. **旧高阶初始化链**：worldgen、旧 createOffice/spawnArmy/raiseUnit 仍可能提交暂停域命令；新增 editNation/editFormation 已实证 editor 无经济创建国家/GOV/完整编制，不能把旧链缺口再套到新入口。完整财政/决策人初始化仍未合并。
3. **双 AI 多年闭环尚未成立**：AdvanceTime 不调用决策人模型；army 参与者发现接触，不自动裁决战争。需要明确外部 GM Agent/宿主编排方式与可恢复轮次。
4. **节点/分支边界**：submitBatch 绕过同 tick 合并；GM 五个决策包工具的读写审计口硬绑定 main。后者本轮仅源码核查。
5. **长跑边界**：运行状态以内存为主，会话全量装载，尚未验证长期上下文、预算、异常中断与恢复；旧判决面还存在接受结果与真实落盘不一致的风险。
6. **内容与局势**：非洲之角地图与虚构国家/军队样例已验收；真实四国国界、公开编制资料与决策人绑定尚未制作。全球投影/环绕不是局部战区前置。
7. **既有未完成项**：领域自然语言字段迁入文档、full/旧档中间 revision 压缩、决策人编辑 GUI 等未完整收口；本轮 PNG 已走持久 ArtifactStore 并重启可读，其他旧图像链未一并验收。旧领域 schema 不承诺兼容。
8. **dsh 本机环境已打通**：早期 `deepseek-official` 报缺凭据、account 报未登录的直接原因是 headless 默认停用 credentials / authorization / deepseek-account；仅为本次调用启用这些组件并选择 `deepseek-account / deepseek-flash` 后，模型探针成功，生产与 full gate 成功，责任区会话已交回停止。全局配置与凭据未修改。

## 6. 下一步

本轮按用户要求保存进度并停止功能推进。后续获准继续显示修复时，第一步运行 `node simos-app/src/test/js/run-gate.cjs` 复现 4 项失败，并按[目标序列](../superpowers/specs/2026-11-15-region-visibility-repair.md)完成统一测试与真实浏览器验收，不能直接套用此前 full 通过结果。

国家/军队工具实现阶段已交付。后续内容制作需采用明确年份与国界/实际控制区口径，查公开军力资料并区分可核实编制与估计缺口，再为也门、埃塞俄比亚、厄立特里亚、索马里四国制作真实局势；不得把虚构演示当成真实数据。

新演示 GUI <http://127.0.0.1:18521/> / MCP <http://127.0.0.1:18525/mcp>；重启先停止 PID140907，再运行 `run/nation-formation-demo-v3/start.sh`，保留原 store。此前地图演示18511/18515和原 store 仍保留。后续经济/AI闭环工作以现有审计和设计为依据，不因新工具通过门禁就声称旧机制都可靠。

## 7. 本轮文档检查

2026-11-15 保存检查运行 `tools/gate.sh docs`：`check-docs.py` 与 `skills-lint.py` 均 PASS，rc=0；18 模块 / 35 索引文件；具体链接计数见本次命令输出，active skills=1。
