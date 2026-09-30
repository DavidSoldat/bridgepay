import { TestBed } from '@angular/core/testing';
import { ArchitectureDiagram } from './architecture-diagram';
import { EDGES, NODES } from './architecture';

describe('ArchitectureDiagram', () => {
  function render() {
    const f = TestBed.createComponent(ArchitectureDiagram);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }

  it('draws one labelled box per node and one line per edge', () => {
    const el = render();
    const boxes = Array.from(el.querySelectorAll('[data-node]'));
    expect(boxes.map((b) => b.getAttribute('data-node'))).toEqual(NODES.map((n) => n.id));
    expect(boxes[0].textContent).toContain(NODES[0].label);
    expect(el.querySelectorAll('[data-edge]').length).toBe(EDGES.length);
  });

  it('is an image described by the visible component table, with a row per node', () => {
    const el = render();
    const svg = el.querySelector('svg')!;
    expect(svg.getAttribute('role')).toBe('img');
    const table = el.querySelector('#' + svg.getAttribute('aria-describedby'))!;
    expect(table.tagName).toBe('TABLE');
    expect(table.classList).not.toContain('sr-only');
    expect(table.querySelectorAll('tbody tr').length).toBe(NODES.length);
  });

  it('scrolls sideways inside its own container on narrow screens', () => {
    expect(render().querySelector('svg')!.parentElement!.className).toContain('overflow-x-auto');
  });

  it('is the #architecture section', () => {
    expect(render().querySelector('section#architecture')).not.toBeNull();
  });
});
