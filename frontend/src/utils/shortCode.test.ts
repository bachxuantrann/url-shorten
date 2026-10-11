import { describe, expect, it } from 'vitest';
import { extractShortCode } from './shortCode';

describe('extractShortCode', () => {
  it('nhận mã trần', () => {
    expect(extractShortCode('abc1234')).toBe('abc1234');
    expect(extractShortCode('  My-alias_01  ')).toBe('My-alias_01');
  });

  it('lấy mã từ link rút gọn đầy đủ, có hoặc không có giao thức', () => {
    expect(extractShortCode('https://sho.rt/abc1234')).toBe('abc1234');
    expect(extractShortCode('http://localhost:8080/abc1234')).toBe('abc1234');
    expect(extractShortCode('sho.rt/abc1234')).toBe('abc1234');
    expect(extractShortCode('https://sho.rt/abc1234/')).toBe('abc1234');
    expect(extractShortCode('https://sho.rt/abc1234?utm=x#top')).toBe('abc1234');
  });

  it('từ chối thứ không phải mã hợp lệ', () => {
    expect(extractShortCode('')).toBeNull();
    expect(extractShortCode('   ')).toBeNull();
    expect(extractShortCode('ab')).toBeNull(); // quá ngắn
    expect(extractShortCode('a'.repeat(33))).toBeNull(); // quá dài
    expect(extractShortCode('bad code')).toBeNull();
    expect(extractShortCode('https://sho.rt/')).toBeNull(); // không có mã
    expect(extractShortCode('sho.rt')).toBeNull();
    expect(extractShortCode('https://sho.rt/abc/def')).toBeNull(); // nhiều đoạn: không phải link rút gọn
    expect(extractShortCode('https://sho.rt/%E0%A4%A')).toBeNull(); // %-encoding hỏng không làm đổ
  });
});
