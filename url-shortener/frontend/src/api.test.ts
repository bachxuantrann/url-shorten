import { describe, expect, it } from 'vitest'
import { toQuery, toServerDateTime } from './api'

describe('toQuery', () => {
  it('bỏ giá trị rỗng và mã hoá ký tự đặc biệt', () => {
    expect(toQuery({ keyword: 'a b&c', status: '', page: 0, x: undefined })).toBe('?keyword=a+b%26c&page=0')
  })
  it('trả chuỗi rỗng khi không có tham số', () => {
    expect(toQuery({ a: '', b: null })).toBe('')
  })
})

describe('toServerDateTime', () => {
  it('thêm giây cho giá trị datetime-local', () => {
    expect(toServerDateTime('2026-10-11T08:30')).toBe('2026-10-11T08:30:00')
  })
  it('rỗng -> undefined', () => {
    expect(toServerDateTime('')).toBeUndefined()
  })
})
