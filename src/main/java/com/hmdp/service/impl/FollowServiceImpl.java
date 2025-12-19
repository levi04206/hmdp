package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService; // 1. 注入 UserService

    /**
     * 关注或取消关注
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result followById(Long followUserId, Boolean isFollow) {
        //1、获取当前用户id
        Long id = UserHolder.getUser().getId();
        String key = "follows:"+id;
        //2、查询当前用户是否关注该用户
        if(isFollow){
            //3、没关注则设置关注
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if(isSuccess){stringRedisTemplate.opsForSet().add(key,followUserId.toString());
                }
        }else{
            //4、关注则取消关注
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", id).eq("follow_user_id", followUserId));
            if (isSuccess){
                stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
            }
        }
       return Result.ok();
    }

    /**
     * 查询是否关注
     * @param followUserId
     * @return
     */
    @Override
    public Result followOrNot(Long followUserId) {
        //1、获取当前用户id
        Long id = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", id).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     * @param id
     * @return
     */
    @Override
    public Result followCommons(Long id) {
        //1、查询当前用户
        Long userId = UserHolder.getUser().getId();
        String userKey = "follows:"+userId;
        String followKey = "follows:"+id;
        //2、查询当前登录用户与关注用户的交集
        Set<String> commonFollowId = stringRedisTemplate.opsForSet().intersect(userKey, followKey);
        if(commonFollowId == null || commonFollowId.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = commonFollowId.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = userService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        //3、返回结果
        return Result.ok(userDTOS);
    }
}
