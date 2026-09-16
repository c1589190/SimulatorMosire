package io.mosire.simos.util.identity;

import java.util.List;

/** 解析结果：候选列表，保序（spec §四）。Human 形式可多解，全部列出；空列表 = 没有候选，不是错误。 */
public record QueryResult(List<ResolvedSubject> candidates) {

  public QueryResult {
    candidates = List.copyOf(candidates);
  }
}
