/**
 * SDSimos（国家与决策人，spec §一）——拥有：决策人（{@code DecisionMaker}）、决策（{@code Directive}）、效果（{@code
 * Effect}）、判决记录（{@code Verdict}）与 sd 侧 INFO。
 *
 * <p>★★ <b>2026-10-12 的收缩与退役（如实记，逐批）</b>——用户裁定「<b>sd 整个弃用！</b>」，本模块的组件按族分批交出：
 *
 * <ul>
 *   <li>决策族七张表 → {@code simos-ga}（批次 3a / 3b-1 / 3b-2）；本模块今天只剩 {@code effects} 一张（它要开 {@code ga →
 *       unit + map} 两条边，顺位在 3b-4）；
 *   <li>外交三张表（{@code nations} / {@code diplomaticRelations} / {@code diplomaticEvents}）→ {@code
 *       simos-gov}（批次 3c；「外交是 gov 的事」）；
 *   <li>军队身份（{@code armies}）→ {@code simos-army}（批次 3d-1）；
 *   <li><b>本批 3d-2：旧战斗族三张表（{@code combats} / {@code combatStates} / {@code lossRecords}）+ 五个
 *       handler + 全套战斗 模型类型（{@code Combat} / {@code CombatStage} / {@code CombatState} / {@code
 *       OutcomeTable} / {@code LossRecord} / {@code LossClass} / {@code CasualtyDelta} / {@code
 *       CasualtySpec} + 五个战斗 id）整族<b>退役</b></b> （用户裁定「战斗模块，包括阶段之类的，归 Army 模块管」＋ D-012「交战的具体记录移到
 *       Army 模块记录与处理」＋「旧 sd 战斗命令族不再作为交战记录的载体」）。★ 是<b>退役</b>不是搬迁：army 自阶段 D1 起就有自己的交战记录模型与地址族。
 * </ul>
 *
 * <p>★ <b>铁律 3 的边界</b>（spec §一.1）：单位与编制仍归 {@code simos-unit}；地图与区域仍归 {@code simos-map}；社会属性仍归
 * {@code simos-social}。本模块的写入**从不直接改**这三者的状态，只能派生自己的数据；跨模块改动一律走命令（spec §五.3）。
 *
 * <p>★ **铁律 4 的边界**（R2）：Core **编译期看不见**本模块——由 {@code simos-core/pom.xml} 的 enforcer 在构建期强制；
 * 本模块自己也不得反向依赖 {@code simos-core}/{@code simos-app}（由本模块 enforcer 强制）。★ 同批还有三条被禁的边不得出现： {@code sd →
 * gov} / {@code sd → army} / {@code sd → ga}（成环）。
 *
 * <p>★ {@code agentlib-mosire} 是本模块的**显式**依赖（R5），不是传递依赖。
 */
package io.mosire.simos.sd;
