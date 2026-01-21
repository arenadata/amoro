import type { App } from 'vue'
import DatePicker from './DatePicker.vue'

export { DatePicker }

export default {
  install(app: App) {
    app.component('DatePicker', DatePicker)
  },
}
