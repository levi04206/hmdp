package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;
    /**
     * 查询最热博客
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        //根据用户查询
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
// 获取当前页数据
        List<Blog> records = page.getRecords();
// 查询用户
        records.forEach(blog ->{
            this.queryBlogUser(blog);
            this.isBlogLike(blog);
        });
        return Result.ok(records);
    }

    /**
     * 查询博客详情
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        //1、查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        //2、查询blog有关用户
        queryBlogUser(blog);
        //3、查询当前用户是否点赞
        isBlogLike(blog);
        return Result.ok(blog);
    }

    /**
     * 查询当前用户是否点赞
     * @param blog
     */
    private void isBlogLike(Blog blog) {
        //1、获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2、判断该用户是否点赞
        String key = "blog:liked:" + blog.getId();
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(isMember){
            blog.setIsLike(BooleanUtil.isTrue(isMember));
        }
    }

    /**
     * 点赞
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1、获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2、判断该用户是否点赞
        String key = "blog:liked:" + id;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)){
            //2、2未点赞则点赞
            //1、数据库点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success){
                //2、保存用户到redis
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }else{
            //2、1已点赞则取消点赞
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success){
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    private void queryBlogUser(Blog  blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
