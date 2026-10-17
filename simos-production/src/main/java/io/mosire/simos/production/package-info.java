/**
 * ★★ <b>{@code simos-production}（生产切片，namespace {@code "production"}）</b>——D-031 接口先行的 Z2 责任区； ★★
 * <b>D-032 起是"生产"的唯一权威与唯一计算者</b>。
 *
 * <p>★★ <b>本模块拥有（D-032 边界裁决 §1）</b>：方案定义（投入/产出/周期/资产需求/劳动/铸币/制度条件/引用的经验条
 * 与公式/可用性条件）、生产方式、<b>生产实例</b>（方案稳定 ID 引用、经营者、地点、进度、周期实际劳动与实际投入 累计 —— 本批从 {@code EconomyData.units}
 * 迁出）、<b>经验条定义 + 家户经验账 + 解锁集合</b>（永久记忆）、 <b>效率累计与精度余数</b>（按新公式重建）、<b>GM 产出数值覆盖</b>，以及<b>两步计算契约</b>
 * （{@code contract.ProductionRuntime}：试算无副作用 / 执行给回执 + 本域 ChangeSet）。
 *
 * <p>★★ <b>实现留本模块内部、契约在 {@code contract} 包</b>：economy / culture / app 只经 {@code
 * io.mosire.simos.production.contract} 读定义与调用计算；{@code ProductionRuntimeEngine} / {@code
 * ProductionEfficiency} / {@code ProductionExperience} 是内部类，外部不得 import。 技能目录 {@code
 * skill.ProductionSkillCatalog} 同样非契约包。
 *
 * <p>★★ <b>本模块不拥有的（边界未变）</b>：实际钱粮库存与货币账户（<b>actor</b>）、家户身份与人口（<b>Social</b>）、 {@code
 * relations}/{@code operatorConditions}/{@code allocations}/{@code assetShares}/ {@code
 * productionOrganizations}、市场/价格/需求/债务/工资/租金/发行政策（<b>economy</b>）。 ★ <b>到货</b>是在途运输到达（{@code
 * EconomySettlement}），留 economy，不随生产段搬走。
 *
 * <p>★ <b>科技点抽象面已按 D1 删除</b>：科技以"生产方式的可选升级方案"体现（用户 U1）；本模块不再有 {@code TechnologyDefinition}/{@code
 * TechnologyId}/{@code TechnologyMastery}/{@code MasteryScope}/ {@code TechnologyLookup} 与两条对应命令。
 *
 * <p>★ <b>依赖方向</b>（spec §4）：{@code production →
 * simos-util/simos-map/simos-economy-api/simos-ga-spi} 单向；不得依赖 {@code simos-economy} 实现、{@code
 * simos-culture}、{@code simos-core}、{@code simos-app} （enforcer 构建期强制）。{@code economy-api}
 * 也不得反向引用本模块新类型（否则成环）—— 故生产实例/方案的 公开值类型都住本模块的 {@code contract} 包。
 *
 * <p>★ <b>无 {@code TimeParticipant}</b>：本模块没有自己的推进器；状态推进由组合根经 {@code ProductionRuntime} 驱动（试算/执行/逐
 * tick 经验衰减），仍走"本域候选态 → 本域 ChangeSet → 同一 revision"。
 */
package io.mosire.simos.production;
