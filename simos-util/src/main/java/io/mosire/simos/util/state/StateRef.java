package io.mosire.simos.util.state;

import java.util.Objects;

/** 状态的唯一坐标：分支 + 版本（总纲 §4.1）。 */
public record StateRef(BranchId branch, RevisionId revision) {

  public StateRef {
    Objects.requireNonNull(branch, "branch");
    Objects.requireNonNull(revision, "revision");
  }
}
