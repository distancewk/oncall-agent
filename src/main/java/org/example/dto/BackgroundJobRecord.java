package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BackgroundJobRecord {

    private String jobId;
    private String jobType;
    private String businessKey;
    private String payload;
    private String status;
    private int attemptCount;
    private int maxAttempts;
    private long availableAt;
    private String leaseOwner;
    private long leaseExpiresAt;
    private long heartbeatAt;
    private boolean cancelRequested;
    private String lastError;
    private long createdAt;
    private long updatedAt;
}
