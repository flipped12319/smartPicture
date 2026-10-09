<template>
  <div v-if="hasContent" class="picture-tags">
    <a-tag v-if="useCategory" color="green" class="pic-tag pic-tag--category">
      {{ displayCategory }}
    </a-tag>
    <a-tag v-for="tag in visibleTags" :key="tag" class="pic-tag" :title="tag">
      {{ tag }}
    </a-tag>
    <a-tooltip v-if="hiddenTags.length" :title="hiddenTags.join('、')">
      <a-tag class="pic-tag pic-tag--more">+{{ hiddenTags.length }}</a-tag>
    </a-tooltip>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  /**
   * 标签列表，两种形态都支持：
   * - PictureVO 里是 string[]（首页、空间列表）
   * - Picture 实体里是 JSON 数组字符串（管理页）
   */
  tags?: string | string[]
  /** 分类。传入时展示在最前面，留空则显示「默认」；完全不传则不展示分类 */
  category?: string
  /** 最多展示几个标签，0 表示全部展示 */
  max?: number
}

const props = withDefaults(defineProps<Props>(), {
  max: 3,
})

/** 把两种形态的 tags 统一归一化成字符串数组 */
const tagList = computed<string[]>(() => {
  const raw = props.tags
  if (!raw) {
    return []
  }
  if (Array.isArray(raw)) {
    return raw.map((tag) => String(tag).trim()).filter(Boolean)
  }
  const text = raw.trim()
  if (!text) {
    return []
  }
  // 后端实体里的 tags 存的是 JSON 数组字符串，直接遍历会被逐字符拆开
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) {
      return parsed.map((tag) => String(tag).trim()).filter(Boolean)
    }
  } catch {
    // 不是合法 JSON，走下面的分隔符兜底
  }
  return text
    .replace(/^[[\]]+/g, '')
    .split(/[,，、]/)
    .map((tag) => tag.replace(/^["'\s]+|["'\s]+$/g, ''))
    .filter(Boolean)
})

/** 传了 category 才展示分类标签，保持和原有「默认」兜底一致 */
const useCategory = computed(() => props.category !== undefined && props.category !== null)

const displayCategory = computed(() => props.category?.trim() || '默认')

const visibleTags = computed(() =>
  props.max > 0 ? tagList.value.slice(0, props.max) : tagList.value,
)

const hiddenTags = computed(() => (props.max > 0 ? tagList.value.slice(props.max) : []))

const hasContent = computed(() => useCategory.value || tagList.value.length > 0)
</script>

<style scoped>
.picture-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}

/* 统一压紧：去掉 antd 标签自带的右间距，间距改由 flex gap 控制 */
.picture-tags :deep(.ant-tag) {
  margin: 0;
  padding: 0 6px;
  height: 20px;
  line-height: 18px;
  font-size: 12px;
  border-radius: 4px;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pic-tag--category {
  font-weight: 500;
}

.pic-tag--more {
  color: rgba(0, 0, 0, 0.45);
  background: rgba(0, 0, 0, 0.04);
  border-color: transparent;
  cursor: default;
}
</style>
