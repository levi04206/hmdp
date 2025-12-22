package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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
    @Resource
    private IFollowService followService;
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
        UserDTO user = UserHolder.getUser();
        if(user == null){
            return;//用户未登录，无需查询是否点赞
        }
        Long userId = UserHolder.getUser().getId();
        //2、判断该用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score != null){
            blog.setIsLike(BooleanUtil.isTrue(score != null));
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
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            //2、2未点赞则点赞
            //1、数据库点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success){
                //2、保存用户到redis
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else{
            //2、1已点赞则取消点赞
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success){
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询关注列表的博客
     * @param max
     * @param offset
     * @return
     */
    @Override
    public Result queryBlogOfBlow(Long max, Integer offset) {
        //1、获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2、查询收件箱zreversrange m1 max min count offset
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().
                reverseRangeByScoreWithScores(key, 0, max, offset, 2);// key min max count offset
        //3、解析数据：blogid,minTime(上一次查询的时间戳最小值) offset偏移值
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int offsetFinal = 1;//记录这个最后出现的时间戳在上一次查询中出现了几次
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples){
            //3、1获取id
            ids.add(Long.valueOf(tuple.getValue()));
            //3、2获取分数
            if(minTime == tuple.getScore().longValue()){
                offsetFinal++;
            }else{
                minTime = tuple.getScore().longValue();
                offsetFinal = 1;//最终要统计最后一个时间戳出现的次数
            }
        }
        //4、根据id查询blog
        String strs = StrUtil.join(",", ids);
        List<Blog> blogList = query().in("id", ids).last("order by FIELD (id," + strs + ")").list();
        for (Blog blog : blogList){
            //查询blog有关用户
            queryBlogUser(blog);
            //查询当前用户是否点赞
            isBlogLike(blog);
        }
        //5、封装并返回
        ScollResult scollResult = new ScollResult();
        scollResult.setList(blogList);
        scollResult.setOffset(offsetFinal);
        scollResult.setMinTime(minTime);
        return Result.ok(scollResult);
    }

    /**
     * 保存博客
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        //1、获取登录用户
        UserDTO userDTO = UserHolder.getUser();
        //2、保存谈点笔记
        blog.setUserId(userDTO.getId());
        boolean success = save(blog);
        if(!success){
            return Result.fail("笔记保存失败");
        }
        //3、查询笔记作者的所有粉丝select * from tb_follow where follow_user_id = ?;
        List<Follow> follows = followService.query().eq("follow_user_id", userDTO.getId()).list();
        if(follows == null || follows.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //4、推送消息给所有粉丝
        for (Follow follow : follows) {
            Long followUserId = follow.getUserId();
            String  key = FEED_KEY + followUserId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        //5、返回笔记id
        return Result.ok(blog.getId());
    }

    /**
     * 查询点赞列表
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLiked(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //1、根据key查询用户id列表
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        // 【关键修复 1】：如果 redis 返回空（没人点赞），直接返回空列表
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //2、根据用户id查询用户
        List<Long> usersId = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String ids = StrUtil.join(",", usersId);
        List<User> users = userService.query().in("id", usersId).last("order by FIELD (id,"+ ids +")").list();
        if(users == null || users.isEmpty()){
            return Result.ok();
        }
        //3、不为空转换为DTO列表返回
        List<UserDTO> userDTOS = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog  blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
