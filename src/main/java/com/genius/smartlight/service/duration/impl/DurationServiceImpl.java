package com.genius.smartlight.service.duration.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.convert.duration.DurationConvert;
import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.dataobject.DurationRecordDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.dal.mysql.DurationRecordMapper;
import com.genius.smartlight.service.duration.DurationService;
import com.genius.smartlight.service.store.CurrentStoreService;
import com.genius.smartlight.vo.duration.DurationCreateReqVO;
import com.genius.smartlight.vo.duration.DurationDeviceSummaryRespVO;
import com.genius.smartlight.vo.duration.DurationRespVO;
import com.genius.smartlight.vo.duration.DurationSumRespVO;
import com.genius.smartlight.websocket.WebSocketPushService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DurationServiceImpl implements DurationService {

    private final WebSocketPushService webSocketPushService;
    private final DurationRecordMapper durationRecordMapper;
    private final DeviceMapper deviceMapper;
    private final CurrentStoreService currentStoreService;

    @Override
    public Long createOrIncrease(DurationCreateReqVO reqVO) {
        if (reqVO.getChipId() == null || reqVO.getChipId().isBlank()) {
            throw new ServiceException("chipId不能为空");
        }
        if (reqVO.getDurationValue() == null || reqVO.getDurationValue() < 0) {
            throw new ServiceException("durationValue不能为空且不能小于0");
        }
        if (reqVO.getStatDate() == null) {
            reqVO.setStatDate(LocalDate.now());
        }

        DeviceDO device = deviceMapper.selectOne(
                new LambdaQueryWrapper<DeviceDO>()
                        .eq(DeviceDO::getChipId, reqVO.getChipId())
                        .last("limit 1")
        );
        if (device == null) {
            throw new ServiceException("设备不存在，请先添加设备");
        }
        if (device.getStoreId() == null) {
            throw new ServiceException("设备未绑定店铺，请先绑定设备");
        }

        LocalDateTime collectTime = LocalDateTime.now();

        durationRecordMapper.insertOrIncrease(
                device.getId(),
                device.getStoreId(),
                reqVO.getChipId(),
                reqVO.getStatDate(),
                reqVO.getDurationValue(),
                collectTime
        );

        DurationRecordDO latest = durationRecordMapper.selectOne(
                new LambdaQueryWrapper<DurationRecordDO>()
                        .eq(DurationRecordDO::getChipId, reqVO.getChipId())
                        .eq(DurationRecordDO::getDeviceId, device.getId())
                        .eq(DurationRecordDO::getStoreId, device.getStoreId())
                        .eq(DurationRecordDO::getStatDate, reqVO.getStatDate())
                        .last("limit 1")
        );

        if (latest == null) {
            throw new ServiceException("时长记录保存成功，但查询结果失败");
        }

        webSocketPushService.pushDuration(DurationConvert.convert(latest), device.getStoreId());
        return latest.getId();
    }

    @Override
    public DurationRespVO getByChipIdAndDate(String chipId, LocalDate statDate) {
        Long storeId = currentStoreService.getCurrentStoreId();
        DurationRecordDO record = durationRecordMapper.selectOne(
                new LambdaQueryWrapper<DurationRecordDO>()
                        .eq(DurationRecordDO::getChipId, chipId)
                        .eq(DurationRecordDO::getStoreId, storeId)
                        .eq(DurationRecordDO::getStatDate, statDate)
        );
        if (record == null) {
            throw new ServiceException("未找到该设备当天的停留时长记录");
        }
        return DurationConvert.convert(record);
    }

    @Override
    public List<DurationRespVO> getListByChipId(String chipId) {
        Long storeId = currentStoreService.getCurrentStoreId();
        List<DurationRecordDO> list = durationRecordMapper.selectList(
                new LambdaQueryWrapper<DurationRecordDO>()
                        .eq(DurationRecordDO::getChipId, chipId)
                        .eq(DurationRecordDO::getStoreId, storeId)
                        .orderByDesc(DurationRecordDO::getStatDate)
        );
        return list.stream().map(DurationConvert::convert).toList();
    }

    @Override
    public List<DurationRespVO> getListByDateRange(String chipId, LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new ServiceException("开始日期不能晚于结束日期");
        }

        Long storeId = currentStoreService.getCurrentStoreId();
        List<DurationRecordDO> list = durationRecordMapper.selectList(
                new LambdaQueryWrapper<DurationRecordDO>()
                        .eq(DurationRecordDO::getChipId, chipId)
                        .eq(DurationRecordDO::getStoreId, storeId)
                        .between(DurationRecordDO::getStatDate, startDate, endDate)
                        .orderByAsc(DurationRecordDO::getStatDate)
        );

        return list.stream().map(DurationConvert::convert).toList();
    }

    @Override
    public DurationSumRespVO getSumByDateRange(String chipId, LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new ServiceException("开始日期不能晚于结束日期");
        }

        Long storeId = currentStoreService.getCurrentStoreId();
        Long total = durationRecordMapper.sumDurationByDateRange(storeId, chipId, startDate, endDate);

        DurationSumRespVO respVO = new DurationSumRespVO();
        respVO.setChipId(chipId);
        respVO.setStartDate(startDate);
        respVO.setEndDate(endDate);
        respVO.setTotalDuration(total == null ? 0 : total);
        return respVO;
    }

    @Override
    public List<DurationDeviceSummaryRespVO> getDeviceSummaryByDateRange(LocalDate startDate, LocalDate endDate, String chipId) {
        if (startDate.isAfter(endDate)) {
            throw new ServiceException("开始日期不能晚于结束日期");
        }

        Long storeId = currentStoreService.getCurrentStoreId();
        String normalizedChipId = normalizeChipId(chipId);
        if (normalizedChipId != null) {
            DeviceDO device = deviceMapper.selectOne(
                    new LambdaQueryWrapper<DeviceDO>()
                            .eq(DeviceDO::getStoreId, storeId)
                            .eq(DeviceDO::getChipId, normalizedChipId)
                            .last("limit 1")
            );
            if (device == null) {
                throw new ServiceException("无权访问该设备或设备不存在");
            }
        }

        return durationRecordMapper.selectDeviceSummaryByDateRange(storeId, startDate, endDate, normalizedChipId);
    }

    private String normalizeChipId(String chipId) {
        if (chipId == null) {
            return null;
        }
        String value = chipId.trim();
        return value.isEmpty() ? null : value;
    }
}
