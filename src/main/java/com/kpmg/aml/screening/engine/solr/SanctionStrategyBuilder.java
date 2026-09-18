/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.kpmg.aml.screening.engine.solr;

import com.kpmg.aml.screening.engine.ScreeningExceptionWriter;
import com.kpmg.aml.screening.entity.ScreeningExceptionEntity;
import com.kpmg.aml.screening.entity.dto.CustomerInfo;
import com.kpmg.aml.screening.entity.dto.SanctionTypeView;
import com.kpmg.aml.screening.util.NameNormalizer;
import com.kpmg.aml.screening.util.NameRefinerUtil;
import com.kpmg.aml.screening.util.SriLankanNicUtil;
import com.kpmg.aml.screening.util.SriLankanNicUtil.NicPair;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Builds per-scenario SanctionScreeningStrategy instances.
 *
 * Changes from previous version:
 *   - queryBuilder replaced: ScreeningSolrQueryBuilder → AdvancedScreeningSolrQueryBuilder
 *   - ScreeningMode introduced: NORMAL / RESTRICTED / NIC_ONLY
 *   - SCREEN_RESTRICTED (single-token names) → restricted query, no wildcard
 *   - NAME_ALL_INITIALS + valid NIC → NIC-only query on FIU + local_watchList
 *   - NAME_ALL_INITIALS without NIC → still skipped (no meaningful query possible)
 *
 * @author user
 */
@Slf4j
@Component
public class SanctionStrategyBuilder {

    private final AdvancedScreeningSolrQueryBuilder queryBuilder;
    private final SolrQueryExecutor executor;
    private final ScreeningExceptionWriter exceptionWriter;

    /** Set once per run by ScreeningProcessor.process() before calling build(). */
    private volatile String currentRunId;

    public SanctionStrategyBuilder(SolrQueryExecutor executor,
                                    ScreeningExceptionWriter exceptionWriter) {
        this.queryBuilder     = new AdvancedScreeningSolrQueryBuilder();
        this.executor         = executor;
        this.exceptionWriter  = exceptionWriter;
    }

    /**
     * Must be called once at the start of each screening run, before {@link #build(List)}.
     * The runId is stored in every exception row created during this run.
     */
    public void setRunId(String runId) {
        this.currentRunId = runId;
    }

    /*
       1. Invoked ONCE before the loop starts
       2. Builds a composed strategy per sanctionId (scenario)
       3. Switch runs once per unique sanction type — never per record
     */
    public Map<Long, SanctionScreeningStrategy> build(List<SanctionTypeView> sanctionTypes) {

        Map<Long, List<String>> grouped = sanctionTypes.stream()
                .collect(Collectors.groupingBy(
                        SanctionTypeView::getSanctionId,
                        Collectors.mapping(SanctionTypeView::getSanctionType, Collectors.toList())
                ));

        return grouped.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> composeStrategy(e.getValue())
                ));
    }

    private SanctionScreeningStrategy composeStrategy(List<String> types) {

        return customer -> {

            String nameToScreen         = "";
            ScreeningMode mode          = ScreeningMode.NORMAL;
            NameNormalizer.Result norm  = null;
            boolean isExceptionCustomer = false;

            // Personal name classification — always applied regardless of which lists
            // are in scope. FIU-ORG uses the customer's name directly in resolveStrategy()
            // and returns empty for RESTRICTED/NIC_ONLY, so it is safe to classify all
            // customers using personal-name rules first.
            norm = NameNormalizer.classify(customer.name());
            boolean nameUsable = norm.action() != NameNormalizer.Action.SKIP_PLACEHOLDER
                              && norm.action() != NameNormalizer.Action.SKIP_ANOMALY;

            if (norm.action() == NameNormalizer.Action.SCREEN_RESTRICTED) {
                // Single-token name (NAME_SINGLE_TOKEN) — restricted query.
                // Do NOT apply NameRefinerUtil: restricted query needs the exact
                // single token as-is for the "name"~1 fuzzy clause.
                mode = ScreeningMode.RESTRICTED;
                nameToScreen = norm.screenName();
                isExceptionCustomer = true;

            } else if (!nameUsable) {
                // Name unusable for any reason (blank, placeholder, invalid chars,
                // all-initials). NIC eligibility is checked independently.
                NicPair nics = SriLankanNicUtil.resolve(customer.nic());
                if (nics.isUsable()) {
                    mode = ScreeningMode.NIC_ONLY;
                    nameToScreen = "";  // not used in NIC-only queries
                    isExceptionCustomer = true;
                } else {
                    // Both checks failed — write SKIPPED exception row and stop.
                    if (currentRunId != null) {
                        exceptionWriter.writeSkipped(customer, currentRunId, norm);
                    }
                    return new ScreeningResult(List.of(), mode);
                }

            } else {
                // Usable name — apply NameRefinerUtil to the normalised name.
                mode = ScreeningMode.NORMAL;
                nameToScreen = NameRefinerUtil.refineName(norm.screenName());
                // Write exception row only for transformed names, not plain SCREEN.
                isExceptionCustomer = (norm.action() == NameNormalizer.Action.SCREEN_TRANSFORMED);
            }

            CustomerInfo cleansed = new CustomerInfo(
                    customer.clientId(),
                    nameToScreen,
                    customer.nic(),
                    customer.passport(),
                    customer.dob(),
                    customer.proposalDate(),
                    customer.policyNo()
            );

            // Build in-memory exception entity for non-NORMAL exception customers.
            ScreeningExceptionEntity exc = null;
            if (isExceptionCustomer && currentRunId != null) {
                exc = exceptionWriter.startException(
                        customer, currentRunId, norm, mode, nameToScreen);
            }

            // Per-list execution — for loop replaces flatMap so list results can be
            // recorded on the exception entity before a single commit at the end.
            // A Solr infrastructure failure on one list marks that column FAILED but
            // does not abort the remaining lists.
            List<ScreeningCandidate> all = new ArrayList<>();
            for (String type : types) {
                try {
                    List<ScreeningCandidate> hits = resolveStrategy(type, mode).apply(cleansed);
                    if (exc != null) {
                        exceptionWriter.setListResult(exc, type, hits, mode);
                    }
                    // RESTRICTED + LXNX: query runs and result is recorded in the exception
                    // audit row (lexis_nexis = CANDIDATE_FOUND / ATTEMPTED), but candidates
                    // are NOT forwarded to Python scoring or alert generation. Single-token
                    // names produce structural false positives on the 7M+ LXNX corpus.
                    // Compliance can filter action='SCREENED_RESTRICTED' AND
                    // lexis_nexis='CANDIDATE_FOUND' for periodic batch review.
                    if (mode == ScreeningMode.RESTRICTED && "LXNX".equals(type)) {
                        continue;
                    }
                    // Pattern E — RESTRICTED + FIU with no identifiers: a single-token name
                    // matched against FIU by name only (no NIC or passport) cannot be uniquely
                    // confirmed — common Tamil/Sinhala first names each appear in 1–11 unrelated
                    // FIU entries. Query result is recorded in the exception audit row for
                    // periodic compliance batch review, but candidates are NOT forwarded to
                    // scoring or alert generation.
                    if (mode == ScreeningMode.RESTRICTED && "FIU".equals(type)) {
                        NicPair eNics = SriLankanNicUtil.resolve(customer.nic());
                        if (!eNics.isUsable()
                                && (customer.passport() == null || customer.passport().isBlank())) {
                            continue;
                        }
                    }
                    // Pattern A — NORMAL + LXNX: names of the form "K S KUMARA" (one or more
                    // initials followed by a surname) produce structural false positives on the
                    // 7M+ LXNX corpus because common Sri Lankan surnames match many LXNX entries
                    // at high similarity scores. Suppress LXNX candidates for these names;
                    // FIU, UN, and LOCAL screening still runs normally.
                    if (mode == ScreeningMode.NORMAL && "LXNX".equals(type)
                            && NameNormalizer.isInitialPlusSurname(nameToScreen)) {
                        continue;
                    }
                    all.addAll(hits);
                } catch (SolrQueryException ex) {
                    String reason = isTimeoutException(ex) ? "SYSTEM_TIMEOUT" : "PROVIDER_ERROR";
                    log.error("[SanctionStrategyBuilder] List query failed type={} clientId={} reason={}",
                            type, customer.clientId(), reason, ex);
                    if (exc != null) {
                        exceptionWriter.setListFailed(exc, type, reason, ex.getMessage());
                    }
                }
            }

            // Persist exception entity with all list columns populated.
            if (exc != null) {
                exc.setMatchCount(all.size());
                exceptionWriter.commit(exc);
            }

            return new ScreeningResult(all, mode);
        };
    }

    private static boolean isTimeoutException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SocketTimeoutException || c instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the per-list execution function for one sanction list type,
     * parameterised by the screening mode determined from NameNormalizer classification.
     *
     * NOT_APPLICABLE combinations (returns empty list):
     *   RESTRICTED mode  — FIU-ORG (person name vs org list)
     *   NIC_ONLY mode    — FIU-ORG, consoli, LXNX (no NIC fields on these lists)
     */
    private Function<CustomerInfo, List<ScreeningCandidate>> resolveStrategy(String type, ScreeningMode mode) {
        return switch (type) {

            case "FIU" ->
                customer -> {
                    NicPair nics = SriLankanNicUtil.resolve(customer.nic());
                    String query = switch (mode) {
                        case RESTRICTED -> queryBuilder.buildRestrictedFIUQuery(
                                customer.name(), nics.oldNic(), nics.newNic(), customer.passport());
                        case NIC_ONLY   -> queryBuilder.buildNicOnlyFIUQuery(
                                nics.oldNic(), nics.newNic(), customer.passport());
                        case NORMAL     -> queryBuilder.buildFIUQuery(
                                customer.name(), nics.oldNic(), nics.newNic(), customer.passport());
                    };
                    if (query == null || query.isBlank()) return List.of();
                    return executor.executeFIU(query);
                };

            case "FIU-ORG" ->
                // RESTRICTED and NIC_ONLY are never set for FIU-ORG paths (org branch
                // in composeStrategy always sets NORMAL), but guard defensively.
                customer -> {
                    if (mode == ScreeningMode.RESTRICTED || mode == ScreeningMode.NIC_ONLY)
                        return List.of();
                    return executor.executeFIUOrg(
                            queryBuilder.buildFIUOrgQuery(customer.name(), customer.policyNo()));
                };

            case "consoli" ->
                customer -> {
                    if (mode == ScreeningMode.NIC_ONLY) return List.of();
                    String query = mode == ScreeningMode.RESTRICTED
                            ? queryBuilder.buildRestrictedUNQuery(customer.name())
                            : queryBuilder.buildUNQuery(customer.name());
                    if (query == null || query.isBlank()) return List.of();
                    return executor.executeUN(query);
                };

            case "local_watchList" ->
                customer -> {
                    NicPair nics = SriLankanNicUtil.resolve(customer.nic());
                    String query = switch (mode) {
                        case RESTRICTED -> queryBuilder.buildRestrictedLocalWatchQuery(
                                customer.name(), nics.oldNic(), nics.newNic());
                        case NIC_ONLY   -> queryBuilder.buildNicOnlyLocalWatchQuery(
                                nics.oldNic(), nics.newNic());
                        case NORMAL     -> queryBuilder.buildLocalWatchListQuery(
                                customer.name(), nics.oldNic(), nics.newNic());
                    };
                    if (query == null || query.isBlank()) return List.of();
                    return executor.executeLocalWatch(query);
                };

            case "LXNX" ->
                customer -> {
                    if (mode == ScreeningMode.NIC_ONLY) return List.of();
                    String query = mode == ScreeningMode.RESTRICTED
                            ? queryBuilder.buildRestrictedLXNXQuery(customer.name())
                            : queryBuilder.buildLXNXQuery(customer.name());
                    if (query == null || query.isBlank()) return List.of();
                    return executor.executeLXNX(query);
                };

            default ->
                throw new IllegalArgumentException(
                        "Unknown sanction type: " + type);
        };
    }
}
