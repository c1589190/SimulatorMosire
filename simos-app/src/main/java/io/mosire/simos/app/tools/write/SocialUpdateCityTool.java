package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code social.UpdateCity} 窄工具（R2a，行政区划修复计划 §1.2/§R4）：**改城市 name / props / region** 的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>载荷</b>：{@code {id, name?, props?, region?}}——{@code name} 空白由域层拒、{@code props} 是**合并**
 * 语义；{@code region} 的三种键态见 {@code UpdateCityHandler}：<b>键缺席</b> = 保持原值、<b>字符串</b> = 设为该 Region、
 * <b>JSON null</b> = 清空归属；其余类型 ⇒ 域层 {@code Rejected}。
 *
 * <p>★ 工具层不做前置校验（那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰），拒绝理由由域层给、经 {@code ToolSupport.fold}
 * 变成可读的 {@code REJECTED}。
 *
 * <p>★ <b>目标资源</b>：{@code CommandTargets} 仍返回空列表（命令载荷不含坐标、social 资源语法也没有 city 专属路径）； GM
 * 直接调用不受影响，受限调用者继续 fail-closed（见 {@code UpdateCityHandler} 类注）。
 */
public final class SocialUpdateCityTool extends AbstractNarrowWriteTool {

  public static final String NAME = "social.UpdateCity";

  public SocialUpdateCityTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改城市 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "改城市：固定 social.UpdateCity，载荷 {id(必填), name?, props?(合并语义),"
        + " region?(string 或 null：null=清空归属，键缺席=保持原值)}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "payloadJson",
        ToolSupport.prop(
            "string",
            "命令载荷 JSON 文本：{id(必填), name?, props?(合并语义), region?(string 或 null："
                + "null=清空归属，键缺席=保持原值)}"));
    props.put("branch", ToolSupport.prop("string", "分支名"));
    props.put("expectedRevision", ToolSupport.prop("integer", "期望的 base revision（乐观并发）"));
    return ToolSupport.schema(props, List.of("branch", "expectedRevision"));
  }

  @Override
  public ResourceManifest resources() {
    return SOCIAL_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐：本工具钉死的 {@code social.UpdateCity} 只产 {@code SocialChangeSet}（实际只写 social 命名空间），故写断言取
   * {@code social:*}（GM 侧 unlimited），不沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return socialNamespaceWriteResources();
  }
}
