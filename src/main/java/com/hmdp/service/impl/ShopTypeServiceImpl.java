package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        // 1. 从redis中查询
        String key = CACHE_SHOP_TYPE_KEY;
        //查询到的是一条条json格式的数据需要转化成bean
        List<String> jsonList = stringRedisTemplate.opsForList().range(key, 0, -1);
        // 2. 如果存在，返回
        if (jsonList != null && !jsonList.isEmpty()) {
            // 将jsonList转为shopTypeList
            List<ShopType> shopTypeList = jsonList.stream()
                    .map(json -> JSONUtil.toBean(json, ShopType.class))
                    .collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        // 3. 不存在，查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        // 4. 不存在， 返回错误
        if (typeList == null) {
            return Result.fail("店铺类型不存在");
        }
        // 5. 存在，写入redis
        List<String> collect = typeList.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, collect);
        // 6. 返回
        return Result.ok(typeList);
    }
}
