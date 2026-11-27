/**
 * JavaLens Pro - Pure Java Edition
 * Represents a single compiler, static, semantic, security, performance, or runtime diagnostic.
 */
public class DiagnosticItem {

    public enum Severity {
        ERROR, WARNING, INFO
    }

    public enum Category {
        COMPILER, STATIC_ANALYSIS, SEMANTIC, SECURITY, PERFORMANCE, RUNTIME
    }

    private final int line;
    private final int column;
    private final Severity severity;
    private final Category category;
    private final String ruleId;
    private final String title;
    private final String message;
    private final String suggestion;

    public DiagnosticItem(int line, int column, Severity severity, Category category,
                          String ruleId, String title, String message, String suggestion) {
        this.line = Math.max(1, line);
        this.column = Math.max(1, column);
        this.severity = severity;
        this.category = category;
        this.ruleId = ruleId;
        this.title = title;
        this.message = message;
        this.suggestion = suggestion;
    }

    public int getLine() { return line; }
    public int getColumn() { return column; }
    public Severity getSeverity() { return severity; }
    public Category getCategory() { return category; }
    public String getRuleId() { return ruleId; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public String getSuggestion() { return suggestion; }

    @Override
    public String toString() {
        return String.format("[%s | %s] Line %d, Col %d - %s: %s (Fix: %s)",
                severity, ruleId, line, column, title, message, suggestion);
    }
}
