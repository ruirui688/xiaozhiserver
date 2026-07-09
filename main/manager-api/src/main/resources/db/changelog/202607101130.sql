UPDATE `sys_params`
SET param_value = '0',
    remark = '无语音输入断开连接时间(秒)，0表示不因空闲语音主动断开'
WHERE param_code = 'close_connection_no_voice_time';
