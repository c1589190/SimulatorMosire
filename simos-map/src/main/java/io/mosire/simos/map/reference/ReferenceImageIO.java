package io.mosire.simos.map.reference;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 参考图原图的真实解码与 PNG 编码（参考图服务的唯一图像 I/O 点）。
 *
 * <p>★ <b>先预检、后解码</b>：32MB 文件仍可能是 100M 像素的压缩炸弹。这里先用 {@link ImageReader#getWidth(int)} 读文件头拿到真实尺寸，超过
 * {@link #MAX_PIXELS} 或不是 PNG/JPEG 一律拒绝，**然后**才整图解码。
 *
 * <p>★ <b>只认 PNG/JPEG</b>：这是设计里写死的输入格式；WebP 等让浏览器本机用的格式不在这里兜底。
 */
final class ReferenceImageIO {

  /** 单文件字节上限（32 MiB）。 */
  static final long MAX_BYTES = 32L * 1024 * 1024;

  /** 单图像素上限（32M 像素）。 */
  static final long MAX_PIXELS = 32_000_000L;

  private ReferenceImageIO() {}

  /** 已通过预检并真实解码的原图。 */
  record Decoded(String mediaType, int width, int height, BufferedImage image) {}

  /**
   * 预检文件头并整图解码。
   *
   * @throws IllegalArgumentException 空字节 / 非 PNG-JPEG / 尺寸非法 / 超过像素上限 / 真实解码失败
   */
  static Decoded decode(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new IllegalArgumentException("参考图图像不得为空");
    }
    if (bytes.length > MAX_BYTES) {
      throw new IllegalArgumentException("参考图文件不得超过 32MB");
    }
    try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
      if (in == null) {
        throw new IllegalArgumentException("参考图图像无法读取");
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
      if (!readers.hasNext()) {
        throw new IllegalArgumentException("参考图必须是可解码的 PNG 或 JPEG 图像");
      }
      ImageReader reader = readers.next();
      try {
        String format = reader.getFormatName().toLowerCase(Locale.ROOT);
        if (!"png".equals(format) && !"jpeg".equals(format) && !"jpg".equals(format)) {
          throw new IllegalArgumentException("参考图只支持 PNG/JPEG，实际格式: " + format);
        }
        reader.setInput(in, true, true);
        int width;
        int height;
        try {
          width = reader.getWidth(0);
          height = reader.getHeight(0);
        } catch (IOException e) {
          throw new IllegalArgumentException("参考图文件头损坏，无法读取尺寸", e);
        }
        if (width <= 0 || height <= 0) {
          throw new IllegalArgumentException("参考图尺寸非法: " + width + "×" + height);
        }
        if ((long) width * (long) height > MAX_PIXELS) {
          throw new IllegalArgumentException("参考图不得超过 3200 万像素，实际 " + width + "×" + height);
        }
        BufferedImage image;
        try {
          image = reader.read(0);
        } catch (IOException e) {
          throw new IllegalArgumentException("参考图真实解码失败", e);
        }
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
          throw new IllegalArgumentException("参考图解码后为空");
        }
        if (image.getWidth() != width || image.getHeight() != height) {
          throw new IllegalArgumentException(
              "参考图解码尺寸与文件头不一致: " + image.getWidth() + "×" + image.getHeight());
        }
        String mediaType = "png".equals(format) ? "image/png" : "image/jpeg";
        return new Decoded(mediaType, width, height, image);
      } finally {
        reader.dispose();
      }
    } catch (IOException e) {
      throw new IllegalArgumentException("参考图图像无法读取", e);
    }
  }

  /** 编码 PNG；内存写入失败说明运行环境坏了，响亮抛。 */
  static byte[] encodePng(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024);
    try {
      if (!ImageIO.write(image, "png", out)) {
        throw new IllegalStateException("JVM 没有 PNG 编码器");
      }
    } catch (IOException e) {
      throw new IllegalStateException("参考图视图 PNG 编码失败", e);
    }
    return out.toByteArray();
  }
}
