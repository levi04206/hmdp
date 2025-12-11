package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expireTime;//用于存放过期时间
    private Object data;//用于存放redis缓存数据
}
