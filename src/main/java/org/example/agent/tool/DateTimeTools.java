package org.example.agent.tool;

import org.example.service.DiagnosisEvidenceRecorder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DateTimeTools {
    
    /** 工具名常量，用于动态构建提示词 */
    public static final String TOOL_GET_CURRENT_DATETIME = "getCurrentDateTime";

    @Autowired(required = false)
    private DiagnosisEvidenceRecorder diagnosisEvidenceRecorder;
    
    @Tool(description = "Get the current date and time in the user's timezone")
    public String getCurrentDateTime() {
        if (diagnosisEvidenceRecorder != null) {
            return diagnosisEvidenceRecorder.recordToolCall(
                    TOOL_GET_CURRENT_DATETIME, "{}", "now", this::currentDateTime);
        }
        return currentDateTime();
    }

    private String currentDateTime() {
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }
}
