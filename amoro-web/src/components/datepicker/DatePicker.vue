<template>
  <div
      class="date-picker"
      :class="[
      rootClass,
      { 'date-picker--error': hasError, 'date-picker--disabled': disabled }
    ]"
      :style="rootStyle"
  >
    <label v-if="label" class="date-picker__label" :for="inputId">
      <span>{{ label }}</span>
      <span v-if="required" class="date-picker__required" aria-hidden="true">*</span>
    </label>

    <component
        :is="pickerComponent"
        ref="pickerRef"
        v-model:value="innerValue"
        :id="inputId"
        :disabled="disabled"
        :allow-clear="allowClear"
        :placeholder="placeholderNormalized"
        :size="size"
        :value-format="valueFormat"
        :format="format"
        v-bind="pickerAttrs"
        @change="handleChange"
        @openChange="handleOpenChange"
        @panelChange="handlePanelChange"
        @calendarChange="handleCalendarChange"
        @ok="handleOk"
    />

    <div v-if="hasError || help" class="date-picker__help" :aria-live="hasError ? 'polite' : undefined">
      <span v-if="hasError" class="date-picker__error">{{ error }}</span>
      <span v-else>{{ help }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, useAttrs } from 'vue'
import type { Component } from 'vue'
import { DatePicker as AntDatePicker } from 'ant-design-vue'

import {
  DEFAULT_RANGE_PLACEHOLDER,
  DEFAULT_SINGLE_PLACEHOLDER,
  DEFAULT_VALUE_FORMAT,
} from './DatePicker.constants'

import type {
  DatePickerEmits,
  DatePickerModelValue,
  DatePickerDateString,
  DatePickerPanelModeValue,
  DatePickerPublicProps,
  DatePickerPlaceholder,
  VueClass,
  VueStyle,
} from './DatePicker.types'

defineOptions({ name: 'DatePicker', inheritAttrs: false })

const props = withDefaults(defineProps<DatePickerPublicProps>(), {
  range: false,

  label: '',
  help: '',
  error: '',
  required: false,

  id: '',

  disabled: false,
  allowClear: true,
  size: 'middle',

  placeholder: DEFAULT_SINGLE_PLACEHOLDER,
  valueFormat: DEFAULT_VALUE_FORMAT,
})

const emit = defineEmits<DatePickerEmits>()

const attrs = useAttrs()

const rootClass = computed<VueClass>(() => attrs.class as VueClass)
const rootStyle = computed<VueStyle>(() => attrs.style as VueStyle)

const hasError = computed(() => props.error.length > 0)

/**
 * Forward all attributes to AntDV DatePicker/RangePicker,
 * except root class/style which are applied to the wrapper.
 */
const pickerAttrs = computed(() => {
  const { class: _class, style: _style, ...rest } = attrs
  return rest
})

/**
 * Keep the id truly optional in DOM: if empty string -> attribute is removed.
 */
const inputId = computed<string | undefined>(() => (props.id.trim() ? props.id : undefined))

/**
 * AntDV DatePicker includes RangePicker as a static property.
 * We type it as a generic Vue Component to avoid `any`.
 */
type AntDatePickerWithRange = typeof AntDatePicker & { RangePicker: Component }
const ant = AntDatePicker as AntDatePickerWithRange

const pickerComponent = computed<Component>(() => (props.range ? ant.RangePicker : ant))

const innerValue = computed<DatePickerModelValue>({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const placeholderNormalized = computed<DatePickerPlaceholder>(() => {
  if (!props.range) {
    return Array.isArray(props.placeholder) ? props.placeholder[0] : props.placeholder
  }

  if (Array.isArray(props.placeholder)) return props.placeholder
  if (props.placeholder.trim()) return [props.placeholder, props.placeholder]

  return [...DEFAULT_RANGE_PLACEHOLDER]
})

function handleChange(value: DatePickerModelValue, dateString: DatePickerDateString) {
  // modelValue is already updated via v-model:value
  emit('change', value, dateString)
}

function handleOpenChange(open: boolean) {
  emit('openChange', open)
}

function handlePanelChange(value: DatePickerModelValue, mode: DatePickerPanelModeValue) {
  emit('panelChange', value, mode)
}

function handleCalendarChange(value: DatePickerModelValue, dateStrings: DatePickerDateString) {
  emit('calendarChange', value, dateStrings)
}

function handleOk(value: DatePickerModelValue) {
  emit('ok', value)
}

type Focusable = {
  focus?: () => void
  blur?: () => void
}

const pickerRef = ref<Focusable | null>(null)

defineExpose({
  focus: () => pickerRef.value?.focus?.(),
  blur: () => pickerRef.value?.blur?.(),
})
</script>

<style scoped lang="less" src="./DatePicker.less"></style>

