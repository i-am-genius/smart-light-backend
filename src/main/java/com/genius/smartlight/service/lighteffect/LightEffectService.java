package com.genius.smartlight.service.lighteffect;

import com.genius.smartlight.vo.lighteffect.LightEffectStateReqVO;
import com.genius.smartlight.vo.lighteffect.LightEffectStateRespVO;

public interface LightEffectService {

    LightEffectStateRespVO getState();

    LightEffectStateRespVO saveState(LightEffectStateReqVO reqVO);

    LightEffectStateRespVO close();

    LightEffectStateRespVO closeForLightControl(Long storeId);
}
