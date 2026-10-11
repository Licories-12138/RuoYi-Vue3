package com.sky.merchant.controller;

import com.sky.common.core.controller.BaseController;
import com.sky.common.core.domain.AjaxResult;
import com.sky.merchant.constant.RedisKeys;
import com.sky.merchant.lock.SimpleRedisLock;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 【Day12】手写 SET NX PX 锁的演示端点。
 * <p>
 * 目的：让"互斥 / 防误删 / 原子释放"这三件事<b>可以手动验证</b>，
 * 而不是靠读代码相信它成立。
 * <p>
 * token 刻意做成参数由调用方传，而不是服务端自己生成 ——
 * 这样才能手动模拟"两个不同的持有者"，去验证"别人的锁删不掉"。
 * <p>
 * 验收五步（都是 POST）：
 * <pre>
 *   1) /merchant/lockdemo/acquire?key=A&token=t1        → locked=true
 *   2) /merchant/lockdemo/acquire?key=A&token=t2        → locked=false   （互斥生效）
 *   3) /merchant/lockdemo/release?key=A&token=t2        → released=false （★ 防误删）
 *   4) /merchant/lockdemo/release?key=A&token=t1        → released=true
 *   5) /merchant/lockdemo/acquire?key=A&token=t2        → locked=true    （锁已正常释放）
 * </pre>
 * 追加观察：第 1 步用 {@code ttl=3000}，等 5 秒再执行第 4 步 ——
 * {@code released=false} 且 {@code holder=null}，这就是"锁到期自动释放"。
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/merchant/lockdemo")
public class LockDemoController extends BaseController
{
    private final SimpleRedisLock simpleRedisLock;

    public LockDemoController(SimpleRedisLock simpleRedisLock)
    {
        this.simpleRedisLock = simpleRedisLock;
    }

    /**
     * 加锁。
     *
     * @param key   业务标识，会自动拼上 LOCK_DEMO_PREFIX
     * @param token 持有者标识。不传则自动生成并回显，方便接着调 release
     * @param ttl   租期（毫秒），默认 30 秒
     */
    @SuppressWarnings("ConstantConditions")
    @PostMapping("/acquire")
    public AjaxResult acquire(@RequestParam String key,
                              @RequestParam(required = false) String token,
                              @RequestParam(defaultValue = "30000") Long ttl)
    {
        String owner = (token == null || token.isEmpty()) ? SimpleRedisLock.newToken() : token;
        String redisKey = RedisKeys.LOCK_DEMO_PREFIX + key;
        boolean locked = simpleRedisLock.tryLock(redisKey, owner, ttl);

        return success().put("action", "acquire")
                        .put("key", key)
                        .put("token", owner)
                        .put("locked", locked)
                        .put("holder", simpleRedisLock.peekHolder(redisKey));
    }

    /**
     * 解锁。用"别人的 token"调用会返回 {@code released=false} —— 这就是防误删的验证点。
     */
    @SuppressWarnings("ConstantConditions")
    @PostMapping("/release")
    public AjaxResult release(@RequestParam String key,
                              @RequestParam String token)
    {
        String redisKey = RedisKeys.LOCK_DEMO_PREFIX + key;
        boolean released = simpleRedisLock.unlock(redisKey, token);

        return success().put("action", "release")
                        .put("key", key)
                        .put("token", token)
                        .put("released", released)
                        .put("holder", simpleRedisLock.peekHolder(redisKey));
    }
}
