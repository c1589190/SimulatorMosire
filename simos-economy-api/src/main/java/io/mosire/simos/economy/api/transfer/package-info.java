/**
 * ★★ <b>转移原语</b>（H2；裁定 D2-A / K4）：全系统唯一的"东西从 A 到 B"的事实。
 *
 * <p>形状与理由见 {@link io.mosire.simos.economy.api.transfer.Transfer}；本包只放<b>契约</b> ——
 * 没有账本、没有余额、没有公式、没有落账口（"谁把转移落到账上"由同时看得见 economy 与 actor 的 app 协调器做，铁律 3）。
 *
 * <p>★ <b>为什么在 {@code economy-api}</b>：转移是经济切片共用的稳定契约（与 ID 层同待遇）；它依赖 {@code actor-api} 的 {@code
 * ActorRef} 与 {@code map} 的 {@code HexCoord}，两个方向都成立（{@code economy-api} 本来就依赖这两者）。
 */
package io.mosire.simos.economy.api.transfer;
