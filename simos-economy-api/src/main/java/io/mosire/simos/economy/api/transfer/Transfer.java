package io.mosire.simos.economy.api.transfer;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>转移：全系统唯一的「东西从 A 到 B」的事实</b>（H2；裁定 D2-A「{@code Transfer} 的形状搬进 {@code economy-api}、 主体改
 * {@code ActorRef}」+ 裁定 K4）。
 *
 * <p>★★ <b>它为什么是唯一的一处</b>：在它之前，"东西换手"这件事在三个地方各写了一遍 —— {@code ProductionSettlement} 的收支条目、{@code
 * 旧结算引擎（R3a 已删除）.transferIntraHexInputs} 的同格取材、 {@code 旧结算引擎（R3a 已删除）.settleHexes} 的同格借粮。三处各写各的 ⇒
 * 加第四条路（市场）时没人拦得住它长成第四套写法。 本类把"从 A 到 B 走了什么"钉成<b>一个形状</b>，于是"任何库存变动必有对应转移记录"这条不变量<b>可判</b>。
 *
 * <p>★★ <b>两端恒为 actor</b>（裁定 D1-A）：产权主体只有 actor；cohort 只出现在"消费取得"的读数里， <b>永远不是转移的一端</b>。★ 家户那一端用
 * {@code HouseholdActors.of(cohort)}（家户身份的唯一拼写点）—— 本类不认识 cohort，只认 actor。
 *
 * <p>★★ <b>必须带格</b>：账户 = <b>{@code (actor, location)}</b>（{@code HouseholdInventory} 的形状）⇒
 * 同一个人在两地各有账， 一条不带格的转移<b>说不清落进哪一本</b>。
 *
 * <p>★★ <b>方向由 {@code from}/{@code to} 表达，不用负号</b>：{@code goods} 逐值 {@code ≥ 0} —— "−5
 * 粮"这种写法会让"方向"与"数量"两个概念揉进一个符号里（本类型最容易长出的那种病）。
 *
 * <p>★★ <b>货币腿按币种</b>（{@code Map<CurrencyId, Long>}）：一个钱包里可以同时有银与铜，而"跨币种求和"是没有意义的运算 ⇒ 不许用单个 {@code
 * long}。★ <b>H4 起它真的有钱</b>（I5.3 的"只定义、不结算"到此结束）：货币工资/地租走只带货币腿的转移、市场成交走一对转移（见下）。
 *
 * <p>★★ <b>货币腿的方向 = {@code from → to}（与商品腿同向）</b>：这一点是 H4 定的口径，理由是<b>本类自己的不变量</b> —— "方向由 {@code
 * from}/{@code to} 表达，不用负号"（见下）。一张转移只有一对端点 ⇒ 两条腿不可能反向。 ⇒ <b>一笔"钱货两清"的买卖 = 一对转移</b> （同一格、同一天、同原因
 * {@link TransferReason#MARKET_TRADE}）：
 *
 * <pre>
 * ① 货：goods={商品: 数量}, money={}      from=卖方 → to=买方
 * ② 钱：goods={},          money={币种: 数量} from=买方 → to=卖方
 * </pre>
 *
 * ★ <b>为什么不做成"一张转移 + 按 reason 决定货币腿反向"</b>（如实记）：那会把"方向"这件事变成 {@code reason} 的函数 —— 同一份数据
 * 在两处（economy 的落账口与 app 的产权落账口）各要判一次"这笔该不该反向"，而判错<b>不会报错</b>（钱货反向的账看起来同样"平"）。
 * 一对同向转移没有这个问题：每一张转移的语义只有一条（{@code from} 给出、{@code to} 收到），钱与货各自成凭据。
 *
 * <p>★★ <b>id 的确定性</b>：{@code "tr-<day>-<seq>"}，{@code seq} = <b>当天</b>该账本内第几条（从 1 起），由 {@code
 * ProductionLedger.Accumulator} 分配 ⇒ 同一天同一序列必然给出同一串 id（重放 / 分支可比，与既有的 {@code debt-c<周期>-…} 同款做法）。★
 * id 里<b>不含 {@code "."}</b>：地址解析器在第一个 {@code "."} 处切名字，带点的 id 会让这条账<b>解析不到</b>（同 {@code debtIdOf}
 * 的三条硬要求）。
 *
 * <p>★★ <b>不变量（逐条当场抛，fail-closed）</b>：
 *
 * <ol>
 *   <li>{@code id} / {@code from} / {@code to} / {@code location} / {@code reason} / {@code
 *       settles} 非 null （空要用 {@code Optional.empty()}，不许用 null —— 同本仓各 record 的口径）；
 *   <li>{@code goods} / {@code money} 本身非 null（没有那一腿就<b>给空 map</b>，不许给 null）， <b>键与值都不得为
 *       null</b>、且<b>逐值 ≥ 0</b>（负数不是一种数量）；
 *   <li>★ <b>{@code from} 与 {@code to} 不得相等</b>：自转移（A → A）不是一条发生额，是坏数据 —— 它会让"库存变动 ↔
 *       转移记录"这条对账恒等成立却什么都没发生。
 * </ol>
 *
 * <p>★ <b>两张表都保序不可变</b>（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b> —— 它的迭代序不是内容的纯函数）；冻结写在字段赋值处（门禁的 {@code EI_EXPOSE_REP} 只看得到那里）。
 *
 * @param id 转移凭据的稳定身份（{@code tr-<day>-<seq>}）
 * @param day 发生日（世界日；与 id 的第二段同源）
 * @param from 出方（**恒为 actor**）
 * @param to 入方（**恒为 actor**，且不得与 {@code from} 相等）
 * @param location 账户所在格（账户 = {@code (actor, location)}）
 * @param goods 商品腿（**可为空 map** = 纯货币转移）；键值非 null、逐值 ≥ 0
 * @param money 货币腿（**H4 起有内容**；没有这一腿就给空 map）；键值非 null、逐值 ≥ 0
 *     <p>★★ <b>{@code settles} 今天恒为 {@code Optional.empty()} —— 这是裁定，不是遗漏</b>（H5）：本批真的产生了债权 （{@code
 *     Debt}：{@code LOAN_PRINCIPAL} 借出、{@code LOAN_REPAYMENT} 偿还），但那条债权的身份是 {@link
 *     io.mosire.simos.economy.api.id.DebtId}，而本字段的类型是 {@link ClaimId} —— <b>两者不是同一种东西</b> （{@code
 *     Claim} 体系随 {@code simos-ledger} 在 2026-09-27 退役，{@code ClaimId} 只作为契约保留）。⇒ 把 {@code DebtId}
 *     硬塞进一个 {@code ClaimId} 的槽位，等于凭空发明一套"债 = 债权"的对应关系（而本仓的债务表与 将来的索取权体系是两套东西）。★ <b>清偿指针留给 S2 的
 *     claim 体系</b>：等 {@code Claim} 落地时，借/还两条转移 各自指回它清偿的那条 claim —— 那时本字段才有第一个非空值。
 * @param reason 这笔转移是**什么制度**造成的
 * @param settles 若这笔转移在清偿某 {@link ClaimId} 债权 ⇒ 那条债权的 id；★ 本批恒 {@code Optional.empty()} （借粮/偿还产生的是
 *     {@code DebtId}，见上）
 */
public record Transfer(
    TransferId id,
    long day,
    ActorRef from,
    ActorRef to,
    HexCoord location,
    Map<CommodityId, Long> goods,
    Map<CurrencyId, Long> money,
    TransferReason reason,
    Optional<ClaimId> settles) {

  public Transfer {
    if (id == null) {
      throw new IllegalArgumentException("Transfer.id 不得为 null");
    }
    if (from == null) {
      throw new IllegalArgumentException("Transfer.from 不得为 null（两端恒为 actor）");
    }
    if (to == null) {
      throw new IllegalArgumentException("Transfer.to 不得为 null（两端恒为 actor）");
    }
    if (location == null) {
      throw new IllegalArgumentException("Transfer.location 不得为 null（账户 = (actor, location)）");
    }
    if (reason == null) {
      throw new IllegalArgumentException("Transfer.reason 不得为 null（这一笔是什么制度造成的必须显式）");
    }
    if (settles == null) {
      throw new IllegalArgumentException("Transfer.settles 不得为 null（非清偿用 Optional.empty()）");
    }
    if (from.equals(to)) {
      throw new IllegalArgumentException("Transfer 的两端不得相等（自转移不是一条发生额，是坏数据）: " + from);
    }
    // ★ 不可变写在**赋值处**（照 Industry.outputPerUnit / ProductionSettlement.Facts 的先例）：
    //   门禁的 EI_EXPOSE_REP 只看得见赋值处字面上的 Collections.unmodifiableMap。
    goods = Collections.unmodifiableMap(freezeGoods(goods));
    money = Collections.unmodifiableMap(freezeMoney(money));
  }

  /** 商品腿：键值非 null、逐值 ≥ 0，返回**保序的副本**。 */
  private static Map<CommodityId, Long> freezeGoods(Map<CommodityId, Long> goods) {
    if (goods == null) {
      throw new IllegalArgumentException("Transfer.goods 不得为 null（没有商品腿请给空 map）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Transfer.goods 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Transfer.goods 的数量不得为负（方向由 from/to 表达，不用负号）："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }

  /** 货币腿：同 {@link #freezeGoods} 的口径，键换成本批新建的 {@link CurrencyId}。 */
  private static Map<CurrencyId, Long> freezeMoney(Map<CurrencyId, Long> money) {
    if (money == null) {
      throw new IllegalArgumentException("Transfer.money 不得为 null（没有货币腿请给空 map）");
    }
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Transfer.money 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Transfer.money 的数量不得为负（方向由 from/to 表达，不用负号）："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return copy;
  }
}
