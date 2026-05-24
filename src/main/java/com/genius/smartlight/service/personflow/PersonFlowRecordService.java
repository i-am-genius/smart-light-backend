package com.genius.smartlight.service.personflow;

import com.genius.smartlight.dal.dataobject.PersonFlowRecordDO;
import com.genius.smartlight.vo.personflow.PersonFlowRecordRespVO;

import java.time.LocalDateTime;
import java.util.List;

public interface PersonFlowRecordService {

    void saveRecord(PersonFlowRecordDO record);

    List<PersonFlowRecordRespVO> getRecentRecords(int limit);

    List<PersonFlowRecordRespVO> getList(LocalDateTime startTime, LocalDateTime endTime,
                                          String chipId, int pageNo, int pageSize);
}
