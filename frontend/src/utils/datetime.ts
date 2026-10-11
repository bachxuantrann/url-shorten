/** Bỏ giao thức và dấu / cuối để hiển thị link ngắn gọn (https://sho.rt/abc/ -> sho.rt/abc). */
export function displayUrl(url: string): string {
  return url.replace(/^https?:\/\//, '').replace(/\/$/, '');
}
