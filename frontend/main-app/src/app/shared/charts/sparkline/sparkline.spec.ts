import { TestBed } from '@angular/core/testing';
import { Sparkline } from './sparkline';

describe('Sparkline', () => {
  it('draws one decorative line through the values', () => {
    const fixture = TestBed.createComponent(Sparkline);
    fixture.componentRef.setInput('values', [0, 10, 5]);
    fixture.detectChanges();
    const svg = (fixture.nativeElement as HTMLElement).querySelector('svg')!;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.querySelector('path')?.getAttribute('d')).toBe('M0,22L50,2L100,12');
  });

  it('stays flat and finite for all-zero values', () => {
    const fixture = TestBed.createComponent(Sparkline);
    fixture.componentRef.setInput('values', [0, 0]);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('path')?.getAttribute('d')).toBe('M0,22L100,22');
  });
});
