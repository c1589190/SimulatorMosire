# simos-ga-spi — 局部 AGENTS

> 本文件只做本模块的局部索引与纪律，不复制根规则、不另立用户裁定。
> 根规则：`../AGENTS.md`；当前状态：`../docs/agents/STATUS.md`；维护索引：`../docs/agents/AGENTS.md`；设计索引：`../docs/superpowers/AGENTS.md`。

## 职责

工具契约层：`SimosTool` 等工具接口、角色/资源声明、窄读写口；向上依赖 AgentLib `AgentTool`。

## 直接依赖边界（回 `pom.xml` 核过，2026-10-23）

- `simos-util`

> 只列本模块直接声明的 `io.mosire:simos-*` 依赖。除允许面之外一律由 enforcer 拒绝；
> 传递依赖不算允许使用面。改依赖前先看项目设计边界，不在本文件私改。

## 源码入口

- 主代码：`src/main/java/io/mosire/simos/ga/**`
- 当前扫描：`*Tool.java` **1** 个；`*Handler.java` **0** 个；`*ToolSource.java` **0** 个。

## 工具归属

- **本模块只放工具契约**，不实现领域功能工具或领域公式。
- 当前扫描到的 `*Tool.java` 是契约接口（`SimosTool`），不是具体领域工具。
- 领域工具实现归各自模块；app 只装配/分桶/桥接。

## 设计与文档入口

- 当前事实与下一步：`../docs/agents/STATUS.md`
- 设计总索引：`../docs/superpowers/AGENTS.md`；设计裁定索引：`../docs/superpowers/DESIGN_RULINGS.md`
- 用户设计原稿：`../docs/superpowers/specs/2026-10-02-architecture-design-source.md`
- 专题设计：`../docs/superpowers/specs/2026-10-12-ga-module-and-tool-descent.md`

## 验证

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-ga-spi -am
tools/gate.sh focused simos-ga-spi <测试类或范围>
```

编译过 ≠ 验收通过；测试与变异自证按 `../docs/agents/VERIFICATION.md`。
