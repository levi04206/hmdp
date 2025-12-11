package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisData;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Object quertById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //用互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        if(shop == null){
//            return Result.fail("店铺不存在");
//        }

        //逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return shop;
    }

    /**
     * 缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        //1、首先查询redis看是否有商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、1redis中如果查到直接返回
        if(StrUtil.isNotBlank(shopJson)){
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //2、2此时redis中没查到需判断是不是""是的话也直接返回
        if(shopJson != null){
            return null;
        }
        //3、没查到去数据库中查
        Shop shop = getById(id);
        //4、数据库中也没有直接返回404
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //5、数据库有则存到redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6、返回给前端
        return shop;
    }

    /**
     * 用互斥锁解决缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        String lockKey = LOCK_SHOP_KEY + id;

        // 循环体开始：替代递归
        while (true) {
            try {
                // 1. 查询 Redis
                String shopJson = stringRedisTemplate.opsForValue().get(key);

                // 2. 判断缓存命中
                if (StrUtil.isNotBlank(shopJson)) {
                    return JSONUtil.toBean(shopJson, Shop.class);
                }
                // 2.1 判断是否是空值对象（防止穿透）
                if (shopJson != null) {
                    return null;
                }

                // 3. 尝试获取互斥锁
                boolean isLock = tryLock(lockKey);

                // 4. 判断是否获取锁成功
                if (!isLock) {
                    // 【关键修改】：获取锁失败，休眠后 continue，跳回 while 开头重试
                    // 而不是调用 return queryWithMutex(id)
                    Thread.sleep(50);
                    continue;
                }

                // 5. 获取锁成功，开启 try-finally 确保释放锁
                try {
                    // 5.1 【Double Check】再次检查缓存
                    // 为什么？因为你休眠醒来后，可能别人已经重建好了
                    String shopJsonDouble = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(shopJsonDouble)) {
                        return JSONUtil.toBean(shopJsonDouble, Shop.class);
                    }

                    // 5.2 查询数据库
                    Shop shop = getById(id);

                    // 5.3 模拟数据库延迟（测试用，生产环境请删除）
                    // Thread.sleep(200);

                    // 5.4 数据库没数据，缓存空值（防穿透）
                    if (shop == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }

                    // 5.5 数据库有数据，写入 Redis
                    stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

                    // 5.6 返回结果
                    return shop;

                } finally {
                    // 6. 释放锁
                    unLock(lockKey);
                }

            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 逻辑过期缓存击穿
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        //1、首先查询redis看是否有商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2redis中如果没查到直接返回null
        if(StrUtil.isBlank(shopJson)){
            //3、不存在，直接返回
            return null;
        }

        //4、命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5、判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){//没过期
            //5.1未过期，直接返回
            return shop;
        }
        //5.2已过期，缓存重建
        //6、缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        //6、1获取互斥锁
        boolean flag = tryLock(lockKey);

        //6、2获取成功，开启独立线程，缓存重建
        if(flag){
            //做doublecheck判断当前是否还存在缓存数据
            String shopJsonDouble = stringRedisTemplate.opsForValue().get(key);
            if(StrUtil.isNotBlank(shopJsonDouble)){
                //再做一次失效时间判断
                RedisData redisDataDouble = JSONUtil.toBean(shopJsonDouble, RedisData.class);
                Shop shopDouble = JSONUtil.toBean((JSONObject) redisDataDouble.getData(), Shop.class);
                LocalDateTime doubleExpireTime = redisDataDouble.getExpireTime();
                if(doubleExpireTime.isAfter(LocalDateTime.now())){//没失效
                    return shopDouble;
                }
            }
            //新建一个线程，重建缓存
            // 新建一个线程，重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 1. 重建缓存
                    this.saveShop2Redis(id, LOCK_SHOP_TTL);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    // 2. ⚠️ 关键修改：子线程干完活后，由子线程释放锁！
                    unLock(lockKey);
                }
            });
            //释放锁
        }
        //6、3获取失败返回旧数据
        //6、4返回旧数据（无论成功与否）
        return shop;
    }

    /**
     * 更新商铺信息
     * @param shop 更新的商铺信息
     * @return 更新结果
     */
    @Override
    public Result updateByShop(Shop shop) {
        //1、先修改商铺信息
        Long id = shop.getId();
        if(id == null){
            return Result.fail("店铺id不能为空");
        }
        updateById( shop);
        //2、删除redis中的商铺信息
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        //3、返回结果
        return Result.ok();
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

    //提前存入热点数据缓存
    public void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //存入redis中
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
}
