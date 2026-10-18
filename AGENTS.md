# AGENTS.md —— 项目简介 + 文档索引（常驻；长文见 `docs/agents/`）

> ★ **2026-10-13 起本文件只做「项目简单介绍 + 文档索引」**（用户裁定：「AGENTS.md只做一个项目简单介绍和文档索引」）。
> 原先 123 KB 的纪律长文已**整节逐字**搬进 `docs/agents/*.md`（一节一个文件，正文一字未改、未重排）；
> 本文件只常驻必需项：**五条铁律 · 模块边界表（只列允许依赖）· 文档索引 · 交叉纪律要点**。
> ★ 本文件是**索引不是全集**：细节一律以 `docs/agents/*.md` 的原文为准；**冲突时以本文件（常驻口径）为准**。

## 一、这是什么

**SimulatorMosire（simos）** 是 GSimulator 的重构：把原本单一功能的地图推演工具，解放为**可分模块生长**的模拟引擎。
- 参考项目 `~/DevMosire/GSimulator`（**只作参考、不作依赖**）与 `~/ProjectMosire/AgentLibMosire`（CoreSimos 整包依赖）。
- 形态：Maven 多模块（根 pom **18 个模块** = 17 个 `simos-*` 领域/契约/编排模块 + 组合根 `simos-app`）；
  领域模块只拥有自己的数据（`MapSimos` 不知道 `SocialSimos`/`UnitSimos` 存在），Core 只组合与调度。
- 权威顺序：**用户原话 > 用户设计原稿 > 最新落盘文档 > 本文件 > 旧文档**（口径细则见 `docs/AGENTS.md`）。
- 「当前状态」**不在本文件抄写**：看 `git log --oneline -20`、`.superpowers/sdd/*/progress.md`，
  以及最新未完成清单 `docs/superpowers/status/2026-10-13-outstanding-work-survey.md`。

## 二、五条铁律（不可协商；原文照抄，必须常驻）

1. **所有查询最终解析为稳定实体。** 地址是定位方式，**ID 是身份**。单位调动、区域改名，历史与 Info 都不断。
2. **所有修改最终表示为 `Command → ChangeSet → Revision`。** 不存在绕过该路径的写入口。
3. **所有领域模块只拥有自己的数据。** MapSimos 永远不知道 SocialSimos / UnitSimos 存在。
4. **Core 只负责组合与调度**，不重新实现领域逻辑。
5. **变更集从完整状态类型派生**，且有**往返不变式测试**守卫：`apply(changeSet, base)` 逐字段重建 target。

> 铁律 5 的由来（GSimulator `MapDiff` 手工对着 `MapData` 维护、四字段漂移、对非 root 节点写连通性静默丢失
> —— 本项目最贵的教训）→ 详见 `docs/agents/01-〇-铁律与模块边界.md` §五条铁律。

## 三、模块边界表（只列允许依赖；长注在长文里）

> 下表**只保留「模块 | 允许依赖」两列**；原表第三列（要点）与单元格内的 ★★ 长注（冻结批 / 回 pom 更正 / 闭合记录），
> **原文整节搬在** `docs/agents/01-〇-铁律与模块边界.md`（下文简写 **`01-〇`**）**的同名行**，此处只留指针 ——
> 本文件**不重复那些长注的原文**。
> 边界由 `maven-enforcer-plugin` 的 `bannedDependencies` **在构建期强制**（越界 = 构建失败，不是 code review 的事）；
> 2026-10-12 闭合后禁列 = **全仓 simos 模块 − 自己 − 允许面，零例外**，且各模块加了
> `<searchTransitive>false</searchTransitive>` ⇒ **只拦"本模块直接声明"**，传递可见不算越界。

| 模块 | 允许依赖 |
|---|---|
| `simos-util` | 仅 Jackson（databind + datatype-jdk8）+ SLF4J（★ 详见 `01-〇` §simos-util） |
| `simos-map` | `simos-util`（★ 详见 `01-〇` §simos-map） |
| `simos-calendar` | `simos-util` + `simos-map`（★ 详见 `01-〇` §simos-calendar） |
| `simos-social-api` | `simos-map`（+ jackson-annotations）（★ 详见 `01-〇` §simos-social-api） |
| `simos-social` | util + map + `simos-social-api` + economy-api + `simos-calendar` + **`simos-ga-spi`**（★ 详见 `01-〇` §simos-social） |
| `simos-unit` | util + map + `simos-social-api` + `simos-social`（实现）+ **`simos-ga-spi`**（★ 详见 `01-〇` §simos-unit） |
| ~~`simos-sd`~~ | ——（已整模块退役；★ 详见 `01-〇` §simos-sd） |
| `simos-core` | **main scope**：util + `agentlib-mosire` + jackson-databind + sqlite-jdbc（map/social/unit 仅 test scope；★ 详见 `01-〇` §simos-core） |
| `simos-actor-api` | **不声明任何主依赖**（★ 详见 `01-〇` §simos-actor-api） |
| `simos-actor` | actor-api + util + map + economy-api + `simos-social-api` + jackson（★ 详见 `01-〇` §simos-actor） |
| `simos-economy-api` | **actor-api** + util + map + `simos-social-api`（★ 详见 `01-〇` §simos-economy-api） |
| `simos-economy` | actor-api + util + economy-api + **map** + `simos-social-api` + `simos-social` **+ `simos-production`**（只读 contract 包；★ 详见 `01-〇` §simos-economy） |
| `simos-production` | `simos-util` + `simos-map` + `simos-economy-api` + **`simos-ga-spi`**（工具契约；★ 2026-10-16 用户纠正，详见 `01-〇` §simos-production） |
| `simos-culture` | `simos-util` + `simos-map` + `simos-social-api` + `simos-economy-api` + `simos-production`（**只读 contract 包**；★ 详见 `01-〇` §simos-culture） |
| `simos-gov` | util + map + unit + social + `economy-api` + **`simos-ga-spi`**（★ 详见 `01-〇` §simos-gov） |
| `simos-army` | util + map + unit + social **+ gov**（★ 详见 `01-〇` §simos-army） |
| ~~`simos-ledger`~~ | ——（已于 2026-09-27 退役；★ 详见 `01-〇` §simos-ledger） |
| `simos-app`（组合根） | **全部 17 个 simos 模块**（core + util + ga-spi + map + calendar + social-api + social + unit + actor-api + actor + economy-api + economy + production + culture + gov + army + **ga**）+ agentlib + mcp-core + mcp-json-jackson2 + jackson + 日志实现（不设 enforcer；★ 详见 `01-〇` §simos-app） |
| `simos-ga-spi` | `simos-util` + `agentlib-mosire`（★ 详见 `01-〇` §simos-ga-spi） |
| `simos-ga` | `simos-util` + `simos-ga-spi` + `map` + `unit` + `agentlib-mosire`（★ 详见 `01-〇` §simos-ga） |

## 四、文档索引（★ 不要用 `@` 导入——导入会在启动时把文件展开进上下文，白载 5000+ 行）

**① 纪律长文（`docs/agents/`，2026-10-13 整节搬出；原文逐字未改）**

| 文件 | 覆盖什么 |
|---|---|
| `docs/agents/00-序言-改名与常驻说明.md` | `AGENT.md` → `AGENTS.md` 的改名理由、`CLAUDE.md` 退役、历史留痕里的旧名怎么读 |
| `docs/agents/01-〇-铁律与模块边界.md` | 这是什么 / 五条铁律 + 由来 / **模块边界表全文（含 ★★ 长注与要点列）** / 文档地图 |
| `docs/agents/02-一-并发.md` | §一 并发 · §一.5 派单粒度 · §一.6–一.7 子代理等待/前台 · §一.8 约束设计书 · §一.8.1 原话附录 · §一.9 日志 · §一.10 责任区委派 · §一.11 Alpha 不兼容旧档 · §一.12 口径溯源 |
| `docs/agents/03-二-产物与jar.md` | 别覆盖在跑的 jar；`-Dshade.skip` 无开关；服务在跑时用 `./mvnw test` |
| `docs/agents/04-三-验证.md` | §三 护栏自证、不许把"没跑"写成"通过"；§三.0 测试与变异自证留到最后 |
| `docs/agents/05-四-台账与代码核对.md` | §四 机制性描述回代码核；§四.1 最新落盘文档为准；§四.2 调查可提设计修改 |
| `docs/agents/06-五-提交与协作.md` | §五 提交 / 推送 / 留痕 / 密钥纪律 |
| `docs/agents/07-六-前端.md` | §六 前端（无 npm、无打包器、纯 `<script>`） |
| `docs/agents/08-七-门禁与关账命令.md` | §七 门禁与关账常用命令（`tools/gate.sh` 等） |
| `docs/agents/09-八-环境与运维.md` | §8.1–8.7 起服务判活 / 世界数据两层 / MCP 审批链 / 图片通路 / 编制与移动 / 换设备自检 / 18Lvt 测试世界 |
| `docs/agents/10-九-实测失败形态.md` | §9.1–9.5 判别力假货、计划数字是预测、夹具与不变量冲突、工具假阴性、技能不是判据 |
| `docs/agents/11-十-用户设计权威.md` | §十.1 用户设计 = 最终版本 · §十.2 中央不设全视野 · §十.3 待调查清单更新规则 |
| `docs/agents/12-十一-运行时操作原则.md` | §十一 直接用工具、不先读代码；有异常只记录 |
| `docs/agents/13-十二-推演Skill文档规则.md` | §十二 推演 Skill 的文档规则（★ 它所指的**运行期 Skill 机制已于 2026-10-13 取缔**，本文只作历史留痕与用户裁定记录；取代者见 `.agents/notes/implemented/architecture/2026-10-13-retire-runtime-skill-system.md`） |
| `docs/agents/14-十三-战损概率表先审后录.md` | §十三 概率/战损表先审后录、掷点可复现、错误落库后的回滚口径 |
| `docs/agents/15-十四-目标运行设计与子设计嵌入.md` | **目标运行设计先行**：先写模块实际运行顺序，再写裁定/子设计嵌入与影响；模块/类型细化规范；责任区子 Agent 的中期设定也必须遵循 |

**② 文档系统与台账形制**

- `docs/AGENTS.md` —— 文档系统本身的用法：**六层归宿表** · 口径优先级 · 「取代关系」怎么写 · `file:line` 与"生成命令"要求 · **日期口径规则**。
- `.superpowers/AGENTS.md` —— **SDD 台账/证据的落点与形制**：`impl-ledger.md` 落 `.superpowers/sdd/<topic>/`，`evidence/` 同目录，**该目录被 `.gitignore` 的 `*` 忽略（不进 git）**。
- 决策记录 → `.agents/notes/{proposed,implemented,archived}/<分类>/YYYY-MM-DD-<slug>.md`（为什么这么定、被谁取代）。
- 技能 → `.agents/skills/<name>/SKILL.md`（**跨运行时**：Codex / Copilot CLI / Gemini CLI 也认）；
  当前项目技能：`.agents/skills/target-runtime-design/SKILL.md`（目标运行设计 + 子设计嵌入 + 责任区中期设定）。
- 机械强制 → `tools/gate.sh`（门禁组合 · `-pl` 强制 `-am` · `git add` 白名单）。

**③ 关键长文（既有，本批未搬动）**

| 文档 | 内容 |
|---|---|
| `docs/superpowers/specs/2026-09-16-simos-master-design.md` | **总纲**：五模块边界、八大件原语、两层地址、两阶段时间推进、存储分层、里程碑（已批准） |
| `docs/superpowers/plans/2026-09-16-simos-master-plan.md` | **实现计划**：M0 分解 + M1~M6 路线图 |
| `docs/superpowers/specs/2026-10-02-architecture-design-source.md` | **用户设计原稿（D-xxx）＝ 最高权威** |
| `docs/superpowers/specs/2026-10-17-d032-production-execution-decision.md` | **D-032 补充裁决（已记录，未实现）**：D3 直接删；D4 归 production，提供预计/实际生产及状态变化能力，economy 处理经济关系；附原话及工程边界（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-production-runtime-boundary-decision.md` | **D-032 工程裁决（已定，未实现）**：实例/进度/经验/产出覆盖归 production，分配/经营状态留 economy；允许临时只读视图；到货、铸币与账户提交边界明确，接续设计无需重复询问工程分工（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-stage1-deviation-review-decision.md` | **D-032 阶段 1 偏离复核（已定，修正未实现）**：方案多地复用不降级；熟练度不能偷换成只加劳动；经验须结转、逐 tick 增长，整周期贡献不能只看收获日；取代迁移设计 §12 对应处置（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-stage2-handoff-review-decision.md` | **D-032 阶段 2 交接复核（工程已定，集成待验）**：接受 A/B 重排；统一 tick 生效条件，批次故障零半提交，允许不可变回执，旧无效构造器须收口；补充/修正迁移设计 §13（2026-10-17） |
| `docs/superpowers/reports/2026-10-17-d032-final-independent-review.md` | **D-032 最终独立审核（发现待修复问题）**：预计/实际脱节、共享资产重复占用、执行绕过条件、零经验迁移平均、原始经验公式顺序依赖；含最终 JAR 独立复现及验证限度（2026-10-17） |
| `docs/superpowers/reports/2026-10-18-d032-original-intent-audit.md` | **D-032 按用户原始口径审核当前实现**：缺工调价未进日循环、合作试算经验/雇工劳动断路、角色所得选业待接、抵物初始顺序偏差；附独立复现、已落地范围及绿色构建的验收边界（2026-10-18） |
| `docs/superpowers/reports/2026-10-17-d032-production-line-replan-review.md` | **D-032 重规划 v1 审核（历史）**：旧公式复活、资产效力、写面与验收依赖冲突；后续 v2 已处置，见 v2 复核（2026-10-17） |
| `docs/superpowers/reports/2026-10-17-d032-production-line-replan-v2-review.md` | **D-032 重规划 v2 复核通过**：可冻结规划基线、继续详细设计；经验归一化为可配置工程默认、R2 仍未修，未验收 F1/F2 实现（2026-10-17） |
| `docs/superpowers/reports/2026-10-17-d032-d3-integration-precheck.md` | **D-032 D3 衔接预审与 U22 bug-first 决定**：§5 顺序为 R2 必需最小设计→修复→独立验证；工资三项后由 U23 裁定，原衔接问题保留，未验收实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-wage-idle-barter-decision.md` | **D-032 U23 最新工资裁决与模块边界**：欠薪零息、按下一 tick 生存条件裁决不从业、抵物折价可更低且可配置；production 定规则、economy 判定/估值/结算，Social 管生存需求与劳动，未实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md` | **D-032 D2 合作生产与分配约束设计**：参与名册、按户预计所得、角色两层分配、资格主体、经验/迁移、C2 状态/命令/ChangeSet/codec；U23/裁决列已并入，未实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md` | **D-032 D3 工资链与劳动匹配约束设计**：U15–U23、报价 default/posting、U20 调价、角色级匹配/idle、W1 工资前置、W3 直付/抵物/成债、W2a→W1→W2b→W3；未实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md` | **D-032 D4 资产与占用完整设计**：OwnershipStake location、AssetOccupancyGrant/InventoryReservation（36/37）、统一预算/跨周期/释放回填、R2 关闭路径、W3 lookup；未实现（2026-10-17） |
| `docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review-5.md` | **D-032 D1–D4 终局设计复核通过**：P0/P1/P2=0；D4-C 接口、组件 32–37、W1/W2b/W3 revision/location 边界已冻结；仅设计层，未实现（2026-10-17） |
| `docs/superpowers/reports/2026-10-17-d032-d3-precheck-adjudication.md` | **D-032 D3 独立复核与工程裁决列**：时间尺度、idle、候选 default、降价边界、产品池措辞、W1/W2/W3/D4 衔接；未实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-cooperative-production-proposal.md` | **D-032 多家户合作生产提案（待用户修改/确认）**：现有多人劳动/分账事实、共同经验、角色分配模型、参与名册、资格主体与本地资产占用建议；未实施（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-distribution-wage-offer-proposal.md` | **D-032 产品分配/现金与抵物/自主工资报价调查提案**：地主权益不要求下地劳动；角色经验量独立配置；工资报价、家户比较与本地反馈机制待确认，未实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-wage-settlement-simplification-decision.md` | **D-032 工资简化裁决**：直接支付、库存不足成债、再生产权益不入活动库存仍有效；定价公式建议已被后续选业前定价分析取代，未实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-preselection-wage-feasibility-analysis.md` | **D-032 选业前雇工定价历史分析**：工资前置入候选成本仍有效；工资下限解释由后续地主主动出价重设计收窄，未实现、未仿真（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-employer-posted-wage-redesign.md` | **D-032 地主主动出价历史重设计**：零/极低工资及非正收益过滤偏差；必须从业前提与当 tick 求价已由后续闲置/逐 tick 反馈方案调整，未实现（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-tick-wage-feedback-proposal.md` | **D-032 工资逐 tick 反馈（最新裁定见 §11）**：快速累加加价、缓慢加速降价；欠薪不影响择业、禁止预付，先钱后按活动库存数量降序抵付，余款成债；未实现、未仿真（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-production-runtime-migration-design.md` | ★ **D-032 约束设计书（可派单，未实现）**：状态归属落形、方案/经验条/受限表达式公式、试算·执行两步契约、同 tick 原子提交（含 actor 账户与铸币权威收口）、不变量 I1–I16、验收 V1–V12、责任区与迁移面重算命令（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md` | **D-032 最新生产设计（仅记录，未实现）**：家户经验与共用经验、公式可配置、解锁、需求—利润选择后派生主副业、通用拆合；取代文化直接加生产倍率的方向（2026-10-17） |
| `docs/superpowers/specs/2026-10-17-d033-parish-household-faith-design.md` | **D-033 文化/宗教调查与规划（草案，未实现）**：独有家户文化记录、地方教区、奉献与税分离、真实利益建议、共享流动表上的择优信奉；源码事实与建议分列（2026-10-17） |
| `docs/superpowers/specs/` 与 `plans/` 下的其余按日期文件 | 各阶段的 spec/plan：M1 util、M2 map、M3 social+unit、M4 core、M5 shell、M6 导入器、M7 webui、M8 地图编辑、M9 大图性能、M10 部署、Unit 扩容、SDSimos、工具面… |
| `docs/superpowers/status/2026-10-16-work-state-after-gm-dm-tools.md` | ★★ **最新存档与交接（下次开工的唯一入口）**：批 K→V 六批全入账 · 门禁基线 3356/0/0/7 · ★ **`stash@{0}` 里未落盘的改动与恢复方法** · 环境事实（本地 LLM / 项目级 Maven 锁 / 三条工作树）· 今晚 9 条新纪律 · **待办 A–G 与待用户裁决** · **未验证/没做**清单（2026-10-16） |
| `docs/superpowers/status/2026-10-16-d031-p1-worktree-progress.md` | **D-031 P1侧线恢复入口**：迁移工作树、P0基线、实现/测试证据、当前进度与未完成项；状态以正文为准（2026-10-16） |
| `docs/superpowers/status/2026-10-16-d031-interface-first-result.md` | **D-031 最新状态**：文化/生产与科技 P0 接口、状态、命令及中性接线已实现；主树完整门禁 3422/0/0/7、前端 412/412；定义迁移/真实效果/共享流动表仍待后续；已同步主树，未提交（2026-10-16） |
| `docs/superpowers/status/2026-10-15-gm-dm-tool-classes-progress.md` | **上一段存档**：GM⇄DM 轮次协议 + 工具三分类（3a/3b）+ 两个同源缺陷 + planOnly 双写；含**未验证清单** / 下一步 / 口径要点 / 复跑命令（2026-10-15） |
| `docs/superpowers/status/2026-10-13-outstanding-work-survey.md` | **最新未完成清单**（口径以此为准） |
| `docs/superpowers/status/2026-10-12-known-issues-code-verified.md` | **回代码核实版**：54 条说法逐条判定 + 16 处文档更正 + 好/没好分档 |
| `docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md` | "计划内未实现"总清单（**其经济部分已被上一份更正**） |
| `docs/archive/handoff/HANDOFF-*.md` | 跨会话进度存档（**2026-10-13 归档，勿据此开工**；机制上已被 `docs/superpowers/status/` 与 `.agents/notes/` 取代） |
| `.superpowers/sdd/<topic>/` | SDD 台账与逐任务证据（`progress.md` / `task-N-report.md` / 各 `*-evidence/`） |

## 五、交叉纪律要点（每条只是概述 + 指针；**原文一律在长文里**）

1. **并发（★ 最容易返工）**：★★ **每棵工作树有各自的 Maven 本地仓库**（`tools/mvn-lock.sh` 首次用时
   从 `~/.m2/repository` 用 **hardlink 播种**到 `<树根>/.m2-repo`；实测 8267 文件 / 真实占用 **11 MB**）
   ⇒ **各树可以【同时】编译，不再互相排队**（用户裁定 2026-10-16：「改成那种能各自跑编译的类型」）；
   ★ 锁退化为**每棵树一把**：同树内仍串行（防两轮同抢 `target/` 让计数失真），跨树并行。
   ★★ **副产品**：新树的仓库里**没有本项目自己的构件** ⇒ 「`-pl` 不带 `-am` ⇒ 验的是 `~/.m2` 里的旧字节码」
   这条陷阱**结构上不可能再发生**（会响亮失败，而不是静默测旧件）。
   **一个文件一个 owner**，动手前先 `git status --porcelain`，看到半成品尾巴**不要接手**；
   不要在别人跑 Maven 的窗口里改 Java。
   → 详见 `docs/agents/02-一-并发.md` §一 / §一.13
2. **派单粒度**：**一个责任区 = 一个写代码代理**（一次写完该责任区全部生产代码，**只写到"编译过"**，不写不跑测试）；
   **测试最后单独一个测试代理**统一盯；**控制方不自己写代码**（只做调查综合 / 约束设计 / 派单 / 验收 / 提交）。
   → 详见 `docs/agents/02-一-并发.md` §一.5
3. **约束设计书（派实现代理的前置）**：控制方先把"世界必须长成什么样"落盘（需求来源与用户原话 / 现状 `file:line` /
   接口·载荷·数据流与所有权边界 / 不变量清单 / 验收判据 + 负向用例 / 文件所有权）；任务书里引用路径 + 小节号并**原样复述关键形状**；
   **没有约束设计书就派实现 Agent ⇒ 派单无效**，子 Agent 有权拒绝。实现方自己出「实现架构账本」。→ 详见 `docs/agents/02-一-并发.md` §一.8
4. **用户原话逐字收录**：用于派单的开发计划/架构设计文档必须有独立小节「**用户原话附录**」，**逐条原样照录**（含日期；不改写、不缩写、不意译）；
   子 Agent 若认为设计与**用户原话/红线**冲突 ⇒ **立即上报**，控制方必须转问用户，**不得代替用户裁定设计**。
   → 详见 `docs/agents/02-一-并发.md` §一.8.1
5. **责任区委派六要素**：① 问题现场（`file:line` 已确认事实）② 责任归属 ③ 最终能力 ④ 不可破坏机制 ⑤ 可动范围 ⑥ 交账方式；
   **明确不要求**逐文件施工图 / 固定实现计划；做不到 ⇒ **BLOCKED，不是 DONE**（"写进风险文档/未完成项"**不能替代**验收判据通过）。
   → 详见 `docs/agents/02-一-并发.md` §一.8 / §一.10
6. **Alpha 不兼容旧档**：旧存档 / 旧格式**一律作废**（直接重建，不写迁移、不写键归一、不写兼容位）；
   "旧档读不回来"**不是缺陷**、不许记 bug；唯一要守的是**同一版本内自洽**（铁律 5 往返不变式）。
   → 详见 `docs/agents/02-一-并发.md` §一.11
7. **口径溯源**：向用户提任何"待裁定"之前，**必须先做口径溯源并落盘**（specs / plans / status / 本文件 —— 尤其各文档开头的「用户原话附录」）；
   已有裁定**不得重复问**；**没有溯源表 = 提问无效**。→ 详见 `docs/agents/02-一-并发.md` §一.12
8. **日志纪律**：先写设计文档再派实现；每模块一个 `XxxLog` 门面（logger 名唯一拼写点），INFO/DEBUG/TRACE 分档；
   新增/修改经济阶段必须同时加日志；**业务拒绝 = INFO**、**契约/跨切片一致性故障 = ERROR 不降级**、逐笔明细 = TRACE。
   → 详见 `docs/agents/02-一-并发.md` §一.9
9. **产物**：重建产物前**先停服务**（覆盖运行中的 jar ⇒ 惰性加载 `ClassNotFoundException`、静态资源 500）；
   服务在跑时验代码用 `./mvnw test`；`-Dshade.skip=true` 会被**静默忽略**。→ 详见 `docs/agents/03-二-产物与jar.md`
10. **验证**：**护栏必须自证**（变异/负向验证：改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5，做不到就如实说"等价存活"）；
    **不许把"没跑"写成"通过"**；测试与变异自证**留到最后统一做**。→ 详见 `docs/agents/04-三-验证.md`
11. **提交与关账**：不擅自 `git commit`；提交/推送/留痕/密钥纪律 → 详见 `docs/agents/06-五-提交与协作.md`；
    关账跑 `tools/gate.sh`（门禁组合 · `-pl` 强制 `-am` · `git add` 白名单）→ 详见 `docs/agents/08-七-门禁与关账命令.md`。
12. **目标运行设计先行（2026-10-19 用户裁定）**：改任何模块/类型前，先读该主题的「目标运行序列」；
    派单/设计/中期确认文档必须先写“目标世界里代码实际怎么跑”（阶段、顺序、读写、所有权、失败、日志/测试锚点），
    再写裁定、子设计嵌入与影响；**责任区子 Agent 写中期设定也必须遵循**。
    缺目标运行设计/影响表 ⇒ 派单无效。→ 详见 `docs/agents/15-十四-目标运行设计与子设计嵌入.md`
    与 `.agents/skills/target-runtime-design/SKILL.md`。
