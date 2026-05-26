package com.genius.smartlight.service.analytics;

import com.genius.smartlight.vo.analytics.StrategyCompareRespVO;
import com.genius.smartlight.vo.analytics.TempPeopleTrendRespVO;

public interface AnalyticsService {

    TempPeopleTrendRespVO getTempPeopleTrend(String chipId);

    StrategyCompareRespVO getStrategyCompare(String chipId);
}
