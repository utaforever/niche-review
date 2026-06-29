# Niche Review

Niche Review 是一个基于 Spring Boot、Redis、RocketMQ 和 MySQL 实现的本地生活点评项目。项目来源于黑马点评教学业务场景，并在原有功能上继续扩展了布隆过滤器、滑动窗口限流、RocketMQ 异步秒杀下单、延迟消息超时关单、支付回调并发控制等能力。

项目包含后端接口和静态前端页面，主要用于学习 Redis 在真实业务中的缓存、限流、地理位置、签到、Feed 流和秒杀场景。

## 技术栈

- Java 8
- Spring Boot 2.3.12
- MyBatis-Plus
- MySQL 8.x
- Redis
- Redisson
- RocketMQ
- Lua
- Hutool
- JMeter
- Nginx 静态页面代理

## 核心功能

- 用户登录、登出、Token 刷新
- 登录拦截器与 ThreadLocal 用户上下文
- 商户类型查询
- 商户详情查询与 Redis 缓存
- Redisson 布隆过滤器预防缓存穿透
- 空值缓存预防缓存穿透
- 逻辑过期缓存重建
- Redis GEO 附近商户查询
- 博客发布、查询、点赞
- Redis Set 实现博客点赞状态记录
- 关注、取关、共同关注
- Redis ZSet 实现关注 Feed 流滚动分页
- Redis Bitmap 实现用户签到和连续签到统计
- Redis 全局唯一 ID 生成器
- Lua 脚本实现秒杀资格校验和 Redis 原子预扣库存
- AOP + Redis ZSet + Lua 实现滑动窗口限流
- RocketMQ 实现秒杀订单异步落库
- RocketMQ 延迟消息实现超时自动关单
- Redisson 分布式锁解决支付回调和超时关单并发冲突
- 订单消费幂等与数据库唯一索引兜底

## 项目结构

```text
niche-review
├── frontend/                         # 前端静态页面
│   └── nginx-heimadianping/html/hmdp
├── src/main/java/com/hmdp
│   ├── annotation/                    # 自定义注解，例如限流注解
│   ├── aspect/                        # AOP 切面，例如滑动窗口限流
│   ├── config/                        # Web、MyBatis、Redisson 配置
│   ├── controller/                    # 控制层
│   ├── dto/                           # DTO 和统一返回对象
│   ├── entity/                        # 实体类
│   ├── mapper/                        # MyBatis-Plus Mapper
│   ├── mq/                            # RocketMQ 消费者
│   ├── service/                       # 业务接口
│   ├── service/impl/                  # 业务实现
│   └── utils/                         # Redis 工具、拦截器、常量等
├── src/main/resources
│   ├── db/                            # 数据库 SQL 和升级脚本
│   ├── mapper/                        # XML Mapper
│   ├── application.yaml               # 示例配置
│   ├── seckill.lua                    # 秒杀 Lua 脚本
│   ├── rate_limit.lua                 # 滑动窗口限流 Lua 脚本
│   └── unlock.lua                     # Redis 分布式锁释放脚本
└── pom.xml
```

## 快速启动

### 1. 准备环境

本地需要准备：

- JDK 8 或更高版本
- Maven
- MySQL 8.x
- Redis
- RocketMQ NameServer 和 Broker
- Nginx，或其他静态资源服务器

### 2. 初始化数据库

创建数据库：

```sql
CREATE DATABASE hmdp DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

导入初始化脚本：

```text
src/main/resources/db/hmdp.sql
```

如果是在已有数据库上升级，需要额外执行：

```sql
ALTER TABLE tb_voucher_order
ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

### 3. 修改后端配置

编辑：

```text
src/main/resources/application.yaml
```

将 MySQL、Redis、RocketMQ 配置改成自己的本地环境：

```yaml
spring:
  datasource:
    url: jdbc:mysql://your-ip:3306/hmdp?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8
    username: name
    password: your-password
  redis:
    host: your-ip
    port: 6379
    password: your-password

rocketmq:
  name-server: 127.0.0.1:9876
  producer:
    group: hmdp-producer-group
```

### 4. 启动后端

```bash
mvn spring-boot:run
```

后端默认端口：

```text
http://localhost:8081
```

### 5. 启动前端

前端页面在：

```text
frontend/nginx-heimadianping/html/hmdp
```

如果使用 Nginx，建议将静态目录指向该路径，并将 `/api` 代理到后端：

```nginx
location / {
    root html/hmdp;
    index index.html index.htm;
}

location /api {
    rewrite /api(/.*) $1 break;
    proxy_pass http://127.0.0.1:8081;
}
```

前端默认通过：

```javascript
let commonURL = "/api";
```

访问后端接口。

## 登录业务

登录流程：

```text
用户提交手机号和验证码
        |
后端校验验证码
        |
查询或创建用户
        |
生成随机 Token
        |
UserDTO 写入 Redis Hash
        |
Token 返回给前端
        |
前端保存 Token，并在后续请求头中携带 Authorization
```

登录态 Redis Key：

```text
login:token:{token}
```

项目中有两个拦截器：

- `RefreshTokenInterceptor`：读取请求头 Token，刷新 Redis 登录态 TTL，并将用户信息保存到 `UserHolder`
- `LoginInterceptor`：判断当前请求是否已经登录

## 商户缓存设计

商户详情查询使用 Redis 缓存：

```text
cache:shop:{shopId}
```

为了降低缓存穿透风险，项目使用了两种方式：

1. 空值缓存：数据库不存在的数据，在 Redis 中短时间缓存空字符串
2. 布隆过滤器：使用 Redisson BloomFilter 保存合法商户 ID，不存在的 ID 直接拦截

布隆过滤器 Key：

```text
bf:shop:id
bf:shop:id:config
```

## 附近商户查询

项目使用 Redis GEO 保存商户经纬度：

```text
shop:geo:{typeId}
```

查询时通过用户坐标和商户类型，按距离排序返回附近商户，并通过 MySQL 根据商户 ID 查询完整商户信息。

## 关注 Feed 流

用户发布博客后，会将博客 ID 推送到粉丝收件箱：

```text
feed:{userId}
```

数据结构使用 Redis ZSet：

- member：博客 ID
- score：发布时间戳

查询关注用户动态时，使用滚动分页方式解决同一时间戳数据分页问题。

## 签到统计

签到使用 Redis Bitmap：

```text
sign:{userId}:{yyyyMM}
```

每天签到就是设置当月对应日期的 bit 位。连续签到统计通过 `BITFIELD` 获取当月签到数据，再从低位开始统计连续 1 的个数。

## 滑动窗口限流

秒杀接口使用自定义注解：

```java
@RateLimit(maxRequests = 5, windowSeconds = 1)
```

限流实现方式：

```text
AOP 拦截方法
        |
构造限流 Key
        |
执行 rate_limit.lua
        |
Redis ZSet 删除窗口外请求
        |
统计窗口内请求数量
        |
超过阈值直接返回“请求太频繁”
```

限流 Key 按用户维度生成，已登录用户使用用户 ID，未登录用户使用 IP。

## 秒杀下单流程

秒杀入口：

```text
POST /voucher-order/seckill/{id}
```

整体流程：

```text
用户请求秒杀
        |
滑动窗口限流
        |
Lua 脚本校验 Redis 库存和一人一单
        |
Redis 原子预扣库存并记录用户已抢
        |
生成订单 ID
        |
发送 RocketMQ 普通订单消息
        |
发送 RocketMQ 延迟关单消息
        |
立即返回 orderId
```

Lua 脚本主要完成：

- 判断 Redis 秒杀库存是否充足
- 判断用户是否已经抢过
- 扣减 Redis 库存
- 将用户 ID 写入已购集合

相关 Redis Key：

```text
seckill:stock:{voucherId}
seckill:order:{voucherId}
```

## RocketMQ 异步下单

订单消息 Topic：

```text
hmdp-voucher-order-topic
```

消费者组：

```text
hmdp-voucher-order-consumer-group
```

消费者处理逻辑：

```text
收到订单消息
        |
校验消息字段
        |
获取用户维度 Redisson 锁
        |
根据 orderId 判断消息是否已处理
        |
根据 userId + voucherId 判断用户是否已购买
        |
扣减 MySQL 库存
        |
保存未支付订单
```

幂等保护：

- `orderId` 已存在：说明同一条 MQ 消息重复投递，直接返回
- `userId + voucherId` 已存在：说明用户已购买，直接返回
- 数据库唯一索引 `uk_user_voucher(user_id, voucher_id)` 兜底防止重复下单

失败重试策略：

- 重复消息：直接返回，MQ 认为消费成功
- 业务处理失败：抛出异常，MQ 后续重试
- 获取锁失败：抛出异常，MQ 后续重试

## 超时关单与支付并发控制

下单成功后发送 RocketMQ 延迟消息：

```text
hmdp-order-timeout-topic
```

默认延迟时间：

```text
15 分钟
```

超时关单逻辑：

```text
收到延迟消息
        |
获取订单维度 Redisson 锁
        |
查询订单状态
        |
如果仍然未支付，则关闭订单
        |
回滚 MySQL 库存
        |
回滚 Redis 库存和已购用户集合
```

支付回调和超时关单使用同一把订单锁：

```text
lock:order:{orderId}
```

这样可以避免以下并发问题：

```text
支付回调正在修改订单为已支付
超时关单同时把订单改成已取消
```

## 压测说明

项目使用 JMeter 进行秒杀接口压测。

推荐压测场景：

```text
1000 个用户
每个用户请求 1 次
库存设置为 100 或 1000
请求头 Authorization 从 CSV 中读取不同 token
```

需要重点验证：

- 订单数量不能超过库存
- 同一个用户不能重复下单
- MySQL 库存不能变成负数
- Redis 库存和 MySQL 库存最终要一致
- MQ 重复消费不会产生重复订单

## 常用 Redis Key

| Key | 类型 | 说明 |
| --- | --- | --- |
| `login:code:{phone}` | String | 手机验证码 |
| `login:token:{token}` | Hash | 登录用户信息 |
| `cache:shop:{shopId}` | String | 商户缓存 |
| `bf:shop:id` | String | Redisson 布隆过滤器位图 |
| `bf:shop:id:config` | Hash | Redisson 布隆过滤器配置 |
| `shop:geo:{typeId}` | GEO | 商户地理位置 |
| `blog:liked:{blogId}` | Set | 博客点赞用户 |
| `feed:{userId}` | ZSet | 关注 Feed 收件箱 |
| `sign:{userId}:{yyyyMM}` | Bitmap | 用户签到 |
| `seckill:stock:{voucherId}` | String | 秒杀 Redis 库存 |
| `seckill:order:{voucherId}` | Set | 秒杀已购用户 |
| `lock:order:{orderId}` | String | 订单支付和关单锁 |

## 项目亮点

- 使用 Redis 缓存、空值缓存和布隆过滤器降低数据库压力并防止缓存穿透
- 使用 Redis GEO 实现附近商户检索
- 使用 Bitmap 实现签到统计，节省存储空间
- 使用 ZSet 实现关注 Feed 流和滚动分页
- 使用 Lua 保证秒杀库存校验和预扣库存的原子性
- 使用 RocketMQ 将秒杀下单异步化，降低接口响应时间
- 使用 RocketMQ 延迟消息实现订单超时自动关闭
- 使用 Redisson 分布式锁解决支付回调和超时关单并发冲突
- 使用 AOP + Redis ZSet + Lua 实现滑动窗口限流
- 在 MQ 消费侧增加幂等判断，并用数据库唯一索引兜底防止重复下单

## 注意事项

- `application.yaml` 中的数据库、Redis、RocketMQ 地址均为示例配置，需要替换成本地环境。
- 前端页面通过 `/api` 访问后端，通常需要 Nginx 反向代理。
- 本项目用于学习和简历展示，支付功能为模拟支付回调，并未接入真实第三方支付平台。
- 如果使用 JMeter 压测，请不要将真实 token CSV 文件提交到 GitHub。
