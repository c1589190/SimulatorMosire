# SimulatorMosire 当前状态

> **更新时间**：2026-11-14 20:49（Asia/Shanghai）
> **本文是唯一实时状态入口。** dated status 和旧报告只表示历史事实；设计与维护裁定仍以各自正本为准。

## 1. 当前目标

**已暂停（2026-11-14，用户明确要求）**：WebUI「地图编辑 → 底图导入」子模式，以根目录非洲之角截图为示例。范围为浏览器识别/修整/草稿、map 原子导入命令、空白 editor 创世与宿主接线。[目标运行设计](../superpowers/specs/2026-11-14-raster-import-runtime-design.md)、[实施计划](../superpowers/plans/2026-11-14-raster-import-plan.md)。

生产已交回并停写：`tools/mvn-lock.sh -DskipTests compile -pl simos-app -am` rc=0 / Maven 6.113s；日志 `/tmp/simos-raster-compile-final.log`。[实现账本](../superpowers/reports/2026-11-14-raster-import-impl-ledger.md)。独立评审发现的参数输入取消新图解码、在途响应污染后续草稿两项 P2，已按 R1/R4 原地细化并修正。

**暂停现场**：生产实现与统一测试已完成；`tools/gate.sh full` 于 20:46 完成，rc=0 / 289s；510 份新 Surefire XML 合计 **3801 tests / 0 failures / 0 errors / 7 skipped**，前端 **455/455**，shaded jar 32,152,500 字节。控制方已核对原始日志与 XML；[统一测试报告](../superpowers/reports/2026-11-14-raster-import-test-report.md)。真实浏览器识别质量、真实 IndexedDB 刷新/清除、用户截图全流程及其服务进程重启验收 **NOT_RUN**，不能称为整体功能已验收。

用户提出转向“提供工具，让 LLM 自己读图填地形”。当前仅讨论该方向，未实施新工具、未替换现有设计、未继续原浏览器验收。dsh 测试会话在完成 full gate 和测试报告后已中断停止，当前没有 dsh 写入、Maven 或验收服务进程。已有实现、测试及用户 dirty 均保留，未 commit/push。

全部内置子 Agent 已停止；后半段生产收尾和统一测试实际使用 dsh headless 的 `deepseek-account / deepseek-flash`。本次临时 overlay 启用 headless 默认停用的 credentials / authorization / deepseek-account，不修改全局配置、不复制密钥。

更大方向仍是暂时搁置经济，建立非洲小军阀局势，再跑决策人 AI 与推演 AI 多年闭环。实施前的[调查报告](../superpowers/reports/2026-11-14-map-ai-readiness-audit.md)与[证据](../superpowers/reports/2026-11-14-map-ai-readiness-evidence.json)记录了旧 CLI 导入、editor 建局势、批提交和多年编排缺陷；本特性不代表这些缺陷已修复。未 commit/push，保留开工前 dirty。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 分支 | `feature/d036-node-editor` |
| HEAD | `299bfc6f`（2026-11-14；本轮未新增提交） |
| upstream | `origin/feature/d036-node-editor`；本地缓存比较 ahead=0 / behind=0，未联网刷新 |
| main 差距 | 本地缓存 `origin/main..HEAD` 为 31 个提交；未合并 main |
| 工作树 | 开工前 19 个 tracked dirty 文件，另有 `.opencode/`、`MapWrap.java`、`MapWrapTest.java`；本轮增加底图导入实现、测试和设计/验收文档；原用户 dirty 保留 |
| reactor / Java | 18 个 simos 模块；本机 OpenJDK 21.0.13.0.3 |
| AgentLib | POM 为 `0.2.3-SNAPSHOT` |
| 构建与运行 | full gate 已通过并打包；用户叫停后无 Maven/验收服务，浏览器流程未执行 |

2026-11-13 清理的历史结果仍保留：worktree/旧运行产物和 SDD 已清理，本地经济分支保留；仓库外归档在 `/home/cna/simos-cleanup-20261113/`。本轮没有继续清理或删除分支。

## 3. 最近功能门禁与真实运行证据

底图特性 full gate **PASS**：`/tmp/simos-raster-gate-full.log`、`/tmp/simos-gate-full-verify-DYiklO.log`、`/tmp/simos-gate-full-validate-cjGZJg.log`。3801 Java tests（7 skipped）与 455 前端 tests；真实浏览器 **NOT_RUN**。见[测试报告](../superpowers/reports/2026-11-14-raster-import-test-report.md)。下表仅为实施前调查证据。

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

1. **导入入口不可靠**：默认格式不可读，重复导入可能损坏旧 checkpoint；需要先保护已有世界并统一当前输出格式。
2. **无经济建局势被阻断**：worldgen、建 GOV、募兵保留工具仍提交暂停域命令。内置创世仍生成经济数据；editor 日结停用则已实测成立。
3. **双 AI 多年闭环尚未成立**：AdvanceTime 不调用决策人模型；army 参与者发现接触，不自动裁决战争。需要明确外部 GM Agent/宿主编排方式与可恢复轮次。
4. **节点/分支边界**：submitBatch 绕过同 tick 合并；GM 五个决策包工具的读写审计口硬绑定 main。后者本轮仅源码核查。
5. **长跑边界**：运行状态以内存为主，会话全量装载，尚未验证长期上下文、预算、异常中断与恢复；旧判决面还存在接受结果与真实落盘不一致的风险。
6. **内容与局势**：底图特性已内置非洲之角示例，生成六角图幅实现和程序化门禁已完成，视觉验收暂停；仍没有已登记的完整派系局势。全球投影/环绕无需作为局部战区前置。
7. **既有未完成项**：领域自然语言字段迁入文档、full/旧档中间 revision 压缩、PNG 持久化、决策人编辑 GUI；旧领域 schema 不承诺兼容。
8. **dsh 本机环境已打通**：早期 `deepseek-official` 报缺凭据、account 报未登录的直接原因是 headless 默认停用 credentials / authorization / deepseek-account；仅为本次调用启用这些组件并选择 `deepseek-account / deepseek-flash` 后，模型探针成功，生产收尾与 full gate 成功，会话已按用户要求停止。全局配置与凭据未修改。

## 6. 下一步

**当前不继续执行。** 用户已要求暂停，先汇报状态并讨论 LLM 判图工具。

- 可复用现有六角格生成、人工修正/确认预览、`map.ImportRaster` 原子写入和 `blank-editor`；识别策略可另加 LLM 入口。
- 新方向需要先写目标运行序列：源图/格网绑定 → 带 q/r 标记的分区图片 → LLM 批量地形提案 → 完整性与坐标校验/原图对照 → 审阅确认 → 既有原子命令。分区结果应允许“不确定”，不能仅凭 LLM 假定真实海拔。
- 判图模型和通道的图片能力尚未核实；dsh 编码 Agent 的可用性不等于判图输入链路已成立。
- 原浏览器任务仅有临时脚本 `/tmp/SimosRasterFlow.java`，尚未启动服务或运行样例；不能把测试环境的程序重启等同于用户截图浏览器验收。
- 更大的 CLI 导入保护、editor 建 GOV/募兵经济残余与双 AI 多年编排仍待后续处理。

## 7. 本轮文档检查

2026-11-14 收尾运行 `tools/gate.sh docs`：`check-docs.py` 与 `skills-lint.py` 均 PASS，rc=0；18 模块 / 35 索引文件 / 66 链接，active skills=1。
