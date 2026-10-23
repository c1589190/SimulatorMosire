# SimulatorMosire

可分模块生长的模拟引擎。Maven reactor 共 **18 个 `simos-*` 模块**：领域模块只拥有自己的数据，
`simos-core` 负责组合与调度，`simos-app` 是组合根。

## 先读哪里

| 需要 | 入口 |
|---|---|
| 常驻红线、模块路由 | [`AGENTS.md`](AGENTS.md) |
| 当前状态、最近门禁、下一步 | [`docs/agents/STATUS.md`](docs/agents/STATUS.md) |
| 文档系统规则（项目设计 / 项目维护分账） | [`docs/AGENTS.md`](docs/AGENTS.md) |
| 项目维护裁定 | [`docs/agents/MAINTENANCE_RULINGS.md`](docs/agents/MAINTENANCE_RULINGS.md) |
| 工程 Skill 目录 | [`docs/agents/SKILLS.md`](docs/agents/SKILLS.md) |
| 项目设计索引 | [`docs/superpowers/AGENTS.md`](docs/superpowers/AGENTS.md) |
| 设计裁定索引 | [`docs/superpowers/DESIGN_RULINGS.md`](docs/superpowers/DESIGN_RULINGS.md) |
| 用户设计原稿（代码/设计裁定） | [`docs/superpowers/specs/2026-10-02-architecture-design-source.md`](docs/superpowers/specs/2026-10-02-architecture-design-source.md) |

## 构建与验证

```bash
tools/gate.sh pre                 # 开工自检
tools/gate.sh focused simos-app   # 聚焦验证（自动带 -am）
tools/gate.sh full                # 阶段边界/关账：全仓 clean verify
tools/gate.sh docs                # 文档与 Skill 结构检查
tools/gate.sh selftest            # 门禁自身红/绿对照
```

要求 JDK 21 + 仓库内 `./mvnw`；Maven 一律通过 `tools/mvn-lock.sh` 使用本树 `.m2-repo`。
完整门禁包含 Spotless、Checkstyle、SpotBugs、Surefire 与前端门禁。
