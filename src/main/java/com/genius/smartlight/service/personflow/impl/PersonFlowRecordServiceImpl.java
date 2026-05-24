package com.genius.smartlight.service.personflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.genius.smartlight.dal.dataobject.PersonFlowRecordDO;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.PersonFlowRecordMapper;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.personflow.PersonFlowRecordService;
import com.genius.smartlight.vo.personflow.PersonFlowRecordRespVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersonFlowRecordServiceImpl implements PersonFlowRecordService {

    private final PersonFlowRecordMapper personFlowRecordMapper;
    private final StoreMapper storeMapper;

    @Override
    public void saveRecord(PersonFlowRecordDO record) {
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        personFlowRecordMapper.insert(record);
    }

    @Override
    public List<PersonFlowRecordRespVO> getRecentRecords(int limit) {
        Long storeId = getCurrentStoreIdOrNull();
        LambdaQueryWrapper<PersonFlowRecordDO> wrapper = new LambdaQueryWrapper<PersonFlowRecordDO>()
                .eq(storeId != null, PersonFlowRecordDO::getStoreId, storeId)
                .orderByDesc(PersonFlowRecordDO::getDetectTime)
                .last("LIMIT " + Math.min(limit, 50));

        List<PersonFlowRecordDO> records = personFlowRecordMapper.selectList(wrapper);
        return records.stream().map(this::toRespVO).toList();
    }

    @Override
    public List<PersonFlowRecordRespVO> getList(LocalDateTime startTime, LocalDateTime endTime,
                                                 String chipId, int pageNo, int pageSize) {
        Long storeId = getCurrentStoreIdOrNull();
        LambdaQueryWrapper<PersonFlowRecordDO> wrapper = new LambdaQueryWrapper<PersonFlowRecordDO>()
                .eq(storeId != null, PersonFlowRecordDO::getStoreId, storeId)
                .ge(startTime != null, PersonFlowRecordDO::getDetectTime, startTime)
                .le(endTime != null, PersonFlowRecordDO::getDetectTime, endTime)
                .eq(chipId != null && !chipId.isBlank(), PersonFlowRecordDO::getChipId, chipId)
                .orderByDesc(PersonFlowRecordDO::getDetectTime);

        Page<PersonFlowRecordDO> page = new Page<>(pageNo, pageSize);
        Page<PersonFlowRecordDO> result = personFlowRecordMapper.selectPage(page, wrapper);
        return result.getRecords().stream().map(this::toRespVO).toList();
    }

    private PersonFlowRecordRespVO toRespVO(PersonFlowRecordDO record) {
        PersonFlowRecordRespVO vo = new PersonFlowRecordRespVO();
        vo.setId(record.getId());
        vo.setChipId(record.getChipId());
        vo.setSource(record.getSource());
        vo.setPersonCount(record.getPersonCount());
        vo.setConfidence(record.getConfidence());
        vo.setProcessingTime(record.getProcessingTime());
        vo.setDetectTime(record.getDetectTime());
        vo.setImageName(record.getImageName());
        vo.setCreateTime(record.getCreateTime());
        return vo;
    }

    private Long getCurrentStoreIdOrNull() {
        try {
            Long userId = SecurityUtils.getCurrentUserId();
            StoreDO store = storeMapper.selectOne(
                    new LambdaQueryWrapper<StoreDO>()
                            .eq(StoreDO::getUserId, userId)
                            .last("LIMIT 1")
            );
            return store != null ? store.getId() : null;
        } catch (Exception e) {
            log.debug("Cannot resolve current store id", e);
            return null;
        }
    }
}
