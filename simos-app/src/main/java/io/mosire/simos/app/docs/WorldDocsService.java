package io.mosire.simos.app.docs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.util.json.SimosObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 世界文档服务（D-036）：把“程序化地址 → Markdown 文档”落在 {@code docs/} 下。
 *
 * <p>★ 文档是**独立持久数据**：不随 world node/revision 删除；revision 只是地址的一部分。
 *
 * <p>目录形态（{@code docsRoot = docs/}）：
 *
 * <pre>
 * docs/world/
 *   entries/&lt;安全地址&gt;/meta.json
 *   entries/&lt;安全地址&gt;/current.md
 *   entries/&lt;安全地址&gt;/history/v1.md, v2.md, ...
 *   .recycle/&lt;删除日期&gt;/&lt;安全地址&gt;-v&lt;N&gt;.md
 * </pre>
 *
 * <p>★ 一个地址可对应多个版本（每次 edit 备份旧 current 到 history，再写新 current）与多个名称 （{@code
 * meta.names}）；查询可按完整/部分地址、标题、名称匹配。
 *
 * <p>★ 路径安全：地址被拆成段，非法字符替换为 {@code _}，末段追加地址 SHA-256 的前 8 位， 避免斜杠/冒号/中文/同名碰撞；拒绝 {@code ..} 与反斜杠。
 *
 * <p>★ 原子写：同目录临时文件 + {@code ATOMIC_MOVE}；同一实例内方法 {@code synchronized}。 本服务不进入 world
 * revision（文档是外部持久数据，与 AgentLib docs 同一定位）。
 */
public final class WorldDocsService {

  private static final String ENTRIES_DIR = "entries";
  private static final String RECYCLE_DIR = ".recycle";
  private static final String META_FILE = "meta.json";
  private static final String CURRENT_FILE = "current.md";
  private static final String HISTORY_DIR = "history";
  private static final int MAX_ADDRESS_LENGTH = 512;

  private final Path worldRoot;
  private final Path entriesRoot;
  private final Path recycleRoot;
  private final ObjectMapper mapper = SimosObjectMapper.create();
  private final Object lock = new Object();

  private static volatile WorldDocsService defaultService;

  public WorldDocsService(Path docsRoot) {
    Objects.requireNonNull(docsRoot, "docsRoot");
    this.worldRoot = docsRoot.toAbsolutePath().normalize().resolve("world");
    this.entriesRoot = worldRoot.resolve(ENTRIES_DIR);
    this.recycleRoot = worldRoot.resolve(RECYCLE_DIR);
  }

  /**
   * 进程默认实例：GUI 与 MCP 共用同一把锁/同一根。
   *
   * <p>根可用系统属性 {@code simos.docs.root} 或环境变量 {@code SIMOS_DOCS_ROOT} 覆盖，缺省 {@code docs}。
   */
  public static WorldDocsService defaultService() {
    WorldDocsService service = defaultService;
    if (service == null) {
      synchronized (WorldDocsService.class) {
        service = defaultService;
        if (service == null) {
          service = new WorldDocsService(defaultRoot());
          defaultService = service;
        }
      }
    }
    return service;
  }

  /** 测试/嵌入方重置默认实例（生产不调用）。 */
  public static synchronized void resetDefaultForTests(Path docsRoot) {
    defaultService = new WorldDocsService(docsRoot);
  }

  private static Path defaultRoot() {
    String property = System.getProperty("simos.docs.root");
    if (property != null && !property.isBlank()) {
      return Path.of(property.strip());
    }
    String env = System.getenv("SIMOS_DOCS_ROOT");
    if (env != null && !env.isBlank()) {
      return Path.of(env.strip());
    }
    return Path.of("docs");
  }

  /** 文档根（{@code .../docs/world}）。 */
  public Path worldRoot() {
    return worldRoot;
  }

  /**
   * 新建文档。地址已存在且未删除 ⇒ {@link WorldDocException.Code#EXISTS}。
   *
   * @param address 程序化地址
   * @param title 标题（空白 ⇒ 取地址最后一段）
   * @param content Markdown 正文（可为空串）
   * @param names 名称/别名（可为空表）
   * @return 新建后的完整视图
   */
  public WorldDocView create(String address, String title, String content, List<String> names) {
    synchronized (lock) {
      String key = requireAddress(address);
      Path dir = docDir(key);
      Optional<WorldDocMeta> existing = readMetaIfPresent(dir);
      int version = 1;
      if (existing.isPresent()) {
        if (!existing.get().deleted()) {
          throw WorldDocException.exists("文档已存在: " + key);
        }
        version = existing.get().version() + 1;
      }
      String now = Instant.now().toString();
      WorldDocMeta meta =
          new WorldDocMeta(
              key, titleOr(title, key), normalizeNames(names), version, now, now, null);
      writeContent(dir.resolve(CURRENT_FILE), content);
      writeMeta(dir, meta);
      return view(meta, dir);
    }
  }

  /** 编辑文档：先备份旧 current 到 history/vN.md，再写新 current；版本号 +1。 文档不存在 / 已删除 ⇒ NOT_FOUND。 */
  public WorldDocView edit(String address, String title, String content, List<String> names) {
    synchronized (lock) {
      String key = requireAddress(address);
      Path dir = docDir(key);
      WorldDocMeta old = readMeta(dir);
      if (old.deleted()) {
        throw WorldDocException.notFound("文档已删除（回收站）: " + key);
      }
      int oldVersion = old.version();
      Path current = dir.resolve(CURRENT_FILE);
      if (Files.isRegularFile(current)) {
        Path history = dir.resolve(HISTORY_DIR).resolve("v" + oldVersion + ".md");
        writeContent(history, readText(current));
      }
      int nextVersion = oldVersion + 1;
      String now = Instant.now().toString();
      WorldDocMeta next =
          new WorldDocMeta(
              key,
              titleOr(title, old.title()),
              names == null ? old.names() : normalizeNames(names),
              nextVersion,
              old.createdAt(),
              now,
              null);
      writeContent(current, content);
      writeMeta(dir, next);
      return view(next, dir);
    }
  }

  /**
   * 删除：把 current.md 移入 {@code .recycle/<删除日期>/}，并在 meta 标 {@code deletedAt}。
   *
   * @param address 地址
   * @param deletedDate 删除日期（空白 ⇒ 今天；建议 ISO-8601 日期）
   */
  public WorldDocMeta delete(String address, String deletedDate) {
    synchronized (lock) {
      String key = requireAddress(address);
      Path dir = docDir(key);
      WorldDocMeta old = readMeta(dir);
      if (old.deleted()) {
        return old;
      }
      String date =
          deletedDate == null || deletedDate.isBlank()
              ? LocalDate.now().toString()
              : deletedDate.strip();
      Path recycleDir = recycleRoot.resolve(safeSegment(date));
      Path current = dir.resolve(CURRENT_FILE);
      if (Files.isRegularFile(current)) {
        writeContent(
            recycleDir.resolve(dir.getFileName() + "-v" + old.version() + ".md"),
            readText(current));
        try {
          Files.delete(current);
        } catch (IOException e) {
          throw new IllegalStateException("删除 current.md 失败: " + current, e);
        }
      }
      WorldDocMeta deleted =
          new WorldDocMeta(
              old.address(),
              old.title(),
              old.names(),
              old.version(),
              old.createdAt(),
              Instant.now().toString(),
              date);
      writeMeta(dir, deleted);
      return deleted;
    }
  }

  /** 读取当前文档；已删除的文档仍可读元数据，但正文为空，版本清单保留历史。 */
  public WorldDocView get(String address) {
    synchronized (lock) {
      String key = requireAddress(address);
      Path dir = docDir(key);
      WorldDocMeta meta = readMeta(dir);
      String content =
          meta.deleted() || !Files.isRegularFile(dir.resolve(CURRENT_FILE))
              ? ""
              : readText(dir.resolve(CURRENT_FILE));
      return new WorldDocView(meta, content, versions(meta, dir));
    }
  }

  /** 版本清单（升序）。 */
  public List<WorldDocVersion> versions(String address) {
    synchronized (lock) {
      String key = requireAddress(address);
      Path dir = docDir(key);
      return versions(readMeta(dir), dir);
    }
  }

  /** 读某个历史版本正文；不存在的版本 ⇒ NOT_FOUND。 */
  public String contentAt(String address, int version) {
    synchronized (lock) {
      String key = requireAddress(address);
      Path dir = docDir(key);
      WorldDocMeta meta = readMeta(dir);
      if (version == meta.version() && Files.isRegularFile(dir.resolve(CURRENT_FILE))) {
        return readText(dir.resolve(CURRENT_FILE));
      }
      Path history = dir.resolve(HISTORY_DIR).resolve("v" + version + ".md");
      if (!Files.isRegularFile(history)) {
        throw WorldDocException.notFound("文档版本不存在: " + key + " v" + version);
      }
      return readText(history);
    }
  }

  /**
   * 查询：空白 query ⇒ 全部（按 updatedAt 降序）；否则按地址/标题/名称做大小写不敏感包含匹配。
   *
   * @param query 查询文本（空白 = 列出全部）
   * @param includeDeleted 是否包含已删除（回收站）条目
   */
  public List<WorldDocMeta> search(String query, boolean includeDeleted) {
    synchronized (lock) {
      String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
      List<WorldDocMeta> out = new ArrayList<>();
      for (WorldDocMeta meta : allMeta()) {
        if (!includeDeleted && meta.deleted()) {
          continue;
        }
        if (needle.isEmpty() || matches(meta, needle)) {
          out.add(meta);
        }
      }
      out.sort(
          Comparator.comparing(WorldDocMeta::updatedAt)
              .reversed()
              .thenComparing(WorldDocMeta::address));
      return List.copyOf(out);
    }
  }

  /** 列出全部文档元数据（含已删除；便于诊断/重建索引）。 */
  public List<WorldDocMeta> allMeta() {
    synchronized (lock) {
      if (!Files.isDirectory(entriesRoot)) {
        return List.of();
      }
      List<WorldDocMeta> out = new ArrayList<>();
      try (Stream<Path> files = Files.walk(entriesRoot)) {
        files
            .filter(Files::isRegularFile)
            .filter(path -> META_FILE.equals(path.getFileName().toString()))
            .forEach(
                metaFile -> {
                  try {
                    out.add(mapper.readValue(metaFile.toFile(), WorldDocMeta.class));
                  } catch (IOException e) {
                    throw new IllegalStateException("读文档元数据失败: " + metaFile, e);
                  }
                });
      } catch (IOException e) {
        throw new IllegalStateException("扫描文档目录失败: " + entriesRoot, e);
      }
      return List.copyOf(out);
    }
  }

  private static boolean matches(WorldDocMeta meta, String needle) {
    if (meta.address().toLowerCase(Locale.ROOT).contains(needle)) {
      return true;
    }
    if (meta.title().toLowerCase(Locale.ROOT).contains(needle)) {
      return true;
    }
    for (String name : meta.names()) {
      if (name.toLowerCase(Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private List<WorldDocVersion> versions(WorldDocMeta meta, Path dir) {
    List<WorldDocVersion> out = new ArrayList<>();
    Path history = dir.resolve(HISTORY_DIR);
    if (Files.isDirectory(history)) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(history, "v*.md")) {
        for (Path file : files) {
          String name = file.getFileName().toString();
          int version = parseVersion(name);
          if (version > 0) {
            out.add(new WorldDocVersion(version, HISTORY_DIR + "/" + name));
          }
        }
      } catch (IOException e) {
        throw new IllegalStateException("读文档历史失败: " + history, e);
      }
    }
    if (Files.isRegularFile(dir.resolve(CURRENT_FILE))) {
      out.add(new WorldDocVersion(meta.version(), CURRENT_FILE));
    }
    out.sort(Comparator.comparingInt(WorldDocVersion::version));
    return List.copyOf(out);
  }

  private static int parseVersion(String fileName) {
    if (fileName == null || !fileName.startsWith("v") || !fileName.endsWith(".md")) {
      return -1;
    }
    try {
      return Integer.parseInt(fileName.substring(1, fileName.length() - 3));
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  private WorldDocView view(WorldDocMeta meta, Path dir) {
    String content =
        meta.deleted() || !Files.isRegularFile(dir.resolve(CURRENT_FILE))
            ? ""
            : readText(dir.resolve(CURRENT_FILE));
    return new WorldDocView(meta, content, versions(meta, dir));
  }

  private Optional<WorldDocMeta> readMetaIfPresent(Path dir) {
    Path file = dir.resolve(META_FILE);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    return Optional.of(readMeta(dir));
  }

  private WorldDocMeta readMeta(Path dir) {
    Path file = dir.resolve(META_FILE);
    if (!Files.isRegularFile(file)) {
      throw WorldDocException.notFound("文档不存在: " + dir.getFileName());
    }
    try {
      return mapper.readValue(file.toFile(), WorldDocMeta.class);
    } catch (IOException e) {
      throw new IllegalStateException("读文档元数据失败: " + file, e);
    }
  }

  private void writeMeta(Path dir, WorldDocMeta meta) {
    try {
      Files.createDirectories(dir);
      byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(meta);
      atomicWrite(dir.resolve(META_FILE), bytes);
    } catch (IOException e) {
      throw new IllegalStateException("写文档元数据失败: " + dir.resolve(META_FILE), e);
    }
  }

  private void writeContent(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      atomicWrite(file, (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalStateException("写文档正文失败: " + file, e);
    }
  }

  private static void atomicWrite(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Path tmp = Files.createTempFile(file.getParent(), ".simos-doc-", ".tmp");
    try {
      Files.write(tmp, bytes);
      try {
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private static String readText(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读文档失败: " + file, e);
    }
  }

  private Path docDir(String address) {
    return entriesRoot.resolve(encodedAddress(address));
  }

  /** 地址 → 安全目录名：按 {@code /} 分段、逐段替换非法字符，末段追加地址 sha256 前 8 位。 */
  static String encodedAddress(String address) {
    String[] raw = address.split("/", -1);
    List<String> safe = new ArrayList<>(raw.length);
    for (String segment : raw) {
      if (segment.isEmpty()) {
        throw WorldDocException.invalid("文档地址含空段: " + address);
      }
      safe.add(safeSegment(segment));
    }
    String last = safe.get(safe.size() - 1) + "-" + shortHash(address);
    safe.set(safe.size() - 1, last);
    return String.join("/", safe);
  }

  private static String safeSegment(String segment) {
    StringBuilder out = new StringBuilder(segment.length());
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      if (c == '/'
          || c == '\\'
          || c == ':'
          || c == '*'
          || c == '?'
          || c == '"'
          || c == '<'
          || c == '>'
          || c == '|'
          || Character.isWhitespace(c)
          || Character.isISOControl(c)) {
        out.append('_');
      } else {
        out.append(c);
      }
    }
    String value = out.toString();
    while (value.startsWith("_")) {
      value = value.substring(1);
    }
    while (value.endsWith("_")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.isEmpty() || value.equals(".") || value.equals("..")) {
      value = "_";
    }
    if (value.length() > 80) {
      value = value.substring(0, 80);
    }
    return value;
  }

  private static String shortHash(String address) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 4);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺 SHA-256", e);
    }
  }

  private static String requireAddress(String address) {
    if (address == null || address.isBlank()) {
      throw WorldDocException.invalid("文档地址不得为空");
    }
    String value = address.strip();
    if (value.length() > MAX_ADDRESS_LENGTH) {
      throw WorldDocException.invalid("文档地址过长（上限 " + MAX_ADDRESS_LENGTH + "）: " + value.length());
    }
    if (value.indexOf('\\') >= 0) {
      throw WorldDocException.invalid("文档地址不得含反斜杠: " + value);
    }
    for (String segment : value.split("/", -1)) {
      if (segment.equals("..") || segment.equals(".")) {
        throw WorldDocException.invalid("文档地址不得含 '.' / '..' 段: " + value);
      }
    }
    return value;
  }

  private static String titleOr(String title, String address) {
    if (title != null && !title.isBlank()) {
      return title.strip();
    }
    int slash = address.lastIndexOf('/');
    String last = slash >= 0 ? address.substring(slash + 1) : address;
    return last.isBlank() ? address : last;
  }

  private static List<String> normalizeNames(List<String> names) {
    if (names == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>(names.size());
    for (String name : names) {
      if (name != null && !name.isBlank()) {
        out.add(name.strip());
      }
    }
    return List.copyOf(out);
  }
}
