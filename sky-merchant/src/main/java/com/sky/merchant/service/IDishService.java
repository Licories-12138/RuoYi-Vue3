package com.sky.merchant.service;

import java.util.List;
import java.util.Map;

import com.sky.merchant.domain.Dish;

/**
 * 菜品管理Service接口
 * 
 * @author ruoyi
 * @date 2026-10-04
 */
public interface IDishService 
{
    /**
     * 查询菜品管理
     * 
     * @param id 菜品管理主键
     * @return 菜品管理
     */
    Dish selectDishById(Long id);

    /**
     * 查询上架菜品管理列表
     *
     * @return 菜品管理集合
     */
    List<Dish> listOnSaleDishes();

    /**
     * 查询菜品管理列表
     * 
     * @param dish 菜品管理
     * @return 菜品管理集合
     */
    List<Dish> selectDishList(Dish dish);

    /**
     * 新增菜品管理
     * 
     * @param dish 菜品管理
     * @return 结果
     */
    int insertDish(Dish dish);

    /**
     * 修改菜品管理
     * 
     * @param dish 菜品管理
     * @return 结果
     */
    int updateDish(Dish dish);

    /**
     * 批量删除菜品管理
     * 
     * @param ids 需要删除的菜品管理主键集合
     * @return 结果
     */
    int deleteDishByIds(Long[] ids);

    /**
     * 删除菜品管理信息
     * 
     * @param id 菜品管理主键
     * @return 结果
     */
    int deleteDishById(Long id);

    /**
     * 获取热门菜品
     * @param top 热门菜品数量
     */
    List<Map<String, Object>> getHotDishes(int top);

    /**
     * 查询菜品详情（逻辑过期版）。
     * 与 selectDishById 的差异：key 永不物理消失，读到逻辑过期值时先返回旧值、
     * 再让后台线程异步重建 —— 换取"任何请求都不需要等锁"。
     */
    Dish selectDishByIdLogical(Long id);

    /**
     * 扣减库存（Day11 Redisson 分布式锁场景）。
     * <p>
     * 与缓存重建锁的差异 —— 这是"长临界区"的典型：
     * 缓存重建是毫秒级、可预测，拿不到锁可以等；
     * 扣库存是业务级、不可预测，拿不到锁必须直接拒绝。
     *
     * @param dishId 菜品 id
     * @param count  扣减数量，必须为正
     * @throws com.sky.common.exception.ServiceException 库存不足或并发冲突时抛出
     */
    void deductStock(Long dishId, Integer count);

    /**
     * 【对照组 v0 · 裸写】故意不加锁的扣库存，用于复现超卖。
     * <p>
     * ⚠️ 这是"错误示范"：查库存 → Java 里判断 → 写回去，三步之间无原子性。
     * 存在的唯一目的是让"没有锁会怎样"变成可观测的现象，不要在生产路径调用。
     *
     * @return 扣减后的库存（这个值本身就是不可信的，仅用于演示）
     */
    Integer deductStockNoLock(Long dishId, Integer count);

    /**
     * 【对照组 v1 · Redisson 锁】用分布式锁包住"读-判断-写"。
     *
     * @return 扣减后的库存
     */
    Integer deductStockWithLock(Long dishId, Integer count);

    /**
     * 【对照组 v2 · 原子更新】不依赖锁，靠一条带条件的 UPDATE 保证正确性。
     *
     * @return 扣减后的库存
     */
    Integer deductStockAtomic(Long dishId, Integer count);

    /**
     * 【对照组 v3 · 乐观锁】读 stock + version → Java 里算好 → 带 version 写回，
     * 写失败（version 变了）就重试。
     * <p>
     * 对比结论：它和 v2 都能保证不超卖，但 v2 一步到位、无重试；
     * v3 的"读-改-写"窗口只是被收窄，并未消灭，高冲突场景重试率会飙。
     *
     * @return 扣减后的库存
     */
    Integer deductStockOptimistic(Long dishId, Integer count);
}
