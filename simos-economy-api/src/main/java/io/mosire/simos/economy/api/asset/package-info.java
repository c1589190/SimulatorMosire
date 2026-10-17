/**
 * ★★ <b>资产词表</b>（经济契约面）：只放"跨模块共用的资产值类型"，不含任何状态与公式。
 *
 * <p>★ 本包当前只有 {@link io.mosire.simos.economy.api.asset.AssetRightKind}（OWNED / TENANCY /
 * COMMUNAL）—— D-032 B2c 把它从 {@code economy} 实现包上移到这里，理由是 production 的 {@code
 * IndustryDefinitionView.acceptedRightKinds} 需要它，而 production 只允许依赖 {@code simos-economy-api}。
 */
package io.mosire.simos.economy.api.asset;
