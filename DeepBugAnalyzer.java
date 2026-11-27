import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Deep Static Bug Analyzer (16th Pure Java OOP Class)
 * Detects subtle algorithmic, numeric, memory-model, regex, null-flow, and concurrency bugs statically:
 *  - OVF-001 : Silent 32-bit Integer Overflow in multiplicative loops
 *  - LOOP-001: Binary Search / Loop Variable Stagnation Infinite Loop (lo = mid instead of lo = mid + 1)
 *  - CONC-003: Double-Checked Locking (DCL) Singleton without volatile
 *  - CONC-004: Check-Then-Act Race (containsKey/get followed by put on shared Map in concurrent context)
 *  - CONC-005: VisibilityBug (shared flag polled in loop & mutated across threads without volatile/sync)
 *  - SEM-008 : BrokenNullCheck / MisplacedNullCheck (dereferencing variable before != null check or inside == null)
 *  - SEC-005 : CatastrophicRegex / ReDoS (nested quantifiers like (a+)+ or (.+)*)
 *  - STAT-009: Swallowed / LosingExceptionInfo (catch block ignoring exception variable without logging or rethrowing)
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
    private static final Pattern MISPLACED_NULL_CHECK = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*)\\.[A-Za-z_][A-Za-z0-9_]*[^&|;]*&&\\s*\\1\\s*!=\\s*null\\b");
    private static final Pattern REGEX_CALL = Pattern.compile(
            "(?:Pattern\\.compile|\\.matches|\\.replaceAll|\\.split)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile(
            "\\([^()]*[+*][^()]*\\)\\s*[+*]");
    private static final Pattern CATCH_HEADER = Pattern.compile(
            "\\bcatch\\s*\\([^)]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");

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
        detectVisibilityBug(sourceCode, lines, findings);
        detectBrokenNullCheck(lines, findings);
        detectCatastrophicRegex(lines, findings);
        detectSwallowedException(lines, findings);

        return findings;
    }

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
                    try { intInitVals.put(var, Long.parseLong(init.trim())); } catch (NumberFormatException ignored) {}
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
                                if (val > Integer.MAX_VALUE / factor) overflowed = true;
                                if (val <= Long.MAX_VALUE / factor) val *= factor;
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
                    if (mm.find()) midVar = mm.group(1);
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

    private void detectDclWithoutVolatile(String sourceCode, String[] lines, List<DiagnosticItem> findings) {
        Map<String, Boolean> fieldVolatile = new HashMap<>();
        Map<String, Integer> fieldLine = new HashMap<>();

        for (int i = 0; i < lines.length; i++) {
            String s = stripComment(lines[i]);
            if (s.contains("(") && !s.contains("=")) continue;
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

    /**
     * CONC-005: Shared boolean/int flag polled in a while-loop across threads without volatile/synchronized.
     */
    private void detectVisibilityBug(String sourceCode, String[] lines, List<DiagnosticItem> findings) {
        if (!CONCURRENT_CTX.matcher(sourceCode).find()) return;
        Map<String, Integer> nonVolatileFlags = new LinkedHashMap<>();
        Pattern flagDecl = Pattern.compile("\\b(?:static|private|public|protected|\\s)+boolean\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*(?:=|;)");

        for (int i = 0; i < lines.length; i++) {
            String s = stripComment(lines[i]);
            if (s.contains("volatile") || s.contains("(")) continue;
            Matcher m = flagDecl.matcher(s);
            if (m.find()) nonVolatileFlags.put(m.group(1), i + 1);
        }

        for (Map.Entry<String, Integer> e : nonVolatileFlags.entrySet()) {
            String flag = e.getKey();
            int declLine = e.getValue();
            Pattern pollLoop = Pattern.compile("\\bwhile\\s*\\(\\s*!?\\s*" + Pattern.quote(flag) + "\\s*\\)");
            for (int i = 0; i < lines.length; i++) {
                String s = stripComment(lines[i]);
                if (pollLoop.matcher(s).find() && !s.contains("synchronized")) {
                    findings.add(new DiagnosticItem(
                            declLine, 1,
                            DiagnosticItem.Severity.ERROR,
                            DiagnosticItem.Category.SEMANTIC,
                            "CONC-005",
                            "VisibilityBug on Shared Flag '" + flag + "'",
                            "Cause: Shared boolean flag '" + flag + "' is polled in a while-loop (Line " + (i + 1)
                                    + ") across threads without 'volatile' or synchronization. Runtime Consequence: The JIT compiler can cache '"
                                    + flag + "' in a CPU register so the worker thread NEVER sees updates and loops forever!",
                            "Declare '" + flag + "' as volatile (static volatile boolean " + flag + ") or use AtomicBoolean."
                    ));
                    break;
                }
            }
        }
    }

    /**
     * SEM-008: BrokenNullCheck / MisplacedNullCheck (dereference before != null, or dereference inside == null).
     */
    private void detectBrokenNullCheck(String[] lines, List<DiagnosticItem> findings) {
        for (int i = 0; i < lines.length; i++) {
            String s = stripComment(lines[i]);
            Matcher m = MISPLACED_NULL_CHECK.matcher(s);
            if (m.find()) {
                String v = m.group(1);
                findings.add(new DiagnosticItem(
                        i + 1, m.start() + 1,
                        DiagnosticItem.Severity.ERROR,
                        DiagnosticItem.Category.SEMANTIC,
                        "SEM-008",
                        "BrokenNullCheck / MisplacedNullCheck on '" + v + "'",
                        "Cause: '" + v + "' is dereferenced BEFORE checking '" + v + " != null' in the && condition. Runtime Consequence: Throws NullPointerException immediately whenever '" + v + "' is null - the null check is never even reached!",
                        "Move '" + v + " != null' to the LEFT side of && so short-circuit evaluation protects the dereference."
                ));
            }
            Matcher mEqNull = Pattern.compile("\\bif\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*null\\s*\\)").matcher(s);
            if (mEqNull.find()) {
                String v = mEqNull.group(1);
                String checkLine = s.substring(mEqNull.end());
                String nextLine = (i + 1 < lines.length) ? stripComment(lines[i + 1]) : "";
                if (checkLine.contains(v + ".") || (!checkLine.contains("return") && !checkLine.contains("throw") && nextLine.contains(v + ".") && !nextLine.contains("else"))) {
                    findings.add(new DiagnosticItem(
                            i + 1, mEqNull.start() + 1,
                            DiagnosticItem.Severity.ERROR,
                            DiagnosticItem.Category.SEMANTIC,
                            "SEM-008",
                            "BrokenNullCheck: Dereferencing Known-Null '" + v + "'",
                            "Cause: '" + v + "' is confirmed null inside 'if (" + v + " == null)' and then dereferenced with '" + v + ".'. Runtime Consequence: Guaranteed NullPointerException at runtime!",
                            "Flip the check to 'if (" + v + " != null)' or return/initialize '" + v + "' before calling methods on it."
                    ));
                }
            }
        }
    }

    /**
     * SEC-005: Catastrophic Backtracking Regex (ReDoS) detection.
     */
    private void detectCatastrophicRegex(String[] lines, List<DiagnosticItem> findings) {
        for (int i = 0; i < lines.length; i++) {
            String s = stripComment(lines[i]);
            Matcher m = REGEX_CALL.matcher(s);
            while (m.find()) {
                String regex = m.group(1);
                if (NESTED_QUANTIFIER.matcher(regex).find() || regex.contains("(.+)+") || regex.contains("(.*)*")) {
                    findings.add(new DiagnosticItem(
                            i + 1, m.start() + 1,
                            DiagnosticItem.Severity.ERROR,
                            DiagnosticItem.Category.SECURITY,
                            "SEC-005",
                            "CatastrophicRegex (ReDoS Vulnerability)",
                            "Cause: Regex \"" + regex + "\" contains nested quantifiers (e.g. (a+)+ or (.+)*). Runtime Consequence: Exponential O(2^n) backtracking can freeze the JVM CPU for minutes on a 30-character crafted input (Denial of Service)!",
                            "Flatten nested quantifiers (e.g. use possessive quantifiers '++' or atomic groups '(?>...)') to guarantee linear matching time."
                    ));
                }
            }
        }
    }

    /**
     * STAT-009: Swallowed / LosingExceptionInfo (catch block ignores exception variable without logging or rethrowing).
     */
    private void detectSwallowedException(String[] lines, List<DiagnosticItem> findings) {
        int n = lines.length;
        for (int i = 0; i < n; i++) {
            String s = stripComment(lines[i]);
            Matcher mc = CATCH_HEADER.matcher(s);
            if (!mc.find()) continue;
            String exVar = mc.group(1);

            int depth = 0;
            boolean entered = false;
            StringBuilder body = new StringBuilder();
            for (int j = i; j < n; j++) {
                String cur = stripComment(lines[j]);
                for (int c = 0; c < cur.length(); c++) {
                    char ch = cur.charAt(c);
                    if (ch == '{') { depth++; entered = true; }
                    else if (ch == '}') depth--;
                    else if (entered && depth >= 1) body.append(ch);
                }
                if (entered && depth == 0) break;
            }

            String b = body.toString().trim();
            if (!b.isEmpty()) {
                boolean usesEx = Pattern.compile("\\b" + Pattern.quote(exVar) + "\\b").matcher(b).find();
                boolean rethrows = b.contains("throw ");
                if (!usesEx && !rethrows) {
                    findings.add(new DiagnosticItem(
                            i + 1, mc.start() + 1,
                            DiagnosticItem.Severity.WARNING,
                            DiagnosticItem.Category.STATIC_ANALYSIS,
                            "STAT-009",
                            "LosingExceptionInfo (Swallowed '" + exVar + "')",
                            "Cause: Catch block captures '" + exVar + "' but never logs, prints, or rethrows it. Runtime Consequence: Original failure cause and stack trace are silently lost, making production bugs nearly impossible to diagnose.",
                            "Log or wrap the exception: System.err.println(" + exVar + ".getMessage()); or throw new RuntimeException(" + exVar + ");"
                    ));
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
