/**
 * JavaLens Pro - VTU 3rd Semester Buggy & Reference Lab Presets
 * Allows instant 1-click live demonstration of Compiler, Static, Auto-Fix, and Runtime features.
 */
public class BuggyPresets {

    public static final String[] NAMES = {
            "Preset 1: String ==, Array Bound & Typo Bugs",
            "Preset 2: OOP & Multithreading (Thread.run & Empty Catch)",
            "Preset 3: Live Runtime Crash Demo (ArrayOutOfBounds)",
            "Preset 4: Clean Reference Program (100/100 A+)"
    };

    public static String getPreset(int index) {
        switch (index) {
            case 0:
                return "public class StudentMarks {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        ArrayList<String> names = new ArrayList<>();\n"
                        + "        names.add(\"Girish\");\n"
                        + "        String target = new String(\"Girish\");\n"
                        + "        if (target == \"Girish\") {\n"
                        + "            Systm.out.pritnln(\"Student found!\")\n"
                        + "        }\n"
                        + "        int[] marks = {88, 92, 95};\n"
                        + "        for (int i = 0; i <= marks.length; i++) {\n"
                        + "            System.out.println(\"Mark: \" + marks[i]);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n";
            case 1:
                return "class WorkerTask extends Thread {\n"
                        + "    public void run() {\n"
                        + "        System.out.println(\"Worker thread running: \" + Thread.currentThread().getName());\n"
                        + "    }\n"
                        + "}\n\n"
                        + "public class ThreadDemo {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        WorkerTask t1 = new WorkerTask();\n"
                        + "        t1.run();\n"
                        + "        try {\n"
                        + "            int x = 10 / 2;\n"
                        + "            System.out.println(\"Result: \" + x);\n"
                        + "        } catch (Exception e) {}\n"
                        + "    }\n"
                        + "}\n";
            case 2:
                return "public class CrashDemo {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        int[] scores = {75, 85, 95};\n"
                        + "        System.out.println(\"Starting array scan...\");\n"
                        + "        for (int i = 0; i <= scores.length; i++) {\n"
                        + "            System.out.println(\"Score \" + i + \": \" + scores[i]);\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n";
            case 3:
            default:
                return "import java.util.*;\n\n"
                        + "public class CleanVtuDemo {\n"
                        + "    public static void main(String[] args) {\n"
                        + "        List<String> subjects = Arrays.asList(\"OOP with Java\", \"DSA\", \"OS\");\n"
                        + "        System.out.println(\"=== VTU 3rd Sem CSE Subjects ===\");\n"
                        + "        for (int i = 0; i < subjects.size(); i++) {\n"
                        + "            System.out.println((i + 1) + \". \" + subjects.get(i));\n"
                        + "        }\n"
                        + "    }\n"
                        + "}\n";
        }
    }
}
