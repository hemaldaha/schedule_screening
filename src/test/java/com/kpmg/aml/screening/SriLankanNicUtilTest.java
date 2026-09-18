package com.kpmg.aml.screening;

import com.kpmg.aml.screening.util.SriLankanNicUtil;
import com.kpmg.aml.screening.util.SriLankanNicUtil.NicPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for SriLankanNicUtil.
 *
 * Test NIC values:
 *   Old format (male)  : "851234567V"   → birth year 1985, day 123
 *   Old format (female): "856234567V"   → birth year 1985, day 623 (500+123)
 *   New format (19xx)  : "198512304567" → birth year 1985, day 123
 *   New format (20xx)  : "200012304567" → birth year 2000, day 123 (no old format)
 */
class SriLankanNicUtilTest {

    // ── Null / blank / garbage ────────────────────────────────────────────────

    @Test
    void resolveNull_returnsUnusablePair() {
        NicPair pair = SriLankanNicUtil.resolve(null);
        assertThat(pair.isUsable()).isFalse();
        assertThat(pair.oldNic()).isNull();
        assertThat(pair.newNic()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void resolveBlank_returnsUnusablePair(String input) {
        NicPair pair = SriLankanNicUtil.resolve(input);
        assertThat(pair.isUsable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABCDEFGHIJ", "851234$67V", "85123-567V"})
    void resolveInvalidChars_returnsUnusablePair(String input) {
        NicPair pair = SriLankanNicUtil.resolve(input);
        assertThat(pair.isUsable()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567890123", "8512345"})
    void resolveWrongLength_returnsUnusablePair(String input) {
        NicPair pair = SriLankanNicUtil.resolve(input);
        assertThat(pair.isUsable()).isFalse();
    }

    // ── Valid old NIC (10-char) ───────────────────────────────────────────────

    @Test
    void resolveValidOldMaleNic_returnsBothFormats() {
        // "851234567V": YY=85 → 1985, DDD=123, SSSS=4567, male
        NicPair pair = SriLankanNicUtil.resolve("851234567V");
        assertThat(pair.isUsable()).isTrue();
        assertThat(pair.oldNic()).isEqualTo("851234567V");
        // New: "19" + "85" + "123" + "0" + "4567" = "198512304567"
        assertThat(pair.newNic()).isEqualTo("198512304567");
    }

    @Test
    void resolveValidOldFemaleNic_returnsBothFormats() {
        // "856234567V": DDD=623, female (623-500=123 day of year)
        NicPair pair = SriLankanNicUtil.resolve("856234567V");
        assertThat(pair.isUsable()).isTrue();
        assertThat(pair.oldNic()).isEqualTo("856234567V");
        // New: "19" + "85" + "623" + "0" + "4567" = "198562304567"
        assertThat(pair.newNic()).isEqualTo("198562304567");
    }

    @Test
    void resolveOldNicLowercaseV_acceptsAndNormalises() {
        // lowercase 'v' should be accepted and normalised to uppercase
        NicPair pair = SriLankanNicUtil.resolve("851234567v");
        assertThat(pair.isUsable()).isTrue();
        assertThat(pair.oldNic()).isEqualTo("851234567V");
    }

    @Test
    void resolveOldNicWithSuffix_X_isAccepted() {
        // X suffix is also valid
        NicPair pair = SriLankanNicUtil.resolve("851234567X");
        assertThat(pair.isUsable()).isTrue();
    }

    @Test
    void resolveOldNicWithLeadingSpaces_cleanedAndValidated() {
        NicPair pair = SriLankanNicUtil.resolve("  851234567V");
        assertThat(pair.isUsable()).isTrue();
        assertThat(pair.oldNic()).isEqualTo("851234567V");
    }

    // ── Valid new NIC (12-char) ───────────────────────────────────────────────

    @Test
    void resolveValidNewNic19xx_returnsBothFormats() {
        // "198512304567": year=1985, day=123
        NicPair pair = SriLankanNicUtil.resolve("198512304567");
        assertThat(pair.isUsable()).isTrue();
        assertThat(pair.newNic()).isEqualTo("198512304567");
        assertThat(pair.oldNic()).isEqualTo("851234567V");
    }

    @Test
    void resolveValidNewNic20xx_hasNoOldFormat() {
        // No old NIC format exists for 20xx birth years
        NicPair pair = SriLankanNicUtil.resolve("200012304567");
        assertThat(pair.isUsable()).isTrue();
        assertThat(pair.newNic()).isEqualTo("200012304567");
        assertThat(pair.oldNic()).isNull();
    }

    // ── Validation edge cases ─────────────────────────────────────────────────

    @Test
    void resolveFutureYear_returnsUnusable() {
        // year 2999 is far in the future — always invalid
        NicPair pair = SriLankanNicUtil.resolve("299912304567");
        assertThat(pair.isUsable()).isFalse();
    }

    @Test
    void resolveDeadZoneDay_returnsUnusable() {
        // day=400: 367 < 400 < 500 → dead zone (not male ≤366, not female ≥501)
        // "854004567V": YY=85, DDD=400
        NicPair pair = SriLankanNicUtil.resolve("854004567V");
        assertThat(pair.isUsable()).isFalse();
    }

    @Test
    void resolveDayZero_returnsUnusable() {
        // day=0 is invalid (days start at 1)
        // "850004567V": YY=85, DDD=000
        NicPair pair = SriLankanNicUtil.resolve("850004567V");
        assertThat(pair.isUsable()).isFalse();
    }

    // ── NicPair helpers ───────────────────────────────────────────────────────

    @Test
    void nicPairIsUsable_trueWhenAtLeastOneNicPresent() {
        assertThat(new NicPair("old", null).isUsable()).isTrue();
        assertThat(new NicPair(null, "new").isUsable()).isTrue();
        assertThat(new NicPair(null, null).isUsable()).isFalse();
    }
}
