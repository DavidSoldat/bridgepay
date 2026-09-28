import { TestBed } from '@angular/core/testing';
import { ToastOutlet } from './toast-outlet';
import { ToastService } from '../toast-service';

describe('ToastOutlet', () => {
  it('announces toasts politely and dismisses one from its close button', () => {
    const fixture = TestBed.createComponent(ToastOutlet);
    const service = TestBed.inject(ToastService);
    service.show('success', 'Application approved');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;

    const region = el.querySelector('[role="status"]')!;
    expect(region.getAttribute('aria-live')).toBe('polite');
    expect(region.textContent).toContain('Application approved');
    expect(el.querySelector('[data-kind="success"]')).not.toBeNull();

    (el.querySelector('button[aria-label="Dismiss"]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(region.textContent).not.toContain('Application approved');
  });
});
