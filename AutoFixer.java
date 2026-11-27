import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - One-Click Auto-Fixer & Line Diff Engine
 * Repairs common syntax, import, string comparison, loop bound, and threading errors safely.
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

    public FixResult autoFix(String originalCode) {
        List<String> applied = new ArrayList<>();
        String code = originalCode;

        // 1. Add missing import java.util.*; if needed
        boolean usesUtil = Pattern.compile("\\b(Scanner|ArrayList|HashMap|Arrays|Collections|LinkedList)\\b")
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

            // 4. Fix <= arr.length -> < arr.length
            Matcher bound = Pattern.compile("<=\\s*([A-Za-z_][A-Za-z0-9_]*\\.length)\\b").matcher(updated);
            if (bound.find()) {
                updated = bound.replaceAll("< $1");
                applied.add("Line " + (i + 1) + ": Fixed off-by-one loop bound '<= length' -> '< length'.");
            }

            // 5. Fix accidental semicolon after for/while/if
            Matcher semi = Pattern.compile("(\\b(?:for|while|if)\\s*\\([^)]*\\))\\s*;").matcher(updated);
            if (semi.find()) {
                updated = semi.replaceAll("$1");
                applied.add("Line " + (i + 1) + ": Removed accidental ';' after loop/if header.");
            }

            // 6. Fix Thread.run() -> Thread.start()
            if (code.contains("extends Thread") || code.contains("new Thread")) {
                Matcher runCall = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.run\\s*\\(\\s*\\)\\s*;").matcher(updated);
                if (runCall.find()) {
                    updated = runCall.replaceAll("$1.start();");
                    applied.add("Line " + (i + 1) + ": Changed '.run()' to '.start()' for true multithreading.");
                }
            }

            // 7. Add missing semicolon on simple System.out / return statements
            String trimCheck = updated.trim();
            if ((trimCheck.startsWith("System.out.print") || trimCheck.startsWith("return "))
                    && !trimCheck.endsWith(";") && !trimCheck.endsWith("{") && !trimCheck.endsWith("}")) {
                updated = updated + ";";
                applied.add("Line " + (i + 1) + ": Added missing trailing semicolon ';'.");
            }

            lines[i] = updated;
        }

        String fixed = String.join("\n", lines);
        List<String> diff = buildLineDiff(originalCode, fixed);
        return new FixResult(fixed, applied, diff);
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
