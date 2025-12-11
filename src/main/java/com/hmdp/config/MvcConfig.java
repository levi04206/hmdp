package com.hmdp.config;

import com.hmdp.utils.UserLoginInterceptor;
import com.hmdp.utils.UserRefreshInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;
import java.util.Locale;

@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new UserLoginInterceptor()).excludePathPatterns(
                "/user/code",
                "/user/login",
                "/shop/**",
                "/voucher/**",
                "/shop-type/**",
                "/upload/**",
                "/blog/hot"
        ).order(1);

        registry.addInterceptor(new UserRefreshInterceptor(stringRedisTemplate)).order(0);
    }
}
