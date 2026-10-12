import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider, useI18n } from './I18nProvider';

function Probe({ count }: { count: number | string }) {
  const { t } = useI18n();
  return (
    <>
      <p data-testid="links">{t('links.count', { count })}</p>
      <p data-testid="clicks">{t('link.clicks', { count })}</p>
      <p data-testid="users">{t('admin.users.count', { count })}</p>
    </>
  );
}

const renderProbe = (count: number | string, lang: 'en' | 'vi') => {
  localStorage.setItem('lang', lang);
  render(
    <I18nProvider>
      <Probe count={count} />
    </I18nProvider>,
  );
};

describe('số ít / số nhiều', () => {
  beforeEach(() => localStorage.clear());

  it('tiếng Anh: đúng 1 thì dùng số ít, còn lại (kể cả 0) dùng số nhiều', () => {
    renderProbe(1, 'en');
    expect(screen.getByTestId('links')).toHaveTextContent('1 link');
    expect(screen.getByTestId('links')).not.toHaveTextContent('links');
    expect(screen.getByTestId('clicks')).toHaveTextContent(/^1 click$/);
    expect(screen.getByTestId('users')).toHaveTextContent(/^1 user$/);
  });

  it.each([0, 2, 11, 1234])('tiếng Anh: %i dùng số nhiều', (n) => {
    renderProbe(n, 'en');
    expect(screen.getByTestId('links')).toHaveTextContent(`${n} links`);
    expect(screen.getByTestId('clicks')).toHaveTextContent(`${n} clicks`);
  });

  it('count đã định dạng thành chuỗi (như các trang đang truyền) vẫn nhận ra số ít', () => {
    renderProbe('1', 'en');
    expect(screen.getByTestId('links')).toHaveTextContent(/^1 link$/);
  });

  it('chuỗi "1.000" hay "10" không bị nhầm là số ít', () => {
    renderProbe('1.000', 'en');
    expect(screen.getByTestId('links')).toHaveTextContent('1.000 links');
  });

  it('tiếng Việt không chia số ít/nhiều: cùng một chữ cho mọi số', () => {
    renderProbe(1, 'vi');
    expect(screen.getByTestId('clicks')).toHaveTextContent('1 lượt click');
    expect(screen.getByTestId('users')).toHaveTextContent('1 người dùng');
  });
});
