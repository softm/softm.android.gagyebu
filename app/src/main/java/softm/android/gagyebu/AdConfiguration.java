package softm.android.gagyebu;

import java.util.regex.Pattern;

final class AdConfiguration {
    private static final Pattern ADMOB_UNIT_ID =
        Pattern.compile("ca-app-pub-\\d{16}/\\d{10}");
    private static final Pattern ADFIT_BANNER_ID =
        Pattern.compile("DAN-[A-Za-z0-9]+");

    private AdConfiguration() {
    }

    static boolean isValidAdMobUnitId(String value) {
        return value != null && ADMOB_UNIT_ID.matcher(value.trim()).matches();
    }

    static boolean isValidAdFitBannerId(String value) {
        return value != null && ADFIT_BANNER_ID.matcher(value.trim()).matches();
    }
}
