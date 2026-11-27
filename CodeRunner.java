import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Live Output Terminal, Interactive Stdin & Root-Cause Correlated Debugger
 * Compiles user Java code to a temp directory, executes it in a child JVM with a 5s safety timeout,
 * drains stdout/stderr concurrently on daemon threads, feeds optional stdin, and correlates live
 * runtime symptoms (negative integer overflow, infinite loop timeout, Map: null, unexpected false,
 * NaN, wrong thread counters, deadlocks) directly back to exact root causes across our 16-class engine.
 */
public class CodeRunner {

    private final ConcurrencyAnalyzer concurrencyAnalyzer = new ConcurrencyAnalyzer();
    private final SemanticAnalyzer semanticAnalyzer = new SemanticAnalyzer();
    private final DeepBugAnalyzer deepBugAnalyzer = new DeepBugAnalyzer();
    private final SecurityScanner securityScanner = new SecurityScanner();

    private static final Pattern NEGATIVE_INT_OUT = Pattern.compile("(?<![A-Za-z0-9_])-\\d{4,}");

    public static class RunResult {
        public final boolean compiled;
        public final int exitCode;
        public final long elapsedMs;
        public final String stdout;
        public final String stderr;
        public final String crashDiagnosis;

        public RunResult(boolean compiled, int exitCode, long elapsedMs,
                         String stdout, String stderr, String crashDiagnosis) {
            this.compiled = compiled;
            this.exitCode = exitCode;
            this.elapsedMs = elapsedMs;
            this.stdout = stdout;
            this.stderr = stderr;
            this.crashDiagnosis = crashDiagnosis;
        }
    }

    public RunResult compileAndRun(String sourceCode) {
        return compileAndRun(sourceCode, "");
    }

    public RunResult compileAndRun(String sourceCode, String stdinInput) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new RunResult(false, -1, 0, "",
                    "Cannot execute code: JDK compiler (javac) not found. Please run using a full JDK (11/17/21).",
                    "Install/select a JDK instead of a JRE-only runtime.");
        }

        String className = CompilerEngine.extractClassName(sourceCode);
        File tempDir = null;

        try {
            tempDir = Files.createTempDirectory("javalens_run_").toFile();
            DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
            StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, null);
            JavaFileObject src = new CompilerEngine.InMemoryJavaSource(className, sourceCode);

            List<String> opts = Arrays.asList("-d", tempDir.getAbsolutePath());
            boolean ok = Boolean.TRUE.equals(
                    compiler.getTask(null, fm, diags, opts, null, Collections.singletonList(src)).call());
            fm.close();

            if (!ok) {
                StringBuilder err = new StringBuilder("Compilation failed - fix errors before running:\n");
                for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                    err.append(String.format("  Line %d: %s%n", d.getLineNumber(), d.getMessage(Locale.ENGLISH)));
                }
                return new RunResult(false, 1, 0, "", err.toString(),
                        "Use 'Auto-Fix All' or inspect the Fixes panel to repair syntax errors first.");
            }

            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", tempDir.getAbsolutePath(), className);
            long start = System.currentTimeMillis();
            Process proc = pb.start();

            // Drain stdout and stderr concurrently so partial output before a deadlock/timeout is preserved
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            Thread outThread = startStreamDrainer(proc.getInputStream(), outBuf);
            Thread errThread = startStreamDrainer(proc.getErrorStream(), errBuf);

            // Feed stdin (if any) and close stream so Scanner/BufferedReader does not hang forever
            try (OutputStream stdinOs = proc.getOutputStream()) {
                if (stdinInput != null && !stdinInput.isEmpty()) {
                    stdinOs.write(stdinInput.getBytes(StandardCharsets.UTF_8));
                    if (!stdinInput.endsWith("\n")) {
                        stdinOs.write('\n');
                    }
                    stdinOs.flush();
                }
            } catch (IOException ignored) {
                // Child process may have exited before reading stdin
            }

            boolean finished = proc.waitFor(5, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                String stackHint = captureJstackSummary(proc);
                proc.destroyForcibly();
                joinQuietly(outThread, 300);
                joinQuietly(errThread, 300);
                String partialOut = new String(outBuf.toByteArray(), StandardCharsets.UTF_8);
                String correlated = correlateOutputWithRootCauses(sourceCode, partialOut, stackHint, true);
                return new RunResult(true, -1, elapsed, partialOut,
                        "Execution timed out after 5 seconds (possible Deadlock or Infinite Loop).",
                        correlated);
            }

            joinQuietly(outThread, 500);
            joinQuietly(errThread, 500);
            String out = new String(outBuf.toByteArray(), StandardCharsets.UTF_8);
            String err = new String(errBuf.toByteArray(), StandardCharsets.UTF_8);
            String baseDiagnosis = diagnoseRuntimeCrash(err);
            String correlated = correlateOutputWithRootCauses(sourceCode, out, baseDiagnosis, false);

            return new RunResult(true, proc.exitValue(), elapsed, out, err, correlated);
        } catch (Exception e) {
            return new RunResult(false, -1, 0, "", "Runner error: " + e.getMessage(),
                    "Ensure the class contains: public static void main(String[] args)");
        } finally {
            CompilerEngine.deleteQuietly(tempDir);
        }
    }

    /**
     * Attempts a lightweight jstack snapshot on timeout to identify the exact stuck line in user code.
     */
    private static String captureJstackSummary(Process proc) {
        try {
            long pid = proc.pid();
            String jstackBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "jstack";
            if (!new File(jstackBin).exists()) return "";
            Process js = new ProcessBuilder(jstackBin, String.valueOf(pid)).start();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread drainer = startStreamDrainer(js.getInputStream(), buf);
            if (js.waitFor(1200, TimeUnit.MILLISECONDS)) {
                joinQuietly(drainer, 300);
                String dump = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                if (dump.contains("Found one Java-level deadlock")) {
                    return "JVM Thread Dump (jstack): Confirmed Java-level Deadlock detected in child JVM!";
                }
                for (String line : dump.split("\\r?\\n")) {
                    if (line.trim().startsWith("at ") && !line.contains("java.") && !line.contains("jdk.")) {
                        return "JVM Thread Dump (jstack): Thread stuck " + line.trim();
                    }
                }
            } else {
                js.destroyForcibly();
            }
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * Correlates live runtime symptoms (negative overflow, timeout/infinite loop, null, false, NaN, wrong counts)
     * directly back to static/semantic/concurrency/deep root causes so ALL bugs are surfaced.
     */
    private String correlateOutputWithRootCauses(String sourceCode, String stdout,
                                                 String baseDiagnosis, boolean timedOut) {
        List<String> notes = new ArrayList<>();
        if (baseDiagnosis != null && !baseDiagnosis.trim().isEmpty()) {
            notes.add(baseDiagnosis.trim());
        }

        List<DiagnosticItem> analyzed = new ArrayList<>();
        analyzed.addAll(deepBugAnalyzer.analyze(sourceCode));
        analyzed.addAll(concurrencyAnalyzer.analyze(sourceCode));
        analyzed.addAll(semanticAnalyzer.analyze(sourceCode));
        analyzed.addAll(securityScanner.scan(sourceCode));

        String outLower = stdout == null ? "" : stdout.toLowerCase(Locale.ENGLISH);
        boolean explainedTimeout = false;

        for (DiagnosticItem d : analyzed) {
            String id = d.getRuleId();

            if ("OVF-001".equals(id)) {
                String tag = (stdout != null && NEGATIVE_INT_OUT.matcher(stdout).find())
                        ? "Confirmed IntegerOverflow (Negative/Wrapped Output)"
                        : "IntegerOverflow Risk";
                notes.add("Root-Cause Link (" + tag + ") [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("LOOP-001".equals(id)) {
                if (timedOut) explainedTimeout = true;
                notes.add("Root-Cause Link (Confirmed InfiniteLoop Timeout) [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("CONC-003".equals(id) || "CONC-004".equals(id) || "SEC-003".equals(id)) {
                notes.add("Root-Cause Link (" + d.getTitle() + ") [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("SEM-007".equals(id) && (outLower.contains("null") || outLower.contains("false"))) {
                notes.add("Root-Cause Link (Map/Set returned null or false) [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("CONC-002".equals(id)) {
                notes.add("Root-Cause Link (Corrupted / Lost Count) [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("CONC-001".equals(id) && timedOut) {
                explainedTimeout = true;
                notes.add("Root-Cause Link (Confirmed Deadlock Timeout) [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("SEM-001".equals(id) && outLower.contains("false")) {
                notes.add("Root-Cause Link (Unexpected 'false' in Output) [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("SEM-002".equals(id) && !outLower.isEmpty()) {
                notes.add("Root-Cause Link (Boxed Wrapper '==' Trap) [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            } else if ("SEM-005".equals(id) && outLower.contains("nan")) {
                notes.add("Root-Cause Link ('NaN' in Output) [Line " + d.getLine() + "]: "
                        + d.getMessage() + " Fix: " + d.getSuggestion());
            }
        }

        if (timedOut && !explainedTimeout) {
            notes.add("Runtime Fix (Timeout / Deadlock): Program hung for >5 seconds. Check for circular synchronized locks (deadlock) or an infinite while/for loop.");
        }

        return String.join("\n>> ", notes);
    }

    private static Thread startStreamDrainer(InputStream is, ByteArrayOutputStream target) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            int total = 0;
            final int maxBytes = 256 * 1024;
            try {
                int n;
                while ((n = is.read(buf)) != -1) {
                    if (total < maxBytes) {
                        int writeLen = Math.min(n, maxBytes - total);
                        target.write(buf, 0, writeLen);
                        total += writeLen;
                    }
                }
            } catch (IOException ignored) {
                // Stream closed cleanly when process exits or is forcibly terminated on timeout
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t, long ms) {
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String diagnoseRuntimeCrash(String stderr) {
        if (stderr == null || stderr.trim().isEmpty()) return "";
        if (stderr.contains("NoSuchElementException")) {
            return "Runtime Fix (NoSuchElementException): Your program tried to read from Scanner/System.in, but no input was provided. Enter sample lines in the 'Program Input (stdin)' box and click Run again.";
        }
        if (stderr.contains("InputMismatchException")) {
            return "Runtime Fix (InputMismatchException): Scanner received text that does not match the expected type (e.g. words when nextInt() expected a number). Check your Program Input (stdin).";
        }
        if (stderr.contains("ArrayIndexOutOfBoundsException")) {
            return "Runtime Fix (ArrayIndexOutOfBoundsException): Your loop or index exceeded array.length - 1. Change '<= arr.length' to '< arr.length'.";
        }
        if (stderr.contains("ConcurrentModificationException")) {
            return "Runtime Fix (ConcurrentModificationException): You modified a collection while looping over it with for-each. Use Iterator.remove() or removeIf().";
        }
        if (stderr.contains("NullPointerException")) {
            return "Runtime Fix (NullPointerException): You called a method or field on a null object. Initialize it first or check 'if (obj != null)'.";
        }
        if (stderr.contains("ArithmeticException")) {
            return "Runtime Fix (ArithmeticException): Integer division or modulo by zero occurred. Guard the divisor with 'if (b != 0)'.";
        }
        if (stderr.contains("NumberFormatException")) {
            return "Runtime Fix (NumberFormatException): Tried to parse non-numeric text into a number. Validate input or wrap in try/catch.";
        }
        if (stderr.contains("StackOverflowError")) {
            return "Runtime Fix (StackOverflowError): Infinite recursion detected. Verify your recursive method has a reachable base case.";
        }
        if (stderr.contains("Main method not found")) {
            return "Runtime Fix: Add 'public static void main(String[] args)' inside your primary class.";
        }
        return "Runtime Exception detected - inspect the stack trace line number above.";
    }
}
