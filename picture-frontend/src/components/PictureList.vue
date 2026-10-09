<template>
  <div class="picture-list">
    <!-- 图片列表 -->
    <a-list
      :grid="{ gutter: 16, xs: 1, sm: 2, md: 3, lg: 4, xl: 5, xxl: 6 }"
      :data-source="dataList"
      :loading="loading"
    >
      <template #renderItem="{ item: picture }">
        <a-list-item style="padding: 0">
          <!-- 单张图片 -->
          <a-card
            hoverable
            :class="{ 'picture-card--selected': selectable && isSelected(picture) }"
            @click="onCardClick(picture)"
          >
            <template #cover>
              <div class="picture-cover">
                <img
                  style="height: 180px; object-fit: cover; width: 100%"
                  :alt="picture.name"
                  :src="picture.thumbnailUrl ?? picture.url"
                  loading="lazy"
                />
                <a-checkbox
                  v-if="selectable"
                  class="picture-cover__checkbox"
                  :checked="isSelected(picture)"
                  @click.stop="toggleSelect(picture)"
                />
              </div>
            </template>
            <a-card-meta :title="picture.name">
              <template #description>
                <PictureTags :category="picture.category" :tags="picture.tags" :max="3" />
              </template>
            </a-card-meta>
          </a-card>
        </a-list-item>
      </template>
    </a-list>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import PictureTags from './PictureTags.vue'

interface Props {
  dataList?: API.PictureVO[]
  loading?: boolean
  /** 是否开启多选模式。默认关闭，关闭时点击卡片跳转详情页 */
  selectable?: boolean
  /** 已选中的图片 id 列表，配合 selectable 使用（可 v-model:selectedIds） */
  selectedIds?: number[]
}

const props = withDefaults(defineProps<Props>(), {
  dataList: () => [],
  loading: false,
  selectable: false,
  selectedIds: () => [],
})

const emit = defineEmits<{
  (e: 'update:selectedIds', ids: number[]): void
}>()

// 判断某张图片是否已选中
const isSelected = (picture: API.PictureVO) => {
  return picture.id != null && props.selectedIds.includes(picture.id)
}

// 切换单张图片的选中状态
const toggleSelect = (picture: API.PictureVO) => {
  const id = picture.id
  if (id == null) {
    return
  }
  const nextIds = isSelected(picture)
    ? props.selectedIds.filter((selectedId) => selectedId !== id)
    : [...props.selectedIds, id]
  emit('update:selectedIds', nextIds)
}

// 跳转至图片详情
const router = useRouter()
const doClickPicture = (picture: API.PictureVO) => {
  router.push({
    path: `/picture/${picture.id}`,
  })
}

// 多选模式下点击卡片用于切换选中，否则跳转详情
// 说明：仅当「已选中至少一张」时才把点击视为选择操作，
// 这样未开始多选时点击卡片仍然可以正常进入图片详情页
const onCardClick = (picture: API.PictureVO) => {
  if (props.selectable && props.selectedIds.length > 0) {
    toggleSelect(picture)
    return
  }
  doClickPicture(picture)
}
</script>

<style scoped>
.picture-cover {
  position: relative;
}

.picture-cover__checkbox {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 2px 6px;
  background: rgba(255, 255, 255, 0.85);
  border-radius: 4px;
}

.picture-card--selected {
  border: 2px solid #1677ff;
}
</style>
