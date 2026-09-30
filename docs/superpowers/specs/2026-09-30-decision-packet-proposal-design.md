# 决策人提案与裁决设计（草案，待确认）

> 日期：2026-09-30
> 状态：**已确认**（2026-09-30 用户裁定「就这么办」）；阶段 1 已按本文件 §8 派单执行
> 说明：本文件只记录设计，不含实现任务分解
> 来源：2026-09-30 与用户的设计讨论；现状描述均为回代码核过的（引用类名/行为，不是台账转述）
> 关联：`docs/superpowers/plans/2026-10-01-direct-replacement.md`（classfirst 替换）、`docs/superpowers/status/2026-09-30-class-first-economy-status.md`

---

## 0. 一句话

**执行永远是 GM 的；决策人只负责把"哪把工具 + 什么参数 + 对谁/哪里 + 为什么"填成一张完整的调用单。**
GM 按 `true/false` 签发格式化命令；冲突时走第三态，由 GM 显式排序、把多条不同决策人的格式化命令合并成一份有序的实际效果集。

---

## 1. 现状（代码实测）

| 要素 | 现状 | 设计要动的点 |
|---|---|---|
| 决策人可见范围（读） | `DecisionScopeFunctions` 注册表：`NationScope`=本国区域 tag、`ArmyScope`=位置+视野半径（map/social/unit 三维）；GM 的 `AccessLimit` 只能收紧（`narrowTo`） | 读侧沿用；辖区粒度下一份计划再细化 |
| 决策人可用工具 | `DecisionCallerFactory.WHITELIST` 是**静态全局**（读工具 + `sd.IssueDirective` + `sd.SubmitVerdict`）；`DecisionMaker.allowedTools` 只进决策简报与 GUI，**未进权限判定**（权限组与给 LLM 的工具面都用全局表） | `allowedTools` 变成**拟稿白名单**，在拟稿期强制 |
| 决策人怎么写 | 唯一写 = `sd.IssueDirective`（载荷 `commands[]` 是 `{type, payloadJson}` 的不透明命令串） | 格式化命令改为按真实工具 schema 填的 `FormattedCall`；自然语言 intent 单独留轨 |
| 谁执行 | `sd.AdjudicateTick` 代执行；命令信封用**出令决策人身份**，按决策人可达面授权（"GM 代执行也不能越权"） | 执行者是 GM（initiator=GM）；决策人只作 proposer 留痕 |
| 审批 | 决策链 `AutoApproveGate → ConfirmGate → PendingApprovals → HTTP`（GUI「决策→审批」）；GM/MCP 链 `GmAutoApproveGate` 无脑过 | 沿用审批链；审批对象改成"某个决策人的决策包" |
| 目标声明 | `CommandTargets` 由 handler 自己实现；未实现的一律 fail-closed 拒。economy 域目前只有 `economy.Seed` 实现 | 新经济命令必须实现 `CommandTargets` |
| 结果与理由 | `sd.PutInfo`（`SdInfoEntry`，`tags` 打给决策人；决策人经 docs 读）；`AdjudicationStatus` 支持生效/作废裁决；`VoidAdjudication` 可回滚重裁 | 拒因/通过理由都走 INFO；合并效果集另立实体 |

---

## 2. 决策包（DecisionPacket）

一个决策人 × 一个世界 tick = 一包，**落盘成世界状态**（可回放、可审计）。

```
DecisionPacket
├─ id / branch / tick
├─ proposerId                  ← 提案人（硬字段；日志另留一份）
├─ intent                      ← 自然语言命令（NL 轨；沿用现有 intentInfo 形态）
├─ calls[]                     ← 格式化命令
│    ├─ tool（真实工具名）
│    ├─ argsJson（拟稿时冻结，逐字节）
│    ├─ targets[]（CommandTargets 抽出的目标）
│    ├─ preview（变更集 diff / 影响摘要）
│    ├─ draftChecks（拟稿期校验结果）
│    └─ status：PENDING / APPROVED / REJECTED / MERGED
└─ 包级状态（按 calls 聚合；NL 轨独立）
```

规则：

1. **格式化命令 = 已注册、有 schema 的 GM 工具调用**（工具名 + JSON 参数）；自然语言轨道保持自由文本。
2. **拟稿期校验**：工具在允许清单内、目标在辖区、参数在上下限、schema 合规；不过就不生成调用单（fail-closed）。
3. **GM 审批默认路径**：按决策人查看整包（NL 原文 + 全部格式化调用的参数/目标/预览），对格式化轨**一次性 `true/false`**；NL 轨不进这个布尔，由 GM 手工处理执行效果。
4. **`true` 的执行**：批准的格式化调用在裁决批里**逐字执行**（GM 身份、proposer 留痕），不重填参数、不偷偷改。
5. **`false` 的处置**：不执行；**写拒绝理由进 INFO**；本 tick 不得重提，决策人等下一回合（自己错的自己扛，GM 不做代改）。
6. **通过也可补理由**：`true` 时可附一条 INFO 理由（可选）。

---

## 3. 第三态：MERGED 与合并效果集

`MERGED` = 这条格式化命令不走机械执行，转交 GM 裁量——因为**同 tick 多个决策人的命令可能冲突**。

```
MergedEffectPlan（新一等实体，落盘、可回放）
├─ tick / branch / 参与决策人清单
├─ orderedEffects[]：GM 拍板的命令序列（每条 = 命令信封；GM 可调参数、定顺序、只取部分）
├─ sources[]：每条效果引用哪些原提案（proposerId + packetId + callIndex）
│              ——或显式标 gmAdded=true（GM 自己补的效果，不留无源效果）
├─ reasonInfoId：GM 的裁量理由与结论（INFO，自然语言）
└─ outcome：逐条 applied/rejected + 受影响资源的合并前后实际变化（"实际效果集"）
```

- **执行**：一份计划 = 一条 revision 原子批（复用 `CoreSimos.submitBatch` 与现有收敛循环）。
- **连续**：单 tick 内 `orderedEffects[]` 按序连续施加，得到一张合并后的实际效果集；跨 tick 就是每次裁决的效果集首尾相接成链，改判走 `VoidAdjudication` + 重裁（现有机制）。
- **原提案不动**：MERGED 不修改原 call；合并集是新对象，用 `sources[]` 溯源 ⇒ 提案人、原始参数、审批链全查得到。
- **失败处置分档**：预检能判的（越权/守恒/参数/引用）拟计划时就拒；执行时才暴露的（并发、引用消失）**退回 GM 重裁**，不静默丢一条 GM 手写的效果（与现有"自动剔除被拒命令"的唯一语义差别）。

### 冲突检测与排序（用户裁定）

- 代码只做**提示**：目标重叠（`CommandTargets`）、同工具/同参数冲突等表面检测；**不做自动语义裁量**。
- **GM Agent 必须自己调查**：GM 有充足时间与义务看完同 tick 所有决策人的决策，再决定推演/执行。
- **排序必须是 GM 显式排序**（合并集里的 `orderedEffects[]` 顺序由 GM 写定，系统不自动定序）。

---

## 4. 权限与范围（charter）

- **本轮（第一份计划）**：charter 由 GM 直接传——允许的工具清单 + 辖区前缀 + 参数上下限；落成决策人字段，拟稿期强制。
- **`allowedTools` 真生效**：从"展示字段"变成拟稿白名单（现缺口必须修）。
- **下一份计划**：军队/地方政府的行政范围由**机制计算**（凭仗 × 位置 × 领地），替换"GM 手传"这个来源。为此留 `CharterResolver` 接缝：GM 配置只是它的第一个实现，换实现不改数据形状。
- 读侧继续用 `DecisionScopeFunctions` + `AccessLimit`；拟稿期的辖区校验复用同一套 jurisdiction 计算。

---

## 5. 审计

- 状态面：`packet.proposerId`、每条 call 的原始参数与状态、`MergedEffectPlan.sources[]` / `gmAdded`、`approverId`、`executorId`。
- 日志面：**提案人必须明确记清**（用户裁定）；执行/审批/合并各留痕。
- 结果面：每次裁决一份 `outcome` + INFO 理由；可 `VoidAdjudication` 作废重裁。

---

## 6. 旧骨架复用与冲突（用户裁定：冲突时新设计优先）

**沿用**：`Directive` 的落盘 + 末位生效 + 状态机思路、`sd.IssueDirective` 作为提交口、`AdjudicateTick` 作为执行批、`CommandTargets`、`sd.PutInfo`、`ConfirmGate`/pending、`DecisionScopeFunctions`。

**以新设计为准、该改就改（不让老代码限制新设计）**：

1. 审批粒度：从"一 tick 全体令一次裁决"改成"每个决策人包一次 `true/false`"；
2. 审计身份：执行 initiator 改 GM（proposer 另记）；现有 `AdjudicateTickTool` 用决策人身份的信封要跟着改；
3. 命令形状：`DirectiveCommand{type,payloadJson}` 换成带 schema/预览/拟稿校验的 `FormattedCall`；
4. `allowedTools` 从展示变判据。

改这些形状按铁律 5 走 Codec/ChangeSet/往返测试与旧档兼容。

---

## 7. 非目标 / 边界

- 不做"改参数再批"（决策人自己错就等下一回合；GM 的合并集是显式新对象，不是对原提案的静默改写）。
- 不做自动语义冲突消解、不自动定序。
- 本轮不做"机制计算辖区"（下一份计划）。
- 决策人不获得任何执行权限；执行权始终在 GM。

---

## 8. 开发顺序

### 阶段 1（先做）：v17levant 三国以新系统重建 + 测试性推演

目标：把老 GSim 的 v17levant 用当前 classfirst 经济系统重建，备份 store，产出三国详细经济数据，跑 360 tick 测试性推演。

已具备/已实测：

- classfirst 引擎在**紧凑三国测试世界**跑通 360 tick（`ClassFirstPilotEngineTest` 360 tick 双向流动+守恒；`ClassFirstPopulationEconomyTimeParticipantTest` tick 120/240/360 守恒、人口同步、无旧结算路径）；
- 全仓 `clean verify` 绿：11 模块 2348 tests / 0 失败，SpotBugs 各模块 0，前端门禁 297/297；
- 真档三国推演管线已存在可复用：`v3curve_setup.py`（`simos.worldgen.initialize` 三国）、`v3curve_advance.py`（推进）、`h6sim_dump.py`（逐格 dump）、`h6sim_full_base.sh`（起服务+初始化+推进+落盘）。

**未做过、必须如实记**：真档 v17levant（59223 hex、三国 11.83M 人口）**尚未用 classfirst 端到端跑过 360 tick**。已知风险/边界：

1. classfirst 是**世界级 4 池**（`ClassFirstState.merge` 把三国同键池加法合并）：per-nation 的生产/池读数不可分，per-nation 明细只能从 hex（classes/actor/social）按区域 tag 聚合；
2. `economyHex` 的 `classFirst` 块是世界级，逐格 dump 会重复 ⇒ dump 脚本要做"逐格数据 + 世界级 classFirst 一份"的去重改造；
3. GUI 目前不渲染 `classFirst`（前端零引用）；"详细经济数据"走 API JSON + 聚合脚本，不依赖 GUI；
4. 性能未测：真档账户规模、单 tick 耗时、内存先做 pilot（advance 1/24/120）再决定 360 的批次；
5. store 与读数**不得放 `/tmp`**（本机重启会清空）；跑前建议先把工作树里已验证的 R4a 六文件提交成 checkpoint。

执行步骤（待确认后开跑）：

1. 重打 shaded jar（跑前确认；当前 `simos-app/target/*-shaded.jar` = 15:21 产物）；
2. 在持久目录建 store（已定：`~/simos-runs/2026-09-30-v17-classfirst/`，下设 `store/`、`backup-t0/`、`raw/`、`agg/`、`logs/`），按 `h6sim_full_base.sh` 口径起服务（GUI 5827 / MCP 5725 / 审批 5723）；
3. 从仓库根跑 `v3curve_setup.py`（三国 worldgen initialize）；
4. 停服务 → 复制 store 为 t0 备份（SQLite 一致性：停服后拷贝）；
5. 落 tick0 三国详细数据（逐格 social/economy + 世界级 classFirst 一份）；
6. pilot：advance → 1 / 24 / 120，量耗时、内存、守恒；
7. 分段推进 120 → 240 → 360，每个关账日落一份原始读数到持久目录；
8. 聚合三国明细（hex→nation 用区域 tag），出对比表；守恒验收：粮/布/钱/土地/债务=债权/账户净额、人口两侧同步、无旧结算路径、双向流动存在。

### 后续阶段（另立计划）

- 阶段 2：classfirst 原生 GM 经济工具（税率、地方债、一次性抽取、征兵、组军、classfirst 参数调整），带预览/审计；
- 阶段 3：决策包 + 提案 + 三种状态 + 合并效果集 + 审批 `true/false` 的实现；
- 阶段 4：辖区/行政范围机制计算（`CharterResolver` 的第二个实现）+ 后果模型（合法性/民怨等）。

---

## 9. 确认记录（2026-09-30）

用户裁定「就这么办」，即确认：

1. 本设计第 2/3/4/6 节的状态与流程（决策包、四态、合并效果集、GM 执行 + proposer 留痕、charter 初期 GM 传）；
2. 阶段 1 执行方案（持久 store、停服备份、先 pilot 再定 360 批次）；
3. 跑前先把已验证的 R4a 六文件提交成 checkpoint。

阶段 1 运行目录已定：`~/simos-runs/2026-09-30-v17-classfirst/`。
