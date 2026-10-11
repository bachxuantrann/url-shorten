/**
 * Chép văn bản vào clipboard, trả về thành công hay không (không bao giờ ném lỗi).
 *
 * navigator.clipboard.writeText bị từ chối trong nhiều ngữ cảnh (trang không có focus, khung nhúng, quyền bị chặn, trang
 * HTTP không an toàn); khi đó thử cách cũ bằng một ô văn bản ẩn + execCommand('copy') vốn không đòi quyền clipboard.
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch {
    /* rơi xuống cách dự phòng */
  }
  return legacyCopy(text);
}

function legacyCopy(text: string): boolean {
  const area = document.createElement('textarea');
  area.value = text;
  area.setAttribute('readonly', '');
  // Ẩn khỏi màn hình nhưng vẫn chọn được; position: fixed để không làm trang cuộn nhảy
  area.style.cssText = 'position:fixed;top:0;left:-9999px;opacity:0';
  document.body.appendChild(area);
  const previous = document.activeElement as HTMLElement | null;
  try {
    area.select();
    return typeof document.execCommand === 'function' && document.execCommand('copy');
  } catch {
    return false;
  } finally {
    document.body.removeChild(area);
    previous?.focus?.();
  }
}
