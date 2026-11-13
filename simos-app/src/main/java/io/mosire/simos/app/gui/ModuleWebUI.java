package io.mosire.simos.app.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块前端资源发现（D-036）：模块把自带 webui 资产放在 {@code src/main/resources/webui/modules/<id>/}， app 通过 {@code
 * /webui/modules.index.json} 列出模块 id，再读取各模块的 {@code manifest.json}。
 *
 * <p>★ 资源本身由 {@link StaticHandler} 从 classpath {@code /webui/...} 统一服务，不复制到 app；
 * 本类只做**发现/校验**，不实现模块 UI。
 *
 * <p>★ 模块清单缺失 / manifest 坏 ⇒ 该模块不进入结果并记录在 {@code errors}（fail-closed： 不让坏 manifest 静默变成“没有模块”）。
 */
public final class ModuleWebUI {

  private static final String INDEX_RESOURCE = "/webui/modules.index.json";
  private static final String MANIFEST_TEMPLATE = "/webui/modules/%s/manifest.json";
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private ModuleWebUI() {}

  /** 读取 app 索引里的模块，并返回每个模块的 manifest + 错误清单。 */
  public static Map<String, Object> discover() {
    List<Map<String, Object>> modules = new ArrayList<>();
    List<String> errors = new ArrayList<>();
    try (InputStream index = ModuleWebUI.class.getResourceAsStream(INDEX_RESOURCE)) {
      if (index == null) {
        errors.add("缺少模块前端索引: " + INDEX_RESOURCE);
        return Map.of("modules", modules, "errors", errors);
      }
      JsonNode root = MAPPER.readTree(index);
      JsonNode ids = root.get("modules");
      if (ids == null || !ids.isArray()) {
        errors.add("模块前端索引缺 modules 数组: " + INDEX_RESOURCE);
        return Map.of("modules", modules, "errors", errors);
      }
      for (JsonNode idNode : ids) {
        String id = idNode.asText();
        if (id.isBlank()) {
          errors.add("模块前端索引含空 id");
          continue;
        }
        String manifestPath = String.format(MANIFEST_TEMPLATE, id);
        try (InputStream manifest = ModuleWebUI.class.getResourceAsStream(manifestPath)) {
          if (manifest == null) {
            errors.add("模块 " + id + " 缺少 manifest: " + manifestPath);
            continue;
          }
          JsonNode node = MAPPER.readTree(manifest);
          if (!node.isObject() || node.path("webuiId").asText().isBlank()) {
            errors.add("模块 " + id + " 的 manifest 缺 webuiId: " + manifestPath);
            continue;
          }
          Map<String, Object> view = new LinkedHashMap<>();
          view.put("id", id);
          view.put("webuiId", node.path("webuiId").asText());
          view.put("module", node.path("module").asText());
          view.put("label", node.path("label").asText());
          view.put("version", node.path("version").asInt(1));
          view.put("assets", MAPPER.convertValue(node.path("assets"), List.class));
          modules.add(view);
        } catch (IOException | IllegalArgumentException e) {
          errors.add("模块 " + id + " manifest 读取失败: " + e.getMessage());
        }
      }
    } catch (IOException e) {
      errors.add("模块前端索引读取失败: " + e.getMessage());
    }
    return Map.of("modules", modules, "errors", errors);
  }
}
