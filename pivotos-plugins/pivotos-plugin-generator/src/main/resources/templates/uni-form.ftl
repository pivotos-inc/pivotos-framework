<#-- =====================================================
 uni-app 表单页模板（新增 / 编辑）
 输出: pivotos-app/src/pages-gen/{moduleName}/{businessName}/form.vue
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
import { create${className}, update${className}, get${className}, type ${className}SaveRequest } from '@/api/${moduleName}/${businessName}';

// ========== 表单数据 ==========
const form = reactive<${className}SaveRequest>({
<#list insertColumns as col>
  ${col.javaField}: ${tsDefault(col.javaType)},
</#list>
});

const loading = ref(false);
const isEdit = ref(false);
const pageId = ref<number>();

const title = computed(() => isEdit.value ? '编辑${functionName}' : '新增${functionName}');

// ========== 加载编辑数据 ==========
onLoad((query) => {
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
        <#if col.htmlType == "textarea">
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

.submit-btn {
  margin-top: 48rpx;
  padding: 0 16rpx;
}
</style>
