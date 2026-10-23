# tools/AGENTS.md —— 门禁与机械检查脚本索引

> **属于项目维护。**
> 本目录是执行面，不是规则正文；规则在 `../docs/agents/`，设计在 `../docs/superpowers/`。
> 当前状态：`../docs/agents/STATUS.md`。

## 一、脚本入口

| 脚本 | 作用 |
|---|---|
| `gate.sh` | 统一门禁：`pre / full / focused / guards / add / docs / selftest` |
| `check-docs.py` | 文档结构、链接、模块 AGENTS、STATUS、设计/维护分账检查 |
| `skills-lint.py` | 工程 Skill 契约检查 |
| `mvn-lock.sh` | 本树 Maven 锁 + `.m2-repo` 播种 |
| `multitick-sim.sh` | 多 tick 真世界模拟，移出 JUnit |
| `scan-multitick-tests.py` | 扫描测试里违规的多 tick 推进 |
| `run-shaded.sh` / `shaded-close-check.sh` | shaded jar 运行/关账辅助 |
| 其余 `v17levant*` / `gsimap_import.py` / `r0-perf-measure.sh` | 世界数据/性能专用工具 |

## 二、维护规则

1. **不许削弱门禁**：不要为通过而加 `-Dskip*`、`-q`、`-N` 或去掉 `-am`；`gate.sh` 自己会拒绝。
2. 新增/修改检查逻辑：先更新 `../docs/agents/VERIFICATION.md` 或 `MAINTENANCE_RULINGS.md`，再加脚本与 selftest。
3. 检查脚本必须有负向 selftest；没有红/绿对照的新规则不算可靠。
4. 不把本机绝对路径、密钥、运行数据写进脚本/输出。
5. 文档与 Skill 变更先跑：

```bash
tools/gate.sh docs
python3 tools/check-docs.py --selftest
python3 tools/skills-lint.py --selftest
```

6. Maven 相关脚本必须走 `mvn-lock.sh`；不要绕过本树锁。
