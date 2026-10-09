package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketTaxLayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>P-T1b：三层税的计税、折算与分摊（全仓唯一拼写点）</b>——口岸设计书 §13 + 全链计划 §2.2/§3 I-C1/I-C3/I-C10。
 *
 * <pre>
 * ① 税基（只对货值，运费不计）：货款 payment = ⌈成交毛量 × 买方币单价 ÷ 1000⌉（毫，买方支付币）
 * ② 区级税额 = ⌊毛量 × 从量税率(折成买方币) ÷ 1000⌋ + ⌊货款 × 从价税率(‰) ÷ 1000⌋
 * ③ 分摊到各收税政府国库：分得_k = ⌊总额 × w_k ÷ Σw⌋，余数给规范序最后一家 ⇒ Σ 分得 == 总额（逐值守恒）
 * </pre>
 *
 * <p>★★ <b>具名折算次序（V-2：禁两次折算、禁换估值实例）</b>——全程<b>只有一次</b>币种折算，且只经调用方交来的 <b>同一份</b> {@link
 * CurrencyValuation}：
 *
 * <ol>
 *   <li><b>税基本来就在买方支付币里</b>（{@code payment} 就是按 {@code buy.currency} 算出来的货款）⇒ <b>不折算</b>；
 *   <li><b>从量税率</b>（毫法定币 / 商品单位）折<b>一次</b>成"毫买方币 / 商品单位"： {@code r' = ⌊r × V(买方币 ← 法定币) ÷ 1000⌋}，其中
 *       {@code V = CurrencyValuation.valuationMicro(买方币, 法定币, 买方所在区)}（微买方币 / 毫法定币；同币 ⇒ 面值 1000 ⇒
 *       {@code r' = r} <b>逐值相同</b>）；
 *   <li><b>从价税率</b>是无量纲的千分比 ⇒ <b>不折算</b>（它作用在已经折好的税基上）；
 *   <li>税额算完就是<b>买方支付币</b>的金额 ⇒ 钱腿直接铸在它上面，<b>不再折回去</b>。
 * </ol>
 *
 * ★ <b>说不出价怎么办</b>（法定币在买方这一侧既无报价、也不在当地流通 ⇒ {@code valuationMicro == 0}）：本层 <b>不收</b>并具名记一条（{@link
 * Assessment#unvalued()}），绝不静默按 1:1 猜一个汇率出来（与 E 批"说不出价就不成交" 同源，但税不影响能不能过 ⇒ 这里不退化成拒绝成交）。
 *
 * <p>★★ <b>币种（I-C10）</b>：金额<b>只</b>以买方支付币表达（每条钱腿一个币种），读口按该币分列，<b>禁跨币求和</b>。
 *
 * <p>★★ <b>各层只算一次（I-C3）</b>：本类不做"哪一层该收"的判断 —— 层与税率的来源（源区/目的区/本区、商品、方向） 由 {@code MarketSettlement}
 * 逐层点名调一次；同一笔成交同一层<b>不可能</b>进两次（调用点只有一处）。
 *
 * <p>★ <b>纯函数 + 确定性（I7）</b>：不写状态、不用随机数、不读时钟；分摊顺序 = {@code governments} 的规范序。
 */
final class MarketTaxBook {

  /** 千分制：{@code 1000‰ = 1.0}（与 {@code PortEnforcementInput.PER_MILLE} 同值；本类不依赖它，就地声明）。 */
  static final long PER_MILLE = 1000L;

  private MarketTaxBook() {}

  /**
   * ★★ <b>一条税项</b>：层 + 收款政府 + 币种 + 金额（毫）+ 收税那一侧的区。
   *
   * <p>★ 金额恒 &gt; 0（0 额不进表：既不改余额，也不该在读数里出现一条"收了 0"）。
   */
  record Charge(
      MarketTaxLayer layer,
      String governmentId,
      ActorRef treasury,
      CurrencyId currency,
      long amountMilli,
      String zone) {

    Charge {
      Objects.requireNonNull(layer, "charge.layer");
      if (governmentId == null || governmentId.isBlank()) {
        throw new IllegalArgumentException("charge.governmentId 不得为空白");
      }
      Objects.requireNonNull(treasury, "charge.treasury");
      Objects.requireNonNull(currency, "charge.currency");
      if (amountMilli <= 0L) {
        throw new IllegalArgumentException("charge.amountMilli 必须 > 0（0 额不进表）: " + amountMilli);
      }
      if (zone == null || zone.isBlank()) {
        throw new IllegalArgumentException("charge.zone 不得为空白");
      }
    }
  }

  /**
   * ★★ <b>一层的计税结果</b>：{@code charges} 非空 = 真收（逐政府一条钱腿）；{@code unvalued} = 说不出法定币的价 ⇒ 本层收
   * 0（具名记录由调用方负责，两者不同时成立）。
   */
  record Assessment(List<Charge> charges, boolean unvalued) {

    static final Assessment NONE = new Assessment(List.of(), false);

    Assessment {
      charges = List.copyOf(charges);
    }

    /** 本层真收的总额（毫买方支付币）；0 = 本层没收。 */
    long totalMilli() {
      long total = 0L;
      for (Charge charge : charges) {
        total = Math.addExact(total, charge.amountMilli());
      }
      return total;
    }
  }

  /**
   * ★★ <b>算一层税并分摊到各政府国库</b>（唯一拼写点）。
   *
   * @param layer 层（只进读数与日志，不参与算式）
   * @param table 该区的税制（法定币 + 收税政府）；{@code null}/没有政府 ⇒ 收 0（"无政府 ⇒ 该侧税 = 0"）
   * @param valuation 本轮<b>唯一那份</b>钱的价（禁换实例）
   * @param paymentCurrency 买方支付币（钱的量纲）
   * @param paymentRegionId 买方所在区（"法定币在当地看不看得见"按它判）
   * @param buyer 买方 actor（国库自己买货时，那一家的那一份是自转移 ⇒ 剔除，且它不参与分摊）
   * @param quantityMilli 成交毛量（毫商品）
   * @param paymentMilli 货款（毫买方支付币；<b>税基 = 只含货值，不含运费</b>）
   * @param perUnitMilli 区级从量税率（毫法定币 / 商品单位）
   * @param adValoremPerMille 区级从价税率（货值千分比 ‰）
   * @param zoneId 收税那一侧的区（只进读数与日志）
   * @return 逐政府税项（规范序）+ 是否"说不出法定币的价"
   */
  static Assessment assess(
      MarketTaxLayer layer,
      PortTaxInput.ZoneTaxTable table,
      CurrencyValuation valuation,
      CurrencyId paymentCurrency,
      String paymentRegionId,
      ActorRef buyer,
      long quantityMilli,
      long paymentMilli,
      long perUnitMilli,
      long adValoremPerMille,
      String zoneId) {
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(valuation, "valuation");
    Objects.requireNonNull(paymentCurrency, "paymentCurrency");
    Objects.requireNonNull(buyer, "buyer");
    if (table == null || !table.taxable()) {
      return Assessment.NONE; // 无政府的一侧 ⇒ 该侧税 = 0（口岸设计书 §13.2-3）
    }
    if (perUnitMilli == 0L && adValoremPerMille == 0L) {
      return Assessment.NONE;
    }
    long perUnitInPayment = 0L;
    if (perUnitMilli > 0L) {
      long valueMicro =
          valuation.valuationMicro(paymentCurrency, table.legalTender(), paymentRegionId);
      if (valueMicro <= 0L) {
        return new Assessment(List.of(), true); // 说不出价 ⇒ 本层不收（不猜 1:1），具名记录由调用方发
      }
      perUnitInPayment = multiplyOrFail(perUnitMilli, valueMicro, "从量税率 × 钱的价") / MICRO_PER_MILLI;
    }
    long total = 0L;
    if (perUnitInPayment > 0L && quantityMilli > 0L) {
      total = multiplyOrFail(quantityMilli, perUnitInPayment, "毛量 × 从量税率") / PER_MILLE;
    }
    if (adValoremPerMille > 0L && paymentMilli > 0L) {
      total =
          Math.addExact(
              total, multiplyOrFail(paymentMilli, adValoremPerMille, "货款 × 从价税率") / PER_MILLE);
    }
    if (total <= 0L) {
      return Assessment.NONE;
    }
    // ── 分摊：只按**能收钱**的政府（国库户），规范序；floor + 余数给最后一家 ⇒ Σ 逐值守恒 ─────────────
    List<PortTaxInput.GovernmentShare> payable = new ArrayList<>(table.governments().size());
    long weightSum = 0L;
    for (PortTaxInput.GovernmentShare share : table.governments()) {
      if (share.treasury().equals(buyer)) {
        continue; // ★ 自行转移不是一笔发生额（Transfer 两端不得相等）：国库自己买货时那一份不参与分摊
      }
      payable.add(share);
      weightSum = Math.addExact(weightSum, share.exposureWeight());
    }
    if (payable.isEmpty() || weightSum <= 0L) {
      return Assessment.NONE;
    }
    List<Charge> charges = new ArrayList<>(payable.size());
    long assigned = 0L;
    for (int i = 0; i < payable.size(); i++) {
      PortTaxInput.GovernmentShare share = payable.get(i);
      long amount =
          i == payable.size() - 1
              ? total - assigned // ★ 余数（含 floor 掉的零头）给规范序最后一家 ⇒ Σ == total
              : multiplyOrFail(total, share.exposureWeight(), "总额 × 权重") / weightSum;
      assigned = Math.addExact(assigned, amount);
      if (amount > 0L) {
        charges.add(
            new Charge(
                layer, share.governmentId(), share.treasury(), paymentCurrency, amount, zoneId));
      }
    }
    return new Assessment(charges, false);
  }

  /** 微 / 毫（与 {@code HouseholdValuationBook.MICRO_PER_MILLI} 同值；避免为一次除法引入依赖）。 */
  private static final long MICRO_PER_MILLI = 1000L;

  /** 整数乘法：溢出 ⇒ 具名失败（与"钱的价一律不静默回退到 double"同口径）。 */
  private static long multiplyOrFail(long left, long right, String what) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "税的算式整数溢出（拒绝回退到 double）: " + what + " left=" + left + " right=" + right, overflow);
    }
  }
}
