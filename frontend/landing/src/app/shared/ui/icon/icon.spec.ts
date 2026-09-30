import { TestBed } from '@angular/core/testing';
import { Icon } from './icon';

function render(inputs: Record<string, string>): HTMLElement {
  const fixture = TestBed.createComponent(Icon);
  for (const [key, value] of Object.entries(inputs)) fixture.componentRef.setInput(key, value);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('Icon', () => {
  it('renders real SVG paths for a known icon, hidden from screen readers', () => {
    const el = render({ name: 'check' });
    const svg = el.querySelector('svg')!;
    const path = el.querySelector('path')!;
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(path.getAttribute('d')).toBe('M20 6 9 17l-5-5');
    expect(path.namespaceURI).toBe('http://www.w3.org/2000/svg');
  });

  it('becomes a labelled image when given a label', () => {
    const svg = render({ name: 'copy', label: 'Close' }).querySelector('svg')!;
    expect(svg.getAttribute('aria-hidden')).toBeNull();
    expect(svg.getAttribute('role')).toBe('img');
    expect(svg.getAttribute('aria-label')).toBe('Close');
  });

  it('renders nothing for an unknown name', () => {
    expect(render({ name: 'no-such-icon' }).querySelector('svg')).toBeNull();
  });
});
