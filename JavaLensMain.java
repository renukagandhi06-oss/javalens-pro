import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

/**
 * JavaLens Pro - Triple-Mode Pure Java Entry Point (Cloud Render + Desktop + CLI)
 * Usage:
 *   java JavaLensMain --web  (Launches built-in Java HttpServer Web Studio; reads PORT env var for Render.com)
 *   java JavaLensMain        (Launches VS Code-style Dark Swing Desktop IDE on laptop/PC)
 *   java JavaLensMain --cli  (Launches ANSI Terminal Studio on phone/headless terminal)
 */
public class JavaLensMain {
    public static void main(String[] args) {
        boolean webMode = false;
        boolean forceCli = false;
        boolean forceGui = false;

        int port = 8080;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.trim().isEmpty()) {
            try {
                port = Integer.parseInt(envPort.trim());
                webMode = true;
            } catch (NumberFormatException ignored) {}
        }

        for (String arg : args) {
            if ("--web".equalsIgnoreCase(arg)) webMode = true;
            else if ("--cli".equalsIgnoreCase(arg)) forceCli = true;
            else if ("--gui".equalsIgnoreCase(arg)) forceGui = true;
        }

        if (webMode && !forceCli && !forceGui) {
            try {
                new JavaLensWebServer().start(port);
            } catch (Exception e) {
                System.err.println("Could not start Java Web Server on port " + port + ": " + e.getMessage());
            }
            return;
        }

        if (forceCli || (!forceGui && GraphicsEnvironment.isHeadless())) {
            new JavaLensCLI().start();
        } else {
            SwingUtilities.invokeLater(() -> new JavaLensGUI().setVisible(true));
        }
    }
}
