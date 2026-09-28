import { TestBed } from '@angular/core/testing';
import { BridgepayMark } from './bridgepay-mark';

describe('BridgepayMark', () => {
  it('shows the wordmark as text next to a decorative glyph', () => {
    const fixture = TestBed.createComponent(BridgepayMark);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent?.trim()).toBe('BridgePay');
    expect(el.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
  });
});
