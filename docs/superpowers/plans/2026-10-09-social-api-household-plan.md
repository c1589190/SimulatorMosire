# 2026-10-09 Social API + 家户/人口开发计划

> 架构：`docs/superpowers/specs/2026-10-09-social-api-household-architecture.md`
> 纪律：AGENTS.md §一.5/§一.8；一个实现子 Agent 只写生产代码、只过 compile；测试最后单独 Agent；子 Agent 不 commit。

## 阶段 S1：契约模块 + 稳定 ID 迁移 + SocialLog 门面

**目标**
- 新建 `simos-social-api` Maven 模块（只放契约，无状态/Codec/Jackson 实现）。
- `HouseholdId`、`PeopleLotId`、`Sex` 迁入该模块，删除旧定义；全仓生产代码改 import。
- 新增契约：`HouseholdLocation`、`HouseholdProfile`、`AgeBracketView`、`HouseholdVitalRates`、`HouseholdPopulationEvent`、`PopulationEventType`、`HouseholdLookup`、`PopulationLookup`。
- `simos-social` 新增 `SocialLog` 门面与 log4j2 配置键 `simos.social.logLevel` / `simos.social.traceLevel`。
- root pom / dependencyManagement / enforcer 同步。

**不做**
- 不改 `SocialData` 的家户状态、不实现事件落账、不改 `PopulationGroup` 形状、不接 Unit/Economy/Gov 消费方、不动测试文件。

**出口判据**
- `./mvnw -q -pl simos-app -am -DskipTests compile` 成功。
- `grep -r "economy.api.id.HouseholdId\|economy.api.id.PeopleLotId" --include='*.java' simos-*/src/main` = 0。
- `simos-social-api` 只依赖 `simos-map`，无 Jackson/领域模块。

**文件所有权**
- 新增：`simos-social-api/**`。
- 修改：root `pom.xml`；所有 `src/main/**` 里旧 ID import（social/economy-api/economy/actor/gov/app 等）；`simos-social/pom.xml`；相关 enforcer；`simos-app/src/main/resources/log4j2.xml`。
- 禁止：任何 `src/test/**`；任何 `git commit`。

## 阶段 S2：Social 家户状态 + 率/事件 + 日志

**目标**
- `Household` 状态：id、location、profile、memberLots、vitalRates。
- `PopulationGroup` 删除 `residence`；`SocialData` 增加 households/events/rates 组件；Snapshot/Codec/ChangeSet 同步。
- 生命周期服务：create/setLocation/setProfile/addMembers/removeMembers/transferMembers/setVitalRates/adjustPopulation/applyEvent。
- 逐家户出生/死亡/转移/GM 调整事件；守恒检查。
- 只读 SPI 的 Social 实现；hex/unit 人口由家户汇总现算。
- `SocialLog` 全事件接入。

**出口判据**
- main compile 成功。
- 新建家户、HEX↔UNIT 移动、成员增删、逐家户出生/死亡/转移、守恒检查均有日志，字段与架构 §6 一致。
- `PopulationGroup` 无 `residence` 字段。
- 旧世界不迁移；新模型自行播种。

## 阶段 S3：消费方接入（Unit/Economy/Gov）

- `Unit` 增加 `List<HouseholdId> households`，人数实时从 PopulationLookup 汇总；旧 `manpower` 逐步退役。
- `GovFormation.staff` 改为“政府单位下家户 + 角色”投影。
- `ClassRow.population` / `LaborSupply` 改为家户投影；`LaborAllocation` 引用 PeopleLotId 迁到 social-api。
- 招募/征兵/退伍/家户迁移命令 + GM 工具。
- 验收：政府内多群体家户示例（Hindu/Han 两个家户在同一 GovUnit）。

## 阶段 S4：测试 Agent + 日志验证

- 单独测试 Agent 按架构 §7 判据补测试：
  - 家户创建/位置/成员/率/事件/守恒/转移；
  - SPI 读口与 hex/unit 汇总；
  - 日志事件名/字段与架构 §6 对照；
  - 旧包零 import、enforcer 反向依赖拒绝。
- 控制方在测试 Agent 回来后自己跑测试并检查日志输出与架构预期。
