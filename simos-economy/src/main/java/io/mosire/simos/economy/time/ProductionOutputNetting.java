package io.mosire.simos.economy.time;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>毛产 → 可分配净产的唯一拼写点</b>（D-032 D2 §8.3 / §6.3）。
 *
 * <p>★★ <b>为什么必须单列</b>：周期关账的净池计算在 C2 有两个读者 —— 现有 {@code EconomySettlement.harvest} 与 D2 §8.1
 * 的预计所得（{@code ExpectedProfitBook}）；两处各写一遍 {@code gross × (FEED + DEPRECIATION) / 1000}
 * 就会出现"预计一套、实收一套"的漂移（本仓最贵的那类 bug）。
 *
 * <p>★ <b>口径（逐商品、整数、下取整）</b>：
 *
 * <pre>
 * loss = floor(gross × (FEED_PER_MILLE + DEPRECIATION_PER_MILLE) / 1000)
 * net  = gross − loss
 * </pre>
 *
 * <p>★ 损耗仍由关账方记入 ledger 的 ΣLoss；角色分配<b>只</b>使用 {@link Netted#net()}，不得再按毛产分一次、也不得先 credit 给
 * operator 再分第二次（D2-I6/I7）。
 *
 * <p>★ <b>纯函数</b>：无状态、无时钟、无随机；同输入同输出。
 */
public final class ProductionOutputNetting {

  /** 收获时的饲料消耗（千分数）：0‰（v1 不做耕牛；取值来源见 {@code EconomySettlement} 的同名常量）。 */
  public static final int FEED_PER_MILLE = 0;

  /** 收获时的农具折旧（千分数）：30‰（3%）。 */
  public static final int DEPRECIATION_PER_MILLE = 30;

  private ProductionOutputNetting() {}

  /** 一个商品的毛产/损耗/净产三件（毫单位；三者满足 {@code gross = loss + net}）。 */
  public record Netted(long gross, long loss, long net) {

    public Netted {
      if (gross < 0L || loss < 0L || net < 0L) {
        throw new IllegalArgumentException(
            "ProductionOutputNetting 不得为负: " + gross + "/" + loss + "/" + net);
      }
      if (Math.addExact(loss, net) != gross) {
        throw new IllegalArgumentException(
            "ProductionOutputNetting 必须满足 gross = loss + net: "
                + gross
                + " != "
                + loss
                + " + "
                + net);
      }
    }
  }

  /** 单个商品的毛产 → 净产。 */
  public static Netted net(long gross) {
    if (gross < 0L) {
      throw new IllegalArgumentException("ProductionOutputNetting.net 的 gross 不得为负: " + gross);
    }
    long loss = gross * (FEED_PER_MILLE + DEPRECIATION_PER_MILLE) / 1000L;
    return new Netted(gross, loss, gross - loss);
  }

  /** 逐商品毛产 → 净产（保序；键/值非 null、值非负）。 */
  public static Map<io.mosire.simos.economy.api.id.CommodityId, Netted> netAll(
      Map<io.mosire.simos.economy.api.id.CommodityId, Long> grossByCommodity) {
    Objects.requireNonNull(grossByCommodity, "grossByCommodity");
    Map<io.mosire.simos.economy.api.id.CommodityId, Netted> netted = new LinkedHashMap<>();
    for (Map.Entry<io.mosire.simos.economy.api.id.CommodityId, Long> entry :
        grossByCommodity.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionOutputNetting.netAll 的键与值都不得为 null");
      }
      netted.put(entry.getKey(), net(entry.getValue()));
    }
    return netted;
  }
}
