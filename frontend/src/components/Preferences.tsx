import { Languages, Monitor, Moon, Sun } from 'lucide-react';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme, type ThemeMode } from '../theme/ThemeProvider';
import { Button } from './ui/Button';
import { Menu, MenuItem, MenuSeparator } from './ui/Menu';

const THEME_ICONS = { light: Sun, dark: Moon, system: Monitor } as const;

/** Nút đổi giao diện (sáng/tối/hệ thống) và ngôn ngữ, dùng ở thanh trên của mọi bố cục. */
export function Preferences() {
  const { t, language, setLanguage } = useI18n();
  const { mode, setMode } = useTheme();
  const ThemeIcon = THEME_ICONS[mode];

  return (
    <>
      <Menu
        trigger={(props) => (
          <Button variant="ghost" icon aria-label={t('theme.toggle')} title={t('theme.toggle')} {...props}>
            <ThemeIcon size={18} aria-hidden="true" />
          </Button>
        )}
      >
        {(close) =>
          (['light', 'dark', 'system'] as ThemeMode[]).map((m) => {
            const Icon = THEME_ICONS[m];
            return (
              <MenuItem
                key={m}
                icon={<Icon size={16} aria-hidden="true" />}
                onSelect={() => {
                  setMode(m);
                  close();
                }}
              >
                {t(`theme.${m}`)}
                {mode === m && <span className="sr-only"> ✓</span>}
              </MenuItem>
            );
          })
        }
      </Menu>
      <Menu
        trigger={(props) => (
          <Button variant="ghost" size="md" aria-label={t('lang.label')} {...props}>
            <Languages size={18} aria-hidden="true" />
            <span className="hide-sm">{language.toUpperCase()}</span>
          </Button>
        )}
      >
        {(close) => (
          <>
            <MenuItem onSelect={() => { setLanguage('vi'); close(); }}>{t('lang.vi')}</MenuItem>
            <MenuSeparator />
            <MenuItem onSelect={() => { setLanguage('en'); close(); }}>{t('lang.en')}</MenuItem>
          </>
        )}
      </Menu>
    </>
  );
}
