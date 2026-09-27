/**
 * ★★ <b>货币契约</b>（H4；M1.1 起多一层"工具身份"）：钱的<b>种类</b>（{@code CurrencyId}，H2 已落地）、钱的<b>工具身份</b>（M1.1：
 * {@link io.mosire.simos.economy.api.money.CurrencyDef} = 币种定义、{@link
 * io.mosire.simos.economy.api.money.MoneyInstrument} = 一张具体的钱、{@link
 * io.mosire.simos.economy.api.money.InstrumentKind} = 金属币/国币/银行存款，词表的唯一拼写点是 {@link
 * io.mosire.simos.economy.api.money.MoneyVocabulary}）、钱的<b>形状</b>（{@code Transfer.money}
 * 的货币腿）与钱<b>从哪来</b>（本包的 {@link io.mosire.simos.economy.api.money.MoneyAuthority} / {@link
 * io.mosire.simos.economy.api.money.MoneyIssuance}）。
 *
 * <p>★ <b>本包只放契约</b>：没有余额、没有公式、没有落账口 —— "谁把货币腿落到账上"由同时看得见 economy 与 actor 的 app 协调器做（铁律
 * 3），"谁敢凭空造钱"由 {@code MoneyIssuance} 的闸门回答（本批恒抛）。★ M1.1 的工具身份<b>同样不动账</b>：余额仍住在 {@code
 * GoodsAccount.money}（键是 {@code CurrencyId}）⇒ 本批没有新的写入点。
 *
 * <p>★ <b>为什么在 {@code economy-api}</b>：发行源要用 {@code ActorRef}（住 {@code actor-api}）与 {@code
 * CurrencyId}（住本模块） —— 而 {@code simos-util} 不依赖任何 simos 模块 ⇒ 把 SPI 放 util 会当场编不过（详见 {@code
 * MoneyAuthority} 的类注）。★ 货币词表（{@code MoneyVocabulary}）同因住这里，不住 util 的 {@code
 * EconomyVocabulary}：它带类型，util 看不见那两个类型。
 */
package io.mosire.simos.economy.api.money;
