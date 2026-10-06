package io.mosire.simos.social.api.household;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 家户画像（2026-10-09 家户/人口架构 §3.1）：名字 + 描述 + 自由元数据。
 *
 * <p>不变量（构造期判、坏数据当场抛）：
 *
 * <ul>
 *   <li>{@code name} 非空白；
 *   <li>{@code description} 可空（缺省即 {@code null}）；
 *   <li>{@code metadata} <b>冻结不可变</b>：入参为 {@code null} 视为空表，否则做防御性拷贝后包 {@code
 *       unmodifiableMap}；键值都不得为 null。保序用 {@link LinkedHashMap}（{@code Map.copyOf} 的迭代序不是内容的纯函数，与
 *       map/economy 侧的既有口径一致）。
 * </ul>
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param name 非空白显示名
 * @param description 描述；可空
 * @param metadata 冻结的字符串元数据；不得含 null 键/值
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "compact constructor 已做防御性拷贝并冻结；SpotBugs 不跨辅助方法识别")
public record HouseholdProfile(String name, String description, Map<String, String> metadata) {

  public HouseholdProfile {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("HouseholdProfile.name 不得为空白");
    }
    metadata = freezeMetadata(metadata);
  }

  /** 防御性拷贝 + 逐项 null 校验 + 保序冻结（{@code null} ⇒ 空表）。 */
  private static Map<String, String> freezeMetadata(Map<String, String> source) {
    if (source == null) {
      return Map.of();
    }
    Map<String, String> copy = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("HouseholdProfile.metadata 不得含 null 键/值: " + entry);
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}
