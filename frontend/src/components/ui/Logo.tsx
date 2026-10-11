import { Link } from 'react-router-dom';
import { Link2 } from 'lucide-react';
import { BRAND } from '../../brand';

/** Logo + tên sản phẩm, bấm vào về trang chủ. */
export function Logo({ to = '/' }: { to?: string }) {
  return (
    <Link to={to} className="brand">
      <span className="brand-mark" aria-hidden="true">
        <Link2 size={18} strokeWidth={2.5} />
      </span>
      <span>{BRAND.name}</span>
    </Link>
  );
}
