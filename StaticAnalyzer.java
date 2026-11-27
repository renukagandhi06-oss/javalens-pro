import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Custom Static Code Analyzer & Levenshtein Typo Engine
 * Detects logic bugs, OOP pitfalls, resource leaks, and runtime risks that javac alone misses.
 */
public class StaticAnalyzer {

    private static final List<String> COMMON_JAVA_WORDS = Arrays.asList(
            "System", "String", "Scanner", "ArrayList", "HashMap", "Integer", "Double", "Boolean",
            "Thread", "Runnable", "Exception", "public", "private", "protected", "static", "final",
            "void", "return", "class", "interface", "extends", "implements", "println", "print",
            "length", "equals", "toString", "start", "close", "main", "args"
    );

    public List<DiagnosticItem> analyze(String sourceCode) {
        List<DiagnosticItem> items = new ArrayList<>();
        String[] lines = sourceCode.split("\\r?\\n", -1);

        boolean hasUtilImport = sourceCode.contains("import java.util.");
        boolean usesUtilClass = Pattern.compile("\\b(Scanner|ArrayList|HashMap|Arrays|Collections|LinkedList)\\b")
                .matcher(sourceCode).find();
        boolean hasScanner = sourceCode.contains("new Scanner(");
        boolean closesScanner = sourceCode.contains(".close()");

        if (usesUtilClass && !hasUtilImport) {
            items.add(new DiagnosticItem(
                    1, 1, DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                    "JL-01", "Missing java.util Import",
                    "Utility class (Scanner/ArrayList/HashMap/Arrays) used without importing java.util.*",
                    "Add 'import java.util.*;' at line 1 (Auto-Fix can add this automatically)."
            ));
        }

        Set<String> nullVars = new HashSet<>();
        int braceBalance = 0;

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String raw = lines[i];
            String trimmed = stripLineComment(raw).trim();
            if (trimmed.isEmpty()) continue;

            // Track brace balance
            for (char c : trimmed.toCharArray()) {
                if (c == '{') braceBalance++;
                else if (c == '}') braceBalance--;
            }

            // JL-02: String == or != comparison
            if (Pattern.compile("(\"[^\"]*\"\\s*(==|!=)|\\b(==|!=)\\s*\"[^\"]*\")").matcher(trimmed).find()) {
                int col = Math.max(1, raw.indexOf("==") >= 0 ? raw.indexOf("==") + 1 : raw.indexOf("!=") + 1);
                items.add(new DiagnosticItem(
                        lineNum, col, DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-02", "String Compared Using == / !=",
                        "Using '==' compares memory references, not text content, causing subtle bugs.",
                        "Use str.equals(\"text\") instead of str == \"text\" (Auto-Fix supported)."
                ));
            }

            // JL-03: Accidental semicolon after for / while / if
            Matcher semiLoop = Pattern.compile("\\b(for|while|if)\\s*\\([^)]*\\)\\s*;").matcher(trimmed);
            if (semiLoop.find()) {
                items.add(new DiagnosticItem(
                        lineNum, raw.indexOf(';') + 1, DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-03", "Accidental Semicolon After '" + semiLoop.group(1) + "'",
                        "A trailing ';' creates an empty body and disconnects the next code block from the condition.",
                        "Remove the ';' right after the closing ')' (Auto-Fix supported)."
                ));
            }

            // JL-04: Infinite loop while(true) without break/return
            if (Pattern.compile("\\bwhile\\s*\\(\\s*true\\s*\\)").matcher(trimmed).find()
                    && !sourceCode.contains("break;") && !sourceCode.contains("return")) {
                items.add(new DiagnosticItem(
                        lineNum, 1, DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-04", "Potential Infinite Loop",
                        "'while (true)' found with no 'break;' or 'return' exit path in the file.",
                        "Add a clear termination condition or 'break;' inside the loop."
                ));
            }

            // JL-05: ArrayIndexOutOfBounds pattern (<= arr.length)
            Matcher arrBound = Pattern.compile("<=\\s*[A-Za-z_][A-Za-z0-9_]*\\.length\\b").matcher(trimmed);
            if (arrBound.find()) {
                items.add(new DiagnosticItem(
                        lineNum, Math.max(1, raw.indexOf("<=") + 1),
                        DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-05", "Off-By-One Array Bound Risk",
                        "Loop uses '<= array.length', which accesses index equal to length and throws ArrayIndexOutOfBoundsException.",
                        "Change '<=' to '<' (e.g., i < arr.length) (Auto-Fix supported)."
                ));
            }

            // JL-06: Calling Thread.run() directly instead of start()
            if (Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\.run\\s*\\(\\s*\\)\\s*;").matcher(trimmed).find()
                    && (sourceCode.contains("extends Thread") || sourceCode.contains("new Thread"))) {
                items.add(new DiagnosticItem(
                        lineNum, Math.max(1, raw.indexOf(".run") + 1),
                        DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-06", "Thread.run() Called Directly",
                        "Calling '.run()' executes synchronously on the current thread instead of starting a new thread.",
                        "Replace '.run()' with '.start()' to spawn a real concurrent thread (Auto-Fix supported)."
                ));
            }

            // JL-07: Division by literal zero
            if (Pattern.compile("[/%]\\s*0(?![0-9.])").matcher(trimmed).find()) {
                items.add(new DiagnosticItem(
                        lineNum, Math.max(1, raw.indexOf('/') + 1),
                        DiagnosticItem.Severity.ERROR, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-07", "Division by Zero Detected",
                        "Dividing an integer by literal 0 throws ArithmeticException at runtime.",
                        "Check that the divisor is non-zero before dividing (e.g., if (b != 0))."
                ));
            }

            // JL-08: Empty catch block
            if (Pattern.compile("\\bcatch\\s*\\([^)]*\\)\\s*\\{\\s*\\}").matcher(trimmed).find()) {
                items.add(new DiagnosticItem(
                        lineNum, Math.max(1, raw.indexOf("catch") + 1),
                        DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-08", "Empty Catch Block Swallows Error",
                        "An empty catch block hides runtime failures and makes debugging difficult.",
                        "Log the error inside catch, e.g., System.err.println(e.getMessage());"
                ));
            }

            // JL-09: Common System.out / println typos
            if (trimmed.contains("Systm.") || trimmed.contains("pritnln") || trimmed.contains("printn(")) {
                items.add(new DiagnosticItem(
                        lineNum, 1, DiagnosticItem.Severity.ERROR, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-09", "Typo in System.out.println",
                        "Detected misspelled console output statement on this line.",
                        "Change to 'System.out.println(...)' (Auto-Fix supported)."
                ));
            }

            // JL-10: Potential NullPointerException tracking
            Matcher nullDecl = Pattern.compile("\\b(?:String|[A-Z][A-Za-z0-9_]*)\\s+([a-z][A-Za-z0-9_]*)\\s*=\\s*null\\s*;").matcher(trimmed);
            if (nullDecl.find()) {
                nullVars.add(nullDecl.group(1));
            } else {
                for (String nv : new ArrayList<>(nullVars)) {
                    if (Pattern.compile("\\b" + nv + "\\s*=\\s*[^n]").matcher(trimmed).find()) {
                        nullVars.remove(nv);
                    } else if (Pattern.compile("\\b" + nv + "\\.[A-Za-z_]").matcher(trimmed).find()
                            && !trimmed.contains(nv + " != null")) {
                        items.add(new DiagnosticItem(
                                lineNum, Math.max(1, raw.indexOf(nv) + 1),
                                DiagnosticItem.Severity.WARNING, DiagnosticItem.Category.STATIC_ANALYSIS,
                                "JL-10", "Potential NullPointerException on '" + nv + "'",
                                "Variable '" + nv + "' was assigned null and dereferenced without a null check.",
                                "Initialize '" + nv + "' or guard with: if (" + nv + " != null) { ... }"
                        ));
                    }
                }
            }

            // JL-11: Redundant new String("...")
            if (Pattern.compile("new\\s+String\\s*\\(\\s*\"[^\"]*\"\\s*\\)").matcher(trimmed).find()) {
                items.add(new DiagnosticItem(
                        lineNum, Math.max(1, raw.indexOf("new String") + 1),
                        DiagnosticItem.Severity.INFO, DiagnosticItem.Category.STATIC_ANALYSIS,
                        "JL-11", "Redundant String Constructor",
                        "new String(\"...\") allocates an extra heap object instead of using Java's String pool.",
                        "Use the string literal directly: String s = \"hello\";"
                ));
            }

            // JL-12: Missing @Override on standard overridden methods
            if (Pattern.compile("public\\s+(?:String\\s+toString|void\\s+run)\\s*\\(\\s*\\)").matcher(trimmed).find()) {
                String prevLine = (i > 0) ? lines[i - 1].trim() : "";
                if (!prevLine.contains("@Override")) {
                    items.add(new DiagnosticItem(
                            lineNum, 1, DiagnosticItem.Severity.INFO, DiagnosticItem.Category.STATIC_ANALYSIS,
                            "JL-12", "Missing @Override Annotation",
                            "Overriding toString() or run() without @Override misses compile-time signature verification.",
                            "Add '@Override' on the line directly above this method."
                    ));
                }
            }
        }

        // JL-13: Unclosed Scanner resource leak
        if (hasScanner && !closesScanner) {
            items.add(new DiagnosticItem(
                    1, 1, DiagnosticItem.Severity.INFO, DiagnosticItem.Category.STATIC_ANALYSIS,
                    "JL-13", "Unclosed Scanner Resource",
                    "A Scanner is opened but never closed with '.close()'.",
                    "Close the Scanner at the end of main() or use try-with-resources."
            ));
        }

        // JL-14: Unbalanced braces summary
        if (braceBalance != 0) {
            items.add(new DiagnosticItem(
                    lines.length, 1, DiagnosticItem.Severity.ERROR, DiagnosticItem.Category.STATIC_ANALYSIS,
                    "JL-14", "Unbalanced Curly Braces '{}'",
                    braceBalance > 0
                            ? ("Missing " + braceBalance + " closing '}' brace(s).")
                            : ("Found " + Math.abs(braceBalance) + " extra closing '}' brace(s)."),
                    "Match every opening '{' with a closing '}'."
            ));
        }

        return items;
    }

    /**
     * Finds the closest matching Java keyword or declared identifier using Levenshtein edit distance.
     */
    public static String findClosestIdentifier(String target, String sourceCode) {
        Set<String> candidates = new LinkedHashSet<>(COMMON_JAVA_WORDS);
        Matcher m = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]{2,})\\b").matcher(sourceCode);
        while (m.find()) {
            String w = m.group(1);
            if (!w.equals(target)) {
                candidates.add(w);
            }
        }

        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String cand : candidates) {
            int d = levenshtein(target.toLowerCase(Locale.ENGLISH), cand.toLowerCase(Locale.ENGLISH));
            if (d < bestDist && d <= Math.max(2, target.length() / 3)) {
                bestDist = d;
                best = cand;
            }
        }
        return best;
    }

    /**
     * Pure Java Dynamic Programming Levenshtein Edit Distance Algorithm.
     */
    public static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
