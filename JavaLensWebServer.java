import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * JavaLens Pro - Built-in Pure Java HTTP Web Server (Standard JDK com.sun.net.httpserver)
 * Serves index.html and routes all Analyze, Auto-Fix, Metrics, and Live Run requests
 * directly to the Pure Java OOP Engine classes (CompilerEngine, StaticAnalyzer, AutoFixer, CodeRunner).
 */
public class JavaLensWebServer {

    private final CompilerEngine compilerEngine = new CompilerEngine();
    private final StaticAnalyzer staticAnalyzer = new StaticAnalyzer();
    private final AutoFixer autoFixer = new AutoFixer();
    private final CodeRunner codeRunner = new CodeRunner();

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);

        server.createContext("/", this::handleStaticUi);
        server.createContext("/api/ping", ex -> sendJson(ex, 200, "{\"status\":\"ok\",\"engine\":\"JavaLens Pure Java JDK 17\"}"));
        server.createContext("/api/analyze", this::handleAnalyze);
        server.createContext("/api/autofix", this::handleAutoFix);
        server.createContext("/api/run", this::handleRun);

        server.setExecutor(null);
        server.start();
        System.out.println("============================================================");
        System.out.println("  JavaLens Pro - Pure Java Backend Server Running!");
        System.out.println("  Listening on port: " + port);
        System.out.println("============================================================");
    }

    private void handleStaticUi(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            sendCorsEmpty(ex);
            return;
        }
        File uiFile = new File("index.html");
        if (!uiFile.exists()) {
            uiFile = new File("javalens_studio.html");
        }
        if (!uiFile.exists()) {
            sendText(ex, 404, "text/plain", "index.html not found in working directory.");
            return;
        }
        byte[] bytes = Files.readAllBytes(uiFile.toPath());
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleAnalyze(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { sendCorsEmpty(ex); return; }
        String code = readBody(ex);
        List<DiagnosticItem> all = new ArrayList<>();
        all.addAll(compilerEngine.compileAndDiagnose(code));
        all.addAll(staticAnalyzer.analyze(code));
        all.sort(Comparator.comparingInt(DiagnosticItem::getLine));
        CodeMetrics m = CodeMetrics.compute(code, all);

        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"metrics\":{")
                .append("\"totalLines\":").append(m.totalLines).append(",")
                .append("\"codeLines\":").append(m.codeLines).append(",")
                .append("\"classCount\":").append(m.classCount).append(",")
                .append("\"methodCount\":").append(m.methodCount).append(",")
                .append("\"complexity\":").append(m.cyclomaticComplexity).append(",")
                .append("\"healthScore\":").append(m.healthScore).append(",")
                .append("\"healthGrade\":\"").append(escJson(m.healthGrade)).append("\",")
                .append("\"oopConcepts\":").append(toJsonStringArray(m.oopConcepts))
                .append("},");
        json.append("\"diagnostics\":[");
        for (int i = 0; i < all.size(); i++) {
            DiagnosticItem d = all.get(i);
            if (i > 0) json.append(",");
            json.append("{")
                    .append("\"line\":").append(d.getLine()).append(",")
                    .append("\"column\":").append(d.getColumn()).append(",")
                    .append("\"severity\":\"").append(d.getSeverity().name()).append("\",")
                    .append("\"category\":\"").append(d.getCategory().name()).append("\",")
                    .append("\"ruleId\":\"").append(escJson(d.getRuleId())).append("\",")
                    .append("\"title\":\"").append(escJson(d.getTitle())).append("\",")
                    .append("\"message\":\"").append(escJson(d.getMessage())).append("\",")
                    .append("\"suggestion\":\"").append(escJson(d.getSuggestion())).append("\"")
                    .append("}");
        }
        json.append("]}");
        sendJson(ex, 200, json.toString());
    }

    private void handleAutoFix(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { sendCorsEmpty(ex); return; }
        String code = readBody(ex);
        AutoFixer.FixResult res = autoFixer.autoFix(code);
        String json = "{"
                + "\"fixedCode\":\"" + escJson(res.fixedCode) + "\","
                + "\"appliedFixes\":" + toJsonStringArray(res.appliedFixes) + ","
                + "\"diffLines\":" + toJsonStringArray(res.diffLines)
                + "}";
        sendJson(ex, 200, json);
    }

    private void handleRun(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { sendCorsEmpty(ex); return; }
        String code = readBody(ex);
        CodeRunner.RunResult rr = codeRunner.compileAndRun(code);
        String json = "{"
                + "\"compiled\":" + rr.compiled + ","
                + "\"exitCode\":" + rr.exitCode + ","
                + "\"elapsedMs\":" + rr.elapsedMs + ","
                + "\"stdout\":\"" + escJson(rr.stdout) + "\","
                + "\"stderr\":\"" + escJson(rr.stderr) + "\","
                + "\"crashDiagnosis\":\"" + escJson(rr.crashDiagnosis) + "\""
                + "}";
        sendJson(ex, 200, json);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        try (InputStream is = ex.getRequestBody()) {
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendCorsEmpty(HttpExchange ex) throws IOException {
        addCors(ex);
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    private static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        sendText(ex, status, "application/json; charset=UTF-8", json);
    }

    private static void sendText(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String toJsonStringArray(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escJson(list.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String escJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c >= 32 && c < 127) sb.append(c);
                    else sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }
}
