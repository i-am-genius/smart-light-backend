package com.genius.smartlight.dal.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.genius.smartlight.dal.dataobject.OtaFirmwareDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OtaFirmwareMapper extends BaseMapper<OtaFirmwareDO> {
}
