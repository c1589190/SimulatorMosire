package io.mosire.simos.app.gui;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * 静态资源（M5 T8，spec §8.1）：classpath {@code /webui/...} 下的文件，**无构建、无 CDN、同源**。
 *
 * <p>★ 页面路由（spec §8.2）：{@code /} → {@code index.html}；{@code /map} / {@code /unit} / {@code
 * /social} → 各自的 {@code .html}。T8 只交付 {@code index.html} 骨架，三页由 T9 补齐——**文件缺席即 404**，不代造页面。
 *
 * <p>★ {@code Cache-Control: no-store}：无构建产物、无常量摘要，缓存住旧页会让"改了源码却看不到"成为症状（与内存级 {@code HttpServer}
 * 的开发形态相称）。
 *
 * <p>★ **不做目录穿越**：名字里出现 {@code ..} 一律不服务（{@link #resourceName} 返回 null）。
 */
final class StaticHandler {

  private static final String CLASSPATH_ROOT = "/webui/";

  /** 页面别名 → classpath 资源名（与 spec §8.2 的静态页路由逐条对应）。 */
  private static final Map<String, String> PAGE_ALIASES =
      Map.of(
          "/", "index.html",
          "/index.html", "index.html",
          "/map", "map.html",
          "/unit", "unit.html",
          "/social", "social.html");

  /**
   * 尝试服务一个静态资源：命中并写出 ⇒ {@code true}；未命中（包括资源缺席）⇒ {@code false}，由调用方回 404。
   *
   * @param exchange 当前请求
   * @param path 已解析的请求路径（不含查询串）
   */
  boolean tryServe(HttpExchange exchange, String path) throws IOException {
    String resourceName = resourceName(path);
    if (resourceName == null) {
      return false;
    }
    try (InputStream in = StaticHandler.class.getResourceAsStream(CLASSPATH_ROOT + resourceName)) {
      if (in == null) {
        return false;
      }
      byte[] body = in.readAllBytes();
      exchange.getResponseHeaders().set("Content-Type", contentType(resourceName));
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.sendResponseHeaders(200, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
      return true;
    }
  }

  /** 路径 → classpath 资源名；非静态路径或含目录穿越 ⇒ {@code null}（＝不服务）。 */
  private static String resourceName(String path) {
    String alias = PAGE_ALIASES.get(path);
    if (alias != null) {
      return alias;
    }
    if (!path.startsWith("/") || path.length() == 1) {
      return null;
    }
    String name = path.substring(1);
    if (name.contains("..")) {
      return null;
    }
    return name;
  }

  private static String contentType(String resourceName) {
    if (resourceName.endsWith(".html")) {
      return "text/html; charset=utf-8";
    }
    if (resourceName.endsWith(".js")) {
      return "text/javascript; charset=utf-8";
    }
    if (resourceName.endsWith(".css")) {
      return "text/css; charset=utf-8";
    }
    if (resourceName.endsWith(".json")) {
      return "application/json; charset=utf-8";
    }
    if (resourceName.endsWith(".svg")) {
      return "image/svg+xml";
    }
    return "application/octet-stream";
  }
}
