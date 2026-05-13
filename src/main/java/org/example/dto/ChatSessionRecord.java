package org.example.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ChatSessionRecord {

    private String sessionId;
    private long createTime;
    private long updateTime;
    private List<Map<String, String>> messageHistory = new ArrayList<>();
}
