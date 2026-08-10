<#-- =====================================================
 uni-app 详情页模板
 输出: pivotos-app/src/pages-gen/{moduleName}/{businessName}/detail.vue
===================================================== -->
<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { delete${className}, get${className}, type ${className}VO } from '@/api/${moduleName}/${businessName}';


const detail = ref<${className}VO>();
const loading = ref(false);
const pageId = ref<number>();

onLoad((query) => {
  const id = Number(query?.id);
  if (id) {
    pageId.value = id;
    loadDetail(id);
  }
});

async function loadDetail(id: number) {
  loading.value = true;
  try {
    detail.value = await get${className}(id);
  } catch {
    uni.showToast({ title: '加载失败', icon: 'none' });
  } finally {
    loading.value = false;
  }
}

function toEdit() {
  uni.navigateTo({ url: '/pages-gen/${moduleName}/${businessName}/form?id=' + pageId.value });
}

async function onDelete() {
  const res = await uni.showModal({
    title: '提示',
    content: '确认删除该${functionName}吗？',
  });
  if (!res.confirm) return;
  loading.value = true;
  try {
    await delete${className}(pageId.value!);
    uni.showToast({ title: '删除成功', icon: 'success' });
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
    <wd-navbar title="${functionName}详情" left-arrow @left-click="uni.navigateBack()" />

    <wd-status-tip v-if="loading" image="search" tip="加载中..." />
    <wd-status-tip v-else-if="!detail" image="content" tip="数据不存在" />

    <view v-else class="detail-container">
      <wd-cell-group border>
      <#list listColumns as col>
        <wd-cell title="${col.columnComment}" :value="detail.${col.javaField}" />
      </#list>
      </wd-cell-group>

      <view class="action-bar">
        <wd-button
          type="primary"
          block
          @click="toEdit"
        >
          编辑
        </wd-button>
        <wd-button
          type="danger"
          block
          :loading="loading"
          @click="onDelete"
        >
          删除
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

.detail-container {
  padding: 24rpx;
}

.action-bar {
  margin-top: 48rpx;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
  padding: 0 16rpx;
}
</style>
