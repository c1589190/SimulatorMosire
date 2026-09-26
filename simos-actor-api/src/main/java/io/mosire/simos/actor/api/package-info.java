/**
 * ActorApiSimos——actor 切片共用的**最底层契约层**（设计稿 §2/§10）。
 *
 * <p>★ **边界**：本模块**只放共用契约**——{@code ActorRef}、{@code ActorKind}、{@code AssetKind} 等跨模块类型。**没有**
 * Snapshot、**没有**数据库/存储、**没有**领域公式。
 *
 * <p>★ **2026-09-27 裁定 S3**：原本也住这里的 {@code AssetClassKey}（资产同质性键）整块退役 —— 它与 {@code simos-actor}
 * 的产权表同族，而那条路径生产侧零写入者。⇒ 本模块的 {@code asset} 包现在**只剩 {@code AssetKind} 这一档粗类型词表**，它被 economy
 * 用作产能的键（{@code Industry.capacity}），故保留。
 *
 * <p>★ **依赖方向**：{@code actor-api ← economy-api ← economy/ledger}。本模块比 {@code simos-economy-api}
 * **更底层**——economy-api 反过来依赖它；其余领域切片（property / production / ledger / market / government / social
 * / unit / sd）与 {@code simos-core} / {@code simos-app} 只许**顺着**这条方向依赖，不许反向。
 *
 * <p>★ **不许反向依赖任何领域/编排模块**：{@code pom.xml} 里的 enforcer 把 economy-api / economy / ledger / social /
 * unit / sd / core / app / agentlib 全部禁掉（enforcer **未禁**的只剩 util/jackson）—— 而本模块**一条都不声明**： util /
 * map / jackson 都不声明；越界 = 构建失败，不是 code review 的事。
 *
 * <p>★ 注意：util、map 与 jackson 只是 enforcer **未禁**而非**需要**——本模块此刻既不用 {@code HexCoord}、也不用 util
 * 的任何东西、更不用 jackson（本模块剩下的都是 enum 与"record + {@code String}"，Jackson 默认就能处理，零 import），
 * 故都不声明（YAGNI，裁定 R-e/R-h/R-j）；等真有 api 侧类型用到再加。
 */
package io.mosire.simos.actor.api;
