import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * JavaLens Pro - VS Code-Style Dark Swing Mini-IDE (100% Standard JDK Swing)
 * Features line numbers, clickable diagnostics that jump to exact lines, Auto-Fix with diff viewer,
 * Code Health metrics, projector font zoom, and a live SwingWorker-powered Output Terminal.
 */
public class JavaLensGUI extends JFrame {

    private static final Color BG_DARK = new Color(15, 23, 42);
    private static final Color BG_PANEL = new Color(30, 41, 59);
    private static final Color BG_EDITOR = new Color(17, 24, 39);
    private static final Color FG_TEXT = new Color(241, 245, 249);
    private static final Color FG_MUTED = new Color(148, 163, 184);
    private static final Color ACCENT_EMERALD = new Color(16, 185, 129);
    private static final Color ACCENT_SKY = new Color(14, 165, 233);
    private static final Color ACCENT_AMBER = new Color(245, 158, 11);

    private final CompilerEngine compilerEngine = new CompilerEngine();
    private final StaticAnalyzer staticAnalyzer = new StaticAnalyzer();
    private final AutoFixer autoFixer = new AutoFixer();
    private final CodeRunner codeRunner = new CodeRunner();

    private JTextArea codeEditor;
    private JTextArea lineNumbers;
    private DefaultListModel<DiagnosticItem> diagModel;
    private JList<DiagnosticItem> diagList;
    private JTextArea diffArea;
    private JTextArea metricsArea;
    private JTextArea consoleArea;
    private JProgressBar healthBar;
    private JLabel statusLabel;
    private int editorFontSize = 14;
    private List<DiagnosticItem> lastDiagnostics = new ArrayList<>();

    public JavaLensGUI() {
        super("JavaLens Pro - Pure Java Error Detector, Code Suggestor & Live Runner");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1240, 760);
        setLocationRelativeTo(null);
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(8, 8));

        add(buildToolbar(), BorderLayout.NORTH);
        add(buildMainSplit(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        loadCode(BuggyPresets.getPreset(0));
        runAnalysisAsync();
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        bar.setBackground(BG_PANEL);
        bar.setBorder(new EmptyBorder(4, 8, 4, 8));

        JComboBox<String> presetBox = new JComboBox<>(BuggyPresets.NAMES);
        presetBox.setBackground(BG_DARK);
        presetBox.setForeground(FG_TEXT);
        presetBox.addActionListener(e -> {
            loadCode(BuggyPresets.getPreset(presetBox.getSelectedIndex()));
            runAnalysisAsync();
        });

        JButton btnAnalyze = makeBtn("Analyze", ACCENT_SKY);
        btnAnalyze.addActionListener(e -> runAnalysisAsync());

        JButton btnAutoFix = makeBtn("Auto-Fix All", ACCENT_AMBER);
        btnAutoFix.addActionListener(e -> runAutoFix());

        JButton btnRun = makeBtn("Run Code >", ACCENT_EMERALD);
        btnRun.addActionListener(e -> runCodeAsync());

        JButton btnOpen = makeBtn("Open .java", new Color(71, 85, 105));
        btnOpen.addActionListener(e -> openJavaFile());

        JButton btnExport = makeBtn("Export Report", new Color(71, 85, 105));
        btnExport.addActionListener(e -> exportReport());

        JButton btnZoomOut = makeBtn("A-", new Color(51, 65, 85));
        btnZoomOut.addActionListener(e -> adjustFont(-2));

        JButton btnZoomIn = makeBtn("A+", new Color(51, 65, 85));
        btnZoomIn.addActionListener(e -> adjustFont(2));

        bar.add(presetBox);
        bar.add(btnAnalyze);
        bar.add(btnAutoFix);
        bar.add(btnRun);
        bar.add(btnOpen);
        bar.add(btnExport);
        bar.add(btnZoomOut);
        bar.add(btnZoomIn);
        return bar;
    }

    private JSplitPane buildMainSplit() {
        // Left: Line-Numbered Editor
        codeEditor = new JTextArea();
        codeEditor.setBackground(BG_EDITOR);
        codeEditor.setForeground(FG_TEXT);
        codeEditor.setCaretColor(ACCENT_EMERALD);
        codeEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, editorFontSize));
        codeEditor.setTabSize(4);
        codeEditor.setBorder(new EmptyBorder(8, 10, 8, 10));

        lineNumbers = new JTextArea("1\n");
        lineNumbers.setEditable(false);
        lineNumbers.setBackground(new Color(15, 23, 42));
        lineNumbers.setForeground(FG_MUTED);
        lineNumbers.setFont(new Font(Font.MONOSPACED, Font.PLAIN, editorFontSize));
        lineNumbers.setBorder(new EmptyBorder(8, 8, 8, 8));

        codeEditor.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateLineNumbers(); }
            public void removeUpdate(DocumentEvent e) { updateLineNumbers(); }
            public void changedUpdate(DocumentEvent e) { updateLineNumbers(); }
        });

        JScrollPane editorScroll = new JScrollPane(codeEditor);
        editorScroll.setRowHeaderView(lineNumbers);
        editorScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BG_PANEL), "Java Source Editor", 0, 0, null, FG_MUTED));

        // Right: Diagnostics + Diff + Metrics Tabs
        diagModel = new DefaultListModel<>();
        diagList = new JList<>(diagModel);
        diagList.setBackground(BG_EDITOR);
        diagList.setForeground(FG_TEXT);
        diagList.setCellRenderer(new DiagnosticRenderer());
        diagList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                DiagnosticItem item = diagList.getSelectedValue();
                if (item != null) jumpToLine(item.getLine());
            }
        });

        diffArea = createReadOnlyArea();
        metricsArea = createReadOnlyArea();

        JPanel metricsPanel = new JPanel(new BorderLayout(6, 6));
        metricsPanel.setBackground(BG_EDITOR);
        healthBar = new JProgressBar(0, 100);
        healthBar.setStringPainted(true);
        healthBar.setPreferredSize(new Dimension(100, 28));
        metricsPanel.add(healthBar, BorderLayout.NORTH);
        metricsPanel.add(new JScrollPane(metricsArea), BorderLayout.CENTER);

        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("Diagnostics & Fixes", new JScrollPane(diagList));
        rightTabs.addTab("Before/After Diff", new JScrollPane(diffArea));
        rightTabs.addTab("Health & OOP Metrics", metricsPanel);

        JSplitPane topSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, editorScroll, rightTabs);
        topSplit.setResizeWeight(0.56);

        // Bottom: Live Output Terminal
        consoleArea = createReadOnlyArea();
        consoleArea.setText("Click 'Run Code >' to compile and execute your Java program in this live terminal.\n");
        JScrollPane consoleScroll = new JScrollPane(consoleArea);
        consoleScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(BG_PANEL), "Live Output Terminal & Runtime Crash Explainer", 0, 0, null, ACCENT_EMERALD));

        JSplitPane outerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topSplit, consoleScroll);
        outerSplit.setResizeWeight(0.72);
        return outerSplit;
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_PANEL);
        p.setBorder(new EmptyBorder(4, 12, 4, 12));
        statusLabel = new JLabel("Ready - 100% Pure Java Standard JDK");
        statusLabel.setForeground(FG_MUTED);
        p.add(statusLabel, BorderLayout.WEST);
        return p;
    }

    private JButton makeBtn(String text, Color bg) {
        JButton b = new JButton(text);
        b.setBackground(bg);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        return b;
    }

    private JTextArea createReadOnlyArea() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setBackground(BG_EDITOR);
        a.setForeground(FG_TEXT);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        a.setBorder(new EmptyBorder(8, 10, 8, 10));
        return a;
    }

    private void loadCode(String code) {
        codeEditor.setText(code);
        codeEditor.setCaretPosition(0);
        updateLineNumbers();
    }

    private void updateLineNumbers() {
        int lines = codeEditor.getLineCount();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) sb.append(String.format("%3d\n", i));
        lineNumbers.setText(sb.toString());
    }

    private void adjustFont(int delta) {
        editorFontSize = Math.max(10, Math.min(28, editorFontSize + delta));
        Font f = new Font(Font.MONOSPACED, Font.PLAIN, editorFontSize);
        codeEditor.setFont(f);
        lineNumbers.setFont(f);
    }

    private void jumpToLine(int lineNum) {
        try {
            int offset = codeEditor.getLineStartOffset(Math.max(0, lineNum - 1));
            codeEditor.setCaretPosition(offset);
            codeEditor.requestFocusInWindow();
        } catch (Exception ignored) {}
    }

    private void runAnalysisAsync() {
        final String code = codeEditor.getText();
        statusLabel.setText("Analyzing with javac + Static Analyzer...");
        new SwingWorker<List<DiagnosticItem>, Void>() {
            @Override
            protected List<DiagnosticItem> doInBackground() {
                List<DiagnosticItem> all = new ArrayList<>();
                all.addAll(compilerEngine.compileAndDiagnose(code));
                all.addAll(staticAnalyzer.analyze(code));
                all.sort(Comparator.comparingInt(DiagnosticItem::getLine));
                return all;
            }

            @Override
            protected void done() {
                try {
                    lastDiagnostics = get();
                    diagModel.clear();
                    for (DiagnosticItem d : lastDiagnostics) diagModel.addElement(d);

                    CodeMetrics m = CodeMetrics.compute(code, lastDiagnostics);
                    healthBar.setValue(m.healthScore);
                    healthBar.setString("Health Score: " + m.healthScore + "/100 (Grade " + m.healthGrade + ")");
                    healthBar.setForeground(m.healthScore >= 85 ? ACCENT_EMERALD : (m.healthScore >= 60 ? ACCENT_AMBER : new Color(244, 63, 94)));

                    metricsArea.setText(String.format(
                            "Total Lines:            %d\nCode Lines:             %d\nComment Lines:          %d\n"
                                    + "Classes / Interfaces:   %d\nMethods:                %d\nCyclomatic Complexity:  %d\n"
                                    + "Issues Found:           %d\n\nOOP Concepts Detected:\n  * %s\n",
                            m.totalLines, m.codeLines, m.commentLines, m.classCount, m.methodCount,
                            m.cyclomaticComplexity, lastDiagnostics.size(), String.join("\n  * ", m.oopConcepts)));

                    statusLabel.setText("Analysis complete - " + lastDiagnostics.size() + " issue(s) | Health: " + m.healthScore + "/100");
                } catch (Exception ex) {
                    statusLabel.setText("Analysis error: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void runAutoFix() {
        AutoFixer.FixResult res = autoFixer.autoFix(codeEditor.getText());
        if (res.appliedFixes.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No automatic repairs needed.", "Auto-Fix", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        loadCode(res.fixedCode);
        StringBuilder sb = new StringBuilder("Applied Fixes (" + res.appliedFixes.size() + "):\n");
        for (String f : res.appliedFixes) sb.append("  [OK] ").append(f).append("\n");
        sb.append("\n--- LINE DIFF ---\n");
        for (String d : res.diffLines) sb.append(d).append("\n");
        diffArea.setText(sb.toString());
        runAnalysisAsync();
    }

    private void runCodeAsync() {
        final String code = codeEditor.getText();
        consoleArea.setText("Compiling & executing in child JVM...\n");
        new SwingWorker<CodeRunner.RunResult, Void>() {
            @Override
            protected CodeRunner.RunResult doInBackground() {
                return codeRunner.compileAndRun(code);
            }

            @Override
            protected void done() {
                try {
                    CodeRunner.RunResult rr = get();
                    StringBuilder out = new StringBuilder();
                    if (!rr.stdout.isEmpty()) out.append(rr.stdout).append("\n");
                    if (!rr.stderr.isEmpty()) out.append("[STDERR]\n").append(rr.stderr).append("\n");
                    if (!rr.crashDiagnosis.isEmpty()) out.append(">> ").append(rr.crashDiagnosis).append("\n");
                    out.append(String.format("[Finished | Exit Code: %d | Time: %d ms]", rr.exitCode, rr.elapsedMs));
                    consoleArea.setText(out.toString());
                } catch (Exception ex) {
                    consoleArea.setText("Runner failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void openJavaFile() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                loadCode(new String(Files.readAllBytes(fc.getSelectedFile().toPath()), StandardCharsets.UTF_8));
                runAnalysisAsync();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not open file: " + ex.getMessage());
            }
        }
    }

    private void exportReport() {
        File out = new File("JavaLens_Audit_Report.txt");
        CodeMetrics m = CodeMetrics.compute(codeEditor.getText(), lastDiagnostics);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8))) {
            pw.println("JavaLens Pro - Pure Java Audit Report");
            pw.printf("Health: %d/100 (%s) | Complexity: %d%n", m.healthScore, m.healthGrade, m.cyclomaticComplexity);
            for (DiagnosticItem d : lastDiagnostics) pw.println(" - " + d);
            JOptionPane.showMessageDialog(this, "Exported report to:\n" + out.getAbsolutePath());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage());
        }
    }

    private static class DiagnosticRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            DiagnosticItem d = (DiagnosticItem) value;
            String colorHex = d.getSeverity() == DiagnosticItem.Severity.ERROR ? "#FB7185"
                    : (d.getSeverity() == DiagnosticItem.Severity.WARNING ? "#FBBF24" : "#38BDF8");
            String html = String.format(
                    "<html><div style='padding:6px;width:360px;'>"
                            + "<b style='color:%s;'>[%s | %s] Line %d: %s</b><br>"
                            + "<span style='color:#CBD5E1;font-size:11px;'>%s</span><br>"
                            + "<span style='color:#34D399;font-size:11px;'>Fix: %s</span>"
                            + "</div></html>",
                    colorHex, d.getSeverity(), d.getRuleId(), d.getLine(),
                    esc(d.getTitle()), esc(d.getMessage()), esc(d.getSuggestion()));
            JLabel lbl = (JLabel) super.getListCellRendererComponent(list, html, index, isSelected, cellHasFocus);
            lbl.setBackground(isSelected ? new Color(51, 65, 85) : BG_EDITOR);
            lbl.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BG_PANEL));
            return lbl;
        }

        private static String esc(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
