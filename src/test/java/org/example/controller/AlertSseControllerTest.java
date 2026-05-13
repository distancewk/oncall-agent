package org.example.controller;

import org.example.service.AlertService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertSseControllerTest {

    @Test
    void simulateAlert_shouldReturnNotFoundWhenSimulationIsDisabled() {
        AlertService alertService = mock(AlertService.class);
        AlertSseController controller = new AlertSseController();
        ReflectionTestUtils.setField(controller, "alertService", alertService);
        ReflectionTestUtils.setField(controller, "simulateEnabled", false);

        ResponseEntity<Map<String, Object>> response = controller.simulateAlert(null);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(alertService, never()).storeAlert(any());
    }

    @Test
    void simulateAlert_shouldStoreAlertWhenSimulationIsEnabled() {
        AlertService alertService = mock(AlertService.class);
        when(alertService.storeAlert(any())).thenReturn("alert-1");

        AlertSseController controller = new AlertSseController();
        ReflectionTestUtils.setField(controller, "alertService", alertService);
        ReflectionTestUtils.setField(controller, "simulateEnabled", true);

        ResponseEntity<Map<String, Object>> response = controller.simulateAlert(null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("alert-1", response.getBody().get("alertId"));
        verify(alertService).storeAlert(any());
    }
}
