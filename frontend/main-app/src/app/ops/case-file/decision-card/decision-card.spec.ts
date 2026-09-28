import { TestBed } from '@angular/core/testing';
import { DecisionCard } from './decision-card';
import { CaseDecision } from '../../../shared/models/application-case';

function render(decision: CaseDecision): HTMLElement {
  const fixture = TestBed.createComponent(DecisionCard);
  fixture.componentRef.setInput('decision', decision);
  fixture.componentRef.setInput('status', 'APPROVED');
  fixture.componentRef.setInput('decisionAt', '2026-09-28T10:00:00Z');
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('DecisionCard', () => {
  it('credits the model for an automatic decision', () => {
    const el = render({ source: 'MODEL', decidedBy: null, reviewerNote: null });
    expect(el.querySelector('[data-testid="decided-by"]')?.textContent).toContain('Model (automatic)');
  });

  it('names the ops user who decided', () => {
    const el = render({ source: 'OPS', decidedBy: 'ops1', reviewerNote: 'income verified' });
    expect(el.querySelector('[data-testid="decided-by"]')?.textContent).toContain('ops1');
    expect(el.querySelector('[data-testid="reviewer-note"]')?.textContent).toContain('income verified');
  });

  it('says the decision maker is unknown for decisions made before decision records', () => {
    const el = render({ source: null, decidedBy: null, reviewerNote: null });
    expect(el.querySelector('[data-testid="decided-by"]')?.textContent).toContain('Unknown (decided before decision records)');
  });

  it('says so when there is no note', () => {
    const el = render({ source: 'OPS', decidedBy: 'ops1', reviewerNote: null });
    expect(el.querySelector('[data-testid="reviewer-note"]')?.textContent?.trim()).toBe('No note');
  });
});
