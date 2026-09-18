package com.kpmg.aml.screening;

import com.kpmg.aml.screening.engine.solr.AdvancedScreeningSolrQueryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdvancedScreeningSolrQueryBuilderTest {

    private AdvancedScreeningSolrQueryBuilder qb;

    @BeforeEach
    void setUp() {
        qb = new AdvancedScreeningSolrQueryBuilder();
    }

    // ── buildFIUQuery (NORMAL) ─────────────────────────────────────────────────

    @Test
    void buildFIUQuery_nameOnly_containsNameTxtAndPhonetic() {
        String q = qb.buildFIUQuery("Kamal Perera", null, null);
        assertThat(q).contains("name_txt:");
        assertThat(q).contains("name_phonetic:");
    }

    @Test
    void buildFIUQuery_withOldAndNewNic_containsBothNicValues() {
        String q = qb.buildFIUQuery("Kamal Perera", "851234567V", "198512304567");
        assertThat(q).contains("nic_no_s:");
        assertThat(q).contains("851234567V");
        assertThat(q).contains("198512304567");
    }

    @Test
    void buildFIUQuery_withPassport_containsPassportField() {
        String q = qb.buildFIUQuery("Kamal Perera", null, null, "N1234567");
        assertThat(q).contains("passport_no_ss:");
        assertThat(q).contains("N1234567");
    }

    @Test
    void buildFIUQuery_nullEverything_returnsEmpty() {
        assertThat(qb.buildFIUQuery(null, null, null)).isEmpty();
    }

    @Test
    void buildFIUQuery_blankName_noNameClauses() {
        // Blank name → name_txt not present; NIC clause still present
        String q = qb.buildFIUQuery("  ", "851234567V", null);
        assertThat(q).doesNotContain("name_txt:");
        assertThat(q).contains("nic_no_s:");
    }

    // ── buildUNQuery (NORMAL) ──────────────────────────────────────────────────

    @Test
    void buildUNQuery_withName_containsNameAliasAndPhonetic() {
        String q = qb.buildUNQuery("Mohamed Hassan");
        assertThat(q).contains("name_txt:");
        assertThat(q).contains("alias_txt:");
        assertThat(q).contains("name_phonetic:");
    }

    @Test
    void buildUNQuery_nullName_returnsEmpty() {
        assertThat(qb.buildUNQuery(null)).isEmpty();
    }

    @Test
    void buildUNQuery_blankName_returnsEmpty() {
        assertThat(qb.buildUNQuery("   ")).isEmpty();
    }

    // ── buildLocalWatchListQuery (NORMAL) ─────────────────────────────────────

    @Test
    void buildLocalWatchListQuery_withNameAndNic_containsBothFields() {
        String q = qb.buildLocalWatchListQuery("Rashidi", "851234567V", null);
        assertThat(q).contains("name_txt:");
        assertThat(q).contains("nic_s:");
        assertThat(q).contains("851234567V");
    }

    @Test
    void buildLocalWatchListQuery_nicOnly_noNameClauses() {
        String q = qb.buildLocalWatchListQuery(null, "851234567V", "198512304567");
        assertThat(q).doesNotContain("name_txt:");
        assertThat(q).contains("nic_s:");
    }

    // ── buildFIUOrgQuery (NORMAL) ──────────────────────────────────────────────

    @Test
    void buildFIUOrgQuery_withLongTokens_containsNameTxtQuotes() {
        // Both tokens length > 5: "Liberation" and "Tigers"
        String q = qb.buildFIUOrgQuery("Liberation Tigers", "REG123");
        assertThat(q).contains("name_txt:");
        assertThat(q).contains("registration_no_s:");
    }

    @Test
    void buildFIUOrgQuery_registrationIsNA_omitsRegistrationField() {
        String q = qb.buildFIUOrgQuery("Liberation Tigers", "NA");
        assertThat(q).doesNotContain("registration_no_s:");
    }

    @Test
    void buildFIUOrgQuery_nullNameAndReg_returnsEmpty() {
        assertThat(qb.buildFIUOrgQuery(null, null)).isEmpty();
    }

    @Test
    void buildFIUOrgQuery_noPhonetic() {
        // Phonetic intentionally omitted for FIU-ORG
        String q = qb.buildFIUOrgQuery("Liberation Tigers", null);
        assertThat(q).doesNotContain("name_phonetic:");
    }

    // ── buildLXNXQuery (NORMAL) ────────────────────────────────────────────────

    @Test
    void buildLXNXQuery_withName_containsSearchNamesTxt() {
        String q = qb.buildLXNXQuery("Velupillai Prabhakaran");
        assertThat(q).contains("search_names_txt:");
    }

    @Test
    void buildLXNXQuery_nullName_returnsEmpty() {
        assertThat(qb.buildLXNXQuery(null)).isEmpty();
    }

    // ── RESTRICTED mode ────────────────────────────────────────────────────────

    @Test
    void buildRestrictedFIUQuery_containsExactPhraseAndFuzzy() {
        String q = qb.buildRestrictedFIUQuery("Rashidi", null, null, null);
        assertThat(q).contains("\"Rashidi\"");
        assertThat(q).contains("rashidi~1");
        assertThat(q).contains("name_phonetic:");
    }

    @Test
    void buildRestrictedFIUQuery_withNic_containsNicField() {
        String q = qb.buildRestrictedFIUQuery("Rashidi", "851234567V", "198512304567", null);
        assertThat(q).contains("nic_no_s:");
    }

    @Test
    void buildRestrictedFIUQuery_nullName_returnsEmpty() {
        assertThat(qb.buildRestrictedFIUQuery(null, null, null, null)).isEmpty();
    }

    @Test
    void buildRestrictedUNQuery_containsExactPhraseOnNameAndAlias() {
        String q = qb.buildRestrictedUNQuery("Rashidi");
        assertThat(q).contains("name_txt:");
        assertThat(q).contains("alias_txt:");
        assertThat(q).contains("\"Rashidi\"");
        assertThat(q).contains("rashidi~1");
    }

    @Test
    void buildRestrictedLocalWatchQuery_containsExactPhrase() {
        String q = qb.buildRestrictedLocalWatchQuery("Rashidi", null, null);
        assertThat(q).contains("name_txt:");
        assertThat(q).contains("\"Rashidi\"");
    }

    @Test
    void buildRestrictedLXNXQuery_exactPhraseOnly_noWildcardOrFuzzy() {
        String q = qb.buildRestrictedLXNXQuery("SOMAPALA");
        assertThat(q).isEqualTo("search_names_txt:\"somapala\"");
        assertThat(q).doesNotContain("*");
        assertThat(q).doesNotContain("~");
    }

    @Test
    void buildRestrictedLXNXQuery_nullName_returnsEmpty() {
        assertThat(qb.buildRestrictedLXNXQuery(null)).isEmpty();
    }

    // ── NIC_ONLY mode ──────────────────────────────────────────────────────────

    @Test
    void buildNicOnlyFIUQuery_withNic_containsNicFieldNoName() {
        String q = qb.buildNicOnlyFIUQuery("851234567V", "198512304567", null);
        assertThat(q).contains("nic_no_s:");
        assertThat(q).doesNotContain("name_txt:");
    }

    @Test
    void buildNicOnlyFIUQuery_withPassport_containsPassportField() {
        String q = qb.buildNicOnlyFIUQuery(null, null, "N1234567");
        assertThat(q).contains("passport_no_ss:");
    }

    @Test
    void buildNicOnlyFIUQuery_allNull_returnsEmpty() {
        assertThat(qb.buildNicOnlyFIUQuery(null, null, null)).isEmpty();
    }

    @Test
    void buildNicOnlyLocalWatchQuery_withNic_containsNicFieldNoName() {
        String q = qb.buildNicOnlyLocalWatchQuery("851234567V", "198512304567");
        assertThat(q).contains("nic_s:");
        assertThat(q).doesNotContain("name_txt:");
    }

    @Test
    void buildNicOnlyLocalWatchQuery_allNull_returnsEmpty() {
        assertThat(qb.buildNicOnlyLocalWatchQuery(null, null)).isEmpty();
    }
}
