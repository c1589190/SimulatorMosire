package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.api.id.IndustryId;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★★ <b>产业 id 的 kind/版本拆解助手</b>（原 {@code economy.UpsertIndustry} 语义落点；D-031 P1 起定义写入已迁入 {@code
 * production.UpsertIndustry}）。
 *
 * <p>★ P1 只保留 GOV 侧仍在用的 <b>纯 id 解析</b>：{@link #baseKindOf(IndustryId)} 判"该产业是不是某个族"（如 {@code
 * office} / {@code office_v2}…）。定义写入、引用守卫、幂等/版本拒绝语义全部搬到 production。
 *
 * <p>★ 本类不再持有任何写口，也不碰 {@code EconomyData}。
 */
public final class EconomyIndustryUpserts {

  /** kind 末尾的版本后缀：{@code office_v2} ⇒ base=office、version=2；没有后缀 ⇒ version=1。 */
  private static final Pattern VERSION_SUFFIX = Pattern.compile("^(.*)_v([0-9]+)$");

  private EconomyIndustryUpserts() {}

  /**
   * 既有 {@code IndustryId} 的 <b>base kind</b>（版本后缀 {@code _v<N>} 剥掉；无后缀 = 整个 kind）。
   *
   * <p>★ 读的是<b>既有状态里的 id</b>，故用宽容拆解（旧档怪拼法按普通 kind 文本读，不把引用检查变成载荷错）。
   */
  public static String baseKindOf(IndustryId id) {
    Objects.requireNonNull(id, "id");
    return kindVersion(id, false).baseKind();
  }

  /**
   * kind 的 base/版本拆解：{@code office} ⇒ (office,1)、{@code office_v2} ⇒ (office,2)。
   *
   * <p>★ {@code strict=false}（既有状态里的 id）：非规范 {@code _v...} 后缀读作<b>普通 kind 文本</b>（version=1）。
   */
  private static KindVersion kindVersion(IndustryId id, boolean strict) {
    String value = id.value();
    int at = value.lastIndexOf('@');
    String kind = at < 0 ? value : value.substring(0, at);
    Matcher matcher = VERSION_SUFFIX.matcher(kind);
    if (!matcher.matches()) {
      return new KindVersion(kind, 1);
    }
    String baseKind = matcher.group(1);
    String digits = matcher.group(2);
    if (baseKind.isBlank()) {
      if (strict) {
        throw new IllegalArgumentException("产业 id 的版本后缀前缺少 kind: " + value);
      }
      return new KindVersion(kind, 1);
    }
    if (digits.length() > 1 && digits.charAt(0) == '0') {
      if (strict) {
        throw new IllegalArgumentException("产业版本号不得有前导 0: " + value);
      }
      return new KindVersion(kind, 1);
    }
    long parsed;
    try {
      parsed = Long.parseLong(digits);
    } catch (NumberFormatException e) {
      if (strict) {
        throw new IllegalArgumentException("产业版本号超出范围: " + value, e);
      }
      return new KindVersion(kind, 1);
    }
    if (parsed < 1L || parsed > Integer.MAX_VALUE) {
      if (strict) {
        throw new IllegalArgumentException("产业版本号必须 ∈ [1, " + Integer.MAX_VALUE + "]: " + value);
      }
      return new KindVersion(kind, 1);
    }
    return new KindVersion(baseKind, (int) parsed);
  }

  /** kind 的 base 与版本。 */
  private record KindVersion(String baseKind, int version) {}
}
