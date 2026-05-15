package com.genius.smartlight.convert.lux;

import com.genius.smartlight.dal.dataobject.LuxRecordDO;
import com.genius.smartlight.vo.lux.LuxCreateReqVO;
import com.genius.smartlight.vo.lux.LuxRespVO;

import java.math.BigDecimal;

public class LuxConvert {

    public static LuxRecordDO convert(LuxCreateReqVO reqVO) {
        LuxRecordDO record = new LuxRecordDO();
        record.setChipId(reqVO.getChipId());
        record.setLuxValue(
                reqVO.getLuxValue() == null ? null : BigDecimal.valueOf(reqVO.getLuxValue())
        );
        record.setCollectTime(reqVO.getCollectTime());
        return record;
    }

    public static LuxRespVO convert(LuxRecordDO record) {
        LuxRespVO respVO = new LuxRespVO();
        respVO.setId(record.getId());
        respVO.setChipId(record.getChipId());
        respVO.setLuxValue(
                record.getLuxValue() == null ? null : record.getLuxValue().doubleValue()
        );
        respVO.setCollectTime(record.getCollectTime());
        respVO.setCreateTime(record.getCreateTime());
        return respVO;
    }
}