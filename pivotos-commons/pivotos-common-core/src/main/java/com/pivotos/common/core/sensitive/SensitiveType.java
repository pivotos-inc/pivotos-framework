package com.pivotos.common.core.sensitive;

/**
 * 脱敏类型
 */
public enum SensitiveType {

    /** 全脱敏 */
    ALL,
    /** 姓名：保留首字 */
    NAME,
    /** 手机号：保留前 3 后 4 */
    MOBILE,
    /** 邮箱：保留首字符与域名 */
    EMAIL,
    /** 身份证：保留前 4 后 4 */
    ID_CARD,
    /** 银行卡：保留前 4 后 4 */
    BANK_CARD,
}
