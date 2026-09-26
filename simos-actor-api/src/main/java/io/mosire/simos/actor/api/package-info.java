/**
 * ActorApiSimos——actor 切片共用的**最底层契约层**（设计稿 §2/§10）。
 *
 * <p>★ **边界**：本模块**只放共用契约**——{@code ActorRef}、{@code ActorKind}、{@code AssetKind}、{@code
 * AssetClassKey} 等跨模块类型。**没有** Snapshot、**没有**数据库/存储、**没有**领域公式。
 *
 * <p>★ **依赖方向**：{@code actor-api ← economy-api ← economy/ledger}。本模块比 {@code simos-economy-api}
 * **更底层**——economy-api 反过来依赖它；其余领域切片（property / production / ledger / market / government / social
 * / unit / sd）与 {@code simos-core} / {@code simos-app} 只许**顺着**这条方向依赖，不许反向。
 *
 * <p>★ **不许反向依赖任何领域/编排模块**：{@code pom.xml} 里的 enforcer 把 economy-api / economy / ledger / social /
 * unit / sd / core / app / agentlib 全部禁掉，可用只剩 util、map 与 jackson（越界 = 构建失败，不是 code review 的事）。
 *
 * <p>★ 注意：map 只是**允许**而非**需要**——本模块此刻零 {@code HexCoord} 用法，故不声明该依赖（YAGNI）；等真有 api 侧类型用到再加。
 */
package io.mosire.simos.actor.api;
