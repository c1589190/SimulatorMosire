package io.mosire.simos.app.docs;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 世界文档工具组（D-036）：AI 通过 MCP 对 {@code docs/} 下的地址文档做增/改/删/查。
 *
 * <p>★ 文档是独立持久数据，不是 world command；这些工具直接读写文件，不产生 revision。 这与 AgentLib 的 {@code doc_*}
 * 家族同一定位（外部文档面），不违反“世界状态写必须经 Command”的铁律。
 */
public final class WorldDocsToolset {

  private static final Set<ToolRole> READ_ROLES = Set.of(ToolRole.GM, ToolRole.DM);
  private static final Set<ToolRole> WRITE_ROLES = Set.of(ToolRole.GM);

  private WorldDocsToolset() {}

  /** 读工具（get/search/versions）。 */
  public static List<SimosTool> readTools(WorldDocsService docs) {
    return List.of(new GetTool(docs), new SearchTool(docs), new VersionsTool(docs));
  }

  /** 写工具（create/edit/delete）。 */
  public static List<SimosTool> writeTools(WorldDocsService docs) {
    return List.of(new CreateTool(docs), new EditTool(docs), new DeleteTool(docs));
  }

  private abstract static class Base implements SimosTool {
    protected final WorldDocsService docs;

    private Base(WorldDocsService docs) {
      this.docs = docs;
    }

    @Override
    public ResourceManifest resources() {
      return ToolSupport.ALL_READ;
    }
  }

  private abstract static class WriteBase extends Base {
    private WriteBase(WorldDocsService docs) {
      super(docs);
    }

    @Override
    public ResourceManifest resources() {
      return ToolSupport.ALL_WRITE;
    }

    @Override
    public ToolSpec spec() {
      return ToolSpec.level(AccessToken.DEFAULT, true, false);
    }

    @Override
    public ToolGate gate(ToolContext context) {
      return new ToolGate.Ask(
          name(), "写世界文档: address=" + context.arguments().get("address"), AskKind.SENSITIVE);
    }
  }

  private static final class GetTool extends Base {
    private GetTool(WorldDocsService docs) {
      super(docs);
    }

    @Override
    public String name() {
      return "simos.docs.get";
    }

    @Override
    public String description() {
      return "按地址读取世界文档：返回 meta（标题/名称/版本/删除时间）+ 当前正文 + 版本清单。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("address", ToolSchema.prop("string", "程序化地址（如 tree1-360/unit/army/vg-berlin）"));
      return ToolSchema.schema(props, List.of("address"));
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return READ_ROLES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        WorldDocView view = docs.get(ToolSupport.requiredText(context.arguments(), "address"));
        return ToolSupport.ok(view(view));
      } catch (WorldDocException e) {
        return ToolResult.error(e.code().name(), e.getMessage());
      } catch (IllegalArgumentException e) {
        return ToolResult.error("BAD_REQUEST", e.getMessage());
      }
    }
  }

  private static final class SearchTool extends Base {
    private SearchTool(WorldDocsService docs) {
      super(docs);
    }

    @Override
    public String name() {
      return "simos.docs.search";
    }

    @Override
    public String description() {
      return "查询世界文档：按地址/标题/名称做完整或部分匹配；includeDeleted=true 时包含回收站条目。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("q", ToolSchema.prop("string", "查询文本；空 = 列出全部"));
      props.put("includeDeleted", ToolSchema.prop("boolean", "是否包含已删除文档（缺省 false）"));
      return ToolSchema.schema(props, List.of());
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return READ_ROLES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        boolean includeDeleted = bool(context.arguments().get("includeDeleted"), false);
        String q = text(context.arguments().get("q"));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (WorldDocMeta meta : docs.search(q, includeDeleted)) {
          entries.add(meta(meta));
        }
        return ToolSupport.ok(Map.of("entries", entries));
      } catch (WorldDocException e) {
        return ToolResult.error(e.code().name(), e.getMessage());
      } catch (IllegalArgumentException e) {
        return ToolResult.error("BAD_REQUEST", e.getMessage());
      }
    }
  }

  private static final class VersionsTool extends Base {
    private VersionsTool(WorldDocsService docs) {
      super(docs);
    }

    @Override
    public String name() {
      return "simos.docs.versions";
    }

    @Override
    public String description() {
      return "列出一个地址的全部文档版本（版本号 + 文件）。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("address", ToolSchema.prop("string", "程序化地址"));
      return ToolSchema.schema(props, List.of("address"));
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return READ_ROLES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (WorldDocVersion version :
            docs.versions(ToolSupport.requiredText(context.arguments(), "address"))) {
          versions.add(Map.of("version", version.version(), "file", version.file()));
        }
        return ToolSupport.ok(Map.of("versions", versions));
      } catch (WorldDocException e) {
        return ToolResult.error(e.code().name(), e.getMessage());
      } catch (IllegalArgumentException e) {
        return ToolResult.error("BAD_REQUEST", e.getMessage());
      }
    }
  }

  private static final class CreateTool extends WriteBase {
    private CreateTool(WorldDocsService docs) {
      super(docs);
    }

    @Override
    public String name() {
      return "simos.docs.create";
    }

    @Override
    public String description() {
      return "新建世界文档：地址 + 标题 + Markdown 正文 + 名称/别名；地址已存在（未删除）⇒ 拒。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return writeSchema();
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return WRITE_ROLES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        WorldDocView view =
            docs.create(
                ToolSupport.requiredText(context.arguments(), "address"),
                text(context.arguments().get("title")),
                text(context.arguments().get("content")),
                names(context.arguments().get("names")));
        return ToolSupport.ok(view(view));
      } catch (WorldDocException e) {
        return ToolResult.error(e.code().name(), e.getMessage());
      } catch (IllegalArgumentException e) {
        return ToolResult.error("BAD_REQUEST", e.getMessage());
      }
    }
  }

  private static final class EditTool extends WriteBase {
    private EditTool(WorldDocsService docs) {
      super(docs);
    }

    @Override
    public String name() {
      return "simos.docs.edit";
    }

    @Override
    public String description() {
      return "编辑世界文档：先把旧 current.md 备份到 history/vN.md，再写新版本（版本号 +1）。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      return writeSchema();
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return WRITE_ROLES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        WorldDocView view =
            docs.edit(
                ToolSupport.requiredText(context.arguments(), "address"),
                text(context.arguments().get("title")),
                text(context.arguments().get("content")),
                names(context.arguments().get("names")));
        return ToolSupport.ok(view(view));
      } catch (WorldDocException e) {
        return ToolResult.error(e.code().name(), e.getMessage());
      } catch (IllegalArgumentException e) {
        return ToolResult.error("BAD_REQUEST", e.getMessage());
      }
    }
  }

  private static final class DeleteTool extends WriteBase {
    private DeleteTool(WorldDocsService docs) {
      super(docs);
    }

    @Override
    public String name() {
      return "simos.docs.delete";
    }

    @Override
    public String description() {
      return "删除世界文档：current.md 移入 docs/world/.recycle/<日期>/，meta 标删除日期。";
    }

    @Override
    public Map<String, Object> jsonSchema() {
      Map<String, Object> props = new LinkedHashMap<>();
      props.put("address", ToolSchema.prop("string", "程序化地址"));
      props.put("deletedDate", ToolSchema.prop("string", "删除日期（缺省今天，建议 yyyy-mm-dd）"));
      return ToolSchema.schema(props, List.of("address"));
    }

    @Override
    public Set<ToolRole> toolRoles() {
      return WRITE_ROLES;
    }

    @Override
    public ToolResult execute(ToolContext context) {
      try {
        WorldDocMeta meta =
            docs.delete(
                ToolSupport.requiredText(context.arguments(), "address"),
                text(context.arguments().get("deletedDate")));
        return ToolSupport.ok(Map.of("meta", meta(meta)));
      } catch (WorldDocException e) {
        return ToolResult.error(e.code().name(), e.getMessage());
      } catch (IllegalArgumentException e) {
        return ToolResult.error("BAD_REQUEST", e.getMessage());
      }
    }
  }

  private static Map<String, Object> writeSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("address", ToolSchema.prop("string", "程序化地址"));
    props.put("title", ToolSchema.prop("string", "标题（缺省取地址最后一段）"));
    props.put("content", ToolSchema.prop("string", "Markdown 正文"));
    props.put("names", ToolSchema.prop("array", "名称/别名（字符串数组）"));
    return ToolSchema.schema(props, List.of("address"));
  }

  private static Map<String, Object> meta(WorldDocMeta meta) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("address", meta.address());
    view.put("title", meta.title());
    view.put("names", meta.names());
    view.put("version", meta.version());
    view.put("createdAt", meta.createdAt());
    view.put("updatedAt", meta.updatedAt());
    view.put("deletedAt", meta.deletedAt());
    return view;
  }

  private static Map<String, Object> view(WorldDocView view) {
    List<Map<String, Object>> versions = new ArrayList<>();
    for (WorldDocVersion version : view.versions()) {
      versions.add(Map.of("version", version.version(), "file", version.file()));
    }
    return Map.of("meta", meta(view.meta()), "content", view.content(), "versions", versions);
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static boolean bool(Object value, boolean fallback) {
    if (value == null) {
      return fallback;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  private static List<String> names(Object value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof List<?> list)) {
      throw new IllegalArgumentException("names 必须是字符串数组");
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object item : list) {
      if (item == null) {
        continue;
      }
      out.add(String.valueOf(item));
    }
    return List.copyOf(out);
  }
}
