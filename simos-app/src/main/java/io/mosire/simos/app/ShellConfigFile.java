package io.mosire.simos.app;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Shell 启动**配置文件**的读取与校验（P1.1）：一个 JSON 对象，作为**缺省值源**——命令行参数优先，配置文件次之，最后才是内置缺省。
 *
 * <p>★ **它和命令行各管什么**：配置文件只提供"缺省值"，不引入任何新语义；最终生效值一律由 {@link ShellMain#parse} 合并后交给 {@link
 * ShellConfig}。因此本类**不构造 {@code ShellConfig}**，只交出"文件里明确写了什么"（每个字段都是 {@link Optional}，未写 = 不覆盖）。
 *
 * <p>★ **位置**：缺省 {@value #DEFAULT_PATH}（相对 CWD；与 {@code config/llm-providers.json}、{@code
 * config/skills} 同族），可用 {@code --config <file>} 改指。**缺省路径不存在 = 正常**（用内置缺省）；{@code --config}
 * 显式指定的文件不存在 = 错误。
 *
 * <p>★ **fail-closed（用户 2026-10-09 口径）**：文件在、但内容是坏数据——不是合法 JSON、根不是对象、字段类型不对、字段值为空白、
 * 端口为负、未知字段、同名字段写两遍——一律**具名抛 {@link IllegalArgumentException}**，消息里带文件路径与字段名。**没有"能读多少算 多少"**：
 * 静默忽略一个拼错的字段，等于让用户在毫不知情下跑在另一个世界上。
 *
 * <p>★ **支持的字段**（与 {@link ShellConfig} 一一对应；JSON 字段名 = 惯用名，见各字段注释）：
 *
 * <pre>{@code
 * {
 *   "world": "v17levant",
 *   "store": "./run/db",
 *   "checkpointInterval": 100,
 *   "guiPort": 5711,
 *   "mcpPort": 5715,
 *   "mcpPath": "/mcp",
 *   "approvalPort": 5713,
 *   "mcpInitiator": "agent:external-mcp",
 *   "mapId": "Map1",
 *   "bindAddress": "127.0.0.1",
 *   "openingSnapshot": false
 * }
 * }</pre>
 *
 * <p>★ 数值范围与 {@link ShellConfig} 同一口径（端口 ≥ 0、{@code checkpointInterval} ≥ 1、字符串非空白），但在本类里
 * **带文件与字段名**报错——用户拿到的是"哪个文件哪个字段错"，不是一句脱离上下文的"端口不得为负"。
 *
 * @param source 本覆盖集来自哪个文件（缺省路径不存在时也保留该路径，供报错与合并提示用）
 */
public record ShellConfigFile(
    Path source,
    Optional<Path> storeDir,
    Optional<String> worldId,
    Optional<Integer> checkpointInterval,
    Optional<Integer> guiPort,
    Optional<Integer> mcpPort,
    Optional<String> mcpPath,
    Optional<Integer> approvalPort,
    Optional<String> mcpInitiator,
    Optional<String> mapId,
    Optional<String> bindAddress,
    Optional<Boolean> openingSnapshot) {

  /** 缺省配置文件位置：相对 CWD（与 {@code config/llm-providers.json} / {@code config/skills} 同族）。 */
  public static final String DEFAULT_PATH = "config/shell.json";

  /** 允许出现的字段；多出来的字段一律 fail-closed（拼错字段名必须响亮，不许静默退化）。 */
  private static final List<String> KNOWN_FIELDS =
      List.of(
          "world",
          "store",
          "checkpointInterval",
          "guiPort",
          "mcpPort",
          "mcpPath",
          "approvalPort",
          "mcpInitiator",
          "mapId",
          "bindAddress",
          "openingSnapshot");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /**
   * 解析配置专用的 parser 工厂：在共享 mapper 的工厂上**复制**并开启重复字段拒绝（{@code STRICT_DUPLICATE_DETECTION}）—— 不改共享
   * mapper 的任何 feature，同名字段写两遍（后一次静默盖前一次）也按坏数据 fail-closed。
   */
  private static final JsonFactory PARSER_FACTORY =
      MAPPER.getFactory().rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  public ShellConfigFile {
    Objects.requireNonNull(source, "source");
    requireOptional(storeDir, "storeDir");
    requireOptional(worldId, "worldId");
    requireOptional(checkpointInterval, "checkpointInterval");
    requireOptional(guiPort, "guiPort");
    requireOptional(mcpPort, "mcpPort");
    requireOptional(mcpPath, "mcpPath");
    requireOptional(approvalPort, "approvalPort");
    requireOptional(mcpInitiator, "mcpInitiator");
    requireOptional(mapId, "mapId");
    requireOptional(bindAddress, "bindAddress");
    requireOptional(openingSnapshot, "openingSnapshot");
  }

  /**
   * 读缺省路径 {@value #DEFAULT_PATH}：**存在才读，缺失返回空覆盖**（内置缺省生效）；存在但坏数据 fail-closed。
   *
   * <p>★ 为什么缺失是正常态：配置文件是"缺省值源"，不是必需依赖；缺省值在 {@link ShellConfig} 常量里。
   */
  public static ShellConfigFile loadDefaultIfPresent() {
    Path path = Path.of(DEFAULT_PATH);
    if (!Files.exists(path)) {
      return empty(path);
    }
    return loadRequired(path);
  }

  /**
   * 读**显式指定**的配置文件：必须存在、必须是文件、必须能读出且内容合法。
   *
   * @throws IllegalArgumentException 文件不存在、是目录、或内容坏（具名）
   * @throws UncheckedIOException 文件存在但读不动（权限/IO）
   */
  public static ShellConfigFile loadRequired(Path path) {
    Objects.requireNonNull(path, "path");
    if (Files.isDirectory(path)) {
      throw new IllegalArgumentException("配置文件是目录，不是文件: " + path);
    }
    if (!Files.exists(path)) {
      throw new IllegalArgumentException("配置文件不存在: " + path + "（--config 显式指定）");
    }
    String json;
    try {
      json = Files.readString(path);
    } catch (IOException e) {
      throw new UncheckedIOException("配置文件读取失败: " + path, e);
    }
    return parse(json, path);
  }

  /** 全部字段为空（不覆盖任何内置缺省）。 */
  public static ShellConfigFile empty(Path source) {
    return new ShellConfigFile(
        source,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * 解析并校验 JSON 文本（包级可见，便于用例直接喂字符串，不依赖真实文件系统）。
   *
   * @throws IllegalArgumentException 根不是对象、有未知字段、或任何字段类型/取值非法
   */
  static ShellConfigFile parse(String json, Path source) {
    Objects.requireNonNull(json, "json");
    Objects.requireNonNull(source, "source");
    JsonNode root;
    try (JsonParser parser = PARSER_FACTORY.createParser(json)) {
      root = MAPPER.readTree(parser);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException("配置文件根必须是 JSON 对象: " + source);
      }
      if (parser.nextToken() != null) {
        throw new IllegalArgumentException("配置文件 JSON 对象之后还有多余内容: " + source);
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "配置文件不是合法 JSON: " + source + "（" + e.getMessage() + "）", e);
    }

    List<String> unknown = new ArrayList<>();
    root.fieldNames().forEachRemaining(name -> unknown.add(name));
    for (String name : unknown) {
      if (!KNOWN_FIELDS.contains(name)) {
        throw new IllegalArgumentException(
            "配置文件有未知字段 \""
                + name
                + "\": "
                + source
                + "（已支持: "
                + String.join(", ", KNOWN_FIELDS)
                + "）");
      }
    }

    return new ShellConfigFile(
        source,
        requiredPath(root, "store", source),
        requiredText(root, "world", source),
        integerField(root, "checkpointInterval", source, 1),
        integerField(root, "guiPort", source, 0),
        integerField(root, "mcpPort", source, 0),
        requiredText(root, "mcpPath", source),
        integerField(root, "approvalPort", source, 0),
        requiredText(root, "mcpInitiator", source),
        requiredText(root, "mapId", source),
        requiredText(root, "bindAddress", source),
        booleanField(root, "openingSnapshot", source));
  }

  private static Optional<String> requiredText(JsonNode root, String field, Path source) {
    JsonNode node = root.get(field);
    if (node == null) {
      return Optional.empty();
    }
    if (!node.isTextual()) {
      throw badValue(source, field, node, "必须是字符串");
    }
    String value = node.asText();
    if (value.isBlank()) {
      throw badValue(source, field, node, "不得为空白");
    }
    return Optional.of(value);
  }

  private static Optional<Path> requiredPath(JsonNode root, String field, Path source) {
    Optional<String> text = requiredText(root, field, source);
    if (text.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Path.of(text.get()));
    } catch (InvalidPathException e) {
      throw badValue(source, field, root.get(field), "不是合法路径");
    }
  }

  private static Optional<Integer> integerField(
      JsonNode root, String field, Path source, int minimum) {
    JsonNode node = root.get(field);
    if (node == null) {
      return Optional.empty();
    }
    if (!node.isIntegralNumber() || !node.canConvertToInt()) {
      throw badValue(source, field, node, "必须是 32 位整数");
    }
    int value = node.intValue();
    if (value < minimum) {
      throw badValue(source, field, node, "必须 ≥ " + minimum);
    }
    return Optional.of(value);
  }

  private static Optional<Boolean> booleanField(JsonNode root, String field, Path source) {
    JsonNode node = root.get(field);
    if (node == null) {
      return Optional.empty();
    }
    if (!node.isBoolean()) {
      throw badValue(source, field, node, "必须是 true / false");
    }
    return Optional.of(node.booleanValue());
  }

  private static IllegalArgumentException badValue(
      Path source, String field, JsonNode node, String expectation) {
    return new IllegalArgumentException(
        "配置文件字段非法: " + source + " 的 \"" + field + "\" " + expectation + "，实得: " + node);
  }

  private static void requireOptional(Optional<?> optional, String name) {
    Objects.requireNonNull(optional, name);
  }
}
