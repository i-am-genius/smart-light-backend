package com.genius.smartlight.vo.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "Fabric archive image item")
@Data
public class FabricArchiveItemRespVO {

    @Schema(description = "Image filename")
    private String filename;

    @Schema(description = "Public image URL")
    private String url;

    @Schema(description = "Archive image type")
    private String type;

    @Schema(description = "Device chip ID parsed from filename")
    private String chipId;

    @Schema(description = "Create time parsed from filename")
    private String createTime;

    @Schema(description = "File size in bytes")
    private Long size;
}
