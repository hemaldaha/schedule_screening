package com.kpmg.aml.screening;

import com.kpmg.aml.screening.engine.AlertManager;
import com.kpmg.aml.screening.entity.AlertStatus;
import com.kpmg.aml.screening.entity.dto.MiniAlert;
import com.kpmg.aml.screening.entity.persistence.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertManagerTest {

    @Mock AlertRepository alertRepo;

    @InjectMocks AlertManager alertManager;

    @BeforeEach
    void init() {
        // @PostConstruct is not called automatically by Mockito — invoke it manually.
        alertManager.init();
    }

    @Test
    void isExist_beforeLoad_alwaysReturnsFalse() {
        // Maps are empty before loadData() is called.
        assertThat(alertManager.isExist("CL001", "POL001")).isFalse();
    }

    @Test
    void loadData_thenIsExist_foundByCustomerId_returnsTrue() {
        MiniAlert alert = new MiniAlert(1L, "CL001", "POL001");
        when(alertRepo.findCustomersToOmit(any())).thenReturn(List.of(alert));

        alertManager.loadData();

        assertThat(alertManager.isExist("CL001", null)).isTrue();
    }

    @Test
    void loadData_thenIsExist_foundByPolicyNo_returnsTrue() {
        MiniAlert alert = new MiniAlert(1L, "CL001", "POL001");
        when(alertRepo.findCustomersToOmit(any())).thenReturn(List.of(alert));

        alertManager.loadData();

        // Customer not in the map but policy number matches
        assertThat(alertManager.isExist("CL999", "POL001")).isTrue();
    }

    @Test
    void loadData_thenIsExist_notFound_returnsFalse() {
        MiniAlert alert = new MiniAlert(1L, "CL001", "POL001");
        when(alertRepo.findCustomersToOmit(any())).thenReturn(List.of(alert));

        alertManager.loadData();

        assertThat(alertManager.isExist("CL999", "POL999")).isFalse();
    }

    @Test
    void loadData_withDuplicateCustomerIds_doesNotThrow() {
        // Merge function (existing, replacement) -> existing prevents IllegalStateException
        MiniAlert a1 = new MiniAlert(1L, "CL001", "POL001");
        MiniAlert a2 = new MiniAlert(2L, "CL001", "POL002");
        when(alertRepo.findCustomersToOmit(any())).thenReturn(List.of(a1, a2));

        alertManager.loadData();  // must not throw

        assertThat(alertManager.isExist("CL001", null)).isTrue();
    }

    @Test
    void loadData_withDuplicatePolicyNos_doesNotThrow() {
        MiniAlert a1 = new MiniAlert(1L, "CL001", "POL-SAME");
        MiniAlert a2 = new MiniAlert(2L, "CL002", "POL-SAME");
        when(alertRepo.findCustomersToOmit(any())).thenReturn(List.of(a1, a2));

        alertManager.loadData();  // must not throw

        assertThat(alertManager.isExist("NOBODY", "POL-SAME")).isTrue();
    }

    @Test
    void loadData_filtersStatusesUnassignedAndPending() {
        // Verify the statuses passed to the repo contain unassigned and pending.
        when(alertRepo.findCustomersToOmit(any())).thenReturn(List.of());

        alertManager.loadData();

        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(alertRepo).findCustomersToOmit(captor.capture());
        @SuppressWarnings("unchecked")
        List<AlertStatus> statuses = captor.getValue();
        assertThat(statuses).containsExactlyInAnyOrder(AlertStatus.unassigned, AlertStatus.pending);
    }

    @Test
    void loadData_alertWithNullPolicyNo_foundByCustomerIdOnly() {
        // Null policyNo is filtered from alertsByPolicyNo — alert still findable via customerId.
        MiniAlert noPol = new MiniAlert(1L, "CL001", null);
        when(alertRepo.findCustomersToOmit(any())).thenReturn(List.of(noPol));

        alertManager.loadData();

        assertThat(alertManager.isExist("CL001", null)).isTrue();
        assertThat(alertManager.isExist("CL999", null)).isFalse();
    }
}
