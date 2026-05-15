package com.genius.smartlight.service.ai;

import java.io.InputStream;

public interface MainColorService {

    MainColorResult extract(InputStream inputStream);
}