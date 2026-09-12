import type { Meta, StoryObj } from '@storybook/vue3'
import { computed, ref, watch } from 'vue'
import type { Dayjs } from 'dayjs'

import DatePicker from './DatePicker.vue'
import { addDays, toDate, toYMD, type DateInput } from './DatePicker.utils'
import type {
  DatePickerPublicProps,
  DatePickerModelValue,
  DatePickerDateString,
  DatePickerPanelModeValue,
} from './DatePicker.types'

type DisabledTimeConfig = {
  disabledHours?: () => number[]
  disabledMinutes?: (hour: number) => number[]
  disabledSeconds?: (hour: number, minute: number) => number[]
}

type ShowTimeConfig = {
  defaultValue?: Dayjs
  format?: string
  showNow?: boolean
  use12Hours?: boolean
  hourStep?: number
  minuteStep?: number
  secondStep?: number
  hideDisabledOptions?: boolean
  disabledTime?: (date: Dayjs) => DisabledTimeConfig
}

/**
 * Attributes forwarded to Ant Design Vue via `$attrs`.
 * Add more here as you start using them in stories.
 */
type AntdPassThroughAttrs = {
  disabledDate?: (current: Dayjs) => boolean
  showTime?: boolean | ShowTimeConfig
  picker?: 'date' | 'week' | 'month' | 'quarter' | 'year'
}

/**
 * Story-only controls (not component props).
 * Storybook "date" control often yields a timestamp number.
 */
type StoryExtras = {
  date?: DateInput
  rangeFrom?: DateInput
  rangeTo?: DateInput
}

/**
 * Action handlers provided by Storybook via `argTypes.action`.
 * (No addon-actions import needed.)
 */
type ActionArgs = {
  onChange?: (value: DatePickerModelValue, dateString: DatePickerDateString) => void
  onOpenChange?: (open: boolean) => void
  onPanelChange?: (value: DatePickerModelValue, mode: DatePickerPanelModeValue) => void
  onCalendarChange?: (value: DatePickerModelValue, dateStrings: DatePickerDateString) => void
  onOk?: (value: DatePickerModelValue) => void
  'onUpdate:modelValue'?: (value: DatePickerModelValue) => void
}

type StoryArgs = DatePickerPublicProps & AntdPassThroughAttrs & StoryExtras & ActionArgs
type Story = StoryObj<StoryArgs>

/**
 * Prevent story-only args and action handlers from being forwarded via `$attrs`.
 * Also strip `modelValue` because stories control it via local state.
 */
type ComponentArgs = Omit<StoryArgs, keyof StoryExtras | keyof ActionArgs | 'modelValue'>

function useComponentArgs(args: StoryArgs) {
  return computed<ComponentArgs>(() => {
    const {
      modelValue,
      date,
      rangeFrom,
      rangeTo,
      onChange,
      onOpenChange,
      onPanelChange,
      onCalendarChange,
      onOk,
      ['onUpdate:modelValue']: onUpdateModelValue,
      ...rest
    } = args
    return rest
  })
}

const now = new Date()
const weekFromNow = addDays(now, 7)

const meta = {
  title: 'DatePicker',
  component: DatePicker,
  argTypes: {
    // Story-only controls (NOT component props)
    date: {
      description: 'Single date (Storybook control)',
      control: { type: 'date' },
    },
    rangeFrom: {
      description: 'Range start (Storybook control)',
      control: { type: 'date' },
    },
    rangeTo: {
      description: 'Range end (Storybook control)',
      control: { type: 'date' },
    },

    // Component props
    modelValue: { table: { disable: true } }, // managed by local state in stories
    range: { control: 'boolean' },
    label: { control: 'text' },
    help: { control: 'text' },
    error: { control: 'text' },
    required: { control: 'boolean' },
    id: { control: 'text' },
    disabled: { control: 'boolean' },
    allowClear: { control: 'boolean' },
    size: { control: 'select', options: ['small', 'middle', 'large'] },
    placeholder: { control: 'object' },
    valueFormat: { control: 'text' },
    format: { control: 'object' },

    // Pass-through attrs
    disabledDate: { table: { disable: true } }, // functions are not convenient in controls
    showTime: { table: { disable: true } }, // object/boolean, usually set in story args
    picker: { control: 'select', options: ['date', 'week', 'month', 'quarter', 'year'] },

    onChange: { action: 'change' },
    onOpenChange: { action: 'openChange' },
    onPanelChange: { action: 'panelChange' },
    onCalendarChange: { action: 'calendarChange' },
    onOk: { action: 'ok' },
    'onUpdate:modelValue': { action: 'update:modelValue' },
  },
  args: {
    modelValue: null,
    range: false,
    label: 'Date',
    help: '',
    error: '',
    required: false,
    id: '',
    disabled: false,
    allowClear: true,
    size: 'middle',
    placeholder: 'Select date',
    valueFormat: 'YYYY-MM-DD',
    format: undefined,

    // Story-only defaults
    date: now,
    rangeFrom: now,
    rangeTo: weekFromNow,
  },
} satisfies Meta<StoryArgs>

export default meta

export const Single: Story = {
  name: 'Single',
  args: {
    range: false,
    label: 'Date',
    valueFormat: 'YYYY-MM-DD',
    placeholder: 'YYYY-MM-DD',
    date: now,
  },
  render: (args) => ({
    components: { DatePicker },
    setup() {
      const componentArgs = useComponentArgs(args)

      const localValue = ref<DatePickerModelValue>(toYMD(toDate(args.date)))

      watch(
        () => args.date,
        (v) => {
          localValue.value = toYMD(toDate(v))
        },
      )

      const handleUpdate = (v: DatePickerModelValue) => {
        localValue.value = v
        args['onUpdate:modelValue']?.(v)
      }

      const handleChange = (value: DatePickerModelValue, dateString: DatePickerDateString) => {
        args.onChange?.(value, dateString)
      }

      return { args, componentArgs, localValue, handleUpdate, handleChange }
    },
    template: `
      <div style="width: 320px; padding: 16px; display: flex; align-items: center;">
        <DatePicker
          v-bind="componentArgs"
          :modelValue="localValue"
          @update:modelValue="handleUpdate"
          @change="handleChange"
          @openChange="args.onOpenChange"
          @panelChange="args.onPanelChange"
          @calendarChange="args.onCalendarChange"
          @ok="args.onOk"
        />
      </div>
    `,
  }),
}

export const Range: Story = {
  name: 'Range',
  args: {
    range: true,
    label: 'Period',
    valueFormat: 'YYYY-MM-DD',
    placeholder: ['Start', 'End'],
    rangeFrom: now,
    rangeTo: weekFromNow,
  },
  render: (args) => ({
    components: { DatePicker },
    setup() {
      const componentArgs = useComponentArgs(args)

      const computedRange = computed<DatePickerModelValue>(() => {
        const from = toYMD(toDate(args.rangeFrom))
        const to = toYMD(toDate(args.rangeTo))
        if (!from || !to) return null
        return [from, to]
      })

      const localValue = ref<DatePickerModelValue>(computedRange.value)

      watch(
        () => [args.rangeFrom, args.rangeTo] as const,
        () => {
          localValue.value = computedRange.value
        },
      )

      const handleUpdate = (v: DatePickerModelValue) => {
        localValue.value = v
        args['onUpdate:modelValue']?.(v)
      }

      const handleChange = (value: DatePickerModelValue, dateString: DatePickerDateString) => {
        args.onChange?.(value, dateString)
      }

      return { args, componentArgs, localValue, handleUpdate, handleChange }
    },
    template: `
      <div style="width: 420px; padding: 16px; display: flex; align-items: center;">
        <DatePicker
          v-bind="componentArgs"
          :modelValue="localValue"
          @update:modelValue="handleUpdate"
          @change="handleChange"
          @openChange="args.onOpenChange"
          @panelChange="args.onPanelChange"
          @calendarChange="args.onCalendarChange"
          @ok="args.onOk"
        />
      </div>
    `,
  }),
}

export const WithHelp: Story = {
  name: 'With help',
  args: {
    label: 'Date',
    help: 'For example, publication date',
  },
  render: (args) => ({
    components: { DatePicker },
    setup() {
      const componentArgs = useComponentArgs(args)
      const localValue = ref<DatePickerModelValue>(null)

      const handleUpdate = (v: DatePickerModelValue) => {
        localValue.value = v
        args['onUpdate:modelValue']?.(v)
      }

      return { args, componentArgs, localValue, handleUpdate }
    },
    template: `
      <div style="width: 320px; padding: 16px;">
        <DatePicker
            v-bind="componentArgs"
            :modelValue="localValue"
            @update:modelValue="handleUpdate"
            @change="args.onChange"
            @openChange="args.onOpenChange"
            @panelChange="args.onPanelChange"
            @calendarChange="args.onCalendarChange"
            @ok="args.onOk"
        />
      </div>
    `,
  }),
}

export const WithError: Story = {
  name: 'With error',
  args: {
    label: 'Date',
    required: true,
    error: 'This field is required',
  },
  render: (args) => ({
    components: { DatePicker },
    setup() {
      const componentArgs = useComponentArgs(args)
      const localValue = ref<DatePickerModelValue>(null)

      const handleUpdate = (v: DatePickerModelValue) => {
        localValue.value = v
        args['onUpdate:modelValue']?.(v)
      }

      return { args, componentArgs, localValue, handleUpdate }
    },
    template: `
      <div style="width: 320px; padding: 16px;">
        <DatePicker
            v-bind="componentArgs"
            :modelValue="localValue"
            @update:modelValue="handleUpdate"
            @change="args.onChange"
            @openChange="args.onOpenChange"
            @panelChange="args.onPanelChange"
            @calendarChange="args.onCalendarChange"
            @ok="args.onOk"
        />
      </div>
    `,
  }),
}

export const Disabled: Story = {
  name: 'Disabled',
  args: {
    label: 'Date',
    disabled: true,
    date: now,
  },
  render: (args) => ({
    components: { DatePicker },
    setup() {
      const componentArgs = useComponentArgs(args)
      const localValue = ref<DatePickerModelValue>(toYMD(toDate(args.date)))

      watch(
        () => args.date,
        (v) => {
          localValue.value = toYMD(toDate(v))
        },
      )

      const handleUpdate = (v: DatePickerModelValue) => {
        localValue.value = v
        args['onUpdate:modelValue']?.(v)
      }

      return { args, componentArgs, localValue, handleUpdate }
    },
    template: `
      <div style="width: 320px; padding: 16px;">
        <DatePicker
            v-bind="componentArgs"
            :modelValue="localValue"
            @update:modelValue="handleUpdate"
            @change="args.onChange"
            @openChange="args.onOpenChange"
            @panelChange="args.onPanelChange"
            @calendarChange="args.onCalendarChange"
            @ok="args.onOk"
        />
      </div>
    `,
  }),
}

export const DisabledFutureDates: Story = {
  name: 'Disabled future dates',
  args: {
    label: 'Past dates only',
    disabledDate: (d: Dayjs) => d.valueOf() > Date.now(),
  },
  render: (args) => ({
    components: { DatePicker },
    setup() {
      const componentArgs = useComponentArgs(args)
      const localValue = ref<DatePickerModelValue>(null)

      const handleUpdate = (v: DatePickerModelValue) => {
        localValue.value = v
        args['onUpdate:modelValue']?.(v)
      }

      return { args, componentArgs, localValue, handleUpdate }
    },
    template: `
      <div style="width: 320px; padding: 16px;">
        <DatePicker
            v-bind="componentArgs"
            :modelValue="localValue"
            @update:modelValue="handleUpdate"
            @change="args.onChange"
            @openChange="args.onOpenChange"
            @panelChange="args.onPanelChange"
            @calendarChange="args.onCalendarChange"
            @ok="args.onOk"
        />
      </div>
    `,
  }),
}

export const WithTime: Story = {
  name: 'With time',
  args: {
    label: 'Date & time',
    showTime: true, // forwarded via $attrs
    valueFormat: 'YYYY-MM-DD HH:mm:ss',
    placeholder: 'YYYY-MM-DD HH:mm:ss',
  },
  render: (args) => ({
    components: { DatePicker },
    setup() {
      const componentArgs = useComponentArgs(args)
      const localValue = ref<DatePickerModelValue>(null)

      const handleUpdate = (v: DatePickerModelValue) => {
        localValue.value = v
        args['onUpdate:modelValue']?.(v)
      }

      return { args, componentArgs, localValue, handleUpdate }
    },
    template: `
      <div style="width: 360px; padding: 16px;">
        <DatePicker
            v-bind="componentArgs"
            :modelValue="localValue"
            @update:modelValue="handleUpdate"
            @change="args.onChange"
            @openChange="args.onOpenChange"
            @panelChange="args.onPanelChange"
            @calendarChange="args.onCalendarChange"
            @ok="args.onOk"
        />
      </div>
    `,
  }),
}
