package com.genius.smartlight.service.lux.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.LuxRecordDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.LuxRecordMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.lux.MultiLuxService;
import com.genius.smartlight.vo.lux.MultiLuxRespVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MultiLuxServiceImpl implements MultiLuxService {

    private final DeviceMapper deviceMapper;
    private final LuxRecordMapper luxMapper;
    private final StoreMapper storeMapper;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    @Override
    public MultiLuxRespVO getMultiLux() {
        Long currentStoreId = getCurrentStoreId();

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
                            .orderByAsc(LuxRecordDO::getCollectTime)
                            .orderByAsc(LuxRecordDO::getCreateTime)
            );

            if (luxList == null || luxList.isEmpty()) {
                continue;
            }

            List<LuxRecordDO> lastLuxList = luxList.size() > 12
                    ? luxList.subList(luxList.size() - 12, luxList.size())
                    : luxList;

            deviceLuxMap.put(device.getChipId(), lastLuxList);

            for (LuxRecordDO lux : lastLuxList) {
                labelSet.add(formatLabel(lux));
            }
        }

        MultiLuxRespVO respVO = new MultiLuxRespVO();
        List<String> labels = new ArrayList<>(labelSet);
        respVO.setLabels(labels);

        List<MultiLuxRespVO.Dataset> datasets = new ArrayList<>();

        for (Map.Entry<String, List<LuxRecordDO>> entry : deviceLuxMap.entrySet()) {
            String chipId = entry.getKey();
            List<LuxRecordDO> luxList = entry.getValue();

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
            datasets.add(dataset);
        }

        respVO.setDatasets(datasets);
        return respVO;
    }

    private Long getCurrentStoreId() {
        Long userId = SecurityUtils.getCurrentUserId();

        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
        );

        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }

        return store.getId();
    }

    private String formatLabel(LuxRecordDO lux) {
        LocalDateTime time = lux.getCollectTime() != null ? lux.getCollectTime() : lux.getCreateTime();
        if (time == null) {
            return "-";
        }
        return time.format(TIME_FORMATTER);
    }
}