package com.sky.merchant.mapper;

import java.util.List;
import com.sky.merchant.domain.Dish;
import com.sky.merchant.domain.DishFlavor;
import org.apache.ibatis.annotations.Param;

/**
 * 菜品管理Mapper接口
 * 
 * @author ruoyi
 * @date 2026-10-04
 */
public interface DishMapper 
{
    /**
     * 查询菜品管理
     * 
     * @param id 菜品管理主键
     * @return 菜品管理
     */
    Dish selectDishById(Long id);

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
     * 删除菜品管理
     * 
     * @param id 菜品管理主键
     * @return 结果
     */
    int deleteDishById(Long id);

    /**
     * 批量删除菜品管理
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    int deleteDishByIds(Long[] ids);

    /**
     * 批量删除菜品口味关系
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    int deleteDishFlavorByDishIds(Long[] ids);
    
    /**
     * 批量新增菜品口味关系
     * 
     * @param dishFlavorList 菜品口味关系列表
     * @return 结果
     */
    int batchDishFlavor(List<DishFlavor> dishFlavorList);
    

    /**
     * 通过菜品管理主键删除菜品口味关系信息
     * 
     * @param id 菜品管理ID
     * @return 结果
     */
    int deleteDishFlavorByDishId(Long id);

    /**
     * 扣减库存（Day11 Redisson 分布式锁场景）。
     * <p>
     * 关键：把「库存够不够」和「扣减」写在同一条 SQL 里，由 MySQL 的行锁保证原子性。
     * 这是锁之外的第二道防线 —— 万一分布式锁失效（Redis 挂了 / 有别的服务没走这把锁），
     * 这条 WHERE 条件仍然能挡住超卖。
     *
     * @param id    菜品 id
     * @param count 扣减数量（正数）
     * @return 影响行数：1 = 扣减成功；0 = 库存不足（where 条件不满足）
     */
    int deductStock(@org.apache.ibatis.annotations.Param("id") Long id,
                    @org.apache.ibatis.annotations.Param("count") Integer count);

    /**
     * 只查库存（Day11 对照组 v0 用）。
     * <p>
     * v0「裸写」故意用这个方法：先读库存 → 在 Java 里判断 → 再写回去。
     * 读和写之间没有原子性，正好用来复现超卖。
     * v1/v2 都不需要这个方法。
     *
     * @param id 菜品 id
     * @return 库存数量，菜品不存在时返回 null
     */
    Integer selectStockById(@org.apache.ibatis.annotations.Param("id") Long id);

    /**
     * 直接设置库存（Day11 对照组 v0 用，故意不带宽条件）。
     * <p>
     * ⚠️ 这是"错误示范"专用方法 —— 无条件、无版本校验地覆盖库存。
     * 生产代码里不该出现这种写法。
     *
     * @param id    菜品 id
     * @param stock 新的库存值
     * @return 影响行数
     */
    int updateStockById(@org.apache.ibatis.annotations.Param("id") Long id,
                               @org.apache.ibatis.annotations.Param("stock") Integer stock);

    /**
     * 乐观锁扣减库存（Day11 对照组 v3，备用）。
     * <p>
     * 和 v2 的区别：v2 让数据库直接算减法（一步、不用重试）；
     * v3 是「先读 stock/version → Java 里算好 → 带 version 写回」，
     * 写时校验 version 未变，变了就返回 0 让业务侧重试。
     *
     * @param id      菜品 id
     * @param count   算好的新库存值（不是扣减量）
     * @param version 读取时的版本号
     * @return 影响行数：1 = 成功；0 = 期间被改过，需重试
     */
    int deductStockOptimistic(@Param("id") Long id,
                              @Param("count") Integer count,
                              @Param("version") Integer version);
}
