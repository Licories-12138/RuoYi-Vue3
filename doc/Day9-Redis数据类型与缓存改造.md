# Day9 Redis 数据类型与若依中的用法

日期：2026-10-05　分支：ruoyi-merchant　本机 Redis：3.2.100（`D:\Develop\Redis-x64-3.2.100`，端口 6379，db 0）

---

## 一、五种数据类型逐个实操

实操前先清了一个临时库：

```bash
redis-cli -n 15 flushdb     # 用 15 号库做实验，不碰业务的 0 号库
```

### 1. String —— 一个 key 对一个值

```bash
set demo:captcha:13800138000 8241 EX 120     # OK，验证码天生带过期时间
get demo:captcha:13800138000                 # 8241
ttl demo:captcha:13800138000                 # 119，还剩 119 秒
incr demo:retry:login:admin                  # 1
incr demo:retry:login:admin                  # 2，连续自增，做失败次数限制很合适
```

场景：验证码、登录 Token、计数器。若依里所有 Redis 用法都在这一层。

### 2. Hash —— 一个 key 装多个字段

```bash
hset demo:dish:1 name 宫保鸡丁 price 28 status 0    # ERR wrong number of arguments
hmset demo:dish:1 name 宫保鸡丁 price 28 status 0   # OK
hget demo:dish:1 price                              # 28
hincrby demo:dish:1 price 2                         # 30
hkeys demo:dish:1                                   # name price status
object encoding demo:dish:1                         # ziplist
```

两个收获：

- **本机 Redis 是 3.2.100，`HSET` 一次只能写一个字段**，一次写多个要 `HMSET`（多字段 HSET 是 Redis 4.0 才加的）。
- 小 Hash 的底层编码是 **ziplist**（压缩列表），不是哈希表，为的是省内存；字段变多变大后才转成 hashtable。

### 3. List —— 有序、可重复

```bash
rpush demo:order:queue A1001 A1002 A1003    # 3
lpush demo:order:queue A1000                # 4
lrange demo:order:queue 0 -1                # A1000 A1001 A1002 A1003
lpop demo:order:queue                       # A1000，左边出队做队列
llen demo:order:queue                       # 3
```

### 4. Set —— 无序、自动去重

```bash
sadd demo:dish:tag:辣 宫保鸡丁 水煮牛肉     # 2
sadd demo:dish:tag:辣 宫保鸡丁              # 0，重复的加不进去
sadd demo:dish:tag:素 清炒时蔬 宫保鸡丁     # 2
sinter demo:dish:tag:辣 demo:dish:tag:素    # 宫保鸡丁，交集
sismember demo:dish:tag:辣 水煮牛肉         # 1
```

### 5. ZSet —— 每个元素带一个分数

```bash
zadd demo:dish:hot 128 宫保鸡丁 96 鱼香肉丝 205 水煮牛肉   # 3
zincrby demo:dish:hot 3 宫保鸡丁                           # 131
zrevrange demo:dish:hot 0 -1 WITHSCORES                    # 水煮牛肉 205 / 宫保鸡丁 131 / 鱼香肉丝 96
zscore demo:dish:hot 宫保鸡丁                              # 131
zrevrank demo:dish:hot 水煮牛肉                            # 0
type demo:dish:hot                                         # zset
```

---

## 二、若依自己的 RedisCache 是怎么用的

`sky-common/core/redis/RedisCache.java`，269 行，封装了 String / List / Set / Hash 四类的读写。

全项目逐个方法搜了一遍调用点，结论有点意外：

| 类型 | 封装方法 | 实际调用次数 | 说明 |
| --- | --- | --- | --- |
| String | setCacheObject / getCacheObject | 多处 | 唯一真正在用的类型 |
| Hash | setCacheMap / getCacheMapValue | 0 | 有封装没人用 |
| List | setCacheList / getCacheList | 0 | 有封装没人用 |
| Set | setCacheSet / getCacheSet | 0 | 有封装没人用 |
| ZSet | 无 | — | 压根没封装 |

真正落在 Redis 上的功能，全是 String：

| 场景 | 位置 | key |
| --- | --- | --- |
| 登录态 | TokenService | `login_tokens:{uuid}` |
| 验证码 | SysLoginService | `captcha_codes:{uuid}` |
| 密码重试计数 | SysPasswordService | `pwd_err_cnt:{username}` |
| 防重复提交 | SameUrlDataInterceptor | `repeat_submit:{url}{...}` |
| 限流 | RedisConfig 的 Lua 脚本 | `rate_limit:{...}` |
| 系统参数 | SysConfigServiceImpl | `sys_config:{configKey}` |
| 字典 | DictUtils | `sys_dict:{dictType}` |

两个容易记错的细节：

1. **字典缓存走的是 Redis，不是 JVM 本地 Map**。`DictUtils.setDictCache` 调的就是 `RedisCache.setCacheObject`，所以多台服务不会出现字典不一致。
2. **缓存集合类数据时，取回来的不是 List\<T\>，而是 JSONArray**。因为序列化侧 `clazz` 传的是 `Object.class`，集合还原时没有目标泛型信息。`DictUtils.getDictCache` 的做法就是先把结果接成 `JSONArray` 再 `toList(SysDictData.class)` —— 我今天缓存菜品列表用的也是这个写法。

---

## 三、今天的改造

改动集中在 `sky-merchant`：

| 文件 | 改了什么 |
| --- | --- |
| `service/IDishService.java` | 新增 listOnSaleDishes 方法 |
| `service/impl/DishServiceImpl.java` | Cache Aside 缓存 + 写操作删缓存 |
| `agent/DishTools.java` | 注入从 Mapper 换成 Service；ZSet 记录热搜菜名 |
| `controller/DishController.java` | 新增热榜、缓存 TTL 查询、手动清缓存三个接口 |
| `controller/DishAgentController.java` | `/chat` 加答案缓存；修 tool 注册链路 |

### key 设计与 TTL

| key | 存什么 | TTL |
| --- | --- | --- |
| `merchant:dish:{id}` | 单个菜品对象 | 30 分钟 + 0~300 秒随机 |
| `merchant:dish:onsale:list` | 在售菜品列表 | 同上 |
| `merchant:dish:hot` | ZSet，菜名被问的次数 | 不设过期，长期累积 |
| `merchant:agent:chat:{md5(问题)}` | 这个问题的模型答案 | 30 分钟 |

两个设计决定：

- **列表缓存只给不带分页的 `listOnSaleDishes()` 用**。带菜名关键词的查询组合太多，命中率极低；而后台分页列表会被 PageHelper 拦出一页，更不该进缓存。理由见踩坑 1。
- **TTL 加了 0~300 秒随机抖动**。全部用固定 TTL 的话它们会在同一秒集体失效，请求一次性全压到 MySQL 上 —— 这就是雪崩，Day10 会专门展开。

---

## 四、今天踩的四个坑（按伤害排序）

### 坑 1：ChatClient.Builder 是可变对象，两个 Client 会互相覆盖

这是今天最大的收获，而且它一直潜伏在 Day7、Day8 里。

原来的写法：

```java
this.chatClient  = builder.defaultSystem("点单助手...").defaultTools(dishTools).build();
this.parseClient = builder.defaultSystem("抽取器...").build();     // 把上面的配置冲掉了
```

结果是 chatClient 的 system prompt 变成了「信息抽取器」，工具也一起没了。表现得很隐蔽：`/chat` 能正常返回，但永远不查库、答案全靠编，还会输出「菜名/份数/是否要辣」这种抽取器才有的格式。

**修法**：每个 Client 各自 `builder.clone()` 一份。

顺带解释了 Day8 记录过的那个现象 ——「parse 多余调用工具」。根因不是"复用了同一个 Client"，而是 builder 状态共享。当时那份"修复"只是把症状按住了。

### 坑 2：OpenAiChatModel 强转 options 类型

`OpenAiChatModel` 内部会把传入的 ChatOptions 强转成它自己的 `OpenAiChatOptions`。用 Spring AI 默认的 `ToolCallingChatOptions.builder().build()`（运行时是 `DefaultToolCallingChatOptions`）会直接抛：

```
ClassCastException: DefaultToolCallingChatOptions cannot be cast to OpenAiChatOptions
```

所以必须用 `OpenAiChatOptions.builder()`，而且一旦传进去就会**覆盖 starter 的默认配置**，model 和 temperature 得自己带上 —— 忘了写 model 的话，SDK 会拿自己的默认值去请求，报 `404: The model 'gpt-5-mini' does not exist`。

### 坑 3：把分页结果塞进了不带页码的缓存 key

最初我把缓存加在 `selectDishList` 上。实测第一Hit组的 SQL 是：

```sql
select ... from tb_dish WHERE status = ? LIMIT ?
```

后台列表接口调用前跑了 `PageHelper.startPage`，查出来的是"某一页"。把它写进 `merchant:dish:onsale:list`，Agent 再问「有什么菜」时拿到的就是被截断的半份数据。

**修法**：后台分页查 `selectDishList`（不带缓存），Agent/客户端这种要全量数据的调用走独立的 `listOnSaleDishes()`（带缓存）。命名上就把"是否带分页、是否带缓存"区分开。

### 坑 4：AjaxResult.success(String) 的重载

`AjaxResult.success(Object data)` 和 `AjaxResult.success(String msg)` 同时在，`success(reply)` 传字符串时会被解析成后者 —— 回答跑到了 `msg` 字段，`data` 是 null，前端取不到内容。

**修法**：写全 `AjaxResult.success("操作成功", reply)`。Day8 没踩到是因为 parse 返回的是对象。

---

## 五、验证记录（9099 端口实测）

应用跑起来后的完整验证序列：

| 步骤 | 操作 | 结果 |
| --- | --- | --- |
| 1 | GET `/merchant/dish/80`，先清缓存 | 日志：`[缓存未命中] 查库 dish id = 80`，打出 selectDishById |
| 2 | 再查一次 | 日志：`[缓存命中] dish id = 80`，**无 SQL** |
| 3 | 查 TTL | `{"ttlSeconds":2074,"exists":true}`（30 分钟 + 随机抖动） |
| 4 | PUT 修改 id=80 | `[缓存未命中] 查库 dish id = 80`，缓存已失效并重建 |
| 5 | 再次查询 | `[缓存命中]`，写后一致 |
| 6 | `/chat?message=宫保鸡丁多少钱` | 日志出现 `模型调用 searchDishByName`，回答「宫保鸡丁售价 28.00 元，目前在售」，耗时 4060 ms |
| 7 | 同一个问题再问 | `fromCache: true`，端到端 2 秒 |
| 8 | `/chat` 问「在卖的菜有哪些」 | `listOnSaleDishes` → `[缓存未命中] 查库`，返回宽粉/宫保鸡丁/鱼香肉丝/麻婆豆腐/回锅肉/酸辣土豆丝 |
| 9 | 换问法「今天菜单有什么」 | `listOnSaleDishes` → `[缓存命中] 在售菜品列表，共 6 条`，无 SQL |
| 10 | `/merchant/dish/hot` | `[{"rank":1,"name":"宫保鸡丁","count":1}]`，ZSet 计数生效 |
| 11 | `/parse?message=来两份鱼香肉丝不要辣打包` | `{"count":2,"dishName":"鱼香肉丝","packed":true,"spicy":false}`，没被工具污染 |

第 7 步的对比最直观：**同一个问题从 65.5 秒降到 2 秒**（第一次真调模型时的实测耗时）。

顺带留了一个已知问题没有解：这版是朴素缓存，**模型这次答错了也会被原样缓存 30 分钟**。要根治得做回答质量校验或反馈失效机制，留到 Day12 一致性那节处理。

### Step 5 验收：/chat 答案缓存（重写分支 day9-rewrite，8080 端口）

接口：`GET /merchant/agent/chat?message=xxx`

必须带 `Authorization: Bearer <token>` —— SecurityConfig 里 `/merchant/agent/**` 不在放行名单，最终落到 `anyRequest().authenticated()`，裸访问直接 401。

| # | 用例 | 期望 |
| --- | --- | --- |
| 1 | 首次问「宫保鸡丁要多少钱」 | 日志 `未命中调用模型`；响应 `fromCache:false`；`costMs` 是几千（不是 0） |
| 2 | 同一句原样再问 | 日志 `命中直接返回`；响应 `fromCache:true`；**响应里没有 `costMs` 字段** |
| 3 | 换一个字再问（「钱」改成「元」） | 日志重新变 `未命中调用模型`；`fromCache:false`；`costMs` 非 0 |

第 2 条那个「没有 costMs 字段」是刻意留的判别点：命中分支只 `put("fromCache", true)` 就 return，压根不经过计时那一段。所以**响应里一旦出现 costMs，就说明走的不是命中分支**，别被 `fromCache:true` 蒙过去。

第 3 条验证的是 key 的确定性 —— md5 对整句话做哈希，差一个字就是另一个 key。

Redis 侧核对：

```bash
redis-cli -n 0 keys "merchant:agent:chat:*"       # 应出现 2 个 key（用例 1、3）
redis-cli -n 0 ttl  merchant:agent:chat:<md5>     # 应在 1800 上下递减
```

命令行传中文要注意编码：用 `curl -G --data-urlencode "message=..."`。直接拼进 URL 的话，Windows 的 curl 会按 GBK 把中文送出去，md5 算出来跟 JVM 侧的 UTF-8 对不上，症状就是「明明刚问过还是 miss」。用 Apifox / Postman 这类工具不用操心，它们默认按 UTF-8 编码。

**实测结果（Apifox，2026-10-06）**

| 用例 | 请求 | 端到端耗时 | 响应体 |
| --- | --- | --- | --- |
| 1 | 宫保鸡丁多少钱（首次） | 3.69 s | `costMs:3437`、`fromCache:false` |
| 2 | 宫保鸡丁多少钱（再问） | 57 ms | `fromCache:true`、**无 costMs** |
| 3 | 宫保鸡丁多少元（换字） | 2.48 s | `costMs:2464`、`fromCache:false` |

Redis 侧核对 —— 不只是数个数，是真的反算了 md5：

| key | 反算等于 | TTL |
| --- | --- | --- |
| `merchant:agent:chat:3fbca053169b37a7c7a35e1e449a474f` | md5("宫保鸡丁多少钱") | 1730 |
| `merchant:agent:chat:37b3315d6749b9cba28ab7ee39c8530e` | md5("宫保鸡丁多少元") | 1765 |

用例 1 和 2 是同一句话，算出来是同一个 key，所以库里是 2 个而不是 3 个。取出来的 value 正好是 `宫保鸡丁售价 28.00 元，目前在售。`。

**结论：3.69 s → 57 ms，约 65 倍。** 端到端耗时比 `costMs` 多几十毫秒，差额是 Spring MVC 分发 + JSON 序列化 + 网络开销。

---

## 六、过期策略与内存淘汰（知道有哪些即可）

**过期删除**是两种一起用：

- 惰性删除：访问 key 时才判断是否过期，缺点是没人访问的过期 key 会一直占内存。
- 定期删除：默认每秒抽一部分带过期时间的 key 检查。

**内存淘汰**由 `maxmemory-policy` 控制，常用的是这几个：

| 策略 | 行为 | 什么时候用 |
| --- | --- | --- |
| noeviction | 不淘汰，写满就报错 | Redis 只做缓存之外的用途 |
| allkeys-lru | 所有 key 里淘汰最久没访问的 | 纯缓存，最常用 |
| volatile-lru | 只在设了过期时间的 key 里淘汰 | 缓存和持久数据混存 |
| allkeys-random / volatile-random | 随机淘汰 | 命中率没要求时 |
| volatile-ttl | 淘汰剩余时间最短的 | 想优先保住长 TTL 数据 |
| allkeys-lfu / volatile-lfu | 淘汰访问次数最少的 | 有明显冷热区分的业务 |

一句话记法：LRU 看最近有没有用过，LFU 看累计用了多少次。
