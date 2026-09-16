package io.mosire.simos.util.state;

/** 数据版本号：只管数据版本，与模拟时间（`SimosTimestamp`）互不换算（总纲 §0.1）。 */
public record RevisionId(long value) implements Comparable<RevisionId> {

  @Override
  public int compareTo(RevisionId other) {
    return Long.compare(value, other.value);
  }
}
