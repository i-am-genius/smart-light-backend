package com.genius.smartlight.service.device.impl;

import com.genius.smartlight.dal.dataobject.DeviceDO;
import com.genius.smartlight.dal.mysql.DeviceMapper;
import com.genius.smartlight.service.device.GarmentSourceResultService;
import com.genius.smartlight.vo.device.DeviceSaveReqVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class GarmentAimEnableRestoreAspect {

    private final DeviceMapper deviceMapper;
    private final GarmentSourceResultService garmentSourceResultService;

    @Around("execution(* com.genius.smartlight.service.device.impl.DeviceServiceImpl.updateDevice(..)) && args(id,reqVO,lightControl)")
    public Object restoreLatestSourceAfterEnable(
            ProceedingJoinPoint joinPoint,
            Long id,
            DeviceSaveReqVO reqVO,
            boolean lightControl) throws Throwable {
        DeviceDO before = id == null ? null : deviceMapper.selectById(id);
        boolean enabling = reqVO != null
                && Boolean.TRUE.equals(reqVO.getGarmentAimEnabled())
                && (before == null || !Boolean.TRUE.equals(before.getGarmentAimEnabled()));

        Object result = joinPoint.proceed();

        if (!enabling || id == null) {
            return result;
        }

        DeviceDO after = deviceMapper.selectById(id);
        if (after == null || !Boolean.TRUE.equals(after.getGarmentAimEnabled())) {
            return result;
        }
        try {
            garmentSourceResultService.pushLatestResult(after.getChipId());
        } catch (RuntimeException exception) {
            log.warn("restore latest-source garment aim failed, deviceId={}, chipId={}, exceptionType={}",
                    id, after.getChipId(), exception.getClass().getSimpleName(), exception);
        }
        return result;
    }
}
