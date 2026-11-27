import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaLens Pro - Code Quality, Cyclomatic Complexity & OOP Metrics Engine
 */
public class CodeMetrics {

    public final int totalLines;
    public final int codeLines;
    public final int commentLines;
    public final int classCount;
    public final int methodCount;
    public final int cyclomaticComplexity;
    public final int healthScore;
    public final String healthGrade;
    public final List<String> oopConcepts;

    private CodeMetrics(int totalLines, int codeLines, int commentLines, int classCount,
                        int methodCount, int cyclomaticComplexity, int healthScore,
                        String healthGrade, List<String> oopConcepts) {
        this.totalLines = totalLines;
        this.codeLines = codeLines;
        this.commentLines = commentLines;
        this.classCount = classCount;
        this.methodCount = methodCount;
        this.cyclomaticComplexity = cyclomaticComplexity;
        this.healthScore = healthScore;
        this.healthGrade = healthGrade;
        this.oopConcepts = oopConcepts;
    }

    public static CodeMetrics compute(String sourceCode, List<DiagnosticItem> diagnostics) {
        String[] lines = sourceCode.split("\\r?\\n", -1);
        int total = lines.length;
        int comments = 0;
        int code = 0;
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) comments++;
            else code++;
        }

        int classes = countMatches(sourceCode, "\\b(?:class|interface|enum)\\s+[A-Za-z_]");
        int methods = countMatches(sourceCode, "\\b(?:public|private|protected|static)\\s+[A-Za-z0-9_<>,\\[\\]]+\\s+[a-z][A-Za-z0-9_]*\\s*\\(");

        // Cyclomatic Complexity: 1 + decision points
        int decisions = countMatches(sourceCode, "\\b(if|for|while|case|catch)\\b")
                + countMatches(sourceCode, "(&&|\\|\\||\\?)");
        int complexity = 1 + decisions;

        // OOP & Java Feature Detection
        List<String> concepts = new ArrayList<>();
        if (sourceCode.contains("private ")) concepts.add("Encapsulation (private fields)");
        if (sourceCode.contains("extends ")) concepts.add("Inheritance (extends)");
        if (sourceCode.contains("implements ")) concepts.add("Interfaces (implements)");
        if (sourceCode.contains("try") && sourceCode.contains("catch")) concepts.add("Exception Handling (try/catch)");
        if (sourceCode.contains("Thread") || sourceCode.contains("Runnable")) concepts.add("Multithreading");
        if (Pattern.compile("\\b(ArrayList|HashMap|List|Map|Set)\\b").matcher(sourceCode).find()) {
            concepts.add("Java Collections Framework");
        }
        if (concepts.isEmpty()) concepts.add("Basic Procedural / Class Structure");

        int errors = 0, warnings = 0;
        for (DiagnosticItem d : diagnostics) {
            if (d.getSeverity() == DiagnosticItem.Severity.ERROR) errors++;
            else if (d.getSeverity() == DiagnosticItem.Severity.WARNING) warnings++;
        }

        int score = 100 - (errors * 22) - (warnings * 8) - Math.max(0, (complexity - 10) * 2);
        score = Math.max(0, Math.min(100, score));

        String grade;
        if (score >= 95) grade = "A+";
        else if (score >= 85) grade = "A";
        else if (score >= 70) grade = "B";
        else if (score >= 50) grade = "C";
        else grade = "D";

        return new CodeMetrics(total, code, comments, classes, methods, complexity, score, grade, concepts);
    }

    private static int countMatches(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }
}
