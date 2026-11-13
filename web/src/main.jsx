import React from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';

const modules = [
  ['cicd', 'CI/CD', '工作流与发布状态', 'ready'],
  ['resources', 'Resources', '资源健康与归属', 'ready'],
  ['logs', 'Logs', '结构化日志查询', 'unavailable'],
  ['metrics', 'Metrics', '指标与延迟', 'unavailable'],
  ['security', 'Security', '安全姿态与策略', 'ready'],
  ['billing', 'Billing', '只读成本摘要', 'unavailable'],
  ['audit', 'Audit', '审计事件', 'ready'],
];

const fixtures = {
  cicd: {
    title: 'CI/CD',
    summary: '最近一次部署通过，当前没有待发布变更。',
    rows: [['album', 'main', 'passed'], ['album-mgmt', 'main', 'fixture']],
  },
  resources: {
    title: 'Resources',
    summary: '基础设施 inventory 使用安全摘要，不展示凭证或原始 provider response。',
    rows: [['ECS Express', 'album-mgmt', 'planned'], ['CloudFront', 'cx.chenyouyi.top', 'planned'], ['ECR', 'album-mgmt', 'ready']],
  },
  logs: {
    title: 'Logs',
    summary: 'Loki adapter 尚未连接；不会伪造实时日志。',
    rows: [],
    unavailable: true,
  },
  metrics: {
    title: 'Metrics',
    summary: 'Prometheus adapter 尚未连接；当前不提供虚构的 latency 数值。',
    rows: [],
    unavailable: true,
  },
  security: {
    title: 'Security',
    summary: '当前原型保持 read-only，生产写操作、IAM 修改与 Terraform apply 未开放。',
    rows: [['IAM', 'read-only boundary', 'enforced'], ['TLS', 'CloudFront edge', 'planned'], ['Secrets', 'runtime references', 'enforced']],
  },
  billing: {
    title: 'Billing',
    summary: 'Cost Explorer adapter 尚未连接；成本数字不可用。',
    rows: [],
    unavailable: true,
  },
  audit: {
    title: 'Audit',
    summary: '审计事件使用最小字段 fixture，敏感值已省略。',
    rows: [['13:40', 'management shell', 'viewed'], ['13:41', 'infra validation', 'passed']],
  },
};

function App() {
  const [active, setActive] = React.useState(window.location.hash.slice(1) || 'cicd');
  React.useEffect(() => {
    const onHashChange = () => setActive(window.location.hash.slice(1) || 'cicd');
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  const panel = fixtures[active] || fixtures.cicd;

  return (
    <main className="shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">ALBUM MANAGEMENT</p>
          <h1>相册管理平台</h1>
        </div>
        <span className="badge">READ ONLY · FIXTURE</span>
      </header>
      <section className="intro" aria-labelledby="overview-title">
        <p className="kicker">OVERVIEW</p>
        <h2 id="overview-title">跨云资源的只读工作台。</h2>
        <p>当前使用本地 fixture；生产写操作、IAM 修改和 Terraform apply 均未开放。</p>
      </section>
      <nav className="module-grid" aria-label="管理模块">
        {modules.map(([id, title, description, status]) => (
          <a className={`module-card ${active === id ? 'active' : ''}`} href={`#${id}`} key={id}>
            <span className="module-id">{id}</span>
            <strong>{title}</strong>
            <span>{description}</span>
            <span className={`module-status ${status}`}>{status === 'ready' ? 'FIXTURE' : 'UNAVAILABLE'}</span>
          </a>
        ))}
      </nav>
      <section className="detail-panel" aria-live="polite" aria-labelledby="detail-title">
        <div className="detail-heading">
          <div>
            <p className="kicker">MODULE DETAIL</p>
            <h2 id="detail-title">{panel.title}</h2>
          </div>
          <span className={`status-pill ${panel.unavailable ? 'unavailable' : ''}`}>
            {panel.unavailable ? 'UNAVAILABLE' : 'READ ONLY'}
          </span>
        </div>
        <p className="detail-summary">{panel.summary}</p>
        {panel.unavailable ? (
          <div className="empty-state">数据源未连接 / Source adapter unavailable</div>
        ) : (
          <div className="fixture-table" role="table" aria-label={`${panel.title} fixture`}>
            {panel.rows.map(([time, resource, state]) => (
              <div className="fixture-row" role="row" key={`${time}-${resource}`}>
                <span role="cell">{time}</span>
                <strong role="cell">{resource}</strong>
                <span role="cell" className="row-state">{state}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </main>
  );
}

createRoot(document.getElementById('root')).render(<App />);
