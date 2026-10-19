# Web UI Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans inline，用户授权一次完成；不额外请求阶段批准、不提交或新建工作树。

**Goal:** 为当前后端全部用户/管理员能力提供可操作页面。
**Architecture:** 同源静态ES模块+现有Session/CSRF/API，仅补两项读取。
**Tech Stack:** Spring Boot、HTML/CSS、原生JavaScript；复用已安装Node/Playwright/Chromium，不安装框架。
**Spec:** docs/plans/2026-10-19-web-ui-design.md

## Global Constraints

保留全部未提交改动；UTF-8/中文注释；不自动发布、提交、安装或数据库迁移。用户明确只做关联最小测试，不全量回归，覆盖技能默认全套要求。台账tools/web-ui-progress.md。

## Review Focus

会话轮换与请求编码；异步响应跨用户泄露；下单未知结果重试幂等；动态内容XSS；状态和权限与后端一致。

## Task 1：读取接口与静态入口

- [x] 写WebUiIntegrationTest，先确认静态页和两个新增读取路由失败。
- [x] 修改Activity/Product的Mapper/Service/Controller读取，SecurityConfig仅开放GET页面资源。
- [x] 专项验证角色、下架商品、活动列表及真实核心交易链路，不跑其他类。

## Task 2：全部前端交互

- [x] 新建static/index.html、assets/{styles.css,api.js,ui.js,app.js,admin.js}，覆盖设计全部页面。
- [x] 先写浏览器用例观察缺少页面失败，再实现；使用既有Playwright模块，无服务进程/下载。
- [x] 各主线一次必要浏览器验证，保存桌面/移动截图，发现实际问题才补关联检查。

## Task 3：复核与交付

- [x] 一次独立只读复核，不跑测试/数据库/修改文件，不再派代理。
- [x] 修复确认问题后仅关联验证；开发/测试数据核对，更新README/roadmap/学习文档。
- [x] 报告真实证据边界、页面入口、完成范围；不称全量回归或真实部署通过。
