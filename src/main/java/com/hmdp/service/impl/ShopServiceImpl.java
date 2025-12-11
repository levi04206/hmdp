package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

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

    /**
     * 根据id查询商铺信息
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @Override
    public Object quertById(Long id) {
        //1、首先查询redis看是否有商铺信息
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2、1redis中如果查到直接返回
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //2、2此时redis中没查到需判断是不是""是的话也直接返回
        if(shopJson != null){
            return Result.fail("店铺不存在");
        }
        //3、没查到去数据库中查
        Shop shop = getById(id);
        //4、数据库中也没有直接返回404
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }
        //5、数据库有则存到redis中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //6、返回给前端
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
}
