import { PRODUCTS, findProduct } from './products';

describe('products', () => {
  it('gives every product a photo, alt text, a description and 3-4 features', () => {
    for (const p of PRODUCTS) {
      expect(p.image).toBe(`/products/${p.id}.webp`);
      expect(p.imageAlt.length).toBeGreaterThan(10);
      expect(p.description.length).toBeGreaterThan(20);
      expect(p.features.length).toBeGreaterThanOrEqual(3);
      expect(p.features.length).toBeLessThanOrEqual(4);
    }
  });

  it('keeps the four products and their prices', () => {
    expect(PRODUCTS.map((p) => [p.id, p.price])).toEqual([
      ['basin-rain-jacket', 198],
      ['ridgeline-trail-pack', 164],
      ['camp-multitool', 58],
      ['insulated-field-bottle', 42],
    ]);
  });

  it('finds a product by id and misses an unknown one', () => {
    expect(findProduct('camp-multitool')?.name).toBe('Camp Multitool');
    expect(findProduct('nope')).toBeUndefined();
  });
});
