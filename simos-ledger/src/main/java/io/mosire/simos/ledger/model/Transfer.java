package io.mosire.simos.ledger.model;

import io.mosire.simos.economy.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.TransferId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 双边转移凭据（增量 2 spec §3 逐字）：一笔"从谁到谁、走了什么货、多少钱"的**流水**。
 *
 * <p>★★ **append-only 流水**：{@link io.mosire.simos.ledger.LedgerData#transfers()} 里的每条是既成事实，
 * 删改只能靠再记一笔反向流水（本切片不实现任何撤销语义）。它是守恒的**凭据来源**，不是守恒本身。
 *
 * <p>★ **守恒不在这里**（设计稿 §6.2 / spec §三）：每一笔转移要满足 {@code 买方扣款 = 卖方入账 + 政府税入账 + 运输方收入}、{@code 卖方出货 =
 * 买方/在途入货 + 明示损耗}—— 那需要跨账户聚合，是**命令层/协调器**的校验，**不落成第二份真相**（本切片只记两支腿）。
 *
 * <p>★ **量纲**：{@code goods} 按**最小计量单位**；{@code money} 按**最小币值**；{@code day} 是**日**（日制底座）。
 *
 * <p>★ 不变量（spec §3 逐字）：{@code goods} 逐商品 {@code ≥ 0}、{@code money ≥ 0}。 {@code goods} 保序不可变（{@code
 * LinkedHashMap} + {@code Collections.unmodifiableMap}，**禁用 {@code Map.copyOf}**），冻结写在字段赋值处。
 *
 * @param id 稳定身份（各腿各自的凭据可回溯到同一 ID）
 * @param day 发生日（日）
 * @param from 出方
 * @param to 入方
 * @param goods 商品腿（**可为空 map**：纯货币转移）；键与值都不得为 null，逐值 ≥ 0
 * @param money 货币腿（**可为 0**）；不得为负
 * @param settles 若这笔转移是在清偿某债权 ⇒ 那条债权的 ID；否则 {@code Optional.empty()}
 */
public record Transfer(
    TransferId id,
    long day,
    ActorRef from,
    ActorRef to,
    Map<CommodityId, Long> goods,
    long money,
    Optional<ClaimId> settles) {

  public Transfer {
    if (id == null) {
      throw new IllegalArgumentException("Transfer.id 不得为 null");
    }
    if (from == null) {
      throw new IllegalArgumentException("Transfer.from 不得为 null");
    }
    if (to == null) {
      throw new IllegalArgumentException("Transfer.to 不得为 null");
    }
    if (goods == null) {
      throw new IllegalArgumentException("Transfer.goods 不得为 null（无商品腿用空 map）");
    }
    if (settles == null) {
      throw new IllegalArgumentException("Transfer.settles 不得为 null（非清偿用 Optional.empty()）");
    }
    Map<CommodityId, Long> goodsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Transfer.goods 的键与值都不得为 null: " + entry.getKey());
      }
      goodsCopy.put(entry.getKey(), entry.getValue());
    }
    goods = Collections.unmodifiableMap(goodsCopy); // ★ 冻在赋值处（EI_EXPOSE_REP 只认它看得见的）
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "Transfer.goods 的数量不得为负：商品 " + entry.getKey() + " = " + entry.getValue());
      }
    }
    if (money < 0) {
      throw new IllegalArgumentException("Transfer.money 不得为负: " + money);
    }
  }
}
