package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code unit.SetGovSuperior} 窄工具（阶段 10b-i，2026-10-01）：<b>改 GOV 上级层级</b>的唯一 GM 窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>载荷</b>：{@code {unitId, superiorGov?}}；{@code superiorGov} 缺省或 JSON {@code null} ⇒ 中央
 * （无上级）。非空上级必须存在、带 {@code GovernmentFormation}、不得指向自身，且不得成环（沿 {@code superiorGov} 上溯， 命中自己即拒；seen +
 * 最多 64 层兜底）。
 *
 * <p>★ 工具层不做前置校验（那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰），拒绝理由由域层给、经 {@code ToolResults.fold}
 * 变成可读的 {@code REJECTED}。
 */
public final class UnitSetGovSuperiorTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "unit.SetGovSuperior";

  public UnitSetGovSuperiorTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设 GOV 上级 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设/改 GOV 上级：固定 unit.SetGovSuperior，载荷 {unitId, superiorGov?}"
        + "（★ superiorGov 缺省或 null = 中央；非空必须存在且带 GovernmentFormation、不得指向自身；"
        + "沿 superiorGov 上溯不得成环（seen + 最多 64 层兜底）；单位本身必须是 GOV）";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}
