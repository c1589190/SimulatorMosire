# simos-app — 局部 AGENTS

> 本文件只做本模块的局部索引与纪律，不复制根规则、不另立用户裁定。
> 根规则：`../AGENTS.md`；当前状态：`../docs/agents/STATUS.md`；维护索引：`../docs/agents/AGENTS.md`；设计索引：`../docs/superpowers/AGENTS.md`。

## 职责

唯一组合根：ToolSource 装配、角色分桶、GUI/MCP、时间参与者与跨域桥接；不实现领域公式、不承载领域工具。

## 直接依赖边界（回 `pom.xml` 核过，2026-10-23）

- `simos-core`
- `simos-util`
- `simos-actor-api`
- `simos-economy-api`
- `simos-map`
- `simos-calendar`
- `simos-social-api`
- `simos-social`
- `simos-unit`
- `simos-economy`
- `simos-actor`
- `simos-gov`
- `simos-army`
- `simos-production`
- `simos-culture`
- `simos-ga`

> 只列本模块直接声明的 `io.mosire:simos-*` 依赖。除允许面之外一律由 enforcer 拒绝；
> 传递依赖不算允许使用面。改依赖前先看项目设计边界，不在本文件私改。

## 源码入口

- 主代码：`src/main/java/io/mosire/simos/app/**`
- 当前扫描：`*Tool.java` **21** 个；`*Handler.java` **1** 个；`*ToolSource.java` **1** 个。

## 工具归属

- **app 不承载领域工具**：领域功能工具必须住各自领域模块；app 只负责 ToolSource 装配、角色分桶、GUI/MCP 桥接和跨域编排。
- 当前仍有具体 `*Tool.java`；它们只应是跨切片/宿主级工具。领域工具必须按设计迁回所属模块。
- 新增领域公式/领域工具一律不得写进 app；需要新工具契约时使用 `simos-ga-spi`，不要在 app 私设第二套协议。

## 设计与文档入口

- 当前事实与下一步：`../docs/agents/STATUS.md`
- 设计总索引：`../docs/superpowers/AGENTS.md`；设计裁定索引：`../docs/superpowers/DESIGN_RULINGS.md`
- 用户设计原稿：`../docs/superpowers/specs/2026-10-02-architecture-design-source.md`
- 专题设计：`../docs/superpowers/DESIGN_RULINGS.md`
- 专题设计：`../docs/superpowers/specs/2026-10-12-ga-module-and-tool-descent.md`
- 专题设计：`../docs/superpowers/specs/2026-10-17-dm-gm-agentlib-decision-package-design.md`

## 验证

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am
tools/gate.sh focused simos-app <测试类或范围>
```

编译过 ≠ 验收通过；测试与变异自证按 `../docs/agents/VERIFICATION.md`。
