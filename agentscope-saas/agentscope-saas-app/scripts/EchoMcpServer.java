// Minimal stdio MCP server for end-to-end verification of the SaaS dynamic-MCP (C2) path.
// Zero external dependencies, zero network: runs as a subprocess the agent's MCP client spawns
// via `java EchoMcpServer.java`. Speaks MCP JSON-RPC 2.0 over stdin/stdout (newline-delimited),
// implementing just the subset the io.modelcontextprotocol.sdk client exercises: initialize,
// notifications/initialized (ignored), tools/list, tools/call.
//
// Tools: `echo` (echoes a message) and `calculate` (a op b for add/sub/mul/div).
//
// Usage: java EchoMcpServer.java
// tools.json / org-base config (stdio transport):
//   { "transport":"stdio", "command":"java",
//     "args":["<repo>/agentscope-saas-app/scripts/EchoMcpServer.java"] }
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EchoMcpServer {

    private static final Pattern ID_NUM = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern ID_STR = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern METHOD = Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PROTO = Pattern.compile("\"protocolVersion\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TC_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ARG_MSG = Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ARG_A = Pattern.compile("\"a\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern ARG_B = Pattern.compile("\"b\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern ARG_OP = Pattern.compile("\"op\"\\s*:\\s*\"([^\"]+)\"");

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintStream out = System.out;
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            try {
                handle(line, out);
            } catch (Exception e) {
                // Never crash the server on a malformed line — one bad request must not kill the
                // agent's MCP connection. Log to stderr (not stdout, which is the JSON-RPC channel).
                System.err.println("echo-mcp: failed to handle line: " + e.getMessage());
            }
        }
    }

    private static void handle(String line, PrintStream out) {
        String method = match(METHOD, line);
        if (method == null) return; // not a request/notification we recognize
        String idJson = match(ID_NUM, line);
        if (idJson == null) {
            String idString = match(ID_STR, line);
            if (idString != null) {
                idJson = jsonString(unescape(idString));
            }
        }

        switch (method) {
            case "initialize":
                // Echo the client's protocolVersion back (standard MCP negotiation); advertise tools.
                String proto = match(PROTO, line);
                if (proto == null) proto = "2025-03-26";
                respond(
                        out,
                        idJson,
                        "{\"protocolVersion\":\""
                                + proto
                                + "\",\"capabilities\":{\"tools\":{}},"
                                + "\"serverInfo\":{\"name\":\"echo-mcp\",\"version\":\"1.0.0\"}}");
                break;
            case "notifications/initialized":
                // Notification (no id) — no response per JSON-RPC.
                break;
            case "tools/list":
                respond(
                        out,
                        idJson,
                        "{\"tools\":["
                                + "{\"name\":\"echo\",\"description\":\"Echo back the provided message.\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                                + "{\"message\":{\"type\":\"string\"}},\"required\":[\"message\"]}},"
                                + "{\"name\":\"calculate\",\"description\":\"Calculate a op b "
                                + "(op: add|sub|mul|div).\","
                                + "\"inputSchema\":{\"type\":\"object\",\"properties\":"
                                + "{\"a\":{\"type\":\"number\"},\"b\":{\"type\":\"number\"},"
                                + "\"op\":{\"type\":\"string\",\"enum\":[\"add\",\"sub\",\"mul\",\"div\"]}},"
                                + "\"required\":[\"a\",\"b\",\"op\"]}}"
                                + "]}");
                break;
            case "tools/call":
                respond(out, idJson, callTool(line));
                break;
            default:
                if (idJson != null) {
                    respond(out, idJson, error(-32601, "Method not found: " + method));
                }
        }
    }

    private static String callTool(String line) {
        String name = match(TC_NAME, line);
        if (name == null) return errorResult("no tool name in tools/call params");
        switch (name) {
            case "echo":
                {
                    String msg = match(ARG_MSG, line);
                    String decoded = msg == null ? "" : unescape(msg);
                    return textResult("echo: " + decoded);
                }
            case "calculate":
                {
                    String a = match(ARG_A, line);
                    String b = match(ARG_B, line);
                    String op = match(ARG_OP, line);
                    if (a == null || b == null || op == null) {
                        return errorResult("calculate requires a, b, op");
                    }
                    try {
                        double x = Double.parseDouble(a);
                        double y = Double.parseDouble(b);
                        double r;
                        switch (op) {
                            case "add":
                                r = x + y;
                                break;
                            case "sub":
                                r = x - y;
                                break;
                            case "mul":
                                r = x * y;
                                break;
                            case "div":
                                if (y == 0) return errorResult("division by zero");
                                r = x / y;
                                break;
                            default:
                                return errorResult("unknown op: " + op);
                        }
                        // Trim trailing .0 so integer results read cleanly.
                        String rs = (r == Math.rint(r) && !Double.isInfinite(r))
                                ? Long.toString((long) r)
                                : Double.toString(r);
                        return textResult(a + " " + op + " " + b + " = " + rs);
                    } catch (NumberFormatException e) {
                        return errorResult("a/b must be numbers");
                    }
                }
            default:
                return errorResult("unknown tool: " + name);
        }
    }

    // --- JSON-RPC framing -----------------------------------------------------

    private static void respond(PrintStream out, String id, String resultJson) {
        if (id == null) return; // notification — no response
        synchronized (out) {
            out.print("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}\n");
            out.flush();
        }
    }

    private static void respondError(PrintStream out, String idJson, int code, String message) {
        if (idJson == null) return;
        synchronized (out) {
            out.print(
                    "{\"jsonrpc\":\"2.0\",\"id\":"
                            + idJson
                            + ",\"error\":{\"code\":"
                            + code
                            + ",\"message\":"
                            + jsonString(message)
                            + "}}\n");
            out.flush();
        }
    }

    private static String error(int code, String message) {
        return "{\"error\":{\"code\":" + code + ",\"message\":" + jsonString(message) + "}}";
    }

    /** A tools/call result carrying text content (success). */
    private static String textResult(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + jsonString(text) + "}],\"isError\":false}";
    }

    /** A tools/call result signaling an error (isError=true) — still 200 at the JSON-RPC layer. */
    private static String errorResult(String message) {
        return "{\"content\":[{\"type\":\"text\",\"text\":" + jsonString(message) + "}],\"isError\":true}";
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String match(Pattern p, String input) {
        Matcher m = p.matcher(input);
        return m.find() ? m.group(1) : null;
    }
}
