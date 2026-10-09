# Day11 扣库存的四条路：从超卖到分布式锁

日期：2026-10-09　分支：`day9-rewrite`（目标合回 `ruoyi-merchant`）　模块：`sky-merchant`　本机 Redis：8.10.2（`D:\Develop\redis-8.10.2`，端口 6379，db 0）

承接 Day10。Day9 解决「**要不要缓存**」，Day10 解决「**缓存扛不住会怎么坏**」，Day11 换一条线：**并发写同一条数据时会怎么坏**。

选扣库存做载体，因为它有一个天然性质 —— **同一行、高频写、有硬约束（不能卖成负数）**。这是并发控制最经典也最容易踩坑的形态。

全部验证落在 `http://localhost:8080`，压测脚本 `scripts/bench_deduct.py`（`threading.Barrier` 保证 N 个请求同瞬间放出）。

---

## 一、先把四版是什么说清楚

同一件事（扣库存），四种解法，代码量递增但**不一定越复杂越好**。

| 版本 | 名字 | 一句话 | 端点 |
| --- | --- | --- | --- |
| **V0** | 裸写 | 读出来、减一下、写回去（无任何保护） | `POST /merchant/dish/{id}/deduct/nolock` |
| **V1** | Redisson 分布式锁 | 加锁串行化，一夫当关 | `POST /merchant/dish/{id}/deduct/lock` |
| **V2** | SQL 原子更新 | 把减法丢给数据库，带上库存条件 | `POST /merchant/dish/{id}/deduct/atomic` |
| **V3** | 乐观锁（version CAS） | 读出 stock+version，写回时校验版本 | `POST /merchant/dish/{id}/deduct/version` |

**V0 不是"方案"，是"对照基线"** —— 把它跑出来，是为了让「超卖」这个抽象名词变成屏幕上真实的 17 份差额。没有 V0，后面三版的"解决了问题"就没有参照物。

### 业务判定标准

一次扣库存实验跑完，只看三个数：

| 量 | 定义 | 正确值 |
| --- | --- | --- |
| **成功请求数** | 有多少请求被答复「扣成功」 | 发起数 |
| **库存实际减少** | DB 里 stock 掉了多少 | = 成功请求数 |
| **超卖量** | 成功请求数 − 库存实际减少 | **0** |

超卖量的另一种说法：**答应了却扣不到的份数**。它 > 0 就是灾难。

---

## 二、V0 裸写：先看清楚"卖超"是怎么发生的

### 实现

```java
@Override
public Integer deductStockNoLock(Long dishId, Integer count) {
    if (dishId == null || count == null || count <= 0) {
        throw new ServiceException("扣减数量必须大于 0");
    }
    Integer current = dishMapper.selectStockById(dishId);
    if (current == null || current < count) {
        throw new ServiceException("菜品不存在");
    } else {
        dishMapper.updateStockById(dishId, current - count);
    }
    return dishMapper.selectStockById(dishId);
}
```

逻辑上挑不出毛病：查库存 → 不够就拒 → 够就写回新值。**单线程下绝对正确。**

### 实验：20 并发，库存 100，每次扣 1

```
起始库存：100
成功请求：20 个
结束库存：3
库存实际减少量：100 - 3 = 97   ← 等等，看下面
```

实际输出：

```
成功请求: 20 个
结束库存: 97
库存实际减少量: 3
❌ 超卖！卖出 20 份，库存只掉了 3 份 —— 差额 17 份
```

**20 个请求全都被告知"扣成功"，但库存只掉了 3。** 差额 17 份 = 欠了 17 份的账。

### 为什么 —— 用时间线看

```
时刻   请求A                      请求B                    数据库
 t0    读 stock = 100                                     100
 t1                              读 stock = 100            100   ← B 读到的也是旧值
 t2    写 stock = 100-1 = 99                               99
 t3                              写 stock = 100-1 = 99      99   ← B 用旧值计算，覆盖了 A
```

两个请求都读到 100，都算出 99，**后写的那次把先写的覆盖了**。A 辛苦扣掉的 1 份被 B 抹平。这叫 **Lost Update（更新丢失）**。

20 并发下只剩 3 次生效，说明大量写操作互相覆盖，最终只沉淀下 3 份扣减。

### 关键理解：是"少卖"还是"超卖"？

容易说反，必须掰清：

| 说法 | 对不对 |
| --- | --- |
| 库存**少扣了** 17 份 | ✅ 对 —— 账面上只扣了 3 |
| 用户**少卖了** 17 份 | ❌ 反了 —— 20 个用户全都被告知成功了 |

**准确表述**：库存还剩 97 份，但已经答应了 20 个用户各买 1 份。如果这 20 人都来取货，97 份库存要兑付 20 份的账 —— **卖超了 17 份**。这就是「超卖」（oversell）。

---

## 三、V1 Redisson 分布式锁：加锁串行化

### 思路

既然"读-算-写"被打断才出事，那就**让同一时刻只有一个线程能进来走完这三步**。锁住整个临界区。

### 实现

```java
@Override
public Integer deductStockWithLock(Long dishId, Integer count) {
    if (dishId == null || count == null || count <= 0) {
        throw new ServiceException("扣减数量必须大于 0");
    }
    RLock lock = redissonClient.getLock(DISH_DEDUCT_LOCK_PREFIX + dishId);
    boolean locked = false;                      // ① 声明必须在 try 之外
    try {
        locked = lock.tryLock(3, TimeUnit.SECONDS);   // ② 只传等待时间，不传 leaseTime
        if (!locked) {
            throw new ServiceException("系统繁忙，请稍后再试");
        }
        Integer current = dishMapper.selectStockById(dishId);
        if (current == null || current < count) {
            throw new ServiceException("库存不足");
        }
        dishMapper.updateStockById(dishId, current - count);
        clearDishCache(dishId);
        return current - count;                  // ③ 返回算好的值，不返回影响行数
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServiceException("系统繁忙，请稍后再试");
    } finally {
        if (locked && lock.isHeldByCurrentThread()) {   // ④ finally 必须双条件
            lock.unlock();
        }
    }
}
```

### Redisson 加锁的四个要点（这四条是踩出来的）

| 要点 | 为什么 |
| --- | --- |
| `boolean locked = false;` 声明在 `try` **之外** | 写在 try 里的话 finally 拿不到这个变量 |
| `tryLock(3, TimeUnit.SECONDS)` **只传等待时间** | 传了 `leaseTime` 会**关掉看门狗**，锁变成固定租期，业务没跑完锁就没了 |
| `finally` 里必须判 `locked && lock.isHeldByCurrentThread()` | ① 没抢到锁却去 unlock 会抛 `IllegalMonitorStateException`；② 锁可能已超时释放、被别的线程持有，这时 unlock 会解锁别人的锁 |
| 返回值必须自己算 `current - count` | `updateStockById` 走 MyBatis update，返回的是**影响行数**（恒为 1），不是库存值 |

### 实验：20 并发，库存 97（V0 留下的状态）

```
成功请求: 20 个
起始库存 : 97
结束库存 : 77
成功扣减请求数 : 20 （每次扣 1）
库存实际减少量 : 20
✅ 数据一致：卖出 20 份，库存准确减少 20 份
```

**全绿。** 20 个请求依次排队，一个不落，超卖 0。

### 代价

- 每次扣减多 **2 次 Redis 往返**（加锁 + 解锁）
- 需要处理锁超时、续期（看门狗）、死锁、误删他人锁
- 引入了一个**新的依赖**（Redis）—— 本来只有 MySQL 一个单点，现在多一个
- 串行化意味着**吞吐上不去**：20 个请求排队走，总耗时 0.46s（V0 只要 0.04s）

**风险**：代码复杂度是 V2 的 3 倍以上，而它解决的问题 V2 用一行 SQL 也能解决。

---

## 四、V2 SQL 原子更新：把约束写进 WHERE

### 思路

与其"读出来 → 判断 → 写回去"，不如**把判断和写合并成一条 SQL**，让数据库在一条语句里原子完成：

```sql
update tb_dish
set stock = stock - #{count}
where id = #{id}
  and stock >= #{count}     -- ← 这就是全部的秘密
```

`stock >= #{count}` 一旦写进 WHERE，DB 执行时会给这行加排他锁，**"检查是否够扣"和"执行扣减"在同一条语句里完成，中间没有任何窗口**。并发请求会在这里排队，逐个判定。

### 实现

```java
@Override
public Integer deductStockAtomic(Long dishId, Integer count) {
    if (dishId == null || count == null || count <= 0) {
        throw new ServiceException("扣减数量必须大于 0");
    }
    int rows = dishMapper.deductStock(dishId, count);   // 就一行
    if (rows == 0) {
        throw new ServiceException("库存不足");
    }
    clearDishCache(dishId);
    return dishMapper.selectStockById(dishId);
}
```

Service 里零锁、零重试、零循环 —— **因为它把并发控制的责任完全交给了数据库**。

### 实验：20 并发

```
{"msg":"操作成功","mode":"v2 原子更新","code":200,"stock":99}
DB: 80 宽粉 stock=99 version=0
```

超卖 0，一次成功，零重试。**version 恒为 0** —— 因为 V2 完全不碰版本号。这一点很重要，它是 V3 的对照锚点。

### 唯一的取舍代价

`rows == 0` 的语义是模糊的，它可能表示两件事：

- 库存不足
- 菜品不存在

**V2 分不清这两个**，因为一条 UPDATE 只告诉你"改了几行"。实测印证：给不存在的 id 扣减，返回的错误信息和库存不足**一模一样**。

要区分只能多查一次 —— 但那是额外的 DB 往返。**实践中通常不区分**，因为前端收到"操作失败"已经够了。

### 为什么 V2 是扣库存的正解

| 优势 | 说明 |
| --- | --- |
| 零额外依赖 | 不需要 Redis，只用已有的 MySQL |
| 零锁管理 | 没有超时、续期、误删的问题 |
| 零重试 | 不冲突就不失败，DB 层排队保证了这点 |
| 代码最少 | 核心就一行 UPDATE |
| 天然原子 | "判断 + 扣减"在一条语句内完成 |

---

## 五、V3 乐观锁：version CAS（以及它的陷阱）

### 思路

V3 换了一条完全不同的路：**先读出快照（stock + version），算好新值，写回时校验"我读到的版本还没被人动过"**。

```sql
update tb_dish
set stock = stock - #{count},
    version = version + 1
where id = #{id}
  and version = #{version}     -- ← 我读到的版本还是当前版本吗
  and stock >= #{count}
```

- `rows == 1` → 抢到了，成功
- `rows == 0` → 版本对不上（有人插队），**重试**

这叫做 **CAS（Compare And Swap）**：比较后交换。

### 实现

```java
private static final int MAX_RETRY = 3;

@Override
public Integer deductStockOptimistic(Long dishId, Integer count) {
    if (dishId == null || count == null || count <= 0) {
        throw new ServiceException("扣减数量必须大于 0");
    }
    for (int i = 0; i < MAX_RETRY; i++) {
        // 退避：只有重试才等，成功路径零开销
        if (i > 0) {
            try {
                Thread.sleep(10L * i);          // 10ms, 20ms 递增
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("系统繁忙，请稍后再试");
            }
        }
        Dish dish = dishMapper.selectDishById(dishId);   // 每轮重新查最新值
        if (dish == null) throw new ServiceException("菜品不存在");
        if (dish.getStock() == null || dish.getStock() < count) {
            throw new ServiceException("库存不足");
        }
        int rows = dishMapper.deductStockOptimistic(dishId, count, dish.getVersion());
        if (rows == 1) {
            clearDishCache(dishId);
            return dish.getStock() - count;
        }
        // rows == 0 → 版本冲突，进下一轮重试
    }
    throw new ServiceException("系统繁忙，请稍后再试");
}
```

### 三个必踩的坑

**坑一：SQL 绝不能写成绝对赋值**

错误的写法（我们真的写错并踩了）：

```sql
set stock = #{stock}          -- ❌ 把库存"设为"读到的值，等于没扣
```

正确的写法：

```sql
set stock = stock - #{count}  -- ✅ 用数据库当前值做减法
```

**区别**：等号右边是「传入的参数」还是「数据库那一列当前的值」。前者是覆盖，后者才是扣减。

错误写法在并发下的表现非常"精彩"：20 个请求全"成功"，但库存几乎没掉，最后落在一个随机值上 —— 因为每个请求都在用自己读到的旧值覆盖。这个形态是我们的实际教训。

**坑二：`rows == 0` 必须重试，而重试必须重新查**

不能把第一轮读的值带到第二轮 —— 那样 version 比对永远失败。每一轮都要 `selectDishById` 拿最新快照。

**坑三：重试必须有次数上限 + 退避**

`while(true)` 是自杀。而且**光有次数上限不够，必须加重试间隔**（见下）。

### 实验一：无退避自旋（失败）

版本 A：`for` 循环里三次重试背靠背执行，中间零等待。

| 并发 | 成功 | 库存减少 | 失败 |
| --- | --- | --- | --- |
| 5 | 3/5（60%） | 3 | 2（系统繁忙） |
| 20 | 6/20（30%） | 6 | 14（系统繁忙） |

**5 并发居然失败 2 个 —— 这不正常。** 原因在时间尺度：

```
t=0.0ms   5 个线程同时读 version=0
t=0.0ms   5 个同时 UPDATE，DB 只能让 1 个成功 → version=1
t=0.1ms   4 个进第 2 轮，同时读到 version=1
t=0.1ms   4 个同时 UPDATE，1 个成功 → version=2
t=0.2ms   3 个进第 3 轮，1 个成功
          → 2 个次数耗尽，抛"系统繁忙"
```

**3 次重试在 0.2 毫秒内全部打完。** 这个窗口太短 —— 短到所有竞争者在同一时刻反复碰撞，没有任何一个能"等别人先走完"。所谓"重试 3 次"，实际效果约等于"只试了 1 次"。

**结论**：**没有退避的重试不是重试，是自旋。**

### 实验二：加退避（成功）

版本 B：重试前 `Thread.sleep(10L * i)`，其余不变。

| 并发 | 成功 | 库存减少 | 耗时 | 对比（无退避） |
| --- | --- | --- | --- | --- |
| 5 | **5/5（100%）** | 5 | 0.25s | 3/5 → 5/5 |
| 20 | **20/20（100%）** | 20 | 0.60s | 6/20 → 20/20 |

**只加了一个 `sleep`，成功率从 30% 拉到 100%。** 代价是耗时翻倍（0.11s → 0.60s）—— 退避让冲突者排队，排队本身花时间。这就是乐观锁的「用时间换成功率」。

> **关于这组数据的边界**：单实例 + 同 JVM + 本地 DB，网络延迟 ≈ 0，20 并发退避 10ms 已足够。生产环境跨实例、有网络抖动，冲突窗口会被放大。所以 100% 这个数是**在证明"退避机制有效"，不是宣称"20 并发下乐观锁无压力"**。写结论时必须带上这句话。

### 为什么 V3 不适合扣库存

这是本实验最值钱的结论：

> **版本号乐观锁的价值前提是"冲突少"。而扣库存恰恰是"同一行高频冲突"，两者天然对立。**

对比两种方案的判据：

| | V3 version CAS | V2 条件 UPDATE |
| --- | --- | --- |
| 判据 | 我读到的世界没变吗（`version = ?`） | 我现在能不能扣（`stock >= n`） |
| 冲突时 | 版本对不上 → **整单放弃，重试** | 不存在"重试"概念，DB 排队逐个判 |
| 失败原因 | **"你慢了一步，世界变了"** | "库存真的不够了" |
| 适用 | 读多写少、冲突偶发 | 写密集、单行热点 |

**关键洞察**：`version = ?` 这个条件是**多余的**。

因为 `stock >= #{count}` 已经**完整表达**了"能不能扣"这个业务约束。加 version 反而引入了一个与业务无关的额外约束 —— 你的请求失败，不是因为库存不够，而是因为"你读到的快照过时了"。**这对用户毫无意义**，纯粹是方案自找的麻烦。

**V3 的正确用法**是这些场景：

- 用户编辑自己的资料（`version = ?` 防止 A 覆盖 B 的修改）
- 后台管理页面改表单（"数据已被他人修改，请刷新"）
- 读多写少的配置表

**共同点**：冲突是**偶发**的，撞上了让用户重试一次可以接受。

而扣库存是**热点行 + 高频写**，冲突是常态 —— 让 70% 的用户"重试"是灾难。

---

## 六、四版对照总表

| 版本 | 并发 | 成功 | 库存减少 | 超卖 | version | 耗时 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **V0 裸写** | 20 | 20 | 3 | **17** | — | 0.04s | ❌ 严重超卖（Lost Update） |
| **V1 Redisson** | 20 | 20 | 20 | 0 | — | 0.46s | ✅ 正确，代价是 Redis + 锁复杂度 |
| **V2 原子更新** | 20 | 20 | 20 | 0 | 0 | — | ✅ 正确，零锁零重试零依赖 |
| **V3 乐观锁（无退避）** | 20 | 6 | 6 | 0 | 6 | 0.11s | ⚠️ 正确但拒 70% |
| **V3 乐观锁（有退避）** | 20 | 20 | 20 | 0 | 20 | 0.60s | ✅ 正确，用耗时换成功 |

**注意 version 列**：V2 下 version 恒为 0（不碰版本号）；V3 下 version 恒等于成功数（每次成功 CAS 都 +1）。**如果 V3 的 version 大于成功数，那才是异常**（CAS 成功了但业务侧没算成功）。

---

## 七、结论链

1. **不加控制的"读-算-写" → 必然超卖**（V0 实证 17 份）
2. **分布式锁能解决，代价是**：Redis 往返 + 锁管理复杂度 + 串行化吞吐损失（V1）
3. **能用带条件的原子 UPDATE 解决，就别上锁**（V2）——**这才是扣库存的正解**
4. **乐观锁（version）适合低冲突场景**；热点行上必须配**退避**，否则大量请求被拒（V3 实证：无退避 30%，有退避 100%）
5. **最终判断**：扣库存不该用版本号 CAS。因为 `stock >= n` 已能完整表达约束，`version = ?` 是多余的；它牺牲的大量请求换不来任何业务收益。

**选型口诀**：

```
能一条 UPDATE 解决 → 用 V2（条件更新）
必须跨行/跨表/跨服务 → 用 V1（分布式锁）
读多写少、冲突偶发 → 可以用 V3（乐观锁）
```

---

## 八、面试话术

被问「扣库存怎么防超卖」时，**直接答"用乐观锁"是减分的**。这样答：

> "要分场景。
>
> **如果冲突是偶发的**（比如编辑表单），用 version 乐观锁，冲突时让用户重试。
>
> **但扣库存是单行热点写**，冲突是常态 —— 我实测过：20 并发瞬时冲击下，version 乐观锁（无退避）的成功率只有 30%，70% 的请求重试耗尽被拒。
>
> 所以扣库存应该用**带条件的原子 UPDATE**（`UPDATE ... SET stock = stock - n WHERE stock >= n`），把业务约束直接写进 WHERE，让数据库的行锁来串行化 —— 一次成功、零重试、零额外依赖。
>
> 分布式锁是最后一层：只有当扣减逻辑**跨多行 / 跨表 / 跨服务**，一条 UPDATE 兜不住时才需要。
>
> 另外补一句实操：乐观锁如果一定要用，**重试必须带退避**。我测过零退避的自旋式重试，3 次重试在 0.2 毫秒内打完，等于没重试；加 10ms 递增退避后成功率从 30% 拉到 100%。"

**这段回答里有**：方案对比 + 实测数据 + 适用边界 + 兜底手段 + 实操细节。这就是面试官想听的"技术判断力"。

---

## 九、踩坑记录

### 坑 1：Redisson 启动失败 —— 误判成 Redis 版本太老

**现象**：加 Redisson 依赖后启动报 `Unable to connect to Redis server`。

**我的误判**：以为 Redis 3.2.100（微软 2016 移植版）不支持 Redis 6.0 引入的 `HELLO` 命令，推动升级到 8.10.2。

**真因**：升级后**仍然失败**。拿到完整堆栈才发现是 `application.yml` 里 `password:` 留空 —— Redisson 4.8.0 把空值当成空字符串密码，发 `AUTH ""`，Redis 回 `ERR AUTH called without any password configured`，被层层包装成极具误导性的 `Unable to connect to Redis server`。

**修复**：把 `application.yml` 里的 `password:` 那行**注释掉**（不是留空）。

**教训**：**拿到完整堆栈前不要过早锁定单一结论。** 我因为先入为主认为"旧版 Redis 必然不行"，白折腾了一轮升级。（升级本身不亏 —— 8.10.2 更好用，但当时不是病因。）

### 坑 2：XML 改了没重新编译

**现象**：SQL 已改成 `set stock = stock - #{count}`，但压测结果形态和旧版一模一样。

**根因**：`DishMapper.xml` 在 `src/main/resources` 下，属于构建产物。IDEA 里改了**光重启 Spring Boot 不生效**，必须重新编译，而且**要带 `clean`** 才能清掉 `target` 里的旧 XML。

**修复**：

```powershell
mvn -pl sky-merchant -am clean package -o -DskipTests
```

**教训**：改了 `resources` 下的文件（XML / yml / properties），必须 clean 重编译。

### 坑 3：压测脚本的成功判定错了

**现象**：明明有 16 个请求抛了"系统繁忙"，脚本却报告"成功请求 20 个"。

**根因**：脚本用 HTTP 状态码判断成功，但**若依的 `GlobalExceptionHandler` 捕获 `ServiceException` 后返回的也是 HTTP 200**（业务码在 body 的 `code` 字段里）。所以失败请求全被记成了成功。

**修复**：改成判断 body 的 `code == 200`。

```python
if resp.get("code") == 200:
    results.append(("ok", resp.get("stock")))
else:
    results.append(("biz-err", resp.get("msg", "")))
```

**教训**：**"HTTP 200 ≠ 业务成功"** —— 这是所有基于 HTTP 状态码的统计都会掉的坑。压测数据不可信时，先查判定逻辑。

### 坑 4：V1 返回了影响行数

**现象**：V1 接口返回 `1`，看起来像"扣了 1 份"，其实是影响行数。

**根因**：`return dishMapper.updateStockById(dishId, current - count);` —— MyBatis 的 `update` 返回的是**影响行数**（这里是恒为 1），不是业务值。

**修复**：改成 `updateStockById(...); return current - count;`

**教训**：MyBatis 的 `insert` / `update` / `delete` 返回值**都是影响行数**，不是业务值。想要业务值必须自己算或再查一次。

### 坑 5：V0 的错误信息混淆了两种情况

V0 里 `if (current == null || current < count)` 用 `else` 包裹，两种情况都抛"菜品不存在"。虽然不影响超卖复现，但错误信息有误导。

---

## 十、代码位置索引

| 文件 | 内容 |
| --- | --- |
| `sky-merchant/.../controller/DishController.java` | 5 个扣减端点（133-177 行） |
| `sky-merchant/.../service/impl/DishServiceImpl.java` | V0/V1/V2/V3 四个实现 |
| `sky-merchant/.../mapper/merchant/DishMapper.java` | 4 个扣减方法签名 |
| `sky-merchant/src/main/resources/mapper/merchant/DishMapper.xml` | `deductStock`（V2）/ `deductStockOptimistic`（V3） |
| `scripts/bench_deduct.py` | 压测脚本，`Barrier` 同瞬间放出 |

### 端点一览

```
POST /merchant/dish/80/deduct         → 无保护（V0 变体）
POST /merchant/dish/80/deduct/nolock  → v0 裸写（必超卖）
POST /merchant/dish/80/deduct/lock    → v1 Redisson 分布式锁
POST /merchant/dish/80/deduct/atomic  → v2 SQL 原子更新（推荐）
POST /merchant/dish/80/deduct/version → v3 乐观锁（备选）
```

### 压测用法

```powershell
cd D:\code\sky
python scripts\bench_deduct.py {nolock|lock|atomic|version} [并发数]
```

跑之前先重置：

```sql
UPDATE tb_dish SET stock = 100, version = 0 WHERE id = 80;
```

---

## 十一、常量与参数

| 常量 | 值 | 说明 |
| --- | --- | --- |
| `DISH_DEDUCT_LOCK_PREFIX` | `merchant:dish:deduct:lock:` | V1 扣减锁前缀（后面拼 id） |
| `MAX_RETRY` | 3 | V3 重试上限 |
| 退避间隔 | `10ms * i` | V3 第 i 次重试前等待，递增 |

**`MAX_RETRY = 3` 的说明**：3 是经验值，覆盖绝大多数偶发冲突。它和退避是配套的 —— 有退避时 3 次足够；无退避时 3 次等于没有。

---

## 附：Redis 环境变更

本机 Redis 从 3.2.100 升级到 **8.10.2**：

| | 旧 | 新 |
| --- | --- | --- |
| 路径 | `D:\Develop\Redis-x64-3.2.100` | `D:\Develop\redis-8.10.2` |
| 版本 | 3.2.100（微软 2016 移植版） | 8.10.2（redis-windows 社区版） |
| `HELLO 3` | `unknown command` | `proto 3` |
| 启动 | — | `D:\Develop\redis-8.10.2\start.bat` |

**注意**：升级**不是** Redisson 问题的病因（真因是 `password:` 空值，见坑 1）。旧目录暂留观察，未删。

**另一个 3.2 时代的限制已消失**：旧版"HSET 只能单字段、多字段要 HMSET"是 3.2 的限制，8.x 已无此问题。
