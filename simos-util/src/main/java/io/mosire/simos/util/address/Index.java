package io.mosire.simos.util.address;

import java.util.List;
import java.util.stream.Collectors;

/** 索引段：`[q,r]` 或 `[i]`（Human 形式；canonical 的单个 hex 用 `hex.4_3` 形式）。 */
public record Index(List<Integer> coords) implements AddressSegment {

  public Index {
    coords = List.copyOf(coords);
    if (coords.isEmpty()) {
      throw new IllegalArgumentException("Index 段至少一个坐标");
    }
  }

  @Override
  public String canonical() {
    return coords.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
  }
}
