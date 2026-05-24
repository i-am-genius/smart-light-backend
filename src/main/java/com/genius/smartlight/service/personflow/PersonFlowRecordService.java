package com.genius.smartlight.service.personflow;

import com.genius.smartlight.dal.dataobject.PersonFlowRecordDO;
import com.genius.smartlight.vo.personflow.PersonFlowRecordRespVO;

import com.genius.smartlight.vo.personflow.PersonFlowTrendItemVO;

import java.time.LocalDateTime;
import java.util.List;

public interface PersonFlowRecordService {

    void saveRecord(PersonFlowRecordDO record);

    List<PersonFlowRecordRespVO> getRecentRecords(int limit);

    List<PersonFlowRecordRespVO> getList(LocalDateTime startTime, LocalDateTime endTime,
                                          String chipId, int pageNo, int pageSize);

    List<PersonFlowTrendItemVO> getTrend(LocalDateTime startTime, LocalDateTime endTime, String chipId);
}
