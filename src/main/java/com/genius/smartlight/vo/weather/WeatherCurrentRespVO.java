package com.genius.smartlight.vo.weather;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "当前天气响应")
public class WeatherCurrentRespVO {

    @Schema(description = "天气记录ID", example = "1")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long id;

    @Schema(description = "店铺ID", example = "1001")
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @tools.jackson.databind.annotation.JsonSerialize(using = tools.jackson.databind.ser.std.ToStringSerializer.class)
    private Long storeId;

    @Schema(description = "省份", example = "湖南省")
    private String province;

    @Schema(description = "城市", example = "长沙市")
    private String city;

    @Schema(description = "纬度", example = "28.189400")
    private BigDecimal latitude;

    @Schema(description = "经度", example = "112.986100")
    private BigDecimal longitude;

    @Schema(description = "温度，单位摄氏度", example = "24.5")
    private BigDecimal temperature;

    @Schema(description = "体感温度，单位摄氏度", example = "25.1")
    private BigDecimal apparentTemperature;

    @Schema(description = "相对湿度，单位百分比", example = "68")
    private BigDecimal humidity;

    @Schema(description = "风速，单位km/h", example = "12.4")
    private BigDecimal windSpeed;

    @Schema(description = "天气代码", example = "1")
    private Integer weatherCode;

    @Schema(description = "天气文本", example = "多云")
    private String weatherText;

    @Schema(description = "当天最高温，单位摄氏度", example = "28.2")
    private BigDecimal tempMax;

    @Schema(description = "当天最低温，单位摄氏度", example = "18.8")
    private BigDecimal tempMin;

    @Schema(description = "采集时间")
    private LocalDateTime collectTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
