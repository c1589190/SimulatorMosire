# 2026-10-09 通用日志 event 机制落 util（模块门面薄化）

> 用户裁定（2026-10-09）：「为啥非要让日志模块本身知道具体涉及了什么发生了什么，单纯的 event
> 完全应该放在 util。」确认范围：新增 util `LogEvent` / `LogLevel` / `EventLog` / `LogChannel`；
> 11 个模块 `XxxLog` 降为只提供 logger 命名空间与分类的薄门面；`kv` 实现去重；既有事件名、输出格式、
> `log4j2.xml` 开关**全部不变**。
> 基线：`HEAD 3de6a88a`。

## 1. 口径

- **事件名与字段由产生者给**：例如 `MIGRATION_PLAN`、`POPULATION_SETTLE`、`TAX_COLLECTED`；
  util 不认识任何领域事件词表、不 import 任何领域类型。
- **通用机制在 util**：事件信封（name + 有序字段表）、级别、行格式化、发射口、channel 包装都在
  `io.mosire.simos.util.log`。
- **模块门面只留命名空间**：`EconomyLog` / `SocialLog` / `UnitLog` / `MapLog` / `SdLog` / `ActorLog` /
  `GovLog` / `ArmyLog` / `CalendarLog` / `CoreLog` / `AppLog` 继续是 logger 名的唯一拼写点，继续被
  `log4j2.xml` 的 `simos.<module>.logLevel/traceLevel` 开关控制；不再各自实现 `kv`。
- **util 不碰状态/公式/文件系统**：沿用 util 的零领域边界；只依赖已有 `slf4j-api`。
- **`Event<T>` 不混用**：`io.mosire.simos.util.time.Event` 是"时刻 + 值 + ADD/SET"的时态值类型；
  新日志类型叫 `LogEvent`，两者不合并。

## 2. 新类型（simos-util/src/main/java/io/mosire/simos/util/log/）

```text
LogLevel
  enum { TRACE, DEBUG, INFO, WARN, ERROR }

LogEvent
  record LogEvent(String name, Map<String, Object> fields)
  - name 非空白；fields 保序不可变
  - static LogEvent of(String name, Object... keyValues)   // 偶数 key/value，键非空
  - String toLine()   // "event=<name> k=v k2=v2"；空字段不带尾空格

EventLog
  final class
  - static String kv(Object... keyValues)                  // 唯一 kv 实现（原 11 份复制收口）
  - static LogEvent event(String name, Object... kv)       // 便捷构造
  - static void emit(Logger, LogLevel, LogEvent)
  - static void emit(Logger, LogLevel, String name, Object... kv)
  - static LogChannel channel(Logger)

LogChannel
  final class
  - static LogChannel of(Logger)
  - boolean isTraceEnabled()/isDebugEnabled()/isInfoEnabled()/isWarnEnabled()/isErrorEnabled()
  - trace/debug/info/warn/error(LogEvent)
  - trace/debug/info/warn/error(String eventName, Object... kv)
```

## 3. 兼容策略

- `XxxLog.<category>()` 继续返回 `org.slf4j.Logger`（69 个字段类型与全部旧调用点不动）。
- `XxxLog.kv(...)` 保留为一行委托：`return EventLog.kv(keyValues);`，旧调用点与测试不需要改；
  新代码直接用 `EventLog.kv` / `LogEvent` / `LogChannel`。
- 输出格式逐字符不变：`event=NAME key=value ...`；日志级别判断与旧行为一致。
- `log4j2.xml`、logger 名、事件名、INFO/DEBUG/TRACE 归属全部不变。

## 4. 验收

1. `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0；
2. 11 个 `XxxLog.kv` 不再各自实现拼接，只委托 util；
3. util 新包零领域依赖（grep 无 `io.mosire.simos` 领域 import 反向？util 只 import java + slf4j）；
4. 随机抽一条现有日志行与改造前逐字符一致（格式：`event=... k=v ...`）；
5. 不跑 test/test-compile/verify，不动状态与公式。

## 5. 明确的 Non-goal

- 不把领域事件（`HouseholdPopulationEvent`、`DebtContract`、`MIGRATION_*` 等）搬进 util；
  util 只放**日志信封**，领域事件仍是各域状态。
- 不重写现有调用点为 `LogEvent`（兼容层保留；新代码优先用新 API）。
- 不改 `log4j2.xml` 与模块分类。
