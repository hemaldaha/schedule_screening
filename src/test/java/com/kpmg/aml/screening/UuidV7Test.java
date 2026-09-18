package com.kpmg.aml.screening;

import com.kpmg.aml.screening.util.UuidV7;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @Test
    void generate_returnsNonNull() {
        assertThat(UuidV7.generate()).isNotNull();
    }

    @Test
    void generate_conformsToUuidStringFormat() {
        String uuid = UuidV7.generate();
        assertThat(UUID_PATTERN.matcher(uuid).matches())
                .as("Expected UUIDv7 format but got: %s", uuid)
                .isTrue();
    }

    @Test
    void generate_versionNibbleIs7() {
        // In the UUID string "xxxxxxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx", position 14 is the version.
        assertThat(UuidV7.generate().charAt(14)).isEqualTo('7');
    }

    @Test
    void generate_variantBitsAreRfc4122() {
        // Position 19 in the UUID string must be one of 8, 9, a, b.
        char variant = UuidV7.generate().charAt(19);
        assertThat("89ab".indexOf(variant))
                .as("Variant char '%c' is not in {8,9,a,b}", variant)
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    void generate_producesUniqueValues() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            ids.add(UuidV7.generate());
        }
        assertThat(ids).hasSize(500);
    }

    @Test
    void generate_twoConsecutiveUuidsAreOrdered() throws InterruptedException {
        // The top 48 bits encode the millisecond timestamp.  Two UUIDs generated
        // in *different* milliseconds must be strictly ordered.  We sleep 2 ms to
        // guarantee the clock advances before generating the second UUID.
        String first = UuidV7.generate();
        Thread.sleep(2);
        String second = UuidV7.generate();
        assertThat(first.compareTo(second)).isLessThan(0);
    }
}
