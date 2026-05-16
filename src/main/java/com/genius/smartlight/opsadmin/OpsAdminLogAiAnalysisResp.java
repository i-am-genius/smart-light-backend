package com.genius.smartlight.opsadmin;

import lombok.Data;

import java.util.List;

@Data
public class OpsAdminLogAiAnalysisResp {

    private String summary;
    private String level;
    private List<LogProblem> problems;
    private List<String> suggestions;
    private List<String> relatedLogs;
    private int analyzedLineCount;
    private boolean truncated;
    private String analysisTime;
    private boolean aiEnabled;
    private boolean fallbackUsed;

    @Data
    public static class LogProblem {
        private String title;
        private String severity;
        private List<String> evidence;
        private String reason;
        private String impact;
        private String suggestion;
    }
}
