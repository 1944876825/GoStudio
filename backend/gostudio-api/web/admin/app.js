/* GoStudio 错误反馈后台（原生 JS，无构建步骤）。 */
(function () {
  'use strict';

  var TOKEN_KEY = 'gs_admin_token';
  var state = {
    token: localStorage.getItem(TOKEN_KEY) || '',
    status: '',
    query: '',
    page: 1,
    pageSize: 20,
    total: 0,
    detailId: 0,
    reportPage: 1,
    reportTotal: 0
  };

  // ---------- 工具 ----------
  function $(id) { return document.getElementById(id); }
  function esc(text) {
    if (text == null) return '';
    return String(text).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }
  function fmtTime(value) {
    if (!value) return '–';
    var date = new Date(value);
    if (isNaN(date.getTime())) return value;
    var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) +
      ' ' + pad(date.getHours()) + ':' + pad(date.getMinutes());
  }
  function stackHtml(stack) {
    var lines = esc(stack || '').split('\n');
    return lines.map(function (line) {
      if (/Exception|Error|Caused by/.test(line)) {
        return '<span class="err-line">' + line + '</span>';
      }
      return line;
    }).join('\n');
  }

  function api(path, options) {
    options = options || {};
    options.headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
    if (state.token) options.headers['Authorization'] = 'Bearer ' + state.token;
    return fetch(path, options).then(function (response) {
      if (response.status === 401) {
        showLogin();
        throw new Error('unauthorized');
      }
      return response.json().then(function (body) {
        if (!response.ok) throw new Error(body.error || ('HTTP ' + response.status));
        return body;
      });
    });
  }

  // ---------- 视图切换 ----------
  function showLogin() {
    state.token = '';
    localStorage.removeItem(TOKEN_KEY);
    $('login-view').classList.remove('hidden');
    $('main-view').classList.add('hidden');
    $('login-password').value = '';
  }
  function showMain() {
    $('login-view').classList.add('hidden');
    $('main-view').classList.remove('hidden');
    $('admin-user').textContent = '@admin';
  }
  function showListPage() { $('list-page').classList.remove('hidden'); $('detail-page').classList.add('hidden'); }
  function showDetailPage() { $('list-page').classList.add('hidden'); $('detail-page').classList.remove('hidden'); }

  // ---------- 登录 ----------
  $('login-form').addEventListener('submit', function (event) {
    event.preventDefault();
    $('login-error').classList.add('hidden');
    var username = $('login-username').value.trim();
    var password = $('login-password').value;
    if (!username || !password) {
      $('login-error').textContent = '请输入用户名和密码';
      $('login-error').classList.remove('hidden');
      return;
    }
    fetch('api/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: username, password: password })
    }).then(function (response) {
      return response.json().then(function (body) {
        if (!response.ok) throw new Error(body.error || '登录失败');
        return body;
      });
    }).then(function (body) {
      state.token = body.token;
      localStorage.setItem(TOKEN_KEY, body.token);
      showMain();
      loadStats();
      loadTypes();
    }).catch(function (error) {
      $('login-error').textContent = error.message === 'invalid username or password'
        ? '用户名或密码错误' : error.message;
      $('login-error').classList.remove('hidden');
    });
  });

  $('logout-btn').addEventListener('click', showLogin);

  // ---------- 列表页 ----------
  function loadStats() {
    api('api/stats').then(function (stats) {
      $('stat-total-types').textContent = stats.total_types;
      $('stat-open-types').textContent = stats.open_types;
      $('stat-today-reports').textContent = stats.today_reports;
      $('stat-total-reports').textContent = stats.total_reports;
    }).catch(function () {});
  }

  function loadTypes() {
    var params = new URLSearchParams({
      page: state.page,
      page_size: state.pageSize
    });
    if (state.status) params.set('status', state.status);
    if (state.query) params.set('q', state.query);
    api('api/error-types?' + params).then(function (body) {
      state.total = body.total;
      renderTypeList(body.items);
      renderPager();
      $('list-empty').classList.toggle('hidden', body.items.length > 0);
    }).catch(function () {});
  }

  function renderTypeList(items) {
    var html = items.map(function (item) {
      var badge = item.status === 'resolved'
        ? '<span class="badge resolved">已修复</span>'
        : '<span class="badge open">未处理</span>';
      return '' +
        '<div class="type-card" data-id="' + item.id + '">' +
          '<div class="type-title">' + esc(item.title) + '</div>' +
          '<div class="type-meta">' +
            badge +
            '<span class="badge count">× ' + item.occurrence_count + ' 次</span>' +
            '<span class="badge plain">' + item.device_count + ' 台设备</span>' +
            '<span>最近：' + fmtTime(item.last_seen_at) + '</span>' +
            '<span>首次：' + fmtTime(item.first_seen_at) + '</span>' +
          '</div>' +
        '</div>';
    }).join('');
    $('error-list').innerHTML = html;
    Array.prototype.forEach.call($('error-list').children, function (card) {
      card.addEventListener('click', function () { openDetail(Number(card.dataset.id)); });
    });
  }

  function renderPager() {
    var pages = Math.max(1, Math.ceil(state.total / state.pageSize));
    $('page-info').textContent = state.page + ' / ' + pages;
    $('prev-page').disabled = state.page <= 1;
    $('next-page').disabled = state.page >= pages;
  }

  $('status-tabs').addEventListener('click', function (event) {
    var button = event.target.closest('.tab');
    if (!button) return;
    Array.prototype.forEach.call($('status-tabs').children, function (tab) {
      tab.classList.toggle('active', tab === button);
    });
    state.status = button.dataset.status;
    state.page = 1;
    loadTypes();
  });

  var searchTimer = null;
  $('search-input').addEventListener('input', function (event) {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(function () {
      state.query = event.target.value.trim();
      state.page = 1;
      loadTypes();
    }, 250);
  });

  $('prev-page').addEventListener('click', function () {
    if (state.page > 1) { state.page--; loadTypes(); }
  });
  $('next-page').addEventListener('click', function () {
    state.page++;
    loadTypes();
  });

  // ---------- 详情页 ----------
  function openDetail(id) {
    state.detailId = id;
    state.reportPage = 1;
    showDetailPage();
    loadDetail();
  }

  function loadDetail() {
    api('api/error-types/' + state.detailId + '?page=' + state.reportPage + '&page_size=' + state.pageSize)
      .then(function (body) {
        renderDetailHeader(body.error_type);
        state.reportTotal = body.total;
        renderReports(body.reports);
        renderReportPager();
      }).catch(function () {});
  }

  function renderDetailHeader(errorType) {
    var badge = errorType.status === 'resolved'
      ? '<span class="badge resolved">已修复</span>'
      : '<span class="badge open">未处理</span>';
    var action = errorType.status === 'resolved'
      ? '<button class="btn small reopen" id="toggle-status" data-next="open">重新打开</button>'
      : '<button class="btn small resolve" id="toggle-status" data-next="resolved">标记已修复</button>';
    $('detail-header').innerHTML =
      '<div class="detail-head-card">' +
        '<div class="detail-title">' + esc(errorType.title) + '</div>' +
        '<div class="detail-grid">' +
          badge +
          '<span class="badge count">× ' + errorType.occurrence_count + ' 次</span>' +
        '</div>' +
        '<div class="kv" style="margin-top:12px">' +
          '<dt>指纹</dt><dd class="mono">' + esc(errorType.fingerprint) + '</dd>' +
          '<dt>首次出现</dt><dd>' + fmtTime(errorType.first_seen_at) + '</dd>' +
          '<dt>最近出现</dt><dd>' + fmtTime(errorType.last_seen_at) + '</dd>' +
        '</div>' +
        '<div class="detail-actions">' + action + '</div>' +
      '</div>';
    $('toggle-status').addEventListener('click', function () {
      api('api/error-types/' + state.detailId + '/status', {
        method: 'PATCH',
        body: JSON.stringify({ status: $('toggle-status').dataset.next })
      }).then(function () {
        loadDetail();
        loadStats();
      }).catch(function () {});
    });
  }

  function renderReports(reports) {
    var html = reports.map(function (report) {
      var device = [report.device_manufacturer, report.device_model].filter(Boolean).join(' ') || '未知设备';
      return '' +
        '<div class="report-card" data-id="' + report.id + '">' +
          '<div class="report-summary">' +
            '<div class="report-device">' + esc(device) + '</div>' +
            '<div class="report-info">' +
              '<span>' + fmtTime(report.created_at) + '</span>' +
              '<span class="badge plain">Android ' + esc(report.android_version || '–') + '</span>' +
              '<span class="badge plain">v' + esc(report.app_version || '–') + '</span>' +
            '</div>' +
          '</div>' +
          '<div class="report-detail">' +
            '<div class="kv">' +
              '<dt>品牌</dt><dd>' + esc(report.device_brand || '–') + '</dd>' +
              '<dt>型号</dt><dd>' + esc(report.device_model || '–') + '</dd>' +
              '<dt>IP</dt><dd class="mono">' + esc(report.ip || '–') + '</dd>' +
              '<dt>联系方式</dt><dd>' + esc(report.contact || '（未填写）') + '</dd>' +
              '<dt>用户备注</dt><dd>' + esc(report.comment || '（无）') + '</dd>' +
              '<dt>错误信息</dt><dd class="mono">' + esc(report.crash_log) + '</dd>' +
            '</div>' +
            '<div class="stack-block">' + stackHtml(report.crash_stack) + '</div>' +
          '</div>' +
        '</div>';
    }).join('');
    $('report-list').innerHTML = html || '<div class="empty">没有上报记录</div>';
    Array.prototype.forEach.call($('report-list').querySelectorAll('.report-summary'), function (summary) {
      summary.addEventListener('click', function () {
        summary.parentElement.classList.toggle('expanded');
      });
    });
  }

  function renderReportPager() {
    var pages = Math.max(1, Math.ceil(state.reportTotal / state.pageSize));
    $('report-page-info').textContent = state.reportPage + ' / ' + pages;
    $('report-prev').disabled = state.reportPage <= 1;
    $('report-next').disabled = state.reportPage >= pages;
  }

  $('report-prev').addEventListener('click', function () {
    if (state.reportPage > 1) { state.reportPage--; loadDetail(); }
  });
  $('report-next').addEventListener('click', function () {
    state.reportPage++;
    loadDetail();
  });

  $('back-btn').addEventListener('click', function () {
    showListPage();
    loadStats();
    loadTypes();
  });

  // ---------- 启动 ----------
  if (state.token) {
    api('api/stats').then(function () {
      showMain();
      loadTypes();
    }).catch(function () {});
  } else {
    showLogin();
  }
})();
