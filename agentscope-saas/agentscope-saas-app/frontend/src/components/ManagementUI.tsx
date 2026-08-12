import type { ReactNode } from 'react';
import {
  Database,
  FileClock,
  Gauge,
  RefreshCw,
  ShieldCheck,
  Users,
  type LucideIcon,
} from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';

const ADMIN_LINKS = [
  { path: '/admin/users', label: 'Users', icon: Users },
  { path: '/admin/usage', label: 'Usage', icon: Gauge },
  { path: '/admin/sandboxes', label: 'Sandboxes', icon: Database },
  { path: '/admin/memory-events', label: 'Memory', icon: FileClock },
  { path: '/admin/audit', label: 'Audit', icon: ShieldCheck },
];

export function AdminSectionNav() {
  const location = useLocation();
  const navigate = useNavigate();
  return (
    <nav className="admin-section-nav" aria-label="Administration sections">
      {ADMIN_LINKS.map(item => {
        const Icon = item.icon;
        return (
          <button
            key={item.path}
            className={location.pathname.startsWith(item.path) ? 'is-active' : ''}
            type="button"
            onClick={() => navigate(item.path)}
          >
            <Icon size={14} />
            {item.label}
          </button>
        );
      })}
    </nav>
  );
}

export function ManagementPage({ children, admin = false }: { children: ReactNode; admin?: boolean }) {
  return (
    <div className="management-page">
      {admin && <AdminSectionNav />}
      {children}
    </div>
  );
}

export function ManagementHeader({
  icon: Icon,
  title,
  description,
  actions,
}: {
  icon: LucideIcon;
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <header className="management-header">
      <span className="management-header__icon"><Icon size={18} /></span>
      <div className="management-header__copy">
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {actions && <div className="management-header__actions">{actions}</div>}
    </header>
  );
}

export function RefreshButton({ loading, onClick }: { loading: boolean; onClick: () => void }) {
  return (
    <button className="quiet-button" type="button" disabled={loading} onClick={onClick}>
      <RefreshCw className={loading ? 'is-spinning' : ''} size={14} />
      {loading ? 'Refreshing' : 'Refresh'}
    </button>
  );
}

export interface MetricItem {
  label: string;
  value: ReactNode;
  detail?: string;
  tone?: 'default' | 'success' | 'danger' | 'warning';
}

export function MetricStrip({ items }: { items: MetricItem[] }) {
  return (
    <section className="metric-strip" aria-label="Summary metrics">
      {items.map(item => (
        <div className={`metric-strip__item metric-strip__item--${item.tone ?? 'default'}`} key={item.label}>
          <span className="metric-strip__label">{item.label}</span>
          <strong>{item.value}</strong>
          {item.detail && <span className="metric-strip__detail">{item.detail}</span>}
        </div>
      ))}
    </section>
  );
}

export function FilterBar({ children, actions }: { children: ReactNode; actions?: ReactNode }) {
  return (
    <section className="filter-bar" aria-label="Filters">
      <div className="filter-bar__fields">{children}</div>
      {actions && <div className="filter-bar__actions">{actions}</div>}
    </section>
  );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <label className="management-field">
      <span>{label}</span>
      {children}
    </label>
  );
}

export function Notice({ tone, children }: { tone: 'error' | 'info' | 'success'; children: ReactNode }) {
  return <div className={`management-notice management-notice--${tone}`}>{children}</div>;
}

export function DataPanel({ title, actions, children }: { title?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="data-panel">
      {(title || actions) && (
        <header className="data-panel__header">
          {title && <h2>{title}</h2>}
          <span className="data-panel__spacer" />
          {actions}
        </header>
      )}
      {children}
    </section>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <div className="management-empty">{children}</div>;
}

export function StatusBadge({
  status,
  tone,
}: {
  status: string;
  tone?: 'neutral' | 'success' | 'danger' | 'warning' | 'info';
}) {
  return <span className={`status-badge status-badge--${tone ?? 'neutral'}`}>{status}</span>;
}
