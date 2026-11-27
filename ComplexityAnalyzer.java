import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Big-O Time & Space Complexity Analyzer + Refactor Advisor (Pure Java OOP)
 * Inspects each method's loop nesting depth, logarithmic steps, recursion, and allocations
 * to estimate Big-O Time/Space complexity and recommend algorithmic improvements.
 */
public class ComplexityAnalyzer {

    public static class MethodComplexity {
        public final String methodName;
        public final int line;
        public final String timeComplexity;
        public final String spaceComplexity;
        public final String refactorHint;

        public MethodComplexity(String methodName, int line, String timeComplexity,
                                String spaceComplexity, String refactorHint) {
            this.methodName = methodName;
            this.line = line;
            this.timeComplexity = timeComplexity;
            this.spaceComplexity = spaceComplexity;
            this.refactorHint = refactorHint;
        }
    }

    private static final Pattern METHOD_HEADER = Pattern.compile(
            "^\\s*(?:(?:public|private|protected|static|final|synchronized)\\s+)+[A-Za-z0-9_<>,\\[\\]\\s]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^)]*\\)\\s*(?:throws\\s+[A-Za-z0-9_,\\s]+)?\\{?\\s*$");
    private static final Pattern LOOP_HEADER = Pattern.compile("\\b(for|while)\\s*\\(");
    private static final Pattern LOG_STEP = Pattern.compile("(\\*=|/=|>>=|<<=)\\s*2\\b");
    private static final Pattern ALLOC_PATTERN = Pattern.compile("\\bnew\\s+(?:ArrayList|HashMap|HashSet|LinkedHashSet|LinkedList|TreeMap|int|double|long|String|char|boolean)\\s*(?:<[^>]*>)?\\s*(?:\\(|\\[)");

    public List<MethodComplexity> analyzeMethods(String sourceCode) {
        List<MethodComplexity> reports = new ArrayList<>();
        if (sourceCode == null || sourceCode.trim().isEmpty()) return reports;

        String[] lines = sourceCode.split("\\r?\\n", -1);
        int n = lines.length;

        for (int i = 0; i < n; i++) {
            String line = stripComment(lines[i]);
            Matcher mh = METHOD_HEADER.matcher(line);
            if (!mh.find()) continue;

            String name = mh.group(1);
            if ("if".equals(name) || "for".equals(name) || "while".equals(name)
                    || "switch".equals(name) || "catch".equals(name)) {
                continue;
            }

            int startLine = i + 1;
            int braceDepth = 0;
            boolean enteredBody = false;
            int loopDepth = 0;
            int maxLoopDepth = 0;
            boolean hasLogLoop = false;
            boolean hasLinearAlloc = false;
            int recursiveCalls = 0;
            boolean hasContainsOrDedupScan = false;
            Deque<Integer> loopBraceTargets = new ArrayDeque<>();
            Pattern selfCall = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(");

            for (int j = i; j < n; j++) {
                String cur = stripComment(lines[j]);

                if (j > i && enteredBody) {
                    Matcher ml = LOOP_HEADER.matcher(cur);
                    if (ml.find()) {
                        loopDepth++;
                        if (loopDepth > maxLoopDepth) maxLoopDepth = loopDepth;
                        if (LOG_STEP.matcher(cur).find()) hasLogLoop = true;
                        if (cur.contains("{")) {
                            loopBraceTargets.push(braceDepth + 1);
                        } else {
                            // single-line loop without braces
                            loopDepth--;
                        }
                    }

                    if (ALLOC_PATTERN.matcher(cur).find()) {
                        hasLinearAlloc = true;
                    }
                    if (cur.contains(".contains(") || (cur.contains("found = true") && maxLoopDepth >= 2)) {
                        hasContainsOrDedupScan = true;
                    }

                    Matcher msc = selfCall.matcher(cur);
                    while (msc.find()) {
                        recursiveCalls++;
                    }
                }

                for (int c = 0; c < cur.length(); c++) {
                    char ch = cur.charAt(c);
                    if (ch == '{') {
                        braceDepth++;
                        enteredBody = true;
                    } else if (ch == '}') {
                        braceDepth--;
                        while (!loopBraceTargets.isEmpty() && braceDepth < loopBraceTargets.peek()) {
                            loopBraceTargets.pop();
                            loopDepth = Math.max(0, loopDepth - 1);
                        }
                    }
                }

                if (enteredBody && braceDepth == 0) {
                    i = j;
                    break;
                }
            }

            String time = "O(1)";
            String space = hasLinearAlloc ? "O(n)" : "O(1)";
            String hint = "Optimal structure - no algorithmic bottleneck detected.";

            if (recursiveCalls >= 2) {
                time = "O(2^n)";
                space = "O(n)";
                hint = "Exponential recursion detected: add memoization (HashMap or DP array) to reduce to O(n).";
            } else if (maxLoopDepth >= 3) {
                time = "O(n^3)";
                hint = "Triple-nested loop detected: consider hashing, sorting, or precomputation to reduce complexity.";
            } else if (maxLoopDepth == 2) {
                time = hasLogLoop ? "O(n log n)" : "O(n^2)";
                if (!hasLogLoop && hasContainsOrDedupScan) {
                    hint = "O(n^2) nested lookup detected: use a HashSet or LinkedHashSet to optimize this method to O(n).";
                } else if (!hasLogLoop) {
                    hint = "Quadratic O(n^2) nested loop: consider a HashMap/HashSet lookup to optimize to O(n) for large inputs.";
                }
            } else if (maxLoopDepth == 1) {
                time = hasLogLoop ? "O(log n)" : "O(n)";
                if (hasContainsOrDedupScan && !hasLogLoop) {
                    time = "O(n^2)";
                    hint = "Calling List.contains() inside a loop runs in O(n^2): switch to a HashSet for O(1) lookups.";
                }
            } else if (recursiveCalls == 1) {
                time = "O(n)";
                space = "O(n)";
                hint = "Linear recursion uses O(n) call-stack space: convert to an iterative loop for O(1) space if input is large.";
            }

            reports.add(new MethodComplexity(name, startLine, time, space, hint));
        }

        return reports;
    }

    public List<DiagnosticItem> toDiagnostics(List<MethodComplexity> reports) {
        List<DiagnosticItem> list = new ArrayList<>();
        for (MethodComplexity mc : reports) {
            if ("O(n^2)".equals(mc.timeComplexity) || "O(n^3)".equals(mc.timeComplexity) || "O(2^n)".equals(mc.timeComplexity)) {
                list.add(new DiagnosticItem(
                        mc.line, 1,
                        DiagnosticItem.Severity.INFO,
                        DiagnosticItem.Category.PERFORMANCE,
                        "PERF-001",
                        "Algorithmic Complexity: " + mc.methodName + "() is " + mc.timeComplexity,
                        "Method '" + mc.methodName + "' has estimated Time " + mc.timeComplexity + " and Space " + mc.spaceComplexity + ".",
                        mc.refactorHint
                ));
            }
        }
        return list;
    }

    private static String stripComment(String line) {
        int idx = line.indexOf("//");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
