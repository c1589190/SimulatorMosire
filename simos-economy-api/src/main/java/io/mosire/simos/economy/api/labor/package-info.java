/**
 * 劳动的**关系层**（第三阶段设计稿 §二/§四；P2-A §13.4 起单位 = 毫小时）：{@link HouseholdLaborCommitment}
 * （这笔**家户时间**分给了哪个生产活动/unit；Z1b 起带 {@code kind}）与 {@link HouseholdLaborTimeTable}（legacy 值载体；C8 起
 * 不再是劳动系数权威，见该类 javadoc）。
 *
 * <p>★★ **本批删除 {@code LaborSupply}**：家户每 tick 的时间预算由 Social 人口组成 × {@code SocialProvisioning}
 * 的劳动权威现算 （投影进 {@code HouseholdEconomy.laborMilli}），不再有"每批次供给容量"这第二权威；{@code
 * HouseholdLaborTimeTable.DEFAULT} 只是旧档/旧调用点的兼容常量。
 *
 * <p>★★ **为什么住 {@code simos-economy-api}**（设计稿 §八.1）：文档**三处**明文把 {@code simos-social} 列为本模块的消费者
 * （{@code package-info} 的"五个经济切片…与 {@code simos-social} 可依赖本模块与 util/map"、根 {@code pom.xml}、 {@code
 * economy-api/pom.xml}），而 R1 已让 social 真的依赖了它 ⇒ 两侧都看得见。这是"人口（social）与经济（economy）之间的桥"
 * 唯一能同时被两端看见的落点：放 social 则 economy 看不见，放 economy 则 social 看不见，放 util 则违背其章程（"只提供原语， 不理解任何领域概念"）。
 *
 * <p>★ **本包只放"关系"的形状与不变量，不放任何切片自己的状态类型**：{@code HouseholdLaborCommitment} 不装库存/货币/土地， {@code
 * HouseholdLaborTimeTable} 不装人口（那是 Social 的 {@code PopulationGroup}/{@code Household.members} 的事）——
 * 三层拆分（§二）在类型层面就看得见。
 */
package io.mosire.simos.economy.api.labor;
