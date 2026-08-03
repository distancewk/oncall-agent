package org.example.controller;

import org.example.dto.ApiResponse;
import org.example.service.MilvusVectorInventoryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only, read-only inventory endpoint for legacy vector migration planning. */
@RestController
@RequestMapping("/api/system/milvus-vectors")
public class MilvusVectorInventoryController {

    private final MilvusVectorInventoryService inventoryService;

    public MilvusVectorInventoryController(MilvusVectorInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<MilvusVectorInventoryService.Inventory> inventory(
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") long offset) {
        return ApiResponse.success(inventoryService.inventory(limit, offset));
    }
}
