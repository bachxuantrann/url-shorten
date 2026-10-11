const CODE = /^[0-9A-Za-z_-]{3,32}$/;

/**
 * Lấy mã link từ thứ người dùng dán vào: link rút gọn đầy đủ ("https://sho.rt/abc1234", "sho.rt/abc1234")
 * hoặc chỉ mã ("abc1234"). Trả null nếu không ra một mã hợp lệ (3-32 ký tự chữ, số, "-" hoặc "_").
 */
export function extractShortCode(input: string): string | null {
  const text = input.trim();
  if (text === '') return null;

  // Chỉ mã: không có "/", ":" hay "."
  if (!/[/:.]/.test(text)) return CODE.test(text) ? text : null;

  try {
    const url = new URL(text.includes('://') ? text : `https://${text}`);
    const segments = url.pathname.split('/').filter(Boolean);
    // Link rút gọn chỉ có một đoạn đường dẫn: "sho.rt/abc/xyz" không phải link của chúng ta
    if (segments.length !== 1) return null;
    const code = decodeURIComponent(segments[0]);
    return CODE.test(code) ? code : null;
  } catch {
    return null;
  }
}
