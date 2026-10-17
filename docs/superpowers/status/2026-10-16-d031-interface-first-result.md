# D-031 P0 接口先行：实现与验收交接（2026-10-16）

> **状态：P0 已实现，主树完整门禁通过；未暂存、未提交、未推送。**
> 本文更新 D-031 的当前状态。设计与原话依据仍为 `docs/superpowers/specs/2026-10-15-d031-interface-first-design.md`、用户设计原稿 D-031；技术补全见 `docs/superpowers/reports/2026-10-16-d031-worktree-coordination.md` §6。
> 所有生产实现与测试由 `dsh headless` 按责任区顺序完成，控制方负责约束、复核、工作树同步与最终验收。

## 1. 本轮已具备的能力

| 面 | 已实现 | 边界 |
|---|---|---|
| 生产与科技 | `simos-production`：生产定义视图与只读接口、稳定技能键、科技定义/掌握状态、两条命令、codec/change | 生产定义权威仍在 economy；没有自动升级或传播 |
| 文化与基层治理 | `simos-culture`：家户文化/宗教/习俗标签、可编辑文化×技能规则、三条命令、codec/change、劳动加权合成服务 | 只立基础状态与接口；真实效果未启用，不实现同化或复杂治理 |
| 经济接口 | 现有产业/生产方式的定义适配；生产单元实际出工家户与劳动份额只读接口 | economy 只引用 production.contract；不搬劳动、账户、债务或进度 |
| app | 两 codec、五 handler、五条载荷提示、四个新世界初始化、技能映射装配工厂、两条日循环的中性桥接 | 不加 TimeParticipant 或激活开关；五条命令保持 GM 专用 |
| 共享流动表 | 设计书 §9 已明确候选范围、阶段贡献、执行周期与原子提交协议 | **本轮仅设计，未替换旧双机制** |

五条命令：`production.RegisterTechnology`、`production.SetMastery`、`culture.SetHouseholdCulture`、`culture.UpsertAptitudeRule`、`culture.RemoveAptitudeRule`。
它们经既有 `Command → ChangeSet → Revision` 提交与回放；删除不存在规则、掌握未登记科技等均具名拒绝。

## 2. 执行口径与技术补全

- P0 真实日循环固定调用 `updateProductionModifiers(List.of())`，**即使已经写入 2000‰ 文化规则也不启用**。落点回核：`CultureAptitudeBridge.java:42`、`PopulationEconomyTimeParticipant.java:641`、`EconomyOwnershipTimeParticipant.java:200`。
- 纯文化合成服务按全部出工家户的劳动量加权，无规则家户按 1000‰ 参与分母，四舍五入并限制在 `[0,2000]`，使用 BigInteger 防止权重乘积溢出；派生倍率不另存权威表。
- 产业缺技能只返回 `skill=empty`，完整定义仍可见；文化合成返回具名中性结果。真正缺 unit/industry 的一致性故障不降级。
- 技能映射由 app 从地图格坐标和六种已声明 kind 正向构造精确产业 ID；跨格同 kind 使用同一技能键，不解析 ID 猜种类，自定义未知产业可缺技能。
- `recipe(RecipeId)` 当前明确返回 empty：现有配方由 Industry 派生，没有 RecipeId 权威表；P1 搬定义时处理，本轮不临时增加 economy 配方表。
- `RegisterTechnology` 同 ID 替换、同值 no-op；`SetMastery` 要求科技已登记；作用域 kind 为非空白开放标签，本轮不推导层级与传播。

## 3. 验收事实（控制方实跑）

在主树 `/home/cna/SimulatorMosire` 执行：

```bash
tools/gate.sh full
```

**rc=0**。注意：不带子命令的 `tools/gate.sh` 只显示用法，不能当作跑过完整门禁。

| 检查 | 实际结果 |
|---|---|
| `clean verify` | rc=0 / BUILD SUCCESS |
| 后续 `-DskipTests validate` | rc=0 / BUILD SUCCESS |
| Surefire | **3422 tests / 0 failures / 0 errors / 7 skipped**，430 份报告 |
| 前端 | **412 tests / 412 pass / 0 fail** |
| SpotBugs | 18 个模块报告，非零 BugInstance 的模块数 **0** |
| 门禁证据 | 前端、shade 产物、7 条关键守卫均已由 full 校验；最终 `PASS`，耗时 307s |
| Git 差异 | `git diff --check` rc=0；未暂存、未提交、未推送 |

计数生成：遍历 `simos-*/target/surefire-reports/TEST-*.xml`，累加根属性 `tests/failures/errors/skipped`；遍历 `simos-*/target/spotbugsXml.xml` 统计 `BugInstance`。未把 7 条跳过计作执行通过，也未额外运行真 LLM 场景。

覆盖 V0–V5 与 N1–N9：非空状态/变更集 JSON 往返、真实命令提交回放、非中性规则仍未启用、全字段定义适配、真实劳动份额、六种技能跨格映射、依赖/包边界及人口写入隔离、倍率越界、重复注入与缺技能可见性。

**关键负向证据**：临时将有效文化合成结果接入真实日循环，编译成功后，V2 用例因经济状态中的实际效率累计值差异失败（1413/2000）；还原后目标用例恢复通过。不是未知单元错误、缺切片、源码扫描或编译错误造成的假红。全部临时变异已还原并核对 MD5。

N1/N7 的 literal 依赖先在 reactor 成环，因此真实红因是 `ProjectCycleException`；另外构造无环禁边，单独验证 enforcer 拒绝。未冒称两者是同一失败原因。

## 4. 工作树与证据位置

- 侧线：`.claude/worktrees/parallel-side-module`，分支 `parallel/side-module`，与主树同步到 `03faf0a3` 后开发；使用每树独立 `.m2-repo` 与锁。
- 写者全部停手后，控制方确认主树干净、两树同基线，将 **38 个已有文件的差异 + 85 个新文件**同步到主树，123 个文件逐一校验一致。未创建提交、未操作既有 stash。
- 本机备份与实现/测试证据位于侧线 `.superpowers/sdd/2026-10-15-d031-interface-first/`：各 `z*/task-report.md`、`z6/mutation-evidence.md`、`integration/manifest.json` 等。该目录被忽略，不入 git；本文保留跨会话必需的结论。
- 主树完整日志：`/tmp/simos-d031-main-gate.log`；门禁原始日志 `/tmp/simos-gate-full-verify-8GuH5M.log`、`/tmp/simos-gate-full-validate-gaZosV.log`，本机留痕副本存上述侧线 `integration/`。

## 5. 尚未完成（后续设计批次，不冒充本轮效果）

1. **生产定义尚未迁移**：P1 再拆分定义/实例并转移唯一写者，删除 economy 的旧定义表和临时适配器。
2. **真实文化效果未启用**：P2 再让预期利润与实际生产同时消费同一权威定义和修正语义；本轮没有自动科技进步、宗教利息或文化同化效果。
3. **旧双流动机制未替换**：共享流动表与市场周期同步落账尚未实装。
4. **尚未提交/推送**；其他批次 stash 保持原状，未处理其中 W/X 改动。
