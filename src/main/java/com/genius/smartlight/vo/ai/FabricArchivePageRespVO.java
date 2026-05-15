package com.genius.smartlight.vo.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Schema(description = "Fabric archive page response")
@Data
public class FabricArchivePageRespVO {

    @Schema(description = "Archive image list")
    private List<FabricArchiveItemRespVO> list;

    @Schema(description = "Total image count")
    private Long total;

    @Schema(description = "Current page")
    private Integer page;

    @Schema(description = "Page size")
    private Integer pageSize;
}
