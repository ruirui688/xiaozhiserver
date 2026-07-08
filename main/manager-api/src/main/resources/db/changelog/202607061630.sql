ALTER TABLE sys_user
  ADD COLUMN wx_openid varchar(64) DEFAULT NULL COMMENT '微信小程序openid',
  ADD COLUMN wx_unionid varchar(64) DEFAULT NULL COMMENT '微信unionid',
  ADD UNIQUE KEY uk_sys_user_wx_openid (wx_openid);
