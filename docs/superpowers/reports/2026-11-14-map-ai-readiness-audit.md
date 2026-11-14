# 地图导入与双 AI 多年推演现状调查

> **状态**：调查完成（2026-11-14，Asia/Shanghai）；不是实现设计或验收关账。
> **源码基线**：`feature/d036-node-editor`，`299bfc6f`，包含开工前已有的未提交地图、环绕、寻路、导入与前端改动。
> **当前状态**：[STATUS](../../agents/STATUS.md)；[结构化实测证据](2026-11-14-map-ai-readiness-evidence.json)。

## 1. 调查所沿目标运行序列

父序列是 [节点编辑器目标运行设计](../specs/2026-11-13-node-docs-runtime-target-design.md) 的 S0–S8。以下是本轮调查用的业务链，表示用户目标的检查顺序，不替代既有设计。

| 阶段 | 目标运行行为 | 对应父序列 | 当前判断 |
|---|---|---|---|
| A0 地图 | 导入局部地图，形成坐标、地形、区域及稳定 ID，可启动并重启读回 | S0/S2/S6/S8 | 有实现；默认导入和重复导入存在实测故障 |
| A1 局势 | 在地图上建立政府/派系、军队/单位、人员、归属、外交、决策人和初始文本 | S0/S2/S5/S7 | 基础工具存在；高层建局势工具仍被经济依赖阻断 |
| A2 决策 | 决策人读取权限内局势，调用绑定模型，保存决策包 | S0/S1/S2/S7 | runner、provider、会话和决策包存在；本轮只验确定性测试 |
| A3 推演 | GM/推演 AI 收齐决策包，裁决后经 Command → ChangeSet → Revision 写入事实 | S1/S2/S5 | 可由外部 MCP Agent 编排；现有旧判决入口不能承担完整多人推演 |
| A4 下一轮 | 推进指定时段，处理已启用参与者，再触发下一轮决策 | S3/S7 | 时间推进已实测；不会自动调用决策人模型 |
| A5 数年 | 重复 A2–A4，遇失败停止于真实阶段，重启可恢复且不重复执行 | S3/S4 | 缺持续轮次编排；分支、任务恢复、上下文与预算仍需处理 |

**结论**：项目已有地图编辑器、时间与存档内核、决策包和军事写工具；离目标最近的缺口集中在导入安全、无经济局势初始化及双 AI 回合编排。现有测试全绿不能证明这些连接已经成立。

## 2. 本轮用户目标与范围

用户原话（2026-11-14，本次会话）：

> 把调试到我都不知道可靠性怎么样的经济系统与相关子模块扔一边
>
> 把地图导入做做好，导入一张非洲小军阀的地图做个局势，让已有的决策人模块ai配合推演ai真实地跑几年

已有 D-034–D-036 覆盖暂停经济/生产/文化、保留 gov/ga/actor、节点地址和文档所有权。本轮调查不恢复经济，也不另定战斗公式、现代军阀设定或地图来源。用户尚未指定具体非洲源图、年代和局势，仓内也未找到对应场景资产。

本轮对父序列的改动仅为调查记录：不改变运行顺序、预算、身份、数据所有权、持久化或失败语义；生产代码和测试文件均未修改。开工前已有 dirty 文件全部保留，未提交或推送。

## 3. 已实机复现的阻断问题

### F1 高：重复导入会先覆盖已有 checkpoint 再失败

`tools/gsimap_import.py:982` 建目录，`:987` 以 `w` 打开 `checkpoints/main/1.json`，`:990` 才操作 SQLite；`:835` 插入已有的 `store_meta` 唯一键时失败。没有在写入前拒绝已有 store，也没有整个输出的原子发布。

**实测**：三格样本首次重建导入 rc=0；修改一格地形后向相同输出目录再次导入，rc=1，报 `UNIQUE constraint failed: store_meta.key`；旧 checkpoint 的 SHA-256 已改变。失败没有保护旧存档。此项优先于扩大地图或导入真实局势。

### F2 高：文档中的默认导入用法成功生成不可读存档

`tools/gsimap_import.py:794` 的默认 social 载荷只含 `populations`；`:986` 只有提供 `--regen-samples` 才补当前十模块空切片。当前 `SocialData.java:104` 拒绝缺少 `provisioning`。

**实测**：同一三格 full map，不带 samples 导入 rc=0，但当前 Shell 重放失败，原因是 `SocialData.provisioning 不得为 null`；带 samples 导入 rc=0，Shell 可读回三格。导入成功退出与世界可运行是两个验收条件。

### F3 高：editor 停掉经济 handler，却保留提交经济命令的建局势工具

卸载本身已做：`simos-app/.../Shell.java:1094` 移除暂停域 handler，`:1165`–`:1181` 只挂 unit/ga/army 时间参与者。工具过滤按名字执行，保留工具内部的跨域调用未清理。

| 入口 | 残余调用与源码 | 本轮实测 |
|---|---|---|
| `simos.worldgen.initialize` | `WorldgenInitializeTool.java:721`–`:741` 仍计算经济种子并加入 `production.Seed`、`economy.Seed` | 在 v17levant 奥斯特马克区域 dryRun 成功，实际执行因两条命令未注册而整批拒绝；库仍只有创世行 |
| `simos.gov.createOffice` | `GovCreateOfficeTool.java:533` 加入 `economy.RegisterGovernment`；类型由 `AppGovToolCommandTypes.java:29` 注入 | SmallWorld 上创建无 staff、无辖区的新 CENTRAL GOV，整批拒绝 |
| `simos.unit.raiseUnit` | `RaiseUnitTool.java:547` 加入 `economy.RegisterHousehold` | SmallWorld 区域内招募 1 人、粮钱均为 0，仍被整批拒绝；可用人力为 1069，并非人力不足 |

两条 SmallWorld 失败调用后 head 仍为 1，候选态没有部分落盘，批事务拒绝语义有效。但募兵工具同时返回 `submitted=true`、`success=false`、`submission.result=rejected`，调用方若只看 `submitted` 会误判。

创世也尚未分离：`SmallWorld.java:270`、`:308` 和 `ThreePowersWorld.java:327`、`:365` 仍直接生成经济数据。因此可以启动内置小世界、暂停经济日结，但尚不能通过现成高层工具在新地图上顺利建局势。

### F4 中：一 tick 一节点只覆盖单命令，批提交会漏出中间 revision

`simos-core/.../CommandBus.java:927` 的单命令 commit 使用 `sameTickMerge`；`:544` 的 `commitBatch` 仍在 `:556` 无条件 append。真实决策包 flush（`DecisionTurnFinalizer.java:247`）、GM 表态（`CoreDecisionSubmissionPort.java:202`）和效果执行（`DecisionEffectExecutor.java:306`）均使用批提交。

**实测**：editor 创世 `main@1/tick=0` 后，以单条 `ga.PutInfo` 组成 submitBatch，得到 `main@2/tick=0`。SQLite 确认同一 tick 两行。后续推进后的行如下：

```text
branch  revision  tick
main    1         0
main    2         0
main    3         1095
```

这不是“GM 的 base+1 校验必然失败”：批提交当前确实递增，那个校验与实际一致；真正缺陷是节点语义没有覆盖批入口。统一时需要同时审计依赖 `base+1` 的决策元数据。

### F5 中：一键运行脚本与 CLI 已脱节

`run-small-world.sh` 仍传 `--approval-port`，而 `ShellMain.java:128` 拒绝未知参数，`:55`–`:76` 记录错误后正常 return。

**实测**：使用当前编译类运行同一非法参数，日志包含 `未知参数: --approval-port`，进程 rc=0。现有脚本会启动失败，外层仅凭退出码又可能误判成功。本轮没有实际启动用户的已有 store。

## 4. 地图制作能力与局限

- GSim 导入器能够处理地形、区域、坐标和边标记；拒绝 MapDiff、未知地形与非空城市。重建模式能输出当前地形块和十模块空切片，但不会生成政府、军队或决策人。
- PNG 功能是对**已有网格**涂色：`simos-map/src/main/resources/webui/modules/map/map-png.js:212` 读取当前 hex，`:290` 分地形提交命令；不从图片尺寸生成地图，不识别派系/政治边界。图片与对齐参数只在 JS 内存；分组写入中途失败时，已有成功组不会回滚。
- `tools/build_earth_hex_map.py:234` 用手写经纬度矩形近似地形，`:393` 按大陆分区，`:465` 的 CLI 没有局部经纬度裁剪入口。适合全球轮廓演示，尚非战区地形数据管线。
- 未提交的 Java 环绕已接入寻路和地形重分块，但 Python 导入器的 `terrain_blocks_from_map`（`:244`）仍用平面邻接，区域边界也用平面几何。全球接缝尚需统一；局部非洲图可以先用无环绕平面格网。

正式 world 登记仍只有 v17levant、small-world、corridor、three-powers（`WorldRegistry.java:135`）；没有非洲军阀场景。

## 5. 双 AI 运行的实际边界

### 已有可以复用的零件

`DecisionAgentService.java:266` 按决策人的 provider 启动 runner，`:309` 调模型并保存决策包；会话使用 SQLite。`RunDecisionMakersTool.java:572` 支持多人基于同一触发快照冻结决策。GM 决策包工具、合并计划、receipt 和领域写入已存在，外部推演 Agent 可经 MCP 驱动。

军事实体和战役阶段工具也存在。`ResolveCombatPlan.java:512` 把伤亡工单与装备变更同批回写；Social 工单在 `SubmitHouseholdWorkOrderHandler.java:99` 即时应用，因此人口时间参与者停用不等于无法处理战损。

### 仍缺的连接与长跑边界

1. **没有持续回合编排。** `RunDecisionMakersTool.java:128`–`:143` 只跑显式名单，due 是展示值；`GaTimeParticipant.java:167` 和 `:284` 处理既有效果/INFO/排队单位命令，不调用 LLM。`ArmyTimeParticipant.java:121` 扫描接触并产出 incident，不自动决定开战、伤亡和控制权。需要明确由外部 GM Agent 还是新增宿主编排器反复执行 A2–A4。
2. **旧“开始决策”入口不是完整多人推演器。** `DecisionAdjudicationService.java:257` 仅选按 ID 排序的首条交战记录；`AdjudicatorRunner.java:132`–`:145` 在 verdict 提交被拒/冲突时返回旧 revision，而上层仍可能拿到模型 Accepted。此项为源码核查，未实机注入冲突；应以真实提交结果区分“模型同意”与“世界已写入”。
3. **分叉裁决面仍绑 main。** `SimosToolSource.java:1629`–`:1643` 给 AgentLib 五个 GM 决策包工具注入的读、写、审计口硬编码默认分支。DM 可以指定分支运行，但整套 GM 面不能据此宣称支持分叉局势。此项为源码核查。
4. **任务状态与会话策略不足以证明数年可靠性。** `DecisionRunRegistry.java:15` 的运行状态仅在内存；`DecisionAgentRunner.java:1316` 加载完整会话，`:1488` 原样组装上下文；`:1458`–`:1469` 未启用墙钟/token 配额，`:1547` 只检查调用轮数。需要持久化轮次、失败阶段和 receipt，约定上下文与预算策略；不得自行覆盖已有用户对限额的裁定。

## 6. 本轮验证及其限度

聚焦命令：

```bash
tools/gate.sh focused simos-app \
  -Dtest=MapWrapTest,MapCodecTest,PathFinderTest,ShellAssemblySmokeTest,ShellMainGenesisTest,DecisionAgentRunnerTest,DecisionPackagePersistenceTest,AdjudicationEndToEndTest,TimeParticipantWiringTest,CommandDrainTest \
  test
```

结果：rc=0，35 秒；10 个指定测试类合计 **59 tests / 0 failures / 0 errors / 0 skipped**；前端门禁 **429/429**。这是 FOCUSED TEST PASSED，未跑 full gate；这些模型测试不等于真实模型验收。

另在 `/tmp/simos-20261114-audit-ozh8zn4c/` 运行独立 Python 导入探针与 Java 进程。Java 的 classpath 优先使用本轮 reactor 编译的各模块 `target/classes`，shaded jar 只提供后续依赖，避免以旧 jar 冒充当前源码。未改写 jar、用户已有库或仓内运行配置。

SmallWorld 在 editor 下 **0 → 1095 天**推进成功，经济 data 逐字段相等，关闭 Shell 后重新打开仍为 tick=1095/head=3。该样本未调用真实决策人模型；177ms 的推进时间只反映这份小世界的固定参与者工作，不能外推真实推演性能。

导入探针：`python3 /tmp/simos-20261114-import-probe.py`。Java 探针：编译 `/tmp/SimosAuditProbe.java` 到临时目录后运行 `io.mosire.simos.app.SimosAuditProbe <临时根目录>`。临时脚本、完整运行日志和新建 SQLite 均保留在 `/tmp`；关键结果已固化为本报告旁的 evidence JSON，临时文件可能随机器清理而消失。

dsh 三次只读任务均因 `deepseek-official` 路由 `MISSING_CREDENTIAL` 在推理前退出；随后使用本会话三个只读子 Agent 完成地图、AI、局势调查，控制方集中复核和运行。未读取、复制或输出密钥。

## 7. 最小推进顺序与验收门槛

| 顺序 | 工作 | 完成判据 |
|---|---|---|
| 1 导入可信 | 统一当前格式，拒绝已有世界，先完成临时输出再发布 | 导入成功后真实启动/重启；二次导入被拒且旧库/checkpoint 字节不变 |
| 2 无经济建局势 | 拆除 worldgen/建 GOV/募兵对暂停域的强制提交，裁剪 editor 创世播种；修启动脚本与失败退出码 | editor 新图能建政府、人员、军队、决策人；无经济命令调用；失败不报成功 |
| 3 做一张局部非洲图 | 选定源图、年代、范围与 hex 比例，固定地形/区域 ID；制作可重复生成的局势输入 | 地形和通路人工抽检，实体/归属/外交/决策人/provider/初始文本齐全，重建结果一致 |
| 4 打通真实一轮 | 两个派系决策 → 推演 AI 裁决 → 世界写入 → 推进一步 → 重启；统一批节点语义与回执 | 真模型、真命令、存档及回读互证；失败与重试不重复执行 |
| 5 延长运行 | 持久化回合阶段、任务恢复、分支选择、上下文和预算，再扩大轮次 | 先多轮短局势，再跨季/跨年；每轮能解释“谁决定、谁裁决、世界怎么变” |

修复应按现有目标运行设计落子设计后派实现任务。本表是调查给出的工作排序，尚未实施，也不替代用户对地图/局势内容的选择。

## 8. 未做与未验证

- 未修改生产代码/测试，未修复上述问题，未 commit/push。
- 未执行本轮 full gate，未全面审计经济可靠性，未恢复暂停域。
- 未调用真实决策人或推演模型，未建立非洲正式世界，未证明多年双 AI 自主推演成立。
- 未做本轮真实浏览器交互验收、全球接缝地理保真、进程异常中断恢复及领域 schema 迁移验证。
- 本轮不将缺失的地图内容当作要求立即澄清的阻塞；导入保护、editor 场景工具与启动链的缺陷已经可以据此继续设计和修复。
