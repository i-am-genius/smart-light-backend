package com.genius.smartlight.service.store;

import com.genius.smartlight.vo.store.StoreRespVO;
import com.genius.smartlight.vo.store.StoreSaveReqVO;

public interface StoreService {

    StoreRespVO getCurrentStore();

    StoreRespVO setupCurrentStore(StoreSaveReqVO reqVO);
}