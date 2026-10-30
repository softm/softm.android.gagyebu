package softm.android.gagyebu;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AdConfigurationTest {
    @Test
    public void acceptsAutorunnaviNativeUnit() {
        assertTrue(
            AdConfiguration.isValidAdMobUnitId("ca-app-pub-4219853075548260/4541245696")
        );
    }

    @Test
    public void rejectsMalformedAdMobUnit() {
        assertFalse(AdConfiguration.isValidAdMobUnitId("ca-app-pub-other/123"));
        assertFalse(AdConfiguration.isValidAdMobUnitId(""));
    }

    @Test
    public void acceptsOnlyConfiguredAdFitFormat() {
        assertTrue(AdConfiguration.isValidAdFitBannerId("DAN-Abc123"));
        assertFalse(AdConfiguration.isValidAdFitBannerId(""));
        assertFalse(AdConfiguration.isValidAdFitBannerId("ca-app-pub-123/456"));
    }
}
