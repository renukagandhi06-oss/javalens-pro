import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Security & Resource-Leak Scanner (Pure Java OOP)
 * Scans Java source code for production security flaws and AutoCloseable resource leaks:
 *  - SEC-001: SQL Injection via String concatenation
 *  - SEC-002: Hardcoded secrets, passwords, or API keys
 *  - SEC-003: Unclosed AutoCloseable Resource Leak (FileInputStream, Scanner, BufferedReader, Socket, Connection, etc.)
 *  - SEC-004: Weak randomness (Math.random / java.util.Random) in security context
 *  - SEC-006: OS Command Injection via Runtime.exec / ProcessBuilder string concatenation
 */
public class SecurityScanner {

    private static final Pattern SQL_CONCAT = Pattern.compile(
            "\"[^\"]*\\b(?:SELECT|INSERT|UPDATE|DELETE)\\b[^\"]*\"\\s*\\+\\s*[A-Za-z_][A-Za-z0-9_]*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CMD_INJECTION = Pattern.compile(
            "(?:Runtime\\.getRuntime\\(\\)\\.exec|new\\s+ProcessBuilder)\\s*\\([^;]*\"[^\"]+\"\\s*\\+\\s*[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern HARDCODED_SECRET = Pattern.compile(
            "\\bString\\s+[A-Za-z0-9_]*(?:password|passwd|secret|apiKey|api_key|token|authKey)[A-Za-z0-9_]*\\s*=\\s*\"[^\"]{3,}\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESOURCE_ALLOC = Pattern.compile(
            "\\b(Scanner|FileReader|FileWriter|BufferedReader|BufferedWriter|FileInputStream|FileOutputStream|InputStream|OutputStream|PrintWriter|Socket|ServerSocket|Connection|PreparedStatement|ResultSet|RandomAccessFile)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*new\\s+");
    private static final Pattern WEAK_RANDOM_CTX = Pattern.compile(
            "\\b(?:otp|token|secret|password|key|pin)[A-Za-z0-9_]*\\b.*(?:Math\\.random|new\\s+Random\\s*\\()",
            Pattern.CASE_INSENSITIVE);

    public List<DiagnosticItem> scan(String sourceCode) {
        List<DiagnosticItem> findings = new ArrayList<>();
        if (sourceCode == null || sourceCode.trim().isEmpty()) return findings;

        String[] lines = sourceCode.split("\\r?\\n", -1);

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String code = stripComment(lines[i]);
            if (code.trim().isEmpty()) continue;

            // SEC-001: SQL Injection
            Matcher msql = SQL_CONCAT.matcher(code);
            if (msql.find()) {
                findings.add(new DiagnosticItem(
                        lineNum, msql.start() + 1,
                        DiagnosticItem.Severity.ERROR,
                        DiagnosticItem.Category.SECURITY,
                        "SEC-001",
                        "SQL Injection Vulnerability",
                        "Cause: Building SQL queries with '+' string concatenation. Runtime Consequence: Attackers can inject arbitrary SQL statements to steal or delete database records.",
                        "Use PreparedStatement with '?' placeholders and ps.setString(1, value)."
                ));
            }

            // SEC-006: OS Command Injection
            Matcher mcmd = CMD_INJECTION.matcher(code);
            if (mcmd.find()) {
                findings.add(new DiagnosticItem(
                        lineNum, mcmd.start() + 1,
                        DiagnosticItem.Severity.ERROR,
                        DiagnosticItem.Category.SECURITY,
                        "SEC-006",
                        "OS Command Injection Vulnerability",
                        "Cause: Concatenating untrusted input into Runtime.exec() or ProcessBuilder command string. Runtime Consequence: Attackers can append shell metacharacters ('; rm -rf', '&& ...') to execute arbitrary OS commands!",
                        "Pass command and arguments as separate ProcessBuilder list elements and validate input against an allowlist."
                ));
            }

            // SEC-002: Hardcoded password / API key
            Matcher msec = HARDCODED_SECRET.matcher(code);
            if (msec.find()) {
                findings.add(new DiagnosticItem(
                        lineNum, msec.start() + 1,
                        DiagnosticItem.Severity.WARNING,
                        DiagnosticItem.Category.SECURITY,
                        "SEC-002",
                        "Hardcoded Secret or Credential",
                        "Cause: Password, token, or API key literal stored directly in Java source code. Runtime Consequence: Credentials leak if source code is pushed to Git or shared.",
                        "Load secrets at runtime using System.getenv(\"API_KEY\") instead of hardcoding."
                ));
            }

            // SEC-003: Unclosed AutoCloseable Resource Leak
            Matcher mres = RESOURCE_ALLOC.matcher(code);
            if (mres.find()) {
                String resType = mres.group(1);
                String varName = mres.group(2);
                boolean inTryWithResources = code.trim().startsWith("try") || code.contains("try (") || code.contains("try(");
                boolean hasClose = sourceCode.contains(varName + ".close()");
                if (!inTryWithResources && !hasClose) {
                    findings.add(new DiagnosticItem(
                            lineNum, mres.start() + 1,
                            DiagnosticItem.Severity.ERROR,
                            DiagnosticItem.Category.SECURITY,
                            "SEC-003",
                            "ResourceLeak: Unclosed " + resType + " ('" + varName + "')",
                            "Cause: AutoCloseable resource '" + varName + "' (" + resType + ") is opened without calling " + varName + ".close() or try-with-resources. Runtime Consequence: Leaks OS file descriptors/handles on every call until the JVM crashes with 'Too many open files'!",
                            "Use try-with-resources: try (" + resType + " " + varName + " = new " + resType + "(...)) { ... }"
                    ));
                }
            }

            // SEC-004: Weak randomness for security tokens/OTPs
            Matcher mrand = WEAK_RANDOM_CTX.matcher(code);
            if (mrand.find()) {
                findings.add(new DiagnosticItem(
                        lineNum, mrand.start() + 1,
                        DiagnosticItem.Severity.WARNING,
                        DiagnosticItem.Category.SECURITY,
                        "SEC-004",
                        "Insecure Random Generator for Security Value",
                        "Cause: Using Math.random() or java.util.Random for a security token/OTP. Runtime Consequence: Outputs are predictable from seed state and can be guessed by attackers.",
                        "Use java.security.SecureRandom instead."
                ));
            }
        }

        return findings;
    }

    private static String stripComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
