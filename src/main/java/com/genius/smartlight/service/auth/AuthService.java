package com.genius.smartlight.service.auth;

import com.genius.smartlight.vo.auth.LoginReqVO;
import com.genius.smartlight.vo.auth.LoginRespVO;
import com.genius.smartlight.vo.auth.RegisterReqVO;

public interface AuthService {

    void register(RegisterReqVO reqVO);

    LoginRespVO login(LoginReqVO reqVO);
}