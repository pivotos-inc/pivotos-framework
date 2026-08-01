package com.pivotos.starter.excel.handler;

import com.alibaba.excel.write.handler.SheetWriteHandler;
import com.alibaba.excel.write.metadata.holder.WriteSheetHolder;
import com.alibaba.excel.write.metadata.holder.WriteWorkbookHolder;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddressList;

import java.util.Map;

/**
 * 字典下拉验证 WriteHandler：为模板指定列生成数据验证下拉框。
 * <p>
 * 通过 {@link Map}&lt;Integer, String[]&gt; 传入每个列的下拉选项，
 * 在 afterSheetCreate 阶段为指定列的第1行至最大行添加下拉验证。
 * </p>
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
public class DictDropDownWriteHandler implements SheetWriteHandler {

    /** colIndex → 下拉选项数组 */
    private final Map<Integer, String[]> dropDownMap;

    public DictDropDownWriteHandler(Map<Integer, String[]> dropDownMap) {
        this.dropDownMap = dropDownMap;
    }

    @Override
    public void afterSheetCreate(WriteWorkbookHolder writeWorkbookHolder, WriteSheetHolder writeSheetHolder) {
        if (dropDownMap == null || dropDownMap.isEmpty()) {
            return;
        }
        Sheet sheet = writeSheetHolder.getSheet();
        DataValidationHelper helper = sheet.getDataValidationHelper();

        for (Map.Entry<Integer, String[]> entry : dropDownMap.entrySet()) {
            int col = entry.getKey();
            String[] options = entry.getValue();
            if (options == null || options.length == 0) {
                continue;
            }

            // 显式列表约束（下拉选项）
            DataValidationConstraint constraint = helper.createExplicitListConstraint(options);
            // 范围：第1行（跳过表头）到 65535
            CellRangeAddressList addressList = new CellRangeAddressList(1, 65535, col, col);
            DataValidation validation = helper.createValidation(constraint, addressList);
            validation.setShowErrorBox(true);
            validation.createErrorBox("填写错误", "请从下拉列表中选择有效值");
            validation.setSuppressDropDownArrow(true);
            validation.setShowPromptBox(true);
            validation.createPromptBox("提示", "请从下拉列表中选择");
            sheet.addValidationData(validation);
        }
    }
}
