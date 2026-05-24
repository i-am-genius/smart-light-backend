package com.genius.smartlight.dal.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("person_flow_record")
public class PersonFlowRecordDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("store_id")
    private Long storeId;

    @TableField("user_id")
    private Long userId;

    @TableField("chip_id")
    private String chipId;

    @TableField("source")
    private String source;

    @TableField("person_count")
    private Integer personCount;

    @TableField("confidence")
    private Double confidence;

    @TableField("processing_time")
    private Double processingTime;

    @TableField("detect_time")
    private LocalDateTime detectTime;

    @TableField("image_name")
    private String imageName;

    @TableField("remark")
    private String remark;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
