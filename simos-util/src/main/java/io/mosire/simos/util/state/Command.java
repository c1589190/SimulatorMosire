package io.mosire.simos.util.state;

/**
 * 命令（总纲 §4.5）：保持最小——只有乐观并发所需的 `expectedRevision()`。
 *
 * <p>`commandId` / `correlationId` / 发起者属于**命令信封**（总纲 §8.1），由 Core 的 Command Bus 承担（spec §十-D6）。
 */
public interface Command {

  RevisionId expectedRevision();
}
