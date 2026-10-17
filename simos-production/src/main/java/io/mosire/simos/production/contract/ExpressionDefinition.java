package io.mosire.simos.production.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>可配置公式的定义形态</b>（D-032 约束设计书 §4.4）：<b>版本 + 显式输入声明 + 受限表达式树</b>。
 *
 * <p>★★ <b>为什么版本在定义上（而不是在代码里）</b>：设计书 §4.4 原文「<b>版本化</b>（定义携带版本，改变求值语义 即换版本）」。{@code version}
 * 是<b>求值语义</b>的版本：同一个 {@link RestrictedExpression} 树在 {@link #CURRENT_VERSION}
 * 下有一个确定答案；将来若改变白名单运算的含义（例如把除法从 floor 改成截断）， <b>必须</b>换一个新版本号，而不是原地改语义——否则旧 revision
 * 里的公式会在重放时给出不同答案（铁律 5 的 往返/回放不变量）。
 *
 * <p>★ <b>未知版本 ⇒ 契约故障</b>（{@link #evaluate}）：读到不认识的版本号说明世界档案来自不同的求值语义， 静默按现行语义算会把"读不懂"伪装成"算得出"。
 *
 * <p>★ <b>声明 ⊇ 引用</b>（构造期判）：{@link #inputs()} 必须覆盖 {@link RestrictedExpression#referencedInputs()}
 * 的全部名字。漏声明原本只在求值期才炸（且只在数据走到那条分支时）——这里把它提前成<b>构造期</b>故障， 与"配置错误不得静默变成中性加成"同一条纪律。
 *
 * <p>★ <b>保序不可变</b>：{@code inputs} 一律 {@code LinkedHashSet} 拷贝后冻结（迭代序是内容的纯函数）。
 *
 * @param version 求值语义版本；必须等于 {@link #CURRENT_VERSION}（将来可扩为"已知版本集合"）
 * @param inputs 显式声明的输入名全集（保序、去重、非空白）；必须覆盖表达式实际引用的名字
 * @param root 表达式根；不得为 null
 */
public record ExpressionDefinition(int version, List<String> inputs, RestrictedExpression root) {

  /** ★ 现行求值语义版本（改变白名单运算的含义即 +1，并在 {@link #evaluate} 里显式分支）。 */
  public static final int CURRENT_VERSION = 1;

  /**
   * ★ <b>恒等公式</b>（{@code 1000}，千分制下的 1.0 = 不做任何加成）。
   *
   * <p>它是"这个方案没有配置公式"的<b>显式</b>中性值——注意它与"公式求值失败"<b>不是</b>同一件事： 后者必须抛 {@link
   * ProductionContractFault}（I15），绝不允许退回本常量。
   */
  public static final ExpressionDefinition NEUTRAL_PER_MILLE =
      new ExpressionDefinition(CURRENT_VERSION, List.of(), new RestrictedExpression.Const(1_000L));

  public ExpressionDefinition {
    if (version != CURRENT_VERSION) {
      throw new ProductionContractFault(
          "expression-unknown-version", "本版本只认识求值语义版本 " + CURRENT_VERSION + "，收到 " + version);
    }
    if (inputs == null) {
      throw new IllegalArgumentException("ExpressionDefinition.inputs 不得为 null（无输入用空表）");
    }
    if (root == null) {
      throw new IllegalArgumentException("ExpressionDefinition.root 不得为 null");
    }
    Set<String> declared = new LinkedHashSet<>();
    for (String input : inputs) {
      if (input == null || input.isBlank()) {
        throw new IllegalArgumentException("ExpressionDefinition.inputs 不得含空白名");
      }
      if (!declared.add(input)) {
        throw new IllegalArgumentException("ExpressionDefinition.inputs 不得重复: " + input);
      }
    }
    for (String referenced : root.referencedInputs()) {
      if (!declared.contains(referenced)) {
        throw new IllegalArgumentException(
            "ExpressionDefinition 的 inputs 未声明表达式引用的输入名（显式输入是硬约束，不允许隐式读状态）: " + referenced);
      }
    }
    inputs = Collections.unmodifiableList(List.copyOf(declared)); // ★ 冻在赋值处
  }

  /**
   * ★★ <b>求值</b>：唯一入口（量纲与错误语义见 {@link RestrictedExpression}）。
   *
   * @param values 本次求值的输入值（名字 → 值）；多给的键被忽略，缺键由 {@link RestrictedExpression} 按"未提供"报契约故障
   * @throws ProductionContractFault 版本未识别 / 引用了未提供的输入 / 除零 / 溢出 / Clamp 越界
   */
  public long evaluate(Map<String, Long> values) {
    if (values == null) {
      throw new IllegalArgumentException("ExpressionDefinition.evaluate 的 values 不得为 null");
    }
    if (version != CURRENT_VERSION) {
      throw new ProductionContractFault(
          "expression-unknown-version", "本版本只认识求值语义版本 " + CURRENT_VERSION + "，收到 " + version);
    }
    Map<String, Long> resolved = new LinkedHashMap<>();
    for (String name : inputs) {
      Long value = values.get(name);
      if (value != null) {
        resolved.put(name, value);
      }
    }
    return root.evaluate(resolved);
  }

  /** ★ 只按声明序给出"本公式需要哪些输入"（供装配方核对属性/经验条是否齐备）。 */
  public List<String> declaredInputs() {
    return inputs;
  }

  /** ★ <b>单一输入名的便捷构造</b>（{@code 1 + x*mastery} 一类的最常见形态的读法辅助，不改变语义）： 只声明一个输入。 */
  public static ExpressionDefinition ofSingleInput(String name, RestrictedExpression root) {
    return new ExpressionDefinition(CURRENT_VERSION, List.of(name), root);
  }
}
