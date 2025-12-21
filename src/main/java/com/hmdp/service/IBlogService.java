package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询最热博客
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 查询博客详情
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 修改点赞数量(一人一赞)
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 查询点赞用户
     * @param id
     * @return
     */
    Result queryBlogLiked(Long id);

    /**
     * 保存探店博文
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 查询最新博文
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfBlow(Long max, Integer offset);
}
