package com.genius.smartlight.dal.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.genius.smartlight.dal.dataobject.LuxRecordDO;
import com.genius.smartlight.opsadmin.OpsAdminLuxStats;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface LuxRecordMapper extends BaseMapper<LuxRecordDO> {

    @Insert("""
        INSERT INTO lux_record
        (device_id, store_id, chip_id, lux_value, collect_time, create_time)
        VALUES
        (#{deviceId}, #{storeId}, #{chipId}, #{luxValue}, #{collectTime}, #{createTime})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertDeviceLux(LuxRecordDO record);

    @Lang(XMLLanguageDriver.class)
    @Select("""
        <script>
        SELECT
            latest.store_id AS storeId,
            latest.lux_value AS latestLux,
            latest.collect_time AS latestLuxTime,
            today.avgLuxToday AS avgLuxToday,
            today.maxLuxToday AS maxLuxToday,
            today.minLuxToday AS minLuxToday,
            COALESCE(today.luxRecordCountToday, 0) AS luxRecordCountToday
        FROM (
            SELECT store_id, lux_value, collect_time
            FROM (
                SELECT
                    store_id,
                    lux_value,
                    collect_time,
                    ROW_NUMBER() OVER (PARTITION BY store_id ORDER BY collect_time DESC, id DESC) AS rn
                FROM lux_record
                WHERE store_id IN
                <foreach collection="storeIds" item="storeId" open="(" separator="," close=")">
                    #{storeId}
                </foreach>
            ) ranked
            WHERE rn = 1
        ) latest
        LEFT JOIN (
            SELECT
                store_id,
                AVG(COALESCE(lux_value, 0)) AS avgLuxToday,
                MAX(COALESCE(lux_value, 0)) AS maxLuxToday,
                MIN(COALESCE(lux_value, 0)) AS minLuxToday,
                COUNT(*) AS luxRecordCountToday
            FROM lux_record
            WHERE store_id IN
            <foreach collection="storeIds" item="storeId" open="(" separator="," close=")">
                #{storeId}
            </foreach>
              AND collect_time &gt;= #{todayStart}
              AND collect_time &lt; #{tomorrowStart}
            GROUP BY store_id
        ) today ON latest.store_id = today.store_id
        </script>
        """)
    List<OpsAdminLuxStats> selectOpsAdminLuxStats(@Param("storeIds") List<Long> storeIds,
                                                  @Param("todayStart") LocalDateTime todayStart,
                                                  @Param("tomorrowStart") LocalDateTime tomorrowStart);
}
