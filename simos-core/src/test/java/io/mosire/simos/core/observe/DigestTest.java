package io.mosire.simos.core.observe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.tool.Digest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 参数摘要的落地契约（总纲 §8.1 / spec §7.1）：**复用** {@code AgentLibMosire} 的 {@link Digest}， **不另造一个**（裁定 45）。
 *
 * <p>★★ **为什么要给一个"别人的类"写用例**——这不是装饰，是本任务最薄的一处地基：
 *
 * <ul>
 *   <li>总纲 §8.1 明写"参数摘要 | **复用 `AgentLibMosire` 的 `Digest`**（sha256 前 16 字节）——**不记明文**"， §10.5
 *       又把出处钉到 {@code io.mosire.agentlib.tool.Digest}。
 *   <li>而**总纲 §10.5 同时记着**：该类在旧的 49 类 agentlib 构件里**整个缺席**（那是一次比"测试变红"更早的、 测试**编译**失败的事故）。⇒
 *       这条依赖**曾经真的断过**，且断法不是断言红、是编译不过。
 *   <li>⇒ 本用例就是那条依赖的守卫：**逐值**钉住它的输出形状与已知向量。谁哪天换了 agentlib 版本、 或谁"顺手"把 {@code sha256}
 *       改成不带前缀/带全长，这里当场红。
 * </ul>
 *
 * <p>★ **期望值全是当场跑出来的**（形态 5）：本机 {@code agentlib-mosire-0.1.0-SNAPSHOT.jar}（实测 118 类）上 跑探针得到 {@code
 * PREFIX="sha256:"}、{@code sha256("")} / {@code sha256("abc")} / {@code sha256("{}")}
 * 三组字面量，**不是**从别处抄来或凭印象写的。其中 {@code sha256("abc")} 的 32 位 hex 段 {@code
 * ba7816bf8f01cfea414140de5dae2223} 正是公开的 SHA-256("abc") 的**前 16 字节**—— "前 16
 * 字节"这条口径由它独立佐证（不是只看长度像）。
 */
class DigestTest {

  /** 当场实测：{@code sha256("abc")} 的 32 hex 段 = 公开 SHA-256("abc") 的前 16 字节。 */
  private static final String ABC = "sha256:ba7816bf8f01cfea414140de5dae2223";

  @Test
  void prefixIsTheLiteralSha256Colon() {
    assertThat(Digest.PREFIX).isEqualTo("sha256:");
  }

  /** ★ **已知向量逐值**：形状（前缀 + 32 hex = 39 字符）与内容一起钉住。 */
  @Test
  void knownVectorsArePinnedValueByValue() {
    assertThat(Digest.sha256("abc")).isEqualTo(ABC);
    assertThat(Digest.sha256("")).isEqualTo("sha256:e3b0c44298fc1c149afbf4c8996fb924");
    assertThat(Digest.sha256("{}")).isEqualTo("sha256:44136fa355b3678a1146ad16f7e8649e");
  }

  /**
   * **形状**：{@code sha256:} + 恰好 32 个小写 hex（= 前 16 字节）。
   *
   * <p>★ 单钉长度不算数——上面那条已知向量才钉得住内容；本条钉的是"任意输入都守这个形状"， 换个输入就换一组 hex，两条互补。
   */
  @Test
  void shapeIsPrefixPlusThirtyTwoLowercaseHex() {
    for (String input : List.of("", "abc", "{}", "{\"a\":\"b\"}", "很长的一串中文载荷")) {
      String digest = Digest.sha256(input);
      assertThat(digest)
          .as("输入 %s 的摘要形状", input)
          .startsWith(Digest.PREFIX)
          .hasSize(Digest.PREFIX.length() + 32)
          .matches("sha256:[0-9a-f]{32}");
    }
  }

  /**
   * ★ **判据的实质**：摘要**不含明文**（总纲 §8.1"不记明文"）。
   *
   * <p>若谁把 {@code sha256} 换成"原文截断"或"原文 + 摘要"，形状可能照样过（长度对不对另说）， 但明文会出现在事件表里——那正是 §8.1
   * 禁止的。故直接对**载荷明文**断言它不出现在摘要里。
   */
  @Test
  void digestDoesNotLeakThePlaintext() {
    String payload = "{\"unitId\":\"u-42\",\"topSecret\":\"不该落进事件表的东西\"}";
    assertThat(Digest.sha256(payload))
        .as("总纲 §8.1：参数摘要**不记明文**")
        .doesNotContain("u-42")
        .doesNotContain("topSecret")
        .doesNotContain("不该落进事件表的东西");
  }

  /**
   * **确定性 + 分辨力**：同输入同输出、不同输入不同输出。
   *
   * <p>★ 后半句是"摘要"这个词之所以有用的**全部**：若它对所有输入返回同一个常量，已知向量那条会红，
   * 但**"事件里存了摘要"这件事本身会变得毫无信息量**——事后审计拿它比对不出"哪两条命令的参数一样"。
   */
  @Test
  void sameInputSameDigestAndDifferentInputsDiffer() {
    assertThat(Digest.sha256("abc")).isEqualTo(Digest.sha256("abc"));
    assertThat(Digest.sha256("abc")).isNotEqualTo(Digest.sha256("abd"));
    assertThat(Digest.sha256("{}")).isNotEqualTo(Digest.sha256("{ }"));
  }

  /**
   * {@code sha256(null)} **抛**（不返回一个"空载荷的摘要"）。
   *
   * <p>★ 吞掉 null 的后果是**静默的**：{@code CommandBus.received} 会照常落一条摘要看起来正常的事件，
   * 而它对应的载荷其实**丢了**——事后审计读到的是"这命令参数是个正常的空/未知值"，不是"这里出过错"。
   */
  @Test
  void nullIsRejectedRatherThanDigested() {
    assertThatThrownBy(() -> Digest.sha256(null)).isInstanceOf(RuntimeException.class);
  }
}
