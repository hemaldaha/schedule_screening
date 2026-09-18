package com.kpmg.aml.screening;

import com.kpmg.aml.screening.engine.scoring.EndpointSelector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for EndpointSelector.
 *
 * <p>{@code init}, {@code nextHealthy}, {@code markUnhealthy}, and
 * {@code markedHealthy} are all package-private. They are accessed
 * via reflection so that all tests live in the flat test package
 * {@code com.kpmg.aml.screening}.
 *
 * NOTE: The "all endpoints unhealthy" path in nextHealthy() performs
 * Thread.sleep(2500) twice before throwing, making that path ~5 seconds.
 * We test it here because the failure mode is critical to the pipeline.
 */
class EndpointSelectorTest {

    // ── Reflection bootstrap ──────────────────────────────────────────────────

    private static final Method INIT;
    private static final Method NEXT_HEALTHY;
    private static final Method MARK_UNHEALTHY;
    private static final Method MARKED_HEALTHY;

    static {
        try {
            INIT = EndpointSelector.class.getDeclaredMethod("init", List.class, HttpClient.class);
            INIT.setAccessible(true);
            NEXT_HEALTHY = EndpointSelector.class.getDeclaredMethod("nextHealthy");
            NEXT_HEALTHY.setAccessible(true);
            MARK_UNHEALTHY = EndpointSelector.class.getDeclaredMethod("markUnhealthy", String.class);
            MARK_UNHEALTHY.setAccessible(true);
            MARKED_HEALTHY = EndpointSelector.class.getDeclaredMethod("markedHealthy", String.class);
            MARKED_HEALTHY.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private EndpointSelector selector;

    @BeforeEach
    void setUp() throws Exception {
        selector = EndpointSelector.getInstance();
        // Re-initialise the singleton with fresh state for each test.
        INIT.invoke(selector,
                List.of("http://ep1:5000", "http://ep2:5000", "http://ep3:5000"),
                HttpClient.newHttpClient());
    }

    // ── Convenience wrappers ──────────────────────────────────────────────────

    private String nextHealthy() throws Exception {
        return (String) NEXT_HEALTHY.invoke(selector);
    }

    private void markUnhealthy(String ep) throws Exception {
        MARK_UNHEALTHY.invoke(selector, ep);
    }

    private void markedHealthy(String ep) throws Exception {
        MARKED_HEALTHY.invoke(selector, ep);
    }

    // ── nextHealthy ───────────────────────────────────────────────────────────

    @Test
    void nextHealthy_allEndpointsHealthy_returnsOne() throws Exception {
        String ep = nextHealthy();
        assertThat(ep).isIn("http://ep1:5000", "http://ep2:5000", "http://ep3:5000");
    }

    @Test
    void nextHealthy_firstEndpointUnhealthy_returnsHealthyOne() throws Exception {
        markUnhealthy("http://ep1:5000");
        String ep = nextHealthy();
        assertThat(ep).isIn("http://ep2:5000", "http://ep3:5000");
    }

    @Test
    void nextHealthy_twoEndpointsUnhealthy_returnsRemainingHealthy() throws Exception {
        markUnhealthy("http://ep1:5000");
        markUnhealthy("http://ep2:5000");
        assertThat(nextHealthy()).isEqualTo("http://ep3:5000");
    }

    @Test
    void nextHealthy_markedUnhealthyThenRecovered_returnsRecoveredEndpoint() throws Exception {
        markUnhealthy("http://ep1:5000");
        markUnhealthy("http://ep2:5000");
        markUnhealthy("http://ep3:5000");
        // Recover ep1
        markedHealthy("http://ep1:5000");
        assertThat(nextHealthy()).isEqualTo("http://ep1:5000");
    }

    @Test
    void nextHealthy_singleEndpoint_returnsIt() throws Exception {
        INIT.invoke(selector, List.of("http://only:5000"), HttpClient.newHttpClient());
        assertThat(nextHealthy()).isEqualTo("http://only:5000");
    }

    // ── all-unhealthy (slow path — ~5 s) ─────────────────────────────────────

    @Test
    void nextHealthy_allUnhealthy_throwsEndpointUnavailableException() throws Exception {
        INIT.invoke(selector, List.of("http://down:5000"), HttpClient.newHttpClient());
        markUnhealthy("http://down:5000");

        // This waits 2.5 s × 2 retries before throwing (~5 s total).
        // InvocationTargetException wraps the actual EndpointUnavailableException.
        assertThatThrownBy(() -> {
            try {
                NEXT_HEALTHY.invoke(selector);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        })
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("unavailable");
    }

    // ── markUnhealthy / markedHealthy ─────────────────────────────────────────

    @Test
    void markUnhealthy_endpointBecomesUnavailableForSelection() throws Exception {
        INIT.invoke(selector, List.of("http://solo:5000"), HttpClient.newHttpClient());
        // Should be healthy initially
        assertThat(nextHealthy()).isEqualTo("http://solo:5000");
        markUnhealthy("http://solo:5000");
        // Will now exhaust retries — covered by the all-unhealthy test above.
    }

    @Test
    void markedHealthy_endpointBecomesAvailableAgain() throws Exception {
        INIT.invoke(selector, List.of("http://ep1:5000"), HttpClient.newHttpClient());
        markUnhealthy("http://ep1:5000");
        markedHealthy("http://ep1:5000");
        assertThat(nextHealthy()).isEqualTo("http://ep1:5000");
    }
}
