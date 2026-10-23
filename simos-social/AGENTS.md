# simos-social — 局部 AGENTS

> 本文件只做本模块的局部索引与纪律，不复制根规则、不另立用户裁定。
> 根规则：`../AGENTS.md`；当前状态：`../docs/agents/STATUS.md`；维护索引：`../docs/agents/AGENTS.md`；设计索引：`../docs/superpowers/AGENTS.md`。

## 职责

人口与家户权威：出生/死亡、需求、劳动、家户工单与人口切片。

## 直接依赖边界（回 `pom.xml` 核过，2026-10-23）

- `simos-util`
- `simos-map`
- `simos-calendar`
- `simos-social-api`
- `simos-economy-api`
- `simos-ga-spi`

> 只列本模块直接声明的 `io.mosire:simos-*` 依赖。除允许面之外一律由 enforcer 拒绝；
> 传递依赖不算允许使用面。改依赖前先看项目设计边界，不在本文件私改。

## 源码入口

- 主代码：`src/main/java/io/mosire/simos/social/**`
- 当前扫描：`*Tool.java` **14** 个；`*Handler.java` **19** 个；`*ToolSource.java` **0** 个。

## 工具归属

- 领域功能工具必须住本模块；`simos-app` 只装配/分桶/桥接，不得承载本模块领域工具。
- 工具契约统一依赖 `simos-ga-spi`；本模块若尚未声明该依赖，新增工具时先按项目设计确认依赖面，不要直接改 app 绕过。

## 设计与文档入口

- 当前事实与下一步：`../docs/agents/STATUS.md`
- 设计总索引：`../docs/superpowers/AGENTS.md`；设计裁定索引：`../docs/superpowers/DESIGN_RULINGS.md`
- 用户设计原稿：`../docs/superpowers/specs/2026-10-02-architecture-design-source.md`
- 专题设计：`../docs/superpowers/specs/2026-09-17-social-unit-simos-design.md`
- 专题设计：`../docs/superpowers/specs/2026-10-09-social-api-household-architecture.md`

## 验证

```bash
tools/mvn-lock.sh -q -DskipTests compile -pl simos-social -am
tools/gate.sh focused simos-social <测试类或范围>
```

编译过 ≠ 验收通过；测试与变异自证按 `../docs/agents/VERIFICATION.md`。
