package com.genius.smartlight.service.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentStoreService {

    private final StoreMapper storeMapper;

    public StoreDO getCurrentStore() {
        StoreDO store = getCurrentStoreOrNull();
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }
        return store;
    }

    public StoreDO getCurrentStoreOrNull() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            return null;
        }
        return storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
                        .last("limit 1")
        );
    }

    public Long getCurrentStoreId() {
        return getCurrentStore().getId();
    }

    public StoreDO getOwnedStore(Long storeId) {
        if (storeId == null) {
            throw new ServiceException("storeId不能为空");
        }
        StoreDO store = storeMapper.selectById(storeId);
        if (store == null) {
            throw new ServiceException("店铺不存在");
        }
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null || !userId.equals(store.getUserId())) {
            throw new ServiceException("无权访问该店铺");
        }
        return store;
    }
}
