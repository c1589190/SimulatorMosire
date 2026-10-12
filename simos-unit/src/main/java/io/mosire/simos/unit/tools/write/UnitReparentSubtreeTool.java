package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code unit.ReparentSubtree} 窄工具（M2，spec §八.3）：**整树改挂**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 给 {@code rootId} **及其全部后代**在同一刻追加 {@code parent} 段（后代的值仍是它本来的父）⇒ 与 {@code
 * unit.ReparentUnit}（只动一个节点）是两条不同的命令。新父落在子树内（含自身）⇒ 域层拒「会成环」。
 *
 * <p>★★ **{@code parent} 缺省或为 null = 提升为根**（把整棵子树变成根树），**不是**"不动"：这条已写进 {@link
 * #description()}，让模型在调用前就看得见。
 */
public final class UnitReparentSubtreeTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "unit.ReparentSubtree";

  public UnitReparentSubtreeTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "整树改挂 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "整树改挂：固定 unit.ReparentSubtree，载荷 {rootId, parent?}（★ parent 缺省或为 null = 提升为根，不是“不动”）";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
