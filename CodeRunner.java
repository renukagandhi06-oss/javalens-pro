import javax.tools.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * JavaLens Pro - Live Output Terminal, Interactive Stdin & Runtime Crash Explainer
 * Compiles user Java code to a temp directory, executes it in a child JVM with a 5s safety timeout,
 * feeds optional Program Input (stdin) to Scanner/BufferedReader, captures stdout/stderr,
 * and translates runtime exceptions into plain-English fix advice.
 */
public class CodeRunner {

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
                proc.destroyForcibly();
                return new RunResult(true, -1, elapsed, readStream(proc.getInputStream()),
                        "Execution timed out after 5 seconds (possible infinite loop).",
                        "Check for infinite while/for loops or recursion without a base case.");
            }

            String out = readStream(proc.getInputStream());
            String err = readStream(proc.getErrorStream());
            String diagnosis = diagnoseRuntimeCrash(err);

            return new RunResult(true, proc.exitValue(), elapsed, out, err, diagnosis);
        } catch (Exception e) {
            return new RunResult(false, -1, 0, "", "Runner error: " + e.getMessage(),
                    "Ensure the class contains: public static void main(String[] args)");
        } finally {
            CompilerEngine.deleteQuietly(tempDir);
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

    private static String readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }
}
