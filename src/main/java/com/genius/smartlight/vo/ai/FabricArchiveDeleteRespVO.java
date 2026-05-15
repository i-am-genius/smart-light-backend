package com.genius.smartlight.vo.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "Fabric archive delete response")
@Data
public class FabricArchiveDeleteRespVO {

    @Schema(description = "Whether delete operation found and removed files")
    private Boolean success;

    @Schema(description = "Result message")
    private String msg;

    @Schema(description = "Archive basename shared by original, annotated and combined images")
    private String baseName;

    @Schema(description = "Deleted file count")
    private Integer deletedCount;

    @Schema(description = "Deleted absolute file paths")
    private List<String> deletedFiles;

    @Schema(description = "Missing absolute file paths")
    private List<String> missingFiles;
}
