package io.mosire.simos.app.editor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.llm.ToolAssetResolver;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.permission.ResourceAuthorizer;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolRegistry;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link EditorToolBridge} 的宿主实现：按固定白名单从 GM 工具注册表取**同一条领域工具**执行。
 *
 * <p>★ <b>不是第二套写入状态机</b>：不拼命令、不算领域计划，直接把 HTTP 载荷交给领域工具的 {@code execute}；计划/校验/原子批都在
 * 领域工具里。白名单只有调用方显式给的那几条（本类不提供"任意名字"入口）。
 *
 * <p>★ <b>权限</b>：网页编辑器是 GM 面，用系统权限集 + 工具自己的 manifest 建资源判定者；工具名白名单是第二道闸——两者都过才执行。
 *
 * <p>★ <b>图片</b>：工具结果里的 assetId 由同一个工件库解析成 bytes，以 base64 随响应返回（GUI 可直接显示 data URL），不把 base64
 * 写进领域工具文本。
 */
public final class RegistryEditorToolBridge implements EditorToolBridge {

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final ToolRegistry registry;
  private final ToolAssetResolver assets;
  private final Set<String> allowedTools;

  public RegistryEditorToolBridge(
      ToolRegistry registry, ToolAssetResolver assets, Set<String> allowedTools) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.assets = Objects.requireNonNull(assets, "assets");
    this.allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
  }

  @Override
  public Map<String, Object> invoke(String toolName, Map<String, Object> arguments) {
    if (toolName == null || !allowedTools.contains(toolName)) {
      throw new IllegalArgumentException("HTTP 编辑器不允许调用该工具: " + toolName);
    }
    AgentTool tool =
        registry
            .find(toolName)
            .orElseThrow(() -> new IllegalArgumentException("工具未装配: " + toolName));
    AgentPermissionSet permissions = AgentPermissionSet.system();
    ResourceAuthorizer authorizer = ResourceAuthorizer.of(permissions, tool.resources());
    ToolContext context =
        new ToolContext(
            AccessToken.SYSTEM,
            permissions,
            Map.of(),
            arguments == null ? Map.of() : arguments,
            AgentIdentity.UNKNOWN,
            authorizer);
    ToolResult result = tool.execute(context);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("ok", result.success());
    if (result.code() != null) {
      out.put("code", result.code());
    }
    out.put("message", result.message());
    Object parsed = parse(result.message());
    if (parsed != null) {
      out.put("result", parsed);
    }
    List<Map<String, Object>> images = new ArrayList<>();
    for (String assetId : result.assetDocIds()) {
      Optional<ToolAsset> asset = assets.resolve(assetId);
      if (asset.isEmpty()) {
        throw new IllegalStateException("工具结果引用了不可解析的资产: " + assetId);
      }
      ToolAsset value = asset.orElseThrow();
      Map<String, Object> image = new LinkedHashMap<>();
      image.put("assetId", assetId);
      image.put("mediaType", value.mediaType());
      image.put("byteSize", value.size());
      image.put("base64", java.util.Base64.getEncoder().encodeToString(value.bytes()));
      images.add(image);
    }
    if (!images.isEmpty()) {
      out.put("images", List.copyOf(images));
    }
    return out;
  }

  /** 工具结果文本本就是 JSON 时解析成对象供前端直接用；否则保留原文本。 */
  private static Object parse(String message) {
    if (message == null || message.isBlank()) {
      return null;
    }
    String text = message.strip();
    if (!text.startsWith("{") && !text.startsWith("[")) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      return MAPPER.convertValue(node, Object.class);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return null;
    } catch (RuntimeException e) {
      return null;
    }
  }
}
