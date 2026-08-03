package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.service.SecurityAuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes only bounded, low-sensitivity audit records to administrators. */
@RestController
@RequestMapping("/api/system")
public class SecurityAuditController {

    private final SecurityAuditService auditService;

    public SecurityAuditController(SecurityAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/security-audit")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<List<SecurityAuditService.AuditEvent>> recent(
            @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(auditService.recent(limit));
    }
}
