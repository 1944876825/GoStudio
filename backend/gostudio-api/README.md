# GoStudio API

GoStudio App 的统一后端（gin + gorm + sqlite，纯 Go 免 CGO），按标准 Go 项目布局组织：

```
cmd/server/          入口：装配配置/DB/路由，优雅关停
internal/config      环境变量配置（见 configs/config.example.env）
internal/database    sqlite（glebarez 纯 Go 驱动）+ AutoMigrate
internal/model       GORM 模型（error_types / error_reports）
internal/repository  数据访问层
internal/service     业务逻辑：崩溃指纹去重、后台账号/JWT
internal/handler     HTTP 接口（crash / admin / translation）
internal/middleware  鉴权、体积限制
internal/translation 翻译提供方（自旧 translation-api 移植，契约不变）
web/admin/           管理后台静态页（embed 内嵌，单二进制分发）
```

## 功能

### 崩溃上报（App 错误反馈）

- `POST /crash/v1/reports`：App 上报崩溃。同一错误类型**不重复创建**——服务端按
  指纹（异常类名 + 前几个应用帧、去行号）聚合，重复上报只在 `error_types` 上累加计数；
  每次上报都在 `error_reports` 留一条明细（设备品牌/型号、Android 版本、App 版本、IP、
  联系方式、用户备注、完整堆栈）。
- `GET /crash/v1/reports?device_id=xxx`：App「我的反馈」——返回该设备的上报历史，
  含该错误当前是否已修复。
- 防滥用：请求体 ≤256KB；单设备每小时 ≤10 次。

请求头：`X-GoStudio-Backend-Key: <GOSTUDIO_API_KEYS 之一>`

```json
// POST /crash/v1/reports
{
  "device_id": "a1b2c3", "device_brand": "Xiaomi",
  "device_manufacturer": "Xiaomi", "device_model": "23127PN04C",
  "android_version": "15", "app_version": "1.0.7",
  "contact": "me@qq.com", "comment": "打开编辑器就崩了",
  "crash_log": "IllegalStateException: ...",
  "crash_stack": "java.lang.IllegalStateException: ...\n\tat com.jmwl.gostudio..."
}
// → {"report_id":1,"error_type_id":1,"fingerprint":"f59…","occurrence_count":2,"first_report":false}
```

### 管理后台（浏览器打开 `/admin`）

账号密码登录（JWT 会话）。功能：错误类型列表（未处理/已修复筛选、标题/指纹搜索、
出现次数与涉及设备数）、错误详情（完整堆栈 + 每次上报的设备信息、IP、联系方式、
备注）、一键标记已修复/重新打开、概览统计。

### 翻译（自旧 translation-api 平移）

`POST /translation/v1/translate`，契约与旧服务完全一致（鉴权头
`X-GoStudio-Translation-Key`、缓存、并发闸、超时与错误码均未变），部署变量同名，
见 `configs/config.example.env`。

## 本地运行

```bash
export GOSTUDIO_API_ADMIN_PASSWORD=admin-pass
export GOSTUDIO_API_ADMIN_JWT_SECRET=0123456789abcdef
export TRANSLATION_PROVIDER=mock            # 本地联调用 mock
export GOSTUDIO_API_KEYS=dev-key
go run ./cmd/server                         # 默认 :8080
```

测试：`go test ./...`

## Docker 部署

```bash
cd backend/gostudio-api
# 编辑 docker-compose.yml 里的三个 change-me 变量
docker compose up -d --build
```

数据落在 `./data/gostudio.db`，备份该目录即可。

## 反向代理与切换说明

App 端地址统一在 `gostudio_backend_config.kt`：

- 崩溃上报：`https://gs.jmwl.dpdns.org/api/crash`
- 翻译：`https://gs.jmwl.dpdns.org/api/translation`

两种代理写法任选其一：

```nginx
# A. 剥掉 /api 前缀（推荐，与现状一致）
location /api/ {
    proxy_pass http://127.0.0.1:8080/;   # /api/crash/v1/reports → /crash/v1/reports
}

# B. 保留前缀：给服务设 GOSTUDIO_API_ROUTE_PREFIX=/api，然后
location /api/ {
    proxy_pass http://127.0.0.1:8080;    # 前缀原样透传
}
```

从旧 `backend/translation-api` 切换：翻译相关环境变量直接复制（同名），
`X-GoStudio-Translation-Key` 兼容旧 key 列表（`TRANSLATION_BACKEND_API_KEYS`
仍会读取）。新服务跑稳后即可下线旧容器。
