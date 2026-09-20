/**
 * SDSimos（国家与决策人，spec §一）——拥有：国家（{@code Nation}）、军队归属（{@code Army}）、交战（{@code Combat}：
 * 场·阶段·结局）、交战状态（{@code CombatState}：时间线切片内）、决策人（{@code DecisionMaker}）、决策（{@code
 * Directive}）、效果（{@code Effect}）、判决记录（{@code Verdict}）、损失记录（{@code LossRecord}）与 sd 侧 INFO。
 *
 * <p>★ **铁律 3 的边界**（spec §一.1）：单位与编制仍归 {@code simos-unit}；地图与区域仍归 {@code simos-map}；社会属性仍归 {@code
 * simos-social}。本模块的写入**从不直接改**这三者的状态，只能派生自己的数据；跨模块改动一律走命令（spec §五.3）。
 *
 * <p>★ **铁律 4 的边界**（R2）：Core **编译期看不见**本模块——由 {@code simos-core/pom.xml} 的 enforcer 在构建期强制；
 * 本模块自己也不得反向依赖 {@code simos-core}/{@code simos-app}（由本模块 enforcer 强制）。
 *
 * <p>★ {@code agentlib-mosire} 是本模块的**显式**依赖（R5），不是传递依赖。
 */
package io.mosire.simos.sd;
