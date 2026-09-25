package io.mosire.simos.app.tools.read;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.llm.AgentLibLlmConfig;
import io.mosire.simos.app.tools.GmOnlyRead;
import io.mosire.simos.app.tools.ToolSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.llm.providers}（工具面补齐，2026-09-25）：**LLM provider 配置的掩码视图**（只读）。
 *
 * <p>补的是 GUI 读口 {@code GET /api/llm/providers}（{@code GuiServer:203} 常量、{@code GuiServer:641} 分支）在
 * MCP 侧的对应工具。数据源是 {@link AgentLibLlmConfig#views()}（AgentLib {@code ConfigStore} 承载的 {@code
 * llm.routes.*} / {@code keys.*}）。
 *
 * <p>★★ **掩码已核：不回任何密钥值**（M4 §二-5 的静态结论，本次复核到字节码层）：{@code AgentLibLlmConfig.view} （{@code
 * AgentLibLlmConfig.java:208-235}）只回 {@code id / valid / baseUrl / model / protocol / readTimeoutMs
 * / connectTimeoutMs / credentialsRef / keyConfigured / capabilities}；其中 {@code credentialsRef} 走
 * {@code maskCredentials}（{@code :472-477}，不匹配安全名形态就变 {@code "****"}）， {@code keyConfigured}
 * 只报**布尔**（{@code :311-334}）。坏条目分支的 {@code error = e.getMessage()} （{@code :233}）也安全：{@code
 * ConfigException} 的消息 = {@code code + ": " + message}，而 AgentLib 的 {@code LlmRouteLoader} 只把**路由名
 * / 字段键名**（如 {@code llm: baseUrl}）拼进消息（本次对 {@code agentlib-mosire-0.1.0-SNAPSHOT.jar} 的 {@code
 * ConfigException}/{@code LlmRouteLoader} 反汇编核对）， **不含任何配置值**。⇒ 仍会暴露的是 {@code baseUrl}（内网端点）、{@code
 * model} 与 {@code credentialsRef} 的**键名** ——是配置面，不是密钥。
 *
 * <p>★★ **只在 GM 桶**（{@link GmOnlyRead}）：M4 §二-5 的结论是"不给决策人桶"——{@code baseUrl}/{@code model}/key
 * 名是**对手的模型配置与观测面**，决策人/外部 Agent 不该看。GM 面本就全权（GUI 同端点）。资源声明取 {@link ResourceManifest#NONE}：它读的是
 * AgentLib 配置，不读任何领域命名空间。
 */
public final class LlmProvidersTool implements AgentTool, GmOnlyRead {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.llm.providers";

  private final AgentLibLlmConfig config;

  /** {@code config} 允许为 null（未接入 ConfigStore 的装配）⇒ 执行期回可读的 {@code UNAVAILABLE}，不静默给空。 */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "AgentLibLlmConfig 是共享配置门面（本工具只调只读的 views()），与 Shell.llmConfig() 同口径豁免")
  public LlmProvidersTool(AgentLibLlmConfig config) {
    this.config = config;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "LLM provider 配置（掩码视图，不含密钥值）：{providers:[{id,valid,baseUrl,model,protocol,"
        + "readTimeoutMs,connectTimeoutMs,credentialsRef,keyConfigured,capabilities}]}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return ToolSupport.schema(new LinkedHashMap<>(), List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ResourceManifest.NONE;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    if (config == null) {
      return ToolResult.error("UNAVAILABLE", "LLM 配置未接入（AgentLib ConfigStore 缺席）");
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("providers", config.views());
    return ToolSupport.ok(view);
  }
}
