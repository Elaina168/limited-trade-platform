import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElAlert, ElButton, ElDatePicker, ElInput, ElInputNumber, ElOption, ElSelect } from 'element-plus'
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/date-picker/style/css'
import 'element-plus/es/components/input-number/style/css'
import './styles/main.css'
import App from './App.vue'
import { router } from './router/index.ts'

const app = createApp(App)
app.use(createPinia())
app.component('ElAlert', ElAlert)
app.component('ElButton', ElButton)
app.component('ElInput', ElInput)
app.component('ElSelect', ElSelect)
app.component('ElOption', ElOption)
app.component('ElDatePicker', ElDatePicker)
app.component('ElInputNumber', ElInputNumber)
app.use(router)
await router.isReady()
app.mount('#app')
