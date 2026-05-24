package com.genius.smartlight.service.lux.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.LuxRecordDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.LuxRecordMapper;
import com.genius.smartlight.service.lux.MultiLuxService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.lux.MultiLuxRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
public class MultiLuxServiceImpl implements MultiLuxService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final DeviceMapper deviceMapper;
    private final LuxRecordMapper luxMapper;
    private final CurrentStoreService currentStoreService;

    @Override
    public MultiLuxRespVO getMultiLux() {
        Long currentStoreId = currentStoreService.getCurrentStoreId();

        List<DeviceDO> devices = deviceMapper.selectList(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getStoreId, currentStoreId)
        );

        Map<String, List<LuxRecordDO>> deviceLuxMap = new LinkedHashMap<>();
        SortedSet<String> labelSet = new TreeSet<>();

        for (DeviceDO device : devices) {
            List<LuxRecordDO> luxList = luxMapper.selectList(
                    new LambdaQueryWrapper<LuxRecordDO>()
                            .eq(LuxRecordDO::getChipId, device.getChipId())
                            .eq(LuxRecordDO::getStoreId, currentStoreId)
                            .orderByDesc(LuxRecordDO::getCollectTime)
                            .orderByDesc(LuxRecordDO::getCreateTime)
                            .last("LIMIT 12")
            );

            if (luxList == null || luxList.isEmpty()) {
                continue;
            }

            Collections.reverse(luxList);
            deviceLuxMap.put(device.getChipId(), luxList);

            for (LuxRecordDO lux : luxList) {
                labelSet.add(formatLabel(lux));
            }
        }

        MultiLuxRespVO respVO = new MultiLuxRespVO();
        List<String> labels = new ArrayList<>(labelSet);
        respVO.setLabels(labels);

        List<MultiLuxRespVO.Dataset> datasets = new ArrayList<>();
        for (Map.Entry<String, List<LuxRecordDO>> entry : deviceLuxMap.entrySet()) {
            datasets.add(buildDataset(entry.getKey(), entry.getValue(), labels));
        }

        respVO.setDatasets(datasets);
        return respVO;
    }

    private MultiLuxRespVO.Dataset buildDataset(String chipId, List<LuxRecordDO> luxList, List<String> labels) {
        Map<String, Double> pointMap = new HashMap<>();
        for (LuxRecordDO lux : luxList) {
            pointMap.put(
                    formatLabel(lux),
                    lux.getLuxValue() == null ? null : lux.getLuxValue().doubleValue()
            );
        }

        MultiLuxRespVO.Dataset dataset = new MultiLuxRespVO.Dataset();
        dataset.setLabel(chipId);

        List<Double> data = new ArrayList<>();
        for (String label : labels) {
            data.add(pointMap.getOrDefault(label, null));
        }
        dataset.setData(data);
        return dataset;
    }

    private String formatLabel(LuxRecordDO lux) {
        LocalDateTime time = lux.getCollectTime() != null ? lux.getCollectTime() : lux.getCreateTime();
        return time == null ? "-" : time.format(TIME_FORMATTER);
    }
}
