import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Deep Static Bug Analyzer (16th Pure Java OOP Class)
 * Detects subtle algorithmic, numeric, and concurrency bugs statically with Cause + Runtime Consequence + Fix:
 *  - OVF-001 : Silent 32-bit Integer Overflow in multiplicative/additive loops
 *  - LOOP-001: Binary Search / Loop Variable Stagnation Infinite Loop (lo = mid instead of lo = mid + 1)
 *  - CONC-003: Double-Checked Locking (DCL) Singleton without volatile
 *  - CONC-004: Check-Then-Act Race (containsKey/get followed by put on shared Map in concurrent context)
 */
public class DeepBugAnalyzer {

    private static final Pattern INT_DECL = Pattern.compile(
            "\\bint\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=\\s*([^;]+))?\\s*;");
    private static final Pattern MULT_OP = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\*=\\s*(\\d+)|\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\3\\s*\\*\\s*(\\d+)");
    private static final Pattern LOOP_HDR = Pattern.compile("\\b(for|while)\\s*\\(([^)]*)\\)");
    private static final Pattern MID_CALC = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\+\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)\\s*(?:/\\s*2|>>>\\s*1)|([A-Za-z_][A-Za-z0-9_]*)\\s*\\+\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*-\\s*\\4\\s*\\)\\s*/\\s*2)");
    private static final Pattern FIELD_DECL = Pattern.compile(
            "\\b(?:private|public|protected|static|final|volatile|\\s)+([A-Za-z_][A-Za-z0-9_<>]*)\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;)");
    private static final Pattern CONCURRENT_CTX = Pattern.compile(
            "(?:\\.(?:submit|execute|parallelStream)\\s*\\(|\\bnew\\s+Thread\\s*\\(|\\bRunnable\\b|\\bCallable\\b)");

    public List<DiagnosticItem> analyze(String sourceCode) {
        List<DiagnosticItem> findings = new ArrayList<>();
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return findings;
        }
        String[] lines = sourceCode.split("\\r?\\n", -1);

        detectIntegerOverflow(lines, findings);
        detectBinarySearchInfiniteLoop(lines, findings);
        detectDclWithoutVolatile(sourceCode, lines, findings);
        detectCheckThenActRace(lines, findings);

        return findings;
    }

    /**
     * OVF-001: Detects 32-bit int multiplicative/exponential growth inside loops that overflows Integer.MAX_VALUE.
     */
    private void detectIntegerOverflow(String[] lines, List<DiagnosticItem> findings) {
        Map<String, Long> intInitVals = new HashMap<>();
        Set<String> intVars = new HashSet<>();

        for (String raw : lines) {
            String s = stripComment(raw);
            Matcher m = INT_DECL.matcher(s);
            while (m.find()) {
                String var = m.group(1);
                intVars.add(var);
                String init = m.group(2);
                if (init != null) {
                    try {
                        intInitVals.put(var, Long.parseLong(init.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        int braceDepth = 0;
        Deque<Integer> loopDepths = new ArrayDeque<>();
        Deque<Integer> loopIters = new ArrayDeque<>();
        Set<String> reportedVars = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String s = stripComment(lines[i]);

            Matcher lh = LOOP_HDR.matcher(s);
            boolean singleLineLoop = false;
            int currentLoopIters = -1;
            if (lh.find()) {
                currentLoopIters = extractForLoopCount(lh.group(2));
                if (s.contains("{")) {
                    loopDepths.push(braceDepth + 1);
                    loopIters.push(currentLoopIters);
                } else {
                    singleLineLoop = true;
                }
            }

            boolean insideLoop = !loopDepths.isEmpty() || singleLineLoop;
            if (insideLoop) {
                Matcher mo = MULT_OP.matcher(s);
                if (mo.find()) {
                    String var = mo.group(1) != null ? mo.group(1) : mo.group(3);
                    String factorStr = mo.group(2) != null ? mo.group(2) : mo.group(4);
                    if (intVars.contains(var) && reportedVars.add(var)) {
                        long factor = 2;
                        try { factor = Long.parseLong(factorStr); } catch (NumberFormatException ignored) {}
                        int iters = singleLineLoop ? currentLoopIters : (loopIters.isEmpty() ? -1 : loopIters.peek());
                        long initVal = intInitVals.getOrDefault(var, 1L);

                        String expectedDetail = "";
                        if (iters > 0 && factor >= 2 && initVal > 0) {
                            long val = initVal;
                            boolean overflowed = false;
                            for (int k = 0; k < iters; k++) {
                                if (val > Integer.MAX_VALUE / factor) {
                                    overflowed = true;
                                }
                                if (val <= Long.MAX_VALUE / factor) {
                                    val *= factor;
                                }
                            }
                            if (overflowed) {
                                expectedDetail = " After " + iters + " iterations, expected value (" + val
                                        + ") exceeds Integer.MAX_VALUE (2,147,483,647) and wraps silently into a wrong/negative 32-bit int!";
                            }
                        }

                        findings.add(new DiagnosticItem(
                                lineNum, mo.start() + 1,
                                DiagnosticItem.Severity.ERROR,
                                DiagnosticItem.Category.SEMANTIC,
                                "OVF-001",
                                "IntegerOverflow on '" + var + "'",
                                "Cause: 32-bit int '" + var + "' is multiplied by " + factor + " inside a loop without overflow checks. Runtime Consequence: Silent 32-bit signed integer wraparound past 2^31-1 (produces corrupted or negative numbers)." + expectedDetail,
                                "Change '" + var + "' from int to long or BigInteger, or use Math.multiplyExact(" + var + ", " + factor + ") to fail fast on overflow."
                        ));
                    }
                }
            }

            for (int c = 0; c < s.length(); c++) {
                char ch = s.charAt(c);
                if (ch == '{') braceDepth++;
                else if (ch == '}') {
                    braceDepth--;
                    while (!loopDepths.isEmpty() && braceDepth < loopDepths.peek()) {
                        loopDepths.pop();
                        if (!loopIters.isEmpty()) loopIters.pop();
                    }
                }
            }
        }
    }

    /**
     * LOOP-001: Detects binary search infinite loop where lo = mid (instead of lo = mid + 1) stagnates when lo + 1 == hi.
     */
    private void detectBinarySearchInfiniteLoop(String[] lines, List<DiagnosticItem> findings) {
        int n = lines.length;
        for (int i = 0; i < n; i++) {
            String s = stripComment(lines[i]);
            Matcher mw = Pattern.compile("\\bwhile\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*<\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\)").matcher(s);
            if (!mw.find()) continue;

            String loVar = mw.group(1);
            String hiVar = mw.group(2);
            int depth = 0;
            boolean entered = false;
            String midVar = null;

            for (int j = i; j < n; j++) {
                String cur = stripComment(lines[j]);
                if (j > i && entered) {
                    Matcher mm = MID_CALC.matcher(cur);
                    if (mm.find()) {
                        midVar = mm.group(1);
                    }
                    if (midVar != null) {
                        Pattern stagnate = Pattern.compile("\\b" + Pattern.quote(loVar) + "\\s*=\\s*" + Pattern.quote(midVar) + "\\s*;");
                        Matcher ms = stagnate.matcher(cur);
                        if (ms.find()) {
                            findings.add(new DiagnosticItem(
                                    j + 1, ms.start() + 1,
                                    DiagnosticItem.Severity.ERROR,
                                    DiagnosticItem.Category.SEMANTIC,
                                    "LOOP-001",
                                    "InfiniteLoop (Binary Search Stagnation on '" + loVar + "')",
                                    "Cause: '" + loVar + " = " + midVar + ";' inside 'while (" + loVar + " < " + hiVar
                                            + ")' with integer-floored midpoint '" + midVar + "'. Runtime Consequence: When "
                                            + loVar + " + 1 == " + hiVar + ", " + midVar + " equals " + loVar
                                            + ", so '" + loVar + "' never advances and the loop hangs forever!",
                                    "Change '" + loVar + " = " + midVar + ";' to '" + loVar + " = " + midVar + " + 1;' so the search space strictly shrinks."
                            ));
                        }
                    }
                }
                for (int c = 0; c < cur.length(); c++) {
                    char ch = cur.charAt(c);
                    if (ch == '{') { depth++; entered = true; }
                    else if (ch == '}') depth--;
                }
                if (entered && depth == 0) break;
            }
        }
    }

    /**
     * CONC-003: Detects Double-Checked Locking (null check -> synchronized -> null check -> assign) without volatile.
     */
    private void detectDclWithoutVolatile(String sourceCode, String[] lines, List<DiagnosticItem> findings) {
        Map<String, Boolean> fieldVolatile = new HashMap<>();
        Map<String, Integer> fieldLine = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String s = stripComment(lines[i]);
            if (s.contains("(") && !s.contains("=")) continue; // skip method headers
            Matcher mf = FIELD_DECL.matcher(s);
            if (mf.find()) {
                String varName = mf.group(2);
                fieldVolatile.put(varName, s.contains("volatile"));
                fieldLine.put(varName, i + 1);
            }
        }

        Pattern nullCheck = Pattern.compile("\\bif\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*null\\s*\\)");
        int n = lines.length;
        Set<String> reported = new HashSet<>();

        for (int i = 0; i < n; i++) {
            String s = stripComment(lines[i]);
            Matcher m1 = nullCheck.matcher(s);
            if (!m1.find()) continue;
            String field = m1.group(1);

            boolean sawSync = false;
            boolean sawSecondNullCheck = false;
            int syncLine = -1;
            int limit = Math.min(n, i + 12);
            for (int j = i + 1; j < limit; j++) {
                String cur = stripComment(lines[j]);
                if (cur.contains("synchronized")) {
                    sawSync = true;
                    syncLine = j + 1;
                }
                if (sawSync && cur.contains(field) && cur.contains("== null")) {
                    sawSecondNullCheck = true;
                    break;
                }
            }

            if (sawSync && sawSecondNullCheck && reported.add(field)) {
                boolean isVolatile = fieldVolatile.getOrDefault(field, false);
                if (!isVolatile) {
                    int reportLine = fieldLine.getOrDefault(field, syncLine > 0 ? syncLine : i + 1);
                    findings.add(new DiagnosticItem(
                            reportLine, 1,
                            DiagnosticItem.Severity.ERROR,
                            DiagnosticItem.Category.SEMANTIC,
                            "CONC-003",
                            "DCLWithoutVolatile on '" + field + "'",
                            "Cause: Double-Checked Locking pattern on '" + field + "' (Line " + (i + 1) + ") without the 'volatile' modifier. Runtime Consequence: JVM instruction reordering can publish a half-constructed object reference to another thread, causing intermittent crashes or corrupted state!",
                            "Add 'volatile' to the field declaration: private static volatile <Type> " + field + ";"
                    ));
                }
            }
        }
    }

    /**
     * CONC-004: Detects non-atomic Check-Then-Act (containsKey/get followed by put) on a shared Map in concurrent tasks.
     */
    private void detectCheckThenActRace(String[] lines, List<DiagnosticItem> findings) {
        int braceDepth = 0;
        boolean inConcurrent = false;
        int concurrentTarget = -1;
        Pattern checkPat = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*(?:containsKey|get)\\s*\\(");

        for (int i = 0; i < lines.length; i++) {
            String s = stripComment(lines[i]);
            if (!inConcurrent && CONCURRENT_CTX.matcher(s).find() && s.contains("{")) {
                inConcurrent = true;
                concurrentTarget = braceDepth + 1;
            }

            if (inConcurrent) {
                Matcher mc = checkPat.matcher(s);
                if (mc.find()) {
                    String mapVar = mc.group(1);
                    int limit = Math.min(lines.length, i + 8);
                    for (int j = i; j < limit; j++) {
                        String cur = stripComment(lines[j]);
                        if (cur.contains(mapVar + ".put(")) {
                            findings.add(new DiagnosticItem(
                                    i + 1, mc.start() + 1,
                                    DiagnosticItem.Severity.ERROR,
                                    DiagnosticItem.Category.SEMANTIC,
                                    "CONC-004",
                                    "CheckThenActRace on Shared Map '" + mapVar + "'",
                                    "Cause: Non-atomic check-then-act ('" + mapVar + ".containsKey/get' followed by '" + mapVar
                                            + ".put' on Line " + (j + 1) + ") inside a concurrent task. Runtime Consequence: Multiple threads can pass the check simultaneously and overwrite entries or corrupt a non-thread-safe HashMap!",
                                    "Use ConcurrentHashMap with atomic " + mapVar + ".computeIfAbsent(key, k -> value) or " + mapVar + ".putIfAbsent(key, value)."
                            ));
                            break;
                        }
                    }
                }
            }

            for (int c = 0; c < s.length(); c++) {
                char ch = s.charAt(c);
                if (ch == '{') braceDepth++;
                else if (ch == '}') {
                    braceDepth--;
                    if (inConcurrent && braceDepth < concurrentTarget) {
                        inConcurrent = false;
                        concurrentTarget = -1;
                    }
                }
            }
        }
    }

    private static int extractForLoopCount(String header) {
        Matcher m = Pattern.compile("=\\s*(\\d+)\\s*;[^<]*<(=?)\\s*(\\d+)").matcher(header);
        if (m.find()) {
            try {
                int start = Integer.parseInt(m.group(1));
                int end = Integer.parseInt(m.group(3));
                boolean inclusive = "=".equals(m.group(2));
                return Math.max(0, (end - start) + (inclusive ? 1 : 0));
            } catch (NumberFormatException ignored) {}
        }
        return -1;
    }

    private static String stripComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
