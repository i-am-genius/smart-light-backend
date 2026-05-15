package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@TableName("user_account")
@Data
public class UserAccountDO {
    private Long id;
    private String username;
    private String passwordHash;
    private String phone;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}