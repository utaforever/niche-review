package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    // 发送验证码完整实现逻辑
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //验证手机号码是否正确
        if (RegexUtils.isPhoneInvalid(phone)){
            //若不正确则返回错误信息
            return Result.fail("手机号码格式错误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存到redis中,并设置有效期
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.info("发送验证码成功：{}", code);
        //返回验证码
        return Result.ok();
    }

    // 登录功能完整实现逻辑
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //判断手机号码是否正确
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)){
            //若不正确则返回错误信息
            return Result.fail("手机号码格式错误");
        }
        //从redis中获取验证码
        String catchCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        //校验手机号码和验证码是否符合
        if (catchCode == null || !catchCode.equals(code)){
            // 不一致或者验证码不存在报错
            return Result.fail("验证码错误");
        }
        //根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //判断用户是否存在
        if (user == null){
            //不存在则创建用户
            user = createUserWithPhone(phone);
        }
        //保存用户到redis中
        //生成token作为登录令牌，这是一个32位的UUID
        String token = UUID.randomUUID().toString(true);
        //将user转化成redis中的HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
        //设置token有效期
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        return save(user) ? user : null;
    }

    @Override
    public Result signById() {
        // 获取当前用户
        Long id = UserHolder.getUser().getId();
        //获取今天是本月第几天
        LocalDateTime now = LocalDateTime.now();
        int dayOfMonth = now.getDayOfMonth();
        // 获取key
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = USER_SIGN_KEY + id + ":" + format;
        // 写入到redis中
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        // 统计一个月的连续签到次数
        // 获取当前用户
        Long id = UserHolder.getUser().getId();

        LocalDateTime now = LocalDateTime.now();

        // 获取key
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM"));
        String key = USER_SIGN_KEY + id + ":" + format;
        // 获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        // 获取redis中的签到数据
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key, BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        long signCount = result.get(0);
        if (signCount == 0){
            return Result.ok(0);
        }
        int count = 0;
        while (true) {
            if ((signCount & 1) == 0) {
                break;
            }else{
                count++;
            }
            signCount >>>= 1;
        }
        // 将数据与1做与运算，然后一直右移直到结果不为1为止，统计个数
        return Result.ok(count);
        //返回的结果
    }

    @Override
    public Result logout(HttpServletRequest request) {
        // 首先根据请求得到当前用户是谁
        String token = request.getHeader("Authorization");
        if (token == null) {
            return Result.fail("请先登录");
        }
        //删除redis中的验证token
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        // 删除ThreadLocal中的用户
        UserHolder.removeUser();
        return Result.ok();
    }
}
