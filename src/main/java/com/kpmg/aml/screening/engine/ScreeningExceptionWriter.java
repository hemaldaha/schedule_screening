/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine;

import com.kpmg.aml.screening.engine.solr.ScreeningCandidate;
import com.kpmg.aml.screening.engine.solr.ScreeningMode;
import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import com.kpmg.aml.screening.entity.persistence.ScreeningExceptionRepository;
import com.kpmg.aml.screening.util.NameNormalizer;
import com.kpmg.aml.screening.util.SriLankanNicUtil;
import com.kpmg.aml.screening.util.SriLankanNicUtil.NicPair;
import com.kpmg.aml.screening.util.UuidV7;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds, populates, and persists {@link ScreeningExceptionEntity} rows for
 * exception customers (SKIPPED, SCREENED_RESTRICTED, TRANSFORMED_AND_SCREENED).
 *
 * <p>Lifecycle per exception customer:
 * <ol>
 *   <li><b>SKIPPED:</b> call {@link #writeSkipped} — entity saved immediately,
 *       no Solr queries follow.</li>
 *   <li><b>Screened exception:</b> call {@link #startException} to obtain an
 *       in-memory entity, then {@link #setListResult} after each list query,
 *       then {@link #commit} once all lists are done.</li>
 * </ol>
 *
 * <p>Thread-safe: no shared mutable state; each call is independent.
 * {@code repo.save()} uses connection-per-transaction from the pool.
 *
 * @author user
 */
@Service
@Slf4j
public class ScreeningExceptionWriter {

    // Oracle exports DOB as DD-MON-YY (e.g. "02-OCT-78").
    // We interpret 2-digit years using a sliding window: years 00-25 → 2000-2025,
    // years 26-99 → 1926-1999 — covers all plausible insurance customer birth years.
    private static final DateTimeFormatter ORACLE_DOB_YY =
            new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern("dd-MMM-")
                    .appendValueReduced(ChronoField.YEAR, 2, 2, 1926)
                    .toFormatter(Locale.ENGLISH);

    private static final List<DateTimeFormatter> DOB_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,                                    // yyyy-MM-dd
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH),          // DD-MON-YYYY (Oracle 4-digit)
            ORACLE_DOB_YY                                                         // DD-MON-YY   (Oracle 2-digit)
    );

    private static final Logger excLog =
            LoggerFactory.getLogger("SCREENING_EXCEPTIONS");

    private final ScreeningExceptionRepository repo;

    public ScreeningExceptionWriter(ScreeningExceptionRepository repo) {
        this.repo = repo;
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Persists a SKIPPED exception row immediately.
     * All list columns are left {@code null} — SKIPPED customers are never queried.
     *
     * @return {@link ExceptionContext} with {@code shouldScreen=false} and the
     *         generated {@code excId} (available for Task 12 infra logging).
     */
    public ExceptionContext writeSkipped(CustomerInfo customer, String runId,
                                         NameNormalizer.Result norm) {
        String excId = UuidV7.generate();
        ScreeningExceptionEntity exc = buildEntity(
                excId, customer, runId, null, norm, "SKIPPED", norm.note());
        repo.save(exc);
        log.warn("[ExceptionWriter] SKIPPED clientId={} reasonCode={}",
                customer.clientId(), norm.reasonCode());
        excLog.warn(Markers.appendEntries(Map.of(
                "event",       "screening_exception",
                "run_id",      runId,
                "customer_id", customer.clientId(),
                "reason_code", norm.reasonCode(),
                "action",      "SKIPPED"
        )), "");
        return new ExceptionContext(false, excId, ScreeningMode.NORMAL);
    }

    /**
     * Builds an in-memory exception entity for a customer that WILL be screened.
     * List columns are not set here — call {@link #setListResult} per list,
     * then {@link #commit}.
     *
     * @param screenName  the name string actually submitted to the Solr query builder
     *                    (post-NameRefinerUtil for NORMAL; norm.screenName() for RESTRICTED;
     *                    blank string for NIC_ONLY — stored as {@code null}).
     * @param mode        RESTRICTED (single-token), NIC_ONLY, or NORMAL (SCREEN_TRANSFORMED)
     */
    public ScreeningExceptionEntity startException(CustomerInfo customer, String runId,
                                                    NameNormalizer.Result norm,
                                                    ScreeningMode mode,
                                                    String screenName) {
        String excId  = UuidV7.generate();
        String action;
        String note;
        if (mode == ScreeningMode.NIC_ONLY) {
            action = "SCREENED_RESTRICTED";
            note   = norm.note() + " Screened via NIC/passport only.";
        } else if (mode == ScreeningMode.RESTRICTED) {
            action = "SCREENED_RESTRICTED";
            note   = norm.note();
        } else {
            // NORMAL mode reached only when action == SCREEN_TRANSFORMED
            action = "TRANSFORMED_AND_SCREENED";
            note   = norm.note();
        }
        ScreeningExceptionEntity exc = buildEntity(excId, customer, runId, screenName, norm, action, note);
        exc.setScreeningMode(mode.name());
        exc.setScreenedAt(LocalDateTime.now());
        return exc;
    }

    /**
     * Sets one list column on an in-progress entity.
     * Determines HIT / ATTEMPTED / NOT_APPLICABLE based on candidates and
     * the mode × type dispatch table (mirrors {@code SanctionStrategyBuilder.resolveStrategy()}).
     * Does <em>not</em> save — caller must call {@link #commit} when all lists are done.
     */
    public void setListResult(ScreeningExceptionEntity exc, String type,
                               List<ScreeningCandidate> candidates, ScreeningMode mode) {
        String status = isNotApplicable(type, mode)
                ? "NOT_APPLICABLE"
                : (candidates.isEmpty() ? "ATTEMPTED" : "CANDIDATE_FOUND");
        applyListColumn(exc, type, status);
    }

    /**
     * Sets a list column to FAILED and records the infrastructure failure context.
     *
     * <p>First-write-wins: if a previous list already failed during the same
     * screening run, {@code failure_reason} and {@code failure_details} are NOT
     * overwritten.  The first failure is the most actionable signal for compliance.
     *
     * @param reason  short code — {@code "SYSTEM_TIMEOUT"} or {@code "PROVIDER_ERROR"}
     * @param details raw exception message from {@link com.kpmg.aml.screening.engine.solr.SolrQueryException}
     */
    public void setListFailed(ScreeningExceptionEntity exc, String type,
                               String reason, String details) {
        applyListColumn(exc, type, "FAILED");
        if (exc.getFailureReason() == null) {
            exc.setFailureReason(reason);
            exc.setFailureDetails(details);
        }
        log.error("[ExceptionWriter] FAILED list={} excId={} reason={}",
                type, exc.getId(), reason);
    }

    /**
     * Persists the completed exception entity with all list columns populated.
     */
    public void commit(ScreeningExceptionEntity exc) {
        repo.save(exc);
        log.info("[ExceptionWriter] committed excId={} action={} clientId={}",
                exc.getId(), exc.getAction(), exc.getClientId());

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("event",          "screening_exception");
        fields.put("run_id",         exc.getRunId());
        fields.put("customer_id",    exc.getClientId());
        fields.put("reason_code",    exc.getReasonCode());
        fields.put("action",         exc.getAction());
        fields.put("screening_mode", exc.getScreeningMode());

        if (exc.getFailureReason() != null) {
            fields.put("failure_reason", exc.getFailureReason());
            excLog.error(Markers.appendEntries(fields), "");
        } else if ("TRANSFORMED_AND_SCREENED".equals(exc.getAction())
                || "SCREENED_RESTRICTED".equals(exc.getAction())) {
            excLog.info(Markers.appendEntries(fields), "");
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private static ScreeningExceptionEntity buildEntity(String excId,
                                                         CustomerInfo customer,
                                                         String runId,
                                                         String screenName,
                                                         NameNormalizer.Result norm,
                                                         String action,
                                                         String note) {
        NicPair nics       = SriLankanNicUtil.resolve(customer.nic());
        boolean hasNic     = nics.oldNic() != null || nics.newNic() != null;
        boolean hasPassport = customer.passport() != null && !customer.passport().isBlank();

        ScreeningExceptionEntity exc = new ScreeningExceptionEntity();
        exc.setId(excId);
        exc.setRunId(runId);
        exc.setClientId(customer.clientId());
        exc.setOriginalName(customer.name() != null ? customer.name() : "");
        exc.setNormalizedName(screenName == null || screenName.isBlank() ? null : screenName);
        exc.setIdNumber(customer.nic());
        exc.setEntityType(hasNic || hasPassport ? "INDIVIDUAL" : "UNKNOWN");
        exc.setNationality(hasNic ? "LKA" : null);
        exc.setDateOfBirth(parseDob(customer.dob()));
        exc.setReasonCode(norm.reasonCode());
        exc.setAction(action);
        exc.setNote(note);
        return exc;
    }

    private static LocalDate parseDob(String dob) {
        if (dob == null || dob.isBlank()) return null;
        String trimmed = dob.trim();
        for (DateTimeFormatter fmt : DOB_FORMATS) {
            try {
                return LocalDate.parse(trimmed, fmt);
            } catch (DateTimeParseException ignored) {}
        }
        log.warn("[ExceptionWriter] Could not parse DOB '{}'", dob);
        return null;
    }

    /**
     * Returns {@code true} when the list type yields no Solr query for the given mode.
     * Mirrors the NOT_APPLICABLE guards in {@code SanctionStrategyBuilder.resolveStrategy()}.
     */
    static boolean isNotApplicable(String type, ScreeningMode mode) {
        return switch (type) {
            case "FIU-ORG"   -> mode == ScreeningMode.RESTRICTED || mode == ScreeningMode.NIC_ONLY;
            case "consoli"   -> mode == ScreeningMode.NIC_ONLY;
            case "LXNX"      -> mode == ScreeningMode.NIC_ONLY;
            case "DOW_JONES" -> mode == ScreeningMode.NIC_ONLY;
            default          -> false;
        };
    }

    private static void applyListColumn(ScreeningExceptionEntity exc,
                                         String type, String status) {
        switch (type) {
            case "FIU"             -> exc.setFiuInd(status);
            case "FIU-ORG"         -> exc.setFiuOrg(status);
            case "consoli"         -> exc.setUnConsolidated(status);
            case "local_watchList" -> exc.setLocalWatch(status);
            case "LXNX"            -> exc.setLexisNexis(status);
            case "DOW_JONES"       -> exc.setDowJones(status);
            default -> log.warn("[ExceptionWriter] Unknown list type '{}' — no column mapped", type);
        }
    }
}
