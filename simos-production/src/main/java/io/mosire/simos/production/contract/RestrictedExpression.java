package io.mosire.simos.production.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★★ <b>受限表达式</b>（D-032 约束设计书 §4.4；控制方工程取舍，用户可推翻）：生产方式可配置公式的
 * <b>唯一形状</b>——成长/熟练度/效率加成都由它表达，不嵌入脚本运行时、不引入 {@code eval}。
 *
 * <p>★★ <b>四条冻结语义</b>（设计书 §4.4 逐条落地）：
 *
 * <ol>
 *   <li><b>显式声明输入</b>：只认 {@link Input} 里写的名字，由 {@link ExpressionDefinition#inputs()} 声明全集；
 *       <b>不允许隐式读状态</b>——表达式看不见 {@code ProductionData}、看不见 world、看不见时钟。
 *   <li><b>白名单运算与函数</b>：{@link Const} / {@link Input} / {@link Add} / {@link Sub} / {@link Mul} /
 *       {@link Div} / {@link MulDiv} / {@link Min} / {@link Max} / {@link Clamp} / {@link
 *       Piecewise} ——覆盖用户例式 {@code 1+x*a} 与 {@code 1+x1*a+x2*b}，并为非线性/分段保留表达空间。
 *   <li><b>确定性求值</b>：<b>全整数</b>（{@code long}），禁 {@code double}/{@code float}；除法是<b>向下取整的整数除法</b>
 *       且除零即故障；加减乘一律走 {@code Math.*Exact}（溢出即故障，不静默回绕）。
 *   <li><b>版本化</b>：版本号在 {@link ExpressionDefinition#version()}（定义携带版本；改变求值语义即换版本）。
 * </ol>
 *
 * <p>★★ <b>量纲口径（唯一拼写点）</b>：表达式本身<b>不带量纲</b>——它的量纲由<b>输入</b>与<b>求值上下文的约定</b>给出。 生产侧只有两条约定：
 *
 * <ul>
 *   <li><b>熟练度</b>（mastery）与<b>加成系数</b>（x）都是<b>千分数</b>（‰；{@code 1000 = 1.0}）；
 *   <li><b>效率倍率</b>（本类型最常见的结果）也是<b>千分数</b>（{@code 1000 = 不做任何加成}）⇒ 用户例式 {@code 1 + x*a} 在千分制下写成
 *       {@code Const(1000) + Mul(Input("x"), Input("mastery"))}， 其中 {@code x = 500‰（= 50%）}、{@code
 *       mastery = 800‰（= 0.8）} ⇒ 结果 {@code 1400‰}。
 * </ul>
 *
 * <p>★ <b>为什么不是"线性项列表"</b>：用户 U4/U5 明确要求公式<b>本身</b>可配置（不是只改系数），且 {@code
 * 2026-10-17-d032-production-experience-choice-design.md} §7 明文"不能把控制方给出的线性项列表
 * 当成用户只允许线性公式的裁定"——故这里给的是<b>树</b>，不是项表。
 *
 * <p>★ <b>sealed 多态</b>：裸往返不可能（同 {@code AllocationRule}/{@code FieldDelta}）⇒ 类型信息以 {@code @class}
 * 注解钉在类型上、子类集封闭，跟着类型走、不依赖某台 mapper 上的 mixin。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = RestrictedExpression.Const.class, name = "const"),
  @JsonSubTypes.Type(value = RestrictedExpression.Input.class, name = "input"),
  @JsonSubTypes.Type(value = RestrictedExpression.Add.class, name = "add"),
  @JsonSubTypes.Type(value = RestrictedExpression.Sub.class, name = "sub"),
  @JsonSubTypes.Type(value = RestrictedExpression.Mul.class, name = "mul"),
  @JsonSubTypes.Type(value = RestrictedExpression.Div.class, name = "div"),
  @JsonSubTypes.Type(value = RestrictedExpression.MulDiv.class, name = "mul_div"),
  @JsonSubTypes.Type(value = RestrictedExpression.Min.class, name = "min"),
  @JsonSubTypes.Type(value = RestrictedExpression.Max.class, name = "max"),
  @JsonSubTypes.Type(value = RestrictedExpression.Clamp.class, name = "clamp"),
  @JsonSubTypes.Type(value = RestrictedExpression.Piecewise.class, name = "piecewise"),
})
public sealed interface RestrictedExpression {

  /** 常量（{@code long}；量纲由使用处约定，见接口类注）。 */
  record Const(long value) implements RestrictedExpression {}

  /**
   * 输入引用（名字由 {@link ExpressionDefinition#inputs()} 声明；求值时缺名 ⇒ 契约故障，<b>不</b>当 0）。
   *
   * @param name 非空白输入名
   */
  record Input(String name) implements RestrictedExpression {

    public Input {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("RestrictedExpression.Input.name 不得为空白");
      }
    }
  }

  /** 加（{@code Math.addExact}；溢出 ⇒ 契约故障）。 */
  record Add(RestrictedExpression left, RestrictedExpression right)
      implements RestrictedExpression {

    public Add {
      if (left == null || right == null) {
        throw new IllegalArgumentException("RestrictedExpression.Add 的两个操作数都不得为 null");
      }
    }
  }

  /** 减（{@code Math.subtractExact}；溢出 ⇒ 契约故障）。 */
  record Sub(RestrictedExpression left, RestrictedExpression right)
      implements RestrictedExpression {

    public Sub {
      if (left == null || right == null) {
        throw new IllegalArgumentException("RestrictedExpression.Sub 的两个操作数都不得为 null");
      }
    }
  }

  /** 乘（{@code Math.multiplyExact}；溢出 ⇒ 契约故障）。 */
  record Mul(RestrictedExpression left, RestrictedExpression right)
      implements RestrictedExpression {

    public Mul {
      if (left == null || right == null) {
        throw new IllegalArgumentException("RestrictedExpression.Mul 的两个操作数都不得为 null");
      }
    }
  }

  /**
   * 整数除法，<b>向下取整</b>（{@code Math.floorDiv}；{@code divisor == 0} ⇒ 契约故障）。
   *
   * <p>★ 负数也向下取整（{@code floorDiv} 语义，不是 Java 的截断除法）——"确定求值"要的是唯一答案， 而不是"取决于符号的两种答案"。
   */
  record Div(RestrictedExpression dividend, RestrictedExpression divisor)
      implements RestrictedExpression {

    public Div {
      if (dividend == null || divisor == null) {
        throw new IllegalArgumentException("RestrictedExpression.Div 的两个操作数都不得为 null");
      }
    }
  }

  /**
   * ★★ <b>定点乘除 {@code (multiplicand × multiplier) ÷ divisor}</b>：先乘后除，<b>不丢中间精度</b>。
   *
   * <p>它是千分制公式的<b>主运算</b>：{@code 熟练度 × 加成 ÷ 1000} 若写成 {@code (a/1000)*x} 会先把熟练度 截断成
   * 0/1，整条加成静默消失。故本仓把"先乘后除"做成<b>一个显式运算</b>而不是让配置者自己拼。
   *
   * <p>★ 溢出与除零同 {@link Mul}/{@link Div}：一律契约故障。
   */
  record MulDiv(
      RestrictedExpression multiplicand,
      RestrictedExpression multiplier,
      RestrictedExpression divisor)
      implements RestrictedExpression {

    public MulDiv {
      if (multiplicand == null || multiplier == null || divisor == null) {
        throw new IllegalArgumentException("RestrictedExpression.MulDiv 的操作数都不得为 null");
      }
    }
  }

  /** 取小。 */
  record Min(RestrictedExpression left, RestrictedExpression right)
      implements RestrictedExpression {

    public Min {
      if (left == null || right == null) {
        throw new IllegalArgumentException("RestrictedExpression.Min 的两个操作数都不得为 null");
      }
    }
  }

  /** 取大。 */
  record Max(RestrictedExpression left, RestrictedExpression right)
      implements RestrictedExpression {

    public Max {
      if (left == null || right == null) {
        throw new IllegalArgumentException("RestrictedExpression.Max 的两个操作数都不得为 null");
      }
    }
  }

  /** 夹取 {@code [min, max]}（{@code min > max} ⇒ 契约故障；先夹下界再夹上界，语义唯一）。 */
  record Clamp(RestrictedExpression value, RestrictedExpression min, RestrictedExpression max)
      implements RestrictedExpression {

    public Clamp {
      if (value == null || min == null || max == null) {
        throw new IllegalArgumentException("RestrictedExpression.Clamp 的操作数都不得为 null");
      }
    }
  }

  /**
   * ★ <b>分段</b>（非线性/分段的表达空间）：逐条判 {@link Branch#predicate()}，<b>非零即真</b>，取第一条为真者的 {@link
   * Branch#value()}；都不为真 ⇒ {@link #otherwise()}。
   *
   * @param branches 有序分支表；不得为 null（可以为空表 ⇒ 直接取 {@code otherwise}）；不得含 null
   * @param otherwise 兜底值；不得为 null
   */
  record Piecewise(List<Branch> branches, RestrictedExpression otherwise)
      implements RestrictedExpression {

    public Piecewise {
      if (branches == null) {
        throw new IllegalArgumentException(
            "RestrictedExpression.Piecewise.branches 不得为 null（无条件分支用空表）");
      }
      if (otherwise == null) {
        throw new IllegalArgumentException("RestrictedExpression.Piecewise.otherwise 不得为 null");
      }
      List<Branch> copy = new ArrayList<>();
      for (Branch branch : branches) {
        if (branch == null) {
          throw new IllegalArgumentException("RestrictedExpression.Piecewise.branches 不得含 null");
        }
        copy.add(branch);
      }
      branches = Collections.unmodifiableList(copy); // ★ 冻在赋值处
    }

    /** 一条分支（谓词 + 取值）。 */
    public record Branch(RestrictedExpression predicate, RestrictedExpression value) {

      public Branch {
        if (predicate == null || value == null) {
          throw new IllegalArgumentException("RestrictedExpression.Piecewise.Branch 两项都不得为 null");
        }
      }
    }
  }

  /**
   * ★★ <b>求值</b>：白名单运算的<b>唯一实现</b>（语义同 {@link ExpressionDefinition#evaluate}）。
   *
   * @param inputs 已解析的输入表（名字 → 值）；缺名 ⇒ 契约故障
   */
  default long evaluate(Map<String, Long> inputs) {
    if (inputs == null) {
      throw new IllegalArgumentException("RestrictedExpression.evaluate 的 inputs 不得为 null");
    }
    return switch (this) {
      case Const constant -> constant.value();
      case Input input -> {
        Long value = inputs.get(input.name());
        if (value == null) {
          throw new ProductionContractFault(
              "expression-unknown-input", "表达式引用了未提供的输入名（不允许隐式读状态）: " + input.name());
        }
        yield value;
      }
      case Add add -> Math.addExact(add.left().evaluate(inputs), add.right().evaluate(inputs));
      case Sub sub -> Math.subtractExact(sub.left().evaluate(inputs), sub.right().evaluate(inputs));
      case Mul mul -> Math.multiplyExact(mul.left().evaluate(inputs), mul.right().evaluate(inputs));
      case Div div ->
          floorDivOrFault(div, div.dividend().evaluate(inputs), div.divisor().evaluate(inputs));
      case MulDiv mulDiv ->
          mulDivOrFault(
              mulDiv,
              mulDiv.multiplicand().evaluate(inputs),
              mulDiv.multiplier().evaluate(inputs),
              mulDiv.divisor().evaluate(inputs));
      case Min min -> Math.min(min.left().evaluate(inputs), min.right().evaluate(inputs));
      case Max max -> Math.max(max.left().evaluate(inputs), max.right().evaluate(inputs));
      case Clamp clamp ->
          clampOrFault(
              clamp,
              clamp.value().evaluate(inputs),
              clamp.min().evaluate(inputs),
              clamp.max().evaluate(inputs));
      case Piecewise piecewise -> piecewise(piecewise, inputs);
    };
  }

  private static long floorDivOrFault(Div div, long dividend, long divisor) {
    if (divisor == 0L) {
      throw new ProductionContractFault("expression-divide-by-zero", "受限表达式的除数为 0（Div）: " + div);
    }
    return Math.floorDiv(dividend, divisor);
  }

  private static long mulDivOrFault(
      MulDiv mulDiv, long multiplicand, long multiplier, long divisor) {
    if (divisor == 0L) {
      throw new ProductionContractFault(
          "expression-divide-by-zero", "受限表达式的除数为 0（MulDiv）: " + mulDiv);
    }
    return Math.floorDiv(Math.multiplyExact(multiplicand, multiplier), divisor);
  }

  private static long clampOrFault(Clamp clamp, long value, long min, long max) {
    if (min > max) {
      throw new ProductionContractFault(
          "expression-clamp-bounds", "受限表达式 Clamp 的下界大于上界: " + min + " > " + max);
    }
    return Math.min(Math.max(value, min), max);
  }

  private static long piecewise(Piecewise piecewise, Map<String, Long> inputs) {
    for (Piecewise.Branch branch : piecewise.branches()) {
      if (branch.predicate().evaluate(inputs) != 0L) {
        return branch.value().evaluate(inputs);
      }
    }
    return piecewise.otherwise().evaluate(inputs);
  }

  /**
   * ★ <b>本表达式引用的全部输入名</b>（保序、去重）——供 {@link ExpressionDefinition} 做"声明 ⊇ 引用"
   * 的构造期校验：漏声明会在求值期才炸，那是运行时故障；这里把它变成<b>构造期</b>故障。
   */
  default List<String> referencedInputs() {
    Map<String, Boolean> ordered = new LinkedHashMap<>();
    collectInputs(ordered);
    return List.copyOf(ordered.keySet());
  }

  private void collectInputs(Map<String, Boolean> sink) {
    switch (this) {
      case Const ignored -> {}
      case Input input -> sink.putIfAbsent(input.name(), Boolean.TRUE);
      case Add add -> {
        add.left().collectInputs(sink);
        add.right().collectInputs(sink);
      }
      case Sub sub -> {
        sub.left().collectInputs(sink);
        sub.right().collectInputs(sink);
      }
      case Mul mul -> {
        mul.left().collectInputs(sink);
        mul.right().collectInputs(sink);
      }
      case Div div -> {
        div.dividend().collectInputs(sink);
        div.divisor().collectInputs(sink);
      }
      case MulDiv mulDiv -> {
        mulDiv.multiplicand().collectInputs(sink);
        mulDiv.multiplier().collectInputs(sink);
        mulDiv.divisor().collectInputs(sink);
      }
      case Min min -> {
        min.left().collectInputs(sink);
        min.right().collectInputs(sink);
      }
      case Max max -> {
        max.left().collectInputs(sink);
        max.right().collectInputs(sink);
      }
      case Clamp clamp -> {
        clamp.value().collectInputs(sink);
        clamp.min().collectInputs(sink);
        clamp.max().collectInputs(sink);
      }
      case Piecewise piecewise -> {
        for (Piecewise.Branch branch : piecewise.branches()) {
          branch.predicate().collectInputs(sink);
          branch.value().collectInputs(sink);
        }
        piecewise.otherwise().collectInputs(sink);
      }
    }
  }
}
