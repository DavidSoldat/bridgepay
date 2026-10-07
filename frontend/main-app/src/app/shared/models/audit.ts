export type AuditOutcome = 'ALLOWED' | 'DENIED' | 'FAILED';

export interface AuditEntry {
  id: string;
  occurredAt: string;
  correlationId: string | null;
  actorUsername: string | null;
  actorRole: 'OPS' | 'MERCHANT' | 'OTHER';
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: string | null;
  httpMethod: string;
  path: string;
  status: number;
}

export interface AuditFilters {
  actor?: string | null;
  action?: string | null;
  outcome?: AuditOutcome | null;
  from?: string | null;
  to?: string | null;
  targetType?: string | null;
  targetId?: string | null;
  page?: number;
}

export const AUDIT_ACTION_LABELS: Record<string, string> = {
  SEARCH_SHOPPERS: 'Searched shoppers',
  VIEW_SHOPPER_PROFILE: 'Viewed shopper profile',
  VIEW_SHOPPER_APPLICATIONS: "Viewed shopper's applications",
  VIEW_CREDIT_STANDING: 'Viewed credit standing',
  VIEW_REPAYMENTS: 'Viewed repayments',
  VIEW_NOTIFICATIONS: 'Viewed notifications',
  VIEW_CASE_FILE: 'Viewed case file',
  VIEW_APPLICATION: 'Viewed application',
  VIEW_REPAYMENT_PLAN: 'Viewed repayment plan',
  DECIDE_APPLICATION: 'Decided application',
  RETRY_FAILED_EVENT: 'Retried failed event',
  REFUND_ORDER: 'Refunded order',
  EXPORT_SALES: 'Exported sales CSV',
  VIEW_AUDIT_LOG: 'Viewed audit log',
};

export const ROLE_LABELS: Record<AuditEntry['actorRole'], string> = { OPS: 'Ops', MERCHANT: 'Merchant', OTHER: 'Other' };

export function outcomeOf(status: number): AuditOutcome {
  if (status === 403) return 'DENIED';
  return status >= 400 ? 'FAILED' : 'ALLOWED';
}

/** Router link for a target, or null when the target has no page of its own. */
export function auditTargetLink(e: Pick<AuditEntry, 'targetType' | 'targetId'>): string[] | null {
  if (!e.targetId) return null;
  if (e.targetType === 'SHOPPER') return ['/ops/shoppers', e.targetId];
  if (e.targetType === 'APPLICATION') return ['/ops', e.targetId];
  return null;
}
