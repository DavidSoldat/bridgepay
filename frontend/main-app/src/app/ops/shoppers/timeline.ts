import { OpsInstallment, OpsPlan, RepaymentHistory } from '../../shared/models/shopper';

export type TimelineTone = 'approved' | 'review' | 'declined' | 'paid' | 'neutral';

export interface TimelineEntry {
  /** ISO instant, or a local calendar day as `yyyy-MM-ddT00:00:00` (no Z) for due dates. */
  at: string;
  tone: TimelineTone;
  text: string;
  applicationId: string;
}

const INSTALLMENT_EVENTS: Record<string, { verb: string; tone: TimelineTone; when: (i: OpsInstallment) => string | null }> = {
  PAID: { verb: 'paid', tone: 'paid', when: (i) => i.paidAt },
  LATE: { verb: 'late', tone: 'review', when: (i) => `${i.dueDate}T00:00:00` },
  MISSED: { verb: 'missed', tone: 'declined', when: (i) => `${i.dueDate}T00:00:00` },
  REFUNDED: { verb: 'refunded', tone: 'neutral', when: (i) => i.updatedAt },
  CANCELLED: { verb: 'cancelled', tone: 'neutral', when: (i) => i.updatedAt },
};

const PLAN_EVENTS: Record<string, { text: string; tone: TimelineTone }> = {
  COMPLETED: { text: 'Plan completed', tone: 'paid' },
  DEFAULTED: { text: 'Plan defaulted', tone: 'declined' },
  CANCELLED: { text: 'Order cancelled — first payment never made', tone: 'neutral' },
  REFUNDED: { text: 'Plan refunded', tone: 'neutral' },
};

/** Every dated thing that happened across a shopper's plans, newest first. Scheduled installments aren't events. */
export function buildTimeline(plans: OpsPlan[]): TimelineEntry[] {
  const entries: (TimelineEntry & { planEvent: boolean })[] = [];
  for (const plan of plans) {
    const planEvent = PLAN_EVENTS[plan.status];
    if (planEvent) {
      entries.push({ at: plan.updatedAt, ...planEvent, applicationId: plan.applicationId, planEvent: true });
    }
    for (const i of plan.installments) {
      const event = INSTALLMENT_EVENTS[i.status];
      const at = event?.when(i);
      if (!event || !at) continue;
      entries.push({
        at,
        tone: event.tone,
        text: `Payment ${i.sequenceNumber} of ${plan.installmentCount} ${event.verb} — $${i.amount.toFixed(2)}`,
        applicationId: plan.applicationId,
        planEvent: false,
      });
    }
  }
  return entries
    .sort((a, b) => new Date(b.at).getTime() - new Date(a.at).getTime() || Number(b.planEvent) - Number(a.planEvent))
    .map(({ planEvent, ...entry }) => entry);
}

const plural = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

/** The repayment history the credit-risk policy overlay scores, in one line. */
export function standingReason(h: RepaymentHistory): string {
  return [
    plural(h.completedPlans, 'completed plan'),
    h.latePaymentCount ? plural(h.latePaymentCount, 'late or missed payment') : 'no late payments',
    h.defaultedPlans ? plural(h.defaultedPlans, 'defaulted plan') : 'no defaults',
  ].join(' · ');
}

/** Same short order reference the storefront shows the shopper. */
export function orderRef(applicationId: string): string {
  return applicationId.slice(-8);
}
