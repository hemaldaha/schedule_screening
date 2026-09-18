package com.kpmg.aml.screening;

import com.kpmg.aml.screening.engine.ExceptionContext;
import com.kpmg.aml.screening.engine.ScreeningExceptionWriter;
import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.engine.solr.ScreeningMode;
import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import com.kpmg.aml.screening.entity.persistence.ScreeningExceptionRepository;
import com.kpmg.aml.screening.util.NameNormalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ScreeningExceptionWriter}.
 *
 * <p>{@code isNotApplicable} is a package-private static helper.
 * It is accessed via reflection so that all tests can live in the
 * single flat test package {@code com.kpmg.aml.screening}.
 */
@ExtendWith(MockitoExtension.class)
class ScreeningExceptionWriterTest {

    // ── Reflection bootstrap for package-private static isNotApplicable() ─────

    private static final Method IS_NOT_APPLICABLE;

    static {
        try {
            IS_NOT_APPLICABLE = ScreeningExceptionWriter.class.getDeclaredMethod(
                    "isNotApplicable", String.class, ScreeningMode.class);
            IS_NOT_APPLICABLE.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Delegates to the package-private static via reflection. */
    private boolean isNotApplicable(String type, ScreeningMode mode) {
        try {
            return (boolean) IS_NOT_APPLICABLE.invoke(null, type, mode);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Test fixtures ─────────────────────────────────────────────────────────

    @Mock ScreeningExceptionRepository repo;

    @InjectMocks ScreeningExceptionWriter writer;

    // ── isNotApplicable ────────────────────────────────────────────────────────

    @Test
    void isNotApplicable_FIUOrg_RESTRICTED_isTrue() {
        assertThat(isNotApplicable("FIU-ORG", ScreeningMode.RESTRICTED)).isTrue();
    }

    @Test
    void isNotApplicable_FIUOrg_NIC_ONLY_isTrue() {
        assertThat(isNotApplicable("FIU-ORG", ScreeningMode.NIC_ONLY)).isTrue();
    }

    @Test
    void isNotApplicable_FIUOrg_NORMAL_isFalse() {
        assertThat(isNotApplicable("FIU-ORG", ScreeningMode.NORMAL)).isFalse();
    }

    @Test
    void isNotApplicable_consoli_NIC_ONLY_isTrue() {
        assertThat(isNotApplicable("consoli", ScreeningMode.NIC_ONLY)).isTrue();
    }

    @Test
    void isNotApplicable_consoli_RESTRICTED_isFalse() {
        assertThat(isNotApplicable("consoli", ScreeningMode.RESTRICTED)).isFalse();
    }

    @Test
    void isNotApplicable_LXNX_NIC_ONLY_isTrue() {
        assertThat(isNotApplicable("LXNX", ScreeningMode.NIC_ONLY)).isTrue();
    }

    @Test
    void isNotApplicable_LXNX_RESTRICTED_isFalse() {
        // Item 3: LXNX query RUNS for RESTRICTED mode — result is recorded in exception row.
        // Candidates are excluded from scoring pipeline in SanctionStrategyBuilder, not here.
        assertThat(isNotApplicable("LXNX", ScreeningMode.RESTRICTED)).isFalse();
    }

    @Test
    void isNotApplicable_LXNX_NORMAL_isFalse() {
        assertThat(isNotApplicable("LXNX", ScreeningMode.NORMAL)).isFalse();
    }

    @Test
    void isNotApplicable_FIU_anyMode_isFalse() {
        assertThat(isNotApplicable("FIU", ScreeningMode.NORMAL)).isFalse();
        assertThat(isNotApplicable("FIU", ScreeningMode.RESTRICTED)).isFalse();
        assertThat(isNotApplicable("FIU", ScreeningMode.NIC_ONLY)).isFalse();
    }

    @Test
    void isNotApplicable_localWatchList_anyMode_isFalse() {
        assertThat(isNotApplicable("local_watchList", ScreeningMode.NIC_ONLY)).isFalse();
    }

    // ── setListResult ─────────────────────────────────────────────────────────

    @Test
    void setListResult_emptyCandidates_setsATTEMPTED() {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        writer.setListResult(exc, "FIU", List.of(), ScreeningMode.RESTRICTED);
        assertThat(exc.getFiuInd()).isEqualTo("ATTEMPTED");
    }

    @Test
    void setListResult_withCandidates_setsCANDIDATE_FOUND() {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        writer.setListResult(exc, "FIU", List.of(mockCandidate()), ScreeningMode.RESTRICTED);
        assertThat(exc.getFiuInd()).isEqualTo("CANDIDATE_FOUND");
    }

    @Test
    void setListResult_fiuOrgRestricted_setsNOT_APPLICABLE() {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        writer.setListResult(exc, "FIU-ORG", List.of(), ScreeningMode.RESTRICTED);
        assertThat(exc.getFiuOrg()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void setListResult_localWatchList_setsCorrectColumn() {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        writer.setListResult(exc, "local_watchList", List.of(), ScreeningMode.NORMAL);
        assertThat(exc.getLocalWatch()).isEqualTo("ATTEMPTED");
    }

    @Test
    void setListResult_consoli_setsUnConsolidated() {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        writer.setListResult(exc, "consoli", List.of(mockCandidate()), ScreeningMode.NORMAL);
        assertThat(exc.getUnConsolidated()).isEqualTo("CANDIDATE_FOUND");
    }

    // ── setListFailed ─────────────────────────────────────────────────────────

    @Test
    void setListFailed_setsFailedOnColumn() {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        writer.setListFailed(exc, "FIU", "SYSTEM_TIMEOUT", "connection timed out");
        assertThat(exc.getFiuInd()).isEqualTo("FAILED");
        assertThat(exc.getFailureReason()).isEqualTo("SYSTEM_TIMEOUT");
        assertThat(exc.getFailureDetails()).isEqualTo("connection timed out");
    }

    @Test
    void setListFailed_firstWriteWins_subsequentCallDoesNotOverwrite() {
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        writer.setListFailed(exc, "FIU",    "SYSTEM_TIMEOUT",  "first error");
        writer.setListFailed(exc, "consoli", "PROVIDER_ERROR", "second error");

        // First failure wins for reason/details
        assertThat(exc.getFailureReason()).isEqualTo("SYSTEM_TIMEOUT");
        assertThat(exc.getFailureDetails()).isEqualTo("first error");
        // But the second list column IS updated
        assertThat(exc.getUnConsolidated()).isEqualTo("FAILED");
    }

    // ── writeSkipped ──────────────────────────────────────────────────────────

    @Test
    void writeSkipped_savesEntityAndReturnsShouldScreenFalse() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CustomerInfo customer = new CustomerInfo("CL001", "N/A", null, null, null, null, null);
        NameNormalizer.Result norm = NameNormalizer.classify("N/A");

        ExceptionContext ctx = writer.writeSkipped(customer, "20260101_000000", norm);

        verify(repo).save(any(ScreeningExceptionEntity.class));
        assertThat(ctx.shouldScreen()).isFalse();
        assertThat(ctx.excId()).isNotNull().isNotBlank();
    }

    // ── startException ────────────────────────────────────────────────────────

    @Test
    void startException_restricted_setsCorrectAction() {
        CustomerInfo customer = new CustomerInfo("CL002", "Rashidi", null, null, null, null, null);
        NameNormalizer.Result norm = NameNormalizer.classify("Rashidi");

        ScreeningExceptionEntity exc = writer.startException(
                customer, "20260101_000000", norm, ScreeningMode.RESTRICTED, "Rashidi");

        assertThat(exc.getAction()).isEqualTo("SCREENED_RESTRICTED");
        assertThat(exc.getScreeningMode()).isEqualTo("RESTRICTED");
        assertThat(exc.getScreenedAt()).isNotNull();
    }

    @Test
    void startException_nicOnly_setsScreenedRestrictedAction() {
        CustomerInfo customer = new CustomerInfo("CL003", "R K B", "851234567V", null, null, null, null);
        NameNormalizer.Result norm = NameNormalizer.classify("R K B");

        ScreeningExceptionEntity exc = writer.startException(
                customer, "20260101_000000", norm, ScreeningMode.NIC_ONLY, "");

        assertThat(exc.getAction()).isEqualTo("SCREENED_RESTRICTED");
        assertThat(exc.getScreeningMode()).isEqualTo("NIC_ONLY");
        assertThat(exc.getNormalizedName()).isNull();   // blank screenName → null
    }

    @Test
    void startException_normalTransformed_setsTransformedAndScreenedAction() {
        CustomerInfo customer = new CustomerInfo("CL004", "A.B.Perera", null, null, null, null, null);
        NameNormalizer.Result norm = NameNormalizer.classify("A.B.Perera");

        ScreeningExceptionEntity exc = writer.startException(
                customer, "20260101_000000", norm, ScreeningMode.NORMAL, "A B Perera");

        assertThat(exc.getAction()).isEqualTo("TRANSFORMED_AND_SCREENED");
    }

    // ── commit ────────────────────────────────────────────────────────────────

    @Test
    void commit_savesEntity() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        exc.setId("test-id");
        exc.setAction("SCREENED_RESTRICTED");
        exc.setClientId("CL001");

        writer.commit(exc);

        verify(repo).save(exc);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ScreeningCandidate mockCandidate() {
        return new ScreeningCandidate("doc1", "fiu_individual_core") {
            @Override public java.util.List<String> getNameVariants() { return List.of("Test Name"); }
            @Override public java.util.List<String> getIdDocuments() { return List.of(); }
        };
    }
}
