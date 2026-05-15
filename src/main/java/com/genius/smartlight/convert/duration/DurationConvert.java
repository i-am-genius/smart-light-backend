package com.genius.smartlight.convert.duration;

import com.genius.smartlight.dal.dataobject.DurationRecordDO;
import com.genius.smartlight.vo.duration.DurationCreateReqVO;
import com.genius.smartlight.vo.duration.DurationRespVO;

public class DurationConvert {

    public static DurationRecordDO convert(DurationCreateReqVO reqVO) {
        DurationRecordDO record = new DurationRecordDO();
        record.setChipId(reqVO.getChipId());
        record.setStatDate(reqVO.getStatDate());
        record.setDurationValue(reqVO.getDurationValue());
        return record;
    }

    public static DurationRespVO convert(DurationRecordDO record) {
        DurationRespVO respVO = new DurationRespVO();
        respVO.setId(record.getId());
        respVO.setChipId(record.getChipId());
        respVO.setStatDate(record.getStatDate());
        respVO.setDurationValue(record.getDurationValue());
        respVO.setCreateTime(record.getCreateTime());
        respVO.setUpdateTime(record.getUpdateTime());
        return respVO;
    }
}