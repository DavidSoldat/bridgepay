import { Component, computed, input } from '@angular/core';

type Tone = 'approved' | 'review' | 'declined' | 'paid' | 'neutral';

// One map for every backend status enum (application, payout, installment, plan, failed event).
const STATUSES: Record<string, { label: string; tone: Tone }> = {
  APPROVED: { label: 'Approved', tone: 'approved' },
  ACTIVE: { label: 'Active', tone: 'approved' },
  RESOLVED: { label: 'Resolved', tone: 'approved' },
  PENDING: { label: 'Pending', tone: 'review' },
  MANUAL_REVIEW: { label: 'In review', tone: 'review' },
  RETRYING: { label: 'Retrying', tone: 'review' },
  LATE: { label: 'Late', tone: 'review' },
  DECLINED: { label: 'Declined', tone: 'declined' },
  DEFAULTED: { label: 'Defaulted', tone: 'declined' },
  FAILED: { label: 'Failed', tone: 'declined' },
  MISSED: { label: 'Missed', tone: 'declined' },
  PAID: { label: 'Paid', tone: 'paid' },
  COMPLETED: { label: 'Completed', tone: 'paid' },
  CANCELLED: { label: 'Cancelled', tone: 'neutral' },
  SCHEDULED: { label: 'Scheduled', tone: 'neutral' },
};

// Whole class names so Tailwind's scanner finds them.
const TONE_CLASSES: Record<Tone, string> = {
  approved: 'bg-approved-bg text-approved',
  review: 'bg-review-bg text-review',
  declined: 'bg-declined-bg text-declined',
  paid: 'bg-paid-bg text-paid',
  neutral: 'bg-neutral-bg text-neutral',
};

@Component({
  selector: 'app-status-badge',
  templateUrl: './status-badge.html',
  styleUrl: './status-badge.css',
})
export class StatusBadge {
  status = input.required<string>();

  protected readonly entry = computed(
    () => STATUSES[this.status()] ?? { label: this.status(), tone: 'neutral' as Tone },
  );
  protected readonly classes = computed(() => TONE_CLASSES[this.entry().tone]);
}
