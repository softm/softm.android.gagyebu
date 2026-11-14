package softm.hannun.update;

/**
 * Shared policy for Play in-app updates across Hannun Android shells.
 * Play release priority 4-5 is reserved for security/critical fixes.
 */
public final class HannunUpdatePolicy {
    public enum Mode { NONE, FLEXIBLE, IMMEDIATE }

    public static final int IMMEDIATE_PRIORITY_THRESHOLD = 4;
    public static final long AUTO_PROMPT_INTERVAL_MS = 24L * 60 * 60 * 1000;
    public static final long DOWNLOADED_REMINDER_INTERVAL_MS = 6L * 60 * 60 * 1000;

    private HannunUpdatePolicy() {}

    public static Mode choose(int priority, boolean flexibleAllowed, boolean immediateAllowed) {
        if (priority >= IMMEDIATE_PRIORITY_THRESHOLD && immediateAllowed) return Mode.IMMEDIATE;
        if (flexibleAllowed) return Mode.FLEXIBLE;
        return Mode.NONE;
    }

    public static boolean intervalElapsed(long lastAt, long now, long interval) {
        return lastAt <= 0 || now < lastAt || now - lastAt >= interval;
    }
}
