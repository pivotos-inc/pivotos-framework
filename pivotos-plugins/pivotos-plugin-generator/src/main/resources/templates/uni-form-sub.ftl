<#-- =====================================================
 uni-app 表单页模板（主子表版，S51 / 2.4-F3）
 输出: pivotos-app/src/pages-gen/{moduleName}/{businessName}/form.vue
 主字段区 + ${subFunctionName}明细卡片区（全量替换语义）
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
import { onLoad } from '@dcloudio/uni-app';
import { ref, reactive, computed } from 'vue';
import { create${className}, update${className}, get${className}<#if hasFk>, get${className}FkOptions, type ${className}FkOption</#if>, type ${className}SaveRequest, type ${subClassName}Item } from '@/api/${moduleName}/${businessName}';

// ========== 表单数据 ==========
const form = reactive<${className}SaveRequest>({
<#list insertColumns as col>
  ${col.javaField}: ${tsDefault(col.javaType)},
</#list>
});

// ========== ${subFunctionName}明细（S51 / 2.4-F3） ==========
const items = ref<${subClassName}Item[]>([]);

function addItem() {
  items.value.push({});
}

function removeItem(index: number) {
  items.value.splice(index, 1);
}
<#if hasFk>

// ========== fk 关联下拉选项（S50 / 2.4-F2） ==========
<#list fkColumns as col>
const ${col.javaField}Options = ref<${className}FkOption[]>([]);
</#list>

async function loadFkOptions() {
<#list fkColumns as col>
  ${col.javaField}Options.value = await get${className}FkOptions('${col.javaField}').catch(() => []);
</#list>
}
</#if>

const loading = ref(false);
const isEdit = ref(false);
const pageId = ref<number>();

const title = computed(() => isEdit.value ? '编辑${functionName}' : '新增${functionName}');

// ========== 加载编辑数据 ==========
onLoad((query) => {
<#if hasFk>
  loadFkOptions();
</#if>
  const id = Number(query?.id);
  if (id) {
    isEdit.value = true;
    pageId.value = id;
    loadDetail(id);
  }
});

async function loadDetail(id: number) {
  loading.value = true;
  try {
    const detail = await get${className}(id);
    Object.assign(form, detail);
    // 明细全量替换语义：仅取可编辑字段回显（VO 中的 id/审计列不带回保存请求）
    items.value = (detail.items ?? []).map((i) => ({
<#list subInsertColumns as col>
      ${col.javaField}: i.${col.javaField},
</#list>
    }));
  } catch {
    uni.showToast({ title: '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

// ========== 提交 ==========
async function onSubmit() {
  <#list insertColumns as col>
  <#if col.isRequired == 1>
  if (!form.${col.javaField}<#if col.javaType == "String" || col.javaType == "LocalDateTime" || col.javaType == "LocalDate" || col.javaType == "LocalTime">.trim()</#if>) {
    uni.showToast({ title: '${col.columnComment}不能为空', icon: 'none' });
    return;
  }
  </#if>
  </#list>

  loading.value = true;
  try {
    form.items = items.value;
    if (isEdit.value) {
      form.id = pageId.value;
      await update${className}(form);
    } else {
      await create${className}(form);
    }
    uni.showToast({ title: isEdit.value ? '修改成功' : '新增成功', icon: 'success' });
    setTimeout(() => {
      uni.navigateBack();
    }, 1200);
  } catch {
    // 接口异常已在拦截器统一提示
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <view class="page">
    <wd-navbar :title="title" left-arrow @left-click="uni.navigateBack()" />

    <view class="form-container">
      <wd-cell-group>
      <#list insertColumns as col>
        <#if col.fkTable?? && col.fkTable?has_content>
        <wd-picker
          v-model="form.${col.javaField}"
          label="${col.columnComment}"
          :columns="${col.javaField}Options"
          title="请选择${col.columnComment}"
          placeholder="请选择${col.columnComment}"
        />
        <#elseif col.htmlType == "textarea">
        <wd-textarea
          v-model="form.${col.javaField}"
          label="${col.columnComment}"
          placeholder="请输入${col.columnComment}"
          :maxlength="500"
          :show-word-limit="false"
          clearable
        />
        <#elseif col.javaType == "Boolean">
        <wd-cell title="${col.columnComment}" center>
          <wd-switch v-model="form.${col.javaField}" />
        </wd-cell>
        <#else>
        <wd-input
          v-model="form.${col.javaField}"
          label="${col.columnComment}"
          placeholder="请输入${col.columnComment}"
          clearable
        />
        </#if>
      </#list>
      </wd-cell-group>

      <!-- ${subFunctionName}明细 -->
      <view class="sub-section">
        <view class="sub-header">
          <text class="sub-title">${subFunctionName}明细</text>
          <wd-button size="small" type="primary" plain @click="addItem">添加明细</wd-button>
        </view>
        <wd-cell-group v-for="(item, idx) in items" :key="idx" class="sub-card">
          <view class="sub-card-bar">
            <text class="sub-card-title">明细 {{ idx + 1 }}</text>
            <text class="sub-card-del" @click="removeItem(idx)">删除</text>
          </view>
        <#list subInsertColumns as col>
          <wd-input
            v-model="item.${col.javaField}"
            label="${col.columnComment}"
            placeholder="请输入${col.columnComment}"
            <#if col.javaType == "Integer" || col.javaType == "Long" || col.javaType == "BigDecimal" || col.javaType == "Float" || col.javaType == "Double">type="number"
            </#if>clearable
          />
        </#list>
        </wd-cell-group>
        <view v-if="items.length === 0" class="sub-empty">暂无明细，点击「添加明细」新增一行</view>
      </view>

      <view class="submit-btn">
        <wd-button type="primary" block :loading="loading" @click="onSubmit">
          {{ isEdit ? '保存修改' : '立即创建' }}
        </wd-button>
      </view>
    </view>
  </view>
</template>

<style scoped lang="scss">
.page {
  min-height: 100vh;
  background-color: #f5f5f5;
}

.form-container {
  padding: 24rpx;
}

.sub-section {
  margin-top: 24rpx;
}

.sub-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8rpx 16rpx 16rpx;
}

.sub-title {
  font-size: 30rpx;
  font-weight: 600;
}

.sub-card {
  margin-bottom: 16rpx;
}

.sub-card-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16rpx 24rpx 0;
}

.sub-card-title {
  font-size: 26rpx;
  color: #666;
}

.sub-card-del {
  font-size: 26rpx;
  color: #f56c6c;
}

.sub-empty {
  padding: 32rpx 0;
  text-align: center;
  font-size: 26rpx;
  color: #999;
}

.submit-btn {
  margin-top: 48rpx;
  padding: 0 16rpx;
}
</style>
