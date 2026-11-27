import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - One-Click Auto-Fixer & Line Diff Engine
 * Repairs syntax, import, string comparison, floating-point ==, boxed wrapper ==,
 * loop bound, and threading errors safely.
 */
public class AutoFixer {

    public static class FixResult {
        public final String fixedCode;
        public final List<String> appliedFixes;
        public final List<String> diffLines;

        public FixResult(String fixedCode, List<String> appliedFixes, List<String> diffLines) {
            this.fixedCode = fixedCode;
            this.appliedFixes = appliedFixes;
            this.diffLines = diffLines;
        }
    }

    private static final Pattern FLOAT_VAR_DECL = Pattern.compile("\\b(?:double|float)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern WRAPPER_VAR_DECL = Pattern.compile("\\b(?:Integer|Long|Double|Float|Short|Character|Boolean)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern WRAPPER_LIST_DECL = Pattern.compile("\\b(?:List|ArrayList|LinkedList|Vector|Collection)\\s*<\\s*(?:Integer|Long|Double|Float|Short|String|Boolean)\\s*>\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final Pattern DECIMAL_LIT = Pattern.compile("^\\d+\\.\\d+[fFdD]?$");
    private static final Pattern EQ_OP = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_.]*(?:\\([^()=!]*\\))?|\\d+\\.\\d+[fFdD]?)\\s*(==|!=)\\s*([A-Za-z_][A-Za-z0-9_.]*(?:\\([^()=!]*\\))?|\\d+\\.\\d+[fFdD]?)");

    public FixResult autoFix(String originalCode) {
        List<String> applied = new ArrayList<>();
        String code = originalCode;

        // Collect semantic type info for safe auto-fixing
        Set<String> floatVars = new HashSet<>();
        Set<String> wrapperVars = new HashSet<>();
        Set<String> wrapperLists = new HashSet<>();
        Matcher mf = FLOAT_VAR_DECL.matcher(code);
        while (mf.find()) floatVars.add(mf.group(1));
        Matcher mw = WRAPPER_VAR_DECL.matcher(code);
        while (mw.find()) wrapperVars.add(mw.group(1));
        Matcher ml = WRAPPER_LIST_DECL.matcher(code);
        while (ml.find()) wrapperLists.add(ml.group(1));

        boolean addedObjectsUsage = false;

        // 1. Add missing import java.util.*; if needed
        boolean usesUtil = Pattern.compile("\\b(Scanner|ArrayList|HashMap|HashSet|LinkedHashSet|Arrays|Collections|LinkedList|List|Map|Set|Objects)\\b")
                .matcher(code).find();
        if (usesUtil && !code.contains("import java.util.")) {
            code = "import java.util.*;\n" + code;
            applied.add("Added missing 'import java.util.*;' at top of file.");
        }

        String[] lines = code.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String updated = line;

            // 2. Fix System.out.println typos
            if (updated.contains("Systm.")) {
                updated = updated.replace("Systm.", "System.");
                applied.add("Line " + (i + 1) + ": Fixed typo 'Systm.' -> 'System.'");
            }
            if (updated.contains("pritnln")) {
                updated = updated.replace("pritnln", "println");
                applied.add("Line " + (i + 1) + ": Fixed typo 'pritnln' -> 'println'");
            }
            if (updated.contains("printn(")) {
                updated = updated.replace("printn(", "println(");
                applied.add("Line " + (i + 1) + ": Fixed typo 'printn(' -> 'println('");
            }

            // 3. Fix String == "literal" -> .equals("literal")
            Matcher eqStr = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*==\\s*(\"[^\"]*\")").matcher(updated);
            if (eqStr.find()) {
                updated = eqStr.replaceAll("$1.equals($2)");
                applied.add("Line " + (i + 1) + ": Replaced '==' with '.equals()' for String comparison.");
            }
            Matcher neqStr = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*!=\\s*(\"[^\"]*\")").matcher(updated);
            if (neqStr.find()) {
                updated = neqStr.replaceAll("!$1.equals($2)");
                applied.add("Line " + (i + 1) + ": Replaced '!=' with '!.equals()' for String comparison.");
            }

            // 4. Fix floating-point == / != and boxed wrapper == / !=
            Matcher meq = EQ_OP.matcher(updated);
            StringBuffer sb = new StringBuffer();
            boolean lineChangedSemantic = false;
            while (meq.find()) {
                String lhs = meq.group(1).trim();
                String op = meq.group(2).trim();
                String rhs = meq.group(3).trim();
                if ("null".equals(lhs) || "null".equals(rhs)) {
                    meq.appendReplacement(sb, Matcher.quoteReplacement(meq.group(0)));
                    continue;
                }
                boolean isFloatCmp = DECIMAL_LIT.matcher(lhs).matches() || DECIMAL_LIT.matcher(rhs).matches()
                        || floatVars.contains(lhs) || floatVars.contains(rhs);
                boolean isBoxedCmp = isBoxedExpr(lhs, wrapperVars, wrapperLists)
                        && isBoxedExpr(rhs, wrapperVars, wrapperLists);

                if (isFloatCmp) {
                    String rep = "==".equals(op)
                            ? "Math.abs(" + lhs + " - " + rhs + ") < 1e-9"
                            : "Math.abs(" + lhs + " - " + rhs + ") >= 1e-9";
                    meq.appendReplacement(sb, Matcher.quoteReplacement(rep));
                    applied.add("Line " + (i + 1) + ": Replaced floating-point '" + op + "' with epsilon tolerance Math.abs(...) < 1e-9.");
                    lineChangedSemantic = true;
                } else if (isBoxedCmp) {
                    String rep = "==".equals(op)
                            ? "Objects.equals(" + lhs + ", " + rhs + ")"
                            : "!Objects.equals(" + lhs + ", " + rhs + ")";
                    meq.appendReplacement(sb, Matcher.quoteReplacement(rep));
                    applied.add("Line " + (i + 1) + ": Replaced boxed wrapper '" + op + "' with Objects.equals(...) to avoid Integer cache bugs.");
                    lineChangedSemantic = true;
                    addedObjectsUsage = true;
                } else {
                    meq.appendReplacement(sb, Matcher.quoteReplacement(meq.group(0)));
                }
            }
            if (lineChangedSemantic) {
                meq.appendTail(sb);
                updated = sb.toString();
            }

            // 5. Fix <= arr.length or <= list.size() -> < ...
            Matcher bound = Pattern.compile("<=\\s*([A-Za-z_][A-Za-z0-9_]*\\.(?:length|size\\s*\\(\\s*\\)))").matcher(updated);
            if (bound.find()) {
                updated = bound.replaceAll("< $1");
                applied.add("Line " + (i + 1) + ": Fixed off-by-one loop bound '<=' -> '<'.");
            }

            // 6. Fix accidental semicolon after for/while/if
            Matcher semi = Pattern.compile("(\\b(?:for|while|if)\\s*\\([^()=!]*\\))\\s*;").matcher(updated);
            if (semi.find()) {
                updated = semi.replaceAll("$1");
                applied.add("Line " + (i + 1) + ": Removed accidental ';' after loop/if header.");
            }

            // 7. Fix Thread.run() -> Thread.start()
            if (code.contains("extends Thread") || code.contains("new Thread")) {
                Matcher runCall = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.run\\s*\\(\\s*\\)\\s*;").matcher(updated);
                if (runCall.find()) {
                    updated = runCall.replaceAll("$1.start();");
                    applied.add("Line " + (i + 1) + ": Changed '.run()' to '.start()' for true multithreading.");
                }
            }

            // 8. Add missing semicolon on simple System.out / return statements
            String trimCheck = updated.trim();
            if ((trimCheck.startsWith("System.out.print") || trimCheck.startsWith("return "))
                    && !trimCheck.endsWith(";") && !trimCheck.endsWith("{") && !trimCheck.endsWith("}")) {
                updated = updated + ";";
                applied.add("Line " + (i + 1) + ": Added missing trailing semicolon ';'.");
            }

            lines[i] = updated;
        }

        String fixed = String.join("\n", lines);

        // 9. Auto-generate missing hashCode() for any class overriding equals() without hashCode()
        if (fixed.contains("boolean equals(") && !fixed.contains("int hashCode(")) {
            Matcher flds = Pattern.compile("\b(?:int|long|double|float|boolean|String)\s+([A-Za-z_][A-Za-z0-9_]*(?:\s*,\s*[A-Za-z_][A-Za-z0-9_]*)*)\s*;").matcher(fixed);
            List<String> fieldNames = new ArrayList<>();
            while (flds.find()) {
                for (String part : flds.group(1).split(",")) {
                    String fn = part.trim();
                    if (!fn.isEmpty() && !"args".equals(fn) && !fieldNames.contains(fn) && fieldNames.size() < 4) {
                        fieldNames.add(fn);
                    }
                }
                if (!fieldNames.isEmpty()) break;
            }
            String hashArgs = fieldNames.isEmpty() ? "" : String.join(", ", fieldNames);
            int eqIdx = fixed.indexOf("boolean equals(");
            if (eqIdx > 0) {
                int braceStart = fixed.indexOf("{", eqIdx);
                if (braceStart > 0) {
                    int depth = 0;
                    int eqEnd = -1;
                    for (int k = braceStart; k < fixed.length(); k++) {
                        if (fixed.charAt(k) == '{') depth++;
                        else if (fixed.charAt(k) == '}') {
                            depth--;
                            if (depth == 0) { eqEnd = k; break; }
                        }
                    }
                    if (eqEnd > 0) {
                        String hashMethod = "\n        @Override\n        public int hashCode() { return Objects.hash(" + hashArgs + "); }";
                        fixed = fixed.substring(0, eqEnd + 1) + hashMethod + fixed.substring(eqEnd + 1);
                        applied.add("Generated missing @Override hashCode() using Objects.hash(" + hashArgs + ") to fix HashMap/HashSet lookups.");
                        addedObjectsUsage = true;
                    }
                }
            }
        }

        if (addedObjectsUsage && !fixed.contains("import java.util.")) {
            fixed = "import java.util.*;\n" + fixed;
            applied.add("Added 'import java.util.*;' for Objects.equals().");
        }

        List<String> diff = buildLineDiff(originalCode, fixed);
        return new FixResult(fixed, applied, diff);
    }

    private static boolean isBoxedExpr(String expr, Set<String> wrapperVars, Set<String> wrapperLists) {
        if (wrapperVars.contains(expr)) return true;
        int dotGet = expr.indexOf(".get(");
        if (dotGet > 0) {
            String colName = expr.substring(0, dotGet).trim();
            if (wrapperLists.contains(colName)) return true;
        }
        return false;
    }

    private List<String> buildLineDiff(String before, String after) {
        List<String> diff = new ArrayList<>();
        String[] bLines = before.split("\\r?\\n", -1);
        String[] aLines = after.split("\\r?\\n", -1);
        int max = Math.max(bLines.length, aLines.length);

        for (int i = 0; i < max; i++) {
            String b = i < bLines.length ? bLines[i] : null;
            String a = i < aLines.length ? aLines[i] : null;
            if (Objects.equals(b, a)) {
                diff.add(String.format("  %3d | %s", i + 1, a != null ? a : ""));
            } else {
                if (b != null) diff.add(String.format("- %3d | %s", i + 1, b));
                if (a != null) diff.add(String.format("+ %3d | %s", i + 1, a));
            }
        }
        return diff;
    }
}
