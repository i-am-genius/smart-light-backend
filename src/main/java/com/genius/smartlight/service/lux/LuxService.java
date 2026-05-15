package com.genius.smartlight.service.lux;

import com.genius.smartlight.vo.lux.LuxCreateReqVO;
import com.genius.smartlight.vo.lux.LuxRespVO;

import java.util.List;

public interface LuxService {

    Long createLuxRecord(LuxCreateReqVO reqVO);

    LuxRespVO getLatestLuxRecord(String chipId);

    List<LuxRespVO> getLuxRecordList(String chipId);
}