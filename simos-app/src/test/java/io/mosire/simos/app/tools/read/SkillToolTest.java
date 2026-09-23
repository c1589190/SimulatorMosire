package io.mosire.simos.app.tools.read;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.AgentIdentity;
import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.skill.SkillLibrary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code simos.skill} 的读面：**目录（省 token）** 与 **单篇正文**，以及"没有这一篇"必须响亮。
 *
 * <p>★ 目录不带正文是有意为之：整库正文一次塞进上下文是最贵的做法，而模型通常只读其中一两篇。
 */
class SkillToolTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path tempDir;

  @Test
  void anIdLessCallReturnsTheCatalogueWithoutBodies() throws Exception {
    JsonNode body = body(call(Map.of()));

    assertThat(body.get("count").asInt()).isEqualTo(2);
    assertThat(body.get("skills").get(0).get("id").asText()).as("按 id 升序").isEqualTo("a-first");
    assertThat(body.get("skills").get(0).get("title").asText()).isEqualTo("第一篇");
    assertThat(body.get("skills").get(0).has("body"))
        .as("★ 目录里**不带正文**（省 token：正文靠 id 再取）")
        .isFalse();
  }

  @Test
  void anIdReturnsThatOneDocumentInFull() throws Exception {
    JsonNode body = body(call(Map.of("id", "a-first")));

    JsonNode skill = body.get("skill");
    assertThat(skill.get("id").asText()).isEqualTo("a-first");
    assertThat(skill.get("version").asInt()).isEqualTo(2);
    assertThat(skill.get("body").asText()).contains("第一篇的正文");
    // ★ 审计锚：技能不在世界 revision 内 ⇒ version/source/modifiedAt 是"当时读的是哪一版"的凭据。
    assertThat(skill.get("source").asText()).contains("a-first.md");
    assertThat(skill.has("modifiedAt")).isTrue();
  }

  @Test
  void anUnknownIdIsAnExplicitFailureThatAlsoListsWhatExists() throws Exception {
    ToolResult result = call(Map.of("id", "nope"));

    assertThat(result.success()).as("查不到**不是**静默成功").isFalse();
    assertThat(result.code()).isEqualTo("NOT_FOUND");
    assertThat(result.message()).as("★ 顺带把目录给它，省掉下一次调用").contains("a-first", "b-second");
  }

  @Test
  void anEmptyLibrarySaysHowToFillIt() throws Exception {
    SkillLibrary empty = SkillLibrary.open(tempDir.resolve("nope"), tempDir.resolve("also-nope"));
    JsonNode body = body(new SkillTool(empty).execute(context(Map.of())));

    assertThat(body.get("count").asInt()).isZero();
    assertThat(body.get("note").asText()).as("空库要告诉人**往哪放文件**").contains("skills");
  }

  // ── 助手 ───────────────────────────────────────────────────────────────────

  private ToolResult call(Map<String, Object> args) {
    return new SkillTool(library()).execute(context(args));
  }

  private SkillLibrary library() {
    Path store = tempDir.resolve("store");
    try {
      Files.createDirectories(store);
      Files.writeString(
          store.resolve("a-first.md"),
          "---\n{\"id\":\"a-first\",\"title\":\"第一篇\",\"version\":2}\n---\n# 第一篇\n\n第一篇的正文\n",
          StandardCharsets.UTF_8);
      Files.writeString(store.resolve("b-second.md"), "# 第二篇\n\n第二篇的正文\n", StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    return SkillLibrary.open(tempDir.resolve("seed"), store);
  }

  /** 本工具只读 {@code arguments()}，其余走缺省（不冒充有判定者）。 */
  private static ToolContext context(Map<String, Object> args) {
    return new ToolContext(
        AccessToken.DEFAULT,
        AgentPermissionSet.builder(AccessToken.DEFAULT).allowAll().build(),
        Map.of(),
        new LinkedHashMap<>(args),
        AgentIdentity.external());
  }

  private static JsonNode body(ToolResult result) throws Exception {
    assertThat(result.success()).as("调用应成功：%s", result.message()).isTrue();
    return JSON.readTree(result.message());
  }
}
