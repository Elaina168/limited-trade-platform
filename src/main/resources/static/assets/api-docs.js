window.ui = SwaggerUIBundle({
  url: '/v3/api-docs',
  dom_id: '#swagger-ui',
  deepLinking: true,
  validatorUrl: null,
  persistAuthorization: false,
  withCredentials: true,
  requestInterceptor: async request => {
    // 写请求每次获取当前会话的Token，避免登录和退出轮换后继续使用旧值。
    // Swagger加载文档时省略method，按Fetch约定视为GET。
    const method = (request.method || 'GET').toUpperCase();
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      const response = await fetch('/api/auth/csrf', { credentials: 'same-origin' });
      if (!response.ok) throw new Error(`获取CSRF Token失败：HTTP ${response.status}`);
      const csrf = await response.json();
      request.headers[csrf.headerName] = csrf.token;
    }
    return request;
  }
});
