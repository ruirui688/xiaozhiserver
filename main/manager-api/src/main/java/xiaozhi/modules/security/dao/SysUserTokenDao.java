package xiaozhi.modules.security.dao;

import java.util.Date;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import xiaozhi.common.dao.BaseDao;
import xiaozhi.modules.security.entity.SysUserTokenEntity;

/**
 * 系统用户Token
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
@Mapper
public interface SysUserTokenDao extends BaseDao<SysUserTokenEntity> {

    SysUserTokenEntity getByToken(String token);

    void expireByToken(@Param("token") String token, @Param("expireDate") Date expireDate);

    void expireByUserId(@Param("userId") Long userId, @Param("expireDate") Date expireDate);

    void deleteExpiredByUserId(@Param("userId") Long userId, @Param("now") Date now);
}
