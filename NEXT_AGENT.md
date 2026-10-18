# 下一个 Agent 的入口

先在这个 worktree 工作：

```text
/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
```

不要碰主树 `/home/cna/SimulatorMosire`。

必读顺序：

1. `AGENTS.md` 与 `docs/agents/00`–`15` **全部读完**（不要只看目录/摘要）
2. **目标运行序列标准文档**：`docs/superpowers/specs/2026-10-19-d032-rural-runtime-target-design.md`；并加载 `.agents/skills/target-runtime-design/SKILL.md`
3. **总派单入口**：`docs/superpowers/plans/2026-10-18-d032-rural-master-dispatch.md`（§0–§4 + 当前阶段）
4. `docs/superpowers/specs/2026-10-18-d032-rural-tick-contract.md`
5. `docs/superpowers/status/2026-10-18-d032-rural-land-labor-debt-redesign-handoff.md`
6. `docs/superpowers/specs/2026-10-18-d032-economy-tick-redesign.md`（以 §14–§45 的后续修正为准）
7. `docs/superpowers/reports/2026-10-18-d032-original-intent-audit.md`
8. `docs/superpowers/specs/2026-10-02-architecture-design-source.md` 的 **D-032 U1–U23 全文**
9. `docs/superpowers/plans/2026-10-18-d032-rural-land-labor-debt-p0-p6-plan.md` §1.1 的“未读清单”——P2/P3/P4 开工前按该表补读对应旧 D-032 文档

当前不是代码实现状态：本轮只完成了用户原话记录与农村 tick/土地/劳动/债务设计，没有改 Java/XML/POM/测试。

## P0 更新（2026-10-18，已恢复进度）

用户已追加裁定：土地与商品进**同一订单簿/同一撮合器**，不是两个阶段；纯库存财富只加土地；低库存追加轮删除；土地数量/质量按 hex、**价格按市场区一个价**、数值由工程定；生产方式归 production，经济运转归 economy；经济千行 Java 文件必须**最先拆分**。

P0 产物：

- **总派单文件**：`docs/superpowers/plans/2026-10-18-d032-rural-master-dispatch.md`
- tick 契约：`docs/superpowers/specs/2026-10-18-d032-rural-tick-contract.md`
- P0–P6 详细差距表 / 责任区 / 大文件拆分附录：`docs/superpowers/plans/2026-10-18-d032-rural-land-labor-debt-p0-p6-plan.md`
- 无级自耕—雇佣农业模板：`docs/superpowers/specs/2026-10-18-d032-unified-agricultural-mode-template.md`

**下一步 = P0.5（大文件拆分先行，先于 P1）**；P0.5 编译+现有回归通过后，再派 P1（Social 先 + 单槽 `PendingFamineFacts`）。开工前先读总派单与对应约束设计书。当前仍未改 Java/XML/POM/测试，未跑 Maven，未提交。

特别注意：设计明确**没有抵押、担保、特定资产优先受偿**；不要恢复 `Pledge` 作为农村债务基础。土地在债务清算中按所属市场区价格直接抵债，不先挂市场。工资第一次只付货币，未付成普通家户债；还债阶段允许库存。
