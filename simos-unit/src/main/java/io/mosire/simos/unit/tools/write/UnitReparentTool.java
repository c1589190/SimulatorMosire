package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code unit.ReparentUnit} 窄工具（M2，spec §八.3）：**改单位的父**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **{@code parent} 缺省或为 null = 清根**，**不是**"不动"：这条已写进 {@link #description()}，让模型在调用前就看得见——
 * 少了它，模型会把"清根"当成"不改"来用（与 M1 的 `meta` 警告同族）。
 */
public final class UnitReparentTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "unit.ReparentUnit";

  public UnitReparentTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改单位的父 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "改单位的父：固定 unit.ReparentUnit，载荷 {id, parent?}（★ parent 缺省或为 null = 清根，不是“不动”）";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}
