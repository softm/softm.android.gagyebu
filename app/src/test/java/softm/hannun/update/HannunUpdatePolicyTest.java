package softm.hannun.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HannunUpdatePolicyTest {
    @Test public void defaultsToFlexible() {
        assertEquals(HannunUpdatePolicy.Mode.FLEXIBLE, HannunUpdatePolicy.choose(0, true, true));
        assertEquals(HannunUpdatePolicy.Mode.FLEXIBLE, HannunUpdatePolicy.choose(3, true, true));
    }

    @Test public void immediateRequiresHighPlayPriorityAndPermission() {
        assertEquals(HannunUpdatePolicy.Mode.IMMEDIATE, HannunUpdatePolicy.choose(4, true, true));
        assertEquals(HannunUpdatePolicy.Mode.IMMEDIATE, HannunUpdatePolicy.choose(5, false, true));
        assertEquals(HannunUpdatePolicy.Mode.FLEXIBLE, HannunUpdatePolicy.choose(5, true, false));
        assertEquals(HannunUpdatePolicy.Mode.NONE, HannunUpdatePolicy.choose(0, false, true));
    }

    @Test public void promptsAreThrottledButRecoverFromClockReset() {
        long hour = 60L * 60 * 1000;
        assertTrue(HannunUpdatePolicy.intervalElapsed(0, 100, 24 * hour));
        assertFalse(HannunUpdatePolicy.intervalElapsed(100, 100 + hour, 24 * hour));
        assertTrue(HannunUpdatePolicy.intervalElapsed(100, 100 + 24 * hour, 24 * hour));
        assertTrue(HannunUpdatePolicy.intervalElapsed(1000, 100, 24 * hour));
    }
}
