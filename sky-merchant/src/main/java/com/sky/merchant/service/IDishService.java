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
}
