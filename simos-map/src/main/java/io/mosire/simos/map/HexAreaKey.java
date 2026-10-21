package io.mosire.simos.map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 旧标量读口 / 旧命令 {@code map.SetHexArea} 的字段键。
 *
 * <p>Alpha 起它只是 {@link HexLand} 组件总量的 scalar view key，不再是持久面积表的键：{@link Builtin#URBAN} 对应 {@link
 * LandTypeId#URBAN}，{@link Builtin#ARABLE} 对应 {@link LandTypeId#ARABLE}。旧 {@code rural} wire 不保留
 * （Alpha 不兼容旧 flat areas 档）。
 */
public sealed interface HexAreaKey {

  /** 自定义字段线格式前缀（只用于旧命令的自定义键；不是土地类型持久形状）。 */
  String CUSTOM_PREFIX = "custom:";

  /** 线格式：落盘/载荷/读面字段名。 */
  @JsonValue
  String wireKey();

  /** 内置标量键。 */
  enum Builtin implements HexAreaKey {
    URBAN("urban"),
    ARABLE("arable");

    private final String label;

    Builtin(String label) {
      this.label = label;
    }

    /** 内置标签（= 线格式）。 */
    public String label() {
      return label;
    }

    @Override
    public String wireKey() {
      return label;
    }

    /** 该标量键对应的土地类型。 */
    public LandTypeId landType() {
      return switch (this) {
        case URBAN -> LandTypeId.URBAN;
        case ARABLE -> LandTypeId.ARABLE;
      };
    }

    /** 标签 → 内置键；词表外一律抛。 */
    public static Builtin parse(String text) {
      for (Builtin value : values()) {
        if (value.label.equals(text)) {
          return value;
        }
      }
      StringBuilder labels = new StringBuilder();
      for (Builtin value : values()) {
        if (labels.length() > 0) {
          labels.append(" | ");
        }
        labels.append(value.label);
      }
      throw new IllegalArgumentException("未知内置字段标签: " + text + "（合法标签: " + labels + "）");
    }
  }

  /** 自定义标量键（开放 String；线格式 {@code custom:<name>}）。 */
  record Custom(String name) implements HexAreaKey {

    public Custom {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("自定义字段名不得为空白: " + name);
      }
    }

    @Override
    public String wireKey() {
      return CUSTOM_PREFIX + name;
    }

    /** 自定义键对应的土地类型（同 wireValue）。 */
    public LandTypeId landType() {
      return new LandTypeId(wireKey());
    }
  }

  /** 线格式 → 键（唯一解析入口）。 */
  @JsonCreator
  static HexAreaKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("字段键不得为空白: " + text);
    }
    if (text.startsWith(CUSTOM_PREFIX)) {
      return new Custom(text.substring(CUSTOM_PREFIX.length()));
    }
    return Builtin.parse(text);
  }

  /** 标量键 → 土地类型 id。 */
  static LandTypeId landTypeOf(HexAreaKey key) {
    if (key instanceof Builtin builtin) {
      return builtin.landType();
    }
    if (key instanceof Custom custom) {
      return custom.landType();
    }
    throw new IllegalArgumentException("未知 HexAreaKey 实现: " + key.getClass().getName());
  }
}
