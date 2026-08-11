<#-- =====================================================
 PC 端 Vue 页面模板（树表版，S53 / 2.4-F4）
 YTable 树模式（row-key + children + default-expand-all）+ ElTreeSelect 父节点选择
 不走 useTablePage 分页 hook（对齐部门/菜单页先例）
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
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElButton, ElInput, ElMessage, ElMessageBox, ElTableColumn, ElTreeSelect } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { YDialog, YForm, YTable } from '@pivotos/ui';
import type { YFormSchema, YTableColumn } from '@pivotos/ui';
import {
  create${className},
  delete${className},
  get${className},
<#if hasFk>
  get${className}FkOptions,
</#if>
  select${className}TreeList,
  update${className},
} from '@/api/${moduleName}/${businessName}';
import type { ${className}VO, ${className}SaveRequest<#if hasFk>, ${className}FkOption</#if> } from '@/api/${moduleName}/${businessName}';

// ============================================================
// 树数据（全量加载，千行内；S53 / 2.4-F4）
// ============================================================
const loading = ref(false);
const tree = ref<${className}VO[]>([]);
const keyword = ref('');

async function load(): Promise<void> {
  loading.value = true;
  try {
    tree.value = await select${className}TreeList();
  } finally {
    loading.value = false;
  }
}

onMounted(load);

// 按「${treeNameField}」前端模糊过滤（保留命中节点的祖先链，对齐部门页先例）
const filteredTree = computed<${className}VO[]>(() => {
  const kw = keyword.value.trim();
  if (!kw) return tree.value;
  const walk = (nodes: ${className}VO[]): ${className}VO[] =>
    nodes
      .map((n) => ({ ...n, children: n.children ? walk(n.children) : undefined }))
      .filter((n) => String(n.${treeNameField} ?? '').includes(kw) || (n.children && n.children.length > 0));
  return walk(tree.value);
});

<#if hasFk>
// ============================================================
// fk 关联下拉选项（S50 / 2.4-F2）
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
// 表格列（首列固定为树名称列）
// ============================================================
const columns: YTableColumn<${className}VO>[] = [
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
// 新增 / 编辑 对话框（父节点 ElTreeSelect）
// ============================================================
const dialogVisible = ref(false);
const confirmLoading = ref(false);
const formRef = ref<InstanceType<typeof YForm>>();
const formModel = reactive<Record<string, unknown>>({});
const isEdit = computed(() => !!formModel.id);

const formSchemas = computed<YFormSchema[]>(() => [
<#list insertColumns as col>
  <#if col.javaField == treeParentField>
  <#-- 父节点列由模板下方 ElTreeSelect 单独渲染，不进 YForm schema -->
  <#elseif col.fkTable?? && col.fkTable?has_content>
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

// 父节点下拉树：编辑时剔除自身（防环底线，后端另有 parentId=自身校验）
const parentTreeData = computed<${className}VO[]>(() => {
  if (!isEdit.value) return tree.value;
  const selfId = formModel.id as number;
  const prune = (nodes: ${className}VO[]): ${className}VO[] =>
    nodes
      .filter((n) => n.${treeCodeField} !== selfId)
      .map((n) => ({ ...n, children: n.children ? prune(n.children) : undefined }));
  return prune(tree.value);
});

const title = computed(() => isEdit.value ? '编辑${functionName}' : '新增${functionName}');

// ============================================================
// 操作函数
// ============================================================
function handleAdd(): void {
  Object.keys(formModel).forEach((k) => delete formModel[k]);
  // 父节点默认留空（显示占位「不选则为根节点」），提交时兜底 0=根（S53 实测：默认 0 会让 TreeSelect 显示原始值"0"）
  dialogVisible.value = true;
}

async function handleEdit(row: ${className}VO): Promise<void> {
  const detail = await get${className}(row.id);
  Object.keys(formModel).forEach((k) => delete formModel[k]);
  Object.assign(formModel, { ...detail });
  dialogVisible.value = true;
}

async function handleSubmit(): Promise<void> {
  const valid = await formRef.value?.validate()?.catch(() => false);
  if (!valid) return;
  confirmLoading.value = true;
  try {
    const body: ${className}SaveRequest = {
<#list insertColumns as col>
<#if col.javaField == treeParentField>
      ${col.javaField}: (formModel.${col.javaField} ?? 0) as ${tsType(col.javaType)},
<#else>
      ${col.javaField}: formModel.${col.javaField} as ${tsType(col.javaType)},
</#if>
</#list>
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
  try {
    await ElMessageBox.confirm('确认删除该${functionName}吗？（存在子节点将被拦截）', '提示', { type: 'warning' });
    await delete${className}(String(row.id));
    ElMessage.success('删除成功');
    await load();
  } catch {
    // 取消确认或后端拦截（如：存在子节点，不允许删除）——拦截文案由请求层 toast 透出，无需重复提示
  }
}
</script>

<template>
  <div class="page-card">
    <div class="page-bar">
      <ElInput
        v-model="keyword"
        class="tree-search"
        placeholder="按${functionName}名称模糊过滤"
        clearable
      />
      <ElButton v-hasPermi="'${permPrefix}:add'" type="primary" :icon="Plus" @click="handleAdd">
        新增
      </ElButton>
    </div>

    <YTable
      :loading="loading"
      :data="filteredTree"
      :columns="columns"
      row-key="${treeCodeField}"
      default-expand-all
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
      width="560px"
      :confirm-loading="confirmLoading"
      @confirm="handleSubmit"
    >
      <div class="parent-field">
        <span class="parent-field__label">父节点</span>
        <ElTreeSelect
          :model-value="formModel.${treeParentField} as number | undefined"
          :data="parentTreeData"
          :props="{ label: '${treeNameField}', children: 'children' }"
          node-key="${treeCodeField}"
          check-strictly
          :render-after-expand="false"
          default-expand-all
          placeholder="不选则为根节点"
          clearable
          style="flex: 1"
          @update:model-value="(v: string | number | undefined) => { formModel.${treeParentField} = v; }"
        />
      </div>
      <YForm ref="formRef" v-model="formModel" :schemas="formSchemas" label-width="100px" />
    </YDialog>
  </div>
</template>

<style scoped>
.page-bar {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
}
.tree-search {
  width: 260px;
}
.parent-field {
  display: flex;
  align-items: center;
  margin-bottom: 18px;
  padding-left: 12px;
}
.parent-field__label {
  width: 100px;
  font-size: 14px;
  color: #606266;
}
</style>
