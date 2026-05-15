package com.genius.smartlight.service.store.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.security.SecurityUtils;
import com.genius.smartlight.service.store.StoreService;
import com.genius.smartlight.vo.store.StoreRespVO;
import com.genius.smartlight.vo.store.StoreSaveReqVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {

    private final StoreMapper storeMapper;

    @Override
    public StoreRespVO getCurrentStore() {
        Long userId = SecurityUtils.getCurrentUserId();

        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
        );
        if (store == null) {
            throw new ServiceException("当前用户未绑定店铺");
        }

        return convertToRespVO(store);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public StoreRespVO setupCurrentStore(StoreSaveReqVO reqVO) {
        if (reqVO.getStoreName() == null || reqVO.getStoreName().isBlank()) {
            throw new ServiceException("店铺名称不能为空");
        }
        if (reqVO.getStoreStyle() == null || reqVO.getStoreStyle().isBlank()) {
            throw new ServiceException("店铺风格不能为空");
        }
        if (reqVO.getArea() == null) {
            throw new ServiceException("店铺面积不能为空");
        }
        if (reqVO.getProvince() == null || reqVO.getProvince().isBlank()) {
            throw new ServiceException("省份不能为空");
        }
        if (reqVO.getCity() == null || reqVO.getCity().isBlank()) {
            throw new ServiceException("城市不能为空");
        }

        Long userId = SecurityUtils.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();

        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, userId)
        );

        if (store == null) {
            store = new StoreDO();
            store.setUserId(userId);
            store.setCreateTime(now);
        }

        store.setStoreName(reqVO.getStoreName());
        store.setStoreStyle(reqVO.getStoreStyle());
        store.setArea(reqVO.getArea());
        store.setProvince(reqVO.getProvince());
        store.setCity(reqVO.getCity());
        store.setLatitude(reqVO.getLatitude());
        store.setLongitude(reqVO.getLongitude());
        store.setUpdateTime(now);

        if (store.getId() == null) {
            storeMapper.insert(store);
        } else {
            storeMapper.updateById(store);
        }

        return convertToRespVO(store);
    }

    private StoreRespVO convertToRespVO(StoreDO store) {
        StoreRespVO respVO = new StoreRespVO();
        respVO.setId(store.getId());
        respVO.setUserId(store.getUserId());
        respVO.setStoreName(store.getStoreName());
        respVO.setStoreStyle(store.getStoreStyle());
        respVO.setArea(store.getArea());
        respVO.setProvince(store.getProvince());
        respVO.setCity(store.getCity());
        respVO.setLatitude(store.getLatitude());
        respVO.setLongitude(store.getLongitude());
        return respVO;
    }
}
