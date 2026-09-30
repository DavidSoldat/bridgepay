export type NodeGroup = 'frontend' | 'service' | 'infra' | 'external';

export interface ArchNode {
  id: string;
  label: string;
  group: NodeGroup;
  /** Top-left corner in diagram units. */
  x: number;
  y: number;
  description: string;
}

export interface ArchEdge {
  from: string;
  to: string;
}

export const NODE_W = 160;
export const NODE_H = 44;
export const DIAGRAM_W = 960;
export const DIAGRAM_H = 480;

export const NODES: ArchNode[] = [
  { id: 'storefront', label: 'Storefront', group: 'frontend', x: 20, y: 120, description: 'Demo merchant shop with the embedded Pay in 4 checkout and a shopper account page.' },
  { id: 'main-app', label: 'Main app', group: 'frontend', x: 20, y: 260, description: 'Role-gated ops dashboard (review queue, score explanations, failed events) and merchant dashboard (sales, payouts).' },
  { id: 'keycloak', label: 'Keycloak', group: 'infra', x: 20, y: 400, description: 'OIDC login with PKCE; realm roles for shopper, merchant and ops.' },
  { id: 'gateway', label: 'API Gateway', group: 'service', x: 215, y: 190, description: 'Spring Cloud Gateway: path routing, JWT validation, rate limiting, correlation IDs.' },
  { id: 'applicant', label: 'Applicant', group: 'service', x: 410, y: 30, description: 'Shopper signup and identity profile. No Kafka, by design.' },
  { id: 'application', label: 'Application', group: 'service', x: 410, y: 130, description: 'Checkout flow, decision finalization, ops review, merchant sales and payouts. Publishes decisions through a transactional outbox.' },
  { id: 'repayment', label: 'Repayments', group: 'service', x: 410, y: 270, description: 'Repayment Reconciliation: creates installment plans in Paddle, verifies Paddle webhooks, tracks repayments. Failed events land in an ops retry queue.' },
  { id: 'postgres', label: 'PostgreSQL', group: 'infra', x: 410, y: 400, description: 'One instance, a separate schema per service; no cross-schema foreign keys.' },
  { id: 'risk', label: 'Credit Risk Engine', group: 'service', x: 605, y: 130, description: 'Scores applications with a logistic-regression model (ONNX) plus a policy overlay for on-platform repayment history. Fails safe to manual review.' },
  { id: 'kafka', label: 'Kafka', group: 'infra', x: 605, y: 300, description: 'Event backbone (KRaft): decisions and repayment events, published through transactional outboxes.' },
  { id: 'paddle', label: 'Paddle', group: 'external', x: 605, y: 400, description: 'Payment provider (sandbox): installment transactions, subscriptions and signed webhooks.' },
  { id: 'bureau', label: 'Mock Credit Bureau', group: 'service', x: 790, y: 60, description: 'Stands in for a real bureau: deterministically maps each applicant to a held-out row of the Kaggle Give Me Some Credit dataset.' },
  { id: 'redis', label: 'Redis', group: 'infra', x: 790, y: 170, description: 'Score cache for the Credit Risk Engine.' },
  { id: 'notifications', label: 'Notifications', group: 'service', x: 790, y: 300, description: 'Idempotent Kafka consumer for decision and repayment events.' },
];

/** The README's Mermaid chart. Every service → PostgreSQL is left out: the PostgreSQL box says so. */
export const EDGES: ArchEdge[] = [
  { from: 'storefront', to: 'gateway' },
  { from: 'main-app', to: 'gateway' },
  { from: 'storefront', to: 'keycloak' },
  { from: 'main-app', to: 'keycloak' },
  { from: 'gateway', to: 'applicant' },
  { from: 'gateway', to: 'application' },
  { from: 'gateway', to: 'repayment' },
  { from: 'application', to: 'risk' },
  { from: 'risk', to: 'bureau' },
  { from: 'risk', to: 'redis' },
  { from: 'risk', to: 'repayment' },
  { from: 'application', to: 'kafka' },
  { from: 'kafka', to: 'repayment' },
  { from: 'repayment', to: 'kafka' },
  { from: 'kafka', to: 'notifications' },
  { from: 'repayment', to: 'applicant' },
  { from: 'repayment', to: 'paddle' },
];

/** Where the line from centre to centre crosses a box border, so arrowheads sit on the target's edge. */
function onBorder(cx: number, cy: number, dx: number, dy: number): [number, number] {
  const scale = 1 / Math.max(Math.abs(dx) / (NODE_W / 2), Math.abs(dy) / (NODE_H / 2));
  return [cx + dx * scale, cy + dy * scale];
}

export function edgeLine(from: ArchNode, to: ArchNode): { x1: number; y1: number; x2: number; y2: number } {
  const [ax, ay] = [from.x + NODE_W / 2, from.y + NODE_H / 2];
  const [bx, by] = [to.x + NODE_W / 2, to.y + NODE_H / 2];
  const [x1, y1] = onBorder(ax, ay, bx - ax, by - ay);
  const [x2, y2] = onBorder(bx, by, ax - bx, ay - by);
  return { x1, y1, x2, y2 };
}
