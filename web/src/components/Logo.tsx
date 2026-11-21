interface LogoProps {
  size?: number;
  className?: string;
}

/** Brand mark: a speech bubble with a sound wave, in Deep Clinical Teal. */
export function LogoMark({ size = 36, className }: LogoProps) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 48 48"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <rect width="48" height="48" rx="14" fill="#0F766E" />
      <path
        d="M14 13h20a5 5 0 0 1 5 5v10a5 5 0 0 1-5 5h-9l-6.5 5.2c-.66.53-1.5.05-1.5-.78V33h-3a5 5 0 0 1-5-5V18a5 5 0 0 1 5-5Z"
        fill="#fff"
      />
      <g stroke="#0F766E" strokeWidth="2.6" strokeLinecap="round">
        <path d="M17.5 21.5v4" />
        <path d="M22 18.5v10" />
        <path d="M26.5 20v7" />
        <path d="M31 22v3" />
      </g>
    </svg>
  );
}
