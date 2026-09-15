# SimulatorMosire

> **铁律**
> 1. 所有查询最终解析为**稳定实体**。地址是定位方式，ID 是身份。
> 2. 所有修改最终表示为 `Command → ChangeSet → Revision`。不存在绕过该路径的写入口。
> 3. 所有领域模块**只拥有自己的数据**。MapSimos 永远不知道 SocialSimos / UnitSimos 存在。
> 4. Core 只负责**组合与调度**，不重新实现领域逻辑。
> 5. 变更集**从完整状态类型派生**，且有往返不变式测试守卫：
>    `apply(changeSet, base)` 必须逐字段重建出 target。

模拟引擎。五模块自下而上单向依赖：

    UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos

设计文档：`docs/superpowers/specs/2026-09-16-simos-master-design.md`
实现计划：`docs/superpowers/plans/2026-09-16-simos-master-plan.md`

## 构建

    ./mvnw verify

门禁 = Spotless + Checkstyle + SpotBugs + Surefire。
注意 `mvn test` **不跑** SpotBugs，关账前须单独跑 `spotbugs:check`。

迭代时只跑相关单条用例：

    ./mvnw -q -Dtest=<类名> test
