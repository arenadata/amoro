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
<script lang="ts">
import {
  computed,
  defineComponent,
  onBeforeMount,
  onBeforeUnmount,
  reactive,
  ref,
  toRefs,
} from 'vue'
import { useRoute, useRouter } from 'vue-router'
import CreateDBModal from '@/components/tables-sub-menu/CreateDB.vue'
import useStore from '@/store'
import { getCatalogList, getDatabaseList, getTableList } from '@/services/table.service.ts'
import type { ICatalogItem, ILableAndValue, IMap } from '@/types/common.type.ts'
import { debounce } from '@/utils'
import { usePlaceholder } from '@/hooks/usePlaceholder.ts'
import virtualRecycleScroller from '@/components/VirtualRecycleScroller.vue'
import { usePagination } from '@/hooks/usePagination.ts'

interface IDatabaseItem {
  id: string
  label: string
}
interface ITableItem {
  name: string
  type: string
}

export default defineComponent({
  name: 'TablesList',
  components: {
    CreateDBModal,
    VirtualRecycleScroller: virtualRecycleScroller,
  },
  emits: ['goCreatePage'],
  setup(_, { emit }) {
    const router = useRouter()
    const pagination = reactive(usePagination())
    const route = useRoute()
    const store = useStore()
    const state = reactive({
      catalogLoading: false as boolean,
      DBSearchInput: '' as string,
      tableSearchInput: '' as string,
      curCatalog: '',
      database: '',
      tableName: '',
      type: '',
      catalogOptions: [] as ILableAndValue[],
      showCreateDBModal: false,
      loading: false,
      tableLoading: false,
      databaseList: [] as IMap<string>[],
      tableList: [] as IMap<string>[],
      allDatabaseListLoaded: [] as IMap<string>[],
      allTableListLoaded: [] as IMap<string>[],
    })
    const storageTableKey = 'easylake-menu-catalog-db-table'
    const tablesTableColumnKey = 'tableName'
    const storageCataDBTable = JSON.parse(localStorage.getItem(storageTableKey) || '{}')
    const tablesWrapperElRef = ref()
    const tablesWrapperElClientHeight = ref()

    const filteredDatabases = computed(() => {
      if (!state.allDatabaseListLoaded) {
        return []
      }
      return state.allDatabaseListLoaded.filter((ele) => {
        return ele.label.includes(state.DBSearchInput)
      })
    })

    const filteredTables = computed(() => {
      if (!state.allTableListLoaded) {
        return []
      }
      return state.allTableListLoaded.filter((ele) => {
        return ele.label.includes(state.tableSearchInput)
      })
    })

    // get tables table element height with fixed table pagination height (56px)
    // set tablesWrapperElClientHeight private ref
    const tablesTableElScrollY = computed({
      get() { return (tablesWrapperElClientHeight.value || tablesWrapperElRef?.value?.clientHeight) - 56 },
      set(value) {
        tablesWrapperElClientHeight.value = value
      },
    })

    const placeholder = reactive(usePlaceholder())

    function handleSearch(type: string) {
      type === 'table' ? getSearchTableList() : getSearchDBList()
    }

    function clearSearch(type: string) {
      if (type === 'table') {
        state.tableSearchInput = ''
        getSearchTableList()
      }
      else {
        state.DBSearchInput = ''
        getSearchDBList()
      }
    }

    function getSearchTableList() {
      debounce(() => {
        getAllTableList()
      })()
    }

    function getSearchDBList() {
      debounce(() => {
        getAllDatabaseList(true)
      })()
    }

    function handleChangeTable({ current = pagination.current, pageSize = pagination.pageSize }) {
      pagination.current = current
      const resetPage = pageSize !== pagination.pageSize
      pagination.pageSize = pageSize

      if (resetPage) {
        pagination.current = 1
      }
    }

    function handleClickDb(item: IDatabaseItem) {
      if (state.database === item.id) {
        return
      }
      state.database = item.id
      state.tableName = ''
      state.allTableListLoaded.length = 0
      getAllTableList()
    }

    function getPopupContainer(triggerNode: Element) {
      return triggerNode.parentNode
    }

    function catalogChange(value: string) {
      state.curCatalog = value
      state.databaseList.length = 0
      state.tableList.length = 0
      state.allDatabaseListLoaded.length = 0
      state.allTableListLoaded.length = 0
      getAllDatabaseList()
    }

    function addDatabase() {
      state.showCreateDBModal = true
    }

    function cancel() {
      state.showCreateDBModal = false
    }

    function createTable() {
      emit('goCreatePage')
    }

    function handleClickTable(item: IMap<string>) {
      state.tableName = item.label
      state.type = item.type
      localStorage.setItem(storageTableKey, JSON.stringify({
        catalog: state.curCatalog,
        database: state.database,
        tableName: item.label,
      }))
      store.updateTablesMenu(false)
      const path = item.type === 'HIVE' ? '/hive-tables' : '/tables'
      const pathQuery = {
        path,
        query: {
          catalog: state.curCatalog,
          db: state.database,
          table: state.tableName,
          type: state.type,
        },
      }
      if (route.path.includes('tables')) {
        router.push(pathQuery)
        return
      }
      router.push(pathQuery)
    }

    function getCatalogOps() {
      state.catalogLoading = true
      getCatalogList().then((res: ICatalogItem[]) => {
        if (!res) {
          return
        }
        state.catalogOptions = (res || []).map((ele: ICatalogItem) => ({
          value: ele.catalogName,
          label: ele.catalogName,
        }))
        if (state.catalogOptions.length) {
          const index = state.catalogOptions.findIndex(ele => ele.value === storageCataDBTable.catalog)
          const query = route.query
          state.curCatalog = index > -1 ? storageCataDBTable.catalog : (query?.catalog)?.toString() || state.catalogOptions[0].value
        }
        getAllDatabaseList()
      }).finally(() => {
        state.catalogLoading = false
      })
    }

    function getAllDatabaseList(isSearch = false) {
      if (!state.curCatalog) {
        return
      }
      if (state.allDatabaseListLoaded.length) {
        state.databaseList = filteredDatabases.value
        return
      }

      state.loading = true
      getDatabaseList({
        catalog: state.curCatalog,
        keywords: state.DBSearchInput,
      }).then((res: string[]) => {
        state.databaseList = (res || []).map((ele: string) => ({
          id: ele,
          label: ele,
        }))
        if (!isSearch) {
          state.allDatabaseListLoaded = [...state.databaseList]
          if (state.databaseList.length) {
            const index = state.databaseList.findIndex(ele => ele.id === storageCataDBTable.database)
            // ISSUE 2413: If the current catalog is not the one in the query, the first db is selected by default.
            state.database = index > -1 ? storageCataDBTable.database : state.curCatalog === (route.query?.catalog)?.toString() ? ((route.query?.db)?.toString() || state.databaseList[0].id || '') : state.databaseList[0].id || ''
            getAllTableList()
          }
        }
      }).finally(() => {
        state.loading = false
      })
    }

    function getAllTableList() {
      if (!state.curCatalog || !state.database) {
        return
      }
      if (state.allTableListLoaded.length) {
        state.tableList = filteredTables.value
        return
      }

      state.tableLoading = true
      state.tableList.length = 0
      getTableList({
        catalog: state.curCatalog,
        db: state.database,
        keywords: state.tableSearchInput,
      }).then((res: ITableItem[]) => {
        state.tableList = (res || []).map((ele: ITableItem) => ({
          id: ele.name,
          label: ele.name,
          type: ele.type,
        }))
        if (state.tableSearchInput === '') {
          state.allTableListLoaded = [...state.tableList]
        }
      }).finally(() => {
        state.tableLoading = false
      })
    }

    // resize event handle
    function handleResize() {
      tablesTableElScrollY.value = tablesWrapperElRef.value.clientHeight
    }

    onBeforeMount(() => {
      const { database, tableName } = storageCataDBTable
      state.database = database
      state.tableName = tableName
      addEventListener('resize', handleResize)
      getCatalogOps()
    })

    onBeforeUnmount(() => removeEventListener('resize', handleResize))

    return {
      ...toRefs(state),
      placeholder,
      tablesTableColumnKey,
      tablesWrapperElRef,
      tablesTableElScrollY,
      pagination,
      handleChangeTable,
      handleClickDb,
      getPopupContainer,
      catalogChange,
      addDatabase,
      cancel,
      createTable,
      handleClickTable,
      handleSearch,
      clearSearch,
    }
  },
})
</script>

<template>
  <div class="tables-list-wrap g-flex">
    <div class="tables-list g-flex g-flex-col">
      <div class="select-catalog g-flex-ac">
        <span class="label">{{ $t('catalog') }}</span>
        <a-select
          v-model:value="curCatalog"
          :options="catalogOptions"
          :loading="catalogLoading"
          :get-popup-container="getPopupContainer"
          @change="catalogChange"
        />
      </div>
      <div class="tables-wrap g-flex">
        <div class="database-list">
          <div class="title g-flex-jsb">
            <span class="label">{{ $t('database', 2) }}</span>
          </div>
          <div class="filter-wrap">
            <a-input-search
              v-model:value="DBSearchInput"
              :placeholder="placeholder.filterDBPh"
              @change="handleSearch('db')"
            >
              <template #prefix>
                <SearchOutlined />
              </template>
              <template v-if="DBSearchInput" #suffix>
                <CloseCircleOutlined class="input-clear-icon" @click="clearSearch('db')" />
              </template>
            </a-input-search>
          </div>
          <u-loading v-if="loading" />
          <VirtualRecycleScroller :loading="loading" :items="databaseList" :active-item="database" :item-size="40" icon-name="database" @handle-click-table="handleClickDb" />
        </div>
        <div class="table-list g-flex g-flex-col">
          <div class="title g-flex-jsb">
            <span class="label">{{ $t('table', 2) }}</span>
            <!-- <plus-outlined @click="createTable" class="icon" /> -->
          </div>
          <div class="filter-wrap">
            <a-input-search
              v-model:value="tableSearchInput"
              :placeholder="placeholder.filterTablePh"
              @change="handleSearch('table')"
            >
              <template #prefix>
                <SearchOutlined />
              </template>
              <template v-if="tableSearchInput" #suffix>
                <CloseCircleOutlined class="input-clear-icon" @click="clearSearch('table')" />
              </template>
            </a-input-search>
          </div>
          <div ref="tablesWrapperElRef" class="table-wrap">
            <a-table
              :scroll="{ y: tablesTableElScrollY }"
              :columns="[
                { dataIndex: tablesTableColumnKey, scopedSlots: { customRender: tablesTableColumnKey } },
              ]"
              :show-header="false"
              :data-source="tableList"
              :pagination="pagination"
              :loading="tableLoading"
              class="ant-table-common table"
              @change="handleChangeTable"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.dataIndex === tablesTableColumnKey">
                  <div class="desc g-flex g-flex-ac">
                    <svg-icon :icon-class="record.type.toLowerCase()" class="svg-icon g-mr-8" />
                    <a-typography-text
                      :ellipsis="{
                        tooltip: record.label,
                      }"
                      class="primary-link"
                      :content="record.label"
                      @click="handleClickTable(record)"
                    />
                  </div>
                </template>
              </template>
            </a-table>
          </div>
        </div>
      </div>
    </div>
  </div>
  <CreateDBModal :visible="showCreateDBModal" :catalog-options="catalogOptions" @cancel="cancel" />
</template>

<style lang="less" scoped>
  .tables-list-wrap {
    padding: 16px 24px;
    height: calc(100% - 40px); // 40px - compensating for the height of app topbar panel
  }

  .tables-list {
    box-sizing: border-box;
    height: 100%;
    width: 100%;
    color: #000;
    border: @border-primary;

    .filter-wrap {
      flex-shrink: 0;
      padding: 8px;
      border-bottom: @border-primary;

      .input-clear-icon {
        font-size: 12px;
      }
    }

    .tables-wrap, .table-wrap {
      height: 100%;
      min-height: 50vh;
    }

    .database-list {
      background-color: #fafafa;
      border-right: 1px solid #e8e8f0;
      flex: 1 0 240px; // 240px - width of databases list container
    }

    .table-list {
      .primary-link {
        color: @primary-color;
        flex: 1 1 100%;

        &:hover {
          cursor: pointer;
        }

        &.disabled {
          color: #999;

          &:hover {
            cursor: not-allowed;
          }
        }
      }
    }

    .select-catalog,
    .title {
      padding-left: 12px;
      padding-right: 12px;
      align-items: center;
      flex-shrink: 0;
      height: 40px;
    }

    .select-catalog {
      padding-top: 8px;
      padding-bottom: 8px;
      border-bottom: @border-primary;
    }

    .title {
      background-color: #fafafa;
      border-bottom: @border-primary;
    }

    .icon {
      cursor: pointer;
    }

    // :deep rules
    :deep(.select-catalog .ant-select) {
      width: 240px;
      margin-left: 12px;
    }

    :deep(.table-list .ant-table-pagination-right) {
      margin-right: 8px;
    }

    :deep(.ant-input-group-addon) {
      display: none;
    }

    :deep(.database-list .scroller){
      height: calc(100% - 90px); // 90px - compensating for the height of blocks with a header and filter that have a fixed height
      margin-top: 0;
      padding: 0;
    }

    :deep(.database-list .desc) {
      color: inherit;

      &:hover {
        background: inherit;
        cursor: pointer;
        color: @primary-color;
      }

      &.active {
        background-color: @primary-color;
        color: #fff;
      }
    }
  }
</style>
