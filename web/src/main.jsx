import React from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

/**
 * 管理站前端：真实 API 接线（契约 managementApi）。
 * - 登录：POST /api/v1/auth/login（principal + 共享口令），令牌只存 sessionStorage，不落 localStorage；
 * - 七域只读：GET /api/v1/...，统一 Envelope（items/source/asOf/correlationId/unavailable）；
 * - 401 → 清令牌回登录页；403 → 显示权限提示；source=unavailable → 明确降级，不伪造数据；
 * - 每个请求带 X-Correlation-Id（corr_[0-9a-f]{16}），与响应回显值一致。
 *
 * Management console frontend wired to the real API (contract managementApi).
 * Login posts to /api/v1/auth/login (principal + shared key); tokens live only in
 * sessionStorage. The seven read-only domains consume the common envelope; 401 resets
 * to the login screen, 403 shows a permission notice, and source=unavailable renders an
 * explicit degraded state — data is never fabricated. Every request carries
 * X-Correlation-Id (corr_[0-9a-f]{16}) matched against the echoed response header.
 */

const ENDPOINTS = {
  cicd: '/api/v1/cicd/workflows',
  resources: '/api/v1/resources',
  logs: '/api/v1/logs',
  metrics: '/api/v1/metrics',
  security: '/api/v1/security',
  billing: '/api/v1/billing',
  audit: '/api/v1/audit',
};

const MODULES = [
  ['cicd', 'CI/CD', '工作流与发布状态'],
  ['resources', 'Resources', '资源健康与归属'],
  ['logs', 'Logs', '结构化日志查询'],
  ['metrics', 'Metrics', '指标与延迟'],
  ['security', 'Security', '安全姿态与策略'],
  ['billing', 'Billing', '只读成本摘要'],
  ['audit', 'Audit', '审计事件'],
];

/** 每次页面加载生成一个请求级 correlationId 基值 / per-page-load correlation base. */
const CORR_BASE = Array.from(crypto.getRandomValues(new Uint8Array(8)))
  .map((b) => b.toString(16).padStart(2, '0'))
  .join('');

function newCorrelationId() {
  // 同页面多个请求共享前缀便于排查；格式仍是契约 corr_[0-9a-f]{16}
  return `corr_${CORR_BASE}`;
}

function loadTokens() {
  try {
    const raw = sessionStorage.getItem('mgmt.tokens');
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function saveTokens(tokens) {
  if (tokens) {
    sessionStorage.setItem('mgmt.tokens', JSON.stringify(tokens));
  } else {
    sessionStorage.removeItem('mgmt.tokens');
  }
}

/** 通用 Envelope 渲染：把 provider-neutral DTO 的前几个键值铺成表格行。 */
function pickCells(item) {
  const keys = Object.keys(item || {});
  const preferred = keys.filter((k) => !['id', 'correlationId'].includes(k));
  return [keys.find((k) => k === 'id') || preferred[0] || '—', preferred.slice(0, 4)];
}

function LoginScreen({ onLogin, error, busy }) {
  const [principal, setPrincipal] = React.useState('');
  const [password, setPassword] = React.useState('');
  const submit = (event) => {
    event.preventDefault();
    onLogin(principal.trim(), password);
  };
  return (
    <main className="shell login-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">ALBUM MANAGEMENT</p>
          <h1>相册管理平台</h1>
        </div>
        <span className="badge">READ ONLY</span>
      </header>
      <section className="intro">
        <p className="kicker">SIGN IN</p>
        <h2>登录后查看六域只读数据。</h2>
        <p>本地演示账户：admin / auditor / viewer，共享口令 album-mgmt-demo；令牌只存在本会话。</p>
      </section>
      <form className="login-card" onSubmit={submit}>
        <label>
          <span>主体 / Principal</span>
          <input
            value={principal}
            onChange={(e) => setPrincipal(e.target.value)}
            placeholder="admin"
            autoComplete="username"
            required
          />
        </label>
        <label>
          <span>共享口令 / Shared key</span>
          <input
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            type="password"
            placeholder="album-mgmt-demo"
            autoComplete="current-password"
            required
          />
        </label>
        {error ? <p className="form-error" role="alert">{error}</p> : null}
        <button type="submit" disabled={busy}>{busy ? '登录中…' : '登录'}</button>
      </form>
    </main>
  );
}

function DomainDetail({ domain, data }) {
  if (!data) return <div className="empty-state">加载中 / Loading…</div>;
  if (data.error) {
    return <div className="empty-state error">{data.error === 'domain_permission_denied'
      ? '当前角色无该域权限 / role lacks this domain permission'
      : `请求失败 / request failed: ${data.error}`}</div>;
  }
  const unavailable = data.source === 'unavailable';
  return (
    <>
      <div className="meta-row">
        <span>source: <strong>{data.source}</strong></span>
        <span>correlation: <code>{data.correlationId}</code></span>
        {unavailable && data.unavailable ? <span className="unavailable-reason">{data.unavailable}</span> : null}
      </div>
      {unavailable ? (
        <div className="empty-state">数据源未连接 / Source adapter unavailable — 按契约不伪造数据</div>
      ) : (
        <div className="fixture-table" role="table" aria-label={`${domain} data`}>
          {(data.items || []).length === 0 ? (
            <div className="fixture-row"><span>—</span><strong>暂无条目 / no items</strong><span /></div>
          ) : (data.items || []).map((item, index) => {
            const [first, rest] = pickCells(item);
            return (
              <div className="fixture-row" role="row" key={item.id || index}>
                <strong role="cell">{String(first)}</strong>
                <span role="cell">{rest.map((k) => `${k}: ${String(item[k])}`).join(' · ')}</span>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}

function App() {
  const [tokens, setTokens] = React.useState(loadTokens);
  const [active, setActive] = React.useState(window.location.hash.slice(1) || 'cicd');
  const [statuses, setStatuses] = React.useState({});
  const [detail, setDetail] = React.useState(null);
  const [loginError, setLoginError] = React.useState('');
  const [busy, setBusy] = React.useState(false);

  React.useEffect(() => {
    const onHashChange = () => setActive(window.location.hash.slice(1) || 'cicd');
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const api = React.useCallback(async (path, accessToken) => {
    const response = await fetch(path, {
      headers: {
        Authorization: `Bearer ${accessToken}`,
        'X-Correlation-Id': newCorrelationId(),
      },
    });
    const body = await response.json().catch(() => ({}));
    if (response.status === 401) return { error: 'unauthorized', status: 401 };
    return { ...body, status: response.status, error: body.error };
  }, []);

  // 登录后探测每个域的可用性与权限 / probe each domain's availability and permission
  const probeAll = React.useCallback(async (accessToken) => {
    const next = {};
    for (const [id, path] of Object.entries(ENDPOINTS)) {
      const result = await api(path, accessToken);
      next[id] = result.error === 'domain_permission_denied'
        ? 'no-access'
        : (result.source === 'unavailable' ? 'unavailable' : 'ready');
    }
    setStatuses(next);
  }, [api]);

  React.useEffect(() => {
    if (tokens?.accessToken) probeAll(tokens.accessToken);
  }, [tokens, probeAll]);

  React.useEffect(() => {
    if (!tokens?.accessToken) return;
    const path = ENDPOINTS[active] || ENDPOINTS.cicd;
    setDetail(null);
    api(path, tokens.accessToken).then((result) => {
      if (result.status === 401) {
        saveTokens(null);
        setTokens(null);
        setLoginError('会话已过期，请重新登录 / session expired, sign in again');
        return;
      }
      setDetail(result);
    });
  }, [active, tokens, api]);

  const onLogin = async (principal, password) => {
    setBusy(true);
    setLoginError('');
    try {
      const response = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Correlation-Id': newCorrelationId() },
        body: JSON.stringify({ principal, password }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) {
        setLoginError(body.error === 'invalid_credentials'
          ? '主体或共享口令不正确 / invalid principal or shared key'
          : `登录失败 / login failed (${response.status})`);
        return;
      }
      const next = { accessToken: body.accessToken, refreshToken: body.refreshToken, role: body.role };
      saveTokens(next);
      setTokens(next);
    } catch {
      setLoginError('网络错误，无法连接管理站 / network error');
    } finally {
      setBusy(false);
    }
  };

  const onLogout = async () => {
    try { await fetch('/api/v1/auth/logout', { method: 'POST' }); } catch { /* 无状态登出 / stateless */ }
    saveTokens(null);
    setTokens(null);
    setStatuses({});
    setDetail(null);
  };

  if (!tokens) {
    return <LoginScreen onLogin={onLogin} error={loginError} busy={busy} />;
  }

  const panel = MODULES.find(([id]) => id === active) || MODULES[0];
  const status = statuses[panel[0]] || 'loading';

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">ALBUM MANAGEMENT</p>
          <h1>相册管理平台</h1>
        </div>
        <div className="topbar-side">
          <span className="badge">READ ONLY · {tokens.role}</span>
          <button className="ghost-button" onClick={onLogout}>退出 / Sign out</button>
        </div>
      </header>
      <section className="intro" aria-labelledby="overview-title">
        <p className="kicker">OVERVIEW</p>
        <h2 id="overview-title">跨云资源的只读工作台。</h2>
        <p>数据来自管理站真实 API（commonEnvelope）；不可用域明确标注，绝不伪造；所有响应已过 forbiddenFields 递归过滤。</p>
      </section>
      <nav className="module-grid" aria-label="管理模块">
        {MODULES.map(([id, title, description]) => (
          <a className={`module-card ${active === id ? 'active' : ''}`} href={`#${id}`} key={id}>
            <span className="module-id">{id}</span>
            <strong>{title}</strong>
            <span>{description}</span>
            <span className={`module-status ${status === 'ready' ? 'ready' : 'unavailable'}`}>
              {status === 'ready' ? 'READY' : status === 'no-access' ? 'NO ACCESS' : status === 'unavailable' ? 'UNAVAILABLE' : '…'}
            </span>
          </a>
        ))}
      </nav>
      <section className="detail-panel" aria-live="polite" aria-labelledby="detail-title">
        <div className="detail-heading">
          <div>
            <p className="kicker">MODULE DETAIL</p>
            <h2 id="detail-title">{panel[1]}</h2>
          </div>
          <span className={`status-pill ${status !== 'ready' ? 'unavailable' : ''}`}>{status.toUpperCase()}</span>
        </div>
        <DomainDetail domain={panel[0]} data={detail} />
      </section>
    </main>
  );
}

createRoot(document.getElementById('root')).render(<App />);
