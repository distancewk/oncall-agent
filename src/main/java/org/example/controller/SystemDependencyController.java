package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.dto.DependencyHealthSnapshot;
import org.example.service.DependencyGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemDependencyController {

    private final DependencyGuard dependencyGuard;

    public SystemDependencyController(DependencyGuard dependencyGuard) {
        this.dependencyGuard = dependencyGuard;
    }

    @GetMapping("/dependencies")
    public ApiResponse<List<DependencyHealthSnapshot>> dependencies() {
        return ApiResponse.success(dependencyGuard.snapshot().stream().map(this::sanitize).toList());
    }

    private DependencyHealthSnapshot sanitize(DependencyHealthSnapshot source) {
        DependencyHealthSnapshot safe = new DependencyHealthSnapshot();
        safe.setName(source.getName());
        safe.setState(source.getState());
        safe.setFailureRate(source.getFailureRate());
        safe.setSlowCallRate(source.getSlowCallRate());
        safe.setBufferedCalls(source.getBufferedCalls());
        safe.setOpenedAt(source.getOpenedAt());
        safe.setLastError(source.getLastError() == null ? null : "依赖暂时不可用");
        return safe;
    }
}
