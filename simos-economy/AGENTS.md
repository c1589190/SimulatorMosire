# simos-economy — 局部 AGENTS

> 本文件只做本模块的局部索引与纪律，不复制根规则、不另立用户裁定。
> 根规则：`../AGENTS.md`；当前状态：`../docs/agents/STATUS.md`；维护索引：`../docs/agents/AGENTS.md`；设计索引：`../docs/superpowers/AGENTS.md`。

## 职责

聚合式经济机制：产业、市场、债务、工资、财政、迁移与逐 tick 结算。

## 直接依赖边界（回 `pom.xml` 核过，2026-10-23）

- `simos-actor-api`
- `simos-util`
- `simos-economy-api`
- `simos-production`
- `simos-map`
- `simos-social-api`
- `simos-social`
- `simos-ga-spi`

> 只列本模块直接声明的 `io.mosire:simos-*` 依赖。除允许面之外一律由 enforcer 拒绝；
> 传递依赖不算允许使用面。改依赖前先看项目设计边界，不在本文件私改。

## 源码入口

- 主代码：`src/main/java/io/mosire/simos/economy/**`
- 当前扫描：`*Tool.java` **17** 个；`*Handler.java` **40** 个；`*ToolSource.java` **0** 个。

## 工具归属

- 领域功能工具必须住本模块；`simos-app` 只装配/分桶/桥接，不得承载本模块领域工具。
- 工具契约统一依赖 `simos-ga-spi`；本模块若尚未声明该依赖，新增工具时先按项目设计确认依赖面，不要直接改 app 绕过。


- **当前工具债**：40 个 handler / 17 个具体工具，部分命令/架构覆盖与 typed schema 尚未统一；修工具面属于本模块与工具迁移设计，不得回流 app。

## 设计与文档入口

- 当前事实与下一步：`../docs/agents/STATUS.md`
- 设计总索引：`../docs/superpowers/AGENTS.md`；设计裁定索引：`../docs/superpowers/DESIGN_RULINGS.md`
- 用户设计原稿：`../docs/superpowers/specs/2026-10-02-architecture-design-source.md`
- 专题设计：`../docs/superpowers/specs/2026-09-25-aggregate-economy-v2-design.md`
- 专题设计：`../docs/superpowers/specs/2026-10-18-d032-economy-tick-redesign.md`

## 验证

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy -am
tools/gate.sh focused simos-economy <测试类或范围>
```

编译过 ≠ 验收通过；测试与变异自证按 `../docs/agents/VERIFICATION.md`。
