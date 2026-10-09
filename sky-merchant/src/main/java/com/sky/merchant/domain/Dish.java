package com.sky.merchant.domain;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.List;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.sky.common.annotation.Excel;
import com.sky.common.core.domain.BaseEntity;

/**
 * 菜品管理对象 tb_dish
 * 
 * @author ruoyi
 * @date 2026-10-04
 */
public class Dish extends BaseEntity
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    private Long id;

    /** 菜品名称 */
    @Excel(name = "菜品名称")
    private String name;

    /** 售价 */
    @Excel(name = "售价")
    private BigDecimal price;

    /** 图片 */
    @Excel(name = "图片")
    private String image;

    /** 描述信息 */
    private String description;

    /** 售卖状态 */
    @Excel(name = "售卖状态")
    private Long status;

    /** 库存数量（Day11 Redisson 分布式锁演示用） */
    @Excel(name = "库存")
    private Integer stock;

    /**
     * 乐观锁版本号（Day11 对照用）。
     * <p>
     * 用途不是"锁"，而是给"读-改-写"加一个校验位：
     * <pre>update tb_dish set stock = #{stock}, version = version + 1
     *  where id = #{id} and version = #{version}</pre>
     * 影响行数为 0 说明期间有人改过，本次作废重试。
     * <p>
     * 注意它和 v1（Redisson 互斥锁）、v2（SQL 原子更新）是三套不同的思路，
     * 不是同一件事的三种写法 —— 详见笔记。
     */
    @Excel(name = "版本号")
    private Integer version;

    /** 菜品口味关系信息 */
    private List<DishFlavor> dishFlavorList;

    public void setId(Long id) 
    {
        this.id = id;
    }

    public Long getId() 
    {
        return id;
    }

    public void setName(String name) 
    {
        this.name = name;
    }

    public String getName() 
    {
        return name;
    }

    public void setPrice(BigDecimal price) 
    {
        this.price = price;
    }

    public BigDecimal getPrice() 
    {
        return price;
    }

    public void setImage(String image) 
    {
        this.image = image;
    }

    public String getImage() 
    {
        return image;
    }

    public void setDescription(String description) 
    {
        this.description = description;
    }

    public String getDescription() 
    {
        return description;
    }

    public void setStatus(Long status) 
    {
        this.status = status;
    }

    public Long getStatus() 
    {
        return status;
    }

    public void setStock(Integer stock)
    {
        this.stock = stock;
    }

    public Integer getStock()
    {
        return stock;
    }

    public void setVersion(Integer version)
    {
        this.version = version;
    }

    public Integer getVersion()
    {
        return version;
    }

    public List<DishFlavor> getDishFlavorList()
    {
        return dishFlavorList;
    }

    public void setDishFlavorList(List<DishFlavor> dishFlavorList)
    {
        this.dishFlavorList = dishFlavorList;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("name", getName())
            .append("price", getPrice())
            .append("image", getImage())
            .append("description", getDescription())
            .append("status", getStatus())
            .append("stock", getStock())
            .append("version", getVersion())
            .append("createTime", getCreateTime())
            .append("updateTime", getUpdateTime())
            .append("dishFlavorList", getDishFlavorList())
            .toString();
    }
}
