package io.mosire.simos.map.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T0/T1 参考图工件域：真实 PNG/JPEG 解码、32MB/32Mpx 预检、内容寻址稳定 id、固定图/格网绑定、跨实例可读、视图真实像素与预算、坏 id/路径/坐标/半径清晰拒绝。
 *
 * <p>全部断言走公开的 {@link MapReferenceService}，不读私有字段；输出 PNG 用 {@link ImageIO} 真解码并数像素，不是只 assert 非空。
 */
class MapReferenceServiceTest {

  private static final int W = 200;
  private static final int H = 200;
  private static final Color SOURCE = new Color(180, 40, 60);

  @TempDir Path tempDir;

  @Test
  void pngAndJpegAreReallyDecodedAndContentAddressedStably() throws Exception {
    RecordingPublisher publisher = new RecordingPublisher();
    MapReferenceService service = service(publisher);
    byte[] png = png(W, H, SOURCE);
    List<MapReferenceService.Cell> grid = grid();

    MapReferenceService.Summary first = service.publish("africa.png", png, grid);
    MapReferenceService.Summary second = service.publish("africa.png", png, reversed(grid));

    assertThat(first.referenceId()).hasSize(64).matches("[0-9a-f]{64}");
    assertThat(second.referenceId()).as("同一图 + 同一格网（换序）⇒ 同 id").isEqualTo(first.referenceId());
    assertThat(first.mediaType()).isEqualTo("image/png");
    assertThat(first.width()).isEqualTo(W);
    assertThat(first.height()).isEqualTo(H);
    assertThat(first.cellCount()).isEqualTo(3);
    assertThat(first.minQ()).isEqualTo(0);
    assertThat(first.maxQ()).isEqualTo(1);
    assertThat(first.minR()).isZero();
    assertThat(first.maxR()).isEqualTo(1);
    assertThat(service.list())
        .extracting(MapReferenceService.Summary::referenceId)
        .containsExactly(first.referenceId());

    byte[] jpeg = jpeg(64, 48);
    MapReferenceService.Summary jpg =
        service.publish("photo.jpg", jpeg, List.of(cell(0, 0, 32, 24, 10)));
    assertThat(jpg.mediaType()).isEqualTo("image/jpeg");
    assertThat(jpg.width()).isEqualTo(64);
    assertThat(jpg.height()).isEqualTo(48);
    assertThat(service.list()).hasSize(2);

    assertThat(service.publish("renamed.png", png, grid).referenceId())
        .as("文件名是 id 的一部分 ⇒ 换名不得偷换同一 id")
        .isNotEqualTo(first.referenceId());
    assertThat(service.publish("africa.png", png, List.of(cell(9, 9, 100, 100, 5))).referenceId())
        .as("格网是 id 的一部分 ⇒ 换格网不得偷换同一 id")
        .isNotEqualTo(first.referenceId());
  }

  @Test
  void viewOverviewAndLocalProduceRealPixelsWithBoundedDimensions() {
    RecordingPublisher publisher = new RecordingPublisher();
    MapReferenceService service = service(publisher);
    MapReferenceService.Summary summary = service.publish("africa.png", png(W, H, SOURCE), grid());

    MapReferenceService.View overview =
        service.view(summary.referenceId(), null, null, null, false, null);
    assertThat(overview.overview()).isTrue();
    assertThat(overview.cells()).as("概览不塞满全图 cells 文本").isEmpty();
    assertThat(overview.width()).isLessThanOrEqualTo(ReferenceViewRenderer.MAX_SIDE);
    assertThat(overview.height()).isLessThanOrEqualTo(ReferenceViewRenderer.MAX_SIDE);
    assertThat(overview.png().length).isLessThanOrEqualTo(ReferenceViewRenderer.MAX_PNG_BYTES);
    assertThat(sourcePixelCount(overview.png(), SOURCE)).as("概览 PNG 必须真的含原图像素").isGreaterThan(100);
    assertThat(overview.assetId()).hasSize(64);

    MapReferenceService.View local = service.view(summary.referenceId(), 0, 0, 1, false, null);
    assertThat(local.overview()).isFalse();
    assertThat(local.radius()).isEqualTo(1);
    assertThat(local.cells()).hasSize(3);
    assertThat(local.cells())
        .extracting(MapReferenceService.CellStatus::q)
        .containsExactlyInAnyOrder(0, 0, 1);
    assertThat(local.cells())
        .extracting(MapReferenceService.CellStatus::r)
        .containsExactlyInAnyOrder(0, 1, 0);
    assertThat(local.cells().get(0).built()).isFalse();
    assertThat(local.png().length).isLessThanOrEqualTo(ReferenceViewRenderer.MAX_PNG_BYTES);
    assertThat(local.width()).isLessThanOrEqualTo(ReferenceViewRenderer.MAX_SIDE);
    assertThat(sourcePixelCount(local.png(), SOURCE)).as("局部 PNG 必须真的含原图像素").isGreaterThan(100);
    assertThat(local.sourceX()).isGreaterThanOrEqualTo(0);
    assertThat(local.sourceY()).isGreaterThanOrEqualTo(0);
    assertThat(local.sourceWidth()).isPositive();
    assertThat(local.sourceHeight()).isPositive();
    assertThat(local.sourceX() + local.sourceWidth()).isLessThanOrEqualTo(W);
    assertThat(local.sourceY() + local.sourceHeight()).isLessThanOrEqualTo(H);

    MapReferenceService.View again = service.view(summary.referenceId(), 0, 0, 1, false, null);
    assertThat(again.assetId()).as("同内容重复发布 ⇒ 稳定 assetId").isEqualTo(local.assetId());
    assertThat(again.png()).isEqualTo(local.png());
  }

  @Test
  void localViewRequiresBothCoordinatesAValidRadiusAndACenterInsideTheGrid() {
    MapReferenceService service = service(new RecordingPublisher());
    MapReferenceService.Summary summary = service.publish("africa.png", png(W, H, SOURCE), grid());

    assertThatThrownBy(() -> service.view(summary.referenceId(), 0, null, 1, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同时给");
    assertThatThrownBy(() -> service.view(summary.referenceId(), null, 0, 1, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("同时给");
    for (int radius : new int[] {0, 9, -1}) {
      assertThatThrownBy(() -> service.view(summary.referenceId(), 0, 0, radius, false, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("radius");
    }
    assertThatThrownBy(() -> service.view(summary.referenceId(), 5, 5, 1, false, null))
        .as("不得默认猜中心")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("中心格不在");
  }

  @Test
  void badReferenceIdsIncludingTraversalAndUppercaseAreRejectedBeforeAnyFileRead() {
    MapReferenceService service = service(new RecordingPublisher());
    for (String bad :
        new String[] {
          null,
          "",
          "../etc/passwd",
          "..\\..\\x",
          "a".repeat(63),
          "a".repeat(65),
          "A".repeat(64),
          "g".repeat(64)
        }) {
      assertThatThrownBy(() -> service.view(bad, null, null, null, false, null))
          .as("bad id: %s", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("id 非法");
    }
    assertThatThrownBy(() -> service.view("f".repeat(64), null, null, null, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在");
  }

  @Test
  void publishRejectsNonImagesOversizeHeaderAndPixelBombs() throws Exception {
    MapReferenceService service = service(new RecordingPublisher());
    List<MapReferenceService.Cell> grid = List.of(cell(0, 0, 10, 10, 4));

    assertThatThrownBy(() -> service.publish("x.png", new byte[0], grid))
        .hasMessageContaining("不得为空");
    assertThatThrownBy(
            () -> service.publish("x.png", "not an image".getBytes(StandardCharsets.UTF_8), grid))
        .hasMessageContaining("PNG 或 JPEG");
    assertThatThrownBy(() -> service.publish("x.gif", gif(), grid))
        .hasMessageContaining("PNG/JPEG");
    assertThatThrownBy(() -> service.publish("x.png", new byte[33 * 1024 * 1024], grid))
        .hasMessageContaining("32MB");
    assertThatThrownBy(() -> service.publish("bomb.png", pngWithHeaderSize(6000, 6000), grid))
        .as("先用文件头预检像素，不先整图解码")
        .hasMessageContaining("3200 万像素");
  }

  @Test
  void publishRejectsBadFileNameGridAndPixelGeometry() throws Exception {
    MapReferenceService service = service(new RecordingPublisher());
    byte[] png = png(W, H, SOURCE);

    assertThatThrownBy(() -> service.publish("  ", png, grid())).hasMessageContaining("文件名不得为空");
    assertThatThrownBy(() -> service.publish("a".repeat(201), png, grid()))
        .hasMessageContaining("200");
    assertThatThrownBy(() -> service.publish("bad\nname.png", png, grid()))
        .hasMessageContaining("控制字符");
    assertThatThrownBy(() -> service.publish("x.png", png, List.of()))
        .hasMessageContaining("1..12000");
    assertThatThrownBy(
            () -> service.publish("x.png", png, Collections.nCopies(12001, cell(0, 0, 10, 10, 2))))
        .hasMessageContaining("12000");
    assertThatThrownBy(
            () ->
                service.publish(
                    "x.png", png, List.of(cell(0, 0, 10, 10, 2), cell(0, 0, 20, 20, 2))))
        .hasMessageContaining("重复坐标");
    assertThatThrownBy(() -> service.publish("x.png", png, List.of(cell(0, 0, -1, 10, 2))))
        .hasMessageContaining("格心必须落在原图内");
    assertThatThrownBy(() -> service.publish("x.png", png, List.of(cell(0, 0, W, 10, 2))))
        .hasMessageContaining("格心必须落在原图内");
    assertThatThrownBy(() -> service.publish("x.png", png, List.of(cell(0, 0, 10, H, 2))))
        .hasMessageContaining("格心必须落在原图内");
    assertThatThrownBy(() -> service.publish("x.png", png, List.of(cell(0, 0, 10, 10, W + 1))))
        .hasMessageContaining("半径超出原图尺寸");
    assertThatThrownBy(
            () -> new MapReferenceService.Cell(new io.mosire.simos.map.hex.HexCoord(0, 0), 1, 1, 0))
        .hasMessageContaining("imageRadius");
    assertThatThrownBy(
            () ->
                new MapReferenceService.Cell(
                    new io.mosire.simos.map.hex.HexCoord(0, 0), Double.NaN, 1, 1))
        .hasMessageContaining("imageX");
    assertThatThrownBy(
            () ->
                new MapReferenceService.Cell(
                    new io.mosire.simos.map.hex.HexCoord(0, 0), 1, Double.POSITIVE_INFINITY, 1))
        .hasMessageContaining("imageY");
  }

  @Test
  void aNewServiceInstanceOverTheSameDirectoryReadsTheSameImageAndBinding() throws Exception {
    Path dir = tempDir.resolve("refs");
    byte[] png = png(W, H, SOURCE);
    MapReferenceService.Summary summary =
        new MapReferenceService(dir, new RecordingPublisher()).publish("africa.png", png, grid());

    RecordingPublisher secondPublisher = new RecordingPublisher();
    MapReferenceService restarted = new MapReferenceService(dir, secondPublisher);
    assertThat(restarted.list()).containsExactly(summary);
    MapReferenceService.View view = restarted.view(summary.referenceId(), 0, 0, 1, false, null);
    assertThat(view.summary()).isEqualTo(summary);
    assertThat(view.cells()).hasSize(3);
    assertThat(view.cells().get(0).imageX()).isEqualTo(serviceCellX(0, 0, grid()));
    assertThat(view.png().length).isPositive();

    // 视图 PNG 走宿主发布窄函数；新实例没有复用旧实例的内存。
    assertThat(secondPublisher.published).hasSize(1);
  }

  @Test
  void tamperedImageBytesFailTheStoredFingerprintInsteadOfServingWrongPixels() throws Exception {
    Path dir = tempDir.resolve("refs");
    MapReferenceService service = new MapReferenceService(dir, new RecordingPublisher());
    MapReferenceService.Summary summary = service.publish("africa.png", png(W, H, SOURCE), grid());

    Files.write(dir.resolve(summary.referenceId() + ".image"), png(20, 20, Color.BLUE));

    assertThatThrownBy(() -> service.view(summary.referenceId(), null, null, null, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("指纹不一致");
  }

  @Test
  void listSkipsCorruptMetadataAndIgnoresNonReferenceFiles() throws Exception {
    Path dir = tempDir.resolve("refs");
    MapReferenceService service = new MapReferenceService(dir, new RecordingPublisher());
    MapReferenceService.Summary summary = service.publish("africa.png", png(W, H, SOURCE), grid());
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("f".repeat(64) + ".json"), "not-json");
    Files.writeString(dir.resolve("notes.txt"), "ignore me");

    assertThat(service.list()).containsExactly(summary);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private MapReferenceService service(MapReferenceService.PngPublisher publisher) {
    return new MapReferenceService(tempDir.resolve("refs"), publisher);
  }

  private static MapReferenceService.Cell cell(int q, int r, double x, double y, double radius) {
    return new MapReferenceService.Cell(new io.mosire.simos.map.hex.HexCoord(q, r), x, y, radius);
  }

  private static List<MapReferenceService.Cell> grid() {
    return List.of(cell(0, 0, 100, 100, 20), cell(1, 0, 130, 100, 20), cell(0, 1, 100, 130, 20));
  }

  private static List<MapReferenceService.Cell> reversed(List<MapReferenceService.Cell> cells) {
    List<MapReferenceService.Cell> copy = new ArrayList<>(cells);
    Collections.reverse(copy);
    return copy;
  }

  private static double serviceCellX(int q, int r, List<MapReferenceService.Cell> cells) {
    return cells.stream()
        .filter(cell -> cell.coord().q() == q && cell.coord().r() == r)
        .findFirst()
        .orElseThrow()
        .imageX();
  }

  private static byte[] png(int width, int height, Color color) {
    return encode(width, height, color, "png");
  }

  private static byte[] jpeg(int width, int height) {
    return encode(width, height, new Color(20, 120, 60), "jpeg");
  }

  private static byte[] gif() {
    return encode(16, 16, Color.RED, "gif");
  }

  private static byte[] encode(int width, int height, Color color, String format) {
    BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setColor(color);
      g.fillRect(0, 0, width, height);
    } finally {
      g.dispose();
    }
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      assertThat(ImageIO.write(image, format, out)).as("JVM 必须有 %s 编码器", format).isTrue();
      return out.toByteArray();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  /** 只改 PNG 的 IHDR 声明尺寸（并重算 CRC）⇒ 文件很小但文件头声称 6000×6000。 */
  private static byte[] pngWithHeaderSize(int width, int height) throws Exception {
    byte[] png = png(8, 8, SOURCE);
    int ihdrType = 12;
    assertThat(new String(png, ihdrType, 4, StandardCharsets.US_ASCII)).isEqualTo("IHDR");
    writeInt(png, 16, width);
    writeInt(png, 20, height);
    CRC32 crc = new CRC32();
    crc.update(png, ihdrType, 4 + 13);
    writeInt(png, 16 + 13, (int) crc.getValue());
    return png;
  }

  private static void writeInt(byte[] bytes, int offset, int value) {
    bytes[offset] = (byte) (value >>> 24);
    bytes[offset + 1] = (byte) (value >>> 16);
    bytes[offset + 2] = (byte) (value >>> 8);
    bytes[offset + 3] = (byte) value;
  }

  private static int sourcePixelCount(byte[] png, Color color) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
      assertThat(image).as("视图必须是可解码 PNG").isNotNull();
      int rgb = color.getRGB() & 0xFFFFFF;
      int count = 0;
      for (int y = 0; y < image.getHeight(); y++) {
        for (int x = 0; x < image.getWidth(); x++) {
          if ((image.getRGB(x, y) & 0xFFFFFF) == rgb) {
            count++;
          }
        }
      }
      return count;
    } catch (Exception e) {
      throw new AssertionError(e);
    }
  }

  /** 内容寻址发布窄函数的测试替身：同字节同 id，并记录每次发布。 */
  private static final class RecordingPublisher implements MapReferenceService.PngPublisher {
    private final Map<String, String> ids = new LinkedHashMap<>();
    private final List<byte[]> published = new ArrayList<>();

    @Override
    public String publish(byte[] png) {
      published.add(png.clone());
      String sha = sha256(png);
      return ids.computeIfAbsent(sha, key -> key);
    }

    private static String sha256(byte[] bytes) {
      try {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }
  }
}
