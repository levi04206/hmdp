package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

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
        //2、查询当前用户是否关注该用户
        if(isFollow){
            //3、没关注则设置关注
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followUserId);
            save( follow);
        }else{
            //4、关注则取消关注
            remove(new QueryWrapper<Follow>().eq("user_id",id).eq("follow_user_id",followUserId));
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
}
