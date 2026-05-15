package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("weather_record")
public class WeatherRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("store_id")
    private Long storeId;

    @TableField("province")
    private String province;

    @TableField("city")
    private String city;

    @TableField("latitude")
    private BigDecimal latitude;

    @TableField("longitude")
    private BigDecimal longitude;

    @TableField("temperature")
    private BigDecimal temperature;

    @TableField("apparent_temperature")
    private BigDecimal apparentTemperature;

    @TableField("humidity")
    private BigDecimal humidity;

    @TableField("wind_speed")
    private BigDecimal windSpeed;

    @TableField("weather_code")
    private Integer weatherCode;

    @TableField("weather_text")
    private String weatherText;

    @TableField("temp_max")
    private BigDecimal tempMax;

    @TableField("temp_min")
    private BigDecimal tempMin;

    @TableField("collect_time")
    private LocalDateTime collectTime;

    @TableField("create_time")
    private LocalDateTime createTime;
}
