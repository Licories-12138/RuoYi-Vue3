package com.sky.merchant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存异步重建用的线程池（逻辑过期方案配套）。
 *
 * <p>为什么不能每次 <code>new Thread()</code>：线程创建没有上限，QPS 一高就把机器打爆 ——
 * 每个线程占约 1MB 栈空间，几万个请求就能把内存吃光。线程池把线程数量封顶并复用。
 *
 * <p>为什么队列必须是<b>有界</b>的：无界队列（比如不指定容量的 LinkedBlockingQueue）
 * 在过载时不会拒绝任务，只会一直堆积，最后 OOM；有界队列才能在过载时明确地"拒绝"。
 *
 * <p><b>拒绝策略为什么不用 CallerRunsPolicy</b>：它会让提交任务的业务线程自己去执行重建，
 * 把"立刻返回旧值"拖成"等重建跑完"，正好抵消了逻辑过期的全部意义。
 * 这里选择「丢弃 + 打日志」：丢的只是一次重建机会，缓存保持旧值，下次请求还会再触发。
 * 宁可少重建一次，也不让任何一个请求被拖慢。
 *
 * @author ruoyi
 */
@Slf4j
@Configuration
public class CacheRebuildConfig
{
    /** 线程池 Bean 名，注入时可用 @Qualifier 指定 */
    public static final String EXECUTOR_BEAN_NAME = "cacheRebuildExecutor";

    /**
     * 重建线程池。核心 4 / 上限 8 / 队列 100 —— 缓存重建是低频操作，
     * 这几个数字只是防"意外洪水"，不是按吞吐量算出来的。
     */
    @Bean(name = EXECUTOR_BEAN_NAME, destroyMethod = "shutdown")
    public ThreadPoolExecutor cacheRebuildExecutor()
    {
        ThreadFactory threadFactory = new ThreadFactory()
        {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r)
            {
                // 起个能认出来的名字：jstack、日志里一眼看到是缓存重建线程而不是业务线程
                return new Thread(r, "cache-rebuild-" + seq.getAndIncrement());
            }
        };

        RejectedExecutionHandler discardWithLog = (r, executor) ->
                log.warn("缓存重建任务被拒绝：队列已满（活跃线程 {} / 排队 {}），"
                                + "本次跳过重建，等下次请求再触发",
                        executor.getActiveCount(), executor.getQueue().size());

        return new ThreadPoolExecutor(
                4,                              // 核心线程数
                8,                              // 最大线程数
                60L, TimeUnit.SECONDS,          // 非核心线程空闲 60s 回收
                new ArrayBlockingQueue<>(100),  // 有界队列：满了就拒绝，绝不无限堆积
                threadFactory,
                discardWithLog);
    }
}
