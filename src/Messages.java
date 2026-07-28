package lap.menu;

// Shadow of the tool's lap.menu.Messages. The real one pops modal Swing
// JOptionPane dialogs (e.g. "some facets have been completely overwritten")
// that block a headless run forever. This replacement routes such messages to
// the console instead. Placed ahead of the tool's class in the build so it wins.
// Only showMessage() is used on the load/frost/export path; the getters are
// GUI-only and never called there (verified), but stubbed for completeness.
public class Messages {
    public static volatile String lastMessage = null;

    public static void showMessage(String m) {
        lastMessage = m;
        if (m != null) System.err.println("[frost] " + m.replace("\n", " "));
    }

    public static String getString(String key) { return key; }
    public static int getInteger(String key) { return 0; }
    public static double getDouble(String key) { return 0.0; }
    public static float getFloat(String key) { return 0.0f; }
}
