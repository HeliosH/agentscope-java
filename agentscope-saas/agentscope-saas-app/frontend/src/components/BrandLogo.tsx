interface BrandLogoProps {
  className?: string;
}

export default function BrandLogo({ className = '' }: BrandLogoProps) {
  return (
    <span className={`brand-mark${className ? ` ${className}` : ''}`} aria-hidden="true">
      <img src="/chugou-mark.svg" alt="" />
    </span>
  );
}
