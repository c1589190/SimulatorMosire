package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.skill.Skill;
import io.mosire.simos.app.skill.SkillLibrary;
import io.mosire.simos.app.skill.SkillSummary;
import io.mosire.simos.app.tools.ToolSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code simos.skill}（Skill 系统，2026-09-23）：**决策人的方法论与常识**——读一份外部维护的 Markdown 库。
 *
 * <p>★ **用户的诉求**：「Skill 系统就是决策人应该怎么做决策的系统……要有 Skill 指导决策人 Agent 的政治经济军事常识和决策人
 * 系统允许其干什么」。本工具承担**常识与方法论**那一半；「系统允许其干什么」那一半由 {@code simos.command.catalog} 的
 * **按调用者权限过滤**承担（用户同日裁定），两者一起回答"我该做什么、我能做什么"。
 *
 * <p>★ **两级读法（省 token）**：不给 {@code id} ⇒ 只回**目录**（id/标题/版本）；给了 {@code id} ⇒ 回那**一篇**的正文。
 * 一次把整库正文塞进上下文是最贵的做法，而模型通常只需要其中一两篇。
 *
 * <p>★ **两桶共享**：决策人读它是本职；GM 读它是为了**写出与之一致的文档**（Docs 系统）——同一份知识，两个入口（与
 * {@code CatalogTool} 同款）。
 *
 * <p>★ **不在世界 revision 内**（用户裁定："存在外部方便读取、写入、修改，兼容其他外部 Agent"）：改文件**不用重启**即生效
 * （按 mtime 热更），任何外部 agent 直接用文本工具就能改。代价是**没有世界版本锚** ⇒ 本工具把
 * {@code version}/{@code source}/{@code modifiedAt} 一并返回，供审计对齐（见 {@link SkillLibrary} 的类注）。
 *
 * <p>★ **它没有资源声明**（{@code ResourceManifest.NONE}，与 {@code CatalogTool} 同款）：技能不在世界里，没有"哪块资源能不能碰"
 * 这一维；它的可见性就是"能调到这个工具"。
 */
public final class SkillTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.skill";

  private final SkillLibrary library;

  public SkillTool(SkillLibrary library) {
    this.library = library;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "读**决策方法论与常识**（外部 Markdown 库，改文件即生效）：不给 id ⇒ 返回目录"
        + "（每篇的 id / 标题 / 版本）；给 id ⇒ 返回那一篇的正文。"
        + "★ 建议开局先看一次目录、再按需读正文——它讲的是'怎么做决策'（政治/经济/军事的判断口径与常见误区）；"
        + "'系统允许你干什么'看 simos_command_catalog。"
        + "返回 {skills:[{id,title,version}], count} 或 {skill:{id,title,version,body,source,modifiedAt}}";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("id", ToolSupport.prop("string", "要读哪一篇（不给则只列目录）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      String id = ToolSupport.optionalText(context.arguments(), "id", null);
      if (id == null) {
        return ToolSupport.ok(catalogue());
      }
      Optional<Skill> skill = library.find(id);
      if (skill.isEmpty()) {
        // ★ 明确可读的"没有这一篇"（并把目录一并给出，模型下一次调用就不用猜）——不是静默空。
        return ToolResult.error(
            "NOT_FOUND",
            "没有这个技能："
                + id
                + "（用不带 id 的调用看目录。当前有："
                + idsOf(library.list())
                + "）");
      }
      Map<String, Object> view = new LinkedHashMap<>();
      Skill found = skill.get();
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("id", found.id());
      body.put("title", found.title());
      body.put("version", found.version());
      body.put("body", found.body());
      body.put("source", found.source().toString());
      body.put("modifiedAt", found.modifiedMillis());
      view.put("skill", body);
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  private Map<String, Object> catalogue() {
    List<SkillSummary> summaries = library.list();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (SkillSummary summary : summaries) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", summary.id());
      row.put("title", summary.title());
      row.put("version", summary.version());
      rows.add(row);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("skills", rows);
    view.put("count", rows.size());
    if (rows.isEmpty()) {
      view.put(
          "note",
          "技能库是空的：把 Markdown 放进 "
              + SkillLibrary.DEFAULT_SEED_DIR
              + "（仓库种子）或 "
              + library.storeDir()
              + "（store 覆盖，同 id 优先）");
    }
    return view;
  }

  private static List<String> idsOf(List<SkillSummary> summaries) {
    List<String> out = new ArrayList<>();
    for (SkillSummary summary : summaries) {
      out.add(summary.id());
    }
    return List.copyOf(out);
  }
}
