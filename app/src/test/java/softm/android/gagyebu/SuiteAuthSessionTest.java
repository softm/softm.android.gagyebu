package softm.android.gagyebu;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Base64;

public class SuiteAuthSessionTest {
    private static final String REQUEST = "A".repeat(43), CODE = "B".repeat(43);
    private static final String HASH = "#request=" + REQUEST + "&code=" + CODE;
    @Test public void acceptsOnlyExpectedAppCallbackAndRequest() {
        for (String url : new String[]{SuiteAuthSession.RETURN_URL, SuiteAuthSession.FALLBACK_URL})
            assertEquals(CODE, SuiteAuthSession.readReturn(url + HASH, REQUEST, 1000, 1001).get("code"));
        assertNull(SuiteAuthSession.readReturn(SuiteAuthSession.RETURN_URL + HASH, CODE, 1000, 1001));
    }
    @Test public void rejectsWrongHostsPathsCredentialsAndQuery() {
        for (String url : new String[]{"https://softm.github.io.evil.test/account/android/gagyebu/",
            "http://softm.github.io/account/android/gagyebu/", "https://evil@softm.github.io/account/android/gagyebu/",
            "https://softm.github.io:443/account/android/gagyebu/", "https://softm.github.io/account/android/gagyebu/extra",
            "softm.android.gagyebu://evil/callback", SuiteAuthSession.RETURN_URL + "?request=x"})
            assertNull(url, SuiteAuthSession.readReturn(url + HASH, REQUEST, 1000, 1001));
    }
    @Test public void rejectsExpiredMissingAndDuplicateProofs() {
        assertNull(SuiteAuthSession.readReturn(SuiteAuthSession.RETURN_URL + HASH, "", 0, 1001));
        assertNull(SuiteAuthSession.readReturn(SuiteAuthSession.RETURN_URL + HASH, REQUEST, 1000, 301001));
        assertNull(SuiteAuthSession.readReturn(SuiteAuthSession.RETURN_URL + HASH, REQUEST, 1000, 999));
        for (String hash : new String[]{"", "#request=" + REQUEST, HASH + "&request=" + REQUEST,
            HASH + "&error=cancelled", HASH + "&token=secret", "#request=" + REQUEST + "&code=short"})
            assertNull(SuiteAuthSession.readReturn(SuiteAuthSession.RETURN_URL + hash, REQUEST, 1000, 1001));
    }
    @Test public void cancellationRequiresTheMatchingRequest() {
        assertEquals("cancelled", SuiteAuthSession.readReturn(SuiteAuthSession.RETURN_URL + "#request=" + REQUEST + "&error=cancelled", REQUEST, 1000, 1001).get("error"));
    }
    @Test public void pkceMatchesRfc7636Vector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Base64.getUrlEncoder().withoutPadding().encodeToString(SuiteAuthSession.challenge(verifier)));
    }
}
