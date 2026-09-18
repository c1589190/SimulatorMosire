package io.mosire.simos.core.store;

import io.mosire.simos.util.state.StateRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * checkpoint 的文件读写（spec §3.5）：{@code <storeDir>/checkpoints/<branch>/<revision>.json} 下放该坐标的**完整信封
 * JSON**（§6.3）。 内容对 Core 是**不透明文本**——本类不 parse、不校验信封结构，与 {@link Envelope} 的 C26 立场一致：Core 只搬运模块载荷。
 *
 * <p>★ 写时机（C24）：{@link #write} 由 Task 13 的装配层在**事务提交之后**调用，checkpoint **绝不进事务**——它是纯派生物， {@code
 * revisions} 表才是唯一真相（C18），写它成败不影响库的状态。写失败（I/O）以 {@link UncheckedIOException} 上抛，由**调用方**按 spec §3.4
 * ④ 处置： 记 WARNING、不失败——分岔等命令不能因 checkpoint 写不出而回滚。
 *
 * <p>★ 读语义（C18）：checkpoint 是纯优化，文件**缺失不导致失败**——{@link #read} 对缺失返回 {@link Optional#empty()} 并记
 * WARNING， 重放方回退到更早的 checkpoint、最坏从创世重放。文件在但读不出（I/O 错误）**不属于"缺失"**，以 {@link UncheckedIOException}
 * 上抛——静默吞掉会把真实的存储故障伪装成"没做过 checkpoint"。
 *
 * <p>★ R17：分支名做**文件名安全校验**（禁空、{@code '/'}、{@code '\'}、{@code ".."}），构造路径时抛 {@link
 * IllegalArgumentException}。 {@code BranchId} 的构造已禁空白（util 那一层管的是字符集纪律），本类补的是**路径字符**这一层——不校验就会写穿
 * {@code checkpoints/} 目录（{@code "../evil"} 经 {@link Path#resolve} 落到 {@code <storeDir>/evil}）。
 * {@code revision} 是 long，天然路径安全。
 */
public final class CheckpointStore {

  private static final Logger LOG = LoggerFactory.getLogger(CheckpointStore.class);

  private static final String CHECKPOINTS_DIR_NAME = "checkpoints";

  private final Path checkpointsDir;

  /**
   * @param storeDir 存储根目录（Task 13 由 {@code CoreConfig.storeDir} 给出），**必须已存在且是目录**
   * @throws NullPointerException {@code storeDir} 为 null
   * @throws IllegalArgumentException {@code storeDir} 不存在或不是目录——本类只读写 {@code checkpoints/}
   *     子树，不替调用方建根
   */
  public CheckpointStore(Path storeDir) {
    Objects.requireNonNull(storeDir, "storeDir");
    if (!Files.isDirectory(storeDir)) {
      throw new IllegalArgumentException("storeDir 不存在或不是目录: " + storeDir);
    }
    this.checkpointsDir = storeDir.resolve(CHECKPOINTS_DIR_NAME);
  }

  /**
   * 写一份 checkpoint（spec §3.5）：信封 JSON **原样落盘**，同路径已有时覆盖（内容派生自 append-only 的 revision，正常不会发生）。
   *
   * <p>调用契约见类注（C24）：只在事务提交之后调用；I/O 失败上抛 {@link UncheckedIOException}，处置（记 WARNING、不失败）归调用方。
   *
   * @param ref checkpoint 的坐标（分支名过 R17 校验）
   * @param envelopeJson spec §6.3 的信封 JSON 文本，原样写入
   * @throws IllegalArgumentException 分支名不是文件名安全的（R17）
   * @throws UncheckedIOException 目录创建或文件写入失败
   */
  public void write(StateRef ref, String envelopeJson) {
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(envelopeJson, "envelopeJson");
    branchDir(ref); // ★ 变异体 r3：校验照做，但不落盘
  }

  /**
   * 读一份 checkpoint：文件缺失 ⇒ {@link Optional#empty()} + WARNING（C18，**不抛异常**）；在 ⇒ 信封 JSON 原样返回。
   *
   * @throws IllegalArgumentException 分支名不是文件名安全的（R17）
   * @throws UncheckedIOException 文件在但读不出（I/O 错误——不是 C18 说的"缺失"）
   */
  public Optional<String> read(StateRef ref) {
    Objects.requireNonNull(ref, "ref");
    Path file = branchDir(ref).resolve(fileName(ref));
    if (!Files.isRegularFile(file)) {
      LOG.warn("checkpoint 缺失（C18：回退到更早的 checkpoint，最坏从创世重放，不失败）: {}", file);
      return Optional.empty();
    }
    try {
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("checkpoint 读取失败（文件在但读不出）: " + file, e);
    }
  }

  /**
   * 坐标 → checkpoint 目录（R17 的唯一校验点，write/read 共用）。
   *
   * <p>★ 目录是**构造**出来的，不是从文件路径 {@code getParent()} 反推的：后者是可空返回，依赖它就等于把"路径够不够深"变成 隐含约定。原先 {@code
   * write} 里正是 {@code createDirectories(file.getParent())}——SpotBugs 的 {@code
   * NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE} 指的就是它（M4 门禁实测）。改成构造之后**那条可空路径根本不存在**， 不是拿断言把它压下去。
   */
  private Path branchDir(StateRef ref) {
    String name = ref.branch().value();
    if (name.isEmpty()
        || name.indexOf('/') >= 0
        || name.indexOf('\\') >= 0
        || name.contains("..")) {
      throw new IllegalArgumentException("分支名不得用作 checkpoint 文件名（R17：禁空、'/'、'\\'、\"..\"）: " + name);
    }
    return checkpointsDir.resolve(name);
  }

  /** {@code revision} 是 long，天然路径安全（R17 尾句）。 */
  private static String fileName(StateRef ref) {
    return ref.revision().value() + ".json";
  }
}
