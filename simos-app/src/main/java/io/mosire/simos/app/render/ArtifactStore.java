package io.mosire.simos.app.render;

import io.mosire.agentlib.llm.ToolAsset;
import io.mosire.agentlib.llm.ToolAssetResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 工件库：渲染产出的 PNG（<b>内容寻址</b>）——既是"三面共用同一份字节"的落点，也是 AgentLib 的 {@link ToolAssetResolver} 在 simos
 * 侧的实现（工具结果/Chat 消息里的 {@code assetId} 就指这里）。
 *
 * <p>★ <b>为什么内容寻址（sha256）</b>：同一张图可能被三个消费面、多轮请求要——存一份就够；而且 id 就是这份字节的指纹， 不存在"id 还在、内容悄悄换了"的形态。
 *
 * <p>★ <b>id 必须校验形态</b>：{@code assetId} 会从模型输出与会话历史回灌（**不可信输入**）。只接受 {@code [0-9a-f]{64}} ⇒
 * 天然不含路径分隔符，杜绝 {@code ../} 越界读。
 *
 * <p>★ <b>TTL 未做</b>（如实记）：工件只增不减。落地期靠"世界 revision 一变、旧图不再被引用"自然降温；真要清理由运维脚本按 mtime
 * 扫即可——本类不做后台线程（服务器进程不该偷偷跑清理）。
 */
public final class ArtifactStore implements ToolAssetResolver {

  /** 工件 id 的合法形态（sha256 十六进制）。 */
  private static final Pattern ID = Pattern.compile("^[0-9a-f]{64}$");

  /** 本库当前只存一种媒体类型；扩展时把类型写进 id/清单，不要靠"猜文件头"。 */
  public static final String PNG_MEDIA_TYPE = "image/png";

  private final Path directory;

  /**
   * @param directory 工件目录（通常是 {@code <store>/artifacts}）；不存在会在首次写入时创建
   */
  public ArtifactStore(Path directory) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath();
  }

  /**
   * 写入一张 PNG，返回内容寻址 id。
   *
   * <p>写入是"临时文件 + 原子改名"：并发渲染同一张图时，读者要么看到完整文件、要么什么也看不到（不会读到半个 PNG）。
   *
   * @param png PNG 字节（不得为空）
   * @return 工件 id（sha256 十六进制）
   */
  public String putPng(byte[] png) {
    Objects.requireNonNull(png, "png");
    if (png.length == 0) {
      throw new IllegalArgumentException("PNG 字节不得为空");
    }
    String id = sha256(png);
    Path target = fileOf(id);
    try {
      if (Files.exists(target)) {
        return id; // 同一份字节已经在库里：不重写（内容寻址的天然去重）
      }
      Files.createDirectories(directory);
      Path temp = Files.createTempFile(directory, "put-", ".tmp");
      try {
        Files.write(temp, png);
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
        Files.deleteIfExists(temp);
        throw e;
      }
    } catch (IOException e) {
      throw new UncheckedIOException("工件写入失败: " + target, e);
    }
    return id;
  }

  @Override
  public Optional<ToolAsset> resolve(String assetId) {
    return pathOf(assetId)
        .filter(Files::isRegularFile)
        .map(
            path -> {
              try {
                return new ToolAsset(PNG_MEDIA_TYPE, Files.readAllBytes(path));
              } catch (IOException e) {
                throw new UncheckedIOException("工件读取失败: " + path, e);
              }
            });
  }

  /**
   * 工件文件路径（GUI 出图路由用）。
   *
   * @return 合法形态的 id 且文件存在时给出路径；否则空
   */
  public Optional<Path> pathOf(String assetId) {
    if (assetId == null || !ID.matcher(assetId).matches()) {
      return Optional.empty();
    }
    return Optional.of(fileOf(assetId));
  }

  /** 工件目录（测试与运维用）。 */
  public Path directory() {
    return directory;
  }

  private Path fileOf(String id) {
    return directory.resolve(id + ".png");
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      // 标准 JVM 必然带 SHA-256；缺失说明运行环境坏了，响亮抛
      throw new IllegalStateException("JVM 缺少 SHA-256", e);
    }
  }
}
