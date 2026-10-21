package io.mosire.simos.map;

/**
 * 土地抽象属性的严格值：{@code kind + canonicalValue}。不允许裸 {@code Object}，也不允许“看起来像整数就猜成整数”。
 *
 * <p>规范串规则：LONG 必须等于 {@link Long#toString(long)} 的输出；BOOLEAN 只接受 {@code true}/{@code false}； STRING
 * 非空白。构造期即 fail-closed，读取时按 kind 取具体值。
 *
 * @param kind 值类型；不得为 null
 * @param canonicalValue 规范字符串；非空白
 */
public record LandAttributeValue(LandAttributeKind kind, String canonicalValue) {

  public LandAttributeValue {
    if (kind == null) {
      throw new IllegalArgumentException("LandAttributeValue.kind 不得为 null");
    }
    if (canonicalValue == null || canonicalValue.isBlank()) {
      throw new IllegalArgumentException("LandAttributeValue.canonicalValue 不得为空白");
    }
    switch (kind) {
      case LONG -> {
        long parsed;
        try {
          parsed = Long.parseLong(canonicalValue);
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("LONG 属性必须是规范十进制 long: " + canonicalValue, e);
        }
        if (!Long.toString(parsed).equals(canonicalValue)) {
          throw new IllegalArgumentException("LONG 属性不是规范十进制: " + canonicalValue);
        }
      }
      case BOOLEAN -> {
        if (!"true".equals(canonicalValue) && !"false".equals(canonicalValue)) {
          throw new IllegalArgumentException("BOOLEAN 属性只接受 true/false: " + canonicalValue);
        }
      }
      case STRING -> {
        // 非空白已由上面的公共校验守住。
      }
    }
  }

  /** 整数属性。 */
  public static LandAttributeValue ofLong(long value) {
    return new LandAttributeValue(LandAttributeKind.LONG, Long.toString(value));
  }

  /** 字符串属性（调用方给的字符串非空白）。 */
  public static LandAttributeValue ofString(String value) {
    return new LandAttributeValue(LandAttributeKind.STRING, value);
  }

  /** 布尔属性。 */
  public static LandAttributeValue ofBoolean(boolean value) {
    return new LandAttributeValue(LandAttributeKind.BOOLEAN, Boolean.toString(value));
  }

  /** 按 LONG 读取；kind 不符具名抛。 */
  public long longValue() {
    if (kind != LandAttributeKind.LONG) {
      throw new IllegalArgumentException("属性不是 LONG，而是 " + kind + ": " + canonicalValue);
    }
    return Long.parseLong(canonicalValue);
  }

  /** 按 STRING 读取；kind 不符具名抛。 */
  public String stringValue() {
    if (kind != LandAttributeKind.STRING) {
      throw new IllegalArgumentException("属性不是 STRING，而是 " + kind + ": " + canonicalValue);
    }
    return canonicalValue;
  }

  /** 按 BOOLEAN 读取；kind 不符具名抛。 */
  public boolean booleanValue() {
    if (kind != LandAttributeKind.BOOLEAN) {
      throw new IllegalArgumentException("属性不是 BOOLEAN，而是 " + kind + ": " + canonicalValue);
    }
    return Boolean.parseBoolean(canonicalValue);
  }
}
