package xiaozhi.common.user;

import java.io.Serializable;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;
import xiaozhi.modules.security.ops.OpsGrant;

import lombok.Data;

/**
 * 登录用户信息
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
@Data
public class UserDetail implements Serializable {
    private Long id;
    private String username;
    private String audience;
    private String employeeId;
    private List<OpsGrant> grants = List.of();
    @JsonIgnore
    private String token;
    private Integer status;
}
