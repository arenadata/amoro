<!--
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
/ -->

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, shallowReactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import type { ColumnProps } from 'ant-design-vue/es/table'
import { usePagination } from '@/hooks/usePagination'
import type { BreadcrumbPartitionItem, IColumns, PartitionItem, PartitionSortField, SortOrder, TableSortOrder } from '@/types/common.type'
import { getPartitionFiles, getPartitionTable } from '@/services/table.service'
import { dateFormat } from '@/utils'

interface TableSorter {
  columnKey?: string | number
  order?: TableSortOrder
}

interface TableChangeExtra {
  action?: 'paginate' | 'sort' | 'filter'
}

interface TablePagination {
  current?: number
  pageSize?: number
}

const props = defineProps<{ hasPartition: boolean }>()
const hasBreadcrumb = ref<boolean>(false)
const { t } = useI18n()

const DEFAULT_SORT_BY: PartitionSortField = 'partition'
const DEFAULT_SORT_ORDER: SortOrder = 'desc'
const DEFAULT_PAGE = 1
const DEFAULT_PAGE_SIZE = 25

const sortBy = ref<PartitionSortField>(DEFAULT_SORT_BY)
const sortOrder = ref<SortOrder>(DEFAULT_SORT_ORDER)

const partitionSortFields: PartitionSortField[] = ['partition', 'specId', 'fileCount', 'fileSize', 'lastCommitTime']

function isPartitionSortField(value: unknown): value is PartitionSortField {
  return typeof value === 'string'
    && partitionSortFields.includes(value as PartitionSortField)
}

function getColumnSortOrder(field: PartitionSortField): TableSortOrder {
  if (sortBy.value !== field) {
    return null
  }

  return sortOrder.value === 'asc'
    ? 'ascend'
    : 'descend'
}

function getNextSortOrder(sorter: TableSorter, nextSortBy: PartitionSortField): SortOrder {
  if (sorter.order) {
    return sorter.order === 'ascend' ? 'asc' : 'desc'
  }

  if (sortBy.value === nextSortBy) {
    return sortOrder.value === 'asc' ? 'desc' : 'asc'
  }

  return DEFAULT_SORT_ORDER
}

const columns = computed<ColumnProps[]>(() => [
  {
    title: t('partition'),
    dataIndex: 'partition',
    key: 'partition',
    ellipsis: true,
    sorter: true,
    sortOrder: getColumnSortOrder('partition'),
  },
  {
    title: t('fileCount'),
    dataIndex: 'fileCount',
    key: 'fileCount',
    width: 120,
    ellipsis: true,
    sorter: true,
    sortOrder: getColumnSortOrder('fileCount'),
  },
  {
    title: t('size'),
    dataIndex: 'size',
    key: 'fileSize',
    width: 120,
    ellipsis: true,
    sorter: true,
    sortOrder: getColumnSortOrder('fileSize'),
  },
  {
    title: t('lastCommitTime'),
    dataIndex: 'lastCommitTime',
    key: 'lastCommitTime',
    width: 200,
    ellipsis: true,
    sorter: true,
    sortOrder: getColumnSortOrder('lastCommitTime'),
  },
])

const breadcrumbColumns: IColumns[] = shallowReactive([
  { title: t('file'), dataIndex: 'file', ellipsis: true },
  // { title: t('fsn'), dataIndex: 'fsn' },
  { title: t('fileType'), dataIndex: 'fileType', width: 120, ellipsis: true },
  { title: t('size'), dataIndex: 'size', width: 120, ellipsis: true },
  { title: t('commitTime'), dataIndex: 'commitTime', width: 200, ellipsis: true },
  { title: t('commitId'), dataIndex: 'commitId', width: 200, ellipsis: true },
  { title: t('path'), dataIndex: 'path', ellipsis: true, scopedSlots: { customRender: 'path' } },
])

const dataSource = reactive<PartitionItem[]>([])
const breadcrumbDataSource = reactive<BreadcrumbPartitionItem[]>([])
const partitionId = ref<string>('')
const specId = ref<number>(0)
const loading = ref<boolean>(false)
const pagination = reactive(usePagination())
const breadcrumbPagination = reactive(usePagination())
const route = useRoute()
const query = route.query

const sourceData = reactive({
  catalog: '',
  db: '',
  table: '',
  ...query,
})

const searchKey = ref<string>('')

async function handleSearch(val: string) {
  searchKey.value = val
  pagination.current = DEFAULT_PAGE
  await getTableInfo()
}

async function getTableInfo() {
  try {
    loading.value = true
    dataSource.length = 0

    const result = await getPartitionTable({
      ...sourceData,
      filter: searchKey.value,
      page: pagination.current,
      pageSize: pagination.pageSize,
      sortBy: sortBy.value,
      sortOrder: sortOrder.value,
    })

    const { list, total } = result
    pagination.total = total;
    (list || []).forEach((p: PartitionItem) => {
      p.lastCommitTime = p.lastCommitTime ? dateFormat(p.lastCommitTime) : ''
      dataSource.push(p)
    })
  }
  catch (error) {
  }
  finally {
    loading.value = false
  }
}

function change(
  { current = DEFAULT_PAGE, pageSize = DEFAULT_PAGE_SIZE }: TablePagination,
  _filters: unknown,
  sorter: TableSorter | TableSorter[],
  extra: TableChangeExtra,
) {
  if (!hasBreadcrumb.value && props.hasPartition) {
    if (extra.action === 'sort') {
      const currentSorter = Array.isArray(sorter) ? (sorter.find(item => item.order) || sorter[0]) : sorter
      if (currentSorter && isPartitionSortField(currentSorter.columnKey)) {
        sortBy.value = currentSorter.columnKey
        sortOrder.value = getNextSortOrder(currentSorter, currentSorter.columnKey)
      } else {
        sortBy.value = DEFAULT_SORT_BY
        sortOrder.value = DEFAULT_SORT_ORDER
      }
      pagination.current = DEFAULT_PAGE
    } else {
      pagination.current = current
      if (pageSize !== pagination.pageSize) {
        pagination.current = DEFAULT_PAGE
      }
      pagination.pageSize = pageSize
    }
  } else {
    breadcrumbPagination.current = current
    if (pageSize !== breadcrumbPagination.pageSize) {
      breadcrumbPagination.current = DEFAULT_PAGE
    }
    breadcrumbPagination.pageSize = pageSize
  }
  refresh()
}

function refresh() {
  if (!props.hasPartition) {
    getFiles()
    return
  }
  if (hasBreadcrumb.value) {
    getFiles()
  } else {
    getTableInfo()
  }
}

async function getFiles() {
  try {
    breadcrumbDataSource.length = 0
    loading.value = true
    const params = {
      ...sourceData,
      partition: props.hasPartition ? encodeURIComponent(partitionId.value) : null,
      specId: specId.value,
      page: breadcrumbPagination.current,
      pageSize: breadcrumbPagination.pageSize,
    }
    const result = await getPartitionFiles(params)
    const { list, total } = result
    breadcrumbPagination.total = total;
    (list || []).forEach((p: BreadcrumbPartitionItem) => {
      p.commitTime = p.commitTime && p.commitTime !== -1 ? dateFormat(p.commitTime) : ''
      breadcrumbDataSource.push(p)
    })
  }
  catch (error) {
  }
  finally {
    loading.value = false
  }
}

function toggleBreadcrumb(record: PartitionItem) {
  partitionId.value = record.partition
  specId.value = record.specId
  hasBreadcrumb.value = !hasBreadcrumb.value

  if (hasBreadcrumb.value) {
    breadcrumbPagination.current = DEFAULT_PAGE
    getFiles()
  }
}

onMounted(() => {
  hasBreadcrumb.value = false

  if (props.hasPartition) {
    getTableInfo()
  }
  else {
    getFiles()
  }
})
</script>

<template>
  <div class="table-partitons">
    <template v-if="!hasBreadcrumb && hasPartition">
      <div class="filter-wrap">
        <a-input-search
          v-model:value="searchKey"
          :placeholder="$t('fileSearchPlaceholder')"
          style="width: 350px"
          @search="(val: string) => handleSearch(val)"
        >
          <template #prefix>
            <SearchOutlined />
          </template>
          <template v-if="searchKey" #suffix>
            <CloseCircleOutlined class="input-clear-icon" @click="handleSearch('')" />
          </template>
        </a-input-search>
      </div>

      <a-table
        row-key="partition"
        :columns="columns"
        :data-source="dataSource"
        :pagination="pagination"
        :loading="loading"
        @change="change"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'partition'">
            <a-button type="link" @click="toggleBreadcrumb(record)">
              {{ record.partition }}
            </a-button>
          </template>
        </template>
      </a-table>
    </template>

    <template v-else>
      <a-breadcrumb v-if="hasPartition" separator=">">
        <a-breadcrumb-item class="text-active" @click="toggleBreadcrumb">
          All
        </a-breadcrumb-item>
        <a-breadcrumb-item>{{ `${$t('partition')} ${partitionId}` }}</a-breadcrumb-item>
      </a-breadcrumb>

      <a-table
        row-key="file"
        :columns="breadcrumbColumns"
        :data-source="breadcrumbDataSource"
        :pagination="breadcrumbPagination"
        :loading="loading"
        @change="change"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.dataIndex === 'file'">
            <a-tooltip>
              <template #title>
                {{ record.file }}
              </template>
              <span>{{ record.file }}</span>
            </a-tooltip>
          </template>

          <template v-if="column.dataIndex === 'path'">
            <a-tooltip>
              <template #title>
                {{ record.path }}
              </template>
              <span>{{ record.path }}</span>
            </a-tooltip>
          </template>
        </template>
      </a-table>
    </template>
  </div>
</template>

<style lang="less" scoped>
.table-partitons {
  padding: 18px 0;

  .text-active {
    color: #1890ff;
    cursor: pointer;
  }

  .filter-wrap {
    margin-bottom: 12px;

    .input-clear-icon {
      font-size: 12px;
    }
  }

  :deep(.ant-input-group-addon) {
    display: none;
  }
}
</style>
