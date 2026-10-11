import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { BRAND } from './brand';

// index.html là HTML tĩnh (trình thu thập và bản xem trước khi chia sẻ link không chạy JavaScript), nên các thẻ trong đó
// không tự theo brand.ts hay bản dịch: các test này giữ chúng đúng.
const root = resolve(__dirname, '..');
const html = readFileSync(resolve(root, 'index.html'), 'utf8');
const attr = (re: RegExp) => html.match(re)?.[1];

describe('index.html', () => {
  it('khai báo favicon và file đó có thật trong public/', () => {
    const href = attr(/<link rel="icon"[^>]*href="([^"]+)"/);

    expect(href).toBe('/favicon.svg');
    expect(existsSync(resolve(root, 'public', href!.slice(1)))).toBe(true);
    expect(readFileSync(resolve(root, 'public/favicon.svg'), 'utf8')).toContain('<svg');
  });

  it('có description, theme-color cho cả sáng và tối, và thẻ Open Graph/Twitter', () => {
    expect(attr(/<meta name="description" content="([^"]+)"/)).toBeTruthy();
    expect(html).toMatch(/<meta name="theme-color"[^>]*prefers-color-scheme: light/);
    expect(html).toMatch(/<meta name="theme-color"[^>]*prefers-color-scheme: dark/);
    for (const property of ['og:type', 'og:site_name', 'og:title', 'og:description']) {
      expect(html, property).toContain(`property="${property}"`);
    }
    expect(html).toContain('name="twitter:card"');
  });

  it('tên sản phẩm trong HTML tĩnh khớp brand.ts (đổi tên ở một nơi mà quên nơi kia sẽ lộ ở đây)', () => {
    expect(attr(/<title>([^<]+)<\/title>/)).toBe(BRAND.name);
    expect(attr(/property="og:site_name" content="([^"]+)"/)).toBe(BRAND.name);
    expect(attr(/<meta name="description" content="([^"]+)"/)).toContain(BRAND.name);
    expect(attr(/property="og:title" content="([^"]+)"/)).toContain(BRAND.name);
  });

  it('lang mặc định là vi và có thẻ viewport', () => {
    expect(html).toMatch(/<html lang="vi">/);
    expect(html).toContain('name="viewport"');
  });
});

describe('robots.txt', () => {
  const robots = readFileSync(resolve(root, 'public/robots.txt'), 'utf8');

  it('chặn khu vực riêng tư và API nhưng không chặn trang chủ', () => {
    for (const path of ['/api/', '/dashboard', '/settings', '/links/', '/unlock/', '/unavailable/']) {
      expect(robots, path).toContain(`Disallow: ${path}`);
    }
    expect(robots).not.toMatch(/Disallow:\s*\/\s*$/m);
  });
});
