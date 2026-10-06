package io.mosire.simos.app.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;

/**
 * ★★ **Skill 库**：决策人 Agent 的**外部知识**（用户 2026-09-23 原话：「Skill 系统就是决策人应该怎么做决策的系统…… 要有 Skill 指导决策人
 * Agent 的政治经济军事常识和决策人系统允许其干什么」）。
 *
 * <p>★★ **为什么是外部文件、不是 classpath 资源、也不是世界数据**（用户裁定：「最好搞成存在外部方便读取、写入、修改，还要兼容其他 外部 Agent 方便进行 GM Agent
 * 的通用修改」）：
 *
 * <ul>
 *   <li>**外部文件** ⇒ 任何外部 agent 用文本工具就能读/写/改，不必经过本仓的工具面；
 *   <li>**不在 revision 内** ⇒ 与 {@code conversations.db}、{@code agentlib/} 同族（它们是"运行环境"不是"世界事实"）。 ★
 *       诚实边界：改 skill 会改变模型行为，但**不留下世界版本锚**（回放一段 AAR 时对不上"当时读的是哪一版"）； {@link #find} 返回的 {@code
 *       version}/{@code modifiedMillis} 就是给审计用的那一份锚；
 *   <li>**两处来源**：仓库种子 {@code config/skills/}（随仓库版本化） + store 覆盖 {@code <storeDir>/skills/}。★ 同 id
 *       **store 版优先**（照 {@code AgentLibLlmConfig} 的「仓库默认 + store 覆盖」）。 理由：dev 常跑临时 store，只靠 store
 *       会"重启即失忆"；种子让默认知识可提交、可评审。
 * </ul>
 *
 * <p>★ **文件格式**（Markdown + 可选的一段单行 JSON 头）：
 *
 * <pre>{@code
 * ---
 * {"id":"decision-basics","title":"决策总则","version":1}
 * ---
 * # 正文……
 * }</pre>
 *
 * 头**可省**：省了就用文件名当 id、用正文里第一个 {@code #} 标题当 title、version 记 1。头**给了但坏了**（JSON 不合法 / 围栏不闭合）⇒
 * 那一条**不生效**（保留上一版并记日志），绝不让"写坏一个文件"变成"整个世界打不开"。
 *
 * <p>★ **热更新**：读的时候按文件 {@code mtime} 判要不要重读 ⇒ **改文件不用重启**。★ 并发写无锁（后写覆盖前写、可能读到半写文件）⇒ 解析失败时**保留上一版 +
 * 记日志**，不崩、不静默改语义。
 */
public final class SkillLibrary {

  /** store 侧的目录名（与 {@code conversations.db} 同层）。 */
  public static final String STORE_DIR_NAME = "skills";

  /** 仓库种子的目录（CWD 相对；与 {@code config/llm-providers.json} 同族）。 */
  public static final Path DEFAULT_SEED_DIR = Path.of("config", STORE_DIR_NAME);

  /** 只认这个扩展名的文件（其余一概忽略，避免把 README 之类当技能）。 */
  public static final String EXTENSION = ".md";

  /** 头的围栏行（独占一行、两侧可有空白）。 */
  private static final String FENCE = "---";

  /** 正文里第一个一级标题（取不到就退回 id）。 */
  private static final Pattern TITLE_LINE = Pattern.compile("^#\\s+(\\S.*?)\\s*$");

  private static final Logger LOG = AppLog.shell();
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();
  private static final TypeReference<Map<String, Object>> HEADER_TYPE = new TypeReference<>() {};

  private final Path seedDir;
  private final Path storeDir;

  /** mtime 缓存：按**绝对路径**记（同一个 id 的两个来源互不干扰）；解析失败时这里留的是**上一版**。 */
  private final Map<Path, Cached> cache = new LinkedHashMap<>();

  private SkillLibrary(Path seedDir, Path storeDir) {
    this.seedDir = seedDir;
    this.storeDir = storeDir;
  }

  /** 生产装配：仓库种子 {@link #DEFAULT_SEED_DIR} + store 覆盖 {@code <storeDir>/skills}。 */
  public static SkillLibrary open(Path storeDir) {
    Objects.requireNonNull(storeDir, "storeDir");
    return open(DEFAULT_SEED_DIR, storeDir.resolve(STORE_DIR_NAME));
  }

  /**
   * 两个来源可注入。★ **公开**是给测试与嵌入式用的（生产走 {@link #open(Path)}）：用例要能把两个目录都指向临时目录， 否则它会读到**仓库里真实的** {@code
   * config/skills}（CWD 相对）——那让用例依赖"跑测试时的工作目录"，是最脆的一种夹具。
   */
  public static SkillLibrary open(Path seedDir, Path storeDir) {
    return new SkillLibrary(seedDir, storeDir);
  }

  /** store 侧目录（诊断用；**不创建**——没有它不影响读种子）。 */
  public Path storeDir() {
    return storeDir;
  }

  /**
   * 全部技能的**摘要**（按 id 升序，可复现）。给模型看的目录就是这个：只有 id/标题/版本，正文靠 {@link #find} 再取
   * ——一次把全部正文塞进上下文是最贵的做法，而模型往往只需要其中一两篇。
   */
  public List<SkillSummary> list() {
    List<SkillSummary> out = new ArrayList<>();
    for (Skill skill : refresh().values()) {
      out.add(new SkillSummary(skill.id(), skill.title(), skill.version()));
    }
    out.sort(Comparator.comparing(SkillSummary::id));
    return List.copyOf(out);
  }

  /** 按 id 取一篇（含正文）；没有就是空（调用方据此给出明确可读的"没有这一篇"）。 */
  public Optional<Skill> find(String id) {
    if (id == null || id.isBlank()) {
      return Optional.empty();
    }
    return Optional.ofNullable(refresh().get(id.trim()));
  }

  /** 扫两个来源、按 mtime 决定要不要重读；返回 id → 技能。 */
  private Map<String, Skill> refresh() {
    Map<String, Skill> next = new LinkedHashMap<>();
    // ★ 顺序即优先级：先种子、后 store ⇒ 同 id 的 store 版把种子版覆盖掉。
    for (Path dir : List.of(seedDir, storeDir)) {
      if (dir == null || !Files.isDirectory(dir)) {
        continue;
      }
      for (LocatedFile file : filesOf(dir)) {
        Skill skill = load(file);
        if (skill != null) {
          next.put(skill.id(), skill);
        }
      }
    }
    return next;
  }

  private static List<LocatedFile> filesOf(Path dir) {
    List<LocatedFile> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.sorted().toList()) {
        // ★ `getFileName()` 对**根路径**返回 null（SpotBugs 实测抓到的一条真缺陷）⇒ 先判空再取名字。
        //   目录里的条目本该都有文件名，但"本该"不是"一定"：拿不到名字就跳过这一条，不让它把整次列举炸掉。
        Path namePath = file.getFileName();
        if (namePath == null) {
          continue;
        }
        String name = namePath.toString();
        if (Files.isRegularFile(file) && name.endsWith(EXTENSION)) {
          out.add(new LocatedFile(file, name.substring(0, name.length() - EXTENSION.length())));
        }
      }
    } catch (IOException e) {
      // ★ 目录读不动只是"这次没读到"，不是致命：照旧返回已有的（可能为空）——不把服务拖倒。
      EventLog.channel(LOG)
          .warn(
              LogEvent.of(
                  "SKILL_DIR_READ_FAILED",
                  AppLogSource.SHELL_LIFECYCLE,
                  "dir",
                  dir,
                  "error",
                  e.getClass().getSimpleName()));
    }
    return out;
  }

  /** 一条候选文件：绝对路径 + 文件名派生的兜底 id。 */
  private record LocatedFile(Path path, String fileId) {}

  /** 读一条技能（带 mtime 缓存与"坏文件保留上一版"）：返回 null = 这条**从来没能读成功过**（没进目录）。 */
  private Skill load(LocatedFile file) {
    Path path = file.path().toAbsolutePath();
    long mtime;
    try {
      mtime = Files.getLastModifiedTime(path).toMillis();
    } catch (IOException e) {
      EventLog.channel(LOG)
          .warn(
              LogEvent.of(
                  "SKILL_MTIME_READ_FAILED",
                  AppLogSource.SHELL_LIFECYCLE,
                  "path",
                  path,
                  "error",
                  e.getClass().getSimpleName()));
      Cached cached = cache.get(path);
      return cached == null ? null : cached.skill();
    }
    Cached cached = cache.get(path);
    if (cached != null && cached.modifiedMillis() == mtime) {
      return cached.skill();
    }
    try {
      String text = Files.readString(path, StandardCharsets.UTF_8);
      Skill skill = parse(file.fileId(), path, text, mtime);
      cache.put(path, new Cached(mtime, skill));
      return skill;
    } catch (IOException | UncheckedIOException e) {
      EventLog.channel(LOG)
          .warn(
              LogEvent.of(
                  "SKILL_FILE_READ_FAILED",
                  AppLogSource.SHELL_LIFECYCLE,
                  "path",
                  path,
                  "error",
                  e.getClass().getSimpleName()));
    } catch (IllegalArgumentException e) {
      // ★ 坏文件**不生效**：保留上一版并记一条响亮的日志（写坏了不该让整库失效，更不该静默改语义）。
      EventLog.channel(LOG)
          .warn(
              LogEvent.of(
                  "SKILL_FILE_PARSE_FAILED",
                  AppLogSource.SHELL_LIFECYCLE,
                  "path",
                  path,
                  "reason",
                  logReason(e.getMessage())));
    }
    return cached == null ? null : cached.skill();
  }

  /** 缓存项：mtime + 那一版技能。 */
  private record Cached(long modifiedMillis, Skill skill) {}

  /**
   * 解析一条技能（**纯函数**，供单测直调）：可选 JSON 头 + Markdown 正文。
   *
   * @param fileId 文件名派生的兜底 id（头里没写 {@code id} 时用它）
   * @throws IllegalArgumentException 头围栏不闭合、或头不是合法 JSON（⇒ 调用方按"坏文件"处理）
   */
  static Skill parse(String fileId, Path source, String text, long modifiedMillis) {
    Objects.requireNonNull(fileId, "fileId");
    Map<String, Object> header = Map.of();
    String body = text;
    List<String> lines = List.of(text.split("\n", -1));
    int first = 0;
    while (first < lines.size() && lines.get(first).isBlank()) {
      first++;
    }
    if (first < lines.size() && FENCE.equals(lines.get(first).trim())) {
      int end = -1;
      for (int i = first + 1; i < lines.size(); i++) {
        if (FENCE.equals(lines.get(i).trim())) {
          end = i;
          break;
        }
      }
      if (end < 0) {
        throw new IllegalArgumentException("技能头没有闭合的 " + FENCE + " 围栏");
      }
      String json = String.join("\n", lines.subList(first + 1, end)).trim();
      if (!json.isEmpty()) {
        try {
          header = MAPPER.readValue(json, HEADER_TYPE);
        } catch (IOException e) {
          throw new IllegalArgumentException("技能头不是合法 JSON: " + e.getMessage(), e);
        }
      }
      body = String.join("\n", lines.subList(end + 1, lines.size()));
    }
    String id = textValue(header, "id").orElse(fileId);
    // ★ 不用 lambda 兜底：`body` 在上面被重新赋值过（不是 effectively final）——写成顺序的三段更直白。
    Optional<String> headerTitle = textValue(header, "title");
    String title = headerTitle.isPresent() ? headerTitle.get() : firstHeading(body).orElse(id);
    int version = intValue(header, "version").orElse(1);
    return new Skill(id, title, version, body.strip(), source, modifiedMillis);
  }

  private static Optional<String> textValue(Map<String, Object> header, String key) {
    Object value = header.get(key);
    if (value instanceof String text && !text.isBlank()) {
      return Optional.of(text.trim());
    }
    return Optional.empty();
  }

  private static Optional<Integer> intValue(Map<String, Object> header, String key) {
    Object value = header.get(key);
    if (value instanceof Number number) {
      return Optional.of(number.intValue());
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Optional.of(Integer.parseInt(text.trim()));
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    }
    return Optional.empty();
  }

  private static Optional<String> firstHeading(String body) {
    for (String line : body.split("\n", -1)) {
      Matcher matcher = TITLE_LINE.matcher(line);
      if (matcher.matches()) {
        return Optional.of(matcher.group(1));
      }
    }
    return Optional.empty();
  }

  /**
   * ★ <b>日志安全的拒绝理由</b>（照 L2 的 {@code logReason} 形态）：技能正文是外部/模型可写文本，解析失败消息可能回显原文； 日志只保留可读前缀——截到第一个
   * JSON 起始符/换行，避免把正文带进日志。截断只影响日志文本，不影响"坏文件保留上一版"的语义。
   */
  private static String logReason(String message) {
    if (message == null || message.isBlank()) {
      return "unknown";
    }
    String text = message.strip();
    int cut = text.length();
    for (char marker : new char[] {'{', '[', '\n', '\r'}) {
      int at = text.indexOf(marker);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    String reason = text.substring(0, cut).strip();
    return reason.isEmpty() ? "unknown" : reason;
  }
}
