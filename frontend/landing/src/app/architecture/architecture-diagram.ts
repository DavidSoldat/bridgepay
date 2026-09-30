import { Component } from '@angular/core';
import { DIAGRAM_H, DIAGRAM_W, EDGES, NODES, NODE_H, NODE_W, NodeGroup, edgeLine } from './architecture';

const BOX: Record<NodeGroup, string> = {
  frontend: 'fill-blush stroke-coral',
  service: 'fill-surface stroke-coral',
  infra: 'fill-neutral-bg stroke-neutral',
  external: 'fill-paid-bg stroke-paid',
};

@Component({
  selector: 'app-architecture-diagram',
  templateUrl: './architecture-diagram.html',
})
export class ArchitectureDiagram {
  protected readonly nodes = NODES;
  protected readonly W = DIAGRAM_W;
  protected readonly H = DIAGRAM_H;
  protected readonly nodeW = NODE_W;
  protected readonly nodeH = NODE_H;
  protected readonly box = BOX;
  protected readonly lines = EDGES.map((e) => {
    const byId = (id: string) => NODES.find((n) => n.id === id)!;
    return { key: `${e.from}-${e.to}`, ...edgeLine(byId(e.from), byId(e.to)) };
  });
}
