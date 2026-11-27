import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * JavaLens Pro - Rich ANSI-Color Terminal Studio (Phone / Termux / OnlineGDB / CLI Ready)
 * 100% Pure Java interactive CLI with column error pointers, Health Score meter,
 * Auto-Fix colored diff, and Live Output Terminal.
 */
public class JavaLensCLI {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String DIM = "\u001B[2m";

    private final CompilerEngine compilerEngine = new CompilerEngine();
    private final StaticAnalyzer staticAnalyzer = new StaticAnalyzer();
    private final AutoFixer autoFixer = new AutoFixer();
    private final CodeRunner codeRunner = new CodeRunner();

    private String currentCode = BuggyPresets.getPreset(0);
    private List<DiagnosticItem> lastDiagnostics = new ArrayList<>();

    public void start() {
        Scanner sc = new Scanner(System.in);
        printBanner();

        while (true) {
            System.out.println("\n" + BOLD + CYAN + "================ JAVALENS PRO MENU ================" + RESET);
            System.out.println("  1. View Current Java Code");
            System.out.println("  2. Load VTU Demo Preset (4 Built-in Samples)");
            System.out.println("  3. Paste / Type Your Own Java Code");
            System.out.println("  4. Load .java File from Storage");
            System.out.println("  5. " + GREEN + "Analyze Code" + RESET + " (javac + Static Rules + Health Score)");
            System.out.println("  6. " + YELLOW + "Auto-Fix All" + RESET + " (Repair Bugs + Colored Diff)");
            System.out.println("  7. " + CYAN + "Run Code in Output Terminal" + RESET + " (+ Crash Explainer)");
            System.out.println("  8. Export Audit Report (.txt)");
            System.out.println("  0. Exit");
            System.out.print(BOLD + "Choose option [0-8]: " + RESET);

            if (!sc.hasNextLine()) break;
            String choice = sc.nextLine().trim();

            switch (choice) {
                case "1":
                    printCodeWithLineNumbers(currentCode);
                    break;
                case "2":
                    choosePreset(sc);
                    break;
                case "3":
                    readCustomCode(sc);
                    break;
                case "4":
                    loadFile(sc);
                    break;
                case "5":
                    runAnalysis();
                    break;
                case "6":
                    runAutoFix();
                    break;
                case "7":
                    runInTerminal();
                    break;
                case "8":
                    exportReport();
                    break;
                case "0":
                    System.out.println(GREEN + "Goodbye! Good luck with your VTU Java demo!" + RESET);
                    return;
                default:
                    System.out.println(YELLOW + "Invalid choice. Enter a number from 0 to 8." + RESET);
            }
        }
    }

    private void printBanner() {
        System.out.println(BOLD + GREEN);
        System.out.println("+==========================================================+");
        System.out.println("|   JavaLens Pro - Pure Java Error Detector & Suggestor    |");
        System.out.println("|   Real javac API * 14 Static Rules * Auto-Fix * Runner   |");
        System.out.println("+==========================================================+" + RESET);
        System.out.println(DIM + "Loaded default Preset 1. Press 5 to Analyze, 6 to Auto-Fix, 7 to Run." + RESET);
    }

    private void choosePreset(Scanner sc) {
        System.out.println("\nSelect a VTU Demo Preset:");
        for (int i = 0; i < BuggyPresets.NAMES.length; i++) {
            System.out.println("  " + (i + 1) + ". " + BuggyPresets.NAMES[i]);
        }
        System.out.print("Enter preset [1-" + BuggyPresets.NAMES.length + "]: ");
        if (!sc.hasNextLine()) return;
        try {
            int idx = Integer.parseInt(sc.nextLine().trim()) - 1;
            if (idx >= 0 && idx < BuggyPresets.NAMES.length) {
                currentCode = BuggyPresets.getPreset(idx);
                System.out.println(GREEN + "Loaded: " + BuggyPresets.NAMES[idx] + RESET);
                printCodeWithLineNumbers(currentCode);
            } else {
                System.out.println(YELLOW + "Preset number out of range." + RESET);
            }
        } catch (NumberFormatException e) {
            System.out.println(YELLOW + "Please enter a valid number." + RESET);
        }
    }

    private void readCustomCode(Scanner sc) {
        System.out.println("\nPaste or type your Java code below. Type " + BOLD + "END" + RESET + " on a new line when done:");
        StringBuilder sb = new StringBuilder();
        while (sc.hasNextLine()) {
            String line = sc.nextLine();
            if (line.trim().equals("END")) break;
            sb.append(line).append("\n");
        }
        if (sb.length() > 0) {
            currentCode = sb.toString();
            System.out.println(GREEN + "Custom code loaded (" + currentCode.split("\\r?\\n").length + " lines)." + RESET);
        }
    }

    private void loadFile(Scanner sc) {
        System.out.print("Enter path to .java file: ");
        if (!sc.hasNextLine()) return;
        File f = new File(sc.nextLine().trim());
        if (!f.exists() || !f.isFile()) {
            System.out.println(RED + "File not found: " + f.getAbsolutePath() + RESET);
            return;
        }
        try {
            currentCode = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            System.out.println(GREEN + "Loaded file: " + f.getName() + RESET);
        } catch (IOException e) {
            System.out.println(RED + "Could not read file: " + e.getMessage() + RESET);
        }
    }

    private void runAnalysis() {
        List<DiagnosticItem> all = new ArrayList<>();
        all.addAll(compilerEngine.compileAndDiagnose(currentCode));
        all.addAll(staticAnalyzer.analyze(currentCode));
        all.sort(Comparator.comparingInt(DiagnosticItem::getLine));
        lastDiagnostics = all;

        CodeMetrics m = CodeMetrics.compute(currentCode, all);
        String[] codeLines = currentCode.split("\\r?\\n", -1);

        System.out.println("\n" + BOLD + CYAN + "--- DIAGNOSTICS & SUGGESTIONS (" + all.size() + " found) ---" + RESET);
        if (all.isEmpty()) {
            System.out.println(GREEN + BOLD + "Zero errors or warnings! Your Java code is clean." + RESET);
        } else {
            for (DiagnosticItem d : all) {
                String sevCol = d.getSeverity() == DiagnosticItem.Severity.ERROR ? RED
                        : (d.getSeverity() == DiagnosticItem.Severity.WARNING ? YELLOW : CYAN);
                System.out.println(sevCol + BOLD + "[" + d.getSeverity() + " | " + d.getRuleId() + "] Line "
                        + d.getLine() + ", Col " + d.getColumn() + " - " + d.getTitle() + RESET);
                if (d.getLine() >= 1 && d.getLine() <= codeLines.length) {
                    System.out.println("  " + DIM + codeLines[d.getLine() - 1] + RESET);
                    StringBuilder ptr = new StringBuilder("  ");
                    for (int s = 1; s < Math.min(d.getColumn(), 60); s++) ptr.append(' ');
                    System.out.println(sevCol + ptr + "^~~~" + RESET);
                }
                System.out.println("  Detail: " + d.getMessage());
                System.out.println("  " + GREEN + "Fix:    " + d.getSuggestion() + RESET + "\n");
            }
        }

        printHealthBar(m);
    }

    private void runAutoFix() {
        AutoFixer.FixResult res = autoFixer.autoFix(currentCode);
        if (res.appliedFixes.isEmpty()) {
            System.out.println(GREEN + "No automatic fixes needed (or remaining errors need manual logic edits)." + RESET);
            return;
        }
        currentCode = res.fixedCode;
        System.out.println("\n" + BOLD + GREEN + "--- AUTO-FIX APPLIED (" + res.appliedFixes.size() + " repair(s)) ---" + RESET);
        for (String fix : res.appliedFixes) {
            System.out.println("  " + GREEN + "[OK] " + fix + RESET);
        }
        System.out.println("\n" + BOLD + CYAN + "--- BEFORE / AFTER DIFF ---" + RESET);
        for (String dl : res.diffLines) {
            if (dl.startsWith("- ")) System.out.println(RED + dl + RESET);
            else if (dl.startsWith("+ ")) System.out.println(GREEN + dl + RESET);
            else System.out.println(DIM + dl + RESET);
        }
        System.out.println(CYAN + "Tip: Press 5 to re-analyze or 7 to Run the fixed code!" + RESET);
    }

    private void runInTerminal() {
        System.out.println("\n" + BOLD + CYAN + "--- LIVE OUTPUT TERMINAL ---" + RESET);
        CodeRunner.RunResult rr = codeRunner.compileAndRun(currentCode);
        if (!rr.stdout.isEmpty()) {
            System.out.println(GREEN + rr.stdout + RESET);
        }
        if (!rr.stderr.isEmpty()) {
            System.out.println(RED + rr.stderr + RESET);
        }
        if (!rr.crashDiagnosis.isEmpty()) {
            System.out.println(YELLOW + BOLD + ">> " + rr.crashDiagnosis + RESET);
        }
        System.out.println(DIM + String.format("[Exit Code: %d | Runtime: %d ms]", rr.exitCode, rr.elapsedMs) + RESET);
    }

    private void exportReport() {
        if (lastDiagnostics.isEmpty()) {
            runAnalysis();
        }
        CodeMetrics m = CodeMetrics.compute(currentCode, lastDiagnostics);
        String outName = "JavaLens_Audit_Report.txt";
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outName), StandardCharsets.UTF_8))) {
            pw.println("======================================================");
            pw.println("  JavaLens Pro - Pure Java Code Audit Report");
            pw.println("======================================================");
            pw.printf("Health Score: %d/100 (%s) | Cyclomatic Complexity: %d%n", m.healthScore, m.healthGrade, m.cyclomaticComplexity);
            pw.printf("Lines: %d total (%d code, %d comments) | Classes: %d | Methods: %d%n",
                    m.totalLines, m.codeLines, m.commentLines, m.classCount, m.methodCount);
            pw.println("OOP Concepts: " + String.join(", ", m.oopConcepts));
            pw.println("\nDiagnostics (" + lastDiagnostics.size() + "):");
            for (DiagnosticItem d : lastDiagnostics) {
                pw.println("  - " + d);
            }
            System.out.println(GREEN + "Saved audit report to: " + new File(outName).getAbsolutePath() + RESET);
        } catch (IOException e) {
            System.out.println(RED + "Export failed: " + e.getMessage() + RESET);
        }
    }

    private void printCodeWithLineNumbers(String code) {
        System.out.println("\n" + BOLD + CYAN + "--- CURRENT JAVA SOURCE ---" + RESET);
        String[] lines = code.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            System.out.printf("%s%3d |%s %s%n", DIM, i + 1, RESET, lines[i]);
        }
    }

    private void printHealthBar(CodeMetrics m) {
        int filled = m.healthScore / 10;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < 10; i++) bar.append(i < filled ? "#" : "-");
        bar.append("]");
        String col = m.healthScore >= 85 ? GREEN : (m.healthScore >= 60 ? YELLOW : RED);

        System.out.println(BOLD + "Code Health: " + col + bar + " " + m.healthScore + "/100 (Grade " + m.healthGrade + ")" + RESET);
        System.out.printf("Metrics: %d code lines | %d class(es) | %d method(s) | Cyclomatic Complexity: %d%n",
                m.codeLines, m.classCount, m.methodCount, m.cyclomaticComplexity);
        System.out.println("OOP Detected: " + CYAN + String.join(", ", m.oopConcepts) + RESET);
    }
}
