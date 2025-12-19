package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    /**
     * 关注或取消关注
     * @param followUserId
     * @param isFollow
     * @return
     */
    @PutMapping("/{followUserId}/{isFollow}")
    public Result follow(@PathVariable("followUserId") Long followUserId, @PathVariable("isFollow") Boolean isFollow){
       return  followService.followById(followUserId, isFollow);
    }

    /**
     * 查询是否关注
     * @param followUserId
     * @return
     */
    @PutMapping("/follow/or/not/{followUserId}")
    public Result follow(@PathVariable("followUserId") Long followUserId){
        return  followService.followOrNot(followUserId);
    }

}
