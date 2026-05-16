package com.genius.smartlight.opsadmin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public class OpsAdminLogAiAnalysisReq {

    @NotBlank
    private String logType;

    private String startTime;

    private String endTime;

    private List<String> levels;

    private String keyword;

    @Min(1)
    @Max(2000)
    private int maxLines = 500;

    private String analysisMode = "diagnose";

    public String getLogType() { return logType; }
    public void setLogType(String v) { this.logType = v; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String v) { this.startTime = v; }
    public String getEndTime() { return endTime; }
    public void setEndTime(String v) { this.endTime = v; }
    public List<String> getLevels() { return levels; }
    public void setLevels(List<String> v) { this.levels = v; }
    public String getKeyword() { return keyword; }
    public void setKeyword(String v) { this.keyword = v; }
    public int getMaxLines() { return maxLines; }
    public void setMaxLines(int v) { this.maxLines = v; }
    public String getAnalysisMode() { return analysisMode; }
    public void setAnalysisMode(String v) { this.analysisMode = v; }
}
