package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.DependencyProbeSnapshot;
import org.example.service.DependencyProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class DependencyProbeController {

    private final DependencyProbeService dependencyProbeService;

    public DependencyProbeController(DependencyProbeService dependencyProbeService) {
        this.dependencyProbeService = dependencyProbeService;
    }

    @GetMapping("/dependencies/probe")
    public ApiResponse<List<DependencyProbeSnapshot>> probe() {
        return ApiResponse.success(dependencyProbeService.probeAll());
    }
}
