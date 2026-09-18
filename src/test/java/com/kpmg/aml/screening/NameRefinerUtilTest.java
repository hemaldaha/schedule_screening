package com.kpmg.aml.screening;

import com.kpmg.aml.screening.util.NameRefinerUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NameRefinerUtilTest {

    @Test
    void refineNull_returnsEmpty() {
        assertThat(NameRefinerUtil.refineName(null)).isEqualTo("");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void refineBlank_returnsEmpty(String input) {
        assertThat(NameRefinerUtil.refineName(input)).isEqualTo("");
    }

    @Test
    void refineName_uppercasesOutput() {
        assertThat(NameRefinerUtil.refineName("kamal perera")).isEqualTo("KAMAL PERERA");
    }

    @Test
    void refineName_replacesHyphenWithSpace() {
        assertThat(NameRefinerUtil.refineName("John-Smith")).isEqualTo("JOHN SMITH");
    }

    @Test
    void refineName_replacesApostropheWithSpace() {
        assertThat(NameRefinerUtil.refineName("O'Brien")).isEqualTo("O BRIEN");
    }

    @Test
    void refineName_preservesDigits() {
        assertThat(NameRefinerUtil.refineName("Agent 007")).isEqualTo("AGENT 007");
    }

    @Test
    void refineName_stripsControlCharacters() {
        // 0x01 = SOH — a non-printable control character
        assertThat(NameRefinerUtil.refineName("John\u0001Smith")).isEqualTo("JOHN SMITH");
    }

    @Test
    void refineName_removesSolrAndOperator() {
        assertThat(NameRefinerUtil.refineName("Hassan AND Malik")).isEqualTo("HASSAN MALIK");
    }

    @Test
    void refineName_removesSolrOrOperator() {
        assertThat(NameRefinerUtil.refineName("Ahmed OR Omar")).isEqualTo("AHMED OMAR");
    }

    @Test
    void refineName_removesSolrNotOperator() {
        assertThat(NameRefinerUtil.refineName("NOT Listed")).isEqualTo("LISTED");
    }

    @Test
    void refineName_doesNotRemoveAndSubstringInsideName() {
        // "Orlando" contains "and" as a substring but NOT as a whole word
        assertThat(NameRefinerUtil.refineName("Orlando")).isEqualTo("ORLANDO");
    }

    @Test
    void refineName_doesNotRemoveOrSubstringInsideName() {
        // "Norbert" contains "or" as substring
        assertThat(NameRefinerUtil.refineName("Norbert")).isEqualTo("NORBERT");
    }

    @Test
    void refineName_doesNotRemoveNotSubstringInsideName() {
        // "Noticeable" contains "not" at word start but not as standalone word
        assertThat(NameRefinerUtil.refineName("Anothony")).isEqualTo("ANOTHONY");
    }

    @Test
    void refineName_collapsesExtraWhitespace() {
        assertThat(NameRefinerUtil.refineName("John   Smith")).isEqualTo("JOHN SMITH");
    }

    @Test
    void refineName_trimsBoundaryWhitespace() {
        assertThat(NameRefinerUtil.refineName("  John Smith  ")).isEqualTo("JOHN SMITH");
    }
}
