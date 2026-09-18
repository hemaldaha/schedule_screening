package com.kpmg.aml.screening.engine.solr;

/**
 * Thrown by {@link SolrQueryExecutor} when a Solr query fails due to
 * infrastructure reasons (timeout, HTTP 5xx, unparseable response).
 *
 * <p>Caught per-list in {@code SanctionStrategyBuilder.composeStrategy()} so
 * that a failure on one list does not abort the remaining lists. The calling
 * loop records {@code FAILED} on the affected list column via
 * {@code ScreeningExceptionWriter.setListFailed()}.
 */
public class SolrQueryException extends RuntimeException {

    public SolrQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
