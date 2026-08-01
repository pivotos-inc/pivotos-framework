package com.pivotos.starter.excel.function;

import java.util.List;

/**
 * 批量保存接口：导入时由业务模块实现，ExcelHelper 校验后分批次入库。
 *
 * @param <T> 数据类型
 * @author PivotOS Team
 * @since 2.1.0
 */
@FunctionalInterface
public interface BatchSaveFunction<T> {

    /**
     * 保存一批数据
     *
     * @param batch 待保存的数据列表（已通过单行校验）
     * @return 成功保存条数
     */
    int save(List<T> batch);
}
