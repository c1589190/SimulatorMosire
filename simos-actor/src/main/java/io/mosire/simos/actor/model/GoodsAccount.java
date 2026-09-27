package io.mosire.simos.actor.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
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
 * <p>★★ <b>追加标注（2026-09-27，裁定 D2-A，上文一字不改）</b>：{@code simos-ledger} <b>整个模块已退役</b> （它零外部引用、无
 * handler、无人依赖；{@code Transfer} 的<b>形状</b>已按 D2-A 搬进 {@code simos-economy-api}）。 ⇒ 上文 R6
 * 的裁定<b>已成事实</b>：<b>本类型是商品余额的唯一真源</b>，而"另一边"那个类<b>不再存在</b>—— 于是这条禁令不再需要 enforcer
 * 的禁列来撑（那行已随之删除），它由"对面没有那个类"来保证。 ★ 全仓对 {@code simos-ledger} 的引用现在只剩<b>历史叙述</b>（本段与若干设计文档的留痕）。
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
 * <p>★★ <b>追加（2026-09-27，裁定 K15 / H4）：本账现在同时装【商品】与【货币】</b> —— H4 起钱要有落点，
 * 而"同一主体在同一格的那本账"只能有<b>一个</b>身份 ⇒ 加一个 {@code money} 组件， <b>不是</b>另立一张同键的表（那会把同一身份写成两处，本仓明令禁止）。 ★
 * <b>类名是历史的</b>（它最初只装商品）：为省一次全模块改名，本批保留名字、以本注为准； 若要改名（{@code HolderAccount}
 * 之类），属**纯机械重构**，记在关账的清理项里。 ★ 货币余额的口径与商品**逐条同款**：最小币值定点整数、≥ 0、0 保留、保序不可变、冻结写在赋值处。 ★
 * 货币的<b>发行/回笼</b>不在本类型：那要 {@code MoneyAuthority}（本批无实现者）⇒ 任何账户的货币余额不得为负。
 *
 * @param key 聚合键（{@code (owner, location)}）
 * @param balances 各商品余额（{@code CommodityId} → 最小计量单位的定点整数；≥ 0，<b>0 保留</b>）
 * @param money 各币种余额（{@code CurrencyId} → 最小币值的定点整数；≥ 0，<b>0 保留</b>）
 */
public record GoodsAccount(
    GoodsAccountKey key, Map<CommodityId, Long> balances, Map<CurrencyId, Long> money) {

  /**
   * 便捷构造器：**只有商品、没有钱**（钱为空表）。
   *
   * <p>★ 存在的理由：H4 之前建的账户（以及大量只关心商品的夹具与读法）不必为"多了一个组件"逐处改。 ★ <b>它不是"忘记传钱"的掩护</b>：真正要动钱的路径（{@code
   * OwnershipBooks} 的落账、{@code HouseholdSeeder} 的创世禀赋）一律走**三参**构造器；而"钱有没有被序列化丢"由 {@code ActorCodec}
   * 的往返用例守着。
   */
  public GoodsAccount(GoodsAccountKey key, Map<CommodityId, Long> balances) {
    this(key, balances, Map.of());
  }

  public GoodsAccount {
    if (key == null) {
      throw new IllegalArgumentException("GoodsAccount.key 不得为 null");
    }
    if (balances == null) {
      throw new IllegalArgumentException("GoodsAccount.balances 不得为 null");
    }
    if (money == null) {
      throw new IllegalArgumentException("GoodsAccount.money 不得为 null（没有钱用空 map）");
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
    // ★ 货币余额：守卫与冻结**与 balances 逐条同款**（口径一致，读的人不必记两套规则）。
    Map<CurrencyId, Long> moneyCopy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("money 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "GoodsAccount 的货币余额是存量、不得为负（发行/回笼要 MoneyAuthority，本批无实现者）: "
                + entry.getKey()
                + "="
                + entry.getValue());
      }
      moneyCopy.put(entry.getKey(), entry.getValue());
    }
    money = Collections.unmodifiableMap(moneyCopy); // ★ 冻在赋值处（含防御性拷贝）
  }
}
