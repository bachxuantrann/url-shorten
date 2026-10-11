import { useEffect } from 'react';
import { BRAND } from '../brand';

/** Đặt tiêu đề tab cho trang hiện tại ("Tiêu đề · Tên sản phẩm"); trang chủ truyền null để chỉ hiện tên sản phẩm. */
export function useDocumentTitle(title: string | null) {
  useEffect(() => {
    document.title = title ? `${title} · ${BRAND.name}` : BRAND.name;
  }, [title]);
}
