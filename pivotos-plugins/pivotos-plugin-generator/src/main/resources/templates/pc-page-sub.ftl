<#-- =====================================================
 PC 端 Vue 页面模板（主子表版，S51 / 2.4-F3）
 基于 YSearchForm + YTable + YDialog + YForm + 内嵌 ElTable 明细编辑
===================================================== -->
<#function tsType javaType>
  <#if javaType == "String"><#return "string">
  <#elseif javaType == "Integer" || javaType == "Long" || javaType == "BigDecimal" || javaType == "Float" || javaType == "Double"><#return "number">
  <#elseif javaType == "Boolean"><#return "boolean">
  <#elseif javaType == "LocalDateTime" || javaType == "LocalDate" || javaType == "LocalTime"><#return "string">
  <#else><#return "any">
  </#if>
</#function>
<#function tsDefault javaType>
  <#if javaType == "String"><#return "''"><#elseif javaType == "Integer" || javaType == "Long" || javaType == "BigDecimal" || javaType == "Float" || javaType == "Double"><#return "undefined"><#elseif javaType == "Boolean"><#return "false"><#elseif javaType == "LocalDateTime" || javaType == "LocalDate" || javaType == "LocalTime"><#return "''"><#else><#return "''"></#if>
</#function>
<#function isNum javaType>
  <#if javaType == "Integer" || javaType == "Long" || javaType == "BigDecimal" || javaType == "Float" || javaType == "Double"><#return true><#else><#return false></#if>
</#function>
<script setup lang="ts">
import { computed<#if hasFk>, onMounted</#if>, reactive, ref } from 'vue';
import { ElButton, ElInput, ElInputNumber, ElMessage, ElMessageBox, ElTable, ElTableColumn } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { YDialog, YForm, YSearchForm, YTable } from '@pivotos/ui';
import type { YFormSchema, YTableColumn } from '@pivotos/ui';
import { useTablePage } from '@/hooks';
import {
  create${className},
  delete${className},
  get${className},
<#if hasFk>
  get${className}FkOptions,
</#if>
  update${className},
} from '@/api/${moduleName}/${businessName}';
import type {
  ${className}VO,
  ${className}SaveRequest,
  ${className}Query,
  ${subClassName}Item,
<#if hasFk>
  ${className}FkOption,
</#if>
} from '@/api/${moduleName}/${businessName}';

// ============================================================
// 分页查询
// ============================================================
const { loading, rows, total, params, load, search, reset } = useTablePage<${className}VO, ${className}Query>({
  url: '/${moduleName}/${businessName}/page',
<#if queryColumns?size gt 0>
  query: {
<#list queryColumns as col>
    ${col.javaField}: ${tsDefault(col.javaType)},
</#list>
  },
</#if>
});

<#if hasFk>
// ============================================================
// fk 关联下拉选项（S50 / 2.4-F2；push 原地填充保持 schema 引用有效）
// ============================================================
const fkOptions = reactive({
<#list fkColumns as col>
  ${col.javaField}: [] as ${className}FkOption[],
</#list>
});

onMounted(() => {
<#list fkColumns as col>
  get${className}FkOptions('${col.javaField}').then((list) => fkOptions.${col.javaField}.push(...list));
</#list>
});

</#if>
// ============================================================
// 搜索表单
// ============================================================
const searchSchemas: YFormSchema[] = [
<#list queryColumns as col>
  <#if col.fkTable?? && col.fkTable?has_content>
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'select', options: fkOptions.${col.javaField}, emptyOption: '全部' },
  <#elseif col.queryType == "BETWEEN">
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'daterange' },
  <#else>
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'input', placeholder: '<#if col.queryType == "LIKE">按${col.columnComment}模糊查询<#else>请输入${col.columnComment}</#if>' },
  </#if>
</#list>
];

// ============================================================
// 表格列
// ============================================================
const columns: YTableColumn<${className}VO>[] = [
  { type: 'index', label: '#', width: 56, align: 'center' },
<#list listColumns as col>
  <#if col.fkTable?? && col.fkTable?has_content>
  { prop: '${col.javaField}Label', label: '${col.columnComment}', minWidth: 140 },
  <#elseif col.javaType == "LocalDateTime" || col.javaType == "LocalDate">
  { prop: '${col.javaField}', label: '${col.columnComment}', width: 170 },
  <#elseif col.javaType == "Boolean">
  { prop: '${col.javaField}', label: '${col.columnComment}', width: 90, align: 'center' },
  <#else>
  { prop: '${col.javaField}', label: '${col.columnComment}', minWidth: 140 },
  </#if>
</#list>
];

// ============================================================
// 新增 / 编辑 对话框（含 ${subFunctionName}明细内嵌编辑，S51 / 2.4-F3）
// ============================================================
const dialogVisible = ref(false);
const confirmLoading = ref(false);
const formRef = ref<InstanceType<typeof YForm>>();
const formModel = reactive<Record<string, unknown>>({});
const isEdit = computed(() => !!formModel.id);
const subRows = ref<${subClassName}Item[]>([]);

const formSchemas = computed<YFormSchema[]>(() => [
<#list insertColumns as col>
  <#if col.fkTable?? && col.fkTable?has_content>
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'select', options: fkOptions.${col.javaField}<#if col.isRequired == 1>, emptyOption: false, rules: [{ required: true, message: '请选择${col.columnComment}', trigger: 'change' }]</#if> },
  <#elseif col.htmlType == "textarea">
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'textarea'<#if col.isRequired == 1>, rules: [{ required: true, message: '${col.columnComment}不能为空', trigger: 'blur' }]</#if> },
  <#elseif col.htmlType == "datetime">
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'date'<#if col.isRequired == 1>, rules: [{ required: true, message: '${col.columnComment}不能为空', trigger: 'blur' }]</#if> },
  <#elseif col.javaType == "Boolean">
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'switch' },
  <#else>
  { field: '${col.javaField}', label: '${col.columnComment}', component: 'input'<#if col.isRequired == 1>, rules: [{ required: true, message: '${col.columnComment}不能为空', trigger: 'blur' }]</#if> },
  </#if>
</#list>
]);

const title = computed(() => isEdit.value ? '编辑${functionName}' : '新增${functionName}');

// ============================================================
// 明细行操作
// ============================================================
function addSubRow(): void {
  subRows.value.push({});
}

function removeSubRow(index: number): void {
  subRows.value.splice(index, 1);
}

// ============================================================
// 操作函数
// ============================================================
function handleAdd(): void {
  Object.keys(formModel).forEach((k) => delete formModel[k]);
  subRows.value = [];
  dialogVisible.value = true;
}

async function handleEdit(row: ${className}VO): Promise<void> {
  const detail = await get${className}(row.id);
  Object.keys(formModel).forEach((k) => delete formModel[k]);
  Object.assign(formModel, { ...detail });
  // 明细全量替换语义：仅取可编辑字段回显（VO 中的 id/审计列不带回保存请求）
  subRows.value = (detail.items ?? []).map((i) => ({
<#list subInsertColumns as col>
    ${col.javaField}: i.${col.javaField},
</#list>
  }));
  dialogVisible.value = true;
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate()?.catch(() => false);
  if (!valid) return;
  confirmLoading.value = true;
  try {
    const body: ${className}SaveRequest = {
<#list insertColumns as col>
      ${col.javaField}: formModel.${col.javaField} as ${tsType(col.javaType)},
</#list>
      items: subRows.value,
    };
    if (isEdit.value) {
      body.id = formModel.id as number;
      await update${className}(body);
    } else {
      await create${className}(body);
    }
    ElMessage.success(isEdit.value ? '修改成功' : '新增成功');
    dialogVisible.value = false;
    await load();
  } finally {
    confirmLoading.value = false;
  }
}

async function handleDelete(row: ${className}VO): Promise<void> {
  await ElMessageBox.confirm('确认删除该${functionName}吗？${subFunctionName}明细将一并删除。', '提示', { type: 'warning' });
  await delete${className}(String(row.id));
  ElMessage.success('删除成功');
  await load();
}
</script>

<template>
  <div class="page-card">
    <div class="page-bar">
      <ElButton v-hasPermi="'${permPrefix}:add'" type="primary" :icon="Plus" @click="handleAdd">
        新增
      </ElButton>
    </div>

<#if queryColumns?size gt 0>
    <YSearchForm v-model="params" :schemas="searchSchemas" @search="search" @reset="reset" />
</#if>

    <YTable
      v-model:page-num="params.pageNum"
      v-model:page-size="params.pageSize"
      :loading="loading"
      :data="rows"
      :columns="columns"
      :total="total"
      row-key="id"
      @refresh="load"
    >
      <ElTableColumn label="操作" width="140" align="center" fixed="right">
        <template #default="{ row }">
          <ElButton v-hasPermi="'${permPrefix}:edit'" link type="primary" @click="handleEdit(row as ${className}VO)">
            编辑
          </ElButton>
          <ElButton v-hasPermi="'${permPrefix}:remove'" link type="danger" @click="handleDelete(row as ${className}VO)">
            删除
          </ElButton>
        </template>
      </ElTableColumn>
    </YTable>

    <YDialog
      v-model="dialogVisible"
      :title="title"
      width="960px"
      :confirm-loading="confirmLoading"
      @confirm="handleSubmit"
    >
      <YForm ref="formRef" v-model="formModel" :schemas="formSchemas" label-width="100px" />

      <div class="sub-section">
        <div class="sub-header">
          <span class="sub-title">${subFunctionName}明细</span>
          <ElButton size="small" type="primary" plain :icon="Plus" @click="addSubRow">
            添加行
          </ElButton>
        </div>
        <ElTable :data="subRows" size="small" border max-height="320">
<#list subInsertColumns as col>
          <ElTableColumn label="${col.columnComment}" min-width="140">
            <template #default="{ row }">
  <#if isNum(col.javaType)>
              <ElInputNumber v-model="row.${col.javaField}" :controls="false" style="width: 100%" />
  <#else>
              <ElInput v-model="row.${col.javaField}" placeholder="请输入${col.columnComment}" />
  </#if>
            </template>
          </ElTableColumn>
</#list>
          <ElTableColumn label="操作" width="70" align="center">
            <template #default="{ $index }">
              <ElButton link type="danger" @click="removeSubRow($index)">删行</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </div>
    </YDialog>
  </div>
</template>

<style scoped>
.page-bar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
.sub-section {
  margin-top: 16px;
}
.sub-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.sub-title {
  font-weight: 600;
}
</style>
