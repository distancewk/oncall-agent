package org.example.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatSessionSummary {

    private String sessionId;
    private String title;
    private int messagePairCount;
    private long createTime;
    private long updateTime;
}
