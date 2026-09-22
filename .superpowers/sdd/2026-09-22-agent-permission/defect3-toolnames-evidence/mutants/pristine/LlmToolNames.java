package io.mosire.simos.app.llm;

import io.mosire.agentlib.llm.ToolDef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * **LLM 线格式的工具名映射**（真实名 ↔ 送给模型的名字）：让 Simos 的点分层工具名能过 OpenAI 兼容端点的函数名文法。
 *
 * <p>★★ **缺陷现场**（2026-09-22 真 LLM 实测，不是推断）：Simos 的工具名形如 {@code simos.map.hex} / {@code
 * sd.IssueDirective}， 含 {@code .}；而 OpenAI 兼容端点要求函数名匹配 {@code ^[a-zA-Z0-9_-]+$}，于是**整份请求被拒**：
 *
 * <pre>
 * HTTP 400: Invalid 'tools[0].function.name': string does not match pattern.
 *            Expected a string that matches the pattern '^[a-zA-Z0-9_-]+$'.
 * </pre>
 *
 * <p>⇒ {@code sd.RunDecision} 返回 {@code {"result":"failed","llmCalls":0,...}}——**一次都没发出去**。修法是本类：
 * 送出去时把非法字符转义成 {@code _}，收回来时按同一张表**映射回真实名**再交给权限链（权限链一字不改，它仍然只认真实名）。
 *
 * <p>★ **为什么不改工具名本身**：工具名是**平台身份**——它进 catalog、进 {@code whitelist}、进 {@code ToolInvocation} 的账、进
 * GUI 的调色板，还与命令类型 {@code <namespace>.<Command>} 同名（T 系列的裁定）。为一个供应商的文法
 * 去改全平台的标识，等于把**线格式的约束**泄进领域标识里；而且换个供应商（文法不同）就得再改一次。
 *
 * <p>★ **为什么放在 {@code app.llm}**：这条约束是**供应商方言**的属性（OpenAI 兼容的函数名文法），不是"决策流程"的属性； {@code app.llm}
 * 本就是"我们怎么跟 provider 说话"的包（读路由、注密钥、定采样、观测用量）。放在 {@code decision/} 里，下一条要暴露工具给模型的路（GM agent
 * 循环、判决器接工具）就只能反向 import 一个 decision 专属包、或再抄一份——正是本仓反复 消掉的那种漂移。
 *
 * <p>★★ **双向且可判**：{@link #of} 建表时**当场**判两种碰撞，命中就抛（{@link IllegalStateException}，装配故障）：
 *
 * <ol>
 *   <li>两条真实名的转义结果相同（如 {@code a.b} 与 {@code a/b} 都成了 {@code a_b}）；
 *   <li>某条真实名的转义结果**恰是另一条真实名**（如 {@code map.SetEdge} 撞上本来就合法的 {@code map_SetEdge}）。
 * </ol>
 *
 * <p>这两种都不许"静默挑一个"：模型说 {@code a_b} 时，我们无从知道它指的是哪一条，而**猜错的代价是执行了另一个工具** （可能是写工具）。故宁可装配期起不来。
 *
 * <p>★ **回不去的名字不新造理由**：{@link #realNameOf} 查不到就返回空，调用方**原样**把模型给的名字交给权限链—— 于是"工具不存在"（{@code
 * TOOL_NOT_FOUND}，正文带着它给的名字）与"工具存在但权限不够"（{@code PERMISSION_DENIED}） 的分野**逐字仍是 AgentLib 的**，本类不插一脚（见
 * {@code DecisionAgentRunner#execute}）。
 *
 * <p>本类无状态、无 IO：结果只取决于建表时给的那组名字。
 */
public final class LlmToolNames {

  /**
   * 供应商对函数名的文法（**实测报错原文**里的那一条）：字母数字、下划线、连字符，且至少一个字符。
   *
   * <p>★ 进的是给模型看的工具名，故写成字面量并当**公开常量**：用例直接拿它断言，而不是各自抄一遍正则（抄的那份不会跟着原文变）。
   */
  public static final String PROVIDER_NAME_PATTERN = "^[a-zA-Z0-9_-]+$";

  /** 转义字符：一切不在文法里的字符都换成它。 */
  private static final char ESCAPE_CHAR = '_';

  private static final Pattern WIRE_SAFE = Pattern.compile(PROVIDER_NAME_PATTERN);

  /** 线名 → 真实名（查不到线名时用）。 */
  private final Map<String, String> wireToReal;

  /** 真实名 → 线名（{@link #wireDefs} 用；两张表一次建成，不给出"只建一半"的形态）。 */
  private final Map<String, String> realToWire;

  private LlmToolNames(Map<String, String> wireToReal, Map<String, String> realToWire) {
    this.wireToReal = wireToReal;
    this.realToWire = realToWire;
  }

  /**
   * 由**一组真实工具名**建双向表（这就是"装配期"：碰撞与非法转义都在这里当场炸）。
   *
   * <p>★ 元素**不得为 null**（工具名不得为空）：由 {@link TreeSet} 的自然序当场抛 {@code NullPointerException}—— 生产路径给不出
   * null（{@link ToolDef} 构造期就禁了），故这里不再抄一遍守卫（抄一遍只会是一行永远不响的装饰）。
   *
   * @param realNames 真实工具名（生产路径 = 交给模型的那份工具面）
   * @throws IllegalStateException 两条真实名的转义结果相同，或转义结果不合文法（都是装配故障，消息里点名每一条）
   */
  public static LlmToolNames of(Collection<String> realNames) {
    Objects.requireNonNull(realNames, "realNames");
    // 有序遍历：同一次装配故障被复现时，报错文本逐字相同（本仓的"同输入同输出"口径）。
    Map<String, String> wireToReal = new TreeMap<>();
    Map<String, String> realToWire = new TreeMap<>();
    for (String real : new TreeSet<>(realNames)) {
      String wire = wireNameOf(real);
      if (!WIRE_SAFE.matcher(wire).matches()) {
        // 转义函数自身坏掉时（比如被改成"原样返回"）在这里当场响，而不是把非法名字发出去换一个 400。
        throw new IllegalStateException(
            "工具名转义后仍不合供应商文法（装配故障）: " + real + " -> " + wire + "（要求 " + PROVIDER_NAME_PATTERN + "）");
      }
      String clash = wireToReal.putIfAbsent(wire, real);
      if (clash != null && !clash.equals(real)) {
        throw new IllegalStateException(
            "工具名转义碰撞（装配故障）——两个真实工具名落到同一个线名 "
                + wire
                + ": "
                + clash
                + " 与 "
                + real
                + "；模型说这个线名时我们无从知道它指哪一条，故不静默挑一个");
      }
      realToWire.put(real, wire);
    }
    return new LlmToolNames(Map.copyOf(wireToReal), Map.copyOf(realToWire));
  }

  /**
   * 真实名 ⇒ 线名（**唯一转义点**：把每个不在文法里的字符换成 {@link #ESCAPE_CHAR}）。
   *
   * <p>★ **为什么是"换字符"而不是"删字符"**：删掉点会得到 {@code simosmaphex}——它同样合法，却再也读不出层级， 且把两条本来分得开的真实名压成一条（{@code
   * simos.map.hex} 与 {@code simosmap.hex}）。换字符保长度、保可读、保区分度。
   *
   * <p>★ 本方法是**纯函数**且公开：身份消息这类"要跟模型说工具名"的地方也用它，于是"说的"与"发的"永远同一个写法。
   */
  public static String wireNameOf(String realName) {
    Objects.requireNonNull(realName, "realName");
    StringBuilder out = new StringBuilder(realName.length());
    for (int i = 0; i < realName.length(); i++) {
      char c = realName.charAt(i);
      out.append(isWireSafe(c) ? c : ESCAPE_CHAR);
    }
    return out.toString();
  }

  /**
   * 单个字符是否在文法里（**只看字符**；{@link #PROVIDER_NAME_PATTERN} 才是权威判据，{@link #of} 拿它复核转义结果）。
   *
   * <p>★ 两条判据（字符级 vs 正则级）**必须一致**，而它们不一致时 {@link #of} 会当场抛——那正是这道复核的用处。
   */
  private static boolean isWireSafe(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '_'
        || c == '-';
  }

  /**
   * 真实工具面 ⇒ **送给模型的那一份**：只有 {@link ToolDef#name()} 换写法，描述与 schema 逐字不动。
   *
   * <p>★ **名字换了、正文一个字节都不动**是有意的：能力面的形状（描述、参数 schema）与"我们怎么拼名字"**无关**， 在这里顺手改点别的就等于把两件事绑在一起。
   *
   * @throws IllegalStateException 某条工具不在本表里（表与工具面不同源——装配故障，绝不静默漏掉或补一个）
   */
  public List<ToolDef> wireDefs(List<ToolDef> realDefs) {
    Objects.requireNonNull(realDefs, "realDefs");
    List<ToolDef> out = new ArrayList<>(realDefs.size());
    for (ToolDef def : realDefs) {
      Objects.requireNonNull(def, "realDefs 里有 null");
      String wire = realToWire.get(def.name());
      if (wire == null) {
        throw new IllegalStateException("工具 " + def.name() + " 不在本次的名字表里（表与工具面不同源）——装配故障");
      }
      out.add(new ToolDef(wire, def.description(), def.jsonSchema()));
    }
    return List.copyOf(out);
  }

  /**
   * 线名 ⇒ 真实名；**查不到就空**（调用方据此原样交给权限链，见类注）。
   *
   * <p>★ 注意它**不认真实名**：传 {@code simos.map.hex}（真实名）进来返回空——这正是"模型用了不在表里的写法"这件事本身。
   */
  public Optional<String> realNameOf(String wireName) {
    Objects.requireNonNull(wireName, "wireName");
    return Optional.ofNullable(wireToReal.get(wireName));
  }
}
