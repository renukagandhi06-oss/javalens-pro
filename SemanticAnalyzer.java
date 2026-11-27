import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Deep Semantic & Subtle-Bug Analyzer (Pure Java OOP)
 * Tracks variable types across methods to catch complex Java bugs that javac ignores:
 *  - SEM-001: Floating-point equality comparison (float/double == or !=)
 *  - SEM-002: Boxed wrapper equality trap (Integer/Long/Double == outside -128..127 cache)
 *  - SEM-003: Integer division truncation into double/float
 *  - SEM-004: ConcurrentModificationException (removing inside for-each loop)
 *  - SEM-005: Unchecked empty-collection division (/ list.size() or / arr.length)
 *  - SEM-006: Wrong-direction loop counter or unguarded infinite loop
 */
public class SemanticAnalyzer {

    private static final Pattern FLOAT_VAR_DECL = Pattern.compile(
            "\\b(?:double|float)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern INT_VAR_DECL = Pattern.compile(
            "\\b(?:int|long|short)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern WRAPPER_VAR_DECL = Pattern.compile(
            "\\b(Integer|Long|Double|Float|Short|Character|Boolean)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern WRAPPER_LIST_DECL = Pattern.compile(
            "\\b(?:List|ArrayList|LinkedList|Vector|Collection)\\s*<\\s*(Integer|Long|Double|Float|Short|String|Boolean)\\s*>\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern EQ_OP = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_.]*(?:\\([^()=!]*\\))?|\\d+\\.\\d+[fFdD]?)\\s*(==|!=)\\s*([A-Za-z_][A-Za-z0-9_.]*(?:\\([^()=!]*\\))?|\\d+\\.\\d+[fFdD]?)");
    private static final Pattern DECIMAL_LIT = Pattern.compile("^\\d+\\.\\d+[fFdD]?$");
    private static final Pattern INT_LIT = Pattern.compile("^\\d+$");
    private static final Pattern DOUBLE_DIV_ASSIGN = Pattern.compile(
            "\\b(?:double|float)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*([A-Za-z0-9_]+)\\s*/\\s*([A-Za-z0-9_]+)\\s*;");
    private static final Pattern FOR_EACH = Pattern.compile(
            "\\bfor\\s*\\([^:]+:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");
    private static final Pattern SIZE_DIV = Pattern.compile(
            "/\\s*([A-Za-z_][A-Za-z0-9_]*)\\.(size\\s*\\(\\s*\\)|length\\b)");
    private static final Pattern WRONG_FOR_DEC = Pattern.compile(
            "\\bfor\\s*\\([^;]*=\\s*0\\s*;[^;]*<[^;]*;[^)]*--\\s*\\)");

    public List<DiagnosticItem> analyze(String sourceCode) {
        List<DiagnosticItem> findings = new ArrayList<>();
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return findings;
        }

        String[] lines = sourceCode.split("\\r?\\n", -1);

        // Pass 1: Collect variable and generic collection types
        Set<String> floatVars = new HashSet<>();
        Set<String> intVars = new HashSet<>();
        Set<String> wrapperVars = new HashSet<>();
        Map<String, String> wrapperLists = new HashMap<>();

        for (String raw : lines) {
            String code = stripComment(raw);
            Matcher mf = FLOAT_VAR_DECL.matcher(code);
            while (mf.find()) floatVars.add(mf.group(1));

            Matcher mi = INT_VAR_DECL.matcher(code);
            while (mi.find()) intVars.add(mi.group(1));

            Matcher mw = WRAPPER_VAR_DECL.matcher(code);
            while (mw.find()) wrapperVars.add(mw.group(2));

            Matcher ml = WRAPPER_LIST_DECL.matcher(code);
            while (ml.find()) wrapperLists.put(ml.group(2), ml.group(1));
        }

        // Track for-each loop scope for ConcurrentModificationException
        String activeForEachCol = null;
        int forEachBraceDepth = -1;
        int braceDepth = 0;

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String code = stripComment(lines[i]);
            String trimmed = code.trim();
            if (trimmed.isEmpty()) continue;

            // Track for-each collection
            Matcher mfe = FOR_EACH.matcher(code);
            if (mfe.find()) {
                activeForEachCol = mfe.group(1);
                forEachBraceDepth = braceDepth + 1;
            }

            // SEM-001 & SEM-002: Subtle == / != comparisons
            Matcher meq = EQ_OP.matcher(code);
            while (meq.find()) {
                String lhs = meq.group(1).trim();
                String op = meq.group(2).trim();
                String rhs = meq.group(3).trim();

                if ("null".equals(lhs) || "null".equals(rhs)) continue;

                // SEM-001: Floating-point equality
                boolean lhsFloat = DECIMAL_LIT.matcher(lhs).matches() || floatVars.contains(lhs);
                boolean rhsFloat = DECIMAL_LIT.matcher(rhs).matches() || floatVars.contains(rhs);
                if (lhsFloat || rhsFloat) {
                    String fixExpr = "==".equals(op)
                            ? "Math.abs(" + lhs + " - " + rhs + ") < 1e-9"
                            : "Math.abs(" + lhs + " - " + rhs + ") >= 1e-9";
                    findings.add(new DiagnosticItem(
                            lineNum, meq.start() + 1,
                            DiagnosticItem.Severity.WARNING,
                            DiagnosticItem.Category.SEMANTIC,
                            "SEM-001",
                            "Floating-Point Equality Trap (" + op + ")",
                            "Comparing double/float values with '" + op + "' fails due to IEEE 754 binary rounding (e.g. (1.1+2.2+3.3)/3 is 2.1999999999999997, not 2.2).",
                            "Use an epsilon tolerance instead: " + fixExpr + " (Auto-Fix supported)."
                    ));
                    continue;
                }

                // SEM-002: Boxed wrapper equality (Integer, Long, Double, etc.)
                boolean lhsBoxed = isBoxedExpr(lhs, wrapperVars, wrapperLists);
                boolean rhsBoxed = isBoxedExpr(rhs, wrapperVars, wrapperLists);
                if (lhsBoxed && rhsBoxed) {
                    String fixExpr = "==".equals(op)
                            ? "Objects.equals(" + lhs + ", " + rhs + ")"
                            : "!Objects.equals(" + lhs + ", " + rhs + ")";
                    findings.add(new DiagnosticItem(
                            lineNum, meq.start() + 1,
                            DiagnosticItem.Severity.ERROR,
                            DiagnosticItem.Category.SEMANTIC,
                            "SEM-002",
                            "Boxed Wrapper '==' Identity Trap",
                            "Comparing boxed objects (" + lhs + " " + op + " " + rhs + ") checks memory references, not values. Java only caches Integer -128..127, so 200 == 200 or 300 == 300 evaluates to false!",
                            "Use value equality: " + fixExpr + " (Auto-Fix supported)."
                    ));
                }
            }

            // SEM-003: Integer division truncation assigned to double/float
            Matcher mdiv = DOUBLE_DIV_ASSIGN.matcher(code);
            if (mdiv.find()) {
                String a = mdiv.group(1);
                String b = mdiv.group(2);
                boolean aInt = INT_LIT.matcher(a).matches() || intVars.contains(a);
                boolean bInt = INT_LIT.matcher(b).matches() || intVars.contains(b);
                if (aInt && bInt && !floatVars.contains(a) && !floatVars.contains(b)) {
                    findings.add(new DiagnosticItem(
                            lineNum, mdiv.start() + 1,
                            DiagnosticItem.Severity.WARNING,
                            DiagnosticItem.Category.SEMANTIC,
                            "SEM-003",
                            "Integer Division Truncation",
                            "Dividing two integers (" + a + " / " + b + ") drops the decimal remainder before assigning to double/float (e.g. 5 / 2 becomes 2.0).",
                            "Cast one operand to double: (double) " + a + " / " + b + "."
                    ));
                }
            }

            // SEM-004: IteratorInvalidation / ConcurrentModificationException in for-each loop
            if (activeForEachCol != null && (code.contains(activeForEachCol + ".remove(") || code.contains(activeForEachCol + ".add("))) {
                boolean isAdd = code.contains(activeForEachCol + ".add(");
                String opName = isAdd ? ".add(" : ".remove(";
                findings.add(new DiagnosticItem(
                        lineNum, code.indexOf(activeForEachCol + opName) + 1,
                        DiagnosticItem.Severity.ERROR,
                        DiagnosticItem.Category.SEMANTIC,
                        "SEM-004",
                        "IteratorInvalidation (ConcurrentModificationException)",
                        "Cause: Calling " + activeForEachCol + opName + "...) while iterating with a for-each loop. Runtime Consequence: Fail-fast iterator immediately throws ConcurrentModificationException at runtime!",
                        isAdd ? "Collect new items in a separate temporary list and call " + activeForEachCol + ".addAll(toAdd) after the loop."
                              : "Use an explicit Iterator with it.remove(), or " + activeForEachCol + ".removeIf(item -> ...)."
                ));
            }

            // SEM-005: Unchecked division by collection.size() or array.length
            Matcher msz = SIZE_DIV.matcher(code);
            if (msz.find()) {
                String col = msz.group(1);
                boolean hasGuard = false;
                int lookbackStart = Math.max(0, i - 6);
                for (int k = lookbackStart; k <= i; k++) {
                    String prev = lines[k];
                    if (prev.contains(col + ".isEmpty()") || prev.contains(col + ".size() == 0")
                            || prev.contains(col + ".size() > 0") || prev.contains(col + ".length == 0")
                            || prev.contains(col + ".length > 0")) {
                        hasGuard = true;
                        break;
                    }
                }
                if (!hasGuard) {
                    findings.add(new DiagnosticItem(
                            lineNum, msz.start() + 1,
                            DiagnosticItem.Severity.WARNING,
                            DiagnosticItem.Category.SEMANTIC,
                            "SEM-005",
                            "Unchecked Empty Collection Division",
                            "Dividing by " + col + "." + msz.group(2) + " without checking if '" + col + "' is empty causes NaN (for double) or ArithmeticException (for int).",
                            "Guard before dividing: if (" + col + ".isEmpty()) return 0.0;"
                    ));
                }
            }

            // SEM-006: Wrong-direction for-loop counter
            Matcher mwf = WRONG_FOR_DEC.matcher(code);
            if (mwf.find()) {
                findings.add(new DiagnosticItem(
                        lineNum, mwf.start() + 1,
                        DiagnosticItem.Severity.ERROR,
                        DiagnosticItem.Category.SEMANTIC,
                        "SEM-006",
                        "Potential Infinite Loop (Counter Moving Away from Bound)",
                        "Loop starts at 0 with a '<' condition but decrements ('--'), which will run until integer underflow.",
                        "Change the loop update to '++' or fix the starting value and condition."
                ));
            }

            // Update brace depth
            for (int c = 0; c < code.length(); c++) {
                if (code.charAt(c) == '{') braceDepth++;
                else if (code.charAt(c) == '}') {
                    braceDepth--;
                    if (activeForEachCol != null && braceDepth < forEachBraceDepth) {
                        activeForEachCol = null;
                        forEachBraceDepth = -1;
                    }
                }
            }
        }

        return findings;
    }

    private static boolean isBoxedExpr(String expr, Set<String> wrapperVars, Map<String, String> wrapperLists) {
        if (wrapperVars.contains(expr)) return true;
        int dotGet = expr.indexOf(".get(");
        if (dotGet > 0) {
            String colName = expr.substring(0, dotGet).trim();
            if (wrapperLists.containsKey(colName)) return true;
        }
        return false;
    }

    private static String stripComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
