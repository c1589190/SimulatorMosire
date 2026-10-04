# 2026-10-09 S3a 开发计划：Unit/Gov 家户容纳

> 架构：`docs/superpowers/specs/2026-10-09-s3a-unit-household-containment.md`
> 纪律：一个实现子 Agent 只写生产代码、只过 main compile；测试后置；子 Agent 不 commit。

## 步骤
1. `simos-unit` 加 `simos-social-api` 依赖（enforcer 同步）；`Unit` 加第 18 组件 `households`，全部兼容构造器补默认空表，所有拷贝点原样带过；`UnitState` 加“同一家户不可在多 unit”守卫。
2. `GovFormation` 加 `households` 组件（兼容构造器默认空表），JSON/往返同步。
3. `simos-social` 新增命令 handler：CreateHousehold / SetHouseholdLocation / AddMembers / RemoveMembers / TransferMembers / SetVitalRates / AdjustPopulation；只调用 `HouseholdBook`，写 `SocialChangeSet`。
4. `simos-unit` 新增 `unit.SetUnitHouseholds` handler；整体替换 households 列表，校验去重/非空。
5. App GM 工具：household create/move/members/rates + unit assign/detach；组合命令同批原子，preview/apply、reason、资源围栏。
6. 读口：`ApiViews`/Unit API 增加 households 与实时人口；Gov 读口增加下辖家户。
7. 日志：SocialLog + Unit 侧结构化事件；log4j2 配好级别。
8. main compile 门必须绿；不写测试、不 commit。

## 出口判据
- 见架构 §7 六条。
- 报告列出：改动文件、compile 结果、会改数值行为清单、受影响的硬编码/旧 API、与文档不一致、未完成项。
