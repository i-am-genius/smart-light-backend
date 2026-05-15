package com.genius.smartlight.vo.store;

import lombok.Data;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "店铺信息响应结果")
public class StoreRespVO {

    @Schema(description = "店铺ID", example = "1001")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;

    @Schema(description = "用户ID", example = "1")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long userId;

    @Schema(description = "店铺名称", example = "陶鑄廣場1號店")
    private String storeName;

    @Schema(description = "店铺风格", example = "高端店")
    private String storeStyle;

    @Schema(description = "店铺面积，单位：平方米", example = "120")
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
