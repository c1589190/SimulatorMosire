package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * ★★ <b>E（2026-10-09 裁定 R1/R3/R4/R6）：本轮的"钱的价格"——家户货币估值与成交判据的唯一行情来源</b>。
 *
 * <p>★★ <b>它解决哪件事</b>：R1 说"单个家户其实什么都愿意收，实际是根据货币价格来判断的"。于是"收不收一笔异币" 必须有一个可判的价格：本类给出<b>微计价物 /
 * 毫币</b>量纲的估值（与 {@link HouseholdValuationBook#MICRO_PER_MILLI}
 * 同一微刻度），估值来源只有两条，<b>没有第三个来源、也没有任何摩擦参数</b>（R4）：
 *
 * <pre>
 * ① 有报价（世界行情）  ⇒ 按官方汇率的中价折算（{@link #valueMicro(CurrencyId, CurrencyId)}）
 * ② 没有报价但当地流通  ⇒ 按面值 1:1（{@code 1000} 微/毫）= R3「没做口岸 ⇒ 事实上的同一个市场区 ⇒ 自然统一汇率」
 * ③ 既没报价、当地也不流通 ⇒ 说不出价 ⇒ 调用方按"不划算"处理（市场性理由，见 MarketSettlement 的唯一拼写点）
 * </pre>
 *
 * <p>★★ <b>为什么"当地流通"这一维必须有</b>（R6 的算力护栏）：三不管地带允许任何货币流通，但"任意币"必须落成
 * <b>当地实际存在的币种集合</b>，不是"世界全部币种"——否则每格都要按币种总数铺开（每格槽数 = 币种总数， 全图槽数 × C 会爆，见上位文档与 stage2 设计书 §12
 * 的性能实测）。本类的 {@link #circulationByRegion} 就是那个 有界集合：<b>该市场区的成员格 + 直接邻接区</b>里家户实际持有的币种（撮合域本来就只到这一圈，见
 * {@code matchAcrossRegions}）。它只决定"认不认得出这种钱"，不决定价格。
 *
 * <p>★★ <b>世界行情怎么来（中价、无摩擦）</b>：{@link FxRoundInput} 的窗口逐条是 {@link OfficialRate} 的双边报价 （{@code
 * buyPerMille}/{@code sellPerMille}，量纲 = 每 1000 个 base 最小单位付多少 quote 最小单位）。本类取 <b>中价</b> {@code
 * (buy + sell) / 2} 作为唯一价格：R4 明令本批不引入任何摩擦参数，买卖价差就是摩擦 ⇒ 不用它。 多条报价（多
 * GOV/多区）连成一张图，从<b>规范序第一个币种</b>（根）出发按币种 id 升序做一次确定序搜索， 每个币种<b>首次被访问</b>时定值 ⇒ 同一份输入永远给出同一张行情表（不依赖任何
 * Map 的迭代序）。
 *
 * <p>★ <b>量纲</b>：{@code V(c)} = <b>微根币 / 毫 c</b>；根币对自己恒 {@code 1000}（= 面值 1:1）。 跨币值 = {@code
 * V(currency) × 1000 / V(numeraire)}（整数、向下取整、至少 1）。
 *
 * <p>★ <b>不是状态</b>：本类逐轮由 {@code MarketRound.fx()} + 本轮账户表现算，不进 {@code EconomyData}、
 * 不进变更集、不落盘（I17：汇率不进状态）。
 */
final class CurrencyValuation {

  /** 面值（1:1）：1 毫钱 = {@value #FACE_VALUE_MICRO} 微计价物（与保留价同微刻度）。 */
  static final long FACE_VALUE_MICRO = HouseholdValuationBook.MICRO_PER_MILLI;

  /** 根币对自己（= 面值 1:1）；{@code V(c)} 的单位就是它。 */
  private static final long ROOT_VALUE_MICRO = FACE_VALUE_MICRO;

  /** 没有外汇面 / 没有报价的世界：行情表为空（一切按面值或"不可判"处理）。 */
  private static final CurrencyValuation NONE = new CurrencyValuation(Map.of(), Set.of(), Map.of());

  /** {@code V(c)}：微根币 / 毫 c（只含被报价图连通的币种；缺键 = 没有报价）。 */
  private final Map<CurrencyId, Long> quotedMicroPerMilli;

  /** 有报价的币种集合（世界行情：人人认得出，与所在地无关）。 */
  private final Set<CurrencyId> quoted;

  /** 区 id → 当地实际流通的币种（该区成员格与直接邻接区里家户实际持有的币；有界集合）。 */
  private final Map<String, Set<CurrencyId>> circulationByRegion;

  private CurrencyValuation(
      Map<CurrencyId, Long> quotedMicroPerMilli,
      Set<CurrencyId> quoted,
      Map<String, Set<CurrencyId>> circulationByRegion) {
    this.quotedMicroPerMilli =
        Collections.unmodifiableMap(new LinkedHashMap<>(quotedMicroPerMilli));
    this.quoted = Collections.unmodifiableSet(new LinkedHashSet<>(quoted));
    this.circulationByRegion =
        Collections.unmodifiableMap(new LinkedHashMap<>(circulationByRegion));
  }

  /** 没有报价、也没有当地流通信息（旧调用方/单币世界）—— 一切按面值或"不可判"。 */
  static CurrencyValuation none() {
    return NONE;
  }

  /**
   * ★★ <b>装配本轮行情</b>：官方汇率（区级覆盖已在 {@link FxRoundInput} 里生效）+ 当地流通币种。
   *
   * @param fx 本轮外汇入参（{@link FxRoundInput#none()} ⇒ 空行情）
   * @param circulationByRegion 区 id → 当地实际流通的币种（{@link #circulationByRegion} 产出；不得为 null）
   */
  static CurrencyValuation of(FxRoundInput fx, Map<String, Set<CurrencyId>> circulationByRegion) {
    Objects.requireNonNull(fx, "CurrencyValuation.of 的 fx 不得为 null（没有就给 FxRoundInput.none()）");
    Objects.requireNonNull(circulationByRegion, "CurrencyValuation.of 的当地流通表不得为 null");
    Map<CurrencyId, Long> values = quotedValues(fx);
    Set<CurrencyId> quotedCurrencies = new LinkedHashSet<>(values.keySet());
    return new CurrencyValuation(values, quotedCurrencies, circulationByRegion);
  }

  /**
   * ★★ <b>当地实际流通的币种集合</b>（唯一拼写点）：逐格取该格家户<b>实际持有</b>（余额 &gt; 0）的币种， 再按区合并；一个区的"看得见的钱" = 本区成员格 ∪
   * <b>直接邻接区</b>的并集（撮合域只到这一圈）。
   *
   * <p>★ <b>为什么按区而不是按格</b>：局面的撮合单位是市场区（区内 + 直接邻接区），家户能遇到的付款币种
   * 只有这一圈里的钱；按格算会把"隔壁格的同区家户持有的钱"误判成不认识。遍历序 = 区（声明序）× 格（q,r 升序） × 币种（id 升序）⇒ 内容的纯函数。
   *
   * @param topology 本轮市场拓扑（区成员与邻接的唯一来源）
   * @param rowsByHex 格键（{@code q_r}）→ 该格家户（{@code EconomySettlement.rowsByHex} 的产出）
   * @param householdMoney 家户 → 币种 → 毫（本轮工作副本；只读）
   */
  static Map<String, Set<CurrencyId>> circulationByRegion(
      MarketTopology topology,
      Map<String, List<HouseholdId>> rowsByHex,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney) {
    Objects.requireNonNull(topology, "circulationByRegion 的拓扑不得为 null");
    Objects.requireNonNull(rowsByHex, "circulationByRegion 的逐格家户表不得为 null");
    Objects.requireNonNull(householdMoney, "circulationByRegion 的家户货币表不得为 null");
    // ── 逐格：实际持有（余额 > 0）的币种，按币种 id 升序 ────────────────────────────────
    Map<String, Set<CurrencyId>> byHex = new LinkedHashMap<>();
    List<String> hexKeys = new ArrayList<>(rowsByHex.keySet());
    hexKeys.sort(Comparator.naturalOrder());
    for (String hexKey : hexKeys) {
      Set<CurrencyId> held = new LinkedHashSet<>();
      List<HouseholdId> households = rowsByHex.getOrDefault(hexKey, List.of());
      List<HouseholdId> ordered = new ArrayList<>(households);
      ordered.sort(Comparator.comparing(HouseholdId::value));
      for (HouseholdId household : ordered) {
        Map<CurrencyId, Long> money = householdMoney.getOrDefault(household, Map.of());
        List<CurrencyId> currencies = new ArrayList<>(money.keySet());
        currencies.sort(Comparator.comparing(CurrencyId::value));
        for (CurrencyId currency : currencies) {
          if (money.getOrDefault(currency, 0L) > 0L) {
            held.add(currency);
          }
        }
      }
      byHex.put(hexKey, held);
    }
    // ── 逐区：本区成员格 ∪ 直接邻接区的成员格 ──────────────────────────────────────────
    Map<String, Set<CurrencyId>> circulation = new LinkedHashMap<>();
    for (MarketRegion region : topology.regions()) {
      String regionId = region.node().nodeId();
      Set<CurrencyId> visible = new LinkedHashSet<>();
      collectHeld(visible, region, byHex);
      for (MarketRegion neighbour : topology.regions()) {
        if (neighbour.node().nodeId().equals(regionId) || !topology.adjacent(region, neighbour)) {
          continue;
        }
        collectHeld(visible, neighbour, byHex);
      }
      circulation.put(regionId, visible);
    }
    return circulation;
  }

  /** 把一个区的成员格里实际持有的币种并进 {@code target}（格序 = q,r 升序）。 */
  private static void collectHeld(
      Set<CurrencyId> target, MarketRegion region, Map<String, Set<CurrencyId>> byHex) {
    List<HexCoord> members = new ArrayList<>(region.members());
    members.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (HexCoord member : members) {
      target.addAll(byHex.getOrDefault(hexKeyOf(member), Set.of()));
    }
  }

  /** 格键的唯一拼写点（与 {@code EconomySettlement.rowsByHex} 同字面）。 */
  private static String hexKeyOf(HexCoord hex) {
    return hex.q() + "_" + hex.r();
  }

  /**
   * ★★ <b>该币种有没有可判的价格</b>：有世界报价（人人认）<b>或</b>在该区当地实际流通（看得见的钱）。
   *
   * <p>★ 两者都没有 ⇒ 说不出价（调用方按"不划算"落市场性理由，绝不当成 1:1 静默放行）。
   */
  boolean recognises(String regionId, CurrencyId currency) {
    Objects.requireNonNull(currency, "recognises 的币种不得为 null");
    if (quoted.contains(currency)) {
      return true;
    }
    return regionId != null
        && circulationByRegion.getOrDefault(regionId, Set.of()).contains(currency);
  }

  /**
   * ★★ <b>该币种在计价物为 {@code numeraire} 的家户眼里的估值</b>（微 numeraire / 毫 currency）。
   *
   * <pre>
   * currency == numeraire                ⇒ 1000（本币对自己 = 面值 1:1，R3 的锚）
   * 两者都有报价                          ⇒ max(1, V(currency) × 1000 ÷ V(numeraire))
   * 否则（任一没有报价）                  ⇒ 0 = <b>不可判</b>（调用方按 recognises 决定面值/不划算）
   * </pre>
   *
   * @return {@code > 0} = 微 numeraire / 毫 currency；{@code 0} = 本表说不出这个价
   */
  long valueMicro(CurrencyId numeraire, CurrencyId currency) {
    Objects.requireNonNull(numeraire, "valueMicro 的计价物不得为 null");
    Objects.requireNonNull(currency, "valueMicro 的币种不得为 null");
    if (currency.equals(numeraire)) {
      return FACE_VALUE_MICRO;
    }
    Long currencyValue = quotedMicroPerMilli.get(currency);
    Long numeraireValue = quotedMicroPerMilli.get(numeraire);
    if (currencyValue == null || numeraireValue == null || numeraireValue <= 0L) {
      return 0L;
    }
    return Math.max(1L, currencyValue * 1000L / numeraireValue);
  }

  /**
   * ★★ <b>E 的估值裁决（唯一拼写点）</b>：本币 ⇒ 面值 1:1；有世界行情 ⇒ 行情价；当地流通 ⇒ 面值（R3）； 三者都不是 ⇒ <b>说不出价</b>（{@code
   * 0}，调用方按"不划算"落市场性理由，绝不静默当 1:1）。
   *
   * <p>★ 结算侧（{@code MarketSettlement} 的三条腿）与价目表侧（{@link HouseholdValuationBook#derive}） 都只经本方法取价 ⇒
   * 两处不可能漂开。
   *
   * @param locallyCirculating 该币在<b>这户/这格看得见的范围</b>里实际存在（结算 = 本区 ∪ 直接邻接区； 价目表 = 本户实际持有）
   * @return {@code > 0} = 微 numeraire / 毫 currency；{@code 0} = 说不出价
   */
  long valuationMicro(CurrencyId numeraire, CurrencyId currency, boolean locallyCirculating) {
    long quoted = valueMicro(numeraire, currency);
    if (quoted > 0L) {
      return quoted;
    }
    if (currency.equals(numeraire) || locallyCirculating) {
      return FACE_VALUE_MICRO;
    }
    return 0L;
  }

  /**
   * ★★ <b>E 的估值裁决（按区）</b>：{@link #valuationMicro(CurrencyId, CurrencyId, boolean)} 的便利入口 —— "当地流通"取
   * {@link #recognises}(区, 币)。
   *
   * @return {@code > 0} = 微 numeraire / 毫 currency；{@code 0} = 说不出价（调用方按不划算处理）
   */
  long valuationMicro(CurrencyId numeraire, CurrencyId currency, String regionId) {
    return valuationMicro(numeraire, currency, recognises(regionId, currency));
  }

  /** ★★ 本币对自己 = 面值 1:1（{@link #FACE_VALUE_MICRO}）；★ 这是 R3「自然统一汇率」的锚。 */
  static long faceValueMicro() {
    return FACE_VALUE_MICRO;
  }

  /** 有报价的币种（规范序；供 {@link HouseholdValuationBook} 收敛估值表的键集）。 */
  Set<CurrencyId> quotedCurrencies() {
    return quoted;
  }

  /** ★★ <b>逐区"当地实际流通的币种"的一句话摘要</b>（§一.9：DEBUG 写"为什么"——"这种钱我怎么认不出来" 必须能一眼看出来）。只用于日志，不参与任何判定。 */
  String circulationSummary() {
    if (circulationByRegion.isEmpty()) {
      return "{}";
    }
    List<String> ordered = new ArrayList<>(circulationByRegion.keySet());
    ordered.sort(Comparator.naturalOrder());
    StringBuilder text = new StringBuilder("{");
    for (String regionId : ordered) {
      if (text.length() > 1) {
        text.append(", ");
      }
      List<String> currencies = new ArrayList<>();
      for (CurrencyId currency : circulationByRegion.getOrDefault(regionId, Set.of())) {
        currencies.add(currency.value());
      }
      currencies.sort(Comparator.naturalOrder());
      text.append(regionId).append('=').append(currencies);
    }
    return text.append('}').toString();
  }

  /**
   * ★★ <b>世界行情表：逐币种 {@code V(c)} = 微根币 / 毫 c</b>（只含被报价图连通的币种）。
   *
   * <pre>
   * 根 = 全部出现过的币种里 id 升序的第一个（确定序，不依赖 Map 迭代序）；V(根) = 1000
   * 对每条报价 (base, quote) 取中价 mid = (buyPerMille + sellPerMille) ÷ 2（整数、向下取整）
   *   ⇒ 1000 毫 base = mid 毫 quote ⇒ V(base) = V(quote) × mid ÷ 1000，V(quote) = V(base) × 1000 ÷ mid
   * 从根做一次确定序搜索（邻接按币种 id 升序）：首次访问即定值（先到先得 ⇒ 多份冲突报价下确定）
   * </pre>
   *
   * <p>★ <b>多份冲突报价</b>：本批取"确定序搜索首次访问"的那一份（同一份输入恒同结果）。按区/按 GOV 的报价冲突 具名记录是 A 批的事（上位文档
   * §4.4），本批不新增第二套口径。
   */
  private static Map<CurrencyId, Long> quotedValues(FxRoundInput fx) {
    Map<CurrencyId, Map<CurrencyId, Long>> edges = new TreeMap<>(CurrencyIdOrder.INSTANCE);
    for (FxRoundInput.Window window : fx.windows()) {
      OfficialRate rate = window.rate();
      long midPerMille = Math.max(1L, (rate.buyPerMille() + rate.sellPerMille()) / 2L);
      edges.computeIfAbsent(rate.base(), ignored -> new TreeMap<>(CurrencyIdOrder.INSTANCE));
      edges.computeIfAbsent(rate.quote(), ignored -> new TreeMap<>(CurrencyIdOrder.INSTANCE));
      // 同一币对多份报价：只留确定序第一条（先到先得；不静默取平均）。
      edges.get(rate.base()).putIfAbsent(rate.quote(), midPerMille);
      edges.get(rate.quote()).putIfAbsent(rate.base(), -midPerMille);
    }
    if (edges.isEmpty()) {
      return Map.of();
    }
    CurrencyId root = edges.keySet().iterator().next();
    Map<CurrencyId, Long> values = new LinkedHashMap<>();
    values.put(root, ROOT_VALUE_MICRO);
    List<CurrencyId> frontier = new ArrayList<>();
    frontier.add(root);
    while (!frontier.isEmpty()) {
      List<CurrencyId> next = new ArrayList<>();
      for (CurrencyId from : frontier) {
        if (from == null) {
          continue;
        }
        long fromValue = values.getOrDefault(from, 0L);
        if (fromValue <= 0L) {
          continue;
        }
        Map<CurrencyId, Long> neighbours = edges.getOrDefault(from, Map.of());
        for (Map.Entry<CurrencyId, Long> edge : neighbours.entrySet()) {
          CurrencyId to = edge.getKey();
          if (values.containsKey(to)) {
            continue;
          }
          long signed = edge.getValue();
          // signed > 0：from = base，to = quote（1000 from = signed 毫 to ⇒ V(from) =
          // V(to)×signed/1000）
          //          故 V(to) = V(from) × 1000 ÷ signed
          // signed < 0：from = quote，to = base ⇒ V(to) = V(from) × (-signed) ÷ 1000
          long toValue = signed > 0L ? fromValue * 1000L / signed : fromValue * (-signed) / 1000L;
          values.put(to, Math.max(1L, toValue));
          next.add(to);
        }
      }
      frontier = next;
    }
    return values;
  }

  /** 币种按 id 升序（确定序的唯一拼写点；键序不依赖任何 Map 的迭代序）。 */
  private enum CurrencyIdOrder implements Comparator<CurrencyId> {
    INSTANCE;

    @Override
    public int compare(CurrencyId left, CurrencyId right) {
      return left.value().compareTo(right.value());
    }
  }
}
