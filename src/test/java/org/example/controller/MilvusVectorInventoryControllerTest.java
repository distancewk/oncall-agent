package org.example.controller;

import org.example.service.MilvusVectorInventoryService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusVectorInventoryControllerTest {

    @Test
    void inventory_shouldDelegateBoundedReadOnlyRequest() {
        MilvusVectorInventoryService service = mock(MilvusVectorInventoryService.class);
        MilvusVectorInventoryService.Inventory inventory = new MilvusVectorInventoryService.Inventory(
                0, 10, 1, 0, 1,
                List.of(new MilvusVectorInventoryService.VectorEntry(
                        "id-1", null, "doc-1", null, "/legacy.md", true)));
        when(service.inventory(10, 0)).thenReturn(inventory);

        MilvusVectorInventoryController controller = new MilvusVectorInventoryController(service);

        assertEquals(inventory, controller.inventory(10, 0).getData());
    }
}
