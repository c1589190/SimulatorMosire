import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次性的 MCP 客户端：连接 http://127.0.0.1:5715/mcp，执行 tools/list 或某条 tools/call。
 *
 * <p>用法：
 *
 * <pre>
 *   java McpCall &lt;baseUrl&gt; &lt;endpoint&gt; __list__
 *   java McpCall &lt;baseUrl&gt; &lt;endpoint&gt; &lt;toolName&gt; &lt;jsonArgs&gt;
 * </pre>
 *
 * <p>{@code MCP_RAW=1} 时把返回体（text content）原样打到 stdout（便于落档）。
 */
public final class McpCall {

  private static final ObjectMapper JSON = new ObjectMapper();

  public static void main(String[] args) throws Exception {
    if (args.length < 3) {
      System.err.println("usage: McpCall <baseUrl> <endpoint> <tool|__list__> [jsonArgs]");
      System.exit(2);
    }
    String baseUrl = args[0];
    String endpoint = args[1];
    String tool = args[2];
    Map<String, Object> arguments = new LinkedHashMap<>();
    if (args.length >= 4 && !args[3].isBlank()) {
      JsonNode node = JSON.readTree(args[3]);
      arguments = JSON.convertValue(node, Map.class);
    }

    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(baseUrl)
            .endpoint(endpoint)
            .resumableStreams(false)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    try (McpSyncClient client = McpClient.sync(transport).build()) {
      McpSchema.InitializeResult init = client.initialize();
      System.out.println("SERVER=" + init.serverInfo().name() + " v" + init.serverInfo().version());
      System.out.println("PROTOCOL=" + init.protocolVersion());

      if (tool.equals("__list__")) {
        McpSchema.ListToolsResult tools = client.listTools();
        System.out.println("TOOL_COUNT=" + tools.tools().size());
        for (McpSchema.Tool t : tools.tools()) {
          System.out.println("TOOL\t" + t.name());
        }
      } else {
        System.out.println("CALL=" + tool + " ARGS=" + JSON.writeValueAsString(arguments));
        McpSchema.CallToolResult result =
            client.callTool(new McpSchema.CallToolRequest(tool, arguments));
        System.out.println("IS_ERROR=" + result.isError());
        for (McpSchema.Content c : result.content()) {
          if (c instanceof McpSchema.TextContent tc) {
            System.out.println("TEXT=" + tc.text());
          } else {
            System.out.println("CONTENT=" + c);
          }
        }
      }
    }
  }
}
