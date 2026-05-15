package com.genius.smartlight.service.duration;

import com.genius.smartlight.vo.duration.DurationCreateReqVO;
import com.genius.smartlight.vo.duration.DurationDeviceSummaryRespVO;
import com.genius.smartlight.vo.duration.DurationRespVO;
import com.genius.smartlight.vo.duration.DurationSumRespVO;

import java.time.LocalDate;
import java.util.List;

public interface DurationService {

    Long createOrIncrease(DurationCreateReqVO reqVO);

    DurationRespVO getByChipIdAndDate(String chipId, LocalDate statDate);

    List<DurationRespVO> getListByChipId(String chipId);

    List<DurationRespVO> getListByDateRange(String chipId, LocalDate startDate, LocalDate endDate);

    DurationSumRespVO getSumByDateRange(String chipId, LocalDate startDate, LocalDate endDate);

    List<DurationDeviceSummaryRespVO> getDeviceSummaryByDateRange(LocalDate startDate, LocalDate endDate, String chipId);
}
