package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询所有商铺类型
     * @return 商铺类型列表
     */
    @Override
    public List<ShopType> queryAllList() {
        String key = CACHE_SHOP_TYPE_KEY;
        //1、从redis中查询商铺类型缓存
        List<String> shopList =stringRedisTemplate.opsForList().range(key, 0, -1);
        //2、判断是否存在
        if(shopList != null && shopList.size() > 0){
            //3、存在直接返回
            List<ShopType> shopTypeList = shopList.stream().map(shopType -> JSONUtil.toBean(shopType, ShopType.class)).
                    sorted((o1, o2) -> o1.getSort() - o2.getSort()).
                    collect(Collectors.toList());
            return shopTypeList;
        }
        //3、不存在需要查数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //4、判断数据库是否有商铺类型
        if(shopTypeList ==  null || shopTypeList.size() == 0){
            return null;
        }
        //5、有的话存在redis中
        // 5.1 先删除旧的！防止重复追加
        stringRedisTemplate.delete(key);

        // 5.2 使用 rightPushAll 保证顺序（数据库查出来是啥顺序，Redis里就是啥顺序）
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList.stream()
                .map(JSONUtil::toJsonStr)
                .collect(Collectors.toList()));

        // 5.3 务必设置过期时间！防止数据由于数据库变更而不一致
        stringRedisTemplate.expire(key, 30, TimeUnit.MINUTES);
        //6、将商铺类型进行返回
        return shopTypeList;
    }
}
