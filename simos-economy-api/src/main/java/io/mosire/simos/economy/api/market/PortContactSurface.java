package io.mosire.simos.economy.api.market;

import java.util.Objects;

/**
 * ★★ <b>一个"接触面"的管制读数（2026-10-09 口岸设计书 §4.3；纯值契约，无公式）</b>。
 *
 * <p>★★ <b>接触面是什么</b>：市场区 Z 与相邻市场区之间的<b>一段边界</b>，按"这段边界归哪个政府管"分组 —— 用户 2026-10-09
 * 原话「给每个政府控制的口岸算成一个集合，控制的政府可以改变控制的口岸的口岸政策」（设计书 §1.3-2）。 因此一个接触面 = <b>一个政府在 Z 一侧控制的那批暴露边</b>；{@link
 * #exposedEdgeCount} 就是设计书 §4.3 里的权重 {@code w_k}。
 *
 * <p>★★ <b>为什么 key 是字符串而不是政府类型</b>：本类型住在 {@code simos-economy-api}（契约层），它<b>不许</b>认识 {@code
 * simos-gov}（切片互不依赖）。{@code contactKey} = 组合根给出的稳定标识（本批 = 该政府的 {@code UnitId} 裸值； 三不管的暴露边归一个具名
 * key，见 {@code PortRegimeBridge}）。
 *
 * <p>★ <b>量纲</b>：{@link #restrictionPerMille} = 限制强度 {@code s}（‰；设计书 §4.2 的"管制标准"）； {@link
 * #portEfficiencyPerMille} = 该政府的口岸效率 {@code e}（‰；{@code GovEfficiency} 的第三维）。 两者的合成（{@code
 * enforcement = s×e÷1000}）是<b>公式</b>，不在本契约里。
 *
 * <p>★ <b>不封顶</b>：两个 ‰ 值都只判 {@code ≥ 0}（用户 2026-10-23「都不封顶」）。
 *
 * @param contactKey 接触面的稳定标识（非空白；本批 = 管辖该段边界的政府身份裸值 / 三不管的具名 key）
 * @param exposedEdgeCount 该接触面的暴露边条数（{@code w_k}；≥ 0；0 表示这个接触面不占边界）
 * @param restrictionPerMille 该政府对本类的限制强度 {@code s}（‰；≥ 0，不封顶；0 = 不限制）
 * @param portEfficiencyPerMille 该政府的口岸效率 {@code e}（‰；≥ 0，不封顶；0 = 没有口岸编制 = 管不住）
 */
public record PortContactSurface(
    String contactKey,
    long exposedEdgeCount,
    long restrictionPerMille,
    long portEfficiencyPerMille) {

  public PortContactSurface {
    if (contactKey == null || contactKey.isBlank()) {
      throw new IllegalArgumentException("PortContactSurface.contactKey 不得为空白");
    }
    requireNonNegative(exposedEdgeCount, "exposedEdgeCount");
    requireNonNegative(restrictionPerMille, "restrictionPerMille");
    requireNonNegative(portEfficiencyPerMille, "portEfficiencyPerMille");
  }

  /** 该接触面是否"不设限"（{@code s = 0}）—— 设计书 §4.3 的 OR 规则唯一读的谓词。 */
  public boolean unrestricted() {
    return restrictionPerMille == 0L;
  }

  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException("PortContactSurface." + field + " 必须 ≥ 0: " + value);
    }
  }

  /** 该接触面 owner 身份的规范拼写点（拒绝/日志共用；避免各处手拼）。 */
  @Override
  public String toString() {
    return contactKey
        + "(w="
        + exposedEdgeCount
        + ",s="
        + restrictionPerMille
        + ",e="
        + portEfficiencyPerMille
        + ")";
  }

  /** 空 key 判据的显式入口（供调用方做"三不管"具名分组的唯一拼写点）。 */
  public static String requireKey(String value) {
    return Objects.requireNonNull(value, "contactKey");
  }
}
