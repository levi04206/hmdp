package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 基于springredisTemplate封装的一个缓存工具类
 */
@Component
@Slf4j
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //将Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
    public void set(String key,Object value,Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    //将Java对象序列化为json并存储在string类型的key中，并且设置逻辑过期时间，用于处理缓存击穿
    public void setWithLogicalExpire(String key,Object value,Long time, TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //存入redis中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值方式解决缓存穿透问题
                                        // key前缀，id，返回类型，数据库查询方法，缓存时间，时间单位
    public <R,ID> R getPathThrough(String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback, Long time, TimeUnit unit){
        //1、首先查询redis看是否有信息
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2、1 redis中如果查到直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //2、2 此时redis中没查到需判断是不是""是的话也直接返回
        if(json != null){
            return null;
        }
        //3、没查到去数据库中查
        R r = dbFallback.apply(id);
        //4、数据库中也没有直接返回404(redis中设置为"")
        if(r == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //5、数据库有则存到redis中
       this.set(key,r,time,unit);
        //6、返回给前端
        return r;
    }


    //根据指定的key查询缓存，并反序列化为指定类型，利用逻辑过期解决缓存击穿问题
    public <R,ID> R getWithLogicalExpire(String prefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time, TimeUnit unit){
        //1、首先查询redis看是否有商铺信息
        String key = prefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2redis中如果没查到直接返回null
        if(StrUtil.isBlank(json)){
            //3、不存在，直接返回
            return null;
        }
        //4、命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5、判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){//没过期
            //5.1未过期，直接返回
            return r;
        }
        //5.2已过期，缓存重建
        //6、缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        //6、1获取互斥锁
        boolean flag = tryLock(lockKey);

        //6、2获取成功，开启独立线程，缓存重建
        if(flag){
            //做doublecheck判断当前缓存数据是否过期
            String shopJsonDouble = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJsonDouble)){
                //再做一次失效时间判断
                RedisData redisDataDouble = JSONUtil.toBean(shopJsonDouble, RedisData.class);
                R r1 = JSONUtil.toBean((JSONObject) redisDataDouble.getData(), type);
                LocalDateTime doubleExpireTime = redisDataDouble.getExpireTime();
                if(doubleExpireTime.isAfter(LocalDateTime.now())){//没失效
                    return r1;
                }
            }
            //新建一个线程，重建缓存
            // 新建一个线程，重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 1. 重建缓存
                    //1、1 查询数据库
                    R r2 = dbFallback.apply(id);
                    //1、2重建缓存
                    this.setWithLogicalExpire(key,r2,time,unit);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 2. ⚠️ 关键修改：子线程干完活后，由子线程释放锁！
                    unLock(lockKey);
                }
            });
        }
        //6、3获取失败返回旧数据
        //6、4返回旧数据（无论成功与否）
        return r;
    }


    //尝试获取锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);//由于是封装类，可能为空
        //Boolean是boolean的包装类网络问题或键不存在但 Redis 未响应，setIfAbsent 可能会返回 null
        return BooleanUtil.isTrue( flag);
    }
    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
