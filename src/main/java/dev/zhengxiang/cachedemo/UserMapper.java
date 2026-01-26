package dev.zhengxiang.cachedemo;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper 接口
 * <p>
 * 继承 MyBatis Plus 的 BaseMapper，提供基础的 CRUD 操作
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
