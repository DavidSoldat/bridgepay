import { buildTimeline, orderRef, standingReason } from './timeline';
import { OpsInstallment, OpsPlan } from '../../shared/models/shopper';

function inst(n: number, status: string, extra: Partial<OpsInstallment> = {}): OpsInstallment {
  return { sequenceNumber: n, dueDate: `2026-01-0${n}`, amount: 17.44, status, paidAt: null, updatedAt: `2026-02-0${n}T09:00:00Z`, ...extra };
}

function plan(status: string, installments: OpsInstallment[], extra: Partial<OpsPlan> = {}): OpsPlan {
  return {
    planId: 'p-1', applicationId: 'app-0000-abcd1234', status, totalAmount: 69.76, installmentCount: 4,
    installmentAmount: 17.44, createdAt: '2026-01-01T08:00:00Z', updatedAt: '2026-03-01T10:00:00Z', installments, ...extra,
  };
}

describe('buildTimeline', () => {
  it('maps every installment status to one dated entry and skips scheduled ones', () => {
    const entries = buildTimeline([plan('ACTIVE', [
      inst(1, 'PAID', { paidAt: '2026-01-01T10:00:00Z' }),
      inst(2, 'LATE'),
      inst(3, 'MISSED'),
      inst(4, 'SCHEDULED'),
    ])]);

    expect(entries.map((e) => e.text)).toEqual([
      'Payment 3 of 4 missed — $17.44',
      'Payment 2 of 4 late — $17.44',
      'Payment 1 of 4 paid — $17.44',
    ]);
    expect(entries.map((e) => e.tone)).toEqual(['declined', 'review', 'paid']);
    expect(entries[0].at).toBe('2026-01-03T00:00:00'); // a due date is a local calendar day
    expect(entries[2].at).toBe('2026-01-01T10:00:00Z');
  });

  it('dates refunded and cancelled installments by their update, and adds plan-level events', () => {
    const entries = buildTimeline([plan('REFUNDED', [inst(1, 'REFUNDED'), inst(2, 'CANCELLED')])]);

    expect(entries.map((e) => e.text)).toEqual([
      'Plan refunded',
      'Payment 2 of 4 cancelled — $17.44',
      'Payment 1 of 4 refunded — $17.44',
    ]);
    expect(entries[1].at).toBe('2026-02-02T09:00:00Z');
  });

  it('keeps the original payment of a refunded installment, so ops can still see when the shopper paid', () => {
    const entries = buildTimeline([plan('REFUNDED', [inst(1, 'REFUNDED', { paidAt: '2026-01-01T10:00:00Z' })])]);

    expect(entries.map((e) => [e.at, e.text])).toEqual([
      ['2026-03-01T10:00:00Z', 'Plan refunded'],
      ['2026-02-01T09:00:00Z', 'Payment 1 of 4 refunded — $17.44'],
      ['2026-01-01T10:00:00Z', 'Payment 1 of 4 paid — $17.44'],
    ]);
  });

  it('labels each terminal plan status and leaves an active plan without one', () => {
    const text = (status: string) => buildTimeline([plan(status, [])]).map((e) => e.text);
    expect(text('COMPLETED')).toEqual(['Plan completed']);
    expect(text('DEFAULTED')).toEqual(['Plan defaulted']);
    expect(text('CANCELLED')).toEqual(['Order cancelled — first payment never made']);
    expect(text('ACTIVE')).toEqual([]);
  });

  it('merges plans newest first, with a plan event before a payment at the same moment', () => {
    const older = plan('COMPLETED', [inst(4, 'PAID', { paidAt: '2026-03-01T10:00:00Z' })], { applicationId: 'old' });
    const newer = plan('ACTIVE', [inst(1, 'PAID', { paidAt: '2026-04-01T10:00:00Z' })], { applicationId: 'new' });

    expect(buildTimeline([older, newer]).map((e) => [e.applicationId, e.text])).toEqual([
      ['new', 'Payment 1 of 4 paid — $17.44'],
      ['old', 'Plan completed'],
      ['old', 'Payment 4 of 4 paid — $17.44'],
    ]);
  });
});

describe('standingReason', () => {
  it('summarizes the history the policy overlay scores', () => {
    expect(standingReason({ completedPlans: 2, latePaymentCount: 1, defaultedPlans: 0, onTimeRate: 0.9 }))
      .toBe('2 completed plans · 1 late or missed payment · no defaults');
    expect(standingReason({ completedPlans: 1, latePaymentCount: 0, defaultedPlans: 2, onTimeRate: 1 }))
      .toBe('1 completed plan · no late payments · 2 defaulted plans');
  });
});

describe('orderRef', () => {
  it('is the last 8 characters of the application id', () => {
    expect(orderRef('0192f0aa-1111-7000-8000-00ab12cd34ef')).toBe('12cd34ef');
  });
});
