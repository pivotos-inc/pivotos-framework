package com.pivotos.system.support;

import com.pivotos.starter.excel.translator.DictTranslator;
import com.pivotos.system.domain.entity.SysDictData;
import com.pivotos.system.mapper.SysDictDataMapper;
import com.pivotos.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 SysDictData 的字典翻译器实现：供 starter-excel 的 ExcelHelper 调用。
 * <p>
 * 注册为 Spring Bean，由 ExcelAutoConfiguration 自动发现并注入。
 * 内置本地缓存避免每条数据都查库。
 * </p>
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
@Component("dictTranslator")
@RequiredArgsConstructor
public class DictDataServiceTranslator implements DictTranslator {

    private final DictDataService dictDataService;

    /** 缓存 dictType + value → label */
    private final ConcurrentMap<String, String> labelCache = new ConcurrentHashMap<>();
    /** 缓存 dictType + label → value（反查） */
    private final ConcurrentMap<String, String> valueCache = new ConcurrentHashMap<>();

    @Override
    public String toLabel(String dictType, String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String cacheKey = dictType + ":" + value;
        return labelCache.computeIfAbsent(cacheKey, k -> {
            List<SysDictData> list = dictDataService.listEnabledByType(dictType);
            for (SysDictData data : list) {
                if (value.equals(data.getDictValue())) {
                    return data.getDictLabel();
                }
            }
            return value;
        });
    }

    @Override
    public String toValue(String dictType, String label) {
        if (label == null || label.isEmpty()) {
            return "";
        }
        String cacheKey = dictType + ":" + label;
        return valueCache.computeIfAbsent(cacheKey, k -> {
            List<SysDictData> list = dictDataService.listEnabledByType(dictType);
            for (SysDictData data : list) {
                if (label.equals(data.getDictLabel())) {
                    return data.getDictValue();
                }
            }
            return label;
        });
    }
}
