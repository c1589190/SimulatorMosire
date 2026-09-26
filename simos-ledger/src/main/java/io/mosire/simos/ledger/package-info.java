/**
 * LedgerSimos —— 账本切片（增量 2 spec §三 的 v1 状态形状）。
 *
 * <p><b>本切片只写自己的数据</b>：账户库存与货币、应收应付的索取权、双边转移凭据（append-only 流水），外加一份 激活元信息。{@code AccountId}/{@code
 * ClaimId}/{@code TransferId}/{@code CommodityId} 一律取自 {@code simos-economy-api}、{@code ActorRef}
 * 取自更底层的 {@code simos-actor-api}（铁律 1：不在切片里另造同义 ID）。
 *
 * <p><b>守恒不在本切片</b>：每一笔转移的 {@code 买方扣款 = 卖方入账 + 政府税入账 + 运输方收入}、{@code 卖方出货 = 买方/在途入货 + 明示损耗}，
 * 以及借款"只搬现有的钱/粮、不凭空造现钞"，都由**命令层/协调器**校验，**不落成第二份真相**——本切片只留账， 不留"校验结论"。
 *
 * <p><b>未激活 = {@code economyMeta} 空</b>：{@code LedgerData.economyMeta} 为空 {@code Optional} 表示日制
 * 世界尚未激活经济（此时仍可走简化人口查询，但日期与参数一律按天解释）。空切片 ≠ 已激活。
 *
 * <p><b>硬约束</b>：不依赖任何别的领域切片（不依赖 {@code simos-social}/{@code simos-unit}/{@code simos-sd}/ {@code
 * simos-core}/app/agentlib），由本模块 POM 的 enforcer 在构建期强制；本切片不含任何经济公式 （产量/价格/税/撮合）。
 *
 * <p><b>切片形状</b>：{@link io.mosire.simos.ledger.LedgerData}（状态树）/ {@link
 * io.mosire.simos.ledger.LedgerSnapshot}（落盘切片）/ {@link
 * io.mosire.simos.ledger.change.LedgerChangeSet}（逐组件 {@code FieldDelta}）/ {@link
 * io.mosire.simos.ledger.codec.LedgerCodec}（JSON 往返 + diff + apply）/ {@link
 * io.mosire.simos.ledger.resolve.LedgerResolver}（{@code ledger:} 地址解析）。
 */
package io.mosire.simos.ledger;
