package com.genius.smartlight.service.auth.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.genius.smartlight.common.ServiceException;
import com.genius.smartlight.dal.dataobject.StoreDO;
import com.genius.smartlight.dal.dataobject.UserAccountDO;
import com.genius.smartlight.dal.mysql.StoreMapper;
import com.genius.smartlight.dal.mysql.UserAccountMapper;
import com.genius.smartlight.security.JwtTokenService;
import com.genius.smartlight.service.auth.AuthService;
import com.genius.smartlight.vo.auth.LoginReqVO;
import com.genius.smartlight.vo.auth.LoginRespVO;
import com.genius.smartlight.vo.auth.RegisterReqVO;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserAccountMapper userAccountMapper;
    private final StoreMapper storeMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(RegisterReqVO reqVO) {
        if (reqVO.getUsername() == null || reqVO.getUsername().isBlank()) {
            throw new ServiceException("用户名不能为空");
        }
        if (reqVO.getPhone() == null || reqVO.getPhone().isBlank()) {
            throw new ServiceException("手机号不能为空");
        }
        if (reqVO.getPassword() == null || reqVO.getPassword().isBlank()) {
            throw new ServiceException("密码不能为空");
        }
        if (!reqVO.getPassword().equals(reqVO.getConfirmPassword())) {
            throw new ServiceException("两次密码不一致");
        }

        validatePasswordStrength(reqVO.getPassword());

        UserAccountDO exist = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccountDO>()
                        .eq(UserAccountDO::getUsername, reqVO.getUsername())
        );
        if (exist != null) {
            throw new ServiceException("用户名已存在");
        }

        LocalDateTime now = LocalDateTime.now();

        UserAccountDO user = new UserAccountDO();
        user.setUsername(reqVO.getUsername());
        user.setPasswordHash(passwordEncoder.encode(reqVO.getPassword()));
        user.setPhone(reqVO.getPhone());
        user.setEnabled(1);
        user.setCreateTime(now);
        user.setUpdateTime(now);
        userAccountMapper.insert(user);
    }

    @Override
    public LoginRespVO login(LoginReqVO reqVO) {
        if (reqVO.getUsername() == null || reqVO.getUsername().isBlank()) {
            throw new ServiceException("用户名不能为空");
        }
        if (reqVO.getPassword() == null || reqVO.getPassword().isBlank()) {
            throw new ServiceException("密码不能为空");
        }

        UserAccountDO user = userAccountMapper.selectOne(
                new LambdaQueryWrapper<UserAccountDO>()
                        .eq(UserAccountDO::getUsername, reqVO.getUsername())
        );
        if (user == null) {
            throw new ServiceException("账号或密码不正确");
        }
        if (user.getEnabled() == null || user.getEnabled() != 1) {
            throw new ServiceException("账号已禁用");
        }

        if (!passwordEncoder.matches(reqVO.getPassword(), user.getPasswordHash())) {
            throw new ServiceException("账号或密码不正确");
        }

        StoreDO store = storeMapper.selectOne(
                new LambdaQueryWrapper<StoreDO>()
                        .eq(StoreDO::getUserId, user.getId())
        );

        String token = jwtTokenService.createToken(user.getId(), user.getUsername());

        LoginRespVO respVO = new LoginRespVO();
        respVO.setToken(token);
        respVO.setUserId(user.getId());
        respVO.setUsername(user.getUsername());
        respVO.setStoreConfigured(store != null);

        if (store != null) {
            respVO.setStoreId(store.getId());
            respVO.setStoreName(store.getStoreName());
            respVO.setStoreStyle(store.getStoreStyle());
            respVO.setProvince(store.getProvince());
            respVO.setCity(store.getCity());
        }

        return respVO;
    }

    private void validatePasswordStrength(String password) {
        if (password.length() < 8 || password.length() > 64) {
            throw new ServiceException("密码长度需为 8-64 位");
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char ch = password.charAt(i);
            if (Character.isLetter(ch)) {
                hasLetter = true;
            }
            if (Character.isDigit(ch)) {
                hasDigit = true;
            }
        }

        if (!hasLetter || !hasDigit) {
            throw new ServiceException("密码需同时包含字母和数字");
        }
    }
}
