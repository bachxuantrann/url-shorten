import { describe, expect, it } from 'vitest';
import { messages } from './messages';

const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe('bản dịch', () => {
  const keys = Object.keys(messages.en);

  it('tiếng Việt và tiếng Anh có đúng cùng bộ khoá', () => {
    expect(Object.keys(messages.vi).sort()).toEqual([...keys].sort());
  });

  it('không có chuỗi rỗng', () => {
    for (const lang of ['en', 'vi'] as const) {
      for (const key of keys) {
        expect(messages[lang][key as keyof typeof messages.en].trim(), `${lang}:${key}`).not.toBe('');
      }
    }
  });

  it('mỗi khoá dùng cùng các tham số {x} ở hai ngôn ngữ (thiếu tham số sẽ hiện nguyên "{count}" cho người dùng)', () => {
    for (const key of keys) {
      const k = key as keyof typeof messages.en;
      expect(placeholders(messages.vi[k]), key).toEqual(placeholders(messages.en[k]));
    }
  });

  it('bản dịch tiếng Việt không bị bỏ nguyên tiếng Anh ở các câu dài', () => {
    // Câu dài giống hệt bản tiếng Anh gần như chắc chắn là quên dịch (tên riêng/viết tắt ngắn thì được phép giống)
    for (const key of keys) {
      const k = key as keyof typeof messages.en;
      if (messages.en[k].length > 40) expect(messages.vi[k], key).not.toBe(messages.en[k]);
    }
  });
});
