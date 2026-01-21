import type { Dayjs } from 'dayjs'
import type { DATE_PICKER_SIZES } from './DatePicker.constants'

export type DatePickerSize = (typeof DATE_PICKER_SIZES)[number]

export type DatePickerValue = Dayjs | string | null
export type DatePickerRangeValue = [DatePickerValue, DatePickerValue] | null
export type DatePickerModelValue = DatePickerValue | DatePickerRangeValue

export type DatePickerPlaceholder = string | [string, string]
export type DatePickerDateString = string | [string, string]

export type DatePickerPanelMode = 'time' | 'date' | 'week' | 'month' | 'quarter' | 'year' | 'decade'
export type DatePickerPanelModeValue = DatePickerPanelMode | [DatePickerPanelMode, DatePickerPanelMode]

export type DatePickerFormat =
  | string
  | string[]
  | ((value: Dayjs) => string)
  | Array<string | ((value: Dayjs) => string)>

/**
 * Vue-compatible types for `class` and `style` bindings.
 * (We keep them local to avoid depending on Vue's internal exported types.)
 */
export type VueClass = string | string[] | Record<string, boolean> | Array<string | Record<string, boolean>> | null | undefined
export type VueStyle =
  | string
  | Record<string, string | number>
  | Array<string | Record<string, string | number>>
  | null
  | undefined

export type DatePickerPublicProps = {
  modelValue: DatePickerModelValue

  range?: boolean

  label?: string
  help?: string
  error?: string
  required?: boolean

  id?: string

  disabled?: boolean
  allowClear?: boolean
  size?: DatePickerSize

  placeholder?: DatePickerPlaceholder
  valueFormat?: string
  format?: DatePickerFormat
}

export type DatePickerEmits = {
  (e: 'update:modelValue', value: DatePickerModelValue): void
  (e: 'change', value: DatePickerModelValue, dateString: DatePickerDateString): void
  (e: 'openChange', open: boolean): void
  (e: 'panelChange', value: DatePickerModelValue, mode: DatePickerPanelModeValue): void
  (e: 'calendarChange', value: DatePickerModelValue, dateStrings: DatePickerDateString): void
  (e: 'ok', value: DatePickerModelValue): void
}
