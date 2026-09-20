package io.mosire.simos.unit;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 命令链（spec §一.2，E1 的第一级）：**谁向谁报告**。一条链 = 一个 commander + 一组 members 的**扁平星形**关系。
 *
 * <p>★ **多属是正常态**：同一个 {@link UnitId} 可出现在多条链里（这正是 P11「可多属」的意义）；链是**扁平具名集合**，链
 * **不构成层级**——报告层级由多条链叠加表达，链内无环问题，唯一要防环的是 {@code Formation} 树（{@code UnitState} 构造期已有的那套）。
 *
 * <p>★ 构造期不变量（spec §一.2）：{@code commander ∈ members}、{@code members} 非空。引用完整性（commander/members
 * 存在于同快照的 {@code units}）由 {@link UnitState} 构造期判。{@code members} 保序不可变（{@code LinkedHashSet} +
 * {@code unmodifiableSet}，**绝不用 {@code Set.copyOf}**——理由见 {@code FieldDelta} 类注释）。
 */
public record CommandChain(CommandChainId id, String name, UnitId commander, Set<UnitId> members) {

  public CommandChain {
    Objects.requireNonNull(id, "id");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    Objects.requireNonNull(commander, "commander");
    if (members == null) {
      throw new IllegalArgumentException("members 不得为 null");
    }
    Set<UnitId> copy = new LinkedHashSet<>();
    for (UnitId member : members) {
      if (member == null) {
        throw new IllegalArgumentException("members 不得含 null");
      }
      copy.add(member);
    }
    members = Collections.unmodifiableSet(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
    if (members.isEmpty()) {
      throw new IllegalArgumentException("members 不得为空");
    }
    if (!members.contains(commander)) {
      throw new IllegalArgumentException("commander 必须是 members 之一: " + commander);
    }
  }
}
