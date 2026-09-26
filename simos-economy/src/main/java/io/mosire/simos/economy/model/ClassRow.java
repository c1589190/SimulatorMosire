package io.mosire.simos.economy.model;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶层行（新经济设计 §3.2 逐字）：表 1（人口/劳动/参与率）+ 表 2（生产资料/库存/货币），外加债务引用与两类需求。
 *
 * <p>★ **它是存量**（§3.3 末条"存量/流量分离"）：本期的发生额在 {@link FlowRow} 里、**结算后清零**；绝不用"生产成本"或"资产减少"
 * 冒充负债——债务只能由借入/赊购产生，引用 {@link #debts} 指向债务表。
 *
 * <p>★ **量纲**（§7）：{@code population} 人；{@code laborMilli} 千分劳动；{@code meansOfProduction}
 * 土地按千分亩、其余按件； {@code goods}/{@code naturalNeeds}/{@code effectiveDemand} 按最小计量单位；{@code money}
 * 最小币值； {@code participationPerMille} 千分数。
 *
 * <p>★ **不变量（构造期判，§6.4）**：{@code population ≥ 0}、{@code laborMilli ≥ 0}、{@code money ≥ 0}、四个表的逐值
 * {@code ≥ 0}、{@code participationPerMille ∈ [0, 1000]}（§6.3 的上界部分；"≤ 槽位上限"要跨对象，见 §6.3）。
 *
 * <p>★ **四张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——迭代序不是内容的纯函数。冻结那一步**写在字段赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP} 不做跨过程分析，只认它看得见的包装）⇒
 * 四段构造**逐字展开、不抽 helper**（照 {@code Account}/{@code Industry} 的先例）。
 *
 * @param key 身份（产业 + 槽位）；在 {@code EconomyData.classes} 里必须与其 Map 键一致
 * @param population 人口（人）；不得为负
 * @param laborMilli 有效劳动（千分劳动）——由 social 的人数 × 年龄系数而来；不得为负
 * @param participationPerMille 本期实际劳动投入率（≤ 槽位上限）；必须 ∈ [0, 1000]
 * @param meansOfProduction 生产资料（土地千分亩 / 耕牛 / 工坊 / 机器 / 船只）；键值非空、逐值 ≥ 0
 * @param goods 商品库存（最小计量单位）；键值非空、逐值 ≥ 0
 * @param money 货币（最小币值）；不得为负
 * @param debts 指向债务表的引用；可空、不得含 null
 * @param naturalNeeds 本期自然需求（生存/再生产；v1 只做前两档）；键值非空、逐值 ≥ 0
 * @param effectiveDemand 有效需求（= 有支付力的那部分，§十四/§十五 的分野）；键值非空、逐值 ≥ 0
 */
public record ClassRow(
    ClassKey key,
    long population,
    long laborMilli,
    int participationPerMille,
    Map<AssetKind, Long> meansOfProduction,
    Map<CommodityId, Long> goods,
    long money,
    List<DebtId> debts,
    Map<CommodityId, Long> naturalNeeds,
    Map<CommodityId, Long> effectiveDemand) {

  public ClassRow {
    if (key == null) {
      throw new IllegalArgumentException("ClassRow.key 不得为 null");
    }
    if (population < 0) {
      throw new IllegalArgumentException("ClassRow.population 不得为负: " + population);
    }
    if (laborMilli < 0) {
      throw new IllegalArgumentException("ClassRow.laborMilli 不得为负: " + laborMilli);
    }
    if (participationPerMille < 0 || participationPerMille > 1000) {
      throw new IllegalArgumentException(
          "ClassRow.participationPerMille 必须 ∈ [0, 1000]: " + participationPerMille);
    }
    if (money < 0) {
      throw new IllegalArgumentException("ClassRow.money 不得为负: " + money);
    }
    if (meansOfProduction == null) {
      throw new IllegalArgumentException("ClassRow.meansOfProduction 不得为 null（无资产用空 map）");
    }
    if (goods == null) {
      throw new IllegalArgumentException("ClassRow.goods 不得为 null（无库存用空 map）");
    }
    if (naturalNeeds == null) {
      throw new IllegalArgumentException("ClassRow.naturalNeeds 不得为 null（无需求用空 map）");
    }
    if (effectiveDemand == null) {
      throw new IllegalArgumentException("ClassRow.effectiveDemand 不得为 null（无需求用空 map）");
    }
    if (debts == null) {
      throw new IllegalArgumentException("ClassRow.debts 不得为 null（无债务用空 list）");
    }
    Map<AssetKind, Long> meansCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : meansOfProduction.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ClassRow.meansOfProduction 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "ClassRow.meansOfProduction 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      meansCopy.put(entry.getKey(), entry.getValue());
    }
    meansOfProduction = Collections.unmodifiableMap(meansCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> goodsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ClassRow.goods 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "ClassRow.goods 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      goodsCopy.put(entry.getKey(), entry.getValue());
    }
    goods = Collections.unmodifiableMap(goodsCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> needsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : naturalNeeds.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ClassRow.naturalNeeds 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "ClassRow.naturalNeeds 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      needsCopy.put(entry.getKey(), entry.getValue());
    }
    naturalNeeds = Collections.unmodifiableMap(needsCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> demandCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : effectiveDemand.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ClassRow.effectiveDemand 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "ClassRow.effectiveDemand 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      demandCopy.put(entry.getKey(), entry.getValue());
    }
    effectiveDemand = Collections.unmodifiableMap(demandCopy); // ★ 冻在赋值处
    List<DebtId> debtsCopy = new ArrayList<>();
    for (DebtId debt : debts) {
      if (debt == null) {
        throw new IllegalArgumentException("ClassRow.debts 不得含 null");
      }
      debtsCopy.add(debt);
    }
    debts = Collections.unmodifiableList(debtsCopy); // ★ 冻在赋值处
  }
}
