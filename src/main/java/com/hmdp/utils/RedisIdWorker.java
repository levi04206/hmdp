package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * id生成器:时间戳+序列号
 */
@Component
public class RedisIdWorker {

    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /*
    * 开始时间戳
    * */
    private static final long BEGIN_TIMESTAMP = 1735689600L;
    /**
     * 序列号位数
     */
    private static final long COUNT_BITS = 32;
    public long nextId(String prefix){
        //1、生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        //2、生成序列号
        //2、1获取当天日期，精确到天
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //2、2.自增长
        Long count = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + data);
        return timestamp<<COUNT_BITS | count;
    }

   /* public static void main(String[] args) {
        // 获取时间戳 年月日 时分秒
        LocalDateTime localDateTime = LocalDateTime.of(2025, 1, 1, 0, 0, 0);
        long timeStamp = localDateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println(timeStamp);
    }*/
}
