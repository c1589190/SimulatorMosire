package io.mosire.simos.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Objects;

/**
 * {@link CoreSimos} 的装配配置（计划 Task 13 的 Produces 行）。
 *
 * <p>★ **{@code mapper} 当前无消费者**（如实记，不编一个死代码消费点出来）：本项确实来自计划，但 Core 现有的四台 {@code
 * ObjectMapper}（{@code Timeline} 的 changeset 台、{@code Envelope} 的信封台、{@code CommandBus} / {@code
 * TimeAdvance} 的事件台、{@code Replay} / {@code CheckpointEncoder} 的 info 台）**各自在构造期由 {@code
 * SimosObjectMapper.create(...)} 自建**，没有任何一处收外部注入的 mapper。要给它找消费者，就得改动那些已关账的 类（Task
 * 6/8/11/12），超出本任务边界 ⇒ 保留组件、校验非空、**不假装它在被用**。将来若真需要"装配方指定 mapper"，改口 应在那一处而不是这里。
 *
 * @param storeDir 存储根目录：库文件与 {@code checkpoints/} 子树的落点（库文件名见 {@link CoreSimos#DB_FILE_NAME}）。
 *     不必预先存在——{@code SqliteStore.open} 会建它；但**必须**是目录（若已存在）
 * @param checkpointInterval C19 第①项的周期 N，必须 ≥ 1（spec §3.5：默认 100；用例取小值压边界）
 * @param mapper 见上：保留的计划组件，当前无消费者
 */
public record CoreConfig(Path storeDir, int checkpointInterval, ObjectMapper mapper) {

  public CoreConfig {
    Objects.requireNonNull(storeDir, "storeDir");
    Objects.requireNonNull(mapper, "mapper");
    if (checkpointInterval < 1) {
      throw new IllegalArgumentException(
          "checkpointInterval 必须 ≥ 1（C19 第①项的取模周期）: " + checkpointInterval);
    }
    // ★ 防御性拷贝（SpotBugs 的硬约束，非洁癖）：ObjectMapper **可变**，直接存参数会被判 EI_EXPOSE_REP2、
    //   直接由访问器返回会被判 EI_EXPOSE_REP。本组件当前无消费者，故"存自己的副本、返回副本"零语义代价。
    mapper = mapper.copy();
  }

  /** ★ 返回**副本**：{@code ObjectMapper} 可变，直接返回字段即 SpotBugs EI_EXPOSE_REP。 */
  @Override
  public ObjectMapper mapper() {
    return mapper.copy();
  }
}
