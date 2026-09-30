package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * ★★ <b>租金模板</b>（理想架构 §2.5 的 {@code RentRule}；E2）：一条 {@link AssetRule} 说清"这种生产资料租来时怎么收租"。
 *
 * <p>★★ <b>四类租金与"混合"的表达</b>（判据原文："固定实物租、固定货币租、分成、混合租都能表达"）：
 *
 * <ul>
 *   <li>{@link RentType#FIXED_IN_KIND} —— 一条 {@link RentLeg}：每周期 {@code fixedAmount} 毫商品（粮/布/铁…）；
 *   <li>{@link RentType#FIXED_MONEY} —— 一条 {@link RentLeg}：每周期 {@code fixedAmount} 毫计价货币；
 *   <li>{@link RentType#SHARE} —— 一条 {@link RentLeg}：毛产的 {@code ratePerMille}‰（实物分成）；
 *   <li>{@link RentType#MIXED} —— <b>两条及以上</b> leg 的任意组合（例如"固定实物租 + 货币附加"、"分成 + 固定货币"）。
 * </ul>
 *
 * <p>★★ <b>为什么"混合"必须是多条 leg 而不是几个扁平字段</b>：{@code CompensationRule} 的构造期守卫判死"货币 ⟺ currency 非空、实物 ⟺
 * commodity 非空"（{@code commodity}/{@code currency} 互为反相）⇒ 一条规则表达不了 "又交粮又交钱"。把每条腿做成独立的 {@link
 * RentLeg}（各自带 commodity/currency 恰其一）之后，混合租由 <b>leg 列表</b> 表达，落到结算侧就是多条独立的 {@code
 * FIXED_IN_KIND_RENT}/{@code FIXED_MONEY_RENT}/{@code OUTPUT_SHARE} 规则 —— 结算路径一行不用改（{@code
 * ProductionSettlement} 已认识这些档）。
 *
 * <p>★★ <b>fail-closed 的构造期判据</b>（每一条都对应一种"读起来像、其实说不清"的坏数据）：
 *
 * <ol>
 *   <li>{@code type}/{@code legs} 非 null；{@code priority ≥ 0}；
 *   <li>{@code legs} 非空、逐项非 null、保序不可变；
 *   <li><b>非 MIXED</b> ⇒ 恰一条 leg、且 leg 的 kind 与 {@code type} 逐值相同；
 *   <li><b>MIXED</b> ⇒ 至少两条 leg；leg 自己的 kind 不得为 MIXED（混合不许嵌套）；
 *   <li>每条 leg 内部的二选一/数值边界见 {@link RentLeg}。
 * </ol>
 *
 * <p>★ <b>本类型只存模板、不含公式</b>：怎么把 leg 变成 {@code CompensationRule}、以及"欠租"怎么读，都在 E2 的 生产组织阶段（{@code
 * EconomyOrganizationSettlement}）与既有结算侧。★ 不可变（legs 冻结在赋值处，绝不用 {@code Map.copyOf}）。
 *
 * @param type 租金类型（四档词表；MIXED = 由 legs 表达的组合）
 * @param priority 付款次序（≥ 0；允许重复，同值按 legs 表序稳定 —— 与结算的 priority 口径同源）
 * @param legs 租金腿（非空、保序、不可变；非 MIXED 恰一条，MIXED 至少两条）
 */
public record RentRule(RentType type, int priority, List<RentLeg> legs) {

  /** ★ 租金类型四档词表（与设计稿 §2.5 的"固定实物/固定货币/分成/组合"逐档对应）。 */
  public enum RentType {
    /** 固定实物租（每周期一笔实物）。 */
    FIXED_IN_KIND,
    /** 固定货币租（每周期一笔货币）。 */
    FIXED_MONEY,
    /** 实物分成（毛产的千分比）。 */
    SHARE,
    /** 混合租：由 {@link RentRule#legs()} 的二条及以上腿组合表达。 */
    MIXED;

    /** 按词表解析：词表外的输入即抛并列出全部合法值（fail-closed，不归一）。 */
    public static RentType parse(String text) {
      if (text == null || text.isBlank()) {
        throw new IllegalArgumentException("RentType 不得为空白: " + text);
      }
      try {
        return valueOf(text);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "未登记的租金类型: " + text + "；合法值: " + Arrays.toString(values()));
      }
    }
  }

  public RentRule {
    if (type == null) {
      throw new IllegalArgumentException("RentRule.type 不得为 null");
    }
    if (legs == null) {
      throw new IllegalArgumentException("RentRule.legs 不得为 null（没有租金就不该有 RentRule）");
    }
    if (priority < 0) {
      throw new IllegalArgumentException("RentRule.priority 不得为负: " + priority);
    }
    List<RentLeg> copy = new ArrayList<>();
    for (RentLeg leg : legs) {
      if (leg == null) {
        throw new IllegalArgumentException("RentRule.legs 不得含 null");
      }
      if (leg.kind() == RentType.MIXED) {
        throw new IllegalArgumentException("RentRule.legs 的腿不得是 MIXED（混合不许嵌套）: " + leg);
      }
      copy.add(leg);
    }
    if (copy.isEmpty()) {
      throw new IllegalArgumentException("RentRule.legs 不得为空（没有腿的租金不是一种租金）");
    }
    if (type == RentType.MIXED) {
      if (copy.size() < 2) {
        throw new IllegalArgumentException(
            "RentType.MIXED 必须由至少两条 leg 表达（只有一条腿时请直接写该腿的类型）: " + copy);
      }
    } else {
      if (copy.size() != 1 || copy.get(0).kind() != type) {
        throw new IllegalArgumentException(
            "非 MIXED 的 RentRule 必须恰有一条同类型的 leg：type=" + type + "，legs=" + copy);
      }
    }
    legs = Collections.unmodifiableList(copy); // ★ 冻在赋值处
  }

  /**
   * ★★ <b>一条租金腿</b>：四档里"具体的一条"（固定实物 / 固定货币 / 实物分成），各自带足自己的量纲与受方之外的字段。
   *
   * <p>★★ <b>二选一（量纲）</b>：固定实物与分成带 {@code commodity}（空 {@code currency}）；固定货币带 {@code currency}（空
   * {@code commodity}）—— 与 {@code CompensationRule} 的同一条守卫同向，混合租因此天然可表达。
   *
   * <p>★ <b>三类各自的数值口径</b>：固定档读 {@code fixedAmount}（&gt; 0）且 {@code ratePerMille == 0}； 分成档读 {@code
   * ratePerMille}（∈ [1, 1000]）且 {@code fixedAmount == 0}。两个字段并存是刻意的（同 {@code
   * CompensationRule}：一条腿两个旋钮、由 kind 决定读哪个），不是冗余。
   *
   * @param kind 这条腿的类型（与 {@link RentRule#type()} 同词表，但不得为 MIXED）
   * @param ratePerMille 分成率（千分数，∈ [0, 1000]；固定档恒 0，分成档 ∈ [1, 1000]）
   * @param fixedAmount 固定额（毫单位，&gt; 0；分成档恒 0）
   * @param commodity 实物商品（固定实物/分成必须给；固定货币恒空）
   * @param currency 货币种类（固定货币必须给；实物档恒空）
   */
  public record RentLeg(
      RentType kind,
      int ratePerMille,
      long fixedAmount,
      Optional<CommodityId> commodity,
      Optional<CurrencyId> currency) {

    public RentLeg {
      if (kind == null) {
        throw new IllegalArgumentException("RentLeg.kind 不得为 null");
      }
      if (kind == RentType.MIXED) {
        throw new IllegalArgumentException("RentLeg.kind 不得为 MIXED（混合是 RentRule 的层，不是腿的层）");
      }
      if (commodity == null) {
        throw new IllegalArgumentException("RentLeg.commodity 不得为 null（空要用 Optional.empty()）");
      }
      if (currency == null) {
        throw new IllegalArgumentException("RentLeg.currency 不得为 null（空要用 Optional.empty()）");
      }
      if (ratePerMille < 0 || ratePerMille > 1000) {
        throw new IllegalArgumentException(
            "RentLeg.ratePerMille 越界（应在 [0, 1000]）: " + ratePerMille);
      }
      if (fixedAmount < 0L) {
        throw new IllegalArgumentException("RentLeg.fixedAmount 不得为负: " + fixedAmount);
      }
      switch (kind) {
        case FIXED_IN_KIND -> {
          if (fixedAmount <= 0L) {
            throw new IllegalArgumentException("固定实物租的 fixedAmount 必须 > 0: " + fixedAmount);
          }
          if (ratePerMille != 0) {
            throw new IllegalArgumentException("固定实物租不读分成率（ratePerMille 必须为 0）: " + ratePerMille);
          }
          if (commodity.isEmpty() || currency.isPresent()) {
            throw new IllegalArgumentException(
                "固定实物租必须恰带商品（commodity 非空、currency 空）: commodity="
                    + commodity
                    + " currency="
                    + currency);
          }
        }
        case FIXED_MONEY -> {
          if (fixedAmount <= 0L) {
            throw new IllegalArgumentException("固定货币租的 fixedAmount 必须 > 0: " + fixedAmount);
          }
          if (ratePerMille != 0) {
            throw new IllegalArgumentException("固定货币租不读分成率（ratePerMille 必须为 0）: " + ratePerMille);
          }
          if (currency.isEmpty() || commodity.isPresent()) {
            throw new IllegalArgumentException(
                "固定货币租必须恰带币种（currency 非空、commodity 空）: commodity="
                    + commodity
                    + " currency="
                    + currency);
          }
        }
        case SHARE -> {
          // ★ R4a：上界 1000 已由构造器首段守卫判过；这里只补下界，避免 SpotBugs UC_USELESS_CONDITION。
          if (ratePerMille < 1) {
            throw new IllegalArgumentException("实物分成的 ratePerMille 必须在 [1, 1000]: " + ratePerMille);
          }
          if (fixedAmount != 0L) {
            throw new IllegalArgumentException("实物分成不读固定额（fixedAmount 必须为 0）: " + fixedAmount);
          }
          if (commodity.isEmpty() || currency.isPresent()) {
            throw new IllegalArgumentException(
                "实物分成必须恰带商品（commodity 非空、currency 空）: commodity="
                    + commodity
                    + " currency="
                    + currency);
          }
        }
        case MIXED -> throw new IllegalArgumentException("RentLeg.kind 不得为 MIXED（见上一条守卫）");
      }
    }

    /** 固定实物租腿的便利构造（ratePerMille = 0）。 */
    public static RentLeg fixedInKind(long fixedAmount, CommodityId commodity) {
      return new RentLeg(
          RentType.FIXED_IN_KIND, 0, fixedAmount, Optional.of(commodity), Optional.empty());
    }

    /** 固定货币租腿的便利构造（ratePerMille = 0）。 */
    public static RentLeg fixedMoney(long fixedAmount, CurrencyId currency) {
      return new RentLeg(
          RentType.FIXED_MONEY, 0, fixedAmount, Optional.empty(), Optional.of(currency));
    }

    /** 实物分成腿的便利构造（fixedAmount = 0）。 */
    public static RentLeg share(int ratePerMille, CommodityId commodity) {
      return new RentLeg(
          RentType.SHARE, ratePerMille, 0L, Optional.of(commodity), Optional.empty());
    }
  }

  /** 单腿租金的便利构造（type 取该腿的 kind；与四参 canonical 构造器并列，便于调用点少写一次 type）。 */
  public static RentRule of(RentLeg leg, int priority) {
    if (leg == null) {
      throw new IllegalArgumentException("RentRule.of 的 leg 不得为 null");
    }
    return new RentRule(leg.kind(), priority, List.of(leg));
  }

  /**
   * 混合租的便利构造（type = MIXED，至少两条腿）。
   *
   * <p>★ 只负责形状校验；"混合里两条腿是不是同一商品/同一货币"属调用方的制度判断，本层不猜（E2 的模板由 GM 给）。
   */
  public static RentRule mixed(int priority, List<RentLeg> legs) {
    if (legs == null || legs.size() < 2) {
      throw new IllegalArgumentException("RentRule.mixed 至少需要两条腿: " + legs);
    }
    return new RentRule(RentType.MIXED, priority, legs);
  }
}
