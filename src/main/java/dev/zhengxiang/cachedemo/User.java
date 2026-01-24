package dev.zhengxiang.cachedemo;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户实体类
 * <p>
 * 实现 Serializable 接口以支持 Redis 序列化
 */
@Data
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private String id;

    /**
     * 用户名称
     */
    private String name;

    /**
     * 用户邮箱
     */
    private String email;
}
