import {
  CHART, areaPath, bandCenter, bandCenterPercent, bandWidth, formatValue, linePath, linearScale, niceTicks, roundedBarPath,
} from './scale';

describe('chart scale', () => {
  it('picks round ticks from zero past the maximum', () => {
    expect(niceTicks(430)).toEqual([0, 200, 400, 600]);
    expect(niceTicks(7)).toEqual([0, 2, 4, 6, 8]);
    expect(niceTicks(0)).toEqual([0]);
  });

  it('keeps count ticks whole', () => {
    expect(niceTicks(1, 4, true)).toEqual([0, 1]);
    expect(niceTicks(1)).toEqual([0, 0.5, 1]);
  });

  it('maps a domain onto a range, inverted for SVG y', () => {
    const y = linearScale([0, 10], [100, 0]);
    expect(y(5)).toBe(50);
    expect(y(10)).toBe(0);
    expect(linearScale([3, 3], [0, 100])(3)).toBe(0);
  });

  it('builds line and closed area paths', () => {
    expect(linePath([[0, 10], [5, 2.25]])).toBe('M0,10L5,2.3');
    expect(areaPath([[0, 10], [5, 2]], 20)).toBe('M0,10L5,2L5,20L0,20Z');
    expect(areaPath([], 20)).toBe('');
  });

  it('rounds the top of a bar and draws nothing for a zero bar', () => {
    expect(roundedBarPath(10, 8, 50, 100)).toBe('M10,100V54Q10,50 14,50H14Q18,50 18,54V100Z');
    expect(roundedBarPath(10, 8, 100, 100)).toBe('');
  });

  it('centres bands across the plot area', () => {
    const plot = CHART.width - CHART.padLeft - CHART.padRight;
    expect(bandWidth(4)).toBe(plot / 4);
    expect(bandCenter(0, 4)).toBe(CHART.padLeft + plot / 8);
    expect(bandCenterPercent(0, 4)).toBeCloseTo(((CHART.padLeft + plot / 8) / CHART.width) * 100);
  });

  it('formats money and counts', () => {
    expect(formatValue(1234.5, 'money')).toBe('$1,235');
    expect(formatValue(1234.5, 'money', true)).toBe('$1,234.50');
    expect(formatValue(3, 'count')).toBe('3');
  });
});
