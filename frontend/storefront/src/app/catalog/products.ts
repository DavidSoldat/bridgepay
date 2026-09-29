export interface Product {
  id: string;
  name: string;
  price: number;
  swatchColor: string; // shown behind the photo while it loads
  image: string;
  imageAlt: string;
  description: string;
  features: string[];
}

// Photos and their alt text: public/products/CREDITS.md.
export const PRODUCTS: Product[] = [
  {
    id: 'basin-rain-jacket',
    name: 'Basin Rain Jacket',
    price: 198,
    swatchColor: '#3F5843',
    image: '/products/basin-rain-jacket.webp',
    imageAlt: 'Person walking in the rain in a hooded black rain jacket beaded with water',
    description: 'A three-layer shell for all-day rain, cut long enough to sit below a hip belt.',
    features: [
      'Waterproof, breathable 3-layer fabric',
      'Pit zips for venting on climbs',
      'Adjustable hood that fits over a helmet',
      'Packs into its own chest pocket',
    ],
  },
  {
    id: 'ridgeline-trail-pack',
    name: 'Ridgeline Trail Pack',
    price: 164,
    swatchColor: '#B08D57',
    image: '/products/ridgeline-trail-pack.webp',
    imageAlt: 'Hiker with a red trekking pack facing snowy granite peaks',
    description: 'A 45-litre trekking pack that carries a multi-day load close to the back.',
    features: [
      '45 L main compartment with lid pocket',
      'Adjustable back length and padded hip belt',
      'Side bottle pockets and compression straps',
      'Recycled ripstop nylon',
    ],
  },
  {
    id: 'camp-multitool',
    name: 'Camp Multitool',
    price: 58,
    swatchColor: '#6B7280',
    image: '/products/camp-multitool.webp',
    imageAlt: 'Opened steel multitool with pliers, knife and saw resting on timber beams',
    description: 'Twelve tools in a pocketable steel body, from needle-nose pliers to a wood saw.',
    features: [
      '12 tools including pliers, knife and saw',
      'Locking blades',
      'Stainless steel, 190 g',
      'Belt sheath included',
    ],
  },
  {
    id: 'insulated-field-bottle',
    name: 'Insulated Field Bottle',
    price: 42,
    swatchColor: '#A24E3A',
    image: '/products/insulated-field-bottle.webp',
    imageAlt: 'Hand carrying a stainless steel bottle by its loop in a sunlit forest',
    description: 'Double-wall steel that keeps coffee hot for 12 hours and water cold for 24.',
    features: ['750 ml capacity', 'Hot 12 h, cold 24 h', 'Leak-proof screw cap with carry loop', 'Dishwasher safe'],
  },
];

export function findProduct(id: string): Product | undefined {
  return PRODUCTS.find((p) => p.id === id);
}
