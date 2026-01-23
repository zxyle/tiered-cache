package dev.zhengxiang.cachedemo;

import lombok.Data;

import java.io.Serializable;

@Data
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private String name;
    private String email;
}
