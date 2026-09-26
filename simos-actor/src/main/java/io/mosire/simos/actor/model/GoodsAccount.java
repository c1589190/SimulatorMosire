package io.mosire.simos.actor.model;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>库存：某人某一格上的商品余额</b>（spec §三 L277 的 {@code Goods ownership/inventory}）。<b>独立概念，不是 {@link
 * Actor} 的字段</b> —— "资产是 Actor <b>拥有的关系</b>，不是 Actor <b>本体的一部分</b>"。故本类型与 {@link Actor} 之间只有 {@link
 * #key()} 里的 {@code owner} 这一条引用，没有嵌套。
 *
 * <p>★★ <b>2026-09-27 裁定 S3：本类型是本切片唯一的那本账</b> —— 同族的产权表 {@code AssetHolding}（及 {@code
 * AssetClassKey} / {@code AssetHoldingKey}）已整块退役：实测生产侧零写入者（真档创世把 actor 起成 {@code
 * ActorData.empty()}）、economy 侧 {@code harvest} 硬编码空表 ⇒ 那条路径收益为 0。资产（土地/工具/牲畜）推迟到真需要时再加； ★ {@code
 * AssetKind} 粗类型词表仍被 economy 用作产能的键，故保留。
 *
 * <p>★★ <b>裁定 R6（本类型的立身之本，2026-09-26 用户裁定 —— 逐字引自 spec 追加-1）</b>：
 *
 * <blockquote>
 *
 * {@code GoodsAccount} 是新产权模型中商品余额的 {@code authoritative state}；既有 {@code simos-ledger.Account} 保持
 * legacy/unwired —— <b>不读、不写、不同步、不做镜像</b>。
 *
 * </blockquote>
 *
 * <p>★ <b>最忌讳的不是两个类同时存在，而是两边各存一份"100 grain"而没人知道哪份是真的</b> ⇒ 本类型是<b>唯一真源</b>， {@code ledger.Account}
 * 是<b>旧死代码</b>（去留另裁）。★ <b>反面纪律</b>：<b>不许</b>为了"复用"把 {@code ledger.Account} 拉活 —— 那会把 S2 的 {@code
 * money} / {@code reserved} / settlement 一大坨<b>未裁领域</b>提前拖进 S1。★ 这条禁令在构建期<b>有牙</b>：{@code
 * simos-ledger} 被本模块 POM 的 enforcer 列在禁列，本类想引用它<b>编译就过不去</b>；
 * 故本类只<b>点名</b>（{@code @code}）而不<b>链接</b>（{@code @link}）—— 同 {@code ActorRef} 对迁移前包的处置。
 *
 * <p>★★ <b>库存是存量，不是流量</b>（spec §2.5 L166）：存量 = 能保存、出售、转移 = 有产权；而 {@code Cohort consumption receipt}
 * 是流量（本结算窗口内<b>可用于最终消费</b>的流入，≠ cohort 拥有库存）。两件事<b>不合并</b> ⇒ 本类型的语义是
 * "该余额<b>是多少</b>"，<b>不是</b>"加多少"：写入口 {@code ActorData.withAccount} 是<b>整本覆盖</b>，而"转入 500"
 * 是**命令**（先读余额、再算出新余额），不是状态类型的方法。
 *
 * <p>★★ <b>0 余额保留，负数当场抛</b>：0 合法 ——「这一格这个人手里还有 0 斤粮」与「这个人根本不在这格」是<b>两件事</b>，前者在阶段 4（产出落
 * operator）与阶段 6（消费从 receipt 来）含义完全不同。 而负数不是余额：可以透支的是<b>信用</b>（S2 的领域），不是库存。★
 * 故余额表<b>不做任何归一</b>：既不移除 0，也不把 0 补成缺省。
 *
 * <p>★ <b>为什么没有 {@code id}</b>：身份就是聚合键 {@link #key()} —— 另造一个 id 等于把 "同一事实"记两处（铁律 1：id
 * 是身份，不在切片里另造同义 ID）。
 *
 * <p>★ <b>余额表保序不可变</b>：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b> —— 它的迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步<b>写在字段赋值处</b>（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装），故外部那张 {@code Map} 之后被改也不影响已建的账。
 *
 * @param key 聚合键（{@code (owner, location)}）
 * @param balances 各商品余额（{@code CommodityId} → 最小计量单位的定点整数；≥ 0，<b>0 保留</b>）
 */
public record GoodsAccount(GoodsAccountKey key, Map<CommodityId, Long> balances) {

  public GoodsAccount {
    if (key == null) {
      throw new IllegalArgumentException("GoodsAccount.key 不得为 null");
    }
    if (balances == null) {
      throw new IllegalArgumentException("GoodsAccount.balances 不得为 null");
    }
    Map<CommodityId, Long> balancesCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : balances.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("balances 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "GoodsAccount 的余额是存量、不得为负: " + entry.getKey() + "=" + entry.getValue());
      }
      balancesCopy.put(entry.getKey(), entry.getValue());
    }
    balances = Collections.unmodifiableMap(balancesCopy); // ★ 冻在赋值处（含防御性拷贝）
  }
}
