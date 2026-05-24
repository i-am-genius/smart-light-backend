package com.genius.smartlight.dal.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.genius.smartlight.dal.dataobject.DurationRecordDO;
import com.genius.smartlight.opsadmin.OpsAdminDurationStats;
import com.genius.smartlight.vo.duration.DurationDeviceSummaryRespVO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.annotations.Lang;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DurationRecordMapper extends BaseMapper<DurationRecordDO> {

    @Insert("""
        INSERT INTO duration_record
        (device_id, store_id, chip_id, stat_date, duration_value, collect_time, create_time, update_time)
        VALUES
        (#{deviceId}, #{storeId}, #{chipId}, #{statDate}, #{durationValue}, #{collectTime}, NOW(), NOW())
        ON DUPLICATE KEY UPDATE
        duration_value = duration_value + VALUES(duration_value),
        collect_time = VALUES(collect_time),
        update_time = NOW()
        """)
    int insertOrIncrease(@Param("deviceId") Long deviceId,
                         @Param("storeId") Long storeId,
                         @Param("chipId") String chipId,
                         @Param("statDate") LocalDate statDate,
                         @Param("durationValue") Long durationValue,
                         @Param("collectTime") LocalDateTime collectTime);

    @Select("""
        SELECT CAST(COALESCE(SUM(duration_value), 0) AS SIGNED)
        FROM duration_record
        WHERE store_id = #{storeId}
          AND chip_id = #{chipId}
          AND stat_date BETWEEN #{startDate} AND #{endDate}
        """)
    Long sumDurationByDateRange(@Param("storeId") Long storeId,
                                @Param("chipId") String chipId,
                                @Param("startDate") LocalDate startDate,
                                @Param("endDate") LocalDate endDate);

    @Lang(XMLLanguageDriver.class)
    @Select("""
        <script>
        SELECT
            chip_id AS chipId,
            CAST(COALESCE(SUM(duration_value), 0) AS SIGNED) AS totalDuration
        FROM duration_record
        WHERE store_id = #{storeId}
          AND stat_date BETWEEN #{startDate} AND #{endDate}
          AND chip_id IS NOT NULL
          AND chip_id != ''
        <if test="chipId != null and chipId != ''">
          AND chip_id = #{chipId}
        </if>
        GROUP BY chip_id
        ORDER BY chip_id
        </script>
        """)
    List<DurationDeviceSummaryRespVO> selectDeviceSummaryByDateRange(@Param("storeId") Long storeId,
                                                                     @Param("startDate") LocalDate startDate,
                                                                     @Param("endDate") LocalDate endDate,
                                                                     @Param("chipId") String chipId);

    @Lang(XMLLanguageDriver.class)
    @Select("""
        <script>
        SELECT
            store_id AS storeId,
            CAST(COALESCE(SUM(CASE WHEN stat_date = #{today} THEN COALESCE(duration_value, 0) ELSE 0 END), 0) AS SIGNED) AS durationToday,
            CAST(COALESCE(SUM(COALESCE(duration_value, 0)), 0) AS SIGNED) AS durationTotal,
            CAST(
                FLOOR(
                    COALESCE(SUM(CASE WHEN stat_date = #{today} THEN COALESCE(duration_value, 0) ELSE 0 END), 0)
                    / NULLIF(SUM(CASE WHEN stat_date = #{today} THEN 1 ELSE 0 END), 0)
                ) AS SIGNED
            ) AS avgDurationToday,
            CAST(COALESCE(SUM(CASE WHEN stat_date = #{today} THEN 1 ELSE 0 END), 0) AS SIGNED) AS durationRecordCountToday
        FROM duration_record
        WHERE store_id IN
        <foreach collection="storeIds" item="storeId" open="(" separator="," close=")">
            #{storeId}
        </foreach>
        GROUP BY store_id
        </script>
        """)
    List<OpsAdminDurationStats> selectOpsAdminDurationStats(@Param("storeIds") List<Long> storeIds,
                                                            @Param("today") LocalDate today);
}
