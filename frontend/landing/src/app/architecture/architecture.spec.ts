import { EDGES, NODES, NODE_H, NODE_W, edgeLine } from './architecture';

describe('architecture data', () => {
  it('has unique node ids and every edge joins two known nodes', () => {
    const ids = NODES.map((n) => n.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const e of EDGES) {
      expect(ids).toContain(e.from);
      expect(ids).toContain(e.to);
    }
  });

  it('describes every node for the component table', () => {
    for (const n of NODES) expect(n.description.length).toBeGreaterThan(10);
  });

  it('ends an edge on the border of the target box, not its centre', () => {
    const a = { id: 'a', label: 'A', group: 'service' as const, x: 0, y: 0, description: 'x'.repeat(11) };
    const b = { ...a, id: 'b', x: 300, y: 0 };
    // horizontal: starts at a's right edge, ends at b's left edge
    expect(edgeLine(a, b)).toEqual({ x1: NODE_W, y1: NODE_H / 2, x2: 300, y2: NODE_H / 2 });
  });
});
