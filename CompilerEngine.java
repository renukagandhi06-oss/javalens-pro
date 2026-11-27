import javax.tools.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Real In-Memory JDK Compiler Engine
 * Uses javax.tools.JavaCompiler to detect 100% genuine Java syntax, type, and symbol errors,
 * and translates cryptic javac diagnostics into plain-English suggestions.
 */
public class CompilerEngine {

    private static final Pattern PUBLIC_CLASS_PATTERN =
            Pattern.compile("\\bpublic\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern ANY_CLASS_PATTERN =
            Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");

    public static String extractClassName(String sourceCode) {
        Matcher pub = PUBLIC_CLASS_PATTERN.matcher(sourceCode);
        if (pub.find()) {
            return pub.group(1);
        }
        Matcher any = ANY_CLASS_PATTERN.matcher(sourceCode);
        if (any.find()) {
            return any.group(1);
        }
        return "UserProgram";
    }

    public List<DiagnosticItem> compileAndDiagnose(String sourceCode) {
        List<DiagnosticItem> results = new ArrayList<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            results.add(new DiagnosticItem(
                    1, 1, DiagnosticItem.Severity.INFO, DiagnosticItem.Category.COMPILER,
                    "JDK-INFO", "JDK Compiler Not Found (JRE Mode)",
                    "Running on a JRE without javac - Static Analyzer rules are still active.",
                    "Run JavaLens Pro using a full JDK (e.g., OpenJDK 17 or 21) to unlock live javac compilation."
            ));
            return results;
        }

        String className = extractClassName(sourceCode);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        File tempDir = null;

        try {
            tempDir = Files.createTempDirectory("javalens_compile_").toFile();
            StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);
            JavaFileObject sourceObj = new InMemoryJavaSource(className, sourceCode);

            List<String> options = Arrays.asList("-d", tempDir.getAbsolutePath(), "-Xlint:none");
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, options, null, Collections.singletonList(sourceObj));
            task.call();
            fileManager.close();

            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                int line = d.getLineNumber() > 0 ? (int) d.getLineNumber() : 1;
                int col = d.getColumnNumber() > 0 ? (int) d.getColumnNumber() : 1;
                DiagnosticItem.Severity sev = (d.getKind() == Diagnostic.Kind.ERROR)
                        ? DiagnosticItem.Severity.ERROR
                        : DiagnosticItem.Severity.WARNING;

                String rawMsg = d.getMessage(Locale.ENGLISH);
                String[] translated = translateJavacMessage(rawMsg, sourceCode);

                results.add(new DiagnosticItem(
                        line, col, sev, DiagnosticItem.Category.COMPILER,
                        "JAVAC", translated[0], rawMsg.replace("\n", " | "), translated[1]
                ));
            }
        } catch (Exception e) {
            results.add(new DiagnosticItem(
                    1, 1, DiagnosticItem.Severity.ERROR, DiagnosticItem.Category.COMPILER,
                    "JAVAC-EX", "Compiler Invocation Error", e.getMessage(),
                    "Ensure your code defines a valid Java class and try again."
            ));
        } finally {
            deleteQuietly(tempDir);
        }

        return results;
    }

    private String[] translateJavacMessage(String raw, String sourceCode) {
        String lower = raw.toLowerCase(Locale.ENGLISH);

        if (lower.contains("';' expected")) {
            return new String[]{
                    "Missing Semicolon",
                    "Add a semicolon ';' at the end of this statement (or check the previous line)."
            };
        }
        if (lower.contains("cannot find symbol")) {
            Matcher sym = Pattern.compile("symbol:\\s+(?:variable|class|method)\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(raw);
            if (sym.find()) {
                String missing = sym.group(1);
                if (missing.equals("Scanner") || missing.equals("ArrayList") || missing.equals("HashMap")
                        || missing.equals("Arrays") || missing.equals("List") || missing.equals("Map")) {
                    return new String[]{
                            "Missing Import for '" + missing + "'",
                            "Add 'import java.util.*;' at the very top of your file."
                    };
                }
                String closest = StaticAnalyzer.findClosestIdentifier(missing, sourceCode);
                if (closest != null) {
                    return new String[]{
                            "Undefined Symbol '" + missing + "' (Possible Typo)",
                            "Did you mean '" + closest + "'? Check spelling and case sensitivity."
                    };
                }
                return new String[]{
                        "Undefined Symbol '" + missing + "'",
                        "Declare '" + missing + "' before using it, or import its package."
                };
            }
            return new String[]{
                    "Undefined Symbol",
                    "Check variable/method spelling, declaration scope, or missing import statements."
            };
        }
        if (lower.contains("incompatible types")) {
            return new String[]{
                    "Type Mismatch",
                    "Convert/cast the value to match the declared type (e.g., Integer.parseInt(), (int), or String.valueOf())."
            };
        }
        if (lower.contains("reached end of file while parsing")) {
            return new String[]{
                    "Unclosed Brace '{'",
                    "You are missing one or more closing '}' braces at the end of a method or class."
            };
        }
        if (lower.contains("non-static") && lower.contains("static context")) {
            return new String[]{
                    "Static vs Instance Context Error",
                    "Either mark the variable/method as 'static' or create an object of the class first (new ClassName())."
            };
        }
        if (lower.contains("might not have been initialized")) {
            return new String[]{
                    "Uninitialized Local Variable",
                    "Assign an initial value (like 0, \"\", or null) when declaring this local variable."
            };
        }
        if (lower.contains("unreported exception")) {
            return new String[]{
                    "Unhandled Checked Exception",
                    "Wrap this call in a try { ... } catch (Exception e) { ... } block or add 'throws Exception' to the method."
            };
        }
        if (lower.contains("missing return statement")) {
            return new String[]{
                    "Missing Return Statement",
                    "Ensure every execution path in this non-void method ends with a 'return' statement."
            };
        }
        return new String[]{
                "Java Compilation Error",
                "Inspect the highlighted line and column to fix the syntax or type mismatch."
        };
    }

    public static void deleteQuietly(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                deleteQuietly(f);
            }
        }
        dir.delete();
    }

    /**
     * In-memory JavaFileObject so javac compiles directly from a String without writing source files.
     */
    public static class InMemoryJavaSource extends SimpleJavaFileObject {
        private final String code;

        public InMemoryJavaSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }
}
