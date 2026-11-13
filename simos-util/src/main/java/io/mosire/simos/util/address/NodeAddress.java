package io.mosire.simos.util.address;

import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.NodeRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 节点地址（D-036）：{@code treeXXX-tickXXX[/<type>[/<subtype>]/<id>...]}。
 *
 * <p>★ 第一段固定是节点坐标 {@code (branch, tick)}；后续段是实体路径，解释权归 app/各模块。
 * 例：
 *
 * <pre>{@code
 * tree1-360
 * tree1-360/hex/1,1
 * tree1-360/region/小世界
 * tree1-360/unit/army/vg-berlin
 * tree1-360/unit/gov/gov-central
 * tree1-350/battle/berlinbattle
 * tree1-350/battle/berlinbattle/stage/s1
 * }</pre>
 *
 * <p>★ 本类型只解析**语法**，不解析实体语义；{@code path} 原样保留，避免 util 认识各领域类型。
 *
 * <p>★ 节点段解析：取**最后一个** {@code '-'} 之前为分支名、之后为十进制 tick。分支名允许含 {@code '-'}；
 * tick 必须是非负十进制整数。节点地址不得含空白、不得含空段、不得含 {@code ..}。
 *
 * @param node 节点坐标
 * @param path 实体路径（可为空 = 只查节点；非空时第 1 段是 type）
 */
public record NodeAddress(NodeRef node, List<String> path) {

  /** 节点地址文本长度上限（防御性；远大于实际地址）。 */
  public static final int MAX_LENGTH = 512;

  public NodeAddress {
    Objects.requireNonNull(node, "node");
    path = List.copyOf(Objects.requireNonNull(path, "path"));
    for (String segment : path) {
      if (segment == null || segment.isBlank()) {
        throw new IllegalArgumentException("节点地址的路径段不得为空白: " + path);
      }
      if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
        throw new IllegalArgumentException("节点地址的路径段不得含 '/' 或 '\\\\': " + segment);
      }
      if (segment.equals("..") || segment.equals(".")) {
        throw new IllegalArgumentException("节点地址的路径段不得为 '.' / '..': " + segment);
      }
    }
  }

  /**
   * 解析节点地址。
   *
   * @param text 地址文本
   * @return 解析结果
   * @throws IllegalArgumentException 空/含空白/空段/节点段非法/路径段非法
   */
  public static NodeAddress parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("节点地址不得为空");
    }
    String value = text.strip();
    if (value.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("节点地址不得含空白: " + value);
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("节点地址过长（上限 " + MAX_LENGTH + "）: " + value.length());
    }
    String[] raw = value.split("/", -1);
    List<String> segments = new ArrayList<>(raw.length);
    for (String segment : raw) {
      if (segment.isEmpty()) {
        throw new IllegalArgumentException("节点地址含空段: " + value);
      }
      segments.add(segment);
    }
    NodeRef node = parseNode(segments.get(0), value);
    List<String> path = segments.size() == 1 ? List.of() : segments.subList(1, segments.size());
    return new NodeAddress(node, path);
  }

  /** 是否匹配节点地址形态（不抛；用于“先试 NodeAddress、失败再试旧 Address”）。 */
  public static boolean looksLikeNodeAddress(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String first = text.strip().split("/", 2)[0];
    int dash = first.lastIndexOf('-');
    if (dash <= 0 || dash == first.length() - 1) {
      return false;
    }
    String branch = first.substring(0, dash);
    String tick = first.substring(dash + 1);
    return !branch.isBlank()
        && branch.indexOf(':') < 0
        && tick.chars().allMatch(Character::isDigit);
  }

  private static NodeRef parseNode(String segment, String whole) {
    int dash = segment.lastIndexOf('-');
    if (dash <= 0 || dash == segment.length() - 1) {
      throw new IllegalArgumentException(
          "节点地址首段必须是 <branch>-<tick>（如 tree1-360）: `" + segment + "`（地址：`" + whole + "`）");
    }
    String branch = segment.substring(0, dash);
    String tickText = segment.substring(dash + 1);
    if (branch.isBlank() || branch.indexOf(':') >= 0) {
      throw new IllegalArgumentException(
          "节点地址的分支名不得为空白或含 ':'（旧 namespace 地址不是节点地址）: `" + whole + "`");
    }
    if (!tickText.chars().allMatch(Character::isDigit)) {
      throw new IllegalArgumentException(
          "节点地址的 tick 必须是非负十进制整数: `" + tickText + "`（地址：`" + whole + "`）");
    }
    long tick;
    try {
      tick = Long.parseLong(tickText);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("节点地址的 tick 溢出: `" + tickText + "`", e);
    }
    return new NodeRef(new BranchId(branch), tick);
  }

  /** 规范化文本：{@code branch-tick[/path…]}。 */
  public String canonical() {
    if (path.isEmpty()) {
      return node.label();
    }
    return node.label() + "/" + String.join("/", path);
  }

  /** 实体类型（path 为空 ⇒ empty；否则第 1 段）。 */
  public Optional<String> type() {
    return path.isEmpty() ? Optional.empty() : Optional.of(path.get(0));
  }

  /** 剩余路径（type 之后的段）。 */
  public List<String> rest() {
    return path.isEmpty() ? List.of() : List.copyOf(path.subList(1, path.size()));
  }

  /** 要求至少有一段路径，否则具名抛。 */
  public NodeAddress requireEntityPath() {
    if (path.isEmpty()) {
      throw new IllegalArgumentException("该地址只指向节点，缺少实体路径: " + canonical());
    }
    return this;
  }

  @Override
  public String toString() {
    return canonical();
  }
}
