package com.sky.merchant.domain;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

@Data
public class OrderRequest {
    private String dishName;   // 菜名
    private Integer count;      // 数量
    @JsonPropertyDescription("是否要辣,true 为要辣，false 为不要辣")
    private Boolean spicy;      // 是否要辣
    @JsonPropertyDescription("是否打包,true 为打包，false 为不打包")
    private Boolean packed;       // 是否打包
}
