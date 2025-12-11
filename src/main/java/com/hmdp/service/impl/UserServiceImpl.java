package com.hmdp.service.impl;

import cn.hutool.Hutool;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码
     * @param phone 手机号
     * @return 发送结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1、校验手机号是否合法
        if (RegexUtils.isPhoneInvalid( phone)) {
            //2、不合法直接返回手机号不合法
            return Result.fail("手机号不合法");
        }
        //3、生成随机验证码
        String code = RandomUtil.randomNumbers(6);
        //4、将验证码保存在redis中
       stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5、发送验证码
        log.debug("验证码发送成功：{}",code);
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1、校验电话号码
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid( phone)) {
            //2、不合法直接返回手机号不合法
            return Result.fail("手机号不合法");
        }
        //2、从redis中获取验证码校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //3、根据手机号查询用户（查到直接存，查不到创建一个之后直接存redis）
        User user = query().eq("phone", phone).one();//基于mybatisplus的查询
        //4、没查到创建一个新用户
        if(user == null){
            user = createNewUser(phone);
        }


        //5、查到了直接存(将信息保存至redis中)
        //5.1随即生成token，作为登陆令牌
        String token = UUID.randomUUID().toString();
        //5、2将user对象转化为hash存储
        UserDTO userDTO = new UserDTO();
        BeanUtil.copyProperties(user,userDTO);

        //5.3将hash保存至redis中
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().ignoreNullValue()
                .setIgnoreNullValue( true).setFieldValueEditor( (fieldName,fieldValue)->fieldValue.toString()));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //5.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //6.返回token
        return Result.ok(token);

    }

    private User createNewUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName("user_"+RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
