/**
 * 劳动的**关系层**（第三阶段设计稿 §二/§四）：{@link LaborSupply}（这批人有多少劳动可支配）与 {@link LaborAllocation}
 * （这批人把多少劳动给了哪个主体）。
 *
 * <p>★★ **为什么住 {@code simos-economy-api}**（设计稿 §八.1）：文档**三处**明文把 {@code simos-social} 列为本模块的消费者
 * （{@code package-info} 的"五个经济切片…与 {@code simos-social} 可依赖本模块与 util/map"、根 {@code pom.xml}、 {@code
 * economy-api/pom.xml}），而 R1 已让 social 真的依赖了它 ⇒ 两侧都看得见。这是"人口（social）与经济（economy）之间的桥"
 * 唯一能同时被两端看见的落点：放 social 则 economy 看不见，放 economy 则 social 看不见，放 util 则违背其章程（"只提供原语， 不理解任何领域概念"）。
 *
 * <p>★ **本包只放"关系"的形状与不变量，不放任何切片自己的状态类型**：{@code LaborAllocation} 不装库存/货币/土地， {@code LaborSupply}
 * 不装年龄/性别（那是 {@code PopulationGroup} 的事）—— 三层拆分（§二）在类型层面就看得见。
 */
package io.mosire.simos.economy.api.labor;
