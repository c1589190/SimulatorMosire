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
 * <p>★★ <b>追加（2026-09-27，M1.2）：本账多出第三、第四张表 —— 冻结额</b>（{@link #frozenBalances()} / {@link
 * #frozenMoney()}）。<b>余额</b>是"这本账有多少"，<b>冻结额</b>是"其中多少已经被明确占用"； 两者之差 = <b>可支配</b>（唯一算法住在 {@code
 * AvailableStock}，本类型只存事实、不做减法）。
 *
 * <p>★★ <b>冻结额的边界（用户 2026-09-27 裁定，这是本类型最容易被做歪的一处）</b>：{@code frozen} <b>只表达"已明确的占用"</b> ——
 * 挂单要卖的货、已承诺的交付。 <b>生活保留、必要生产投入、经营储备一律不在这里</b>：它们是<b>决策层的策略</b>，照
 *
 * <pre>
 * 可售库存 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)   // 各项互不重复扣除；落点是 M2 的订单/保留算式
 * </pre>
 *
 * 逐项算在 M2。⇒ 两条禁令（都不是洁癖，是"把储备政策搬进账户模型"这个错误的两个具体形态）：
 *
 * <ol>
 *   <li><b>不许</b>把 {@code MarketSettlement.MARKET_SELF_RESERVE_PER_MILLE} 或 {@code
 *       旧结算引擎（R3a 已删除）.LENDER_SUBSISTENCE_RESERVE_PER_MILLE} 折进 {@code frozen} ——
 *       那两条是<b>只读算式的中间量</b>（{@code 旧结算引擎（R3a 已删除）}
 *       逐字写着"保留额不是冻结起来的一笔粮"），搬进来就等于让"制度参数"变成"某人的库存事实"；
 *   <li><b>不许</b>让 {@code frozen} 变成"按阶层/人口自动算出来的保留额" —— 那样一来，"已经承诺出去的东西"与"自己打算留着的东西" 就再也分不开，而 M2
 *       的算式恰恰要求它们<b>逐项互不重复扣除</b>。
 * </ol>
 *
 * <p>★★ <b>为什么是两张表、不是一个 {@code Map<Asset, Long>}</b>（★ 形态由实现裁，理由记在这里）：本账既有的形状<b>就是两张余额表</b> （{@code
 * balances} 键 {@link CommodityId}、{@code money} 键 {@link CurrencyId}）—— 冻结若合成一张统一键的表，就得多造一个"商品 ∪
 * 货币"的联合键类型， 而每一处"读某个币种的冻结额"都要先做一次类型分派；<b>照旧两张表</b>则与余额<b>逐键同型</b>：同一个 {@code CommodityId} / {@code
 * CurrencyId} 在余额表与冻结表里的键是同一个，读的人不必记两套规则， 构造期守卫也能按<b>逐条同款</b>的两段写（口径一致 —— 与 H4 把货币并进本账时给的理由是同一条）。
 *
 * <p>★ <b>冻结额也是存量</b>：<b>绝对值</b>（"现在被占用多少"），不是增量 —— 写入口给的是"这本账现在的冻结额是多少"， 与余额同一口径（{@code
 * ActorData.withAccount} 是整本覆盖）。<b>幂等由这条语义来</b>：同一个数写两次 ⇒ 状态逐字段相同。 ★ <b>0 保留</b>：冻结表同样不做任何归一 —— 一条
 * {@code 0} 的意思是"这个商品的占用<u>曾经</u>存在、现在是 0"，与"根本没有这一条"在审计上不是同一件事。
 *
 * <p>★ <b>缺键（{@code null}）⇒ 空表</b>（旧档兼容，照 {@code ActorData} 的同款口径）：M1.2 之前落盘的 {@code GoodsAccount}
 * 没有这两张表，Jackson 会绑成 {@code null} ⇒ 收成空表、<b>此处不抛</b>（抛了等于"旧档全部读不回来"）。 ★ 方向是
 * fail-closed：旧档没提冻结，就是<b>没有冻结</b>。★ 而余额那两张表不适用本条：它们是这本账的<b>本体</b>，{@code null} 仍是坏数据、照样抛。
 *
 * @param key 聚合键（{@code (owner, location)}）
 * @param balances 各商品余额（{@code CommodityId} → 最小计量单位的定点整数；≥ 0，<b>0 保留</b>）
 * @param money 各币种余额（{@code CurrencyId} → 最小币值的定点整数；≥ 0，<b>0 保留</b>）
 * @param frozenBalances 各商品的<b>冻结额</b>（{@code CommodityId} → 定点整数；{@code 0 ≤ 冻结 ≤ 余额}，缺键 = 0，<b>0
 *     保留</b>）
 * @param frozenMoney 各币种的<b>冻结额</b>（{@code CurrencyId} → 定点整数；口径与 {@code frozenBalances} 逐条同款）
 */
public record GoodsAccount(
    GoodsAccountKey key,
    Map<CommodityId, Long> balances,
    Map<CurrencyId, Long> money,
    Map<CommodityId, Long> frozenBalances,
    Map<CurrencyId, Long> frozenMoney) {

  /**
   * 便捷构造器：**只有商品、没有钱、没有冻结**（货币与两张冻结表都是空表）。
   *
   * <p>★ 存在的理由：H4 之前建的账户（以及大量只关心商品的夹具与读法）不必为"多了一个组件"逐处改。 ★ <b>它不是"忘记传钱"的掩护</b>：真正要动钱的路径（{@code
   * OwnershipBooks} 的落账、{@code HouseholdSeeder} 的创世禀赋）一律走**五参**构造器；而"钱有没有被序列化丢"由 {@code
   * ActorCodecTest#moneyRoundTripsThroughTheWireWithZeroKeptAndAbsentDistinct} 守着（★ M1.0 补记：本条曾声称
   * "由 {@code ActorCodec} 的往返用例守着"，而**那条用例当时并不存在** —— 本仓第 5 例幻影判别力，落盘路径因此在整个 M1 之前无人守）。
   *
   * <p>★★ <b>M1.3 收口后，全仓 {@code src/main} 里没有本构造器的调用点</b>（{@code ActorPayloads} / {@code
   * HouseholdSeeder} / {@code OwnershipBooks} 五处落账点全部显式带过 两张冻结表）—— 留着它只为测试夹具与"确认无钱、无冻结"的旧读法。★
   * <b>写回点一律用五参</b>：它给的是"冻结 = 空表"， 用它写回会把已有冻结静默清零。
   */
  public GoodsAccount(GoodsAccountKey key, Map<CommodityId, Long> balances) {
    this(key, balances, Map.of(), Map.of(), Map.of());
  }

  /**
   * 便捷构造器：商品 + 货币，**没有冻结**（两张冻结表都是空表）—— <b>语义与 M1.2 之前逐字不变</b>。
   *
   * <p>★★ <b>但"整本覆盖"的写入口要小心它</b>：{@code OwnershipBooks} 的 5 个落账点若用它写回，会把<b>已有的冻结额静默清零</b>（与 H4
   * 两参构造器把钱静默清零是同一个形态的病）⇒ 那些点必须显式把冻结带过（见 {@code OwnershipBooks} 的注释与用例）。 ★ <b>M1.3 收口后，全仓 {@code
   * src/main} 里同样没有本构造器的调用点</b>（载荷解析与创世装配已改走五参）—— 理由与两参那条一字不差。
   */
  public GoodsAccount(
      GoodsAccountKey key, Map<CommodityId, Long> balances, Map<CurrencyId, Long> money) {
    this(key, balances, money, Map.of(), Map.of());
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
    // ★★ M1.2：两张**冻结**表缺键（null）⇒ 空表（旧档兼容，fail-closed 方向 —— 旧档没提冻结就是没有冻结）。
    //   与上面那两条**刻意不同**：余额表是这本账的本体，null 是坏数据；冻结表是 M1.2 新增的组件，
    //   M1.2 之前落盘的 JSON 里根本没有它们（照 ActorData 的同款口径）。
    if (frozenBalances == null) {
      frozenBalances = Map.of();
    }
    if (frozenMoney == null) {
      frozenMoney = Map.of();
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
    // ★★ M1.2 冻结守卫：`0 ≤ 冻结 ≤ 余额`，**逐键**（缺键 = 0）。商品与货币**两张表各判一遍**（口径同款）。
    //   ★ 判在副本上（上面两张表已经归一并冻结完），故守卫读到的余额就是本账最终的余额。
    //   ★ 缺键 = 0 这一条**有牙**：冻结表里出现一个余额表里没有的键、且冻结 > 0 ⇒ 当场抛（"占用了不存在的东西"）。
    Map<CommodityId, Long> frozenBalancesCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : frozenBalances.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("frozenBalances 的键与值都不得为 null: " + entry.getKey());
      }
      long frozen = entry.getValue();
      if (frozen < 0L) {
        throw new IllegalArgumentException(
            "冻结额不得为负（它是「已明确的占用」，不是可以透支的信用）: " + entry.getKey() + "=" + frozen);
      }
      long balance = balances.getOrDefault(entry.getKey(), 0L);
      if (frozen > balance) {
        throw new IllegalArgumentException(
            "冻结额不得超过余额（冻结只是把已有的一部分标成「已占用」，它不凭空造出库存）: "
                + entry.getKey()
                + " 冻结="
                + frozen
                + " 余额="
                + balance);
      }
      frozenBalancesCopy.put(entry.getKey(), frozen);
    }
    frozenBalances = Collections.unmodifiableMap(frozenBalancesCopy); // ★ 冻在赋值处（含防御性拷贝）
    Map<CurrencyId, Long> frozenMoneyCopy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : frozenMoney.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("frozenMoney 的键与值都不得为 null: " + entry.getKey());
      }
      long frozen = entry.getValue();
      if (frozen < 0L) {
        throw new IllegalArgumentException(
            "货币冻结额不得为负（它是「已明确的占用」，不是可以透支的信用）: " + entry.getKey() + "=" + frozen);
      }
      long balance = money.getOrDefault(entry.getKey(), 0L);
      if (frozen > balance) {
        throw new IllegalArgumentException(
            "货币冻结额不得超过余额（冻结只是把已有的一部分标成「已占用」，它不凭空造出钱）: "
                + entry.getKey()
                + " 冻结="
                + frozen
                + " 余额="
                + balance);
      }
      frozenMoneyCopy.put(entry.getKey(), frozen);
    }
    frozenMoney = Collections.unmodifiableMap(frozenMoneyCopy); // ★ 冻在赋值处（含防御性拷贝）
  }
}
