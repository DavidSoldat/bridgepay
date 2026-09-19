export interface Product {
  id: string;
  name: string;
  price: number;
  swatchColor: string;
}

export const PRODUCTS: Product[] = [
  { id: 'basin-rain-jacket', name: 'Basin Rain Jacket', price: 198, swatchColor: '#3F5843' },
  { id: 'ridgeline-trail-pack', name: 'Ridgeline Trail Pack', price: 164, swatchColor: '#B08D57' },
  { id: 'camp-multitool', name: 'Camp Multitool', price: 58, swatchColor: '#6B7280' },
  { id: 'insulated-field-bottle', name: 'Insulated Field Bottle', price: 42, swatchColor: '#A24E3A' },
];
