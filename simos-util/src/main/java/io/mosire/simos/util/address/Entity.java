package io.mosire.simos.util.address;

import java.util.Objects;
import java.util.Optional;

/**
 * 实体段：`kind.name`（`hex.4_3`）或根主体名（`Map1`，kind 缺省）。
 *
 * <p>kind 必须是裸词——它是结构词（类型判别用），任意字符的载体是 name（spec §3.2/§3.4）。
 */
public record Entity(Optional<String> kind, String name) implements AddressSegment {

  public Entity {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(name, "name");
    if (kind.isPresent() && !AddressText.isBareWord(kind.get())) {
      throw new IllegalArgumentException("kind 必须是裸词（非空、无空白、不含 : . [ ] \"）：" + kind.get());
    }
  }

  /** 缺省 kind 的实体（根主体，或 Human 形式里省略类型词的实体）。 */
  public static Entity of(String name) {
    return new Entity(Optional.empty(), name);
  }

  public static Entity of(String kind, String name) {
    return new Entity(Optional.of(kind), name);
  }

  @Override
  public String canonical() {
    return kind.map(k -> k + ".").orElse("") + AddressText.quoteIfNeeded(name, kind.isEmpty());
  }
}
