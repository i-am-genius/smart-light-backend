package com.genius.smartlight.vo.store;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "店铺保存请求参数")
public class StoreSaveReqVO {

    @Schema(description = "店铺名称", example = "陶铸广场1号店", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "店铺名称不能为空")
    private String storeName;

    @Schema(description = "店铺风格", example = "高端店")
    private String storeStyle;

    @Schema(description = "店铺面积，单位：平方米", example = "120")
    @DecimalMin(value = "0.0", inclusive = false, message = "店铺面积必须大于0")
    private BigDecimal area;

    @Schema(description = "省份", example = "湖南省")
    private String province;

    @Schema(description = "城市", example = "长沙市")
    private String city;

    @Schema(description = "纬度", example = "28.189400")
    private BigDecimal latitude;

    @Schema(description = "经度", example = "112.986100")
    private BigDecimal longitude;
}
