import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * Các trang vẽ biểu đồ kéo theo recharts (~400 kB). Nếu App.tsx import tĩnh một trong số chúng, recharts rơi vào bundle
 * chính và mọi trang (kể cả trang landing và trang link chết) phải tải gấp đôi. Đã từng xảy ra khi thêm trang Tổng quan
 * (bundle chính 400 kB -> 814 kB) nên giữ một test chốt: các trang này phải được tải theo nhu cầu (lazyWithReload).
 */
describe('bundle chính', () => {
  const app = readFileSync(resolve(__dirname, 'App.tsx'), 'utf8');

  it.each(['LinkDetailPage', 'OverviewPage', 'AdminLinkDetailPage'])('%s được tải theo nhu cầu, không import tĩnh trong App.tsx', (page) => {
    expect(app).not.toMatch(new RegExp(`^import .* from '\\./pages/${page}'`, 'm'));
    expect(app).toMatch(new RegExp(`lazyWithReload\\(\\(\\) => import\\('\\./pages/${page}'\\)`));
  });

  it('không có trang nào được import tĩnh trong App.tsx lại import charts/StatsView/recharts', () => {
    const staticPages = [...app.matchAll(/^import .* from '\.\/pages\/(\w+)'/gm)].map((m) => m[1]);
    expect(staticPages.length).toBeGreaterThan(5); // regex còn nhận ra các import tĩnh
    for (const page of staticPages) {
      const source = readFileSync(resolve(__dirname, 'pages', `${page}.tsx`), 'utf8');
      expect(source, `${page} kéo recharts vào bundle chính`).not.toMatch(/components\/(charts|StatsView)|from 'recharts'/);
    }
  });
});
