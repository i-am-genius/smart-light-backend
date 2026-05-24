package com.genius.smartlight.dal.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.genius.smartlight.dal.dataobject.WeatherRecordDO;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;

import java.util.List;

@Mapper
public interface WeatherRecordMapper extends BaseMapper<WeatherRecordDO> {

    @Lang(XMLLanguageDriver.class)
    @Select("""
        <script>
        SELECT
            id,
            store_id,
            province,
            city,
            latitude,
            longitude,
            temperature,
            apparent_temperature,
            humidity,
            wind_speed,
            weather_code,
            weather_text,
            temp_max,
            temp_min,
            collect_time,
            create_time
        FROM (
            SELECT
                wr.*,
                ROW_NUMBER() OVER (PARTITION BY store_id ORDER BY collect_time DESC, id DESC) AS rn
            FROM weather_record wr
            WHERE store_id IN
            <foreach collection="storeIds" item="storeId" open="(" separator="," close=")">
                #{storeId}
            </foreach>
        ) ranked
        WHERE rn = 1
        </script>
        """)
    List<WeatherRecordDO> selectLatestByStoreIds(@Param("storeIds") List<Long> storeIds);
}
