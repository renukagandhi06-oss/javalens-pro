import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Concurrency, Lock-Graph & Contract Impact Analyzer (Pure Java OOP)
 * Performs cross-method and cross-class impact analysis to report BOTH Cause AND Runtime Consequence:
 *  - SEM-007 : equals() without hashCode() traced to HashMap/HashSet key lookups (Silent null / data loss)
 *  - CONC-001: Lock-ordering Deadlock Cycle Detector (nested synchronized lockA -> lockB vs lockB -> lockA)
 *  - CONC-002: Thread Data Race / Lost Updates (unsynchronized ++/--/+= inside Thread/ExecutorService)
 */
public class ConcurrencyAnalyzer {

    private static final Pattern CLASS_DECL = Pattern.compile(
            "\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern SYNC_LOCK = Pattern.compile(
            "\\bsynchronized\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*\\)");
    private static final Pattern THREAD_START = Pattern.compile(
            "(?:\\.(?:submit|execute)\\s*\\(|\\bnew\\s+Thread\\s*\\()");
    private static final Pattern SHARED_MUTATION = Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]*(?:\\[[^\\]]+\\])?)\\s*(\\+\\+|--|\\+=|-=)");
    private static final Pattern LOCAL_FOR_INC = Pattern.compile(
            "\\bfor\\s*\\([^;]*;[^;]*;[^)]*\\)");

    public List<DiagnosticItem> analyze(String sourceCode) {
        List<DiagnosticItem> findings = new ArrayList<>();
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            return findings;
        }
        String[] lines = sourceCode.split("\\r?\\n", -1);

        analyzeEqualsHashCodeImpact(sourceCode, lines, findings);
        analyzeDeadlockCycles(lines, findings);
        analyzeDataRaces(lines, findings);

        return findings;
    }

    /**
     * SEM-007: Traces equals() without hashCode() to actual HashMap/HashSet/Map/Set usage
     * and escalates to ERROR with exact runtime consequence (map.get() silently returning null).
     */
    private void analyzeEqualsHashCodeImpact(String sourceCode, String[] lines, List<DiagnosticItem> findings) {
        int n = lines.length;
        for (int i = 0; i < n; i++) {
            String clean = stripComment(lines[i]);
            Matcher mc = CLASS_DECL.matcher(clean);
            if (!mc.find()) continue;

            String className = mc.group(1);
            int classLine = i + 1;
            int depth = 0;
            boolean entered = false;
            int equalsLine = -1;
            boolean hasHashCode = false;

            for (int j = i; j < n; j++) {
                String cur = stripComment(lines[j]);
                if (entered && depth == 1) {
                    if (cur.contains("boolean equals(")) equalsLine = j + 1;
                    if (cur.contains("int hashCode(")) hasHashCode = true;
                }
                for (int c = 0; c < cur.length(); c++) {
                    char ch = cur.charAt(c);
                    if (ch == '{') { depth++; entered = true; }
                    else if (ch == '}') { depth--; }
                }
                if (entered && depth == 0) break;
            }

            if (equalsLine > 0 && !hasHashCode) {
                // Trace if this exact class is used as a HashMap / Map / HashSet / Set key!
                Pattern hashUsage = Pattern.compile(
                        "\\b(?:HashMap|Map|ConcurrentHashMap|LinkedHashMap|Hashtable|HashSet|Set|LinkedHashSet)\\s*<\\s*"
                                + Pattern.quote(className) + "\\b");
                int usageLine = -1;
                String mapVarName = null;
                Pattern mapVarPat = Pattern.compile(
                        "\\b(?:HashMap|Map|ConcurrentHashMap|LinkedHashMap|Hashtable|HashSet|Set|LinkedHashSet)\\s*<\\s*"
                                + Pattern.quote(className) + "[^>]*>\\s+([A-Za-z_][A-Za-z0-9_]*)");

                for (int k = 0; k < n; k++) {
                    String cur = stripComment(lines[k]);
                    if (hashUsage.matcher(cur).find()) {
                        usageLine = k + 1;
                        Matcher mv = mapVarPat.matcher(cur);
                        if (mv.find()) mapVarName = mv.group(1);
                        break;
                    }
                }

                if (usageLine > 0) {
                    String lookupDetail = (mapVarName != null)
                            ? "'" + mapVarName + ".get(...)' / '" + mapVarName + ".contains(...)'"
                            : "HashMap.get() / HashSet.contains()";
                    findings.add(new DiagnosticItem(
                            equalsLine, 1,
                            DiagnosticItem.Severity.ERROR,
                            DiagnosticItem.Category.SEMANTIC,
                            "SEM-007",
                            "equals() Without hashCode() -> Silent Map/Set Lookup Failure",
                            "Cause: Class '" + className + "' overrides equals() without hashCode(), and is used as a hashed key on Line " + usageLine
                                    + ". Runtime Consequence: Two equal " + className + " objects hash to DIFFERENT buckets, causing "
                                    + lookupDetail + " to silently return null/false (silent data loss at runtime)!",
                            "Override hashCode() in '" + className + "' using Objects.hash(...) matching the same fields used in equals() (Auto-Fix supported)."
                    ));
                } else {
                    findings.add(new DiagnosticItem(
                            equalsLine, 1,
                            DiagnosticItem.Severity.WARNING,
                            DiagnosticItem.Category.SEMANTIC,
                            "SEM-007",
                            "Missing hashCode() Override in '" + className + "'",
                            "Cause: '" + className + "' overrides equals() without hashCode(). Runtime Consequence: If stored in a HashMap or HashSet, lookups will silently return null/false.",
                            "Override hashCode() using Objects.hash(...) with the same fields checked in equals() (Auto-Fix supported)."
                    ));
                }
            }
        }
    }

    /**
     * CONC-001: Builds a directed Lock-Acquisition Graph from nested synchronized(lock) blocks
     * and detects circular lock-ordering cycles (A -> B and B -> A) that cause Deadlock.
     */
    private void analyzeDeadlockCycles(String[] lines, List<DiagnosticItem> findings) {
        // Track active locks as stack of [lockName, braceDepthTarget, lineNum]
        Deque<String> lockStack = new ArrayDeque<>();
        Deque<Integer> lockDepthStack = new ArrayDeque<>();

        // Directed edges: outerLock -> Map<innerLock, lineNum>
        Map<String, Map<String, Integer>> lockGraph = new LinkedHashMap<>();
        int braceDepth = 0;

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String code = stripComment(lines[i]);

            Matcher ms = SYNC_LOCK.matcher(code);
            if (ms.find()) {
                String currentLock = ms.group(1);
                if (!lockStack.isEmpty()) {
                    String outerLock = lockStack.peek();
                    if (!outerLock.equals(currentLock)) {
                        lockGraph.computeIfAbsent(outerLock, k -> new LinkedHashMap<>())
                                .putIfAbsent(currentLock, lineNum);
                    }
                }
                if (code.contains("{")) {
                    lockStack.push(currentLock);
                    lockDepthStack.push(braceDepth + 1);
                }
            }

            for (int c = 0; c < code.length(); c++) {
                char ch = code.charAt(c);
                if (ch == '{') {
                    braceDepth++;
                } else if (ch == '}') {
                    braceDepth--;
                    while (!lockDepthStack.isEmpty() && braceDepth < lockDepthStack.peek()) {
                        lockDepthStack.pop();
                        lockStack.pop();
                    }
                }
            }
        }

        // Check for 2-node cycles (lockA -> lockB AND lockB -> lockA)
        Set<String> reportedPairs = new HashSet<>();
        for (Map.Entry<String, Map<String, Integer>> e : lockGraph.entrySet()) {
            String lockA = e.getKey();
            for (Map.Entry<String, Integer> inner : e.getValue().entrySet()) {
                String lockB = inner.getKey();
                int lineAB = inner.getValue();
                Map<String, Integer> rev = lockGraph.get(lockB);
                if (rev != null && rev.containsKey(lockA)) {
                    int lineBA = rev.get(lockA);
                    String pairKey = lockA.compareTo(lockB) < 0 ? (lockA + "|" + lockB) : (lockB + "|" + lockA);
                    if (reportedPairs.add(pairKey)) {
                        findings.add(new DiagnosticItem(
                                Math.min(lineAB, lineBA), 1,
                                DiagnosticItem.Severity.ERROR,
                                DiagnosticItem.Category.SEMANTIC,
                                "CONC-001",
                                "Lock-Ordering Deadlock (" + lockA + " <-> " + lockB + ")",
                                "Cause: Nested locks are acquired in opposite orders: '" + lockA + " -> " + lockB + "' (Line " + lineAB
                                        + ") vs '" + lockB + " -> " + lockA + "' (Line " + lineBA
                                        + "). Runtime Consequence: Permanent thread freeze / application hang as both threads wait forever on each other!",
                                "Enforce one global lock order across all threads: always synchronize on '" + lockA + "' before '" + lockB + "', or use ReentrantLock.tryLock()."
                        ));
                    }
                }
            }
        }
    }

    /**
     * CONC-002: Detects unsynchronized shared variable/array mutations inside Thread or ExecutorService tasks.
     */
    private void analyzeDataRaces(String[] lines, List<DiagnosticItem> findings) {
        int braceDepth = 0;
        boolean inTask = false;
        int taskTargetDepth = -1;
        boolean inSync = false;
        int syncTargetDepth = -1;
        Set<String> localLoopVars = new HashSet<>();

        for (int i = 0; i < lines.length; i++) {
            int lineNum = i + 1;
            String code = stripComment(lines[i]);

            if (!inTask && THREAD_START.matcher(code).find() && code.contains("{")) {
                inTask = true;
                taskTargetDepth = braceDepth + 1;
                localLoopVars.clear();
            }

            if (inTask && code.contains("synchronized")) {
                inSync = true;
                syncTargetDepth = braceDepth + 1;
            }

            if (inTask && !inSync) {
                // Collect local loop counters like for (int i = 0; ...)
                Matcher mf = Pattern.compile("\\bfor\\s*\\(\\s*(?:int|long)\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(code);
                while (mf.find()) localLoopVars.add(mf.group(1));

                // Strip the for(...) header itself so we only inspect the body for shared mutations
                String bodyOnly = LOCAL_FOR_INC.matcher(code).replaceAll("");
                Matcher mm = SHARED_MUTATION.matcher(bodyOnly);
                if (mm.find()) {
                    String target = mm.group(1);
                    String baseVar = target.contains("[") ? target.substring(0, target.indexOf('[')) : target;
                    if (!localLoopVars.contains(baseVar)) {
                        findings.add(new DiagnosticItem(
                                lineNum, mm.start() + 1,
                                DiagnosticItem.Severity.ERROR,
                                DiagnosticItem.Category.SEMANTIC,
                                "CONC-002",
                                "Thread Data Race on Shared '" + target + "'",
                                "Cause: '" + target + mm.group(2) + "' performs a non-atomic read-modify-write inside a concurrent task without synchronization. Runtime Consequence: Silent lost updates at runtime (final counter value will be unpredictably lower than expected)!",
                                "Replace with java.util.concurrent.atomic.AtomicInteger (counter.incrementAndGet()) or guard updates inside a synchronized block."
                        ));
                    }
                }
            }

            for (int c = 0; c < code.length(); c++) {
                char ch = code.charAt(c);
                if (ch == '{') braceDepth++;
                else if (ch == '}') {
                    braceDepth--;
                    if (inSync && braceDepth < syncTargetDepth) {
                        inSync = false;
                        syncTargetDepth = -1;
                    }
                    if (inTask && braceDepth < taskTargetDepth) {
                        inTask = false;
                        taskTargetDepth = -1;
                        localLoopVars.clear();
                    }
                }
            }
        }
    }

    private static String stripComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
