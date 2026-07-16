package org.example.controller;

import org.example.dto.DependencyHealthSnapshot;
import org.example.service.DependencyGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemDependencyController.class)
class SystemDependencyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DependencyGuard dependencyGuard;

    @Test
    void dependencies_shouldReturnDependencySnapshots() throws Exception {
        DependencyHealthSnapshot prometheus = new DependencyHealthSnapshot();
        prometheus.setName("prometheus");
        prometheus.setState("OPEN");
        prometheus.setFailureRate(66.7f);
        prometheus.setLastError("connection refused");

        when(dependencyGuard.snapshot()).thenReturn(List.of(prometheus));

        mockMvc.perform(get("/api/system/dependencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("prometheus"))
                .andExpect(jsonPath("$.data[0].state").value("OPEN"))
                .andExpect(jsonPath("$.data[0].failureRate").value(66.7))
                .andExpect(jsonPath("$.data[0].lastError").value("依赖暂时不可用"));
    }
}
