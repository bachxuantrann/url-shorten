import { useCallback, useEffect, useMemo, useState } from 'react';
import { request } from '../api/client';
import { useI18n } from '../i18n/I18nProvider';
import {
  browserZone,
  formatServerDate,
  formatServerDateTime,
  inputToServer,
  isValidZone,
  lastDays as lastDaysIn,
  serverToInput,
  zoneLabel,
  zoneOffsetMinutes,
} from '../utils/timezone';

// Múi giờ máy chủ gần như không đổi trong một phiên: hỏi một lần rồi nhớ. Thất bại (null) không được nhớ để lần sau thử lại.
let cached: string | undefined;
let inflight: Promise<string | null> | null = null;

function loadServerZone(): Promise<string | null> {
  if (cached !== undefined) return Promise.resolve(cached);
  inflight ??= request<{ timezone: string }>('/api/v1/public/config')
    .then((c) => {
      // Mã vùng mà trình duyệt không hiểu (hiếm) coi như không biết: không quy đổi, thay vì ném lỗi ở mọi nơi dùng thời gian
      const zone = isValidZone(c.timezone) ? c.timezone : null;
      if (zone) cached = zone;
      return zone;
    })
    .catch(() => null)
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/** Chỉ dùng cho test: xoá múi giờ đã nhớ. */
export function resetServerZoneCache() {
  cached = undefined;
  inflight = null;
}

/**
 * Quy đổi thời gian giữa máy chủ và người dùng. Khi chưa biết (đang tải) hoặc không lấy được múi giờ máy chủ, coi như hai
 * bên cùng múi giờ (không quy đổi), nên giao diện không bao giờ hỏng chỉ vì endpoint cấu hình lỗi.
 * `ready` = đã có câu trả lời (kể cả thất bại): trang cần ngày "hôm nay" theo máy chủ nên đợi `ready` trước khi gọi API.
 */
export function useServerTime() {
  const { language } = useI18n();
  const [zone, setZone] = useState<string | null | undefined>(cached);

  useEffect(() => {
    if (zone !== undefined) return;
    let cancelled = false;
    void loadServerZone().then((z) => !cancelled && setZone(z));
    return () => {
      cancelled = true;
    };
  }, [zone]);

  const user = browserZone();
  const server = zone ?? user;

  const toInput = useCallback((iso: string | null) => serverToInput(iso, server, user), [server, user]);
  const fromInput = useCallback((value: string) => inputToServer(value, server, user), [server, user]);
  const format = useCallback((iso: string) => formatServerDateTime(iso, server, user, language), [server, user, language]);
  const formatDay = useCallback((iso: string) => formatServerDate(iso, server, user, language), [server, user, language]);
  const lastDays = useCallback((days: number) => lastDaysIn(days, server), [server]);

  return useMemo(
    () => ({
      ready: zone !== undefined,
      serverZone: server,
      userZone: user,
      /** Máy chủ và người dùng ở hai múi giờ khác nhau (theo độ lệch hiện tại). */
      differs: zoneOffsetMinutes(server) !== zoneOffsetMinutes(user),
      userZoneLabel: zoneLabel(user, language),
      serverZoneLabel: zoneLabel(server, language),
      toInput,
      fromInput,
      format,
      formatDay,
      lastDays,
    }),
    [zone, server, user, language, toInput, fromInput, format, formatDay, lastDays],
  );
}
