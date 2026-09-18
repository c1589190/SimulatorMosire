package io.mosire.simos.core.store;

import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 「{@link SimulationState} → checkpoint 信封 JSON」的编码侧——{@link Replay#decodeCheckpoint} 的**反函数**。
 *
 * <p>★★ **本类存在的理由（不写它 Task 12 就落不了地，且这个缺口很隐蔽）**：写完状态推进要按 C19 写 checkpoint， 而 {@link
 * Envelope#encode} **在 main 侧从来没有生产调用点**——{@code Envelope.decode} 有（{@link Replay} 在用）， {@code
 * encode} 只有测试在调（{@code ReplayTest} / {@code EnvelopeTest} / {@code CheckpointStoreTest}）。 ⇒ **"状态
 * → 信封"这条通路在本次之前从未在生产代码里跑过**。这与裁定 38/39 是同一族： **两处各自正确的东西之间，缺一个装配点**。补它的地方就是这里——**不是** {@code
 * advance} 包 （"状态 → 信封"是 store 的事，放进 advance 是层次倒置）。
 *
 * <p>★ 装配规则与解码侧**逐条对称**，这不是巧合而是要求（否则写出来的档自己读不回）：
 *
 * <ul>
 *   <li>模块表按 namespace **字典序**（{@code TreeMap}）——解码侧 {@code decodeCheckpoint} 用 {@code
 *       LinkedHashMap} 按信封里的顺序装。两侧都用**确定序**才能让"同一状态 ⇒ 同一份字节"成立（决定论）。
 *   <li>每个模块的 JSON 由**它自己的 codec** 产出（C26：Core 不碰模块载荷的内容）。
 *   <li>{@code info} 段走 {@link SimosObjectMapper}——与解码侧同一个 mapper（它认识 {@code Address} 的 Map 键绑定， 裁定
 *       38）。
 *   <li>**信封里出现没有 codec 的模块 ⇒ 抛**（解码侧同样抛，且消息里点出 namespace）。静默丢一个模块，
 *       写出来的就是一个**残缺但看着正常**的档——重放时才发现状态丢了，那时已经隔了一次崩溃。
 * </ul>
 *
 * @see Envelope#encode
 * @see Replay#decodeCheckpoint
 */
public final class CheckpointEncoder {

  private CheckpointEncoder() {}

  /**
   * @param state 要落成 checkpoint 的状态
   * @param codecs 本实例装配的 codec 表；**必须覆盖 state 的全部 namespace**
   * @throws IllegalArgumentException 某个 namespace 没有 codec（宁可写不出，也不写一个残缺的档）
   */
  public static String encode(SimulationState state, Collection<ModuleCodec> codecs) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(codecs, "codecs");

    Map<String, ModuleCodec> table = new LinkedHashMap<>();
    for (ModuleCodec codec : codecs) {
      table.put(Objects.requireNonNull(codec, "codecs 的元素").namespace(), codec);
    }

    Map<String, String> modules = new TreeMap<>();
    for (Map.Entry<String, Snapshot> entry : state.modules().entrySet()) {
      String namespace = entry.getKey();
      ModuleCodec codec = table.get(namespace);
      if (codec == null) {
        throw new IllegalArgumentException(
            "checkpoint 信封要写的模块没有装配 codec（信封与本实例的 codec 表不同源）: " + namespace);
      }
      modules.put(namespace, codec.encodeSnapshot(entry.getValue()));
    }

    try {
      return Envelope.encode(
              state.meta(), modules, SimosObjectMapper.create().writeValueAsString(state.info()))
          .toString();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(
          "checkpoint 信封的 info 段序列化失败（info 是 util 的类型，其 JSON 装配在 SimosObjectMapper）", e);
    }
  }
}
