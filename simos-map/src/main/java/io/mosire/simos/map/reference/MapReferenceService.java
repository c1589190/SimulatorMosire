package io.mosire.simos.map.reference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 地图参考图工件服务：**不可变原图 + 固定格网绑定**，内容寻址、跨重启可读。
 *
 * <p>★ <b>不是 GameMap 的旁门</b>：参考图只属于宿主输入工件（与渲染 ArtifactStore 同类），发布参考图不碰世界、不产生 revision。所有世界写入仍走
 * Command → ChangeSet → Revision。
 *
 * <p>★ <b>固定绑定</b>：id 由「文件名 + 图像字节 sha256 + 真实尺寸/媒体类型 + 规整后的格网」共同派生；同一 id 不会对应另一张图或另一份
 * 格网。新参考图不覆盖已有地形，也不改写已有工件（同 id 直接复用）。
 *
 * <p>★ <b>不读任意路径/URL</b>：只认调用方直接给的字节；id 严格匹配 {@code [0-9a-f]{64}}，磁盘路径只由 id 派生。
 *
 * <p>★ <b>目录/发布窄函数注入</b>：目录通常取 {@code <store>/map-references}（ArtifactStore 相邻目录），PNG 发布函数由宿主给 （生产
 * = ArtifactStore::putPng）。本类不依赖 app/core。
 */
public final class MapReferenceService {

  /** 参考格网上限。 */
  public static final int MAX_CELLS = 12_000;

  /** 局部查看半径下限/上限。 */
  public static final int MIN_RADIUS = 1;

  public static final int MAX_RADIUS = 8;

  /** 局部查看缺省半径。 */
  public static final int DEFAULT_RADIUS = 4;

  /** HTTP 请求体上限（base64 放大后仍留余量）。 */
  public static final long MAX_REQUEST_BYTES = 46L * 1024 * 1024;

  private static final Pattern REFERENCE_ID = Pattern.compile("^[0-9a-f]{64}$");

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final int MAX_FILE_NAME = 200;

  private final Path directory;
  private final PngPublisher publisher;

  /** 把一张视图 PNG 发布成内容寻址 assetId（生产 = ArtifactStore::putPng）。 */
  @FunctionalInterface
  public interface PngPublisher {
    String publish(byte[] png);
  }

  /** 格网中的一格：q/r 是地图坐标，imageX/imageY/imageRadius 是它在原图里的像素几何。 */
  public record Cell(HexCoord coord, double imageX, double imageY, double imageRadius) {
    public Cell {
      Objects.requireNonNull(coord, "coord");
      requireFinite(imageX, "imageX");
      requireFinite(imageY, "imageY");
      requireFinite(imageRadius, "imageRadius");
      if (imageRadius <= 0) {
        throw new IllegalArgumentException("imageRadius 必须大于 0: " + imageRadius);
      }
    }

    private static void requireFinite(double value, String field) {
      if (!Double.isFinite(value)) {
        throw new IllegalArgumentException(field + " 必须是有限数");
      }
    }
  }

  /** 参考工件摘要（列表/结果用；不含原图字节）。 */
  public record Summary(
      String referenceId,
      String fileName,
      String mediaType,
      int width,
      int height,
      long byteSize,
      int cellCount,
      int minQ,
      int maxQ,
      int minR,
      int maxR) {}

  /** 局部格状态（文本可定位坐标；地形/高度只在当前地图已建该格时给出）。 */
  public record CellStatus(
      int q,
      int r,
      double imageX,
      double imageY,
      double imageRadius,
      boolean built,
      String terrain,
      Double height) {}

  /** 一次参考图查看的结果：PNG + assetId + 原图裁切矩形（前端点图换算用）。 */
  public record View(
      Summary summary,
      boolean overview,
      int radius,
      List<CellStatus> cells,
      byte[] png,
      String assetId,
      int width,
      int height,
      double sourceX,
      double sourceY,
      double sourceWidth,
      double sourceHeight) {

    public View {
      Objects.requireNonNull(summary, "summary");
      Objects.requireNonNull(cells, "cells");
      Objects.requireNonNull(png, "png");
      Objects.requireNonNull(assetId, "assetId");
      cells = List.copyOf(cells);
      png = png.clone();
    }

    /** 防御性拷贝：调用方（HTTP 服务）要 base64 时不能拿到内部字节数组。 */
    @Override
    public byte[] png() {
      return png.clone();
    }
  }

  public MapReferenceService(Path directory, PngPublisher publisher) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  /**
   * 发布一张参考图。已存在同 id 工件 ⇒ 原样复用，**不覆盖**。
   *
   * @throws IllegalArgumentException 文件名/图像/格网任一非法
   */
  public Summary publish(String fileName, byte[] imageBytes, List<Cell> cells) {
    String name = requireFileName(fileName);
    if (imageBytes == null || imageBytes.length == 0) {
      throw new IllegalArgumentException("参考图图像不得为空");
    }
    ReferenceImageIO.Decoded decoded = ReferenceImageIO.decode(imageBytes);
    List<Cell> grid = canonicalCells(cells, decoded.width(), decoded.height());
    String imageSha = sha256(imageBytes);
    String referenceId = referenceId(name, decoded, imageSha, grid);
    Summary summary = summaryOf(referenceId, name, decoded, imageBytes.length, grid);
    Path metadata = metadataPath(referenceId);
    Path image = imagePath(referenceId);
    if (Files.isRegularFile(metadata) && Files.isRegularFile(image)) {
      return readSummary(referenceId);
    }
    writeAtomic(image, imageBytes);
    writeAtomic(
        metadata,
        metadataJson(summary, imageSha, grid).toString().getBytes(StandardCharsets.UTF_8));
    logInfo(
        "MAP_REFERENCE_PUBLISHED",
        "referenceId",
        referenceId,
        "cells",
        grid.size(),
        "width",
        decoded.width(),
        "height",
        decoded.height(),
        "bytes",
        imageBytes.length);
    return summary;
  }

  /** 全部参考工件（按 id 排序；坏元数据跳过并记 DEBUG，不拖垮列表）。 */
  public List<Summary> list() {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<Summary> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(directory)) {
      files
          .filter(
              path -> {
                String name = fileNameOf(path);
                return name != null && name.endsWith(".json");
              })
          .sorted()
          .forEach(
              path -> {
                String name = fileNameOf(path);
                if (name == null) {
                  return; // 坏工件：没有可读文件名，跳过（不拖垮列表）。
                }
                String id = name.substring(0, name.length() - ".json".length());
                if (!REFERENCE_ID.matcher(id).matches()) {
                  return;
                }
                try {
                  out.add(readSummary(id));
                } catch (RuntimeException e) {
                  logDebug(
                      "MAP_REFERENCE_LIST_SKIPPED", "referenceId", id, "reason", shortReason(e));
                }
              });
    } catch (IOException e) {
      throw new UncheckedIOException("参考图列表读取失败: " + directory, e);
    }
    out.sort(Comparator.comparing(Summary::referenceId));
    logDebug("MAP_REFERENCE_LISTED", "references", out.size());
    return List.copyOf(out);
  }

  /**
   * 查看概览或局部。
   *
   * @param q 中心 q；与 r 同时缺省 ⇒ 概览
   * @param r 中心 r
   * @param radius 局部半径 1..8；缺省 {@value #DEFAULT_RADIUS}
   * @param terrainOverlay 是否叠当前地形
   * @param map 当前地图（可为 null：不叠地形）；只读
   */
  public View view(
      String referenceId,
      Integer q,
      Integer r,
      Integer radius,
      boolean terrainOverlay,
      GameMap map) {
    Stored stored = load(referenceId);
    ReferenceImageIO.Decoded decoded = ReferenceImageIO.decode(stored.imageBytes());
    boolean overview = q == null && r == null;
    int resolvedRadius = radius == null ? DEFAULT_RADIUS : radius;
    HexCoord center = null;
    List<Cell> visible;
    Map<HexCoord, Cell> byCoord = new LinkedHashMap<>();
    for (Cell cell : stored.cells()) {
      byCoord.put(cell.coord(), cell);
    }
    if (overview) {
      visible = stored.cells();
    } else {
      if (q == null || r == null) {
        throw new IllegalArgumentException("q 与 r 必须同时给；都不给才是概览");
      }
      if (resolvedRadius < MIN_RADIUS || resolvedRadius > MAX_RADIUS) {
        throw new IllegalArgumentException(
            "radius 必须在 " + MIN_RADIUS + ".." + MAX_RADIUS + ": " + resolvedRadius);
      }
      center = new HexCoord(q, r);
      if (!byCoord.containsKey(center)) {
        throw new IllegalArgumentException("中心格不在该参考图格网内: " + center);
      }
      long radiusValue = resolvedRadius;
      List<Cell> within = new ArrayList<>();
      for (Cell cell : stored.cells()) {
        if (distance(cell.coord(), center) <= radiusValue) {
          within.add(cell);
        }
      }
      within.sort(Comparator.comparing(Cell::coord));
      visible = List.copyOf(within);
    }
    // ★ 概览只出图 + 范围，不把全图几千格塞进文本；局部最多 217 格。
    List<CellStatus> statuses = overview ? List.of() : statuses(visible, map);
    String caption =
        "参考 "
            + stored.summary().referenceId().substring(0, 10)
            + " · 原图 "
            + stored.summary().width()
            + "×"
            + stored.summary().height()
            + (overview ? " · 概览" : " · 中心 " + center + " 半径 " + resolvedRadius);
    ReferenceViewRenderer.Rendered rendered =
        ReferenceViewRenderer.render(
            decoded.image(), visible, overview, resolvedRadius, map, terrainOverlay, caption);
    String assetId = publisher.publish(rendered.png());
    logInfo(
        "MAP_REFERENCE_VIEWED",
        "referenceId",
        stored.summary().referenceId(),
        "overview",
        overview,
        "radius",
        overview ? "-" : resolvedRadius,
        "cells",
        visible.size(),
        "pngBytes",
        rendered.png().length);
    return new View(
        stored.summary(),
        overview,
        resolvedRadius,
        statuses,
        rendered.png(),
        assetId,
        rendered.width(),
        rendered.height(),
        rendered.sourceX(),
        rendered.sourceY(),
        rendered.sourceWidth(),
        rendered.sourceHeight());
  }

  // ── 持久化 ─────────────────────────────────────────────────────────────

  private record Stored(Summary summary, String imageSha256, List<Cell> cells, byte[] imageBytes) {}

  private Stored load(String referenceId) {
    // 保留存在性/元数据读取校验；后续 parseMetadata 才是实际取用的那一份。
    readSummary(referenceId);
    Path metadata = metadataPath(referenceId);
    Path image = imagePath(referenceId);
    Stored stored = parseMetadata(referenceId, metadata);
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(image);
    } catch (IOException e) {
      throw new IllegalArgumentException("参考图图像文件读取失败: " + referenceId, e);
    }
    if (!sha256(bytes).equals(stored.imageSha256())) {
      throw new IllegalArgumentException("参考图图像内容与元数据指纹不一致: " + referenceId);
    }
    return new Stored(stored.summary(), stored.imageSha256(), stored.cells(), bytes);
  }

  private Summary readSummary(String referenceId) {
    requireReferenceId(referenceId);
    Path metadata = metadataPath(referenceId);
    if (!Files.isRegularFile(metadata)) {
      throw new IllegalArgumentException("参考图不存在: " + referenceId);
    }
    return parseMetadata(referenceId, metadata).summary();
  }

  private Stored parseMetadata(String referenceId, Path metadata) {
    JsonNode root;
    try {
      root = MAPPER.readTree(Files.readString(metadata, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new IllegalArgumentException("参考图元数据读取失败: " + referenceId, e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("参考图元数据不是 JSON 对象: " + referenceId);
    }
    String id = text(root, "referenceId");
    if (!referenceId.equals(id)) {
      throw new IllegalArgumentException("参考图元数据 id 与文件名不一致: " + referenceId);
    }
    String fileName = requireFileName(text(root, "fileName"));
    String mediaType = text(root, "mediaType");
    if (!"image/png".equals(mediaType) && !"image/jpeg".equals(mediaType)) {
      throw new IllegalArgumentException("参考图元数据媒体类型非法: " + mediaType);
    }
    int width = integer(root, "width");
    int height = integer(root, "height");
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("参考图元数据尺寸非法: " + width + "×" + height);
    }
    String imageSha = text(root, "imageSha256");
    if (!REFERENCE_ID.matcher(imageSha).matches()) {
      throw new IllegalArgumentException("参考图元数据图像指纹非法");
    }
    List<Cell> cells = grid(root.get("grid"), width, height);
    long byteSize;
    try {
      byteSize = Files.size(imagePath(referenceId));
    } catch (IOException e) {
      throw new IllegalArgumentException("参考图图像文件不存在: " + referenceId, e);
    }
    Summary summary =
        new Summary(
            referenceId,
            fileName,
            mediaType,
            width,
            height,
            byteSize,
            cells.size(),
            cells.stream().mapToInt(cell -> cell.coord().q()).min().orElseThrow(),
            cells.stream().mapToInt(cell -> cell.coord().q()).max().orElseThrow(),
            cells.stream().mapToInt(cell -> cell.coord().r()).min().orElseThrow(),
            cells.stream().mapToInt(cell -> cell.coord().r()).max().orElseThrow());
    return new Stored(summary, imageSha, cells, new byte[0]);
  }

  /** 集中取文件名并判空：`Path.getFileName()` 在根路径等情况下可为 null。 */
  private static String fileNameOf(Path path) {
    Path name = path.getFileName();
    return name == null ? null : name.toString();
  }

  private static List<Cell> grid(JsonNode node, int width, int height) {
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException("参考图元数据 grid 必须是数组");
    }
    List<Cell> raw = new ArrayList<>();
    for (JsonNode element : node) {
      if (!element.isObject()) {
        throw new IllegalArgumentException("参考图 grid 元素必须是对象");
      }
      int q = integer(element, "q");
      int r = integer(element, "r");
      JsonNode x = element.get("imageX");
      JsonNode y = element.get("imageY");
      JsonNode radius = element.get("imageRadius");
      if (x == null
          || !x.isNumber()
          || y == null
          || !y.isNumber()
          || radius == null
          || !radius.isNumber()) {
        throw new IllegalArgumentException("参考图 grid 必须有数值 imageX/imageY/imageRadius");
      }
      raw.add(new Cell(new HexCoord(q, r), x.doubleValue(), y.doubleValue(), radius.doubleValue()));
    }
    return canonicalCells(raw, width, height);
  }

  private static List<Cell> canonicalCells(List<Cell> cells, int width, int height) {
    if (cells == null || cells.isEmpty()) {
      throw new IllegalArgumentException("参考图格网必须包含 1.." + MAX_CELLS + " 格");
    }
    if (cells.size() > MAX_CELLS) {
      throw new IllegalArgumentException("参考图格网不得超过 " + MAX_CELLS + " 格");
    }
    List<Cell> ordered = new ArrayList<>(cells.size());
    Set<HexCoord> seen = new HashSet<>();
    for (Cell cell : cells) {
      if (cell == null || cell.coord() == null) {
        throw new IllegalArgumentException("参考图格网不得含空格");
      }
      if (!seen.add(cell.coord())) {
        throw new IllegalArgumentException("参考图格网含重复坐标: " + cell.coord());
      }
      if (cell.imageX() < 0
          || cell.imageX() >= width
          || cell.imageY() < 0
          || cell.imageY() >= height) {
        throw new IllegalArgumentException(
            "参考图格心必须落在原图内: " + cell.coord() + " → (" + cell.imageX() + "," + cell.imageY() + ")");
      }
      if (cell.imageRadius() > Math.max(width, height)) {
        throw new IllegalArgumentException("参考图格半径超出原图尺寸: " + cell.imageRadius());
      }
      ordered.add(cell);
    }
    ordered.sort(Comparator.comparing(Cell::coord));
    return List.copyOf(ordered);
  }

  private ObjectNode metadataJson(Summary summary, String imageSha, List<Cell> cells) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("referenceId", summary.referenceId());
    root.put("fileName", summary.fileName());
    root.put("mediaType", summary.mediaType());
    root.put("width", summary.width());
    root.put("height", summary.height());
    root.put("byteSize", summary.byteSize());
    root.put("imageSha256", imageSha);
    ArrayNode grid = root.putArray("grid");
    for (Cell cell : cells) {
      ObjectNode item = grid.addObject();
      item.put("q", cell.coord().q());
      item.put("r", cell.coord().r());
      item.put("imageX", cell.imageX());
      item.put("imageY", cell.imageY());
      item.put("imageRadius", cell.imageRadius());
    }
    return root;
  }

  private void writeAtomic(Path target, byte[] bytes) {
    try {
      Files.createDirectories(directory);
      if (Files.exists(target)) {
        return;
      }
      Path temp = Files.createTempFile(directory, "ref-", ".tmp");
      try {
        Files.write(temp, bytes);
        try {
          Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException e) {
          // 并发发布同一内容：目标已是同一份字节，删临时文件即可。
          Files.deleteIfExists(temp);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
      } catch (IOException e) {
        Files.deleteIfExists(temp);
        throw e;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("参考图工件写入失败: " + target, e);
    }
  }

  // ── 身份与摘要 ─────────────────────────────────────────────────────────

  private static Summary summaryOf(
      String referenceId,
      String fileName,
      ReferenceImageIO.Decoded decoded,
      long byteSize,
      List<Cell> cells) {
    return new Summary(
        referenceId,
        fileName,
        decoded.mediaType(),
        decoded.width(),
        decoded.height(),
        byteSize,
        cells.size(),
        cells.stream().mapToInt(cell -> cell.coord().q()).min().orElseThrow(),
        cells.stream().mapToInt(cell -> cell.coord().q()).max().orElseThrow(),
        cells.stream().mapToInt(cell -> cell.coord().r()).min().orElseThrow(),
        cells.stream().mapToInt(cell -> cell.coord().r()).max().orElseThrow());
  }

  private static String referenceId(
      String fileName, ReferenceImageIO.Decoded decoded, String imageSha, List<Cell> cells) {
    StringBuilder canonical = new StringBuilder(256);
    canonical.append("simos-map-reference-v1\n");
    canonical.append("fileName=").append(fileName).append('\n');
    canonical.append("mediaType=").append(decoded.mediaType()).append('\n');
    canonical.append("width=").append(decoded.width()).append('\n');
    canonical.append("height=").append(decoded.height()).append('\n');
    canonical.append("imageSha256=").append(imageSha).append('\n');
    for (Cell cell : cells) {
      canonical
          .append(cell.coord().q())
          .append(',')
          .append(cell.coord().r())
          .append(',')
          .append(Double.toString(cell.imageX()))
          .append(',')
          .append(Double.toString(cell.imageY()))
          .append(',')
          .append(Double.toString(cell.imageRadius()))
          .append('\n');
    }
    return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static String requireFileName(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("参考图文件名不得为空");
    }
    String name = value.strip();
    if (name.length() > MAX_FILE_NAME) {
      throw new IllegalArgumentException("参考图文件名不得超过 " + MAX_FILE_NAME + " 字符");
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isISOControl(c)) {
        throw new IllegalArgumentException("参考图文件名不得含控制字符");
      }
    }
    return name;
  }

  private static void requireReferenceId(String referenceId) {
    if (referenceId == null || !REFERENCE_ID.matcher(referenceId).matches()) {
      throw new IllegalArgumentException("参考图 id 非法（必须是 sha256 十六进制）");
    }
  }

  private static String text(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("参考图元数据字段 " + field + " 必须是非空字符串");
    }
    return value.asText();
  }

  private static int integer(JsonNode root, String field) {
    JsonNode value = root.get(field);
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
      throw new IllegalArgumentException("参考图元数据字段 " + field + " 必须是整数");
    }
    return value.intValue();
  }

  private Path metadataPath(String referenceId) {
    requireReferenceId(referenceId);
    return directory.resolve(referenceId + ".json");
  }

  private Path imagePath(String referenceId) {
    requireReferenceId(referenceId);
    return directory.resolve(referenceId + ".image");
  }

  private static long distance(HexCoord a, HexCoord b) {
    long dq = (long) a.q() - b.q();
    long dr = (long) a.r() - b.r();
    long ds = -dq - dr;
    return (Math.abs(dq) + Math.abs(dr) + Math.abs(ds)) / 2;
  }

  private static List<CellStatus> statuses(List<Cell> visible, GameMap map) {
    List<CellStatus> out = new ArrayList<>(visible.size());
    for (Cell cell : visible) {
      boolean built = map != null && map.hexes().containsKey(cell.coord());
      String terrain = null;
      Double height = null;
      if (built) {
        terrain = map.terrainAt(cell.coord());
        height = map.hexes().get(cell.coord()).height();
      }
      out.add(
          new CellStatus(
              cell.coord().q(),
              cell.coord().r(),
              cell.imageX(),
              cell.imageY(),
              cell.imageRadius(),
              built,
              terrain,
              height));
    }
    return List.copyOf(out);
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("JVM 缺少 SHA-256", e);
    }
  }

  private static String shortReason(RuntimeException e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    int cut = message.length();
    for (char marker : new char[] {'{', '[', '\n', '\r'}) {
      int at = message.indexOf(marker);
      if (at >= 0 && at < cut) {
        cut = at;
      }
    }
    String reason = message.substring(0, cut).strip();
    return reason.isEmpty() ? e.getClass().getSimpleName() : reason;
  }

  private static void logInfo(String event, Object... keyValues) {
    EventLog.channel(MapLog.edit()).info(LogEvent.of(event, MapLogSource.MAP_REFERENCE, keyValues));
  }

  private static void logDebug(String event, Object... keyValues) {
    if (!MapLog.edit().isDebugEnabled()) {
      return;
    }
    EventLog.channel(MapLog.edit())
        .debug(LogEvent.of(event, MapLogSource.MAP_REFERENCE, keyValues));
  }
}
