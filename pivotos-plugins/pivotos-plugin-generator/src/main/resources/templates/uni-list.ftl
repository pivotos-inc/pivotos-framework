<#-- =====================================================
 uni-app 列表页模板
 输出: pivotos-app/src/pages-gen/{moduleName}/{businessName}/list.vue
 功能: 搜索 + 列表 + 下拉刷新 + 上拉加载更多
===================================================== -->
<#function tsDefault javaType>
  <#if javaType == "String"><#return "''"><#elseif javaType == "Integer" || javaType == "Long" || javaType == "BigDecimal" || javaType == "Float" || javaType == "Double"><#return "undefined"><#elseif javaType == "Boolean"><#return "false"><#elseif javaType == "LocalDateTime" || javaType == "LocalDate" || javaType == "LocalTime"><#return "''"><#else><#return "''"></#if>
</#function>
<script setup lang="ts">
import { onShow, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app';
import { ref, reactive, computed } from 'vue';
import { select${className}Page, type ${className}VO, type ${className}Query } from '@/api/${moduleName}/${businessName}';
import { useUserStore } from '@/store/user';

const hasPerm = (perm: string) => useUserStore().hasPermission(perm);

// ========== 搜索条件 ==========
const query = reactive<${className}Query>({
<#list queryColumns as col>
  ${col.javaField}: ${tsDefault(col.javaType)},
</#list>
  pageNum: 1,
  pageSize: 10,
});

// ========== 数据状态 ==========
const list = ref<${className}VO[]>([]);
const loading = ref(false);
const finished = ref(false);
const total = ref(0);

// ========== 加载数据 ==========
async function loadData(reset = false) {
  if (loading.value) return;
  if (!reset && finished.value) return;
  if (reset) {
    query.pageNum = 1;
    finished.value = false;
  }
  loading.value = true;
  try {
    const res = await select${className}Page({ ...query });
    if (reset) {
      list.value = res.list || [];
    } else {
      list.value = [...list.value, ...(res.list || [])];
    }
    total.value = res.total || 0;
    if (list.value.length >= total.value) {
      finished.value = true;
    }
    query.pageNum++;
  } catch {
    // 接口异常已在拦截器统一提示
  } finally {
    loading.value = false;
    uni.stopPullDownRefresh();
  }
}

<#if queryColumns?size gt 0>
// ========== 搜索 ==========
function onSearch() {
  loadData(true);
}
</#if>

// ========== 页面跳转 ==========
function toAdd() {
  uni.navigateTo({ url: '/pages-gen/${moduleName}/${businessName}/form' });
}

function toDetail(id: number) {
  uni.navigateTo({ url: '/pages-gen/${moduleName}/${businessName}/detail?id=' + id });
}

function toEdit(id: number) {
  uni.navigateTo({ url: '/pages-gen/${moduleName}/${businessName}/form?id=' + id });
}

// ========== 生命周期 ==========
onShow(() => {
  loadData(true);
});

onPullDownRefresh(() => {
  loadData(true);
});

onReachBottom(() => {
  loadData();
});
</script>

<template>
  <view class="page">
    <#if queryColumns?size gt 0>
    <!-- 搜索栏 -->
    <view class="search-bar">
    <#list queryColumns as col>
      <wd-input
        v-model="query.${col.javaField}"
        placeholder="<#if col.queryType == "LIKE">按${col.columnComment}模糊查询<#else>请输入${col.columnComment}</#if>"
        clearable
        custom-class="search-input"
        @confirm="onSearch"
      />
    </#list>
      <wd-button type="primary" size="small" @click="onSearch">搜索</wd-button>
    </view>
    </#if>

    <!-- 列表 -->
    <view class="list">
      <view
        v-for="item in list"
        :key="item.id"
        class="list-item"
        @click="toDetail(item.id)"
      >
        <view class="item-content">
        <#list listColumns as col>
          <view class="item-row">
            <text class="item-label">${col.columnComment}</text>
            <text class="item-value">{{ item.${col.javaField} }}</text>
          </view>
        </#list>
        </view>
        <view class="item-actions">
          <wd-button v-if="hasPerm('${permPrefix}:edit')" type="primary" size="small" @click.stop="toEdit(item.id)">
            编辑
          </wd-button>
        </view>
      </view>
    </view>

    <!-- 加载更多 -->
    <wd-loadmore :state="loading ? 'loading' : finished ? 'finished' : 'idle'" />

    <!-- 空状态 -->
    <wd-status-tip v-if="!loading && list.length === 0" image="content" tip="暂无${functionName}数据" />

    <!-- 新增按钮 -->
    <view v-if="hasPerm('${permPrefix}:add')" class="fab" @click="toAdd">
      <wd-icon name="add" size="28px" color="#fff" />
    </view>
  </view>
</template>

<style scoped lang="scss">
.page {
  min-height: 100vh;
  background-color: #f5f5f5;
  padding-bottom: 120rpx;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx;
  background-color: #fff;
  margin-bottom: 16rpx;

  .search-input {
    flex: 1;
  }
}

.list {
  padding: 0 20rpx;
}

.list-item {
  background-color: #fff;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.item-content {
  flex: 1;
  margin-right: 16rpx;
}

.item-row {
  display: flex;
  margin-bottom: 8rpx;

  .item-label {
    color: #999;
    font-size: 26rpx;
    width: 160rpx;
    flex-shrink: 0;
  }

  .item-value {
    color: #333;
    font-size: 28rpx;
    flex: 1;
  }
}

.item-actions {
  flex-shrink: 0;
}

.fab {
  position: fixed;
  right: 40rpx;
  bottom: 120rpx;
  width: 100rpx;
  height: 100rpx;
  border-radius: 50%;
  background-color: #4d80f0;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4rpx 16rpx rgba(77, 128, 240, 0.4);
}
</style>
