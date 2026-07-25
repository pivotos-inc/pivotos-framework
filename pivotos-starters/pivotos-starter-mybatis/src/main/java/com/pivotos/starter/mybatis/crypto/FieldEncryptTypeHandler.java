package com.pivotos.starter.mybatis.crypto;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 字段加密 TypeHandler：DO 字段标注 @FieldEncrypt 后在实体上声明
 * typeHandler = FieldEncryptTypeHandler.class 即可透明加解密。
 */
@MappedTypes(String.class)
public class FieldEncryptTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, FieldEncryptCrypto.encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return FieldEncryptCrypto.decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return FieldEncryptCrypto.decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return FieldEncryptCrypto.decrypt(cs.getString(columnIndex));
    }
}
