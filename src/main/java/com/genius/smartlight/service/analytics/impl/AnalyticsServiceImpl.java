package com.genius.smartlight.service.analytics.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.PersonFlowRecordDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.dataobject.WeatherRecordDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.PersonFlowRecordMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.dal.mysql.WeatherRecordMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.analytics.AnalyticsService;
import com.genius.smartlight.vo.analytics.StrategyCompareRespVO;
import com.genius.smartlight.vo.analytics.TempPeopleTrendRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final int TREND_LIMIT = 20;
    private static final DateTimeFormatter BUCKET_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
    private static final DateTimeFormatter LABEL_FMT = DateTimeFormatter.ofPattern("MM-dd HH:00");

    private final WeatherRecordMapper weatherRecordMapper;
    private final DeviceMapper deviceMapper;
    private final StoreMapper storeMapper;
    private final PersonFlowRecordMapper personFlowRecordMapper;

    @Override
    public TempPeopleTrendRespVO getTempPeopleTrend(String chipId) {
        Long storeId = resolveStoreId(chipId);
        if (storeId == null) {
            return new TempPeopleTrendRespVO();
        }

        List<WeatherRecordDO> records = weatherRecordMapper.selectList(
                new LambdaQueryWrapper<WeatherRecordDO>()
                        .eq(WeatherRecordDO::getStoreId, storeId)
                        .isNotNull(WeatherRecordDO::getTemperature)
                        .orderByDesc(WeatherRecordDO::getCollectTime)
                        .orderByDesc(WeatherRecordDO::getCreateTime)
                        .orderByDesc(WeatherRecordDO::getId)
                        .last("limit " + TREND_LIMIT)
        );

        if (records == null || records.isEmpty()) {
            return new TempPeopleTrendRespVO();
        }

        List<WeatherRecordDO> ordered = new ArrayList<>(records);
        Collections.reverse(ordered);

        LocalDateTime trendStart = ordered.get(0).getCollectTime() != null
                ? ordered.get(0).getCollectTime() : ordered.get(0).getCreateTime();
        LocalDateTime trendEnd = ordered.get(ordered.size() - 1).getCollectTime() != null
                ? ordered.get(ordered.size() - 1).getCollectTime() : ordered.get(ordered.size() - 1).getCreateTime();

        Map<String, List<Double>> tempByHour = bucketTemperatureByHour(ordered);
        Map<String, List<Integer>> peopleByHour = bucketPeopleByHour(storeId, trendStart, trendEnd);

        return buildTrendResponse(tempByHour, peopleByHour);
    }

    @Override
    public StrategyCompareRespVO getStrategyCompare(String chipId) {
        return new StrategyCompareRespVO();
    }

    private Long resolveStoreId(String chipId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        StoreDO currentUserStore = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, currentUserId)
                        .last("limit 1")
        );

        if (chipId != null && !chipId.isBlank()) {
            DeviceDO device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<DeviceDO>()
                            .eq(DeviceDO::getChipId, chipId)
                            .last("limit 1")
            );
            if (device == null || device.getStoreId() == null) {
                return null;
            }
            if (currentUserStore != null && device.getStoreId().equals(currentUserStore.getId())) {
                return device.getStoreId();
            }
            return null;
        }

        return currentUserStore != null ? currentUserStore.getId() : null;
    }

    private Map<String, List<Double>> bucketTemperatureByHour(List<WeatherRecordDO> records) {
        Map<String, List<Double>> tempByHour = new LinkedHashMap<>();
        for (WeatherRecordDO record : records) {
            LocalDateTime refTime = record.getCollectTime() != null ? record.getCollectTime() : record.getCreateTime();
            if (refTime == null) continue;
            String hourKey = refTime.format(BUCKET_FMT);
            tempByHour.computeIfAbsent(hourKey, k -> new ArrayList<>()).add(record.getTemperature().doubleValue());
        }
        return tempByHour;
    }

    private Map<String, List<Integer>> bucketPeopleByHour(Long storeId, LocalDateTime trendStart, LocalDateTime trendEnd) {
        Map<String, List<Integer>> peopleByHour = new LinkedHashMap<>();
        if (trendStart == null || trendEnd == null) {
            return peopleByHour;
        }

        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<PersonFlowRecordDO> flowRecords = personFlowRecordMapper.selectList(
                new LambdaQueryWrapper<PersonFlowRecordDO>()
                        .and(w -> w
                            .eq(PersonFlowRecordDO::getStoreId, storeId)
                            .or()
                            .isNull(PersonFlowRecordDO::getStoreId)
                            .eq(currentUserId != null, PersonFlowRecordDO::getUserId, currentUserId)
                        )
                        .ge(PersonFlowRecordDO::getDetectTime, trendStart)
                        .le(PersonFlowRecordDO::getDetectTime, trendEnd.plusHours(1))
                        .orderByAsc(PersonFlowRecordDO::getDetectTime)
        );
        for (PersonFlowRecordDO fr : flowRecords) {
            if (fr.getDetectTime() != null && fr.getPersonCount() != null) {
                String hourKey = fr.getDetectTime().format(BUCKET_FMT);
                peopleByHour.computeIfAbsent(hourKey, k -> new ArrayList<>()).add(fr.getPersonCount());
            }
        }
        return peopleByHour;
    }

    private TempPeopleTrendRespVO buildTrendResponse(
            Map<String, List<Double>> tempByHour,
            Map<String, List<Integer>> peopleByHour) {

        java.util.Set<String> allKeys = new java.util.LinkedHashSet<>(tempByHour.keySet());
        allKeys.addAll(peopleByHour.keySet());
        List<String> sortedKeys = new ArrayList<>(allKeys);
        Collections.sort(sortedKeys);

        TempPeopleTrendRespVO respVO = new TempPeopleTrendRespVO();
        for (String hourKey : sortedKeys) {
            LocalDateTime bucketTime = LocalDateTime.parse(hourKey, BUCKET_FMT);
            respVO.getLabels().add(bucketTime.format(LABEL_FMT));

            List<Double> temps = tempByHour.get(hourKey);
            if (temps != null && !temps.isEmpty()) {
                double sum = 0;
                for (Double t : temps) sum += t;
                respVO.getTempSeries().add(Math.round(sum / temps.size() * 10.0) / 10.0);
            } else {
                respVO.getTempSeries().add(null);
            }

            List<Integer> peopleValues = peopleByHour.get(hourKey);
            if (peopleValues != null && !peopleValues.isEmpty()) {
                double sum = 0;
                for (Integer v : peopleValues) sum += v;
                respVO.getPeopleSeries().add((int) Math.round(sum / peopleValues.size()));
            } else {
                respVO.getPeopleSeries().add(0);
            }
        }

        return respVO;
    }
}
