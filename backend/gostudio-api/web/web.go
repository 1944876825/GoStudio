// Package web 内嵌管理后台静态资源，构建产物单二进制即可服务 /admin 页面。
package web

import "embed"

//go:embed admin
var AdminFS embed.FS
