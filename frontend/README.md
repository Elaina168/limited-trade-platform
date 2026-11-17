# 限量集市 Vue前端

第一阶段已实现基础布局、登录退出、身份恢复、USER/ADMIN路由及错误反馈。开发环境使用Vite代理访问现有Java接口。

## 运行

使用nvm中已确认的Node24.13.0和npm11.6.2，在frontend目录执行。

```powershell
npm.cmd ci
npm.cmd run dev
```

页面入口为http://127.0.0.1:5173，默认将/api代理到http://127.0.0.1:8080。使用现有后端账号登录，后端提供Session与CSRF校验。

后端监听其他地址时，在同一终端设置目标再启动。

```powershell
$env:BACKEND_URL='http://127.0.0.1:18739'
npm.cmd run dev
```

BACKEND_URL由vite.config.ts在启动时读取。该环境变量只用于本机代理目标。

## 检查与构建

```powershell
npm.cmd test
npm.cmd run build
```

test使用Node内置测试运行器验证身份、CSRF和异步请求边界。build依次执行vue-tsc和Vite构建，输出到dist。浏览器联调记录见docs/learning/39-vue-auth-foundation.md（相对仓库根目录）。

## 文件调用流程

main.ts安装Pinia、组件和路由。router/index.ts先调用stores/auth.ts恢复身份，再决定访问位置。登录页调用auth.login()，经api/auth.ts与api/http.ts获取CSRF、发送表单并读取me。App.vue根据身份变化导航，AppLayout.vue显示当前账号与内容。

接口返回直接对应后端DTO。登录与退出的204作为空响应处理。登录密码只在表单与当前请求中使用，用户信息通过me恢复。Element Plus按本阶段使用范围注册ElInput、ElButton和ElAlert。
