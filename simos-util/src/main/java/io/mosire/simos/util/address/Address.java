package io.mosire.simos.util.address;

import java.util.List;

/**
 * 地址：段序列。首段是命名空间、第 2 段是该命名空间的根主体（spec §3.2）。
 *
 * <p>构造期校验：至少两段、首段是 {@link Namespace}、各段类型的落位符合 spec §3.2 段类型表——后者是 canonical 唯一与
 * `parse(canonical(x)) == x` 的防线（段类型无位置信息，只能在这一层判）。
 *
 * <p>解析宽容（人类形式），{@link #canonical()} 唯一（机器协议只用这个，spec §3.4）。
 */
public record Address(List<AddressSegment> segments) {

  public Address {
    segments = List.copyOf(segments);
    if (segments.size() < 2) {
      throw new IllegalArgumentException("地址至少两段（命名空间 + 根主体）：" + segments);
    }
    if (!(segments.get(0) instanceof Namespace)) {
      throw new IllegalArgumentException("地址首段必须是命名空间：" + segments.get(0));
    }
    for (int i = 1; i < segments.size(); i++) {
      AddressSegment segment = segments.get(i);
      // 段类型表（spec §3.2）允许的落位：第 2 段是根主体（Entity / Index），第 ≥3 段是 Entity / Index /
      // Property。其余落位都会渲染出与别种段逐字相同的 canonical（都是裸词），唯一性与往返同时破。
      if (segment instanceof Namespace) {
        throw new IllegalArgumentException("第 " + (i + 1) + " 段是命名空间——命名空间只允许出现在第 1 段：" + segment);
      }
      if (i == 1 && segment instanceof Property) {
        throw new IllegalArgumentException("第 2 段是属性——第 2 段是该命名空间的根主体（Entity）：" + segment);
      }
      if (i >= 2 && rendersAsBareSubject(segment)) {
        throw new IllegalArgumentException(
            "第 " + (i + 1) + " 段是缺 kind 的实体——裸词主体只允许出现在根位置（第 2 段）：" + segment);
      }
    }
  }

  /**
   * 该段是否是"渲染成裸词"的缺 kind 实体——第 ≥3 段非法（spec §3.2：裸词主体只出现在根位置）。
   *
   * <p>它的 canonical 与同名 {@link Property} 逐字相同（都是 `member`），canonical 唯一性与 `parse(canonical(x)) ==
   * x` 会同时破。名字需要加引的缺 kind 实体不在此列（如 {@code "Nation.区域A"}）： 它的 canonical 自带引号，解析回来仍是同一个缺 kind
   * 实体——spec §3.1 的 Human 形式靠它承载。
   *
   * <p>解析器不会产出这种实体（`unit:U:"member"` 按 spec §3.4 末段归一并读成 {@link Property}），
   * 故本校验守的是直接构造——段类型无位置信息，只能在这一层判（spec §3.2 末段）。
   */
  private static boolean rendersAsBareSubject(AddressSegment segment) {
    return segment instanceof Entity e && e.kind().isEmpty() && AddressText.isBareWord(e.name());
  }

  public static Address parse(String text) {
    return AddressParser.parse(text);
  }

  /** 首段的命名空间标识，例如 `map`。 */
  public String namespace() {
    return ((Namespace) segments.get(0)).ident();
  }

  public String canonical() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < segments.size(); i++) {
      if (i > 0) {
        sb.append(':');
      }
      sb.append(segments.get(i).canonical());
    }
    return sb.toString();
  }
}
