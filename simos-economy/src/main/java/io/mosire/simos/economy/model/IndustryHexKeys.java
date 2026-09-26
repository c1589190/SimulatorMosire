package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.IndustryId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ **"产业属于哪一格"的唯一拼写点**（聚合式经济重设计 §十）：R2a 起每一格各得一份 {@code economy} 状态，农业恒有、城市格加手工业 —— 于是 {@code
 * IndustryId} 必须携带格的坐标，而{@link Industry} 自身**没有**（也不该有）"我在哪一格"的字段（§3.1 的形状不变）。
 *
 * <p>★ **为什么单列一个类而不是就地拼字符串**：这份约定被**三个地方**同时读——{@code EconomySeeder}（生成 id）、 {@code
 * ApiViews.economyHex}（按格取该格的产业）、以及用例。三处各拼一次 "kind + '@' + q + '_' + r" 不会报错、只会漂移
 * （本仓「同一个东西两处各写一份」那一族）。故约定只在这里，别处一律经本类。
 *
 * <p>★ **格式 {@code <kind>@<q>_<r>}**（如 {@code farm@0_0}、{@code craft@-3_2}）：用 {@code '@'} 而**不用
 * {@code '.'}**， 因为 {@code AddressParser} 把 {@code Entity(kind, name)} 的名字在**第一个 {@code '.'}** 处切开
 * （H0 起 class/flow 的局部名 = {@code CohortKey} 的规范串，见 {@code EconomyResolver}）——产业 id 里再带 {@code '.'}
 * 会让那个拆分读歪。
 *
 * <p>★ **本类无公式、无状态**：只有拼/拆/筛选三个纯函数。
 */
public final class IndustryHexKeys {

  /** kind 与坐标之间的分隔符（见类注：不能用 {@code '.'}）。 */
  private static final char SEPARATOR = '@';

  /** 坐标两段之间的分隔符（与 {@code ResourcePaths#social} 的 {@code _} 同源）。 */
  private static final String COORD_SEPARATOR = "_";

  private IndustryHexKeys() {}

  /** 格的键：{@code <q>_<r>}（与 {@code ResourcePaths.economy(q, r)} 逐字同形）。 */
  public static String hexKey(int q, int r) {
    return q + COORD_SEPARATOR + r;
  }

  /**
   * 某格某产业的稳定 id：{@code <kind>@<q>_<r>}。
   *
   * @param kind 产业种类标签（如 {@code "farm"} / {@code "craft"}）；非空白
   */
  public static IndustryId id(String kind, int q, int r) {
    if (kind == null || kind.isBlank()) {
      throw new IllegalArgumentException("IndustryHexKeys.id 的 kind 不得为空白");
    }
    return new IndustryId(kind + SEPARATOR + hexKey(q, r));
  }

  /** 产业 id 里的格键（{@code <q>_<r>}）；id 里没有 {@code '@'} 或其后为空 ⇒ {@link Optional#empty()}（不猜）。 */
  public static Optional<String> hexKeyOf(IndustryId id) {
    if (id == null) {
      return Optional.empty();
    }
    int at = id.value().lastIndexOf(SEPARATOR);
    if (at <= 0 || at == id.value().length() - 1) {
      return Optional.empty();
    }
    return Optional.of(id.value().substring(at + 1));
  }

  /**
   * 该格上的全部产业 id，**按 id 字典序**（读口的"逐值可复现"由此承担：{@code industries} 的插入序不是内容的纯函数）。
   *
   * <p>只认 {@link #hexKeyOf} 与该格键**逐字相等**的 id——{@code 1_10} 与 {@code 1_1} 因此不会互相误命中。
   */
  public static List<IndustryId> at(Map<IndustryId, Industry> industries, int q, int r) {
    String target = hexKey(q, r);
    List<IndustryId> ids = new ArrayList<>();
    for (IndustryId id : industries.keySet()) {
      if (hexKeyOf(id).filter(target::equals).isPresent()) {
        ids.add(id);
      }
    }
    ids.sort(Comparator.comparing(IndustryId::value));
    return List.copyOf(ids);
  }
}
