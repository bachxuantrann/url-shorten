import { useEffect, useState } from 'react';

/** Giá trị trễ {@code ms} mili-giây sau lần đổi cuối (để ô tìm kiếm không gọi API mỗi phím bấm). */
export function useDebounced<T>(value: T, ms = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return debounced;
}
